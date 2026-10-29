package com.jarvys.agent.tasks

import android.content.Context
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreTool
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.R
import com.jarvys.agent.ToolSpec
import com.jarvys.agent.connectors.ConnectorRegistry
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** The single registration and behavior boundary for interactive scheduled-task management. */
class TaskManagementTools @JvmOverloads constructor(
    context: Context,
    private val sessionId: String,
    private val creatorToolNames: List<String>,
    private val repository: TaskRepository = TaskRepository(context.applicationContext),
    private val ledger: TaskRunLedger = TaskRunLedger(context.applicationContext),
    private val clock: Clock = Clock.systemUTC(),
    private val zoneProvider: TaskZoneProvider = TaskZoneProvider { ZoneId.systemDefault() },
    private val connectorRegistry: ConnectorRegistry = ConnectorRegistry.get(context.applicationContext),
    private val manualRunEnqueue: (Context, String, String, ScheduledTask?) -> Unit =
        { app, taskId, occurrenceId, expectedTask ->
            TaskManualRunScheduler.enqueue(app, taskId, occurrenceId, expectedTask)
        },
    private val manualRunIsActive: (Context, String) -> Boolean = TaskManualRunScheduler::isActive,
    private val taskCapabilityNames: List<String> = creatorToolNames,
    private val pendingActions: TaskPendingActionStore = TaskPendingActionStore(context.applicationContext),
    private val creatorSkillIds: List<String> = emptyList(),
) {
    private val app = context.applicationContext
    private val creatorMessageId: String = runCatching {
        com.jarvys.agent.LocalRunStore(app).latestUserMessageId(sessionId)
    }.getOrDefault("")
    private val calculator = ScheduleCalculator(clock, zoneProvider)
    private val schedulePresentation = TaskSchedulePresentation(app, zoneProvider)
    private val safeCreatorTools: List<String> by lazy {
        val creatorCeiling = creatorToolNames.toSet()
        taskCapabilityNames.distinct().filter { it in creatorCeiling }.sorted()
    }

    fun tools(): List<CoreTool> = listOf(
        ManagementTool(SCHEDULE_TASK, "Create a scheduled task from name, instruction, schedule, delivery, web, and valid_until.",
            scheduleSchema(), listOf("name", "instruction", "schedule"), ::scheduleTask),
        ManagementTool(LIST_TASKS, "List scheduled tasks and the current local clock context.",
            objectSchema(mapOf("include_paused" to booleanProperty()), emptyList()), emptyList(), ::listTasks),
        ManagementTool(UPDATE_TASK, "Apply a patch to a scheduled task using task_id and patch.revision.",
            updateSchema(), listOf("task_id", "patch"), ::updateTask),
        ManagementTool(CANCEL_TASK, "Cancel a scheduled task using task_id.",
            objectSchema(mapOf("task_id" to stringProperty()), listOf("task_id")), listOf("task_id"), ::cancelTask),
        ManagementTool(TASK_RUNS, "Read scheduled task run history for task_id, newest first.",
            objectSchema(linkedMapOf(
                "task_id" to stringProperty(),
                "limit" to mapOf("type" to "integer", "minimum" to 1),
            ), listOf("task_id")), listOf("task_id"), ::taskRuns),
        ManagementTool(RUN_TASK, "Enqueue a manual run for task_id at its current revision; returns run_id for task_runs lookup.",
            objectSchema(linkedMapOf(
                "task_id" to stringProperty(),
                "revision" to mapOf("type" to "integer", "minimum" to 1),
            ), listOf("task_id", "revision")), listOf("task_id", "revision"), ::runTask),
    )

    private fun scheduleTask(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
        token.throwIfCancelled()
        rejectUnknown(arguments, setOf("name", "instruction", "schedule", "delivery", "web", "writes", "valid_until", "delete_after_run"))
        val name = text(arguments, "name")
        val instruction = text(arguments, "instruction", trim = false)
        val scheduleInput = objectValue(arguments["schedule"], "schedule")
        val zone = parseZone(scheduleInput["zone"] as? String)
        val schedule = parseSchedule(scheduleInput, zone)
        val delivery = parseDelivery(arguments["delivery"] as? String)
        val wantsWeb = boolean(arguments, "web", false)
        val writes = parseWriteScope(arguments["writes"] as? String ?: TaskToolScope.LEGACY_WRITE_SCOPE)
        val allowedCreatorTools = safeCreatorTools
        if (wantsWeb && allowedCreatorTools.none { it in WEB_TOOL_NAMES }) {
            throw IllegalArgumentException("Web search tools are not available in this chat scope")
        }
        val taskTools = allowedCreatorTools.filter { wantsWeb || it !in WEB_TOOL_NAMES }
        val validUntil = parseValidUntil(arguments["valid_until"], zone)
        val deleteAfterRun = boolean(arguments, "delete_after_run", false)
        val candidate = ScheduledTask(name = name.trim(), instruction = instruction.trim(), schedule = schedule,
            timePrecision = TaskTimePrecision.APPROXIMATE, validUntil = validUntil,
            deleteAfterRun = deleteAfterRun,
            // Scheduled runs rebuild read_skill and skill search from this saved skill ceiling.
            toolScope = TaskToolScope("LISTED", taskTools, wantsWeb, writes,
                if ("read_skill" in allowedCreatorTools) creatorSkillIds.distinct().sorted() else emptyList()),
            delivery = delivery,
            createdBy = createdBy(), creatorToolNames = allowedCreatorTools,
            createdAt = clock.millis())
        ScheduledTaskValidation.validate(candidate)
        val now = clock.millis()
        require(validUntil == null || validUntil > now) { app.getString(R.string.task_management_valid_until_past) }
        if (schedule is TaskSchedule.At) {
            val at = calculator.resolveLocalDateTime(schedule.localDateTime, schedule.zone)
            require(at > now) { app.getString(R.string.task_management_at_past) }
            require(validUntil == null || at < validUntil) { app.getString(R.string.task_management_valid_until_before_run) }
        }
        val duplicate = repository.list().firstOrNull {
            it.name == candidate.name && it.instruction == candidate.instruction &&
                it.deleteAfterRun == candidate.deleteAfterRun && sameSchedule(it.schedule, candidate.schedule)
        }
        if (duplicate != null) {
            token.throwIfCancelled()
            return success(taskResult(duplicate, "existing"))
        }
        val createResult = repository.createPreparedIfEquivalent(candidate) { token.throwIfCancelled() }
        return success(taskResult(createResult.task, if (createResult.created) "created" else "existing"))
    }

    private fun listTasks(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
        token.throwIfCancelled()
        rejectUnknown(arguments, setOf("include_paused"))
        val includePaused = boolean(arguments, "include_paused", false)
        val zone = zoneProvider.deviceZone()
        val rows = repository.list().asSequence()
                .filter { includePaused || it.state != TaskState.Paused }
                .map { task ->
                    val next = task.nextRunAt?.let { schedulePresentation.localTime(it, taskZone(task)) }
                    JSONObject().put("id", task.id).put("name", task.name)
                        .put("state", stateName(task.state))
                        .put("schedule_human", scheduleHuman(task.schedule))
                        .put("next_run_local", next ?: JSONObject.NULL)
                        .put("revision", task.revision)
                        .put("last_status", task.lastRun?.status ?: JSONObject.NULL)
                }.toList()
            val result = JSONObject().put("tasks", JSONArray(rows))
                .put("now_local", schedulePresentation.localTime(clock.millis(), zone))
                .put("zone", zone.id).put("offset", Instant.ofEpochMilli(clock.millis()).atZone(zone).offset.id)
        token.throwIfCancelled()
        return success(result)
    }

    private fun updateTask(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
        token.throwIfCancelled()
        rejectUnknown(arguments, setOf("task_id", "patch"))
        val id = text(arguments, "task_id")
        val patch = objectValue(arguments["patch"], "patch")
        val current = repository.get(id) ?: throw IllegalArgumentException("Task $id was not found")
        val revision = integer(patch["revision"], "patch.revision")
        if (revision != current.revision) throw StaleTaskRevisionException(
            "Task $id revision changed: expected $revision, found ${current.revision}")
        val changed = applyPatch(current, patch)
        ScheduledTaskValidation.validate(changed)
        if (changed == current) return success(taskResult(current, "unchanged"))
        val beforeWrite = { token.throwIfCancelled() }
        val saved = when {
                changed.state == TaskState.Paused && current.state != TaskState.Paused &&
                        changed.copy(state = current.state) == current -> repository.pause(id, revision, beforeWrite, current)
                changed.state == TaskState.Active && current.state != TaskState.Active &&
                        changed.copy(state = current.state) == current -> repository.resume(id, revision, beforeWrite, current)
                else -> repository.update(changed, revision, beforeWrite, current)
        }
        return success(taskResult(saved, "updated"))
    }

    private fun cancelTask(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
        token.throwIfCancelled()
        rejectUnknown(arguments, setOf("task_id"))
        val id = text(arguments, "task_id")
        val task = repository.get(id) ?: throw IllegalArgumentException("Task $id was not found")
        val hasHistory = ledger.forTask(id).isNotEmpty() || task.lastRun != null
        repository.delete(id, task.revision, beforeWrite = { token.throwIfCancelled() }, expectedTask = task)
        return success(JSONObject().put("status", "cancelled").put("task_id", id)
            .put("history", if (hasHistory) "kept" else "none")
            .put("task", taskCard(task, "cancelled", null)))
    }

    private fun runTask(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
        token.throwIfCancelled()
        rejectUnknown(arguments, setOf("task_id", "revision"))
        val id = text(arguments, "task_id")
        val expectedRevision = integer(arguments["revision"], "revision")
        val task = repository.get(id) ?: throw IllegalArgumentException("Task $id was not found")
        if (expectedRevision != task.revision) throw StaleTaskRevisionException(
            "Task $id revision changed: expected $expectedRevision, found ${task.revision}")
        require(pendingActions.stateForTask(task.id, task.revision, task.state) != TaskState.AwaitingUser) {
            "Task $id is awaiting approval for a pending action"
        }
        if (manualRunIsActive(app, id)) throw IllegalStateException("Task $id already has a manual run in progress")
        val runId = java.util.UUID.randomUUID().toString()
        val occurrenceId = "manual:$id:$runId"
        token.throwIfCancelled()
        manualRunEnqueue(app, id, occurrenceId, task)
        ledger.appendIfAbsent(TaskRunRecord(task.id, runId, clock.millis(), clock.millis(),
            status = "QUEUED", deliveryStatus = "NOT_APPLICABLE", manualOccurrenceId = occurrenceId))
        return success(JSONObject().put("status", "queued").put("task_id", id)
            .put("run_id", runId).put("occurrence_id", occurrenceId).put("revision", task.revision))
    }

    private fun taskRuns(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
        token.throwIfCancelled()
        rejectUnknown(arguments, setOf("task_id", "limit"))
        val id = text(arguments, "task_id")
        val limit = arguments["limit"]?.let { integer(it, "limit").also { value ->
            require(value > 0) { "limit must be positive" }
        } }
        val zone = zoneForTask(id)
        val runs = ledger.forTask(id).sortedWith(compareByDescending<TaskRunRecord> { it.startedAt }
                .thenByDescending { it.finishedAt ?: Long.MIN_VALUE }).let {
                if (limit == null) it else it.take(minOf(limit, it.size.toLong()).toInt())
            }
            val array = JSONArray(runs.map { run -> JSONObject()
                .put("task_id", run.taskId).put("run_id", run.runId).put("occurrence_id", run.idempotencyKey)
                .put("scheduled_for", schedulePresentation.localTime(run.scheduledFor, zone))
                .put("started_at", schedulePresentation.localTime(run.startedAt, zone))
                .put("finished_at", run.finishedAt?.let { schedulePresentation.localTime(it, zone) } ?: JSONObject.NULL)
                .put("status", run.status).put("delivery_status", run.deliveryStatus)
                .put("reason", run.reason ?: JSONObject.NULL).put("notified", run.notified)
                .put("delivered_result", run.deliveredResult ?: JSONObject.NULL)
                .put("conversation_message_id", run.conversationMessageId ?: JSONObject.NULL)
                .put("cause_code", run.causeCode?.code ?: JSONObject.NULL)
                .put("model", run.model ?: JSONObject.NULL).put("usage_tokens", run.usageTokens ?: JSONObject.NULL)
                .put("tools_called", JSONArray(run.toolsCalled.map { call -> JSONObject()
                    .put("name", call.name).put("write", call.write).put("outcome", call.outcome) }))
            })
        token.throwIfCancelled()
        return success(JSONObject().put("task_id", id).put("runs", array))
    }

    private fun applyPatch(task: ScheduledTask, patch: Map<String, Any>): ScheduledTask {
        val allowed = setOf("revision", "name", "instruction", "schedule", "delivery", "web", "tools", "writes", "valid_until", "state", "delete_after_run")
        val unknown = patch.keys - allowed
        require(unknown.isEmpty()) { "Unexpected patch field(s): ${unknown.sorted().joinToString()}" }
        var schedule = task.schedule
        if (patch.containsKey("schedule")) {
            val map = objectValue(patch["schedule"], "patch.schedule")
            schedule = parseSchedule(map, parseZone(map["zone"] as? String))
        }
        val requestedTools = if (patch.containsKey("tools")) stringList(patch["tools"], "patch.tools") else task.toolScope.tools
        val web = if (patch.containsKey("web")) boolean(patch, "web", task.toolScope.web) else task.toolScope.web
        val tools = if (web) requestedTools else requestedTools.filterNot { it in WEB_TOOL_NAMES }
        val allowedCreator = task.creatorToolNames.toSet()
        require(tools.all { it in allowedCreator }) { "Task scope cannot exceed creatorToolNames" }
        val writes = if (patch.containsKey("writes")) parseWriteScope(patch["writes"] as? String)
            else task.toolScope.writes
        require(!web || tools.any { it in WEB_TOOL_NAMES }) { "Web scope requires a creator-approved web tool" }
        val delivery = parseDelivery(patch["delivery"] as? String ?: task.delivery.name.lowercase(Locale.ROOT))
        val validUntil = if (patch.containsKey("valid_until")) {
            if (patch["valid_until"] == JSONObject.NULL) null else parseValidUntil(patch["valid_until"], scheduleTaskZone(schedule))
        } else task.validUntil
        if (schedule is TaskSchedule.At) require(calculator.resolveLocalDateTime(schedule.localDateTime, schedule.zone) > clock.millis()) {
            app.getString(R.string.task_management_at_past)
        }
        val state = when ((patch["state"] as? String)?.lowercase(Locale.ROOT)) {
            null -> task.state
            "active", "resume" -> TaskState.Active
            "paused", "pause" -> TaskState.Paused
            else -> throw IllegalArgumentException("patch.state must be active or paused")
        }
        return task.copy(
            name = if (patch.containsKey("name")) text(patch, "name").trim() else task.name,
            instruction = if (patch.containsKey("instruction")) text(patch, "instruction", false).trim() else task.instruction,
            schedule = schedule, validUntil = validUntil,
            deleteAfterRun = boolean(patch, "delete_after_run", task.deleteAfterRun),
            toolScope = task.toolScope.copy(tools = tools, web = web, writes = writes), delivery = delivery, state = state,
        )
    }

    private fun parseSchedule(values: Map<String, Any>, zone: TaskZone): TaskSchedule {
        val type = text(values, "type").lowercase(Locale.ROOT)
        val allowed = when (type) {
            "at" -> setOf("type", "local_date_time", "zone")
            "every" -> setOf("type", "interval", "unit", "zone")
            "daily" -> setOf("type", "time", "zone")
            "weekly" -> setOf("type", "time", "days", "days_of_week", "zone")
            "monthly" -> setOf("type", "time", "day_of_month", "zone")
            else -> setOf("type")
        }
        rejectUnknown(values, allowed, "schedule")
        return when (type) {
            "at" -> {
                val local = text(values, "local_date_time")
                try { LocalDateTime.parse(local) } catch (_: Exception) {
                    throw IllegalArgumentException("schedule.local_date_time must be ISO local date-time")
                }
                ScheduledTaskValidation.validateZone(zone)
                TaskSchedule.At(local, zone)
            }
            "every" -> {
                val interval = integer(values["interval"], "schedule.interval")
                val unit = (values["unit"] as? String ?: "minutes").lowercase(Locale.ROOT)
                val multiplier = when (unit) {
                    "minute", "minutes" -> 60_000L
                    "hour", "hours" -> 3_600_000L
                    else -> throw IllegalArgumentException("schedule.unit must be minutes or hours")
                }
                val millis = try { Math.multiplyExact(interval, multiplier) }
                catch (_: ArithmeticException) { throw IllegalArgumentException("schedule.interval is out of range") }
                require(millis >= ScheduledTaskValidation.MIN_INTERVAL_MILLIS) {
                    app.getString(R.string.task_management_interval_error)
                }
                TaskSchedule.Every(millis, clock.millis())
            }
            "daily" -> TaskSchedule.Calendar(text(values, "time"), CalendarCadence.DAILY, zone = zone)
            "weekly" -> TaskSchedule.Calendar(text(values, "time"), CalendarCadence.WEEKLY,
                daysOfWeek = intSet(values["days"] ?: values["days_of_week"], "schedule.days"), zone = zone)
            "monthly" -> TaskSchedule.Calendar(text(values, "time"), CalendarCadence.MONTHLY,
                dayOfMonth = integer(values["day_of_month"], "schedule.day_of_month").toInt(), zone = zone)
            else -> throw IllegalArgumentException("schedule.type must be at, every, daily, weekly, or monthly; cron is not supported")
        }.also { validateTaskSchedule(it) }
    }

    private fun validateTaskSchedule(schedule: TaskSchedule) {
        val dummy = ScheduledTask(name = "validation", instruction = "validation", schedule = schedule, createdAt = 0L)
        ScheduledTaskValidation.validate(dummy)
    }

    private fun parseValidUntil(raw: Any?, zone: TaskZone): Long? {
        if (raw == null) return null
        val value = raw as? String ?: throw IllegalArgumentException("valid_until must be an ISO local date-time string")
        val local = try { LocalDateTime.parse(value) } catch (_: Exception) {
            throw IllegalArgumentException("valid_until must be an ISO local date-time string")
        }
        return calculator.resolveLocalDateTime(local.toString(), zone)
    }

    private fun parseZone(value: String?): TaskZone = when {
        value == null || value == "FOLLOW_DEVICE" -> TaskZone.FollowDevice
        else -> TaskZone.Iana(value).also(ScheduledTaskValidation::validateZone)
    }

    private fun parseDelivery(value: String?): TaskDelivery = when (value?.lowercase(Locale.ROOT)) {
        null, "always" -> TaskDelivery.ALWAYS
        "only_if_notable" -> TaskDelivery.ONLY_IF_NOTABLE
        else -> throw IllegalArgumentException("delivery must be always or only_if_notable")
    }

    private fun parseWriteScope(value: String?): String {
        val normalized = value?.uppercase(Locale.ROOT) ?: TaskToolScope.LEGACY_WRITE_SCOPE
        require(normalized in TaskWriteScope.values().map { it.name }) {
            "writes must be ALLOW, DEFER, or DENY"
        }
        return normalized
    }

    private fun scheduleHuman(schedule: TaskSchedule): String = schedulePresentation.scheduleHuman(schedule)

    private fun taskResult(task: ScheduledTask, status: String): JSONObject {
        val next = task.nextRunAt?.let { schedulePresentation.localTime(it, taskZone(task)) }
        return JSONObject().put("status", status).put("task_id", task.id)
            .put("next_run_local", next ?: JSONObject.NULL).put("zone", taskZone(task).id)
            .put("revision", task.revision).put("task", taskCard(task, status, next))
    }

    private fun taskCard(task: ScheduledTask, status: String, next: String?): JSONObject = JSONObject()
        .put("kind", "scheduled_task").put("id", task.id).put("name", task.name)
        .put("schedule_human", scheduleHuman(task.schedule))
        .put("next_run_local", next ?: JSONObject.NULL)
        .put("state", stateName(task.state)).put("status", status)
        .put("revision", task.revision)

    private fun zoneForTask(taskId: String): ZoneId = repository.get(taskId)?.let(::taskZone) ?: zoneProvider.deviceZone()
    private fun taskZone(task: ScheduledTask): ZoneId = schedulePresentation.zoneFor(task.schedule)
    private fun scheduleTaskZone(schedule: TaskSchedule): TaskZone = when (schedule) {
        is TaskSchedule.At -> schedule.zone
        is TaskSchedule.Calendar -> schedule.zone
        is TaskSchedule.Every -> TaskZone.FollowDevice
    }
    private fun stateName(state: TaskState): String = when (state) {
        TaskState.Active -> "ACTIVE"
        TaskState.Paused -> "PAUSED"
        TaskState.Done -> "DONE"
        is TaskState.NeedsAttention -> "NEEDS_ATTENTION:${state.reason}"
        TaskState.AwaitingUser -> "AWAITING_USER"
    }
    private fun createdBy(): String = if (creatorMessageId.isBlank()) "AGENT_CHAT:$sessionId"
        else "AGENT_CHAT:$sessionId:$creatorMessageId"
    private fun sameSchedule(left: TaskSchedule, right: TaskSchedule): Boolean = when {
        left is TaskSchedule.Every && right is TaskSchedule.Every -> left.intervalMillis == right.intervalMillis
        else -> left == right
    }

    private fun success(value: JSONObject) = CoreToolResult.success(value.toString())

    private fun text(values: Map<String, Any>, key: String, trim: Boolean = true): String {
        val value = values[key] as? String ?: throw IllegalArgumentException("$key must be a string")
        val result = if (trim) value.trim() else value
        require(result.isNotBlank()) { "$key must not be empty" }
        return result
    }
    private fun objectValue(value: Any?, path: String): Map<String, Any> {
        val map = value as? Map<*, *> ?: throw IllegalArgumentException("$path must be an object")
        require(map.keys.all { it is String }) { "$path keys must be strings" }
        return map.entries.associate { it.key as String to (it.value ?: JSONObject.NULL) }
    }
    private fun number(value: Any?, path: String): Number = value as? Number
        ?: throw IllegalArgumentException("$path must be a number")
    private fun integer(value: Any?, path: String): Long {
        val numeric = number(value, path)
        val double = numeric.toDouble()
        require(double.isFinite() && double == double.toLong().toDouble()) { "$path must be an integer" }
        return numeric.toLong()
    }
    private fun boolean(values: Map<String, Any>, key: String, default: Boolean): Boolean =
        if (!values.containsKey(key)) default else values[key] as? Boolean
            ?: throw IllegalArgumentException("$key must be a boolean")
    private fun stringList(value: Any?, path: String): List<String> = (value as? List<*>)
        ?.mapIndexed { index, item -> item as? String ?: throw IllegalArgumentException("$path[$index] must be a string") }
        ?: throw IllegalArgumentException("$path must be an array")
    private fun intSet(value: Any?, path: String): Set<Int> = (value as? List<*>)
        ?.mapIndexed { index, item -> integer(item, "$path[$index]").toInt() }?.toSet()
        ?: throw IllegalArgumentException("$path must be an array of ISO weekday numbers")

    private fun rejectUnknown(values: Map<String, Any>, allowed: Set<String>, path: String = "arguments") {
        val unknown = values.keys - allowed
        require(unknown.isEmpty()) { "Unexpected $path field(s): ${unknown.sorted().joinToString()}" }
    }

    private inner class ManagementTool(
        private val toolName: String,
        private val description: String,
        private val schema: Map<String, Any>,
        required: List<String>,
        private val handler: (Map<String, Any>, CancellationToken) -> CoreToolResult,
    ) : CoreTool {
        private val requiredFields = required.toList()
        override fun executionMode() = CoreTool.ExecutionMode.SEQUENTIAL
        override fun effectClass() = if (toolName in setOf(LIST_TASKS, TASK_RUNS)) CoreTool.EffectClass.READ else CoreTool.EffectClass.WRITE
        override fun declaration() = ToolSpec(toolName, "jarvys/tasks", description, "conversation",
            ToolSpec.Status.IMPLEMENTED, emptyMap(), requiredFields, schema)
        override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
            token.throwIfCancelled()
            return handler(arguments, token)
        }
    }

    private fun scheduleSchema(): Map<String, Any> {
        val scheduleProps = linkedMapOf<String, Any>(
            "type" to mapOf("type" to "string", "enum" to listOf("at", "every", "daily", "weekly", "monthly")),
            "local_date_time" to mapOf("type" to "string", "description" to "ISO local date-time, without a UTC offset."),
            "time" to mapOf("type" to "string", "description" to "24-hour local wall time, HH:mm."),
            "days" to mapOf("type" to "array", "description" to "ISO weekdays: 1=Monday through 7=Sunday.",
                "items" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 7)),
            "day_of_month" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 31),
            "interval" to mapOf("type" to "integer", "minimum" to 1, "description" to "Periodic value; resulting interval must be at least 15 minutes."),
            "unit" to mapOf("type" to "string", "enum" to listOf("minutes", "hours")),
            "zone" to mapOf("type" to "string", "description" to "IANA zone ID; omit or use FOLLOW_DEVICE for the device zone."),
        )
        val schedule = objectSchema(scheduleProps, listOf("type"))
        return objectSchema(linkedMapOf(
            "name" to stringProperty(), "instruction" to stringProperty(), "schedule" to schedule,
            "delivery" to mapOf("type" to "string", "enum" to listOf("always", "only_if_notable")),
            "web" to booleanProperty(),
            "writes" to mapOf("type" to "string", "enum" to TaskWriteScope.values().map { it.name }),
            "valid_until" to stringProperty(), "delete_after_run" to booleanProperty(),
        ), listOf("name", "instruction", "schedule"))
    }

    private fun updateSchema(): Map<String, Any> = objectSchema(linkedMapOf(
        "task_id" to stringProperty(),
        "patch" to objectSchema(linkedMapOf(
            "revision" to mapOf("type" to "integer", "minimum" to 1),
            "name" to stringProperty(), "instruction" to stringProperty(),
            "schedule" to scheduleSchema()["properties"].let { props ->
                (props as Map<*, *>)["schedule"] as Map<String, Any>
            },
            "delivery" to mapOf("type" to "string", "enum" to listOf("always", "only_if_notable")),
            "web" to booleanProperty(), "tools" to mapOf("type" to "array", "items" to stringProperty()),
            "writes" to mapOf("type" to "string", "enum" to TaskWriteScope.values().map { it.name }),
            "valid_until" to stringProperty(),
            "delete_after_run" to booleanProperty(),
            "state" to mapOf("type" to "string", "enum" to listOf("active", "paused")),
        ), listOf("revision")),
    ), listOf("task_id", "patch"))

    private fun objectSchema(properties: Map<String, Any>, required: List<String>): Map<String, Any> = linkedMapOf(
        "type" to "object", "properties" to properties, "required" to required,
        "additionalProperties" to false,
    )
    private fun stringProperty() = mapOf("type" to "string")
    private fun booleanProperty() = mapOf("type" to "boolean")

    companion object {
        const val SCHEDULE_TASK = "schedule_task"
        const val LIST_TASKS = "list_tasks"
        const val UPDATE_TASK = "update_task"
        const val CANCEL_TASK = "cancel_task"
        const val TASK_RUNS = "task_runs"
        const val RUN_TASK = "run_task"
        @JvmField val TOOL_NAMES = setOf(SCHEDULE_TASK, LIST_TASKS, UPDATE_TASK, CANCEL_TASK, TASK_RUNS, RUN_TASK)
        private val WEB_TOOL_NAMES = setOf(com.jarvys.agent.WebSearchTools.SEARCH, com.jarvys.agent.WebSearchTools.FETCH)

        @JvmStatic fun create(context: Context, sessionId: String, creatorToolNames: List<String>,
                              taskCapabilityNames: List<String>): List<CoreTool> =
            TaskManagementTools(context, sessionId, creatorToolNames,
                taskCapabilityNames = taskCapabilityNames.toList()).tools()

        @JvmStatic fun create(context: Context, sessionId: String, creatorToolNames: List<String>,
                              taskCapabilityNames: List<String>, creatorSkillIds: List<String>): List<CoreTool> =
            TaskManagementTools(context, sessionId, creatorToolNames,
                taskCapabilityNames = taskCapabilityNames.toList(),
                creatorSkillIds = creatorSkillIds.toList()).tools()
    }
}
