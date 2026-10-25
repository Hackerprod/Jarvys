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
        Index(name = "idx_memory_entries_layer_day", value = ["layer", "day"]),
        Index(name = "idx_memory_entries_deleted", value = ["deleted_at"]),
        Index(name = "idx_memory_entries_kind_event", value = ["kind", "event_at"]),
        Index(name = "idx_memory_entries_source_message", value = ["source_message_id"]),
    ],
)
data class MemoryEntry(
    @androidx.room.PrimaryKey val id: String,
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
    @androidx.room.ColumnInfo(defaultValue = "'note'") val kind: String = "note",
    val topic_key: String? = null,
    val title: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "'none'") val narration: String = "none",
    val event_at: Long? = null,
    val source_conversation_id: String? = null,
    val source_message_id: String? = null,
    val through_seq: Long? = null,
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

/** Runtime-written pointers and neutral facts behind an episode; the model never writes these rows. */
@Entity(tableName = "memory_evidence", primaryKeys = ["entry_id", "ordinal"], foreignKeys = [
    ForeignKey(entity = MemoryEntry::class, parentColumns = ["id"], childColumns = ["entry_id"], onDelete = ForeignKey.CASCADE),
], indices = [
    Index(name = "idx_memory_evidence_ref", value = ["ref_conversation_id", "ref_id"]),
    Index(name = "idx_memory_evidence_tool", value = ["tool_name", "outcome"]),
    Index(name = "idx_memory_evidence_connector", value = ["connector"]),
])
data class MemoryEvidence(
    val entry_id: String,
    val ordinal: Long,
    val ref_kind: String,
    val ref_conversation_id: String?,
    val ref_id: String,
    val action_id: String?,
    val attempt_id: String?,
    val at_ms: Long,
    val tool_name: String?,
    val outcome: String?,
    val authorization: String?,
    val facts_json: String,
    /** Connector or MCP server id of the call, so a structured search can filter on it without parsing JSON. */
    val connector: String? = null,
)

@Entity(tableName = "memory_links", primaryKeys = ["from_id", "to_id", "relation"], foreignKeys = [
    ForeignKey(entity = MemoryEntry::class, parentColumns = ["id"], childColumns = ["from_id"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = MemoryEntry::class, parentColumns = ["id"], childColumns = ["to_id"], onDelete = ForeignKey.CASCADE),
], indices = [Index(value = ["to_id"])])
data class MemoryLink(
    val from_id: String,
    val to_id: String,
    val relation: String,
    val actor: String,
    val reason: String?,
    val created_at: Long,
)

@Entity(tableName = "transcript_artifact_refs", indices = [Index(value = ["conversation_id"])],
    foreignKeys = [ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)])
data class TranscriptArtifactRef(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversation_id: String,
    val owner_kind: String,
    val batch_id: String,
    val call_id: String?,
    val artifact_id: String,
)
