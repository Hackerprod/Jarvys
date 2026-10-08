package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.StopController
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ApprovalGateSafetyTest {
    private class Presenter : ApprovalPresenter {
        val shown = CountDownLatch(1)
        val id = AtomicReference<String>()
        override fun show(id: String, summary: ApprovalSummary) {
            this.id.set(id)
            shown.countDown()
        }
        override fun update(id: String, decision: ApprovalDecision) = Unit
    }

    @Test fun missingApprovalIsNotPendingAndCannotDispatchAnIntent() {
        val gate = ApprovalGate(1_000, Presenter())
        var invoked = false
        assertFalse(gate.isPending("missing"))
        assertFalse(ApprovalNotificationActionResolver.resolve(gate, "missing", ApprovalDecision.APPROVED) {
            invoked = true
            true
        })
        assertFalse(invoked)
    }

    @Test fun expiredApprovalCannotDispatchAnIntent() {
        val presenter = Presenter()
        val gate = ApprovalGate(1, presenter)
        assertEquals(ApprovalDecision.EXPIRED, gate.request(ApprovalSummary("Write", emptyList()), CancellationToken.uncancellable()))
        var invoked = false
        assertFalse(gate.isPending(presenter.id.get()))
        assertFalse(ApprovalNotificationActionResolver.resolve(gate, presenter.id.get(), ApprovalDecision.APPROVED) {
            invoked = true
            true
        })
        assertFalse(invoked)
    }

    @Test fun resolvedApprovalCannotDispatchTheSameIntentTwice() {
        val presenter = Presenter()
        val gate = ApprovalGate(1_000, presenter)
        val executor = Executors.newSingleThreadExecutor()
        val effects = AtomicInteger()
        try {
            val pending = executor.submit<ApprovalDecision> {
                gate.request(ApprovalSummary("Open draft", emptyList()), CancellationToken.uncancellable())
            }
            assertTrue(presenter.shown.await(1, TimeUnit.SECONDS))
            val id = presenter.id.get()
            assertTrue(ApprovalNotificationActionResolver.resolve(gate, id, ApprovalDecision.APPROVED) {
                effects.incrementAndGet()
                true
            })
            assertEquals(ApprovalDecision.APPROVED, pending.get(1, TimeUnit.SECONDS))
            assertFalse(gate.isPending(id))
            assertFalse(ApprovalNotificationActionResolver.resolve(gate, id, ApprovalDecision.APPROVED) {
                effects.incrementAndGet()
                true
            })
            assertEquals(1, effects.get())
        } finally { executor.shutdownNow() }
    }

    @Test fun cancellationDuringPresentationPreventsApprovalSideEffects() {
        val token = CancellationToken.cancellable()
        lateinit var gate: ApprovalGate
        var invoked = false
        val status = AtomicReference<ApprovalDecision>()
        val presenter = object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) {
                token.cancel()
                assertFalse(gate.isPending(id))
                assertFalse(ApprovalNotificationActionResolver.resolve(gate, id, ApprovalDecision.APPROVED) {
                    invoked = true
                    true
                })
            }
            override fun update(id: String, decision: ApprovalDecision) { status.set(decision) }
        }
        gate = ApprovalGate(1_000, presenter)
        assertThrows(CancellationException::class.java) {
            gate.request(ApprovalSummary("Open draft", emptyList()), token)
        }
        assertFalse(invoked)
        assertEquals(ApprovalDecision.CANCELLED, status.get())
    }

    @Test fun deniedApprovalNeverDispatchesItsAction() {
        val presenter = Presenter()
        val gate = ApprovalGate(1_000, presenter)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<ApprovalDecision> {
                gate.request(ApprovalSummary("Write", emptyList()), CancellationToken.uncancellable())
            }
            assertTrue(presenter.shown.await(1, TimeUnit.SECONDS))
            var invoked = false
            assertTrue(ApprovalNotificationActionResolver.resolve(gate, presenter.id.get(), ApprovalDecision.DENIED) {
                invoked = true
                true
            })
            assertEquals(ApprovalDecision.DENIED, pending.get(1, TimeUnit.SECONDS))
            assertFalse(invoked)
        } finally { executor.shutdownNow() }
    }

    @Test fun stoppedControllerRegistrationDoesNotHoldItsMonitorWhileEnteringApprovalGate() {
        val controller = StopController.getInstance()
        val token = controller.beginRun() ?: error("run token unavailable")
        val gate = ApprovalGate(1_000, Presenter())
        val gateLock = ApprovalGate::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(gate)
        val callbackStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            assertTrue(controller.stopRun())
            val registration = synchronized(gateLock) {
                val pending = executor.submit<Runnable> {
                    token.registerCancelAction {
                        callbackStarted.countDown()
                        gate.resolve("stopped-request", ApprovalDecision.CANCELLED)
                    }
                }
                assertTrue(callbackStarted.await(1, TimeUnit.SECONDS))
                // The callback is waiting for this gate lock. Its registration must not hold
                // the controller monitor needed by UI-side cancellation checks under that lock.
                val check = executor.submit<Boolean> { token.isCancellationRequested }
                assertTrue(check.get(1, TimeUnit.SECONDS))
                pending
            }
            registration.get(1, TimeUnit.SECONDS)
        } finally {
            controller.stopRun()
            executor.shutdownNow()
        }
    }

    @Test fun stopDuringPresentationRejectsAnIntentBeforeCancellationRegistration() {
        val controller = StopController.getInstance()
        val token = controller.beginRun() ?: error("run token unavailable")
        lateinit var gate: ApprovalGate
        var invoked = false
        val presenter = object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) {
                assertTrue(controller.stopRun())
                assertFalse(gate.isPending(id))
                assertFalse(ApprovalNotificationActionResolver.resolve(gate, id, ApprovalDecision.APPROVED) {
                    invoked = true
                    true
                })
            }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        }
        gate = ApprovalGate(1_000, presenter)
        try {
            assertThrows(CancellationException::class.java) {
                gate.request(ApprovalSummary("Open draft", emptyList()), token)
            }
            assertFalse(invoked)
        } finally { controller.stopRun() }
    }

    @Test fun asynchronousPermissionPromptStartsOnceAndItsLateFallbackCannotRunAfterStop() {
        val presenter = Presenter()
        val gate = ApprovalGate(1_000, presenter)
        val token = CancellationToken.crewChild()
        val executor = Executors.newSingleThreadExecutor()
        var launches = 0
        var fallback = false
        try {
            val pending = executor.submit<Boolean> {
                try {
                    gate.request(ApprovalSummary("Request permission", emptyList()), token)
                    false
                } catch (_: CancellationException) { true }
            }
            assertTrue(presenter.shown.await(1, TimeUnit.SECONDS))
            val id = presenter.id.get()
            assertTrue(gate.beginUiAction(id) { launches++ })
            assertTrue(gate.isPending(id))
            assertFalse(gate.beginUiAction(id) { launches++ })
            assertEquals(1, launches)
            assertTrue(token.cancel())
            assertTrue(pending.get(1, TimeUnit.SECONDS))
            assertFalse(gate.resolveFromUi(id) {
                fallback = true
                ApprovalDecision.PERMISSION_FALLBACK_LAUNCHED
            })
            assertFalse(fallback)
            assertFalse(gate.beginUiAction(id) { launches++ })
            assertEquals(1, launches)
        } finally {
            token.cancel()
            executor.shutdownNow()
        }
    }
}
