package com.jarvys.agent.chatdb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ConversationReader
import com.jarvys.agent.ConversationTurn
import com.jarvys.agent.ui.chat.ConversationVisibility
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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.Collections

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

    @Test fun assistantRegenerationOwnerLookupPreservesUserMissingAndNonUserRules() = onDatabaseThread {
        val id = "reader-regeneration-owners"
        success(conversations.ensureConversation(id))
        val userOwner = append(id, ConversationRepository.ROLE_USER, "the user request")
        val agentOwner = append(id, "agent", "an internal worker result")
        val userOwnedAssistant = append(id, ConversationRepository.ROLE_ASSISTANT, "answer to user", userOwner.messageId)
        val agentOwnedAssistant = append(id, ConversationRepository.ROLE_ASSISTANT, "answer to worker", agentOwner.messageId)
        val missingOwnerAssistant = append(id, ConversationRepository.ROLE_ASSISTANT, "answer with missing owner", "missing-owner")
        val unownedAssistant = append(id, ConversationRepository.ROLE_ASSISTANT, "unowned answer")

        val events = reader.readRecentConversationTimeline(id, 20).events
        assertTrue(events.single { it.messageId == userOwnedAssistant.messageId }.canRegenerate)
        assertFalse(events.single { it.messageId == agentOwnedAssistant.messageId }.canRegenerate)
        assertFalse(events.single { it.messageId == missingOwnerAssistant.messageId }.canRegenerate)
        assertTrue(events.single { it.messageId == unownedAssistant.messageId }.canRegenerate)
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

    @Test fun timelinePageResolvesToolResultWhoseBatchIsOutsideTheWindowWithoutReadingOpaqueCallPayloads() = onDatabaseThread {
        val id = "reader-page-cross-window-tool"
        success(conversations.ensureConversation(id))
        val owner = append(id, ConversationRepository.ROLE_USER, "first prompt")
        val batchId = "cross-window-batch"
        val batch = success(model.appendToolBatch(ModelToolBatchInput(
            conversationId = id,
            batchId = batchId,
            ownerUserMessageId = owner.messageId,
            messageIndex = 0,
            assistantText = "Looking up the answer",
            calls = listOf(
                ModelCallInput("older-cross-window-call", "lookup", "{\"query\":\"older\"}"),
                ModelCallInput("cross-window-call", "lookup", "{\"query\":\"source\"}"),
            ),
            providerOutputItemsJson = "opaque-provider-payload-".repeat(200),
        )))
        val nextSeq = database.testChatSql.query(
            "SELECT next_seq FROM conversations WHERE id=?", arrayOf(id),
        ).use { c -> c.moveToFirst(); c.getLong(0) }
        val paddingCount = 520
        database.withChatWriteTransaction {
            database.conversationItemDao().insertAll((0 until paddingCount).map { offset ->
                val seq = nextSeq + offset
                ConversationItem(
                    id, seq, "workspace_result_notice", "padding-$offset", seq,
                    turn_anchor_seq = owner.seq, created_at = seq.toDouble(),
                )
            })
            database.chatSql.execSQL(
                "UPDATE conversations SET next_seq=? WHERE id=?",
                arrayOf(nextSeq + paddingCount, id),
            )
        }
        val olderOutput = "unrelated-result-".repeat(400)
        success(model.appendToolResult(ModelToolResultInput(
            id, batchId, "older-cross-window-call", "lookup", "RESULT", olderOutput,
        )))
        append(id, ConversationRepository.ROLE_USER, "second prompt")
        success(model.appendToolResult(ModelToolResultInput(
            id, batchId, "cross-window-call", "lookup", "RESULT", "the cross-window result",
        )))

        val statements = Collections.synchronizedList(mutableListOf<String>())
        val readStats = AtomicReference<Pair<Int, Long>>()
        val pageReader = ConversationReader(database) { rows, bytes -> readStats.set(rows to bytes) }
        val page = ChatSqlInstrumentation.observeStatements({ statements += it }) {
            pageReader.readRecentConversationTimeline(id, 1)
        }
        val result = page.events.single { it.kind == "tool" && it.stage == "tool_result" }
        assertEquals("the cross-window result", result.detail)
        assertEquals(owner.messageId, result.toolTurnMessageId)
        assertTrue("off-page results are not materialized with their batch", readStats.get().second < olderOutput.toByteArray(Charsets.UTF_8).size)
        assertTrue("the batch row is outside this page", batch.batch.seq < page.events.minOf { it.id })
        assertTrue("the result page retains an older cursor", page.hasOlder)
        assertTrue("page projection reads only referenced batch rows", statements.any { it.contains("tool_batches") && it.contains("batch_id IN") })
        assertTrue("page projection reads only referenced result rows", statements.any { it.contains("tool_results") && it.contains("requested(batch_id,call_id)") })
        assertFalse("timeline does not read opaque arguments", statements.any { it.contains("arguments_json") })
        assertFalse("timeline does not read provider item payloads", statements.any { it.contains("items_json") || it.contains("provider_output_items_json") })
        assertFalse("timeline does not read unrelated model artifacts", statements.any { it.contains("model_artifacts") })
    }

    @Test fun timelineReopensAssistantInterimBeforeItsToolResultAndFinalAnswer() = onDatabaseThread {
        val id = "reader-interim-order"
        success(conversations.ensureConversation(id))
        val user = append(id, ConversationRepository.ROLE_USER, "Build an app")
        val batchId = "interim-batch"
        success(model.appendToolBatch(ModelToolBatchInput(
            conversationId = id,
            batchId = batchId,
            ownerUserMessageId = user.messageId,
            messageIndex = 0,
            assistantText = "The build is underway; I am preparing its screens.",
            calls = listOf(ModelCallInput("interim-call", "write_file", "{}")),
        )))
        success(model.appendToolResult(ModelToolResultInput(
            id, batchId, "interim-call", "write_file", "RESULT", "The screen files are ready.",
        )))
        val final = append(id, ConversationRepository.ROLE_ASSISTANT, "The app is ready.", user.messageId)

        val reopened = reader.readRecentConversationTimeline(id, 10).events
        val progress = reopened.single { it.kind == "assistant" && it.stage == "PROGRESS" }
        val toolResult = reopened.single { it.kind == "tool" && it.stage == "tool_result" }
        assertEquals("The build is underway; I am preparing its screens.", progress.text)
        assertEquals(user.messageId, progress.toolTurnMessageId)
        assertEquals("The screen files are ready.", toolResult.detail)
        assertTrue(reopened.indexOf(progress) < reopened.indexOf(toolResult))
        assertTrue(reopened.indexOf(toolResult) < reopened.indexOfFirst { it.kind == "assistant" && it.messageId == final.messageId })

        val workspaceProjection = ConversationVisibility.project(reopened)
        assertFalse(workspaceProjection.any { it.messageId == progress.messageId })
        val mainChatProjection = ConversationVisibility.project(reopened, showAssistantProgress = true)
        assertEquals(listOf("PROGRESS", "tool_result", null), mainChatProjection
            .filter { it.kind == "assistant" || it.kind == "tool" }.map { it.stage })

        val reloaded = ConversationReader(database).readRecentConversationTimeline(id, 10).events
        assertEquals(reopened.map { Triple(it.kind, it.stage, it.messageId) },
            reloaded.map { Triple(it.kind, it.stage, it.messageId) })
        assertEquals(1, reloaded.count { it.kind == "assistant" && it.stage == "PROGRESS" })
    }

    @Test fun timelineRestoresDeliveredAndGeneratedArtifactsThroughOneGenericEvent() = onDatabaseThread {
        val id = "reader-artifacts"
        success(conversations.ensureConversation(id))
        val repository = ConversationArtifactsRepository(database)
        val deliveredInput = DeliveredArtifactInput(
            conversationId = id,
            artifactId = "file-artifact-1",
            name = "report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 24L,
            kind = "FILE",
            relativePath = "delivered/$id/report.pdf",
            sha256 = "file-digest",
            artifactType = "application/pdf",
            moduleId = "com.jarvys.module.file",
            manifestJson = """{"schemaVersion":1,"id":"com.jarvys.module.file"}""",
            title = "Quarterly report",
        )
        val delivered = success(repository.appendDeliveredArtifact(deliveredInput))
        assertEquals(delivered.seq, success(repository.appendDeliveredArtifact(deliveredInput)).seq)
        assertTrue(repository.appendDeliveredArtifact(deliveredInput.copy(name = "changed.pdf")) is ChatDbResult.Conflict)
        val generatedInput = GeneratedImageInput(
            conversationId = id,
            prompt = "blue lighthouse",
            status = "COMPLETED",
            imagePath = "generated/$id/blue.png",
            sha256 = "image-digest",
            bytes = 18L,
            artifactType = "image/png",
            moduleId = "com.jarvys.module.image",
            title = "Blue lighthouse",
            manifestJson = """{"schemaVersion":1,"id":"com.jarvys.module.image"}""",
        )
        val generated = success(repository.appendGeneratedImage(generatedInput))
        assertEquals(generated.seq, success(repository.appendGeneratedImage(generatedInput)).seq)
        assertTrue(repository.appendGeneratedImage(generatedInput.copy(sha256 = "changed-digest")) is ChatDbResult.Conflict)
        assertTrue(repository.appendGeneratedImage(generatedInput.copy(manifestJson = "{}")) is ChatDbResult.Conflict)
        val failedImageRecord = success(repository.appendGeneratedImage(GeneratedImageInput(
            conversationId = id,
            prompt = "unfinished",
            status = "FAILED",
            error = "generation failed",
        )))

        val events = reader.readRecentConversationTimeline(id, 10).events
        assertEquals(listOf("artifact", "artifact", "artifact"), events.map { it.kind })
        val file = events.single { it.presentationArtifact?.moduleId == "com.jarvys.module.file" }
        assertEquals("application/pdf", file.presentationArtifact?.artifactType)
        assertEquals("Quarterly report", file.presentationArtifact?.title)
        val image = events.single { it.presentationArtifact?.moduleId == "com.jarvys.module.image" }
        assertEquals("image/png", image.presentationArtifact?.artifactType)
        assertEquals(generatedInput.manifestJson, image.presentationArtifact?.manifestJson)
        assertEquals("blue lighthouse", image.generatedImagePrompt)
        val failedEvent = events.single { it.generatedImageStatus == "FAILED" }
        assertEquals("artifact", failedEvent.kind)
        assertEquals("generation failed", failedEvent.generatedImageError)
        assertEquals(2L, database.testChatSql.query("SELECT COUNT(*) FROM artifacts WHERE conversation_id=?", arrayOf(id))
            .use { cursor -> cursor.moveToFirst(); cursor.getLong(0) })

        // The pre-module ledger used these event keys. Restores continue to use the generic UI event.
        database.testChatSql.execSQL(
            "UPDATE conversation_items SET item_kind='delivered_file' WHERE conversation_id=? AND seq=?",
            arrayOf(id, file.id),
        )
        database.testChatSql.execSQL(
            "UPDATE conversation_items SET item_kind='generated_image' WHERE conversation_id=? AND seq IN (?,?)",
            arrayOf(id, image.id, failedImageRecord.seq),
        )
        val restoredLegacyEvents = reader.readRecentConversationTimeline(id, 10).events
        assertEquals(listOf("artifact", "artifact", "artifact"), restoredLegacyEvents.map { it.kind })
        assertEquals("file-artifact-1", restoredLegacyEvents.single { it.presentationArtifact?.moduleId == "com.jarvys.module.file" }
            .presentationArtifact?.id)
        assertEquals("generated:generated/$id/blue.png", restoredLegacyEvents.single { it.generatedImageStatus == "COMPLETED" }
            .presentationArtifact?.id)
        assertTrue(restoredLegacyEvents.any { it.generatedImageStatus == "FAILED" })
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
