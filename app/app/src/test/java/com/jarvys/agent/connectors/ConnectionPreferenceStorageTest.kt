package com.jarvys.agent.connectors

import com.jarvys.agent.connectors.ConnectionStateCoordinator.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.ArrayDeque

/** Fault model separates process memory from durable bytes. It is not an Android fsync claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionPreferenceStorageTest {
    private enum class Fault { BEFORE_THROW, AFTER_APPLY_FALSE, AFTER_APPLY_THROW, AFTER_DISK_FALSE, TRUE }
    private class Backend : ConnectionPreferenceBackend {
        val memory = linkedMapOf<String, Any>()
        val disk = linkedMapOf<String, Any>()
        val faults = ArrayDeque<Fault>()
        var commits = 0
        override fun snapshot(): Map<String, *> = HashMap(memory)
        override fun commit(writes: Map<String, Any>, removals: Set<String>): Boolean {
            commits++
            val fault = if (faults.isEmpty()) Fault.TRUE else faults.removeFirst()
            if (fault == Fault.BEFORE_THROW) throw IllegalStateException("synthetic failure")
            removals.forEach { memory.remove(it) }; memory.putAll(writes)
            if (fault == Fault.AFTER_APPLY_THROW) throw IllegalStateException("synthetic failure")
            if (fault == Fault.AFTER_APPLY_FALSE) return false
            disk.clear(); disk.putAll(memory)
            return fault != Fault.AFTER_DISK_FALSE
        }
        fun piggyback() { commit(mapOf("policy_fixture" to "kept"), emptySet()) }
        fun reopen() = Backend().also { it.memory.putAll(disk); it.disk.putAll(disk) }
    }
    private val projectionFence = java.util.concurrent.locks.ReentrantLock()
    private val providerIdentity = Any()
    private fun storage(backend: Backend) = SharedPreferencesConnectionStorage(backend)
    private fun coordinator(store: SharedPreferencesConnectionStorage) =
        ConnectionStateCoordinator(store, { 7L }, "process-one", projectionFence, providerIdentity)
    private fun evidence(generation: String, current: () -> Boolean = { true }) = object : DestinationEvidence {
        override fun generation() = generation
        override fun providerIdentity() = this@ConnectionPreferenceStorageTest.providerIdentity
        override fun fenceIdentity() = projectionFence
        override fun isCurrentPure() = current()
    }
    private fun mutation(epoch: String, sequence: Long = 1, selected: Boolean = true,
                         phase: Phase = if (selected) Phase.ACTIVATING else Phase.NONE): Mutation {
        val kind = if (selected) Kind.CONNECT else Kind.DISCONNECT
        val command = Command("fixture", "definition-one", "operation-$sequence", kind, sequence - 1, sequence - 1, null)
        val event = Event(epoch, sequence, "fixture", if (sequence == 1L) null else !selected,
            selected, if (selected) "LOCAL_CONNECT_SELECTED" else "LOCAL_DISCONNECT_SELECTED", command.operationId, null, -7)
        return Mutation(event, "version-$sequence-${phase.name}", command, sequence, sequence,
            phase, selected, false, Origin.REQUEST, null, "process-one")
    }
    private inline fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun codecRoundTripsFullMutationAndRejectsMalformedLengthsUtf8AndTrailingBytes() {
        val m = mutation("epoch")
        assertEquals(m, ConnectionStateCodec.mutation(ConnectionStateCodec.mutation(m)))
        val maximum = mutation("epoch", Long.MAX_VALUE, false)
        assertEquals(maximum, ConnectionStateCodec.mutation(ConnectionStateCodec.mutation(maximum)))
        val event = ConnectionStateCodec.event(m.event)
        assertEquals(m.event, ConnectionStateCodec.event(event))
        rejected { ConnectionStateCodec.event(event + byteArrayOf(0)) }
        rejected { ConnectionStateCodec.event(event.copyOf(4)) }
        val tooLong = event.copyOf(); (5..8).forEach { tooLong[it] = 0x7f }
        rejected { ConnectionStateCodec.event(tooLong) }
        val invalidUtf8 = event.copyOf(); invalidUtf8[9] = 0xff.toByte()
        rejected { ConnectionStateCodec.event(invalidUtf8) }
        val invalidSequence = event.copyOf()
        java.nio.ByteBuffer.wrap(invalidSequence).putLong(14, -1L) // header + length + "epoch".
        rejected { ConnectionStateCodec.event(invalidSequence) }
    }
    @Test fun initialFalseThenUnrelatedCommitAndReopenNeverCreatesProof() {
        val backend = Backend(); val store = storage(backend); val empty = store.load()
        val m = mutation(empty.storeEpoch)
        backend.faults.add(Fault.AFTER_APPLY_FALSE)
        assertEquals(Outcome.UNCERTAIN, store.writeCandidate(m))
        try { store.writeProof(m); fail("False initial commit cannot mint proof") } catch (_: IllegalStateException) { }
        backend.piggyback()
        val loaded = storage(backend.reopen()).load()
        assertEquals(m, loaded.current["fixture"])
        assertEquals(m, loaded.candidates[m.version]!!.mutation)
        assertFalse(loaded.candidates[m.version]!!.choiceConfirmed)
        assertTrue(loaded.proofs.isEmpty())
        assertEquals(1L, loaded.highWater)
    }
    @Test fun initialTrueProofFalsePiggybackRetainsTruthfulExactProof() {
        val backend = Backend(); val store = storage(backend); val m = mutation(store.load().storeEpoch)
        assertEquals(Outcome.CONFIRMED, store.writeCandidate(m))
        backend.faults.add(Fault.AFTER_APPLY_FALSE)
        assertEquals(Outcome.UNCERTAIN, store.writeProof(m))
        backend.piggyback()
        val loaded = storage(backend.reopen()).load()
        assertEquals(m, loaded.proofs[m.version])
        assertEquals(Phase.ACTIVATING, loaded.proofs[m.version]!!.phase)
        assertEquals("kept", backend.disk["policy_fixture"])
    }
    @Test fun crashBeforeProofDoesNotPromoteInitialTrueAndOldProofDoesNotProveReady() {
        val backend = Backend(); val store = storage(backend); val m = mutation(store.load().storeEpoch)
        assertEquals(Outcome.CONFIRMED, store.writeCandidate(m))
        assertTrue(storage(backend.reopen()).load().proofs.isEmpty())
        assertEquals(Outcome.CONFIRMED, store.writeProof(m))
        val ready = Mutation(m.event, "ready-version", m.command, m.revision, m.highWater,
            Phase.READY, true, false, Origin.REQUEST, null, "process-one")
        assertEquals(Outcome.CONFIRMED, store.writeCandidate(ready))
        val loaded = storage(backend.reopen()).load()
        assertEquals(ready, loaded.current["fixture"])
        assertEquals(m, loaded.proofs[m.version])
        assertNull(loaded.proofs[ready.version])
    }
    @Test fun throwsAndDiskWrittenFalseRemainUnprovenAcrossReopen() {
        for (fault in listOf(Fault.AFTER_APPLY_THROW, Fault.AFTER_DISK_FALSE)) {
            val backend = Backend(); val store = storage(backend); val m = mutation(store.load().storeEpoch)
            backend.faults.add(fault)
            assertEquals(Outcome.UNCERTAIN, store.writeCandidate(m))
            backend.piggyback()
            val loaded = storage(backend.reopen()).load()
            assertEquals(m, loaded.candidates[m.version]!!.mutation)
            assertTrue(loaded.proofs.isEmpty())
        }
    }
    @Test fun metadataFalsePiggybackReusesExactEpochAndPreservesForeignKeys() {
        for (failProof in listOf(false, true)) {
            val backend = Backend()
            backend.memory["connected_other"] = false
            backend.memory["permission_asked_fixture"] = true
            if (failProof) backend.faults.add(Fault.TRUE)
            backend.faults.add(Fault.AFTER_APPLY_FALSE)
            try { storage(backend).load(); fail("Metadata not confirmed") } catch (_: StorageUnconfirmedException) { }
            val epoch = backend.memory[SharedPreferencesConnectionStorage.EPOCH]
            backend.piggyback()
            val reopened = backend.reopen(); val loaded = storage(reopened).load()
            assertEquals(epoch, loaded.storeEpoch)
            assertNull(loaded.destinationGeneration)
            assertNull(loaded.lastAcknowledgedGeneration)
            assertTrue(loaded.current.isEmpty())
            assertEquals(false, reopened.memory["connected_other"])
            assertEquals(true, reopened.memory["permission_asked_fixture"])
        }
    }
    @Test fun conflictingVersionCannotOverwriteExactCandidateOrMintProof() {
        val backend = Backend(); val store = storage(backend); val m = mutation(store.load().storeEpoch)
        store.writeCandidate(m)
        val conflicting = Mutation(m.event, m.version, m.command, m.revision, m.highWater,
            Phase.READY, true, false, Origin.REQUEST, null, "process-one")
        val before = backend.commits
        rejected { store.writeCandidate(conflicting) }
        rejected { store.writeProof(conflicting) }
        assertEquals(before, backend.commits)
        assertEquals(m, storage(backend.reopen()).load().current["fixture"])
    }
    @Test fun cleanupFalseKeepsCoordinatorPendingAndReplaysExactEventAfterReopen() {
        val backend = Backend(); val store = storage(backend)
        val coordinator = coordinator(store)
        coordinator.initialize()
        val token = coordinator.beginIntent(Command("fixture", "definition", "disconnect", Kind.DISCONNECT, 0, 0, null))
        assertEquals(Outcome.CONFIRMED, coordinator.persistSelection(token).persistence.proof)
        coordinator.bindDestination(evidence("destination-one"))
        val batch = coordinator.prepareProjection("destination-one")
        assertEquals(1, batch.events.size)
        backend.faults.add(Fault.AFTER_APPLY_FALSE)
        assertEquals(Outcome.UNCERTAIN, coordinator.ackProjection(batch,
            Ack(batch.sourceEpoch, batch.targetGeneration, batch.events)))
        assertEquals(1, coordinator.read().confirmedPending.size)
        val diskImage = storage(backend.reopen()).load()
        assertEquals(batch.events.single(), diskImage.pending.values.single())
        assertNull(diskImage.lastAcknowledgedGeneration)
        assertEquals(Outcome.CONFIRMED, coordinator.ackProjection(batch,
            Ack(batch.sourceEpoch, batch.targetGeneration, batch.events)))
        assertTrue(coordinator.read().confirmedPending.isEmpty())
        val delivered = storage(backend.reopen()).load()
        assertTrue(delivered.pending.isEmpty())
        assertEquals("destination-one", delivered.lastAcknowledgedGeneration)
        assertFalse(delivered.proofs.isEmpty()) // No unreviewed pruning of replay/proof records.
    }
    @Test fun malformedProtocolKeyDoesNotRewriteOrClearExistingBytes() {
        val backend = Backend(); val store = storage(backend); val m = mutation(store.load().storeEpoch)
        store.writeCandidate(m); store.writeProof(m)
        backend.memory[SharedPreferencesConnectionStorage.CURRENT + "other-id"] =
            backend.memory[SharedPreferencesConnectionStorage.CURRENT + "fixture"]!!
        backend.piggyback()
        val reopened = backend.reopen(); val before = HashMap(reopened.memory)
        rejected { storage(reopened).load() }
        assertEquals(before, reopened.memory)
        assertEquals(0, reopened.commits)
    }

    @Test fun rebindingRequiresNewOpaqueBindingAndOldAckNeverDrainsPending() {
        val backend = Backend(); val coordinator = coordinator(storage(backend))
        coordinator.initialize()
        coordinator.persistSelection(coordinator.beginIntent(Command("fixture", "definition", "disconnect", Kind.DISCONNECT, 0, 0, null)))
        coordinator.bindDestination(evidence("destination-one"))
        val old = coordinator.prepareProjection("destination-one")
        coordinator.invalidateDestination()
        coordinator.bindDestination(evidence("destination-two"))
        val before = backend.commits
        assertEquals(Outcome.NOT_ATTEMPTED, coordinator.ackProjection(old,
            Ack(old.sourceEpoch, old.targetGeneration, old.events)))
        assertEquals(before, backend.commits)
        assertEquals(1, coordinator.read().confirmedPending.size)
        val current = coordinator.prepareProjection("destination-two")
        assertEquals(Outcome.CONFIRMED, coordinator.ackProjection(current,
            Ack(current.sourceEpoch, current.targetGeneration, current.events)))
        assertTrue(coordinator.read().confirmedPending.isEmpty())
        assertEquals("destination-two", storage(backend.reopen()).load().lastAcknowledgedGeneration)
    }
    @Test fun failedLoadRetryDoesNotKeepPartiallyParsedGhostRecords() {
        val backend = Backend(); val first = storage(backend); val m = mutation(first.load().storeEpoch)
        first.writeCandidate(m); first.writeProof(m)
        val currentKey = SharedPreferencesConnectionStorage.CURRENT + "fixture"
        backend.memory[SharedPreferencesConnectionStorage.PREFIX + "unknown_record"] = "malformed"
        val retried = storage(backend)
        rejected { retried.load() }
        backend.memory.remove(SharedPreferencesConnectionStorage.PREFIX + "unknown_record")
        backend.memory.remove(currentKey)
        backend.memory.remove(SharedPreferencesConnectionStorage.CANDIDATE + m.version)
        backend.memory.remove(SharedPreferencesConnectionStorage.PROOF + m.version)
        backend.memory.remove(SharedPreferencesConnectionStorage.PENDING + m.event.eventId)
        val image = retried.load()
        assertTrue(image.current.isEmpty())
        assertTrue(image.candidates.isEmpty())
        assertTrue(image.proofs.isEmpty())
        assertTrue(image.pending.isEmpty())
    }

    @Test fun throwBeforeApplyingBytesRetriesSameMutationAndCannotProveBeforeTrue() {
        val backend = Backend(); val store = storage(backend); val m = mutation(store.load().storeEpoch)
        backend.faults.add(Fault.BEFORE_THROW)
        assertEquals(Outcome.UNCERTAIN, store.writeCandidate(m))
        assertFalse(backend.memory.containsKey(SharedPreferencesConnectionStorage.CURRENT + "fixture"))
        assertFalse(backend.disk.containsKey(SharedPreferencesConnectionStorage.CANDIDATE + m.version))
        try { store.writeProof(m); fail("Unconfirmed attempt cannot mint proof") }
        catch (_: IllegalStateException) { }
        assertEquals(Outcome.CONFIRMED, store.writeCandidate(m))
        assertEquals(Outcome.CONFIRMED, store.writeProof(m))
        val restored = storage(backend.reopen()).load()
        assertEquals(m, restored.current["fixture"])
        assertEquals(m, restored.proofs[m.version])
        assertEquals(1L, restored.highWater)
        assertEquals(setOf(m.event.eventId), restored.pending.keys)
    }

    @Test fun absentRetiredKeysRemainExcludedFromExplicitAndDefaultConnections() {
        val backend = Backend(); val coordinator = coordinator(storage(backend))
        assertEquals(BootstrapStatus.READY, coordinator.applyBootstrap(coordinator.prepareBootstrap()).status)
        val before = backend.commits
        for (id in listOf("email", "sms_draft")) for (kind in listOf(Kind.CONNECT, Kind.DEFAULT_CONNECT)) {
            val token = coordinator.beginIntent(Command(id, "definition", "$id-$kind", kind, 0, 0, null))
            assertEquals(Disposition.SUPERSEDED, token.disposition)
            assertEquals(Outcome.NOT_ATTEMPTED, coordinator.persistSelection(token).persistence.choice)
            assertFalse(coordinator.read().locallySelected(id))
            assertFalse(backend.memory.containsKey("connected_$id"))
        }
        assertEquals(before, backend.commits)
    }

    private fun choose(core: ConnectionStateCoordinator, id: String, operation: String, selected: Boolean): Result {
        val view = core.read()
        val token = core.beginIntent(Command(id, "definition", operation, if (selected) Kind.CONNECT else Kind.DISCONNECT,
            view.revision(id), view.intentEpoch(id), null))
        return core.persistSelection(token)
    }
    private fun acknowledge(core: ConnectionStateCoordinator, generation: String): Outcome {
        val batch = core.prepareProjection(generation)
        return core.ackProjection(batch, Ack(batch.sourceEpoch, batch.targetGeneration, batch.events))
    }
    @Test fun successfulAckPrunesDrainedHistoryButPreservesLastReceiptAndReservationFloors() {
        val backend = Backend(); backend.memory["action_policy_fixture"] = "ASK"
        val store = storage(backend); val core = coordinator(store); core.initialize()
        val firstCommand = Command("fixture", "definition", "operation-1", Kind.CONNECT, 0, 0, null)
        for (index in 1..30) {
            val result = choose(core, "fixture", "operation-$index", index % 2 == 1)
            assertEquals(Outcome.CONFIRMED, result.persistence.proof)
            if (result.permit != null) assertEquals(Outcome.CONFIRMED, core.activationReturned(result.permit).persistence.proof)
        }
        assertTrue(store.retentionSnapshot().image.proofs.size > 30)
        core.bindDestination(evidence("destination-one"))
        assertEquals(Outcome.CONFIRMED, acknowledge(core, "destination-one"))
        assertEquals(1, backend.disk.keys.count { it.startsWith(SharedPreferencesConnectionStorage.PROOF) })
        assertEquals(0, backend.disk.keys.count { it.startsWith(SharedPreferencesConnectionStorage.CANDIDATE) })
        assertEquals(0, backend.disk.keys.count { it.startsWith(SharedPreferencesConnectionStorage.PENDING) })
        assertEquals("ASK", backend.disk["action_policy_fixture"])
        val reopenedBackend = backend.reopen(); val reopened = coordinator(storage(reopenedBackend)); reopened.initialize()
        assertEquals("operation-30", reopened.read().choices["fixture"]!!.mutation.command.operationId)
        assertEquals(core.read().revision("fixture"), reopened.read().revision("fixture"))
        assertEquals(core.read().intentEpoch("fixture"), reopened.read().intentEpoch("fixture"))
        assertEquals(core.read().highWater, reopened.read().highWater)
        val before = reopenedBackend.commits
        assertEquals(Disposition.SUPERSEDED, reopened.beginIntent(firstCommand).disposition)
        assertEquals(before, reopenedBackend.commits)
        assertEquals(DestinationState.UNBOUND, reopened.read().destinationState)
        reopened.bindDestination(evidence("destination-rebuilt"))
        assertEquals(1, reopened.prepareProjection("destination-rebuilt").events.size)
    }
    @Test fun beforeBytesCandidateIsCarriedByLaterCommitWithoutInventingCurrentOrProof() {
        val backend = Backend(); val store = storage(backend); val core = coordinator(store); core.initialize()
        backend.faults.add(Fault.BEFORE_THROW)
        assertEquals(Outcome.UNCERTAIN, choose(core, "orphan", "uncertain-off", false).persistence.choice)
        val orphan = core.read().candidates.values.single().mutation
        assertFalse(backend.memory.containsKey(SharedPreferencesConnectionStorage.CURRENT + "orphan"))
        assertEquals(Outcome.CONFIRMED, choose(core, "other", "other-off", false).persistence.proof)
        assertTrue(backend.disk.containsKey(SharedPreferencesConnectionStorage.CANDIDATE + orphan.version))
        assertTrue(backend.disk.containsKey(SharedPreferencesConnectionStorage.PENDING + orphan.event.eventId))
        assertFalse(backend.disk.containsKey(SharedPreferencesConnectionStorage.CURRENT + "orphan"))
        assertFalse(backend.disk.containsKey(SharedPreferencesConnectionStorage.PROOF + orphan.version))
        val reopenedBackend = backend.reopen(); val reopened = coordinator(storage(reopenedBackend))
        val attempt = reopened.applyBootstrap(reopened.prepareBootstrap())
        assertEquals(BootstrapStatus.READY, attempt.status)
        assertFalse(reopened.read().locallySelected("orphan"))
        assertEquals("PERSISTENCE_RECOVERY_UNCONFIRMED", reopened.read().choices["orphan"]!!.mutation.event.reason)
        assertEquals(orphan, reopened.read().candidates[orphan.version]!!.mutation)
        val view = reopened.read(); val before = reopenedBackend.commits
        val token = reopened.beginIntent(Command("orphan", "definition", "default", Kind.DEFAULT_CONNECT,
            view.revision("orphan"), view.intentEpoch("orphan"), null))
        assertEquals(Disposition.SUPERSEDED, token.disposition)
        assertEquals(Outcome.NOT_ATTEMPTED, reopened.persistSelection(token).persistence.choice)
        assertEquals(before, reopenedBackend.commits)
    }
    @Test fun unresolvedReadyRetainsItsEventProofWithoutRecreatingDrainedPending() {
        val backend = Backend(); val store = storage(backend); val core = coordinator(store); core.initialize()
        val activation = choose(core, "fixture", "connect", true)
        val eventId = core.read().choices["fixture"]!!.mutation.event.eventId
        backend.faults.add(Fault.TRUE); backend.faults.add(Fault.BEFORE_THROW)
        assertEquals(Outcome.UNCERTAIN, core.activationReturned(activation.permit).persistence.proof)
        val uncertain = core.read().candidates.values.single().mutation
        core.bindDestination(evidence("destination-one"))
        assertEquals(Outcome.CONFIRMED, acknowledge(core, "destination-one"))
        assertFalse(backend.disk.containsKey(SharedPreferencesConnectionStorage.PENDING + eventId))
        assertTrue(backend.disk.containsKey(SharedPreferencesConnectionStorage.CANDIDATE + uncertain.version))
        assertEquals(1, store.retentionSnapshot().image.proofs.size)
        assertEquals(Outcome.CONFIRMED, choose(core, "other", "other-off", false).persistence.proof)
        assertFalse(backend.disk.containsKey(SharedPreferencesConnectionStorage.PENDING + eventId))
        assertFalse(backend.disk.containsKey(SharedPreferencesConnectionStorage.PROOF + uncertain.version))
    }
    @Test fun uncertainPruneRetainsCacheUntilAnExactReplannedAckConfirms() {
        val backend = Backend(); val store = storage(backend); val core = coordinator(store); core.initialize()
        for (index in 1..4) {
            val result = choose(core, "fixture", "operation-$index", index % 2 == 1)
            if (result.permit != null) core.activationReturned(result.permit)
        }
        core.bindDestination(evidence("destination-one"))
        val before = store.retentionSnapshot()
        backend.faults.add(Fault.AFTER_APPLY_FALSE)
        assertEquals(Outcome.UNCERTAIN, acknowledge(core, "destination-one"))
        val uncertain = store.retentionSnapshot()
        assertTrue(uncertain.version > before.version)
        assertEquals(before.image.proofs, uncertain.image.proofs)
        assertEquals(before.image.pending, uncertain.image.pending)
        assertEquals(Outcome.CONFIRMED, acknowledge(core, "destination-one"))
        assertEquals(1, store.retentionSnapshot().image.proofs.size)
        assertTrue(store.retentionSnapshot().image.pending.isEmpty())
        assertEquals(1, storage(backend.reopen()).load().proofs.size)
    }

}
