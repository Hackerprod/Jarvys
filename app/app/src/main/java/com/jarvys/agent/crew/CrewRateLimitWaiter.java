package com.jarvys.agent.crew;

import com.jarvys.agent.CancellationToken;
import com.jarvys.agent.ProviderRetryWaiter;

/** Compatibility name for Crew callers; retry behavior is shared with all provider loops. */
public final class CrewRateLimitWaiter extends ProviderRetryWaiter {
    public interface Clock extends ProviderRetryWaiter.Clock { }

    public CrewRateLimitWaiter() {
        super();
    }

    public CrewRateLimitWaiter(Clock clock, java.util.function.DoubleSupplier jitter) {
        super(clock, jitter);
    }
}
