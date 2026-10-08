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
        fun openMenu() {
            showText("Saved chat")
            compose.onNodeWithContentDescription(context.getString(R.string.drawer_chat_actions, "Saved chat")).performClick()
        }
        openMenu()
        compose.onNodeWithText(context.getString(R.string.drawer_rename_chat)).performClick()
        compose.onNodeWithText(context.getString(R.string.drawer_cancel_action)).performClick()
        assertTrue(actions.isEmpty())
        openMenu()
        compose.onNodeWithText(context.getString(R.string.drawer_delete_action)).performClick()
        compose.onNodeWithText(context.getString(R.string.drawer_delete_chat)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.drawer_cancel_action)).performClick()
        assertTrue(actions.isEmpty())
        showText("Saved chat").assertIsDisplayed()
    }

    @Test fun showingArchivesInTheirOwnDestinationDoesNotOpenOrMutateChats() {
        var opens = 0
        var mutations = 0
        val normal = RunHistoryItem("r", "goal", "CHAT", 0, 1, 1.0, "s", "Normal")
        val archived = normal.copy(id = "a", sessionId = "a", title = "Archived", archived = true)
        val archiveView = mutableStateOf(false)
        compose.setContent { MaterialTheme {
            if (archiveView.value) ArchivedChatsScreen(listOf(normal, archived), true, { opens++ }, { _, _, _ -> mutations++ })
            else ConversationDrawer(listOf(normal, archived), "other", null, "", false, null, true, {}, {}, { opens++ }, {},
                onConversationAction = { _, _, _ -> mutations++ })
        } }
        showText("Normal").assertIsDisplayed()
        compose.onNodeWithText("Archived").assertDoesNotExist()
        compose.runOnIdle { archiveView.value = true }
        compose.onNodeWithText("Archived").assertIsDisplayed()
        compose.onNodeWithText("Normal").assertDoesNotExist()
        compose.runOnIdle { archiveView.value = false }
        showText("Normal").assertIsDisplayed()
        assertEquals(0, opens)
        assertEquals(0, mutations)
    }

    private fun showText(text: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("conversation-drawer-scroll").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }

}
