package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.jarvys.agent.connectors.ConnectionStateCoordinator as Core
import com.jarvys.agent.connectors.ConnectorRegistry.ConnectorTransitionResult as Result

/** Typed in-memory protocol plus real Registry lanes; no provider effects or Android IO. */
class ConnectorConnectionLifecycleTest {
    private class Runtime : ConnectorRuntime {
        val connects = AtomicInteger(); val disconnects = AtomicInteger()
        val events = CopyOnWriteArrayList<String>()
        @Volatile var enter: CountDownLatch? = null
        @Volatile var release: CountDownLatch? = null
        @Volatile var failCleanup = false
        override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) {
            val ordinal = connects.incrementAndGet(); events += "connect-$ordinal"
            if (ordinal == 1) { enter?.countDown(); release?.let { check(it.await(5, TimeUnit.SECONDS)) } }
        }
        override fun disconnect() {
            disconnects.incrementAndGet(); events += "cleanup"
            if (failCleanup) error("fixture cleanup failure")
        }
        override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken) =
            ConnectorWritePreparation(ApprovalSummary("fixture", emptyList()), arguments)
        override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken) = JSONObject()
    }
    private fun registry(runtime: Runtime, access: ConnectionAccess = ConnectionAccess.forTests(emptyMap()),
                         granted: () -> Boolean = { true }, settings: Boolean = false): ConnectorRegistry =
        ConnectorRegistry.createForTests(access, { granted() }).also {
            it.register(ConnectorDefinition("fixture", "Fixture", "1", "Local fixture", runtime = runtime,
                readPermissions = listOf("fixture.read"), connectionFlow = if (settings)
                    ConnectorConnectionFlow.NOTIFICATION_LISTENER_SETTINGS else ConnectorConnectionFlow.PERMISSIONS))
        }
    private fun await(latch: CountDownLatch) = assertTrue("Missing causal barrier", latch.await(5, TimeUnit.SECONDS))

    @Test fun offPersistsDuringBlockedConnectAndOldActivationCleansBeforeNextStart() {
        val runtime = Runtime().apply { enter = CountDownLatch(1); release = CountDownLatch(1) }
        val access = ConnectionAccess.forTests(emptyMap()); val registry = registry(runtime, access)
        try {
            val first = registry.captureConnectionRequest("fixture", Core.Kind.CONNECT)
            val firstResult = registry.submitConnectionRequest(first); await(runtime.enter!!)
            val off = registry.captureConnectionRequest("fixture", Core.Kind.DISCONNECT)
            val offResult = registry.submitConnectionRequest(off)
            assertFalse(access.captureChoice("fixture").selected)
            assertFalse(access.captureChoice("fixture").coreView!!.factualCurrent["fixture"]!!.selected)
            assertFalse(offResult.isDone) // Only cleanup waits for the runtime lane.
            val on = registry.captureConnectionRequest("fixture", Core.Kind.CONNECT)
            val onResult = registry.submitConnectionRequest(on)
            runtime.release!!.countDown()
            assertEquals(Result.Status.SUPERSEDED, firstResult.get(5, TimeUnit.SECONDS).status)
            assertTrue(offResult.get(5, TimeUnit.SECONDS).persistenceConfirmed)
            assertEquals(Result.Status.COMPLETED, onResult.get(5, TimeUnit.SECONDS).status)
            assertEquals(2, runtime.connects.get())
            assertTrue(runtime.events.indexOf("cleanup") < runtime.events.indexOf("connect-2"))
            assertFalse(registry.isConnectionRequestCurrent(off)); assertTrue(registry.isConnectionRequestCurrent(on))
            assertEquals(ConnectorState.CONNECTED, registry.state("fixture"))
        } finally { runtime.release!!.countDown() }
    }

    @Test fun oldOffSubmittedAfterNewOnCannotDisconnectTheNewActivation() {
        val runtime = Runtime(); val registry = registry(runtime)
        assertEquals(Result.Status.COMPLETED, registry.connect("fixture").status)
        val off = registry.captureConnectionRequest("fixture", Core.Kind.DISCONNECT)
        val on = registry.captureConnectionRequest("fixture", Core.Kind.CONNECT)
        assertEquals(Result.Status.COMPLETED, registry.submitConnectionRequest(on).get(5, TimeUnit.SECONDS).status)
        val cleanups = runtime.disconnects.get(); assertTrue(cleanups > 0)
        assertEquals(Result.Status.SUPERSEDED, registry.submitConnectionRequest(off).get(5, TimeUnit.SECONDS).status)
        assertEquals(cleanups, runtime.disconnects.get()); assertEquals(2, runtime.connects.get())
        assertEquals(ConnectorState.CONNECTED, registry.state("fixture"))
    }

    @Test fun failedOldCleanupBlocksNewRuntimeStartAndCanBeRetriedByFreshIntent() {
        val runtime = Runtime(); val registry = registry(runtime); registry.connect("fixture")
        runtime.failCleanup = true; registry.captureConnectionRequest("fixture", Core.Kind.DISCONNECT)
        val on = registry.captureConnectionRequest("fixture", Core.Kind.CONNECT)
        assertEquals(Result.Status.CLEANUP_FAILED, registry.submitConnectionRequest(on).get(5, TimeUnit.SECONDS).status)
        assertEquals(1, runtime.connects.get()); assertEquals(ConnectorState.DISCONNECTED, registry.state("fixture"))
        runtime.failCleanup = false
        assertEquals(Result.Status.COMPLETED, registry.connect("fixture").status); assertEquals(2, runtime.connects.get())
    }

    @Test fun observerCancellationDoesNotCancelProcessOwnedLifecycleAndSubmitIsMemoized() {
        val runtime = Runtime().apply { enter = CountDownLatch(1); release = CountDownLatch(1) }; val registry = registry(runtime)
        try {
            val request = registry.captureConnectionRequest("fixture", Core.Kind.CONNECT)
            assertSame(request, registry.resolvePendingRequest(request.requestId))
            val cancelled = registry.submitConnectionRequest(request); val observer = registry.submitConnectionRequest(request)
            await(runtime.enter!!); assertTrue(cancelled.cancel(true)); runtime.release!!.countDown()
            assertEquals(Result.Status.COMPLETED, observer.get(5, TimeUnit.SECONDS).status)
            assertEquals(1, runtime.connects.get()); assertNull(registry.resolvePendingRequest(request.requestId))
            assertTrue(registry.isConnectionRequestCurrent(request))
        } finally { runtime.release!!.countDown() }
    }

    @Test fun permissionAbortPreservesPreviousChoiceAndNeverRestartsFromLateLookup() {
        val runtime = Runtime(); val access = ConnectionAccess.forTests(mapOf("fixture" to true)); val registry = registry(runtime, access)
        val request = registry.captureConnectionRequest("fixture", Core.Kind.CONNECT)
        assertEquals(ConnectorState.DISCONNECTED, registry.state("fixture"))
        assertEquals(Result.Status.ABORTED, registry.abortConnectionRequest(request).get(5, TimeUnit.SECONDS).status)
        assertEquals(ConnectorState.CONNECTED, registry.state("fixture")); assertEquals(0, runtime.connects.get())
        assertNull(registry.resolvePendingRequest(request.requestId))
        assertEquals(Result.Status.ABORTED, registry.submitConnectionRequest(request).get(5, TimeUnit.SECONDS).status)
        registry.captureConnectionRequest("fixture", Core.Kind.DISCONNECT)
        assertFalse(registry.isConnectionRequestCurrent(request)); assertEquals(0, runtime.connects.get())
    }

    @Test fun settingsSelectionBeforeGrantRemainsLocalAndLaterGrantDoesNotReconnect() {
        var granted = false; val runtime = Runtime(); val registry = registry(runtime, granted = { granted }, settings = true)
        assertEquals(Result.Status.COMPLETED, registry.connectFromSystemSettings("fixture").status)
        assertEquals(ConnectorState.PERMISSION_REVOKED, registry.state("fixture")); assertEquals(1, runtime.connects.get())
        granted = true; registry.refreshStates()
        assertEquals(ConnectorState.CONNECTED, registry.state("fixture")); assertEquals(1, runtime.connects.get())
    }

    @Test fun silentCaptureDoesNotInvokeListenersUntilPublicationAndStillDeniesImmediately() {
        val access = ConnectionAccess.forTests(mapOf("fixture" to true)); val registry = registry(Runtime(), access)
        val notifications = AtomicInteger(); val listener = access.addListener(Runnable { notifications.incrementAndGet() })
        try {
            val before = notifications.get()
            val request = registry.captureConnectionRequestSilently("fixture", Core.Kind.DISCONNECT)
            assertEquals(before, notifications.get()); assertFalse(access.captureChoice("fixture").selected)
            registry.publishConnectionRequest(request); assertTrue(notifications.get() > before)
            val published = notifications.get(); registry.publishConnectionRequest(request); assertEquals(published, notifications.get())
        } finally { listener.close() }
    }
    @Test fun latePermissionRequestCannotOperateOnAReplacementDefinitionWithTheSameId() {
        val oldRuntime = Runtime(); val registry = registry(oldRuntime); registry.connect("fixture")
        val oldRequest = registry.captureConnectionRequest("fixture", Core.Kind.CONNECT)
        registry.unregister("fixture")
        val replacement = Runtime()
        registry.register(ConnectorDefinition("fixture", "Replacement", "1", "Same id and version, new registration", runtime = replacement))
        assertEquals(ConnectorState.DISCONNECTED, registry.state("fixture"))
        assertEquals(Result.Status.COMPLETED, registry.connect("fixture").status)
        val oldCleanups = oldRuntime.disconnects.get()
        assertEquals(Result.Status.SUPERSEDED, registry.submitConnectionRequest(oldRequest).get(5, TimeUnit.SECONDS).status)
        assertEquals(1, oldRuntime.connects.get()); assertEquals(oldCleanups, oldRuntime.disconnects.get())
        assertEquals(1, replacement.connects.get()); assertEquals(0, replacement.disconnects.get())
        assertEquals(ConnectorState.CONNECTED, registry.state("fixture")); assertFalse(registry.isConnectionRequestCurrent(oldRequest))
    }

    @Test fun provisionalOffFeedbackCanBeCurrentWithoutInventingCoreSnapshotOrDurability() {
        val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val access = ConnectionAccess.forTestsControlled(mapOf("fixture" to true), java.util.concurrent.Executor { queued.add(it) })
        val runtime = Runtime(); val registry = registry(runtime, access)
        try {
            val off = registry.captureConnectionRequest("fixture", Core.Kind.DISCONNECT)
            val historicalFuture = registry.submitConnectionRequest(off)
            val result = historicalFuture.get(5, TimeUnit.SECONDS)
            assertEquals(Result.Status.PENDING, result.status); assertFalse(result.persistenceConfirmed)
            assertTrue(registry.isConnectionRequestCurrent(off)); assertNull(access.captureChoice("fixture").coreView)
            assertEquals(1, runtime.disconnects.get()); assertEquals(0, runtime.connects.get())
            registry.captureConnectionRequest("fixture", Core.Kind.DISCONNECT)
            assertFalse(registry.isConnectionRequestCurrent(off))
            while (true) (queued.poll() ?: break).run()
            assertEquals(Result.Status.PENDING, historicalFuture.get(5, TimeUnit.SECONDS).status)
        } finally { while (true) (queued.poll() ?: break).run() }
    }

    @Test fun workerDefaultWaitsForBootstrapThenConnectsOnlyKnownAbsentChoice() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val bootstrapWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor()
        val access = ConnectionAccess.forTestsControlled(emptyMap(), bootstrapWorker,
            beforeBootstrap = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
        val runtime = Runtime(); val registry = registry(runtime, access)
        try {
            val result = caller.submit<Result> { registry.connectIfUnselected("fixture") }
            await(entered)
            assertFalse(access.isReady()); assertEquals(0, runtime.connects.get())
            assertFalse(result.isDone)
            release.countDown()
            assertEquals(Result.Status.COMPLETED, result.get(5, TimeUnit.SECONDS).status)
            assertTrue(access.captureChoice("fixture").selected)
            assertEquals(1, runtime.connects.get())
        } finally { release.countDown(); caller.shutdownNow(); bootstrapWorker.shutdownNow() }
    }

    @Test fun offCapturedDuringDefaultBootstrapWaitPreventsDefaultResurrection() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val bootstrapWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor()
        val access = ConnectionAccess.forTestsControlled(emptyMap(), bootstrapWorker,
            beforeBootstrap = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
        val runtime = Runtime(); val registry = registry(runtime, access)
        try {
            val result = caller.submit<Result> { registry.connectIfUnselected("fixture") }
            await(entered)
            val off = registry.captureConnectionRequest("fixture", Core.Kind.DISCONNECT)
            val pending = registry.submitConnectionRequest(off).get(5, TimeUnit.SECONDS)
            assertEquals(Result.Status.PENDING, pending.status)
            assertFalse(access.captureChoice("fixture").selected)
            assertEquals(0, runtime.connects.get())
            release.countDown()
            assertNotEquals(Result.Status.COMPLETED, result.get(5, TimeUnit.SECONDS).status)
            val choice = access.captureChoice("fixture")
            assertTrue(choice.ready); assertTrue(choice.hasChoice); assertFalse(choice.selected)
            assertFalse(choice.coreView!!.factualCurrent["fixture"]!!.selected)
            assertEquals(0, runtime.connects.get())
        } finally { release.countDown(); caller.shutdownNow(); bootstrapWorker.shutdownNow() }
    }

}
