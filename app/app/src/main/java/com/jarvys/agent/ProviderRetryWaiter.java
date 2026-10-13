package com.jarvys.agent;

import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Shared cancellation-aware backoff for retryable provider model requests. */
public class ProviderRetryWaiter implements CoreAgentLoop.RateLimitWaiter {
    private static final long RETRY_BASE_DELAY_MS = 1_000L;
    private static final long RETRY_MAX_DELAY_MS = 30_000L;
    public interface Clock { void sleep(long millis, CancellationToken token); }
    private final Clock clock;
    private final java.util.function.DoubleSupplier jitter;

    public ProviderRetryWaiter() {
        this((millis, token) -> {
            token.throwIfCancelled();
            CountDownLatch cancelled = new CountDownLatch(1);
            Runnable unregister = token.registerCancelAction(cancelled::countDown);
            try {
                try { cancelled.await(millis, TimeUnit.MILLISECONDS); }
                catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    token.throwIfCancelled();
                    throw new IllegalStateException("Provider retry wait interrupted", stopped);
                }
                token.throwIfCancelled();
            } finally {
                unregister.run();
            }
        }, () -> ThreadLocalRandom.current().nextDouble(0.0d, 1.0d));
    }

    public ProviderRetryWaiter(Clock clock, java.util.function.DoubleSupplier jitter) {
        this.clock = java.util.Objects.requireNonNull(clock);
        this.jitter = java.util.Objects.requireNonNull(jitter);
    }

    @Override public void await(Throwable failure, long retryNumber, CancellationToken token) {
        token.throwIfCancelled();
        // Match OpenClaw's transient retry shape: 1s exponential base, capped at 30s,
        // then 0.5x..1.5x jitter which is itself capped at 30s. retryNumber is zero-based
        // here, so it maps directly to OpenClaw's one-based retryNumber - 1 exponent.
        int exponent = (int) Math.min(5L, Math.max(0L, retryNumber));
        long exponential = Math.min(RETRY_MAX_DELAY_MS, RETRY_BASE_DELAY_MS * (1L << exponent));
        long retryAfter = retryAfter(failure);
        double factor = Math.max(0d, Math.min(1d, jitter.getAsDouble()));
        long backoff = Math.min(RETRY_MAX_DELAY_MS,
                Math.max(1L, Math.round(exponential * (0.5d + factor))));
        // Retry-After is a provider floor and remains uncapped; cancellation and the
        // active run timeout still interrupt the wait through the existing token gate.
        long delay = Math.max(backoff, retryAfter);
        clock.sleep(delay, token);
    }

    private static long retryAfter(Throwable failure) {
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause())
            if (cause instanceof ProviderHttpException && ((ProviderHttpException) cause).retryAfterMillis > 0)
                return ((ProviderHttpException) cause).retryAfterMillis;
        return 0;
    }
}
