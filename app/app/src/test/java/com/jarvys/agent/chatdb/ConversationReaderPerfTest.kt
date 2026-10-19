package com.jarvys.agent.chatdb

import com.jarvys.agent.ConversationReader
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
import java.util.concurrent.atomic.AtomicReference
import java.util.Collections

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
        // Keep this chat-reader microbenchmark independent from optional memory-index setup.
        PersonalMemorySearchIndex.capabilityOverrideForTests = PersonalMemorySearchMode.LIKE
        database = JarvysChatDatabaseProvider.openDatabaseFileForTests(context, File(temporary.root, "reader-perf.db"))
    }

    @After fun closeDatabase() {
        if (::database.isInitialized) JarvysChatDatabaseProvider.closeDatabaseFileForTests(database)
        PersonalMemorySearchIndex.capabilityOverrideForTests = null
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
        val elapsedSamples = (1..5).map {
            val statementCount = AtomicInteger()
            val startedAt = System.nanoTime()
            ChatSqlInstrumentation.observeStatements({ statementCount.incrementAndGet() }) {
                val window = reader.readTailWindow(conversationId, 40)
                assertEquals(80, window.slots.size)
            }
            assertTrue("tail scan stays within the indexed query budget", statementCount.get() <= 7)
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        }
        val medianMillis = elapsedSamples.sorted()[elapsedSamples.size / 2]
        assertTrue("warmed chat tail window median should stay below 200 ms in JVM; samples=$elapsedSamples",
            medianMillis < 200L)
    }

    @Test fun timelineToolPayloadReadsScaleWithOnePageInALargeTranscript() = onDatabaseThread {
        val conversationId = "reader-perf-tool-pages"
        val repository = ConversationRepository(database)
        val transcript = ModelTranscriptRepository(database)
        success(repository.ensureConversation(conversationId))
        val firstUser = success(repository.appendMessage(AppendMessageInput(
            conversationId = conversationId,
            role = ConversationRepository.ROLE_USER,
            parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "historical prompt")),
        )))

        val historicalBatches = 350
        val nextSeq = database.testChatSql.query(
            "SELECT next_seq FROM conversations WHERE id=?", arrayOf(conversationId),
        ).use { c -> c.moveToFirst(); c.getLong(0) }
        val argumentPayload = "a".repeat(4_000)
        val resultPayload = "r".repeat(4_000)
        val providerPayload = "p".repeat(4_000)
        database.withChatWriteTransaction {
            fun bulkInsert(table: String, columns: List<String>, rows: List<List<Any?>>) {
                rows.chunked(60).forEach { chunk ->
                    val rowPlaceholder = "(" + columns.joinToString(",") { "?" } + ")"
                    val values = List(chunk.size) { rowPlaceholder }.joinToString(",")
                    database.chatSql.execSQL(
                        "INSERT INTO $table(${columns.joinToString(",")}) VALUES $values",
                        chunk.flatten().toTypedArray(),
                    )
                }
            }

            val itemRows = ArrayList<List<Any?>>(historicalBatches * 3)
            val batchRows = ArrayList<List<Any?>>(historicalBatches)
            val callRows = ArrayList<List<Any?>>(historicalBatches)
            val resultRows = ArrayList<List<Any?>>(historicalBatches)
            val providerRows = ArrayList<List<Any?>>(historicalBatches)
            repeat(historicalBatches) { index ->
                val batchId = "history-batch-$index"
                val batchSeq = nextSeq + index * 3L
                val providerSeq = batchSeq + 1L
                val resultSeq = batchSeq + 2L
                val callId = "history-call-$index"
                itemRows += listOf(conversationId, batchSeq, "model_tool_calls", batchId, batchSeq, 0, firstUser.seq, batchSeq.toDouble())
                itemRows += listOf(conversationId, providerSeq, "model_provider_output_items", firstUser.messageId, providerSeq, 0, firstUser.seq, providerSeq.toDouble())
                itemRows += listOf(conversationId, resultSeq, "model_tool_result", "v2|${batchId.length}:$batchId:${callId.length}:$callId", resultSeq, 0, firstUser.seq, resultSeq.toDouble())
                batchRows += listOf(conversationId, batchId, batchSeq, firstUser.messageId, 0L, "", 0, batchSeq.toDouble(), resultSeq, null, null, providerPayload)
                callRows += listOf(conversationId, batchId, 0, callId, "lookup", argumentPayload, "STARTED", batchSeq)
                resultRows += listOf(conversationId, batchId, callId, resultSeq, "lookup", "RESULT", resultPayload, resultSeq.toDouble(), null)
                providerRows += listOf(conversationId, providerSeq, firstUser.messageId, 0L, providerPayload)
            }
            bulkInsert("conversation_items", listOf("conversation_id", "seq", "item_kind", "item_key", "sort_seq", "sort_sub", "turn_anchor_seq", "created_at"), itemRows)
            bulkInsert("tool_batches", listOf("conversation_id", "batch_id", "seq", "owner_user_message_id", "message_index", "assistant_text", "assistant_progress", "created_at", "last_activity_seq", "calls_decode_error", "raw_calls", "provider_output_items_json"), batchRows)
            bulkInsert("tool_calls", listOf("conversation_id", "batch_id", "ordinal", "call_id", "name", "arguments_json", "lifecycle", "lifecycle_seq"), callRows)
            bulkInsert("tool_results", listOf("conversation_id", "batch_id", "call_id", "seq", "tool_name", "lifecycle", "output", "created_at", "file_artifact_id"), resultRows)
            bulkInsert("model_provider_outputs", listOf("conversation_id", "seq", "owner_user_message_id", "message_index", "items_json"), providerRows)
            database.chatSql.execSQL(
                "UPDATE conversations SET next_seq=? WHERE id=?",
                arrayOf(nextSeq + historicalBatches * 3L, conversationId),
            )
        }

        val latestUser = success(repository.appendMessage(AppendMessageInput(
            conversationId = conversationId,
            role = ConversationRepository.ROLE_USER,
            parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "current prompt")),
        )))
        success(transcript.appendToolBatch(ModelToolBatchInput(
            conversationId = conversationId,
            batchId = "current-page-batch",
            ownerUserMessageId = latestUser.messageId,
            messageIndex = 1,
            assistantText = "",
            assistantProgress = false,
            calls = listOf(ModelCallInput("current-page-call", "lookup", "{}")),
        )))
        success(transcript.appendToolResult(ModelToolResultInput(
            conversationId, "current-page-batch", "current-page-call", "lookup", "RESULT", "current page output",
        )))

        val pageStats = AtomicReference<Pair<Int, Long>>()
        val statements = Collections.synchronizedList(mutableListOf<String>())
        val reader = ConversationReader(database) { rows, bytes -> pageStats.set(rows to bytes) }
        val page = ChatSqlInstrumentation.observeStatements({ statements += it }) {
            reader.readRecentConversationTimeline(conversationId, 1)
        }
        assertEquals("current page output", page.events.single { it.kind == "tool" && it.stage == "tool_result" }.detail)
        val stats = pageStats.get()
        val totalRows = database.testChatSql.query(
            "SELECT (SELECT COUNT(*) FROM tool_batches WHERE conversation_id=?) + " +
                "(SELECT COUNT(*) FROM tool_calls WHERE conversation_id=?) + " +
                "(SELECT COUNT(*) FROM tool_results WHERE conversation_id=?) + " +
                "(SELECT COUNT(*) FROM model_provider_outputs WHERE conversation_id=?)",
            arrayOf(conversationId, conversationId, conversationId, conversationId),
        ).use { c -> c.moveToFirst(); c.getLong(0) }
        val totalPayloadBytes = database.testChatSql.query(
            "SELECT COALESCE((SELECT SUM(length(CAST(arguments_json AS BLOB))) FROM tool_calls WHERE conversation_id=?),0) + " +
                "COALESCE((SELECT SUM(length(CAST(output AS BLOB))) FROM tool_results WHERE conversation_id=?),0) + " +
                "COALESCE((SELECT SUM(length(CAST(provider_output_items_json AS BLOB))) FROM tool_batches WHERE conversation_id=?),0) + " +
                "COALESCE((SELECT SUM(length(CAST(items_json AS BLOB))) FROM model_provider_outputs WHERE conversation_id=?),0)",
            arrayOf(conversationId, conversationId, conversationId, conversationId),
        ).use { c -> c.moveToFirst(); c.getLong(0) }

        assertEquals("one batch, call, result reference, and page output are materialized", 6, stats.first)
        assertTrue("page tool rows are fewer than rows stored for the full transcript", stats.first < totalRows)
        assertTrue("page payload bytes are fewer than all opaque and result payloads", stats.second < totalPayloadBytes)
        assertTrue("the page query uses only referenced batch IDs", statements.any { it.contains("tool_batches") && it.contains("batch_id IN") })
        assertFalse("timeline page does not query opaque call arguments", statements.any { it.contains("arguments_json") })
        assertFalse("timeline page does not query provider output payloads", statements.any { it.contains("items_json") || it.contains("provider_output_items_json") })
        assertFalse("timeline page does not query model artifacts", statements.any { it.contains("model_artifacts") })
    }

    private fun <T> success(result: ChatDbResult<T>): T = when (result) {
        is ChatDbResult.Success -> result.value
        else -> error("Expected success, got $result")
    }
}
