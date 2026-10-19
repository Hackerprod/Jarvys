package com.jarvys.agent.chatdb

import android.content.Context
import android.database.Cursor
import android.os.Looper
import com.jarvys.agent.ConversationMetadataAccess
import com.jarvys.agent.ConversationTitle
import com.jarvys.agent.ConversationInput
import com.jarvys.agent.MessageReactionEmoji
import com.jarvys.agent.UserDecisionOption
import com.jarvys.agent.UserDecisionRole
import com.jarvys.agent.proactive.ProactiveReplyClaim
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

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

data class AppendMessageInput @JvmOverloads constructor(
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
    /** Kind used when this first message creates a new conversation in the same transaction. */
    val conversationKind: String = "chat",
    /** Optional stable source identity for idempotent delivery of an external conversation input. */
    val stableId: String? = null,
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

/** A hydrated message together with its globally sorted ledger position. */
data class ConversationMessageAt(val message: StoredMessage, val sortSeq: Long, val sortSub: Int)

/** User prompt paired with a regeneration marker that was durably appended in the same transaction. */
data class RegenerationPromptValue(val text: String, val userMessageId: String)

/** Marker identity and assistant owner captured together by one writer transaction. */
data class AssistantMarkerValue(val seq: Long, val ownerUserMessageId: String?)

data class MessageWindow(val conversationId: String, val slots: List<MessageSlot>, val olderCursorSeq: Long?)

data class MessageCounts(val total: Long, val users: Long)

data class ConversationDecisionValue(
    val conversationId: String,
    val decisionId: String,
    val seq: Long,
    val title: String,
    val body: String,
    val allowDismiss: Boolean,
    val status: String,
    val optionId: String?,
    val optionLabel: String?,
    val createdAtSeconds: Double,
    val options: List<UserDecisionOption>,
)

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

/** Metadata lookup retaining deletion state after the conversation row has been cascaded away. */
data class ConversationMetadataValue(
    val id: String,
    val kind: String?,
    val title: String?,
    val generatedTitle: String?,
    val pinned: Boolean,
    val archived: Boolean,
    val privateCode: Boolean,
    val deleted: Boolean,
    val createdAtMillis: Long?,
    val updatedAtMillis: Long?,
    val deletedAtMillis: Long?,
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
    val alreadyDispatched: Boolean = dispatchedSeq != null,
    val acknowledgementRequired: Boolean = false,
)

/** One authoritative projection of pending interrupt receipts, shared by the repository and timeline. */
private const val MAX_PENDING_INTERRUPT_SKILL_BIND_PARAMETERS = 500

internal fun readPendingInterruptRows(sql: ChatSqlDatabase, conversationId: String? = null): List<InterruptRequestValue> {
    val scope = if (conversationId == null) "" else "AND r.conversation_id=?"
    val args = if (conversationId == null) emptyArray() else arrayOf<Any?>(conversationId)
    val pending = sql.query(
        "SELECT r.conversation_id,r.request_id,r.seq,r.content,r.memory_disabled," +
            "CASE WHEN r.dispatched_seq<=c.next_seq-1 THEN r.dispatched_message_id END AS dispatched_message_id," +
            "CASE WHEN r.dispatched_seq<=c.next_seq-1 THEN r.dispatched_seq END AS dispatched_seq," +
            "CASE WHEN r.input_state_seq<=c.next_seq-1 THEN r.input_state END AS input_state," +
            "CASE WHEN r.input_state_seq<=c.next_seq-1 THEN r.input_state_seq END AS input_state_seq," +
            "CASE WHEN r.dispatched_seq<=c.next_seq-1 THEN COALESCE(m.ack_required,0) ELSE 0 END AS ack_required " +
            "FROM interrupt_requests r JOIN conversations c ON c.id=r.conversation_id " +
            "LEFT JOIN messages m ON m.conversation_id=r.conversation_id AND m.message_id=r.dispatched_message_id " +
            "WHERE r.seq<=c.next_seq-1 $scope ORDER BY r.conversation_id,r.seq",
        args,
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) {
            val state = cursor.getStringOrNull("input_state")
            val dispatchedSeq = cursor.getLongOrNull("dispatched_seq")
            val ackRequired = cursor.getInt(cursor.getColumnIndexOrThrow("ack_required")) != 0
            // input_state stores the CHECK-constrained values "resolved" / "incorporated";
            // those are the same states represented by the legacy marker rows.
            if (state == ConversationRepository.INPUT_RESOLVED || state == ConversationRepository.INPUT_INCORPORATED) continue
            if (dispatchedSeq != null && !ackRequired) continue
            add(InterruptRequestValue(
                conversationId = cursor.getString(0), requestId = cursor.getString(1), seq = cursor.getLong(2),
                content = cursor.getString(3), memoryDisabled = cursor.getInt(4) != 0, skills = emptyList(),
                dispatchedMessageId = cursor.getStringOrNull("dispatched_message_id"), dispatchedSeq = dispatchedSeq,
                inputState = state, inputStateSeq = cursor.getLongOrNull("input_state_seq"),
                alreadyDispatched = dispatchedSeq != null, acknowledgementRequired = ackRequired,
            ))
        }
    } }
    if (pending.isEmpty()) return emptyList()
    val skills = mutableMapOf<Pair<String, String>, MutableList<String>>()
    pending.map { it.conversationId to it.requestId }.distinct().chunked(MAX_PENDING_INTERRUPT_SKILL_BIND_PARAMETERS / 2).forEach { pairs ->
        val requested = pairs.joinToString(",") { "(?,?)" }
        val args = pairs.flatMap { listOf(it.first, it.second) }.toTypedArray()
        sql.query(
            "WITH requested(conversation_id,request_id) AS (VALUES $requested) " +
                "SELECT s.conversation_id,s.request_id,s.skill_id FROM interrupt_request_skills s " +
                "JOIN requested r ON r.conversation_id=s.conversation_id AND r.request_id=s.request_id " +
                "ORDER BY s.conversation_id,s.request_id,s.ordinal",
            args,
        ).use { cursor -> while (cursor.moveToNext()) {
            skills.getOrPut(cursor.getString(0) to cursor.getString(1)) { mutableListOf() }.add(cursor.getString(2))
        } }
    }
    return pending.map { it.copy(skills = skills[it.conversationId to it.requestId].orEmpty()) }
}

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
 * transaction executor serializes mutations; [withChatWriteTransaction] keeps sequence assignment, spine
 * insertion, and all typed child writes atomic.
 */
class ConversationRepository internal constructor(private val database: JarvysChatDatabase) {
    /** Opens the process database through the app's guarded provider; call from a background context. */
    constructor(context: Context) : this(JarvysChatDatabaseProvider.getInstance(context))

    private val sql get() = database.chatSql

    /** Returns the durable interrupt request identity, including resolved/dispatched state. */
    suspend fun readInterruptRequest(conversationId: String, requestId: String): InterruptRequestValue? =
        JarvysChatDatabaseProvider.onQueryExecutor(database) {
            database.withChatReadTransaction { findInterrupt(conversationId, requestId) }
        }

    /** Reads all live metadata columns for one conversation; tombstoned IDs return null. */
    suspend fun readConversation(conversationId: String): Conversation? =
        JarvysChatDatabaseProvider.onQueryExecutor(database) {
            database.withChatReadTransaction { findConversation(conversationId) }
        }

    /** Reads live metadata or the immutable metadata snapshot retained by a conversation tombstone. */
    suspend fun readConversationMetadata(conversationId: String): ConversationMetadataValue? =
        JarvysChatDatabaseProvider.onQueryExecutor(database) {
            database.withChatReadTransaction {
                val live = findConversation(conversationId)
                if (live != null) return@withChatReadTransaction ConversationMetadataValue(
                    live.id, live.kind, live.title, live.generated_title, live.pinned, live.archived,
                    live.private_code, false, live.created_at, live.updated_at, null,
                )
                sql.query(
                    "SELECT deleted_at,payload FROM tombstones WHERE scope='conversation' AND entity_id=?",
                    arrayOf(conversationId),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) return@withChatReadTransaction null
                    val payload = cursor.getStringOrNull(1)?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                    ConversationMetadataValue(
                        conversationId,
                        payload?.optString("kind")?.takeIf(String::isNotEmpty),
                        payload?.optString("title")?.takeIf(String::isNotEmpty),
                        payload?.optString("generated_title")?.takeIf(String::isNotEmpty),
                        payload?.optBoolean("pinned") ?: false,
                        payload?.optBoolean("archived") ?: false,
                        payload?.optBoolean("private_code") ?: false,
                        true,
                        payload?.optLong("created_at")?.takeIf { it > 0L },
                        payload?.optLong("updated_at")?.takeIf { it > 0L },
                        cursor.getLong(0),
                    )
                }
            }
        }

    internal suspend fun readConversationKind(conversationId: String): String? =
        JarvysChatDatabaseProvider.onQueryExecutor(database) {
            database.withChatReadTransaction {
                sql.query("SELECT kind FROM conversations WHERE id=?", arrayOf(conversationId)).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }
        }

    private fun maxSeq(conversationId: String): Long = sql.query(
        "SELECT next_seq-1 FROM conversations WHERE id=?", arrayOf(conversationId),
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /**
     * Idempotent counterpart of the implicit conversation creation in
     * `role=user`/`role=assistant` rows (`LocalRunStore.java:appendConversationMessage`); ID validation
     * matches the existing `ConversationMetadataAccess.validateSessionId` contract. A tombstone
     * always wins and an existing conversation retains its original kind and creation time.
     */
    suspend fun ensureConversation(id: String, kind: String = "chat"): ChatDbResult<Conversation> =
        transactionResult { ensureConversationInTransaction(id, kind) }

    /** Shared by first-write append operations so row creation and the ledger append stay atomic. */
    private fun ensureConversationInTransaction(id: String, kind: String): ChatDbResult<Conversation> {
        if (!isValidConversationId(id) || kind.isBlank()) return ChatDbResult.Invalid("conversation id and kind are required")
        if (isTombstonedRaw(id)) return ChatDbResult.Tombstoned
        val existing = findConversation(id)
        if (existing != null) return ChatDbResult.Success(existing)
        val now = System.currentTimeMillis()
        sql.execSQL(
            "INSERT INTO conversations(id,kind,pinned,archived,private_code,created_at,updated_at,message_count,next_seq) " +
                "SELECT ?,?,0,0,0,?,?,0,1 WHERE NOT EXISTS(SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=?)",
            arrayOf(id, kind, now, now, id),
        )
        return findConversation(id)?.let { ChatDbResult.Success(it) }
            ?: if (isTombstonedRaw(id)) ChatDbResult.Tombstoned
            else ChatDbResult.Conflict("conversation insert lost a concurrent race")
    }

    private fun ensureFailure(result: ChatDbResult<Conversation>): ChatDbResult<Nothing> = when (result) {
        is ChatDbResult.Success -> error("ensureFailure called for a successful conversation")
        ChatDbResult.NotFound -> ChatDbResult.NotFound
        ChatDbResult.Tombstoned -> ChatDbResult.Tombstoned
        is ChatDbResult.Conflict -> ChatDbResult.Conflict(result.reason)
        is ChatDbResult.Invalid -> ChatDbResult.Invalid(result.reason)
    }

    /**
     * Provides title/pinned/archived/privateCode updates formerly handled by the file-backed store (`LocalRunStore.java:renameConversation`,
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
        if (!isValidConversationId(conversationId)) return@transactionResult ChatDbResult.Invalid("conversation id is invalid")
        if (isTombstonedRaw(conversationId)) return@transactionResult ChatDbResult.Tombstoned
        var current = findConversation(conversationId)
        if (current == null) {
            val now = System.currentTimeMillis()
            sql.execSQL(
                "INSERT INTO conversations(id,kind,pinned,archived,private_code,created_at,updated_at,message_count,next_seq) " +
                    "SELECT ?,'chat',0,0,0,?,?,0,1 WHERE NOT EXISTS(SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=?)",
                arrayOf(conversationId, now, now, conversationId),
            )
            current = findConversation(conversationId) ?: return@transactionResult missingConversation(conversationId)
        }
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

    /** Mirrors `LocalRunStore.appendConversationTitleIfAbsent`: true only when this title wins first place. */
    suspend fun appendConversationTitleIfAbsent(conversationId: String, title: String?): ChatDbResult<Boolean> = transactionResult {
        val normalized = title?.trim()?.takeIf(String::isNotEmpty)
            ?: return@transactionResult ChatDbResult.Success(false)
        if (!isValidConversationId(conversationId)) return@transactionResult ChatDbResult.Invalid("conversation id is invalid")
        if (isTombstonedRaw(conversationId)) return@transactionResult ChatDbResult.Tombstoned
        if (findConversation(conversationId) == null) {
            val now = System.currentTimeMillis()
            sql.execSQL(
                "INSERT INTO conversations(id,kind,pinned,archived,private_code,created_at,updated_at,message_count,next_seq) " +
                    "SELECT ?,'chat',0,0,0,?,?,0,1 WHERE NOT EXISTS(SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=?)",
                arrayOf(conversationId, now, now, conversationId),
            )
        }
        sql.execSQL(
            "UPDATE conversations SET generated_title=?,updated_at=? WHERE id=? AND (title IS NULL OR TRIM(title)='') AND (generated_title IS NULL OR TRIM(generated_title)='')",
            arrayOf(normalized, System.currentTimeMillis(), conversationId),
        )
        val inserted = sql.query("SELECT changes()").use { c -> c.moveToFirst(); c.getInt(0) != 0 }
        ChatDbResult.Success(inserted)
    }

    /** Updates conversation metadata in Room, replacing `LocalRunStore.java:renameConversation`. */
    suspend fun renameConversation(conversationId: String, title: String?): ChatDbResult<Conversation> = transactionResult {
        if (findConversation(conversationId) == null) return@transactionResult missingConversation(conversationId)
        val cleanTitle = title?.let(::normalizeManualTitle)
            ?: return@transactionResult ChatDbResult.Invalid("a non-empty conversation title is required")
        sql.execSQL("UPDATE conversations SET title=?,updated_at=? WHERE id=?", arrayOf(cleanTitle, System.currentTimeMillis(), conversationId))
        ChatDbResult.Success(findConversation(conversationId)!!)
    }

    /**
     * Replaces `LocalRunStore.java:deleteConversation`: preserve a
     * tombstone even when the ledger did not yet contain a conversation, then let FK CASCADE clear it.
     */
    suspend fun deleteConversation(conversationId: String, deletedAtMillis: Long = System.currentTimeMillis()): ChatDbResult<Boolean> =
        transactionResult {
            if (conversationId.isBlank()) return@transactionResult ChatDbResult.Invalid("conversation id is required")
            val current = findConversation(conversationId)
            val metadataPayload = current?.let {
                JSONObject()
                    .put("kind", it.kind)
                    .put("title", it.title)
                    .put("generated_title", it.generated_title)
                    .put("pinned", it.pinned)
                    .put("archived", it.archived)
                    .put("private_code", it.private_code)
                    .put("created_at", it.created_at)
                    .put("updated_at", it.updated_at)
                    .toString()
            }
            sql.execSQL(
                "INSERT INTO tombstones(scope,entity_id,deleted_at,payload) " +
                    "SELECT 'conversation',?,?,? WHERE NOT EXISTS(" +
                    "SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=?)",
                arrayOf(conversationId, deletedAtMillis, metadataPayload, conversationId),
            )
            val existed = current != null
            sql.execSQL("DELETE FROM conversations WHERE id=?", arrayOf(conversationId))
            ChatDbResult.Success(existed)
        }

    /** Reads tombstone state replacing the deletion check through `LocalRunStore.java:readConversationMetadata`. */
    suspend fun isTombstoned(conversationId: String): Boolean = JarvysChatDatabaseProvider.onQueryExecutor(database) {
        database.withChatReadTransaction {
            sql.query("SELECT EXISTS(SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=?)", arrayOf(conversationId))
                .use { it.moveToFirst(); it.getInt(0) != 0 }
        }
    }

    /**
     * Appends one conversation row and its body/attachment references. The
     * generated UUID is the default `stable_id`; internal inputs may supply a source-derived id,
     * replacing `role=user`/`role=assistant` in
     * `LocalRunStore.java:appendConversationMessage`.
     */
    suspend fun appendMessage(input: AppendMessageInput): ChatDbResult<StoredMessage> = transactionResult {
        validateMessageInput(input)?.let { return@transactionResult ChatDbResult.Invalid(it) }
        if (input.conversationKind.isBlank()) return@transactionResult ChatDbResult.Invalid("conversation kind is required")
        var conversation = findConversation(input.conversationId)
        if (conversation == null) {
            if (isTombstonedRaw(input.conversationId)) return@transactionResult ChatDbResult.Tombstoned
            val nowMillis = System.currentTimeMillis()
            sql.execSQL(
                "INSERT INTO conversations(id,kind,pinned,archived,private_code,created_at,updated_at,message_count,next_seq) " +
                    "SELECT ?,?,0,0,0,?,?,0,1 WHERE NOT EXISTS(SELECT 1 FROM conversations WHERE id=?)",
                arrayOf(input.conversationId, input.conversationKind, nowMillis, nowMillis, input.conversationId),
            )
            conversation = findConversation(input.conversationId)
        }
        conversation = conversation ?: return@transactionResult missingConversation(input.conversationId)
        if (isTombstonedRaw(input.conversationId)) return@transactionResult ChatDbResult.Tombstoned
        val suppliedStableId = input.stableId?.takeIf { it.isNotBlank() }
        if (suppliedStableId != null) {
            val identity = findMessageByStableId(input.conversationId, suppliedStableId)
            if (identity != null) {
                val existing = hydrateMessages(input.conversationId, listOf(identity.messageId)).singleOrNull()
                    ?: return@transactionResult ChatDbResult.Conflict("stable input row could not be hydrated")
                val expectedParts = partsWithAttachmentReferences(input.parts, input.attachments)
                val roleMatches = existing.role == input.role
                val partsMatch = existing.parts == expectedParts
                val attachmentsMatch = existing.attachments == input.attachments
                if (roleMatches && partsMatch && attachmentsMatch) return@transactionResult ChatDbResult.Success(existing)
                return@transactionResult ChatDbResult.Conflict("stable input identity was already used with different content")
            }
        }
        if (input.attachments.isNotEmpty() && (!isOrdinaryChat(conversation.kind) || !input.proactiveThreadKey.isNullOrEmpty())) {
            return@transactionResult ChatDbResult.Invalid("attachments are supported only in ordinary user messages")
        }
        if (input.proactiveDecisionId != null) {
            val existing = findMessageByDecision(input.conversationId, input.proactiveDecisionId)
            if (existing != null) return@transactionResult ChatDbResult.Success(hydrateMessages(input.conversationId, listOf(existing.messageId)).single())
        }
        val id = UUID.randomUUID().toString()
        val stableId = suppliedStableId ?: id
        val now = input.createdAtSeconds ?: epochSeconds()
        val updatedAt = now
        val seq = allocateSequence(input.conversationId)
        val index = messageIndexFor(input.conversationId, input.beforeMessageId)
        val sort = sortPositionFor(input.conversationId, seq, input.beforeMessageId)
        val anchor = if (input.role == ROLE_USER && input.interruptRequestId == null) seq else latestTurnAnchor(input.conversationId)
        insertItem(input.conversationId, seq, ITEM_MESSAGE, id, sort.first, sort.second, anchor, now)
        sql.execSQL(
            "INSERT INTO messages(conversation_id,message_id,stable_id,seq,message_index,role,status,run_id," +
                "owner_user_message_id,duration_ms,before_message_id,proactive_thread_key,proactive_reply_to_message_id," +
                "proactive_decision_id,interrupt_request_id,ack_required,created_at,updated_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(input.conversationId, id, stableId, seq, index, input.role, input.status, input.runId,
                input.ownerUserMessageId, input.durationMillis?.takeIf { it > 0 }, input.beforeMessageId, input.proactiveThreadKey,
                input.proactiveReplyToMessageId, input.proactiveDecisionId, input.interruptRequestId,
                input.acknowledgementRequired.asSqlInt(), now, updatedAt),
        )
        replaceParts(input.conversationId, id, partsWithAttachmentReferences(input.parts, input.attachments))
        insertAttachments(input.conversationId, id, input.attachments, now)
        insertSuggestedReplies(input.conversationId, id, input.suggestedReplies)
        if (input.role == ROLE_USER && !input.interruptRequestId.isNullOrBlank() && !input.acknowledgementRequired) {
            resolveUnacknowledgedInterruptInput(input.conversationId, id, input.interruptRequestId)
        }
        if (input.role == ROLE_ASSISTANT) {
            resolveOwnedInputAfterFailure(input.conversationId, input.ownerUserMessageId, input.status)
        }
        updateConversationMessageCounters(input.conversationId, now)
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
    ): ChatDbResult<StoredMessage> = appendProactiveAssistantMessageInternal(conversationId, decisionId, content, threadKey, replies)

    private suspend fun appendProactiveAssistantMessageInternal(
        conversationId: String,
        decisionId: String,
        content: String,
        threadKey: String?,
        replies: List<SuggestedReplyInput>,
    ): ChatDbResult<StoredMessage> = transactionResult {
        if (decisionId.isBlank()) return@transactionResult ChatDbResult.Invalid("decision id is required")
        if (isTombstonedRaw(conversationId)) return@transactionResult ChatDbResult.Tombstoned
        if (findConversation(conversationId) == null) {
            val nowMillis = System.currentTimeMillis()
            sql.execSQL(
                "INSERT INTO conversations(id,kind,pinned,archived,private_code,created_at,updated_at,message_count,next_seq) " +
                    "SELECT ?,'proactive',0,0,0,?,?,0,1 WHERE NOT EXISTS(" +
                    "SELECT 1 FROM conversations WHERE id=? OR EXISTS(" +
                    "SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=?))",
                arrayOf(conversationId, nowMillis, nowMillis, conversationId, conversationId),
            )
        }
        if (findConversation(conversationId) == null) return@transactionResult missingConversation(conversationId)
        val existing = findMessageByDecision(conversationId, decisionId)
        if (existing != null) return@transactionResult ChatDbResult.Success(hydrateMessages(conversationId, listOf(existing.messageId)).single())
        val id = UUID.randomUUID().toString()
        if (findMessage(conversationId, id) != null) return@transactionResult ChatDbResult.Conflict("proactive message id already exists")
        val stableId = id
        val now = epochSeconds()
        val updatedAt = now
        val seq = allocateSequence(conversationId)
        val sort = sortPositionFor(conversationId, seq, null)
        insertItem(conversationId, seq, ITEM_MESSAGE, id, sort.first, sort.second, latestTurnAnchor(conversationId), now)
        sql.execSQL(
            "INSERT INTO messages(conversation_id,message_id,stable_id,seq,message_index,role,status,proactive_thread_key," +
                "proactive_decision_id,ack_required,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,0,?,?)",
            arrayOf(conversationId, id, stableId, seq, nextMessageIndex(conversationId), ROLE_ASSISTANT, "COMPLETED", threadKey, decisionId, now, updatedAt),
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
        resolveOwnedInputAfterFailure(conversationId, current.ownerUserMessageId, finalStatus)
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

    /** Appends one hide marker and returns the assistant owner read under that same transaction. */
    suspend fun hideAssistantWithOwner(
        conversationId: String,
        messageId: String,
        kind: String,
    ): ChatDbResult<AssistantMarkerValue> = transactionResult {
        if (kind !in setOf("assistant_regenerated", "assistant_deleted"))
            return@transactionResult ChatDbResult.Invalid("unknown assistant marker")
        if (findConversation(conversationId) == null) return@transactionResult missingConversation(conversationId)
        val target = findMessage(conversationId, messageId) ?: return@transactionResult ChatDbResult.NotFound
        if (target.role != ROLE_ASSISTANT) return@transactionResult ChatDbResult.Invalid("assistant marker target must be an assistant message")
        val seq = allocateSequence(conversationId)
        val now = epochSeconds()
        insertItem(conversationId, seq, kind, messageId, seq, 0, latestTurnAnchor(conversationId), now)
        sql.execSQL(
            "UPDATE messages SET hidden_kind=?,hidden_seq=? WHERE conversation_id=? AND message_id=?",
            arrayOf(kind, seq, conversationId, messageId),
        )
        ChatDbResult.Success(AssistantMarkerValue(seq, target.ownerUserMessageId))
    }

    /**
     * Atomically verifies the target is the last visible assistant, resolves its user prompt, and
     * appends `assistant_regenerated`. A successful null means the target was already hidden or a
     * later active assistant won the race. The marker changes visibility only; status and body are
     * retained exactly as for the standalone hide marker.
     */
    suspend fun regenerateIfLatestAssistant(
        conversationId: String,
        messageId: String,
    ): ChatDbResult<RegenerationPromptValue?> = transactionResult {
        if (findConversation(conversationId) == null) return@transactionResult missingConversation(conversationId)
        val target = findMessage(conversationId, messageId) ?: return@transactionResult ChatDbResult.NotFound
        if (target.role != ROLE_ASSISTANT) return@transactionResult ChatDbResult.Invalid("regeneration target must be an assistant message")
        if (target.hiddenKind != null) return@transactionResult ChatDbResult.Success(null)
        val explicitOwnerId = target.ownerUserMessageId
        val explicitOwner = explicitOwnerId?.takeIf { it.isNotBlank() }
            ?.let { findMessage(conversationId, it) }
        if (!explicitOwnerId.isNullOrBlank() && explicitOwner?.role != ROLE_USER)
            return@transactionResult ChatDbResult.Invalid("assistant replies owned by internal inputs cannot be regenerated")

        val latestActiveAssistantId = sql.query(
            "SELECT m.message_id FROM messages m " +
                "JOIN conversation_items i ON i.conversation_id=m.conversation_id AND i.seq=m.seq " +
                "WHERE m.conversation_id=? AND m.role='assistant' AND m.hidden_kind IS NULL " +
                "ORDER BY i.sort_seq DESC,i.sort_sub DESC,i.seq DESC LIMIT 1",
            arrayOf(conversationId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (latestActiveAssistantId != messageId) return@transactionResult ChatDbResult.Success(null)

        val targetPosition = sql.query(
            "SELECT sort_seq,sort_sub,seq FROM conversation_items WHERE conversation_id=? AND seq=?",
            arrayOf(conversationId, target.seq),
        ).use { cursor ->
            if (cursor.moveToFirst()) SortPosition(cursor.getLong(0), cursor.getInt(1), cursor.getLong(2)) else null
        } ?: return@transactionResult ChatDbResult.NotFound

        val owner = explicitOwner?.takeIf { it.role == ROLE_USER } ?: if (explicitOwnerId.isNullOrBlank()) sql.query(
            "SELECT m.message_id FROM messages m " +
                "JOIN conversation_items i ON i.conversation_id=m.conversation_id AND i.seq=m.seq " +
                "WHERE m.conversation_id=? AND m.role='user' AND " +
                "(i.sort_seq<? OR (i.sort_seq=? AND i.sort_sub<?) OR " +
                "(i.sort_seq=? AND i.sort_sub=? AND i.seq<?)) " +
                "ORDER BY i.sort_seq DESC,i.sort_sub DESC,i.seq DESC LIMIT 1",
            arrayOf(conversationId, targetPosition.sortSeq, targetPosition.sortSeq, targetPosition.sortSub,
                targetPosition.sortSeq, targetPosition.sortSub, targetPosition.seq),
        ).use { cursor ->
            if (cursor.moveToFirst()) findMessage(conversationId, cursor.getString(0)) else null
        } else null
        if (owner == null) return@transactionResult ChatDbResult.NotFound
        val prompt = hydrateMessages(conversationId, listOf(owner.messageId)).singleOrNull()
            ?: return@transactionResult ChatDbResult.NotFound

        val seq = allocateSequence(conversationId)
        val now = epochSeconds()
        insertItem(conversationId, seq, "assistant_regenerated", messageId, seq, 0, latestTurnAnchor(conversationId), now)
        sql.execSQL(
            "UPDATE messages SET hidden_kind='assistant_regenerated',hidden_seq=? WHERE conversation_id=? AND message_id=? AND hidden_kind IS NULL",
            arrayOf(seq, conversationId, messageId),
        )
        ChatDbResult.Success(RegenerationPromptValue(prompt.text, prompt.messageId))
    }

    /** Stores the positional `assistant_translation_hidden` marker, replacing `LocalRunStore.java:appendAssistantTranslationHidden`. */
    suspend fun hideTranslation(conversationId: String, messageId: String): ChatDbResult<Long> =
        setMessageMarker(conversationId, messageId, "assistant_translation_hidden", "translation_hidden_seq", null)

    /** Stores the current `message_reaction` state; an empty emoji removes it as in `LocalRunStore.java:setMessageReaction`. */
    suspend fun setReaction(conversationId: String, stableMessageId: String, emoji: String): ChatDbResult<ReactionUpdate> = transactionResult {
        if (emoji.isNotEmpty() && !MessageReactionEmoji.isValid(emoji)) return@transactionResult ChatDbResult.Invalid("invalid reaction")
        val conversation = findConversation(conversationId) ?: return@transactionResult missingConversation(conversationId)
        val target = findMessageByStableId(conversationId, stableMessageId) ?: return@transactionResult ChatDbResult.NotFound
        if (!isOrdinaryChat(conversation.kind) || target.role != ROLE_USER || !target.proactiveThreadKey.isNullOrEmpty()) {
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

    /** Marks all suggestions consumed when an external caller does not need to claim one by index. */
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

    /** Replaces `LocalRunStore.claimProactiveSuggestedReply`, validating and consuming atomically. */
    suspend fun claimProactiveSuggestedReply(
        conversationId: String,
        messageId: String,
        index: Int,
    ): ChatDbResult<ProactiveReplyClaim?> = transactionResult {
        if (index < 0) return@transactionResult ChatDbResult.Success<ProactiveReplyClaim?>(null)
        if (findConversation(conversationId) == null) return@transactionResult ChatDbResult.Success<ProactiveReplyClaim?>(null)
        val message = findMessage(conversationId, messageId)
            ?: return@transactionResult ChatDbResult.Success<ProactiveReplyClaim?>(null)
        if (message.role != ROLE_ASSISTANT || message.proactiveDecisionId == null ||
            message.suggestedRepliesConsumedSeq != null) {
            return@transactionResult ChatDbResult.Success<ProactiveReplyClaim?>(null)
        }
        val selectedText = sql.query(
            "SELECT text FROM proactive_suggested_replies WHERE conversation_id=? AND message_id=? AND ordinal=?",
            arrayOf(conversationId, messageId, index),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).trim() else "" }
        val threadKey = message.proactiveThreadKey?.trim().orEmpty()
        if (selectedText.isEmpty() || threadKey.isEmpty())
            return@transactionResult ChatDbResult.Success<ProactiveReplyClaim?>(null)

        val seq = allocateSequence(conversationId)
        insertItem(conversationId, seq, ITEM_PROACTIVE_REPLY_USED, messageId, seq, 0, message.seq, epochSeconds())
        sql.execSQL(
            "UPDATE messages SET suggested_replies_consumed_seq=? WHERE conversation_id=? AND message_id=? AND suggested_replies_consumed_seq IS NULL",
            arrayOf(seq, conversationId, messageId),
        )
        ChatDbResult.Success(ProactiveReplyClaim(selectedText, threadKey, ""))
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
    ): ChatDbResult<InterruptRequestValue> = appendInterruptRequestInternal(conversationId, content, skills, memoryDisabled)

    private suspend fun appendInterruptRequestInternal(
        conversationId: String,
        content: String,
        skills: List<String>,
        memoryDisabled: Boolean,
    ): ChatDbResult<InterruptRequestValue> = transactionResult {
        if (content.isBlank() || content.length > 32000) return@transactionResult ChatDbResult.Invalid("interrupt content is invalid")
        val kind = findConversation(conversationId)?.kind ?: conversationKindForSession(conversationId)
        if (!isOrdinaryChat(kind)) return@transactionResult ChatDbResult.Invalid("interrupts require an ordinary chat")
        val ensured = ensureConversationInTransaction(conversationId, conversationKindForSession(conversationId))
        val conversation = (ensured as? ChatDbResult.Success)?.value
            ?: return@transactionResult ensureFailure(ensured)
        if (!isOrdinaryChat(conversation.kind)) return@transactionResult ChatDbResult.Invalid("interrupts require an ordinary chat")
        if (isTombstonedRaw(conversationId)) return@transactionResult ChatDbResult.Tombstoned
        val id = UUID.randomUUID().toString()
        val existing = findInterrupt(conversationId, id)
        if (existing != null) {
            val existingSkills = sql.query(
                "SELECT skill_id FROM interrupt_request_skills WHERE conversation_id=? AND request_id=? ORDER BY ordinal",
                arrayOf(conversationId, id),
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            return@transactionResult if (existing.content == content && existing.memoryDisabled == memoryDisabled && existingSkills == skills)
                ChatDbResult.Success(existing) else ChatDbResult.Conflict("interrupt request identity has different content")
        }
        if (findMessage(conversationId, id) != null) return@transactionResult ChatDbResult.Conflict("interrupt request id already belongs to a message")
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

    /**
     * Reproduces `LocalRunStore.readPendingInterruptRequests`: unresolved receipts are returned
     * before dispatch, and dispatched receipts remain recoverable until their required input
     * acknowledgement is incorporated. The read is not a write transaction; each conversation's
     * captured `next_seq-1` bounds rows and state markers so an active WAL writer cannot block it.
     */
    suspend fun pendingInterruptRequests(conversationId: String? = null): List<InterruptRequestValue> {
        return JarvysChatDatabaseProvider.onQueryExecutor(database) {
            database.withChatReadTransaction { readPendingInterruptRows(sql, conversationId) }
        }
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
            val requestState = sql.query(
                "SELECT input_state FROM interrupt_requests WHERE conversation_id=? AND request_id=?", arrayOf(conversationId, id),
            ).use { c -> if (c.moveToFirst()) c.getStringOrNull(0) else NO_INPUT_STATE_ROW }
            if (message == null && requestState == NO_INPUT_STATE_ROW) continue
            if (state == INPUT_INCORPORATED && (message == null || !isConversationInputRole(message.role))) continue
            if (message != null && (!isConversationInputRole(message.role) || message.inputState != null)) continue
            if (message == null && requestState != null) continue
            if (message != null && requestState != null && requestState != NO_INPUT_STATE_ROW) continue
            val seq = allocateSequence(conversationId)
            insertItem(conversationId, seq, itemKindForInputState(state), id, seq, 0, latestTurnAnchor(conversationId), epochSeconds())
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
        val ensured = ensureConversationInTransaction(conversationId, conversationKindForSession(conversationId))
        if (ensured !is ChatDbResult.Success) return@transactionResult ensureFailure(ensured)
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
        if (isTombstonedRaw(conversationId)) return@transactionResult ChatDbResult.Tombstoned
        if (decisionId.isBlank() || !findDecision(conversationId, decisionId)) return@transactionResult ChatDbResult.NotFound
        val seq = allocateSequence(conversationId)
        insertItem(conversationId, seq, ITEM_DECISION_RESOLUTION, decisionId, seq, 0, latestTurnAnchor(conversationId), epochSeconds())
        sql.execSQL("UPDATE user_decisions SET status=?,option_id=?,option_label=?,resolved_seq=? WHERE conversation_id=? AND decision_id=?", arrayOf(status, optionId, optionLabel, seq, conversationId, decisionId))
        ChatDbResult.Success(seq)
    }

    /** Reads `role=user`/`role=assistant` rows and normalized children, replacing `LocalRunStore.java:readConversationMessage`, `LocalRunStore.java:readConversationMessages`, `LocalRunStore.java:readConversationAttachments`, and `LocalRunStore.java:appendAssistantTranslation` projections. */
    suspend fun getMessage(conversationId: String, messageId: String): StoredMessage? = getMessagesByIds(conversationId, listOf(messageId)).firstOrNull()

    /** Indexed idempotency lookup for source-derived internal input identities. */
    suspend fun getMessageByStableId(conversationId: String, stableId: String): StoredMessage? =
        JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
            if (stableId.isBlank()) return@withChatReadTransaction null
            val id = sql.query(
                "SELECT message_id FROM messages WHERE conversation_id=? AND stable_id=? LIMIT 1",
                arrayOf(conversationId, stableId),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: return@withChatReadTransaction null
            hydrateMessages(conversationId, listOf(id)).singleOrNull()
        } }

    /**
     * Hydrates requested `role=user`/`role=assistant` identities in caller order. Body parts,
     * attachments, translations, and proactive replies are fetched in one UNION query, replacing
     * `LocalRunStore.java:readConversationMessages`, `LocalRunStore.java:readConversationMessage`,
     * and `LocalRunStore.java:readConversationAttachments` without N+1 hydration.
     */
    suspend fun getMessagesByIds(conversationId: String, messageIds: List<String>): List<StoredMessage> {
        return JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
        if (messageIds.isEmpty()) return@withChatReadTransaction emptyList()
        val highWater = maxSeq(conversationId)
        val byId = mutableMapOf<String, StoredMessage>()
        messageIds.distinct().chunked(MAX_SQL_BIND_PARAMETERS).forEach { ids ->
            val placeholders = ids.joinToString(",") { "(?)" }
            val baseSql = "WITH requested(message_id) AS (VALUES $placeholders) " +
                "SELECT m.* FROM messages m JOIN requested r ON r.message_id=m.message_id WHERE m.conversation_id=? AND m.seq<=?"
            sql.query(baseSql, arrayOf<Any?>(*ids.toTypedArray(), conversationId, highWater)).use { cursor -> while (cursor.moveToNext()) {
                val value = readMessage(cursor)
                byId[value.messageId] = value
            } }
        }
        if (byId.isEmpty()) return@withChatReadTransaction emptyList()
        val loaded = hydrateChildren(conversationId, byId, highWater)
        messageIds.mapNotNull(loaded::get)
        } }
    }

    /** Hydrates only pending AGENT/SYSTEM rows addressed by their source-derived stable IDs. */
    suspend fun getPendingInternalMessagesByStableIds(
        conversationId: String,
        stableIds: List<String>,
    ): List<StoredMessage> = JarvysChatDatabaseProvider.onQueryExecutor(database) {
        database.withChatReadTransaction {
            val requested = stableIds.filter(String::isNotBlank).distinct()
            if (requested.isEmpty()) return@withChatReadTransaction emptyList()
            val highWater = maxSeq(conversationId)
            val byMessageId = linkedMapOf<String, StoredMessage>()
            // Leave room for the fixed conversation and snapshot-sequence bind parameters.
            requested.chunked(MAX_SQL_BIND_PARAMETERS - 2).forEach { ids ->
                val placeholders = ids.joinToString(",") { "?" }
                val query = "SELECT * FROM messages WHERE conversation_id=? AND stable_id IN ($placeholders) " +
                    "AND role IN ('agent','system') AND input_state IS NULL AND seq<=?"
                val arguments = arrayOf<Any?>(conversationId, *ids.toTypedArray(), highWater)
                sql.query(query, arguments).use { cursor ->
                    while (cursor.moveToNext()) {
                        val message = readMessage(cursor)
                        byMessageId[message.messageId] = message
                    }
                }
            }
            if (byMessageId.isEmpty()) return@withChatReadTransaction emptyList()
            val hydrated = hydrateChildren(conversationId, byMessageId, highWater)
            val byStableId = byMessageId.values.associate { message ->
                message.stableId to requireNotNull(hydrated[message.messageId])
            }
            requested.mapNotNull(byStableId::get)
        }
    }

    /**
     * Reads all persisted user/assistant messages at or after a model message index, in the
     * conversation's steering-aware sort order. The returned body and attachment children are
     * hydrated in batches under the same read fence.
     */
    suspend fun readMessagesAtOrAfterIndex(conversationId: String, firstMessageIndex: Long = 0L): List<ConversationMessageAt> {
        require(firstMessageIndex >= 0L) { "firstMessageIndex must be non-negative" }
        return JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
            val highWater = maxSeq(conversationId)
            val positions = sql.query(
                "SELECT m.message_id,i.sort_seq,i.sort_sub FROM messages m " +
                    "JOIN conversation_items i ON i.conversation_id=m.conversation_id AND i.seq=m.seq " +
                    "WHERE m.conversation_id=? AND m.seq<=? AND m.message_index>=? " +
                    "ORDER BY i.sort_seq,i.sort_sub,i.seq",
                arrayOf(conversationId, highWater, firstMessageIndex),
            ).use { c -> buildList {
                while (c.moveToNext()) add(Triple(c.getString(0), c.getLong(1), c.getInt(2)))
            } }
            if (positions.isEmpty()) return@withChatReadTransaction emptyList()
            val hydrated = hydrateMessages(conversationId, positions.map { it.first }, highWater).associateBy { it.messageId }
            positions.mapNotNull { (id, sortSeq, sortSub) ->
                hydrated[id]?.let { ConversationMessageAt(it, sortSeq, sortSub) }
            }
        } }
    }

    /**
     * Reads the legacy `LocalRunStore.readConversationMessages` stream in physical ledger order.
     * This deliberately does not apply steering-aware `conversation_items.sort_*` ordering;
     * callers that build model/UI projections use [readMessagesAtOrAfterIndex] or a window API.
     */
    suspend fun readConversationMessages(conversationId: String): List<StoredMessage> =
        JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
            val highWater = maxSeq(conversationId)
            val ids = sql.query(
                "SELECT message_id FROM messages WHERE conversation_id=? AND seq<=? ORDER BY seq",
                arrayOf(conversationId, highWater),
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            hydrateMessages(conversationId, ids, highWater)
        } }

    /** Hydrates only unacknowledged AGENT inputs, using the existing conversation/role index. */
    suspend fun readPendingAgentInputs(conversationId: String): List<StoredMessage> =
        JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
            val highWater = maxSeq(conversationId)
            val ids = sql.query(
                "SELECT message_id FROM messages WHERE conversation_id=? AND role='agent' " +
                    "AND input_state IS NULL AND seq<=? ORDER BY seq",
                arrayOf(conversationId, highWater),
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            hydrateMessages(conversationId, ids, highWater)
        } }

    /**
     * Counts pending inputs for one visible workspace page without loading any conversation
     * transcript. Existing role/state fields and the conversation/role/sequence index bound the
     * scan; provenance payloads are the only message-part data read.
     */
    suspend fun countPendingAgentInputsByWorkspace(
        conversationWorkspaceIds: Map<String, String>,
    ): Map<String, Int> {
        if (conversationWorkspaceIds.isEmpty()) return emptyMap()
        val counts = linkedMapOf<String, Int>()
        val conversationIds = conversationWorkspaceIds.keys.distinct()
        conversationIds.chunked(MAX_SQL_BIND_PARAMETERS).forEach { chunk ->
            JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
                val placeholders = chunk.joinToString(",") { "?" }
                val query = "SELECT m.conversation_id,p.payload FROM messages m " +
                    "JOIN conversations c ON c.id=m.conversation_id " +
                    "LEFT JOIN message_parts p ON p.conversation_id=m.conversation_id " +
                    "AND p.message_id=m.message_id AND p.kind=? " +
                    "WHERE m.conversation_id IN ($placeholders) AND m.role='agent' " +
                    "AND m.input_state IS NULL AND m.seq<c.next_seq ORDER BY m.conversation_id,m.seq"
                val arguments = arrayOf<Any?>(ConversationInput.PROVENANCE_PART_KIND,
                    *chunk.toTypedArray())
                sql.query(query, arguments).use { cursor ->
                    while (cursor.moveToNext()) {
                        val conversationId = cursor.getString(0)
                        val encoded = cursor.getStringOrNull(1)
                        val workspaceId = encoded?.let { payload -> runCatching {
                            JSONObject(payload).optJSONObject("provenance")?.optString("workspaceId")
                                ?.takeIf(String::isNotBlank)
                        }.getOrNull() } ?: conversationWorkspaceIds[conversationId].orEmpty()
                        if (workspaceId.isNotBlank()) counts[workspaceId] = (counts[workspaceId] ?: 0) + 1
                    }
                }
            } }
        }
        return counts
    }

    /** Last-wins reaction projection keyed by the persisted stable message ID. */
    suspend fun readMessageReactions(conversationId: String): Map<String, String> =
        JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
            sql.query(
                "SELECT target_stable_id,emoji FROM message_reactions WHERE conversation_id=? ORDER BY seq",
                arrayOf(conversationId),
            ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }
        } }

    /** Final decision cards and latest resolution, with options hydrated in one indexed join. */
    suspend fun readDecisions(conversationId: String): List<ConversationDecisionValue> =
        JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
            val highWater = maxSeq(conversationId)
            sql.query(
                "SELECT d.decision_id,d.seq,d.title,d.body,d.allow_dismiss," +
                    "CASE WHEN d.resolved_seq IS NOT NULL AND d.resolved_seq<=? THEN d.status ELSE NULL END AS resolved_status," +
                    "CASE WHEN d.resolved_seq IS NOT NULL AND d.resolved_seq<=? THEN d.option_id ELSE NULL END AS resolved_option_id," +
                    "CASE WHEN d.resolved_seq IS NOT NULL AND d.resolved_seq<=? THEN d.option_label ELSE NULL END AS resolved_option_label," +
                    "d.created_at,o.option_id,o.label,o.description,o.role " +
                    "FROM user_decisions d LEFT JOIN user_decision_options o " +
                    "ON o.conversation_id=d.conversation_id AND o.decision_id=d.decision_id " +
                    "WHERE d.conversation_id=? AND d.seq<=? ORDER BY d.seq,o.ordinal",
                arrayOf(highWater, highWater, highWater, conversationId, highWater),
            ).use { c ->
                data class Pending(
                    val seq: Long,
                    val title: String,
                    val body: String,
                    val allowDismiss: Boolean,
                    val status: String?,
                    val optionId: String?,
                    val optionLabel: String?,
                    val createdAt: Double,
                    val options: MutableList<UserDecisionOption>,
                )
                val values = linkedMapOf<String, Pending>()
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val pending = values.getOrPut(id) {
                        Pending(c.getLong(1), c.getString(2), c.getString(3), c.getInt(4) != 0,
                            c.getStringOrNull("resolved_status"), c.getStringOrNull("resolved_option_id"),
                            c.getStringOrNull("resolved_option_label"),
                            c.getDouble(8), mutableListOf())
                    }
                    if (!c.isNull(9)) {
                        val idValue = c.getString(9).trim()
                        val label = c.getString(10).trim()
                        if (idValue.isNotEmpty() && label.isNotEmpty()) {
                            val role = runCatching {
                                UserDecisionRole.valueOf(c.getStringOrNull(12).orEmpty().ifBlank { "default" }.uppercase(java.util.Locale.ROOT))
                            }.getOrDefault(UserDecisionRole.DEFAULT)
                            pending.options += UserDecisionOption(idValue, label, c.getStringOrNull(11).orEmpty(), role)
                        }
                    }
                }
                values.map { (id, value) -> ConversationDecisionValue(
                    conversationId, id, value.seq, value.title, value.body, value.allowDismiss,
                    value.status ?: "UNANSWERED", value.optionId, value.optionLabel, value.createdAt,
                    value.options.toList(),
                ) }
            }
        } }

    /**
     * Replaces `role=user`/`role=assistant` history windows from `LocalRunStore.java:loadConversationContext`; a steering dispatch
     * (`interrupt_request_id` set) is deliberately not a turn anchor, matching the legacy pager rule.
     */
    suspend fun readTailWindow(conversationId: String, turns: Int): MessageWindow {
        require(turns > 0) { "turns must be positive" }
        return JarvysChatDatabaseProvider.onQueryExecutor(database) {
            database.withChatReadTransaction { readWindow(conversationId, cursorSeq = null, turns = turns) }
        }
    }

    /** Replaces paged timeline reads from `LocalRunStore.java:readConversationTimelinePage`, resolving the ledger sequence cursor to the projected sort key. */
    suspend fun readWindowBefore(conversationId: String, cursorSeq: Long, turns: Int): MessageWindow {
        require(turns > 0) { "turns must be positive" }
        require(cursorSeq > 0) { "cursorSeq must be positive" }
        return JarvysChatDatabaseProvider.onQueryExecutor(database) {
            database.withChatReadTransaction { readWindow(conversationId, cursorSeq, turns) }
        }
    }

    /** Returns the latest `role=user` ID derived from `LocalRunStore.java:readConversationMessages`. */
    suspend fun latestUserMessageId(conversationId: String): String? {
        return JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
        val highWater = maxSeq(conversationId)
        sql.query("SELECT message_id FROM messages WHERE conversation_id=? AND role='user' AND seq<=? ORDER BY seq DESC LIMIT 1", arrayOf(conversationId, highWater))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } }
    }

    /** Returns the first `role=user` row, replacing `LocalRunStore.java:readFirstUserMessage` and `LocalRunStore.java:loadConversationContext`. */
    suspend fun firstUserMessage(conversationId: String): StoredMessage? {
        return JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
        val highWater = maxSeq(conversationId)
        val id = sql.query("SELECT message_id FROM messages WHERE conversation_id=? AND role='user' AND seq<=? ORDER BY seq LIMIT 1", arrayOf(conversationId, highWater)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        id?.let { hydrateMessages(conversationId, listOf(it), highWater).firstOrNull() }
        } }
    }

    /** Counts `role=user`/`role=assistant` rows from `LocalRunStore.java:readConversationMessages`. */
    suspend fun messageCounts(conversationId: String): MessageCounts = JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
        sql.query(
            "SELECT message_count,(SELECT COUNT(*) FROM messages WHERE conversation_id=? AND role='user') FROM conversations WHERE id=?",
            arrayOf(conversationId, conversationId),
        ).use { c -> if (c.moveToFirst()) MessageCounts(c.getLong(0), c.getLong(1)) else MessageCounts(0, 0) }
    } }

    /**
     * One WAL-friendly projection for the drawer. Rows are timestamp-ordered like the legacy
     * history scan; pin grouping is a presentation concern handled by `DrawerConversationPresentation`.
     */
    suspend fun listConversations(
        limit: Int,
        excludedKinds: Set<String> = DEFAULT_EXCLUDED_KINDS,
    ): List<ConversationSummary> {
        require(limit > 0) { "limit must be positive" }
        return readConversationSummaries(excludedKinds, limit)
    }

    /** Returns every matching live conversation without an arbitrary product limit. */
    suspend fun listAllConversations(
        excludedKinds: Set<String> = DEFAULT_EXCLUDED_KINDS,
    ): List<ConversationSummary> = readConversationSummaries(excludedKinds, null)

    /** Internal delivery recovery must include conversations that have no user or assistant rows. */
    suspend fun listConversationsWithPendingAgentInputs(): List<String> =
        JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
            sql.query(
                "SELECT DISTINCT conversation_id FROM messages WHERE role='agent' AND input_state IS NULL ORDER BY conversation_id",
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        } }

    private suspend fun readConversationSummaries(
        excludedKinds: Set<String>,
        limit: Int?,
    ): List<ConversationSummary> = JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
        val excluded = excludedKinds.toList()
        val hasActiveMessages = "EXISTS(SELECT 1 FROM messages active WHERE active.conversation_id=c.id AND active.seq<c.next_seq AND " +
            "(active.role='user' OR (active.role='assistant' AND active.hidden_kind IS NULL)))"
        val exclusionSql = if (excluded.isEmpty()) "WHERE $hasActiveMessages" else "WHERE c.kind NOT IN (${excluded.joinToString(",") { "?" }}) AND $hasActiveMessages"
        fun orderedText(messageId: String) = "(SELECT group_concat(ordered_payload,'') FROM (" +
            "SELECT p.payload AS ordered_payload FROM message_parts p WHERE p.conversation_id=c.id AND p.message_id=$messageId AND p.kind='text' ORDER BY p.ordinal LIMIT -1))"
        val firstUserId = "(SELECT m.message_id FROM messages m WHERE m.conversation_id=c.id AND m.seq<c.next_seq AND m.role='user' ORDER BY m.seq LIMIT 1)"
        val latestGoalId = "(SELECT m.message_id FROM messages m WHERE m.conversation_id=c.id AND m.seq<c.next_seq AND m.role='user' ORDER BY m.seq DESC LIMIT 1)"
        val firstUser = orderedText(firstUserId)
        val latestGoal = orderedText(latestGoalId)
        val latestActiveId = "(SELECT m.message_id FROM messages m WHERE m.conversation_id=c.id AND m.seq<c.next_seq AND " +
            "(m.role='user' OR (m.role='assistant' AND m.hidden_kind IS NULL)) ORDER BY m.seq DESC LIMIT 1)"
        val latestPreview = orderedText(latestActiveId)
        val latestActiveAt = "(SELECT CAST(m.created_at*1000 AS INTEGER) FROM messages m WHERE m.conversation_id=c.id AND m.seq<c.next_seq AND " +
            "(m.role='user' OR (m.role='assistant' AND m.hidden_kind IS NULL)) ORDER BY m.seq DESC LIMIT 1)"
        val limitClause = if (limit == null) "" else " LIMIT ?"
        val sqlText = "SELECT c.id,c.kind,NULLIF(TRIM(c.title),''),NULLIF(TRIM(c.generated_title),''),$firstUser,$latestPreview,$latestGoal," +
            "((SELECT COUNT(*) FROM messages m WHERE m.conversation_id=c.id AND m.seq<c.next_seq AND (m.role='user' OR (m.role='assistant' AND m.hidden_kind IS NULL)))+1)/2 AS turns," +
            "c.message_count,c.created_at,$latestActiveAt AS latest_active_at,c.pinned,c.archived,0 AS deleted " +
            "FROM conversations c $exclusionSql ORDER BY latest_active_at DESC,c.id$limitClause"
        val args: Array<out Any?> = if (limit == null) excluded.toTypedArray() else arrayOf<Any?>(*excluded.toTypedArray(), limit)
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
    } }

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
     * Optimistically transitions only `generation_run`; callers finalize the corresponding
     * `role=assistant.status` through [finalizeMessage]. Revision, checkpoint monotonicity, and
     * active-target release commit together.
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
    suspend fun markStaleRunsInterrupted(): Int = database.withChatWriteTransaction {
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

    private fun readWindow(conversationId: String, cursorSeq: Long?, turns: Int): MessageWindow {
        // WAL readers avoid Room's serialized write transaction. The append-only item sequence
        // gives every query in this multi-query projection the same high-water boundary.
        val highWater = maxSeq(conversationId)
        if (highWater == 0L) return MessageWindow(conversationId, emptyList(), null)
        val cursorPosition = cursorSeq?.let { seq ->
            sql.query("SELECT sort_seq,sort_sub,seq FROM conversation_items WHERE conversation_id=? AND seq=? AND seq<=?", arrayOf(conversationId, seq, highWater)).use { c ->
                if (c.moveToFirst()) SortPosition(c.getLong(0), c.getInt(1), c.getLong(2)) else null
            }
        }
        if (cursorSeq != null && cursorPosition == null) return MessageWindow(conversationId, emptyList(), null)

        val beforeCursor = cursorPosition?.let { " AND ${tupleLess("i")}" }.orEmpty()
        val anchorArgs = mutableListOf<Any?>(conversationId, highWater)
        if (cursorPosition != null) anchorArgs.addAll(tupleArguments(cursorPosition))
        anchorArgs += turns - 1
        val anchorSql = "SELECT i.seq,i.sort_seq,i.sort_sub FROM conversation_items i INDEXED BY idx_conversation_items_sort " +
            "CROSS JOIN messages m INDEXED BY idx_messages_conversation_seq " +
            "WHERE i.conversation_id=? AND i.seq<=? AND m.conversation_id=i.conversation_id AND m.seq=i.seq " +
            "AND m.role='user' AND m.interrupt_request_id IS NULL$beforeCursor " +
            "ORDER BY i.sort_seq DESC,i.sort_sub DESC,i.seq DESC LIMIT 1 OFFSET ?"
        val selectedAnchor = sql.query(anchorSql, anchorArgs.toTypedArray()).use { c ->
            if (c.moveToFirst()) SortPosition(c.getLong(1), c.getInt(2), c.getLong(0)) else null
        }
        val hasEarlierAnchors = selectedAnchor?.let { selected ->
            sql.query(
                "SELECT EXISTS(SELECT 1 FROM conversation_items i INDEXED BY idx_conversation_items_sort " +
                    "CROSS JOIN messages m INDEXED BY idx_messages_conversation_seq " +
                    "WHERE i.conversation_id=? AND i.seq<=? AND m.conversation_id=i.conversation_id AND m.seq=i.seq " +
                    "AND m.role='user' AND m.interrupt_request_id IS NULL AND ${tupleLess("i")} LIMIT 1)",
                arrayOf<Any?>(conversationId, highWater, *tupleArguments(selected)),
            ).use { c -> c.moveToFirst(); c.getInt(0) != 0 }
        } ?: false
        val startPosition = selectedAnchor?.takeIf { hasEarlierAnchors } ?: run {
            val upper = cursorPosition?.let { " AND ${tupleLess("i")}" }.orEmpty()
            val args = mutableListOf<Any?>(conversationId, highWater)
            if (cursorPosition != null) args.addAll(tupleArguments(cursorPosition))
            sql.query(
                "SELECT i.sort_seq,i.sort_sub,i.seq FROM conversation_items i INDEXED BY idx_conversation_items_sort WHERE i.conversation_id=? AND i.seq<=?$upper " +
                    "ORDER BY i.sort_seq,i.sort_sub,i.seq LIMIT 1",
                args.toTypedArray(),
            ).use { c -> if (c.moveToFirst()) SortPosition(c.getLong(0), c.getInt(1), c.getLong(2)) else null }
        }
        if (startPosition == null) return MessageWindow(conversationId, emptyList(), null)

        val upper = cursorPosition?.let { " AND ${tupleLess("conversation_items")}" }.orEmpty()
        val rangeArgs = mutableListOf<Any?>(conversationId, highWater).also {
            it.addAll(tupleGreaterOrEqualArguments(startPosition))
            if (cursorPosition != null) it.addAll(tupleArguments(cursorPosition))
            it += MAX_WINDOW_ITEMS
        }
        val itemSql = "SELECT * FROM conversation_items INDEXED BY idx_conversation_items_sort WHERE conversation_id=? AND seq<=? AND ${tupleGreaterOrEqual("conversation_items")}$upper " +
            "ORDER BY sort_seq DESC,sort_sub DESC,seq DESC LIMIT ?"
        val items = sql.query(itemSql, rangeArgs.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(readItem(c)) }.asReversed()
        }
        if (items.isEmpty()) return MessageWindow(conversationId, emptyList(), null)

        val first = items.first()
        val hasOlderItems = sql.query(
            "SELECT EXISTS(SELECT 1 FROM conversation_items older WHERE older.conversation_id=? AND older.seq<=? AND ${tupleLess("older")} LIMIT 1)",
            arrayOf<Any?>(conversationId, highWater, *tupleArguments(SortPosition(first.sort_seq, first.sort_sub, first.seq))),
        ).use { c -> c.moveToFirst(); c.getInt(0) != 0 }
        val messageIds = items.asSequence().filter { it.item_kind == ITEM_MESSAGE }.map { it.item_key }.toList()
        val messages = hydrateMessages(conversationId, messageIds, highWater).associateBy { it.messageId }
        val slots = items.map { item ->
            MessageSlot(item, if (item.item_kind == ITEM_MESSAGE) messages[item.item_key] else null)
        }
        // The cursor is inclusive on the next page's lower boundary; using the first item lets
        // even conversations with no user-turn anchors page through their entire item history.
        return MessageWindow(conversationId, slots, first.seq.takeIf { hasOlderItems })
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
        if (input.role !in CONVERSATION_MESSAGE_ROLES) return "role must be user, assistant, agent, or system"
        if (input.stableId != null && input.stableId.isBlank()) return "stable message identity must not be blank"
        if (input.parts.any { it.kind.isBlank() }) return "part kind is required"
        if (input.attachments.isNotEmpty() && input.role != ROLE_USER) return "attachments belong to user messages"
        if (input.attachments.any { it.relativePath.isBlank() || it.attachmentId.isBlank() }) return "attachment identity and relative path are required"
        if (input.beforeMessageId != null && input.role != ROLE_ASSISTANT) return "only assistant messages can be inserted before another message"
        if (input.proactiveDecisionId != null && input.proactiveDecisionId.isBlank()) return "proactive decision id is required"
        if (input.proactiveDecisionId != null && input.role != ROLE_ASSISTANT) return "proactive decision messages must be assistant rows"
        if (input.interruptRequestId != null && input.role != ROLE_USER) return "interrupt dispatches must be user rows"
        return null
    }

    private fun isConversationInputRole(role: String): Boolean = role in CONVERSATION_INPUT_ROLES

    private fun itemKindForInputState(state: String): String = when (state) {
        INPUT_INCORPORATED -> ITEM_INPUT_INCORPORATED
        INPUT_RESOLVED -> ITEM_INPUT_RESOLVED
        else -> error("unsupported input state: $state")
    }

    private fun allocateSequence(conversationId: String): Long {
        val current = sql.query("SELECT next_seq FROM conversations WHERE id=?", arrayOf(conversationId)).use { c ->
            if (!c.moveToFirst()) throw RepositoryAbort("conversation_not_found")
            c.getLong(0)
        }
        sql.execSQL(
            "UPDATE conversations SET next_seq=?,updated_at=? WHERE id=? AND next_seq=?",
            arrayOf(current + 1L, System.currentTimeMillis(), conversationId, current),
        )
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
        ).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getInt(1) else null } ?: return newSeq to 0
        // Open one integer slot at the target. Repeated inserts before the same moved message
        // then land after earlier inserts and immediately before the target, matching the legacy
        // beforeMessageId chain semantics.
        sql.execSQL(
            "UPDATE conversation_items SET sort_sub=sort_sub+1 WHERE conversation_id=? AND sort_seq=? AND sort_sub>=?",
            arrayOf(conversationId, target.first, target.second),
        )
        return target
    }

    private fun latestTurnAnchor(conversationId: String): Long? = sql.query(
        "SELECT seq FROM messages WHERE conversation_id=? AND role='user' AND interrupt_request_id IS NULL ORDER BY seq DESC LIMIT 1",
        arrayOf(conversationId),
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun nextMessageIndex(conversationId: String): Long = sql.query(
        "SELECT message_count FROM conversations WHERE id=?", arrayOf(conversationId),
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /** Places a beforeMessageId append at its destination and shifts the affected suffix once. */
    private fun messageIndexFor(conversationId: String, beforeMessageId: String?): Long {
        if (beforeMessageId == null) return nextMessageIndex(conversationId)
        val destination = sql.query(
            "SELECT message_index FROM messages WHERE conversation_id=? AND message_id=?",
            arrayOf(conversationId, beforeMessageId),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return nextMessageIndex(conversationId)
        sql.execSQL(
            "UPDATE messages SET message_index=message_index+1 WHERE conversation_id=? AND message_index>=?",
            arrayOf(conversationId, destination),
        )
        return destination
    }

    private fun resolveOwnedInputAfterFailure(conversationId: String, ownerUserMessageId: String?, status: String?) {
        if (ownerUserMessageId.isNullOrBlank() || status?.uppercase() !in INPUT_RESOLVING_FAILURE_STATUSES) return
        val owner = findMessage(conversationId, ownerUserMessageId) ?: return
        if (owner.role != ROLE_USER || owner.inputState != null) return
        val seq = allocateSequence(conversationId)
        val now = epochSeconds()
        insertItem(conversationId, seq, ITEM_INPUT_RESOLVED, ownerUserMessageId, seq, 0, latestTurnAnchor(conversationId), now)
        sql.execSQL(
            "UPDATE messages SET input_state=?,input_state_seq=? WHERE conversation_id=? AND message_id=? AND input_state IS NULL",
            arrayOf(INPUT_RESOLVED, seq, conversationId, ownerUserMessageId),
        )
        sql.execSQL(
            "UPDATE interrupt_requests SET input_state=?,input_state_seq=? WHERE conversation_id=? AND dispatched_message_id=? AND input_state IS NULL",
            arrayOf(INPUT_RESOLVED, seq, conversationId, ownerUserMessageId),
        )
    }

    private fun resolveUnacknowledgedInterruptInput(conversationId: String, messageId: String, requestId: String) {
        val message = findMessage(conversationId, messageId) ?: return
        if (message.role != ROLE_USER || message.inputState != null) return
        val seq = allocateSequence(conversationId)
        insertItem(conversationId, seq, ITEM_INPUT_RESOLVED, messageId, seq, 0, latestTurnAnchor(conversationId), epochSeconds())
        sql.execSQL(
            "UPDATE messages SET input_state=?,input_state_seq=? WHERE conversation_id=? AND message_id=? AND input_state IS NULL",
            arrayOf(INPUT_RESOLVED, seq, conversationId, messageId),
        )
        sql.execSQL(
            "UPDATE interrupt_requests SET input_state=?,input_state_seq=? WHERE conversation_id=? AND (request_id=? OR dispatched_message_id=?) AND input_state IS NULL",
            arrayOf(INPUT_RESOLVED, seq, conversationId, requestId, messageId),
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
        parts.filterNot { it.kind == MessagePartKinds.ATTACHMENT_REF } + attachments.map { attachmentReferencePart(it.attachmentId) }

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
            val existingFile = sql.query("SELECT id,sha256,bytes FROM file_refs WHERE relative_path=?", arrayOf(attachment.relativePath)).use { c ->
                if (c.moveToFirst()) Triple(c.getString(0), c.getString(1), c.getLong(2)) else null
            }
            val inputDigest = attachment.sha256.orEmpty()
            val resolvedFileId = existingFile?.first ?: UUID.randomUUID().toString().also { id ->
                sql.execSQL("INSERT INTO file_refs(id,sha256,relative_path,bytes,mime_type,last_referenced_at,attempts,generation) VALUES(?,?,?,?,?,?,0,0)", arrayOf(id, inputDigest, attachment.relativePath, attachment.sizeBytes, attachment.mimeType, (now * 1000).toLong()))
            }
            if (existingFile != null) {
                val storedDigest = existingFile.second.orEmpty()
                if (existingFile.third != attachment.sizeBytes ||
                    storedDigest.isNotEmpty() && inputDigest.isNotEmpty() && storedDigest != inputDigest
                ) throw RepositoryAbort("file_reference_content_conflict")
                // An empty digest means unknown; preserve a known digest or complete it when a
                // later owner supplies one. Byte count remains strict because it is always known.
                val resolvedDigest = if (storedDigest.isEmpty()) inputDigest else storedDigest
                sql.execSQL(
                    "UPDATE file_refs SET sha256=?,mime_type=?,last_referenced_at=? WHERE id=?",
                    arrayOf(resolvedDigest, attachment.mimeType, (now * 1000).toLong(), resolvedFileId),
                )
            }
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

    private fun hydrateMessages(conversationId: String, ids: List<String>, highWater: Long = maxSeq(conversationId)): List<StoredMessage> {
        if (ids.isEmpty()) return emptyList()
        val byId = mutableMapOf<String, StoredMessage>()
        ids.distinct().chunked(MAX_SQL_BIND_PARAMETERS).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "(?)" }
            val baseSql = "WITH requested(message_id) AS (VALUES $placeholders) SELECT m.* FROM messages m JOIN requested r ON r.message_id=m.message_id WHERE m.conversation_id=? AND m.seq<=?"
            sql.query(baseSql, arrayOf<Any?>(*chunk.toTypedArray(), conversationId, highWater)).use { c -> while (c.moveToNext()) { val msg = readMessage(c); byId[msg.messageId] = msg } }
        }
        val loaded = hydrateChildren(conversationId, byId, highWater)
        return ids.mapNotNull(loaded::get)
    }

    private fun hydrateChildren(conversationId: String, base: Map<String, StoredMessage>, highWater: Long): Map<String, StoredMessage> {
        if (base.size <= MAX_SQL_BIND_PARAMETERS) return hydrateChildrenBatch(conversationId, base, highWater)
        return base.entries.chunked(MAX_SQL_BIND_PARAMETERS).flatMap { entries ->
            hydrateChildrenBatch(conversationId, entries.associate { it.key to it.value }, highWater).values
        }.associateBy { it.messageId }
    }

    private fun hydrateChildrenBatch(conversationId: String, base: Map<String, StoredMessage>, highWater: Long): Map<String, StoredMessage> {
        if (base.isEmpty()) return emptyMap()
        val ids = base.keys.toList()
        val values = ids.joinToString(",") { "(?)" }
        val childrenSql = "WITH requested(message_id) AS (VALUES $values) " +
            "SELECT 'part' AS type,p.message_id,p.ordinal,p.kind,p.payload,NULL AS extra1,NULL AS extra2,NULL AS extra3,NULL AS extra4,NULL AS extra5,NULL AS extra6,NULL AS extra7,NULL AS extra8,NULL AS extra9,NULL AS extra10 " +
            "FROM message_parts p JOIN requested r ON r.message_id=p.message_id WHERE p.conversation_id=? " +
            "UNION ALL SELECT 'attachment',a.message_id,a.ordinal,a.attachment_id,a.name,a.mime_type,a.size_bytes,a.kind,a.relative_path,a.sha256,a.file_ref_id,NULL,NULL,NULL,NULL " +
            "FROM message_attachments a JOIN requested r ON r.message_id=a.message_id WHERE a.conversation_id=? " +
            "UNION ALL SELECT 'translation',t.message_id,t.seq,t.language,t.content,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL " +
                "FROM message_translations t JOIN requested r ON r.message_id=t.message_id WHERE t.conversation_id=? AND t.seq<=? AND t.seq=(SELECT MAX(latest.seq) FROM message_translations latest WHERE latest.conversation_id=t.conversation_id AND latest.message_id=t.message_id AND latest.seq<=?) " +
            "UNION ALL SELECT 'reply',s.message_id,s.ordinal,s.label,s.text,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL " +
            "FROM proactive_suggested_replies s JOIN requested r ON r.message_id=s.message_id WHERE s.conversation_id=?"
        val parts = ids.associateWith { mutableListOf<Pair<Int, MessagePartInput>>() }
        val attachments = ids.associateWith { mutableListOf<Pair<Int, MessageAttachmentInput>>() }
        val translations = mutableMapOf<String, TranslationValue>()
        val replies = ids.associateWith { mutableListOf<Pair<Int, SuggestedReplyInput>>() }
        sql.query(childrenSql, arrayOf<Any?>(*ids.toTypedArray(), conversationId, conversationId, conversationId, highWater, highWater, conversationId)).use { c ->
            while (c.moveToNext()) {
                val type = c.getString(0); val id = c.getString(1)
                when (type) {
                    "part" -> parts[id]?.add(c.getInt(2) to MessagePartInput(c.getString(3), c.getString(4)))
                    "attachment" -> attachments[id]?.add(c.getInt(2) to MessageAttachmentInput(c.getString(3), c.getString(4), c.getStringOrNull(5), c.getLong(6), c.getString(7), c.getString(8), c.getStringOrNull(9)))
                    "translation" -> translations[id] = TranslationValue(c.getString(3), c.getString(4), c.getLong(2))
                    "reply" -> replies[id]?.add(c.getInt(2) to SuggestedReplyInput(c.getString(3), c.getString(4)))
                }
            }
        }
        return base.mapValues { (id, message) ->
            val translation = translations[id]?.takeIf { message.translationHiddenSeq == null || it.seq > message.translationHiddenSeq }
            message.copy(
                parts = parts[id].orEmpty().sortedBy { it.first }.map { it.second },
                attachments = attachments[id].orEmpty().sortedBy { it.first }.map { it.second },
                translation = translation,
                suggestedReplies = replies[id].orEmpty().sortedBy { it.first }.map { it.second },
            )
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
        )
    }

    private fun isTombstonedRaw(id: String): Boolean = sql.query("SELECT EXISTS(SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=?)", arrayOf(id)).use { c -> c.moveToFirst(); c.getInt(0)!=0 }

    private fun normalizeManualTitle(value: String): String? = runCatching {
        ConversationMetadataAccess.normalizeTitle(value)
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
        val dispatchedMessageId = c.getStringOrNull("dispatched_message_id")
        val dispatchedSeq = c.getLongOrNull("dispatched_seq")
        val acknowledgementRequired = dispatchedMessageId?.let { messageId ->
            sql.query("SELECT ack_required FROM messages WHERE conversation_id=? AND message_id=?", arrayOf(conversationId, messageId)).use { row -> row.moveToFirst() && row.getInt(0) != 0 }
        } ?: false
        return InterruptRequestValue(conversationId, requestId, c.getLong(c.getColumnIndexOrThrow("seq")), c.getString(c.getColumnIndexOrThrow("content")), c.getInt(c.getColumnIndexOrThrow("memory_disabled"))!=0, skills,
            dispatchedMessageId, dispatchedSeq, c.getStringOrNull("input_state"), c.getLongOrNull("input_state_seq"), dispatchedSeq != null, acknowledgementRequired)
    }

    private suspend fun <T> transactionResult(block: suspend () -> ChatDbResult<T>): ChatDbResult<T> = try {
        database.withChatWriteTransaction { block() }
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
        const val ROLE_AGENT = "agent"
        const val ROLE_SYSTEM = "system"
        private val CONVERSATION_MESSAGE_ROLES = setOf(ROLE_USER, ROLE_ASSISTANT, ROLE_AGENT, ROLE_SYSTEM)
        private val CONVERSATION_INPUT_ROLES = setOf(ROLE_USER, ROLE_AGENT, ROLE_SYSTEM)
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
        private const val ITEM_INPUT_INCORPORATED = "main_chat_input_incorporated"
        private const val ITEM_INPUT_RESOLVED = "main_chat_input_resolved"
        private const val NO_INPUT_STATE_ROW = "\u0001"
        private const val MAX_SQL_BIND_PARAMETERS = 500
        private const val MAX_WINDOW_ITEMS = 500
        private val DEFAULT_EXCLUDED_KINDS = setOf("proactive", "tasks")
        private val INPUT_RESOLVING_FAILURE_STATUSES = setOf("FAILED", "PARTIAL", "STOPPED")
        private fun activeTargetKey(conversationId: String, messageId: String) = "${conversationId.length}:$conversationId${messageId.length}:$messageId"
        private fun isValidConversationId(value: String) = value.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && value != "." && value != ".."
        private fun epochSeconds(): Double = System.currentTimeMillis() / 1000.0
        private fun Boolean.asSqlInt() = if (this) 1 else 0
        private fun String?.isTerminalStatus() = this?.uppercase() in setOf("COMPLETED", "FAILED", "PARTIAL", "STOPPED", "INTERRUPTED")
        private fun isOrdinaryChat(kind: String) = kind != "proactive" && kind != "tasks"
        private fun String?.isStreamingStatus() = this?.uppercase() in setOf("STREAMING", "IN_PROGRESS")
        private fun String.isActiveRunState() = this in setOf(RUN_PREPARING, RUN_REQUESTING, RUN_STREAMING, RUN_WAITING_TOOL)
        private fun String.isTerminalRunState() = this in setOf("completed", "failed", "interrupted", "cancelled", "stopped")

    }
}

/**
 * Java bridge for M4 callers. Every `*Sync` call runs its coroutine on this facade's single
 * background executor, then lets Room dispatch database work to Room's own transaction executor.
 * Calls from Android's main looper are rejected. Never invoke this bridge from inside a Room
 * transaction callback; Java callers should own one bridge and close it with their component.
 */
class ConversationRepositoryBlocking(private val repository: ConversationRepository) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "chatdb-java-blocking").apply { isDaemon = true }
    }

    fun ensureConversationSync(id: String, kind: String = "chat"): ChatDbResult<Conversation> =
        execute { repository.ensureConversation(id, kind) }

    fun updateMetadataSync(
        conversationId: String,
        title: String?,
        generatedTitle: String?,
        pinned: Boolean?,
        archived: Boolean?,
        privateCode: Boolean?,
    ): ChatDbResult<Conversation> = execute {
        repository.updateMetadata(conversationId, title, generatedTitle, pinned, archived, privateCode)
    }

    fun appendConversationTitleIfAbsentSync(conversationId: String, title: String?): ChatDbResult<Boolean> =
        execute { repository.appendConversationTitleIfAbsent(conversationId, title) }

    fun deleteConversationSync(conversationId: String): ChatDbResult<Boolean> =
        execute { repository.deleteConversation(conversationId) }

    fun appendMessageSync(input: AppendMessageInput): ChatDbResult<StoredMessage> =
        execute { repository.appendMessage(input) }

    fun finalizeMessageSync(
        conversationId: String,
        messageId: String,
        parts: List<MessagePartInput>,
        finalStatus: String,
        durationMillis: Long? = null,
    ): ChatDbResult<StoredMessage> = execute {
        repository.finalizeMessage(conversationId, messageId, parts, finalStatus, durationMillis)
    }

    fun pendingInterruptRequestsSync(conversationId: String? = null): List<InterruptRequestValue> =
        execute { repository.pendingInterruptRequests(conversationId) }

    fun dispatchInterruptRequestSync(conversationId: String, requestId: String): ChatDbResult<DispatchedInterrupt> =
        execute { repository.dispatchInterruptRequest(conversationId, requestId) }

    fun markInputStateSync(conversationId: String, messageIds: List<String>, state: String): ChatDbResult<List<String>> =
        execute { repository.markInputState(conversationId, messageIds, state) }

    fun getMessageSync(conversationId: String, messageId: String): StoredMessage? =
        execute { repository.getMessage(conversationId, messageId) }

    fun getMessagesByIdsSync(conversationId: String, messageIds: List<String>): List<StoredMessage> =
        execute { repository.getMessagesByIds(conversationId, messageIds) }

    fun readTailWindowSync(conversationId: String, turns: Int): MessageWindow =
        execute { repository.readTailWindow(conversationId, turns) }

    fun readWindowBeforeSync(conversationId: String, cursorSeq: Long, turns: Int): MessageWindow =
        execute { repository.readWindowBefore(conversationId, cursorSeq, turns) }

    fun listConversationsSync(limit: Int): List<ConversationSummary> =
        execute { repository.listConversations(limit) }

    private fun <T> execute(block: suspend () -> T): T {
        ChatSqlBeforeWriteGuard.checkRepositoryAccess()
        check(LeasedConnectionElement.current() == null && RawReadConnectionElement.current() == null) {
            "ConversationRepositoryBlocking cannot be called inside an active chat database lease"
        }
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ConversationRepositoryBlocking must not be called from the main thread"
        }
        return try {
            executor.submit(Callable { runBlocking { block() } }).get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for the chat database", interrupted)
        } catch (failure: java.util.concurrent.ExecutionException) {
            throw (failure.cause ?: failure)
        }
    }

    override fun close() {
        executor.shutdown()
    }
}

data class DecisionOptionInput(val id: String, val label: String, val description: String? = null, val role: String? = null)

private fun Cursor.getStringOrNull(name: String): String? = getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getString(it) }
private fun Cursor.getLongOrNull(name: String): Long? = getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getLong(it) }
private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
private fun Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
private fun Cursor.getColumnIndexOrThrow(name: String): Int = getColumnIndex(name).also { check(it >= 0) { "Missing query column: $name" } }

