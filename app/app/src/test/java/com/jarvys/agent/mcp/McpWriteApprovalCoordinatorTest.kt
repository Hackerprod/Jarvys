package com.jarvys.agent.mcp

import com.jarvys.agent.CancellationToken
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
        override fun post(record: AutonomyAuditRecord) { posted.incrementAndGet() }
    }

    private fun writeTool(name: String = "create_issue", destructive: Boolean = false) = McpToolDefinition(
        serverId = "catalog_github",
        serverAlias = "GitHub",
        wireName = name,
        modelName = "mcp_github_$name",
        description = "remote description",
        inputSchema = JSONObject(),
        catalogServiceId = "github",
        annotations = McpToolAnnotations(readOnlyHint = false, destructiveHint = destructive, title = "Create item"),
        access = McpToolAccess.WRITE,
    )

    @Test fun askShowsBoundedSummaryAndDeniedWriteNeverCallsRemoteTool() {
        val store = InMemoryConnectorAutonomyStore()
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

    @Test fun denyStopsBeforeCallingAndDestructiveToolsCannotUseAllow() {
        val store = InMemoryConnectorAutonomyStore()
        val presenter = Presenter()
        val gate = ApprovalGate(2_000, presenter)
        val coordinator = McpWriteApprovalCoordinator(store, gate, Notifier())
        val denied = writeTool("update_issue")
        coordinator.setPolicy(denied, AutonomyPolicy.DENY)
        var invoked = false
        runCatching { coordinator.execute(denied, JSONObject(), CancellationToken.uncancellable()) {
            invoked = true; JSONObject()
        } }
        assertTrue(!invoked)
        val destructive = writeTool("delete_issue", destructive = true)
        assertTrue(runCatching { coordinator.setPolicy(destructive, AutonomyPolicy.ALLOW) }.isFailure)
        // Even a legacy/stale ALLOW setting cannot bypass Ask for a destructive action.
        store.setPolicy("mcp:catalog_github", destructive.wireName, AutonomyPolicy.ALLOW)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit {
                coordinator.execute(destructive, JSONObject(), CancellationToken.uncancellable()) {
                    invoked = true; JSONObject()
                }
            }
            val (requestId, summary) = presenter.shown.poll(2, TimeUnit.SECONDS) ?: error("Destructive write did not ask")
            assertTrue(summary.lines.any { it.contains("High-impact") })
            gate.resolve(requestId, ApprovalDecision.DENIED)
            try { future.get(2, TimeUnit.SECONDS); error("Denied destructive write unexpectedly ran") }
            catch (deniedWrite: ExecutionException) { assertTrue(deniedWrite.cause is McpWriteDeniedException) }
            assertTrue(!invoked)
        } finally { executor.shutdownNow() }
    }

    @Test fun deletingRemoteServerClearsItsPersistedWriteGrants() {
        val store = InMemoryConnectorAutonomyStore()
        val coordinator = McpWriteApprovalCoordinator(store, ApprovalGate(2_000, Presenter()), Notifier())
        val tool = writeTool("update_issue")
        coordinator.setPolicy(tool, AutonomyPolicy.ALLOW)
        assertEquals(AutonomyPolicy.ALLOW, coordinator.policy(tool))

        coordinator.forgetServer(tool.serverId)

        assertEquals(AutonomyPolicy.ASK, coordinator.policy(tool))
    }
}
