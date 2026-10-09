package com.jarvys.factory.contract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Compiled-code construction vocabulary, NOT a project or bridge permission grant.
 * No raw XML, arbitrary attributes, exported components, or catalog registration API.
 */
public final class ManifestNodes {
    private ManifestNodes() {}
    public enum PermissionVariant { STANDARD, SDK_23 }
    public enum ComponentKind { ACTIVITY, SERVICE, RECEIVER }
    public static final class Element {
        public final String name;
        public final List<ManifestPlan.Attribute> attributes;
        public final List<Element> children;
        private final String identity;
        private Element(String name, String identity, List<Element> children, ManifestPlan.Attribute... attrs) {
            this(name, identity, children, false, attrs);
        }
        private Element(String name, String identity, List<Element> children, boolean preserveOrder, ManifestPlan.Attribute... attrs) {
            this.name = name; this.identity = identity;
            List<ManifestPlan.Attribute> values = new ArrayList<>(Arrays.asList(attrs));
            values.sort(Comparator.comparingInt(a -> a.resourceId));
            Set<String> keys = new HashSet<>();
            for (ManifestPlan.Attribute a : values) if (a == null || !keys.add(a.key())) fail("Duplicate attribute");
            this.attributes = Collections.unmodifiableList(values);
            List<Element> checked = ordered(children);
            this.children = preserveOrder ? Collections.unmodifiableList(new ArrayList<>(children)) : checked;
        }
    }
    private static ManifestPlan.Attribute a(String name, int id, int type, Object value) {
        return ManifestPlan.attribute(name, id, type, value);
    }
    private static Element leaf(String name, String identity, ManifestPlan.Attribute... attrs) {
        return new Element(name, identity, Collections.emptyList(), attrs);
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
    static void text(String value, int max) {
        if (value == null || value.isEmpty() || value.length() > max) fail("Invalid manifest text");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) fail("Control in manifest text");
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) fail("Invalid manifest Unicode");
            } else if (Character.isLowSurrogate(c)) fail("Invalid manifest Unicode");
        }
    }
    private static String token(String value) {
        text(value, 200);
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) fail("Invalid manifest name");
        return value;
    }
    private static String androidName(String value, String prefix) {
        token(value);
        if (!value.startsWith(prefix) || value.length() == prefix.length()) fail("Unexpected Android name");
        return value;
    }
    static List<Element> ordered(List<Element> input) {
        if (input == null || input.size() > 32) fail("Manifest sibling limit exceeded");
        List<Element> copy = new ArrayList<>(input); Set<String> keys = new HashSet<>();
        Set<String> componentNames = new HashSet<>(), authorities = new HashSet<>();
        for (Element e : copy) {
            if (e == null || !keys.add(e.identity)) fail("Duplicate or conflicting manifest declaration");
            if (Arrays.asList("activity", "service", "receiver", "provider").contains(e.name)) {
                if (!componentNames.add(value(e, "name"))) fail("Conflicting component name");
                if (e.name.equals("provider") && !authorities.add(value(e, "authorities"))) fail("Conflicting provider authority");
            }
        }
        copy.sort(Comparator.comparing(e -> e.identity));
        return Collections.unmodifiableList(copy);
    }
    private static String value(Element element, String name) {
        for (ManifestPlan.Attribute a : element.attributes) if (a.name.equals(name) && a.namespace.equals(ManifestPlan.ANDROID) && a.type == 3) return (String)a.value;
        throw new IllegalArgumentException("Missing component identity");
    }
    private static void kinds(List<Element> values, String... names) {
        if (values == null) fail("Missing manifest children");
        for (Element e : values) if (e == null || !Arrays.asList(names).contains(e.name)) fail("Invalid manifest child");
    }
    public static Element permission(String name, PermissionVariant variant, Integer maxSdkVersion) {
        androidName(name, "android.permission.");
        if (variant == null || (maxSdkVersion != null && (maxSdkVersion < 24 || maxSdkVersion > 36))) fail("Invalid permission API bound");
        List<ManifestPlan.Attribute> attrs = new ArrayList<>(); attrs.add(a("name", 0x01010003, 3, name));
        if (maxSdkVersion != null) attrs.add(a("maxSdkVersion", 0x01010271, 16, maxSdkVersion));
        return leaf(variant == PermissionVariant.STANDARD ? "uses-permission" : "uses-permission-sdk-23",
            "permission:" + name, attrs.toArray(new ManifestPlan.Attribute[0]));
    }
    public static Element feature(String name, boolean required) {
        androidName(name, "android.hardware.");
        return leaf("uses-feature", "feature:" + name, a("name", 0x01010003, 3, name), a("required", 0x0101028e, 18, required ? -1 : 0));
    }
    public static Element action(String name) {
        androidName(name, "android.intent.action."); return leaf("action", "action:" + name, a("name", 0x01010003, 3, name));
    }
    public static Element category(String name) {
        androidName(name, "android.intent.category."); return leaf("category", "category:" + name, a("name", 0x01010003, 3, name));
    }
    public static Element data(String mimeType, String scheme) {
        if (mimeType == null && scheme == null) fail("Empty intent data");
        List<ManifestPlan.Attribute> attrs = new ArrayList<>();
        if (mimeType != null) {
            text(mimeType, 127);
            if (!mimeType.matches("[a-z0-9][a-z0-9.+-]*/(?:[a-z0-9][a-z0-9.+-]*|\\*)") && !mimeType.equals("*/*")) fail("Invalid MIME type");
            attrs.add(a("mimeType", 0x01010026, 3, mimeType));
        }
        if (scheme != null) {
            if (!Arrays.asList("https", "geo", "tel", "mailto", "sms", "content").contains(scheme)) fail("Unsupported intent scheme");
            attrs.add(a("scheme", 0x01010027, 3, scheme));
        }
        return leaf("data", "data:" + mimeType + ":" + scheme, attrs.toArray(new ManifestPlan.Attribute[0]));
    }
    public static Element intentFilter(List<Element> children) {
        kinds(children, "action", "category", "data");
        if (children.stream().noneMatch(e -> e.name.equals("action"))) fail("Intent filter needs an action");
        List<Element> ordered = ordered(children);
        StringBuilder key = new StringBuilder("filter:"); for (Element e : ordered) key.append(e.identity).append(';');
        return new Element("intent-filter", key.toString(), ordered);
    }
    public static Element privateComponent(ComponentKind kind, String className, List<Element> children) {
        if (kind == null) fail("Missing component kind"); token(className);
        kinds(children, "meta-data", "intent-filter");
        return new Element(kind.name().toLowerCase(java.util.Locale.ROOT), "component:" + className, children,
            a("name", 0x01010003, 3, className), a("exported", 0x01010010, 18, 0));
    }
    /** Authorities cannot be shared between independently named applications. No path grants here. */
    public static Element privateProvider(String className, String appId, String suffix, boolean grantUriPermissions, List<Element> metadata) {
        token(className); appId(appId);
        if (suffix == null || !suffix.matches("[a-z][a-z0-9_]{0,31}")) fail("Invalid authority suffix");
        kinds(metadata, "meta-data");
        return new Element("provider", "component:" + className, metadata, a("name", 0x01010003, 3, className),
            a("exported", 0x01010010, 18, 0), a("authorities", 0x01010018, 3, appId + "." + suffix),
            a("grantUriPermissions", 0x0101001b, 18, grantUriPermissions ? -1 : 0));
    }
    public static Element metadataString(String name, String value) { token(name); text(value, 512); return metadata(name, a("value", 0x01010024, 3, value)); }
    public static Element metadataInt(String name, int value) { token(name); return metadata(name, a("value", 0x01010024, 16, value)); }
    public static Element metadataBoolean(String name, boolean value) { token(name); return metadata(name, a("value", 0x01010024, 18, value ? -1 : 0)); }
    /** Only a compiled catalog may supply this binding; existence/type/name must be audited before future activation. */
    public static Element metadataResource(String name, int resourceId) {
        token(name); resource(resourceId); return metadata(name, a("resource", 0x01010025, 1, resourceId));
    }
    private static Element metadata(String name, ManifestPlan.Attribute value) {
        return leaf("meta-data", "metadata:" + name, a("name", 0x01010003, 3, name), value);
    }
    public static Element queryPackage(String packageName) {
        appId(packageName); return leaf("package", "package:" + packageName, a("name", 0x01010003, 3, packageName));
    }
    public static Element queryIntent(String actionName, String mimeType, String scheme) {
        List<Element> children = new ArrayList<>(); children.add(action(actionName));
        if (mimeType != null || scheme != null) children.add(data(mimeType, scheme));
        return new Element("intent", "intent:" + actionName + ":" + mimeType + ":" + scheme, children);
    }
    public static Element queries(List<Element> children) {
        kinds(children, "package", "intent"); if (children.isEmpty()) fail("Empty queries declaration");
        return new Element("queries", "queries", children);
    }
    static void appId(String value) {
        if (value == null || value.length() > 127 || !value.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){1,15}")) fail("Invalid application ID");
    }
    static void resource(int value) {
        if ((value >>> 24) != 0x7f || ((value >>> 16) & 255) == 0) fail("Invalid compiled resource binding");
    }
    // Only these package-private constructors can create the closed base. Never parsed from project JSON.
    static Element base(String name, List<Element> children, ManifestPlan.Attribute... attrs) {
        if (!Arrays.asList("manifest", "uses-sdk", "application", "activity", "intent-filter", "action", "category").contains(name)) fail("Unknown base node");
        switch (name) {
            case "manifest":
                kinds(children, "uses-sdk", "uses-permission", "uses-permission-sdk-23", "uses-feature", "queries", "application");
                if (children.stream().filter(e -> e.name.equals("uses-sdk")).count() != 1 || children.stream().filter(e -> e.name.equals("application")).count() != 1) fail("Missing base singleton");
                break;
            case "application": kinds(children, "activity", "service", "receiver", "provider", "meta-data"); break;
            case "activity": kinds(children, "intent-filter", "meta-data"); break;
            case "intent-filter":
                kinds(children, "action", "category", "data");
                if (children.stream().noneMatch(e -> e.name.equals("action"))) fail("Missing filter action");
                break;
            default: if (children == null || !children.isEmpty()) fail("Leaf node cannot have children");
        }
        return new Element(name, name, children, true, attrs);
    }
}
