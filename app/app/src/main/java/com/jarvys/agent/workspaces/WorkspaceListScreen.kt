package com.jarvys.agent.workspaces

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvys.agent.JarvysEmptyState
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R
import com.jarvys.agent.ui.chat.ConversationListRow
import com.jarvys.agent.ui.chat.ConversationRowMenuAction

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
    val initialLoad = workspacesLoading && workspaces.isEmpty()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("workspace-list"),
        contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (initialLoad) item(key = "workspace-loading") {
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
        if (!initialLoad && workspaceError == null && workspaces.isEmpty()) item(key = "workspace-empty") {
            JarvysEmptyState(
                icon = LucideIcons.Boxes,
                title = stringResource(if (canFirstWorkspacePage) R.string.workspace_page_empty else R.string.workspace_sidebar_empty),
                body = "",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                titleModifier = Modifier.testTag("workspace-list-empty"),
            )
        }
        items(workspaces, key = { "workspace-" + it.id }) { workspace ->
            val title = workspaceDisplayText(workspace.title, stringResource(R.string.workspace_title), 256)
            val active = workspace.status in setOf(WorkspaceUiStatus.ACTIVE, WorkspaceUiStatus.WAITING,
                WorkspaceUiStatus.CANCEL_REQUESTED, WorkspaceUiStatus.STOPPING)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ConversationListRow(
                    title = title,
                    subtitle = stringResource(workspaceStatusResource(workspace.status)),
                    selected = false,
                    active = active,
                    enabled = actionsEnabled && workspaceError == null,
                    onClick = { if (actionsEnabled && workspaceError == null) onOpenWorkspace(workspace.id) },
                    actionsEnabled = actionsEnabled && workspaceError == null,
                    menuActions = listOf(ConversationRowMenuAction("open", R.string.workspace_open)),
                    onMenuAction = { if (it == "open" && actionsEnabled && workspaceError == null) onOpenWorkspace(workspace.id) },
                    modifier = Modifier.testTag("workspace-list-row-${workspace.id}"),
                )
                if (workspace.statusPageLimited) {
                    Text(stringResource(R.string.workspace_status_page_scope),
                        modifier = Modifier.padding(start = 26.dp, end = 12.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                WorkspaceCoordinationIndicator(workspace.coordination)
            }
        }
        if (canFirstWorkspacePage || canNextWorkspacePage) item(key = "workspace-pages") {
            WorkspacePageButtons(canPreviousWorkspacePage, canFirstWorkspacePage, canNextWorkspacePage,
                !workspacesLoading && actionsEnabled, onPreviousWorkspacePage, onNextWorkspacePage, onFirstWorkspacePage)
        }
    }
}
