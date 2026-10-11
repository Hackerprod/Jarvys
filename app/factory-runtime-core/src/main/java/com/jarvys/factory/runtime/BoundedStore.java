package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ResourceBudgets;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Synchronous storage core. Activity runs it on a single worker, never on the UI thread. */
public final class BoundedStore {
    public static final int MAX_ENTRIES = 256;
    public static final int MAX_TOTAL_BYTES = 1048576;
    // Activities (including overlapping instances during recreation) share this transaction lock.
    // Android isolates app processes. No component in this runtime uses android:process.
    private static final Object TRANSACTION_LOCK = new Object();
    public interface Backend {
        default Object transactionLock() { return TRANSACTION_LOCK; }
        Map<String, String> read();
        boolean replace(Map<String, String> values);
        /** Backup reads must not silently discard entries with unsupported native types. */
        default Map<String, String> readForBackup() throws FactoryException { return read(); }
        /** Called under transactionLock. Production preferences override this with a non-clearing edit. */
        default boolean putSelected(Map<String, String> selected) throws FactoryException {
            Map<String, String> complete = new HashMap<>(readForBackup()); complete.putAll(selected);
            return replace(complete);
        }
    }
    private final Backend backend;
    private final ResourceBudgets.Storage budget;
    public BoundedStore(Backend backend) { this(backend, ResourceBudgets.legacy()); }
    public BoundedStore(Backend backend, ResourceBudgets budgets) {
        if (backend == null || budgets == null) throw new IllegalArgumentException();
        this.backend = backend; this.budget = budgets.storage;
    }
    public String get(String key) {
        synchronized (backend.transactionLock()) { return backend.read().get(key); }
    }
    public List<String> list() {
        synchronized (backend.transactionLock()) {
            List<String> keys = new ArrayList<>(backend.read().keySet()); Collections.sort(keys); return keys;
        }
    }
    public void set(String key, String value) throws FactoryException {
        synchronized (backend.transactionLock()) {
            if (key == null || value == null || !key.matches("[a-zA-Z0-9_.:-]{1,96}") || bytes(value) > BridgeProtocol.MAX_VALUE_BYTES)
                throw new FactoryException("INVALID_ARGUMENT", "Storage entry exceeds the supported limits.");
            Map<String, String> before = backend.read();
            Map<String, String> values = new HashMap<>(before); values.put(key, value);
            checkValueQuota(before.get(key), value); checkQuota(before, values);
            if (!backend.replace(values)) throw new FactoryException("STORAGE_ERROR", "Could not persist application data.");
        }
    }
    public void remove(String key) throws FactoryException {
        synchronized (backend.transactionLock()) {
            Map<String, String> values = new HashMap<>(backend.read()); values.remove(key);
            if (!backend.replace(values)) throw new FactoryException("STORAGE_ERROR", "Could not persist application data.");
        }
    }
    /** The preimage includes every selected key's presence, including keys absent at review time. */
    public static final class BackupBaseline {
        public final String preimageSha256;
        private final String archiveSha256;
        private final Backend owner;
        private final JSONObject selectedPresent;
        private BackupBaseline(Backend owner, String archiveSha256, String preimageSha256, JSONObject selectedPresent) {
            this.owner = owner; this.archiveSha256 = archiveSha256; this.preimageSha256 = preimageSha256;
            this.selectedPresent = (JSONObject) BackupJson.copy(selectedPresent);
        }
        public JSONObject present() { return (JSONObject) BackupJson.copy(selectedPresent); }
    }
    public StorageBackupFormat.Archive backupExport(String appId, String certificate, int version,
            List<String> keys, BooleanSupplier allowed) throws Exception {
        if (keys == null || version != 1) throw StorageBackupRequest.invalid();
        List<String> selected = StorageBackupRequest.keys(new JSONArray(keys));
        synchronized (backend.transactionLock()) {
            checkBackup(allowed); Map<String, String> current = readBackup(); JSONObject values = new JSONObject();
            for (String key : selected) {
                checkBackup(allowed);
                if (!current.containsKey(key)) throw backupConflict();
                values.put(key, current.get(key));
            }
            StorageBackupFormat.Archive archive = StorageBackupFormat.create(appId, certificate, version, values);
            checkBackup(allowed); return archive;
        }
    }
    public BackupBaseline backupBaseline(StorageBackupFormat.Archive archive, BooleanSupplier allowed) throws Exception {
        if (archive == null) throw StorageBackupRequest.invalid();
        synchronized (backend.transactionLock()) {
            checkBackup(allowed); BackupBaseline baseline = backupSnapshot(archive, readBackup(), allowed);
            checkBackup(allowed); return baseline;
        }
    }
    public JSONObject backupImport(StorageBackupFormat.Archive archive, String mode, BackupBaseline baseline,
            BooleanSupplier allowed) throws Exception {
        if (archive == null) throw StorageBackupRequest.invalid();
        StorageBackupRequest.mode(mode);
        if (baseline == null || baseline.owner != backend || !archive.sha256.equals(baseline.archiveSha256)) throw backupConflict();
        synchronized (backend.transactionLock()) {
            checkBackup(allowed); Map<String, String> current = readBackup();
            BackupBaseline actual = backupSnapshot(archive, current, allowed);
            if (!actual.preimageSha256.equals(baseline.preimageSha256)) throw backupConflict();
            JSONObject values = archive.data(); Map<String, String> selected = new HashMap<>();
            for (String key : archive.keys) {
                checkBackup(allowed);
                if ("insertOnly".equals(mode) && current.containsKey(key)) throw backupConflict();
                String value = StorageBackupRequest.string(values, key); StorageBackupFormat.validateValue(value);
                checkValueQuota(current.get(key), value); selected.put(key, value);
            }
            Map<String, String> complete = new HashMap<>(current); complete.putAll(selected); checkQuota(current, complete);
            // Build the receipt before the irreversible boundary so no serialization work can fail after persistence.
            JSONObject receipt = new JSONObject().put("version", archive.version).put("mode", mode)
                    .put("keys", new JSONArray(archive.keys)).put("count", selected.size()).put("committed", true);
            checkBackup(allowed); // Last authority check immediately before the backend mutation.
            try {
                if (!backend.putSelected(Collections.unmodifiableMap(selected))) throw outcomeUnknown();
            } catch (Exception failure) {
                // commit() may have changed the process map even when it returns false or throws. Never replay or roll back.
                throw outcomeUnknown();
            }
            // A confirmed irreversible commit is the end of effect authorization. A later revocation
            // cannot turn known persisted success into failure; response lifecycle is checked by the runtime.
            return receipt;
        }
    }
    private Map<String, String> readBackup() throws FactoryException {
        try {
            Map<?, ?> raw = backend.readForBackup(); if (raw == null) throw backupState();
            Map<String, String> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) throw backupState();
                String key = (String) entry.getKey(), value = (String) entry.getValue();
                BackupJson.unicode(key); BackupJson.unicode(value); result.put(key, value);
            }
            return result;
        } catch (Exception failure) { throw backupState(); }
    }
    private BackupBaseline backupSnapshot(StorageBackupFormat.Archive archive, Map<String, String> current,
            BooleanSupplier allowed) throws Exception {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable."); }
        digest.update("jarvys-preferences-preimage-v1\0".getBytes(StandardCharsets.US_ASCII));
        JSONObject present = new JSONObject();
        for (String key : archive.keys) {
            checkBackup(allowed); hashText(digest, key); boolean exists = current.containsKey(key);
            digest.update((byte) (exists ? 1 : 0)); present.put(key, exists);
            if (exists) hashText(digest, current.get(key));
        }
        return new BackupBaseline(backend, archive.sha256, DatabaseBackupFormat.hex(digest.digest()), present);
    }
    private static void hashText(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); int length = bytes.length;
        digest.update(new byte[] { (byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length });
        digest.update(bytes);
    }
    /** A lower signed budget never removes existing data or prevents a non-growing replacement. */
    private void checkQuota(Map<String, String> before, Map<String, String> after) throws FactoryException {
        if (after.size() > budget.entries && after.size() > before.size())
            throw new FactoryException("QUOTA_EXCEEDED", "Storage entry limit reached.");
        long next = totalBytes(after);
        if (next > budget.totalBytes && next > totalBytes(before))
            throw new FactoryException("QUOTA_EXCEEDED", "Application storage limit reached.");
    }
    private void checkValueQuota(String before, String after) throws FactoryException {
        int next = bytes(after);
        if (next > budget.valueBytes && (before == null || next > bytes(before)))
            throw new FactoryException("QUOTA_EXCEEDED", "Storage value limit reached.");
    }
    private static long totalBytes(Map<String, String> values) throws FactoryException {
        long total = 0;
        try {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                total = Math.addExact(total, (long) bytes(entry.getKey()));
                total = Math.addExact(total, (long) bytes(entry.getValue()));
            }
        } catch (ArithmeticException overflow) {
            throw new FactoryException("QUOTA_EXCEEDED", "Application storage limit reached.");
        }
        return total;
    }
    private static void checkBackup(BooleanSupplier allowed) throws FactoryException {
        boolean current = false;
        try { current = allowed != null && allowed.getAsBoolean(); } catch (RuntimeException failure) { /* Fail closed. */ }
        if (!current || Thread.currentThread().isInterrupted())
            throw new FactoryException("CANCELLED", "Storage backup operation cancelled. A prior write is not undone; inspect state before retrying.");
    }
    private static FactoryException backupConflict() {
        return new FactoryException("BACKUP_CONFLICT", "A selected storage key is missing, changed or already exists; review a fresh backup plan.");
    }
    private static FactoryException backupState() {
        return new FactoryException("STORAGE_ERROR", "Storage contains unsupported entries or could not be read safely.");
    }
    private static FactoryException outcomeUnknown() {
        return new FactoryException("OUTCOME_UNKNOWN", "Storage persistence was not confirmed. Values may have changed; inspect state before retrying.");
    }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
}
