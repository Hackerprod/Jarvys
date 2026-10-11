package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable native consent plan, bound to exact archive bytes and selected-key target preimage. */
public final class StorageBackupPlan {
    public static final int MAX_BYTES = 8192;
    public static final long LIFETIME_MILLIS = 300000L;
    public final String nonce, operation, archiveSha256, appId, certificateSha256, mode, filename, preimageSha256;
    public final long deadline;
    public final int size, storageVersion;
    public final List<String> keys;
    private final String encoded, present;
    private StorageBackupPlan(JSONObject value) throws Exception {
        StorageBackupRequest.required(value, "nonce", "deadline", "operation", "archiveSha256", "size", "appId",
                "certificateSha256", "storageVersion", "keys", "mode", "filename", "preimageSha256", "targetPresent");
        nonce = string(value, "nonce"); require(hex(nonce));
        deadline = integer(value, "deadline"); require(deadline > 0);
        operation = string(value, "operation"); require(operation.equals("export") || operation.equals("import"));
        archiveSha256 = string(value, "archiveSha256"); require(hex(archiveSha256));
        long bytes = integer(value, "size"); require(bytes > 0 && bytes <= StorageBackupFormat.MAX_BYTES); size = (int) bytes;
        appId = string(value, "appId"); require(StorageBackupFormat.validAppId(appId));
        certificateSha256 = string(value, "certificateSha256"); require(hex(certificateSha256));
        storageVersion = StorageBackupRequest.version(value, "storageVersion");
        Object selected = value.get("keys"); require(selected instanceof JSONArray);
        keys = Collections.unmodifiableList(StorageBackupRequest.keys((JSONArray) selected));
        mode = string(value, "mode"); filename = string(value, "filename"); preimageSha256 = string(value, "preimageSha256");
        Object rawPresent = value.get("targetPresent"); require(rawPresent instanceof JSONObject); JSONObject target = (JSONObject) rawPresent;
        if (operation.equals("export")) {
            require(mode.equals("export") && preimageSha256.isEmpty() && target.length() == 0); StorageBackupRequest.filename(filename);
        } else {
            StorageBackupRequest.mode(mode); require(filename.isEmpty() && hex(preimageSha256));
            require(BackupJson.keys(target).equals(keys));
            for (String key : keys) require(target.get(key) instanceof Boolean);
        }
        present = new String(StorageBackupFormat.canonical(target), StandardCharsets.UTF_8);
        encoded = new String(StorageBackupFormat.canonical(value), StandardCharsets.UTF_8);
        require(encoded.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES);
    }
    public static StorageBackupPlan create(String nonce, long deadline, String operation, StorageBackupFormat.Archive archive,
            String mode, String filename, String preimageSha256, JSONObject targetPresent) throws FactoryException {
        try {
            require(archive != null && targetPresent != null);
            JSONObject value = new JSONObject().put("nonce", nonce).put("deadline", deadline).put("operation", operation)
                    .put("archiveSha256", archive.sha256).put("size", archive.bytes().length).put("appId", archive.appId)
                    .put("certificateSha256", archive.certificateSha256).put("storageVersion", archive.version)
                    .put("keys", new JSONArray(archive.keys)).put("mode", mode).put("filename", filename)
                    .put("preimageSha256", preimageSha256).put("targetPresent", BackupJson.copy(targetPresent));
            StorageBackupPlan result = new StorageBackupPlan(value); result.validateArchive(archive); return result;
        } catch (Exception failure) { throw invalid(); }
    }
    public static StorageBackupPlan parse(String json) throws FactoryException {
        try {
            StorageBackupPlan result = new StorageBackupPlan(StrictJson.object(json, MAX_BYTES));
            require(result.encoded.equals(json)); return result;
        } catch (Exception failure) { throw invalid(); }
    }
    public String json() { return encoded; }
    public String sha256() { return StorageBackupFormat.sha256(encoded.getBytes(StandardCharsets.UTF_8)); }
    public JSONObject targetPresent() {
        try { return new JSONObject(present); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    public void validateArchive(StorageBackupFormat.Archive archive) throws FactoryException {
        try {
            require(archive != null && appId.equals(archive.appId) && certificateSha256.equals(archive.certificateSha256)
                    && storageVersion == archive.version && archiveSha256.equals(archive.sha256) && size == archive.bytes().length
                    && keys.equals(new ArrayList<>(archive.keys)));
        } catch (Exception failure) { throw invalid(); }
    }
    private static String string(JSONObject object, String name) throws Exception { return StorageBackupRequest.string(object, name); }
    private static long integer(JSONObject object, String name) throws Exception {
        Object value = object.get(name); require(value instanceof Integer || value instanceof Long); return ((Number) value).longValue();
    }
    private static boolean hex(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static void require(boolean value) { if (!value) throw new IllegalArgumentException(); }
    private static FactoryException invalid() { return new FactoryException("INVALID_BACKUP", "Invalid native storage backup review plan."); }
}
