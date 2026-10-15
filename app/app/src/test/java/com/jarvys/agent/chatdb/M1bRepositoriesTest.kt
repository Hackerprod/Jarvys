package com.jarvys.agent.chatdb

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.crew.CrewCoordinationEvidence
import com.jarvys.agent.crew.CrewResultEvidence
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    @Test fun firstLedgerWritesCreateTheirConversationInTheSameTransaction() = onDatabaseThread {
        suspend fun assertCreated(conversationId: String, result: ChatDbResult<*>) {
            assertTrue("$conversationId write should succeed: $result", result is ChatDbResult.Success)
            assertNotNull(conversations.readConversation(conversationId))
            assertEquals("$conversationId should contain exactly one committed ledger row", 2L, nextSeq(conversationId))
        }

        assertCreated("first-compaction", memory.appendCompaction(CompactionInput(
            "first-compaction", "summary", 0, summarizedMessages = 0,
        )))
        assertCreated("first-crew-snapshot", memory.appendCrewSnapshot("first-crew-snapshot", crewSnapshot(
            "first-crew-snapshot", "mission", 1, "Worker", "DONE", "DONE", "result",
        )))
        assertCreated("first-generated-image", artifacts.appendGeneratedImage(GeneratedImageInput(
            "first-generated-image", "draw a tree", status = "FAILED", error = "provider unavailable",
        )))
        assertCreated("first-decision", conversations.appendDecisionRequest(
            "first-decision", "decision", "Choose", "Pick one", true, emptyList(),
        ))
        assertCreated("first-tool-batch", model.appendToolBatch(ModelToolBatchInput(
            conversationId = "first-tool-batch", batchId = "batch:opaque", ownerUserMessageId = null,
            messageIndex = 0, assistantText = "", calls = listOf(ModelCallInput("call", "search", "{}")),
        )))
        assertCreated("first-tool-activity", model.appendToolActivity(ToolActivityInput(
            conversationId = "first-tool-activity", eventId = "event", ownerUserMessageId = null,
            executionId = null, callId = null, toolName = "search", stage = "tool_call", timestampMillis = 1L,
        )))
        assertCreated("first-provider-output", model.appendProviderOutputItems(
            "first-provider-output", "owner", 0, "[]",
        ))
        assertCreated("first-model-error", model.appendModelError("first-model-error", null, 0, "provider_error"))
        assertCreated("first-reflection", memory.appendReflectionCheckpoint(ReflectionInput(
            conversationId = "first-reflection", kind = "reflection_checkpoint", throughMessageId = "unpersisted-user",
            completedAssistantSteps = 0, result = "checkpoint",
        )))
        assertCreated("first-model-artifact", model.appendModelArtifact(
            "first-model-artifact", "artifact", "chats/model.txt", "a".repeat(64), 1, 1,
        ))
        assertCreated("first-delivered-artifact", artifacts.appendDeliveredArtifact(DeliveredArtifactInput(
            "first-delivered-artifact", "artifact", "note.txt", "text/plain", 1, "FILE",
            "chats/note.txt", "b".repeat(64),
        )))
        assertCreated("first-interrupt", conversations.appendInterruptRequest("first-interrupt", "Please continue"))

        success(conversations.deleteConversation("first-tombstone", 1L))
        assertTrue(memory.appendCompaction(CompactionInput(
            "first-tombstone", "summary", 0, summarizedMessages = 0,
        )) === ChatDbResult.Tombstoned)
        assertNull(conversations.readConversation("first-tombstone"))
    }

    @Test fun modelProtocolUsesFirstResultsAndExcludesPreBoundaryProviderOutputsAfterCompaction() = onDatabaseThread {
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
        val exactResolution = model.resolveActivitySourceBatch("protocol", owner, "execution-1", "incomplete-call")
        assertEquals(incompleteId, exactResolution.batchId)
        assertTrue(exactResolution.exactExecutionMatch)
        success(model.appendToolActivity(activity.copy(eventId = "event-2", stage = "tool_progress", timestampMillis = 11L)))
        assertEquals(2, model.readToolActivities("protocol", owner, "execution-1").size)

        success(model.appendToolPresentation(ToolPresentationInput("protocol", owner, callId = "incomplete-call", toolName = "writer", stage = "tool_call")))
        success(model.appendToolPresentation(ToolPresentationInput("protocol", owner, callId = "incomplete-call", toolName = "writer", stage = "tool_result")))
        assertEquals("tool_result", model.latestToolPresentation("protocol", incompleteId, "incomplete-call")!!.stage)
        success(model.appendReflectionTool(ReflectionToolInput("protocol", owner, callId = "incomplete-call", toolName = "writer", source = "connector", stage = "tool_result", marker = "safe marker")))

        val beforeCompactionRows = database.testChatSql.query(
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
        assertTrue("pre-boundary provider outputs are excluded from restored state", transcript.latestProviderOutputItemsByOwner.isEmpty())
        assertEquals(16L, transcript.compactionBoundaryRowIndex)
        assertEquals(compaction.seq, transcript.compactionBoundarySeq)
        assertEquals(1L, transcript.firstKeptMessageIndex)
        assertEquals(incompleteId, model.latestBatchForOwner("protocol", owner)!!.batch.batch_id)
        assertEquals(listOf(first), model.toolResultsForOwner("protocol", owner))

        val plan = database.testChatSql.query(
            "EXPLAIN QUERY PLAN SELECT source_batch_id FROM tool_activities WHERE conversation_id=? AND owner_user_message_id=? AND execution_id=? AND call_id=? AND source_batch_id IS NOT NULL ORDER BY seq DESC LIMIT 1",
            arrayOf("protocol", owner, "execution-1", "incomplete-call"),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) } }.joinToString(" ")
        assertTrue(plan.contains("idx_tool_activities_execution_call"))
    }

    @Test fun mismatchedFirstToolResultIsRetainedForLegacyRestorePlaceholder() = onDatabaseThread {
        success(conversations.ensureConversation("first-result"))
        val owner = success(conversations.appendMessage(AppendMessageInput(
            conversationId = "first-result", role = "user", parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "run tool")),
        ))).messageId
        val batchId = UUID.randomUUID().toString()
        success(model.appendToolBatch(ModelToolBatchInput(
            conversationId = "first-result", batchId = batchId, ownerUserMessageId = owner, messageIndex = 0,
            assistantText = "call", calls = listOf(ModelCallInput("call-1", "expected-tool", "{}")),
        )))

        val first = success(model.appendToolResult(ModelToolResultInput(
            "first-result", batchId, "call-1", "different-tool", "RESULT", "legacy payload",
        )))!!
        val later = success(model.appendToolResult(ModelToolResultInput(
            "first-result", batchId, "call-1", "expected-tool", "RESULT", "later payload",
        )))!!
        assertEquals("first result row is retained even when legacy toolName mismatches", first, later)
        assertEquals("different-tool", later.tool_name)
        assertEquals("legacy payload", later.output)

        val transcript = model.readModelTranscript("first-result")
        val restoredCall = transcript.entries.filterIsInstance<ModelTranscriptEntry.Batch>().single()
            .value.calls.single()
        assertEquals("expected-tool", restoredCall.call.name)
        assertEquals("different-tool", restoredCall.result?.tool_name)
    }

    @Test fun unknownAttachmentDigestCanBeCompletedByOwnedArtifact() = onDatabaseThread {
        success(conversations.ensureConversation("digest-shared"))
        val relativePath = "chats/digest-shared/shared.bin"
        val message = success(conversations.appendMessage(AppendMessageInput(
            conversationId = "digest-shared",
            role = "user",
            parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "attached file")),
            attachments = listOf(MessageAttachmentInput(
                attachmentId = "attachment-unknown-digest", name = "shared.bin", mimeType = "application/octet-stream",
                sizeBytes = 4L, kind = "document", relativePath = relativePath, sha256 = null,
            )),
        )))
        val attachmentFileRef = database.testChatSql.query(
            "SELECT file_ref_id FROM message_attachments WHERE conversation_id=? AND message_id=?",
            arrayOf("digest-shared", message.messageId),
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) }

        val knownDigest = "f".repeat(64)
        val artifact = success(model.appendModelArtifact(
            "digest-shared", "shared-model-artifact", relativePath, knownDigest, chars = 4L, bytes = 4L,
        ))
        assertEquals("unknown and known owners share one path identity", attachmentFileRef, artifact.file_ref_id)
        assertEquals(knownDigest, database.testChatSql.query(
            "SELECT sha256 FROM file_refs WHERE id=?", arrayOf(attachmentFileRef),
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) })

        assertTrue("a conflicting known digest is rejected", model.appendModelArtifact(
            "digest-shared", "shared-model-artifact-conflict", relativePath, "e".repeat(64), chars = 4L, bytes = 4L,
        ) is ChatDbResult.Conflict)
    }

    @Test fun suspendReadsAcrossM1bRepositoriesDispatchSqlOffTheMainLooper() {
        onDatabaseThread { success(conversations.ensureConversation("repository-main-read")) }
        val executionSession = WorkspaceIdentity.executionSessionId("a1234567-b123-c123-d123-e123456789ab")
        onDatabaseThread { success(conversations.ensureConversation(executionSession, "workspace_exec")) }
        val strict = StrictJournalRepository(database)
        val missionId = UUID.nameUUIDFromBytes("m1b-main-read-mission".toByteArray()).toString()
        val evidence = CrewResultEvidence.create(executionSession, missionId, "bot-main-read", UUID.randomUUID().toString(), "DONE", "valid evidence", 1_800_000_000_000L)
        onDatabaseThread { success(strict.appendCrewResultEvidence(evidence)) }
        val sqlRanOnMain = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val strictRead = AtomicReference<CrewResultEvidence?>()
        val finished = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                runBlocking {
                    withTimeout(60_000L) {
                        ChatSqlInstrumentation.observeStatements({
                            if (Looper.myLooper() == Looper.getMainLooper()) sqlRanOnMain.set(true)
                        }) {
                            conversations.messageCounts("repository-main-read")
                            model.readModelTranscript("repository-main-read")
                            memory.pendingCompactionIds("repository-main-read")
                            artifacts.artifactsForConversation("repository-main-read")
                            strictRead.set(success(strict.readCrewResultEvidence(executionSession, evidence.evidenceId)))
                        }
                    }
                }
            } catch (caught: Throwable) {
                failure.set(caught)
            } finally {
                finished.countDown()
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("all repository reads complete when invoked from the main looper", finished.await(5, TimeUnit.SECONDS))
        assertNull("repository reads do not fail when invoked from the main looper", failure.get())
        assertEquals("the strict read returns the valid evidence created by this test", evidence.evidenceId, strictRead.get()?.evidenceId)
        assertFalse("SQL for Conversation, ModelTranscript, MemoryTimeline, Artifacts, and StrictJournal runs off-main", sqlRanOnMain.get())
    }

    @Test fun providerOutputsAreLastWinsWhenNoCompactionBoundaryExists() = onDatabaseThread {
        success(conversations.ensureConversation("provider-last-wins"))
        val user = success(conversations.appendMessage(AppendMessageInput(
            conversationId = "provider-last-wins", role = "user", parts = listOf(MessagePartInput(MessagePartKinds.TEXT, "question")),
        )))
        success(model.appendProviderOutputItems("provider-last-wins", user.messageId, 0, "[{\"item\":\"first\"}]"))
        success(model.appendProviderOutputItems("provider-last-wins", user.messageId, 0, "[{\"item\":\"last\"}]"))

        assertEquals("[{\"item\":\"last\"}]", model.readModelTranscript("provider-last-wins")
            .latestProviderOutputItemsByOwner[user.messageId]?.items_json)
    }

    @Test fun nullableM1bCursorColumnsRestoreWithoutNullDereferences() = onDatabaseThread {
        success(conversations.ensureConversation("nullable-m1b"))

        val failedImage = success(artifacts.appendGeneratedImage(GeneratedImageInput(
            conversationId = "nullable-m1b", prompt = "failed", status = "FAILED", error = null,
        )))
        assertNull(failedImage.image_path)
        assertNull(failedImage.error)
        assertNull(failedImage.file_ref_id)

        val activity = success(model.appendToolActivity(ToolActivityInput(
            conversationId = "nullable-m1b", eventId = "nullable-event", ownerUserMessageId = null,
            executionId = null, callId = null, toolName = "reader", stage = "tool_call", timestampMillis = 5L,
        )))!!
        assertNull(activity.owner_user_message_id)
        assertNull(activity.source_batch_id)
        assertNull(activity.execution_id)
        assertNull(activity.call_id)
        assertNull(activity.display_name)
        assertNull(activity.detail)
        assertNull(activity.preview_id)

        success(memory.appendCrewSnapshot("nullable-m1b", crewSnapshot(
            "nullable-m1b", "nullable-mission", 3, "Crew", "DONE", "DONE", "message",
        )))
        database.testChatSql.execSQL(
            "UPDATE crew_missions SET process_id=NULL,title_source=NULL,synthesis=NULL,finished_at_ms=NULL WHERE conversation_id=? AND mission_id=?",
            arrayOf("nullable-m1b", "nullable-mission"),
        )
        database.testChatSql.execSQL(
            "UPDATE crew_bots SET role_id=NULL,role_name=NULL,color_key=NULL,mission=NULL,error=NULL,result=NULL,waiting_reason=NULL," +
                "started_at_ms=NULL,finished_at_ms=NULL,recovery_note=NULL,phase=NULL,last_progress=NULL,last_progress_at_ms=NULL,cycle_id=NULL " +
                "WHERE conversation_id=? AND mission_id=?",
            arrayOf("nullable-m1b", "nullable-mission"),
        )
        val restoredCrew = memory.readCrewMissionSnapshots("nullable-m1b").single()
        assertNull(restoredCrew.processId)
        assertEquals("", restoredCrew.title)
        assertEquals("fallback", restoredCrew.titleSource)
        assertEquals("", restoredCrew.synthesis)
        assertEquals(0L, restoredCrew.finishedAtMillis)
        val bot = restoredCrew.bots.single()
        assertNull(bot.roleId)
        assertNull(bot.roleName)
        assertNull(bot.colorKey)
        assertNull(bot.mission)
        assertEquals("", bot.error)
        assertEquals("", bot.result)
        assertEquals("", bot.waitingReason)
        assertEquals("", bot.recoveryNote)
        assertEquals("", bot.phase)
        assertEquals("", bot.lastProgress)
        assertEquals("", bot.cycleId)
        assertEquals(0L, bot.startedAtMillis)
        assertEquals(0L, bot.finishedAtMillis)
        assertEquals("message", restoredCrew.messages.single().text)
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
        database.testChatSql.execSQL(
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
        database.testChatSql.execSQL("DROP TRIGGER injected_tool_call_failure")
    }

    @Test fun concurrentM1bAppendsAllocateContiguousConversationSequences() = onDatabaseThread {
        success(conversations.ensureConversation("concurrent-model"))
        val workers = Executors.newFixedThreadPool(4)
        try {
            val futures = workers.invokeAll((0 until 24).map { index ->
                Callable {
                    runBlocking { model.appendModelError("concurrent-model", null, index.toLong(), "error-$index") }
                }
            }, 45, TimeUnit.SECONDS)
            futures.forEach { future -> assertTrue(future.get(45, TimeUnit.SECONDS) is ChatDbResult.Success) }
        } finally {
            workers.shutdownNow()
        }
        val seqs = database.testChatSql.query(
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
        val expanded = database.testChatSql.query(
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
        assertEquals(2L, database.testChatSql.query(
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
        assertEquals("application/pdf", database.testChatSql.query(
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
        val firstSeq = database.testChatSql.query(
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
        assertEquals(firstSeq, database.testChatSql.query(
            "SELECT first_snapshot_seq FROM crew_missions WHERE conversation_id=? AND mission_id=?", arrayOf("crew", "mission-a"),
        ).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) })
        val order = memory.readCrewMissionSnapshots("crew")
        assertEquals(1L, firstSeq)
        assertEquals(listOf("mission-a", "mission-b"), order.map { it.missionId })
        assertEquals("latest title", order.first().title)
        val textBlob = database.testChatSql.query(
            "SELECT text FROM crew_messages WHERE conversation_id=? AND mission_id=?", arrayOf("crew", "mission-a"),
        ).use { c -> assertTrue(c.moveToFirst()); c.getBlob(0) }
        assertArrayEquals(ExactUtf16.encode("text\uD800exact"), textBlob)

        val recovered = memory.recoverCrewMissions("crew", "new-process")
        assertEquals("INTERRUPTED", recovered.last().status)
        assertTrue(recovered.last().bots.single().resumeRequired)
        assertEquals(com.jarvys.agent.crew.CrewProcessIdentity.ID, recovered.last().processId)
    }

    @Test fun crewCoordinationSnapshotRejectsNullReferenceMessageIdAndDigest() = onDatabaseThread {
        val conversationId = WorkspaceIdentity.executionSessionId(UUID.randomUUID().toString())
        val missionId = "nullable-mission"
        val messageId = "message-$missionId"
        success(conversations.ensureConversation(conversationId))
        val evidence = CrewCoordinationEvidence.create(
            conversationId,
            missionId,
            "bot",
            UUID.nameUUIDFromBytes("cycle-$missionId".toByteArray()).toString(),
            messageId,
            "QUESTION",
            "question",
            emptyList(),
            1_000L,
        )
        success(StrictJournalRepository(database).appendCrewCoordinationEvidence(evidence))
        val snapshot = crewSnapshot(conversationId, missionId, 4, "coordination", "RUNNING", "RUNNING", "question")
        snapshot.getJSONArray("messages").getJSONObject(0)
            .put("type", "QUESTION")
            .put("coordination", evidence.reference().toJson())
        success(memory.appendCrewSnapshot(conversationId, snapshot))

        val sql = database.testChatSql
        sql.execSQL(
            "UPDATE crew_messages SET reference_message_id=NULL WHERE conversation_id=? AND mission_id=?",
            arrayOf(conversationId, missionId),
        )
        val missingMessageId = runCatching { memory.readCrewMissionSnapshots(conversationId) }.exceptionOrNull()
        assertTrue(missingMessageId is IllegalStateException)
        assertEquals("crew_snapshot_coordination_reference_message_id_null", missingMessageId?.message)

        sql.execSQL(
            "UPDATE crew_messages SET reference_message_id=? WHERE conversation_id=? AND mission_id=?",
            arrayOf(messageId, conversationId, missionId),
        )
        sql.execSQL(
            "UPDATE crew_messages SET evidence_content_sha256=NULL WHERE conversation_id=? AND mission_id=?",
            arrayOf(conversationId, missionId),
        )
        val missingDigest = runCatching { memory.readCrewMissionSnapshots(conversationId) }.exceptionOrNull()
        assertTrue(missingDigest is IllegalStateException)
        assertEquals("crew_snapshot_coordination_reference_digest_null", missingDigest?.message)
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
            database.testChatSql.execSQL("CREATE TRIGGER injected_m1b_result_failure BEFORE INSERT ON tool_results BEGIN SELECT RAISE(ABORT,'injected failure'); END")
            assertTrue(model.appendToolResult(ModelToolResultInput("rollback", batchId, "call", "tool", "RESULT", "never persisted")) is ChatDbResult.Conflict)
            assertEquals(before, nextSeq("rollback"))
            assertEquals(0L, count("SELECT COUNT(*) FROM conversation_items WHERE conversation_id=? AND item_kind='model_tool_result'", arrayOf("rollback")))
            assertEquals(0L, count("SELECT COUNT(*) FROM tool_results WHERE conversation_id=?", arrayOf("rollback")))
            database.testChatSql.execSQL("DROP TRIGGER injected_m1b_result_failure")
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
            val seqs = database.testChatSql.query(
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
            .put("title", title).put("originalInstructions", "instructions").put("titleSource", "agent")
            .put("status", missionStatus).put("synthesis", "synthesis-$title").put("startedAtMillis", 1_000L)
            .put("finishedAtMillis", 0L).put("bots", JSONArray().put(bot)).put("messages", JSONArray().put(message))
            .put("timestamp", 100.25)
    }

    private fun nextSeq(conversationId: String): Long = database.testChatSql.query(
        "SELECT next_seq FROM conversations WHERE id=?", arrayOf(conversationId),
    ).use { assertTrue(it.moveToFirst()); it.getLong(0) }

    private fun count(query: String, args: Array<out Any?>): Long = database.testChatSql.query(query, args)
        .use { assertTrue(it.moveToFirst()); it.getLong(0) }

    private fun <T> success(result: ChatDbResult<T>): T = when (result) {
        is ChatDbResult.Success -> result.value
        else -> throw AssertionError("Expected ChatDbResult.Success; got $result")
    }
}
