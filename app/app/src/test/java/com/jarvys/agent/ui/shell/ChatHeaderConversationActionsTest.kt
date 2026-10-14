package com.jarvys.agent.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.AppRouteAction
import com.jarvys.agent.AppRouteMeta
import com.jarvys.agent.ConversationAction
import com.jarvys.agent.R
import com.jarvys.agent.ui.chat.awaitReactionDrawIdle
import com.jarvys.agent.ui.motion.LocalReducedMotion
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h900dp-port-mdpi")
class ChatHeaderConversationActionsTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    @Test fun headerMenuRenamesAndConfirmsDeletingItsConversation() {
        val calls = mutableListOf<Triple<String, ConversationAction, String?>>()
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                JarvysRouteTopBar(
                    route = AppRouteMeta("Conversation title", true, action = AppRouteAction.CHAT),
                    agentSnapshot = AgentRunUiSnapshot(),
                    onBack = {}, onOpenDrawer = {}, onOpenSettings = {}, onNewChat = {},
                    onAddServer = {}, onImportSkill = {}, chatWithoutMemory = false,
                    canCompact = false, compacting = false, canReflect = false,
                    onToggleMemory = {}, onCompact = {}, onReflect = {},
                    mascotVisibleSessionId = "conversation-id",
                    conversationActionTitle = "Conversation title",
                    canRenameConversation = true,
                    canDeleteConversation = true,
                    onConversationAction = { session, action, title -> calls += Triple(session, action, title) },
                )
            }
        } }

        compose.onNodeWithTag("jarvys-topbar-more-options").performClick()
        awaitReactionDrawIdle(compose)
        compose.onNodeWithTag("topbar-rename-chat").assertIsDisplayed().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.drawer_rename_chat)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.drawer_save_name)).performClick()
        compose.runOnIdle {
            assertEquals(listOf(Triple("conversation-id", ConversationAction.RENAME, "Conversation title")), calls)
        }

        compose.onNodeWithTag("jarvys-topbar-more-options").performClick()
        awaitReactionDrawIdle(compose)
        compose.onNodeWithTag("topbar-delete-chat").assertIsDisplayed().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.drawer_delete_chat)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.drawer_delete_action)).performClick()
        compose.runOnIdle {
            assertEquals(ConversationAction.DELETE, calls.last().second)
            assertEquals("conversation-id", calls.last().first)
        }
    }
}
