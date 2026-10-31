package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.CompactionInput
import com.jarvys.agent.chatdb.CompactionCoverage
import com.jarvys.agent.chatdb.PreparedCompactionFlush
import com.jarvys.agent.chatdb.ModelToolBatchInput
import com.jarvys.agent.chatdb.ModelCallInput
import com.jarvys.agent.chatdb.ModelToolResultInput
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

/** Explicit persisted metadata fixtures only: these rows are NOT journal/issuer/producer proofs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CodingJobSemanticSourceTest {
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
        assertTrue(bridge.appendToolBatchSync(ModelToolBatchInput(central, batch, source, 0,
            "Synthetic batch", calls = listOf(ModelCallInput(call, "synthetic_write", "{}")))) is ChatDbResult.Success)
        val id = bridge.personalAgentRecordEpisodeToolStartedSync(central, source, call,
            "synthetic_write", null, "allow", "{}", "started",
            sourceBatchId = batch, runId = run, processId = process)
        bridge.personalAgentFinishEpisodeToolEvidenceSync(central, call, "ok", "{}", sourceBatchId = batch, runId = run)
        assertTrue(bridge.appendToolResultSync(ModelToolResultInput(central, batch, call, "synthetic_write", "RESULT", "Synthetic result")) is ChatDbResult.Success)
        roots[id] = CentralInvocationSource(generation, central, source, batch, call, run, process)
        return id
    }
    private fun link(entry: String, terminal: Boolean = true, status: String = "LINKED"): String = runBlocking {
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

    private fun retained(): Pair<String, String> {
        val entry = episode()
        val job = link(entry)
        mutate { db ->
            val encoded = db.chatSql.query("SELECT binding_json FROM coding_job_bindings WHERE job_id=?", arrayOf(job))
                .use { check(it.moveToFirst()); it.getString(0) }
            val binding = CodingJobEvidenceCodec.decode(encoded, encoded.length).record.binding
            val record = CodingJobEvidence.Record(binding, 2, CodingJobEvidence.Phase.SUBMISSION_INTENT, true, null,
                CodingJobEvidence.TerminalFacts(CodingJobEvidence.TerminalState.NOT_STARTED, null, false, false, false,
                    CodingJobEvidence.AbsenceCoverage.NO_PREPARATION_OR_DISPATCH))
            val terminal = CodingJobEvidenceCodec.encode(record)
            db.chatSql.execSQL("INSERT INTO coding_job_observations(job_id,revision,observation_kind,evidence_json,payload_sha256,payload_bytes) VALUES(?,2,'JOURNAL_PROJECTION',?,?,?)",
                arrayOf(job, terminal, "0".repeat(64), byteArrayOf(1)))
            val ordinal = db.chatSql.query("SELECT COALESCE(MAX(ordinal),-1)+1 FROM memory_evidence WHERE entry_id=?", arrayOf(entry))
                .use { check(it.moveToFirst()); it.getLong(0) }
            listOf("coding_job_submission", "coding_job_execution").forEachIndexed { index, role ->
                db.chatSql.execSQL("INSERT INTO memory_evidence(entry_id,ordinal,ref_kind,ref_conversation_id,ref_id,at_ms,tool_name,outcome,facts_json) VALUES(?,?,?,?,?,1,'project_exec',?,'{}')",
                    arrayOf(entry, ordinal + index, role, binding.source.crew.executionSessionId, job, if(index==0) null else "not_executed"))
            }
            db.chatSql.execSQL("UPDATE coding_job_semantic_links SET submission_ordinal=?,execution_ordinal=? WHERE job_id=?",
                arrayOf(ordinal, ordinal+1, job))
        }
        assertEquals("retained", state(entry, job))
        return entry to job
    }
    private fun mutate(block: (com.jarvys.agent.chatdb.JarvysChatDatabase) -> Unit) = runBlocking {
        val db = JarvysChatDatabaseProvider.getInstance(context)
        db.withChatWriteTransaction { block(db) }
    }
    private fun state(entry: String, job: String) = bridge.personalAgentCodingJobSourceSync(entry, job, 12000).getString("state")

    @Test fun automaticNarrationKeepsExactHistoricalSource() {
        val (entry, job) = retained()
        bridge.personalAgentSetEpisodeNarrationSync(entry, "done", "Narrated title", "Narrated facts")
        val source = bridge.personalAgentCodingJobSourceSync(entry, job, 12000)
        assertEquals("retained", source.getString("state"))
        assertEquals("not_executed", source.getString("outcome"))
        assertFalse(source.getBoolean("goal_verified"))
        assertEquals(job, source.getString("job_id"))
    }
    @Test fun explicitMemoryRevisionInvalidatesSourceWithoutErasingTechnicalHistory() {
        val (entry, job) = retained()
        mutate { db ->
            db.chatSql.execSQL("UPDATE memory_entries SET text='Corrected by user' WHERE id=?", arrayOf(entry))
            db.chatSql.execSQL("INSERT INTO memory_revisions(id,target_type,target_id,before_content,after_content,actor,cause,created_at) VALUES(?,'memory',?,'Old','Corrected by user','user','correct',1)", arrayOf(UUID.randomUUID().toString(), entry))
        }
        assertEquals("unavailable", state(entry, job))
        mutate { db -> db.chatSql.query("SELECT COUNT(*) FROM coding_job_observations WHERE job_id=?", arrayOf(job)).use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        } }
    }
    @Test fun editedSourceMessageInvalidatesSource() {
        val (entry, job) = retained()
        mutate { db -> db.chatSql.execSQL("UPDATE message_parts SET payload='Changed source' WHERE conversation_id=? AND message_id=? AND kind='text'",
            arrayOf(central, roots.getValue(entry).sourceMessageId)) }
        assertEquals("unavailable", state(entry, job))
    }
    @Test fun replacementDestinationCannotAdoptHistoricalTechnicalRecord() {
        val (entry, job) = retained()
        mutate { db -> db.chatSql.execSQL("UPDATE storage_meta SET value=? WHERE key=?",
            arrayOf(UUID.randomUUID().toString(), ConnectionProjectionRepository.GENERATION_KEY)) }
        assertEquals("unavailable", state(entry, job))
    }
    @Test fun changedBindingCannotBeInferredFromLatestObservation() {
        val (entry, job) = retained()
        mutate { db ->
            val encoded = db.chatSql.query("SELECT binding_json FROM coding_job_bindings WHERE job_id=?", arrayOf(job))
                .use { check(it.moveToFirst()); it.getString(0) }
            val changed = JSONObject(encoded)
            changed.getJSONObject("binding").put("owner", "different-owner")
            db.chatSql.execSQL("UPDATE coding_job_bindings SET binding_json=? WHERE job_id=?", arrayOf(changed.toString(), job))
        }
        assertEquals("unavailable", state(entry, job))
    }
    @Test fun wrongRoleOrdinalDoesNotExposeSourceByJobIdAlone() {
        val (entry, job) = retained()
        mutate { db -> db.chatSql.execSQL("UPDATE coding_job_semantic_links SET execution_ordinal=submission_ordinal WHERE job_id=?", arrayOf(job)) }
        assertEquals("unavailable", state(entry, job))
    }
}
