package com.jarvys.agent.chatdb

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** `compaction` rows; compaction_id persists the legacy-derived identifier. */
@Entity(
    tableName = "compactions",
    primaryKeys = ["conversation_id", "compaction_id"],
    foreignKeys = [ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)],
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
    ],
    indices = [Index(name = "idx_compaction_acks_seq", value = ["conversation_id", "seq"])],
)
data class CompactionReflectionAck(
    val conversation_id: String,
    val compaction_id: String,
    val seq: Long,
    val source: String,
)

/** `reflection_checkpoint`, `reflection_commit`, `reflection_event`, and `reflection_undo` rows. */
@Entity(
    tableName = "reflections",
    primaryKeys = ["conversation_id", "seq"],
    foreignKeys = [ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)],
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
    foreignKeys = [ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(name = "idx_crew_missions_snapshot_seq", value = ["conversation_id", "last_snapshot_seq"])],
)
data class CrewMission(
    val conversation_id: String,
    val mission_id: String,
    val last_snapshot_seq: Long,
    val schema_version: Int,
    val process_id: String?,
    val title: String,
    val original_instructions: String,
    val title_source: String? = null,
    val status: String,
    val synthesis: String? = null,
    val started_at_ms: Long,
    val finished_at_ms: Long? = null,
)

/** Latest bot state nested under a `crew_snapshot` mission. */
@Entity(
    tableName = "crew_bots",
    primaryKeys = ["conversation_id", "mission_id", "bot_id"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CrewMission::class, parentColumns = ["conversation_id", "mission_id"], childColumns = ["conversation_id", "mission_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CrewResultEvidence::class, parentColumns = ["evidence_id"], childColumns = ["result_evidence_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_crew_bots_ordinal", value = ["conversation_id", "mission_id", "ordinal"], unique = true),
        Index(name = "idx_crew_bots_evidence", value = ["result_evidence_id"]),
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
    val error: String? = null,
    val result: String? = null,
    val waiting_reason: String? = null,
    val phase: String? = null,
    val last_progress: String? = null,
    val last_progress_at_ms: Long? = null,
    val resume_required: Boolean,
    val can_resume: Boolean,
    val recovery_note: String? = null,
    val started_at_ms: Long? = null,
    val finished_at_ms: Long? = null,
    val result_evidence_id: String? = null,
)

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
        ForeignKey(entity = CrewCoordinationEvidence::class, parentColumns = ["evidence_id"], childColumns = ["coordination_evidence_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_crew_messages_id", value = ["conversation_id", "mission_id", "message_id"], unique = true),
        Index(name = "idx_crew_messages_coordination", value = ["coordination_evidence_id"]),
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
    val coordination_evidence_id: String? = null,
    /** Crew inputBinding has not been structurally inventoried and remains opaque JSON. */
    val input_binding_json: String? = null,
    val activity_event_id: String? = null,
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
    foreignKeys = [ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(name = "idx_crew_result_evidence_mission", value = ["conversation_id", "mission_id", "bot_id", "cycle_id"]),
        Index(name = "idx_crew_result_evidence_seq", value = ["conversation_id", "seq"]),
    ],
)
data class CrewResultEvidence(
    @androidx.room.PrimaryKey val evidence_id: String,
    val conversation_id: String,
    val seq: Long,
    val mission_id: String,
    val bot_id: String,
    val cycle_id: String,
    val reported_status: String,
    val result_text: ByteArray,
    val produced_at_ms: Long,
    val content_sha256: String,
)

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

