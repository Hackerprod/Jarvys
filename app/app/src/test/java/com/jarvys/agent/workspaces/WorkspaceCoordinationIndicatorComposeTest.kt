package com.jarvys.agent.workspaces

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.R
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.ui.chat.ConversationDrawer
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h900dp-port-mdpi")
class WorkspaceCoordinationIndicatorComposeTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    @Test fun drawerConsolidatesNativeMetadataAndClearsHistoricalOnlyInEnglish() = drawerContract()
    @Test @Config(qualifiers = "es-rES-w360dp-h900dp-port-mdpi")
    fun drawerConsolidatesNativeMetadataAndClearsHistoricalOnlyInSpanish() = drawerContract()

    private fun drawerContract() {
        val summary = mutableStateOf(WorkspaceCoordinationSummary(live = 3))
        var opened = ""
        compose.setContent { MaterialTheme {
            WorkspaceListScreen(workspaces = listOf(WorkspaceSidebarRow("space", "Project", WorkspaceUiStatus.WAITING,
                    coordination = summary.value)), onOpenWorkspace = { opened = it })
        } }
        compose.onAllNodesWithTag("workspace-coordination-indicator", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText(quantityText(R.plurals.workspace_coordination_live, 3)).assertExists()
        compose.onNodeWithTag("workspace-list-row-space").performClick()
        assertEquals("space", opened)
        compose.runOnIdle { summary.value = WorkspaceCoordinationSummary(uncertain = 1, pageLimited = true) }
        compose.onAllNodesWithTag("workspace-coordination-indicator", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText(quantityText(R.plurals.workspace_coordination_live, 3)).assertDoesNotExist()
        compose.onNodeWithText(quantityText(R.plurals.workspace_coordination_uncertain, 1)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_coordination_page_scope)).assertExists()
        compose.runOnIdle { summary.value = WorkspaceCoordinationSummary(historical = 3) }
        compose.onNodeWithTag("workspace-coordination-indicator", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { summary.value = WorkspaceCoordinationSummary() }
        compose.onNodeWithTag("workspace-coordination-indicator", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("model_wait", substring = true).assertDoesNotExist()
        compose.onNodeWithText("model_response", substring = true).assertDoesNotExist()
    }

    @Test fun workspaceChatUsesItsTimelineInsteadOfCoordinationEvidenceCards() {
        val workspaceId = "11111111-1111-1111-1111-111111111111"
        val sessionId = WorkspaceIdentity.executionSessionId(workspaceId)
        val event = AgentRunUiEvent(1L, "assistant", text = "Conversation evidence")
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        compose.setContent { MaterialTheme {
            WorkspaceDetailScreen(WorkspaceChatUiState(workspaceId, sessionId, "Project", WorkspaceUiStatus.INTERRUPTED,
                listOf(event)), AgentRunUiSnapshot(sessionId = sessionId, events = listOf(event)), connectors,
                showAgentEvents = true, onOpenPreview = {}, onOpenSkillFile = {}, onOpenCrewMission = {})
        } }
        compose.onNodeWithTag("chat-message-list").assertIsDisplayed()
        compose.onNodeWithText("Conversation evidence", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("workspace-coordination-indicator", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(quantityText(R.plurals.workspace_coordination_historical, 2)).assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun englishCountersPreserveCountsVisibilityAndReadOnlySemantics() = counterContract()
    @Test @Config(qualifiers = "es-rES-w360dp-h900dp-port-mdpi")
    fun spanishCountersPreserveCountsVisibilityAndReadOnlySemantics() = counterContract()

    private fun counterContract() {
        val summary = mutableStateOf(WorkspaceCoordinationSummary())
        val showHistory = mutableStateOf(true)
        val counters = listOf(R.plurals.workspace_coordination_live,
            R.plurals.workspace_coordination_uncertain, R.plurals.workspace_coordination_historical)
        compose.setContent { MaterialTheme {
            // Keep a real window-sized host while the zero-counter child has no layout node.
            Box(Modifier.fillMaxSize()) {
                WorkspaceCoordinationIndicator(summary.value, showHistory = showHistory.value)
            }
        } }
        compose.onNodeWithTag("workspace-coordination-indicator").assertDoesNotExist()
        for (count in 1..2) {
            compose.runOnIdle {
                summary.value = WorkspaceCoordinationSummary(live = count, uncertain = count, historical = count)
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("workspace-coordination-indicator").fetchSemanticsNodes().size == 1 &&
                    counters.all { resource ->
                        compose.onAllNodesWithText(quantityText(resource, count)).fetchSemanticsNodes().size == 1
                    }
            }
            compose.onAllNodesWithTag("workspace-coordination-indicator").assertCountEquals(1)
            counters.forEach { resource ->
                compose.onNodeWithText(quantityText(resource, count)).assertIsDisplayed()
                compose.onNodeWithText(quantityText(resource, 0)).assertDoesNotExist()
            }
            compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(0)
            compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
        }
        compose.runOnIdle { showHistory.value = false }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(quantityText(R.plurals.workspace_coordination_historical, 2)).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText(quantityText(R.plurals.workspace_coordination_historical, 2)).assertDoesNotExist()
        compose.onNodeWithText(quantityText(R.plurals.workspace_coordination_live, 2)).assertIsDisplayed()
        compose.onNodeWithText(quantityText(R.plurals.workspace_coordination_uncertain, 2)).assertIsDisplayed()
        compose.runOnIdle {
            showHistory.value = true
            summary.value = WorkspaceCoordinationSummary(pageLimited = true, unavailable = true)
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(compose.activity.getString(R.string.workspace_coordination_unavailable))
                .fetchSemanticsNodes().size == 1
        }
        compose.onAllNodesWithTag("workspace-coordination-indicator").assertCountEquals(1)
        counters.forEach { resource ->
            for (count in 0..2) compose.onNodeWithText(quantityText(resource, count)).assertDoesNotExist()
        }
        listOf(R.string.workspace_coordination_page_scope,
            R.string.workspace_coordination_unavailable).forEach { resource ->
            compose.onNodeWithText(compose.activity.getString(resource)).assertIsDisplayed()
        }
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { summary.value = WorkspaceCoordinationSummary() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("workspace-coordination-indicator").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("workspace-coordination-indicator").assertDoesNotExist()
    }

    private fun quantityText(resource: Int, count: Int) =
        compose.activity.resources.getQuantityString(resource, count, count)
}
