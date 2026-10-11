package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.CapabilityCatalog;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure configuration compatibility fixtures; parsing never contacts a broker or native device. */
public class FactoryConfigNativeCompatibilityTest {
    private static final String APP = "org.example.nativecompat";
    private static final String DIGEST = new String(new char[64]).replace('\0', 'a');
    private static final String[] NATIVE = {"network", "notifications", "speech", "voiceRecognition", "location", "recording", "steps"};
    private static JSONObject configuration(String... capabilities) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("appId", APP).put("name", "Native compatibility")
                .put("entryPoint", "www/index.html").put("capabilities", new JSONArray(Arrays.asList(capabilities)));
    }
    private static JSONObject broker(String host, String digest) throws Exception {
        return new JSONObject().put("packageName", host).put("certificateSha256", digest);
    }
    private static FactoryConfig installed(JSONObject config) throws Exception { return FactoryConfig.parse(config.toString(), APP); }
    private static void invalidConfig(JSONObject config) {
        assertEquals("INVALID_CONFIG", assertThrows(FactoryException.class, () -> installed(config)).code);
    }
    @Test public void publishedFiniteSensorConfigurationsStillLoadWithoutAPinnedHost() throws Exception {
        for (String[] capabilities : new String[][]{{"sensors"}, {"storage", "sensors"}, {"sensors", "presentation", "biometrics"}}) {
            JSONObject json = configuration(capabilities);
            FactoryConfig config = installed(json);
            assertEquals(new LinkedHashSet<>(Arrays.asList(capabilities)), config.capabilities);
            assertNull(config.documentBroker); assertNull(FactoryConfig.parsePreview(json.toString()).documentBroker);
            assertTrue(CapabilityCatalog.requiresHostBroker(config.capabilities));
            assertFalse(CapabilityCatalog.requiresMandatoryHostBroker(config.capabilities));
            assertThrows(UnsupportedOperationException.class, () -> config.capabilities.add("network"));
        }
    }
    @Test public void sensorsMayPinEitherExactHostForNewSubscriptionOperations() throws Exception {
        for (String host : new String[]{"com.jarvys.agent", "com.jarvys.agent.recoverytest"}) {
            JSONObject json = configuration("sensors").put("documentBroker", broker(host, DIGEST));
            FactoryConfig config = installed(json);
            assertEquals(Collections.singleton("sensors"), config.capabilities);
            assertEquals(host, config.documentBroker.packageName); assertEquals(DIGEST, config.documentBroker.certificateSha256);
            assertEquals(host, FactoryConfig.parsePreview(json.toString()).documentBroker.packageName);
        }
    }
    @Test public void everyNativeFamilyRequiresAnInstalledPinWithoutRequiringDocuments() throws Exception {
        for (String capability : NATIVE) {
            JSONObject json = configuration(capability);
            invalidConfig(json); assertNull(FactoryConfig.parsePreview(json.toString()).documentBroker);
            assertTrue(CapabilityCatalog.requiresMandatoryHostBroker(Collections.singleton(capability)));
            for (String host : new String[]{"com.jarvys.agent", "com.jarvys.agent.recoverytest"}) {
                FactoryConfig config = installed(json.put("documentBroker", broker(host, DIGEST)));
                assertEquals(Collections.singleton(capability), config.capabilities);
                assertEquals(host, config.documentBroker.packageName); assertEquals(DIGEST, config.documentBroker.certificateSha256);
                assertFalse(config.capabilities.contains("documents"));
            }
            invalidConfig(configuration("sensors", capability));
        }
    }
    @Test public void allExistingAndNewBrokerFamiliesShareTheExactPinningContract() throws Exception {
        for (String capability : Arrays.asList("documents", "browser", "maps", "phone", "email", "sms", "contacts", "calendar",
                "clipboard", "share", "export", "network", "notifications", "speech", "voiceRecognition", "location", "recording", "steps", "sensors")) {
            JSONObject json = configuration(capability);
            for (String host : new String[]{"org.example.host", "com.jarvys.agent.fake", "com.jarvys.agent.debug", ""})
                invalidConfig(json.put("documentBroker", broker(host, DIGEST)));
            for (String digest : new String[]{DIGEST.toUpperCase(java.util.Locale.ROOT), "", "aa", DIGEST + "a", "sha256:" + DIGEST})
                invalidConfig(json.put("documentBroker", broker("com.jarvys.agent", digest)));
            invalidConfig(json.put("documentBroker", JSONObject.NULL));
            JSONObject extra = broker("com.jarvys.agent", DIGEST).put("uri", "content://other");
            assertEquals("INVALID_ARGUMENT", assertThrows(FactoryException.class,
                    () -> installed(json.put("documentBroker", extra))).code);
        }
    }
    @Test public void standaloneCapabilitiesCannotAcquireUnneededBrokerBindings() throws Exception {
        for (String[] capabilities : new String[][]{{}, {"storage"}, {"audio"}, {"photos"}, {"presentation"}, {"database"}, {"biometrics"}}) {
            JSONObject json = configuration(capabilities);
            assertNull(installed(json).documentBroker);
            assertFalse(CapabilityCatalog.requiresHostBroker(Arrays.asList(capabilities)));
            assertFalse(CapabilityCatalog.requiresMandatoryHostBroker(Arrays.asList(capabilities)));
            invalidConfig(json.put("documentBroker", broker("com.jarvys.agent", DIGEST)));
        }
    }
    @Test public void wholeSelectionsRejectUnknownAndDuplicateCapabilitiesEvenAfterBrokerMatches() throws Exception {
        for (String[] capabilities : new String[][]{{"sensors", "sensors"}, {"network", "network"}, {"network", "unknown"}, {"sensors", "unknown"}}) {
            JSONObject json = configuration(capabilities).put("documentBroker", broker("com.jarvys.agent", DIGEST));
            invalidConfig(json);
            assertThrows(FactoryException.class, () -> FactoryConfig.parsePreview(json.toString()));
            assertThrows(IllegalArgumentException.class, () -> CapabilityCatalog.requiresHostBroker(Arrays.asList(capabilities)));
            assertThrows(IllegalArgumentException.class, () -> CapabilityCatalog.requiresMandatoryHostBroker(Arrays.asList(capabilities)));
        }
        assertThrows(IllegalArgumentException.class, () -> CapabilityCatalog.requiresMandatoryHostBroker(null));
    }
    @Test public void sensorPeriodBudgetsRetainLegacyDefaultAndAcceptOnlyBoundedIntegers() throws Exception {
        assertEquals(100000, installed(configuration("sensors")).resources.sensors.minimumIntervalUs);
        for (int period : new int[]{0, 1, 4, 5000, 100000, 1000000}) {
            JSONObject resources = new JSONObject().put("version", 1)
                    .put("sensors", new JSONObject().put("minimumIntervalUs", period));
            FactoryConfig config = installed(configuration("sensors").put("resourceBudgets", resources));
            assertEquals(period, config.resources.sensors.minimumIntervalUs);
            assertEquals(50, config.resources.sensors.captureSamples); assertEquals(50, config.resources.sensors.bufferedSamples);
        }
        for (Object period : new Object[]{-1, 1000001, 2147483648L, "5000", true, 5000.5, JSONObject.NULL}) {
            JSONObject resources = new JSONObject().put("version", 1)
                    .put("sensors", new JSONObject().put("minimumIntervalUs", period));
            invalidConfig(configuration("sensors").put("resourceBudgets", resources));
        }
    }
}
