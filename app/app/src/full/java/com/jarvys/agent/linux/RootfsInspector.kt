package com.jarvys.agent.linux

import java.io.File
import java.io.IOException

data class RootfsInspection(val distro: String, val versionId: String, val codename: String, val machine: Int)

object RootfsInspector {
    fun inspect(rootfs: File, expectedArchitecture: String,
                fileKindReader: LinuxFileKindReader = AndroidLinuxFileKindReader,
                readGuestLink: (File) -> String = { android.system.Os.readlink(it.absolutePath) }): RootfsInspection {
        val shellPath = ProotLauncher.resolveGuestExecutable(rootfs, "/bin/sh", fileKindReader, readGuestLink)
            ?: run {
                val bin = File(rootfs, "bin")
                val shell = File(rootfs, "bin/sh")
                throw IOException("Rootfs architecture does not match $expectedArchitecture: executable /bin/sh is missing " +
                    "(bin_kind=${fileKindReader.kind(bin)}, shell_kind=${fileKindReader.kind(shell)}, " +
                    "shell_mode=${LinuxFileModes.mode(shell)}, shell_permissions=${LinuxFileModes.permissions(shell)}, " +
                    "can_execute=${shell.canExecute()})")
            }
        val shell = File(rootfs, shellPath.removePrefix("/"))
        val header = ByteArray(20)
        val read = shell.inputStream().use { it.read(header) }
        val expectedMachine = when (expectedArchitecture.lowercase()) {
            "arm64", "arm64-v8a", "aarch64" -> 183
            "amd64", "x86_64" -> 62
            else -> null
        }
        val machine = if (read >= 20 && header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() && header[5] == 1.toByte()) {
            (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        } else null
        val classMatches = read >= 5 && when (expectedArchitecture.lowercase()) {
            "arm64", "arm64-v8a", "aarch64" -> header[4] == 2.toByte()
            "amd64", "x86_64" -> header[4] == 2.toByte()
            else -> true
        }
        if (machine == null || expectedMachine == null || machine != expectedMachine || !classMatches)
            throw IOException("rootfs architecture does not match $expectedArchitecture (e_machine=${machine ?: "unknown"})")

        val osReleasePath = ProotLauncher.resolveGuestPath(rootfs, "/etc/os-release", requireExecutable = false,
            fileKindReader = fileKindReader, readGuestLink = readGuestLink)
        val osRelease = osReleasePath?.let { File(rootfs, it.removePrefix("/")) }
        if (osRelease == null || LinuxFileModes.mode(osRelease) != android.system.OsConstants.S_IFREG ||
            runCatching { osRelease.canonicalPath }.getOrNull()?.startsWith(rootfs.canonicalPath + File.separator) != true)
            throw IOException("Rootfs etc/os-release is missing or outside rootfs")
        val values = osRelease.readLines().mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split) to line.substring(split + 1).trim().trim('"', '\'')
        }.toMap()
        return RootfsInspection(values["ID"].orEmpty(), values["VERSION_ID"].orEmpty(),
            values["VERSION_CODENAME"].orEmpty(), machine)
    }
}
