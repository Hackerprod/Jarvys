package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.MemoryUiAutomationGuard
import org.junit.Assert.*
import org.junit.Test

class ApprovalPolicyProtectionTest {
    @Test fun realAskIsProtectedUntilExactlyResolved() {
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(1000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) {
                assertTrue(MemoryUiAutomationGuard.isProtected())
                assertTrue(gate.resolveFromUi(id) { ApprovalDecision.APPROVED })
            }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        assertEquals(ApprovalDecision.APPROVED, gate.request(ApprovalSummary("Exact ASK", emptyList()), CancellationToken.uncancellable()))
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun taintedEntryFailsWithoutAnUnusableCardOrLeaseLeak() {
        var cards = 0
        val gate = ApprovalGate(1000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) { cards++ }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        MemoryUiAutomationGuard.beginAsyncAction(MemoryUiAutomationGuard.captureAutomationEpoch()).use {
            assertTrue(runCatching { gate.request(ApprovalSummary("ASK", emptyList()), CancellationToken.uncancellable()) }.isFailure)
            assertEquals(0, cards); assertFalse(MemoryUiAutomationGuard.isProtected())
        }
    }
    @Test fun presenterFailureAlwaysClosesProtection() {
        val gate = ApprovalGate(1000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) { error("Presenter unavailable") }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        assertTrue(runCatching { gate.request(ApprovalSummary("ASK", emptyList()), CancellationToken.uncancellable()) }.isFailure)
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun cancellationRemainsIndependentOfHumanResolution() {
        val token = CancellationToken.cancellable()
        val gate = ApprovalGate(1000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) { token.cancel() }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        assertTrue(runCatching { gate.request(ApprovalSummary("ASK", emptyList()), token) }.isFailure)
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }
}
