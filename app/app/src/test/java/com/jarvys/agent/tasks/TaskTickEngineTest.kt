package com.jarvys.agent.tasks

import com.jarvys.agent.CancellationToken
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTickEngineTest {
    @Test fun runLateOnceCollapsesManyMissedIntervalsToLatestSingleOccurrenceAndRetryCannotDuplicate() {
        val root = Files.createTempDirectory("st0-late-once").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val interval = 15L * 60 * 1000
        val anchor = 100_000L
        val now = anchor + 5 * interval + 12_000
        val task = store.create(task("interval", TaskSchedule.Every(interval, anchor), nextRunAt = anchor))
        val executor = RecordingTaskExecutor()
        val calculator = calculator(now)
        val engine = TaskTickEngine(store, ledger, calculator, executor, fixedClock(now))

        assertEquals(TaskTickResult(1, 0, false), engine.tick())
        assertEquals(listOf(task.id to (anchor + 5 * interval)), executor.occurrences())
        val updated = requireNotNull(store.get(task.id))
        assertEquals(anchor + 6 * interval, updated.nextRunAt)
        assertEquals("OK", updated.lastRun?.status)

        // Simulate a stale scheduled snapshot being delivered again after process recovery.
        store.update(updated.copy(nextRunAt = anchor, updatedAt = now + 1), updated.revision)
        engine.tick()
        assertEquals(1, executor.occurrences().size)
        assertEquals(anchor + 6 * interval, store.get(task.id)?.nextRunAt)
        assertEquals(1, ledger.read().runs.size)
    }

    @Test fun skipMissedWritesOneSkippedOccurrenceWithoutInvokingExecutorAndRearmsFuture() {
        val root = Files.createTempDirectory("st0-skip").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val interval = 15L * 60 * 1000
        val anchor = 20_000L
        val now = anchor + interval * 4 + 5
        val task = store.create(task("skip", TaskSchedule.Every(interval, anchor), TaskCatchUp.SKIP_MISSED,
            nextRunAt = anchor))
        val executor = RecordingTaskExecutor()
        var rearmed = 0
        val result = TaskTickEngine(store, ledger, calculator(now), executor, fixedClock(now), { rearmed++ }).tick()

        assertEquals(TaskTickResult(1, 1, false), result)
        assertTrue(executor.occurrences().isEmpty())
        assertEquals("SKIPPED", store.get(task.id)?.lastRun?.status)
        assertEquals(anchor + interval * 5, store.get(task.id)?.nextRunAt)
        assertEquals(1, rearmed)
    }

    @Test fun retryableRunReusesTheSameOccurrenceAndPersistsOneFinalLedgerRow() {
        val root = Files.createTempDirectory("st0-retry-same-occurrence").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val anchor = 1_000_000L
        val interval = 900_000L
        val now = anchor + 300_000L
        val task = store.create(task("retry", TaskSchedule.Every(interval, anchor), nextRunAt = anchor))
        val attempts = mutableListOf<Long>()
        val executor = TaskExecutor { _, scheduledFor, _, _, _ ->
            attempts += scheduledFor
            if (attempts.size == 1) TaskExecutionResult("RETRYABLE", "NOT_APPLICABLE", "network")
            else TaskExecutionResult("OK", "CHAT_ONLY")
        }
        val clock = fixedClock(now)

        val first = TaskTickEngine(store, ledger, calculator(now), executor, clock).tick()
        assertEquals(1, first.started)
        assertTrue(first.retryWork)
        assertTrue(ledger.read().runs.isEmpty())
        assertEquals("RETRYABLE", store.get(task.id)?.lastRun?.status)
        assertEquals(anchor, store.get(task.id)?.nextRunAt)

        val second = TaskTickEngine(store, ledger, calculator(now), executor, clock).tick()
        assertFalse(second.retryWork)
        assertEquals(listOf(anchor, anchor), attempts)
        assertEquals(1, ledger.read().runs.size)
        assertEquals(anchor, ledger.read().runs.single().scheduledFor)
        assertEquals(anchor + interval, store.get(task.id)?.nextRunAt)
    }

    @Test fun stoppedTickRecordsInterruptionRearmsAndLeavesOccurrenceUnclaimedForSafeRetry() {
        val root = Files.createTempDirectory("st0-worker-stopped").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val now = 300_000L
        val task = store.create(task("stopped", TaskSchedule.Every(900_000L, now - 1L), nextRunAt = now - 1L))
        val token = CancellationToken.cancellable()
        var rearmed = 0
        val executor = TaskExecutor { _, _, _, _, activeToken ->
            activeToken.cancel()
            TaskExecutionResult("INTERRUPTED", "NOT_APPLICABLE", "worker_stopped")
        }

        val result = TaskTickEngine(store, ledger, calculator(now), executor, fixedClock(now),
            { rearmed++ }, token = token).tick()
        assertTrue(result.retryWork)
        assertTrue(token.isCancelled)
        assertEquals(1, rearmed)
        assertEquals("INTERRUPTED", store.get(task.id)?.lastRun?.status)
        assertEquals(now - 1L, store.get(task.id)?.nextRunAt)
        assertTrue(ledger.read().runs.isEmpty())
    }

    @Test fun atRunsOnceAndMovesToDoneAndExpiredValidUntilMovesToDoneWithoutExecution() {
        val root = Files.createTempDirectory("st0-at").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val at = 100_000L
        val oneShot = store.create(task("once", TaskSchedule.At("1970-01-01T00:01:40", TaskZone.Iana("UTC")),
            nextRunAt = at))
        val executor = RecordingTaskExecutor()
        TaskTickEngine(store, ledger, calculator(at), executor, fixedClock(at)).tick()
        val finished = requireNotNull(store.get(oneShot.id))
        assertEquals(TaskState.Done, finished.state)
        assertNull(finished.nextRunAt)
        assertEquals("OK", finished.lastRun?.status)
        assertEquals(1, executor.occurrences().size)

        val expiring = store.create(task("expire", TaskSchedule.Every(900_000L, at + 1_000_000L),
            validUntil = at - 1, nextRunAt = at + 1_000_000L))
        TaskTickEngine(store, ledger, calculator(at), executor, fixedClock(at)).tick()
        val expired = requireNotNull(store.get(expiring.id))
        assertEquals(TaskState.Done, expired.state)
        assertNull(expired.lastRun)
        assertEquals(1, executor.occurrences().size)
    }

    @Test fun allOverdueTasksRunSeriallyInScheduledOrderAndRemainingWorkIsRearmed() {
        val root = Files.createTempDirectory("st0-serial").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val now = 5_000_000L
        val wall = listOf("1970-01-01T00:10:00", "1970-01-01T00:20:00", "1970-01-01T00:30:00")
        val tasks = wall.mapIndexed { index, local -> store.create(task("task-$index",
            TaskSchedule.At(local, TaskZone.Iana("UTC")), nextRunAt = 600_000L * (index + 1))) }
        val order = mutableListOf<String>()
        val recording = TaskExecutor { task, _, _, _, _ -> order += task.id; TaskExecutionResult() }
        val shouldContinue = AtomicInteger()
        var rearmed = 0
        val result = TaskTickEngine(store, ledger, calculator(now), recording, fixedClock(now), { rearmed++ },
            { shouldContinue.getAndIncrement() == 0 }).tick()

        assertEquals(listOf(tasks.first().id), order)
        assertEquals(TaskTickResult(1, 0, true), result)
        assertEquals(TaskState.Done, store.get(tasks.first().id)?.state)
        assertEquals(TaskState.Active, store.get(tasks[1].id)?.state)
        assertEquals(1, rearmed)
    }

    @Test fun tickProcessesEveryDueTaskSeriallyInAscendingScheduledOrder() {
        val root = Files.createTempDirectory("st0-all-due").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val dueAt = listOf(600_000L, 1_200_000L, 1_800_000L)
        val tasks = dueAt.mapIndexed { index, time -> store.create(task("serial-$index",
            TaskSchedule.At(java.time.Instant.ofEpochMilli(time).atOffset(java.time.ZoneOffset.UTC)
                .toLocalDateTime().toString(), TaskZone.Iana("UTC")), nextRunAt = time)) }
        val order = mutableListOf<String>()
        val executor = TaskExecutor { task, _, _, _, _ -> order += task.id; TaskExecutionResult() }
        val result = TaskTickEngine(store, ledger, calculator(2_000_000L), executor,
            fixedClock(2_000_000L)).tick()

        assertEquals(TaskTickResult(3, 0, false), result)
        assertEquals(tasks.map { it.id }, order)
        assertEquals(3, ledger.read().runs.size)
        assertTrue(tasks.all { store.get(it.id)?.state == TaskState.Done })
    }

    @Test fun globalPauseActivatedDuringFirstRunPreventsStartingTheNextDueTask() {
        val root = Files.createTempDirectory("st3-mid-tick-pause").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val now = 2_000_000L
        val due = listOf(600_000L, 1_200_000L).mapIndexed { index, time ->
            store.create(task("pause-$index", TaskSchedule.At(
                Instant.ofEpochMilli(time).atOffset(ZoneOffset.UTC).toLocalDateTime().toString(), TaskZone.Iana("UTC")),
                nextRunAt = time))
        }
        var paused = false
        var rearmed = 0
        val started = mutableListOf<String>()
        val executor = TaskExecutor { task, _, _, _, _ ->
            started += task.id
            paused = true
            TaskExecutionResult()
        }
        val result = TaskTickEngine(store, ledger, calculator(now), executor, fixedClock(now),
            { rearmed++ }, isGloballyPaused = { paused }).tick()

        assertEquals(listOf(due.first().id), started)
        assertEquals(TaskTickResult(1, 0, true), result)
        assertEquals(TaskState.Done, store.get(due.first().id)?.state)
        assertEquals(TaskState.Active, store.get(due.last().id)?.state)
        assertEquals(1, rearmed)
    }

    private fun task(
        name: String,
        schedule: TaskSchedule,
        catchUp: TaskCatchUp = TaskCatchUp.RUN_LATE_ONCE,
        nextRunAt: Long?,
        validUntil: Long? = null,
    ) = ScheduledTask(name = name, instruction = "Record only", schedule = schedule,
        catchUp = catchUp, nextRunAt = nextRunAt, validUntil = validUntil, createdAt = 1L)

    private fun fixedClock(now: Long) = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private fun calculator(now: Long) = ScheduleCalculator(fixedClock(now))
}
