package com.jarvys.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvys.agent.R
import com.jarvys.agent.ConversationAction
import com.jarvys.agent.isManagedSystemConversation
import com.jarvys.agent.RunHistoryItem
import com.jarvys.agent.drawerChatsForDisplay
import com.jarvys.agent.drawerConversationTitle
import com.jarvys.agent.JarvysSectionLabel
import com.jarvys.agent.LucideIcons

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import com.jarvys.agent.drawerChatMatches

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
    onOpenBots: () -> Unit = {},
    actionsEnabled: Boolean = true,
    onConversationAction: (String, ConversationAction, String?) -> Unit = { _, _, _ -> },
    onOpenScheduledTasks: () -> Unit = {},
    isDrawerOpen: Boolean = true,
    onCloseDrawer: () -> Unit = {},
) {
    val configuration = LocalConfiguration.current
    val listState = rememberLazyListState()
    var filter by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    fun dismissSearch() {
        filter = ""
        searchOpen = false
        focusManager.clearFocus()
        keyboard?.hide()
    }
    BackHandler(enabled = isDrawerOpen) {
        if (searchOpen) dismissSearch() else onCloseDrawer()
    }
    LaunchedEffect(isDrawerOpen, searchOpen) {
        if (isDrawerOpen && searchOpen) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    val activeRecord = history.firstOrNull { it.sessionId == activeSessionId }
        ?: RunHistoryItem(activeSessionId, activeGoal, "", 0, 0, 0.0, activeSessionId, activeTitle)
    val searchableHistory = remember(history, activeSessionVisible, activeSessionId) {
        history.filterNot { activeSessionVisible && it.sessionId == activeSessionId }
    }
    val visibleHistory = remember(searchableHistory, filter) { drawerChatsForDisplay(searchableHistory, filter) }
    val activeVisible = activeSessionVisible && !activeRecord.archived &&
        drawerChatMatches(activeTitle ?: activeRecord.title, activeGoal, filter)
    val rows = buildList {
        if (activeVisible) add(activeRecord)
        addAll(visibleHistory)
    }
    val pinnedRows = rows.filter { it.pinned }
    val currentRows = rows.filterNot { it.pinned }
    val screenWidth = configuration.screenWidthDp.dp
    ChatConversationActions(actionsEnabled, onConversationAction) { action ->
        ModalDrawerSheet(
            modifier = Modifier.fillMaxHeight().width((screenWidth * 0.86f).coerceAtMost(420.dp)).imePadding(),
            drawerContainerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
                    .testTag("drawer-header"), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.app_name), Modifier.weight(1f).testTag("drawer-header-title"),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { if (searchOpen) dismissSearch() else searchOpen = true },
                        modifier = Modifier.size(48.dp).testTag("drawer-search-toggle")) {
                        Icon(if (searchOpen) LucideIcons.X else LucideIcons.Search,
                            contentDescription = stringResource(if (searchOpen) R.string.drawer_cancel_search else R.string.drawer_search_hint),
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                }
                if (searchOpen) {
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                        shape = RoundedCornerShape(13.dp)) {
                        Row(Modifier.heightIn(min = 48.dp).padding(start = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            val searchDescription = stringResource(R.string.drawer_search_hint)
                            Box(Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                                if (filter.isEmpty()) Text(searchDescription, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                BasicTextField(value = filter, onValueChange = { filter = it }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("drawer-search")
                                        .semantics { contentDescription = searchDescription },
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); keyboard?.hide() }))
                            }
                            if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }, modifier = Modifier.size(48.dp)) {
                                Icon(LucideIcons.X, contentDescription = stringResource(R.string.drawer_clear_search), modifier = Modifier.size(18.dp))
                            } else Spacer(Modifier.width(13.dp))
                        }
                    }
                }
                // Menus and chat groups scroll together; the title/search and Settings remain reachable.
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().testTag("conversation-drawer-scroll"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    item(key = "menu-new-chat") {
                        DrawerMenuRow(stringResource(R.string.drawer_new_chat), LucideIcons.MessageCirclePlus,
                            Modifier.testTag("drawer-new-chat"), onNewConversation)
                    }
                    item(key = "menu-bots") {
                        DrawerMenuRow(stringResource(R.string.drawer_bots), LucideIcons.Bot,
                            Modifier.testTag("drawer-open-bots"), onOpenBots)
                    }
                    item(key = "menu-scheduled-tasks") {
                        DrawerMenuRow(stringResource(R.string.drawer_scheduled_tasks), LucideIcons.Calendar,
                            Modifier.testTag("drawer-open-scheduled-tasks"), onOpenScheduledTasks)
                    }
                    if (pinnedRows.isNotEmpty()) {
                        item(key = "pinned-heading") {
                            DrawerGroupHeading(stringResource(R.string.drawer_pinned_section), "drawer-pinned-heading")
                        }
                        items(pinnedRows, key = { "pinned-" + it.sessionId }) { record ->
                            val active = activeVisible && record.sessionId == activeSessionId
                            ConversationSessionRow(drawerConversationTitle(if (active) activeTitle else record.title,
                                if (active) activeGoal else record.goal, stringResource(R.string.drawer_chat_fallback)),
                                selected = isChatRoute && if (active) selectedHistoryId == null else selectedHistoryId == record.id,
                                active = active, onClick = { if (active) onResumeActive() else onOpenHistory(record.id) },
                                pinned = record.pinned, archived = false, actionsEnabled = actionsEnabled,
                                managed = isManagedSystemConversation(record.sessionId), onAction = { action(record, it) })
                        }
                    }
                    item(key = "current-heading") {
                        DrawerGroupHeading(stringResource(R.string.drawer_current_chats), "drawer-current-heading")
                    }
                    if (currentRows.isEmpty()) item(key = "history-empty") {
                        Text(stringResource(if (filter.isNotBlank()) R.string.drawer_search_no_results else R.string.drawer_no_chats),
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    items(currentRows, key = { "current-" + it.sessionId }) { record ->
                        val active = activeVisible && record.sessionId == activeSessionId
                        ConversationSessionRow(drawerConversationTitle(if (active) activeTitle else record.title,
                            if (active) activeGoal else record.goal, stringResource(R.string.drawer_chat_fallback)),
                            selected = isChatRoute && if (active) selectedHistoryId == null else selectedHistoryId == record.id,
                            active = active, onClick = { if (active) onResumeActive() else onOpenHistory(record.id) },
                            pinned = record.pinned, archived = false, actionsEnabled = actionsEnabled,
                            managed = isManagedSystemConversation(record.sessionId), onAction = { action(record, it) })
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
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.drawer_local_profile), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp).testTag("drawer-open-settings")) {
                            Icon(LucideIcons.Settings, contentDescription = stringResource(R.string.drawer_settings), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerGroupHeading(title: String, tag: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp).testTag(tag).semantics { heading() }) {
        JarvysSectionLabel(title)
    }
}

@Composable
private fun DrawerMenuRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(23.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}
