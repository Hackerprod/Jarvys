package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Canonical data-only SQLite archive. Package/certificate fields describe compatibility, not authenticity. */
public final class DatabaseBackupFormat {
    public static final int MAX_BYTES = 1048576, MAX_TABLES = 8;
    private DatabaseBackupFormat() { }

    public static final class Archive {
        public final String appId, certificateSha256, sha256;
        public final int version;
        public final List<String> tables;
        private final byte[] encoded;
        private final JSONObject selectedSchema, selectedData;
        private Archive(String appId, String certificate, int version, List<String> tables,
                        JSONObject schema, JSONObject data, byte[] encoded) {
            this.appId = appId; this.certificateSha256 = certificate; this.version = version;
            this.tables = Collections.unmodifiableList(new ArrayList<>(tables));
            this.selectedSchema = (JSONObject) BackupJson.copy(schema);
            this.selectedData = (JSONObject) BackupJson.copy(data);
            this.encoded = encoded.clone(); this.sha256 = DatabaseBackupFormat.sha256(encoded);
        }
        public byte[] bytes() { return encoded.clone(); }
        public JSONObject schema() { return (JSONObject) BackupJson.copy(selectedSchema); }
        public JSONObject data() { return (JSONObject) BackupJson.copy(selectedData); }
    }

    public static Archive parse(byte[] bytes) throws FactoryException {
        // Own the input before parsing so a provider/caller cannot race the canonical-byte comparison.
        if (bytes == null) throw DatabaseRequest.invalid();
        if (bytes.length > MAX_BYTES) throw BackupJson.tooLarge();
        byte[] owned = bytes.clone(); JSONObject envelope = BackupJson.object(owned);
        try {
            DatabaseBackupRequest.required(envelope, "certificateSha256", "databaseVersion", "format", "formatVersion", "packageName", "tables");
            if (!"jarvys-sqlite-data".equals(DatabaseRequest.string(envelope, "format")) ||
                    DatabaseRequest.integer(envelope, "formatVersion", 1, 1) != 1) throw DatabaseRequest.invalid();
            JSONObject tables = envelope.getJSONObject("tables"), schema = new JSONObject(), data = new JSONObject();
            for (String table : BackupJson.keys(tables)) {
                JSONObject content = tables.getJSONObject(table);
                DatabaseBackupRequest.required(content, "columns", "rows");
                schema.put(table, content.getJSONObject("columns")); data.put(table, content.getJSONArray("rows"));
            }
            Archive archive = create(DatabaseRequest.string(envelope, "packageName"), DatabaseRequest.string(envelope, "certificateSha256"),
                    DatabaseRequest.integer(envelope, "databaseVersion", 1, 1000), schema, data);
            if (!Arrays.equals(owned, archive.encoded)) throw DatabaseRequest.invalid();
            return archive;
        } catch (JSONException e) { throw DatabaseRequest.invalid(); }
    }

    public static Archive create(String appId, String certificate, int version, JSONObject selectedSchema, JSONObject selectedRows) throws FactoryException {
        if (appId == null || appId.length() > 160 || !appId.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*){1,15}") ||
                certificate == null || !certificate.matches("[a-f0-9]{64}") || version < 1 || version > 1000 ||
                selectedSchema == null || selectedRows == null || selectedSchema.length() < 1 || selectedSchema.length() > MAX_TABLES)
            throw DatabaseRequest.invalid();
        List<String> tables = BackupJson.keys(selectedSchema);
        if (!tables.equals(BackupJson.keys(selectedRows))) throw DatabaseRequest.invalid();
        try {
            JSONObject schema = new JSONObject(), data = new JSONObject(), contents = new JSONObject(); int totalRows = 0; long encodedRows = 0;
            for (String table : tables) {
                DatabaseRequest.identifier(table);
                JSONObject columns = selectedSchema.getJSONObject(table); validateColumns(columns);
                JSONObject ownedColumns = (JSONObject) BackupJson.copy(columns); schema.put(table, ownedColumns);
                JSONArray source = selectedRows.getJSONArray(table);
                if (source.length() > DatabaseRequest.MAX_ROWS - totalRows) throw DatabaseRequest.invalid();
                totalRows += source.length(); List<JSONObject> sorted = new ArrayList<>(); Set<String> ids = new HashSet<>();
                for (int i = 0; i < source.length(); i++) {
                    JSONObject row = normalizeRow(source.getJSONObject(i), ownedColumns);
                    if (!ids.add(row.getString("id"))) throw DatabaseRequest.invalid();
                    encodedRows += canonical(row).length + 1L;
                    if (encodedRows > MAX_BYTES) throw BackupJson.tooLarge();
                    sorted.add(row);
                }
                Collections.sort(sorted, Comparator.comparing(row -> row.optString("id")));
                JSONArray rows = new JSONArray(); for (JSONObject row : sorted) rows.put(row);
                data.put(table, rows); contents.put(table, new JSONObject().put("columns", ownedColumns).put("rows", rows));
            }
            JSONObject envelope = new JSONObject().put("certificateSha256", certificate).put("databaseVersion", version)
                    .put("format", "jarvys-sqlite-data").put("formatVersion", 1).put("packageName", appId).put("tables", contents);
            return new Archive(appId, certificate, version, tables, schema, data, canonical(envelope));
        } catch (JSONException e) { throw DatabaseRequest.invalid(); }
    }

    static void validateColumns(JSONObject columns) throws JSONException, FactoryException {
        // V1 archives keep their original schema envelope even when the paged database grows.
        if (columns.length() > DatabaseRequest.MAX_COLUMNS) throw DatabaseRequest.invalid();
        DatabaseRequest.columns(columns);
        for (String name : BackupJson.keys(columns)) DatabaseBackupRequest.required(columns.getJSONObject(name), "type", "nullable");
    }
    /** A full persisted row, including explicit null cells. Real columns always use the finite-double form. */
    static JSONObject normalizeRow(JSONObject source, JSONObject columns) throws JSONException, FactoryException {
        if (source.length() != columns.length() + 1 || !source.has("id")) throw DatabaseRequest.invalid();
        JSONObject row = new JSONObject().put("id", DatabaseRequest.id(DatabaseRequest.string(source, "id")));
        for (String name : BackupJson.keys(columns)) {
            if (!source.has(name)) throw DatabaseRequest.invalid();
            JSONObject definition = columns.getJSONObject(name); Object value = source.get(name);
            DatabaseRequest.typed(value, definition);
            if (value != JSONObject.NULL && "real".equals(definition.getString("type"))) {
                double real = ((Number) value).doubleValue(); value = real == 0.0 ? 0.0 : real;
            }
            row.put(name, value);
        }
        // Use the same typed database row quota, independent of JSON's defensive presentation escapes.
        if (DatabaseRequest.bytes(row) > DatabaseRequest.MAX_ROW_BYTES) throw DatabaseRequest.invalid();
        return row;
    }
    public static byte[] canonical(Object value) throws FactoryException { return BackupJson.canonical(value); }
    public static String sha256(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("Missing bytes.");
        try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable."); }
    }
    static String hex(byte[] value) {
        char[] hex = "0123456789abcdef".toCharArray(), result = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) { result[i * 2] = hex[(value[i] & 255) >>> 4]; result[i * 2 + 1] = hex[value[i] & 15]; }
        return new String(result);
    }
    public static boolean sameTables(Archive archive, List<String> tables) { return archive != null && archive.tables.equals(tables); }
}
