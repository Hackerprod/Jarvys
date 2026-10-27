package com.jarvys.agent.chatdb

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Technical ledger deliberately has no FK to Central messages or semantic memory entries. */
@Entity(tableName = "coding_job_bindings", indices = [Index(value = ["submission_id"], unique = true)])
data class CodingJobBindingRow(
    @PrimaryKey val job_id: String,
    val submission_id: String,
    val technical_generation: String,
    val binding_json: String,
)

@Entity(tableName = "coding_job_observations", primaryKeys = ["job_id", "revision"],
    foreignKeys = [ForeignKey(entity = CodingJobBindingRow::class, parentColumns = ["job_id"],
        childColumns = ["job_id"], onDelete = ForeignKey.NO_ACTION)],
    indices = [Index(value = ["job_id"])])
data class CodingJobObservationRow(
    val job_id: String,
    val revision: Long,
    val observation_kind: String,
    val evidence_json: String,
    val payload_sha256: String,
    val payload_bytes: ByteArray,
)

/** Persisted eligibility metadata only: no serialized memory ticket or reconstructed permission. */
@Entity(tableName = "coding_job_semantic_links",
    foreignKeys = [ForeignKey(entity = CodingJobBindingRow::class, parentColumns = ["job_id"],
        childColumns = ["job_id"], onDelete = ForeignKey.NO_ACTION)],
    indices = [Index(value = ["target_entry_id"])])
data class CodingJobSemanticLinkRow(
    @PrimaryKey val job_id: String,
    val target_entry_id: String?,
    val target_fingerprint: String?,
    val status: String,
    val admitted: Boolean,
    val terminal_confirmed: Boolean,
    val submission_ordinal: Long?,
    val execution_ordinal: Long?,
)
