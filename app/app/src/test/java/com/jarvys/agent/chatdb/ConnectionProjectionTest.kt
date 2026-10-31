package com.jarvys.agent.chatdb

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ChatTestStore
import com.jarvys.agent.SingleSessionReset
import com.jarvys.agent.connectors.ConnectionStateCoordinator
import com.jarvys.agent.connectors.ConnectionStateCoordinator.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConnectionProjectionTest {
    @get:Rule val fixture = ChatTestStore()
    private lateinit var context: Context
    private lateinit var bridge: ChatDatabaseBridge
    private lateinit var db: JarvysChatDatabase
    private lateinit var anchor: ConnectionProjectionAnchor
    private lateinit var core: ConnectionStateCoordinator
    private lateinit var source: Source

    @Before fun setup() {
        val files = fixture.newFilesDirectory()
        fixture.localRunStore(files)
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        runBlocking { SingleSessionReset.awaitReady(context) }
        bridge = ChatDatabaseBridge.getInstance(context)
        anchor = ChatDatabaseBridge.connectionProjectionAnchor(context)
        db = JarvysChatDatabaseProvider.getInstance(context)
        source = Source()
        core = ConnectionStateCoordinator(source, { 11L }, "test-process", anchor.fence, anchor.providerIdentity)
        core.initialize()
    }

    private class Source : Storage {
        val current = linkedMapOf<String, Mutation>()
        val proofs = linkedMapOf<String, Mutation>()
        val pending = linkedMapOf<String, Event>()
        val candidates = linkedMapOf<String, UncertainCandidate>()
        var high = 0L
        var version = 0L
        var ack: String? = null
        var binding: DestinationBinding? = null
        override fun load() = StoredImage("synthetic-source", high, null, ack, current, proofs, pending, candidates)
        override fun writeCandidate(m: Mutation): Outcome {
            version++; high = maxOf(high, m.highWater); current[m.event.connectorId] = m
            candidates[m.version] = UncertainCandidate(m, false); pending[m.event.eventId] = m.event
            return Outcome.CONFIRMED
        }
        override fun writeProof(m: Mutation): Outcome { version++; proofs[m.version] = m; return Outcome.CONFIRMED }
        override fun bindDestination(value: DestinationBinding?) { binding = value }
        override fun retentionSnapshot() = RetentionSnapshot(load(), this, version)
        override fun cleanup(batch: ProjectionBatch, plan: RetentionPlan): Outcome {
            check(plan.expectedSnapshot.ownerIdentity === this && plan.expectedSnapshot.version == version)
            check(binding === batch.binding)
            version++; batch.events.forEach { pending.remove(it.eventId) }; ack = batch.targetGeneration
            return Outcome.CONFIRMED
        }
    }

    private fun choose(id: String, op: String = id): ConnectionStateCoordinator.Result {
        val v = core.read()
        return core.persistSelection(core.beginIntent(Command(id, "definition", op, Kind.DISCONNECT,
            v.revision(id), v.intentEpoch(id), null)))
    }
    private fun bind(): DestinationEvidence = bridge.connectionProjectionEvidenceSync().also {
        assertNotNull(core.bindDestination(it))
    }
    private fun batch(): ProjectionBatch = core.prepareProjection(core.read().destinationGeneration)
    private fun exec(sql: String, vararg args: Any?) = runBlocking { db.withChatWriteTransaction { db.chatSql.execSQL(sql, args) } }
    private fun count(): Long = runBlocking { db.withChatReadTransaction {
        db.chatSql.query("SELECT COUNT(*) FROM connector_state_events").use { assertTrue(it.moveToFirst()); it.getLong(0) }
    } }
    private fun await(latch: CountDownLatch) = assertTrue("Missing ordering checkpoint", latch.await(10, TimeUnit.SECONDS))

    @Test fun sourceIsUnboundUntilRealEvidenceAndLocalChoiceDoesNotOpenProjection() {
        assertEquals(DestinationState.UNBOUND, core.read().destinationState)
        choose("calendar")
        assertEquals(Outcome.CONFIRMED, core.read().choices["calendar"]?.let { Outcome.CONFIRMED })
        assertEquals(0L, count())
        assertNotNull(core.read().observation("calendar"))
        bind(); assertEquals(1, batch().events.size)
    }

    @Test fun exactProjectionIsIdempotentAndAckFollowsCommittedRows() {
        choose("calendar"); bind()
        val batch = batch()
        val ack = bridge.projectConnectionStateSync(batch)
        assertEquals(1L, count())
        assertEquals(batch.events, bridge.projectConnectionStateSync(batch).events)
        assertEquals(1L, count())
        assertEquals(Outcome.CONFIRMED, core.ackProjection(batch, ack))
        assertTrue(source.pending.isEmpty())
        assertEquals(batch.targetGeneration, source.ack)
    }

    @Test fun conflictRollsBackEarlierInsertAndPreservesWholeSourceOutbox() {
        choose("first"); choose("second"); bind()
        val batch = batch(); val collision = batch.events.last()
        exec("INSERT INTO connector_state_events(id,connector_id,connected,connected_at_ms) VALUES(?,?,0,11)",
            collision.eventId, "different-connector")
        assertThrows(IllegalStateException::class.java) { bridge.projectConnectionStateSync(batch) }
        assertEquals(1L, count())
        assertEquals(2, source.pending.size)
        anchor.fenced { assertFalse(batch.binding.evidence().isCurrentPure()) }
        assertEquals(Outcome.NOT_ATTEMPTED, core.ackProjection(batch, Ack(batch.sourceEpoch, batch.targetGeneration, batch.events)))
    }

    @Test fun sameEpochSequenceUnderDifferentIdIsAConflictNotAnAcknowledgment() {
        choose("calendar"); bind(); val batch = batch(); val event = batch.events.single()
        exec("INSERT INTO connector_state_events(id,connector_id,connected,connected_at_ms,store_epoch,sequence) VALUES(?,?,0,11,?,?)",
            "unrelated-id", event.connectorId, event.storeEpoch, event.sequence)
        assertThrows(IllegalStateException::class.java) { bridge.projectConnectionStateSync(batch) }
        assertEquals(1L, count())
        assertEquals(1, source.pending.size)
        assertNull(source.ack)
    }

    @Test fun everyPayloadFieldParticipatesInImmutableComparison() {
        choose("calendar"); bind()
        val initialBatch = batch(); bridge.projectConnectionStateSync(initialBatch)
        val changes = listOf("connector_id='changed'", "connected=1", "connected_at_ms=12", "store_epoch='changed'",
            "sequence=99", "previous_choice=1", "reason='changed'", "operation_id='changed'",
            "parent_operation_id='changed'", "payload_version=2")
        for (change in changes) {
            exec("DELETE FROM connector_state_events")
            // A fresh actual read is needed after each uncertainty; it does not reuse the old binding.
            bind(); val fresh = batch(); bridge.projectConnectionStateSync(fresh)
            exec("UPDATE connector_state_events SET $change")
            assertThrows("Changed $change must conflict", IllegalStateException::class.java) {
                bridge.projectConnectionStateSync(fresh)
            }
            assertEquals(1L, count())
        }
    }

    @Test fun malformedGenerationFailsClosedWithoutRepairingOrAcknowledging() {
        choose("calendar"); val evidence = bind(); val batch = batch()
        exec("UPDATE storage_meta SET value='invalid-generation' WHERE key=?", ConnectionProjectionRepository.GENERATION_KEY)
        assertThrows(IllegalStateException::class.java) { bridge.connectionProjectionEvidenceSync() }
        anchor.fenced { assertFalse(evidence.isCurrentPure()) }
        assertNull(core.bindDestination(evidence))
        assertThrows(IllegalStateException::class.java) { bridge.projectConnectionStateSync(batch) }
        assertEquals(1, source.pending.size)
        runBlocking { db.withChatReadTransaction {
            db.chatSql.query("SELECT value FROM storage_meta WHERE key=?", arrayOf(ConnectionProjectionRepository.GENERATION_KEY)).use {
                assertTrue(it.moveToFirst()); assertEquals("invalid-generation", it.getString(0))
            }
        } }
    }

    @Test fun projectionWhileAutomaticMemoryOffDoesNotCreateMemoryOrAuthorizationEvidence() {
        com.jarvys.agent.MemoryWritePolicy.forContext(context).setEnabledBlocking(false)
        fun rows(table: String): Long = runBlocking { db.withChatReadTransaction {
            db.chatSql.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); it.getLong(0) }
        } }
        val memories = rows("memory_entries"); val evidence = rows("memory_evidence")
        choose("calendar"); bind(); val batch = batch()
        assertEquals(Outcome.CONFIRMED, core.ackProjection(batch, bridge.projectConnectionStateSync(batch)))
        assertEquals(1L, count())
        assertEquals(memories, rows("memory_entries")); assertEquals(evidence, rows("memory_evidence"))
    }

    @Test fun warningRetentionDoesNotDeleteGeneration() {
        val first = bind().generation()
        runBlocking { ArtifactRetentionRepository(db).setStorageWarning(true); ArtifactRetentionRepository(db).setStorageWarning(false) }
        assertEquals(first, bridge.connectionProjectionEvidenceSync().generation())
    }

    @Test fun reopeningSameFilePreservesDurableGenerationButRejectsOldEvidenceAndAck() {
        choose("calendar"); val old = bind(); val oldBatch = batch(); val oldAck = bridge.projectConnectionStateSync(oldBatch)
        ChatDatabaseBridge.closeInstanceForTests(context.filesDir)
        anchor.fenced { assertFalse(old.isCurrentPure()) }
        assertNull(core.bindDestination(old))
        assertEquals(Outcome.NOT_ATTEMPTED, core.ackProjection(oldBatch, oldAck))
        bridge = ChatDatabaseBridge.getInstance(context)
        val fresh = bridge.connectionProjectionEvidenceSync()
        db = JarvysChatDatabaseProvider.getInstance(context)
        assertEquals(old.generation(), fresh.generation())
        assertNotNull(core.bindDestination(fresh))
        assertThrows(IllegalStateException::class.java) { bridge.projectConnectionStateSync(oldBatch) }
        val newBatch = batch(); val ack = bridge.projectConnectionStateSync(newBatch)
        assertEquals(Outcome.CONFIRMED, core.ackProjection(newBatch, ack))
        assertEquals(1L, count())
    }

    @Test fun secondHandleLifecycleInvalidatesTokensWithoutClosingTheSurvivor() {
        val first = bridge.connectionProjectionEvidenceSync()
        val second = JarvysChatDatabaseProvider.openDatabaseFileForTests(context,
            JarvysChatDatabaseProvider.databaseFileForRawQuery(db))
        try {
            anchor.fenced { assertFalse(first.isCurrentPure()) }
            val during = bridge.connectionProjectionEvidenceSync()
            anchor.fenced { assertTrue(during.isCurrentPure()) }
            JarvysChatDatabaseProvider.closeDatabase(second)
            anchor.fenced { assertFalse(during.isCurrentPure()) }
            val after = bridge.connectionProjectionEvidenceSync()
            assertEquals(first.generation(), after.generation())
            anchor.fenced { assertTrue(after.isCurrentPure()); assertFalse(first.isCurrentPure()) }
            assertNotNull(core.bindDestination(after))
            choose("calendar"); val batch = batch()
            assertEquals(Outcome.CONFIRMED, core.ackProjection(batch, bridge.projectConnectionStateSync(batch)))
        } finally { JarvysChatDatabaseProvider.closeDatabase(second) }
    }

    @Test fun validEvidenceCapturedBeforeCloseCannotBindAfterClose() {
        val captured = bridge.connectionProjectionEvidenceSync()
        ChatDatabaseBridge.closeInstanceForTests(context.filesDir)
        assertNull(core.bindDestination(captured))
        assertEquals(DestinationState.UNBOUND, core.read().destinationState)
    }

    @Test fun foreignIssuerAndFabricatedEvidenceNeverReachProjection() {
        val real = bridge.connectionProjectionEvidenceSync()
        val forged = object : DestinationEvidence {
            override fun generation() = real.generation()
            override fun providerIdentity() = anchor.providerIdentity
            override fun fenceIdentity() = anchor.fence
            override fun isCurrentPure() = true
        }
        // The coordinator's provider contract is injectable; the real Room boundary must additionally
        // recognize its own private evidence type, not just a matching generation string.
        choose("calendar"); assertNotNull(core.bindDestination(forged))
        assertThrows(IllegalStateException::class.java) { bridge.projectConnectionStateSync(batch()) }
        assertEquals(0L, count())
    }

    @Test fun generationReadIsSingleIdentityAcrossConcurrentCallers() {
        val workers = Executors.newFixedThreadPool(4)
        try {
            val reads = (1..8).map { workers.submit<String> { bridge.connectionProjectionEvidenceSync().generation() } }
            assertEquals(1, reads.map { it.get(10, TimeUnit.SECONDS) }.toSet().size)
        } finally { workers.shutdownNow() }
    }

    @Test fun closeWaitsForProjectionFenceThenInvalidatesBeforeOldAck() {
        choose("calendar"); bind(); val batch = batch()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val held = workers.submit { anchor.fenced { entered.countDown(); await(release) } }
            await(entered)
            val closeStarted = CountDownLatch(1)
            val closeThread = java.util.concurrent.atomic.AtomicReference<Thread>()
            val closing = workers.submit {
                closeThread.set(Thread.currentThread()); closeStarted.countDown()
                JarvysChatDatabaseProvider.closeDatabase(db)
            }
            await(closeStarted)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!anchor.fence.hasQueuedThread(closeThread.get()) && !closing.isDone && System.nanoTime() < deadline) Thread.yield()
            assertTrue("Close must actually wait on the shared destination fence", anchor.fence.hasQueuedThread(closeThread.get()))
            assertFalse(closing.isDone)
            release.countDown(); held.get(10, TimeUnit.SECONDS); closing.get(10, TimeUnit.SECONDS)
            assertNull(core.bindDestination(batch.binding.evidence()))
            assertEquals(Outcome.NOT_ATTEMPTED, core.ackProjection(batch, Ack(batch.sourceEpoch, batch.targetGeneration, batch.events)))
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun recreatedDestinationGetsNewGenerationAndReprojectsConfirmedSnapshots() {
        choose("calendar"); val old = bind(); val original = batch()
        assertEquals(Outcome.CONFIRMED, core.ackProjection(original, bridge.projectConnectionStateSync(original)))
        assertTrue(source.pending.isEmpty())
        val file = JarvysChatDatabaseProvider.databaseFileForRawQuery(db)
        ChatDatabaseBridge.closeInstanceForTests(context.filesDir)
        assertTrue(android.database.sqlite.SQLiteDatabase.deleteDatabase(file))
        bridge = ChatDatabaseBridge.getInstance(context)
        val fresh = bridge.connectionProjectionEvidenceSync()
        db = JarvysChatDatabaseProvider.getInstance(context)
        assertNotEquals(old.generation(), fresh.generation())
        assertNotNull(core.bindDestination(fresh))
        val replay = batch()
        assertEquals(original.events, replay.events)
        assertEquals(0L, count())
        assertEquals(Outcome.CONFIRMED, core.ackProjection(replay, bridge.projectConnectionStateSync(replay)))
        assertEquals(1L, count())
    }

    @Test fun closingWithBorrowedLeaseCannotDeadlockThroughLifecycleCallback() {
        bind()
        val leased = CountDownLatch(1); val closeRequested = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val borrowed = workers.submit {
                runBlocking { db.withChatWriteTransaction {
                    leased.countDown(); await(closeRequested)
                    assertThrows(IllegalStateException::class.java) { JarvysChatDatabaseProvider.getInstance(context) }
                    assertThrows(IllegalStateException::class.java) { anchor.fenced { throw AssertionError("Lease guard allowed lifecycle entry") } }
                } }
            }
            await(leased)
            val closing = workers.submit {
                closeRequested.countDown()
                JarvysChatDatabaseProvider.closeDatabase(db)
            }
            borrowed.get(10, TimeUnit.SECONDS)
            closing.get(10, TimeUnit.SECONDS)
        } finally { closeRequested.countDown(); workers.shutdownNow() }
    }

    @Test fun activeRoomLeaseCannotReenterDestinationLifecycleOrBridge() = runBlocking {
        db.withChatWriteTransaction {
            assertThrows(IllegalStateException::class.java) { anchor.fenced { throw AssertionError("Lease guard allowed lifecycle entry") } }
            assertThrows(IllegalStateException::class.java) { JarvysChatDatabaseProvider.closeDatabase(db) }
            assertThrows(IllegalStateException::class.java) { JarvysChatDatabaseProvider.getInstance(context) }
            assertThrows(IllegalStateException::class.java) { bridge.connectionProjectionEvidenceSync() }
        }
        assertNotNull(bridge.connectionProjectionEvidenceSync())
    }
}
