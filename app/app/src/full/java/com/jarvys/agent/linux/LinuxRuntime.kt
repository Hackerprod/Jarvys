package com.jarvys.agent.linux

import com.jarvys.agent.CancellationToken

/** Small Full-only seam consumed by agent tools and replaced by deterministic test fakes. */
interface LinuxRuntime {
    fun state(): LinuxInstallState
    fun availableBytes(): Long
    fun supportsArm64(): Boolean
    fun workspacePath(): String
    fun observe(observer: (LinuxInstallState) -> Unit): AutoCloseable
    fun installForProbe(progress: (LinuxInstallState) -> Unit, token: CancellationToken): LinuxInstallState
    fun probe(): LinuxProbeResult
    fun markProbeFailure(code: String, detail: String)
    fun markProbeSuccess()
    fun uninstall()
    fun deleteWorkspace()
    fun exec(command: String, cwd: String, timeoutMillis: Long?, callback: LinuxOutputCallback,
             token: CancellationToken): LinuxExecResult
    fun projectStatus(scope: com.jarvys.agent.coding.ProjectScope, cwd: String,
                      requiredTools: List<String> = emptyList()): LinuxProjectStatus =
        LinuxProjectStatus(false, "Project execution is unavailable in this runtime.")
    fun execProject(scope: com.jarvys.agent.coding.ProjectScope, command: String, cwd: String,
                    timeoutMillis: Long?, callback: LinuxOutputCallback, token: CancellationToken,
                    beforeLaunch: () -> Unit): LinuxExecResult =
        throw UnsupportedOperationException("Project execution is unavailable in this runtime.")
}
