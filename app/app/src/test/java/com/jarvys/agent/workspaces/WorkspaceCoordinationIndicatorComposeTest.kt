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
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_coordination_live, 3)).assertExists()
        compose.onNodeWithTag("drawer-workspace-space").performClick()
        assertEquals("space", opened)
        compose.runOnIdle { summary.value = WorkspaceCoordinationSummary(uncertain = 1, reviewRequired = true, pageLimited = true) }
        compose.onAllNodesWithTag("workspace-coordination-indicator", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_coordination_live, 3)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_coordination_uncertain, 1)).assertExists()
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
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_coordination_historical, 2)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_coordination_live, 2)).assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }
}
