package com.jarvys.agent.chatdb

import androidx.room.Entity
import androidx.room.ForeignKey

/** Exact native provenance; absence remains absence. Payload strings use ExactUtf16 bytes. */
@Entity(tableName = "crew_message_input_provenance",
    primaryKeys = ["conversation_id", "mission_id", "message_id"],
    foreignKeys = [ForeignKey(entity = CrewMessage::class,
        parentColumns = ["conversation_id", "mission_id", "message_id"],
        childColumns = ["conversation_id", "mission_id", "message_id"], onDelete = ForeignKey.CASCADE)])
internal data class CrewMessageInputProvenance(
    val conversation_id: String,
    val mission_id: String,
    val message_id: String,
    val source_session_id: ByteArray,
    val workspace_id: ByteArray,
    val assignment_id: ByteArray,
    val bot_id: ByteArray,
    val source_message_id: ByteArray,
    val kind: String,
    val principal_role: String,
    val correlation_message_id: ByteArray,
    val reference_id: ByteArray,
    val content_sha256: ByteArray,
    val assignment_revision: Long,
    val request_id: ByteArray,
    val input_mission_id: ByteArray,
    val cycle_id: ByteArray,
)
