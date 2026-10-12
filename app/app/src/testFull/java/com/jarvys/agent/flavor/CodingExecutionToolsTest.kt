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
    private class Fixture(val files: java.io.File, policy: AutonomyPolicy? = null, executor: Executor = Executor { it.run() }) {
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
        var useExactAllowCallback = false
        var beforeLaunch: () -> Unit = { }
        lateinit var gate: ApprovalGate
        val jobs = CodingJobManager(files, CodingJobManager.Backend { _, _, _, limit, _, output, launch ->
            beforeLaunch(); launch(); executed++; timeout = limit; output.onChunk(LinuxOutputStream.STDOUT, "verified\n"); LinuxExecResult(0)
        }, executor = executor)
        init {
            policy?.let { assertNull(autonomy.setProjectPolicyFromUi(it)) }
            gate = ApprovalGate(presenter = object : ApprovalPresenter {
                override fun show(id: String, summary: ApprovalSummary) {
                    requests++
                    assertTrue(summary.allowAlwaysAvailable)
                    assertEquals(LinuxExecAutonomy.PROJECT_CONNECTOR_ID, summary.autonomyConnectorId)
                    assertEquals(LinuxExecAutonomy.PROJECT_OPERATION_NAME, summary.autonomyOperationName)
                    duringApproval()
                    if (useExactAllowCallback) gate.enableAlwaysFromUi(id)
                    gate.resolve(id, decision)
                }
                override fun update(id: String, decision: ApprovalDecision) = Unit
            })
        }
        val tools get() = CodingExecutionTools(scope, jobs, runtime, gate, autonomy, LinuxOutputSanitizer(emptyList(), emptyList()),
            budget, "Bot", "coding", { "chat/bot/1" }, { _, _ -> capability }).create(CodingExecutionTools.NAMES)
            .associateBy { it.declaration().name }
        fun execute(extra: Map<String, Any> = emptyMap(), token: CancellationToken = CancellationToken.cancellable()) =
            tools.getValue(CodingExecutionTools.EXEC).execute(
                mapOf("command" to "test", "expected_scope_version" to scope.version()) + extra, token)
    }
    @Test fun explicitCodingAllowIgnoresUnrelatedPrincipalRevocationAtLaunch() {
        val f = Fixture(folder.newFolder(), AutonomyPolicy.ALLOW)
        f.beforeLaunch = { f.autonomy.setPolicyFromUi(AutonomyPolicy.DENY) }
        assertTrue(f.execute().success); assertEquals(1, f.executed)
        assertEquals(AutonomyPolicy.ALLOW, f.autonomy.projectPolicy())
    }

    @Test fun crossInstanceCodingPolicyAbaAtLaunchInvalidatesExactSnapshot() {
        val f = Fixture(folder.newFolder(), AutonomyPolicy.ALLOW)
        f.beforeLaunch = {
            val other = LinuxExecAutonomy(f.store, { true })
            other.setProjectPolicyFromUi(AutonomyPolicy.DENY)
            other.setProjectPolicyFromUi(AutonomyPolicy.ALLOW)
        }
        val result = f.execute()
        assertEquals(0, f.executed)
        if (result.success) assertEquals("FAILED", JSONObject(result.content).getString("state"))
    }

    @Test fun statusIsInspectionOnlyAndDisclosesTheEffectiveCodingPolicy() {
        val f = Fixture(folder.newFolder())
        for (policy in listOf(AutonomyPolicy.ALLOW, AutonomyPolicy.ASK, AutonomyPolicy.DENY)) {
            if (policy != AutonomyPolicy.ALLOW) f.autonomy.setProjectPolicyFromUi(policy)
            val result = f.tools.getValue(CodingExecutionTools.STATUS).execute(emptyMap(), CancellationToken.cancellable())
            assertTrue(result.content, result.success)
            assertEquals(policy.name.lowercase(java.util.Locale.ROOT), JSONObject(result.content).getString("policy"))
        }
        assertEquals(0, f.executed); assertEquals(0, f.requests)
    }
    @Test fun absentPolicyAndExplicitAllowRunWithoutCardsAndKeepDurableReceipts() {
        val f = Fixture(folder.newFolder())
        repeat(2) {
            val result = f.execute()
            assertTrue(result.content, result.success)
            assertEquals("SUCCEEDED", JSONObject(result.content).getString("state"))
        }
        f.autonomy.setProjectPolicyFromUi(AutonomyPolicy.ALLOW)
        assertTrue(f.execute().success)
        assertEquals(0, f.requests); assertEquals(3, f.executed)
        assertEquals(3, f.jobs.list("chat/bot/1", f.scope).size)
        assertEquals(AutonomyPolicy.ALLOW, f.autonomy.policy())
    }
    @Test fun existingLinuxAllowAndDenyRemainEffectiveWithoutCodingOverride() {
        val f = Fixture(folder.newFolder()); f.autonomy.setPolicyFromUi(AutonomyPolicy.ALLOW)
        assertTrue(f.execute().success); assertEquals(0, f.requests); assertEquals(1, f.executed)
        f.autonomy.setPolicyFromUi(AutonomyPolicy.DENY)
        assertFalse(f.execute().success); assertEquals(0, f.requests); assertEquals(1, f.executed)
    }
    @Test fun savedLegacyAskStillAsksAndExplicitCodingDenyBlocksEvenPrincipalAllow() {
        val f = Fixture(folder.newFolder()); f.autonomy.setPolicyFromUi(AutonomyPolicy.ASK)
        assertTrue(f.execute().success); assertEquals(1, f.requests)
        f.autonomy.setPolicyFromUi(AutonomyPolicy.ALLOW)
        f.autonomy.setProjectPolicyFromUi(AutonomyPolicy.DENY)
        assertFalse(f.execute().success); assertEquals(1, f.requests); assertEquals(1, f.executed)
    }
    @Test fun denialAndPolicyRevisionChangeDoNotLaunch() {
        val f = Fixture(folder.newFolder(), AutonomyPolicy.ASK); f.decision = ApprovalDecision.DENIED
        assertFalse(f.execute().success); assertEquals(0, f.executed)
        f.decision = ApprovalDecision.APPROVED
        f.duringApproval = { f.autonomy.setProjectPolicyFromUi(AutonomyPolicy.ASK) }
        assertFalse(f.execute().success); assertEquals(0, f.executed)
    }
    @Test fun allowAlwaysApprovesThisCommandAndPersistsOnlyCodingPermission() {
        val f = Fixture(folder.newFolder(), AutonomyPolicy.ASK)
        f.useExactAllowCallback = true
        f.decision = ApprovalDecision.APPROVED_ALLOW_ALWAYS
        assertTrue(f.execute().success); assertTrue(f.execute().success)
        assertEquals(1, f.requests); assertEquals(2, f.executed)
        val recreated = LinuxExecAutonomy(f.store, { true })
        assertEquals(AutonomyPolicy.ALLOW, recreated.projectPolicy())
        assertEquals(AutonomyPolicy.ALLOW, recreated.policy())
    }
    @Test fun allowAlwaysCannotHideRevocationOrPretendTheChoiceWasSaved() {
        val f = Fixture(folder.newFolder(), AutonomyPolicy.ASK)
        f.decision = ApprovalDecision.APPROVED_ALLOW_ALWAYS
        assertFalse(f.execute().success)
        f.duringApproval = {
            f.autonomy.setProjectPolicyFromUi(AutonomyPolicy.DENY)
            f.autonomy.setProjectPolicyFromUi(AutonomyPolicy.ALLOW)
        }
        assertFalse(f.execute().success); assertEquals(0, f.executed)
    }
    @Test fun failedAlwaysPersistenceStillAllowsOnlyTheApprovedCommand() {
        val f = Fixture(folder.newFolder(), AutonomyPolicy.ASK)
        f.decision = ApprovalDecision.APPROVED_ALLOW_FAILED
        assertTrue(f.execute().success); assertTrue(f.execute().success)
        assertEquals(2, f.requests); assertEquals(2, f.executed)
        assertEquals(AutonomyPolicy.ASK, f.autonomy.projectPolicy())
    }
    @Test fun revokedCapabilityMissingToolsAndInteractiveArgumentsFailClosed() {
        val f = Fixture(folder.newFolder())
        assertFalse(f.execute(mapOf("required_tools" to listOf("missing"))).success)
        assertFalse(f.execute(mapOf("stdin" to "yes")).success)
        assertFalse(f.execute(mapOf("timeout_seconds" to 0)).success)
        assertEquals(0, f.requests)
        f.autonomy.setProjectPolicyFromUi(AutonomyPolicy.ASK)
        f.duringApproval = { f.capability = false }
        assertFalse(f.execute().success); assertEquals(0, f.executed)
    }
    @Test fun oldScopeVersionInvalidatesApprovalAndToolsCannotDelegate() {
        val f = Fixture(folder.newFolder(), AutonomyPolicy.ASK)
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
        assertEquals(0, f.requests)
        assertEquals(2, f.executed)
    }

    @Test fun allowStillRejectsUnpreparedRuntimeStaleScopeAndOccupiedWriterLease() {
        val f = Fixture(folder.newFolder())
        f.runtime.ready = false
        assertFalse(f.execute().success)
        f.runtime.ready = true
        val previous = f.scope.version()
        f.scope.acquireWriter("other", previous).use { it.markChanged() }
        assertFalse(f.execute(mapOf("expected_scope_version" to previous)).success)
        f.scope.acquireWriter("other", f.scope.version()).use { assertFalse(f.execute().success) }
        assertEquals(0, f.executed); assertEquals(0, f.requests)
    }

    @Test fun queuedAllowRevalidatesPolicyAndReleasesWriterLeaseWithoutLaunching() {
        for (revoked in listOf(AutonomyPolicy.ASK, AutonomyPolicy.DENY)) {
            val pending = mutableListOf<Runnable>()
            val f = Fixture(folder.newFolder(), executor = Executor { pending += it })
            val result = f.execute()
            assertTrue(result.content, result.success)
            assertEquals("LAUNCH_INTENT", JSONObject(result.content).getString("state"))
            assertThrows(ProjectScope.ConflictException::class.java) { f.scope.acquireWriter("other", f.scope.version()) }
            f.autonomy.setProjectPolicyFromUi(revoked)
            pending.single().run()
            val completed = f.jobs.list("chat/bot/1", f.scope).single()
            assertEquals(CodingJobManager.State.NOT_STARTED, completed.state)
            f.scope.acquireWriter("other", f.scope.version()).use { it.validate() }
            assertEquals(0, f.executed); assertEquals(0, f.requests)
        }
    }

    @Test fun allowStillHonorsStopAndLateCapabilityRevocation() {
        val pending = mutableListOf<Runnable>()
        val f = Fixture(folder.newFolder(), executor = Executor { pending += it })
        val token = CancellationToken.cancellable()
        assertTrue(f.execute(token = token).success)
        token.cancel(); pending.single().run()
        assertEquals(CodingJobManager.State.CANCELLED, f.jobs.list("chat/bot/1", f.scope).single().state)
        assertEquals(0, f.executed)
        val g = Fixture(folder.newFolder())
        g.beforeLaunch = { g.capability = false }
        val result = g.execute()
        assertTrue(result.content, result.success) // The receipt records a job that never started.
        assertEquals("NOT_STARTED", JSONObject(result.content).getString("state"))
        assertEquals(0, g.executed); assertEquals(0, g.requests)
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
