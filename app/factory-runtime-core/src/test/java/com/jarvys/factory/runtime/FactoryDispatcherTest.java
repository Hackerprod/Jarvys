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
    @Test public void browserIsStandaloneUnavailableInPreviewAndTypedInMetadata() throws Exception {
        FactoryConfig browser = FactoryConfig.parsePreview(configuration("[\"browser\"]"));
        JSONObject args = new JSONObject().put("url", "https://example.com/");
        BridgeProtocol.Request call = request("browser.open", args, browser);
        BoundedStore store = new BoundedStore(new MemoryBackend());
        try { FactoryDispatcher.dispatch(call, browser, store, FactoryDispatcher.previewMetadata("host", 35, 35), FactoryDispatcher.simulatedEffects()); fail(); }
        catch (FactoryException denied) { assertEquals("UNAVAILABLE", denied.code); }
        assertEquals("adapter", FactoryDispatcher.dispatch(call, browser, store, FactoryDispatcher.metadata("installed", "app", 35, 35),
                (method, value) -> { assertEquals(CapabilityCatalog.Method.BROWSER_OPEN, method); assertEquals(args.toString(), value.toString()); return "adapter"; }));
        JSONObject info = (JSONObject) FactoryDispatcher.dispatch(request("runtime.info", new JSONObject(), browser), browser, store,
                FactoryDispatcher.previewMetadata("host", 35, 35), FactoryDispatcher.simulatedEffects());
        assertEquals(1, info.getInt("browserProtocolVersion")); assertTrue(info.getBoolean("browserBrokerRequired"));
        assertEquals("[\"browser\"]", info.getJSONArray("browserRequires").toString());
        assertTrue(info.getJSONArray("unavailableMethods").toString().contains("browser.open"));
        assertTrue(info.getJSONArray("unavailableCapabilities").toString().contains("browser"));
        assertEquals(2048, info.getJSONObject("limits").getInt("browserUrlLength"));
        assertEquals(300000, info.getJSONObject("limits").getInt("browserLifetimeMs"));
        call.args.put("url", "https://example.com/#changed");
        try { FactoryDispatcher.dispatch(call, browser, store, FactoryDispatcher.metadata("installed", "app", 35, 35),
                (method, value) -> { throw new AssertionError("Effect invoked"); }); fail(); }
        catch (FactoryException denied) { assertEquals("INVALID_ARGUMENT", denied.code); }
    }

    @Test public void offlineMetadataExplicitlyLimitsClaimToEmbeddedWebviewInBothModes() throws Exception {
        for (String mode : new String[]{"installed", "preview"}) {
            for (String caps : new String[]{"[]", "[\"browser\"]"}) {
                FactoryConfig config = FactoryConfig.parsePreview(configuration(caps));
                JSONObject info = (JSONObject) FactoryDispatcher.dispatch(request("runtime.info", new JSONObject(), config), config,
                        new BoundedStore(new MemoryBackend()), FactoryDispatcher.metadata(mode, "host", 35, 35), FactoryDispatcher.simulatedEffects());
                assertTrue(info.getBoolean("offline"));
                assertEquals("embedded_webview", info.getString("offlineScope"));
                assertTrue(info.getBoolean("browserMayUseExternalNetwork"));
                assertEquals(mode, info.getString("mode"));
            }
        }
    }

    /** Exhaustive 2x2x2 original manifest dependency axes, plus regression samples of all
     * original singletons/pairs and the full original set. The latter samples are not exhaustive. */
    private static java.util.List<java.util.List<String>> legacyManifestSelections() {
        java.util.List<String> original=java.util.Arrays.asList("storage","export","share","clipboard","haptics","device","documents","photos","audio","browser","maps","phone","email","sms","contacts","calendar","database","presentation","sensors","biometrics");
        java.util.Set<java.util.List<String>> cases=new java.util.LinkedHashSet<>();
        for(int axes=0;axes<8;axes++) {
            java.util.List<String> selected=new java.util.ArrayList<>();
            if((axes&1)!=0)selected.add("biometrics"); if((axes&2)!=0)selected.add("documents"); if((axes&4)!=0)selected.add("sensors");
            java.util.Collections.sort(selected);cases.add(selected);
        }
        for(int i=0;i<original.size();i++) {
            cases.add(java.util.Collections.singletonList(original.get(i)));
            for(int j=i+1;j<original.size();j++) {java.util.List<String> pair=new java.util.ArrayList<>(java.util.Arrays.asList(original.get(i),original.get(j)));java.util.Collections.sort(pair);cases.add(pair);}
        }
        java.util.List<String> full=new java.util.ArrayList<>(original);java.util.Collections.sort(full);cases.add(full);
        return new java.util.ArrayList<>(cases);
    }

    @Test public void originalManifestAxesAndCombinatorialSamplesHaveExactPermissionsAndVisibility() {
        assertEquals(33, CapabilityCatalog.NAMES.size()); assertEquals(134, CapabilityCatalog.METHODS.size());
        for (java.util.List<String> selected : legacyManifestSelections()) {
            com.jarvys.factory.contract.ManifestPlan plan = new com.jarvys.factory.contract.ManifestPlan(
                    "com.example.browser", "Browser fixture", 1, "1.0", 0x7f010001, 0x7f020001, selected);
            boolean host = false;
            for(String name:java.util.Arrays.asList("browser","documents","maps","phone","email","sms","contacts","calendar","clipboard","share","export","sensors"))host |= selected.contains(name);
            java.util.List<String> permissions=new java.util.ArrayList<>();
            if(selected.contains("sensors"))permissions.add("android.permission.HIGH_SAMPLING_RATE_SENSORS");
            if(selected.contains("biometrics"))permissions.add("android.permission.USE_BIOMETRIC");
            assertEquals(permissions,plan.permissions);
            assertTrue(plan.features.isEmpty()); assertTrue(plan.hosts.isEmpty());
            assertEquals(java.util.Collections.singletonList(com.jarvys.factory.contract.ManifestPlan.ACTIVITY), plan.exportedComponents);
            assertEquals(host ? 2 : 0, plan.queries.size());
            assertEquals((host ? 1 : 0)+permissions.size(), CapabilityCatalog.manifestContributions(selected).size());
            assertEquals((host ? 10 : 7)+permissions.size(), plan.nodes.size());
            if (host) assertEquals(CapabilityCatalog.DOCUMENT_BROKER_PACKAGES, new java.util.LinkedHashSet<>(plan.queries));
        }
        // Every method is represented exactly once in its declared capability; no Cartesian repetition.
        java.util.Set<String> methods=new java.util.HashSet<>();methods.add("runtime.info");
        for(CapabilityCatalog.Capability capability:CapabilityCatalog.CAPABILITIES.values()) for(CapabilityCatalog.Method method:capability.methods) {
            assertEquals(capability.name,method.capability);assertSame(method,CapabilityCatalog.METHODS.get(method.wireName));assertTrue(methods.add(method.wireName));
        }
        assertEquals(CapabilityCatalog.METHODS.keySet(),methods);
    }

    @Test public void mapsAndPhoneHaveIndependentInstalledDispatchAndHonestUnavailablePreviewMetadata() throws Exception {
        for(String method:Arrays.asList("maps.open","phone.dial")) {
            String capability=method.startsWith("maps")?"maps":"phone";
            FactoryConfig config=FactoryConfig.parsePreview(configuration("[\""+capability+"\"]"));
            JSONObject args=method.startsWith("maps")?new JSONObject().put("query","Synthetic map fixture"):new JSONObject().put("number","+15550100");
            BridgeProtocol.Request call=request(method,args,config);
            BoundedStore store=new BoundedStore(new MemoryBackend());
            FactoryException denied=assertThrows(FactoryException.class,()->FactoryDispatcher.dispatch(call,config,store,FactoryDispatcher.previewMetadata("host",35,35),FactoryDispatcher.simulatedEffects()));
            assertEquals("UNAVAILABLE",denied.code);
            assertEquals("adapter",FactoryDispatcher.dispatch(call,config,store,FactoryDispatcher.metadata("installed","app",35,35),(operation,value)->{
                assertEquals(method,operation.wireName);assertEquals(args.toString(),value.toString());return "adapter";
            }));
            JSONObject info=(JSONObject)FactoryDispatcher.dispatch(request("runtime.info",new JSONObject(),config),config,store,
                    FactoryDispatcher.previewMetadata("host",35,35),FactoryDispatcher.simulatedEffects());
            assertEquals("embedded_webview",info.getString("offlineScope"));assertTrue(info.getBoolean("externalRecipientsMayUseNetwork"));
            assertFalse(info.getBoolean("externalActionConfirmed"));assertTrue(info.getBoolean("externalLaunchBrokerRequired"));
            assertEquals(1,info.getInt("externalLaunchProtocolVersion"));
            assertEquals("[\"maps\"]",info.getJSONArray("mapsRequires").toString());
            assertEquals("[\"phone\"]",info.getJSONArray("phoneRequires").toString());
            assertTrue(info.getJSONArray("unavailableMethods").toString().contains(method));
            assertTrue(info.getJSONArray("unavailableCapabilities").toString().contains(capability));
            assertEquals(300000,info.getJSONObject("limits").getInt("externalLaunchLifetimeMs"));
            assertEquals(256,info.getJSONObject("limits").getInt("mapsQueryCodePoints"));
            assertEquals(1024,info.getJSONObject("limits").getInt("mapsQueryBytes"));
            assertEquals(15,info.getJSONObject("limits").getInt("phoneDigits"));
            call.args.put("flags",0);
            FactoryException mutated=assertThrows(FactoryException.class,()->FactoryDispatcher.dispatch(call,config,store,FactoryDispatcher.metadata("installed","app",35,35),(op,value)->{throw new AssertionError("effect called");}));
            assertEquals("INVALID_ARGUMENT",mutated.code);
        }
    }


    @Test public void presentationPreviewIsUnavailableAndInstalledDispatchRevalidatesBeforeLocalAdapter() throws Exception {
        FactoryConfig enabled = FactoryConfig.parse(configuration("[\"presentation\"]"), "com.example.generated");
        FactoryConfig denied = FactoryConfig.parsePreview(configuration("[]"));
        assertNull(enabled.documentBroker);
        BoundedStore store = new BoundedStore(new MemoryBackend());
        for (String method : Arrays.asList("presentation.get", "presentation.set", "presentation.reset")) {
            JSONObject args = method.equals("presentation.set")
                    ? new JSONObject().put("theme", "dark").put("orientation", "portrait") : new JSONObject();
            BridgeProtocol.Request call = request(method, args, enabled);
            FactoryException unavailable = assertThrows(FactoryException.class, () -> FactoryDispatcher.dispatch(call,
                    enabled, store, FactoryDispatcher.previewMetadata("host", 35, 35), FactoryDispatcher.simulatedEffects()));
            assertEquals("UNAVAILABLE", unavailable.code);
            int[] invocations = {0};
            assertEquals("local adapter", FactoryDispatcher.dispatch(call, enabled, store,
                    FactoryDispatcher.metadata("installed", enabled.appId, 35, 35), (operation, value) -> {
                        invocations[0]++;
                        assertEquals(method, operation.wireName); assertEquals(args.toString(), value.toString());
                        return "local adapter";
                    }));
            assertEquals(1, invocations[0]);
            FactoryDispatcher.Effects forbidden = (operation, value) -> { throw new AssertionError("Adapter invoked"); };
            FactoryException undeclared = assertThrows(FactoryException.class, () -> FactoryDispatcher.dispatch(call,
                    denied, store, FactoryDispatcher.metadata("installed", enabled.appId, 35, 35), forbidden));
            assertEquals("CAPABILITY_DENIED", undeclared.code);
            call.args.put("appId", "com.example.other");
            FactoryException mutated = assertThrows(FactoryException.class, () -> FactoryDispatcher.dispatch(call,
                    enabled, store, FactoryDispatcher.metadata("installed", enabled.appId, 35, 35), forbidden));
            assertEquals("INVALID_ARGUMENT", mutated.code);
        }
        for (String mode : Arrays.asList("preview", "installed")) {
            JSONObject info = (JSONObject) FactoryDispatcher.dispatch(request("runtime.info", new JSONObject(), enabled),
                    enabled, store, FactoryDispatcher.metadata(mode, enabled.appId, 35, 35), FactoryDispatcher.simulatedEffects());
            assertEquals(1, info.getInt("presentationProtocolVersion"));
            assertFalse(info.getBoolean("presentationBrokerRequired"));
            assertEquals("[\"presentation\"]", info.getJSONArray("presentationRequires").toString());
            assertEquals("[\"system\",\"light\",\"dark\"]", info.getJSONArray("presentationThemes").toString());
            assertEquals("[\"system\",\"portrait\",\"landscape\"]", info.getJSONArray("presentationOrientations").toString());
            assertFalse(info.getBoolean("presentationOrientationGuaranteed"));
            assertTrue(info.getBoolean("presentationRecreatesActivity"));
            assertEquals(mode.equals("preview"), info.getJSONArray("unavailableCapabilities").toString().contains("\"presentation\""));
            for (String method : Arrays.asList("presentation.get", "presentation.set", "presentation.reset"))
                assertEquals(mode.equals("preview"), info.getJSONArray("unavailableMethods").toString().contains("\"" + method + "\""));
        }
    }


    @Test public void biometricPreviewNeverAuthenticatesAndDispatcherRevalidatesBeforeLocalAdapter() throws Exception {
        FactoryConfig enabled=FactoryConfig.parse(configuration("[\"biometrics\"]"),"com.example.generated");
        FactoryConfig denied=FactoryConfig.parsePreview(configuration("[]"));
        assertNull(enabled.documentBroker);
        BoundedStore store=new BoundedStore(new MemoryBackend());
        for(String method:Arrays.asList("biometrics.availability","biometrics.authenticate","biometrics.cancel")) {
            JSONObject args=method.endsWith("authenticate")?new JSONObject().put("reason","Confirm this local action"):new JSONObject();
            BridgeProtocol.Request call=request(method,args,enabled);
            assertEquals("UNAVAILABLE",assertThrows(FactoryException.class,()->FactoryDispatcher.dispatch(call,enabled,store,
                    FactoryDispatcher.previewMetadata("host",35,35),FactoryDispatcher.simulatedEffects())).code);
            int[] invocations={0};
            assertEquals("local adapter",FactoryDispatcher.dispatch(call,enabled,store,
                    FactoryDispatcher.metadata("installed",enabled.appId,35,35),(operation,value)->{
                        invocations[0]++;assertEquals(method,operation.wireName);assertEquals(args.toString(),value.toString());return "local adapter";
                    }));
            assertEquals(1,invocations[0]);
            FactoryDispatcher.Effects forbidden=(operation,value)->{throw new AssertionError("Adapter invoked");};
            assertEquals("CAPABILITY_DENIED",assertThrows(FactoryException.class,()->FactoryDispatcher.dispatch(call,denied,store,
                    FactoryDispatcher.metadata("installed",enabled.appId,35,35),forbidden)).code);
            call.args.put("credentialFallback",true);
            assertEquals("INVALID_ARGUMENT",assertThrows(FactoryException.class,()->FactoryDispatcher.dispatch(call,enabled,store,
                    FactoryDispatcher.metadata("installed",enabled.appId,35,35),forbidden)).code);
        }
        for(String mode:Arrays.asList("preview","installed")) {
            JSONObject info=(JSONObject)FactoryDispatcher.dispatch(request("runtime.info",new JSONObject(),enabled),enabled,store,
                    FactoryDispatcher.metadata(mode,enabled.appId,35,35),FactoryDispatcher.simulatedEffects());
            assertEquals(1,info.getInt("biometricsProtocolVersion"));assertFalse(info.getBoolean("biometricsBrokerRequired"));
            assertEquals("[\"biometrics\"]",info.getJSONArray("biometricsRequires").toString());
            assertEquals(30,info.getInt("biometricsMinimumApi"));assertEquals("strong",info.getString("biometricsClass"));
            assertFalse(info.getBoolean("biometricsDeviceCredentialAllowed"));assertTrue(info.getBoolean("biometricsForegroundOnly"));
            assertTrue(info.getBoolean("biometricsLocalOnly"));assertFalse(info.getBoolean("biometricsAuthorizesOtherActions"));
            assertEquals(60000,info.getJSONObject("limits").getInt("biometricsAuthenticationMs"));
            assertEquals(160,info.getJSONObject("limits").getInt("biometricsReasonCharacters"));
            assertEquals(512,info.getJSONObject("limits").getInt("biometricsReasonBytes"));
            assertEquals(1024,info.getJSONObject("limits").getInt("biometricsCancelEnvelopeBytes"));
            assertEquals(mode.equals("preview"),info.getJSONArray("unavailableCapabilities").toString().contains("\"biometrics\""));
            for(String method:Arrays.asList("biometrics.availability","biometrics.authenticate","biometrics.cancel"))
                assertEquals(mode.equals("preview"),info.getJSONArray("unavailableMethods").toString().contains("\""+method+"\""));
        }
    }
    @Test public void backupsAreDeniedByDefaultAndPreviewNeverAllocatesOrUsesDatabaseAdapter() throws Exception {
        FactoryConfig empty = FactoryConfig.parsePreview(configuration("[\"database\",\"documents\"]"));
        JSONObject declared = new JSONObject(configuration("[\"database\",\"documents\"]"))
                .put("dataBackup", new JSONObject().put("tables", new JSONArray("[\"notes\",\"tasks\"]")));
        FactoryConfig allowed = FactoryConfig.parsePreview(declared.toString());
        BoundedStore store = new BoundedStore(new MemoryBackend());
        for (String method : new String[]{"database.backupExport", "database.backupImport"}) {
            JSONObject args = new JSONObject().put("version", 1).put("tables", new JSONArray("[\"notes\"]"));
            if (method.equals("database.backupExport")) args.put("filename", "backup.json");
            else args.put("handle", new String(new char[64]).replace('\0', 'a')).put("mode", "insertOnly");
            FactoryDispatcher.Database forbidden = (wire, value) -> { throw new AssertionError("Database adapter invoked"); };
            for (FactoryConfig config : new FactoryConfig[]{empty, allowed}) {
                try { FactoryDispatcher.dispatch(request(method, args, config), config, store,
                        FactoryDispatcher.previewMetadata("host", 35, 35), FactoryDispatcher.simulatedEffects(), forbidden); fail(); }
                catch (FactoryException denied) { assertEquals("UNAVAILABLE", denied.code); }
            }
            try { FactoryDispatcher.dispatch(request(method, args, empty), empty, store,
                    FactoryDispatcher.metadata("installed", "app", 35, 35), FactoryDispatcher.simulatedEffects(), forbidden); fail(); }
            catch (FactoryException denied) { assertEquals("BACKUP_DENIED", denied.code); }
            assertEquals("adapter", FactoryDispatcher.dispatch(request(method, args, allowed), allowed, store,
                    FactoryDispatcher.metadata("installed", "app", 35, 35), FactoryDispatcher.simulatedEffects(),
                    (wire, value) -> { assertEquals(method, wire); return "adapter"; }));
            BridgeProtocol.Request mutation = request(method, args, allowed);
            mutation.args.put("tables", new JSONArray("[\"secret\"]"));
            try { FactoryDispatcher.dispatch(mutation, allowed, store,
                    FactoryDispatcher.metadata("installed", "app", 35, 35), FactoryDispatcher.simulatedEffects(), forbidden); fail(); }
            catch (FactoryException denied) { assertEquals("BACKUP_DENIED", denied.code); }
        }
        JSONObject info = (JSONObject) FactoryDispatcher.dispatch(request("runtime.info", new JSONObject(), allowed), allowed, store,
                FactoryDispatcher.previewMetadata("host", 35, 35), FactoryDispatcher.simulatedEffects());
        assertEquals(1, info.getInt("databaseBackupProtocolVersion"));
        assertTrue(info.getBoolean("databaseBackupBrokerRequired"));
        assertEquals("[\"database\",\"documents\"]", info.getJSONArray("databaseBackupRequires").toString());
        assertEquals("[\"notes\",\"tasks\"]", info.getJSONArray("databaseBackupTables").toString());
        assertEquals(1048576, info.getJSONObject("limits").getInt("databaseBackupBytes"));
        assertEquals(8, info.getJSONObject("limits").getInt("databaseBackupTables"));
        assertEquals(300000, info.getJSONObject("limits").getInt("databaseBackupLifetimeMs"));
        assertTrue(info.getJSONArray("unavailableMethods").toString().contains("database.backupExport"));
        assertTrue(info.getJSONArray("unavailableMethods").toString().contains("database.backupImport"));
        assertFalse(info.getJSONArray("unavailableMethods").toString().contains("database.select"));
        assertFalse(info.getJSONArray("unavailableCapabilities").toString().contains("database"));
    }

    @Test public void storageBackupsCheckPolicyAfterPreviewDenialAndNeverInvokeOrdinaryStoreOrEffects() throws Exception {
        FactoryConfig empty = FactoryConfig.parsePreview(configuration("[\"storage\",\"documents\"]"));
        JSONObject declared = new JSONObject(configuration("[\"storage\",\"documents\"]"))
                .put("dataBackup", new JSONObject().put("keys", new JSONArray("[\"settings\",\"theme\"]")));
        FactoryConfig allowed = FactoryConfig.parsePreview(declared.toString());
        BoundedStore store = new BoundedStore(new BoundedStore.Backend() {
            public java.util.Map<String, String> read() { throw new AssertionError("Ordinary storage read"); }
            public boolean replace(java.util.Map<String, String> values) { throw new AssertionError("Ordinary storage write"); }
        });
        FactoryDispatcher.Database forbidden = (wire, value) -> { throw new AssertionError("Backup/database adapter invoked"); };
        FactoryDispatcher.Effects effects = (operation, value) -> { throw new AssertionError("Effect/handle allocated"); };
        for (String method : new String[]{"storage.backupExport", "storage.backupImport"}) {
            JSONObject args = new JSONObject().put("version", 1).put("keys", new JSONArray("[\"settings\"]"));
            if (method.endsWith("Export")) args.put("filename", "backup.json");
            else args.put("handle", new String(new char[64]).replace('\0', 'a')).put("mode", "insertOnly");
            for (FactoryConfig config : new FactoryConfig[]{empty, allowed}) {
                try { FactoryDispatcher.dispatch(request(method, args, config), config, store,
                        FactoryDispatcher.previewMetadata("host", 35, 35), effects, forbidden); fail(); }
                catch (FactoryException denied) { assertEquals("UNAVAILABLE", denied.code); }
            }
            try { FactoryDispatcher.dispatch(request(method, args, empty), empty, store,
                    FactoryDispatcher.metadata("installed", "app", 35, 35), effects, forbidden); fail(); }
            catch (FactoryException denied) { assertEquals("BACKUP_DENIED", denied.code); }
            try { FactoryDispatcher.dispatch(request(method, args, allowed), allowed, store,
                    FactoryDispatcher.metadata("installed", "app", 35, 35), effects); fail(); }
            catch (FactoryException denied) { assertEquals("UNAVAILABLE", denied.code); }
            assertEquals("adapter", FactoryDispatcher.dispatch(request(method, args, allowed), allowed, store,
                    FactoryDispatcher.metadata("installed", "app", 35, 35), effects,
                    (wire, value) -> { assertEquals(method, wire); assertEquals(args.toString(), value.toString()); return "adapter"; }));
            BridgeProtocol.Request mutation = request(method, args, allowed);
            mutation.args.put("keys", new JSONArray("[\"secret\"]"));
            try { FactoryDispatcher.dispatch(mutation, allowed, store,
                    FactoryDispatcher.metadata("installed", "app", 35, 35), effects, forbidden); fail(); }
            catch (FactoryException denied) { assertEquals("BACKUP_DENIED", denied.code); }
            mutation.args.put("keys", new JSONArray("[\"settings\"]")).put("version", 1.0d);
            try { FactoryDispatcher.dispatch(mutation, allowed, store,
                    FactoryDispatcher.metadata("installed", "app", 35, 35), effects, forbidden); fail(); }
            catch (FactoryException denied) { assertEquals("INVALID_ARGUMENT", denied.code); }
        }
        for (String mode : new String[]{"installed", "preview"}) {
            JSONObject info = (JSONObject) FactoryDispatcher.dispatch(request("runtime.info", new JSONObject(), allowed), allowed, store,
                    FactoryDispatcher.metadata(mode, "host", 35, 35), effects);
            assertEquals(1, info.getInt("storageBackupProtocolVersion"));
            assertTrue(info.getBoolean("storageBackupBrokerRequired"));
            assertEquals("[\"storage\",\"documents\"]", info.getJSONArray("storageBackupRequires").toString());
            assertEquals("[\"settings\",\"theme\"]", info.getJSONArray("storageBackupKeys").toString());
            assertEquals("jarvys-preferences-data", info.getString("storageBackupFormat"));
            assertEquals("string_key_value_store", info.getString("storageBackupScope"));
            assertTrue(info.getBoolean("storageBackupPlaintext"));
            assertFalse(info.getBoolean("storageBackupProviderDurabilityConfirmed"));
            assertEquals(1048576, info.getJSONObject("limits").getInt("storageBackupBytes"));
            assertEquals(32, info.getJSONObject("limits").getInt("storageBackupKeys"));
            assertEquals(300000, info.getJSONObject("limits").getInt("storageBackupLifetimeMs"));
            for (String method : new String[]{"storage.backupExport", "storage.backupImport"})
                assertEquals(mode.equals("preview"), info.getJSONArray("unavailableMethods").toString().contains(method));
            assertFalse(info.getJSONArray("unavailableMethods").toString().contains("storage.get"));
            assertFalse(info.getJSONArray("unavailableCapabilities").toString().contains("storage"));
        }
    }

}
