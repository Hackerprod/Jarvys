package com.jarvys.agent.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.jarvys.agent.AgentErrorReporter
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.AttachmentStore
import com.jarvys.agent.DeliveredArtifactStore
import com.jarvys.agent.GeneratedImageStore
import com.jarvys.agent.PreviewResponsePolicy
import com.jarvys.agent.WebViewDiagnosticTrace
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

internal data class ChatCanvasCallbacks(
    val action: (String, AgentRunUiEvent, String) -> Unit = { _, _, _ -> },
    val requestOlder: () -> Unit = {},
    val benchmarkFirstPaint: (Double) -> Unit = {},
    val benchmark: (String) -> Unit = {},
)

internal data class ChatCanvasUpdate(
    val bootstrapScript: String,
    val patchScript: String,
    val patchNeeded: Boolean,
    val benchmark: Boolean,
)

private data class ChatCanvasSerializedSnapshot(
    val bySeq: Map<Long, String>,
    val order: String,
    val followEnd: Boolean,
    val loadingOlder: Boolean,
)

@Composable
internal fun ChatCanvasTimeline(
    sessionId: String,
    events: List<AgentRunUiEvent>,
    running: Boolean,
    allowRetry: Boolean,
    hasOlder: Boolean,
    loadingOlder: Boolean,
    callbacks: ChatCanvasCallbacks,
    benchmarkMode: Boolean = false,
    benchmarkStartedAtNanos: Long? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snapshot = androidx.compose.runtime.remember { AtomicReference<ChatCanvasSerializedSnapshot?>(null) }
    val update by produceState<ChatCanvasUpdate?>(null, events, running, allowRetry, loadingOlder, benchmarkMode) {
        value = withContext(Dispatchers.Default) {
            val payload = ChatCanvasEventSerializer.page(context, events, running, allowRetry, loadingOlder = loadingOlder)
                .put("benchmark", benchmarkMode)
            val orderArray = payload.getJSONArray("order")
            val orderKey = orderArray.toString()
            val followEnd = payload.optBoolean("followEnd", false)
            val historyLoading = payload.optBoolean("loadingOlder", false)
            val changed = org.json.JSONArray()
            val nextBySeq = HashMap<Long, String>(events.size)
            payload.getJSONArray("events").let { serialized ->
                for (index in 0 until serialized.length()) {
                    val event = serialized.getJSONObject(index)
                    val seq = event.getLong("seq")
                    val json = event.toString()
                    nextBySeq[seq] = json
                    if (snapshot.get()?.bySeq?.get(seq) != json) changed.put(event)
                }
            }
            val previous = snapshot.get()
            val orderChanged = previous?.order != orderKey
            val patchNeeded = previous == null || changed.length() > 0 || orderChanged ||
                previous.followEnd != followEnd || previous.loadingOlder != historyLoading
            val patch = JSONObject().put("events", changed).put("followEnd", followEnd).put("loadingOlder", historyLoading)
            if (orderChanged) patch.put("order", orderArray)
            snapshot.set(ChatCanvasSerializedSnapshot(nextBySeq, orderKey, followEnd, historyLoading))
            ChatCanvasUpdate(
                bootstrapScript = "window.ChatCanvas.bootstrap(JSON.parse(${JSONObject.quote(payload.toString())}));",
                patchScript = "window.ChatCanvas.applyDeltasToView(JSON.parse(${JSONObject.quote(patch.toString())}));",
                patchNeeded = patchNeeded,
                benchmark = benchmarkMode,
            )
        }
    }
    val viewCallbacks = androidx.compose.runtime.remember(callbacks) { callbacks }
    AndroidView(
        modifier = modifier.testTag("chat-canvas-webview"),
        factory = { viewContext ->
            ChatCanvasWebView(viewContext).also { view ->
                view.setBridge(ChatCanvasNativeBridge(
                    sessionId = sessionId,
                    currentEvents = { events },
                    running = { running },
                    allowRetry = { allowRetry },
                    hasOlder = { hasOlder },
                    loadingOlder = { loadingOlder },
                    benchmarkStartedAtNanos = benchmarkStartedAtNanos,
                    callbacks = viewCallbacks,
                ))
                view.loadUrl("${ChatCanvasWebView.ORIGIN}/index.html")
            }
        },
        update = { view ->
            view.setBridge(ChatCanvasNativeBridge(
                sessionId = sessionId,
                currentEvents = { events },
                running = { running },
                allowRetry = { allowRetry },
                hasOlder = { hasOlder },
                loadingOlder = { loadingOlder },
                benchmarkStartedAtNanos = benchmarkStartedAtNanos,
                callbacks = viewCallbacks,
            ))
            update?.let(view::update)
        },
        onRelease = { view ->
            view.stopLoading()
            view.removeJavascriptInterface("Android")
            view.webChromeClient = null
            view.destroy()
        },
    )
}

/** WebView with an asset-only origin and resource paths scoped to the active conversation. */
@SuppressLint("SetJavaScriptEnabled")
internal class ChatCanvasWebView(context: Context) : WebView(context) {
    companion object { const val ORIGIN = "https://jarvys-chat-canvas.invalid" }

    private var nativeBridge: ChatCanvasNativeBridge? = null
    private var pageReady = false
    private var pendingUpdate: ChatCanvasUpdate? = null
    private var benchmarkStarted = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = "Mensajes de la conversación"
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.blockNetworkLoads = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        addJavascriptInterface(object {
            @JavascriptInterface fun action(action: String, seq: Long, value: String?) { nativeBridge?.dispatch(action, seq, value.orEmpty()) }
            @JavascriptInterface fun requestOlderPage() { nativeBridge?.requestOlderPage() }
            @JavascriptInterface fun benchmarkFirstPaint() { nativeBridge?.benchmarkFirstPaint() }
            @JavascriptInterface fun benchmarkResult(report: String) { nativeBridge?.benchmarkResult(report) }
        }, "Android")
        val diagnostics = WebViewDiagnosticTrace(
            localResourceLabel = ::canvasDiagnosticResourceLabel,
            logMessage = { Log.w("ChatCanvasWeb", it) },
        )
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                diagnostics.recordConsoleMessage(
                    consoleMessage.sourceId(), consoleMessage.lineNumber(),
                    consoleMessage.messageLevel().name, consoleMessage.message(),
                )
                return true
            }
        }
        webViewClient = CanvasClient(context.applicationContext, this, diagnostics)
    }

    fun setBridge(bridge: ChatCanvasNativeBridge) { nativeBridge = bridge }

    fun pageFinished() {
        pageReady = true
        pendingUpdate?.let(::update)
    }

    fun update(update: ChatCanvasUpdate) {
        pendingUpdate = update
        if (!pageReady) return
        if (!bootstrapped) {
            evaluateJavascript(update.bootstrapScript, null)
            bootstrapped = true
        } else if (update.patchNeeded) {
            evaluateJavascript(update.patchScript, null)
        }
        if (!benchmarkStarted && update.benchmark) {
            benchmarkStarted = true
            postDelayed({ evaluateJavascript("window.ChatCanvas.runBenchmark($CHAT_CANVAS_BENCHMARK_SCROLL_VIEWPORT_FRACTION);", null) }, 100)
        }
    }

    private var bootstrapped = false

    private class CanvasClient(
        private val appContext: Context,
        private val view: ChatCanvasWebView,
        private val diagnostics: WebViewDiagnosticTrace,
    ) : WebViewClient() {
        override fun shouldInterceptRequest(webView: WebView?, request: WebResourceRequest): WebResourceResponse {
            val uri = request.url
            if (request.method != "GET" && request.method != "HEAD") return deny(
                uri, 405, WebViewDiagnosticTrace.REASON_METHOD_NOT_ALLOWED, "Canvas is read-only",
                request.isForMainFrame,
            )
            if (!isCanvasRequestInOrigin(uri.scheme, uri.host, uri.port, uri.query)) {
                return deny(
                    uri, 403, WebViewDiagnosticTrace.REASON_OUTSIDE_ALLOWED_SCOPE, "External resources are disabled",
                    request.isForMainFrame,
                )
            }
            return try {
                val path = uri.path ?: return deny(
                    uri, 404, WebViewDiagnosticTrace.REASON_RESOURCE_UNAVAILABLE, "Resource unavailable",
                    request.isForMainFrame,
                )
                if (canvasPathHasTraversal(path)) return deny(
                    uri, 404, WebViewDiagnosticTrace.REASON_RESOURCE_UNAVAILABLE, "Resource unavailable",
                    request.isForMainFrame,
                )
                val resource = when (path) {
                    "/index.html" -> CanvasResource(appContext.assets.open("chat_canvas/index.html"), "text/html")
                    "/canvas.css" -> CanvasResource(appContext.assets.open("chat_canvas/canvas.css"), "text/css")
                    "/canvas.js" -> CanvasResource(appContext.assets.open("chat_canvas/canvas.js"), "application/javascript")
                    "/benchmark-image.svg" -> {
                        val synthetic = view.nativeBridge?.hasSyntheticImages() == true
                        if (!synthetic) return deny(
                            uri, 404, WebViewDiagnosticTrace.REASON_RESOURCE_UNAVAILABLE, "Resource unavailable",
                            request.isForMainFrame,
                        )
                        CanvasResource(appContext.assets.open("chat_canvas/benchmark-image.svg"), "image/svg+xml")
                    }
                    else -> resolveConversationResource(appContext, view.nativeBridge, path)
                        ?: return deny(
                            uri, 404, WebViewDiagnosticTrace.REASON_RESOURCE_UNAVAILABLE, "Resource unavailable",
                            request.isForMainFrame,
                        )
                }
                val mime = resource.mimeType
                WebResourceResponse(mime, if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("svg")) "UTF-8" else null,
                    200, "OK", PreviewResponsePolicy.interactiveHeaders(), if (request.method == "HEAD") {
                        resource.stream.close(); ByteArrayInputStream(byteArrayOf())
                    } else resource.stream)
            } catch (failure: RuntimeException) {
                AgentErrorReporter.reportChatDatabaseFailure(appContext, failure)
                deny(uri, 404, WebViewDiagnosticTrace.REASON_RESOURCE_UNAVAILABLE, "Resource unavailable", request.isForMainFrame)
            } catch (_: Exception) {
                deny(uri, 404, WebViewDiagnosticTrace.REASON_RESOURCE_UNAVAILABLE, "Resource unavailable", request.isForMainFrame)
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
            val uri = request.url
            val denied = !uri.scheme.equals("https", ignoreCase = true) ||
                uri.host != "jarvys-chat-canvas.invalid" || uri.port != -1 ||
                uri.query != null || uri.fragment != null
            if (denied) diagnostics.recordDenied(uri, WebViewDiagnosticTrace.REASON_OUTSIDE_ALLOWED_SCOPE, request.isForMainFrame)
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

        override fun onPageFinished(view: WebView, url: String) { this.view.pageFinished() }

        private fun deny(uri: android.net.Uri, code: Int, reason: String, message: String, mainFrame: Boolean): WebResourceResponse {
            diagnostics.recordDenied(uri, reason, mainFrame)
            return denied(code, message)
        }

        private fun denied(code: Int, message: String) = WebResourceResponse(
            "text/plain", "UTF-8", code, when (code) { 403 -> "Forbidden"; 405 -> "Method Not Allowed"; else -> "Not Found" },
            PreviewResponsePolicy.interactiveHeaders(), ByteArrayInputStream(message.toByteArray(Charsets.UTF_8)))
    }
}

private fun canvasDiagnosticResourceLabel(uri: android.net.Uri): String? {
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host != "jarvys-chat-canvas.invalid" ||
        uri.port != -1 || uri.query != null || uri.fragment != null) return null
    val parts = uri.path.orEmpty().split('/').filter(String::isNotEmpty)
    if (parts.size == 1) return parts.single().takeIf { it in setOf("index.html", "canvas.css", "canvas.js", "benchmark-image.svg") }
    if (parts.size == 4 && parts[0] == "chat-assets" && parts[1] == "message" &&
        parts[2].all(Char::isDigit) && parts[3].all(Char::isDigit)) return "chat-assets/message/image"
    if (parts.size == 3 && parts[0] == "chat-assets" && parts[1] == "generated" &&
        parts[2].all(Char::isDigit)) return "chat-assets/generated/image"
    if (parts.size == 4 && parts[0] == "chat-assets" && parts[1] == "artifact" &&
        parts[2].all(Char::isDigit) && parts[3] == "thumbnail") return "chat-assets/artifact/thumbnail"
    return null
}

/** WebView JS callbacks run off-main. Recheck against the current event snapshot before dispatch. */
internal class ChatCanvasNativeBridge(
    private val sessionId: String,
    private val currentEvents: () -> List<AgentRunUiEvent>,
    private val running: () -> Boolean,
    private val allowRetry: () -> Boolean,
    private val hasOlder: () -> Boolean,
    private val loadingOlder: () -> Boolean,
    private val benchmarkStartedAtNanos: Long?,
    private val callbacks: ChatCanvasCallbacks,
) {
    private val main = Handler(Looper.getMainLooper())
    private val firstPaintReported = AtomicBoolean(false)

    fun dispatch(action: String, seq: Long, value: String) {
        val events = currentEvents()
        val event = events.firstOrNull { it.id == seq }
        val latestAssistant = events.lastOrNull { it.kind == "assistant" }?.messageId
        val decision = ChatCanvasActionPolicy.check(action, event, value, running(), latestAssistant, hasOlder(), allowRetry())
        if (!decision.allowed) return
        main.post {
            val current = currentEvents().firstOrNull { it.id == seq } ?: return@post
            val latest = currentEvents().lastOrNull { it.kind == "assistant" }?.messageId
            if (!ChatCanvasActionPolicy.check(action, current, value, running(), latest, hasOlder(), allowRetry()).allowed) return@post
            callbacks.action(action, current, value)
        }
    }

    fun requestOlderPage() {
        if (!hasOlder() || loadingOlder()) return
        main.post { if (hasOlder() && !loadingOlder()) callbacks.requestOlder() }
    }

    fun benchmarkResult(report: String) {
        if (report.length > 64_000 || runCatching { JSONObject(report) }.isFailure) return
        main.post { callbacks.benchmark(report) }
    }

    fun benchmarkFirstPaint() {
        val started = benchmarkStartedAtNanos ?: return
        if (!firstPaintReported.compareAndSet(false, true)) return
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        main.post { callbacks.benchmarkFirstPaint(elapsedMs) }
    }

    internal fun session(): String = sessionId
    internal fun event(seq: Long): AgentRunUiEvent? = currentEvents().firstOrNull { it.id == seq }
    internal fun events(): List<AgentRunUiEvent> = currentEvents()
    internal fun hasSyntheticImages(): Boolean = currentEvents().any {
        it.generatedImageStatus == "SYNTHETIC" || it.presentationArtifact?.thumbnailRef == "benchmark"
    }
}

private data class CanvasResource(val stream: InputStream, val mimeType: String)

internal fun isCanvasRequestInOrigin(scheme: String?, host: String?, port: Int, query: String?): Boolean =
    scheme.equals("https", ignoreCase = true) && host == "jarvys-chat-canvas.invalid" && port == -1 && query == null

internal fun canvasPathHasTraversal(path: String): Boolean =
    path.split('/').any { it == "." || it == ".." }

internal sealed interface CanvasConversationResourceRoute<out T> {
    data class MessageAttachment(val event: AgentRunUiEvent, val attachment: com.jarvys.agent.ChatAttachment) : CanvasConversationResourceRoute<Nothing>
    data class GeneratedImage(val event: AgentRunUiEvent, val artifactId: String) : CanvasConversationResourceRoute<Nothing>
    data class ArtifactThumbnail<T>(
        val event: AgentRunUiEvent,
        val artifact: com.jarvys.agent.PresentationArtifact,
        val reference: T,
    ) : CanvasConversationResourceRoute<T>
}

/** Resolves a local resource only from an event in the active conversation and its owned file metadata. */
internal fun <T> resolveCanvasConversationResourceRoute(
    path: String,
    sessionId: String?,
    events: List<AgentRunUiEvent>,
    attachmentIsOwned: (String, com.jarvys.agent.ChatAttachment) -> Boolean,
    generatedImageIsOwned: (String, AgentRunUiEvent) -> Boolean,
    findThumbnailReference: (String, String) -> T?,
    thumbnailReferenceId: (T) -> String,
): CanvasConversationResourceRoute<T>? {
    if (sessionId == null || canvasPathHasTraversal(path)) return null
    val chunks = path.split('/').filter(String::isNotBlank)
    if (chunks.size < 3 || chunks[0] != "chat-assets") return null
    val eventSeq = chunks.getOrNull(2)?.toLongOrNull() ?: return null
    val event = events.firstOrNull { it.id == eventSeq } ?: return null
    return when (chunks[1]) {
        "message" -> {
            if (chunks.size != 4) return null
            val index = chunks[3].toIntOrNull() ?: return null
            val attachment = event.attachments.getOrNull(index)?.takeIf { it.isImage } ?: return null
            if (!attachmentIsOwned(sessionId, attachment)) return null
            CanvasConversationResourceRoute.MessageAttachment(event, attachment)
        }
        "generated" -> {
            if (chunks.size != 3 || event.generatedImagePath.isNullOrBlank() ||
                !generatedImageIsOwned(sessionId, event)) return null
            CanvasConversationResourceRoute.GeneratedImage(event, event.generatedImagePath)
        }
        "artifact" -> {
            if (chunks.size != 4 || chunks[3] != "thumbnail") return null
            val artifact = event.presentationArtifact ?: return null
            val reference = findThumbnailReference(sessionId, artifact.id) ?: return null
            if (thumbnailReferenceId(reference) != artifact.thumbnailRef) return null
            CanvasConversationResourceRoute.ArtifactThumbnail(event, artifact, reference)
        }
        else -> null
    }
}

private fun resolveConversationResource(context: Context, bridge: ChatCanvasNativeBridge?, path: String): CanvasResource? {
    val sessionId = bridge?.session()
    val route = resolveCanvasConversationResourceRoute(
        path = path,
        sessionId = sessionId,
        events = bridge?.events().orEmpty(),
        attachmentIsOwned = { session, attachment ->
            com.jarvys.agent.ui.chat.ChatFileRequest.attachment(session, attachment).requireOwnership(context)
            true
        },
        generatedImageIsOwned = { session, event ->
            val request = com.jarvys.agent.ui.chat.ChatFileRequest.generated(session, event)
                ?: return@resolveCanvasConversationResourceRoute false
            request.requireOwnership(context)
            true
        },
        findThumbnailReference = { session, artifactId ->
            ChatDatabaseBridge.getInstance(context).findArtifactThumbnailSync(session, artifactId)
        },
        thumbnailReferenceId = { it.id },
    ) ?: return null
    return when (route) {
        is CanvasConversationResourceRoute.MessageAttachment -> {
            CanvasResource(FileInputStream(AttachmentStore(context).resolve(requireNotNull(sessionId), route.attachment)), route.attachment.mimeType)
        }
        is CanvasConversationResourceRoute.GeneratedImage -> {
            CanvasResource(FileInputStream(GeneratedImageStore(context).resolve(requireNotNull(sessionId), route.artifactId)),
                route.event.generatedImageMimeType ?: "image/png")
        }
        is CanvasConversationResourceRoute.ArtifactThumbnail -> {
            val reference = route.reference
            CanvasResource(FileInputStream(DeliveredArtifactStore(context).resolveThumbnail(requireNotNull(sessionId), route.artifact.id, reference)),
                reference.mime_type ?: "image/png")
        }
    }
}
