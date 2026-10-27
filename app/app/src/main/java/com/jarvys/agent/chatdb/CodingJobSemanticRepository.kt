package com.jarvys.agent.chatdb

import com.jarvys.agent.CentralInvocationSource
import com.jarvys.agent.coding.CodingJobEvidence
import com.jarvys.agent.coding.CodingJobEvidenceCodec
import com.jarvys.agent.coding.CodingJobJournalStore
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Exact existing targets only. Caller owns the current Room transaction and (for roles) memory gate. */
internal class CodingJobSemanticRepository(private val database: JarvysChatDatabase) {
    private val sql get() = database.chatSql

    fun capture(binding: CodingJobEvidence.Binding) {
        val existing = sql.query("SELECT 1 FROM coding_job_semantic_links WHERE job_id=?", arrayOf(binding.jobId)).use { it.moveToFirst() }
        if (existing) return // Never recapture or backfill a previously absent/disabled target.
        var status = if (binding.semanticCapture == CodingJobEvidence.SemanticCapture.DISABLED) "DISABLED" else "ABSENT"
        var target: String? = null
        var fingerprint: String? = null
        val root = binding.source.rootCentral
        if (status != "DISABLED" && root != null && root.storageGeneration == binding.technicalStoreGeneration) {
            val candidates = sql.query(
                "SELECT DISTINCT m.id FROM memory_entries m JOIN memory_evidence e ON e.entry_id=m.id " +
                    "WHERE m.kind='episode' AND m.deleted_at IS NULL AND m.source_conversation_id=? AND m.source_message_id=? " +
                    "AND e.ref_kind='tool_call' AND e.ref_conversation_id=? AND e.source_batch_id=? AND e.ref_id=? AND e.run_id=? AND e.process_id=? LIMIT 2",
                arrayOf(root.ownerCentralSessionId, root.sourceMessageId, root.ownerCentralSessionId,
                    root.sourceBatchId, root.sourceCallId, root.sourceRunId, root.sourceProcessId)).use { c -> buildList {
                while (c.moveToNext()) add(c.getString(0))
            } }
            if (candidates.size == 1) {
                fingerprint = targetFingerprint(candidates.single(), root)
                if (fingerprint != null) { target = candidates.single(); status = "PENDING" }
            }
        }
        sql.execSQL("INSERT INTO coding_job_semantic_links(job_id,target_entry_id,target_fingerprint,status,admitted,terminal_confirmed,submission_ordinal,execution_ordinal) VALUES(?,?,?,?,0,0,NULL,NULL)",
            arrayOf(binding.jobId, target, fingerprint, status))
    }

    fun recordTechnical(record: CodingJobEvidence.Record, kind: CodingJobRepository.Kind) {
        if (kind == CodingJobRepository.Kind.SUBMISSION_ACCEPTED)
            sql.execSQL("UPDATE coding_job_semantic_links SET admitted=1 WHERE job_id=?", arrayOf(record.binding.jobId))
        if (record.terminal != null)
            sql.execSQL("UPDATE coding_job_semantic_links SET terminal_confirmed=1 WHERE job_id=?", arrayOf(record.binding.jobId))
    }

    fun suppress(jobId: String) {
        sql.execSQL("UPDATE coding_job_semantic_links SET status='SUPPRESSED' WHERE job_id=? AND status='PENDING'", arrayOf(jobId))
    }

    fun linkTerminal(commit: CodingJobJournalStore.Commit) {
        val record = commit.record(); check(record.terminal != null)
        val exactTechnical = sql.query("SELECT payload_sha256,evidence_json FROM coding_job_observations WHERE job_id=? AND revision=?",
            arrayOf(record.binding.jobId, record.revision)).use { c ->
            c.moveToFirst() && c.getString(0) == commit.payloadSha256() && c.getString(1) == CodingJobEvidenceCodec.encode(record)
        }
        check(exactTechnical) { "Semantic roles require the exact confirmed technical terminal" }
        val accepted = sql.query("SELECT 1 FROM coding_job_observations WHERE job_id=? AND observation_kind='SUBMISSION_ACCEPTED' LIMIT 1",
            arrayOf(record.binding.jobId)).use { it.moveToFirst() }
        if (!accepted) { suppress(record.binding.jobId); return } // Restrictive abandonment is never an ACK.
        val link = sql.query("SELECT target_entry_id,target_fingerprint,status,admitted,terminal_confirmed FROM coding_job_semantic_links WHERE job_id=?",
            arrayOf(record.binding.jobId)).use { c ->
            if (!c.moveToFirst()) null else Link(if (c.isNull(0)) null else c.getString(0), if (c.isNull(1)) null else c.getString(1), c.getString(2), c.getInt(3) != 0, c.getInt(4) != 0)
        } ?: error("Missing coding semantic link metadata")
        if (link.status != "PENDING") return
        val root = record.binding.source.rootCentral
        if (!link.admitted || !link.terminal || link.target == null || root == null ||
            targetFingerprint(link.target, root) != link.fingerprint) { suppress(record.binding.jobId); return }
        val target = link.target
        val firstOrdinal = sql.query("SELECT COALESCE(MAX(ordinal),-1)+1 FROM memory_evidence WHERE entry_id=?", arrayOf(target))
            .use { check(it.moveToFirst()); it.getLong(0) }
        val now = System.currentTimeMillis()
        fun insert(ordinal: Long, role: String, outcome: String?, semantics: String) {
            val facts = JSONObject().put("completion_semantics", semantics).put("goal_verified", false)
                .put("technical_generation", record.binding.technicalStoreGeneration).put("job_id", record.binding.jobId)
                .put("revision", record.revision).put("input_coverage", record.binding.source.crew.inputContext.coverage.name.lowercase(java.util.Locale.ROOT))
                .put("root_is_historical", true).put("historical_root_is_exclusive_cause", false)
            sql.execSQL("INSERT INTO memory_evidence(entry_id,ordinal,ref_kind,ref_conversation_id,ref_id,at_ms,tool_name,outcome,authorization,facts_json,connector,source_batch_id,run_id,process_id) VALUES(?,?,?,?,?,?,'project_exec',?,NULL,?,NULL,NULL,NULL,?)",
                arrayOf(target, ordinal, role, record.binding.source.crew.executionSessionId, record.binding.jobId, now,
                    outcome, facts.toString(), record.binding.ownerProcessId))
        }
        insert(firstOrdinal, "coding_job_submission", null, "async_submission")
        insert(firstOrdinal + 1, "coding_job_execution", outcome(record), "durable_process_terminal")
        sql.execSQL("UPDATE coding_job_semantic_links SET status='LINKED',submission_ordinal=?,execution_ordinal=? WHERE job_id=? AND status='PENDING'",
            arrayOf(firstOrdinal, firstOrdinal + 1, record.binding.jobId))
        sql.execSQL("UPDATE memory_entries SET narration='none',updated_at=? WHERE id=? AND deleted_at IS NULL", arrayOf(now, target))
    }

    private data class Link(val target: String?, val fingerprint: String?, val status: String, val admitted: Boolean, val terminal: Boolean)

    // Narration may change text/title/updated_at without changing target identity. Explicit memory
    // mutations append memory_revisions; source edits change the exact message-part fingerprint.
    private fun targetFingerprint(id: String, root: CentralInvocationSource): String? {
        val row = sql.query("SELECT m.id,m.source_conversation_id,m.source_message_id,m.created_at," +
            "(SELECT COUNT(*) FROM memory_revisions r WHERE r.target_type='memory' AND r.target_id=m.id) " +
            "FROM memory_entries m JOIN messages source ON source.conversation_id=m.source_conversation_id AND source.message_id=m.source_message_id " +
            "WHERE m.id=? AND m.kind='episode' AND m.deleted_at IS NULL AND m.source_conversation_id=? AND m.source_message_id=? " +
            "AND source.role='user' AND source.hidden_kind IS NULL AND EXISTS(SELECT 1 FROM memory_evidence e WHERE e.entry_id=m.id AND e.ref_kind='tool_call' " +
            "AND e.ref_conversation_id=? AND e.source_batch_id=? AND e.ref_id=? AND e.run_id=? AND e.process_id=?)",
            arrayOf(id, root.ownerCentralSessionId, root.sourceMessageId, root.ownerCentralSessionId,
                root.sourceBatchId, root.sourceCallId, root.sourceRunId, root.sourceProcessId)).use { c ->
            if (!c.moveToFirst()) null else JSONArray().apply { for (index in 0..4) put(if(c.isNull(index)) JSONObject.NULL else c.getString(index)) }
        } ?: return null
        sql.query("SELECT ordinal,payload FROM message_parts WHERE conversation_id=? AND message_id=? AND kind='text' ORDER BY ordinal",
            arrayOf(root.ownerCentralSessionId, root.sourceMessageId)).use { c -> while(c.moveToNext()) row.put(JSONArray().put(c.getLong(0)).put(c.getString(1))) }
        return sha256(row.toString())
    }

    fun source(entryId: String, jobId: String, maxChars: Int): JSONObject {
        val unavailable = JSONObject().put("state", "unavailable")
        require(maxChars >= unavailable.toString().length) { "Source budget cannot hold the minimal result state" }
        val row = sql.query("SELECT o.evidence_json,l.target_fingerprint,l.submission_ordinal,l.execution_ordinal,b.binding_json,b.technical_generation,b.submission_id " +
            "FROM coding_job_semantic_links l JOIN memory_entries m ON m.id=l.target_entry_id " +
            "JOIN coding_job_bindings b ON b.job_id=l.job_id JOIN coding_job_observations o ON o.job_id=l.job_id " +
            "WHERE l.job_id=? AND l.target_entry_id=? AND l.status='LINKED' AND l.admitted=1 " +
            "AND l.terminal_confirmed=1 AND m.deleted_at IS NULL ORDER BY o.revision DESC LIMIT 1", arrayOf(jobId, entryId))
            .use { c -> if (!c.moveToFirst() || c.isNull(1) || c.isNull(2) || c.isNull(3)) null else
                SourceRow(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getString(4), c.getString(5), c.getString(6)) } ?: return unavailable
        val encoded = row.encoded
        val record = CodingJobEvidenceCodec.decode(encoded, encoded.length).record
        val bound = CodingJobEvidenceCodec.decode(row.binding, row.binding.length).record.binding
        if (record.terminal == null || record.binding != bound || bound.jobId != jobId ||
            bound.submissionId != row.submission || bound.technicalStoreGeneration != row.generation ||
            currentGeneration() != row.generation) return unavailable
        val root = bound.source.rootCentral ?: return unavailable
        if (targetFingerprint(entryId, root) != row.fingerprint) return unavailable
        fun exactRole(ordinal: Long, kind: String): Boolean = sql.query(
            "SELECT ref_kind,ref_id,ref_conversation_id,tool_name FROM memory_evidence WHERE entry_id=? AND ordinal=?",
            arrayOf(entryId, ordinal)).use { c -> c.moveToFirst() && c.getString(0) == kind && c.getString(1) == jobId &&
                c.getString(2) == bound.source.crew.executionSessionId && c.getString(3) == "project_exec" }
        if (row.submissionOrdinal == row.executionOrdinal || !exactRole(row.submissionOrdinal, "coding_job_submission") ||
            !exactRole(row.executionOrdinal, "coding_job_execution")) return unavailable
        val source = JSONObject(encoded).getJSONObject("binding").getJSONObject("source")
        val result = JSONObject().put("state", "retained").put("job_id", jobId).put("revision", record.revision)
            .put("technical_generation", record.binding.technicalStoreGeneration).put("execution_state", record.terminal.state.name)
            .put("exit_code", record.terminal.exitCode ?: JSONObject.NULL).put("outcome", outcome(record))
            .put("goal_verified", false).put("root_is_historical", true).put("historical_root_is_exclusive_cause", false)
            .put("input_coverage", record.binding.source.crew.inputContext.coverage.name.lowercase(java.util.Locale.ROOT))
            .put("sourceRefs", source.getJSONObject("crew").getJSONObject("inputContext").getJSONArray("references"))
            .put("unresolvedInputIds", source.getJSONObject("crew").getJSONObject("inputContext").getJSONArray("unresolvedInputIds"))
        return if(result.toString().length <= maxChars) result else JSONObject().put("state", "bounded")
    }

    private data class SourceRow(val encoded: String, val fingerprint: String, val submissionOrdinal: Long,
        val executionOrdinal: Long, val binding: String, val generation: String, val submission: String)

    private fun currentGeneration(): String? = sql.query("SELECT value FROM storage_meta WHERE key=?",
        arrayOf(ConnectionProjectionRepository.GENERATION_KEY)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** Read-only eligibility: an old pending job must not fence a different, edited target. */
    fun isCurrentTarget(entryId: String, jobId: String): Boolean {
        val row = sql.query("SELECT l.target_fingerprint,b.binding_json,b.technical_generation FROM coding_job_semantic_links l " +
            "JOIN coding_job_bindings b ON b.job_id=l.job_id WHERE l.job_id=? AND l.target_entry_id=?",
            arrayOf(jobId, entryId)).use { c -> if (!c.moveToFirst() || c.isNull(0)) null else
                Triple(c.getString(0), c.getString(1), c.getString(2)) } ?: return false
        val binding = CodingJobEvidenceCodec.decode(row.second, row.second.length).record.binding
        val root = binding.source.rootCentral ?: return false
        return binding.jobId == jobId && binding.technicalStoreGeneration == row.third &&
            currentGeneration() == row.third && targetFingerprint(entryId, root) == row.first
    }

    companion object {
        private fun outcome(record: CodingJobEvidence.Record): String {
            val terminal = requireNotNull(record.terminal)
            if (terminal.uncertaintyObserved) return "uncertain"
            return when(terminal.state) {
                CodingJobEvidence.TerminalState.SUCCEEDED -> "ok"
                CodingJobEvidence.TerminalState.FAILED -> "error"
                CodingJobEvidence.TerminalState.NOT_STARTED ->
                    if (terminal.absenceCoverage == CodingJobEvidence.AbsenceCoverage.NO_PREPARATION_OR_DISPATCH &&
                        record.highestEffectPhase == CodingJobEvidence.Phase.SUBMISSION_INTENT) "not_executed" else "uncertain"
                else -> "uncertain" // Cancellation/timeout/interruption do not prove absence of effects.
            }
        }
        private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
    }
}
