package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.coding.*
import com.jarvys.agent.crew.*
import com.jarvys.agent.device.ScreenData
import com.jarvys.agent.linux.*
import com.jarvys.agent.workspaces.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Natural Central→assignment→Crew→instrumented job→semantic target. Models/backend are local fakes.
 * Uses the existing instrumented manager seam, NOT opt-in of the production Coding tool catalog.
 * No SQL episode/link insertion, constructed root, fabricated witness, or decoded Commit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CodingNaturalEpisodeTest {
    @get:Rule val chat = ChatTestStore()
    private val central = WorkspaceIdentity.CENTRAL_SESSION

    private inner class Scenario : AutoCloseable {
        val files = chat.newFilesDirectory().also { chat.localRunStore(it) }
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        val bridge = ChatDatabaseBridge.getInstance(context)
        val store = LocalRunStore(context)
        val policy = MemoryWritePolicy.forContext(context)
        val previousMemoryEnabled = policy.isEnabled()
        val closed = AtomicBoolean()
        val jobToken = AtomicReference<CancellationToken>()
        val queue = ConcurrentLinkedQueue<Runnable>()
        val accepted = AtomicReference<CodingJobAcceptance>()
        val failure = AtomicReference<Throwable>()
        val submitted = CountDownLatch(1)
        val backendCalls = AtomicInteger()
        val managers = ConcurrentHashMap<String, CrewManager>()
        val previousCrewMode = CrewMode.read(context)
        val jobs = CodingJobManager(files, CodingJobManager.Backend { _, _, _, _, _, _, beforeLaunch ->
            backendCalls.incrementAndGet()
            beforeLaunch()
            LinuxExecResult(0)
        }, executor = Executor { queue.add(it) })
        lateinit var ticket: MemoryWriteGate.Ticket
        lateinit var ownerMessage: String
        lateinit var entry: String

        init {
            try {
            context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
            CrewMode.write(context, CrewMode.AUTO)
            policy.setEnabledBlocking(true)
            ticket = policy.captureTicket()
            val coordinator = WorkspaceCoordinator(context, { session -> managers.computeIfAbsent(session, ::crew) }, {})
            ownerMessage = store.appendConversationMessage(central, "user", "Run the synthetic local Coding operation")
            val run = UUID.randomUUID().toString()
            val transcript = MainChatTranscriptStore(files, central, store)
            val centralTool = CentralWorkspaceTool(context, central, ownerMessage,
                "Run the synthetic local Coding operation", coordinator)
            val registry = CoreToolRegistry(listOf(centralTool))
                .withInvocationSourceProvider(transcript.centralSourceProvider(ownerMessage, run))
                .withInvocationObserver(EpisodeRecorder(context, central, ownerMessage, run, transcript))
            val turns = AtomicInteger()
            val loop = CoreAgentLoop({ _, _, _, _ ->
                when (turns.getAndIncrement()) {
                    0 -> ModelReply("", listOf(ModelReply.Call("natural-create", CentralWorkspaceTool.NAME,
                        mapOf("action" to "create", "key" to "natural-workspace", "title" to "Synthetic workspace"))))
                    1 -> {
                        val workspace = WorkspaceRegistry(context).use { it.listWorkspaces().single() }
                        ModelReply("", listOf(ModelReply.Call("natural-delegate", CentralWorkspaceTool.NAME,
                            mapOf("action" to "delegate", "workspace_id" to workspace.workspaceId,
                                "key" to "natural-assignment", "role" to "coding", "mission" to "Synthetic local operation",
                                "tools" to listOf("project_exec", "project_jobs")))))
                    }
                    else -> ModelReply("Assignment accepted; command completion is not established.", emptyList())
                }
            }, registry, "Use only the scripted local tools", central)
            transcript.attach(loop, emptyList(), ownerMessage, run)
            loop.run("Run synthetic operation", emptyList(), emptyList(), CancellationToken.cancellable(), null, ownerMessage, run)
            assertTrue("Real assignment did not reach instrumented Coding", submitted.await(30, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Instrumented worker failed", it) }
            val binding = requireNotNull(accepted.get()).reservation().binding()
            val root = requireNotNull(binding.source.rootCentral)
            assertEquals(ownerMessage, root.sourceMessageId)
            assertEquals("natural-delegate", root.sourceCallId)
            assertEquals(binding.technicalStoreGeneration, root.storageGeneration)
            val workspace = requireNotNull(binding.source.workspace)
            WorkspaceRegistry(context).use { persisted ->
                val assignment = requireNotNull(persisted.findBoundAssignment(workspace.workspaceId,
                    binding.source.crew.missionId, binding.source.crew.botId))
                assertEquals(root, assignment.centralSource)
                assertEquals(workspace.assignmentId, assignment.assignmentId)
            }
            entry = bridge.personalAgentEpisodesForSourceMessagesSync(central, listOf(ownerMessage)).single().id
            val input = requireNotNull(bridge.personalAgentEpisodeNarrationInputSync(entry))
            assertTrue(input.evidence.any { it.refKind == "tool_call" && it.refId == root.sourceCallId &&
                it.sourceBatchId == root.sourceBatchId && it.runId == root.sourceRunId })
            assertEquals(binding.jobId, input.codingJobs.single().jobId)
            assertTrue(input.codingJobs.single().admitted)
            assertFalse(input.codingJobs.single().terminalConfirmed)
            assertTrue(codingRows().isEmpty())
            assertEquals(0, backendCalls.get())
            assertEquals(1, queue.size)
            } catch (error: Throwable) {
                runCatching { close() }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }

        private fun crew(session: String): CrewManager {
            val probe = object : CoreTool {
                override fun declaration() = ToolSpec("project_exec", "test", "Instrumented manager seam", "test",
                    ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
                override fun effectClass() = CoreTool.EffectClass.WRITE
                override fun completionMode(arguments: Map<String, Any>) = CoreTool.CompletionMode.ASYNC_JOB
                override fun hasCompleteAsyncSubmissionCoverage() = true // Test seam only; production catalog remains closed.
                override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult = error("Explicit context required")
                override fun execute(arguments: Map<String, Any>, token: CancellationToken, progress: CoreTool.ProgressListener?,
                    invocation: EffectInvocation?): CoreToolResult {
                    try {
                        val current = requireNotNull(invocation)
                        jobToken.set(token)
                        val source = current.crewSource().source()
                        val scope = ProjectScopeStore(files).open(source.executionSessionId)
                        jobs.startInstrumented(scope, "${source.executionSessionId}/${source.botId}/1",
                            "synthetic-command", ".", scope.version(), null, token, current, bridge,
                            CodingJobEvidence.AuthorizationSnapshot(CodingJobEvidence.Authorization.ALLOW,
                                "native", "project_exec", "synthetic-revision", "local_fixture"), ticket)
                        accepted.set(requireNotNull(current.acceptedSubmission()))
                        return CoreToolResult.success("Durable submission accepted, not a command result")
                    } catch (error: Throwable) {
                        failure.set(error)
                        throw error
                    } finally { submitted.countDown() }
                }
            }
            val registry = CoreToolRegistry(listOf(probe, CodingSubmissionTestSource.projectJobsCompanion()))
            val role = CrewRole("coding", "Coding", "coding", "Synthetic local Coding worker",
                listOf("project_exec", "project_jobs"), null, "Local fixture profile", 1, emptyList(),
                CrewProfile.WorkspaceMode.CONVERSATION_PROJECT)
            val turns = AtomicInteger()
            return CrewManager(session, registry,
                { bot, manager -> CoreAgentRuntime.withCrewInvocationSource(registry, bot, manager) },
                { _, tools, incoming -> CoreAgentLoop({ _, _, _, _ ->
                    if (turns.getAndIncrement() == 0) ModelReply("", listOf(ModelReply.Call("natural-coding-call", "project_exec", emptyMap())))
                    else ModelReply("Submission acknowledged.", emptyList())
                }, tools, "Scripted local worker", session, CorePromptBudget.standard(), null,
                    CoreAgentLoop.ToolResultBudget.UNBOUNDED, incoming, null) }, null).also {
                it.configureProfiles({ role }, role.tools)
                it.configureCheckpoints(CrewCheckpointCoordinator(context, session) { bot -> bot.role })
            }
        }
        fun codingRows() = requireNotNull(bridge.personalAgentEpisodeNarrationInputSync(entry)).evidence
            .filter { it.refKind.startsWith("coding_job_") }
        fun finish() {
            val work = requireNotNull(queue.poll())
            work.run()
            work.run() // Duplicate runnable cannot create another command or role pair.
            assertEquals(1, backendCalls.get())
        }
        fun technicalTerminals(): Long = runBlocking {
            val db = JarvysChatDatabaseProvider.getInstance(context)
            JarvysChatDatabaseProvider.onQueryExecutor(db) { db.withChatReadTransaction {
                db.chatSql.query("SELECT evidence_json FROM coding_job_observations WHERE job_id=? ORDER BY revision DESC LIMIT 1",
                    arrayOf(accepted.get().reservation().binding().jobId)).use {
                    assertTrue(it.moveToFirst())
                    val encoded = it.getString(0)
                    val record = CodingJobEvidenceCodec.decode(encoded, encoded.length).record
                    assertEquals(CodingJobEvidence.TerminalState.SUCCEEDED, record.terminal.state)
                    record.revision
                }
            } }
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            var failure: Throwable? = null
            fun attempt(block: () -> Unit) {
                try { block() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            // A failed setup must never turn queued work into an accidental backend dispatch.
            attempt { jobToken.get()?.cancel() }
            while (true) {
                val work = queue.poll() ?: break
                attempt { work.run() }
            }
            attempt { jobs.requestQuiesce().get(10, TimeUnit.SECONDS) }
            managers.values.forEach { manager -> attempt { manager.close() } }
            attempt { policy.setEnabledBlocking(previousMemoryEnabled) }
            attempt { CrewMode.write(context, previousCrewMode) }
            attempt { context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME) }
            failure?.let { throw it }
        }
    }

    private fun narrator(s: Scenario, call: (List<ConversationTurn>, String) -> Unit): EpisodeNarrator {
        val provider = object : ModelProviderClient {
            override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>, tools: List<ToolSpec>,
                sessionId: String, token: CancellationToken): ModelReply = error("Only fake conversation boundary")
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply {
                call(history, userPrompt)
                return ModelReply("""{"title":"Synthetic process terminated","narration":"One process terminated with exit zero; goal completion is unverified."}""", emptyList())
            }
        }
        return EpisodeNarrator(s.context, central, CoreAgentModel(provider, "natural-episode-fixture"))
    }

    @Test fun naturalAssignmentCapturesExistingEpisodeAndTerminalNarrationKeepsExactSource() {
        Scenario().use { s ->
            assertTrue(s.bridge.personalAgentPendingEpisodeNarrationsSync(central).isEmpty())
            assertTrue(narrator(s) { _, _ -> error("Pending job cannot be narrated") }.narratePending(CancellationToken.cancellable()))
            s.finish(); assertTrue(s.technicalTerminals() > 1)
            val rows = s.codingRows()
            assertEquals(listOf("coding_job_submission", "coding_job_execution"), rows.map { it.refKind })
            assertEquals(1, rows.map { it.refId }.distinct().size)
            assertNull(rows.first().outcome)
            assertEquals("ok", rows.last().outcome)
            var calls = 0
            assertTrue(narrator(s) { history, _ ->
                calls++
                val payload = JSONObject(history.single().content)
                assertEquals(1, payload.getInt("coding_job_count"))
                assertFalse(payload.getBoolean("coding_goal_verified"))
            }.narratePending(CancellationToken.cancellable()))
            assertEquals(1, calls)
            val source = s.bridge.personalAgentCodingJobSourceSync(s.entry, rows.last().refId, 12000)
            assertEquals("retained", source.getString("state"))
            assertFalse(source.getBoolean("historical_root_is_exclusive_cause"))
            assertFalse(source.getBoolean("goal_verified"))
            assertEquals("ok", source.getString("outcome"))
        }
    }

    @Test fun offThenOnBeforeTerminalPreservesTechnicalFactsWithoutSemanticBackfill() {
        Scenario().use { s ->
            s.policy.setEnabledBlocking(false); s.policy.setEnabledBlocking(true)
            s.finish(); assertTrue(s.technicalTerminals() > 1)
            assertTrue(s.codingRows().isEmpty())
            assertEquals("SUPPRESSED", s.bridge.personalAgentEpisodeNarrationInputSync(s.entry)!!.codingJobs.single().status)
            val count = AtomicInteger()
            assertTrue(narrator(s) { history, _ ->
                count.incrementAndGet()
                assertEquals(1, JSONObject(history.single().content).getInt("coding_jobs_suppressed"))
            }.narratePending(CancellationToken.cancellable()))
            assertEquals(1, count.get())
            assertTrue(s.codingRows().isEmpty())
        }
    }

    @Test fun forgottenCapturedEpisodeCannotBeRecreatedByActualTerminalProjection() {
        Scenario().use { s ->
            s.bridge.personalAgentForget(s.entry)
            s.finish(); assertTrue(s.technicalTerminals() > 1)
            assertNull(s.bridge.personalAgentEpisodeNarrationInputSync(s.entry))
            assertTrue(s.bridge.personalAgentEpisodesForSourceMessagesSync(central, listOf(s.ownerMessage)).isEmpty())
            assertEquals("unavailable", s.bridge.personalAgentCodingJobSourceSync(s.entry,
                s.accepted.get().reservation().binding().jobId, 12000).getString("state"))
        }
    }
}
