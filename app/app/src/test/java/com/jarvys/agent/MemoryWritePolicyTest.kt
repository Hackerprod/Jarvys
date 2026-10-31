package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MemoryWritePolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(root: File, overridePreferences: SharedPreferences? = null): Context {
        val app = ApplicationProvider.getApplicationContext<Context>()
        return object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                overridePreferences ?: app.getSharedPreferences("${root.canonicalPath.hashCode()}-$name", mode)
        }
    }

    @Test fun canonicalRootSharesOwnerAndAnotherRootCannotConsumeItsTicket() {
        val root = temporary.newFolder("one")
        val first = MemoryWritePolicy.forContext(context(root))
        val alias = MemoryWritePolicy.forContext(context(File(root, "../one")))
        val foreign = MemoryWritePolicy.forContext(context(temporary.newFolder("other")))
        assertSame(first, alias)
        assertSame(first.gate(), alias.gate())
        assertNotSame(first.gate(), foreign.gate())
        try {
            foreign.gate().commit(first.captureTicket(), { true }) { fail("Foreign effect must not run") }
            fail("Foreign ticket must be rejected")
        } catch (_: MemoryWriteGate.RejectedException) { }
    }

    @Test fun blockingSetterRejectsMainThreadBeforeChangingPreference() {
        val context = context(temporary.newFolder("main"))
        val policy = MemoryWritePolicy.forContext(context)
        try { policy.setEnabledBlocking(false); fail("Main-thread persistence must be rejected") }
        catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("main thread")) }
        assertTrue(policy.isEnabled)
        assertFalse(context.getSharedPreferences(MemoryConstants.PREFERENCES_FILE, 0).contains(MemoryConstants.ENABLED_KEY))
    }

    @Test fun queuedWritesAndHooksRemainOrderedWhenUiStopsAwaiting() {
        val context = context(temporary.newFolder("ordered"))
        val readyEntered = CountDownLatch(1)
        val releaseReady = CountDownLatch(1)
        val waits = AtomicInteger()
        val policy = MemoryWritePolicy(context) {
            if (waits.incrementAndGet() == 1) {
                readyEntered.countDown()
                check(releaseReady.await(5, TimeUnit.SECONDS))
            }
        }
        val effects = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val first = policy.requestSetEnabled(false) { enabled ->
            assertFalse(enabled)
            assertFalse(policy.gate().isEnabled)
            effects += enabled
        }
        assertTrue(readyEntered.await(5, TimeUnit.SECONDS))
        first.cancel(false) // Cancellation belongs to the observer, not the process-owned operation.
        val second = policy.requestSetEnabled(true) { enabled -> effects += enabled }
        releaseReady.countDown()
        val result = second.get(5, TimeUnit.SECONDS)
        assertTrue(result.confirmed)
        assertTrue(result.effectiveEnabled)
        assertEquals(listOf(false, true), effects)
        assertTrue(policy.readStateAsync().get(5, TimeUnit.SECONDS).effectiveEnabled)
    }

    @Test fun preferenceCommitFailureMayChangeRawMemoryButNeverConfirmsOrEnables() {
        val base = context(temporary.newFolder("commit-failure"))
        val original = base.getSharedPreferences(MemoryConstants.PREFERENCES_FILE, 0)
        original.edit().putBoolean(MemoryConstants.ENABLED_KEY, false).commit()
        val failing = object : SharedPreferences by original {
            override fun edit(): SharedPreferences.Editor {
                val editor = original.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                        editor.putBoolean(key, value); return this
                    }
                    override fun commit(): Boolean { editor.apply(); return false }
                }
            }
        }
        val policy = MemoryWritePolicy(context(base.filesDir, failing)) { }
        var hookEnabled = true
        val result = policy.requestSetEnabled(true) { hookEnabled = it }.get(5, TimeUnit.SECONDS)
        assertFalse(result.confirmed)
        assertFalse(result.effectiveEnabled)
        assertFalse(hookEnabled)
        assertTrue(original.getBoolean(MemoryConstants.ENABLED_KEY, false))
        assertFalse(policy.isEnabled)
        assertFalse(policy.readStateAsync().get(5, TimeUnit.SECONDS).confirmed)
    }

    @Test fun readinessFailureDeniesOldTicketsAndDoesNotRetryAnUnrecoverableStartup() {
        val calls = AtomicInteger()
        val policy = MemoryWritePolicy(context(temporary.newFolder("readiness"))) {
            calls.incrementAndGet(); error("Synthetic startup failure")
        }
        val old = policy.gate().capture()
        val result = policy.requestSetEnabled(false, null).get(5, TimeUnit.SECONDS)
        assertFalse(result.confirmed)
        assertFalse(result.effectiveEnabled)
        try {
            policy.gate().commit(old, { true }) { fail("Startup failure must deny old ticket") }
            fail("Old ticket must not commit")
        } catch (_: MemoryWriteGate.RejectedException) { }
        assertFalse(policy.requestSetEnabled(true, null).get(5, TimeUnit.SECONDS).confirmed)
        assertEquals(1, calls.get())
    }

    @Test fun followUpFailureCannotUndoAConfirmedOffPreference() {
        val policy = MemoryWritePolicy(context(temporary.newFolder("hook"))) { }
        val result = policy.requestSetEnabled(false) { error("Synthetic scheduling failure") }.get(5, TimeUnit.SECONDS)
        assertTrue(result.confirmed)
        assertFalse(result.effectiveEnabled)
        assertFalse(result.followUpSucceeded)
        assertFalse(policy.isEnabled)
    }
}
