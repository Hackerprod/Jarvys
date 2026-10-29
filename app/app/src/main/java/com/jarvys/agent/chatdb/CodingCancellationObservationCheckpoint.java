package com.jarvys.agent.chatdb;

import com.jarvys.agent.coding.CodingCancellation;
import java.util.Objects;

/** Historical post-TX observation only: cannot satisfy cancellation INTENT or admission APIs. */
public final class CodingCancellationObservationCheckpoint {
    private final CodingCancellation.Observation observation;
    private CodingCancellationObservationCheckpoint(CodingCancellation.Observation observation) {
        this.observation = Objects.requireNonNull(observation);
    }
    static CodingCancellationObservationCheckpoint confirmed(CodingCancellation.Observation observation) {
        return new CodingCancellationObservationCheckpoint(observation);
    }
    public CodingCancellation.Observation observation() { return observation; }
    public void requireFor(CodingCancellation.Observation expected) {
        if (observation != expected) throw new IllegalStateException("Exact cancellation observation required");
    }
}
