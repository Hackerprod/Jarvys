package com.jarvys.factory.contract;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class StandalonePolicyTest {
    private static void badHosts(String... hosts) {
        try { StandalonePolicy.validateHosts(Arrays.asList(hosts)); fail("accepted " + Arrays.toString(hosts)); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void acceptsAndNormalizesHostPortList() {
        assertEquals(Collections.singletonList("10.0.0.1:8877"), StandalonePolicy.validateHosts(Collections.singletonList("10.0.0.1:8877")));
        assertEquals(Arrays.asList("pc.lan:80", "10.0.0.1:65535"), StandalonePolicy.validateHosts(Arrays.asList("PC.lan:80", "10.0.0.1:65535")));
    }
    @Test public void rejectsEmptyTooManyAndMalformedHosts() {
        badHosts();
        badHosts("10.0.0.1"); badHosts("10.0.0.1:0"); badHosts("10.0.0.1:65536"); badHosts("http://10.0.0.1:8877"); badHosts("10.0.0.1:8877/x");
        badHosts("a:1", "a:1"); badHosts("user@10.0.0.1:80"); badHosts("");
        String[] many = new String[17]; for (int i = 0; i < many.length; i++) many[i] = "h" + i + ":80";
        badHosts(many);
        try { StandalonePolicy.validateHosts(null); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void sixteenHostsAreAccepted() {
        String[] many = new String[16]; for (int i = 0; i < many.length; i++) many[i] = "h" + i + ":80";
        assertEquals(16, StandalonePolicy.validateHosts(Arrays.asList(many)).size());
    }
    @Test public void onlyNetworkAndHostFreeCapabilitiesAreAllowed() {
        StandalonePolicy.validateCapabilities(Collections.singletonList("network"));
        StandalonePolicy.validateCapabilities(Arrays.asList("network", "storage"));
        for (String name : CapabilityCatalog.NAMES) {
            List<String> one = Collections.singletonList(name);
            boolean host = CapabilityCatalog.requiresHostBroker(one) && !name.equals("network");
            try { StandalonePolicy.validateCapabilities(one); assertFalse(name, host); }
            catch (IllegalArgumentException rejected) { assertTrue(name, host); }
        }
    }
    @Test public void networkNeedsNoMandatoryBrokerOnlyWhenStandalone() {
        List<String> network = Collections.singletonList("network");
        assertTrue(CapabilityCatalog.requiresMandatoryHostBroker(network));
        assertTrue(CapabilityCatalog.requiresMandatoryHostBroker(network, false));
        assertFalse(CapabilityCatalog.requiresMandatoryHostBroker(network, true));
        assertTrue(CapabilityCatalog.requiresMandatoryHostBroker(Arrays.asList("network", "notifications"), true));
    }
}
