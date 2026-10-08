package com.jarvys.agent.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
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
    private val first = RunHistoryItem("record-first", "First original goal", "CHAT", 0, 0, 1.0,
        "session-first", "First title")
    private val second = first.copy(id = "record-second", sessionId = "session-second", title = "Second title")

    @Test fun pinnedSectionAppearsOnlyForVisiblePinnedRowsWithoutDuplicatingRecentRows() {
        val history = mutableStateOf(listOf(first, second))
        compose.setContent { MaterialTheme { drawer(history.value) } }
        compose.onNodeWithTag("drawer-pinned-heading").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.drawer_no_pinned_chats)).assertDoesNotExist()
        compose.runOnIdle { history.value = listOf(first.copy(pinned = true), second) }
        compose.onNodeWithTag("drawer-pinned-heading").assertIsDisplayed()
        compose.onAllNodesWithText("First title").assertCountEquals(1)
        compose.onNodeWithTag("drawer-search-toggle").performClick()
        compose.onNodeWithTag("drawer-search").performTextInput("Second")
        compose.onNodeWithTag("drawer-pinned-heading").assertDoesNotExist()
        compose.onNodeWithText("Second title").assertIsDisplayed()
    }

    @Test fun searchFiltersTitleAndOriginalGoalButNeverIncludesArchivedChats() {
        compose.setContent { MaterialTheme { drawer(listOf(first, second.copy(archived = true))) } }
        compose.onNodeWithTag("drawer-search-toggle").performTouchInput { click() }
        compose.onNodeWithTag("drawer-search").assertIsFocused().performTextInput("oRiGiNaL")
        compose.onNodeWithText("First title").assertIsDisplayed()
        compose.onNodeWithText("Second title").assertDoesNotExist()
        compose.onNodeWithTag("drawer-search").performTextReplacement("missing")
        compose.onNodeWithText(compose.activity.getString(R.string.drawer_search_no_results)).assertIsDisplayed()
    }

    @Test fun clearAndCancelHaveDifferentEffectsAndBothRestoreChats() {
        compose.setContent { MaterialTheme { drawer(listOf(first)) } }
        compose.onNodeWithTag("drawer-search-toggle").performClick()
        compose.onNodeWithTag("drawer-search").performTextInput("missing")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.drawer_clear_search)).performTouchInput { click() }
        compose.onNodeWithTag("drawer-search").assertTextEquals("")
        compose.onNodeWithText("First title").assertIsDisplayed()
        compose.onNodeWithTag("drawer-search").performTextInput("missing")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.drawer_cancel_search)).performTouchInput { click() }
        compose.onNodeWithTag("drawer-search").assertDoesNotExist()
        compose.onNodeWithText("First title").assertIsDisplayed()
    }

    @Test fun backCancelsSearchBeforeLeavingAndClosedDrawerDoesNotConsumeBack() {
        val open = mutableStateOf(true)
        var backCalls = 0
        compose.activity.onBackPressedDispatcher.addCallback(compose.activity,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { backCalls++ }
            })
        compose.setContent { MaterialTheme { drawer(listOf(first), open.value) } }
        compose.onNodeWithTag("drawer-search-toggle").performClick()
        compose.onNodeWithTag("drawer-search").performTextInput("missing")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(0, backCalls)
        compose.onNodeWithTag("drawer-search").assertDoesNotExist()
        compose.onNodeWithText("First title").assertIsDisplayed()
        compose.onNodeWithTag("drawer-search-toggle").performClick()
        compose.runOnIdle { open.value = false }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, backCalls)
    }

    @Test fun searchSurvivesDrawerCloseReopenAndSavedStateRestoration() {
        val open = mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { drawer(listOf(first, second), open.value) } }
        compose.onNodeWithTag("drawer-search-toggle").performClick()
        compose.onNodeWithTag("drawer-search").performTextInput("Second")
        compose.runOnIdle { open.value = false }
        compose.onNodeWithTag("drawer-search").assertIsNotFocused()
        compose.runOnIdle { open.value = true }
        compose.onNodeWithTag("drawer-search").assertTextEquals("Second").assertIsFocused()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("drawer-search").assertTextEquals("Second")
        compose.onNodeWithText("First title").assertDoesNotExist()
        compose.onNodeWithText("Second title").assertIsDisplayed()
    }

    @Test fun menuCallbacksAreIndependentAndNoArchiveOrReloadControlsRemain() {
        val calls = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            ConversationDrawer(emptyList(), "new", null, "", false, null, true,
                { calls += "new" }, {}, {}, { calls += "settings" }, onOpenBots = { calls += "bots" },
                onOpenScheduledTasks = { calls += "scheduled" })
        } }
        listOf("drawer-new-chat", "drawer-open-bots", "drawer-open-scheduled-tasks", "drawer-open-settings").forEach {
            compose.onNodeWithTag(it).performTouchInput { click() }
        }
        assertEquals(listOf("new", "bots", "scheduled", "settings"), calls)
        compose.onNodeWithTag("drawer-archive-toggle").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.drawer_archived_chats)).assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.drawer_history)).assertDoesNotExist()
    }

    @Test fun placeholderIsHonestAndHasNoScheduleCreationControls() {
        compose.setContent { MaterialTheme { ScheduledTasksPlaceholderScreen() } }
        compose.onNodeWithTag("scheduled-tasks-placeholder").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.scheduled_tasks_placeholder_title)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.scheduled_tasks_placeholder_body)).assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun archiveAndPlaceholderRoutesHaveLocalizedBackMetadata() {
        listOf(AppNavigationBackPolicy.ARCHIVED_CHATS to R.string.drawer_archived_chats,
            AppNavigationBackPolicy.SCHEDULED_TASKS to R.string.drawer_scheduled_tasks).forEach { (route, title) ->
            val meta = AppNavigationBackPolicy.metadata(compose.activity, route, "Chat title", null, null)
            assertEquals(compose.activity.getString(title), meta.title)
            assertFalse(meta.isRoot)
            assertEquals(AppRouteAction.NONE, meta.action)
        }
        assertNotEquals(AppNavigationBackPolicy.TASKS, AppNavigationBackPolicy.SCHEDULED_TASKS)
    }

    @androidx.compose.runtime.Composable
    private fun drawer(history: List<RunHistoryItem>, open: Boolean = true) {
        ConversationDrawer(history, "new", null, "", false, null, true, {}, {}, {}, {}, isDrawerOpen = open)
    }
}
