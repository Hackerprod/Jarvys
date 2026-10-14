package com.jarvys.agent

import com.jarvys.agent.proactive.ProactiveConversation
import com.jarvys.agent.tasks.ScheduledTaskConversation
import org.junit.Assert.*
import org.junit.Test

class ConversationActionPolicyTest {
    @Test fun managedConversationsCannotBeDeletedAsOrdinaryChats() {
        assertTrue(isManagedSystemConversation(ProactiveConversation.SESSION_ID))
        assertTrue(isManagedSystemConversation(ScheduledTaskConversation.SESSION_ID))
        assertFalse(isManagedSystemConversation("normal-session"))
    }
    @Test fun eachActiveWorkSourcePreventsRemoval() {
        val idle = AgentRunUiSnapshot()
        assertFalse(conversationRemovalBlocked(idle, false, false, true))
        assertTrue(conversationRemovalBlocked(idle.copy(running = true), false, false, true))
        assertTrue(conversationRemovalBlocked(idle.copy(stopping = true), false, false, true))
        assertTrue(conversationRemovalBlocked(idle.copy(compacting = true), false, false, true))
        assertTrue(conversationRemovalBlocked(idle.copy(reflecting = true), false, false, true))
        assertTrue(conversationRemovalBlocked(idle, true, false, true))
        assertTrue(conversationRemovalBlocked(idle, false, true, true))
        assertTrue(conversationRemovalBlocked(idle, false, false, false))
        assertTrue(conversationRemovalBlocked(idle, false, false, true, true))
    }
    @Test fun onlyRemovingTheCurrentConversationChangesNavigation() {
        for (action in ConversationAction.entries) {
            assertEquals(action == ConversationAction.ARCHIVE || action == ConversationAction.DELETE,
                conversationActionLeavesCurrent(action, "a", "a"))
            assertFalse(conversationActionLeavesCurrent(action, "a", "b"))
        }
    }
    @Test fun deletingTheCurrentConversationReturnsToAgent() {
        assertTrue(conversationActionNavigatesToAgent(ConversationAction.DELETE, "a", "a"))
        assertFalse(conversationActionNavigatesToAgent(ConversationAction.DELETE, "a", "b"))
        assertFalse(conversationActionNavigatesToAgent(ConversationAction.ARCHIVE, "a", "a"))
    }
    @Test fun centralAndExecutionSessionsRejectRenameAndPinActions() {
        val protectedActions = listOf(ConversationAction.RENAME, ConversationAction.PIN,
            ConversationAction.DELETE, ConversationAction.ARCHIVE)
        protectedActions.forEach { action ->
            assertTrue(conversationActionForbiddenForWorkspace(action, com.jarvys.agent.workspaces.WorkspaceIdentity.CENTRAL_SESSION))
            assertTrue(conversationActionForbiddenForWorkspace(action,
                com.jarvys.agent.workspaces.WorkspaceIdentity.executionSessionId("00000000-0000-0000-0000-000000000000")))
        }
        assertFalse(conversationActionForbiddenForWorkspace(ConversationAction.RENAME, "ordinary"))
        assertFalse(conversationActionForbiddenForWorkspace(ConversationAction.PIN, "ordinary"))
    }
    @Test fun drawerSeparatesArchivedChatsAndKeepsPinsFirstWithoutDuplicates() {
        val olderPin = RunHistoryItem("1", "one", "CHAT", 0, 1, 1.0, "a", pinned = true)
        val newer = RunHistoryItem("2", "two", "CHAT", 0, 1, 4.0, "b")
        val archived = RunHistoryItem("3", "three", "CHAT", 0, 1, 5.0, "c", archived = true)
        val items = listOf(newer, archived, olderPin, olderPin.copy(id = "duplicate"))
        assertEquals(listOf("a", "b"), drawerChatsForDisplay(items, "").map { it.sessionId })
        assertEquals(listOf("c"), drawerChatsForDisplay(items, "", archived = true).map { it.sessionId })
    }
}
