package com.jarvys.agent

import com.jarvys.agent.proactive.ProactiveConversation
import com.jarvys.agent.tasks.ScheduledTaskConversation

enum class ConversationAction { PIN, UNPIN, ARCHIVE, RESTORE, RENAME, DELETE }

internal fun isManagedSystemConversation(sessionId: String): Boolean =
    sessionId == ProactiveConversation.SESSION_ID || sessionId == ScheduledTaskConversation.SESSION_ID

internal fun conversationRemovalBlocked(state: AgentRunUiSnapshot, sending: Boolean,
    translating: Boolean, foregroundStopped: Boolean, crewActive: Boolean = false): Boolean =
    state.running || state.stopping || state.compacting || state.reflecting || sending || translating || !foregroundStopped || crewActive

internal fun conversationActionLeavesCurrent(action: ConversationAction, target: String, current: String): Boolean =
    target == current && (action == ConversationAction.ARCHIVE || action == ConversationAction.DELETE)

internal fun conversationActionNavigatesToAgent(action: ConversationAction, target: String, current: String): Boolean =
    action == ConversationAction.DELETE && target == current

internal fun isReservedWorkspaceConversation(sessionId: String): Boolean =
    com.jarvys.agent.workspaces.WorkspaceIdentity.isCentral(sessionId) ||
        com.jarvys.agent.workspaces.WorkspaceIdentity.isExecutionSession(sessionId)

internal fun conversationActionForbiddenForWorkspace(action: ConversationAction, sessionId: String): Boolean =
    (action == ConversationAction.PIN || action == ConversationAction.RENAME ||
        action == ConversationAction.DELETE || action == ConversationAction.ARCHIVE) &&
        isReservedWorkspaceConversation(sessionId)
