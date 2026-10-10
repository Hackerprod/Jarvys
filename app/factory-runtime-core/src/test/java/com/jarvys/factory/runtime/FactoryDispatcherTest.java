package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.CapabilityCatalog;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.HashSet;
import static org.junit.Assert.*;

public class FactoryDispatcherTest {
    static String configuration(String capabilities) {
        return "{\"schemaVersion\":1,\"appId\":\"com.example.generated\",\"name\":\"Example\",\"entryPoint\":\"www/index.html\",\"capabilities\":" + capabilities + "}";
    }
    static FactoryConfig config() throws Exception {
        return FactoryConfig.parsePreview(configuration("[\"storage\",\"device\",\"haptics\",\"share\",\"clipboard\",\"export\"]"));
    }
    static BridgeProtocol.Request request(String method, JSONObject args, FactoryConfig config) throws Exception {
        return BridgeProtocol.validate(BridgeProtocol.ORIGIN, true, new JSONObject().put("v", 1).put("id", "test")
                .put("method", method).put("args", args).toString(), config);
    }
    private Object dispatch(String method, JSONObject args, BoundedStore store) throws Exception {
        FactoryConfig config = config();
        return FactoryDispatcher.dispatch(request(method, args, config), config, store,
                FactoryDispatcher.previewMetadata("com.jarvys.host", 35, 35), FactoryDispatcher.simulatedEffects());
    }
    @Test public void previewConfigDoesNotWeakenInstalledIdentityBinding() throws Exception {
        assertEquals("com.example.generated", config().appId);
        try { FactoryConfig.parse(configuration("[]"), "com.jarvys.host"); fail(); }
        catch (FactoryException e) { assertEquals("INVALID_CONFIG", e.code); }
        try { FactoryConfig.parse(configuration("[]"), null); fail(); }
        catch (FactoryException e) { assertEquals("INVALID_CONFIG", e.code); }
        try { FactoryConfig.parsePreview(configuration("[]").replace("www/index.html", "www/../secret.html")); fail(); }
        catch (FactoryException expected) { }
    }
    @Test public void actualSharedStorageHandlersAreIsolatedAndResettable() throws Exception {
        MemoryBackend first = new MemoryBackend(), second = new MemoryBackend();
        BoundedStore a = new BoundedStore(first), b = new BoundedStore(second);
        assertNull(dispatch("storage.set", new JSONObject().put("key", "test").put("value", "private-test"), a));
        assertEquals("private-test", dispatch("storage.get", new JSONObject().put("key", "test"), a));
        assertNull(dispatch("storage.get", new JSONObject().put("key", "test"), b));
        assertEquals("test", ((JSONArray) dispatch("storage.list", new JSONObject(), a)).getString(0));
        dispatch("storage.remove", new JSONObject().put("key", "test"), a);
        assertEquals(0, ((JSONArray) dispatch("storage.list", new JSONObject(), a)).length());
        a.set("clear", "value"); first.clear(); assertTrue(a.list().isEmpty());
        assertNotSame(first.transactionLock(), second.transactionLock());
    }
    @Test public void metadataSeparatesDeclaredAppFromActualHostAndSimulations() throws Exception {
        BoundedStore store = new BoundedStore(new MemoryBackend());
        for (String method : Arrays.asList("runtime.info", "device.info")) {
            JSONObject info = (JSONObject) dispatch(method, new JSONObject(), store);
            assertEquals("preview", info.getString("mode"));
            assertEquals("com.example.generated", info.getString("declaredAppId"));
            assertEquals("com.jarvys.host", info.getString("hostAppId"));
        }
        JSONObject device = (JSONObject) dispatch("device.info", new JSONObject(), store);
        assertEquals("com.jarvys.host", device.getString("appId"));
        assertEquals(35, device.getInt("apiLevel"));
    }
    @Test public void allFourEffectsAreExplicitSimulationsWithNoPayloadEcho() throws Exception {
        BoundedStore store = new BoundedStore(new MemoryBackend());
        for (String method : Arrays.asList("haptics.perform", "share.text", "clipboard.write", "export.text")) {
            JSONObject args = new JSONObject();
            if (!method.equals("haptics.perform")) args.put("text", "private-fixture-do-not-echo");
            if (method.equals("export.text")) args.put("filename", "fixture.txt");
            JSONObject result = (JSONObject) dispatch(method, args, store);
            assertTrue(result.getBoolean("simulated")); assertFalse(result.getBoolean("performed"));
            assertEquals(method, result.getString("operation"));
            assertFalse(result.toString().contains("private-fixture"));
        }
    }
    @Test public void dispatcherRevalidatesMutatedArgsAndCapabilityWithoutInvokingEffects() throws Exception {
        FactoryConfig config = config();
        BridgeProtocol.Request call = request("clipboard.write", new JSONObject().put("text", "ok"), config);
        call.args.put("unexpected", true);
        FactoryDispatcher.Effects forbidden = (method, args) -> { throw new AssertionError("Effects called"); };
        try { FactoryDispatcher.dispatch(call, config, new BoundedStore(new MemoryBackend()),
                FactoryDispatcher.previewMetadata("host.app", 35, 35), forbidden); fail(); }
        catch (FactoryException e) { assertEquals("INVALID_ARGUMENT", e.code); }
        call.args.remove("unexpected");
        try { FactoryDispatcher.dispatch(call, FactoryConfig.parsePreview(configuration("[]")),
                new BoundedStore(new MemoryBackend()), FactoryDispatcher.previewMetadata("host.app", 35, 35), forbidden); fail(); }
        catch (FactoryException e) { assertEquals("CAPABILITY_DENIED", e.code); }
    }
    @Test public void installedDispatcherDelegatesEachRealEffectExactlyOnce() throws Exception {
        HashSet<CapabilityCatalog.Method> observed = new HashSet<>();
        for (String method : Arrays.asList("haptics.perform", "share.text", "clipboard.write", "export.text")) {
            JSONObject args = new JSONObject();
            if (!method.equals("haptics.perform")) args.put("text", "fixture");
            if (method.equals("export.text")) args.put("filename", "fixture.txt");
            Object result = FactoryDispatcher.dispatch(request(method, args, config()), config(), new BoundedStore(new MemoryBackend()),
                    FactoryDispatcher.metadata("installed", "com.example.generated", 35, 35), (operation, value) -> {
                        assertTrue(observed.add(operation)); return "adapter-result";
                    });
            assertEquals("adapter-result", result);
        }
        assertEquals(4, observed.size());
    }
}
