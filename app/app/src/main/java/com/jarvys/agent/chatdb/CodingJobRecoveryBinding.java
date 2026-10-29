package com.jarvys.agent.chatdb;

import com.jarvys.agent.coding.CodingJobJournalStore;
import com.jarvys.agent.connectors.ConnectionStateCoordinator;
import java.util.Objects;

/** Existing-only Room identity attestation. Not a reservation, acceptance or journal durability proof. */
public final class CodingJobRecoveryBinding {
    private final CodingJobJournalStore.RecoveryCandidate candidate;
    private final CodingJobJournalStore journal;
    private final ConnectionStateCoordinator.DestinationEvidence lifecycle;
    private CodingJobRecoveryBinding(CodingJobJournalStore.RecoveryCandidate candidate,
            CodingJobJournalStore journal, ConnectionStateCoordinator.DestinationEvidence lifecycle) {
        this.candidate = Objects.requireNonNull(candidate);
        this.journal = Objects.requireNonNull(journal);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        if (candidate.journal() != journal || !candidate.record().binding.technicalStoreGeneration.equals(lifecycle.generation()))
            throw new IllegalArgumentException("Recovery destination identity mismatch");
    }
    static CodingJobRecoveryBinding verified(CodingJobJournalStore.RecoveryCandidate candidate,
            CodingJobJournalStore journal, ConnectionStateCoordinator.DestinationEvidence lifecycle) {
        return new CodingJobRecoveryBinding(candidate, journal, lifecycle);
    }
    public CodingJobJournalStore.RecoveryCandidate candidate() { return candidate; }
    public CodingJobJournalStore journal() { return journal; }
    public void requireFor(CodingJobJournalStore.RecoveryCandidate expected, CodingJobJournalStore issuer) {
        if (candidate != expected || journal != issuer || candidate.journal() != issuer)
            throw new IllegalStateException("Foreign recovery candidate or journal issuer");
    }
    public void requireCurrentProjection() {
        if (!lifecycle.fenceIdentity().isHeldByCurrentThread() || !lifecycle.isCurrentPure())
            throw new IllegalStateException("Coding recovery destination is no longer current");
    }
    ConnectionStateCoordinator.DestinationEvidence lifecycle() { return lifecycle; }
}
