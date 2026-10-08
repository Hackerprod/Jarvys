package com.jarvys.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.fakes.RoboWebSettings
import org.robolectric.shadows.ShadowWebView

/**
 * Actual WebView settings and request-client policy, exercised with immutable delivered fixtures.
 * The visual-state recorder checks callback ordering only. Robolectric does not execute Chromium;
 * source-image and uniform-pixel fixtures are not proof that a web page rendered successfully.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class])
class HtmlThumbnailRendererTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(shadows = [HtmlThumbnailSettingsShadow::class, ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class])
    fun staticWebViewDisablesExecutionStorageNetworkAndDeviceAccess() {
        val fixture = fixture()
        val web = createStaticThumbnailWebView(context, fixture.content, {}, {})
        try {
            with(web.settings) {
                assertFalse(javaScriptEnabled)
                assertFalse(domStorageEnabled)
                assertFalse(allowFileAccess)
                assertFalse(allowContentAccess)
                assertFalse(allowFileAccessFromFileURLs)
                assertFalse(allowUniversalAccessFromFileURLs)
                assertTrue("No network fallback is allowed", blockNetworkLoads)
                assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, mixedContentMode)
                assertFalse(javaScriptCanOpenWindowsAutomatically)
                assertFalse(supportMultipleWindows())
                assertTrue(mediaPlaybackRequiresUserGesture)
                assertEquals(WebSettings.LOAD_NO_CACHE, cacheMode)
                assertEquals("Production requests prerasterization; RoboWebSettings 4.16 has a no-op setter and false getter",
                    listOf(true), (this as HtmlThumbnailRecordingSettings).offscreenPreRasterWrites)
                assertFalse(supportZoom())
            }
            assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(web))
            assertFalse(web.isFocusable)
            assertFalse(web.isFocusableInTouchMode)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, web.importantForAccessibility)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, web.importantForAutofill)
            assertEquals("html-thumbnail-renderer", web.tag)
            assertTrue(web.webViewClient is HtmlThumbnailClient)
        } finally { web.destroy() }
    }

    @Test fun permissionAndGeolocationCallbacksAlwaysDenyWithoutRememberingGrants() {
        val fixture = fixture()
        val web = createStaticThumbnailWebView(context, fixture.content, {}, {})
        try {
            val request = RecordingPermissionRequest(Uri.parse(fixture.content.entryUrl))
            requireNotNull(web.webChromeClient).onPermissionRequest(request)
            assertEquals(1, request.denials)
            assertEquals(0, request.grants)
            var location: Triple<String?, Boolean, Boolean>? = null
            requireNotNull(web.webChromeClient).onGeolocationPermissionsShowPrompt(fixture.content.host,
                GeolocationPermissions.Callback { origin, allowed, remember ->
                    location = Triple(origin, allowed, remember)
                })
            assertEquals(Triple(fixture.content.host, false, false), location)
            // No user prompt or remembered permission should be needed for a nullable callback.
            requireNotNull(web.webChromeClient).onGeolocationPermissionsShowPrompt(null, null)
        } finally { web.destroy() }
    }

    @Test fun localHtmlCssAndImageResponsesUseImmutableDeliveredBytesAndRestrictiveHeaders() {
        val fixture = fixture()
        fixture.workspace.write(fixture.attachment.name, "<!doctype html><h1>Changed workspace</h1>")
        fixture.workspace.write("styles.css", "body { background: red; }")
        fixture.workspace.resolvePreviewPath("pixel.png").writeBytes(byteArrayOf(1, 2, 3))
        val web = createStaticThumbnailWebView(context, fixture.content, {}, {})
        try {
            for ((path, bytes) in fixture.staticResources) {
                val response = intercept(web, fixture.url(path), mainFrame = path == fixture.attachment.name)
                assertEquals(path, 200, response.statusCode)
                assertArrayEquals("$path is read from the immutable delivered snapshot", bytes, response.data.use { it.readBytes() })
                assertEquals("no-store", response.responseHeaders["Cache-Control"])
                assertEquals("nosniff", response.responseHeaders["X-Content-Type-Options"])
                assertEquals("off", response.responseHeaders["X-DNS-Prefetch-Control"])
                val csp = requireNotNull(response.responseHeaders["Content-Security-Policy"])
                for (rule in listOf("default-src 'none'", "script-src 'none'", "connect-src 'none'",
                    "object-src 'none'", "frame-src 'none'", "base-uri 'none'", "form-action 'none'",
                    "worker-src 'none'", "media-src 'none'", "img-src 'self' data:", "style-src 'self' 'unsafe-inline'")) {
                    assertTrue("$path must carry $rule", csp.contains(rule))
                }
                assertEquals(WorkspaceStore.mimeType(path), response.mimeType)
                assertEquals(if (path.endsWith(".png")) null else "UTF-8", response.encoding)
            }
        } finally { web.destroy() }
    }

    @Test fun headRequestsKeepMetadataButNeverReturnBodyBytes() {
        val fixture = fixture()
        val web = createStaticThumbnailWebView(context, fixture.content, {}, {})
        try {
            for (path in fixture.staticResources.keys) {
                val response = intercept(web, fixture.url(path), "HEAD", mainFrame = path == fixture.attachment.name)
                assertEquals(200, response.statusCode)
                assertEquals("no-store", response.responseHeaders["Cache-Control"])
                assertEquals(0, response.data.use { it.readBytes().size })
            }
        } finally { web.destroy() }
    }

    @Test fun capturedJavascriptAndModulesAreNeverServedByTheThumbnailClient() {
        val fixture = fixture()
        // Prove that denial is the thumbnail policy, rather than an absent snapshot member.
        val store = DeliveredArtifactStore(context)
        for ((path, body) in fixture.scripts) {
            assertEquals(body, store.openPreview(fixture.session, fixture.descriptor.token, path).bufferedReader().use { it.readText() })
        }
        val web = createStaticThumbnailWebView(context, fixture.content, {}, {})
        try {
            for (path in fixture.scripts.keys) {
                for (method in listOf("GET", "HEAD")) assertDenied(intercept(web, fixture.url(path), method), 403)
            }
        } finally { web.destroy() }
    }

    @Test fun externalTraversalEncodedAuthoritiesAndMutatingMethodsCannotEscapeTheSnapshot() {
        val fixture = fixture()
        val web = createStaticThumbnailWebView(context, fixture.content, {}, {})
        try {
            val host = fixture.content.host
            for (url in listOf(
                "https://outside.invalid/styles.css", "http://$host/styles.css", "file:///private.txt",
                "content://private/files/secret", "data:text/html,secret", "javascript:alert(1)",
                "https://$host:443/styles.css", "https://user@$host/styles.css", "https://$host.evil.invalid/styles.css",
                "https://$host/../private.txt", "https://$host/%2e%2e/private.txt", "https://$host/%2E%2E/private.txt",
                "https://$host/assets%2fprivate.txt", "https://$host/assets%5Cprivate.txt", "https://$host/%00private.txt",
                "https://$host/%252e%252e/private.txt",
            )) assertDenied(intercept(web, url), 403)
            for (method in listOf("POST", "PUT", "DELETE", "PATCH", "OPTIONS", "get")) {
                assertDenied(intercept(web, fixture.content.entryUrl, method, mainFrame = true), 405)
            }
            assertDenied(intercept(web, fixture.url("not-captured.css")), 404)
            // An ordinary workspace file that was never captured remains unavailable as well.
            fixture.workspace.write("private.txt", "Private uncaptured workspace data")
            assertDenied(intercept(web, fixture.url("private.txt")), 404)
        } finally { web.destroy() }
    }

    @Test fun onlyTheExactEntryUrlMayLoadAsAMainFrame() {
        val fixture = fixture()
        val web = createStaticThumbnailWebView(context, fixture.content, {}, {})
        try {
            val entry = intercept(web, fixture.content.entryUrl, mainFrame = true)
            assertEquals(200, entry.statusCode)
            entry.data.close()
            for (url in listOf(fixture.url("styles.css"), fixture.url("pixel.png"),
                fixture.content.entryUrl + "?changed=true", fixture.content.entryUrl + "#fragment")) {
                assertDenied(intercept(web, url, mainFrame = true), 403)
            }
        } finally { web.destroy() }
    }

    @Suppress("DEPRECATION")
    @Test fun everyNavigationIsConsumedIncludingSamePageLinksAndExternalIntents() {
        val fixture = fixture()
        val web = createStaticThumbnailWebView(context, fixture.content, {}, {})
        try {
            for (url in listOf(fixture.content.entryUrl, fixture.content.entryUrl + "#heading", fixture.url("details.html"),
                "https://outside.invalid/", "mailto:person@example.invalid", "tel:123", "intent://outside", "javascript:alert(1)")) {
                assertTrue(url, web.webViewClient.shouldOverrideUrlLoading(web, Request(url, mainFrame = true)))
                assertTrue(url, web.webViewClient.shouldOverrideUrlLoading(web, url))
            }
        } finally { web.destroy() }
    }

    @Test fun thumbnailOriginsAreSeparateFromInteractivePreviewAndOtherConversations() {
        val fixture = fixture()
        val interactive = WorkspacePreviewContent(context, null, fixture.session, fixture.descriptor)
        val other = WorkspacePreviewContent(context, null, "another-conversation", fixture.descriptor, thumbnail = true)
        assertNotEquals(interactive.host, fixture.content.host)
        assertNotEquals(other.host, fixture.content.host)
        assertTrue(fixture.content.host.startsWith("jarvys-thumbnail-"))
        assertTrue(interactive.host.startsWith("jarvys-preview-"))
        assertEquals(fixture.content.host,
            WorkspacePreviewContent(context, null, fixture.session, fixture.descriptor, thumbnail = true).host)
        val web = createStaticThumbnailWebView(context, fixture.content, {}, {})
        try {
            assertDenied(intercept(web, interactive.entryUrl, mainFrame = true), 403)
            assertDenied(intercept(web, other.entryUrl, mainFrame = true), 403)
        } finally { web.destroy() }
        val otherWeb = createStaticThumbnailWebView(context, other, {}, {})
        try { assertDenied(intercept(otherWeb, other.entryUrl, mainFrame = true), 404) }
        finally { otherWeb.destroy() }
    }

    @Test fun readyRequiresTheMatchingEntryAndOneVisualStateCallback() {
        val fixture = fixture()
        val ready = mutableListOf<WebView>()
        var failures = 0
        val client = HtmlThumbnailClient(fixture.content, ready::add) { failures++ }
        val web = VisualStateRecorder(context)
        try {
            client.onPageFinished(web, fixture.url("styles.css"))
            assertEquals(0, web.visualRequests.size)
            client.onPageFinished(web, fixture.content.entryUrl)
            client.onPageFinished(web, fixture.content.entryUrl)
            assertEquals("A repeated page-finished event cannot request another capture", 1, web.visualRequests.size)
            assertTrue("Page completion alone is not evidence that pixels are ready", ready.isEmpty())
            web.completeVisualState()
            assertEquals(listOf(web), ready)
            assertEquals(0, failures)
        } finally { web.destroy() }
    }

    @Test fun subresourceErrorsDoNotFailThePageButMainFrameFailureSuppressesPendingReadiness() {
        val fixture = fixture()
        val ready = mutableListOf<WebView>()
        var failures = 0
        val client = HtmlThumbnailClient(fixture.content, ready::add) { failures++ }
        val web = VisualStateRecorder(context)
        try {
            client.onReceivedError(web, Request(fixture.url("missing.css")), null)
            client.onReceivedHttpError(web, Request(fixture.url("missing.png")), null)
            assertEquals(0, failures)
            client.onPageFinished(web, fixture.content.entryUrl)
            assertEquals(1, web.visualRequests.size)
            client.onReceivedError(web, Request(fixture.content.entryUrl, mainFrame = true), null)
            client.onReceivedHttpError(web, Request(fixture.content.entryUrl, mainFrame = true), null)
            assertTrue(client.onRenderProcessGone(web, null))
            assertEquals("Multiple failure callbacks report one failed render", 1, failures)
            web.completeVisualState()
            assertTrue("A failed render must not deliver pending pixels", ready.isEmpty())
            client.onPageFinished(web, fixture.content.entryUrl)
            assertEquals(1, web.visualRequests.size)
        } finally { web.destroy() }
    }

    @Test fun earlyRendererFailureNeverRequestsVisualReadiness() {
        val fixture = fixture()
        var failures = 0
        val client = HtmlThumbnailClient(fixture.content, { fail("Failure cannot become a successful render") }) { failures++ }
        val web = VisualStateRecorder(context)
        try {
            assertTrue(client.onRenderProcessGone(web, null))
            client.onPageFinished(web, fixture.content.entryUrl)
            assertEquals(1, failures)
            assertTrue(web.visualRequests.isEmpty())
        } finally { web.destroy() }
    }

    @Test fun uniformPixelAlgorithmRejectsBlankAndSolidColorOutput() {
        for (color in listOf(Color.WHITE, Color.BLACK, Color.TRANSPARENT, Color.argb(255, 12, 34, 56))) {
            val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(color); assertTrue(isUniformThumbnail(bitmap)) }
            finally { bitmap.recycle() }
        }
    }

    @Test fun uniformPixelAlgorithmDetectsContentEvenAtTheLastPixel() {
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            bitmap.setPixel(23, 15, Color.BLACK)
            assertFalse("Every pixel is checked, including the bottom-right corner", isUniformThumbnail(bitmap))
            bitmap.setPixel(23, 15, Color.WHITE)
            bitmap.setPixel(12, 8, Color.BLACK)
            assertFalse("Interior detail must also avoid the blank-output fallback", isUniformThumbnail(bitmap))
        } finally { bitmap.recycle() }
    }

    @Test fun memoryBudgetRejectsZeroNegativeAndOversizedReservations() {
        val budget = ThumbnailMemoryBudget(1024)
        for (bytes in listOf(0, -1, Int.MIN_VALUE, 1025, Int.MAX_VALUE)) assertNull(budget.acquire(bytes))
        assertEquals(0, budget.retainedBytes)
    }

    @Test fun memoryBudgetAllowsExactCapacityAndReusesIdempotentlyReleasedBytes() {
        val budget = ThumbnailMemoryBudget(1024)
        val first = requireNotNull(budget.acquire(256))
        val second = requireNotNull(budget.acquire(768))
        assertEquals(1024, budget.retainedBytes)
        assertNull(budget.acquire(1))
        first.close()
        first.close()
        assertEquals("Double-close cannot release somebody else's reservation", 768, budget.retainedBytes)
        assertNull(budget.acquire(257))
        val replacement = requireNotNull(budget.acquire(256))
        assertEquals(1024, budget.retainedBytes)
        second.close()
        replacement.close()
        assertEquals(0, budget.retainedBytes)
    }

    @Test fun memoryBudgetChecksCapacityWithoutIntegerOverflow() {
        val budget = ThumbnailMemoryBudget(Int.MAX_VALUE)
        val almostFull = requireNotNull(budget.acquire(Int.MAX_VALUE - 1))
        assertNull(budget.acquire(2))
        val lastByte = requireNotNull(budget.acquire(1))
        assertEquals(Int.MAX_VALUE, budget.retainedBytes)
        assertNull(budget.acquire(Int.MAX_VALUE))
        almostFull.close()
        lastByte.close()
        assertEquals(0, budget.retainedBytes)
    }

    @Test fun concurrentReservationsNeverExceedTheSharedMemoryCap() {
        val budget = ThumbnailMemoryBudget(4096)
        val workers = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val attempted = CountDownLatch(16)
        val release = CountDownLatch(1)
        val admitted = ConcurrentLinkedQueue<ThumbnailMemoryBudget.Reservation>()
        try {
            val results = (0 until 16).map {
                workers.submit<Boolean> {
                    check(start.await(10, TimeUnit.SECONDS))
                    val reservation = budget.acquire(512)
                    reservation?.let(admitted::add)
                    attempted.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    reservation?.close()
                    reservation?.close()
                    reservation != null
                }
            }
            start.countDown()
            assertTrue(attempted.await(10, TimeUnit.SECONDS))
            assertEquals("Only eight simultaneous 512-byte reservations fit", 8, admitted.size)
            assertEquals(4096, budget.retainedBytes)
            release.countDown()
            assertEquals(8, results.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(0, budget.retainedBytes)
        } finally {
            start.countDown()
            release.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
            admitted.forEach { it.close() }
        }
    }

    @Test fun concurrentCloseOfOneReservationCannotUnderflowTheBudget() {
        val budget = ThumbnailMemoryBudget(1024)
        val reservation = requireNotNull(budget.acquire(1024))
        val workers = Executors.newFixedThreadPool(8)
        try {
            val results = (0 until 64).map { workers.submit { reservation.close() } }
            results.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(0, budget.retainedBytes)
            requireNotNull(budget.acquire(1024)).close()
            assertEquals(0, budget.retainedBytes)
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
            reservation.close()
        }
    }

    @Test fun closingAPublishedImageReleasesItsLeaseWithoutRecyclingAnInflightFrame() {
        val budget = ThumbnailMemoryBudget(48)
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        val image = HtmlThumbnailImage(bitmap, requireNotNull(budget.acquire(48)))
        try {
            assertSame(bitmap, image.bitmap)
            image.close()
            image.close()
            assertEquals(0, budget.retainedBytes)
            assertFalse("Compose may still be drawing the published bitmap", bitmap.isRecycled)
        } finally { image.close(); bitmap.recycle() }
    }

    private data class Fixture(val session: String, val workspace: WorkspaceStore, val attachment: ChatAttachment,
        val descriptor: HtmlPreviewDescriptor, val content: WorkspacePreviewContent,
        val staticResources: Map<String, ByteArray>, val scripts: Map<String, String>) {
        fun url(path: String) = "https://${content.host}/" + path.split('/').joinToString("/") { Uri.encode(it) }
    }

    private fun fixture(): Fixture {
        val session = "ux28-thumbnail-renderer-${UUID.randomUUID()}"
        val workspace = WorkspaceStore(File(context.filesDir, "jarvys/workspaces"),
            WorkspaceStore.projectIdForSession(session), null, null, null, session, false)
        val name = "garden-report.html"
        val html = """
            <!doctype html><html><head><link rel="stylesheet" href="styles.css">
            <script src="app.js"></script><script type="module" src="module.mjs"></script></head>
            <body><h1>Original immutable garden</h1><img src="pixel.png" alt="Source asset"></body></html>
        """.trimIndent()
        val css = "body { color: #14532d; background: white; }"
        val scripts = linkedMapOf("app.js" to "window.shouldNotExecute = true;", "module.mjs" to "export const blocked = true;")
        workspace.write(name, html)
        workspace.write("styles.css", css)
        scripts.forEach { (path, body) -> workspace.write(path, body) }
        workspace.write("pixel.png", "staged")
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        try {
            workspace.resolvePreviewPath("pixel.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val png = workspace.resolvePreviewPath("pixel.png").readBytes()
        val store = DeliveredArtifactStore(context)
        val attachment = store.snapshot(session, workspace, name, null, CancellationToken.uncancellable())
        LocalRunStore(context).appendDeliveredFile(session, attachment)
        val descriptor = requireNotNull(store.previewForAttachment(session, attachment))
        assertEquals(5, descriptor.fileCount)
        return Fixture(session, workspace, attachment, descriptor,
            WorkspacePreviewContent(context, null, session, descriptor, thumbnail = true),
            linkedMapOf(name to html.toByteArray(), "styles.css" to css.toByteArray(), "pixel.png" to png), scripts)
    }

    private fun intercept(web: WebView, url: String, method: String = "GET", mainFrame: Boolean = false): WebResourceResponse =
        requireNotNull(web.webViewClient.shouldInterceptRequest(web, Request(url, method, mainFrame)))

    private fun assertDenied(response: WebResourceResponse, status: Int) {
        assertEquals(status, response.statusCode)
        assertEquals("no-store", response.responseHeaders["Cache-Control"])
        assertEquals("Denied requests have no body or fallback payload", 0, response.data.use { it.readBytes().size })
    }

    private class Request(private val value: String, private val verb: String = "GET",
        private val mainFrame: Boolean = false) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(value)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = verb
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private class RecordingPermissionRequest(private val requestOrigin: Uri) : PermissionRequest() {
        var grants = 0
        var denials = 0
        override fun getOrigin(): Uri = requestOrigin
        override fun getResources(): Array<String> = arrayOf(RESOURCE_AUDIO_CAPTURE, RESOURCE_VIDEO_CAPTURE,
            RESOURCE_PROTECTED_MEDIA_ID, RESOURCE_MIDI_SYSEX)
        override fun grant(resources: Array<out String>?) { grants++ }
        override fun deny() { denials++ }
    }

    private class VisualStateRecorder(context: Context) : WebView(context) {
        val visualRequests = mutableListOf<Pair<Long, VisualStateCallback>>()
        override fun postVisualStateCallback(requestId: Long, callback: VisualStateCallback) {
            visualRequests += requestId to callback
        }
        fun completeVisualState() { visualRequests.single().let { (id, callback) -> callback.onComplete(id) } }
    }
}

/** Preserves RoboWebSettings behavior; records only the currently unimplemented preraster API. */
class HtmlThumbnailRecordingSettings : RoboWebSettings() {
    val offscreenPreRasterWrites = mutableListOf<Boolean>()
    override fun setOffscreenPreRaster(enabled: Boolean) {
        offscreenPreRasterWrites += enabled
        super.setOffscreenPreRaster(enabled)
    }
}

/** Used only by the actual WebView factory-settings test, never to supply rendered pixels. */
@Implements(WebView::class)
class HtmlThumbnailSettingsShadow : ShadowWebView() {
    private val recordingSettings = HtmlThumbnailRecordingSettings()
    @Implementation override fun getSettings(): WebSettings = recordingSettings
}
