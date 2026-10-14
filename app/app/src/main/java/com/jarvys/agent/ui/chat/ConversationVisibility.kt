package com.jarvys.agent.ui.chat

import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.HtmlPreviewDescriptor
import org.json.JSONObject

/** Display-only projection. Original tool/model evidence remains available to recovery and diagnostics. */
internal object ConversationVisibility {
    private val internalStages = setOf(
        "thinking", "model_wait", "model_response", "answer", "compacting",
        "perception", "operator", "device_use", "validator", "summarizer",
    )

    fun visible(event: AgentRunUiEvent): Boolean {
        if (event.kind == "memory" && event.stage == "SEED") return false
        if (event.kind == "progress" && event.stage in internalStages) return false
        // A model preamble accompanying tool calls is not the final user-facing answer.
        if (event.kind == "assistant" && event.stage == "PROGRESS") return false
        // Typed identity only: workspace coordination rows are projected separately from user work.
        // Failed, interrupted and never-started coordination remains actionable and visible.
        if (event.kind == "tool" && event.toolActivity?.toolName == "workspaces" &&
            event.stage in setOf("tool_call", "tool_progress", "tool_result")) return false
        return true
    }

    /**
     * Suppress successful tool rows only when their exact artifact is already represented by a
     * first-class timeline card. This is intentionally a projection over the whole conversation:
     * the card and tool result can be restored from separate durable rows.
     */
    fun project(events: List<AgentRunUiEvent>): List<AgentRunUiEvent> {
        val deliveredArtifactIds = events.asSequence()
            .filter { it.kind == "delivered_file" }
            .mapNotNull { it.deliveredArtifact?.id }
            .toSet()
        val generatedImageIds = events.asSequence()
            .filter { it.kind == "generated_image" }
            .mapNotNull { it.generatedImagePath?.removeSuffix(".png")?.takeIf(String::isNotBlank) }
            .toSet()

        return events.filter { event ->
            visible(event) && !hasMatchingArtifactCard(event, deliveredArtifactIds, generatedImageIds)
        }
    }

    private fun hasMatchingArtifactCard(
        event: AgentRunUiEvent,
        deliveredArtifactIds: Set<String>,
        generatedImageIds: Set<String>,
    ): Boolean {
        if (event.kind != "tool" || event.stage != "tool_result") return false

        val previewId = event.previewId.orEmpty()
        if (HtmlPreviewDescriptor.isSnapshotToken(previewId) &&
            previewId.removePrefix(HtmlPreviewDescriptor.TOKEN_PREFIX) in deliveredArtifactIds) return true

        val detail = event.detail.orEmpty()
        val deliveredArtifactId = runCatching {
            JSONObject(detail).optString("artifact_id")
        }.getOrNull()?.takeIf { it.startsWith("delivered:") }?.removePrefix("delivered:")
        if (!deliveredArtifactId.isNullOrBlank() && deliveredArtifactId in deliveredArtifactIds) return true

        val generatedImageId = generatedImageReference.find(detail)?.groupValues?.getOrNull(1)
        return !generatedImageId.isNullOrBlank() && generatedImageId in generatedImageIds
    }

    private val generatedImageReference = Regex("\\bgenerated:([0-9a-fA-F-]{36})\\b")
}
