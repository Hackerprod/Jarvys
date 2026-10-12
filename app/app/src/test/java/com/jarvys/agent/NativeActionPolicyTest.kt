package com.jarvys.agent

import com.jarvys.agent.connectors.ApprovalDecision
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.InMemoryConnectorAutonomyStore
import org.junit.Assert.*
import org.junit.Test

class NativeActionPolicyTest {
    private val operation = NativeActionPolicy.Operation.BOT_CREATE
    private val binding = NativeActionPolicy.binding("conversation", "request", "exact payload")
    private val summary = ApprovalSummary("Create exact bot", listOf("Exact instructions and capabilities"))

    @Test fun freshDefaultAllowsWithoutCallingApprovalAndAdmitsFreshEffects() {
        val policy = NativeActionPolicy(InMemoryConnectorAutonomyStore())
        policy.prepare(operation, binding).use { request ->
            request.authorize(summary, CancellationToken.uncancellable()) { _, _ -> error("No redundant Ask") }
            var checks = 0
            repeat(3) { request.admit { checks++ } }
            assertEquals(3, checks)
            assertFalse(request.requiresApproval)
        }
    }
    @Test fun askNeedsExactRequestApprovalAndDenyStopsBeforeValidation() {
        val policy = NativeActionPolicy.inMemory(AutonomyPolicy.ASK)
        policy.prepare(operation, binding).use { request ->
            assertTrue(request.requiresApproval)
            assertThrows(IllegalStateException::class.java) { request.admit() }
            var shown = 0
            request.authorize(summary, CancellationToken.uncancellable()) { actual, _ ->
                shown++; assertEquals(summary.lines, actual.lines); ApprovalDecision.APPROVED
            }
            request.admit(); assertEquals(1, shown)
        }
        policy.set(operation, AutonomyPolicy.DENY)
        assertThrows(IllegalStateException::class.java) { policy.prepare(operation, binding) }
    }
    @Test fun policyChangeRevokesApprovedAndDefaultRequestsEvenWhenRestoredToSameValue() {
        for (mode in listOf(AutonomyPolicy.ALLOW, AutonomyPolicy.ASK)) {
            val policy = NativeActionPolicy.inMemory(mode)
            val request = policy.prepare(operation, binding)
            request.approveOnce { }
            policy.set(operation, AutonomyPolicy.DENY)
            policy.set(operation, mode)
            assertThrows(IllegalStateException::class.java) { request.admit { fail("Stale admission") } }
        }
    }
    @Test fun changeDuringAskNeverAdmitsAndNoPayloadCanReuseAnotherBinding() {
        val policy = NativeActionPolicy.inMemory(AutonomyPolicy.ASK)
        val request = policy.prepare(operation, binding)
        assertThrows(IllegalStateException::class.java) {
            request.authorize(summary, CancellationToken.uncancellable()) { _, _ ->
                policy.set(operation, AutonomyPolicy.DENY); ApprovalDecision.APPROVED
            }
        }
        val fresh = NativeActionPolicy.inMemory().prepare(operation, binding)
        assertThrows(IllegalStateException::class.java) { fresh.requireBinding(NativeActionPolicy.binding("different")) }
        fresh.close()
        assertThrows(IllegalStateException::class.java) { fresh.admit() }
    }
    @Test fun finalValidationRunsOnlyForAllowedLiveRequestAndCancellationWins() {
        val request = NativeActionPolicy.inMemory().prepare(operation, binding)
        val token = CancellationToken.cancellable().apply { cancel() }
        assertThrows(java.util.concurrent.CancellationException::class.java) { request.admit { token.throwIfCancelled() } }
    }
    @Test fun parentAllowDoesNotApproveChildAskAndLinkRejectsDifferentPayload() {
        val policy = NativeActionPolicy.inMemory()
        policy.set(NativeActionPolicy.Operation.BOT_MASCOT, AutonomyPolicy.ASK)
        val parent = policy.prepare(operation, binding)
        val child = policy.prepare(NativeActionPolicy.Operation.BOT_MASCOT, NativeActionPolicy.binding("exact mascot"))
        val link = parent.linkMascot(child)
        assertThrows(IllegalStateException::class.java) { child.admit { link.admitParent(child) } }
        assertThrows(IllegalStateException::class.java) { link.requireBinding(NativeActionPolicy.binding("other mascot")) }
        child.approveOnce { }
        child.admit(CancellationToken.uncancellable()) { link.admitParent(child) }
        policy.set(operation, AutonomyPolicy.DENY)
        assertThrows(IllegalStateException::class.java) { child.admit { link.admitParent(child) } }
    }
    @Test fun rebindPreservesPolicyContinuityButNeverCarriesAskApprovalToNewArguments() {
        val policy = NativeActionPolicy.inMemory(AutonomyPolicy.ASK)
        val beforePicker = policy.prepare(operation, binding)
        beforePicker.approveOnce { }
        val selected = beforePicker.rebind(NativeActionPolicy.binding("selected URI"))
        assertTrue(selected.requiresApproval)
        assertThrows(IllegalStateException::class.java) { beforePicker.admit() }
        policy.set(operation, AutonomyPolicy.DENY)
        assertThrows(IllegalStateException::class.java) { selected.rebind(binding) }
    }
    @Test fun stoppedTokenNeverConsumesAdmissionOrRunsValidation() {
        val request = NativeActionPolicy.inMemory().prepare(operation, binding)
        val token = CancellationToken.cancellable().apply { cancel() }
        assertThrows(java.util.concurrent.CancellationException::class.java) { request.admit(token) { fail("STOP won") } }
        request.admit(CancellationToken.uncancellable())
    }
    @Test fun operationCatalogHasExactlyTheReviewedSemanticFamilies() {
        assertEquals(14, NativeActionPolicy.Operation.entries.size)
        assertEquals(14, NativeActionPolicy.Operation.entries.map { it.id }.distinct().size)
        assertFalse(NativeActionPolicy.Operation.entries.any { it.id.contains("crew") || it.id.contains("stop") })
    }
}
