package com.jarvys.agent.ui.chat

import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.AgentRunUiPresenceSummary
import com.jarvys.agent.AgentRunUiSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentPresenceTest {
    @Test fun snapshotMapsToReadyThinkingAndWorkingStates() {
        assertEquals(AgentPresence.State.IDLE, AgentPresence.from(AgentRunUiSnapshot()).state)
        assertEquals(AgentPresence.State.THINKING,
            AgentPresence.from(AgentRunUiSnapshot(running = true)).state)
        assertEquals(AgentPresence.State.STOPPING,
            AgentPresence.from(AgentRunUiSnapshot(stopping = true)).state)
        val tool = AgentRunUiEvent.toolEvent(1L, "tool_call", "Calendar Search", null, "call-1", null, 1L)
        val working = AgentPresence.from(AgentRunUiSnapshot(running = true, events = listOf(tool)))
        assertEquals(AgentPresence.State.WORKING, working.state)
        assertEquals("Calendar Search", working.toolName)
    }

    @Test fun pendingHumanActionsTakePriorityOverRunAndTerminalStates() {
        val approval = AgentRunUiEvent(1L, "approval", "PENDING", "Confirm", approvalStatus = "PENDING")
        val decision = AgentRunUiEvent(2L, "user_decision", "PENDING", "Choose", decisionStatus = "PENDING")
        assertEquals(AgentPresence.State.WAITING_USER,
            AgentPresence.from(AgentRunUiSnapshot(running = true, outcome = "FAILED", events = listOf(approval))).state)
        assertEquals(AgentPresence.State.WAITING_USER,
            AgentPresence.from(AgentRunUiSnapshot(events = listOf(decision))).state)
    }

    @Test fun failuresSuccessAndCompactionMapToStaticOrThinkingStates() {
        assertEquals(AgentPresence.State.THINKING,
            AgentPresence.from(AgentRunUiSnapshot(compacting = true)).state)
        assertEquals(AgentPresence.State.ERROR,
            AgentPresence.from(AgentRunUiSnapshot(outcome = "FAILED")).state)
        assertEquals(AgentPresence.State.ERROR,
            AgentPresence.from(AgentRunUiSnapshot(events = listOf(
                AgentRunUiEvent(1L, "assistant", "FAILED", "Could not finish"),
            ))).state)
        assertEquals(AgentPresence.State.DONE,
            AgentPresence.from(AgentRunUiSnapshot(outcome = "COMPLETED")).state)
        assertEquals(AgentPresence.State.DONE,
            AgentPresence.from(AgentRunUiSnapshot(outcome = "STOPPED")).state)
    }

    @Test fun compactRenderSnapshotPreservesPresenceFromFullEventList() {
        val cases = listOf(
            AgentRunUiSnapshot(running = true, events = listOf(
                AgentRunUiEvent(1L, "approval", "PENDING", "Confirm", approvalStatus = "PENDING"),
                AgentRunUiEvent.toolEvent(2L, "tool_call", "Search", null, "call", null, 2L),
            )),
            AgentRunUiSnapshot(running = true, events = listOf(
                AgentRunUiEvent.messageEvent(1L, "user", "question", 1L),
                AgentRunUiEvent.toolEvent(2L, "tool_call", "Search", null, "call", null, 2L),
            )),
            AgentRunUiSnapshot(events = listOf(
                AgentRunUiEvent.messageEvent(1L, "assistant", "answer", 1L).copyStage("FAILED"),
            )),
            AgentRunUiSnapshot(outcome = "COMPLETED", events = listOf(
                AgentRunUiEvent.messageEvent(1L, "assistant", "answer", 1L).copyStage("COMPLETED"),
            )),
        )
        cases.forEach { full ->
            val compact = full.copy(events = emptyList(), presenceSummary = AgentRunUiPresenceSummary.from(full.events))
            assertEquals(AgentPresence.from(full), AgentPresence.from(compact))
        }
        val working = cases[1].copy(
            events = emptyList(), presenceSummary = AgentRunUiPresenceSummary.from(cases[1].events),
        )
        assertEquals("Search", AgentPresence.from(working).toolName)
    }
}
