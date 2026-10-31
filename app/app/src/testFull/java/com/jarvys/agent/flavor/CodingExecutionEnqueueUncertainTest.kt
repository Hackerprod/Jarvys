package com.jarvys.agent.flavor

import com.jarvys.agent.ChatTestStore
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CorePromptBudget
import com.jarvys.agent.coding.ProjectScope
import com.jarvys.agent.coding.ProjectScopeStore
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.InMemoryConnectorAutonomyStore
import com.jarvys.agent.linux.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodingExecutionEnqueueUncertainTest {
    @get:Rule val chat = ChatTestStore(true)
    private class LocalRuntime : LinuxRuntime {
        override fun projectStatus(scope: ProjectScope, cwd: String, requiredTools: List<String>) =
            LinuxProjectStatus(true, "synthetic ready", tools = mapOf("git" to true), probe = "required_at_approved_launch")
        override fun state() = LinuxInstallState(LinuxInstallPhase.READY)
        override fun availableBytes() = 1L
        override fun supportsArm64() = true
        override fun workspacePath() = "/workspace"
        override fun observe(observer: (LinuxInstallState) -> Unit) = AutoCloseable { }
        override fun installForProbe(progress: (LinuxInstallState) -> Unit, token: CancellationToken): LinuxInstallState = error("No installation")
        override fun probe(): LinuxProbeResult = error("No probe")
        override fun markProbeFailure(code: String, detail: String) = Unit
        override fun markProbeSuccess() = Unit
        override fun uninstall() = error("No uninstall")
        override fun deleteWorkspace() = error("No deletion")
        override fun exec(command: String, cwd: String, timeoutMillis: Long?, callback: LinuxOutputCallback, token: CancellationToken): LinuxExecResult = error("No real execution")
    }

    @Test fun toolPreservesJobIdentityAndFactualTerminalWithoutReportingEnqueueSuccess() {
        val files = chat.newFilesDirectory().also { chat.localRunStore(it) }
        val scope = ProjectScopeStore(files).open("tool-enqueue-uncertain")
        val count = AtomicInteger(); val completions = AtomicInteger()
        val jobs = CodingJobManager(files, CodingJobManager.Backend { _, _, _, _, _, _, launch ->
            count.incrementAndGet(); launch(); LinuxExecResult(0)
        }, executor = Executor { it.run(); throw IllegalStateException("synthetic internal executor detail") })
        val autonomy = LinuxExecAutonomy(InMemoryConnectorAutonomyStore(), { true })
        assertNull(autonomy.setProjectPolicyFromUi(AutonomyPolicy.ALLOW))
        val tool = CodingExecutionTools(scope, jobs, LocalRuntime(), ApprovalGate.INSTANCE, autonomy,
            LinuxOutputSanitizer(emptyList(), emptyList()), CorePromptBudget.standard(), "Bot", "coding",
            { "owner" }, { _, _ -> true }, { _, _ -> completions.incrementAndGet() })
            .create(listOf(CodingExecutionTools.EXEC)).single()
        val result = tool.execute(mapOf("command" to "synthetic-command", "expected_scope_version" to 0L), CancellationToken.cancellable())
        assertFalse(result.success)
        val json = JSONObject(result.content)
        val observed = jobs.list("owner", scope).single()
        assertEquals(observed.id, json.getString("job_id"))
        assertEquals("uncertain", json.getString("submission_transport"))
        assertEquals("SUCCEEDED", json.getString("execution_state")); assertEquals(0, json.getInt("exit_code"))
        assertTrue(json.getString("notice").contains("do not repeat"))
        assertFalse(result.content.contains("synthetic internal executor detail"))
        assertFalse(result.content.contains("synthetic-command"))
        assertNull(result.effectReceipt); assertNull(result.administrativeReceipt)
        assertEquals(1, count.get()); assertEquals(1, completions.get()); assertEquals(1L, scope.version())
    }
}
