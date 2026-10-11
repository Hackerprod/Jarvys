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
    public static final String BIOMETRIC_PERMISSION = "android.permission.USE_BIOMETRIC";
    public static final String INTERNET_PERMISSION = "android.permission.INTERNET";
    public static final String NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS";
    public static final String COARSE_LOCATION_PERMISSION = "android.permission.ACCESS_COARSE_LOCATION";
    public static final String FINE_LOCATION_PERMISSION = "android.permission.ACCESS_FINE_LOCATION";
    public static final String RECOGNIZE_SPEECH_ACTION = "android.speech.action.RECOGNIZE_SPEECH";
    public static final String TTS_SERVICE_ACTION = "android.intent.action.TTS_SERVICE";
    public enum Method {
        RUNTIME_INFO("runtime.info", null),
        STORAGE_GET("storage.get", "storage"), STORAGE_SET("storage.set", "storage"),
        STORAGE_REMOVE("storage.remove", "storage"), STORAGE_LIST("storage.list", "storage"),
        STORAGE_BACKUP_EXPORT("storage.backupExport", "storage"), STORAGE_BACKUP_IMPORT("storage.backupImport", "storage"),
        EXPORT_TEXT("export.text", "export"), SHARE_TEXT("share.text", "share"), SHARE_FILE("share.file", "share"),
        CLIPBOARD_WRITE("clipboard.write", "clipboard"), HAPTICS_PERFORM("haptics.perform", "haptics"),
        DEVICE_INFO("device.info", "device"),
        DOCUMENTS_OPEN("documents.open", "documents"), DOCUMENTS_CREATE("documents.create", "documents"),
        DOCUMENTS_READ("documents.read", "documents"), DOCUMENTS_WRITE("documents.write", "documents"),
        DOCUMENTS_CLOSE("documents.close", "documents"), DOCUMENTS_CANCEL("documents.cancel", "documents"),
        PHOTOS_PICK("photos.pick", "photos"), PHOTOS_CAPTURE("photos.capture", "photos"), AUDIO_PLAY("audio.play", "audio"),
        BROWSER_OPEN("browser.open", "browser"), MAPS_OPEN("maps.open", "maps"), PHONE_DIAL("phone.dial", "phone"),
        EMAIL_COMPOSE("email.compose", "email"), SMS_COMPOSE("sms.compose", "sms"), CONTACTS_PICK("contacts.pick", "contacts"), CALENDAR_INSERT("calendar.insert", "calendar"),
        DATABASE_INFO("database.info", "database"), DATABASE_MIGRATE("database.migrate", "database"),
        DATABASE_TRANSACT("database.transact", "database"), DATABASE_SELECT("database.select", "database"),
        DATABASE_BACKUP_EXPORT("database.backupExport", "database"), DATABASE_BACKUP_IMPORT("database.backupImport", "database"),
        DATABASE_CLOSE("database.close", "database"), DATABASE_CANCEL("database.cancel", "database"),
        PRESENTATION_GET("presentation.get", "presentation"), PRESENTATION_SET("presentation.set", "presentation"),
        PRESENTATION_RESET("presentation.reset", "presentation"),
        SENSORS_LIST("sensors.list", "sensors"), SENSORS_CAPTURE("sensors.capture", "sensors"), SENSORS_CANCEL("sensors.cancel", "sensors"),
        BIOMETRICS_AVAILABILITY("biometrics.availability", "biometrics"),
        BIOMETRICS_AUTHENTICATE("biometrics.authenticate", "biometrics"), BIOMETRICS_CANCEL("biometrics.cancel", "biometrics"),
        NETWORK_REQUEST("network.request", "network"), NETWORK_PROBE("network.probe", "network"), NETWORK_PING("network.ping", "network"), NETWORK_CANCEL("network.cancel", "network"),
        NOTIFICATIONS_STATUS("notifications.status", "notifications"), NOTIFICATIONS_CHANNEL("notifications.channel", "notifications"),
        NOTIFICATIONS_SHOW("notifications.show", "notifications"), NOTIFICATIONS_CANCEL("notifications.cancel", "notifications"),
        NOTIFICATIONS_REQUEST_PERMISSION("notifications.requestPermission", "notifications"),
        SPEECH_STATUS("speech.status", "speech"), SPEECH_VOICES("speech.voices", "speech"),
        SPEECH_SPEAK("speech.speak", "speech"), SPEECH_STOP("speech.stop", "speech"),
        VOICE_RECOGNITION_STATUS("voiceRecognition.status", "voiceRecognition"), VOICE_RECOGNITION_RECOGNIZE("voiceRecognition.recognize", "voiceRecognition"), VOICE_RECOGNITION_CANCEL("voiceRecognition.cancel", "voiceRecognition"),
        LOCATION_STATUS("location.status", "location"), LOCATION_CURRENT("location.current", "location"), LOCATION_CANCEL("location.cancel", "location"), LOCATION_REQUEST_PERMISSION("location.requestPermission", "location");
        public final String wireName;
        public final String capability;
        Method(String wireName, String capability) { this.wireName = wireName; this.capability = capability; }
    }
    public static final class Capability {
        public final String name;
        public final int minimumApi;
        public final List<Method> methods;
        // Only exact compiled permissions and package/intent visibility declarations.
        public final List<ManifestNodes.Element> manifestNodes;
        public final Set<String> permissions;
        public final Set<String> features = Collections.emptySet();
        public final Set<String> components = Collections.emptySet();
        public final Set<String> intentFilters = Collections.emptySet();
        public final Set<String> queries;
        public final Set<String> queryActions;
        public final Set<String> metadata = Collections.emptySet();
        public final Set<String> resources = Collections.emptySet();
        public final Set<String> dependencies = Collections.emptySet();
        public final Set<String> conflicts = Collections.emptySet();
        private Capability(String name) {
            this.name = name;
            boolean biometric = name.equals("biometrics");
            boolean broker = hostBrokerCapability(name);
            boolean network = name.equals("network"), notifications = name.equals("notifications"), speech = name.equals("speech"), voice = name.equals("voiceRecognition"), location = name.equals("location");
            minimumApi = biometric ? 30 : MIN_APP_API;
            permissions = biometric ? Collections.singleton(BIOMETRIC_PERMISSION) : network ? Collections.singleton(INTERNET_PERMISSION)
                    : notifications ? Collections.singleton(NOTIFICATION_PERMISSION) : location ? LOCATION_PERMISSIONS : Collections.emptySet();
            List<ManifestNodes.Element> declarations = new ArrayList<>();
            if (biometric) declarations.add(BIOMETRIC_DECLARATION);
            if (network) declarations.add(INTERNET_DECLARATION);
            if (notifications) declarations.add(NOTIFICATION_DECLARATION);
            if (location) { declarations.add(COARSE_LOCATION_DECLARATION); declarations.add(FINE_LOCATION_DECLARATION); }
            if (broker) declarations.add(speech ? SPEECH_QUERIES : voice ? VOICE_QUERIES : DOCUMENT_QUERIES);
            manifestNodes = ManifestNodes.ordered(declarations);
            queries = broker ? DOCUMENT_BROKER_PACKAGES : Collections.emptySet();
            queryActions = speech ? Collections.singleton(TTS_SERVICE_ACTION) : voice ? Collections.singleton(RECOGNIZE_SPEECH_ACTION) : Collections.emptySet();
            List<Method> selected = new ArrayList<>();
            for (Method method : Method.values()) if (name.equals(method.capability)) selected.add(method);
            methods = Collections.unmodifiableList(selected);
        }
    }
    public static final Set<String> DOCUMENT_BROKER_PACKAGES = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList("com.jarvys.agent", "com.jarvys.agent.recoverytest")));
    static final ManifestNodes.Element DOCUMENT_QUERIES = ManifestNodes.queries(Arrays.asList(
            ManifestNodes.queryPackage("com.jarvys.agent"), ManifestNodes.queryPackage("com.jarvys.agent.recoverytest")));
    static final ManifestNodes.Element BIOMETRIC_DECLARATION = ManifestNodes.permission(
            BIOMETRIC_PERMISSION, ManifestNodes.PermissionVariant.STANDARD, null);
    static final ManifestNodes.Element INTERNET_DECLARATION = ManifestNodes.permission(
            INTERNET_PERMISSION, ManifestNodes.PermissionVariant.STANDARD, null);
    static final ManifestNodes.Element NOTIFICATION_DECLARATION = ManifestNodes.permission(
            NOTIFICATION_PERMISSION, ManifestNodes.PermissionVariant.STANDARD, null);
    public static final Set<String> LOCATION_PERMISSIONS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(COARSE_LOCATION_PERMISSION, FINE_LOCATION_PERMISSION)));
    static final ManifestNodes.Element COARSE_LOCATION_DECLARATION = ManifestNodes.permission(COARSE_LOCATION_PERMISSION, ManifestNodes.PermissionVariant.STANDARD, null);
    static final ManifestNodes.Element FINE_LOCATION_DECLARATION = ManifestNodes.permission(FINE_LOCATION_PERMISSION, ManifestNodes.PermissionVariant.STANDARD, null);
    static final ManifestNodes.Element SPEECH_QUERIES, VOICE_QUERIES, SPEECH_VOICE_QUERIES;
    static {
        List<ManifestNodes.Element> queries = new ArrayList<>(DOCUMENT_QUERIES.children);
        queries.add(ManifestNodes.queryIntent(TTS_SERVICE_ACTION, null, null));
        SPEECH_QUERIES = ManifestNodes.queries(queries);
        queries.add(ManifestNodes.queryIntent(RECOGNIZE_SPEECH_ACTION, null, null));
        SPEECH_VOICE_QUERIES = ManifestNodes.queries(queries);
        queries = new ArrayList<>(DOCUMENT_QUERIES.children);
        queries.add(ManifestNodes.queryIntent(RECOGNIZE_SPEECH_ACTION, null, null));
        VOICE_QUERIES = ManifestNodes.queries(queries);
    }
    public static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList(
            "storage", "export", "share", "clipboard", "haptics", "device", "documents", "photos", "audio", "browser", "maps", "phone", "email", "sms", "contacts", "calendar", "database", "presentation", "sensors", "biometrics",
            "network", "notifications", "speech", "voiceRecognition", "location"));
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
        boolean broker = false, speech = false, voice = false;
        for (String name : select(requested)) {
            for (ManifestNodes.Element node : CAPABILITIES.get(name).manifestNodes) {
                if (node == DOCUMENT_QUERIES) broker = true;
                else if (node == SPEECH_QUERIES) { broker = true; speech = true; }
                else if (node == VOICE_QUERIES) { broker = true; voice = true; }
                else if (!result.contains(node)) result.add(node);
            }
        }
        // Exactly one queries element; speech adds its one service intent to the shared packages.
        if (broker) result.add(speech && voice ? SPEECH_VOICE_QUERIES : speech ? SPEECH_QUERIES : voice ? VOICE_QUERIES : DOCUMENT_QUERIES);
        return ManifestNodes.ordered(result);
    }
    /** Current runtime binding requirement; historical manifest profiles filter separately. */
    public static boolean requiresHostBroker(Iterable<String> requested) {
        boolean required = false;
        for (String name : select(requested)) if (hostBrokerCapability(name)) required = true;
        return required;
    }
    private static boolean hostBrokerCapability(String name) {
        return name.equals("documents") || name.equals("browser") || name.equals("maps") || name.equals("phone")
                || name.equals("email") || name.equals("sms") || name.equals("contacts") || name.equals("calendar")
                || name.equals("clipboard") || name.equals("share") || name.equals("export")
                || name.equals("network") || name.equals("notifications") || name.equals("speech") || name.equals("voiceRecognition") || name.equals("location");
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
