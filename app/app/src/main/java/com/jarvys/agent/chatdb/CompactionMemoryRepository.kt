package com.jarvys.agent.chatdb

import com.jarvys.agent.ConversationTurn
import org.json.JSONArray
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/** Exact plan coverage and short atomic digest binding; never executes a model or effect. */
internal class CompactionMemoryRepository(private val database: JarvysChatDatabase) {
    private val sql get() = database.chatSql
    private val personal = PersonalAgentRepository(database)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun key(source: ConversationTurn.Source) = JSONArray().put(source.kind.name).put(source.sourceId).put(source.callId).toString()
    private fun rows(query: String, args: Array<out Any?>): String = sql.query(query, args).use { c ->
        val result = JSONArray()
        while (c.moveToNext()) {
            val row = JSONArray()
            for (i in 0 until c.columnCount) row.put(if (c.isNull(i)) JSONObjectNull else c.getString(i))
            result.put(row)
        }
        result.toString()
    }
    private val JSONObjectNull get() = org.json.JSONObject.NULL
    private fun scalar(query: String, args: Array<out Any?>): Long? = sql.query(query, args).use { if (it.moveToFirst()) it.getLong(0) else null }

    suspend fun prepare(conversationId: String, sources: List<ConversationTurn.Source>): CompactionCoverage? =
        database.withChatReadTransaction { prepareInside(conversationId, sources) }

    private suspend fun prepareInside(conversationId: String, sources: List<ConversationTurn.Source>): CompactionCoverage? {
        if (conversationId.isBlank() || sources.isEmpty() || sources.any { it.conversationId != conversationId }) return null
        val leaves = linkedMapOf<String, CompactionLeaf>()
        val sourceTexts = linkedMapOf<ConversationTurn.Source, String>()
        for (source in sources.distinct()) {
            if (source.kind == ConversationTurn.Source.Kind.MESSAGE) {
                sourceTexts[source] = sql.query("SELECT payload FROM message_parts WHERE conversation_id=? AND message_id=? AND kind='text' ORDER BY ordinal", arrayOf(conversationId, source.sourceId))
                    .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) }.joinToString("") }
            }
            if (source.kind == ConversationTurn.Source.Kind.COMPACTION) {
                sourceTexts[source] = sql.query("SELECT summary FROM compactions WHERE conversation_id=? AND compaction_id=?", arrayOf(conversationId, source.sourceId))
                    .use { if (it.moveToFirst()) it.getString(0) else return null }
                val parentSeq = scalar("SELECT seq FROM compactions WHERE conversation_id=? AND compaction_id=?", arrayOf(conversationId, source.sourceId)) ?: return null
                val bound = sql.query("SELECT coverage_hash FROM compaction_flush WHERE conversation_id=? AND compaction_id=?", arrayOf(conversationId, source.sourceId))
                    .use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null } ?: return null
                val inherited = sql.query("SELECT source_kind,source_id,call_id,source_fingerprint,source_seq FROM compaction_coverage_sources WHERE conversation_id=? AND compaction_id=? ORDER BY source_kind,source_id,call_id", arrayOf(conversationId, source.sourceId)).use { c -> buildList {
                    while (c.moveToNext()) {
                        val kind = runCatching { ConversationTurn.Source.Kind.valueOf(c.getString(0)) }.getOrNull() ?: return null
                        if (kind == ConversationTurn.Source.Kind.COMPACTION) return null
                        add(CompactionLeaf(ConversationTurn.Source(kind, conversationId, c.getString(1), c.getString(2)), c.getString(3), c.getLong(4)))
                    }
                } }
                if (inherited.isEmpty() || coverageKey(conversationId, inherited) != bound) return null
                for (old in inherited) {
                    val current = readLeaf(old.source) ?: return null
                    if (current.source != old.source || current.fingerprint != old.fingerprint || current.seq != old.seq || current.seq >= parentSeq) return null
                    leaves[key(current.source)] = current
                }
            } else {
                val leaf = readLeaf(source) ?: return null
                leaves[key(source)] = leaf
            }
        }
        val canonical = leaves.values.sortedBy { key(it.source) }
        if (canonical.isEmpty()) return null
        val coverageHash = coverageKey(conversationId, canonical)
        val candidates = linkedSetOf<String>()
        for (leaf in canonical) {
            val source = leaf.source
            val query = if (source.kind == ConversationTurn.Source.Kind.MESSAGE)
                "SELECT id FROM memory_entries WHERE source_conversation_id=? AND source_message_id=? AND kind='episode' AND deleted_at IS NULL"
            else "SELECT DISTINCT m.id FROM memory_evidence e JOIN memory_entries m ON m.id=e.entry_id WHERE e.ref_conversation_id=? AND e.source_batch_id=? AND m.kind='episode' AND m.deleted_at IS NULL"
            sql.query(query, arrayOf(conversationId, source.sourceId)).use { c -> while (c.moveToNext()) candidates += c.getString(0) }
        }
        val episodes = arrayListOf<CompactionEpisode>()
        var episodesComplete = true
        for (id in candidates.sorted()) {
            val input = personal.episodeNarrationInput(id) ?: return null
            var outside = false
            for (evidence in input.evidence) {
                when (evidence.refKind) {
                    "tool_call" -> {
                        val batch = evidence.sourceBatchId
                        if (batch == null) { episodesComplete = false; continue } // Structural leaves survive unproven task/legacy membership.
                        val inBatch = canonical.any { it.source.kind == ConversationTurn.Source.Kind.BATCH && it.source.sourceId == batch }
                        val inResult = canonical.any { it.source.kind == ConversationTurn.Source.Kind.BATCH_RESULT && it.source.sourceId == batch && it.source.callId == evidence.refId }
                        if (!inBatch || (evidence.outcome !in setOf("intent", "started") && !inResult)) outside = true
                    }
                    "crew_result" -> if (canonical.none { it.source.kind == ConversationTurn.Source.Kind.MESSAGE && it.source.sourceId == input.sourceMessageId }) outside = true
                    "coding_job_submission", "coding_job_execution" -> {
                        // Root lineage alone cannot cover the job's plural Crew input context.
                        // Keep this episode structural until exact source leaves can prove coverage.
                        episodesComplete = false
                    }
                    "connector_event" -> Unit // Attached metadata, not another execution.
                    else -> episodesComplete = false
                }
            }
            if (outside) episodesComplete = false // Keep structural coverage and honest pending references, never complete the digest.
            val narration = sql.query("SELECT narration FROM memory_entries WHERE id=? AND deleted_at IS NULL", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null } ?: return null
            episodes += CompactionEpisode(id, input.title, input.text, narration,
                input.evidence.any { it.outcome in setOf("intent", "started") }, input)
        }
        val digest = digestFingerprint(conversationId, coverageHash)
        val snapshot = JSONArray().put(coverageHash).put(episodesComplete).put(digest ?: JSONObjectNull)
        sourceTexts.entries.sortedBy { key(it.key) }.forEach { snapshot.put(JSONArray().put(key(it.key)).put(hash(it.value))) }
        for (episode in episodes) snapshot.put(JSONArray().put(episode.id).put(episode.narration)
            .put(rows("SELECT * FROM memory_entries WHERE id=?", arrayOf(episode.id)))
            .put(rows("SELECT * FROM memory_evidence WHERE entry_id=? ORDER BY ordinal", arrayOf(episode.id)))
            .put(episode.input.sourceFingerprint).put(episode.input.finalFingerprint))
        return CompactionCoverage(conversationId, sources.distinct().toList(), canonical, coverageHash,
            hash(snapshot.toString()), canonical.maxOf { it.seq }, episodes, digest, episodesComplete, sourceTexts.toMap())
    }

    private fun coverageKey(conversationId: String, leaves: List<CompactionLeaf>): String {
        val value = JSONArray().put(conversationId)
        leaves.sortedBy { key(it.source) }.forEach { value.put(JSONArray().put(key(it.source)).put(it.fingerprint).put(it.seq)) }
        return hash(value.toString())
    }

    private fun readLeaf(source: ConversationTurn.Source): CompactionLeaf? {
        val args = arrayOf(source.conversationId, source.sourceId)
        val body: String
        val seq: Long
        var text: String? = null
        var role: String? = null
        var untrusted = false
        when (source.kind) {
            ConversationTurn.Source.Kind.MESSAGE -> {
                val head = sql.query("SELECT seq,role FROM messages WHERE conversation_id=? AND message_id=? AND hidden_kind IS NULL", args).use { c ->
                    if (!c.moveToFirst()) return null
                    c.getLong(0) to c.getString(1)
                }
                seq = head.first
                role = head.second
                untrusted = role !in setOf("user", "assistant") || scalar(
                    "SELECT 1 FROM message_parts WHERE conversation_id=? AND message_id=? AND kind=? LIMIT 1",
                    arrayOf(source.conversationId, source.sourceId, com.jarvys.agent.ConversationInput.PROVENANCE_PART_KIND)) != null
                text = sql.query("SELECT payload FROM message_parts WHERE conversation_id=? AND message_id=? AND kind='text' ORDER BY ordinal", args)
                    .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) }.joinToString("") }
                body = rows("SELECT * FROM messages WHERE conversation_id=? AND message_id=?", args) +
                    rows("SELECT * FROM message_parts WHERE conversation_id=? AND message_id=? ORDER BY ordinal", args)
            }
            ConversationTurn.Source.Kind.BATCH -> {
                seq = scalar("SELECT seq FROM tool_batches WHERE conversation_id=? AND batch_id=?", args) ?: return null
                body = rows("SELECT * FROM tool_batches WHERE conversation_id=? AND batch_id=?", args) +
                    rows("SELECT * FROM tool_calls WHERE conversation_id=? AND batch_id=? ORDER BY ordinal", args)
            }
            ConversationTurn.Source.Kind.BATCH_RESULT -> {
                val resultArgs = arrayOf(source.conversationId, source.sourceId, source.callId)
                seq = scalar("SELECT seq FROM tool_results WHERE conversation_id=? AND batch_id=? AND call_id=?", resultArgs) ?: return null
                body = rows("SELECT * FROM tool_results WHERE conversation_id=? AND batch_id=? AND call_id=?", resultArgs)
            }
            ConversationTurn.Source.Kind.COMPACTION -> return null
        }
        val position = sql.query("SELECT sort_seq,sort_sub FROM conversation_items WHERE conversation_id=? AND seq=?",
            arrayOf(source.conversationId, seq)).use { c -> if (!c.moveToFirst()) return null else c.getLong(0) to c.getInt(1) }
        return CompactionLeaf(source, hash(body + JSONArray().put(position.first).put(position.second).toString()),
            seq, text, role, untrusted, position.first, position.second)
    }

    private fun topic(conversationId: String, coverageHash: String) = "digest:compaction:$conversationId:$coverageHash"
    private fun digestFingerprint(conversationId: String, coverageHash: String): String? {
        val value = rows("SELECT * FROM memory_entries WHERE topic_key=? ORDER BY id", arrayOf(topic(conversationId, coverageHash)))
        if (value == "[]") return null
        return hash(value + rows("SELECT r.* FROM memory_revisions r JOIN memory_entries m ON m.id=r.target_id WHERE r.target_type='memory' AND m.topic_key=? ORDER BY r.created_at,r.id", arrayOf(topic(conversationId, coverageHash))))
    }

    /** Called ONLY inside the successful append transaction, before exposing its durable ID. */
    suspend fun bind(compaction: Compaction, coverage: CompactionCoverage?, prepared: PreparedCompactionFlush?, sessionKind: String) {
        val current = coverage?.takeIf { it.conversationId == compaction.conversation_id }?.let { prepareInside(it.conversationId, it.sources) }
        val exactCoverage = current?.takeIf { it.coverageHash == coverage?.coverageHash && it.leaves == coverage?.leaves && it.throughSeq < compaction.seq }
        if (exactCoverage != null) for (leaf in exactCoverage.leaves) sql.execSQL(
            "INSERT INTO compaction_coverage_sources(conversation_id,compaction_id,source_kind,source_id,call_id,source_fingerprint,source_seq) VALUES(?,?,?,?,?,?,?)",
            arrayOf(compaction.conversation_id, compaction.compaction_id, leaf.source.kind.name, leaf.source.sourceId, leaf.source.callId, leaf.fingerprint, leaf.seq))
        val valid = exactCoverage != null && prepared != null && prepared.coverage == coverage &&
            exactCoverage.episodesComplete && exactCoverage.fingerprint == prepared.coverage.fingerprint && prepared.text.isNotBlank() && prepared.canCommit.asBoolean &&
            exactCoverage.episodes.none { it.active || (it.narration != "done" && it.input.evidence.any { e -> e.outcome == "ok" }) }
        val receipt = if (valid) commitDigest(requireNotNull(exactCoverage), requireNotNull(prepared)) else null
        sql.execSQL("INSERT INTO compaction_flush(conversation_id,compaction_id,coverage_hash,digest_id,digest_revision,status) VALUES(?,?,?,?,?,?)",
            arrayOf(compaction.conversation_id, compaction.compaction_id, exactCoverage?.coverageHash, receipt?.first, receipt?.second, if (receipt != null) "complete" else "incomplete"))
        personal.markCompacted(compaction.conversation_id, sessionKind, receipt != null)
    }

    private fun commitDigest(coverage: CompactionCoverage, proposed: PreparedCompactionFlush): Pair<String, Long>? {
        if (!proposed.canCommit.asBoolean) return null
        val topic = topic(coverage.conversationId, coverage.coverageHash)
        val existing = sql.query("SELECT id,text,title,deleted_at FROM memory_entries WHERE topic_key=? ORDER BY id", arrayOf(topic)).use { c ->
            if (!c.moveToFirst()) null else {
                if (!c.isNull(3)) return null // Forgotten entries are not silently resurrected.
                Triple(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getString(2))
            }
        }
        val id = existing?.first ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val title = proposed.title.trim()
        val text = proposed.text.trim()
        val sourceRefs = JSONArray().also { refs -> coverage.leaves.forEach { leaf ->
            refs.put(org.json.JSONObject().put("kind", leaf.source.kind.name).put("conversation_id", coverage.conversationId)
                .put("source_id", leaf.source.sourceId).put("call_id", leaf.source.callId).put("seq", leaf.seq))
        } }.toString()
        for (episode in coverage.episodes) {
            val memberSeqs = coverage.leaves.filter { leaf ->
                (leaf.source.kind == ConversationTurn.Source.Kind.MESSAGE && leaf.source.sourceId == episode.input.sourceMessageId) ||
                    episode.input.evidence.any { e -> e.sourceBatchId == leaf.source.sourceId &&
                        (leaf.source.kind == ConversationTurn.Source.Kind.BATCH || leaf.source.callId == e.refId) }
            }.map { it.seq }
            if (memberSeqs.isNotEmpty() && !episode.active) sql.execSQL("UPDATE memory_entries SET through_seq=? WHERE id=? AND deleted_at IS NULL",
                arrayOf(memberSeqs.maxOrNull(), episode.id))
        }
        if (existing == null) sql.execSQL("INSERT INTO memory_entries(id,layer,day,text,category,importance,triggers,provenance,source_ref,usage_count,last_used_at,created_at,updated_at,deleted_at,kind,topic_key,title,narration,event_at,source_conversation_id,source_message_id,through_seq) VALUES(?,'daily',?,?,'compaction',0.5,'','compaction_flush',?,0,NULL,?,?,NULL,'digest',?,?,'none',?,?,NULL,?)",
            arrayOf(id, LocalDate.now().toString(), text, sourceRefs, now, now, topic, title, now, coverage.conversationId, coverage.throughSeq))
        else if (existing.second != text || existing.third != title) sql.execSQL("UPDATE memory_entries SET text=?,title=?,updated_at=?,through_seq=? WHERE id=?", arrayOf(text, title, now, coverage.throughSeq, id))
        if (existing == null || existing.second != text || existing.third != title) sql.execSQL("INSERT INTO memory_revisions(id,target_type,target_id,before_content,after_content,actor,cause,created_at) VALUES(?,'memory',?,?,?,'compaction_flush','compaction_digest',?)",
            arrayOf(UUID.randomUUID().toString(), id, existing?.second, text, now))
        sql.execSQL("DELETE FROM memory_links WHERE from_id=? AND relation='part_of' AND actor='compaction_flush'", arrayOf(id))
        for (episode in coverage.episodes) sql.execSQL("INSERT OR IGNORE INTO memory_links(from_id,to_id,relation,actor,reason,created_at) VALUES(?,?,'part_of','compaction_flush',?,?)", arrayOf(id, episode.id, coverage.coverageHash, now))
        sql.execSQL("DELETE FROM memory_evidence WHERE entry_id=? AND ref_kind='model_spine'", arrayOf(id))
        var ordinal = scalar("SELECT COALESCE(MAX(ordinal),-1)+1 FROM memory_evidence WHERE entry_id=?", arrayOf(id)) ?: 0L
        for (leaf in coverage.leaves) sql.execSQL("INSERT INTO memory_evidence(entry_id,ordinal,ref_kind,ref_conversation_id,ref_id,at_ms,outcome,facts_json,source_batch_id) VALUES(?,?,'model_spine',?,?,?,NULL,?,?)",
            arrayOf(id, ordinal++, coverage.conversationId, leaf.source.sourceId, now,
                org.json.JSONObject().put("source_kind", leaf.source.kind.name).put("call_id", leaf.source.callId).put("source_seq", leaf.seq).put("source_sha256", leaf.fingerprint).toString(),
                if (leaf.source.kind in setOf(ConversationTurn.Source.Kind.BATCH, ConversationTurn.Source.Kind.BATCH_RESULT)) leaf.source.sourceId else null))
        return id to (scalar("SELECT COUNT(*) FROM memory_revisions WHERE target_type='memory' AND target_id=?", arrayOf(id)) ?: 0L)
    }

    suspend fun status(conversationId: String, compactionId: String): String? = database.withChatReadTransaction {
        sql.query("SELECT status FROM compaction_flush WHERE conversation_id=? AND compaction_id=?", arrayOf(conversationId, compactionId)).use { if (it.moveToFirst()) it.getString(0) else null }
    }
}
