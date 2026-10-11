package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SecretEffectsPreparedTest {
    private static final String ID = "sec_0123456789abcdef0123456789abcdef";
    interface Action { void run() throws Exception; }
    static void error(String code, Action action) throws Exception {
        try { action.run(); fail("Expected " + code); }
        catch (FactoryException failure) { assertEquals(code, failure.code); }
    }
    static JSONObject metadata() throws Exception {
        return new JSONObject().put("label", "Demo credential").put("scope", new JSONObject()
                .put("origin", "https://api.example.test").put("methods", new JSONArray().put("GET").put("POST"))
                .put("header", "Authorization").put("prefix", "Bearer "));
    }
    static JSONObject request() throws Exception {
        return new JSONObject().put("id", ID).put("request", new JSONObject().put("url", "https://api.example.test/path"));
    }
    @Test public void putReviewNeverContainsValueHashOrSize() throws Exception {
        try (SecretEffects.Prepared prepared = SecretEffects.prepare("secrets.put", metadata().put("value", "synthetic-sensitive-marker"))) {
            JSONObject details = prepared.authorizationDetails();
            assertEquals(2, details.length()); assertTrue(details.has("label")); assertTrue(details.has("scope"));
            assertFalse(details.toString().contains("synthetic-sensitive-marker"));
            assertFalse(details.has("value")); assertFalse(details.has("valueBytes")); assertFalse(details.has("valueSha256"));
        }
    }
    @Test public void captureCannotAcceptAnEmbeddedValue() throws Exception {
        error("INVALID_ARGUMENT", () -> SecretEffects.prepare("secrets.capture", metadata().put("value", "synthetic")));
    }
    @Test public void preparedMetadataIsSnapshotNotMutatedJson() throws Exception {
        JSONObject args = metadata();
        try (SecretEffects.Prepared prepared = SecretEffects.prepare("secrets.capture", args)) {
            args.getJSONObject("scope").put("origin", "https://changed.example.test"); args.put("label", "changed");
            JSONObject details = prepared.authorizationDetails(); details.put("label", "second mutation");
            assertEquals("Demo credential", prepared.authorizationDetails().getString("label"));
            assertEquals("https://api.example.test", prepared.authorizationDetails().getJSONObject("scope").getString("origin"));
        }
    }
    @Test public void requestBindingContainsIdAndFullExistingNetworkReview() throws Exception {
        JSONObject args = request(); args.getJSONObject("request").put("method", "POST").put("body", "hello")
                .put("headers", new JSONObject().put("X-Test", "ordinary"));
        try (SecretEffects.Prepared prepared = SecretEffects.prepare("secrets.request", args)) {
            JSONObject details = prepared.authorizationDetails();
            assertEquals(2, details.length()); assertEquals(ID, details.getString("id"));
            JSONObject bound = details.getJSONObject("request");
            assertEquals("POST", bound.getString("method")); assertEquals(5, bound.getInt("bodyBytes"));
            assertTrue(bound.has("bodySha256")); assertTrue(bound.has("maxHeaderBytes")); assertTrue(bound.has("redirect"));
            args.getJSONObject("request").put("url", "https://other.example.test").put("body", "changed");
            assertEquals("https://api.example.test/path", prepared.authorizationDetails().getJSONObject("request").getString("url"));
            assertEquals(5, prepared.authorizationDetails().getJSONObject("request").getInt("bodyBytes"));
        }
    }
    @Test public void requestHasClosedOuterAndInnerGrammar() throws Exception {
        error("INVALID_ARGUMENT", () -> SecretEffects.prepare("secrets.request", request().put("value", "synthetic")));
        JSONObject args = request(); args.getJSONObject("request").put("secretId", ID);
        error("INVALID_ARGUMENT", () -> SecretEffects.prepare("secrets.request", args));
        error("INVALID_ARGUMENT", () -> SecretEffects.prepare("secrets.request", request().put("id", 12)));
        error("INVALID_ARGUMENT", () -> SecretEffects.prepare("secrets.request", request().put("request", "{}")));
    }
    @Test public void credentialRequestRejectsInitialCleartextButPlainNetworkStillAcceptsIt() throws Exception {
        JSONObject args = request(); args.getJSONObject("request").put("url", "http://api.example.test/");
        error("INVALID_ARGUMENT", () -> SecretEffects.prepare("secrets.request", args));
        assertEquals("http", NetworkRequest.fromRequest(args.getJSONObject("request")).url.getProtocol());
    }
    @Test public void listResetAndOpaqueIdOperationsStaySeparate() throws Exception {
        for (String method : new String[]{"secrets.get", "secrets.describe", "secrets.remove"}) {
            try (SecretEffects.Prepared prepared = SecretEffects.prepare(method, new JSONObject().put("id", ID))) {
                assertEquals(method, prepared.method); assertEquals(ID, prepared.authorizationDetails().getString("id"));
            }
            error("INVALID_ARGUMENT", () -> SecretEffects.prepare(method, new JSONObject().put("id", "friendly-name")));
        }
        for (String method : new String[]{"secrets.list", "secrets.reset"}) {
            try (SecretEffects.Prepared prepared = SecretEffects.prepare(method, new JSONObject())) {
                assertEquals(0, prepared.authorizationDetails().length());
            }
            error("INVALID_ARGUMENT", () -> SecretEffects.prepare(method, new JSONObject().put("confirm", true)));
        }
    }
    @Test public void closedPreparedCannotProduceReviewAgain() throws Exception {
        SecretEffects.Prepared prepared = SecretEffects.prepare("secrets.put", metadata().put("value", "synthetic"));
        prepared.close(); prepared.close(); error("CANCELLED", prepared::authorizationDetails);
    }
}
