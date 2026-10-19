package com.jarvys.agent.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
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
class ToolGroupScrollStabilityTest {
    @get:Rule val compose = createComposeRule()

    private fun registry() = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
        override fun isConnected(id: String) = false
        override fun setConnected(id: String, connected: Boolean) = Unit
    }, { true })

    private fun completedTool(id: Long) = AgentRunUiEvent.toolEvent(
        id = id, stage = "tool_result", displayName = "Completed tool $id",
        detail = "Result details for completed tool $id", callId = "tool-call-$id", previewId = null,
        timestampMillis = id)

    @Test fun appendingToolEventsAndEndingTheRunKeepsTheOpenSheetAndScroll() {
        val session = "tool-sheet-born-live"
        val events = mutableStateOf(listOf(completedTool(2L)))
        val running = mutableStateOf(true)
        val connectors = registry()
        compose.setContent { MaterialTheme {
            ConversationTimeline(conversationKey = session, events = events.value, isRunning = running.value,
                connectorRegistry = connectors, onOpenPreview = {}, onOpenSkillFile = {},
                chatWithoutMemory = false,
                runSnapshot = AgentRunUiSnapshot(sessionId = session, events = events.value, running = running.value))
        } }

        // Insert a new, earlier tool event beside an already-seen event. Its key becomes the new
        // group's first key, so the live group is born with animateEntry=true.
        compose.runOnIdle { events.value = listOf(completedTool(1L), completedTool(2L)) }
        compose.waitForIdle()
        compose.onNodeWithTag("tool-group-1").performClick()
        compose.onNodeWithTag("tool-group-sheet-1").assertIsDisplayed()

        compose.runOnIdle { events.value = (1L..40L).map(::completedTool) }
        compose.waitForIdle()
        repeat(40) { compose.onNodeWithTag("tool-group-list-1").performTouchInput { swipeUp() } }
        compose.waitForIdle()
        compose.onNodeWithTag("conversation-activity-40").assertIsDisplayed()

        compose.runOnIdle { events.value = events.value + completedTool(41L) }
        compose.waitForIdle()
        compose.onNodeWithTag("conversation-activity-40").assertIsDisplayed()
        compose.runOnIdle { running.value = false }
        compose.waitForIdle()

        compose.onNodeWithTag("tool-group-sheet-1").assertIsDisplayed()
        compose.onNodeWithTag("tool-group-list-1").assertIsDisplayed()
        compose.onNodeWithTag("conversation-activity-40").assertIsDisplayed()
    }

    @Test fun togglingAnimateEntryBothWaysKeepsTheOpenSheetAndItsScrollPosition() {
        val groupEvents = mutableStateOf((1L..40L).map(::completedTool))
        val animateEntry = mutableStateOf(true)
        val listState = LazyListState()
        val connectors = registry()
        compose.setContent { MaterialTheme {
            TimelineArrival(animateEntry.value) {
                val group = ToolGrouping.project(groupEvents.value).single() as ConversationTimelineItem.ToolGroup
                ToolGroupLine(group, connectors, onOpenPreview = {}, onOpenSkillFile = {}, listState = listState)
            }
        } }

        compose.onNodeWithTag("tool-group-1").performClick()
        compose.onNodeWithTag("tool-group-sheet-1").assertIsDisplayed()
        repeat(40) {
            if (compose.runOnIdle { listState.canScrollForward }) {
                compose.onNodeWithTag("tool-group-list-1").performTouchInput { swipeUp() }
            }
        }
        compose.waitForIdle()
        val initialScroll = compose.runOnIdle { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }

        compose.runOnIdle {
            groupEvents.value = groupEvents.value + completedTool(41L)
            animateEntry.value = false
        }
        compose.waitForIdle()
        compose.onNodeWithTag("tool-group-sheet-1").assertIsDisplayed()
        assertEquals(initialScroll, compose.runOnIdle { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset })
        compose.runOnIdle { animateEntry.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("tool-group-sheet-1").assertIsDisplayed()
        compose.runOnIdle { animateEntry.value = false }
        compose.waitForIdle()

        compose.onNodeWithTag("tool-group-sheet-1").assertIsDisplayed()
        compose.onNodeWithTag("tool-group-list-1").assertIsDisplayed()
        assertEquals(initialScroll, compose.runOnIdle { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset })
    }

    @Test fun appendingLiveEventsKeepsAnOpenSheetAttachedToItsStableTimelineRow() {
        val session = "tool-sheet-live-events"
        val events = mutableStateOf(listOf(completedTool(1L), completedTool(2L)))
        val listState = LazyListState()
        val connectors = registry()
        compose.setContent { MaterialTheme {
            ConversationTimeline(conversationKey = session, events = events.value, isRunning = true,
                connectorRegistry = connectors, onOpenPreview = {}, onOpenSkillFile = {},
                chatWithoutMemory = false, benchmarkListState = listState,
                runSnapshot = AgentRunUiSnapshot(sessionId = session, events = events.value, running = true))
        } }

        compose.onNodeWithTag("tool-group-1").performClick()
        compose.onNodeWithTag("tool-group-sheet-1").assertIsDisplayed()
        compose.onNodeWithTag("chat-message-list").assertIsDisplayed()
        compose.waitForIdle()
        val initialBounds = compose.onNodeWithTag("tool-group-sheet-1").fetchSemanticsNode()
            .layoutInfo.coordinates.boundsInWindow()

        val samples = mutableListOf<Rect?>()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { events.value = events.value + completedTool(3L) }
            repeat(30) {
                compose.mainClock.advanceTimeByFrame()
                samples += compose.onAllNodesWithTag("tool-group-sheet-1").fetchSemanticsNodes()
                    .firstOrNull()?.layoutInfo?.coordinates?.boundsInWindow()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()

        compose.onNodeWithTag("tool-group-sheet-1").assertIsDisplayed()
        compose.onNodeWithTag("tool-group-list-1").assertIsDisplayed()
        assertTrue("the sheet must stay composed during the live update: $samples", samples.all { it != null })
        assertTrue("the sheet must keep its bottom position as live content grows: $samples",
            samples.all { it?.bottom == initialBounds.bottom })
    }

    @Test fun progressToLongResultWithANewLiveEventKeepsTheSheetBottomAnchored() {
        val session = "tool-sheet-changing-height"
        val first = AgentRunUiEvent.toolEvent(1L, "tool_result", "Completed tool", "Prior result",
            "prior-call", null, 1L)
        val progress = AgentRunUiEvent.toolEvent(2L, "tool_progress", "Updating tool", "Short progress detail",
            "changing-call", null, 2L)
        val events = mutableStateOf(listOf(first, progress))
        val listState = LazyListState()
        val connectors = registry()
        compose.setContent { MaterialTheme {
            ConversationTimeline(conversationKey = session, events = events.value, isRunning = true,
                connectorRegistry = connectors, onOpenPreview = {}, onOpenSkillFile = {},
                chatWithoutMemory = false, benchmarkListState = listState,
                runSnapshot = AgentRunUiSnapshot(sessionId = session, events = events.value, running = true))
        } }

        compose.onNodeWithTag("tool-group-1").performClick()
        compose.onNodeWithTag("tool-group-sheet-1").assertIsDisplayed()
        compose.onNodeWithTag("tool-output-toggle-changing-call").performClick()
        compose.waitForIdle()
        val initialBounds = compose.onNodeWithTag("tool-group-sheet-1").fetchSemanticsNode()
            .layoutInfo.coordinates.boundsInWindow()
        val longResult = AgentRunUiEvent.toolEvent(2L, "tool_result", "Updating tool",
            (1..90).joinToString(" ") { "result-detail-$it" }, "changing-call", null, 2L)
        val nextProgress = AgentRunUiEvent.toolEvent(3L, "tool_progress", "Next tool", "New live activity",
            "next-call", null, 3L)

        compose.runOnIdle { events.value = listOf(first, longResult, nextProgress) }
        compose.waitForIdle()
        val updatedBounds = compose.onNodeWithTag("tool-group-sheet-1").fetchSemanticsNode()
            .layoutInfo.coordinates.boundsInWindow()

        compose.onNodeWithTag("tool-group-sheet-1").assertIsDisplayed()
        compose.onNodeWithTag("tool-group-list-1").assertIsDisplayed()
        assertTrue("the expanded result should change the sheet's measured height", updatedBounds.height > initialBounds.height)
        assertEquals("content growth should remain anchored to the sheet's bottom edge",
            initialBounds.bottom, updatedBounds.bottom, 1f)
    }

    @Test
    fun toolGroupSheetAndListStayStillAfterUserScrollReachesTheEnd() {
        val events = (1L..40L).map { id ->
            AgentRunUiEvent.toolEvent(
                id = id,
                stage = "tool_result",
                displayName = "Completed tool $id",
                detail = "Result details for completed tool $id",
                callId = "tool-call-$id",
                previewId = null,
                timestampMillis = id,
            )
        }
        val group = ToolGrouping.project(events).single() as ConversationTimelineItem.ToolGroup
        val groupTag = "tool-group-${group.firstEventId}"
        val listTag = "tool-group-list-${group.firstEventId}"
        val sheetTag = "tool-group-sheet-${group.firstEventId}"
        val listState = LazyListState()
        val registry = registry()

        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ToolGroupLine(
                        group = group,
                        registry = registry,
                        onOpenPreview = {},
                        onOpenSkillFile = {},
                        listState = listState,
                    )
                }
            }
        }

        compose.onNodeWithTag(groupTag).performClick()
        compose.onNodeWithTag(sheetTag).assertIsDisplayed()
        compose.onNodeWithTag(listTag).assertIsDisplayed()
        compose.waitForIdle()
        val initialSheetBounds = compose.onNodeWithTag(sheetTag).fetchSemanticsNode().boundsInRoot

        // Scroll through the actual sheet list with touch gestures; attempts are bounded by the fixture size.
        repeat(group.events.size) {
            if (compose.runOnIdle { listState.canScrollForward }) {
                compose.onNodeWithTag(listTag).performTouchInput { swipeUp() }
            }
        }
        compose.waitForIdle()
        assertFalse("the sheet list should be at its last tool", compose.runOnIdle { listState.canScrollForward })

        data class PositionSample(val firstIndex: Int, val firstOffset: Int, val sheetBounds: Rect)
        val samples = mutableListOf<PositionSample>()
        compose.mainClock.autoAdvance = false
        repeat(30) {
            compose.mainClock.advanceTimeByFrame()
            val scroll = compose.runOnIdle {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }
            val sheetBounds = compose.onNodeWithTag(sheetTag).fetchSemanticsNode().boundsInRoot
            samples += PositionSample(scroll.first, scroll.second, sheetBounds)
        }
        compose.mainClock.autoAdvance = true

        assertEquals("list position or sheet bounds changed after the end scroll settled", samples.first(), samples.last())
        assertTrue("tool sheet moved after reaching the end: $samples", samples.all { it == samples.first() })
        assertEquals("tool sheet shifted while the inner list scrolled", initialSheetBounds, samples.first().sheetBounds)
    }
}
