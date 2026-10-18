package com.jarvys.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvys.agent.R
import com.jarvys.agent.ConversationAction
import com.jarvys.agent.ScrollableDialogContent
import com.jarvys.agent.JarvysTextField
import com.jarvys.agent.RunHistoryItem
import com.jarvys.agent.drawerConversationTitle
import com.jarvys.agent.LucideIcons

internal data class ConversationActionDialogTarget(val sessionId: String, val title: String)

/** Shared actions preserve validation and explicit delete confirmation in both chat lists. */
@Composable
internal fun ChatConversationActions(
    actionsEnabled: Boolean,
    onConversationAction: (String, ConversationAction, String?) -> Unit,
    content: @Composable (action: (RunHistoryItem, ConversationAction) -> Unit) -> Unit,
) {
    var renameTarget by remember { mutableStateOf<ConversationActionDialogTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationActionDialogTarget?>(null) }
    var renameText by remember { mutableStateOf("") }
    val fallbackTitle = stringResource(R.string.drawer_chat_fallback)
    fun action(record: RunHistoryItem, action: ConversationAction) {
        if (!actionsEnabled) return
        when (action) {
            ConversationAction.RENAME -> {
                renameTarget = ConversationActionDialogTarget(record.sessionId, record.title ?: record.goal)
                renameText = record.title ?: record.goal
            }
            ConversationAction.DELETE -> {
                deleteTarget = ConversationActionDialogTarget(record.sessionId,
                    drawerConversationTitle(record.title, record.goal, fallbackTitle))
            }
            else -> onConversationAction(record.sessionId, action, null)
        }
    }
    content(::action)
    ConversationActionDialogs(
        renameTarget = renameTarget,
        deleteTarget = deleteTarget,
        renameText = renameText,
        onRenameTextChange = { renameText = it },
        actionsEnabled = actionsEnabled,
        onConversationAction = onConversationAction,
        onDismissRename = { renameTarget = null },
        onDismissDelete = { deleteTarget = null },
    )
}

/** Rename and delete confirmation are shared by the drawer and the chat header menu. */
@Composable
internal fun ConversationActionDialogs(
    renameTarget: ConversationActionDialogTarget?,
    deleteTarget: ConversationActionDialogTarget?,
    renameText: String,
    onRenameTextChange: (String) -> Unit,
    actionsEnabled: Boolean,
    onConversationAction: (String, ConversationAction, String?) -> Unit,
    onDismissRename: () -> Unit,
    onDismissDelete: () -> Unit,
) {
    renameTarget?.let { target ->
        AlertDialog(onDismissRequest = onDismissRename,
            title = { Text(stringResource(R.string.drawer_rename_chat)) },
            text = { ScrollableDialogContent {
                JarvysTextField(value = renameText, onValueChange = onRenameTextChange, singleLine = true,
                    label = { Text(stringResource(R.string.drawer_chat_name)) })
                if (renameText.isBlank()) Text(stringResource(R.string.drawer_name_required), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(enabled = actionsEnabled && renameText.isNotBlank(), onClick = {
                onDismissRename(); onConversationAction(target.sessionId, ConversationAction.RENAME, renameText.trim())
            }) { Text(stringResource(R.string.drawer_save_name)) } },
            dismissButton = { TextButton(onClick = onDismissRename) { Text(stringResource(R.string.drawer_cancel_action)) } })
    }
    deleteTarget?.let { target ->
        AlertDialog(onDismissRequest = onDismissDelete,
            title = { Text(stringResource(R.string.drawer_delete_chat)) },
            text = { ScrollableDialogContent { Text(stringResource(R.string.drawer_delete_confirmation, target.title)) } },
            confirmButton = { TextButton(enabled = actionsEnabled, onClick = {
                onDismissDelete(); onConversationAction(target.sessionId, ConversationAction.DELETE, null)
            }) { Text(stringResource(R.string.drawer_delete_action), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = onDismissDelete) { Text(stringResource(R.string.drawer_cancel_action)) } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationSessionRow(title: String, selected: Boolean, active: Boolean, onClick: () -> Unit,
    pinned: Boolean, archived: Boolean, actionsEnabled: Boolean, managed: Boolean,
    showPinnedMarker: Boolean = true, verticalOverflow: Boolean = false,
    protectedWorkspace: Boolean = false, onAction: (ConversationAction) -> Unit) {
    val menuActions = if (protectedWorkspace) emptyList() else buildList {
        add(ConversationRowMenuAction("${if (pinned) "unpin" else "pin"}",
            if (pinned) R.string.drawer_unpin else R.string.drawer_pin))
        add(ConversationRowMenuAction("rename", R.string.drawer_rename_chat))
        add(ConversationRowMenuAction("${if (archived) "restore" else "archive"}",
            if (archived) R.string.drawer_unarchive else R.string.drawer_archive))
        if (!managed) add(ConversationRowMenuAction("delete", R.string.drawer_delete_action, destructive = true))
    }
    ConversationListRow(
        title = title,
        selected = selected,
        active = active,
        onClick = onClick,
        actionsEnabled = actionsEnabled,
        verticalOverflow = verticalOverflow,
        pinned = pinned && showPinnedMarker,
        menuActions = menuActions,
        onMenuAction = { key ->
            val action = when (key) {
                "pin" -> ConversationAction.PIN
                "unpin" -> ConversationAction.UNPIN
                "rename" -> ConversationAction.RENAME
                "archive" -> ConversationAction.ARCHIVE
                "restore" -> ConversationAction.RESTORE
                "delete" -> ConversationAction.DELETE
                else -> null
            }
            action?.let(onAction)
        },
    )
}

internal data class ConversationRowMenuAction(
    val key: String,
    @androidx.annotation.StringRes val labelResource: Int,
    val destructive: Boolean = false,
)

/** Shared compact chat row used by the conversation drawer and the workspace catalog. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationListRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    actionsEnabled: Boolean,
    menuActions: List<ConversationRowMenuAction>,
    onMenuAction: (String) -> Unit,
    pinned: Boolean = false,
    verticalOverflow: Boolean = false,
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
        if (pinned) Text(stringResource(R.string.drawer_pinned), Modifier.testTag("drawer-pinned-marker"), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary)
        if (menuActions.isNotEmpty()) {
            Box {
                IconButton(enabled = actionsEnabled, onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(LucideIcons.Ellipsis, contentDescription = stringResource(R.string.drawer_chat_actions, title),
                        modifier = Modifier.size(18.dp).rotate(if (verticalOverflow) 90f else 0f)
                            .testTag(if (verticalOverflow) "conversation-overflow-vertical" else "conversation-overflow-horizontal"))
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
