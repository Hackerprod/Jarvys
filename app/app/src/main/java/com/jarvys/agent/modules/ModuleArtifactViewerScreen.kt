package com.jarvys.agent.modules

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.ChatAttachment
import com.jarvys.agent.ConversationModuleCapabilityAccess
import com.jarvys.agent.DeliveredArtifactStore
import com.jarvys.agent.R
import com.jarvys.agent.WebViewDiagnosticTrace
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ChatFileOwnership
import com.jarvys.agent.chatdb.ConversationArtifact
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_VIEWER_HTML_BYTES = 2 * 1024 * 1024

private sealed interface ViewerArtifactState {
    data object Loading : ViewerArtifactState
    data object Unavailable : ViewerArtifactState
    data class Ready(val module: ModuleManifest, val artifact: ConversationArtifact, val viewerPath: String) : ViewerArtifactState
}

/**
 * Opens a delivered artifact with the HTML viewer declared for its artifact type in its module
 * manifest. The manifest and ledger are checked before display and again before serving bytes.
 */
@Composable
fun ModuleArtifactViewerScreen(
    conversationId: String,
    artifactId: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val state by produceState<ViewerArtifactState>(
        initialValue = ViewerArtifactState.Loading,
        context,
        conversationId,
        artifactId,
    ) {
        value = withContext(Dispatchers.IO) {
            resolveViewerArtifact(context, conversationId, artifactId)
                ?: ViewerArtifactState.Unavailable
        }
    }
    when (val current = state) {
        ViewerArtifactState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        ViewerArtifactState.Unavailable -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.chat_attachment_unavailable), color = MaterialTheme.colorScheme.error)
        }
        is ViewerArtifactState.Ready -> {
            Column(modifier.fillMaxSize()) {
                Text(
                    current.artifact.title?.takeIf(String::isNotBlank) ?: current.artifact.name,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                val token = remember(conversationId, artifactId) { UUID.randomUUID().toString().replace("-", "") }
                val cancellation = remember(conversationId, artifactId) { CancellationToken.cancellable() }
                val assetLoader = remember(context) {
                    WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                        .build()
                }
                val access = remember(conversationId, artifactId, current.module, cancellation) {
                    ConversationModuleCapabilityAccess(context, conversationId, current.module, cancellation, null)
                }
                val bridge = remember(conversationId, artifactId, current.module, access) {
                    ModuleCapabilityBridge(
                        conversationId = conversationId,
                        declaredCapabilities = emptySet(),
                        declaredArtifactTypes = emptyList(),
                        access = access,
                        complete = {},
                        fail = {},
                    )
                }
                val webView = remember(context, conversationId, artifactId, token, current.module, current.viewerPath) {
                    createViewerWebView(
                        context = context,
                        conversationId = conversationId,
                        artifactId = artifactId,
                        artifact = current.artifact,
                        module = current.module,
                        viewerPath = current.viewerPath,
                        token = token,
                        assetLoader = assetLoader,
                        bridge = bridge,
                    )
                }
                DisposableEffect(webView, cancellation) {
                    onDispose {
                        cancellation.cancel()
                        webView.stopLoading()
                        webView.removeJavascriptInterface("Jarvys")
                        webView.webViewClient = WebViewClient()
                        webView.webChromeClient = null
                        webView.onPause()
                        webView.destroy()
                    }
                }
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize(),
                    update = {},
                )
            }
        }
    }
}

private fun resolveViewerArtifact(
    context: Context,
    conversationId: String,
    artifactId: String,
): ViewerArtifactState.Ready? {
    if (conversationId.isBlank() || artifactId.isBlank()) return null
    val artifact = runCatching {
        (ChatDatabaseBridge.getInstance(context).findChatFileSync(conversationId, "delivered", artifactId)
            as? ChatFileOwnership.Delivered)?.value
    }.getOrNull() ?: return null
    val moduleId = artifact.module_id?.takeIf(String::isNotBlank) ?: return null
    val registry = runCatching { ModuleRegistry.get(context) }.getOrNull() ?: return null
    val module = registry.module(moduleId) ?: return null
    val declaredModule = registry.artifactModuleForType(artifact.artifact_type) ?: return null
    if (declaredModule.id != moduleId) return null
    val viewerPath = registry.artifactViewerForType(artifact.artifact_type) ?: return null
    if (viewerPath.startsWith("native/") || !ModuleWorkspacePath.isSafeBundlePath(viewerPath) ||
        !viewerPath.endsWith(".html", ignoreCase = true)) return null
    return ViewerArtifactState.Ready(module, artifact, viewerPath)
}

@SuppressLint("SetJavaScriptEnabled")
private fun createViewerWebView(
    context: Context,
    conversationId: String,
    artifactId: String,
    artifact: ConversationArtifact,
    module: ModuleManifest,
    viewerPath: String,
    token: String,
    assetLoader: WebViewAssetLoader,
    bridge: ModuleCapabilityBridge,
): WebView {
    val encodedDirectory = module.bundleAssetDirectory.split('/').joinToString("/") { Uri.encode(it) }
    val encodedViewer = viewerPath.split('/').joinToString("/") { Uri.encode(it) }
    val viewerUrl = "https://${ModuleWebViewSecurity.ASSET_AUTHORITY}/assets/$encodedDirectory/$encodedViewer"
    val diagnostics = WebViewDiagnosticTrace(
        localResourceLabel = { uri ->
            ModuleWebViewSecurity.moduleAssetPath(uri, module.bundleAssetDirectory)
                ?: if (ModuleWebViewSecurity.isArtifactUrl(uri, token)) "delivered-artifact" else null
        },
        logMessage = { Log.w("ArtifactViewer", it) },
    )
    val view = WebView(context)
    ModuleWebViewSecurity.configure(view)
    view.addJavascriptInterface(bridge, "Jarvys")
    view.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            diagnostics.recordConsoleMessage(
                consoleMessage.sourceId(), consoleMessage.lineNumber(),
                consoleMessage.messageLevel().name, consoleMessage.message(),
            )
            return true
        }
    }
    view.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
            if (!request.method.equals("GET", ignoreCase = true)) {
                diagnostics.recordDenied(request.url, WebViewDiagnosticTrace.REASON_METHOD_NOT_ALLOWED, request.isForMainFrame)
                return ModuleWebViewSecurity.blockedResponse()
            }
            if (ModuleWebViewSecurity.isArtifactUrl(request.url, token)) {
                val response = artifactResponse(context, conversationId, artifactId, module.id)
                if (response.statusCode != 200) {
                    diagnostics.recordDenied(request.url, WebViewDiagnosticTrace.REASON_ARTIFACT_UNAVAILABLE, request.isForMainFrame)
                }
                return response
            }
            val relative = ModuleWebViewSecurity.moduleAssetPath(request.url, module.bundleAssetDirectory)
                ?: run {
                    diagnostics.recordDenied(request.url, WebViewDiagnosticTrace.REASON_OUTSIDE_ALLOWED_SCOPE, request.isForMainFrame)
                    return ModuleWebViewSecurity.blockedResponse()
                }
            if (relative == viewerPath) {
                val response = viewerHtmlResponse(context, module, artifact, token, viewerPath)
                if (response.statusCode != 200) diagnostics.recordAssetHandlerError(request.url)
                return response
            }
            return try {
                assetLoader.shouldInterceptRequest(request.url) ?: run {
                    diagnostics.recordAssetHandlerMiss(request.url)
                    ModuleWebViewSecurity.blockedResponse()
                }
            } catch (_: Exception) {
                diagnostics.recordAssetHandlerError(request.url)
                ModuleWebViewSecurity.blockedResponse()
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
            val denied = ModuleWebViewSecurity.moduleAssetPath(request.url, module.bundleAssetDirectory) == null
            if (denied) diagnostics.recordDenied(request.url, WebViewDiagnosticTrace.REASON_OUTSIDE_ALLOWED_SCOPE, request.isForMainFrame)
            return denied
        }

        @Deprecated("Required for older WebView callbacks")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String): Boolean {
            val uri = Uri.parse(url)
            val denied = ModuleWebViewSecurity.moduleAssetPath(uri, module.bundleAssetDirectory) == null
            if (denied) diagnostics.recordDenied(uri, WebViewDiagnosticTrace.REASON_OUTSIDE_ALLOWED_SCOPE)
            return denied
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: android.webkit.WebResourceError?,
        ) {
            diagnostics.recordResourceError(request?.url, error?.errorCode ?: 0, request?.isForMainFrame)
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?,
        ) {
            diagnostics.recordHttpError(request?.url, errorResponse?.statusCode ?: 0, request?.isForMainFrame)
        }
    }
    view.loadUrl(viewerUrl)
    return view
}

private fun viewerHtmlResponse(
    context: Context,
    module: ModuleManifest,
    artifact: ConversationArtifact,
    token: String,
    viewerPath: String,
): WebResourceResponse = runCatching {
    val bundlePath = "${module.bundleAssetDirectory}/$viewerPath"
    val source = context.assets.open(bundlePath).use { stream -> readBoundedUtf8(stream, MAX_VIEWER_HTML_BYTES) }
    val artifactInfo = JSONObject()
        .put("id", artifact.artifact_id)
        .put("artifactType", artifact.artifact_type)
        .put("moduleId", module.id)
        .put("name", artifact.name)
        .put("title", artifact.title)
        .put("sizeBytes", artifact.size_bytes)
        .put("url", "https://${ModuleWebViewSecurity.ASSET_AUTHORITY}/module-artifacts/$token")
        .toString()
        .replace("&", "\\u0026")
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
    val bootstrap = "<script>window.JarvysArtifact=$artifactInfo;</script>"
    val content = insertBootstrapBeforeModuleScripts(source, bootstrap)
    WebResourceResponse(
        "text/html",
        "UTF-8",
        200,
        "OK",
        ModuleWebViewSecurity.viewerHeaders() + mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
        ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)),
    )
}.getOrElse { ModuleWebViewSecurity.blockedResponse() }

private fun insertBootstrapBeforeModuleScripts(source: String, bootstrap: String): String {
    val head = Regex("(?i)<head(?:\\s[^>]*)?>").find(source)
    if (head != null) return source.substring(0, head.range.last + 1) + bootstrap + source.substring(head.range.last + 1)
    val firstScript = Regex("(?i)<script(?:\\s|>)").find(source)
    if (firstScript != null) return source.substring(0, firstScript.range.first) + bootstrap + source.substring(firstScript.range.first)
    val bodyEnd = Regex("(?i)</body\\s*>").find(source)
    if (bodyEnd != null) return source.substring(0, bodyEnd.range.first) + bootstrap + source.substring(bodyEnd.range.first)
    return bootstrap + source
}

private fun artifactResponse(
    context: Context,
    conversationId: String,
    artifactId: String,
    moduleId: String,
): WebResourceResponse = runCatching {
    val resolved = resolveViewerArtifact(context, conversationId, artifactId)
        ?: return@runCatching ModuleWebViewSecurity.blockedResponse()
    if (resolved.module.id != moduleId) return@runCatching ModuleWebViewSecurity.blockedResponse()
    val row = resolved.artifact
    val attachment = ChatAttachment(
        row.artifact_id,
        ChatAttachment.sanitizeName(row.name),
        row.mime_type,
        row.size_bytes,
        if (row.kind.equals("IMAGE", ignoreCase = true)) ChatAttachment.Kind.IMAGE else ChatAttachment.Kind.FILE,
        row.relative_path,
    )
    val stream = DeliveredArtifactStore(context).open(conversationId, attachment)
    artifactResourceResponse(attachment.mimeType, stream)
}.getOrElse { ModuleWebViewSecurity.blockedResponse() }

private fun artifactResourceResponse(mimeType: String, stream: InputStream): WebResourceResponse =
    WebResourceResponse(
        mimeType,
        "UTF-8",
        200,
        "OK",
        ModuleWebViewSecurity.viewerHeaders() + mapOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Access-Control-Allow-Origin" to "https://${ModuleWebViewSecurity.ASSET_AUTHORITY}",
        ),
        stream,
    )

private fun readBoundedUtf8(stream: InputStream, maxBytes: Int): String {
    val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        require(output.size() + count <= maxBytes) { "Module viewer document exceeds the size limit." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray().toString(Charsets.UTF_8)
}
