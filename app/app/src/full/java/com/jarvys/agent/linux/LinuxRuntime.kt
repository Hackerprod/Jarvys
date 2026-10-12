package com.jarvys.agent.linux

import com.jarvys.agent.CancellationToken

/** Small Full-only seam consumed by agent tools and replaced by deterministic test fakes. */
interface LinuxRuntime {
    fun state(): LinuxInstallState
    fun availableBytes(): Long
    fun supportsArm64(): Boolean
    fun workspacePath(): String
    fun rootfsPath(): String = java.io.File(java.io.File(workspacePath()).parentFile, "rootfs").path
    fun observe(observer: (LinuxInstallState) -> Unit): AutoCloseable
    fun installForProbe(progress: (LinuxInstallState) -> Unit, token: CancellationToken): LinuxInstallState
    fun probe(): LinuxProbeResult
    fun markProbeFailure(code: String, detail: String)
    fun markProbeSuccess()
    fun uninstall()
    fun deleteWorkspace()
    /** Fake-compatible effect seams; production rechecks after internal lock/credential waits. */
    fun installForProbeAdmitted(progress: (LinuxInstallState) -> Unit, token: CancellationToken,
                                beforeEffect: () -> Unit): LinuxInstallState {
        token.throwIfCancelled(); beforeEffect(); token.throwIfCancelled()
        return installForProbe(progress, token)
    }
    fun probeAdmitted(token: CancellationToken, beforeEffect: () -> Unit): LinuxProbeResult {
        token.throwIfCancelled(); beforeEffect(); token.throwIfCancelled()
        return probe().also { token.throwIfCancelled() }
    }
    fun markProbeFailureAdmitted(code: String, detail: String, beforeEffect: () -> Unit) {
        beforeEffect(); markProbeFailure(code, detail)
    }
    fun markProbeSuccessAdmitted(beforeEffect: () -> Unit) { beforeEffect(); markProbeSuccess() }
    fun uninstallAdmitted(beforeEffect: () -> Unit) { beforeEffect(); uninstall() }
    fun deleteWorkspaceAdmitted(beforeEffect: () -> Unit) { beforeEffect(); deleteWorkspace() }

    fun exec(command: String, cwd: String, timeoutMillis: Long?, callback: LinuxOutputCallback,
             token: CancellationToken): LinuxExecResult
    /** Production overrides this seam to admit immediately before process creation, after preparation. */
    fun execAdmitted(command: String, cwd: String, timeoutMillis: Long?, callback: LinuxOutputCallback,
                     token: CancellationToken, beforeLaunch: () -> Unit): LinuxExecResult {
        token.throwIfCancelled()
        beforeLaunch()
        token.throwIfCancelled()
        return exec(command, cwd, timeoutMillis, callback, token)
    }
    fun projectStatus(scope: com.jarvys.agent.coding.ProjectScope, cwd: String,
                      requiredTools: List<String> = emptyList()): LinuxProjectStatus =
        LinuxProjectStatus(false, "Project execution is unavailable in this runtime.")
    fun execProject(scope: com.jarvys.agent.coding.ProjectScope, command: String, cwd: String,
                    timeoutMillis: Long?, callback: LinuxOutputCallback, token: CancellationToken,
                    beforeLaunch: () -> Unit): LinuxExecResult =
        throw UnsupportedOperationException("Project execution is unavailable in this runtime.")
}
