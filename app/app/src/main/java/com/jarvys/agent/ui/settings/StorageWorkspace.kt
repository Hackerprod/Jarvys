package com.jarvys.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvys.agent.R
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.RetentionSummary
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings > Storage: how much tool output the conversation keeps, and the notice when the device runs out of room. */
@Composable
internal fun StorageWorkspace() {
    val context = LocalContext.current.applicationContext
    var summary by remember { mutableStateOf<RetentionSummary?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            summary = withContext(Dispatchers.IO) {
                ChatDatabaseBridge.getInstance(context).retentionSummarySync(WorkspaceIdentity.CENTRAL_SESSION)
            }
        } catch (_: Exception) {
            failed = true
        }
    }
    StorageContent(summary, failed) { bytes -> android.text.format.Formatter.formatShortFileSize(context, bytes) }
}

@Composable
internal fun StorageContent(summary: RetentionSummary?, failed: Boolean, formatBytes: (Long) -> String) {
    Text(stringResource(R.string.settings_storage_description), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    when {
        failed -> Text(stringResource(R.string.settings_storage_error), color = MaterialTheme.colorScheme.error)
        summary == null -> Text(stringResource(R.string.settings_storage_loading))
        else -> {
            if (summary.storageWarning) {
                Card(Modifier.fillMaxWidth().testTag("storage-low-warning"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.settings_storage_low_title), style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(stringResource(R.string.settings_storage_low_body), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            StorageRow(stringResource(R.string.settings_storage_state_retained), summary.retained.toString(), "storage-retained")
            StorageRow(stringResource(R.string.settings_storage_state_bounded), summary.bounded.toString(), "storage-bounded")
            StorageRow(stringResource(R.string.settings_storage_state_expelled), summary.expelled.toString(), "storage-expelled")
            StorageRow(stringResource(R.string.settings_storage_state_unavailable), summary.unavailable.toString(), "storage-unavailable")
            StorageRow(stringResource(R.string.settings_storage_protected), summary.protectedCount.toString(), "storage-protected")
            StorageRow(stringResource(R.string.settings_storage_used), formatBytes(summary.retainedBytes), "storage-used")
        }
    }
}

@Composable
private fun StorageRow(label: String, value: String, tag: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(tag), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}
