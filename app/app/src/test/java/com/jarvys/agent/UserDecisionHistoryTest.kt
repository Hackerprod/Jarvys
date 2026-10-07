package com.jarvys.agent

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDecisionHistoryTest {
    @Test fun unresolvedRequestRestoresAsUnansweredWithoutReopeningGateAndResolutionIsDurable() {
        val store = LocalRunStore(Files.createTempDirectory("e1-decision-history").toFile())
        val session = "e1-history-${System.nanoTime()}"
        val id = "decision-pending"
        val options = listOf(
            UserDecisionOption("keep", "Keep", "Keep the current choice", UserDecisionRole.DEFAULT),
            UserDecisionOption("change", "Change", "Use the alternative", UserDecisionRole.PRIMARY),
        )
        store.appendUserDecisionRequest(session, id, "Choose", "Body", options, true)
        val unanswered = store.readConversationTimeline(session).single()
        assertEquals("user_decision", unanswered.kind)
        assertEquals("UNANSWERED", unanswered.decisionStatus)
        assertEquals(options, unanswered.decisionOptions)
        assertTrue(unanswered.decisionAllowDismiss)
        assertFalse(UserDecisionRequests.gate.isPending(id))

        store.appendUserDecisionResolution(session, id, "SELECTED", "change", "Change")
        val restored = store.readConversationTimeline(session).single()
        assertEquals("SELECTED", restored.decisionStatus)
        assertEquals("change", restored.decisionOptionId)
        assertEquals("Change", restored.decisionOptionLabel)
        assertFalse(UserDecisionRequests.gate.isPending(id))
    }

    @Test fun refreshDoesNotReplaceLivePendingCardWithRestoredUnansweredSnapshot() {
        val store = LocalRunStore(Files.createTempDirectory("e1-decision-refresh").toFile())
        val session = "e1-live-${System.nanoTime()}"
        val option = UserDecisionOption("yes", "Yes")
        AgentRunUiState.resetSession(session)
        AgentRunUiState.beginRun(session, "Choose")
        store.appendUserDecisionRequest(session, "live-id", "Choose", "Body", listOf(option), false)
        AgentRunUiState.showUserDecision(session, "live-id", "Choose", "Body", listOf(option), false)

        AgentRunUiState.refreshPersistedSession(session, store.readConversationTimeline(session))
        val event = AgentRunUiState.state.value.events.single { it.kind == "user_decision" }
        assertEquals("PENDING", event.decisionStatus)

        AgentRunUiState.updateUserDecision(session, "live-id", "DISMISSED")
        store.appendUserDecisionResolution(session, "live-id", "DISMISSED", null, null)
        val restored = store.readConversationTimeline(session).single()
        assertEquals("DISMISSED", restored.decisionStatus)
        AgentRunUiState.resetSession("e1-decision-history-cleanup")
    }
}
