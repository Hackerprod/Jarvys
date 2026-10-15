package com.jarvys.agent

import com.jarvys.agent.chatdb.ConversationArtifactsRepository
import com.jarvys.agent.chatdb.ConversationDecisionValue
import com.jarvys.agent.chatdb.ConversationMessageAt
import com.jarvys.agent.chatdb.ConversationRepository
import com.jarvys.agent.chatdb.ConversationSummary
import com.jarvys.agent.chatdb.FileRef
import com.jarvys.agent.chatdb.GlobalTimelineMarkers
import com.jarvys.agent.chatdb.InterruptRequestValue
import com.jarvys.agent.chatdb.JarvysChatDatabase
import com.jarvys.agent.chatdb.MemoryTimelineRepository
import com.jarvys.agent.chatdb.MessageCounts
import com.jarvys.agent.chatdb.MessageWindow
import com.jarvys.agent.chatdb.ModelTranscriptEntry
import com.jarvys.agent.chatdb.ModelTranscriptRepository
import com.jarvys.agent.chatdb.ModelTranscriptSnapshot
import com.jarvys.agent.chatdb.ReflectionPayloadSnapshot
import com.jarvys.agent.chatdb.StrictJournalRepository
import com.jarvys.agent.chatdb.StoredMessage
import com.jarvys.agent.chatdb.chatSql
import com.jarvys.agent.chatdb.withChatReadTransaction
import com.jarvys.agent.chatdb.JarvysChatDatabaseProvider
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/**
 * Read surface for the conversation database. Every composed projection runs under one read fence.
 * Repository calls made inside the fence reuse its read handle, while a writer lease continues to
 * provide read-your-writes inline.
 */
internal class ConversationReader(private val database: JarvysChatDatabase) {
    private val conversations = ConversationRepository(database)
    private val transcript = ModelTranscriptRepository(database)
    private val memory = MemoryTimelineRepository(database)
    private val artifacts = ConversationArtifactsRepository(database)

    /** Strict J ledgers retain their fail-closed, typed read operations. */
    internal val strictJournals = StrictJournalRepository(database)

    data class RegenerationPrompt(val text: String, val userMessageId: String)

    /** A timeline page uses the first older item sequence as its exclusive keyset cursor. */
    data class TimelineEventPage(val events: List<AgentRunUiEvent>, val olderCursorSeq: Long?) {
        val hasOlder: Boolean get() = olderCursorSeq != null
    }

    suspend fun loadConversationContext(conversationId: String): List<ConversationTurn> = readProjection {
        val compaction = memory.activeCompaction(conversationId)
        val modelSnapshot = transcript.readModelTranscript(conversationId)
        val modelRows = readModelRows(modelSnapshot, conversationId)
        val compactionRow = compaction?.let { active ->
            val position = database.chatSql.query(
                "SELECT sort_seq,sort_sub FROM conversation_items WHERE conversation_id=? AND seq=?",
                arrayOf(conversationId, active.seq),
            ).use { c -> if (c.moveToFirst()) SortPosition(c.getLong(0), c.getInt(1), active.seq) else null }
            position?.let { boundary -> modelRows.indexOfFirst { it.position >= boundary }.let { if (it < 0) modelRows.size else it } }
                ?: modelRows.size
        } ?: -1
        val firstKept = compaction?.first_kept ?: 0L
        val messageRows = conversations.readMessagesAtOrAfterIndex(conversationId, firstKept)
        val modelJsonRows = modelRows.map { it.row }
        val groups = MainChatTranscriptStore.restore(modelJsonRows, firstKept.toInt(), compactionRow)
        val providerItems = MainChatTranscriptStore.restoreProviderOutputItems(
            modelJsonRows, firstKept.toInt(), compactionRow,
        )
        val groupsByPosition = groups.mapNotNull { (rowIndex, turns) ->
            val row = modelRows.getOrNull(rowIndex) ?: return@mapNotNull null
            val visible = turns.filter { it.kind != ConversationTurn.Kind.MODEL_ERROR }
            if (visible.isEmpty()) null else ContextPart(
                row.position.sortSeq, row.position.sortSub, row.position.seq, null, visible,
            )
        }
        val messageParts = messageRows.map { positioned ->
            ContextPart(
                positioned.sortSeq,
                positioned.sortSub,
                positioned.message.seq,
                positioned.message,
                emptyList(),
            )
        }
        val context = ArrayList<ConversationTurn>()
        if (compaction != null && compaction.summary.isNotEmpty()) {
            context += ConversationTurn.compactionSummary(compaction.summary, compaction.summarized_messages.toInt())
        }
        (messageParts + groupsByPosition).sortedWith(compareBy<ContextPart>({ it.sortSeq }, { it.sortSub }, { it.seq }))
            .forEach { part ->
                val message = part.message ?: run {
                    context.addAll(part.turns)
                    return@forEach
                }
                if (message.role == "assistant" && message.hiddenKind in HIDDEN_ASSISTANT_KINDS) return@forEach
                if (message.role == "assistant" && isAssistantRunStatus(message.status)) return@forEach
                val index = message.messageIndex.toInt()
                val assistantItems = if (message.role == "assistant") providerItems[message.ownerUserMessageId] else null
                if (!assistantItems.isNullOrEmpty()) {
                    context += ConversationTurn.assistantReply(message.text, index, assistantItems)
                } else {
                    context += ConversationTurn.messageWithAttachments(
                        message.role,
                        message.text,
                        index,
                        message.attachments.map(::toChatAttachment),
                    )
                }
            }
        context
    }

    suspend fun readModelTranscript(conversationId: String): ModelTranscriptSnapshot =
        readProjection { transcript.readModelTranscript(conversationId) }

    suspend fun readConversationMessages(conversationId: String): List<StoredMessage> =
        readProjection { conversations.readConversationMessages(conversationId) }

    /** Active-message view hides regenerated/deleted assistant rows but keeps terminal status rows. */
    suspend fun readActiveConversationMessages(conversationId: String): List<StoredMessage> =
        readProjection {
            conversations.readMessagesAtOrAfterIndex(conversationId)
                .map(ConversationMessageAt::message)
                .filterNot { it.role == "assistant" && it.hiddenKind in HIDDEN_ASSISTANT_KINDS }
        }

    suspend fun latestUserMessageId(conversationId: String): String? =
        readProjection { conversations.latestUserMessageId(conversationId) }

    suspend fun firstUserMessage(conversationId: String): StoredMessage? =
        readProjection { conversations.firstUserMessage(conversationId) }

    suspend fun latestUserMessageIndex(conversationId: String): Int {
        val messages = readConversationMessages(conversationId)
        return messages.indexOfLast { it.role == "user" }
    }

    suspend fun readConversationMessage(conversationId: String, messageId: String): String? =
        readProjection { conversations.getMessage(conversationId, messageId)?.takeIf { it.role == "user" }?.text }

    suspend fun proactiveThreadKeyForUserMessage(conversationId: String, messageId: String): String? =
        readProjection { conversations.getMessage(conversationId, messageId)?.takeIf { it.role == "user" }?.proactiveThreadKey }

    suspend fun assistantOwnerUserMessageId(conversationId: String, messageId: String): String =
        readProjection { conversations.getMessage(conversationId, messageId)?.takeIf { it.role == "assistant" }?.ownerUserMessageId.orEmpty() }

    suspend fun findUserMessageBeforeAssistant(conversationId: String, messageId: String): RegenerationPrompt? {
        val messages = readConversationMessages(conversationId)
        val assistantIndex = messages.indexOfFirst { it.role == "assistant" && it.messageId == messageId }
        if (assistantIndex <= 0) return null
        val user = messages.subList(0, assistantIndex).lastOrNull { it.role == "user" } ?: return null
        return RegenerationPrompt(user.text, user.messageId)
    }

    suspend fun isLatestActiveAssistant(conversationId: String, messageId: String?): Boolean {
        if (messageId.isNullOrEmpty()) return false
        val latest = readActiveConversationMessages(conversationId).lastOrNull {
            it.role == "assistant" && it.hiddenKind !in HIDDEN_ASSISTANT_KINDS
        } ?: return false
        return latest.messageId == messageId
    }

    /** Returns later user messages in conversation order after the specified message. */
    suspend fun readUserMessagesAfter(conversationId: String, messageId: String): List<StoredMessage> = readProjection {
        if (messageId.isEmpty()) return@readProjection emptyList()
        val messages = conversations.readMessagesAtOrAfterIndex(conversationId)
            .map(ConversationMessageAt::message).sortedBy(StoredMessage::seq)
        val cursor = messages.indexOfFirst { it.messageId == messageId }
        if (cursor < 0) emptyList() else messages.drop(cursor + 1).filter { it.role == "user" }
    }

    suspend fun readMessage(conversationId: String, messageId: String): StoredMessage? =
        readProjection { conversations.getMessage(conversationId, messageId) }

    suspend fun readMessageAttachments(conversationId: String, messageId: String): List<ChatAttachment> =
        readProjection { conversations.getMessage(conversationId, messageId)?.attachments.orEmpty().map(::toChatAttachment) }

    suspend fun readTailWindow(conversationId: String, turns: Int): MessageWindow =
        readProjection { conversations.readTailWindow(conversationId, turns) }

    suspend fun readWindowBefore(conversationId: String, cursorSeq: Long, turns: Int): MessageWindow =
        readProjection { conversations.readWindowBefore(conversationId, cursorSeq, turns) }

    /**
     * Domain-event projection for persisted user/assistant messages and the last visible
     * translation. Item selection and ordering reuse the indexed, steering-aware sequence window.
     * Tool/progress, compaction, reflection, decision, crew, artifact and workspace notice rows are
     * retained by their typed repositories but are not yet assembled into `AgentRunUiEvent` here.
     */
    suspend fun readRecentConversationTimeline(conversationId: String, turns: Int): TimelineEventPage = readProjection {
        projectTimelinePage(conversationId, conversations.readTailWindow(conversationId, turns))
    }

    /** Reads the page strictly older than [cursorSeq], using the same `(sort_seq, sort_sub, seq)` keyset as the tail query. */
    suspend fun readConversationTimelineBefore(conversationId: String, cursorSeq: Long, turns: Int): TimelineEventPage = readProjection {
        projectTimelinePage(conversationId, conversations.readWindowBefore(conversationId, cursorSeq, turns))
    }

    private suspend fun projectTimelinePage(conversationId: String, window: MessageWindow): TimelineEventPage {
        val messages = window.slots.mapNotNull { it.message }
        val translationMessageIds = window.slots.asSequence()
            .filter { it.item.item_kind == "assistant_translation" }
            .map { it.item.item_key }
            .toList()
        val translationTargets = if (translationMessageIds.isEmpty()) emptyMap() else
            conversations.getMessagesByIds(conversationId, translationMessageIds).associateBy { it.messageId }
        val messagesById = (messages + translationTargets.values).associateBy { it.messageId }
        val reactions = conversations.readMessageReactions(conversationId)
        val ordinaryChat = (conversations.readConversationKind(conversationId) ?: "chat") !in setOf("proactive", "tasks")
        val events = ArrayList<AgentRunUiEvent>()
        for (slot in window.slots) {
            val message = slot.message
            if (message != null) {
                val role = message.role
                if (role == "assistant" && message.hiddenKind in HIDDEN_ASSISTANT_KINDS) continue
                if (role != "user" && role != "assistant") continue
                val timestamp = (message.createdAtSeconds * 1_000.0).toLong()
                val threadKey = message.proactiveThreadKey.orEmpty()
                val proactive = message.proactiveDecisionId != null
                if (role == "user") {
                    events += if (threadKey.isEmpty()) {
                        AgentRunUiEvent.messageEvent(slot.item.seq, role, message.text, timestamp)
                            .copyMetadata(message.messageId, 0L)
                            .copyAttachments(if (ordinaryChat) message.attachments.map(::toChatAttachment) else emptyList())
                            .copyReaction(reactions[message.stableId].orEmpty())
                    } else {
                        AgentRunUiEvent.proactiveMessageEvent(
                            slot.item.seq, role, message.text, timestamp, message.messageId, threadKey,
                            emptyList(), false,
                        )
                    }
                } else {
                    val stage = AgentRunUiEvent.assistantStageForOutcome(message.status)
                    val duration = message.durationMillis ?: 0L
                    events += if (proactive || threadKey.isNotEmpty()) {
                        AgentRunUiEvent.proactiveMessageEvent(
                            slot.item.seq, role, message.text, timestamp, message.messageId, threadKey.ifEmpty { null },
                            message.suggestedReplies.map { ProactiveSuggestedReply(it.label, it.text) },
                            message.suggestedRepliesConsumedSeq != null, duration, stage, proactive,
                        )
                    } else {
                        AgentRunUiEvent.messageEvent(slot.item.seq, role, message.text, timestamp)
                            .copyMetadata(message.messageId, duration)
                            .copyStage(stage)
                    }
                }
                continue
            }
            if (slot.item.item_kind == "assistant_translation") {
                val target = messagesById[slot.item.item_key] ?: continue
                if (target.hiddenKind in HIDDEN_ASSISTANT_KINDS) continue
                val translation = target.translation?.takeIf { it.seq == slot.item.seq } ?: continue
                events += AgentRunUiEvent.translationEvent(
                    slot.item.seq, translation.language, translation.content, target.messageId,
                    (slot.item.created_at * 1_000.0).toLong(),
                )
            }
        }
        return TimelineEventPage(events, window.olderCursorSeq)
    }

    suspend fun messageCounts(conversationId: String): MessageCounts =
        readProjection { conversations.messageCounts(conversationId) }

    suspend fun listConversations(limit: Int, excludedKinds: Set<String> = setOf("proactive", "tasks")): List<ConversationSummary> =
        readProjection { conversations.listConversations(limit, excludedKinds) }

    suspend fun pendingInterruptRequests(conversationId: String? = null): List<InterruptRequestValue> =
        readProjection { conversations.pendingInterruptRequests(conversationId) }

    suspend fun readInterruptRequest(conversationId: String, requestId: String): InterruptRequestValue? =
        readProjection { conversations.readInterruptRequest(conversationId, requestId) }

    suspend fun hasReflectionCommit(conversationId: String, reflectionId: String): Boolean =
        readProjection { memory.hasReflectionCommit(conversationId, reflectionId) }

    /** Builds a bounded context string using message and UTF-16 code-unit limits. */
    suspend fun readConversationContext(conversationId: String): String {
        val messages = readActiveConversationMessages(conversationId)
        val context = StringBuilder()
        val first = maxOf(0, messages.size - 16)
        for (index in first until messages.size) {
            var content = messages[index].text
            // Kotlin String length and substring count UTF-16 code units.
            if (content.length > 3_000) content = content.substring(0, 3_000) + "\u00E2\u20AC\u00A6"
            if (context.length + content.length > 16_000) break
            context.append(messages[index].role.uppercase(Locale.ROOT)).append(": ")
                .append(content).append('\n')
        }
        return context.toString()
    }

    /** Projects attachment and completed-image rows in conversation timeline order. */
    suspend fun readConversationImageReferences(conversationId: String): List<ConversationImageReference> = readProjection {
        artifacts.readImageReferences(conversationId).mapNotNull { value ->
            value.attachment?.let { ConversationImageReference.attachment(value.messageId, it) }
                ?: ConversationImageReference.generated(value.messageId, value.generatedPath, value.prompt)
        }
    }

    suspend fun readCrewMissionSnapshots(conversationId: String) =
        readProjection { memory.readCrewMissionSnapshots(conversationId) }

    suspend fun recoverCrewMissions(conversationId: String, processIdForComparison: String? = null) =
        readProjection {
            if (processIdForComparison == null) memory.recoverCrewMissions(conversationId)
            else memory.recoverCrewMissions(conversationId, processIdForComparison)
        }

    suspend fun buildReflectionPayload(
        conversationId: String,
        throughMessageId: String? = null,
        replayAll: Boolean = false,
    ): ReflectionPayloadSnapshot = readProjection {
        memory.buildReflectionPayload(conversationId, throughMessageId, replayAll)
    }

    suspend fun pendingCompactionIds(conversationId: String): List<String> =
        readProjection { memory.pendingCompactionIds(conversationId) }

    suspend fun globalTimelineMarkers(): GlobalTimelineMarkers =
        readProjection { memory.readGlobalTimelineMarkers() }

    suspend fun messageReactions(conversationId: String): Map<String, String> =
        readProjection { conversations.readMessageReactions(conversationId) }

    suspend fun readDecisions(conversationId: String): List<ConversationDecisionValue> =
        readProjection { conversations.readDecisions(conversationId) }

    suspend fun findChatFile(conversationId: String, kind: String, id: String) =
        readProjection { artifacts.findChatFile(conversationId, kind, id) }

    suspend fun unreferencedFileRefs(): List<FileRef> = readProjection { artifacts.unreferencedFileRefs() }

    suspend fun artifactsForConversation(conversationId: String) =
        readProjection { artifacts.artifactsForConversation(conversationId) }

    suspend fun ownsGeneratedFile(conversationId: String, relativePath: String): Boolean =
        readProjection { artifacts.ownsGeneratedFile(conversationId, relativePath) }

    suspend fun conversationHasAttachments(conversationId: String): Boolean =
        readProjection { artifacts.conversationHasAttachments(conversationId) }

    suspend fun conversationHasPrivateImagesOrAttachments(conversationId: String): Boolean =
        readProjection { artifacts.conversationHasPrivateImagesOrAttachments(conversationId) }

    private suspend fun <T> readProjection(block: suspend () -> T): T =
        JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction { block() } }

    private suspend fun readModelRows(
        snapshot: ModelTranscriptSnapshot,
        conversationId: String,
    ): List<ModelProjectionRow> {
        val rows = snapshot.entries.mapNotNull { entry ->
            val json = entry.toModelRestoreRow() ?: return@mapNotNull null
            ModelProjectionRow(SortPosition(entry.sortSeq, entry.sortSub, entry.seq), json)
        }.toMutableList()
        database.chatSql.query(
            "SELECT r.seq,i.sort_seq,i.sort_sub,r.owner_user_message_id,r.batch_id,r.call_id,r.tool_name,r.source,r.stage,r.marker " +
                "FROM reflection_tool_markers r JOIN conversation_items i ON i.conversation_id=r.conversation_id AND i.seq=r.seq " +
                "WHERE r.conversation_id=? ORDER BY i.sort_seq,i.sort_sub,i.seq",
            arrayOf(conversationId),
        ).use { c -> while (c.moveToNext()) {
            val row = JSONObject()
                .put("type", "reflection_tool")
                .put("userMessageId", c.getStringOrNull(3).orEmpty())
                .put("batchId", c.getStringOrNull(4).orEmpty())
                .put("callId", c.getStringOrNull(5).orEmpty())
                .put("toolName", c.getString(6))
                .put("source", c.getString(7))
                .put("stage", c.getString(8))
                .put("marker", c.getString(9))
            rows += ModelProjectionRow(SortPosition(c.getLong(1), c.getInt(2), c.getLong(0)), row)
        } }
        return rows.sortedBy { it.position }
    }

    private fun ModelTranscriptEntry.toModelRestoreRow(): JSONObject? = when (this) {
        is ModelTranscriptEntry.Batch -> {
            val batch = value.batch
            val calls = if (batch.raw_calls != null) {
                runCatching { JSONTokener(batch.raw_calls).nextValue() }.getOrNull() ?: JSONObject.NULL
            } else JSONArray().also { encoded -> value.calls.forEach { callState ->
                val call = callState.call
                encoded.put(JSONObject().put("id", call.call_id).put("name", call.name)
                    .put("arguments", runCatching { JSONTokener(call.arguments_json).nextValue() }.getOrNull() ?: JSONObject.NULL))
            } }
            val row = JSONObject()
                .put("type", "model_tool_calls")
                .put("batchId", batch.batch_id)
                .put("userMessageId", batch.owner_user_message_id.orEmpty())
                .put("messageIndex", batch.message_index)
                .put("assistantText", batch.assistant_text)
                .put("assistantProgress", batch.assistant_progress)
                .put("calls", calls)
            batch.provider_output_items_json?.let { encoded -> runCatching { JSONArray(encoded) }.getOrNull() }?.let {
                row.put("providerOutputItems", it)
            }
            row
        }
        is ModelTranscriptEntry.Lifecycle -> JSONObject()
            .put("type", if (state == "STARTED") "model_tool_started" else "model_tool_not_started")
            .put("batchId", batchId).put("callId", callId)
        is ModelTranscriptEntry.ResultActivity -> firstResult?.let { result -> JSONObject()
            .put("type", "model_tool_result")
            .put("batchId", batchId).put("callId", callId).put("toolName", result.tool_name)
            .put("lifecycle", result.lifecycle).put("output", result.output)
        }
        is ModelTranscriptEntry.Error -> JSONObject()
            .put("type", "model_error")
            .put("userMessageId", value.owner_user_message_id.orEmpty())
            .put("messageIndex", value.message_index).put("errorCode", value.error_code)
        is ModelTranscriptEntry.ProviderOutput -> JSONObject()
            .put("type", "model_provider_output_items")
            .put("userMessageId", value.owner_user_message_id)
            .put("messageIndex", value.message_index)
            .put("items", runCatching { JSONArray(value.items_json) }.getOrElse { JSONArray() })
        is ModelTranscriptEntry.Artifact -> JSONObject().put("type", "model_artifact")
    }

    private fun toChatAttachment(value: com.jarvys.agent.chatdb.MessageAttachmentInput): ChatAttachment =
        ChatAttachment(
            value.attachmentId,
            value.name,
            value.mimeType,
            value.sizeBytes,
            ChatAttachment.Kind.valueOf(value.kind),
            value.relativePath,
        )

    private fun isAssistantRunStatus(status: String?): Boolean = when (status.orEmpty().trim().uppercase()) {
        "STOPPED", "PARTIAL", "FAILED", "ERROR", "MODEL_ERROR", "TIMED_OUT", "TIMEOUT",
        "INTERRUPTED", "ABORTED", "CANCELLED" -> true
        else -> false
    }

    private data class SortPosition(val sortSeq: Long, val sortSub: Int, val seq: Long) : Comparable<SortPosition> {
        override fun compareTo(other: SortPosition): Int = compareValuesBy(this, other,
            SortPosition::sortSeq, SortPosition::sortSub, SortPosition::seq)
    }

    private data class ModelProjectionRow(val position: SortPosition, val row: JSONObject)
    private data class ContextPart(
        val sortSeq: Long,
        val sortSub: Int,
        val seq: Long,
        val message: StoredMessage?,
        val turns: List<ConversationTurn>,
    )

    private companion object {
        val HIDDEN_ASSISTANT_KINDS = setOf("assistant_regenerated", "assistant_deleted")
    }
}

private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
