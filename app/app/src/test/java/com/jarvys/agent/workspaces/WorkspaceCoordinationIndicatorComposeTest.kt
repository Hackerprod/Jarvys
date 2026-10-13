package com.jarvys.agent.workspaces

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jarvys.agent.R
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
            ConversationDrawer(emptyList(), WorkspaceIdentity.CENTRAL_SESSION, null, "", false, null, true,
                {}, {}, {}, {}, onOpenCentral = {},
                workspaces = listOf(WorkspaceSidebarRow("space", "Project", WorkspaceUiStatus.WAITING,
                    coordination = summary.value)), onOpenWorkspace = { opened = it })
        } }
        compose.onAllNodesWithTag("workspace-coordination-indicator", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText(quantityText(R.plurals.workspace_coordination_live, 3)).assertExists()
        compose.onNodeWithTag("drawer-workspace-space").performClick()
        assertEquals("space", opened)
        compose.runOnIdle { summary.value = WorkspaceCoordinationSummary(uncertain = 1, reviewRequired = true, pageLimited = true) }
        compose.onAllNodesWithTag("workspace-coordination-indicator", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText(quantityText(R.plurals.workspace_coordination_live, 3)).assertDoesNotExist()
        compose.onNodeWithText(quantityText(R.plurals.workspace_coordination_uncertain, 1)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_coordination_review)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_coordination_page_scope)).assertExists()
        compose.runOnIdle { summary.value = WorkspaceCoordinationSummary(historical = 3) }
        compose.onNodeWithTag("workspace-coordination-indicator", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { summary.value = WorkspaceCoordinationSummary() }
        compose.onNodeWithTag("workspace-coordination-indicator", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("model_wait", substring = true).assertDoesNotExist()
        compose.onNodeWithText("model_response", substring = true).assertDoesNotExist()
    }

    @Test fun reopenedHistoricalDetailShowsNoLiveReplyClaimOrNewAction() {
        compose.setContent { MaterialTheme {
            WorkspaceDetailScreen(WorkspaceDetailUiState("space", "Project", WorkspaceUiStatus.INTERRUPTED,
                coordination = WorkspaceCoordinationSummary(historical = 2)), { _, _ -> }, { _, _ -> }, { _, _ -> }, {})
        } }
        compose.onAllNodesWithTag("workspace-coordination-indicator", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText(quantityText(R.plurals.workspace_coordination_historical, 2)).assertExists()
        compose.onNodeWithText(quantityText(R.plurals.workspace_coordination_live, 2)).assertDoesNotExist()
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
            WorkspaceCoordinationIndicator(summary.value, showHistory = showHistory.value)
        } }
        compose.onNodeWithTag("workspace-coordination-indicator").assertDoesNotExist()
        for (count in 1..2) {
            compose.runOnIdle {
                summary.value = WorkspaceCoordinationSummary(live = count, uncertain = count, historical = count)
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
        compose.onNodeWithText(quantityText(R.plurals.workspace_coordination_historical, 2)).assertDoesNotExist()
        compose.onNodeWithText(quantityText(R.plurals.workspace_coordination_live, 2)).assertIsDisplayed()
        compose.onNodeWithText(quantityText(R.plurals.workspace_coordination_uncertain, 2)).assertIsDisplayed()
        compose.runOnIdle {
            showHistory.value = true
            summary.value = WorkspaceCoordinationSummary(reviewRequired = true, pageLimited = true, unavailable = true)
        }
        compose.onAllNodesWithTag("workspace-coordination-indicator").assertCountEquals(1)
        counters.forEach { resource ->
            for (count in 0..2) compose.onNodeWithText(quantityText(resource, count)).assertDoesNotExist()
        }
        listOf(R.string.workspace_coordination_review, R.string.workspace_coordination_page_scope,
            R.string.workspace_coordination_unavailable).forEach { resource ->
            compose.onNodeWithText(compose.activity.getString(resource)).assertIsDisplayed()
        }
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { summary.value = WorkspaceCoordinationSummary() }
        compose.onNodeWithTag("workspace-coordination-indicator").assertDoesNotExist()
    }

    private fun quantityText(resource: Int, count: Int) =
        compose.activity.resources.getQuantityString(resource, count, count)
}
