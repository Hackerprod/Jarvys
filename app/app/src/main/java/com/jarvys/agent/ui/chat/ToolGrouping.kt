package com.jarvys.agent.ui.chat

import com.jarvys.agent.AgentRunUiEvent

private val activeToolStages = setOf("tool_call", "tool_progress", "approval_waiting")

/** A display-only timeline row. Transient answers retain their row key when promoted to durable events. */
internal sealed interface ConversationTimelineItem {
    val key: Long

    data class Event(val event: AgentRunUiEvent) : ConversationTimelineItem {
        override val key: Long get() = event.timelineKey ?: event.id
    }

    data class ToolGroup(val events: List<AgentRunUiEvent>) : ConversationTimelineItem {
        init {
            require(events.size >= 2) { "Tool groups require at least two events" }
            require(events.all { it.kind == "tool" }) { "Tool groups may contain only tool events" }
        }

        val firstEventId: Long get() = events.first().id
        override val key: Long get() = firstEventId
        val toolCount: Int get() = events.size
        val activeEvent: AgentRunUiEvent? get() = events.lastOrNull { it.stage in activeToolStages }
        val isRunning: Boolean get() = activeEvent != null
        /** Number represented by the `+N` suffix while [activeEvent] is shown. */
        val additionalToolCount: Int get() = (toolCount - 1).coerceAtLeast(0)
        val failureCount: Int get() = events.count { it.stage == "tool_error" }
    }
}

/** Groups adjacent tool rows without changing or mutating the underlying run events. */
internal object ToolGrouping {
    fun project(events: List<AgentRunUiEvent>): List<ConversationTimelineItem> {
        if (events.isEmpty()) return emptyList()

        val result = ArrayList<ConversationTimelineItem>(events.size)
        var index = 0
        while (index < events.size) {
            val event = events[index]
            if (event.kind != "tool") {
                result += ConversationTimelineItem.Event(event)
                index++
                continue
            }

            var endExclusive = index + 1
            while (endExclusive < events.size && events[endExclusive].kind == "tool") {
                endExclusive++
            }
            val run = events.subList(index, endExclusive).toList()
            result += if (run.size >= 2) {
                ConversationTimelineItem.ToolGroup(run)
            } else {
                ConversationTimelineItem.Event(run.single())
            }
            index = endExclusive
        }
        return result
    }
}
