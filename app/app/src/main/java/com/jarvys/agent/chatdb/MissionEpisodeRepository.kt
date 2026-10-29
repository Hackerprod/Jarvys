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
internal class MissionEpisodeRepository(private val database: JarvysChatDatabase,private val clock:java.time.Clock=java.time.Clock.systemUTC()) {
    private val sql get() = database.chatSql

    suspend fun bind(input: MissionEpisodeInput,authorization:MissionReceiptAuthorization?=null) = database.withChatWriteTransaction {
        validateInput(input)
        val old = readInput(input.conversation_id, input.message_id)
        check(old == null || old == input) { "Mission result input binding changed" }
        if (old == null) sql.execSQL(
            "INSERT INTO mission_episode_inputs(conversation_id,message_id,execution_session_id,workspace_id,assignment_id,mission_id,bot_id,cycle_id,result_text_sha256) VALUES(?,?,?,?,?,?,?,?,?)",
            arrayOf(input.conversation_id, input.message_id, input.execution_session_id, input.workspace_id,
                input.assignment_id, input.mission_id, input.bot_id, input.cycle_id, input.result_text_sha256))
        reconcile(input,authorization,old==null)
    }

    private fun validateInput(input:MissionEpisodeInput) {
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
    }

    suspend fun reconcileInputs(conversationId: String, messageIds: List<String>,authorization:MissionReceiptAuthorization?=null,newlyIncorporated:Set<String> = emptySet()) = database.withChatWriteTransaction {
        if (conversationId != WorkspaceIdentity.CENTRAL_SESSION) return@withChatWriteTransaction emptyList<String>()
        messageIds.distinct().mapNotNull { id -> readInput(conversationId, id)?.let { reconcile(it,authorization,id in newlyIncorporated) } }
    }

    suspend fun reconcileEvidence(evidence: CrewResultEvidence,authorization:MissionReceiptAuthorization?=null,inserted:Boolean=false) = database.withChatWriteTransaction {
        val inputs = sql.query(
            "SELECT conversation_id,message_id,execution_session_id,workspace_id,assignment_id,mission_id,bot_id,cycle_id,result_text_sha256 FROM mission_episode_inputs WHERE execution_session_id=? AND mission_id=? AND bot_id=? AND cycle_id=? ORDER BY message_id",
            arrayOf(evidence.conversationId, evidence.missionId, evidence.botId, evidence.cycleId),
        ).use { c -> buildList { while (c.moveToNext()) add(readBinding(c)) } }
        inputs.mapNotNull { reconcile(it,authorization,inserted) }
    }

    /** Validate retained technical receipts and wake already materialized narration only; never backfill. */
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
            "SELECT i.conversation_id,i.message_id,i.execution_session_id,i.workspace_id,i.assignment_id,i.mission_id,i.bot_id,i.cycle_id,i.result_text_sha256,r.evidence_id " +
                "FROM mission_episode_inputs i JOIN messages m ON m.conversation_id=i.conversation_id AND m.message_id=i.message_id " +
                "JOIN crew_result_evidence r ON r.conversation_id=i.execution_session_id AND r.mission_id=i.mission_id AND r.bot_id=i.bot_id AND r.cycle_id=i.cycle_id " +
                "WHERE m.role='agent' AND m.input_state='incorporated' AND NOT EXISTS " +
                "(SELECT 1 FROM memory_entries e WHERE e.project='jarvys-central' AND e.scope='project' AND e.provenance='runtime' AND e.kind='episode' AND e.source_conversation_id=i.conversation_id " +
                "AND EXISTS(SELECT 1 FROM mission_episode_inputs original JOIN messages consumed ON consumed.conversation_id=original.conversation_id AND consumed.message_id=original.message_id " +
                "WHERE original.conversation_id=e.source_conversation_id AND original.message_id=e.source_message_id " +
                "AND original.execution_session_id=i.execution_session_id AND original.workspace_id=i.workspace_id " +
                "AND original.assignment_id=i.assignment_id AND original.mission_id=i.mission_id " +
                "AND original.bot_id=i.bot_id AND original.cycle_id=i.cycle_id " +
                "AND original.result_text_sha256=i.result_text_sha256 " +
                "AND consumed.role='agent' AND consumed.input_state='incorporated') " +
                "AND e.source_ref='crew_result:' || r.evidence_id AND e.topic_key='episode:mission:' || i.execution_session_id || ':' || r.evidence_id || ':' || i.assignment_id)",
        ).use { c -> buildList { while (c.moveToNext()) {
            val input = readBinding(c)
            if (MemoryDeletionMarkers.deletedId(database, MemoryDeletionMarkers.missionEpisode(
                    input.execution_session_id, c.getString(9), input.assignment_id)) == null) add(input)
        } } }
    }

    suspend fun pendingIds(): Set<String> = database.withChatReadTransaction {
        sql.query("SELECT id FROM memory_entries WHERE project='jarvys-central' AND scope='project' AND provenance='runtime' AND kind='episode' AND topic_key LIKE 'episode:mission:%' AND deleted_at IS NULL AND narration IN ('none','pending','failed') AND NOT EXISTS(SELECT 1 FROM memory_revisions r WHERE r.target_type='memory' AND r.target_id=memory_entries.id AND r.actor IN ('user','agent') AND r.cause='${MemoryObservationHistory.BODY_UPDATE}')").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    private fun readInput(conversationId: String, messageId: String): MissionEpisodeInput? = sql.query(
        "SELECT conversation_id,message_id,execution_session_id,workspace_id,assignment_id,mission_id,bot_id,cycle_id,result_text_sha256 FROM mission_episode_inputs WHERE conversation_id=? AND message_id=?",
        arrayOf(conversationId, messageId),
    ).use { if (it.moveToFirst()) readBinding(it) else null }

    private fun readBinding(c: android.database.Cursor) = MissionEpisodeInput(c.getString(0), c.getString(1),
        c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8))

    private suspend fun reconcile(input: MissionEpisodeInput,authorization:MissionReceiptAuthorization?=null,newEvent:Boolean=false): String? {
        val sourceSeq = sql.query(
            "SELECT seq FROM messages WHERE conversation_id=? AND message_id=? AND role='agent' AND input_state='incorporated'",
            arrayOf(input.conversation_id, input.message_id),
        ).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        validateInput(input)
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
        if (MemoryDeletionMarkers.deletedId(database, MemoryDeletionMarkers.missionEpisode(
                input.execution_session_id, evidence.evidenceId, input.assignment_id)) != null) return null
        val topic = "episode:mission:${input.execution_session_id}:${evidence.evidenceId}:${input.assignment_id}"
        // Multiple delivered copies can carry the same cycle receipt. Preserve the first exact
        // incorporated source, rather than reparenting its episode to the newest message.
        val originalSource = sql.query(
            "SELECT source_conversation_id,source_message_id FROM memory_entries WHERE project='jarvys-central' AND scope='project' AND topic_key=?",
            arrayOf(topic),
        ).use { c ->
            var source: Pair<String, String>? = null
            while (c.moveToNext()) {
                check(c.getType(0) == android.database.Cursor.FIELD_TYPE_STRING &&
                    c.getType(1) == android.database.Cursor.FIELD_TYPE_STRING) { "memory_runtime_identity_conflict" }
                val current = c.getString(0) to c.getString(1)
                check(source == null || source == current) { "memory_runtime_identity_conflict" }
                source = current
            }
            source
        }
        val sourceMessageId = originalSource?.let { (conversation, message) ->
            check(conversation == input.conversation_id) { "memory_runtime_identity_conflict" }
            val original = readInput(conversation, message)
            check(original != null && original.copy(message_id = input.message_id) == input) {
                "memory_runtime_identity_conflict"
            }
            check(sql.query("SELECT 1 FROM messages WHERE conversation_id=? AND message_id=? AND role='agent' AND input_state='incorporated'",
                arrayOf(conversation, message)).use { it.moveToFirst() }) { "memory_runtime_identity_conflict" }
            message
        } ?: input.message_id
        // Even a forgotten episode is not resurrected by receipt replay.
        val existing = RuntimeMemoryIdentity.byTopic(database, topic, "runtime", "episode",
            "crew_result:${evidence.evidenceId}", input.conversation_id, sourceMessageId)
        // The exact runtime receipt remains immutable under replay, including a user-edited body.
        if (existing != null) return null
        // Existing receipt/marker checks above are read-only, even on a later OFF redelivery.
        if(!newEvent || authorization==null) return null
        val suppressed=sql.query("SELECT memory_projection_suppressed FROM crew_result_evidence WHERE conversation_id=? AND evidence_id=?",arrayOf(input.execution_session_id,evidence.evidenceId)).use { c ->
            check(c.moveToFirst()) { "mission_evidence_missing" }
            check(c.getType(0)==android.database.Cursor.FIELD_TYPE_INTEGER && c.getInt(0) in 0..1) { "mission_privacy_state_invalid" }
            c.getInt(0)==1
        }
        if(suppressed) return null
        if(authorization.policyDenied) {
            sql.execSQL("UPDATE crew_result_evidence SET memory_projection_suppressed=1 WHERE conversation_id=? AND evidence_id=? AND memory_projection_suppressed=0",arrayOf(input.execution_session_id,evidence.evidenceId))
            check(sql.query("SELECT changes()").use { it.moveToFirst();it.getLong(0)==1L }) { "mission_privacy_state_changed" }
            return null
        }
        // Known missing current workspace grant is source-only, not OFF and not a new grant.
        if(!authorization.allows(input.execution_session_id)) return null
        val now = clock.millis()
        val id = UUID.randomUUID().toString()
        val session=UUID.randomUUID().toString()
        val generation=ConnectionProjectionRepository(database).generation()
        sql.execSQL("INSERT INTO memory_sessions(id,project,ownership_mode,state,started_at,ended_at,source_generation,owner_conversation_id,owner_message_id,mission_execution_session_id,mission_evidence_id,mission_assignment_id) VALUES(? ,?,'project_owned','completed',?,?,?,?,?,?,?,?)",
            arrayOf(session,MemoryNamespace.CENTRAL,now,now,generation,input.conversation_id,sourceMessageId,input.execution_session_id,evidence.evidenceId,input.assignment_id))
        val day = Instant.ofEpochMilli(evidence.producedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        sql.execSQL(
            "INSERT INTO memory_entries(project,scope,observation_type,id,layer,day,text,category,importance,triggers,provenance,source_ref,usage_count,last_used_at,created_at,updated_at,deleted_at,kind,topic_key,title,narration,event_at,source_conversation_id,source_message_id,through_seq,normalized_hash,last_seen_at,memory_session_id,review_after) VALUES('jarvys-central','project','episode',?,'curated',?,'',NULL,1.0,'','runtime',?,0,NULL,?,?,NULL,'episode',?,NULL,'none',?,?,?,?,?,?,?,?)",
            arrayOf(id, day, "crew_result:${evidence.evidenceId}", now, now, topic, evidence.producedAtMillis,
                input.conversation_id, sourceMessageId, sourceSeq, MemoryObservationContent.normalizedHash(""), now,session, MemoryReview.defaultAfter("episode", now)))
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
