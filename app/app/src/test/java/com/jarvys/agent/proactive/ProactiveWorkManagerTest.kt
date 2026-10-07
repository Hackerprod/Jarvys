package com.jarvys.agent.proactive

import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.TestListenableWorkerBuilder
import com.jarvys.agent.AgentRunUiState
import com.jarvys.agent.WorkManagerTestCleanup
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProactiveWorkManagerTest {
    private lateinit var context: android.content.Context
    private lateinit var workManager: WorkManager
    private lateinit var testProcessor: ProactiveBatchProcessor
    private var workerResult: ProactiveProcessingResult = ProactiveProcessingResult.Completed
    private val testWorkerFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: android.content.Context,
            workerClassName: String,
            workerParameters: androidx.work.WorkerParameters,
        ): ListenableWorker? = if (workerClassName == ProactiveWorker::class.java.name) {
            ProactiveWorker(appContext, workerParameters, testProcessor)
        } else null
    }

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        testProcessor = object : ProactiveBatchProcessor {
            override fun review(batches: List<ProactiveEventBatch>, token: com.jarvys.agent.CancellationToken): ProactiveProcessingResult {
                token.throwIfCancelled()
                ProactiveReviewStateStore(context).recordReview(System.currentTimeMillis(), batches.size)
                reviewedBatches += batches
                when (val result = workerResult) {
                    is ProactiveProcessingResult.Retryable -> ProactiveReviewStateStore(context).recordFailure(result.reason)
                    is ProactiveProcessingResult.Failed -> ProactiveReviewStateStore(context).recordFailure(result.reason)
                    ProactiveProcessingResult.Completed -> ProactiveReviewStateStore(context).clearFailure()
                    ProactiveProcessingResult.Stopped -> Unit
                }
                return workerResult
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(Executor { command -> command.run() })
                .setWorkerFactory(testWorkerFactory)
                .build(),
            WorkManagerTestInitHelper.ExecutorsMode.LEGACY_OVERRIDE_WITH_SYNCHRONOUS_EXECUTORS,
        )
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get()
        context.getSharedPreferences("jarvys_proactive_preferences", 0).edit().clear().commit()
        context.getSharedPreferences("jarvys_proactive_review_state", 0).edit().clear().commit()
        eventFile().delete()
        captureBoundaryFile().delete()
        workerResult = ProactiveProcessingResult.Completed
        reviewedBatches.clear()
        AgentRunUiState.restoreSession("p3-work-test-reset", emptyList())
        ProactiveRunController.cancelAll()
    }

    @After fun tearDown() {
        AgentRunUiState.restoreSession("p2-test-reset", emptyList())
        WorkManagerTestCleanup.close(context)
        reviewedBatches.clear()
    }

    @Test fun enabledKeepsOnePlatformMinimumPeriodicWorkAndDisabledCancelsBothNames() {
        val preferences = ProactivePreferences(context)
        assertFalse(preferences.enabled)
        preferences.enabled = true
        val periodic = activeWork(ProactiveWorkNames.PERIODIC)
        assertEquals(1, periodic.size)
        assertTrue(periodic.single().state == WorkInfo.State.ENQUEUED || periodic.single().state == WorkInfo.State.RUNNING)
        assertEquals(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
            ProactiveScheduler.PERIODIC_INTERVAL_MILLIS)
        assertEquals(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
            periodic.single().periodicityInfo?.repeatIntervalMillis)
        preferences.enabled = true
        assertEquals(1, activeWork(ProactiveWorkNames.PERIODIC).size)

        AgentRunUiState.beginRun("p2-cancel-run", "foreground task")
        try {
            assertTrue(ProactiveScheduler.requestRunNow(context))
            val runNow = activeWork(ProactiveWorkNames.RUN_NOW)
            assertEquals(1, runNow.size)
            preferences.enabled = false
            assertTrue(activeWork(ProactiveWorkNames.PERIODIC).isEmpty())
            assertTrue(activeWork(ProactiveWorkNames.RUN_NOW).isEmpty())
            assertFalse(ProactiveScheduler.requestRunNow(context))
        } finally {
            AgentRunUiState.restoreSession("p2-cancel-run-reset", emptyList())
        }
    }

    @Test fun punctualRequestsKeepOneUniqueChainWhileRunIsActiveAndBackoffOwnsRetryTiming() {
        val preferences = ProactivePreferences(context)
        preferences.enabled = true
        workManager.cancelUniqueWork(ProactiveWorkNames.PERIODIC).result.get()
        val checkBeforePunctualRun = ProactiveReviewStateStore(context).lastCheckMillis()
        AgentRunUiState.beginRun("p2-active-run", "existing foreground task")
        try {
            assertTrue(ProactiveScheduler.requestRunNow(context))
            val first = activeWork(ProactiveWorkNames.RUN_NOW).single()
            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(first.id)
            assertTrue(activeWork(ProactiveWorkNames.RUN_NOW).single().runAttemptCount > 0)
            repeat(5) { assertTrue(ProactiveScheduler.requestRunNow(context)) }
            val current = activeWork(ProactiveWorkNames.RUN_NOW)
            assertEquals(1, current.size)
            assertEquals(first.id, current.single().id)
            assertEquals(checkBeforePunctualRun, ProactiveReviewStateStore(context).lastCheckMillis())
        } finally {
            AgentRunUiState.restoreSession("p2-active-run-reset", emptyList())
        }
    }

    @Test fun workerReviewsPendingBatchesWithoutConsumingAndPublishesCountsAndNextWorkTime() {
        ProactivePreferences(context).enabled = true
        workManager.cancelUniqueWork(ProactiveWorkNames.PERIODIC).result.get()
        val store = ProactiveEventStore(eventFile())
        val now = System.currentTimeMillis()
        store.append(event(sender = "Ada", body = "first", receivedAt = now + 100))
        store.append(event(sender = "Ada", body = "second", receivedAt = now + 200))

        val result = runProactiveWorker()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(2, store.pending().size)
        assertTrue("processor input did not preserve the pending IDs: $reviewedBatches", reviewedBatches.any { batches ->
            batches.size == 1 && batches.single().events.map { it.id }.toSet() == store.pending().map { it.id }.toSet()
        })
        val review = ProactiveReviewStateStore(context)
        assertTrue(review.lastCheckMillis() > 0L)
        assertEquals(1, review.lastBatchesSeen())
        val status = kotlinx.coroutines.runBlocking { ProactiveStatusProvider.read(context) }
        assertTrue(status.enabled)
        assertTrue(status.lastCheckMillis > 0L)
        assertEquals(2, status.pendingCount)
        assertEquals(0, status.discardedCount)
        assertNull(status.nextCheckMillis)
    }

    @Test fun workerReturnsWorkManagerRetryForAnActiveAgentWithoutTouchingQueueOrReviewState() {
        ProactivePreferences(context).enabled = true
        workManager.cancelUniqueWork(ProactiveWorkNames.PERIODIC).result.get()
        val checkBeforeWorker = ProactiveReviewStateStore(context).lastCheckMillis()
        val store = ProactiveEventStore(eventFile())
        store.append(event(body = "do not touch", receivedAt = System.currentTimeMillis() + 300))
        AgentRunUiState.beginRun("p2-busy-run", "foreground task")
        try {
            val result = runProactiveWorker()
            assertTrue(result is ListenableWorker.Result.Retry)
            assertEquals(1, store.pending().size)
            assertEquals(checkBeforeWorker, ProactiveReviewStateStore(context).lastCheckMillis())
        } finally {
            AgentRunUiState.restoreSession("p2-busy-run-reset", emptyList())
        }
    }

    @Test fun retryableProcessorResultMapsToWorkManagerRetryAndLeavesBatchPending() {
        ProactivePreferences(context).enabled = true
        workManager.cancelUniqueWork(ProactiveWorkNames.PERIODIC).result.get()
        val eventStore = ProactiveEventStore(eventFile())
        val event = event(sender = "Ava", body = "needs provider", receivedAt = System.currentTimeMillis() + 350)
        eventStore.append(event)
        workerResult = ProactiveProcessingResult.Retryable("sin proveedor")

        val result = runProactiveWorker()

        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals(listOf(event.id), eventStore.pending().map { it.id })
        assertEquals("sin proveedor", ProactiveReviewStateStore(context).lastFailureReason())
    }

    @Test fun listenerDispatchExtractsAndStoresOnlyWhenEnabledAndRequestsRunOnlyForCandidates() {
        val preferences = ProactivePreferences(context)
        var extractions = 0
        val off = ProactiveNotificationDispatch.onNotification(
            context, preferences, { extractions++; input("off secret") }, context.packageName,
        )
        assertEquals(ProactiveCaptureResult.Disabled, off)
        assertEquals(0, extractions)
        assertFalse(eventFile().exists())
        assertTrue(activeWork(ProactiveWorkNames.RUN_NOW).isEmpty())

        preferences.enabled = true
        val saved = ProactiveNotificationDispatch.onNotification(
            context, preferences, { extractions++; input("candidate content", key = "candidate-key") }, context.packageName,
        )
        assertEquals(ProactiveCaptureResult.CandidateStored("msg"), saved)
        val afterCandidate = workManager.getWorkInfosForUniqueWork(ProactiveWorkNames.RUN_NOW).get().map { it.id }
        assertTrue(afterCandidate.isNotEmpty())

        val discarded = ProactiveNotificationDispatch.onNotification(
            context, preferences,
            { extractions++; input("ongoing content", key = "ongoing-key", ongoing = true) },
            context.packageName,
        )
        assertEquals(ProactiveCaptureResult.DiscardedStored("ongoing_notification"), discarded)
        assertEquals(afterCandidate, workManager.getWorkInfosForUniqueWork(ProactiveWorkNames.RUN_NOW).get().map { it.id })
        assertEquals(2, extractions)
    }

    @Test fun onlyTimeSensitiveCategoriesRequestImmediateProviderWork() {
        ProactivePreferences(context).enabled = true
        listOf("msg", "call", "missed_call", "email").forEach {
            assertTrue(ProactiveImmediatePolicy.shouldRequestRunNow(it))
        }
        listOf("social", "other").forEach { category ->
            assertFalse(ProactiveImmediatePolicy.shouldRequestRunNow(category))
            val result = ProactiveNotificationDispatch.onNotification(
                context,
                ProactivePreferences(context),
                { input("wait for the periodic $category review", key = "key-$category", androidCategory = category) },
                context.packageName,
            )
            assertEquals(ProactiveCaptureResult.CandidateStored(category), result)
        }
        assertTrue(workManager.getWorkInfosForUniqueWork(ProactiveWorkNames.RUN_NOW).get().isEmpty())
        val email = ProactiveNotificationDispatch.onNotification(
            context,
            ProactivePreferences(context),
            { input("time-sensitive", key = "key-email", androidCategory = "email") },
            context.packageName,
        )
        assertEquals(ProactiveCaptureResult.CandidateStored("email"), email)
        assertTrue(workManager.getWorkInfosForUniqueWork(ProactiveWorkNames.RUN_NOW).get().isNotEmpty())
    }

    private fun activeWork(name: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(name).get()
            .filter { it.state != WorkInfo.State.CANCELLED && it.state != WorkInfo.State.FAILED && it.state != WorkInfo.State.SUCCEEDED }

    private fun runProactiveWorker(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder.from(context, ProactiveWorker::class.java)
            .setWorkerFactory(testWorkerFactory)
            .build()
            .doWork()
    }

    private fun eventFile(): File = File(File(context.filesDir, "jarvys"), "proactive/events.jsonl")
    private fun captureBoundaryFile(): File = File(File(File(context.filesDir, "jarvys"), "proactive"), "notification-capture-boundary")

    private fun event(sender: String = "Kai", body: String, receivedAt: Long): ProactiveEvent =
        ProactiveNormalizer.notification(input(body, receivedAt = receivedAt, sender = sender))

    private fun input(
        body: String,
        key: String = body,
        receivedAt: Long = 1_000,
        sender: String? = "Kai",
        ongoing: Boolean = false,
        androidCategory: String = "msg",
    ) = NotificationInput(
        receivedAtMillis = receivedAt,
        observedAtMillis = receivedAt + 1,
        appPackage = "com.example.chat",
        appLabel = "Chat",
        title = "Message",
        body = body,
        androidCategory = androidCategory,
        messagingSender = sender,
        ongoing = ongoing,
        notificationKey = key,
    )

    private val reviewedBatches = mutableListOf<List<ProactiveEventBatch>>()
}
