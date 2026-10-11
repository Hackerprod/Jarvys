package com.jarvys.factory.runtime;

import android.security.NetworkSecurityPolicy;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;

/**
 * Native HTTP transport. Run off the UI thread and cancel on lifecycle/policy revocation.
 * Platform TLS trust/hostname verification are never replaced. INTERNET is process-wide;
 * these checks do not isolate WebView or other callers. DNS/socket teardown can outlast a
 * cancellation on some devices; no result is delivered after cancellation or authorization loss.
 */
public final class NativeNetwork {
    public interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
    public interface CleartextPolicy { boolean permitted(String host); }
    public interface Clock { long nanoTime(); }
    /** Trusted native code only. Called anew after ordinary headers for each actual connection. */
    public interface HeaderInjector {
        void inject(URL destination, String method, HeaderSink sink) throws FactoryException;
    }
    /** A synchronous, single-connection sink. Never retain it or pass it to JavaScript. */
    public interface HeaderSink { void set(String name, String value) throws FactoryException; }
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private final ConnectionFactory connections;
    private final CleartextPolicy cleartext;
    private final Clock clock;

    public NativeNetwork() {
        this(url -> (HttpURLConnection) url.openConnection(),
                host -> NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host), System::nanoTime);
    }
    public NativeNetwork(ConnectionFactory connections, CleartextPolicy cleartext, Clock clock) {
        if (connections == null || cleartext == null || clock == null) throw new IllegalArgumentException();
        this.connections = connections; this.cleartext = cleartext; this.clock = clock;
    }
    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor value = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "factory-network-deadline"); thread.setDaemon(true); return thread;
        });
        value.setRemoveOnCancelPolicy(true);
        return value;
    }

    /** Single-operation cancellation. Never reused, including after completion. */
    public static final class Cancellation implements AutoCloseable {
        private boolean started, completed;
        private String reason;
        private HttpURLConnection active;
        public void cancel() { cancel("CANCELLED"); }
        @Override public void close() { cancel(); }
        public synchronized boolean isCancelled() { return reason != null; }
        private void cancel(String code) {
            HttpURLConnection connection;
            synchronized (this) { if (completed || reason != null) return; reason = code; connection = active; }
            disconnect(connection);
        }
        private synchronized void begin() throws FactoryException {
            if (started) throw new FactoryException("INVALID_ARGUMENT", "Cancellation belongs to one network operation.");
            started = true; check();
        }
        private void attach(HttpURLConnection connection) throws FactoryException {
            synchronized (this) { if (reason == null) { active = connection; return; } }
            disconnect(connection); check();
        }
        private synchronized void detach(HttpURLConnection connection) { if (active == connection) active = null; }
        private synchronized void finish() { completed = true; active = null; }
        private synchronized void complete() throws FactoryException { check(); completed = true; active = null; }
        private synchronized void check() throws FactoryException {
            if (reason != null) throw new FactoryException(reason, "TIMEOUT".equals(reason) ? "Network deadline elapsed." : "Network operation cancelled.");
        }
    }

    public JSONObject execute(NetworkRequest request, NativeOperationAuthorization.Access authorization,
                              Cancellation cancellation) throws FactoryException {
        return execute(request, authorization, cancellation, null);
    }

    /** Native credential path; the ordinary request grammar and plain-network behavior are unchanged. */
    public JSONObject execute(NetworkRequest request, NativeOperationAuthorization.Access authorization,
                              Cancellation cancellation, HeaderInjector injector) throws FactoryException {
        if (request == null || authorization == null || cancellation == null) throw new IllegalArgumentException();
        cancellation.begin();
        long started = clock.nanoTime();
        long deadline = started + TimeUnit.MILLISECONDS.toNanos(request.timeoutMs);
        ScheduledFuture<?> timer = DEADLINES.schedule(() -> cancellation.cancel("TIMEOUT"), request.timeoutMs, TimeUnit.MILLISECONDS);
        try {
            check(authorization, cancellation, deadline);
            JSONObject result = perform(request, authorization, cancellation, started, deadline, injector);
            check(authorization, cancellation, deadline);
            cancellation.complete();
            return result;
        } catch (IOException failure) {
            check(authorization, cancellation, deadline);
            String code = failure instanceof SSLException ? "TLS_ERROR" : failure instanceof UnknownHostException ? "DNS_ERROR"
                    : failure instanceof SocketTimeoutException ? "TIMEOUT" : failure instanceof ProtocolException ? "NETWORK_PROTOCOL_ERROR" : "NETWORK_ERROR";
            if (!request.probe) throw new FactoryException(code, "Native HTTP request could not complete.");
            JSONObject result;
            try {
                result = new JSONObject().put("measurement", "http_response_headers").put("icmp", false)
                        .put("reachable", false).put("ok", false).put("url", request.url.toExternalForm())
                        .put("method", request.method).put("elapsedMs", elapsed(started)).put("error", code);
            } catch (Exception impossible) { throw new FactoryException("UNAVAILABLE", "Network result unavailable."); }
            check(authorization, cancellation, deadline);
            cancellation.complete();
            return result;
        } finally { timer.cancel(false); cancellation.finish(); }
    }

    private JSONObject perform(NetworkRequest request, NativeOperationAuthorization.Access authorization,
                               Cancellation cancellation, long started, long deadline, HeaderInjector injector) throws FactoryException, IOException {
        URL url = request.url;
        String method = request.method;
        byte[] body = request.bodyBytes();
        boolean hasBody = request.hasBody();
        Map<String, String> headers = new LinkedHashMap<>(request.headers);
        int redirects = 0;
        while (true) {
            check(authorization, cancellation, deadline);
            if (injector != null && !"https".equalsIgnoreCase(url.getProtocol()))
                throw new FactoryException("SECRET_SCOPE_MISMATCH", "Native credential requests require HTTPS.");
            if ("http".equalsIgnoreCase(url.getProtocol()) && !cleartext.permitted(url.getHost()))
                throw new FactoryException("CLEARTEXT_NOT_PERMITTED", "Android configuration does not permit cleartext HTTP for this host.");
            // URL.openConnection may consult a system proxy; authorize even resource acquisition.
            check(authorization, cancellation, deadline);
            HttpURLConnection connection = connections.open(url);
            if (connection == null) throw new IOException();
            cancellation.attach(connection);
            try {
                check(authorization, cancellation, deadline);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(remaining(request.connectTimeoutMs, deadline));
                connection.setReadTimeout(remaining(request.readTimeoutMs, deadline));
                try { connection.setRequestMethod(method); }
                catch (ProtocolException unsupported) { throw new FactoryException("UNSUPPORTED_METHOD", "This Android HTTP transport does not support the requested method."); }
                for (Map.Entry<String, String> entry : headers.entrySet()) connection.setRequestProperty(entry.getKey(), entry.getValue());
                if (injector != null) inject(connection, url, method, headers, request.maxHeaderBytes,
                        injector, authorization, cancellation, deadline);
                if (hasBody) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(body.length);
                    check(authorization, cancellation, deadline);
                    try (OutputStream stream = connection.getOutputStream()) {
                        for (int at = 0; at < body.length; at += 8192) {
                            check(authorization, cancellation, deadline);
                            stream.write(body, at, Math.min(8192, body.length - at));
                        }
                        check(authorization, cancellation, deadline);
                        stream.flush();
                    }
                }
                check(authorization, cancellation, deadline);
                int status = connection.getResponseCode();
                check(authorization, cancellation, deadline);
                if (status < 100 || status > 599) throw new IOException();
                double latencyMs = elapsed(started);
                JSONObject responseHeaders = responseHeaders(connection.getHeaderFields(), request.maxHeaderBytes);
                if (isRedirect(status) && !"manual".equals(request.redirect)) {
                    if ("error".equals(request.redirect)) throw new FactoryException("REDIRECT_DISALLOWED", "Request returned a redirect.");
                    if (redirects >= request.maxRedirects) throw new FactoryException("TOO_MANY_REDIRECTS", "Network redirect limit reached.");
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty()) throw new FactoryException("INVALID_REDIRECT", "Redirect has no destination.");
                    URL next;
                    try { next = NetworkRequest.parseUrl(new URL(url, location).toExternalForm()); }
                    catch (FactoryException | IOException invalid) { throw new FactoryException("INVALID_REDIRECT", "Redirect destination is not a supported HTTP URL."); }
                    if ("https".equalsIgnoreCase(url.getProtocol()) && "http".equalsIgnoreCase(next.getProtocol()) && (injector != null || !request.allowHttpsDowngrade))
                        throw new FactoryException("INSECURE_REDIRECT", injector != null ? "Native credential requests cannot downgrade HTTPS."
                                : "HTTPS downgrade needs allowHttpsDowngrade and Android cleartext support.");
                    // A new origin never inherits app-supplied credentials or arbitrary custom headers.
                    // Call the destination explicitly when it needs its own headers.
                    if (!sameOrigin(url, next)) headers.clear();
                    if ((status == 303 && !"HEAD".equals(method)) || ((status == 301 || status == 302) && "POST".equals(method))) {
                        method = "GET"; body = new byte[0]; hasBody = false;
                        headers.remove("content-type"); headers.remove("content-encoding");
                    }
                    check(authorization, cancellation, deadline);
                    url = next; redirects++;
                    continue;
                }
                byte[] responseBody = new byte[0];
                if (!request.probe && !"HEAD".equals(method) && status != 204 && status != 304) {
                    check(authorization, cancellation, deadline);
                    InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    if (stream != null) try (InputStream input = stream) {
                        responseBody = read(input, connection, request, authorization, cancellation, deadline);
                    }
                }
                check(authorization, cancellation, deadline);
                JSONObject result;
                try {
                    result = new JSONObject().put("url", url.toExternalForm()).put("method", method).put("status", status)
                            .put("ok", status >= 200 && status < 300).put("headers", responseHeaders).put("redirects", redirects)
                            .put("latencyMs", latencyMs).put("elapsedMs", elapsed(started)).put("cleartext", "http".equalsIgnoreCase(url.getProtocol()));
                    if (request.probe) result.put("measurement", "http_response_headers").put("icmp", false).put("reachable", true);
                    else {
                        result.put("bodyBytes", responseBody.length).put("responseType", request.responseType);
                        if ("base64".equals(request.responseType)) result.put("bodyBase64", Base64.encodeToString(responseBody, Base64.NO_WRAP));
                        else {
                            Charset charset = responseCharset(connection.getContentType());
                            result.put("body", new String(responseBody, charset)).put("charset", charset.name());
                        }
                    }
                    if (result.toString().getBytes(StandardCharsets.UTF_8).length > NetworkRequest.MAX_RESULT_BYTES)
                        throw new FactoryException("RESPONSE_TOO_LARGE", "Encoded network result exceeds the bridge envelope; request fewer bytes or base64.");
                } catch (FactoryException failure) { throw failure; }
                catch (Exception failure) { throw new FactoryException("UNAVAILABLE", "Network result unavailable."); }
                check(authorization, cancellation, deadline);
                return result;
            } finally { cancellation.detach(connection); disconnect(connection); }
        }
    }

    private void inject(HttpURLConnection connection, URL url, String method, Map<String, String> headers,
                        int maxBytes, HeaderInjector injector, NativeOperationAuthorization.Access access,
                        Cancellation cancellation, long deadline) throws FactoryException {
        check(access, cancellation, deadline);
        // Names/byte counts only: a decrypted value is never put in a reusable header collection.
        final java.util.Set<String> names = new java.util.HashSet<>();
        long count = 0;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            names.add(entry.getKey().toLowerCase(Locale.ROOT));
            count += entry.getKey().length() + entry.getValue().length() + 4L;
        }
        final class Sink implements HeaderSink {
            long bytes; boolean open = true; FactoryException rejected;
            final Thread owner = Thread.currentThread();
            Sink(long bytes) { this.bytes = bytes; }
            @Override public synchronized void set(String name, String value) throws FactoryException {
                if (!open || Thread.currentThread() != owner) throw new FactoryException("SECRET_INJECTION_FAILED", "Credential header sink has ended.");
                if (rejected != null) throw rejected;
                try {
                    check(access, cancellation, deadline);
                    if (name == null || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}") || value == null)
                        throw new FactoryException("SECRET_HEADER_INVALID", "Invalid native credential header.");
                    String lower = name.toLowerCase(Locale.ROOT);
                    if ("host".equals(lower) || "content-length".equals(lower) || "transfer-encoding".equals(lower)
                            || "connection".equals(lower) || "proxy-connection".equals(lower) || "expect".equals(lower)
                            || "upgrade".equals(lower) || "trailer".equals(lower) || "te".equals(lower)
                            || "proxy-authorization".equals(lower) || "proxy-authenticate".equals(lower))
                        throw new FactoryException("SECRET_HEADER_INVALID", "Invalid native credential header.");
                    if (names.contains(lower))
                        throw new FactoryException("SECRET_HEADER_COLLISION", "Credential header conflicts with an existing request header.");
                    if (value.length() > maxBytes)
                        throw new FactoryException("REQUEST_HEADERS_TOO_LARGE", "Combined request headers exceed maxHeaderBytes.");
                    for (int i = 0; i < value.length(); i++)
                        if (value.charAt(i) < 32 || value.charAt(i) > 126)
                            throw new FactoryException("SECRET_HEADER_INVALID", "Invalid native credential header.");
                    long next = bytes + name.length() + value.length() + 4L;
                    if (next > maxBytes)
                        throw new FactoryException("REQUEST_HEADERS_TOO_LARGE", "Combined request headers exceed maxHeaderBytes.");
                    check(access, cancellation, deadline);
                    connection.setRequestProperty(name, value);
                    names.add(lower); bytes = next;
                    check(access, cancellation, deadline);
                } catch (FactoryException failure) { rejected = failure; throw failure; }
                catch (SecurityException failure) { throw failure; }
                catch (RuntimeException failure) {
                    rejected = new FactoryException("SECRET_INJECTION_FAILED", "Native credential injection could not complete.");
                    throw rejected;
                }
            }
            synchronized void close() { open = false; }
        }
        Sink sink = new Sink(count);
        try {
            injector.inject(url, method, sink);
            // A helper may sanitize a sink exception; retain the transport's fixed diagnostic only.
            if (sink.rejected != null) throw sink.rejected;
            check(access, cancellation, deadline);
        } catch (FactoryException failure) {
            check(access, cancellation, deadline);
            throw sink.rejected == null ? failure : sink.rejected;
        } catch (SecurityException failure) {
            cancellation.cancel();
            throw new SecurityException("Native credential authority ended.");
        } catch (RuntimeException failure) {
            check(access, cancellation, deadline);
            throw new FactoryException("SECRET_INJECTION_FAILED", "Native credential injection could not complete.");
        } finally { sink.close(); }
    }

    private byte[] read(InputStream stream, HttpURLConnection connection, NetworkRequest request,
                        NativeOperationAuthorization.Access authorization, Cancellation cancellation, long deadline) throws IOException, FactoryException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(request.maxResponseBytes, 8192));
        byte[] chunk = new byte[8192];
        while (true) {
            check(authorization, cancellation, deadline);
            connection.setReadTimeout(remaining(request.readTimeoutMs, deadline));
            int count = stream.read(chunk, 0, Math.min(chunk.length, request.maxResponseBytes - output.size() + 1));
            check(authorization, cancellation, deadline);
            if (count < 0) return output.toByteArray();
            if (count == 0) continue;
            if (output.size() + count > request.maxResponseBytes)
                throw new FactoryException("RESPONSE_TOO_LARGE", "Response body exceeds maxResponseBytes.");
            output.write(chunk, 0, count);
        }
    }
    private void check(NativeOperationAuthorization.Access authorization, Cancellation cancellation, long deadline) throws FactoryException {
        if (Thread.currentThread().isInterrupted()) cancellation.cancel();
        cancellation.check();
        if (clock.nanoTime() >= deadline) { cancellation.cancel("TIMEOUT"); cancellation.check(); }
        try { authorization.check(); }
        catch (RuntimeException denied) { cancellation.cancel(); throw denied; }
        cancellation.check();
        if (clock.nanoTime() >= deadline) { cancellation.cancel("TIMEOUT"); cancellation.check(); }
    }
    private int remaining(int configured, long deadline) throws FactoryException {
        long nanos = deadline - clock.nanoTime();
        if (nanos <= 0) throw new FactoryException("TIMEOUT", "Network deadline elapsed.");
        return (int) Math.min(configured, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(nanos)));
    }
    private double elapsed(long started) { return Math.max(0d, (clock.nanoTime() - started) / 1000000d); }
    private static JSONObject responseHeaders(Map<String, List<String>> source, int limit) throws FactoryException {
        JSONObject result = new JSONObject();
        int bytes = 0, entries = 0;
        if (source == null) return result;
        try {
            for (Map.Entry<String, List<String>> entry : source.entrySet()) {
                if (entry.getKey() == null) continue; // Status line.
                if (++entries > 256) throw new FactoryException("RESPONSE_TOO_LARGE", "Response has too many headers.");
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                if (key.length() > limit) throw new FactoryException("RESPONSE_TOO_LARGE", "Response headers exceed maxHeaderBytes.");
                bytes += key.getBytes(StandardCharsets.UTF_8).length + 4;
                if (bytes > limit) throw new FactoryException("RESPONSE_TOO_LARGE", "Response headers exceed maxHeaderBytes.");
                JSONArray values = result.optJSONArray(key);
                if (values == null) values = new JSONArray();
                if (entry.getValue() != null) for (String value : entry.getValue()) {
                    if (value == null) continue;
                    if (value.length() > limit) throw new FactoryException("RESPONSE_TOO_LARGE", "Response headers exceed maxHeaderBytes.");
                    bytes += value.getBytes(StandardCharsets.UTF_8).length + 2;
                    if (bytes > limit || values.length() >= 256) throw new FactoryException("RESPONSE_TOO_LARGE", "Response headers exceed maxHeaderBytes.");
                    values.put(value);
                }
                result.put(key, values);
            }
            return result;
        } catch (FactoryException failure) { throw failure; }
        catch (Exception failure) { throw new FactoryException("NETWORK_ERROR", "Invalid response headers."); }
    }
    private static Charset responseCharset(String contentType) {
        if (contentType != null) for (String part : contentType.split(";")) {
            String value = part.trim();
            if (value.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                try { return Charset.forName(value.substring(8).trim().replace("\"", "")); }
                catch (IllegalArgumentException unsupported) { return StandardCharsets.UTF_8; }
            }
        }
        return StandardCharsets.UTF_8;
    }
    private static boolean isRedirect(int status) { return status == 301 || status == 302 || status == 303 || status == 307 || status == 308; }
    private static boolean sameOrigin(URL left, URL right) {
        return left.getProtocol().equalsIgnoreCase(right.getProtocol()) && left.getHost().equalsIgnoreCase(right.getHost())
                && port(left) == port(right);
    }
    private static int port(URL url) { return url.getPort() < 0 ? url.getDefaultPort() : url.getPort(); }
    private static void disconnect(HttpURLConnection connection) {
        if (connection != null) try { connection.disconnect(); } catch (RuntimeException ignored) { }
    }
}
