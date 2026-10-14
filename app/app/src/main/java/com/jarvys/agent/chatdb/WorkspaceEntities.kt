package com.jarvys.agent.chatdb

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Replaces `workspace_result_reference` and `workspace_result_notice` rows that link to immutable crew evidence. */
@Entity(
    tableName = "workspace_result_refs",
    primaryKeys = ["conversation_id", "reference_id", "is_notice"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CrewResultEvidence::class, parentColumns = ["evidence_id"], childColumns = ["evidence_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_workspace_result_refs_evidence", value = ["evidence_id"]),
        Index(name = "idx_workspace_result_refs_seq", value = ["conversation_id", "seq"]),
        Index(name = "idx_workspace_result_refs_notice", value = ["conversation_id", "is_notice", "seq"]),
    ],
)
data class WorkspaceResultRef(
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
    foreignKeys = [ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(name = "idx_crew_coordination_mission", value = ["conversation_id", "mission_id", "bot_id", "cycle_id"]),
        Index(name = "idx_crew_coordination_message", value = ["conversation_id", "message_id"]),
        Index(name = "idx_crew_coordination_seq", value = ["conversation_id", "seq"]),
    ],
)
data class CrewCoordinationEvidence(
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
)

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

/** Replaces `workspace_coordination_reference` and `workspace_coordination_notice` rows. */
@Entity(
    tableName = "workspace_coordination_refs",
    primaryKeys = ["conversation_id", "reference_id", "is_notice"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CrewCoordinationEvidence::class, parentColumns = ["evidence_id"], childColumns = ["evidence_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_workspace_coord_refs_evidence", value = ["evidence_id"]),
        Index(name = "idx_workspace_coord_refs_seq", value = ["conversation_id", "seq"]),
        Index(name = "idx_workspace_coord_refs_notice", value = ["conversation_id", "is_notice", "seq"]),
        Index(name = "idx_workspace_coord_refs_message", value = ["conversation_id", "message_id"]),
    ],
)
data class WorkspaceCoordinationRef(
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

/** Replaces `workspace_coordination_reply_intent`; free-form text is preserved as exact UTF-16 BLOBs. */
@Entity(
    tableName = "workspace_coordination_replies",
    primaryKeys = ["conversation_id", "reference_id"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = WorkspaceCoordinationRef::class, parentColumns = ["conversation_id", "reference_id", "is_notice"], childColumns = ["conversation_id", "reference_id", "is_notice"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CrewCoordinationEvidence::class, parentColumns = ["evidence_id"], childColumns = ["evidence_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_workspace_replies_conversation_reply", value = ["conversation_id", "reply_id"], unique = true),
        Index(name = "idx_workspace_replies_reply_id", value = ["reply_id"], unique = true),
        Index(name = "idx_workspace_replies_evidence", value = ["evidence_id"], unique = true),
        Index(name = "idx_workspace_replies_state", value = ["conversation_id", "state", "seq"]),
    ],
)
data class WorkspaceCoordinationReply(
    val conversation_id: String,
    val reference_id: String,
    val is_notice: Boolean = false,
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
)

/** Replaces `workspace_coordination_reply_state` with its monotonic transition history. */
@Entity(
    tableName = "workspace_coordination_reply_transitions",
    primaryKeys = ["conversation_id", "reply_id", "seq"],
    foreignKeys = [ForeignKey(entity = WorkspaceCoordinationReply::class, parentColumns = ["conversation_id", "reply_id"], childColumns = ["conversation_id", "reply_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(name = "idx_workspace_reply_transitions_seq", value = ["conversation_id", "seq"])],
)
data class WorkspaceCoordinationReplyTransition(
    val conversation_id: String,
    val reply_id: String,
    val seq: Long,
    val state: String,
    val state_at_ms: Long,
)

/** Replaces `workspace_input_incorporation` receipts proving that workspace input was incorporated. */
@Entity(
    tableName = "workspace_input_incorporations",
    foreignKeys = [ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(name = "idx_workspace_input_mailbox", value = ["mailbox_message_id"], unique = true),
        Index(name = "idx_workspace_input_source", value = ["conversation_id", "source_kind", "source_id"]),
        Index(name = "idx_workspace_input_seq", value = ["conversation_id", "seq"]),
    ],
)
data class WorkspaceInputIncorporation(
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

