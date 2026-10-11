package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.BluetoothPolicy;
import org.json.JSONObject;

/** Exact, non-coercing signed Bluetooth scan privacy schema. */
public final class BluetoothPolicyJson {
    private BluetoothPolicyJson() { }
    public static BluetoothPolicy parse(JSONObject object) throws FactoryException {
        try {
            if (object == null || object.length() != 2 || !(object.get("version") instanceof Integer) || object.getInt("version") != 1 || !(object.get("scanPrivacy") instanceof String)) throw new IllegalArgumentException();
            return new BluetoothPolicy((String) object.get("scanPrivacy"));
        } catch (Exception invalid) { throw new FactoryException("INVALID_CONFIG", "Invalid Bluetooth scan privacy declaration."); }
    }
    public static JSONObject toJson(BluetoothPolicy value) {
        try { return new JSONObject().put("version", value.version).put("scanPrivacy", value.scanPrivacy); }
        catch (org.json.JSONException impossible) { throw new IllegalArgumentException("Invalid Bluetooth policy", impossible); }
    }
}
