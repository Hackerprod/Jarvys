package com.jarvys.agent.chatdb

import com.jarvys.agent.ConversationInput
import com.jarvys.agent.crew.CrewResultEvidence
import com.jarvys.agent.workspaces.WorkspaceIdentity
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Joins processed Central inputs to exact durable worker results, in either arrival order. */
internal class MissionEpisodeRepository(private val database: JarvysChatDatabase) {
    private val sql get() = database.chatSql

    suspend fun bind(input: MissionEpisodeInput) = database.withChatWriteTransaction {
        require(input.conversation_id == WorkspaceIdentity.CENTRAL_SESSION)
        require(input.execution_session_id == WorkspaceIdentity.executionSessionId(input.workspace_id))
        require(UUID.fromString(input.cycle_id).toString() == input.cycle_id)
        require(input.result_text_sha256.matches(Regex("[a-f0-9]{64}")))
        check(sql.query("SELECT 1 FROM messages WHERE conversation_id=? AND message_id=? AND role='agent'",
            arrayOf(input.conversation_id, input.message_id)).use { it.moveToFirst() })
        val provenance = sql.query(
            "SELECT payload FROM message_parts WHERE conversation_id=? AND message_id=? AND kind=?",
            arrayOf(input.conversation_id, input.message_id, ConversationInput.PROVENANCE_PART_KIND),
        ).use { c ->
            check(c.moveToFirst()) { "Mission result input has no provenance" }
            ConversationInput.Provenance.fromJson(JSONObject(c.getString(0)).getJSONObject("provenance"))
        }
        require(provenance.kind == ConversationInput.Kind.RESULT &&
            provenance.principalRole == ConversationInput.Provenance.PrincipalRole.SUBORDINATE &&
            provenance.sessionId == input.execution_session_id && provenance.workspaceId == input.workspace_id &&
            provenance.assignmentId == input.assignment_id && provenance.missionId == input.mission_id &&
            provenance.botId == input.bot_id && provenance.cycleId == input.cycle_id)
        val old = readInput(input.conversation_id, input.message_id)
        check(old == null || old == input) { "Mission result input binding changed" }
        if (old == null) sql.execSQL(
            "INSERT INTO mission_episode_inputs(conversation_id,message_id,execution_session_id,workspace_id,assignment_id,mission_id,bot_id,cycle_id,result_text_sha256) VALUES(?,?,?,?,?,?,?,?,?)",
            arrayOf(input.conversation_id, input.message_id, input.execution_session_id, input.workspace_id,
                input.assignment_id, input.mission_id, input.bot_id, input.cycle_id, input.result_text_sha256))
        reconcile(input)
    }

    suspend fun reconcileInputs(conversationId: String, messageIds: List<String>) = database.withChatWriteTransaction {
        if (conversationId != WorkspaceIdentity.CENTRAL_SESSION) return@withChatWriteTransaction emptyList<String>()
        messageIds.distinct().mapNotNull { id -> readInput(conversationId, id)?.let { reconcile(it) } }
    }

    suspend fun reconcileEvidence(evidence: CrewResultEvidence) = database.withChatWriteTransaction {
        val inputs = sql.query(
            "SELECT conversation_id,message_id,execution_session_id,workspace_id,assignment_id,mission_id,bot_id,cycle_id,result_text_sha256 FROM mission_episode_inputs WHERE execution_session_id=? AND mission_id=? AND bot_id=? AND cycle_id=? ORDER BY message_id",
            arrayOf(evidence.conversationId, evidence.missionId, evidence.botId, evidence.cycleId),
        ).use { c -> buildList { while (c.moveToNext()) add(readBinding(c)) } }
        inputs.mapNotNull { reconcile(it) }
    }

    suspend fun recoverPending(): Boolean = database.withChatWriteTransaction {
        recoverableInputs().forEach { reconcile(it) }
        val pending = pendingIds()
        if (pending.isNotEmpty()) PersonalAgentRepository(database).enqueueMemoryJob(WorkspaceIdentity.CENTRAL_SESSION, "narrate")
        pending.isNotEmpty()
    }

    /** Only unmaterialized incorporated receipts with a durable exact result need recovery.
     * The anti-join intentionally includes forgotten episodes: recovery must not resurrect them.
     */
    internal suspend fun recoverableInputs(): List<MissionEpisodeInput> = database.withChatReadTransaction {
        sql.query(
            "SELECT i.conversation_id,i.message_id,i.execution_session_id,i.workspace_id,i.assignment_id,i.mission_id,i.bot_id,i.cycle_id,i.result_text_sha256 " +
                "FROM mission_episode_inputs i JOIN messages m ON m.conversation_id=i.conversation_id AND m.message_id=i.message_id " +
                "JOIN crew_result_evidence r ON r.conversation_id=i.execution_session_id AND r.mission_id=i.mission_id AND r.bot_id=i.bot_id AND r.cycle_id=i.cycle_id " +
                "WHERE m.role='agent' AND m.input_state='incorporated' AND NOT EXISTS " +
                "(SELECT 1 FROM memory_entries e WHERE e.topic_key='episode:mission:' || i.execution_session_id || ':' || r.evidence_id || ':' || i.assignment_id)",
        ).use { c -> buildList { while (c.moveToNext()) add(readBinding(c)) } }
    }

    suspend fun pendingIds(): Set<String> = database.withChatReadTransaction {
        sql.query("SELECT id FROM memory_entries WHERE kind='episode' AND topic_key LIKE 'episode:mission:%' AND deleted_at IS NULL AND narration IN ('none','pending','failed')").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    private fun readInput(conversationId: String, messageId: String): MissionEpisodeInput? = sql.query(
        "SELECT conversation_id,message_id,execution_session_id,workspace_id,assignment_id,mission_id,bot_id,cycle_id,result_text_sha256 FROM mission_episode_inputs WHERE conversation_id=? AND message_id=?",
        arrayOf(conversationId, messageId),
    ).use { if (it.moveToFirst()) readBinding(it) else null }

    private fun readBinding(c: android.database.Cursor) = MissionEpisodeInput(c.getString(0), c.getString(1),
        c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8))

    private suspend fun reconcile(input: MissionEpisodeInput): String? {
        val sourceSeq = sql.query(
            "SELECT seq FROM messages WHERE conversation_id=? AND message_id=? AND role='agent' AND input_state='incorporated'",
            arrayOf(input.conversation_id, input.message_id),
        ).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        val evidence = sql.query(
            "SELECT evidence_id,content_sha256,reported_status,result_text,produced_at_ms,schema_version FROM crew_result_evidence WHERE conversation_id=? AND mission_id=? AND bot_id=? AND cycle_id=?",
            arrayOf(input.execution_session_id, input.mission_id, input.bot_id, input.cycle_id),
        ).use { c ->
            if (!c.moveToFirst()) null else {
                val value = CrewResultEvidence.create(input.execution_session_id, input.mission_id, input.bot_id,
                    input.cycle_id, c.getString(2), ExactUtf16.decode(c.getBlob(3)), c.getLong(4))
                check(c.getInt(5) == CrewResultEvidence.SCHEMA_VERSION && value.evidenceId == c.getString(0) &&
                    value.contentSha256 == c.getString(1) && !c.moveToNext()) { "Invalid mission result evidence" }
                value
            }
        } ?: return null
        if (textHash(evidence.resultText) != input.result_text_sha256) return null
        val topic = "episode:mission:${input.execution_session_id}:${evidence.evidenceId}:${input.assignment_id}"
        // Even a forgotten episode is not resurrected by receipt replay.
        if (sql.query("SELECT 1 FROM memory_entries WHERE topic_key=? LIMIT 1", arrayOf(topic)).use { it.moveToFirst() }) return null
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val day = Instant.ofEpochMilli(evidence.producedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        sql.execSQL(
            "INSERT INTO memory_entries(id,layer,day,text,category,importance,triggers,provenance,source_ref,usage_count,last_used_at,created_at,updated_at,deleted_at,kind,topic_key,title,narration,event_at,source_conversation_id,source_message_id,through_seq) VALUES(?,'curated',?,'',NULL,1.0,'','runtime',?,0,NULL,?,?,NULL,'episode',?,NULL,'none',?,?,?,?)",
            arrayOf(id, day, "crew_result:${evidence.evidenceId}", now, now, topic, evidence.producedAtMillis,
                input.conversation_id, input.message_id, sourceSeq))
        val facts = JSONObject().put("workspace_id", input.workspace_id).put("assignment_id", input.assignment_id)
            .put("mission_id", input.mission_id).put("bot_id", input.bot_id).put("cycle_id", input.cycle_id)
            .put("evidence_id", evidence.evidenceId).put("content_sha256", evidence.contentSha256)
            .put("reported_status", evidence.reportedStatus).put("receipt", "central_input_incorporated")
            .put("result_verified", false).toString()
        // ok describes the durable receipt, not verified success of the worker's task.
        sql.execSQL(
            "INSERT INTO memory_evidence(entry_id,ordinal,ref_kind,ref_conversation_id,ref_id,action_id,attempt_id,at_ms,tool_name,outcome,authorization,facts_json,connector) VALUES(?,0,'crew_result',?,?,NULL,NULL,?,NULL,'ok',NULL,?,NULL)",
            arrayOf(id, evidence.conversationId, evidence.evidenceId, evidence.producedAtMillis, facts))
        PersonalAgentRepository(database).enqueueMemoryJob(input.conversation_id, "narrate")
        return id
    }

    companion object {
        fun textHash(text: String): String = MessageDigest.getInstance("SHA-256").digest(ExactUtf16.encode(text))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
