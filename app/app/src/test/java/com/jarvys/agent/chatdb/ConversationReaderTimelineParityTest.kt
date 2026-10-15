package com.jarvys.agent.chatdb

import com.jarvys.agent.ConversationReader
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.LocalRunStore
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConversationReaderTimelineParityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val databaseExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "chatdb-timeline-parity-test").apply { isDaemon = true }
    }
    private lateinit var database: JarvysChatDatabase

    @Before fun openDatabase() {
        database = JarvysChatDatabaseProvider.openDatabaseFileForTests(context, File(temporary.root, "timeline.db"))
    }

    @After fun closeDatabase() {
        if (::database.isInitialized) JarvysChatDatabaseProvider.closeDatabaseFileForTests(database)
        databaseExecutor.shutdownNow()
    }

    private fun <T> onDatabaseThread(block: suspend () -> T): T =
        databaseExecutor.submit<T> { runBlocking { block() } }.get(60, TimeUnit.SECONDS)

    @Test fun indexedUiTimelineMatchesLegacyProjectionForMessagesAndTranslations() = onDatabaseThread {
        val sessionId = "timeline-parity"
        val legacyFiles = File(temporary.root, "legacy-files")
        val legacy = legacyStore(legacyFiles)
        val ledger = File(legacyFiles, "jarvys/conversations/$sessionId.jsonl")
        val question = legacy.appendConversationMessage(sessionId, "user", "What is 🚀?")
        val answer = legacy.appendConversationMessage(sessionId, "assistant", "A reusable answer", 37L, "run-1", question, "COMPLETED")
        legacy.appendAssistantTranslation(sessionId, answer, "Spanish", "Primera respuesta")
        legacy.appendAssistantTranslation(sessionId, answer, "Spanish", "Respuesta más reciente")
        legacy.setMessageReaction(sessionId, question, "\uD83D\uDC4D")
        legacy.setMessageReaction(sessionId, question, "\u2764\uFE0F")

        val secondUser = legacy.appendConversationMessage(sessionId, "user", "A second question")
        val secondAssistant = legacy.appendConversationMessage(sessionId, "assistant", "A translated answer", null, "run-2", secondUser, "COMPLETED")
        legacy.appendAssistantTranslation(sessionId, secondAssistant, "French", "Bonjour")
        legacy.appendAssistantTranslationHidden(sessionId, secondAssistant)

        val invalidated = legacy.appendConversationMessage(sessionId, "assistant", "This response is hidden")
        legacy.appendAssistantDeleted(sessionId, invalidated)
        val thirdUser = legacy.appendConversationMessage(sessionId, "user", "Continue")
        legacy.appendConversationMessage(sessionId, "user", "x".repeat(3_001))

        val proactive = legacy.appendProactiveAssistantMessageIfAbsent(
            sessionId, "proactive-decision", "A suggestion", listOf(ProactiveSuggestedReply("Continue", "Continue")), "thread-1",
        )
        // Steering is represented by an assistant row inserted before an already appended user row.
        appendJsonLine(ledger, JSONObject()
            .put("role", "assistant")
            .put("content", "Steered response")
            .put("messageId", "steered-before-third-user")
            .put("beforeMessageId", thirdUser)
            .put("userMessageId", secondUser)
            .put("status", "COMPLETED")
            .put("timestamp", 1_700_000_010.0))
        assertTrue(proactive.isNotEmpty())
        // Unknown and malformed trailing rows are ignored by the timeline reader but still occupy ledger positions.
        appendJsonLine(ledger, JSONObject().put("type", "future_annotation").put("timestamp", 1_700_000_011.0))
        FileOutputStream(ledger, true).bufferedWriter(StandardCharsets.UTF_8).use { it.append("{damaged-row\n") }

        val report = LedgerImporter(database).importConversation(
            sessionId, ledger, File(legacyFiles, "jarvys/conversations/$sessionId.meta.json").apply { writeText("{}") },
        )
        assertEquals("done", report.state)

        val reader = ConversationReader(database)
        val expectedMessages = legacy.readConversationMessages(sessionId).map { row ->
            Triple(row.optString("messageId"), row.optString("role"), row.optString("content"))
        }
        val actualMessages = reader.readConversationMessages(sessionId).map { message ->
            Triple(message.messageId, message.role, message.text)
        }
        assertEquals("readConversationMessages preserves physical ledger order and message fields", expectedMessages, actualMessages)
        assertEquals("bounded active context preserves legacy truncation", legacy.readConversationContext(sessionId), reader.readConversationContext(sessionId))

        val expected = legacy.readConversationTimeline(sessionId)
        val actual = reader.readRecentConversationTimeline(sessionId, turns = 20).events
        // Legacy event IDs are byte-offset based for pages and encounter-order based for complete reads;
        // the DB contract uses the durable ledger seq so IDs remain unique across keyset pages.
        assertEquals(expected.map { it.copy(id = 0L) }, actual.map { it.copy(id = 0L) })
        assertEquals("last translation wins", "Respuesta más reciente", actual.single { it.kind == "assistant_translation" }.text)
        assertEquals("last reaction wins", "\u2764\uFE0F", actual.single { it.messageId == question }.reactionEmoji)
        assertTrue("deleted assistant stays hidden", actual.none { it.messageId == invalidated })

        val eventIds = actual.map(AgentRunUiEvent::id)
        assertEquals("durable item sequences uniquely key UI rows", eventIds.size, eventIds.toSet().size)
        val sequences = JarvysChatDatabaseProvider.onQueryExecutor(database) {
            database.withChatReadTransaction {
                actual.map { event ->
                    val expectedSeq = if (event.kind == "assistant_translation") {
                        database.chatSql.query(
                            "SELECT seq FROM message_translations WHERE conversation_id=? AND message_id=? AND language=? AND content=?",
                            arrayOf(sessionId, event.detail, event.stage, event.text),
                        ).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }
                    } else {
                        database.chatSql.query(
                            "SELECT seq FROM messages WHERE conversation_id=? AND message_id=?",
                            arrayOf(sessionId, event.messageId),
                        ).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }
                    }
                    event.id to expectedSeq
                }
            }
        }
        sequences.forEach { (eventId, expectedSeq) ->
            assertEquals("event id is the normalized item sequence", expectedSeq, eventId)
        }

        val allPages = mutableListOf<AgentRunUiEvent>()
        var page = reader.readRecentConversationTimeline(sessionId, turns = 1)
        allPages += page.events
        while (page.olderCursorSeq != null) {
            page = reader.readConversationTimelineBefore(sessionId, page.olderCursorSeq!!, turns = 1)
            allPages.addAll(0, page.events)
        }
        assertEquals(actual, allPages)
    }

    private fun legacyStore(filesDirectory: File): LocalRunStore = LocalRunStore::class.java
        .getDeclaredConstructor(File::class.java)
        .apply { isAccessible = true }
        .newInstance(filesDirectory)

    private fun appendJsonLine(file: File, row: JSONObject) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).bufferedWriter(StandardCharsets.UTF_8).use { it.append(row.toString()).append('\n') }
    }
}
