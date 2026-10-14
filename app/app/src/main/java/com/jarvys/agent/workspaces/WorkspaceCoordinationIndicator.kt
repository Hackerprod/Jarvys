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

/** Metadata for one bounded page, never worker text or a claim that a question is resolved. */
@Immutable
data class WorkspaceCoordinationSummary(
    val live: Int = 0,
    val uncertain: Int = 0,
    val historical: Int = 0,
    val pageLimited: Boolean = false,
    val unavailable: Boolean = false,
)

@Composable
fun WorkspaceCoordinationIndicator(summary: WorkspaceCoordinationSummary, showHistory: Boolean = false) {
    if (summary.live == 0 && summary.uncertain == 0 && !summary.pageLimited &&
        !summary.unavailable && (!showHistory || summary.historical == 0)) return
    Column(Modifier.testTag("workspace-coordination-indicator")) {
        if (summary.live > 0) Text(pluralStringResource(R.plurals.workspace_coordination_live, summary.live, summary.live),
            style = MaterialTheme.typography.bodySmall)
        if (summary.uncertain > 0) Text(pluralStringResource(R.plurals.workspace_coordination_uncertain, summary.uncertain, summary.uncertain),
            style = MaterialTheme.typography.bodySmall)
        if (showHistory && summary.historical > 0) Text(pluralStringResource(R.plurals.workspace_coordination_historical, summary.historical, summary.historical),
            style = MaterialTheme.typography.bodySmall)
        if (summary.pageLimited) Text(stringResource(R.string.workspace_coordination_page_scope),
            style = MaterialTheme.typography.bodySmall)
        if (summary.unavailable) Text(stringResource(R.string.workspace_coordination_unavailable),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
