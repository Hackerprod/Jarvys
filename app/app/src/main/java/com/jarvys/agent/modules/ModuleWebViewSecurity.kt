package com.jarvys.agent.modules

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.io.ByteArrayInputStream

/** Common settings and URL policy for the off-screen handler and visible module viewer hosts. */
internal object ModuleWebViewSecurity {
    const val ASSET_AUTHORITY = "appassets.androidplatform.net"

    private const val VIEWER_CSP = "default-src 'self' data: blob:; " +
        "img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; " +
        "script-src 'self' 'unsafe-inline'; connect-src 'self'; " +
        "object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'; " +
        "worker-src 'none'; child-src 'none'"
    private val encodedPathControl = Regex("%(?:2e|2f|5c|00|25)", RegexOption.IGNORE_CASE)

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(view: WebView) {
        view.setBackgroundColor(Color.TRANSPARENT)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            blockNetworkLoads = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
    }

    /** Returns the path below the current module's bundle, or null for every other origin/path. */
    fun moduleAssetPath(uri: Uri, bundleAssetDirectory: String): String? {
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.encodedAuthority != ASSET_AUTHORITY ||
            uri.encodedQuery != null || uri.encodedFragment != null) return null
        val path = uri.encodedPath ?: return null
        val encodedDirectory = bundleAssetDirectory.split('/').joinToString("/") { Uri.encode(it) }
        val expectedPrefix = "/assets/$encodedDirectory/"
        if (!path.startsWith(expectedPrefix) || encodedPathControl.containsMatchIn(path)) return null
        val relative = Uri.decode(path.removePrefix(expectedPrefix))
        return relative.takeIf(ModuleWorkspacePath::isSafeBundlePath)
    }

    fun isArtifactUrl(uri: Uri, token: String): Boolean =
        uri.scheme.equals("https", ignoreCase = true) && uri.encodedAuthority == ASSET_AUTHORITY &&
            uri.encodedPath == "/module-artifacts/$token" && uri.encodedQuery == null && uri.encodedFragment == null

    fun viewerHeaders(): Map<String, String> =
        com.jarvys.agent.PreviewResponsePolicy.interactiveHeaders().toMutableMap().apply {
            put("Content-Security-Policy", VIEWER_CSP)
        }

    fun executionCsp(): String = com.jarvys.agent.PreviewResponsePolicy.interactiveHeaders()
        .getValue("Content-Security-Policy")

    fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Forbidden", viewerHeaders(),
        ByteArrayInputStream("Module resource is outside its allowed bundle.".toByteArray(Charsets.UTF_8)),
    )
}
