package com.jarvys.agent.chatdb

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTransaction
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
import kotlin.system.measureNanoTime

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
        database.conversationDao().insert(conversation(id))
        database.conversationItemDao().insert(ConversationItem(id, 1, "message", "m1", 1))
        database.messageDao().insert(message(id, "m1", "stable-m1", 1, 0))
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "INSERT INTO message_parts(conversation_id,message_id,revision,ordinal,kind,payload) VALUES(?,?,?,?,?,?)",
            arrayOf(id, "m1", 0L, 0, MessagePartKinds.TEXT, "hello")
        )
        sqlite.execSQL(
            "INSERT INTO message_attachments(conversation_id,message_id,ordinal,attachment_id,name,mime_type,size_bytes,kind,relative_path,sha256,file_ref_id) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(id, "m1", 0, "a1", "note.txt", "text/plain", 5L, "file", "attachments/note.txt", "hash-a1", null)
        )
        sqlite.execSQL(
            "INSERT INTO message_translations(conversation_id,message_id,seq,language,content) VALUES(?,?,?,?,?)",
            arrayOf(id, "m1", 2L, "es", "hola")
        )
        sqlite.execSQL(
            "INSERT INTO proactive_suggested_replies(conversation_id,message_id,ordinal,label,text) VALUES(?,?,?,?,?)",
            arrayOf(id, "m1", 0, "Yes", "Yes")
        )
        sqlite.execSQL(
            "INSERT INTO tool_batches(conversation_id,batch_id,seq,message_index,assistant_text,assistant_progress) VALUES(?,?,?,?,?,?)",
            arrayOf(id, "b1", 3L, 0L, "working", 1)
        )
        sqlite.execSQL(
            "INSERT INTO tool_calls(conversation_id,batch_id,ordinal,call_id,name,arguments_json,lifecycle,lifecycle_seq) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(id, "b1", 0, "c1", "lookup", "{}", "STARTED", 3L)
        )
        sqlite.execSQL(
            "INSERT INTO tool_results(conversation_id,batch_id,call_id,seq,tool_name,lifecycle,output) VALUES(?,?,?,?,?,?,?)",
            arrayOf(id, "b1", "c1", 4L, "lookup", "RESULT", "done")
        )
        sqlite.execSQL(
            "INSERT INTO model_errors(conversation_id,seq,message_index,error_code) VALUES(?,?,?,?)",
            arrayOf(id, 5L, 0L, "test_error")
        )
        sqlite.execSQL(
            "INSERT INTO generated_images(conversation_id,seq,prompt,status) VALUES(?,?,?,?)",
            arrayOf(id, 6L, "a small test image", "FAILED")
        )

        listOf(
            "conversation_items", "messages", "message_parts", "message_attachments", "message_translations",
            "proactive_suggested_replies", "tool_batches", "tool_calls", "tool_results", "model_errors", "generated_images"
        ).forEach { assertEquals("fixture row in $it", 1L, rowCount(sqlite, it)) }

        assertEquals(1, database.conversationDao().deleteById(id))
        listOf(
            "conversation_items", "messages", "message_parts", "message_attachments", "message_translations",
            "proactive_suggested_replies", "tool_batches", "tool_calls", "tool_results", "model_errors", "generated_images"
        ).forEach { assertEquals("CASCADE did not clear $it", 0L, rowCount(sqlite, it)) }
    }

    @Test
    fun declaredPrimaryAndUniqueKeysRejectDuplicates() = onDatabaseThread {
        database.conversationDao().insert(conversation("unique"))
        database.conversationItemDao().insert(ConversationItem("unique", 1, "message", "m1", 1))
        assertSuspendConstraint {
            database.conversationItemDao().insert(ConversationItem("unique", 1, "message", "other", 1))
        }

        database.messageDao().insert(message("unique", "m1", "stable-1", 1, 0))
        assertSuspendConstraint {
            database.messageDao().insert(message("unique", "m1", "stable-duplicate-id", 2, 1))
        }
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO message_parts(conversation_id,message_id,revision,ordinal,kind,payload) VALUES(?,?,?,?,?,?)",
            arrayOf("unique", "m1", 0L, 0, MessagePartKinds.TEXT, "first")
        )
        assertConstraint {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO message_parts(conversation_id,message_id,revision,ordinal,kind,payload) VALUES(?,?,?,?,?,?)",
                arrayOf("unique", "m1", 0L, 0, MessagePartKinds.TEXT, "duplicate ordinal")
            )
        }
        assertSuspendConstraint {
            database.messageDao().insert(message("unique", "m2", "stable-1", 2, 1))
        }
        database.messageDao().insert(message("unique", "m2", "stable-2", 2, 1, proactiveDecisionId = "decision-1"))
        assertSuspendConstraint {
            database.messageDao().insert(message("unique", "m3", "stable-3", 3, 2, proactiveDecisionId = "decision-1"))
        }
    }

    @Test
    fun itemKeysetPagesCoverFiveHundredRowsWithoutGapsOrDuplicates() = onDatabaseThread {
        val id = "pages"
        database.conversationDao().insert(conversation(id))
        val items = (1L..500L).map { seq -> ConversationItem(id, seq, "message", "m$seq", seq) }
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
        database.conversationDao().insert(conversation(id))
        assertEquals(1, database.conversationDao().tombstone(id, 99L))
        assertSuspendConstraint { database.conversationDao().insert(conversation(id)) }
        assertEquals(99L, database.conversationDao().getById(id)?.deleted_at)
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

    @Test
    fun migrationTestHelperCanCreateAndValidateExportedV1Schema() {
        val schemaAsset = "${JarvysChatDatabase::class.java.name}/1.json"
        val hasExportedSchema = runCatching { context.assets.open(schemaAsset).use { } }.isSuccess
        assumeTrue("KSP must first export $schemaAsset", hasExportedSchema)

        onDatabaseThreadBlocking {
            val helper = MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                JarvysChatDatabase::class.java,
                emptyList(),
                FrameworkSQLiteOpenHelperFactory()
            )
            val name = File(temporary.root, "migration-v1.db").absolutePath
            helper.createDatabase(name, 1).close()
            helper.runMigrationsAndValidate(name, 1, true).close()
        }
    }

    @Test
    fun strictJournalTextRoundTripsUnpairedUtf16CodeUnitsAsBlob() = onDatabaseThread {
        val id = "utf16"
        database.conversationDao().insert(conversation(id))
        val exactText = String(charArrayOf('A', '\uD800', 'B', '\uDC00', 'Z'))
        val expectedBytes = ExactUtf16.encode(exactText)
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO crew_result_evidence(evidence_id,conversation_id,seq,mission_id,bot_id,cycle_id,reported_status,result_text,produced_at_ms,content_sha256) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
            arrayOf("e1", id, 1L, "mission-1", "bot-1", "cycle-1", "completed", expectedBytes, 123L, "sha256")
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
        database.conversationDao().insert(conversation(id))
        val items = (1L..50_000L).map { seq -> ConversationItem(id, seq, "message", "m$seq", seq) }
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
