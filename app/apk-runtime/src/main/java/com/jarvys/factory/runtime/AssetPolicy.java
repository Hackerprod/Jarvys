package com.jarvys.factory.runtime;

import java.net.URI;
import java.net.URISyntaxException;

/** Offline resource allowlist, independent from Android for adversarial unit tests. */
public final class AssetPolicy {
    public static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; font-src 'self'; connect-src 'none'; frame-src 'none'; child-src 'none'; "
            + "worker-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    public static String assetPath(String url) {
        try {
            URI uri = new URI(url);
            if (!"https".equals(uri.getScheme()) || !"app.jarvys.invalid".equals(uri.getRawAuthority()) || uri.getRawQuery() != null) return null;
            String rawPath = uri.getRawPath();
            if (rawPath == null || !rawPath.startsWith("/")) return null;
            String path = rawPath.substring(1);
            if (!FactoryConfig.safeAssetPath(path)) return null;
            if (path.equals("factory-sdk.js")) return path;
            if (!path.startsWith("www/") || mimeType(path) == null) return null;
            return path;
        } catch (URISyntaxException | NullPointerException e) { return null; }
    }
    public static boolean navigationAllowed(String url, boolean mainFrame) {
        String path = assetPath(url);
        return mainFrame && path != null && path.startsWith("www/") && path.endsWith(".html");
    }
    static String mimeType(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".txt")) return "text/plain";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".gif")) return "image/gif";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".woff2")) return "font/woff2";
        return null;
    }
}
