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
import org.junit.Assert.assertTrue
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

    @Test fun headerMenuOffersRenameAndConfirmsDeletingItsConversation() {
        // Keep Compose animations under test-clock control while the rename text field is mounted.
        compose.mainClock.autoAdvance = false
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
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("topbar-rename-chat").assertIsDisplayed()
        compose.onNodeWithTag("topbar-rename-chat-icon", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.drawer_settings)).assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.drawer_new_chat)).assertDoesNotExist()
        val firstActionBottom = compose.onNodeWithTag("topbar-rename-chat").fetchSemanticsNode().boundsInRoot.bottom
        val memoryAction = compose.onNodeWithTag("topbar-memory-action").fetchSemanticsNode().boundsInRoot
        val compactAction = compose.onNodeWithTag("topbar-compaction-action").fetchSemanticsNode().boundsInRoot
        val reflectAction = compose.onNodeWithTag("topbar-reflection-action").fetchSemanticsNode().boundsInRoot
        val deleteAction = compose.onNodeWithTag("topbar-delete-chat").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("topbar-memory-action-icon", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("topbar-compaction-action-icon", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("topbar-reflection-action-icon", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("topbar-delete-chat-icon", useUnmergedTree = true).assertExists()
        assertTrue("Rename is the first chat menu item", firstActionBottom <= memoryAction.top)
        assertTrue("Delete is after every other chat menu item", reflectAction.bottom <= deleteAction.top)
        assertTrue(memoryAction.bottom <= compactAction.top && compactAction.bottom <= reflectAction.top)

        compose.onNodeWithTag("topbar-delete-chat").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText(compose.activity.getString(R.string.drawer_delete_chat)).assertIsDisplayed()
        compose.onAllNodes(hasText(compose.activity.getString(R.string.drawer_delete_action)) and hasClickAction()).onFirst().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            assertEquals(ConversationAction.DELETE, calls.last().second)
            assertEquals("conversation-id", calls.last().first)
        }
    }

    @Test fun headerMenuShowsAndRunsOptionalWorkspaceAction() {
        var actionCount = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                JarvysRouteTopBar(
                    route = AppRouteMeta("Workspace title", true, action = AppRouteAction.CHAT),
                    agentSnapshot = AgentRunUiSnapshot(),
                    onBack = {}, onOpenDrawer = {}, onOpenSettings = {}, onNewChat = {},
                    onAddServer = {}, onImportSkill = {}, chatWithoutMemory = false,
                    canCompact = false, compacting = false, canReflect = false,
                    onToggleMemory = {}, onCompact = {}, onReflect = {},
                    canRenameConversation = false,
                    canDeleteConversation = false,
                    workspaceMenuActionTitle = "View files",
                    onWorkspaceMenuAction = { actionCount += 1 },
                )
            }
        } }

        compose.onNodeWithTag("jarvys-topbar-more-options").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("workspace/topbar-action").assertIsDisplayed()
            .assertTextEquals("View files")
        compose.onNodeWithTag("workspace/topbar-action-icon", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.drawer_settings)).assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.drawer_new_chat)).assertDoesNotExist()
        compose.onNodeWithTag("workspace/topbar-action").performClick()
        compose.runOnIdle { assertEquals(1, actionCount) }
    }
}
