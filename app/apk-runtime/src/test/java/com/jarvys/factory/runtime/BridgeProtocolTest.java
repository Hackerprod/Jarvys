package com.jarvys.factory.runtime;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BridgeProtocolTest {
    static FactoryConfig config(String capabilities) throws Exception {
        return FactoryConfig.parse("{\"schemaVersion\":1,\"appId\":\"com.example.notes\",\"name\":\"Notes\",\"entryPoint\":\"www/index.html\",\"capabilities\":" + capabilities + "}", "com.example.notes");
    }
    static String request(String method, String args) {
        return "{\"v\":1,\"id\":\"r_1\",\"method\":\"" + method + "\",\"args\":" + args + "}";
    }
    private void rejected(String expected, String origin, boolean main, String raw, FactoryConfig config) throws Exception {
        try { BridgeProtocol.validate(origin, main, raw, config); fail("Expected " + expected); }
        catch (FactoryException e) { assertEquals(expected, e.code); }
    }
    @Test public void originAndFrameAreCheckedBeforeParsing() throws Exception {
        FactoryConfig config = config("[\"storage\"]");
        String[] origins = {"null", "file://", "content://app", "http://app.jarvys.invalid", "https://evil.example", "https://app.jarvys.invalid.evil.example", "https://app.jarvys.invalid/", "https://app.jarvys.invalid:443", "https://user@app.jarvys.invalid"};
        for (String origin : origins) rejected("UNTRUSTED_SOURCE", origin, true, "not json", config);
        rejected("UNTRUSTED_SOURCE", BridgeProtocol.ORIGIN, false, request("storage.get", "{\"key\":\"notes\"}"), config);
    }
    @Test public void storageRequiresDeclarationButRuntimeIntrospectionDoesNot() throws Exception {
        FactoryConfig empty = config("[]");
        rejected("CAPABILITY_DENIED", BridgeProtocol.ORIGIN, true, request("storage.get", "{\"key\":\"notes\"}"), empty);
        assertEquals("runtime.info", BridgeProtocol.validate(BridgeProtocol.ORIGIN, true, request("runtime.info", "{}"), empty).method);
        String[][] methods = {{"export.text", "{\"filename\":\"note.txt\",\"text\":\"hi\"}"}, {"share.text", "{\"text\":\"hi\"}"}, {"clipboard.write", "{\"text\":\"hi\"}"}, {"haptics.perform", "{}"}, {"device.info", "{}"}};
        for (String[] call : methods) rejected("CAPABILITY_DENIED", BridgeProtocol.ORIGIN, true, request(call[0], call[1]), empty);
    }
    @Test public void typedAllowlistRejectsDangerousOrUnknownRequests() throws Exception {
        FactoryConfig all = config("[\"storage\",\"export\",\"share\",\"clipboard\",\"haptics\",\"device\"]");
        for (String method : new String[]{"shell.exec", "intent.start", "location.get", "network.fetch", "java.invoke", "storage.clearAll", "permission.grant"})
            rejected("UNKNOWN_METHOD", BridgeProtocol.ORIGIN, true, request(method, "{}"), all);
        rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("storage.set", "{\"key\":\"notes\",\"value\":123}"), all);
        rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("storage.get", "{\"key\":\"../otherapp\"}"), all);
        rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("device.info", "{\"serial\":true}"), all);
        rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("haptics.perform", "{\"kind\":\"continuous\"}"), all);
        rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("export.text", "{\"filename\":\"../x.txt\",\"text\":\"a\"}"), all);
        rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("export.text", "{\"filename\":\"a.apk\",\"text\":\"a\",\"mimeType\":\"application/vnd.android.package-archive\"}"), all);
        rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, request("share.text", "{\"text\":\"a\",\"targetPackage\":\"evil\"}"), all);
    }
    @Test public void strictJsonRejectsDuplicatesTrailingCommentsAndDeepNesting() throws Exception {
        FactoryConfig all = config("[\"storage\"]");
        String[] invalid = {"{'v':1}", "{v:1}", "{\"v\":1,}", "{\"v\":1,\"v\":1}", "{\"v\":1} /* comment */", "{\"a\":NaN}", "{\"a\":01}", "{\"a\":\"line\nline\"}", "{\"a\":[[[[[[[[[[1]]]]]]]]]]}"};
        for (String raw : invalid) rejected("INVALID_REQUEST", BridgeProtocol.ORIGIN, true, raw, all);
    }
    @Test public void boundedMessagesAndUtf8Values() throws Exception {
        FactoryConfig all = config("[\"storage\"]");
        String huge = new String(new char[BridgeProtocol.MAX_MESSAGE_BYTES + 1]).replace('\0', 'x');
        rejected("TOO_LARGE", BridgeProtocol.ORIGIN, true, huge, all);
        String unicode = new String(new char[22000]).replace('\0', '界');
        String raw = new JSONObject().put("v", 1).put("id", "utf8").put("method", "storage.set")
                .put("args", new JSONObject().put("key", "test").put("value", unicode)).toString();
        rejected("INVALID_ARGUMENT", BridgeProtocol.ORIGIN, true, raw, all);
        assertEquals("storage.set", BridgeProtocol.validate(BridgeProtocol.ORIGIN, true,
                request("storage.set", "{\"key\":\"notes.v1\",\"value\":\"hello\"}"), all).method);
    }
    @Test public void responsesAreEscapedAndPreserveTypedValues() throws Exception {
        JSONObject success = new JSONObject(BridgeProtocol.success("r_1", "\"quoted\"\ntext"));
        assertTrue(success.getBoolean("ok")); assertEquals("\"quoted\"\ntext", success.getString("result"));
        assertTrue(new JSONObject(BridgeProtocol.success("r_1", null)).isNull("result"));
        JSONObject failure = new JSONObject(BridgeProtocol.failure("r_1", "CANCELLED", "Cancelled"));
        assertFalse(failure.getBoolean("ok")); assertEquals("CANCELLED", failure.getJSONObject("error").getString("code"));
    }
}
