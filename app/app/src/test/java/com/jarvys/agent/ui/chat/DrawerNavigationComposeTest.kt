package com.jarvys.agent.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jarvys.agent.*
import com.jarvys.agent.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class DrawerNavigationComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun drawerIsOnlyTheProfileHeaderAndSettingsWithIndependentCallbacks() {
        val calls = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            ConversationDrawer(agentName = "Luna", onOpenProfile = { calls += "profile" },
                onOpenSettings = { calls += "settings" })
        } }
        compose.onNodeWithTag("drawer-profile-name", useUnmergedTree = true).assertTextEquals("Luna")
        compose.onNodeWithText(compose.activity.getString(R.string.drawer_profile_memory)).assertIsDisplayed()
        listOf("drawer-profile-header", "drawer-open-settings").forEach {
            compose.onNodeWithTag(it).performTouchInput { click() }
        }
        assertEquals(listOf("profile", "settings"), calls)
        listOf("drawer-new-chat", "drawer-open-bots", "drawer-open-scheduled-tasks", "drawer-open-central",
            "drawer-open-workspaces", "drawer-search-toggle", "drawer-archive-toggle", "conversation-drawer-scroll",
            "drawer-header").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithText(compose.activity.getString(R.string.drawer_archived_chats)).assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.drawer_history)).assertDoesNotExist()
    }

    @Test fun disabledActionsDoNotNavigate() {
        var calls = 0
        compose.setContent { MaterialTheme {
            ConversationDrawer(agentName = "Luna", onOpenProfile = { calls++ }, onOpenSettings = { calls++ },
                actionsEnabled = false)
        } }
        listOf("drawer-profile-header", "drawer-open-settings").forEach {
            compose.onNodeWithTag(it).performTouchInput { click() }
        }
        assertEquals(0, calls)
    }

    @Test fun backClosesAnOpenDrawerAndAClosedDrawerDoesNotConsumeBack() {
        val open = mutableStateOf(true)
        var closeCalls = 0
        var backCalls = 0
        compose.activity.onBackPressedDispatcher.addCallback(compose.activity,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { backCalls++ }
            })
        compose.setContent { MaterialTheme {
            ConversationDrawer(agentName = "Luna", onOpenProfile = {}, onOpenSettings = {},
                isDrawerOpen = open.value, onCloseDrawer = { closeCalls++ })
        } }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, closeCalls)
        assertEquals(0, backCalls)
        compose.runOnIdle { open.value = false }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, closeCalls)
        assertEquals(1, backCalls)
    }

    @Test fun archiveRouteIsGoneAndTaskRoutesKeepLocalizedBackMetadata() {
        assertFalse(AppNavigationBackPolicy.registeredRoutePatterns.contains("settings/archived-chats"))
        val meta = AppNavigationBackPolicy.metadata(compose.activity, AppNavigationBackPolicy.TASKS, "Chat title", null, null)
        assertEquals(compose.activity.getString(R.string.tasks_title), meta.title)
        assertFalse(meta.isRoot)
        assertEquals(AppRouteAction.NONE, meta.action)
        assertFalse(AppNavigationBackPolicy.registeredRoutePatterns.contains("scheduled-tasks"))
    }
}
