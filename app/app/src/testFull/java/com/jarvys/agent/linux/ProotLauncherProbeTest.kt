package com.jarvys.agent.linux

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProotLauncherProbeTest {
    @Test fun argvAndEnvironmentAreExactAndCommandRemainsOneUnmodifiedArgument() {
        val proot = "/app/lib/libproot_exec.so"
        val rootfs = "/data/files/rootfs"
        val workspace = "/data/files/workspace"
        val cwd = "/workspace"
        for (command in listOf(
            "printf '%s' \"quoted\"",
            "echo a; \$(touch /tmp/host)\nsecond line",
            "--help\nexit 7",
        )) {
            val argv = ProotLauncher.buildArgv(proot, rootfs, cwd, workspace, command)
            assertEquals(command, argv.last())
            assertEquals(1, argv.count { it == command })
            assertEquals(listOf(proot, "--root-id", "--link2symlink", "--kill-on-exit", "-r", rootfs,
                "-w", cwd, "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "$workspace:/workspace",
                "/usr/bin/env", "-i", "HOME=/root", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LANG=C.UTF-8", "/bin/bash", "-lc", "cd -- \"\$1\" && eval \"\$2\"", "jarvys", cwd, command), argv)
        }
        assertEquals(mapOf("PROOT_LOADER" to "/lib/loader", "PROOT_TMP_DIR" to "/tmp/proot",
            "TMPDIR" to "/tmp/proot", "LD_LIBRARY_PATH" to "/tmp/proot:/lib"),
            ProotLauncher.buildEnvironment("/lib/loader", "/tmp/proot", "/lib"))
    }

    @Test fun planResolvesGuestAbsoluteAndRelativeShellLinksCopiesTallocAndValidatesCwd() {
        val sandbox = tempDir()
        try {
            val rootfs = File(sandbox, "rootfs").apply { mkdirs() }
            val bin = File(rootfs, "usr/bin").apply { mkdirs() }
            File(bin, "bash").apply { writeText("#!/bin/sh\n"); setExecutable(true, false) }
            Files.createSymbolicLink(File(rootfs, "bin").toPath(), java.nio.file.Paths.get("/usr/bin"))
            val native = File(sandbox, "lib").apply { mkdirs() }
            val proot = File(native, "libproot_exec.so").apply { writeText("p") }
            val loader = File(native, "libproot_loader.so").apply { writeText("l") }
            val talloc = File(native, "libtalloc.so").apply { writeText("native talloc") }
            val workspace = File(sandbox, "workspace").apply { mkdirs() }
            val tmp = File(sandbox, "tmp").apply { mkdirs() }

            val plan = ProotLauncher.plan(proot, loader, rootfs, "/workspace", workspace, tmp, native, "echo '$1'",
                fileKindReader = NioFileKindReader,
                readGuestLink = { Files.readSymbolicLink(it.toPath()).toString() })

            assertEquals("/usr/bin/bash", plan.argv[plan.argv.indexOf("-lc") - 1])
            assertEquals("cd -- \"\$1\" && eval \"\$2\"", plan.argv[plan.argv.indexOf("-lc") + 1])
            assertEquals(listOf("jarvys", "/workspace", "echo '$1'"), plan.argv.takeLast(3))
            assertEquals(rootfs.parentFile, plan.workingDirectory)
            val libraryPath = plan.environment.getValue("LD_LIBRARY_PATH").substringBefore(':')
            val copied = File(libraryPath, "libtalloc.so.2")
            assertTrue(copied.isFile)
            assertFalse(Files.isSymbolicLink(copied.toPath()))
            assertEquals(talloc.readText(), copied.readText())
            assertEquals(native.absolutePath, plan.environment["LD_LIBRARY_PATH"]?.substringAfter(':'))
            assertTrue(libraryPath.startsWith(File(tmp, "lib-").absolutePath))
            assertFalse(plan.argv.contains("TERM=xterm-256color"))
            Files.delete(copied.toPath())
            Files.createSymbolicLink(copied.toPath(), talloc.toPath())
            ProotLauncher.plan(proot, loader, rootfs, "/workspace", workspace, tmp, native, "true",
                fileKindReader = NioFileKindReader,
                readGuestLink = { Files.readSymbolicLink(it.toPath()).toString() })
            assertFalse("current app-library copy must be regular", Files.isSymbolicLink(copied.toPath()))
            assertEquals(libraryPath, ProotLauncher.plan(proot, loader, rootfs, "/workspace", workspace, tmp, native, "true",
                fileKindReader = NioFileKindReader,
                readGuestLink = { Files.readSymbolicLink(it.toPath()).toString() })
                .environment.getValue("LD_LIBRARY_PATH").substringBefore(':'))

            val shellRoot = File(sandbox, "fallback-rootfs").apply { mkdirs() }
            File(shellRoot, "bin/sh").apply { parentFile.mkdirs(); writeText("#!/bin/sh\n"); setExecutable(true, false) }
            val fallback = ProotLauncher.plan(proot, loader, shellRoot, "/", workspace, File(sandbox, "fallback-tmp"),
                native, "true", fileKindReader = NioFileKindReader)
            assertEquals("/bin/sh", fallback.argv[fallback.argv.indexOf("-lc") - 1])
            assertTrue(runCatching { ProotLauncher.buildArgv("p", "r", "/a/../b", "w", "x") }.isFailure)
            assertTrue(runCatching { ProotLauncher.buildArgv("p", "r", "/a\u0000b", "w", "x") }.isFailure)
        } finally { remove(sandbox) }
    }

    @Test fun processTreeKillHandlesChildrenGrandchildrenAndPpidCycles() {
        val tree = FakeTree(mapOf(10 to 15, 11 to 10, 12 to 10, 13 to 11, 14 to 13, 15 to 10))
        val killer = ProcessTreeKiller(tree)
        val descendants = killer.descendants(10)
        assertEquals(setOf(11, 12, 13, 14, 15), descendants.toSet())
        killer.killTree(10)
        assertEquals(setOf(10, 11, 12, 13, 14, 15), tree.killed.toSet())
        assertEquals(6, tree.killed.size)
    }

    @Test fun uniqueTallocDirectoryIgnoresAndSafelyCleansLegacyEntries() {
        val sandbox = tempDir()
        try {
            val rootfs = File(sandbox, "rootfs").apply { mkdirs() }
            File(rootfs, "bin/bash").apply { parentFile.mkdirs(); writeText("#!/bin/sh\n"); setExecutable(true, false) }
            val native = File(sandbox, "native-current").apply { mkdirs() }
            val proot = File(native, "libproot_exec.so").apply { writeText("p") }
            val loader = File(native, "libproot_loader.so").apply { writeText("l") }
            File(native, "libtalloc.so").writeText("current talloc")
            val tmp = File(sandbox, "tmp").apply { mkdirs() }
            val missingOld = File(sandbox, "old-apk/nativeLibraryDir/libtalloc.so")
            Files.createSymbolicLink(File(tmp, "libtalloc.so.2").toPath(), missingOld.toPath())
            Files.createSymbolicLink(File(tmp, "libtalloc.so.2.copy").toPath(), missingOld.toPath())
            val stale = File(tmp, "lib-old-content").apply { mkdirs() }
            File(stale, "obsolete").writeText("stale")
            val outside = File(sandbox, "outside-sentinel").apply { writeText("keep") }
            Files.createSymbolicLink(File(stale, "outside-link").toPath(), outside.toPath())
            val plan = ProotLauncher.plan(proot, loader, rootfs, "/", sandbox, tmp, native, "true",
                fileKindReader = NioFileKindReader,
                readGuestLink = { Files.readSymbolicLink(it.toPath()).toString() })
            val selected = File(plan.environment.getValue("LD_LIBRARY_PATH").substringBefore(':'), "libtalloc.so.2")
            assertEquals("current talloc", selected.readText())
            assertEquals(native.absolutePath, plan.environment.getValue("LD_LIBRARY_PATH").substringAfter(':'))
            assertFalse(File(tmp, "libtalloc.so.2").exists())
            assertFalse(File(tmp, "libtalloc.so.2.copy").exists())
            assertFalse(stale.exists())
            assertEquals("keep", outside.readText())
            val selectedTimestamp = selected.lastModified()
            val repeated = ProotLauncher.plan(proot, loader, rootfs, "/", sandbox, tmp, native, "true",
                fileKindReader = NioFileKindReader,
                readGuestLink = { Files.readSymbolicLink(it.toPath()).toString() })
            val reused = File(repeated.environment.getValue("LD_LIBRARY_PATH").substringBefore(':'), "libtalloc.so.2")
            assertEquals(selected, reused)
            assertEquals(selectedTimestamp, reused.lastModified())
        } finally { remove(sandbox) }
    }

    @Test fun probeReportsPreflightFailuresAndKeepsExactCommandStderr() {
        val root = tempDir()
        try {
            val rootfs = File(root, "rootfs").apply { mkdirs() }
            val libs = File(root, "lib").apply { mkdirs() }
            fun populated() {
                LinuxProbe.REQUIRED_BINARIES.forEach { name -> File(libs, name).apply { writeText("fake"); setExecutable(true) } }
            }
            val noRoot = LinuxProbe(File(root, "absent"), libs, listOf("arm64-v8a"), LinuxProbeExecutor { _, _ -> error("must not execute") }).probe()
            assertEquals(LinuxProbeStatus.NOT_INSTALLED, noRoot.status)
            val unsupported = LinuxProbe(rootfs, libs, listOf("x86_64"), LinuxProbeExecutor { _, _ -> error("must not execute") }).probe()
            assertEquals(LinuxProbeStatus.UNSUPPORTED_ABI, unsupported.status)
            val missing = LinuxProbe(rootfs, libs, listOf("arm64-v8a"), LinuxProbeExecutor { _, _ -> error("must not execute") }).probe()
            assertEquals(LinuxProbeStatus.BINARY_MISSING, missing.status)
            assertTrue(missing.error.startsWith("proot absent:"))

            populated()
            val loaderFile = File(libs, "libproot_loader.so")
            loaderFile.delete()
            val noLoader = LinuxProbe(rootfs, libs, listOf("arm64-v8a"),
                LinuxProbeExecutor { _, _ -> error("must not execute") }).probe()
            assertEquals(LinuxProbeStatus.BINARY_MISSING, noLoader.status)
            assertTrue(noLoader.error.startsWith("loader absent:"))
            loaderFile.writeText("loader")
            loaderFile.setExecutable(true)
            val deniedFile = File(libs, "libproot_exec.so")
            Files.setPosixFilePermissions(deniedFile.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            assertFalse(deniedFile.canExecute())
            val denied = LinuxProbe(rootfs, libs, listOf("arm64-v8a"),
                LinuxProbeExecutor { _, _ -> error("must not execute") }, makeExecutable = { false }).probe()
            assertEquals(LinuxProbeStatus.EXEC_DENIED, denied.status)
            assertTrue(denied.error.startsWith("proot not executable:"))
            populated()
            Files.setPosixFilePermissions(loaderFile.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            val deniedLoader = LinuxProbe(rootfs, libs, listOf("arm64-v8a"),
                LinuxProbeExecutor { _, _ -> error("must not execute") },
                makeExecutable = { it.name != "libproot_loader.so" }).probe()
            assertEquals(LinuxProbeStatus.EXEC_DENIED, deniedLoader.status)
            assertTrue(deniedLoader.error.startsWith("loader not executable:"))
            populated()
            deniedFile.setExecutable(true)
            var executed = ""
            val providerError = "proot error: loader not found (exact backend output)\n"
            val failed = LinuxProbe(rootfs, libs, listOf("arm64-v8a"), LinuxProbeExecutor { command, callback ->
                executed = command
                callback.onChunk(LinuxOutputStream.STDERR, providerError)
                LinuxExecResult(127)
            }).probe()
            assertEquals(LinuxProbe.PROBE_COMMAND, executed)
            assertEquals(LinuxProbeStatus.BINARY_MISSING, failed.status)
            assertEquals(providerError, failed.stderr)
            assertEquals(providerError, failed.error)

            val selinuxError = "avc: denied { execute } for path=libproot_exec.so\n"
            val selinux = LinuxProbe(rootfs, libs, listOf("arm64-v8a"), LinuxProbeExecutor { _, callback ->
                callback.onChunk(LinuxOutputStream.STDERR, selinuxError)
                LinuxExecResult(126)
            }).probe()
            assertEquals(LinuxProbeStatus.SELINUX_DENIED, selinux.status)
            assertEquals(selinuxError, selinux.error)

            val passed = LinuxProbe(rootfs, libs, listOf("arm64-v8a"), LinuxProbeExecutor { command, callback ->
                assertEquals(LinuxProbe.PROBE_COMMAND, command)
                callback.onChunk(LinuxOutputStream.STDOUT, "aarch64\n0\nPRETTY_NAME=Ubuntu\nVERSION_ID=\"24.04\"\nok\n")
                LinuxExecResult(0)
            }).probe()
            assertTrue(passed.ready)
            assertEquals("aarch64", passed.architecture)
            assertEquals("0", passed.uid)
            assertTrue(passed.osRelease.contains("PRETTY_NAME=Ubuntu"))
            assertNotNull(passed.exitCode)
        } finally { remove(root) }
    }

    private fun tempDir() = File(System.getProperty("java.io.tmpdir"), "jarvys-probe-${java.util.UUID.randomUUID()}")
        .apply { check(mkdirs()) }
    private fun remove(file: File) {
        val tempRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile.path
        val target = file.canonicalFile.path
        check(target == tempRoot || target.startsWith(tempRoot + File.separator))
        RootfsInstaller.deletePrivateTree(file, NioFileKindReader)
    }

    private class FakeTree(private val parents: Map<Int, Int>) : ProcessTree {
        val killed = mutableListOf<Int>()
        override fun processIds() = parents.keys.toList()
        override fun parentPid(pid: Int) = parents[pid]
        override fun kill(pid: Int) { killed += pid }
    }
}
