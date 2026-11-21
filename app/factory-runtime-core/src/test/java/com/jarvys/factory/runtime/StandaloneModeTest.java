package com.jarvys.factory.runtime;

import static org.junit.Assert.*;

import android.app.Application;
import android.os.Looper;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Standalone authority: config parsing, local lease and host:port enforcement. Absent authority keeps host mode. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class StandaloneModeTest {
    static final String PC = "10.0.0.1:8877";
    static JSONObject standalone(String capabilities) throws Exception {
        return new JSONObject(FactoryDispatcherTest.configuration(capabilities)).put("authority", "standalone").put("allowedHosts", new JSONArray().put(PC));
    }
    static FactoryConfig parse(JSONObject value) throws Exception { return FactoryConfig.parse(value.toString(), "com.example.generated"); }
    static void invalid(JSONObject value) throws Exception {
        try { parse(value); fail("accepted " + value); } catch (FactoryException expected) { assertEquals("INVALID_CONFIG", expected.code); }
    }
    static FactoryConfig config() throws Exception { return parse(standalone("[\"network\"]")); }

    // ---- config ----
    @Test public void standaloneNetworkConfigNeedsNoHostBroker() throws Exception {
        FactoryConfig config = config();
        assertTrue(config.standalone()); assertNull(config.documentBroker);
        assertEquals(Collections.singletonList(PC), config.allowedHosts);
    }
    @Test public void standaloneRejectsHostCapabilitiesEmptyOrMissingHostsAndBrokers() throws Exception {
        invalid(standalone("[\"network\",\"notifications\"]"));
        invalid(standalone("[\"documents\"]"));
        invalid(standalone("[\"network\"]").put("allowedHosts", new JSONArray()));
        invalid(standalone("[\"network\"]").put("allowedHosts", new JSONArray().put("10.0.0.1")));
        invalid(standalone("[\"network\"]").put("allowedHosts", new JSONArray().put(1)));
        invalid(standalone("[\"network\"]").put("authority", "other"));
        invalid(standalone("[\"network\"]").put("documentBroker", new JSONObject().put("packageName", DocumentBrokerIdentity.PRIMARY)
                .put("certificateSha256", DocumentBrokerIdentityTest.digest())));
        JSONObject noHosts = standalone("[\"network\"]"); noHosts.remove("allowedHosts"); invalid(noHosts);
    }
    @Test public void allowedHostsWithoutAuthorityIsRejectedAndNetworkWithoutAuthorityStillNeedsBroker() throws Exception {
        invalid(new JSONObject(FactoryDispatcherTest.configuration("[\"network\"]")).put("allowedHosts", new JSONArray().put(PC)));
        invalid(new JSONObject(FactoryDispatcherTest.configuration("[\"network\"]"))); // unchanged host mode: pinned Jarvys host required
        FactoryConfig plain = parse(new JSONObject(FactoryDispatcherTest.configuration("[\"storage\"]")));
        assertFalse(plain.standalone()); assertNull(plain.authority); assertTrue(plain.allowedHosts.isEmpty());
    }

    // ---- NativeNetwork host:port enforcement ----
    private static NativeNetwork net(NativeNetworkTest.Transport transport) {
        return new NativeNetwork(transport, host -> true, System::nanoTime, Collections.singletonList(PC));
    }
    private static JSONObject run(NativeNetwork network, String url) throws Exception {
        return network.execute(NetworkRequest.fromRequest(new JSONObject().put("url", url)), NativeNetworkTest.ALLOWED, new NativeNetwork.Cancellation());
    }
    @Test public void declaredHostAndPortIsAllowed() throws Exception {
        NativeNetworkTest.Transport transport = new NativeNetworkTest.Transport(new NativeNetworkTest.Fake());
        JSONObject result = run(net(transport), "http://10.0.0.1:8877/status");
        assertEquals(200, result.getInt("status")); assertEquals(1, transport.urls.size());
    }
    @Test public void otherHostOrPortIsRejectedBeforeAnyConnection() throws Exception {
        for (String url : Arrays.asList("http://10.0.0.2:8877/", "http://10.0.0.1:8766/", "http://10.0.0.1/", "https://10.0.0.1:8764/", "http://example.com:8877/", "http://10.0.0.1.evil.com:8877/")) {
            NativeNetworkTest.Transport transport = new NativeNetworkTest.Transport(new NativeNetworkTest.Fake());
            try { run(net(transport), url); fail("allowed " + url); } catch (FactoryException expected) { assertEquals(url, "HOST_NOT_ALLOWED", expected.code); }
            assertEquals(url, 0, transport.urls.size());
        }
    }
    @Test public void externalRedirectIsRejectedAndInternalRedirectFollowed() throws Exception {
        NativeNetworkTest.Fake first = new NativeNetworkTest.Fake().status(302).header("Location", "http://example.com:8877/steal");
        NativeNetworkTest.Transport transport = new NativeNetworkTest.Transport(first, new NativeNetworkTest.Fake());
        try { run(net(transport), "http://10.0.0.1:8877/a"); fail(); } catch (FactoryException expected) { assertEquals("HOST_NOT_ALLOWED", expected.code); }
        assertEquals(1, transport.urls.size());
        NativeNetworkTest.Fake hop = new NativeNetworkTest.Fake().status(302).header("Location", "/b");
        NativeNetworkTest.Transport internal = new NativeNetworkTest.Transport(hop, new NativeNetworkTest.Fake());
        assertEquals(200, run(net(internal), "http://10.0.0.1:8877/a").getInt("status")); assertEquals(2, internal.urls.size());
    }
    @Test public void withoutAllowedHostsNetworkBehavesAsBefore() throws Exception {
        NativeNetworkTest.Transport transport = new NativeNetworkTest.Transport(new NativeNetworkTest.Fake());
        assertEquals(200, run(NativeNetworkTest.network(transport), "https://anything.example/x").getInt("status"));
    }

    // ---- local lease ----
    private static final class Fixture implements AutoCloseable {
        final NativePermissionSessionsTest.Screen screen = Robolectric.buildActivity(NativePermissionSessionsTest.Screen.class).setup().get();
        final NativePermissionSessionsTest.Worker io = new NativePermissionSessionsTest.Worker();
        final NativePermissionSessions sessions;
        Fixture() throws Exception { sessions = new NativePermissionSessions(screen, config(), io, true); }
        NativePermissionSessions.Lease authorize(NativePermissionSessionsTest.Capture reply) throws Exception {
            return sessions.authorize("network.request", NetworkRequest.fromRequest(new JSONObject().put("url", "http://" + PC + "/status")).authorizationDetails(), reply);
        }
        public void close() throws Exception { sessions.close(); Shadows.shadowOf(Looper.getMainLooper()).idle(); io.close(); screen.finish(); }
    }
    @Test public void localLeaseIsDeliveredOnMainThreadWithoutHostAndCancels() throws Exception {
        try (Fixture f = new Fixture()) {
            NativePermissionSessionsTest.Capture reply = new NativePermissionSessionsTest.Capture();
            NativePermissionSessions.Lease lease = f.authorize(reply);
            assertEquals(0, reply.ready);
            NativePermissionSessionsTest.idle();
            assertSame(lease, reply.lease); assertEquals(1, reply.ready); assertNull(reply.error);
            assertTrue(f.io.tasks.isEmpty()); // no host-broker acquire
            lease.check();
            boolean[] cancelled = {false};
            lease.onCancelled(() -> cancelled[0] = true);
            f.sessions.cancelAll(); NativePermissionSessionsTest.idle();
            assertTrue(cancelled[0]); assertEquals("CANCELLED", reply.error);
            assertThrows(SecurityException.class, lease::check);
        }
    }
    @Test public void localLeaseStopsWhenPageLeavesForeground() throws Exception {
        try (Fixture f = new Fixture()) {
            NativePermissionSessionsTest.Capture reply = new NativePermissionSessionsTest.Capture();
            NativePermissionSessions.Lease lease = f.authorize(reply);
            f.sessions.onPause(); NativePermissionSessionsTest.idle();
            assertEquals("CANCELLED", reply.error); assertEquals(0, reply.ready);
            assertThrows(SecurityException.class, lease::check);
        }
    }
}
