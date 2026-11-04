package com.jarvys.agent

import com.jarvys.agent.proactive.ProactiveSuggestedReply
import com.jarvys.agent.workspaces.WorkspaceIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [com.jarvys.agent.ArtifactOsShadow::class])
class ProactiveReplyUiSelectionTest {
    @get:org.junit.Rule val chatTestStore = ChatTestStore()

    @Test fun sameInstanceOptionListMutationCannotRetargetTheDisplayedSelection() {
        val session = WorkspaceIdentity.CENTRAL_SESSION
        val options = mutableListOf(ProactiveSuggestedReply("Yes", "Original selected text"))
        val displayed = AgentRunUiEvent.proactiveMessageEvent(2L, "assistant", "Notice", 1L,
            "mutable-notice", "thread", options, false)
        AgentRunUiState.restoreSession(session, listOf(displayed))
        UserDecisionUiAvailability.setChatVisible(true)
        try {
            assertTrue(AgentRunUiState.containsExactProactiveSelection(session, displayed, 0))
            options[0] = ProactiveSuggestedReply("Yes", "Changed selected text")
            assertFalse("The same event reference must not permit an in-place option replacement",
                AgentRunUiState.containsExactProactiveSelection(session, displayed, 0))
            assertFalse(displayed.hasUnchangedProactiveSelection())
        } finally {
            UserDecisionUiAvailability.setChatVisible(false)
            AgentRunUiState.resetSession(session)
        }
    }

    @Test fun onlyTheCurrentDisplayedInstanceAndValidUnusedOptionAreSelectable() {
        val session = WorkspaceIdentity.CENTRAL_SESSION
        val displayed = AgentRunUiEvent.proactiveMessageEvent(1L, "assistant", "Notice", 1L,
            "notice-id", "thread", listOf(ProactiveSuggestedReply("Yes", "Yes please")), false)
        AgentRunUiState.restoreSession(session, listOf(displayed))
        UserDecisionUiAvailability.setChatVisible(true)
        try {
            assertTrue(AgentRunUiState.containsExactProactiveSelection(session, displayed, 0))
            assertFalse(AgentRunUiState.containsExactProactiveSelection(session, displayed.copy(), 0))
            assertFalse(AgentRunUiState.containsExactProactiveSelection(session, displayed, -1))
            assertFalse(AgentRunUiState.containsExactProactiveSelection(session, displayed, 1))
            assertFalse(AgentRunUiState.containsExactProactiveSelection("another-session", displayed, 0))
            val originalVisibilityEpoch = requireNotNull(UserDecisionUiAvailability.captureVisibleEpoch())
            val originalSessionEpoch = requireNotNull(AgentRunUiState.captureVisibleSessionEpoch(session))
            UserDecisionUiAvailability.setChatVisible(false)
            assertFalse("Leaving the chat route invalidates the captured selection",
                AgentRunUiState.containsExactProactiveSelection(session, displayed, 0))
            UserDecisionUiAvailability.setChatVisible(true)
            assertFalse("Returning must not revive the old captured presentation epoch",
                UserDecisionUiAvailability.isCurrentVisibleEpoch(originalVisibilityEpoch))
            assertTrue(UserDecisionUiAvailability.isCurrentVisibleEpoch(requireNotNull(UserDecisionUiAvailability.captureVisibleEpoch())))
            assertTrue(AgentRunUiState.containsExactProactiveSelection(session, displayed, 0))
            AgentRunUiState.showSession("workspace-native-route")
            assertFalse("A retained Central snapshot is not a visible native selection",
                AgentRunUiState.containsExactProactiveSelection(session, displayed, 0))
            AgentRunUiState.showSession(session)
            assertFalse("Returning to Central must not revive an old session epoch",
                AgentRunUiState.isCurrentVisibleSessionEpoch(session, originalSessionEpoch))
            assertTrue(AgentRunUiState.isCurrentVisibleSessionEpoch(session, requireNotNull(AgentRunUiState.captureVisibleSessionEpoch(session))))
            assertTrue(AgentRunUiState.containsExactProactiveSelection(session, displayed, 0))
            val replacement = displayed.copy(text = "New displayed revision")
            AgentRunUiState.restoreSession(session, listOf(replacement))
            assertFalse(AgentRunUiState.containsExactProactiveSelection(session, displayed, 0))
            assertTrue(AgentRunUiState.containsExactProactiveSelection(session, replacement, 0))
            val consumed = replacement.copy(proactiveRepliesUsed = true)
            AgentRunUiState.restoreSession(session, listOf(consumed))
            assertFalse(AgentRunUiState.containsExactProactiveSelection(session, consumed, 0))
        } finally {
            UserDecisionUiAvailability.setChatVisible(false)
            AgentRunUiState.resetSession(session)
        }
    }
}
