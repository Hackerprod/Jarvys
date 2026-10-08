package com.jarvys.agent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.File

/** In-app preview for local, static workspace files; no TCP listener or external browser process. */
@Composable
fun WorkspacePreviewScreen(projectId: String) {
    val context = LocalContext.current
    val workspace = remember(projectId) { runCatching { readOnlyPreviewWorkspace(context, projectId) }.getOrNull() }
    if (workspace == null || !workspace.hasIndexHtml()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.workspace_preview_missing), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext -> createPreviewWebView(viewContext, workspace) },
        update = { view ->
            val url = previewUrl(projectId)
            if (view.url != url) view.loadUrl(url)
        },
        onRelease = { view ->
            view.stopLoading()
            view.loadUrl("about:blank")
            view.destroy()
        },
    )
}

/** Static HTML preview needs only its local files, never skills, connectors, or a credential vault. */
internal fun readOnlyPreviewWorkspace(context: Context, projectId: String): WorkspaceStore =
    WorkspaceStore(File(context.applicationContext.filesDir, "jarvys/workspaces"), projectId)

@SuppressLint("SetJavaScriptEnabled")
private fun createPreviewWebView(context: android.content.Context, workspace: WorkspaceStore): WebView =
    WebView(context).apply {
        setBackgroundColor(AndroidColor.TRANSPARENT)
        clearCache(true)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webViewClient = WorkspacePreviewClient(workspace)
        loadUrl(previewUrl(workspace.projectId()))
    }

private class WorkspacePreviewClient(private val workspace: WorkspaceStore) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
        if (request.method != "GET" && request.method != "HEAD") {
            return response(405, "Preview is read-only")
        }
        val uri = request.url
        val path = localPath(uri)
            ?: return response(403, "External preview resources are disabled")
        return try {
            val file = workspace.resolvePreviewPath(path)
            val mime = WorkspaceStore.mimeType(path)
            val headers = mutableMapOf(
                "Cache-Control" to if (mime == "text/html") "no-store" else "private, max-age=300",
                "X-Content-Type-Options" to "nosniff",
                "Content-Security-Policy" to "default-src 'self' data: blob:; "
                        + "img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; "
                        + "script-src 'self' 'unsafe-inline'; connect-src 'none'; "
                        + "object-src 'none'; frame-src 'none'; base-uri 'self'",
            )
            val encoding = if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") || mime.contains("svg")) "UTF-8" else null
            WebResourceResponse(mime, encoding, 200, "OK", headers, FileInputStream(file))
        } catch (error: Exception) {
            response(404, error.message ?: "Workspace file not found")
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
        localPath(request.url) == null

    private fun localPath(uri: Uri): String? {
        if (!"https".equals(uri.scheme, true) || uri.host != PREVIEW_HOST) return null
        val prefix = "/workspaces/${workspace.projectId()}/"
        val path = uri.path ?: return null
        if (!path.startsWith(prefix)) return null
        val relative = path.removePrefix(prefix).ifBlank { "index.html" }
        return if (relative.endsWith("/")) relative + "index.html" else relative
    }

    private fun response(status: Int, message: String) = WebResourceResponse(
        "text/plain",
        "UTF-8",
        status,
        when (status) { 403 -> "Forbidden"; 405 -> "Method Not Allowed"; else -> "Not Found" },
        mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
        ByteArrayInputStream(message.toByteArray(Charsets.UTF_8)),
    )
}

private fun previewUrl(projectId: String): String = "https://$PREVIEW_HOST/workspaces/$projectId/index.html"

private const val PREVIEW_HOST = "preview.jarvys.invalid"
