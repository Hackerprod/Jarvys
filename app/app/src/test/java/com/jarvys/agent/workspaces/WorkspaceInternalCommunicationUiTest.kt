package com.jarvys.agent.workspaces

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.ConversationInput
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h900dp-port-mdpi")
class WorkspaceInternalCommunicationUiTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    @Test fun workspaceListShowsCoordinationCountsWithoutMessageBodiesOrPayloads() {
        val body = "PRIVATE worker message body"
        val inboxPayload = "PRIVATE inbox JSON payload"
        val replyPayload = "PRIVATE reply payload"
        compose.setContent { MaterialTheme {
            WorkspaceListScreen(listOf(WorkspaceSidebarRow("space", "Project", WorkspaceUiStatus.WAITING,
                statusPageLimited = true,
                coordination = WorkspaceCoordinationSummary(pendingInputs = 2, uncertainReplies = 1,
                    unavailable = false))), onOpenWorkspace = {})
        } }

        compose.onNodeWithTag("workspace-list-row-space").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.resources.getQuantityString(
            com.jarvys.agent.R.plurals.workspace_coordination_pending, 2, 2)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.resources.getQuantityString(
            com.jarvys.agent.R.plurals.workspace_coordination_uncertain, 1, 1)).assertIsDisplayed()
        listOf(body, inboxPayload, replyPayload).forEach { payload ->
            compose.onAllNodesWithText(payload, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test fun workspaceChatHidesAgentMessagesAndKeepsTheOwnerMessageVisible() {
        val workspaceId = "11111111-1111-1111-1111-111111111111"
        val sessionId = WorkspaceIdentity.executionSessionId(workspaceId)
        val ownerMessage = AgentRunUiEvent.messageEvent(1L, "user", "Owner assignment", 110L)
        val internal = AgentRunUiEvent.messageEvent(2L, "assistant", "Internal BLOCKER text", 120L)
            .copy(conversationInputOrigin = ConversationInput.Origin.AGENT)
        val workerAnswer = AgentRunUiEvent.messageEvent(3L, "assistant", "Worker answer is internal too.", 130L)
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        compose.setContent { MaterialTheme {
            WorkspaceDetailScreen(WorkspaceChatUiState(workspaceId, sessionId, "Project", WorkspaceUiStatus.INTERRUPTED,
                listOf(ownerMessage, internal, workerAnswer)),
                AgentRunUiSnapshot(sessionId = sessionId, events = listOf(ownerMessage, internal, workerAnswer)), connectors,
                onOpenPreview = {}, onOpenSkillFile = {}, onOpenCrewMission = {})
        } }

        compose.onNodeWithTag("chat-message-list").assertIsDisplayed()
        compose.onAllNodesWithText("Internal BLOCKER text", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("Worker answer is internal too.", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Owner assignment", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("INCORPORATED_IN_CHECKPOINT", useUnmergedTree = true).assertCountEquals(0)
    }
}
