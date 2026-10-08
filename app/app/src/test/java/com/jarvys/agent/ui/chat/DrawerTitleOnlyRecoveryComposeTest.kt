package com.jarvys.agent.ui.chat

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ConversationAction
import com.jarvys.agent.R
import com.jarvys.agent.RunHistoryItem
import com.jarvys.agent.proactive.ProactiveConversation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrawerTitleOnlyRecoveryComposeTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val saved = RunHistoryItem("saved-record", "Original saved goal", "UNIQUE_RESULT", 7, 9, 1.0,
        "saved-session", "Saved chat")

    @Test fun activeAndHistoricalRowsShowTitlesWithoutStatusSubtitlesAndKeepSelectionAndNavigation() {
        var resumed = 0
        val opened = mutableListOf<String>()
        val selected = mutableStateOf<String?>(null)
        compose.setContent { MaterialTheme {
            ConversationDrawer(listOf(saved), "current-session", "Current chat", "Current goal", true,
                selected.value, true, {}, { resumed++ }, { opened += it }, {})
        } }
        compose.onNodeWithText("Current chat").assertIsDisplayed()
        compose.onNodeWithText("Saved chat").assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.drawer_current_conversation)).assertCountEquals(0)
        compose.onAllNodesWithText(context.getString(R.string.drawer_history_summary, saved.outcome, saved.steps, saved.turns))
            .assertCountEquals(0)
        compose.onAllNodesWithText(saved.outcome, substring = true).assertCountEquals(0)
        assertEquals(FontWeight.SemiBold, titleWeight("Current chat"))
        assertEquals(FontWeight.Medium, titleWeight("Saved chat"))
        compose.onNodeWithText("Current chat").performClick()
        compose.onNodeWithText("Saved chat").performClick()
        assertEquals(1, resumed)
        assertEquals(listOf(saved.id), opened)
        compose.runOnIdle { selected.value = saved.id }
        assertEquals(FontWeight.Medium, titleWeight("Current chat"))
        assertEquals(FontWeight.SemiBold, titleWeight("Saved chat"))
    }

    @Test fun pinUnpinArchiveAndRestoreStillDispatchForTheCorrectSession() {
        val record = mutableStateOf(saved)
        val actions = mutableListOf<Pair<String, ConversationAction>>()
        compose.setContent { MaterialTheme {
            ConversationDrawer(listOf(record.value), "other", null, "", false, saved.id, true, {}, {}, {}, {},
                onConversationAction = { session, action, _ ->
                    actions += session to action
                    record.value = when (action) {
                        ConversationAction.PIN -> record.value.copy(pinned = true)
                        ConversationAction.UNPIN -> record.value.copy(pinned = false)
                        ConversationAction.ARCHIVE -> record.value.copy(archived = true)
                        ConversationAction.RESTORE -> record.value.copy(archived = false)
                        else -> record.value
                    }
                })
        } }
        menuAction(R.string.drawer_pin)
        compose.onNodeWithText(context.getString(R.string.drawer_pinned)).assertIsDisplayed()
        menuAction(R.string.drawer_unpin)
        compose.onNodeWithText(context.getString(R.string.drawer_pinned)).assertDoesNotExist()
        menuAction(R.string.drawer_archive)
        compose.onNodeWithText("Saved chat").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.drawer_archived_chats)).performClick()
        compose.onNodeWithText("Saved chat").assertIsDisplayed()
        compose.onAllNodesWithText(saved.outcome, substring = true).assertCountEquals(0)
        menuAction(R.string.drawer_unarchive)
        compose.onNodeWithText("Saved chat").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.drawer_back_to_chats)).performClick()
        compose.onNodeWithText("Saved chat").assertIsDisplayed()
        assertEquals(listOf(ConversationAction.PIN, ConversationAction.UNPIN, ConversationAction.ARCHIVE,
            ConversationAction.RESTORE).map { saved.sessionId to it }, actions)
    }

    @Test fun renameStillValidatesAndDeleteStillRequiresConfirmation() {
        val actions = mutableListOf<Triple<String, ConversationAction, String?>>()
        compose.setContent { MaterialTheme {
            ConversationDrawer(listOf(saved), "other", null, "", false, null, true, {}, {}, {}, {},
                onConversationAction = { session, action, text -> actions += Triple(session, action, text) })
        } }
        menuAction(R.string.drawer_rename_chat)
        val input = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
        input.performTextReplacement(" ")
        compose.onNodeWithText(context.getString(R.string.drawer_save_name)).assertIsNotEnabled()
        input.performTextReplacement("  Renamed title  ")
        compose.onNodeWithText(context.getString(R.string.drawer_save_name)).performClick()
        assertEquals(listOf(Triple(saved.sessionId, ConversationAction.RENAME, "Renamed title")), actions)
        menuAction(R.string.drawer_delete_action)
        assertEquals("Opening confirmation cannot delete", 1, actions.size)
        compose.onNodeWithText(context.getString(R.string.drawer_delete_chat)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.drawer_delete_action)).performClick()
        assertEquals(Triple(saved.sessionId, ConversationAction.DELETE, null), actions.last())
    }

    @Test fun managedChatsStillHideDeleteAndDisabledActionsCannotOpenMenus() {
        val enabled = mutableStateOf(true)
        var mutations = 0
        compose.setContent { MaterialTheme {
            ConversationDrawer(listOf(saved.copy(sessionId = ProactiveConversation.SESSION_ID)),
                "other", null, "", false, null, true, {}, {}, {}, {}, actionsEnabled = enabled.value,
                onConversationAction = { _, _, _ -> mutations++ })
        } }
        openMenu()
        compose.onNodeWithText(context.getString(R.string.drawer_delete_action)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.drawer_rename_chat)).assertIsDisplayed()
        // Choosing a nonmutating rename and cancelling also dismisses the popup.
        compose.onNodeWithText(context.getString(R.string.drawer_rename_chat)).performClick()
        compose.onNodeWithText(context.getString(R.string.drawer_cancel_action)).performClick()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithContentDescription(context.getString(R.string.drawer_chat_actions, "Saved chat"))
            .assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText(context.getString(R.string.drawer_pin)).assertDoesNotExist()
        assertEquals(0, mutations)
    }

    private fun openMenu() = compose.onNodeWithContentDescription(context.getString(R.string.drawer_chat_actions, "Saved chat"))
        .performClick()

    private fun menuAction(resource: Int) {
        openMenu()
        compose.onNodeWithText(context.getString(resource)).performClick()
    }

    private fun titleWeight(title: String): FontWeight? {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single().layoutInput.style.fontWeight
    }
}
