package com.jarvys.agent.workspaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.AgentRunUiTimelineEvents
import com.jarvys.agent.JarvysEmptyState
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.skills.SkillFileLink
import com.jarvys.agent.ui.chat.ConversationTimeline
import com.jarvys.agent.ui.chat.AssistantReplyCapabilities

/** The workspace detail body is the shared chat timeline for its registered execution session. */
@Composable
fun WorkspaceDetailScreen(
    state: WorkspaceChatUiState,
    runSnapshot: AgentRunUiSnapshot,
    connectorRegistry: ConnectorRegistry,
    onOpenPreview: (String) -> Unit,
    onOpenSkillFile: (SkillFileLink) -> Unit,
    onOpenCrewMission: (String) -> Unit,
    onRetry: () -> Unit = {},
    isLoadingOlderHistory: Boolean = false,
    olderHistoryLoadError: Boolean = false,
    onLoadOlderHistory: () -> Unit = {},
    modifier: Modifier = Modifier,
    workspaceRunning: Boolean? = null,
    timelineEvents: AgentRunUiTimelineEvents? = null,
) {
    val sessionSnapshot = runSnapshot.takeIf { it.sessionId == state.executionSessionId }
    val sourceEvents = timelineEvents?.takeIf { it.sessionId == state.executionSessionId }?.events ?: state.events
    val activeWork = workspaceRunning ?: workspaceHasActiveWork(state, sessionSnapshot)
    val events = remember(state.workspaceId, sourceEvents) {
        WorkspaceTimelineProjection.project(state.workspaceId, sourceEvents)
    }
    val timelineSnapshot = (sessionSnapshot ?: AgentRunUiSnapshot(sessionId = state.executionSessionId))
        .copy(events = events, running = activeWork, presenceSummary = null)
    Box(modifier.fillMaxSize().testTag("workspace-chat-timeline")) {
        when {
            state.loading && events.isEmpty() -> JarvysEmptyState(
                icon = LucideIcons.Boxes,
                title = stringResource(R.string.workspace_chat_loading),
                body = "",
                modifier = Modifier.fillMaxSize().testTag("workspace-chat-loading"),
            )
            state.error != null && events.isEmpty() -> JarvysEmptyState(
                icon = LucideIcons.Boxes,
                title = stringResource(R.string.workspace_chat_load_error),
                body = state.error,
                modifier = Modifier.fillMaxSize().testTag("workspace-chat-error"),
                action = {
                    TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("workspace-chat-retry")) {
                        Text(stringResource(R.string.workspace_retry))
                    }
                },
            )
            events.isEmpty() && !activeWork -> JarvysEmptyState(
                icon = LucideIcons.Boxes,
                title = stringResource(R.string.workspace_chat_empty),
                body = "",
                modifier = Modifier.fillMaxSize().testTag("workspace-chat-empty"),
            )
            else -> ConversationTimeline(
                conversationKey = state.executionSessionId,
                generatedImageSessionId = state.executionSessionId,
                events = events,
                hasOlderHistory = state.hasOlderHistory,
                isLoadingOlderHistory = isLoadingOlderHistory,
                olderHistoryLoadError = olderHistoryLoadError,
                onLoadOlderHistory = onLoadOlderHistory,
                isRunning = activeWork,
                connectorRegistry = connectorRegistry,
                onOpenPreview = onOpenPreview,
                onOpenSkillFile = onOpenSkillFile,
                allowRegeneration = false,
                isCompacting = sessionSnapshot?.compacting == true,
                compactionStatus = sessionSnapshot?.compactionStatus,
                isReflecting = sessionSnapshot?.reflecting == true
                    && sessionSnapshot.reflectionSessionId == state.executionSessionId,
                reflectionStatus = sessionSnapshot?.reflectionStatus,
                onOpenMission = { mission ->
                    if (mission.workspaceId == state.workspaceId && mission.executionSessionId == state.executionSessionId)
                        onOpenCrewMission(mission.missionId)
                },
                runSnapshot = timelineSnapshot,
                showAssistantProgress = true,
                assistantReplyCapabilities = AssistantReplyCapabilities.Workspace,
                allowMessageEntrance = sessionSnapshot != null,
            )
        }
    }
}

@Composable
internal fun WorkspaceStatusText(status: WorkspaceUiStatus, modifier: Modifier = Modifier) {
    Text(stringResource(workspaceStatusResource(status)), modifier, style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@androidx.annotation.StringRes
internal fun workspaceStatusResource(status: WorkspaceUiStatus): Int = when (status) {
    WorkspaceUiStatus.UNKNOWN -> R.string.workspace_status_unknown
    WorkspaceUiStatus.ACTIVE -> R.string.workspace_status_active
    WorkspaceUiStatus.WAITING -> R.string.workspace_status_waiting
    WorkspaceUiStatus.CANCEL_REQUESTED -> R.string.workspace_status_cancel_requested
    WorkspaceUiStatus.STOPPING -> R.string.workspace_status_stopping
    WorkspaceUiStatus.PAUSED -> R.string.workspace_status_paused
    WorkspaceUiStatus.PARTIAL -> R.string.workspace_status_partial
    WorkspaceUiStatus.INTERRUPTED -> R.string.workspace_status_interrupted
    WorkspaceUiStatus.COMPLETED -> R.string.workspace_status_completed
}

/** Bounded catalog paging shared by the workspace list. */
@Composable
internal fun WorkspacePageButtons(canPrevious: Boolean, canFirst: Boolean, canNext: Boolean, enabled: Boolean,
                                  onPrevious: () -> Unit, onNext: () -> Unit, onFirst: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (canFirst) TextButton(onClick = onFirst, enabled = enabled,
            modifier = Modifier.testTag("workspace-first-page")) { Text(stringResource(R.string.workspace_first_page)) }
        if (canPrevious) TextButton(onClick = onPrevious, enabled = enabled,
            modifier = Modifier.testTag("workspace-previous-page")) { Text(stringResource(R.string.workspace_previous_page)) }
        if (canNext) TextButton(onClick = onNext, enabled = enabled,
            modifier = Modifier.testTag("workspace-next-page")) { Text(stringResource(R.string.workspace_next_page)) }
    }
}
