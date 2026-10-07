package com.jarvys.agent;

/** Pure compaction/manual trigger, usefulness, and failure-backoff policy. */
public final class MemoryReflectionPolicy {
    private static final long BASE_BACKOFF_MILLIS = 15L * 60L * 1000L;
    private static final long MAX_BACKOFF_MILLIS = 24L * 60L * 60L * 1000L;

    private MemoryReflectionPolicy() { }

    public static boolean shouldTrigger(boolean compactionPending, boolean manual) { return manual || compactionPending; }

    public static boolean isUseful(int messageCount, int userCharacters) {
        return messageCount >= 2 && userCharacters >= 12;
    }

    public static boolean canReflect(boolean memoryEnabled, boolean memoryEnabledForChat,
                                     boolean autoEnabled, boolean disclosureShown, boolean manual) {
        return memoryEnabled && memoryEnabledForChat && disclosureShown && (manual || autoEnabled);
    }

    public static boolean failureBackoffElapsed(long nowMillis, long backoffUntilMillis) { return nowMillis >= backoffUntilMillis; }

    public static long failureBackoffMillis(int consecutiveFailures) {
        int exponent = Math.max(0, Math.min(7, consecutiveFailures - 1));
        return Math.min(MAX_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS * (1L << exponent));
    }

}
