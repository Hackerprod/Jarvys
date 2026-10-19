package com.jarvys.agent.workspaces

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.jarvys.agent.R

/** Counts only; message bodies and chat events are never part of the workspace-list projection. */
@Immutable
data class WorkspaceCoordinationSummary(
    val pendingInputs: Int = 0,
    val uncertainReplies: Int = 0,
    val pageLimited: Boolean = false,
    val unavailable: Boolean = false,
) {
    init {
        require(pendingInputs >= 0 && uncertainReplies >= 0)
    }
}

@Composable
fun WorkspaceCoordinationIndicator(summary: WorkspaceCoordinationSummary) {
    if (summary.pendingInputs == 0 && summary.uncertainReplies == 0 &&
        !summary.pageLimited && !summary.unavailable) return
    Column(Modifier.testTag("workspace-coordination-indicator")) {
        if (summary.pendingInputs > 0) Text(
            pluralStringResource(R.plurals.workspace_coordination_pending, summary.pendingInputs, summary.pendingInputs),
            style = MaterialTheme.typography.bodySmall,
        )
        if (summary.uncertainReplies > 0) Text(
            pluralStringResource(R.plurals.workspace_coordination_uncertain, summary.uncertainReplies, summary.uncertainReplies),
            style = MaterialTheme.typography.bodySmall,
        )
        if (summary.pageLimited) Text(
            stringResource(R.string.workspace_coordination_page_scope),
            style = MaterialTheme.typography.bodySmall,
        )
        if (summary.unavailable) Text(
            stringResource(R.string.workspace_coordination_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
