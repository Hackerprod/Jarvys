package com.jarvys.agent

import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class MainJarvysMascotReducerTest {
    private lateinit var session: String
    private var generation = 1L

    @Before fun reset() {
        session = "mascot-${System.nanoTime()}"
        AgentRunUiState.resetSession(session)
        AgentRunUiState.bindGeneration(session, generation)
        AgentRunUiState.stopPendingGeneration(session, generation)
        AgentRunUiState.resetSession(session)
    }

    @After fun cleanup() {
        AgentRunUiState.bindGeneration(session, Long.MAX_VALUE)
        AgentRunUiState.stopPendingGeneration(session, Long.MAX_VALUE)
        AgentRunUiState.resetSession("$session-cleanup")
    }

    private fun start() {
        AgentRunUiState.beginServiceRun(session, "Inspect", emptyList(), false, generation)
    }

    private fun activity(execution: String = "first", stage: String = "tool_call",
        eventId: String = "$execution:$stage") = ToolActivity(
        eventId, execution, "provider-call", "read", "Read", "", "", stage, "detail", "", "", "", 100,
    )

    private fun deliver(value: ToolActivity, token: Long = generation, chat: String = session) {
        AgentRunUiState.withGeneration(chat, token) { AgentRunUiState.onToolActivity(value) }
    }

    private fun snapshot() = AgentRunUiState.state.value
    private fun mode(visible: String = session, live: Boolean = true) =
        mainJarvysMascotMode(snapshot(), visible, live)

    @Test fun optimisticAndQueuedRunningAreStaticUntilFreshOwnedTypedCall() {
        AgentRunUiState.beginRun(session, "Inspect")
        AgentRunUiState.onToolActivity(activity("optimistic"))
        assertNull(snapshot().liveMascotTool)
        assertNull(mode())
        AgentRunUiState.bindGeneration(session, generation)
        assertNull(mode())
        AgentRunUiState.onProgress("thinking", "Model turn")
        AgentRunUiState.onProgress("tool_call", "legacy tool")
        AgentRunUiState.onToolProgress("tool_call", "legacy", "Read", null)
        assertNull(mode())
        deliver(activity())
        assertEquals(LiveMascotTool("first", true), snapshot().liveMascotTool)
        assertEquals(2, mode())
    }

    @Test fun onlyMatchingProgressRetainsFreshCallAndOrphanProgressCannotStartOne() {
        start()
        deliver(activity("orphan", "tool_progress"))
        assertNull(mode())
        deliver(activity("orphan"))
        assertNull(mode())
        deliver(activity())
        deliver(activity(stage = "tool_progress"))
        assertEquals(2, mode())
        assertEquals("first", snapshot().liveMascotTool?.executionId)
        deliver(activity("unrelated", "tool_progress"))
        assertEquals("first", snapshot().liveMascotTool?.executionId)
    }

    @Test fun terminalToolEventsClearWithoutCompletingOrFailingRun() {
        start()
        for (stage in listOf("tool_result", "tool_error", "tool_not_started", "tool_interrupted")) {
            deliver(activity(stage))
            assertEquals(2, mode())
            deliver(activity(stage, stage))
            assertNull(snapshot().liveMascotTool)
            assertNull(mode())
            assertTrue(snapshot().running)
            assertNull(snapshot().outcome)
        }
    }

    @Test fun definitiveResultAndInterruptionCannotBeRevivedByDuplicateCallOrLateProgress() {
        start()
        for (stage in listOf("tool_result", "tool_error", "tool_not_started", "tool_interrupted")) {
            val call = activity(stage)
            deliver(call)
            deliver(activity(stage, stage))
            deliver(call)
            deliver(activity(stage, "tool_call", "another-start-event:$stage"))
            deliver(activity(stage, "tool_progress"))
            assertNull(mode())
            assertNull(snapshot().liveMascotTool)
            assertEquals(stage, snapshot().events.last { it.toolCallId == stage }.stage)
        }
    }

    @Test fun unrelatedOldResultDoesNotStopNewInvocation() {
        start()
        deliver(activity("old"))
        deliver(activity("new"))
        deliver(activity("old", "tool_result"))
        assertEquals(LiveMascotTool("new", true), snapshot().liveMascotTool)
        assertEquals(2, mode())
    }

    @Test fun actualApprovalSuppressesIdentityUntilGenuinelyNewInvocation() {
        start()
        val call = activity()
        deliver(call)
        AgentRunUiState.showApproval("approval", "Approve read", listOf("Details"), null)
        assertEquals(LiveMascotTool("first", false), snapshot().liveMascotTool)
        assertNull(mode())
        deliver(call)
        deliver(activity(eventId = "duplicate-start"))
        deliver(activity(stage = "tool_progress"))
        assertNull(mode())
        AgentRunUiState.updateApproval("approval", "APPROVED")
        deliver(activity(stage = "tool_progress", eventId = "after-approval"))
        assertNull(mode())
        assertEquals("APPROVED", snapshot().events.single { it.approvalId == "approval" }.approvalStatus)
        deliver(activity("second"))
        assertEquals(2, mode())
    }

    @Test fun actualDecisionSuppressesIdentityAndResolutionDoesNotFabricateActivity() {
        start()
        val call = activity()
        deliver(call)
        AgentRunUiState.showUserDecision(session, "decision", "Choose", "Details", emptyList(), true)
        assertEquals(LiveMascotTool("first", false), snapshot().liveMascotTool)
        deliver(call)
        deliver(activity(stage = "tool_progress"))
        assertNull(mode())
        AgentRunUiState.updateUserDecision(session, "decision", "RESOLVED", "one", "One")
        deliver(activity(eventId = "duplicate-after-decision"))
        assertNull(mode())
        assertEquals("RESOLVED", snapshot().events.single { it.decisionId == "decision" }.decisionStatus)
        deliver(activity("second"))
        assertEquals(2, mode())
    }

    @Test fun wrongSessionDecisionLeavesOwnedEvidenceUnchanged() {
        start()
        deliver(activity())
        AgentRunUiState.showUserDecision("other", "decision", "Choose", "Details", emptyList(), true)
        assertEquals(2, mode())
        assertFalse(snapshot().events.any { it.decisionId == "decision" })
    }

    @Test fun compactionSuppressesSameInvocationWithoutCreatingThinkingOrResumingAfterward() {
        start()
        deliver(activity())
        AgentRunUiState.compactionStarted(session, "Compacting")
        assertNull(mode())
        AgentRunUiState.compactionFinished(session, "Compacted", 5, "manual")
        deliver(activity())
        deliver(activity(stage = "tool_progress"))
        assertEquals(LiveMascotTool("first", false), snapshot().liveMascotTool)
        assertNull(mode())
        deliver(activity("new"))
        assertEquals(2, mode())
    }

    @Test fun bindReplacementClearsMarkerAndRejectsOldGenerationCallbacksAndCompletion() {
        start()
        deliver(activity())
        AgentRunUiState.bindGeneration(session, ++generation)
        assertNull(snapshot().liveMascotTool)
        assertNull(snapshot().outcome)
        assertNull(mode())
        deliver(activity("stale"), token = generation - 1)
        deliver(activity("first", "tool_result"), token = generation - 1)
        AgentRunUiState.completeGeneration(session, generation - 1, "old", "COMPLETED", "stale", "", 0)
        assertTrue(snapshot().running)
        assertNull(mode())
        assertFalse(snapshot().events.any { it.toolCallId == "stale" || it.text == "stale" })
        deliver(activity()) // Historical invocation is not a fresh start in this generation.
        assertNull(mode())
        deliver(activity("new"))
        assertEquals(2, mode())
    }

    @Test fun rebindingSameGenerationPreservesEvidenceButChangingOwnerClearsIt() {
        start()
        deliver(activity())
        AgentRunUiState.bindGeneration(session, generation)
        assertEquals(2, mode())
        AgentRunUiState.bindGeneration("another-owner", generation)
        assertNull(snapshot().liveMascotTool)
        assertNull(mode())
        deliver(activity("wrong-owner"))
        assertNull(mode())
    }

    @Test fun beginAndRegenerationNeverReusePriorTurnMarkerOrRunId() {
        start()
        deliver(activity())
        AgentRunUiState.beginRun(session, "Next")
        assertNull(mode())
        deliver(activity())
        assertNull(mode())
        deliver(activity("second"))
        assertEquals(2, mode())
        AgentRunUiState.beginRegenerationRun(session, "Next")
        assertNull(mode())
        deliver(activity("second"))
        deliver(activity("second", "tool_progress"))
        assertNull(mode())
        deliver(activity("third"))
        assertEquals(2, mode())
    }

    @Test fun regenerationAfterCompletedRunDoesNotTreatRetainedRunIdAsActivityEvidence() {
        start()
        deliver(activity())
        AgentRunUiState.completeGeneration(session, generation, "completed-id", "COMPLETED", "Done", "", 0)
        assertEquals(6, mode())
        AgentRunUiState.beginRegenerationRun(session, "Inspect again")
        AgentRunUiState.bindGeneration(session, ++generation)
        assertEquals("completed-id", snapshot().runId)
        assertNull(snapshot().liveMascotTool)
        assertNull(mode())
    }

    @Test fun restoringHistoricalTypedToolsAndPendingCardsNeverCreatesFreshEvidence() {
        val history = listOf(
            AgentRunUiEvent.activityEvent(1, activity()),
            AgentRunUiEvent(2, "approval", "PENDING", "Old approval", approvalId = "old", approvalStatus = "PENDING"),
        )
        AgentRunUiState.restoreSession(session, history)
        assertNull(mode())
        AgentRunUiState.bindGeneration(session, generation)
        assertNull(mode())
        deliver(activity())
        deliver(activity(stage = "tool_progress"))
        assertNull(mode())
        deliver(activity("fresh"))
        assertEquals(2, mode())
    }

    @Test fun sameOwnerRestoreKeepsActualLiveMarkerAndNeverUsesRestoredPendingRowsAsWaiting() {
        start()
        deliver(activity())
        val persisted = snapshot().events
        AgentRunUiState.restoreSession(session, persisted)
        assertEquals(2, mode())
        AgentRunUiState.showApproval("approval", "Approve", emptyList(), null)
        AgentRunUiState.restoreSession(session, persisted)
        assertEquals(LiveMascotTool("first", false), snapshot().liveMascotTool)
        assertNull(mode())
    }

    @Test fun navigationKeepsOwnershipWithoutShowingActivityInAnotherChat() {
        start()
        deliver(activity())
        val persisted = snapshot().events
        AgentRunUiState.resetSession("other")
        assertNull(snapshot().liveMascotTool)
        assertEquals(session, snapshot().interactiveOwnerSessionId)
        assertNull(mode("other"))
        deliver(activity("hidden"))
        assertFalse(snapshot().events.any { it.toolCallId == "hidden" })
        AgentRunUiState.restoreSession(session, persisted)
        assertEquals(2, mode())
        assertNull(mode(live = false))
    }

    @Test fun approvalWhileAnotherChatVisibleSuppressesSavedOwnedMarker() {
        start()
        deliver(activity())
        val persisted = snapshot().events
        AgentRunUiState.resetSession("other")
        AgentRunUiState.showApproval("hidden-approval", "Approve", emptyList(), null)
        assertFalse(snapshot().events.any { it.approvalId == "hidden-approval" })
        AgentRunUiState.restoreSession(session, persisted)
        assertEquals(LiveMascotTool("first", false), snapshot().liveMascotTool)
        deliver(activity())
        deliver(activity(stage = "tool_progress"))
        assertNull(mode())
    }

    @Test fun hiddenCompletionCannotResurrectMarkerOnReturn() {
        start()
        deliver(activity())
        val persisted = snapshot().events
        AgentRunUiState.resetSession("other")
        AgentRunUiState.completeGeneration(session, generation, "done", "COMPLETED", "private", "", 0)
        assertEquals("other", snapshot().sessionId)
        assertFalse(snapshot().events.any { it.text == "private" })
        AgentRunUiState.restoreSession(session, persisted)
        assertFalse(snapshot().running)
        assertNull(snapshot().liveMascotTool)
        assertNull(mode())
    }

    @Test fun authoritativePersistedResultClearsHiddenInvocationWithoutStartingHistoricalOnes() {
        start()
        deliver(activity())
        val persisted = snapshot().events.filter { it.kind != "tool" } +
            AgentRunUiEvent.activityEvent(100, activity(stage = "tool_result"))
        AgentRunUiState.resetSession("other")
        deliver(activity(stage = "tool_result")) // Existing guard intentionally ignores hidden callbacks.
        AgentRunUiState.restoreSession(session, persisted)
        assertTrue(snapshot().running)
        assertNull(snapshot().liveMascotTool)
        assertNull(mode())
        deliver(activity())
        deliver(activity(stage = "tool_progress"))
        assertNull(mode())
    }

    @Test fun missingPersistedResultObservationDoesNotOverrideAnActuallyOwnedCall() {
        start()
        deliver(activity())
        AgentRunUiState.refreshPersistedSession(session,
            listOf(AgentRunUiEvent.activityEvent(100, activity(stage = "tool_interrupted"))))
        assertEquals(2, mode())
        assertEquals("tool_call", snapshot().events.single { it.kind == "tool" }.stage)
    }

    @Test fun everyDirectTerminalReducerClearsMarker() {
        val complete: List<() -> Unit> = listOf(
            { AgentRunUiState.complete("r", "COMPLETED", "done") },
            { AgentRunUiState.completeChatTurn(session, "r", "done") },
            { AgentRunUiState.fail("failed") },
            { AgentRunUiState.fail(session, "failed") },
            { AgentRunUiState.failChatTurn(session, "failed") },
            { AgentRunUiState.stopPendingGeneration(session, generation) },
            { AgentRunUiState.completeGeneration(session, generation, "r", "PARTIAL", "partial", "", 0) },
        )
        complete.forEachIndexed { index, finish ->
            generation++
            start()
            deliver(activity("terminal-$index"))
            assertEquals(2, mode())
            finish()
            assertNull(snapshot().liveMascotTool)
            assertFalse(snapshot().running)
            assertNotNull(mode())
        }
    }
}
