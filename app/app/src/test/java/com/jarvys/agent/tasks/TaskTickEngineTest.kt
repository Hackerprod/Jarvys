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
        assertEquals("RETRYABLE", ledger.read().runs.single().status)
        assertEquals("RETRYABLE", store.get(task.id)?.lastRun?.status)
        assertEquals(TaskState.Active, store.get(task.id)?.state)
        assertNull(store.get(task.id)?.openIncidentCause)
        assertEquals(anchor, store.get(task.id)?.nextRunAt)

        val second = TaskTickEngine(store, ledger, calculator(now), executor, clock).tick()
        assertFalse(second.retryWork)
        assertEquals(listOf(anchor, anchor), attempts)
        assertEquals(1, ledger.read().runs.size)
        assertEquals(anchor, ledger.read().runs.single().scheduledFor)
        assertEquals(anchor + interval, store.get(task.id)?.nextRunAt)
    }

    @Test fun pendingActionKeepsReviewedRevisionAndBlocksAnotherScheduledRun() {
        val root = Files.createTempDirectory("task-pending-action-tick").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val pending = TaskPendingActionStore(File(root, "pending-actions.jsonl"))
        val anchor = 1_000_000L
        val interval = 900_000L
        val now = anchor + 300_000L
        val task = store.create(task("approval", TaskSchedule.Every(interval, anchor), nextRunAt = anchor))
        var executions = 0
        val executor = TaskExecutor { current, _, _, runId, _ ->
            executions++
            pending.enqueue(current, runId, "gmail_send_message", """{"to":"person@example.test"}""")
            TaskExecutionResult(toolsCalled = listOf(TaskToolCallRecord("gmail_send_message", true, "PENDING")))
        }
        val engine = TaskTickEngine(store, ledger, calculator(now), executor, fixedClock(now),
            pendingActions = pending)

        assertEquals(1, engine.tick().started)
        val afterRun = requireNotNull(store.get(task.id))
        assertEquals(task.revision, afterRun.revision)
        assertEquals(anchor + interval, afterRun.nextRunAt)
        assertEquals(TaskState.AwaitingUser, pending.stateForTask(task.id, afterRun.revision, afterRun.state))

        assertEquals(0, engine.tick().started)
        assertEquals(1, executions)
        assertEquals(task.revision, store.get(task.id)?.revision)
    }

    @Test fun permanentFailureOpensCauseIncidentAndNeedsAttention() {
        val root = Files.createTempDirectory("task-permanent-failure").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val now = 200_000L
        val task = store.create(task("permanent", TaskSchedule.Every(900_000L, now - 1L), nextRunAt = now - 1L))
        val executor = TaskExecutor { _, _, _, _, _ -> TaskExecutionResult("ERROR", "CHAT_ONLY",
            causeCode = TaskRunCause.TOOL_SCOPE_UNAVAILABLE) }

        TaskTickEngine(store, ledger, calculator(now), executor, fixedClock(now)).tick()

        val failed = requireNotNull(store.get(task.id))
        assertEquals(TaskRunCause.TOOL_SCOPE_UNAVAILABLE, failed.openIncidentCause)
        assertEquals(TaskState.NeedsAttention(TaskRunCause.TOOL_SCOPE_UNAVAILABLE.code), failed.state)
    }

    @Test fun stoppedTickRecordsInterruptionAndDurableNoEffectReceiptForSafeRetry() {
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
        assertEquals("INTERRUPTED", ledger.read().runs.single().status)
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

    @Test fun successfulDeleteAfterRunOneShotRemovesTaskAndRetainsDeliveredRunHistory() {
        val root = Files.createTempDirectory("task-delete-after-run").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val at = 100_000L
        val oneShot = store.create(task("delete after success",
            TaskSchedule.At("1970-01-01T00:01:40", TaskZone.Iana("UTC")), nextRunAt = at)
            .copy(deleteAfterRun = true))
        val executor = TaskExecutor { _, _, _, _, _ ->
            TaskExecutionResult("OK", "DELIVERED", deliveredResult = "The final model response.",
                conversationMessageId = "stable-message")
        }

        TaskTickEngine(store, ledger, calculator(at), executor, fixedClock(at)).tick()

        assertNull(store.get(oneShot.id))
        val run = ledger.forTask(oneShot.id).single()
        assertEquals("OK", run.status)
        assertEquals("The final model response.", run.deliveredResult)
        assertEquals("stable-message", run.conversationMessageId)
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

    @Test fun claimIsDurableBeforeDispatchAndCrashDuringEffectNeverReplaysOccurrence() {
        val root = Files.createTempDirectory("task-crash-during-effect").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledgerFile = File(root, "runs.jsonl")
        val ledger = TaskRunLedger(ledgerFile)
        val at = 100_000L
        val interval = 900_000L
        val task = store.create(task("crash", TaskSchedule.Every(interval, at), nextRunAt = at))
        var effects = 0
        val executor = TaskExecutor { current, scheduledFor, _, runId, _ ->
            val claim = requireNotNull(TaskRunLedger(ledgerFile).find(current.id, scheduledFor))
            assertEquals("STARTED", claim.status)
            assertEquals(runId, claim.runId)
            effects++
            throw SimulatedProcessDeath()
        }
        val first = runCatching { TaskTickEngine(store, ledger, calculator(at), executor, fixedClock(at)).tick() }
        assertTrue(first.exceptionOrNull() is SimulatedProcessDeath)
        assertEquals("STARTED", ledger.find(task.id, at)?.status)
        assertNull(store.get(task.id)?.lastRun)

        // A later restart must reconcile the old claim, not advance to another due interval first.
        val later = at + interval * 2
        val recovered = TaskTickEngine(store, TaskRunLedger(ledgerFile), calculator(later),
            TaskExecutor { _, _, _, _, _ -> effects++; TaskExecutionResult() }, fixedClock(later)).tick()
        assertEquals(0, recovered.started)
        assertEquals(1, effects)
        assertEquals("PARTIAL", ledger.find(task.id, at)?.status)
        assertEquals(TaskRunCause.PREVIOUS_OCCURRENCE_INTERRUPTED, ledger.find(task.id, at)?.causeCode)
        assertEquals(TaskState.NeedsAttention(TaskRunCause.PREVIOUS_OCCURRENCE_INTERRUPTED.code), store.get(task.id)?.state)
        assertFalse(ledger.claim(TaskRunRecord(task.id, "new-attempt", at, later)))
    }

    @Test fun expiryAfterCrashDoesNotHideAnUncertainOccurrence() {
        val root = Files.createTempDirectory("task-expired-crash").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val at = 100_000L
        val task = store.create(task("expires after effect", TaskSchedule.Every(900_000L, at),
            nextRunAt = at, validUntil = at + 1))
        val first = runCatching {
            TaskTickEngine(store, ledger, calculator(at), TaskExecutor { _, _, _, _, _ ->
                throw SimulatedProcessDeath()
            }, fixedClock(at)).tick()
        }
        assertTrue(first.exceptionOrNull() is SimulatedProcessDeath)
        val effects = AtomicInteger()
        val expiredAt = at + 2
        val result = TaskTickEngine(store, ledger, calculator(expiredAt), TaskExecutor { _, _, _, _, _ ->
            effects.incrementAndGet(); TaskExecutionResult()
        }, fixedClock(expiredAt)).tick()
        assertEquals(0, result.started)
        assertEquals(0, effects.get())
        assertEquals("PARTIAL", ledger.find(task.id, at)?.status)
        assertEquals(TaskState.NeedsAttention(TaskRunCause.PREVIOUS_OCCURRENCE_INTERRUPTED.code), store.get(task.id)?.state)
    }

    @Test fun crashAfterExecutorReturnsBeforeFinalReceiptAlsoNeverReplays() {
        val root = Files.createTempDirectory("task-crash-after-effect").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val at = 100_000L
        val task = store.create(task("result gap", TaskSchedule.Every(900_000L, at), nextRunAt = at))
        var effects = 0
        val executor = TaskExecutor { _, _, _, _, _ -> effects++; TaskExecutionResult("OK", "DELIVERED") }
        val interruptedClock = object : Clock() {
            var reads = 0
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(millis())
            override fun millis(): Long {
                if (++reads == 3) throw SimulatedProcessDeath()
                return at
            }
        }
        assertTrue(runCatching {
            TaskTickEngine(store, ledger, calculator(at), executor, interruptedClock).tick()
        }.exceptionOrNull() is SimulatedProcessDeath)
        assertEquals("STARTED", ledger.find(task.id, at)?.status)
        TaskTickEngine(store, ledger, calculator(at), executor, fixedClock(at)).tick()
        assertEquals(1, effects)
        assertEquals("PARTIAL", ledger.find(task.id, at)?.status)
    }

    @Test fun escapedCancellationIsUncertainRatherThanAProofThatNoWriteOccurred() {
        val root = Files.createTempDirectory("task-escaped-cancellation").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val at = 100_000L
        val task = store.create(task("cancel after effect", TaskSchedule.Every(900_000L, at), nextRunAt = at))
        var effects = 0
        val executor = TaskExecutor { _, _, _, _, _ ->
            effects++
            throw java.util.concurrent.CancellationException("cancelled after dispatch")
        }
        val result = TaskTickEngine(store, ledger, calculator(at), executor, fixedClock(at)).tick()
        assertFalse(result.retryWork)
        TaskTickEngine(store, ledger, calculator(at), executor, fixedClock(at)).tick()
        assertEquals(1, effects)
        assertEquals("PARTIAL", ledger.find(task.id, at)?.status)
        assertEquals("UNKNOWN", ledger.find(task.id, at)?.deliveryStatus)
    }

    @Test fun explicitNoDispatchReceiptsPreserveExistingRetryWithoutRepeatingAWrite() {
        for (outcome in listOf("NOT_EXECUTED", "PENDING")) {
            val root = Files.createTempDirectory("task-proven-no-dispatch").toFile()
            val store = TaskStore(File(root, "tasks.jsonl"))
            val ledger = TaskRunLedger(File(root, "runs.jsonl"))
            val at = 100_000L
            val task = store.create(task("no dispatch", TaskSchedule.Every(900_000L, at), nextRunAt = at))
            val attempts = AtomicInteger()
            val executor = TaskExecutor { _, _, _, _, _ ->
                if (attempts.incrementAndGet() == 1) TaskExecutionResult("RETRYABLE",
                    toolsCalled = listOf(TaskToolCallRecord("write", true, outcome, noDispatchVerified = true)))
                else TaskExecutionResult("OK")
            }
            assertTrue(TaskTickEngine(store, ledger, calculator(at), executor, fixedClock(at)).tick().retryWork)
            assertFalse(TaskTickEngine(store, ledger, calculator(at), executor, fixedClock(at)).tick().retryWork)
            assertEquals(2, attempts.get())
            assertEquals("OK", ledger.find(task.id, at)?.status)
            assertEquals(1, ledger.read().runs.size)
        }
    }

    @Test fun onlyExplicitNoDispatchLabelsPermitWriteRetry() {
        for (outcome in listOf("UNKNOWN", "ERROR", "WAITING_VISIBLE_APP", "OK", "", "not_executed"))
            assertTrue(TaskToolCallRecord("write", true, outcome).mayHaveWritten())
        assertFalse(TaskToolCallRecord("write", true, "NOT_EXECUTED", noDispatchVerified = true).mayHaveWritten())
        assertFalse(TaskToolCallRecord("write", true, "PENDING", noDispatchVerified = true).mayHaveWritten())
        assertTrue(TaskToolCallRecord("write", true, "NOT_EXECUTED").mayHaveWritten())
        assertTrue(TaskToolCallRecord("write", true, "PENDING").mayHaveWritten())
        assertFalse(TaskToolCallRecord("read", false, "ERROR").mayHaveWritten())
    }

    @Test fun interruptedReceiptWithWriteCannotBecomeAnAutomaticRetry() {
        val root = Files.createTempDirectory("task-interrupted-write-receipt").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val at = 100_000L
        val task = store.create(task("write receipt", TaskSchedule.Every(900_000L, at), nextRunAt = at))
        val effects = AtomicInteger()
        val executor = TaskExecutor { _, _, _, _, _ ->
            effects.incrementAndGet()
            TaskExecutionResult("RETRYABLE", toolsCalled = listOf(TaskToolCallRecord("write", true, "UNKNOWN")))
        }
        val first = TaskTickEngine(store, ledger, calculator(at), executor, fixedClock(at)).tick()
        assertFalse(first.retryWork)
        TaskTickEngine(store, ledger, calculator(at), executor, fixedClock(at)).tick()
        assertEquals(1, effects.get())
        assertEquals("PARTIAL", ledger.find(task.id, at)?.status)
    }

    @Test fun terminalUncertaintyIsProjectedAfterCrashBeforeTaskSnapshotUpdate() {
        val root = Files.createTempDirectory("task-unprojected-uncertainty").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val at = 100_000L
        val task = store.create(task("recovery gap", TaskSchedule.Every(900_000L, at), nextRunAt = at))
        val claim = TaskRunRecord(task.id, "original-run", at, at)
        assertTrue(ledger.claim(claim))
        ledger.saveSnapshot(claim.copy(status = "PARTIAL", finishedAt = at + 1,
            causeCode = TaskRunCause.PREVIOUS_OCCURRENCE_INTERRUPTED, deliveryStatus = "UNKNOWN"))
        val effects = AtomicInteger()
        val later = at + 2 * 900_000L
        val result = TaskTickEngine(store, ledger, calculator(later), TaskExecutor { _, _, _, _, _ ->
            effects.incrementAndGet(); TaskExecutionResult()
        }, fixedClock(later)).tick()
        assertEquals(0, result.started)
        assertEquals(0, effects.get())
        assertEquals("original-run", store.get(task.id)?.lastRun?.runId)
        assertEquals(TaskState.NeedsAttention(TaskRunCause.PREVIOUS_OCCURRENCE_INTERRUPTED.code), store.get(task.id)?.state)
    }

    @Test fun concurrentDuplicateTicksRunAnOccurrenceOnlyOnce() {
        val root = Files.createTempDirectory("task-concurrent-claim").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledgerFile = File(root, "runs.jsonl")
        val at = 100_000L
        store.create(task("concurrent", TaskSchedule.Every(900_000L, at), nextRunAt = at))
        val effects = AtomicInteger()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = TaskExecutor { _, _, _, _, _ ->
            effects.incrementAndGet()
            entered.countDown()
            assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            TaskExecutionResult()
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            fun tick() = TaskTickEngine(store, TaskRunLedger(ledgerFile), calculator(at), executor, fixedClock(at)).tick()
            val first = pool.submit<TaskTickResult> { tick() }
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val second = pool.submit<TaskTickResult> { tick() }
            release.countDown()
            first.get(10, java.util.concurrent.TimeUnit.SECONDS)
            second.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(1, effects.get())
            assertEquals("OK", TaskRunLedger(ledgerFile).read().runs.single().status)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun unavailableDurableLedgerNeverInvokesExecutor() {
        val root = Files.createTempDirectory("task-claim-storage-failure").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val notDirectory = File(root, "not-a-directory").apply { writeText("unavailable ledger parent") }
        val ledger = TaskRunLedger(File(notDirectory, "runs.jsonl"))
        val at = 100_000L
        store.create(task("claim failure", TaskSchedule.Every(900_000L, at), nextRunAt = at))
        val effects = AtomicInteger()
        val failure = runCatching {
            TaskTickEngine(store, ledger, calculator(at), TaskExecutor { _, _, _, _, _ ->
                effects.incrementAndGet(); TaskExecutionResult()
            }, fixedClock(at)).tick()
        }.exceptionOrNull()
        assertTrue("Unavailable durable storage must stop the tick", failure != null)
        assertEquals(0, effects.get())
    }

    private class SimulatedProcessDeath : Error()

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
