package com.jarvys.agent;

import android.content.Context;

/** Best-effort local diagnostic metadata. There is no automatic telemetry transport. */
public final class AgentErrorReporter {
    private static final ThreadLocal<Integer> PRIVATE_ATTACHMENT_RUNS = new ThreadLocal<Integer>() {
        @Override protected Integer initialValue() { return 0; }
    };

    private AgentErrorReporter() { }

    static AttachmentScope suppressForAttachments() {
        PRIVATE_ATTACHMENT_RUNS.set(PRIVATE_ATTACHMENT_RUNS.get() + 1);
        return new AttachmentScope();
    }

    static AttachmentScope suppressForPrivateContent() { return suppressForAttachments(); }

    static boolean attachmentReportsSuppressed() { return PRIVATE_ATTACHMENT_RUNS.get() > 0; }

    /** Captures the caller's privacy scope for a parallel tool worker. */
    static ScopeSnapshot captureScope() { return new ScopeSnapshot(PRIVATE_ATTACHMENT_RUNS.get()); }

    static final class ScopeSnapshot {
        private final int privateAttachmentRuns;
        private ScopeSnapshot(int privateAttachmentRuns) { this.privateAttachmentRuns = privateAttachmentRuns; }

        Scope install() { return new Scope(privateAttachmentRuns); }
    }

    /** Installs a captured scope and restores the worker's prior value when it exits. */
    static final class Scope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final int previous;
        private boolean closed;

        private Scope(int value) {
            previous = PRIVATE_ATTACHMENT_RUNS.get();
            if (value > 0) PRIVATE_ATTACHMENT_RUNS.set(value);
            else PRIVATE_ATTACHMENT_RUNS.remove();
        }

        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner) throw new IllegalStateException("Attachment scope has a different owner");
            closed = true;
            if (previous > 0) PRIVATE_ATTACHMENT_RUNS.set(previous);
            else PRIVATE_ATTACHMENT_RUNS.remove();
        }
    }

    static final class AttachmentScope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private boolean closed;
        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner) throw new IllegalStateException("Attachment scope has a different owner");
            closed = true;
            int remaining = PRIVATE_ATTACHMENT_RUNS.get() - 1;
            if (remaining <= 0) PRIVATE_ATTACHMENT_RUNS.remove();
            else PRIVATE_ATTACHMENT_RUNS.set(remaining);
        }
    }

    static void report(Context context, String provider, String model, Integer httpStatus,
                       String responseBody, String exceptionMessage, String exceptionType,
                       String stackTrace) {
        if (attachmentReportsSuppressed() || context == null) return;
        // Compatibility callers may still supply private strings. Never read, retain or transmit them.
        LocalDiagnostics.compatibility(context, httpStatus);
    }

    /** Records only the sanitized structural projection for a chat database open failure. */
    public static void reportChatDatabaseFailure(Context context, Throwable failure) {
        if (attachmentReportsSuppressed() || context == null || failure == null) return;
        LocalDiagnostics.recoverable(context, LocalDiagnostics.Origin.ACTIVITY, failure);
    }
}
