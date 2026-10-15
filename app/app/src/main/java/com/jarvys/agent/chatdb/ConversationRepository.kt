package com.jarvys.agent.chatdb

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import android.database.Cursor
import com.jarvys.agent.ConversationMetadataStore
import com.jarvys.agent.ConversationTitle
import com.jarvys.agent.MessageReactionEmoji
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Typed outcome for repository operations whose normal result can be absent or rejected. */
sealed interface ChatDbResult<out T> {
    data class Success<T>(val value: T) : ChatDbResult<T>
    data object NotFound : ChatDbResult<Nothing>
    data object Tombstoned : ChatDbResult<Nothing>
    data class Conflict(val reason: String) : ChatDbResult<Nothing>
    data class Invalid(val reason: String) : ChatDbResult<Nothing>
}

data class MessagePartInput(val kind: String, val payload: String)

data class MessageAttachmentInput(
    val attachmentId: String,
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val kind: String,
    val relativePath: String,
    val sha256: String? = null,
)

data class SuggestedReplyInput(val label: String, val text: String)

data class AppendMessageInput(
    val conversationId: String,
    val role: String,
    val parts: List<MessagePartInput>,
    val status: String? = null,
    val runId: String? = null,
    val ownerUserMessageId: String? = null,
    val durationMillis: Long? = null,
    val beforeMessageId: String? = null,
    val proactiveThreadKey: String? = null,
    val proactiveReplyToMessageId: String? = null,
    val proactiveDecisionId: String? = null,
    val interruptRequestId: String? = null,
    val acknowledgementRequired: Boolean = false,
    val attachments: List<MessageAttachmentInput> = emptyList(),
    val suggestedReplies: List<SuggestedReplyInput> = emptyList(),
    val createdAtSeconds: Double? = null,
)

data class StoredMessage(
    val conversationId: String,
    val messageId: String,
    val stableId: String,
    val seq: Long,
    val messageIndex: Long,
    val role: String,
    val status: String?,
    val runId: String?,
    val ownerUserMessageId: String?,
    val durationMillis: Long?,
    val beforeMessageId: String?,
    val proactiveThreadKey: String?,
    val proactiveReplyToMessageId: String?,
    val proactiveDecisionId: String?,
    val interruptRequestId: String?,
    val acknowledgementRequired: Boolean,
    val createdAtSeconds: Double,
    val updatedAtSeconds: Double,
    val hiddenKind: String?,
    val hiddenSeq: Long?,
    val translationHiddenSeq: Long?,
    val suggestedRepliesConsumedSeq: Long?,
    val inputState: String?,
    val inputStateSeq: Long?,
    val parts: List<MessagePartInput> = emptyList(),
    val attachments: List<MessageAttachmentInput> = emptyList(),
    val translation: TranslationValue? = null,
    val suggestedReplies: List<SuggestedReplyInput> = emptyList(),
) {
    val text: String get() = parts.asSequence().filter { it.kind == MessagePartKinds.TEXT }.joinToString("") { it.payload }
}

data class TranslationValue(val language: String, val content: String, val seq: Long)
data class ReactionUpdate(val seq: Long?, val changed: Boolean)

data class MessageSlot(val item: ConversationItem, val message: StoredMessage?)

data class MessageWindow(val conversationId: String, val slots: List<MessageSlot>, val olderCursorSeq: Long?)

data class MessageCounts(val total: Long, val users: Long)

data class ConversationSummary(
    val id: String,
    val kind: String,
    val title: String?,
    val preview: String?,
    val goal: String?,
    val turnCount: Long,
    val messageCount: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastMessageAtMillis: Long?,
    val pinned: Boolean,
    val archived: Boolean,
    val deleted: Boolean,
)

data class InterruptRequestValue(
    val conversationId: String,
    val requestId: String,
    val seq: Long,
    val content: String,
    val memoryDisabled: Boolean,
    val skills: List<String>,
    val dispatchedMessageId: String?,
    val dispatchedSeq: Long?,
    val inputState: String?,
    val inputStateSeq: Long?,
)

data class DispatchedInterrupt(val request: InterruptRequestValue, val message: StoredMessage, val inserted: Boolean)

data class GenerationRunValue(
    val id: String,
    val conversationId: String,
    val messageId: String,
    val state: String,
    val stateRevision: Long,
    val checkpointSeq: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val terminalAtMillis: Long?,
    val errorCode: String?,
    val activeTargetKey: String?,
)

/**
 * Public transactional access point for the normalized conversation database. Room's configured
 * transaction executor serializes mutations; [withTransaction] keeps sequence assignment, spine
 * insertion, and all typed child writes atomic.
 */
class ConversationRepository internal constructor(private val database: JarvysChatDatabase) {
    /** Opens the process database through the app's guarded provider; call from a background context. */
    constructor(context: Context) : this(JarvysChatDatabaseProvider.getInstance(context))

    private val sql get() = database.openHelper.writableDatabase

    /**
     * Idempotent counterpart of the implicit conversation creation in
     * `role=user`/`role=assistant` rows (`LocalRunStore.java:appendConversationMessage`); ID validation
     * matches the existing `ConversationMetadataStore.validateSessionId` contract. A tombstone
     * always wins and an existing conversation retains its original kind and creation time.
     */
    suspend fun ensureConversation(id: String, kind: String = "chat"): ChatDbResult<Conversation> =
        transactionResult {
            if (!isValidConversationId(id) || kind.isBlank()) return@transactionResult ChatDbResult.Invalid("conversation id and kind are required")
            if (isTombstonedRaw(id)) return@transactionResult ChatDbResult.Tombstoned
            val existing = findConversation(id)
            if (existing != null) return@transactionResult ChatDbResult.Success(existing)
            val now = System.currentTimeMillis()
            sql.execSQL(
                "INSERT INTO conversations(id,kind,created_at,updated_at,next_seq) " +
                    "SELECT ?,?,?,?,1 WHERE NOT EXISTS(SELECT 1 FROM conversations WHERE id=?)",
                arrayOf(id, kind, now, now, id),
            )
            findConversation(id)?.let { ChatDbResult.Success(it) } ?: ChatDbResult.Conflict("conversation insert lost a concurrent race")
        }

    /**
     * Replaces `.meta.json` title/pinned/archived/privateCode updates (`LocalRunStore.java:renameConversation`,
     * `setConversationPinned`, `setConversationArchived`, `markConversationHasPrivateCode`) and the
     * first-wins `role=session_title` row (`LocalRunStore.java:appendConversationTitleIfAbsent`).
     */
    suspend fun updateMetadata(
        conversationId: String,
        title: String? = null,
        generatedTitle: String? = null,
        pinned: Boolean? = null,
        archived: Boolean? = null,
        privateCode: Boolean? = null,
    ): ChatDbResult<Conversation> = transactionResult {
        val current = findConversation(conversationId) ?: return@transactionResult missingConversation(conversationId)
        val cleanTitle = if (title == null) null else normalizeManualTitle(title)
            ?: return@transactionResult ChatDbResult.Invalid("a non-empty conversation title is required")
        val cleanGenerated = generatedTitle?.trim()?.takeIf(String::isNotEmpty)
        sql.execSQL(
            "UPDATE conversations SET title=COALESCE(?,title), " +
                "generated_title=CASE WHEN generated_title IS NULL OR generated_title='' THEN COALESCE(?,generated_title) ELSE generated_title END, " +
                "pinned=COALESCE(?,pinned),archived=COALESCE(?,archived)," +
                "private_code=CASE WHEN ?=1 THEN 1 ELSE private_code END,updated_at=? WHERE id=?",
            arrayOf(cleanTitle, cleanGenerated, pinned?.asSqlInt(), archived?.asSqlInt(), privateCode?.asSqlInt(), System.currentTimeMillis(), conversationId),
        )
        ChatDbResult.Success(findConversation(conversationId) ?: current)
    }

    /** Updates conversation metadata (`.meta.json` / title), replacing `LocalRunStore.java:renameConversation`. */
    suspend fun renameConversation(conversationId: String, title: String?): ChatDbResult<Conversation> = transactionResult {
        if (findConversation(conversationId) == null) return@transactionResult missingConversation(conversationId)
        val cleanTitle = title?.let(::normalizeManualTitle)
            ?: return@transactionResult ChatDbResult.Invalid("a non-empty conversation title is required")
        sql.execSQL("UPDATE conversations SET title=?,updated_at=? WHERE id=?", arrayOf(cleanTitle, System.currentTimeMillis(), conversationId))
        ChatDbResult.Success(findConversation(conversationId)!!)
    }

    /**
     * Counterpart of deleted `.meta.json` and `LocalRunStore.java:deleteConversation`: preserve a
     * tombstone even when the ledger did not yet contain a conversation, then let FK CASCADE clear it.
     */
    suspend fun deleteConversation(conversationId: String, deletedAtMillis: Long = System.currentTimeMillis()): ChatDbResult<Boolean> =
        transactionResult {
            if (conversationId.isBlank()) return@transactionResult ChatDbResult.Invalid("conversation id is required")
            sql.execSQL(
                "INSERT INTO tombstones(scope,entity_id,deleted_at,payload) " +
                    "SELECT 'conversation',?,?,NULL WHERE NOT EXISTS(" +
                    "SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=?)",
                arrayOf(conversationId, deletedAtMillis, conversationId),
            )
            val existed = findConversation(conversationId) != null
            sql.execSQL("DELETE FROM conversations WHERE id=?", arrayOf(conversationId))
            ChatDbResult.Success(existed)
        }

    /** Reads tombstone state replacing the deletion check through `LocalRunStore.java:readConversationMetadata`. */
    suspend fun isTombstoned(conversationId: String): Boolean = database.withTransaction {
        sql.query("SELECT EXISTS(SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=?)", arrayOf(conversationId))
            .use { it.moveToFirst(); it.getInt(0) != 0 }
    }

    /**
     * Appends one `role=user` or `role=assistant` row and its body/attachment references. The
     * generated UUID is also `stable_id`, replacing `role=user`/`role=assistant` in
     * `LocalRunStore.java:appendConversationMessage`.
     */
    suspend fun appendMessage(input: AppendMessageInput): ChatDbResult<StoredMessage> = transactionResult {
        validateMessageInput(input)?.let { return@transactionResult ChatDbResult.Invalid(it) }
        val conversation = findConversation(input.conversationId) ?: return@transactionResult missingConversation(input.conversationId)
        if (isTombstonedRaw(input.conversationId)) return@transactionResult ChatDbResult.Tombstoned
        if (input.attachments.isNotEmpty() && (conversation.kind != "chat" || !input.proactiveThreadKey.isNullOrEmpty())) {
            return@transactionResult ChatDbResult.Invalid("attachments are supported only in ordinary user messages")
        }
        if (input.proactiveDecisionId != null) {
            val existing = findMessageByDecision(input.conversationId, input.proactiveDecisionId)
            if (existing != null) return@transactionResult ChatDbResult.Success(hydrateMessages(input.conversationId, listOf(existing.messageId)).single())
        }
        val id = UUID.randomUUID().toString()
        val stableId = id
        val now = input.createdAtSeconds ?: epochSeconds()
        val seq = allocateSequence(input.conversationId)
        val sort = sortPositionFor(input.conversationId, seq, input.beforeMessageId)
        val anchor = if (input.role == ROLE_USER && input.interruptRequestId == null) seq else latestTurnAnchor(input.conversationId)
        insertItem(input.conversationId, seq, ITEM_MESSAGE, id, sort.first, sort.second, anchor, now)
        val index = nextMessageIndex(input.conversationId)
        sql.execSQL(
            "INSERT INTO messages(conversation_id,message_id,stable_id,seq,message_index,role,status,run_id," +
                "owner_user_message_id,duration_ms,before_message_id,proactive_thread_key,proactive_reply_to_message_id," +
                "proactive_decision_id,interrupt_request_id,ack_required,created_at,updated_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(input.conversationId, id, stableId, seq, index, input.role, input.status, input.runId,
                input.ownerUserMessageId, input.durationMillis?.takeIf { it > 0 }, input.beforeMessageId, input.proactiveThreadKey,
                input.proactiveReplyToMessageId, input.proactiveDecisionId, input.interruptRequestId,
                input.acknowledgementRequired.asSqlInt(), now, now),
        )
        replaceParts(input.conversationId, id, partsWithAttachmentReferences(input.parts, input.attachments))
        insertAttachments(input.conversationId, id, input.attachments, now)
        insertSuggestedReplies(input.conversationId, id, input.suggestedReplies)
        updateConversationMessageCounters(input.conversationId, now)
        if (input.beforeMessageId != null) recomputeMessageIndices(input.conversationId)
        ChatDbResult.Success(hydrateMessages(input.conversationId, listOf(id)).single())
    }

    /**
     * Idempotent `role=assistant,type=proactive_message` append. Decision IDs are deduplicated
     * inside one conversation, matching the `role=assistant,type=proactive_message` row from
     * `LocalRunStore.java:appendProactiveAssistantMessageIfAbsent`.
     */
    suspend fun appendProactiveAssistantMessageIfAbsent(
        conversationId: String,
        decisionId: String,
        content: String,
        threadKey: String? = null,
        replies: List<SuggestedReplyInput> = emptyList(),
    ): ChatDbResult<StoredMessage> = transactionResult {
        if (decisionId.isBlank()) return@transactionResult ChatDbResult.Invalid("decision id is required")
        if (findConversation(conversationId) == null) return@transactionResult missingConversation(conversationId)
        if (isTombstonedRaw(conversationId)) return@transactionResult ChatDbResult.Tombstoned
        val existing = findMessageByDecision(conversationId, decisionId)
        if (existing != null) return@transactionResult ChatDbResult.Success(hydrateMessages(conversationId, listOf(existing.messageId)).single())
        val id = UUID.randomUUID().toString()
        val now = epochSeconds()
        val seq = allocateSequence(conversationId)
        val sort = sortPositionFor(conversationId, seq, null)
        insertItem(conversationId, seq, ITEM_MESSAGE, id, sort.first, sort.second, latestTurnAnchor(conversationId), now)
        sql.execSQL(
            "INSERT INTO messages(conversation_id,message_id,stable_id,seq,message_index,role,status,proactive_thread_key," +
                "proactive_decision_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(conversationId, id, id, seq, nextMessageIndex(conversationId), ROLE_ASSISTANT, "COMPLETED", threadKey, decisionId, now, now),
        )
        replaceParts(conversationId, id, listOf(MessagePartInput(MessagePartKinds.TEXT, content)))
        insertSuggestedReplies(conversationId, id, replies)
        updateConversationMessageCounters(conversationId, now)
        ChatDbResult.Success(hydrateMessages(conversationId, listOf(id)).single())
    }

    /**
     * Kelivo-style full snapshot checkpoint for message parts. A late streaming snapshot is ignored
     * after a terminal status, replacing status/body rewrites in the `role=assistant` row from
     * `LocalRunStore.java:appendConversationMessage`.
     */
    suspend fun updateMessageSnapshot(
        conversationId: String,
        messageId: String,
        parts: List<MessagePartInput>,
        status: String? = null,
        runId: String? = null,
        ownerUserMessageId: String? = null,
        durationMillis: Long? = null,
        updatedAtSeconds: Double = epochSeconds(),
    ): ChatDbResult<StoredMessage> = transactionResult {
        if (findConversation(conversationId) == null) return@transactionResult missingConversation(conversationId)
        if (isTombstonedRaw(conversationId)) return@transactionResult ChatDbResult.Tombstoned
        val current = findMessage(conversationId, messageId) ?: return@transactionResult ChatDbResult.NotFound
        if (current.role != ROLE_ASSISTANT) return@transactionResult ChatDbResult.Invalid("snapshots belong to assistant messages")
        if (current.status.isTerminalStatus()) return@transactionResult ChatDbResult.Success(hydrateMessages(conversationId, listOf(messageId)).single())
        if (parts.any { it.kind.isBlank() }) return@transactionResult ChatDbResult.Invalid("part kind is required")
        replaceParts(conversationId, messageId, preserveAttachmentReferences(conversationId, messageId, parts))
        sql.execSQL(
            "UPDATE messages SET status=COALESCE(?,status),run_id=COALESCE(?,run_id)," +
                "owner_user_message_id=COALESCE(?,owner_user_message_id),duration_ms=COALESCE(?,duration_ms),updated_at=? " +
                "WHERE conversation_id=? AND message_id=? AND NOT (status IN ('COMPLETED','FAILED','PARTIAL','STOPPED','INTERRUPTED') AND ? IN ('STREAMING','IN_PROGRESS'))",
            arrayOf(status, runId, ownerUserMessageId, durationMillis?.takeIf { it > 0 }, updatedAtSeconds, conversationId, messageId, status ?: ""),
        )
        touchConversation(conversationId, updatedAtSeconds)
        ChatDbResult.Success(hydrateMessages(conversationId, listOf(messageId)).single())
    }

    /** Final `role=assistant` status/body write corresponding to `LocalRunStore.java:appendConversationMessage`. */
    suspend fun finalizeMessage(
        conversationId: String,
        messageId: String,
        parts: List<MessagePartInput>,
        finalStatus: String,
        durationMillis: Long? = null,
        updatedAtSeconds: Double = epochSeconds(),
    ): ChatDbResult<StoredMessage> = transactionResult {
        if (!finalStatus.isTerminalStatus()) return@transactionResult ChatDbResult.Invalid("final status must be terminal")
        if (findConversation(conversationId) == null) return@transactionResult missingConversation(conversationId)
        if (isTombstonedRaw(conversationId)) return@transactionResult ChatDbResult.Tombstoned
        val current = findMessage(conversationId, messageId) ?: return@transactionResult ChatDbResult.NotFound
        if (current.role != ROLE_ASSISTANT) return@transactionResult ChatDbResult.Invalid("finalization belongs to assistant messages")
        if (parts.any { it.kind.isBlank() }) return@transactionResult ChatDbResult.Invalid("part kind is required")
        replaceParts(conversationId, messageId, preserveAttachmentReferences(conversationId, messageId, parts))
        sql.execSQL(
            "UPDATE messages SET status=?,duration_ms=COALESCE(?,duration_ms),updated_at=? WHERE conversation_id=? AND message_id=?",
            arrayOf(finalStatus, durationMillis?.takeIf { it > 0 }, updatedAtSeconds, conversationId, messageId),
        )
        touchConversation(conversationId, updatedAtSeconds)
        ChatDbResult.Success(hydrateMessages(conversationId, listOf(messageId)).single())
    }

    /** Stores the latest `assistant_translation` row; replaces `LocalRunStore.java:appendAssistantTranslation`. */
    suspend fun setTranslation(conversationId: String, messageId: String, language: String, content: String): ChatDbResult<Long> = transactionResult {
        if (findConversation(conversationId) == null) return@transactionResult missingConversation(conversationId)
        val message = findMessage(conversationId, messageId) ?: return@transactionResult ChatDbResult.NotFound
        if (message.role != ROLE_ASSISTANT) return@transactionResult ChatDbResult.Invalid("translations belong to assistant messages")
        val seq = allocateSequence(conversationId)
        val now = epochSeconds()
        insertItem(conversationId, seq, ITEM_TRANSLATION, messageId, seq, 0, latestTurnAnchor(conversationId), now)
        sql.execSQL(
            "INSERT INTO message_translations(conversation_id,message_id,seq,language,content,created_at) VALUES(?,?,?,?,?,?)",
            arrayOf(conversationId, messageId, seq, language, content, now),
        )
        ChatDbResult.Success(seq)
    }

    /** Stores `assistant_regenerated`/`assistant_deleted` markers, replacing `LocalRunStore.java:appendAssistantRegenerated` and `LocalRunStore.java:appendAssistantDeleted`. */
    suspend fun hideAssistant(conversationId: String, messageId: String, kind: String): ChatDbResult<Long> =
        setMessageMarker(conversationId, messageId, kind, "hidden_kind", "hidden_seq")

    /** Stores the positional `assistant_translation_hidden` marker, replacing `LocalRunStore.java:appendAssistantTranslationHidden`. */
    suspend fun hideTranslation(conversationId: String, messageId: String): ChatDbResult<Long> =
        setMessageMarker(conversationId, messageId, "assistant_translation_hidden", "translation_hidden_seq", null)

    /** Stores the current `message_reaction` state; an empty emoji removes it as in `LocalRunStore.java:setMessageReaction`. */
    suspend fun setReaction(conversationId: String, stableMessageId: String, emoji: String): ChatDbResult<ReactionUpdate> = transactionResult {
        if (emoji.isNotEmpty() && !MessageReactionEmoji.isValid(emoji)) return@transactionResult ChatDbResult.Invalid("invalid reaction")
        val conversation = findConversation(conversationId) ?: return@transactionResult missingConversation(conversationId)
        val target = findMessageByStableId(conversationId, stableMessageId) ?: return@transactionResult ChatDbResult.NotFound
        if (conversation.kind != "chat" || target.role != ROLE_USER || !target.proactiveThreadKey.isNullOrEmpty()) {
            return@transactionResult ChatDbResult.Invalid("reaction target is not an ordinary user message")
        }
        val previous = sql.query("SELECT emoji,seq FROM message_reactions WHERE conversation_id=? AND target_stable_id=?", arrayOf(conversationId, stableMessageId)).use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
        }
        if (previous?.first == emoji || previous == null && emoji.isEmpty()) {
            return@transactionResult ChatDbResult.Success(ReactionUpdate(previous?.second, false))
        }
        val seq = allocateSequence(conversationId)
        val now = epochSeconds()
        insertItem(conversationId, seq, ITEM_REACTION, stableMessageId, seq, 0, latestTurnAnchor(conversationId), now)
        if (previous == null) {
            sql.execSQL(
                "INSERT INTO message_reactions(conversation_id,target_stable_id,emoji,seq,created_at) VALUES(?,?,?,?,?)",
                arrayOf(conversationId, target.stableId, emoji, seq, now),
            )
        } else {
            sql.execSQL(
                "UPDATE message_reactions SET emoji=?,seq=?,created_at=? WHERE conversation_id=? AND target_stable_id=? AND emoji<>?",
                arrayOf(emoji, seq, now, conversationId, target.stableId, emoji),
            )
        }
        ChatDbResult.Success(ReactionUpdate(seq, true))
    }

    /** Consumes all `proactive_message.suggestedReplies`, replacing `LocalRunStore.java:claimProactiveSuggestedReply`. */
    suspend fun consumeSuggestedReplies(conversationId: String, messageId: String): ChatDbResult<Long> = transactionResult {
        if (findConversation(conversationId) == null) return@transactionResult missingConversation(conversationId)
        val message = findMessage(conversationId, messageId) ?: return@transactionResult ChatDbResult.NotFound
        if (message.role != ROLE_ASSISTANT || message.proactiveDecisionId == null) return@transactionResult ChatDbResult.Invalid("message has no proactive replies")
        if (message.suggestedRepliesConsumedSeq != null) return@transactionResult ChatDbResult.Success(message.suggestedRepliesConsumedSeq)
        val seq = allocateSequence(conversationId)
        insertItem(conversationId, seq, ITEM_PROACTIVE_REPLY_USED, messageId, seq, 0, message.seq, epochSeconds())
        sql.execSQL("UPDATE messages SET suggested_replies_consumed_seq=? WHERE conversation_id=? AND message_id=? AND suggested_replies_consumed_seq IS NULL", arrayOf(seq, conversationId, messageId))
        ChatDbResult.Success(seq)
    }

    /**
     * Appends `main_interrupt_request` with normalized skill rows, replacing
     * `main_interrupt_request` and skill rows from `LocalRunStore.java:appendInterruptRequest`.
     */
    suspend fun appendInterruptRequest(
        conversationId: String,
        content: String,
        skills: List<String> = emptyList(),
        memoryDisabled: Boolean = false,
    ): ChatDbResult<InterruptRequestValue> = transactionResult {
        if (content.isBlank() || content.length > 32000) return@transactionResult ChatDbResult.Invalid("interrupt content is invalid")
        val conversation = findConversation(conversationId) ?: return@transactionResult missingConversation(conversationId)
        if (conversation.kind != "chat") return@transactionResult ChatDbResult.Invalid("interrupts require an ordinary chat")
        if (isTombstonedRaw(conversationId)) return@transactionResult ChatDbResult.Tombstoned
        val id = UUID.randomUUID().toString()
        val now = epochSeconds()
        val seq = allocateSequence(conversationId)
        insertItem(conversationId, seq, ITEM_INTERRUPT_REQUEST, id, seq, 0, latestTurnAnchor(conversationId), now)
        sql.execSQL(
            "INSERT INTO interrupt_requests(conversation_id,request_id,seq,content,memory_disabled,created_at) VALUES(?,?,?,?,?,?)",
            arrayOf(conversationId, id, seq, content, memoryDisabled.asSqlInt(), now),
        )
        skills.forEachIndexed { index, skill -> sql.execSQL(
            "INSERT INTO interrupt_request_skills(conversation_id,request_id,ordinal,skill_id) VALUES(?,?,?,?)",
            arrayOf(conversationId, id, index, skill),
        ) }
        ChatDbResult.Success(InterruptRequestValue(conversationId, id, seq, content, memoryDisabled, skills.toList(), null, null, null, null))
    }

    /**
     * Atomically writes the dispatched user row and marks the request dispatched, replacing
     * `main_interrupt_request` plus its dispatched `role=user` row from `LocalRunStore.java:dispatchInterruptRequest`.
     * Duplicate dispatch returns the original row.
     */
    suspend fun dispatchInterruptRequest(conversationId: String, requestId: String): ChatDbResult<DispatchedInterrupt> = transactionResult {
        if (findConversation(conversationId) == null) return@transactionResult missingConversation(conversationId)
        val request = findInterrupt(conversationId, requestId) ?: return@transactionResult ChatDbResult.NotFound
        if (request.dispatchedSeq != null && request.dispatchedMessageId != null) {
            val prior = findMessage(conversationId, request.dispatchedMessageId) ?: return@transactionResult ChatDbResult.Conflict("request dispatch points to a missing message")
            return@transactionResult ChatDbResult.Success(DispatchedInterrupt(request, hydrateMessages(conversationId, listOf(prior.messageId)).single(), false))
        }
        if (isTombstonedRaw(conversationId)) return@transactionResult ChatDbResult.Tombstoned
        if (findMessage(conversationId, requestId) != null) return@transactionResult ChatDbResult.Conflict("request id already belongs to a message")
        val now = epochSeconds()
        val seq = allocateSequence(conversationId)
        val anchor = latestTurnAnchor(conversationId)
        insertItem(conversationId, seq, ITEM_MESSAGE, requestId, seq, 0, anchor, now)
        sql.execSQL(
            "INSERT INTO messages(conversation_id,message_id,stable_id,seq,message_index,role,interrupt_request_id,ack_required,created_at,updated_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
            arrayOf(conversationId, requestId, requestId, seq, nextMessageIndex(conversationId), ROLE_USER, requestId, 1, now, now),
        )
        replaceParts(conversationId, requestId, listOf(MessagePartInput(MessagePartKinds.TEXT, request.content)))
        if (request.inputState != null) {
            sql.execSQL("UPDATE messages SET input_state=?,input_state_seq=? WHERE conversation_id=? AND message_id=?", arrayOf(request.inputState, request.inputStateSeq, conversationId, requestId))
        }
        sql.execSQL(
            "UPDATE interrupt_requests SET dispatched_message_id=?,dispatched_seq=? WHERE conversation_id=? AND request_id=? AND dispatched_seq IS NULL",
            arrayOf(requestId, seq, conversationId, requestId),
        )
        updateConversationMessageCounters(conversationId, now)
        val value = findInterrupt(conversationId, requestId)!!
        ChatDbResult.Success(DispatchedInterrupt(value, hydrateMessages(conversationId, listOf(requestId)).single(), true))
    }

    /** Indexed counterpart of `main_interrupt_request` reads in `LocalRunStore.java:readPendingInterruptRequests`; NULL dispatch means pending. */
    suspend fun pendingInterruptRequests(conversationId: String? = null): List<InterruptRequestValue> = database.withTransaction {
        val rows = if (conversationId == null) {
            sql.query("SELECT * FROM interrupt_requests WHERE dispatched_seq IS NULL ORDER BY conversation_id,seq")
        } else {
            sql.query("SELECT * FROM interrupt_requests WHERE conversation_id=? AND dispatched_seq IS NULL ORDER BY seq", arrayOf(conversationId))
        }
        val pending = rows.use { cursor -> buildList {
            while (cursor.moveToNext()) add(InterruptRequestValue(
                cursor.getString(cursor.getColumnIndexOrThrow("conversation_id")), cursor.getString(cursor.getColumnIndexOrThrow("request_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("seq")), cursor.getString(cursor.getColumnIndexOrThrow("content")),
                cursor.getInt(cursor.getColumnIndexOrThrow("memory_disabled")) != 0, emptyList(),
                cursor.getStringOrNull("dispatched_message_id"), cursor.getLongOrNull("dispatched_seq"),
                cursor.getStringOrNull("input_state"), cursor.getLongOrNull("input_state_seq"),
            ))
        } }
        if (pending.isEmpty()) return@withTransaction emptyList()
        val filter = if (conversationId == null) "" else "AND r.conversation_id=?"
        val skillArgs: Array<Any?> = if (conversationId == null) emptyArray() else arrayOf(conversationId)
        val skills = mutableMapOf<Pair<String, String>, MutableList<String>>()
        sql.query(
            "SELECT s.conversation_id,s.request_id,s.skill_id FROM interrupt_request_skills s " +
                "JOIN interrupt_requests r ON r.conversation_id=s.conversation_id AND r.request_id=s.request_id " +
                "WHERE r.dispatched_seq IS NULL $filter ORDER BY r.conversation_id,r.seq,s.ordinal",
            skillArgs,
        ).use { cursor -> while (cursor.moveToNext()) {
            skills.getOrPut(cursor.getString(0) to cursor.getString(1)) { mutableListOf() }.add(cursor.getString(2))
        } }
        pending.map { it.copy(skills = skills[it.conversationId to it.requestId].orEmpty()) }
    }

    /**
     * Stores the exclusive `main_chat_input_incorporated` or `main_chat_input_resolved` state
     * directly on user messages and interrupt receipts, replacing `main_chat_input_incorporated` /
     * `main_chat_input_resolved` from `LocalRunStore.java:markConversationMessagesIncorporated` and
     * `markConversationMessagesResolved`.
     */
    suspend fun markInputState(conversationId: String, messageIds: List<String>, state: String): ChatDbResult<List<String>> = transactionResult {
        if (findConversation(conversationId) == null) return@transactionResult missingConversation(conversationId)
        if (state != INPUT_INCORPORATED && state != INPUT_RESOLVED) return@transactionResult ChatDbResult.Invalid("unknown input state")
        if (messageIds.isEmpty()) return@transactionResult ChatDbResult.Success(emptyList())
        val changed = mutableListOf<String>()
        for (id in messageIds.distinct()) {
            val message = findMessage(conversationId, id)
            val pendingRequest = if (message == null) sql.query(
                "SELECT input_state FROM interrupt_requests WHERE conversation_id=? AND request_id=?", arrayOf(conversationId, id),
            ).use { c -> if (c.moveToFirst()) c.getStringOrNull(0) else NO_INPUT_STATE_ROW } else null
            if (message == null && pendingRequest == NO_INPUT_STATE_ROW) continue
            if (message != null && (message.role != ROLE_USER || message.inputState != null)) continue
            if (message == null && pendingRequest != null) continue
            val seq = allocateSequence(conversationId)
            insertItem(conversationId, seq, state, id, seq, 0, latestTurnAnchor(conversationId), epochSeconds())
            if (message != null) {
                sql.execSQL("UPDATE messages SET input_state=?,input_state_seq=? WHERE conversation_id=? AND message_id=? AND input_state IS NULL", arrayOf(state, seq, conversationId, id))
                sql.execSQL("UPDATE interrupt_requests SET input_state=?,input_state_seq=? WHERE conversation_id=? AND dispatched_message_id=? AND input_state IS NULL", arrayOf(state, seq, conversationId, id))
            } else {
                sql.execSQL("UPDATE interrupt_requests SET input_state=?,input_state_seq=? WHERE conversation_id=? AND request_id=? AND input_state IS NULL", arrayOf(state, seq, conversationId, id))
            }
            changed += id
        }
        ChatDbResult.Success(changed)
    }

    /** Adds/updates `user_decision_request` plus options, replacing `LocalRunStore.java:appendUserDecisionRequest`. */
    suspend fun appendDecisionRequest(
        conversationId: String,
        decisionId: String,
        title: String,
        body: String,
        allowDismiss: Boolean,
        options: List<DecisionOptionInput>,
    ): ChatDbResult<Long> = transactionResult {
        if (decisionId.isBlank()) return@transactionResult ChatDbResult.Invalid("decision id is required")
        if (findConversation(conversationId) == null) return@transactionResult missingConversation(conversationId)
        if (isTombstonedRaw(conversationId)) return@transactionResult ChatDbResult.Tombstoned
        val now = epochSeconds()
        val seq = allocateSequence(conversationId)
        insertItem(conversationId, seq, ITEM_DECISION_REQUEST, decisionId, seq, 0, latestTurnAnchor(conversationId), now)
        val hasDecision = findDecision(conversationId, decisionId)
        if (hasDecision) {
            sql.execSQL(
                "UPDATE user_decisions SET seq=?,title=?,body=?,allow_dismiss=?,status=NULL,option_id=NULL,option_label=NULL,resolved_seq=NULL,created_at=? " +
                    "WHERE conversation_id=? AND decision_id=? AND seq<?",
                arrayOf(seq, title, body, allowDismiss.asSqlInt(), now, conversationId, decisionId, seq),
            )
        } else {
            sql.execSQL(
                "INSERT INTO user_decisions(conversation_id,decision_id,seq,title,body,allow_dismiss,created_at) VALUES(?,?,?,?,?,?,?)",
                arrayOf(conversationId, decisionId, seq, title, body, allowDismiss.asSqlInt(), now),
            )
        }
        sql.execSQL("DELETE FROM user_decision_options WHERE conversation_id=? AND decision_id=?", arrayOf(conversationId, decisionId))
        options.forEachIndexed { index, option -> sql.execSQL(
            "INSERT INTO user_decision_options(conversation_id,decision_id,ordinal,option_id,label,description,role) VALUES(?,?,?,?,?,?,?)",
            arrayOf(conversationId, decisionId, index, option.id, option.label, option.description, option.role),
        ) }
        ChatDbResult.Success(seq)
    }

    /** Appends a last-wins `user_decision_resolution`, replacing `LocalRunStore.java:appendUserDecisionResolution`. */
    suspend fun resolveDecision(conversationId: String, decisionId: String, status: String, optionId: String?, optionLabel: String?): ChatDbResult<Long> = transactionResult {
        if (findConversation(conversationId) == null) return@transactionResult missingConversation(conversationId)
        if (findDecision(conversationId, decisionId) == null) return@transactionResult ChatDbResult.NotFound
        val seq = allocateSequence(conversationId)
        insertItem(conversationId, seq, ITEM_DECISION_RESOLUTION, decisionId, seq, 0, latestTurnAnchor(conversationId), epochSeconds())
        sql.execSQL("UPDATE user_decisions SET status=?,option_id=?,option_label=?,resolved_seq=? WHERE conversation_id=? AND decision_id=?", arrayOf(status, optionId, optionLabel, seq, conversationId, decisionId))
        ChatDbResult.Success(seq)
    }

    /** Reads `role=user`/`role=assistant` rows and normalized children, replacing `LocalRunStore.java:readConversationMessage`, `LocalRunStore.java:readConversationMessages`, `LocalRunStore.java:readConversationAttachments`, and `LocalRunStore.java:appendAssistantTranslation` projections. */
    suspend fun getMessage(conversationId: String, messageId: String): StoredMessage? = getMessagesByIds(conversationId, listOf(messageId)).firstOrNull()

    /**
     * Hydrates requested `role=user`/`role=assistant` identities in caller order. Body parts,
     * attachments, translations, and proactive replies are fetched in one UNION query, replacing
     * `LocalRunStore.java:readConversationMessages`, `LocalRunStore.java:readConversationMessage`,
     * and `LocalRunStore.java:readConversationAttachments` without N+1 hydration.
     */
    suspend fun getMessagesByIds(conversationId: String, messageIds: List<String>): List<StoredMessage> = database.withTransaction {
        if (messageIds.isEmpty()) emptyList() else {
            val ids = messageIds.distinct()
            val placeholders = ids.joinToString(",") { "(?)" }
            val baseSql = "WITH requested(message_id) AS (VALUES $placeholders) " +
                "SELECT m.* FROM messages m JOIN requested r ON r.message_id=m.message_id WHERE m.conversation_id=?"
            val baseArgs = arrayOf<Any?>(*ids.toTypedArray(), conversationId)
            val byId = mutableMapOf<String, StoredMessage>()
            sql.query(baseSql, baseArgs).use { cursor -> while (cursor.moveToNext()) {
                val value = readMessage(cursor)
                byId[value.messageId] = value
            } }
            if (byId.isEmpty()) emptyList() else {
                val loaded = hydrateChildren(conversationId, byId)
                messageIds.mapNotNull(loaded::get)
            }
        }
    }

    /**
     * Replaces `role=user`/`role=assistant` history windows from `LocalRunStore.java:loadConversationContext`; a steering dispatch
     * (`interrupt_request_id` set) is deliberately not a turn anchor, matching the legacy pager rule.
     */
    suspend fun readTailWindow(conversationId: String, turns: Int): MessageWindow {
        require(turns > 0) { "turns must be positive" }
        return readWindow(conversationId, cursorSeq = null, turns = turns)
    }

    /** Replaces paged timeline reads from `LocalRunStore.java:readConversationTimelinePage`, resolving the ledger sequence cursor to the projected sort key. */
    suspend fun readWindowBefore(conversationId: String, cursorSeq: Long, turns: Int): MessageWindow {
        require(turns > 0) { "turns must be positive" }
        require(cursorSeq > 0) { "cursorSeq must be positive" }
        return readWindow(conversationId, cursorSeq, turns)
    }

    /** Returns the latest `role=user` ID derived from `LocalRunStore.java:readConversationMessages`. */
    suspend fun latestUserMessageId(conversationId: String): String? = database.withTransaction {
        sql.query("SELECT message_id FROM messages WHERE conversation_id=? AND role='user' ORDER BY seq DESC LIMIT 1", arrayOf(conversationId))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    /** Returns the first `role=user` row, replacing `LocalRunStore.java:readFirstUserMessage` and `LocalRunStore.java:loadConversationContext`. */
    suspend fun firstUserMessage(conversationId: String): StoredMessage? = database.withTransaction {
        val id = sql.query("SELECT message_id FROM messages WHERE conversation_id=? AND role='user' ORDER BY seq LIMIT 1", arrayOf(conversationId)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        id?.let { hydrateMessages(conversationId, listOf(it)).firstOrNull() }
    }

    /** Counts `role=user`/`role=assistant` rows from `LocalRunStore.java:readConversationMessages`. */
    suspend fun messageCounts(conversationId: String): MessageCounts = database.withTransaction {
        sql.query(
            "SELECT message_count,(SELECT COUNT(*) FROM messages WHERE conversation_id=? AND role='user') FROM conversations WHERE id=?",
            arrayOf(conversationId, conversationId),
        ).use { c -> if (c.moveToFirst()) MessageCounts(c.getLong(0), c.getLong(1)) else MessageCounts(0, 0) }
    }

    /** One SQL projection for the drawer, replacing title/active-message summaries in `LocalRunStore.java:listConversations`. */
    suspend fun listConversations(
        limit: Int,
        excludedKinds: Set<String> = DEFAULT_EXCLUDED_KINDS,
    ): List<ConversationSummary> = database.withTransaction {
        require(limit > 0) { "limit must be positive" }
        val excluded = excludedKinds.toList()
        val hasActiveMessages = "EXISTS(SELECT 1 FROM messages active WHERE active.conversation_id=c.id AND " +
            "(active.role='user' OR (active.role='assistant' AND active.hidden_kind IS NULL)))"
        val exclusionSql = if (excluded.isEmpty()) "WHERE $hasActiveMessages" else "WHERE c.kind NOT IN (${excluded.joinToString(",") { "?" }}) AND $hasActiveMessages"
        fun orderedText(messageId: String) = "(SELECT group_concat(ordered_payload,'') FROM (" +
            "SELECT p.payload AS ordered_payload FROM message_parts p WHERE p.conversation_id=c.id AND p.message_id=$messageId AND p.kind='text' ORDER BY p.ordinal))"
        val firstUserId = "(SELECT m.message_id FROM messages m WHERE m.conversation_id=c.id AND m.role='user' ORDER BY m.seq LIMIT 1)"
        val latestGoalId = "(SELECT m.message_id FROM messages m WHERE m.conversation_id=c.id AND m.role='user' ORDER BY m.message_index DESC LIMIT 1)"
        val firstUser = orderedText(firstUserId)
        val latestGoal = orderedText(latestGoalId)
        val latestActiveId = "(SELECT m.message_id FROM messages m WHERE m.conversation_id=c.id AND " +
            "(m.role='user' OR (m.role='assistant' AND m.hidden_kind IS NULL)) ORDER BY m.message_index DESC LIMIT 1)"
        val latestPreview = orderedText(latestActiveId)
        val latestActiveAt = "(SELECT CAST(m.created_at*1000 AS INTEGER) FROM messages m WHERE m.conversation_id=c.id AND " +
            "(m.role='user' OR (m.role='assistant' AND m.hidden_kind IS NULL)) ORDER BY m.message_index DESC LIMIT 1)"
        val sqlText = "SELECT c.id,c.kind,NULLIF(TRIM(c.title),''),NULLIF(TRIM(c.generated_title),''),$firstUser,$latestPreview,$latestGoal," +
            "((SELECT COUNT(*) FROM messages m WHERE m.conversation_id=c.id AND (m.role='user' OR (m.role='assistant' AND m.hidden_kind IS NULL)))+1)/2 AS turns," +
            "c.message_count,c.created_at,$latestActiveAt AS latest_active_at,c.pinned,c.archived,0 AS deleted " +
            "FROM conversations c $exclusionSql ORDER BY c.pinned DESC,latest_active_at DESC,c.id LIMIT ?"
        val args = arrayOf<Any?>(*excluded.toTypedArray(), limit)
        sql.query(sqlText, args).use { c -> buildList { while (c.moveToNext()) add(
            run {
                val createdAt = c.getLong(9)
                val activeAt = c.getLongOrNull(10)
                val title = c.getStringOrNull(2)?.takeIf(String::isNotBlank)
                    ?: c.getStringOrNull(3)?.takeIf(String::isNotBlank)
                    ?: ConversationTitle.fromFirstMessage(c.getStringOrNull(4))
                ConversationSummary(c.getString(0), c.getString(1), title, c.getStringOrNull(5), c.getStringOrNull(6), c.getLong(7), c.getLong(8), createdAt, activeAt ?: createdAt, activeAt, c.getInt(11) != 0, c.getInt(12) != 0, c.getInt(13) != 0)
            },
        ) } }
    }

    /** Creates a `generation_run` for a streaming `role=assistant` row from `LocalRunStore.java:appendConversationMessage`; run revision locking is new to SQLite. */
    suspend fun createGenerationRun(runId: String, conversationId: String, messageId: String, state: String = RUN_PREPARING): ChatDbResult<GenerationRunValue> = transactionResult {
        if (runId.isBlank()) return@transactionResult ChatDbResult.Invalid("run id is required")
        if (!state.isActiveRunState()) return@transactionResult ChatDbResult.Invalid("new run state must be active")
        if (findConversation(conversationId) == null) return@transactionResult missingConversation(conversationId)
        val message = findMessage(conversationId, messageId) ?: return@transactionResult ChatDbResult.NotFound
        if (message.role != ROLE_ASSISTANT) return@transactionResult ChatDbResult.Invalid("generation runs belong to assistant messages")
        if (findRun(runId) != null) return@transactionResult ChatDbResult.Conflict("run id already exists")
        val target = activeTargetKey(conversationId, messageId)
        val targetBusy = sql.query("SELECT EXISTS(SELECT 1 FROM generation_run WHERE active_target_key=?)", arrayOf(target)).use { c -> c.moveToFirst(); c.getInt(0) != 0 }
        if (targetBusy) return@transactionResult ChatDbResult.Conflict("assistant message already has an active run")
        val now = System.currentTimeMillis()
        sql.execSQL("INSERT INTO generation_run(id,conversation_id,message_id,state,state_revision,checkpoint_seq,created_at,updated_at,active_target_key) VALUES(?,?,?,?,0,0,?,?,?)", arrayOf(runId, conversationId, messageId, state, now, now, target))
        ChatDbResult.Success(findRun(runId)!!)
    }

    /**
     * Optimistically transitions `generation_run` while projecting terminal `role=assistant.status`
     * writes from `LocalRunStore.java:appendConversationMessage`; revision, checkpoint monotonicity,
     * and active-target release commit together.
     */
    suspend fun transitionGenerationRun(
        runId: String,
        expectedRevision: Long,
        newState: String,
        checkpointSeq: Long? = null,
        errorCode: String? = null,
    ): ChatDbResult<GenerationRunValue> = transactionResult {
        val run = findRun(runId) ?: return@transactionResult ChatDbResult.NotFound
        if (run.stateRevision != expectedRevision) return@transactionResult ChatDbResult.Conflict("state revision changed")
        if (!newState.isActiveRunState() && !newState.isTerminalRunState()) return@transactionResult ChatDbResult.Invalid("unknown generation state")
        if (!run.state.isActiveRunState()) return@transactionResult ChatDbResult.Conflict("terminal generation run cannot transition")
        if (checkpointSeq != null && checkpointSeq < run.checkpointSeq) return@transactionResult ChatDbResult.Conflict("checkpoint sequence moved backwards")
        val now = System.currentTimeMillis()
        val terminalAt = if (newState.isTerminalRunState()) now else null
        val target = if (newState.isActiveRunState()) activeTargetKey(run.conversationId, run.messageId) else null
        val nextCheckpoint = checkpointSeq ?: run.checkpointSeq
        sql.execSQL(
            "UPDATE generation_run SET state=?,state_revision=state_revision+1,checkpoint_seq=?,updated_at=?,terminal_at=?,error_code=?,active_target_key=? " +
                "WHERE id=? AND state_revision=? AND state IN ('preparing','requesting','streaming','waiting_tool')",
            arrayOf(newState, nextCheckpoint, now, terminalAt, errorCode, target, runId, expectedRevision),
        )
        if (findRun(runId)?.stateRevision == expectedRevision) ChatDbResult.Conflict("run changed during transition") else ChatDbResult.Success(findRun(runId)!!)
    }

    /** Startup recovery for streaming `role=assistant.status` rows from `LocalRunStore.java:appendConversationMessage`; current LocalRunStore has no generation-run recovery API. */
    suspend fun markStaleRunsInterrupted(): Int = database.withTransaction {
        val now = System.currentTimeMillis()
        sql.execSQL(
            "UPDATE generation_run SET state='interrupted',state_revision=state_revision+1,updated_at=?,terminal_at=?,error_code=COALESCE(error_code,'app_restart'),active_target_key=NULL " +
                "WHERE state IN ('preparing','requesting','streaming','waiting_tool')",
            arrayOf(now, now),
        )
        val interrupted = sql.query("SELECT changes()").use { c -> c.moveToFirst(); c.getInt(0) }
        sql.execSQL(
            "UPDATE messages SET status='INTERRUPTED',updated_at=? WHERE status IN ('STREAMING','IN_PROGRESS') AND EXISTS(" +
                "SELECT 1 FROM generation_run r WHERE r.conversation_id=messages.conversation_id AND r.message_id=messages.message_id " +
                "AND r.state='interrupted')",
            arrayOf(now / 1000.0),
        )
        interrupted
    }

    private suspend fun setMessageMarker(conversationId: String, messageId: String, kind: String, valueColumn: String, seqColumn: String?): ChatDbResult<Long> = transactionResult {
        if (kind !in setOf("assistant_regenerated", "assistant_deleted", "assistant_translation_hidden")) return@transactionResult ChatDbResult.Invalid("unknown message marker")
        if (findConversation(conversationId) == null) return@transactionResult missingConversation(conversationId)
        val target = findMessage(conversationId, messageId) ?: return@transactionResult ChatDbResult.NotFound
        if (target.role != ROLE_ASSISTANT) return@transactionResult ChatDbResult.Invalid("assistant marker target must be an assistant message")
        val seq = allocateSequence(conversationId)
        val now = epochSeconds()
        insertItem(conversationId, seq, kind, messageId, seq, 0, latestTurnAnchor(conversationId), now)
        if (seqColumn == null) {
            sql.execSQL("UPDATE messages SET $valueColumn=? WHERE conversation_id=? AND message_id=?", arrayOf(seq, conversationId, messageId))
        } else {
            sql.execSQL("UPDATE messages SET $valueColumn=?,$seqColumn=? WHERE conversation_id=? AND message_id=?", arrayOf(kind, seq, conversationId, messageId))
        }
        ChatDbResult.Success(seq)
    }

    private suspend fun readWindow(conversationId: String, cursorSeq: Long?, turns: Int): MessageWindow = database.withTransaction {
        val cursorPosition = cursorSeq?.let { seq ->
            sql.query("SELECT sort_seq,sort_sub,seq FROM conversation_items WHERE conversation_id=? AND seq=?", arrayOf(conversationId, seq)).use { c ->
                if (c.moveToFirst()) SortPosition(c.getLong(0), c.getInt(1), c.getLong(2)) else null
            }
        }
        if (cursorSeq != null && cursorPosition == null) return@withTransaction MessageWindow(conversationId, emptyList(), null)

        val beforeCursor = cursorPosition?.let { " AND ${tupleLess("i")}" }.orEmpty()
        val anchorArgs = mutableListOf<Any?>(conversationId)
        if (cursorPosition != null) anchorArgs.addAll(tupleArguments(cursorPosition))
        anchorArgs += turns - 1
        val anchorSql = "SELECT i.seq,i.sort_seq,i.sort_sub FROM conversation_items i " +
            "JOIN messages m ON m.conversation_id=i.conversation_id AND m.seq=i.seq " +
            "WHERE i.conversation_id=? AND m.role='user' AND m.interrupt_request_id IS NULL$beforeCursor " +
            "ORDER BY i.sort_seq DESC,i.sort_sub DESC,i.seq DESC LIMIT 1 OFFSET ?"
        val selectedAnchor = sql.query(anchorSql, anchorArgs.toTypedArray()).use { c ->
            if (c.moveToFirst()) SortPosition(c.getLong(1), c.getInt(2), c.getLong(0)) else null
        }
        val earlierAnchors = selectedAnchor?.let { selected ->
            sql.query(
                "SELECT COUNT(*) FROM conversation_items i JOIN messages m ON m.conversation_id=i.conversation_id AND m.seq=i.seq " +
                    "WHERE i.conversation_id=? AND m.role='user' AND m.interrupt_request_id IS NULL AND ${tupleLess("i")}",
                arrayOf<Any?>(conversationId, *tupleArguments(selected)),
            ).use { c -> c.moveToFirst(); c.getLong(0) }
        } ?: 0L
        val startPosition = selectedAnchor?.takeIf { earlierAnchors > 0L } ?: run {
            val upper = cursorPosition?.let { " AND ${tupleLess("i")}" }.orEmpty()
            val args = mutableListOf<Any?>(conversationId)
            if (cursorPosition != null) args.addAll(tupleArguments(cursorPosition))
            sql.query(
                "SELECT i.sort_seq,i.sort_sub,i.seq FROM conversation_items i WHERE i.conversation_id=?$upper " +
                    "ORDER BY i.sort_seq,i.sort_sub,i.seq LIMIT 1",
                args.toTypedArray(),
            ).use { c -> if (c.moveToFirst()) SortPosition(c.getLong(0), c.getInt(1), c.getLong(2)) else null }
        }
        if (startPosition == null) {
            MessageWindow(conversationId, emptyList(), null)
        } else {
            val upper = cursorPosition?.let { " AND ${tupleLess("conversation_items")}" }.orEmpty()
            val rangeArgs = mutableListOf<Any?>(conversationId).also {
                it.addAll(tupleGreaterOrEqualArguments(startPosition))
                if (cursorPosition != null) it.addAll(tupleArguments(cursorPosition))
            }
            val itemSql = "SELECT * FROM conversation_items WHERE conversation_id=? AND ${tupleGreaterOrEqual("conversation_items")}$upper ORDER BY sort_seq,sort_sub,seq"
            val items = sql.query(itemSql, rangeArgs.toTypedArray()).use { c ->
                buildList { while (c.moveToNext()) add(readItem(c)) }
            }
            val messageIds = items.filter { it.item_kind == ITEM_MESSAGE }.map { it.item_key }
            val messages = hydrateMessages(conversationId, messageIds).associateBy { it.messageId }
            MessageWindow(conversationId, items.map { MessageSlot(it, messages[it.item_key]) }, selectedAnchor?.seq?.takeIf { earlierAnchors > 0L })
        }
    }

    private data class SortPosition(val sortSeq: Long, val sortSub: Int, val seq: Long)

    private fun tupleLess(alias: String) =
        "($alias.sort_seq<? OR ($alias.sort_seq=? AND $alias.sort_sub<?) OR ($alias.sort_seq=? AND $alias.sort_sub=? AND $alias.seq<?))"

    private fun tupleGreaterOrEqual(alias: String) =
        "($alias.sort_seq>? OR ($alias.sort_seq=? AND $alias.sort_sub>?) OR ($alias.sort_seq=? AND $alias.sort_sub=? AND $alias.seq>=?))"

    private fun tupleArguments(position: SortPosition): Array<Any?> = arrayOf(
        position.sortSeq, position.sortSeq, position.sortSub, position.sortSeq, position.sortSub, position.seq,
    )

    private fun tupleGreaterOrEqualArguments(position: SortPosition): Array<Any?> = arrayOf(
        position.sortSeq, position.sortSeq, position.sortSub, position.sortSeq, position.sortSub, position.seq,
    )

    private fun validateMessageInput(input: AppendMessageInput): String? {
        if (input.conversationId.isBlank()) return "conversation id is required"
        if (input.role != ROLE_USER && input.role != ROLE_ASSISTANT) return "role must be user or assistant"
        if (input.parts.any { it.kind.isBlank() }) return "part kind is required"
        if (input.attachments.isNotEmpty() && input.role != ROLE_USER) return "attachments belong to user messages"
        if (input.attachments.any { it.relativePath.isBlank() || it.attachmentId.isBlank() }) return "attachment identity and relative path are required"
        if (input.beforeMessageId != null && input.role != ROLE_ASSISTANT) return "only assistant messages can be inserted before another message"
        if (input.proactiveDecisionId != null && input.proactiveDecisionId.isBlank()) return "proactive decision id is required"
        if (input.proactiveDecisionId != null && input.role != ROLE_ASSISTANT) return "proactive decision messages must be assistant rows"
        if (input.interruptRequestId != null && input.role != ROLE_USER) return "interrupt dispatches must be user rows"
        return null
    }

    private fun allocateSequence(conversationId: String): Long {
        val current = sql.query("SELECT next_seq FROM conversations WHERE id=?", arrayOf(conversationId)).use { c ->
            if (!c.moveToFirst()) throw RepositoryAbort("conversation_not_found")
            c.getLong(0)
        }
        sql.execSQL("UPDATE conversations SET next_seq=?,updated_at=? WHERE id=? AND next_seq=?", arrayOf(current + 1, System.currentTimeMillis(), conversationId, current))
        return current
    }

    private fun insertItem(conversationId: String, seq: Long, kind: String, key: String, sortSeq: Long, sortSub: Int, anchor: Long?, createdAt: Double) {
        sql.execSQL(
            "INSERT INTO conversation_items(conversation_id,seq,item_kind,item_key,sort_seq,sort_sub,turn_anchor_seq,created_at) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(conversationId, seq, kind, key, sortSeq, sortSub, anchor, createdAt),
        )
    }

    private fun sortPositionFor(conversationId: String, newSeq: Long, beforeMessageId: String?): Pair<Long, Int> {
        if (beforeMessageId == null) return newSeq to 0
        val target = sql.query(
            "SELECT i.sort_seq,i.sort_sub FROM conversation_items i JOIN messages m ON m.conversation_id=i.conversation_id AND m.seq=i.seq WHERE i.conversation_id=? AND m.message_id=?",
            arrayOf(conversationId, beforeMessageId),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getInt(1) else null }
            ?: throw RepositoryAbort("before_message_not_found")
        return target.first to (target.second - 1)
    }

    private fun latestTurnAnchor(conversationId: String): Long? = sql.query(
        "SELECT seq FROM messages WHERE conversation_id=? AND role='user' AND interrupt_request_id IS NULL ORDER BY seq DESC LIMIT 1",
        arrayOf(conversationId),
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun nextMessageIndex(conversationId: String): Long = sql.query(
        "SELECT COUNT(*) FROM messages WHERE conversation_id=?", arrayOf(conversationId),
    ).use { c -> c.moveToFirst(); c.getLong(0) }

    private fun recomputeMessageIndices(conversationId: String) {
        sql.execSQL(
            "UPDATE messages SET message_index=(SELECT COUNT(*) FROM messages prior " +
                "JOIN conversation_items pi ON pi.conversation_id=prior.conversation_id AND pi.seq=prior.seq " +
                "JOIN conversation_items ti ON ti.conversation_id=messages.conversation_id AND ti.seq=messages.seq " +
                "WHERE prior.conversation_id=messages.conversation_id AND " +
                "(pi.sort_seq<ti.sort_seq OR (pi.sort_seq=ti.sort_seq AND pi.sort_sub<ti.sort_sub) OR " +
                "(pi.sort_seq=ti.sort_seq AND pi.sort_sub=ti.sort_sub AND prior.seq<messages.seq))) WHERE conversation_id=?",
            arrayOf(conversationId),
        )
    }

    private fun replaceParts(conversationId: String, messageId: String, parts: List<MessagePartInput>) {
        parts.forEachIndexed { index, part ->
            sql.execSQL(
                "UPDATE message_parts SET kind=?,payload=? WHERE conversation_id=? AND message_id=? AND ordinal=? AND (kind<>? OR payload<>?)",
                arrayOf(part.kind, part.payload, conversationId, messageId, index, part.kind, part.payload),
            )
            sql.execSQL(
                "INSERT INTO message_parts(conversation_id,message_id,ordinal,kind,payload) " +
                    "SELECT ?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM message_parts WHERE conversation_id=? AND message_id=? AND ordinal=?)",
                arrayOf(conversationId, messageId, index, part.kind, part.payload, conversationId, messageId, index),
            )
        }
        sql.execSQL("DELETE FROM message_parts WHERE conversation_id=? AND message_id=? AND ordinal>=?", arrayOf(conversationId, messageId, parts.size))
    }

    private fun partsWithAttachmentReferences(parts: List<MessagePartInput>, attachments: List<MessageAttachmentInput>): List<MessagePartInput> =
        parts.filterNot { it.kind == MessagePartKinds.ATTACHMENT_REF } + attachments.map(::attachmentReferencePart)

    /** Snapshots replace body parts while attachment rows remain stable; rebuild their ordered refs once. */
    private fun preserveAttachmentReferences(conversationId: String, messageId: String, parts: List<MessagePartInput>): List<MessagePartInput> {
        val ids = sql.query(
            "SELECT attachment_id FROM message_attachments WHERE conversation_id=? AND message_id=? ORDER BY ordinal",
            arrayOf(conversationId, messageId),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return parts.filterNot { it.kind == MessagePartKinds.ATTACHMENT_REF } + ids.map(::attachmentReferencePart)
    }

    private fun attachmentReferencePart(attachmentId: String) = MessagePartInput(
        MessagePartKinds.ATTACHMENT_REF,
        JSONObject().put("attachmentId", attachmentId).toString(),
    )

    private fun insertAttachments(conversationId: String, messageId: String, attachments: List<MessageAttachmentInput>, now: Double) {
        attachments.forEachIndexed { index, attachment ->
            val fileId = sql.query("SELECT id FROM file_refs WHERE relative_path=?", arrayOf(attachment.relativePath)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            val resolvedFileId = fileId ?: UUID.randomUUID().toString().also { id ->
                sql.execSQL("INSERT INTO file_refs(id,sha256,relative_path,bytes,last_referenced_at) VALUES(?,?,?,?,?)", arrayOf(id, attachment.sha256.orEmpty(), attachment.relativePath, attachment.sizeBytes, (now * 1000).toLong()))
            }
            if (fileId != null) sql.execSQL(
                "UPDATE file_refs SET sha256=COALESCE(NULLIF(?,''),sha256),bytes=?,last_referenced_at=? WHERE id=?",
                arrayOf(attachment.sha256.orEmpty(), attachment.sizeBytes, (now * 1000).toLong(), fileId),
            )
            sql.execSQL(
                "INSERT INTO message_attachments(conversation_id,message_id,ordinal,attachment_id,name,mime_type,size_bytes,kind,relative_path,sha256,file_ref_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(conversationId, messageId, index, attachment.attachmentId, attachment.name, attachment.mimeType, attachment.sizeBytes, attachment.kind, attachment.relativePath, attachment.sha256, resolvedFileId),
            )
        }
        sql.execSQL("DELETE FROM message_attachments WHERE conversation_id=? AND message_id=? AND ordinal>=?", arrayOf(conversationId, messageId, attachments.size))
    }

    private fun insertSuggestedReplies(conversationId: String, messageId: String, replies: List<SuggestedReplyInput>) {
        replies.forEachIndexed { index, reply -> sql.execSQL(
            "INSERT INTO proactive_suggested_replies(conversation_id,message_id,ordinal,label,text) VALUES(?,?,?,?,?)",
            arrayOf(conversationId, messageId, index, reply.label, reply.text),
        ) }
        sql.execSQL("DELETE FROM proactive_suggested_replies WHERE conversation_id=? AND message_id=? AND ordinal>=?", arrayOf(conversationId, messageId, replies.size))
    }

    private fun updateConversationMessageCounters(conversationId: String, createdAt: Double) {
        sql.execSQL("UPDATE conversations SET message_count=message_count+1,last_message_at=?,updated_at=? WHERE id=?", arrayOf((createdAt * 1000).toLong(), (createdAt * 1000).toLong(), conversationId))
    }

    private fun touchConversation(conversationId: String, updatedAtSeconds: Double) {
        sql.execSQL("UPDATE conversations SET updated_at=? WHERE id=?", arrayOf((updatedAtSeconds * 1000).toLong(), conversationId))
    }

    private fun hydrateMessages(conversationId: String, ids: List<String>): List<StoredMessage> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "(?)" }
        val baseSql = "WITH requested(message_id) AS (VALUES $placeholders) SELECT m.* FROM messages m JOIN requested r ON r.message_id=m.message_id WHERE m.conversation_id=?"
        val byId = mutableMapOf<String, StoredMessage>()
        sql.query(baseSql, arrayOf<Any?>(*ids.toTypedArray(), conversationId)).use { c -> while (c.moveToNext()) { val msg = readMessage(c); byId[msg.messageId] = msg } }
        return hydrateChildren(conversationId, byId).values.toList()
    }

    private fun hydrateChildren(conversationId: String, base: Map<String, StoredMessage>): Map<String, StoredMessage> {
        if (base.isEmpty()) return emptyMap()
        val ids = base.keys.toList()
        val values = ids.joinToString(",") { "(?)" }
        val childrenSql = "WITH requested(message_id) AS (VALUES $values) " +
            "SELECT 'part' AS type,p.message_id,p.ordinal,p.kind,p.payload,NULL AS extra1,NULL AS extra2,NULL AS extra3,NULL AS extra4,NULL AS extra5,NULL AS extra6,NULL AS extra7,NULL AS extra8,NULL AS extra9,NULL AS extra10 " +
            "FROM message_parts p JOIN requested r ON r.message_id=p.message_id WHERE p.conversation_id=? " +
            "UNION ALL SELECT 'attachment',a.message_id,a.ordinal,a.attachment_id,a.name,a.mime_type,a.size_bytes,a.kind,a.relative_path,a.sha256,a.file_ref_id,NULL,NULL,NULL,NULL " +
            "FROM message_attachments a JOIN requested r ON r.message_id=a.message_id WHERE a.conversation_id=? " +
            "UNION ALL SELECT 'translation',t.message_id,t.seq,t.language,t.content,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL " +
            "FROM message_translations t JOIN requested r ON r.message_id=t.message_id WHERE t.conversation_id=? AND t.seq=(SELECT MAX(latest.seq) FROM message_translations latest WHERE latest.conversation_id=t.conversation_id AND latest.message_id=t.message_id) " +
            "UNION ALL SELECT 'reply',s.message_id,s.ordinal,s.label,s.text,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL " +
            "FROM proactive_suggested_replies s JOIN requested r ON r.message_id=s.message_id WHERE s.conversation_id=? ORDER BY 2,1,3"
        val parts = ids.associateWith { mutableListOf<MessagePartInput>() }
        val attachments = ids.associateWith { mutableListOf<MessageAttachmentInput>() }
        val translations = mutableMapOf<String, TranslationValue>()
        val replies = ids.associateWith { mutableListOf<SuggestedReplyInput>() }
        sql.query(childrenSql, arrayOf<Any?>(*ids.toTypedArray(), conversationId, conversationId, conversationId, conversationId)).use { c ->
            while (c.moveToNext()) {
                val type = c.getString(0); val id = c.getString(1)
                when (type) {
                    "part" -> parts[id]?.add(MessagePartInput(c.getString(3), c.getString(4)))
                    "attachment" -> attachments[id]?.add(MessageAttachmentInput(c.getString(3), c.getString(4), c.getStringOrNull(5), c.getLong(6), c.getString(7), c.getString(8), c.getStringOrNull(9)))
                    "translation" -> translations[id] = TranslationValue(c.getString(3), c.getString(4), c.getLong(2))
                    "reply" -> replies[id]?.add(SuggestedReplyInput(c.getString(3), c.getString(4)))
                }
            }
        }
        return base.mapValues { (id, message) ->
            val translation = translations[id]?.takeIf { message.translationHiddenSeq == null || it.seq > message.translationHiddenSeq }
            message.copy(parts = parts[id].orEmpty(), attachments = attachments[id].orEmpty(), translation = translation, suggestedReplies = replies[id].orEmpty())
        }
    }

    private fun findMessage(conversationId: String, messageId: String): StoredMessage? = sql.query(
        "SELECT * FROM messages WHERE conversation_id=? AND message_id=? LIMIT 1", arrayOf(conversationId, messageId),
    ).use { c -> if (c.moveToFirst()) readMessage(c) else null }

    private fun findMessageByStableId(conversationId: String, stableId: String): StoredMessage? = sql.query(
        "SELECT * FROM messages WHERE conversation_id=? AND stable_id=? LIMIT 1", arrayOf(conversationId, stableId),
    ).use { c -> if (c.moveToFirst()) readMessage(c) else null }

    private data class MessageIdentity(val messageId: String)
    private fun findMessageByDecision(conversationId: String, decisionId: String): MessageIdentity? = sql.query(
        "SELECT message_id FROM messages WHERE conversation_id=? AND proactive_decision_id=? LIMIT 1", arrayOf(conversationId, decisionId),
    ).use { c -> if (c.moveToFirst()) MessageIdentity(c.getString(0)) else null }

    private fun findDecision(conversationId: String, decisionId: String): Boolean = sql.query(
        "SELECT 1 FROM user_decisions WHERE conversation_id=? AND decision_id=?", arrayOf(conversationId, decisionId),
    ).use { it.moveToFirst() }

    private fun findConversation(id: String): Conversation? = sql.query("SELECT * FROM conversations WHERE id=?", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) null else Conversation(
            id=c.getString(0), kind=c.getString(1), title=c.getStringOrNull(2), generated_title=c.getStringOrNull(3),
            pinned=c.getInt(4)!=0, archived=c.getInt(5)!=0, private_code=c.getInt(6)!=0,
            created_at=c.getLong(7), updated_at=c.getLong(8), last_message_at=c.getLongOrNull(9), message_count=c.getLong(10), next_seq=c.getLong(11),
            import_state=c.getString(12), legacy_ledger_bytes=c.getLongOrNull(13), legacy_ledger_sha256=c.getStringOrNull(14), imported_at=c.getLongOrNull(15),
        )
    }

    private fun isTombstonedRaw(id: String): Boolean = sql.query("SELECT EXISTS(SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=?)", arrayOf(id)).use { c -> c.moveToFirst(); c.getInt(0)!=0 }

    private fun normalizeManualTitle(value: String): String? = runCatching {
        ConversationMetadataStore.normalizeTitle(value)
    }.getOrNull()

    private fun findInterrupt(conversationId: String, requestId: String): InterruptRequestValue? = sql.query(
        "SELECT * FROM interrupt_requests WHERE conversation_id=? AND request_id=?", arrayOf(conversationId, requestId),
    ).use { c -> if (c.moveToFirst()) readInterrupt(c) else null }

    private fun findRun(runId: String): GenerationRunValue? = sql.query("SELECT * FROM generation_run WHERE id=?", arrayOf(runId)).use { c ->
        if (!c.moveToFirst()) null else GenerationRunValue(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5), c.getLong(6), c.getLong(7), c.getLongOrNull(8), c.getStringOrNull(9), c.getStringOrNull(10))
    }

    private fun readMessage(c: Cursor): StoredMessage = StoredMessage(
        conversationId=c.getString(c.getColumnIndexOrThrow("conversation_id")), messageId=c.getString(c.getColumnIndexOrThrow("message_id")),
        stableId=c.getString(c.getColumnIndexOrThrow("stable_id")), seq=c.getLong(c.getColumnIndexOrThrow("seq")), messageIndex=c.getLong(c.getColumnIndexOrThrow("message_index")),
        role=c.getString(c.getColumnIndexOrThrow("role")), status=c.getStringOrNull("status"), runId=c.getStringOrNull("run_id"),
        ownerUserMessageId=c.getStringOrNull("owner_user_message_id"), durationMillis=c.getLongOrNull("duration_ms"), beforeMessageId=c.getStringOrNull("before_message_id"),
        proactiveThreadKey=c.getStringOrNull("proactive_thread_key"), proactiveReplyToMessageId=c.getStringOrNull("proactive_reply_to_message_id"),
        proactiveDecisionId=c.getStringOrNull("proactive_decision_id"), interruptRequestId=c.getStringOrNull("interrupt_request_id"),
        acknowledgementRequired=c.getInt(c.getColumnIndexOrThrow("ack_required"))!=0, createdAtSeconds=c.getDouble(c.getColumnIndexOrThrow("created_at")),
        updatedAtSeconds=c.getDouble(c.getColumnIndexOrThrow("updated_at")), hiddenKind=c.getStringOrNull("hidden_kind"), hiddenSeq=c.getLongOrNull("hidden_seq"),
        translationHiddenSeq=c.getLongOrNull("translation_hidden_seq"), suggestedRepliesConsumedSeq=c.getLongOrNull("suggested_replies_consumed_seq"),
        inputState=c.getStringOrNull("input_state"), inputStateSeq=c.getLongOrNull("input_state_seq"),
    )

    private fun readItem(c: Cursor): ConversationItem = ConversationItem(
        c.getString(c.getColumnIndexOrThrow("conversation_id")), c.getLong(c.getColumnIndexOrThrow("seq")), c.getString(c.getColumnIndexOrThrow("item_kind")), c.getString(c.getColumnIndexOrThrow("item_key")),
        c.getLong(c.getColumnIndexOrThrow("sort_seq")), c.getInt(c.getColumnIndexOrThrow("sort_sub")), c.getLongOrNull("turn_anchor_seq"), c.getDouble(c.getColumnIndexOrThrow("created_at")),
    )

    private fun readInterrupt(c: Cursor): InterruptRequestValue {
        val conversationId = c.getString(c.getColumnIndexOrThrow("conversation_id")); val requestId = c.getString(c.getColumnIndexOrThrow("request_id"))
        val skills = sql.query("SELECT skill_id FROM interrupt_request_skills WHERE conversation_id=? AND request_id=? ORDER BY ordinal", arrayOf(conversationId, requestId)).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
        return InterruptRequestValue(conversationId, requestId, c.getLong(c.getColumnIndexOrThrow("seq")), c.getString(c.getColumnIndexOrThrow("content")), c.getInt(c.getColumnIndexOrThrow("memory_disabled"))!=0, skills,
            c.getStringOrNull("dispatched_message_id"), c.getLongOrNull("dispatched_seq"), c.getStringOrNull("input_state"), c.getLongOrNull("input_state_seq"))
    }

    private suspend fun <T> transactionResult(block: suspend () -> ChatDbResult<T>): ChatDbResult<T> = try {
        database.withTransaction { block() }
    } catch (abort: RepositoryAbort) {
        when (abort.reason) {
            "conversation_not_found" -> ChatDbResult.NotFound
            "conversation_tombstoned" -> ChatDbResult.Tombstoned
            else -> ChatDbResult.Conflict(abort.reason)
        }
    }

    private fun missingConversation(id: String): ChatDbResult<Nothing> = if (isTombstonedRaw(id)) ChatDbResult.Tombstoned else ChatDbResult.NotFound

    private class RepositoryAbort(val reason: String) : RuntimeException(reason)

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val INPUT_INCORPORATED = "incorporated"
        const val INPUT_RESOLVED = "resolved"
        const val RUN_PREPARING = "preparing"
        const val RUN_REQUESTING = "requesting"
        const val RUN_STREAMING = "streaming"
        const val RUN_WAITING_TOOL = "waiting_tool"
        const val RUN_COMPLETED = "completed"
        const val RUN_FAILED = "failed"
        const val RUN_INTERRUPTED = "interrupted"
        private const val ITEM_MESSAGE = "message"
        private const val ITEM_TRANSLATION = "assistant_translation"
        private const val ITEM_REACTION = "message_reaction"
        private const val ITEM_PROACTIVE_REPLY_USED = "proactive_reply_used"
        private const val ITEM_INTERRUPT_REQUEST = "main_interrupt_request"
        private const val ITEM_DECISION_REQUEST = "user_decision_request"
        private const val ITEM_DECISION_RESOLUTION = "user_decision_resolution"
        private const val NO_INPUT_STATE_ROW = "\u0001"
        private val DEFAULT_EXCLUDED_KINDS = setOf("proactive", "tasks", "central", "workspace_exec")
        private fun activeTargetKey(conversationId: String, messageId: String) = "${conversationId.length}:$conversationId${messageId.length}:$messageId"
        private fun isValidConversationId(value: String) = value.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && value != "." && value != ".."
        private fun epochSeconds(): Double = System.currentTimeMillis() / 1000.0
        private fun Boolean.asSqlInt() = if (this) 1 else 0
        private fun String?.isTerminalStatus() = this?.uppercase() in setOf("COMPLETED", "FAILED", "PARTIAL", "STOPPED", "INTERRUPTED")
        private fun String?.isStreamingStatus() = this?.uppercase() in setOf("STREAMING", "IN_PROGRESS")
        private fun String.isActiveRunState() = this in setOf(RUN_PREPARING, RUN_REQUESTING, RUN_STREAMING, RUN_WAITING_TOOL)
        private fun String.isTerminalRunState() = this in setOf("completed", "failed", "interrupted", "cancelled", "stopped")

        /** Legacy identity for `role=user`/`role=assistant` rows read by `LocalRunStore.java:readConversationMessages`, following `MessageReactionTool.java:stableMessageId` when `messageId` is absent. */
        @JvmStatic fun legacyStableMessageId(sessionId: String, messageIndex: Long): String {
            val bytes = "$sessionId\u0000$messageIndex".toByteArray(StandardCharsets.UTF_8)
            return "legacy-${UUID.nameUUIDFromBytes(bytes)}"
        }
    }
}

data class DecisionOptionInput(val id: String, val label: String, val description: String? = null, val role: String? = null)

private fun Cursor.getStringOrNull(name: String): String? = getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getString(it) }
private fun Cursor.getLongOrNull(name: String): Long? = getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getLong(it) }
private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
private fun Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
private fun Cursor.getColumnIndexOrThrow(name: String): Int = getColumnIndex(name).also { check(it >= 0) { "Missing query column: $name" } }

