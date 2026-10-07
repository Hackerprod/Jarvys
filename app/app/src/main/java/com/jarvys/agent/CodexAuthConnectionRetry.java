package com.jarvys.agent;

import java.net.HttpURLConnection;
import java.net.UnknownHostException;
import java.util.concurrent.CancellationException;

/** Retries DNS failures only when connect() failed before any request body was obtained or written. */
public final class CodexAuthConnectionRetry {
    private static final long[] RETRY_DELAYS_MILLIS = {2_000L, 4_000L, 8_000L};
    private static final long RETRY_WINDOW_MILLIS = 20_000L;

    @FunctionalInterface public interface Attempt<T> { T run() throws Exception; }
    @FunctionalInterface public interface Clock { long nowMillis(); }
    @FunctionalInterface public interface Waiter {
        void waitFor(long millis, CancellationToken token) throws Exception;
    }

    private CodexAuthConnectionRetry() { }

    public static <T> T execute(CancellationToken token, Attempt<T> attempt) throws Exception {
        return execute(token, attempt, () -> System.nanoTime() / 1_000_000L, (millis, cancellation) -> {
            long remaining = millis;
            while (remaining > 0) {
                cancellation.throwIfCancelled();
                long step = Math.min(remaining, 100L);
                try {
                    Thread.sleep(step);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("ChatGPT sign-in cancelled");
                }
                remaining -= step;
            }
            cancellation.throwIfCancelled();
        });
    }

    static <T> T execute(CancellationToken token, Attempt<T> attempt, Clock clock, Waiter waiter) throws Exception {
        CancellationToken cancellation = token == null ? CancellationToken.uncancellable() : token;
        long startedAt = clock.nowMillis();
        int retries = 0;
        BeforeBodyDnsFailure lastFailure = null;
        while (true) {
            cancellation.throwIfCancelled();
            if (lastFailure != null && clock.nowMillis() - startedAt >= RETRY_WINDOW_MILLIS) throw lastFailure;
            try {
                return attempt.run();
            } catch (BeforeBodyDnsFailure failure) {
                cancellation.throwIfCancelled();
                lastFailure = failure;
                long remaining = RETRY_WINDOW_MILLIS - (clock.nowMillis() - startedAt);
                if (retries >= RETRY_DELAYS_MILLIS.length || remaining <= 0) throw failure;
                waiter.waitFor(Math.min(RETRY_DELAYS_MILLIS[retries++], remaining), cancellation);
            }
        }
    }

    public static void connectBeforeBody(HttpURLConnection connection, CodexAuthDiagnostic.Stage stage,
                                         CancellationToken token) throws Exception {
        if (token != null) token.throwIfCancelled();
        try {
            connection.connect();
        } catch (UnknownHostException failure) {
            if (token != null) token.throwIfCancelled();
            throw new BeforeBodyDnsFailure(CodexAuthDiagnostic.beforeBodyDns(stage));
        }
        if (token != null) token.throwIfCancelled();
    }

    public static final class BeforeBodyDnsFailure extends CodexAuthDiagnostic.Failure {
        private BeforeBodyDnsFailure(CodexAuthDiagnostic diagnostic) { super(diagnostic); }
    }
}
