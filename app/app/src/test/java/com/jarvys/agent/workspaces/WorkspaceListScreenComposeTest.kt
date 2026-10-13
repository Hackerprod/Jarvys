package com.jarvys.agent.workspaces

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jarvys.agent.R
import com.jarvys.agent.ui.chat.awaitReactionDrawIdle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The standalone list has no execution surface; callbacks carry only catalog navigation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class WorkspaceListScreenComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val rows = listOf(
        WorkspaceSidebarRow("alpha", "Alpha recorded project", WorkspaceUiStatus.WAITING),
        WorkspaceSidebarRow("beta", "Beta recorded project", WorkspaceUiStatus.INTERRUPTED),
    )

    @Before fun controlClock() { compose.mainClock.autoAdvance = false }

    @Test fun listRowsNavigateByExactIdentityWithoutChatComposerOrExecutionControls() {
        val opened = mutableListOf<String>()
        compose.setContent { MaterialTheme { WorkspaceListScreen(rows, onOpenWorkspace = { opened += it }) } }
        settle()
        compose.onNodeWithTag("workspace-list").assertIsDisplayed()
        tap("workspace-list-row-beta")
        tap("workspace-list-row-alpha")
        assertEquals(listOf("beta", "alpha"), opened)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        listOf(R.string.workspace_pause, R.string.workspace_resume, R.string.workspace_correct).forEach {
            compose.onNodeWithText(compose.activity.getString(it)).assertDoesNotExist()
        }
        compose.onNodeWithTag("drawer-open-workspaces").assertDoesNotExist()
    }

    @Test fun loadingErrorsAndDisabledActionsBlockStaleRowNavigation() {
        val loading = mutableStateOf(true)
        val error = mutableStateOf<String?>(null)
        val enabled = mutableStateOf(true)
        var opened = 0
        var retried = 0
        compose.setContent { MaterialTheme {
            WorkspaceListScreen(rows, workspacesLoading = loading.value, workspaceError = error.value,
                actionsEnabled = enabled.value, onOpenWorkspace = { opened++ }, onRetryWorkspaces = { retried++ })
        } }
        settle()
        compose.onNodeWithTag("workspace-list-loading").assertIsDisplayed()
        compose.onNodeWithTag("workspace-list-empty").assertDoesNotExist()
        compose.onNodeWithTag("workspace-list-row-alpha").assertIsNotEnabled()
        tap("workspace-list-row-alpha")
        assertEquals(0, opened)
        compose.runOnIdle { loading.value = false; error.value = "Recorded catalog error" }
        settle()
        compose.onNodeWithTag("workspace-list-error").assertIsDisplayed()
        compose.onNodeWithTag("workspace-list-empty").assertDoesNotExist()
        compose.onNodeWithTag("workspace-list-row-alpha").assertIsNotEnabled()
        tap("workspace-list-retry")
        assertEquals(1, retried)
        compose.runOnIdle { error.value = null; enabled.value = false }
        settle()
        compose.onNodeWithTag("workspace-list-row-alpha").assertIsNotEnabled()
        tap("workspace-list-row-alpha")
        assertEquals(0, opened)
        compose.runOnIdle { enabled.value = true }
        settle()
        compose.onNodeWithTag("workspace-list-row-alpha").assertIsEnabled()
        tap("workspace-list-row-alpha")
        assertEquals(1, opened)
    }

    @Test fun paginationReturnsOnlyNavigationAndLoadingBlocksEveryPageButton() {
        val loading = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        var previous = 0
        var first = 0
        var next = 0
        var opened = 0
        compose.setContent { MaterialTheme {
            WorkspaceListScreen(rows, workspacesLoading = loading.value, actionsEnabled = enabled.value,
                canPreviousWorkspacePage = true, canFirstWorkspacePage = true, canNextWorkspacePage = true,
                onOpenWorkspace = { opened++ }, onPreviousWorkspacePage = { previous++ },
                onNextWorkspacePage = { next++ }, onFirstWorkspacePage = { first++ })
        } }
        settle()
        listOf("workspace-previous-page", "workspace-first-page", "workspace-next-page").forEach(::tap)
        assertEquals(listOf(1, 1, 1, 0), listOf(previous, first, next, opened))
        compose.runOnIdle { loading.value = true }
        settle()
        listOf("workspace-previous-page", "workspace-first-page", "workspace-next-page").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
            tap(it)
        }
        assertEquals(listOf(1, 1, 1, 0), listOf(previous, first, next, opened))
        compose.runOnIdle { loading.value = false; enabled.value = false }
        settle()
        listOf("workspace-previous-page", "workspace-first-page", "workspace-next-page").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
    }

    @Test fun emptyFirstPageAndEmptyLaterPageHaveDifferentCopyAndRecovery() = emptyPageContract()

    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi")
    fun emptyFirstPageAndEmptyLaterPageHaveDifferentCopyAndRecoveryInSpanish() = emptyPageContract()

    private fun emptyPageContract() {
        val laterPage = mutableStateOf(false)
        var restored = 0
        compose.setContent { MaterialTheme {
            WorkspaceListScreen(emptyList(), canFirstWorkspacePage = laterPage.value, onOpenWorkspace = {},
                onFirstWorkspacePage = { restored++ })
        } }
        settle()
        compose.onNodeWithTag("workspace-list-empty")
            .assertTextEquals(compose.activity.getString(R.string.workspace_sidebar_empty))
        compose.onNodeWithTag("workspace-first-page").assertDoesNotExist()
        compose.runOnIdle { laterPage.value = true }
        settle()
        compose.onNodeWithTag("workspace-list-empty")
            .assertTextEquals(compose.activity.getString(R.string.workspace_page_empty))
        tap("workspace-first-page")
        assertEquals(1, restored)
    }

    private fun tap(tag: String) {
        settle()
        compose.onNodeWithTag(tag).assertIsDisplayed().performTouchInput { click() }
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(64)
        awaitReactionDrawIdle(compose)
    }
}
