package com.jarvys.agent.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.jarvys.agent.AgentErrorReporter
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.AttachmentStore
import com.jarvys.agent.DeliveredArtifactStore
import com.jarvys.agent.GeneratedImageStore
import com.jarvys.agent.PreviewResponsePolicy
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
    val snapshot = androidx.compose.runtime.remember { AtomicReference<ChatCanvasSerializedSnapshot?>(null) }
    val update by produceState<ChatCanvasUpdate?>(null, events, running, allowRetry, loadingOlder, benchmarkMode) {
        value = withContext(Dispatchers.Default) {
            val payload = ChatCanvasEventSerializer.page(events, running, allowRetry, loadingOlder = loadingOlder)
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
        webViewClient = CanvasClient(context.applicationContext, this)
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
            postDelayed({ evaluateJavascript("window.ChatCanvas.runBenchmark();", null) }, 100)
        }
    }

    private var bootstrapped = false

    private class CanvasClient(private val appContext: Context, private val view: ChatCanvasWebView) : WebViewClient() {
        override fun shouldInterceptRequest(webView: WebView?, request: WebResourceRequest): WebResourceResponse {
            if (request.method != "GET" && request.method != "HEAD") return denied(405, "Canvas is read-only")
            val uri = request.url
            if (!uri.scheme.equals("https", true) || uri.host != "jarvys-chat-canvas.invalid" || uri.port != -1 || uri.query != null) {
                return denied(403, "External resources are disabled")
            }
            return try {
                val path = uri.path ?: return denied(404, "Resource unavailable")
                val resource = when (path) {
                    "/index.html" -> CanvasResource(appContext.assets.open("chat_canvas/index.html"), "text/html")
                    "/canvas.css" -> CanvasResource(appContext.assets.open("chat_canvas/canvas.css"), "text/css")
                    "/canvas.js" -> CanvasResource(appContext.assets.open("chat_canvas/canvas.js"), "application/javascript")
                    "/benchmark-image.svg" -> {
                        val synthetic = view.nativeBridge?.hasSyntheticImages() == true
                        if (!synthetic) return denied(404, "Resource unavailable")
                        CanvasResource(appContext.assets.open("chat_canvas/benchmark-image.svg"), "image/svg+xml")
                    }
                    else -> resolveConversationResource(appContext, view.nativeBridge, path)
                        ?: return denied(404, "Resource unavailable")
                }
                val mime = resource.mimeType
                WebResourceResponse(mime, if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("svg")) "UTF-8" else null,
                    200, "OK", PreviewResponsePolicy.interactiveHeaders(), if (request.method == "HEAD") {
                        resource.stream.close(); ByteArrayInputStream(byteArrayOf())
                    } else resource.stream)
            } catch (failure: RuntimeException) {
                AgentErrorReporter.reportChatDatabaseFailure(appContext, failure)
                denied(404, "Resource unavailable")
            } catch (_: Exception) { denied(404, "Resource unavailable") }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
            request.url.host != "jarvys-chat-canvas.invalid" || request.url.scheme != "https"

        override fun onPageFinished(view: WebView, url: String) { this.view.pageFinished() }

        private fun denied(code: Int, message: String) = WebResourceResponse(
            "text/plain", "UTF-8", code, when (code) { 403 -> "Forbidden"; 405 -> "Method Not Allowed"; else -> "Not Found" },
            PreviewResponsePolicy.interactiveHeaders(), ByteArrayInputStream(message.toByteArray(Charsets.UTF_8)))
    }
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
    internal fun hasSyntheticImages(): Boolean = currentEvents().any {
        it.generatedImageStatus == "SYNTHETIC" || it.presentationArtifact?.thumbnailRef == "benchmark"
    }
}

private data class CanvasResource(val stream: InputStream, val mimeType: String)

private fun resolveConversationResource(context: Context, bridge: ChatCanvasNativeBridge?, path: String): CanvasResource? {
    val chunks = path.split('/').filter(String::isNotBlank)
    if (chunks.size < 3 || chunks[0] != "chat-assets") return null
    val eventSeq = chunks.getOrNull(2)?.toLongOrNull() ?: return null
    val event = bridge?.event(eventSeq) ?: return null
    return when (chunks[1]) {
        "message" -> {
            if (chunks.size != 4) return null
            val index = chunks[3].toIntOrNull() ?: return null
            val attachment = event.attachments.getOrNull(index)?.takeIf { it.isImage } ?: return null
            val request = com.jarvys.agent.ui.chat.ChatFileRequest.attachment(bridge.session(), attachment)
            request.requireOwnership(context)
            CanvasResource(FileInputStream(AttachmentStore(context).resolve(bridge.session(), attachment)), attachment.mimeType)
        }
        "generated" -> {
            if (chunks.size != 3 || event.generatedImagePath.isNullOrBlank()) return null
            val request = com.jarvys.agent.ui.chat.ChatFileRequest.generated(bridge.session(), event) ?: return null
            request.requireOwnership(context)
            CanvasResource(FileInputStream(GeneratedImageStore(context).resolve(bridge.session(), request.artifactId)),
                event.generatedImageMimeType ?: "image/png")
        }
        "artifact" -> {
            if (chunks.size != 4) return null
            if (chunks[3] != "thumbnail") return null
            val artifact = event.presentationArtifact ?: return null
            val reference = ChatDatabaseBridge.getInstance(context).findArtifactThumbnailSync(bridge.session(), artifact.id) ?: return null
            if (reference.id != artifact.thumbnailRef) return null
            CanvasResource(FileInputStream(DeliveredArtifactStore(context).resolveThumbnail(bridge.session(), artifact.id, reference)),
                reference.mime_type ?: "image/png")
        }
        else -> null
    }
}
