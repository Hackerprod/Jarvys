package com.jarvys.agent.chatdb

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Conversation metadata and legacy-import receipts. Deletion tombstones live independently in [Tombstone]. */
@Entity(tableName = "conversations")
data class Conversation(
    @androidx.room.PrimaryKey val id: String,
    val kind: String = "chat",
    val title: String? = null,
    val generated_title: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val private_code: Boolean = false,
    val created_at: Long,
    val updated_at: Long,
    val last_message_at: Long? = null,
    val message_count: Long = 0L,
    val next_seq: Long = 1L,
    val import_state: String = "not_started",
    val legacy_ledger_bytes: Long? = null,
    val legacy_ledger_sha256: String? = null,
    val imported_at: Long? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val import_offset: Long = 0L,
)

/** Deletion history survives the conversation CASCADE and permanently blocks resurrection. */
@Entity(tableName = "tombstones", primaryKeys = ["scope", "entity_id"])
data class Tombstone(
    val scope: String,
    val entity_id: String,
    val deleted_at: Long,
    val payload: String? = null,
)

/** Ordered ledger spine for timeline-visible rows and positional markers of every ledger row type. */
@Entity(
    tableName = "conversation_items",
    primaryKeys = ["conversation_id", "seq"],
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "idx_conversation_items_sort", value = ["conversation_id", "sort_seq", "sort_sub", "seq"]),
        Index(name = "idx_conversation_items_turn_anchor", value = ["conversation_id", "turn_anchor_seq"]),
    ],
)
data class ConversationItem(
    val conversation_id: String,
    val seq: Long,
    val item_kind: String,
    val item_key: String,
    val sort_seq: Long,
    val sort_sub: Int = 0,
    val turn_anchor_seq: Long? = null,
    /** Original ledger timestamp in seconds; seq remains the only ordering key. */
    val created_at: Double,
)

/** `role=user`, `role=assistant`, and `proactive_message` rows plus positional message markers; body lives only in message_parts. */
@Entity(
    tableName = "messages",
    primaryKeys = ["conversation_id", "message_id"],
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ConversationItem::class,
            parentColumns = ["conversation_id", "seq"],
            childColumns = ["conversation_id", "seq"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "idx_messages_conversation_seq", value = ["conversation_id", "seq"], unique = true),
        Index(name = "idx_messages_conversation_role_seq", value = ["conversation_id", "role", "seq"]),
        Index(name = "idx_messages_owner", value = ["conversation_id", "owner_user_message_id"]),
        Index(name = "idx_messages_message_index", value = ["conversation_id", "message_index"]),
        Index(name = "idx_messages_message_id", value = ["message_id"]),
        Index(name = "idx_messages_stable_id", value = ["conversation_id", "stable_id"], unique = true),
        Index(name = "idx_messages_proactive_decision", value = ["conversation_id", "proactive_decision_id"], unique = true),
        Index(name = "idx_messages_interrupt", value = ["conversation_id", "interrupt_request_id"]),
    ],
)
data class Message(
    val conversation_id: String,
    val message_id: String,
    val stable_id: String,
    val seq: Long,
    val message_index: Long,
    val role: String,
    val status: String? = null,
    val run_id: String? = null,
    val owner_user_message_id: String? = null,
    val duration_ms: Long? = null,
    val before_message_id: String? = null,
    val proactive_thread_key: String? = null,
    val proactive_reply_to_message_id: String? = null,
    /** Legacy decision identity is deduplicated within this conversation, not across ledgers. */
    val proactive_decision_id: String? = null,
    val interrupt_request_id: String? = null,
    val ack_required: Boolean = false,
    /** Optional source integer retained from the proposed legacy schema; M2 will define its importer mapping. */
    val legacy_id: Long? = null,
    val created_at: Double,
    val updated_at: Double,
    val hidden_kind: String? = null,
    val hidden_seq: Long? = null,
    val translation_hidden_seq: Long? = null,
    val suggested_replies_consumed_seq: Long? = null,
    val input_state: String? = null,
    val input_state_seq: Long? = null,
)

/** User/assistant `content` and reasoning body parts. Re-generation creates a new message identity. */
@Entity(
    tableName = "message_parts",
    primaryKeys = ["conversation_id", "message_id", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Message::class,
            parentColumns = ["conversation_id", "message_id"],
            childColumns = ["conversation_id", "message_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MessagePart(
    val conversation_id: String,
    val message_id: String,
    val ordinal: Int,
    val kind: String,
    val payload: String,
)

/** `role=user.attachments` references; file bytes remain on disk. */
@Entity(
    tableName = "message_attachments",
    primaryKeys = ["conversation_id", "message_id", "ordinal"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Message::class, parentColumns = ["conversation_id", "message_id"], childColumns = ["conversation_id", "message_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FileRef::class, parentColumns = ["id"], childColumns = ["file_ref_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index(name = "idx_message_attachments_id", value = ["conversation_id", "attachment_id"]),
        Index(name = "idx_message_attachments_file_ref", value = ["file_ref_id"]),
    ],
)
data class MessageAttachment(
    val conversation_id: String,
    val message_id: String,
    val ordinal: Int,
    val attachment_id: String,
    val name: String,
    val mime_type: String?,
    val size_bytes: Long,
    val kind: String,
    val relative_path: String,
    val sha256: String? = null,
    val file_ref_id: String? = null,
)

/** `assistant_translation` rows keyed by their ledger position. */
@Entity(
    tableName = "message_translations",
    primaryKeys = ["conversation_id", "message_id", "seq"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Message::class, parentColumns = ["conversation_id", "message_id"], childColumns = ["conversation_id", "message_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(name = "idx_message_translations_seq", value = ["conversation_id", "seq"])],
)
data class MessageTranslation(
    val conversation_id: String,
    val message_id: String,
    val seq: Long,
    val language: String,
    val content: String,
    val created_at: Double,
)

/** Current state derived from `message_reaction` rows for a stable message identity. */
@Entity(
    tableName = "message_reactions",
    primaryKeys = ["conversation_id", "target_stable_id"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(name = "idx_message_reactions_seq", value = ["conversation_id", "seq"])],
)
data class MessageReaction(
    val conversation_id: String,
    val target_stable_id: String,
    val emoji: String,
    val seq: Long,
    val created_at: Double,
)

/** `proactive_message.suggestedReplies` rows attached to proactive assistant messages. */
@Entity(
    tableName = "proactive_suggested_replies",
    primaryKeys = ["conversation_id", "message_id", "ordinal"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Message::class, parentColumns = ["conversation_id", "message_id"], childColumns = ["conversation_id", "message_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ProactiveSuggestedReply(
    val conversation_id: String,
    val message_id: String,
    val ordinal: Int,
    val label: String,
    val text: String,
)

/** File metadata for attachment, delivered-file, generated-image, and model-artifact rows; retained for deferred M1 GC. */
@Entity(
    tableName = "file_refs",
    indices = [
        Index(name = "idx_file_refs_relative_path", value = ["relative_path"], unique = true),
        Index(name = "idx_file_refs_sha256", value = ["sha256"]),
    ],
)
data class FileRef(
    @androidx.room.PrimaryKey val id: String,
    val sha256: String,
    val relative_path: String,
    val bytes: Long,
    val mime_type: String? = null,
    val last_referenced_at: Long,
    /** Earliest GC attempt time; deleting the ref never deletes history or the physical file in SQL. */
    val not_before: Long? = null,
    val attempts: Int = 0,
    val generation: Long = 0,
)

/** Normalized string part kinds; unknown kinds remain round-trippable. */
object MessagePartKinds {
    const val TEXT = "text"
    const val REASONING = "reasoning"
    const val ATTACHMENT_REF = "attachment_ref"
}

