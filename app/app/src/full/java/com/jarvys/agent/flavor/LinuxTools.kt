package com.jarvys.agent.flavor

import android.content.Context
import android.text.format.Formatter
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CorePromptBudget
import com.jarvys.agent.CoreTool
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.R
import com.jarvys.agent.ToolSpec
import com.jarvys.agent.UserDecisionGate
import com.jarvys.agent.UserDecisionOption
import com.jarvys.agent.UserDecisionPresenter
import com.jarvys.agent.UserDecisionResult
import com.jarvys.agent.UserDecisionRole
import com.jarvys.agent.UserDecisionSpec
import com.jarvys.agent.UserDecisionTool
import com.jarvys.agent.connectors.ApprovalDecision
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.connectors.ConnectorUiText
import com.jarvys.agent.linux.LinuxInstallPhase
import com.jarvys.agent.linux.LinuxInstallState
import com.jarvys.agent.linux.LinuxFailureDiagnostics
import com.jarvys.agent.linux.LinuxOutputCallback
import com.jarvys.agent.linux.LinuxOutputSanitizer
import com.jarvys.agent.linux.LinuxOutputStream
import com.jarvys.agent.linux.LinuxProbeResult
import com.jarvys.agent.linux.LinuxRuntime
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale

private const val LINUX_TOOLS_SOURCE = "jarvys/linux"
private const val LINUX_STATUS_NAME = "linux_status"
private const val LINUX_SETUP_NAME = "linux_setup"
private const val LINUX_EXEC_NAME = "linux_exec"
private const val LINUX_UNINSTALL_NAME = "linux_uninstall"

private fun emptyObjectSchema() = linkedMapOf<String, Any>(
    "type" to "object", "properties" to emptyMap<String, Any>(), "required" to emptyList<String>(),
    "additionalProperties" to false,
)

private fun strictSchema(properties: Map<String, Any>, required: List<String>) = linkedMapOf<String, Any>(
    "type" to "object", "properties" to properties, "required" to required, "additionalProperties" to false,
)

private fun linuxSpec(name: String, description: String, schema: Map<String, Any>) = ToolSpec(
    name, LINUX_TOOLS_SOURCE, description, "linux", ToolSpec.Status.IMPLEMENTED,
    emptyMap(), emptyList(), schema,
)

private fun linuxText(context: Context, resource: Int, vararg args: Any): String =
    AppLanguageRuntime.localizedContext(context.applicationContext).getString(resource, *args)

private fun unavailable(context: Context) = CoreToolResult.failure(
    linuxText(context, R.string.full_linux_setup_unavailable))

private fun safeFailure(context: Context, sanitizer: LinuxOutputSanitizer, failure: Throwable): CoreToolResult =
    CoreToolResult.failure(sanitizer.scrub(failure.message ?: failure.javaClass.simpleName))

internal abstract class LinuxToolBase(
    protected val context: Context,
    protected val sessionId: String,
    private val surfaceAvailable: (Context?, String?, Int) -> Boolean,
) : CoreTool {
    protected fun ensureAvailable(): Boolean = surfaceAvailable(context, sessionId, 0)
    protected fun requireNoArguments(arguments: Map<String, Any>) {
        require(arguments.isEmpty()) { "Unexpected parameter(s): ${arguments.keys.sorted().joinToString()}" }
    }
}

internal class LinuxStatusTool(
    context: Context,
    sessionId: String,
    private val runtime: LinuxRuntime,
    private val sanitizer: LinuxOutputSanitizer,
    surfaceAvailable: (Context?, String?, Int) -> Boolean,
) : LinuxToolBase(context, sessionId, surfaceAvailable) {
    override fun declaration() = linuxSpec(LINUX_STATUS_NAME,
        "Read the optional Linux installation state and, when installed, the PRoot probe result.", emptyObjectSchema())

    override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
        return try {
            requireNoArguments(arguments)
            token.throwIfCancelled()
            if (!ensureAvailable()) return unavailable(context)
            CoreToolResult.success(statusJson().toString())
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            safeFailure(context, sanitizer, failure)
        }
    }

    internal fun statusJson(): JSONObject {
        val state = runtime.state()
        val status = if (!runtime.supportsArm64()) "UNSUPPORTED_ABI" else state.phase.name
        val message = when (status) {
            "NOT_INSTALLED" -> linuxText(context, R.string.full_linux_not_installed)
            "UNSUPPORTED_ABI" -> linuxText(context, R.string.full_linux_unsupported_abi)
            else -> ""
        }
        val result = JSONObject()
            .put("status", status)
            .put("version", state.versionId)
            .put("distro", state.distro)
            .put("version_id", state.versionId)
            .put("version_codename", state.codename)
            .put("catalog_version", state.version)
            .put("architecture", state.arch)
            .put("size_bytes", state.sizeBytes)
            .put("downloaded_bytes", state.bytes)
            .put("download_total_bytes", state.total)
            .put("extracted_entries", state.extractedEntries)
            .put("last_entry_seen", sanitizer.scrub(state.lastEntrySeen))
            .put("skipped_entries", state.skippedEntries)
            .put("installed_at_ms", state.installedAt)
            .put("free_bytes", runtime.availableBytes())
            .put("failure_code", state.failureCode)
            .put("failure_detail", sanitizer.scrub(state.failureDetail))
            .put("degraded_entries", state.degradedEntries)
            .put("hardlinks_copied", state.hardlinksCopied)
            .put("message", message)
        if (status == "READY") {
            val probe = runtime.probe()
            result.put("probe", linuxProbeJson(probe, sanitizer))
            if (!probe.ready) result.put("failure_detail", LinuxFailureDiagnostics.probe(probe, sanitizer::scrub))
        }
        return result
    }
}

internal class LinuxSetupTool(
    context: Context,
    sessionId: String,
    private val runtime: LinuxRuntime,
    private val decisionGate: UserDecisionGate,
    private val decisionPresenter: UserDecisionPresenter?,
    private val decisionAvailability: () -> Boolean,
    private val sanitizer: LinuxOutputSanitizer,
    surfaceAvailable: (Context?, String?, Int) -> Boolean,
) : LinuxToolBase(context, sessionId, surfaceAvailable) {
    override fun declaration() = linuxSpec(LINUX_SETUP_NAME,
        "Request explicit installation consent, install Ubuntu Base, then verify it with the PRoot probe.",
        emptyObjectSchema())

    override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult =
        execute(arguments, token, CoreTool.ProgressListener { })

    override fun execute(arguments: Map<String, Any>, token: CancellationToken,
                         progress: CoreTool.ProgressListener): CoreToolResult {
        try {
            requireNoArguments(arguments)
            token.throwIfCancelled()
            if (!ensureAvailable()) return unavailable(context)
            if (!runtime.supportsArm64()) return CoreToolResult.failure(linuxText(context, R.string.full_linux_unsupported_abi))
            val existing = runtime.state()
            if (existing.phase == LinuxInstallPhase.READY) {
                progress.onProgress(linuxText(context, R.string.full_linux_progress_probe))
                val probe = runtime.probe()
                if (probe.ready) {
                    runtime.markProbeSuccess()
                    return CoreToolResult.success(JSONObject().put("status", "already_ready")
                        .put("message", linuxText(context, R.string.full_linux_setup_already_ready))
                        .put("probe", linuxProbeJson(probe, sanitizer)).toString())
                }
                val detail = LinuxFailureDiagnostics.probe(probe, sanitizer::scrub)
                runtime.markProbeFailure(probe.status.name.lowercase(Locale.ROOT), detail)
                return setupFailure(detail)
            }
            when (val decision = requestDecision(setupDecisionSpec(), token)) {
                is UserDecisionResult.Selected -> if (decision.option.id != OPTION_INSTALL) {
                    return CoreToolResult.failure(linuxText(context, R.string.full_linux_setup_dismissed))
                }
                UserDecisionResult.Dismissed, UserDecisionResult.Cancelled ->
                    return CoreToolResult.failure(linuxText(context, R.string.full_linux_setup_dismissed))
                UserDecisionResult.Unavailable ->
                    return CoreToolResult.failure(linuxText(context, R.string.full_linux_setup_unavailable))
            }
            val installed = runtime.installForProbe({ state -> progress.onProgress(progressMessage(state)) }, token)
            if (installed.phase != LinuxInstallPhase.READY) {
                return setupFailure(installed.failureDetail.ifBlank { installed.failureCode.ifBlank { installed.phase.name } })
            }
            progress.onProgress(linuxText(context, R.string.full_linux_progress_probe))
            val probe = runtime.probe()
            if (!probe.ready) {
                val detail = LinuxFailureDiagnostics.probe(probe, sanitizer::scrub)
                runtime.markProbeFailure(probe.status.name.lowercase(Locale.ROOT), detail)
                return setupFailure(detail)
            }
            runtime.markProbeSuccess()
            return CoreToolResult.success(JSONObject().put("status", "ready")
                .put("version", installed.version).put("architecture", installed.arch)
                .put("size_bytes", installed.sizeBytes).put("probe", linuxProbeJson(probe, sanitizer))
                .put("message", linuxText(context, R.string.full_linux_progress_ready)).toString())
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            return setupFailure(LinuxFailureDiagnostics.format("SETUP", failure, sanitize = sanitizer::scrub))
        }
    }

    private fun setupFailure(detail: String): CoreToolResult = CoreToolResult.failure(
        linuxText(context, R.string.full_linux_setup_failed_detail, sanitizer.scrub(detail)))

    private fun setupDecisionSpec(): UserDecisionSpec {
        val c = AppLanguageRuntime.localizedContext(context.applicationContext)
        val body = c.getString(R.string.full_linux_setup_consent_body,
            com.jarvys.agent.linux.LinuxCatalog.VERSION, com.jarvys.agent.linux.LinuxCatalog.ARCH,
            Formatter.formatFileSize(c, com.jarvys.agent.linux.LinuxCatalog.DOWNLOAD_BYTES),
            Formatter.formatFileSize(c, com.jarvys.agent.linux.LinuxCatalog.EXTRACTED_BYTES),
            Formatter.formatFileSize(c, runtime.availableBytes()))
        return UserDecisionSpec(c.getString(R.string.full_linux_setup_consent_title), body, listOf(
            UserDecisionOption(OPTION_INSTALL, c.getString(R.string.full_linux_setup_install), role = UserDecisionRole.PRIMARY),
            UserDecisionOption(OPTION_LATER, c.getString(R.string.full_linux_setup_later)),
        ), allowDismiss = false)
    }

    private fun requestDecision(spec: UserDecisionSpec, token: CancellationToken): UserDecisionResult {
        if (!decisionAvailability()) return UserDecisionResult.Unavailable
        val options = spec.options.map { option -> linkedMapOf<String, Any>(
            "id" to option.id, "label" to option.label,
            "role" to when (option.role) {
                UserDecisionRole.PRIMARY -> "primary"
                UserDecisionRole.DESTRUCTIVE -> "destructive"
                UserDecisionRole.DEFAULT -> "default"
            },
        ).apply { if (option.description.isNotEmpty()) put("description", option.description) } }
        val tool = UserDecisionTool(context, sessionId, decisionGate, decisionAvailability, decisionPresenter)
        val result = JSONObject(tool.execute(mapOf("title" to spec.title, "body" to spec.body,
            "options" to options, "allow_dismiss" to spec.allowDismiss), token).content)
        return when (result.optString("status")) {
            "selected" -> spec.options.firstOrNull { it.id == result.optString("option_id") }
                ?.let(UserDecisionResult::Selected) ?: UserDecisionResult.Unavailable
            "dismissed" -> UserDecisionResult.Dismissed
            "cancelled" -> UserDecisionResult.Cancelled
            else -> UserDecisionResult.Unavailable
        }
    }

    private fun progressMessage(state: LinuxInstallState): String = when (state.phase) {
        LinuxInstallPhase.DOWNLOADING -> linuxText(context, R.string.full_linux_progress_downloading,
            Formatter.formatFileSize(context, state.bytes), Formatter.formatFileSize(context, state.total))
        LinuxInstallPhase.VERIFYING -> linuxText(context, R.string.full_linux_progress_verifying)
        LinuxInstallPhase.EXTRACTING -> linuxText(context, R.string.full_linux_progress_extracting, state.extractedEntries)
        LinuxInstallPhase.PATCHING -> linuxText(context, R.string.full_linux_progress_patching)
        LinuxInstallPhase.READY -> linuxText(context, R.string.full_linux_progress_ready)
        LinuxInstallPhase.FAILED -> linuxText(context, R.string.full_linux_setup_failed,
            sanitizer.scrub(state.failureCode))
        LinuxInstallPhase.NOT_INSTALLED -> linuxText(context, R.string.full_linux_status_not_installed_label)
    }

    private companion object {
        const val OPTION_INSTALL = "install"
        const val OPTION_LATER = "later"
    }
}

internal class LinuxExecTool(
    context: Context,
    sessionId: String,
    private val runtime: LinuxRuntime,
    private val approvalGate: ApprovalGate,
    private val sanitizer: LinuxOutputSanitizer,
    budget: CorePromptBudget,
    surfaceAvailable: (Context?, String?, Int) -> Boolean,
) : LinuxToolBase(context, sessionId, surfaceAvailable) {
    private val perStreamChars = (budget.toolResultsPerTurnChars / 4).coerceAtLeast(1)

    override fun declaration(): ToolSpec = linuxSpec(LINUX_EXEC_NAME,
        "Run one individually user-approved command inside the optional PRoot Ubuntu environment.", execSchema())

    override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult =
        execute(arguments, token, CoreTool.ProgressListener { })

    override fun execute(arguments: Map<String, Any>, token: CancellationToken,
                         progress: CoreTool.ProgressListener): CoreToolResult {
        try {
            val unknown = arguments.keys - setOf("command", "cwd", "timeout_seconds")
            require(unknown.isEmpty()) { "Unexpected parameter(s): ${unknown.sorted().joinToString()}" }
            val command = arguments["command"] as? String ?: throw IllegalArgumentException("command must be a string")
            require(command.isNotBlank()) { "command must not be empty" }
            val cwd = (arguments["cwd"] as? String)?.also(::validateCwd) ?: "/workspace"
            val timeoutMillis = parseTimeout(arguments["timeout_seconds"])
            token.throwIfCancelled()
            if (!ensureAvailable()) return unavailable(context)
            if (!runtime.supportsArm64()) return CoreToolResult.failure(linuxText(context, R.string.full_linux_unsupported_abi))
            if (runtime.state().phase != LinuxInstallPhase.READY) {
                return CoreToolResult.failure(linuxText(context, R.string.full_linux_exec_not_installed))
            }
            val decision = approvalGate.request(commandApproval(command, cwd, timeoutMillis), token)
            if (decision != ApprovalDecision.APPROVED) return CoreToolResult.failure(approvalResultMessage(decision))

            progress.onProgress(linuxText(context, R.string.full_linux_exec_progress_running))
            val stdout = LinuxBoundedOutput(perStreamChars, sanitizer)
            val stderr = LinuxBoundedOutput(perStreamChars, sanitizer)
            val outputProgress = LinuxExecProgress(sanitizer, emit = { stream, line ->
                val resource = if (stream == LinuxOutputStream.STDERR) R.string.full_linux_exec_progress_stderr
                    else R.string.full_linux_exec_progress_stdout
                progress.onProgress(linuxText(context, resource, line))
            })
            val started = android.os.SystemClock.elapsedRealtime()
            val execution = runtime.exec(command, cwd, timeoutMillis, LinuxOutputCallback { stream, text ->
                val message = if (stream == LinuxOutputStream.STDOUT) {
                    stdout.append(text)
                } else {
                    stderr.append(text)
                }
                outputProgress.append(stream, text)
            }, token)
            outputProgress.finish()
            val stdoutResult = stdout.finish()
            val stderrResult = stderr.finish()
            val output = JSONObject()
                .put("exit_code", execution.exitCode)
                .put("stdout", stdoutResult.text)
                .put("stderr", stderrResult.text)
                .put("timed_out", execution.timedOut)
                .put("cancelled", execution.cancelled)
                .put("truncated", stdoutResult.truncated || stderrResult.truncated)
                .put("duration_ms", (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L))
            return CoreToolResult.success(output.toString())
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            return safeFailure(context, sanitizer, failure)
        }
    }

    private fun commandApproval(command: String, cwd: String, timeoutMillis: Long?): ApprovalSummary {
        val lines = mutableListOf(
            ConnectorUiText(R.string.full_linux_exec_approval_command, listOf(command), "Command (literal):\n$command"),
            ConnectorUiText(R.string.full_linux_exec_approval_cwd, listOf(cwd), "Working directory: $cwd"),
            ConnectorUiText(R.string.full_linux_exec_approval_warning,
                fallback = "PRoot is not a sandbox. The command runs with this app's access and network permissions."),
        )
        if (timeoutMillis != null) lines += ConnectorUiText(R.string.full_linux_exec_approval_timeout,
            listOf(timeoutMillis / 1000L), "Timeout: ${timeoutMillis / 1000L} seconds")
        return ApprovalSummary(
            title = "Approve Linux command", lines = lines.map { it.fallback }, allowAlwaysAvailable = false,
            localizedTitle = ConnectorUiText(R.string.full_linux_exec_approval_title, fallback = "Approve Linux command"),
            localizedLines = lines,
            compactSummary = ConnectorUiText(R.string.full_linux_exec_approval_compact,
                listOf(command), "Linux: $command"),
        )
    }

    private fun approvalResultMessage(decision: ApprovalDecision): String = linuxText(context, when (decision) {
        ApprovalDecision.EXPIRED -> R.string.full_linux_exec_expired
        ApprovalDecision.CANCELLED -> R.string.full_linux_exec_cancelled
        else -> R.string.full_linux_exec_denied
    })

    private fun parseTimeout(raw: Any?): Long? {
        if (raw == null) return null
        val number = raw as? Number ?: throw IllegalArgumentException("timeout_seconds must be an integer")
        val seconds = number.toLong()
        require(number.toDouble() == seconds.toDouble() && seconds > 0L) {
            linuxText(context, R.string.full_linux_exec_timeout_invalid)
        }
        return try { Math.multiplyExact(seconds, 1000L) }
        catch (_: ArithmeticException) { throw IllegalArgumentException(linuxText(context, R.string.full_linux_exec_timeout_invalid)) }
    }

    private fun validateCwd(cwd: String) {
        require(cwd.startsWith('/') && cwd.none(Char::isISOControl)
            && cwd.replace('\\', '/').split('/').none { it == ".." }) {
            "cwd must be an absolute path inside the guest rootfs"
        }
    }

    private fun execSchema() = strictSchema(linkedMapOf(
        "command" to mapOf("type" to "string", "minLength" to 1),
        "cwd" to mapOf("type" to "string"),
        "timeout_seconds" to mapOf("type" to "integer", "minimum" to 1),
    ), listOf("command"))
}

internal class LinuxUninstallTool(
    context: Context,
    sessionId: String,
    private val runtime: LinuxRuntime,
    private val decisionGate: UserDecisionGate,
    private val decisionPresenter: UserDecisionPresenter?,
    private val decisionAvailability: () -> Boolean,
    private val sanitizer: LinuxOutputSanitizer,
    surfaceAvailable: (Context?, String?, Int) -> Boolean,
) : LinuxToolBase(context, sessionId, surfaceAvailable) {
    override fun declaration() = linuxSpec(LINUX_UNINSTALL_NAME,
        "Remove the private Linux rootfs after the user chooses whether /workspace should be kept.", emptyObjectSchema())

    override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
        return try {
            requireNoArguments(arguments)
            token.throwIfCancelled()
            if (!ensureAvailable()) return unavailable(context)
            when (val decision = requestDecision(uninstallDecisionSpec(), token)) {
                is UserDecisionResult.Selected -> when (decision.option.id) {
                    OPTION_KEEP_WORKSPACE -> {
                        runtime.uninstall()
                        CoreToolResult.success(linuxText(context, R.string.full_linux_uninstall_done_keep))
                    }
                    OPTION_DELETE_WORKSPACE -> {
                        runtime.uninstall()
                        runtime.deleteWorkspace()
                        CoreToolResult.success(linuxText(context, R.string.full_linux_uninstall_done_all))
                    }
                    else -> CoreToolResult.failure(linuxText(context, R.string.full_linux_uninstall_dismissed))
                }
                UserDecisionResult.Unavailable -> CoreToolResult.failure(linuxText(context, R.string.full_linux_uninstall_unavailable))
                else -> CoreToolResult.failure(linuxText(context, R.string.full_linux_uninstall_dismissed))
            }
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            CoreToolResult.failure(linuxText(context, R.string.full_linux_uninstall_failed,
                sanitizer.scrub(failure.message ?: failure.javaClass.simpleName)))
        }
    }

    private fun uninstallDecisionSpec() = UserDecisionSpec(
        linuxText(context, R.string.full_linux_uninstall_title), linuxText(context, R.string.full_linux_uninstall_body),
        listOf(
            UserDecisionOption(OPTION_KEEP_WORKSPACE, linuxText(context, R.string.full_linux_uninstall_keep_workspace),
                role = UserDecisionRole.PRIMARY),
            UserDecisionOption(OPTION_DELETE_WORKSPACE, linuxText(context, R.string.full_linux_uninstall_delete_workspace),
                role = UserDecisionRole.DESTRUCTIVE),
            UserDecisionOption(OPTION_CANCEL, linuxText(context, R.string.full_linux_uninstall_cancel)),
        ), allowDismiss = false)

    private fun requestDecision(spec: UserDecisionSpec, token: CancellationToken): UserDecisionResult {
        if (!decisionAvailability()) return UserDecisionResult.Unavailable
        val options = spec.options.map { option -> linkedMapOf<String, Any>(
            "id" to option.id, "label" to option.label,
            "role" to when (option.role) {
                UserDecisionRole.PRIMARY -> "primary"
                UserDecisionRole.DESTRUCTIVE -> "destructive"
                UserDecisionRole.DEFAULT -> "default"
            },
        ) }
        val tool = UserDecisionTool(context, sessionId, decisionGate, decisionAvailability, decisionPresenter)
        val result = JSONObject(tool.execute(mapOf("title" to spec.title, "body" to spec.body,
            "options" to options, "allow_dismiss" to spec.allowDismiss), token).content)
        return when (result.optString("status")) {
            "selected" -> spec.options.firstOrNull { it.id == result.optString("option_id") }
                ?.let(UserDecisionResult::Selected) ?: UserDecisionResult.Unavailable
            "dismissed" -> UserDecisionResult.Dismissed
            "cancelled" -> UserDecisionResult.Cancelled
            else -> UserDecisionResult.Unavailable
        }
    }

    private companion object {
        const val OPTION_KEEP_WORKSPACE = "keep_workspace"
        const val OPTION_DELETE_WORKSPACE = "delete_workspace"
        const val OPTION_CANCEL = "cancel"
    }
}

/** Throttles only UI progress events; captured output and tool results remain unchanged. */
internal class LinuxExecProgress(
    private val sanitizer: LinuxOutputSanitizer,
    private val emit: (LinuxOutputStream, String) -> Unit,
    private val nowMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private data class StreamTail(
        val partial: StringBuilder = StringBuilder(),
        var lastLine: String = "",
        var sequence: Long = 0,
        var activitySequence: Long = 0,
    )
    private val tails = mapOf(LinuxOutputStream.STDOUT to StreamTail(), LinuxOutputStream.STDERR to StreamTail())
    private var sequence = 0L
    private var latestStream: LinuxOutputStream? = null
    private var latestLine = ""
    private var lastEmittedStream: LinuxOutputStream? = null
    private var lastEmittedLine = ""
    private var lastEmissionTime = Long.MIN_VALUE

    @Synchronized fun append(stream: LinuxOutputStream, chunk: String) {
        val tail = tails.getValue(stream)
        tail.activitySequence = ++sequence
        chunk.forEach { character ->
            if (character == '\n') {
                commit(stream, tail)
                tail.partial.setLength(0)
            } else tail.partial.append(character)
        }
        emitIfDue(force = false)
    }

    @Synchronized fun finish() {
        tails.entries.sortedBy { it.value.activitySequence }.forEach { (stream, tail) ->
            if (tail.partial.isNotEmpty()) {
                commit(stream, tail)
                tail.partial.setLength(0)
            }
        }
        emitIfDue(force = true)
    }

    private fun commit(stream: LinuxOutputStream, tail: StreamTail) {
        val line = tail.partial.toString().trim()
        if (line.isBlank()) return
        tail.lastLine = sanitizer.scrub(line).replace('\n', ' ').replace('\r', ' ')
        tail.sequence = ++sequence
        if (tail.sequence >= (latestStream?.let { tails.getValue(it).sequence } ?: Long.MIN_VALUE)) {
            latestStream = stream
            latestLine = tail.lastLine
        }
    }

    private fun emitIfDue(force: Boolean) {
        val stream = latestStream ?: return
        if (latestLine.isBlank() || (stream == lastEmittedStream && latestLine == lastEmittedLine)) return
        val now = nowMillis()
        if (!force && lastEmissionTime != Long.MIN_VALUE && now - lastEmissionTime < LINUX_PROGRESS_PRESENTATION_INTERVAL_MS) return
        emit(stream, latestLine)
        lastEmittedStream = stream
        lastEmittedLine = latestLine
        lastEmissionTime = now
    }

    private companion object {
        const val LINUX_PROGRESS_PRESENTATION_INTERVAL_MS = 200L
    }
}

private data class LinuxBoundedOutputResult(val text: String, val truncated: Boolean)

/** Keeps a budget-derived head and tail per stream and reports omitted UTF-8 bytes. */
private class LinuxBoundedOutput(maxChars: Int, private val sanitizer: LinuxOutputSanitizer) {
    private val totalCharsLimit = maxChars.coerceAtLeast(2)
    private val headLimit = totalCharsLimit / 2
    private val tailLimit = totalCharsLimit - headLimit
    private val head = StringBuilder(headLimit)
    private val tail = StringBuilder(tailLimit)
    private var totalCharacters = 0L
    private var totalBytes = 0L

    @Synchronized fun append(raw: String) {
        if (raw.isEmpty()) return
        totalCharacters += raw.length
        totalBytes += raw.toByteArray(StandardCharsets.UTF_8).size
        if (head.length < headLimit) {
            val takeLength = (headLimit - head.length).coerceAtMost(raw.length)
            val end = if (takeLength > 0 && takeLength < raw.length
                && Character.isHighSurrogate(raw[takeLength - 1]) && Character.isLowSurrogate(raw[takeLength])) takeLength - 1
                else takeLength
            head.append(raw, 0, end)
        }
        tail.append(raw)
        if (tail.length > tailLimit) {
            var remove = tail.length - tailLimit
            if (remove < tail.length && remove > 0 && Character.isLowSurrogate(tail[remove])) remove++
            tail.delete(0, remove.coerceAtMost(tail.length))
        }
    }

    @Synchronized fun finish(): LinuxBoundedOutputResult {
        val truncated = totalCharacters > totalCharsLimit
        if (!truncated) {
            val tailStart = (totalCharacters - tail.length).coerceAtLeast(0L).toInt()
            val overlap = (head.length - tailStart).coerceAtLeast(0).coerceAtMost(tail.length)
            return LinuxBoundedOutputResult(sanitizer.scrub(head.toString() + tail.substring(overlap)), false)
        }
        val retainedBytes = (head.toString() + tail.toString()).toByteArray(StandardCharsets.UTF_8).size
        val omittedBytes = (totalBytes - retainedBytes).coerceAtLeast(0L)
        return LinuxBoundedOutputResult(sanitizer.scrub(
            head.toString() + "\n…[$omittedBytes bytes omitted]…\n" + tail), true)
    }
}

private fun linuxProbeJson(probe: LinuxProbeResult, sanitizer: LinuxOutputSanitizer) = JSONObject()
    .put("status", probe.status.name)
    .put("ready", probe.ready)
    .put("stdout", sanitizer.scrub(probe.stdout))
    .put("stderr", sanitizer.scrub(probe.stderr))
    .put("exit_code", probe.exitCode ?: JSONObject.NULL)
    .put("architecture", probe.architecture)
    .put("uid", probe.uid)
    .put("os_release", sanitizer.scrub(probe.osRelease))
    .put("error", sanitizer.scrub(probe.error))
    .put("failure_class", probe.failureClass ?: "")
    .put("failure_errno", probe.failureErrno ?: JSONObject.NULL)
    .put("failure_function", probe.failureFunction ?: "")
