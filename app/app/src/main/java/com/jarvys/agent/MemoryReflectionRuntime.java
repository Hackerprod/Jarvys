package com.jarvys.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-session reflection execution registry, independent from the foreground chat STOP latch. */
public final class MemoryReflectionRuntime {
    private static final Map<String, CancellationToken> ACTIVE = new ConcurrentHashMap<>();
    private MemoryReflectionRuntime() { }

    public static synchronized CancellationToken begin(String sessionId) {
        if (!ACTIVE.isEmpty()) return null;
        CancellationToken token = CancellationToken.cancellable();
        ACTIVE.put(sessionId, token);
        return token;
    }

    public static synchronized void finish(String sessionId, CancellationToken token) {
        if (sessionId != null && token != null) ACTIVE.remove(sessionId, token);
    }

    public static boolean isRunning(String sessionId) { return sessionId != null && ACTIVE.containsKey(sessionId); }
    public static boolean isAnyRunning() { return !ACTIVE.isEmpty(); }

    public static boolean cancel(String sessionId) {
        CancellationToken token = ACTIVE.get(sessionId);
        return token != null && token.cancel();
    }

    public static void cancelAll() { for (CancellationToken token : ACTIVE.values()) token.cancel(); }
}
