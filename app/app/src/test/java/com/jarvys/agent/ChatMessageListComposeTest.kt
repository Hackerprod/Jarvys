package com.jarvys.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatMessageListComposeTest {
    @get:Rule val compose = createComposeRule()

    private data class Message(val id: Int, val height: Int, val user: Boolean = false)

    @Test fun openingThirtyMessagesWithOversizedLatestAnchorsItsBottomAndHidesJumpButton() {
        val messages = mutableStateOf((0 until 30).map { Message(it, if (it == 29) 900 else 72) })
        val state = LazyListState()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("root")) {
                    ChatMessageList("conversation-a", messages.value, false, { it.id }, { it.user }, listState = state,
                        modifier = Modifier.fillMaxSize(), itemContent = { message -> messageBox(message) })
                }
            }
        }
        compose.waitForIdle()
        val last = compose.onNodeWithTag("message-29").fetchSemanticsNode().boundsInRoot
        val viewportBottom = compose.onNodeWithTag("root").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("oversized latest message is visible", last.height > 0f)
        assertTrue("last message bottom ${last.bottom} should meet viewport $viewportBottom", last.bottom >= viewportBottom - 40f)
        assertFalse(state.canScrollBackward)
        assertTrue(compose.onAllNodesWithTag("chat-jump-to-end").fetchSemanticsNodes().isEmpty())
    }

    @Test fun lateMarkdownGrowthKeepsLatestMessagesBottomAnchored() {
        val messages = mutableStateOf((0 until 12).map { Message(it, if (it == 11) 100 else 72) })
        val state = LazyListState()
        compose.setContent {
            MaterialTheme {
                ChatMessageList("conversation-b", messages.value, false, { it.id }, { it.user }, listState = state,
                    modifier = Modifier.fillMaxSize(), itemContent = { message -> messageBox(message) })
            }
        }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("message-11").fetchSemanticsNode().boundsInRoot.bottom
        messages.value = messages.value.map { if (it.id == 11) it.copy(height = 1000) else it }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("message-11").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("tail bottom moved from $before to $after", kotlin.math.abs(before - after) <= 2f)
        assertFalse(state.canScrollBackward)
    }

    @Test fun historySwipeKeepsStableVisibleAnchorWhenNewMessageArrivesAndJumpReturnsToTail() {
        val messages = mutableStateOf((0 until 40).map { Message(it, 90) })
        val state = LazyListState()
        compose.setContent {
            MaterialTheme {
                ChatMessageList("conversation-c", messages.value, false, { it.id }, { it.user }, listState = state,
                    modifier = Modifier.fillMaxSize(), itemContent = { message -> messageBox(message) })
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat-message-list").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertTrue("back=${state.canScrollBackward} forward=${state.canScrollForward} index=${state.firstVisibleItemIndex}", state.canScrollBackward)
        val anchor = state.layoutInfo.visibleItemsInfo.first { it.index in 3..30 }
        val oldOffset = anchor.offset
        val oldKey = anchor.key
        compose.onNodeWithTag("chat-jump-to-end").assertIsDisplayed()
        messages.value = messages.value + Message(40, 90)
        compose.waitForIdle()
        val stillVisible = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == oldKey }
        assertTrue("stable key $oldKey remains visible", stillVisible != null)
        assertEquals(oldOffset, stillVisible!!.offset)
        compose.onNodeWithTag("chat-jump-to-end").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(0, state.firstVisibleItemIndex)
        assertFalse(state.canScrollBackward)
    }

    @Test fun sendingOwnMessageScrollsBackToNewestIndexZero() {
        val messages = mutableStateOf((0 until 35).map { Message(it, 84) })
        val state = LazyListState(firstVisibleItemIndex = 12)
        compose.setContent {
            MaterialTheme {
                ChatMessageList("conversation-d", messages.value, true, { it.id }, { it.user }, listState = state,
                    modifier = Modifier.fillMaxSize(), itemContent = { message -> messageBox(message) })
            }
        }
        compose.waitForIdle()
        compose.waitForIdle()
        messages.value = messages.value + Message(35, 84, user = true)
        compose.waitForIdle()
        assertEquals(0, state.firstVisibleItemIndex)
        assertFalse(state.canScrollBackward)
    }

    @Test fun deepLinkTargetScrollsToTheRequestedProactiveMessage() {
        val messages = (0 until 35).map { Message(it, 84) }
        val state = LazyListState()
        compose.setContent {
            MaterialTheme {
                ChatMessageList("proactive-thread", messages, false, { it.id }, { it.user }, listState = state,
                    targetMessage = { it.id == 7 }, modifier = Modifier.fillMaxSize(),
                    itemContent = { message -> messageBox(message) })
            }
        }
        compose.waitForIdle()
        assertEquals(messages.lastIndex - 7, state.firstVisibleItemIndex)
        compose.onNodeWithTag("message-7").assertIsDisplayed()
    }

    @Test fun fewMessagesWithReverseLayoutAndTopArrangementRemainAtViewportTop() {
        val messages = listOf(Message(0, 40), Message(1, 40), Message(2, 40))
        val state = LazyListState()
        compose.setContent {
            MaterialTheme {
                ChatMessageList("conversation-e", messages, false, { it.id }, { it.user }, listState = state,
                    modifier = Modifier.fillMaxSize(), itemContent = { message -> messageBox(message) })
            }
        }
        compose.waitForIdle()
        val firstInVisualOrder = compose.onNodeWithTag("message-0").fetchSemanticsNode().boundsInRoot
        assertTrue("oldest message should start near top, y=${firstInVisualOrder.top}", firstInVisualOrder.top < 40f)
        assertTrue(firstInVisualOrder.width > 0f)
        assertFalse(state.canScrollBackward)
    }

    @androidx.compose.runtime.Composable
    private fun messageBox(message: Message) {
        Box(Modifier.width(280.dp).height(message.height.dp).testTag("message-${message.id}"))
    }
}
