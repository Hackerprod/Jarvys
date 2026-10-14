package com.jarvys.agent.chatdb

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Durable generation lifecycle/checkpoint rows; M1 repository enforces state-machine CHECK equivalents. */
@Entity(
    tableName = "generation_run",
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Message::class, parentColumns = ["conversation_id", "message_id"], childColumns = ["conversation_id", "message_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_generation_run_active_target", value = ["active_target_key"], unique = true),
        Index(name = "idx_generation_run_message", value = ["conversation_id", "message_id"]),
        Index(name = "idx_generation_run_state_updated", value = ["state", "updated_at", "id"]),
    ],
)
data class GenerationRun(
    @androidx.room.PrimaryKey val id: String,
    val conversation_id: String,
    val message_id: String,
    val state: String,
    val state_revision: Long,
    val checkpoint_seq: Long,
    val created_at: Long,
    val updated_at: Long,
    val terminal_at: Long? = null,
    val error_code: String? = null,
    /**
     * Collision-free key in the form `<conversation length>:<conversation><message length>:<message>`
     * for preparing/requesting/streaming/waiting_tool runs; NULL for terminal runs. M1 must update
     * this column in the same transaction as state.
     */
    val active_target_key: String? = null,
)

/** Versioned key/value receipts for storage identity and migration progress. */
@Entity(tableName = "storage_meta")
data class StorageMeta(
    @androidx.room.PrimaryKey val key: String,
    val value: String,
)

