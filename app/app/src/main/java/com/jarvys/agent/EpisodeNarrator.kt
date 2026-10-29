package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException

/** Narrates only episodes with normalized successful write evidence. */
internal class EpisodeNarrator(context: Context, private val conversationId: String, private val model: CoreAgentModel) {
    private val app = context.applicationContext
    private val bridge = ChatDatabaseBridge.getInstance(app)

    fun narratePending(token: CancellationToken): Boolean = narratePending(token, null)

    /** A compaction pass may narrate only the exact episodes resolved from its coverage. */
    fun narratePending(token: CancellationToken, episodeIds: Collection<String>?): Boolean {
        return narratePending(token, episodeIds, MemoryWritePolicy.forContext(app).captureTicket())
    }

    @JvmOverloads
    fun narratePending(token: CancellationToken, episodeIds: Collection<String>?, ticket: MemoryWriteGate.Ticket, maintenance:com.jarvys.agent.chatdb.MaintenanceMemoryBinding?=null, compaction:com.jarvys.agent.chatdb.CompactionMemoryBinding?=null): Boolean {
        if (!memoryEnabled()) return false
        val selected = episodeIds?.toSet()
        maintenance?.checkCancellation(token)
        compaction?.check(token)
        token.throwIfCancelled()
        if (selected != null && selected.isEmpty()) return true
        val pending = bridge.personalAgentPendingEpisodeNarrationsSync(conversationId, selected)
        if (pending.isEmpty()) return true
        val promptContext = bridge.personalAgentPromptContext(conversationId, false, "principal")
        for (episode in pending) {
            maintenance?.checkCancellation(token)
        compaction?.check(token)
        token.throwIfCancelled()
            if (!memoryEnabled()) return false
            val input = bridge.personalAgentEpisodeNarrationInputSync(episode.id) ?: continue
            if (input.codingJobs.any { it.admitted && !it.terminalConfirmed } ||
                input.codingCancellations.any { it.recordKind == "ADMITTED_INTENT" && !it.resultConfirmed } ||
                input.evidence.any { it.outcome == "intent" || it.outcome == "started" } ||
                input.evidence.none { it.refKind in setOf("tool_call", "crew_result", "coding_job_execution", "coding_cancel_request") && it.outcome == "ok" }) continue
            if(compaction!=null) bridge.setCompactionNarration(ticket,token,compaction,episode.id,"pending",null,null)
            else if(maintenance==null) bridge.personalAgentSetEpisodeNarrationAutomatic(ticket, token, episode.id, "pending", null, null)
            else bridge.setMaintenanceNarration(ticket,token,maintenance,episode.id,"pending",null,null)
            try {
                val request = input.sourceMessageId?.let { bridge.readMessageSync(conversationId, it) }
                    ?.text?.takeIf(String::isNotBlank).orEmpty()
                val finalResponse = input.finalMessageId?.let { bridge.readMessageSync(conversationId, it) }
                    ?.text.orEmpty()
                val sourceBudget = CorePromptBudget.standard().toolResultsPerTurnChars /
                    (input.evidence.count { it.refKind == "tool_call" || it.refKind == "tool_result" } + 2)
                val transcriptStore = MainChatTranscriptStore(app.filesDir, conversationId, LocalRunStore(app))
                val evidence = JSONArray()
                input.evidence.forEach { row ->
                    val source = if (row.refKind in setOf("coding_job_submission", "coding_job_execution")) {
                        bridge.personalAgentCodingJobSourceSync(input.id, row.refId, sourceBudget)
                    } else if (row.refKind == "coding_cancel_request") {
                        bridge.personalAgentCodingCancellationSourceSync(input.id, row.refId, sourceBudget)
                    } else if (row.refKind in setOf("tool_call", "tool_result") &&
                        row.refConversationId == conversationId && row.sourceBatchId != null) {
                        transcriptStore.readSource(row.sourceBatchId, row.refId, sourceBudget)
                    } else null
                    val unverifiedCancellation = row.refKind == "coding_cancel_request" && source?.optString("state") != "retained"
                    val facts = if (unverifiedCancellation) EpisodeNarrationPayload.unverifiedCancellationFacts()
                        else runCatching { JSONObject(row.factsJson) }.getOrDefault(JSONObject())
                    evidence.put(JSONObject()
                        .put("kind", row.refKind)
                        .put("ref_id", row.refId)
                        .put("tool", row.toolName ?: JSONObject.NULL)
                        .put("connector", row.connector ?: JSONObject.NULL)
                        .put("outcome", if (unverifiedCancellation) "uncertain" else row.outcome ?: JSONObject.NULL)
                        .put("authorization", row.authorization ?: JSONObject.NULL)
                        .put("at_ms", row.atMs)
                        .put("facts", facts)
                        .put("source", source ?: JSONObject.NULL))
                }
                val userPayload = EpisodeNarrationPayload.render(
                    EpisodeNarrationPayload.redact(request, PersonalMemoryFlush::redactSecrets).toString(),
                    EpisodeNarrationPayload.redact(finalResponse, PersonalMemoryFlush::redactSecrets).toString(),
                    input.eventAt, EpisodeNarrationPayload.redactEvidence(evidence, PersonalMemoryFlush::redactSecrets),
                    CorePromptBudget.standard().transcriptChars,
                    suppressedCodingJobs = input.codingJobs.count { it.status == "SUPPRESSED" },
                    omittedCodingCancellations = input.codingCancellations.count { it.resultConfirmed && it.status != "LINKED" },
                    unconfirmedCodingCancellationProjections = input.codingCancellations.count { it.resultConfirmed && it.status == "PENDING" })
                val reply = model.complete(
                    promptContext.rules + "\n\n" + promptContext.soul,
                    mutableListOf(ConversationTurn.messageWithRichParts("user", userPayload, null, 0, 0)),
                    "Write a concise title and a factual narration for this episode. Return a JSON object with title and narration fields. " +
                        "Respect normalized completion_semantics: local_resource_created confirms only a local record; " +
                        "coordination_accepted confirms only local delegation or input acceptance, never worker consumption, mission completion or a Coding command result; " +
                        "local_delivery_confirmed confirms only local delivery, not that a recipient read it; " +
                        "existing_reference refers to an earlier operation and is not a new execution. " +
                        "cancel_request_accepted proves only a cancellation request, not signal delivery, a stopped/reaped process or a terminal job. " +
                        "existing_reference with kind job is not a new cancellation. Neither cancellation metadata nor job references add commands or verify a goal. " +
                        "An ok outcome for these local acts is not verification of the user's goal, build or tests. Never turn goal_verified=false into success of that goal. " +
                        "coding_job_submission is administrative acceptance only, never command completion. " +
                        "coding_job_execution records one durable process terminal per job, not proof that a build, test suite or user goal succeeded. " +
                        "Count unique jobs, not submission and execution rows as separate commands. Recorded inputs may be plural or partial; a historical root is not an exclusive cause. " +
                        "Cancellation retention_unverified means the recorded source could not be validated; do not claim that request was accepted. " +
                        "If coding_cancellations_omitted or coding_cancellation_projection_unconfirmed is nonzero, no linked cancellation receipt is available for those requests; never infer acceptance, stopping, termination or goal success from other evidence. " +
                        "If coding_jobs_suppressed is nonzero, some job evidence is intentionally omitted: narrate only visible evidence, never infer overall mission or goal completion.",
                    emptyList(),
                    token,
                ) ?: throw IllegalStateException("Episode narration model returned no response")
                maintenance?.checkCancellation(token)
        compaction?.check(token)
        token.throwIfCancelled()
                if (!memoryEnabled()) return false
                val result = JSONObject(reply.text.trim())
                val title = EpisodeNarrationPayload.redact(result.optString("title"), PersonalMemoryFlush::redactSecrets).toString().trim()
                val narration = EpisodeNarrationPayload.redact(result.optString("narration"), PersonalMemoryFlush::redactSecrets).toString().trim()
                if (title.isEmpty() || narration.isEmpty()) throw IllegalStateException("episode_narration_incomplete")
                if (!(if(compaction!=null) bridge.commitCompactionNarration(ticket,token,compaction,episode.id,input,"done",title,narration) else if(maintenance==null) bridge.personalAgentCommitEpisodeNarrationAutomatic(ticket, token, episode.id, input, "done", title, narration) else bridge.commitMaintenanceNarration(ticket,token,maintenance,episode.id,input,"done",title,narration)))
                    throw IllegalStateException("episode_evidence_changed_during_narration")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // OFF/epoch changes must not let failure bookkeeping become a late automatic write.
                runCatching { if(compaction!=null) bridge.commitCompactionNarration(ticket,token,compaction,episode.id,input,"failed",null,null) else if(maintenance==null) bridge.personalAgentCommitEpisodeNarrationAutomatic(ticket, token, episode.id, input, "failed", null, null) else bridge.commitMaintenanceNarration(ticket,token,maintenance,episode.id,input,"failed",null,null) }
                throw failure
            }
        }
        return true
    }
    private fun memoryEnabled() = MemoryWritePolicy.forContext(app).isEnabled()

}
