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

    fun narratePending(token: CancellationToken): Boolean {
        if (!app.getSharedPreferences(MemoryConstants.PREFERENCES_FILE, Context.MODE_PRIVATE)
                .getBoolean(MemoryConstants.ENABLED_KEY, true)) return false
        val promptContext = bridge.personalAgentPromptContext(conversationId, true, "principal")
        for (episode in bridge.personalAgentPendingEpisodeNarrationsSync(conversationId)) {
            token.throwIfCancelled()
            bridge.personalAgentSetEpisodeNarrationSync(episode.id, "pending", null, null)
            try {
                val input = requireNotNull(bridge.personalAgentEpisodeNarrationInputSync(episode.id))
                val request = input.sourceMessageId?.let { bridge.readMessageSync(conversationId, it) }
                    ?.text?.takeIf(String::isNotBlank).orEmpty()
                val evidence = JSONArray()
                input.evidence.forEach { row ->
                    evidence.put(JSONObject()
                        .put("kind", row.refKind)
                        .put("tool", row.toolName ?: JSONObject.NULL)
                        .put("connector", row.connector ?: JSONObject.NULL)
                        .put("outcome", row.outcome ?: JSONObject.NULL)
                        .put("authorization", row.authorization ?: JSONObject.NULL)
                        .put("at_ms", row.atMs)
                        .put("facts", runCatching { JSONObject(row.factsJson) }.getOrDefault(JSONObject())))
                }
                val userPayload = JSONObject().put("request", PersonalMemoryFlush.redactSecrets(request))
                    .put("event_at_ms", input.eventAt ?: JSONObject.NULL).put("evidence", evidence).toString()
                val reply = model.complete(
                    promptContext.rules + "\n\n" + promptContext.soul,
                    mutableListOf(ConversationTurn.messageWithRichParts("user", userPayload, null, 0, 0)),
                    "Write a concise title and a factual narration for this episode. Return a JSON object with title and narration fields.",
                    emptyList(),
                    token,
                ) ?: throw IllegalStateException("Episode narration model returned no response")
                token.throwIfCancelled()
                val result = JSONObject(reply.text.trim())
                val title = result.optString("title").trim()
                val narration = result.optString("narration").trim()
                if (title.isEmpty() || narration.isEmpty()) throw IllegalStateException("episode_narration_incomplete")
                bridge.personalAgentSetEpisodeNarrationSync(episode.id, "done", title, narration)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                bridge.personalAgentSetEpisodeNarrationSync(episode.id, "failed", null, null)
                throw failure
            }
        }
        return true
    }
}
