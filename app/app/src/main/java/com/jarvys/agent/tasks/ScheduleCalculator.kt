package com.jarvys.agent.tasks

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneRules

fun interface TaskZoneProvider {
    fun deviceZone(): ZoneId
}

/** Deterministic wall-time/interval scheduling. Calendar and At rules are never converted to UTC storage. */
class ScheduleCalculator(
    private val clock: Clock = Clock.systemUTC(),
    private val zoneProvider: TaskZoneProvider = TaskZoneProvider { ZoneId.systemDefault() },
) {
    fun nextRunAfter(task: ScheduledTask, now: Long = clock.millis()): Long? {
        ScheduledTaskValidation.validate(task)
        if (task.validUntil != null && now >= task.validUntil) return null
        val next = when (val schedule = task.schedule) {
            is TaskSchedule.At -> resolveWallTime(LocalDateTime.parse(schedule.localDateTime), zone(schedule.zone)).toEpochMilli()
                .takeIf { it > now }
            is TaskSchedule.Every -> nextInterval(schedule, now)
            is TaskSchedule.Calendar -> nextCalendar(schedule, now)
        }
        return next?.takeIf { task.validUntil == null || it < task.validUntil }
    }

    fun latestDueAtOrBefore(task: ScheduledTask, now: Long = clock.millis()): Long? {
        ScheduledTaskValidation.validate(task)
        if (task.validUntil != null && now >= task.validUntil) return null
        val due = when (val schedule = task.schedule) {
            is TaskSchedule.At -> resolveWallTime(LocalDateTime.parse(schedule.localDateTime), zone(schedule.zone))
                .toEpochMilli().takeIf { it <= now }
            is TaskSchedule.Every -> latestInterval(schedule, now)
            is TaskSchedule.Calendar -> latestCalendar(schedule, now)
        }
        return due?.takeIf { task.validUntil == null || it <= task.validUntil }
    }

    fun resolveLocalDateTime(value: String, zone: TaskZone): Long {
        val local = try { LocalDateTime.parse(value) }
        catch (_: Exception) { throw IllegalArgumentException("At.localDateTime must be a valid ISO local date-time") }
        return resolveWallTime(local, zone(zone)).toEpochMilli()
    }

    fun resolveCalendarTime(date: LocalDate, time: LocalTime, zone: TaskZone): Long =
        resolveWallTime(LocalDateTime.of(date, time), zone(zone)).toEpochMilli()

    private fun zone(zone: TaskZone): ZoneId = when (zone) {
        TaskZone.FollowDevice -> zoneProvider.deviceZone()
        is TaskZone.Iana -> ZoneId.of(zone.id)
    }

    private fun resolveWallTime(local: LocalDateTime, zone: ZoneId): Instant {
        val rules: ZoneRules = zone.rules
        val offsets = rules.getValidOffsets(local)
        return when {
            offsets.size == 1 -> local.toInstant(offsets[0])
            offsets.size > 1 -> local.toInstant(offsets[0]) // overlap: choose the first wall-time occurrence only
            else -> {
                val transition = rules.getTransition(local)
                    ?: throw IllegalArgumentException("Could not resolve local date-time in zone $zone")
                transition.dateTimeAfter.toInstant(transition.offsetAfter) // gap: first valid wall time after the gap
            }
        }
    }

    private fun nextInterval(schedule: TaskSchedule.Every, now: Long): Long? {
        if (now < schedule.anchorMillis) return schedule.anchorMillis
        val elapsed = now - schedule.anchorMillis
        val intervals = elapsed / schedule.intervalMillis + 1L
        val delta = safeMultiply(intervals, schedule.intervalMillis) ?: return null
        return safeAdd(schedule.anchorMillis, delta)
    }

    private fun latestInterval(schedule: TaskSchedule.Every, now: Long): Long? {
        if (now < schedule.anchorMillis) return null
        val intervals = (now - schedule.anchorMillis) / schedule.intervalMillis
        val delta = safeMultiply(intervals, schedule.intervalMillis) ?: return null
        return safeAdd(schedule.anchorMillis, delta)
    }

    private fun nextCalendar(schedule: TaskSchedule.Calendar, now: Long): Long? {
        val zone = zone(schedule.zone)
        val start = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = LocalTime.parse(schedule.time)
        var date = start
        while (true) {
            if (matches(schedule, date)) {
                val candidate = resolveWallTime(LocalDateTime.of(date, time), zone).toEpochMilli()
                if (candidate > now) return candidate
            }
            date = date.plusDays(1)
        }
    }

    private fun latestCalendar(schedule: TaskSchedule.Calendar, now: Long): Long? {
        val zone = zone(schedule.zone)
        val start = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = LocalTime.parse(schedule.time)
        var date = start
        while (true) {
            if (matches(schedule, date)) {
                val candidate = resolveWallTime(LocalDateTime.of(date, time), zone).toEpochMilli()
                if (candidate <= now) return candidate
            }
            date = date.minusDays(1)
        }
    }

    private fun matches(schedule: TaskSchedule.Calendar, date: LocalDate): Boolean = when (schedule.cadence) {
        CalendarCadence.DAILY -> true
        CalendarCadence.WEEKLY -> date.dayOfWeek.value in schedule.daysOfWeek
        CalendarCadence.MONTHLY -> date.dayOfMonth == minOf(requireNotNull(schedule.dayOfMonth), date.lengthOfMonth())
    }

    private fun safeAdd(left: Long, right: Long): Long? = try { Math.addExact(left, right) } catch (_: ArithmeticException) { null }
    private fun safeMultiply(left: Long, right: Long): Long? = try { Math.multiplyExact(left, right) } catch (_: ArithmeticException) { null }
}
