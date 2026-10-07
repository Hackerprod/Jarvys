package com.jarvys.agent.crew;

import com.jarvys.agent.CancellationToken;
import com.jarvys.agent.CoreAgentLoop;
import com.jarvys.agent.ProviderHttpException;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Unbounded retry policy with exponential delay, jitter, and provider Retry-After precedence. */
public final class CrewRateLimitWaiter implements CoreAgentLoop.RateLimitWaiter {
    public interface Clock { void sleep(long millis, CancellationToken token); }
    private final Clock clock;
    private final java.util.function.DoubleSupplier jitter;

    public CrewRateLimitWaiter() {
        this((millis, token) -> {
            token.throwIfCancelled();
            try { TimeUnit.MILLISECONDS.sleep(millis); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); token.throwIfCancelled(); }
            token.throwIfCancelled();
        }, () -> ThreadLocalRandom.current().nextDouble(0.5d, 1.0d));
    }

    public CrewRateLimitWaiter(Clock clock, java.util.function.DoubleSupplier jitter) {
        this.clock = clock;
        this.jitter = jitter;
    }

    @Override public void await(ProviderHttpException failure, long retryNumber, CancellationToken token) {
        token.throwIfCancelled();
        long exponential;
        if (retryNumber >= 54) exponential = Long.MAX_VALUE;
        else exponential = 1000L * (1L << Math.max(0L, retryNumber));
        long delay;
        if (failure.retryAfterMillis > 0) delay = failure.retryAfterMillis;
        else {
            double factor = Math.max(0d, Math.min(1d, jitter.getAsDouble()));
            delay = exponential == Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.max(1d, exponential * factor);
        }
        clock.sleep(delay, token);
    }
}
