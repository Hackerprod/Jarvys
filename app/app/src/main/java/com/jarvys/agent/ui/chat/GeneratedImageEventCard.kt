package com.jarvys.agent.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.widget.ImageView
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.GeneratedImageStore
import com.jarvys.agent.JarvysMotion
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R
import com.jarvys.agent.ui.motion.LocalReducedMotion
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun GeneratedImageEventCard(
    event: AgentRunUiEvent,
    sessionId: String,
    onSave: (AgentRunUiEvent) -> Unit,
    onShare: (AgentRunUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reducedMotion = LocalReducedMotion.current
    val store = remember(context) { GeneratedImageStore(context) }
    val file = remember(sessionId, event.generatedImagePath) {
        event.generatedImagePath?.let { path -> runCatching { store.resolve(sessionId, path) }.getOrNull() }
    }
    val fileStamp = file?.let(::generatedImageFileStamp)
    var viewerOpen by remember(event.id) { mutableStateOf(false) }
    val failed = event.generatedImageStatus != "COMPLETED"
    val description = event.generatedImagePrompt ?: event.text

    Column(modifier.fillMaxWidth().testTag("generated-image-${event.id}")) {
        if (failed) {
            Text(event.generatedImageError.orEmpty().ifBlank { stringResource(R.string.image_error_incomplete, "FAILED", "Image generation failed", "unknown") },
                Modifier.testTag("generated-image-error-${event.id}"),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        } else if (file == null) {
            Text(stringResource(R.string.image_missing_file),
                Modifier.testTag("generated-image-missing-${event.id}"),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        } else {
            var imageAspect by remember(fileStamp, event.generatedImageSize) {
                mutableFloatStateOf(imageAspectFromSize(event.generatedImageSize))
            }
            LaunchedEffect(fileStamp) {
                readGeneratedImageBounds(file)?.let { bounds -> imageAspect = bounds.width.toFloat() / bounds.height }
            }
            BoxWithConstraints(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { viewerOpen = true }
                    .semantics {
                        contentDescription = context.getString(R.string.image_open_accessibility, description)
                    }.testTag("generated-image-open-${event.id}"),
                contentAlignment = Alignment.Center,
            ) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                // The transcript is vertically scrollable, so retain the source image's ratio rather than letterboxing it.
                val frameHeight = maxWidth / imageAspect.coerceAtLeast(0.01f)
                val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
                val heightPx = with(density) { frameHeight.roundToPx() }.coerceAtLeast(1)
                var bitmap by remember(fileStamp, widthPx, heightPx) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(fileStamp, widthPx, heightPx) {
                    bitmap = decodeGeneratedImage(file, widthPx, heightPx)
                }
                val image = bitmap
                Box(Modifier.fillMaxWidth().height(frameHeight)
                    .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    if (image == null) {
                        Box(Modifier.fillMaxSize().testTag("generated-image-loading-${event.id}"))
                    } else {
                        AndroidView(
                            factory = { imageContext -> ImageView(imageContext).apply {
                                adjustViewBounds = false
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                contentDescription = context.getString(R.string.image_open_accessibility, description)
                                setImageBitmap(image)
                            } },
                            modifier = Modifier.fillMaxSize().testTag("generated-image-thumbnail-${event.id}"),
                            update = { view ->
                                view.setImageBitmap(image)
                                view.contentDescription = context.getString(R.string.image_open_accessibility, description)
                            },
                        )
                    }
                }
            }
        }
    }
    if (viewerOpen && file != null) {
        GeneratedImageViewer(event, file, description, reducedMotion, onDismiss = { viewerOpen = false },
            onSave = onSave, onShare = onShare)
    }
}

@Composable
private fun GeneratedImageViewer(
    event: AgentRunUiEvent,
    file: File,
    prompt: String,
    reducedMotion: Boolean,
    onDismiss: () -> Unit,
    onSave: (AgentRunUiEvent) -> Unit,
    onShare: (AgentRunUiEvent) -> Unit,
) {
    val context = LocalContext.current
    var transform by remember(event.id) { mutableStateOf(GeneratedImageTransform()) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f).testTag("generated-image-viewer-${event.id}"),
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            val motion = if (reducedMotion) Modifier else Modifier.animateContentSize(JarvysMotion.feedback())
            Column(Modifier.fillMaxSize().then(motion).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("generated-image-close")) {
                        Text(stringResource(R.string.image_viewer_close))
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
                    val heightPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
                    var bitmap by remember(file, widthPx, heightPx) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(file, widthPx, heightPx) { bitmap = decodeGeneratedImage(file, widthPx, heightPx) }
                    bitmap?.let { image ->
                        val geometry = remember(widthPx, heightPx, image) { GeneratedImageGeometry(
                            widthPx.toFloat(), heightPx.toFloat(), image.width.toFloat(), image.height.toFloat()) }
                        val displayed = geometry.clamp(transform)
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp))
                            .pointerInput(event.id, geometry) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    transform = geometry.gesture(transform, zoom, pan.x, pan.y, centroid.x, centroid.y)
                                }
                            }) {
                            AndroidView(
                                factory = { imageContext -> ImageView(imageContext).apply { scaleType = ImageView.ScaleType.MATRIX } },
                                modifier = Modifier.fillMaxSize().semantics {
                                    contentDescription = context.getString(R.string.image_open_accessibility, prompt)
                                }.testTag("generated-image-viewer-image"),
                                update = { view ->
                                    view.setImageBitmap(image)
                                    view.contentDescription = context.getString(R.string.image_open_accessibility, prompt)
                                    val scale = geometry.fittedWidth / image.width * displayed.scale
                                    view.imageMatrix = Matrix().apply {
                                        setScale(scale, scale)
                                        postTranslate((widthPx - image.width * scale) / 2f + displayed.panX,
                                            (heightPx - image.height * scale) / 2f + displayed.panY)
                                    }
                                },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSave(event) }, modifier = Modifier.weight(1f).testTag("generated-image-save")) {
                        Icon(LucideIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.image_action_save))
                    }
                    OutlinedButton(onClick = { onShare(event) },
                        modifier = Modifier.weight(1f).testTag("generated-image-share")) {
                        Icon(LucideIcons.FileUp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.image_action_share))
                    }
                }
            }
        }
    }
}

private fun imageAspectFromSize(size: String?): Float {
    if (size.isNullOrBlank()) return 1f
    val dimensions = size.split(Regex("[xX:]"), limit = 2)
    val width = dimensions.getOrNull(0)?.toIntOrNull() ?: return 1f
    val height = dimensions.getOrNull(1)?.toIntOrNull() ?: return 1f
    return if (width > 0 && height > 0) width.toFloat() / height else 1f
}

private data class GeneratedImageFileStamp(val path: String, val modified: Long, val length: Long)
private fun generatedImageFileStamp(file: File) = GeneratedImageFileStamp(file.absolutePath, file.lastModified(), file.length())

internal data class GeneratedImageBounds(val width: Int, val height: Int)

/** Reads only PNG metadata before the view is sized; pixel decoding remains sampled and asynchronous. */
internal suspend fun readGeneratedImageBounds(file: File): GeneratedImageBounds? =
    withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else GeneratedImageBounds(bounds.outWidth, bounds.outHeight)
    }

/** Every decode, including bounds inspection and sampling, happens on Dispatchers.IO. */
internal suspend fun decodeGeneratedImage(
    file: File,
    targetWidth: Int,
    targetHeight: Int,
    onDecodeThread: (String) -> Unit = {},
): Bitmap? =
    withContext(Dispatchers.IO) {
        onDecodeThread(Thread.currentThread().name)
        val key = "${generatedImageFileStamp(file)}:${targetWidth}x$targetHeight"
        GeneratedImageBitmapCache.get(key)?.let { return@withContext it }
        if (!file.isFile) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val width = targetWidth.coerceAtLeast(1)
        val height = targetHeight.coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth / sample > width || bounds.outHeight / sample > height) {
            if (sample > Int.MAX_VALUE / 2) break
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return@withContext null
        GeneratedImageBitmapCache.put(key, bitmap)
        bitmap
    }

/** Cache ceiling is one eighth of the process's currently advertised maximum heap. */
private object GeneratedImageBitmapCache {
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 8L)
        .coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private val cache = object : android.util.LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }
}
