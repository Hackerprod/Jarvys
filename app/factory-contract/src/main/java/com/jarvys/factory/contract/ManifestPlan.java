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
    public enum Profile { CURRENT, V1_BEFORE_SAFE_AREA }
    public final Profile profile;
    public final String appId, label, versionName;
    public final int versionCode, iconResourceId, backupResourceId;
    public final List<String> capabilities;
    public final List<Node> nodes;
    public final ManifestNodes.Element root;
    public final List<String> permissions = Collections.emptyList();
    public final List<String> features = Collections.emptyList();
    public final List<String> queries;
    public final List<String> hosts = Collections.emptyList();
    public final List<String> exportedComponents = Collections.singletonList(ACTIVITY);

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
            a("usesCleartextTraffic", 0x010104ec, 18, 0),
            a("appComponentFactory", 0x0101057a, 3, "androidx.core.app.CoreComponentFactory"),
            a("dataExtractionRules", 0x0101063e, 1, backupResourceId)));
        result.add(new Node("manifest/application/activity", 2, a("name", 0x01010003, 3, ACTIVITY),
            a("exported", 0x01010010, 18, -1), a("windowSoftInputMode", 0x0101022b, 17, 16)));
        result.add(new Node("manifest/application/activity/intent-filter", 3));
        result.add(new Node("manifest/application/activity/intent-filter/action", 4, a("name", 0x01010003, 3, "android.intent.action.MAIN")));
        result.add(new Node("manifest/application/activity/intent-filter/category", 4, a("name", 0x01010003, 3, "android.intent.category.LAUNCHER")));
        if (profile == Profile.V1_BEFORE_SAFE_AREA) {
            // Exact published v1 layout before UX35, only for previously receipted artifacts.
            result.set(3, without(result.get(3), "windowSoftInputMode"));
        }
        List<ManifestNodes.Element> contributions = CapabilityCatalog.manifestContributions(this.capabilities);
        if (contributions.isEmpty()) queries = Collections.emptyList();
        else {
            if (profile != Profile.CURRENT || contributions.size() != 1 ||
                    contributions.get(0) != CapabilityCatalog.DOCUMENT_QUERIES)
                throw new IllegalArgumentException("Additional manifest declarations are not activated");
            queries = Collections.unmodifiableList(new ArrayList<>(CapabilityCatalog.DOCUMENT_BROKER_PACKAGES));
            int queryIndex = result.size();
            result.add(new Node("manifest/queries", 0));
            for (String host : queries) result.add(new Node("manifest/queries/package", queryIndex,
                    a("name", 0x01010003, 3, host)));
        }
        nodes = Collections.unmodifiableList(result);
        root = tree(result, 0);
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
        if (name.equals("queries")) return ManifestNodes.queries(children);
        if (name.equals("package")) return ManifestNodes.queryPackage((String)node.attributes.values().iterator().next().value);
        return ManifestNodes.base(name, children, node.attributes.values().toArray(new Attribute[0]));
    }
    private static void text(String value, int max) { ManifestNodes.text(value, max); }
}
