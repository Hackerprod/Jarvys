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
    @Test public void fileSharingIsUnavailableInPreviewAndRequiresBothCapabilitiesAtDispatch() throws Exception {
        FactoryConfig both=FactoryConfig.parsePreview(configuration("[\"share\",\"documents\"]"));
        JSONObject args=new JSONObject().put("handle",new String(new char[64]).replace('\0','a')).put("filename","file.bin").put("mimeType","application/octet-stream");
        BridgeProtocol.Request request=request("share.file",args,both);
        try { FactoryDispatcher.dispatch(request,both,new BoundedStore(new MemoryBackend()),
                FactoryDispatcher.previewMetadata("host",35,35),FactoryDispatcher.simulatedEffects()); fail(); }
        catch(FactoryException expected){assertEquals("UNAVAILABLE",expected.code);}
        for(String caps:Arrays.asList("[]","[\"share\"]","[\"documents\"]")) {
            try { FactoryDispatcher.dispatch(request,FactoryConfig.parsePreview(configuration(caps)),new BoundedStore(new MemoryBackend()),
                    FactoryDispatcher.previewMetadata("host",35,35),(method,value)->{throw new AssertionError("Effect invoked");});fail();}
            catch(FactoryException expected){assertEquals("CAPABILITY_DENIED",expected.code);}
        }
        assertEquals("adapter",FactoryDispatcher.dispatch(request,both,new BoundedStore(new MemoryBackend()),
            FactoryDispatcher.metadata("installed","app",35,35),(method,value)->{assertEquals(CapabilityCatalog.Method.SHARE_FILE,method);return "adapter";}));
        JSONObject info=(JSONObject)FactoryDispatcher.dispatch(request("runtime.info",new JSONObject(),both),both,new BoundedStore(new MemoryBackend()),
                FactoryDispatcher.previewMetadata("host",35,35),FactoryDispatcher.simulatedEffects());
        assertEquals(8388608,info.getJSONObject("limits").getInt("fileShareBytes"));
        assertEquals("share.file",info.getJSONArray("unavailableMethods").getString(0));
        assertEquals("[\"documents\",\"share\"]",info.getJSONArray("fileShareRequires").toString());
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
    @Test public void photosAreUnavailableInPreviewAndInstalledDispatchRevalidatesBothCapabilities() throws Exception {
        FactoryConfig both = FactoryConfig.parsePreview(configuration("[\"photos\",\"documents\"]"));
        BoundedStore store = new BoundedStore(new MemoryBackend());
        for (String method : Arrays.asList("photos.pick", "photos.capture")) {
            BridgeProtocol.Request call = request(method, new JSONObject(), both);
            try { FactoryDispatcher.dispatch(call, both, store,
                    FactoryDispatcher.previewMetadata("host", 35, 35), FactoryDispatcher.simulatedEffects()); fail(); }
            catch (FactoryException expected) { assertEquals("UNAVAILABLE", expected.code); }
            for (String caps : Arrays.asList("[]", "[\"photos\"]", "[\"documents\"]")) {
                try { FactoryDispatcher.dispatch(call, FactoryConfig.parsePreview(configuration(caps)), store,
                        FactoryDispatcher.metadata("installed", "app", 35, 35),
                        (operation, args) -> { throw new AssertionError("Effect invoked"); }); fail(); }
                catch (FactoryException expected) { assertEquals("CAPABILITY_DENIED", expected.code); }
            }
            assertEquals("adapter", FactoryDispatcher.dispatch(call, both, store,
                    FactoryDispatcher.metadata("installed", "app", 35, 35),
                    (operation, args) -> { assertEquals(method, operation.wireName); return "adapter"; }));
            call.args.put("uri", "content://forbidden");
            try { FactoryDispatcher.dispatch(call, both, store,
                    FactoryDispatcher.metadata("installed", "app", 35, 35),
                    (operation, args) -> { throw new AssertionError("Effect invoked"); }); fail(); }
            catch (FactoryException expected) { assertEquals("INVALID_ARGUMENT", expected.code); }
        }
        JSONObject info = (JSONObject) FactoryDispatcher.dispatch(request("runtime.info", new JSONObject(), both), both, store,
                FactoryDispatcher.previewMetadata("host", 35, 35), FactoryDispatcher.simulatedEffects());
        assertEquals(2, info.getInt("sdkVersion"));
        assertEquals(1, info.getInt("photoProtocolVersion"));
        assertTrue(info.getBoolean("photoBrokerRequired"));
        assertEquals("[\"documents\",\"photos\"]", info.getJSONArray("photoRequires").toString());
        assertTrue(info.getJSONArray("unavailableCapabilities").toString().contains("photos"));
        assertTrue(info.getJSONArray("unavailableMethods").toString().contains("photos.pick"));
        assertTrue(info.getJSONArray("unavailableMethods").toString().contains("photos.capture"));
        JSONObject installed = (JSONObject) FactoryDispatcher.dispatch(request("runtime.info", new JSONObject(), both), both, store,
                FactoryDispatcher.metadata("installed", "app", 35, 35), FactoryDispatcher.simulatedEffects());
        assertEquals(0, installed.getJSONArray("unavailableMethods").length());
        assertEquals(0, installed.getJSONArray("unavailableCapabilities").length());
    }

    @Test public void photosCapabilityNeverAddsManifestPermissionsComponentsOrQueries() {
        CapabilityCatalog.Capability photos = CapabilityCatalog.CAPABILITIES.get("photos");
        assertNotNull(photos);
        assertEquals(2, photos.methods.size());
        assertTrue(photos.manifestNodes.isEmpty());
        assertTrue(photos.permissions.isEmpty());
        assertTrue(photos.features.isEmpty());
        assertTrue(photos.components.isEmpty());
        assertTrue(photos.queries.isEmpty());
        assertTrue(CapabilityCatalog.manifestContributions(Arrays.asList("photos")).isEmpty());
        assertEquals(CapabilityCatalog.manifestContributions(Arrays.asList("documents")).size(),
                CapabilityCatalog.manifestContributions(Arrays.asList("documents", "photos")).size());
    }

    @org.junit.Test public void audioDispatchRequiresBothCapabilitiesAndPreviewNeverPlays() throws Exception {
        FactoryConfig both=FactoryConfig.parsePreview(configuration("[\"audio\",\"documents\"]"));
        JSONObject args=new JSONObject().put("handle",new String(new char[64]).replace('\0','a'));
        BridgeProtocol.Request request=request("audio.play",args,both);
        try { FactoryDispatcher.dispatch(request,both,new BoundedStore(new MemoryBackend()),
                FactoryDispatcher.previewMetadata("host",35,35),FactoryDispatcher.simulatedEffects()); fail(); }
        catch(FactoryException denied){assertEquals("UNAVAILABLE",denied.code);}
        assertEquals("adapter",FactoryDispatcher.dispatch(request,both,new BoundedStore(new MemoryBackend()),
                FactoryDispatcher.metadata("installed","app",35,35),(method,value)->{assertEquals(CapabilityCatalog.Method.AUDIO_PLAY,method);return "adapter";}));
        JSONObject info=(JSONObject)FactoryDispatcher.dispatch(request("runtime.info",new JSONObject(),both),both,new BoundedStore(new MemoryBackend()),
                FactoryDispatcher.previewMetadata("host",35,35),FactoryDispatcher.simulatedEffects());
        assertEquals(1,info.getInt("audioProtocolVersion"));assertTrue(info.getBoolean("audioBrokerRequired"));
        assertEquals("[\"documents\",\"audio\"]",info.getJSONArray("audioRequires").toString());
        assertEquals("[\"documents\",\"photos\"]",info.getJSONArray("photoRequires").toString());
        assertTrue(info.getJSONArray("unavailableMethods").toString().contains("audio.play"));
        assertEquals("wav-pcm16",info.getJSONArray("audioFormats").getString(0));
        assertEquals(6291456,info.getJSONObject("limits").getInt("audioBytes"));
        assertEquals(30000,info.getJSONObject("limits").getInt("audioDurationMs"));
    }
}
