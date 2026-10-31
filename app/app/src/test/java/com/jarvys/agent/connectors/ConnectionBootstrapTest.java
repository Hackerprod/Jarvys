package com.jarvys.agent.connectors;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static com.jarvys.agent.connectors.ConnectionStateCoordinator.*;

/** Pure bootstrap/recovery contract; no Android bytes, lifecycle, permissions or remote assertions. */
public class ConnectionBootstrapTest {
    private static final class Store implements Storage {
        String epoch = "epoch", destination = "room";
        long highWater;
        int loads, metadataFailures;
        final Map<String, Mutation> current = new LinkedHashMap<>(), proofs = new LinkedHashMap<>();
        final Map<String, Event> pending = new LinkedHashMap<>();
        final Map<String, UncertainCandidate> candidates = new LinkedHashMap<>();
        final Map<String, LegacyRecord> legacy = new LinkedHashMap<>();
        final List<Mutation> writes = new ArrayList<>(), proofWrites = new ArrayList<>();
        final Deque<Outcome> writeOutcomes = new ArrayDeque<>(), proofOutcomes = new ArrayDeque<>();
        @Override public StoredImage load() throws StorageUnconfirmedException {
            loads++; if (metadataFailures-- > 0) throw new StorageUnconfirmedException("Metadata proof pending");
            return new StoredImage(epoch, highWater, destination, null, current, proofs, pending, candidates, new ArrayList<>(legacy.values()));
        }
        @Override public Outcome writeCandidate(Mutation m) {
            writes.add(m); current.put(m.event.connectorId, m); candidates.put(m.version, new UncertainCandidate(m, false));
            pending.put(m.event.eventId, m.event); highWater = Math.max(highWater, m.highWater);
            if (m.origin == Origin.BOOTSTRAP_OBSERVED) {
                LegacyRecord old = legacy.get(m.event.connectorId);
                legacy.put(old.connectorId, new LegacyRecord(old.recordId, old.connectorId, old.definitionGeneration, old.state, m.version));
            }
            return writeOutcomes.isEmpty() ? Outcome.CONFIRMED : writeOutcomes.removeFirst();
        }
        @Override public Outcome writeProof(Mutation m) {
            assertTrue(writes.contains(m)); proofWrites.add(m);
            Outcome outcome = proofOutcomes.isEmpty() ? Outcome.CONFIRMED : proofOutcomes.removeFirst();
            if (outcome == Outcome.CONFIRMED) proofs.put(m.version, m);
            return outcome;
        }
        @Override public Outcome cleanup(ProjectionBatch batch, RetentionPlan plan) { throw new AssertionError("Bootstrap cannot project or acknowledge Room"); }
        @Override public RetentionSnapshot retentionSnapshot() { throw new AssertionError("Bootstrap cannot prune"); }
        @Override public void bindDestination(DestinationBinding binding) { if (binding != null) throw new AssertionError("Bootstrap cannot bind Room"); }
        void source(String id, LegacyState state) {
            legacy.put(id, new LegacyRecord("legacy:" + id, id, LEGACY_DEFINITION, state, null));
        }
    }
    private static ConnectionStateCoordinator core(Store store, String process) {
        return new ConnectionStateCoordinator(store, () -> 17L, process);
    }
    private static RequestToken request(ConnectionStateCoordinator c, String id, String operation, Kind kind) {
        ReadView v = c.read(); return c.beginIntent(new Command(id, "real-definition", operation, kind, v.revision(id), v.intentEpoch(id), null));
    }
    private static ConnectionStateCoordinator readyFoundation(Store store, String process) {
        ConnectionStateCoordinator c = core(store, process); c.initialize(); return c;
    }

    @Test public void legacyTrueFalseAndAbsenceAreDistinctObservationsWithoutActivation() throws Exception {
        Store s = new Store(); s.source("yes", LegacyState.TRUE); s.source("no", LegacyState.FALSE); s.source("absent", LegacyState.ABSENT);
        ConnectionStateCoordinator c = core(s, "new-process"); BootstrapPlan plan = c.prepareBootstrap();
        assertSame(plan, c.prepareBootstrap()); assertEquals(1, s.loads); assertEquals(2, plan.actions.size());
        assertFalse(c.read().locallySelected("yes")); assertEquals(Bootstrap.INITIALIZING, c.read().bootstrap);
        assertEquals(BootstrapStatus.READY, c.applyBootstrap(plan).status);
        assertTrue(c.read().locallySelected("yes")); assertTrue(c.read().hasChoice("no")); assertFalse(c.read().locallySelected("no"));
        assertFalse(c.read().hasChoice("absent")); assertEquals(2L, c.read().highWater);
        for (Mutation m : s.writes) {
            assertEquals(Origin.BOOTSTRAP_OBSERVED, m.origin); assertEquals("BOOTSTRAP_OBSERVED_LOCAL_CHOICE", m.event.reason);
            assertEquals(LEGACY_DEFINITION, m.definitionGeneration); assertNull(m.activationOwnerProcessId);
            assertEquals(m.selected ? Phase.LEGACY_OBSERVED : Phase.NONE, m.phase); assertEquals(17L, m.event.observedAtMs);
        }
        assertEquals(Disposition.SUPERSEDED, request(c, "yes", "default", Kind.DEFAULT_CONNECT).disposition);
        Result explicit = c.persistSelection(request(c, "yes", "explicit", Kind.CONNECT));
        assertNotNull(explicit.permit); assertEquals(Phase.ACTIVATING, c.read().choices.get("yes").mutation.phase);
        assertFalse(c.read().locallySelected("yes"));
    }

    @Test public void uncertainImportBlocksWholeBootstrapAndRetrySkipsAlreadyProvenActions() throws Exception {
        Store s = new Store(); s.source("a", LegacyState.TRUE); s.source("b", LegacyState.FALSE);
        s.writeOutcomes.add(Outcome.UNCERTAIN); s.writeOutcomes.add(Outcome.CONFIRMED);
        ConnectionStateCoordinator c = core(s, "p"); BootstrapPlan plan = c.prepareBootstrap();
        BootstrapAttempt first = c.applyBootstrap(plan);
        assertEquals(BootstrapStatus.INCOMPLETE, first.status); assertEquals(Bootstrap.INITIALIZING, c.read().bootstrap);
        assertEquals(Disposition.SUPERSEDED, request(c, "new", "default", Kind.DEFAULT_CONNECT).disposition);
        assertEquals(2, s.writes.size()); assertEquals(1, s.proofWrites.size()); Mutation exact = s.writes.get(0);
        BootstrapAttempt second = c.applyBootstrap(plan);
        assertEquals(BootstrapStatus.READY, second.status); assertEquals(3, s.writes.size()); assertEquals(exact, s.writes.get(2));
        assertEquals(2, s.proofWrites.size()); assertEquals(1, s.loads); assertEquals(2L, c.read().highWater);
        c.applyBootstrap(plan); assertEquals(3, s.writes.size());
    }

    @Test public void confirmedInitialImportWithUncertainProofRetriesOnlyProof() throws Exception {
        Store s = new Store(); s.source("yes", LegacyState.TRUE); s.proofOutcomes.add(Outcome.UNCERTAIN);
        ConnectionStateCoordinator c = core(s, "p"); BootstrapPlan plan = c.prepareBootstrap();
        assertEquals(BootstrapStatus.INCOMPLETE, c.applyBootstrap(plan).status);
        assertFalse(c.read().locallySelected("yes")); assertEquals(1L, c.read().revision("yes"));
        assertEquals(BootstrapStatus.READY, c.applyBootstrap(plan).status);
        assertEquals(1, s.writes.size()); assertEquals(2, s.proofWrites.size());
    }

    @Test public void metadataUnconfirmedIsRetryableButNeverPublishesReady() throws Exception {
        Store s = new Store(); s.metadataFailures = 2;
        ConnectionStateCoordinator c = core(s, "p");
        assertThrows(StorageUnconfirmedException.class, c::prepareBootstrap);
        assertThrows(StorageUnconfirmedException.class, c::prepareBootstrap);
        assertEquals(Bootstrap.INITIALIZING, c.read().bootstrap); assertTrue(s.writes.isEmpty());
        BootstrapPlan plan = c.prepareBootstrap(); assertEquals("epoch", plan.storeEpoch);
        assertEquals(BootstrapStatus.READY, c.applyBootstrap(plan).status); assertEquals(3, s.loads);
    }

    @Test public void abandonedActivatingBecomesNewRestrictiveChoiceNotRuntimeReplay() throws Exception {
        Store s = new Store(); ConnectionStateCoordinator old = readyFoundation(s, "previous-process");
        Result initial = old.persistSelection(request(old, "mail", "connect", Kind.CONNECT)); assertNotNull(initial.permit);
        Mutation original = s.current.get("mail");
        ConnectionStateCoordinator c = core(s, "new-process"); BootstrapPlan plan = c.prepareBootstrap();
        assertEquals(1, plan.actions.size()); Mutation recovery = plan.actions.get(0).mutation;
        assertEquals(Origin.RECOVERY_RESTRICTIVE, recovery.origin); assertFalse(recovery.selected);
        assertEquals("ACTIVATION_INTERRUPTED", recovery.event.reason); assertEquals("connect", recovery.event.parentOperationId);
        assertEquals(Boolean.TRUE, recovery.event.previous); assertEquals(2L, recovery.revision); assertEquals(2L, recovery.event.sequence);
        assertEquals(BootstrapStatus.READY, c.applyBootstrap(plan).status); assertFalse(c.read().locallySelected("mail"));
        assertTrue(c.read().confirmedPending.containsKey(original.event.eventId));
    }

    @Test public void sameProcessActivationIsNotDeclaredAbandonedByAnotherCore() throws Exception {
        Store s = new Store(); ConnectionStateCoordinator live = readyFoundation(s, "same-process");
        live.persistSelection(request(live, "mail", "connect", Kind.CONNECT)); int writes = s.writes.size();
        ConnectionStateCoordinator accidentalFacade = core(s, "same-process"); BootstrapPlan plan = accidentalFacade.prepareBootstrap();
        assertEquals(BootstrapDiagnostic.CURRENT_PROCESS_ACTIVATION, plan.diagnostics.get("mail"));
        assertEquals(BootstrapStatus.INCOMPLETE, accidentalFacade.applyBootstrap(plan).status); assertEquals(writes, s.writes.size());
        assertFalse(accidentalFacade.read().locallySelected("mail"));
    }

    @Test public void unprovenReadyRecoversFalseWithPersistenceReasonAndRetainsOriginalCandidate() throws Exception {
        Store s = new Store(); ConnectionStateCoordinator old = readyFoundation(s, "old");
        Result selected = old.persistSelection(request(old, "mail", "connect", Kind.CONNECT));
        s.proofOutcomes.add(Outcome.UNCERTAIN); old.activationReturned(selected.permit); Mutation unproven = s.current.get("mail");
        assertEquals(Phase.READY, unproven.phase); assertFalse(s.proofs.containsKey(unproven.version));
        ConnectionStateCoordinator c = core(s, "new"); BootstrapPlan plan = c.prepareBootstrap(); Mutation repair = plan.actions.get(0).mutation;
        assertEquals("PERSISTENCE_RECOVERY_UNCONFIRMED", repair.event.reason); assertFalse(repair.selected);
        assertNull(repair.event.previous); assertEquals(unproven.operationId, repair.event.parentOperationId);
        c.applyBootstrap(plan); assertFalse(c.read().locallySelected("mail"));
        assertEquals(unproven, c.read().candidates.get(unproven.version).mutation);
    }

    @Test public void unprovenFalseGetsNewRepairIdentityAndRestartRetriesExactRestrictiveRepair() throws Exception {
        Store s = new Store(); ConnectionStateCoordinator old = readyFoundation(s, "old"); s.writeOutcomes.add(Outcome.UNCERTAIN);
        old.persistSelection(request(old, "mail", "disconnect", Kind.DISCONNECT)); Mutation original = s.current.get("mail");
        ConnectionStateCoordinator first = core(s, "first"); BootstrapPlan plan = first.prepareBootstrap(); Mutation repair = plan.actions.get(0).mutation;
        assertEquals("DISCONNECTION_PERSISTENCE_RECOVERY", repair.event.reason); assertNotEquals(original.operationId, repair.operationId);
        assertEquals(2L, repair.revision); s.writeOutcomes.add(Outcome.UNCERTAIN);
        assertEquals(BootstrapStatus.INCOMPLETE, first.applyBootstrap(plan).status);
        ConnectionStateCoordinator restarted = core(s, "second"); BootstrapPlan retry = restarted.prepareBootstrap();
        assertEquals(repair, retry.actions.get(0).mutation); assertEquals(2L, restarted.read().highWater);
        assertEquals(BootstrapStatus.READY, restarted.applyBootstrap(retry).status);
        assertEquals(original, restarted.read().candidates.get(original.version).mutation);
    }

    @Test public void legacyUncertaintyAfterRestartCanRetryOnlyExactBaselineIdentity() throws Exception {
        for (LegacyState state : new LegacyState[]{LegacyState.TRUE, LegacyState.FALSE}) {
            Store s = new Store(); s.source("mail", state); s.writeOutcomes.add(Outcome.UNCERTAIN);
            ConnectionStateCoordinator first = core(s, "first"); first.applyBootstrap(first.prepareBootstrap()); Mutation exact = s.current.get("mail");
            ConnectionStateCoordinator second = core(s, "second"); BootstrapPlan retry = second.prepareBootstrap();
            assertEquals(exact, retry.actions.get(0).mutation); assertEquals(1L, second.read().highWater);
            assertEquals(BootstrapStatus.READY, second.applyBootstrap(retry).status);
            assertEquals(exact, s.writes.get(1)); assertEquals(1, second.read().confirmedPending.size());
            assertEquals(state == LegacyState.TRUE, second.read().locallySelected("mail"));
        }
    }

    @Test public void legacyBaselineMismatchOrLaterUncertainInterventionNeverReappliesOldChoice() throws Exception {
        for (int change = 0; change < 4; change++) {
            Store s = new Store(); s.source("mail", LegacyState.TRUE); s.writeOutcomes.add(Outcome.UNCERTAIN);
            ConnectionStateCoordinator first = core(s, "old"); first.applyBootstrap(first.prepareBootstrap()); Mutation exact = s.current.get("mail");
            LegacyRecord original = s.legacy.get("mail");
            if (change < 3) s.legacy.put("mail", new LegacyRecord(change == 2 ? "different-source" : original.recordId, "mail", LEGACY_DEFINITION,
                    change == 0 ? LegacyState.FALSE : LegacyState.TRUE, change == 1 ? "different-marker" : original.markerVersion));
            else {
                Command later = new Command("mail", "real-definition", "later", Kind.DISCONNECT, 0, 1, null);
                Event event = new Event("epoch", 2, "mail", null, false, "DISCONNECT", "later", null, 18);
                Mutation candidate = new Mutation(event, "selection/later", later, 2, 2, Phase.NONE, false, false);
                s.candidates.put(candidate.version, new UncertainCandidate(candidate, false)); s.pending.put(event.eventId, event); s.highWater = 2;
            }
            ConnectionStateCoordinator second = core(s, "new"); BootstrapPlan blocked = second.prepareBootstrap(); int writes = s.writes.size();
            assertEquals(BootstrapStatus.INCOMPLETE, second.applyBootstrap(blocked).status);
            assertEquals(writes, s.writes.size()); assertFalse(second.read().locallySelected("mail"));
            assertEquals(exact, second.read().candidates.get(exact.version).mutation);
        }
    }

    @Test public void invalidLegacyCanBeExplicitlyReplacedButNeverDefaultedAndRetiredStaysExcluded() throws Exception {
        Store s = new Store(); s.source("invalid", LegacyState.INVALID); s.source("retired", LegacyState.RETIRED); s.source("valid", LegacyState.TRUE);
        ConnectionStateCoordinator c = core(s, "p"); assertEquals(BootstrapStatus.READY, c.applyBootstrap(c.prepareBootstrap()).status);
        assertTrue(c.read().locallySelected("valid")); assertEquals(1, s.writes.size());
        assertEquals(BootstrapDiagnostic.INVALID_LEGACY_SOURCE, c.read().diagnostics.get("invalid"));
        assertNull(s.legacy.get("invalid").markerVersion); assertNull(s.legacy.get("retired").markerVersion);
        assertEquals(Disposition.SUPERSEDED, request(c, "invalid", "default", Kind.DEFAULT_CONNECT).disposition);
        assertEquals(Disposition.SUPERSEDED, request(c, "retired", "connect", Kind.CONNECT).disposition);
        RequestToken explicit = request(c, "invalid", "explicit", Kind.CONNECT); s.proofOutcomes.add(Outcome.UNCERTAIN);
        assertNull(c.persistSelection(explicit).permit); assertTrue(c.read().diagnostics.containsKey("invalid"));
        Result selected = c.persistSelection(explicit); assertNotNull(selected.permit); assertTrue(c.read().diagnostics.containsKey("invalid"));
        s.proofOutcomes.add(Outcome.UNCERTAIN); c.activationReturned(selected.permit);
        assertFalse(c.read().locallySelected("invalid")); assertTrue(c.read().diagnostics.containsKey("invalid"));
        c.activationReturned(selected.permit); assertTrue(c.read().locallySelected("invalid")); assertFalse(c.read().diagnostics.containsKey("invalid"));
        // A legitimate current protocol proof wins over the old invalid legacy source snapshot.
        ConnectionStateCoordinator reopened = core(s, "new"); reopened.applyBootstrap(reopened.prepareBootstrap());
        assertTrue(reopened.read().locallySelected("invalid")); assertFalse(reopened.read().diagnostics.containsKey("invalid"));
        assertTrue(reopened.read().excluded.contains("retired"));
    }

    @Test public void foreignPlanAndResetInvalidateBootstrapWithoutWriting() throws Exception {
        Store s = new Store(); s.source("mail", LegacyState.TRUE); ConnectionStateCoordinator first = core(s, "p"); BootstrapPlan old = first.prepareBootstrap();
        ConnectionStateCoordinator other = core(new Store(), "other");
        assertEquals(BootstrapStatus.SUPERSEDED, other.applyBootstrap(old).status);
        first.reset(StoredImage.empty("new-epoch", "new-room"));
        assertEquals(BootstrapStatus.SUPERSEDED, first.applyBootstrap(old).status); assertTrue(s.writes.isEmpty());
    }

    @Test public void protocolCorruptionFailsGloballyWithoutWritingOrTreatingItAsInvalidLegacy() throws Exception {
        Store s = new Store(); s.source("mail", LegacyState.TRUE); ConnectionStateCoordinator first = core(s, "old");
        first.applyBootstrap(first.prepareBootstrap()); s.highWater = 0;
        ConnectionStateCoordinator corrupt = core(s, "new"); int writes = s.writes.size();
        assertThrows(IllegalStateException.class, corrupt::prepareBootstrap);
        assertEquals(Bootstrap.FAILED, corrupt.read().bootstrap); assertFalse(corrupt.read().locallySelected("mail")); assertEquals(writes, s.writes.size());
    }
    @Test public void laterUnknownCandidatePreventsFallbackToOlderProvenReadyPointer() throws Exception {
        Store s = new Store(); ConnectionStateCoordinator old = readyFoundation(s, "old");
        Result selected = old.persistSelection(request(old, "mail", "connect", Kind.CONNECT)); old.activationReturned(selected.permit);
        Command later = new Command("mail", "real-definition", "later-off", Kind.DISCONNECT, 1, 1, null);
        Event event = new Event("epoch", 2, "mail", true, false, "DISCONNECT", "later-off", null, 20);
        Mutation unknown = new Mutation(event, "selection/later-off", later, 2, 2, Phase.NONE, false, false);
        s.candidates.put(unknown.version, new UncertainCandidate(unknown, false)); s.pending.put(event.eventId, event); s.highWater = 2;
        int writes = s.writes.size(); ConnectionStateCoordinator c = core(s, "new"); BootstrapPlan plan = c.prepareBootstrap();
        assertEquals(BootstrapDiagnostic.CURRENT_VERSION_CONFLICT, plan.diagnostics.get("mail"));
        assertEquals(BootstrapStatus.INCOMPLETE, c.applyBootstrap(plan).status); assertEquals(writes, s.writes.size());
        assertFalse(c.read().locallySelected("mail")); assertNull(c.read().observation("mail"));
        assertEquals(unknown, c.read().candidates.get(unknown.version).mutation);
    }

    @Test public void bootstrapProofCannotCarryContradictoryParentCausality() throws Exception {
        Store s = new Store(); s.source("mail", LegacyState.TRUE); ConnectionStateCoordinator first = core(s, "old");
        first.applyBootstrap(first.prepareBootstrap()); Mutation m = s.current.get("mail"); Event e = m.event;
        Event forged = new Event(e.storeEpoch, e.sequence, e.connectorId, e.previous, e.selected, e.reason, e.operationId, "wrong-parent", e.observedAtMs);
        Mutation corrupt = new Mutation(forged, m.version, m.command, m.revision, m.highWater, m.phase, m.selected,
                m.unchanged, m.origin, m.bootstrapRecordId, m.activationOwnerProcessId);
        s.current.put("mail", corrupt); s.proofs.put(m.version, corrupt); s.pending.put(e.eventId, forged);
        s.candidates.put(m.version, new UncertainCandidate(corrupt, false));
        ConnectionStateCoordinator reopened = core(s, "new");
        assertThrows(IllegalStateException.class, reopened::prepareBootstrap);
        assertEquals(Bootstrap.FAILED, reopened.read().bootstrap);
    }

    @Test public void orphanUnknownDisconnectRepairsRestrictivelyWithoutBaselineOrDefault() throws Exception {
        Store s = new Store(); ConnectionStateCoordinator source = core(s, "old"); source.applyBootstrap(source.prepareBootstrap());
        s.writeOutcomes.add(Outcome.UNCERTAIN); source.persistSelection(request(source, "mail", "orphan-off", Kind.DISCONNECT));
        Mutation orphan = s.current.remove("mail"); // Candidate carried after an initial write failed before current bytes.
        ConnectionStateCoordinator reopened = core(s, "new"); BootstrapPlan plan = reopened.prepareBootstrap();
        assertEquals(1, plan.actions.size()); BootstrapAction action = plan.actions.get(0);
        assertNull(action.expectedCurrentVersion); assertFalse(action.mutation.selected); assertNull(action.mutation.event.previous);
        assertEquals(orphan.operationId, action.mutation.event.parentOperationId);
        assertTrue(action.mutation.revision > orphan.revision); assertTrue(action.mutation.event.sequence > orphan.event.sequence);
        assertEquals(Disposition.SUPERSEDED, request(reopened, "mail", "default-early", Kind.DEFAULT_CONNECT).disposition);
        s.proofOutcomes.add(Outcome.UNCERTAIN); assertEquals(BootstrapStatus.INCOMPLETE, reopened.applyBootstrap(plan).status);
        assertEquals(BootstrapStatus.READY, reopened.applyBootstrap(plan).status);
        assertEquals(orphan, reopened.read().candidates.get(orphan.version).mutation);
        assertFalse(reopened.read().locallySelected("mail"));
        assertEquals(Disposition.SUPERSEDED, request(reopened, "mail", "default-late", Kind.DEFAULT_CONNECT).disposition);
    }

    @Test public void historicalUnknownBeforeNewerProvenCurrentDoesNotBlockBootstrap() throws Exception {
        Store s = new Store(); ConnectionStateCoordinator first = core(s, "old"); first.applyBootstrap(first.prepareBootstrap());
        s.writeOutcomes.add(Outcome.UNCERTAIN); first.persistSelection(request(first, "mail", "old-off", Kind.DISCONNECT));
        Mutation old = s.current.get("mail"); first.persistSelection(request(first, "mail", "new-off", Kind.DISCONNECT));
        ConnectionStateCoordinator reopened = core(s, "new"); BootstrapPlan plan = reopened.prepareBootstrap();
        assertTrue(plan.actions.isEmpty()); assertEquals(BootstrapStatus.READY, reopened.applyBootstrap(plan).status);
        assertEquals(old, reopened.read().candidates.get(old.version).mutation);
        assertFalse(reopened.read().denied.contains("mail")); assertFalse(reopened.read().locallySelected("mail"));
    }

    @Test public void permissionAbortPreservesPriorInvalidLegacyDenyAndDiagnostic() throws Exception {
        Store s = new Store(); s.source("mail", LegacyState.INVALID); ConnectionStateCoordinator c = core(s, "process");
        c.applyBootstrap(c.prepareBootstrap()); RequestToken request = request(c, "mail", "permission", Kind.CONNECT);
        assertEquals(AbortDisposition.ABORTED, c.abortBeforePersistence(request).disposition);
        assertTrue(c.read().denied.contains("mail")); assertFalse(c.read().locallySelected("mail"));
        assertEquals(BootstrapDiagnostic.INVALID_LEGACY_SOURCE, c.read().diagnostics.get("mail"));
        assertEquals(Disposition.SUPERSEDED, request(c, "mail", "default", Kind.DEFAULT_CONNECT).disposition);
        assertTrue(s.writes.isEmpty());
    }

}
