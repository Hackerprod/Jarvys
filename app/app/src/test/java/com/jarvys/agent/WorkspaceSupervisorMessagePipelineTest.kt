package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ChatDbResult
import com.jarvys.agent.crew.CrewBoard
import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.crew.CrewMissionAccess
import com.jarvys.agent.workspaces.CentralWorkspaceTool
import com.jarvys.agent.workspaces.WorkspaceCoordinator
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.Collections
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [ArtifactOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class WorkspaceSupervisorMessagePipelineTest {
    @get:Rule val chatTestStore = ChatTestStore(
        bridgeExecutorFactory = { ChatTestStore.directExecutorService() })

    private val applicationContext = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var context: Context
    private val managers = mutableListOf<CrewManager>()
    private var previousSecretStore: Any? = null

    @Before fun installSecretStore() {
        val files = chatTestStore.newFilesDirectory("workspace-supervisor-app")
        context = IsolatedWorkspaceContext(applicationContext, files)
        chatTestStore.localRunStore(files)
        val field = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
        previousSecretStore = field.get(null)
        field.set(null, SecretStore(context.getSharedPreferences("workspace-supervisor-message-test-secrets", Context.MODE_PRIVATE)))
    }

    @After fun cleanup() {
        managers.forEach { runCatching { it.close() } }
        managers.clear()
        StopController.stopAllRuns()
        StopController.getInstance().stopRun()
        context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
        previousSecretStore?.let { previous ->
            SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }.set(null, previous)
        }
        previousSecretStore = null
    }

    @Test fun realWorkspaceTaskAndMessageUseTypedInputsAndRecoverExactReceipt() {
        val key = UUID.randomUUID().toString()
        val registry = WorkspaceRegistry(context)
        val scope = registry.createWorkspace("message-pipeline-$key", "Supervisor message pipeline")
        val mission = "Read the assigned board note, then ask the chief if follow-up detail is needed."
        assertTrue(ChatDatabaseBridge.getInstance(context)
            .ensureConversationSync(scope.executionSessionId, "workspace") is ChatDbResult.Success)
        assertTrue(ChatDatabaseBridge.getInstance(context)
            .ensureConversationSync(WorkspaceIdentity.CENTRAL_SESSION, "chat") is ChatDbResult.Success)
        CrewBoard(WorkspaceStore.forCrewBoard(context, scope.executionSessionId))
            .post("/board/task.md", "Assigned follow-up notes for the worker to inspect.")

        val initialTaskRead = AtomicBoolean()
        val workerQuestionSent = AtomicBoolean()
        val questionBoundaryEntered = AtomicBoolean()
        val handledMessageIds = Collections.synchronizedSet(mutableSetOf<String>())
        val messageTurn = AtomicReference<ConversationTurn>()
        val doneFollowupTurn = AtomicReference<ConversationTurn>()
        val taskBotId = AtomicReference<String>()
        val questionBoundary = CountDownLatch(1)
        val releaseQuestionBoundary = CountDownLatch(1)
        val followupEntered = CountDownLatch(1)
        val releaseFollowup = CountDownLatch(1)
        val doneFollowupEntered = CountDownLatch(1)
        val prompts = Collections.synchronizedList(mutableListOf<String>())
        val provider = Provider { systemPrompt, history, _, sessionId, token ->
            assertEquals(scope.executionSessionId, sessionId)
            prompts += systemPrompt
            val unhandledSupervisorMessage = history.asSequence().filter {
                it.incomingMetadata?.type == "MESSAGE" &&
                    it.incomingMetadata.provenance.principalRole == ConversationInput.Provenance.PrincipalRole.SUPERVISOR
            }.firstOrNull { handledMessageIds.add(it.durableMessageId) }
            if (unhandledSupervisorMessage != null) {
                if (messageTurn.compareAndSet(null, unhandledSupervisorMessage)) {
                    followupEntered.countDown()
                    awaitRelease(releaseFollowup, token)
                } else {
                    doneFollowupTurn.set(unhandledSupervisorMessage)
                    doneFollowupEntered.countDown()
                }
                ModelReply("Supervisor input handled within the assigned task.", emptyList())
            } else if (history.any { it.incomingMetadata?.type == "TASK" } && initialTaskRead.compareAndSet(false, true)) {
                val task = history.single { it.incomingMetadata?.type == "TASK" }
                assertEquals("user", task.providerMessageRole())
                assertEquals(mission, task.content)
                assertEquals(ConversationInput.Provenance.PrincipalRole.SUPERVISOR,
                    task.incomingMetadata.provenance.principalRole)
                taskBotId.set(task.incomingMetadata.provenance.botId)
                assertTrue(systemPrompt.contains("SUPERVISOR"))
                assertTrue(systemPrompt.contains("within your assigned mission"))
                ModelReply("", listOf(ModelReply.Call("task-board-read", "board_read",
                    mapOf("path" to "/board/task.md"))))
            } else if (history.any { it.incomingMetadata?.type == "TASK" } && workerQuestionSent.compareAndSet(false, true)) {
                assertTrue("the TASK instruction led to an actual read-only board tool call",
                    history.any { it.kind == ConversationTurn.Kind.TOOL_RESULT
                        && it.toolName == "board_read" && it.content.contains("<crew_board") })
                ModelReply("", listOf(ModelReply.Call("task-question", "msg_send", mapOf(
                    "to" to "chief", "type" to "QUESTION",
                    "text" to "Which exact board entry should I use for the follow-up?"))))
            } else if (history.any { it.incomingMetadata?.type == "TASK" } &&
                questionBoundaryEntered.compareAndSet(false, true)) {
                questionBoundary.countDown()
                awaitRelease(releaseQuestionBoundary, token)
                ModelReply("", listOf(ModelReply.Call("after-question-board-read", "board_read",
                    mapOf("path" to "/board/task.md"))))
            } else ModelReply("Initial worker task completed.", emptyList())
        }

        val runtime = CoreAgentRuntime(context, scope.executionSessionId, emptyList(), CorePromptBudget.standard())
        val manager = runtime.configureCrewManager(runtime.createTools(),
            CoreAgentModel(context, provider, scope.executionSessionId), null)
        managers += manager
        val requestId = "request-$key"
        val prepared = registry.prepareAssignment(scope.workspaceId, "assignment-$key", requestId,
            "Owner request", "coding", mission, listOf("board_read", "msg_send"), "Reviewer",
            CrewMissionAccess.STANDARD)
        val started = registry.markStarted(scope.workspaceId, prepared.assignmentId, prepared.revision, "process-$key")
        val bot = manager.spawnForMissionDeferred(prepared.missionId, "Owner request", "coding", mission,
            listOf("board_read", "msg_send"), "Reviewer", CrewMissionAccess.STANDARD, null,
            started)
        val assignment = registry.bindBot(scope.workspaceId, prepared.assignmentId, started.revision, bot.id)
        manager.startPreparedForMission(bot)
        val workerThread = CrewManager.Bot::class.java.getDeclaredField("thread").apply { isAccessible = true }
            .get(bot) as? Thread
        assertTrue(
            "the worker is parked inside a live model turn after its question; " +
                "status=${bot.status()} error=${bot.error()} initialTask=${initialTaskRead.get()} " +
                "questionSent=${workerQuestionSent.get()} providerCalls=${prompts.size} " +
                "thread=${workerThread?.name}:${workerThread?.state} stack=${workerThread?.stackTrace?.take(30)}",
            questionBoundary.await(60, TimeUnit.SECONDS))
        assertEquals(CrewManager.Status.RUNNING, bot.status())
        assertTrue(initialTaskRead.get())
        assertTrue(workerQuestionSent.get())
        assertEquals(bot.id, taskBotId.get())
        assertTrue(prompts.any { it.contains("SUBORDINATE and PEER messages") })

        val store = LocalRunStore(context)
        val centralQuestion = chatTestStore.onDatabaseWorker {
            store.readConversationMessages(WorkspaceIdentity.CENTRAL_SESSION).single { row ->
                row.optString("role") == "agent" &&
                    row.optJSONObject("provenance")?.optString("kind") == "QUESTION"
            }
        }
        val questionProvenance = centralQuestion.getJSONObject("provenance")
        val workerQuestion = questionProvenance.getString("messageId")
        val referenceId = questionProvenance.getString("referenceId")
        val questionHash = questionProvenance.getString("contentSha256")
        assertEquals(ConversationInput.Provenance.PrincipalRole.SUBORDINATE.name,
            questionProvenance.getString("principalRole"))
        assertEquals(scope.executionSessionId, questionProvenance.getString("sessionId"))
        assertEquals(scope.workspaceId, questionProvenance.getString("workspaceId"))
        assertEquals(assignment.assignmentId, questionProvenance.getString("assignmentId"))
        assertEquals(bot.id, questionProvenance.getString("botId"))
        assertEquals(workerQuestion, questionProvenance.getString("correlationMessageId"))
        assertEquals(assignment.requestId, questionProvenance.getString("requestId"))
        assertEquals(assignment.missionId, questionProvenance.getString("missionId"))
        assertTrue(referenceId.isNotBlank())
        assertTrue(questionHash.isNotBlank())
        assertEquals(assignment.revision, questionProvenance.getLong("assignmentRevision"))
        WorkspaceCoordinationDelivery(context).reconcile(scope.workspaceId, CancellationToken.uncancellable())

        val backend = WorkspaceCoordinator(context, { session ->
            assertEquals(scope.executionSessionId, session)
            manager
        }, {})
        val tool = CentralWorkspaceTool(WorkspaceIdentity.CENTRAL_SESSION, "central-turn-$key",
            "Owner request", backend)
        val messageArgs = linkedMapOf<String, Any>(
            "action" to "message", "workspace_id" to scope.workspaceId,
            "assignment_id" to assignment.assignmentId, "bot_id" to bot.id,
            "expected_assignment_revision" to assignment.revision,
            "key" to "supervisor-message-$key", "text" to "Continue the assigned review and verify the task note.",
            "correlation_message_id" to referenceId,
        )
        val accepted = tool.execute(messageArgs, CancellationToken.uncancellable())
        assertTrue(accepted.content, accepted.success)
        val receipt = JSONObject(accepted.content).getJSONObject("data")
        assertEquals("PENDING_INCORPORATION", receipt.getString("state"))
        assertFalse(receipt.getBoolean("incorporated_in_worker_checkpoint"))
        assertTrue(receipt.getBoolean("admission_committed"))

        val duplicate = tool.execute(messageArgs, CancellationToken.uncancellable())
        assertTrue("${duplicate.content}; worker=${bot.status()} error=${bot.error()} " +
            "pending=${manager.messageBus().pending(bot.id).map { it.id }}",
            duplicate.success)
        assertEquals(receipt.getString("worker_input_id"),
            JSONObject(duplicate.content).getJSONObject("data").getString("worker_input_id"))
        val conflict = tool.execute(messageArgs + ("text" to "Changed payload under the same stable key."),
            CancellationToken.uncancellable())
        assertFalse("changed stable-key payload must be rejected", conflict.success)

        releaseQuestionBoundary.countDown()
        val followupObserved = followupEntered.await(10, TimeUnit.SECONDS)
        if (!followupObserved) {
            val pendingMailbox = manager.messageBus().pending(bot.id).map { "${it.type}:${it.id}:${it.text}" }
            val retainedInputs = bot.checkpoint().appliedIncomingIds
            val transcript = runCatching {
                CrewManager.Bot::class.java.getDeclaredField("transcript").apply { isAccessible = true }
                    .get(bot)
            }.getOrNull()
            fail("the live worker consumes the supervisor input at its next boundary; " +
                "status=${bot.status()} error=${bot.error()} pendingMailbox=$pendingMailbox " +
                "appliedIncomingIds=$retainedInputs messageTurn=${messageTurn.get()} " +
                "doneFollowup=${doneFollowupTurn.get()} providerCalls=${prompts.size} " +
                "transcript=$transcript thread=${workerThread?.name}:${workerThread?.state} " +
                "stack=${workerThread?.stackTrace?.take(30)}")
        }

        val expectedInput = messageTurn.get()
        assertNotNull(expectedInput)
        assertEquals("user", expectedInput.providerMessageRole())
        assertEquals(ConversationInput.Origin.AGENT, expectedInput.incomingMetadata.origin)
        assertTrue(expectedInput.incomingMetadata.untrusted)
        val provenance = expectedInput.incomingMetadata.provenance
        assertEquals(ConversationInput.Provenance.PrincipalRole.SUPERVISOR, provenance.principalRole)
        assertEquals(WorkspaceIdentity.CENTRAL_SESSION, provenance.sessionId)
        assertEquals(scope.workspaceId, provenance.workspaceId)
        assertEquals(assignment.assignmentId, provenance.assignmentId)
        assertEquals(bot.id, provenance.botId)
        assertEquals(workerQuestion, provenance.correlationMessageId)
        assertEquals(referenceId, provenance.referenceId)
        assertEquals(questionHash, provenance.contentSha256)
        assertEquals(assignment.revision, provenance.assignmentRevision)
        assertFalse("AGENT message text cannot become a user-authored Room message",
            chatTestStore.onDatabaseWorker {
                store.readConversationMessages(scope.executionSessionId).any {
                    it.optString("role") == "user" && it.optString("content") == messageArgs["text"]
                }
            })

        releaseFollowup.countDown()
        awaitBotQuiescent(bot, "worker after supervisor steering")
        assertEquals(CrewManager.Status.DONE, bot.status())
        assertTrue(bot.checkpoint().appliedIncomingIds.contains(expectedInput.durableMessageId))
        val incorporated = chatTestStore.onDatabaseWorker {
            val stableId = ConversationInput(scope.executionSessionId, expectedInput.durableMessageId,
                expectedInput.content, ConversationInput.Origin.AGENT,
                expectedInput.incomingMetadata.provenance).stableId()
            val row = store.readConversationMessageByStableId(scope.executionSessionId, stableId)!!
            store.readConversationInputState(scope.executionSessionId, row.getString("messageId"))
        }
        assertEquals("incorporated", incorporated)

        val doneMessageArgs = (messageArgs - "correlation_message_id") + mapOf(
            "key" to "done-followup-$key", "text" to "Check the same assigned note once more.")
        val doneAccepted = tool.execute(doneMessageArgs, CancellationToken.uncancellable())
        assertTrue(doneAccepted.content, doneAccepted.success)
        assertTrue("a DONE worker accepts a same-assignment follow-up",
            doneFollowupEntered.await(10, TimeUnit.SECONDS))
        awaitBotQuiescent(bot, "worker after DONE follow-up")
        val doneInput = doneFollowupTurn.get()
        assertNotNull(doneInput)
        assertEquals(ConversationInput.Provenance.PrincipalRole.SUPERVISOR,
            doneInput.incomingMetadata.provenance.principalRole)
        assertEquals(bot.id, doneInput.incomingMetadata.provenance.botId)
        assertEquals("", doneInput.incomingMetadata.provenance.referenceId)
        val doneInputState = chatTestStore.onDatabaseWorker {
            val stableId = ConversationInput(scope.executionSessionId, doneInput.durableMessageId,
                doneInput.content, ConversationInput.Origin.AGENT,
                doneInput.incomingMetadata.provenance).stableId()
            val row = store.readConversationMessageByStableId(scope.executionSessionId, stableId)!!
            store.readConversationInputState(scope.executionSessionId, row.getString("messageId"))
        }
        assertEquals("incorporated", doneInputState)
        registry.close()
    }

    @Test fun boardPostWritesSharedBoardWithoutDeliveringFindingToCentral() {
        val key = UUID.randomUUID().toString()
        val registry = WorkspaceRegistry(context)
        val scope = registry.createWorkspace("board-post-$key", "Local board note")
        assertTrue(ChatDatabaseBridge.getInstance(context)
            .ensureConversationSync(scope.executionSessionId, "workspace") is ChatDbResult.Success)
        assertTrue(ChatDatabaseBridge.getInstance(context)
            .ensureConversationSync(WorkspaceIdentity.CENTRAL_SESSION, "chat") is ChatDbResult.Success)
        val posted = AtomicBoolean()
        val provider = Provider { systemPrompt, history, _, sessionId, _ ->
            assertEquals(scope.executionSessionId, sessionId)
            if (posted.compareAndSet(false, true)) {
                assertTrue(systemPrompt.contains("SUPERVISOR"))
                assertTrue(history.any { it.incomingMetadata?.type == "TASK" })
                ModelReply("", listOf(ModelReply.Call("board-post", "board_post", mapOf(
                    "path" to "/board/finding.md", "content" to "The source note is on page 4."))))
            } else ModelReply("The finding is saved on the shared board.", emptyList())
        }
        val runtime = CoreAgentRuntime(context, scope.executionSessionId, emptyList(),
            CorePromptBudget.standard())
        val manager = runtime.configureCrewManager(runtime.createTools(),
            CoreAgentModel(context, provider, scope.executionSessionId), null)
        managers += manager
        val requestId = "request-$key"
        val mission = "Record a local finding on the shared board."
        val prepared = registry.prepareAssignment(scope.workspaceId, "assignment-$key", requestId,
            "Owner request", "coding", mission, listOf("board_post"), "Reporter", CrewMissionAccess.STANDARD)
        val started = registry.markStarted(scope.workspaceId, prepared.assignmentId, prepared.revision, "process-$key")
        val bot = manager.spawnForMissionDeferred(prepared.missionId, "Owner request", "coding", mission,
            listOf("board_post"), "Reporter", CrewMissionAccess.STANDARD, null, started)
        registry.bindBot(scope.workspaceId, prepared.assignmentId, started.revision, bot.id)
        manager.startPreparedForMission(bot)
        awaitBotQuiescent(bot, "worker after board post")

        assertTrue(posted.get())
        assertEquals("The source note is on page 4.", CrewBoard(WorkspaceStore.forCrewBoard(context,
            scope.executionSessionId)).read("/board/finding.md"))
        val centralInputs = chatTestStore.onDatabaseWorker {
            LocalRunStore(context).readConversationMessages(WorkspaceIdentity.CENTRAL_SESSION)
        }.filter { it.optString("role") == "agent" }
        assertTrue("board_post must not create a chief-directed FINDING input",
            centralInputs.none { it.optJSONObject("provenance")?.optString("kind") == "FINDING" })
        assertTrue("board note content remains on the shared board",
            centralInputs.none { it.optString("content").contains("The source note is on page 4.") })
        registry.close()
    }

    private class Provider(
        private val respond: (String, List<ConversationTurn>, List<ToolSpec>, String, CancellationToken) -> ModelReply,
    ) : ModelProviderClient {
        override fun complete(systemPrompt: String, userPrompt: String, images: List<com.jarvys.agent.device.ScreenData>,
                              tools: List<ToolSpec>, sessionId: String, token: CancellationToken) =
            ModelReply("unexpected non-conversation request", emptyList())

        override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                                          images: List<com.jarvys.agent.device.ScreenData>, tools: List<ToolSpec>,
                                          sessionId: String, token: CancellationToken): ModelReply =
            respond(systemPrompt, history, tools, sessionId, token)
    }

    private fun awaitRelease(release: CountDownLatch, token: CancellationToken) {
        while (release.count > 0) {
            token.throwIfCancelled()
            try {
                release.await(50, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                token.throwIfCancelled()
                throw IllegalStateException("Unexpected provider interruption", interrupted)
            }
        }
    }

    private fun awaitBotQuiescent(bot: CrewManager.Bot, label: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!bot.isQuiescent && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("$label did not finish: status=${bot.status()} error=${bot.error()}", bot.isQuiescent)
    }

    private class IsolatedWorkspaceContext(base: Context, private val files: File) : ContextWrapper(base) {
        init { check(files.isDirectory || files.mkdirs()) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
    }
}
