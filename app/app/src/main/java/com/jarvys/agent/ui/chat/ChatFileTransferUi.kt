package com.jarvys.agent.ui.chat

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvys.agent.DownloadIntents
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R
import com.jarvys.agent.ScrollableDialogContent

@Composable
internal fun ChatFileTransferHost(transfers: ChatFileTransfers, startIntent: (Intent) -> Unit) {
    val permissionRequest by transfers.permissionRequest.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), transfers::permissionResult)
    LaunchedEffect(permissionRequest) {
        if (permissionRequest != null && transfers.claimPermissionRequest()) {
            runCatching { permission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) }
                .onFailure { transfers.permissionResult(false) }
        }
    }
    val launch by transfers.launch.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(launch) {
        launch?.let { intent ->
            transfers.consumeLaunch()
            runCatching { startIntent(intent) }.onFailure {
                android.widget.Toast.makeText(context, R.string.chat_download_open_failed, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    val notice by transfers.notice.collectAsState()
    notice?.let { current ->
        val success = current.saved
        AlertDialog(
            onDismissRequest = transfers::dismissNotice,
            modifier = Modifier.testTag("chat-download-result"),
            title = { Text(stringResource(when {
                success -> R.string.chat_download_complete
                current.failure == ChatFileTransfer.Failure.OPEN -> R.string.chat_download_open_failed_title
                else -> R.string.chat_download_failed
            })) },
            text = { ScrollableDialogContent {
                Text(if (success) context.getString(R.string.chat_download_location, current.result!!.displayName) else
                    context.getString(when (current.failure) {
                        ChatFileTransfer.Failure.PERMISSION -> R.string.chat_download_permission_denied
                        ChatFileTransfer.Failure.UNAVAILABLE -> R.string.chat_attachment_unavailable
                        ChatFileTransfer.Failure.OPEN -> R.string.chat_download_open_failed
                        else -> R.string.chat_download_retry
                    }))
            } },
            confirmButton = {
                if (success) TextButton(onClick = {
                    transfers.dismissNotice()
                    runCatching { startIntent(DownloadIntents.open(current.result)) }
                        .onFailure { transfers.openFailed(current.request) }
                }, modifier = Modifier.testTag("chat-download-open")) { Text(stringResource(R.string.chat_download_open)) }
                else if (current.failure == ChatFileTransfer.Failure.PERMISSION) TextButton(onClick = {
                    transfers.dismissNotice(); transfers.share(current.request)
                }, modifier = Modifier.testTag("chat-download-share-instead")) { Text(stringResource(R.string.image_action_share)) }
                else TextButton(onClick = transfers::dismissNotice) { Text(stringResource(R.string.image_viewer_close)) }
            },
            dismissButton = {
                if (success || current.failure == ChatFileTransfer.Failure.PERMISSION) TextButton(onClick = transfers::dismissNotice) {
                    Text(stringResource(R.string.image_viewer_close))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatFileButtons(request: ChatFileRequest, modifier: Modifier = Modifier, tagPrefix: String = "chat-file",
    onPreview: (() -> Unit)? = null) {
    val actions = LocalChatFileActions.current
    val transfer = actions.transfers[request.key]
    val busy = transfer?.busy == true || transfer?.waitingForPermission == true
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (onPreview != null) OutlinedButton(onClick = onPreview,
            modifier = Modifier.testTag("$tagPrefix-preview-${request.artifactId}")) {
            Text(stringResource(R.string.chat_view_in_jarvys))
        }
        OutlinedButton(onClick = { actions.download(request) }, enabled = !busy,
            modifier = Modifier.testTag("$tagPrefix-download-${request.artifactId}")) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(LucideIcons.Download, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(if (busy) R.string.chat_download_saving else R.string.chat_download_action))
        }
        if (busy) TextButton(onClick = { actions.cancel(request) },
            modifier = Modifier.testTag("$tagPrefix-cancel-${request.artifactId}")) { Text(stringResource(R.string.settings_cancel)) }
        else TextButton(onClick = { actions.share(request) }, modifier = Modifier.testTag("$tagPrefix-share-${request.artifactId}")) {
            Text(stringResource(R.string.image_action_share))
        }
        if (transfer?.saved == true) TextButton(onClick = { actions.open(request) },
            modifier = Modifier.testTag("$tagPrefix-open-${request.artifactId}")) { Text(stringResource(R.string.chat_download_open)) }
    }
}
