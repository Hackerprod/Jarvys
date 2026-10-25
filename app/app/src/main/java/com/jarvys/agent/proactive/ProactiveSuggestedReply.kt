package com.jarvys.agent.proactive

import com.jarvys.agent.AgentRunUiState
import com.jarvys.agent.LocalRunStore
import com.jarvys.agent.chatdb.StoredMessage
import com.jarvys.agent.workspaces.WorkspaceIdentity

data class ProactiveSuggestedReply(val label: String, val text: String)

data class ProactiveReplyClaim(val text: String, val threadKey: String, val userMessageId: String = "")

data class ProactiveThreadMessage(val text: String, val timestampMillis: Long, val fromAssistant: Boolean)

/**
 * A background writer just appended to Central: show the row if Central is the visible session. Only the
 * in-memory view is refreshed, so a failure here must not undo or retry an already committed delivery.
 */
fun LocalRunStore.refreshVisibleCentral() {
    val central = WorkspaceIdentity.CENTRAL_SESSION
    runCatching { AgentRunUiState.refreshPersistedSession(central, readRecentConversationTimeline(central)) }
}

/** Notification line for a stored thread row, with the same timestamp the timeline shows. */
fun StoredMessage.toThreadMessage(): ProactiveThreadMessage =
    ProactiveThreadMessage(text, (createdAtSeconds * 1_000.0).toLong(), role == "assistant")
