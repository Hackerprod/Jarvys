package com.jarvys.agent.tasks

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

data class TaskStoreReadResult(val tasks: List<ScheduledTask>, val ignoredCorruptRows: Int)
data class TaskCreateResult(val task: ScheduledTask, val created: Boolean)
class StaleTaskRevisionException(message: String) : IllegalStateException(message)

/** Append-only snapshot store: last row per task ID wins; tombstones remain in the file. */
class TaskStore(private val ledger: File) {
    constructor(context: Context) : this(File(File(context.applicationContext.filesDir, "jarvys"), "tasks/tasks.jsonl"))

    fun read(): TaskStoreReadResult = withLock {
        val latest = readLatestLocked()
        TaskStoreReadResult(latest.rows.values.filterNot(Snapshot::deleted).map(Snapshot::task), latest.ignored)
    }
    fun list(): List<ScheduledTask> = read().tasks
    fun get(id: String): ScheduledTask? = read().tasks.firstOrNull { it.id == id }

    fun create(task: ScheduledTask): ScheduledTask = withLock {
        ScheduledTaskValidation.validate(task)
        val current = readLatestLocked()
        if (current.rows.containsKey(task.id)) throw StaleTaskRevisionException("Task ${task.id} already exists")
        val initial = task.copy(revision = 1L)
        appendLocked(encode(initial, deleted = false))
        initial
    }

    /** Atomic name/instruction/schedule deduplication for chat confirmations racing across processes. */
    fun createIfEquivalent(task: ScheduledTask, beforeWrite: () -> Unit = {}): TaskCreateResult = withLock {
        ScheduledTaskValidation.validate(task)
        val current = readLatestLocked()
        val equivalent = current.rows.values.asSequence().filterNot(Snapshot::deleted).map(Snapshot::task)
            .firstOrNull { it.name == task.name && it.instruction == task.instruction &&
                it.deleteAfterRun == task.deleteAfterRun && sameTaskSchedule(it.schedule, task.schedule) }
        if (equivalent != null) return@withLock TaskCreateResult(equivalent, false)
        if (current.rows.containsKey(task.id)) throw StaleTaskRevisionException("Task ${task.id} already exists")
        val initial = task.copy(revision = 1L)
        beforeWrite()
        appendLocked(encode(initial, deleted = false))
        TaskCreateResult(initial, true)
    }

    fun update(task: ScheduledTask, expectedRevision: Long, beforeWrite: () -> Unit = {},
               expectedTask: ScheduledTask? = null): ScheduledTask = withLock {
        val current = readLatestLocked().rows[task.id]
            ?: throw StaleTaskRevisionException("Task ${task.id} no longer exists")
        if (current.deleted || current.task.revision != expectedRevision) {
            throw StaleTaskRevisionException(
                "Task ${task.id} revision changed: expected $expectedRevision, found ${current.task.revision}")
        }
        check(expectedTask == null || current.task == expectedTask) { "Task content changed after preparation" }
        val updated = task.copy(revision = expectedRevision + 1L)
        ScheduledTaskValidation.validate(updated)
        beforeWrite()
        appendLocked(encode(updated, deleted = false))
        updated
    }

    /** Persists scheduler bookkeeping without changing the revision of the user-approved task. */
    internal fun updateRuntimeSnapshot(task: ScheduledTask, expectedRevision: Long,
                                       expectedTask: ScheduledTask): ScheduledTask = withLock {
        val current = readLatestLocked().rows[task.id]
            ?: throw StaleTaskRevisionException("Task ${task.id} no longer exists")
        if (current.deleted || current.task.revision != expectedRevision || current.task != expectedTask) {
            throw StaleTaskRevisionException("Task ${task.id} changed before runtime bookkeeping")
        }
        require(task.revision == expectedRevision) { "Runtime bookkeeping cannot change the task revision" }
        val runtimeOnly = current.task.copy(
            state = task.state,
            nextRunAt = task.nextRunAt,
            lastRun = task.lastRun,
            openIncidentCause = task.openIncidentCause,
            lastIncidentRecoveredAt = task.lastIncidentRecoveredAt,
            updatedAt = task.updatedAt,
        )
        require(runtimeOnly == task) { "Runtime bookkeeping changed user-owned task fields" }
        ScheduledTaskValidation.validate(task)
        appendLocked(encode(task, deleted = false))
        task
    }

    fun delete(id: String, expectedRevision: Long, deletedAt: Long, beforeWrite: () -> Unit = {},
               expectedTask: ScheduledTask? = null): ScheduledTask = withLock {
        val current = readLatestLocked().rows[id]
            ?: throw StaleTaskRevisionException("Task $id no longer exists")
        if (current.deleted || current.task.revision != expectedRevision) {
            throw StaleTaskRevisionException(
                "Task $id revision changed: expected $expectedRevision, found ${current.task.revision}")
        }
        check(expectedTask == null || current.task == expectedTask) { "Task content changed after preparation" }
        val tombstone = current.task.copy(revision = expectedRevision + 1L, updatedAt = deletedAt)
        beforeWrite()
        appendLocked(encode(tombstone, deleted = true))
        tombstone
    }

    private class UnsupportedTaskScopeFormatException : IllegalArgumentException("Unsupported task scope field: memory")

    private data class Snapshot(val task: ScheduledTask, val deleted: Boolean)
    private data class Latest(val rows: LinkedHashMap<String, Snapshot>, val ignored: Int)

    private fun readLatestLocked(): Latest {
        val rows = LinkedHashMap<String, Snapshot>()
        var ignored = 0
        if (ledger.isFile) {
            BufferedReader(InputStreamReader(FileInputStream(ledger), StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val decoded = runCatching { decode(JSONObject(line)) }.getOrElse { failure ->
                        // Never expose an older revision when a newer row uses a retired authority field.
                        if (failure is UnsupportedTaskScopeFormatException) throw failure
                        null
                    }
                    if (decoded == null) ignored++ else rows[decoded.task.id] = decoded
                }
            }
        }
        lastReadDiagnostics = ignored
        return Latest(rows, ignored)
    }

    @Volatile var lastReadDiagnostics: Int = 0
        private set

    private fun encode(task: ScheduledTask, deleted: Boolean): String = JSONObject()
        .put("id", task.id)
        .put("name", task.name)
        .put("instruction", task.instruction)
        .put("schedule", encodeSchedule(task.schedule))
        .put("state", when (val state = task.state) {
            TaskState.Active -> JSONObject().put("kind", "ACTIVE")
            TaskState.Paused -> JSONObject().put("kind", "PAUSED")
            TaskState.Done -> JSONObject().put("kind", "DONE")
            is TaskState.NeedsAttention -> JSONObject().put("kind", "NEEDS_ATTENTION").put("reason", state.reason)
            TaskState.AwaitingUser -> JSONObject().put("kind", "AWAITING_USER")
        })
        .put("timePrecision", task.timePrecision.name)
        .put("catchUp", task.catchUp.name)
        .putNullableLong("validUntil", task.validUntil)
        .put("deleteAfterRun", task.deleteAfterRun)
        .putNullableLong("nextRunAt", task.nextRunAt)
        .put("lastRun", encodeLastRun(task.lastRun))
        .put("toolScope", JSONObject()
            .put("mode", task.toolScope.mode)
            .put("tools", JSONArray(task.toolScope.tools))
            .put("web", task.toolScope.web)
            .put("writes", task.toolScope.writes)
            .put("skillIds", JSONArray(task.toolScope.skillIds)))
        .put("delivery", task.delivery.name)
        .put("createdBy", task.createdBy)
        .put("creatorToolNames", JSONArray(task.creatorToolNames))
        .putNullableString("openIncidentCause", task.openIncidentCause?.code)
        .putNullableLong("lastIncidentRecoveredAt", task.lastIncidentRecoveredAt)
        .put("createdAt", task.createdAt)
        .put("updatedAt", task.updatedAt)
        .put("revision", task.revision)
        .put("deleted", deleted)
        .toString()

    private fun encodeSchedule(schedule: TaskSchedule): JSONObject = when (schedule) {
        is TaskSchedule.At -> JSONObject().put("kind", "At").put("localDateTime", schedule.localDateTime)
            .put("zone", encodeZone(schedule.zone))
        is TaskSchedule.Calendar -> JSONObject().put("kind", "Calendar").put("time", schedule.time)
            .put("cadence", schedule.cadence.name).put("daysOfWeek", JSONArray(schedule.daysOfWeek.sorted()))
            .putNullableInt("dayOfMonth", schedule.dayOfMonth).put("zone", encodeZone(schedule.zone))
        is TaskSchedule.Every -> JSONObject().put("kind", "Every")
            .put("intervalMillis", schedule.intervalMillis).put("anchorMillis", schedule.anchorMillis)
    }

    private fun encodeZone(zone: TaskZone): String = when (zone) {
        TaskZone.FollowDevice -> "FOLLOW_DEVICE"
        is TaskZone.Iana -> zone.id
    }

    private fun encodeLastRun(run: TaskLastRun?): Any = if (run == null) JSONObject.NULL
    else JSONObject().put("runId", run.runId).put("scheduledFor", run.scheduledFor)
        .put("startedAt", run.startedAt).putNullableLong("finishedAt", run.finishedAt)
        .put("status", run.status).put("deliveryStatus", run.deliveryStatus)
        .putNullableString("causeCode", run.causeCode?.code)

    private fun decode(row: JSONObject): Snapshot {
        // Check before any other field can fail into the ordinary malformed-row skip.
        if (row.optJSONObject("toolScope")?.has("memory") == true) throw UnsupportedTaskScopeFormatException()
        val scheduleRow = row.getJSONObject("schedule")
        val schedule = when (scheduleRow.getString("kind")) {
            "At" -> TaskSchedule.At(scheduleRow.getString("localDateTime"), decodeZone(scheduleRow.getString("zone")))
            "Calendar" -> TaskSchedule.Calendar(
                scheduleRow.getString("time"), CalendarCadence.valueOf(scheduleRow.getString("cadence")),
                scheduleRow.getJSONArray("daysOfWeek").toIntSet(), scheduleRow.optNullableInt("dayOfMonth"),
                decodeZone(scheduleRow.getString("zone")),
            )
            "Every" -> TaskSchedule.Every(scheduleRow.getLong("intervalMillis"), scheduleRow.getLong("anchorMillis"))
            else -> throw IllegalArgumentException("Unknown schedule kind")
        }
        val stateRow = row.getJSONObject("state")
        val state = when (stateRow.getString("kind")) {
            "ACTIVE" -> TaskState.Active
            "PAUSED" -> TaskState.Paused
            "DONE" -> TaskState.Done
            "NEEDS_ATTENTION" -> {
                val reason = stateRow.getString("reason")
                TaskState.NeedsAttention(TaskRunCause.fromCode(reason)?.code
                    ?: TaskRunCause.fromLegacy(reason)?.code ?: TaskRunCause.UNKNOWN.code)
            }
            "AWAITING_USER" -> TaskState.AwaitingUser
            else -> throw IllegalArgumentException("Unknown task state")
        }
        val lastRunRow = row.optJSONObject("lastRun")
        val lastRun = lastRunRow?.let { item ->
            TaskLastRun(item.getString("runId"), item.getLong("scheduledFor"), item.getLong("startedAt"),
                item.optNullableLong("finishedAt"), item.getString("status"), item.getString("deliveryStatus"),
                TaskRunCause.fromCode(item.optNullableString("causeCode"))
                    ?: TaskRunCause.fromLegacy(item.optString("causeCode", "").takeIf(String::isNotBlank)))
        }
        val scope = row.optJSONObject("toolScope") ?: JSONObject()
        val task = ScheduledTask(
            id = row.getString("id"), name = row.getString("name"), instruction = row.getString("instruction"),
            schedule = schedule, state = state,
            timePrecision = TaskTimePrecision.valueOf(row.optString("timePrecision", "APPROXIMATE")),
            catchUp = TaskCatchUp.valueOf(row.optString("catchUp", "RUN_LATE_ONCE")),
            validUntil = row.optNullableLong("validUntil"), deleteAfterRun = row.optBoolean("deleteAfterRun", false),
            nextRunAt = row.optNullableLong("nextRunAt"), lastRun = lastRun,
            toolScope = TaskToolScope(scope.optString("mode", "READ_ONLY"), scope.optJSONArray("tools").toStringList(),
                scope.optBoolean("web", false), scope.optString("writes", "DEFER"),
                scope.optJSONArray("skillIds").toStringList()),
            delivery = TaskDelivery.valueOf(row.optString("delivery", "ALWAYS")),
            createdBy = row.optString("createdBy", "USER_UI"),
            creatorToolNames = row.optJSONArray("creatorToolNames").toStringList(),
            openIncidentCause = TaskRunCause.fromCode(row.optNullableString("openIncidentCause"))
                ?: (state as? TaskState.NeedsAttention)?.reason?.let(TaskRunCause::fromCode),
            lastIncidentRecoveredAt = row.optNullableLong("lastIncidentRecoveredAt"),
            createdAt = row.getLong("createdAt"), updatedAt = row.getLong("updatedAt"),
            revision = row.optLong("revision", 1L),
        )
        ScheduledTaskValidation.validate(task)
        return Snapshot(task, row.optBoolean("deleted", false))
    }

    /** Updates incident metadata without changing the task revision: it does not alter the reviewed schedule or scope. */
    fun openIncident(id: String, expectedRevision: Long, cause: TaskRunCause): Boolean = withLock {
        val current = readLatestLocked().rows[id] ?: return@withLock false
        if (current.deleted || current.task.revision != expectedRevision) return@withLock false
        if (current.task.openIncidentCause == cause) return@withLock false
        appendLocked(encode(current.task.copy(openIncidentCause = cause), deleted = false))
        true
    }

    /** Clears an open incident once and records the recovery time in the same durable snapshot. */
    fun closeIncident(id: String, expectedRevision: Long, recoveredAt: Long): Boolean = withLock {
        val current = readLatestLocked().rows[id] ?: return@withLock false
        if (current.deleted || current.task.revision != expectedRevision || current.task.openIncidentCause == null)
            return@withLock false
        appendLocked(encode(current.task.copy(openIncidentCause = null,
            lastIncidentRecoveredAt = recoveredAt), deleted = false))
        true
    }

    private fun decodeZone(value: String): TaskZone =
        if (value == "FOLLOW_DEVICE") TaskZone.FollowDevice else TaskZone.Iana(value)

    private fun appendLocked(line: String) {
        val parent = ledger.parentFile ?: error("Task ledger has no parent directory")
        check(parent.isDirectory || parent.mkdirs()) { "Could not create task ledger directory" }
        FileOutputStream(ledger, true).use { stream ->
            stream.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
            stream.fd.sync()
        }
        TaskDataChanges.invalidate()
    }

    private inline fun <T> withLock(block: () -> T): T {
        val canonical = ledger.canonicalFile
        val lock = LOCKS.computeIfAbsent(canonical.path) { Any() }
        return synchronized(lock) {
            val parent = canonical.parentFile ?: error("Task ledger has no parent directory")
            check(parent.isDirectory || parent.mkdirs()) { "Could not create task ledger directory" }
            // Keep the OS lock off the ledger so Windows permits the separate read and append handles.
            val lockFile = File(parent, canonical.name + ".lock")
            RandomAccessFile(lockFile, "rw").use { file -> file.channel.lock().use { _: FileLock -> block() } }
        }
    }

    companion object {
        private val LOCKS = ConcurrentHashMap<String, Any>()
    }
}

private fun sameTaskSchedule(left: TaskSchedule, right: TaskSchedule): Boolean = when {
    left is TaskSchedule.Every && right is TaskSchedule.Every -> left.intervalMillis == right.intervalMillis
    else -> left == right
}

private fun JSONObject.putNullableLong(key: String, value: Long?): JSONObject = put(key, value ?: JSONObject.NULL)
private fun JSONObject.putNullableInt(key: String, value: Int?): JSONObject = put(key, value ?: JSONObject.NULL)
private fun JSONObject.optNullableLong(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
private fun JSONObject.optNullableInt(key: String): Int? = if (!has(key) || isNull(key)) null else getInt(key)
private fun JSONArray?.toIntSet(): Set<Int> = if (this == null) emptySet()
    else (0 until length()).map { getInt(it) }.toSet()
private fun JSONArray?.toStringList(): List<String> = if (this == null) emptyList()
    else (0 until length()).map { getString(it) }
