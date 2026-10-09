package com.jarvys.factory.runtime;

import java.util.HashMap;
import java.util.Map;

/** One instance per preview/test session. Never touches preferences or disk. */
public final class MemoryBackend implements BoundedStore.Backend {
    private final Map<String, String> values = new HashMap<>();
    @Override public synchronized Map<String, String> read() { return new HashMap<>(values); }
    @Override public synchronized boolean replace(Map<String, String> replacement) {
        values.clear(); values.putAll(replacement); return true;
    }
    public synchronized void clear() { values.clear(); }
}
