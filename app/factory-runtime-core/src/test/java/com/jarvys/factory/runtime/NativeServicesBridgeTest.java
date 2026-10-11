package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.CapabilityCatalog;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure bridge/dispatcher contracts. Never creates an Activity, network transport, engine, or process. */
public class NativeServicesBridgeTest {
    private static final String[] METHODS = {
            "network.request", "network.probe", "network.ping", "network.cancel",
            "notifications.status", "notifications.channel", "notifications.show", "notifications.cancel", "notifications.requestPermission",
            "speech.status", "speech.voices", "speech.speak", "speech.stop"
    };

    @Test public void pingBridgeAcceptsTypedOptionsAndDispatchesTheDistinctIcmpOperation() throws Exception {
        FactoryConfig config = config("[\"network\"]");
        JSONObject args = new JSONObject().put("host", "2001:db8::1").put("family", "ipv6").put("count", 2)
                .put("timeoutMs", 10000).put("packetTimeoutMs", 2000).put("intervalMs", 500)
                .put("packetBytes", 32).put("maxOutputBytes", 2048);
        BridgeProtocol.Request request = request("network.ping", args, config);
        assertEquals(CapabilityCatalog.Method.NETWORK_PING, request.operation);
        assertEquals("network", request.operation.capability);
        assertEquals("adapter-result", FactoryDispatcher.dispatch(request, config, new BoundedStore(new MemoryBackend()),
                FactoryDispatcher.metadata("installed", config.appId, 35, 35), (operation, value) -> {
                    assertEquals(CapabilityCatalog.Method.NETWORK_PING, operation);
                    assertEquals("2001:db8::1", value.getString("host")); assertEquals("ipv6", value.getString("family"));
                    assertEquals(2, value.getInt("count")); assertEquals(2048, value.getInt("maxOutputBytes"));
                    return "adapter-result";
                }));
        assertEquals(CapabilityCatalog.Method.NETWORK_PING,
                request("network.ping", new JSONObject().put("host", "synthetic.invalid"), config).operation);
    }

    @Test public void pingRequiresNetworkCapabilityAtBothBridgeAndDispatcherBoundaries() throws Exception {
        FactoryConfig withoutNetwork = config("[\"notifications\",\"speech\"]");
        JSONObject args = args("network.ping");
        assertEquals("CAPABILITY_DENIED", assertThrows(FactoryException.class,
                () -> request("network.ping", args, withoutNetwork)).code);
        BridgeProtocol.Request approvedShape = request("network.ping", args, config("[\"network\"]"));
        assertEquals("CAPABILITY_DENIED", assertThrows(FactoryException.class,
                () -> FactoryDispatcher.dispatch(approvedShape, withoutNetwork, new BoundedStore(new MemoryBackend()),
                        FactoryDispatcher.metadata("installed", withoutNetwork.appId, 35, 35),
                        (operation, value) -> { throw new AssertionError("Undeclared ping reached an effect adapter"); })).code);
    }

    @Test public void previewRejectsEveryNativeServiceMethodInsteadOfSimulatingSuccess() throws Exception {
        FactoryConfig config = config("[\"network\",\"notifications\",\"speech\"]");
        for (String method : METHODS) {
            BridgeProtocol.Request request = request(method, args(method), config);
            FactoryException failure = assertThrows(method, FactoryException.class,
                    () -> FactoryDispatcher.dispatch(request, config, new BoundedStore(new MemoryBackend()),
                            FactoryDispatcher.previewMetadata("com.jarvys.host", 35, 35), FactoryDispatcher.simulatedEffects()));
            assertEquals(method, "UNAVAILABLE", failure.code);
        }
    }

    @Test public void runtimeInfoDistinguishesHttpProbeFromIcmpAndDisclosesPlatformRequirement() throws Exception {
        FactoryConfig config = config("[\"network\",\"notifications\",\"speech\"]");
        for (String mode : Arrays.asList("installed", "preview")) {
            JSONObject info = (JSONObject) FactoryDispatcher.dispatch(request("runtime.info", new JSONObject(), config), config,
                    new BoundedStore(new MemoryBackend()), FactoryDispatcher.metadata(mode, "com.jarvys.host", 35, 35),
                    (operation, value) -> { throw new AssertionError("Metadata must not invoke native effects"); });
            assertEquals("http_https", info.getString("nativeNetworkProtocol"));
            assertFalse(info.getBoolean("nativeProbeIsIcmp")); assertEquals("icmp", info.getString("nativePingProtocol"));
            assertTrue(info.getBoolean("platformPingUtilityRequired"));
            assertTrue(info.getBoolean("processInternetPermission")); assertFalse(info.getBoolean("offline"));
            assertEquals("embedded_webview_configuration_not_process_isolation", info.getString("offlineScope"));
            assertEquals("host_allow_ask_deny", info.getString("nativeOperationsPolicy"));
            assertTrue(info.getBoolean("speechEngineMayUseNetwork")); assertFalse(info.getBoolean("notificationAcceptedMeansDelivered"));
            if ("preview".equals(mode)) {
                assertTrue(strings(info.getJSONArray("unavailableCapabilities")).containsAll(Arrays.asList("network", "notifications", "speech")));
                assertTrue(strings(info.getJSONArray("unavailableMethods")).containsAll(Arrays.asList(METHODS)));
            } else {
                assertEquals(0, info.getJSONArray("unavailableCapabilities").length());
                assertEquals(0, info.getJSONArray("unavailableMethods").length());
            }
        }
    }

    private static FactoryConfig config(String capabilities) throws Exception {
        return FactoryConfig.parsePreview(FactoryDispatcherTest.configuration(capabilities));
    }
    private static BridgeProtocol.Request request(String method, JSONObject args, FactoryConfig config) throws Exception {
        return FactoryDispatcherTest.request(method, args, config);
    }
    private static JSONObject args(String method) throws Exception {
        switch (method) {
            case "network.request": case "network.probe": return new JSONObject().put("url", "https://synthetic.invalid/");
            case "network.ping": return new JSONObject().put("host", "synthetic.invalid");
            case "notifications.channel": return new JSONObject().put("id", "test").put("name", "Test");
            case "notifications.show": return new JSONObject().put("id", 1).put("channelId", "test").put("title", "Synthetic").put("body", "No notification is posted");
            case "notifications.cancel": return new JSONObject().put("id", 1);
            case "speech.speak": return new JSONObject().put("text", "Never spoken");
            default: return new JSONObject();
        }
    }
    private static Set<String> strings(JSONArray values) throws Exception {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < values.length(); i++) result.add(values.getString(i));
        return result;
    }
}
