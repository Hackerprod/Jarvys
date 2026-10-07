package com.jarvys.agent.tasks

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.jarvys.agent.WorkManagerTestCleanup
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskWorkManagerTest {
    private lateinit var context: Context
    private lateinit var manager: WorkManager
    private lateinit var taskFile: File
    private lateinit var runFile: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(TaskGlobalPauseStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        val directory = File(File(context.filesDir, "jarvys"), "tasks").apply {
            if (exists()) listFiles()?.forEach(File::delete)
            else mkdirs()
        }
        taskFile = File(directory, "tasks.jsonl")
        runFile = File(directory, "runs.jsonl")
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(Executor { it.run() }).build(),
            WorkManagerTestInitHelper.ExecutorsMode.LEGACY_OVERRIDE_WITH_SYNCHRONOUS_EXECUTORS)
        manager = WorkManager.getInstance(context)
        manager.cancelAllWork().result.get()
    }

    @After fun tearDown() {
        WorkManagerTestCleanup.close(context)
    }

    @Test fun rearmSelectsEarliestDeadlineReplacesUniqueWorkAndCancelsWhenNoneRemain() {
        val store = TaskStore(taskFile)
        val now = System.currentTimeMillis()
        val first = store.create(task("first", now + 90_000L))
        val second = store.create(task("second", now + 20_000L))
        store.create(task("third", now + 60_000L))
        TaskScheduler.rearm(context, store, now)
        val firstActive = activeTickWork()
        assertEquals(1, firstActive.size)
        val firstInfo = firstActive.single()
        assertTrue(kotlin.math.abs(firstInfo.nextScheduleTimeMillis - (now + 20_000L)) < 2_000L)

        store.update(second.copy(state = TaskState.Paused, updatedAt = now + 1L), second.revision)
        TaskScheduler.rearm(context, store, now + 1L)
        val replacement = activeTickWork()
        assertEquals(1, replacement.size)
        assertTrue(replacement.single().id != firstInfo.id)
        assertTrue(kotlin.math.abs(replacement.single().nextScheduleTimeMillis - (now + 60_000L)) < 2_000L)

        store.update(first.copy(state = TaskState.Paused, updatedAt = now + 2L), first.revision)
        store.list().filter { it.state == TaskState.Active }.forEach { task ->
            store.update(task.copy(state = TaskState.Paused, updatedAt = now + 3L), task.revision)
        }
        TaskScheduler.rearm(context, store, now + 3L)
        assertTrue(activeTickWork().isEmpty())
        assertTrue(manager.getWorkInfosForUniqueWork(TaskWorkNames.TICK).get().any { it.state == WorkInfo.State.CANCELLED })
    }

    @Test fun rearmRecalculatesFollowDeviceWallTimeAfterZoneChangeAndLeavesExplicitZoneUnchanged() {
        val store = TaskStore(taskFile)
        val now = Instant.parse("2025-01-02T00:00:00Z").toEpochMilli()
        val deviceZone = AtomicReference(ZoneId.of("America/New_York"))
        val calculator = ScheduleCalculator(Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
            TaskZoneProvider { deviceZone.get() })
        val follow = store.create(ScheduledTask(name = "Follow", instruction = "x",
            schedule = TaskSchedule.At("2025-01-03T08:00:00", TaskZone.FollowDevice),
            nextRunAt = Instant.parse("2025-01-03T13:00:00Z").toEpochMilli(), createdAt = 1L))
        val fixed = store.create(ScheduledTask(name = "Fixed", instruction = "x",
            schedule = TaskSchedule.At("2025-01-03T08:00:00", TaskZone.Iana("America/New_York")),
            nextRunAt = Instant.parse("2025-01-03T13:00:00Z").toEpochMilli(), createdAt = 1L))
        TaskScheduler.rearm(context, store, now, calculator)
        deviceZone.set(ZoneId.of("Europe/London"))
        TaskScheduler.rearm(context, store, now, calculator)

        assertEquals(Instant.parse("2025-01-03T08:00:00Z").toEpochMilli(), store.get(follow.id)?.nextRunAt)
        assertEquals(Instant.parse("2025-01-03T13:00:00Z").toEpochMilli(), store.get(fixed.id)?.nextRunAt)
    }

    @Test fun workerProcessesDueTasksAndCallsRearmBeforeReturning() {
        assertTrue(TaskTickWorker.MAX_TICK_PROCESSING_MILLIS < TimeUnit.MINUTES.toMillis(10))
        val now = Instant.parse("2025-01-02T12:00:00Z").toEpochMilli()
        val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
        val store = TaskStore(taskFile)
        val ledger = TaskRunLedger(runFile)
        val due = store.create(task("due", now - 1L))
        val executed = mutableListOf<String>()
        var rearmed = 0
        val executor = TaskExecutor { task, _, _, _, _ -> executed += task.id; TaskExecutionResult() }
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String,
                                      workerParameters: androidx.work.WorkerParameters): ListenableWorker? =
                if (workerClassName == TaskTickWorker::class.java.name) TaskTickWorker(
                    appContext, workerParameters, executor, store, ledger, clock,
                    ScheduleCalculator(clock), {
                        rearmed++
                        TaskScheduler.rearm(context, store, now, ScheduleCalculator(clock))
                    }) else null
        }
        val result = runBlocking {
            TestListenableWorkerBuilder.from(context, TaskTickWorker::class.java)
                .setWorkerFactory(factory).build().doWork()
        }
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf(due.id), executed)
        assertEquals(TaskState.Active, store.get(due.id)?.state)
        assertTrue(store.get(due.id)?.nextRunAt!! > now)
        assertEquals(1, ledger.read().runs.size)
        assertEquals(1, rearmed)
        assertEquals(1, activeTickWork().size)
    }

    @Test fun retryUsesWorkManagerRetryAndRepeatsTheSameOccurrenceUntilOneFinalLedgerRow() {
        val now = Instant.parse("2025-02-01T10:00:00Z").toEpochMilli()
        val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
        val store = TaskStore(taskFile)
        val ledger = TaskRunLedger(runFile)
        val due = store.create(task("retry-same", now - 1L))
        val attempts = AtomicInteger()
        val scheduled = CopyOnWriteArrayList<Long>()
        val executor = TaskExecutor { _, scheduledFor, _, _, _ ->
            scheduled += scheduledFor
            if (attempts.getAndIncrement() == 0) TaskExecutionResult("RETRYABLE", "NOT_APPLICABLE", "429")
            else TaskExecutionResult("OK", "CHAT_ONLY")
        }
        var rearmed = 0
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String,
                                      workerParameters: androidx.work.WorkerParameters): ListenableWorker? =
                if (workerClassName == TaskTickWorker::class.java.name) TaskTickWorker(
                    appContext, workerParameters, executor, store, ledger, clock,
                    ScheduleCalculator(clock), { rearmed++ }) else null
        }
        fun runWorker() = runBlocking {
            TestListenableWorkerBuilder.from(context, TaskTickWorker::class.java)
                .setWorkerFactory(factory).build().doWork()
        }

        assertTrue(runWorker() is ListenableWorker.Result.Retry)
        assertEquals("RETRYABLE", store.get(due.id)?.lastRun?.status)
        assertTrue(ledger.read().runs.isEmpty())
        assertEquals(0, rearmed)
        assertTrue(runWorker() is ListenableWorker.Result.Success)
        assertEquals(listOf(now - 1L, now - 1L), scheduled)
        assertEquals(1, ledger.read().runs.size)
        assertEquals(1, runFile.readLines().size)
        assertEquals("OK", store.get(due.id)?.lastRun?.status)
        assertEquals(1, rearmed)
    }

    @Test fun globalPauseCancelsTickResumeSkipsMissedRunsAndManualRunStillQueues() {
        val now = Instant.parse("2025-04-01T09:00:00Z").toEpochMilli()
        val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
        val store = TaskStore(taskFile)
        val due = store.create(task("pause-resume", now + TimeUnit.MINUTES.toMillis(1)))
        val calculator = ScheduleCalculator(clock)
        TaskScheduler.rearm(context, store, now, calculator)
        val queuedSnapshot = requireNotNull(store.get(due.id))
        store.update(queuedSnapshot.copy(nextRunAt = now - TimeUnit.HOURS.toMillis(2)), queuedSnapshot.revision)
        assertEquals(1, activeTickWork().size)

        assertTrue(TaskScheduler.setAllPaused(context, true, now, store, calculator))
        assertTrue(TaskGlobalPauseStore(context).isPaused())
        assertTrue(activeTickWork().isEmpty())
        assertTrue(manager.getWorkInfosForUniqueWork(TaskWorkNames.TICK).get()
            .any { it.state == WorkInfo.State.CANCELLED })

        val manualOccurrence = "manual:${due.id}:while-paused"
        TaskManualRunScheduler.enqueue(context, due.id, manualOccurrence)
        assertTrue(manager.getWorkInfosForUniqueWork(TaskManualRunScheduler.uniqueWorkName(due.id)).get().isNotEmpty())

        val resumedAt = now + TimeUnit.HOURS.toMillis(3)
        assertTrue(TaskScheduler.setAllPaused(context, false, resumedAt, store, calculator))
        assertFalse(TaskGlobalPauseStore(context).isPaused())
        val resumed = requireNotNull(store.get(due.id))
        assertEquals(TaskState.Active, resumed.state)
        assertTrue(requireNotNull(resumed.nextRunAt) > resumedAt)
        val executor = RecordingTaskExecutor()
        val pausedMissedResult = TaskTickEngine(store,
            TaskRunLedger(File(Files.createTempDirectory("st3-resumed-runs").toFile(), "runs.jsonl")),
            ScheduleCalculator(Clock.fixed(Instant.ofEpochMilli(resumedAt), ZoneOffset.UTC)), executor,
            Clock.fixed(Instant.ofEpochMilli(resumedAt), ZoneOffset.UTC)).tick()
        assertEquals(0, pausedMissedResult.started)
        assertTrue(executor.occurrences().isEmpty())
        assertEquals(1, activeTickWork().size)
    }

    @Test fun manifestRearmReceiverIsNotExportedAndHasOnlyTimeAndPackageActions() {
        val info = context.packageManager.getReceiverInfo(ComponentName(context, TaskRearmReceiver::class.java), 0)
        assertFalse(info.exported)
        val filters = listOf(Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
        filters.forEach { action ->
            val intent = Intent(action).setPackage(context.packageName)
            assertTrue("missing manifest receiver action $action",
                context.packageManager.queryBroadcastReceivers(intent, 0).any { it.activityInfo.name == TaskRearmReceiver::class.java.name })
        }
        assertNotNull(context.packageManager.getReceiverInfo(ComponentName(context, TaskRearmReceiver::class.java), 0))
    }

    private fun activeTickWork() = manager.getWorkInfosForUniqueWork(TaskWorkNames.TICK).get()
        .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }

    private fun task(name: String, nextRunAt: Long) = ScheduledTask(
        name = name, instruction = "Record one occurrence",
        schedule = TaskSchedule.Every(15L * 60L * 1000L, nextRunAt),
        nextRunAt = nextRunAt, createdAt = 1L,
    )
}
