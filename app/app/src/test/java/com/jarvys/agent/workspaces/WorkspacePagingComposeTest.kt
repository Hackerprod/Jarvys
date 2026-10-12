package com.jarvys.agent.workspaces

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jarvys.agent.R
import com.jarvys.agent.ui.chat.ConversationDrawer
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h900dp-port-mdpi")
class WorkspacePagingComposeTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    @Test fun catalogErrorAndLoadingNeverPretendTheCatalogIsEmpty() {
        val failed = mutableStateOf(true)
        val loading = mutableStateOf(false)
        var retried = 0
        compose.setContent { MaterialTheme {
            ConversationDrawer(emptyList(), "ordinary", null, "", false, null, true, {}, {}, {}, {},
                onOpenCentral = {}, workspaceError = if (failed.value) "Catalog unavailable" else null,
                workspacesLoading = loading.value, onRetryWorkspaces = { retried++ })
        } }
        compose.onNodeWithTag("drawer-workspaces-error").assertExists()
        compose.onNodeWithTag("drawer-workspaces-empty").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_retry)).performClick()
        assertEquals(1, retried)
        compose.runOnIdle { failed.value = false; loading.value = true }
        compose.onNodeWithTag("drawer-workspaces-loading").assertExists()
        compose.onNodeWithTag("drawer-workspaces-empty").assertDoesNotExist()
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithTag("drawer-workspaces-empty").assertExists()
    }

    @Test fun detailPageButtonsReturnNavigationOnlyAndExposeLimitedSummary() {
        var previous = 0; var next = 0; var first = 0; var commands = 0
        compose.setContent { MaterialTheme {
            WorkspaceDetailScreen(WorkspaceDetailUiState("workspace", "Page", WorkspaceUiStatus.UNKNOWN,
                nextCursor = "next", statusPageLimited = true),
                { _, _ -> commands++ }, { _, _ -> commands++ }, { _, _ -> commands++ }, {},
                canPreviousPage = true, canFirstPage = true,
                onPreviousPage = { previous++ }, onNextPage = { next++ }, onFirstPage = { first++ })
        } }
        compose.onNodeWithTag("workspace-status-page-scope").assertExists()
        compose.onNodeWithTag("workspace-first-page").performScrollTo().performClick()
        compose.onNodeWithTag("workspace-previous-page").performScrollTo().performClick()
        compose.onNodeWithTag("workspace-next-page").performScrollTo().performClick()
        assertEquals(listOf(1, 1, 1, 0), listOf(previous, next, first, commands))
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun pendingSavedNoticeDoesNotReplaceWorkerStatusOrDisableTargetedPause() {
        var paused = 0
        val row = WorkspaceAssignmentUi("assignment", 3, "Worker", "Mission", WorkspaceUiStatus.WAITING,
            recoveryNote = "Prior uncertain evidence remains visible", canPause = true, pendingControlSaved = true)
        compose.setContent { MaterialTheme {
            WorkspaceDetailScreen(WorkspaceDetailUiState("workspace", "Workspace", WorkspaceUiStatus.WAITING, listOf(row)),
                { _, _ -> }, { id, revision -> assertEquals("assignment", id); assertEquals(3L, revision); paused++ }, { _, _ -> }, {})
        } }
        compose.onNodeWithTag("workspace-pending-control-assignment").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Prior uncertain evidence remains visible").assertExists()
        compose.onAllNodesWithText(compose.activity.getString(R.string.workspace_status_waiting)).assertCountEquals(2)
        compose.onNodeWithTag("workspace-pause-assignment").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, paused)
    }
}
