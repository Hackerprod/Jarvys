package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.crew.CrewMessage
import com.jarvys.agent.crew.CrewMissionAccess
import com.jarvys.agent.crew.CrewCoordinationEvidence
import com.jarvys.agent.crew.CrewProfile
import com.jarvys.agent.crew.CrewRole
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ChatDbResult
import com.jarvys.agent.device.ScreenData
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceRegistry
import com.jarvys.agent.ui.chat.ConversationVisibility
import com.jarvys.agent.coding.ProjectScopeStore
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TreeNativeTestApplication::class, shadows = [com.jarvys.agent.ArtifactOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AgentMessagingPipelineTest {
    @get:Rule val chatTestStore = ChatTestStore(runTestOnWorker = false)

    private val applicationContext = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var context: Context
    private var controller: org.robolectric.android.controller.ServiceController<AgentForegroundService>? = null
    private var startId = 0
    private var previousSecretStore: Any? = null
    private var secretStoreInstalled = false
    private val managers = mutableListOf<CrewManager>()
    private val runtimeFailures = Collections.synchronizedList(mutableListOf<String>())

    @Before fun installTestSecrets() {
        runtimeFailures.clear()
        context = applicationContext
        chatTestStore.localRunStore(context.filesDir)
        chatTestStore.onDatabaseWorker {
            val store = LocalRunStore(context)
            val staleCentralInputs = store.readPendingInternalConversationInputs(WorkspaceIdentity.CENTRAL_SESSION)
                .map { it.optString("messageId") }.filter(String::isNotBlank)
            if (staleCentralInputs.isNotEmpty())
                store.markConversationMessagesResolved(WorkspaceIdentity.CENTRAL_SESSION, staleCentralInputs)
        }
        installSecretStore()
        chatTestStore.onDatabaseWorker {
            FreshStorageBootstrap.ensure(context)
            (applicationContext as TreeNativeTestApplication).connectionAccess().initializeAsync().get()
            check((applicationContext as TreeNativeTestApplication).connectionAccess().isReady())
        }
    }

    @After fun cleanup() {
        managers.forEach { runCatching { it.close() } }
        managers.clear()
        StopController.stopAllRuns()
        StopController.getInstance().stopRun()
        controller?.let { runCatching { it.destroy() } }
        controller = null
        restoreSecretStore()
        context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
    }

    @Test fun workerResultWhileCentralIsIdleStartsTurnWithContentAndStaysOffScreen() {
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val worker = createBoundWorker(onModel = { _, token ->
            workerEntered.countDown()
            awaitRelease(releaseWorker, token)
            ModelReply("worker internal completion", emptyList())
        })
        assertTrue(workerEntered.await(10, TimeUnit.SECONDS))
        val observed = AtomicReference<List<ConversationTurn>>()
        val provider = ProviderDouble { history, _ ->
            observed.set(history.toList())
            ModelReply("Jarvys processed the result", emptyList())
        }
        val service = startManagedService()
        installProvider(service, provider)

        worker.manager.reportDone(worker.bot, "APK built; the install check is still pending.", listOf("artifacts/app.apk"))
        deliverStartedServices()
        assertTrue(awaitServiceFinished(service, WorkspaceIdentity.CENTRAL_SESSION, 15))

        val input = observed.get().single { it.incomingMetadata?.origin == ConversationInput.Origin.AGENT }
        assertTrue(input.content.contains("APK built; the install check is still pending."))
        assertTrue(input.content.contains("artifacts/app.apk"))
        assertTrue(input.renderedModelContent("test").contains("origin=\"AGENT\""))
        assertEquals("incorporated", chatTestStore.onDatabaseWorker {
            LocalRunStore(context).readConversationInputState(WorkspaceIdentity.CENTRAL_SESSION, input.durableMessageId)
        })
        val store = LocalRunStore(context)
        val timeline = chatTestStore.onDatabaseWorker { store.readConversationTimeline(WorkspaceIdentity.CENTRAL_SESSION) }
        val deliveredTimelineEvent = timeline.single {
            it.conversationInputOrigin == ConversationInput.Origin.AGENT &&
                it.messageId == input.durableMessageId
        }
        assertTrue(deliveredTimelineEvent.text.contains("APK built"))
        val mainProjection = ConversationVisibility.project(timeline)
        assertFalse(mainProjection.any { it.messageId == deliveredTimelineEvent.messageId })
        assertTrue(mainProjection.any { it.kind == "assistant" && it.text == "Jarvys processed the result" })
        assertEquals("", chatTestStore.onDatabaseWorker { store.latestUserMessageId(WorkspaceIdentity.CENTRAL_SESSION) })
        assertEquals("RESULT", input.incomingMetadata.type)
    }

    @Test fun blockerStartsCentralWithoutWaitingForAnotherUserMessage() {
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val worker = createBoundWorker(onModel = { _, token ->
            workerEntered.countDown()
            awaitRelease(releaseWorker, token)
            ModelReply("Waiting for a chief reply", emptyList())
        })
        assertTrue(workerEntered.await(10, TimeUnit.SECONDS))
        val observed = AtomicReference<List<ConversationTurn>>()
        val service = startManagedService()
        installProvider(service, ProviderDouble { history, _ ->
            observed.set(history.toList())
            ModelReply("I saw the blocker and will respond", emptyList())
        })

        worker.manager.sendFromWorker(worker.bot, worker.bot.token, "chief", CrewMessage.Type.BLOCKER,
            "BLOCKER: deployment credentials are missing.", emptyList())
        deliverStartedServices()
        assertTrue("the blocker starts a Central turn without a user input",
            awaitServiceFinished(service, WorkspaceIdentity.CENTRAL_SESSION, 15))
        val delivered = observed.get().single { it.incomingMetadata?.origin == ConversationInput.Origin.AGENT }
        assertTrue(delivered.content.contains("deployment credentials are missing"))
        assertEquals("BLOCKER", delivered.incomingMetadata.type)
        assertTrue(chatTestStore.onDatabaseWorker {
            LocalRunStore(context).readConversationMessages(WorkspaceIdentity.CENTRAL_SESSION)
        }.none { it.optString("role") == "user" })
        releaseWorker.countDown()
    }

    @Test(timeout = 120_000) fun everyContentMessageTypeToChiefReachesCentralAsAnAttributedInput() {
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val worker = createBoundWorker(onModel = { _, token ->
            workerEntered.countDown()
            awaitRelease(releaseWorker, token)
            ModelReply("Worker remains available for messages", emptyList())
        })
        assertTrue("worker did not start", workerEntered.await(10, TimeUnit.SECONDS))

        val central = startManagedService()
        val observed = Collections.synchronizedList(mutableListOf<List<ConversationTurn>>())
        installProvider(central, ProviderDouble { history, token ->
            observed += history.toList()
            ModelReply("Central processed worker input", emptyList())
        })

        val sent = mutableListOf<CrewMessage>()
        try {
            for (type in CrewMessage.Type.values()) {
                if (type == CrewMessage.Type.PROGRESS || type == CrewMessage.Type.USER
                    || type == CrewMessage.Type.TASK) {
                    val failure = try {
                        worker.manager.sendFromWorker(worker.bot, worker.bot.token, "chief", type,
                            "Central intake ${type.name}", emptyList())
                        null
                    } catch (expected: IllegalArgumentException) { expected }
                    val expected = when (type) {
                        CrewMessage.Type.PROGRESS -> "PROGRESS is runtime-generated presentation only"
                        CrewMessage.Type.USER -> "Workers cannot assert native USER authority"
                        else -> "Workers cannot assign TASK work to the captain"
                    }
                    assertEquals(expected, failure?.message)
                    continue
                }
                val message = worker.manager.sendFromWorker(worker.bot, worker.bot.token, "chief", type,
                    "Central intake ${type.name}", emptyList())
                sent += message
                deliverStartedServices()
                assertTrue("Central did not finish the ${message.type} input",
                    awaitServiceFinished(central, WorkspaceIdentity.CENTRAL_SESSION, 20))
                val input = observed.flatMap { it }.singleOrNull {
                    it.incomingMetadata?.id == message.id
                }
                val rows = chatTestStore.onDatabaseWorker {
                    LocalRunStore(context).readConversationMessages(WorkspaceIdentity.CENTRAL_SESSION)
                        .filter { it.optString("role") == "agent" }
                }
                assertNotNull("${message.type} did not reach Jarvys' provider transcript; " +
                    "observed=${observed.flatMap { it }.map { it.incomingMetadata?.type + ":" + it.durableMessageId }} " +
                    "rows=${rows.map { it.optJSONObject("provenance")?.optString("kind") + ":" + it.optString("messageId") }} " +
                    "report=${AgentForegroundService.getLastRunReport()}", input)
                assertEquals(message.type.name, input!!.incomingMetadata.type)
                assertEquals(message.text, input.content)
                assertEquals(ConversationInput.Provenance.PrincipalRole.SUBORDINATE,
                    input.incomingMetadata.provenance.principalRole)
            }
        } finally {
            releaseWorker.countDown()
        }
        assertEquals(CrewMessage.Type.values().size - 3, sent.size)
    }

    @Test fun askChiefQuestionEntersCentralAsAnInternalInput() {
        val question = "Should I use the primary source for this comparison?"
        val workerCalls = AtomicInteger()
        val centralHistory = AtomicReference<List<ConversationTurn>>()
        val service = startManagedService()
        installProvider(service, ProviderDouble { history, _ ->
            centralHistory.set(history.toList())
            ModelReply("I recommend the primary source.", emptyList())
        })
        val worker = createBoundWorker(
            requestedTools = listOf("ask_chief"),
            configureBeforeStart = { manager ->
                manager.configureCoordinationEvidenceListener(object : CrewManager.CoordinationEvidenceListener {
                    override fun onCoordinationEvidence(evidence: CrewCoordinationEvidence) {
                        LocalRunStore(context).appendCrewCoordinationEvidence(evidence, Runnable {})
                        com.jarvys.agent.workspaces.WorkspaceChanges.coordinationEvidenceSaved(
                            evidence.conversationId, evidence.missionId, evidence.evidenceId)
                        WorkspaceCoordinationDelivery.schedule(context, evidence.conversationId)
                    }
                })
            },
            onModel = { _, _ ->
                if (workerCalls.incrementAndGet() == 1) {
                    ModelReply("", listOf(ModelReply.Call("ask-chief", "ask_chief", mapOf("question" to question))))
                } else ModelReply("The chief answered.", emptyList())
            },
        )
        val store = LocalRunStore(context)
        awaitCondition(15) {
            chatTestStore.onDatabaseWorker {
                store.readConversationMessages(WorkspaceIdentity.CENTRAL_SESSION).any {
                    it.optString("role") == "agent" && it.optString("content") == question
                }
            }
        }
        deliverStartedServices()
        assertTrue("ask_chief starts Central from its question content",
            awaitServiceFinished(service, WorkspaceIdentity.CENTRAL_SESSION, 15))

        val centralRows = chatTestStore.onDatabaseWorker {
            store.readConversationMessages(WorkspaceIdentity.CENTRAL_SESSION)
        }
        val history = centralHistory.get()
        assertNotNull("Central provider did not run; pending=${store.readPendingInternalConversationInputs(WorkspaceIdentity.CENTRAL_SESSION)}, " +
            "rows=$centralRows, report=${AgentForegroundService.getLastRunReport()}, " +
            "ui=${AgentRunUiState.snapshotForSession(WorkspaceIdentity.CENTRAL_SESSION)}", history)
        val input = history!!.single { it.incomingMetadata?.origin == ConversationInput.Origin.AGENT }
        assertEquals(question, input.content)
        assertEquals("QUESTION", input.incomingMetadata.type)
        assertEquals(worker.scope.executionSessionId, input.incomingMetadata.provenance.sessionId)
        assertEquals(worker.assignment.assignmentId, input.incomingMetadata.provenance.assignmentId)
        assertEquals(worker.bot.id, input.incomingMetadata.provenance.botId)
        assertTrue(input.incomingMetadata.untrusted)
        assertEquals("incorporated", chatTestStore.onDatabaseWorker {
            store.readConversationInputState(WorkspaceIdentity.CENTRAL_SESSION, input.durableMessageId)
        })
        val timeline = chatTestStore.onDatabaseWorker { store.readConversationTimeline(WorkspaceIdentity.CENTRAL_SESSION) }
        val internalQuestion = timeline.single { it.text == question }
        assertEquals(ConversationInput.Origin.AGENT, internalQuestion.conversationInputOrigin)
        assertFalse(ConversationVisibility.visible(internalQuestion))
        assertTrue(timeline.any { it.kind == "assistant" && it.text == "I recommend the primary source." })
    }

    @Test fun blockerArrivingDuringCentralRunIsSteeredIntoTheNextModelStep() {
        val userId = chatTestStore.onDatabaseWorker {
            LocalRunStore(context).appendConversationMessage(WorkspaceIdentity.CENTRAL_SESSION, "user", "Review the assignment")
        }
        val centralEntered = CountDownLatch(1)
        val releaseCentral = CountDownLatch(1)
        val centralCalls = Collections.synchronizedList(mutableListOf<List<ConversationTurn>>())
        val centralProvider = ProviderDouble { history, token ->
            centralCalls += history.toList()
            if (centralCalls.size == 1) {
                centralEntered.countDown()
                awaitRelease(releaseCentral, token)
                ModelReply("I will check the incoming update", emptyList())
            } else ModelReply("The blocker is addressed", emptyList())
        }
        val service = startManagedService()
        installProvider(service, centralProvider)
        val runtimeFailure = AtomicReference<RuntimeException>()
        service.mainChatRunner = AgentForegroundService.MainChatRunner { runtime, goal, history, attachments, token, progress ->
            try { runtime.runMainChat(goal, history, attachments, token, progress) }
            catch (failure: RuntimeException) {
                runtimeFailure.set(failure)
                throw AssertionError("Real Central runtime failed during blocker steering", failure)
            }
        }
        startStoredMessage(userId)
        assertTrue(centralEntered.await(10, TimeUnit.SECONDS))

        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val worker = createBoundWorker(onModel = { _, token ->
            workerEntered.countDown()
            awaitRelease(releaseWorker, token)
            ModelReply("worker remains blocked", emptyList())
        })
        assertTrue(workerEntered.await(10, TimeUnit.SECONDS))
        worker.manager.sendFromWorker(worker.bot, worker.bot.token, "chief", CrewMessage.Type.BLOCKER,
            "BLOCKER: the signing configuration is missing.", emptyList())
        deliverStartedServices()
        releaseCentral.countDown()
        assertTrue(awaitServiceFinished(service, WorkspaceIdentity.CENTRAL_SESSION, 15))

        assertTrue("the next real provider call sees the blocker; calls=${centralCalls.size}; " +
            "runtimeFailure=${runtimeFailure.get()}; report=${AgentForegroundService.getLastRunReport()}", centralCalls.drop(1).any { transcript ->
            transcript.any { it.incomingMetadata?.origin == ConversationInput.Origin.AGENT &&
                it.content.contains("the signing configuration is missing") }
        })
        val stored = chatTestStore.onDatabaseWorker {
            LocalRunStore(context).readConversationMessages(WorkspaceIdentity.CENTRAL_SESSION)
        }
        assertTrue(stored.any { it.optString("role") == "assistant" && it.optString("content") == "The blocker is addressed" })
        val intermediate = centralCalls.drop(1).first().single {
            it.kind == ConversationTurn.Kind.MESSAGE && it.content == "I will check the incoming update"
        }
        val source = requireNotNull(intermediate.source())
        assertEquals(ConversationTurn.Source.Kind.MESSAGE, source.kind)
        assertEquals(WorkspaceIdentity.CENTRAL_SESSION, source.conversationId)
        assertTrue(stored.any { it.optString("messageId") == source.sourceId &&
            it.optString("role") == "assistant" && it.optString("content") == intermediate.content })
        assertNull(runtimeFailure.get())
    }

    @Test fun resultArrivingWhileStoppedCentralRunIsStillInFlightStartsAfterStopCleanup() {
        val userId = chatTestStore.onDatabaseWorker {
            LocalRunStore(context).appendConversationMessage(WorkspaceIdentity.CENTRAL_SESSION, "user", "Build the app")
        }
        val centralEntered = CountDownLatch(1)
        val releaseStoppedProvider = CountDownLatch(1)
        val centralCalls = Collections.synchronizedList(mutableListOf<List<ConversationTurn>>())
        val firstCall = AtomicInteger()
        val centralProvider = ProviderDouble { history, token ->
            centralCalls += history.toList()
            if (firstCall.incrementAndGet() == 1) {
                centralEntered.countDown()
                // Hold the real service/loop inside its provider call after STOP interrupts it.
                // This makes the worker result race the still-running generation's cleanup.
                awaitReleaseIgnoringInterrupt(releaseStoppedProvider)
            }
            ModelReply("The worker result survived the STOP race", emptyList())
        }
        val service = startManagedService()
        installProvider(service, centralProvider)
        startStoredMessage(userId)
        assertTrue(centralEntered.await(10, TimeUnit.SECONDS))

        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val worker = createBoundWorker(onModel = { _, token ->
            workerEntered.countDown()
            awaitRelease(releaseWorker, token)
            ModelReply("worker internal completion", emptyList())
        })
        assertTrue(workerEntered.await(10, TimeUnit.SECONDS))
        AgentForegroundService.stopRun(WorkspaceIdentity.CENTRAL_SESSION)
        service.awaitStopTasksForTesting().get(10, TimeUnit.SECONDS)
        assertFalse("the stopped run remains physically in flight", service
            .awaitSessionIdleForTesting(WorkspaceIdentity.CENTRAL_SESSION).isDone)

        worker.manager.reportDone(worker.bot, "The APK result arrived during STOP cleanup.", emptyList())
        deliverStartedServices()
        val pendingId = awaitPendingAgentInput(WorkspaceIdentity.CENTRAL_SESSION)
        assertEquals(null, chatTestStore.onDatabaseWorker {
            LocalRunStore(context).readConversationInputState(WorkspaceIdentity.CENTRAL_SESSION, pendingId)
        })
        releaseStoppedProvider.countDown()
        assertTrue(awaitServiceFinished(service, WorkspaceIdentity.CENTRAL_SESSION, 15))

        assertTrue("the post-STOP turn received the worker input", centralCalls.any { history ->
            history.any { it.incomingMetadata?.origin == ConversationInput.Origin.AGENT &&
                it.content.contains("arrived during STOP cleanup") }
        })
        val rows = chatTestStore.onDatabaseWorker {
            LocalRunStore(context).readConversationMessages(WorkspaceIdentity.CENTRAL_SESSION)
        }
        assertTrue(rows.any { it.optString("role") == "assistant" && it.optString("content") == "The worker result survived the STOP race" })
        assertEquals("the internal result does not replace the latest human input", userId,
            chatTestStore.onDatabaseWorker { LocalRunStore(context).latestUserMessageId(WorkspaceIdentity.CENTRAL_SESSION) })
        assertEquals("incorporated", chatTestStore.onDatabaseWorker {
            LocalRunStore(context).readConversationInputState(WorkspaceIdentity.CENTRAL_SESSION, pendingId)
        })
    }

    @Test fun workerLoopTakesChiefInputWhileRunningAndReopensAfterDoneWithAnExactAck() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<List<ConversationTurn>>())
        val worker = createBoundWorker(onModel = { history, token ->
            calls += history.toList()
            when (calls.size) {
                1 -> { firstEntered.countDown(); awaitRelease(releaseFirst, token); ModelReply("first step", emptyList()) }
                2 -> ModelReply("worker finished first cycle", emptyList())
                else -> ModelReply("worker finished correction", emptyList())
            }
        })
        val manager = worker.manager
        val bot = worker.bot
        assertTrue(firstEntered.await(10, TimeUnit.SECONDS))
        val activeInput = manager.send("chief", bot.id, CrewMessage.Type.ANSWER, "Use the new manifest path.", emptyList())
        releaseFirst.countDown()
        bot.awaitTermination()
        assertTrue(calls.any { transcript -> transcript.any { turn ->
            turn.incomingMetadata?.origin == ConversationInput.Origin.AGENT && turn.content == "Use the new manifest path."
        } })
        assertTrue(bot.checkpoint().appliedIncomingIds.contains(activeInput.id))
        assertEquals(CrewManager.Status.DONE, bot.status())

        val reopenInput = manager.send("chief", bot.id, CrewMessage.Type.ANSWER, "Correct the manifest entry.", emptyList())
        bot.awaitTermination()
        assertTrue(calls.any { transcript -> transcript.any { turn ->
            turn.incomingMetadata?.origin == ConversationInput.Origin.AGENT && turn.content == "Correct the manifest entry."
        } })
        assertTrue(bot.checkpoint().appliedIncomingIds.contains(reopenInput.id))
        assertEquals(CrewManager.Status.DONE, bot.status())
    }

    @Test fun peerWorkerMessageUsesRecipientInputWithoutEnteringCentralConversation() {
        val calls = Collections.synchronizedList(mutableListOf<List<ConversationTurn>>())
        val centralRoute = Collections.synchronizedList(mutableListOf<CrewMessage>())
        val workersEnteredModel = CountDownLatch(2)
        val releaseWorkers = CountDownLatch(1)
        val worker = createBoundWorker(centralDelivery = false, onModel = { history, _ ->
            calls += history.toList()
            workersEnteredModel.countDown()
            awaitRelease(releaseWorkers, CancellationToken.uncancellable())
            ModelReply("Worker step complete", emptyList())
        })
        val manager = worker.manager
        manager.configureConversationMessageListener { _, message -> centralRoute += message }
        val sender = manager.spawn("custom", "Inspect the source", emptyList(), "Sender")
        val recipient = worker.bot
        assertTrue(workersEnteredModel.await(10, TimeUnit.SECONDS))

        val peerMessage = manager.send(sender.id, recipient.id, CrewMessage.Type.ANSWER,
            "Check the serializer before changing the adapter.", emptyList())
        releaseWorkers.countDown()
        sender.awaitTermination()
        recipient.awaitTermination()

        assertTrue(calls.any { transcript -> transcript.any { turn ->
            turn.incomingMetadata?.origin == ConversationInput.Origin.AGENT &&
                turn.durableMessageId == peerMessage.id &&
                turn.content == "Check the serializer before changing the adapter."
        } })
        assertFalse("peer-to-peer delivery must not enter the worker-to-chief listener",
            centralRoute.any { it.id == peerMessage.id })
    }

    @Test fun initialWorkspaceTaskIsAnAttributedAgentInputWithAssignmentProvenance() {
        val observed = AtomicReference<List<ConversationTurn>>()
        val worker = createBoundWorker(onModel = { history, _ ->
            observed.set(history.toList())
            ModelReply("Task received", emptyList())
        })
        worker.bot.awaitTermination()

        val task = observed.get().single { it.incomingMetadata?.type == "TASK" }
        assertEquals("assistant", task.role)
        assertEquals(ConversationInput.Origin.AGENT, task.incomingMetadata.origin)
        assertTrue(task.incomingMetadata.untrusted)
        assertEquals(ConversationInput.Provenance.PrincipalRole.SUPERVISOR,
            task.incomingMetadata.provenance.principalRole)
        assertEquals("Inspect the current project and report the result.", task.content)
        assertEquals(WorkspaceIdentity.CENTRAL_SESSION, task.incomingMetadata.provenance.sessionId)
        assertEquals(worker.scope.workspaceId, task.incomingMetadata.provenance.workspaceId)
        assertEquals(worker.assignment.assignmentId, task.incomingMetadata.provenance.assignmentId)
        assertEquals(worker.bot.id, task.incomingMetadata.provenance.botId)
        assertEquals(task.durableMessageId, task.incomingMetadata.provenance.messageId)
        assertFalse(observed.get().any { it.role == "user" && it.content == task.content })
        val stored = chatTestStore.onDatabaseWorker {
            LocalRunStore(context).readConversationMessages(worker.scope.executionSessionId)
        }.single { it.optString("role") == "agent" && it.optString("content").contains(task.content) }
        assertEquals("incorporated", stored.optString("inputState"))
    }

    @Test fun chiefReplyIsSteeredIntoRunningWorkerAndDurableIncorporationIsAcknowledged() {
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<List<ConversationTurn>>())
        val worker = createBoundWorker(checkpointSupport = true, centralDelivery = false, onModel = { history, token ->
            calls += history.toList()
            if (calls.size == 1) {
                workerEntered.countDown()
                awaitRelease(releaseWorker, token)
            }
            ModelReply("The correction is incorporated.", emptyList())
        })
        assertTrue(workerEntered.await(10, TimeUnit.SECONDS))
        worker.manager.configureCoordinationEvidenceListener(object : CrewManager.CoordinationEvidenceListener {
            override fun onCoordinationEvidence(evidence: CrewCoordinationEvidence) {
                LocalRunStore(context).appendCrewCoordinationEvidence(evidence, Runnable {})
            }
        })
        worker.manager.sendFromWorker(worker.bot, worker.bot.token, "chief", CrewMessage.Type.BLOCKER,
            "Which manifest entry should I use?", emptyList())
        val evidence = LocalRunStore(context).listCrewCoordinationEvidence(worker.scope.executionSessionId, 10, null)
            .single().let { LocalRunStore(context).readCrewCoordinationEvidence(worker.scope.executionSessionId, it)!! }
        val delivery = WorkspaceCoordinationDelivery(context, LocalRunStore(context)) { worker.manager }
        delivery.reconcile(worker.scope.workspaceId, CancellationToken.uncancellable())
        val reference = LocalRunStore(context)
            .listWorkspaceCoordinationReferences(worker.scope.executionSessionId, 10, null).single()
        assertEquals(evidence.evidenceId, reference.evidenceId)

        val accepted = delivery.reply(worker.scope.workspaceId, reference.referenceId, reference.contentSha256,
            "running-reply-${UUID.randomUUID()}", "Use app/src/main/AndroidManifest.xml.",
            reference.observedAssignmentRevision, "central-turn-${UUID.randomUUID()}",
            worker.assignment.originalUserRequest, CancellationToken.uncancellable(), Runnable {})
        assertEquals("PENDING_INCORPORATION", accepted.getString("state"))
        assertTrue(accepted.getBoolean("admission_committed"))
        val pendingRow = chatTestStore.onDatabaseWorker {
            LocalRunStore(context).readConversationMessages(worker.scope.executionSessionId)
        }.single { it.optString("role") == "agent" && it.optString("content") == "Use app/src/main/AndroidManifest.xml." }
        releaseWorker.countDown()
        worker.bot.awaitTermination()

        assertTrue(calls.drop(1).flatMap { it }.any {
            it.incomingMetadata?.origin == ConversationInput.Origin.AGENT &&
                it.content == "Use app/src/main/AndroidManifest.xml."
        })
        val read = delivery.read(worker.scope.workspaceId, reference.referenceId, reference.contentSha256,
            0, CancellationToken.uncancellable(), Runnable {})
        assertEquals("INCORPORATED_IN_CHECKPOINT", read.getJSONObject("incorporation").getString("state"))
        assertEquals("incorporated", chatTestStore.onDatabaseWorker {
            LocalRunStore(context).readConversationInputState(worker.scope.executionSessionId,
                pendingRow.getString("messageId"))
        })
    }

    @Test fun chiefReplyReopensDoneWorkerAndDurableIncorporationIsAcknowledged() {
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val reopenedWorkerEntered = CountDownLatch(1)
        val releaseReopenedWorker = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<List<ConversationTurn>>())
        val worker = createBoundWorker(checkpointSupport = true, centralDelivery = false, onModel = { history, token ->
            calls += history.toList()
            when (calls.size) {
                1 -> {
                    workerEntered.countDown()
                    awaitRelease(releaseWorker, token)
                    ModelReply("The blocker is saved.", emptyList())
                }
                else -> {
                    reopenedWorkerEntered.countDown()
                    awaitRelease(releaseReopenedWorker, token)
                    ModelReply("The correction is incorporated.", emptyList())
                }
            }
        })
        assertTrue(workerEntered.await(10, TimeUnit.SECONDS))
        worker.manager.configureCoordinationEvidenceListener(object : CrewManager.CoordinationEvidenceListener {
            override fun onCoordinationEvidence(evidence: CrewCoordinationEvidence) {
                LocalRunStore(context).appendCrewCoordinationEvidence(evidence, Runnable {})
            }
        })
        worker.manager.sendFromWorker(worker.bot, worker.bot.token, "chief", CrewMessage.Type.BLOCKER,
            "Which manifest entry should I use?", emptyList())
        val evidence = LocalRunStore(context).listCrewCoordinationEvidence(worker.scope.executionSessionId, 10, null)
            .single().let { LocalRunStore(context).readCrewCoordinationEvidence(worker.scope.executionSessionId, it)!! }
        val delivery = WorkspaceCoordinationDelivery(context, LocalRunStore(context)) { worker.manager }
        delivery.reconcile(worker.scope.workspaceId, CancellationToken.uncancellable())
        val reference = LocalRunStore(context)
            .listWorkspaceCoordinationReferences(worker.scope.executionSessionId, 10, null).single()
        assertEquals(evidence.evidenceId, reference.evidenceId)

        releaseWorker.countDown()
        worker.bot.awaitTermination()
        assertEquals(CrewManager.Status.DONE, worker.bot.status())
        val doneRead = delivery.read(worker.scope.workspaceId, reference.referenceId, reference.contentSha256,
            0, CancellationToken.uncancellable(), Runnable {})
        assertEquals("DONE_REOPENABLE", doneRead.getString("reply_state"))

        val accepted = delivery.reply(worker.scope.workspaceId, reference.referenceId, reference.contentSha256,
            "exact-reply-${UUID.randomUUID()}", "Use app/src/main/AndroidManifest.xml.",
            reference.observedAssignmentRevision, "central-turn-${UUID.randomUUID()}",
            worker.assignment.originalUserRequest, CancellationToken.uncancellable(), Runnable {})
        assertEquals("PENDING_INCORPORATION", accepted.getString("state"))
        assertTrue(accepted.getBoolean("admission_committed"))
        assertTrue("a reply to a BLOCKER reopens its DONE worker", reopenedWorkerEntered.await(10, TimeUnit.SECONDS))
        val pendingRow = chatTestStore.onDatabaseWorker {
            LocalRunStore(context).readConversationMessages(worker.scope.executionSessionId)
        }.single { it.optString("role") == "agent" && it.optString("content") == "Use app/src/main/AndroidManifest.xml." }
        assertEquals("incorporated", pendingRow.optString("inputState"))
        releaseReopenedWorker.countDown()
        worker.bot.awaitTermination()

        val admitted = calls.drop(1).flatMap { it }.single {
            it.incomingMetadata?.origin == ConversationInput.Origin.AGENT &&
                it.content == "Use app/src/main/AndroidManifest.xml."
        }
        assertEquals("ANSWER", admitted.incomingMetadata.type)
        val binding = worker.bot.checkpoint().incorporations.values.single().binding
        WorkspaceInputIncorporationDelivery(context, LocalRunStore(context))
            .reconcile(worker.scope.workspaceId, CancellationToken.uncancellable(), admittedRead = null, admittedInput = null)
        val read = delivery.read(worker.scope.workspaceId, reference.referenceId, reference.contentSha256,
            0, CancellationToken.uncancellable(), Runnable {})
        assertEquals("INCORPORATED_IN_CHECKPOINT", read.getJSONObject("incorporation").getString("state"))
        assertEquals(binding.mailboxMessageId, admitted.durableMessageId)
        assertTrue(worker.bot.checkpoint().appliedIncomingIds.contains(binding.mailboxMessageId))
        val currentAssignment = WorkspaceRegistry(context).use {
            requireNotNull(it.getAssignment(worker.scope.workspaceId, worker.assignment.assignmentId))
        }
        assertEquals(worker.assignment.revision, currentAssignment.revision)
        assertEquals(CrewManager.Status.DONE, worker.bot.status())
        assertEquals("incorporated", chatTestStore.onDatabaseWorker {
            LocalRunStore(context).readConversationInputState(worker.scope.executionSessionId,
                pendingRow.getString("messageId"))
        })
    }

    @Test fun twoWorkersDeliverConcurrentResultsOnceInConversationSequenceOrder() {
        val workerReleases = listOf(CountDownLatch(1), CountDownLatch(1))
        val workerEntered = listOf(CountDownLatch(1), CountDownLatch(1))
        val workers = workerReleases.indices.map { index ->
            createBoundWorker(onModel = { _, token ->
                workerEntered[index].countDown()
                awaitRelease(workerReleases[index], token)
                ModelReply("Concurrent result $index", emptyList())
            })
        }
        workerEntered.forEach { assertTrue(it.await(10, TimeUnit.SECONDS)) }

        val centralCalls = Collections.synchronizedList(mutableListOf<List<ConversationTurn>>())
        val centralEntered = CountDownLatch(1)
        val releaseCentral = CountDownLatch(1)
        val service = startManagedService()
        installProvider(service, ProviderDouble { history, token ->
            centralCalls += history.toList()
            if (centralCalls.size == 1) {
                centralEntered.countDown()
                awaitRelease(releaseCentral, token)
            }
            ModelReply("Both worker results were considered", emptyList())
        })

        val releases = workerReleases.map { Thread { it.countDown() }.apply { start() } }
        releases.forEach { it.join() }
        deliverStartedServices()
        val centralStarted = awaitStartedServicesUntil(centralEntered, 15)
        val diagnosis = if (centralStarted) "" else {
            val pending = chatTestStore.onDatabaseWorker {
                LocalRunStore(context).readPendingInternalConversationInputs(WorkspaceIdentity.CENTRAL_SESSION)
                    .map { it.optString("messageId") to it.optString("inputState") }
            }
            fun field(name: String, owner: Any?): Any? = AgentForegroundService::class.java
                .getDeclaredField(name).apply { isAccessible = true }.get(owner)
            val stacks = Thread.getAllStackTraces().filterValues { trace ->
                trace.any { it.className.startsWith("com.jarvys.") }
            }.map { (thread, trace) -> "${thread.name}:${thread.state}:${trace.take(10)}" }
            "workers=${workers.map { listOf(it.bot.status(), it.bot.error(), it.bot.isQuiescent(), it.manager.resultEvidenceDeliveryFailures()) }}, " +
                "pending=$pending, queued=${field("queuedInternalInputIds",service)}, " +
                "early=${field("EARLY_FAILED_AGENT_INPUT_STARTS",null)}, idle=${service.awaitSessionIdleForTesting(WorkspaceIdentity.CENTRAL_SESSION).isDone}, " +
                "runtimeFailures=$runtimeFailures, report=${AgentForegroundService.getLastRunReport()}, stacks=$stacks"
        }
        assertTrue("Central starts from the first committed worker result; $diagnosis", centralStarted)
        awaitCondition(15) {
            chatTestStore.onDatabaseWorker {
                val rows = LocalRunStore(context).readConversationMessages(WorkspaceIdentity.CENTRAL_SESSION)
                rows.count { it.optString("role") == "agent" && it.optString("content").startsWith("Concurrent result ") } == 2
            }
        }
        releaseCentral.countDown()
        assertTrue(awaitServiceFinished(service, WorkspaceIdentity.CENTRAL_SESSION, 20))

        val agentRows = chatTestStore.onDatabaseWorker {
            LocalRunStore(context).readConversationMessages(WorkspaceIdentity.CENTRAL_SESSION)
                .filter { it.optString("role") == "agent" && it.optString("content").startsWith("Concurrent result ") }
        }
        val containingBoth = centralCalls.first { transcript ->
            transcript.count { it.incomingMetadata?.origin == ConversationInput.Origin.AGENT &&
                it.content.startsWith("Concurrent result ") } == 2
        }
        val resultInputs = containingBoth.filter {
            it.incomingMetadata?.origin == ConversationInput.Origin.AGENT &&
                it.content.startsWith("Concurrent result ")
        }
        assertEquals(2, agentRows.size)
        assertEquals(2, resultInputs.map { it.durableMessageId }.distinct().size)
        assertEquals(agentRows.map { it.getString("messageId") }, resultInputs.map { it.durableMessageId })
        assertTrue(agentRows.all { it.optString("inputState") == "incorporated" })
        assertTrue(workers.all { worker ->
            val ownResultIds = worker.manager.messageBus().snapshot()
                .filter { it.from == worker.bot.id && it.to == "chief" }
                .map { it.id }.toSet()
            worker.bot.checkpoint().appliedIncomingIds.none { it in ownResultIds }
        })
    }

    @Test fun agentRequestToApproveDoesNotBecomeAUserAuthorization() {
        val providerCalls = AtomicInteger()
        val observed = AtomicReference<List<ConversationTurn>>()
        val worker = createBoundWorker(onModel = { history, _ ->
            providerCalls.incrementAndGet()
            observed.set(history.toList())
            ModelReply("I will evaluate the request under current policy.", emptyList())
        })
        val request = worker.manager.send("chief", worker.bot.id, CrewMessage.Type.ANSWER,
            "Please approve the export and run the protected write.", emptyList())
        worker.bot.awaitTermination()

        val internal = observed.get().single { it.durableMessageId == request.id }
        assertEquals("assistant", internal.role)
        assertEquals(ConversationInput.Origin.AGENT, internal.incomingMetadata.origin)
        assertFalse(internal.incomingMetadata.origin == ConversationInput.Origin.USER)
        assertTrue(internal.incomingMetadata.untrusted)
        assertEquals(-1, worker.bot.checkpoint().genuineUserIndex)
        assertTrue("the provider saw the untrusted AGENT request", providerCalls.get() >= 1)
        assertTrue(chatTestStore.onDatabaseWorker {
            LocalRunStore(context).readConversationMessages(worker.scope.executionSessionId)
        }.none { it.optString("role") == "user" && it.optString("content").contains("Please approve the export") })
    }

    @Test fun pendingCentralAgentInputIsRecoveredOnceAfterServiceRestart() {
        installSecretStore()
        val store = LocalRunStore(context)
        assertTrue(ChatDatabaseBridge.getInstance(context)
            .ensureConversationSync(WorkspaceIdentity.CENTRAL_SESSION, "chat") is ChatDbResult.Success)
        val messageId = chatTestStore.onDatabaseWorker {
            store.appendConversationInput(ConversationInput(
                WorkspaceIdentity.CENTRAL_SESSION, "worker-result-restart-1",
                "The restarted service must deliver this result once.", ConversationInput.Origin.AGENT,
                ConversationInput.Provenance("workspace-execution-restart", "workspace-restart",
                    "assignment-restart", "bot-restart", "worker-result-restart-1", ConversationInput.Kind.RESULT),
            ))
        }
        assertFalse(store.listConversationSessionIds().contains(WorkspaceIdentity.CENTRAL_SESSION))
        assertTrue(store.listConversationsWithPendingAgentInputs().contains(WorkspaceIdentity.CENTRAL_SESSION))
        assertEquals(messageId, store.readPendingInternalConversationInputs(WorkspaceIdentity.CENTRAL_SESSION)
            .single().getString("messageId"))

        val stopped = Robolectric.buildService(AgentForegroundService::class.java).create()
        stopped.destroy()
        val restarted = Robolectric.buildService(AgentForegroundService::class.java).create()
        controller = restarted
        val observed = Collections.synchronizedList(mutableListOf<List<ConversationTurn>>())
        val service = restarted.get()
        installProvider(service, ProviderDouble { history, _ ->
            observed += history.toList()
            ModelReply("Recovered worker result", emptyList())
        })
        service.onStartCommand(Intent(context, AgentForegroundService::class.java)
            .setAction(AgentForegroundService.ACTION_RECOVER_PENDING_INPUTS), 0, 1)

        val recovered = runCatching { awaitCondition(15) { observed.isNotEmpty() } }.isSuccess
        val recoveryRows = chatTestStore.onDatabaseWorker { store.readConversationMessages(WorkspaceIdentity.CENTRAL_SESSION) }
            .filter { it.optString("role") == "agent" }
        assertTrue("Recovery did not start a provider turn; state=${store.readConversationInputState(WorkspaceIdentity.CENTRAL_SESSION, messageId)}, pending=${store.readPendingInternalConversationInputs(WorkspaceIdentity.CENTRAL_SESSION)}, sessions=${store.listConversationSessionIds()}, ui=${AgentRunUiState.snapshotForSession(WorkspaceIdentity.CENTRAL_SESSION)}, rows=$recoveryRows, report=${AgentForegroundService.getLastRunReport()}", recovered)
        assertTrue(awaitServiceFinished(service, WorkspaceIdentity.CENTRAL_SESSION, 15))
        val inputId = observed.single().single {
            it.incomingMetadata?.origin == ConversationInput.Origin.AGENT
        }.durableMessageId
        assertEquals(messageId, inputId)
        assertEquals("incorporated", chatTestStore.onDatabaseWorker {
            store.readConversationInputState(WorkspaceIdentity.CENTRAL_SESSION, messageId)
        })
        service.onStartCommand(Intent(context, AgentForegroundService::class.java)
            .setAction(AgentForegroundService.ACTION_RECOVER_PENDING_INPUTS), 0, 2)
        assertTrue(awaitServiceFinished(service, WorkspaceIdentity.CENTRAL_SESSION, 10))
        assertEquals(1, observed.size)
    }

    private data class BoundWorker(
        val manager: CrewManager,
        val bot: CrewManager.Bot,
        val scope: com.jarvys.agent.workspaces.WorkspaceRecord,
        val assignment: com.jarvys.agent.workspaces.WorkspaceAssignment,
    )

    private fun createBoundWorker(
        onModel: (List<ConversationTurn>, CancellationToken) -> ModelReply,
        checkpointSupport: Boolean = false,
        centralDelivery: Boolean = true,
        requestedTools: List<String> = emptyList(),
        configureBeforeStart: (CrewManager) -> Unit = {},
    ): BoundWorker {
        val registry = WorkspaceRegistry(context)
        val key = UUID.randomUUID().toString()
        val scope = registry.createWorkspace("message-$key", "Message pipeline test")
        assertTrue(ChatDatabaseBridge.getInstance(context).ensureConversationSync(scope.executionSessionId, "workspace") is ChatDbResult.Success)
        assertTrue(ChatDatabaseBridge.getInstance(context).ensureConversationSync(WorkspaceIdentity.CENTRAL_SESSION, "chat") is ChatDbResult.Success)
        val manager = newManager(scope.executionSessionId, onModel)
        managers += manager
        val requestId = "request-$key"
        val mission = "Inspect the current project and report the result."
        val prepared = registry.prepareAssignment(scope.workspaceId, "assignment-$key", requestId,
            "Owner request", "custom", mission, emptyList(), "Worker", CrewMissionAccess.STANDARD)
        val started = registry.markStarted(scope.workspaceId, prepared.assignmentId, prepared.revision, "process-$key")
        manager.configureConversationInputPipeline(
            { bot, message -> WorkspaceConversationInputStore.append(context, scope.executionSessionId, bot, message) },
            { _, ids -> WorkspaceConversationInputStore.acknowledge(context, scope.executionSessionId, ids) },
        )
        if (centralDelivery) manager.configureConversationMessageListener { sender, message ->
            WorkspaceAgentInputDelivery.deliver(context, sender, message)
        }
        if (checkpointSupport) {
            val projectIdentity = ProjectScopeStore(context.filesDir).open(scope.executionSessionId).durableIdentity()
            val checkpoints = CrewCheckpointStore(context.filesDir)
            manager.configureCheckpoints(object : CrewManager.CheckpointSupport {
                override fun persist(bot: CrewManager.Bot, messages: List<CrewMessage>, pending: List<CrewMessage>) {
                    val metadata = JSONObject().put("conversationId", scope.executionSessionId)
                        .put("botId", bot.id).put("missionId", bot.missionId).put("mission", bot.mission)
                        .put("assignmentId", bot.assignmentId()).put("status", bot.status().name)
                    checkpoints.save(scope.executionSessionId, bot.id, metadata, projectIdentity,
                        bot.checkpoint(), JSONObject())
                }
                override fun reconcile(bot: CrewManager.Bot): CrewManager.ResumePlan =
                    throw AssertionError("This test does not resume worker work")
            })
        }
        configureBeforeStart(manager)
        val bot = manager.spawnForMissionDeferred(prepared.missionId, "Owner request", "custom", mission,
            requestedTools, "Worker", CrewMissionAccess.STANDARD, null, started)
        val bound = registry.bindBot(scope.workspaceId, prepared.assignmentId, started.revision, bot.id)
        manager.startPreparedForMission(bot)
        registry.close()
        return BoundWorker(manager, bot, scope, bound)
    }

    private fun newManager(sessionId: String,
        onModel: (List<ConversationTurn>, CancellationToken) -> ModelReply): CrewManager =
        chatTestStore.onDatabaseWorker {
            // Runtime discovery may initialize bundled module connections; keep its real worker contract.
            val provider = ProviderDouble(onModel)
            val runtime = CoreAgentRuntime(context, sessionId, emptyList(), CorePromptBudget.standard())
            runtime.configureCrewManager(runtime.createTools(), CoreAgentModel(context, provider, sessionId), null)
        }

    private class ProviderDouble(
        private val respond: (List<ConversationTurn>, CancellationToken) -> ModelReply,
    ) : ModelProviderClient {
        override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>,
            tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply =
            ModelReply("unexpected non-conversation request", emptyList())

        override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
            images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply =
            respond(history, token)
    }

    private fun awaitRelease(release: CountDownLatch, token: CancellationToken) {
        try {
            while (release.count > 0) {
                token.throwIfCancelled()
                release.await(50, TimeUnit.MILLISECONDS)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            token.throwIfCancelled()
            throw IllegalStateException("Unexpected test provider interruption", interrupted)
        }
    }

    private fun awaitReleaseIgnoringInterrupt(release: CountDownLatch) {
        var interrupted = false
        try {
            while (release.count > 0) try {
                release.await()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun awaitPendingAgentInput(sessionId: String): String {
        val store = LocalRunStore(context)
        awaitCondition(10) {
            chatTestStore.onDatabaseWorker {
                store.readPendingInternalConversationInputs(sessionId).isNotEmpty()
            }
        }
        return chatTestStore.onDatabaseWorker {
            store.readPendingInternalConversationInputs(sessionId).first().getString("messageId")
        }
    }

    private fun awaitCondition(timeoutSeconds: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("condition did not become true in ${timeoutSeconds}s", condition())
    }

    private fun startManagedService(): AgentForegroundService {
        installSecretStore()
        val instance = Robolectric.buildService(AgentForegroundService::class.java).create()
        controller = instance
        val service = instance.get()
        worker(service).submit {}.get(5, TimeUnit.SECONDS)
        deliverStartedServices()
        return service
    }

    private fun installProvider(service: AgentForegroundService, provider: ModelProviderClient) {
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { runtimeContext, id, skills ->
            try { CoreAgentRuntime(runtimeContext, id, skills, CorePromptBudget.standard(), provider) }
            catch (failure: RuntimeException) {
                runtimeFailures += "construct:${failure.javaClass.simpleName}:${failure.message}:${failure.cause}"
                throw failure
            }
        }
        val originalRunner = service.mainChatRunner
        service.mainChatRunner = AgentForegroundService.MainChatRunner { runtime, goal, history, attachments, token, progress ->
            try { originalRunner.run(runtime,goal,history,attachments,token,progress) }
            catch (failure: RuntimeException) {
                runtimeFailures += "run:${failure.javaClass.simpleName}:${failure.message}:${failure.cause}:${failure.stackTrace.take(8)}"
                throw failure
            }
        }
    }

    private fun startStoredMessage(messageId: String) {
        context.startForegroundService(AgentForegroundService.storedChatMessageIntent(
            context, WorkspaceIdentity.CENTRAL_SESSION, messageId))
        deliverStartedServices()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    private fun deliverStartedServices() {
        val service = controller?.get() ?: return
        val shadow = org.robolectric.Shadows.shadowOf(applicationContext as Application)
        var intent = shadow.nextStartedService
        while (intent != null) {
            service.onStartCommand(intent, 0, ++startId)
            intent = shadow.nextStartedService
        }
    }

    private fun awaitStartedServicesUntil(latch: CountDownLatch, timeoutSeconds: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline && latch.count > 0) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            deliverStartedServices()
            latch.await(20, TimeUnit.MILLISECONDS)
        }
        return latch.count == 0L
    }

    private fun awaitServiceFinished(service: AgentForegroundService, sessionId: String, seconds: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            deliverStartedServices()
            val idle = service.awaitSessionIdleForTesting(sessionId)
            if (idle.isDone) {
                idle.get(1, TimeUnit.SECONDS)
                deliverStartedServices()
                if (service.awaitSessionIdleForTesting(sessionId).isDone) return true
            }
            Thread.sleep(10)
        }
        return false
    }

    private fun worker(service: AgentForegroundService): ExecutorService =
        AgentForegroundService::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(service) as ExecutorService

    private fun installSecretStore() {
        if (secretStoreInstalled) return
        val field = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
        previousSecretStore = field.get(null)
        field.set(null, SecretStore(context.getSharedPreferences("agent-messaging-test-secrets", Context.MODE_PRIVATE)))
        secretStoreInstalled = true
    }

    private fun restoreSecretStore() {
        if (!secretStoreInstalled) return
        SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
            .set(null, previousSecretStore)
        previousSecretStore = null
        secretStoreInstalled = false
    }

}
