package com.jarvys.factory.runtime;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure argument fixtures; no Android sensor access. */
public class SensorSubscriptionRequestTest {
    private static JSONObject args(Object period) throws Exception {
        return new JSONObject().put("kind", "gyroscope").put("samplingPeriodUs", period);
    }
    @Test public void acceptsOnlyExplicitRawPeriodsAndFastestWithoutCoercion() throws Exception {
        for (int period : new int[]{0, 4, 999, 1000, 4999, 5000, 100000, Integer.MAX_VALUE}) {
            SensorSubscriptionRequest.Subscribe request = SensorSubscriptionRequest.parseSubscribe(args(period));
            assertEquals(period, request.samplingPeriodUs);
            assertEquals("gyroscope", request.kind);
            assertEquals(period, request.authorizationDetails().getInt("samplingPeriodUs"));
        }
        for (Object value : new Object[]{1, 2, 3, -1, 2147483648L, Long.MAX_VALUE, "5000", true, 5000.0, JSONObject.NULL})
            assertEquals("INVALID_ARGUMENT", assertThrows(FactoryException.class,
                    () -> SensorSubscriptionRequest.parseSubscribe(args(value))).code);
    }
    @Test public void rejectsMissingUnknownAndBackgroundFields() throws Exception {
        for (String key : new String[]{"kind", "samplingPeriodUs"}) {
            JSONObject value = args(5000); value.remove(key);
            assertThrows(FactoryException.class, () -> SensorSubscriptionRequest.parseSubscribe(value));
        }
        for (String field : new String[]{"durationMs", "maxSamples", "background", "frequencyHz", "sensorId", "persist"})
            assertThrows(FactoryException.class, () -> SensorSubscriptionRequest.parseSubscribe(args(5000).put(field, 1)));
        for (String kind : new String[]{"gyro", "gyroscope_uncalibrated", "step-counter", "LIGHT", ""})
            assertThrows(FactoryException.class, () -> SensorSubscriptionRequest.parseSubscribe(args(5000).put("kind", kind)));
        assertThrows(FactoryException.class, () -> SensorSubscriptionRequest.parseSubscribe((JSONObject) null));
    }
    @Test public void strictJsonRejectsDuplicatesAndUnboundedArguments() throws Exception {
        assertEquals("INVALID_REQUEST", assertThrows(FactoryException.class, () -> SensorSubscriptionRequest.parseSubscribe(
                "{\"kind\":\"light\",\"samplingPeriodUs\":5000,\"samplingPeriodUs\":0}")).code);
        assertThrows(FactoryException.class, () -> SensorSubscriptionRequest.parseSubscribe("{kind:'light',samplingPeriodUs:5000}"));
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < SensorSubscriptionRequest.MAX_ARGS_BYTES; i++) huge.append('a');
        assertEquals("TOO_LARGE", assertThrows(FactoryException.class,
                () -> SensorSubscriptionRequest.parseSubscribe(args(5000).put("kind", huge.toString()))).code);
    }
    @Test public void opaqueHandleHasAnExactClosedShape() throws Exception {
        String id = "12345678-1234-1234-1234-123456789abc";
        assertEquals(id, SensorSubscriptionRequest.parseHandle(new JSONObject().put("subscriptionId", id)).subscriptionId);
        for (Object value : new Object[]{"", "123", id.toUpperCase(java.util.Locale.ROOT), " " + id, 123, true, JSONObject.NULL})
            assertThrows(FactoryException.class, () -> SensorSubscriptionRequest.parseHandle(new JSONObject().put("subscriptionId", value)));
        assertThrows(FactoryException.class, () -> SensorSubscriptionRequest.parseHandle(new JSONObject()));
        assertThrows(FactoryException.class, () -> SensorSubscriptionRequest.parseHandle(new JSONObject().put("subscriptionId", id).put("cursor", 1)));
    }
}
