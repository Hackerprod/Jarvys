package com.jarvys.agent.chatdb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class M1bRepositoriesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val databaseExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "m1b-repository-test").apply { isDaemon = true } }
    private lateinit var database: JarvysChatDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var model: ModelTranscriptRepository
    private lateinit var artifacts: ConversationArtifactsRepository
    private lateinit var memory: MemoryTimelineRepository

    @Before fun openDatabase() {
        database = JarvysChatDatabaseProvider.openDatabaseFileForTests(context, File(temporary.root, "m1b.db"))
        conversations = ConversationRepository(database)
        model = ModelTranscriptRepository(database)
        artifacts = ConversationArtifactsRepository(database)
        memory = MemoryTimelineRepository(database)
    }

    @After fun closeDatabase() {
        if (::database.isInitialized) JarvysChatDatabaseProvider.closeDatabaseFileForTests(database)
        databaseExecutor.shutdownNow()
    }

    private fun <T> onDatabaseThread(block: suspend () -> T): T =
        databaseExecutor.submit<T> { runBlocking { block() } }.get(60, TimeUnit.SECONDS)

    @Test fun modelProtocolUsesFirstResultsLastProviderOutputAndPreservesCompactionParity() = onDatabaseThread {
        success(conversations.ensureConversation("protocol"))
        val user = success(conversations.appendMessage(AppendMessageInput(
            conversationId = "protocol", role = "user", parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "question")),
        )))
        val owner = user.messageId
        assertTrue(model.appendToolBatch(ModelToolBatchInput(
            conversationId = "protocol", batchId = UUID.randomUUID().toString(), ownerUserMessageId = owner,
            messageIndex = 0, assistantText = "oversized args", calls = listOf(ModelCallInput("large-args", "reader", "x".repeat(8_001))),
        )) is ChatDbResult.Invalid)
        val modelArtifact = success(model.appendModelArtifact(
            "protocol", "artifact-model-1", "chats/protocol/model.txt", "a".repeat(64), 10, 16L * 1024L * 1024L,
        ))
        assertTrue(model.appendModelArtifact(
            "protocol", "artifact-model-over", "chats/protocol/over.txt", "b".repeat(64), 1, 1,
        ) is ChatDbResult.Invalid)
        val completeId = UUID.randomUUID().toString()
        val incompleteId = UUID.randomUUID().toString()
        success(model.appendToolBatch(ModelToolBatchInput(
            conversationId = "protocol", batchId = completeId, ownerUserMessageId = owner, messageIndex = 0,
            assistantText = "checking", calls = listOf(ModelCallInput("complete-call", "reader", "{\"path\":\"one\"}")),
        )))
        assertTrue(model.appendToolResult(ModelToolResultInput(
            "protocol", completeId, "complete-call", "other-tool", "RESULT", "must conflict",
        )) is ChatDbResult.Conflict)
        val first = success(model.appendToolResult(ModelToolResultInput(
            "protocol", completeId, "complete-call", "reader", "RESULT", "first result", artifactId = modelArtifact.artifact_id,
        )))!!
        assertTrue(model.appendToolResult(ModelToolResultInput(
            "protocol", completeId, "complete-call", "reader", "RESULT", "x".repeat(8_001),
        )) is ChatDbResult.Invalid)
        success(model.appendToolResult(ModelToolResultInput("protocol", completeId, "complete-call", "reader", "RESULT", "duplicate result")))
        success(model.appendProviderOutputItems("protocol", owner, 0, "[{\"item\":\"first\"}]"))
        success(model.appendProviderOutputItems("protocol", owner, 0, "[{\"item\":\"last\"}]"))
        success(model.appendModelError("protocol", owner, 0, "provider_error"))
        success(model.appendToolBatch(ModelToolBatchInput(
            conversationId = "protocol", batchId = incompleteId, ownerUserMessageId = owner, messageIndex = 0,
            assistantText = "second attempt", calls = listOf(
                ModelCallInput("incomplete-call", "writer", "{}"), ModelCallInput("never-launched-call", "writer", "{}"),
            ),
        )))
        success(model.setCallLifecycle("protocol", incompleteId, "incomplete-call", "STARTED"))
        success(model.setCallLifecycle("protocol", incompleteId, "never-launched-call", "NEVER_LAUNCHED"))

        val activity = ToolActivityInput("protocol", "event-1", owner, "execution-1", "incomplete-call", "writer", stage = "tool_call", timestampMillis = 10L)
        val firstResolution = success(model.appendToolActivity(activity))!!
        assertEquals(incompleteId, firstResolution.source_batch_id)
        val exactResolution = success(model.resolveActivitySourceBatch("protocol", owner, "execution-1", "incomplete-call"))
        assertEquals(incompleteId, exactResolution.batchId)
        assertTrue(exactResolution.exactExecutionMatch)
        success(model.appendToolActivity(activity.copy(eventId = "event-2", stage = "tool_progress", timestampMillis = 11L)))
        assertEquals(2, model.readToolActivities("protocol", owner, "execution-1").size)

        success(model.appendToolPresentation(ToolPresentationInput("protocol", owner, callId = "incomplete-call", toolName = "writer", stage = "tool_call")))
        success(model.appendToolPresentation(ToolPresentationInput("protocol", owner, callId = "incomplete-call", toolName = "writer", stage = "tool_result")))
        assertEquals("tool_result", model.latestToolPresentation("protocol", incompleteId, "incomplete-call")!!.stage)
        success(model.appendReflectionTool(ReflectionToolInput("protocol", owner, callId = "incomplete-call", toolName = "writer", source = "connector", stage = "tool_result", marker = "safe marker")))

        val beforeCompactionRows = database.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM conversation_items WHERE conversation_id=?", arrayOf("protocol"),
        ).use { it.moveToFirst(); it.getLong(0) }
        assertEquals(16L, beforeCompactionRows)
        val compaction = success(memory.appendCompaction(CompactionInput(
            conversationId = "protocol", summary = "summary", firstKept = 1, summarizedMessages = 1,
            createdAtSeconds = 123.5,
        )))
        assertEquals("legacy-123.5-$beforeCompactionRows", compaction.compaction_id)

        val transcript = model.readModelTranscript("protocol")
        assertEquals(
            listOf("Artifact", "Batch", "ResultActivity", "ResultActivity", "ProviderOutput", "ProviderOutput", "Error", "Batch", "Lifecycle", "Lifecycle"),
            transcript.entries.map { entry -> when (entry) {
                is ModelTranscriptEntry.Artifact -> "Artifact"
                is ModelTranscriptEntry.Batch -> "Batch"
                is ModelTranscriptEntry.ResultActivity -> "ResultActivity"
                is ModelTranscriptEntry.ProviderOutput -> "ProviderOutput"
                is ModelTranscriptEntry.Error -> "Error"
                is ModelTranscriptEntry.Lifecycle -> "Lifecycle"
            } },
        )
        assertEquals("chats/protocol/model.txt", (transcript.entries.first() as ModelTranscriptEntry.Artifact).value.path)
        val batchEntries = transcript.entries.filterIsInstance<ModelTranscriptEntry.Batch>()
        assertEquals(listOf(completeId, incompleteId), batchEntries.map { it.value.batch.batch_id })
        assertEquals(owner, batchEntries.first().value.batch.owner_user_message_id)
        assertEquals(0L, batchEntries.first().value.batch.message_index)
        assertEquals("checking", batchEntries.first().value.batch.assistant_text)
        assertEquals("{\"path\":\"one\"}", batchEntries.first().value.calls.single().call.arguments_json)
        assertTrue(batchEntries[0].value.complete)
        assertFalse(batchEntries[1].value.complete)
        assertEquals(listOf("STARTED", "NEVER_LAUNCHED"), batchEntries[1].value.calls.map { it.call.lifecycle })
        val resultEvents = transcript.entries.filterIsInstance<ModelTranscriptEntry.ResultActivity>()
        assertEquals(2, resultEvents.size)
        assertEquals(listOf("first result", "first result"), resultEvents.map { it.firstResult!!.output })
        assertEquals("artifact-model-1", resultEvents.first().firstResult!!.file_artifact_id)
        assertEquals(first.seq, resultEvents.first().firstResult!!.seq)
        assertEquals(resultEvents.last().seq, batchEntries.first().value.batch.last_activity_seq)
        assertEquals("[{\"item\":\"last\"}]", transcript.latestProviderOutputItemsByOwner[owner]!!.items_json)
        assertEquals(16L, transcript.compactionBoundaryRowIndex)
        assertEquals(compaction.seq, transcript.compactionBoundarySeq)
        assertEquals(1L, transcript.firstKeptMessageIndex)
        assertEquals(incompleteId, success(model.latestBatchForOwner("protocol", owner))!!.batch.batch_id)
        assertEquals(listOf(first), model.toolResultsForOwner("protocol", owner))

        val plan = database.openHelper.writableDatabase.query(
            "EXPLAIN QUERY PLAN SELECT source_batch_id FROM tool_activities WHERE conversation_id=? AND owner_user_message_id=? AND execution_id=? AND call_id=? AND source_batch_id IS NOT NULL ORDER BY seq DESC LIMIT 1",
            arrayOf("protocol", owner, "execution-1", "incomplete-call"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) } }.joinToString(" ")
        assertTrue(plan.contains("idx_tool_activities_execution_call"))
    }

    @Test fun undecodableModelCallRowsRetainRawCallsAndDecodeFailure() = onDatabaseThread {
        success(conversations.ensureConversation("undecodable"))
        val user = success(conversations.appendMessage(AppendMessageInput(
            conversationId = "undecodable", role = "user", parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "question")),
        )))
        val batchId = UUID.randomUUID().toString()
        val batch = success(model.appendToolBatch(ModelToolBatchInput(
            conversationId = "undecodable", batchId = batchId, ownerUserMessageId = user.messageId, messageIndex = 0,
            assistantText = "opaque", calls = emptyList(), rawCalls = "[{not-json]", callsDecodeError = "malformed_calls",
        )))
        assertEquals("[{not-json]", batch.batch.raw_calls)
        assertEquals("malformed_calls", batch.batch.calls_decode_error)
        assertFalse(batch.complete)
        assertEquals(batchId, model.readModelTranscript("undecodable").entries.filterIsInstance<ModelTranscriptEntry.Batch>().single().value.batch.batch_id)
    }

    @Test fun failedBatchChildInsertRollsBackItsSequenceAndSpineRow() = onDatabaseThread {
        success(conversations.ensureConversation("atomic-model"))
        val beforeNext = nextSeq("atomic-model")
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER injected_tool_call_failure BEFORE INSERT ON tool_calls BEGIN SELECT RAISE(ABORT,'injected failure'); END",
        )

        val batchId = UUID.randomUUID().toString()
        val result = model.appendToolBatch(ModelToolBatchInput(
            conversationId = "atomic-model", batchId = batchId, ownerUserMessageId = null, messageIndex = 0,
            assistantText = "atomic", calls = listOf(ModelCallInput("call", "reader", "{}")),
        ))
        assertTrue(result is ChatDbResult.Conflict)
        assertEquals(beforeNext, nextSeq("atomic-model"))
        assertEquals(0L, count("SELECT COUNT(*) FROM conversation_items WHERE conversation_id=?", arrayOf("atomic-model")))
        assertEquals(0L, count("SELECT COUNT(*) FROM tool_batches WHERE conversation_id=?", arrayOf("atomic-model")))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER injected_tool_call_failure")
    }

    @Test fun concurrentM1bAppendsAllocateContiguousConversationSequences() = onDatabaseThread {
        success(conversations.ensureConversation("concurrent-model"))
        val workers = Executors.newFixedThreadPool(4)
        try {
            val futures = workers.invokeAll((0 until 24).map { index ->
                Callable {
                    runBlocking { model.appendModelError("concurrent-model", null, index.toLong(), "error-$index") }
                }
            })
            futures.forEach { future -> assertTrue(future.get() is ChatDbResult.Success) }
        } finally {
            workers.shutdownNow()
        }
        val seqs = database.openHelper.writableDatabase.query(
            "SELECT seq FROM conversation_items WHERE conversation_id=? ORDER BY seq", arrayOf("concurrent-model"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        assertEquals((1L..24L).toList(), seqs)
        assertEquals(25L, nextSeq("concurrent-model"))
    }

    @Test fun compactionAcknowledgementsExpandClearAllAndLaterRegenerationInvalidatesSummary() = onDatabaseThread {
        success(conversations.ensureConversation("reflection"))
        val user = success(conversations.appendMessage(AppendMessageInput(
            conversationId = "reflection", role = "user", parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "keep this")),
        )))
        val assistant = success(conversations.appendMessage(AppendMessageInput(
            conversationId = "reflection", role = "assistant", status = "COMPLETED", ownerUserMessageId = user.messageId,
            parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "answer")),
        )))
        val reflectionTool = success(model.appendReflectionTool(ReflectionToolInput(
            "reflection", user.messageId, callId = "call-1", toolName = "search", source = "connector.web",
            stage = "tool_result", marker = "[result summarized]",
        )))
        val payload = memory.buildReflectionPayload("reflection")
        assertEquals(assistant.messageId, payload.endMessageId)
        assertEquals(2, payload.messageCount)
        assertTrue(payload.text.contains("[result summarized]"))
        assertTrue(reflectionTool.seq > assistant.seq)

        val first = success(memory.appendCompaction(CompactionInput("reflection", "summary one", 1, summarizedMessages = 1, createdAtSeconds = 1.25)))
        val second = success(memory.appendCompaction(CompactionInput("reflection", "summary two", 1, summarizedMessages = 1, createdAtSeconds = 2.25)))
        assertEquals(listOf(first.compaction_id, second.compaction_id), memory.pendingCompactionIds("reflection"))
        success(memory.acknowledgeCompactions("reflection", clearAll = true))
        assertTrue(memory.pendingCompactionIds("reflection").isEmpty())
        val expanded = database.openHelper.writableDatabase.query(
            "SELECT compaction_id FROM compaction_reflection_acks WHERE conversation_id=? ORDER BY compaction_id", arrayOf("reflection"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(listOf(first.compaction_id, second.compaction_id).sorted(), expanded)

        val checkpoint = ReflectionInput("reflection", "reflection_checkpoint", reflectionId = "checkpoint-1",
            throughMessageId = assistant.messageId, acknowledgedCompactionIds = listOf(first.compaction_id), result = "skipped")
        success(memory.appendReflectionCheckpoint(checkpoint))
        val commit = ReflectionInput("reflection", "reflection_commit", reflectionId = "group-1",
            throughMessageId = assistant.messageId, summary = "learned", revisionIds = listOf(7L, 9L), status = "completed", trigger = "manual")
        success(memory.appendReflectionCommit(commit))
        assertTrue(memory.hasReflectionCommit("reflection", "group-1"))
        assertEquals(2L, database.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM reflection_revisions WHERE conversation_id=?", arrayOf("reflection"),
        ).use { it.moveToFirst(); it.getLong(0) })
        success(memory.appendReflectionUndo("reflection", "group-1"))

        assertNotNull(memory.activeCompaction("reflection"))
        success(conversations.hideAssistant("reflection", assistant.messageId, "assistant_regenerated"))
        assertNull(memory.activeCompaction("reflection"))
        val replacement = success(memory.appendCompaction(CompactionInput("reflection", "rebuilt summary", 1, summarizedMessages = 1, createdAtSeconds = 3.25)))
        assertEquals(replacement.compaction_id, memory.activeCompaction("reflection")!!.compaction_id)
    }

    @Test fun deliveredArtifactsAreContentIdempotentAndGeneratedImagesRemainOwnedByOneChat() = onDatabaseThread {
        success(conversations.ensureConversation("files"))
        val delivered = DeliveredArtifactInput(
            conversationId = "files", artifactId = "artifact-1", name = "report.pdf", mimeType = "application/pdf",
            sizeBytes = 31, kind = "FILE", relativePath = "chats/files/report.pdf", sha256 = "a".repeat(64),
            moduleId = "presentation", artifactType = "x-jarvys/chart", manifestJson = "{\"opaque\":true}", title = "Report",
        )
        val first = success(artifacts.appendDeliveredArtifact(delivered))
        val duplicate = success(artifacts.appendDeliveredArtifact(delivered))
        assertEquals(first.seq, duplicate.seq)
        assertTrue(artifacts.appendDeliveredArtifact(delivered.copy(title = "Changed")) is ChatDbResult.Conflict)
        assertEquals("x-jarvys/chart", first.artifact_type)
        assertEquals("application/pdf", database.openHelper.writableDatabase.query(
            "SELECT mime_type FROM file_refs WHERE id=?", arrayOf(first.file_ref_id),
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) })
        assertTrue(artifacts.findChatFile("files", "delivered", "artifact-1") is ChatFileOwnership.Delivered)
        assertEquals(listOf("artifact-1"), artifacts.artifactsForConversation("files").map { it.artifact_id })
        assertFalse(artifacts.conversationHasAttachments("files"))
        assertTrue(artifacts.conversationHasPrivateImagesOrAttachments("files"))

        val completed = success(artifacts.appendGeneratedImage(GeneratedImageInput(
            conversationId = "files", prompt = "mountain", status = "COMPLETED", imagePath = "chats/files/image.png",
            sha256 = "b".repeat(64), bytes = 99, mimeType = "image/png",
        )))
        val failed = success(artifacts.appendGeneratedImage(GeneratedImageInput(
            conversationId = "files", prompt = "unavailable", status = "FAILED", error = "provider failed",
        )))
        assertTrue(artifacts.ownsGeneratedFile("files", completed.image_path!!))
        assertFalse(artifacts.ownsGeneratedFile("files", failed.image_path.orEmpty()))
        assertFalse(artifacts.ownsGeneratedFile("other-chat", completed.image_path!!))
    }

    @Test fun crewSnapshotsAcceptVersionsOneThroughFourDeduplicateAndRecoverOlderProcess() = onDatabaseThread {
        success(conversations.ensureConversation("crew"))
        val v1 = crewSnapshot("crew", "mission-a", 1, "old title", "DONE", "DONE", "text-1")
        success(memory.appendCrewSnapshot("crew", v1))
        val firstSeq = database.openHelper.writableDatabase.query(
            "SELECT first_snapshot_seq FROM crew_missions WHERE conversation_id=? AND mission_id=?", arrayOf("crew", "mission-a"),
        ).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }
        val nextSeq = nextSeq("crew")
        success(memory.appendCrewSnapshot("crew", JSONObject(v1.toString())))
        assertEquals(nextSeq, nextSeq("crew"))
        success(memory.appendCrewSnapshot("crew", crewSnapshot("crew", "mission-a", 2, "title-v2", "DONE", "DONE", "text-2")))
        success(memory.appendCrewSnapshot("crew", crewSnapshot("crew", "mission-a", 3, "title-v3", "DONE", "DONE", "text-3")))
        val latestA = success(memory.appendCrewSnapshot("crew", crewSnapshot("crew", "mission-a", 4, "latest title", "DONE", "DONE", "text\uD800exact")))
        success(memory.appendCrewSnapshot("crew", crewSnapshot("crew", "mission-b", 4, "running", "RUNNING", "RUNNING", "live")))
        assertEquals("latest title", latestA.title)
        assertEquals(firstSeq, database.openHelper.writableDatabase.query(
            "SELECT first_snapshot_seq FROM crew_missions WHERE conversation_id=? AND mission_id=?", arrayOf("crew", "mission-a"),
        ).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) })
        val order = memory.readCrewMissionSnapshots("crew")
        assertEquals(1L, firstSeq)
        assertEquals(listOf("mission-a", "mission-b"), order.map { it.missionId })
        assertEquals("latest title", order.first().title)
        val textBlob = database.openHelper.writableDatabase.query(
            "SELECT text FROM crew_messages WHERE conversation_id=? AND mission_id=?", arrayOf("crew", "mission-a"),
        ).use { c -> assertTrue(c.moveToFirst()); c.getBlob(0) }
        assertArrayEquals(ExactUtf16.encode("text\uD800exact"), textBlob)

        val recovered = memory.recoverCrewMissions("crew", "new-process")
        assertEquals("INTERRUPTED", recovered.last().status)
        assertTrue(recovered.last().bots.single().resumeRequired)
        assertEquals("new-process", recovered.last().processId)
    }

    @Test fun failedModelResultRollsBackSequenceAndConcurrentAppendsStayContiguous() {
        onDatabaseThread {
            success(conversations.ensureConversation("rollback"))
            val user = success(conversations.appendMessage(AppendMessageInput(
                conversationId = "rollback", role = "user", parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "prompt")),
            )))
            val batchId = UUID.randomUUID().toString()
            success(model.appendToolBatch(ModelToolBatchInput("rollback", batchId, user.messageId, 0, "", calls = listOf(ModelCallInput("call", "tool", "{}")))))
            val before = nextSeq("rollback")
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER injected_m1b_result_failure BEFORE INSERT ON tool_results BEGIN SELECT RAISE(ABORT,'injected failure'); END")
            assertTrue(model.appendToolResult(ModelToolResultInput("rollback", batchId, "call", "tool", "RESULT", "never persisted")) is ChatDbResult.Conflict)
            assertEquals(before, nextSeq("rollback"))
            assertEquals(0L, count("SELECT COUNT(*) FROM conversation_items WHERE conversation_id=? AND item_kind='model_tool_result'", arrayOf("rollback")))
            assertEquals(0L, count("SELECT COUNT(*) FROM tool_results WHERE conversation_id=?", arrayOf("rollback")))
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER injected_m1b_result_failure")
        }
        onDatabaseThread { success(conversations.ensureConversation("concurrent-m1b")) }
        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = (0 until 24).map { index -> pool.submit(Callable {
                runBlocking { model.appendModelError("concurrent-m1b", null, index.toLong(), "error-$index") }
            }) }
            futures.forEach { assertTrue(it.get(45, TimeUnit.SECONDS) is ChatDbResult.Success) }
        } finally { pool.shutdownNow() }
        onDatabaseThread {
            val seqs = database.openHelper.writableDatabase.query(
                "SELECT seq FROM conversation_items WHERE conversation_id=? ORDER BY seq", arrayOf("concurrent-m1b"),
            ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
            assertEquals((1L..24L).toList(), seqs)
            assertEquals(25L, nextSeq("concurrent-m1b"))
        }
    }

    @Test fun globalMarkersIncludeResolvedDecisionsReactionsUsedRepliesAndPendingInterrupts() = onDatabaseThread {
        success(conversations.ensureConversation("markers"))
        val user = success(conversations.appendMessage(AppendMessageInput(
            conversationId = "markers", role = "user", parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "user")),
        )))
        success(conversations.appendDecisionRequest("markers", "decision", "Choose", "Pick", true, listOf(DecisionOptionInput("yes", "Yes"))))
        success(conversations.resolveDecision("markers", "decision", "selected", "yes", "Yes"))
        success(conversations.setReaction("markers", user.stableId, "👍"))
        val proactive = success(conversations.appendProactiveAssistantMessageIfAbsent("markers", "decision-reply", "suggestions", "thread", listOf(SuggestedReplyInput("Yes", "yes"))))
        success(conversations.consumeSuggestedReplies("markers", proactive.messageId))
        val interrupt = success(conversations.appendInterruptRequest("markers", "continue", listOf("skill-a")))
        success(conversations.dispatchInterruptRequest("markers", interrupt.requestId))

        val markers = memory.readGlobalTimelineMarkers()
        assertEquals("selected", markers.resolvedDecisions["markers" to "decision"]!!.status)
        assertEquals("👍", markers.reactionsByStableMessage["markers" to user.stableId])
        assertEquals(listOf("markers" to proactive.messageId), markers.consumedProactiveReplies)
        assertTrue(markers.pendingInterrupts.any { it.requestId == interrupt.requestId && it.alreadyDispatched && it.acknowledgementRequired && it.skills == listOf("skill-a") })
    }

    private fun crewSnapshot(
        conversation: String, mission: String, version: Int, title: String, missionStatus: String, botStatus: String, text: String,
    ): JSONObject {
        val bot = JSONObject().put("id", "bot").put("name", "Worker").put("status", botStatus)
            .put("mission", "mission text").put("tools", JSONArray().put("search"))
        if (version >= 4) bot.put("cycleId", UUID.nameUUIDFromBytes("cycle-$mission".toByteArray()).toString())
        val message = JSONObject().put("id", "message-$mission").put("conversationId", conversation)
            .put("from", "bot").put("to", "chief").put("type", "FINDING").put("text", text)
            .put("refs", JSONArray().put("ref-a")).put("timestampMillis", 10_000L)
        return JSONObject().put("type", "crew_snapshot").put("crewSchemaVersion", version)
            .put("missionId", mission).put("conversationId", conversation).put("processId", "old-process")
            .put("title", title).put("originalInstructions", "instructions").put("titleSource", "user")
            .put("status", missionStatus).put("synthesis", "synthesis-$title").put("startedAtMillis", 1_000L)
            .put("finishedAtMillis", 0L).put("bots", JSONArray().put(bot)).put("messages", JSONArray().put(message))
            .put("timestamp", 100.25)
    }

    private fun nextSeq(conversationId: String): Long = database.openHelper.writableDatabase.query(
        "SELECT next_seq FROM conversations WHERE id=?", arrayOf(conversationId),
    ).use { assertTrue(it.moveToFirst()); it.getLong(0) }

    private fun count(query: String, args: Array<out Any?>): Long = database.openHelper.writableDatabase.query(query, args)
        .use { assertTrue(it.moveToFirst()); it.getLong(0) }

    private fun <T> success(result: ChatDbResult<T>): T = when (result) {
        is ChatDbResult.Success -> result.value
        else -> throw AssertionError("Expected ChatDbResult.Success; got $result")
    }
}
