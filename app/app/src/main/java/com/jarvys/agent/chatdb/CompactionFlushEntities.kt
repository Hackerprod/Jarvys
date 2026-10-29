package com.jarvys.agent.chatdb

import androidx.room.Entity
import androidx.room.ForeignKey
import com.jarvys.agent.ConversationTurn

@Entity(tableName = "compaction_coverage_sources",
    primaryKeys = ["conversation_id", "compaction_id", "source_kind", "source_id", "call_id"],
    foreignKeys = [ForeignKey(entity = Compaction::class, parentColumns = ["conversation_id", "compaction_id"],
        childColumns = ["conversation_id", "compaction_id"], onDelete = ForeignKey.CASCADE)])
data class CompactionCoverageSource(
    val conversation_id: String, val compaction_id: String, val source_kind: String,
    val source_id: String, val call_id: String, val source_fingerprint: String, val source_seq: Long,
)

@Entity(tableName = "compaction_flush", primaryKeys = ["conversation_id", "compaction_id"],
    foreignKeys = [ForeignKey(entity = Compaction::class, parentColumns = ["conversation_id", "compaction_id"],
        childColumns = ["conversation_id", "compaction_id"], onDelete = ForeignKey.CASCADE)])
data class CompactionFlushBinding(
    val conversation_id: String, val compaction_id: String, val coverage_hash: String?,
    val digest_id: String?, val digest_revision: Long?, val status: String,
    val memory_session_id: String? = null,
)

data class CompactionLeaf(val source: ConversationTurn.Source, val fingerprint: String, val seq: Long,
    val text: String? = null, val role: String? = null, val untrusted: Boolean = false,
    val sortSeq: Long = seq, val sortSub: Int = 0)
data class CompactionEpisode(val id: String, val title: String?, val text: String, val narration: String,
    val active: Boolean, val input: EpisodeNarrationInput)
data class CompactionCoverage(val conversationId: String, val sources: List<ConversationTurn.Source>,
    val leaves: List<CompactionLeaf>, val coverageHash: String, val fingerprint: String,
    val throughSeq: Long, val episodes: List<CompactionEpisode>, val existingDigestFingerprint: String?,
    val episodesComplete: Boolean = true,
    val sourceTexts: Map<ConversationTurn.Source, String> = emptyMap())
/** Runtime candidate, not a receipt of an already persisted digest or compacted range. */
data class PreparedCompactionFlush(val coverage: CompactionCoverage, val title: String, val text: String,
    val memoryTicket: com.jarvys.agent.MemoryWriteGate.Ticket,
    val canCommit: java.util.function.BooleanSupplier)

/** Request-local lifecycle survives a missing digest candidate. It is not a persisted receipt. */
data class CompactionMemoryAttempt(val binding:CompactionMemoryBinding,
    val memoryTicket:com.jarvys.agent.MemoryWriteGate.Ticket,
    val token:com.jarvys.agent.CancellationToken)
