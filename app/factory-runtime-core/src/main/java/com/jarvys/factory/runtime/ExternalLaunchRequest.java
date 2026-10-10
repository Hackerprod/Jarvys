package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ExternalLaunchSpec;
import org.json.JSONException;
import org.json.JSONObject;

/** Shared JSON boundary for both the generated bridge and authenticated native host. */
public final class ExternalLaunchRequest {
    public static final int MAX_ARGS_BYTES = 8192;
    private ExternalLaunchRequest() { }
    public static ExternalLaunchSpec parse(String method, String argsJson) throws FactoryException {
        JSONObject args = StrictJson.object(argsJson, MAX_ARGS_BYTES);
        try {
            if ("maps.open".equals(method)) {
                if (args.has("query")) {
                    FactoryConfig.exactKeys(args, "query");
                    return ExternalLaunchSpec.query(FactoryConfig.string(args, "query"));
                }
                FactoryConfig.exactKeys(args, "latitude", "longitude");
                Object latitude = args.get("latitude"), longitude = args.get("longitude");
                if (!(latitude instanceof Number) || !(longitude instanceof Number))
                    throw new IllegalArgumentException("Coordinates must be JSON numbers");
                return ExternalLaunchSpec.coordinates(((Number) latitude).doubleValue(), ((Number) longitude).doubleValue());
            }
            if ("phone.dial".equals(method)) {
                FactoryConfig.exactKeys(args, "number");
                return ExternalLaunchSpec.dial(FactoryConfig.string(args, "number"));
            }
            throw new FactoryException("UNKNOWN_METHOD", "Unsupported typed external action.");
        } catch (JSONException | IllegalArgumentException invalid) {
            throw new FactoryException("INVALID_ARGUMENT", "Use exact typed map coordinates, a safe bounded query, or a plain dialer number.");
        }
    }
}
