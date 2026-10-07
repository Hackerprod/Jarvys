package com.jarvys.agent.tasks

import android.content.Context
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.R
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Shared deterministic schedule wording and local-time formatting for chat cards and task screens. */
class TaskSchedulePresentation(
    context: Context,
    private val zoneProvider: TaskZoneProvider = TaskZoneProvider { ZoneId.systemDefault() },
) {
    private val app = AppLanguageRuntime.localizedContext(context.applicationContext)
    private val locale: Locale = app.resources.configuration.locales[0]
    private val formatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm z", locale)
    private val calculator = ScheduleCalculator(Clock.systemUTC(), zoneProvider)

    fun scheduleHuman(schedule: TaskSchedule): String {
        val zone = zoneFor(schedule)
        val rule = when (schedule) {
            is TaskSchedule.At -> app.getString(R.string.task_management_schedule_at,
                localTime(calculator.resolveLocalDateTime(schedule.localDateTime, schedule.zone), zone))
            is TaskSchedule.Every -> app.getString(R.string.task_management_schedule_every,
                durationHuman(schedule.intervalMillis))
            is TaskSchedule.Calendar -> {
                val time = LocalTime.parse(schedule.time).format(DateTimeFormatter.ofPattern("HH:mm", locale))
                when (schedule.cadence) {
                    CalendarCadence.DAILY -> app.getString(R.string.task_management_schedule_daily, time)
                    CalendarCadence.WEEKLY -> when (schedule.daysOfWeek) {
                        (1..5).toSet() -> app.getString(R.string.task_management_schedule_weekdays, time)
                        (1..7).toSet() -> app.getString(R.string.task_management_schedule_daily, time)
                        else -> app.getString(R.string.task_management_schedule_weekly,
                            schedule.daysOfWeek.sorted().joinToString(", ") {
                                java.time.DayOfWeek.of(it).getDisplayName(TextStyle.FULL, locale)
                            }, time)
                    }
                    CalendarCadence.MONTHLY -> app.getString(R.string.task_management_schedule_monthly,
                        schedule.dayOfMonth ?: 1, time)
                }
            }
        }
        return "$rule (${zone.id})"
    }

    fun zoneFor(schedule: TaskSchedule): ZoneId = when (schedule) {
        is TaskSchedule.At -> zone(schedule.zone)
        is TaskSchedule.Calendar -> zone(schedule.zone)
        is TaskSchedule.Every -> zoneProvider.deviceZone()
    }

    fun localTime(millis: Long, zone: ZoneId = zoneProvider.deviceZone()): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(formatter)

    fun zone(zone: TaskZone): ZoneId = when (zone) {
        TaskZone.FollowDevice -> zoneProvider.deviceZone()
        is TaskZone.Iana -> ZoneId.of(zone.id)
    }

    private fun durationHuman(millis: Long): String {
        val minutes = millis / 60_000L
        return if (minutes % 60L == 0L) app.getString(R.string.task_management_hours, minutes / 60L)
            else app.getString(R.string.task_management_minutes, minutes)
    }
}
