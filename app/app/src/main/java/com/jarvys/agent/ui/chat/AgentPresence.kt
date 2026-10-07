package com.jarvys.agent.ui.chat

import com.jarvys.agent.AgentRunUiSnapshot

/** A pure projection from run data to a short-lived visual state. */
data class AgentPresence(val state: State, val toolName: String? = null) {
    enum class State { IDLE, THINKING, WORKING, WAITING_USER, ERROR, DONE }

    val active: Boolean get() = state == State.THINKING || state == State.WORKING

    companion object {
        @JvmStatic
        fun from(snapshot: AgentRunUiSnapshot): AgentPresence {
            val events = snapshot.events
            if (events.any { it.kind == "approval" && it.approvalStatus == "PENDING" }
                || events.any { it.kind == "user_decision" && it.decisionStatus == "PENDING" }) {
                return AgentPresence(State.WAITING_USER)
            }
            if (snapshot.outcome == "FAILED" || snapshot.outcome == "PARTIAL"
                || events.lastOrNull { it.kind == "assistant" || it.kind == "result" }?.stage == "FAILED") {
                return AgentPresence(State.ERROR)
            }
            if (snapshot.running || snapshot.compacting) {
                val last = events.lastOrNull()
                if (last?.kind == "tool" && last.stage == "tool_call") {
                    return AgentPresence(State.WORKING, last.toolDisplayName ?: last.text)
                }
                return AgentPresence(State.THINKING)
            }
            if (snapshot.outcome in setOf("COMPLETED", "STOPPED")
                || events.lastOrNull { it.kind == "assistant" || it.kind == "result" }?.stage == "COMPLETED") {
                return AgentPresence(State.DONE)
            }
            return AgentPresence(State.IDLE)
        }
    }
}
