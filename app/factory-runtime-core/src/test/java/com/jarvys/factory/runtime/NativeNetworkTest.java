package com.jarvys.factory.runtime;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLHandshakeException;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class NativeNetworkTest {
    interface Checked { void run() throws Exception; }
    static final NativeOperationAuthorization.Access ALLOWED = () -> { };
    static JSONObject args() throws Exception { return new JSONObject().put("url", "https://service.example/data"); }
    static void error(String code, Checked operation) throws Exception {
        try { operation.run(); fail("Expected " + code); }
        catch (FactoryException failure) { assertEquals(code, failure.code); }
    }
    static class Fake extends HttpURLConnection {
        int status = 200, inputOpens, outputOpens, disconnects;
        byte[] response = "hello".getBytes(StandardCharsets.UTF_8);
        final Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
        final Map<String, String> requestHeaders = new LinkedHashMap<>();
        final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
        Runnable onResponse = () -> { }, onRead = () -> { }, onOutput = () -> { };
        IOException responseFailure;
        Fake() throws Exception { super(new URL("https://fake.example/")); }
        Fake status(int status) { this.status = status; return this; }
        Fake header(String name, String... values) { responseHeaders.put(name, Arrays.asList(values)); return this; }
        @Override public void connect() { connected = true; }
        @Override public void disconnect() { disconnects++; connected = false; }
        @Override public boolean usingProxy() { return false; }
        @Override public void setRequestProperty(String key, String value) { requestHeaders.put(key, value); }
        @Override public int getResponseCode() throws IOException { onResponse.run(); if (responseFailure != null) throw responseFailure; return status; }
        @Override public Map<String, List<String>> getHeaderFields() { return responseHeaders; }
        @Override public String getHeaderField(String name) {
            for (Map.Entry<String, List<String>> entry : responseHeaders.entrySet()) if (entry.getKey().equalsIgnoreCase(name))
                return entry.getValue().get(0);
            return null;
        }
        @Override public String getContentType() { return getHeaderField("Content-Type"); }
        @Override public InputStream getInputStream() {
            inputOpens++;
            return new ByteArrayInputStream(response) {
                @Override public synchronized int read(byte[] buffer, int offset, int length) { onRead.run(); return super.read(buffer, offset, length); }
            };
        }
        @Override public InputStream getErrorStream() { return getInputStream(); }
        @Override public OutputStream getOutputStream() { outputOpens++; onOutput.run(); return requestBody; }
    }
    static class Transport implements NativeNetwork.ConnectionFactory {
        final ArrayDeque<Fake> queued = new ArrayDeque<>();
        final java.util.ArrayList<URL> urls = new java.util.ArrayList<>();
        Transport(Fake... fakes) { Collections.addAll(queued, fakes); }
        @Override public HttpURLConnection open(URL url) throws IOException { urls.add(url); if (queued.isEmpty()) throw new IOException(); return queued.remove(); }
    }
    static NativeNetwork network(Transport transport) { return new NativeNetwork(transport, host -> true, System::nanoTime); }
    static JSONObject execute(Transport transport, JSONObject args) throws Exception {
        return network(transport).execute(NetworkRequest.fromRequest(args), ALLOWED, new NativeNetwork.Cancellation());
    }

    @Test public void textResponsePreservesStatusHeadersCharsetAndTiming() throws Exception {
        Fake fake = new Fake().status(404).header("Content-Type", "text/plain; charset=ISO-8859-1").header("Set-Cookie", "a=1", "b=2");
        fake.response = new byte[]{(byte) 233};
        JSONObject result = execute(new Transport(fake), args());
        assertEquals(404, result.getInt("status")); assertFalse(result.getBoolean("ok"));
        assertEquals("é", result.getString("body")); assertEquals("ISO-8859-1", result.getString("charset"));
        assertEquals(2, result.getJSONObject("headers").getJSONArray("set-cookie").length());
        assertEquals(1, fake.disconnects); assertFalse(fake.getInstanceFollowRedirects()); assertFalse(fake.getUseCaches());
        assertTrue(result.getDouble("elapsedMs") >= result.getDouble("latencyMs"));
    }
    @Test public void binaryResponseIsExactAndPostBodyIsNotChanged() throws Exception {
        Fake fake = new Fake(); fake.response = new byte[]{0, (byte) 255, 13, 10};
        JSONObject result = execute(new Transport(fake), args().put("method", "POST").put("body", "é🌱").put("responseType", "base64"));
        assertEquals("AP8NCg==", result.getString("bodyBase64")); assertEquals(4, result.getInt("bodyBytes"));
        assertArrayEquals("é🌱".getBytes(StandardCharsets.UTF_8), fake.requestBody.toByteArray());
        assertEquals("POST", fake.getRequestMethod());
    }
    @Test public void probeIsHttpResponseLatencyAndNeverReadsBodyEvenForHttpError() throws Exception {
        Fake fake = new Fake().status(503); fake.response = new byte[200000];
        AtomicLong now = new AtomicLong(); fake.onResponse = () -> now.set(123000000);
        NativeNetwork network = new NativeNetwork(new Transport(fake), host -> true, now::get);
        JSONObject result = network.execute(NetworkRequest.fromProbe(args()), ALLOWED, new NativeNetwork.Cancellation());
        assertEquals("http_response_headers", result.getString("measurement")); assertFalse(result.getBoolean("icmp"));
        assertTrue(result.getBoolean("reachable")); assertFalse(result.getBoolean("ok"));
        assertEquals(123, result.getDouble("latencyMs"), 0.0); assertEquals("HEAD", fake.getRequestMethod());
        assertEquals(0, fake.inputOpens); assertFalse(result.has("body"));
    }
    @Test public void probeReachabilityFailureDoesNotClaimIcmpOrOfflineDevice() throws Exception {
        Fake fake = new Fake(); fake.responseFailure = new UnknownHostException("private input must not leak");
        JSONObject result = network(new Transport(fake)).execute(NetworkRequest.fromProbe(args()), ALLOWED, new NativeNetwork.Cancellation());
        assertFalse(result.getBoolean("reachable")); assertEquals("DNS_ERROR", result.getString("error"));
        assertFalse(result.getBoolean("icmp")); assertFalse(result.toString().contains("private input"));
    }
    @Test public void revokedBeforeResourceAcquisitionNeverOpensConnection() throws Exception {
        Transport transport = new Transport(new Fake());
        try { network(transport).execute(NetworkRequest.fromRequest(args()), () -> { throw new SecurityException("revoked"); }, new NativeNetwork.Cancellation()); fail(); }
        catch (SecurityException expected) { assertEquals("revoked", expected.getMessage()); }
        assertEquals(0, transport.urls.size());
    }
    @Test public void revokedAfterHeadersDoesNotReadOrDeliverBody() throws Exception {
        Fake fake = new Fake(); AtomicInteger state = new AtomicInteger(); fake.onResponse = () -> state.set(1);
        try { network(new Transport(fake)).execute(NetworkRequest.fromRequest(args()), () -> { if (state.get() == 1) throw new SecurityException(); }, new NativeNetwork.Cancellation()); fail(); }
        catch (SecurityException expected) { }
        assertEquals(0, fake.inputOpens); assertTrue(fake.disconnects >= 1);
    }
    @Test public void revokedDuringReadDropsBytesAndPropagatesLeaseFailure() throws Exception {
        Fake fake = new Fake(); AtomicInteger state = new AtomicInteger(); fake.onRead = () -> state.set(1);
        try { network(new Transport(fake)).execute(NetworkRequest.fromRequest(args()), () -> { if (state.get() == 1) throw new SecurityException(); }, new NativeNetwork.Cancellation()); fail(); }
        catch (SecurityException expected) { }
        assertTrue(fake.disconnects >= 1);
    }
    @Test public void cancellationDisconnectsAndSuppressesResponse() throws Exception {
        Fake fake = new Fake(); NativeNetwork.Cancellation cancellation = new NativeNetwork.Cancellation();
        fake.onResponse = cancellation::cancel;
        error("CANCELLED", () -> network(new Transport(fake)).execute(NetworkRequest.fromRequest(args()), ALLOWED, cancellation));
        assertTrue(cancellation.isCancelled()); assertTrue(fake.disconnects >= 1); assertEquals(0, fake.inputOpens);
    }
    @Test public void cancellationBeforeStartAcquiresNothingAndCannotBeReused() throws Exception {
        NativeNetwork.Cancellation cancellation = new NativeNetwork.Cancellation(); cancellation.cancel();
        Transport transport = new Transport(new Fake());
        error("CANCELLED", () -> network(transport).execute(NetworkRequest.fromRequest(args()), ALLOWED, cancellation));
        assertEquals(0, transport.urls.size());
        error("INVALID_ARGUMENT", () -> network(transport).execute(NetworkRequest.fromRequest(args()), ALLOWED, cancellation));
    }
    @Test public void interruptedWorkerCancelsBeforeResourceAcquisition() throws Exception {
        Transport transport = new Transport(new Fake());
        try {
            Thread.currentThread().interrupt();
            error("CANCELLED", () -> network(transport).execute(NetworkRequest.fromRequest(args()), ALLOWED, new NativeNetwork.Cancellation()));
            assertEquals(0, transport.urls.size());
        } finally { Thread.interrupted(); }
    }
    @Test public void authorizationIsRecheckedBeforeRequestBodyWrite() throws Exception {
        Fake fake = new Fake(); AtomicInteger state = new AtomicInteger(); fake.onOutput = () -> state.set(1);
        try { network(new Transport(fake)).execute(NetworkRequest.fromRequest(args().put("method", "POST").put("body", "do not send")),
                () -> { if (state.get() == 1) throw new SecurityException(); }, new NativeNetwork.Cancellation()); fail(); }
        catch (SecurityException expected) { }
        assertEquals(0, fake.requestBody.size());
    }
    @Test public void exactResponseLimitPassesAndNextByteFails() throws Exception {
        JSONObject result = execute(new Transport(new Fake()), args().put("maxResponseBytes", 5));
        assertEquals("hello", result.getString("body"));
        error("RESPONSE_TOO_LARGE", () -> execute(new Transport(new Fake()), args().put("maxResponseBytes", 4)));
        Fake empty = new Fake(); empty.response = new byte[0];
        assertEquals(0, execute(new Transport(empty), args().put("maxResponseBytes", 0)).getInt("bodyBytes"));
        error("RESPONSE_TOO_LARGE", () -> execute(new Transport(new Fake()), args().put("maxResponseBytes", 0)));
    }
    @Test public void encodedEnvelopeAndResponseHeadersAreBounded() throws Exception {
        Fake controls = new Fake(); controls.response = new byte[100000];
        error("RESPONSE_TOO_LARGE", () -> execute(new Transport(controls), args().put("maxResponseBytes", 100000)));
        Fake headers = new Fake().header("Huge", NetworkRequestTest.repeat('x', 257));
        error("RESPONSE_TOO_LARGE", () -> execute(new Transport(headers), args().put("maxHeaderBytes", 256)));
        assertEquals(0, headers.inputOpens);
    }
    @Test public void cleartextUsesActualInjectedPlatformPolicy() throws Exception {
        Transport blocked = new Transport(new Fake());
        NativeNetwork network = new NativeNetwork(blocked, host -> false, System::nanoTime);
        error("CLEARTEXT_NOT_PERMITTED", () -> network.execute(NetworkRequest.fromRequest(args().put("url", "http://localhost:8080/")), ALLOWED, new NativeNetwork.Cancellation()));
        assertEquals(0, blocked.urls.size());
        assertTrue(execute(new Transport(new Fake()), args().put("url", "http://localhost:8080/")).getBoolean("cleartext"));
    }
    @Test public void sameOriginRedirectRetainsHeadersAndPost307PreservesBody() throws Exception {
        Fake first = new Fake().status(307).header("Location", "/next"), second = new Fake();
        JSONObject result = execute(new Transport(first, second), args().put("method", "POST").put("body", "payload")
                .put("headers", new JSONObject().put("Authorization", "Bearer scoped")));
        assertEquals("POST", second.getRequestMethod()); assertEquals("payload", second.requestBody.toString("UTF-8"));
        assertEquals("Bearer scoped", second.requestHeaders.get("authorization"));
        assertEquals("https://service.example/next", result.getString("url")); assertEquals(1, result.getInt("redirects"));
        assertEquals(0, first.inputOpens); assertEquals(1, first.disconnects);
    }
    @Test public void crossOriginRedirectStripsAllAppHeadersAnd303DropsBody() throws Exception {
        Fake first = new Fake().status(303).header("Location", "https://other.example/next"), second = new Fake();
        execute(new Transport(first, second), args().put("method", "POST").put("body", "payload")
                .put("headers", new JSONObject().put("Authorization", "Bearer secret").put("X-Api-Key", "secret").put("Content-Type", "text/plain")));
        assertEquals("GET", second.getRequestMethod()); assertEquals(0, second.outputOpens); assertTrue(second.requestHeaders.isEmpty());
    }
    @Test public void downgradeRequiresOptInAndStillHonorsPlatformCleartextPolicy() throws Exception {
        Fake first = new Fake().status(302).header("Location", "http://service.example/plain");
        Transport transport = new Transport(first, new Fake());
        error("INSECURE_REDIRECT", () -> execute(transport, args())); assertEquals(1, transport.urls.size());
        Fake optIn = new Fake().status(302).header("Location", "http://service.example/plain");
        assertTrue(execute(new Transport(optIn, new Fake()), args().put("allowHttpsDowngrade", true)).getBoolean("cleartext"));
        Fake blocked = new Fake().status(302).header("Location", "http://service.example/plain");
        error("CLEARTEXT_NOT_PERMITTED", () -> new NativeNetwork(new Transport(blocked, new Fake()), host -> false, System::nanoTime)
                .execute(NetworkRequest.fromRequest(args().put("allowHttpsDowngrade", true)), ALLOWED, new NativeNetwork.Cancellation()));
    }
    @Test public void manualErrorAndRedirectLimitHaveDistinctOutcomes() throws Exception {
        assertEquals(302, execute(new Transport(new Fake().status(302).header("Location", "/next")), args().put("redirect", "manual")).getInt("status"));
        error("REDIRECT_DISALLOWED", () -> execute(new Transport(new Fake().status(302).header("Location", "/next")), args().put("redirect", "error")));
        Transport limited = new Transport(new Fake().status(302).header("Location", "/next"), new Fake());
        error("TOO_MANY_REDIRECTS", () -> execute(limited, args().put("maxRedirects", 0))); assertEquals(1, limited.urls.size());
        error("INVALID_REDIRECT", () -> execute(new Transport(new Fake().status(302).header("Location", "file:///data/private")), args()));
        error("INVALID_REDIRECT", () -> execute(new Transport(new Fake().status(302)), args()));
    }
    @Test public void authorizationLossBeforeRedirectNeverOpensNextTarget() throws Exception {
        AtomicInteger state = new AtomicInteger(); Fake first = new Fake().status(302).header("Location", "/next"); first.onResponse = () -> state.set(1);
        Transport transport = new Transport(first, new Fake());
        try { network(transport).execute(NetworkRequest.fromRequest(args()), () -> { if (state.get() == 1) throw new SecurityException(); }, new NativeNetwork.Cancellation()); fail(); }
        catch (SecurityException expected) { }
        assertEquals(1, transport.urls.size());
    }
    @Test public void totalDeadlineAndSocketTimeoutRemainFinite() throws Exception {
        AtomicLong now = new AtomicLong(); Fake fake = new Fake(); fake.onResponse = () -> now.set(100000001);
        error("TIMEOUT", () -> new NativeNetwork(new Transport(fake), host -> true, now::get)
                .execute(NetworkRequest.fromRequest(args().put("timeoutMs", 100)), ALLOWED, new NativeNetwork.Cancellation()));
        assertTrue(fake.getConnectTimeout() > 0 && fake.getConnectTimeout() <= 100);
        Fake timeout = new Fake(); timeout.responseFailure = new SocketTimeoutException("private native details");
        error("TIMEOUT", () -> execute(new Transport(timeout), args()));
    }
    @Test public void tlsFailuresAreNotBypassedOrRetriedAndUnsupportedMethodIsTruthful() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        NativeNetwork network = new NativeNetwork(url -> { opens.incrementAndGet(); throw new SSLHandshakeException("secret certificate data"); }, host -> true, System::nanoTime);
        error("TLS_ERROR", () -> network.execute(NetworkRequest.fromRequest(args()), ALLOWED, new NativeNetwork.Cancellation()));
        assertEquals(1, opens.get());
        error("UNSUPPORTED_METHOD", () -> execute(new Transport(new Fake()), args().put("method", "PROPFIND")));
    }
    @Test public void headAndNoContentDoNotReadResponseStream() throws Exception {
        Fake head = new Fake(); execute(new Transport(head), args().put("method", "HEAD")); assertEquals(0, head.inputOpens);
        Fake empty = new Fake().status(204); execute(new Transport(empty), args()); assertEquals(0, empty.inputOpens);
    }
}
