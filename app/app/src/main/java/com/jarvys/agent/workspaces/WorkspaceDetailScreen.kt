package com.jarvys.agent.workspaces

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvys.agent.R

/** Read-only projection. All commands return the displayed identity/revision to central. */
@Composable
fun WorkspaceDetailScreen(
    state: WorkspaceDetailUiState,
    onCorrect: (assignmentId: String, revision: Long) -> Unit,
    onPause: (assignmentId: String, revision: Long) -> Unit,
    onResume: (assignmentId: String, revision: Long) -> Unit,
    onOpenFiles: () -> Unit,
    modifier: Modifier = Modifier,
    canPreviousPage: Boolean = false,
    canFirstPage: Boolean = false,
    onPreviousPage: () -> Unit = {},
    onNextPage: () -> Unit = {},
    onFirstPage: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val available = !state.loading && state.error == null
    LazyColumn(
        modifier.fillMaxSize().testTag("workspace-detail"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "workspace-summary") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(workspaceDisplayText(state.title, stringResource(R.string.workspace_title), 256),
                    Modifier.semantics { heading() }.testTag("workspace-title"),
                    style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                WorkspaceStatusText(state.status)
                WorkspaceCoordinationIndicator(state.coordination, showHistory = true)
                if (state.statusPageLimited) Text(stringResource(R.string.workspace_status_page_scope),
                    Modifier.testTag("workspace-status-page-scope"), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.workspace_read_only_notice), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onOpenFiles, enabled = available && state.canOpenFiles,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("workspace-open-files")) {
                    Text(stringResource(R.string.workspace_open_files))
                }
            }
        }
        if (state.loading) item(key = "workspace-loading") {
            Text(stringResource(R.string.workspace_loading), Modifier.testTag("workspace-loading"))
        }
        if (state.error != null) item(key = "workspace-error") {
            Column(Modifier.testTag("workspace-error"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.workspace_load_error), color = MaterialTheme.colorScheme.error)
                androidx.compose.material3.TextButton(onClick = onRetry, modifier = Modifier.testTag("workspace-retry")) {
                    Text(stringResource(R.string.workspace_retry))
                }
                Text(workspaceDisplayText(state.error, stringResource(R.string.workspace_unknown_evidence)),
                    maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (available && state.assignments.isEmpty()) item(key = "workspace-empty") {
            Text(stringResource(if (canFirstPage) R.string.workspace_page_empty else R.string.workspace_empty), Modifier.testTag("workspace-empty"),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (canFirstPage || state.nextCursor != null) item(key = "workspace-pages") {
            WorkspacePageButtons(canPreviousPage, canFirstPage, state.nextCursor != null, !state.loading,
                onPreviousPage, onNextPage, onFirstPage)
        }
        items(state.assignments, key = { "assignment-${it.id}" }) { assignment ->
            WorkspaceAssignmentCard(assignment, available, onCorrect, onPause, onResume)
        }
    }
}

@Composable
private fun WorkspaceAssignmentCard(
    assignment: WorkspaceAssignmentUi,
    available: Boolean,
    onCorrect: (String, Long) -> Unit,
    onPause: (String, Long) -> Unit,
    onResume: (String, Long) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("workspace-assignment-${assignment.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(workspaceDisplayText(assignment.name, stringResource(R.string.workspace_assignment), 256),
                Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            WorkspaceStatusText(assignment.status)
            WorkspaceField(R.string.workspace_mission, assignment.mission, R.string.workspace_unknown_evidence, 6)
            WorkspaceField(R.string.workspace_recovery, assignment.recoveryNote, R.string.workspace_recovery_unknown, 5)
            WorkspaceField(R.string.workspace_result, assignment.result, R.string.workspace_result_unknown, 12)
            if (!assignment.result.isNullOrBlank()) {
                Text(stringResource(if (assignment.resultVerified) R.string.workspace_result_verified else R.string.workspace_result_unverified),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (assignment.pendingControlSaved) Text(stringResource(R.string.workspace_pending_saved),
                Modifier.testTag("workspace-pending-control-${assignment.id}"), style = MaterialTheme.typography.bodySmall)
            // Stack actions so long translations and large fonts retain full-size touch targets.
            WorkspaceAction(R.string.workspace_correct, "workspace-correct-${assignment.id}", available && assignment.canCorrect) {
                onCorrect(assignment.id, assignment.revision)
            }
            WorkspaceAction(R.string.workspace_pause, "workspace-pause-${assignment.id}", available && assignment.canPause) {
                onPause(assignment.id, assignment.revision)
            }
            WorkspaceAction(R.string.workspace_resume, "workspace-resume-${assignment.id}", available && assignment.canResume) {
                onResume(assignment.id, assignment.revision)
            }
        }
    }
}

@Composable
private fun WorkspaceField(@StringRes label: Int, value: String?, @StringRes fallback: Int, lines: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(workspaceDisplayText(value, stringResource(fallback)), style = MaterialTheme.typography.bodyMedium,
            maxLines = lines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WorkspaceAction(@StringRes label: Int, tag: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
        Text(stringResource(label))
    }
}

@Composable
internal fun WorkspaceStatusText(status: WorkspaceUiStatus, modifier: Modifier = Modifier) {
    Text(stringResource(workspaceStatusResource(status)), modifier, style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@StringRes
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

/** At most 32 cursor positions are remembered; First always restores access to the catalog start. */
@Composable
internal fun WorkspacePageButtons(canPrevious: Boolean, canFirst: Boolean, canNext: Boolean, enabled: Boolean,
                                  onPrevious: () -> Unit, onNext: () -> Unit, onFirst: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (canFirst) androidx.compose.material3.TextButton(onClick = onFirst, enabled = enabled,
            modifier = Modifier.testTag("workspace-first-page")) { Text(stringResource(R.string.workspace_first_page)) }
        if (canPrevious) androidx.compose.material3.TextButton(onClick = onPrevious, enabled = enabled,
            modifier = Modifier.testTag("workspace-previous-page")) { Text(stringResource(R.string.workspace_previous_page)) }
        if (canNext) androidx.compose.material3.TextButton(onClick = onNext, enabled = enabled,
            modifier = Modifier.testTag("workspace-next-page")) { Text(stringResource(R.string.workspace_next_page)) }
    }
}
