package com.jarvys.factory.contract;

/** Signed scan privacy assertion. Absent historical configuration never grants Bluetooth. */
public final class BluetoothPolicy {
    public final int version = 1;
    public final String scanPrivacy;
    public BluetoothPolicy(String scanPrivacy) {
        if (!"full".equals(scanPrivacy) && !"neverForLocation".equals(scanPrivacy))
            throw new IllegalArgumentException("Bluetooth scanPrivacy must be full or neverForLocation");
        this.scanPrivacy = scanPrivacy;
    }
    public boolean neverForLocation() { return "neverForLocation".equals(scanPrivacy); }
    public static void validateSelection(Iterable<String> capabilities, BluetoothPolicy policy) {
        boolean selected = false;
        for (String capability : capabilities) if ("bluetooth".equals(capability)) selected = true;
        if (selected != (policy != null)) throw new IllegalArgumentException("Bluetooth capability requires an explicit bluetooth scan privacy declaration, and vice versa");
    }
    @Override public boolean equals(Object other) { return other instanceof BluetoothPolicy && scanPrivacy.equals(((BluetoothPolicy) other).scanPrivacy); }
    @Override public int hashCode() { return scanPrivacy.hashCode(); }
}
