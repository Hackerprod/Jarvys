package com.jarvys.agent.ui.chat

import com.jarvys.agent.AgentRunUiSnapshot

/** A pure projection from run data to a short-lived visual state. */
data class AgentPresence(val state: State, val toolName: String? = null) {
    enum class State { IDLE, THINKING, WORKING, WAITING_USER, STOPPING, ERROR, DONE }

    val active: Boolean get() = state == State.THINKING || state == State.WORKING

    companion object {
        @JvmStatic
        fun from(snapshot: AgentRunUiSnapshot): AgentPresence {
            val events = snapshot.events
            val summary = snapshot.presenceSummary
            if (snapshot.stopping) return AgentPresence(State.STOPPING)
            if (summary?.hasPendingApproval == true || summary?.hasPendingDecision == true
                || summary == null && (events.any { it.kind == "approval" && it.approvalStatus == "PENDING" }
                    || events.any { it.kind == "user_decision" && it.decisionStatus == "PENDING" })) {
                return AgentPresence(State.WAITING_USER)
            }
            if (snapshot.outcome == "FAILED" || snapshot.outcome == "PARTIAL"
                || (summary?.hasFailedOutput ?: (events.lastOrNull { it.kind == "assistant" || it.kind == "result" }?.stage == "FAILED"))) {
                return AgentPresence(State.ERROR)
            }
            if (snapshot.running || snapshot.compacting) {
                val isToolCall = summary?.lastEventIsToolCall ?: events.lastOrNull()?.let {
                    it.kind == "tool" && it.stage == "tool_call"
                }
                if (isToolCall == true) {
                    val toolName = if (summary != null) summary.currentToolName
                        else events.lastOrNull()?.let { it.toolDisplayName ?: it.text }
                    return AgentPresence(State.WORKING, toolName)
                }
                return AgentPresence(State.THINKING)
            }
            if (snapshot.outcome in setOf("COMPLETED", "STOPPED")
                || (summary?.hasCompletedOutput ?: (events.lastOrNull { it.kind == "assistant" || it.kind == "result" }?.stage == "COMPLETED"))) {
                return AgentPresence(State.DONE)
            }
            return AgentPresence(State.IDLE)
        }
    }
}
