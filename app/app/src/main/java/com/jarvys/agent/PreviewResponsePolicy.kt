package com.jarvys.agent

/**
 * Preview-only response policies. Keep these on successful and denied responses alike.
 * Connection-Allowlist is native defense in depth on supporting WebView providers (152+),
 * not a universal offline guarantee. Older providers may ignore it; URL/manifest checks and
 * CSP remain mandatory. No JavaScript shim, bridge, provider flag or permission is installed.
 */
internal object PreviewResponsePolicy {
    private const val CONNECTION_ALLOWLIST = "(response-origin); webrtc=block; redirects=block"
    private const val INTERACTIVE_CSP = "default-src 'self' data: blob:; " +
        "img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; " +
        "script-src 'self' 'unsafe-inline'; connect-src 'none'; " +
        "object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'; " +
        "worker-src 'none'; child-src 'none'"
    private const val THUMBNAIL_CSP = "default-src 'none'; img-src 'self' data:; " +
        "style-src 'self' 'unsafe-inline'; font-src 'self' data:; script-src 'none'; " +
        "connect-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'; " +
        "form-action 'none'; worker-src 'none'; media-src 'none'; child-src 'none'"

    fun interactiveHeaders(): Map<String, String> = headers(INTERACTIVE_CSP)

    fun thumbnailHeaders(): Map<String, String> = headers(THUMBNAIL_CSP)

    // A new map per response prevents one caller from relaxing a later response's policy.
    private fun headers(csp: String): Map<String, String> = mapOf(
        "Cache-Control" to "no-store",
        "X-Content-Type-Options" to "nosniff",
        "X-DNS-Prefetch-Control" to "off",
        "Content-Security-Policy" to csp,
        "Connection-Allowlist" to CONNECTION_ALLOWLIST,
    )
}
