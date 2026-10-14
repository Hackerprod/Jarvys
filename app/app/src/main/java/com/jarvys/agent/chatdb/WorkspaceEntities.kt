package com.jarvys.agent.chatdb

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Replaces `workspace_result_reference` and `workspace_result_notice` rows that link to immutable crew evidence.
 * `evidence_id` is a logical cross-ledger identity: execution evidence and central notices are imported from
 * separate conversation ledgers, so repository validation must check the binding without a SQLite FK.
 * First-seen result/reference identities are immutable; M1 writes use first-wins `INSERT OR IGNORE` semantics.
 */
@Entity(
    tableName = "workspace_result_refs",
    primaryKeys = ["conversation_id", "reference_id", "is_notice"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_workspace_result_refs_evidence", value = ["evidence_id"]),
        Index(name = "idx_workspace_result_refs_seq", value = ["conversation_id", "seq"]),
        Index(name = "idx_workspace_result_refs_notice", value = ["conversation_id", "is_notice", "seq"]),
    ],
)
data class WorkspaceResultRef(
    val schema_version: Int = 1,
    val conversation_id: String,
    val reference_id: String,
    val is_notice: Boolean,
    val seq: Long,
    val evidence_id: String,
    val content_sha256: String,
    val owner_central_session_id: String,
    val workspace_id: String,
    val execution_session_id: String,
    val assignment_id: String,
    val request_id: String,
    val mission_id: String,
    val bot_id: String,
    val cycle_id: String,
    val observed_assignment_revision: Long,
    val reported_status: String,
    val produced_at_ms: Long,
)

/** Replaces `crew_coordination_evidence`; free-form text is preserved as exact UTF-16 code-unit bytes. */
@Entity(
    tableName = "crew_coordination_evidence",
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_crew_coordination_mission", value = ["conversation_id", "mission_id", "bot_id", "cycle_id"]),
        Index(name = "idx_crew_coordination_message", value = ["conversation_id", "message_id"]),
        Index(name = "idx_crew_coordination_seq", value = ["conversation_id", "seq"]),
    ],
)
data class CrewCoordinationEvidence(
    val schema_version: Int = 1,
    @androidx.room.PrimaryKey val evidence_id: String,
    val conversation_id: String,
    val seq: Long,
    val mission_id: String,
    val bot_id: String,
    val cycle_id: String,
    val message_id: String,
    val kind: String,
    val text: ByteArray,
    val produced_at_ms: Long,
    val content_sha256: String,
) {
    override fun equals(other: Any?): Boolean = other is CrewCoordinationEvidence &&
        schema_version == other.schema_version && evidence_id == other.evidence_id &&
        conversation_id == other.conversation_id && seq == other.seq && mission_id == other.mission_id &&
        bot_id == other.bot_id && cycle_id == other.cycle_id && message_id == other.message_id &&
        kind == other.kind && text.contentEquals(other.text) && produced_at_ms == other.produced_at_ms &&
        content_sha256 == other.content_sha256

    override fun hashCode(): Int {
        var result = schema_version
        result = 31 * result + evidence_id.hashCode()
        result = 31 * result + conversation_id.hashCode()
        result = 31 * result + seq.hashCode()
        result = 31 * result + mission_id.hashCode()
        result = 31 * result + bot_id.hashCode()
        result = 31 * result + cycle_id.hashCode()
        result = 31 * result + message_id.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + text.contentHashCode()
        result = 31 * result + produced_at_ms.hashCode()
        result = 31 * result + content_sha256.hashCode()
        return result
    }
}

/** Replaces the `refs[]` values nested in `crew_coordination_evidence` rows. */
@Entity(
    tableName = "crew_coordination_evidence_refs",
    primaryKeys = ["evidence_id", "ordinal"],
    foreignKeys = [ForeignKey(entity = CrewCoordinationEvidence::class, parentColumns = ["evidence_id"], childColumns = ["evidence_id"], onDelete = ForeignKey.CASCADE)],
)
data class CrewCoordinationEvidenceRef(
    val evidence_id: String,
    val ordinal: Int,
    val ref: String,
)

/**
 * Replaces `workspace_coordination_reference` and `workspace_coordination_notice` rows.
 * `evidence_id` remains a logical reference because notices are written to the central ledger before its
 * execution-ledger evidence is necessarily imported; the repository verifies the cross-ledger binding.
 */
@Entity(
    tableName = "workspace_coordination_refs",
    primaryKeys = ["conversation_id", "reference_id", "is_notice"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_workspace_coord_refs_evidence", value = ["evidence_id"]),
        Index(name = "idx_workspace_coord_refs_seq", value = ["conversation_id", "seq"]),
        Index(name = "idx_workspace_coord_refs_notice", value = ["conversation_id", "is_notice", "seq"]),
        Index(name = "idx_workspace_coord_refs_message", value = ["conversation_id", "message_id"]),
    ],
)
data class WorkspaceCoordinationRef(
    val schema_version: Int = 1,
    val conversation_id: String,
    val reference_id: String,
    val is_notice: Boolean,
    val seq: Long,
    val evidence_id: String,
    val content_sha256: String,
    val owner_central_session_id: String,
    val workspace_id: String,
    val execution_session_id: String,
    val assignment_id: String,
    val request_id: String,
    val mission_id: String,
    val bot_id: String,
    val cycle_id: String,
    val message_id: String,
    val kind: String,
    val observed_assignment_revision: Long,
    val reported_status: String,
    val produced_at_ms: Long,
)

/**
 * Replaces `workspace_coordination_reply_intent`; free-form text is preserved as exact UTF-16 BLOBs.
 * Reply intents belong to the central ledger and reference execution evidence logically, while their
 * `(conversation_id, reference_id, is_notice)` FK points to the local central notice row.
 */
@Entity(
    tableName = "workspace_coordination_replies",
    primaryKeys = ["conversation_id", "reference_id"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = WorkspaceCoordinationRef::class, parentColumns = ["conversation_id", "reference_id", "is_notice"], childColumns = ["conversation_id", "reference_id", "is_notice"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_workspace_replies_reference", value = ["conversation_id", "reference_id", "is_notice"]),
        Index(name = "idx_workspace_replies_conversation_reply", value = ["conversation_id", "reply_id"], unique = true),
        Index(name = "idx_workspace_replies_reply_id", value = ["reply_id"], unique = true),
        Index(name = "idx_workspace_replies_evidence", value = ["evidence_id"], unique = true),
        Index(name = "idx_workspace_replies_seq", value = ["conversation_id", "seq"]),
        Index(name = "idx_workspace_replies_state", value = ["conversation_id", "state", "seq"]),
    ],
)
data class WorkspaceCoordinationReply(
    val conversation_id: String,
    val reference_id: String,
    @ColumnInfo(defaultValue = "1") val is_notice: Boolean = true,
    val schema_version: Int = 1,
    val reply_id: String,
    val evidence_id: String,
    val reply_key: String,
    val text: ByteArray,
    val central_request_id: String,
    val original_user_request: ByteArray,
    val mailbox_message_id: String,
    val payload_sha256: String,
    val prepared_at_ms: Long,
    val state: String,
    val state_at_ms: Long,
    val seq: Long,
) {
    override fun equals(other: Any?): Boolean = other is WorkspaceCoordinationReply &&
        conversation_id == other.conversation_id && reference_id == other.reference_id &&
        is_notice == other.is_notice && schema_version == other.schema_version && reply_id == other.reply_id &&
        evidence_id == other.evidence_id && reply_key == other.reply_key && text.contentEquals(other.text) &&
        central_request_id == other.central_request_id &&
        original_user_request.contentEquals(other.original_user_request) &&
        mailbox_message_id == other.mailbox_message_id && payload_sha256 == other.payload_sha256 &&
        prepared_at_ms == other.prepared_at_ms && state == other.state && state_at_ms == other.state_at_ms && seq == other.seq

    override fun hashCode(): Int {
        var result = conversation_id.hashCode()
        result = 31 * result + reference_id.hashCode()
        result = 31 * result + is_notice.hashCode()
        result = 31 * result + schema_version
        result = 31 * result + reply_id.hashCode()
        result = 31 * result + evidence_id.hashCode()
        result = 31 * result + reply_key.hashCode()
        result = 31 * result + text.contentHashCode()
        result = 31 * result + central_request_id.hashCode()
        result = 31 * result + original_user_request.contentHashCode()
        result = 31 * result + mailbox_message_id.hashCode()
        result = 31 * result + payload_sha256.hashCode()
        result = 31 * result + prepared_at_ms.hashCode()
        result = 31 * result + state.hashCode()
        result = 31 * result + state_at_ms.hashCode()
        result = 31 * result + seq.hashCode()
        return result
    }
}

/**
 * Replaces `workspace_coordination_reply_state` with its monotonic transition history. M1 repository writes
 * must allow only PREPARED to STARTED to ACCEPTED_LOCAL; the state transition check needs a database trigger because
 * Room cannot express it. The unique state index enforces at most one transition per state.
 */
@Entity(
    tableName = "workspace_coordination_reply_transitions",
    primaryKeys = ["conversation_id", "reply_id", "seq"],
    foreignKeys = [
        ForeignKey(entity = WorkspaceCoordinationReply::class, parentColumns = ["conversation_id", "reply_id"], childColumns = ["conversation_id", "reply_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_workspace_reply_transitions_seq", value = ["conversation_id", "seq"]),
        Index(name = "idx_workspace_reply_transitions_state", value = ["conversation_id", "reply_id", "state"], unique = true),
    ],
)
data class WorkspaceCoordinationReplyTransition(
    val schema_version: Int = 1,
    val conversation_id: String,
    val reply_id: String,
    val seq: Long,
    val state: String,
    val state_at_ms: Long,
)

/**
 * Replaces `workspace_input_incorporation` receipts proving that workspace input was incorporated. First-seen
 * receipt identities are immutable; M1 repository writes use first-wins `INSERT OR IGNORE` semantics.
 */
@Entity(
    tableName = "workspace_input_incorporations",
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_workspace_input_mailbox", value = ["mailbox_message_id"], unique = true),
        Index(name = "idx_workspace_input_source", value = ["conversation_id", "source_kind", "source_id"]),
        Index(name = "idx_workspace_input_seq", value = ["conversation_id", "seq"]),
    ],
)
data class WorkspaceInputIncorporation(
    val schema_version: Int = 1,
    @androidx.room.PrimaryKey val receipt_id: String,
    val conversation_id: String,
    val seq: Long,
    val source_kind: String,
    val source_id: String,
    val source_reference_id: String?,
    val mailbox_message_id: String,
    val owner_central_session_id: String,
    val workspace_id: String,
    val execution_session_id: String,
    val assignment_id: String,
    val request_id: String,
    val mission_id: String,
    val bot_id: String,
    val admission_cycle_id: String,
    val first_incorporation_cycle_id: String,
    val source_payload_sha256: String,
    val delivered_content_sha256: String,
    val transport_kind: String,
    val transport_sha256: String,
    val checkpoint_payload_sha256: String,
    val checkpoint_saved_at_ms: Long,
    val recorded_at_ms: Long,
)

