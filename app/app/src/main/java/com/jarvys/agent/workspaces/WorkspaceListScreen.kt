package com.jarvys.agent.workspaces

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvys.agent.JarvysEmptyState
import com.jarvys.agent.LucideIcons
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

private data class ConversationRowMenuAction(
    val key: String,
    @androidx.annotation.StringRes val labelResource: Int,
    val destructive: Boolean = false,
)

/** Compact catalog row; the chat drawer no longer lists conversations, so only the workspace list uses it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationListRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    actionsEnabled: Boolean,
    menuActions: List<ConversationRowMenuAction>,
    onMenuAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().heightIn(min = 62.dp).semantics { role = Role.Button }
        .combinedClickable(enabled = enabled, onClick = onClick,
        onLongClick = { if (actionsEnabled && menuActions.isNotEmpty()) menuOpen = true })
        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
            else Color.Transparent, RoundedCornerShape(13.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.width(3.dp).height(36.dp).background(
            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (menuActions.isNotEmpty()) {
            Box {
                IconButton(enabled = actionsEnabled, onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(LucideIcons.Ellipsis, contentDescription = stringResource(R.string.drawer_chat_actions, title),
                        modifier = Modifier.size(18.dp).testTag("conversation-overflow-horizontal"))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menuActions.forEach { action ->
                        DropdownMenuItem(
                            modifier = Modifier.testTag("conversation-row-action-${action.key}"),
                            text = { Text(stringResource(action.labelResource),
                                color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                            onClick = { menuOpen = false; onMenuAction(action.key) },
                            enabled = actionsEnabled,
                        )
                    }
                }
            }
        }
    }
}