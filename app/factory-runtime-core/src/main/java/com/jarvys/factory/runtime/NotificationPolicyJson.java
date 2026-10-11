package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.NotificationPolicy;
import org.json.JSONObject;

/** Closed, exact Boolean-only signed scheduling declaration. */
public final class NotificationPolicyJson {
    private NotificationPolicyJson() { }
    public static NotificationPolicy parse(JSONObject object) throws FactoryException {
        try {
            if (object == null || object.length() != 2 || !(object.get("scheduling") instanceof Boolean) || !(object.get("exactAlarms") instanceof Boolean)) throw new IllegalArgumentException();
            return new NotificationPolicy((Boolean) object.get("scheduling"), (Boolean) object.get("exactAlarms"));
        } catch (Exception invalid) { throw new FactoryException("INVALID_CONFIG", "Invalid notification scheduling declaration."); }
    }
    public static JSONObject toJson(NotificationPolicy value) {
        try { return new JSONObject().put("scheduling", value.scheduling).put("exactAlarms", value.exactAlarms); }
        catch (org.json.JSONException impossible) { throw new IllegalArgumentException("Invalid notification policy", impossible); }
    }
}
