package com.jarvys.factory.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable per-artifact capacity declaration. It grants no capability or operation permission.
 *
 * Missing declarations use {@link #legacy()}; explicit v1 declarations may override its fields.
 * Serialize {@link #toMap()} into the signed artifact to freeze all resolved values. Neither
 * defaults nor ceilings are derived from capabilities, device state, clocks or mutable settings.
 *
 * Whole-payload byte ceilings are not resident-heap measurements: UTF-16, JSON/map objects and
 * defensive copies use additional memory. Large database/document totals are disk/stream totals,
 * not allocation sizes. Adapters must still enforce the fixed envelope, buffer and worker limits,
 * actual free resources, live authorization and transactional accounting at effect boundaries.
 */
public final class ResourceBudgets {
    public static final int VERSION = 1;
    private static final long KIB = 1024L, MIB = 1024L * KIB, GIB = 1024L * MIB;
    private static final String[] SECTIONS = {"storage", "database", "documents", "audio", "sensors", "packaging"};

    /** Fixed allocation/transport ceilings; these are not developer-editable policy settings. */
    public static final int BRIDGE_MESSAGE_BYTES = 512 * 1024;
    public static final int DOCUMENT_CHUNK_BYTES = 32 * 1024;
    public static final int DATABASE_REQUEST_BYTES = 256 * 1024;
    public static final int DATABASE_RESPONSE_BYTES = 256 * 1024;
    public static final int DATABASE_SCHEMA_BYTES = 64 * 1024;
    public static final int SENSOR_RESULT_BYTES = 16 * 1024;
    public static final int GLOBAL_DOCUMENT_SLOTS = 128;

    private enum Field {
        STORAGE_TOTAL("storage", "totalBytes", MIB, 1, MIB),
        STORAGE_ENTRIES("storage", "entries", 256, 1, 256),
        STORAGE_VALUE("storage", "valueBytes", 64 * KIB, 1, 64 * KIB),
        DATABASE_MAIN("database", "mainFileBytes", 16 * MIB, 4096, GIB),
        DATABASE_ROWS("database", "rows", 10000, 1, 1000000),
        // At most 512 bounded column definitions keep the closed schema below 64 KiB.
        DATABASE_TABLES("database", "tables", 8, 1, 16),
        DATABASE_COLUMNS("database", "columnsPerTable", 16, 1, 32),
        DATABASE_CELL("database", "cellBytes", 4096, 1, 4096),
        DATABASE_ROW("database", "rowBytes", 16384, 1, 16384),
        DATABASE_PAGE("database", "selectPageRows", 100, 1, 100),
        DATABASE_BATCH("database", "batchOperations", 64, 1, 64),
        DOCUMENT_HANDLES("documents", "openHandles", 4, 1, 16),
        DOCUMENT_HANDLE_BYTES("documents", "handleBytes", 16 * MIB, 1, 4 * GIB),
        DOCUMENT_SESSION_BYTES("documents", "sessionBytes", 32 * MIB, 1, 16 * GIB),
        DOCUMENT_LEASE("documents", "leaseMs", 300000, 1, 86400000),
        AUDIO_BYTES("audio", "sourceBytes", 6 * MIB, 46, 6 * MIB),
        // Longer low-rate PCM fits the same existing 6 MiB whole-payload ceiling.
        AUDIO_DURATION("audio", "durationMs", 30000, 1, 300000),
        SENSOR_DURATION("sensors", "captureDurationMs", 5000, 100, 300000),
        SENSOR_SAMPLES("sensors", "captureSamples", 50, 1, 50),
        SENSOR_INTERVAL("sensors", "minimumIntervalUs", 100000, 0, 1000000),
        // V1 materializes a capture array. A streaming-ring adapter is required to raise this.
        SENSOR_BUFFER("sensors", "bufferedSamples", 50, 1, 50),
        PACKAGE_FILES("packaging", "sourceFiles", 128, 1, 128),
        PACKAGE_TOTAL("packaging", "totalSourceBytes", 8 * MIB, 1, 8 * MIB),
        PACKAGE_SINGLE("packaging", "singleSourceBytes", MIB, 1, MIB),
        PACKAGE_APK("packaging", "apkBytes", 32 * MIB, 1, 32 * MIB);

        final String section, name;
        final long legacy, minimum, maximum;
        Field(String section, String name, long legacy, long minimum, long maximum) {
            this.section = section; this.name = name; this.legacy = legacy;
            this.minimum = minimum; this.maximum = maximum;
        }
    }

    public static final class Storage {
        public final long totalBytes;
        public final int entries, valueBytes;
        private Storage(long[] values) {
            totalBytes = value(values, Field.STORAGE_TOTAL);
            entries = count(values, Field.STORAGE_ENTRIES); valueBytes = count(values, Field.STORAGE_VALUE);
        }
    }
    public static final class Database {
        public final long mainFileBytes, rows;
        public final int tables, columnsPerTable, cellBytes, rowBytes, selectPageRows, batchOperations;
        private Database(long[] values) {
            mainFileBytes = value(values, Field.DATABASE_MAIN); rows = value(values, Field.DATABASE_ROWS);
            tables = count(values, Field.DATABASE_TABLES); columnsPerTable = count(values, Field.DATABASE_COLUMNS);
            cellBytes = count(values, Field.DATABASE_CELL); rowBytes = count(values, Field.DATABASE_ROW);
            selectPageRows = count(values, Field.DATABASE_PAGE); batchOperations = count(values, Field.DATABASE_BATCH);
        }
    }
    public static final class Documents {
        public final int openHandles;
        public final long handleBytes, sessionBytes, leaseMs;
        private Documents(long[] values) {
            openHandles = count(values, Field.DOCUMENT_HANDLES); handleBytes = value(values, Field.DOCUMENT_HANDLE_BYTES);
            sessionBytes = value(values, Field.DOCUMENT_SESSION_BYTES); leaseMs = value(values, Field.DOCUMENT_LEASE);
        }
    }
    public static final class Audio {
        public final long sourceBytes, durationMs;
        private Audio(long[] values) { sourceBytes = value(values, Field.AUDIO_BYTES); durationMs = value(values, Field.AUDIO_DURATION); }
    }
    public static final class Sensors {
        public final long captureDurationMs;
        public final int captureSamples, minimumIntervalUs, bufferedSamples;
        private Sensors(long[] values) {
            captureDurationMs = value(values, Field.SENSOR_DURATION); captureSamples = count(values, Field.SENSOR_SAMPLES);
            minimumIntervalUs = count(values, Field.SENSOR_INTERVAL); bufferedSamples = count(values, Field.SENSOR_BUFFER);
        }
    }
    public static final class Packaging {
        public final int sourceFiles;
        public final long totalSourceBytes, singleSourceBytes, apkBytes;
        private Packaging(long[] values) {
            sourceFiles = count(values, Field.PACKAGE_FILES); totalSourceBytes = value(values, Field.PACKAGE_TOTAL);
            singleSourceBytes = value(values, Field.PACKAGE_SINGLE); apkBytes = value(values, Field.PACKAGE_APK);
        }
    }

    private static final ResourceBudgets LEGACY = new ResourceBudgets(initial(false));
    private static final ResourceBudgets MAXIMA = new ResourceBudgets(initial(true));
    public final Storage storage;
    public final Database database;
    public final Documents documents;
    public final Audio audio;
    public final Sensors sensors;
    public final Packaging packaging;
    private final Map<String, Object> canonical;

    private ResourceBudgets(long[] values) {
        storage = new Storage(values); database = new Database(values); documents = new Documents(values);
        audio = new Audio(values); sensors = new Sensors(values); packaging = new Packaging(values);
        require(storage.valueBytes <= storage.totalBytes, "storage.valueBytes exceeds totalBytes");
        require(database.cellBytes <= database.rowBytes, "database.cellBytes exceeds rowBytes");
        require(database.mainFileBytes % 4096 == 0, "database.mainFileBytes must be a multiple of 4096");
        require(documents.handleBytes <= documents.sessionBytes, "documents.handleBytes exceeds sessionBytes");
        require(sensors.captureSamples <= sensors.bufferedSamples, "sensors.captureSamples exceeds bufferedSamples");
        require(packaging.singleSourceBytes <= packaging.totalSourceBytes, "packaging.singleSourceBytes exceeds totalSourceBytes");
        require(packaging.totalSourceBytes <= packaging.apkBytes, "packaging.totalSourceBytes exceeds apkBytes");
        LinkedHashMap<String, Object> root = new LinkedHashMap<>(); root.put("version", VERSION);
        for (String section : SECTIONS) {
            LinkedHashMap<String, Object> members = new LinkedHashMap<>();
            for (Field field : Field.values()) if (section.equals(field.section)) members.put(field.name, values[field.ordinal()]);
            root.put(section, Collections.unmodifiableMap(members));
        }
        canonical = Collections.unmodifiableMap(root);
    }

    /** Exact historical limits, independent of later scaffolding choices or current device memory. */
    public static ResourceBudgets legacy() { return LEGACY; }
    /** Largest declaration supported by the v1 adapters, not a guarantee of available resources. */
    public static ResourceBudgets maxima() { return MAXIMA; }

    /**
     * Parse a versioned partial declaration. Only Integer/Long JSON numbers are accepted, without
     * coercion or clamping. The result's complete ordered map freezes every resolved default.
     * The host/runtime JSON adapters own JSON parsing; this module has no JSON or Android dependency.
     */
    public static ResourceBudgets parse(Map<?, ?> declaration) {
        require(declaration != null, "Missing resourceBudgets declaration");
        require(declaration.get("version") instanceof Integer && ((Integer) declaration.get("version")) == VERSION,
                "Unsupported resourceBudgets version");
        for (Object key : declaration.keySet()) require("version".equals(key) || section(key), "Unknown resourceBudgets section");
        long[] values = initial(false);
        for (String section : SECTIONS) {
            if (!declaration.containsKey(section)) continue;
            Object raw = declaration.get(section);
            require(raw instanceof Map, "resourceBudgets." + section + " must be an object");
            Map<?, ?> members = (Map<?, ?>) raw;
            for (Map.Entry<?, ?> entry : members.entrySet()) {
                Field field = find(section, entry.getKey());
                require(field != null, "Unknown resourceBudgets." + section + " field");
                Object number = entry.getValue();
                require(number instanceof Integer || number instanceof Long, "resourceBudgets values must be integers");
                long value = ((Number) number).longValue();
                require(value >= field.minimum && value <= field.maximum,
                        "resourceBudgets." + section + "." + field.name + " exceeds implementation bounds");
                values[field.ordinal()] = value;
            }
        }
        return new ResourceBudgets(values);
    }

    /** Deeply immutable; root/section insertion order is fixed, regardless of input map order. */
    public Map<String, Object> toMap() { return canonical; }

    /** Deterministic non-JSON representation for diagnostics or domain-separated binding. */
    public String canonicalText() {
        StringBuilder text = new StringBuilder("jarvys-resource-budgets-v1\n");
        for (Field field : Field.values()) {
            Map<?, ?> members = (Map<?, ?>) canonical.get(field.section);
            text.append(field.section).append('.').append(field.name).append('=').append(members.get(field.name)).append('\n');
        }
        return text.toString();
    }

    /** Envelope/working-buffer metadata. Whole-payload limits do not describe total heap usage. */
    public static Map<String, Long> implementationLimits() {
        LinkedHashMap<String, Long> values = new LinkedHashMap<>();
        values.put("bridgeMessageBytes", (long) BRIDGE_MESSAGE_BYTES);
        values.put("documentChunkBytes", (long) DOCUMENT_CHUNK_BYTES);
        values.put("databaseRequestBytes", (long) DATABASE_REQUEST_BYTES);
        values.put("databaseResponseBytes", (long) DATABASE_RESPONSE_BYTES);
        values.put("databaseSchemaBytes", (long) DATABASE_SCHEMA_BYTES);
        values.put("sensorResultBytes", (long) SENSOR_RESULT_BYTES);
        values.put("globalDocumentSlots", (long) GLOBAL_DOCUMENT_SLOTS);
        values.put("wholeStorePayloadBytes", MIB); values.put("wholeAudioPayloadBytes", 6 * MIB);
        values.put("wholeWebsitePayloadBytes", 8 * MIB); values.put("wholeApkPayloadBytes", 32 * MIB);
        return Collections.unmodifiableMap(values);
    }

    @Override public boolean equals(Object other) {
        return other instanceof ResourceBudgets && canonical.equals(((ResourceBudgets) other).canonical);
    }
    @Override public int hashCode() { return canonical.hashCode(); }

    private static long[] initial(boolean maximum) {
        long[] values = new long[Field.values().length];
        for (Field field : Field.values()) values[field.ordinal()] = maximum ? field.maximum : field.legacy;
        return values;
    }
    private static long value(long[] values, Field field) { return values[field.ordinal()]; }
    private static int count(long[] values, Field field) { return Math.toIntExact(value(values, field)); }
    private static boolean section(Object key) {
        for (String section : SECTIONS) if (section.equals(key)) return true;
        return false;
    }
    private static Field find(String section, Object key) {
        for (Field field : Field.values()) if (field.section.equals(section) && field.name.equals(key)) return field;
        return null;
    }
    private static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
}
