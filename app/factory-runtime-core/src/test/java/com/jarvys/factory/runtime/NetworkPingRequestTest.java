package com.jarvys.factory.runtime;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class NetworkPingRequestTest {
    interface Checked { void run() throws Exception; }
    static JSONObject args() throws Exception { return new JSONObject().put("host", "example.com"); }
    static void invalid(Checked operation) throws Exception {
        try { operation.run(); fail("Expected invalid ping input"); }
        catch (FactoryException failure) { assertEquals("INVALID_ARGUMENT", failure.code); }
    }
    @Test public void normalizesDomainsWithoutNetworkLookupAndAllowsLocalTargets() throws Exception {
        assertEquals("example.com", NetworkPingRequest.parse(args().put("host", "EXAMPLE.com")).host);
        assertEquals("xn--bcher-kva.example", NetworkPingRequest.parse(args().put("host", "bücher.example")).host);
        for (String host : new String[]{"localhost", "router.lan", "10.0.0.1", "127.0.0.1", "192.168.1.1", "example.com."})
            assertEquals(host, NetworkPingRequest.parse(args().put("host", host)).host);
    }
    @Test public void ipv6SyntaxIsPureAndSupportsScopedLiterals() throws Exception {
        assertEquals("2001:db8::1", NetworkPingRequest.parse(args().put("host", "[2001:DB8::1]")).host);
        for (String host : new String[]{"::", "::1", "fe80::1%wlan0", "fe80::1%2", "::ffff:192.0.2.1", "1:2:3:4:5:6:7:8"})
            assertEquals(host, NetworkPingRequest.parse(args().put("host", host)).host);
        for (String host : new String[]{":", "1:::2", "1::2::3", "1:2:3", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7::8", "::gg", "::ffff:300.1.1.1", "fe80::1%wlan0;id"})
            invalid(() -> NetworkPingRequest.parse(args().put("host", host)));
    }
    @Test public void rejectsShellFlagsUrlsAndAmbiguousNumericHosts() throws Exception {
        for (String host : new String[]{"-c", "--help", "example.com;id", "example.com && id", "$(id)", "`id`", "https://example.com", "user@example.com", "/system/bin/sh", "example.com\n-c1000", "", " ", "127.1", "2130706433", "001.2.3.4", "256.0.0.1", "host_name", ".example", "a..b", "[example.com]"})
            invalid(() -> NetworkPingRequest.parse(args().put("host", host)));
        invalid(() -> NetworkPingRequest.parse(args().put("executable", "/system/bin/sh")));
        invalid(() -> NetworkPingRequest.parse(args().put("args", "-f")));
        invalid(() -> NetworkPingRequest.parse(args().put("command", "ping")));
    }
    @Test public void supportsFiniteTechnicalBoundsAndConservativeDefaults() throws Exception {
        NetworkPingRequest request = NetworkPingRequest.parse(args());
        assertEquals(4, request.count); assertEquals(56, request.packetBytes); assertEquals(1000, request.intervalMs);
        request = NetworkPingRequest.parse(args().put("count", 1000).put("packetBytes", 65507).put("timeoutMs", 120000)
                .put("packetTimeoutMs", 120000).put("intervalMs", 60000).put("maxOutputBytes", 65536));
        assertEquals(1000, request.count); assertEquals(65507, request.packetBytes);
        invalid(() -> NetworkPingRequest.parse(args().put("count", 0)));
        invalid(() -> NetworkPingRequest.parse(args().put("count", 1001)));
        invalid(() -> NetworkPingRequest.parse(args().put("packetBytes", 65508)));
        invalid(() -> NetworkPingRequest.parse(args().put("timeoutMs", Integer.MAX_VALUE)));
        invalid(() -> NetworkPingRequest.parse(args().put("intervalMs", 199)));
        invalid(() -> NetworkPingRequest.parse(args().put("maxOutputBytes", 0)));
    }
    @Test public void numericTypesAndFamilyAreStrict() throws Exception {
        for (Object value : new Object[]{"4", 4.0, true, JSONObject.NULL})
            invalid(() -> NetworkPingRequest.parse(args().put("count", value)));
        invalid(() -> NetworkPingRequest.parse(args().put("family", "tcp")));
        invalid(() -> NetworkPingRequest.parse(args().put("host", "::1").put("family", "ipv4")));
        invalid(() -> NetworkPingRequest.parse(args().put("host", "127.0.0.1").put("family", "ipv6")));
    }
    @Test public void authorizationDisclosureIsCompleteAndFresh() throws Exception {
        JSONObject input = args(); NetworkPingRequest request = NetworkPingRequest.parse(input); input.put("host", "other.example");
        JSONObject details = request.authorizationDetails(); assertEquals(8, details.length());
        assertEquals("example.com", details.getString("host")); assertEquals("auto", details.getString("family"));
        details.put("host", "changed.example");
        assertEquals("example.com", request.authorizationDetails().getString("host"));
        assertEquals(request.authorizationDetails().toString(), NetworkPingRequest.parse(request.authorizationDetails()).authorizationDetails().toString());
    }
}
