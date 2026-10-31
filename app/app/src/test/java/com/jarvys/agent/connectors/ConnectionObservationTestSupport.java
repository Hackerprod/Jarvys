package com.jarvys.agent.connectors;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import static com.jarvys.agent.connectors.ConnectionStateCoordinator.*;

/** Synthetic durable adapter for causal tests; observations still originate from a real immutable ReadView. */
public final class ConnectionObservationTestSupport {
    private ConnectionObservationTestSupport() { }
    public static Observation observation(String connector, boolean selected) {
        MemoryStorage storage = new MemoryStorage();
        ConnectionStateCoordinator core = new ConnectionStateCoordinator(storage, () -> 42L, "synthetic-process");
        core.initialize();
        ReadView initial = core.read();
        RequestToken request = core.beginIntent(new Command(connector, "synthetic-definition", "synthetic-connect",
                Kind.CONNECT, initial.revision(connector), initial.intentEpoch(connector), null));
        Result candidate = core.persistSelection(request);
        if (candidate.permit == null) throw new AssertionError("Synthetic connection was not admitted");
        core.activationReturned(candidate.permit);
        if (!selected) {
            ReadView current = core.read();
            core.persistSelection(core.beginIntent(new Command(connector, "synthetic-definition", "synthetic-disconnect",
                    Kind.DISCONNECT, current.revision(connector), current.intentEpoch(connector), null)));
        }
        Observation result = core.read().observation(connector);
        if (result == null || result.selected != selected) throw new AssertionError("Expected exact confirmed observation");
        return result;
    }

    private static final class MemoryStorage implements Storage {
        long highWater;
        final Map<String, Mutation> current = new LinkedHashMap<>(), proofs = new LinkedHashMap<>();
        final Map<String, Event> pending = new LinkedHashMap<>();
        final Map<String, UncertainCandidate> candidates = new LinkedHashMap<>();
        @Override public StoredImage load() {
            return new StoredImage("synthetic-epoch", highWater, "synthetic-room", "synthetic-room",
                    current, proofs, pending, candidates);
        }
        @Override public Outcome writeCandidate(Mutation mutation) {
            current.put(mutation.event.connectorId, mutation);
            highWater = Math.max(highWater, mutation.highWater);
            pending.put(mutation.event.eventId, mutation.event);
            candidates.put(mutation.version, new UncertainCandidate(mutation, false));
            return Outcome.CONFIRMED;
        }
        @Override public Outcome writeProof(Mutation mutation) {
            proofs.put(mutation.version, mutation); return Outcome.CONFIRMED;
        }
        @Override public Outcome cleanup(ProjectionBatch batch, RetentionPlan plan) {
            throw new AssertionError("Causal tests must not depend on projection");
        }
        @Override public RetentionSnapshot retentionSnapshot() { return new RetentionSnapshot(load(), this, 0L); }
        @Override public void bindDestination(DestinationBinding binding) { }
    }
}
