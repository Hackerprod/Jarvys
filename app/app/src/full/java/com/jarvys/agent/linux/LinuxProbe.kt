package com.jarvys.agent.linux

import android.os.Build
import android.system.ErrnoException
import java.io.File

enum class LinuxProbeStatus { READY, NOT_INSTALLED, UNSUPPORTED_ABI, ARCH_MISMATCH, BINARY_MISSING, EXEC_DENIED, SELINUX_DENIED, COMMAND_FAILED }

data class LinuxProbeResult(
    val status: LinuxProbeStatus,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val architecture: String = "",
    val uid: String = "",
    val osRelease: String = "",
    val error: String = "",
    val failureClass: String? = null,
    val failureErrno: Int? = null,
    val failureFunction: String? = null,
) {
    val ready: Boolean get() = status == LinuxProbeStatus.READY
}

fun interface LinuxProbeExecutor { fun execute(command: String, callback: LinuxOutputCallback): LinuxExecResult }

/** E0 check and a structured, lossless view of command output for device-side failure reports. */
class LinuxProbe(
    private val rootfs: File,
    private val nativeLibraryDir: File,
    private val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    private val executor: LinuxProbeExecutor,
    private val output: (LinuxOutputStream, String) -> Unit = { _, _ -> },
    private val makeExecutable: (File) -> Boolean = { it.setExecutable(true, false) },
) {
    fun probe(): LinuxProbeResult {
        if (!rootfs.isDirectory) return LinuxProbeResult(LinuxProbeStatus.NOT_INSTALLED, error = "rootfs not installed")
        if ("arm64-v8a" !in supportedAbis) return LinuxProbeResult(
            LinuxProbeStatus.UNSUPPORTED_ABI, error = "Unsupported device ABI; arm64-v8a is required")
        val proot = File(nativeLibraryDir, "libproot_exec.so")
        if (!proot.isFile) return LinuxProbeResult(LinuxProbeStatus.BINARY_MISSING, error = "proot absent: ${proot.absolutePath}")
        runCatching { makeExecutable(proot) }
        if (!proot.canExecute()) return LinuxProbeResult(LinuxProbeStatus.EXEC_DENIED,
            error = "proot not executable: ${proot.absolutePath}")
        val loader = File(nativeLibraryDir, "libproot_loader.so")
        if (!loader.isFile) return LinuxProbeResult(LinuxProbeStatus.BINARY_MISSING, error = "loader absent: ${loader.absolutePath}")
        runCatching { makeExecutable(loader) }
        if (!loader.canExecute()) return LinuxProbeResult(LinuxProbeStatus.EXEC_DENIED,
            error = "loader not executable: ${loader.absolutePath}")
        for (name in REQUIRED_BINARIES - setOf("libproot_exec.so", "libproot_loader.so")) {
            val binary = File(nativeLibraryDir, name)
            if (!binary.isFile) return LinuxProbeResult(LinuxProbeStatus.BINARY_MISSING,
                error = "binary absent: ${binary.absolutePath}")
        }
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        return try {
            val result = executor.execute(PROBE_COMMAND, LinuxOutputCallback { stream, text ->
                output(stream, text)
                if (stream == LinuxOutputStream.STDOUT) stdout.append(text) else stderr.append(text)
            })
            val out = stdout.toString()
            val err = stderr.toString()
            if (result.exitCode != 0) {
                val status = when {
                    err.contains("selinux", true) || err.contains("avc: denied", true) -> LinuxProbeStatus.SELINUX_DENIED
                    err.contains("Permission denied", true) || err.contains("exec format", true) -> LinuxProbeStatus.EXEC_DENIED
                    err.contains("loader", true) && err.contains("not found", true) -> LinuxProbeStatus.BINARY_MISSING
                    else -> LinuxProbeStatus.COMMAND_FAILED
                }
                LinuxProbeResult(status, out, err, result.exitCode, error = err,
                    failureClass = "${LinuxExecResult::class.java.name} (exit=${result.exitCode})")
            } else {
                val lines = out.lineSequence().toList()
                val arch = lines.getOrNull(0).orEmpty()
                val uid = lines.getOrNull(1).orEmpty()
                val release = lines.drop(2).takeWhile { it != "ok" }.joinToString("\n")
                val incomplete = "ok" !in lines || arch.isBlank() || uid.isBlank() || release.isBlank()
                when {
                    incomplete -> LinuxProbeResult(LinuxProbeStatus.COMMAND_FAILED, out, err, result.exitCode,
                        arch, uid, release, err.ifBlank { "probe output incomplete" })
                    arch != "aarch64" -> LinuxProbeResult(LinuxProbeStatus.ARCH_MISMATCH, out, err, result.exitCode,
                        arch, uid, release, err.ifBlank { "guest architecture mismatch: $arch" })
                    uid != "0" -> LinuxProbeResult(LinuxProbeStatus.COMMAND_FAILED, out, err, result.exitCode,
                        arch, uid, release, err.ifBlank { "guest uid is not root: $uid" })
                    else -> LinuxProbeResult(LinuxProbeStatus.READY, out, err, result.exitCode, arch, uid, release)
                }
            }
        } catch (failure: Throwable) {
            val raw = failure.message.orEmpty()
            val exact = if (raw.contains("Cannot prepare libtalloc", ignoreCase = true))
                "$raw; launcher preparation failed; rootfs is not implicated and does not need reinstalling" else raw
            val status = when {
                exact.contains("selinux", true) || exact.contains("avc: denied", true) -> LinuxProbeStatus.SELINUX_DENIED
                exact.contains("Permission denied", true) || exact.contains("exec denied", true) -> LinuxProbeStatus.EXEC_DENIED
                exact.contains("loader", true) || exact.contains("not found", true) -> LinuxProbeStatus.BINARY_MISSING
                else -> LinuxProbeStatus.COMMAND_FAILED
            }
            val errno = generateSequence(failure) { it.cause }.filterIsInstance<ErrnoException>().firstOrNull()
            LinuxProbeResult(status, stdout.toString(), stderr.toString(), error = exact,
                failureClass = failure.javaClass.name, failureErrno = errno?.errno,
                failureFunction = errno?.let(LinuxFailureDiagnostics::functionName))
        }
    }

    companion object {
        const val PROBE_COMMAND = "uname -m; id -u; grep -E '^(ID|VERSION_ID|VERSION_CODENAME)=' /etc/os-release; echo ok"
        val REQUIRED_BINARIES = listOf("libproot_exec.so", "libproot_loader.so", "libtalloc.so", "libandroid-shmem.so")
    }
}
