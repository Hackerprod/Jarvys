package com.jarvys.agent.coding;

import com.jarvys.agent.CrewInvocationSource;
import com.jarvys.agent.CrewInvocationWitness;
import com.jarvys.agent.EffectInvocation;
import com.jarvys.agent.MemoryWriteGate;
import com.jarvys.agent.chatdb.CodingCancellationCheckpoint;
import com.jarvys.agent.chatdb.CodingCancellationObservationCheckpoint;
import com.jarvys.agent.chatdb.CodingJobReservation;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Narrow trusted-manager attestation. Not a permission or a boundary against arbitrary module code. */
public final class CodingCancellation {
    private CodingCancellation() {}
    public enum Disposition { NEW_REQUEST, ALREADY_REQUESTED, ALREADY_TERMINAL, REQUEST_UNCONFIRMED }

    /** One issuer per actual process-lived manager owner, not an issuer reconstructed from IDs. */
    public static final class Issuer {
        private final CodingJobOwnerLease owner;
        Issuer(CodingJobOwnerLease owner) { this.owner=Objects.requireNonNull(owner); }
        public Request capture(EffectInvocation invocation, CodingJobReservation reservation,
                CodingJobOwnerLease.Pin pin, MemoryWriteGate.Ticket semanticTicket,
                CodingJobEvidence.Source semanticSource) {
            pin.requireLive();
            if(pin.ownerIdentity()!=owner)throw new IllegalStateException("Foreign cancellation owner");
            // Requires a distinct root admission from the exact original owner object, not supplied IDs.
            reservation.submission().requireRepairPin(pin);
            reservation.submission().requireOwnerLive();
            return new Request(this,invocation,reservation,pin,semanticTicket,semanticSource);
        }
        /** Observational only: original worker pin may be closed, but a new same-owner root pin is required. */
        public Observation observe(EffectInvocation invocation, CodingJobReservation reservation,
                CodingJobOwnerLease.Pin pin, Disposition disposition, CodingJobSnapshot.State state, Integer exitCode,
                MemoryWriteGate.Ticket semanticTicket, CodingJobEvidence.Source semanticSource) {
            pin.requireLive();
            if(pin.ownerIdentity()!=owner)throw new IllegalStateException("Foreign observation owner");
            reservation.submission().requireRepairPin(pin);
            return new Observation(this,invocation,reservation,pin,disposition,state,exitCode,semanticTicket,semanticSource);
        }
        /** Called only with the private manager disposition after its locked cancellation operation.
         * It never accepts JSON, a decoded receipt, or a generic success flag. */
        public Result result(Request request, CodingCancellationCheckpoint intent,
                Disposition disposition, CodingJobJournalStore.Commit cancellingCommit) {
            if(request.issuer!=this)throw new IllegalStateException("Foreign cancellation issuer");
            request.pin.requireLive();
            intent.requireIntent(request);
            Objects.requireNonNull(disposition);
            if(cancellingCommit!=null) {
                cancellingCommit.requireMintedBy(request.reservation.journal());
                if(cancellingCommit.reservation()!=request.reservation ||
                        cancellingCommit.snapshot().state!=CodingJobSnapshot.State.CANCELLING)
                    throw new IllegalStateException("Cancellation journal is not the exact target request");
                if(disposition!=Disposition.NEW_REQUEST)
                    throw new IllegalStateException("A no-op cannot claim a new cancellation journal");
            }
            synchronized(request) {
                if(request.result!=null)throw new IllegalStateException("Cancellation result already captured");
                request.result=new Result(this,request,intent,disposition,cancellingCommit);
                return request.result;
            }
        }
    }

    /** Resolver metadata is not authority. The trusted runtime resolves it from this call, never the target job.
     * The later Room target join and live ticket fence remain mandatory. */
    private static CodingJobEvidence.Source requireSemanticSource(CrewInvocationSource actual,
            CodingJobEvidence.Source resolved) {
        // Null is explicit lookup UNAVAILABLE, not verified root absence. Technical Crew source remains exact.
        if(resolved==null)return null;
        CodingJobEvidence.CrewSource expected=new CodingJobEvidence.CrewSource(actual.executionSessionId,actual.botId,
            actual.missionId,actual.cycleId,actual.scopeIdentity,actual.checkpointPayloadSha256,actual.callId,
            actual.toolName,actual.inputContext);
        if(!expected.equals(resolved.crew))throw new IllegalArgumentException("Cancellation semantic source differs from its live call");
        return resolved;
    }

    private static String sourceKey(CrewInvocationSource source) {
        // Canonical escaped JSON preserves UTF-16 identity; target is excluded to detect call rebinding.
        String key=CodingJobEvidenceCodec.json(java.util.Arrays.asList(source.executionSessionId,source.botId,
            source.missionId,source.cycleId,source.scopeIdentity,source.checkpointPayloadSha256,source.callId,source.toolName));
        return ProjectScope.sha256(key.getBytes(StandardCharsets.UTF_8));
    }
    private static String sourceJson(CrewInvocationSource source) {
        return CodingJobEvidenceCodec.json(CodingJobEvidenceCodec.object(
            "executionSessionId",source.executionSessionId,"botId",source.botId,"missionId",source.missionId,
            "cycleId",source.cycleId,"scopeIdentity",source.scopeIdentity,
            "checkpointPayloadSha256",source.checkpointPayloadSha256,"callId",source.callId,"toolName",source.toolName,
            "inputContext",CodingJobEvidenceCodec.inputContext(source.inputContext)));
    }

    /** An exact private-manager no-op observation. This type cannot become Request, INTENT or a signal. */
    public static final class Observation implements AutoCloseable {
        private final Issuer issuer;
        private final String observationId=UUID.randomUUID().toString(), sourceKey, sourceJson;
        private final CrewInvocationSource source;
        private final MemoryWriteGate.Ticket semanticTicket;
        private final CodingJobEvidence.Source semanticSource;
        private final CodingJobReservation reservation;
        private final CodingJobOwnerLease.Pin pin;
        private final Object invocationNonce;
        private final WeakReference<CrewInvocationWitness> pendingCapture;
        private final Disposition disposition;
        private final CodingJobSnapshot.State state;
        private final Integer exitCode;
        private boolean closed;
        private volatile CodingCancellationObservationCheckpoint outcome;
        private Observation(Issuer issuer,EffectInvocation invocation,CodingJobReservation reservation,
                CodingJobOwnerLease.Pin pin,Disposition disposition,CodingJobSnapshot.State state,Integer exitCode,
                MemoryWriteGate.Ticket semanticTicket,CodingJobEvidence.Source semanticSource) {
            // Outside Room/manager locks. The trusted no-op branch never subsequently admits.
            if(invocation.admitted())throw new IllegalStateException("An admitted invocation cannot become a no-admission observation");
            this.issuer=issuer;this.reservation=Objects.requireNonNull(reservation);this.pin=pin;
            this.disposition=Objects.requireNonNull(disposition);this.state=Objects.requireNonNull(state);this.exitCode=exitCode;
            boolean terminal=state!=CodingJobSnapshot.State.LAUNCH_INTENT && state!=CodingJobSnapshot.State.RUNNING &&
                state!=CodingJobSnapshot.State.CANCELLING;
            if(disposition!=Disposition.ALREADY_TERMINAL && disposition!=Disposition.ALREADY_REQUESTED ||
                (disposition==Disposition.ALREADY_TERMINAL)!=terminal || !terminal && exitCode!=null)
                throw new IllegalArgumentException("Observation must preserve an exact no-op fact");
            CrewInvocationWitness witness=Objects.requireNonNull(invocation.crewSource(),"Live Crew observation source required");
            witness.requireInvocationCurrent();source=witness.source();
            this.semanticTicket=Objects.requireNonNull(semanticTicket,"Current cancellation ticket is required");
            this.semanticSource=requireSemanticSource(source,semanticSource);
            CodingJobEvidence.Binding binding=reservation.binding();
            if(!"project_jobs".equals(source.toolName) || !source.scopeIdentity.equals(binding.durableScopeIdentity) ||
                    !binding.owner.startsWith(source.executionSessionId+"/"+source.botId+"/"))
                throw new IllegalArgumentException("Observation source does not own the target scope");
            invocationNonce=invocation.asyncSubmissionOwnerIdentity();pendingCapture=new WeakReference<>(witness);
            sourceKey=CodingCancellation.sourceKey(source);sourceJson=CodingCancellation.sourceJson(source);
        }
        public String observationId(){return observationId;}
        public String sourceKey(){return sourceKey;}
        public String sourceJson(){return sourceJson;}
        public CrewInvocationSource source(){return source;}
        public MemoryWriteGate.Ticket semanticTicket(){return semanticTicket;}
        /** Null means optional attribution lookup was unavailable; never a reconstructed or guessed root. */
        public CodingJobEvidence.Source semanticSource(){return semanticSource;}
        public CodingJobReservation reservation(){return reservation;}
        public Issuer issuer(){return issuer;}
        public Disposition disposition(){return disposition;}
        public CodingJobSnapshot.State observedState(){return state;}
        public Integer observedExitCode(){return exitCode;}
        public void requireIssuer(){if(pin.ownerIdentity()!=issuer.owner)throw new IllegalStateException("Foreign observation issuer");}
        public void requireCaptureCurrent() {
            pin.requireLive();requireIssuer();
            CrewInvocationWitness witness=pendingCapture.get();
            if(witness==null || witness.source()!=source)throw new IllegalStateException("Observation capture is no longer live");
            witness.requireInvocationCurrent();
        }
        public void captureConfirmed(){pendingCapture.clear();}
        public void requireFor(EffectInvocation invocation) {
            if(invocation==null || invocation.asyncSubmissionOwnerIdentity()!=invocationNonce ||
                invocation.crewSource()==null || invocation.crewSource().source()!=source)
                throw new IllegalStateException("Observation belongs to another invocation");
            invocation.crewSource().requireInvocationCurrent();
        }
        /** Observer abandonment never proves whether an already-running transaction committed. */
        public void abandonDispatch(){pendingCapture.clear();}
        public synchronized RoomOperation beginOperation() {
            if(closed)throw new IllegalStateException("Observation owner is closed");
            return new RoomOperation(pin.fork());
        }
        public CodingCancellationObservationCheckpoint outcome(){return outcome;}
        @Override public void close() {
            synchronized(this){if(closed)return;closed=true;pendingCapture.clear();}
            pin.close();
        }
        public final class RoomOperation implements AutoCloseable {
            private final CodingJobOwnerLease.Pin child;
            private final AtomicBoolean finished=new AtomicBoolean();
            private RoomOperation(CodingJobOwnerLease.Pin child){this.child=child;}
            public void complete(CodingCancellationObservationCheckpoint checkpoint) {
                checkpoint.requireFor(Observation.this);
                synchronized(Observation.this){if(outcome==null)outcome=checkpoint;}
            }
            public void fail(Throwable ignored) { /* A failed observer cannot erase a committed observation. */ }
            @Override public void close(){if(finished.compareAndSet(false,true))child.close();}
        }
    }

    /** Owns one root pin. Original worker children outlive an interrupted observer, never its dispatch. */
    public static final class Request implements AutoCloseable {
        private final Issuer issuer;
        private final String requestId=UUID.randomUUID().toString(), sourceKey, sourceJson;
        private final CrewInvocationSource source;
        private final MemoryWriteGate.Ticket semanticTicket;
        private final CodingJobEvidence.Source semanticSource;
        private final CodingJobReservation reservation;
        private final CodingJobOwnerLease.Pin pin;
        private final Object invocationNonce;
        private final WeakReference<CrewInvocationWitness> pendingCapture;
        private final AtomicBoolean abandoned=new AtomicBoolean();
        private boolean closed;
        private volatile Result result;
        private volatile CodingCancellationCheckpoint intentOutcome, resultOutcome;
        private Request(Issuer issuer,EffectInvocation invocation,CodingJobReservation reservation,
                CodingJobOwnerLease.Pin pin,MemoryWriteGate.Ticket semanticTicket,CodingJobEvidence.Source semanticSource) {
            this.issuer=issuer;this.reservation=Objects.requireNonNull(reservation);this.pin=pin;
            CrewInvocationWitness witness=Objects.requireNonNull(invocation.crewSource(),"Live Crew cancellation source required");
            witness.requireInvocationCurrent();source=witness.source();
            this.semanticTicket=Objects.requireNonNull(semanticTicket,"Current cancellation ticket is required");
            this.semanticSource=requireSemanticSource(source,semanticSource);
            CodingJobEvidence.Binding binding=reservation.binding();
            if(!"project_jobs".equals(source.toolName) || !source.scopeIdentity.equals(binding.durableScopeIdentity) ||
                    !binding.owner.startsWith(source.executionSessionId+"/"+source.botId+"/"))
                throw new IllegalArgumentException("Cancellation source does not own the target scope");
            invocationNonce=invocation.asyncSubmissionOwnerIdentity();pendingCapture=new WeakReference<>(witness);
            sourceKey=CodingCancellation.sourceKey(source);
            sourceJson=CodingCancellation.sourceJson(source);
        }
        public String requestId(){return requestId;}
        public String sourceKey(){return sourceKey;}
        public CrewInvocationSource source(){return source;}
        public MemoryWriteGate.Ticket semanticTicket(){return semanticTicket;}
        /** Null means optional attribution lookup was unavailable; never a reconstructed or guessed root. */
        public CodingJobEvidence.Source semanticSource(){return semanticSource;}
        public String sourceJson(){return sourceJson;}
        public CodingJobReservation reservation(){return reservation;}
        public Issuer issuer(){return issuer;}
        public void requireCaptureCurrent() {
            pin.requireLive();
            CrewInvocationWitness witness=pendingCapture.get();
            if(witness==null || witness.source()!=source)throw new IllegalStateException("Cancellation capture is no longer live");
            witness.requireInvocationCurrent();
        }
        public void captureConfirmed(){pendingCapture.clear();}
        public void requireFor(EffectInvocation invocation) {
            if(invocation==null || invocation.asyncSubmissionOwnerIdentity()!=invocationNonce ||
                    invocation.crewSource()==null || invocation.crewSource().source()!=source)
                throw new IllegalStateException("Cancellation belongs to a different invocation");
            invocation.crewSource().requireInvocationCurrent();
        }
        public void abandonDispatch(){abandoned.set(true);}
        public void requireDispatchLive(){pin.requireLive();if(abandoned.get())throw new IllegalStateException("Cancellation dispatch was abandoned");}
        public synchronized RoomOperation beginOperation() {
            if(closed)throw new IllegalStateException("Cancellation request owner is closed");
            return new RoomOperation(pin.fork());
        }
        public CodingCancellationCheckpoint intentOutcome(){return intentOutcome;}
        public CodingCancellationCheckpoint resultOutcome(){return resultOutcome;}
        @Override public void close() {
            synchronized(this){if(closed)return;closed=true;pendingCapture.clear();}
            pin.close();
        }
        public final class RoomOperation implements AutoCloseable {
            private final CodingJobOwnerLease.Pin child;
            private final AtomicBoolean finished=new AtomicBoolean();
            private RoomOperation(CodingJobOwnerLease.Pin child){this.child=child;}
            public void complete(CodingCancellationCheckpoint checkpoint) {
                if(checkpoint.request()!=Request.this)throw new IllegalStateException("Foreign cancellation completion");
                synchronized(Request.this) {
                    if(checkpoint.stage()==CodingCancellationCheckpoint.Stage.INTENT) {
                        checkpoint.requireIntent(Request.this);
                        if(intentOutcome!=null && !intentOutcome.admission().equals(checkpoint.admission()))
                            throw new IllegalStateException("Cancellation intent outcome replaced");
                        if(intentOutcome==null)intentOutcome=checkpoint;
                    } else {
                        checkpoint.requireResult(result);
                        if(resultOutcome!=null && resultOutcome.result()!=checkpoint.result())
                            throw new IllegalStateException("Cancellation result outcome replaced");
                        if(resultOutcome==null)resultOutcome=checkpoint;
                    }
                }
            }
            public void fail(Throwable ignored) { /* Failure never erases an exact late committed outcome. */ }
            @Override public void close(){if(finished.compareAndSet(false,true))child.close();}
        }
    }
    /** Private immutable facts attested by the exact live manager issuer, not by journal decoding. */
    public static final class Result {
        private final Issuer issuer;
        private final Request request;
        private final CodingCancellationCheckpoint intent;
        private final Disposition disposition;
        private final CodingJobJournalStore.Commit commit;
        private Result(Issuer issuer,Request request,CodingCancellationCheckpoint intent,Disposition disposition,
                CodingJobJournalStore.Commit commit) {
            this.issuer=issuer;this.request=request;this.intent=intent;this.disposition=disposition;this.commit=commit;
        }
        public Request request(){return request;}
        public CodingCancellationCheckpoint intentCheckpoint(){return intent;}
        public Disposition disposition(){return disposition;}
        public CodingJobJournalStore.Commit cancellingCommit(){return commit;}
        public void requireIssuer(){
            if(request.issuer!=issuer || request.result!=this)throw new IllegalStateException("Foreign cancellation result");
            intent.requireIntent(request);
        }
        public void requireCurrentProducer(){requireIssuer();}
    }
}
