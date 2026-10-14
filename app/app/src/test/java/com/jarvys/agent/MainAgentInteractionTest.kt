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
import java.util.concurrent.atomic.AtomicReference

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

    @Test fun concurrentSteeringPersistsAndDispatchesInArrivalOrder() {
        withHeldWorker { service, context, session, release ->
            val provider = AgentForegroundService::class.java.getDeclaredField("activeSteeringProvider")
                .apply { isAccessible = true }.get(service) as MainChatTurnContextProvider
            val firstStarted = CountDownLatch(1)
            val secondStarted = CountDownLatch(1)
            val firstResult = AtomicReference<Boolean>()
            val secondResult = AtomicReference<Boolean>()
            val first = Thread {
                firstStarted.countDown()
                firstResult.set(AgentForegroundService.interruptAndSend(context, session, "first", arrayListOf(), false))
            }
            val second = Thread {
                secondStarted.countDown()
                secondResult.set(AgentForegroundService.interruptAndSend(context, session, "second", arrayListOf(), false))
            }
            synchronized(provider) {
                first.start()
                assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
                val store = LocalRunStore(context)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (store.readPendingInterruptRequests(session).size != 1 && System.nanoTime() < deadline)
                    Thread.yield()
                assertEquals(1, store.readPendingInterruptRequests(session).size)
                second.start()
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            }
            first.join(5000)
            second.join(5000)
            assertFalse(first.isAlive)
            assertFalse(second.isAlive)
            assertEquals(true, firstResult.get())
            assertEquals(true, secondResult.get())
            val users = LocalRunStore(context).readConversationMessages(session)
                .filter { it.optString("role") == "user" }.map { it.optString("content") }
            assertEquals(listOf("original", "first", "second"), users)
        }
    }

    @Test fun finalDrainConsumesDispatchedSteeringWithoutStoppingTheRun() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "final-drain-${System.nanoTime()}"
        val store = LocalRunStore(context)
        val initialId = store.appendConversationMessage(session, "user", "initial")
        val provider = MainChatTurnContextProvider(store, session, initialId)
        val modelEntered = CountDownLatch(1)
        val releaseModel = CountDownLatch(1)
        val calls = AtomicInteger()
        val secondTranscript = AtomicReference<List<ConversationTurn>>()
        val model = object : CoreAgentLoop.Model {
            override fun complete(transcript: List<ConversationTurn>, prompt: String,
                declarations: List<ToolSpec>, token: CancellationToken): ModelReply {
                if (calls.incrementAndGet() == 1) {
                    modelEntered.countDown()
                    assertTrue(releaseModel.await(5, TimeUnit.SECONDS))
                    return ModelReply("First answer", emptyList())
                }
                secondTranscript.set(ArrayList(transcript))
                return ModelReply("Follow-up answered", emptyList())
            }
        }
        val loop = CoreAgentLoop(model, CoreToolRegistry(emptyList()), "", session,
            CorePromptBudget.standard(), null, CoreAgentLoop.ToolResultBudget.BOUNDED, provider, null)
        val token = CancellationToken.cancellable()
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val run = pool.submit<CoreAgentLoop.Result> { loop.run("initial", emptyList(), token, null) }
        try {
            assertTrue(modelEntered.await(5, TimeUnit.SECONDS))
            val id = store.appendInterruptRequest(session, "please inspect this", emptyList(), false)
            assertTrue(provider.dispatchPendingRequest(id))
            releaseModel.countDown()
            assertEquals("Follow-up answered", run.get(10, TimeUnit.SECONDS).text)
            assertEquals(2, calls.get())
            assertTrue(secondTranscript.get().any { it.role == "user" && it.content == "please inspect this" })
            assertFalse(token.isCancellationRequested)
        } finally {
            releaseModel.countDown()
            provider.closeForRunExit()
            pool.shutdownNow()
        }
    }

    @Test fun dispatchedSteeringSurvivesProviderFailureBeforeNextCheckpointAndStartsFollowUp() {
        StopController.getInstance().stopRun()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "steering-provider-failure-${System.nanoTime()}"
        val store = LocalRunStore(context)
        val initialId = store.appendConversationMessage(session, "user", "initial")
        val service = startManagedService(context)
        val calls = AtomicInteger()
        val followUp = CountDownLatch(1)
        val observedGoals = java.util.Collections.synchronizedList(mutableListOf<String>())
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { runtimeContext, id, skills, memoryDisabled ->
            CoreAgentRuntime(runtimeContext, id, skills, CorePromptBudget.standard(), memoryDisabled)
        }
        service.mainChatRunner = AgentForegroundService.MainChatRunner { runtime, goal, history, attachments, token, progress ->
            observedGoals.add(goal)
            if (calls.incrementAndGet() == 1) {
                val provider = runtime.mainChatSteeringProvider()!!
                val loop = CoreAgentLoop(object : CoreAgentLoop.Model {
                    override fun complete(transcript: List<ConversationTurn>, prompt: String,
                        declarations: List<ToolSpec>, current: CancellationToken): ModelReply {
                        val requestId = store.appendInterruptRequest(session, "retry the accepted input", emptyList(), false)
                        assertTrue(provider.dispatchPendingRequest(requestId))
                        throw ProviderHttpException("temporary provider outage", 503, 0L, null)
                    }
                }, CoreToolRegistry(emptyList()), "", session, CorePromptBudget.standard(), null,
                    CoreAgentLoop.ToolResultBudget.BOUNDED, provider, null)
                loop.run(goal, history, attachments, token, progress)
            } else {
                followUp.countDown()
                CoreAgentLoop.Result("follow-up", "follow-up retained", 1, "PARTIAL")
            }
        }
        try {
            AgentRunUiState.resetSession(session)
            startServiceIntent(context, AgentForegroundService.storedChatMessageIntent(context, session, initialId))
            assertTrue("A dispatched message survives a pre-checkpoint provider error", awaitServiceCallback(followUp, 10))
            worker(service).submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(listOf("initial", "retry the accepted input"), observedGoals)
            val messages = store.readConversationMessages(session)
            assertTrue(messages.any { it.optString("role") == "assistant" && it.optString("status") == "PARTIAL" })
            assertTrue(messages.any { it.optString("role") == "user" && it.optString("content") == "retry the accepted input" })
        } finally {
            StopController.getInstance().stopRun()
            stopManagedService(context)
        }
    }

    @Test fun newerPendingInputSupersedesFollowUpForAlreadyConsumedSteering() {
        StopController.getInstance().stopRun()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "steering-newer-pending-${System.nanoTime()}"
        val store = LocalRunStore(context)
        val initialId = store.appendConversationMessage(session, "user", "initial request")
        val service = startManagedService(context)
        val calls = AtomicInteger()
        val followUp = CountDownLatch(1)
        val observedGoals = java.util.Collections.synchronizedList(mutableListOf<String>())
        val followUpHistory = AtomicReference<List<ConversationTurn>>(emptyList())
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { runtimeContext, id, skills, memoryDisabled ->
            CoreAgentRuntime(runtimeContext, id, skills, CorePromptBudget.standard(), memoryDisabled)
        }
        service.mainChatRunner = AgentForegroundService.MainChatRunner { runtime, goal, history, _, _, _ ->
            observedGoals.add(goal)
            if (calls.incrementAndGet() == 1) {
                val provider = runtime.mainChatSteeringProvider()!!
                val olderRequestId = store.appendInterruptRequest(
                    session, "older accepted steering", emptyList(), false)
                assertTrue(provider.dispatchPendingRequest(olderRequestId))
                assertEquals(1, provider.takeDurableMessages().size)
                provider.closeForRunExit()

                assertTrue(AgentForegroundService.interruptAndSend(
                    context, session, "newer fallback input", arrayListOf(), false))
                CoreAgentLoop.Result("initial-partial", "initial work paused", 1, "PARTIAL")
            } else {
                followUpHistory.set(history.toList())
                followUp.countDown()
                CoreAgentLoop.Result("follow-up", "newer input handled", 1, "PARTIAL")
            }
        }
        try {
            AgentRunUiState.resetSession(session)
            startServiceIntent(context, AgentForegroundService.storedChatMessageIntent(context, session, initialId))
            assertTrue("Recovery starts the newest pending input", awaitServiceCallback(followUp, 10))
            worker(service).submit {}.get(10, TimeUnit.SECONDS)

            assertEquals(listOf("initial request", "newer fallback input"), observedGoals)
            assertTrue(followUpHistory.get().any {
                it.role == "user" && it.content == "older accepted steering"
            })
            assertTrue(followUpHistory.get().any {
                it.role == "user" && it.content == "newer fallback input"
            })
            assertTrue(store.readPendingInterruptRequests(session).isEmpty())
        } finally {
            StopController.getInstance().stopRun()
            stopManagedService(context)
        }
    }

    @Test fun steeringAfterFinalProviderClosureStartsANormalFollowUpRun() = followUpAfterUnavailableProvider(true)

    @Test fun steeringWithoutAnActiveProviderStartsANormalFollowUpRun() = followUpAfterUnavailableProvider(false)

    private fun followUpAfterUnavailableProvider(providerWasClosed: Boolean) {
        StopController.getInstance().stopRun()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "steering-fallback-${System.nanoTime()}"
        val store = LocalRunStore(context)
        val initialId = store.appendConversationMessage(session, "user", "initial request")
        val service = startManagedService(context)
        val calls = AtomicInteger()
        val followUp = CountDownLatch(1)
        val observedGoals = java.util.Collections.synchronizedList(mutableListOf<String>())
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { _, _, _, _ ->
            CoreAgentRuntime(emptyList(), emptyList(), emptyList(), emptyList())
        }
        service.mainChatRunner = AgentForegroundService.MainChatRunner { _, goal, _, _, _, _ ->
            observedGoals.add(goal)
            if (calls.incrementAndGet() == 1) {
                if (providerWasClosed) {
                    val provider = MainChatTurnContextProvider(store, session, initialId)
                    service.registerMainChatSteeringProvider(session, provider)
                    assertTrue(provider.takeDurableMessagesAtFinalCheckpoint().isEmpty())
                }
                assertTrue(AgentForegroundService.interruptAndSend(
                    context, session, "follow up after close", arrayListOf(), false))
                service.registerMainChatSteeringProvider(session, null)
                CoreAgentLoop.Result("initial-partial", "initial work paused", 1, "PARTIAL")
            } else {
                followUp.countDown()
                CoreAgentLoop.Result("follow-up", "follow-up started", 1, "PARTIAL")
            }
        }
        try {
            AgentRunUiState.resetSession(session)
            startServiceIntent(context, AgentForegroundService.storedChatMessageIntent(context, session, initialId))
            assertTrue("Fallback input launches a fresh run", awaitServiceCallback(followUp, 10))
            worker(service).submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(listOf("initial request", "follow up after close"), observedGoals)
            assertTrue(store.readConversationMessages(session).any {
                it.optString("role") == "user" && it.optString("content") == "follow up after close"
            })
        } finally {
            StopController.getInstance().stopRun()
            stopManagedService(context)
        }
    }

    @Test fun serviceRestartRecoversPendingInputsAcrossSessionsInSequence() {
        StopController.getInstance().stopRun()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocalRunStore(context)
        val sessionA = "recover-a-${System.nanoTime()}"
        val sessionB = "recover-b-${System.nanoTime()}"
        store.appendConversationMessage(sessionA, "user", "initial A")
        store.appendConversationMessage(sessionB, "user", "initial B")
        val service = startManagedService(context)
        val observed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val bothStarted = CountDownLatch(2)
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { _, _, _, _ ->
            CoreAgentRuntime(emptyList(), emptyList(), emptyList(), emptyList())
        }
        service.mainChatRunner = AgentForegroundService.MainChatRunner { _, goal, _, _, _, _ ->
            observed.add(goal)
            bothStarted.countDown()
            CoreAgentLoop.Result("recovered", "visible recovery", 1, "PARTIAL")
        }
        try {
            store.appendInterruptRequest(sessionA, "pending A", emptyList(), false)
            store.appendInterruptRequest(sessionB, "pending B", emptyList(), false)
            val expected = store.readPendingInterruptRequests().filter {
                it.optString("sessionId") == sessionA || it.optString("sessionId") == sessionB
            }.map { it.optString("content") }
            AgentRunUiState.resetSession(sessionA)
            startServiceIntent(context, android.content.Intent(context, AgentForegroundService::class.java)
                .setAction(AgentForegroundService.ACTION_RECOVER_PENDING_INPUTS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (bothStarted.count > 0 && System.nanoTime() < deadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                Thread.sleep(10)
            }
            assertEquals(0, bothStarted.count)
            assertEquals(expected, observed)
            assertTrue(store.readPendingInterruptRequests().isEmpty())
            assertEquals("PARTIAL", store.readConversationMessages(sessionA).last().optString("status"))
            assertEquals("PARTIAL", store.readConversationMessages(sessionB).last().optString("status"))
        } finally {
            StopController.getInstance().stopRun()
            stopManagedService(context)
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
            val provider = AgentForegroundService::class.java.getDeclaredField("activeSteeringProvider")
                .apply { isAccessible = true }.get(service) as MainChatTurnContextProvider
            provider.closeForRunExit()
            worker(service).shutdown()
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "preserved", arrayListOf(), false))
            assertTrue(AgentRunUiState.state.value.running)
            val messages = LocalRunStore(context).readConversationMessages(session)
            val user = messages.single { it.optString("role") == "user" && it.optString("content") == "preserved" }
            assertTrue("Rejected recovery is visible as a failed follow-up", messages.any {
                it.optString("role") == "assistant" && it.optString("status") == "FAILED"
                    && it.optString("userMessageId") == user.optString("messageId")
            })
            assertTrue(LocalRunStore(context).readPendingInterruptRequests(session).isEmpty())
            release.countDown()
            StopController.getInstance().stopRun()
        }
    }

    @Test fun stoppingAnActiveRunDoesNotLaunchItsQueuedSteeringFollowUp() {
        StopController.getInstance().stopRun()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "stopped-steering-${System.nanoTime()}"
        val store = LocalRunStore(context)
        val initialId = store.appendConversationMessage(session, "user", "original")
        val service = startManagedService(context)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { runtimeContext, id, skills, memoryDisabled ->
            CoreAgentRuntime(runtimeContext, id, skills, CorePromptBudget.standard(), memoryDisabled)
        }
        service.mainChatRunner = AgentForegroundService.MainChatRunner { runtime, _, _, _, _, _ ->
            entered.countDown()
            while (release.count > 0) {
                try { release.await() } catch (_: InterruptedException) { }
            }
            CoreAgentLoop.Result("stopped-owner", "owner stopped", 1, "PARTIAL")
        }
        try {
            AgentRunUiState.resetSession(session)
            startServiceIntent(context, AgentForegroundService.storedChatMessageIntent(context, session, initialId))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val provider = AgentForegroundService::class.java.getDeclaredField("activeSteeringProvider")
                .apply { isAccessible = true }.get(service) as MainChatTurnContextProvider
            provider.closeForRunExit()
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "cancel with parent run", arrayListOf(), false))
            assertTrue(StopController.getInstance().stopRun())
            val generation = StopController.getInstance().javaClass.getDeclaredField("generation")
                .apply { isAccessible = true }.get(StopController.getInstance()) as java.util.concurrent.atomic.AtomicLong
            val afterStop = generation.get()
            release.countDown()
            worker(service).submit {}.get(5, TimeUnit.SECONDS)
            assertEquals("A stopped owner does not start a replacement run", afterStop, generation.get())
            val messages = store.readConversationMessages(session)
            val queued = messages.single { it.optString("role") == "user" && it.optString("content") == "cancel with parent run" }
            assertTrue(messages.any { it.optString("role") == "assistant" && it.optString("status") == "FAILED"
                && it.optString("userMessageId") == queued.optString("messageId") })
            assertTrue(store.readPendingInterruptRequests(session).isEmpty())
            assertFalse(AgentRunUiState.state.value.running)
        } finally {
            release.countDown()
            StopController.getInstance().stopRun()
            if (!worker(service).isShutdown) worker(service).submit {}.get(5, TimeUnit.SECONDS)
            stopManagedService(context)
        }
    }

    @Test fun deletedConversationCannotAcceptSteeringOrLeaveItsRunStuck() {
        withHeldWorker { service, context, session, release ->
            val store = LocalRunStore(context)
            val provider = AgentForegroundService::class.java.getDeclaredField("activeSteeringProvider")
                .apply { isAccessible = true }.get(service) as MainChatTurnContextProvider
            val token = AgentForegroundService::class.java.getDeclaredField("activeRunToken")
                .apply { isAccessible = true }.get(service) as CancellationToken
            provider.closeForRunExit()
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "must not dispatch", arrayListOf(), false))
            assertEquals(1, store.readPendingInterruptRequests(session).size)
            store.deleteConversation(session)
            assertTrue(store.readConversationMetadata(session).deleted)
            assertTrue(store.readPendingInterruptRequests(session).isEmpty())
            assertTrue(StopController.getInstance().completeRun(token))
            val generationAfterOldRun = StopController.getInstance().javaClass.getDeclaredField("generation")
                .apply { isAccessible = true }.get(StopController.getInstance()) as java.util.concurrent.atomic.AtomicLong
            val completedGeneration = generationAfterOldRun.get()
            release.countDown()
            worker(service).submit {}.get(5, TimeUnit.SECONDS)
            assertEquals("Deleted pending input cannot start a follow-up", completedGeneration, generationAfterOldRun.get())
            AgentRunUiState.completeGeneration(session, token.generation(), "deleted-chat", "COMPLETED", "finished", "", 0L)
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

    private var managedController: org.robolectric.android.controller.ServiceController<AgentForegroundService>? = null
    private var managedStartId = 0

    /** Robolectric only records startForegroundService; create the real service once and deliver the recorded starts to it. */
    private fun startManagedService(context: Context): AgentForegroundService {
        val controller = org.robolectric.Robolectric.buildService(AgentForegroundService::class.java).create()
        managedController = controller
        managedStartId = 0
        val service = controller.get()
        worker(service).submit {}.get(5, TimeUnit.SECONDS)
        deliverStartedServices(context)
        return service
    }

    private fun deliverStartedServices(context: Context) {
        val service = managedController?.get() ?: return
        val app = org.robolectric.Shadows.shadowOf(context.applicationContext as android.app.Application)
        var intent = app.nextStartedService
        while (intent != null) {
            service.onStartCommand(intent, 0, ++managedStartId)
            intent = app.nextStartedService
        }
    }

    private fun startServiceIntent(context: Context, intent: android.content.Intent) {
        context.startForegroundService(intent)
        deliverStartedServices(context)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    private fun awaitServiceCallback(latch: CountDownLatch, timeoutSeconds: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (latch.count > 0 && System.nanoTime() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            deliverStartedServices(androidx.test.core.app.ApplicationProvider.getApplicationContext())
            if (latch.count > 0) Thread.sleep(10)
        }
        return latch.count == 0L
    }

    private fun stopManagedService(context: Context) {
        context.stopService(android.content.Intent(context, AgentForegroundService::class.java))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    private fun withHeldWorker(block: (AgentForegroundService, Context, String, CountDownLatch) -> Unit) {
        StopController.getInstance().stopRun()
        val controller = Robolectric.buildService(AgentForegroundService::class.java).create()
        val service = controller.get()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "replacement-${System.nanoTime()}"
        val store = LocalRunStore(context)
        val initialId = store.appendConversationMessage(session, "user", "original")
        AgentRunUiState.beginRun(session, "original")
        val old = StopController.getInstance().beginRun()!!
        AgentRunUiState.bindGeneration(session, old.generation())
        service.registerMainChatSteeringProvider(session, MainChatTurnContextProvider(store, session, initialId))
        AgentForegroundService::class.java.getDeclaredField("activeRunToken").apply { isAccessible = true }.set(service, old)
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
            service.registerMainChatSteeringProvider(session, null)
            AgentForegroundService::class.java.getDeclaredField("activeRunToken").apply { isAccessible = true }.set(service, null)
            if (!worker(service).isShutdown) worker(service).submit {}.get(5, TimeUnit.SECONDS)
            else worker(service).awaitTermination(5, TimeUnit.SECONDS)
            controller.destroy()
        }
    }
}
