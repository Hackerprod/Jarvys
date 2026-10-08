package com.jarvys.agent.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.AttachmentStore
import com.jarvys.agent.ChatAttachment
import com.jarvys.agent.DeliveredArtifactStore
import com.jarvys.agent.HtmlPreviewDescriptor
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
            key(sessionId, pending.id) {
                val isImage = pending.kind == ChatAttachment.Kind.IMAGE
                // Recovered v28: images are compact previews, never filename/size cards.
                Surface(Modifier.width(if (isImage) 136.dp else 168.dp).testTag("pending-attachment-${pending.id}"),
                    shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    if (isImage && pending.attachment != null && !pending.copying && pending.error == null) {
                        Box(Modifier.fillMaxWidth().height(96.dp)) {
                            AttachmentImage(pending.attachment, sessionId, Modifier.fillMaxSize(),
                                interactive = false, tagPrefix = "pending")
                            RemovePendingAttachment(pending, onRemove, Modifier.align(Alignment.TopEnd).padding(4.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), CircleShape))
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                if (!isImage) {
                                    Icon(LucideIcons.FileText, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(pending.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                }
                                when {
                                    pending.copying -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Text(stringResource(R.string.chat_attachment_copying), Modifier.testTag("chat-attachment-copying-${pending.id}"),
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                    pending.error != null -> Text(pending.error.ifBlank { stringResource(R.string.chat_attachment_copy_failed) },
                                        Modifier.testTag("chat-attachment-error-${pending.id}"), color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmall)
                                    !isImage -> Text(Formatter.formatShortFileSize(LocalContext.current, pending.sizeBytes),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            RemovePendingAttachment(pending, onRemove)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemovePendingAttachment(pending: PendingChatAttachment, onRemove: (String) -> Unit,
    modifier: Modifier = Modifier) {
    IconButton(onClick = { onRemove(pending.id) }, modifier = modifier.size(48.dp).testTag("chat-attachment-remove-${pending.id}")) {
        Icon(LucideIcons.X, stringResource(R.string.chat_attachment_remove, pending.displayName), Modifier.size(18.dp))
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
private fun AttachmentFileCard(attachment: ChatAttachment, sessionId: String, delivered: Boolean = false,
    onOpenPreview: (String) -> Unit = {}) {
    val context = LocalContext.current
    val request = remember(sessionId, attachment, delivered) { ChatFileRequest.attachment(sessionId, attachment, delivered) }
    val available by produceState<Boolean?>(null, request) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { request.requireOwnership(context); request.resolve(context).isFile }.getOrDefault(false) }
    }
    val preview by produceState<HtmlPreviewDescriptor?>(null, request) {
        value = null
        if (delivered && attachment.mimeType == "text/html") value = withContext(Dispatchers.IO) {
            runCatching {
                request.requireOwnership(context)
                DeliveredArtifactStore(context).previewMetadataForAttachment(sessionId, attachment)
            }.getOrNull()
        }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("chat-attachment-file-${attachment.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(LucideIcons.FileText, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(attachment.name, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(Formatter.formatShortFileSize(context, attachment.sizeBytes) + " • " + attachment.mimeType,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (available == false) UnavailableAttachment(attachment.id)
            if (available == true) {
                preview?.let { descriptor ->
                    HtmlArtifactThumbnail(request, descriptor) { onOpenPreview(descriptor.token) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ChatFileIconButtons(request, Modifier.align(Alignment.CenterHorizontally),
                    tagPrefix = if (delivered) "delivered-file" else "chat-file")
            }
        }
    }
}

/** Files delivered by the agent are first-class transcript entries, not Markdown paths. */
@Composable
internal fun DeliveredArtifactEventCard(event: AgentRunUiEvent, sessionId: String, onOpenPreview: (String) -> Unit = {}) {
    val attachment = event.deliveredArtifact
    Column(Modifier.fillMaxWidth().testTag("delivered-file-${event.id}")) {
        if (attachment == null) UnavailableAttachment(event.id.toString())
        else if (attachment.isImage) AttachmentImage(attachment, sessionId,
            Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp).clip(RoundedCornerShape(14.dp)),
            tagPrefix = "delivered", delivered = true)
        else AttachmentFileCard(attachment, sessionId, delivered = true, onOpenPreview = onOpenPreview)
    }
}

@Composable
private fun UnavailableAttachment(id: String) {
    Text(stringResource(R.string.chat_attachment_unavailable), color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("attachment-unavailable-$id"))
}

@Composable
private fun AttachmentImage(attachment: ChatAttachment, sessionId: String, modifier: Modifier,
    interactive: Boolean = true, tagPrefix: String = "chat", delivered: Boolean = false) {
    val context = LocalContext.current
    val request = remember(sessionId, attachment, delivered) { ChatFileRequest.attachment(sessionId, attachment, delivered) }
    var loaded by remember(request) { mutableStateOf(false) }
    var sourceAvailable by remember(request) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, request) {
        val file = withContext(Dispatchers.IO) { runCatching { request.resolve(context) }.getOrNull() }
        value = file?.let { decodeChatAttachmentPreview(it, 640, 640) }
        sourceAvailable = file != null
        loaded = true
    }
    var showViewer by remember(sessionId, attachment.id) { mutableStateOf(false) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)
        .testTag("$tagPrefix-attachment-image-${attachment.id}")
        .then(if (interactive && sourceAvailable) Modifier.clickable { showViewer = true } else Modifier),
        contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.chat_attachment_open_image, attachment.name),
            Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: if (loaded) {
                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (sourceAvailable) Text(stringResource(R.string.chat_file_preview_unavailable))
                    else UnavailableAttachment(attachment.id)
                    if (interactive && sourceAvailable) ChatFileButtons(request, tagPrefix = "chat-preview")
                }
            } else CircularProgressIndicator(Modifier.size(24.dp))
    }
    if (showViewer) ChatAttachmentViewer(request) { showViewer = false }
}

@Composable
private fun ChatAttachmentViewer(request: ChatFileRequest, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val attachment = requireNotNull(request.attachment)
    val sessionId = request.sessionId
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("chat-attachment-viewer-${attachment.id}"), color = Color.Black) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("chat-attachment-viewer-close")) { Icon(LucideIcons.X,
                        stringResource(R.string.chat_attachment_close_preview), tint = Color.White) }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val density = LocalDensity.current
                    val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
                    val height = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
                    var loaded by remember(sessionId, attachment) { mutableStateOf(false) }
                    val bitmap by produceState<Bitmap?>(null, sessionId, attachment, width to height) {
                        value = resolveAttachmentPreview(context, request, width, height)
                        loaded = true
                    }
                    val image = bitmap
                    if (image != null) {
                        val geometry = remember(width, height, image) {
                            GeneratedImageGeometry(width.toFloat(), height.toFloat(), image.width.toFloat(), image.height.toFloat())
                        }
                        var transform by remember(attachment.id, geometry) { mutableStateOf(GeneratedImageTransform()) }
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp)).testTag("chat-attachment-viewport")
                            .pointerInput(geometry) { detectTransformGestures { centroid, pan, zoom, _ ->
                                transform = geometry.gesture(transform, zoom, pan.x, pan.y, centroid.x, centroid.y)
                            } }, contentAlignment = Alignment.Center) {
                            Image(image.asImageBitmap(), attachment.name, Modifier.fillMaxSize().graphicsLayer {
                                scaleX = transform.scale; scaleY = transform.scale
                                translationX = transform.panX; translationY = transform.panY
                            }, contentScale = ContentScale.Fit)
                        }
                    } else if (loaded) Text(stringResource(R.string.chat_file_preview_unavailable), color = Color.White) else Text(
                        stringResource(R.string.chat_attachment_preview_loading), color = Color.White)
                }
                ChatFileButtons(request, Modifier.padding(12.dp), tagPrefix = "chat-viewer")
            }
        }
    }
}

private suspend fun resolveAttachmentPreview(context: android.content.Context, request: ChatFileRequest,
    width: Int, height: Int): Bitmap? = withContext(Dispatchers.IO) {
    val file = runCatching { request.resolve(context) }.getOrNull() ?: return@withContext null
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
