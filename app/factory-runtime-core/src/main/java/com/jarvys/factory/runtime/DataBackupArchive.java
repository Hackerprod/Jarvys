package com.jarvys.factory.runtime;

import org.json.JSONObject;
import java.util.List;

/** Closed data-kind adapter. Original SQLite archives are never rewritten or reinterpreted. */
public final class DataBackupArchive {
    public final DatabaseBackupFormat.Archive database;
    public final StorageBackupFormat.Archive storage;
    public final String appId, certificateSha256, sha256;
    public final int version;
    private DataBackupArchive(DatabaseBackupFormat.Archive database, StorageBackupFormat.Archive storage) {
        if ((database == null) == (storage == null)) throw new IllegalArgumentException();
        this.database = database; this.storage = storage;
        appId = database != null ? database.appId : storage.appId;
        certificateSha256 = database != null ? database.certificateSha256 : storage.certificateSha256;
        sha256 = database != null ? database.sha256 : storage.sha256;
        version = database != null ? database.version : storage.version;
    }
    public static DataBackupArchive of(DatabaseBackupFormat.Archive archive) { return new DataBackupArchive(archive, null); }
    public static DataBackupArchive of(StorageBackupFormat.Archive archive) { return new DataBackupArchive(null, archive); }
    public boolean isStorage() { return storage != null; }
    public List<String> selectedNames() { return isStorage() ? storage.keys : database.tables; }
    public byte[] bytes() { return isStorage() ? storage.bytes() : database.bytes(); }
    public JSONObject data() { return isStorage() ? storage.data() : database.data(); }
    public static DataBackupArchive parse(byte[] bytes) throws FactoryException {
        if (bytes == null) throw DatabaseRequest.invalid();
        if (bytes.length > DatabaseBackupFormat.MAX_BYTES) throw BackupJson.tooLarge();
        byte[] owned = bytes.clone();
        try {
            JSONObject envelope = BackupJson.object(owned);
            String format = DatabaseRequest.string(envelope, "format");
            if ("jarvys-sqlite-data".equals(format)) return of(DatabaseBackupFormat.parse(owned));
            if ("jarvys-preferences-data".equals(format)) return of(StorageBackupFormat.parse(owned));
            throw DatabaseRequest.invalid();
        } catch (org.json.JSONException invalid) { throw DatabaseRequest.invalid(); }
        finally { java.util.Arrays.fill(owned, (byte) 0); }
    }
}
