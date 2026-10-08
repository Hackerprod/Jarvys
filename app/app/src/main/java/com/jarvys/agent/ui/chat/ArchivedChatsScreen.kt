package com.jarvys.agent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvys.agent.ConversationAction
import com.jarvys.agent.R
import com.jarvys.agent.RunHistoryItem
import com.jarvys.agent.drawerChatsForDisplay
import com.jarvys.agent.drawerConversationTitle
import com.jarvys.agent.isManagedSystemConversation

/** Settings destination; history remains the source of truth after every conversation action. */
@Composable
fun ArchivedChatsScreen(
    history: List<RunHistoryItem>,
    actionsEnabled: Boolean,
    onOpenHistory: (String) -> Unit,
    onConversationAction: (String, ConversationAction, String?) -> Unit,
) {
    val archivedChats = drawerChatsForDisplay(history, query = "", archived = true)
    ChatConversationActions(actionsEnabled, onConversationAction) { action ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("archived-chats-list"),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "archive-help") {
                Text(
                    stringResource(R.string.drawer_archive_help),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (archivedChats.isEmpty()) {
                item(key = "archive-empty") {
                    Text(
                        stringResource(R.string.drawer_no_archived_chats),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)
                            .testTag("archived-chats-empty"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(archivedChats, key = { "archive-" + it.sessionId }) { record ->
                ConversationSessionRow(
                    title = drawerConversationTitle(record.title, record.goal,
                        stringResource(R.string.drawer_chat_fallback)),
                    selected = false,
                    active = false,
                    onClick = { onOpenHistory(record.id) },
                    pinned = record.pinned,
                    archived = true,
                    actionsEnabled = actionsEnabled,
                    managed = isManagedSystemConversation(record.sessionId),
                    onAction = { action(record, it) },
                )
            }
        }
    }
}
