package com.jarvys.agent.connectors;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.Assert.*;
import static com.jarvys.agent.connectors.ConnectionStateCoordinator.*;

/** Pure protocol tests, not evidence that an Android adapter provides durable proof. */
public class ConnectionStateCoordinatorTest {
    private static final class FakeStorage implements Storage {
        String epoch = "store-a", destination = "room-a", acknowledged = "room-a";
        long highWater;
        final Map<String, Mutation> current = new LinkedHashMap<>(), proofs = new LinkedHashMap<>();
        final Map<String, Event> pending = new LinkedHashMap<>();
        final Map<String, UncertainCandidate> candidates = new LinkedHashMap<>();
        final List<Mutation> writes = new ArrayList<>(), proofWrites = new ArrayList<>();
        Outcome candidateOutcome = Outcome.CONFIRMED, proofOutcome = Outcome.CONFIRMED, cleanupOutcome = Outcome.CONFIRMED;
        boolean throwCandidate, throwCleanup, throwBind, staleSnapshot;
        final ReentrantLock fence = new ReentrantLock(); final Object provider = new Object();
        long cacheVersion, destinationInstance;
        DestinationBinding binding;
        final Map<String, Long> reserved = new HashMap<>(), intents = new HashMap<>();
        DestinationEvidence evidence() {
            String generation = destination; long instance = destinationInstance;
            return new DestinationEvidence() {
                public String generation() { return generation; }
                public Object providerIdentity() { return provider; }
                public ReentrantLock fenceIdentity() { return fence; }
                public boolean isCurrentPure() { return instance == destinationInstance && generation.equals(destination); }
            };
        }
        @Override public void bindDestination(DestinationBinding value) {
            cacheVersion++; binding = value;
            if (throwBind && value != null) throw new IllegalStateException("Binding rejected");
        }
        @Override public RetentionSnapshot retentionSnapshot() { RetentionSnapshot result = new RetentionSnapshot(image(), this, cacheVersion); if (staleSnapshot) cacheVersion++; return result; }
        CountDownLatch entered, release, cleanupEntered, cleanupRelease;
        int cleanupCalls;
        @Override public StoredImage load() { return image(); }
        StoredImage image() { return new StoredImage(epoch, highWater, destination, acknowledged, current, proofs, pending, candidates, Collections.emptyList(), reserved, intents); }
        @Override public Outcome writeCandidate(Mutation m) throws Exception {
            cacheVersion++; writes.add(m); current.put(m.event.connectorId, m); highWater = Math.max(highWater, m.highWater);
            pending.put(m.event.eventId, m.event); candidates.put(m.version, new UncertainCandidate(m, false));
            if (entered != null) { entered.countDown(); await(release); }
            if (throwCandidate) throw new IllegalStateException("Uncertain storage failure");
            return candidateOutcome;
        }
        @Override public Outcome writeProof(Mutation m) {
            assertTrue("Proof may follow only this exact candidate write", writes.contains(m));
            cacheVersion++; proofWrites.add(m); if (proofOutcome == Outcome.CONFIRMED) proofs.put(m.version, m);
            return proofOutcome;
        }
        @Override public Outcome cleanup(ProjectionBatch batch, RetentionPlan plan) throws Exception {
            if (binding != batch.binding || plan.expectedSnapshot.ownerIdentity != this || plan.expectedSnapshot.version != cacheVersion)
                return Outcome.NOT_ATTEMPTED;
            cacheVersion++; cleanupCalls++;
            if (throwCleanup) throw new IllegalStateException("Uncertain cleanup failure");
            if (cleanupEntered != null) { cleanupEntered.countDown(); await(cleanupRelease); }
            if (cleanupOutcome == Outcome.CONFIRMED) {
                for (Event e : batch.events) if (e.equals(pending.get(e.eventId))) pending.remove(e.eventId);
                acknowledged = batch.targetGeneration;
                proofs.keySet().retainAll(plan.keepProofVersions);
                candidates.keySet().retainAll(plan.keepCandidateVersions);
                for (Mutation m : plan.carryCandidates.values()) candidates.put(m.version, new UncertainCandidate(m, false));
                pending.putAll(plan.carryPending);
                reserved.putAll(plan.reservedRevisions); intents.putAll(plan.intentHighWater);
            }
            return cleanupOutcome;
        }
    }
    private static void replaceDestination(ConnectionStateCoordinator core, FakeStorage store, String generation, Runnable replacement) {
        store.fence.lock();
        try {
            core.invalidateDestination(); store.destinationInstance++; store.destination = generation;
            replacement.run();
        } finally { store.fence.unlock(); }
        core.bindDestination(store.evidence());
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("Checkpoint not reached", latch.await(5, TimeUnit.SECONDS));
    }
    private static ConnectionStateCoordinator coordinator(FakeStorage store) {
        ConnectionStateCoordinator core = new ConnectionStateCoordinator(store, () -> 50L, "process", store.fence, store.provider); core.initialize(); core.bindDestination(store.evidence()); return core;
    }
    private static RequestToken request(ConnectionStateCoordinator core, String operation, Kind kind) {
        ReadView v = core.read();
        return core.beginIntent(new Command("mail", "definition-a", operation, kind, v.revision("mail"), v.intentEpoch("mail"), null));
    }
    private static Result connect(ConnectionStateCoordinator core, String operation) {
        Result selected = core.persistSelection(request(core, operation, Kind.CONNECT));
        assertNotNull(selected.permit); return core.activationReturned(selected.permit);
    }
    private static Ack ack(ProjectionBatch batch) { return new Ack(batch.sourceEpoch, batch.targetGeneration, batch.events); }

    @Test public void initialUncertaintyNeverIssuesActivationAndRetryUsesExactEvent() {
        for (boolean throwsFailure : new boolean[]{false, true}) {
            FakeStorage store = new FakeStorage(); store.candidateOutcome = Outcome.UNCERTAIN; store.throwCandidate = throwsFailure;
            ConnectionStateCoordinator core = coordinator(store);
            RequestToken token = request(core, "connect", Kind.CONNECT);
            Result first = core.persistSelection(token);
            assertEquals(Outcome.UNCERTAIN, first.persistence.choice);
            assertEquals(Outcome.NOT_ATTEMPTED, first.persistence.proof);
            assertEquals(Activation.NOT_STARTED, first.activation); assertNull(first.permit);
            assertTrue(store.proofWrites.isEmpty()); assertFalse(core.read().locallySelected("mail"));
            assertTrue(core.prepareProjection("room-a").events.isEmpty());
            Mutation exact = store.writes.get(0);
            store.candidateOutcome = Outcome.CONFIRMED; store.throwCandidate = false;
            Result retried = core.persistSelection(token);
            assertEquals(exact, store.writes.get(1)); assertEquals(1L, core.read().highWater);
            assertNotNull(retried.permit); assertFalse(core.read().locallySelected("mail"));
            assertTrue(core.activationReturned(retried.permit).snapshot.locallySelected("mail"));
        }
    }

    @Test public void uncertainProofRetainsKnownInitialCommitAndNeverRewritesSelection() {
        FakeStorage store = new FakeStorage(); store.proofOutcome = Outcome.UNCERTAIN;
        ConnectionStateCoordinator core = coordinator(store); RequestToken t = request(core, "connect", Kind.CONNECT);
        Result result = core.persistSelection(t);
        assertEquals(Outcome.CONFIRMED, result.persistence.choice); assertEquals(Outcome.UNCERTAIN, result.persistence.proof);
        assertNull(result.permit); assertTrue(core.read().candidates.values().iterator().next().choiceConfirmed);
        store.proofOutcome = Outcome.CONFIRMED;
        assertNotNull(core.persistSelection(t).permit);
        assertEquals(1, store.writes.size()); assertEquals(2, store.proofWrites.size());
    }

    @Test public void laterDisconnectSurvivesConnectIoPublicationWithoutBlockingBeginIntent() throws Exception {
        FakeStorage store = new FakeStorage(); store.entered = new CountDownLatch(1); store.release = new CountDownLatch(1);
        ConnectionStateCoordinator core = coordinator(store); RequestToken old = request(core, "connect", Kind.CONNECT);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Result> committing = workers.submit(() -> core.persistSelection(old)); await(store.entered);
            RequestToken off = workers.submit(() -> request(core, "disconnect", Kind.DISCONNECT)).get(5, TimeUnit.SECONDS);
            assertTrue(core.read().denied.contains("mail")); assertTrue(core.isCurrent(off));
            store.release.countDown(); Result oldResult = committing.get(5, TimeUnit.SECONDS);
            assertEquals(Disposition.SUPERSEDED, oldResult.disposition); assertNull(oldResult.permit);
            assertTrue(core.isCurrent(off)); assertTrue(core.read().denied.contains("mail"));
            assertTrue(core.read().hasChoice("mail")); // The durable fact advances, not its stale intent authority.
            assertEquals(1L, core.read().revision("mail"));
            int committedWrites = store.writes.size();
            Result strict = core.persistSelection(off);
            assertEquals(Disposition.SUPERSEDED, strict.disposition);
            assertEquals(Outcome.NOT_ATTEMPTED, strict.persistence.choice); assertTrue(strict.cleanupRequired);
            assertEquals(committedWrites, store.writes.size());
            assertTrue(core.read().denied.contains("mail"));
            core.persistSelection(request(core, "renewed-off", Kind.DISCONNECT));
            assertFalse(core.read().locallySelected("mail")); assertEquals(2L, core.read().highWater);
            assertEquals(2L, core.read().revision("mail"));
            assertEquals(2, core.prepareProjection("room-a").events.size());
        } finally { store.release.countDown(); workers.shutdownNow(); }
    }

    @Test public void supersededInitialUncertaintyIsRetainedNeverReappliedOrPromotedOnReopen() {
        FakeStorage store = new FakeStorage(); store.candidateOutcome = Outcome.UNCERTAIN;
        ConnectionStateCoordinator core = coordinator(store); RequestToken old = request(core, "old", Kind.CONNECT);
        core.persistSelection(old); Mutation candidate = store.writes.get(0);
        store.candidateOutcome = Outcome.CONFIRMED;
        core.persistSelection(request(core, "off", Kind.DISCONNECT));
        int writes = store.writes.size(); Result obsolete = core.persistSelection(old);
        assertEquals(Disposition.SUPERSEDED, obsolete.disposition); assertEquals(Outcome.NOT_ATTEMPTED, obsolete.persistence.choice);
        assertEquals(writes, store.writes.size()); assertEquals(candidate, core.read().candidates.get(candidate.version).mutation);
        assertEquals(2L, core.read().highWater); assertEquals(1, core.prepareProjection("room-a").events.size());
        ConnectionStateCoordinator reopened = coordinator(store);
        assertFalse(reopened.read().locallySelected("mail"));
        assertEquals(candidate, reopened.read().candidates.get(candidate.version).mutation);
        assertEquals(1, reopened.prepareProjection("room-a").events.size());
    }

    @Test public void supersededKnownInitialCommitCanAddOnlyItsExactHistoricalProof() {
        FakeStorage store = new FakeStorage(); store.proofOutcome = Outcome.UNCERTAIN;
        ConnectionStateCoordinator core = coordinator(store); RequestToken old = request(core, "old", Kind.CONNECT);
        core.persistSelection(old); Mutation first = store.writes.get(0);
        store.proofOutcome = Outcome.CONFIRMED;
        core.persistSelection(request(core, "off", Kind.DISCONNECT)); int count = store.writes.size();
        Result result = core.persistSelection(old);
        assertEquals(Disposition.SUPERSEDED, result.disposition); assertEquals(count, store.writes.size());
        assertEquals(first, store.proofWrites.get(store.proofWrites.size() - 1));
        assertFalse(core.read().choices.get("mail").selected()); assertFalse(core.read().locallySelected("mail"));
        assertEquals(2, core.prepareProjection("room-a").events.size());
    }

    @Test public void readyNeedsItsOwnProofAndRuntimeReturnIsNotInitialFailure() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
        Result initial = core.persistSelection(request(core, "connect", Kind.CONNECT));
        Mutation activating = store.writes.get(0); store.candidateOutcome = Outcome.UNCERTAIN;
        Result ready = core.activationReturned(initial.permit);
        assertEquals(Activation.RETURNED, ready.activation); assertEquals(Outcome.UNCERTAIN, ready.persistence.choice);
        assertFalse(core.read().locallySelected("mail")); assertEquals(1, store.proofWrites.size());
        assertFalse(activating.equals(store.writes.get(1)));
        ConnectionStateCoordinator reopened = coordinator(store); // Old ACTIVATING proof is not READY proof.
        assertFalse(reopened.read().locallySelected("mail")); assertFalse(reopened.read().hasChoice("mail"));
        store.candidateOutcome = Outcome.CONFIRMED;
        core.activationReturned(initial.permit);
        assertTrue(core.read().locallySelected("mail")); assertEquals(1L, core.read().highWater);
        assertEquals(1, core.prepareProjection("room-a").events.size());
    }

    @Test public void oldActivationCallbackNeedsCleanupAndCannotOverrideNewDefinitionIntent() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
        Result first = core.persistSelection(request(core, "connect", Kind.CONNECT));
        ReadView v = core.read();
        RequestToken replacement = core.beginIntent(new Command("mail", "definition-b", "replace", Kind.DISCONNECT,
                v.revision("mail"), v.intentEpoch("mail"), null));
        core.persistSelection(replacement); int writes = store.writes.size();
        Result late = core.activationReturned(first.permit);
        assertEquals(Disposition.SUPERSEDED, late.disposition); assertTrue(late.cleanupRequired);
        assertEquals(writes, store.writes.size()); assertFalse(core.read().locallySelected("mail"));
    }

    @Test public void replayConflictAndDefaultRespectFullPayloadAndExplicitFalseChoice() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
        RequestToken off = request(core, "off", Kind.DISCONNECT);
        assertSame(off, core.beginIntent(off.command));
        Command conflict = new Command("mail", "definition-a", "off", Kind.DISCONNECT, 0, 0, "different-parent");
        assertEquals(Disposition.CONFLICT, core.beginIntent(conflict).disposition);
        core.persistSelection(off);
        assertTrue(core.read().hasChoice("mail")); assertFalse(core.read().locallySelected("mail"));
        assertEquals(Disposition.SUPERSEDED, request(core, "default", Kind.DEFAULT_CONNECT).disposition);
        core.persistSelection(request(core, "off-again", Kind.DISCONNECT));
        assertEquals(1L, core.read().highWater); assertEquals(1, core.prepareProjection("room-a").events.size());
    }

    @Test public void unchangedReceiptUncertaintyDoesNotClaimDurableDedupe() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
        connect(core, "connect"); long seq = core.read().highWater;
        store.proofOutcome = Outcome.UNCERTAIN;
        Result same = core.persistSelection(request(core, "same", Kind.CONNECT));
        assertEquals(Disposition.UNCHANGED, same.disposition); assertEquals(Outcome.UNCERTAIN, same.persistence.proof);
        assertNull(same.permit); assertFalse(core.read().locallySelected("mail")); assertEquals(seq, core.read().highWater);
    }

    @Test public void projectionUsesSequenceNotClockAndObservationBelongsToCapturedView() {
        FakeStorage store = new FakeStorage(); AtomicLong clock = new AtomicLong(50);
        ConnectionStateCoordinator core = new ConnectionStateCoordinator(store, clock::get, "process", store.fence, store.provider); core.initialize(); core.bindDestination(store.evidence());
        connect(core, "connect"); ReadView snapshot = core.read(); Observation observation = snapshot.observation("mail");
        clock.set(1); core.persistSelection(request(core, "off", Kind.DISCONNECT));
        clock.set(-50); connect(core, "reconnect");
        List<Event> events = core.prepareProjection("room-a").events;
        assertEquals(Arrays.asList(1L, 2L, 3L), Arrays.asList(events.get(0).sequence, events.get(1).sequence, events.get(2).sequence));
        assertEquals(1L, observation.sequence); assertTrue(observation.selected); assertEquals("connect", observation.operationId);
        assertEquals(1L, snapshot.observation("mail").sequence); assertEquals(3L, core.read().observation("mail").sequence);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.choices.clear());
    }

    @Test public void cleanupFailureRetainsEventsAndExactAckRejectsPayloadMismatch() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "connect");
        ProjectionBatch batch = core.prepareProjection("room-a"); Event e = batch.events.get(0);
        Event altered = new Event(e.storeEpoch, e.sequence, e.connectorId, e.previous, e.selected, e.reason, e.operationId, e.parentOperationId, e.observedAtMs + 1);
        assertEquals(Outcome.NOT_ATTEMPTED, core.ackProjection(batch, new Ack(batch.sourceEpoch, batch.targetGeneration, Collections.singletonList(altered))));
        assertEquals(0, store.cleanupCalls);
        store.cleanupOutcome = Outcome.UNCERTAIN; assertEquals(Outcome.UNCERTAIN, core.ackProjection(batch, ack(batch)));
        assertEquals(1, core.read().confirmedPending.size());
        store.cleanupOutcome = Outcome.CONFIRMED; core.ackProjection(batch, ack(batch)); assertTrue(core.read().confirmedPending.isEmpty());
    }

    @Test public void cleanupNotAttemptedNullAndExceptionNeverAcknowledgeOrRemovePending() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "connect");
        ProjectionBatch batch = core.prepareProjection("room-a");
        Map<String, Event> pending = new LinkedHashMap<>(store.pending);
        ReadView before = core.read();
        store.cleanupOutcome = Outcome.NOT_ATTEMPTED;
        assertEquals(Outcome.NOT_ATTEMPTED, core.ackProjection(batch, ack(batch)));
        assertEquals(1, store.cleanupCalls);
        assertEquals(pending, store.pending); assertSame(before, core.read());
        store.cleanupOutcome = null;
        assertEquals(Outcome.UNCERTAIN, core.ackProjection(batch, ack(batch)));
        assertEquals(2, store.cleanupCalls);
        assertEquals(pending, store.pending); assertSame(before, core.read());
        store.throwCleanup = true;
        assertEquals(Outcome.UNCERTAIN, core.ackProjection(batch, ack(batch)));
        assertEquals(3, store.cleanupCalls);
        assertEquals(pending, store.pending); assertSame(before, core.read());
        store.throwCleanup = false; store.cleanupOutcome = Outcome.CONFIRMED;
        assertEquals(Outcome.CONFIRMED, core.ackProjection(batch, ack(batch)));
        assertTrue(store.pending.isEmpty()); assertTrue(core.read().confirmedPending.isEmpty());
    }

    @Test public void resetInvalidatesPermitsRequestsAndAcksAndDestinationAbaInvalidatesOldBatch() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
        RequestToken t = request(core, "connect", Kind.CONNECT); Result first = core.persistSelection(t);
        ProjectionBatch old = core.prepareProjection("room-a");
        replaceDestination(core, store, "room-b", () -> {}); replaceDestination(core, store, "room-a", () -> {});
        assertEquals(Outcome.NOT_ATTEMPTED, core.ackProjection(old, ack(old)));
        core.reset(StoredImage.empty("store-b", "room-c"));
        assertEquals(Disposition.SUPERSEDED, core.persistSelection(t).disposition);
        assertEquals(Disposition.SUPERSEDED, core.activationReturned(first.permit).disposition);
        assertEquals(Outcome.NOT_ATTEMPTED, core.ackProjection(old, ack(old)));
        assertTrue(core.read().choices.isEmpty()); assertEquals(0L, core.read().highWater);
    }

    @Test public void ackFenceCoversCleanupUntilRealDestinationReplacementAndBeginIntentStaysQuick() throws Exception {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "connect");
        ProjectionBatch batch = core.prepareProjection("room-a");
        store.cleanupEntered = new CountDownLatch(1); store.cleanupRelease = new CountDownLatch(1);
        AtomicBoolean replaced = new AtomicBoolean(); AtomicReference<Thread> replacementThread = new AtomicReference<>();
        CountDownLatch attempt = new CountDownLatch(1); ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> cleanup = workers.submit(() -> core.ackProjection(batch, ack(batch))); await(store.cleanupEntered);
            Future<?> replace = workers.submit(() -> { replacementThread.set(Thread.currentThread()); attempt.countDown();
                replaceDestination(core, store, "room-b", () -> { assertFalse(lock(core, "writer").isHeldByCurrentThread()); replaced.set(true); }); });
            await(attempt); ReentrantLock fence = lock(core, "resetFence"); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!fence.hasQueuedThread(replacementThread.get()) && !replace.isDone() && System.nanoTime() < deadline) Thread.yield();
            if (replace.isDone()) replace.get(5, TimeUnit.SECONDS);
            assertTrue(fence.hasQueuedThread(replacementThread.get())); assertFalse(replaced.get());
            RequestToken off = request(core, "off", Kind.DISCONNECT); assertTrue(core.isCurrent(off));
            store.cleanupRelease.countDown(); assertEquals(Outcome.CONFIRMED, cleanup.get(5, TimeUnit.SECONDS)); replace.get(5, TimeUnit.SECONDS);
            assertTrue(replaced.get()); assertTrue(core.read().denied.contains("mail"));
            assertEquals(1, core.prepareProjection("room-b").events.size()); // Last confirmed snapshot replay after destination loss.
        } finally { store.cleanupRelease.countDown(); workers.shutdownNow(); }
    }
    @Test public void knownInitialWithoutProofAdvancesCasButReopenDoesNotInventThatKnowledge() throws Exception {
        FakeStorage store = new FakeStorage(); store.proofOutcome = Outcome.UNCERTAIN;
        store.entered = new CountDownLatch(1); store.release = new CountDownLatch(1);
        ConnectionStateCoordinator core = coordinator(store); RequestToken old = request(core, "connect", Kind.CONNECT);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Result> commit = worker.submit(() -> core.persistSelection(old)); await(store.entered);
            RequestToken earlyOff = request(core, "early-off", Kind.DISCONNECT);
            store.release.countDown(); commit.get(5, TimeUnit.SECONDS);
            assertEquals(1L, core.read().revision("mail")); assertFalse(core.read().hasChoice("mail"));
            assertEquals(Outcome.NOT_ATTEMPTED, core.persistSelection(earlyOff).persistence.choice);
            assertTrue(core.read().denied.contains("mail")); assertEquals(1, store.writes.size());
            store.proofs.clear(); // Crash before any durable proof: raw candidate bytes alone are insufficient.
            ConnectionStateCoordinator reopened = coordinator(store);
            assertEquals(0L, reopened.read().revision("mail")); assertEquals(1L, reopened.read().highWater);
            assertFalse(reopened.read().locallySelected("mail"));
            store.proofOutcome = Outcome.CONFIRMED;
            Result fresh = reopened.persistSelection(request(reopened, "fresh-off", Kind.DISCONNECT));
            assertEquals(Outcome.CONFIRMED, fresh.persistence.proof);
            assertEquals(2L, reopened.read().revision("mail")); assertEquals(2L, reopened.read().highWater);
        } finally { store.release.countDown(); worker.shutdownNow(); }
    }

    @Test public void candidateIdentityIncludesConnectorAndEveryCommandFieldEvenAfterReopen() {
        FakeStorage store = new FakeStorage(); store.candidateOutcome = Outcome.UNCERTAIN;
        ConnectionStateCoordinator core = coordinator(store); RequestToken old = request(core, "reused", Kind.CONNECT);
        core.persistSelection(old); core.beginIntent(new Command("mail", "definition-a", "later", Kind.DISCONNECT, 0, 1, null));
        assertEquals(Disposition.CONFLICT, core.beginIntent(new Command("other", "definition-a", "reused", Kind.CONNECT, 0, 0, null)).disposition);
        assertEquals(Disposition.CONFLICT, core.beginIntent(new Command("mail", "definition-a", "reused", Kind.CONNECT, 0, 2, "altered-parent")).disposition);
        assertEquals(1, store.writes.size());
        ConnectionStateCoordinator reopened = coordinator(store);
        assertEquals(Disposition.CONFLICT, reopened.beginIntent(new Command("other", "definition-a", "reused", Kind.CONNECT, 0, 0, null)).disposition);
        assertEquals(Disposition.SUPERSEDED, reopened.beginIntent(old.command).disposition); // No process-token revival.
    }

    @Test public void restoredConfirmedReceiptChecksFullCommandAndNeverRestartsActivation() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
        RequestToken original = request(core, "connect", Kind.CONNECT); core.persistSelection(original);
        ConnectionStateCoordinator reopened = coordinator(store);
        Result replay = reopened.persistSelection(reopened.beginIntent(original.command));
        assertEquals(Disposition.UNCHANGED, replay.disposition); assertNull(replay.permit);
        assertFalse(reopened.read().locallySelected("mail")); // Abandoned ACTIVATING is not READY.
        assertEquals(Disposition.CONFLICT, reopened.beginIntent(new Command("mail", "definition-b", "connect", Kind.CONNECT, 0, 0, null)).disposition);
        assertEquals(Disposition.CONFLICT, reopened.beginIntent(new Command("mail", "definition-a", "connect", Kind.DISCONNECT, 0, 0, null)).disposition);
    }

    @Test public void replacingDefinitionCannotBorrowOldReadyPhase() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "first");
        ReadView v = core.read(); RequestToken newDefinition = core.beginIntent(new Command("mail", "definition-b", "replacement", Kind.CONNECT,
                v.revision("mail"), v.intentEpoch("mail"), null));
        Result selected = core.persistSelection(newDefinition);
        assertEquals(Disposition.ACCEPTED, selected.disposition); assertNotNull(selected.permit);
        assertFalse(core.read().locallySelected("mail")); assertEquals(Phase.ACTIVATING, core.read().choices.get("mail").mutation.phase);
        assertTrue(core.activationReturned(selected.permit).snapshot.locallySelected("mail"));
    }

    @Test public void failedReplacementInvalidatesOldAcknowledgmentsAndRetainsExactReplay() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "connect");
        ProjectionBatch old = core.prepareProjection("room-a"); AtomicBoolean actualReplacement = new AtomicBoolean();
        assertThrows(IllegalStateException.class, () -> replaceDestination(core, store, "room-b", () -> {
            actualReplacement.set(true); throw new IllegalStateException("Replacement completion uncertain");
        }));
        assertTrue(actualReplacement.get()); assertFalse(core.read().destinationReady);
        assertEquals(Outcome.NOT_ATTEMPTED, core.ackProjection(old, ack(old))); assertEquals(0, store.cleanupCalls);
        assertThrows(IllegalStateException.class, () -> core.prepareProjection("room-a"));
        replaceDestination(core, store, "room-c", () -> {});
        assertEquals(old.events, core.prepareProjection("room-c").events);
    }

    @Test public void reopenNewDestinationReplaysLastSnapshotWithoutInventingLostHistory() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "connect");
        ProjectionBatch old = core.prepareProjection("room-a"); core.ackProjection(old, ack(old));
        assertTrue(store.pending.isEmpty()); store.destination = "room-b";
        ConnectionStateCoordinator reopened = coordinator(store);
        assertEquals(old.events, reopened.prepareProjection("room-b").events);
        assertEquals(Outcome.NOT_ATTEMPTED, reopened.ackProjection(old, ack(old)));
        store.acknowledged = null;
        assertEquals(old.events, coordinator(store).prepareProjection("room-b").events);
    }

    @Test public void bootstrapRejectsConflictingEventPayloadsForOneSequence() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "connect");
        Mutation original = store.current.get("mail");
        Command command = new Command("other", "definition-a", "other-op", Kind.CONNECT, 0, 0, null);
        Event conflicting = new Event("store-a", 1, "other", null, true, "CONNECT", "other-op", null, 50);
        Mutation other = new Mutation(conflicting, "selection/other-op", command, 1, 1, Phase.ACTIVATING, true, false);
        store.current.put("other", other); store.proofs.put(other.version, other);
        ConnectionStateCoordinator reopened = new ConnectionStateCoordinator(store, () -> 0L);
        assertThrows(IllegalStateException.class, reopened::initialize);
        assertEquals(Bootstrap.FAILED, reopened.read().bootstrap); assertFalse(reopened.read().locallySelected("mail"));
        assertNotEquals(original.event, conflicting);
    }

    @Test public void runtimeFailureRequiresCleanupRegardlessOfRollbackPersistence() {
        for (Outcome outcome : new Outcome[]{Outcome.CONFIRMED, Outcome.UNCERTAIN}) {
            FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
            Result selected = core.persistSelection(request(core, "connect", Kind.CONNECT)); store.candidateOutcome = outcome;
            Result failed = core.activationFailed(selected.permit);
            assertEquals(Activation.FAILED, failed.activation); assertTrue(failed.cleanupRequired);
            assertFalse(core.read().locallySelected("mail"));
            int count = store.writes.size();
            assertEquals(Disposition.CONFLICT, core.activationReturned(selected.permit).disposition);
            assertEquals(count, store.writes.size());
        }
    }

    @Test public void historicalUnchangedProofCannotReplaceNewerSameRevisionReceipt() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); core.persistSelection(request(core, "first-off", Kind.DISCONNECT));
        store.proofOutcome = Outcome.UNCERTAIN;
        RequestToken older = request(core, "older-same", Kind.DISCONNECT); core.persistSelection(older);
        store.proofOutcome = Outcome.CONFIRMED;
        RequestToken newer = request(core, "newer-same", Kind.DISCONNECT); core.persistSelection(newer);
        int writes = store.writes.size(); core.persistSelection(older);
        assertEquals(writes, store.writes.size());
        assertEquals("newer-same", core.read().choices.get("mail").mutation.operationId);
        assertEquals(1L, core.read().revision("mail")); assertFalse(core.read().locallySelected("mail"));
    }

    @Test public void lateReadyProofCanOnlyRecordHistoricalFactAfterNewDisconnect() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
        RequestToken old = request(core, "connect", Kind.CONNECT); Result selected = core.persistSelection(old);
        store.proofOutcome = Outcome.UNCERTAIN; core.activationReturned(selected.permit);
        assertNull(core.persistSelection(old).permit); // Runtime already returned: never offer another start.
        store.proofOutcome = Outcome.CONFIRMED; core.persistSelection(request(core, "off", Kind.DISCONNECT));
        int writes = store.writes.size(); Result historical = core.activationReturned(selected.permit);
        assertEquals(Disposition.SUPERSEDED, historical.disposition); assertTrue(historical.cleanupRequired);
        assertEquals(writes, store.writes.size()); assertFalse(core.read().choices.get("mail").selected());
        assertEquals(2L, core.read().revision("mail"));
    }

    @Test public void knownTrueWithPendingProofCannotReuseOlderFalseAsUnchanged() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
        core.persistSelection(request(core, "first-off", Kind.DISCONNECT));
        store.proofOutcome = Outcome.UNCERTAIN;
        core.persistSelection(request(core, "connect", Kind.CONNECT));
        assertEquals(2L, core.read().revision("mail"));
        assertTrue(core.read().factualCurrent.get("mail").selected); assertNull(core.read().observation("mail"));
        store.proofOutcome = Outcome.CONFIRMED;
        Result off = core.persistSelection(request(core, "latest-off", Kind.DISCONNECT));
        assertEquals(Disposition.ACCEPTED, off.disposition);
        Mutation mutation = core.read().choices.get("mail").mutation;
        assertEquals(Boolean.TRUE, mutation.event.previous); assertFalse(mutation.selected);
        assertEquals(3L, mutation.revision); assertEquals(3L, mutation.event.sequence);
    }

    @Test public void knownFalseWithPendingProofCannotBorrowOlderReadyForReconnect() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "first-connect");
        store.proofOutcome = Outcome.UNCERTAIN;
        core.persistSelection(request(core, "off", Kind.DISCONNECT));
        assertEquals(2L, core.read().revision("mail"));
        assertFalse(core.read().factualCurrent.get("mail").selected);
        assertNull(core.read().observation("mail")); assertFalse(core.read().locallySelected("mail"));
        store.proofOutcome = Outcome.CONFIRMED;
        Result reconnect = core.persistSelection(request(core, "reconnect", Kind.CONNECT));
        assertEquals(Disposition.ACCEPTED, reconnect.disposition); assertNotNull(reconnect.permit);
        Mutation mutation = core.read().choices.get("mail").mutation;
        assertEquals(Boolean.FALSE, mutation.event.previous); assertEquals(3L, mutation.revision);
        assertEquals(Phase.ACTIVATING, mutation.phase); assertFalse(core.read().locallySelected("mail"));
        assertTrue(core.activationReturned(reconnect.permit).snapshot.locallySelected("mail"));
    }

    @Test public void unknownCurrentAfterAttemptOrReopenDoesNotFabricatePreviousChoice() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "connect");
        store.candidateOutcome = Outcome.UNCERTAIN;
        core.persistSelection(request(core, "uncertain-off", Kind.DISCONNECT));
        assertNull(core.read().factualCurrent.get("mail")); assertNull(core.read().observation("mail"));
        ConnectionStateCoordinator reopened = coordinator(store);
        assertNull(reopened.read().factualCurrent.get("mail")); assertNull(reopened.read().observation("mail"));
        store.candidateOutcome = Outcome.CONFIRMED;
        reopened.persistSelection(request(reopened, "new-off", Kind.DISCONNECT));
        assertNull(reopened.read().choices.get("mail").mutation.event.previous);
        assertEquals(3L, reopened.read().revision("mail"));
    }

    @Test public void bootstrapRejectsCurrentPointerOlderThanAccreditedRevisionReceiptOrPhase() {
        for (int scenario = 0; scenario < 3; scenario++) {
            FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
            Result selected = core.persistSelection(request(core, "connect", Kind.CONNECT));
            Mutation stale = store.current.get("mail");
            core.activationReturned(selected.permit);
            if (scenario != 2) {
                stale = store.current.get("mail");
                core.persistSelection(request(core, "newer", scenario == 0 ? Kind.DISCONNECT : Kind.CONNECT));
            }
            store.current.put("mail", stale);
            ConnectionStateCoordinator reopened = new ConnectionStateCoordinator(store, () -> 0L);
            assertThrows(IllegalStateException.class, reopened::initialize);
            assertEquals(Bootstrap.FAILED, reopened.read().bootstrap);
            assertFalse(reopened.read().locallySelected("mail"));
        }
    }

    @Test public void exactCurrentCandidateAfterReopenIsRetainedButCannotAutomaticallyRestart() {
        FakeStorage store = new FakeStorage(); store.candidateOutcome = Outcome.UNCERTAIN;
        ConnectionStateCoordinator core = coordinator(store); RequestToken old = request(core, "connect", Kind.CONNECT);
        core.persistSelection(old); Mutation exact = store.writes.get(0);
        ConnectionStateCoordinator reopened = coordinator(store); int writes = store.writes.size(); int proofs = store.proofWrites.size();
        Result retry = reopened.persistSelection(reopened.beginIntent(old.command));
        assertEquals(Disposition.SUPERSEDED, retry.disposition);
        assertEquals(Outcome.NOT_ATTEMPTED, retry.persistence.choice); assertNull(retry.permit);
        assertEquals(writes, store.writes.size()); assertEquals(proofs, store.proofWrites.size());
        assertEquals(exact, reopened.read().candidates.get(exact.version).mutation);
        assertFalse(reopened.read().locallySelected("mail")); assertTrue(reopened.read().confirmedPending.isEmpty());
    }

    @Test public void disconnectCleanupObligationIsIndependentOfCandidateAndProofPersistence() {
        for (int failureStage = 0; failureStage < 3; failureStage++) {
            FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "connect");
            if (failureStage == 1) store.candidateOutcome = Outcome.UNCERTAIN;
            if (failureStage == 2) store.proofOutcome = Outcome.UNCERTAIN;
            RequestToken off = request(core, "off", failureStage == 2 ? Kind.UNREGISTER : Kind.DISCONNECT);
            assertFalse(core.read().locallySelected("mail"));
            Result result = core.persistSelection(off);
            assertTrue(result.cleanupRequired); assertFalse(result.snapshot.locallySelected("mail"));
        }
    }

    private static ReentrantLock lock(ConnectionStateCoordinator core, String name) {
        try { java.lang.reflect.Field field = ConnectionStateCoordinator.class.getDeclaredField(name); field.setAccessible(true); return (ReentrantLock) field.get(core); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    @Test public void bootstrapIsUnboundAndOnlyLiveSameIssuerEvidenceCanBind() {
        FakeStorage store = new FakeStorage();
        ConnectionStateCoordinator core = new ConnectionStateCoordinator(store, () -> 1L, "process", store.fence, store.provider);
        core.initialize(); assertEquals(DestinationState.UNBOUND, core.read().destinationState);
        assertNull(core.read().destinationGeneration);
        assertThrows(IllegalStateException.class, () -> core.prepareProjection("room-a"));
        FakeStorage foreign = new FakeStorage(); assertNull(core.bindDestination(foreign.evidence()));
        DestinationEvidence stale = store.evidence(); store.destinationInstance++;
        assertNull(core.bindDestination(stale)); assertFalse(core.read().destinationReady);
        DestinationBinding first = core.bindDestination(store.evidence()); assertNotNull(first);
        connect(core, "connect"); ProjectionBatch batch = core.prepareProjection("room-a");
        DestinationBinding rebound = core.bindDestination(store.evidence());
        assertNotSame(first, rebound); assertTrue(rebound.instanceVersion() > first.instanceVersion());
        assertEquals(Outcome.NOT_ATTEMPTED, core.ackProjection(batch, ack(batch))); assertEquals(0, store.cleanupCalls);
        assertSame(rebound.evidence(), core.read().binding.evidence());
    }

    @Test public void adapterBindingFailureLeavesUnboundAndInvalidatesOldAck() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "connect");
        ProjectionBatch old = core.prepareProjection("room-a"); store.throwBind = true;
        assertThrows(IllegalStateException.class, () -> core.bindDestination(store.evidence()));
        assertEquals(DestinationState.UNBOUND, core.read().destinationState); assertNull(core.read().binding);
        assertEquals(Outcome.NOT_ATTEMPTED, core.ackProjection(old, ack(old))); assertEquals(0, store.cleanupCalls);
        store.throwBind = false; core.bindDestination(store.evidence());
        assertEquals(old.events, core.prepareProjection("room-a").events);
    }

    @Test public void drainedConfirmedHistoryIsBoundedAndOldSameRevisionReceiptCannotReplayAfterReopen() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
        RequestToken oldest = request(core, "off-0", Kind.DISCONNECT); core.persistSelection(oldest);
        for (int i = 1; i <= 80; i++) {
            core.persistSelection(request(core, "off-" + i, Kind.DISCONNECT));
            ProjectionBatch batch = core.prepareProjection("room-a");
            assertEquals(Outcome.CONFIRMED, core.ackProjection(batch, ack(batch)));
            assertEquals(1, store.proofs.size()); assertTrue(store.candidates.isEmpty()); assertTrue(store.pending.isEmpty());
            assertEquals(1, store.current.size()); assertEquals(1, store.reserved.size()); assertEquals(1, store.intents.size());
        }
        long intentFloor = core.read().intentEpoch("mail"); long revision = core.read().revision("mail");
        ConnectionStateCoordinator reopened = coordinator(store);
        assertEquals(revision, reopened.read().revision("mail")); assertEquals(intentFloor, reopened.read().intentEpoch("mail"));
        int writes = store.writes.size(); assertEquals(Disposition.SUPERSEDED, reopened.beginIntent(oldest.command).disposition);
        assertEquals(writes, store.writes.size());
        Mutation last = store.current.get("mail"); assertEquals(Disposition.UNCHANGED, reopened.beginIntent(last.command).disposition);
    }

    @Test public void historicalLastSnapshotReprojectsWithoutOperationalFallbackWhenCurrentIsUncertain() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "connect");
        ProjectionBatch confirmed = core.prepareProjection("room-a"); core.ackProjection(confirmed, ack(confirmed));
        store.candidateOutcome = Outcome.UNCERTAIN; core.persistSelection(request(core, "off-unknown", Kind.DISCONNECT));
        Mutation uncertain = store.current.get("mail"); store.destination = "room-b";
        ConnectionStateCoordinator reopened = coordinator(store);
        assertFalse(reopened.read().locallySelected("mail")); assertNull(reopened.read().observation("mail"));
        assertTrue(reopened.read().choices.isEmpty()); assertEquals(confirmed.events, reopened.prepareProjection("room-b").events);
        ProjectionBatch replay = reopened.prepareProjection("room-b"); reopened.ackProjection(replay, ack(replay));
        assertEquals(uncertain, store.candidates.get(uncertain.version).mutation);
        assertTrue(store.pending.containsKey(uncertain.event.eventId)); assertEquals(1L, reopened.read().revision("mail"));
        assertEquals(2L, (long) store.reserved.get("mail"));
    }

    @Test public void unresolvedReadyKeepsItsDrainedEventProofWithoutRecreatingPending() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
        Result selected = core.persistSelection(request(core, "connect", Kind.CONNECT));
        Mutation activating = store.current.get("mail"); store.proofOutcome = Outcome.UNCERTAIN;
        core.activationReturned(selected.permit); Mutation ready = store.current.get("mail");
        ProjectionBatch batch = core.prepareProjection("room-a"); core.ackProjection(batch, ack(batch));
        assertTrue(store.pending.isEmpty()); assertEquals(ready, store.candidates.get(ready.version).mutation);
        assertEquals(activating, store.proofs.get(activating.version)); assertFalse(store.proofs.containsKey(ready.version));
        ProjectionBatch empty = core.prepareProjection("room-a"); assertTrue(empty.events.isEmpty()); core.ackProjection(empty, ack(empty));
        assertTrue(store.pending.isEmpty());
        store.proofOutcome = Outcome.CONFIRMED; core.activationReturned(selected.permit);
        ProjectionBatch resolved = core.prepareProjection("room-a"); core.ackProjection(resolved, ack(resolved));
        assertTrue(store.candidates.isEmpty()); assertEquals(Collections.singleton(ready.version), store.proofs.keySet());
    }

    @Test public void staleRetentionSnapshotCannotPruneAnyRecord() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "connect");
        ProjectionBatch batch = core.prepareProjection("room-a");
        Map<String, Event> before = new LinkedHashMap<>(store.pending); store.staleSnapshot = true;
        assertEquals(Outcome.NOT_ATTEMPTED, core.ackProjection(batch, ack(batch)));
        assertEquals(0, store.cleanupCalls); assertEquals(before, store.pending); assertFalse(core.read().confirmedPending.isEmpty());
        store.staleSnapshot = false; assertEquals(Outcome.CONFIRMED, core.ackProjection(batch, ack(batch)));
    }

    @Test public void lateHistoricalProofSurvivesOlderBatchAckThenPrunesWithoutReplacingCurrent() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
        store.proofOutcome = Outcome.UNCERTAIN; RequestToken old = request(core, "old-off", Kind.DISCONNECT);
        core.persistSelection(old); Mutation historical = store.current.get("mail");
        store.proofOutcome = Outcome.CONFIRMED; connect(core, "new-connect"); Mutation current = store.current.get("mail");
        ProjectionBatch beforeLateProof = core.prepareProjection("room-a");
        int writes = store.writes.size(); Result late = core.persistSelection(old);
        assertEquals(Disposition.SUPERSEDED, late.disposition); assertEquals(writes, store.writes.size());
        assertEquals(current, store.current.get("mail")); assertTrue(core.read().locallySelected("mail"));
        core.ackProjection(beforeLateProof, ack(beforeLateProof));
        assertEquals(Collections.singleton(historical.event.eventId), store.pending.keySet());
        assertTrue(store.proofs.containsKey(historical.version)); assertTrue(store.proofs.containsKey(current.version));
        ProjectionBatch remaining = core.prepareProjection("room-a"); assertEquals(Collections.singletonList(historical.event), remaining.events);
        core.ackProjection(remaining, ack(remaining));
        assertEquals(Collections.singleton(current.version), store.proofs.keySet()); assertTrue(store.pending.isEmpty());
        assertEquals(current, store.current.get("mail")); assertTrue(coordinator(store).read().locallySelected("mail"));
    }

    @Test public void localBootstrapAndDisconnectDoNotWaitForDestinationFence() throws Exception {
        FakeStorage store = new FakeStorage();
        ConnectionStateCoordinator core = new ConnectionStateCoordinator(store, () -> 1L, "process", store.fence, store.provider);
        assertNull(core.bindDestination(store.evidence())); // No binding while INITIALIZING.
        ExecutorService worker = Executors.newSingleThreadExecutor();
        store.fence.lock();
        try {
            Future<Result> local = worker.submit(() -> {
                BootstrapPlan plan = core.prepareBootstrap();
                assertEquals(BootstrapStatus.READY, core.applyBootstrap(plan).status);
                return core.persistSelection(request(core, "local-off", Kind.DISCONNECT));
            });
            Result result = local.get(5, TimeUnit.SECONDS); // Fence remains held here by a DIFFERENT thread.
            assertEquals(Outcome.CONFIRMED, result.persistence.proof);
            assertEquals(DestinationState.UNBOUND, result.snapshot.destinationState);
            assertFalse(result.snapshot.locallySelected("mail")); assertNull(store.binding);
        } finally { store.fence.unlock(); worker.shutdownNow(); }
    }

    @Test public void untouchedPermissionAbortPreservesChoiceButInvalidatesOldRequestAndAdvancesIntentFloor() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "original");
        Mutation baseline = core.read().factualCurrent.get("mail"); long floor = core.read().intentEpoch("mail");
        int writes = store.writes.size(); long seq = core.read().highWater;
        RequestToken pending = request(core, "permission-pending", Kind.CONNECT);
        assertFalse(core.read().locallySelected("mail"));
        AbortResult result = core.abortBeforePersistence(pending);
        assertEquals(AbortDisposition.ABORTED, result.disposition); assertTrue(result.snapshot.locallySelected("mail"));
        assertEquals(baseline, result.snapshot.factualCurrent.get("mail")); assertEquals(writes, store.writes.size());
        assertEquals(seq, result.snapshot.highWater); assertEquals(floor + 1, result.snapshot.intentEpoch("mail"));
        assertFalse(core.isCurrent(pending)); assertFalse(result.snapshot.intents.containsKey("mail"));
        Result late = core.persistSelection(pending); assertEquals(Disposition.SUPERSEDED, late.disposition);
        assertEquals(Outcome.NOT_ATTEMPTED, late.persistence.choice); assertEquals(writes, store.writes.size());
        assertEquals(Disposition.SUPERSEDED, core.beginIntent(pending.command).disposition);
    }

    @Test public void permissionAbortDoesNotInventAChoiceForPreviouslyAbsentConnector() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
        RequestToken pending = request(core, "permission-pending", Kind.CONNECT);
        assertEquals(AbortDisposition.ABORTED, core.abortBeforePersistence(pending).disposition);
        assertFalse(core.read().hasChoice("mail")); assertFalse(core.read().denied.contains("mail"));
        assertTrue(store.writes.isEmpty()); assertEquals(0L, core.read().highWater);
        assertEquals(Disposition.ACCEPTED, request(core, "fresh-default", Kind.DEFAULT_CONNECT).disposition);
    }

    @Test public void permissionAbortCannotEraseAnyAttemptedCandidateOrProofUncertainty() {
        for (int stage = 0; stage < 4; stage++) {
            FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
            if (stage == 0) store.candidateOutcome = Outcome.UNCERTAIN;
            if (stage == 1) store.throwCandidate = true;
            if (stage == 2) store.proofOutcome = Outcome.UNCERTAIN;
            RequestToken pending = request(core, "attempted", Kind.CONNECT); core.persistSelection(pending);
            ReadView before = core.read();
            assertEquals(AbortDisposition.NOT_ABORTABLE, core.abortBeforePersistence(pending).disposition);
            assertSame(before, core.read()); assertTrue(core.read().denied.contains("mail"));
            assertTrue(core.isCurrent(pending));
        }
    }

    @Test public void permissionAbortCannotClearNewerDisconnectOrReset() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "original");
        RequestToken permission = request(core, "permission", Kind.CONNECT); RequestToken off = request(core, "off", Kind.DISCONNECT);
        assertEquals(AbortDisposition.SUPERSEDED, core.abortBeforePersistence(permission).disposition);
        assertTrue(core.isCurrent(off)); assertTrue(core.read().denied.contains("mail"));
        core.reset(StoredImage.empty("new-epoch", null));
        assertEquals(AbortDisposition.SUPERSEDED, core.abortBeforePersistence(off).disposition);
    }

    @Test public void permissionAbortRejectsLateProofThatChangedItsExactBaseline() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
        store.proofOutcome = Outcome.UNCERTAIN; RequestToken previous = request(core, "previous-off", Kind.DISCONNECT);
        core.persistSelection(previous); RequestToken permission = request(core, "permission", Kind.CONNECT);
        store.proofOutcome = Outcome.CONFIRMED; core.persistSelection(previous);
        assertEquals(AbortDisposition.NOT_ABORTABLE, core.abortBeforePersistence(permission).disposition);
        assertTrue(core.isCurrent(permission)); assertTrue(core.read().denied.contains("mail"));
    }

    @Test public void connectionObservationIsAnImmutableEventValueNotObjectIdentity() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "connect");
        ReadView view = core.read(); Observation first = view.observation("mail"), copy = view.observation("mail");
        assertNotSame(first, copy); assertEquals(first, copy); assertEquals(first.hashCode(), copy.hashCode());
        assertEquals("mail", first.connectorId); assertEquals("store-a:1", first.eventId()); assertTrue(first.selected);
        core.persistSelection(request(core, "off", Kind.DISCONNECT));
        assertNotEquals(first, core.read().observation("mail")); assertEquals(first, view.observation("mail"));
    }

    @Test public void permissionAbortCannotUndoDisconnectOrUnregisterDeny() {
        for (Kind kind : new Kind[]{Kind.DISCONNECT, Kind.UNREGISTER}) {
            FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "original");
            int writes = store.writes.size(); RequestToken off = request(core, "off", kind); ReadView before = core.read();
            assertEquals(AbortDisposition.NOT_ABORTABLE, core.abortBeforePersistence(off).disposition);
            assertSame(before, core.read()); assertTrue(core.read().denied.contains("mail"));
            assertFalse(core.read().locallySelected("mail")); assertTrue(core.isCurrent(off)); assertEquals(writes, store.writes.size());
        }
    }

    @Test public void revokedBeforeRuntimeGetsRestrictiveNotStartedReceiptWithoutInventingRuntimeFailure() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
        Result selected = core.persistSelection(request(core, "connect", Kind.CONNECT)); Mutation activating = store.current.get("mail");
        Result stopped = core.activationNotStarted(selected.permit);
        assertEquals(Activation.NOT_STARTED, stopped.activation); assertFalse(stopped.cleanupRequired);
        assertEquals(Outcome.CONFIRMED, stopped.persistence.proof); assertFalse(stopped.snapshot.locallySelected("mail"));
        Mutation rollback = store.current.get("mail"); assertFalse(rollback.selected);
        assertTrue(rollback.revision > activating.revision); assertTrue(rollback.event.sequence > activating.event.sequence);
        assertEquals("CONNECT_ROLLBACK", rollback.event.reason); int writes = store.writes.size();
        assertEquals(Disposition.UNCHANGED, core.activationNotStarted(selected.permit).disposition);
        assertEquals(writes, store.writes.size()); assertEquals(Disposition.CONFLICT, core.activationReturned(selected.permit).disposition);
    }

    @Test public void notStartedAfterNewerOffNeverPersistsAnotherRollback() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
        Result selected = core.persistSelection(request(core, "connect", Kind.CONNECT));
        RequestToken off = request(core, "off", Kind.DISCONNECT); int writes = store.writes.size();
        Result stopped = core.activationNotStarted(selected.permit);
        assertEquals(Disposition.SUPERSEDED, stopped.disposition); assertEquals(Activation.NOT_STARTED, stopped.activation);
        assertEquals(Outcome.NOT_ATTEMPTED, stopped.persistence.choice); assertFalse(stopped.cleanupRequired);
        assertEquals(writes, store.writes.size()); assertTrue(core.isCurrent(off)); assertTrue(core.read().denied.contains("mail"));
    }

    @Test public void connectAfterUnpersistedDisconnectCannotBorrowPriorReadyPhase() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "original");
        Mutation previous = store.current.get("mail"); RequestToken off = request(core, "off", Kind.DISCONNECT);
        RequestToken on = request(core, "on", Kind.CONNECT); Result selected = core.persistSelection(on);
        assertEquals(Disposition.ACCEPTED, selected.disposition); assertNotNull(selected.permit);
        assertEquals(Phase.ACTIVATING, store.current.get("mail").phase); assertFalse(core.read().locallySelected("mail"));
        assertEquals(Boolean.TRUE, store.current.get("mail").event.previous); assertTrue(store.current.get("mail").event.selected);
        assertTrue(store.current.get("mail").event.sequence > previous.event.sequence);
        assertEquals(Disposition.SUPERSEDED, core.persistSelection(off).disposition);
        assertTrue(core.activationReturned(selected.permit).snapshot.locallySelected("mail"));
    }

    @Test public void abortedOnRestoresDisplacedOffAsNewSingleUseRestrictiveChild() {
        for (boolean unknown : new boolean[]{false, true}) {
            FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "original");
            RequestToken off = request(core, "off", Kind.DISCONNECT);
            Mutation uncertain = null;
            if (unknown) { store.candidateOutcome = Outcome.UNCERTAIN; core.persistSelection(off); uncertain = store.current.get("mail"); }
            RequestToken on = request(core, "permission-on", Kind.CONNECT);
            assertEquals(AbortDisposition.ABORTED, core.abortBeforePersistence(on).disposition);
            OffRestoration restored = core.restoreOffAfterPermissionAbort(off, on);
            assertEquals(OffRestoration.Status.RESTORED, restored.status); assertNotNull(restored.request);
            assertNotSame(off, restored.request); assertEquals(Kind.DISCONNECT_AFTER_PERMISSION_ABORT, restored.request.command.kind);
            assertEquals(off.command.operationId, restored.request.command.parentOperationId);
            assertSame(restored, core.restoreOffAfterPermissionAbort(off, on));
            assertEquals(Disposition.CONFLICT, core.beginIntent(restored.request.command).disposition);
            store.candidateOutcome = Outcome.CONFIRMED; Result result = core.persistSelection(restored.request);
            assertEquals(Outcome.CONFIRMED, result.persistence.proof); assertFalse(result.snapshot.locallySelected("mail"));
            assertEquals("DISCONNECT_AFTER_PERMISSION_ABORT", store.current.get("mail").event.reason);
            assertEquals(Disposition.SUPERSEDED, core.persistSelection(off).disposition);
            if (uncertain != null) assertEquals(uncertain, core.read().candidates.get(uncertain.version).mutation);
        }
    }

    @Test public void alreadyProvenOffAfterPermissionAbortNeedsNoInventedRecoveryWrite() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "original");
        RequestToken off = request(core, "off", Kind.DISCONNECT); core.persistSelection(off);
        RequestToken on = request(core, "permission-on", Kind.CONNECT); core.abortBeforePersistence(on);
        int writes = store.writes.size(); long floor = core.read().intentEpoch("mail");
        OffRestoration restored = core.restoreOffAfterPermissionAbort(off, on);
        assertEquals(OffRestoration.Status.NOT_NEEDED, restored.status); assertNull(restored.request);
        assertEquals(writes, store.writes.size()); assertEquals(floor, core.read().intentEpoch("mail"));
        assertFalse(core.read().locallySelected("mail")); assertFalse(core.read().factualCurrent.get("mail").selected);
    }

    @Test public void offRestorationCannotOverrideNewIntentOrReset() {
        for (int scenario = 0; scenario < 3; scenario++) {
            FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store); connect(core, "original");
            RequestToken off = request(core, "off", Kind.DISCONNECT), on = request(core, "permission-on", Kind.CONNECT);
            core.abortBeforePersistence(on);
            if (scenario == 2) core.reset(StoredImage.empty("reset", null));
            else request(core, "newer", scenario == 0 ? Kind.CONNECT : Kind.DISCONNECT);
            int writes = store.writes.size(); ReadView before = core.read();
            assertEquals(OffRestoration.Status.SUPERSEDED, core.restoreOffAfterPermissionAbort(off, on).status);
            assertSame(before, core.read()); assertEquals(writes, store.writes.size());
        }
    }

    @Test public void lateOffProofChangingAbortBaselineNeverCreatesFalseChild() {
        FakeStorage store = new FakeStorage(); ConnectionStateCoordinator core = coordinator(store);
        RequestToken off = request(core, "off", Kind.DISCONNECT); store.proofOutcome = Outcome.UNCERTAIN; core.persistSelection(off);
        RequestToken on = request(core, "permission-on", Kind.CONNECT); store.proofOutcome = Outcome.CONFIRMED; core.persistSelection(off);
        assertEquals(AbortDisposition.NOT_ABORTABLE, core.abortBeforePersistence(on).disposition);
        int writes = store.writes.size();
        assertEquals(OffRestoration.Status.INVALID, core.restoreOffAfterPermissionAbort(off, on).status);
        assertEquals(writes, store.writes.size()); assertTrue(core.isCurrent(on)); assertTrue(core.read().denied.contains("mail"));
    }

    @Test public void provisionalRestrictiveResultNeverClaimsDurabilityOrActivation() {
        for (Kind kind : new Kind[]{Kind.DISCONNECT, Kind.UNREGISTER}) {
            Result result = ConnectionStateCoordinator.pendingRestrictive(kind);
            assertEquals(Disposition.ACCEPTED, result.disposition); assertTrue(result.cleanupRequired);
            assertEquals(Outcome.NOT_ATTEMPTED, result.persistence.choice); assertEquals(Outcome.NOT_ATTEMPTED, result.persistence.proof);
            assertTrue(result.provisional); assertNull(result.snapshot); assertNull(result.projectionPending); assertNull(result.permit);
        }
        assertThrows(IllegalArgumentException.class, () -> ConnectionStateCoordinator.pendingRestrictive(Kind.CONNECT));
        assertThrows(IllegalArgumentException.class, () -> ConnectionStateCoordinator.pendingRestrictive(Kind.DISCONNECT_AFTER_PERMISSION_ABORT));
    }

}
