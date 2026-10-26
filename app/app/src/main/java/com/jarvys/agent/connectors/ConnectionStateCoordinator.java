package com.jarvys.agent.connectors;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Pure, NOT WIRED foundation. No permissions, runtime dispatch, Android persistence or Room proof.
 * A future process/root owner must serialize lifecycle/obsolete cleanup separately. A permit proves
 * only the local selection at issuance; it is not a future dispatch authorization.
 * Storage ports model candidate-safe writes and exact proofs separately. Passing these tests does
 * not establish that a SharedPreferences adapter implements the durable proof protocol.
 * Cross-process reattachment/retry of an uncertain request and legacy baseline import are deferred.
 * Restored candidates remain exact and denied, but an old request cannot restart activation. The
 * future adapter/recovery must resolve abandoned ACTIVATING to false without runtime.connect.
 */
public final class ConnectionStateCoordinator {
    public static final String LEGACY_DEFINITION = "bootstrap:legacy-observed";
    public enum Origin { REQUEST, BOOTSTRAP_OBSERVED, RECOVERY_RESTRICTIVE }
    public enum LegacyState { ABSENT, TRUE, FALSE, INVALID, RETIRED }
    public enum BootstrapStatus { READY, INCOMPLETE, SUPERSEDED }
    public enum BootstrapDiagnostic { INVALID_LEGACY_SOURCE, RETIRED_EXCLUDED, CURRENT_PROCESS_ACTIVATION,
        LEGACY_BASELINE_MISMATCH, CURRENT_VERSION_CONFLICT, PERSISTENCE_UNCONFIRMED }
    public static final class StorageUnconfirmedException extends Exception {
        public StorageUnconfirmedException(String message) { super(message); }
    }
    public static final class LegacyRecord {
        public final String recordId, connectorId, definitionGeneration, markerVersion;
        public final LegacyState state;
        public LegacyRecord(String recordId, String connectorId, String definitionGeneration, LegacyState state, String markerVersion) {
            this.recordId = required(recordId); this.connectorId = required(connectorId);
            this.definitionGeneration = definitionGeneration; this.state = Objects.requireNonNull(state); this.markerVersion = markerVersion;
            if ((state == LegacyState.TRUE || state == LegacyState.FALSE) && !LEGACY_DEFINITION.equals(definitionGeneration))
                throw new IllegalArgumentException("Legacy observation must use the reserved non-runtime identity");
        }
        public Boolean selected() { return state == LegacyState.TRUE ? Boolean.TRUE : state == LegacyState.FALSE ? Boolean.FALSE : null; }
    }
    public enum Kind { CONNECT, DEFAULT_CONNECT, SETTINGS_CONNECT, DISCONNECT, UNREGISTER, DISCONNECT_AFTER_PERMISSION_ABORT }
    public enum Phase { NONE, LEGACY_OBSERVED, ACTIVATING, READY }
    public enum Bootstrap { INITIALIZING, READY, FAILED }
    public enum Outcome { NOT_ATTEMPTED, CONFIRMED, UNCERTAIN }
    public enum Disposition { ACCEPTED, UNCHANGED, SUPERSEDED, CONFLICT }
    public enum Activation { NOT_REQUESTED, NOT_STARTED, RETURNED, FAILED }

    public interface Storage {
        StoredImage load() throws Exception;
        /** One atomic candidate-safe patch: current choice/phase/version, event and high-water. */
        Outcome writeCandidate(Mutation mutation) throws Exception;
        /** Exact proof ONLY; must not reapply current choice/phase. Called only after initial TRUE. */
        Outcome writeProof(Mutation mutation) throws Exception;
        /** Remove only these exact confirmed pending events and acknowledge this destination. */
        Outcome cleanup(ProjectionBatch batch, RetentionPlan plan) throws Exception;
        RetentionSnapshot retentionSnapshot();
        /** Cache-only binding; null means explicitly UNBOUND. No IO or callbacks here. */
        void bindDestination(DestinationBinding binding);
    }

    /** Issued outside the owner lock by the actual destination provider. Checks must be pure. */
    public interface DestinationEvidence {
        String generation();
        Object providerIdentity();
        ReentrantLock fenceIdentity();
        boolean isCurrentPure();
    }
    public enum DestinationState { UNBOUND, BOUND }
    public static final class DestinationBinding {
        private final DestinationEvidence evidence;
        private final String generation;
        private final long instanceVersion;
        private DestinationBinding(DestinationEvidence evidence, long version) {
            this.evidence = evidence; this.generation = required(evidence.generation()); this.instanceVersion = version;
        }
        public String generation() { return generation; }
        public long instanceVersion() { return instanceVersion; }
        public DestinationEvidence evidence() { return evidence; }
    }
    public static final class RetentionSnapshot {
        public final StoredImage image;
        public final Object ownerIdentity;
        public final long version;
        public RetentionSnapshot(StoredImage image, Object ownerIdentity, long version) {
            this.image = Objects.requireNonNull(image); this.ownerIdentity = Objects.requireNonNull(ownerIdentity);
            if (version < 0) throw new IllegalArgumentException("Invalid cache version");
            this.version = version;
        }
    }
    /** Exact keep-set, not a request to infer authority from arbitrary preference keys. */
    public static final class RetentionPlan {
        public final RetentionSnapshot expectedSnapshot;
        public final Set<String> keepProofVersions, keepCandidateVersions;
        public final Map<String, Mutation> carryCandidates;
        public final Map<String, Event> carryPending;
        public final Map<String, Long> reservedRevisions, intentHighWater;
        public final long highWater;
        private RetentionPlan(RetentionSnapshot snapshot, Set<String> proofs, Map<String, Mutation> candidates,
                              Map<String, Event> pending, ReadView view) {
            expectedSnapshot = snapshot;
            keepProofVersions = Collections.unmodifiableSet(new HashSet<>(proofs));
            keepCandidateVersions = Collections.unmodifiableSet(new HashSet<>(candidates.keySet()));
            carryCandidates = frozen(candidates); carryPending = frozen(pending);
            reservedRevisions = frozen(view.revisions); intentHighWater = frozen(view.intentHighWater);
            highWater = view.highWater;
        }
    }

    public static final class Command {
        public final String connectorId, definitionGeneration, operationId, parentOperationId;
        public final long expectedRevision, expectedIntentEpoch;
        public final Kind kind;
        public Command(String connectorId, String definitionGeneration, String operationId, Kind kind,
                       long expectedRevision, long expectedIntentEpoch, String parentOperationId) {
            this.connectorId = required(connectorId); this.definitionGeneration = required(definitionGeneration);
            this.operationId = required(operationId); this.kind = Objects.requireNonNull(kind);
            if (expectedRevision < 0 || expectedIntentEpoch < 0) throw new IllegalArgumentException("Negative revision");
            this.expectedRevision = expectedRevision; this.expectedIntentEpoch = expectedIntentEpoch;
            this.parentOperationId = parentOperationId;
        }
        public boolean selected() { return kind == Kind.CONNECT || kind == Kind.DEFAULT_CONNECT || kind == Kind.SETTINGS_CONNECT; }
        private List<Object> identity() { return Arrays.asList(connectorId, definitionGeneration, operationId, kind,
                expectedRevision, expectedIntentEpoch, parentOperationId); }
        @Override public boolean equals(Object other) { return other instanceof Command && identity().equals(((Command) other).identity()); }
        @Override public int hashCode() { return identity().hashCode(); }
    }

    public static final class Event {
        public final String storeEpoch, eventId, connectorId, operationId, parentOperationId, reason;
        public final long sequence, observedAtMs;
        public final Boolean previous;
        public final boolean selected;
        public Event(String storeEpoch, long sequence, String connectorId, Boolean previous, boolean selected,
                     String reason, String operationId, String parentOperationId, long observedAtMs) {
            this.storeEpoch = required(storeEpoch); if (sequence <= 0) throw new IllegalArgumentException("Invalid sequence");
            this.sequence = sequence; this.eventId = storeEpoch + ":" + sequence;
            this.connectorId = required(connectorId); this.previous = previous; this.selected = selected;
            this.reason = required(reason); this.operationId = required(operationId);
            this.parentOperationId = parentOperationId; this.observedAtMs = observedAtMs;
        }
        private List<Object> identity() { return Arrays.asList(storeEpoch, sequence, connectorId, previous, selected,
                reason, operationId, parentOperationId, observedAtMs); }
        @Override public boolean equals(Object other) { return other instanceof Event && identity().equals(((Event) other).identity()); }
        @Override public int hashCode() { return identity().hashCode(); }
    }

    /** Exact mutation identity binds phase as well as event: ACTIVATING proof never proves READY. */
    public static final class Mutation {
        public final Event event;
        public final String version, operationId, definitionGeneration;
        public final Command command;
        public final Origin origin;
        public final String bootstrapRecordId, activationOwnerProcessId;
        public final long revision, highWater;
        public final Phase phase;
        public final boolean selected, unchanged;
        public Mutation(Event event, String version, Command command,
                        long revision, long highWater, Phase phase, boolean selected, boolean unchanged) {
            this(event, version, command, revision, highWater, phase, selected, unchanged, Origin.REQUEST, null, null);
        }
        public Mutation(Event event, String version, Command command, long revision, long highWater,
                        Phase phase, boolean selected, boolean unchanged, Origin origin, String bootstrapRecordId,
                        String activationOwnerProcessId) {
            this.origin = Objects.requireNonNull(origin); this.bootstrapRecordId = bootstrapRecordId;
            this.activationOwnerProcessId = activationOwnerProcessId;
            this.event = Objects.requireNonNull(event); this.version = required(version);
            this.command = Objects.requireNonNull(command); this.operationId = command.operationId;
            this.definitionGeneration = command.definitionGeneration;
            if (!event.connectorId.equals(command.connectorId)) throw new IllegalArgumentException("Mutation connector mismatch");
            if (revision <= 0 || highWater < event.sequence) throw new IllegalArgumentException("Invalid mutation revision/reservation");
            this.revision = revision; this.highWater = highWater; this.phase = Objects.requireNonNull(phase);
            this.selected = selected; this.unchanged = unchanged;
            if (event.selected != selected || (!selected && phase != Phase.NONE)) throw new IllegalArgumentException("Incoherent choice");
        }
        private List<Object> identity() { return Arrays.asList(event, version, command,
                revision, highWater, phase, selected, unchanged, origin, bootstrapRecordId, activationOwnerProcessId); }
        @Override public boolean equals(Object other) { return other instanceof Mutation && identity().equals(((Mutation) other).identity()); }
        @Override public int hashCode() { return identity().hashCode(); }
    }

    public static final class UncertainCandidate {
        public final Mutation mutation;
        /** Process knowledge only. Restore without exact durable proof MUST reset this to false. */
        public final boolean choiceConfirmed;
        public UncertainCandidate(Mutation mutation, boolean choiceConfirmed) {
            this.mutation = Objects.requireNonNull(mutation); this.choiceConfirmed = choiceConfirmed;
        }
    }
    public static final class ConfirmedChoice {
        public final Mutation mutation;
        private ConfirmedChoice(Mutation mutation) { this.mutation = mutation; }
        public long revision() { return mutation.revision; }
        public boolean selected() { return mutation.selected; }
    }
    public static final class Observation {
        public final String connectorId, storeEpoch, operationId;
        public final long sequence;
        public final boolean selected;
        private Observation(Event event) { connectorId = event.connectorId; storeEpoch = event.storeEpoch; sequence = event.sequence;
            operationId = event.operationId; selected = event.selected; }
        public String eventId() { return storeEpoch + ":" + sequence; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Observation)) return false;
            Observation that = (Observation) other;
            return sequence == that.sequence && selected == that.selected && connectorId.equals(that.connectorId)
                    && storeEpoch.equals(that.storeEpoch) && operationId.equals(that.operationId);
        }
        @Override public int hashCode() { return Objects.hash(connectorId, storeEpoch, sequence, operationId, selected); }
    }

    /** Adapter input: raw current mutations and exact durable proofs are deliberately separate. */
    public static final class StoredImage {
        public final String storeEpoch, destinationGeneration, lastAcknowledgedGeneration;
        public final long highWater;
        public final Map<String, Mutation> current, proofs;
        public final Map<String, Event> pending;
        public final Map<String, UncertainCandidate> candidates;
        public final List<LegacyRecord> legacyRecords;
        public final Map<String, Long> reservedRevisions, intentHighWater;
        public StoredImage(String epoch, long highWater, String destinationGeneration, String lastAcknowledgedGeneration,
                           Map<String, Mutation> current, Map<String, Mutation> proofs,
                           Map<String, Event> pending, Map<String, UncertainCandidate> candidates) {
            this(epoch, highWater, destinationGeneration, lastAcknowledgedGeneration, current, proofs, pending, candidates, Collections.emptyList());
        }
        public StoredImage(String epoch, long highWater, String destinationGeneration, String lastAcknowledgedGeneration,
                           Map<String, Mutation> current, Map<String, Mutation> proofs, Map<String, Event> pending,
                           Map<String, UncertainCandidate> candidates, List<LegacyRecord> legacyRecords) {
            this(epoch, highWater, destinationGeneration, lastAcknowledgedGeneration, current, proofs, pending, candidates,
                    legacyRecords, Collections.emptyMap(), Collections.emptyMap());
        }
        public StoredImage(String epoch, long highWater, String destinationGeneration, String lastAcknowledgedGeneration,
                           Map<String, Mutation> current, Map<String, Mutation> proofs, Map<String, Event> pending,
                           Map<String, UncertainCandidate> candidates, List<LegacyRecord> legacyRecords,
                           Map<String, Long> reservedRevisions, Map<String, Long> intentHighWater) {
            this.legacyRecords = Collections.unmodifiableList(new ArrayList<>(legacyRecords));
            this.reservedRevisions = frozen(reservedRevisions); this.intentHighWater = frozen(intentHighWater);
            this.storeEpoch = required(epoch); this.destinationGeneration = destinationGeneration; // Legacy data, never a binding.

            this.lastAcknowledgedGeneration = lastAcknowledgedGeneration;
            if (highWater < 0) throw new IllegalArgumentException("Invalid high-water");
            this.highWater = highWater; this.current = frozen(current); this.proofs = frozen(proofs);
            this.pending = frozen(pending); this.candidates = frozen(candidates);
        }
        public static StoredImage empty(String epoch, String destination) {
            return new StoredImage(epoch, 0, destination, null, Collections.emptyMap(), Collections.emptyMap(),
                    Collections.emptyMap(), Collections.emptyMap());
        }
    }

    public static final class RequestToken {
        private final ConnectionStateCoordinator owner;
        private final long incarnation, intentEpoch;
        public final Command command;
        public final Disposition disposition;
        private final Mutation replay, baselineFactual, baselineProof;
        private final RequestToken baselineIntent;
        private boolean permissionAborted;
        private OffRestoration offRestoration;
        private final boolean abortable, baselineDenied;
        private boolean persistenceAttempted; // Monotonic, accessed under writer; marked before reserve/IO.
        private RequestToken(ConnectionStateCoordinator owner, long incarnation, long intentEpoch,
                             Command command, Disposition disposition) {
            this(owner, incarnation, intentEpoch, command, disposition, null);
        }
        private RequestToken(ConnectionStateCoordinator owner, long incarnation, long intentEpoch,
                             Command command, Disposition disposition, Mutation replay) {
            this(owner, incarnation, intentEpoch, command, disposition, replay, null);
        }
        private RequestToken(ConnectionStateCoordinator owner, long incarnation, long intentEpoch,
                             Command command, Disposition disposition, Mutation replay, ReadView baseline) {
            this.owner = owner; this.incarnation = incarnation; this.intentEpoch = intentEpoch;
            this.command = command; this.disposition = disposition; this.replay = replay;
            abortable = baseline != null;
            baselineIntent = baseline == null ? null : baseline.intents.get(command.connectorId);
            baselineFactual = baseline == null ? null : baseline.factualCurrent.get(command.connectorId);
            ConfirmedChoice proof = baseline == null ? null : baseline.choices.get(command.connectorId);
            baselineProof = proof == null ? null : proof.mutation;
            baselineDenied = baseline != null && baseline.denied.contains(command.connectorId);
        }
    }
    public enum AbortDisposition { ABORTED, SUPERSEDED, NOT_ABORTABLE, CONFLICT }
    public static final class AbortResult {
        public final AbortDisposition disposition;
        public final ReadView snapshot;
        private AbortResult(AbortDisposition disposition, ReadView snapshot) {
            this.disposition = disposition; this.snapshot = snapshot;
        }
    }
    public static final class OffRestoration {
        public enum Status { RESTORED, NOT_NEEDED, SUPERSEDED, INVALID }
        public final Status status;
        public final RequestToken request;
        public final ReadView snapshot;
        private OffRestoration(Status status, RequestToken request, ReadView snapshot) {
            this.status = status; this.request = request; this.snapshot = snapshot;
        }
    }
    public static final class ActivationPermit {
        private final RequestToken request;
        private final Mutation activating;
        private ActivationPermit(RequestToken request, Mutation activating) { this.request = request; this.activating = activating; }
    }
    public static final class CommitFacts {
        public final Outcome choice, proof;
        private CommitFacts(Outcome choice, Outcome proof) { this.choice = choice; this.proof = proof; }
    }
    public static final class Result {
        public final Disposition disposition;
        public final Activation activation;
        public final CommitFacts persistence;
        public final ActivationPermit permit;
        /** Obligation for this request/definition only; never permission to disconnect a newer runtime.
         * Lifecycle ownership, serialization and completion tracking belong to the future facade. */
        public final boolean cleanupRequired, provisional;
        /** Null only for a provisional result: no projection state has been observed. */
        public final Boolean projectionPending;
        public final ReadView snapshot;
        private Result(Disposition disposition, Activation activation, CommitFacts persistence,
                       ActivationPermit permit, boolean cleanupRequired, ReadView snapshot) {
            this(disposition, activation, persistence, permit, cleanupRequired, Objects.requireNonNull(snapshot), false);
        }
        private Result(Disposition disposition, Activation activation, CommitFacts persistence,
                       ActivationPermit permit, boolean cleanupRequired, ReadView snapshot, boolean provisional) {
            if (provisional != (snapshot == null)) throw new IllegalArgumentException("Only provisional results omit an observed snapshot");
            this.disposition = disposition; this.activation = activation; this.persistence = persistence;
            this.permit = permit; this.cleanupRequired = cleanupRequired; this.snapshot = snapshot; this.provisional = provisional;
            this.projectionPending = snapshot == null ? null : !snapshot.confirmedPending.isEmpty();
        }
    }
    public static final class ReadView {
        public final Bootstrap bootstrap;
        public final String storeEpoch, destinationGeneration, projectedGeneration;
        public final long highWater;
        public final boolean destinationReady;
        public final DestinationState destinationState;
        public final DestinationBinding binding;
        private final Map<String, Long> revisions, factualRevisions, intentHighWater;
        private final Map<String, Activation> activationOutcomes;
        private final long incarnation, destinationVersion;
        public final Map<String, ConfirmedChoice> choices, lastConfirmedSnapshots;
        public final Map<String, Mutation> factualCurrent;
        public final Map<String, RequestToken> intents;
        public final Set<String> denied, excluded;
        public final Map<String, BootstrapDiagnostic> diagnostics;
        public final Map<String, Event> confirmedPending;
        public final Map<String, UncertainCandidate> candidates;
        private ReadView(Builder b) {
            bootstrap = b.bootstrap; storeEpoch = b.epoch; destinationGeneration = b.destination;
            projectedGeneration = b.projected; highWater = b.highWater; incarnation = b.incarnation; destinationVersion = b.destinationVersion;
            activationOutcomes = frozen(b.activationOutcomes);
            binding = b.binding; destinationReady = binding != null; destinationState = destinationReady ? DestinationState.BOUND : DestinationState.UNBOUND; revisions = frozen(b.revisions); factualRevisions = frozen(b.factualRevisions); intentHighWater = frozen(b.intentHighWater);
            choices = frozen(b.choices); lastConfirmedSnapshots = frozen(b.lastConfirmedSnapshots); factualCurrent = frozen(b.factualCurrent); intents = frozen(b.intents); denied = Collections.unmodifiableSet(new HashSet<>(b.denied));
            confirmedPending = frozen(b.pending); candidates = frozen(b.candidates);
            excluded = Collections.unmodifiableSet(new HashSet<>(b.excluded)); diagnostics = frozen(b.diagnostics);
        }
        public boolean hasChoice(String id) { return choices.containsKey(id); }
        public boolean locallySelected(String id) {
            ConfirmedChoice c = choices.get(id);
            return bootstrap == Bootstrap.READY && c != null && c.mutation.equals(factualCurrent.get(id))
                    && c.selected() && !denied.contains(id)
                    && (c.mutation.phase == Phase.READY || c.mutation.phase == Phase.LEGACY_OBSERVED);
        }
        public long revision(String id) { return factualRevisions.getOrDefault(id, 0L); }
        public long intentEpoch(String id) { return intentHighWater.getOrDefault(id, 0L); }
        /** Deliberately on this immutable view, never a second coordinator read. Not an admission. */
        public Observation observation(String id) {
            ConfirmedChoice c = choices.get(id);
            return c == null || !c.mutation.equals(factualCurrent.get(id)) ? null : new Observation(c.mutation.event);
        }
    }
    public static final class ProjectionBatch {
        public final String sourceEpoch, targetGeneration;
        public final List<Event> events;
        public final DestinationBinding binding;
        private final long incarnation, destinationVersion;
        private ProjectionBatch(ReadView view, Collection<Event> events) {
            binding = view.binding; sourceEpoch = view.storeEpoch; targetGeneration = view.destinationGeneration; incarnation = view.incarnation; destinationVersion = view.destinationVersion;
            List<Event> sorted = new ArrayList<>(events); sorted.sort(Comparator.comparingLong(e -> e.sequence));
            this.events = Collections.unmodifiableList(sorted);
        }
    }
    public static final class Ack {
        public final String sourceEpoch, targetGeneration;
        public final List<Event> events;
        public Ack(String sourceEpoch, String targetGeneration, Collection<Event> events) {
            this.sourceEpoch = required(sourceEpoch); this.targetGeneration = required(targetGeneration);
            this.events = Collections.unmodifiableList(new ArrayList<>(events));
        }
    }

    public static final class BootstrapAction {
        public final Mutation mutation;
        public final String expectedCurrentVersion;
        private final Mutation expectedCurrent;
        private BootstrapAction(Mutation mutation, Mutation expectedCurrent) {
            this.mutation = mutation; this.expectedCurrent = expectedCurrent;
            this.expectedCurrentVersion = expectedCurrent == null ? null : expectedCurrent.version;
        }
    }
    public static final class BootstrapPlan {
        private final ConnectionStateCoordinator owner;
        private final long incarnation;
        public final String storeEpoch;
        public final List<BootstrapAction> actions;
        public final Map<String, BootstrapDiagnostic> diagnostics;
        private final boolean blocked;
        private final Map<String, Mutation> sourceCurrent;
        private BootstrapPlan(ConnectionStateCoordinator owner, ReadView view, List<BootstrapAction> actions,
                              Map<String, BootstrapDiagnostic> diagnostics, boolean blocked, Map<String, Mutation> sourceCurrent) {
            this.owner = owner; this.incarnation = view.incarnation; this.storeEpoch = view.storeEpoch;
            this.actions = Collections.unmodifiableList(new ArrayList<>(actions)); this.diagnostics = frozen(diagnostics);
            this.blocked = blocked; this.sourceCurrent = frozen(sourceCurrent);
        }
    }
    public static final class BootstrapAttempt {
        public final BootstrapStatus status;
        public final ReadView snapshot;
        public final Map<String, BootstrapDiagnostic> diagnostics;
        public final Map<String, CommitFacts> persistence;
        private BootstrapAttempt(BootstrapStatus status, ReadView snapshot, Map<String, CommitFacts> persistence) {
            this.status = status; this.snapshot = snapshot; this.diagnostics = snapshot.diagnostics;
            this.persistence = frozen(persistence);
        }
    }

    private static final class Builder {
        Bootstrap bootstrap = Bootstrap.INITIALIZING;
        String epoch = "", destination, projected;
        long highWater, incarnation, destinationVersion;
        DestinationBinding binding;
        Map<String, Activation> activationOutcomes = new HashMap<>();
        Map<String, Long> revisions = new LinkedHashMap<>(), factualRevisions = new LinkedHashMap<>(), intentHighWater = new LinkedHashMap<>();
        Map<String, ConfirmedChoice> choices = new LinkedHashMap<>(), lastConfirmedSnapshots = new LinkedHashMap<>();
        Map<String, Mutation> factualCurrent = new LinkedHashMap<>();
        Map<String, RequestToken> intents = new LinkedHashMap<>();
        Set<String> denied = new HashSet<>(), excluded = new HashSet<>();
        Map<String, BootstrapDiagnostic> diagnostics = new LinkedHashMap<>();
        Map<String, Event> pending = new LinkedHashMap<>();
        Map<String, UncertainCandidate> candidates = new LinkedHashMap<>();
        Map<String, Event> restoredEvents = new HashMap<>();
        Map<String, Mutation> restoredVersions = new HashMap<>();
        Map<String, Command> restoredCommands = new HashMap<>();
        Map<String, Mutation> newestProof = new HashMap<>();
        Builder() {}
        Builder(ReadView v) {
            bootstrap = v.bootstrap; epoch = v.storeEpoch; destination = v.destinationGeneration;
            projected = v.projectedGeneration; highWater = v.highWater; incarnation = v.incarnation; destinationVersion = v.destinationVersion;
            activationOutcomes.putAll(v.activationOutcomes);
            binding = v.binding; revisions.putAll(v.revisions); factualRevisions.putAll(v.factualRevisions); intentHighWater.putAll(v.intentHighWater);
            choices.putAll(v.choices); lastConfirmedSnapshots.putAll(v.lastConfirmedSnapshots); factualCurrent.putAll(v.factualCurrent); intents.putAll(v.intents); denied.addAll(v.denied); excluded.addAll(v.excluded); diagnostics.putAll(v.diagnostics);
            pending.putAll(v.confirmedPending); candidates.putAll(v.candidates);
        }
        ReadView build() { return new ReadView(this); }
    }
    private final Storage storage;
    private final LongSupplier clock;
    private final String processIdentity;
    private BootstrapPlan activeBootstrap;
    private final ReentrantLock writer = new ReentrantLock(), resetFence;
    private final Object destinationProviderIdentity;
    private final AtomicReference<ReadView> latest = new AtomicReference<>(new Builder().build());
    /** Pure-test convenience. Operational owners must supply their process-lifetime identity. */
    public ConnectionStateCoordinator(Storage storage, LongSupplier clock) { this(storage, clock, UUID.randomUUID().toString()); }
    public ConnectionStateCoordinator(Storage storage, LongSupplier clock, String processIdentity) {
        this(storage, clock, processIdentity, new ReentrantLock(), new Object());
    }
    public ConnectionStateCoordinator(Storage storage, LongSupplier clock, String processIdentity,
                                      ReentrantLock sharedFence, Object expectedProviderIdentity) {
        this.storage = Objects.requireNonNull(storage); this.clock = Objects.requireNonNull(clock);
        this.processIdentity = required(processIdentity); this.resetFence = Objects.requireNonNull(sharedFence);
        this.destinationProviderIdentity = Objects.requireNonNull(expectedProviderIdentity);
    }
    public ReadView read() { return latest.get(); }
    /** Read-only foundation loader; operational owners use prepareBootstrap/applyBootstrap. */
    public void initialize() {
        writer.lock();
        try {
            if (read().bootstrap != Bootstrap.INITIALIZING) throw new IllegalStateException("Already initialized");
            try { latest.set(restore(storage.load(), read().incarnation + 1)); }
            catch (Exception failure) {
                Builder b = new Builder(read()); b.bootstrap = Bootstrap.FAILED; latest.set(b.build());
                throw new IllegalStateException("Connection bootstrap failed", failure);
            }
        } finally { writer.unlock(); }
    }

    /** Unique startup planning. Storage supplies legacy records from the SAME immutable load. */
    public BootstrapPlan prepareBootstrap() throws StorageUnconfirmedException {
        writer.lock();
        try {
            if (activeBootstrap != null && activeBootstrap.incarnation == read().incarnation) return activeBootstrap;
            if (read().bootstrap != Bootstrap.INITIALIZING) throw new IllegalStateException("Bootstrap is not initializable");
            try {
                StoredImage image = storage.load();
                Builder b = new Builder(restore(image, read().incarnation + 1)); b.bootstrap = Bootstrap.INITIALIZING;
                List<BootstrapAction> actions = new ArrayList<>();
                Map<String, LegacyRecord> records = new TreeMap<>();
                for (LegacyRecord record : image.legacyRecords) {
                    if (records.put(record.connectorId, record) != null) throw new IllegalArgumentException("Duplicate legacy source");
                    if (record.state == LegacyState.RETIRED) {
                        b.excluded.add(record.connectorId); b.denied.add(record.connectorId);
                        b.diagnostics.put(record.connectorId, BootstrapDiagnostic.RETIRED_EXCLUDED);
                    }
                }
                boolean blocked = false;
                for (Map.Entry<String, Mutation> entry : new TreeMap<>(image.current).entrySet()) {
                    String id = entry.getKey(); Mutation current = entry.getValue();
                    if (b.excluded.contains(id)) continue;
                    boolean proven = current.equals(image.proofs.get(current.version));
                    if (current.phase == Phase.ACTIVATING && processIdentity.equals(current.activationOwnerProcessId)) {
                        b.denied.add(id); b.diagnostics.put(id, BootstrapDiagnostic.CURRENT_PROCESS_ACTIVATION); blocked = true; continue;
                    }
                    if (laterIntervention(image, current)) {
                        b.factualCurrent.remove(id); b.denied.add(id);
                        b.diagnostics.put(id, BootstrapDiagnostic.CURRENT_VERSION_CONFLICT); blocked = true; continue;
                    }
                    if (proven && current.phase != Phase.ACTIVATING) continue;
                    Mutation repair;
                    if (current.origin == Origin.BOOTSTRAP_OBSERVED && !proven) {
                        LegacyRecord source = records.get(id);
                        if (source == null || source.selected() == null || source.selected() != current.selected
                                || !Objects.equals(source.recordId, current.bootstrapRecordId)
                                || !Objects.equals(source.markerVersion, current.version)) {
                            b.denied.add(id); b.diagnostics.put(id, BootstrapDiagnostic.LEGACY_BASELINE_MISMATCH); blocked = true; continue;
                        }
                        repair = current; // Narrow baseline retry: exact source, marker, version, event and timestamp.
                    } else if (current.origin == Origin.RECOVERY_RESTRICTIVE && !current.selected) {
                        repair = current; // Restarted restrictive repair may retry its exact candidate, never a CONNECT token.
                    } else {
                        String reason = current.phase == Phase.ACTIVATING ? "ACTIVATION_INTERRUPTED"
                                : current.selected ? "PERSISTENCE_RECOVERY_UNCONFIRMED" : "DISCONNECTION_PERSISTENCE_RECOVERY";
                        repair = bootstrapMutation(b, id, current.definitionGeneration, false, reason,
                                "recovery:" + current.version, current, null, Origin.RECOVERY_RESTRICTIVE);
                    }
                    addBootstrapAction(b, actions, repair, current);
                }
                Set<String> orphanIds = new HashSet<>();
                Map<String, Mutation> orphans = new TreeMap<>();
                for (UncertainCandidate candidate : image.candidates.values()) {
                    Mutation m = candidate.mutation; String id = m.event.connectorId;
                    if (image.current.containsKey(id) || b.excluded.contains(id) || m.equals(image.proofs.get(m.version))) continue;
                    Mutation prior = orphans.get(id);
                    if (prior != null && mutationOrder(prior, m) == 0 && !prior.equals(m))
                        throw new IllegalArgumentException("Ambiguous orphan reservation order");
                    if (prior == null || mutationOrder(m, prior) > 0) orphans.put(id, m);
                }
                for (Mutation orphan : orphans.values()) {
                    String id = orphan.event.connectorId; orphanIds.add(id); b.denied.add(id);
                    Mutation known = b.newestProof.get(id);
                    if (known != null) {
                        b.diagnostics.put(id, BootstrapDiagnostic.CURRENT_VERSION_CONFLICT); blocked = true; continue;
                    }
                    Mutation repair = bootstrapMutation(b, id, orphan.definitionGeneration, false,
                            "PERSISTENCE_RECOVERY_UNCONFIRMED", "orphan:" + orphan.version, orphan, null, Origin.RECOVERY_RESTRICTIVE);
                    addBootstrapAction(b, actions, repair, null);
                }
                for (LegacyRecord record : records.values()) {
                    String id = record.connectorId;
                    if (b.excluded.contains(id) || image.current.containsKey(id) || orphanIds.contains(id)) continue;
                    if (record.state == LegacyState.INVALID) {
                        b.denied.add(id); b.diagnostics.put(id, BootstrapDiagnostic.INVALID_LEGACY_SOURCE); continue;
                    }
                    if (record.state == LegacyState.ABSENT) continue;
                    if (record.markerVersion != null || hasProtocolEvidence(image, id)) {
                        b.denied.add(id); b.diagnostics.put(id, BootstrapDiagnostic.LEGACY_BASELINE_MISMATCH); blocked = true; continue;
                    }
                    Mutation imported = bootstrapMutation(b, id, LEGACY_DEFINITION, record.state == LegacyState.TRUE,
                            "BOOTSTRAP_OBSERVED_LOCAL_CHOICE", "legacy:" + record.recordId, null, record.recordId, Origin.BOOTSTRAP_OBSERVED);
                    addBootstrapAction(b, actions, imported, null);
                }
                ReadView planned = b.build(); latest.set(planned);
                activeBootstrap = new BootstrapPlan(this, planned, actions, b.diagnostics, blocked, image.current);
                return activeBootstrap;
            } catch (StorageUnconfirmedException retryable) { throw retryable; }
            catch (Exception corrupt) {
                Builder b = new Builder(read()); b.bootstrap = Bootstrap.FAILED; latest.set(b.build());
                throw new IllegalStateException("Bootstrap source is invalid", corrupt);
            }
        } finally { writer.unlock(); }
    }

    /** No lifecycle and no permits. UNKNOWN keeps the whole bootstrap INITIALIZING for exact retry. */
    public BootstrapAttempt applyBootstrap(BootstrapPlan plan) {
        writer.lock();
        try {
            if (plan == null || plan.owner != this || plan != activeBootstrap || plan.incarnation != read().incarnation
                    || !plan.storeEpoch.equals(read().storeEpoch))
                return new BootstrapAttempt(BootstrapStatus.SUPERSEDED, read(), Collections.emptyMap());
            if (read().bootstrap == Bootstrap.READY)
                return new BootstrapAttempt(BootstrapStatus.READY, read(), Collections.emptyMap());
            if (read().bootstrap != Bootstrap.INITIALIZING)
                return new BootstrapAttempt(BootstrapStatus.SUPERSEDED, read(), Collections.emptyMap());
            Map<String, CommitFacts> results = new LinkedHashMap<>(); boolean complete = !plan.blocked;
            for (BootstrapAction action : plan.actions) {
                Mutation m = action.mutation;
                if (!Objects.equals(action.expectedCurrent, plan.sourceCurrent.get(m.event.connectorId)))
                    throw new IllegalStateException("Bootstrap source identity mismatch");
                ReadView v = read(); ConfirmedChoice proven = v.choices.get(m.event.connectorId);
                if (proven != null && proven.mutation.equals(m) && m.equals(v.factualCurrent.get(m.event.connectorId))) {
                    results.put(m.version, new CommitFacts(Outcome.CONFIRMED, Outcome.CONFIRMED)); continue;
                }
                UncertainCandidate candidate = v.candidates.get(m.version);
                if (candidate == null || !candidate.mutation.equals(m)) throw new IllegalStateException("Bootstrap candidate lost identity");
                boolean known = candidate.choiceConfirmed;
                Outcome initial = known ? Outcome.CONFIRMED : call(() -> storage.writeCandidate(m));
                Outcome proof = initial == Outcome.CONFIRMED ? call(() -> storage.writeProof(m)) : Outcome.NOT_ATTEMPTED;
                results.put(m.version, new CommitFacts(initial, proof));
                complete &= proof == Outcome.CONFIRMED;
                for (;;) {
                    ReadView old = read();
                    if (old.incarnation != plan.incarnation) return new BootstrapAttempt(BootstrapStatus.SUPERSEDED, old, results);
                    Builder b = new Builder(old);
                    if (!known) {
                        if (initial == Outcome.CONFIRMED) b.factualCurrent.put(m.event.connectorId, m);
                        else b.factualCurrent.remove(m.event.connectorId);
                    }
                    if (initial == Outcome.CONFIRMED)
                        b.factualRevisions.put(m.event.connectorId, Math.max(b.factualRevisions.getOrDefault(m.event.connectorId, 0L), m.revision));
                    if (proof == Outcome.CONFIRMED) {
                        b.choices.put(m.event.connectorId, new ConfirmedChoice(m)); b.pending.put(m.event.eventId, m.event);
                        b.candidates.remove(m.version); rememberSnapshot(b, m); b.denied.remove(m.event.connectorId);
                        b.diagnostics.remove(m.event.connectorId);
                    } else {
                        b.candidates.put(m.version, new UncertainCandidate(m, initial == Outcome.CONFIRMED));
                        b.denied.add(m.event.connectorId); b.diagnostics.put(m.event.connectorId, BootstrapDiagnostic.PERSISTENCE_UNCONFIRMED);
                    }
                    if (latest.compareAndSet(old, b.build())) break;
                }
            }
            if (complete) {
                for (;;) { ReadView old = read(); Builder b = new Builder(old); b.bootstrap = Bootstrap.READY;
                    if (latest.compareAndSet(old, b.build())) break; }
            }
            return new BootstrapAttempt(complete ? BootstrapStatus.READY : BootstrapStatus.INCOMPLETE, read(), results);
        } finally { writer.unlock(); }
    }
    private void addBootstrapAction(Builder b, List<BootstrapAction> actions, Mutation mutation, Mutation current) {
        actions.add(new BootstrapAction(mutation, current)); b.denied.add(mutation.event.connectorId);
        b.highWater = Math.max(b.highWater, mutation.highWater);
        b.revisions.put(mutation.event.connectorId, Math.max(b.revisions.getOrDefault(mutation.event.connectorId, 0L), mutation.revision));
        b.intentHighWater.put(mutation.event.connectorId, Math.max(b.intentHighWater.getOrDefault(mutation.event.connectorId, 0L),
                Math.addExact(mutation.command.expectedIntentEpoch, 1)));
        b.candidates.put(mutation.version, new UncertainCandidate(mutation, false));
    }
    private Mutation bootstrapMutation(Builder b, String id, String definition, boolean selected, String reason,
                                       String identity, Mutation current, String recordId, Origin origin) {
        String seed = b.epoch.length() + ":" + b.epoch + id.length() + ":" + id + identity.length() + ":" + identity;
        String operation = "bootstrap-" + UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long seq = Math.addExact(b.highWater, 1), revision = Math.addExact(b.revisions.getOrDefault(id, 0L), 1);
        String parent = current == null ? null : current.operationId;
        Command command = new Command(id, definition, operation, selected ? Kind.CONNECT : Kind.DISCONNECT,
                b.factualRevisions.getOrDefault(id, 0L), b.intentHighWater.getOrDefault(id, 0L), parent);
        Mutation factual = b.factualCurrent.get(id);
        Event event = new Event(b.epoch, seq, id, factual == null ? null : factual.selected, selected, reason, operation, parent, clock.getAsLong());
        return new Mutation(event, "bootstrap/" + operation, command, revision, seq,
                selected ? Phase.LEGACY_OBSERVED : Phase.NONE, selected, false, origin, recordId, null);
    }
    private boolean laterIntervention(StoredImage image, Mutation current) {
        for (Mutation m : image.proofs.values()) if (m.event.connectorId.equals(current.event.connectorId) && mutationOrder(m, current) > 0) return true;
        for (UncertainCandidate c : image.candidates.values()) if (c.mutation.event.connectorId.equals(current.event.connectorId)
                && (mutationOrder(c.mutation, current) > 0 || c.mutation.event.sequence > current.event.sequence)) return true;
        return false;
    }
    private boolean hasProtocolEvidence(StoredImage image, String id) {
        for (Mutation m : image.proofs.values()) if (m.event.connectorId.equals(id)) return true;
        for (UncertainCandidate c : image.candidates.values()) if (c.mutation.event.connectorId.equals(id)) return true;
        for (Event e : image.pending.values()) if (e.connectorId.equals(id)) return true;
        return false;
    }

    public RequestToken beginIntent(Command command) {
        Objects.requireNonNull(command);
        if (command.kind == Kind.DISCONNECT_AFTER_PERMISSION_ABORT) return rejected(command, read(), Disposition.CONFLICT);
        return beginIntentInternal(command);
    }
    private RequestToken beginIntentInternal(Command command) {
        for (;;) {
            ReadView old = read(); RequestToken previous = old.intents.get(command.connectorId);
            if (old.bootstrap != Bootstrap.READY || old.excluded.contains(command.connectorId)) return rejected(command, old, Disposition.SUPERSEDED);
            if (LEGACY_DEFINITION.equals(command.definitionGeneration)) return rejected(command, old, Disposition.CONFLICT);
            if (previous != null && previous.command.operationId.equals(command.operationId)) {
                return previous.command.equals(command) ? previous : rejected(command, old, Disposition.CONFLICT);
            }
            for (RequestToken t : old.intents.values()) if (t.command.operationId.equals(command.operationId))
                return rejected(command, old, Disposition.CONFLICT);
            for (UncertainCandidate candidate : old.candidates.values()) {
                if (candidate.mutation.operationId.equals(command.operationId) && !candidate.mutation.command.equals(command))
                    return rejected(command, old, Disposition.CONFLICT);
            }
            for (ConfirmedChoice choice : old.choices.values()) {
                if (choice.mutation.operationId.equals(command.operationId)) {
                    if (!choice.mutation.command.equals(command)) return rejected(command, old, Disposition.CONFLICT);
                    if (previous != null) return rejected(command, old, Disposition.SUPERSEDED);
                    return new RequestToken(this, old.incarnation, old.intentEpoch(command.connectorId), command,
                            Disposition.UNCHANGED, choice.mutation);
                }
            }
            if (command.expectedRevision != old.revision(command.connectorId)
                    || command.expectedIntentEpoch != old.intentEpoch(command.connectorId)
                    || (command.kind == Kind.DEFAULT_CONNECT && (old.hasChoice(command.connectorId) || old.denied.contains(command.connectorId))))
                return rejected(command, old, Disposition.SUPERSEDED);
            RequestToken token = new RequestToken(this, old.incarnation, old.intentEpoch(command.connectorId) + 1, command, Disposition.ACCEPTED, null, old);
            Builder b = new Builder(old); b.intents.put(command.connectorId, token); b.intentHighWater.put(command.connectorId, token.intentEpoch); b.activationOutcomes.remove(command.connectorId); b.denied.add(command.connectorId);
            if (latest.compareAndSet(old, b.build())) return token;
        }
    }
    private RequestToken rejected(Command c, ReadView v, Disposition d) { return new RequestToken(this, v.incarnation, 0, c, d); }
    public boolean isCurrent(RequestToken token) { return current(read(), token); }
    private boolean current(ReadView v, RequestToken t) {
        return t != null && t.owner == this && t.disposition == Disposition.ACCEPTED && t.incarnation == v.incarnation
                && v.intents.get(t.command.connectorId) == t;
    }

    /** Cancel only an untouched current permission request. Never resurrect an earlier intent or permit. */
    public AbortResult abortBeforePersistence(RequestToken token) {
        writer.lock();
        try {
            if (token == null || token.owner != this) return new AbortResult(AbortDisposition.CONFLICT, read());
            for (;;) {
                ReadView v = read();
                if (!current(v, token)) return new AbortResult(AbortDisposition.SUPERSEDED, v);
                String id = token.command.connectorId; ConfirmedChoice proof = v.choices.get(id);
                if (!token.command.selected() || !token.abortable || token.persistenceAttempted || v.activationOutcomes.containsKey(id)
                        || !Objects.equals(token.baselineFactual, v.factualCurrent.get(id))
                        || !Objects.equals(token.baselineProof, proof == null ? null : proof.mutation))
                    return new AbortResult(AbortDisposition.NOT_ABORTABLE, v);
                Builder b = new Builder(v); b.intents.remove(id);
                if (token.baselineDenied) b.denied.add(id); else b.denied.remove(id);
                ReadView aborted = b.build();
                if (latest.compareAndSet(v, aborted)) { token.permissionAborted = true; return new AbortResult(AbortDisposition.ABORTED, aborted); }
            }
        } finally { writer.unlock(); }
    }

    /** Recover the exact restrictive obligation displaced by an untouched, subsequently aborted permission request. */
    public OffRestoration restoreOffAfterPermissionAbort(RequestToken originalOff, RequestToken abortedOn) {
        writer.lock();
        try {
            if (originalOff == null || abortedOn == null || originalOff.owner != this || abortedOn.owner != this
                    || (originalOff.command.kind != Kind.DISCONNECT && originalOff.command.kind != Kind.UNREGISTER)
                    || !abortedOn.permissionAborted || abortedOn.persistenceAttempted
                    || abortedOn.baselineIntent != originalOff)
                return new OffRestoration(OffRestoration.Status.INVALID, null, read());
            if (abortedOn.offRestoration != null) {
                OffRestoration saved = abortedOn.offRestoration;
                ReadView currentView = read(); String savedId = abortedOn.command.connectorId;
                ConfirmedChoice currentProof = currentView.choices.get(savedId);
                Mutation currentFact = currentView.factualCurrent.get(savedId);
                boolean same = saved.request == null
                        ? currentView.incarnation == abortedOn.incarnation && currentView.intentEpoch(savedId) == abortedOn.intentEpoch
                            && !currentView.intents.containsKey(savedId) && currentFact != null && !currentFact.selected
                            && currentProof != null && currentFact.equals(currentProof.mutation)
                            && Objects.equals(saved.snapshot.factualCurrent.get(savedId), currentFact)
                            && Objects.equals(abortedOn.baselineProof, currentProof.mutation)
                        : current(currentView, saved.request);
                return same ? saved : new OffRestoration(OffRestoration.Status.SUPERSEDED, null, read());
            }
            for (;;) {
                ReadView v = read(); String id = abortedOn.command.connectorId;
                ConfirmedChoice proof = v.choices.get(id); Mutation factual = v.factualCurrent.get(id);
                if (v.bootstrap != Bootstrap.READY || v.incarnation != abortedOn.incarnation
                        || originalOff.incarnation != v.incarnation || v.intents.containsKey(id)
                        || v.intentEpoch(id) != abortedOn.intentEpoch
                        || !Objects.equals(abortedOn.baselineFactual, factual)
                        || !Objects.equals(abortedOn.baselineProof, proof == null ? null : proof.mutation))
                    return new OffRestoration(OffRestoration.Status.SUPERSEDED, null, v);
                if (factual != null && !factual.selected && proof != null && factual.equals(proof.mutation)) {
                    if (!latest.compareAndSet(v, v)) continue;
                    OffRestoration result = new OffRestoration(OffRestoration.Status.NOT_NEEDED, null, v);
                    abortedOn.offRestoration = result; return result;
                }
                if (!abortedOn.baselineDenied || !v.denied.contains(id))
                    return new OffRestoration(OffRestoration.Status.INVALID, null, v);
                String seed = v.storeEpoch + "\n" + originalOff.command.operationId + "\n" + abortedOn.command.operationId;
                String operation = "restore-off-" + UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Command command = new Command(id, originalOff.command.definitionGeneration, operation,
                        Kind.DISCONNECT_AFTER_PERMISSION_ABORT, v.revision(id), v.intentEpoch(id), originalOff.command.operationId);
                RequestToken child = beginIntentInternal(command);
                if (child.disposition != Disposition.ACCEPTED)
                    return new OffRestoration(OffRestoration.Status.SUPERSEDED, null, read());
                OffRestoration result = new OffRestoration(OffRestoration.Status.RESTORED, child, read());
                abortedOn.offRestoration = result; return result;
            }
        } finally { writer.unlock(); }
    }

    /** Provisional local revocation only: never a durable choice, effect admission, or completed transition. */
    public static Result pendingRestrictive(Kind kind) {
        if (kind != Kind.DISCONNECT && kind != Kind.UNREGISTER)
            throw new IllegalArgumentException("Only restrictive requests can be deferred before bootstrap");
        return new Result(Disposition.ACCEPTED, Activation.NOT_REQUESTED,
                new CommitFacts(Outcome.NOT_ATTEMPTED, Outcome.NOT_ATTEMPTED), null, true, null, true);
    }

    public Result persistSelection(RequestToken token) {
        writer.lock();
        try {
            ReadView v = read();
            if (token == null || token.owner != this) return noAttempt(Disposition.CONFLICT, Activation.NOT_STARTED, false);
            if (token.replay != null) {
                if (token.incarnation != v.incarnation || v.intents.containsKey(token.command.connectorId)
                        || !v.choices.containsKey(token.command.connectorId)
                        || !token.replay.equals(v.choices.get(token.command.connectorId).mutation))
                    return noAttempt(Disposition.SUPERSEDED, Activation.NOT_STARTED, false);
                return result(Disposition.UNCHANGED, Activation.NOT_REQUESTED, Outcome.CONFIRMED, Outcome.CONFIRMED, null, false);
            }
            if (token.disposition != Disposition.ACCEPTED) return noAttempt(token.disposition, Activation.NOT_STARTED, false);
            UncertainCandidate retry = findCandidate(v, token, "selection/");
            if (!current(v, token)) {
                if (retry != null && retry.choiceConfirmed && token.incarnation == v.incarnation)
                    return persist(token, retry.mutation, true, Activation.NOT_STARTED);
                return noAttempt(Disposition.SUPERSEDED, Activation.NOT_STARTED, false);
            }
            ConfirmedChoice existing = v.choices.get(token.command.connectorId);
            if (existing != null && existing.mutation.operationId.equals(token.command.operationId)) {
                if (!existing.mutation.command.equals(token.command)) return noAttempt(Disposition.CONFLICT, Activation.NOT_STARTED, false);
                return confirmedReplay(token, existing.mutation);
            }
            if (retry != null && retry.choiceConfirmed) return persist(token, retry.mutation, true, Activation.NOT_STARTED);
            if (token.command.expectedRevision != v.revision(token.command.connectorId))
                return noAttempt(Disposition.SUPERSEDED, Activation.NOT_STARTED, !token.command.selected());
            if (retry != null) return persist(token, retry.mutation, false, Activation.NOT_STARTED);
            Mutation factual = v.factualCurrent.get(token.command.connectorId);
            boolean exactProof = factual != null && existing != null && factual.equals(existing.mutation);
            boolean unchanged = factual != null && factual.definitionGeneration.equals(token.command.definitionGeneration)
                    && factual.selected == token.command.selected() && factual.phase != Phase.ACTIVATING
                    && (!factual.selected || (exactProof && !token.baselineDenied));
            long seq = unchanged ? factual.event.sequence : Math.addExact(v.highWater, 1);
            Event event = unchanged ? factual.event : new Event(v.storeEpoch, seq, token.command.connectorId,
                    factual == null ? null : factual.selected, token.command.selected(), token.command.kind.name(),
                    token.command.operationId, token.command.parentOperationId, clock.getAsLong());
            Mutation m = new Mutation(event, "selection/" + token.command.operationId, token.command,
                    unchanged ? factual.revision : Math.addExact(v.revisions.getOrDefault(token.command.connectorId, 0L), 1),
                    Math.max(v.highWater, seq), unchanged ? factual.phase : (event.selected ? Phase.ACTIVATING : Phase.NONE),
                    event.selected, unchanged, Origin.REQUEST, null, event.selected ? (unchanged ? factual.activationOwnerProcessId : processIdentity) : null);
            reserve(token, m);
            return persist(token, m, false, event.selected ? Activation.NOT_STARTED : Activation.NOT_REQUESTED);
        } finally { writer.unlock(); }
    }

    /** Revalidation failed after selection proof, before runtime dispatch. No runtime failure is invented. */
    public Result activationNotStarted(ActivationPermit permit) {
        writer.lock();
        try {
            if (permit == null || permit.request.owner != this)
                return noAttempt(Disposition.CONFLICT, Activation.NOT_STARTED, false);
            RequestToken t = permit.request; ReadView v = read();
            if (!current(v, t)) return noAttempt(Disposition.SUPERSEDED, Activation.NOT_STARTED, false);
            Activation prior = v.activationOutcomes.get(t.command.connectorId);
            if (prior != null && prior != Activation.NOT_STARTED)
                return noAttempt(Disposition.CONFLICT, Activation.NOT_STARTED, false);
            UncertainCandidate retry = findCandidate(v, t, "rollback/");
            if (retry != null && prior == Activation.NOT_STARTED)
                return persist(t, retry.mutation, retry.choiceConfirmed, Activation.NOT_STARTED);
            ConfirmedChoice current = v.choices.get(t.command.connectorId);
            if (prior == Activation.NOT_STARTED && current != null && current.mutation.command.equals(t.command)
                    && current.mutation.version.equals("rollback/" + t.command.operationId))
                return result(Disposition.UNCHANGED, Activation.NOT_STARTED, Outcome.CONFIRMED, Outcome.CONFIRMED, null, false);
            if (current == null || !current.mutation.equals(permit.activating)
                    || !current.mutation.equals(v.factualCurrent.get(t.command.connectorId))
                    || current.mutation.phase != Phase.ACTIVATING)
                return noAttempt(Disposition.CONFLICT, Activation.NOT_STARTED, false);
            if (!recordActivation(t, Activation.NOT_STARTED))
                return noAttempt(Disposition.CONFLICT, Activation.NOT_STARTED, false);
            deny(t);
            Event event = new Event(v.storeEpoch, Math.addExact(v.highWater, 1), t.command.connectorId,
                    true, false, "CONNECT_ROLLBACK", t.command.operationId, t.command.operationId, clock.getAsLong());
            Mutation m = new Mutation(event, "rollback/" + t.command.operationId, t.command,
                    Math.addExact(v.revisions.getOrDefault(t.command.connectorId, 0L), 1), event.sequence,
                    Phase.NONE, false, false);
            reserve(t, m); return persist(t, m, false, Activation.NOT_STARTED);
        } finally { writer.unlock(); }
    }
    public Result activationReturned(ActivationPermit permit) { return finishActivation(permit, true); }
    public Result activationFailed(ActivationPermit permit) { return finishActivation(permit, false); }
    private Result finishActivation(ActivationPermit permit, boolean returned) {
        writer.lock();
        try {
            if (permit == null || permit.request.owner != this) return noAttempt(Disposition.CONFLICT, Activation.NOT_STARTED, false);
            RequestToken t = permit.request; ReadView v = read(); Activation activation = returned ? Activation.RETURNED : Activation.FAILED;
            String prefix = returned ? "ready/" : "rollback/";
            UncertainCandidate retry = findCandidate(v, t, prefix);
            if (!current(v, t)) {
                if (retry != null && retry.choiceConfirmed && t.incarnation == v.incarnation)
                    return persist(t, retry.mutation, true, activation);
                return noAttempt(Disposition.SUPERSEDED, activation, true);
            }
            if (!recordActivation(t, activation)) return noAttempt(Disposition.CONFLICT, activation, true);
            if (retry != null) return persist(t, retry.mutation, retry.choiceConfirmed, activation);
            ConfirmedChoice c = v.choices.get(t.command.connectorId);
            if (c == null || !c.mutation.operationId.equals(t.command.operationId)) return noAttempt(Disposition.SUPERSEDED, activation, true);
            if (c.mutation.phase != Phase.ACTIVATING) {
                if (returned && c.mutation.phase == Phase.READY) return confirmedReplay(t, c.mutation);
                return noAttempt(Disposition.CONFLICT, activation, true);
            }
            deny(t);
            Event event = returned ? permit.activating.event : new Event(v.storeEpoch, Math.addExact(v.highWater, 1),
                    t.command.connectorId, true, false, "CONNECT_ROLLBACK", t.command.operationId,
                    t.command.operationId, clock.getAsLong());
            Mutation m = new Mutation(event, prefix + t.command.operationId, t.command,
                    returned ? c.revision() : Math.addExact(v.revisions.getOrDefault(t.command.connectorId, 0L), 1),
                    Math.max(v.highWater, event.sequence), returned ? Phase.READY : Phase.NONE, returned, false,
                    Origin.REQUEST, null, returned ? permit.activating.activationOwnerProcessId : null);
            reserve(t, m); return persist(t, m, false, activation);
        } finally { writer.unlock(); }
    }

    private boolean recordActivation(RequestToken t, Activation outcome) {
        for (;;) {
            ReadView v = read(); if (!current(v, t)) return false;
            Activation existing = v.activationOutcomes.get(t.command.connectorId);
            if (existing != null) return existing == outcome;
            Builder b = new Builder(v); b.activationOutcomes.put(t.command.connectorId, outcome);
            if (latest.compareAndSet(v, b.build())) return true;
        }
    }
    private UncertainCandidate findCandidate(ReadView v, RequestToken t, String prefix) {
        UncertainCandidate c = v.candidates.get(prefix + t.command.operationId);
        return c != null && c.mutation.event.storeEpoch.equals(v.storeEpoch)
                && c.mutation.command.equals(t.command) ? c : null;
    }
    private void reserve(RequestToken t, Mutation m) {
        t.persistenceAttempted = true;
        for (;;) {
            ReadView v = read(); if (v.incarnation != t.incarnation) return;
            Builder b = new Builder(v); b.highWater = Math.max(v.highWater, m.highWater);
            b.candidates.put(m.version, new UncertainCandidate(m, false));
            b.revisions.put(m.event.connectorId, Math.max(b.revisions.getOrDefault(m.event.connectorId, 0L), m.revision));
            if (latest.compareAndSet(v, b.build())) return;
        }
    }
    private void deny(RequestToken t) {
        for (;;) {
            ReadView v = read(); if (!current(v, t)) return;
            Builder b = new Builder(v); b.denied.add(t.command.connectorId);
            if (latest.compareAndSet(v, b.build())) return;
        }
    }
    private Result persist(RequestToken t, Mutation m, boolean initialKnown, Activation activation) {
        Outcome initial = initialKnown ? Outcome.CONFIRMED : call(() -> storage.writeCandidate(m));
        Outcome proof = initial == Outcome.CONFIRMED ? call(() -> storage.writeProof(m)) : Outcome.NOT_ATTEMPTED;
        for (;;) {
            ReadView latestView = read();
            if (latestView.incarnation != t.incarnation || !latestView.storeEpoch.equals(m.event.storeEpoch))
                return result(Disposition.SUPERSEDED, activation, initial, proof, null, activation == Activation.RETURNED || activation == Activation.FAILED);
            Builder b = new Builder(latestView);
            b.highWater = Math.max(latestView.highWater, m.highWater);
            boolean stillCurrent = current(latestView, t);
            if (!initialKnown) {
                if (initial == Outcome.CONFIRMED) b.factualCurrent.put(m.event.connectorId, m);
                else b.factualCurrent.remove(m.event.connectorId); // Latest attempted current bytes are not accredited.
            }
            if (initial == Outcome.CONFIRMED)
                b.factualRevisions.put(m.event.connectorId, Math.max(b.factualRevisions.getOrDefault(m.event.connectorId, 0L), m.revision));
            if (proof == Outcome.CONFIRMED) {
                b.candidates.remove(m.version); rememberSnapshot(b, m);
                Event old = b.pending.get(m.event.eventId);
                if (old != null && !old.equals(m.event)) throw new IllegalStateException("Conflicting event identity");
                b.pending.put(m.event.eventId, m.event);
                // A committed fact advances even if an in-memory intent superseded it. Never
                // replace a newer durable revision or clear that newer intent's deny overlay.
                ConfirmedChoice known = latestView.choices.get(m.event.connectorId);
                boolean sameRevisionNewerReceipt = known == null || m.revision > known.revision()
                        || (m.revision == known.revision()
                        && m.command.expectedIntentEpoch >= known.mutation.command.expectedIntentEpoch
                        && !(known.mutation.phase == Phase.READY && m.phase == Phase.ACTIVATING
                        && known.mutation.command.equals(m.command)));
                if (m.revision >= latestView.revision(m.event.connectorId) && sameRevisionNewerReceipt)
                    b.choices.put(m.event.connectorId, new ConfirmedChoice(m));
                if (stillCurrent && m.phase != Phase.ACTIVATING) {
                    b.denied.remove(m.event.connectorId);
                    if (m.origin == Origin.REQUEST) b.diagnostics.remove(m.event.connectorId);
                }
            } else {
                b.candidates.put(m.version, new UncertainCandidate(m, initial == Outcome.CONFIRMED));
                if (stillCurrent) b.denied.add(m.event.connectorId);
            }
            if (latest.compareAndSet(latestView, b.build())) {
                ActivationPermit permit = stillCurrent && proof == Outcome.CONFIRMED && m.phase == Phase.ACTIVATING
                        ? new ActivationPermit(t, m) : null;
                return result(stillCurrent ? (m.unchanged ? Disposition.UNCHANGED : Disposition.ACCEPTED) : Disposition.SUPERSEDED,
                        activation, initial, proof, permit, !t.command.selected() || activation == Activation.FAILED || (!stillCurrent && activation == Activation.RETURNED));
            }
        }
    }
    private Result confirmedReplay(RequestToken t, Mutation m) {
        Activation observed = read().activationOutcomes.get(t.command.connectorId);
        ActivationPermit permit = m.phase == Phase.ACTIVATING && observed == null ? new ActivationPermit(t, m) : null;
        Activation activation = observed != null ? observed : (m.unchanged ? Activation.NOT_REQUESTED
                : (m.phase == Phase.READY ? Activation.RETURNED : (m.selected ? Activation.NOT_STARTED : Activation.NOT_REQUESTED)));
        return result(Disposition.UNCHANGED, activation, Outcome.CONFIRMED, Outcome.CONFIRMED, permit,
                !t.command.selected() || activation == Activation.FAILED);
    }
    private Result noAttempt(Disposition d, Activation a, boolean cleanup) {
        return result(d, a, Outcome.NOT_ATTEMPTED, Outcome.NOT_ATTEMPTED, null, cleanup);
    }
    private Result result(Disposition d, Activation a, Outcome c, Outcome p, ActivationPermit permit, boolean cleanup) {
        return new Result(d, a, new CommitFacts(c, p), permit, cleanup, read());
    }

    public ProjectionBatch prepareProjection(String targetGeneration) {
        ReadView v = read();
        if (v.bootstrap != Bootstrap.READY || !v.destinationReady || !v.destinationGeneration.equals(targetGeneration)) throw new IllegalStateException("Destination not current");
        Map<String, Event> events = new LinkedHashMap<>(v.confirmedPending);
        if (!Objects.equals(v.projectedGeneration, targetGeneration)) for (ConfirmedChoice c : v.lastConfirmedSnapshots.values())
            events.put(c.mutation.event.eventId, c.mutation.event);
        return new ProjectionBatch(v, events.values());
    }
    public Outcome ackProjection(ProjectionBatch batch, Ack ack) {
        Objects.requireNonNull(batch); Objects.requireNonNull(ack);
        resetFence.lock(); writer.lock();
        try {
            ReadView v = read();
            if (!v.destinationReady || batch.incarnation != v.incarnation || batch.destinationVersion != v.destinationVersion || !batch.sourceEpoch.equals(v.storeEpoch)
                    || !batch.targetGeneration.equals(v.destinationGeneration)
                    || !ack.sourceEpoch.equals(batch.sourceEpoch) || !ack.targetGeneration.equals(batch.targetGeneration)
                    || !batch.events.equals(ack.events)) return Outcome.NOT_ATTEMPTED;
            if (batch.binding != v.binding || !evidenceCurrent(v.binding.evidence())
                    || !v.binding.generation().equals(v.binding.evidence().generation())) return Outcome.NOT_ATTEMPTED;
            RetentionPlan plan = retentionPlan(storage.retentionSnapshot(), batch, v);
            Outcome outcome = callCleanup(() -> storage.cleanup(batch, plan));
            if (outcome != Outcome.CONFIRMED) return outcome;
            for (;;) {
                ReadView current = read(); Builder b = new Builder(current);
                for (Event e : batch.events) if (e.equals(b.pending.get(e.eventId))) b.pending.remove(e.eventId);
                b.projected = batch.targetGeneration;
                if (latest.compareAndSet(current, b.build())) return outcome;
            }
        } finally { writer.unlock(); resetFence.unlock(); }
    }
    private boolean evidenceCurrent(DestinationEvidence evidence) {
        try {
            return evidence.providerIdentity() == destinationProviderIdentity && evidence.fenceIdentity() == resetFence
                    && evidence.generation() != null && !evidence.generation().isEmpty() && evidence.isCurrentPure();
        } catch (RuntimeException unavailable) { return false; }
    }
    /** The provider issues evidence BEFORE this call, without holding an owner lock or awaiting Room here. */
    public DestinationBinding bindDestination(DestinationEvidence evidence) {
        Objects.requireNonNull(evidence); resetFence.lock(); writer.lock();
        try {
            if (read().bootstrap != Bootstrap.READY || !evidenceCurrent(evidence)) return null;
            unbind(); // Invalidate old ACKs before any adapter failure.
            DestinationBinding binding = new DestinationBinding(evidence, read().destinationVersion);
            storage.bindDestination(binding); // Pure cache operation, never Room IO.
            for (;;) { ReadView v = read(); Builder b = new Builder(v); b.binding = binding; b.destination = binding.generation();
                if (latest.compareAndSet(v, b.build())) return binding; }
        } finally { writer.unlock(); resetFence.unlock(); }
    }
    public void invalidateDestination() {
        resetFence.lock(); writer.lock();
        try { unbind(); } finally { writer.unlock(); resetFence.unlock(); }
    }
    private void unbind() {
        for (;;) { ReadView v = read(); Builder b = new Builder(v); b.binding = null; b.destination = null;
            b.destinationVersion = Math.addExact(v.destinationVersion, 1);
            if (latest.compareAndSet(v, b.build())) break; }
        storage.bindDestination(null);
    }

    public void reset(StoredImage image) {
        resetFence.lock(); writer.lock();
        try {
            if (read().storeEpoch.equals(image.storeEpoch)) throw new IllegalArgumentException("Reset requires a new store epoch");
            unbind(); Builder restored = new Builder(restore(image, read().incarnation + 1));
            restored.destinationVersion = read().destinationVersion;
            latest.set(restored.build()); activeBootstrap = null;
        } finally { writer.unlock(); resetFence.unlock(); }
    }

    private ReadView restore(StoredImage image, long incarnation) {
        Builder b = new Builder(); b.bootstrap = Bootstrap.READY; b.epoch = image.storeEpoch;
        b.destination = null; b.projected = image.lastAcknowledgedGeneration;
        b.highWater = image.highWater; b.incarnation = incarnation;
        restoreFloors(b.revisions, image.reservedRevisions); restoreFloors(b.intentHighWater, image.intentHighWater);
        for (Map.Entry<String, Mutation> entry : image.proofs.entrySet()) {
            validateStored(image, entry.getValue(), b); restoreReservations(b, entry.getValue());
            b.factualRevisions.put(entry.getValue().event.connectorId, Math.max(b.factualRevisions.getOrDefault(entry.getValue().event.connectorId, 0L), entry.getValue().revision));
            Mutation proof = entry.getValue(); rememberSnapshot(b, proof);
            Mutation prior = b.newestProof.get(proof.event.connectorId);
            if (prior == null || mutationOrder(proof, prior) > 0) b.newestProof.put(proof.event.connectorId, proof);
            if (!entry.getKey().equals(entry.getValue().version)) throw new IllegalArgumentException("Proof identity mismatch");
        }
        for (Map.Entry<String, UncertainCandidate> entry : image.candidates.entrySet()) {
            Mutation m = entry.getValue().mutation; validateStored(image, m, b); restoreReservations(b, m);
            if (!entry.getKey().equals(m.version)) throw new IllegalArgumentException("Candidate identity mismatch");
            b.candidates.put(m.version, new UncertainCandidate(m, false)); // No proof from prior process knowledge.
        }
        for (Map.Entry<String, Mutation> entry : image.current.entrySet()) {
            Mutation m = entry.getValue(); validateStored(image, m, b); restoreReservations(b, m);
            if (!entry.getKey().equals(m.event.connectorId)) throw new IllegalArgumentException("Current connector mismatch");
            Mutation latestProof = b.newestProof.get(entry.getKey());
            if (latestProof != null && mutationOrder(m, latestProof) < 0)
                throw new IllegalArgumentException("Current mutation predates accredited history");
            if (m.equals(image.proofs.get(m.version))) {
                b.choices.put(entry.getKey(), new ConfirmedChoice(m)); b.factualCurrent.put(entry.getKey(), m); b.factualRevisions.put(entry.getKey(), Math.max(b.factualRevisions.getOrDefault(entry.getKey(), 0L), m.revision)); b.candidates.remove(m.version); rememberSnapshot(b, m);
                if (m.phase == Phase.ACTIVATING) b.denied.add(entry.getKey());
            } else {
                b.candidates.put(m.version, new UncertainCandidate(m, false)); b.denied.add(entry.getKey());
            }
        }
        for (Map.Entry<String, Event> entry : image.pending.entrySet()) {
            Event e = entry.getValue();
            validateEvent(b, e);
            if (!entry.getKey().equals(e.eventId) || !e.storeEpoch.equals(image.storeEpoch) || e.sequence > image.highWater)
                throw new IllegalArgumentException("Pending identity/reservation mismatch");
            boolean proven = false;
            for (Mutation proof : image.proofs.values()) if (proof.event.equals(e)) { proven = true; break; }
            if (proven) b.pending.put(e.eventId, e);
            // Unproven events are represented by exact candidates, never promoted from key presence.
            else {
                boolean retained = false;
                for (UncertainCandidate c : b.candidates.values()) if (c.mutation.event.equals(e)) { retained = true; break; }
                if (!retained) throw new IllegalArgumentException("Unproven pending event lacks its exact candidate");
            }
        }
        return b.build();
    }
    private int mutationOrder(Mutation left, Mutation right) {
        int revision = Long.compare(left.revision, right.revision);
        if (revision != 0) return revision;
        int intent = Long.compare(left.command.expectedIntentEpoch, right.command.expectedIntentEpoch);
        if (intent != 0) return intent;
        return Integer.compare(left.phase.ordinal(), right.phase.ordinal());
    }
    private void restoreReservations(Builder b, Mutation m) {
        b.revisions.put(m.event.connectorId, Math.max(b.revisions.getOrDefault(m.event.connectorId, 0L), m.revision));
        b.intentHighWater.put(m.event.connectorId, Math.max(b.intentHighWater.getOrDefault(m.event.connectorId, 0L),
                Math.addExact(m.command.expectedIntentEpoch, 1)));
    }
    private void validateStored(StoredImage image, Mutation m, Builder b) {
        validateEvent(b, m.event);
        Mutation previousVersion = b.restoredVersions.put(m.version, m);
        if (previousVersion != null && !previousVersion.equals(m)) throw new IllegalArgumentException("Conflicting mutation version");
        Command previousCommand = b.restoredCommands.put(m.operationId, m.command);
        if (previousCommand != null && !previousCommand.equals(m.command)) throw new IllegalArgumentException("Conflicting operation payload");
        if (m.origin != Origin.REQUEST) {
            validateBootstrapMutation(m);
            if (!m.event.storeEpoch.equals(image.storeEpoch) || m.highWater > image.highWater)
                throw new IllegalArgumentException("Mutation outside store epoch/reservation");
            return;
        }
        if (LEGACY_DEFINITION.equals(m.definitionGeneration) || m.bootstrapRecordId != null)
            throw new IllegalArgumentException("Request cannot fabricate legacy provenance");
        if (m.command.kind == Kind.DISCONNECT_AFTER_PERMISSION_ABORT
                && (m.command.parentOperationId == null || !m.operationId.startsWith("restore-off-")))
            throw new IllegalArgumentException("Restrictive restoration lacks causal identity");
        boolean selection = m.version.equals("selection/" + m.operationId);
        boolean ready = m.version.equals("ready/" + m.operationId);
        boolean rollback = m.version.equals("rollback/" + m.operationId);
        // Legacy baseline import is intentionally not implemented by this foundation.
        if (m.phase == Phase.LEGACY_OBSERVED || (!rollback && m.command.selected() != m.selected)
                || (rollback && !m.command.selected())
                || (m.unchanged && (!selection || m.phase != (m.selected ? Phase.READY : Phase.NONE))))
            throw new IllegalArgumentException("Unsupported/incoherent current choice provenance");
        if ((!selection && !ready && !rollback) || (ready && (m.phase != Phase.READY || !m.selected || m.unchanged))
                || (rollback && (m.phase != Phase.NONE || m.selected || m.unchanged))
                || (selection && !m.unchanged && m.phase != (m.selected ? Phase.ACTIVATING : Phase.NONE)))
            throw new IllegalArgumentException("Mutation phase/version mismatch");
        if (!m.unchanged && (!m.event.operationId.equals(m.command.operationId)
                || !m.event.reason.equals(rollback ? "CONNECT_ROLLBACK" : m.command.kind.name())
                || !Objects.equals(m.event.parentOperationId, rollback ? m.command.operationId : m.command.parentOperationId)))
            throw new IllegalArgumentException("Mutation event/command mismatch");
        if (!m.event.storeEpoch.equals(image.storeEpoch) || m.highWater > image.highWater)
            throw new IllegalArgumentException("Mutation outside store epoch/reservation");
    }
    private void validateBootstrapMutation(Mutation m) {
        if (m.unchanged || !m.event.operationId.equals(m.command.operationId)
                || !Objects.equals(m.event.parentOperationId, m.command.parentOperationId)
                || !m.version.equals("bootstrap/" + m.operationId) || m.command.selected() != m.selected)
            throw new IllegalArgumentException("Invalid bootstrap mutation identity");
        if (m.origin == Origin.BOOTSTRAP_OBSERVED) {
            if (m.bootstrapRecordId == null || !LEGACY_DEFINITION.equals(m.definitionGeneration)
                    || m.phase != (m.selected ? Phase.LEGACY_OBSERVED : Phase.NONE)
                    || !m.event.reason.equals("BOOTSTRAP_OBSERVED_LOCAL_CHOICE") || m.activationOwnerProcessId != null
                    || m.event.parentOperationId != null || m.event.previous != null)
                throw new IllegalArgumentException("Invalid legacy observation provenance");
        } else if (m.origin == Origin.RECOVERY_RESTRICTIVE) {
            if (m.selected || m.phase != Phase.NONE || m.bootstrapRecordId != null || m.activationOwnerProcessId != null
                    || m.event.parentOperationId == null
                    || !(m.event.reason.equals("ACTIVATION_INTERRUPTED") || m.event.reason.equals("PERSISTENCE_RECOVERY_UNCONFIRMED")
                    || m.event.reason.equals("DISCONNECTION_PERSISTENCE_RECOVERY")))
                throw new IllegalArgumentException("Recovery must remain restrictive");
        }
    }
    private void validateEvent(Builder b, Event e) {
        Event existing = b.restoredEvents.put(e.eventId, e);
        if (existing != null && !existing.equals(e)) throw new IllegalArgumentException("Conflicting full event payload");
    }
    private void rememberSnapshot(Builder b, Mutation mutation) {
        ConfirmedChoice old = b.lastConfirmedSnapshots.get(mutation.event.connectorId);
        if (old != null && mutationOrder(mutation, old.mutation) == 0 && !mutation.equals(old.mutation))
            throw new IllegalArgumentException("Ambiguous confirmed snapshot order");
        if (old == null || mutationOrder(mutation, old.mutation) > 0)
            b.lastConfirmedSnapshots.put(mutation.event.connectorId, new ConfirmedChoice(mutation));
    }
    private void restoreFloors(Map<String, Long> target, Map<String, Long> floors) {
        for (Map.Entry<String, Long> entry : floors.entrySet()) {
            required(entry.getKey());
            if (entry.getValue() == null || entry.getValue() < 0 || entry.getValue() == Long.MAX_VALUE)
                throw new IllegalArgumentException("Invalid reservation floor");
            target.put(entry.getKey(), entry.getValue());
        }
    }
    private RetentionPlan retentionPlan(RetentionSnapshot snapshot, ProjectionBatch batch, ReadView view) {
        StoredImage image = snapshot.image;
        if (!view.storeEpoch.equals(image.storeEpoch)) throw new IllegalStateException("Retention epoch mismatch");
        // Validate identity collisions and reservation bounds without reading mutable preferences.
        restore(image, view.incarnation);
        Set<String> proofs = new HashSet<>(); Map<String, Mutation> candidates = new LinkedHashMap<>();
        Map<String, Event> pending = new LinkedHashMap<>(image.pending);
        for (Event event : batch.events) if (event.equals(pending.get(event.eventId))) pending.remove(event.eventId);
        for (Mutation m : image.current.values()) {
            if (m.equals(image.proofs.get(m.version))) proofs.add(m.version);
            else candidates.put(m.version, m);
        }
        for (ConfirmedChoice last : view.lastConfirmedSnapshots.values()) {
            Mutation m = last.mutation;
            if (!m.equals(image.proofs.get(m.version))) throw new IllegalStateException("Missing accredited last snapshot");
            proofs.add(m.version);
        }
        for (UncertainCandidate candidate : image.candidates.values()) {
            Mutation m = candidate.mutation;
            if (!m.equals(image.proofs.get(m.version))) candidates.put(m.version, m);
        }
        // In-process reservations may precede the adapter call; preserve exact candidates, never current/proof.
        for (UncertainCandidate candidate : view.candidates.values()) {
            Mutation m = candidate.mutation;
            if (!m.equals(image.proofs.get(m.version))) candidates.put(m.version, m);
        }
        for (Mutation m : candidates.values()) {
            Mutation eventProof = null;
            for (Mutation p : image.proofs.values()) if (p.event.equals(m.event)
                    && (eventProof == null || mutationOrder(p, eventProof) > 0)) eventProof = p;
            if (eventProof == null) pending.put(m.event.eventId, m.event);
            else proofs.add(eventProof.version); // Remember an already-drained event despite an unresolved phase.
        }
        for (Event event : pending.values()) {
            Mutation chosen = null;
            for (Mutation m : image.proofs.values()) if (m.event.equals(event)
                    && (chosen == null || mutationOrder(m, chosen) > 0)) chosen = m;
            if (chosen != null) proofs.add(chosen.version);
        }
        return new RetentionPlan(snapshot, proofs, candidates, pending, view);
    }
    private interface Attempt { Outcome run() throws Exception; }
    /** Cleanup can reject a stale destination before any write; preserve that explicit fact. */
    private Outcome callCleanup(Attempt attempt) {
        try { Outcome outcome = attempt.run(); return outcome == null ? Outcome.UNCERTAIN : outcome; }
        catch (Exception uncertain) { return Outcome.UNCERTAIN; }
    }
    private Outcome call(Attempt attempt) {
        try { Outcome outcome = attempt.run(); return outcome == Outcome.CONFIRMED ? outcome : Outcome.UNCERTAIN; }
        catch (Exception uncertain) { return Outcome.UNCERTAIN; }
    }
    private static String required(String value) { if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing identity"); return value; }
    private static <K,V> Map<K,V> frozen(Map<K,V> source) { return Collections.unmodifiableMap(new LinkedHashMap<>(source)); }
}
