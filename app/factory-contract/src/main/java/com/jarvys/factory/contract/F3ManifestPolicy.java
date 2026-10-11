package com.jarvys.factory.contract;

/** Closed native component names and selection invariants; never project-controlled XML. */
public final class F3ManifestPolicy {
    private F3ManifestPolicy() { }
    public static final String NFC_PERMISSION = "android.permission.NFC";
    public static final String NFC_FEATURE = "android.hardware.nfc";
    public static final String BLE_FEATURE = "android.hardware.bluetooth_le";
    public static final String BLUETOOTH_PERMISSION = "android.permission.BLUETOOTH";
    public static final String BLUETOOTH_ADMIN_PERMISSION = "android.permission.BLUETOOTH_ADMIN";
    public static final String BLUETOOTH_SCAN_PERMISSION = "android.permission.BLUETOOTH_SCAN";
    public static final String BLUETOOTH_CONNECT_PERMISSION = "android.permission.BLUETOOTH_CONNECT";
    public static final String NETWORK_STATE_PERMISSION = "android.permission.ACCESS_NETWORK_STATE";
    public static final String JOB_PERMISSION = "android.permission.BIND_JOB_SERVICE";
    public static final String JOB_SERVICE = "com.jarvys.factory.runtime.FactoryHttpJobService";
    public static final String WIDGET_PROVIDER = "com.jarvys.factory.runtime.FactoryWidgetProvider";
    public static final String WIDGET_ACTION_RECEIVER = "com.jarvys.factory.runtime.FactoryWidgetActionReceiver";
    public static final String WIDGET_ACTION = "android.appwidget.action.APPWIDGET_UPDATE";
    public static final String WIDGET_METADATA = "android.appwidget.provider";
    public static void validateSelection(Iterable<String> capabilities) {
        boolean jobs = false, network = false;
        for (String capability : capabilities) { jobs |= "jobs".equals(capability); network |= "network".equals(capability); }
        if (jobs && !network) throw new IllegalArgumentException("jobs requires the network capability");
    }
}
