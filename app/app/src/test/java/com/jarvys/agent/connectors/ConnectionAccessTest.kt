package com.jarvys.agent.connectors

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.ConnectionStateCoordinator as Core
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConnectionAccessTest {
    private class Queued : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    @Test fun earlyOffSurvivesBootstrapWithoutAnySelectedPublicationAndOldOnIsNotReady() {
        val queue = Queued()
        val access = ConnectionAccess.forTestsControlled(mapOf("fixture" to true), queue)
        val selected = mutableListOf<Boolean>()
        access.addListener { selected += access.captureChoice("fixture").selected }
        val on = access.beginRequest("fixture", "definition", Core.Kind.CONNECT)
        assertEquals(ConnectionAccess.RequestStatus.NOT_READY, on.status)
        val off = access.beginRequest("fixture", "definition", Core.Kind.DISCONNECT)
        assertEquals(ConnectionAccess.RequestStatus.ACCEPTED, off.status)
        val observer = access.persist(off)
        assertFalse(access.captureChoice("fixture").selected)
        assertNull(access.captureChoice("fixture").observation)
        queue.drain()
        val result = observer.get(5, TimeUnit.SECONDS)
        assertTrue(result.cleanupRequired)
        assertTrue(access.isReady())
        assertFalse(access.captureChoice("fixture").selected)
        assertFalse(selected.any { it })
        assertEquals(false, access.captureChoice("fixture").observation?.selected)
    }
    @Test fun uncertainOffRetainsDenyAndRepairRetriesTheSameIdentityOnly() {
        val fail = AtomicBoolean(false)
        val versions = mutableListOf<String>()
        val access = ConnectionAccess.forTestsControlled(mapOf("fixture" to true), Executor { it.run() },
            outcome = { mutation, proof ->
                if (mutation.command.kind == Core.Kind.DISCONNECT && !proof) versions += mutation.version
                if (fail.get()) Core.Outcome.UNCERTAIN else Core.Outcome.CONFIRMED
            })
        access.initializeAsync().get()
        fail.set(true)
        val off = access.beginRequest("fixture", "definition", Core.Kind.DISCONNECT)
        val result = access.persist(off).get()
        assertEquals(Core.Outcome.UNCERTAIN, result.persistence.choice)
        assertFalse(access.captureChoice("fixture").selected)
        assertTrue(access.captureChoice("fixture").denied)
        assertNull(access.captureChoice("fixture").observation)
        fail.set(false)
        assertEquals(ConnectionAccess.RepairOutcome.SUCCESS, access.repairOnce().get(5, TimeUnit.SECONDS))
        assertEquals(2, versions.size)
        assertEquals(versions.first(), versions.last())
        assertFalse(access.captureChoice("fixture").denied)
        assertEquals(off.opId, access.captureChoice("fixture").observation?.operationId)
        assertEquals(false, access.captureChoice("fixture").observation?.selected)
    }
    @Test fun repairNeverRestartsAnUncertainConnectOrCreatesReady() {
        val fail = AtomicBoolean(false); val connects = AtomicInteger()
        val access = ConnectionAccess.forTestsControlled(mapOf("fixture" to false), Executor { it.run() },
            outcome = { mutation, proof ->
                if (mutation.command.kind == Core.Kind.CONNECT && !proof) connects.incrementAndGet()
                if (fail.get()) Core.Outcome.UNCERTAIN else Core.Outcome.CONFIRMED
            })
        access.initializeAsync().get(); fail.set(true)
        val on = access.beginRequest("fixture", "definition", Core.Kind.CONNECT)
        assertNull(access.persist(on).get().permit)
        fail.set(false)
        access.repairOnce().get(5, TimeUnit.SECONDS)
        assertEquals(1, connects.get())
        assertFalse(access.captureChoice("fixture").selected)
        assertNull(access.captureChoice("fixture").observation)
    }
    @Test fun wrappersDelegateOneHandleAndMissingProviderNeverReadsRawPreferences() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val access = ConnectionAccess.forTests(mapOf("fixture" to true))
        fun wrapper() = object : ContextWrapper(app), ConnectionAccessProvider {
            override fun getApplicationContext(): Context = this
            override fun connectionAccess() = access
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = error("Raw preference fallback")
            override fun getFilesDir(): java.io.File = error("Main canonicalization")
        }
        val one = wrapper(); val alias = wrapper()
        assertSame(ConnectionAccess.find(one), ConnectionAccess.find(alias))
        assertTrue(ConnectionAccess.isLocallySelected(one, "fixture"))
        val off = access.beginRequest("fixture", "definition", Core.Kind.DISCONNECT)
        assertFalse(ConnectionAccess.isLocallySelected(alias, "fixture"))
        access.persist(off).get()
        assertFalse(ConnectionAccess.isLocallySelected(app, "fixture"))
    }
    @Test fun callbacksRunOutsideFacadeLockAndExactReadViewObservationIsReturned() {
        val access = ConnectionAccess.forTests(mapOf("fixture" to false))
        val worker = Executors.newSingleThreadExecutor(); val completedCallbacks = AtomicInteger()
        try {
            val listener = access.addListener {
                worker.submit { access.captureChoice("fixture") }.get(2, TimeUnit.SECONDS)
                completedCallbacks.incrementAndGet()
            }
            val on = access.beginRequest("fixture", "definition", Core.Kind.CONNECT)
            val permit = access.persist(on).get().permit
            assertNotNull(permit)
            access.activationReturned(on, permit).get()
            val snapshot = access.captureChoice("fixture")
            assertTrue(snapshot.selected)
            assertEquals(snapshot.coreView!!.observation("fixture"), snapshot.observation)
            assertEquals(on.opId, snapshot.observation!!.operationId)
            assertTrue(completedCallbacks.get() >= 3)
            listener.close()
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @Test fun cancellingProjectionObserverDoesNotLoseConcurrentDirtyCommit() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val projections = AtomicInteger()
        val access = ConnectionAccess.forTestsControlled(emptyMap(), Executor { it.run() }, projection = {
            if (projections.incrementAndGet() == 1) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
            ConnectionAccess.RepairOutcome.SUCCESS
        })
        access.initializeAsync().get()
        val cancelled = access.repairOnce()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(cancelled.cancel(true))
        val off = access.beginRequest("fixture", "definition", Core.Kind.DISCONNECT)
        access.persist(off).get()
        val observer = access.repairOnce()
        release.countDown()
        assertEquals(ConnectionAccess.RepairOutcome.SUCCESS, observer.get(5, TimeUnit.SECONDS))
        assertEquals(2, projections.get())
    }
    @Test fun silentCaptureDoesNotPublishCallbacksOrEnqueueUntilExplicitPublish() {
        val queue = Queued(); val access = ConnectionAccess.forTestsControlled(emptyMap(), queue)
        val callbacks = AtomicInteger(); access.addListener { callbacks.incrementAndGet() }
        val off = access.beginRequestSilently("fixture", "definition", Core.Kind.DISCONNECT)
        assertTrue(access.captureChoice("fixture").denied)
        assertEquals(0, callbacks.get()); assertTrue(queue.tasks.isEmpty())
        access.publishRequest(off)
        assertEquals(1, callbacks.get()); assertEquals(1, queue.tasks.size)
        access.publishRequest(off)
        assertEquals(1, callbacks.get()); assertEquals(1, queue.tasks.size)
        queue.drain(); access.persist(off).get()
        assertFalse(access.captureChoice("fixture").selected)
    }
    @Test fun provisionalOffAllowsCleanupBeforeMetadataRetryAndLaterCommitsOriginalIdentity() {
        val queue = Queued(); val metadataFails = AtomicBoolean(true)
        val access = ConnectionAccess.forTestsControlled(mapOf("fixture" to true), queue, beforeBootstrap = {
            if (metadataFails.get()) throw ConnectionPreferencesOwner.MetadataUnconfirmedException()
        })
        val off = access.beginRequest("fixture", "definition", Core.Kind.DISCONNECT)
        val provisional = access.persist(off).get(1, TimeUnit.SECONDS)
        assertTrue(provisional.cleanupRequired)
        assertTrue(provisional.provisional)
        assertNull(provisional.snapshot)
        assertNull(provisional.projectionPending)
        assertEquals(Core.Outcome.NOT_ATTEMPTED, provisional.persistence.choice)
        assertEquals(Core.Outcome.NOT_ATTEMPTED, provisional.persistence.proof)
        assertNull(provisional.permit)
        queue.drain()
        assertFalse(access.isReady()); assertTrue(access.captureChoice("fixture").denied)
        metadataFails.set(false)
        val repair = access.repairOnce(); queue.drain()
        assertEquals(ConnectionAccess.RepairOutcome.SUCCESS, repair.get(5, TimeUnit.SECONDS))
        assertTrue(access.isReady()); assertFalse(access.captureChoice("fixture").denied)
        assertEquals(off.opId, access.captureChoice("fixture").observation!!.operationId)
        assertFalse(access.captureChoice("fixture").selected)
    }
    @Test fun permissionAbortRestoresPendingOffAsNewRestrictiveChildNotOldToken() {
        val access = ConnectionAccess.forTests(mapOf("fixture" to true))
        val off = access.beginRequest("fixture", "definition", Core.Kind.DISCONNECT)
        val on = access.beginRequest("fixture", "definition", Core.Kind.CONNECT)
        assertEquals(Core.AbortDisposition.ABORTED, access.abortBeforeSelection(on).get().disposition)
        val snapshot = access.captureChoice("fixture")
        assertFalse(snapshot.selected); assertFalse(snapshot.denied)
        val restored = snapshot.coreView!!.choices["fixture"]!!.mutation
        assertEquals(Core.Kind.DISCONNECT_AFTER_PERMISSION_ABORT, restored.command.kind)
        assertEquals(off.opId, restored.event.parentOperationId)
        assertNotEquals(off.opId, restored.command.operationId)
        assertNotEquals(on.opId, restored.command.operationId)
        assertFalse(access.isCurrent(off)); assertFalse(access.isCurrent(on))
        try { access.persist(off).get(); fail("Old OFF was rebased") }
        catch (failure: java.util.concurrent.ExecutionException) {
            assertEquals(ConnectionAccess.RequestStatus.SUPERSEDED, (failure.cause as ConnectionAccess.RequestUnavailableException).status)
        }
    }
    @Test fun uncertainRestorationChildKeepsDenyAndRepairUsesSameChild() {
        val failChild = AtomicBoolean(true); val children = mutableListOf<String>()
        val access = ConnectionAccess.forTestsControlled(mapOf("fixture" to true), Executor { it.run() }, outcome = { mutation, proof ->
            if (mutation.command.kind == Core.Kind.DISCONNECT_AFTER_PERMISSION_ABORT && !proof) children += mutation.command.operationId
            if (mutation.command.kind == Core.Kind.DISCONNECT_AFTER_PERMISSION_ABORT && failChild.get()) Core.Outcome.UNCERTAIN else Core.Outcome.CONFIRMED
        })
        access.initializeAsync().get()
        val off = access.beginRequest("fixture", "definition", Core.Kind.DISCONNECT)
        val on = access.beginRequest("fixture", "definition", Core.Kind.CONNECT)
        assertEquals(Core.AbortDisposition.ABORTED, access.abortBeforeSelection(on).get().disposition)
        assertTrue(access.captureChoice("fixture").denied); assertNull(access.captureChoice("fixture").observation)
        failChild.set(false)
        assertEquals(ConnectionAccess.RepairOutcome.SUCCESS, access.repairOnce().get(5, TimeUnit.SECONDS))
        assertEquals(2, children.size); assertEquals(children.first(), children.last())
        assertFalse(access.captureChoice("fixture").denied)
        assertEquals(off.opId, access.captureChoice("fixture").coreView!!.choices["fixture"]!!.mutation.event.parentOperationId)
    }
    @Test fun laterOnMustReallyActivateBeforeItCanClearEarlierOffOverlay() {
        val access = ConnectionAccess.forTests(mapOf("fixture" to true))
        val off = access.beginRequest("fixture", "definition", Core.Kind.DISCONNECT)
        val on = access.beginRequest("fixture", "definition", Core.Kind.CONNECT)
        val selected = access.persist(on).get()
        assertNotNull(selected.permit)
        assertTrue(access.captureChoice("fixture").denied)
        assertFalse(access.captureChoice("fixture").selected)
        access.activationReturned(on, selected.permit).get()
        assertTrue(access.captureChoice("fixture").selected)
        assertEquals(on.opId, access.captureChoice("fixture").observation!!.operationId)
        assertFalse(access.isCurrent(off))
    }
    @Test fun publicCaptureCannotMintInternalRestorationKind() {
        val access = ConnectionAccess.forTests(emptyMap())
        val before = access.captureChoice("fixture")
        val request = access.beginRequest("fixture", "definition", Core.Kind.DISCONNECT_AFTER_PERMISSION_ABORT)
        assertEquals(ConnectionAccess.RequestStatus.CONFLICT, request.status)
        assertEquals(before.stamp, access.captureChoice("fixture").stamp)
        assertEquals(before.coreView, access.captureChoice("fixture").coreView)
    }

}
