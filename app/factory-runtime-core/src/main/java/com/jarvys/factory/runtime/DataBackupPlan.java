package com.jarvys.factory.runtime;

import org.json.JSONObject;
import java.util.Collections;
import java.util.List;

/** Closed typed review-plan adapter. Legacy SQLite JSON and hashes remain byte-for-byte intact. */
public final class DataBackupPlan {
    public final DatabaseBackupPlan database;
    public final StorageBackupPlan storage;
    public final String nonce, operation, archiveSha256, appId, certificateSha256, mode, filename, preimageSha256;
    public final long deadline;
    public final int size;
    public final List<String> tables, keys;
    private DataBackupPlan(DatabaseBackupPlan database, StorageBackupPlan storage) {
        if ((database == null) == (storage == null)) throw new IllegalArgumentException();
        this.database = database; this.storage = storage;
        nonce = database != null ? database.nonce : storage.nonce;
        operation = database != null ? database.operation : storage.operation;
        archiveSha256 = database != null ? database.archiveSha256 : storage.archiveSha256;
        appId = database != null ? database.appId : storage.appId;
        certificateSha256 = database != null ? database.certificateSha256 : storage.certificateSha256;
        mode = database != null ? database.mode : storage.mode;
        filename = database != null ? database.filename : storage.filename;
        preimageSha256 = database != null ? database.preimageSha256 : storage.preimageSha256;
        deadline = database != null ? database.deadline : storage.deadline;
        size = database != null ? database.size : storage.size;
        tables = database != null ? database.tables : Collections.emptyList();
        keys = storage != null ? storage.keys : Collections.emptyList();
    }
    public static DataBackupPlan of(DatabaseBackupPlan plan) { return new DataBackupPlan(plan, null); }
    public static DataBackupPlan of(StorageBackupPlan plan) { return new DataBackupPlan(null, plan); }
    public boolean isStorage() { return storage != null; }
    public List<String> selectedNames() { return isStorage() ? keys : tables; }
    public String capability() { return isStorage() ? "storage" : "database"; }
    public String json() { return isStorage() ? storage.json() : database.json(); }
    public String sha256() { return isStorage() ? storage.sha256() : database.sha256(); }
    public JSONObject targetRows() { return database == null ? new JSONObject() : database.targetRows(); }
    public void validateArchive(DataBackupArchive archive) throws FactoryException {
        if (archive == null || isStorage() != archive.isStorage())
            throw DatabaseRequest.invalid("dataBackup.archive must match the selected database or storage backup plan");
        if (isStorage()) storage.validateArchive(archive.storage); else database.validateArchive(archive.database);
    }
    public static DataBackupPlan parse(String text) throws FactoryException {
        JSONObject value = StrictJson.object(text, DatabaseBackupPlan.MAX_BYTES);
        // Disjoint required fields choose the parser; its exact-field checks reject hybrids.
        if (value.has("storageVersion") && !value.has("databaseVersion")) return of(StorageBackupPlan.parse(text));
        if (value.has("databaseVersion") && !value.has("storageVersion")) return of(DatabaseBackupPlan.parse(text));
        throw DatabaseRequest.invalid("dataBackup.plan must contain exactly one databaseVersion or storageVersion field");
    }
}
