package com.jarvys.agent.ui.chat

import com.jarvys.agent.AgentRunUiEvent

/** Selects at most one new transcript row for a brief arrival cue. */
internal object ChatMessageArrivalPolicy {
    fun key(event: AgentRunUiEvent): String = if (event.messageId.isNotBlank()) {
        "${event.kind}:${event.messageId}"
    } else "${event.kind}:${event.id}"

    fun newestUnseen(previousKeys: Set<String>, currentKeys: List<String>, liveChange: Boolean): String? {
        if (!liveChange) return null
        return currentKeys.lastOrNull { it !in previousKeys }
    }
}
