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

    fun narratePending(token: CancellationToken, episodeIds: Collection<String>?, ticket: MemoryWriteGate.Ticket): Boolean {
        if (!memoryEnabled()) return false
        val selected = episodeIds?.toSet()
        token.throwIfCancelled()
        if (selected != null && selected.isEmpty()) return true
        val pending = bridge.personalAgentPendingEpisodeNarrationsSync(conversationId, selected)
        if (pending.isEmpty()) return true
        val promptContext = bridge.personalAgentPromptContext(conversationId, true, "principal")
        for (episode in pending) {
            token.throwIfCancelled()
            if (!memoryEnabled()) return false
            val input = bridge.personalAgentEpisodeNarrationInputSync(episode.id) ?: continue
            if (input.evidence.any { it.outcome == "intent" || it.outcome == "started" } ||
                input.evidence.none { it.refKind in setOf("tool_call", "crew_result") && it.outcome == "ok" }) continue
            bridge.personalAgentSetEpisodeNarrationAutomatic(ticket, token, episode.id, "pending", null, null)
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
                    val source = if (row.refKind in setOf("tool_call", "tool_result") &&
                        row.refConversationId == conversationId && row.sourceBatchId != null) {
                        transcriptStore.readSource(row.sourceBatchId, row.refId, sourceBudget)
                    } else null
                    evidence.put(JSONObject()
                        .put("kind", row.refKind)
                        .put("tool", row.toolName ?: JSONObject.NULL)
                        .put("connector", row.connector ?: JSONObject.NULL)
                        .put("outcome", row.outcome ?: JSONObject.NULL)
                        .put("authorization", row.authorization ?: JSONObject.NULL)
                        .put("at_ms", row.atMs)
                        .put("facts", runCatching { JSONObject(row.factsJson) }.getOrDefault(JSONObject()))
                        .put("source", source ?: JSONObject.NULL))
                }
                val userPayload = EpisodeNarrationPayload.render(
                    EpisodeNarrationPayload.redact(request, PersonalMemoryFlush::redactSecrets).toString(),
                    EpisodeNarrationPayload.redact(finalResponse, PersonalMemoryFlush::redactSecrets).toString(),
                    input.eventAt, EpisodeNarrationPayload.redactEvidence(evidence, PersonalMemoryFlush::redactSecrets),
                    CorePromptBudget.standard().transcriptChars)
                val reply = model.complete(
                    promptContext.rules + "\n\n" + promptContext.soul,
                    mutableListOf(ConversationTurn.messageWithRichParts("user", userPayload, null, 0, 0)),
                    "Write a concise title and a factual narration for this episode. Return a JSON object with title and narration fields. " +
                        "Respect normalized completion_semantics: local_resource_created confirms only a local record; " +
                        "coordination_accepted confirms only local delegation or input acceptance, never worker consumption, mission completion or a Coding command result; " +
                        "local_delivery_confirmed confirms only local delivery, not that a recipient read it; " +
                        "existing_reference refers to an earlier operation and is not a new execution. " +
                        "An ok outcome for these local acts is not verification of the user's goal, build or tests. Never turn goal_verified=false into success of that goal.",
                    emptyList(),
                    token,
                ) ?: throw IllegalStateException("Episode narration model returned no response")
                token.throwIfCancelled()
                if (!memoryEnabled()) return false
                val result = JSONObject(reply.text.trim())
                val title = EpisodeNarrationPayload.redact(result.optString("title"), PersonalMemoryFlush::redactSecrets).toString().trim()
                val narration = EpisodeNarrationPayload.redact(result.optString("narration"), PersonalMemoryFlush::redactSecrets).toString().trim()
                if (title.isEmpty() || narration.isEmpty()) throw IllegalStateException("episode_narration_incomplete")
                if (!bridge.personalAgentCommitEpisodeNarrationAutomatic(ticket, token, episode.id, input, "done", title, narration))
                    throw IllegalStateException("episode_evidence_changed_during_narration")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // OFF/epoch changes must not let failure bookkeeping become a late automatic write.
                runCatching { bridge.personalAgentCommitEpisodeNarrationAutomatic(ticket, token, episode.id, input, "failed", null, null) }
                throw failure
            }
        }
        return true
    }
    private fun memoryEnabled() = MemoryWritePolicy.forContext(app).isEnabled()

}
