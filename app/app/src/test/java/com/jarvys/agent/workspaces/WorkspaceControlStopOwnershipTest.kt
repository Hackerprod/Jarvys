package com.jarvys.agent.workspaces

import com.jarvys.agent.CancellationToken
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Process-local ownership tests. The approval wait below is synthetic; no provider or UI is used. */
class WorkspaceControlStopOwnershipTest {
    private val registered = mutableListOf<CancellationToken>()

    private fun owner(type: WorkspaceControlType): CancellationToken =
        WorkspaceControlOwners.register(type).also { registered += it }

    @After fun releaseOwners() {
        registered.forEach { WorkspaceControlOwners.release(it); it.cancel() }
    }

    @Test fun stopCancelsResumeAndCorrectionRegisteredBeforeExecutorAdmission() {
        val resume = owner(WorkspaceControlType.RESUME)
        val correct = owner(WorkspaceControlType.CORRECT)
        WorkspaceControlOwners.cancelPending()
        assertTrue(resume.isCancellationRequested)
        assertTrue(correct.isCancellationRequested)
        var persisted = false
        assertFalse(resume.runIfActive { persisted = true })
        assertFalse(correct.runIfActive { persisted = true })
        assertFalse(persisted)
    }

    @Test fun stopDoesNotCancelSafetyPauseOrItsCleanup() {
        val pause = owner(WorkspaceControlType.PAUSE)
        val cancelled = AtomicInteger()
        val unregister = pause.registerCancelAction { cancelled.incrementAndGet() }
        try {
            WorkspaceControlOwners.cancelPending()
            assertFalse(pause.isCancellationRequested)
            assertEquals(0, cancelled.get())
            assertTrue(pause.runIfActive {})
        } finally { unregister.run() }
    }

    @Test fun completedOwnersAreRemovedAndNewExplicitRequestAfterStopRemainsActive() {
        val completed = owner(WorkspaceControlType.RESUME)
        WorkspaceControlOwners.release(completed)
        val old = owner(WorkspaceControlType.CORRECT)
        WorkspaceControlOwners.cancelPending()
        assertFalse(completed.isCancellationRequested)
        assertTrue(old.isCancellationRequested)
        val newer = owner(WorkspaceControlType.RESUME)
        assertFalse(newer.isCancellationRequested)
        assertTrue(newer.runIfActive {})
    }

    @Test fun lateSyntheticApprovalCannotDispatchAfterGlobalStop() {
        val token = owner(WorkspaceControlType.RESUME)
        val waiting = CountDownLatch(1)
        val approval = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val effects = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                waiting.countDown()
                assertTrue(approval.await(5, TimeUnit.SECONDS))
                assertFalse(token.runIfActive { effects.incrementAndGet() })
            } catch (problem: Throwable) { failure.set(problem) }
            finally { finished.countDown() }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            WorkspaceControlOwners.cancelPending()
            approval.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError(it) }
            assertEquals(0, effects.get())
        } finally { approval.countDown(); thread.interrupt() }
    }

    @Test fun actionAdmissionAndStopHaveOneOrderWithoutHoldingCleanupOpen() {
        val token = owner(WorkspaceControlType.CORRECT)
        val admitted = CountDownLatch(1)
        val releaseEffect = CountDownLatch(1)
        val stopEntered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val failure = AtomicReference<Throwable?>()
        val effect = Thread {
            try {
                assertTrue(token.runIfActive {
                    events += "effect-start"
                    admitted.countDown()
                    assertTrue(releaseEffect.await(5, TimeUnit.SECONDS))
                    events += "effect-end"
                })
            } catch (problem: Throwable) { failure.compareAndSet(null, problem) }
            finally { finished.countDown() }
        }.apply { isDaemon = true; start() }
        val stopping = Thread {
            try {
                stopEntered.countDown()
                WorkspaceControlOwners.cancelPending()
                events += "stop-return"
            } catch (problem: Throwable) { failure.compareAndSet(null, problem) }
            finally { stopped.countDown() }
        }.apply { isDaemon = true }
        try {
            assertTrue(admitted.await(5, TimeUnit.SECONDS))
            stopping.start()
            assertTrue(stopEntered.await(5, TimeUnit.SECONDS))
            assertFalse("STOP cannot finish before an already admitted dispatch releases its gate", stopped.await(100, TimeUnit.MILLISECONDS))
            releaseEffect.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError(it) }
            assertEquals(listOf("effect-start", "effect-end", "stop-return"), events.toList())
            assertTrue(token.isCancellationRequested)
        } finally { releaseEffect.countDown(); effect.interrupt(); stopping.interrupt() }
    }

    @Test fun cancellationCallbacksDoNotRunUnderOwnerRegistryLock() {
        val token = owner(WorkspaceControlType.CORRECT)
        val registeredFromCallback = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val unregister = token.registerCancelAction {
            val thread = Thread {
                try {
                    val another = WorkspaceControlOwners.register(WorkspaceControlType.RESUME)
                    WorkspaceControlOwners.release(another)
                    registeredFromCallback.countDown()
                } catch (problem: Throwable) { failure.set(problem) }
            }.apply { isDaemon = true; start() }
            try { assertTrue(registeredFromCallback.await(5, TimeUnit.SECONDS)) }
            finally { thread.interrupt() }
        }
        try {
            WorkspaceControlOwners.cancelPending()
            failure.get()?.let { throw AssertionError(it) }
            assertEquals(0L, registeredFromCallback.count)
        } finally { unregister.run() }
    }
}
