package com.jarvys.agent.linux

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RootfsInspectorTest {
    @Test fun followsGuestAbsoluteAndRelativeLinksAndReadsRealOsReleaseFields() {
        val sandbox = tempDir()
        try {
            val root = File(sandbox, "rootfs").apply { mkdirs() }
            File(root, "usr/bin").mkdirs()
            File(root, "usr/lib").mkdirs()
            File(root, "etc").mkdirs()
            executable(File(root, "usr/bin/dash"), elf(2, 183))
            File(root, "usr/lib/os-release").writeText("ID=ubuntu\nVERSION_ID=24.04\nVERSION_CODENAME=noble\n")
            Files.createSymbolicLink(File(root, "bin").toPath(), java.nio.file.Paths.get("/usr/bin"))
            Files.createSymbolicLink(File(root, "usr/bin/sh").toPath(), java.nio.file.Paths.get("dash"))
            Files.createSymbolicLink(File(root, "etc/os-release").toPath(), java.nio.file.Paths.get("../usr/lib/os-release"))

            val inspection = RootfsInspector.inspect(root, "arm64", NioFileKindReader,
                readGuestLink = { Files.readSymbolicLink(it.toPath()).toString() })

            assertEquals("ubuntu", inspection.distro)
            assertEquals("24.04", inspection.versionId)
            assertEquals("noble", inspection.codename)
            assertEquals(183, inspection.machine)
        } finally { remove(sandbox) }
    }

    @Test fun rejects32BitX86AndNonElfGuestShellsWithMachineDiagnostic() {
        for ((name, binary, expectedMachine) in listOf(
            Triple("arm32", elf(1, 40), "40"),
            Triple("x86_64", elf(2, 62), "62"),
            Triple("not-elf", byteArrayOf(1, 2, 3, 4), "unknown"),
        )) {
            val sandbox = tempDir()
            try {
                val root = File(sandbox, "rootfs").apply { mkdirs() }
                executable(File(root, "bin/sh"), binary)
                File(root, "etc").mkdirs()
                File(root, "etc/os-release").writeText("ID=ubuntu\n")
                val failure = runCatching { RootfsInspector.inspect(root, "arm64", NioFileKindReader) }.exceptionOrNull()
                assertTrue("$name: $failure", failure?.message.orEmpty().contains("rootfs architecture does not match arm64"))
                assertTrue("$name: $failure", failure?.message.orEmpty().contains("e_machine=$expectedMachine"))
            } finally { remove(sandbox) }
        }
    }

    private fun elf(elfClass: Int, machine: Int) = ByteArray(64).apply {
        this[0] = 0x7f.toByte(); this[1] = 'E'.code.toByte(); this[2] = 'L'.code.toByte(); this[3] = 'F'.code.toByte()
        this[4] = elfClass.toByte(); this[5] = 1
        this[18] = machine.toByte(); this[19] = (machine shr 8).toByte()
    }

    private fun executable(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        file.setExecutable(true, false)
    }

    private fun tempDir() = File(System.getProperty("java.io.tmpdir"), "lk1-inspector-${UUID.randomUUID()}")
        .apply { check(mkdirs()) }

    private fun remove(file: File) {
        val temp = File(System.getProperty("java.io.tmpdir")).canonicalFile.path
        val target = file.canonicalFile.path
        check(target == temp || target.startsWith(temp + File.separator))
        RootfsInstaller.deletePrivateTree(file, NioFileKindReader)
    }
}
