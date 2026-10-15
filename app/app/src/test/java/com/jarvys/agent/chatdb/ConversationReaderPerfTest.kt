package com.jarvys.agent.chatdb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConversationReaderPerfTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val databaseExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "chatdb-reader-perf-test").apply { isDaemon = true }
    }
    private lateinit var database: JarvysChatDatabase

    @Before fun openDatabase() {
        database = JarvysChatDatabaseProvider.openDatabaseFileForTests(context, File(temporary.root, "reader-perf.db"))
    }

    @After fun closeDatabase() {
        if (::database.isInitialized) JarvysChatDatabaseProvider.closeDatabaseFileForTests(database)
        databaseExecutor.shutdownNow()
    }

    private fun <T> onDatabaseThread(block: suspend () -> T): T =
        databaseExecutor.submit<T> { runBlocking { block() } }.get(60, TimeUnit.SECONDS)

    @Test fun chatOpenTailUsesIndexedBoundedQueriesOnFiftyThousandMessages() = onDatabaseThread {
        val conversationId = "reader-perf-50k"
        val messageCount = 50_000L
        database.conversationDao().insertIfNotTombstoned(Conversation(
            id = conversationId,
            created_at = 1L,
            updated_at = messageCount,
            last_message_at = messageCount,
            message_count = messageCount,
            next_seq = messageCount + 1,
        ))
        val items = (1L..messageCount).map { seq ->
            ConversationItem(conversationId, seq, "message", "m$seq", seq, created_at = seq.toDouble())
        }
        val messages = (1L..messageCount).map { seq ->
            Message(
                conversation_id = conversationId,
                message_id = "m$seq",
                stable_id = "stable-$seq",
                seq = seq,
                message_index = seq - 1,
                role = if (seq % 2L == 1L) "user" else "assistant",
                owner_user_message_id = if (seq % 2L == 0L) "m${seq - 1}" else null,
                created_at = seq.toDouble(),
                updated_at = seq.toDouble(),
            )
        }
        database.withChatWriteTransaction {
            database.conversationItemDao().insertAll(items)
            database.messageDao().insertAll(messages)
        }

        val plan = database.testChatSql.query(
            "EXPLAIN QUERY PLAN SELECT seq FROM conversation_items INDEXED BY idx_conversation_items_sort " +
                "WHERE conversation_id=? ORDER BY sort_seq DESC,sort_sub DESC,seq DESC LIMIT ?",
            arrayOf(conversationId, 80),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) } }
        assertTrue("tail scan uses the sort index", plan.any { it.contains("idx_conversation_items_sort") })
        assertFalse("tail scan does not sort a full conversation", plan.any { it.contains("USE TEMP B-TREE") })

        val reader = ConversationReader(database)
        assertEquals(80, reader.readTailWindow(conversationId, 40).slots.size)
        val statementCount = AtomicInteger()
        val startedAt = System.nanoTime()
        ChatSqlInstrumentation.observeStatements({ statementCount.incrementAndGet() }) {
            val window = reader.readTailWindow(conversationId, 40)
            assertEquals(80, window.slots.size)
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue("opening-chat tail read uses the seven expected index/hydration queries, without per-message SQL", statementCount.get() <= 7)
        assertTrue("opening chat tail window should stay below 200 ms in JVM; took ${elapsedMillis}ms", elapsedMillis < 200L)
    }
}
