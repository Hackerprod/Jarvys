package com.jarvys.agent;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;

/** Per-invocation evidence, not an authority or a substitute for a gate's dispatch revalidation. */
public final class EffectInvocation {
    public enum Authorization { ALLOW, ASK_APPROVED, ASK_PENDING, DENY, UNSPECIFIED }
    public enum Outcome { OK, ERROR, UNCERTAIN, NOT_EXECUTED, DEFERRED }

    /** Identifiers and policy facts only: callers must never put arguments or credentials here. */
    public static final class Admission {
        public final Authorization authorization;
        public final String authority;
        public final String actionId;
        public final String revision;
        public final String origin;
        public final com.jarvys.agent.connectors.ConnectionStateCoordinator.Observation connectionObservation;

        public Admission(Authorization authorization, String authority, String actionId,
                         String revision, String origin) {
            this(authorization, authority, actionId, revision, origin, null);
        }
        public Admission(Authorization authorization, String authority, String actionId,
                         String revision, String origin,
                         com.jarvys.agent.connectors.ConnectionStateCoordinator.Observation connectionObservation) {
            this.connectionObservation = connectionObservation;
            this.authorization = Objects.requireNonNull(authorization, "authorization");
            this.authority = authority;
            this.actionId = actionId;
            this.revision = revision;
            this.origin = origin;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Admission)) return false;
            Admission that = (Admission) other;
            return authorization == that.authorization && Objects.equals(authority, that.authority)
                    && Objects.equals(actionId, that.actionId) && Objects.equals(revision, that.revision)
                    && Objects.equals(origin, that.origin)
                    && Objects.equals(connectionObservation, that.connectionObservation);
        }
        @Override public int hashCode() {
            return Objects.hash(authorization, authority, actionId, revision, origin, connectionObservation);
        }
    }

    public enum CompletionSemantics {
        EXTERNAL_EFFECT, LOCAL_RESOURCE_CREATED, COORDINATION_ACCEPTED,
        LOCAL_DELIVERY_CONFIRMED, EXISTING_REFERENCE
    }
    public enum EffectReferenceKind { WORKSPACE, ASSIGNMENT, WORKSPACE_INPUT, DELIVERY }

    /** Operational identifiers only, never tool text, private paths or a grant of authority. */
    public static final class EffectReference {
        public final EffectReferenceKind kind;
        public final String scopeId;
        public final String id;
        public EffectReference(EffectReferenceKind kind, String scopeId, String id) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.scopeId = identifier(scopeId);
            this.id = identifier(id);
        }
        private static String identifier(String value) {
            // Existing references are UUIDs or bounded prefixed digests, not arbitrary payloads.
            if (value == null || value.length() > 512 || !value.matches("[A-Za-z0-9_.:-]+"))
                throw new IllegalArgumentException("An operational identifier is required");
            return value;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof EffectReference)) return false;
            EffectReference that = (EffectReference) other;
            return kind == that.kind && scopeId.equals(that.scopeId) && id.equals(that.id);
        }
        @Override public int hashCode() { return Objects.hash(kind, scopeId, id); }
    }

    /** Constructed by trusted local producers, never deserialized from a provider response. */
    public static final class Receipt {
        public final Outcome outcome;
        public final Admission admission;
        public final CompletionSemantics completionSemantics;
        public final EffectReference reference;
        private final Object owner;
        /** Unbound metadata cannot establish a completion for any invocation. Prefer invocation.receipt(). */
        public Receipt(Outcome outcome, Admission admission) {
            this(outcome, admission, null, CompletionSemantics.EXTERNAL_EFFECT, null);
        }
        private Receipt(Outcome outcome, Admission admission, Object owner,
                        CompletionSemantics completionSemantics, EffectReference reference) {
            this.owner = owner;
            this.completionSemantics = Objects.requireNonNull(completionSemantics, "completionSemantics");
            this.reference = reference;
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            this.admission = admission;
        }
    }

    public enum AdministrativeKind { READ_COMPLETED, MEMORY_COMPLETED }

    /** Local read/memory completion, never evidence of an external WRITE effect. */
    public static final class AdministrativeReceipt {
        public final AdministrativeKind kind;
        private final Object owner;
        private AdministrativeReceipt(Object owner, AdministrativeKind kind) {
            this.owner = owner;
            this.kind = kind;
        }
    }

    public synchronized AdministrativeReceipt readCompleted() {
        return administrativeCompletion(AdministrativeKind.READ_COMPLETED);
    }

    public synchronized AdministrativeReceipt memoryCompleted() {
        return administrativeCompletion(AdministrativeKind.MEMORY_COMPLETED);
    }

    private AdministrativeReceipt administrativeCompletion(AdministrativeKind kind) {
        requireSynchronousCompletion();
        if (admission != null) throw new IllegalStateException("An admitted external effect is not an administrative completion");
        return new AdministrativeReceipt(identity, kind);
    }

    public synchronized boolean ownsReadCompletion(AdministrativeReceipt receipt) {
        return ownsAdministrativeCompletion(receipt, AdministrativeKind.READ_COMPLETED);
    }

    public synchronized boolean ownsMemoryCompletion(AdministrativeReceipt receipt) {
        return ownsAdministrativeCompletion(receipt, AdministrativeKind.MEMORY_COMPLETED);
    }

    private boolean ownsAdministrativeCompletion(AdministrativeReceipt receipt, AdministrativeKind kind) {
        return receipt != null && receipt.owner == identity && receipt.kind == kind && admission == null;
    }

    @FunctionalInterface public interface AdmissionWriter {
        /** Persist the exact intent -> started transition or throw, before any dispatch. */
        void persist(Admission admission);
    }

    private final Object identity = new Object();
    private final Object asyncSubmissionIdentity = new Object();
    private boolean asyncSubmission;
    private com.jarvys.agent.chatdb.CodingJobAcceptance acceptedSubmission;
    /** Opaque identity only, with no reverse reference to this invocation or permission by itself. */
    public Object asyncSubmissionOwnerIdentity() { return asyncSubmissionIdentity; }
    synchronized void beginAsyncSubmission() {
        if (sealed || asyncSubmission || admission != null || persisting)
            throw new IllegalStateException("Invocation cannot become an async submission");
        asyncSubmission = true;
    }
    /** The complete producer binds its durable administrative proof before it enqueues the worker. */
    public synchronized void bindAcceptedSubmission(com.jarvys.agent.chatdb.CodingJobAcceptance acceptance) {
        if (sealed || !asyncSubmission || admission != null || persisting || acceptedSubmission != null)
            throw new IllegalStateException("Async submission acceptance cannot be rebound");
        Objects.requireNonNull(acceptance, "acceptance").requireFor(this);
        acceptedSubmission = acceptance;
    }
    /** Historical fact after seal; not permission to dispatch or claim a terminal result. */
    public synchronized com.jarvys.agent.chatdb.CodingJobAcceptance acceptedSubmission() {
        return acceptedSubmission;
    }
    private void requireSynchronousCompletion() {
        if (asyncSubmission) throw new IllegalStateException("Async acceptance is not synchronous effect completion");
    }

    private final List<AdmissionWriter> writers = new ArrayList<>();
    private Admission decision;
    private Admission admission;
    private boolean persisting;
    private volatile boolean sealed;
    private volatile CentralInvocationWitness centralSource;
    private volatile CrewInvocationWitness crewSource;
    public CrewInvocationWitness crewSource() { return crewSource; }
    synchronized void bindCrewSource(CrewInvocationWitness witness) {
        if (sealed || admission != null || persisting || centralSource != null || crewSource != null || !witness.ownedBy(this))
            throw new IllegalStateException("Crew invocation source cannot be rebound");
        crewSource = witness;
    }
    void requireCrewSourceActive(CrewInvocationWitness witness) {
        if (sealed || crewSource != witness || !witness.ownedBy(this))
            throw new IllegalStateException("Crew invocation source is no longer active");
    }


    /** Read-only provenance; null only when no Central source provider was installed. */
    public synchronized CentralInvocationWitness centralSource() { return centralSource; }
    synchronized void bindCentralSource(CentralInvocationWitness witness) {
        if (sealed || admission != null || persisting || centralSource != null || crewSource != null || !witness.ownedBy(this))
            throw new IllegalStateException("Central source cannot be rebound or transferred");
        centralSource = witness;
    }
    void requireCentralSourceActive(CentralInvocationWitness witness) {
        if (sealed || centralSource != witness || !witness.ownedBy(this))
            throw new IllegalStateException("Central source invocation is no longer active");
    }

    public EffectInvocation(AdmissionWriter writer) {
        writers.add(Objects.requireNonNull(writer, "A durable admission writer is required"));
    }

    /** Attach another observer to this same invocation before its first admitted effect. */
    public synchronized void addAdmissionWriter(AdmissionWriter writer) {
        if (sealed || admission != null || persisting) throw new IllegalStateException("Admission already started");
        writers.add(Objects.requireNonNull(writer, "writer"));
    }

    /** Observed gate decision. Before admission a gate may legitimately replace ASK with ALLOW. */
    public synchronized void noteDecision(Admission observed) {
        if (sealed) throw new IllegalStateException("Invocation is terminal");
        Objects.requireNonNull(observed, "decision");
        if (persisting) throw new IllegalStateException("Admission persistence is in progress");
        if (admission != null && !admission.equals(observed))
            throw new IllegalStateException("Cannot mix authorization snapshots after admission");
        decision = observed;
    }

    /** Call immediately before dispatch, after the existing gate checks. Never grants permission itself. */
    public synchronized void admit(Admission observed) {
        requireSynchronousCompletion();
        if (sealed) throw new IllegalStateException("Invocation is terminal");
        Objects.requireNonNull(observed, "admission");
        if (observed.authorization != Authorization.ALLOW && observed.authorization != Authorization.ASK_APPROVED)
            throw new IllegalArgumentException("Only an authoritatively allowed effect can be admitted");
        if (persisting) throw new IllegalStateException("Reentrant admission persistence");
        if (admission != null) {
            if (!admission.equals(observed)) throw new IllegalStateException("Cannot mix authorization snapshots after admission");
            return;
        }
        decision = observed;
        persisting = true;
        try {
            for (AdmissionWriter writer : writers) writer.persist(observed);
            admission = observed;
        } finally {
            persisting = false;
        }
    }

    /** Called by the outer owner once dispatch has returned; late callbacks must not start another effect. */
    public synchronized void seal() {
        if (persisting) throw new IllegalStateException("Admission persistence is in progress");
        sealed = true;
    }
    public synchronized boolean admitted() { return admission != null; }
    public synchronized Admission admission() { return admission; }
    public synchronized Admission decision() { return decision; }
    public synchronized Receipt receipt(Outcome outcome) {
        requireSynchronousCompletion();
        return new Receipt(outcome, admission != null ? admission : decision, identity,
                CompletionSemantics.EXTERNAL_EFFECT, null);
    }

    public synchronized Receipt receipt(Outcome outcome, CompletionSemantics semantics, EffectReference reference) {
        requireSynchronousCompletion();
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(semantics, "semantics");
        if (!validSemantics(outcome, semantics, reference))
            throw new IllegalArgumentException("Completion semantics do not match the admitted local effect");
        return new Receipt(outcome, admission != null ? admission : decision, identity, semantics, reference);
    }

    private boolean validSemantics(Outcome outcome, CompletionSemantics semantics, EffectReference reference) {
        if (semantics == CompletionSemantics.EXTERNAL_EFFECT) return reference == null;
        if (reference == null) return false;
        if (semantics == CompletionSemantics.EXISTING_REFERENCE)
            return admission == null ? outcome == Outcome.NOT_EXECUTED : outcome == Outcome.UNCERTAIN;
        if (admission == null || outcome != Outcome.OK) return false;
        switch (semantics) {
            case LOCAL_RESOURCE_CREATED: return reference.kind == EffectReferenceKind.WORKSPACE;
            case COORDINATION_ACCEPTED: return reference.kind == EffectReferenceKind.ASSIGNMENT
                    || reference.kind == EffectReferenceKind.WORKSPACE_INPUT;
            case LOCAL_DELIVERY_CONFIRMED: return reference.kind == EffectReferenceKind.DELIVERY;
            default: return false;
        }
    }

    /**
     * Only producers covering every dispatch boundary may assert fullyInstrumented.
     * A success flag alone never establishes an effect result. Prior admission is never erased.
     */
    public synchronized Receipt resolve(CoreToolResult result, boolean fullyInstrumented) {
        requireSynchronousCompletion();
        Receipt explicit = result == null ? null : result.effectReceipt;
        if (explicit == null) return resolveFailure(fullyInstrumented);
        Admission actual = admission != null ? admission : decision;
        if (explicit.owner != identity || !Objects.equals(explicit.admission, actual)) return receipt(Outcome.UNCERTAIN);
        if (admission != null && (explicit.outcome == Outcome.NOT_EXECUTED || explicit.outcome == Outcome.DEFERRED))
            return receipt(Outcome.UNCERTAIN);
        if ((!result.success || admission == null) && explicit.outcome == Outcome.OK) return receipt(Outcome.UNCERTAIN);
        if (!validSemantics(explicit.outcome, explicit.completionSemantics, explicit.reference)) return receipt(Outcome.UNCERTAIN);
        return new Receipt(explicit.outcome, actual, identity, explicit.completionSemantics, explicit.reference);
    }

    public synchronized Receipt resolveFailure(boolean fullyInstrumented) {
        requireSynchronousCompletion();
        return receipt(admission != null || !fullyInstrumented ? Outcome.UNCERTAIN : Outcome.NOT_EXECUTED);
    }
}
