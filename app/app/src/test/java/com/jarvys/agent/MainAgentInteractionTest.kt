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
    @get:org.junit.Rule val chatTestStore = ChatTestStore(
        runTestOnWorker = false,
        registerApplicationFilesDirectory = true,
    )

    @After fun cleanup() {
        StopController.stopAllRuns()
        StopController.getInstance().stopRun()
    }

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
        chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "old") }
        AgentRunUiState.beginRun(session, "old")
        val id = chatTestStore.onDatabaseWorker { store.appendInterruptRequest(session, "same text", emptyList(), false) }
        AgentRunUiState.refreshPersistedSession(session, chatTestStore.onDatabaseWorker { store.readConversationTimeline(session) })
        assertTrue(AgentRunUiState.state.value.events.any { it.stage == "QUEUED" })
        chatTestStore.onDatabaseWorker { store.dispatchInterruptRequest(session, id) }
        AgentRunUiState.refreshPersistedSession(session, chatTestStore.onDatabaseWorker { store.readConversationTimeline(session) })
        AgentRunUiState.beginRun(session, "same text")
        AgentRunUiState.bindCurrentUserMessage(session, id)
        assertEquals(1, AgentRunUiState.state.value.events.count { it.messageId == id })
        assertFalse(AgentRunUiState.state.value.events.any { it.stage == "QUEUED" })
    }

    @Test fun interruptedPendingInputRecoveryLeavesDurableRequestForRetry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "interrupted-recovery-${System.nanoTime()}"
        val store = LocalRunStore(context)
        chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "initial request") }
        val requestId = chatTestStore.onDatabaseWorker {
            store.appendInterruptRequest(session, "retry after service restart", emptyList(), false)
        }
        val controller = Robolectric.buildService(AgentForegroundService::class.java).create()
        val service = controller.get()
        try {
            // ChatDatabaseBridge translates an interrupted blocking wait to CancellationException.
            val workerInterruptCleared = chatTestStore.onDatabaseWorker {
                Thread.currentThread().interrupt()
                service.runPendingInputRecoverySafely(session)
                !Thread.currentThread().isInterrupted
            }
            assertTrue("Recovery clears the pool worker's interrupt before its next task",
                workerInterruptCleared)
            assertTrue(chatTestStore.onDatabaseWorker { store.readPendingInterruptRequests(session) }
                .any { it.optString("requestId") == requestId })
        } finally {
            controller.destroy()
        }
    }

    @Test fun steeringDuringAnActiveRunPersistsWithoutStoppingThatRun() {
        withHeldWorker { service, context, session, release ->
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "new request", arrayListOf(), false))
            assertTrue(AgentRunUiState.state.value.running)
            assertFalse(StopController.getInstance().isStopped)
            assertTrue(chatTestStore.onDatabaseWorker { LocalRunStore(context).readConversationMessages(session) }.any {
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
            assertFalse(chatTestStore.onDatabaseWorker { LocalRunStore(context).readConversationTimeline(session) }.any { it.stage == "QUEUED" })
        }
    }

    @Test fun steeringDoesNotLaunchAReplacementOrChangeTheActiveGeneration() {
        withHeldWorker { service, context, session, release ->
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "follow up", arrayListOf("safe"), true))
            assertTrue(AgentRunUiState.state.value.running)
            assertFalse(StopController.getInstance().isStopped)
            assertEquals("follow up", chatTestStore.onDatabaseWorker { LocalRunStore(context).readConversationMessages(session) }.last().optString("content"))
            release.countDown()
            worker(service).submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(AgentRunUiState.state.value.running)
        }
    }

    @Test fun steeringMessagesRemainDurableInArrivalOrder() {
        withHeldWorker { service, context, session, release ->
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "B", arrayListOf(), false))
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "C", arrayListOf(), false))
            val rows = chatTestStore.onDatabaseWorker { LocalRunStore(context).readConversationMessages(session) }
                .filter { it.optString("role") == "user" }.map { it.optString("content") }
            assertEquals(listOf("original", "B", "C"), rows)
            release.countDown()
            worker(service).submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(AgentRunUiState.state.value.running)
        }
    }

    @Test fun steeringAdmittedAfterRunExitIsPickedUpExactlyOnce() {
        StopController.getInstance().stopRun()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "steering-exit-race-${System.nanoTime()}"
        val store = LocalRunStore(context)
        val initialId = chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "initial request") }
        val service = startManagedService(context)
        val initialEntered = CountDownLatch(1)
        val releaseInitial = CountDownLatch(1)
        val admissionReached = CountDownLatch(1)
        val releaseAdmission = CountDownLatch(1)
        val emptyRecoveryScan = CountDownLatch(1)
        val followUpEntered = CountDownLatch(1)
        val senderFinished = CountDownLatch(1)
        val initialResultReady = AtomicBoolean(false)
        val calls = AtomicInteger()
        val accepted = AtomicReference<Boolean>()
        val senderFailure = AtomicReference<Throwable?>()
        service.mainChatRunner = AgentForegroundService.MainChatRunner { _, _, _, _, _, progress ->
            if (calls.incrementAndGet() == 1) {
                initialEntered.countDown()
                while (releaseInitial.count > 0) {
                    try { releaseInitial.await() } catch (_: InterruptedException) { }
                }
                initialResultReady.set(true)
                progress.onInitialUserMessageIncorporated()
                CoreAgentLoop.Result("initial-race-run", "initial done", 1, "COMPLETED")
            } else {
                followUpEntered.countDown()
                progress.onInitialUserMessageIncorporated()
                CoreAgentLoop.Result("follow-up-race-run", "follow-up done", 1, "COMPLETED")
            }
        }
        service.beforeInterruptAdmissionForTesting = Runnable {
            admissionReached.countDown()
            releaseAdmission.await()
        }
        service.afterPendingInputRecoveryScanForTesting = Runnable {
            if (initialResultReady.get()) emptyRecoveryScan.countDown()
        }
        try {
            AgentRunUiState.resetSession(session)
            service.onStartCommand(AgentForegroundService.storedChatMessageIntent(context, session, initialId), 0, 1)
            assertTrue("The owning run reaches its held model response", initialEntered.await(5, TimeUnit.SECONDS))
            val sender = Thread {
                try {
                    accepted.set(AgentForegroundService.interruptAndSend(
                        context, session, "arrived at run exit", arrayListOf(), false,
                    ))
                } catch (failure: Throwable) {
                    senderFailure.set(failure)
                } finally {
                    senderFinished.countDown()
                }
            }
            sender.start()
            assertTrue("Steering pauses before session admission", admissionReached.await(5, TimeUnit.SECONDS))

            releaseInitial.countDown()
            assertTrue("The original run leaves its session lane", awaitRunFinished(service, context))
            assertTrue("The run's empty recovery scan has completed", emptyRecoveryScan.await(5, TimeUnit.SECONDS))
            releaseAdmission.countDown()

            assertTrue("Steering admission returns", senderFinished.await(5, TimeUnit.SECONDS))
            assertNull(senderFailure.get())
            assertEquals(true, accepted.get())
            assertTrue("The admitted message runs as one follow-up", awaitServiceCallback(followUpEntered, 10))
            assertTrue("The follow-up leaves its session lane", awaitRunFinished(service, context))

            val messages = chatTestStore.onDatabaseWorker { store.readConversationMessages(session) }
            val steering = messages.single { it.optString("role") == "user"
                && it.optString("content") == "arrived at run exit" }
            val steeringId = steering.optString("messageId")
            assertTrue(chatTestStore.onDatabaseWorker { store.incorporatedConversationMessageIds(session) }.contains(steeringId))
            assertEquals(1, messages.count { it.optString("role") == "assistant"
                && it.optString("userMessageId") == steeringId })
            assertTrue(chatTestStore.onDatabaseWorker { store.readPendingInterruptRequests(session) }.isEmpty())
            assertEquals(2, calls.get())
        } finally {
            releaseInitial.countDown()
            releaseAdmission.countDown()
            service.beforeInterruptAdmissionForTesting = null
            service.afterPendingInputRecoveryScanForTesting = null
            AgentForegroundService.stopRun(session)
            stopManagedService(context)
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
                while (chatTestStore.onDatabaseWorker { store.readPendingInterruptRequests(session) }.size != 1 && System.nanoTime() < deadline)
                    Thread.yield()
                assertEquals(1, chatTestStore.onDatabaseWorker { store.readPendingInterruptRequests(session) }.size)
                second.start()
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            }
            first.join(5000)
            second.join(5000)
            assertFalse(first.isAlive)
            assertFalse(second.isAlive)
            assertEquals(true, firstResult.get())
            assertEquals(true, secondResult.get())
            val users = chatTestStore.onDatabaseWorker { LocalRunStore(context).readConversationMessages(session) }
                .filter { it.optString("role") == "user" }.map { it.optString("content") }
            assertEquals(listOf("original", "first", "second"), users)
        }
    }

    @Test fun finalDrainConsumesDispatchedSteeringWithoutStoppingTheRun() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "final-drain-${System.nanoTime()}"
        val store = LocalRunStore(context)
        val initialId = chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "initial") }
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
            val id = chatTestStore.onDatabaseWorker { store.appendInterruptRequest(session, "please inspect this", emptyList(), false) }
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
        val initialId = chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "initial") }
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
                        val requestId = chatTestStore.onDatabaseWorker { store.appendInterruptRequest(session, "retry the accepted input", emptyList(), false) }
                        assertTrue(provider.dispatchPendingRequest(requestId))
                        throw ProviderHttpException("temporary provider outage", 503, 0L, null)
                    }
                }, CoreToolRegistry(emptyList()), "", session, CorePromptBudget.standard(), null,
                    CoreAgentLoop.ToolResultBudget.BOUNDED, provider, null)
                loop.run(goal, history, attachments, token, progress)
            } else {
                followUp.countDown()
                progress.onInitialUserMessageIncorporated()
                CoreAgentLoop.Result("follow-up", "follow-up retained", 1, "PARTIAL")
            }
        }
        try {
            AgentRunUiState.resetSession(session)
            startServiceIntent(context, AgentForegroundService.storedChatMessageIntent(context, session, initialId))
            assertTrue("A dispatched message survives a pre-checkpoint provider error", awaitServiceCallback(followUp, 10))
            service.awaitSessionIdleForTesting(session).get(10, TimeUnit.SECONDS)
            assertEquals(listOf("initial", "retry the accepted input"), observedGoals)
            val messages = chatTestStore.onDatabaseWorker { store.readConversationMessages(session) }
            assertTrue(messages.any { it.optString("role") == "assistant" && it.optString("status") == "PARTIAL" })
            assertTrue(messages.any { it.optString("role") == "user" && it.optString("content") == "retry the accepted input" })
        } finally {
            StopController.getInstance().stopRun()
            stopManagedService(context)
        }
    }

    @Test fun transcriptIncorporatedSteeringIsNotReplayedAfterPartialCompletion() =
        assertIncorporatedSteeringIsNotReplayed("partial")

    @Test fun transcriptIncorporatedSteeringIsNotReplayedAfterFailure() =
        assertIncorporatedSteeringIsNotReplayed("failed")

    @Test fun transcriptIncorporatedSteeringIsNotReplayedAfterTimeout() =
        assertIncorporatedSteeringIsNotReplayed("timeout")

    private fun assertIncorporatedSteeringIsNotReplayed(terminal: String) {
        StopController.getInstance().stopRun()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "steering-consumed-$terminal-${System.nanoTime()}"
        val store = LocalRunStore(context)
        val initialId = chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "initial request") }
        val service = startManagedService(context)
        val runCalls = AtomicInteger()
        val modelCalls = AtomicInteger()
        val followUp = CountDownLatch(1)
        val observedGoals = java.util.Collections.synchronizedList(mutableListOf<String>())
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { runtimeContext, id, skills, memoryDisabled ->
            CoreAgentRuntime(runtimeContext, id, skills, CorePromptBudget.standard(), memoryDisabled)
        }
        service.mainChatRunner = AgentForegroundService.MainChatRunner { runtime, goal, history, attachments, token, progress ->
            observedGoals.add(goal)
            if (runCalls.incrementAndGet() > 1) {
                followUp.countDown()
                CoreAgentLoop.Result("unexpected-follow-up", "unexpected follow-up", 1, "PARTIAL")
            } else {
                val provider = runtime.mainChatSteeringProvider()!!
                val model = object : CoreAgentLoop.Model {
                    override fun complete(transcript: List<ConversationTurn>, prompt: String,
                        declarations: List<ToolSpec>, current: CancellationToken): ModelReply {
                        if (modelCalls.incrementAndGet() == 1) {
                            val requestId = chatTestStore.onDatabaseWorker { store.appendInterruptRequest(
                                session, "accepted steering", emptyList(), false) }
                            assertTrue(provider.dispatchPendingRequest(requestId))
                            return ModelReply("Intermediate answer", emptyList())
                        }
                        when (terminal) {
                            "partial" -> throw ProviderHttpException("temporary provider outage", 503, 0L, null)
                            "failed" -> throw IllegalStateException("failure after steering was incorporated")
                            "timeout" -> {
                                assertTrue(current.cancelForTimeout())
                                current.throwIfCancelled()
                            }
                        }
                        return ModelReply("unexpected final answer", emptyList())
                    }
                }
                CoreAgentLoop(model, CoreToolRegistry(emptyList()), "", session,
                    CorePromptBudget.standard(), null, CoreAgentLoop.ToolResultBudget.BOUNDED, provider, null)
                    .run(goal, history, attachments, token, progress)
            }
        }
        try {
            AgentRunUiState.resetSession(session)
            startServiceIntent(context, AgentForegroundService.storedChatMessageIntent(context, session, initialId))
            assertTrue("The service run finishes", awaitRunFinished(service, context))
            worker(service).submit {}.get(10, TimeUnit.SECONDS)
            deliverStartedServices(context)
            worker(service).submit {}.get(10, TimeUnit.SECONDS)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            deliverStartedServices(context)
            assertEquals(listOf("initial request"), observedGoals)
            assertEquals(1, runCalls.get())
            assertEquals(1L, followUp.count)
            val assistants = chatTestStore.onDatabaseWorker { store.readConversationMessages(session) }.filter { it.optString("role") == "assistant" }
            assertTrue(assistants.any { it.optString("status") == if (terminal == "failed") "FAILED" else "PARTIAL" })
        } finally {
            StopController.getInstance().stopRun()
            stopManagedService(context)
        }
    }

    @Test fun pickedButUnincorporatedSteeringIsFollowedUpOnceAfterCancellation() {
        StopController.getInstance().stopRun()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "steering-picked-not-incorporated-${System.nanoTime()}"
        val store = LocalRunStore(context)
        val initialId = chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "initial request") }
        val service = startManagedService(context)
        val runCalls = AtomicInteger()
        val observedGoals = java.util.Collections.synchronizedList(mutableListOf<String>())
        val followUp = CountDownLatch(1)
        val steeringId = AtomicReference("")
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { runtimeContext, id, skills, memoryDisabled ->
            CoreAgentRuntime(runtimeContext, id, skills, CorePromptBudget.standard(), memoryDisabled)
        }
        service.mainChatRunner = AgentForegroundService.MainChatRunner { runtime, goal, history, attachments, token, progress ->
            observedGoals.add(goal)
            if (runCalls.incrementAndGet() > 1) {
                followUp.countDown()
                progress.onInitialUserMessageIncorporated()
                CoreAgentLoop.Result("follow-up", "follow-up processed", 1, "PARTIAL")
            } else {
                val provider = runtime.mainChatSteeringProvider()!!
                val model = object : CoreAgentLoop.Model {
                    override fun complete(transcript: List<ConversationTurn>, prompt: String,
                        declarations: List<ToolSpec>, current: CancellationToken): ModelReply {
                        val requestId = chatTestStore.onDatabaseWorker { store.appendInterruptRequest(
                            session, "picked but not incorporated", emptyList(), false) }
                        steeringId.set(requestId)
                        assertTrue(provider.dispatchPendingRequest(requestId))
                        val picked = provider.takeDurableMessages()
                        assertEquals("picked but not incorporated", picked.single().content)
                        current.cancelForTimeout()
                        current.throwIfCancelled()
                        return ModelReply("unreachable", emptyList())
                    }
                }
                CoreAgentLoop(model, CoreToolRegistry(emptyList()), "", session,
                    CorePromptBudget.standard(), null, CoreAgentLoop.ToolResultBudget.BOUNDED, provider, null)
                    .run(goal, history, attachments, token, progress)
            }
        }
        try {
            AgentRunUiState.resetSession(session)
            startServiceIntent(context, AgentForegroundService.storedChatMessageIntent(context, session, initialId))
            assertTrue("The unincorporated message starts a recovery turn", awaitServiceCallback(followUp, 10))
            assertTrue("Both service turns finish", awaitRunFinished(service, context))
            assertEquals(listOf("initial request", "picked but not incorporated"), observedGoals)
            assertEquals(1, observedGoals.count { it == "picked but not incorporated" })
            val messages = chatTestStore.onDatabaseWorker { store.readConversationMessages(session) }
            assertFalse("The duplicate recovery must not write a spurious FAILED row: $messages", messages.any {
                it.optString("role") == "assistant" && it.optString("status") == "FAILED"
                    && it.optString("userMessageId") == steeringId.get()
            })
        } finally {
            StopController.getInstance().stopRun()
            stopManagedService(context)
        }
    }

    @Test fun assistantAnswerBeforeSteeringIsVisibleAndRestoredInConversationOrder() {
        StopController.getInstance().stopRun()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "assistant-before-steering-${System.nanoTime()}"
        val store = LocalRunStore(context)
        val initialId = chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "initial request") }
        val service = startManagedService(context)
        val modelCalls = AtomicInteger()
        val finalAnswer = CountDownLatch(1)
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { runtimeContext, id, skills, memoryDisabled ->
            CoreAgentRuntime(runtimeContext, id, skills, CorePromptBudget.standard(), memoryDisabled)
        }
        service.mainChatRunner = AgentForegroundService.MainChatRunner { runtime, goal, history, attachments, token, progress ->
            val provider = runtime.mainChatSteeringProvider()!!
            val loop = CoreAgentLoop(object : CoreAgentLoop.Model {
                override fun complete(transcript: List<ConversationTurn>, prompt: String,
                    declarations: List<ToolSpec>, current: CancellationToken): ModelReply {
                    if (modelCalls.incrementAndGet() == 1) {
                        val requestId = chatTestStore.onDatabaseWorker { store.appendInterruptRequest(
                            session, "steering request", emptyList(), false) }
                        assertTrue(provider.dispatchPendingRequest(requestId))
                        return ModelReply("A1", emptyList())
                    }
                    val messages = transcript.filter { it.kind == ConversationTurn.Kind.MESSAGE }
                        .map { "${it.role}:${it.content}" }
                    assertEquals(listOf("user:initial request", "assistant:A1", "user:steering request"), messages)
                    finalAnswer.countDown()
                    return ModelReply("A2", emptyList())
                }
            }, CoreToolRegistry(emptyList()), "", session, CorePromptBudget.standard(), null,
                CoreAgentLoop.ToolResultBudget.BOUNDED, provider, null)
            MainChatTranscriptStore(context.filesDir, session, store).attach(loop, history)
            loop.run(goal, history, attachments, token, progress)
        }
        try {
            AgentRunUiState.resetSession(session)
            startServiceIntent(context, AgentForegroundService.storedChatMessageIntent(context, session, initialId))
            assertTrue("The second model answer is produced", awaitServiceCallback(finalAnswer, 10))
            assertTrue("The service run finishes", awaitRunFinished(service, context))

            val visibleMessages = chatTestStore.onDatabaseWorker { store.readConversationTimeline(session) }
                .filter { it.kind == "user" || it.kind == "assistant" }
                .map { "${it.kind}:${it.text}" }
            assertEquals(listOf(
                "user:initial request", "assistant:A1", "user:steering request", "assistant:A2"), visibleMessages)
            val restoredMessages = chatTestStore.onDatabaseWorker { store.loadConversationContext(session) }
                .filter { it.kind == ConversationTurn.Kind.MESSAGE }
                .map { "${it.role}:${it.content}" }
            assertEquals(listOf(
                "user:initial request", "assistant:A1", "user:steering request", "assistant:A2"), restoredMessages)
            assertEquals(1, visibleMessages.count { it == "assistant:A1" })
            assertEquals(1, visibleMessages.count { it == "assistant:A2" })
            val assistantRows = chatTestStore.onDatabaseWorker { store.readConversationMessages(session) }.filter { it.optString("role") == "assistant" }
            assertEquals(initialId, assistantRows.single { it.optString("content") == "A1" }.optString("userMessageId"))
            assertEquals("steering request", chatTestStore.onDatabaseWorker { store.readConversationMessage(session,
                assistantRows.single { it.optString("content") == "A2" }.optString("userMessageId")) })
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
        val initialId = chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "initial request") }
        val service = startManagedService(context)
        val calls = AtomicInteger()
        val followUp = CountDownLatch(1)
        val observedGoals = java.util.Collections.synchronizedList(mutableListOf<String>())
        val followUpHistory = AtomicReference<List<ConversationTurn>>(emptyList())
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { runtimeContext, id, skills, memoryDisabled ->
            CoreAgentRuntime(runtimeContext, id, skills, CorePromptBudget.standard(), memoryDisabled)
        }
        service.mainChatRunner = AgentForegroundService.MainChatRunner { runtime, goal, history, _, _, progress ->
            observedGoals.add(goal)
            if (calls.incrementAndGet() == 1) {
                val provider = runtime.mainChatSteeringProvider()!!
                val olderRequestId = chatTestStore.onDatabaseWorker { store.appendInterruptRequest(
                    session, "older accepted steering", emptyList(), false) }
                assertTrue(provider.dispatchPendingRequest(olderRequestId))
                val incorporated = provider.takeDurableMessages()
                assertEquals(1, incorporated.size)
                provider.durableMessagesIncorporated(incorporated)
                provider.closeForRunExit()

                assertTrue(AgentForegroundService.interruptAndSend(
                    context, session, "newer fallback input", arrayListOf(), false))
                CoreAgentLoop.Result("initial-partial", "initial work paused", 1, "PARTIAL")
            } else {
                followUpHistory.set(history.toList())
                followUp.countDown()
                progress.onInitialUserMessageIncorporated()
                CoreAgentLoop.Result("follow-up", "newer input handled", 1, "PARTIAL")
            }
        }
        try {
            AgentRunUiState.resetSession(session)
            startServiceIntent(context, AgentForegroundService.storedChatMessageIntent(context, session, initialId))
            assertTrue("Recovery starts the newest pending input", awaitServiceCallback(followUp, 10))
            service.awaitSessionIdleForTesting(session).get(10, TimeUnit.SECONDS)

            assertEquals(listOf("initial request", "newer fallback input"), observedGoals)
            assertTrue("history=${followUpHistory.get().map { it.role + ":" + it.content.take(40) }} goals=$observedGoals", followUpHistory.get().any {
                it.role == "user" && it.content == "older accepted steering"
            })
            // The newest input is the follow-up run goal (never part of its history); it must be consumed exactly once.
            assertEquals(1, observedGoals.count { it == "newer fallback input" } +
                followUpHistory.get().count { it.role == "user" && it.content == "newer fallback input" })
            assertTrue(chatTestStore.onDatabaseWorker { store.readPendingInterruptRequests(session) }.isEmpty())
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
        val initialId = chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "initial request") }
        val service = startManagedService(context)
        val calls = AtomicInteger()
        val followUp = CountDownLatch(1)
        val observedGoals = java.util.Collections.synchronizedList(mutableListOf<String>())
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { _, _, _, _ ->
            CoreAgentRuntime(emptyList(), emptyList(), emptyList(), emptyList())
        }
        service.mainChatRunner = AgentForegroundService.MainChatRunner { _, goal, _, _, _, progress ->
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
                progress.onInitialUserMessageIncorporated()
                CoreAgentLoop.Result("follow-up", "follow-up started", 1, "PARTIAL")
            }
        }
        try {
            AgentRunUiState.resetSession(session)
            startServiceIntent(context, AgentForegroundService.storedChatMessageIntent(context, session, initialId))
            assertTrue("Fallback input launches a fresh run", awaitServiceCallback(followUp, 10))
            worker(service).submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(listOf("initial request", "follow up after close"), observedGoals)
            assertTrue(chatTestStore.onDatabaseWorker { store.readConversationMessages(session) }.any {
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
        chatTestStore.onDatabaseWorker { store.appendConversationMessage(sessionA, "user", "initial A") }
        chatTestStore.onDatabaseWorker { store.appendConversationMessage(sessionB, "user", "initial B") }
        val service = startManagedService(context)
        val observed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val bothStarted = CountDownLatch(2)
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { _, _, _, _ ->
            CoreAgentRuntime(emptyList(), emptyList(), emptyList(), emptyList())
        }
        service.mainChatRunner = AgentForegroundService.MainChatRunner { _, goal, _, _, _, progress ->
            observed.add(goal)
            bothStarted.countDown()
            progress.onInitialUserMessageIncorporated()
            CoreAgentLoop.Result("recovered", "visible recovery", 1, "PARTIAL")
        }
        try {
            chatTestStore.onDatabaseWorker { store.appendInterruptRequest(sessionA, "pending A", emptyList(), false) }
            chatTestStore.onDatabaseWorker { store.appendInterruptRequest(sessionB, "pending B", emptyList(), false) }
            val expected = chatTestStore.onDatabaseWorker { store.readPendingInterruptRequests() }.filter {
                it.optString("sessionId") == sessionA || it.optString("sessionId") == sessionB
            }.map { it.optString("content") }
            AgentRunUiState.resetSession(sessionA)
            startServiceIntent(context, android.content.Intent(context, AgentForegroundService::class.java)
                .setAction(AgentForegroundService.ACTION_RECOVER_PENDING_INPUTS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (bothStarted.count > 0 && System.nanoTime() < deadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                deliverStartedServices(context)
                Thread.sleep(10)
            }
            assertEquals(0, bothStarted.count)
            assertTrue("Both recovery runs drain", awaitRunFinished(service, context))
            assertEquals(expected, observed)
            assertTrue(chatTestStore.onDatabaseWorker { store.readPendingInterruptRequests() }.isEmpty())
            assertEquals("PARTIAL", chatTestStore.onDatabaseWorker { store.readConversationMessages(sessionA) }.last().optString("status"))
            assertEquals("PARTIAL", chatTestStore.onDatabaseWorker { store.readConversationMessages(sessionB) }.last().optString("status"))
        } finally {
            StopController.getInstance().stopRun()
            stopManagedService(context)
        }
    }

    @Test fun dispatchedUnincorporatedSteeringRecoversOnceAfterServiceRestart() {
        StopController.getInstance().stopRun()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocalRunStore(context)
        val session = "recover-dispatched-steering-${System.nanoTime()}"
        val initialId = chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "initial request") }
        val requestId = chatTestStore.onDatabaseWorker { store.appendInterruptRequest(session, "accepted steering", emptyList(), false) }
        assertTrue(chatTestStore.onDatabaseWorker { store.dispatchInterruptRequest(session, requestId) } != null)
        val pickedProvider = MainChatTurnContextProvider(store, session, initialId)
        assertEquals(requestId, pickedProvider.takeDurableMessages().single().id)
        pickedProvider.closeForRunExit()

        startManagedService(context)
        stopManagedService(context)
        val recoveredService = startManagedService(context)
        val calls = AtomicInteger()
        val recovered = CountDownLatch(1)
        recoveredService.runtimeFactory = AgentForegroundService.RuntimeFactory { _, _, _, _ ->
            CoreAgentRuntime(emptyList(), emptyList(), emptyList(), emptyList())
        }
        recoveredService.mainChatRunner = AgentForegroundService.MainChatRunner { _, goal, _, _, _, progress ->
            assertEquals("accepted steering", goal)
            calls.incrementAndGet()
            progress.onInitialUserMessageIncorporated()
            recovered.countDown()
            CoreAgentLoop.Result("recovered-dispatched", "recovered once", 1, "PARTIAL")
        }
        try {
            startServiceIntent(context, android.content.Intent(context, AgentForegroundService::class.java)
                .setAction(AgentForegroundService.ACTION_RECOVER_PENDING_INPUTS))
            assertTrue("Dispatched input is recovered after service restart", awaitServiceCallback(recovered, 10))
            assertTrue("Recovery run finishes", awaitRunFinished(recoveredService, context))
            assertEquals(1, calls.get())
            assertTrue(chatTestStore.onDatabaseWorker { store.readPendingInterruptRequests(session) }.isEmpty())
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
            val messages = chatTestStore.onDatabaseWorker { LocalRunStore(context).readConversationMessages(session) }
            val user = messages.single { it.optString("role") == "user" && it.optString("content") == "preserved" }
            assertTrue("Rejected recovery is visible as a failed follow-up", messages.any {
                it.optString("role") == "assistant" && it.optString("status") == "FAILED"
                    && it.optString("userMessageId") == user.optString("messageId")
            })
            assertTrue(chatTestStore.onDatabaseWorker { LocalRunStore(context).readPendingInterruptRequests(session) }.isEmpty())
            release.countDown()
            StopController.getInstance().stopRun()
        }
    }

    @Test fun stoppingAnActiveRunDoesNotLaunchItsQueuedSteeringFollowUp() {
        StopController.getInstance().stopRun()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "stopped-steering-${System.nanoTime()}"
        val store = LocalRunStore(context)
        val initialId = chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "original") }
        val service = startManagedService(context)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { runtimeContext, id, skills, memoryDisabled ->
            CoreAgentRuntime(runtimeContext, id, skills, CorePromptBudget.standard(), memoryDisabled)
        }
        val runCalls = AtomicInteger()
        service.mainChatRunner = AgentForegroundService.MainChatRunner { _, _, _, _, _, _ ->
            runCalls.incrementAndGet()
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
            val steeringMessages = listOf("cancel with parent run 1", "cancel with parent run 2", "cancel with parent run 3")
            for (message in steeringMessages)
                assertTrue(AgentForegroundService.interruptAndSend(context, session, message, arrayListOf(), false))
            val nextGeneration = StopController::class.java.getDeclaredField("NEXT_GENERATION")
                .apply { isAccessible = true }.get(null) as java.util.concurrent.atomic.AtomicLong
            AgentForegroundService.stopRun(session)
            service.awaitStopTasksForTesting().get(5, TimeUnit.SECONDS)
            assertTrue(AgentRunUiState.snapshotForSession(session).stopping)
            val afterStop = nextGeneration.get()
            release.countDown()
            service.awaitSessionIdleForTesting(session).get(10, TimeUnit.SECONDS)
            assertEquals("A stopped owner does not start a replacement run", afterStop, nextGeneration.get())
            val messages = chatTestStore.onDatabaseWorker { store.readConversationMessages(session) }
            val queued = messages.filter { it.optString("role") == "user" && it.optString("content") in steeringMessages }
            assertEquals(steeringMessages.size, queued.size)
            for (input in queued) assertTrue(messages.any {
                it.optString("role") == "assistant" && it.optString("status") == "FAILED"
                    && it.optString("userMessageId") == input.optString("messageId")
            })
            val incorporatedIds = chatTestStore.onDatabaseWorker { store.incorporatedConversationMessageIds(session) }
            assertTrue(queued.none { incorporatedIds.contains(it.optString("messageId")) })
            assertTrue(chatTestStore.onDatabaseWorker { store.readPendingInterruptRequests(session) }.isEmpty())
            assertFalse(AgentRunUiState.state.value.running)
            assertEquals(1, runCalls.get())
            startServiceIntent(context, android.content.Intent(context, AgentForegroundService::class.java)
                .setAction(AgentForegroundService.ACTION_RECOVER_PENDING_INPUTS))
            assertTrue("The recovery scan finishes without a follow-up", awaitRunFinished(service, context))
            assertEquals("Resolved STOP inputs never start after recovery", 1, runCalls.get())
        } finally {
            release.countDown()
            AgentForegroundService.stopRun(session)
            if (!worker(service).isShutdown) service.awaitSessionIdleForTesting(session).get(5, TimeUnit.SECONDS)
            stopManagedService(context)
        }
    }

    @Test fun deterministicRuntimeFactoryFailureWritesOneFailedResponseAndDoesNotRetry() {
        StopController.getInstance().stopRun()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "runtime-factory-failure-${System.nanoTime()}"
        val store = LocalRunStore(context)
        chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "initial request") }
        val steeringId = chatTestStore.onDatabaseWorker { store.appendInterruptRequest(session, "retry after owner exit", emptyList(), false) }
        assertTrue(chatTestStore.onDatabaseWorker { store.dispatchInterruptRequest(session, steeringId) } != null)
        val service = startManagedService(context)
        val factoryCalls = AtomicInteger()
        val runnerCalls = AtomicInteger()
        val factoryEntered = CountDownLatch(1)
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { _, _, _, _ ->
            factoryEntered.countDown()
            factoryCalls.incrementAndGet()
            throw IllegalStateException("deterministic runtime construction failure")
        }
        service.mainChatRunner = AgentForegroundService.MainChatRunner { _, _, _, _, _, _ ->
            runnerCalls.incrementAndGet()
            CoreAgentLoop.Result("unexpected-runner", "unexpected", 1, "PARTIAL")
        }
        try {
            startServiceIntent(context, android.content.Intent(context, AgentForegroundService::class.java)
                .setAction(AgentForegroundService.ACTION_RECOVER_PENDING_INPUTS))
            assertTrue("Pending recovery enters runtime setup", awaitServiceCallback(factoryEntered, 10))
            assertTrue("The failed run and its recovery scan finish", awaitRunFinished(service, context))
            val failures = chatTestStore.onDatabaseWorker { store.readConversationMessages(session) }.filter {
                it.optString("role") == "assistant" && it.optString("status") == "FAILED"
                    && it.optString("userMessageId") == steeringId
            }
            assertEquals(1, factoryCalls.get())
            assertEquals(0, runnerCalls.get())
            assertEquals(1, failures.size)
            assertTrue(chatTestStore.onDatabaseWorker { store.readPendingInterruptRequests(session) }.isEmpty())
        } finally {
            StopController.getInstance().stopRun()
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
            assertEquals(1, chatTestStore.onDatabaseWorker { store.readPendingInterruptRequests(session) }.size)
            chatTestStore.onDatabaseWorker { store.deleteConversation(session) }
            assertTrue(chatTestStore.onDatabaseWorker { store.readConversationMetadata(session) }.deleted)
            assertTrue(chatTestStore.onDatabaseWorker { store.readPendingInterruptRequests(session) }.isEmpty())
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

    @Test fun messageSentAfterStopAppearsImmediatelyAndSurvivesOldWorkerExit() {
        val session = "stop-then-send-${System.nanoTime()}"
        AgentRunUiState.resetSession(session)
        AgentRunUiState.beginRun(session, "old task")
        AgentRunUiState.bindGeneration(session, 701L)
        AgentRunUiState.stopPendingGeneration(session, 701L)
        assertTrue(AgentRunUiState.state.value.stopping)

        AgentRunUiState.beginRun(session, "new message")
        AgentRunUiState.bindCurrentUserMessage(session, "new-message-id")
        assertEquals("new message", AgentRunUiState.state.value.events.last { it.kind == "user" }.text)
        assertTrue(AgentRunUiState.state.value.running)
        assertFalse(AgentRunUiState.state.value.stopping)
        AgentRunUiState.finishPendingGeneration(session, 701L)
        assertTrue(AgentRunUiState.state.value.running)
        assertEquals("new message", AgentRunUiState.state.value.goal)
        assertFalse(AgentRunUiState.state.value.events.any { it.kind == "assistant" && it.text == "old task stopped" })
        AgentRunUiState.resetSession("$session-cleanup")
    }

    @Test fun otherChatRunsBesideActiveChatAndStopIsScopedToItsSession() {
        withHeldWorker { service, context, session, release ->
            val other = "$session-other"
            AgentRunUiState.resetSession(other)
            val store = LocalRunStore(context)
            val messageId = chatTestStore.onDatabaseWorker { store.appendConversationMessage(other, "user", "different chat") }
            val started = CountDownLatch(1)
            val releaseOther = CountDownLatch(1)
            service.mainChatRunner = AgentForegroundService.MainChatRunner { _, _, _, _, _, _ ->
                started.countDown()
                while (releaseOther.count > 0) {
                    try { releaseOther.await() } catch (_: InterruptedException) { }
                }
                CoreAgentLoop.Result("other-session-run", "other session finished", 1, "COMPLETED")
            }
            service.onStartCommand(AgentForegroundService.storedChatMessageIntent(context, other, messageId), 0, 2)
            assertTrue("The second session starts while the first session is still working", started.await(5, TimeUnit.SECONDS))
            assertTrue(AgentRunUiState.snapshotForSession(other).running)
            assertFalse(StopController.getInstance().isStopped)
            assertTrue(AgentForegroundService.interruptAndSend(context, session, "steer session A", arrayListOf(), false))
            assertTrue(AgentForegroundService.interruptAndSend(context, other, "steer session B", arrayListOf(), false))
            assertTrue(chatTestStore.onDatabaseWorker { store.readPendingInterruptRequests(session) }.any { it.optString("content") == "steer session A" })
            assertTrue(chatTestStore.onDatabaseWorker { store.readPendingInterruptRequests(other) }.any { it.optString("content") == "steer session B" })
            val notification = AgentForegroundService::class.java.getDeclaredMethod("buildForegroundNotification")
                .apply { isAccessible = true }.invoke(service) as android.app.Notification
            assertEquals(service.getResources().getQuantityString(R.plurals.agent_notification_active_runs, 2, 2),
                notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
            assertEquals(service.getString(R.string.agent_notification_stop_all), notification.actions.single().title.toString())

            AgentForegroundService.stopRun(other)
            service.awaitStopTasksForTesting().get(5, TimeUnit.SECONDS)
            assertTrue(AgentRunUiState.snapshotForSession(other).stopping)
            assertFalse("Stopping the second session leaves the first session's token active", StopController.getInstance().isStopped)
            releaseOther.countDown()
            service.awaitSessionIdleForTesting(other).get(5, TimeUnit.SECONDS)
            val activeRuns = AgentForegroundService::class.java.getDeclaredField("activeRuns")
                .apply { isAccessible = true }.get(service) as Map<*, *>
            assertFalse("The stopped second session eventually unwinds", activeRuns.containsKey(other))
            assertEquals("STOPPED", AgentRunUiState.snapshotForSession(other).outcome)
            assertFalse(StopController.getInstance().isStopped)
            release.countDown()
        }
    }

    @Test fun messagesForOneSessionRunInArrivalOrder() {
        StopController.getInstance().stopRun()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "ordered-session-${System.nanoTime()}"
        val store = LocalRunStore(context)
        val firstId = chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "first message") }
        val secondId = chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "second message") }
        val service = startManagedService(context)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val observed = java.util.Collections.synchronizedList(mutableListOf<String>())
        service.mainChatRunner = AgentForegroundService.MainChatRunner { _, goal, _, _, _, _ ->
            observed.add(goal)
            if (goal == "first message") {
                firstStarted.countDown()
                while (releaseFirst.count > 0) {
                    try { releaseFirst.await() } catch (_: InterruptedException) { }
                }
            } else if (goal == "second message") secondStarted.countDown()
            CoreAgentLoop.Result("ordered-$goal", "finished $goal", 1, "COMPLETED")
        }
        try {
            AgentRunUiState.resetSession(session)
            service.onStartCommand(AgentForegroundService.storedChatMessageIntent(context, session, firstId), 0, 1)
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            service.onStartCommand(AgentForegroundService.storedChatMessageIntent(context, session, secondId), 0, 2)
            assertFalse(secondStarted.await(100, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertTrue("Both turns leave the per-session lane", awaitRunFinished(service, context))
            assertEquals(listOf("first message", "second message"), observed.toList())
        } finally {
            releaseFirst.countDown()
            StopController.stopAllRuns()
            stopManagedService(context)
        }
    }

    @Test fun stopCancelsAndResolvesQueuedMessagesForOnlyThatSession() {
        StopController.getInstance().stopRun()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "stop-queued-${System.nanoTime()}"
        var service: AgentForegroundService? = null
        val releaseFirst = CountDownLatch(1)
        var stopEnqueued = false
        try {
            val store = chatTestStore.onDatabaseWorker { LocalRunStore(context) }
            val firstId = chatTestStore.onDatabaseWorker {
                store.appendConversationMessage(session, "user", "first held message")
            }
            val serviceForTest = startManagedService(context)
            service = serviceForTest
            val firstEntered = CountDownLatch(1)
            val calls = AtomicInteger()
            serviceForTest.mainChatRunner = AgentForegroundService.MainChatRunner { _, goal, _, _, _, _ ->
                calls.incrementAndGet()
                if (goal == "first held message") {
                    firstEntered.countDown()
                    while (releaseFirst.count > 0) {
                        try { releaseFirst.await() } catch (_: InterruptedException) { }
                    }
                }
                CoreAgentLoop.Result("stop-queued-$goal", "finished $goal", 1, "COMPLETED")
            }
            AgentRunUiState.resetSession(session)
            serviceForTest.onStartCommand(AgentForegroundService.storedChatMessageIntent(context, session, firstId), 0, 1)
            assertTrue("The first request owns the lane", firstEntered.await(5, TimeUnit.SECONDS))
            val (followUpRequestId, followUpMessageId) = chatTestStore.onDatabaseWorker {
                val requestId = store.appendInterruptRequest(session, "must stay queued", emptyList(), false)
                val dispatched = store.dispatchInterruptRequest(session, requestId)
                    ?: throw AssertionError("The durable follow-up request must dispatch once")
                requestId to dispatched.optString("messageId")
            }
            val queuedIntent = android.content.Intent(context, AgentForegroundService::class.java)
                .setAction(AgentForegroundService.ACTION_REAL_AGENT)
                .putExtra("agent_goal", "must stay queued")
                .putExtra("agent_display_goal", "must stay queued")
                .putExtra("chat_session_id", session)
                .putExtra("chat_user_message_recorded", true)
                .putExtra("chat_user_message_id", followUpMessageId)
                .putExtra("chat_follow_up_request_id", followUpRequestId)
            serviceForTest.onStartCommand(queuedIntent, 0, 2)

            AgentForegroundService.stopRun(session)
            stopEnqueued = true
            serviceForTest.awaitStopTasksForTesting().get(10, TimeUnit.SECONDS)
            val stoppedMessages = chatTestStore.onDatabaseWorker {
                store.readConversationMessages(session)
            }
            val queuedMessage = stoppedMessages.single { it.optString("messageId") == followUpMessageId }
            val stoppedResponse = stoppedMessages.single { it.optString("role") == "assistant"
                && it.optString("userMessageId") == followUpMessageId }
            assertEquals("must stay queued", queuedMessage.optString("content"))
            assertEquals("FAILED", stoppedResponse.optString("status"))
            assertTrue(chatTestStore.onDatabaseWorker {
                store.resolvedConversationMessageIds(session).contains(followUpRequestId)
            })
            assertTrue(chatTestStore.onDatabaseWorker { store.readPendingInterruptRequests(session).isEmpty() })
            releaseFirst.countDown()
            serviceForTest.awaitSessionIdleForTesting(session).get(10, TimeUnit.SECONDS)

            assertEquals("STOP must not promote the queued request", 1, calls.get())
            assertEquals(serviceForTest.getString(R.string.agent_run_stopped), stoppedResponse.optString("content"))
            assertFalse(chatTestStore.onDatabaseWorker {
                store.incorporatedConversationMessageIds(session).contains(followUpRequestId)
            })
            val state = AgentRunUiState.snapshotForSession(session)
            assertFalse(state.running)
            assertFalse(state.stopping)
            assertEquals("STOPPED", state.outcome)
        } finally {
            releaseFirst.countDown()
            try {
                val activeService = service
                if (activeService != null) {
                    if (!stopEnqueued) AgentForegroundService.stopRun(session)
                    activeService.awaitStopTasksForTesting().get(10, TimeUnit.SECONDS)
                    activeService.awaitSessionIdleForTesting(session).get(10, TimeUnit.SECONDS)
                }
            } finally {
                if (service != null) stopManagedService(context)
            }
        }
    }

    @Test fun stopCannotMissPublishedRunBeforeGenerationBinding() {
        StopController.getInstance().stopRun()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "stop-bind-window-${System.nanoTime()}"
        val store = LocalRunStore(context)
        val userMessageId = chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "stop during bind") }
        val service = startManagedService(context)
        val runPublished = CountDownLatch(1)
        val allowBind = CountDownLatch(1)
        val stopStarted = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val runnerEntered = CountDownLatch(1)
        val releaseRunner = CountDownLatch(1)
        val runnerCalls = AtomicInteger()
        service.beforeGenerationBindForTesting = Runnable {
            runPublished.countDown()
            allowBind.await()
        }
        service.mainChatRunner = AgentForegroundService.MainChatRunner { _, _, _, _, _, _ ->
            runnerCalls.incrementAndGet()
            runnerEntered.countDown()
            while (releaseRunner.count > 0) {
                try { releaseRunner.await() } catch (_: InterruptedException) { }
            }
            CoreAgentLoop.Result("stop-bind-window", "run completed", 1, "COMPLETED")
        }
        try {
            AgentRunUiState.resetSession(session)
            service.onStartCommand(AgentForegroundService.storedChatMessageIntent(context, session, userMessageId), 0, 1)
            assertTrue("The service publishes the run before binding its UI generation", runPublished.await(5, TimeUnit.SECONDS))

            val stopper = Thread {
                stopStarted.countDown()
                AgentForegroundService.stopRun(session)
                stopReturned.countDown()
            }
            stopper.start()
            assertTrue("STOP reaches the published run", stopStarted.await(5, TimeUnit.SECONDS))
            allowBind.countDown()
            assertTrue("STOP completes after generation binding", stopReturned.await(5, TimeUnit.SECONDS))
            releaseRunner.countDown()
            service.awaitSessionIdleForTesting(session).get(10, TimeUnit.SECONDS)

            assertTrue(runnerCalls.get() <= 1)
            val state = AgentRunUiState.snapshotForSession(session)
            assertFalse("A stopped generation must release the busy UI", state.running)
            assertFalse(state.stopping)
            assertEquals("STOPPED", state.outcome)
        } finally {
            allowBind.countDown()
            releaseRunner.countDown()
            service.beforeGenerationBindForTesting = null
            AgentForegroundService.stopRun(session)
            stopManagedService(context)
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

    @Test fun concurrentGateApprovalsStayWithTheirRunSessions() {
        val sessionA = "approval-concurrent-a"
        val sessionB = "approval-concurrent-b"
        val controllerA = StopController.forRun()
        val controllerB = StopController.forRun()
        val tokenA = controllerA.beginRun() ?: error("first run token unavailable")
        val tokenB = controllerB.beginRun() ?: error("second run token unavailable")
        AgentRunUiState.resetSession(sessionA)
        AgentRunUiState.beginRun(sessionA, "first approval")
        AgentRunUiState.bindGeneration(sessionA, tokenA.generation())
        AgentRunUiState.resetSession(sessionB)
        AgentRunUiState.beginRun(sessionB, "second approval")
        AgentRunUiState.bindGeneration(sessionB, tokenB.generation())
        AgentRunUiState.resetSession("approval-unrelated-visible")
        AgentRunUiState.showApproval("legacy-ambiguous", "Unattributed action", listOf("details"), null)
        assertFalse(AgentRunUiState.snapshotForSession(sessionA).events.any { it.approvalId == "legacy-ambiguous" })
        assertFalse(AgentRunUiState.snapshotForSession(sessionB).events.any { it.approvalId == "legacy-ambiguous" })
        AgentRunUiState.showSession(sessionB)

        val ids = java.util.concurrent.ConcurrentHashMap<String, String>()
        val shown = CountDownLatch(2)
        val gate = com.jarvys.agent.connectors.ApprovalGate(10_000, object : com.jarvys.agent.connectors.ApprovalPresenter {
            override fun show(id: String, summary: com.jarvys.agent.connectors.ApprovalSummary) {
                AgentRunUiState.showApproval(id, summary.title, summary.lines, summary.permission)
            }
            override fun update(id: String, decision: com.jarvys.agent.connectors.ApprovalDecision) {
                AgentRunUiState.updateApproval(id, decision.name)
            }
            override fun show(id: String, summary: com.jarvys.agent.connectors.ApprovalSummary, token: CancellationToken) {
                ids[summary.title] = id
                AgentRunUiState.showApprovalForGeneration(token.generation(), id, summary)
                shown.countDown()
            }
            override fun update(id: String, decision: com.jarvys.agent.connectors.ApprovalDecision, token: CancellationToken) {
                AgentRunUiState.updateApprovalForGeneration(token.generation(), id, decision.name)
            }
        })
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val resultA = workers.submit<com.jarvys.agent.connectors.ApprovalDecision> {
                gate.request(com.jarvys.agent.connectors.ApprovalSummary("approval A", listOf("A details")), tokenA)
            }
            val resultB = workers.submit<com.jarvys.agent.connectors.ApprovalDecision> {
                gate.request(com.jarvys.agent.connectors.ApprovalSummary("approval B", listOf("B details")), tokenB)
            }
            assertTrue("both requests reached their presenter", shown.await(5, TimeUnit.SECONDS))

            val idA = ids.getValue("approval A")
            val idB = ids.getValue("approval B")
            assertNotEquals(idA, idB)
            assertEquals(listOf(idA), AgentRunUiState.snapshotForSession(sessionA).events.mapNotNull { it.approvalId })
            assertEquals(listOf(idB), AgentRunUiState.snapshotForSession(sessionB).events.mapNotNull { it.approvalId })
            assertEquals(idB, AgentRunUiState.state.value.events.single { it.kind == "approval" }.approvalId)

            assertTrue(gate.resolve(idA, com.jarvys.agent.connectors.ApprovalDecision.DENIED))
            assertEquals(com.jarvys.agent.connectors.ApprovalDecision.DENIED, resultA.get(5, TimeUnit.SECONDS))
            assertTrue(gate.isPending(idB))
            assertEquals("PENDING", AgentRunUiState.snapshotForSession(sessionB).events.single { it.approvalId == idB }.approvalStatus)

            assertTrue(gate.resolve(idB, com.jarvys.agent.connectors.ApprovalDecision.APPROVED))
            assertEquals(com.jarvys.agent.connectors.ApprovalDecision.APPROVED, resultB.get(5, TimeUnit.SECONDS))
            assertEquals("DENIED", AgentRunUiState.snapshotForSession(sessionA).events.single { it.approvalId == idA }.approvalStatus)
            assertEquals("APPROVED", AgentRunUiState.snapshotForSession(sessionB).events.single { it.approvalId == idB }.approvalStatus)
        } finally {
            workers.shutdownNow()
            AgentRunUiState.completeGeneration(sessionA, tokenA.generation(), "run-a", "STOPPED", "stopped", "", 0)
            AgentRunUiState.completeGeneration(sessionB, tokenB.generation(), "run-b", "STOPPED", "stopped", "", 0)
            controllerA.stopRun()
            controllerB.stopRun()
            AgentRunUiState.resetSession("approval-concurrent-cleanup")
        }
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
        val id = chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "must remain recoverable") }
        AgentRunUiState.beginRun(session, "must remain recoverable")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        if (reject) {
            worker(service).shutdown()
        } else {
            service.runtimeFactory = AgentForegroundService.RuntimeFactory { _, _, _, _ ->
                CoreAgentRuntime(emptyList(), emptyList(), emptyList(), emptyList())
            }
            service.mainChatRunner = AgentForegroundService.MainChatRunner { _, _, _, _, token, _ ->
                started.countDown()
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { }
                }
                token.throwIfCancelled()
                CoreAgentLoop.Result("cancelled-before-model", "unreachable", 1, "PARTIAL")
            }
        }
        try {
            service.onStartCommand(AgentForegroundService.storedChatMessageIntent(context, session, id), 0, 1)
            if (!reject) {
                assertTrue("The run reaches the pre-model gate", started.await(5, TimeUnit.SECONDS))
                assertTrue(com.jarvys.agent.proactive.BackgroundRunController.isInteractiveActive())
                AgentForegroundService.stopRun(session)
                release.countDown()
            }
            assertTrue("The session work drains", awaitRunFinished(service, context))
            service.awaitStopTasksForTesting().get(5, TimeUnit.SECONDS)
            service.awaitSessionIdleForTesting(session).get(5, TimeUnit.SECONDS)
            assertFalse(com.jarvys.agent.proactive.BackgroundRunController.isInteractiveActive())
            assertFalse(AgentRunUiState.state.value.running)
            assertTrue(StopController.getInstance().isStopped)
            assertEquals("must remain recoverable", chatTestStore.onDatabaseWorker { store.readConversationMessage(session, id) })
            if (reject) assertTrue(chatTestStore.onDatabaseWorker { store.readConversationMessages(session) }.any { it.optString("status") == "FAILED" })
        } finally {
            AgentForegroundService.stopRun(session)
            release.countDown()
            if (!worker(service).isShutdown) awaitRunFinished(service, context)
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
        val id = chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "read only") }
        val factoryEntered = CountDownLatch(1)
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { _, _, _, _ ->
            factoryEntered.countDown()
            throw IllegalStateException("synthetic runtime initialization failure")
        }
        AgentRunUiState.beginRun(session, "read only")
        try {
            service.onStartCommand(AgentForegroundService.storedChatMessageIntent(context, session, id), 0, 1)
            assertTrue("The session enters runtime setup", factoryEntered.await(10, TimeUnit.SECONDS))
            assertTrue("The failed setup drains its session lane", awaitRunFinished(service, context))
            assertFalse(com.jarvys.agent.proactive.BackgroundRunController.isInteractiveActive())
            assertTrue(StopController.getInstance().isStopped)
            assertFalse(AgentRunUiState.state.value.running)
            assertEquals("FAILED", AgentRunUiState.state.value.outcome)
            assertTrue(chatTestStore.onDatabaseWorker { store.readConversationMessages(session) }.any { it.optString("status") == "FAILED" })
            assertTrue(chatTestStore.onDatabaseWorker { store.readModelTranscriptRows(session) }.isEmpty())
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
    private var priorSecretStore: Any? = null

    /** The real run builds repositories that need Android encrypted storage, which Robolectric lacks: use the package-local in-memory seam. */
    private fun installInMemorySecretStore(context: Context) {
        val field = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
        priorSecretStore = field.get(null)
        field.set(null, SecretStore(context.getSharedPreferences("steering-fixture-secrets", Context.MODE_PRIVATE)))
    }

    private fun restoreSecretStore() {
        val field = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
        field.set(null, priorSecretStore)
        priorSecretStore = null
    }

    private fun startManagedService(context: Context): AgentForegroundService {
        installInMemorySecretStore(context)
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

    private fun awaitRunFinished(service: AgentForegroundService, context: Context): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            deliverStartedServices(context)
            if (sessionWorkIdle(service)) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                deliverStartedServices(context)
                if (sessionWorkIdle(service)) return true
            }
            Thread.sleep(10)
        }
        return false
    }

    private fun sessionWorkIdle(service: AgentForegroundService): Boolean {
        val active = AgentForegroundService::class.java.getDeclaredField("activeRuns")
            .apply { isAccessible = true }.get(service) as Map<*, *>
        val lanes = AgentForegroundService::class.java.getDeclaredField("sessionLanes")
            .apply { isAccessible = true }.get(service) as Map<*, *>
        return active.isEmpty() && lanes.isEmpty()
    }

    private fun stopManagedService(context: Context) {
        context.stopService(android.content.Intent(context, AgentForegroundService::class.java))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        restoreSecretStore()
    }

    private fun withHeldWorker(block: (AgentForegroundService, Context, String, CountDownLatch) -> Unit) {
        StopController.getInstance().stopRun()
        val controller = Robolectric.buildService(AgentForegroundService::class.java).create()
        val service = controller.get()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "replacement-${System.nanoTime()}"
        val store = LocalRunStore(context)
        val initialId = chatTestStore.onDatabaseWorker { store.appendConversationMessage(session, "user", "original") }
        AgentRunUiState.beginRun(session, "original")
        val old = StopController.getInstance().beginRun()!!
        AgentRunUiState.bindGeneration(session, old.generation())
        service.registerMainChatSteeringProvider(session, MainChatTurnContextProvider(store, session, initialId))
        AgentForegroundService::class.java.getDeclaredField("activeRunToken").apply { isAccessible = true }.set(service, old)
        (AgentForegroundService::class.java.getDeclaredField("activeRunTokens")
            .apply { isAccessible = true }.get(service) as MutableMap<String, CancellationToken>)[session] = old
        com.jarvys.agent.proactive.BackgroundRunController.interactiveStarted(old.generation())
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workerExited = CountDownLatch(1)
        val future = worker(service).submit {
            started.countDown()
            try { while (release.count > 0) try { release.await() } catch (_: InterruptedException) { } }
            finally {
                com.jarvys.agent.proactive.BackgroundRunController.interactiveFinished(old.generation())
                workerExited.countDown()
            }
        }
        StopController.getInstance().attachFuture(old, future)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        try { block(service, context, session, release) }
        finally {
            AgentRunUiState.stopPendingGeneration(session, old.generation())
            StopController.getInstance().stopRun(); release.countDown()
            service.registerMainChatSteeringProvider(session, null)
            AgentForegroundService::class.java.getDeclaredField("activeRunToken").apply { isAccessible = true }.set(service, null)
            (AgentForegroundService::class.java.getDeclaredField("activeRunTokens")
                .apply { isAccessible = true }.get(service) as MutableMap<String, CancellationToken>).remove(session, old)
            assertTrue("The held worker physically exits after release", workerExited.await(5, TimeUnit.SECONDS))
            AgentRunUiState.completeGeneration(session, old.generation(), "held-worker-stopped",
                "STOPPED", "Run stopped", "", 0L)
            AgentRunUiState.finishPendingGeneration(session, old.generation())
            controller.destroy()
        }
    }
}
