package com.jarvys.agent.flavor

import android.content.Context
import android.os.Build
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.coding.ProjectScope
import com.jarvys.agent.coding.ProjectScopeStore
import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.crew.CrewProfile
import com.jarvys.agent.crew.CrewProfileRepository
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.linux.CodingJobManager
import com.jarvys.agent.linux.LinuxExecAutonomy
import com.jarvys.agent.linux.LinuxProjectPaths
import com.jarvys.agent.linux.LinuxProjectStatus
import com.jarvys.agent.linux.LinuxInstallState
import com.jarvys.agent.linux.LinuxInstallPhase
import com.jarvys.agent.linux.RootfsInstaller
import com.jarvys.agent.linux.LinuxProbeResult
import com.jarvys.agent.linux.LinuxOutputCallback
import com.jarvys.agent.linux.LinuxExecResult
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.CorePromptBudget
import com.jarvys.agent.CoreTool
import com.jarvys.agent.MemoryReflectionRuntime
import com.jarvys.agent.UserDecisionPresenter
import com.jarvys.agent.UserDecisionRequests
import com.jarvys.agent.UserDecisionUiAvailability
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.linux.LinuxEnvironment
import com.jarvys.agent.linux.LinuxOutputSanitizer
import com.jarvys.agent.linux.LinuxRuntime
import com.jarvys.agent.proactive.ProactiveConversation
import com.jarvys.agent.tasks.ScheduledTaskConversation

/** Full-flavor seam only. No Linux implementation is linked from the Play source set. */
object FlavorLinuxTools {
    @JvmStatic
    fun create(context: Context, sessionId: String?, depth: Int): List<CoreTool> =
        create(context, sessionId, depth, CorePromptBudget.standard())

    @JvmStatic
    fun create(context: Context, sessionId: String?, depth: Int, budget: CorePromptBudget): List<CoreTool> {
        if (!available(context, sessionId, depth)) return emptyList()
        val app = context.applicationContext
        val activeSession = requireNotNull(sessionId)
        val chatAvailability = {
            UserDecisionUiAvailability.isChatVisible() && com.jarvys.agent.AgentRunUiState.state.value.let {
                it.running && it.sessionId == activeSession
            }
        }
        return createForRuntime(app, activeSession, depth, budget, LinuxEnvironment.get(app), ApprovalGate.INSTANCE,
            UserDecisionRequests.gate, null, chatAvailability)
    }

    @JvmStatic
    fun systemPromptSection(context: Context?, sessionId: String?, depth: Int): String? {
        if (!available(context, sessionId, depth)) return null
        return AppLanguageRuntime.localizedContext(requireNotNull(context).applicationContext)
            .getString(com.jarvys.agent.R.string.full_linux_system_prompt)
    }

    internal fun createForRuntime(
        context: Context,
        sessionId: String,
        depth: Int,
        budget: CorePromptBudget,
        runtime: LinuxRuntime,
        approvalGate: ApprovalGate,
        decisionGate: com.jarvys.agent.UserDecisionGate,
        decisionPresenter: UserDecisionPresenter?,
        decisionAvailability: () -> Boolean,
        autonomy: LinuxExecAutonomy = LinuxExecAutonomy.get(context),
    ): List<CoreTool> {
        if (!available(context, sessionId, depth)) return emptyList()
        val app = context.applicationContext
        val localized = AppLanguageRuntime.localizedContext(app)
        val redactor = LinuxOutputSanitizer.from(app)
        return listOf(
            LinuxStatusTool(localized, sessionId, runtime, redactor, ::available),
            LinuxSetupTool(localized, sessionId, runtime, decisionGate, decisionPresenter,
                decisionAvailability, redactor, ::available),
            LinuxExecTool(localized, sessionId, runtime, approvalGate, redactor, budget, ::available, autonomy),
            LinuxUninstallTool(localized, sessionId, runtime, decisionGate, decisionPresenter,
                decisionAvailability, redactor, ::available, autonomy),
        )
    }

    private val projectManagers = mutableMapOf<String, CodingJobManager>()

    @JvmStatic fun profileCapabilityNames(context: Context?, conversationId: String?): List<String> =
        if (available(context, conversationId, 0)) CodingExecutionTools.NAMES else emptyList()

    @JvmStatic fun profilePrompt(context: Context?, conversationId: String?): String =
        if (!available(context, conversationId, 0)) "" else
            "Current declared capabilities are authoritative; historical release notes in a stored profile may be stale. Execution remains unavailable unless its capability is explicitly selected. Explicitly selected project execution capabilities use the same canonical project as file tools, mounted at /workspace. Only already prepared environments and installed toolchains can be used. Status does not execute a probe; each approved job validates the environment before its command. Each command needs its own user approval, even if the principal has Allow. No installation, interactive stdin, PTY or persistent daemons. PRoot is not a security sandbox; the prepared system rootfs is shared and writable. Keep long-running work in project jobs, read the paginated log and check terminal state/exit code before reporting a check completed. Uncertain or interrupted results must be inspected, never automatically replayed."

    @JvmStatic fun createProfile(context: Context, conversationId: String, bot: CrewManager.Bot,
                                 crew: CrewManager, budget: CorePromptBudget): List<CoreTool> {
        if (!available(context, conversationId, 0) || bot.role.profileVersion <= 0 ||
            bot.role.workspaceMode != CrewProfile.WorkspaceMode.CONVERSATION_PROJECT) return emptyList()
        val selected = CodingExecutionTools.NAMES.filter(bot.role.tools::contains)
        if (selected.isEmpty()) return emptyList()
        val app = context.applicationContext
        val scope = ProjectScopeStore(app.filesDir).open(conversationId)
        val manager = projectJobManager(app)
        val owners = ConcurrentHashMap.newKeySet<String>()
        crew.registerOwnedWork(bot, object : CrewManager.OwnedWork {
            override fun pending(): Boolean = owners.any(manager::hasPending)
            override fun cancelAndAwait() { owners.toList().forEach(manager::cancelAllAndAwait) }
        })
        val profiles = CrewProfileRepository(app)
        fun selectedNow(name: String): Boolean {
            if (bot.role.profileVersion <= 0 || bot.role.workspaceMode != CrewProfile.WorkspaceMode.CONVERSATION_PROJECT ||
                name !in bot.role.tools) return false
            return runCatching {
                val profile = profiles.profile(bot.role.id)
                profile.version == bot.role.profileVersion && profile.workspaceMode == CrewProfile.WorkspaceMode.CONVERSATION_PROJECT &&
                    name in profile.capabilities
            }.getOrDefault(false)
        }
        fun permitted(name: String, token: CancellationToken) = available(app, conversationId, 0) && token === bot.token &&
            !token.isCancelled && selectedNow(name)
        return CodingExecutionTools(scope, manager, ProjectInspectionRuntime(app), ApprovalGate.INSTANCE,
            LinuxExecAutonomy.get(app), LinuxOutputSanitizer.from(app), budget, bot.name, bot.role.colorKey,
            owner = { token ->
                check(permitted(CodingExecutionTools.JOBS, token)) { "Current profile no longer permits project job access" }
                scope.validate()
                val owner = "$conversationId/${bot.id}/${token.generation()}"
                manager.bindRecoveredOwners(owner, scope, bot.recoveredJobOwners())
                bot.addJobOwner(owner); owners.add(owner); owner
            }, available = ::permitted, completed = { token, job ->
                crew.observeOwnedWork(bot, token.generation(), "project-job:${job.id}",
                    "Project job ${job.id} is ${job.state.name}; exit code ${job.exitCode ?: "unknown"}. Read its redacted log with project_jobs before verifying or reporting the result. Never automatically repeat an interrupted or uncertain command.")
            }).create(selected)
    }

    @JvmStatic fun validateProfileResume(context: Context, selectedNames: List<String>) =
        validateProfileResume(selectedNames, LinuxExecAutonomy.get(context.applicationContext))

    internal fun validateProfileResume(selectedNames: List<String>, autonomy: LinuxExecAutonomy) {
        if (CodingExecutionTools.EXEC in selectedNames) check(autonomy.policy() != AutonomyPolicy.DENY) {
            "Project execution is denied by the current command policy; resume did not start"
        }
    }

    @JvmStatic fun checkpointRecovery(context: Context, conversationId: String, scope: ProjectScope, owners: List<String>): String {
        check(available(context, conversationId, 0) && conversationId == scope.conversationId()) {
            "Project recovery is unavailable for this conversation"
        }
        scope.validate(); scope.durableIdentity()
        val manager = projectJobManager(context)
        val evidence = manager.checkpointEvidence(scope, owners)
        check(evidence.all { it.state.terminal }) { "Previous jobs are still pending; stop and await the original owner before resuming" }
        val issues = manager.recoveryIssueCount(scope)
        val counts = evidence.groupingBy { it.state.name }.eachCount().entries.joinToString { "${it.key}=${it.value}" }
        val states = evidence.joinToString("; ") { "${it.id}: ${it.state.name}, exit=${it.exitCode ?: "unknown"}, log=${it.logArtifact}, retained_bytes=${it.logBytes}, log_complete=${it.logComplete}" +
            if (it.legacyIdentity) ", historical identity only; no resumed log access" else "" }
        return "Project job checkpoint: ${counts.ifEmpty { "no matching recorded jobs" }}. Unreadable journals: $issues. " +
            (if (states.isNotEmpty()) "$states. " else "") +
            (if (evidence.any { it.state == CodingJobManager.State.UNCERTAIN } || issues > 0) "Execution outcome or process liveness may be unknown. " else "") +
            (if (evidence.any { !it.logComplete }) "Incomplete log evidence is preserved; do not treat missing output as a passed check. " else "") +
            "No command was replayed or recovered process signalled. Read retained logs with project_jobs after explicit Resume. Only known terminal results with their actual exit code can establish a completed check; new commands still require current approval."
    }

    private fun projectJobManager(context: Context): CodingJobManager = synchronized(projectManagers) {
        val app = context.applicationContext
        projectManagers.getOrPut(app.filesDir.canonicalPath) {
            CodingJobManager(app.filesDir, CodingJobManager.Backend { scope, command, cwd, timeout, token, output, beforeLaunch ->
                LinuxEnvironment.get(app).execProject(scope, command, cwd, timeout, output, token, beforeLaunch)
            }, { LinuxOutputSanitizer.from(app).scrub(it) })
        }
    }

    /** Avoid constructing an installer on the status-only path: that can reconcile installation state. */
    private class ProjectInspectionRuntime(context: Context) : LinuxRuntime {
        private val app = context.applicationContext
        private val paths = RootfsInstaller.Paths(app.filesDir.canonicalFile)
        override fun state(): LinuxInstallState = runCatching {
            if (!paths.stateFile.isFile) LinuxInstallState() else {
                check(paths.stateFile.canonicalFile == paths.stateFile.absoluteFile) { "Installation state path changed" }
                val p = Properties().apply { paths.stateFile.inputStream().use(::load) }
                LinuxInstallState(phase = LinuxInstallPhase.valueOf(p.getProperty("phase", "NOT_INSTALLED")), arch = p.getProperty("arch", ""))
            }
        }.getOrElse { LinuxInstallState(LinuxInstallPhase.FAILED, failureCode = "state_unreadable") }
        override fun supportsArm64() = "arm64-v8a" in Build.SUPPORTED_ABIS
        override fun projectStatus(scope: ProjectScope, cwd: String, requiredTools: List<String>): LinuxProjectStatus =
            LinuxProjectPaths.inspect(paths.rootfs, app.applicationInfo.nativeLibraryDir?.let(::File) ?: File(app.filesDir, "lib"),
                scope, cwd, supportsArm64(), state(), requiredTools = requiredTools, deviceAbis = Build.SUPPORTED_ABIS.toList())
        override fun availableBytes() = app.filesDir.usableSpace
        override fun workspacePath() = paths.workspace.absolutePath
        override fun observe(observer: (LinuxInstallState) -> Unit): AutoCloseable { observer(state()); return AutoCloseable { } }
        override fun installForProbe(progress: (LinuxInstallState) -> Unit, token: CancellationToken): LinuxInstallState = error("Inspection cannot install Linux")
        override fun probe(): LinuxProbeResult = error("Status does not execute probes")
        override fun markProbeFailure(code: String, detail: String): Unit = error("Inspection cannot modify Linux")
        override fun markProbeSuccess(): Unit = error("Inspection cannot modify Linux")
        override fun uninstall(): Unit = error("Inspection cannot uninstall Linux")
        override fun deleteWorkspace(): Unit = error("Inspection cannot delete the workspace")
        override fun exec(command: String, cwd: String, timeoutMillis: Long?, callback: LinuxOutputCallback,
                          token: CancellationToken): LinuxExecResult = error("Inspection cannot execute commands")
    }

    internal fun available(context: Context?, sessionId: String?, depth: Int): Boolean {
        if (context == null || depth != 0 || sessionId.isNullOrBlank()) return false
        if (sessionId == ProactiveConversation.SESSION_ID || sessionId == ScheduledTaskConversation.SESSION_ID) return false
        if (sessionId.startsWith("proactive-") || sessionId.startsWith("task-") || sessionId.startsWith("task:")) return false
        if ("/crew/" in sessionId || sessionId.startsWith("jarvys-subagent-")) return false
        return !MemoryReflectionRuntime.isRunning(sessionId)
    }
}
