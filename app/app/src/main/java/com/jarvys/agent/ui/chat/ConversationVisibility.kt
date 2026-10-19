package com.jarvys.agent.ui.chat

import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.ConversationInput
import com.jarvys.agent.HtmlPreviewDescriptor
import com.jarvys.agent.ScheduledTaskArtifact
import org.json.JSONObject

/** Display-only projection. Original tool/model evidence remains available to recovery and diagnostics. */
internal object ConversationVisibility {
    private val internalStages = setOf(
        "thinking", "model_wait", "model_response", "answer", "compacting",
        "perception", "operator", "device_use", "validator", "summarizer",
    )

    fun visible(event: AgentRunUiEvent, showAssistantProgress: Boolean = false): Boolean {
        // Origin is independent of the row's presentation kind and is the generic boundary for
        // internal agent/system inputs. Their resulting assistant answer remains a separate event.
        if (event.conversationInputOrigin == ConversationInput.Origin.AGENT ||
            event.conversationInputOrigin == ConversationInput.Origin.SYSTEM) return false
        // The strict journal row is durable model/recovery evidence, not a chat card for its owner.
        if (event.kind == "workspace_result") return false
        if (event.kind == "memory" && event.stage == "SEED") return false
        if (event.kind == "progress" && event.stage in internalStages) return false
        // Callers opt in when their timeline is meant to show persisted assistant progress preambles.
        if (event.kind == "assistant" && event.stage == "PROGRESS" && !showAssistantProgress) return false
        // Typed identity only: workspace coordination rows are projected separately from user work.
        // Failed, interrupted and never-started coordination remains actionable and visible.
        if (event.kind == "tool" && event.toolActivity?.toolName == "workspaces" &&
            event.stage in setOf("tool_call", "tool_progress", "tool_result")) return false
        return true
    }

    /** Replaces successful structured tool results with their first-class timeline cards. */
    fun project(events: List<AgentRunUiEvent>, showAssistantProgress: Boolean = false): List<AgentRunUiEvent> {
        val deliveredArtifactIds = events.asSequence()
            .filter { it.kind == "artifact" }
            .mapNotNull { it.presentationArtifact?.id }
            .toSet()
        val generatedImageIds = events.asSequence()
            .filter { it.kind == "artifact" && it.presentationArtifact?.isGeneratedImage == true }
            .mapNotNull { it.generatedImagePath?.removeSuffix(".png")?.takeIf(String::isNotBlank) }
            .toSet()

        return events.mapNotNull { event ->
            if (!visible(event, showAssistantProgress)) return@mapNotNull null
            val scheduledTask = scheduledTaskArtifact(event)
            if (scheduledTask != null) {
                return@mapNotNull event.copy(kind = "scheduled_task", scheduledTaskArtifact = scheduledTask)
            }
            if (hasMatchingArtifactCard(event, deliveredArtifactIds, generatedImageIds)) return@mapNotNull null
            event
        }
    }

    private fun scheduledTaskArtifact(event: AgentRunUiEvent): ScheduledTaskArtifact? {
        if (event.kind != "tool" || event.stage != "tool_result") return null
        return runCatching {
            val detail = event.detail.orEmpty()
            val jsonStart = detail.indexOf('{')
            val jsonEnd = detail.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd < jsonStart) return null
            val result = JSONObject(detail.substring(jsonStart, jsonEnd + 1))
            val task = result.optJSONObject("task") ?: return null
            if (task.optString("kind") != "scheduled_task") return null
            val id = task.optString("id").takeIf(String::isNotBlank) ?: return null
            val name = task.optString("name").takeIf(String::isNotBlank) ?: return null
            val schedule = task.optString("schedule_human").takeIf(String::isNotBlank) ?: return null
            ScheduledTaskArtifact(id, name, schedule,
                task.optString("next_run_local").takeIf { it.isNotBlank() && it != "null" },
                task.optString("status", result.optString("status")))
        }.getOrNull()
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
