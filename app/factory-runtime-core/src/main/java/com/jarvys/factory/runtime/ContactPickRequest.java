package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ContactPickSpec;
import org.json.JSONException;
import org.json.JSONObject;

/** Shared strict JSON request boundary for a single human-selected phone or email value. */
public final class ContactPickRequest {
    public static final int MAX_ARGS_BYTES = 128;
    private ContactPickRequest() { }
    public static String parse(String argsJson) throws FactoryException {
        JSONObject args = StrictJson.object(argsJson, MAX_ARGS_BYTES);
        try {
            FactoryConfig.exactKeys(args, "kind");
            return ContactPickSpec.kind(FactoryConfig.string(args, "kind"));
        } catch (JSONException | IllegalArgumentException invalid) {
            throw new FactoryException("INVALID_ARGUMENT", "Use exactly kind: phone or email.");
        }
    }
}
