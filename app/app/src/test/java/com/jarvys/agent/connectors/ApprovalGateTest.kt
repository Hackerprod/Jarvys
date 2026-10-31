package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.StopController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ApprovalGateTest {
    private class FakePresenter : ApprovalPresenter {
        val shown = CountDownLatch(1)
        val id = AtomicReference<String>()
        val summary = AtomicReference<ApprovalSummary>()
        val status = AtomicReference<ApprovalDecision>()
        override fun show(id: String, summary: ApprovalSummary) {
            this.id.set(id)
            this.summary.set(summary)
            shown.countDown()
        }
        override fun update(id: String, decision: ApprovalDecision) { status.set(decision) }
    }

    @Test fun approveAndDoubleResolutionAreIdempotent() {
        val presenter = FakePresenter()
        val gate = ApprovalGate(1_000, presenter)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<ApprovalDecision> { gate.request(ApprovalSummary("Create", listOf("Event")), CancellationToken.uncancellable()) }
            assertTrue(presenter.shown.await(1, TimeUnit.SECONDS))
            val id = presenter.id.get()
            assertTrue(gate.resolve(id, ApprovalDecision.APPROVED))
            assertFalse(gate.resolve(id, ApprovalDecision.DENIED))
            assertEquals(ApprovalDecision.APPROVED, pending.get(1, TimeUnit.SECONDS))
            assertEquals(ApprovalDecision.APPROVED, presenter.status.get())
        } finally { executor.shutdownNow() }
    }

    @Test fun notificationActionsResolveTheSameGateAndContinueOrAbortTheWaitingFlow() {
        val approvePresenter = FakePresenter()
        val approveGate = ApprovalGate(1_000, approvePresenter)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val continuation = executor.submit<String> {
                when (approveGate.request(ApprovalSummary("Open SMS Draft", listOf("To: +15551234567", "Message: full body")),
                    CancellationToken.uncancellable())) {
                    ApprovalDecision.APPROVED -> "continued"
                    else -> "aborted"
                }
            }
            assertTrue(approvePresenter.shown.await(1, TimeUnit.SECONDS))
            var openedDraft = false
            assertTrue(ApprovalNotificationActionResolver.resolve(approveGate, approvePresenter.id.get(),
                ApprovalDecision.APPROVED) { openedDraft = true; true })
            assertTrue(openedDraft)
            assertEquals("continued", continuation.get(1, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }

        val rejectPresenter = FakePresenter()
        val rejectGate = ApprovalGate(1_000, rejectPresenter)
        val rejected = Executors.newSingleThreadExecutor()
        try {
            val completion = rejected.submit<String> {
                if (rejectGate.request(ApprovalSummary("Write", emptyList()), CancellationToken.uncancellable())
                    == ApprovalDecision.DENIED) "aborted" else "continued"
            }
            assertTrue(rejectPresenter.shown.await(1, TimeUnit.SECONDS))
            var sideEffectStarted = false
            assertTrue(ApprovalNotificationActionResolver.resolve(rejectGate, rejectPresenter.id.get(),
                ApprovalDecision.DENIED) { sideEffectStarted = true; true })
            assertFalse(sideEffectStarted)
            assertEquals("aborted", completion.get(1, TimeUnit.SECONDS))
        } finally { rejected.shutdownNow() }
    }

    @Test fun rejectionAndTimeoutDoNotApprove() {
        val rejectedPresenter = FakePresenter()
        val rejectedGate = ApprovalGate(1_000, rejectedPresenter)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val rejected = executor.submit<ApprovalDecision> {
                rejectedGate.request(ApprovalSummary("Create", emptyList()), CancellationToken.uncancellable())
            }
            assertTrue(rejectedPresenter.shown.await(1, TimeUnit.SECONDS))
            rejectedGate.resolve(rejectedPresenter.id.get(), ApprovalDecision.DENIED)
            assertEquals(ApprovalDecision.DENIED, rejected.get(1, TimeUnit.SECONDS))
            val timeout = ApprovalGate(15, FakePresenter()).request(
                ApprovalSummary("Create", emptyList()), CancellationToken.uncancellable(),
            )
            assertEquals(ApprovalDecision.EXPIRED, timeout)
        } finally { executor.shutdownNow() }
    }

    @Test fun stopReleasesBlockedApprovalAndReturnsCancellation() {
        val presenter = FakePresenter()
        val gate = ApprovalGate(10_000, presenter)
        val controller = StopController.getInstance()
        val token = controller.beginRun() ?: error("run token unavailable")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = executor.submit<String> {
                try {
                    gate.request(ApprovalSummary("Create", emptyList()), token).name
                } catch (_: java.util.concurrent.CancellationException) { "cancelled" }
            }
            assertTrue(presenter.shown.await(1, TimeUnit.SECONDS))
            assertTrue(controller.stopRun())
            assertEquals("cancelled", waiting.get(1, TimeUnit.SECONDS))
            assertEquals(ApprovalDecision.CANCELLED, presenter.status.get())
        } finally { executor.shutdownNow() }
    }
}
