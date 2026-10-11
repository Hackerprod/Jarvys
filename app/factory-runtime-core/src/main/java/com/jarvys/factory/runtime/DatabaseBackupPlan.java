package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Immutable native-only consent plan. Archive identity labels are compatibility data, not provenance. */
public final class DatabaseBackupPlan {
    public static final int MAX_BYTES = 8192;
    public static final long LIFETIME_MILLIS = 300000L;
    public final String nonce, operation, archiveSha256, appId, certificateSha256, mode, filename,
            schemaSha256, preimageSha256;
    public final long deadline;
    public final int size, databaseVersion;
    public final List<String> tables;
    private final String encoded, counts;
    private DatabaseBackupPlan(JSONObject value) throws Exception {
        exact(value, "nonce", "deadline", "operation", "archiveSha256", "size", "appId",
                "certificateSha256", "databaseVersion", "tables", "mode", "filename", "schemaSha256", "preimageSha256", "targetRows");
        nonce = string(value, "nonce"); require(hex(nonce));
        deadline = integer(value, "deadline"); require(deadline > 0);
        operation = string(value, "operation"); require(operation.equals("export") || operation.equals("import"));
        archiveSha256 = string(value, "archiveSha256"); require(hex(archiveSha256));
        long bytes = integer(value, "size"); require(bytes > 0 && bytes <= 1024 * 1024); size = (int) bytes;
        appId = string(value, "appId"); require(appId.length() <= 160 && appId.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*){1,15}"));
        certificateSha256 = string(value, "certificateSha256"); require(hex(certificateSha256));
        long version = integer(value, "databaseVersion"); require(version >= 1 && version <= 1000); databaseVersion = (int) version;
        Object raw = value.get("tables"); require(raw instanceof JSONArray); JSONArray names = (JSONArray) raw;
        require(names.length() > 0 && names.length() <= DatabaseBackupFormat.MAX_TABLES);
        ArrayList<String> sorted = new ArrayList<>();
        for (int i = 0; i < names.length(); i++) {
            Object table = names.get(i); require(table instanceof String);
            String name = (String) table;
            DatabaseRequest.identifier(name);
            require(sorted.isEmpty() || sorted.get(sorted.size() - 1).compareTo(name) < 0); sorted.add(name);
        }
        tables = Collections.unmodifiableList(sorted);
        mode = string(value, "mode"); filename = string(value, "filename");
        schemaSha256 = string(value, "schemaSha256"); preimageSha256 = string(value, "preimageSha256"); require(hex(schemaSha256));
        Object rawCounts = value.get("targetRows"); require(rawCounts instanceof JSONObject); JSONObject rows = (JSONObject) rawCounts;
        if (operation.equals("export")) {
            require(mode.equals("export") && preimageSha256.isEmpty() && rows.length() == 0);
            require(filename.matches("[a-zA-Z0-9][a-zA-Z0-9 _.-]{0,119}") && !filename.contains("..") && !filename.endsWith("."));
        } else {
            require((mode.equals("insertOnly") || mode.equals("replaceSelectedTables")) && filename.isEmpty() && hex(preimageSha256));
            require(keys(rows).equals(new HashSet<>(tables)));
            long total = 0;
            for (String table : tables) { long count = integer(rows, table); require(count >= 0 && count <= DatabaseRequest.MAX_ROWS); total += count; require(total <= DatabaseRequest.MAX_ROWS); }
        }
        counts = new String(DatabaseBackupFormat.canonical(rows), StandardCharsets.UTF_8);
        encoded = new String(DatabaseBackupFormat.canonical(value), StandardCharsets.UTF_8);
        require(encoded.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES);
    }
    public static DatabaseBackupPlan create(String nonce, long deadline, String operation,
            DatabaseBackupFormat.Archive archive, String mode, String filename, String schemaSha256,
            String preimageSha256, JSONObject targetRows) throws FactoryException {
        try {
            require(archive != null && targetRows != null);
            List<String> tables = new ArrayList<>(archive.tables); Collections.sort(tables);
            JSONObject value = new JSONObject().put("nonce", nonce).put("deadline", deadline)
                    .put("operation", operation).put("archiveSha256", archive.sha256).put("size", archive.bytes().length)
                    .put("appId", archive.appId).put("certificateSha256", archive.certificateSha256)
                    .put("databaseVersion", archive.version).put("tables", new JSONArray(tables)).put("mode", mode)
                    .put("filename", filename).put("schemaSha256", schemaSha256).put("preimageSha256", preimageSha256)
                    .put("targetRows", StrictJson.object(new String(DatabaseBackupFormat.canonical(targetRows), StandardCharsets.UTF_8), MAX_BYTES));
            DatabaseBackupPlan result = new DatabaseBackupPlan(value); result.validateArchive(archive); return result;
        } catch (Exception invalid) { throw invalid(); }
    }
    public static DatabaseBackupPlan parse(String json) throws FactoryException {
        try {
            DatabaseBackupPlan result = new DatabaseBackupPlan(StrictJson.object(json, MAX_BYTES));
            require(result.encoded.equals(json)); return result;
        } catch (Exception invalid) { throw invalid(); }
    }
    public String json() { return encoded; }
    public String sha256() { return DatabaseBackupFormat.sha256(encoded.getBytes(StandardCharsets.UTF_8)); }
    public JSONObject targetRows() {
        try { return new JSONObject(counts); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    public void validateArchive(DatabaseBackupFormat.Archive archive) throws FactoryException {
        try {
            require(archive != null && appId.equals(archive.appId) && certificateSha256.equals(archive.certificateSha256)
                    && databaseVersion == archive.version && archiveSha256.equals(archive.sha256) && size == archive.bytes().length
                    && tables.equals(new ArrayList<>(archive.tables))
                    && schemaSha256.equals(DatabaseBackupFormat.sha256(DatabaseBackupFormat.canonical(archive.schema()))));
        } catch (Exception invalid) { throw invalid(); }
    }
    private static boolean hex(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static void require(boolean value) { if (!value) throw new IllegalArgumentException(); }
    private static String string(JSONObject value, String key) throws Exception { Object v = value.get(key); require(v instanceof String); return (String) v; }
    private static long integer(JSONObject value, String key) throws Exception { Object v = value.get(key); require(v instanceof Integer || v instanceof Long); return ((Number) v).longValue(); }
    private static Set<String> keys(JSONObject value) { Set<String> keys = new HashSet<>(); Iterator<String> i = value.keys(); while (i.hasNext()) keys.add(i.next()); return keys; }
    private static void exact(JSONObject value, String... expected) { require(keys(value).equals(new HashSet<>(Arrays.asList(expected)))); }
    private static FactoryException invalid() { return new FactoryException("INVALID_BACKUP", "Invalid native backup review plan."); }
}
