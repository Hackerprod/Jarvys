package com.jarvys.agent.chatdb

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Central notices and reply intents must be importable before their execution-ledger evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkspaceLedgerIndependenceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "jarvys-workspace-ledger-test").apply { isDaemon = true }
    }
    private lateinit var database: JarvysChatDatabase
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun openDatabase() {
        database = JarvysChatDatabaseProvider.openDatabaseFileForTests(
            context,
            File(temporary.root, "workspace-ledgers.db"),
        )
    }

    @After
    fun closeDatabase() {
        if (::database.isInitialized) JarvysChatDatabaseProvider.closeDatabaseFileForTests(database)
        executor.shutdownNow()
    }

    @Test
    fun centralRowsPrecedeExecutionEvidenceAndSurviveItsDeletion() = onDatabaseThread {
        val central = "central-ledger"
        val execution = "execution-ledger"
        database.conversationDao().insertIfNotTombstoned(Conversation(id = central, created_at = 1L, updated_at = 1L))
        database.conversationDao().insertIfNotTombstoned(Conversation(id = execution, created_at = 1L, updated_at = 1L))
        val sqlite = database.openHelper.writableDatabase
        (1L..5L).forEach { seq ->
            sqlite.execSQL(
                "INSERT INTO conversation_items(conversation_id,seq,item_kind,item_key,sort_seq,created_at) VALUES(?,?,?,?,?,?)",
                arrayOf(central, seq, "workspace", "central-$seq", seq, seq + 0.25),
            )
        }
        (1L..2L).forEach { seq ->
            sqlite.execSQL(
                "INSERT INTO conversation_items(conversation_id,seq,item_kind,item_key,sort_seq,created_at) VALUES(?,?,?,?,?,?)",
                arrayOf(execution, seq, "evidence", "execution-$seq", seq, seq + 0.25),
            )
        }
        val resultEvidenceId = "result-evidence-from-execution"
        val coordinationEvidenceId = "coordination-evidence-from-execution"

        // Central imports notices/reply intent before it has imported either source evidence row.
        sqlite.execSQL(
            "INSERT INTO workspace_result_refs(schema_version,conversation_id,reference_id,is_notice,seq,evidence_id,content_sha256," +
                "owner_central_session_id,workspace_id,execution_session_id,assignment_id,request_id,mission_id,bot_id,cycle_id," +
                "observed_assignment_revision,reported_status,produced_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(1, central, "result-reference", 1, 1L, resultEvidenceId, "a".repeat(64), central, "workspace-1", execution,
                "assignment-1", "request-1", "mission-1", "bot-1", "cycle-1", 1L, "DONE", 10L),
        )
        sqlite.execSQL(
            "INSERT INTO workspace_coordination_refs(schema_version,conversation_id,reference_id,is_notice,seq,evidence_id,content_sha256," +
                "owner_central_session_id,workspace_id,execution_session_id,assignment_id,request_id,mission_id,bot_id,cycle_id," +
                "message_id,kind,observed_assignment_revision,reported_status,produced_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(1, central, "coordination-reference", 1, 2L, coordinationEvidenceId, "b".repeat(64), central, "workspace-1", execution,
                "assignment-1", "request-1", "mission-1", "bot-1", "cycle-1", "message-1", "QUESTION", 1L, "DONE", 11L),
        )

        // is_notice defaults to true because an intent is bound to its central notice row.
        val reply = WorkspaceCoordinationReply(
            conversation_id = central,
            reference_id = "coordination-reference",
            reply_id = "reply-1",
            evidence_id = coordinationEvidenceId,
            reply_key = "reply-key",
            text = byteArrayOf(1, 2),
            central_request_id = "request-1",
            original_user_request = byteArrayOf(3, 4),
            mailbox_message_id = "mailbox-1",
            payload_sha256 = "c".repeat(64),
            prepared_at_ms = 12L,
            state = "PREPARED",
            state_at_ms = 12L,
            seq = 3L,
        )
        assertTrue("reply intents reference the central notice", reply.is_notice)
        val replyCopy = reply.copy(text = reply.text.copyOf(), original_user_request = reply.original_user_request.copyOf())
        assertEquals(reply, replyCopy)
        assertEquals(reply.hashCode(), replyCopy.hashCode())
        sqlite.execSQL(
            "INSERT INTO workspace_coordination_replies(schema_version,conversation_id,reference_id,reply_id,evidence_id," +
                "reply_key,text,central_request_id,original_user_request,mailbox_message_id,payload_sha256,prepared_at_ms,state,state_at_ms,seq) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(reply.schema_version, reply.conversation_id, reply.reference_id, reply.reply_id,
                reply.evidence_id, reply.reply_key, reply.text, reply.central_request_id, reply.original_user_request,
                reply.mailbox_message_id, reply.payload_sha256, reply.prepared_at_ms, reply.state, reply.state_at_ms, reply.seq),
        )
        assertEquals(1L, scalarLong(sqlite,
            "SELECT is_notice FROM workspace_coordination_replies WHERE conversation_id=? AND reference_id=?",
            arrayOf(central, reply.reference_id),
        ))
        val skippedUpdate = runCatching {
            sqlite.execSQL(
                "UPDATE workspace_coordination_replies SET state='ACCEPTED_LOCAL',state_at_ms=13 WHERE conversation_id=? AND reply_id=?",
                arrayOf(central, reply.reply_id),
            )
        }.exceptionOrNull()
        assertTrue("PREPARED cannot skip STARTED", skippedUpdate is SQLiteConstraintException)
        val skippedTransition = runCatching {
            sqlite.execSQL(
                "INSERT INTO workspace_coordination_reply_transitions(schema_version,conversation_id,reply_id,seq,state,state_at_ms) " +
                    "VALUES(?,?,?,?,?,?)",
                arrayOf(1, central, reply.reply_id, 4L, "ACCEPTED_LOCAL", 13L),
            )
        }.exceptionOrNull()
        assertTrue("PREPARED cannot skip STARTED", skippedTransition is SQLiteConstraintException)
        sqlite.execSQL(
            "INSERT INTO workspace_coordination_reply_transitions(schema_version,conversation_id,reply_id,seq,state,state_at_ms) " +
                "VALUES(?,?,?,?,?,?)",
            arrayOf(1, central, reply.reply_id, 5L, "STARTED", 14L),
        )
        val duplicateTransition = runCatching {
            sqlite.execSQL(
                "INSERT INTO workspace_coordination_reply_transitions(schema_version,conversation_id,reply_id,seq,state,state_at_ms) " +
                    "VALUES(?,?,?,?,?,?)",
                arrayOf(1, central, reply.reply_id, 6L, "STARTED", 15L),
            )
        }.exceptionOrNull()
        assertTrue("one transition per state is enforced", duplicateTransition is SQLiteConstraintException)
        sqlite.execSQL(
            "UPDATE workspace_coordination_replies SET state='STARTED',state_at_ms=14 WHERE conversation_id=? AND reply_id=?",
            arrayOf(central, reply.reply_id),
        )
        sqlite.execSQL(
            "INSERT INTO workspace_coordination_reply_transitions(schema_version,conversation_id,reply_id,seq,state,state_at_ms) " +
                "VALUES(?,?,?,?,?,?)",
            arrayOf(1, central, reply.reply_id, 6L, "ACCEPTED_LOCAL", 15L),
        )
        sqlite.execSQL(
            "UPDATE workspace_coordination_replies SET state='ACCEPTED_LOCAL',state_at_ms=15 WHERE conversation_id=? AND reply_id=?",
            arrayOf(central, reply.reply_id),
        )
        val backwardsUpdate = runCatching {
            sqlite.execSQL(
                "UPDATE workspace_coordination_replies SET state='STARTED',state_at_ms=16 WHERE conversation_id=? AND reply_id=?",
                arrayOf(central, reply.reply_id),
            )
        }.exceptionOrNull()
        assertTrue("terminal reply state cannot move backwards", backwardsUpdate is SQLiteConstraintException)

        assertEquals(1L, rowCount(sqlite, "workspace_result_refs"))
        assertEquals(1L, rowCount(sqlite, "workspace_coordination_refs"))
        assertEquals(1L, rowCount(sqlite, "workspace_coordination_replies"))

        // Now import the source evidence into the execution ledger.
        insertCrewResultEvidence(sqlite, resultEvidenceId, execution)
        if ("schema_version" in tableColumns(sqlite, "crew_result_evidence")) {
            assertEquals(1L, scalarLong(sqlite, "SELECT schema_version FROM crew_result_evidence WHERE evidence_id=?", arrayOf(resultEvidenceId)))
        }
        sqlite.execSQL(
            "INSERT INTO crew_coordination_evidence(schema_version,evidence_id,conversation_id,seq,mission_id,bot_id,cycle_id,message_id,kind,text,produced_at_ms,content_sha256) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(1, coordinationEvidenceId, execution, 2L, "mission-1", "bot-1", "cycle-1", "message-1", "QUESTION",
                byteArrayOf(7, 8), 11L, "b".repeat(64)),
        )
        val evidenceBytes = byteArrayOf(7, 8)
        val evidenceA = CrewCoordinationEvidence(1, "equality-evidence", execution, 2L, "mission-1", "bot-1", "cycle-1",
            "message-2", "QUESTION", evidenceBytes, 12L, "d".repeat(64))
        val evidenceB = evidenceA.copy(text = evidenceBytes.copyOf())
        assertEquals(evidenceA, evidenceB)
        assertEquals(evidenceA.hashCode(), evidenceB.hashCode())

        // Removing the execution ledger cascades its evidence but must not erase central history.
        sqlite.execSQL("DELETE FROM conversations WHERE id=?", arrayOf(execution))
        assertEquals(0L, rowCount(sqlite, "crew_result_evidence"))
        assertEquals(0L, rowCount(sqlite, "crew_coordination_evidence"))
        assertEquals(1L, rowCount(sqlite, "workspace_result_refs"))
        assertEquals(1L, rowCount(sqlite, "workspace_coordination_refs"))
        assertEquals(1L, rowCount(sqlite, "workspace_coordination_replies"))

        assertTrue("result refs do not FK into execution evidence", "crew_result_evidence" !in foreignKeyParents(sqlite, "workspace_result_refs"))
        assertTrue("coordination refs do not FK into execution evidence", "crew_coordination_evidence" !in foreignKeyParents(sqlite, "workspace_coordination_refs"))
        assertTrue("central reply intents do not FK into execution evidence", "crew_coordination_evidence" !in foreignKeyParents(sqlite, "workspace_coordination_replies"))
    }

    private fun <T> onDatabaseThread(block: suspend () -> T): T =
        executor.submit<T> { runBlocking { block() } }.get(30, TimeUnit.SECONDS)

    private fun rowCount(database: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Long =
        database.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun scalarLong(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        sql: String,
        args: Array<out Any?>,
    ): Long = database.query(sql, args).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun insertCrewResultEvidence(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        evidenceId: String,
        executionConversationId: String,
    ) {
        val columns = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        val tableColumns = tableColumns(database, "crew_result_evidence")
        if ("schema_version" in tableColumns) {
            columns += "schema_version"
            values += 1
        }
        columns += listOf(
            "evidence_id", "conversation_id", "seq", "mission_id", "bot_id", "cycle_id", "reported_status",
            "result_text", "produced_at_ms", "content_sha256",
        )
        values += listOf(
            evidenceId, executionConversationId, 1L, "mission-1", "bot-1", "cycle-1", "DONE",
            byteArrayOf(5, 6), 10L, "a".repeat(64),
        )
        val placeholders = List(columns.size) { "?" }.joinToString(",")
        database.execSQL(
            "INSERT INTO crew_result_evidence(${columns.joinToString(",")}) VALUES($placeholders)",
            values.toTypedArray(),
        )
    }

    private fun tableColumns(database: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Set<String> =
        database.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }

    private fun foreignKeyParents(database: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Set<String> =
        database.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("table")))
            }
        }
}
