package com.jarvys.factory.runtime;

import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** Closed arguments for page-owned streams. Parsing never registers hardware or grants access. */
public final class SensorSubscriptionRequest {
    public static final int MAX_ARGS_BYTES = 512;
    private SensorSubscriptionRequest() { }

    public static final class Subscribe {
        public final String kind;
        public final int samplingPeriodUs;
        private Subscribe(String kind, int samplingPeriodUs) {
            this.kind = kind; this.samplingPeriodUs = samplingPeriodUs;
        }
        public JSONObject authorizationDetails() {
            try { return new JSONObject().put("kind", kind).put("samplingPeriodUs", samplingPeriodUs); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    public static final class Handle {
        public final String subscriptionId;
        private Handle(String subscriptionId) { this.subscriptionId = subscriptionId; }
    }

    public static Subscribe parseSubscribe(String json) throws FactoryException {
        return parseSubscribe(StrictJson.object(json, MAX_ARGS_BYTES));
    }
    public static Subscribe parseSubscribe(JSONObject args) throws FactoryException {
        bounded(args); FactoryConfig.exactKeys(args, "kind", "samplingPeriodUs");
        try {
            String kind = FactoryConfig.string(args, "kind");
            if (!"light".equals(kind) && !"accelerometer".equals(kind) && !"gyroscope".equals(kind))
                throw invalid("Unsupported sensor kind.");
            Object value = args.get("samplingPeriodUs");
            if (!(value instanceof Integer) && !(value instanceof Long))
                throw invalid("samplingPeriodUs must be an integer.");
            long period = ((Number) value).longValue();
            if (period < 0 || period > Integer.MAX_VALUE)
                throw invalid("samplingPeriodUs is outside Android's integer range.");
            // SensorManager interprets 1, 2 and 3 as named delay constants, not microseconds.
            // Reject those aliases instead of silently changing the requested sampling period.
            if (period > 0 && period < 4)
                throw invalid("samplingPeriodUs must be 0 (fastest) or at least 4 microseconds; 1, 2 and 3 are Android delay aliases.");
            return new Subscribe(kind, (int) period);
        } catch (JSONException malformed) { throw invalid("Both kind and samplingPeriodUs are required."); }
    }

    public static Handle parseHandle(String json) throws FactoryException {
        return parseHandle(StrictJson.object(json, MAX_ARGS_BYTES));
    }
    public static Handle parseHandle(JSONObject args) throws FactoryException {
        bounded(args); FactoryConfig.exactKeys(args, "subscriptionId");
        try {
            String id = FactoryConfig.string(args, "subscriptionId");
            if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw invalid("A native subscriptionId is required.");
            return new Handle(id);
        } catch (JSONException malformed) { throw invalid("A native subscriptionId is required."); }
    }

    private static void bounded(JSONObject args) throws FactoryException {
        if (args == null) throw invalid("Sensor subscription arguments are required.");
        String encoded = args.toString();
        if (encoded == null || encoded.length() > MAX_ARGS_BYTES
                || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_ARGS_BYTES)
            throw new FactoryException("TOO_LARGE", "Sensor subscription arguments exceed the bounded envelope.");
    }
    private static FactoryException invalid(String message) { return new FactoryException("INVALID_ARGUMENT", message); }
}
