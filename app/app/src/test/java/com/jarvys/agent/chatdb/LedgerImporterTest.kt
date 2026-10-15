package com.jarvys.agent.chatdb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ConversationTurn
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.workspaces.WorkspaceCoordinationReference
import com.jarvys.agent.workspaces.WorkspaceCoordinationReply
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceInputIncorporation
import com.jarvys.agent.workspaces.WorkspaceResultReference
import com.jarvys.agent.crew.CrewCoordinationEvidence
import com.jarvys.agent.crew.CrewResultEvidence
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LedgerImporterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val databaseExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "chatdb-ledger-import-test").apply { isDaemon = true }
    }
    private lateinit var database: JarvysChatDatabase

    @Before fun openDatabase() {
        database = JarvysChatDatabaseProvider.openDatabaseFileForTests(context, File(temporary.root, "import.db"))
    }

    @After fun closeDatabase() {
        if (::database.isInitialized) JarvysChatDatabaseProvider.closeDatabaseFileForTests(database)
        databaseExecutor.shutdownNow()
    }

    private fun <T> onDatabaseThread(block: suspend () -> T): T =
        databaseExecutor.submit<T> { runBlocking { block() } }.get(90, TimeUnit.SECONDS)

    @Test fun syntheticCatalogImportsEveryDocumentedRowFormAndKeepsProjectionInvariants() = onDatabaseThread {
        val workspaceId = "a1234567-b123-c123-d123-e123456789ab"
        val executionSession = WorkspaceIdentity.executionSessionId(workspaceId)
        val requestId = "request-1"
        val missionId = UUID.nameUUIDFromBytes("jarvys-workspace-mission-v1\u0000$workspaceId\u0000$requestId".toByteArray(StandardCharsets.UTF_8)).toString()
        val assignmentId = "b1234567-b123-c123-d123-e123456789ab"
        val cycleId = "c1234567-b123-c123-d123-e123456789ab"
        val central = WorkspaceIdentity.CENTRAL_SESSION
        val timestampMillis = 1_700_000_000_125L

        val resultEvidence = CrewResultEvidence.create(executionSession, missionId, "bot-1", cycleId, "DONE", "durable result", timestampMillis)
        val resultReference = WorkspaceResultReference.create(resultEvidence, central, workspaceId, assignmentId, requestId, 1L)
        val coordinationEvidence = CrewCoordinationEvidence.create(
            executionSession, missionId, "bot-1", cycleId, "coord-1", "QUESTION", "Need clarification", emptyList(), timestampMillis,
        )
        val coordinationReference = WorkspaceCoordinationReference.create(coordinationEvidence, central, workspaceId, assignmentId, requestId, 1L)
        val reply = WorkspaceCoordinationReply.create(coordinationReference, "reply-key", "Answer", "central-request", "Please investigate", timestampMillis + 1)
        val inputReceipt = WorkspaceInputIncorporation.create(
            WorkspaceInputIncorporation.SourceKind.NATIVE_CORRECT, "d1234567-b123-c123-d123-e123456789ab",
            "d1234567-b123-c123-d123-e123456789ab", "e1234567-b123-c123-d123-e123456789ab", central, workspaceId,
            executionSession, assignmentId, requestId, missionId, "bot-1", cycleId, cycleId,
            "a".repeat(64), "b".repeat(64), "USER", "c".repeat(64), "d".repeat(64), timestampMillis, timestampMillis + 2,
        )

        val userId = "user-main"
        val user2Id = "user-steered-to"
        val assistantId = "assistant-main"
        val steeredAssistantId = "assistant-steered"
        val proactiveId = "proactive-main"
        val batchId = "f1234567-b123-c123-d123-e123456789ab"
        val decisionId = "decision-1"
        val artifactId = "12345678-1234-1234-1234-123456789abc"
        val attachmentId = "22345678-1234-1234-1234-123456789abc"
        val callId = "call-1"
        val toolCall = JSONObject().put("id", callId).put("name", "lookup").put("arguments", JSONObject().put("query", "synthetic"))
        val activity = JSONObject().put("eventId", "activity-first").put("executionId", "execution-1").put("callId", callId)
            .put("toolName", "lookup").put("stage", "started").put("timestampMillis", timestampMillis)
        val updatedActivity = JSONObject(activity.toString()).put("stage", "completed").put("detail", "later duplicate")
        val modelRows = listOf(
            jsonRow("model_tool_calls", "batchId" to batchId, "userMessageId" to userId, "messageIndex" to 0,
                "assistantText" to "Searching", "assistantProgress" to true, "calls" to JSONArray().put(toolCall)),
            jsonRow("model_tool_started", "batchId" to batchId, "callId" to callId),
            jsonRow("model_tool_result", "batchId" to batchId, "callId" to callId, "toolName" to "lookup", "lifecycle" to "RESULT", "output" to "first output"),
            jsonRow("model_tool_result", "batchId" to batchId, "callId" to callId, "toolName" to "lookup", "lifecycle" to "RESULT", "output" to "later output"),
            jsonRow("model_tool_not_started", "batchId" to batchId, "callId" to callId),
            jsonRow("model_artifact", "artifactId" to "a".repeat(64), "artifact" to JSONObject().put("path", "model/a.txt").put("sha256", "a".repeat(64)).put("chars", 1).put("bytes", 1).put("mimeType", "text/plain")),
            jsonRow("model_error", "userMessageId" to userId, "messageIndex" to 0, "errorCode" to "synthetic_error"),
            jsonRow("model_provider_output_items", "userMessageId" to userId, "messageIndex" to 0, "items" to JSONArray().put(JSONObject().put("kind", "REASONING").put("value", "opaque"))),
            jsonRow("model_provider_output_items", "userMessageId" to userId, "messageIndex" to 0, "items" to JSONArray().put(JSONObject().put("kind", "MESSAGE").put("value", "latest provider output"))),
        )

        val chatRows = mutableListOf<JSONObject>()
        chatRows += jsonRow(null, "user", "content" to "original user", "messageId" to userId, "timestamp" to 1_700_000_000.125)
        chatRows += jsonRow(null, "user", "content" to "steering destination", "messageId" to user2Id, "timestamp" to 1_700_000_002.5)
        chatRows += jsonRow(null, "assistant", "content" to "steered first", "messageId" to steeredAssistantId,
            "beforeMessageId" to user2Id, "status" to "COMPLETED", "timestamp" to 1_700_000_001.25)
        chatRows += jsonRow(null, "assistant", "content" to "assistant body", "messageId" to assistantId,
            "userMessageId" to user2Id, "status" to "COMPLETED", "timestamp" to 1_700_000_003.75)
        chatRows += jsonRow("proactive_message", "assistant", "content" to "proactive text", "messageId" to proactiveId,
            "proactiveDecisionId" to "decision-proactive", "proactiveThreadKey" to "thread-1",
            "suggestedReplies" to JSONArray().put(JSONObject().put("label", "Yes").put("text", "Yes, continue")), "timestamp" to 1_700_000_004.0)
        chatRows += jsonRow("session_title", "content" to "Ledger title", "timestamp" to 1_700_000_004.5)
        chatRows += jsonRow("assistant_regenerated", "messageId" to steeredAssistantId, "runId" to "run-1", "userMessageId" to user2Id)
        chatRows += jsonRow("assistant_deleted", "messageId" to assistantId, "runId" to "run-2", "userMessageId" to user2Id)
        chatRows += jsonRow("assistant_translation", "messageId" to assistantId, "language" to "es", "content" to "traducción")
        chatRows += jsonRow("assistant_translation_hidden", "messageId" to assistantId)
        chatRows += jsonRow("message_reaction", "version" to 1, "messageId" to userId, "emoji" to "👍")
        chatRows += jsonRow("message_reaction", "version" to 1, "messageId" to userId, "emoji" to "❤️")
        chatRows += jsonRow("proactive_reply_used", "messageId" to proactiveId)
        chatRows += jsonRow("user_decision_request", "decisionId" to decisionId, "title" to "Choose", "body" to "Select one",
            "allowDismiss" to true, "options" to JSONArray().put(JSONObject().put("id", "yes").put("label", "Yes").put("description", "Accept").put("role", "primary")))
        chatRows += jsonRow("user_decision_resolution", "decisionId" to decisionId, "status" to "ACCEPTED", "optionId" to "yes", "optionLabel" to "Yes")
        chatRows += jsonRow("delivered_file", "artifact" to JSONObject().put("id", attachmentId).put("name", "report.txt").put("mimeType", "text/plain")
            .put("sizeBytes", 4).put("kind", "FILE").put("relativePath", "$attachmentId-report.txt"))
        chatRows += jsonRow("generated_image", "prompt" to "draw a circle", "revisedPrompt" to "circle", "size" to "small", "mimeType" to "image/png", "status" to "FAILED", "error" to "not generated")
        chatRows += jsonRow("main_interrupt_request", "requestId" to "interrupt-1", "content" to "Please check this", "skills" to JSONArray().put("skill-x"), "memoryDisabled" to false)
        chatRows += jsonRow(null, "user", "content" to "interrupt input", "messageId" to "interrupt-1", "interruptRequestId" to "interrupt-1", "mainChatAcknowledgementRequired" to true)
        chatRows += jsonRow("main_chat_input_incorporated", "messageIds" to JSONArray().put(userId))
        chatRows += jsonRow("main_chat_input_resolved", "messageIds" to JSONArray().put(user2Id))
        // Conflicting legacy markers for one identity are first-wins, matching LocalRunStore.
        chatRows += jsonRow("main_chat_input_resolved", "messageIds" to JSONArray().put(userId))
        chatRows += modelRows
        chatRows += jsonRow("tool_presentation", "userMessageId" to userId, "batchId" to batchId, "callId" to callId, "toolName" to "lookup", "stage" to "tool_result", "detail" to "shown")
        chatRows += jsonRow("tool_activity", "userMessageId" to userId, "batchId" to batchId, "activity" to activity)
        chatRows += jsonRow("tool_activity", "userMessageId" to userId, "batchId" to batchId, "activity" to updatedActivity)
        chatRows += jsonRow("reflection_tool", "userMessageId" to userId, "batchId" to batchId, "callId" to callId, "toolName" to "lookup", "source" to "connector", "stage" to "tool_result", "marker" to "done")
        chatRows += jsonRow("compaction", "summary" to "first compacted section", "firstKept" to 0, "trigger" to "size", "mode" to "auto", "summarizedMessages" to 2, "timestamp" to 1_700_000_010.25)
        chatRows += jsonRow("compaction", "summary" to "second compacted section", "firstKept" to 0, "trigger" to "size", "mode" to "auto", "summarizedMessages" to 1, "timestamp" to 1_700_000_011.5)
        chatRows += jsonRow("compaction_reflection_consumed")
        chatRows += jsonRow("model_provider_output_items", "userMessageId" to userId, "messageIndex" to 0,
            "items" to JSONArray().put(JSONObject().put("kind", "MESSAGE").put("value", "latest provider output")))
        chatRows += jsonRow("reflection_checkpoint", "throughMessageId" to userId, "reflectionId" to "reflection-1", "completedAssistantSteps" to 1,
            "result" to "checkpoint", "acknowledgedCompactionIds" to JSONArray())
        chatRows += jsonRow("reflection_commit", "throughMessageId" to userId, "reflectionId" to "reflection-1", "completedAssistantSteps" to 1,
            "result" to "checkpoint", "summary" to "summary", "status" to "COMPLETED", "trigger" to "manual", "revisionIds" to JSONArray().put(1))
        chatRows += jsonRow("reflection_event", "reflectionId" to "reflection-2", "summary" to "event", "status" to "COMPLETED", "revisionIds" to JSONArray())
        chatRows += jsonRow("reflection_undo", "reflectionId" to "reflection-1")
        chatRows += crewSnapshot("chat-catalog", "crew-catalog", 1_700_000_012.0, "snapshot")
        chatRows += jsonRow(null, "user", "content" to "message without source identity", "timestamp" to 1_700_000_013.0)

        val executionRows = listOf(
            resultEvidence.toJson(), resultReference.toJson(), coordinationEvidence.toJson(), coordinationReference.toJson(),
        )
        val centralRows = listOf(
            resultReference.toNoticeJson(), coordinationReference.toNoticeJson(), reply.toJson(),
            reply.withState(WorkspaceCoordinationReply.State.STARTED, timestampMillis + 2).toStateJson(), inputReceipt.toJson(),
        )
        val reports = listOf(
            importRows("chat-catalog", chatRows),
            importRows(executionSession, executionRows),
            importRows(central, centralRows),
        )

        reports.forEach { report ->
            assertEquals(report.toDiagnosticText(), "done", report.state)
            assertEquals(report.toDiagnosticText(), 0L, report.skippedLines)
            assertEquals(report.toDiagnosticText(), 0L, report.unknownLines)
            assertTrue(report.validations.all { it.passed })
        }
        val catalogTypes = reports.flatMap { it.ledgerRowsByType.keys }.toSet()
        assertEquals("every catalog row form must be represented", 43, catalogTypes.size)

        val messages = database.testChatSql.query(
            "SELECT message_id,stable_id,message_index,hidden_kind,hidden_seq,translation_hidden_seq,input_state,input_state_seq,created_at " +
                "FROM messages WHERE conversation_id='chat-catalog' ORDER BY seq",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0..8).map { index -> when (index) {
                    0, 1, 3 -> cursor.getString(index)
                    2 -> cursor.getLong(index)
                    4, 5 -> if (cursor.isNull(index)) null else cursor.getLong(index)
                    6 -> if (cursor.isNull(index)) null else cursor.getString(index)
                    else -> cursor.getDouble(index)
                } })
            }
        }
        val byId = messages.associateBy { it[0] as String }
        val orderedMessages = database.testChatSql.query(
            "SELECT message_id,stable_id,message_index FROM messages WHERE conversation_id='chat-catalog' ORDER BY message_index",
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getLong(2))) } }
        assertEquals(listOf(userId, steeredAssistantId, user2Id, assistantId, proactiveId, "interrupt-1"), orderedMessages.take(6).map { it.first })
        val expectedLegacyId = "legacy-" + UUID.nameUUIDFromBytes("chat-catalog\u00006".toByteArray(StandardCharsets.UTF_8))
        assertEquals(Triple(expectedLegacyId, expectedLegacyId, 6L), orderedMessages.last())
        assertEquals(steeredAssistantId, byId.getValue(steeredAssistantId)[1])
        assertEquals("assistant_regenerated", byId.getValue(steeredAssistantId)[3])
        assertNotNull(byId.getValue(steeredAssistantId)[4])
        assertEquals("assistant_deleted", byId.getValue(assistantId)[3])
        assertNotNull(byId.getValue(assistantId)[5])
        assertEquals("incorporated", byId.getValue(userId)[6])
        assertEquals("resolved", byId.getValue(user2Id)[6])
        assertEquals(1_700_000_000.125, byId.getValue(userId)[8] as Double, 0.00001)
        assertTrue(database.testChatSql.query(
            "SELECT 1 FROM conversation_items a JOIN conversation_items b ON b.conversation_id=a.conversation_id " +
                "WHERE a.conversation_id=? AND a.item_kind='message' AND a.item_key=? AND b.item_kind='message' AND b.item_key=? " +
                "AND (a.sort_seq<b.sort_seq OR (a.sort_seq=b.sort_seq AND a.sort_sub<b.sort_sub))",
            arrayOf("chat-catalog", steeredAssistantId, user2Id),
        ).use { it.moveToFirst() })
        assertEquals("❤️", database.testChatSql.query(
            "SELECT emoji FROM message_reactions WHERE conversation_id=? AND target_stable_id=?", arrayOf("chat-catalog", userId),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        assertEquals(0L, database.testChatSql.query(
            "SELECT COUNT(*) FROM tool_results WHERE conversation_id='chat-catalog' AND output<>'first output'",
        ).use { it.moveToFirst(); it.getLong(0) })
        assertEquals(1L, count("SELECT COUNT(*) FROM tool_activities WHERE conversation_id=? AND event_id=?", "chat-catalog", "activity-first"))
        assertEquals("started", database.testChatSql.query(
            "SELECT stage FROM tool_activities WHERE conversation_id=? AND event_id=?", arrayOf("chat-catalog", "activity-first"),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) })
        assertTrue(ModelTranscriptRepository(database).readModelTranscript("chat-catalog")
            .latestProviderOutputItemsByOwner.getValue(userId).items_json.contains("latest provider output"))
        assertTrue(database.testChatSql.query(
            "SELECT compaction_id FROM compactions WHERE conversation_id=? ORDER BY seq LIMIT 1", arrayOf("chat-catalog"),
        ).use { it.moveToFirst() && it.getString(0).startsWith("legacy-1700000010.25-") })
        assertEquals(2L, count("SELECT COUNT(*) FROM compaction_reflection_acks WHERE conversation_id=?", "chat-catalog"))
        assertEquals("done", database.testChatSql.query(
            "SELECT import_state FROM conversations WHERE id=?", arrayOf("chat-catalog"),
        ).use { it.moveToFirst(); it.getString(0) })
    }

    @Test fun legacyCompactionIdentityIncludesExactTimestampTokenAndFilteredRowIndex() = onDatabaseThread {
        val ledger = File(temporary.root, "compaction-identity.jsonl").apply {
            writeText(
                """{"role":"user","content":"u","messageId":"u","timestamp":1.0}""" + "\n" +
                    """{"role":"assistant","content":"a","messageId":"a","timestamp":2.0}""" + "\n" +
                    """{"type":"compaction","summary":"s","firstKept":0,"summarizedMessages":1,"timestamp":123.5}""" + "\n",
                StandardCharsets.UTF_8,
            )
        }
        val report = importer().importConversation("compaction-identity", ledger, writeMeta("compaction-identity.meta.json"))
        assertEquals("done", report.state)
        assertEquals("legacy-123.5-2", database.testChatSql.query(
            "SELECT compaction_id FROM compactions WHERE conversation_id=?", arrayOf("compaction-identity"),
        ).use { assertTrue(it.moveToFirst()); it.getString(0) })
    }

    @Test fun strictCrewResultPreservesUnpairedUtf16CodeUnitsInBlob() = onDatabaseThread {
        val workspaceId = "12345678-1234-1234-1234-123456789abc"
        val sessionId = WorkspaceIdentity.executionSessionId(workspaceId)
        val resultText = "before-\uD800-middle-\uDC00-after"
        val evidence = CrewResultEvidence.create(
            sessionId,
            "22345678-1234-1234-1234-123456789abc",
            "bot-utf16",
            "32345678-1234-1234-1234-123456789abc",
            "DONE",
            resultText,
            1_700_000_000_125L,
        )
        // Escape only the unpaired code units in JSON source so the UTF-8 ledger stays valid.
        val rawJson = evidence.toJson().toString()
            .replace("\uD800", "\\ud800")
            .replace("\uDC00", "\\udc00")
        val ledger = File(temporary.root, "strict-utf16.jsonl").apply {
            outputStream().use { it.write(rawJson.toByteArray(StandardCharsets.UTF_8)); it.write('\n'.code) }
        }
        val report = importer().importConversation(sessionId, ledger, writeMeta("strict-utf16.meta.json"))
        assertEquals("done", report.state)
        assertTrue(report.validations.all { it.passed })
        val stored = database.testChatSql.query(
            "SELECT result_text FROM crew_result_evidence WHERE conversation_id=? AND evidence_id=?",
            arrayOf(sessionId, evidence.evidenceId),
        ).use { assertTrue(it.moveToFirst()); it.getBlob(0) }
        assertTrue(ExactUtf16.encode(resultText).contentEquals(stored))
        assertEquals(resultText.toCharArray().map(Char::code), ExactUtf16.decode(stored).toCharArray().map(Char::code))
        assertFalse(report.toDiagnosticText().contains(resultText))
    }

    @Test fun repeatedCrewSnapshotsKeepOnlyLatestBodyAndFirstAndLastSourcePositions() = onDatabaseThread {
        val rows = (1..5).map { index -> crewSnapshot("crew-import", "same-mission", 100.0 + index, "snapshot-$index") }
        val report = importRows("crew-import", rows)
        assertEquals("done", report.state)
        val row = database.testChatSql.query(
            "SELECT first_snapshot_seq,last_snapshot_seq,snapshot_digest,synthesis FROM crew_missions WHERE conversation_id=? AND mission_id=?",
            arrayOf("crew-import", "same-mission"),
        ).use { c -> assertTrue(c.moveToFirst()); listOf(c.getLong(0), c.getLong(1), c.getString(2), c.getBlob(3)) }
        assertEquals(1L, row[0])
        assertEquals(5L, row[1])
        assertEquals(hashCrewSnapshot("snapshot-5"), row[2])
        assertEquals(5L, count("SELECT COUNT(*) FROM conversation_items WHERE conversation_id=? AND item_kind='crew_snapshot'", "crew-import"))
        assertEquals(1, MemoryTimelineRepository(database).readCrewMissionSnapshots("crew-import").size)
    }

    @Test fun malformedOrdinaryLinesAreReportedAndSkippedWithoutLosingFollowingRows() = onDatabaseThread {
        val ledger = File(temporary.root, "corrupt.jsonl")
        val out = FileOutputStream(ledger)
        out.write(jsonRow(null, "user", "content" to "before", "messageId" to "before").toString().toByteArray(StandardCharsets.UTF_8))
        out.write('\n'.code)
        out.write("{\"type\":\"future\",\"x\":1,\"x\":2}\n".toByteArray(StandardCharsets.UTF_8))
        out.write("{broken json}\n".toByteArray(StandardCharsets.UTF_8))
        out.write("{\"type\":\"assistant\",\"content\":\"bad-utf8-".toByteArray(StandardCharsets.UTF_8))
        out.write(byteArrayOf(0xc3.toByte(), 0x28))
        out.write("\"}\n".toByteArray(StandardCharsets.UTF_8))
        out.write(jsonRow(null, "assistant", "content" to "after", "messageId" to "after").toString().toByteArray(StandardCharsets.UTF_8))
        out.write('\n'.code)
        out.write("{\"role\":\"user\",\"content\":\"truncated\"}".toByteArray(StandardCharsets.UTF_8))
        out.close()
        val report = importer().importConversation("corrupt-chat", ledger, writeMeta("corrupt.meta.json"))
        assertEquals("done", report.state)
        assertEquals(6L, report.physicalLines)
        assertEquals(4L, report.skippedLines)
        assertTrue(report.issues.any { it.reason.contains("duplicate_key") })
        assertTrue(report.issues.any { it.reason.startsWith("invalid_json") })
        assertTrue(report.issues.any { it.reason.contains("invalid_utf8") })
        assertTrue(report.issues.any { it.reason == "truncated_final_line" })
        assertEquals(listOf("before", "after"), database.testChatSql.query(
            "SELECT p.payload FROM message_parts p JOIN messages m ON m.conversation_id=p.conversation_id AND m.message_id=p.message_id " +
                "WHERE m.conversation_id=? ORDER BY m.seq", arrayOf("corrupt-chat"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } })
    }

    @Test fun strictJournalViolationFailsClosedAndKeepsLegacySourceUncut() = onDatabaseThread {
        val sessionId = "jarvys-workspace-12345678-1234-1234-1234-123456789abc"
        val malformed = jsonRow("workspace_input_incorporation", "workspaceInputIncorporationSchemaVersion" to 1)
        val ledger = writeLedger("strict-bad", listOf(malformed))
        val report = importer().importConversation(sessionId, ledger, writeMeta("strict-bad.meta.json"))
        assertEquals("failed", report.state)
        assertTrue(report.validations.any { !it.passed })
        assertTrue("failure report must not mutate or truncate the input ledger", ledger.readText().contains("workspace_input_incorporation"))
        assertTrue(report.toDiagnosticText().length < 4096)
    }

    @Test fun legacyModelToolPayloadsBeyondCurrentInlineThresholdArePreserved() = onDatabaseThread {
        val largeText = "payload".repeat(2_000)
        // The legacy ledger's identifier is opaque and does not have to be a generated UUID.
        val batchId = "legacy-batch-large-payload"
        val callId = "large-call"
        val rows = listOf(
            jsonRow(null, "user", "content" to "request", "messageId" to "large-owner", "timestamp" to 1.0),
            jsonRow("model_tool_calls", "batchId" to batchId, "userMessageId" to "large-owner", "messageIndex" to 0,
                "calls" to JSONArray().put(JSONObject().put("id", callId).put("name", "large-tool")
                    .put("arguments", JSONObject().put("payload", largeText).toString()))),
            jsonRow("model_tool_result", "batchId" to batchId, "callId" to callId, "toolName" to "large-tool",
                "lifecycle" to "RESULT", "output" to largeText),
        )
        val ledger = writeLedger("large-model-payload", rows)
        val report = importer().importConversation("large-model-payload", ledger, writeMeta("large-model-payload.meta.json"))

        assertEquals("done", report.state)
        database.testChatSql.query(
            "SELECT c.arguments_json,r.output FROM tool_calls c JOIN tool_results r " +
                "ON r.conversation_id=c.conversation_id AND r.batch_id=c.batch_id AND r.call_id=c.call_id WHERE c.conversation_id=?",
            arrayOf("large-model-payload"),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(0).contains(largeText))
            assertEquals(largeText, cursor.getString(1))
        }
    }

    @Test fun deletedMetadataCreatesTombstoneAndDoesNotImportRows() = onDatabaseThread {
        val ledger = writeLedger("deleted-chat", listOf(jsonRow(null, "user", "content" to "must remain absent", "messageId" to "m1")))
        val meta = File(temporary.root, "deleted.meta.json").apply { writeText("{\"deleted\":true,\"title\":\"private\"}") }
        val report = importer().importConversation("deleted-chat", ledger, meta)
        assertEquals("tombstone", report.state)
        assertTrue(ConversationRepository(database).isTombstoned("deleted-chat"))
        assertEquals(0L, count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", "deleted-chat"))
    }

    @Test fun coordinatorTombstonesDeletedMetadataWhenLedgerWasAlreadyRemoved() = onDatabaseThread {
        val directory = File(temporary.root, "orphan-tombstones").apply { mkdirs() }
        File(directory, "deleted-orphan.meta.json").writeText("{\"deleted\":true}")

        val reports = ImportCoordinator(database).importAll(directory)

        assertEquals(1, reports.size)
        assertEquals("deleted-orphan", reports.single().conversationId)
        assertEquals("tombstone", reports.single().state)
        assertTrue(ConversationRepository(database).isTombstoned("deleted-orphan"))
    }

    @Test fun interruptedBatchResumesAtCommittedCheckpointAndIsIdempotent() = onDatabaseThread {
        val rows = (1..17).map { index -> jsonRow(null, if (index % 2 == 0) "assistant" else "user", "content" to "body-$index", "messageId" to "resume-$index", "timestamp" to index.toDouble()) }
        val ledger = writeLedger("resume-source", rows)
        val meta = writeMeta("resume-source.meta.json")
        val first = importer(LedgerImportOptions(batchSize = 4, beforeBatchCommit = { batch -> if (batch == 2) error("injected_batch_failure") }))
            .importConversation("resume-chat", ledger, meta)
        assertEquals("failed", first.state)
        assertTrue(first.toDiagnosticText().contains("injected_batch_failure"))
        val checkpoint = database.testChatSql.query("SELECT import_offset FROM conversations WHERE id=?", arrayOf("resume-chat"))
            .use { assertTrue(it.moveToFirst()); it.getLong(0) }
        assertTrue(checkpoint > 0L && checkpoint < ledger.length())

        val resumed = importer(LedgerImportOptions(batchSize = 3)).importConversation("resume-chat", ledger, meta)
        val clean = importRows("clean-chat", rows)
        assertEquals("done", resumed.state)
        assertEquals("done", clean.state)
        assertEquals(messageDigest("resume-chat"), messageDigest("clean-chat"))
        assertEquals(count("SELECT COUNT(*) FROM conversation_items WHERE conversation_id=?", "clean-chat"),
            count("SELECT COUNT(*) FROM conversation_items WHERE conversation_id=?", "resume-chat"))
        val repeated = importer().importConversation("resume-chat", ledger, meta)
        assertEquals("done", repeated.state)
        assertEquals(17L, count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", "resume-chat"))
    }

    @Test fun validationDetectsDatabaseMutationAfterImport() = onDatabaseThread {
        val ledger = writeLedger("validate-source", listOf(jsonRow(null, "user", "content" to "original", "messageId" to "validate-1", "timestamp" to 12.5)))
        val meta = writeMeta("validate.meta.json")
        assertEquals("done", importer().importConversation("validate-chat", ledger, meta).state)
        database.testChatSql.execSQL("UPDATE message_parts SET payload='tampered' WHERE conversation_id='validate-chat'")
        val report = importer().importConversation("validate-chat", ledger, meta)
        assertTrue("repeat import must revalidate persisted payloads", report.validations.any { !it.passed })
    }

    @Test fun importReaderContinuesWhenWorkerThreadIsInterruptedDuringStreaming() {
        val ledger = writeLedger("interrupted-reader", (1..40).map { jsonRow(null, "user", "content" to "row-$it", "messageId" to "i-$it") })
        val meta = writeMeta("interrupted-reader.meta.json")
        val result = databaseExecutor.submit<Pair<ImportReport, Long>> {
            Thread.currentThread().interrupt()
            val interruptedDuringStreaming = AtomicBoolean(false)
            try {
                val report = runBlocking {
                    importer(LedgerImportOptions(batchSize = 1, onProgress = { progress ->
                        if (progress.bytesProcessed > 0L) {
                            if (interruptedDuringStreaming.compareAndSet(false, true)) Thread.currentThread().interrupt()
                            else Thread.interrupted()
                        }
                    })).importConversation("interrupted-reader", ledger, meta)
                }
                val messageCount = count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", "interrupted-reader")
                report to messageCount
            }
            finally { Thread.interrupted() }
        }.get(90, TimeUnit.SECONDS)
        assertEquals("done", result.first.state)
        assertEquals(40L, result.second)
    }

    @Test fun largeLedgerUsesLineStreamingForFiftyMegabytesOfThreeHundredKilobyteRows() = onDatabaseThread {
        val file = File(temporary.root, "large-stream.jsonl")
        val payloadBytes = 300 * 1024
        val prefix = "{\"type\":\"future_large\",\"payload\":\"".toByteArray(StandardCharsets.UTF_8)
        val suffix = "\"}\n".toByteArray(StandardCharsets.UTF_8)
        val filler = ByteArray(payloadBytes - prefix.size - suffix.size) { 'x'.code.toByte() }
        var rows = 0L
        BufferedOutputStream(FileOutputStream(file), 1024 * 1024).use { output ->
            while (file.length() < 50L * 1024L * 1024L) {
                output.write(prefix); output.write(filler); output.write(suffix); rows++
            }
        }
        assertTrue(file.length() >= 50L * 1024L * 1024L)
        val meta = writeMeta("large-stream.meta.json")
        System.gc()
        val usedBefore = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val report = importer(LedgerImportOptions(batchSize = 7)).importConversation("large-stream", file, meta)
        System.gc()
        val usedAfter = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        assertEquals("done", report.state)
        assertEquals(rows, report.unknownLines)
        assertTrue("streaming should not retain an input-sized heap after import", usedAfter - usedBefore < 40L * 1024L * 1024L)
    }

    @Test fun modelTranscriptMatchesLegacyRestoreProjectionForToolProtocol() = onDatabaseThread {
        val owner = "transcript-user"
        val batch = "32345678-1234-1234-1234-123456789abc"
        val rows = listOf(
            jsonRow(null, "user", "content" to "ask", "messageId" to owner, "timestamp" to 50.0),
            jsonRow("model_tool_calls", "batchId" to batch, "userMessageId" to owner, "messageIndex" to 0, "assistantText" to "Working",
                "assistantProgress" to true, "calls" to JSONArray().put(JSONObject().put("id", "c1").put("name", "lookup").put("arguments", JSONObject().put("q", "x")))),
            jsonRow("model_tool_started", "batchId" to batch, "callId" to "c1"),
            jsonRow("model_tool_result", "batchId" to batch, "callId" to "c1", "toolName" to "lookup", "lifecycle" to "RESULT", "output" to "found"),
        )
        val report = importRows("transcript-chat", rows)
        assertEquals("done", report.state)
        val expectedRows = rows.filter { it.optString("type").startsWith("model_") }
        val restoreMethod = Class.forName("com.jarvys.agent.MainChatTranscriptStore").getDeclaredMethod(
            "restore", List::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val expected = restoreMethod.invoke(null, expectedRows, 0, expectedRows.size) as Map<Int, List<ConversationTurn>>
        val snapshot = ModelTranscriptRepository(database).readModelTranscript("transcript-chat")
        val actualBatch = snapshot.entries.filterIsInstance<ModelTranscriptEntry.Batch>().single().value
        assertEquals(1, expected.size)
        val legacyGroup = expected.values.single()
        assertEquals(2, legacyGroup.size)
        assertEquals(ConversationTurn.Kind.TOOL_CALLS, legacyGroup[0].kind)
        assertEquals(ConversationTurn.Kind.TOOL_RESULT, legacyGroup[1].kind)
        fun legacyTurnShape(turn: ConversationTurn): List<Any?> = when (turn.kind) {
            ConversationTurn.Kind.TOOL_CALLS -> listOf(
                turn.kind,
                turn.content,
                turn.toolCalls.map { call -> listOf(call.id, call.name, call.arguments) },
            )
            ConversationTurn.Kind.TOOL_RESULT -> listOf(turn.kind, turn.toolCallId, turn.toolName, turn.content)
            else -> error("unexpected restored model turn ${turn.kind}")
        }
        val actualTurns = buildList<List<Any?>> {
            add(listOf(
                ConversationTurn.Kind.TOOL_CALLS,
                actualBatch.batch.assistant_text,
                actualBatch.calls.map { state ->
                    val arguments = JSONObject(state.call.arguments_json)
                    val parsedArguments = arguments.keys().asSequence().associateWith(arguments::get)
                    listOf(state.call.call_id, state.call.name, parsedArguments)
                },
            ))
            actualBatch.calls.forEach { state ->
                state.result?.let { result ->
                    add(listOf(ConversationTurn.Kind.TOOL_RESULT, state.call.call_id, state.call.name, result.output))
                }
            }
        }
        assertEquals("Room transcript must match MainChatTranscriptStore.restore", legacyGroup.map(::legacyTurnShape), actualTurns)
        assertTrue("restored complete legacy batches must remain complete in Room", actualBatch.complete)
    }

    private suspend fun importRows(sessionId: String, rows: List<JSONObject>): ImportReport =
        importer().importConversation(sessionId, writeLedger("$sessionId-source", rows), writeMeta("$sessionId.meta.json"))

    private fun importer(options: LedgerImportOptions = LedgerImportOptions()) = LedgerImporter(database, options)

    private fun writeLedger(name: String, rows: List<JSONObject>): File = File(temporary.root, "$name.jsonl").apply {
        outputStream().bufferedWriter(StandardCharsets.UTF_8).use { writer -> rows.forEach { writer.append(it.toString()).append('\n') } }
    }

    private fun writeMeta(name: String): File = File(temporary.root, name).apply { writeText("{}", StandardCharsets.UTF_8) }

    private fun jsonRow(type: String, firstField: Pair<String, Any?>, vararg fields: Pair<String, Any?>): JSONObject = JSONObject().apply {
        put("type", type); put(firstField.first, firstField.second)
        fields.forEach { (key, value) -> put(key, value) }
    }

    private fun jsonRow(type: String? = null, role: String? = null, vararg fields: Pair<String, Any?>): JSONObject = JSONObject().apply {
        if (role != null) put("role", role)
        if (type != null) put("type", type)
        fields.forEach { (key, value) -> put(key, value) }
    }

    private fun crewSnapshot(conversationId: String, missionId: String, timestamp: Double, synthesis: String): JSONObject =
        CrewMissionSnapshot(missionId, conversationId, com.jarvys.agent.crew.CrewProcessIdentity.ID,
            "Crew title", "Original task", "agent", "DONE", synthesis, 1000L, 2000L, emptyList(), emptyList())
            .toJson().put("timestamp", timestamp)

    private fun hashCrewSnapshot(synthesis: String): String {
        val canonical = crewSnapshot("crew-import", "same-mission", 0.0, synthesis).apply { remove("timestamp") }.toString()
        val escaped = buildString(canonical.length + 1) {
            canonical.forEach { character ->
                if (Character.isSurrogate(character)) append("\\u").append(character.code.toString(16).padStart(4, '0')) else append(character)
            }
            append('\n')
        }
        return MessageDigest.getInstance("SHA-256").digest(escaped.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun count(sql: String, vararg args: Any): Long = database.testChatSql.query(sql, args).use { it.moveToFirst(); it.getLong(0) }

    private fun messageDigest(conversationId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        database.testChatSql.query(
            "SELECT m.role,p.payload FROM messages m JOIN message_parts p ON p.conversation_id=m.conversation_id AND p.message_id=m.message_id " +
                "WHERE m.conversation_id=? AND p.kind='text' ORDER BY m.message_index", arrayOf(conversationId),
        ).use { c ->
            while (c.moveToNext()) {
                val value = (c.getString(0) + "\u0000" + c.getString(1)).toByteArray(StandardCharsets.UTF_8)
                digest.update(value.size.toString().toByteArray(StandardCharsets.UTF_8)); digest.update(0); digest.update(value)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
