package com.jarvys.agent.mcp

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.EffectInvocation
import com.jarvys.agent.connectors.ApprovalDecision
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ApprovalPresenter
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.connectors.AutonomyActionNotifier
import com.jarvys.agent.connectors.AutonomyAuditRecord
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.InMemoryConnectorAutonomyStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpWriteApprovalCoordinatorTest {
    private class Presenter : ApprovalPresenter {
        val shown = LinkedBlockingQueue<Pair<String, ApprovalSummary>>()
        override fun show(id: String, summary: ApprovalSummary) { shown.put(id to summary) }
        override fun update(id: String, decision: ApprovalDecision) = Unit
    }

    private class Notifier(private val enabled: Boolean = true) : AutonomyActionNotifier {
        val posted = AtomicInteger()
        override fun canPost() = enabled
        override fun missingRuntimePermission(): String? = null
        override fun post(record: AutonomyAuditRecord) { check(enabled); posted.incrementAndGet() }
    }

    private fun writeTool(name: String = "create_issue", destructive: Boolean = false) = McpToolDefinition(
        serverId = "catalog_github",
        serverAlias = "GitHub",
        wireName = name,
        modelName = "mcp_github_$name",
        description = "remote description",
        inputSchema = JSONObject(),
        catalogServiceId = null, // These cases exercise generic MCP policy; GitHub has repository-bound tests.
        annotations = McpToolAnnotations(readOnlyHint = false, destructiveHint = destructive, title = "Create item"),
        access = McpToolAccess.WRITE,
    )

    @Test fun preflightDoesNotRecordWriteAndDurableAdmissionPrecedesMutation() {
        val store = InMemoryConnectorAutonomyStore()
        val coordinator = McpWriteApprovalCoordinator(store, ApprovalGate(2_000, Presenter()), Notifier())
        val tool = writeTool()
        coordinator.setPolicy(tool, AutonomyPolicy.ALLOW)
        val events = mutableListOf<String>()
        val invocation = EffectInvocation { decision ->
            assertEquals(EffectInvocation.Authorization.ALLOW, decision.authorization)
            assertEquals("mcp:catalog_github", decision.authority)
            events += "durable"
        }
        coordinator.executeWithAdmission(tool, JSONObject(), CancellationToken.uncancellable(), null, null, invocation) { admission ->
            admission.admit(mutation = false) { events += "read validation" }
            assertFalse(invocation.admitted())
            events += "read"
            admission.admit(mutation = true) { events += "write validation" }
            assertTrue(invocation.admitted())
            events += "effect"
            admission.admit(mutation = true)
            JSONObject()
        }
        assertEquals(listOf("read validation", "read", "write validation", "durable", "effect"), events)
    }

    @Test fun admissionPersistenceFailureBlocksDispatchAndCrossThreadContextIsExplicit() {
        val store = InMemoryConnectorAutonomyStore()
        val coordinator = McpWriteApprovalCoordinator(store, ApprovalGate(2_000, Presenter()), Notifier())
        val calls = AtomicInteger()
        val writerAttempts = AtomicInteger()
        coordinator.setPolicy(writeTool(), AutonomyPolicy.ALLOW)
        val invocation = EffectInvocation { writerAttempts.incrementAndGet(); throw IllegalStateException("simulated durable write failure") }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Unit> {
                coordinator.executeWithAdmission(writeTool(), JSONObject(), CancellationToken.uncancellable(), null, null, invocation) { admission ->
                    admission.admit()
                    calls.incrementAndGet()
                    JSONObject()
                }
            }
            assertThrows(ExecutionException::class.java) { future.get(2, TimeUnit.SECONDS) }
            assertEquals(0, calls.get())
            assertEquals(1, writerAttempts.get())
            assertFalse(invocation.admitted())
        } finally { executor.shutdownNow() }
    }

    @Test fun scheduledAskPendingAndApprovedUseTheActualSnapshot() {
        val store = InMemoryConnectorAutonomyStore()
        val coordinator = McpWriteApprovalCoordinator(store, ApprovalGate(2_000, Presenter()), Notifier())
        val tool = writeTool()
        coordinator.setPolicy(tool, AutonomyPolicy.ASK)
        val pending = EffectInvocation { error("Unapproved task must never admit") }
        coordinator.executeForScheduledTask(tool, JSONObject(), CancellationToken.uncancellable(), false,
            java.util.function.Supplier { JSONObject().put("queued", true) }, pending) { error("No dispatch") }
        assertEquals(EffectInvocation.Authorization.ASK_PENDING, pending.decision().authorization)
        assertFalse(pending.admitted())
        val approved = EffectInvocation { decision -> assertEquals(EffectInvocation.Authorization.ASK_APPROVED, decision.authorization) }
        coordinator.executeForScheduledTask(tool, JSONObject(), CancellationToken.uncancellable(), true, null, approved) {
            it.admit()
            JSONObject()
        }
        assertTrue(approved.admitted())
    }

    @Test fun explicitUserRejectionIsNotLeftAsPendingApproval() {
        val store = InMemoryConnectorAutonomyStore()
        val presenter = Presenter()
        val gate = ApprovalGate(2_000, presenter)
        val coordinator = McpWriteApprovalCoordinator(store, gate, Notifier())
        val tool = writeTool()
        coordinator.setPolicy(tool, AutonomyPolicy.ASK)
        val invocation = EffectInvocation { error("Rejected action cannot dispatch") }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<JSONObject> {
                coordinator.executeWithAdmission(tool, JSONObject(), CancellationToken.uncancellable(), null, null, invocation) {
                    error("Rejected action cannot reach its transport")
                }
            }
            val (requestId, _) = presenter.shown.poll(2, TimeUnit.SECONDS) ?: error("Approval was not shown")
            gate.resolve(requestId, ApprovalDecision.DENIED)
            assertThrows(ExecutionException::class.java) { pending.get(2, TimeUnit.SECONDS) }
            assertFalse(invocation.admitted())
            assertEquals(EffectInvocation.Authorization.DENY, invocation.decision().authorization)
            assertTrue(invocation.decision().origin.endsWith(":user_denied"))
            assertEquals(AutonomyPolicy.ASK, coordinator.policy(tool))
        } finally { executor.shutdownNow() }
    }

    @Test fun askShowsBoundedSummaryAndDeniedWriteNeverCallsRemoteTool() {
        val store = InMemoryConnectorAutonomyStore()
        store.setPolicy("mcp:catalog_github", "create_issue", AutonomyPolicy.ASK)
        val presenter = Presenter()
        val gate = ApprovalGate(2_000, presenter)
        val coordinator = McpWriteApprovalCoordinator(store, gate, Notifier())
        val calls = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<String> {
                coordinator.execute(writeTool(), JSONObject().put("owner", "team").put("password", "must-not-show"),
                    CancellationToken.uncancellable()) {
                    calls.incrementAndGet(); JSONObject().put("ok", true)
                }.toString()
            }
            val (requestId, summary) = presenter.shown.poll(2, TimeUnit.SECONDS) ?: error("Approval was not shown")
            assertTrue(summary.title.contains("GitHub"))
            assertTrue(summary.lines.any { it.contains("owner: team") })
            assertTrue(summary.lines.any { it.contains("[redacted]") })
            gate.resolve(requestId, ApprovalDecision.DENIED)
            try { future.get(2, TimeUnit.SECONDS); error("Denied write unexpectedly ran") }
            catch (denied: ExecutionException) { assertTrue(denied.cause is McpWriteDeniedException) }
            assertEquals(0, calls.get())
            assertEquals(AutonomyPolicy.ASK, coordinator.policy(writeTool()))
        } finally { executor.shutdownNow() }
    }

    @Test fun approvedAskWriteRunsExactlyOnce() {
        val store = InMemoryConnectorAutonomyStore()
        store.setPolicy("mcp:catalog_github", "create_issue", AutonomyPolicy.ASK)
        val presenter = Presenter()
        val gate = ApprovalGate(2_000, presenter)
        val coordinator = McpWriteApprovalCoordinator(store, gate, Notifier())
        val calls = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<String> {
                coordinator.execute(writeTool(), JSONObject().put("owner", "team"), CancellationToken.uncancellable()) {
                    calls.incrementAndGet(); JSONObject().put("result", "done")
                }.getString("result")
            }
            val (requestId, _) = presenter.shown.poll(2, TimeUnit.SECONDS) ?: error("Approval was not shown")
            gate.resolve(requestId, ApprovalDecision.APPROVED)
            assertEquals("done", future.get(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally { executor.shutdownNow() }
    }

    @Test fun allowUsesExistingAuditForOneHundredActionsWithoutCounterFallback() {
        val store = InMemoryConnectorAutonomyStore()
        val presenter = Presenter()
        val gate = ApprovalGate(2_000, presenter)
        val notifier = Notifier()
        val coordinator = McpWriteApprovalCoordinator(store, gate, notifier, clock = { 1_000L })
        val tool = writeTool()
        coordinator.setPolicy(tool, AutonomyPolicy.ALLOW)
        val calls = AtomicInteger()
        val token = CancellationToken.uncancellable()
        repeat(100) { coordinator.execute(tool, JSONObject(), token) { calls.incrementAndGet(); JSONObject() } }
        assertEquals(100, calls.get())
        assertEquals(100, notifier.posted.get())
        assertEquals(100, store.recentAudit("mcp:catalog_github", 100).size)
        assertEquals(null, presenter.shown.poll())
    }

    @Test fun denyBlocksBothReadsAndWritesButEveryActionCanUseAllow() {
        val store = InMemoryConnectorAutonomyStore()
        val presenter = Presenter()
        val coordinator = McpWriteApprovalCoordinator(store, ApprovalGate(2_000, presenter), Notifier(false))
        var calls = 0
        for (tool in listOf(writeTool("delete_issue", destructive = true),
            writeTool("get_issue").copy(access = McpToolAccess.READ),
            writeTool().copy(catalogServiceId = "github"))) {
            coordinator.setPolicy(tool, AutonomyPolicy.DENY)
            assertThrows(McpWriteDeniedException::class.java) {
                coordinator.execute(tool, JSONObject(), CancellationToken.uncancellable()) { calls++; JSONObject() }
            }
            coordinator.setPolicy(tool, AutonomyPolicy.ALLOW)
            val response = coordinator.execute(tool, JSONObject(), CancellationToken.uncancellable()) { calls++; JSONObject() }
            if (tool.access == McpToolAccess.WRITE) assertTrue(response.has("notificationWarning"))
        }
        assertEquals(3, calls)
        assertTrue(presenter.shown.isEmpty())
    }

    @Test fun absentPolicyDefaultsToAllowIncludingReadsAndDoesNotPersistARow() {
        val store = InMemoryConnectorAutonomyStore()
        val presenter = Presenter()
        val coordinator = McpWriteApprovalCoordinator(store, ApprovalGate(2_000, presenter), Notifier(false))
        val tool = writeTool().copy(access = McpToolAccess.READ)
        assertEquals(AutonomyPolicy.ALLOW, coordinator.policy(tool))
        coordinator.execute(tool, JSONObject(), CancellationToken.uncancellable()) { JSONObject() }
        assertEquals(null, store.savedPolicy("mcp:${tool.serverId}", tool.wireName))
        assertTrue(presenter.shown.isEmpty())
    }

    @Test fun serverFallbackNeverOverwritesAnExistingOrReturningToolOverride() {
        val store = InMemoryConnectorAutonomyStore()
        val coordinator = McpWriteApprovalCoordinator(store, ApprovalGate(2_000, Presenter()), Notifier())
        val tool = writeTool().copy(inheritedPolicy = AutonomyPolicy.ASK)
        assertEquals(AutonomyPolicy.ASK, coordinator.policy(tool))
        assertEquals(null, store.savedPolicy("mcp:${tool.serverId}", tool.wireName))
        coordinator.setPolicy(tool, AutonomyPolicy.DENY)
        assertEquals(AutonomyPolicy.DENY, coordinator.policy(tool.copy(inheritedPolicy = AutonomyPolicy.ALLOW)))
        coordinator.setPolicy(tool, AutonomyPolicy.ASK)
        assertEquals(AutonomyPolicy.ASK, coordinator.policy(tool.copy(inheritedPolicy = AutonomyPolicy.DENY)))
    }

    @Test fun allowToAskAndDenyAllowAbaInvalidateUnadmittedRequest() {
        for (aba in listOf(false, true)) {
            val store = InMemoryConnectorAutonomyStore()
            val coordinator = McpWriteApprovalCoordinator(store, ApprovalGate(2_000, Presenter()), Notifier())
            val tool = writeTool()
            var calls = 0
            assertTrue(runCatching {
                coordinator.executeWithAdmission(tool, JSONObject(), CancellationToken.uncancellable(), null, null) { admission ->
                    coordinator.setPolicy(tool, if (aba) AutonomyPolicy.DENY else AutonomyPolicy.ASK)
                    if (aba) coordinator.setPolicy(tool, AutonomyPolicy.ALLOW)
                    admission.admit()
                    calls++; JSONObject()
                }
            }.isFailure)
            assertEquals(0, calls)
        }
    }

    @Test fun askReadBindsApprovalToOriginalPolicyRevision() {
        val store = InMemoryConnectorAutonomyStore()
        val presenter = Presenter()
        val gate = ApprovalGate(2_000, presenter)
        val coordinator = McpWriteApprovalCoordinator(store, gate, Notifier())
        val tool = writeTool("read_issue").copy(access = McpToolAccess.READ)
        coordinator.setPolicy(tool, AutonomyPolicy.ASK)
        val executor = Executors.newSingleThreadExecutor()
        val calls = AtomicInteger()
        try {
            val future = executor.submit {
                coordinator.execute(tool, JSONObject(), CancellationToken.uncancellable()) { calls.incrementAndGet(); JSONObject() }
            }
            val (id, summary) = presenter.shown.poll(2, TimeUnit.SECONDS) ?: error("Read approval missing")
            assertTrue(summary.compactSummary?.fallback?.contains("read") == true)
            coordinator.setPolicy(tool, AutonomyPolicy.DENY)
            coordinator.setPolicy(tool, AutonomyPolicy.ASK)
            gate.resolve(id, ApprovalDecision.APPROVED)
            assertThrows(ExecutionException::class.java) { future.get(2, TimeUnit.SECONDS) }
            assertEquals(0, calls.get())
        } finally { executor.shutdownNow() }
    }

    @Test fun everyDispatchRechecksPolicyAndStopWithoutHoldingLocksAcrossEffect() {
        val store = InMemoryConnectorAutonomyStore()
        val coordinator = McpWriteApprovalCoordinator(store, ApprovalGate(2_000, Presenter()), Notifier())
        val tool = writeTool()
        val token = CancellationToken.cancellable()
        var calls = 0
        assertTrue(runCatching {
            coordinator.executeWithAdmission(tool, JSONObject(), token, null, null) { admission ->
                admission.admit(); calls++
                admission.admit(); calls++ // A verified retry/subrequest gets a fresh, single-use admission.
                coordinator.setPolicy(tool, AutonomyPolicy.DENY)
                admission.admit(); calls++; JSONObject()
            }
        }.isFailure)
        assertEquals(2, calls)
        coordinator.setPolicy(tool, AutonomyPolicy.ALLOW)
        assertTrue(runCatching {
            coordinator.executeWithAdmission(tool, JSONObject(), token, null, null) { admission ->
                token.cancel()
                admission.admit(); calls++; JSONObject()
            }
        }.isFailure)
        assertEquals(2, calls)
    }

    @Test fun deletingRemoteServerClearsItsPersistedWriteGrants() {
        val store = InMemoryConnectorAutonomyStore()
        val coordinator = McpWriteApprovalCoordinator(store, ApprovalGate(2_000, Presenter()), Notifier())
        val tool = writeTool("update_issue")
        coordinator.setPolicy(tool, AutonomyPolicy.ALLOW)
        assertEquals(AutonomyPolicy.ALLOW, coordinator.policy(tool))

        coordinator.forgetServer(tool.serverId)

        assertEquals(AutonomyPolicy.ALLOW, coordinator.policy(tool))
    }
}
