package com.jarvys.factory.runtime;

import android.util.Base64;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class NetworkRequestTest {
    interface Checked { void run() throws Exception; }
    static JSONObject request() throws Exception { return new JSONObject().put("url", "https://service.example/items"); }
    static void invalid(Checked operation) throws Exception {
        try { operation.run(); fail("Expected INVALID_ARGUMENT"); }
        catch (FactoryException failure) { assertEquals("INVALID_ARGUMENT", failure.code); }
    }
    static String repeat(char value, int count) { char[] data = new char[count]; java.util.Arrays.fill(data, value); return new String(data); }

    @Test public void defaultsAreFiniteAndProbeIsExplicitHttpHead() throws Exception {
        NetworkRequest value = NetworkRequest.fromRequest(request());
        assertEquals("GET", value.method);
        assertEquals(65536, value.maxResponseBytes);
        assertEquals(30000, value.timeoutMs);
        assertFalse(value.allowHttpsDowngrade);
        NetworkRequest probe = NetworkRequest.fromProbe(request());
        assertEquals("HEAD", probe.method);
        assertTrue(probe.probe);
        assertFalse(probe.hasBody());
        assertEquals(0, probe.maxResponseBytes);
    }
    @Test public void supportsPrivateLoopbackCleartextAndWriteMethods() throws Exception {
        for (String url : new String[]{"http://localhost:8080/", "http://127.0.0.1/", "http://10.0.0.4/", "https://[::1]:8443/a"})
            assertEquals(url, NetworkRequest.fromRequest(request().put("url", url)).url.toExternalForm());
        for (String method : new String[]{"post", "put", "PATCH", "DELETE", "OPTIONS", "PROPFIND"}) {
            NetworkRequest value = NetworkRequest.fromRequest(request().put("method", method).put("body", "hello"));
            assertEquals(method.toUpperCase(java.util.Locale.ROOT), value.method);
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), value.bodyBytes());
        }
    }
    @Test public void rejectsAmbiguousOrNonHttpUrls() throws Exception {
        for (String url : new String[]{"file:///tmp/a", "ftp://server/a", "//service.example/", "https://a@service.example/", "https://service.example/#fragment",
                "https://service.example:0/", "https://service.example:65536/", "https://service.example/a b", "https://service.example\\evil/", "https://service.example/\r\nX: a"})
            invalid(() -> NetworkRequest.fromRequest(request().put("url", url)));
    }
    @Test public void utf8BytesAndBase64AreStrictAndBounded() throws Exception {
        NetworkRequest value = NetworkRequest.fromRequest(request().put("method", "POST").put("body", "é🌱").put("maxRequestBytes", 6));
        assertEquals(6, value.bodyBytes().length);
        invalid(() -> NetworkRequest.fromRequest(request().put("method", "POST").put("body", "é🌱").put("maxRequestBytes", 5)));
        invalid(() -> NetworkRequest.fromRequest(request().put("method", "POST").put("body", "\uD800")));
        for (String invalid : new String[]{"aA", "aA=", "aA===", "aA==\n", "aB==", "****", "===="})
            invalid(() -> NetworkRequest.fromRequest(request().put("method", "PUT").put("bodyBase64", invalid)));
        byte[] all = new byte[NetworkRequest.MAX_BODY_BYTES];
        java.util.Arrays.fill(all, (byte) 255);
        value = NetworkRequest.fromRequest(request().put("method", "PUT").put("bodyBase64", Base64.encodeToString(all, Base64.NO_WRAP))
                .put("maxRequestBytes", NetworkRequest.MAX_BODY_BYTES));
        assertArrayEquals(all, value.bodyBytes());
    }
    @Test public void headersNormalizeButCannotInjectFraming() throws Exception {
        NetworkRequest value = NetworkRequest.fromRequest(request().put("headers", new JSONObject().put("Authorization", "Bearer developer-token").put("X-App", "hello\tworld")));
        assertEquals("Bearer developer-token", value.headers.get("authorization"));
        for (String name : new String[]{"Host", "Content-Length", "Transfer-Encoding", "Connection", "Expect", "X-\nHeader"})
            invalid(() -> NetworkRequest.fromRequest(request().put("headers", new JSONObject().put(name, "test"))));
        invalid(() -> NetworkRequest.fromRequest(request().put("headers", new JSONObject().put("x", "a\r\nInjected: 1"))));
        invalid(() -> NetworkRequest.fromRequest(request().put("headers", new JSONObject().put("x", "é"))));
        invalid(() -> NetworkRequest.fromRequest(request().put("headers", new JSONObject().put("X-App", "one").put("x-app", "two"))));
        invalid(() -> NetworkRequest.fromRequest(request().put("headers", new JSONObject().put("x", repeat('a', 256))).put("maxHeaderBytes", 256)));
    }
    @Test public void finiteOptionsAreStrictlyTypedAndClosed() throws Exception {
        for (String field : new String[]{"connectTimeoutMs", "readTimeoutMs", "timeoutMs"}) {
            invalid(() -> NetworkRequest.fromRequest(request().put(field, 0)));
            invalid(() -> NetworkRequest.fromRequest(request().put(field, Integer.MAX_VALUE)));
            invalid(() -> NetworkRequest.fromRequest(request().put(field, "10")));
            invalid(() -> NetworkRequest.fromRequest(request().put(field, 10.0)));
        }
        invalid(() -> NetworkRequest.fromRequest(request().put("maxResponseBytes", NetworkRequest.MAX_BODY_BYTES + 1)));
        invalid(() -> NetworkRequest.fromRequest(request().put("maxRedirects", 11)));
        invalid(() -> NetworkRequest.fromRequest(request().put("allowHttpsDowngrade", "true")));
        invalid(() -> NetworkRequest.fromRequest(request().put("ignoreTlsErrors", true)));
        invalid(() -> NetworkRequest.fromRequest(request().put("responseType", "json")));
        invalid(() -> NetworkRequest.fromRequest(request().put("redirect", "yes")));
        invalid(() -> NetworkRequest.fromRequest(request().put("url", JSONObject.NULL)));
    }
    @Test public void unsupportedBodyCombinationsNeverSilentlyRewriteGet() throws Exception {
        for (String method : new String[]{"GET", "HEAD", "TRACE"})
            invalid(() -> NetworkRequest.fromRequest(request().put("method", method).put("body", "")));
        invalid(() -> NetworkRequest.fromRequest(request().put("method", "POST").put("body", "hi").put("bodyBase64", "aGk=")));
        invalid(() -> NetworkRequest.fromProbe(request().put("method", "POST")));
        invalid(() -> NetworkRequest.fromProbe(request().put("body", "payload")));
        invalid(() -> NetworkRequest.fromProbe(request().put("maxResponseBytes", 1)));
        invalid(() -> NetworkRequest.fromRequest(request().put("method", "GET /x")));
    }
    @Test public void requestAndAuthorizationDetailsAreImmutableSnapshots() throws Exception {
        JSONObject headers = new JSONObject().put("X-App", "original");
        JSONObject args = request().put("method", "POST").put("body", "hello").put("headers", headers);
        NetworkRequest value = NetworkRequest.fromRequest(args);
        headers.put("X-App", "changed"); args.put("body", "changed");
        assertEquals("original", value.headers.get("x-app"));
        byte[] body = value.bodyBytes(); body[0] = 0;
        assertEquals('h', value.bodyBytes()[0]);
        try { value.headers.put("x", "new"); fail(); } catch (UnsupportedOperationException expected) { }
        JSONObject details = value.authorizationDetails();
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", details.getString("bodySha256"));
        assertEquals("hello", details.getString("bodyPreview"));
        assertEquals(5, details.getInt("bodyBytes"));
        assertTrue(details.getBoolean("hasBody"));
        assertEquals(30000, details.getInt("timeoutMs"));
        details.getJSONObject("headers").put("x-app", "mutated review");
        assertEquals("original", value.authorizationDetails().getJSONObject("headers").getString("x-app"));
    }
    @Test public void reviewPreviewIsBoundedAndCannotSplitSurrogatePair() throws Exception {
        NetworkRequest value = NetworkRequest.fromRequest(request().put("method", "POST").put("body", repeat('a', 511) + "🌱tail"));
        JSONObject details = value.authorizationDetails();
        assertEquals(511, details.getString("bodyPreview").length());
        assertTrue(details.getBoolean("bodyPreviewTruncated"));
        assertEquals("utf-8", details.getString("bodyEncoding"));
    }
}
