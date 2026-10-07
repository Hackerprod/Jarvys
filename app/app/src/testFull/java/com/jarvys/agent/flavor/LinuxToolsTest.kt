package com.jarvys.agent.flavor

import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CorePromptBudget
import com.jarvys.agent.CoreTool
import com.jarvys.agent.R
import com.jarvys.agent.UserDecisionGate
import com.jarvys.agent.UserDecisionOption
import com.jarvys.agent.UserDecisionPresenter
import com.jarvys.agent.UserDecisionResult
import com.jarvys.agent.UserDecisionSpec
import com.jarvys.agent.connectors.ApprovalDecision
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ApprovalPresenter
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.linux.LinuxExecResult
import com.jarvys.agent.linux.LinuxInstallPhase
import com.jarvys.agent.linux.LinuxInstallState
import com.jarvys.agent.linux.LinuxOutputCallback
import com.jarvys.agent.linux.LinuxOutputStream
import com.jarvys.agent.linux.LinuxOutputSanitizer
import com.jarvys.agent.linux.LinuxProbeResult
import com.jarvys.agent.linux.LinuxProbeStatus
import com.jarvys.agent.linux.LinuxRuntime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinuxToolsTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val budget = CorePromptBudget(4096, 256, 1, 256, 4096)

    @Test fun setupRequiresExplicitConsentBeforeAnyInstallAndReportsProgressAndProbe() {
        val runtime = FakeLinuxRuntime()
        val gate = UserDecisionGate()
        var presented: UserDecisionSpec? = null
        val presenter = object : UserDecisionPresenter {
            override fun isAvailable() = true
            override fun show(id: String, spec: UserDecisionSpec) {
                presented = spec
                gate.resolve(id, UserDecisionResult.Selected(spec.options.first { it.id == "install" }))
            }
            override fun update(id: String, result: UserDecisionResult) = Unit
        }
        val tools = create(runtime, decisionGate = gate, decisionPresenter = presenter)
        val progress = mutableListOf<String>()

        val result = tools.getValue("linux_setup").execute(emptyMap(), CancellationToken.cancellable()) {
            progress += it
        }

        assertTrue(result.content, result.success)
        assertEquals(1, runtime.installCalls)
        assertEquals("ready", JSONObject(result.content).getString("status"))
        assertTrue(presented!!.body.contains("Ubuntu Base"))
        assertTrue(presented!!.body.contains("arm64"))
        assertEquals(listOf("install", "later"), presented!!.options.map { it.id })
        assertFalse(presented!!.allowDismiss)
        assertEquals(0, presented!!.options.count { it.id.contains("always", ignoreCase = true) })
        assertTrue(progress.any { it.contains("Downloading") })
        assertTrue(progress.any { it.contains("Extracting") })
        assertTrue(progress.any { it.contains("Checking") })
        assertTrue(runtime.probeCalls > 0)
        assertTrue(runtime.probeMarkedReady)
    }

    @Test fun linuxStatusUsesDistroVersionAndCodenameFromInstalledRootfsMetadata() {
        val status = JSONObject(create(FakeLinuxRuntime(ready = true)).getValue("linux_status")
            .execute(emptyMap(), CancellationToken.cancellable()).content)
        assertEquals("ubuntu", status.getString("distro"))
        assertEquals("24.04", status.getString("version"))
        assertEquals("24.04", status.getString("version_id"))
        assertEquals("noble", status.getString("version_codename"))
        assertEquals("24.04.5", status.getString("catalog_version"))
    }

    @Test fun setupUnavailableDismissedAndCancelledNeverStartDownload() {
        val notNow = decisionPresenter("later")
        val cancelled = decisionPresenter("__cancel__")
        for (scenario in listOf(notNow, cancelled)) {
            val runtime = FakeLinuxRuntime()
            val tool = create(runtime, decisionGate = scenario.first, decisionPresenter = scenario.second)
                .getValue("linux_setup")
            val result = tool.execute(emptyMap(), CancellationToken.cancellable())
            assertFalse(result.success)
            assertEquals(0, runtime.installCalls)
        }

        val runtime = FakeLinuxRuntime()
        val unavailable = create(runtime, decisionAvailability = { false }).getValue("linux_setup")
            .execute(emptyMap(), CancellationToken.cancellable())
        assertFalse(unavailable.success)
        assertEquals(0, runtime.installCalls)

        val attemptedByModel = create(runtime, decisionAvailability = { true }).getValue("linux_setup")
            .execute(mapOf("confirm" to true), CancellationToken.cancellable())
        assertFalse(attemptedByModel.success)
        assertEquals(0, runtime.installCalls)
    }

    @Test fun commandNeedsOneExactPerCallApprovalAndSanitizesInterleavedOutput() {
        val runtime = FakeLinuxRuntime(ready = true)
        val hostPath = context.filesDir.absolutePath
        val command = "printf 'approval literal must remain exact: " + "x".repeat(140) + "' && id"
        var summary: ApprovalSummary? = null
        var gate: ApprovalGate? = null
        gate = ApprovalGate(2_000, object : ApprovalPresenter {
            override fun show(id: String, value: ApprovalSummary) {
                summary = value
                gate!!.resolve(id, ApprovalDecision.APPROVED)
            }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        val tools = create(runtime, approvalGate = gate!!)
        val progress = mutableListOf<String>()
        val result = tools.getValue("linux_exec").execute(mapOf(
            "command" to command, "cwd" to "/workspace/project", "timeout_seconds" to 17,
        ), CancellationToken.cancellable()) { progress += it }

        assertTrue(result.content, result.success)
        assertEquals(command, runtime.lastCommand)
        assertEquals("/workspace/project", runtime.lastCwd)
        assertEquals(17_000L, runtime.lastTimeout)
        assertEquals(1, runtime.execCalls)
        assertTrue(summary!!.allowAlwaysAvailable)
        assertEquals("linux", summary!!.autonomyConnectorId)
        assertEquals("linux_exec", summary!!.autonomyOperationName)
        assertTrue(summary!!.lines.any { it.contains(command) })
        assertTrue(summary!!.lines.any { it.contains("/workspace/project") })
        assertTrue(summary!!.lines.any { it.contains("PRoot is not a sandbox") })
        val output = JSONObject(result.content)
        assertEquals(7, output.getInt("exit_code"))
        assertEquals("first line\n[app-private path] data=Bearer [secret redacted]\n", output.getString("stdout"))
        assertTrue(output.getString("stdout").contains("[app-private path]"))
        assertTrue(output.getString("stdout").contains("[secret redacted]"))
        assertEquals("warning:stderr\n", output.getString("stderr"))
        assertEquals(context.getString(R.string.full_linux_exec_progress_running), progress.first())
        assertTrue(progress.contains("first line"))
        assertTrue(progress.last().contains("[app-private path]"))
        assertFalse(progress.any { it.contains(hostPath) })
    }

    @Test fun linuxExecProgressReportsSanitizedLastLineAndPrefixesOnlyStderrAtPresentationCadence() {
        val clock = AtomicLong(0L)
        val display = mutableListOf<String>()
        val progress = LinuxExecProgress(LinuxOutputSanitizer.fromFilesDir(context.filesDir), { stream, line ->
            display += if (stream == LinuxOutputStream.STDERR) "stderr: $line" else line
        }, clock::get)

        progress.append(LinuxOutputStream.STDOUT, "one\n")
        clock.set(200L)
        progress.append(LinuxOutputStream.STDERR, "warning\n")
        clock.set(400L)
        progress.append(LinuxOutputStream.STDOUT,
            "${context.filesDir.absolutePath}/private token=secretValue")
        progress.finish()

        assertEquals("one", display.first())
        assertTrue(display.contains("stderr: warning"))
        assertTrue(display.last().startsWith("[app-private path]"))
        assertTrue(display.last().contains("token=[secret redacted]"))
        assertFalse(display.last().contains("secretValue"))
    }

    @Test fun denialDoesNotExecuteAndResultBudgetPreservesHeadTailWithExactByteMarker() {
        val deniedRuntime = FakeLinuxRuntime(ready = true)
        val denyGate = approvalGate(ApprovalDecision.DENIED)
        val denied = create(deniedRuntime, approvalGate = denyGate).getValue("linux_exec")
            .execute(mapOf("command" to "id"), CancellationToken.cancellable())
        assertFalse(denied.success)
        assertEquals(0, deniedRuntime.execCalls)

        val runtime = FakeLinuxRuntime(ready = true, hugeOutput = true)
        val approved = approvalGate(ApprovalDecision.APPROVED)
        val bounded = create(runtime, approvalGate = approved, budget = CorePromptBudget(4096, 256, 1, 256, 1024))
            .getValue("linux_exec").execute(mapOf("command" to "printf output"), CancellationToken.cancellable())
        assertTrue(bounded.success)
        val output = JSONObject(bounded.content)
        assertTrue(output.getBoolean("truncated"))
        assertTrue(output.getString("stdout").startsWith("HEAD"))
        assertTrue(output.getString("stdout").endsWith("TAIL"))
        assertTrue(output.getString("stdout").contains("bytes omitted"))
        assertTrue(output.getString("stdout").length < 400)
    }

    @Test fun setupFailureIsLocalizedCopyableAndStatusExposesSanitizedPersistedDetail() {
        val hostPath = context.filesDir.absolutePath
        val detail = "phase=EXTRACTING; exception=android.system.ErrnoException; message=open failed at $hostPath; errno=EACCES; function=open; tar_entry=etc/os-release; tar_type=regular(0); tar_mode=0644; entries_processed=0"
        val failedState = LinuxInstallState(phase = LinuxInstallPhase.FAILED,
            bytes = 29_936_675L, total = 29_936_675L, failureCode = "extract_failed",
            failureDetail = detail, degradedEntries = 2, hardlinksCopied = 2)
        val runtime = FakeLinuxRuntime(installationFailure = failedState)
        val gate = UserDecisionGate()
        val presenter = object : UserDecisionPresenter {
            override fun isAvailable() = true
            override fun show(id: String, spec: UserDecisionSpec) {
                gate.resolve(id, UserDecisionResult.Selected(spec.options.first { it.id == "install" }))
            }
            override fun update(id: String, result: UserDecisionResult) = Unit
        }
        val tools = create(runtime, decisionGate = gate, decisionPresenter = presenter)
        val result = tools.getValue("linux_setup").execute(emptyMap(), CancellationToken.cancellable())

        assertFalse(result.success)
        assertTrue(result.content.startsWith("Linux setup failed."))
        assertTrue(result.content.endsWith("phase=EXTRACTING; exception=android.system.ErrnoException" +
            "; message=open failed at [app-private path]; errno=EACCES; function=open; tar_entry=etc/os-release" +
            "; tar_type=regular(0); tar_mode=0644; entries_processed=0"))
        assertFalse(result.content.contains(hostPath))
        val status = JSONObject(tools.getValue("linux_status").execute(emptyMap(), CancellationToken.cancellable()).content)
        assertEquals("extract_failed", status.getString("failure_code"))
        assertEquals(2, status.getInt("degraded_entries"))
        assertEquals(2, status.getInt("hardlinks_copied"))
        assertTrue(status.getString("failure_detail").contains("function=open"))
        assertFalse(status.getString("failure_detail").contains(hostPath))
    }

    @Test fun uninstallChoiceDeterminesWorkspaceDeletionAndDismissalDoesNothing() {
        val preserveRuntime = FakeLinuxRuntime(ready = true)
        val preservePair = decisionPresenter("keep_workspace")
        val preserve = create(preserveRuntime, decisionGate = preservePair.first,
            decisionPresenter = preservePair.second).getValue("linux_uninstall")
            .execute(emptyMap(), CancellationToken.cancellable())
        assertTrue(preserve.success)
        assertEquals(1, preserveRuntime.uninstallCalls)
        assertEquals(0, preserveRuntime.workspaceDeleteCalls)

        val deleteRuntime = FakeLinuxRuntime(ready = true)
        val deletePair = decisionPresenter("delete_workspace")
        val delete = create(deleteRuntime, decisionGate = deletePair.first, decisionPresenter = deletePair.second)
            .getValue("linux_uninstall").execute(emptyMap(), CancellationToken.cancellable())
        assertTrue(delete.success)
        assertEquals(1, deleteRuntime.uninstallCalls)
        assertEquals(1, deleteRuntime.workspaceDeleteCalls)

        val cancelRuntime = FakeLinuxRuntime(ready = true)
        val cancelPair = decisionPresenter("cancel")
        val cancelled = create(cancelRuntime, decisionGate = cancelPair.first, decisionPresenter = cancelPair.second)
            .getValue("linux_uninstall").execute(emptyMap(), CancellationToken.cancellable())
        assertFalse(cancelled.success)
        assertEquals(0, cancelRuntime.uninstallCalls)
    }

    private fun create(
        runtime: LinuxRuntime,
        approvalGate: ApprovalGate = approvalGate(ApprovalDecision.APPROVED),
        decisionGate: UserDecisionGate = UserDecisionGate(),
        decisionPresenter: UserDecisionPresenter? = null,
        decisionAvailability: () -> Boolean = { true },
        budget: CorePromptBudget = this.budget,
    ): Map<String, CoreTool> = FlavorLinuxTools.createForRuntime(context, "linux-tool-test", 0, budget,
        runtime, approvalGate, decisionGate, decisionPresenter, decisionAvailability)
        .associateBy { it.declaration().name }

    private fun decisionPresenter(optionId: String?): Pair<UserDecisionGate, UserDecisionPresenter> {
        val gate = UserDecisionGate()
        val presenter = object : UserDecisionPresenter {
            override fun isAvailable() = true
            override fun show(id: String, spec: UserDecisionSpec) {
                when (optionId) {
                    null -> gate.resolve(id, UserDecisionResult.Dismissed)
                    "__cancel__" -> gate.resolve(id, UserDecisionResult.Cancelled)
                    else -> gate.resolve(id, UserDecisionResult.Selected(spec.options.first { it.id == optionId }))
                }
            }
            override fun update(id: String, result: UserDecisionResult) = Unit
        }
        return gate to presenter
    }

    private fun approvalGate(decision: ApprovalDecision): ApprovalGate {
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(2_000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) { gate.resolve(id, decision) }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        return gate
    }

    private class FakeLinuxRuntime(
        ready: Boolean = false,
        private val hugeOutput: Boolean = false,
        private val installationFailure: LinuxInstallState? = null,
    ) : LinuxRuntime {
        private val hostPath = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir.absolutePath
        private var current = if (ready) LinuxInstallState(phase = LinuxInstallPhase.READY,
            version = "24.04.5", distro = "ubuntu", versionId = "24.04", codename = "noble",
            arch = "arm64", sizeBytes = 1234L) else LinuxInstallState()
        var installCalls = 0
        var probeCalls = 0
        var probeMarkedReady = false
        var execCalls = 0
        var uninstallCalls = 0
        var workspaceDeleteCalls = 0
        var lastCommand = ""
        var lastCwd = ""
        var lastTimeout: Long? = null

        override fun state() = current
        override fun availableBytes() = 8_000_000_000L
        override fun supportsArm64() = true
        override fun workspacePath() = "/private/linux/workspace"
        override fun observe(observer: (LinuxInstallState) -> Unit): AutoCloseable = AutoCloseable { }
        override fun installForProbe(progress: (LinuxInstallState) -> Unit, token: CancellationToken): LinuxInstallState {
            token.throwIfCancelled()
            installCalls++
            installationFailure?.let {
                current = it
                progress(current)
                return current
            }
            listOf(
                LinuxInstallState(LinuxInstallPhase.DOWNLOADING, bytes = 512L, total = 1024L),
                LinuxInstallState(LinuxInstallPhase.VERIFYING),
                LinuxInstallState(LinuxInstallPhase.EXTRACTING, extractedEntries = 3),
                LinuxInstallState(LinuxInstallPhase.PATCHING),
            ).forEach(progress)
            current = LinuxInstallState(LinuxInstallPhase.READY, version = "24.04.5", distro = "ubuntu",
                versionId = "24.04", codename = "noble", arch = "arm64", sizeBytes = 1234L)
            progress(current)
            return current
        }
        override fun probe(): LinuxProbeResult { probeCalls++; return LinuxProbeResult(
            LinuxProbeStatus.READY, "aarch64\n0\nUbuntu 24.04\n", exitCode = 0,
            architecture = "aarch64", uid = "0", osRelease = "Ubuntu 24.04") }
        override fun markProbeFailure(code: String, detail: String) {
            current = current.copy(phase = LinuxInstallPhase.FAILED, failureCode = code, failureDetail = detail)
        }
        override fun markProbeSuccess() { probeMarkedReady = true }
        override fun uninstall() { uninstallCalls++; current = LinuxInstallState() }
        override fun deleteWorkspace() { workspaceDeleteCalls++ }
        override fun exec(command: String, cwd: String, timeoutMillis: Long?, callback: LinuxOutputCallback,
                          token: CancellationToken): LinuxExecResult {
            token.throwIfCancelled()
            execCalls++
            lastCommand = command
            lastCwd = cwd
            lastTimeout = timeoutMillis
            if (hugeOutput) {
                callback.onChunk(LinuxOutputStream.STDOUT, "HEAD" + "x".repeat(3000) + "TAIL")
            } else {
                callback.onChunk(LinuxOutputStream.STDOUT, "first ")
                callback.onChunk(LinuxOutputStream.STDERR, "warning:")
                callback.onChunk(LinuxOutputStream.STDOUT, "line\n$hostPath data=Bearer ")
                callback.onChunk(LinuxOutputStream.STDERR, "stderr\n")
                callback.onChunk(LinuxOutputStream.STDOUT, "secretValue\n")
            }
            return LinuxExecResult(7)
        }
    }
}
