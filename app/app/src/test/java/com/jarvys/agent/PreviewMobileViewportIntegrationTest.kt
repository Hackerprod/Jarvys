package com.jarvys.agent

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real interceptor and native configuration boundaries; no Chromium layout/IME/zoom simulation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class])
class PreviewMobileViewportIntegrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val html = "<!doctype html><html><head><meta charset=utf-8><style>@media(max-width:600px){main{display:block}}table{min-width:1000px}</style></head><body><main><input><button>Tap</button><a href='next.html#part'>Next</a></main></body></html>"

    @Test @Config(sdk = [24, 26, 34])
    fun settingsHonorViewportWithoutOverviewUaScaleOrZoomOverrides() {
        val original = WebView(context)
        val defaultUa = original.settings.userAgentString
        val defaultTextZoom = original.settings.textZoom
        original.destroy()
        withFixture { f, web ->
            assertTrue(web.settings.useWideViewPort)
            assertFalse(web.settings.loadWithOverviewMode)
            assertEquals(defaultUa, web.settings.userAgentString)
            assertEquals(defaultTextZoom, web.settings.textZoom)
            assertTrue(web.settings.supportZoom()); assertTrue(web.settings.builtInZoomControls)
            assertFalse(web.settings.displayZoomControls)
            assertTrue(web.settings.javaScriptEnabled); assertTrue(web.settings.domStorageEnabled)
            assertTrue(web.settings.blockNetworkLoads)
            assertFalse(web.settings.allowFileAccess); assertFalse(web.settings.allowContentAccess)
            assertEquals(PreviewResponsePolicy.interactiveHeaders(), response(web, f.url).responseHeaders)
        }
    }
    @Test fun liveDocumentAdaptsButSourceAndSubresourceReadsAreExact() {
        withFixture { f, web ->
            val original = f.workspace.read("index.html")
            val bytes = html.toByteArray()
            val digest = sha(bytes)
            assertEquals(html.replace("<head>", "<head>${PreviewMobileViewport.META}"), body(web, f.url))
            assertArrayEquals(bytes, response(web, f.url, main = false).data.use { it.readBytes() })
            assertEquals(original, f.workspace.read("index.html"))
            assertEquals(digest, f.content.open("index.html").use { sha(it.readBytes()) })
        }
    }
    @Test fun capturedDocumentAdaptsWithoutChangingSnapshotHashOrItsManifestBoundary() {
        withFixture(snapshot = true) { f, web ->
            val original = f.content.open("index.html").use { it.readBytes() }
            f.workspace.write("index.html", "<p>Changed outside snapshot</p>")
            f.workspace.write("private.html", "<p>Not captured</p>")
            assertEquals(html.replace("<head>", "<head>${PreviewMobileViewport.META}"), body(web, f.url))
            assertArrayEquals(original, f.content.open("index.html").use { it.readBytes() })
            assertArrayEquals(original, response(web, f.url, main = false).data.use { it.readBytes() })
            assertEquals(404, response(web, f.url.replace("index.html", "private.html")).statusCode)
        }
    }
    @Test fun benignInlineSvgRemainsMobileInLiveAndCapturedDocuments() {
        val document = "<!doctype html><head><meta charset=utf-8></head><body><svg><title>Logo</title><path d='M 0 0'/></svg><input></body>"
        for (snapshot in listOf(false, true)) withFixture(snapshot, document) { f, web ->
            assertEquals(document.replace("<head>", "<head>${PreviewMobileViewport.META}"), body(web, f.url))
            assertEquals(document, f.content.open("index.html").use { String(it.readBytes()) })
            assertEquals(PreviewResponsePolicy.interactiveHeaders(), response(web, f.url).responseHeaders)
        }
    }
    @Test fun everyNavigationGetsItsOwnDefaultWithoutChangingAuthoredPages() {
        withFixture { f, web ->
            f.workspace.write("next.html", "<!doctype html><h1 id=part>Next</h1>")
            f.workspace.write("desktop.html", "<head><meta name=viewport content='width=1280, initial-scale=.5'></head><p>Desktop</p>")
            val next = f.url.replace("index.html", "next.html")
            assertEquals("<!doctype html>${PreviewMobileViewport.META}<h1 id=part>Next</h1>", body(web, next))
            assertEquals("<head><meta name=viewport content='width=1280, initial-scale=.5'></head><p>Desktop</p>",
                body(web, f.url.replace("index.html", "desktop.html")))
            assertFalse(web.webViewClient.shouldOverrideUrlLoading(web, Request("$next#part")))
            assertEquals(html.replace("<head>", "<head>${PreviewMobileViewport.META}"), body(web, f.url))
        }
    }
    @Test fun headAndAssetResponsesRetainBytesMimeAndExactSecurityHeaders() {
        withFixture { f, web ->
            f.workspace.write("style.css", "body{color:red}")
            f.workspace.write("module.mjs", "export const width=window.innerWidth;")
            for ((name, expected) in listOf("style.css" to "body{color:red}", "module.mjs" to "export const width=window.innerWidth;")) {
                val resource = response(web, f.url.replace("index.html", name))
                assertEquals(expected, resource.data.use { String(it.readBytes()) })
                assertEquals(WorkspaceStore.mimeType(name), resource.mimeType)
                assertEquals(PreviewResponsePolicy.interactiveHeaders(), resource.responseHeaders)
            }
            val head = response(web, f.url, verb = "HEAD")
            assertEquals(200, head.statusCode); assertEquals("text/html", head.mimeType)
            assertEquals("UTF-8", head.encoding); assertEquals(0, head.data.use { it.readBytes().size })
            assertEquals(PreviewResponsePolicy.interactiveHeaders(), head.responseHeaders)
        }
    }
    @Test fun adaptationDoesNotAlterAnyDenialOrAllowExternalOrigin() {
        withFixture { f, web ->
            for ((url, verb, code) in listOf(Triple(f.url, "POST", 405), Triple("https://outside.invalid/", "GET", 403),
                Triple(f.url.replace("index.html", "%2e%2e/private.html"), "GET", 403),
                Triple(f.url.replace("index.html", "missing.html"), "GET", 404))) {
                val denied = response(web, url, verb)
                assertEquals(code, denied.statusCode)
                assertEquals(PreviewResponsePolicy.interactiveHeaders(), denied.responseHeaders)
                assertFalse(denied.data.use { String(it.readBytes()) }.contains(PreviewMobileViewport.META))
            }
        }
    }
    @Test fun thumbnailRemainsUnadaptedAndJavascriptDisabled() {
        val f = fixture(snapshot = true)
        val web = createStaticThumbnailWebView(context, f.content, {}, {})
        try {
            assertEquals(html, body(web, f.url))
            assertFalse(web.settings.javaScriptEnabled); assertFalse(web.settings.domStorageEnabled)
            assertEquals(PreviewResponsePolicy.thumbnailHeaders(), response(web, f.url).responseHeaders)
        } finally { web.destroy() }
    }
    @Test @Config(qualifiers = "w320dp-h640dp-hdpi")
    fun compactDensityDoesNotIntroduceHardcodedPageWidthOrScale() = checkWindowContract()
    @Test @Config(qualifiers = "w840dp-h400dp-xhdpi")
    fun wideDenseWindowDoesNotOverrideAuthoredContentOrTextZoom() {
        RuntimeEnvironment.setFontScale(2f)
        try { checkWindowContract() } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    private fun checkWindowContract() = withFixture { f, web ->
        val baseline = WebView(context)
        try {
            assertEquals(baseline.settings.textZoom, web.settings.textZoom)
            assertTrue(web.settings.useWideViewPort); assertFalse(web.settings.loadWithOverviewMode)
            assertEquals(html.replace("<head>", "<head>${PreviewMobileViewport.META}"), body(web, f.url))
        } finally { baseline.destroy() }
    }
    private fun body(web: WebView, url: String) = response(web, url).data.use { String(it.readBytes()) }
    private fun response(web: WebView, url: String, verb: String = "GET", main: Boolean = true) =
        requireNotNull(shadowOf(web).webViewClient.shouldInterceptRequest(web, Request(url, verb, main)))
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()
    private data class Fixture(val workspace: WorkspaceStore, val content: WorkspacePreviewContent) {
        val url get() = content.entryUrl
    }
    private fun fixture(snapshot: Boolean = false, document: String = html): Fixture {
        val session = "ux41-${UUID.randomUUID()}"
        val workspace = WorkspaceStore(File(context.filesDir, "jarvys/workspaces"), WorkspaceStore.projectIdForSession(session),
            null, null, null, session, false)
        workspace.write("index.html", document)
        val content = if (snapshot) {
            val store = DeliveredArtifactStore(context)
            val attachment = store.snapshot(session, workspace, "index.html", null, CancellationToken.uncancellable())
            LocalRunStore(context).appendDeliveredFile(session, attachment)
            WorkspacePreviewContent(context, null, session, requireNotNull(store.previewForAttachment(session, attachment)))
        } else WorkspacePreviewContent(context, workspace)
        return Fixture(workspace, content)
    }
    private fun withFixture(snapshot: Boolean = false, document: String = html, action: (Fixture, WebView) -> Unit) {
        val fixture = fixture(snapshot, document)
        val state = PreviewPageState()
        val web = createPreviewWebView(context, fixture.content, state)
        try { action(fixture, web) } finally { state.release(web); web.destroy() }
    }
    private class Request(url: String, private val verb: String = "GET", private val main: Boolean = true) : WebResourceRequest {
        private val uri = Uri.parse(url)
        override fun getUrl(): Uri = uri
        override fun getMethod() = verb
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
