package com.jarvys.factory.runtime;

import java.util.*;

/** Signed artifact privacy choice. It is not inferred from the capability or mutable JS arguments. */
public final class BlePermissions {
    public enum ScanPrivacy { FULL, NEVER_FOR_LOCATION }
    public static final String SCAN = "android.permission.BLUETOOTH_SCAN", CONNECT = "android.permission.BLUETOOTH_CONNECT",
            LEGACY = "android.permission.BLUETOOTH", ADMIN = "android.permission.BLUETOOTH_ADMIN",
            FINE = "android.permission.ACCESS_FINE_LOCATION", COARSE = "android.permission.ACCESS_COARSE_LOCATION";
    private BlePermissions() { }
    public static List<String> required(int deviceApi, int targetApi, boolean scan, boolean connect, ScanPrivacy privacy) {
        if (deviceApi < 23 || targetApi < 23 || privacy == null) throw new IllegalArgumentException("Unsupported Bluetooth permission profile");
        List<String> result = new ArrayList<>();
        if (deviceApi >= 31 && targetApi >= 31) {
            if (scan) result.add(SCAN);
            if (connect) result.add(CONNECT);
            if (scan && privacy == ScanPrivacy.FULL) { result.add(COARSE); result.add(FINE); }
        } else {
            if (scan || connect) result.add(LEGACY);
            if (scan) { result.add(ADMIN); result.add(FINE); }
        }
        return Collections.unmodifiableList(result);
    }
    public static List<String> dangerous(int deviceApi, int targetApi, boolean scan, boolean connect, ScanPrivacy privacy) {
        List<String> result = new ArrayList<>(required(deviceApi, targetApi, scan, connect, privacy));
        result.remove(LEGACY); result.remove(ADMIN); return Collections.unmodifiableList(result);
    }
    /** For current target >=31. ManifestPlan must preserve maxSdk/flags in tree, binary and audit paths. */
    public static List<Map<String, Object>> manifest(ScanPrivacy privacy) {
        if (privacy == null) throw new IllegalArgumentException("Explicit Bluetooth scan privacy is required");
        List<Map<String,Object>> result = new ArrayList<>();
        result.add(BleCentral.map("name", LEGACY, "maxSdkVersion", 30));
        result.add(BleCentral.map("name", ADMIN, "maxSdkVersion", 30));
        Map<String,Object> scan = BleCentral.map("name", SCAN);
        if (privacy == ScanPrivacy.NEVER_FOR_LOCATION) scan.put("usesPermissionFlags", "neverForLocation");
        result.add(scan); result.add(BleCentral.map("name", CONNECT));
        Map<String,Object> coarse = BleCentral.map("name", COARSE), fine = BleCentral.map("name", FINE);
        if (privacy == ScanPrivacy.NEVER_FOR_LOCATION) { coarse.put("maxSdkVersion", 30); fine.put("maxSdkVersion", 30); }
        result.add(coarse); result.add(fine); return Collections.unmodifiableList(result);
    }
}
