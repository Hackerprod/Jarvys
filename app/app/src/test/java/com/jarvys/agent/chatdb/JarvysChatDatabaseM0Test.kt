package com.jarvys.agent.chatdb

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

private const val EXPECTED_SCHEMA_V1_IDENTITY_HASH = "ee888c15388481cc4ac92c629c0f8c38" // update after build; v1 is not published yet

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class JarvysChatDatabaseM0Test {
    @get:Rule
    val temporary = TemporaryFolder()

    private val testExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "jarvys-chatdb-m0-test").apply { isDaemon = true }
    }
    private lateinit var database: JarvysChatDatabase
    private lateinit var databaseFile: File
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun openDatabase() {
        databaseFile = temporary.newFile("jarvys-chat.db")
        database = JarvysChatDatabaseProvider.openDatabaseFileForTests(context, databaseFile)
    }

    @After
    fun closeDatabase() {
        if (::database.isInitialized) JarvysChatDatabaseProvider.closeDatabaseFileForTests(database)
        testExecutor.shutdownNow()
    }

    private fun <T> onDatabaseThread(block: suspend () -> T): T =
        testExecutor.submit<T> { runBlocking { block() } }.get(30, TimeUnit.SECONDS)

    private fun <T> onDatabaseThreadBlocking(block: () -> T): T =
        testExecutor.submit<T> { block() }.get(30, TimeUnit.SECONDS)

    @Test
    fun createsEveryDeclaredEntityTableAndEnforcesPragmaContract() = onDatabaseThreadBlocking {
        val sqlite = database.openHelper.writableDatabase
        val openedPath = sqlite.query("PRAGMA database_list").use { cursor ->
            var mainPath: String? = null
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "main") {
                    mainPath = cursor.getString(cursor.getColumnIndexOrThrow("file"))
                }
            }
            mainPath
        }
        assertEquals(databaseFile.canonicalPath, File(openedPath!!).canonicalPath)
        val expectedTables = JarvysChatDatabase.ENTITY_TABLE_NAMES
        val actualTables = mutableSetOf<String>()
        sqlite.query(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' AND name != 'room_master_table'"
        ).use { cursor ->
            while (cursor.moveToNext()) actualTables += cursor.getString(0)
        }
        assertEquals(expectedTables, actualTables)

        assertEquals("wal", pragmaString(sqlite, "journal_mode").lowercase())
        assertEquals(1L, pragmaLong(sqlite, "foreign_keys"))
        assertEquals(5000L, pragmaLong(sqlite, "busy_timeout"))
        assertEquals(1L, pragmaLong(sqlite, "synchronous"))
        assertEquals(1000L, pragmaLong(sqlite, "wal_autocheckpoint"))
        assertEquals(16_777_216L, pragmaLong(sqlite, "journal_size_limit"))
    }

    @Test
    fun deletingConversationCascadesAcrossNormalizedChildTables() = onDatabaseThread {
        val id = "cascade"
        database.conversationDao().insertIfNotTombstoned(conversation(id))
        database.conversationItemDao().insertAll((1L..6L).map { seq ->
            ConversationItem(id, seq, "event", "row-$seq", seq, created_at = seq + 0.25)
        })
        database.messageDao().insert(message(id, "m1", "stable-m1", 1, 0))
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "INSERT INTO message_parts(conversation_id,message_id,ordinal,kind,payload) VALUES(?,?,?,?,?)",
            arrayOf(id, "m1", 0, MessagePartKinds.TEXT, "hello")
        )
        sqlite.execSQL(
            "INSERT INTO message_attachments(conversation_id,message_id,ordinal,attachment_id,name,mime_type,size_bytes,kind,relative_path,sha256,file_ref_id) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(id, "m1", 0, "a1", "note.txt", "text/plain", 5L, "file", "attachments/note.txt", "hash-a1", null)
        )
        sqlite.execSQL(
            "INSERT INTO message_translations(conversation_id,message_id,seq,language,content,created_at) VALUES(?,?,?,?,?,?)",
            arrayOf(id, "m1", 2L, "es", "hola", 2.25)
        )
        sqlite.execSQL(
            "INSERT INTO proactive_suggested_replies(conversation_id,message_id,ordinal,label,text) VALUES(?,?,?,?,?)",
            arrayOf(id, "m1", 0, "Yes", "Yes")
        )
        sqlite.execSQL(
            "INSERT INTO tool_batches(conversation_id,batch_id,seq,message_index,assistant_text,assistant_progress,created_at) VALUES(?,?,?,?,?,?,?)",
            arrayOf(id, "b1", 3L, 0L, "working", 1, 3.25)
        )
        sqlite.execSQL(
            "INSERT INTO tool_calls(conversation_id,batch_id,ordinal,call_id,name,arguments_json,lifecycle,lifecycle_seq) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(id, "b1", 0, "c1", "lookup", "{}", "STARTED", 3L)
        )
        sqlite.execSQL(
            "INSERT INTO tool_results(conversation_id,batch_id,call_id,seq,tool_name,lifecycle,output,created_at) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(id, "b1", "c1", 4L, "lookup", "RESULT", "done", 4.25)
        )
        sqlite.execSQL(
            "INSERT INTO model_errors(conversation_id,seq,message_index,error_code) VALUES(?,?,?,?)",
            arrayOf(id, 5L, 0L, "test_error")
        )
        sqlite.execSQL(
            "INSERT INTO generated_images(conversation_id,seq,prompt,status,created_at) VALUES(?,?,?,?,?)",
            arrayOf(id, 6L, "a small test image", "FAILED", 6.25)
        )

        listOf(
            "conversation_items", "messages", "message_parts", "message_attachments", "message_translations",
            "proactive_suggested_replies", "tool_batches", "tool_calls", "tool_results", "model_errors", "generated_images"
        ).forEach { table ->
            val expectedRows = if (table == "conversation_items") 6L else 1L
            assertEquals("fixture rows in $table", expectedRows, rowCount(sqlite, table))
        }

        assertEquals(1, database.conversationDao().deleteById(id))
        listOf(
            "conversation_items", "messages", "message_parts", "message_attachments", "message_translations",
            "proactive_suggested_replies", "tool_batches", "tool_calls", "tool_results", "model_errors", "generated_images"
        ).forEach { assertEquals("CASCADE did not clear $it", 0L, rowCount(sqlite, it)) }
    }

    @Test
    fun declaredPrimaryAndUniqueKeysRejectDuplicates() = onDatabaseThread {
        database.conversationDao().insertIfNotTombstoned(conversation("unique"))
        database.conversationItemDao().insert(ConversationItem("unique", 1, "message", "m1", 1, created_at = 1.0))
        assertSuspendConstraint {
            database.conversationItemDao().insert(ConversationItem("unique", 1, "message", "other", 1, created_at = 1.0))
        }

        database.messageDao().insert(message("unique", "m1", "stable-1", 1, 0))
        assertSuspendConstraint {
            database.messageDao().insert(message("unique", "m1", "stable-duplicate-id", 2, 1))
        }
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO message_parts(conversation_id,message_id,ordinal,kind,payload) VALUES(?,?,?,?,?)",
            arrayOf("unique", "m1", 0, MessagePartKinds.TEXT, "first")
        )
        assertConstraint {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO message_parts(conversation_id,message_id,ordinal,kind,payload) VALUES(?,?,?,?,?)",
                arrayOf("unique", "m1", 0, MessagePartKinds.TEXT, "duplicate ordinal")
            )
        }
        database.conversationItemDao().insert(ConversationItem("unique", 2, "message", "m2", 2, created_at = 2.0))
        assertSuspendConstraint {
            database.messageDao().insert(message("unique", "m2", "stable-1", 2, 1))
        }
        database.messageDao().insert(message("unique", "m2", "stable-2", 2, 1, proactiveDecisionId = "decision-1"))
        database.conversationItemDao().insert(ConversationItem("unique", 3, "message", "m3", 3, created_at = 3.0))
        assertSuspendConstraint {
            database.messageDao().insert(message("unique", "m3", "stable-3", 3, 2, proactiveDecisionId = "decision-1"))
        }
        database.conversationDao().insertIfNotTombstoned(conversation("unique-other"))
        database.conversationItemDao().insert(ConversationItem("unique-other", 1, "message", "m1", 1, created_at = 1.0))
        database.messageDao().insert(message("unique-other", "m1", "stable-other", 1, 0, proactiveDecisionId = "decision-1"))
        Unit
    }

    @Test
    fun activeGenerationRunKeysAreUniqueAndTerminalRunsReleaseTheKey() = onDatabaseThread {
        val conversationId = "active-runs"
        val messageId = "m1"
        database.conversationDao().insertIfNotTombstoned(conversation(conversationId))
        database.conversationItemDao().insert(ConversationItem(conversationId, 1, "message", messageId, 1, created_at = 1.0))
        database.messageDao().insert(message(conversationId, messageId, "stable-m1", 1, 0))
        val sqlite = database.openHelper.writableDatabase
        val activeTargetKey = "${conversationId.length}:$conversationId${messageId.length}:$messageId"

        fun insertRun(runId: String, state: String, targetKey: String?) {
            sqlite.execSQL(
                "INSERT INTO generation_run(id,conversation_id,message_id,active_target_key,state,state_revision,checkpoint_seq,created_at,updated_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(runId, conversationId, messageId, targetKey, state, 1L, 0L, 1L, 1L),
            )
        }

        insertRun("run-1", "streaming", activeTargetKey)
        assertConstraint { insertRun("run-duplicate", "requesting", activeTargetKey) }
        assertConstraint { insertRun("active-without-key", "streaming", null) }
        assertConstraint { insertRun("terminal-with-key", "complete", activeTargetKey) }

        sqlite.execSQL("UPDATE generation_run SET state='complete', active_target_key=NULL WHERE id='run-1'")
        insertRun("run-2", "preparing", activeTargetKey)
        insertRun("run-terminal-1", "complete", null)
        insertRun("run-terminal-2", "failed", null)
        assertConstraint { sqlite.execSQL("UPDATE generation_run SET active_target_key=NULL WHERE id='run-2'") }
        assertConstraint { sqlite.execSQL("UPDATE generation_run SET active_target_key=? WHERE id='run-terminal-1'", arrayOf(activeTargetKey)) }
        assertEquals(4L, rowCount(sqlite, "generation_run"))
    }

    @Test
    fun pendingInterruptIndexIsNonUniqueAndIncludesDispatchPosition() = onDatabaseThread {
        val conversationId = "pending-interrupts"
        database.conversationDao().insertIfNotTombstoned(conversation(conversationId))
        database.conversationItemDao().insertAll(listOf(
            ConversationItem(conversationId, 1, "interrupt", "pending-1", 1, created_at = 1.0),
            ConversationItem(conversationId, 2, "interrupt", "pending-2", 2, created_at = 2.0),
        ))
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "INSERT INTO interrupt_requests(conversation_id,request_id,seq,content,memory_disabled,dispatched_seq,created_at) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(conversationId, "pending-1", 1L, "first", 0, null, 1.25),
        )
        sqlite.execSQL(
            "INSERT INTO interrupt_requests(conversation_id,request_id,seq,content,memory_disabled,dispatched_seq,created_at) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(conversationId, "pending-2", 2L, "second", 0, null, 2.25),
        )

        val indexSql = sqlite.query(
            "SELECT sql FROM sqlite_master WHERE type='index' AND name='idx_interrupt_requests_pending'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        assertTrue(indexSql.startsWith("CREATE INDEX "))
        assertTrue(!indexSql.contains(" WHERE ", ignoreCase = true))
        val indexedColumns = sqlite.query("PRAGMA index_info('idx_interrupt_requests_pending')").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
        assertEquals(listOf("conversation_id", "dispatched_seq"), indexedColumns)
        assertEquals(2L, rowCount(sqlite, "interrupt_requests"))
    }

    @Test
    fun itemKeysetPagesCoverFiveHundredRowsWithoutGapsOrDuplicates() = onDatabaseThread {
        val id = "pages"
        database.conversationDao().insertIfNotTombstoned(conversation(id))
        val items = (1L..500L).map { seq -> ConversationItem(id, seq, "message", "m$seq", seq, created_at = seq.toDouble()) }
        val messages = (1L..500L).map { seq -> message(id, "m$seq", "stable-$seq", seq, seq - 1) }
        database.withTransaction {
            database.conversationItemDao().insertAll(items)
            database.messageDao().insertAll(messages)
        }

        val seen = mutableListOf<Long>()
        var page = database.conversationItemDao().queryTail(id, 20)
        assertEquals((500L downTo 481L).toList(), page.map { it.seq })
        seen += page.map { it.seq }
        while (page.isNotEmpty()) {
            page = database.conversationItemDao().queryBefore(id, page.last().seq, 20)
            seen += page.map { it.seq }
        }
        assertEquals((500L downTo 1L).toList(), seen)
        assertEquals(500, seen.toSet().size)
    }

    @Test
    fun tombstonePreventsDuplicateInsertFromResurrectingConversation() = onDatabaseThread {
        val id = "deleted"
        database.conversationDao().insertIfNotTombstoned(conversation(id))
        assertEquals(1, database.conversationDao().deleteById(id, 99L))
        assertTrue(database.conversationDao().isTombstoned(id))
        assertEquals(null, database.conversationDao().getById(id))
        assertTrue(runCatching {
            database.conversationDao().insertIfNotTombstoned(conversation(id))
        }.isFailure)
        assertConstraint {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO conversations(id,kind,pinned,archived,private_code,created_at,updated_at,message_count,next_seq,import_state) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(id, "chat", 0, 0, 0, 1L, 1L, 0L, 1L, "not_started"),
            )
        }
        assertEquals(99L, scalarLong(
            database.openHelper.writableDatabase,
            "SELECT deleted_at FROM tombstones WHERE scope='conversation' AND entity_id=?",
            arrayOf(id),
        ))
    }

    @Test
    fun deletingConversationThatWasNeverImportedLeavesPermanentTombstone() = onDatabaseThread {
        val id = "not-imported-yet"
        assertEquals(0, database.conversationDao().deleteById(id, 77L))
        assertTrue(database.conversationDao().isTombstoned(id))
        assertEquals(77L, scalarLong(
            database.openHelper.writableDatabase,
            "SELECT deleted_at FROM tombstones WHERE scope='conversation' AND entity_id=?",
            arrayOf(id),
        ))
        assertTrue(runCatching {
            database.conversationDao().insertIfNotTombstoned(conversation(id))
        }.isFailure)
    }

    @Test
    fun tombstonesRejectUpdateAndDelete() = onDatabaseThread {
        val sqlite = database.openHelper.writableDatabase
        database.conversationDao().deleteById("immutable-tombstone", 55L)

        assertConstraint("tombstone UPDATE") {
            sqlite.execSQL("UPDATE tombstones SET deleted_at=56 WHERE scope='conversation' AND entity_id='immutable-tombstone'")
        }
        assertConstraint("tombstone DELETE") {
            sqlite.execSQL("DELETE FROM tombstones WHERE scope='conversation' AND entity_id='immutable-tombstone'")
        }
        assertEquals(1L, rowCount(sqlite, "tombstones"))
    }

    @Test
    fun newConversationAndTombstoneTriggersRejectRawSqlAndAfterDeletePersistsMillis() = onDatabaseThread {
        val sqlite = database.openHelper.writableDatabase
        val id = "guarded-conversation"
        database.conversationDao().insertIfNotTombstoned(conversation(id))
        database.conversationItemDao().insert(ConversationItem(id, 1, "message", "m1", 1, created_at = 1.0))
        database.messageDao().insert(message(id, "m1", "stable-m1", 1, 0))

        // allowed-replace-fixture: exercise the abort trigger that prevents REPLACE from cascading children.
        assertConstraintMessage("INSERT OR REPLACE conversation", "conversation_exists") {
            sqlite.execSQL(
                "INSERT OR REPLACE INTO conversations(id,kind,pinned,archived,private_code,created_at,updated_at,message_count,next_seq,import_state) " + // allowed-replace-fixture
                    "VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(id, "chat", 0, 0, 0, 2L, 2L, 0L, 1L, "not_started"),
            )
        }
        assertEquals(1L, rowCount(sqlite, "conversation_items"))
        assertEquals(1L, rowCount(sqlite, "messages"))

        assertConstraintMessage("conversation ID mutation", "conversation_id_immutable") {
            sqlite.execSQL("UPDATE conversations SET id='mutated-id' WHERE id=?", arrayOf(id))
        }
        assertEquals(1L, scalarLong(sqlite, "SELECT COUNT(*) FROM conversations WHERE id=?", arrayOf(id)))

        val beforeDelete = System.currentTimeMillis()
        sqlite.execSQL("DELETE FROM conversations WHERE id=?", arrayOf(id))
        val deletedAt = scalarLong(sqlite, "SELECT deleted_at FROM tombstones WHERE scope='conversation' AND entity_id=?", arrayOf(id))
        assertTrue("AFTER DELETE timestamp should be approximate epoch milliseconds: $deletedAt", deletedAt in (beforeDelete - 1_000L)..(System.currentTimeMillis() + 1_000L))
        assertEquals(0L, rowCount(sqlite, "conversation_items"))

        val missingId = "raw-tombstone"
        database.conversationDao().deleteById(missingId, 123L)
        assertConstraintMessage("duplicate tombstone", "tombstone_exists") {
            sqlite.execSQL(
                "INSERT OR IGNORE INTO tombstones(scope,entity_id,deleted_at) VALUES('conversation',?,124)",
                arrayOf(missingId),
            )
        }
        assertEquals(1L, scalarLong(sqlite, "SELECT COUNT(*) FROM tombstones WHERE scope='conversation' AND entity_id=?", arrayOf(missingId)))
    }

    @Test
    fun installedTriggerNamesExactlyMatchTheDatabaseTriggerContract() = onDatabaseThreadBlocking {
        val expected = setOf(
            "trg_conversation_no_tombstone_resurrection",
            "trg_conversation_no_replace",
            "trg_conversation_id_immutable",
            "trg_tombstones_no_duplicate",
            "trg_conversation_tombstone_after_delete",
            "trg_tombstones_no_update",
            "trg_tombstones_no_delete",
            "trg_workspace_reply_transition_monotonic",
            "trg_workspace_reply_update_monotonic",
            "trg_generation_run_target_insert",
            "trg_generation_run_target_update",
        )
        val actual = database.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='trigger' ORDER BY name",
        ).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertEquals(expected, actual)
    }

    @Test
    fun onOpenRecreatesMissingDatabaseTriggers() = onDatabaseThreadBlocking {
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL("DROP TRIGGER trg_tombstones_no_update")
        sqlite.execSQL("DROP TRIGGER trg_conversation_no_replace")
        assertEquals(0L, scalarLong(
            sqlite,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='trg_tombstones_no_update'",
            emptyArray(),
        ))

        JarvysChatDatabaseProvider.closeDatabaseFileForTests(database)
        database = JarvysChatDatabaseProvider.openDatabaseFileForTests(context, databaseFile)
        val reopened = database.openHelper.writableDatabase
        assertEquals(1L, scalarLong(
            reopened,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='trg_tombstones_no_update'",
            emptyArray(),
        ))
        assertEquals(1L, scalarLong(
            reopened,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='trg_conversation_no_replace'",
            emptyArray(),
        ))
    }

    @Test
    fun everyConversationChildCascadesAndOnlyGlobalStorageAndTombstoneRowsRemain() = onDatabaseThread {
        val id = "cascade-every-table"
        val sqlite = database.openHelper.writableDatabase
        val seeded = seedEveryConversationChild(sqlite, id)
        val childTables = JarvysChatDatabase.ENTITY_TABLE_NAMES - setOf(
            "conversations", "tombstones", "storage_meta", "file_refs",
        )
        assertEquals(childTables, seeded.keys - setOf("conversations", "file_refs"))
        childTables.forEach { table -> assertEquals("fixture row in $table", 1L, rowCount(sqlite, table)) }
        assertEmptyForeignKeyCheck(sqlite)

        assertEquals(1, database.conversationDao().deleteById(id, 1234L))
        childTables.forEach { table -> assertEquals("CASCADE did not clear $table", 0L, rowCount(sqlite, table)) }
        assertEquals(1L, rowCount(sqlite, "tombstones"))
        assertEquals(1L, rowCount(sqlite, "file_refs"))
        assertEquals(0L, rowCount(sqlite, "storage_meta"))
        assertTrue(database.conversationDao().isTombstoned(id))
        assertNull(database.conversationDao().getById(id))
        assertTrue(runCatching {
            database.conversationDao().insertIfNotTombstoned(conversation(id))
        }.isFailure)
        assertEmptyForeignKeyCheck(sqlite)
    }

    @Test
    fun foreignKeyCheckIsEmptyAndEveryDeclaredForeignKeyRejectsAnOrphan() = onDatabaseThread {
        val sqlite = database.openHelper.writableDatabase
        val seeded = seedEveryConversationChild(sqlite, "fk-every-table")
        assertEmptyForeignKeyCheck(sqlite)
        var checked = 0
        seeded.forEach { (table, row) ->
            foreignKeys(sqlite, table).forEach { foreignKey ->
                val childColumn = foreignKey.columns.last().first
                val column = tableColumns(sqlite, table).getValue(childColumn)
                val orphan = orphanValue(table, childColumn, column.type, row[childColumn])
                val setSql = "UPDATE `$table` SET `$childColumn`=? WHERE " +
                    primaryKeyColumns(sqlite, table).joinToString(" AND ") { "`$it`=?" }
                val keyArgs = primaryKeyColumns(sqlite, table).map { row[it] }.toTypedArray()
                val error = runCatching { sqlite.execSQL(setSql, arrayOf(orphan, *keyArgs)) }.exceptionOrNull()
                assertConstraintFailure("$table.${foreignKey.parentTable} FK ($childColumn)", error)
                assertEmptyForeignKeyCheck(sqlite)
                checked++
            }
        }
        assertTrue("Expected to exercise declared foreign keys", checked >= 35)
    }

    @Test
    fun duplicateRowsAreRejectedForEveryStrictJournalTable() = onDatabaseThread {
        val sqlite = database.openHelper.writableDatabase
        val seeded = seedEveryConversationChild(sqlite, "unique-journal")
        val journalTables = setOf(
            "crew_result_evidence", "workspace_result_refs", "crew_coordination_evidence",
            "crew_coordination_evidence_refs", "workspace_coordination_refs",
            "workspace_coordination_replies", "workspace_coordination_reply_transitions",
            "workspace_input_incorporations",
        )
        journalTables.forEach { table ->
            val row = seeded.getValue(table)
            assertConstraint("duplicate $table") { insertRow(sqlite, table, row) }
        }

        val eventId = seeded.getValue("tool_activities").getValue("event_id") as String
        suspend fun insertActivity(conversationId: String, seq: Long, batchId: String) {
            database.conversationItemDao().insert(
                ConversationItem(conversationId, seq, "tool_activity", "activity-$seq", seq, created_at = seq.toDouble()),
            )
            sqlite.execSQL(
                "INSERT INTO tool_batches(conversation_id,batch_id,seq,message_index,assistant_text,assistant_progress,created_at) VALUES(?,?,?,?,?,?,?)",
                arrayOf(conversationId, batchId, seq, 0L, "", 0, seq.toDouble()),
            )
            sqlite.execSQL(
                "INSERT INTO tool_activities(conversation_id,seq,event_id,source_batch_id,tool_name,stage,timestamp_millis,created_at) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf(conversationId, seq, eventId, batchId, "tool", "complete", seq, seq.toDouble()),
            )
        }

        assertConstraint("event_id of the seeded activity is already taken in this conversation") {
            insertActivity("unique-journal", 2L, "same-ledger-batch")
        }
        assertConstraint("event_id remains unique within one conversation") {
            insertActivity("unique-journal", 3L, "same-ledger-batch-duplicate")
        }
        database.conversationDao().insertIfNotTombstoned(conversation("unique-journal-other"))
        insertActivity("unique-journal-other", 1L, "other-ledger-batch")

        val inputReceipt = seeded.getValue("workspace_input_incorporations")
        val duplicateMailboxInSameLedger = inputReceipt.toMutableMap().apply {
            put("receipt_id", "same-ledger-second-receipt")
        }
        assertConstraint("mailbox message remains unique within one conversation") {
            insertRow(sqlite, "workspace_input_incorporations", duplicateMailboxInSameLedger)
        }
        val duplicateMailboxInOtherLedger = inputReceipt.toMutableMap().apply {
            put("conversation_id", "unique-journal-other")
        }
        insertRow(sqlite, "workspace_input_incorporations", duplicateMailboxInOtherLedger)
    }

    @Test
    fun journalIdentityKeysAreScopedToConversationLedger() = onDatabaseThread {
        val sqlite = database.openHelper.writableDatabase
        val firstId = "journal-key-a"
        val secondId = "journal-key-b"
        listOf(firstId, secondId).forEach { id ->
            database.conversationDao().insertIfNotTombstoned(conversation(id))
            database.conversationItemDao().insert(ConversationItem(id, 1, "journal", "fixture", 1, created_at = 1.0))
        }
        assertEquals(listOf("conversation_id", "evidence_id"), primaryKeyColumns(sqlite, "crew_result_evidence"))
        assertEquals(listOf("conversation_id", "evidence_id"), primaryKeyColumns(sqlite, "crew_coordination_evidence"))
        assertEquals(listOf("conversation_id", "evidence_id", "ordinal"), primaryKeyColumns(sqlite, "crew_coordination_evidence_refs"))
        assertEquals(listOf("conversation_id", "receipt_id"), primaryKeyColumns(sqlite, "workspace_input_incorporations"))

        fun ledgerParents(id: String) = mapOf(
            "conversations" to mapOf("id" to id),
            "conversation_items" to mapOf("conversation_id" to id, "seq" to 1L),
        )
        val resultEvidenceA = genericEntityRow(sqlite, "crew_result_evidence", ledgerParents(firstId), foreignKeys(sqlite, "crew_result_evidence"))
        val resultEvidenceB = genericEntityRow(sqlite, "crew_result_evidence", ledgerParents(secondId), foreignKeys(sqlite, "crew_result_evidence"))
        insertRow(sqlite, "crew_result_evidence", resultEvidenceA)
        insertRow(sqlite, "crew_result_evidence", resultEvidenceB)
        assertConstraint("duplicate result evidence in one ledger") { insertRow(sqlite, "crew_result_evidence", resultEvidenceA) }

        val coordinationA = genericEntityRow(sqlite, "crew_coordination_evidence", ledgerParents(firstId), foreignKeys(sqlite, "crew_coordination_evidence"))
        val coordinationB = genericEntityRow(sqlite, "crew_coordination_evidence", ledgerParents(secondId), foreignKeys(sqlite, "crew_coordination_evidence"))
        insertRow(sqlite, "crew_coordination_evidence", coordinationA)
        insertRow(sqlite, "crew_coordination_evidence", coordinationB)
        assertConstraint("duplicate coordination evidence in one ledger") { insertRow(sqlite, "crew_coordination_evidence", coordinationA) }

        fun evidenceRef(evidence: Map<String, Any?>): Map<String, Any?> = genericEntityRow(
            sqlite,
            "crew_coordination_evidence_refs",
            mapOf("crew_coordination_evidence" to evidence),
            foreignKeys(sqlite, "crew_coordination_evidence_refs"),
        )
        val evidenceRefA = evidenceRef(coordinationA)
        val evidenceRefB = evidenceRef(coordinationB)
        insertRow(sqlite, "crew_coordination_evidence_refs", evidenceRefA)
        insertRow(sqlite, "crew_coordination_evidence_refs", evidenceRefB)
        assertConstraint("duplicate coordination evidence ref in one ledger") {
            insertRow(sqlite, "crew_coordination_evidence_refs", evidenceRefA)
        }

        val receiptA = genericEntityRow(sqlite, "workspace_input_incorporations", ledgerParents(firstId), foreignKeys(sqlite, "workspace_input_incorporations"))
        val receiptB = genericEntityRow(sqlite, "workspace_input_incorporations", ledgerParents(secondId), foreignKeys(sqlite, "workspace_input_incorporations"))
        insertRow(sqlite, "workspace_input_incorporations", receiptA)
        insertRow(sqlite, "workspace_input_incorporations", receiptB)
        assertConstraint("duplicate workspace receipt in one ledger") {
            insertRow(sqlite, "workspace_input_incorporations", receiptA)
        }
    }

    @Test
    fun workspaceReplyAndEvidenceIdsAreUniqueWithinConversationOnly() = onDatabaseThread {
        val sqlite = database.openHelper.writableDatabase
        suspend fun insertReference(conversationId: String, seq: Long, referenceId: String, evidenceId: String) {
            database.conversationItemDao().insert(
                ConversationItem(conversationId, seq, "workspace_coordination_ref", referenceId, seq, created_at = seq.toDouble()),
            )
            sqlite.execSQL(
                "INSERT INTO workspace_coordination_refs(schema_version,conversation_id,reference_id,is_notice,seq,evidence_id,content_sha256,owner_central_session_id,workspace_id,execution_session_id,assignment_id,request_id,mission_id,bot_id,cycle_id,message_id,kind,observed_assignment_revision,reported_status,produced_at_ms) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(1, conversationId, referenceId, 1, seq, evidenceId, "digest-$evidenceId", "central", "workspace",
                    "execution", "assignment", "request", "mission", "bot", "cycle", "message", "RESULT", 1L, "completed", seq),
            )
        }
        fun insertReply(conversationId: String, seq: Long, referenceId: String, replyId: String, evidenceId: String) {
            sqlite.execSQL(
                "INSERT INTO workspace_coordination_replies(conversation_id,reference_id,is_notice,schema_version,reply_id,evidence_id,reply_key,text,central_request_id,original_user_request,mailbox_message_id,payload_sha256,prepared_at_ms,state,state_at_ms,seq) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(conversationId, referenceId, 1, 1, replyId, evidenceId, "key-$replyId", byteArrayOf(65), "request",
                    "original".toByteArray(), "mailbox-$replyId", "payload-$replyId", seq, "PREPARED", seq, seq),
            )
        }

        val firstConversation = "workspace-reply-a"
        database.conversationDao().insertIfNotTombstoned(conversation(firstConversation))
        insertReference(firstConversation, 1L, "ref-a-1", "evidence-shared")
        insertReply(firstConversation, 1L, "ref-a-1", "reply-shared", "evidence-shared")

        insertReference(firstConversation, 2L, "ref-a-2", "evidence-other")
        assertConstraint("reply_id remains unique within one conversation") {
            insertReply(firstConversation, 2L, "ref-a-2", "reply-shared", "evidence-other")
        }
        insertReference(firstConversation, 3L, "ref-a-3", "evidence-shared")
        assertConstraint("evidence_id remains unique within one conversation") {
            insertReply(firstConversation, 3L, "ref-a-3", "reply-other", "evidence-shared")
        }

        val secondConversation = "workspace-reply-b"
        database.conversationDao().insertIfNotTombstoned(conversation(secondConversation))
        insertReference(secondConversation, 1L, "ref-b-1", "evidence-shared")
        insertReply(secondConversation, 1L, "ref-b-1", "reply-shared", "evidence-shared")
    }

    @Test
    fun messagePartsHaveNoRevisionAndOrdinalIsUniqueWithinTheMessage() = onDatabaseThread {
        val id = "message-part-shape"
        database.conversationDao().insertIfNotTombstoned(conversation(id))
        database.conversationItemDao().insert(ConversationItem(id, 1, "message", "m1", 1, created_at = 1.0))
        database.messageDao().insert(message(id, "m1", "stable-m1", 1, 0))
        val sqlite = database.openHelper.writableDatabase
        val columns = tableColumns(sqlite, "message_parts").keys
        assertTrue("revision is deliberately absent", "revision" !in columns)
        val pk = primaryKeyColumns(sqlite, "message_parts")
        assertEquals(listOf("conversation_id", "message_id", "ordinal"), pk)
        sqlite.execSQL(
            "INSERT INTO message_parts(conversation_id,message_id,ordinal,kind,payload) VALUES(?,?,?,?,?)",
            arrayOf(id, "m1", 0, MessagePartKinds.TEXT, "first"),
        )
        assertConstraint {
            sqlite.execSQL(
                "INSERT INTO message_parts(conversation_id,message_id,ordinal,kind,payload) VALUES(?,?,?,?,?)",
                arrayOf(id, "m1", 0, MessagePartKinds.TEXT, "second"),
            )
        }
    }

    @Test
    fun ledgerCreatedAtColumnsAreRealAndRoundTripTheOriginalDouble() = onDatabaseThread {
        val sqlite = database.openHelper.writableDatabase
        val seeded = seedEveryConversationChild(sqlite, "timestamp-round-trip")
        val expected = 98_765.4321
        val requiredEventTables = setOf(
            "conversation_items", "messages", "interrupt_requests", "artifacts", "generated_images", "tool_batches",
            "tool_results", "tool_activities", "tool_presentations", "reflection_tool_markers", "reflections",
            "message_translations", "user_decisions", "message_reactions", "compactions", "compaction_reflection_acks",
        )
        requiredEventTables.forEach { table ->
            assertTrue("$table must persist the ledger timestamp", "created_at" in tableColumns(sqlite, table))
        }
        val eventTables = seeded.keys.filter { tableColumns(sqlite, it)["created_at"]?.type.equals("REAL", ignoreCase = true) }
        assertTrue("timestamp columns are represented in the spine and event rows", requiredEventTables.all { it in eventTables })
        eventTables.forEach { table ->
            val column = tableColumns(sqlite, table).getValue("created_at")
            assertEquals("$table.created_at must be REAL", "REAL", column.type.uppercase())
            assertEquals("$table.created_at must be NOT NULL", 1, column.notNull)
            val actual = scalarDouble(sqlite, "SELECT created_at FROM `$table` LIMIT 1")
            assertEquals("$table timestamp", expected, actual, 0.0000001)
        }
    }

    @Test
    fun crewMessageActivityBindingAndLosslessTextRoundTripAsTypedRows() = onDatabaseThread {
        val id = "crew-round-trip"
        database.conversationDao().insertIfNotTombstoned(conversation(id))
        database.conversationItemDao().insert(ConversationItem(id, 1, "crew_snapshot", "mission-1", 1, created_at = 1.0))
        val sqlite = database.openHelper.writableDatabase
        val synthesis = ExactUtf16.encode(String(charArrayOf('S', '\uD800', 'Y')))
        val error = ExactUtf16.encode(String(charArrayOf('E', '\uDC00', 'R')))
        val result = ExactUtf16.encode(String(charArrayOf('R', '\uD800', 'S')))
        val messageText = ExactUtf16.encode(String(charArrayOf('M', '\uDC00', 'G')))
        sqlite.execSQL(
            "INSERT INTO crew_missions(conversation_id,mission_id,first_snapshot_seq,last_snapshot_seq,schema_version,process_id,title," +
                "snapshot_digest,original_instructions,title_source,status,synthesis,started_at_ms,finished_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(id, "mission-1", 1L, 1L, 4, "process-1", "digest", "title", "instructions", "user", "completed", synthesis, 10L, 20L),
        )
        sqlite.execSQL(
            "INSERT INTO crew_bots(conversation_id,mission_id,bot_id,ordinal,name,status,error,result,resume_required,can_resume) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
            arrayOf(id, "mission-1", "bot-1", 0, "Bot", "completed", error, result, 0, 1),
        )
        sqlite.execSQL(
            "INSERT INTO crew_messages(conversation_id,mission_id,ordinal,message_id,from_bot,to_bot,type,text,timestamp_ms,origin) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
            arrayOf(id, "mission-1", 0, "message-1", "bot-1", "chief", "ANSWER", messageText, 123456L, "local_activity"),
        )
        sqlite.execSQL(
            "INSERT INTO crew_message_activities(conversation_id,mission_id,message_id,schema_version,event_id,execution_id,call_id," +
                "tool_name,display_name,skill_id,skill_name,stage,detail,preview_id,audit_detail,reflection_source,timestamp_millis) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(id, "mission-1", "message-1", 1, "event-1", "execution-1", "call-1", "read_skill", "Read skill",
                "skill-1", "Skill", "tool_result", "activity detail", "preview-1", "audit", "web", 234567L),
        )
        sqlite.execSQL(
            "INSERT INTO crew_message_input_bindings(conversation_id,mission_id,message_id,schema_version,source_kind,source_id," +
                "owner_central_session_id,workspace_id,execution_session_id,assignment_id,input_mission_id,bot_id,mailbox_message_id," +
                "source_payload_sha256,delivery_sha256,admission_cycle_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(id, "mission-1", "message-1", 1, "WORKSPACE_REPLY", "source-1", "central", "workspace", id,
                "assignment", "mission-1", "bot-1", "mailbox-1", "a".repeat(64), "b".repeat(64), "cycle-1"),
        )

        assertEquals("BLOB", tableColumns(sqlite, "crew_missions").getValue("synthesis").type.uppercase())
        assertEquals("BLOB", tableColumns(sqlite, "crew_bots").getValue("error").type.uppercase())
        assertEquals("BLOB", tableColumns(sqlite, "crew_bots").getValue("result").type.uppercase())
        assertArrayEquals(synthesis, scalarBlob(sqlite, "SELECT synthesis FROM crew_missions WHERE mission_id='mission-1'"))
        assertArrayEquals(error, scalarBlob(sqlite, "SELECT error FROM crew_bots WHERE bot_id='bot-1'"))
        assertArrayEquals(result, scalarBlob(sqlite, "SELECT result FROM crew_bots WHERE bot_id='bot-1'"))
        assertArrayEquals(messageText, scalarBlob(sqlite, "SELECT text FROM crew_messages WHERE message_id='message-1'"))

        val activity = sqlite.query(
            "SELECT schema_version,event_id,execution_id,call_id,tool_name,display_name,skill_id,skill_name,stage,detail," +
                "preview_id,audit_detail,reflection_source,timestamp_millis FROM crew_message_activities WHERE message_id='message-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            (0 until cursor.columnCount).map { cursor.getString(it) }
        }
        assertEquals(listOf("1", "event-1", "execution-1", "call-1", "read_skill", "Read skill", "skill-1", "Skill",
            "tool_result", "activity detail", "preview-1", "audit", "web", "234567"), activity)
        assertEquals("WORKSPACE_REPLY", scalarString(sqlite,
            "SELECT source_kind FROM crew_message_input_bindings WHERE message_id='message-1'"))
        assertEquals("cycle-1", scalarString(sqlite,
            "SELECT admission_cycle_id FROM crew_message_input_bindings WHERE message_id='message-1'"))
        assertTrue("input binding is no longer an opaque JSON column", "input_binding_json" !in tableColumns(sqlite, "crew_messages"))

        val crewMessageA = CrewMessage(id, "mission-1", 0, "message-1", "bot-1", "chief", "ANSWER", messageText, 123456L, "local_activity")
        val crewMessageB = crewMessageA.copy(text = messageText.copyOf())
        assertEquals(crewMessageA, crewMessageB)
        assertEquals(crewMessageA.hashCode(), crewMessageB.hashCode())

        val missionA = CrewMission(id, "mission-1", 1L, 1L, 4, "process-1", "digest", "title", "instructions", "user", "completed", synthesis, 10L, 20L)
        val missionB = missionA.copy(synthesis = synthesis.copyOf())
        assertEquals(missionA, missionB)
        assertEquals(missionA.hashCode(), missionB.hashCode())
        val botA = CrewBot(id, "mission-1", "bot-1", 0, name = "Bot", status = "completed", error = error, result = result,
            resume_required = false, can_resume = true)
        val botB = botA.copy(error = error.copyOf(), result = result.copyOf())
        assertEquals(botA, botB)
        assertEquals(botA.hashCode(), botB.hashCode())
        val evidenceA = CrewResultEvidence(id, "evidence-1", 1L, 1, "mission-1", "bot-1", "cycle-1", "DONE", result, 30L, "sha256")
        val evidenceB = evidenceA.copy(result_text = result.copyOf())
        assertEquals(evidenceA, evidenceB)
        assertEquals(evidenceA.hashCode(), evidenceB.hashCode())
    }

    @Test
    fun deletingFileReferenceLeavesConversationHistoryAndPhysicalFileInPlace() = onDatabaseThread {
        val sqlite = database.openHelper.writableDatabase
        seedEveryConversationChild(sqlite, "file-reference-gc")
        val physicalFile = File(temporary.root, "test-artifact.bin").apply { writeBytes(byteArrayOf(8, 7, 6)) }
        sqlite.execSQL(
            "INSERT INTO file_refs(id,sha256,relative_path,bytes,last_referenced_at,attempts,generation) VALUES(?,?,?,?,?,?,?)",
            arrayOf("same-hash-second-copy", "file_refs_sha256", "second-copy.bin", 3L, 1L, 0, 0L),
        )
        assertEquals("hashes are non-unique", 2L, rowCount(sqlite, "file_refs"))
        assertConstraint {
            sqlite.execSQL(
                "INSERT INTO file_refs(id,sha256,relative_path,bytes,last_referenced_at,attempts,generation) VALUES(?,?,?,?,?,?,?)",
                arrayOf("duplicate-path", "another-sha", "test-artifact.bin", 3L, 1L, 0, 0L),
            )
        }

        sqlite.execSQL("DELETE FROM file_refs WHERE id='file_refs_id'")
        listOf("message_attachments", "model_artifacts", "artifacts", "generated_images").forEach { table ->
            assertEquals("GC must preserve $table history", 1L, rowCount(sqlite, table))
            assertNull(scalarNullableString(sqlite, "SELECT file_ref_id FROM `$table` LIMIT 1"))
        }
        assertTrue("physical file deletion is outside the SQLite transaction", physicalFile.exists())
    }

    @Test
    fun foreignKeysAreEnforcedOnASecondWriterConnection() {
        val secondDatabase = Room.databaseBuilder(context, JarvysChatDatabase::class.java, databaseFile.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(JarvysChatDatabaseProvider.connectionPragmasCallback())
            .build()
        try {
            val secondHelper: SupportSQLiteOpenHelper = secondDatabase.openHelper
            val secondWriter = secondHelper.writableDatabase
            assertEquals(1L, pragmaLong(secondWriter, "foreign_keys"))
            assertConstraint("foreign key on second writer") {
                secondWriter.execSQL(
                    "INSERT INTO conversation_items(conversation_id,seq,item_kind,item_key,sort_seq,sort_sub,created_at) VALUES(?,?,?,?,?,?,?)",
                    arrayOf("missing-parent", 1L, "message", "orphan", 1L, 0, 1.25),
                )
            }
        } finally {
            secondDatabase.close()
        }
    }

    @Test
    fun databaseSupportsConcurrentReadersUnderWal() {
        val sqlite = database.openHelper.writableDatabase
        val start = CountDownLatch(1)
        val readers = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map {
                readers.submit<Long> {
                    start.await(10, TimeUnit.SECONDS)
                    sqlite.query(
                        "WITH RECURSIVE n(x) AS (SELECT 0 UNION ALL SELECT x+1 FROM n WHERE x<200000) SELECT SUM(x) FROM n",
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        cursor.getLong(0)
                    }
                }
            }
            start.countDown()
            futures.forEach { assertEquals(20_000_100_000L, it.get(30, TimeUnit.SECONDS)) }
        } finally {
            readers.shutdownNow()
        }
    }

    @Test
    fun futureSchemaVersionIsRejectedWithoutChangingDatabaseBytes() {
        val futureFile = File(temporary.root, "future.db")
        onDatabaseThreadBlocking {
            SQLiteDatabase.openDatabase(
                futureFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
            ).use { sqlite -> sqlite.execSQL("PRAGMA user_version = 2") }
        }
        val before = futureFile.readBytes()
        val failure = try {
            JarvysChatDatabaseProvider.openDatabaseFileForTests(context, futureFile)
            throw AssertionError("A future schema version unexpectedly opened")
        } catch (expected: IllegalStateException) {
            expected
        }
        assertTrue(failure.message.orEmpty().contains("database_schema_too_new"))
        assertArrayEquals(before, futureFile.readBytes())
    }

    /**
     * Pins v1's identity hash and checks it against both Room's export and the opened database.
     * Any v1 schema change requires a version bump plus an explicit migration; after the final
     * schema export, update the pinned constant deliberately because KSP regenerates the JSON.
     */
    @Test
    fun exportedSchemaV1MatchesRealDatabaseStructure() = onDatabaseThreadBlocking {
        val schemaDirectory = System.getProperty("room.schemaDir")
        assertTrue(
            "Missing required system property room.schemaDir; configure it to the Room schemas directory",
            !schemaDirectory.isNullOrBlank(),
        )
        val schemaFile = File(
            File(schemaDirectory!!, JarvysChatDatabase::class.java.name),
            "1.json",
        )
        assertTrue("Exported Room schema JSON is missing: ${schemaFile.absolutePath}", schemaFile.isFile)

        val exportedDatabase = JSONObject(schemaFile.readText(Charsets.UTF_8)).getJSONObject("database")
        assertEquals("Exported schema version", 1, exportedDatabase.getInt("version"))
        assertEquals("Pinned v1 identity hash", EXPECTED_SCHEMA_V1_IDENTITY_HASH, exportedDatabase.getString("identityHash"))

        val sqlite = database.openHelper.writableDatabase
        assertEquals("Room database version", 1L, pragmaLong(sqlite, "user_version"))
        assertEquals(
            "Room identity hash",
            EXPECTED_SCHEMA_V1_IDENTITY_HASH,
            scalarString(sqlite, "SELECT identity_hash FROM room_master_table WHERE id=42"),
        )
        assertExportedSchemaMatchesDatabase(exportedDatabase, sqlite)
    }

    @Test
    fun entityInsertsDoNotUseReplaceConflictStrategy() {
        val schemaDirectory = System.getProperty("room.schemaDir")
        assertTrue("Missing room.schemaDir", !schemaDirectory.isNullOrBlank())
        val moduleDirectory = File(schemaDirectory!!).parentFile
        val sourceDirectories = listOf(
            File(moduleDirectory, "src/main/java/com/jarvys/agent/chatdb"),
            File(moduleDirectory, "src/test/java/com/jarvys/agent/chatdb"),
        )
        sourceDirectories.forEach { sourceDirectory ->
            assertTrue("Missing chat database source directory: ${sourceDirectory.absolutePath}", sourceDirectory.isDirectory)
        }
        val annotationStart = "@" + "Insert"
        val insertAnnotation = Regex(Regex.escape(annotationStart) + "(?:\\s*\\((?:[^()]|\\([^()]*\\))*\\))?")
        val conflictReplace = Regex("\\bonConflict\\s*=\\s*(?:[\\w.]*REPLACE\\b|1\\b)")
        val rawReplace = Regex("\\bINSERT\\s+OR\\s+" + "REPLACE\\b|\\b" + "REPLACE\\s+INTO\\b", RegexOption.IGNORE_CASE)
        val ddlReplace = Regex("\\bON\\s+CONFLICT\\s+" + "RE" + "PLACE\\b", RegexOption.IGNORE_CASE)
        val sqliteInsertReplace = Regex(
            "\\.insert\\s*\\([^,]+,\\s*(?:5|SQLiteDatabase\\.CONFLICT_" + "REPLACE)\\s*,|" +
                "\\.insertWithOnConflict\\s*\\([^,]+,[^,]+,[^,]+,\\s*(?:5|SQLiteDatabase\\.CONFLICT_" + "REPLACE)\\s*\\)",
            RegexOption.IGNORE_CASE,
        )
        val allowMarker = "allowed-replace-fixture"
        val violations = sourceDirectories.flatMapIndexed { sourceIndex, sourceDirectory ->
            val isTestSource = sourceIndex == 1
            sourceDirectory.walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "java") }
                .flatMap { sourceFile ->
                    val source = sourceFile.readText(Charsets.UTF_8)
                    val lines = source.lines()
                    val annotationViolations = insertAnnotation.findAll(source)
                        .filter { conflictReplace.containsMatchIn(it.value) }
                        .map { "${sourceFile.name}:${source.substring(0, it.range.first).count { ch -> ch == '\n' } + 1}: @Insert REPLACE" }
                        .toList()
                    val sqlViolations = rawReplace.findAll(source).mapNotNull { match ->
                        val lineNumber = source.substring(0, match.range.first).count { ch -> ch == '\n' }
                        val line = lines.getOrElse(lineNumber) { "" }
                        val precedingLine = lines.getOrElse(lineNumber - 1) { "" }
                        if (isTestSource && (allowMarker in line || allowMarker in precedingLine)) null
                        else "${sourceFile.name}:${lineNumber + 1}: ${match.value}"
                    }.toList()
                    val ddlViolations = ddlReplace.findAll(source).map { match ->
                        val lineNumber = source.substring(0, match.range.first).count { ch -> ch == '\n' }
                        "${sourceFile.name}:${lineNumber + 1}: ${match.value}"
                    }.toList()
                    val sqliteApiViolations = sqliteInsertReplace.findAll(source).map { match ->
                        val lineNumber = source.substring(0, match.range.first).count { ch -> ch == '\n' }
                        "${sourceFile.name}:${lineNumber + 1}: SQLiteDatabase.${match.value}"
                    }.toList()
                    (annotationViolations + sqlViolations + ddlViolations + sqliteApiViolations).asSequence()
                }
                .toList()
        }
        assertTrue("REPLACE can delete a parent and cascade children; test-only fixtures must be marked: $violations", violations.isEmpty())
    }

    @Test
    fun m1aDaosRemainInternal() {
        val schemaDirectory = System.getProperty("room.schemaDir")
        assertTrue("Missing room.schemaDir", !schemaDirectory.isNullOrBlank())
        val moduleDirectory = File(schemaDirectory!!).parentFile
        val daoSource = File(moduleDirectory, "src/main/java/com/jarvys/agent/chatdb/Daos.kt")
        assertTrue("Daos.kt source must be available to audit", daoSource.isFile)

        val declarations = Regex("(?m)^\\s*(?:internal\\s+)?interface\\s+(\\w+)")
            .findAll(daoSource.readText(Charsets.UTF_8))
            .associate { it.groupValues[1] to it.value.trimStart().startsWith("internal interface") }
        assertEquals(setOf("ConversationDao", "ConversationItemDao", "MessageDao"), declarations.keys)
        assertTrue("M1a DAOs are implementation details and must remain internal: $declarations", declarations.values.all { it })
    }

    @Test
    fun strictJournalTextRoundTripsUnpairedUtf16CodeUnitsAsBlob() = onDatabaseThread {
        val id = "utf16"
        database.conversationDao().insertIfNotTombstoned(conversation(id))
        database.conversationItemDao().insert(ConversationItem(id, 1, "crew_result_evidence", "e1", 1, created_at = 1.0))
        val exactText = String(charArrayOf('A', '\uD800', 'B', '\uDC00', 'Z'))
        val expectedBytes = ExactUtf16.encode(exactText)
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO crew_result_evidence(evidence_id,conversation_id,seq,schema_version,mission_id,bot_id,cycle_id,reported_status,result_text,produced_at_ms,content_sha256) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf("e1", id, 1L, 1, "mission-1", "bot-1", "cycle-1", "completed", expectedBytes, 123L, "sha256")
        )
        val sqlite = database.openHelper.writableDatabase
        val storedType = sqlite.query("PRAGMA table_info(crew_result_evidence)").use { cursor ->
            var type: String? = null
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "result_text") {
                    type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                }
            }
            type
        }
        assertEquals("BLOB", storedType)
        val storedBytes = sqlite.query("SELECT result_text FROM crew_result_evidence WHERE evidence_id='e1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getBlob(0)
        }
        assertArrayEquals(expectedBytes, storedBytes)
        assertEquals(exactText.toCharArray().map(Char::code), ExactUtf16.decode(storedBytes).toCharArray().map(Char::code))
    }

    @Test
    fun indexedTailReadStaysWithinHalfSecondAfterFiftyThousandMessages() = onDatabaseThread {
        val id = "bench-50k"
        database.conversationDao().insertIfNotTombstoned(conversation(id))
        val items = (1L..50_000L).map { seq -> ConversationItem(id, seq, "message", "m$seq", seq, created_at = seq.toDouble()) }
        val messages = (1L..50_000L).map { seq -> message(id, "m$seq", "stable-$seq", seq, seq - 1) }
        database.withTransaction {
            database.conversationItemDao().insertAll(items)
            database.messageDao().insertAll(messages)
        }

        val dao = database.conversationItemDao()
        assertEquals(40, dao.queryTail(id, 40).size)
        val elapsedMillis = measureNanoTime { assertEquals(40, dao.queryTail(id, 40).size) } / 1_000_000.0
        assertTrue("Indexed tail read took ${elapsedMillis}ms", elapsedMillis < 500.0)
    }

    private fun conversation(id: String) = Conversation(
        id = id,
        created_at = 1L,
        updated_at = 1L,
    )

    private fun message(
        conversationId: String,
        messageId: String,
        stableId: String,
        seq: Long,
        messageIndex: Long,
        proactiveDecisionId: String? = null,
    ) = Message(
        conversation_id = conversationId,
        message_id = messageId,
        stable_id = stableId,
        seq = seq,
        message_index = messageIndex,
        role = "user",
        proactive_decision_id = proactiveDecisionId,
        created_at = seq.toDouble(),
        updated_at = seq.toDouble(),
    )

    private fun pragmaLong(database: SupportSQLiteDatabase, name: String): Long =
        database.query("PRAGMA $name").use { cursor ->
            assertTrue("PRAGMA $name returned no row", cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun pragmaString(database: SupportSQLiteDatabase, name: String): String =
        database.query("PRAGMA $name").use { cursor ->
            assertTrue("PRAGMA $name returned no row", cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun rowCount(database: SupportSQLiteDatabase, table: String): Long =
        database.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private data class SchemaColumn(
        val name: String,
        val affinity: String,
        val notNull: Boolean,
        val primaryKeyPosition: Int,
        val defaultValue: String?,
    )

    private data class SchemaIndex(val name: String, val unique: Boolean, val columns: List<String>)

    private data class SchemaForeignKey(
        val table: String,
        val columns: List<String>,
        val referencedColumns: List<String>,
        val onDelete: String,
        val onUpdate: String,
    )

    private data class SchemaForeignKeyPart(
        val sequence: Int,
        val column: String,
        val referencedColumn: String,
    )

    private data class SchemaForeignKeyRows(
        val table: String,
        val onDelete: String,
        val onUpdate: String,
        val parts: MutableList<SchemaForeignKeyPart> = mutableListOf(),
    )

    private fun assertExportedSchemaMatchesDatabase(
        exportedDatabase: JSONObject,
        sqlite: SupportSQLiteDatabase,
    ) {
        val expectedEntities = linkedMapOf<String, JSONObject>()
        val entities = exportedDatabase.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")
            assertTrue("Duplicate exported entity table $table", expectedEntities.put(table, entity) == null)
        }

        val actualTables = sqlite.query(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata', 'room_master_table')",
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
        assertEquals("Exported entity tables differ from the Room-created database", expectedEntities.keys, actualTables)

        expectedEntities.forEach { (table, entity) ->
            assertEquals("Column definitions for $table", exportedColumns(entity), databaseColumns(sqlite, table))
            assertEquals("Indexes for $table", exportedIndexes(entity), databaseIndexes(sqlite, table))
            assertEquals("Foreign keys for $table", exportedForeignKeys(entity), databaseForeignKeys(sqlite, table))
        }
    }

    private fun exportedColumns(entity: JSONObject): List<SchemaColumn> {
        val primaryKeyColumns = entity.getJSONObject("primaryKey").getJSONArray("columnNames")
        val primaryKeyPositions = (0 until primaryKeyColumns.length()).associate { index ->
            primaryKeyColumns.getString(index) to (index + 1)
        }
        val fields = entity.getJSONArray("fields")
        return (0 until fields.length()).map { index ->
            val field = fields.getJSONObject(index)
            val name = field.getString("columnName")
            SchemaColumn(
                name = name,
                affinity = sqliteAffinity(field.getString("affinity")),
                notNull = field.optBoolean("notNull", false),
                primaryKeyPosition = primaryKeyPositions[name] ?: 0,
                defaultValue = normalizeSqlDefault(
                    if (field.has("defaultValue") && !field.isNull("defaultValue")) field.getString("defaultValue") else null,
                ),
            )
        }
    }

    private fun databaseColumns(sqlite: SupportSQLiteDatabase, table: String): List<SchemaColumn> =
        sqlite.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val defaultColumn = cursor.getColumnIndexOrThrow("dflt_value")
                    add(
                        SchemaColumn(
                            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            affinity = sqliteAffinity(cursor.getString(cursor.getColumnIndexOrThrow("type"))),
                            notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) != 0,
                            primaryKeyPosition = cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
                            defaultValue = normalizeSqlDefault(
                                if (cursor.isNull(defaultColumn)) null else cursor.getString(defaultColumn),
                            ),
                        ),
                    )
                }
            }
        }

    private fun exportedIndexes(entity: JSONObject): Set<SchemaIndex> {
        val indices = entity.optJSONArray("indices") ?: JSONArray()
        return buildSet {
            for (index in 0 until indices.length()) {
                val exportedIndex = indices.getJSONObject(index)
                add(
                    SchemaIndex(
                        name = exportedIndex.getString("name"),
                        unique = exportedIndex.getBoolean("unique"),
                        columns = exportedIndex.getJSONArray("columnNames").stringValues(),
                    ),
                )
            }
        }
    }

    private fun databaseIndexes(sqlite: SupportSQLiteDatabase, table: String): Set<SchemaIndex> {
        val indexNames = sqlite.query("PRAGMA index_list(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    // SQLite creates these implementation indexes for primary/unique constraints;
                    // Room's exported indices describe the named indexes declared by the entity.
                    if (!name.startsWith("sqlite_autoindex_")) {
                        add(name to (cursor.getInt(cursor.getColumnIndexOrThrow("unique")) != 0))
                    }
                }
            }
        }
        return indexNames.mapTo(mutableSetOf()) { (name, unique) ->
            val columns = sqlite.query("PRAGMA index_info(`$name`)").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getInt(cursor.getColumnIndexOrThrow("seqno")) to
                            cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    }
                }.sortedBy { it.first }.map { it.second }
            }
            SchemaIndex(name, unique, columns)
        }
    }

    private fun exportedForeignKeys(entity: JSONObject): Set<SchemaForeignKey> {
        val foreignKeys = entity.optJSONArray("foreignKeys") ?: JSONArray()
        return buildSet {
            for (index in 0 until foreignKeys.length()) {
                val foreignKey = foreignKeys.getJSONObject(index)
                add(
                    SchemaForeignKey(
                        table = foreignKey.getString("table"),
                        columns = foreignKey.getJSONArray("columns").stringValues(),
                        referencedColumns = foreignKey.getJSONArray("referencedColumns").stringValues(),
                        onDelete = foreignKey.getString("onDelete").uppercase(),
                        onUpdate = foreignKey.getString("onUpdate").uppercase(),
                    ),
                )
            }
        }
    }

    private fun databaseForeignKeys(sqlite: SupportSQLiteDatabase, table: String): Set<SchemaForeignKey> {
        val grouped = linkedMapOf<Int, SchemaForeignKeyRows>()
        sqlite.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getInt(cursor.getColumnIndexOrThrow("id"))
                val foreignKey = grouped.getOrPut(id) {
                    SchemaForeignKeyRows(
                        table = cursor.getString(cursor.getColumnIndexOrThrow("table")),
                        onDelete = cursor.getString(cursor.getColumnIndexOrThrow("on_delete")).uppercase(),
                        onUpdate = cursor.getString(cursor.getColumnIndexOrThrow("on_update")).uppercase(),
                    )
                }
                foreignKey.parts += SchemaForeignKeyPart(
                    sequence = cursor.getInt(cursor.getColumnIndexOrThrow("seq")),
                    column = cursor.getString(cursor.getColumnIndexOrThrow("from")),
                    referencedColumn = cursor.getString(cursor.getColumnIndexOrThrow("to")),
                )
            }
        }
        return grouped.values.mapTo(mutableSetOf()) { foreignKey ->
            val parts = foreignKey.parts.sortedBy { it.sequence }
            SchemaForeignKey(
                table = foreignKey.table,
                columns = parts.map { it.column },
                referencedColumns = parts.map { it.referencedColumn },
                onDelete = foreignKey.onDelete,
                onUpdate = foreignKey.onUpdate,
            )
        }
    }

    private fun JSONArray.stringValues(): List<String> =
        List(length()) { index -> getString(index) }

    private fun sqliteAffinity(declaredType: String): String {
        val type = declaredType.trim().uppercase()
        return when {
            "INT" in type -> "INTEGER"
            "CHAR" in type || "CLOB" in type || "TEXT" in type -> "TEXT"
            type.isEmpty() || "BLOB" in type -> "BLOB"
            "REAL" in type || "FLOA" in type || "DOUB" in type -> "REAL"
            else -> "NUMERIC"
        }
    }

    private fun normalizeSqlDefault(value: String?): String? {
        var normalized = value?.trim() ?: return null
        while (normalized.length >= 2 && normalized.first() == '(' && normalized.last() == ')') {
            var depth = 0
            var enclosesWholeExpression = true
            normalized.forEachIndexed { index, character ->
                when (character) {
                    '(' -> depth++
                    ')' -> depth--
                }
                if (depth == 0 && index != normalized.lastIndex) enclosesWholeExpression = false
            }
            if (!enclosesWholeExpression) break
            normalized = normalized.substring(1, normalized.lastIndex).trim()
        }
        return if (normalized.startsWith("'") || normalized.startsWith("X'")) normalized else normalized.uppercase()
    }

    private data class SqlColumn(val type: String, val notNull: Int, val primaryKeyPosition: Int)
    private data class SqlForeignKey(
        val id: Int,
        val parentTable: String,
        val columns: List<Pair<String, String>>,
    )

    /** Inserts one valid fixture row into every entity table whose rows belong to this conversation. */
    private suspend fun seedEveryConversationChild(
        sqlite: SupportSQLiteDatabase,
        conversationId: String,
    ): Map<String, Map<String, Any?>> {
        database.conversationDao().insertIfNotTombstoned(conversation(conversationId))
        val rows = linkedMapOf<String, Map<String, Any?>>()
        rows["conversations"] = linkedMapOf("id" to conversationId)
        val item = ConversationItem(conversationId, 1, "fixture", "fixture-row", 1, created_at = 98_765.4321)
        database.conversationItemDao().insert(item)
        rows["conversation_items"] = linkedMapOf("conversation_id" to conversationId, "seq" to 1L)

        val fileRef = linkedMapOf<String, Any?>(
            "id" to "file_refs_id", "sha256" to "file_refs_sha256", "relative_path" to "test-artifact.bin",
            "bytes" to 3L, "last_referenced_at" to 1L, "attempts" to 0, "generation" to 0L,
        )
        insertRow(sqlite, "file_refs", fileRef)
        rows["file_refs"] = fileRef

        val pending = (JarvysChatDatabase.ENTITY_TABLE_NAMES - setOf(
            "conversations", "conversation_items", "tombstones", "storage_meta", "file_refs",
        )).toMutableSet()
        while (pending.isNotEmpty()) {
            val before = pending.size
            for (table in pending.toList()) {
                val foreignKeys = foreignKeys(sqlite, table)
                if (foreignKeys.any { it.parentTable !in rows }) continue
                val row = genericEntityRow(sqlite, table, rows, foreignKeys)
                insertRow(sqlite, table, row)
                rows[table] = row
                pending.remove(table)
            }
            check(pending.size < before) {
                "Could not seed entity rows because of an unresolved FK dependency: ${pending.sorted()}"
            }
        }
        return rows
    }

    private fun genericEntityRow(
        sqlite: SupportSQLiteDatabase,
        table: String,
        parentRows: Map<String, Map<String, Any?>>,
        foreignKeys: List<SqlForeignKey>,
    ): Map<String, Any?> {
        val columns = tableColumns(sqlite, table)
        val uniqueColumns = uniqueIndexColumns(sqlite, table)
        val foreignKeyColumns = foreignKeys.flatMap { it.columns }.map { it.first }.toSet()
        val included = columns.filter { (name, column) ->
            column.notNull != 0 || column.primaryKeyPosition != 0 || name in uniqueColumns || name in foreignKeyColumns
        }.keys
        val values = linkedMapOf<String, Any?>()
        foreignKeys.forEach { foreignKey ->
            val parent = parentRows.getValue(foreignKey.parentTable)
            foreignKey.columns.forEach { (child, parentColumn) ->
                val value = parent[parentColumn]
                    ?: error("Fixture parent ${foreignKey.parentTable}.$parentColumn has no key value")
                val previous = values.put(child, value)
                check(previous == null || previous == value) { "Conflicting fixture values for $table.$child" }
            }
        }
        included.forEach { name ->
            if (name !in values) {
                values[name] = genericColumnValue(table, name, columns.getValue(name).type)
            }
        }
        return values
    }

    private fun genericColumnValue(table: String, name: String, type: String): Any? = when {
        table == "generation_run" && name == "state" -> "complete"
        table == "workspace_coordination_replies" && name == "state" -> "PREPARED"
        table == "workspace_coordination_reply_transitions" && name == "state" -> "STARTED"
        name == "active_target_key" -> null
        name == "is_notice" -> 1
        name == "schema_version" -> 1
        name == "lifecycle" -> "RESULT"
        name == "source_kind" -> "WORKSPACE_REPLY"
        name == "transport_kind" -> "USER"
        name == "created_at" && type.equals("REAL", true) -> 98_765.4321
        type.equals("BLOB", true) -> byteArrayOf(65, 0, 66)
        type.equals("REAL", true) -> 98_765.4321
        type.contains("INT", ignoreCase = true) -> when (name) {
            "ordinal", "sort_sub", "schema_version" -> 0
            "memory_disabled", "allow_dismiss", "assistant_progress", "preview_expected", "resume_required", "can_resume" -> 1
            else -> 1L
        }
        else -> "${table}_$name"
    }

    private fun insertRow(sqlite: SupportSQLiteDatabase, table: String, row: Map<String, Any?>) {
        val columns = row.keys.toList()
        val placeholders = List(columns.size) { "?" }.joinToString(",")
        val columnSql = columns.joinToString(",") { "`$it`" }
        sqlite.execSQL("INSERT INTO `$table`($columnSql) VALUES($placeholders)", columns.map { row[it] }.toTypedArray())
    }

    private fun tableColumns(sqlite: SupportSQLiteDatabase, table: String): Map<String, SqlColumn> =
        sqlite.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    put(name, SqlColumn(
                        type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                        notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")),
                        primaryKeyPosition = cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
                    ))
                }
            }
        }

    private fun primaryKeyColumns(sqlite: SupportSQLiteDatabase, table: String): List<String> =
        tableColumns(sqlite, table).entries
            .filter { it.value.primaryKeyPosition > 0 }
            .sortedBy { it.value.primaryKeyPosition }
            .map { it.key }

    private fun uniqueIndexColumns(sqlite: SupportSQLiteDatabase, table: String): Set<String> {
        val names = sqlite.query("PRAGMA index_list(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getInt(cursor.getColumnIndexOrThrow("unique")) != 0) {
                        add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    }
                }
            }
        }
        return names.flatMap { index ->
            sqlite.query("PRAGMA index_info(`$index`)").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        cursor.getString(cursor.getColumnIndexOrThrow("name"))?.let(::add)
                    }
                }
            }
        }.toSet()
    }

    private fun foreignKeys(sqlite: SupportSQLiteDatabase, table: String): List<SqlForeignKey> {
        val grouped = linkedMapOf<Int, Pair<String, MutableList<Pair<Int, Pair<String, String>>>>>()
        sqlite.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getInt(cursor.getColumnIndexOrThrow("id"))
                val parentTable = cursor.getString(cursor.getColumnIndexOrThrow("table"))
                val sequence = cursor.getInt(cursor.getColumnIndexOrThrow("seq"))
                val child = cursor.getString(cursor.getColumnIndexOrThrow("from"))
                val parent = cursor.getString(cursor.getColumnIndexOrThrow("to"))
                val entry = grouped.getOrPut(id) { parentTable to mutableListOf() }
                entry.second += sequence to (child to parent)
            }
        }
        return grouped.map { (id, parentAndColumns) ->
            SqlForeignKey(id, parentAndColumns.first, parentAndColumns.second.sortedBy { it.first }.map { it.second })
        }
    }

    private fun orphanValue(table: String, column: String, type: String, current: Any?): Any = when {
        type.equals("BLOB", true) -> byteArrayOf(90, 91)
        type.equals("REAL", true) -> -999.75
        type.contains("INT", true) -> (current as? Number)?.toLong()?.plus(1_000_000L) ?: -999_999L
        else -> "orphan_${table}_${column}"
    }

    private fun assertEmptyForeignKeyCheck(sqlite: SupportSQLiteDatabase) {
        sqlite.query("PRAGMA foreign_key_check").use { cursor ->
            assertTrue("PRAGMA foreign_key_check returned a violation", !cursor.moveToFirst())
        }
    }

    private inline fun assertConstraint(label: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertConstraintFailure(label, failure)
    }

    private inline fun assertConstraintMessage(label: String, fragment: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertConstraintFailure(label, failure)
        var cause: Throwable? = failure
        while (cause != null && cause !is SQLiteConstraintException) cause = cause.cause
        assertTrue("Expected $label to be rejected by its trigger ($fragment), got $cause", cause?.message?.contains(fragment) == true)
    }

    private fun assertConstraintFailure(label: String, failure: Throwable?) {
        assertNotNull("Expected SQLite constraint failure for $label", failure)
        var cause: Throwable? = failure
        while (cause != null && cause !is SQLiteConstraintException) cause = cause.cause
        assertNotNull("Expected SQLiteConstraintException for $label but got $failure", cause)
    }

    private fun scalarLong(sqlite: SupportSQLiteDatabase, sql: String, args: Array<out Any?> = emptyArray()): Long =
        sqlite.query(sql, args).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun scalarDouble(sqlite: SupportSQLiteDatabase, sql: String): Double =
        sqlite.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getDouble(0)
        }

    private fun scalarString(sqlite: SupportSQLiteDatabase, sql: String): String =
        sqlite.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun scalarNullableString(sqlite: SupportSQLiteDatabase, sql: String): String? =
        sqlite.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    private fun scalarBlob(sqlite: SupportSQLiteDatabase, sql: String): ByteArray =
        sqlite.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getBlob(0)
        }

    private fun assertConstraint(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertConstraintFailure(failure)
    }

    private suspend fun assertSuspendConstraint(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertConstraintFailure(failure)
    }

    private fun assertConstraintFailure(failure: Throwable?) {
        assertNotNull("Expected a SQLite uniqueness/primary-key exception", failure)
        var cause: Throwable? = failure
        while (cause != null && cause !is SQLiteConstraintException) cause = cause.cause
        assertNotNull("Expected SQLiteConstraintException but got $failure", cause)
    }
}
