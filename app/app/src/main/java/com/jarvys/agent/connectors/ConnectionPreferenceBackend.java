package com.jarvys.agent.connectors;

import android.content.SharedPreferences;
import android.os.Looper;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Narrow persistence seam. A false/throwing commit may already have changed the memory image. */
interface ConnectionPreferenceBackend {
    Map<String, ?> snapshot();
    boolean commit(Map<String, Object> writes, Set<String> removals);

    /** Not wired into ConnectorStateStore: used only by the staged connection storage owner. */
    final class Android implements ConnectionPreferenceBackend {
        private final SharedPreferences preferences;
        Android(SharedPreferences preferences) { this.preferences = preferences; }
        private static void requireWorker() {
            if (Looper.myLooper() == Looper.getMainLooper())
                throw new IllegalStateException("Connection persistence requires a worker thread");
        }
        @Override public Map<String, ?> snapshot() {
            requireWorker();
            return new HashMap<>(preferences.getAll());
        }
        @Override public boolean commit(Map<String, Object> writes, Set<String> removals) {
            requireWorker();
            SharedPreferences.Editor editor = preferences.edit();
            for (String key : removals) {
                if (writes.containsKey(key)) throw new IllegalArgumentException("Conflicting preference patch");
                editor.remove(key);
            }
            for (Map.Entry<String, Object> entry : writes.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) editor.putString(entry.getKey(), (String) value);
                else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
                else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                else throw new IllegalArgumentException("Unsupported connection preference type");
            }
            return editor.commit();
        }
    }
}
