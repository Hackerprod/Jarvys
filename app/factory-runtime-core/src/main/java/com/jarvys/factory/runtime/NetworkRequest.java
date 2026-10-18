package com.jarvys.factory.runtime;

import android.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Immutable HTTP request grammar. Host policy and Android network configuration remain separate. */
public final class NetworkRequest {
    public static final int MAX_BODY_BYTES = 256 * 1024;
    public static final int DEFAULT_BODY_BYTES = 64 * 1024;
    public static final int MAX_HEADER_BYTES = 32 * 1024;
    public static final int MAX_URL_BYTES = 8192;
    public static final int MAX_TIMEOUT_MS = 120000;
    public static final int MAX_REDIRECTS = 10;
    public static final int MAX_RESULT_BYTES = BridgeProtocol.MAX_MESSAGE_BYTES - 4096;

    public final URL url;
    public final String method, responseType, redirect;
    public final Map<String, String> headers;
    public final int connectTimeoutMs, readTimeoutMs, timeoutMs, maxRequestBytes, maxResponseBytes,
            maxHeaderBytes, maxRedirects;
    public final boolean probe, allowHttpsDowngrade;
    private final byte[] body;
    private final boolean hasBody, textBody;

    private NetworkRequest(JSONObject args, boolean probe) throws Exception {
        this.probe = probe;
        if (probe) FactoryConfig.exactKeys(args, "url", "method", "headers", "connectTimeoutMs",
                "readTimeoutMs", "timeoutMs", "maxHeaderBytes", "redirect", "maxRedirects", "allowHttpsDowngrade");
        else FactoryConfig.exactKeys(args, "url", "method", "headers", "body", "bodyBase64", "responseType",
                "connectTimeoutMs", "readTimeoutMs", "timeoutMs", "maxRequestBytes", "maxResponseBytes",
                "maxHeaderBytes", "redirect", "maxRedirects", "allowHttpsDowngrade");
        url = parseUrl(string(args, "url", null));
        method = string(args, "method", probe ? "HEAD" : "GET").toUpperCase(Locale.ROOT);
        if (!token(method) || method.length() > 32 || (probe && !"HEAD".equals(method) && !"GET".equals(method)))
            throw invalid("method must be a supported HTTP token; probes support HEAD or GET.");
        responseType = string(args, "responseType", "text");
        if (!"text".equals(responseType) && !"base64".equals(responseType)) throw invalid("responseType must be text or base64.");
        redirect = string(args, "redirect", "follow");
        if (!"follow".equals(redirect) && !"manual".equals(redirect) && !"error".equals(redirect))
            throw invalid("redirect must be follow, manual, or error.");
        connectTimeoutMs = integer(args, "connectTimeoutMs", 10000, 1, MAX_TIMEOUT_MS);
        readTimeoutMs = integer(args, "readTimeoutMs", 10000, 1, MAX_TIMEOUT_MS);
        timeoutMs = integer(args, "timeoutMs", 30000, 1, MAX_TIMEOUT_MS);
        maxRequestBytes = integer(args, "maxRequestBytes", DEFAULT_BODY_BYTES, 0, MAX_BODY_BYTES);
        maxResponseBytes = probe ? 0 : integer(args, "maxResponseBytes", DEFAULT_BODY_BYTES, 0, MAX_BODY_BYTES);
        maxHeaderBytes = integer(args, "maxHeaderBytes", 16384, 256, MAX_HEADER_BYTES);
        maxRedirects = integer(args, "maxRedirects", 5, 0, MAX_REDIRECTS);
        allowHttpsDowngrade = bool(args, "allowHttpsDowngrade", false);
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        int headerBytes = 0;
        if (args.has("headers")) {
            Object rawHeaders = args.get("headers");
            if (!(rawHeaders instanceof JSONObject)) throw invalid("headers must be an object of string values.");
            JSONObject object = (JSONObject) rawHeaders;
            if (object.length() > 64) throw invalid("headers may contain at most 64 entries.");
            for (Iterator<String> keys = object.keys(); keys.hasNext();) {
                String name = keys.next(), lower = name.toLowerCase(Locale.ROOT);
                String value = string(object, name, null);
                if (!token(name) || name.length() > 256 || managedHeader(lower) || values.containsKey(lower))
                    throw invalid("headers must use unique, safe, unmanaged HTTP names.");
                if (value.length() > maxHeaderBytes) throw invalid("headers values must fit maxHeaderBytes.");
                for (int i = 0; i < value.length(); i++) {
                    char c = value.charAt(i);
                    if ((c < 32 && c != '\t') || c > 126) throw invalid("headers values must contain HTTP ASCII field values.");
                }
                headerBytes += name.length() + value.length() + 4;
                if (headerBytes > maxHeaderBytes) throw invalid("headers must fit maxHeaderBytes.");
                values.put(lower, value);
            }
        }
        headers = Collections.unmodifiableMap(values);
        hasBody = args.has("body") || args.has("bodyBase64");
        textBody = args.has("body");
        if (args.has("body") && args.has("bodyBase64")) throw invalid("body and bodyBase64 are mutually exclusive.");
        // HttpURLConnection rewrites GET-with-output to POST; reject rather than silently change semantics.
        if (hasBody && ("GET".equals(method) || "HEAD".equals(method) || "TRACE".equals(method)))
            throw invalid("body is unsupported with GET, HEAD, or TRACE.");
        if (textBody) {
            String value = string(args, "body", null);
            if (value.length() > maxRequestBytes || !validUnicode(value)) throw invalid("body must be valid Unicode within maxRequestBytes.");
            body = value.getBytes(StandardCharsets.UTF_8);
        } else if (hasBody) {
            String value = string(args, "bodyBase64", null);
            if (value.length() > ((maxRequestBytes + 2) / 3) * 4 || value.length() % 4 != 0)
                throw invalid("bodyBase64 must be canonical padded base64 within maxRequestBytes.");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '+' && c != '/' && c != '=')
                    throw invalid("bodyBase64 must be canonical padded base64.");
            }
            body = Base64.decode(value, Base64.NO_WRAP);
            if (!Base64.encodeToString(body, Base64.NO_WRAP).equals(value)) throw invalid("bodyBase64 must be canonical padded base64.");
        } else body = new byte[0];
        if (body.length > maxRequestBytes) throw invalid("body must not exceed maxRequestBytes.");
    }

    public static NetworkRequest fromRequest(JSONObject args) throws FactoryException { return parse(args, false); }
    public static NetworkRequest fromProbe(JSONObject args) throws FactoryException { return parse(args, true); }
    private static NetworkRequest parse(JSONObject args, boolean probe) throws FactoryException {
        try { if (args == null) throw invalid((probe ? "network.probe" : "network.request") + ".args must be an object."); return new NetworkRequest(args, probe); }
        catch (FactoryException failure) { throw failure; }
        catch (Exception invalid) { throw invalid((probe ? "network.probe" : "network.request") + ": request fields have invalid JSON types or shapes."); }
    }

    /** A new review object each time; callers must not log it, as URL/headers/preview may be private. */
    public JSONObject authorizationDetails() throws FactoryException {
        try {
            JSONObject details = new JSONObject().put("url", url.toExternalForm()).put("method", method)
                    .put("headers", new JSONObject(headers)).put("bodyBytes", body.length)
                    .put("bodySha256", hex(MessageDigest.getInstance("SHA-256").digest(body)))
                    .put("probe", probe).put("redirect", redirect).put("maxRedirects", maxRedirects)
                    .put("allowHttpsDowngrade", allowHttpsDowngrade).put("hasBody", hasBody)
                    .put("responseType", responseType).put("connectTimeoutMs", connectTimeoutMs)
                    .put("readTimeoutMs", readTimeoutMs).put("timeoutMs", timeoutMs)
                    .put("maxRequestBytes", maxRequestBytes).put("maxResponseBytes", maxResponseBytes)
                    .put("maxHeaderBytes", maxHeaderBytes);
            String preview = textBody ? new String(body, StandardCharsets.UTF_8) : "";
            if (preview.length() > 512) {
                int end = Character.isHighSurrogate(preview.charAt(511)) ? 511 : 512;
                preview = preview.substring(0, end);
            }
            return details.put("bodyPreview", preview).put("bodyPreviewTruncated", textBody && preview.getBytes(StandardCharsets.UTF_8).length < body.length)
                    .put("bodyEncoding", textBody ? "utf-8" : "base64");
        } catch (Exception failure) { throw new FactoryException("UNAVAILABLE", "Network review details unavailable."); }
    }

    byte[] bodyBytes() { return body.clone(); }
    boolean hasBody() { return hasBody; }
    static URL parseUrl(String value) throws FactoryException {
        try {
            if (value == null || value.length() > MAX_URL_BYTES || value.getBytes(StandardCharsets.UTF_8).length > MAX_URL_BYTES || !validUnicode(value))
                throw invalid("url must be a bounded HTTP(S) URL without credentials or a fragment.");
            for (int i = 0; i < value.length(); i++) if (value.charAt(i) <= 32 || value.charAt(i) == 127 || value.charAt(i) == '\\') throw invalid("url must be a bounded HTTP(S) URL without whitespace, credentials or a fragment.");
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getUserInfo() != null || uri.getRawFragment() != null || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535)
                throw invalid("url must be an absolute HTTP(S) URL without userinfo or fragment.");
            return uri.toURL();
        } catch (FactoryException failure) { throw failure; }
            catch (Exception failure) { throw invalid("url must be a bounded HTTP(S) URL without credentials or a fragment."); }
    }
    private static boolean managedHeader(String name) {
        return "host".equals(name) || "content-length".equals(name) || "transfer-encoding".equals(name)
                || "connection".equals(name) || "proxy-connection".equals(name) || "expect".equals(name)
                || "upgrade".equals(name) || "trailer".equals(name) || "te".equals(name);
    }
    private static boolean token(String value) { return !value.isEmpty() && value.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"); }
    private static String string(JSONObject object, String key, String fallback) throws JSONException, FactoryException {
        if (!object.has(key)) { if (fallback != null) return fallback; throw invalid(key + " is required and must be a string."); }
        Object value = object.get(key);
        if (!(value instanceof String)) throw invalid(key + " must be a string.");
        return (String) value;
    }
    private static int integer(JSONObject object, String key, int fallback, int min, int max) throws JSONException, FactoryException {
        if (!object.has(key)) return fallback;
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid(key + " must be an integer.");
        long number = ((Number) value).longValue();
        if (number < min || number > max) throw invalid(key + " must be from " + min + " through " + max + ".");
        return (int) number;
    }
    private static boolean bool(JSONObject object, String key, boolean fallback) throws JSONException, FactoryException {
        if (!object.has(key)) return fallback;
        if (!(object.get(key) instanceof Boolean)) throw invalid(key + " must be a boolean.");
        return object.getBoolean(key);
    }
    private static boolean validUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false; }
            else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return value.toString();
    }
    private static FactoryException invalid(String message) { return new FactoryException("INVALID_ARGUMENT", message); }
}
