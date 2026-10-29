package com.jarvys.agent.chatdb

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import androidx.room.ForeignKey

/** A memory execution, never a Conversation or a WorkspaceRegistry row. */
@Entity(tableName = "memory_sessions", indices = [Index(value = ["project"]), Index(value = ["source_generation", "owner_conversation_id", "owner_message_id"]), Index(value = ["resume_event_key"], unique = true)])
data class MemorySession(
    @PrimaryKey val id: String,
    val project: String,
    val ownership_mode: String,
    val state: String,
    val started_at: Long,
    val ended_at: Long? = null,
    val source_generation: String? = null,
    val owner_conversation_id: String? = null,
    val owner_message_id: String? = null,
    val run_id: String? = null,
    val process_id: String? = null,
    val runtime_lease_expires_at: Long? = null,
    val runtime_root_id: String? = null,
    val resume_event_key: String? = null,
    val summary: String? = null,
    val maintenance_job_id: String? = null,
    val maintenance_lease_owner: String? = null,
    val compaction_parent_session_id: String? = null,
    val mission_execution_session_id: String? = null,
    val mission_evidence_id: String? = null,
    val mission_assignment_id: String? = null,
)

@Entity(tableName = "memory_prompts", foreignKeys = [ForeignKey(entity = MemorySession::class,
    parentColumns = ["id"], childColumns = ["session_id"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["session_id", "source_inbox_id"], unique = true), Index(value = ["project"])])
data class MemoryPrompt(@PrimaryKey val id: String, val session_id: String, val project: String,
    val content: String, val source_inbox_id: String?, val created_at: Long)

/** Local source-deletion evidence has no payload and no cascading session/chat FK. */
@Entity(tableName = "memory_prompt_tombstones", primaryKeys = ["session_id", "source_inbox_id"],
    indices = [Index(value = ["project"])])
data class MemoryPromptTombstone(val session_id: String, val source_inbox_id: String,
    val prompt_id: String, val project: String, val deleted_at: Long)

@Entity(tableName = "memory_session_tombstones", indices = [Index(value = ["project"])])
data class MemorySessionTombstone(@PrimaryKey val id: String, val project: String, val deleted_at: Long)

/** Local atomic-finalization identity; deleting the summary does not erase its receipt. */
@Entity(tableName = "memory_session_finalizations", foreignKeys = [ForeignKey(entity = MemorySession::class,
    parentColumns = ["id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE)])
data class MemorySessionFinalization(@PrimaryKey val session_id: String,
    val summary_id: String, val created_at: Long)
