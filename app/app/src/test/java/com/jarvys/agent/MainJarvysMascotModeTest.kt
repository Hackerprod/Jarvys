package com.jarvys.agent

import org.junit.Assert.*
import org.junit.Test

class MainJarvysMascotModeTest {
    private val active = AgentRunUiSnapshot(
        sessionId = "visible", running = true, interactiveOwnerSessionId = "visible",
        liveMascotTool = LiveMascotTool("execution", eligibleToAnimate = true),
    )

    @Test fun freshOwnedToolMapsToExactWorkingWireValue() {
        assertEquals(2, mainJarvysMascotMode(active, "visible", true))
    }

    @Test fun hiddenHistoryNewChatAndWrongSessionAlwaysStayStatic() {
        assertNull(mainJarvysMascotMode(active, "visible", false))
        assertNull(mainJarvysMascotMode(active, "other", true))
        assertNull(mainJarvysMascotMode(active, null, true))
        assertNull(mainJarvysMascotMode(active.copy(sessionId = null), null, true))
        assertNull(mainJarvysMascotMode(active.copy(sessionId = ""), "", true))
        assertNull(mainJarvysMascotMode(active.copy(running = false, outcome = "COMPLETED"), "visible", false))
    }

    @Test fun optimisticQueueAndAbsentOrSuppressedEvidenceStayStatic() {
        assertNull(mainJarvysMascotMode(active.copy(interactiveOwnerSessionId = null), "visible", true))
        assertNull(mainJarvysMascotMode(active.copy(interactiveOwnerSessionId = "other"), "visible", true))
        assertNull(mainJarvysMascotMode(active.copy(liveMascotTool = null), "visible", true))
        assertNull(mainJarvysMascotMode(active.copy(liveMascotTool = LiveMascotTool("execution", false)), "visible", true))
        assertNull(mainJarvysMascotMode(active.copy(compacting = true), "visible", true))
    }

    @Test fun textLegacyRowsAndReflectionNeverInventActivity() {
        val historical = listOf(
            AgentRunUiEvent(1, "tool", "tool_call", "Working", toolCallId = "old"),
            AgentRunUiEvent(2, "approval", "PENDING", "Waiting", approvalId = "old", approvalStatus = "PENDING"),
        )
        val snapshot = active.copy(liveMascotTool = null, reflecting = true, events = historical)
        assertNull(mainJarvysMascotMode(snapshot, "visible", true))
        assertNull(mainJarvysMascotMode(snapshot.copy(running = false), "visible", true))
    }

    @Test fun onlyExplicitNonrunningTerminalOutcomesMapToExactWireValues() {
        val outcomes = mapOf("COMPLETED" to 6, "FAILED" to 7, "STOPPED" to 8,
            "PARTIAL" to 8, "TIMED_OUT" to 8, "INTERRUPTED" to 8)
        for ((outcome, expected) in outcomes) {
            val terminal = active.copy(running = false, interactiveOwnerSessionId = null,
                outcome = outcome, liveMascotTool = null,
                events = listOf(AgentRunUiEvent(1, "approval", "PENDING", "Old pending", approvalStatus = "PENDING")))
            assertEquals(outcome, expected, mainJarvysMascotMode(terminal, "visible", true))
            assertNull(mainJarvysMascotMode(terminal.copy(running = true), "visible", true))
            assertNull(mainJarvysMascotMode(terminal, "other", true))
        }
        for (outcome in listOf(null, "", "UNKNOWN", "completed", "tool_result", "tool_error")) {
            assertNull(mainJarvysMascotMode(active.copy(running = false, outcome = outcome), "visible", true))
        }
    }
}
