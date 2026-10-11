package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.net.URL;
import static org.junit.Assert.*;

public final class SecretRequestTest {
    private interface Action { void run() throws Exception; }
    private static void invalid(Action action) throws Exception {
        try { action.run(); fail("Expected a rejected typed request"); }
        catch (FactoryException expected) {
            assertTrue(expected.code.equals("INVALID_ARGUMENT") || expected.code.equals("INVALID_REQUEST") || expected.code.equals("TOO_LARGE"));
            assertFalse(expected.getMessage().contains("private-fixture"));
        }
    }
    static JSONObject scope() throws Exception {
        return new JSONObject().put("origin", "https://api.example.test")
                .put("methods", new JSONArray().put("GET").put("POST"))
                .put("header", "Authorization").put("prefix", "Bearer ");
    }
    static JSONObject capture() throws Exception { return new JSONObject().put("label", "Example token").put("scope", scope()); }
    static JSONObject put() throws Exception { return capture().put("value", "private-fixture-token"); }
    @Test public void putUsesOpaqueMetadataAndConsumedCopy() throws Exception {
        SecretRequest.Put request = SecretRequest.parsePut(put());
        assertEquals("Example token", request.label);
        assertFalse(request.authorizationDetails().toString().contains("private-fixture"));
        assertFalse(request.authorizationDetails().has("value"));
        assertFalse(request.authorizationDetails().has("valueHash"));
        assertFalse(request.authorizationDetails().has("valueBytes"));
        request.close();
        try { request.copyValue(); fail(); } catch (FactoryException expected) { assertEquals("CANCELLED", expected.code); }
    }
    @Test public void captureHasNoValueAndOptionalLabel() throws Exception {
        SecretRequest.Capture request = SecretRequest.parseCapture(new JSONObject().put("scope", scope()));
        assertEquals("", request.label); assertNull(request.replaceId);
        invalid(() -> SecretRequest.parseCapture(put()));
    }
    @Test public void inputTypesAreStrictAndClosed() throws Exception {
        for (Object value : new Object[]{JSONObject.NULL, true, 12, new JSONArray(), new JSONObject(), ""})
            invalid(() -> SecretRequest.parsePut(put().put("value", value)));
        invalid(() -> SecretRequest.parsePut(put().put("value", "\ud800")));
        invalid(() -> SecretRequest.parsePut(put().put("value", repeat('x', SecretRequest.MAX_VALUE_BYTES + 1))));
        invalid(() -> SecretRequest.parsePut(put().put("value", repeat('é', SecretRequest.MAX_VALUE_BYTES))));
        invalid(() -> SecretRequest.parsePut(put().put("unsafe", true)));
        invalid(() -> SecretRequest.parseCapture(capture().put("scope", JSONObject.NULL)));
        invalid(() -> SecretRequest.parseCapture(capture().put("label", JSONObject.NULL)));
        invalid(() -> SecretRequest.parseCapture(capture().put("label", "private-fixture\n")));
        invalid(() -> SecretRequest.parseCapture(capture().put("label", repeat('x', 81))));
    }
    @Test public void duplicateAndLenientJsonAreRejected() throws Exception {
        invalid(() -> SecretRequest.parsePut("{\"value\":\"a\",\"value\":\"b\"}"));
        invalid(() -> SecretRequest.parseCapture("{'scope':{}}"));
        invalid(() -> SecretRequest.parseCapture("{\"scope\":{},}"));
        invalid(() -> SecretRequest.parseId("{\"id\":\"a\",\"id\":\"b\"}"));
    }
    @Test public void canonicalHttpsOriginsRespectExactPortsAndHosts() throws Exception {
        SecretRequest.Scope rule = SecretRequest.Scope.parse(scope().put("origin", "HTTPS://API.EXAMPLE.TEST:443/"));
        assertEquals("https://api.example.test", rule.origin);
        assertTrue(rule.permits(new URL("https://api.example.test/v1?x=1"), "GET"));
        assertTrue(rule.permits(new URL("https://api.example.test/résumé"), "GET"));
        assertTrue(rule.permits(new URL("https://API.example.test:443/v2"), "POST"));
        for (String url : new String[]{"http://api.example.test/", "https://api.example.test:444/", "https://sub.api.example.test/",
                "https://api.example.test.evil.test/", "https://api.example.test./", "https://user@api.example.test/",
                "https://api.example.test/#fragment", "https://other.example.test/"})
            assertFalse(url, rule.permits(new URL(url), "GET"));
        assertFalse(rule.permits(new URL("https://api.example.test/"), "DELETE"));
        assertFalse(rule.permits(new URL("https://api.example.test/"), "get"));
        assertFalse(rule.permits(null, "GET"));
    }
    @Test public void originsCannotContainPatternsCredentialsPathsOrQueries() throws Exception {
        for (String value : new String[]{"http://api.example.test", "https://*.example.test", "https://u:p@api.example.test",
                "https://api.example.test/path", "https://api.example.test/?x=1", "https://api.example.test#x",
                "https://api.example.test:0", "https://api.example.test:65536", "https://api.example.test\\evil",
                "https://api.example.test.", " https://api.example.test", "https://api.example.test\n"})
            invalid(() -> SecretRequest.Scope.parse(scope().put("origin", value)));
    }
    @Test public void ipOriginsAreLiteralAndDoNotResolveDns() throws Exception {
        SecretRequest.Scope v6 = SecretRequest.Scope.parse(scope().put("origin", "https://[::1]:8443"));
        assertTrue(v6.permits(new URL("https://[::1]:8443/a"), "GET"));
        assertFalse(v6.permits(new URL("https://[::1]/a"), "GET"));
        SecretRequest.Scope v4 = SecretRequest.Scope.parse(scope().put("origin", "https://127.0.0.1"));
        assertTrue(v4.permits(new URL("https://127.0.0.1/a"), "GET"));
    }
    @Test public void scopeMethodGrammarIsExact() throws Exception {
        for (JSONArray methods : new JSONArray[]{new JSONArray(), new JSONArray().put("get"), new JSONArray().put("*"),
                new JSONArray().put("GET").put("GET"), new JSONArray().put(1), new JSONArray().put(JSONObject.NULL),
                new JSONArray().put("GET /"), new JSONArray().put("GET\r\n")})
            invalid(() -> SecretRequest.Scope.parse(scope().put("methods", methods)));
        invalid(() -> SecretRequest.Scope.parse(scope().put("methods", "GET")));
        assertTrue(SecretRequest.Scope.parse(scope().put("methods", new JSONArray().put("CUSTOM_METHOD"))).methods.contains("CUSTOM_METHOD"));
    }
    @Test public void developerHeadersAreAllowedExceptReservedTransportFields() throws Exception {
        for (String header : new String[]{"Authorization", "api-key", "X-Credential", "Cookie", "custom_token", "Content-Type", "Content-Encoding", "Set-Cookie"})
            assertEquals(header.toLowerCase(java.util.Locale.ROOT), SecretRequest.Scope.parse(scope().put("header", header)).header);
        for (String header : new String[]{"Host", "Content-Length", "Transfer-Encoding", "Connection", "Proxy-Authorization",
                "TE", "Expect", "x\r\n", "x y", "", "é"})
            invalid(() -> SecretRequest.Scope.parse(scope().put("header", header)));
        for (String prefix : new String[]{"Bearer\r\n", "é", repeat('a', 129)})
            invalid(() -> SecretRequest.Scope.parse(scope().put("prefix", prefix)));
        invalid(() -> SecretRequest.Scope.parse(scope().put("wildcard", true)));
    }
    @Test public void scopeSnapshotsCannotBeMutatedAfterAuthorization() throws Exception {
        JSONObject scope = scope();
        SecretRequest.Scope rule = SecretRequest.Scope.parse(scope);
        scope.getJSONArray("methods").put("DELETE"); scope.put("origin", "https://evil.test");
        assertEquals("https://api.example.test", rule.origin); assertFalse(rule.methods.contains("DELETE"));
        try { rule.methods.add("DELETE"); fail(); } catch (UnsupportedOperationException expected) { }
        rule.metadata().put("origin", "https://evil.test");
        assertEquals("https://api.example.test", rule.metadata().getString("origin"));
    }
    @Test public void IDsAreOpaqueBoundedAndCannotBePathsOrKeyAliases() throws Exception {
        String id = "sec_0123456789abcdef0123456789abcdef";
        assertEquals(id, SecretRequest.parseId(new JSONObject().put("id", id)));
        assertEquals(id, SecretRequest.parseCapture(capture().put("replaceId", id)).replaceId);
        for (String candidate : new String[]{"token", "../vault.bin", "jfsec.v1.key", "sec_ABCDEF", "sec_0123456789abcdef0123456789abcdef0"})
            invalid(() -> SecretRequest.parseId(new JSONObject().put("id", candidate)));
        invalid(() -> SecretRequest.parseId(new JSONObject().put("id", id).put("value", "private-fixture")));
        invalid(() -> SecretRequest.parseCapture(capture().put("replaceId", JSONObject.NULL)));
    }
    @Test public void getRemoveDescribeBindingContainsOnlyOpaqueId() throws Exception {
        String id = "sec_0123456789abcdef0123456789abcdef";
        JSONObject details = SecretRequest.idAuthorizationDetails(id);
        assertEquals(1, details.length()); assertEquals(id, details.getString("id"));
        invalid(() -> SecretRequest.idAuthorizationDetails("private-fixture"));
    }
    @Test public void injectionBindingContainsDestinationMethodAndIdWithoutLoadingVault() throws Exception {
        String id = "sec_0123456789abcdef0123456789abcdef";
        JSONObject details = SecretRequest.injectionAuthorizationDetails(id, new URL("https://api.example.test/v1"), "POST");
        assertEquals(3, details.length()); assertEquals(id, details.getString("id"));
        assertEquals("POST", details.getString("method")); assertEquals("https://api.example.test/v1", details.getString("url"));
        invalid(() -> SecretRequest.injectionAuthorizationDetails(id, new URL("http://api.example.test/"), "POST"));
    }
    private static String repeat(char character, int count) { char[] chars = new char[count]; java.util.Arrays.fill(chars, character); return new String(chars); }
}
