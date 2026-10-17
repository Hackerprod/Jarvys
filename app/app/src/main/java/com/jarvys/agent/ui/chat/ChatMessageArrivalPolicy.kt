package com.jarvys.agent.ui.chat

import com.jarvys.agent.AgentRunUiEvent

/** Selects at most one new transcript row for a brief arrival cue. */
internal object ChatMessageArrivalPolicy {
    fun key(event: AgentRunUiEvent): String = when {
        event.kind == "assistant_stream" -> "assistant:${event.timelineKey ?: event.id}"
        event.kind == "assistant" && event.timelineKey != null -> "assistant:${event.timelineKey}"
        event.messageId.isNotBlank() -> "${event.kind}:${event.messageId}"
        else -> "${event.kind}:${event.id}"
    }

    fun newestUnseen(previousKeys: Set<String>, currentKeys: List<String>, liveChange: Boolean): String? {
        if (!liveChange) return null
        return currentKeys.lastOrNull { it !in previousKeys }
    }
}
