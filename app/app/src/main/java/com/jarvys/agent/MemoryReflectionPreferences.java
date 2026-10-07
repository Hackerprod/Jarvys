package com.jarvys.agent;

import android.content.Context;
import android.content.SharedPreferences;

/** On-device reflection toggle, disclosure, status, and failure recovery state. */
public final class MemoryReflectionPreferences {
    public static final boolean DEFAULT_ENABLED = true;
    private static final String PREFS = "jarvys_memory_reflection";
    private final SharedPreferences preferences;
    private final Context context;

    public MemoryReflectionPreferences(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        removeLegacyNumericControls();
    }

    public boolean enabled() { return preferences.getBoolean("enabled", DEFAULT_ENABLED); }
    public void setEnabled(boolean enabled) { preferences.edit().putBoolean("enabled", enabled).apply(); }
    public boolean disclosureShown() { return preferences.getBoolean("disclosure_shown", false); }
    public void markDisclosureShown() { preferences.edit().putBoolean("disclosure_shown", true).apply(); }

    /** Records the active run without a per-session interval or application-wide daily reservation. */
    public synchronized void markStarted(String sessionId) {
        String key = sessionKey(sessionId);
        preferences.edit()
                .putString(key + "_status", MemoryReflectionStatus.REFLECTING)
                .putString("global_status", MemoryReflectionStatus.REFLECTING)
                .putString("active_session", sessionId)
                .commit();
    }

    public synchronized boolean failureBackoffElapsed(String sessionId, long nowMillis) {
        return MemoryReflectionPolicy.failureBackoffElapsed(
                nowMillis, preferences.getLong(sessionKey(sessionId) + "_backoff_until", 0L));
    }

    public synchronized void succeeded(String sessionId, long nowMillis, String result) {
        String key = sessionKey(sessionId);
        preferences.edit()
                .putLong(key + "_last_success", nowMillis)
                .putLong("global_last_success", nowMillis)
                .remove("active_session")
                .putInt(key + "_failures", 0)
                .putLong(key + "_backoff_until", 0L)
                .putString(key + "_status", result == null || result.trim().isEmpty()
                        ? MemoryReflectionStatus.NO_CHANGES : MemoryReflectionStatus.code(context, result))
                .putString("global_status", result == null || result.trim().isEmpty()
                        ? MemoryReflectionStatus.NO_CHANGES : MemoryReflectionStatus.code(context, result))
                .commit();
    }

    public synchronized void failed(String sessionId, long nowMillis, String result) {
        String key = sessionKey(sessionId);
        int failures = Math.min(10, preferences.getInt(key + "_failures", 0) + 1);
        long delay = MemoryReflectionPolicy.failureBackoffMillis(failures);
        preferences.edit()
                .putInt(key + "_failures", failures)
                .putLong(key + "_backoff_until", nowMillis + delay)
                .remove("active_session")
                .putString(key + "_status", MemoryReflectionStatus.FAILED_RETRY)
                .putString("global_status", MemoryReflectionStatus.FAILED_RETRY)
                .commit();
    }

    public synchronized void cancelled(String sessionId, String status) {
        String safe = MemoryReflectionStatus.code(context, status);
        preferences.edit().putString(sessionKey(sessionId) + "_status", safe).putString("global_status", safe)
                .remove("active_session").commit();
    }

    public String status(String sessionId) { return readStatus(sessionKey(sessionId) + "_status"); }
    public void setStatus(String sessionId, String status) {
        String safe = MemoryReflectionStatus.code(context, status);
        preferences.edit().putString(sessionKey(sessionId) + "_status", safe).putString("global_status", safe).apply();
    }
    public void recoverInterrupted(String sessionId, long nowMillis, String message) {
        if (MemoryReflectionStatus.REFLECTING.equals(status(sessionId))) failed(sessionId, nowMillis, message);
    }
    public synchronized void recoverGlobalInterrupted(long nowMillis, String message) {
        if (!MemoryReflectionStatus.REFLECTING.equals(readStatus("global_status"))) return;
        String sessionId = preferences.getString("active_session", "");
        if (sessionId != null && !sessionId.isEmpty()) recoverInterrupted(sessionId, nowMillis, message);
    }
    public long lastSuccess(String sessionId) { return preferences.getLong(sessionKey(sessionId) + "_last_success", 0L); }
    public String globalStatus() { return readStatus("global_status"); }
    public String localizedStatus(String status) {
        return MemoryReflectionStatus.localized(AppLanguageRuntime.localizedContext(context), status);
    }
    public long globalLastSuccess() { return preferences.getLong("global_last_success", 0L); }

    static String sessionKey(String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_.-]{1,100}")) {
            throw new IllegalArgumentException("Invalid reflection session id");
        }
        return "session_" + sessionId;
    }

    private void removeLegacyNumericControls() {
        SharedPreferences.Editor editor = preferences.edit()
                .remove("step_count")
                .remove("minimum_interval_minutes")
                .remove("max_per_day")
                .remove("daily_day")
                .remove("daily_count");
        if ("step-count".equals(preferences.getString("trigger", ""))) {
            editor.putString("trigger", "compaction-event");
        }
        preferences.getAll().keySet().stream()
                .filter(key -> key.startsWith("session_")
                        && (key.endsWith("_last_start") || key.endsWith("_last_attempt")))
                .forEach(editor::remove);
        editor.apply();
    }

    private String readStatus(String key) {
        String stored = preferences.getString(key, "");
        String stable = MemoryReflectionStatus.code(context, stored);
        if (!stable.equals(stored)) preferences.edit().putString(key, stable).apply();
        return stable;
    }
}
