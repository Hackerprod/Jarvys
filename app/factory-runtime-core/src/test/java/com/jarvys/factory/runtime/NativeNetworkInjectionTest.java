package com.jarvys.factory.runtime;

import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** No real connections, servers, credentials or AndroidKeyStore. */
public class NativeNetworkInjectionTest {
    private static final String VALUE = "Bearer synthetic-only";
    private static final NativeOperationAuthorization.Access ALLOWED = () -> { };
    interface Action { void run() throws Exception; }
    static void error(String code, Action action) throws Exception {
        try { action.run(); fail("Expected " + code); }
        catch (FactoryException failure) { assertEquals(code, failure.code); assertFalse(failure.getMessage().contains(VALUE)); }
    }
    static JSONObject args() throws Exception { return new JSONObject().put("url", "https://api.example.test/one"); }
    static final class Fake extends HttpURLConnection {
        int status = 200, effects, disconnected;
        String location, body = "ok";
        final Map<String, String> headers = new LinkedHashMap<>();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Runnable response = () -> { };
        Fake() throws Exception { super(new URL("https://ignored.example.test")); }
        Fake redirect(String location, int status) { this.location = location; this.status = status; return this; }
        @Override public void connect() { effects++; }
        @Override public void disconnect() { disconnected++; }
        @Override public boolean usingProxy() { return false; }
        @Override public void setRequestProperty(String key, String value) { headers.put(key.toLowerCase(java.util.Locale.ROOT), value); }
        @Override public int getResponseCode() { effects++; response.run(); return status; }
        @Override public Map<String, List<String>> getHeaderFields() {
            return location == null ? Collections.emptyMap() : Collections.singletonMap("Location", Arrays.asList(location));
        }
        @Override public String getHeaderField(String key) { return "Location".equalsIgnoreCase(key) ? location : null; }
        @Override public InputStream getInputStream() { effects++; return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
        @Override public OutputStream getOutputStream() { effects++; return output; }
    }
    static final class Transport implements NativeNetwork.ConnectionFactory {
        final ArrayDeque<Fake> queued = new ArrayDeque<>();
        final List<URL> opened = new ArrayList<>();
        Transport(Fake... values) { Collections.addAll(queued, values); }
        @Override public HttpURLConnection open(URL url) throws IOException { opened.add(url); if (queued.isEmpty()) throw new IOException(); return queued.remove(); }
    }
    static NativeNetwork network(Transport transport) { return new NativeNetwork(transport, host -> true, System::nanoTime); }
    static JSONObject execute(Transport transport, JSONObject args, NativeNetwork.HeaderInjector injector) throws Exception {
        return network(transport).execute(NetworkRequest.fromRequest(args), ALLOWED, new NativeNetwork.Cancellation(), injector);
    }
    static NativeNetwork.HeaderInjector scoped(String... methods) {
        return (url, method, sink) -> {
            if (!"https".equals(url.getProtocol()) || !"api.example.test".equals(url.getHost())
                    || url.getPort() != -1 || !Arrays.asList(methods).contains(method))
                throw new FactoryException("SECRET_SCOPE_MISMATCH", "Credential is not configured for this destination and method.");
            sink.set("Authorization", VALUE);
        };
    }
    @Test public void injectsAfterOrdinaryHeadersWithoutMutatingReusableRequest() throws Exception {
        Fake fake = new Fake(); Transport transport = new Transport(fake);
        NetworkRequest request = NetworkRequest.fromRequest(args().put("headers", new JSONObject().put("X-Ordinary", "yes")));
        JSONObject result = network(transport).execute(request, ALLOWED, new NativeNetwork.Cancellation(), (url, method, sink) -> {
            assertEquals("yes", fake.headers.get("x-ordinary")); assertFalse(fake.headers.containsKey("authorization"));
            sink.set("Authorization", VALUE);
        });
        assertEquals(VALUE, fake.headers.get("authorization")); assertFalse(request.headers.containsKey("authorization"));
        assertFalse(request.authorizationDetails().toString().contains(VALUE)); assertFalse(result.toString().contains(VALUE));
        assertFalse(fake.getInstanceFollowRedirects()); assertTrue(fake.disconnected > 0);
    }
    @Test public void plainNetworkHasNoInjectedHeaderAndKeepsHttpSupport() throws Exception {
        Fake fake = new Fake(); execute(new Transport(fake), args().put("url", "http://api.example.test/one"), null);
        assertFalse(fake.headers.containsKey("authorization"));
    }
    @Test public void cleartextIsRejectedBeforeOpeningWhenInjectorPresent() throws Exception {
        Transport transport = new Transport(new Fake());
        error("SECRET_SCOPE_MISMATCH", () -> execute(transport, args().put("url", "http://api.example.test/one"), scoped("GET")));
        assertTrue(transport.opened.isEmpty());
    }
    @Test public void requestHeaderCollisionIsCaseInsensitiveAndNoSocketEffectOccurs() throws Exception {
        Fake fake = new Fake();
        error("SECRET_HEADER_COLLISION", () -> execute(new Transport(fake), args().put("headers", new JSONObject().put("aUtHoRiZaTiOn", "ordinary")), scoped("GET")));
        assertEquals("ordinary", fake.headers.get("authorization")); assertEquals(0, fake.effects);
    }
    @Test public void combinedHeaderCapCountsOrdinaryAndInjectedBytes() throws Exception {
        Fake fake = new Fake();
        String ordinary = String.join("", Collections.nCopies(225, "x"));
        error("REQUEST_HEADERS_TOO_LARGE", () -> execute(new Transport(fake), args().put("maxHeaderBytes", 256)
                .put("headers", new JSONObject().put("X", ordinary)), scoped("GET")));
        assertFalse(fake.headers.containsKey("authorization")); assertEquals(0, fake.effects);
    }
    @Test public void exactCombinedHeaderCapIsAllowed() throws Exception {
        Fake fake = new Fake();
        int remaining = 256 - ("Authorization".length() + VALUE.length() + 4) - 5;
        execute(new Transport(fake), args().put("maxHeaderBytes", 256).put("headers", new JSONObject()
                .put("X", String.join("", Collections.nCopies(remaining, "x")))), scoped("GET"));
        assertEquals(VALUE, fake.headers.get("authorization"));
    }
    @Test public void sinkRetainsTransportReasonEvenWhenHelperSanitizesIt() throws Exception {
        Fake fake = new Fake();
        error("SECRET_HEADER_COLLISION", () -> execute(new Transport(fake), args().put("headers", new JSONObject().put("Authorization", "ordinary")),
                (url, method, sink) -> {
                    try { sink.set("Authorization", VALUE); }
                    catch (Exception ignored) { throw new FactoryException("SECRET_INJECTION_FAILED", "Sanitized helper failure."); }
                }));
        assertEquals(0, fake.effects);
    }
    @Test public void freshRedirectInjectionUsesActualUrlAndRewrittenMethod() throws Exception {
        Fake first = new Fake().redirect("/two", 303), second = new Fake();
        List<String> calls = new ArrayList<>(); NativeNetwork.HeaderInjector delegate = scoped("GET", "POST");
        execute(new Transport(first, second), args().put("method", "POST").put("body", "payload"), (url, method, sink) -> {
            calls.add(method + " " + url.getPath()); delegate.inject(url, method, sink);
        });
        assertEquals(Arrays.asList("POST /one", "GET /two"), calls);
        assertEquals(VALUE, first.headers.get("authorization")); assertEquals(VALUE, second.headers.get("authorization"));
        assertEquals(0, second.output.size()); assertEquals("GET", second.getRequestMethod());
    }
    @Test public void redirectMethodMustAlsoBelongToScope() throws Exception {
        Fake first = new Fake().redirect("/two", 303), second = new Fake();
        error("SECRET_SCOPE_MISMATCH", () -> execute(new Transport(first, second), args().put("method", "POST").put("body", "payload"), scoped("POST")));
        assertTrue(second.headers.isEmpty()); assertEquals(0, second.effects);
    }
    @Test public void redirectOriginAndPortCannotInheritCredential() throws Exception {
        for (String location : new String[]{"https://other.example.test/path", "https://api.example.test:444/path"}) {
            Fake first = new Fake().redirect(location, 302), second = new Fake();
            error("SECRET_SCOPE_MISMATCH", () -> execute(new Transport(first, second), args().put("headers", new JSONObject().put("X-Ordinary", "private")), scoped("GET")));
            assertTrue(second.headers.isEmpty()); assertEquals(0, second.effects);
        }
    }
    @Test public void downgradeIsBlockedEvenWhenPlainRequestWouldAllowIt() throws Exception {
        Fake first = new Fake().redirect("http://api.example.test/two", 302); Transport transport = new Transport(first, new Fake());
        error("INSECURE_REDIRECT", () -> execute(transport, args().put("allowHttpsDowngrade", true), scoped("GET")));
        assertEquals(1, transport.opened.size());
    }
    @Test public void authorityRevokedAfterInjectionPreventsSocketAndReply() throws Exception {
        Fake fake = new Fake(); AtomicBoolean live = new AtomicBoolean(true);
        try {
            network(new Transport(fake)).execute(NetworkRequest.fromRequest(args()), () -> { if (!live.get()) throw new SecurityException(); },
                    new NativeNetwork.Cancellation(), (url, method, sink) -> { sink.set("Authorization", VALUE); live.set(false); });
            fail();
        } catch (SecurityException expected) { }
        assertEquals(0, fake.effects); assertTrue(fake.disconnected > 0);
    }
    @Test public void completedSinkCannotBeRetainedForLaterHeaderWrites() throws Exception {
        Fake fake = new Fake(); NativeNetwork.HeaderSink[] retained = new NativeNetwork.HeaderSink[1];
        execute(new Transport(fake), args(), (url, method, sink) -> { retained[0] = sink; sink.set("Authorization", VALUE); });
        error("SECRET_INJECTION_FAILED", () -> retained[0].set("X-Later", "not allowed"));
        assertFalse(fake.headers.containsKey("x-later"));
    }
    @Test public void reservedHeadersAndCrLfNeverReachConnection() throws Exception {
        Fake fake = new Fake();
        error("SECRET_HEADER_INVALID", () -> execute(new Transport(fake), args(), (url, method, sink) -> sink.set("Proxy-Authorization", VALUE)));
        assertTrue(fake.headers.isEmpty()); assertEquals(0, fake.effects);
        Fake second = new Fake();
        error("SECRET_HEADER_INVALID", () -> execute(new Transport(second), args(), (url, method, sink) -> sink.set("Authorization", "a\r\nb")));
        assertTrue(second.headers.isEmpty()); assertEquals(0, second.effects);
    }
    @Test public void nativeInjectionExceptionDetailsAreSanitized() throws Exception {
        error("SECRET_INJECTION_FAILED", () -> execute(new Transport(new Fake()), args(), (url, method, sink) -> { throw new IllegalStateException(VALUE); }));
    }
    @Test public void responseEchoCanReachCallerAndIsNotClaimedAsNeverJs() throws Exception {
        Fake fake = new Fake(); fake.body = VALUE;
        JSONObject result = execute(new Transport(fake), args(), scoped("GET"));
        assertEquals(VALUE, result.getString("body"));
    }
}
