package com.jarvys.agent.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.jarvys.agent.AgentRunUiEvent
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
        val registry = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })

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
