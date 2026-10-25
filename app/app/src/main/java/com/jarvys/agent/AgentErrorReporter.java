package com.jarvys.agent;

import android.content.Context;

/** Best-effort local diagnostic metadata. There is no automatic telemetry transport. */
public final class AgentErrorReporter {
    private AgentErrorReporter() { }

    static void report(Context context, String provider, String model, Integer httpStatus,
                       String responseBody, String exceptionMessage, String exceptionType,
                       String stackTrace) {
        if (context == null) return;
        // Compatibility callers may still supply private strings. Never read, retain or transmit them.
        LocalDiagnostics.compatibility(context, httpStatus);
    }

    /** Records only the sanitized structural projection for a chat database open failure. */
    public static void reportChatDatabaseFailure(Context context, Throwable failure) {
        if (context == null || failure == null) return;
        LocalDiagnostics.recoverable(context, LocalDiagnostics.Origin.ACTIVITY, failure);
    }

    /** Records only structural failure data for background memory maintenance. */
    public static void reportBackgroundFailure(Context context, Throwable failure) {
        if (context == null || failure == null) return;
        LocalDiagnostics.recoverable(context, LocalDiagnostics.Origin.RUNTIME, failure);
    }
}
