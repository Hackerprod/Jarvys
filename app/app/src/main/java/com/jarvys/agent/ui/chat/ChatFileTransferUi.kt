package com.jarvys.agent.ui.chat

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
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


/** Compact file-card actions retain the existing transfer state machine and native destinations. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatFileIconButtons(request: ChatFileRequest, modifier: Modifier = Modifier,
    tagPrefix: String = "chat-file", overlay: Boolean = false) {
    val actions = LocalChatFileActions.current
    val transfer = actions.transfers[request.key]
    val busy = transfer?.busy == true || transfer?.waitingForPermission == true
    val colors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.primary,
        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
        disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
    )
    val saving = stringResource(R.string.chat_download_saving)
    val download = stringResource(R.string.chat_file_action_named,
        stringResource(R.string.chat_download_action), request.displayName)
    val share = stringResource(R.string.chat_file_action_named,
        stringResource(R.string.image_action_share), request.displayName)
    val cancel = stringResource(R.string.chat_file_action_named,
        stringResource(R.string.settings_cancel), request.displayName)
    val open = stringResource(R.string.chat_file_action_named,
        stringResource(R.string.chat_download_open), request.displayName)
    if (overlay) {
        FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThumbnailActionButton("$tagPrefix-download-${request.artifactId}",
                "$tagPrefix-download-visual-${request.artifactId}", download, !busy,
                if (busy) saving else null, { actions.download(request) }) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp).clearAndSetSemantics {}, strokeWidth = 2.dp)
                else Icon(LucideIcons.Download, null, Modifier.size(22.dp))
            }
            if (busy) ThumbnailActionButton("$tagPrefix-cancel-${request.artifactId}",
                "$tagPrefix-cancel-visual-${request.artifactId}", cancel, onClick = { actions.cancel(request) }) {
                Icon(LucideIcons.X, null, Modifier.size(22.dp))
            } else ThumbnailActionButton("$tagPrefix-share-${request.artifactId}",
                "$tagPrefix-share-visual-${request.artifactId}", share, onClick = { actions.share(request) }) {
                Icon(LucideIcons.Share, null, Modifier.size(22.dp))
            }
            if (transfer?.saved == true) ThumbnailActionButton("$tagPrefix-open-${request.artifactId}",
                "$tagPrefix-open-visual-${request.artifactId}", open, onClick = { actions.open(request) }) {
                Icon(LucideIcons.Eye, null, Modifier.size(22.dp))
            }
        }
        return
    }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(colors = colors, onClick = { actions.download(request) }, enabled = !busy,
            modifier = Modifier.size(48.dp).testTag("$tagPrefix-download-${request.artifactId}")
                .semantics { contentDescription = download; if (busy) stateDescription = saving }) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp).clearAndSetSemantics {}, strokeWidth = 2.dp)
            else Icon(LucideIcons.Download, null, Modifier.size(22.dp))
        }
        if (busy) FilledTonalIconButton(colors = colors, onClick = { actions.cancel(request) },
            modifier = Modifier.size(48.dp).testTag("$tagPrefix-cancel-${request.artifactId}")) {
            Icon(LucideIcons.X, cancel, Modifier.size(22.dp))
        } else FilledTonalIconButton(colors = colors, onClick = { actions.share(request) },
            modifier = Modifier.size(48.dp).testTag("$tagPrefix-share-${request.artifactId}")) {
            Icon(LucideIcons.Share, share, Modifier.size(22.dp))
        }
        if (transfer?.saved == true) FilledTonalIconButton(colors = colors, onClick = { actions.open(request) },
            modifier = Modifier.size(48.dp).testTag("$tagPrefix-open-${request.artifactId}")) {
            Icon(LucideIcons.Eye, open, Modifier.size(22.dp))
        }
    }
}

/** Smaller paint bounds never shrink the action's explicit 48 dp interaction area. */
@Composable
private fun ThumbnailActionButton(tag: String, visualTag: String, label: String,
    enabled: Boolean = true, state: String? = null, onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.size(48.dp).testTag(tag)
            .semantics { contentDescription = label; if (state != null) stateDescription = state }
            .pointerInput(enabled) {
                // A disabled control still owns its footprint above the sibling preview. Leave
                // movement unconsumed so the transcript can scroll; absorb a completed tap.
                if (!enabled) awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    waitForUpOrCancellation()?.consume()
                }
            }) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f), CircleShape)
            .testTag(visualTag), contentAlignment = Alignment.Center) { content() }
    }
}
