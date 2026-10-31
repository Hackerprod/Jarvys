package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [ArtifactOsShadow::class])
class ContinuationGenerationReceiptTest {
    @get:Rule val storage = ChatTestStore()
    private lateinit var context: Context
    private lateinit var bridge: ChatDatabaseBridge
    private lateinit var inputId: String
    private val run = "continuation-run"
    private val stable = "continuation-assistant:continuation-run"

    @Before fun setup() {
        val files = storage.newFilesDirectory("continuation-receipt")
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
            override fun getDatabasePath(name: String): java.io.File = if (java.io.File(name).isAbsolute) java.io.File(name)
                else java.io.File(files, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        bridge = ChatDatabaseBridge.getInstance(context)
        storage.localRunStore(files)
        FreshStorageBootstrap.ensure(context)
        LocalRunStore(context).appendConversationMessage(WorkspaceIdentity.CENTRAL_SESSION, "user", "request")
        inputId = LocalRunStore(context).appendConversationInput(ConversationInput(WorkspaceIdentity.CENTRAL_SESSION,
            "receipt-input", "retained reference", ConversationInput.Origin.AGENT,
            ConversationInput.Provenance("worker-session", "", "", "", "receipt-input", ConversationInput.Kind.RESULT)))
    }

    private fun sql(statement: String) = runBlocking {
        val db = JarvysChatDatabaseProvider.getInstance(context)
        db.withChatWriteTransaction { db.chatSql.execSQL(statement) }
    }
    private fun rows(query: String): List<List<String?>> = runBlocking {
        val db = JarvysChatDatabaseProvider.getInstance(context)
        db.withChatWriteTransaction { db.chatSql.query(query).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
        } }
    }
    private fun snapshot() = listOf(rows("SELECT * FROM messages ORDER BY seq"),
        rows("SELECT * FROM message_parts ORDER BY message_id,ordinal"),
        rows("SELECT * FROM generation_run ORDER BY id"))
    private fun rejected(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }

    @Test fun missingOrHumanInputCannotOwnContinuationGeneration() {
        val before = snapshot()
        rejected { bridge.prepareContinuationGenerationSync(run, stable, "missing-input") }
        val user = rows("SELECT message_id FROM messages WHERE role='user'").single().single()!!
        rejected { bridge.prepareContinuationGenerationSync(run, stable, user) }
        assertEquals(before, snapshot())
    }

    @Test fun prepareRunInsertFailureRollsBackAssistantAndCanRetry() {
        val before = snapshot()
        sql("CREATE TRIGGER receipt_fail BEFORE INSERT ON generation_run BEGIN SELECT RAISE(ABORT,'synthetic run insert'); END")
        try { rejected { bridge.prepareContinuationGenerationSync(run, stable, inputId) }; assertEquals(before, snapshot()) }
        finally { sql("DROP TRIGGER receipt_fail") }
        assertEquals("requesting", bridge.prepareContinuationGenerationSync(run, stable, inputId).state)
        assertEquals(1, rows("SELECT id FROM generation_run").size)
        assertEquals(listOf(listOf(inputId)), rows("SELECT owner_user_message_id FROM messages WHERE role='assistant'"))
    }

    @Test fun prepareTransitionFailureRollsBackAllThreeWrites() {
        val before = snapshot()
        sql("CREATE TRIGGER receipt_fail BEFORE UPDATE ON generation_run BEGIN SELECT RAISE(ABORT,'synthetic prepare transition'); END")
        try { rejected { bridge.prepareContinuationGenerationSync(run, stable, inputId) }; assertEquals(before, snapshot()) }
        finally { sql("DROP TRIGGER receipt_fail") }
    }

    @Test fun finalTransitionFailureRollsBackAssistantBodyAndStatus() {
        bridge.prepareContinuationGenerationSync(run, stable, inputId)
        val before = snapshot()
        sql("CREATE TRIGGER receipt_fail BEFORE UPDATE ON generation_run BEGIN SELECT RAISE(ABORT,'synthetic finish transition'); END")
        try {
            rejected { bridge.finishContinuationGenerationSync(run, stable, "completed answer", "COMPLETED", 12) }
            assertEquals(before, snapshot())
        } finally { sql("DROP TRIGGER receipt_fail") }
        bridge.finishContinuationGenerationSync(run, stable, "completed answer", "COMPLETED", 12)
        assertEquals(listOf(listOf("completed", "2")), rows("SELECT state,state_revision FROM generation_run"))
    }

    @Test fun exactTerminalRetryIsReadOnlyAndConflictingCatchCannotOverwriteAnswer() {
        bridge.prepareContinuationGenerationSync(run, stable, inputId)
        val id = bridge.finishContinuationGenerationSync(run, stable, "answer", "COMPLETED", 12)
        val completed = snapshot()
        assertEquals(id, bridge.finishContinuationGenerationSync(run, stable, "answer", "COMPLETED", 12))
        assertEquals(completed, snapshot())
        rejected { bridge.finishContinuationGenerationSync(run, stable, "generic error", "FAILED", 0) }
        rejected { bridge.finishContinuationGenerationSync(run, stable, "other answer", "COMPLETED", 12) }
        rejected { bridge.finishContinuationGenerationSync(run, stable, "answer", "COMPLETED", 13) }
        rejected { bridge.finishContinuationGenerationSync("wrong-run", stable, "answer", "COMPLETED", 12) }
        rejected { bridge.prepareContinuationGenerationSync(run, stable, inputId) }
        assertEquals(completed, snapshot())
    }

    @Test fun unknownOutcomeAndMismatchedRunOwnerCannotMutatePreparedReceipt() {
        bridge.prepareContinuationGenerationSync(run, stable, inputId)
        val before = snapshot()
        rejected { bridge.finishContinuationGenerationSync(run, stable, "answer", "UNKNOWN", 0) }
        assertEquals(before, snapshot())
        sql("UPDATE generation_run SET message_id=(SELECT message_id FROM messages WHERE role='user' LIMIT 1) WHERE id='$run'")
        val mismatched = snapshot()
        rejected { bridge.finishContinuationGenerationSync(run, stable, "answer", "COMPLETED", 0) }
        assertEquals(mismatched, snapshot())
    }
}
