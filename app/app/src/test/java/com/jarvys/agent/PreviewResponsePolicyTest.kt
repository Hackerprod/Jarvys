package com.jarvys.agent

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Native response/settings contracts only. Robolectric does not execute Chromium or observe ICE. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class])
class PreviewResponsePolicyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val interactiveCsp = "default-src 'self' data: blob:; " +
        "img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; " +
        "script-src 'self' 'unsafe-inline'; connect-src 'none'; " +
        "object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'; " +
        "worker-src 'none'; child-src 'none'"
    private val thumbnailCsp = "default-src 'none'; img-src 'self' data:; " +
        "style-src 'self' 'unsafe-inline'; font-src 'self' data:; script-src 'none'; " +
        "connect-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'; " +
        "form-action 'none'; worker-src 'none'; media-src 'none'; child-src 'none'"

    private fun expected(csp: String) = mapOf(
        "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
        "X-DNS-Prefetch-Control" to "off", "Content-Security-Policy" to csp,
        "Connection-Allowlist" to "(response-origin); webrtc=block; redirects=block",
    )

    @Test fun interactivePolicyHasExactNativeHeaderWithoutChangingOrdinaryScriptSources() {
        assertEquals(expected(interactiveCsp), PreviewResponsePolicy.interactiveHeaders())
    }

    @Test fun thumbnailPolicyRetainsItsStricterScriptAndMediaRestrictions() {
        assertEquals(expected(thumbnailCsp), PreviewResponsePolicy.thumbnailHeaders())
    }

    @Test fun modifyingOneResponseCannotRelaxLaterResponsesOrTheOtherRenderer() {
        val first = PreviewResponsePolicy.interactiveHeaders()
        @Suppress("UNCHECKED_CAST")
        (first as MutableMap<String, String>)["Connection-Allowlist"] = "(*) ; webrtc=allow"
        assertEquals(expected(interactiveCsp), PreviewResponsePolicy.interactiveHeaders())
        assertEquals(expected(thumbnailCsp), PreviewResponsePolicy.thumbnailHeaders())
        assertNotSame(PreviewResponsePolicy.thumbnailHeaders(), PreviewResponsePolicy.thumbnailHeaders())
    }

    @Test
    @Config(sdk = [24, 26, 34])
    fun headersAndInteractiveSettingsDoNotRequireANewerAndroidApiOrDisableJavascript() {
        val fixture = fixture()
        val web = createPreviewWebView(context, fixture.content, PreviewPageState())
        try {
            with(web.settings) {
                assertTrue(javaScriptEnabled); assertTrue(domStorageEnabled); assertTrue(blockNetworkLoads)
                assertFalse(allowFileAccess); assertFalse(allowContentAccess)
                assertFalse(allowFileAccessFromFileURLs); assertFalse(allowUniversalAccessFromFileURLs)
                assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, mixedContentMode)
                assertFalse(javaScriptCanOpenWindowsAutomatically); assertFalse(supportMultipleWindows())
                assertTrue(supportZoom()); assertTrue(builtInZoomControls); assertFalse(displayZoomControls)
            }
            // Inspect the pinned test shadow's recorder, never an app/provider private API.
            val bridges = org.robolectric.shadows.ShadowWebView::class.java
                .getDeclaredField("javascriptInterfaces").apply { isAccessible = true }
                .get(shadowOf(web)) as Map<*, *>
            assertTrue(bridges.isEmpty())
            assertEquals(expected(interactiveCsp), PreviewResponsePolicy.interactiveHeaders())
        } finally { web.destroy() }
    }

    @Test fun interactiveHtmlCssJavascriptModulesAndImagesKeepExactBytesAndHeaders() {
        val f = fixture()
        withWeb(f) { web ->
            for ((path, bytes) in f.resources) {
                val response = request(web, f.url(path))
                assertEquals(path, 200, response.statusCode)
                assertEquals(expected(interactiveCsp), response.responseHeaders)
                assertArrayEquals(path, bytes, response.data.use { it.readBytes() })
                assertEquals(WorkspaceStore.mimeType(path), response.mimeType)
            }
        }
    }

    @Test fun headHasIdenticalHeadersAndNoBody() {
        val f = fixture()
        withWeb(f) { web ->
            for (path in f.resources.keys) {
                val response = request(web, f.url(path), "HEAD")
                assertEquals(200, response.statusCode)
                assertEquals(expected(interactiveCsp), response.responseHeaders)
                assertEquals(0, response.data.use { it.readBytes().size })
            }
        }
    }

    @Test fun allDeniedOriginsAndSchemesKeepHeadersAndNeverFallThrough() {
        val f = fixture()
        withWeb(f) { web ->
            for (url in listOf("https://other.invalid/index.html", "http://${f.content.host}/index.html",
                "https://${f.content.host}:443/index.html", "https://user@${f.content.host}/index.html",
                "https://${f.content.host}.outside.invalid/index.html", "file:///private.txt", "content://private/item",
                "intent://outside", "data:text/html,hello", "blob:https://${f.content.host}/id")) {
                val response = request(web, url)
                assertEquals(url, 403, response.statusCode)
                assertEquals(expected(interactiveCsp), response.responseHeaders)
            }
        }
    }

    @Test fun deniedMethodsAndMissingMembersKeepHeadersWithoutServingPrivateBytes() {
        val f = fixture()
        withWeb(f) { web ->
            for (method in listOf("POST", "PUT", "DELETE", "OPTIONS", "PATCH")) {
                val response = request(web, f.url("index.html"), method)
                assertEquals(405, response.statusCode)
                assertEquals(expected(interactiveCsp), response.responseHeaders)
            }
            for (path in listOf("absent.html", "not-captured.js")) {
                val response = request(web, f.url(path))
                assertEquals(404, response.statusCode)
                assertEquals(expected(interactiveCsp), response.responseHeaders)
            }
        }
    }

    @Test fun encodedEscapesAndPrivatePathsStayDenied() {
        val f = fixture()
        withWeb(f) { web ->
            for (path in listOf("%2e%2e/secret", "%252e%252e/secret", "nested%2fsecret", "nested%5csecret", "secret%00")) {
                val response = request(web, f.url(path))
                assertEquals(path, 403, response.statusCode)
                assertEquals(expected(interactiveCsp), response.responseHeaders)
            }
        }
    }

    @Test fun localLinksAndFragmentsRemainAllowedButExternalNavigationAndPostsDoNot() {
        val f = fixture()
        withWeb(f) { web ->
            for (url in listOf(f.url("next.html"), f.url("index.html") + "#section")) {
                assertFalse(web.webViewClient.shouldOverrideUrlLoading(web, Request(url)))
            }
            assertTrue(web.webViewClient.shouldOverrideUrlLoading(web, Request("https://outside.invalid/")))
            assertTrue(web.webViewClient.shouldOverrideUrlLoading(web, Request(f.url("index.html"), "POST")))
        }
    }

    @Test fun snapshotMembersStayImmutableAndNewWorkspaceFilesDoNotBecomeAuthorized() {
        val f = fixture(snapshot = true)
        f.workspace.write("index.html", "Changed")
        f.workspace.write("not-captured.js", "private")
        withWeb(f) { web ->
            assertArrayEquals(f.resources.getValue("index.html"), request(web, f.url("index.html")).data.use { it.readBytes() })
            val denied = request(web, f.url("not-captured.js"))
            assertEquals(404, denied.statusCode)
            assertEquals(expected(interactiveCsp), denied.responseHeaders)
        }
    }

    @Test fun recreatedViewKeepsPoliciesAndAllowedSavedNavigationWithoutAnExtraEntryReload() {
        val f = fixture()
        val state = PreviewPageState(Bundle().apply { putString("url", f.url("next.html")); putInt("y", 220) })
        val first = createPreviewWebView(context, f.content, state)
        assertEquals(f.url("next.html"), shadowOf(first).lastLoadedUrl)
        first.onPause(); first.onResume()
        assertEquals(expected(interactiveCsp), request(first, f.url("next.html")).responseHeaders)
        state.release(first); first.destroy()
        val restored = createPreviewWebView(context, f.content, state)
        try {
            assertEquals(f.url("next.html"), shadowOf(restored).lastLoadedUrl)
            assertTrue(restored.settings.javaScriptEnabled); assertTrue(restored.settings.blockNetworkLoads)
            assertEquals(expected(interactiveCsp), request(restored, f.url("next.html")).responseHeaders)
        } finally { state.release(restored); restored.destroy() }
    }

    @Test fun invalidSavedExternalNavigationStillFallsBackToOwnedEntry() {
        val f = fixture()
        val state = PreviewPageState(Bundle().apply { putString("url", "https://outside.invalid/") })
        val web = createPreviewWebView(context, f.content, state)
        try {
            assertEquals(f.content.entryUrl, shadowOf(web).lastLoadedUrl)
            assertEquals(expected(interactiveCsp), request(web, f.content.entryUrl).responseHeaders)
        } finally { state.release(web); web.destroy() }
    }

    @Test fun thumbnailSuccessAndEveryDenialKeepNativeRestrictionAndStricterCsp() {
        val f = fixture(snapshot = true)
        val web = createStaticThumbnailWebView(context, f.content, {}, {})
        try {
            for ((url, method, status) in listOf(Triple(f.url("index.html"), "GET", 200),
                Triple(f.url("index.html"), "HEAD", 200), Triple(f.url("app.js"), "GET", 403),
                Triple(f.url("absent.html"), "GET", 404), Triple("https://outside.invalid/", "GET", 403),
                Triple(f.url("index.html"), "POST", 405))) {
                val response = request(web, url, method, mainFrame = false)
                assertEquals(url, status, response.statusCode)
                assertEquals(expected(thumbnailCsp), response.responseHeaders)
            }
            assertFalse(web.settings.javaScriptEnabled); assertFalse(web.settings.domStorageEnabled)
        } finally { web.destroy() }
    }

    private fun fixture(snapshot: Boolean = false): Fixture {
        val session = "ux39-${UUID.randomUUID()}"
        val workspace = WorkspaceStore(File(context.filesDir, "jarvys/workspaces"), WorkspaceStore.projectIdForSession(session),
            null, null, null, session, false)
        val resources = linkedMapOf(
            "index.html" to "<!doctype html><link rel=\"stylesheet\" href=\"style.css\"><script src=\"app.js\"></script><script type=\"module\" src=\"module.js\"></script><img src=\"icon.svg\"><a href=\"next.html\">Next</a><button onclick=\"this.textContent='ok'\">Tap</button>",
            "next.html" to "<!doctype html><p id=\"section\">Next local page</p>",
            "style.css" to "body { color: #123456; }", "app.js" to "window.interactive=true;",
            "module.js" to "export const local = true;", "icon.svg" to "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"/>",
        ).mapValues { it.value.toByteArray(Charsets.UTF_8) }
        resources.forEach { (path, bytes) -> workspace.write(path, String(bytes, Charsets.UTF_8)) }
        val content = if (snapshot) {
            val store = DeliveredArtifactStore(context)
            val attachment = store.snapshot(session, workspace, "index.html", null, CancellationToken.uncancellable())
            LocalRunStore(context).appendDeliveredFile(session, attachment)
            WorkspacePreviewContent(context, null, session, requireNotNull(store.previewForAttachment(session, attachment)))
        } else WorkspacePreviewContent(context, workspace)
        return Fixture(workspace, content, resources)
    }
    private data class Fixture(val workspace: WorkspaceStore, val content: WorkspacePreviewContent, val resources: Map<String, ByteArray>) {
        fun url(path: String) = content.entryUrl.substringBeforeLast('/') + "/" + path
    }
    private fun withWeb(f: Fixture, action: (WebView) -> Unit) {
        val state = PreviewPageState(); val web = createPreviewWebView(context, f.content, state)
        try { action(web) } finally { state.release(web); web.destroy() }
    }
    private fun request(web: WebView, url: String, method: String = "GET", mainFrame: Boolean = true): WebResourceResponse =
        requireNotNull(web.webViewClient.shouldInterceptRequest(web, Request(url, method, mainFrame)))
    private class Request(url: String, private val verb: String = "GET", private val mainFrame: Boolean = true) : WebResourceRequest {
        private val uri = Uri.parse(url)
        override fun getUrl(): Uri = uri
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = verb
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
