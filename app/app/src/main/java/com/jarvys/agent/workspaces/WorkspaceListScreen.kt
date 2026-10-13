package com.jarvys.agent.workspaces

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvys.agent.R

/** Reconstructed from delivered v95: catalog presentation does not start workspace execution. */
@Composable
fun WorkspaceListScreen(
    workspaces: List<WorkspaceSidebarRow>,
    workspacesLoading: Boolean = false,
    workspaceError: String? = null,
    actionsEnabled: Boolean = true,
    canPreviousWorkspacePage: Boolean = false,
    canFirstWorkspacePage: Boolean = false,
    canNextWorkspacePage: Boolean = false,
    onOpenWorkspace: (String) -> Unit,
    onPreviousWorkspacePage: () -> Unit = {},
    onNextWorkspacePage: () -> Unit = {},
    onFirstWorkspacePage: () -> Unit = {},
    onRetryWorkspaces: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("workspace-list"),
        contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (workspacesLoading) item(key = "workspace-loading") {
            Text(stringResource(R.string.workspace_loading), Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("workspace-list-loading"))
        }
        if (workspaceError != null) item(key = "workspace-error") {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("workspace-list-error")) {
                Text(workspaceError, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetryWorkspaces, modifier = Modifier.testTag("workspace-list-retry")) {
                    Text(stringResource(R.string.workspace_retry))
                }
            }
        }
        if (!workspacesLoading && workspaceError == null && workspaces.isEmpty()) item(key = "workspace-empty") {
            Text(stringResource(if (canFirstWorkspacePage) R.string.workspace_page_empty else R.string.workspace_sidebar_empty),
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("workspace-list-empty"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(workspaces, key = { "workspace-" + it.id }) { workspace ->
            Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("workspace-list-row-${workspace.id}")
                    .clickable(enabled = actionsEnabled && !workspacesLoading && workspaceError == null, role = Role.Button) {
                        onOpenWorkspace(workspace.id)
                    }.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(workspaceDisplayText(workspace.title, stringResource(R.string.workspace_title), 256),
                        style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    WorkspaceStatusText(workspace.status)
                    WorkspaceCoordinationIndicator(workspace.coordination)
                    if (workspace.statusPageLimited) Text(stringResource(R.string.workspace_status_page_scope),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (canFirstWorkspacePage || canNextWorkspacePage) item(key = "workspace-pages") {
            WorkspacePageButtons(canPreviousWorkspacePage, canFirstWorkspacePage, canNextWorkspacePage,
                !workspacesLoading && actionsEnabled, onPreviousWorkspacePage, onNextWorkspacePage, onFirstWorkspacePage)
        }
    }
}
