package com.jarvys.agent.ui.chat

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jarvys.agent.ArtifactOsShadow
import com.jarvys.agent.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual preserved drawer component, not a replacement mock or physical-device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class,
    shadows = [ArtifactOsShadow::class], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class PreservedCentralDrawerComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val entries = linkedMapOf(
        "drawer-open-central" to "central",
        "drawer-open-workspaces" to "workspaces",
        "drawer-open-bots" to "bots",
        "drawer-open-scheduled-tasks" to "tasks",
        "drawer-profile-header" to "profile",
        "drawer-open-settings" to "settings",
    )

    @Test fun allSixPreservedEntriesDisplayAndDispatchOnlyTheirExistingCallback() {
        val calls = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            ConversationDrawer(agentName = "Fixture generalist", onOpenProfile = { calls += "profile" },
                onOpenSettings = { calls += "settings" }, onOpenCentral = { calls += "central" },
                onOpenWorkspaces = { calls += "workspaces" }, onOpenBots = { calls += "bots" },
                onOpenScheduledTasks = { calls += "tasks" }, centralSelected = true)
        } }
        compose.onNodeWithTag("drawer-header").assertIsDisplayed()
        compose.onNodeWithTag("conversation-drawer-scroll").assertIsDisplayed()
        compose.onNodeWithTag("drawer-profile-name", useUnmergedTree = true).assertTextEquals("Fixture generalist")
        listOf(R.string.drawer_agent, R.string.workspace_sidebar_heading, R.string.drawer_bots,
            R.string.drawer_scheduled_tasks, R.string.drawer_local_profile).forEach { id ->
            compose.onNodeWithText(compose.activity.getString(id)).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.drawer_settings)).assertIsDisplayed()
        entries.entries.forEachIndexed { index, (tag, expected) ->
            compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
            compose.runOnIdle { assertEquals(entries.values.take(index + 1), calls.toList()); assertEquals(expected, calls.last()) }
        }
        // There is no seventh action that could create/select an additional conversation.
        compose.onAllNodes(hasClickAction()).assertCountEquals(6)
        assertNoRetiredConversationControls()
    }

    @Test fun pendingNavigationDisablesEveryPreservedActionWithoutChangingTheMenu() {
        var calls = 0
        compose.setContent { MaterialTheme {
            ConversationDrawer(agentName = "Fixture generalist", onOpenProfile = { calls++ },
                onOpenSettings = { calls++ }, onOpenCentral = { calls++ }, onOpenWorkspaces = { calls++ },
                onOpenBots = { calls++ }, onOpenScheduledTasks = { calls++ }, actionsEnabled = false)
        } }
        entries.keys.forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
        }
        compose.runOnIdle { assertEquals(0, calls) }
        assertNoRetiredConversationControls()
    }

    @Test fun nativeSelectionChangesBetweenCentralAndWorkspacesWithoutCreatingNewEntries() {
        val central = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            ConversationDrawer(agentName = "Fixture generalist", onOpenProfile = {}, onOpenSettings = {},
                centralSelected = central.value, workspacesSelected = !central.value)
        } }
        compose.onNodeWithTag("drawer-open-central").assertIsSelected()
        compose.onNodeWithTag("drawer-open-workspaces").assertIsNotSelected()
        compose.runOnIdle { central.value = false }
        compose.onNodeWithTag("drawer-open-central").assertIsNotSelected()
        compose.onNodeWithTag("drawer-open-workspaces").assertIsSelected()
        compose.onAllNodes(hasClickAction()).assertCountEquals(6)
        assertNoRetiredConversationControls()
    }

    @Test fun backClosesOnlyTheOpenDrawerWithoutInvokingAnyMenuAction() {
        val open = mutableStateOf(true)
        var closeCalls = 0
        var underlyingBackCalls = 0
        var menuCalls = 0
        compose.activity.onBackPressedDispatcher.addCallback(compose.activity,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { underlyingBackCalls++ }
            })
        compose.setContent { MaterialTheme {
            ConversationDrawer(agentName = "Fixture generalist", onOpenProfile = { menuCalls++ },
                onOpenSettings = { menuCalls++ }, onOpenCentral = { menuCalls++ },
                onOpenWorkspaces = { menuCalls++ }, onOpenBots = { menuCalls++ },
                onOpenScheduledTasks = { menuCalls++ }, isDrawerOpen = open.value,
                onCloseDrawer = { closeCalls++ })
        } }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, closeCalls); assertEquals(0, underlyingBackCalls); assertEquals(0, menuCalls)
        compose.runOnIdle { open.value = false }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, closeCalls); assertEquals(1, underlyingBackCalls); assertEquals(0, menuCalls)
    }

    private fun assertNoRetiredConversationControls() {
        listOf("drawer-new-chat", "drawer-search-toggle", "drawer-archive-toggle").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.drawer_archived_chats)).assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.drawer_history)).assertDoesNotExist()
    }
}
