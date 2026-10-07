package com.jarvys.agent.tasks

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleCalculatorTest {
    @Test fun northAmericanDstGapMovesToFirstValidWallTimeAndOverlapRunsOnlyAtFirstOffset() {
        val ny = TaskZone.Iana("America/New_York")
        val springNow = millis("2024-03-09T12:00:00Z")
        val dailyGap = task(TaskSchedule.Calendar("02:30", CalendarCadence.DAILY, zone = ny))
        assertEquals(millis("2024-03-10T07:00:00Z"), calculator(springNow).nextRunAfter(dailyGap))

        val fallNow = millis("2024-11-02T12:00:00Z")
        val dailyOverlap = task(TaskSchedule.Calendar("01:30", CalendarCadence.DAILY, zone = ny))
        assertEquals(millis("2024-11-03T05:30:00Z"), calculator(fallNow).nextRunAfter(dailyOverlap))
        assertEquals(millis("2024-11-03T05:30:00Z"),
            calculator(millis("2024-11-03T06:00:00Z")).latestDueAtOrBefore(dailyOverlap))
        assertEquals(millis("2024-11-04T06:30:00Z"),
            calculator(millis("2024-11-03T05:45:00Z")).nextRunAfter(dailyOverlap))
    }

    @Test fun southernDstHalfHourAndNoDstZonesResolveWallTime() {
        val sydney = task(TaskSchedule.Calendar("02:30", CalendarCadence.DAILY,
            zone = TaskZone.Iana("Australia/Sydney")))
        assertEquals(millis("2024-10-05T16:00:00Z"),
            calculator(millis("2024-10-05T03:00:00Z")).nextRunAfter(sydney))

        val kathmandu = task(TaskSchedule.Calendar("09:00", CalendarCadence.DAILY,
            zone = TaskZone.Iana("Asia/Kathmandu")))
        assertEquals(millis("2025-01-01T03:15:00Z"),
            calculator(millis("2025-01-01T00:00:00Z")).nextRunAfter(kathmandu))
    }

    @Test fun followDeviceRecalculatesItsWallTimeButExplicitZoneDoesNotMove() {
        val device = AtomicReference(ZoneId.of("America/New_York"))
        val calculator = ScheduleCalculator(Clock.fixed(Instant.parse("2025-01-02T00:00:00Z"), ZoneOffset.UTC),
            TaskZoneProvider { device.get() })
        val follow = task(TaskSchedule.At("2025-01-03T08:00:00", TaskZone.FollowDevice))
        val fixed = task(TaskSchedule.At("2025-01-03T08:00:00", TaskZone.Iana("America/New_York")))
        device.set(ZoneId.of("Europe/London"))
        val followNext = calculator.nextRunAfter(follow)
        val fixedNext = calculator.nextRunAfter(fixed)
        assertEquals(millis("2025-01-03T08:00:00Z"), followNext)
        assertEquals(millis("2025-01-03T13:00:00Z"), fixedNext)
        assertTrue(followNext != fixedNext)
    }

    @Test fun monthlyShortMonthsClampDayAndLeapYearAtUsesCalendarRules() {
        val day31 = task(TaskSchedule.Calendar("08:00", CalendarCadence.MONTHLY,
            dayOfMonth = 31, zone = TaskZone.Iana("UTC")))
        assertEquals(millis("2025-02-28T08:00:00Z"),
            calculator(millis("2025-02-01T00:00:00Z")).nextRunAfter(day31))
        assertEquals(millis("2024-02-29T08:00:00Z"),
            calculator(millis("2024-02-01T00:00:00Z")).nextRunAfter(day31))
        assertEquals(millis("2025-04-30T08:00:00Z"),
            calculator(millis("2025-04-01T00:00:00Z")).nextRunAfter(day31))

        val leapOnce = task(TaskSchedule.At("2024-02-29T10:15:00", TaskZone.Iana("UTC")))
        assertEquals(millis("2024-02-29T10:15:00Z"),
            calculator(millis("2024-02-28T10:15:00Z")).nextRunAfter(leapOnce))
        assertNull(calculator(millis("2024-03-01T00:00:00Z")).nextRunAfter(leapOnce))
    }

    @Test fun everyUsesItsOriginalAnchorWithoutAccumulatedDriftAndRespectsValidUntil() {
        val anchor = millis("2025-01-01T00:00:00Z")
        val schedule = TaskSchedule.Every(15L * 60 * 1000, anchor)
        val task = task(schedule)
        val now = anchor + 3 * 15L * 60 * 1000 + 1234
        assertEquals(anchor + 3 * 15L * 60 * 1000, calculator(now).latestDueAtOrBefore(task))
        assertEquals(anchor + 4 * 15L * 60 * 1000, calculator(now).nextRunAfter(task))
        val bounded = task.copy(validUntil = anchor + 4 * 15L * 60 * 1000)
        assertNull(calculator(now).nextRunAfter(bounded))
        assertNull(calculator(anchor + 4 * 15L * 60 * 1000).latestDueAtOrBefore(bounded))
    }

    @Test fun atPastIsNotNextButRemainsTheLatestDueOccurrence() {
        val at = task(TaskSchedule.At("2025-01-01T08:00:00", TaskZone.Iana("UTC")))
        val now = millis("2025-01-02T00:00:00Z")
        assertNull(calculator(now).nextRunAfter(at))
        assertEquals(millis("2025-01-01T08:00:00Z"), calculator(now).latestDueAtOrBefore(at))
    }

    @Test fun strictValidationRejectsInvalidTimesZonesIntervalsAndImpossibleNumericDays() {
        listOf(
            task(TaskSchedule.At("2025-02-30T08:00:00", TaskZone.Iana("UTC"))),
            task(TaskSchedule.At("2025-01-01T08:00:00", TaskZone.Iana("Mars/Olympus"))),
            task(TaskSchedule.Calendar("25:00", CalendarCadence.DAILY)),
            task(TaskSchedule.Calendar("08:00", CalendarCadence.WEEKLY, daysOfWeek = setOf(0))),
            task(TaskSchedule.Calendar("08:00", CalendarCadence.MONTHLY, dayOfMonth = 32)),
            task(TaskSchedule.Every(14L * 60 * 1000, 1L)),
        ).forEach { invalid ->
            assertTrue("Expected validation to reject $invalid", runCatching { ScheduledTaskValidation.validate(invalid) }.isFailure)
        }
    }

    private fun task(schedule: TaskSchedule) = ScheduledTask(
        name = "Schedule test", instruction = "Record one scheduled occurrence", schedule = schedule,
        createdAt = 1L,
    )

    private fun calculator(now: Long, zone: ZoneId = ZoneOffset.UTC) =
        ScheduleCalculator(Clock.fixed(Instant.ofEpochMilli(now), zone), TaskZoneProvider { zone })

    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
}
