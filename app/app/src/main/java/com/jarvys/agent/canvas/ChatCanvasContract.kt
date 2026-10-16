package com.jarvys.agent.canvas

import android.content.Context
import com.jarvys.agent.AgentRunUiEvent
import org.json.JSONArray
import org.json.JSONObject

/** One explicit, format-neutral projection of the existing Kotlin event model for the local canvas. */
internal object ChatCanvasEventSerializer {
    fun page(
        events: List<AgentRunUiEvent>,
        running: Boolean,
        allowRetry: Boolean = true,
        resourceOrigin: String = ChatCanvasWebView.ORIGIN,
        loadingOlder: Boolean = false,
    ): JSONObject {
        val latestAssistant = events.lastOrNull { it.kind == "assistant" }?.messageId
        val projected = JSONArray()
        events.forEachIndexed { index, event ->
            projected.put(event(event, index, running, allowRetry, latestAssistant, resourceOrigin))
        }
        return JSONObject().put("events", projected).put("followEnd", running || events.lastOrNull()?.kind == "user")
            .put("loadingOlder", loadingOlder)
            .put("order", JSONArray().also { order ->
            events.forEach { order.put(it.id) }
        })
    }

    private fun event(
        event: AgentRunUiEvent,
        order: Int,
        running: Boolean,
        allowRetry: Boolean,
        latestAssistant: String?,
        origin: String,
    ): JSONObject {
        val value = JSONObject()
            .put("seq", event.id)
            .put("order", order)
            .put("kind", if (event.kind == "assistant_stream") "assistant" else event.kind)
            .put("stage", event.stage)
            .put("text", event.text)
            .put("detail", event.detail)
            .put("messageId", event.messageId)
            .put("label", event.toolDisplayName)
            .put("previewId", event.previewId)
            .put("decisionId", event.decisionId)
            .put("decisionStatus", event.decisionStatus)
            .put("decisionAllowDismiss", event.decisionAllowDismiss)
            .put("artifactId", event.presentationArtifact?.id)
            .put("artifactTitle", event.presentationArtifact?.title ?: event.presentationArtifact?.name)
            .put("thumbnailRef", event.presentationArtifact?.thumbnailRef)
            .put("repliesUsed", event.proactiveRepliesUsed)
        val links = JSONArray()
        event.attachments.forEachIndexed { index, attachment ->
            if (attachment.isImage) links.put("$origin/chat-assets/message/${event.id}/$index")
        }
        if (event.generatedImageStatus == "SYNTHETIC") links.put("$origin/benchmark-image.svg")
        else if (!event.generatedImagePath.isNullOrBlank()) links.put("$origin/chat-assets/generated/${event.id}")
        value.put("imageUrls", links)
            .put("imageAlt", event.attachments.firstOrNull { it.isImage }?.name ?: event.generatedImagePrompt)
        if (event.presentationArtifact?.thumbnailRef?.isNotBlank() == true) {
            value.put("thumbnailUrl", if (event.presentationArtifact.thumbnailRef == "benchmark")
                "$origin/benchmark-image.svg" else "$origin/chat-assets/artifact/${event.id}/thumbnail")
        }
        val canOpen = event.previewId?.isNotBlank() == true || event.presentationArtifact != null ||
            event.crewMissionSnapshot != null || event.scheduledTaskArtifact != null
        val canTransfer = event.deliveredArtifact != null || !event.generatedImagePath.isNullOrBlank() || event.attachments.isNotEmpty()
        val canRetry = allowRetry && !running && event.kind == "assistant" && event.stage == null &&
            event.messageId.isNotBlank() && event.messageId == latestAssistant
        value.put("canCopy", event.kind == "user" || event.kind == "assistant" || event.kind == "assistant_stream")
            .put("canOpen", canOpen).put("canTransfer", canTransfer).put("canRetry", canRetry)
        val options = JSONArray()
        event.decisionOptions.forEach { option ->
            options.put(JSONObject().put("id", option.id).put("label", option.label).put("description", option.description))
        }
        value.put("decisionOptions", options)
        val replies = JSONArray()
        event.proactiveReplies.forEach { reply -> replies.put(JSONObject().put("label", reply.label)) }
        value.put("replies", replies)
        return value
    }
}

/** Developer flag is global and opt-in; conversation content and persistence remain unchanged. */
internal object ChatCanvasPreferences {
    private const val PREFS = "jarvys_chat_canvas"
    private const val ENABLED = "experimental_enabled"

    fun enabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED, enabled).apply()
    }
}

internal data class ChatCanvasActionDecision(val allowed: Boolean, val reason: String)

/** Host-side action allowlist. JavaScript supplies only an event seq and a small action value. */
internal object ChatCanvasActionPolicy {
    fun check(
        action: String,
        event: AgentRunUiEvent?,
        value: String,
        running: Boolean,
        latestAssistantMessageId: String?,
        hasOlderPage: Boolean,
        allowRetry: Boolean = true,
    ): ChatCanvasActionDecision {
        if (action == "request_older") return decision(hasOlderPage, "older page unavailable")
        if (event == null) return ChatCanvasActionDecision(false, "event is not in this conversation window")
        val allowed = when (action) {
            "copy" -> event.kind == "user" || event.kind == "assistant" || event.kind == "assistant_stream"
            "retry" -> allowRetry && !running && event.kind == "assistant" && event.messageId.isNotBlank() &&
                (event.stage == "FAILED" || event.messageId == latestAssistantMessageId)
            "open_viewer" -> event.previewId?.isNotBlank() == true || event.presentationArtifact != null ||
                event.crewMissionSnapshot != null || event.scheduledTaskArtifact != null
            "download", "share" -> event.deliveredArtifact != null ||
                !event.generatedImagePath.isNullOrBlank() || event.attachments.isNotEmpty()
            "resolve_decision" -> event.kind == "user_decision" && event.decisionStatus == "PENDING" &&
                (event.decisionOptions.any { it.id == value } || value == "dismiss" && event.decisionAllowDismiss)
            "respond" -> event.kind == "user" && !event.proactiveRepliesUsed &&
                value.toIntOrNull()?.let { it in event.proactiveReplies.indices } == true
            else -> false
        }
        return decision(allowed, "action is not allowed for this event")
    }

    private fun decision(allowed: Boolean, reason: String) = ChatCanvasActionDecision(allowed, if (allowed) "allowed" else reason)
}
