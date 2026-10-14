package com.jarvys.agent.workspaces

import com.jarvys.agent.AgentRunUiEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class WorkspaceChatProjectionTest {
    @Test fun chatProjectionUsesTheRegistryWorkspaceIdentityAndKeepsTimelineOrder() {
        val workspaceId = "11111111-1111-1111-1111-111111111111"
        val events = listOf(
            AgentRunUiEvent(1L, "user", text = "Worker question"),
            AgentRunUiEvent.toolEvent(2L, "tool_result", "Read file", "Read the index", "read-1", null, 2L),
            AgentRunUiEvent(3L, "assistant", text = "Worker answer"),
        )
        val detail = WorkspaceDetailUiState(workspaceId, "Workspace from registry", WorkspaceUiStatus.ACTIVE)

        val projected = workspaceChatUiState(detail, events)

        assertEquals(workspaceId, projected.workspaceId)
        assertEquals(WorkspaceIdentity.executionSessionId(workspaceId), projected.executionSessionId)
        assertEquals(detail.title, projected.title)
        assertEquals(detail.status, projected.status)
        assertEquals(events, projected.events)
        assertNotSame(events, projected.events)
    }
}
