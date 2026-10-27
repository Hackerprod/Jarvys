package com.jarvys.agent.chatdb;

import com.jarvys.agent.coding.CodingJobEvidence;
import com.jarvys.agent.coding.CodingJobJournalStore;
import com.jarvys.agent.coding.CodingJobSubmission;
import com.jarvys.agent.connectors.ConnectionStateCoordinator;
import java.util.Objects;

/** Post-transaction technical reservation. No enqueue, launch, success or semantic-memory permission. */
public final class CodingJobReservation {
    private final CodingJobEvidence.Binding binding;
    private final CodingJobSubmission submission;
    private final CodingJobJournalStore journal;
    private final ConnectionStateCoordinator.DestinationEvidence lifecycle;
    private CodingJobReservation(CodingJobEvidence.Binding binding, CodingJobSubmission submission,
            CodingJobJournalStore journal, ConnectionStateCoordinator.DestinationEvidence lifecycle) {
        this.binding=Objects.requireNonNull(binding); this.submission=Objects.requireNonNull(submission);
        this.journal=Objects.requireNonNull(journal); this.lifecycle=Objects.requireNonNull(lifecycle);
        if(!binding.technicalStoreGeneration.equals(lifecycle.generation()) || submission.journal()!=journal)
            throw new IllegalArgumentException("Technical reservation owner mismatch");
    }
    static CodingJobReservation confirmed(CodingJobEvidence.Binding binding, CodingJobSubmission submission,
            CodingJobJournalStore journal, ConnectionStateCoordinator.DestinationEvidence lifecycle) {
        return new CodingJobReservation(binding,submission,journal,lifecycle);
    }
    public CodingJobEvidence.Binding binding(){return binding;}
    public CodingJobSubmission submission(){return submission;}
    public CodingJobJournalStore journal(){return journal;}
    /** Historical journal writes may continue after an admitted effect loses its projection destination. */
    public void requireJournalOwner(CodingJobJournalStore expected) {
        if(journal!=expected || submission.journal()!=expected)throw new IllegalStateException("Foreign coding journal issuer");
    }
    /** Pure check under the caller's already-held lifecycle fence; never acquires a lock. */
    public void requireCurrentProjection() {
        if(!lifecycle.fenceIdentity().isHeldByCurrentThread() || !lifecycle.isCurrentPure())
            throw new IllegalStateException("Coding technical destination is no longer current");
    }
    ConnectionStateCoordinator.DestinationEvidence lifecycle(){return lifecycle;}
}
