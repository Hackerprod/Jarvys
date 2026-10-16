package com.jarvys.agent.chatdb

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "agent_profiles")
data class AgentProfile(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val creature: String,
    val vibe: String,
    val emoji: String,
    val avatar_ref: String?,
    val bootstrap_completed: Boolean,
    val revision: Long,
    val updated_at: Long,
)

@Entity(tableName = "agent_docs", primaryKeys = ["agent_id", "kind"])
data class AgentDoc(
    val agent_id: String,
    val kind: String,
    val content: String,
    val revision: Long,
    val content_hash: String,
    val updated_at: Long,
)

@Entity(
    tableName = "user_directives",
    indices = [
        Index(name = "idx_user_directives_active", value = ["scope", "section", "status"]),
        Index(name = "idx_user_directives_source", value = ["source_message_id"]),
    ],
)
data class UserDirective(
    @androidx.room.PrimaryKey val id: String,
    val scope: String,
    val section: String,
    val directive: String,
    val status: String,
    val observed_at: Long,
    val source_message_id: String?,
    val evidence: String,
    val provenance: String,
    val superseded_by: String?,
)

@Entity(
    tableName = "memory_entries",
    indices = [
        Index(name = "idx_memory_entries_scope_layer_day", value = ["scope", "layer", "day"]),
        Index(name = "idx_memory_entries_deleted", value = ["deleted_at"]),
    ],
)
data class MemoryEntry(
    @androidx.room.PrimaryKey val id: String,
    val scope: String,
    val layer: String,
    val day: String?,
    val text: String,
    val category: String?,
    val importance: Double,
    val triggers: String,
    val provenance: String,
    val source_ref: String?,
    val usage_count: Long,
    val last_used_at: Long?,
    val created_at: Long,
    val updated_at: Long,
    val deleted_at: Long?,
)

@Entity(tableName = "memory_revisions", indices = [Index(name = "idx_memory_revisions_target", value = ["target_type", "target_id", "created_at"])])
data class MemoryRevision(
    @androidx.room.PrimaryKey val id: String,
    val target_type: String,
    val target_id: String,
    val before_content: String?,
    val after_content: String?,
    val actor: String,
    val cause: String,
    val created_at: Long,
)

@Entity(tableName = "agent_session_state")
data class AgentSessionState(
    @androidx.room.PrimaryKey val conversation_id: String,
    val kind: String,
    val compaction_count: Long,
    val memory_flush_compaction_count: Long,
    val saved_turn_count: Long,
    val bootstrap_completed: Boolean,
    val updated_at: Long,
)

@Entity(
    tableName = "memory_jobs",
    indices = [Index(name = "idx_memory_jobs_ready", value = ["state", "available_at"])],
    foreignKeys = [ForeignKey(
        entity = Conversation::class,
        parentColumns = ["id"],
        childColumns = ["source_conversation_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class MemoryJob(
    @androidx.room.PrimaryKey val id: String,
    val job_type: String,
    val scope: String,
    val source_conversation_id: String?,
    val state: String,
    val lease_owner: String?,
    val lease_expires_at: Long?,
    val attempts: Long,
    val available_at: Long,
    val last_error: String?,
    val created_at: Long,
    val updated_at: Long,
)
