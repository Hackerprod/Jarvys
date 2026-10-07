package com.jarvys.agent;

import android.content.Context;

/** Stable persisted reflection-state codes and app-language presentation. Unknown values are free-form model results. */
public final class MemoryReflectionStatus {
    public static final String REFLECTING = "REFLECTING";
    public static final String NO_CHANGES = "NO_CHANGES";
    public static final String FAILED_RETRY = "FAILED_RETRY";
    public static final String INTERRUPTED = "INTERRUPTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String PARTIAL = "PARTIAL";
    public static final String PRIVACY_REQUIRED = "PRIVACY_REQUIRED";
    public static final String MEMORY_DISABLED = "MEMORY_DISABLED";
    public static final String CHAT_DISABLED = "CHAT_DISABLED";
    public static final String AUTO_OFF = "AUTO_OFF";

    private MemoryReflectionStatus() { }

    public static String code(Context context, String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String normalized = value.trim();
        if (is(normalized, REFLECTING, "reflecting", context.getString(R.string.reflection_status_running))) return REFLECTING;
        if (is(normalized, NO_CHANGES, "No changes worth remembering",
                context.getString(R.string.reflection_status_no_changes))) return NO_CHANGES;
        if (is(normalized, FAILED_RETRY, "Reflection failed; will retry later",
                context.getString(R.string.reflection_failed_backoff))) return FAILED_RETRY;
        if (is(normalized, INTERRUPTED, context.getString(R.string.reflection_interrupted))) return INTERRUPTED;
        if (is(normalized, CANCELLED, context.getString(R.string.reflection_cancelled))) return CANCELLED;
        if (is(normalized, PARTIAL, context.getString(R.string.reflection_status_partial))) return PARTIAL;
        if (is(normalized, PRIVACY_REQUIRED, context.getString(R.string.reflection_privacy_required))) return PRIVACY_REQUIRED;
        if (is(normalized, MEMORY_DISABLED, context.getString(R.string.reflection_skip_memory))) return MEMORY_DISABLED;
        if (is(normalized, CHAT_DISABLED, context.getString(R.string.reflection_skip_chat))) return CHAT_DISABLED;
        if (is(normalized, AUTO_OFF, context.getString(R.string.reflection_auto_off))) return AUTO_OFF;
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        if ("RATE_LIMITED".equals(normalized)
                || lower.startsWith("reflection is waiting for its ")
                || lower.startsWith("la reflexión espera el intervalo de ")) return "";
        return normalized;
    }

    public static String localized(Context context, String value) {
        String state = code(context, value);
        if (state.isEmpty()) return "";
        switch (state) {
            case REFLECTING: return context.getString(R.string.reflection_status_running);
            case NO_CHANGES: return context.getString(R.string.reflection_status_no_changes);
            case FAILED_RETRY: return context.getString(R.string.reflection_failed_backoff);
            case INTERRUPTED: return context.getString(R.string.reflection_interrupted);
            case CANCELLED: return context.getString(R.string.reflection_cancelled);
            case PARTIAL: return context.getString(R.string.reflection_status_partial);
            case PRIVACY_REQUIRED: return context.getString(R.string.reflection_privacy_required);
            case MEMORY_DISABLED: return context.getString(R.string.reflection_skip_memory);
            case CHAT_DISABLED: return context.getString(R.string.reflection_skip_chat);
            case AUTO_OFF: return context.getString(R.string.reflection_auto_off);
            default: return value == null ? "" : value;
        }
    }

    private static boolean is(String value, String... candidates) {
        for (String candidate : candidates) if (candidate != null && value.equals(candidate)) return true;
        return false;
    }
}
