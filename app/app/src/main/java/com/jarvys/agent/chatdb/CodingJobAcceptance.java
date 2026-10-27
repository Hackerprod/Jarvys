package com.jarvys.agent.chatdb;

import com.jarvys.agent.EffectInvocation;
import com.jarvys.agent.coding.CodingJobJournalStore;
import java.util.Objects;

/** Administrative durable submission acceptance only; never an execution-success receipt. */
public final class CodingJobAcceptance {
    private final CodingJobReservation reservation;
    private final CodingJobJournalStore.Commit commit;
    private CodingJobAcceptance(CodingJobReservation reservation,CodingJobJournalStore.Commit commit) {
        this.reservation=Objects.requireNonNull(reservation);this.commit=Objects.requireNonNull(commit);
        if(commit.reservation()!=reservation)throw new IllegalArgumentException("Foreign submission commit");
    }
    static CodingJobAcceptance confirmed(CodingJobReservation reservation,CodingJobJournalStore.Commit commit) {
        return new CodingJobAcceptance(reservation,commit);
    }
    public CodingJobReservation reservation(){return reservation;}
    public CodingJobJournalStore.Commit submissionCommit(){return commit;}
    /** Bind while the exact originating invocation is live; historical getters remain available afterward. */
    public void requireFor(EffectInvocation invocation){reservation.submission().requireFor(invocation);}
}
