package com.jarvys.agent.flavor

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CorePromptBudget
import com.jarvys.agent.coding.ProjectScope
import com.jarvys.agent.coding.ProjectScopeStore
import com.jarvys.agent.connectors.*
import com.jarvys.agent.linux.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
class CodingExecutionToolsTest {
    @get:Rule val folder = TemporaryFolder()
    private class Runtime : LinuxRuntime {
        var statusCalls = 0
        var ready = true
        override fun projectStatus(scope: ProjectScope, cwd: String, requiredTools: List<String>): LinuxProjectStatus {
            statusCalls++; return LinuxProjectStatus(ready, "Not prepared", tools = mapOf("git" to true), probe = "required_at_approved_launch")
        }
        override fun state() = LinuxInstallState(LinuxInstallPhase.READY)
        override fun availableBytes() = 1L
        override fun supportsArm64() = true
        override fun workspacePath() = "/workspace"
        override fun observe(observer: (LinuxInstallState) -> Unit) = AutoCloseable { }
        override fun installForProbe(progress: (LinuxInstallState) -> Unit, token: CancellationToken): LinuxInstallState = error("No install")
        override fun probe(): LinuxProbeResult = error("Status must not probe")
        override fun markProbeFailure(code: String, detail: String) = Unit
        override fun markProbeSuccess() = Unit
        override fun uninstall() = error("No uninstall")
        override fun deleteWorkspace() = error("No delete")
        override fun exec(command: String, cwd: String, timeoutMillis: Long?, callback: LinuxOutputCallback, token: CancellationToken): LinuxExecResult = error("No principal command")
    }
    private class Fixture(val files: java.io.File) {
        val scope = ProjectScopeStore(files).open("chat")
        val runtime = Runtime()
        val store = InMemoryConnectorAutonomyStore()
        val autonomy = LinuxExecAutonomy(store, { true })
        var executed = 0
        var timeout: Long? = null
        var budget = CorePromptBudget.standard()
        var requests = 0
        var capability = true
        var decision = ApprovalDecision.APPROVED
        var duringApproval: () -> Unit = { }
        lateinit var gate: ApprovalGate
        val jobs = CodingJobManager(files, CodingJobManager.Backend { _, _, _, limit, _, output, launch ->
            launch(); executed++; timeout = limit; output.onChunk(LinuxOutputStream.STDOUT, "verified\n"); LinuxExecResult(0)
        }, executor = Executor { it.run() })
        init {
            gate = ApprovalGate(presenter = object : ApprovalPresenter {
                override fun show(id: String, summary: ApprovalSummary) {
                    requests++; assertFalse(summary.allowAlwaysAvailable); duringApproval(); gate.resolve(id, decision)
                }
                override fun update(id: String, decision: ApprovalDecision) = Unit
            })
        }
        val tools get() = CodingExecutionTools(scope, jobs, runtime, gate, autonomy, LinuxOutputSanitizer(emptyList(), emptyList()),
            budget, "Bot", "coding", { "chat/bot/1" }, { _, _ -> capability }).create(CodingExecutionTools.NAMES)
            .associateBy { it.declaration().name }
        fun execute(extra: Map<String, Any> = emptyMap()) = tools.getValue(CodingExecutionTools.EXEC).execute(
            mapOf("command" to "test", "expected_scope_version" to scope.version()) + extra, CancellationToken.cancellable())
    }
    @Test fun statusIsInspectionOnlyAndDisclosesAskEvenForPrincipalAllow() {
        val f = Fixture(folder.newFolder()); f.autonomy.setPolicyFromUi(AutonomyPolicy.ALLOW)
        val result = f.tools.getValue(CodingExecutionTools.STATUS).execute(emptyMap(), CancellationToken.cancellable())
        assertTrue(result.content, result.success); assertEquals("ask", JSONObject(result.content).getString("policy"))
        assertEquals(0, f.executed); assertEquals(0, f.requests)
    }
    @Test fun principalAllowDoesNotBypassProjectApprovalAndDenyBlocksBeforePrompt() {
        val f = Fixture(folder.newFolder()); f.autonomy.setPolicyFromUi(AutonomyPolicy.ALLOW)
        assertTrue(f.execute().success); assertEquals(1, f.requests); assertEquals(1, f.executed)
        f.autonomy.setPolicyFromUi(AutonomyPolicy.DENY)
        assertFalse(f.execute().success); assertEquals(1, f.requests); assertEquals(1, f.executed)
    }
    @Test fun denialAndPolicyRevisionChangeDoNotLaunch() {
        val f = Fixture(folder.newFolder()); f.decision = ApprovalDecision.DENIED
        assertFalse(f.execute().success); assertEquals(0, f.executed)
        f.decision = ApprovalDecision.APPROVED
        f.duringApproval = { f.autonomy.setPolicyFromUi(AutonomyPolicy.ASK) }
        assertFalse(f.execute().success); assertEquals(0, f.executed)
    }
    @Test fun revokedCapabilityMissingToolsAndInteractiveArgumentsFailClosed() {
        val f = Fixture(folder.newFolder())
        assertFalse(f.execute(mapOf("required_tools" to listOf("missing"))).success)
        assertFalse(f.execute(mapOf("stdin" to "yes")).success)
        assertFalse(f.execute(mapOf("timeout_seconds" to 0)).success)
        assertEquals(0, f.requests)
        f.duringApproval = { f.capability = false }
        assertFalse(f.execute().success); assertEquals(0, f.executed)
    }
    @Test fun oldScopeVersionInvalidatesApprovalAndToolsCannotDelegate() {
        val f = Fixture(folder.newFolder())
        assertTrue(f.tools.values.none { it.canDelegate() })
        f.duringApproval = { f.scope.acquireWriter("other", f.scope.version()).use { it.markChanged() } }
        assertFalse(f.execute().success); assertEquals(0, f.executed)
    }
    @Test fun executionTimeoutIsFiniteByDefaultAndStrictlyBoundedBeforeApproval() {
        val f = Fixture(folder.newFolder())
        assertTrue(f.execute().success)
        assertEquals(900_000L, f.timeout)
        assertTrue(f.execute(mapOf("timeout_seconds" to 3600)).success)
        assertEquals(3_600_000L, f.timeout)
        assertFalse(f.execute(mapOf("timeout_seconds" to 3601)).success)
        assertFalse(f.execute(mapOf("timeout_seconds" to 1.5)).success)
        assertFalse(f.execute(mapOf("timeout_seconds" to Long.MAX_VALUE)).success)
        assertEquals(2, f.requests)
        assertEquals(2, f.executed)
    }

    @Test fun longCommandPreviewCannotConsumeTheBudgetNeededForJobLogsAndLifecycle() {
        val f = Fixture(folder.newFolder())
        f.budget = CorePromptBudget(4096, 256, 1, 256, 2048)
        val result = f.execute(mapOf("command" to "echo fixture\n".repeat(1000)))
        assertTrue(result.content, result.success)
        val receipt = JSONObject(result.content)
        assertTrue(receipt.getBoolean("command_truncated"))
        assertEquals(128, receipt.getString("command").length)
        assertEquals(13_000, receipt.getInt("command_chars"))
        assertTrue(result.content.length < 2048)
        val token = CancellationToken.cancellable()
        val jobs = f.tools.getValue(CodingExecutionTools.JOBS)
        val list = jobs.execute(mapOf("action" to "list"), token)
        assertTrue(list.content, list.success)
        assertEquals(1, JSONObject(list.content).getJSONArray("jobs").length())
        for (action in listOf("read", "wait")) {
            val page = jobs.execute(mapOf("action" to action, "job_id" to receipt.getString("job_id"),
                "wait_seconds" to 1), token)
            assertTrue(page.content, page.success)
            assertEquals("[stdout] verified\n", JSONObject(page.content).getString("output"))
            assertTrue(page.content.length <= 2048)
        }
        assertEquals(1, f.executed)
    }

}
