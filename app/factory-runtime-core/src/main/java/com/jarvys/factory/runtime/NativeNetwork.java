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
        private DocumentHandles.Transfer document;
        private TransferProgress transferProgress;
        public void cancel() { cancel("CANCELLED"); }
        @Override public void close() { cancel(); }
        public synchronized boolean isCancelled() { return reason != null; }
        private void cancel(String code) {
            HttpURLConnection connection; DocumentHandles.Transfer selected;
            synchronized (this) {
                if (completed || reason != null) return;
                reason = code; connection = active; selected = document;
                if (transferProgress != null) { transferProgress.error = code; transferProgress.complete = false; }
            }
            // Retire provider authority first; its close is asynchronous and capacity stays charged
            // until cleanup is confirmed. No arbitrary blocking provider close on deadline threads.
            if (selected != null) selected.cancel();
            disconnect(connection);
        }
        private void attachDocument(DocumentHandles.Transfer value) throws FactoryException {
            synchronized (this) { if (reason == null) { document = value; return; } }
            value.cancel(); check();
        }
        private synchronized void detachDocument() { document = null; }
        /** Native-only diagnostics. A revoked job must not deliver response contents to its renderer. */
        public synchronized JSONObject transferProgress() throws FactoryException {
            return transferProgress == null ? null : transferProgress.snapshot();
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

    /** HTTP body transfer through one existing opaque document handle; never opens a provider.
     * Uses the same platform TLS, connection factory, authorization, and cancellation machinery.
     * No credential injector is accepted: credential scope/replay is not implied by network access.
     */
    public JSONObject executeTransfer(NetworkTransferRequest request, DocumentHandles documents,
                                      NativeOperationAuthorization.Access authorization, Cancellation cancellation) throws FactoryException {
        if (request == null || documents == null || authorization == null || cancellation == null) throw new IllegalArgumentException();
        cancellation.begin();
        long started = clock.nanoTime(), deadline = started + TimeUnit.MILLISECONDS.toNanos(request.timeoutMs);
        ScheduledFuture<?> timer = DEADLINES.schedule(() -> cancellation.cancel("TIMEOUT"), request.timeoutMs, TimeUnit.MILLISECONDS);
        TransferProgress progress = new TransferProgress(request);
        synchronized (cancellation) { cancellation.transferProgress = progress; }
        DocumentHandles.Transfer document = null;
        try {
            check(authorization, cancellation, deadline);
            document = documents.claimTransfer(request.handle, !request.upload, request.offset, request.upload ? request.length : request.maxBytes);
            progress.document = document;
            cancellation.attachDocument(document);
            checkTransfer(authorization, cancellation, deadline, document);
            try { performTransfer(request, document, authorization, cancellation, started, deadline, progress); }
            catch (IOException failure) {
                checkTransfer(authorization, cancellation, deadline, document);
                progress.error = failure instanceof SSLException ? "TLS_ERROR" : failure instanceof UnknownHostException ? "DNS_ERROR"
                        : failure instanceof SocketTimeoutException ? "TIMEOUT" : failure instanceof ProtocolException ? "NETWORK_PROTOCOL_ERROR" : "NETWORK_ERROR";
            } catch (FactoryException failure) { progress.error = failure.code; }
            // This is deliberately not checkTransfer: an I/O failure may have poisoned the handle,
            // but an authorized caller still needs the honest partial result. Authority never renews.
            check(authorization, cancellation, deadline);
            progress.elapsedMs = elapsed(started);
            JSONObject result = progress.snapshot();
            cancellation.complete();
            return result;
        } catch (FactoryException failure) { progress.complete = false; progress.error = failure.code; throw failure; }
        catch (RuntimeException denied) { progress.complete = false; progress.error = "PERMISSION_DENIED"; throw denied; }
        finally {
            progress.elapsedMs = elapsed(started);
            timer.cancel(false); cancellation.detachDocument();
            if (document != null) document.close();
            cancellation.finish();
        }
    }

    private void performTransfer(NetworkTransferRequest request, DocumentHandles.Transfer document,
                                 NativeOperationAuthorization.Access access, Cancellation cancellation,
                                 long started, long deadline, TransferProgress progress) throws FactoryException, IOException {
        NetworkRequest http = request.http;
        URL url = http.url; String method = http.method;
        Map<String, String> headers = new LinkedHashMap<>(http.headers);
        byte[] inline = request.upload ? null : http.bodyBytes();
        boolean hasBody = request.upload || http.hasBody();
        byte[] chunk = new byte[8192];
        int redirects = 0;
        while (true) {
            checkTransfer(access, cancellation, deadline, document);
            if ("http".equalsIgnoreCase(url.getProtocol()) && !cleartext.permitted(url.getHost()))
                throw new FactoryException("CLEARTEXT_NOT_PERMITTED", "Android configuration does not permit cleartext HTTP for this host.");
            checkTransfer(access, cancellation, deadline, document);
            HttpURLConnection connection = connections.open(url);
            if (connection == null) throw new IOException();
            cancellation.attach(connection);
            try {
                checkTransfer(access, cancellation, deadline, document);
                connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
                connection.setConnectTimeout(remaining(request.connectTimeoutMs, deadline));
                connection.setReadTimeout(remaining(request.readTimeoutMs, deadline));
                try { connection.setRequestMethod(method); }
                catch (ProtocolException unsupported) { throw new FactoryException("UNSUPPORTED_METHOD", "This Android HTTP transport does not support the requested method."); }
                for (Map.Entry<String, String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
                if (hasBody) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(request.upload ? request.length : (long) inline.length);
                    checkTransfer(access, cancellation, deadline, document);
                    progress.requestMayHaveReachedPeer = true;
                    try (OutputStream output = connection.getOutputStream()) {
                        long sent = 0, length = request.upload ? request.length : inline.length;
                        while (sent < length) {
                            checkTransfer(access, cancellation, deadline, document);
                            int wanted = (int) Math.min(chunk.length, length - sent), count;
                            if (request.upload) {
                                count = document.read(chunk, wanted);
                                if (count == -1) throw new FactoryException("TRANSFER_SOURCE_EOF", "Upload source ended before its declared length.");
                            } else { count = wanted; System.arraycopy(inline, (int) sent, chunk, 0, count); }
                            checkTransfer(access, cancellation, deadline, document);
                            progress.networkWriteUncertain = true;
                            output.write(chunk, 0, count);
                            progress.requestBytesWritten += count; sent += count;
                            progress.networkWriteUncertain = false;
                            checkTransfer(access, cancellation, deadline, document);
                        }
                        checkTransfer(access, cancellation, deadline, document);
                        output.flush();
                    }
                }
                checkTransfer(access, cancellation, deadline, document);
                progress.requestMayHaveReachedPeer = true;
                int status = connection.getResponseCode();
                checkTransfer(access, cancellation, deadline, document);
                if (status < 100 || status > 599) throw new IOException();
                progress.status = status; progress.url = url.toExternalForm(); progress.method = method;
                progress.headers = responseHeaders(connection.getHeaderFields(), http.maxHeaderBytes);
                progress.redirects = redirects; progress.latencyMs = elapsed(started);
                if (isRedirect(status) && !"manual".equals(http.redirect)) {
                    if (request.upload) throw new FactoryException("UPLOAD_REDIRECT_NOT_REPLAYABLE", "Upload may already have been sent; reopen a source and explicitly call the new destination.");
                    if ("error".equals(http.redirect)) throw new FactoryException("REDIRECT_DISALLOWED", "Request returned a redirect.");
                    if (redirects >= http.maxRedirects) throw new FactoryException("TOO_MANY_REDIRECTS", "Network redirect limit reached.");
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty()) throw new FactoryException("INVALID_REDIRECT", "Redirect has no destination.");
                    URL next;
                    try { next = NetworkRequest.parseUrl(new URL(url, location).toExternalForm()); }
                    catch (FactoryException | IOException invalid) { throw new FactoryException("INVALID_REDIRECT", "Redirect destination is not a supported HTTP URL."); }
                    if ("https".equalsIgnoreCase(url.getProtocol()) && "http".equalsIgnoreCase(next.getProtocol()) && !http.allowHttpsDowngrade)
                        throw new FactoryException("INSECURE_REDIRECT", "HTTPS downgrade needs allowHttpsDowngrade and Android cleartext support.");
                    if (!sameOrigin(url, next)) headers.clear();
                    if ((status == 303 && !"HEAD".equals(method)) || ((status == 301 || status == 302) && "POST".equals(method))) {
                        method = "GET"; hasBody = false; headers.remove("content-type"); headers.remove("content-encoding");
                    }
                    checkTransfer(access, cancellation, deadline, document);
                    url = next; redirects++; continue;
                }
                if (!request.upload && !"HEAD".equals(method) && status != 204 && status != 304) {
                    checkTransfer(access, cancellation, deadline, document);
                    InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    if (stream != null) try (InputStream input = stream) {
                        while (progress.responseBytesRead < request.maxBytes) {
                            checkTransfer(access, cancellation, deadline, document);
                            connection.setReadTimeout(remaining(request.readTimeoutMs, deadline));
                            int wanted = (int) Math.min(chunk.length, request.maxBytes - progress.responseBytesRead);
                            progress.responseReadUncertain = true;
                            int count = input.read(chunk, 0, wanted);
                            if (count < -1 || count == 0 || count > wanted) throw new IOException();
                            progress.responseReadUncertain = false;
                            if (count == -1) { progress.eofConfirmed = true; break; }
                            progress.responseBytesRead += count;
                            checkTransfer(access, cancellation, deadline, document);
                            document.write(chunk, count);
                            checkTransfer(access, cancellation, deadline, document);
                        }
                        if (!progress.eofConfirmed) {
                            progress.limitReached = true;
                            throw new FactoryException("TRANSFER_LIMIT_REACHED", "Transfer stopped at maxBytes; no additional byte was read to prove EOF.");
                        }
                    }
                    else throw new FactoryException("RESPONSE_BODY_UNAVAILABLE", "Response body stream was not available; EOF cannot be confirmed.");
                } else if (!request.upload) progress.eofConfirmed = true;
                checkTransfer(access, cancellation, deadline, document);
                progress.complete = true;
                return;
            } finally { cancellation.detach(connection); disconnect(connection); }
        }
    }

    private void checkTransfer(NativeOperationAuthorization.Access access, Cancellation cancellation, long deadline,
                               DocumentHandles.Transfer document) throws FactoryException {
        check(access, cancellation, deadline); document.check(); check(access, cancellation, deadline);
    }

    private static final class TransferProgress {
        final NetworkTransferRequest request;
        volatile DocumentHandles.Transfer document;
        volatile long requestBytesWritten, responseBytesRead;
        volatile boolean networkWriteUncertain, responseReadUncertain, requestMayHaveReachedPeer, complete, eofConfirmed, limitReached;
        volatile int status, redirects;
        volatile String url, method, error;
        volatile JSONObject headers = new JSONObject();
        volatile double elapsedMs, latencyMs;
        TransferProgress(NetworkTransferRequest request) { this.request = request; url = request.http.url.toExternalForm(); method = request.http.method; }
        JSONObject snapshot() throws FactoryException {
            try {
                DocumentHandles.Transfer selected = document;
                DocumentHandles.Transfer.Progress state = selected == null ? null : selected.progress();
                long bytes = state == null ? 0 : state.confirmedBytes;
                boolean unknown = state != null && state.positionUncertain;
                JSONObject result = new JSONObject().put("operation", request.upload ? "network.upload" : "network.download")
                        .put("url", url).put("method", method).put("status", status).put("headers", new JSONObject(headers.toString()))
                        .put("ok", complete && status >= 200 && status < 300).put("complete", complete).put("redirects", redirects)
                        .put("elapsedMs", elapsedMs).put("latencyMs", latencyMs).put("offset", request.offset)
                        .put("documentBytesRead", request.upload ? bytes : 0).put("documentBytesWritten", request.upload ? 0 : bytes)
                        .put("confirmedNextOffset", request.offset + bytes).put("nextOffset", unknown ? JSONObject.NULL : request.offset + bytes)
                        .put("documentPositionUncertain", unknown).put("requestBytesWritten", requestBytesWritten)
                        .put("requestBytesAreLocalWriteAcknowledgements", true).put("requestWriteUncertain", networkWriteUncertain)
                        .put("requestMayHaveReachedPeer", requestMayHaveReachedPeer).put("remoteCommitConfirmed", false)
                        .put("responseBytesRead", responseBytesRead).put("responseReadUncertain", responseReadUncertain)
                        .put("eofConfirmed", eofConfirmed).put("limitReached", limitReached)
                        .put("providerCommitConfirmed", false).put("atomicReplacement", false).put("rollbackConfirmed", false)
                        .put("partialEffectsPossible", requestMayHaveReachedPeer || bytes != 0 || unknown);
                if (error != null) result.put("error", error);
                return result;
            } catch (Exception invalid) { throw new FactoryException("UNAVAILABLE", "Transfer result unavailable."); }
        }
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
