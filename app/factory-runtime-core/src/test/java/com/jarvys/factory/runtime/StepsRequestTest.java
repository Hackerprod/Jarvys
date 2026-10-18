package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure strict-grammar checks; no Android sensor or permission API is called. */
public class StepsRequestTest {
    @Test public void defaultAndBoundariesRemainBounded() throws Exception {
        assertEquals(15000, StepsRequest.parseCurrent("{}").timeoutMs);
        assertEquals(1000, StepsRequest.parseCurrent("{\"timeoutMs\":1000}").timeoutMs);
        assertEquals(120000, StepsRequest.parseCurrent("{\"timeoutMs\":120000}").timeoutMs);
    }

    @Test public void timeoutRejectsCoercionFractionalValuesAndOverflow() throws Exception {
        for (Object bad : new Object[]{"1000", true, false, JSONObject.NULL, new JSONObject(), new JSONArray(),
                -1, 0, 999, 120001, 1000.0, 1000.25, Long.MAX_VALUE})
            assertThrows(FactoryException.class, () -> StepsRequest.parseCurrent(new JSONObject().put("timeoutMs", bad)));
        for (String bad : new String[]{"{\"timeoutMs\":1e999}", "{\"timeoutMs\":NaN}", "{\"timeoutMs\":Infinity}",
                "{\"timeoutMs\":1e3}", "{\"timeoutMs\":1000.0}", "{\"timeoutMs\":1000.000000000000001}"})
            assertThrows(FactoryException.class, () -> StepsRequest.parseCurrent(bad));
        FactoryException field = assertThrows(FactoryException.class,
                () -> StepsRequest.parseCurrent(new JSONObject().put("timeoutMs", "soon")));
        assertEquals("steps.current: timeoutMs must be an integer from 1000 through 120000.", field.getMessage());
    }

    @Test public void strictJsonRejectsLeniencyDuplicatesAndTrailingData() {
        for (String bad : new String[]{"[]", "null", "", "{timeoutMs:1000}", "{'timeoutMs':1000}",
                "{\"timeoutMs\":1000,}", "{\"timeoutMs\":1000,\"timeoutMs\":2000}",
                "{\"timeoutMs\":1000,\"timeout\\u004ds\":2000}", "{} {}", "{}true", "{\"timeoutMs\":01}"})
            assertThrows(FactoryException.class, () -> StepsRequest.parseCurrent(bad));
    }

    @Test public void unknownFieldsCannotEnableBackgroundOrSyntheticCounting() throws Exception {
        for (String key : new String[]{"background", "watch", "intervalMs", "sensor", "reset", "daily", "strideLength", "accelerometer", "permission"})
            assertThrows(FactoryException.class, () -> StepsRequest.parseCurrent(new JSONObject().put(key, true)));
        assertThrows(FactoryException.class, () -> StepsRequest.parseCurrent((JSONObject) null));
    }

    @Test public void serializedAndStructuredArgumentsHaveTransportCeilings() throws Exception {
        String padding = new String(new char[StepsRequest.MAX_ARGS_BYTES]).replace('\0', ' ');
        assertEquals("TOO_LARGE", assertThrows(FactoryException.class, () -> StepsRequest.parseCurrent("{" + padding + "}")).code);
        assertEquals("TOO_LARGE", assertThrows(FactoryException.class, () -> StepsRequest.parseCurrent(new JSONObject().put("unknown", padding))).code);
        String multiByte = new String(new char[400]).replace('\0', '\u20ac');
        assertEquals("TOO_LARGE", assertThrows(FactoryException.class, () -> StepsRequest.parseCurrent(new JSONObject().put("unknown", multiByte))).code);
    }

    @Test public void statusCancelAndPermissionAcceptOnlyStrictEmptyObjects() throws Exception {
        StepsRequest.parseStatus("{}"); StepsRequest.parseCancel("{}"); StepsRequest.parsePermission("{}");
        StepsRequest.parseStatus(new JSONObject()); StepsRequest.parseCancel(new JSONObject()); StepsRequest.parsePermission(new JSONObject());
        for (String bad : new String[]{"[]", "null", "{\"timeoutMs\":1000}", "{\"background\":false}", "{}x", "{,}"}) {
            assertThrows(FactoryException.class, () -> StepsRequest.parseStatus(bad));
            assertThrows(FactoryException.class, () -> StepsRequest.parseCancel(bad));
            assertThrows(FactoryException.class, () -> StepsRequest.parsePermission(bad));
        }
        assertThrows(FactoryException.class, () -> StepsRequest.parsePermission((JSONObject) null));
    }

    @Test public void authorizationDetailsAreCanonicalAndIndependent() throws Exception {
        StepsRequest.Current request = StepsRequest.parseCurrent("{\"timeoutMs\":2000}");
        JSONObject details = request.authorizationDetails();
        assertEquals(1, details.length()); assertEquals(2000, details.getInt("timeoutMs"));
        details.put("timeoutMs", 999999).put("background", true);
        assertEquals(2000, request.timeoutMs); assertEquals(1, request.authorizationDetails().length());
        assertEquals(2000, request.authorizationDetails().getInt("timeoutMs"));
    }
}
