package com.jarvys.agent.connectors

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.ConnectionStateCoordinator.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConnectionPreferencesOwnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val projectionFence = java.util.concurrent.locks.ReentrantLock()
    private val providerIdentity = Any()
    private class RootContext(base: Context, private val root: File) : ContextWrapper(base) {
        val preferenceReads = AtomicInteger()
        val filesReads = AtomicInteger()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File { filesReads.incrementAndGet(); return root }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Preference access on UI thread" }
            preferenceReads.incrementAndGet()
            return super.getSharedPreferences("${root.canonicalPath.hashCode()}-$name", mode)
        }
    }
    private fun <T> worker(block: () -> T): T {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            return executor.submit<T> { block() }.get(10, TimeUnit.SECONDS)
        } catch (failure: java.util.concurrent.ExecutionException) {
            throw (failure.cause as? RuntimeException ?: failure)
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)) }
    }
    @Test fun mainThreadAcquisitionRejectsBeforeAnyFilesOrPreferenceAccess() {
        val context = RootContext(ApplicationProvider.getApplicationContext(), temporary.newFolder("guard"))
        try { ConnectionPreferencesOwner.forContext(context, projectionFence, providerIdentity); fail("Main acquisition accepted") }
        catch (_: IllegalStateException) { }
        assertEquals(0, context.filesReads.get())
        assertEquals(0, context.preferenceReads.get())
    }
    @Test fun canonicalRootSharesOwnerAndDestinationCannotChangeSilently() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = temporary.newFolder("root")
        val first = RootContext(app, root)
        val alias = RootContext(app, File(root, "."))
        val owner = worker { ConnectionPreferencesOwner.forContext(first, projectionFence, providerIdentity) }
        assertSame(owner, worker { ConnectionPreferencesOwner.forContext(alias, projectionFence, providerIdentity) })
        assertEquals(0, first.preferenceReads.get())
        assertEquals(0, alias.preferenceReads.get())
        try { worker { ConnectionPreferencesOwner.forContext(alias, projectionFence, Any()) }; fail("Silent replacement") }
        catch (_: IllegalStateException) { }
        val foreignContext = RootContext(app, temporary.newFolder("foreign"))
        val foreign = worker { ConnectionPreferencesOwner.forContext(foreignContext, projectionFence, providerIdentity) }
        assertNotSame(owner, foreign)
        assertEquals(BootstrapStatus.READY, owner.initializeAsync().get(10, TimeUnit.SECONDS).status)
        assertEquals(1, first.preferenceReads.get())
        assertEquals(0, alias.preferenceReads.get())
    }
    @Test fun cancellationOfObserverDoesNotCancelSharedBootstrapOrInitializationHook() {
        val context = RootContext(ApplicationProvider.getApplicationContext(), temporary.newFolder("owned"))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val waits = AtomicInteger()
        val owner = ConnectionPreferencesOwner(context, projectionFence, providerIdentity, Runnable {
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            waits.incrementAndGet(); entered.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
        })
        val cancelled = owner.initializeAsync()
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        assertEquals(Bootstrap.INITIALIZING, owner.snapshot().bootstrap)
        assertEquals(0, context.preferenceReads.get())
        assertTrue(cancelled.cancel(true))
        val observer = owner.initializeAsync()
        release.countDown()
        val complete = observer.get(10, TimeUnit.SECONDS)
        assertEquals(BootstrapStatus.READY, complete.status)
        assertEquals(Bootstrap.READY, owner.snapshot().bootstrap)
        assertEquals(1, waits.get())
        assertEquals(1, context.preferenceReads.get())
        assertSame(complete, owner.initializeAsync().get(10, TimeUnit.SECONDS))
    }
    @Test fun metadataUncertaintyIsTypedAndRetryableWithoutChangingEpoch() {
        val values = linkedMapOf<String, Any>()
        var calls = 0
        val backend = object : ConnectionPreferenceBackend {
            override fun snapshot(): Map<String, *> = HashMap(values)
            override fun commit(writes: Map<String, Any>, removals: Set<String>): Boolean {
                calls++; removals.forEach { values.remove(it) }; values.putAll(writes)
                return calls != 1 // First editor changed memory but persistence is unconfirmed.
            }
        }
        val owner = ConnectionPreferencesOwner(projectionFence, providerIdentity, Runnable { }, backend)
        try { owner.initializeAsync().get(10, TimeUnit.SECONDS); fail("Expected typed uncertainty") }
        catch (failure: java.util.concurrent.ExecutionException) {
            assertTrue(failure.cause is ConnectionPreferencesOwner.MetadataUnconfirmedException)
        }
        val epoch = values[SharedPreferencesConnectionStorage.EPOCH]
        assertEquals(Bootstrap.INITIALIZING, owner.snapshot().bootstrap)
        assertEquals(BootstrapStatus.READY, owner.initializeAsync().get(10, TimeUnit.SECONDS).status)
        assertEquals(epoch, owner.snapshot().storeEpoch)
        assertEquals(3, calls)
    }
    @Test fun malformedProtocolFailsWithDifferentTypedResultAndNoWrite() {
        val backend = object : ConnectionPreferenceBackend {
            override fun snapshot(): Map<String, *> = mapOf(SharedPreferencesConnectionStorage.FORMAT to 999L)
            override fun commit(writes: Map<String, Any>, removals: Set<String>): Boolean {
                fail("Corruption must not rewrite source"); return false
            }
        }
        val owner = ConnectionPreferencesOwner(projectionFence, providerIdentity, Runnable { }, backend)
        try { owner.initializeAsync().get(10, TimeUnit.SECONDS); fail("Expected corruption failure") }
        catch (failure: java.util.concurrent.ExecutionException) {
            assertTrue(failure.cause is ConnectionPreferencesOwner.InitializationFailedException)
            assertFalse(failure.cause is ConnectionPreferencesOwner.MetadataUnconfirmedException)
        }
        assertEquals(Bootstrap.FAILED, owner.snapshot().bootstrap)
    }

    @Test fun headerProofFalseRetryDoesNotTreatMutableMemoryProofAsDurable() {
        val values = linkedMapOf<String, Any>()
        val patches = mutableListOf<Map<String, Any>>()
        val backend = object : ConnectionPreferenceBackend {
            override fun snapshot(): Map<String, *> = HashMap(values)
            override fun commit(writes: Map<String, Any>, removals: Set<String>): Boolean {
                patches.add(HashMap(writes)); removals.forEach { values.remove(it) }; values.putAll(writes)
                return patches.size != 2 // First header TRUE, proof applied to memory then FALSE.
            }
        }
        val owner = ConnectionPreferencesOwner(projectionFence, providerIdentity, Runnable { }, backend)
        try { owner.initializeAsync().get(10, TimeUnit.SECONDS); fail("Expected proof uncertainty") }
        catch (failure: java.util.concurrent.ExecutionException) {
            assertTrue(failure.cause is ConnectionPreferencesOwner.MetadataUnconfirmedException)
        }
        val epoch = values[SharedPreferencesConnectionStorage.EPOCH]
        assertEquals(epoch, values[SharedPreferencesConnectionStorage.HEADER_PROOF])
        assertEquals(Bootstrap.INITIALIZING, owner.snapshot().bootstrap)
        assertEquals(BootstrapStatus.READY, owner.initializeAsync().get(10, TimeUnit.SECONDS).status)
        assertEquals(3, patches.size)
        assertEquals(setOf(SharedPreferencesConnectionStorage.HEADER_PROOF), patches.last().keys)
        assertEquals(epoch, owner.snapshot().storeEpoch)
    }

    @Test fun resetAfterUncertainHeaderProofCannotReuseOldHeaderCapability() {
        val values = linkedMapOf<String, Any>()
        var commits = 0
        val backend = object : ConnectionPreferenceBackend {
            override fun snapshot(): Map<String, *> = HashMap(values)
            override fun commit(writes: Map<String, Any>, removals: Set<String>): Boolean {
                commits++; removals.forEach { values.remove(it) }; values.putAll(writes)
                return commits != 2
            }
        }
        val owner = ConnectionPreferencesOwner(projectionFence, providerIdentity, Runnable { }, backend)
        try { owner.initializeAsync().get(10, TimeUnit.SECONDS); fail("Expected uncertain proof") }
        catch (failure: java.util.concurrent.ExecutionException) {
            assertTrue(failure.cause is ConnectionPreferencesOwner.MetadataUnconfirmedException)
        }
        values.keys.removeAll { it.startsWith(SharedPreferencesConnectionStorage.PREFIX) }
        try { owner.initializeAsync().get(10, TimeUnit.SECONDS); fail("Old capability survived reset") }
        catch (failure: java.util.concurrent.ExecutionException) {
            assertTrue(failure.cause is ConnectionPreferencesOwner.InitializationFailedException)
        }
        assertEquals(2, commits)
        assertTrue(values.isEmpty())
        assertEquals(Bootstrap.FAILED, owner.snapshot().bootstrap)
    }

    @Test fun localBootstrapDoesNotWaitForPendingOrFailedConversationStartup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val field = com.jarvys.agent.SingleSessionReset::class.java.getDeclaredField("startups").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val startups = field.get(null) as MutableMap<String, Any>
        for (failed in listOf(false, true)) {
            val root = temporary.newFolder(if (failed) "failed-chat" else "pending-chat")
            val context = RootContext(app, root)
            worker { com.jarvys.agent.SingleSessionReset.register(root) }
            val key = root.canonicalPath
            try {
                if (failed) {
                    val startup = synchronized(startups) { requireNotNull(startups[key]) }
                    val completionField = startup.javaClass.getDeclaredField("completion").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    val completion = completionField.get(startup) as kotlinx.coroutines.CompletableDeferred<Unit>
                    completion.completeExceptionally(IllegalStateException("synthetic startup failure"))
                }
                assertFalse(worker { com.jarvys.agent.SingleSessionReset.isReady(context) })
                val owner = worker { ConnectionPreferencesOwner.forContext(context, projectionFence, providerIdentity) }
                assertEquals(BootstrapStatus.READY, owner.initializeAsync().get(10, TimeUnit.SECONDS).status)
                assertEquals(DestinationState.UNBOUND, owner.snapshot().destinationState)
                assertFalse(worker { com.jarvys.agent.SingleSessionReset.isReady(context) })
            } finally { synchronized(startups) { startups.remove(key) } }
        }
    }
    @Test fun ownerBindsOnlyMatchingProviderOutsideLocalInitializationQueue() {
        val context = RootContext(ApplicationProvider.getApplicationContext(), temporary.newFolder("binding"))
        val owner = worker { ConnectionPreferencesOwner.forContext(context, projectionFence, providerIdentity) }
        owner.initializeAsync().get(10, TimeUnit.SECONDS)
        assertEquals(DestinationState.UNBOUND, owner.snapshot().destinationState)
        fun evidence(issuer: Any) = object : DestinationEvidence {
            override fun generation() = "real-generation"
            override fun providerIdentity() = issuer
            override fun fenceIdentity() = projectionFence
            override fun isCurrentPure() = true
        }
        try { owner.bindDestination(evidence(providerIdentity)); fail("Main binding accepted") }
        catch (_: IllegalStateException) { }
        val binding = worker { owner.bindDestination(evidence(providerIdentity)) }
        assertNotNull(binding)
        val batch = worker { owner.prepareProjection(binding) }
        assertSame(binding, batch.binding)
        assertNull(worker { owner.bindDestination(evidence(Any())) })
        assertSame(binding, owner.snapshot().binding)
        worker { owner.invalidateDestination() }
        assertEquals(DestinationState.UNBOUND, owner.snapshot().destinationState)
        assertEquals(Outcome.NOT_ATTEMPTED, worker { owner.ackProjection(batch, Ack(batch.sourceEpoch, batch.targetGeneration, batch.events)) })
        assertEquals(Bootstrap.READY, owner.snapshot().bootstrap)
    }

    @Test fun localBootstrapCompletesUnboundWhileAnotherThreadHoldsRoomFence() {
        val context = RootContext(ApplicationProvider.getApplicationContext(), temporary.newFolder("held-room-fence"))
        val acquired = CountDownLatch(1); val release = CountDownLatch(1)
        val holder = java.util.concurrent.Executors.newSingleThreadExecutor()
        val held = holder.submit {
            projectionFence.lock()
            try { acquired.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
            finally { projectionFence.unlock() }
        }
        try {
            assertTrue(acquired.await(10, TimeUnit.SECONDS))
            val owner = worker { ConnectionPreferencesOwner.forContext(context, projectionFence, providerIdentity) }
            val attempt = owner.initializeAsync().get(5, TimeUnit.SECONDS)
            assertEquals(BootstrapStatus.READY, attempt.status)
            assertEquals(DestinationState.UNBOUND, attempt.snapshot.destinationState)
            assertEquals(1L, release.count) // Bootstrap finished before releasing the Room lifecycle fence.
        } finally {
            release.countDown(); held.get(10, TimeUnit.SECONDS)
            holder.shutdownNow(); assertTrue(holder.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

}
