package com.jarvys.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
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

/** Shared actions preserve validation and explicit delete confirmation in both chat lists. */
@Composable
internal fun ChatConversationActions(
    actionsEnabled: Boolean,
    onConversationAction: (String, ConversationAction, String?) -> Unit,
    content: @Composable (action: (RunHistoryItem, ConversationAction) -> Unit) -> Unit,
) {
    var renameTarget by remember { mutableStateOf<RunHistoryItem?>(null) }
    var deleteTarget by remember { mutableStateOf<RunHistoryItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    fun action(record: RunHistoryItem, action: ConversationAction) {
        if (!actionsEnabled) return
        when (action) {
            ConversationAction.RENAME -> { renameTarget = record; renameText = record.title ?: record.goal }
            ConversationAction.DELETE -> { deleteTarget = record }
            else -> onConversationAction(record.sessionId, action, null)
        }
    }
    content(::action)
    renameTarget?.let { record ->
        AlertDialog(onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.drawer_rename_chat)) },
            text = { ScrollableDialogContent {
                JarvysTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true,
                    label = { Text(stringResource(R.string.drawer_chat_name)) })
                if (renameText.isBlank()) Text(stringResource(R.string.drawer_name_required), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(enabled = actionsEnabled && renameText.isNotBlank(), onClick = {
                renameTarget = null; onConversationAction(record.sessionId, ConversationAction.RENAME, renameText.trim())
            }) { Text(stringResource(R.string.drawer_save_name)) } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.drawer_cancel_action)) } })
    }
    deleteTarget?.let { record ->
        AlertDialog(onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.drawer_delete_chat)) },
            text = { ScrollableDialogContent { Text(stringResource(R.string.drawer_delete_confirmation,
                drawerConversationTitle(record.title, record.goal, stringResource(R.string.drawer_chat_fallback)))) } },
            confirmButton = { TextButton(enabled = actionsEnabled, onClick = {
                deleteTarget = null; onConversationAction(record.sessionId, ConversationAction.DELETE, null)
            }) { Text(stringResource(R.string.drawer_delete_action), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.drawer_cancel_action)) } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationSessionRow(title: String, selected: Boolean, active: Boolean, onClick: () -> Unit,
    pinned: Boolean, archived: Boolean, actionsEnabled: Boolean, managed: Boolean, onAction: (ConversationAction) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).combinedClickable(onClick = onClick,
        onLongClick = { if (actionsEnabled) menuOpen = true })
        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
            else Color.Transparent, RoundedCornerShape(13.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.width(3.dp).height(36.dp).background(
            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            RoundedCornerShape(2.dp)))
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (pinned) Text(stringResource(R.string.drawer_pinned), Modifier.testTag("drawer-pinned-marker"), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary)
        Box {
            IconButton(enabled = actionsEnabled, onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(LucideIcons.Ellipsis, contentDescription = stringResource(R.string.drawer_chat_actions, title), modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                listOf(
                    (if (pinned) ConversationAction.UNPIN else ConversationAction.PIN) to (if (pinned) R.string.drawer_unpin else R.string.drawer_pin),
                    ConversationAction.RENAME to R.string.drawer_rename_chat,
                    (if (archived) ConversationAction.RESTORE else ConversationAction.ARCHIVE) to (if (archived) R.string.drawer_unarchive else R.string.drawer_archive),
                ).forEach { (action, label) ->
                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { menuOpen = false; onAction(action) }, enabled = actionsEnabled)
                }
                if (!managed) DropdownMenuItem(text = { Text(stringResource(R.string.drawer_delete_action), color = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; onAction(ConversationAction.DELETE) }, enabled = actionsEnabled)
            }
        }
    }
}
