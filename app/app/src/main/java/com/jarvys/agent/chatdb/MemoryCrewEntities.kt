package com.jarvys.agent.chatdb

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** `compaction` rows; compaction_id persists the legacy-derived identifier. */
@Entity(
    tableName = "compactions",
    primaryKeys = ["conversation_id", "compaction_id"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(name = "idx_compactions_seq", value = ["conversation_id", "seq"])],
)
data class Compaction(
    val conversation_id: String,
    val compaction_id: String,
    val seq: Long,
    val summary: String,
    val first_kept: Long,
    val summarized_messages: Long,
    val trigger: String,
    val mode: String,
    val created_at: Double,
)

/** `compaction_reflection_consumed` rows and acknowledgements emitted by reflection checkpoints/commits. */
@Entity(
    tableName = "compaction_reflection_acks",
    primaryKeys = ["conversation_id", "compaction_id", "seq"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Compaction::class, parentColumns = ["conversation_id", "compaction_id"], childColumns = ["conversation_id", "compaction_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(name = "idx_compaction_acks_seq", value = ["conversation_id", "seq"])],
)
data class CompactionReflectionAck(
    val conversation_id: String,
    val compaction_id: String,
    val seq: Long,
    val source: String,
    val created_at: Double,
)

/** `reflection_checkpoint`, `reflection_commit`, `reflection_event`, and `reflection_undo` rows. */
@Entity(
    tableName = "reflections",
    primaryKeys = ["conversation_id", "seq"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(name = "idx_reflections_id", value = ["conversation_id", "reflection_id", "seq"])],
)
data class Reflection(
    val conversation_id: String,
    val seq: Long,
    val kind: String,
    val reflection_id: String? = null,
    val through_message_id: String? = null,
    val completed_assistant_steps: Long? = null,
    val result: String? = null,
    val summary: String? = null,
    val status: String? = null,
    val trigger: String? = null,
    val undone_seq: Long? = null,
    val created_at: Double,
)

/** Revision IDs nested in `reflection_commit` and `reflection_event` rows. */
@Entity(
    tableName = "reflection_revisions",
    primaryKeys = ["conversation_id", "reflection_seq", "ordinal"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Reflection::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "reflection_seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(name = "idx_reflection_revisions_id", value = ["conversation_id", "revision_id"])],
)
data class ReflectionRevision(
    val conversation_id: String,
    val reflection_seq: Long,
    val ordinal: Int,
    val revision_id: Long,
)

/** Latest normalized `crew_snapshot` header for a mission. */
@Entity(
    tableName = "crew_missions",
    primaryKeys = ["conversation_id", "mission_id"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "first_snapshot_seq"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "last_snapshot_seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_crew_missions_first_snapshot_seq", value = ["conversation_id", "first_snapshot_seq"]),
        Index(name = "idx_crew_missions_snapshot_seq", value = ["conversation_id", "last_snapshot_seq"]),
    ],
)
data class CrewMission(
    val conversation_id: String,
    val mission_id: String,
    val first_snapshot_seq: Long,
    val last_snapshot_seq: Long,
    val schema_version: Int,
    val process_id: String?,
    /** SHA-256 of the lossless serialized `crew_snapshot` payload, excluding its ledger timestamp. */
    val snapshot_digest: String,
    val title: String,
    val original_instructions: String,
    val title_source: String? = null,
    val status: String,
    val synthesis: ByteArray? = null,
    val started_at_ms: Long,
    val finished_at_ms: Long? = null,
) {
    override fun equals(other: Any?): Boolean = this === other || other is CrewMission &&
        conversation_id == other.conversation_id && mission_id == other.mission_id &&
        first_snapshot_seq == other.first_snapshot_seq && last_snapshot_seq == other.last_snapshot_seq &&
        schema_version == other.schema_version && process_id == other.process_id && snapshot_digest == other.snapshot_digest && title == other.title &&
        original_instructions == other.original_instructions && title_source == other.title_source && status == other.status &&
        ((synthesis == null && other.synthesis == null) || (synthesis != null && other.synthesis != null && synthesis.contentEquals(other.synthesis))) &&
        started_at_ms == other.started_at_ms && finished_at_ms == other.finished_at_ms

    override fun hashCode(): Int = 31 * listOf(conversation_id, mission_id, first_snapshot_seq, last_snapshot_seq,
        schema_version, process_id, snapshot_digest, title, original_instructions, title_source, status, started_at_ms, finished_at_ms).hashCode() +
        (synthesis?.contentHashCode() ?: 0)
}

/** Latest bot state nested under a `crew_snapshot` mission. */
@Entity(
    tableName = "crew_bots",
    primaryKeys = ["conversation_id", "mission_id", "bot_id"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CrewMission::class, parentColumns = ["conversation_id", "mission_id"], childColumns = ["conversation_id", "mission_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_crew_bots_ordinal", value = ["conversation_id", "mission_id", "ordinal"], unique = true),
        Index(name = "idx_crew_bots_evidence", value = ["conversation_id", "result_evidence_id"]),
    ],
)
data class CrewBot(
    val conversation_id: String,
    val mission_id: String,
    val bot_id: String,
    val ordinal: Int,
    val cycle_id: String? = null,
    val role_id: String? = null,
    val role_name: String? = null,
    val name: String,
    val color_key: String? = null,
    val mission: String? = null,
    val status: String,
    val error: ByteArray? = null,
    val result: ByteArray? = null,
    val waiting_reason: String? = null,
    val phase: String? = null,
    val last_progress: String? = null,
    val last_progress_at_ms: Long? = null,
    val resume_required: Boolean,
    val can_resume: Boolean,
    val recovery_note: String? = null,
    val started_at_ms: Long? = null,
    val finished_at_ms: Long? = null,
    /** Evidence identity and digest let M2 verify this snapshot copy against evidence in the same conversation. */
    val result_evidence_id: String? = null,
    val evidence_content_sha256: String? = null,
) {
    override fun equals(other: Any?): Boolean = this === other || other is CrewBot &&
        conversation_id == other.conversation_id && mission_id == other.mission_id && bot_id == other.bot_id &&
        ordinal == other.ordinal && cycle_id == other.cycle_id && role_id == other.role_id && role_name == other.role_name &&
        name == other.name && color_key == other.color_key && mission == other.mission && status == other.status &&
        nullableBytesEqual(error, other.error) && nullableBytesEqual(result, other.result) && waiting_reason == other.waiting_reason &&
        phase == other.phase && last_progress == other.last_progress && last_progress_at_ms == other.last_progress_at_ms &&
        resume_required == other.resume_required && can_resume == other.can_resume && recovery_note == other.recovery_note &&
        started_at_ms == other.started_at_ms && finished_at_ms == other.finished_at_ms &&
        result_evidence_id == other.result_evidence_id && evidence_content_sha256 == other.evidence_content_sha256

    override fun hashCode(): Int = 31 * listOf(conversation_id, mission_id, bot_id, ordinal, cycle_id, role_id,
        role_name, name, color_key, mission, status, waiting_reason, phase, last_progress, last_progress_at_ms,
        resume_required, can_resume, recovery_note, started_at_ms, finished_at_ms, result_evidence_id,
        evidence_content_sha256).hashCode() +
        31 * (error?.contentHashCode() ?: 0) + (result?.contentHashCode() ?: 0)
}

/** Tool declaration nested under one `crew_snapshot` bot. */
@Entity(
    tableName = "crew_bot_tools",
    primaryKeys = ["conversation_id", "mission_id", "bot_id", "ordinal"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CrewBot::class, parentColumns = ["conversation_id", "mission_id", "bot_id"], childColumns = ["conversation_id", "mission_id", "bot_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class CrewBotTool(
    val conversation_id: String,
    val mission_id: String,
    val bot_id: String,
    val ordinal: Int,
    val tool: String,
)

/** Message row nested under a `crew_snapshot`; exact UTF-16 text is stored as raw code-unit bytes. */
@Entity(
    tableName = "crew_messages",
    primaryKeys = ["conversation_id", "mission_id", "ordinal"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CrewMission::class, parentColumns = ["conversation_id", "mission_id"], childColumns = ["conversation_id", "mission_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_crew_messages_id", value = ["conversation_id", "mission_id", "message_id"], unique = true),
        Index(name = "idx_crew_messages_coordination", value = ["conversation_id", "coordination_evidence_id"]),
    ],
)
data class CrewMessage(
    val conversation_id: String,
    val mission_id: String,
    val ordinal: Int,
    val message_id: String,
    val from_bot: String?,
    val to_bot: String?,
    val type: String,
    val text: ByteArray,
    val timestamp_ms: Long,
    val origin: String? = null,
    /** Evidence identity, referenced message, and digest let M2 verify the snapshot copy in this conversation. */
    val coordination_evidence_id: String? = null,
    val reference_message_id: String? = null,
    val evidence_content_sha256: String? = null,
) {
    override fun equals(other: Any?): Boolean = this === other || other is CrewMessage &&
        conversation_id == other.conversation_id && mission_id == other.mission_id && ordinal == other.ordinal &&
        message_id == other.message_id && from_bot == other.from_bot && to_bot == other.to_bot && type == other.type &&
        text.contentEquals(other.text) && timestamp_ms == other.timestamp_ms && origin == other.origin &&
        coordination_evidence_id == other.coordination_evidence_id &&
        reference_message_id == other.reference_message_id && evidence_content_sha256 == other.evidence_content_sha256

    override fun hashCode(): Int = 31 * listOf(conversation_id, mission_id, ordinal, message_id, from_bot, to_bot,
        type, timestamp_ms, origin, coordination_evidence_id, reference_message_id, evidence_content_sha256).hashCode() +
        text.contentHashCode()
}

/** Full CrewInputBinding identity, normalized because every field has a validated schema. */
@Entity(
    tableName = "crew_message_input_bindings",
    primaryKeys = ["conversation_id", "mission_id", "message_id"],
    foreignKeys = [ForeignKey(
        entity = CrewMessage::class,
        parentColumns = ["conversation_id", "mission_id", "message_id"],
        childColumns = ["conversation_id", "mission_id", "message_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class CrewMessageInputBinding(
    val conversation_id: String,
    val mission_id: String,
    val message_id: String,
    val schema_version: Int,
    val source_kind: String,
    val source_id: String,
    val owner_central_session_id: String,
    val workspace_id: String,
    val execution_session_id: String,
    val assignment_id: String,
    val input_mission_id: String,
    val bot_id: String,
    val mailbox_message_id: String,
    val source_payload_sha256: String,
    val delivery_sha256: String,
    val admission_cycle_id: String,
)

/** Full ToolActivity object retained with the crew message; no activity fields are opaque. */
@Entity(
    tableName = "crew_message_activities",
    primaryKeys = ["conversation_id", "mission_id", "message_id"],
    foreignKeys = [ForeignKey(
        entity = CrewMessage::class,
        parentColumns = ["conversation_id", "mission_id", "message_id"],
        childColumns = ["conversation_id", "mission_id", "message_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(name = "idx_crew_message_activities_event", value = ["conversation_id", "event_id"], unique = true)],
)
data class CrewMessageActivity(
    val conversation_id: String,
    val mission_id: String,
    val message_id: String,
    val schema_version: Int,
    val event_id: String,
    val execution_id: String,
    val call_id: String,
    val tool_name: String,
    val display_name: String,
    val skill_id: String,
    val skill_name: String,
    val stage: String,
    val detail: String,
    val preview_id: String,
    val audit_detail: String,
    val reflection_source: String,
    val timestamp_millis: Long,
)

/** Reference nested under a crew snapshot message. */
@Entity(
    tableName = "crew_message_refs",
    primaryKeys = ["conversation_id", "mission_id", "message_id", "ordinal"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CrewMessage::class, parentColumns = ["conversation_id", "mission_id", "message_id"], childColumns = ["conversation_id", "mission_id", "message_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class CrewMessageRef(
    val conversation_id: String,
    val mission_id: String,
    val message_id: String,
    val ordinal: Int,
    val ref: String,
)

/** `crew_result_evidence`; free-form result text is an exact UTF-16 BLOB. */
@Entity(
    tableName = "crew_result_evidence",
    primaryKeys = ["conversation_id", "evidence_id"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_crew_result_evidence_mission", value = ["conversation_id", "mission_id", "bot_id", "cycle_id"]),
        Index(name = "idx_crew_result_evidence_seq", value = ["conversation_id", "seq"]),
    ],
)
data class CrewResultEvidence(
    val conversation_id: String,
    val evidence_id: String,
    val seq: Long,
    val schema_version: Int,
    val mission_id: String,
    val bot_id: String,
    val cycle_id: String,
    val reported_status: String,
    val result_text: ByteArray,
    val produced_at_ms: Long,
    val content_sha256: String,
    // Local privacy metadata, deliberately outside the immutable worker payload/hash.
    @androidx.room.ColumnInfo(defaultValue = "0") val memory_projection_suppressed: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = this === other || other is CrewResultEvidence &&
        evidence_id == other.evidence_id && conversation_id == other.conversation_id && seq == other.seq &&
        schema_version == other.schema_version && mission_id == other.mission_id && bot_id == other.bot_id &&
        cycle_id == other.cycle_id && reported_status == other.reported_status && result_text.contentEquals(other.result_text) &&
        produced_at_ms == other.produced_at_ms && content_sha256 == other.content_sha256

    override fun hashCode(): Int = 31 * listOf(evidence_id, conversation_id, seq, schema_version, mission_id, bot_id,
        cycle_id, reported_status, produced_at_ms, content_sha256).hashCode() + result_text.contentHashCode()
}

private fun nullableBytesEqual(left: ByteArray?, right: ByteArray?): Boolean =
    (left == null && right == null) || (left != null && right != null && left.contentEquals(right))

/** UTF-16-preserving conversion for strict crew/workspace journal text BLOBs. */
object ExactUtf16 {
    fun encode(text: String): ByteArray {
        val bytes = ByteArray(text.length * 2)
        for (i in text.indices) {
            val codeUnit = text[i].code
            bytes[i * 2] = (codeUnit and 0xff).toByte()
            bytes[i * 2 + 1] = (codeUnit ushr 8).toByte()
        }
        return bytes
    }

    fun decode(bytes: ByteArray): String {
        require(bytes.size % 2 == 0) { "UTF-16 code-unit BLOB must have an even byte count" }
        val chars = CharArray(bytes.size / 2)
        for (i in chars.indices) {
            val low = bytes[i * 2].toInt() and 0xff
            val high = bytes[i * 2 + 1].toInt() and 0xff
            chars[i] = ((high shl 8) or low).toChar()
        }
        return String(chars)
    }
}

