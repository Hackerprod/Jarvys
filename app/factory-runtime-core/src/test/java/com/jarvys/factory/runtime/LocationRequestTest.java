package com.jarvys.factory.runtime;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure grammar tests; no Android permission prompts or location APIs. */
public class LocationRequestTest {
    @Test public void currentDefaultsAreForegroundBoundedFreshAndMockTransparent() throws Exception {
        LocationRequest.Current value = LocationRequest.parseCurrent("{}");
        assertEquals("auto", value.provider); assertEquals(15000, value.timeoutMs);
        assertEquals(0, value.maxAgeMs); assertEquals(0, value.maxAccuracyMeters, 0); assertTrue(value.allowMock);
        JSONObject details = value.authorizationDetails();
        assertEquals(5, details.length()); assertTrue(details.getBoolean("allowMock"));
    }

    @Test public void everyProviderAndExplicitBoundaryArePreserved() throws Exception {
        for (String provider : new String[]{"auto", "gps", "network"}) {
            LocationRequest.Current low = LocationRequest.parseCurrent(new JSONObject().put("provider", provider)
                    .put("timeoutMs", 1000).put("maxAgeMs", 0).put("maxAccuracyMeters", 0.01).put("allowMock", false));
            assertEquals(provider, low.provider); assertEquals(1000, low.timeoutMs);
            assertEquals(0.01, low.maxAccuracyMeters, 0); assertFalse(low.allowMock);
            LocationRequest.Current high = LocationRequest.parseCurrent(new JSONObject().put("provider", provider)
                    .put("timeoutMs", 120000).put("maxAgeMs", 300000).put("maxAccuracyMeters", 100000));
            assertEquals(120000, high.timeoutMs); assertEquals(300000, high.maxAgeMs);
            assertEquals(100000, high.maxAccuracyMeters, 0);
        }
    }

    @Test public void numbersAreTypedFiniteIntegralWhereRequiredAndBounded() throws Exception {
        for (String key : new String[]{"timeoutMs", "maxAgeMs", "maxAccuracyMeters"})
            for (Object bad : new Object[]{"1000", true, JSONObject.NULL, new JSONObject(), -1})
                assertThrows(FactoryException.class, () -> LocationRequest.parseCurrent(new JSONObject().put(key, bad)));
        for (Object bad : new Object[]{0, 999, 120001, 1000.1, Long.MAX_VALUE})
            assertThrows(FactoryException.class, () -> LocationRequest.parseCurrent(new JSONObject().put("timeoutMs", bad)));
        for (Object bad : new Object[]{300001, 0.1, Long.MAX_VALUE})
            assertThrows(FactoryException.class, () -> LocationRequest.parseCurrent(new JSONObject().put("maxAgeMs", bad)));
        assertThrows(FactoryException.class, () -> LocationRequest.parseCurrent("{\"maxAccuracyMeters\":100000.1}"));
        assertThrows(FactoryException.class, () -> LocationRequest.parseCurrent("{\"maxAccuracyMeters\":1e1000}"));
        assertThrows(FactoryException.class, () -> LocationRequest.parseCurrent("{\"timeoutMs\":1e1000}"));
        FactoryException field = assertThrows(FactoryException.class,
                () -> LocationRequest.parseCurrent(new JSONObject().put("timeoutMs", "soon")));
        assertEquals("location.current: timeoutMs must be a number.", field.getMessage());
    }

    @Test public void noStringBooleanCoercionOrUnknownProvider() throws Exception {
        for (Object bad : new Object[]{"true", "false", 0, 1, JSONObject.NULL, new JSONObject()})
            assertThrows(FactoryException.class, () -> LocationRequest.parseCurrent(new JSONObject().put("allowMock", bad)));
        for (Object bad : new Object[]{"GPS", "fused", "passive", "", "gps\u0000", 1, true, JSONObject.NULL})
            assertThrows(FactoryException.class, () -> LocationRequest.parseCurrent(new JSONObject().put("provider", bad)));
    }

    @Test public void strictJsonRejectsDuplicatesLeniencyAndTrailingInput() {
        for (String bad : new String[]{"[]", "null", "{provider:'gps'}", "{\"provider\":\"gps\",}",
                "{\"timeoutMs\":1000,\"timeoutMs\":2000}", "{} {}", "{\"allowMock\":TRUE}"})
            assertThrows(FactoryException.class, () -> LocationRequest.parseCurrent(bad));
    }

    @Test public void unknownFieldsAndOversizeArgumentsFailClosed() throws Exception {
        for (String unknown : new String[]{"background", "watch", "intervalMs", "permission", "coordinates"})
            assertThrows(FactoryException.class, () -> LocationRequest.parseCurrent(new JSONObject().put(unknown, true)));
        assertThrows(FactoryException.class, () -> LocationRequest.parseCurrent((JSONObject) null));
        assertThrows(FactoryException.class, () -> LocationRequest.parseCurrent("{" + new String(new char[2048]).replace('\0', ' ') + "}"));
    }

    @Test public void permissionPrecisionIsExplicitAndDefaultsApproximate() throws Exception {
        assertEquals("approximate", LocationRequest.parsePermission("{}").precision);
        for (String precision : new String[]{"approximate", "precise"}) {
            LocationRequest.Permission value = LocationRequest.parsePermission(new JSONObject().put("precision", precision));
            assertEquals(precision, value.precision);
            assertEquals(precision, value.authorizationDetails().getString("precision"));
        }
        for (Object bad : new Object[]{"fine", "coarse", "Precise", "", true, 1, JSONObject.NULL})
            assertThrows(FactoryException.class, () -> LocationRequest.parsePermission(new JSONObject().put("precision", bad)));
        FactoryException field = assertThrows(FactoryException.class,
                () -> LocationRequest.parsePermission(new JSONObject().put("precision", "fine")));
        assertEquals("location.requestPermission: precision must be approximate or precise.", field.getMessage());
        assertThrows(FactoryException.class, () -> LocationRequest.parsePermission("{\"background\":true}"));
        assertThrows(FactoryException.class, () -> LocationRequest.parsePermission("{\"precision\":\"precise\",\"precision\":\"approximate\"}"));
    }

    @Test public void statusAndCancelRequireEmptyObjects() throws Exception {
        LocationRequest.parseStatus("{}"); LocationRequest.parseCancel("{}");
        LocationRequest.parseStatus(new JSONObject()); LocationRequest.parseCancel(new JSONObject());
        for (String bad : new String[]{"[]", "null", "{\"permission\":true}", "{}true"}) {
            assertThrows(FactoryException.class, () -> LocationRequest.parseStatus(bad));
            assertThrows(FactoryException.class, () -> LocationRequest.parseCancel(bad));
        }
    }

    @Test public void authorizationDetailsCannotMutateValidatedArguments() throws Exception {
        LocationRequest.Current value = LocationRequest.parseCurrent("{\"provider\":\"network\",\"allowMock\":false}");
        value.authorizationDetails().put("provider", "gps").put("allowMock", true);
        assertEquals("network", value.provider); assertFalse(value.allowMock);
        assertEquals("network", value.authorizationDetails().getString("provider"));
        LocationRequest.Permission permission = LocationRequest.parsePermission("{}");
        permission.authorizationDetails().put("precision", "precise");
        assertEquals("approximate", permission.precision);
    }
}
