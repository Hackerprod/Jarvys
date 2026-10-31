package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.AppendMessageInput
import com.jarvys.agent.chatdb.MessagePartInput
import com.jarvys.agent.chatdb.ChatDbResult
import com.jarvys.agent.chatdb.ConnectionProjectionRepository
import com.jarvys.agent.chatdb.CodingJobSemanticRepository
import com.jarvys.agent.coding.CodingJobEvidence
import com.jarvys.agent.coding.CodingJobEvidenceCodec
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.JarvysChatDatabaseProvider
import com.jarvys.agent.chatdb.chatSql
import com.jarvys.agent.chatdb.withChatWriteTransaction
import com.jarvys.agent.device.ScreenData
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Persisted-state consumer fixtures, not claims of producer/commit authority. No model/network IO. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CodingEpisodeConsumerTest {
    @get:Rule val fixture = ChatTestStore()
    private lateinit var context: Context
    private lateinit var bridge: ChatDatabaseBridge
    private val central = WorkspaceIdentity.CENTRAL_SESSION

    @Before fun setup() {
        val files = fixture.newFilesDirectory()
        fixture.localRunStore(files)
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        bridge = ChatDatabaseBridge.getInstance(context)
        bridge.ensureConversationSync(central)
        MemoryWritePolicy.forContext(context).setEnabledBlocking(true)
    }

    private val roots = mutableMapOf<String, CentralInvocationSource>()
    private fun episode(suffix: String = ""): String {
        val source = (bridge.appendMessageSync(AppendMessageInput(central, "user",
            listOf(MessagePartInput("text", "Synthetic request$suffix")))) as ChatDbResult.Success).value.messageId
        val generation = runBlocking {
            val db = JarvysChatDatabaseProvider.getInstance(context)
            db.withChatWriteTransaction {
                db.chatSql.execSQL("INSERT OR IGNORE INTO storage_meta(key,value) VALUES(?,?)",
                    arrayOf(ConnectionProjectionRepository.GENERATION_KEY, UUID.randomUUID().toString()))
                db.chatSql.query("SELECT value FROM storage_meta WHERE key=?",
                    arrayOf(ConnectionProjectionRepository.GENERATION_KEY)).use { check(it.moveToFirst()); it.getString(0) }
            }
        }
        val batch = "synthetic-batch$suffix"
        val call = "synthetic-call$suffix"
        val run = "synthetic-run$suffix"
        val process = UUID.randomUUID().toString()
        val id = bridge.personalAgentRecordEpisodeToolStartedSync(central, source, call,
            "synthetic_write", null, "allow", "{}", "started",
            sourceBatchId = batch, runId = run, processId = process)
        bridge.personalAgentFinishEpisodeToolEvidenceSync(central, call, "ok", "{}", sourceBatchId = batch, runId = run)
        roots[id] = CentralInvocationSource(generation, central, source, batch, call, run, process)
        return id
    }
    private fun link(entry: String, terminal: Boolean, status: String = "PENDING"): String = runBlocking {
        val job = UUID.randomUUID().toString()
        val root = requireNotNull(roots[entry])
        val source = CodingJobEvidence.Source(CodingJobEvidence.CrewSource("synthetic-execution", "bot", "mission", "cycle",
            "synthetic-scope", "0".repeat(64), "synthetic-coding-call", "project_exec",
            CrewInputContext.resolve(emptyList(), emptyList())),
            CodingJobEvidence.WorkspaceBinding("workspace", "assignment", "request"), root)
        val binding = CodingJobEvidence.Binding(root.storageGeneration, job, UUID.randomUUID().toString(), "owner", "scope",
            "synthetic-scope", UUID.randomUUID().toString(), UUID.randomUUID().toString(), source,
            CodingJobEvidence.SemanticCapture.ENABLED)
        val encoded = CodingJobEvidenceCodec.encode(CodingJobEvidence.Record(binding, 1,
            CodingJobEvidence.Phase.SUBMISSION_INTENT, false, null, null))
        val db = JarvysChatDatabaseProvider.getInstance(context)
        db.withChatWriteTransaction {
            db.chatSql.execSQL("INSERT INTO coding_job_bindings(job_id,submission_id,technical_generation,binding_json) VALUES(?,?,?,?)",
                arrayOf(job, binding.submissionId, binding.technicalStoreGeneration, encoded))
            CodingJobSemanticRepository(db).capture(binding)
            db.chatSql.execSQL("UPDATE coding_job_semantic_links SET status=?,admitted=1,terminal_confirmed=? WHERE job_id=?",
                arrayOf(status, if (terminal) 1 else 0, job))
            db.chatSql.query("SELECT target_entry_id FROM coding_job_semantic_links WHERE job_id=?", arrayOf(job)).use {
                assertTrue(it.moveToFirst()); assertEquals(entry, it.getString(0))
            }
        }
        job
    }
    private fun narrator(block: (List<ConversationTurn>, String) -> Unit): EpisodeNarrator {
        val provider = object : ModelProviderClient {
            override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>,
                tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply = error("Conversation mock required")
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply {
                block(history, userPrompt)
                return ModelReply("""{"title":"Synthetic record","narration":"Only the visible local evidence is described."}""", emptyList())
            }
        }
        return EpisodeNarrator(context, central, CoreAgentModel(provider, "synthetic-narrator"))
    }

    @Test fun pendingTechnicalJobBlocksNarrationEvenWhenSemanticStatusIsSuppressed() {
        val id = episode()
        link(id, false, "SUPPRESSED")
        assertTrue(bridge.personalAgentPendingEpisodeNarrationsSync(central).isEmpty())
        assertTrue(narrator { _, _ -> error("A pending job must block the model") }.narratePending(CancellationToken.cancellable()))
    }

    @Test fun pendingLinkBlocksOnlyItsCapturedTargetNotAnotherEpisode() {
        val captured = episode("-captured")
        val unrelated = episode("-unrelated")
        link(captured, false)
        assertEquals(listOf(unrelated), bridge.personalAgentPendingEpisodeNarrationsSync(central).map { it.id })
        bridge.personalAgentForget(captured)
        assertEquals(listOf(unrelated), bridge.personalAgentPendingEpisodeNarrationsSync(central).map { it.id })
        assertNull(bridge.personalAgentEpisodeNarrationInputSync(captured))
    }

    @Test fun offDuringModelResponseCannotCommitNarrationAfterReenable() {
        val id = episode()
        link(id, true, "SUPPRESSED")
        var calls = 0
        assertThrows(MemoryWriteGate.RejectedException::class.java) {
            narrator { _, _ ->
                calls++
                MemoryWritePolicy.forContext(context).setEnabledBlocking(false)
                MemoryWritePolicy.forContext(context).setEnabledBlocking(true)
            }.narratePending(CancellationToken.cancellable())
        }
        assertEquals(1, calls)
        assertNotEquals("Synthetic record", bridge.personalAgentEpisodeNarrationInputSync(id)!!.title)
    }

    @Test fun changedSourceIdentityIsNotBlockedByOldPendingTarget() {
        val id = episode()
        val job = link(id, false)
        assertTrue(bridge.personalAgentPendingEpisodeNarrationsSync(central).isEmpty())
        runBlocking {
            val db = JarvysChatDatabaseProvider.getInstance(context)
            db.withChatWriteTransaction {
                db.chatSql.execSQL("UPDATE message_parts SET payload=? WHERE conversation_id=? AND message_id=? AND kind='text'",
                    arrayOf("User changed the request", central, roots.getValue(id).sourceMessageId))
                db.chatSql.query("SELECT terminal_confirmed FROM coding_job_semantic_links WHERE job_id=?", arrayOf(job)).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
            }
        }
        assertEquals(listOf(id), bridge.personalAgentPendingEpisodeNarrationsSync(central).map { it.id })
        assertTrue(bridge.personalAgentEpisodeNarrationInputSync(id)!!.codingJobs.isEmpty())
    }

    @Test fun confirmedSuppressedTerminalUnblocksOnlyVisibleEvidenceWithOmissionMarker() {
        val id = episode()
        link(id, true, "SUPPRESSED")
        var calls = 0
        assertTrue(narrator { history, prompt ->
            calls++
            val payload = JSONObject(history.single().content)
            assertEquals(1, payload.getInt("coding_jobs_suppressed"))
            assertTrue(prompt.contains("never infer overall mission or goal completion"))
            assertFalse(payload.has("coding_job_count"))
        }.narratePending(CancellationToken.cancellable()))
        assertEquals(1, calls)
    }

    @Test fun newPendingJobDuringModelResponseRejectsNarrationCommit() {
        val id = episode()
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            narrator { _, _ -> calls++; link(id, false) }.narratePending(CancellationToken.cancellable())
        }
        assertEquals(1, calls)
        assertTrue(bridge.personalAgentPendingEpisodeNarrationsSync(central).isEmpty())
        assertNotEquals("Synthetic record", bridge.personalAgentEpisodeNarrationInputSync(id)!!.title)
    }

    @Test fun memoryGetAndNarratorConsumeCodingRoleWithoutInventingMissingSource() {
        val id = episode()
        val job = UUID.randomUUID().toString()
        runBlocking {
            val db = JarvysChatDatabaseProvider.getInstance(context)
            db.withChatWriteTransaction {
                db.chatSql.execSQL("UPDATE memory_evidence SET ref_kind='coding_job_execution',ref_id=?,tool_name='project_exec',facts_json=? WHERE entry_id=?",
                    arrayOf(job, JSONObject().put("job_id", job).put("goal_verified", false).toString(), id))
            }
        }
        val get = PersonalMemoryTools.create(context, "", central, CorePromptBudget.standard().toolResultsPerTurnChars)
            .single { it.declaration().name == "memory_get" }
        val result = get.execute(mapOf("ids" to listOf(id), "expand" to "source"), CancellationToken.cancellable())
        assertTrue(result.success)
        val source = org.json.JSONArray(result.content).getJSONObject(0).getJSONArray("evidence")
            .getJSONObject(0).getJSONObject("source")
        assertEquals("unavailable", source.getString("state"))
        var calls = 0
        assertTrue(narrator { history, prompt ->
            calls++
            val payload = JSONObject(history.single().content)
            assertEquals(1, payload.getInt("coding_job_count"))
            assertFalse(payload.getBoolean("coding_goal_verified"))
            assertEquals("unavailable", payload.getJSONArray("evidence").getJSONObject(0)
                .getJSONObject("source").getString("state"))
            assertTrue(prompt.contains("not proof that a build, test suite or user goal succeeded"))
        }.narratePending(CancellationToken.cancellable()))
        assertEquals(1, calls)
    }

    @Test fun codingEvidenceSourceWithoutExactLiveLinkIsExplicitlyUnavailable() {
        val id = episode()
        assertEquals("unavailable", bridge.personalAgentCodingJobSourceSync(id, UUID.randomUUID().toString(), 1024).getString("state"))
        bridge.personalAgentForget(id)
        assertEquals("unavailable", bridge.personalAgentCodingJobSourceSync(id, UUID.randomUUID().toString(), 1024).getString("state"))
    }
}
