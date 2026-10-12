package com.jarvys.agent.workspaces

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvys.agent.R

/** Trusted metadata only. No Markdown, clickable worker refs, assistant actions or raw report. */
@Composable
fun WorkspaceResultNotice(reference: WorkspaceResultReference) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.testTag("workspace-result-${reference.referenceId}")) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.workspace_result_notice_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.workspace_result_notice_identity, reference.workspaceId,
                reference.assignmentId), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.workspace_result_notice_status, reference.reportedStatus),
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.workspace_result_notice_disclaimer), style = MaterialTheme.typography.bodySmall)
        }
    }
}
