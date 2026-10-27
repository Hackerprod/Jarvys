package com.jarvys.agent.chatdb

import com.jarvys.agent.coding.CodingJobEvidence
import com.jarvys.agent.coding.CodingJobEvidenceCodec
import com.jarvys.agent.coding.CodingJobJournalStore
import com.jarvys.agent.coding.CodingJobSubmission
import java.security.MessageDigest
import java.util.UUID

/** Deterministic technical facts only. No memory toggle, semantic insertion, command replay or runtime. */
internal class CodingJobRepository(private val database: JarvysChatDatabase) {
    private val sql get() = database.chatSql
    enum class Kind { SUBMISSION_ACCEPTED, PREPARATION_ADMITTED, JOURNAL_PROJECTION }

    suspend fun reserve(submission: CodingJobSubmission, source: CodingJobEvidence.Source): CodingJobEvidence.Binding =
        database.withChatWriteTransaction {
            submission.requireCaptureCurrent()
            val generation = generationInsideTransaction()
            val binding = CodingJobEvidence.Binding(generation, submission.jobId(), submission.submissionId(),
                submission.owner(), submission.scopeId(), submission.durableScopeIdentity(), submission.ownerProcessId(),
                submission.managerInstanceId(), source, submission.semanticCapture())
            val encoded = bindingJson(binding)
            sql.execSQL("INSERT OR IGNORE INTO coding_job_bindings(job_id,submission_id,technical_generation,binding_json) VALUES(?,?,?,?)",
                arrayOf(binding.jobId, binding.submissionId, generation, encoded))
            requireBinding(binding)
            binding
        }

    suspend fun record(commit: CodingJobJournalStore.Commit, kind: Kind) = database.withChatWriteTransaction {
        val reservation = commit.reservation()
        val record = commit.record()
        require(record.binding == reservation.binding()) { "Coding journal binding mismatch" }
        check(readGeneration() == record.binding.technicalStoreGeneration) { "Coding technical destination changed" }
        requireBinding(record.binding)
        val payload = commit.copyPayload()
        val digest = sha256(payload)
        check(digest == commit.payloadSha256()) { "Coding journal payload identity mismatch" }
        val encoded = CodingJobEvidenceCodec.encode(record)
        val duplicate = sql.query("SELECT observation_kind,evidence_json,payload_sha256,payload_bytes FROM coding_job_observations WHERE job_id=? AND revision=?",
            arrayOf(record.binding.jobId, record.revision)).use { c ->
            if (!c.moveToFirst()) false else {
                check(c.getString(0) == kind.name && c.getString(1) == encoded && c.getString(2) == digest && c.getBlob(3).contentEquals(payload)) {
                    "Immutable coding journal revision conflict"
                }
                true
            }
        }
        if (duplicate) return@withChatWriteTransaction
        val accepted = hasKind(record.binding.jobId, Kind.SUBMISSION_ACCEPTED)
        when (kind) {
            Kind.SUBMISSION_ACCEPTED -> check(!accepted && record.revision == 1L && record.submissionCommitted && record.highestEffectPhase == CodingJobEvidence.Phase.SUBMISSION_INTENT && record.terminal == null) {
                "Submission acceptance requires its original submission journal"
            }
            Kind.PREPARATION_ADMITTED -> check(accepted && record.highestEffectPhase == CodingJobEvidence.Phase.PREPARATION_INTENT && record.terminal == null) {
                "Preparation requires accepted submission and exact preparation intent"
            }
            Kind.JOURNAL_PROJECTION -> {
                check(accepted) { "Coding projection has no accepted submission" }
                if (record.terminal?.exitCode != null || record.highestEffectPhase == CodingJobEvidence.Phase.LAUNCH_INTENT)
                    check(hasKind(record.binding.jobId, Kind.PREPARATION_ADMITTED)) { "Launch facts lack preparation admission" }
                check(record.terminal != null || record.highestEffectPhase == CodingJobEvidence.Phase.LAUNCH_INTENT) {
                    "Generic projection cannot substitute for phase admission"
                }
            }
        }
        val previous = sql.query("SELECT evidence_json FROM coding_job_observations WHERE job_id=? ORDER BY revision DESC LIMIT 1",
            arrayOf(record.binding.jobId)).use { c ->
            if (!c.moveToFirst()) null else c.getString(0).let { CodingJobEvidenceCodec.decode(it, it.length).record }
        }
        if (previous != null) {
            // A projector may skip local journal revisions; it must never replace/regress confirmed facts.
            check(record.revision > previous.revision && record.binding == previous.binding && previous.terminal == null &&
                record.highestEffectPhase.ordinal >= previous.highestEffectPhase.ordinal &&
                (!previous.submissionCommitted || record.submissionCommitted) &&
                (previous.authorization == null || previous.authorization == record.authorization)) {
                "Coding projection regresses or replaces committed facts"
            }
        }
        sql.execSQL("INSERT INTO coding_job_observations(job_id,revision,observation_kind,evidence_json,payload_sha256,payload_bytes) VALUES(?,?,?,?,?,?)",
            arrayOf(record.binding.jobId, record.revision, kind.name, encoded, digest, payload))
    }

    private fun hasKind(jobId: String, kind: Kind): Boolean =
        sql.query("SELECT 1 FROM coding_job_observations WHERE job_id=? AND observation_kind=? LIMIT 1", arrayOf(jobId, kind.name))
            .use { it.moveToFirst() }

    private fun requireBinding(binding: CodingJobEvidence.Binding) {
        val matches = sql.query("SELECT submission_id,technical_generation,binding_json FROM coding_job_bindings WHERE job_id=?", arrayOf(binding.jobId)).use { c ->
            c.moveToFirst() && c.getString(0) == binding.submissionId && c.getString(1) == binding.technicalStoreGeneration &&
                c.getString(2) == bindingJson(binding)
        }
        check(matches) { "Immutable coding submission identity conflict" }
    }

    private fun generationInsideTransaction(): String {
        readGeneration()?.let { return it }
        val candidate = UUID.randomUUID().toString()
        sql.execSQL("INSERT OR IGNORE INTO storage_meta(key,value) VALUES(?,?)", arrayOf(ConnectionProjectionRepository.GENERATION_KEY, candidate))
        return checkNotNull(readGeneration())
    }
    private fun readGeneration(): String? = sql.query("SELECT value FROM storage_meta WHERE key=?", arrayOf(ConnectionProjectionRepository.GENERATION_KEY)).use { c ->
        if (!c.moveToFirst()) null else c.getString(0).also {
            check(runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)) { "Invalid coding destination generation" }
        }
    }
    companion object {
        private fun bindingJson(binding: CodingJobEvidence.Binding): String = CodingJobEvidenceCodec.encode(
            CodingJobEvidence.Record(binding, 1, CodingJobEvidence.Phase.SUBMISSION_INTENT, false, null, null))
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
    }
}
