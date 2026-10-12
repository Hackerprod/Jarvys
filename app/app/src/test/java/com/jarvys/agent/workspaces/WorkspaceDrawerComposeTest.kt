package com.jarvys.agent.workspaces

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jarvys.agent.R
import com.jarvys.agent.RunHistoryItem
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
class WorkspaceDrawerComposeTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    private val legacy = RunHistoryItem("record-old", "Original independent goal", "CHAT", 0, 0, 1.0,
        "legacy-session", "Independent conversation")
    private val workspace = WorkspaceSidebarRow("workspace", "Research workspace", WorkspaceUiStatus.WAITING)

    @Test fun workspaceShelfPrecedesIndependentHistoryAndNeverExposesExecutionChats() {
        val calls = mutableListOf<String>()
        val execution = WorkspaceIdentity.executionSessionId("11111111-1111-1111-1111-111111111111")
        compose.setContent { MaterialTheme {
            ConversationDrawer(listOf(legacy,
                legacy.copy(id = "central-record", sessionId = WorkspaceIdentity.CENTRAL_SESSION, title = "Hidden central history", pinned = true),
                legacy.copy(id = "execution-record", sessionId = execution, title = "Hidden execution history")),
                WorkspaceIdentity.CENTRAL_SESSION, "Hidden active central", "Central goal", true, null, true,
                { calls += "new" }, { calls += "resume-active" }, { calls += it }, { calls += "settings" },
                onOpenBots = { calls += "bots" }, onOpenScheduledTasks = { calls += "scheduled" },
                centralSelected = true, onOpenCentral = { calls += "central" }, workspaces = listOf(workspace),
                onOpenWorkspace = { calls += it })
        } }
        compose.onNodeWithTag("drawer-open-central").assertIsSelected().performClick()
        compose.onNodeWithTag("drawer-workspace-workspace").assertIsNotSelected().performClick()
        compose.onNodeWithText("Hidden central history").assertDoesNotExist()
        compose.onNodeWithText("Hidden execution history").assertDoesNotExist()
        compose.onNodeWithText("Hidden active central").assertDoesNotExist()
        compose.onNodeWithTag("drawer-pinned-heading").assertDoesNotExist()
        val workspaceBottom = compose.onNodeWithTag("drawer-workspace-workspace").fetchSemanticsNode().boundsInRoot.bottom
        val historyTop = compose.onNodeWithText("Independent conversation").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Workspaces precede legacy history", workspaceBottom < historyTop)
        compose.onNodeWithText("Independent conversation").performClick()
        listOf("drawer-new-chat", "drawer-open-bots", "drawer-open-scheduled-tasks").forEach {
            compose.onNodeWithTag(it).performScrollTo().performClick()
        }
        compose.onNodeWithTag("drawer-open-settings").performClick()
        assertEquals(listOf("central", "workspace", "record-old", "new", "bots", "scheduled", "settings"), calls)
    }

    @Test fun ordinaryHistoryFiltersReservedSessionsEvenWithDefaultOptionalProps() {
        compose.setContent { MaterialTheme {
            ConversationDrawer(listOf(legacy.copy(id = "central", sessionId = WorkspaceIdentity.CENTRAL_SESSION,
                title = "Hidden central"), legacy), WorkspaceIdentity.CENTRAL_SESSION, "Hidden active", "", true,
                null, true, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("drawer-open-central").assertDoesNotExist()
        compose.onNodeWithText("Hidden central").assertDoesNotExist()
        compose.onNodeWithText("Hidden active").assertDoesNotExist()
        compose.onNodeWithText("Independent conversation").assertIsDisplayed()
    }

    @Test fun searchRestorationAndBackStillBelongToTheDrawerWithWorkspaceNavigation() {
        val open = mutableStateOf(true)
        var closed = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme {
            ConversationDrawer(listOf(legacy), "new", null, "", false, null, true, {}, {}, {}, {},
                isDrawerOpen = open.value, onCloseDrawer = { closed++ }, onOpenCentral = {}, workspaces = listOf(workspace))
        } }
        compose.onNodeWithTag("drawer-search-toggle").performClick()
        compose.onNodeWithTag("drawer-search").performTextInput("missing")
        compose.onNodeWithTag("drawer-search-empty").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("drawer-workspace-workspace").assertExists()
        compose.runOnIdle { open.value = false }
        compose.onNodeWithTag("drawer-search").assertIsNotFocused()
        compose.runOnIdle { open.value = true }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("drawer-search").assertTextEquals("missing")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("drawer-search").assertDoesNotExist()
        assertEquals(0, closed)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, closed)
    }

    @Test fun noWorkspaceRecordsDoNotInventAWorkspaceOrCommands() {
        compose.setContent { MaterialTheme {
            ConversationDrawer(emptyList(), "new", null, "", false, null, true, {}, {}, {}, {}, onOpenCentral = {})
        } }
        compose.onNodeWithTag("drawer-workspaces-empty").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_pause)).assertDoesNotExist()
        compose.onNodeWithTag("drawer-new-chat").assertIsDisplayed()
    }
}
