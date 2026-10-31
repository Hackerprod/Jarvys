package com.jarvys.agent.ui.mascot

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.rive.runtime.kotlin.core.RefCount
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Reference ownership only; no Rive native object is constructed. */
class JarvysHeaderLifecycleTest {
    private class FakeRef(private val failure: Throwable? = null) : RefCount {
        override var refs = AtomicInteger(1)
        var releases = 0
        override fun release(): Int { releases++; failure?.let { throw it }; return refs.decrementAndGet() }
        override fun acquire(): Int = refs.incrementAndGet()
        override val refCount: Int get() = refs.get()
    }
    private class Owner(private val throwOnRemoval: Boolean = false) : LifecycleOwner {
        var removals = 0
        override val lifecycle = object : Lifecycle() {
            override val currentState = State.DESTROYED
            override fun addObserver(observer: LifecycleObserver) = Unit
            override fun removeObserver(observer: LifecycleObserver) { removals++; if (throwOnRemoval) error("owner removal") }
        }
    }
    @Test fun composeThenActivityReleasesDependenciesExactlyOnce() {
        val first = FakeRef(); val second = FakeRef(); val owner = Owner()
        val observer = HeaderViewLifecycleObserver(mutableListOf(first, second))
        observer.onDestroy(owner); observer.onDestroy(owner)
        assertEquals(1, first.releases); assertEquals(1, second.releases)
        assertEquals(0, first.refCount); assertEquals(0, second.refCount); assertEquals(2, owner.removals)
    }
    @Test fun activityThenComposeCannotDoubleRelease() {
        val ref = FakeRef(); val activity = Owner(); val composition = Owner()
        val observer = HeaderViewLifecycleObserver(mutableListOf(ref))
        observer.onDestroy(activity); observer.onDestroy(composition); observer.onDestroy(activity)
        assertEquals(1, ref.releases); assertEquals(0, ref.refCount)
        assertEquals(2, activity.removals); assertEquals(1, composition.removals)
    }
    @Test fun throwingReleaseCannotSkipOtherResourcesOrRetryTheUncertainRelease() {
        for (failure in listOf(IllegalStateException("release"), UnsatisfiedLinkError("native"), OutOfMemoryError("allocation"))) {
            val first = FakeRef(failure); val second = FakeRef(); val owner = Owner()
            val observer = HeaderViewLifecycleObserver(mutableListOf(first, second))
            observer.onDestroy(owner); observer.onDestroy(owner)
            assertEquals(1, first.releases); assertEquals(1, second.releases)
            assertEquals(0, second.refCount); assertEquals(2, owner.removals)
        }
    }
    @Test fun bestEffortActionsRemainIndependent() {
        var attempts = 0
        headerBestEffortCleanup { attempts++; error("unregister") }
        headerBestEffortCleanup { attempts++; throw UnsatisfiedLinkError("pause") }
        headerBestEffortCleanup { attempts++ }
        assertEquals(3, attempts)
    }
    @Test fun superclassObserverRemovalFailureCannotEscapeOrReplayNativeCleanup() {
        val ref = FakeRef(); val owner = Owner(throwOnRemoval = true)
        val observer = HeaderViewLifecycleObserver(mutableListOf(ref))
        observer.onDestroy(owner); observer.onDestroy(owner)
        assertEquals(1, ref.releases); assertEquals(2, owner.removals)
    }
}
