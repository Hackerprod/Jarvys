package com.jarvys.agent.chatdb

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** A runtime-authored result input binding; it is not evidence that Central consumed the result. */
@Entity(tableName = "mission_episode_inputs", primaryKeys = ["conversation_id", "message_id"],
    foreignKeys = [ForeignKey(entity = Message::class,
        parentColumns = ["conversation_id", "message_id"], childColumns = ["conversation_id", "message_id"],
        onDelete = ForeignKey.CASCADE)],
    indices = [Index(name = "idx_mission_episode_inputs_source",
        value = ["execution_session_id", "mission_id", "bot_id", "cycle_id"])])
data class MissionEpisodeInput(
    val conversation_id: String,
    val message_id: String,
    val execution_session_id: String,
    val workspace_id: String,
    val assignment_id: String,
    val mission_id: String,
    val bot_id: String,
    val cycle_id: String,
    val result_text_sha256: String,
)
