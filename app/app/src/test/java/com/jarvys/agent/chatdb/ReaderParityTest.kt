package com.jarvys.agent.chatdb

import com.jarvys.agent.ConversationReader
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ChatAttachment
import com.jarvys.agent.ConversationTurn
import com.jarvys.agent.LocalRunStore
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewResultEvidence
import com.jarvys.agent.crew.CrewProcessIdentity
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReaderParityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val databaseExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "chatdb-reader-parity-test").apply { isDaemon = true }
    }
    private lateinit var database: JarvysChatDatabase

    @Before fun openDatabase() {
        database = JarvysChatDatabaseProvider.openDatabaseFileForTests(context, File(temporary.root, "reader-parity.db"))
    }

    @After fun closeDatabase() {
        if (::database.isInitialized) JarvysChatDatabaseProvider.closeDatabaseFileForTests(database)
        databaseExecutor.shutdownNow()
    }

    private fun <T> onDatabaseThread(block: suspend () -> T): T =
        databaseExecutor.submit<T> { runBlocking { block() } }.get(60, TimeUnit.SECONDS)

    @Test fun syntheticModelContextAndCoreReadersMatchRealLegacyJsonlReader() = onDatabaseThread {
        val workspaceId = "62345678-1234-1234-1234-123456789abc"
        val sessionId = "reader-parity"
        val user0 = "user-before-summary"
        val user1 = "user-after-summary"
        val oldAssistant = "assistant-hidden"
        val steeredAssistant = "assistant-steered"
        val attachmentId = "11111111-1111-4111-8111-111111111111"
        val attachmentName = "source.txt"
        val attachment = JSONObject()
            .put("id", attachmentId)
            .put("name", attachmentName)
            .put("mimeType", "text/plain")
            .put("sizeBytes", 17)
            .put("kind", "FILE")
            .put("relativePath", "$attachmentId-$attachmentName")
        val oldBatch = "72345678-1234-1234-1234-123456789abc"
        val activeBatch = "82345678-1234-1234-1234-123456789abc"
        val crewMission = CrewMissionSnapshot(
            "mission-reader", sessionId, CrewProcessIdentity.ID, "Crew title", "Original task", "agent", "DONE",
            "latest synthesis", 1_700_000_000_000L, 1_700_000_000_500L, emptyList(), emptyList(),
        )
        val lines = listOf(
            row("session_title", content = "Legacy title", timestamp = 10.0),
            row(role = "user", content = "first request", messageId = user0, attachments = JSONArray().put(attachment), timestamp = 11.0),
            row("model_tool_calls", batchId = oldBatch, userMessageId = user0, messageIndex = 0,
                assistantText = "Earlier work", calls = JSONArray().put(toolCall("old-call", "lookup"))),
            row("model_tool_result", batchId = oldBatch, callId = "old-call", toolName = "lookup", lifecycle = "RESULT", output = "first output"),
            row("model_tool_result", batchId = oldBatch, callId = "old-call", toolName = "lookup", lifecycle = "RESULT", output = "duplicate ignored"),
            row(role = "assistant", content = "old answer", messageId = oldAssistant, userMessageId = user0, status = "COMPLETED", timestamp = 12.0),
            row("assistant_translation", messageId = oldAssistant, language = "es", content = "respuesta antigua", timestamp = 12.1),
            row("assistant_translation_hidden", messageId = oldAssistant, timestamp = 12.2),
            row("compaction", summary = "obsolete summary", firstKept = 0, summarizedMessages = 1, timestamp = 13.0),
            row("assistant_regenerated", messageId = oldAssistant, runId = "old-run", userMessageId = user0, timestamp = 13.1),
            row(role = "user", content = "second request", messageId = user1, timestamp = 14.0),
            row(role = "assistant", content = "inserted before second request", messageId = steeredAssistant,
                beforeMessageId = user1, userMessageId = user0, status = "COMPLETED", timestamp = 13.5),
            row(role = "assistant", type = "proactive_message", content = "proactive note", messageId = "proactive-1",
                proactiveDecisionId = "decision-proactive", proactiveThreadKey = "thread-1", timestamp = 14.1,
                suggestedReplies = JSONArray().put(JSONObject().put("label", "Continue").put("text", "Continue"))),
            row("message_reaction", version = 1, messageId = steeredAssistant, emoji = "\uD83D\uDC4D", timestamp = 14.2),
            row("message_reaction", version = 1, messageId = steeredAssistant, emoji = "\u2764\uFE0F", timestamp = 14.3),
            row("user_decision_request", decisionId = "decision-1", title = "Choose", body = "Choose one",
                options = JSONArray().put(JSONObject().put("id", "yes").put("label", "Yes")), allowDismiss = true, timestamp = 14.4),
            row("user_decision_resolution", decisionId = "decision-1", status = "SELECTED", optionId = "yes", optionLabel = "Yes", timestamp = 14.5),
            crewMission.toJson().put("timestamp", 14.6),
            CrewMissionSnapshot(
                "mission-reader", sessionId, CrewProcessIdentity.ID, "Crew title", "Original task", "agent", "DONE",
                "older duplicate", 1_700_000_000_000L, 1_700_000_000_400L, emptyList(), emptyList(),
            ).toJson().put("timestamp", 14.7),
            row("main_interrupt_request", requestId = "pending-request", content = "follow up", skills = JSONArray().put("memory"),
                memoryDisabled = true, timestamp = 14.8),
            row("main_interrupt_request", requestId = "ack-request", content = "already incorporated", skills = JSONArray(),
                memoryDisabled = false, timestamp = 14.9),
            row(role = "user", content = "already incorporated", messageId = "ack-request", interruptRequestId = "ack-request",
                acknowledgementRequired = true, timestamp = 15.0),
            row("main_chat_input_incorporated", messageIds = JSONArray().put("ack-request"), timestamp = 15.1),
            row("compaction", summary = "current summary", firstKept = 1, summarizedMessages = 1, timestamp = 15.2),
            row("model_tool_calls", batchId = activeBatch, userMessageId = user1, messageIndex = 3,
                assistantText = "Searching now", calls = JSONArray().put(toolCall("active-call", "lookup")), timestamp = 15.3),
            row("model_tool_started", batchId = activeBatch, callId = "active-call", timestamp = 15.4),
            row("model_tool_result", batchId = activeBatch, callId = "active-call", toolName = "lookup", lifecycle = "RESULT", output = "durable result", timestamp = 15.5),
            row("model_tool_result", batchId = activeBatch, callId = "active-call", toolName = "lookup", lifecycle = "RESULT", output = "later duplicate", timestamp = 15.6),
            row("model_provider_output_items", userMessageId = user1, messageIndex = 3,
                items = JSONArray().put(JSONObject().put("kind", "MESSAGE").put("value", "old provider")), timestamp = 15.7),
            row("model_provider_output_items", userMessageId = user1, messageIndex = 3,
                items = JSONArray().put(JSONObject().put("kind", "REASONING").put("value", "latest provider")), timestamp = 15.8),
            row("model_error", userMessageId = user1, messageIndex = 3, errorCode = "model-timeout", timestamp = 15.9),
            row("reflection_tool", userMessageId = user1, callId = "legacy-card", toolName = "legacy", source = "legacy", stage = "tool_result",
                marker = "old transcript card", timestamp = 16.0),
            row("future_annotation", note = "unknown but parseable", timestamp = 16.1),
        )

        val ledger = File(temporary.root, "reader-parity.jsonl")
        ledger.outputStream().buffered().use { output ->
            lines.forEach { json ->
                val encoded = json.toString()
                    .replace("\uD800", "\\ud800")
                    .replace("\uDC00", "\\udc00")
                output.write(encoded.toByteArray(StandardCharsets.UTF_8))
                output.write('\n'.code)
            }
            output.write("{damaged-line\n".toByteArray(StandardCharsets.UTF_8))
        }
        val report = LedgerImporter(database).importConversation(
            sessionId, ledger, File(temporary.root, "$sessionId.meta.json").apply { writeText("{}") },
        )
        assertEquals("done", report.state)
        assertEquals(1L, report.unknownLines)
        assertEquals(1L, report.skippedLines)

        val legacyFiles = File(temporary.root, "legacy-files")
        val legacyLedger = File(legacyFiles, "jarvys/conversations/$sessionId.jsonl").apply {
            parentFile!!.mkdirs()
            ledger.copyTo(this)
        }
        File(legacyFiles, "jarvys/conversations/$sessionId.meta.json").writeText("{}")
        val legacy = legacyStore(legacyFiles)
        val reader = ConversationReader(database)

        val expectedContext = legacy.loadConversationContext(sessionId).map(::turnShape)
        val actualContext = reader.loadConversationContext(sessionId).map(::turnShape)
        assertEquals("loadConversationContext parity", expectedContext, actualContext)

        val expectedMessages = legacy.readConversationMessages(sessionId).map { messageShape(it) }
        val actualMessages = reader.readConversationMessages(sessionId).map(::messageShape)
        assertEquals("readConversationMessages parity", expectedMessages, actualMessages)
        assertEquals(
            "active messages hide regenerated assistants with final-state semantics",
            legacy.readActiveConversationMessages(sessionId).map(::messageShape),
            reader.readActiveConversationMessages(sessionId).map(::messageShape),
        )
        assertEquals(legacy.latestUserMessageId(sessionId), reader.latestUserMessageId(sessionId).orEmpty())
        assertEquals(legacy.latestUserMessageIndex(sessionId), reader.latestUserMessageIndex(sessionId))

        val expectedInterrupts = legacy.readPendingInterruptRequests(sessionId).map { row ->
            listOf(row.optString("requestId"), row.optString("content"), row.optBoolean("memoryDisabled"),
                row.optJSONArray("skills").toString(), row.optBoolean("alreadyDispatched"))
        }
        val actualInterrupts = reader.pendingInterruptRequests(sessionId).map { request ->
            listOf(request.requestId, request.content, request.memoryDisabled, JSONArray(request.skills).toString(), request.alreadyDispatched)
        }
        assertEquals("pending interrupt parity", expectedInterrupts, actualInterrupts)

        val expectedCrew = legacy.readCrewMissionSnapshots(sessionId).map { it.toJson().toString() }
        val actualCrew = reader.readCrewMissionSnapshots(sessionId).map { it.toJson().toString() }
        assertEquals("latest crew snapshot parity", expectedCrew, actualCrew)

        val expectedDrawer = legacy.listConversations().single()
        val actualDrawer = reader.listConversations(1).single()
        assertEquals(expectedDrawer.optString("session_id"), actualDrawer.id)
        assertEquals(expectedDrawer.optString("title"), actualDrawer.title)
        assertEquals(expectedDrawer.optString("goal"), actualDrawer.goal)
        assertEquals((expectedDrawer.optDouble("timestamp") * 1000.0).toLong(), actualDrawer.lastMessageAtMillis)
        assertEquals(expectedDrawer.optInt("turns").toLong(), actualDrawer.turnCount)
        assertEquals(legacy.conversationMessageCount(sessionId).toLong(), actualDrawer.messageCount)
        assertEquals(
            legacy.readActiveConversationMessages(sessionId).last().optString("content"),
            actualDrawer.preview,
        )

        val legacyReaction = legacy.readConversationTimeline(sessionId).single { it.messageId == steeredAssistant }.reactionEmoji
        assertEquals(legacyReaction, reader.messageReactions(sessionId)[steeredAssistant])
        assertEquals(null, reader.readMessage(sessionId, oldAssistant)?.translation)
        val expectedDecision = legacy.readConversationTimeline(sessionId).single { it.kind == "user_decision" }
        val actualDecision = reader.readDecisions(sessionId).single()
        assertEquals(expectedDecision.decisionId, actualDecision.decisionId)
        assertEquals(expectedDecision.text, actualDecision.title)
        assertEquals(expectedDecision.decisionBody, actualDecision.body)
        assertEquals(expectedDecision.decisionAllowDismiss, actualDecision.allowDismiss)
        assertEquals(expectedDecision.decisionStatus, actualDecision.status)
        assertEquals(expectedDecision.decisionOptionId, actualDecision.optionId)
        assertEquals(expectedDecision.decisionOptionLabel, actualDecision.optionLabel)
        assertEquals(expectedDecision.decisionOptions, actualDecision.options)
        assertEquals(expectedDecision.timestampMillis / 1000.0, actualDecision.createdAtSeconds, 0.001)
        assertEquals(legacy.pendingCompactionIds(sessionId), reader.pendingCompactionIds(sessionId))

        val pagedSequences = mutableListOf<Long>()
        var page = reader.readTailWindow(sessionId, 100)
        pagedSequences.addAll(page.slots.map { it.item.seq })
        while (page.olderCursorSeq != null) {
            page = reader.readWindowBefore(sessionId, page.olderCursorSeq!!, 100)
            pagedSequences.addAll(0, page.slots.map { it.item.seq })
        }
        val completeSpine = database.chatSql.query(
            "SELECT seq FROM conversation_items WHERE conversation_id=? ORDER BY sort_seq,sort_sub,seq",
            arrayOf(sessionId),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        assertEquals("keyset pages reproduce the global spine projection without gaps or duplicates", completeSpine, pagedSequences)

        val expectedReflection = legacy.buildReflectionPayload(sessionId)
        val actualReflection = reader.buildReflectionPayload(sessionId)
        assertEquals("reflection text parity", expectedReflection.text, actualReflection.text)
        assertEquals(expectedReflection.endMessageId, actualReflection.endMessageId)
        assertEquals(expectedReflection.compactionIds, actualReflection.compactionIds)
        assertEquals(expectedReflection.completedAssistantSteps, actualReflection.completedAssistantSteps)
        assertEquals(expectedReflection.userCharacters, actualReflection.userCharacters)
        assertEquals(expectedReflection.messageCount, actualReflection.messageCount)
        assertEquals(expectedReflection.hasMoreMessages, actualReflection.hasMoreMessages)

        assertTrue("same source ledger was imported", legacyLedger.readBytes().contentEquals(ledger.readBytes()))

        val strictSession = WorkspaceIdentity.executionSessionId(workspaceId)
        val evidence = CrewResultEvidence.create(
            strictSession, "92345678-1234-1234-1234-123456789abc", "bot-utf16",
            "a2345678-1234-1234-1234-123456789abc", "DONE", "before-\uD800-middle-\uDC00-after", 1_700_000_000_100L,
        )
        val strictLedger = File(temporary.root, "$strictSession.jsonl")
        val strictJson = evidence.toJson().toString()
            .replace("\uD800", "\\ud800")
            .replace("\uDC00", "\\udc00")
        strictLedger.writeText("$strictJson\n", StandardCharsets.UTF_8)
        val strictReport = LedgerImporter(database).importConversation(
            strictSession, strictLedger, File(temporary.root, "$strictSession.meta.json").apply { writeText("{}") },
        )
        assertEquals("done", strictReport.state)
        val strictLegacyLedger = File(legacyFiles, "jarvys/conversations/$strictSession.jsonl").apply {
            parentFile!!.mkdirs()
            strictLedger.copyTo(this)
        }
        File(legacyFiles, "jarvys/conversations/$strictSession.meta.json").writeText("{}")
        val expectedEvidence = legacy.readCrewResultEvidence(strictSession, evidence.evidenceId)
        val actualEvidence = reader.strictJournals.readCrewResultEvidence(strictSession, evidence.evidenceId)
        assertEquals(expectedEvidence.resultText.toCharArray().map(Char::code), actualEvidence?.resultText?.toCharArray()?.map(Char::code))
        assertTrue("strict UTF-16 source bytes are unchanged", strictLegacyLedger.readBytes().contentEquals(strictLedger.readBytes()))
    }

    private fun row(
        type: String? = null,
        role: String? = null,
        content: Any? = null,
        timestamp: Any? = null,
        messageId: Any? = null,
        attachments: Any? = null,
        batchId: Any? = null,
        userMessageId: Any? = null,
        messageIndex: Any? = null,
        assistantText: Any? = null,
        calls: Any? = null,
        callId: Any? = null,
        toolName: Any? = null,
        lifecycle: Any? = null,
        output: Any? = null,
        language: Any? = null,
        firstKept: Any? = null,
        summarizedMessages: Any? = null,
        runId: Any? = null,
        beforeMessageId: Any? = null,
        status: Any? = null,
        proactiveDecisionId: Any? = null,
        proactiveThreadKey: Any? = null,
        suggestedReplies: Any? = null,
        version: Any? = null,
        emoji: Any? = null,
        decisionId: Any? = null,
        title: Any? = null,
        body: Any? = null,
        options: Any? = null,
        allowDismiss: Any? = null,
        optionId: Any? = null,
        optionLabel: Any? = null,
        requestId: Any? = null,
        skills: Any? = null,
        memoryDisabled: Any? = null,
        interruptRequestId: Any? = null,
        acknowledgementRequired: Any? = null,
        messageIds: Any? = null,
        errorCode: Any? = null,
        source: Any? = null,
        stage: Any? = null,
        marker: Any? = null,
        note: Any? = null,
        summary: Any? = null,
        items: Any? = null,
    ): JSONObject = JSONObject().apply {
        if (type != null) put("type", type)
        if (role != null) put("role", role)
        val fields = listOf(
            "content" to content, "timestamp" to timestamp, "messageId" to messageId, "attachments" to attachments,
            "batchId" to batchId, "userMessageId" to userMessageId, "messageIndex" to messageIndex,
            "assistantText" to assistantText, "calls" to calls, "callId" to callId, "toolName" to toolName,
            "lifecycle" to lifecycle, "output" to output, "language" to language, "firstKept" to firstKept,
            "summarizedMessages" to summarizedMessages, "runId" to runId, "beforeMessageId" to beforeMessageId,
            "status" to status, "proactiveDecisionId" to proactiveDecisionId, "proactiveThreadKey" to proactiveThreadKey,
            "suggestedReplies" to suggestedReplies, "version" to version, "emoji" to emoji, "decisionId" to decisionId,
            "title" to title, "body" to body, "options" to options, "allowDismiss" to allowDismiss,
            "optionId" to optionId, "optionLabel" to optionLabel, "requestId" to requestId, "skills" to skills,
            "memoryDisabled" to memoryDisabled, "interruptRequestId" to interruptRequestId,
            "mainChatAcknowledgementRequired" to acknowledgementRequired, "messageIds" to messageIds,
            "errorCode" to errorCode, "source" to source, "stage" to stage, "marker" to marker,
            "note" to note, "summary" to summary, "items" to items,
        )
        fields.forEach { (key, value) -> if (value != null) put(key, value) }
    }

    private fun toolCall(id: String, name: String): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("arguments", JSONObject().put("query", "source"))

    private fun legacyStore(filesDirectory: File): LocalRunStore = LocalRunStore::class.java
        .getDeclaredConstructor(File::class.java)
        .apply { isAccessible = true }
        .newInstance(filesDirectory)

    private fun turnShape(turn: ConversationTurn): List<Any?> = listOf(
        turn.kind.name, turn.role, turn.content, turn.originalMessageIndex, turn.summarizedMessageCount,
        turn.thinking, turn.imageCount,
        turn.attachments.map { listOf(it.id, it.name, it.mimeType, it.sizeBytes, it.kind.name, it.relativePath) },
        turn.toolCallId, turn.toolName,
        turn.toolCalls.map { call -> listOf(call.id, call.name, call.arguments, call.invalidArgumentsReason) },
        turn.providerOutputItems.map { item -> listOf(item.kind.name, item.value, item.provider, item.api, item.model) },
        turn.modelErrorCode,
    )

    private fun messageShape(row: JSONObject): List<Any?> = listOf(
        row.optString("messageId"), row.optString("role"), row.optString("content"),
        row.optString("status").takeIf(String::isNotEmpty), row.optString("runId").takeIf(String::isNotEmpty),
        row.optString("userMessageId").takeIf(String::isNotEmpty), row.optLong("durationMs").takeIf { row.has("durationMs") },
        row.optString("beforeMessageId").takeIf(String::isNotEmpty),
        row.optString("proactiveThreadKey").takeIf(String::isNotEmpty),
        row.optString("proactiveReplyToMessageId").takeIf(String::isNotEmpty),
        row.optString("interruptRequestId").takeIf(String::isNotEmpty),
        row.optBoolean("mainChatAcknowledgementRequired"), row.optDouble("timestamp"),
        ChatAttachment.fromJsonArray(row.optJSONArray("attachments")),
    )

    private fun messageShape(message: StoredMessage): List<Any?> = listOf(
        message.messageId, message.role, message.text, message.status, message.runId, message.ownerUserMessageId,
        message.durationMillis, message.beforeMessageId, message.proactiveThreadKey, message.proactiveReplyToMessageId,
        message.interruptRequestId, message.acknowledgementRequired, message.createdAtSeconds,
        message.attachments.map { ChatAttachment(it.attachmentId, it.name, it.mimeType, it.sizeBytes, ChatAttachment.Kind.valueOf(it.kind), it.relativePath) },
    )
}

