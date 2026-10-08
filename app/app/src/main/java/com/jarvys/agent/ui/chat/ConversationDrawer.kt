package com.jarvys.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvys.agent.R
import com.jarvys.agent.ConversationAction
import com.jarvys.agent.isManagedSystemConversation
import com.jarvys.agent.ScrollableDialogContent
import com.jarvys.agent.JarvysTextField
import com.jarvys.agent.RunHistoryItem
import com.jarvys.agent.drawerChatsForDisplay
import com.jarvys.agent.drawerConversationTitle
import com.jarvys.agent.JarvysSectionLabel
import com.jarvys.agent.LucideIcons
import kotlinx.coroutines.launch

@Composable
fun ConversationDrawer(
    history: List<RunHistoryItem>,
    activeSessionId: String,
    activeTitle: String?,
    activeGoal: String,
    activeSessionVisible: Boolean,
    selectedHistoryId: String?,
    isChatRoute: Boolean,
    onNewConversation: () -> Unit,
    onResumeActive: () -> Unit,
    onOpenHistory: (String) -> Unit,
    onOpenSettings: () -> Unit,
    actionsEnabled: Boolean = true,
    onConversationAction: (String, ConversationAction, String?) -> Unit = { _, _, _ -> },
) {
    val configuration = LocalConfiguration.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RunHistoryItem?>(null) }
    var deleteTarget by remember { mutableStateOf<RunHistoryItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    fun action(record: RunHistoryItem, action: ConversationAction) {
        when (action) {
            ConversationAction.RENAME -> { renameTarget = record; renameText = record.title ?: record.goal }
            ConversationAction.DELETE -> { deleteTarget = record }
            else -> onConversationAction(record.sessionId, action, null)
        }
    }
    val activeRecord = history.firstOrNull { it.sessionId == activeSessionId }
        ?: RunHistoryItem(activeSessionId, activeGoal, "", 0, 0, 0.0, activeSessionId, activeTitle)

    val searchableHistory = remember(history, activeSessionVisible, activeSessionId) {
        history.filterNot { activeSessionVisible && it.sessionId == activeSessionId }
    }
    val visibleHistory = remember(searchableHistory, filter, archived) { drawerChatsForDisplay(searchableHistory, filter, archived) }
    val activeVisible = activeSessionVisible && activeRecord.archived == archived &&
        (filter.isBlank() || activeTitle.orEmpty().contains(filter.trim(), true) || activeGoal.contains(filter.trim(), true))
    val clearDescription = stringResource(R.string.drawer_clear_search)
    val historyDescription = stringResource(R.string.drawer_history)
    val settingsDescription = stringResource(R.string.drawer_settings)
    val screenWidth = configuration.screenWidthDp.dp

    ModalDrawerSheet(
        modifier = Modifier.fillMaxHeight().width((screenWidth * 0.86f).coerceAtMost(420.dp)),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.drawer_chats_heading),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.chat_drawer_local_profile),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = onNewConversation, modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)) {
                        Icon(LucideIcons.MessageCirclePlus, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(stringResource(R.string.drawer_new_chat))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Surface(Modifier.weight(1f).heightIn(min = 48.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                        shape = RoundedCornerShape(13.dp)) {
                        Row(Modifier.padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Icon(LucideIcons.Search, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (filter.isEmpty()) Text(stringResource(R.string.drawer_search_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
                                BasicTextField(value = filter, onValueChange = { filter = it }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
                            }
                            if (filter.isNotEmpty()) IconButton(onClick = { filter = "" },
                                modifier = Modifier.size(40.dp).semantics { contentDescription = clearDescription }) {
                                Icon(LucideIcons.X, contentDescription = null, modifier = Modifier.size(17.dp))
                            }
                        }
                    }
                    IconButton(onClick = {
                        if (filter.isNotEmpty()) filter = ""
                        else scope.launch { listState.animateScrollToItem(if (activeVisible) 1 else 0) }
                    }, modifier = Modifier.size(48.dp).semantics { contentDescription = historyDescription }) {
                        Icon(LucideIcons.History, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    JarvysSectionLabel(stringResource(if (archived) R.string.drawer_archived_chats else R.string.drawer_history_section))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { archived = !archived; filter = "" }) {
                        Text(stringResource(if (archived) R.string.drawer_back_to_chats else R.string.drawer_archived_chats))
                    }
                }
            }

            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                if (activeVisible) item(key = "active-session") {
                    SessionRow(drawerConversationTitle(activeTitle, activeGoal,
                        stringResource(R.string.drawer_chat_fallback)),
                        selected = isChatRoute && selectedHistoryId == null,
                        active = true,
                        onClick = onResumeActive, pinned = activeRecord.pinned, archived = activeRecord.archived,
                        actionsEnabled = actionsEnabled, managed = isManagedSystemConversation(activeSessionId),
                        onAction = { action(activeRecord, it) })
                }
                if (visibleHistory.isEmpty() && !activeVisible) item(key = "history-empty") {
                    Text(stringResource(if (filter.isNotBlank()) R.string.drawer_search_no_results else if (archived) R.string.drawer_no_archived_chats else R.string.drawer_no_chats),
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                items(visibleHistory, key = { it.id }) { record ->
                    SessionRow(drawerConversationTitle(record.title, record.goal,
                        stringResource(R.string.drawer_chat_fallback)),
                        selected = isChatRoute && selectedHistoryId == record.id,
                        active = false,
                        onClick = { onOpenHistory(record.id) }, pinned = record.pinned, archived = record.archived,
                        actionsEnabled = actionsEnabled, managed = isManagedSystemConversation(record.sessionId),
                        onAction = { action(record, it) })
                }
            }

            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 9.dp, end = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center) {
                        Text("J", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.drawer_local_profile), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)
                        .semantics { contentDescription = settingsDescription }) {
                        Icon(LucideIcons.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
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
private fun SessionRow(title: String, selected: Boolean, active: Boolean, onClick: () -> Unit,
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
        if (pinned) Text(stringResource(R.string.drawer_pinned), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary)
        Box {
            IconButton(enabled = actionsEnabled, onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
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
