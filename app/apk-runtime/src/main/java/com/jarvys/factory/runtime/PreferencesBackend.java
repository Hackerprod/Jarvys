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
    @Override public Map<String, String> read() {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getValue() instanceof String) result.put(entry.getKey(), (String) entry.getValue());
        }
        return result;
    }
    @Override public boolean replace(Map<String, String> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, String> entry : values.entrySet()) editor.putString(entry.getKey(), entry.getValue());
        return editor.commit();
    }
}
