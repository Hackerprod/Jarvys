package com.jarvys.agent

import com.jarvys.agent.chatdb.ChatFileOwnership
import com.jarvys.agent.chatdb.ConversationArtifactsRepository
import com.jarvys.agent.chatdb.ConversationDecisionValue
import com.jarvys.agent.chatdb.ConversationMessageAt
import com.jarvys.agent.chatdb.ConversationRepository
import com.jarvys.agent.chatdb.ConversationSummary
import com.jarvys.agent.chatdb.ConversationArtifact
import com.jarvys.agent.chatdb.FileRef
import com.jarvys.agent.chatdb.GlobalTimelineMarkers
import com.jarvys.agent.chatdb.InterruptRequestValue
import com.jarvys.agent.chatdb.JarvysChatDatabase
import com.jarvys.agent.chatdb.JarvysChatDatabaseProvider
import com.jarvys.agent.chatdb.MemoryTimelineRepository
import com.jarvys.agent.chatdb.Compaction
import com.jarvys.agent.chatdb.GeneratedImage
import com.jarvys.agent.chatdb.MessageCounts
import com.jarvys.agent.chatdb.MessageWindow
import com.jarvys.agent.chatdb.ModelTranscriptEntry
import com.jarvys.agent.chatdb.ModelTranscriptRepository
import com.jarvys.agent.chatdb.ModelTranscriptSnapshot
import com.jarvys.agent.chatdb.ReflectionPayloadSnapshot
import com.jarvys.agent.chatdb.StrictJournalRepository
import com.jarvys.agent.chatdb.ToolActivity as StoredToolActivity
import com.jarvys.agent.chatdb.StoredMessage
import com.jarvys.agent.chatdb.chatSql
import com.jarvys.agent.chatdb.withChatReadTransaction
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.ToolActivity as UiToolActivity
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
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
    data class TimelineEventPage(
        val events: List<AgentRunUiEvent>,
        val olderCursorSeq: Long?,
        val invalidatedToolTurnMessageIds: Set<String>,
    ) {
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

    /** Projects persisted UI timeline rows in the indexed, steering-aware sequence window. */
    suspend fun readRecentConversationTimeline(conversationId: String, turns: Int): TimelineEventPage = readProjection {
        projectTimelinePage(conversationId, conversations.readTailWindow(conversationId, turns))
    }

    /** Reads the page strictly older than [cursorSeq], using the same `(sort_seq, sort_sub, seq)` keyset as the tail query. */
    suspend fun readConversationTimelineBefore(conversationId: String, cursorSeq: Long, turns: Int): TimelineEventPage = readProjection {
        projectTimelinePage(conversationId, conversations.readWindowBefore(conversationId, cursorSeq, turns))
    }

    /** Reads every page under one fence and shares the conversation-wide projection tables. */
    suspend fun readConversationTimeline(conversationId: String, turns: Int): List<AgentRunUiEvent> = readProjection {
        require(turns > 0) { "timeline page size must be positive" }
        val sideTables = readTimelineSideTables(conversationId)
        val pages = ArrayList<List<AgentRunUiEvent>>()
        var window = conversations.readTailWindow(conversationId, turns)
        var page = projectTimelinePage(conversationId, window, sideTables)
        pages.add(page.events)
        var cursor = page.olderCursorSeq
        while (cursor != null) {
            window = conversations.readWindowBefore(conversationId, cursor, turns)
            val older = projectTimelinePage(conversationId, window, sideTables)
            pages.add(older.events)
            val nextCursor = older.olderCursorSeq
            if (nextCursor == null || nextCursor >= cursor) break
            cursor = nextCursor
        }
        pages.asReversed().flatMapTo(ArrayList<AgentRunUiEvent>()) { it }
    }

    private suspend fun projectTimelinePage(conversationId: String, window: MessageWindow): TimelineEventPage =
        projectTimelinePage(conversationId, window, readTimelineSideTables(conversationId))

    private suspend fun projectTimelinePage(
        conversationId: String,
        window: MessageWindow,
        sideTables: TimelineSideTables,
    ): TimelineEventPage {
        val messages = window.slots.mapNotNull { it.message }
        val translationMessageIds = window.slots.asSequence()
            .filter { it.item.item_kind == "assistant_translation" }
            .map { it.item.item_key }
            .toList()
        val translationTargets = if (translationMessageIds.isEmpty()) emptyMap() else
            conversations.getMessagesByIds(conversationId, translationMessageIds).associateBy { it.messageId }
        val messagesById = (messages + translationTargets.values).associateBy { it.messageId }
        val reactions = sideTables.reactions
        val ordinaryChat = sideTables.ordinaryChat
        val transcriptSnapshot = sideTables.transcriptSnapshot
        val modelEntries = transcriptSnapshot.entries
        val batchesBySeq = modelEntries.filterIsInstance<ModelTranscriptEntry.Batch>().associateBy { it.seq }
        val batchesById = batchesBySeq.values.associateBy { it.value.batch.batch_id }
        val modelResults = modelEntries.filterIsInstance<ModelTranscriptEntry.ResultActivity>()
        val modelResultKeys = modelResults.mapTo(HashSet()) { timelineToolKey(it.batchId, it.callId, null) }
        val invalidations = sideTables.invalidations
        val rawActivities = sideTables.activities
            .filter { activity ->
                val owner = activity.owner_user_message_id
                owner != null && activity.execution_id != null && !isInvalidated(owner, activity.seq, invalidations)
            }
        val activityGroups = rawActivities.groupBy { it.owner_user_message_id!! to it.execution_id!! }
        val typedCalls = HashSet<String>()
        val typedReflectionCalls = HashSet<String>()
        val activityEventsByFirstSeq = HashMap<Long, AgentRunUiEvent>()
        for ((ownerAndExecution, rows) in activityGroups) {
            val ordered = rows.sortedBy { it.seq }
            val first = ordered.first()
            val activityValues = ordered.map(::toUiToolActivity)
            val source = first.source_batch_id?.takeIf(String::isNotBlank) ?: ownerAndExecution.first
            ordered.forEach { activity ->
                activity.call_id?.takeIf(String::isNotBlank)?.let { callId ->
                    typedCalls += timelineToolKey(activity.source_batch_id, callId, ownerAndExecution.first)
                    typedReflectionCalls += "${ownerAndExecution.first}/$callId"
                }
            }
            val projected = UiToolActivity.project(activityValues, false)
            var durableResult: Pair<String, String>? = null
            if (!UiToolActivity.definitive(projected.stage)) {
                val matchingResult = modelResults.firstOrNull { timelineToolKey(it.batchId, it.callId, null) == "$source/${first.call_id.orEmpty()}" }
                    ?.firstResult
                if (matchingResult != null) {
                    // A read_skill transcript can retain output text even when its accepted
                    // typed tool_result activity was never durably appended. Keep the
                    // activity interrupted while exposing that durable detail.
                    val stage = if (projected.skill() && projected.stage == "tool_interrupted") {
                        projected.stage
                    } else when (matchingResult.lifecycle) {
                        "INTERRUPTED_UNCERTAIN" -> "tool_interrupted"
                        "NEVER_LAUNCHED" -> "tool_not_started"
                        else -> if (matchingResult.output.startsWith("Tool error:")) "tool_error" else "tool_result"
                    }
                    durableResult = stage to matchingResult.output
                }
            }
            val activityEvent = AgentRunUiEvent.activityEvent(first.seq, projected)
            activityEventsByFirstSeq[first.seq] = (durableResult?.let { (stage, detail) ->
                activityEvent.copy(stage = stage, detail = detail.ifEmpty { null })
            } ?: activityEvent).copyToolTimelineIdentity("execution:${projected.executionId}", ownerAndExecution.first)
        }
        val presentations = sideTables.presentations
        val latestPresentations = presentations.groupBy { timelineToolKey(it.batch_id, it.call_id, it.owner_user_message_id) }
            .mapValues { (_, values) -> values.maxBy { it.seq } }
        val presentationKeys = presentations.mapTo(HashSet()) {
            timelineToolKey(it.batch_id, it.call_id, it.owner_user_message_id)
        }
        val reflectionTools = sideTables.reflectionTools
        val reflectionToolsByKey = reflectionTools.groupBy { timelineToolKey(it.batch_id, it.call_id, it.owner_user_message_id) }
            .mapValues { (_, values) -> values.maxBy { it.seq } }
        val compactionsBySeq = sideTables.compactions.associateBy { it.seq }
        val reflectionRows = sideTables.reflections
        val undoneReflections = reflectionRows.asSequence().filter { it.kind == "reflection_undo" }
            .mapNotNull { it.reflectionId }.toSet()
        val reflectionsBySeq = reflectionRows.associateBy { it.seq }
        val decisionsBySeq = sideTables.decisions.associateBy { it.seq }
        val pendingInterrupts = sideTables.pendingInterrupts.associateBy { it.seq }
        val dispatchedInterrupts = pendingInterrupts.values.filter { it.dispatchedSeq != null }.mapTo(HashSet()) { it.requestId }
        val artifactsBySeq = sideTables.artifacts.associateBy { it.seq }
        val generatedImagesBySeq = sideTables.generatedImages.associateBy { it.seq }
        val crewSnapshotsAtSeq = sideTables.crewSnapshotsAtSeq
        val crewSnapshots = sideTables.crewSnapshots.associateBy { it.missionId }
        val events = ArrayList<AgentRunUiEvent>()
        for (slot in window.slots) {
            val seq = slot.item.seq
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
                        AgentRunUiEvent.messageEvent(seq, role, message.text, timestamp)
                            .copyMetadata(message.messageId, 0L)
                            .copyAttachments(if (ordinaryChat) message.attachments.map(::toChatAttachment) else emptyList())
                            .copyReaction(reactions[message.stableId].orEmpty())
                    } else {
                        AgentRunUiEvent.proactiveMessageEvent(
                            seq, role, message.text, timestamp, message.messageId, threadKey,
                            emptyList(), false,
                        )
                    }
                } else {
                    val stage = AgentRunUiEvent.assistantStageForOutcome(message.status)
                    val duration = message.durationMillis ?: 0L
                    events += if (proactive || threadKey.isNotEmpty()) {
                        AgentRunUiEvent.proactiveMessageEvent(
                            seq, role, message.text, timestamp, message.messageId, threadKey.ifEmpty { null },
                            message.suggestedReplies.map { ProactiveSuggestedReply(it.label, it.text) },
                            message.suggestedRepliesConsumedSeq != null, duration, stage, proactive,
                        )
                    } else {
                        AgentRunUiEvent.messageEvent(seq, role, message.text, timestamp)
                            .copyMetadata(message.messageId, duration)
                            .copyStage(stage)
                    }
                }
                continue
            }
            val timestamp = (slot.item.created_at * 1_000.0).toLong()
            when (slot.item.item_kind) {
                "assistant_translation" -> {
                    val target = messagesById[slot.item.item_key] ?: continue
                    if (target.hiddenKind in HIDDEN_ASSISTANT_KINDS) continue
                    val translation = target.translation?.takeIf { it.seq == seq } ?: continue
                    if (target.translationHiddenSeq?.let { it >= translation.seq } == true) continue
                    events += AgentRunUiEvent.translationEvent(seq, translation.language, translation.content, target.messageId, timestamp)
                }
                "main_interrupt_request" -> {
                    val request = pendingInterrupts[seq] ?: continue
                    if (request.requestId in dispatchedInterrupts) continue
                    events += AgentRunUiEvent.messageEvent(seq, "user", request.content, timestamp)
                        .copyMetadata("pending-${request.requestId}", 0L).copyStage("QUEUED")
                }
                "user_decision_request" -> {
                    val decision = decisionsBySeq[seq] ?: continue
                    events += AgentRunUiEvent.userDecisionEvent(
                        seq, decision.decisionId, decision.title, decision.body, decision.options,
                        decision.allowDismiss, decision.status, decision.optionId, decision.optionLabel,
                        (decision.createdAtSeconds * 1_000.0).toLong(),
                    )
                }
                "compaction" -> {
                    val compaction = compactionsBySeq[seq] ?: continue
                    if (invalidations.values.any { it > seq }) continue
                    events += AgentRunUiEvent.compactionEvent(
                        seq, compaction.summary, compaction.summarized_messages.toInt(), compaction.mode, timestamp,
                    )
                }
                "reflection_event", "reflection_commit" -> {
                    val reflection = reflectionsBySeq[seq] ?: continue
                    val reflectionId = reflection.reflectionId ?: continue
                    if (reflection.revisionIds.isEmpty()) continue
                    events += AgentRunUiEvent.reflectionMemoryEvent(
                        seq, reflection.summary.orEmpty(), reflectionId, reflection.revisionIds,
                        if (reflectionId in undoneReflections) "undone" else reflection.status ?: "completed", timestamp,
                    )
                }
                "crew_snapshot" -> {
                    val missionId = slot.item.item_key
                    if (crewSnapshotsAtSeq[missionId] != seq) continue
                    val snapshot = crewSnapshots[missionId] ?: continue
                    events += AgentRunUiEvent.crewMissionEvent(seq, snapshot)
                }
                // Older on-device ledgers used these two item kinds. Keep projecting them
                // through the generic event so upgrading never hides existing presentations.
                "artifact", "delivered_file", "generated_image" -> {
                    val artifact = artifactsBySeq[seq]
                    val image = generatedImagesBySeq[seq]
                    if (artifact != null) {
                        if (image == null) {
                            events += AgentRunUiEvent.artifactEvent(
                                seq, artifact.toPresentationArtifact(), timestamp, artifact.toChatAttachment(),
                            )
                        } else {
                            events += AgentRunUiEvent.generatedImageEvent(
                                seq, image.image_path, image.prompt, image.revised_prompt.orEmpty(), image.mime_type ?: "image/png",
                                image.size.orEmpty(), image.status, image.error.orEmpty(), timestamp,
                            ).copy(presentationArtifact = artifact.toPresentationArtifact())
                        }
                    } else if (image != null) {
                        events += AgentRunUiEvent.generatedImageEvent(
                            seq, image.image_path, image.prompt, image.revised_prompt.orEmpty(), image.mime_type ?: "image/png",
                            image.size.orEmpty(), image.status, image.error.orEmpty(), timestamp,
                        ).copy(presentationArtifact = image.toPresentationArtifact(slot.item.item_key))
                    }
                }
                "workspace_result_notice" -> {
                    if (!WorkspaceIdentity.isCentral(conversationId)) continue
                    val exact = (strictJournals.readWorkspaceResultReference(conversationId, slot.item.item_key, true)
                        as? com.jarvys.agent.chatdb.ChatDbResult.Success)?.value ?: continue
                    events += AgentRunUiEvent.workspaceResultEvent(seq, exact, exact.producedAtMillis)
                }
                "tool_activity" -> activityEventsByFirstSeq[seq]?.let(events::add)
                "model_tool_calls" -> {
                    val batch = batchesBySeq[seq]?.value ?: continue
                    val owner = batch.batch.owner_user_message_id.orEmpty()
                    if (owner.isEmpty() || isInvalidated(owner, seq, invalidations)) continue
                    val pending = batch.calls.filter { state ->
                        val key = timelineToolKey(batch.batch.batch_id, state.call.call_id, owner)
                        state.result == null && key !in typedCalls && key !in presentationKeys &&
                            key !in reflectionToolsByKey && key !in modelResultKeys
                    }
                    val progress = batch.batch.assistant_text.trim().takeIf { batch.batch.assistant_progress && it.isNotEmpty() }
                    if (pending.isNotEmpty()) {
                        val started = pending.any { it.call.lifecycle == "STARTED" }
                        val details = pending.joinToString("\n\n") { state ->
                            val call = state.call
                            val status = if (call.lifecycle == "STARTED")
                                "Execution started, but no final result was durably recorded. The run may have been interrupted; effects may have occurred. Inspect current evidence before retrying."
                            else "This tool intent was recorded but was never launched. It was not automatically replayed."
                            "${CoreToolRegistry.humanizeToolName(call.name)} (${call.call_id}): $status"
                        }
                        val detail = listOfNotNull(progress?.let(WebSearchCitationMarkup::resolve), details).joinToString("\n\n")
                        val displayName = pending.map { CoreToolRegistry.humanizeToolName(it.call.name) }.distinct().joinToString(", ")
                        val callIds = pending.joinToString(",") { it.call.call_id }
                        events += AgentRunUiEvent.toolEvent(
                            seq, if (started) "tool_interrupted" else "tool_not_started", displayName, detail,
                            callIds, null, timestamp,
                        ).copyToolTimelineIdentity("${batch.batch.batch_id}/$callIds", owner)
                    } else if (progress != null) {
                        val firstCall = batch.calls.firstOrNull()?.call?.call_id.orEmpty()
                        val scope = batch.batch.batch_id.ifEmpty { owner }
                        events += AgentRunUiEvent.messageEvent(seq, "assistant", WebSearchCitationMarkup.resolve(progress), timestamp)
                            .copyMetadata("progress-$scope/$firstCall", 0L).copyStage("PROGRESS")
                            .copyToolTimelineIdentity("progress:$scope/$firstCall", owner)
                    }
                }
                "model_tool_result" -> {
                    val resultEntry = modelResults.firstOrNull { it.seq == seq } ?: continue
                    val result = resultEntry.firstResult ?: continue
                    val key = timelineToolKey(resultEntry.batchId, resultEntry.callId, null)
                    val batch = batchesById[resultEntry.batchId]?.value
                    val owner = batch?.batch?.owner_user_message_id.orEmpty()
                    if (owner.isEmpty() || isInvalidated(owner, seq, invalidations) || key in presentationKeys || key in typedCalls) continue
                    val tool = reflectionToolsByKey[key]?.tool_name ?: CoreToolRegistry.humanizeToolName(result.tool_name)
                    val stage = when (result.lifecycle) {
                        "INTERRUPTED_UNCERTAIN" -> "tool_interrupted"
                        "NEVER_LAUNCHED" -> "tool_not_started"
                        else -> if (result.output.startsWith("Tool error:")) "tool_error" else "tool_result"
                    }
                    events += AgentRunUiEvent.toolEvent(seq, stage, tool, result.output.ifEmpty { null }, resultEntry.callId, null, timestamp)
                        .copyToolTimelineIdentity(key, owner)
                }
                "tool_presentation" -> {
                    val presentation = latestPresentations.values.firstOrNull { it.seq == seq } ?: continue
                    val key = timelineToolKey(presentation.batch_id, presentation.call_id, presentation.owner_user_message_id)
                    val owner = presentation.owner_user_message_id.orEmpty()
                    if (owner.isEmpty() || isInvalidated(owner, seq, invalidations) || key in typedCalls) continue
                    if (!presentation.stage.isTimelineToolResultStage()) continue
                    val previewId = if (presentation.preview_expected) {
                        presentation.preview_id?.let { verifiedTimelinePreview(conversationId, it) }
                    } else null
                    val previewUnavailable = (presentation.preview_expected || !presentation.preview_id.isNullOrBlank()) && previewId == null
                    val detail = presentation.detail?.takeIf(String::isNotEmpty)
                    val displayedDetail = if (previewUnavailable) {
                        listOfNotNull(detail, "The recorded preview is no longer available.").joinToString("\n\n")
                    } else detail
                    events += AgentRunUiEvent.toolEvent(
                        seq, presentation.stage, presentation.tool_name,
                        displayedDetail, presentation.call_id, previewId, timestamp,
                    ).copyToolPresentation(presentation.audit_detail, false)
                        .copyToolTimelineIdentity(key, owner)
                }
                "reflection_tool" -> {
                    val marker = reflectionTools.firstOrNull { it.seq == seq } ?: continue
                    val key = timelineToolKey(marker.batch_id, marker.call_id, marker.owner_user_message_id)
                    val owner = marker.owner_user_message_id.orEmpty()
                    if (owner.isEmpty() || isInvalidated(owner, seq, invalidations) || key in typedCalls ||
                        key in presentationKeys || key in modelResultKeys || "${owner}/${marker.call_id.orEmpty()}" in typedReflectionCalls) continue
                    if (!marker.stage.isTimelineToolResultStage()) continue
                    events += AgentRunUiEvent.toolEvent(
                        seq, marker.stage, marker.tool_name, null, marker.call_id, null, timestamp,
                    ).copyToolTimelineIdentity(key, owner)
                }
            }
        }
        return TimelineEventPage(events, window.olderCursorSeq, sideTables.invalidations.keys)
    }

    private data class TimelineSideTables(
        val reactions: Map<String, String>,
        val ordinaryChat: Boolean,
        val transcriptSnapshot: ModelTranscriptSnapshot,
        val invalidations: Map<String, Long>,
        val activities: List<StoredToolActivity>,
        val presentations: List<TimelineToolPresentation>,
        val reflectionTools: List<TimelineReflectionTool>,
        val compactions: List<Compaction>,
        val reflections: List<TimelineReflection>,
        val decisions: List<ConversationDecisionValue>,
        val pendingInterrupts: List<InterruptRequestValue>,
        val artifacts: List<ConversationArtifact>,
        val generatedImages: List<GeneratedImage>,
        val crewSnapshotsAtSeq: Map<String, Long>,
        val crewSnapshots: List<CrewMissionSnapshot>,
    )

    private suspend fun readTimelineSideTables(conversationId: String) = TimelineSideTables(
        reactions = conversations.readMessageReactions(conversationId),
        ordinaryChat = (conversations.readConversationKind(conversationId) ?: "chat") !in setOf("proactive", "tasks"),
        transcriptSnapshot = transcript.readModelTranscript(conversationId),
        invalidations = readTimelineInvalidations(conversationId),
        activities = readTimelineToolActivities(conversationId),
        presentations = readTimelineToolPresentations(conversationId),
        reflectionTools = readTimelineReflectionTools(conversationId),
        compactions = readTimelineCompactions(conversationId),
        reflections = readTimelineReflections(conversationId),
        decisions = conversations.readDecisions(conversationId),
        pendingInterrupts = conversations.pendingInterruptRequests(conversationId),
        artifacts = artifacts.artifactsForConversation(conversationId),
        generatedImages = readTimelineGeneratedImages(conversationId),
        crewSnapshotsAtSeq = readTimelineLatestCrewSnapshots(conversationId),
        crewSnapshots = memory.readCrewMissionSnapshots(conversationId),
    )

    private fun readTimelineInvalidations(conversationId: String): Map<String, Long> = database.chatSql.query(
        "SELECT owner_user_message_id,MAX(hidden_seq) FROM messages WHERE conversation_id=? " +
            "AND hidden_kind IN ('assistant_regenerated','assistant_deleted') AND owner_user_message_id IS NOT NULL " +
            "GROUP BY owner_user_message_id",
        arrayOf(conversationId),
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) } }

    private fun isInvalidated(owner: String, seq: Long, invalidations: Map<String, Long>): Boolean =
        invalidations[owner]?.let { seq < it } == true

    private fun readTimelineToolActivities(conversationId: String): List<StoredToolActivity> = database.chatSql.query(
        "SELECT * FROM tool_activities WHERE conversation_id=? ORDER BY seq",
        arrayOf(conversationId),
    ).use { c -> buildList {
        while (c.moveToNext()) add(StoredToolActivity(
            c.getString(c.getColumnIndexOrThrow("conversation_id")),
            c.getLong(c.getColumnIndexOrThrow("seq")),
            c.getString(c.getColumnIndexOrThrow("event_id")),
            c.getStringOrNull(c.getColumnIndexOrThrow("owner_user_message_id")),
            c.getStringOrNull(c.getColumnIndexOrThrow("source_batch_id")),
            c.getStringOrNull(c.getColumnIndexOrThrow("execution_id")),
            c.getStringOrNull(c.getColumnIndexOrThrow("call_id")),
            c.getString(c.getColumnIndexOrThrow("tool_name")),
            c.getStringOrNull(c.getColumnIndexOrThrow("display_name")),
            c.getStringOrNull(c.getColumnIndexOrThrow("skill_id")),
            c.getStringOrNull(c.getColumnIndexOrThrow("skill_name")),
            c.getString(c.getColumnIndexOrThrow("stage")),
            c.getStringOrNull(c.getColumnIndexOrThrow("detail")),
            c.getStringOrNull(c.getColumnIndexOrThrow("preview_id")),
            c.getStringOrNull(c.getColumnIndexOrThrow("audit_detail")),
            c.getStringOrNull(c.getColumnIndexOrThrow("reflection_source")),
            c.getLong(c.getColumnIndexOrThrow("timestamp_millis")),
            c.getDouble(c.getColumnIndexOrThrow("created_at")),
        ))
    } }

    private fun toUiToolActivity(value: StoredToolActivity): UiToolActivity = UiToolActivity(
        value.event_id, value.execution_id.orEmpty(), value.call_id.orEmpty(), value.tool_name,
        value.display_name.orEmpty(), value.skill_id.orEmpty(), value.skill_name.orEmpty(), value.stage,
        value.detail.orEmpty(), value.preview_id.orEmpty(), value.audit_detail.orEmpty(),
        value.reflection_source.orEmpty(), value.timestamp_millis,
    )

    private fun readTimelineToolPresentations(conversationId: String): List<TimelineToolPresentation> = database.chatSql.query(
        "SELECT * FROM tool_presentations WHERE conversation_id=? ORDER BY seq",
        arrayOf(conversationId),
    ).use { c -> buildList {
        while (c.moveToNext()) add(TimelineToolPresentation(
            c.getLong(c.getColumnIndexOrThrow("seq")),
            c.getStringOrNull(c.getColumnIndexOrThrow("owner_user_message_id")),
            c.getStringOrNull(c.getColumnIndexOrThrow("batch_id")),
            c.getStringOrNull(c.getColumnIndexOrThrow("call_id")),
            c.getString(c.getColumnIndexOrThrow("tool_name")),
            c.getString(c.getColumnIndexOrThrow("stage")),
            c.getStringOrNull(c.getColumnIndexOrThrow("detail")),
            c.getStringOrNull(c.getColumnIndexOrThrow("audit_detail")),
            c.getInt(c.getColumnIndexOrThrow("preview_expected")) != 0,
            c.getStringOrNull(c.getColumnIndexOrThrow("preview_id")),
        ))
    } }

    private fun readTimelineReflectionTools(conversationId: String): List<TimelineReflectionTool> = database.chatSql.query(
        "SELECT seq,owner_user_message_id,batch_id,call_id,tool_name,stage FROM reflection_tool_markers " +
            "WHERE conversation_id=? ORDER BY seq",
        arrayOf(conversationId),
    ).use { c -> buildList {
        while (c.moveToNext()) add(TimelineReflectionTool(
            c.getLong(0), c.getStringOrNull(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getString(4), c.getString(5),
        ))
    } }

    private fun readTimelineCompactions(conversationId: String): List<Compaction> = database.chatSql.query(
        "SELECT * FROM compactions WHERE conversation_id=? ORDER BY seq",
        arrayOf(conversationId),
    ).use { c -> buildList {
        while (c.moveToNext()) add(Compaction(
            c.getString(c.getColumnIndexOrThrow("conversation_id")),
            c.getString(c.getColumnIndexOrThrow("compaction_id")),
            c.getLong(c.getColumnIndexOrThrow("seq")),
            c.getString(c.getColumnIndexOrThrow("summary")),
            c.getLong(c.getColumnIndexOrThrow("first_kept")),
            c.getLong(c.getColumnIndexOrThrow("summarized_messages")),
            c.getString(c.getColumnIndexOrThrow("trigger")),
            c.getString(c.getColumnIndexOrThrow("mode")),
            c.getDouble(c.getColumnIndexOrThrow("created_at")),
        ))
    } }

    private fun readTimelineReflections(conversationId: String): List<TimelineReflection> {
        val rows = database.chatSql.query(
            "SELECT seq,kind,reflection_id,summary,status,created_at FROM reflections WHERE conversation_id=? ORDER BY seq",
            arrayOf(conversationId),
        ).use { c -> buildList {
            while (c.moveToNext()) add(TimelineReflection(
                c.getLong(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3),
                c.getStringOrNull(4), c.getDouble(5), emptyList(),
            ))
        } }
        if (rows.isEmpty()) return rows
        val revisions = database.chatSql.query(
            "SELECT reflection_seq,revision_id FROM reflection_revisions WHERE conversation_id=? ORDER BY reflection_seq,ordinal",
            arrayOf(conversationId),
        ).use { c -> buildMap<Long, MutableList<Long>> {
            while (c.moveToNext()) getOrPut(c.getLong(0)) { mutableListOf() }.add(c.getLong(1))
        } }
        return rows.map { it.copy(revisionIds = revisions[it.seq].orEmpty()) }
    }

    private fun readTimelineGeneratedImages(conversationId: String): List<GeneratedImage> = database.chatSql.query(
        "SELECT * FROM generated_images WHERE conversation_id=? ORDER BY seq",
        arrayOf(conversationId),
    ).use { c -> buildList {
        while (c.moveToNext()) add(GeneratedImage(
            c.getString(c.getColumnIndexOrThrow("conversation_id")),
            c.getLong(c.getColumnIndexOrThrow("seq")),
            c.getStringOrNull(c.getColumnIndexOrThrow("image_path")),
            c.getString(c.getColumnIndexOrThrow("prompt")),
            c.getStringOrNull(c.getColumnIndexOrThrow("revised_prompt")),
            c.getStringOrNull(c.getColumnIndexOrThrow("size")),
            c.getStringOrNull(c.getColumnIndexOrThrow("mime_type")),
            c.getString(c.getColumnIndexOrThrow("status")),
            c.getStringOrNull(c.getColumnIndexOrThrow("error")),
            c.getStringOrNull(c.getColumnIndexOrThrow("file_ref_id")),
            c.getDouble(c.getColumnIndexOrThrow("created_at")),
        ))
    } }

    private fun readTimelineLatestCrewSnapshots(conversationId: String): Map<String, Long> = database.chatSql.query(
        "SELECT mission_id,last_snapshot_seq FROM crew_missions WHERE conversation_id=? ORDER BY first_snapshot_seq",
        arrayOf(conversationId),
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) } }

    private fun ConversationArtifact.toPresentationArtifact() = PresentationArtifact(
        id = artifact_id,
        artifactType = artifact_type,
        moduleId = module_id,
        name = name,
        title = title,
        mimeType = mime_type,
        sizeBytes = size_bytes,
        relativePath = relative_path,
        sha256 = sha256,
        manifestJson = manifest_json,
        thumbnailRef = thumbnail_ref,
        storageKind = kind,
        isGeneratedImage = kind.equals("IMAGE", ignoreCase = true) && artifact_id.startsWith("generated:"),
    )

    private fun GeneratedImage.toPresentationArtifact(itemKey: String) = PresentationArtifact(
        id = itemKey,
        artifactType = mime_type ?: "application/octet-stream",
        moduleId = null,
        name = image_path?.substringAfterLast('/') ?: "artifact",
        title = prompt.takeIf(String::isNotBlank),
        mimeType = mime_type,
        sizeBytes = 0L,
        relativePath = image_path,
        sha256 = null,
        manifestJson = null,
        thumbnailRef = null,
        storageKind = "IMAGE",
        isGeneratedImage = true,
    )

    private fun ConversationArtifact.toChatAttachment(): ChatAttachment? = runCatching {
        ChatAttachment(artifact_id, name, mime_type, size_bytes,
            ChatAttachment.Kind.valueOf(kind.uppercase(Locale.ROOT)), relative_path)
    }.getOrNull()

    private fun timelineToolKey(batchId: String?, callId: String?, ownerUserMessageId: String?): String =
        "${batchId?.takeIf(String::isNotBlank) ?: ownerUserMessageId.orEmpty()}/${callId.orEmpty()}"

    private data class TimelineToolPresentation(
        val seq: Long,
        val owner_user_message_id: String?,
        val batch_id: String?,
        val call_id: String?,
        val tool_name: String,
        val stage: String,
        val detail: String?,
        val audit_detail: String?,
        val preview_expected: Boolean,
        val preview_id: String?,
    )

    private data class TimelineReflectionTool(
        val seq: Long,
        val owner_user_message_id: String?,
        val batch_id: String?,
        val call_id: String?,
        val tool_name: String,
        val stage: String,
    )

    private data class TimelineReflection(
        val seq: Long,
        val kind: String,
        val reflectionId: String?,
        val summary: String?,
        val status: String?,
        val createdAtSeconds: Double,
        val revisionIds: List<Long>,
    )

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

    private suspend fun verifiedTimelinePreview(conversationId: String, recordedId: String): String? {
        return try {
            if (HtmlPreviewDescriptor.isSnapshotToken(recordedId)) {
                val artifactId = recordedId.removePrefix(HtmlPreviewDescriptor.TOKEN_PREFIX)
                val owned = artifacts.findChatFile(conversationId, "delivered", artifactId) as? ChatFileOwnership.Delivered
                    ?: return null
                val artifact = owned.value
                val kind = if (artifact.kind.equals("IMAGE", ignoreCase = true)) ChatAttachment.Kind.IMAGE else ChatAttachment.Kind.FILE
                val attachment = runCatching {
                    ChatAttachment(artifact.artifact_id, artifact.name, artifact.mime_type, artifact.size_bytes, kind, artifact.relative_path)
                }.getOrNull() ?: return null
                val jarvysRoot = JarvysChatDatabaseProvider.databaseFileForRawQuery(database).parentFile ?: return null
                DeliveredArtifactStore(jarvysRoot).previewMetadataForAttachment(conversationId, attachment)
                    .token.takeIf { it == recordedId }
            } else {
                val expected = WorkspaceStore.projectIdForSession(conversationId)
                if (recordedId != expected) return null
                val databaseFile = JarvysChatDatabaseProvider.databaseFileForRawQuery(database)
                val filesDirectory = databaseFile.parentFile?.parentFile ?: return null
                val workspaces = File(filesDirectory.canonicalFile, "jarvys/workspaces")
                val project = File(workspaces, expected)
                if (!project.isDirectory || project.canonicalFile != project.absoluteFile) return null
                val index = WorkspaceStore(workspaces, expected).resolvePreviewPath("index.html")
                expected.takeIf { index.isFile && index.canRead() }
            }
        } catch (_: Exception) {
            null
        }
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
        is ModelTranscriptEntry.Artifact -> JSONObject()
            .put("type", "model_artifact")
            .put("artifactId", value.artifact_id)
            .put("artifact", JSONObject()
                .put("path", value.path)
                .put("sha256", value.sha256)
                .put("chars", value.chars)
                .put("bytes", value.bytes))
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

private fun String.isTimelineToolResultStage(): Boolean = this in setOf(
    "tool_result", "tool_error", "tool_interrupted", "tool_not_started",
)
