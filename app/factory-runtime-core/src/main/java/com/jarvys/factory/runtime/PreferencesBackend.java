package com.jarvys.factory.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;

/** Android app sandbox is the isolation boundary; no package identity is baked into the DEX. */
final class PreferencesBackend implements BoundedStore.Backend {
    private final SharedPreferences preferences;
    PreferencesBackend(Context context) {
        preferences = context.getSharedPreferences("factory.storage.v1." + context.getPackageName(), Context.MODE_PRIVATE);
    }
    /** Test seam for commit uncertainty; installed hosts use the package-scoped Context constructor. */
    PreferencesBackend(SharedPreferences preferences) { this.preferences = preferences; }
    @Override public String get(String key) {
        try { return preferences.getString(key, null); }
        catch (ClassCastException unsupported) { return null; }
    }
    @Override public boolean removeSelected(String key) { return preferences.edit().remove(key).commit(); }
    @Override public Map<String, String> read() {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getValue() instanceof String) result.put(entry.getKey(), (String) entry.getValue());
        }
        return result;
    }
    @Override public Map<String, String> readForBackup() throws FactoryException {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey() == null || !(entry.getValue() instanceof String))
                throw new FactoryException("STORAGE_ERROR", "Storage contains unsupported native preference entries.");
            result.put(entry.getKey(), (String) entry.getValue());
        }
        return result;
    }
    @Override public boolean putSelected(Map<String, String> values) {
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, String> entry : values.entrySet()) editor.putString(entry.getKey(), entry.getValue());
        return editor.commit();
    }
    @Override public boolean replace(Map<String, String> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, String> entry : values.entrySet()) editor.putString(entry.getKey(), entry.getValue());
        return editor.commit();
    }
}
