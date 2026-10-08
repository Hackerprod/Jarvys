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

    @Test fun stopBeforeDispatchPreservesInertTextAndClosesQueuedGeneration() {
        withHeldWorker { service, context, session, release ->
            var runs = 0
            service.replacementRunner = AgentForegroundService.ReplacementRunner { _,_,_,_,_,_ -> runs++ }
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "new request", arrayListOf(), false))
            assertTrue(AgentRunUiState.state.value.running)
            assertEquals(1, LocalRunStore(context).readConversationMessages(session).size)
            StopController.getInstance().stopRun()
            assertFalse(AgentRunUiState.state.value.running)
            release.countDown()
            worker(service).submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(0, runs)
            val restored = LocalRunStore(context).readConversationTimeline(session)
            assertTrue(restored.any { it.stage == "QUEUED" && it.text == "new request" })
        }
    }

    @Test fun replacementWaitsForOldWorkerThenDispatchesAndRebinds() {
        withHeldWorker { service, context, session, release ->
            val invoked = CountDownLatch(1)
            val correct = AtomicBoolean(false)
            service.replacementRunner = AgentForegroundService.ReplacementRunner { token, chat, id, text, skills, memory ->
                correct.set(release.count == 0L && chat == session && text == "follow up" && skills == arrayListOf("safe") && memory
                    && LocalRunStore(context).latestUserMessageId(session) == id
                    && AgentRunUiState.state.value.events.none { it.stage == "QUEUED" })
                AgentRunUiState.beginRun(chat, text)
                AgentRunUiState.bindGeneration(chat, token.generation())
                AgentRunUiState.completeGeneration(chat, token.generation(), "r", "COMPLETED", "done", "m", 0)
                invoked.countDown()
            }
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "follow up", arrayListOf("safe"), true))
            assertEquals(1L, invoked.count)
            release.countDown()
            assertTrue(invoked.await(5, TimeUnit.SECONDS))
            worker(service).submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(correct.get())
            assertEquals("COMPLETED", AgentRunUiState.state.value.outcome)
        }
    }

    @Test fun rapidReplacementsNeverRunSupersededPendingInput() {
        withHeldWorker { service, context, session, release ->
            val observed = java.util.concurrent.CopyOnWriteArrayList<String>()
            service.replacementRunner = AgentForegroundService.ReplacementRunner { _,_,_,text,_,_ -> observed.add(text) }
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "B", arrayListOf(), false))
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "C", arrayListOf(), false))
            release.countDown()
            worker(service).submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("C"), observed)
            val timeline = LocalRunStore(context).readConversationTimeline(session)
            assertTrue(timeline.any { it.stage == "QUEUED" && it.text == "B" })
            assertFalse(timeline.any { it.stage == "QUEUED" && it.text == "C" })
        }
    }

    @Test fun cancelledApprovalCannotLaunchAfterReplacement() {
        withHeldWorker { service, context, session, release ->
            val cancelled = AtomicInteger()
            val controller = StopController.getInstance()
            val oldGeneration = controller.javaClass.getDeclaredField("generation").apply { isAccessible = true }
                .get(controller) as java.util.concurrent.atomic.AtomicLong
            val old = CancellationToken(controller, oldGeneration.get())
            old.registerCancelAction { cancelled.incrementAndGet() }
            service.replacementRunner = AgentForegroundService.ReplacementRunner { _,_,_,_,_,_ -> }
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "instead inspect", arrayListOf(), false))
            assertEquals(1, cancelled.get())
            assertFalse(old.runIfActive { fail("stale approved effect") })
            StopController.getInstance().stopRun()
            release.countDown()
        }
    }

    @Test fun rejectedExecutorKeepsInputAndUnlocksComposer() {
        withHeldWorker { service, context, session, release ->
            worker(service).shutdown()
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "preserved", arrayListOf(), false))
            assertFalse(AgentRunUiState.state.value.running)
            assertTrue(LocalRunStore(context).readConversationTimeline(session).any { it.stage == "QUEUED" && it.text == "preserved" })
            release.countDown()
        }
    }

    @Test fun deletedConversationCannotDispatchOrLeaveRunStuck() {
        withHeldWorker { service, context, session, release ->
            val invoked = AtomicInteger()
            service.replacementRunner = AgentForegroundService.ReplacementRunner { _,_,_,_,_,_ -> invoked.incrementAndGet() }
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "never run", arrayListOf(), false))
            LocalRunStore(context).deleteConversation(session)
            release.countDown()
            worker(service).submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(0, invoked.get())
            assertFalse(AgentRunUiState.state.value.running)
        }
    }

    @Test fun synchronousCancellationIdleCheckCannotDestroyReplacementService() {
        withHeldWorker { service, context, session, release ->
            val controller = StopController.getInstance()
            val field = controller.javaClass.getDeclaredField("generation").apply { isAccessible = true }
            val old = CancellationToken(controller, (field.get(controller) as java.util.concurrent.atomic.AtomicLong).get())
            val idle = AgentForegroundService::class.java.getDeclaredMethod("stopServiceIfIdle").apply { isAccessible = true }
            old.registerCancelAction { idle.invoke(service) }
            service.replacementRunner = AgentForegroundService.ReplacementRunner { _,_,_,_,_,_ -> }
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

    @Test fun actualApprovalGateIsCancelledByReplacement() = approvalReplacement(false)
    @Test fun approvalCannotApproveInPresenterRegistrationCancellationGap() = approvalReplacement(true)

    private fun approvalReplacement(gap: Boolean) {
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
                var effects = 0
                assertFalse(gate.resolveFromUi(id.get()) { effects++; com.jarvys.agent.connectors.ApprovalDecision.APPROVED })
                assertEquals(0, effects)
                finishShow.countDown(); request.get(5, TimeUnit.SECONDS)
                assertFalse(gate.isPending(id.get()))
                StopController.getInstance().stopRun(); release.countDown()
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
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val future = worker(service).submit {
            started.countDown()
            while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
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
