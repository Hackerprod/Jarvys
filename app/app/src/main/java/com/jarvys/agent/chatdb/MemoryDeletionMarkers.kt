package com.jarvys.agent.chatdb

import com.jarvys.agent.ConversationInput
import com.jarvys.agent.ConversationTurn
import org.json.JSONObject
import com.jarvys.agent.crew.CrewResultEvidence as VerifiedCrewResult
import com.jarvys.agent.workspaces.WorkspaceIdentity
import org.json.JSONArray

/** Local runtime identity only. These keys are never model-provided save/upsert constraints. */
internal object MemoryDeletionMarkers {
    data class Identity internal constructor(val producer: String, val key: String)
    private fun identity(producer: String, vararg ids: String): Identity {
        check(ids.all { it.isNotEmpty() }) { "memory_runtime_identity_conflict" }
        val value = JSONArray().put(1).put(producer)
        ids.forEach { value.put(it) }
        return Identity(producer, value.toString())
    }
    fun passive(generation:String, session:String, run:String, ordinal:Int):Identity {
        require(ordinal>=0) { "memory_runtime_identity_conflict" }
        return identity("passive_capture",generation,session,run,ordinal.toString())
    }
    fun mainEpisode(conversationId: String, sourceMessageId: String) = identity("main_episode", conversationId, sourceMessageId)
    fun missionEpisode(executionSessionId: String, evidenceId: String, assignmentId: String) =
        identity("mission_episode", executionSessionId, evidenceId, assignmentId)
    fun compactionDigest(conversationId: String, coverageHash: String) = identity("compaction_digest", conversationId, coverageHash)
    fun dailyDigest(sourceIds: List<String>): Identity {
        val ids = sourceIds.distinct().sorted()
        check(ids.isNotEmpty() && ids.all(String::isNotEmpty)) { "memory_runtime_identity_conflict" }
        return Identity("daily_digest", JSONArray().put(1).put("daily_digest").put(JSONArray(ids)).toString())
    }

    fun deletedId(database: JarvysChatDatabase, identity: Identity,
        project: String = MemoryNamespace.CENTRAL, scope: String = "project"): String? = database.chatSql.query(
        "SELECT old_id FROM memory_deleted_observations WHERE project=? AND scope=? AND producer=? AND producer_key=?",
        arrayOf(project, scope, identity.producer, identity.key),
    ).use { c -> if (!c.moveToFirst()) null else c.getString(0).also {
        check(!c.moveToNext()) { "memory_runtime_identity_conflict" }
    } }

    fun isDeletedId(database: JarvysChatDatabase, id: String,
        project: String = MemoryNamespace.CENTRAL, scope: String = "project"): Boolean = database.chatSql.query(
        "SELECT 1 FROM memory_deleted_observations WHERE old_id=? AND project=? AND scope=?", arrayOf(id, project, scope),
    ).use { it.moveToFirst() }

    fun requireAvailable(database: JarvysChatDatabase, identity: Identity,
        project: String = MemoryNamespace.CENTRAL, scope: String = "project") {
        check(deletedId(database, identity, project, scope) == null) { "memory_runtime_target_hard_deleted" }
    }

    /** Caller holds the same transaction as owner validation and payload deletion. */
    fun insertForTarget(database: JarvysChatDatabase, id: String, project: String, scope: String, now: Long) {
        val producer=validateTarget(database,id,project,scope)
        database.chatSql.execSQL("INSERT INTO memory_deleted_observations(old_id,project,scope,deleted_at,producer,producer_key) VALUES(?,?,?,?,?,?)",
            arrayOf(id, project, scope, now, producer?.producer, producer?.key))
    }

    /** Same retained producer validation, with no marker write for a soft consolidation. */
    fun validateTarget(database:JarvysChatDatabase,id:String,project:String,scope:String):Identity? {
        val sql = database.chatSql
        val row = sql.query("SELECT provenance,kind,source_ref,source_conversation_id,source_message_id,CAST(topic_key AS BLOB),category,layer FROM memory_entries WHERE id=? AND project=? AND scope=?",
            arrayOf(id, project, scope)).use { c ->
            check(c.moveToFirst()) { "memory_not_found" }
            List(8) {
                if (c.isNull(it)) null
                else if (it == 5) {
                    // A replacement-decoded topic must never certify a protected runtime identity.
                    // Ordinary notes may have upstream byte-truncated keys and need no producer identity.
                    val protectedOrigin = c.getString(0) !in setOf("user", "agent") || c.getString(1) != "note" ||
                        sql.query("SELECT 1 FROM memory_evidence WHERE entry_id=? LIMIT 1", arrayOf(id)).use { evidence -> evidence.moveToFirst() }
                    if (!protectedOrigin) null else try { MemoryObservationContent.strictTopic(c.getBlob(it)) }
                    catch (_: java.nio.charset.CharacterCodingException) { error("memory_runtime_identity_conflict") }
                } else c.getString(it)
            }
        }
        val ordinary = row[0] in setOf("user", "agent") && row[1] == "note" &&
            !sql.query("SELECT 1 FROM memory_evidence WHERE entry_id=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }
        val producer = try { if (ordinary) null else {
            check(project == MemoryNamespace.CENTRAL && scope == "project") { "memory_runtime_identity_conflict" }
            when {
                row[0] == "passive_capture" && row[1] == "note" -> {
                    val values=JSONArray(requireIdentity(row[2]))
                    check(values.length()==6 && values.getInt(0)==1 && values.getString(1)=="passive_capture" && row[3]==MemoryNamespace.CENTRAL && row[4]!=null && row[5]==null) { "memory_runtime_identity_conflict" }
                    val ordinal=values.getString(5).toInt()
                    val identity=passive(values.getString(2),values.getString(3),values.getString(4),ordinal)
                    check(identity.key==row[2] && sql.query("SELECT 1 FROM memory_entries WHERE id=? AND memory_session_id=? AND tool_name='session-end'",
                        arrayOf(id,values.getString(3))).use { it.moveToFirst() }) { "memory_runtime_identity_conflict" }
                    identity
                }
                row[0] == "runtime" && row[1] == "episode" && row[2]?.startsWith("message:") == true -> {
                    val message = requireIdentity(row[4])
                    check(row[3] == MemoryNamespace.CENTRAL && row[2] == "message:$message" &&
                        row[5] == "episode:${row[3]}:$message") { "memory_runtime_identity_conflict" }
                    mainEpisode(requireIdentity(row[3]), message)
                }
                row[0] == "runtime" && row[1] == "episode" && row[2]?.startsWith("crew_result:") == true ->
                    missionIdentity(database, row)
                row[0] == "compaction_flush" && row[1] == "digest" -> compactionIdentity(database, id, row)
                row[0] == "daily_consolidation" && row[1] == "digest" -> {
                    check(row[6] == "daily_consolidation" && row[7] == "curated" && row[3] == null && row[4] == null && row[5] == null) { "memory_runtime_identity_conflict" }
                    val values = JSONArray(requireIdentity(row[2]))
                    val ids = (0 until values.length()).map { string(values.get(it)) }
                    check(ids == ids.distinct().sorted() && values.toString() == JSONArray(ids).toString()) { "memory_runtime_identity_conflict" }
                    dailyDigest(ids)
                }
                else -> error("memory_runtime_identity_conflict")
            }
        }
        } catch (_: org.json.JSONException) {
            error("memory_runtime_identity_conflict")
        } catch (_: IllegalArgumentException) {
            error("memory_runtime_identity_conflict")
        }
        check(!isDeletedId(database, id, project, scope)) { "memory_runtime_identity_conflict" }
        if (producer != null) check(deletedId(database, producer, project, scope) == null) { "memory_runtime_identity_conflict" }
        return producer
    }

    private fun missionIdentity(database: JarvysChatDatabase, row: List<String?>): Identity {
        val conversation = requireIdentity(row[3])
        val message = requireIdentity(row[4])
        check(conversation == MemoryNamespace.CENTRAL) { "memory_runtime_identity_conflict" }
        val sql = database.chatSql
        val input = sql.query("SELECT execution_session_id,workspace_id,assignment_id,mission_id,bot_id,cycle_id,result_text_sha256 FROM mission_episode_inputs WHERE conversation_id=? AND message_id=?",
            arrayOf(conversation, message)).use { c ->
            check(c.moveToFirst()) { "memory_runtime_identity_conflict" }
            List(7) { requireIdentity(if(c.isNull(it)) null else c.getString(it)) }
        }
        check(input[0] == WorkspaceIdentity.executionSessionId(input[1])) { "memory_runtime_identity_conflict" }
        check(sql.query("SELECT 1 FROM messages WHERE conversation_id=? AND message_id=? AND role='agent' AND input_state='incorporated'",
            arrayOf(conversation, message)).use { it.moveToFirst() }) { "memory_runtime_identity_conflict" }
        val provenance = sql.query("SELECT payload FROM message_parts WHERE conversation_id=? AND message_id=? AND kind=?",
            arrayOf(conversation, message, ConversationInput.PROVENANCE_PART_KIND)).use { c ->
            check(c.moveToFirst()) { "memory_runtime_identity_conflict" }
            val value = ConversationInput.Provenance.fromJson(JSONObject(c.getString(0)).getJSONObject("provenance"))
            check(!c.moveToNext()) { "memory_runtime_identity_conflict" }
            value
        }
        check(provenance.kind == ConversationInput.Kind.RESULT &&
            provenance.principalRole == ConversationInput.Provenance.PrincipalRole.SUBORDINATE &&
            provenance.sessionId == input[0] && provenance.workspaceId == input[1] &&
            provenance.assignmentId == input[2] && provenance.missionId == input[3] &&
            provenance.botId == input[4] && provenance.cycleId == input[5]) { "memory_runtime_identity_conflict" }
        val evidence = sql.query("SELECT evidence_id,content_sha256,reported_status,result_text,produced_at_ms,schema_version FROM crew_result_evidence WHERE conversation_id=? AND mission_id=? AND bot_id=? AND cycle_id=?",
            arrayOf(input[0], input[3], input[4], input[5])).use { c ->
            check(c.moveToFirst()) { "memory_runtime_identity_conflict" }
            val result = VerifiedCrewResult.create(input[0], input[3], input[4], input[5], c.getString(2), ExactUtf16.decode(c.getBlob(3)), c.getLong(4))
            check(c.getInt(5) == VerifiedCrewResult.SCHEMA_VERSION && result.evidenceId == c.getString(0) &&
                result.contentSha256 == c.getString(1) && !c.moveToNext()) { "memory_runtime_identity_conflict" }
            result
        }
        check(MissionEpisodeRepository.textHash(evidence.resultText) == input[6] && row[2] == "crew_result:${evidence.evidenceId}" &&
            row[5] == "episode:mission:${input[0]}:${evidence.evidenceId}:${input[2]}") { "memory_runtime_identity_conflict" }
        return missionEpisode(input[0], evidence.evidenceId, input[2])
    }

    private data class Leaf(val kind: String, val sourceId: String, val callId: String, val seq: Long)
    private fun compactionIdentity(database: JarvysChatDatabase, id: String, row: List<String?>): Identity {
        val sql = database.chatSql
        check(row[3] == MemoryNamespace.CENTRAL && row[4] == null) { "memory_runtime_identity_conflict" }
        val refs = JSONArray(requireIdentity(row[2]))
        val leaves = (0 until refs.length()).map { index ->
            val ref = refs.getJSONObject(index)
            check(ref.keys().asSequence().toSet() == setOf("kind", "conversation_id", "source_id", "call_id", "seq") &&
                string(ref.get("conversation_id")) == row[3]) { "memory_runtime_identity_conflict" }
            val seq = ref.get("seq")
            check(seq is Number) { "memory_runtime_identity_conflict" }
            val exactSeq = runCatching { java.math.BigDecimal(seq.toString()).longValueExact() }.getOrElse { error("memory_runtime_identity_conflict") }
            Leaf(string(ref.get("kind")), string(ref.get("source_id")),
                string(ref.get("call_id"), allowEmpty = true), exactSeq)
        }
        check(leaves.isNotEmpty() && leaves.distinct().size == leaves.size) { "memory_runtime_identity_conflict" }
        var identity: Identity? = null
        sql.query("SELECT conversation_id,compaction_id,coverage_hash FROM compaction_flush WHERE digest_id=?", arrayOf(id)).use { c ->
            while(c.moveToNext()) {
                val conversation = c.getString(0)
                val compaction = c.getString(1)
                val coverage = requireIdentity(if(c.isNull(2)) null else c.getString(2))
                check(conversation == row[3] && coverage.matches(Regex("[a-f0-9]{64}")) &&
                    row[5] == "digest:compaction:$conversation:$coverage") { "memory_runtime_identity_conflict" }
                val bound = sql.query("SELECT source_kind,source_id,call_id,source_seq,source_fingerprint FROM compaction_coverage_sources WHERE conversation_id=? AND compaction_id=?",
                    arrayOf(conversation, compaction)).use { leaf -> buildList {
                    while(leaf.moveToNext()) {
                        val kind = runCatching { ConversationTurn.Source.Kind.valueOf(leaf.getString(0)) }.getOrElse { error("memory_runtime_identity_conflict") }
                        check(kind != ConversationTurn.Source.Kind.COMPACTION) { "memory_runtime_identity_conflict" }
                        val source = ConversationTurn.Source(kind, conversation, leaf.getString(1), leaf.getString(2))
                        val fingerprint = leaf.getString(4)
                        check(fingerprint.matches(Regex("[a-f0-9]{64}"))) { "memory_runtime_identity_conflict" }
                        add(CompactionLeaf(source, fingerprint, leaf.getLong(3)))
                    }
                } }.sortedBy { JSONArray().put(it.source.kind.name).put(it.source.sourceId).put(it.source.callId).toString() }
                val actual = bound.map { Leaf(it.source.kind.name, it.source.sourceId, it.source.callId, it.seq) }
                check(actual == leaves && CompactionMemoryRepository(database).coverageKey(conversation, bound) == coverage) { "memory_runtime_identity_conflict" }
                val current = compactionDigest(conversation, coverage)
                check(identity == null || identity == current) { "memory_runtime_identity_conflict" }
                identity = current
            }
        }
        return identity ?: error("memory_runtime_identity_conflict")
    }

    private fun requireIdentity(value: String?): String = value?.takeIf(String::isNotEmpty) ?: error("memory_runtime_identity_conflict")
    private fun string(value: Any?, allowEmpty: Boolean = false): String {
        check(value is String && (allowEmpty || value.isNotEmpty())) { "memory_runtime_identity_conflict" }
        return value
    }
}
