package com.jarvys.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** In-app local preview. The native WebView, not a Compose drag handler, owns page gestures. */
@Composable
fun WorkspacePreviewScreen(projectId: String, modifier: Modifier = Modifier, sessionId: String? = null) {
    key(projectId, sessionId) { ScopedPreviewScreen(projectId, sessionId, modifier) }
}

private data class PreviewLoad(val content: WorkspacePreviewContent? = null, val loaded: Boolean = false)

@Composable
private fun ScopedPreviewScreen(projectId: String, sessionId: String?, modifier: Modifier) {
    val context = LocalContext.current
    val loaded by produceState(PreviewLoad(), projectId, sessionId) {
        value = withContext(Dispatchers.IO) {
            PreviewLoad(runCatching {
                if (HtmlPreviewDescriptor.isSnapshotToken(projectId)) {
                    requireNotNull(sessionId)
                    val artifacts = DeliveredArtifactStore(context)
                    val descriptor = artifacts.resolvePreview(sessionId, projectId)
                    val owner = LocalRunStore(context).findChatFile(sessionId, "delivered", descriptor.artifactId)
                        ?: error("Preview is not owned by this conversation")
                    require(owner.id == descriptor.artifactId)
                    WorkspacePreviewContent(context.applicationContext, null, sessionId, descriptor)
                } else {
                    require(sessionId == null || WorkspaceStore.projectIdForSession(sessionId) == projectId)
                    val workspace = readOnlyPreviewWorkspace(context, projectId)
                    require(workspace.hasIndexHtml())
                    WorkspacePreviewContent(context.applicationContext, workspace)
                }
            }.getOrNull(), true)
        }
    }
    val content = loaded.content
    if (content == null) {
        Box(modifier.fillMaxSize().testTag("workspace-preview-status"), contentAlignment = Alignment.Center) {
            if (!loaded.loaded) CircularProgressIndicator()
            else Text(stringResource(R.string.workspace_preview_missing), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val activity = remember(context) { context.previewActivity() }
    val states = remember(activity) { ViewModelProvider(activity)[PreviewStateViewModel::class.java] }
    val state = remember(content.key, states) { states.page(content.key) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, state) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> state.view?.onResume()
                Lifecycle.Event.ON_PAUSE -> { state.capture(); state.view?.onPause() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column(modifier.fillMaxSize()) {
        if (content.warningCount > 0) Text(
            stringResource(R.string.workspace_preview_partial),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("workspace-preview-partial"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxSize(),
            factory = { viewContext -> createPreviewWebView(viewContext, content, state) },
            // Navigation, anchors, form focus and scroll belong to the existing native view.
            // Comparing its current URL to the entry URL here reloaded pages on recomposition.
            onRelease = { view ->
                state.release(view)
                view.stopLoading()
                view.onPause()
                view.destroy()
            },
        )
    }
}

/** Static preview reads never instantiate skills, connectors or credentials. Reject root symlinks. */
internal fun readOnlyPreviewWorkspace(context: Context, projectId: String): WorkspaceStore {
    val root = File(context.applicationContext.filesDir.canonicalFile, "jarvys/workspaces")
    val project = File(root, projectId)
    require(root.canonicalFile == root.absoluteFile && project.canonicalFile == project.absoluteFile)
    return WorkspaceStore(root, projectId).withoutAttachments()
}

private fun Context.previewActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.previewActivity()
    else -> error("Preview requires an Activity")
}

/** Protect one native touch stream without consuming Compose pointers or synthesizing scrolling. */
internal class WorkspacePreviewWebView(context: Context) : WebView(context) {
    var onUserInteraction: () -> Unit = {}
    // Keep the inherited native accessibility/click path. Touch handling never fabricates a click.
    override fun performClick(): Boolean = super.performClick()

    // WebView detects native/DOM clicks in super. This interception wrapper detects no click
    // itself, and calling performClick here would fabricate activations after drags/selection.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onUserInteraction()
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        var handled = false
        return try { super.onTouchEvent(event).also { handled = it } }
        finally {
            if (!handled || event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }
    override fun onDetachedFromWindow() {
        parent?.requestDisallowInterceptTouchEvent(false)
        super.onDetachedFromWindow()
    }
}

internal class WorkspacePreviewContent(context: Context, private val workspace: WorkspaceStore?,
    private val session: String? = null, private val descriptor: HtmlPreviewDescriptor? = null,
    thumbnail: Boolean = false) {
    val key = if (descriptor != null) "$session/${descriptor.token}" else requireNotNull(workspace).projectId()
    // Independent registrable hosts also prevent parent-domain cookies crossing previews.
    val host = "jarvys-${if (thumbnail) "thumbnail" else "preview"}-${previewOriginKey(key)}.invalid"
    private val prefix = if (descriptor == null) "/workspaces/${workspace!!.projectId()}/" else "/"
    private val root = workspace?.let { File(context.filesDir.canonicalFile, "jarvys/workspaces/${it.projectId()}") }
    private val artifacts = if (descriptor != null) DeliveredArtifactStore(context) else null
    val entryUrl = "https://$host$prefix" + (descriptor?.entryPath ?: "index.html").split('/').joinToString("/") { Uri.encode(it) }
    val warningCount = descriptor?.warnings?.size ?: 0

    fun localPath(uri: Uri): String? {
        if (!"https".equals(uri.scheme, true) || uri.encodedAuthority != host) return null
        val encoded = uri.encodedPath ?: return null
        if (Regex("%(?:2e|2f|5c|00|25)", RegexOption.IGNORE_CASE).containsMatchIn(encoded)) return null
        val path = uri.path ?: return null
        if (!path.startsWith(prefix)) return null
        val relative = path.removePrefix(prefix).ifBlank { "index.html" }
        val resource = if (relative.endsWith("/")) relative + "index.html" else relative
        return runCatching { HtmlPreviewCapture.requireOrdinaryPath(resource); resource }.getOrNull()
    }

    fun open(path: String): InputStream {
        if (descriptor != null) return artifacts!!.openPreview(session!!, descriptor.token, path)
        val logical = File(root!!, path)
        require(root.canonicalFile == root.absoluteFile && logical.canonicalFile == logical.absoluteFile)
        return FileInputStream(workspace!!.resolvePreviewPath(path))
    }
}

private fun previewOriginKey(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }

@SuppressLint("SetJavaScriptEnabled")
private fun createPreviewWebView(context: Context, content: WorkspacePreviewContent, state: PreviewPageState): WebView =
    WorkspacePreviewWebView(context).apply {
        tag = "workspace-preview-webview"
        setBackgroundColor(AndroidColor.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.blockNetworkLoads = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        onUserInteraction = state::userInteracted
        webViewClient = WorkspacePreviewClient(content, state)
        state.attach(this, content.entryUrl) { content.localPath(it.toUri()) != null }
    }

private class WorkspacePreviewClient(private val content: WorkspacePreviewContent,
    private val state: PreviewPageState) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
        if (request.method != "GET" && request.method != "HEAD") return response(405, "Preview is read-only")
        val path = content.localPath(request.url) ?: return response(403, "External preview resources are disabled")
        return try {
            val mime = WorkspaceStore.mimeType(path)
            val headers = mapOf(
                "Cache-Control" to "no-store",
                "X-Content-Type-Options" to "nosniff",
                "Content-Security-Policy" to "default-src 'self' data: blob:; "
                    + "img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; "
                    + "script-src 'self' 'unsafe-inline'; connect-src 'none'; "
                    + "object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'; worker-src 'none'",
            )
            val encoding = if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") || mime.contains("svg")) "UTF-8" else null
            val input = content.open(path)
            if (request.method == "HEAD") input.close()
            WebResourceResponse(mime, encoding, 200, "OK", headers,
                if (request.method == "HEAD") ByteArrayInputStream(byteArrayOf()) else input)
        } catch (_: Exception) {
            response(404, "Local preview resource is unavailable")
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
        request.method != "GET" || content.localPath(request.url) == null

    @Deprecated("Required for older WebView callbacks")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String): Boolean = content.localPath(url.toUri()) == null

    override fun onPageFinished(view: WebView, url: String) { state.pageFinished(view) }

    private fun response(status: Int, message: String) = WebResourceResponse(
        "text/plain", "UTF-8", status,
        when (status) { 403 -> "Forbidden"; 405 -> "Method Not Allowed"; else -> "Not Found" },
        mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
        ByteArrayInputStream(message.toByteArray(Charsets.UTF_8)),
    )
}
