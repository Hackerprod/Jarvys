package com.jarvys.agent.ui.chat

import com.jarvys.agent.AgentRunUiEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolGroupingTest {
    private fun tool(id: Long, stage: String = "tool_result", name: String = "tool-$id") =
        AgentRunUiEvent.toolEvent(id, "tool_result", name, null, "call-$id", null, id).copy(stage = stage)

    private fun item(event: AgentRunUiEvent) = ConversationTimelineItem.Event(event)

    @Test fun adjacentToolsBecomeOneGroupAndKeepTheirOrderAndFirstIdKey() {
        val events = listOf(tool(10), tool(11), tool(12))

        val projected = ToolGrouping.project(events)

        val group = projected.single() as ConversationTimelineItem.ToolGroup
        assertEquals(10L, group.key)
        assertEquals(10L, group.firstEventId)
        assertEquals(events, group.events)
        assertEquals(3, group.toolCount)
    }

    @Test fun singleToolRemainsAnIndividualEvent() {
        val event = tool(4)

        assertEquals(listOf(item(event)), ToolGrouping.project(listOf(event)))
    }

    @Test fun assistantUserAndApprovalEventsBreakToolRuns() {
        val breakers = listOf(
            AgentRunUiEvent(2, "assistant", text = "answer"),
            AgentRunUiEvent(2, "user", text = "message"),
            AgentRunUiEvent(2, "progress", text = "progress"),
            AgentRunUiEvent(2, "approval", text = "approval"),
        )

        breakers.forEach { breaker ->
            val before = tool(1)
            val after = tool(3)
            assertEquals(
                listOf(item(before), item(breaker), item(after)),
                ToolGrouping.project(listOf(before, breaker, after)),
            )
        }
    }

    @Test fun anActiveGroupUsesTheMostRecentActiveToolAndCountsTheOtherRows() {
        val events = listOf(
            tool(1, "tool_result", "finished first"),
            tool(2, "tool_call", "started second"),
            tool(3, "approval_waiting", "waiting third"),
        )

        val group = ToolGrouping.project(events).single() as ConversationTimelineItem.ToolGroup

        assertTrue(group.isRunning)
        assertSame(events.last(), group.activeEvent)
        assertEquals(2, group.additionalToolCount)
        assertEquals(0, group.failureCount)
    }

    @Test fun everySpecifiedActiveStageKeepsGroupInRunningState() {
        listOf("tool_call", "tool_progress", "approval_waiting").forEach { activeStage ->
            val group = ToolGrouping.project(listOf(tool(1), tool(2, activeStage)))
                .single() as ConversationTimelineItem.ToolGroup
            assertTrue("$activeStage should be active", group.isRunning)
            assertEquals(activeStage, group.activeEvent?.stage)
        }
    }

    @Test fun completedGroupExposesToolAndFailureCounts() {
        val events = listOf(
            tool(1, "tool_result"),
            tool(2, "tool_error"),
            tool(3, "tool_interrupted"),
            tool(4, "tool_error"),
        )

        val group = ToolGrouping.project(events).single() as ConversationTimelineItem.ToolGroup

        assertFalse(group.isRunning)
        assertNull(group.activeEvent)
        assertEquals(4, group.toolCount)
        assertEquals(2, group.failureCount)
    }
}
