package com.jarvys.factory.runtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    }
    private final Backend backend;
    public BoundedStore(Backend backend) { this.backend = backend; }
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
            if (!key.matches("[a-zA-Z0-9_.:-]{1,96}") || bytes(value) > BridgeProtocol.MAX_VALUE_BYTES)
                throw new FactoryException("INVALID_ARGUMENT", "Storage entry exceeds the supported limits.");
            Map<String, String> values = new HashMap<>(backend.read()); values.put(key, value);
            if (values.size() > MAX_ENTRIES) throw new FactoryException("QUOTA_EXCEEDED", "Storage entry limit reached.");
            long total = 0;
            for (Map.Entry<String, String> entry : values.entrySet()) total += bytes(entry.getKey()) + bytes(entry.getValue());
            if (total > MAX_TOTAL_BYTES) throw new FactoryException("QUOTA_EXCEEDED", "Application storage limit reached.");
            if (!backend.replace(values)) throw new FactoryException("STORAGE_ERROR", "Could not persist application data.");
        }
    }
    public void remove(String key) throws FactoryException {
        synchronized (backend.transactionLock()) {
            Map<String, String> values = new HashMap<>(backend.read()); values.remove(key);
            if (!backend.replace(values)) throw new FactoryException("STORAGE_ERROR", "Could not persist application data.");
        }
    }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
}
