package com.jarvys.agent.connectors

import org.junit.Assert.*
import org.junit.Test

class GoogleWorkspaceWriteJournalTest {
    private class Store : GoogleWorkspaceWriteJournalStore {
        var hashes = emptySet<String>()
        var writable = true
        var writes = 0
        override fun read() = hashes.toSet()
        override fun write(hashes: Set<String>): Boolean {
            writes++
            if (!writable) return false
            this.hashes = hashes.toSet()
            return true
        }
    }
    private fun hash(index: Int) = index.toString(16).padStart(64, '0')

    @Test fun reservationSurvivesRuntimeRecreationAndContainsOnlyDigests() {
        val store = Store()
        BoundedGoogleWorkspaceWriteJournal(store).reserve(hash(1))
        val recreated = BoundedGoogleWorkspaceWriteJournal(store)
        assertTrue(recreated.isUncertain(hash(1)))
        assertTrue(runCatching { recreated.reserve(hash(1)) }.isFailure)
        assertEquals(setOf(hash(1)), store.hashes)
        assertTrue(recreated.resolve(hash(1)))
        assertFalse(BoundedGoogleWorkspaceWriteJournal(store).isUncertain(hash(1)))
    }

    @Test fun fullJournalNeverEvictsUnreconciledWrites() {
        val store = Store()
        val journal = BoundedGoogleWorkspaceWriteJournal(store)
        repeat(BoundedGoogleWorkspaceWriteJournal.MAX_ENTRIES) { journal.reserve(hash(it)) }
        assertTrue(runCatching { journal.reserve(hash(999)) }.isFailure)
        assertEquals(BoundedGoogleWorkspaceWriteJournal.MAX_ENTRIES, store.hashes.size)
        repeat(BoundedGoogleWorkspaceWriteJournal.MAX_ENTRIES) { assertTrue(journal.isUncertain(hash(it))) }
        assertTrue(journal.resolve(hash(1)))
        journal.reserve(hash(999))
        assertTrue(journal.isUncertain(hash(999)))
    }

    @Test fun persistenceFailureBlocksReservationAndFailedClearRetainsMarker() {
        val store = Store().apply { writable = false }
        val journal = BoundedGoogleWorkspaceWriteJournal(store)
        assertTrue(runCatching { journal.reserve(hash(1)) }.isFailure)
        assertTrue(journal.isUncertain(hash(1)))
        store.writable = true
        journal.reserve(hash(2))
        store.writable = false
        assertFalse(journal.resolve(hash(2)))
        assertTrue(journal.isUncertain(hash(2)))
    }

    @Test fun corruptedOrOverCapacityStorageFailsClosed() {
        val store = Store().apply { hashes = setOf("not-a-hash") }
        val journal = BoundedGoogleWorkspaceWriteJournal(store)
        assertTrue(runCatching { journal.isUncertain(hash(1)) }.isFailure)
        assertTrue(runCatching { journal.reserve(hash(1)) }.isFailure)
        store.hashes = (1..129).map(::hash).toSet()
        assertTrue(runCatching { journal.reserve(hash(999)) }.isFailure)
        assertEquals(0, store.writes)
    }

    private class SharedBacking {
        var values = emptySet<String>()
        var commitSucceeds = true
    }
    private fun wrapper(backing: SharedBacking) = object : GoogleWorkspaceWriteJournalStore {
        override val identity: Any = backing
        override fun read() = backing.values.toSet()
        override fun write(hashes: Set<String>): Boolean {
            // Android SharedPreferences can expose the new process-memory value even on commit(false).
            backing.values = hashes.toSet()
            return backing.commitSucceeds
        }
    }

    @Test fun failedClearThatMutatesMemoryRemainsBlockedAcrossIndependentStoreWrappers() {
        val backing = SharedBacking()
        val first = BoundedGoogleWorkspaceWriteJournal(wrapper(backing))
        val second = BoundedGoogleWorkspaceWriteJournal(wrapper(backing))
        first.reserve(hash(1))
        backing.commitSucceeds = false
        assertFalse(first.resolve(hash(1)))
        assertTrue(backing.values.isEmpty())
        assertTrue(second.isUncertain(hash(1)))
        assertTrue(runCatching { second.reserve(hash(1)) }.isFailure)
        val recreatedWrapper = BoundedGoogleWorkspaceWriteJournal(wrapper(backing))
        assertTrue(recreatedWrapper.isUncertain(hash(1)))
    }

    @Test fun failedReservationAndFailedClearOfLoadedMarkerShareFailureState() {
        val backing = SharedBacking().apply { commitSucceeds = false }
        val first = BoundedGoogleWorkspaceWriteJournal(wrapper(backing))
        assertTrue(runCatching { first.reserve(hash(2)) }.isFailure)
        backing.values = emptySet() // A later disk re-read can omit the failed reservation.
        val second = BoundedGoogleWorkspaceWriteJournal(wrapper(backing))
        assertTrue(second.isUncertain(hash(2)))
        assertTrue(runCatching { second.reserve(hash(2)) }.isFailure)
        val loaded = SharedBacking().apply { values = setOf(hash(3)); commitSucceeds = false }
        assertFalse(BoundedGoogleWorkspaceWriteJournal(wrapper(loaded)).resolve(hash(3)))
        assertTrue(loaded.values.isEmpty())
        assertTrue(BoundedGoogleWorkspaceWriteJournal(wrapper(loaded)).isUncertain(hash(3)))
    }

    @Test fun catalogConstructionDoesNotInitializePrivateJournalStorage() {
        var reads = 0
        val context = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                reads++
                error("No write was requested")
            }
        }
        val journal = GoogleWorkspaceWriteJournals.persistent(context)
        assertEquals(0, reads)
        assertTrue(runCatching { journal.isUncertain(hash(1)) }.isFailure)
        assertEquals(1, reads)
    }

}
