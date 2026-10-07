package com.jarvys.agent;

import java.util.Map;

/** Pure per-conversation keying for the temporary no-memory mode. */
public final class MemorySessionMode {
    private MemorySessionMode() { }

    public static String preferenceKey(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) throw new IllegalArgumentException("Conversation id is required");
        return "chat_without_memory_" + sessionId;
    }

    public static boolean isDisabled(Map<String, Boolean> storedModes, String sessionId) {
        if (storedModes == null) return false;
        return Boolean.TRUE.equals(storedModes.get(preferenceKey(sessionId)));
    }
}
