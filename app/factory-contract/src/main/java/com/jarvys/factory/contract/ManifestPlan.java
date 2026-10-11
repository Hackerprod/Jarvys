package com.jarvys.factory.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, closed effective v1 manifest. No project-defined nodes or attributes. */
public final class ManifestPlan {
    public static final String ANDROID = "http://schemas.android.com/apk/res/android";
    public static final String ACTIVITY = "com.jarvys.factory.runtime.FactoryActivity";
    public enum Profile { CURRENT, V4_BEFORE_GENERIC_NATIVE, V3_BEFORE_INCOMING_SHARE, V2_BEFORE_NATIVE_OPERATIONS, V1_BEFORE_CLIPBOARD_BROKER, V1_BEFORE_SAFE_AREA }
    public final Profile profile;
    public final IncomingSharePolicy incomingShare;
    public final NotificationPolicy notifications;
    public final BluetoothPolicy bluetooth;
    public final int widgetInfoResourceId;
    public final String appId, label, versionName;
    public final int versionCode, iconResourceId, backupResourceId;
    public final List<String> capabilities;
    public final List<Node> nodes;
    public final ManifestNodes.Element root;
    public final List<String> permissions;
    public final List<String> features;
    public final List<String> queries;
    public final List<String> queryActions;
    public final List<String> hosts = Collections.emptyList();
    public final List<String> exportedComponents = Collections.singletonList(ACTIVITY);
    public final List<String> privateComponents;

    public static final class Attribute {
        public final String namespace, name;
        public final int resourceId, type;
        public final Object value;
        private Attribute(String name, int resourceId, int type, Object value) {
            this.namespace = resourceId == 0 ? "" : ANDROID;
            this.name = name; this.resourceId = resourceId; this.type = type; this.value = value;
        }
        public String key() { return namespace + "|" + name; }
    }
    public static final class Node {
        public final String path;
        public final int parentIndex;
        public final Map<String, Attribute> attributes;
        Node(String path, int parentIndex, Attribute... values) {
            this.path = path; this.parentIndex = parentIndex;
            Map<String, Attribute> attrs = new LinkedHashMap<>();
            for (Attribute value : values) {
                if (attrs.put(value.key(), value) != null) throw new AssertionError("Duplicate plan attribute");
            }
            attributes = Collections.unmodifiableMap(attrs);
        }
    }
    static Attribute attribute(String name, int id, int type, Object value) { return new Attribute(name, id, type, value); }
    private static Attribute a(String name, int id, int type, Object value) { return attribute(name, id, type, value); }
    public ManifestPlan(String appId, String label, int versionCode, String versionName,
                        int iconResourceId, int backupResourceId, Iterable<String> capabilities) {
        this(appId,label,versionCode,versionName,iconResourceId,backupResourceId,capabilities,Profile.CURRENT);
    }
    public ManifestPlan(String appId, String label, int versionCode, String versionName,
                        int iconResourceId, int backupResourceId, Iterable<String> capabilities, Profile profile) {
        this(appId,label,versionCode,versionName,iconResourceId,backupResourceId,capabilities,null,profile);
    }
    public ManifestPlan(String appId, String label, int versionCode, String versionName,
                        int iconResourceId, int backupResourceId, Iterable<String> capabilities, IncomingSharePolicy incomingShare) {
        this(appId,label,versionCode,versionName,iconResourceId,backupResourceId,capabilities,incomingShare,Profile.CURRENT);
    }
    public ManifestPlan(String appId, String label, int versionCode, String versionName,
                        int iconResourceId, int backupResourceId, Iterable<String> capabilities, IncomingSharePolicy incomingShare, Profile profile) {
        this(appId,label,versionCode,versionName,iconResourceId,backupResourceId,capabilities,incomingShare,null,profile);
    }
    public ManifestPlan(String appId, String label, int versionCode, String versionName,
                        int iconResourceId, int backupResourceId, Iterable<String> capabilities, IncomingSharePolicy incomingShare, NotificationPolicy notifications) {
        this(appId,label,versionCode,versionName,iconResourceId,backupResourceId,capabilities,incomingShare,notifications,Profile.CURRENT);
    }
    public ManifestPlan(String appId, String label, int versionCode, String versionName,
                        int iconResourceId, int backupResourceId, Iterable<String> capabilities, IncomingSharePolicy incomingShare, NotificationPolicy notifications, Profile profile) {
        this(appId,label,versionCode,versionName,iconResourceId,backupResourceId,capabilities,incomingShare,notifications,null,0,profile);
    }
    public ManifestPlan(String appId, String label, int versionCode, String versionName,
                        int iconResourceId, int backupResourceId, Iterable<String> capabilities, IncomingSharePolicy incomingShare,
                        NotificationPolicy notifications, BluetoothPolicy bluetooth, int widgetInfoResourceId) {
        this(appId,label,versionCode,versionName,iconResourceId,backupResourceId,capabilities,incomingShare,notifications,bluetooth,widgetInfoResourceId,Profile.CURRENT);
    }
    public ManifestPlan(String appId, String label, int versionCode, String versionName,
                        int iconResourceId, int backupResourceId, Iterable<String> capabilities, IncomingSharePolicy incomingShare,
                        NotificationPolicy notifications, BluetoothPolicy bluetooth, int widgetInfoResourceId, Profile profile) {
        if (profile == null) throw new IllegalArgumentException("Missing manifest profile");
        this.profile = profile;
        if (appId == null || !appId.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){1,15}") || appId.length() > 127)
            throw new IllegalArgumentException("Invalid plan application ID");
        text(label, 80); text(versionName, 64);
        if (versionCode < 1 || (iconResourceId >>> 24) != 0x7f || (backupResourceId >>> 24) != 0x7f || iconResourceId == backupResourceId)
            throw new IllegalArgumentException("Invalid plan version or resource bindings");
        this.appId = appId; this.label = label; this.versionCode = versionCode; this.versionName = versionName;
        this.iconResourceId = iconResourceId; this.backupResourceId = backupResourceId;
        this.capabilities = CapabilityCatalog.select(capabilities);
        BluetoothPolicy.validateSelection(this.capabilities, bluetooth);
        F3ManifestPolicy.validateSelection(this.capabilities);
        if (profile != Profile.CURRENT && (this.capabilities.contains("nfc") || this.capabilities.contains("bluetooth") || this.capabilities.contains("widgets") || this.capabilities.contains("jobs")))
            throw new IllegalArgumentException("Generic native capabilities have no historical manifest profile");
        this.bluetooth = bluetooth;
        boolean widgets = this.capabilities.contains("widgets");
        if (widgets) { ManifestNodes.resource(widgetInfoResourceId); if (widgetInfoResourceId == iconResourceId || widgetInfoResourceId == backupResourceId) throw new IllegalArgumentException("Conflicting widget resource binding"); }
        else if (widgetInfoResourceId != 0) throw new IllegalArgumentException("Widget resource binding requires widgets");
        this.widgetInfoResourceId = widgetInfoResourceId;
        boolean beforeIncomingShare = profile != Profile.CURRENT && profile != Profile.V4_BEFORE_GENERIC_NATIVE;
        IncomingSharePolicy.validateSelection(this.capabilities, incomingShare);
        if (beforeIncomingShare && incomingShare != null)
            throw new IllegalArgumentException("incomingShare has no historical manifest profile");
        this.incomingShare = incomingShare;
        NotificationPolicy.validateSelection(this.capabilities, notifications);
        if (beforeIncomingShare && ((notifications != null && notifications.scheduling) || this.capabilities.contains("secrets")))
            throw new IllegalArgumentException("New native policy has no historical manifest profile");
        this.notifications = notifications;
        List<String> privateComponents = new ArrayList<>();
        if (notifications != null && notifications.scheduling) { privateComponents.add(NotificationPolicy.ALARM_RECEIVER); privateComponents.add(NotificationPolicy.RECOVERY_RECEIVER); }
        if (widgets) { privateComponents.add(F3ManifestPolicy.WIDGET_PROVIDER); privateComponents.add(F3ManifestPolicy.WIDGET_ACTION_RECEIVER); }
        if (this.capabilities.contains("jobs")) privateComponents.add(F3ManifestPolicy.JOB_SERVICE);
        this.privateComponents = Collections.unmodifiableList(privateComponents);
        boolean beforeNativeOperations = beforeIncomingShare && profile != Profile.V3_BEFORE_INCOMING_SHARE;
        if (beforeNativeOperations && (this.capabilities.contains("network")
                || this.capabilities.contains("notifications") || this.capabilities.contains("speech")
                || this.capabilities.contains("voiceRecognition") || this.capabilities.contains("location") || this.capabilities.contains("recording") || this.capabilities.contains("steps")))
            throw new IllegalArgumentException("Native operation capabilities have no historical manifest profile");
        List<Node> result = new ArrayList<>();
        result.add(new Node("manifest", -1,
            a("package", 0, 3, appId), a("versionCode", 0x0101021b, 16, versionCode),
            a("versionName", 0x0101021c, 3, versionName), a("compileSdkVersion", 0x01010572, 16, 36),
            a("compileSdkVersionCodename", 0x01010573, 3, "16"),
            a("platformBuildVersionCode", 0, 16, 36), a("platformBuildVersionName", 0, 16, 16)));
        result.add(new Node("manifest/uses-sdk", 0, a("minSdkVersion", 0x0101020c, 16, CapabilityCatalog.MIN_APP_API),
            a("targetSdkVersion", 0x01010270, 16, 35)));
        result.add(new Node("manifest/application", 0,
            a("theme", 0x01010000, 1, 0x01030241), a("label", 0x01010001, 3, label),
            a("icon", 0x01010002, 1, iconResourceId), a("allowBackup", 0x01010280, 18, 0),
            a("supportsRtl", 0x010103af, 18, -1), a("extractNativeLibs", 0x010104ea, 18, 0),
            // Selecting network explicitly permits plaintext HTTP. TLS validation is never bypassed.
            a("usesCleartextTraffic", 0x010104ec, 18, this.capabilities.contains("network") ? -1 : 0),
            a("appComponentFactory", 0x0101057a, 3, "androidx.core.app.CoreComponentFactory"),
            a("dataExtractionRules", 0x0101063e, 1, backupResourceId)));
        result.add(new Node("manifest/application/activity", 2, a("name", 0x01010003, 3, ACTIVITY),
            a("exported", 0x01010010, 18, -1), a("windowSoftInputMode", 0x0101022b, 17, 16)));
        result.add(new Node("manifest/application/activity/intent-filter", 3));
        result.add(new Node("manifest/application/activity/intent-filter/action", 4, a("name", 0x01010003, 3, "android.intent.action.MAIN")));
        result.add(new Node("manifest/application/activity/intent-filter/category", 4, a("name", 0x01010003, 3, "android.intent.category.LAUNCHER")));
        if (incomingShare != null) {
            incomingFilter(result, "android.intent.action.SEND", incomingShare);
            if (incomingShare.multiple) incomingFilter(result, "android.intent.action.SEND_MULTIPLE", incomingShare);
        }
        if (notifications != null && notifications.scheduling) notificationReceivers(result, notifications);
        if (widgets) widgetReceivers(result, widgetInfoResourceId);
        if (this.capabilities.contains("jobs")) result.add(new Node("manifest/application/service", 2,
            a("name", 0x01010003, 3, F3ManifestPolicy.JOB_SERVICE), a("exported", 0x01010010, 18, 0),
            a("enabled", 0x0101000e, 18, -1), a("directBootAware", 0x01010505, 18, 0), a("permission", 0x01010006, 3, F3ManifestPolicy.JOB_PERMISSION)));
        if (profile == Profile.V1_BEFORE_SAFE_AREA) {
            // Exact published v1 layout before UX35, only for previously receipted artifacts.
            result.set(3, without(result.get(3), "windowSoftInputMode"));
        }
        // Legacy receipt profiles retain the complete historical declaration set. Clipboard
        // was local then; it must not acquire new broker queries when re-verifying old bytes.
        List<String> manifestCapabilities = new ArrayList<>(this.capabilities);
        if (beforeNativeOperations) {
            manifestCapabilities.remove("share"); manifestCapabilities.remove("export"); manifestCapabilities.remove("sensors");
        }
        if (profile == Profile.V1_BEFORE_CLIPBOARD_BROKER || profile == Profile.V1_BEFORE_SAFE_AREA)
            manifestCapabilities.remove("clipboard");
        List<ManifestNodes.Element> contributions = CapabilityCatalog.manifestContributions(manifestCapabilities);
        List<String> selectedPermissions = new ArrayList<>(), selectedQueries = new ArrayList<>(), selectedQueryActions = new ArrayList<>();
        for (ManifestNodes.Element contribution : contributions) {
            if (profile == Profile.V1_BEFORE_SAFE_AREA)
                throw new IllegalArgumentException("Additional manifest declarations are not activated");
            if (contribution == CapabilityCatalog.BIOMETRIC_DECLARATION || contribution == CapabilityCatalog.INTERNET_DECLARATION
                    || contribution == CapabilityCatalog.NOTIFICATION_DECLARATION || contribution == CapabilityCatalog.COARSE_LOCATION_DECLARATION
                    || contribution == CapabilityCatalog.FINE_LOCATION_DECLARATION || contribution == CapabilityCatalog.RECORD_AUDIO_DECLARATION || contribution == CapabilityCatalog.ACTIVITY_RECOGNITION_DECLARATION || contribution == CapabilityCatalog.HIGH_RATE_SENSORS_DECLARATION) {
                String permission = contribution == CapabilityCatalog.BIOMETRIC_DECLARATION ? CapabilityCatalog.BIOMETRIC_PERMISSION
                        : contribution == CapabilityCatalog.INTERNET_DECLARATION ? CapabilityCatalog.INTERNET_PERMISSION
                        : contribution == CapabilityCatalog.COARSE_LOCATION_DECLARATION ? CapabilityCatalog.COARSE_LOCATION_PERMISSION
                        : contribution == CapabilityCatalog.ACTIVITY_RECOGNITION_DECLARATION ? CapabilityCatalog.ACTIVITY_RECOGNITION_PERMISSION
                        : contribution == CapabilityCatalog.HIGH_RATE_SENSORS_DECLARATION ? CapabilityCatalog.HIGH_RATE_SENSORS_PERMISSION
                        : contribution == CapabilityCatalog.RECORD_AUDIO_DECLARATION ? CapabilityCatalog.RECORD_AUDIO_PERMISSION
                        : contribution == CapabilityCatalog.FINE_LOCATION_DECLARATION ? CapabilityCatalog.FINE_LOCATION_PERMISSION : CapabilityCatalog.NOTIFICATION_PERMISSION;
                selectedPermissions.add(permission);
                result.add(new Node("manifest/uses-permission", 0,
                        a("name", 0x01010003, 3, permission)));
            } else if (contribution == CapabilityCatalog.DOCUMENT_QUERIES || contribution == CapabilityCatalog.SPEECH_QUERIES
                    || contribution == CapabilityCatalog.VOICE_QUERIES || contribution == CapabilityCatalog.SPEECH_VOICE_QUERIES) {
                selectedQueries.addAll(CapabilityCatalog.DOCUMENT_BROKER_PACKAGES);
                int queryIndex = result.size();
                result.add(new Node("manifest/queries", 0));
                if (contribution == CapabilityCatalog.SPEECH_QUERIES || contribution == CapabilityCatalog.SPEECH_VOICE_QUERIES)
                    selectedQueryActions.add(CapabilityCatalog.TTS_SERVICE_ACTION);
                if (contribution == CapabilityCatalog.VOICE_QUERIES || contribution == CapabilityCatalog.SPEECH_VOICE_QUERIES)
                    selectedQueryActions.add(CapabilityCatalog.RECOGNIZE_SPEECH_ACTION);
                for (String action : selectedQueryActions) {
                    int intentIndex = result.size();
                    result.add(new Node("manifest/queries/intent", queryIndex));
                    result.add(new Node("manifest/queries/intent/action", intentIndex, a("name", 0x01010003, 3, action)));
                }
                for (String host : selectedQueries) result.add(new Node("manifest/queries/package", queryIndex,
                        a("name", 0x01010003, 3, host)));
            } else throw new IllegalArgumentException("Additional manifest declarations are not activated");
        }
        if (notifications != null && notifications.scheduling) {
            selectedPermissions.add(NotificationPolicy.BOOT_PERMISSION);
            if (notifications.exactAlarms) selectedPermissions.add(NotificationPolicy.EXACT_PERMISSION);
            result.add(new Node("manifest/uses-permission", 0, a("name", 0x01010003, 3, NotificationPolicy.BOOT_PERMISSION)));
            if (notifications.exactAlarms) result.add(new Node("manifest/uses-permission", 0, a("name", 0x01010003, 3, NotificationPolicy.EXACT_PERMISSION)));
        }
        List<String> selectedFeatures = new ArrayList<>();
        if (this.capabilities.contains("nfc")) {
            permission(result, selectedPermissions, F3ManifestPolicy.NFC_PERMISSION, null, false);
            feature(result, selectedFeatures, F3ManifestPolicy.NFC_FEATURE);
        }
        if (bluetooth != null) {
            permission(result, selectedPermissions, F3ManifestPolicy.BLUETOOTH_PERMISSION, 30, false);
            permission(result, selectedPermissions, F3ManifestPolicy.BLUETOOTH_ADMIN_PERMISSION, 30, false);
            permission(result, selectedPermissions, F3ManifestPolicy.BLUETOOTH_SCAN_PERMISSION, null, bluetooth.neverForLocation());
            permission(result, selectedPermissions, F3ManifestPolicy.BLUETOOTH_CONNECT_PERMISSION, null, false);
            Integer locationMax = bluetooth.neverForLocation() ? 30 : null;
            permission(result, selectedPermissions, CapabilityCatalog.COARSE_LOCATION_PERMISSION, locationMax, false);
            permission(result, selectedPermissions, CapabilityCatalog.FINE_LOCATION_PERMISSION, locationMax, false);
            feature(result, selectedFeatures, F3ManifestPolicy.BLE_FEATURE);
        }
        if (this.capabilities.contains("jobs")) {
            permission(result, selectedPermissions, F3ManifestPolicy.NETWORK_STATE_PERMISSION, null, false);
            permission(result, selectedPermissions, NotificationPolicy.BOOT_PERMISSION, null, false);
        }
        features = Collections.unmodifiableList(selectedFeatures);
        permissions = Collections.unmodifiableList(selectedPermissions);
        queries = Collections.unmodifiableList(selectedQueries);
        queryActions = Collections.unmodifiableList(selectedQueryActions);
        nodes = Collections.unmodifiableList(result);
        root = tree(result, 0);
    }
    private static void permission(List<Node> nodes, List<String> permissions, String name, Integer max, boolean neverForLocation) {
        // Existing location capability needs unbounded location permission; never narrow it for BLE.
        if (permissions.contains(name)) return;
        permissions.add(name);
        List<Attribute> attrs = new ArrayList<>(); attrs.add(a("name", 0x01010003, 3, name));
        if (max != null) attrs.add(a("maxSdkVersion", 0x01010271, 16, max));
        if (neverForLocation) attrs.add(a("usesPermissionFlags", 0x01010644, 17, 0x10000));
        nodes.add(new Node("manifest/uses-permission", 0, attrs.toArray(new Attribute[0])));
    }
    private static void feature(List<Node> nodes, List<String> features, String name) {
        features.add(name); nodes.add(new Node("manifest/uses-feature", 0, a("name", 0x01010003, 3, name), a("required", 0x0101028e, 18, 0)));
    }
    private static void widgetReceivers(List<Node> nodes, int widgetInfo) {
        String path = "manifest/application/receiver"; int provider = nodes.size();
        nodes.add(new Node(path, 2, a("name", 0x01010003, 3, F3ManifestPolicy.WIDGET_PROVIDER), a("exported", 0x01010010, 18, 0), a("enabled", 0x0101000e, 18, -1)));
        int filter = nodes.size(); nodes.add(new Node(path + "/intent-filter", provider));
        nodes.add(new Node(path + "/intent-filter/action", filter, a("name", 0x01010003, 3, F3ManifestPolicy.WIDGET_ACTION)));
        nodes.add(new Node(path + "/meta-data", provider, a("name", 0x01010003, 3, F3ManifestPolicy.WIDGET_METADATA), a("resource", 0x01010025, 1, widgetInfo)));
        nodes.add(new Node(path, 2, a("name", 0x01010003, 3, F3ManifestPolicy.WIDGET_ACTION_RECEIVER), a("exported", 0x01010010, 18, 0), a("enabled", 0x0101000e, 18, -1)));
    }
    private static void notificationReceivers(List<Node> nodes, NotificationPolicy policy) {
        String path = "manifest/application/receiver";
        nodes.add(new Node(path, 2, a("name", 0x01010003, 3, NotificationPolicy.ALARM_RECEIVER), a("exported", 0x01010010, 18, 0)));
        int receiver = nodes.size();
        nodes.add(new Node(path, 2, a("name", 0x01010003, 3, NotificationPolicy.RECOVERY_RECEIVER), a("exported", 0x01010010, 18, 0)));
        int filter = nodes.size(); nodes.add(new Node(path + "/intent-filter", receiver));
        nodes.add(new Node(path + "/intent-filter/action", filter, a("name", 0x01010003, 3, "android.intent.action.BOOT_COMPLETED")));
        nodes.add(new Node(path + "/intent-filter/action", filter, a("name", 0x01010003, 3, "android.intent.action.MY_PACKAGE_REPLACED")));
        if (policy.exactAlarms) nodes.add(new Node(path + "/intent-filter/action", filter, a("name", 0x01010003, 3, NotificationPolicy.EXACT_ACTION)));
    }
    private static void incomingFilter(List<Node> nodes, String action, IncomingSharePolicy policy) {
        int filter = nodes.size(); String path = "manifest/application/activity/intent-filter";
        nodes.add(new Node(path, 3));
        nodes.add(new Node(path + "/action", filter, a("name", 0x01010003, 3, action)));
        nodes.add(new Node(path + "/category", filter, a("name", 0x01010003, 3, "android.intent.category.DEFAULT")));
        for (String mime : policy.mimeTypes) nodes.add(new Node(path + "/data", filter, a("mimeType", 0x01010026, 3, mime)));
    }
    private static Node without(Node node, String name) {
        List<Attribute> attrs = new ArrayList<>(node.attributes.values());
        for (int i = attrs.size() - 1; i >= 0; i--) if (attrs.get(i).name.equals(name)) attrs.remove(i);
        return new Node(node.path, node.parentIndex, attrs.toArray(new Attribute[0]));
    }
    private static ManifestNodes.Element tree(List<Node> nodes, int index) {
        Node node = nodes.get(index); List<ManifestNodes.Element> children = new ArrayList<>();
        for (int i = index + 1; i < nodes.size(); i++) if (nodes.get(i).parentIndex == index) children.add(tree(nodes, i));
        String name = node.path.substring(node.path.lastIndexOf('/') + 1);
        if (name.equals("uses-permission")) return ManifestNodes.permission(
                (String)node.attributes.get(ANDROID + "|name").value, ManifestNodes.PermissionVariant.STANDARD,
                node.attributes.containsKey(ANDROID + "|maxSdkVersion") ? (Integer)node.attributes.get(ANDROID + "|maxSdkVersion").value : null,
                node.attributes.containsKey(ANDROID + "|usesPermissionFlags"));
        if (name.equals("uses-feature")) return ManifestNodes.feature((String)node.attributes.get(ANDROID + "|name").value, false);
        if (name.equals("service")) return ManifestNodes.jobService();
        if (name.equals("meta-data")) return ManifestNodes.metadataResource((String)node.attributes.get(ANDROID + "|name").value, (Integer)node.attributes.get(ANDROID + "|resource").value);
        if (name.equals("receiver") && node.attributes.containsKey(ANDROID + "|enabled")) return ManifestNodes.enabledWidgetReceiver((String)node.attributes.get(ANDROID + "|name").value, children);
        if (name.equals("queries")) return ManifestNodes.queries(children);
        if (node.path.equals("manifest/queries/intent")) return ManifestNodes.queryIntent((String)nodes.get(index + 1).attributes.get(ANDROID + "|name").value, null, null);
        if (name.equals("receiver")) return ManifestNodes.privateComponent(ManifestNodes.ComponentKind.RECEIVER,
                (String)node.attributes.get(ANDROID + "|name").value, children);
        if (name.equals("action")) return ManifestNodes.action((String)node.attributes.get(ANDROID + "|name").value);
        if (name.equals("category")) return ManifestNodes.category((String)node.attributes.get(ANDROID + "|name").value);
        if (name.equals("data")) return ManifestNodes.data((String)node.attributes.get(ANDROID + "|mimeType").value, null);
        if (name.equals("package")) return ManifestNodes.queryPackage((String)node.attributes.values().iterator().next().value);
        return ManifestNodes.base(name, children, node.attributes.values().toArray(new Attribute[0]));
    }
    private static void text(String value, int max) { ManifestNodes.text(value, max); }
}
