package com.jarvys.agent.chatdb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ConversationReader
import com.jarvys.agent.ConversationTurn
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConversationReaderDatabaseTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val databaseExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "chatdb-reader-semantics-test").apply { isDaemon = true }
    }
    private lateinit var database: JarvysChatDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var memory: MemoryTimelineRepository
    private lateinit var model: ModelTranscriptRepository
    private lateinit var reader: ConversationReader

    @Before fun openDatabase() {
        database = JarvysChatDatabaseProvider.openDatabaseFileForTests(context, File(temporary.root, "reader-semantics.db"))
        conversations = ConversationRepository(database)
        memory = MemoryTimelineRepository(database)
        model = ModelTranscriptRepository(database)
        reader = ConversationReader(database)
    }

    @After fun closeDatabase() {
        if (::database.isInitialized) JarvysChatDatabaseProvider.closeDatabaseFileForTests(database)
        databaseExecutor.shutdownNow()
    }

    private fun <T> onDatabaseThread(block: suspend () -> T): T =
        databaseExecutor.submit<T> { runBlocking { block() } }.get(60, TimeUnit.SECONDS)

    private fun <T> success(result: ChatDbResult<T>): T = when (result) {
        is ChatDbResult.Success -> result.value
        else -> error("Expected success, got $result")
    }

    private suspend fun append(conversationId: String, role: String, text: String, owner: String? = null) =
        success(conversations.appendMessage(AppendMessageInput(
            conversationId = conversationId,
            role = role,
            status = if (role == ConversationRepository.ROLE_ASSISTANT) "COMPLETED" else null,
            ownerUserMessageId = owner,
            parts = listOf(MessagePartInput(MessagePartKinds.TEXT, text)),
        )))

    @Test fun readerProjectsSteeringHiddenMessagesTranslationsAndReactions() = onDatabaseThread {
        val id = "reader-projection"
        success(conversations.ensureConversation(id))
        val user = append(id, ConversationRepository.ROLE_USER, "question")
        val hiddenAssistant = append(id, ConversationRepository.ROLE_ASSISTANT, "superseded", user.messageId)
        val activeAssistant = success(conversations.appendMessage(AppendMessageInput(
            conversationId = id,
            role = ConversationRepository.ROLE_ASSISTANT,
            status = "COMPLETED",
            ownerUserMessageId = user.messageId,
            beforeMessageId = hiddenAssistant.messageId,
            durationMillis = 24L,
            parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "answer before steering")),
        )))
        success(conversations.hideAssistant(id, hiddenAssistant.messageId, "assistant_regenerated"))
        val followUp = append(id, ConversationRepository.ROLE_USER, "follow-up")
        success(conversations.setTranslation(id, activeAssistant.messageId, "es", "respuesta inicial"))
        success(conversations.setTranslation(id, activeAssistant.messageId, "es", "respuesta final"))
        success(conversations.setReaction(id, user.stableId, "\uD83D\uDC4D"))

        val active = reader.readActiveConversationMessages(id)
        assertEquals(listOf(user.messageId, activeAssistant.messageId, followUp.messageId), active.map { it.messageId })
        assertTrue(active.any { it.messageId == activeAssistant.messageId })
        assertFalse(active.any { it.messageId == hiddenAssistant.messageId })
        assertEquals(listOf(followUp.messageId), reader.readUserMessagesAfter(id, user.messageId).map { it.messageId })

        val events = reader.readRecentConversationTimeline(id, 10).events
        assertFalse(events.any { it.messageId == hiddenAssistant.messageId })
        assertEquals("\uD83D\uDC4D", events.single { it.messageId == user.messageId }.reactionEmoji)
        val translation = events.single { it.kind == "assistant_translation" }
        assertEquals(activeAssistant.messageId, translation.detail)
        assertEquals("es", translation.stage)
        assertEquals("respuesta final", translation.text)

        success(conversations.hideTranslation(id, activeAssistant.messageId))
        assertFalse(reader.readRecentConversationTimeline(id, 10).events.any { it.kind == "assistant_translation" })
    }

    @Test fun readerProjectsProactiveRepliesDecisionsInterruptAcknowledgementsAndFirstResultWins() = onDatabaseThread {
        val proactiveId = "reader-proactive"
        success(conversations.ensureConversation(proactiveId, "proactive"))
        val proactive = success(conversations.appendProactiveAssistantMessageIfAbsent(
            proactiveId,
            decisionId = "decision-reply",
            content = "A suggestion",
            threadKey = "thread-1",
            replies = listOf(SuggestedReplyInput("Accept", "accept")),
        ))
        success(conversations.consumeSuggestedReplies(proactiveId, proactive.messageId))
        val proactiveEvent = reader.readRecentConversationTimeline(proactiveId, 5).events.single()
        assertEquals(proactive.messageId, proactiveEvent.messageId)
        assertEquals("thread-1", proactiveEvent.proactiveThreadKey)
        assertEquals(listOf("accept"), proactiveEvent.proactiveReplies.map { it.text })
        assertTrue(proactiveEvent.proactiveRepliesUsed)

        val id = "reader-decisions"
        success(conversations.ensureConversation(id))
        success(conversations.appendDecisionRequest(
            id, "choice", "Choose", "Pick an option", true,
            listOf(DecisionOptionInput("yes", "Yes"), DecisionOptionInput("no", "No")),
        ))
        success(conversations.resolveDecision(id, "choice", "selected", "yes", "Yes"))
        val decision = reader.readDecisions(id).single()
        assertEquals("selected", decision.status)
        assertEquals("yes", decision.optionId)
        assertEquals(listOf("yes", "no"), decision.options.map { it.id })

        val request = success(conversations.appendInterruptRequest(id, "please clarify", listOf("skill-a"), memoryDisabled = true))
        val pending = reader.pendingInterruptRequests(id).single()
        assertEquals(request.requestId, pending.requestId)
        assertEquals(listOf("skill-a"), pending.skills)
        assertTrue(pending.memoryDisabled)
        val dispatch = success(conversations.dispatchInterruptRequest(id, request.requestId))
        assertTrue(dispatch.message.acknowledgementRequired)
        val dispatchedPending = reader.pendingInterruptRequests(id).single()
        assertTrue(dispatchedPending.alreadyDispatched)
        assertTrue(dispatchedPending.acknowledgementRequired)
        success(conversations.markInputState(id, listOf(request.requestId), ConversationRepository.INPUT_INCORPORATED))
        assertTrue(reader.pendingInterruptRequests(id).isEmpty())
        val acknowledged = reader.readInterruptRequest(id, request.requestId)!!
        assertEquals(ConversationRepository.INPUT_INCORPORATED, acknowledged.inputState)
        assertEquals(dispatch.message.messageId, acknowledged.dispatchedMessageId)

        val batchId = "be0c0f71-1912-4d38-ae72-84be78fb9624"
        success(model.appendToolBatch(ModelToolBatchInput(
            conversationId = id,
            batchId = batchId,
            ownerUserMessageId = null,
            messageIndex = 0,
            assistantText = "Calling tool",
            calls = listOf(ModelCallInput("call-1", "lookup", "{}")),
        )))
        success(model.appendToolResult(ModelToolResultInput(id, batchId, "call-1", "lookup", "RESULT", "first result")))
        success(model.appendToolResult(ModelToolResultInput(id, batchId, "call-1", "lookup", "RESULT", "second result")))
        val resultEntries = reader.readModelTranscript(id).entries.filterIsInstance<ModelTranscriptEntry.ResultActivity>()
        assertEquals(2, resultEntries.size)
        assertEquals(listOf("first result", "first result"), resultEntries.map { it.firstResult!!.output })
    }

    @Test fun timelineProjectsPersistedActivityWithPhysicalSeqIdsAndKeysetPages() = onDatabaseThread {
        val id = "reader-timeline-events"
        success(conversations.ensureConversation(id))
        val user = append(id, ConversationRepository.ROLE_USER, "find a source")
        val batchId = "timeline-batch"
        success(model.appendToolBatch(ModelToolBatchInput(
            conversationId = id,
            batchId = batchId,
            ownerUserMessageId = user.messageId,
            messageIndex = 0,
            assistantText = "Checking sources",
            calls = listOf(
                ModelCallInput("completed-call", "lookup", "{}"),
                ModelCallInput("running-call", "inspect", "{}"),
            ),
        )))
        success(model.setCallLifecycle(id, batchId, "running-call", "STARTED"))
        success(model.appendToolResult(ModelToolResultInput(
            id, batchId, "completed-call", "lookup", "RESULT", "source found",
        )))
        val batchSeq = reader.readModelTranscript(id).entries
            .filterIsInstance<ModelTranscriptEntry.Batch>().single().seq
        val resultSeq = reader.readModelTranscript(id).entries
            .filterIsInstance<ModelTranscriptEntry.ResultActivity>().single().seq

        val compaction = success(memory.appendCompaction(CompactionInput(
            conversationId = id, summary = "Earlier context", firstKept = 1,
            summarizedMessages = 1, createdAtSeconds = 20.0,
        )))
        val decisionSeq = success(conversations.appendDecisionRequest(
            id, "timeline-choice", "Choose", "Select a source", true,
            listOf(DecisionOptionInput("open", "Open")),
        ))
        val interrupt = success(conversations.appendInterruptRequest(id, "add another source", emptyList(), false))

        val initial = reader.readRecentConversationTimeline(id, 10)
        val progressAndPending = initial.events.single { it.id == batchSeq }
        assertEquals("tool", progressAndPending.kind)
        assertEquals("tool_interrupted", progressAndPending.stage)
        assertEquals("running-call", progressAndPending.toolCallId)
        assertTrue(progressAndPending.detail!!.contains("Checking sources"))
        assertTrue(progressAndPending.detail!!.contains("effects may have occurred"))
        val completed = initial.events.single { it.id == resultSeq }
        assertEquals("tool_result", completed.stage)
        assertEquals("source found", completed.detail)
        assertEquals(compaction.seq, initial.events.single { it.kind == "compaction" }.id)
        assertEquals(decisionSeq, initial.events.single { it.kind == "user_decision" }.id)
        assertEquals(interrupt.seq, initial.events.single { it.messageId == "pending-${interrupt.requestId}" }.id)
        assertEquals(initial.events.size, initial.events.map { it.id }.toSet().size)

        val nextUser = append(id, ConversationRepository.ROLE_USER, "another question")
        val newest = reader.readRecentConversationTimeline(id, 1)
        assertTrue(newest.events.any { it.messageId == nextUser.messageId })
        val cursor = newest.olderCursorSeq!!
        val older = reader.readConversationTimelineBefore(id, cursor, 1)
        assertTrue(older.events.any { it.id == batchSeq })
        assertTrue(older.events.none { it.messageId == nextUser.messageId })
        assertTrue(older.events.all { olderEvent -> newest.events.none { it.id == olderEvent.id } })
    }

    @Test fun readerUsesCompactionSummaryAcknowledgementsAndUtf16ContextLimits() = onDatabaseThread {
        val id = "reader-compaction"
        success(conversations.ensureConversation(id))
        val first = append(id, ConversationRepository.ROLE_USER, "summarized question")
        val retained = append(id, ConversationRepository.ROLE_ASSISTANT, "retained answer", first.messageId)
        val compaction = success(memory.appendCompaction(CompactionInput(
            conversationId = id,
            summary = "earlier messages summarized",
            firstKept = 1,
            summarizedMessages = 1,
            createdAtSeconds = 12.5,
        )))

        val context = reader.loadConversationContext(id)
        assertEquals(ConversationTurn.Kind.COMPACTION_SUMMARY, context.first().kind)
        assertTrue("the summary keeps the legacy safety framing around the stored text", context.first().content.endsWith("earlier messages summarized"))
        assertFalse(context.any { it.content.contains("summarized question") })
        assertTrue(context.any { it.content == "retained answer" })
        assertEquals(listOf(compaction.compaction_id), reader.pendingCompactionIds(id))
        success(memory.acknowledgeCompactions(id, listOf(compaction.compaction_id)))
        assertTrue(reader.pendingCompactionIds(id).isEmpty())
        assertTrue(retained.seq > first.seq)

        val utf16Id = "reader-utf16"
        success(conversations.ensureConversation(utf16Id))
        append(utf16Id, ConversationRepository.ROLE_USER, "a".repeat(2_999) + "\uD83D\uDE00")
        val bounded = reader.readConversationContext(utf16Id).substringAfter("USER: ").substringBefore('\n')
        assertEquals(3_003, bounded.length)
        assertTrue(bounded[2_999].isHighSurrogate())
        assertEquals("\u00E2\u20AC\u00A6", bounded.substring(3_000))
    }
}
