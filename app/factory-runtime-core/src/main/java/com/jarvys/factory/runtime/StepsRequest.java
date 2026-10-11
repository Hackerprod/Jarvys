package com.jarvys.factory.runtime;

import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** Strict, bounded one-shot step-counter arguments. Parsing has no permission/sensor effects. */
public final class StepsRequest {
    public static final int MAX_ARGS_BYTES = 1024;
    public static final int MIN_TIMEOUT_MS = 1000, DEFAULT_TIMEOUT_MS = 15000, MAX_TIMEOUT_MS = 120000;
    private StepsRequest() { }

    public static final class Current {
        public final int timeoutMs;
        private Current(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public JSONObject authorizationDetails() {
            try { return new JSONObject().put("timeoutMs", timeoutMs); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    public static Current parseCurrent(String json) throws FactoryException {
        return parseCurrent(StrictJson.object(json, MAX_ARGS_BYTES));
    }
    public static Current parseCurrent(JSONObject args) throws FactoryException {
        bounded(args); FactoryConfig.exactKeys(args, "timeoutMs");
        if (!args.has("timeoutMs")) return new Current(DEFAULT_TIMEOUT_MS);
        try {
            Object raw = args.get("timeoutMs");
            // Require an integer token/type, avoiding rounding a fractional/exponent input into
            // an allowed integer on Android's Double-based JSON implementation.
            if (!(raw instanceof Integer) && !(raw instanceof Long)) throw invalid();
            long value = ((Number) raw).longValue();
            if (value < MIN_TIMEOUT_MS || value > MAX_TIMEOUT_MS) throw invalid();
            return new Current((int) value);
        } catch (JSONException malformed) { throw invalid(); }
    }
    public static void parseStatus(String json) throws FactoryException { parseEmpty(StrictJson.object(json, MAX_ARGS_BYTES)); }
    public static void parseStatus(JSONObject args) throws FactoryException { parseEmpty(args); }
    public static void parseCancel(String json) throws FactoryException { parseEmpty(StrictJson.object(json, MAX_ARGS_BYTES)); }
    public static void parseCancel(JSONObject args) throws FactoryException { parseEmpty(args); }
    public static void parsePermission(String json) throws FactoryException { parseEmpty(StrictJson.object(json, MAX_ARGS_BYTES)); }
    public static void parsePermission(JSONObject args) throws FactoryException { parseEmpty(args); }
    public static void parseEmpty(JSONObject args) throws FactoryException { bounded(args); FactoryConfig.exactKeys(args); }

    private static void bounded(JSONObject args) throws FactoryException {
        if (args == null) throw invalid();
        String encoded = args.toString();
        if (encoded == null || encoded.length() > MAX_ARGS_BYTES || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_ARGS_BYTES)
            throw new FactoryException("TOO_LARGE", "Step-counter arguments exceed the transport limit.");
    }
    private static FactoryException invalid() { return new FactoryException("INVALID_ARGUMENT", "Invalid typed foreground step-counter arguments."); }
}
