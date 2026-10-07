package com.jarvys.agent

import com.jarvys.agent.proactive.ProactiveConversation
import com.jarvys.agent.tasks.ScheduledTaskConversation

enum class ConversationAction { PIN, UNPIN, ARCHIVE, RESTORE, RENAME, DELETE }

internal fun isManagedSystemConversation(sessionId: String): Boolean =
    sessionId == ProactiveConversation.SESSION_ID || sessionId == ScheduledTaskConversation.SESSION_ID

internal fun conversationRemovalBlocked(state: AgentRunUiSnapshot, sending: Boolean,
    translating: Boolean, foregroundStopped: Boolean, crewActive: Boolean = false): Boolean =
    state.running || state.compacting || state.reflecting || sending || translating || !foregroundStopped || crewActive

internal fun conversationActionLeavesCurrent(action: ConversationAction, target: String, current: String): Boolean =
    target == current && (action == ConversationAction.ARCHIVE || action == ConversationAction.DELETE)
