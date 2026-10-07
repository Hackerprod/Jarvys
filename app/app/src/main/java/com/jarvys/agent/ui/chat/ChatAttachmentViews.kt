package com.jarvys.agent.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvys.agent.AttachmentStore
import com.jarvys.agent.ChatAttachment
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PendingChatAttachment(val id: String, val displayName: String,
    val mimeType: String = "application/octet-stream", val sizeBytes: Long = 0,
    val kind: ChatAttachment.Kind = ChatAttachment.Kind.FILE, val attachment: ChatAttachment? = null,
    val copying: Boolean = false, val error: String? = null)

internal fun canSendWithAttachments(goal: String, attachments: List<PendingChatAttachment>): Boolean =
    attachments.none { it.copying || it.error != null || it.attachment == null } &&
        (goal.isNotBlank() || attachments.any { it.attachment != null })

@Composable
fun PendingChatAttachments(attachments: List<PendingChatAttachment>, sessionId: String,
    onRemove: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { pending ->
            Surface(Modifier.width(150.dp).testTag("pending-attachment-${pending.id}"),
                shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth().height(88.dp)) {
                        if (pending.kind == ChatAttachment.Kind.IMAGE && pending.attachment != null) {
                            AttachmentImage(pending.attachment, sessionId, Modifier.fillMaxSize(), tagPrefix = "pending")
                        } else Icon(LucideIcons.FileText, null, Modifier.align(Alignment.Center).size(28.dp))
                        IconButton(onClick = { onRemove(pending.id) }, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
                            Icon(LucideIcons.X, stringResource(R.string.chat_attachment_remove, pending.displayName), Modifier.size(18.dp))
                        }
                    }
                    Text(pending.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    when {
                        pending.copying -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.chat_attachment_copying), Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelSmall)
                        }
                        pending.error != null -> Text(pending.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        else -> Text(Formatter.formatShortFileSize(LocalContext.current, pending.sizeBytes), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun UserChatAttachments(attachments: List<ChatAttachment>, sessionId: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { attachment ->
            if (attachment.isImage) AttachmentImage(attachment, sessionId,
                Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 280.dp).clip(RoundedCornerShape(14.dp)))
            else AttachmentFileCard(attachment, sessionId)
        }
    }
}

@Composable
private fun AttachmentFileCard(attachment: ChatAttachment, sessionId: String) {
    val context = LocalContext.current
    val store = remember(context) { AttachmentStore(context) }
    val available by produceState<Boolean?>(null, sessionId, attachment) {
        value = withContext(Dispatchers.IO) { runCatching { store.resolve(sessionId, attachment).isFile }.getOrDefault(false) }
    }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("chat-attachment-file-${attachment.id}")) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(LucideIcons.FileText, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(attachment.name, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text(Formatter.formatShortFileSize(context, attachment.sizeBytes), style = MaterialTheme.typography.labelSmall)
                Text(attachment.mimeType, maxLines = 2, style = MaterialTheme.typography.labelSmall)
                if (available == false) UnavailableAttachment(attachment.id)
            }
        }
    }
}

@Composable
private fun UnavailableAttachment(id: String) {
    Text(stringResource(R.string.chat_attachment_unavailable), color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("attachment-unavailable-$id"))
}

@Composable
private fun AttachmentImage(attachment: ChatAttachment, sessionId: String, modifier: Modifier,
    interactive: Boolean = true, tagPrefix: String = "chat") {
    val context = LocalContext.current
    val store = remember(context) { AttachmentStore(context) }
    var loaded by remember(sessionId, attachment) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, sessionId, attachment) {
        value = resolveAttachmentPreview(store, sessionId, attachment, 640, 640)
        loaded = true
    }
    var showViewer by remember(sessionId, attachment.id) { mutableStateOf(false) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)
        .testTag("$tagPrefix-attachment-image-${attachment.id}")
        .then(if (interactive && bitmap != null) Modifier.clickable { showViewer = true } else Modifier),
        contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.chat_attachment_open_image, attachment.name),
            Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: if (loaded) UnavailableAttachment(attachment.id) else CircularProgressIndicator(Modifier.size(24.dp))
    }
    if (showViewer) ChatAttachmentViewer(attachment, sessionId) { showViewer = false }
}

@Composable
private fun ChatAttachmentViewer(attachment: ChatAttachment, sessionId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { AttachmentStore(context) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onDismiss) { Icon(LucideIcons.X,
                        stringResource(R.string.chat_attachment_close_preview), tint = Color.White) }
                }
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val density = LocalDensity.current
                    val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
                    val height = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
                    var loaded by remember(sessionId, attachment) { mutableStateOf(false) }
                    val bitmap by produceState<Bitmap?>(null, sessionId, attachment, width to height) {
                        value = resolveAttachmentPreview(store, sessionId, attachment, width, height)
                        loaded = true
                    }
                    val image = bitmap
                    if (image != null) {
                        val geometry = remember(width, height, image) {
                            GeneratedImageGeometry(width.toFloat(), height.toFloat(), image.width.toFloat(), image.height.toFloat())
                        }
                        var transform by remember(attachment.id, geometry) { mutableStateOf(GeneratedImageTransform()) }
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp))
                            .pointerInput(geometry) { detectTransformGestures { centroid, pan, zoom, _ ->
                                transform = geometry.gesture(transform, zoom, pan.x, pan.y, centroid.x, centroid.y)
                            } }, contentAlignment = Alignment.Center) {
                            Image(image.asImageBitmap(), attachment.name, Modifier.fillMaxSize().graphicsLayer {
                                scaleX = transform.scale; scaleY = transform.scale
                                translationX = transform.panX; translationY = transform.panY
                            }, contentScale = ContentScale.Fit)
                        }
                    } else if (loaded) UnavailableAttachment(attachment.id) else Text(
                        stringResource(R.string.chat_attachment_preview_loading), color = Color.White)
                }
            }
        }
    }
}

private suspend fun resolveAttachmentPreview(store: AttachmentStore, sessionId: String,
    attachment: ChatAttachment, width: Int, height: Int): Bitmap? = withContext(Dispatchers.IO) {
    val file = runCatching { store.resolve(sessionId, attachment) }.getOrNull() ?: return@withContext null
    decodeChatAttachmentPreview(file, width, height)
}

internal suspend fun decodeChatAttachmentPreview(file: File, targetWidth: Int, targetHeight: Int,
    onDecodeThread: (String) -> Unit = {}): Bitmap? = withContext(Dispatchers.IO) {
    onDecodeThread(Thread.currentThread().name)
    if (!file.isFile) return@withContext null
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val orientation = runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val rotated = orientation in 5..8
        val width = if (rotated) bounds.outHeight else bounds.outWidth
        val height = if (rotated) bounds.outWidth else bounds.outHeight
        var sample = 1
        while (width / sample > targetWidth.coerceAtLeast(1).toLong() * 2 || height / sample > targetHeight.coerceAtLeast(1).toLong() * 2) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return@withContext null
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }
                8 -> setRotate(270f)
            }
        }
        if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
}
