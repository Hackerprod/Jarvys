package com.jarvys.agent;

import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Shared cancellation-aware backoff for retryable provider model requests. */
public class ProviderRetryWaiter implements CoreAgentLoop.RateLimitWaiter {
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
        }, () -> ThreadLocalRandom.current().nextDouble(0.5d, 1.0d));
    }

    public ProviderRetryWaiter(Clock clock, java.util.function.DoubleSupplier jitter) {
        this.clock = java.util.Objects.requireNonNull(clock);
        this.jitter = java.util.Objects.requireNonNull(jitter);
    }

    @Override public void await(Throwable failure, long retryNumber, CancellationToken token) {
        token.throwIfCancelled();
        long exponential = retryNumber >= 54 ? Long.MAX_VALUE
                : 1000L * (1L << Math.max(0L, retryNumber));
        long retryAfter = retryAfter(failure);
        double factor = Math.max(0d, Math.min(1d, jitter.getAsDouble()));
        long backoff = exponential == Long.MAX_VALUE ? Long.MAX_VALUE
                : (long) Math.max(1d, exponential * factor);
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
