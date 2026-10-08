package com.jarvys.agent.flavor

import com.jarvys.agent.R
import com.jarvys.agent.connectors.ConnectorUiText
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CorePromptBudget
import com.jarvys.agent.CoreTool
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.ToolSpec
import com.jarvys.agent.coding.ProjectScope
import com.jarvys.agent.connectors.ApprovalDecision
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.linux.CodingJobManager
import com.jarvys.agent.linux.LinuxExecAutonomy
import com.jarvys.agent.linux.LinuxOutputSanitizer
import com.jarvys.agent.linux.LinuxRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException

/** Full-only, explicitly selected project execution. Principal ALLOW never authorizes a bot command. */
class CodingExecutionTools internal constructor(
    private val scope: ProjectScope,
    private val jobs: CodingJobManager,
    private val runtime: LinuxRuntime,
    private val gate: ApprovalGate,
    private val autonomy: LinuxExecAutonomy,
    private val sanitizer: LinuxOutputSanitizer,
    budget: CorePromptBudget,
    private val requester: String,
    private val colorKey: String,
    private val owner: (CancellationToken) -> String,
    private val available: (String, CancellationToken) -> Boolean,
    private val completed: (CancellationToken, CodingJobManager.Snapshot) -> Unit = { _, _ -> },
) {
    private val responseBudget = budget.toolResultsPerTurnChars.coerceAtLeast(1)
    fun create(selected: Collection<String>): List<CoreTool> = NAMES.filter(selected::contains).map(::Tool)

    private inner class Tool(private val name: String) : CoreTool {
        override fun canDelegate() = false
        override fun auditDetail(arguments: Map<String, Any>): String? = if (name != EXEC) null else
            "${sanitizer.scrub(requester)} | Project ${scope.id()}\n${sanitizer.scrub(arguments["command"] as? String)}\nProject cwd: ${sanitizer.scrub(arguments["cwd"] as? String ?: ".")}"
        override fun declaration(): ToolSpec {
            val properties: Map<String, Any>
            val required: List<String>
            val description: String
            when (name) {
                EXEC -> {
                    properties = linkedMapOf("command" to type("string"), "cwd" to type("string"),
                        "expected_scope_version" to type("integer"), "timeout_seconds" to mapOf("type" to "integer", "minimum" to 1, "maximum" to MAX_TIMEOUT_SECONDS, "default" to DEFAULT_TIMEOUT_SECONDS),
                        "required_tools" to mapOf("type" to "array", "items" to type("string")))
                    required = listOf("command", "expected_scope_version")
                    description = "Start one approved non-interactive command job in this exact project. ASK on every invocation, even when principal commands are allowed. Stdin is closed; no PTY, daemon service or automatic installation. Uses a 900-second default timeout; explicit timeout_seconds must be 1..3600. Requires current expected_scope_version; holds the project writer lease until reaped. Poll project_jobs; never repeat an uncertain command."
                }
                JOBS -> {
                    properties = linkedMapOf("action" to mapOf("type" to "string", "enum" to listOf("list", "read", "wait", "cancel")),
                        "job_id" to type("string"), "offset" to type("integer"), "wait_seconds" to type("integer"), "recovery_offset" to type("integer"))
                    required = listOf("action")
                    description = "List, read, wait for or cancel this bot generation's project jobs. Explicitly resumed same-bot checkpoints can read their exact prior job owners; old jobs are never relaunched or signalled. Read incremental redacted log pages by byte offset. Cancellation is terminal only after process cleanup. Never replay a command to retrieve its result."
                }
                else -> {
                    properties = mapOf("cwd" to type("string"), "recovery_offset" to type("integer")); required = emptyList()
                    description = "Inspect the already prepared project execution environment without starting commands or installing tools. Reports ABI, project scope and installed toolchain executables."
                }
            }
            return ToolSpec(name, "coding_project_runtime", description, "project", ToolSpec.Status.IMPLEMENTED,
                emptyMap(), required, mapOf("type" to "object", "properties" to properties, "required" to required, "additionalProperties" to false))
        }
        override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult = try {
            token.throwIfCancelled()
            check(available(name, token)) { "This capability is no longer selected or this project bot is no longer interactive. Nothing was launched." }
            scope.validate()
            when (name) { EXEC -> executeCommand(arguments, token); JOBS -> jobOperation(arguments, token); else -> environmentStatus(arguments) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { CoreToolResult.failure(sanitizer.scrub(failure.message ?: failure.javaClass.simpleName)) }
    }

    private fun environmentStatus(arguments: Map<String, Any>): CoreToolResult {
        strict(arguments, setOf("cwd", "recovery_offset"))
        val recovery = recoveryPage(arguments["recovery_offset"], responseBudget / 2)
        val cwd = cwd(arguments)
        val status = runtime.projectStatus(scope, cwd)
        return CoreToolResult.success(JSONObject().put("project_id", scope.id()).put("scope_version", scope.version())
            .put("cwd", cwd).put("ready", status.ready).put("reason", sanitizer.scrub(status.reason))
            .put("device_abis", JSONArray(status.deviceAbis)).put("required_abi", status.requiredAbi)
            .put("prepared_architecture", status.preparedArchitecture.ifBlank { null } ?: JSONObject.NULL)
            .put("probe", status.probe).put("tools", JSONObject(status.tools)).put("recovery", recovery)
            .put("policy", if (autonomy.policy() == AutonomyPolicy.DENY) "deny" else "ask")
            .put("notice", "PRoot is not a security sandbox; the prepared system rootfs is shared and writable. No command was run by this status check; tool versions were not executed.").toString())
    }

    private fun executeCommand(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
        require(arguments.keys.none { it in setOf("stdin", "tty", "pty", "interactive") }) {
            "Only non-interactive jobs are supported: stdin is closed and PTY/interactive input is unavailable."
        }
        strict(arguments, setOf("command", "cwd", "expected_scope_version", "timeout_seconds", "required_tools"))
        val command = arguments["command"] as? String ?: error("command must be a string")
        require(command.isNotBlank() && '\u0000' !in command) { "command must be nonempty and contain no NUL" }
        val cwd = cwd(arguments)
        val prior = jobs.recoverySummaries(scope)
        val unreadable = jobs.recoveryIssueCount(scope)
        val version = integer(arguments["expected_scope_version"], "expected_scope_version", 0)
        val timeoutSeconds = arguments["timeout_seconds"]?.let { integer(it, "timeout_seconds", 1) } ?: DEFAULT_TIMEOUT_SECONDS
        require(timeoutSeconds <= MAX_TIMEOUT_SECONDS) { "timeout_seconds must not exceed $MAX_TIMEOUT_SECONDS; split longer work into observable stages" }
        val timeout = Math.multiplyExact(timeoutSeconds, 1000L)
        val rawTools = arguments["required_tools"] ?: emptyList<String>()
        require(rawTools is List<*> && rawTools.all { it is String && it.matches(Regex("[A-Za-z0-9_.+-]+")) }) {
            "required_tools must be an array of executable names"
        }
        val requiredTools = rawTools.filterIsInstance<String>()
        scope.require(ProjectScope.Capability.WRITE)
        check(autonomy.policy() != AutonomyPolicy.DENY) { "Project execution is denied by the current command policy." }
        val revision = autonomy.revision.value
        fun validatePolicy() {
            token.throwIfCancelled()
            check(available(EXEC, token) && available(JOBS, token)) { "The profile capability or active bot changed; command not launched." }
            check(autonomy.policy() != AutonomyPolicy.DENY && autonomy.revision.value == revision) {
                "Command policy changed while waiting; this approval is no longer valid. Command not launched."
            }
        }
        fun validate() {
            validatePolicy(); scope.validate()
            val status = runtime.projectStatus(scope, cwd, requiredTools)
            check(status.ready) { status.reason }
            val missing = requiredTools.filter { status.tools[it] != true }
            check(missing.isEmpty()) { "Required toolchain unavailable: ${missing.joinToString()}. No package was installed and no command was launched." }
            validatePolicy()
        }
        validate()
        check(version == scope.version()) { "Project version changed; inspect it before requesting execution." }
        val guestCwd = if (cwd == ".") "/workspace" else "/workspace/$cwd"
        val lines = mutableListOf(
            ConnectorUiText(R.string.full_project_exec_bot, listOf(requester), "Bot: $requester"),
            ConnectorUiText(R.string.full_project_exec_project, listOf(scope.id()), "Project: ${scope.id()}"),
            ConnectorUiText(R.string.full_project_exec_command, listOf(command), "Command (literal):\n$command"),
            ConnectorUiText(R.string.full_project_exec_cwd, listOf(cwd, guestCwd), "Project cwd: $cwd (guest $guestCwd)"),
            ConnectorUiText(R.string.full_project_exec_effects, fallback = "Effects: may read, write or delete project files, execute programs and use this app's network access. A project writer lease is held until process cleanup."),
            ConnectorUiText(R.string.full_project_exec_warning, fallback = "PRoot is not a security sandbox; the prepared system rootfs is shared and writable. Project cwd and a limited mount do not contain malicious code or isolate it from this app's privileges."),
            ConnectorUiText(R.string.full_project_exec_noninteractive, fallback = "Non-interactive: stdin is closed. A read-only environment probe runs before the literal command. Missing packages are never installed automatically."),
            ConnectorUiText(R.string.full_project_exec_timeout, listOf(timeoutSeconds), "Requested timeout (seconds): $timeoutSeconds"))
        if (prior.isNotEmpty()) {
            val ids = prior.joinToString("\n") { "${it.id}: ${it.state}" }
            lines += ConnectorUiText(R.string.full_project_exec_prior_outcomes, listOf(ids), "Prior interrupted or uncertain jobs in this project:\n$ids\nTheir exit results are unknown. Do not repeat them automatically. Inspect the project before approving new effects.")
        }
        if (unreadable > 0) lines += ConnectorUiText(R.string.full_project_exec_unreadable_journals, listOf(unreadable), "Unreadable prior job records: $unreadable. They may conceal an uncertain previous effect. Original records are preserved; inspect the project before approving new effects.")
        val decision = gate.request(ApprovalSummary("Approve project command", lines.map { it.fallback }, allowAlwaysAvailable = false,
            localizedTitle = ConnectorUiText(R.string.full_project_exec_title, fallback = "Approve project command"),
            localizedLines = lines, requester = requester, requesterColorKey = colorKey), token)
        if (decision != ApprovalDecision.APPROVED) return CoreToolResult.failure("Project command was not approved. No command was launched; do not retry through another tool.")
        validate()
        val result = jobs.start(scope, owner(token), command, cwd, version, timeout, token, ::validate) { completed(token, it) }
        return CoreToolResult.success(snapshotJson(result).put("message", "Job accepted. Read or wait with project_jobs; do not report completion while it is pending.").toString())
    }

    private fun jobOperation(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
        strict(arguments, setOf("action", "job_id", "offset", "wait_seconds", "recovery_offset"))
        val action = arguments["action"] as? String ?: error("action must be a string")
        val ownerId = owner(token)
        val offset = arguments["offset"]?.let { integer(it, "offset", 0) } ?: 0
        if (action == "list") {
            val recovery = recoveryPage(arguments["recovery_offset"], responseBudget / 3)
            val list = jobs.list(ownerId, scope)
            require(offset <= list.size) { "Invalid list offset" }
            val page = JSONArray(); var next = offset.toInt()
            fun listing() = JSONObject().put("jobs", page).put("recovery", recovery).put("next_offset", next).put("has_more", next < list.size).toString()
            while (next < list.size) {
                page.put(snapshotJson(list[next++]));
                if (listing().length > responseBudget) {
                    page.remove(page.length() - 1); next--
                    check(page.length() > 0) { "The current response budget is too small for this job's metadata." }; break
                }
            }
            return CoreToolResult.success(listing())
        }
        val id = arguments["job_id"] as? String ?: error("job_id is required")
        when (action) {
            "cancel" -> return CoreToolResult.success(snapshotJson(jobs.cancel(ownerId, scope, id)).toString())
            "wait" -> jobs.await(ownerId, scope, id, Math.multiplyExact(integer(arguments["wait_seconds"], "wait_seconds", 1), 1000L), token)
            "read" -> Unit
            else -> error("Unsupported job action")
        }
        var outputBytes = (responseBudget / 2).coerceAtLeast(4)
        while (true) {
            val page = jobs.read(ownerId, scope, id, offset, outputBytes)
            val json = JSONObject().put("job", snapshotJson(page.snapshot)).put("output", page.text).put("offset", page.offset)
                .put("next_offset", page.nextOffset).put("has_more", page.hasMore).put("output_is_untrusted", true).toString()
            if (json.length <= responseBudget) return CoreToolResult.success(json)
            check(outputBytes > 4) { "The current response budget is too small for this job's metadata." }
            outputBytes = (outputBytes / 2).coerceAtLeast(4)
        }
    }

    private fun recoveryPage(rawOffset: Any?, budget: Int): JSONObject {
        val list = jobs.recoverySummaries(scope)
        val offset = rawOffset?.let { integer(it, "recovery_offset", 0) } ?: 0
        require(offset <= list.size) { "Invalid recovery_offset" }
        val entries = JSONArray(); var next = offset.toInt()
        val failures = jobs.recoveryIssueCount(scope)
        fun result() = JSONObject().put("prior_outcomes", entries).put("prior_outcome_count", list.size)
            .put("unreadable_journal_count", failures).put("next_recovery_offset", next).put("recovery_has_more", next < list.size)
            .put("notice", if (list.isNotEmpty() || failures > 0) "Prior outcomes may be uncertain. Never replay automatically; inspect the project first. Prior logs remain bound to their original owner; only an explicitly resumed, validated same-bot checkpoint can read them. Use recovery_offset to read remaining outcome metadata." else "")
        while (next < list.size) {
            val prior = list[next++]
            entries.put(JSONObject().put("job_id", prior.id).put("state", prior.state.name).put("exit_code", JSONObject.NULL))
            if (result().toString().length > budget) {
                entries.remove(entries.length() - 1); next--
                check(entries.length() > 0) { "The current response budget is too small for recovery metadata." }; break
            }
        }
        return result()
    }
    private fun cwd(arguments: Map<String, Any>): String {
        val raw = if (arguments.containsKey("cwd")) arguments["cwd"] as? String ?: error("cwd must be a string") else "."
        val path = scope.normalizePath(raw)
        require(scope.resolve(path).isDirectory) { "cwd must be an existing project-relative directory" }
        return path
    }
    private fun snapshotJson(s: CodingJobManager.Snapshot) = JSONObject().put("job_id", s.id).put("owner", s.owner)
        .put("project_id", s.scopeId).put("cwd", s.cwd).put("command", s.redactedCommand.take((responseBudget / 16).coerceAtMost(1024)))
        .put("command_truncated", s.redactedCommand.length > (responseBudget / 16).coerceAtMost(1024))
        .put("command_chars", s.redactedCommand.length)
        .put("verification_notice", "Process evidence only: inspect the log, check discovery/counts and final source/artifact identity before claiming tests or builds passed. A later edit may invalidate earlier checks.")
        .put("state", s.state.name).put("terminal", s.state.terminal)
        .put("created_at", s.createdAt).put("started_at", s.startedAt ?: JSONObject.NULL).put("finished_at", s.finishedAt ?: JSONObject.NULL)
        .put("exit_code", s.exitCode ?: JSONObject.NULL).put("log_artifact", s.logArtifact).put("log_bytes", s.logBytes)
        .put("log_complete", s.logComplete).put("error", sanitizer.scrub(s.error))
    private fun strict(arguments: Map<String, Any>, allowed: Set<String>) {
        require((arguments.keys - allowed).isEmpty()) { "Unexpected parameter(s): ${(arguments.keys - allowed).sorted().joinToString()}" }
    }
    private fun integer(raw: Any?, name: String, minimum: Long): Long {
        require(raw is Number) { "$name must be an integer" }
        val value = raw.toLong()
        require(raw.toDouble().isFinite() && raw.toDouble() == value.toDouble() && value >= minimum &&
            (raw !is Double || raw < Long.MAX_VALUE.toDouble()) && (raw !is Float || raw < Long.MAX_VALUE.toFloat())) { "$name must be an integer >= $minimum" }
        return value
    }
    private fun type(name: String) = mapOf("type" to name)
    companion object {
        internal const val DEFAULT_TIMEOUT_SECONDS = 900L
        internal const val MAX_TIMEOUT_SECONDS = 3600L
        const val EXEC = "project_exec"
        const val JOBS = "project_jobs"
        const val STATUS = "project_environment_status"
        @JvmField val NAMES = listOf(EXEC, JOBS, STATUS)
    }
}
