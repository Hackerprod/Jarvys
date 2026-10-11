package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.IncomingSharePolicy;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Closed typed JSON adapter shared by project, runtime and retained signed evidence. */
public final class IncomingSharePolicyJson {
    private IncomingSharePolicyJson() { }
    public static IncomingSharePolicy parse(JSONObject object) throws FactoryException {
        try {
            if (object == null || object.length() != 2 || !object.has("mimeTypes") || !object.has("multiple") ||
                    !(object.get("mimeTypes") instanceof JSONArray) || !(object.get("multiple") instanceof Boolean))
                throw new IllegalArgumentException();
            JSONArray declared = object.getJSONArray("mimeTypes");
            if (declared.length() < 1 || declared.length() > IncomingSharePolicy.MAX_MIME_TYPES) throw new IllegalArgumentException();
            List<String> values = new ArrayList<>();
            for (int i = 0; i < declared.length(); i++) {
                Object value = declared.get(i);
                if (!(value instanceof String)) throw new IllegalArgumentException();
                values.add((String) value);
            }
            return new IncomingSharePolicy(values, (Boolean) object.get("multiple"));
        } catch (Exception invalid) { throw new FactoryException("INVALID_CONFIG", "Invalid incomingShare declaration."); }
    }
    public static JSONObject toJson(IncomingSharePolicy policy) {
        try { return new JSONObject().put("mimeTypes", new JSONArray(policy.mimeTypes)).put("multiple", policy.multiple); }
        catch (org.json.JSONException impossible) { throw new IllegalArgumentException("Invalid incomingShare policy", impossible); }
    }
}
