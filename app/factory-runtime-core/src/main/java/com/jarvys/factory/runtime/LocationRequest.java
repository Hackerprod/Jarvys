package com.jarvys.factory.runtime;

import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** Strict, bounded foreground-only location arguments; no platform or permission effects. */
public final class LocationRequest {
    public static final int MAX_ARGS_BYTES = 2048;
    public static final int MAX_TIMEOUT_MS = 120000, MAX_AGE_MS = 300000;
    public static final double MAX_ACCURACY_METERS = 100000;
    private LocationRequest() { }

    public static final class Current {
        public final String provider;
        public final int timeoutMs, maxAgeMs;
        public final double maxAccuracyMeters;
        public final boolean allowMock;
        private Current(String provider, int timeoutMs, int maxAgeMs, double maxAccuracyMeters, boolean allowMock) {
            this.provider = provider; this.timeoutMs = timeoutMs; this.maxAgeMs = maxAgeMs;
            this.maxAccuracyMeters = maxAccuracyMeters; this.allowMock = allowMock;
        }
        public JSONObject authorizationDetails() {
            try {
                return new JSONObject().put("provider", provider).put("timeoutMs", timeoutMs)
                        .put("maxAgeMs", maxAgeMs).put("maxAccuracyMeters", maxAccuracyMeters).put("allowMock", allowMock);
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    public static final class Permission {
        public final String precision;
        private Permission(String precision) { this.precision = precision; }
        public JSONObject authorizationDetails() {
            try { return new JSONObject().put("precision", precision); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    public static Current parseCurrent(String json) throws FactoryException {
        return parseCurrent(StrictJson.object(json, MAX_ARGS_BYTES));
    }
    public static Current parseCurrent(JSONObject args) throws FactoryException {
        bounded(args);
        FactoryConfig.exactKeys(args, "provider", "timeoutMs", "maxAgeMs", "maxAccuracyMeters", "allowMock");
        try {
            String provider = args.has("provider") ? FactoryConfig.string(args, "provider") : "auto";
            if (!"auto".equals(provider) && !"gps".equals(provider) && !"network".equals(provider)) throw invalid();
            int timeout = integer(args, "timeoutMs", 15000, 1000, MAX_TIMEOUT_MS);
            int age = integer(args, "maxAgeMs", 0, 0, MAX_AGE_MS);
            double accuracy = number(args, "maxAccuracyMeters", 0);
            if (!finite(accuracy) || accuracy < 0 || accuracy > MAX_ACCURACY_METERS) throw invalid();
            boolean mock = true;
            if (args.has("allowMock")) {
                Object value = args.get("allowMock");
                if (!(value instanceof Boolean)) throw invalid();
                mock = (Boolean) value;
            }
            return new Current(provider, timeout, age, accuracy, mock);
        } catch (JSONException malformed) { throw invalid(); }
    }

    public static Permission parsePermission(String json) throws FactoryException {
        return parsePermission(StrictJson.object(json, MAX_ARGS_BYTES));
    }
    public static Permission parsePermission(JSONObject args) throws FactoryException {
        bounded(args); FactoryConfig.exactKeys(args, "precision");
        try {
            String precision = args.has("precision") ? FactoryConfig.string(args, "precision") : "approximate";
            if (!"approximate".equals(precision) && !"precise".equals(precision)) throw invalid();
            return new Permission(precision);
        } catch (JSONException malformed) { throw invalid(); }
    }

    public static void parseStatus(String json) throws FactoryException { parseEmpty(StrictJson.object(json, MAX_ARGS_BYTES)); }
    public static void parseStatus(JSONObject args) throws FactoryException { parseEmpty(args); }
    public static void parseCancel(String json) throws FactoryException { parseEmpty(StrictJson.object(json, MAX_ARGS_BYTES)); }
    public static void parseCancel(JSONObject args) throws FactoryException { parseEmpty(args); }
    public static void parseEmpty(JSONObject args) throws FactoryException { bounded(args); FactoryConfig.exactKeys(args); }

    private static int integer(JSONObject args, String key, int fallback, int min, int max) throws JSONException, FactoryException {
        double value = number(args, key, fallback);
        if (!finite(value) || value != Math.rint(value) || value < min || value > max) throw invalid();
        return (int) value;
    }
    private static double number(JSONObject args, String key, double fallback) throws JSONException, FactoryException {
        if (!args.has(key)) return fallback;
        Object value = args.get(key);
        if (!(value instanceof Number)) throw invalid();
        return ((Number) value).doubleValue();
    }
    static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
    private static void bounded(JSONObject args) throws FactoryException {
        if (args == null) throw invalid();
        String encoded = args.toString();
        if (encoded == null || encoded.length() > MAX_ARGS_BYTES || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_ARGS_BYTES)
            throw new FactoryException("TOO_LARGE", "Location arguments exceed the transport limit.");
    }
    private static FactoryException invalid() { return new FactoryException("INVALID_ARGUMENT", "Invalid typed foreground location arguments."); }
}
