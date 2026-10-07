package com.jarvys.agent.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.AppRouteAction
import com.jarvys.agent.AppRouteMeta
import com.jarvys.agent.ChatMessageList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JarvysShellFrameUx3Test {
    @get:Rule val compose = createComposeRule()

    private data class Message(val index: Int)

    @Test fun chatContentExtendsBehindMeasuredFloatingComposerAndTailAndJumpStayAboveIt() {
        var composerHeight by mutableStateOf(104.dp)
        val listState = LazyListState()
        var underComposerTaps = 0
        val messages = (0 until 36).map(::Message)

        compose.setContent {
            MaterialTheme {
                val drawer = rememberDrawerState(DrawerValue.Closed)
                JarvysShellFrame(
                    drawerState = drawer,
                    drawerContent = {},
                    route = AppRouteMeta("Chat", isRoot = true, action = AppRouteAction.CHAT),
                    agentSnapshot = AgentRunUiSnapshot(),
                    onBack = {}, onOpenDrawer = {}, onOpenSettings = {}, onNewChat = {},
                    onAddServer = {}, onImportSkill = {}, chatWithoutMemory = false,
                    canCompact = false, compacting = false, canReflect = false,
                    onToggleMemory = {}, onCompact = {}, onReflect = {},
                    bottomBar = {
                        Box(Modifier.fillMaxWidth().height(composerHeight).background(Color.Red)
                            .testTag("ux3-composer-surface"))
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).testTag("ux3-chat-content")) {
                        ChatMessageList("ux3-chat", messages, false, { it.index }, { false }, listState = listState,
                            modifier = Modifier.fillMaxSize(), itemContent = { message ->
                                Box(Modifier.fillMaxWidth().height(74.dp).background(Color.Blue)
                                    .clickable { underComposerTaps++ }
                                    .testTag("ux3-message-${message.index}"))
                            })
                        Box(Modifier.fillMaxSize().testTag("ux3-behind-composer-hit-area"))
                    }
                }
            }
        }
        compose.waitForIdle()
        assertContentAndComposerOverlap()
        assertTailAndJumpAboveComposer()

        compose.onNodeWithTag("chat-message-list").performTouchInput { swipeDown() }
        compose.waitForIdle()
        val jump = compose.onNodeWithTag("chat-jump-to-end").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("ux3-composer-surface").fetchSemanticsNode().boundsInRoot
        assertTrue("jump button remains above floating composer: jump=$jump composer=$composer", jump.bottom <= composer.top)
        compose.onNodeWithTag("ux3-composer-surface").performTouchInput { click() }
        assertEquals(0, underComposerTaps)
        compose.onNodeWithTag("chat-jump-to-end").performClick()
        compose.waitForIdle()

        val insetBeforeGrowth = compose.onNodeWithTag("ux3-composer-surface").fetchSemanticsNode().boundsInRoot.height
        composerHeight = 188.dp
        compose.waitForIdle()
        assertContentAndComposerOverlap()
        val insetAfterGrowth = compose.onNodeWithTag("ux3-composer-surface").fetchSemanticsNode().boundsInRoot.height
        assertTrue("measured composer inset grows with composer content", insetAfterGrowth > insetBeforeGrowth)
        assertTailAndJumpAboveComposer()
        compose.onNodeWithTag("chat-message-list").performTouchInput { swipeDown() }
        compose.waitForIdle()
        val jumpAfterGrowth = compose.onNodeWithTag("chat-jump-to-end").fetchSemanticsNode().boundsInRoot
        val composerAfterGrowth = compose.onNodeWithTag("ux3-composer-surface").fetchSemanticsNode().boundsInRoot
        assertTrue("jump button tracks updated inset", jumpAfterGrowth.bottom <= composerAfterGrowth.top)
        compose.onNodeWithTag("chat-jump-to-end").performClick()
        compose.waitForIdle()
        assertTailAndJumpAboveComposer()
    }

    @Test fun nonChatRoutesKeepScaffoldBottomBarLayout() {
        compose.setContent {
            MaterialTheme {
                JarvysShellFrame(
                    drawerState = rememberDrawerState(DrawerValue.Closed), drawerContent = {},
                    route = AppRouteMeta("Settings", isRoot = false, action = AppRouteAction.NONE),
                    agentSnapshot = AgentRunUiSnapshot(), onBack = {}, onOpenDrawer = {}, onOpenSettings = {},
                    onNewChat = {}, onAddServer = {}, onImportSkill = {}, chatWithoutMemory = false,
                    canCompact = false, compacting = false, canReflect = false,
                    onToggleMemory = {}, onCompact = {}, onReflect = {},
                    bottomBar = { Box(Modifier.fillMaxWidth().height(112.dp).testTag("other-route-bottom-bar")) },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).testTag("other-route-body"))
                }
            }
        }
        compose.waitForIdle()
        val content = compose.onNodeWithTag("other-route-body").fetchSemanticsNode().boundsInRoot
        val bottomBar = compose.onNodeWithTag("other-route-bottom-bar").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("non-chat scaffold still reserves the bottom bar: content=$content bottomBar=$bottomBar",
            content.bottom <= bottomBar.top)
    }

    private fun assertContentAndComposerOverlap() {
        val content = compose.onNodeWithTag("ux3-chat-content").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("ux3-composer-surface").fetchSemanticsNode().boundsInRoot
        assertTrue("chat content fills behind composer: content=$content composer=$composer", content.bottom > composer.top)
        assertTrue("composer remains in the visible viewport", composer.bottom >= content.bottom - 1f)
    }

    private fun assertTailAndJumpAboveComposer() {
        val newest = compose.onNodeWithTag("ux3-message-35").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("ux3-composer-surface").fetchSemanticsNode().boundsInRoot
        assertTrue("reverse-layout tail is visible above composer: newest=$newest composer=$composer",
            newest.bottom <= composer.top)
    }
}
