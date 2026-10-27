package com.jarvys.agent.coding;

import com.jarvys.agent.CrewInvocationSource;
import com.jarvys.agent.CrewInvocationWitness;
import com.jarvys.agent.EffectInvocation;
import com.jarvys.agent.MemoryWriteGate;
import com.jarvys.agent.chatdb.CodingJobReservation;
import com.jarvys.agent.chatdb.CodingJobAcceptance;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.ref.WeakReference;
import java.util.Objects;

/** A live capture, not a decoded source DTO. Durable holders retain only metadata and an opaque nonce. */
public final class CodingJobSubmission {
    private final String jobId, submissionId, owner, scopeId, durableScopeIdentity, ownerProcessId, managerInstanceId;
    private final CrewInvocationSource crew;
    private final Object ownerNonce;
    private final CodingJobEvidence.SemanticCapture semanticCapture;
    private final CodingJobJournalStore journal;
    private final CodingJobOwnerLease.Pin ownerPin;
    private final MemoryWriteGate.Ticket semanticTicket;
    private final AtomicBoolean dispatchAbandoned = new AtomicBoolean();
    private final AtomicReference<ReservationOutcome> reservationOutcome = new AtomicReference<>();
    private final Object acceptanceLock = new Object();
    private AcceptanceOutcome acceptanceOutcome;
    private final List<Runnable> acceptanceObservers = new ArrayList<>();
    private final WeakReference<CrewInvocationWitness> pendingCapture;
    private CodingJobSubmission(EffectInvocation invocation, String job, String submission, String owner,
            String scope, String identity, CodingJobOwnerLease.Pin ownerPin,
            MemoryWriteGate.Ticket semanticTicket, CodingJobJournalStore journal) {
        CrewInvocationWitness witness = Objects.requireNonNull(invocation.crewSource(), "A real Crew invocation source is required");
        witness.requireInvocationCurrent();
        crew = witness.source();
        if (!"project_exec".equals(crew.toolName)) throw new IllegalArgumentException("Only the exact project execution call may submit a job");
        jobId = CodingJobEvidence.uuid(job); submissionId = CodingJobEvidence.uuid(submission);
        this.owner = CodingJobEvidence.id(owner); scopeId = CodingJobEvidence.id(scope);
        durableScopeIdentity = CodingJobEvidence.id(identity);
        this.ownerPin = Objects.requireNonNull(ownerPin); ownerPin.requireLive();
        ownerProcessId = ownerPin.processId(); managerInstanceId = ownerPin.managerId();
        if (!crew.scopeIdentity.equals(identity) || !owner.startsWith(crew.executionSessionId + "/" + crew.botId + "/"))
            throw new IllegalArgumentException("Submission scope or owner does not match the live Crew source");
        this.semanticTicket = Objects.requireNonNull(semanticTicket);
        this.semanticCapture = semanticTicket.enabledAtCapture() ? CodingJobEvidence.SemanticCapture.ENABLED : CodingJobEvidence.SemanticCapture.DISABLED;
        this.journal = Objects.requireNonNull(journal);
        ownerNonce = invocation.asyncSubmissionOwnerIdentity();
        pendingCapture = new WeakReference<>(witness);
    }
    public static CodingJobSubmission capture(EffectInvocation invocation, String job, String submission, String owner,
            String scope, String identity, CodingJobOwnerLease.Pin ownerPin,
            MemoryWriteGate.Ticket semanticTicket, CodingJobJournalStore journal) {
        return new CodingJobSubmission(Objects.requireNonNull(invocation),job,submission,owner,scope,identity,ownerPin,semanticTicket,journal);
    }
    public void requireCaptureCurrent() {
        ownerPin.requireLive();
        CrewInvocationWitness witness = pendingCapture.get();
        if (witness == null || witness.source() != crew) throw new IllegalStateException("Submission capture is no longer live");
        witness.requireInvocationCurrent();
    }
    /** Only after the reserve transaction committed. No worker retains a live guard or invocation. */
    public void captureConfirmed() { pendingCapture.clear(); }
    public void requireFor(EffectInvocation invocation) {
        if (invocation == null || invocation.asyncSubmissionOwnerIdentity() != ownerNonce)
            throw new IllegalStateException("Submission belongs to a different invocation");
        CrewInvocationWitness witness = invocation.crewSource();
        if (witness == null || witness.source() != crew) throw new IllegalStateException("Submission source was replaced");
        witness.requireInvocationCurrent();
    }
    public void requireOwnerLive() { ownerPin.requireLive(); }
    public void abandonDispatch() { dispatchAbandoned.set(true); }
    public void requireDispatchLive() {
        requireOwnerLive();
        if (dispatchAbandoned.get()) throw new IllegalStateException("This submission cannot resume dispatch after uncertainty");
    }
    public MemoryWriteGate.Ticket semanticTicket() { return semanticTicket; }
    public static final class ReservationOutcome {
        public final CodingJobReservation reservation;
        public final boolean failed;
        private ReservationOutcome(CodingJobReservation reservation, boolean failed) { this.reservation=reservation;this.failed=failed; }
    }
    /** Historical observation only, never a continuation/retry permission. */
    public ReservationOutcome reservationOutcome() { return reservationOutcome.get(); }
    /** Exact completion of the original acceptance operation, never permission to enqueue work. */
    public static final class AcceptanceOutcome {
        public final CodingJobAcceptance acceptance;
        public final boolean failed;
        private AcceptanceOutcome(CodingJobAcceptance acceptance, boolean failed) {
            this.acceptance = acceptance; this.failed = failed;
        }
    }
    public AcceptanceOutcome acceptanceOutcome() {
        synchronized (acceptanceLock) { return acceptanceOutcome; }
    }
    /** Observers may only schedule work; they run outside the slot monitor and Room lifecycle fence. */
    public void onAcceptanceCompleted(Runnable observer) {
        Objects.requireNonNull(observer);
        synchronized (acceptanceLock) {
            if (acceptanceOutcome == null) { acceptanceObservers.add(observer); return; }
        }
        notifyAcceptanceObserver(observer);
    }
    private void publishAcceptance(AcceptanceOutcome result) {
        List<Runnable> observers;
        synchronized (acceptanceLock) {
            if (acceptanceOutcome != null) {
                if (!result.failed && acceptanceOutcome.acceptance != result.acceptance)
                    throw new IllegalStateException("Acceptance outcome cannot be replaced");
                return; // A reported failure cannot erase an already committed acceptance.
            }
            acceptanceOutcome = result;
            observers = new ArrayList<>(acceptanceObservers);
            acceptanceObservers.clear();
        }
        for (Runnable observer : observers) notifyAcceptanceObserver(observer);
    }
    private static void notifyAcceptanceObserver(Runnable observer) {
        try { observer.run(); } catch (RuntimeException ignored) {
            // The exact operation result remains observable; an observer is not its authority.
        }
    }
    public RoomOperation beginRoomOperation() { return new RoomOperation(ownerPin.fork(), false); }
    public RoomOperation beginAcceptanceOperation() { return new RoomOperation(ownerPin.fork(), true); }
    public final class RoomOperation implements AutoCloseable {
        private final CodingJobOwnerLease.Pin pin;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final boolean acceptance;
        private RoomOperation(CodingJobOwnerLease.Pin pin, boolean acceptance) {
            this.pin=pin; this.acceptance=acceptance;
        }
        public void completeReservation(CodingJobReservation value) {
            if(value.submission()!=CodingJobSubmission.this)throw new IllegalStateException("Foreign reservation completion");
            ReservationOutcome result=new ReservationOutcome(value,false);
            ReservationOutcome previous=reservationOutcome.get();
            if(previous!=null && previous.reservation!=value)throw new IllegalStateException("Reservation outcome cannot be replaced");
            reservationOutcome.compareAndSet(null,result);
        }
        public void completeAcceptance(CodingJobAcceptance value) {
            if (!acceptance || value.reservation().submission() != CodingJobSubmission.this)
                throw new IllegalStateException("Foreign acceptance completion");
            publishAcceptance(new AcceptanceOutcome(value, false));
        }
        public void fail(Throwable ignored) {
            if (acceptance) publishAcceptance(new AcceptanceOutcome(null, true));
            else reservationOutcome.compareAndSet(null,new ReservationOutcome(null,true));
        }
        @Override public void close() { if(finished.compareAndSet(false,true))pin.close(); }
    }
    public String jobId() { return jobId; }
    public String submissionId() { return submissionId; }
    public String owner() { return owner; }
    public String scopeId() { return scopeId; }
    public String durableScopeIdentity() { return durableScopeIdentity; }
    public String ownerProcessId() { return ownerProcessId; }
    public String managerInstanceId() { return managerInstanceId; }
    public CrewInvocationSource crew() { return crew; }
    public CodingJobEvidence.SemanticCapture semanticCapture() { return semanticCapture; }
    public CodingJobJournalStore journal() { return journal; }
}
