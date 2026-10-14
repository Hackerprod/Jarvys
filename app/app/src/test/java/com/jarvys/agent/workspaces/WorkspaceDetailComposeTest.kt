package com.jarvys.agent.workspaces

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h900dp-port-mdpi")
class WorkspaceDetailComposeTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    private val workspaceId = "11111111-1111-1111-1111-111111111111"

    @Test fun detailRendersTheWorkspaceSessionAsTheSharedConversationTimeline() {
        val sessionId = WorkspaceIdentity.executionSessionId(workspaceId)
        val user = AgentRunUiEvent(1L, "user", text = "Worker question")
        val firstTool = AgentRunUiEvent.toolEvent(2L, "tool_result", "Read file", "Read the index", "read-1", null, 2L)
        val secondTool = AgentRunUiEvent.toolEvent(3L, "tool_result", "Write file", "Updated the index", "write-1", null, 3L)
        val assistant = AgentRunUiEvent(4L, "assistant", text = "Workspace answer")
        val events = listOf(user, firstTool, secondTool, assistant)
        val state = WorkspaceChatUiState(workspaceId, sessionId, "Workspace title", WorkspaceUiStatus.ACTIVE, events)
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })

        compose.setContent { MaterialTheme {
            WorkspaceDetailScreen(state = state,
                runSnapshot = AgentRunUiSnapshot(sessionId = sessionId, events = events),
                connectorRegistry = connectors, showAgentEvents = true,
                onOpenPreview = {}, onOpenSkillFile = {}, onOpenCrewMission = {})
        } }

        compose.onNodeWithTag("workspace-chat-timeline").assertIsDisplayed()
        compose.onNodeWithTag("chat-message-list").assertIsDisplayed()
        compose.onNodeWithText("Worker question").assertIsDisplayed()
        compose.onNodeWithText("Workspace answer").assertIsDisplayed()
        compose.onNodeWithTag("tool-group-2").assertIsDisplayed()
        compose.onNodeWithText("Mission").assertDoesNotExist()
        compose.onNodeWithText("Recovery").assertDoesNotExist()
        compose.onNodeWithText("Recorded result").assertDoesNotExist()
    }

    @Test fun loadingErrorAndEmptyStatesRemainDistinctFromAnEmptyConversation() {
        val sessionId = WorkspaceIdentity.executionSessionId(workspaceId)
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        val state = mutableStateOf(WorkspaceChatUiState(workspaceId, sessionId, "Workspace title",
            WorkspaceUiStatus.UNKNOWN, loading = true))
        var retries = 0
        compose.setContent { MaterialTheme {
            WorkspaceDetailScreen(state = state.value, runSnapshot = AgentRunUiSnapshot(sessionId = sessionId),
                connectorRegistry = connectors, showAgentEvents = true,
                onOpenPreview = {}, onOpenSkillFile = {}, onOpenCrewMission = {}, onRetry = { retries++ })
        } }
        compose.onNodeWithTag("workspace-chat-loading").assertIsDisplayed()
        compose.onNodeWithTag("workspace-chat-empty").assertDoesNotExist()
        compose.runOnIdle { state.value = WorkspaceChatUiState(workspaceId, sessionId, "Workspace title",
            WorkspaceUiStatus.UNKNOWN, error = "Stored conversation is unavailable") }
        compose.onNodeWithTag("workspace-chat-error").assertIsDisplayed()
        compose.onNodeWithTag("workspace-chat-retry").performClick()
        assertEquals(1, retries)
        compose.runOnIdle { state.value = WorkspaceChatUiState(workspaceId, sessionId, "Workspace title",
            WorkspaceUiStatus.UNKNOWN) }
        compose.onNodeWithTag("workspace-chat-empty").assertIsDisplayed()
        compose.onNodeWithTag("workspace-chat-loading").assertDoesNotExist()
        compose.onNodeWithTag("workspace-chat-error").assertDoesNotExist()
    }
}
