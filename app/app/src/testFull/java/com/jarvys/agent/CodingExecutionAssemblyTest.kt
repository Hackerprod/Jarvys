package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.coding.ProjectScope
import com.jarvys.agent.coding.ProjectScopeStore
import com.jarvys.agent.connectors.*
import com.jarvys.agent.crew.*
import com.jarvys.agent.flavor.CodingExecutionTools
import com.jarvys.agent.flavor.FlavorLinuxTools
import com.jarvys.agent.linux.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/** Production assembly and approval boundaries; the native process is deliberately a test double. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodingExecutionAssemblyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var previousSecrets: Any? = null
    @Before fun prepare() {
        val field = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
        previousSecrets = field.get(null)
        field.set(null, SecretStore(context.getSharedPreferences("ux25-assembly", Context.MODE_PRIVATE)))
    }
    @After fun clean() {
        SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }.set(null, previousSecrets)
    }
    private fun role(session: String): CrewRole = CoreAgentRuntime(context, session, emptyList())
        .resolveCrewProfile(CoreAgentRuntime.profileCapabilities(context, session), "coding")
    private fun manager(session: String) = CrewManager(session, CoreToolRegistry(emptyList()),
        { _, _ -> error("Historical fixture must not start a worker") },
        { _, _, _ -> error("No real provider is used") }, null)
    private fun restored(manager: CrewManager, role: CrewRole): CrewManager.Bot {
        val snapshot = CrewBotSnapshot("ux25-bot", role.id, role.name, role.name, role.colorKey,
            "Inspect production execution", "INTERRUPTED", "", "", "", role.tools, 1L, 0L)
        val mission = CrewMissionSnapshot("ux25-mission", manager.conversationId(), "prior", "Execution", "INTERRUPTED",
            "", 1L, 0L, listOf(snapshot), emptyList())
        return manager.restoreBot(mission, snapshot, role, CoreAgentLoop.Checkpoint.empty(), JSONObject(), "",
            emptyList(), 0, emptyList(), emptyList(), "")
    }
    private class Runtime : LinuxRuntime {
        override fun projectStatus(scope: ProjectScope, cwd: String, requiredTools: List<String>) =
            LinuxProjectStatus(true, "fixture prepared", tools = mapOf("test" to true), probe = "required_at_approved_launch")
        override fun state() = LinuxInstallState(LinuxInstallPhase.READY)
        override fun availableBytes() = 1L
        override fun supportsArm64() = true
        override fun workspacePath() = "/workspace"
        override fun observe(observer: (LinuxInstallState) -> Unit) = AutoCloseable { }
        override fun installForProbe(progress: (LinuxInstallState) -> Unit, token: CancellationToken): LinuxInstallState = error("No installation")
        override fun probe(): LinuxProbeResult = error("No implicit probe")
        override fun markProbeFailure(code: String, detail: String) = Unit
        override fun markProbeSuccess() = Unit
        override fun uninstall(): Unit = error("No uninstall")
        override fun deleteWorkspace(): Unit = error("No delete")
        override fun exec(command: String, cwd: String, timeoutMillis: Long?, callback: LinuxOutputCallback, token: CancellationToken): LinuxExecResult = error("No principal route")
    }

    @Test fun builtInActualRegistryExposesFullExecutionAndCannotForwardItToGenericChildren() {
        val session = "ux25-real-${System.nanoTime()}"
        val runtime = CoreAgentRuntime(context, session, emptyList())
        val selected = role(session)
        assertTrue(selected.tools.containsAll(CodingExecutionTools.NAMES))
        assertFalse(CrewProfile.codingDefault().capabilities.contains(CodingExecutionTools.EXEC))
        manager(session).use { crew ->
            val bot = restored(crew, selected)
            val registry = runtime.createCrewBotTools(context, session, CoreToolRegistry(emptyList()), bot, crew)
            assertTrue(registry.names().containsAll(CodingExecutionTools.NAMES))
            assertTrue(CodingExecutionTools.NAMES.none { it in registry.forDelegatedAgent().names() })
            val result = registry.invoke(CodingExecutionTools.STATUS, emptyMap(), bot.token)
            assertTrue(result.content, result.success)
            assertFalse(JSONObject(result.content).getBoolean("ready")) // Host has no prepared native environment.
            assertTrue(result.content.contains("No command was run"))
        }
    }

    @Test fun realEffectiveProfileRequiresEachExactApprovalAndKeepsCommandReceipt() {
        val session = "ux25-approved-${System.nanoTime()}"
        val scope = ProjectScopeStore(context.filesDir).open(session)
        var launches = 0; var approvals = 0; var timeout: Long? = null
        val commands = mutableListOf<String>()
        val jobs = CodingJobManager(context.filesDir, CodingJobManager.Backend { _, command, _, limit, _, output, beforeLaunch ->
            beforeLaunch(); launches++; commands += command; timeout = limit
            output.onChunk(LinuxOutputStream.STDOUT, "fixture check completed\n"); LinuxExecResult(0)
        }, executor = Executor { it.run() })
        val autonomy = LinuxExecAutonomy(InMemoryConnectorAutonomyStore(), { true })
        autonomy.setPolicyFromUi(AutonomyPolicy.ALLOW)
        var decision = ApprovalDecision.DENIED
        var whileApproving: () -> Unit = { }
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(presenter = object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) {
                approvals++; assertFalse(summary.allowAlwaysAvailable); whileApproving(); gate.resolve(id, decision)
            }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        manager(session).use { crew ->
            val bot = restored(crew, role(session))
            val tools = FlavorLinuxTools.createProfileForRuntime(context, session, bot, crew, CorePromptBudget.standard(),
                scope, jobs, Runtime(), gate, autonomy, LinuxOutputSanitizer(emptyList(), emptyList()))
                .associateBy { it.declaration().name }
            fun execute(command: String) = tools.getValue(CodingExecutionTools.EXEC).execute(
                mapOf("command" to command, "expected_scope_version" to scope.version()), bot.token)
            assertFalse(execute("test rejected").success)
            assertEquals(0, launches); assertEquals(1, approvals)
            decision = ApprovalDecision.APPROVED
            val result = execute("test approved")
            assertTrue(result.content, result.success)
            assertEquals(1, launches); assertEquals(2, approvals); assertEquals(900_000L, timeout)
            val receipt = JSONObject(result.content)
            assertEquals("test approved", receipt.getString("command"))
            assertEquals("SUCCEEDED", receipt.getString("state"))
            assertTrue(receipt.getString("verification_notice").contains("Process evidence only"))
            assertTrue(execute("test second").success)
            assertEquals(listOf("test approved", "test second"), commands)
            assertEquals(3, approvals)
            whileApproving = { bot.token.cancel() }
            assertThrows(java.util.concurrent.CancellationException::class.java) { execute("must never run") }
            assertEquals(2, launches)
        }
    }

    @Test fun disablingSelectedProfileWhileApprovalPendingBlocksLaunchInLiveResolver() {
        val session = "ux25-revoked-${System.nanoTime()}"
        val scope = ProjectScopeStore(context.filesDir).open(session)
        var launched = false
        val jobs = CodingJobManager(context.filesDir, CodingJobManager.Backend { _, _, _, _, _, _, before ->
            before(); launched = true; LinuxExecResult(0)
        }, executor = Executor { it.run() })
        val profiles = CrewProfileRepository(context)
        val configured = CrewProfile(CrewProfileRepository.newCustomId(), 1, "Execution fixture", "Explicit test capabilities",
            "Observe approved commands", emptyList(), CodingExecutionTools.NAMES, CrewProfile.WorkspaceMode.CONVERSATION_PROJECT)
        profiles.create(configured, CoreAgentRuntime.profileCapabilities(context, session), emptyList())
        var revoked = false
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(presenter = object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) {
                val definition = profiles.definition(configured.id)
                profiles.setEnabled(configured.id, definition.revision, false)
                revoked = !profiles.definition(configured.id).enabled
                gate.resolve(id, ApprovalDecision.APPROVED)
            }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        manager(session).use { crew ->
            val bot = restored(crew, profiles.resolveRole(configured.id, CoreAgentRuntime.profileCapabilities(context, session), emptyList()))
            val tools = FlavorLinuxTools.createProfileForRuntime(context, session, bot, crew, CorePromptBudget.standard(),
                scope, jobs, Runtime(), gate, LinuxExecAutonomy(InMemoryConnectorAutonomyStore(), { true }),
                LinuxOutputSanitizer(emptyList(), emptyList())).associateBy { it.declaration().name }
            val result = tools.getValue(CodingExecutionTools.EXEC).execute(
                mapOf("command" to "test", "expected_scope_version" to scope.version()), bot.token)
            assertFalse(result.success); assertFalse(launched); assertTrue("Supported profile revocation actually occurred", revoked)
        }
    }

    @Test fun customCopiesDoNotInheritExecutionButExplicitCustomSelectionsRemainValid() {
        val session = "ux25-custom-${System.nanoTime()}"
        val profiles = CrewProfileRepository(context)
        val base = CrewProfile.codingDefault().withIdentity(CrewProfileRepository.newCustomId()).withVersion(1)
        val copied = profiles.create(base, base.capabilities, base.skillIds)
        assertFalse(profiles.resolveRole(copied.id, CoreAgentRuntime.profileCapabilities(context, session), base.skillIds)
            .tools.contains(CodingExecutionTools.EXEC))
        val explicit = CrewProfile(CrewProfileRepository.newCustomId(), 1, "Configured coder", "Explicit project tools",
            "Verify only approved commands", emptyList(), CodingExecutionTools.NAMES, CrewProfile.WorkspaceMode.CONVERSATION_PROJECT)
        profiles.create(explicit, CoreAgentRuntime.profileCapabilities(context, session), emptyList())
        assertTrue(profiles.resolveRole(explicit.id, CoreAgentRuntime.profileCapabilities(context, session), emptyList())
            .tools.containsAll(CodingExecutionTools.NAMES))
    }

    @Test fun noninteractiveAndPartialCeilingsDoNotInjectExecution() {
        val runtime = CoreAgentRuntime(context, "task:ux25", emptyList())
        assertFalse(runtime.resolveCrewProfile(CoreAgentRuntime.profileCapabilities(context, "task:ux25"), "coding")
            .tools.contains(CodingExecutionTools.EXEC))
        val profile = CrewProfile.codingDefault()
        val partial = profile.capabilities + CodingExecutionTools.EXEC
        val resolved = CrewProfileRepository(context).resolveRole("coding", partial, profile.skillIds)
        assertTrue(CodingExecutionTools.NAMES.none { it in resolved.tools })
    }
}
