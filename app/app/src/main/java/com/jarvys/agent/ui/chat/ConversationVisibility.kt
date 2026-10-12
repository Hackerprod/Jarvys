package com.jarvys.agent.ui.chat

import com.jarvys.agent.AgentRunUiEvent

/** Display-only projection. Original tool/model evidence remains available to recovery and diagnostics. */
internal object ConversationVisibility {
    private val internalStages = setOf(
        "thinking", "model_wait", "model_response", "answer", "compacting",
        "perception", "operator", "device_use", "validator", "summarizer",
    )

    fun visible(event: AgentRunUiEvent): Boolean {
        if (event.kind == "progress" && event.stage in internalStages) return false
        // A model preamble accompanying tool calls is not the final user-facing answer.
        if (event.kind == "assistant" && event.stage == "PROGRESS") return false
        // Typed identity only: never classify user text, display names or tool output as instructions.
        // Failed, interrupted and never-started coordination remains actionable and visible.
        if (event.kind == "tool" && event.toolActivity?.toolName == "workspaces" &&
            event.stage in setOf("tool_call", "tool_progress", "tool_result")) return false
        return true
    }

    fun project(events: List<AgentRunUiEvent>): List<AgentRunUiEvent> = events.filter(::visible)
}
