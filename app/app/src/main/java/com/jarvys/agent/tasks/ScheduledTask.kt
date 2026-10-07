package com.jarvys.agent.tasks

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

sealed interface TaskZone {
    data object FollowDevice : TaskZone
    data class Iana(val id: String) : TaskZone
}

enum class CalendarCadence { DAILY, WEEKLY, MONTHLY }

sealed interface TaskSchedule {
    /** ISO local date-time plus a wall-time zone. The value is never stored as a UTC rule. */
    data class At(val localDateTime: String, val zone: TaskZone = TaskZone.FollowDevice) : TaskSchedule

    /** Wall-clock calendar recurrence. Weekdays use ISO 1=Monday ... 7=Sunday. */
    data class Calendar(
        val time: String,
        val cadence: CalendarCadence,
        val daysOfWeek: Set<Int> = emptySet(),
        val dayOfMonth: Int? = null,
        val zone: TaskZone = TaskZone.FollowDevice,
    ) : TaskSchedule

    /** Fixed elapsed interval measured from an immutable epoch-millisecond anchor. */
    data class Every(val intervalMillis: Long, val anchorMillis: Long) : TaskSchedule
}

sealed interface TaskState {
    data object Active : TaskState
    data object Paused : TaskState
    data object Done : TaskState
    data class NeedsAttention(val reason: String) : TaskState
    data object AwaitingUser : TaskState
}

enum class TaskTimePrecision { APPROXIMATE, EXACT }
enum class TaskCatchUp { RUN_LATE_ONCE, SKIP_MISSED }
enum class TaskDelivery { ALWAYS, ONLY_IF_NOTABLE }

/** Reserved ST1+ policy fields are modeled and serialized here; ST0 never interprets them. */
data class TaskToolScope(
    val mode: String = "READ_ONLY",
    val tools: List<String> = emptyList(),
    val web: Boolean = false,
    val writes: String = "DEFER",
)

data class TaskLastRun(
    val runId: String,
    val scheduledFor: Long,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: String,
    val deliveryStatus: String,
)

data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val instruction: String,
    val schedule: TaskSchedule,
    val state: TaskState = TaskState.Active,
    val timePrecision: TaskTimePrecision = TaskTimePrecision.APPROXIMATE,
    val catchUp: TaskCatchUp = TaskCatchUp.RUN_LATE_ONCE,
    val validUntil: Long? = null,
    val deleteAfterRun: Boolean = false,
    val nextRunAt: Long? = null,
    val lastRun: TaskLastRun? = null,
    val toolScope: TaskToolScope = TaskToolScope(),
    val delivery: TaskDelivery = TaskDelivery.ALWAYS,
    val createdBy: String = "USER_UI",
    val creatorToolNames: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val revision: Long = 1L,
)

object ScheduledTaskValidation {
    const val MIN_INTERVAL_MILLIS = 15L * 60L * 1000L

    fun validate(task: ScheduledTask) {
        require(runCatching { UUID.fromString(task.id) }.isSuccess) { "id must be a UUID" }
        require(task.name.isNotBlank()) { "name must not be empty" }
        require(task.instruction.isNotBlank()) { "instruction must not be empty" }
        require(task.createdBy.isNotBlank()) { "createdBy must not be empty" }
        require(task.revision > 0L) { "revision must be positive" }
        require(task.updatedAt >= task.createdAt) { "updatedAt must not precede createdAt" }
        require(task.validUntil == null || task.validUntil >= 0L) { "validUntil must be a valid epoch-millisecond timestamp" }
        if (task.state is TaskState.NeedsAttention) require(task.state.reason.isNotBlank()) {
            "NEEDS_ATTENTION requires a reason"
        }
        when (val schedule = task.schedule) {
            is TaskSchedule.At -> {
                try { LocalDateTime.parse(schedule.localDateTime) }
                catch (_: Exception) { throw IllegalArgumentException("At.localDateTime must be a valid ISO local date-time") }
                validateZone(schedule.zone)
            }
            is TaskSchedule.Every -> require(schedule.intervalMillis >= MIN_INTERVAL_MILLIS) {
                "Every interval must be at least 15 minutes"
            }
            is TaskSchedule.Calendar -> {
                try { LocalTime.parse(schedule.time) }
                catch (_: Exception) { throw IllegalArgumentException("Calendar.time must be a valid 24-hour local time") }
                validateZone(schedule.zone)
                when (schedule.cadence) {
                    CalendarCadence.DAILY -> require(schedule.daysOfWeek.isEmpty() && schedule.dayOfMonth == null) {
                        "Daily schedule must not include weekday or month-day fields"
                    }
                    CalendarCadence.WEEKLY -> {
                        require(schedule.daysOfWeek.isNotEmpty()) { "Weekly schedule requires at least one weekday" }
                        require(schedule.daysOfWeek.all { it in 1..7 }) { "Weekdays must be ISO values 1-7" }
                        require(schedule.dayOfMonth == null) { "Weekly schedule must not include dayOfMonth" }
                    }
                    CalendarCadence.MONTHLY -> {
                        require(schedule.dayOfMonth != null && schedule.dayOfMonth in 1..31) {
                            "Monthly dayOfMonth must be between 1 and 31"
                        }
                        require(schedule.daysOfWeek.isEmpty()) { "Monthly schedule must not include weekdays" }
                    }
                }
            }
        }
    }

    fun validateZone(zone: TaskZone) {
        if (zone is TaskZone.Iana) {
            require(zone.id.isNotBlank() && zone.id in ZoneId.getAvailableZoneIds()) {
                "zone must be FOLLOW_DEVICE or a valid IANA time-zone id"
            }
        }
    }
}
