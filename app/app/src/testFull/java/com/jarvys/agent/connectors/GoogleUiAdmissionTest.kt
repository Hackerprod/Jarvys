package com.jarvys.agent.connectors

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GoogleUiAdmissionTest {
    private class ManualExecutor : Executor {
        val jobs = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { jobs.addLast(command) }
        fun runNext() { jobs.removeFirst().run() }
    }
    @Test fun busyRejectsBeforeCapturingOnAndOffIsImmediateBeforeWorker() {
        val executor = ManualExecutor()
        val owner = GoogleUiAdmission(executor).apply { initialize(false) }
        var off = false
        owner.local({ off = true }, {}, { _, done -> done(true) })
        assertTrue(off)
        assertTrue(owner.isLocallyDenied())
        var captures = 0
        assertEquals(GoogleUiAdmission.Submission.Rejected(GoogleUiAdmission.Rejection.BUSY),
            owner.authorize({ captures++ }, {}, { _, _ -> fail("No launch") }))
        assertEquals(0, captures)
        executor.runNext()
        assertFalse(owner.isLocallyDenied())
    }
    @Test fun serialCleanupFinishesBeforeNewAuthorizationCanStart() {
        val executor = ManualExecutor()
        val owner = GoogleUiAdmission(executor).apply { initialize(false) }
        val events = mutableListOf<String>()
        lateinit var finish: (Boolean) -> Unit
        owner.local({}, {}, { _, done -> events += "cleanup-start"; finish = done })
        executor.runNext()
        assertTrue(owner.authorize({}, {}, { _, _ -> events += "authorize" }) is GoogleUiAdmission.Submission.Rejected)
        finish(true)
        owner.authorize({}, {}, { _, _ -> events += "authorize" })
        executor.runNext()
        assertEquals(listOf("cleanup-start", "authorize"), events)
    }
    @Test fun lateCompletionCannotClearNewerPendingOrInteractiveOwner() {
        val executor = ManualExecutor()
        val owner = GoogleUiAdmission(executor).apply { initialize(false) }
        val old = (owner.authorize({}, {}, { _, _ -> }) as GoogleUiAdmission.Submission.Accepted).ticket
        executor.runNext()
        owner.local({}, {}, { _, done -> done(true) })
        assertFalse(owner.isCurrent(old))
        owner.finishAuthorization(old)
        executor.runNext()
        val current = owner.authorize({}, {}, { _, _ -> }) as GoogleUiAdmission.Submission.Accepted
        owner.finishAuthorization(old)
        assertTrue(owner.isCurrent(current.ticket))
        assertTrue(owner.authorize({}, {}, { _, _ -> }) is GoogleUiAdmission.Submission.Rejected)
    }
    @Test fun failureRemainsDeniedUntilExplicitSuccessfulRecovery() {
        val executor = ManualExecutor()
        val owner = GoogleUiAdmission(executor).apply { initialize(false) }
        owner.local({}, {}, { _, done -> done(false); done(true) })
        executor.runNext()
        assertTrue(owner.isLocallyDenied())
        assertTrue(owner.authorize({ fail("No capture") }, {}, { _, _ -> }) is GoogleUiAdmission.Submission.Rejected)
        owner.local({}, {}, { _, done -> done(true) })
        executor.runNext()
        assertFalse(owner.isLocallyDenied())
    }
    @Test fun allLocalCommandsFinishBeforeDenyIsReleasedAndQueueIsFifo() {
        val executor = ManualExecutor()
        val owner = GoogleUiAdmission(executor).apply { initialize(false) }
        val order = mutableListOf<Int>()
        owner.local({}, {}, { _, done -> order += 1; done(true) })
        owner.local({}, {}, { _, done -> order += 2; done(true) })
        executor.runNext()
        assertTrue(owner.isLocallyDenied())
        executor.runNext()
        assertEquals(listOf(1, 2), order)
        assertFalse(owner.isLocallyDenied())
    }
    @Test fun publicationRunsOutsideAdmissionAndCanReenterFromAnotherThread() {
        val executor = ManualExecutor()
        val owner = GoogleUiAdmission(executor).apply { initialize(false) }
        owner.local({}, {
            val completed = CountDownLatch(1)
            Thread { owner.authorize({}, {}, { _, _ -> }); completed.countDown() }.start()
            assertTrue("Listener must not run under admission lock", completed.await(2, TimeUnit.SECONDS))
        }, { _, done -> done(true) })
    }
    @Test fun unknownAndRevocationBlockedNeverCaptureOn() {
        val owner = GoogleUiAdmission(ManualExecutor())
        assertEquals(GoogleUiAdmission.Submission.Rejected(GoogleUiAdmission.Rejection.NOT_READY),
            owner.authorize({ fail("No capture") }, {}, { _, _ -> }))
        owner.initialize(true)
        assertEquals(GoogleUiAdmission.Submission.Rejected(GoogleUiAdmission.Rejection.REVOCATION_PENDING),
            owner.authorize({ fail("No capture") }, {}, { _, _ -> }))
    }
    @Test fun rejectedSilentCaptureDoesNotEnqueueOAuthOrKeepInteractiveBusy() {
        val executor = ManualExecutor()
        val owner = GoogleUiAdmission(executor).apply { initialize(false) }
        assertTrue(runCatching {
            owner.authorize({ error("Connection request was not accepted") }, {}, { _, _ -> fail("No OAuth") })
        }.isFailure)
        assertTrue(executor.jobs.isEmpty())
        assertTrue(owner.authorize({}, {}, { _, _ -> }) is GoogleUiAdmission.Submission.Accepted)
    }
    @Test fun unexpectedLocalFailureRemainsDeniedButDoesNotStrandRecoveryQueue() {
        val executor = ManualExecutor()
        val owner = GoogleUiAdmission(executor).apply { initialize(false) }
        owner.local({}, {}, { _, _ -> error("Synthetic local failure") })
        executor.runNext()
        assertTrue(owner.isLocallyDenied())
        owner.local({}, {}, { _, done -> done(true) })
        executor.runNext()
        assertFalse(owner.isLocallyDenied())
    }

}
