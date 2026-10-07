package com.jarvys.agent.tasks

import android.content.Context
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

/** Internal API shared by future chat tools/UI; ST0 has no user-facing creation surface. */
class TaskRepository(
    context: Context,
    private val store: TaskStore = TaskStore(context.applicationContext),
    private val calculator: ScheduleCalculator = ScheduleCalculator(),
    private val clock: Clock = Clock.systemUTC(),
    private val rearm: () -> Unit = { TaskScheduler.rearm(context.applicationContext) },
    private val runLedger: TaskRunLedger = TaskRunLedger(context.applicationContext),
) {
    fun create(
        name: String,
        instruction: String,
        schedule: TaskSchedule,
        timePrecision: TaskTimePrecision = TaskTimePrecision.APPROXIMATE,
        catchUp: TaskCatchUp = TaskCatchUp.RUN_LATE_ONCE,
        validUntil: Long? = null,
        deleteAfterRun: Boolean = false,
        toolScope: TaskToolScope = TaskToolScope(),
        delivery: TaskDelivery = TaskDelivery.ALWAYS,
        createdBy: String = "USER_UI",
        creatorToolNames: List<String> = emptyList(),
    ): ScheduledTask {
        val now = clock.millis()
        val task = ScheduledTask(
            id = UUID.randomUUID().toString(), name = name.trim(), instruction = instruction.trim(),
            schedule = schedule, timePrecision = timePrecision, catchUp = catchUp,
            validUntil = validUntil, deleteAfterRun = deleteAfterRun,
            toolScope = toolScope, delivery = delivery, createdBy = createdBy,
            creatorToolNames = creatorToolNames.toList(), createdAt = now, updatedAt = now,
        )
        ScheduledTaskValidation.validate(task)
        if (schedule is TaskSchedule.At) {
            val at = calculator.resolveLocalDateTime(schedule.localDateTime, schedule.zone)
            require(at > now) { "At schedule must be in the future" }
            require(validUntil == null || at < validUntil) { "At schedule must be before validUntil" }
        }
        val saved = store.create(task.copy(nextRunAt = calculator.nextRunAfter(task, now)))
        rearm()
        return saved
    }

    fun createIfEquivalent(
        name: String,
        instruction: String,
        schedule: TaskSchedule,
        timePrecision: TaskTimePrecision = TaskTimePrecision.APPROXIMATE,
        validUntil: Long? = null,
        toolScope: TaskToolScope = TaskToolScope(),
        delivery: TaskDelivery = TaskDelivery.ALWAYS,
        createdBy: String = "USER_UI",
        creatorToolNames: List<String> = emptyList(),
    ): TaskCreateResult {
        val now = clock.millis()
        val draft = ScheduledTask(name = name.trim(), instruction = instruction.trim(), schedule = schedule,
            timePrecision = timePrecision, validUntil = validUntil, toolScope = toolScope, delivery = delivery,
            createdBy = createdBy, creatorToolNames = creatorToolNames.toList(), createdAt = now,
            updatedAt = now)
        val candidate = draft.copy(nextRunAt = calculator.nextRunAfter(draft, now))
        ScheduledTaskValidation.validate(candidate)
        if (schedule is TaskSchedule.At) {
            val at = calculator.resolveLocalDateTime(schedule.localDateTime, schedule.zone)
            require(at > now) { "At schedule must be in the future" }
            require(validUntil == null || at < validUntil) { "At schedule must be before validUntil" }
        }
        val result = store.createIfEquivalent(candidate)
        if (result.created) rearm()
        return result
    }

    fun get(id: String): ScheduledTask? = store.get(id)
    fun list(): List<ScheduledTask> = store.list()
    fun readDiagnostics(): Int = store.lastReadDiagnostics

    fun nextRunAt(task: ScheduledTask, now: Long = clock.millis()): Long? =
        calculator.nextRunAfter(task, now)

    fun update(task: ScheduledTask, expectedRevision: Long): ScheduledTask {
        ScheduledTaskValidation.validate(task)
        val now = clock.millis()
        val next = if (task.state == TaskState.Active) calculator.nextRunAfter(task, now) else task.nextRunAt
        val saved = store.update(task.copy(nextRunAt = next, updatedAt = now), expectedRevision)
        rearm()
        return saved
    }

    fun pause(id: String, expectedRevision: Long): ScheduledTask {
        val task = store.get(id) ?: throw IllegalArgumentException("Task $id was not found")
        val saved = store.update(task.copy(state = TaskState.Paused, updatedAt = clock.millis()), expectedRevision)
        rearm()
        return saved
    }

    fun resume(id: String, expectedRevision: Long): ScheduledTask {
        val task = store.get(id) ?: throw IllegalArgumentException("Task $id was not found")
        val now = clock.millis()
        if (task.state is TaskState.NeedsAttention && task.lastRun?.status == "ERROR") {
            runLedger.markRetryable(task.id, task.lastRun.scheduledFor)
        }
        val next = when {
            task.validUntil != null && now >= task.validUntil -> null
            task.nextRunAt != null && task.nextRunAt <= now && task.catchUp == TaskCatchUp.SKIP_MISSED -> task.nextRunAt
            task.catchUp == TaskCatchUp.RUN_LATE_ONCE -> calculator.latestDueAtOrBefore(task, now)
                ?: calculator.nextRunAfter(task, now)
            else -> calculator.nextRunAfter(task, now)
        }
        val state = if (task.validUntil != null && now >= task.validUntil) TaskState.Done else TaskState.Active
        val saved = store.update(task.copy(state = state, nextRunAt = next, updatedAt = now), expectedRevision)
        rearm()
        return saved
    }

    fun delete(id: String, expectedRevision: Long) {
        store.delete(id, expectedRevision, clock.millis())
        rearm()
    }

    companion object {
        fun parseAt(localDateTime: String, zone: TaskZone = TaskZone.FollowDevice): TaskSchedule.At {
            try { LocalDateTime.parse(localDateTime) }
            catch (_: Exception) { throw IllegalArgumentException("At.localDateTime must be a valid ISO local date-time") }
            ScheduledTaskValidation.validateZone(zone)
            return TaskSchedule.At(localDateTime, zone)
        }
    }
}
