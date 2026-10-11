package com.jarvys.factory.runtime;

import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Canonical plaintext string preferences. Package/certificate labels are compatibility, not provenance. */
public final class StorageBackupFormat {
    public static final int MAX_BYTES = 1048576, MAX_KEYS = 32;
    private StorageBackupFormat() { }

    public static final class Archive {
        public final String appId, certificateSha256, sha256;
        public final int version;
        public final List<String> keys;
        private final byte[] encoded;
        private final JSONObject values;
        private Archive(String appId, String certificate, List<String> keys, JSONObject values, byte[] encoded) {
            this.appId = appId; this.certificateSha256 = certificate; this.version = 1;
            this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
            this.values = (JSONObject) BackupJson.copy(values); this.encoded = encoded.clone();
            this.sha256 = DatabaseBackupFormat.sha256(encoded);
        }
        public byte[] bytes() { return encoded.clone(); }
        public JSONObject data() { return (JSONObject) BackupJson.copy(values); }
    }
    public static Archive parse(byte[] bytes) throws FactoryException {
        if (bytes == null) throw StorageBackupRequest.invalid();
        if (bytes.length > MAX_BYTES) throw tooLarge();
        byte[] owned = bytes.clone();
        try {
            JSONObject envelope = BackupJson.object(owned);
            StorageBackupRequest.required(envelope, "certificateSha256", "format", "formatVersion", "packageName", "storageVersion", "values");
            if (!"jarvys-preferences-data".equals(StorageBackupRequest.string(envelope, "format"))) throw StorageBackupRequest.invalid();
            StorageBackupRequest.version(envelope, "formatVersion");
            StorageBackupRequest.version(envelope, "storageVersion");
            Object values = envelope.get("values");
            if (!(values instanceof JSONObject)) throw StorageBackupRequest.invalid();
            Archive archive = create(StorageBackupRequest.string(envelope, "packageName"),
                    StorageBackupRequest.string(envelope, "certificateSha256"), 1, (JSONObject) values);
            if (!Arrays.equals(owned, archive.encoded)) throw StorageBackupRequest.invalid();
            return archive;
        } catch (JSONException failure) { throw StorageBackupRequest.invalid(); }
        catch (FactoryException failure) { throw storageError(failure); }
    }
    public static Archive create(String appId, String certificate, int version, JSONObject selectedValues) throws FactoryException {
        if (!validAppId(appId) || certificate == null || !certificate.matches("[a-f0-9]{64}") || version != 1 ||
                selectedValues == null || selectedValues.length() < 1 || selectedValues.length() > MAX_KEYS) throw StorageBackupRequest.invalid();
        try {
            JSONObject values = new JSONObject(); List<String> keys = BackupJson.keys(selectedValues);
            for (String key : keys) {
                StorageBackupRequest.key(key);
                String value = StorageBackupRequest.string(selectedValues, key); validateValue(value); values.put(key, value);
            }
            JSONObject envelope = new JSONObject().put("certificateSha256", certificate).put("format", "jarvys-preferences-data")
                    .put("formatVersion", 1).put("packageName", appId).put("storageVersion", 1).put("values", values);
            return new Archive(appId, certificate, keys, values, canonical(envelope));
        } catch (JSONException failure) { throw StorageBackupRequest.invalid(); }
    }
    static boolean validAppId(String appId) {
        return appId != null && appId.length() <= 160 && appId.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*){1,15}");
    }
    static void validateValue(String value) throws FactoryException {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > BridgeProtocol.MAX_VALUE_BYTES) throw StorageBackupRequest.invalid();
        try { BackupJson.unicode(value); } catch (FactoryException failure) { throw StorageBackupRequest.invalid(); }
    }
    public static byte[] canonical(Object value) throws FactoryException {
        try { return BackupJson.canonical(value); } catch (FactoryException failure) { throw storageError(failure); }
    }
    public static String sha256(byte[] bytes) { return DatabaseBackupFormat.sha256(bytes); }
    public static boolean sameKeys(Archive archive, List<String> keys) { return archive != null && archive.keys.equals(keys); }
    private static FactoryException storageError(FactoryException error) {
        return "TOO_LARGE".equals(error.code) ? tooLarge() : StorageBackupRequest.invalid();
    }
    static FactoryException tooLarge() { return new FactoryException("TOO_LARGE", "Storage backup exceeds the 1 MiB limit."); }
}
