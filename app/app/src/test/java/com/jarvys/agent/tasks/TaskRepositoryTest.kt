package com.jarvys.agent.tasks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskRepositoryTest {
    @Test fun createPauseResumeDeletePersistsRevisionAndRearmsAfterEveryMutation() {
        val now = Instant.parse("2025-01-01T00:00:00Z")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TaskStore(Files.createTempDirectory("st0-repository").resolve("tasks.jsonl").toFile())
        var rearmCount = 0
        val repository = TaskRepository(context, store, ScheduleCalculator(Clock.fixed(now, ZoneOffset.UTC)),
            Clock.fixed(now, ZoneOffset.UTC), { rearmCount++ })

        val created = repository.create("  Morning  ", "  Record only  ",
            TaskSchedule.Calendar("08:30", CalendarCadence.WEEKLY, daysOfWeek = setOf(1, 3), zone = TaskZone.Iana("Europe/London")),
            timePrecision = TaskTimePrecision.EXACT, catchUp = TaskCatchUp.SKIP_MISSED,
            toolScope = TaskToolScope("LISTED", listOf("calendar_search"), true, "NEVER"),
            delivery = TaskDelivery.ONLY_IF_NOTABLE, createdBy = "AGENT_CHAT:session:message",
            creatorToolNames = listOf("calendar_search"))
        assertEquals("Morning", created.name)
        assertEquals("Record only", created.instruction)
        assertEquals(1L, created.revision)
        assertTrue(created.nextRunAt!! > now.toEpochMilli())
        assertEquals(TaskTimePrecision.EXACT, repository.get(created.id)?.timePrecision)
        assertEquals("LISTED", repository.get(created.id)?.toolScope?.mode)
        assertEquals(TaskDelivery.ONLY_IF_NOTABLE, repository.get(created.id)?.delivery)

        val paused = repository.pause(created.id, created.revision)
        assertEquals(TaskState.Paused, paused.state)
        assertEquals(paused.revision, repository.get(created.id)?.revision)
        assertTrue(paused.nextRunAt!! > now.toEpochMilli())
        val resumed = repository.resume(created.id, paused.revision)
        assertEquals(TaskState.Active, resumed.state)
        assertEquals(3L, resumed.revision)
        repository.delete(resumed.id, resumed.revision)
        assertNull(repository.get(resumed.id))
        assertEquals(4, rearmCount)
        assertEquals(0, repository.readDiagnostics())
    }

    @Test fun oneShotInThePastIsRejectedClearlyAndFutureOneShotResolvesWallTime() {
        val now = Instant.parse("2025-04-01T10:00:00Z")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = TaskRepository(context,
            TaskStore(Files.createTempDirectory("st0-at-validation").resolve("tasks.jsonl").toFile()),
            ScheduleCalculator(Clock.fixed(now, ZoneOffset.UTC)), Clock.fixed(now, ZoneOffset.UTC), {})
        assertTrue(runCatching {
            repository.create("Past", "No run", TaskSchedule.At("2025-04-01T09:00:00", TaskZone.Iana("UTC")))
        }.exceptionOrNull()?.message.orEmpty().contains("future"))
        val future = repository.create("Future", "Run once", TaskSchedule.At("2025-04-02T08:15:00", TaskZone.Iana("UTC")))
        assertEquals(Instant.parse("2025-04-02T08:15:00Z").toEpochMilli(), future.nextRunAt)
    }

    @Test fun serviceRejectsImpossibleSchedulesAndKeepsExactAsModelOnly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = Instant.parse("2025-01-01T00:00:00Z")
        val repository = TaskRepository(context,
            TaskStore(Files.createTempDirectory("st0-invalid").resolve("tasks.jsonl").toFile()),
            ScheduleCalculator(Clock.fixed(now, ZoneOffset.UTC)), Clock.fixed(now, ZoneOffset.UTC), {})
        assertTrue(runCatching {
            repository.create("Bad interval", "x", TaskSchedule.Every(899_999L, now.toEpochMilli()))
        }.isFailure)
        assertTrue(runCatching {
            repository.create("Bad monthly", "x", TaskSchedule.Calendar("11:00", CalendarCadence.MONTHLY, dayOfMonth = 32))
        }.isFailure)
        val exact = repository.create("Exact model", "x",
            TaskSchedule.At("2025-01-02T00:00:00", TaskZone.FollowDevice), timePrecision = TaskTimePrecision.EXACT)
        assertEquals(TaskTimePrecision.EXACT, exact.timePrecision)
        assertFalse(exact.schedule is TaskSchedule.Every)
    }
}
