package com.jarvys.factory.runtime;

import org.json.*;

/** Reference parser for the signed artifact profile. Runtime never accepts these settings from JS. */
public final class BleConfiguration {
    public final BlePermissions.ScanPrivacy privacy; public final BleCentral.Limits limits;
    private BleConfiguration(BlePermissions.ScanPrivacy privacy, BleCentral.Limits limits) { this.privacy = privacy; this.limits = limits; }
    public static BleConfiguration parse(JSONObject bluetooth, JSONObject resources) throws FactoryException {
        try {
            if (bluetooth == null || resources == null) throw BleRequest.invalid();
            BleRequest.exact(bluetooth, "version", "scanPrivacy");
            BleRequest.integer(bluetooth, "version", 1, 1); String privacy = BleRequest.text(bluetooth, "scanPrivacy");
            if (!privacy.equals("full") && !privacy.equals("neverForLocation")) throw BleRequest.invalid();
            BleRequest.exact(resources, "connections", "discoveredDevices", "queuedOperations", "bufferedEvents", "pollEvents", "services", "attributes", "subscriptions", "valueBytes", "scanDurationMs", "operationTimeoutMs");
            BleCentral.Limits limits = new BleCentral.Limits(n(resources,"connections",16), n(resources,"discoveredDevices",1024),
                    n(resources,"queuedOperations",256), n(resources,"bufferedEvents",2048), n(resources,"pollEvents",256),
                    n(resources,"services",256), n(resources,"attributes",4096), n(resources,"subscriptions",256), n(resources,"valueBytes",512),
                    BleRequest.integer(resources,"scanDurationMs",1,300000), BleRequest.integer(resources,"operationTimeoutMs",1,300000));
            return new BleConfiguration(privacy.equals("full") ? BlePermissions.ScanPrivacy.FULL : BlePermissions.ScanPrivacy.NEVER_FOR_LOCATION, limits);
        } catch (JSONException | IllegalArgumentException | FactoryException e) { throw BleCentral.error("INVALID_CONFIG", "Invalid signed Bluetooth privacy or resource declaration."); }
    }
    private static int n(JSONObject value, String name, int max) throws FactoryException, JSONException { return (int)BleRequest.integer(value,name,1,max); }
}
