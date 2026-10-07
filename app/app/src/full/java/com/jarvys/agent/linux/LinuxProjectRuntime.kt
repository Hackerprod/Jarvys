package com.jarvys.agent.linux

import android.system.Os
import com.jarvys.agent.coding.ProjectScope
import java.io.File
import java.io.IOException

data class LinuxProjectStatus(
    val ready: Boolean,
    val reason: String = "",
    val tools: Map<String, Boolean> = emptyMap(),
    val probe: String = "not_run",
    val deviceAbis: List<String> = emptyList(),
    val preparedArchitecture: String = "",
    val requiredAbi: String = "arm64-v8a",
)

/** File inspection only: neither status nor cwd validation executes guest code. */
object LinuxProjectPaths {
    val commonTools = listOf("git", "python3", "node", "npm", "java", "javac", "make", "cmake", "gcc", "gradle")
    val guestPaths = listOf("/usr/local/sbin", "/usr/local/bin", "/usr/sbin", "/usr/bin", "/sbin", "/bin")

    fun cwd(scope: ProjectScope, relative: String): String {
        val normalized = scope.normalizePath(relative)
        if (!scope.resolve(normalized).isDirectory) throw IOException("Project cwd is not an existing directory")
        scope.validatedMountRoot()
        return if (normalized == ".") "/workspace" else "/workspace/$normalized"
    }

    fun inspect(rootfs: File, native: File, scope: ProjectScope, relative: String, supported: Boolean,
                state: LinuxInstallState, fileKindReader: LinuxFileKindReader = AndroidLinuxFileKindReader,
                readLink: (File) -> String = { Os.readlink(it.absolutePath) },
                requiredTools: List<String> = emptyList(), deviceAbis: List<String> = emptyList()): LinuxProjectStatus {
        cwd(scope, relative)
        require(requiredTools.all { it.matches(Regex("[A-Za-z0-9_.+-]+")) }) { "Invalid executable name" }
        fun status(ready: Boolean, reason: String = "", tools: Map<String, Boolean> = emptyMap(), probe: String = "not_run") =
            LinuxProjectStatus(ready, reason, tools, probe, deviceAbis, state.arch)
        if (!supported) return status(false, "This prepared runtime requires arm64-v8a; no command was launched.")
        if (state.phase != LinuxInstallPhase.READY || !rootfs.isDirectory)
            return status(false, "Prepare and verify the environment from the principal chat first; no command was launched.")
        for (name in LinuxProbe.REQUIRED_BINARIES) {
            val binary = File(native, name)
            if (!binary.isFile || (name !in setOf("libtalloc.so", "libandroid-shmem.so") && !binary.canExecute()))
                return status(false, "Prepared runtime binary is absent or not executable: $name")
        }
        fun executable(path: String) = ProotLauncher.resolveGuestExecutable(rootfs, path, fileKindReader, readLink) != null
        if (!executable("/bin/bash") && !executable("/bin/sh")) return status(false, "Prepared runtime has no executable shell.")
        val tools = (commonTools + requiredTools).distinct().associateWith { tool -> guestPaths.any { executable("$it/$tool") } }
        return status(true, tools = tools, probe = "required_at_approved_launch")
    }
}
