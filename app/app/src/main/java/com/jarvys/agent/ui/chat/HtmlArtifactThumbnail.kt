package com.jarvys.agent.ui.chat

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.HtmlPreviewDescriptor
import com.jarvys.agent.HtmlThumbnailImage
import com.jarvys.agent.HtmlThumbnailRenderer
import com.jarvys.agent.R

private data class ThumbnailPixels(val image: HtmlThumbnailImage? = null, val loading: Boolean = false)

/** Only visible, resumed cards request pixels. A page is never run to make its thumbnail. */
@Composable
internal fun HtmlArtifactThumbnail(request: ChatFileRequest, descriptor: HtmlPreviewDescriptor,
    onOpen: () -> Unit) {
    val context = LocalContext.current
    val localView = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var visible by remember(request.key) { mutableStateOf(false) }
    var width by remember(request.key) { mutableIntStateOf(0) }
    val active = visible && resumed && width > 0
    val token = remember(request.key, descriptor.token, width, active) { CancellationToken.cancellable() }
    DisposableEffect(token) { onDispose { token.cancel() } }
    val pixels by produceState(ThumbnailPixels(), request.key, descriptor.token, width, active, token) {
        value = ThumbnailPixels(loading = active)
        if (active) {
            val root = context.thumbnailActivity()?.window?.decorView as? ViewGroup
            val image = root?.let { HtmlThumbnailRenderer.load(context, it, request.sessionId, descriptor, width, token) }
            if (!token.isCancellationRequested) {
                value = ThumbnailPixels(image)
                awaitDispose { image?.close() }
            } else image?.close()
        }
    }
    HtmlThumbnailCardSurface(request, pixels.image?.bitmap, pixels.loading,
        modifier = Modifier.onGloballyPositioned { coordinates ->
                width = coordinates.size.width
                val bounds = coordinates.boundsInWindow()
                visible = bounds.width > 0 && bounds.height > 0 && bounds.bottom > 0 &&
                    bounds.top < localView.rootView.height && bounds.right > 0 && bounds.left < localView.rootView.width
            }, onOpen = onOpen)
}

/** Presentation only: pixels still come from the bounded, scripts-disabled local renderer. */
@Composable
internal fun HtmlThumbnailCardSurface(request: ChatFileRequest, image: Bitmap?, loading: Boolean,
    modifier: Modifier = Modifier, showActions: Boolean = true, onOpen: (() -> Unit)?) {
    val label = stringResource(R.string.chat_html_thumbnail_open, request.displayName)
    Surface(modifier.fillMaxWidth().aspectRatio(1.5f).testTag("chat-attachment-file-${request.artifactId}"),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Box(Modifier.fillMaxSize()) {
            // The preview and actions are siblings: actions never become children of its click target.
            Box(Modifier.fillMaxSize().then(if (onOpen != null) Modifier
                .testTag("delivered-file-preview-${request.artifactId}")
                .semantics { contentDescription = label }
                .clickable(role = Role.Button, onClickLabel = label, onClick = onOpen) else Modifier),
                contentAlignment = Alignment.Center) {
            if (image != null) Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Box(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp,
                bottom = if (showActions) 72.dp else 12.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.testTag("html-thumbnail-status-${request.artifactId}"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(stringResource(if (loading) R.string.chat_html_thumbnail_loading else R.string.chat_html_thumbnail_unavailable),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            }
            if (showActions) {
                Box(Modifier.fillMaxWidth().height(88.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))))
                    .testTag("html-thumbnail-gradient-${request.artifactId}"))
                ChatFileIconButtons(request, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    tagPrefix = "delivered-file", overlay = true)
            }
        }
    }
}

private fun Context.thumbnailActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.thumbnailActivity() else null
    else -> null
}
