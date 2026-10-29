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
    foreignKeys = [ForeignKey(entity = MemorySession::class, parentColumns = ["id"], childColumns = ["memory_session_id"], onDelete = ForeignKey.RESTRICT)],
    indices = [
        Index(value = ["memory_session_id"]),
        Index(name = "idx_memory_entries_layer_day", value = ["layer", "day"]),
        Index(name = "idx_memory_entries_deleted", value = ["deleted_at"]),
        Index(name = "idx_memory_entries_kind_event", value = ["kind", "event_at"]),
        Index(name = "idx_memory_entries_source_message", value = ["source_message_id"]),
        Index(name = "idx_memory_entries_topic_key", value = ["topic_key"]),
        Index(name = "idx_memory_entries_content_dedupe",
            value = ["normalized_hash", "project", "scope", "observation_type", "title", "created_at"],
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.ASC, Index.Order.ASC, Index.Order.ASC, Index.Order.DESC]),
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
    @androidx.room.ColumnInfo(defaultValue = "'jarvys-central'") val project: String = "jarvys-central",
    @androidx.room.ColumnInfo(defaultValue = "'project'") val scope: String = "project",
    @androidx.room.ColumnInfo(defaultValue = "'manual'") val observation_type: String = "manual",
    @androidx.room.ColumnInfo(defaultValue = "1") val revision_count: Long = 1,
    val normalized_hash: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "1") val duplicate_count: Long = 1,
    val last_seen_at: Long? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    val memory_session_id: String? = null,
    /** Actual observation producer tool, never inferred from evidence or operation keys. */
    val tool_name: String? = null,
    val review_after: String? = null,
    val superseded_by_id: String? = null,
)

/** Payload-free identity retained after explicit permanent observation deletion. */
@Entity(tableName = "memory_deleted_observations", indices = [
    Index(name = "idx_memory_deleted_producer", value = ["project", "scope", "producer", "producer_key"], unique = true),
])
data class MemoryDeletedObservation(
    @androidx.room.PrimaryKey val old_id: String,
    val project: String,
    val scope: String,
    val deleted_at: Long,
    val producer: String?,
    val producer_key: String?,
)

@Entity(
    tableName = "memory_revisions",
    indices = [
        Index(name = "idx_memory_revisions_target", value = ["target_type", "target_id", "created_at"]),
        Index(name = "idx_memory_revisions_snapshot_version", value = ["target_type", "target_id", "snapshot_version"], unique = true),
    ],
)
data class MemoryRevision(
    @androidx.room.PrimaryKey val id: String,
    val target_type: String,
    val target_id: String,
    val before_content: String?,
    val after_content: String?,
    val actor: String,
    val cause: String,
    val created_at: Long,
    val before_title: String? = null,
    val snapshot_version: Long? = null,
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
    val memory_session_id: String? = null,
)

/** Runtime-written pointers and neutral facts behind an episode; the model never writes these rows. */
@Entity(tableName = "memory_evidence", primaryKeys = ["entry_id", "ordinal"], foreignKeys = [
    ForeignKey(entity = MemoryEntry::class, parentColumns = ["id"], childColumns = ["entry_id"], onDelete = ForeignKey.CASCADE),
], indices = [
    Index(name = "idx_memory_evidence_ref", value = ["ref_conversation_id", "ref_id"]),
    Index(name = "idx_memory_evidence_tool", value = ["tool_name", "outcome"]),
    Index(name = "idx_memory_evidence_connector", value = ["connector"]),
    Index(name = "idx_memory_evidence_batch", value = ["ref_conversation_id", "source_batch_id", "ref_id"]),
    Index(name = "idx_memory_evidence_run", value = ["run_id", "outcome"]),
    Index(name = "idx_memory_evidence_pending_process", value = ["outcome", "process_id"]),
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
    val source_batch_id: String? = null,
    val run_id: String? = null,
    val process_id: String? = null,
)

@Entity(tableName = "memory_links", primaryKeys = ["from_id", "to_id", "relation"], indices = [Index(value = ["to_id"])])
data class MemoryLink(
    val from_id: String,
    val to_id: String,
    val relation: String,
    val actor: String,
    val reason: String?,
    val created_at: Long,
    @androidx.room.ColumnInfo(defaultValue = "0") val orphaned: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "0") val updated_at: Long = 0L,
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

@Entity(tableName = "connector_autonomy_audit", indices = [Index(value = ["connector_id", "timestamp_millis", "id"])])
data class ConnectorAutonomyAudit(
    @androidx.room.PrimaryKey val id: String,
    val timestamp_millis: Long,
    val connector_id: String,
    val connector_name: String,
    val operation_name: String,
    val operation_label: String,
    val summary: String,
)

@Entity(tableName = "connector_state_events", indices = [
    Index(value = ["connector_id", "connected_at_ms"]),
    Index(value = ["store_epoch", "sequence"], unique = true),
])
data class ConnectorStateEvent(
    @androidx.room.PrimaryKey val id: String,
    val connector_id: String,
    val connected: Boolean,
    val connected_at_ms: Long,
    // Legacy rows retain NULL metadata: timestamps and UUIDs never establish protocol ordering.
    val store_epoch: String? = null,
    val sequence: Long? = null,
    val previous_choice: Boolean? = null,
    val reason: String? = null,
    val operation_id: String? = null,
    val parent_operation_id: String? = null,
    val payload_version: Int? = null,
)

/** Native save identity only; hard deletion expires its target without deleting the operation. */
@Entity(tableName = "memory_save_operations", foreignKeys = [ForeignKey(entity = MemoryEntry::class,
    parentColumns = ["id"], childColumns = ["observation_id"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index(name = "idx_memory_save_operation_target", value = ["observation_id"]),
        Index(name = "idx_memory_save_operation_created", value = ["created_at"])])
data class MemorySaveOperation(
    @androidx.room.PrimaryKey val operation_id: String,
    val fingerprint: String, val extension_fingerprint: String, val observation_id: String?,
    val project: String, val scope: String, val source_generation: String, val source_session_id: String,
    val source_owner_message_id: String, val source_batch_id: String, val source_call_id: String,
    val source_arguments_digest: String, val source_input_digest: String, val created_at: Long,
    val memory_session_id: String,
)

/** Payload-free local explicit-clear intent; not a cloud queue or a resurrection source. */
@Entity(tableName = "memory_review_clear_events", indices = [Index(value = ["event_key"], unique = true)])
data class MemoryReviewClearEventEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val event_key: String,
    val observation_id: String,
)

/** Canonical question identity; no endpoint FK and no pair uniqueness (later actors may judge independently). */
@Entity(tableName = "memory_relations", indices = [Index(value = ["relation_id"], unique = true),
    Index(value = ["source_id"]), Index(value = ["target_id"])])
data class MemoryRelationEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val relation_id: String, val source_id: String, val target_id: String,
    val project: String, val scope: String, val relation: String, val status: String,
    val created_at: Long, val updated_at: Long,
    val reason:String?=null,val evidence:String?=null,val confidence:Double?=null,
    val marked_by_actor:String?=null,val marked_by_kind:String?=null,val marked_by_model:String?=null,
    val memory_session_id:String?=null,
)
