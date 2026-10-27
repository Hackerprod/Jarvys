package com.jarvys.agent.linux

import com.jarvys.agent.ChatTestStore
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.coding.ProjectScope
import com.jarvys.agent.coding.ProjectScopeStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Adversarial executor scheduling without any shell/process or network execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodingJobWorkerClaimTest {
    @get:Rule val chat = ChatTestStore(true)
    private fun files() = chat.newFilesDirectory().also { chat.localRunStore(it) }
    private fun success(count: AtomicInteger) = CodingJobManager.Backend { _, _, _, _, _, _, launch ->
        count.incrementAndGet(); launch(); LinuxExecResult(0)
    }
    private fun assertLeaseBusy(scope: ProjectScope) {
        assertThrows(Exception::class.java) { scope.acquireWriter("competing-owner", scope.version()).use { } }
    }
    private fun assertLeaseFree(scope: ProjectScope) { scope.acquireWriter("probe-owner", scope.version()).use { } }

    @Test fun inlineRunThenThrowPreservesFactualSuccessAndSingleCompletion() {
        val files = files(); val scope = ProjectScopeStore(files).open("claim-inline")
        val calls = AtomicInteger(); val completions = AtomicInteger()
        val manager = CodingJobManager(files, success(calls), executor = Executor { it.run(); throw IllegalStateException("synthetic enqueue failure") })
        val failure = assertThrows(CodingJobManager.EnqueueOutcomeUncertain::class.java) {
            manager.start(scope, "owner", "synthetic-command", ".", 0, null, CancellationToken.cancellable(), completion = { completions.incrementAndGet() })
        }
        assertEquals(CodingJobManager.State.SUCCEEDED, failure.observedState); assertEquals(0, failure.observedExitCode)
        val terminal = manager.list("owner", scope).single()
        assertEquals(failure.jobId, terminal.id); assertEquals(CodingJobManager.State.SUCCEEDED, terminal.state)
        assertEquals(0, terminal.exitCode); assertEquals(1, calls.get()); assertEquals(1, completions.get())
        assertEquals(1L, scope.version()); assertLeaseFree(scope); assertFalse(manager.hasPending("owner"))
    }

    @Test fun executorCancellationAfterInlineClaimIsTransportUncertaintyNotJobCancellation() {
        val files = files(); val scope = ProjectScopeStore(files).open("claim-executor-cancellation")
        val calls = AtomicInteger(); val completions = AtomicInteger(); val token = CancellationToken.cancellable()
        val manager = CodingJobManager(files, success(calls), executor = Executor {
            it.run(); throw CancellationException("synthetic executor cancellation after delivery")
        })
        val failure = assertThrows(CodingJobManager.EnqueueOutcomeUncertain::class.java) {
            manager.start(scope, "owner", "synthetic-command", ".", 0, null, token,
                completion = { completions.incrementAndGet() })
        }
        assertFalse(token.isCancellationRequested)
        assertEquals(CodingJobManager.State.SUCCEEDED, failure.observedState); assertEquals(0, failure.observedExitCode)
        val observed = manager.list("owner", scope).single()
        assertEquals(failure.jobId, observed.id); assertEquals(CodingJobManager.State.SUCCEEDED, observed.state)
        assertEquals(1, calls.get()); assertEquals(1, completions.get()); assertEquals(1L, scope.version())
        assertLeaseFree(scope); assertFalse(manager.hasPending("owner"))
    }

    @Test fun liveClaimThenEnqueueThrowDoesNotFinalizeOrReleaseWorkerLease() {
        val files = files(); val scope = ProjectScopeStore(files).open("claim-live")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = AtomicInteger(); val completions = AtomicInteger(); val worker = AtomicReference<Thread>()
        val workerFailure = AtomicReference<Throwable>()
        val backend = CodingJobManager.Backend { _, _, _, _, _, _, launch ->
            calls.incrementAndGet(); launch(); entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); LinuxExecResult(0)
        }
        val executor = Executor { work ->
            Thread({ try { work.run() } catch (failure: Throwable) { workerFailure.set(failure) } }, "claim-test").also { worker.set(it); it.start() }
            check(entered.await(5, TimeUnit.SECONDS)); throw IllegalStateException("synthetic enqueue failure")
        }
        val manager = CodingJobManager(files, backend, executor = executor)
        try {
            val failure = assertThrows(CodingJobManager.EnqueueOutcomeUncertain::class.java) {
                manager.start(scope, "owner", "synthetic-command", ".", 0, null, CancellationToken.cancellable(), completion = { completions.incrementAndGet() })
            }
            assertEquals(CodingJobManager.State.RUNNING, failure.observedState); assertNull(failure.observedExitCode)
            assertEquals(CodingJobManager.State.RUNNING, manager.list("owner", scope).single().state)
            assertEquals(0, completions.get()); assertLeaseBusy(scope); assertTrue(manager.hasPending("owner"))
            release.countDown()
            val terminal = manager.await("owner", scope, failure.jobId, 5000, CancellationToken.cancellable())
            worker.get().join(5000)
            assertFalse(worker.get().isAlive); assertNull(workerFailure.get())
            assertEquals(CodingJobManager.State.SUCCEEDED, terminal.state); assertEquals(1, completions.get())
            assertEquals(1, calls.get()); assertEquals(1L, scope.version()); assertLeaseFree(scope)
        } finally { release.countDown(); worker.get()?.join(5000) }
    }

    @Test fun duplicateRunnableDuringClaimAndAfterFinishDoesNotRunOrCleanUpTwice() {
        val files = files(); val scope = ProjectScopeStore(files).open("claim-duplicate")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = AtomicInteger(); val completions = AtomicInteger(); val queued = mutableListOf<Runnable>()
        val backend = CodingJobManager.Backend { _, _, _, _, _, _, launch ->
            calls.incrementAndGet(); launch(); entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); LinuxExecResult(0)
        }
        val manager = CodingJobManager(files, backend, executor = Executor { queued += it })
        val job = manager.start(scope, "owner", "synthetic-command", ".", 0, null, CancellationToken.cancellable(), completion = { completions.incrementAndGet() })
        val error = AtomicReference<Throwable>()
        val thread = Thread { try { queued.single().run() } catch (failure: Throwable) { error.set(failure) } }
        try {
            thread.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            queued.single().run()
            assertEquals(1, calls.get()); assertEquals(0, completions.get()); assertLeaseBusy(scope)
            assertEquals(CodingJobManager.State.RUNNING, manager.list("owner", scope).single().state)
            release.countDown(); thread.join(5000); assertFalse(thread.isAlive); assertNull(error.get())
            queued.single().run()
            assertEquals(CodingJobManager.State.SUCCEEDED, manager.await("owner", scope, job.id, 0, CancellationToken.cancellable()).state)
            assertEquals(1, completions.get()); assertEquals(1, calls.get()); assertEquals(1L, scope.version()); assertLeaseFree(scope)
        } finally { release.countDown(); thread.join(5000) }
    }

    @Test fun cancellationBeforeClaimDoesNotDispatchAndDuplicateDoesNotRefinalize() {
        val files = files(); val scope = ProjectScopeStore(files).open("claim-cancel")
        val calls = AtomicInteger(); val completions = AtomicInteger(); val queued = mutableListOf<Runnable>()
        val manager = CodingJobManager(files, success(calls), executor = Executor { queued += it })
        val token = CancellationToken.cancellable()
        manager.start(scope, "owner", "synthetic-command", ".", 0, null, token, completion = { completions.incrementAndGet() })
        token.cancel(); assertLeaseBusy(scope)
        queued.single().run(); queued.single().run()
        assertEquals(CodingJobManager.State.CANCELLED, manager.list("owner", scope).single().state)
        assertEquals(0, calls.get()); assertEquals(1, completions.get()); assertEquals(0L, scope.version()); assertLeaseFree(scope)
    }

    @Test fun trueRejectionRejectsRetainedLateRunnablePermanently() {
        val files = files(); val scope = ProjectScopeStore(files).open("claim-rejected")
        val calls = AtomicInteger(); val completions = AtomicInteger(); val queued = mutableListOf<Runnable>()
        val manager = CodingJobManager(files, success(calls), executor = Executor { queued += it; throw IllegalStateException("synthetic rejection") })
        val failure = assertThrows(IllegalStateException::class.java) {
            manager.start(scope, "owner", "synthetic-command", ".", 0, null, CancellationToken.cancellable(), completion = { completions.incrementAndGet() })
        }
        assertEquals(IllegalStateException::class.java, failure.javaClass)
        assertEquals(CodingJobManager.State.NOT_STARTED, manager.list("owner", scope).single().state)
        queued.single().run(); queued.single().run()
        assertEquals(0, calls.get()); assertEquals(1, completions.get()); assertEquals(0L, scope.version()); assertLeaseFree(scope)
    }

    @Test fun fatalWorkerErrorStillFinalizesOnceAndIsRethrown() {
        for (launched in listOf(false, true)) {
            val files = files(); val scope = ProjectScopeStore(files).open("claim-fatal-$launched")
            val fatal = AssertionError("synthetic fatal worker")
            val calls = AtomicInteger(); val completions = AtomicInteger()
            val backend = CodingJobManager.Backend { _, _, _, _, _, _, launch ->
                calls.incrementAndGet(); if (launched) launch(); throw fatal
            }
            val manager = CodingJobManager(files, backend, executor = Executor { it.run() })
            val thrown = assertThrows(AssertionError::class.java) {
                manager.start(scope, "owner", "synthetic-command", ".", 0, null,
                    CancellationToken.cancellable(), completion = { completions.incrementAndGet() })
            }
            assertSame(fatal, thrown); assertEquals(1, calls.get()); assertEquals(1, completions.get())
            val terminal = manager.list("owner", scope).single()
            assertEquals(CodingJobManager.State.UNCERTAIN, terminal.state); assertNull(terminal.exitCode)
            assertEquals(if (launched) 1L else 0L, scope.version()); assertLeaseFree(scope)
            assertFalse(manager.hasPending("owner"))
        }
    }

    @Test fun completedCallbackMayReenterWithoutOldCatchReleasingNewJobLease() {
        val files = files(); val scope = ProjectScopeStore(files).open("claim-reentry")
        val calls = AtomicInteger(); val submissions = AtomicInteger(); val queued = mutableListOf<Runnable>()
        val callbacks = AtomicInteger(); val reentryError = AtomicReference<Throwable>(); val secondId = AtomicReference<String>()
        val executor = Executor { work ->
            if (submissions.incrementAndGet() == 1) { work.run(); throw IllegalStateException("synthetic late enqueue failure") }
            else queued += work
        }
        val manager = CodingJobManager(files, success(calls), executor = executor)
        val firstFailure = assertThrows(CodingJobManager.EnqueueOutcomeUncertain::class.java) {
            manager.start(scope, "owner", "first-synthetic", ".", 0, null, CancellationToken.cancellable(), completion = {
                callbacks.incrementAndGet()
                try { secondId.set(manager.start(scope, "owner", "second-synthetic", ".", scope.version(), null,
                    CancellationToken.cancellable(), completion = { callbacks.incrementAndGet() }).id) }
                catch (failure: Throwable) { reentryError.set(failure) }
            })
        }
        assertNull(reentryError.get()); assertNotNull(secondId.get()); assertNotEquals(firstFailure.jobId, secondId.get())
        assertEquals(CodingJobManager.State.SUCCEEDED, firstFailure.observedState)
        assertEquals(1, callbacks.get()); assertLeaseBusy(scope); assertEquals(1L, scope.version())
        queued.single().run()
        assertEquals(2, callbacks.get()); assertEquals(2, calls.get()); assertEquals(2L, scope.version()); assertLeaseFree(scope)
        assertTrue(manager.list("owner", scope).all { it.state == CodingJobManager.State.SUCCEEDED })
    }
}
