package com.jarvys.agent

/**
 * Bounded presentation for the visible live conversation. Null means static artwork, not Idle.
 * Working is backed only by the transient typed-tool marker, never by running or historical rows.
 */
fun mainJarvysMascotMode(
    snapshot: AgentRunUiSnapshot,
    visibleSessionId: String?,
    liveConversationVisible: Boolean,
): Int? {
    if (!liveConversationVisible || visibleSessionId.isNullOrBlank()
        || snapshot.sessionId != visibleSessionId || snapshot.compacting) return null
    if (snapshot.running) {
        return if (snapshot.interactiveOwnerSessionId == visibleSessionId
            && snapshot.liveMascotTool?.eligibleToAnimate == true) 2 else null
    }
    return when (snapshot.outcome) {
        "COMPLETED" -> 6
        "FAILED" -> 7
        "STOPPED", "PARTIAL", "TIMED_OUT", "INTERRUPTED" -> 8
        else -> null
    }
}
