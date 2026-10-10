package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ExternalLaunchSpec;
import org.json.JSONException;
import org.json.JSONObject;

/** Shared JSON boundary for both the generated bridge and authenticated native host. */
public final class ExternalLaunchRequest {
    public static final int MAX_ARGS_BYTES = 8192;
    public static final int MAX_EDITOR_ARGS_BYTES = 32768;
    private ExternalLaunchRequest() { }
    public static ExternalLaunchSpec parse(String method, String argsJson) throws FactoryException {
        JSONObject args = StrictJson.object(argsJson, ("email.compose".equals(method) || "sms.compose".equals(method)) ? MAX_EDITOR_ARGS_BYTES : MAX_ARGS_BYTES);
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
            if ("email.compose".equals(method)) {
                FactoryConfig.exactKeys(args, "to", "subject", "body");
                return ExternalLaunchSpec.email(FactoryConfig.string(args, "to"), FactoryConfig.string(args, "subject"), FactoryConfig.string(args, "body"));
            }
            if ("sms.compose".equals(method)) {
                FactoryConfig.exactKeys(args, "number", "body");
                return ExternalLaunchSpec.sms(FactoryConfig.string(args, "number"), FactoryConfig.string(args, "body"));
            }
            throw new FactoryException("UNKNOWN_METHOD", "Unsupported typed external action.");
        } catch (JSONException | IllegalArgumentException invalid) {
            throw new FactoryException("INVALID_ARGUMENT", "Use exact bounded fields for a typed map, dialer, email or SMS editor.");
        }
    }
}
