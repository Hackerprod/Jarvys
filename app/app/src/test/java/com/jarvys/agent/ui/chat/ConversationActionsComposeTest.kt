package com.jarvys.agent.ui.chat

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ConversationAction
import com.jarvys.agent.R
import com.jarvys.agent.RunHistoryItem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationActionsComposeTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun cancellingRenameAndDeleteLeavesConversationUntouched() {
        val actions = mutableListOf<ConversationAction>()
        val row = RunHistoryItem("r", "goal", "CHAT", 0, 1, 1.0, "s", "Saved chat")
        compose.setContent { MaterialTheme {
            ConversationDrawer(listOf(row), "other", null, "", false, "r", true, {}, {}, {}, {},
                onConversationAction = { _, action, _ -> actions += action })
        } }
        fun openMenu() = compose.onNodeWithContentDescription(context.getString(R.string.drawer_chat_actions, "Saved chat")).performClick()
        openMenu()
        compose.onNodeWithText(context.getString(R.string.drawer_rename_chat)).performClick()
        compose.onNodeWithText(context.getString(R.string.drawer_cancel_action)).performClick()
        assertTrue(actions.isEmpty())
        openMenu()
        compose.onNodeWithText(context.getString(R.string.drawer_delete_action)).performClick()
        compose.onNodeWithText(context.getString(R.string.drawer_delete_chat)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.drawer_cancel_action)).performClick()
        assertTrue(actions.isEmpty())
        compose.onNodeWithText("Saved chat").assertIsDisplayed()
    }

    @Test fun switchingArchiveViewsDoesNotOpenOrMutateChats() {
        var opens = 0
        var mutations = 0
        val normal = RunHistoryItem("r", "goal", "CHAT", 0, 1, 1.0, "s", "Normal")
        val archived = normal.copy(id = "a", sessionId = "a", title = "Archived", archived = true)
        compose.setContent { MaterialTheme {
            ConversationDrawer(listOf(normal, archived), "other", null, "", false, null, true, {}, {}, { opens++ }, {},
                onConversationAction = { _, _, _ -> mutations++ })
        } }
        compose.onNodeWithText("Normal").assertIsDisplayed()
        compose.onNodeWithText("Archived").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.drawer_archived_chats)).performClick()
        compose.onNodeWithText("Archived").assertIsDisplayed()
        compose.onNodeWithText("Normal").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.drawer_back_to_chats)).performClick()
        compose.onNodeWithText("Normal").assertIsDisplayed()
        assertEquals(0, opens)
        assertEquals(0, mutations)
    }
}
