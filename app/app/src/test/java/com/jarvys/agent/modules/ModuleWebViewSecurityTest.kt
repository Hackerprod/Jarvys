package com.jarvys.agent.modules

import android.net.Uri
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModuleWebViewSecurityTest {
    @Test
    fun moduleWebViewDisablesFileContentAndUnmediatedNetworkAccess() {
        val webView = WebView(ApplicationProvider.getApplicationContext())
        try {
            ModuleWebViewSecurity.configure(webView)

            assertFalse(webView.settings.allowFileAccess)
            assertFalse(webView.settings.allowContentAccess)
            assertFalse(webView.settings.allowFileAccessFromFileURLs)
            assertFalse(webView.settings.allowUniversalAccessFromFileURLs)
            assertFalse(webView.settings.domStorageEnabled)
            assertFalse(webView.settings.databaseEnabled)
            assertTrue(webView.settings.blockNetworkLoads)
            assertEquals(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW, webView.settings.mixedContentMode)
        } finally {
            webView.destroy()
        }
    }

    @Test
    fun viewerCspAllowsOnlySameOriginRequestsAndDisablesActiveEmbeds() {
        val csp = ModuleWebViewSecurity.viewerHeaders().getValue("Content-Security-Policy")

        assertTrue(csp.contains("connect-src 'self'"))
        assertTrue(csp.contains("script-src 'self' 'unsafe-inline'"))
        assertTrue(csp.contains("object-src 'none'"))
        assertTrue(csp.contains("frame-src 'none'"))
        assertTrue(csp.contains("base-uri 'none'"))
        assertTrue(csp.contains("form-action 'none'"))
    }

    @Test
    fun resourcePolicyServesOnlyTheCurrentModuleDirectoryAndExactArtifactToken() {
        val ownAsset = Uri.parse("https://appassets.androidplatform.net/assets/com.example.chart/viewer/index.html")
        val otherModuleAsset = Uri.parse("https://appassets.androidplatform.net/assets/com.example.other/viewer/index.html")
        val outsideBundle = Uri.parse("https://appassets.androidplatform.net/other/viewer/index.html")
        val networkAsset = Uri.parse("https://example.com/assets/com.example.chart/viewer/index.html")
        val traversal = Uri.parse("https://appassets.androidplatform.net/assets/com.example.chart/%2e%2e/other/index.html")
        val withQuery = Uri.parse("https://appassets.androidplatform.net/assets/com.example.chart/viewer.js?remote=1")

        assertEquals("viewer/index.html", ModuleWebViewSecurity.moduleAssetPath(ownAsset, "com.example.chart"))
        assertNull(ModuleWebViewSecurity.moduleAssetPath(otherModuleAsset, "com.example.chart"))
        assertNull(ModuleWebViewSecurity.moduleAssetPath(outsideBundle, "com.example.chart"))
        assertNull(ModuleWebViewSecurity.moduleAssetPath(networkAsset, "com.example.chart"))
        assertNull(ModuleWebViewSecurity.moduleAssetPath(traversal, "com.example.chart"))
        assertNull(ModuleWebViewSecurity.moduleAssetPath(withQuery, "com.example.chart"))

        val artifact = Uri.parse("https://appassets.androidplatform.net/module-artifacts/session-token")
        val lookalike = Uri.parse("https://appassets.androidplatform.net/module-artifacts/session-token/extra")
        assertTrue(ModuleWebViewSecurity.isArtifactUrl(artifact, "session-token"))
        assertFalse(ModuleWebViewSecurity.isArtifactUrl(lookalike, "session-token"))
        assertFalse(ModuleWebViewSecurity.isArtifactUrl(artifact, "another-token"))
    }
}
