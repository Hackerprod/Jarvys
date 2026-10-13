package com.jarvys.agent

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainAgentInteractionTest {
    @After fun cleanup() { StopController.getInstance().stopRun() }

    @Test fun oldGenerationCannotCompleteOrMutateNewOne() {
        AgentRunUiState.beginRun("identity", "first")
        AgentRunUiState.bindGeneration("identity", 10)
        AgentRunUiState.bindGeneration("identity", 11)
        AgentRunUiState.withGeneration("identity", 10) { AgentRunUiState.compactionStarted("identity", "stale") }
        AgentRunUiState.completeGeneration("identity", 10, "old", "COMPLETED", "stale", "", 0)
        assertTrue(AgentRunUiState.state.value.running)
        assertFalse(AgentRunUiState.state.value.compacting)
        assertFalse(AgentRunUiState.state.value.events.any { it.text == "stale" })
        AgentRunUiState.completeGeneration("identity", 11, "new", "COMPLETED", "fresh", "", 0)
        assertEquals("fresh", AgentRunUiState.state.value.events.last().text)
        AgentRunUiState.stopPendingGeneration("identity", 11)
        assertEquals("COMPLETED", AgentRunUiState.state.value.outcome)
    }

    @Test fun recreationPreservesLiveOwnershipAndCompletion() {
        AgentRunUiState.beginRun("rotation", "first")
        AgentRunUiState.bindGeneration("rotation", 20)
        val events = AgentRunUiState.state.value.events
        AgentRunUiState.restoreSession("rotation", events)
        assertTrue(AgentRunUiState.state.value.running)
        AgentRunUiState.completeGeneration("rotation", 20, "r", "COMPLETED", "finished", "m", 5)
        assertFalse(AgentRunUiState.state.value.running)
        assertEquals("finished", AgentRunUiState.state.value.events.last().text)
    }

    @Test fun navigationAndReturnKeepRunWithoutCrossChatCallbacks() {
        AgentRunUiState.beginRun("origin", "first")
        AgentRunUiState.bindGeneration("origin", 30)
        val events = AgentRunUiState.state.value.events
        AgentRunUiState.resetSession("other")
        AgentRunUiState.withGeneration("origin", 30) { AgentRunUiState.compactionStarted("origin", "must not show") }
        assertEquals("other", AgentRunUiState.state.value.sessionId)
        assertFalse(AgentRunUiState.state.value.compacting)
        AgentRunUiState.restoreSession("origin", events)
        assertTrue(AgentRunUiState.state.value.running)
        AgentRunUiState.completeGeneration("origin", 30, "r", "COMPLETED", "finished", "m", 0)
    }

    @Test fun hiddenChatCompletionDoesNotStopVisibleChatOrResurrectOnReturn() {
        AgentRunUiState.beginRun("hidden", "first")
        AgentRunUiState.bindGeneration("hidden", 31)
        val events = AgentRunUiState.state.value.events
        AgentRunUiState.resetSession("visible")
        AgentRunUiState.completeGeneration("hidden", 31, "r", "COMPLETED", "private", "m", 0)
        assertEquals("visible", AgentRunUiState.state.value.sessionId)
        assertFalse(AgentRunUiState.state.value.events.any { it.text == "private" })
        AgentRunUiState.restoreSession("hidden", events)
        assertFalse(AgentRunUiState.state.value.running)
    }

    @Test fun commentaryIsExactlyOnceAndDoesNotFinishRun() {
        AgentRunUiState.beginRun("progress-ui", "first")
        val event = AgentRunUiEvent.messageEvent(0, "assistant", "Evidence found", 1).copyMetadata("progress-call", 0).copyStage("PROGRESS")
        AgentRunUiState.assistantProgress("progress-ui", event)
        AgentRunUiState.assistantProgress("progress-ui", event)
        AgentRunUiState.assistantProgress("other", event.copyMetadata("other", 0))
        assertTrue(AgentRunUiState.state.value.running)
        assertEquals(1, AgentRunUiState.state.value.events.count { it.stage == "PROGRESS" })
        AgentRunUiState.refreshPersistedSession("progress-ui", AgentRunUiState.state.value.events)
        assertEquals(1, AgentRunUiState.state.value.events.count { it.stage == "PROGRESS" })
    }

    @Test fun pendingPromotionUsesExactDurableIdentityBeforeBeginRun() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "promote-${System.nanoTime()}"
        val store = LocalRunStore(context)
        store.appendConversationMessage(session, "user", "old")
        AgentRunUiState.beginRun(session, "old")
        val id = store.appendInterruptRequest(session, "same text", emptyList(), false)
        AgentRunUiState.refreshPersistedSession(session, store.readConversationTimeline(session))
        assertTrue(AgentRunUiState.state.value.events.any { it.stage == "QUEUED" })
        store.dispatchInterruptRequest(session, id)
        AgentRunUiState.refreshPersistedSession(session, store.readConversationTimeline(session))
        AgentRunUiState.beginRun(session, "same text")
        AgentRunUiState.bindCurrentUserMessage(session, id)
        assertEquals(1, AgentRunUiState.state.value.events.count { it.messageId == id })
        assertFalse(AgentRunUiState.state.value.events.any { it.stage == "QUEUED" })
    }

    @Test fun steeringDuringAnActiveRunPersistsWithoutStoppingThatRun() {
        withHeldWorker { service, context, session, release ->
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "new request", arrayListOf(), false))
            assertTrue(AgentRunUiState.state.value.running)
            assertFalse(StopController.getInstance().isStopped)
            assertTrue(LocalRunStore(context).readConversationMessages(session).any {
                it.optString("role") == "user" && it.optString("content") == "new request"
            })
            val generationField = StopController.getInstance().javaClass.getDeclaredField("generation")
                .apply { isAccessible = true }
            val retainedGeneration = (generationField.get(StopController.getInstance()) as java.util.concurrent.atomic.AtomicLong).get()
            StopController.getInstance().stopRun()
            assertTrue(AgentRunUiState.state.value.running)
            release.countDown()
            worker(service).submit {}.get(5, TimeUnit.SECONDS)
            // withHeldWorker is synthetic; model the service's terminal UI callback after its worker barrier.
            AgentRunUiState.completeGeneration(session, retainedGeneration, "stopped-$session",
                "STOPPED", "Run stopped", "", 0)
            assertFalse(AgentRunUiState.state.value.running)
            assertFalse(LocalRunStore(context).readConversationTimeline(session).any { it.stage == "QUEUED" })
        }
    }

    @Test fun steeringDoesNotLaunchAReplacementOrChangeTheActiveGeneration() {
        withHeldWorker { service, context, session, release ->
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "follow up", arrayListOf("safe"), true))
            assertTrue(AgentRunUiState.state.value.running)
            assertFalse(StopController.getInstance().isStopped)
            assertEquals("follow up", LocalRunStore(context).readConversationMessages(session).last().optString("content"))
            release.countDown()
            worker(service).submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(AgentRunUiState.state.value.running)
        }
    }

    @Test fun steeringMessagesRemainDurableInArrivalOrder() {
        withHeldWorker { service, context, session, release ->
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "B", arrayListOf(), false))
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "C", arrayListOf(), false))
            val rows = LocalRunStore(context).readConversationMessages(session)
                .filter { it.optString("role") == "user" }.map { it.optString("content") }
            assertEquals(listOf("original", "B", "C"), rows)
            release.countDown()
            worker(service).submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(AgentRunUiState.state.value.running)
        }
    }

    @Test fun steeringPreservesAnActiveApprovalUntilTheUserStopsTheRun() {
        withHeldWorker { service, context, session, release ->
            val cancelled = AtomicInteger()
            val controller = StopController.getInstance()
            val oldGeneration = controller.javaClass.getDeclaredField("generation").apply { isAccessible = true }
                .get(controller) as java.util.concurrent.atomic.AtomicLong
            val old = CancellationToken(controller, oldGeneration.get())
            old.registerCancelAction { cancelled.incrementAndGet() }
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "instead inspect", arrayListOf(), false))
            assertEquals(0, cancelled.get())
            assertTrue(old.runIfActive { })
            StopController.getInstance().stopRun()
            assertEquals(1, cancelled.get())
            assertFalse(old.runIfActive { fail("stopped run must reject approval") })
            release.countDown()
        }
    }

    @Test fun steeringRemainsDurableWhenTheServiceExecutorIsShuttingDown() {
        withHeldWorker { service, context, session, release ->
            worker(service).shutdown()
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "preserved", arrayListOf(), false))
            assertTrue(AgentRunUiState.state.value.running)
            assertTrue(LocalRunStore(context).readConversationMessages(session).any {
                it.optString("role") == "user" && it.optString("content") == "preserved"
            })
            release.countDown()
            StopController.getInstance().stopRun()
        }
    }

    @Test fun synchronousCancellationIdleCheckCannotDestroyReplacementService() {
        withHeldWorker { service, context, session, release ->
            val controller = StopController.getInstance()
            val field = controller.javaClass.getDeclaredField("generation").apply { isAccessible = true }
            val old = CancellationToken(controller, (field.get(controller) as java.util.concurrent.atomic.AtomicLong).get())
            val idle = AgentForegroundService::class.java.getDeclaredMethod("stopServiceIfIdle").apply { isAccessible = true }
            old.registerCancelAction { idle.invoke(service) }
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "reserved", arrayListOf(), false))
            assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf)
            StopController.getInstance().stopRun()
            release.countDown()
        }
    }

    @Test fun startingHiddenReplacementDoesNotSwitchVisibleConversation() {
        AgentRunUiState.resetSession("selected-chat")
        AgentRunUiState.beginServiceRun("background-chat", "new goal", emptyList(), false, 50)
        assertEquals("selected-chat", AgentRunUiState.state.value.sessionId)
        assertFalse(AgentRunUiState.state.value.running)
        AgentRunUiState.completeGeneration("background-chat", 50, "r", "COMPLETED", "hidden result", "m", 0)
        assertTrue(AgentRunUiState.state.value.events.isEmpty())
    }

    @Test fun activeOtherChatRejectsOrdinarySendAndCannotBeInterruptedFromOptimisticUi() {
        withHeldWorker { service, context, session, release ->
            val other = "$session-other"
            AgentRunUiState.resetSession(other)
            val store = LocalRunStore(context)
            val messageId = store.appendConversationMessage(other, "user", "different chat")
            AgentRunUiState.beginRun(other, "different chat")
            service.onStartCommand(AgentForegroundService.storedChatMessageIntent(context, other, messageId), 0, 2)
            assertFalse(AgentRunUiState.state.value.running)
            assertEquals(session, AgentRunUiState.state.value.interactiveOwnerSessionId)
            assertFalse(AgentForegroundService.interruptAndSend(context, other, "must not cancel A", arrayListOf(), false))
            assertFalse(StopController.getInstance().isStopped)
            assertTrue(store.readConversationMessages(other).any { it.optString("status") == "FAILED" })
            release.countDown()
        }
    }

    @Test fun approvalCardAndWaitSurviveSameChatHydration() {
        AgentRunUiState.beginRun("approval-restore", "request")
        AgentRunUiState.bindGeneration("approval-restore", 80)
        val durable = AgentRunUiState.state.value.events
        AgentRunUiState.showApproval("pending-80", "Approve action", listOf("read first"), null)
        AgentRunUiState.restoreSession("approval-restore", durable)
        assertEquals(1, AgentRunUiState.state.value.events.count { it.approvalId == "pending-80" })
        assertTrue(AgentRunUiState.state.value.running)
        AgentRunUiState.completeGeneration("approval-restore", 80, "r", "STOPPED", "stopped", "m", 0)
    }

    @Test fun approvalArrivingWhileOtherChatVisibleReturnsToItsOwner() {
        AgentRunUiState.beginRun("approval-owner", "request")
        AgentRunUiState.bindGeneration("approval-owner", 81)
        val durable = AgentRunUiState.state.value.events
        AgentRunUiState.resetSession("unrelated")
        AgentRunUiState.showApproval("pending-81", "Private action", listOf("details"), null)
        assertFalse(AgentRunUiState.state.value.events.any { it.approvalId == "pending-81" })
        AgentRunUiState.restoreSession("approval-owner", durable)
        assertEquals(1, AgentRunUiState.state.value.events.count { it.approvalId == "pending-81" })
        AgentRunUiState.resetSession("unrelated")
        AgentRunUiState.updateApproval("pending-81", "CANCELLED")
        AgentRunUiState.restoreSession("approval-owner", durable)
        assertEquals("CANCELLED", AgentRunUiState.state.value.events.single { it.approvalId == "pending-81" }.approvalStatus)
        AgentRunUiState.completeGeneration("approval-owner", 81, "r", "STOPPED", "stopped", "m", 0)
    }

    @Test fun steeringKeepsAnActiveApprovalResolvable() = approvalSteering(gap = false, stopAfterSteering = false)
    @Test fun explicitStopBlocksApprovalDuringPresenterRegistrationGap() = approvalSteering(gap = true, stopAfterSteering = true)

    private fun approvalSteering(gap: Boolean, stopAfterSteering: Boolean) {
        withHeldWorker { service, context, session, release ->
            val controller = StopController.getInstance()
            val field = controller.javaClass.getDeclaredField("generation").apply { isAccessible = true }
            val old = CancellationToken(controller, (field.get(controller) as java.util.concurrent.atomic.AtomicLong).get())
            val shown = CountDownLatch(1); val finishShow = CountDownLatch(if (gap) 1 else 0)
            val id = java.util.concurrent.atomic.AtomicReference<String>()
            val gate = com.jarvys.agent.connectors.ApprovalGate(5000, object : com.jarvys.agent.connectors.ApprovalPresenter {
                override fun show(value: String, summary: com.jarvys.agent.connectors.ApprovalSummary) {
                    id.set(value); AgentRunUiState.showApproval(value, summary.title, summary.lines, null)
                    shown.countDown(); finishShow.await(5, TimeUnit.SECONDS)
                }
                override fun update(value: String, decision: com.jarvys.agent.connectors.ApprovalDecision) { AgentRunUiState.updateApproval(value, decision.name) }
            })
            val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
            val request = pool.submit { runCatching { gate.request(com.jarvys.agent.connectors.ApprovalSummary("write", listOf("target")), old) } }
            try {
                assertTrue(shown.await(5, TimeUnit.SECONDS))
                assertTrue(AgentForegroundService.interruptAndSend(context, session, "inspect instead", arrayListOf(), false))
                if (stopAfterSteering) StopController.getInstance().stopRun()
                var effects = 0
                val resolved = gate.resolveFromUi(id.get()) { effects++; com.jarvys.agent.connectors.ApprovalDecision.APPROVED }
                assertEquals(!stopAfterSteering, resolved)
                assertEquals(if (stopAfterSteering) 0 else 1, effects)
                finishShow.countDown(); request.get(5, TimeUnit.SECONDS)
                assertFalse(gate.isPending(id.get()))
                if (!stopAfterSteering) StopController.getInstance().stopRun()
                release.countDown()
            } finally { finishShow.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun hiddenStartRetainsApprovalUntilOwnerChatIsOpened() {
        AgentRunUiState.resetSession("visible-before-start")
        AgentRunUiState.beginServiceRun("hidden-start-owner", "inspect then write", emptyList(), false, 82)
        AgentRunUiState.showApproval("hidden-start-approval", "Write file", listOf("target"), null)
        assertEquals("visible-before-start", AgentRunUiState.state.value.sessionId)
        assertTrue(AgentRunUiState.state.value.events.isEmpty())
        val user = AgentRunUiEvent.messageEvent(1, "user", "inspect then write", 1).copyMetadata("actual-user-id", 0)
        AgentRunUiState.restoreSession("hidden-start-owner", listOf(user))
        assertEquals(1, AgentRunUiState.state.value.events.count { it.approvalId == "hidden-start-approval" })
        assertEquals(1, AgentRunUiState.state.value.events.count { it.messageId == "actual-user-id" })
        assertTrue(AgentRunUiState.state.value.running)
        AgentRunUiState.completeGeneration("hidden-start-owner", 82, "r", "STOPPED", "stopped", "m", 0)
    }

    @Test fun olderInteractiveCompletionCannotReleaseNewGenerationOrLegacyScope() {
        val background = com.jarvys.agent.proactive.BackgroundRunController
        background.interactiveStarted(9001); background.interactiveStarted(9002)
        try {
            background.interactiveFinished(9001)
            assertTrue(background.isInteractiveActive())
            background.interactiveFinished()
            assertTrue(background.isInteractiveActive())
            assertNull(background.tryStart(com.jarvys.agent.proactive.BackgroundRunKind.PROACTIVE))
        } finally { background.interactiveFinished(9001); background.interactiveFinished(9002) }
        assertFalse(background.isInteractiveActive())
    }

    @Test fun cancellingQueuedReplacementDoesNotReleaseUnwindingOlderWorker() {
        val background = com.jarvys.agent.proactive.BackgroundRunController
        background.interactiveStarted(9011); background.interactiveStarted(9012)
        try {
            background.interactiveFinished(9012)
            assertTrue(background.isInteractiveActive())
            assertNull(background.tryStart(com.jarvys.agent.proactive.BackgroundRunKind.TASK))
        } finally { background.interactiveFinished(9011); background.interactiveFinished(9012) }
        assertFalse(background.isInteractiveActive())
    }

    @Test fun ordinarySubmissionCancelledBeforeStartReleasesItsReservation() = ordinaryQueuedExit(false)
    @Test fun ordinaryRejectedSubmissionReleasesItsReservationAndRecordsFailure() = ordinaryQueuedExit(true)

    private fun ordinaryQueuedExit(reject: Boolean) {
        StopController.getInstance().stopRun()
        val serviceController = Robolectric.buildService(AgentForegroundService::class.java).create()
        val service = serviceController.get()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "ordinary-queue-${System.nanoTime()}"
        val store = LocalRunStore(context)
        val id = store.appendConversationMessage(session, "user", "must remain recoverable")
        AgentRunUiState.beginRun(session, "must remain recoverable")
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        if (reject) worker(service).shutdown() else {
            worker(service).submit { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
        }
        try {
            service.onStartCommand(AgentForegroundService.storedChatMessageIntent(context, session, id), 0, 1)
            if (!reject) {
                assertTrue(com.jarvys.agent.proactive.BackgroundRunController.isInteractiveActive())
                StopController.getInstance().stopRun()
            }
            assertFalse(com.jarvys.agent.proactive.BackgroundRunController.isInteractiveActive())
            assertFalse(AgentRunUiState.state.value.running)
            assertTrue(StopController.getInstance().isStopped)
            assertEquals("must remain recoverable", store.readConversationMessage(session, id))
            if (reject) assertTrue(store.readConversationMessages(session).any { it.optString("status") == "FAILED" })
        } finally {
            StopController.getInstance().stopRun(); release.countDown()
            if (!worker(service).isShutdown) worker(service).submit {}.get(5, TimeUnit.SECONDS)
            serviceController.destroy()
        }
    }

    @Test fun earlyRuntimeSetupFailureCannotLeakInteractiveOwnership() {
        StopController.getInstance().stopRun()
        val serviceController = Robolectric.buildService(AgentForegroundService::class.java).create()
        val service = serviceController.get()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "early-setup-${System.nanoTime()}"
        val store = LocalRunStore(context)
        val id = store.appendConversationMessage(session, "user", "read only")
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { _, _, _, _ ->
            throw IllegalStateException("synthetic runtime initialization failure")
        }
        AgentRunUiState.beginRun(session, "read only")
        try {
            service.onStartCommand(AgentForegroundService.storedChatMessageIntent(context, session, id), 0, 1)
            worker(service).submit {}.get(5, TimeUnit.SECONDS)
            assertFalse(com.jarvys.agent.proactive.BackgroundRunController.isInteractiveActive())
            assertTrue(StopController.getInstance().isStopped)
            assertFalse(AgentRunUiState.state.value.running)
            assertEquals("FAILED", AgentRunUiState.state.value.outcome)
            assertTrue(store.readConversationMessages(session).any { it.optString("status") == "FAILED" })
            assertTrue(store.readModelTranscriptRows(session).isEmpty())
        } finally {
            StopController.getInstance().stopRun(); serviceController.destroy()
        }
    }

    @Test @Config(sdk = [32], qualifiers = "en")
    fun selectedAppLocaleSurvivesApplicationContextNormalization() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = AppLanguageRuntime.current(context)
        val defaultLocale = java.util.Locale.getDefault()
        try {
            AppLanguageRuntime.select(context, AppLanguageChoice.SPANISH)
            assertEquals("es", CoreAgentRuntime.responseLocale(context.applicationContext).language)
            assertTrue(MainAgentPrompt.core(CoreAgentRuntime.responseLocale(context.applicationContext), true)
                .contains("Runtime response locale: es"))
            AppLanguageRuntime.select(context, AppLanguageChoice.ENGLISH)
            assertEquals("en", CoreAgentRuntime.responseLocale(context.applicationContext).language)
        } finally {
            AppLanguageRuntime.select(context, original)
            java.util.Locale.setDefault(defaultLocale)
        }
    }

    private fun worker(service: AgentForegroundService) = AgentForegroundService::class.java.getDeclaredField("worker")
        .apply { isAccessible = true }.get(service) as ExecutorService

    private fun withHeldWorker(block: (AgentForegroundService, Context, String, CountDownLatch) -> Unit) {
        StopController.getInstance().stopRun()
        val controller = Robolectric.buildService(AgentForegroundService::class.java).create()
        val service = controller.get()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "replacement-${System.nanoTime()}"
        LocalRunStore(context).appendConversationMessage(session, "user", "original")
        AgentRunUiState.beginRun(session, "original")
        val old = StopController.getInstance().beginRun()!!
        AgentRunUiState.bindGeneration(session, old.generation())
        com.jarvys.agent.proactive.BackgroundRunController.interactiveStarted(old.generation())
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val future = worker(service).submit {
            started.countDown()
            try { while (release.count > 0) try { release.await() } catch (_: InterruptedException) { } }
            finally { com.jarvys.agent.proactive.BackgroundRunController.interactiveFinished(old.generation()) }
        }
        StopController.getInstance().attachFuture(old, future)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        try { block(service, context, session, release) }
        finally {
            StopController.getInstance().stopRun(); release.countDown()
            if (!worker(service).isShutdown) worker(service).submit {}.get(5, TimeUnit.SECONDS)
            else worker(service).awaitTermination(5, TimeUnit.SECONDS)
            controller.destroy()
        }
    }
}
