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
) {
    val configuration = LocalConfiguration.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf("") }
    val searchableHistory = remember(history, activeSessionVisible, activeSessionId) {
        history.filterNot { activeSessionVisible && it.sessionId == activeSessionId }
    }
    val visibleHistory = remember(searchableHistory, filter) { drawerChatsForDisplay(searchableHistory, filter) }
    val activeVisible = activeSessionVisible &&
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
                JarvysSectionLabel(stringResource(R.string.drawer_history_section))
            }

            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                if (activeVisible) item(key = "active-session") {
                    SessionRow(drawerConversationTitle(activeTitle, activeGoal,
                        stringResource(R.string.drawer_chat_fallback)),
                        subtitle = stringResource(R.string.drawer_current_conversation),
                        selected = isChatRoute && selectedHistoryId == null,
                        active = true,
                        onClick = onResumeActive)
                }
                if (visibleHistory.isEmpty() && !activeVisible) item(key = "history-empty") {
                    Text(stringResource(if (filter.isBlank()) R.string.drawer_no_chats else R.string.drawer_search_no_results),
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                items(visibleHistory, key = { it.id }) { record ->
                    SessionRow(drawerConversationTitle(record.title, record.goal,
                        stringResource(R.string.drawer_chat_fallback)),
                        subtitle = stringResource(R.string.drawer_history_summary, record.outcome, record.steps, record.turns),
                        selected = isChatRoute && selectedHistoryId == record.id,
                        active = false,
                        onClick = { onOpenHistory(record.id) })
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
}

@Composable
private fun SessionRow(title: String, subtitle: String, selected: Boolean, active: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable(onClick = onClick)
        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
            else Color.Transparent, RoundedCornerShape(13.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.width(3.dp).height(36.dp).background(
            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (active) Icon(LucideIcons.Circle, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
    }
}
