package com.jarvys.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** No page execution merely to scroll a chat. Pixels are drawn by a bounded local Chromium view. */
internal object HtmlThumbnailRenderer {
    const val MAX_QUEUED = 8
    const val TIMEOUT_MS = 5_000L
    private val renderer = Mutex()
    private val queued = AtomicInteger()

    private val memory = ThumbnailMemoryBudget(HtmlThumbnailStore.MAX_MEMORY_BYTES)

    suspend fun load(context: Context, host: ViewGroup, session: String, descriptor: HtmlPreviewDescriptor,
        viewportWidth: Int, token: CancellationToken): HtmlThumbnailImage? {
        if (queued.incrementAndGet() > MAX_QUEUED) { queued.decrementAndGet(); return null }
        try {
            return renderer.withLock {
                currentCoroutineContext().ensureActive()
                token.throwIfCancelled()
                val width = viewportWidth.coerceIn(1, 1536)
                val height = (width * 2 / 3).coerceAtLeast(1)
                val outputWidth = width.coerceAtMost(768)
                val outputHeight = (outputWidth * 2 / 3).coerceAtLeast(1)
                val reservation = memory.acquire(outputWidth * outputHeight * 4) ?: return@withLock null
                var transferred = false
                var bitmap: Bitmap? = null
                try {
                    val configuration = context.resources.configuration
                    val policy = "static-js-off-v1/$width/$height/${configuration.densityDpi}/${configuration.uiMode}/" +
                        "${configuration.fontScale}/${configuration.locales.toLanguageTags().take(64)}"
                    val store = HtmlThumbnailStore(context.applicationContext)
                    val key = withContext(Dispatchers.IO) { store.prepare(session, descriptor.token, outputWidth, outputHeight, policy) }
                    val cached = withContext(Dispatchers.IO) { store.read(key) }
                    token.throwIfCancelled()
                    if (cached != null) {
                        bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(cached, 0, cached.size) }
                    } else {
                        val content = withContext(Dispatchers.IO) {
                            val checked = DeliveredArtifactStore(context).resolvePreview(session, descriptor.token)
                            check(LocalRunStore(context).findChatFile(session, "delivered", checked.artifactId) != null)
                            WorkspacePreviewContent(context.applicationContext, null, session, checked, thumbnail = true)
                        }
                        bitmap = withContext(Dispatchers.Main.immediate) {
                            render(context, host, content, width, height, outputWidth, outputHeight)
                        }
                        val rendered = bitmap ?: return@withLock null
                        currentCoroutineContext().ensureActive()
                        token.throwIfCancelled()
                        if (withContext(Dispatchers.Default) { isUniformThumbnail(rendered) }) return@withLock null
                        val bytes = withContext(Dispatchers.Default) {
                            val output = object : ByteArrayOutputStream() {
                                override fun write(b: ByteArray, off: Int, len: Int) {
                                    require(count.toLong() + len <= HtmlThumbnailStore.MAX_ENCODED_BYTES)
                                    super.write(b, off, len)
                                }
                                override fun write(b: Int) { require(count < HtmlThumbnailStore.MAX_ENCODED_BYTES); super.write(b) }
                            }
                            check(rendered.compress(Bitmap.CompressFormat.PNG, 100, output))
                            output.toByteArray()
                        }
                        if (!withContext(Dispatchers.IO) { store.write(key, bytes, token) }) return@withLock null
                    }
                    // Cached and freshly rendered images both recheck the owning live generation.
                    if (!withContext(Dispatchers.IO) { store.isCurrent(key) }) return@withLock null
                    token.throwIfCancelled()
                    currentCoroutineContext().ensureActive()
                    val result = bitmap ?: return@withLock null
                    HtmlThumbnailImage(result, reservation).also { transferred = true }
                } finally {
                    if (!transferred) { bitmap?.recycle(); reservation.close() }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) { return null }
        catch (_: Exception) { return null }
        finally { queued.decrementAndGet() }
    }

    private suspend fun render(context: Context, root: ViewGroup, content: WorkspacePreviewContent,
        width: Int, height: Int, outputWidth: Int, outputHeight: Int): Bitmap? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!root.isAttachedToWindow) return null
        val holder = FrameLayout(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isFocusable = false
            // Attached for Chromium's visual-state contract, outside the user's hit/visible area.
            translationX = -(root.width + width + 1).toFloat()
            alpha = 0f
        }
        var web: WebView? = null
        return try {
            withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    var finished = false
                    fun finish(result: Bitmap?) {
                        if (finished || !continuation.isActive) { result?.recycle(); return }
                        finished = true
                        continuation.resume(result)
                    }
                    val view = createStaticThumbnailWebView(context, content, onReady = { ready ->
                        if (continuation.isActive && root.isAttachedToWindow && ready.isAttachedToWindow && !finished) {
                            var bitmap: Bitmap? = null
                            try {
                                bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(bitmap)
                                canvas.drawColor(Color.WHITE)
                                canvas.scale(outputWidth.toFloat() / width, outputHeight.toFloat() / height)
                                ready.draw(canvas)
                                finish(bitmap)
                            } catch (_: OutOfMemoryError) { bitmap?.recycle(); finish(null) }
                            catch (_: Exception) { bitmap?.recycle(); finish(null) }
                        } else finish(null)
                    }, onFailed = { finish(null) })
                    web = view
                    holder.addView(view, FrameLayout.LayoutParams(width, height))
                    root.addView(holder, ViewGroup.LayoutParams(width, height))
                    holder.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    holder.layout(0, 0, width, height)
                    view.loadUrl(content.entryUrl)
                }
            }
        } finally {
            web?.let { view ->
                // A failed provider must not skip detachment or leave the global render slot held.
                runCatching { view.stopLoading() }
                runCatching { view.onPause() }
                runCatching { (view.parent as? ViewGroup)?.removeView(view) }
                runCatching { view.destroy() }
            }
            runCatching { (holder.parent as? ViewGroup)?.removeView(holder) }
        }
    }
}

internal fun createStaticThumbnailWebView(context: Context, content: WorkspacePreviewContent,
    onReady: (WebView) -> Unit, onFailed: () -> Unit): WebView = WebView(context).apply {
    tag = "html-thumbnail-renderer"
    setBackgroundColor(Color.WHITE)
    isFocusable = false; isFocusableInTouchMode = false
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    settings.apply {
        javaScriptEnabled = false
        domStorageEnabled = false
        allowFileAccess = false; allowContentAccess = false
        allowFileAccessFromFileURLs = false; allowUniversalAccessFromFileURLs = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        blockNetworkLoads = true
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        setGeolocationEnabled(false)
        mediaPlaybackRequiresUserGesture = true
        cacheMode = WebSettings.LOAD_NO_CACHE
        offscreenPreRaster = true
        setSupportZoom(false)
    }
    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    webChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            callback?.invoke(origin, false, false)
        }
    }
    webViewClient = HtmlThumbnailClient(content, onReady, onFailed)
}

/** Never returns null: no network fallback, URL delegation, JavaScript, download or storage bridge. */
internal class HtmlThumbnailClient(private val content: WorkspacePreviewContent,
    private val onReady: (WebView) -> Unit, private val onFailed: () -> Unit) : WebViewClient() {
    private var visualRequested = false
    private var failed = false
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
        if (request.method != "GET" && request.method != "HEAD") return denied(405)
        val path = content.localPath(request.url) ?: return denied(403)
        if (request.isForMainFrame && request.url.toString() != content.entryUrl) return denied(403)
        return try {
            val mime = WorkspaceStore.mimeType(path)
            if (mime.contains("javascript")) return denied(403)
            val input = content.open(path)
            if (request.method == "HEAD") input.close()
            WebResourceResponse(mime, if (mime.startsWith("text/") || mime.contains("svg")) "UTF-8" else null,
                200, "OK", mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff", "X-DNS-Prefetch-Control" to "off",
                    "Content-Security-Policy" to "default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; " +
                        "font-src 'self' data:; script-src 'none'; connect-src 'none'; object-src 'none'; " +
                        "frame-src 'none'; base-uri 'none'; form-action 'none'; worker-src 'none'; media-src 'none'"),
                if (request.method == "HEAD") ByteArrayInputStream(byteArrayOf()) else input)
        } catch (_: Exception) { denied(404) }
    }
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean = true
    @Deprecated("Required for older WebView callbacks")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String): Boolean = true
    override fun onPageFinished(view: WebView, url: String) {
        if (visualRequested || failed || url != content.entryUrl) return
        visualRequested = true
        view.postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) { if (!failed) onReady(view) }
        })
    }
    override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError?) {
        if (request.isForMainFrame) fail()
    }
    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest, errorResponse: WebResourceResponse?) {
        if (request.isForMainFrame) fail()
    }
    @RequiresApi(26)
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean { fail(); return true }
    private fun fail() { if (!failed) { failed = true; onFailed() } }
    private fun denied(status: Int) = WebResourceResponse("text/plain", "UTF-8", status,
        when (status) { 405 -> "Method Not Allowed"; 404 -> "Not Found"; else -> "Forbidden" },
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(byteArrayOf()))
}

/** Blank output is an unavailable preview, never evidence of a successfully rendered application. */
internal fun isUniformThumbnail(bitmap: Bitmap): Boolean {
    val row = IntArray(bitmap.width)
    val first = bitmap.getPixel(0, 0)
    for (y in 0 until bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        if (row.any { it != first }) return false
    }
    return true
}


/** Reservation follows the displayed image, not just its short-lived render request. */
internal class ThumbnailMemoryBudget(private val maximum: Int) {
    private val retained = AtomicInteger()
    val retainedBytes: Int get() = retained.get()
    fun acquire(bytes: Int): Reservation? {
        if (bytes <= 0 || bytes > maximum) return null
        while (true) {
            val before = retained.get()
            if (before.toLong() + bytes > maximum) return null
            if (retained.compareAndSet(before, before + bytes)) return Reservation(bytes)
        }
    }
    inner class Reservation internal constructor(private val bytes: Int) : AutoCloseable {
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) retained.addAndGet(-bytes) }
    }
}

internal class HtmlThumbnailImage(val bitmap: Bitmap, private val reservation: ThumbnailMemoryBudget.Reservation) : AutoCloseable {
    // Compose may still have an in-flight frame, so do not manually recycle a published bitmap.
    override fun close() { reservation.close() }
}
