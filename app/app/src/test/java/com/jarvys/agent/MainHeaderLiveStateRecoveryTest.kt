package com.jarvys.agent

import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class MainHeaderLiveStateRecoveryTest {
    private lateinit var session: String
    private val generation = 420L
    @Before fun start() {
        session = "header-recovery-${System.nanoTime()}"
        AgentRunUiState.resetSession(session)
        AgentRunUiState.beginServiceRun(session, "Inspect", emptyList(), false, generation)
    }
    @After fun stop() {
        AgentRunUiState.bindGeneration(session, Long.MAX_VALUE)
        AgentRunUiState.stopPendingGeneration(session, Long.MAX_VALUE)
        AgentRunUiState.resetSession("$session-cleanup")
    }
    private fun state() = AgentRunUiState.state.value
    private fun mode() = mainJarvysMascotMode(state(), session, true)
    private fun progress(stage: String, token: Long = generation) =
        AgentRunUiState.withGeneration(session, token) { AgentRunUiState.onProgress(stage, stage) }
    private fun tool(id: String, stage: String = "tool_call") = ToolActivity(
        "$id:$stage", id, id, "read", "Read", "", "", stage, "", "", "", "", 1L)

    @Test fun modelWaitIsCurrentEvidenceAndResponseClearsIt() {
        assertEquals("$session:$generation", state().liveRunIdentity)
        progress("thinking")
        assertNull(mode())
        progress("model_wait", generation - 1)
        assertNull(mode())
        progress("model_wait")
        val first = state().liveModelWaitId
        assertNotNull(first)
        assertEquals(1, mode())
        progress("model_response")
        assertNull(state().liveModelWaitId)
        assertNull(mode())
        progress("model_wait")
        assertNotEquals(first, state().liveModelWaitId)
    }

    @Test fun realToolSupersedesModelWaitAndTerminalToolDoesNotFailRun() {
        progress("model_wait")
        AgentRunUiState.withGeneration(session, generation) { AgentRunUiState.onToolActivity(tool("call")) }
        assertNull(state().liveModelWaitId)
        assertEquals(2, mode())
        AgentRunUiState.withGeneration(session, generation) { AgentRunUiState.onToolActivity(tool("call", "tool_error")) }
        assertNull(mode())
        assertTrue(state().running)
        assertNull(state().outcome)
    }

    @Test fun liveDecisionAndApprovalWaitUntilMatchingResolution() {
        progress("model_wait")
        AgentRunUiState.showUserDecision(session, "choose", "Choose", "Details", emptyList(), true)
        assertEquals("decision:choose", state().liveUserWaitId)
        assertEquals(5, mode())
        assertNull(state().liveModelWaitId)
        AgentRunUiState.updateUserDecision(session, "unrelated", "SELECTED")
        assertEquals(5, mode())
        AgentRunUiState.updateUserDecision(session, "choose", "SELECTED")
        assertNull(mode())
        AgentRunUiState.showApproval("approve", "Approve", emptyList(), null)
        assertEquals("approval:approve", state().liveUserWaitId)
        assertEquals(5, mode())
        AgentRunUiState.updateApproval("approve", "APPROVED")
        assertNull(mode())
    }

    @Test fun compactionAndNewGenerationClearTransientEvidence() {
        progress("model_wait")
        AgentRunUiState.compactionStarted(session, "Compacting")
        assertNull(state().liveModelWaitId)
        assertNull(mode())
        AgentRunUiState.compactionCancelled(session)
        assertNull(mode())
        progress("model_wait")
        AgentRunUiState.beginServiceRun(session, "Next", emptyList(), false, generation + 1)
        assertNull(state().liveModelWaitId)
        assertNull(state().liveUserWaitId)
        assertNull(mode())
        assertEquals("$session:${generation + 1}", state().liveRunIdentity)
    }

    @Test fun terminalPreservesGenerationIdentityButClearsActivity() {
        progress("model_wait")
        AgentRunUiState.completeGeneration(session, generation, "run", "FAILED", "Failed", "", 0L)
        assertFalse(state().running)
        assertNull(state().liveModelWaitId)
        assertNull(state().liveUserWaitId)
        assertEquals("$session:$generation", state().liveRunIdentity)
        assertEquals(7, mode())
    }

    @Test fun restoredPendingRowsCannotProduceWaitEvidence() {
        AgentRunUiState.stopPendingGeneration(session, generation)
        AgentRunUiState.restoreSession(session, listOf(
            AgentRunUiEvent(1, "progress", "model_wait", "old"),
            AgentRunUiEvent(2, "user_decision", "PENDING", "old", decisionId = "old", decisionStatus = "PENDING")))
        assertNull(state().liveModelWaitId)
        assertNull(state().liveUserWaitId)
        assertNull(state().liveRunIdentity)
        assertNull(mode())
    }

    @Test fun seedIsHiddenButCreateEditAndUndoRemainVisible() {
        for ((index, operation) in listOf("SEED", "CREATE", "EDIT", "UNDO").withIndex()) {
            AgentRunUiState.memoryChanged(MemoryStore.Revision(index.toLong(), "now", MemoryStore.Actor.AGENT,
                session, "note.md", "", operation, false, "before", true, "after"))
        }
        assertEquals(listOf("CREATE", "EDIT", "UNDO"), state().events.filter { it.kind == "memory" }.map { it.stage })
    }
}
