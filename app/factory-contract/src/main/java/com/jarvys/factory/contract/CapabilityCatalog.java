package com.jarvys.factory.contract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Implemented schema-v1 capabilities. Pure Java data shared by factory and runtime; never reflection. */
public final class CapabilityCatalog {
    private CapabilityCatalog() {}
    public static final int SCHEMA_VERSION = 1;
    public static final int SDK_VERSION = 2;
    public static final int MIN_APP_API = 24;
    public enum Method {
        RUNTIME_INFO("runtime.info", null),
        STORAGE_GET("storage.get", "storage"), STORAGE_SET("storage.set", "storage"),
        STORAGE_REMOVE("storage.remove", "storage"), STORAGE_LIST("storage.list", "storage"),
        EXPORT_TEXT("export.text", "export"), SHARE_TEXT("share.text", "share"), SHARE_FILE("share.file", "share"),
        CLIPBOARD_WRITE("clipboard.write", "clipboard"), HAPTICS_PERFORM("haptics.perform", "haptics"),
        DEVICE_INFO("device.info", "device"),
        DOCUMENTS_OPEN("documents.open", "documents"), DOCUMENTS_CREATE("documents.create", "documents"),
        DOCUMENTS_READ("documents.read", "documents"), DOCUMENTS_WRITE("documents.write", "documents"),
        DOCUMENTS_CLOSE("documents.close", "documents"), DOCUMENTS_CANCEL("documents.cancel", "documents");
        public final String wireName;
        public final String capability;
        Method(String wireName, String capability) { this.wireName = wireName; this.capability = capability; }
    }
    public static final class Capability {
        public final String name;
        public final int minimumApi = MIN_APP_API;
        public final List<Method> methods;
        // Only documents contributes the exact reviewed host-package visibility queries.
        public final List<ManifestNodes.Element> manifestNodes;
        public final Set<String> permissions = Collections.emptySet();
        public final Set<String> features = Collections.emptySet();
        public final Set<String> components = Collections.emptySet();
        public final Set<String> intentFilters = Collections.emptySet();
        public final Set<String> queries;
        public final Set<String> metadata = Collections.emptySet();
        public final Set<String> resources = Collections.emptySet();
        public final Set<String> dependencies = Collections.emptySet();
        public final Set<String> conflicts = Collections.emptySet();
        private Capability(String name) {
            this.name = name;
            manifestNodes = name.equals("documents") ? Collections.singletonList(DOCUMENT_QUERIES) : Collections.emptyList();
            queries = name.equals("documents") ? DOCUMENT_BROKER_PACKAGES : Collections.emptySet();
            List<Method> selected = new ArrayList<>();
            for (Method method : Method.values()) if (name.equals(method.capability)) selected.add(method);
            methods = Collections.unmodifiableList(selected);
        }
    }
    public static final Set<String> DOCUMENT_BROKER_PACKAGES = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList("com.jarvys.agent", "com.jarvys.agent.recoverytest")));
    static final ManifestNodes.Element DOCUMENT_QUERIES = ManifestNodes.queries(Arrays.asList(
            ManifestNodes.queryPackage("com.jarvys.agent"), ManifestNodes.queryPackage("com.jarvys.agent.recoverytest")));
    public static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList(
            "storage", "export", "share", "clipboard", "haptics", "device", "documents"));
    public static final Set<String> SUPPORTED = Collections.unmodifiableSet(new LinkedHashSet<>(NAMES));
    public static final Map<String, Capability> CAPABILITIES;
    public static final Map<String, Method> METHODS;
    static {
        Map<String, Capability> capabilities = new LinkedHashMap<>();
        for (String name : NAMES) capabilities.put(name, new Capability(name));
        CAPABILITIES = Collections.unmodifiableMap(capabilities);
        Map<String, Method> methods = new LinkedHashMap<>();
        for (Method method : Method.values()) {
            if (method.capability != null && !SUPPORTED.contains(method.capability)) throw new AssertionError("Unknown capability");
            if (methods.put(method.wireName, method) != null) throw new AssertionError("Duplicate method");
        }
        METHODS = Collections.unmodifiableMap(methods);
    }
    /** Only compiled catalog entries contribute; project strings can never register declarations. */
    public static List<ManifestNodes.Element> manifestContributions(Iterable<String> requested) {
        List<ManifestNodes.Element> result = new ArrayList<>();
        for (String name : select(requested)) result.addAll(CAPABILITIES.get(name).manifestNodes);
        return ManifestNodes.ordered(result);
    }
    /** Defensive, deterministic selection; no implied or future capabilities. */
    public static List<String> select(Iterable<String> requested) {
        if (requested == null) throw new IllegalArgumentException("Missing capabilities");
        Set<String> found = new LinkedHashSet<>();
        for (String name : requested) {
            if (!SUPPORTED.contains(name) || !found.add(name)) throw new IllegalArgumentException("Unknown or duplicate capability");
        }
        List<String> result = new ArrayList<>(found);
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }
}
