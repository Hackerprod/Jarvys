package com.jarvys.agent.linux

import com.jarvys.agent.CancellationToken
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RootfsInstallerTest {
    @Test fun resumesRangePartialAndPersistsThenReconstructsReadyState() {
        val archive = archive()
        val root = tempDir()
        try {
            val paths = RootfsInstaller.Paths(root)
            paths.linuxDir.mkdirs()
            val prefix = archive.copyOfRange(0, archive.size / 3)
            paths.archivePart.writeBytes(prefix)
            val transport = FakeTransport(archive)
            val installer = installer(paths, transport, archive)
            assertEquals(LinuxInstallPhase.DOWNLOADING, installer.state().phase)
            assertEquals(prefix.size.toLong(), installer.state().bytes)
            val ready = installer.install(CancellationToken.uncancellable())
            assertEquals("state=$ready", LinuxInstallPhase.READY, ready.phase)
            assertEquals("test-24", ready.version)
            assertEquals("ubuntu", ready.distro)
            assertEquals("24.04", ready.versionId)
            assertEquals("noble", ready.codename)
            assertEquals(listOf(prefix.size.toLong()), transport.offsets)
            assertTrue(File(paths.rootfs, "etc/os-release").isFile)
            assertFalse(paths.archivePart.exists())
            assertEquals(LinuxInstallPhase.READY, installer(paths, FakeTransport(archive), archive).state().phase)
        } finally { remove(root) }
    }

    @Test fun restartsCleanWhenServerIgnoresRange() {
        val archive = archive()
        val root = tempDir()
        try {
            val paths = RootfsInstaller.Paths(root)
            paths.linuxDir.mkdirs()
            val prefix = archive.copyOfRange(0, archive.size / 4)
            paths.archivePart.writeBytes(prefix)
            val transport = FakeTransport(archive, supportsRange = false)
            val result = installer(paths, transport, archive).install(CancellationToken.uncancellable())
            assertEquals("state=$result", LinuxInstallPhase.READY, result.phase)
            assertEquals(listOf(prefix.size.toLong(), 0L), transport.offsets)
        } finally { remove(root) }
    }

    @Test fun checksumMismatchDeletesPartAndHasExplicitFailureCode() {
        val archive = archive()
        val root = tempDir()
        try {
            val paths = RootfsInstaller.Paths(root)
            val result = installer(paths, FakeTransport(archive), archive, digest = "0".repeat(64))
                .install(CancellationToken.uncancellable())
            assertEquals(LinuxInstallPhase.FAILED, result.phase)
            assertEquals("checksum_mismatch", result.failureCode)
            assertFalse(paths.archivePart.exists())
        } finally { remove(root) }
    }

    @Test fun interruptedDownloadIsRecoverableAndSpaceIsCheckedBeforeNetwork() {
        val archive = archive()
        val root = tempDir()
        try {
            val paths = RootfsInstaller.Paths(root)
            val broken = FakeTransport(archive, maxBytes = archive.size / 2)
            val first = installer(paths, broken, archive).install(CancellationToken.uncancellable())
            assertEquals("download_failed", first.failureCode)
            val resumed = installer(paths, FakeTransport(archive), archive)
            assertEquals(LinuxInstallPhase.DOWNLOADING, resumed.state().phase)
            assertTrue(resumed.state().bytes > 0)
            val recovered = resumed.install(CancellationToken.uncancellable())
            assertEquals("state=$recovered", LinuxInstallPhase.READY, recovered.phase)

            val noNetwork = FakeTransport(archive)
            val insufficient = RootfsInstaller(paths = RootfsInstaller.Paths(File(root, "no-space")),
                transport = noNetwork, availableBytes = { 0 }, release = release(archive))
                .install(CancellationToken.uncancellable())
            assertEquals("insufficient_space", insufficient.failureCode)
            assertTrue(noNetwork.offsets.isEmpty())
        } finally { remove(root) }
    }

    @Test fun cancelledDownloadAndInterruptedExtractionDiscardStagingAndCanRetry() {
        val archive = archive()
        val root = tempDir()
        try {
            val paths = RootfsInstaller.Paths(root)
            val token = CancellationToken.cancellable()
            val cancelledTransport = FakeTransport(archive, afterRead = { token.cancel() })
            val cancelled = installer(paths, cancelledTransport, archive).install(token)
            assertEquals("cancelled", cancelled.failureCode)
            assertTrue(paths.archivePart.length() > 0)
            val cancelledBytes = paths.archivePart.length()
            val retryTransport = FakeTransport(archive)
            assertEquals(LinuxInstallPhase.READY,
                installer(paths, retryTransport, archive).install(CancellationToken.uncancellable()).phase)
            assertEquals(cancelledBytes, retryTransport.offsets.single())

            val brokenExtractor = object : RootfsTarExtractor() {
                override fun extract(gzipTar: java.io.InputStream, destination: File,
                                     onProgress: (Progress) -> Unit): Report {
                    destination.mkdirs()
                    File(destination, "partial-entry").writeText("partial")
                    throw IOException("simulated process death during extraction")
                }
            }
            val failed = installer(paths, FakeTransport(archive), archive, extractor = brokenExtractor)
                .install(CancellationToken.uncancellable())
            assertEquals("extract_failed", failed.failureCode)
            assertFalse(paths.staging.exists())
            val recovered = installer(paths, FakeTransport(archive), archive)
            val retry = recovered.install(CancellationToken.uncancellable())
            assertEquals("state=$retry", LinuxInstallPhase.READY, retry.phase)
        } finally { remove(root) }
    }

    @Test fun failuresAcrossInstallAndProbePhasesExposeSanitizedDetailsThatSurviveRestart() {
        val archive = archive()
        val root = tempDir()
        val hostPath = root.absolutePath
        val sanitizer: (String) -> String = { it.replace(hostPath, "[app-private path]") }
        fun assertPersisted(paths: RootfsInstaller.Paths, phase: String, expected: List<String>,
                            stateRelease: LinuxRelease) {
            val detail = RootfsInstaller(paths, release = stateRelease, failureSanitizer = sanitizer).state().failureDetail
            assertTrue("$phase detail missing $expected: $detail", expected.all(detail::contains))
            assertFalse("host path leaked: $detail", detail.contains(hostPath))
        }
        try {
            val release = release(archive)

            val downloadPaths = RootfsInstaller.Paths(File(root, "download"))
            val downloadFailure = RootfsInstaller(downloadPaths,
                transport = DownloadTransport { _, _ -> throw IOException("download failed at $hostPath") },
                availableBytes = { Long.MAX_VALUE }, release = release, failureSanitizer = sanitizer)
                .install(CancellationToken.uncancellable())
            assertEquals("download_failed", downloadFailure.failureCode)
            assertPersisted(downloadPaths, "download", listOf("phase=DOWNLOADING", "java.io.IOException", "download failed", "[app-private path]"), release)

            val verifyPaths = RootfsInstaller.Paths(File(root, "verify"))
            val verifyFailure = RootfsInstaller(verifyPaths, transport = FakeTransport(archive),
                availableBytes = { Long.MAX_VALUE }, release = release.copy(sha256 = "0".repeat(64)),
                failureSanitizer = sanitizer).install(CancellationToken.uncancellable())
            assertEquals("checksum_mismatch", verifyFailure.failureCode)
            assertPersisted(verifyPaths, "verify", listOf("phase=VERIFYING", "InstallFailure", "SHA-256 mismatch"), release.copy(sha256 = "0".repeat(64)))

            val extractPaths = RootfsInstaller.Paths(File(root, "extract"))
            val failingOps = object : RootfsTarFileOps {
                override fun openNewRegular(file: File): OutputStream = throw ErrnoException("open", OsConstants.EACCES)
                override fun symlink(target: String, link: File) { Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target)) }
                override fun hardlink(source: File, link: File) { Files.createLink(link.toPath(), source.toPath()) }
                override fun chmod(file: File, mode: Int) { }
            }
            val extractFailure = RootfsInstaller(extractPaths, transport = FakeTransport(archive),
                extractor = RootfsTarExtractor(fileOps = failingOps), availableBytes = { Long.MAX_VALUE },
                release = release, failureSanitizer = sanitizer).install(CancellationToken.uncancellable())
            assertEquals("extract_failed", extractFailure.failureCode)
            assertEquals(archive.size.toLong(), extractFailure.bytes)
            assertEquals(1, extractFailure.extractedEntries)
            assertEquals("etc/os-release", extractFailure.lastEntrySeen)
            assertTrue(extractFailure.failureDetail.contains("phase=EXTRACTING"))
            assertTrue(extractFailure.failureDetail.contains("errno=EACCES"))
            assertTrue(extractFailure.failureDetail.contains("function=open"))
            assertTrue(extractFailure.failureDetail.contains("tar_entry=etc/os-release"))
            assertTrue(extractFailure.failureDetail.contains("tar_type=regular(0)"))
            assertTrue(extractFailure.failureDetail.contains("tar_mode=0644"))
            assertTrue(extractFailure.failureDetail.contains("entries_processed=1"))
            assertPersisted(extractPaths, "extract", listOf("phase=EXTRACTING", "errno=EACCES", "tar_entry=etc/os-release"), release)
            assertTrue(extractPaths.archivePart.isFile)
            val noDownload = FakeTransport(archive)
            val retried = RootfsInstaller(extractPaths, transport = noDownload, availableBytes = { Long.MAX_VALUE },
                release = release, extractor = testExtractor(), failureSanitizer = sanitizer)
                .install(CancellationToken.uncancellable())
            assertEquals(LinuxInstallPhase.READY, retried.phase)
            assertTrue("retry downloaded the preserved verified archive", noDownload.offsets.isEmpty())
            assertFalse(extractPaths.staging.exists())

            val patchPaths = RootfsInstaller.Paths(File(root, "patch"))
            val patchFailure = RootfsInstaller(patchPaths, transport = FakeTransport(archive),
                patcher = RootfsPatcher(supplementalGroups = { throw IOException("patch failed at $hostPath") }),
                extractor = testExtractor(), availableBytes = { Long.MAX_VALUE }, release = release,
                failureSanitizer = sanitizer)
                .install(CancellationToken.uncancellable())
            assertEquals("patch_failed", patchFailure.failureCode)
            assertPersisted(patchPaths, "patch", listOf("phase=PATCHING", "java.io.IOException", "patch failed", "[app-private path]"), release)

            val probePaths = RootfsInstaller.Paths(File(root, "probe"))
            val probeInstaller = RootfsInstaller(probePaths, transport = FakeTransport(archive),
                extractor = testExtractor(), availableBytes = { Long.MAX_VALUE }, release = release,
                failureSanitizer = sanitizer)
            assertEquals(LinuxInstallPhase.READY, probeInstaller.install(CancellationToken.uncancellable()).phase)
            val probeDetail = LinuxFailureDiagnostics.format("PROBE",
                IOException("exec denied at $hostPath", ErrnoException("execve", OsConstants.EACCES)),
                sanitize = sanitizer)
            probeInstaller.markProbeFailure("exec_denied", probeDetail)
            assertPersisted(probePaths, "probe", listOf("phase=PROBE", "errno=EACCES", "function=execve", "[app-private path]"), release)
        } finally { remove(root) }
    }

    @Test fun gzipFailureBeforeFirstTarEntryKeepsZeroCountAndExactCauseForTheOwner() {
        val invalidGzip = "not a gzip stream".toByteArray()
        val root = tempDir()
        try {
            val paths = RootfsInstaller.Paths(root)
            val result = RootfsInstaller(paths, transport = FakeTransport(invalidGzip),
                availableBytes = { Long.MAX_VALUE }, release = release(invalidGzip))
                .install(CancellationToken.uncancellable())

            assertEquals("extract_failed", result.failureCode)
            assertEquals(0, result.extractedEntries)
            assertEquals(invalidGzip.size.toLong(), result.bytes)
            assertTrue(result.failureDetail, result.failureDetail.contains("phase=EXTRACTING"))
            assertTrue(result.failureDetail, result.failureDetail.contains("java.util.zip.ZipException"))
            assertTrue(result.failureDetail, result.failureDetail.contains("entries_processed=0"))
            assertTrue(result.failureDetail, result.failureDetail.contains("tar_entry=none"))
            assertEquals(result.failureDetail, RootfsInstaller(paths, release = release(invalidGzip)).state().failureDetail)
            assertFalse(paths.staging.exists())
        } finally { remove(root) }
    }

    @Test fun degradedModeAndCopiedHardlinkMetricsPersistInReadyState() {
        val elf = ByteArray(64).apply {
            this[0] = 0x7f.toByte(); this[1] = 'E'.code.toByte(); this[2] = 'L'.code.toByte(); this[3] = 'F'.code.toByte()
            this[4] = 2; this[5] = 1; this[18] = 183.toByte()
        }
        val raw = TestTar().directory("tmp", 0x3FF).directory("etc")
            .file("etc/os-release", "ID=ubuntu\nVERSION_ID=24.04\nVERSION_CODENAME=noble\n".toByteArray())
            .directory("bin").file("bin/sh", elf, 0x1ED).directory("usr").directory("usr/bin")
            .file("usr/bin/perl", "perl".toByteArray(), 0x1ED)
            .hardlink("usr/bin/perl-copy", "usr/bin/perl")
            .file("usr/bin/chfn", "setuid".toByteArray(), 0x9ED).bytes()
        val compressed = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(raw) } }.toByteArray()
        val root = tempDir()
        try {
            val paths = RootfsInstaller.Paths(root)
            val release = release(compressed)
            val installer = RootfsInstaller(paths, transport = FakeTransport(compressed),
                patcher = RootfsPatcher(supplementalGroups = { intArrayOf() }),
                extractor = RootfsTarExtractor(fileOps = AndroidDeniedTestOps),
                availableBytes = { Long.MAX_VALUE }, release = release)

            val ready = installer.install(CancellationToken.uncancellable())

            assertEquals(LinuxInstallPhase.READY, ready.phase)
            assertEquals(2, ready.degradedEntries)
            assertEquals(1, ready.hardlinksCopied)
            val restored = RootfsInstaller(paths, release = release).state()
            assertEquals(2, restored.degradedEntries)
            assertEquals(1, restored.hardlinksCopied)
            assertEquals("perl", File(paths.rootfs, "usr/bin/perl-copy").readText())
        } finally { remove(root) }
    }

    @Test fun privateTreeDeletionNeverTraversesSymlinksAndLegacyAlgorithmWouldDeleteVictim() {
        val tempRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile
        val sandbox = tempDir()
        val tree = File(sandbox, "tree").apply { mkdirs() }
        val victim = File(sandbox, "victim").apply { mkdirs() }
        try {
            val sentinel = File(victim, "sentinel.txt").apply { writeText("preserve me") }
            val varDirectory = File(tree, "var").apply { mkdirs() }
            fun link(path: File, target: java.nio.file.Path) {
                assertUnder(tempRoot, path.absoluteFile)
                assertUnder(tempRoot, path.parentFile.toPath().resolve(target).toFile())
                Files.createSymbolicLink(path.toPath(), target)
            }
            link(File(tree, "absolute-victim"), victim.toPath())
            link(File(tree, "relative-victim"), java.nio.file.Paths.get("../victim"))
            link(File(tree, "ancestor"), java.nio.file.Paths.get(".."))
            link(File(tree, "file-link"), sentinel.toPath())
            link(File(tree, "loop"), java.nio.file.Paths.get("."))
            link(File(varDirectory, "run"), victim.toPath())
            link(File(varDirectory, "lock"), java.nio.file.Paths.get("../../victim"))
            val osKind = AndroidLinuxFileKindReader.kind(File(tree, "absolute-victim"))
            println("LF1_ROBOLECTRIC_OS_LSTAT_SYMLINK_KIND=$osKind")
            assertEquals(LinuxFileKind.SYMLINK, NioFileKindReader.kind(File(tree, "absolute-victim")))

            RootfsInstaller.deletePrivateTree(tree, NioFileKindReader)

            assertFalse(Files.exists(tree.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertEquals("preserve me", sentinel.readText())

            // The canonical containment check remains a second guard if a platform lstat lies.
            val lstatSandbox = tempDir()
            try {
                val lstatTree = File(lstatSandbox, "tree").apply { mkdirs() }
                val lstatVictim = File(lstatSandbox, "victim").apply { mkdirs() }
                val lstatSentinel = File(lstatVictim, "sentinel.txt").apply { writeText("canonical guard") }
                val lstatAlias = File(lstatTree, "external-alias")
                assertUnder(tempRoot, lstatSandbox)
                Files.createSymbolicLink(lstatAlias.toPath(), lstatVictim.toPath())
                assertUnder(tempRoot, lstatAlias.canonicalFile)
                RootfsInstaller.deletePrivateTree(lstatTree, AndroidLinuxFileKindReader)
                assertEquals("canonical guard", lstatSentinel.readText())
            } finally {
                assertUnder(tempRoot, lstatSandbox)
                RootfsInstaller.deletePrivateTree(lstatSandbox, NioFileKindReader)
            }

            // Demonstrate the old File.isDirectory recursion on an isolated tmp-only fixture.
            val legacySandbox = tempDir()
            try {
                val legacyTree = File(legacySandbox, "tree").apply { mkdirs() }
                val legacyVictim = File(legacySandbox, "victim").apply { mkdirs() }
                val legacySentinel = File(legacyVictim, "sentinel.txt").apply { writeText("old algorithm reaches this") }
                val alias = File(legacyTree, "victim-link")
                assertUnder(tempRoot, legacyTree)
                assertUnder(tempRoot, legacyVictim)
                Files.createSymbolicLink(alias.toPath(), legacyVictim.toPath())
                assertUnder(tempRoot, alias.canonicalFile)
                fun legacyDelete(file: File) {
                    if (file.exists() && file.isDirectory) file.listFiles()?.forEach(::legacyDelete)
                    file.delete()
                }
                legacyDelete(legacyTree)
                assertFalse("legacy algorithm should reproduce the regression", legacySentinel.exists())
            } finally {
                assertUnder(tempRoot, legacySandbox)
                RootfsInstaller.deletePrivateTree(legacySandbox, NioFileKindReader)
            }
        } finally {
            assertUnder(tempRoot, sandbox)
            RootfsInstaller.deletePrivateTree(sandbox, NioFileKindReader)
        }
    }

    @Test fun uninstallAndWorkspaceDeletionUnlinkAppDataAliasesWithoutDeletingTheTarget() {
        val tempRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile
        val sandbox = tempDir()
        try {
            val files = File(sandbox, "app/files").apply { mkdirs() }
            val memory = File(files, "jarvys/memory").apply { mkdirs() }
            val sentinel = File(memory, "MEMORY.md").apply { writeText("private app data") }
            val paths = RootfsInstaller.Paths(files)
            val installer = RootfsInstaller(paths, availableBytes = { Long.MAX_VALUE },
                fileKindReader = NioFileKindReader)
            assertUnder(tempRoot, paths.workspace)
            Files.delete(paths.workspace.toPath())
            Files.createSymbolicLink(paths.workspace.toPath(), memory.toPath())
            assertUnder(tempRoot, paths.workspace.canonicalFile)

            installer.deleteWorkspace()

            assertFalse(Files.exists(paths.workspace.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertEquals("private app data", sentinel.readText())

            Files.createSymbolicLink(paths.rootfs.toPath(), memory.toPath())
            assertUnder(tempRoot, paths.rootfs.canonicalFile)
            installer.uninstall()
            assertFalse(Files.exists(paths.rootfs.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertEquals("private app data", sentinel.readText())
        } finally {
            assertUnder(tempRoot, sandbox)
            RootfsInstaller.deletePrivateTree(sandbox, NioFileKindReader)
        }
    }

    @Test fun patchingIsIdempotentAndCreatesOfficialArm64AptAndGroupConfig() {
        val root = tempDir()
        try {
            val patchRoot = File(root, "rootfs").apply { mkdirs() }
            val etc = File(patchRoot, "etc").apply { mkdirs() }
            File(etc, "group").writeText("root:x:0:\nusers:x:100:\n")
            val patcher = RootfsPatcher(supplementalGroups = { intArrayOf(1234, 2345, 1234) })
            patcher.patch(patchRoot)
            val first = filesSnapshot(patchRoot)
            patcher.patch(patchRoot)
            assertEquals(first, filesSnapshot(patchRoot))
            assertTrue(File(etc, "resolv.conf").readText().contains("nameserver"))
            assertTrue(File(etc, "apt/apt.conf.d/99jarvys-root-sandbox").readText().contains("APT::Sandbox::User \"root\";"))
            val sources = File(etc, "apt/sources.list.d/ubuntu.sources").readText()
            assertTrue(sources.contains("https://ports.ubuntu.com/ubuntu-ports/"))
            assertTrue(sources.contains("Suites: noble-security"))
            assertTrue(sources.contains("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg"))
            assertFalse(sources.contains("mirror"))
            assertEquals(1, File(etc, "group").readLines().count { it == "android_gid_1234:x:1234:" })
        } finally { remove(root) }
    }

    private fun installer(paths: RootfsInstaller.Paths, transport: DownloadTransport, archive: ByteArray,
                          digest: String = sha(archive), extractor: RootfsTarExtractor = testExtractor()) =
        RootfsInstaller(paths, transport, RootfsPatcher(supplementalGroups = { intArrayOf(1000) }), extractor,
            availableBytes = { Long.MAX_VALUE }, release = release(archive, digest))

    private fun testExtractor() = RootfsTarExtractor(fileOps = InstallerTestTarOps)

    private fun release(archive: ByteArray, digest: String = sha(archive)) =
        LinuxRelease("test-24", "arm64", "https://unit.test/rootfs.tar.gz", digest, archive.size.toLong(), 1)

    private fun archive(): ByteArray {
        val entropy = ByteArray(96 * 1024).also { java.util.Random(37).nextBytes(it) }
        val elf = ByteArray(64).apply {
            this[0] = 0x7f.toByte(); this[1] = 'E'.code.toByte(); this[2] = 'L'.code.toByte(); this[3] = 'F'.code.toByte()
            this[4] = 2; this[5] = 1; this[18] = 183.toByte()
        }
        val raw = TestTar().directory("etc").file("etc/os-release", "ID=ubuntu\nVERSION_ID=24.04\nVERSION_CODENAME=noble\n".toByteArray())
            .directory("bin").file("bin/sh", elf, 0x1ED).file("usr/share/entropy", entropy).bytes()
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(raw) }
        return output.toByteArray()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun filesSnapshot(root: File): Map<String, String> {
        val result = linkedMapOf<String, String>()
        fun visit(file: File) {
            if (file.isDirectory) file.listFiles()?.forEach(::visit)
            else result[file.relativeTo(root).path] = file.readText()
        }
        visit(root)
        return result
    }

    private fun tempDir() = File(System.getProperty("java.io.tmpdir"), "jarvys-installer-${java.util.UUID.randomUUID()}")
        .apply { check(mkdirs()) }

    private fun assertUnder(root: File, target: File) {
        val canonicalRoot = root.canonicalFile.path
        val canonicalTarget = target.canonicalFile.path
        assertTrue("refusing destructive test outside java.io.tmpdir: $canonicalTarget",
            canonicalTarget == canonicalRoot || canonicalTarget.startsWith(canonicalRoot + File.separator))
    }

    private fun remove(file: File) {
        val tempRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile
        assertUnder(tempRoot, file)
        RootfsInstaller.deletePrivateTree(file, NioFileKindReader)
    }

    private class FakeTransport(
        private val archive: ByteArray,
        private val supportsRange: Boolean = true,
        private val maxBytes: Int = Int.MAX_VALUE,
        private val afterRead: (() -> Unit)? = null,
    ) : DownloadTransport {
        val offsets = mutableListOf<Long>()
        override fun open(url: String, offset: Long): DownloadSession {
            offsets += offset
            val ranged = offset > 0 && supportsRange
            val from = if (ranged) offset.toInt() else 0
            val end = minOf(archive.size.toLong(), from.toLong() + maxBytes.toLong()).toInt()
            val payload = archive.copyOfRange(from, end)
            return object : DownloadSession {
                override val statusCode = if (ranged) 206 else 200
                override val rangeStart = if (ranged) offset else null
                override val input = object : ByteArrayInputStream(payload) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val count = super.read(buffer, offset, length)
                        if (count > 0) afterRead?.invoke()
                        return count
                    }
                }
                override fun close() = input.close()
            }
        }
    }
}

internal object NioFileKindReader : LinuxFileKindReader {
    override fun kind(file: File): LinuxFileKind = when {
        Files.isSymbolicLink(file.toPath()) -> LinuxFileKind.SYMLINK
        Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS) -> LinuxFileKind.DIRECTORY
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) -> LinuxFileKind.OTHER
        else -> LinuxFileKind.MISSING
    }
}

private object AndroidDeniedTestOps : RootfsTarFileOps {
    override fun openNewRegular(file: File) = Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
    override fun symlink(target: String, link: File) {
        Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target))
    }
    override fun hardlink(source: File, link: File) { throw android.system.ErrnoException("link", android.system.OsConstants.EACCES) }
    override fun chmod(file: File, mode: Int) {
        val special = android.system.OsConstants.S_ISUID or android.system.OsConstants.S_ISGID or
            android.system.OsConstants.S_ISVTX
        if ((mode and special) != 0) throw android.system.ErrnoException("chmod", android.system.OsConstants.EPERM)
        val permissions = java.util.EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission::class.java).apply {
            val bits = listOf(0x100 to java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                0x80 to java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                0x40 to java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                0x20 to java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                0x10 to java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
                0x8 to java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
                0x4 to java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                0x2 to java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
                0x1 to java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE)
            bits.filter { (mode and it.first) != 0 }.forEach { add(it.second) }
        }
        Files.setPosixFilePermissions(file.toPath(), permissions)
    }
}

private object InstallerTestTarOps : RootfsTarFileOps {
    override fun openNewRegular(file: File): OutputStream = Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
    override fun symlink(target: String, link: File) { Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target)) }
    override fun hardlink(source: File, link: File) { Files.createLink(link.toPath(), source.toPath()) }
    override fun chmod(file: File, mode: Int) {
        val permissions = java.util.EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission::class.java).apply {
            val bits = listOf(0x100 to java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                0x80 to java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                0x40 to java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                0x20 to java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                0x10 to java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
                0x8 to java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
                0x4 to java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                0x2 to java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
                0x1 to java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE)
            bits.filter { (mode and it.first) != 0 }.forEach { add(it.second) }
        }
        Files.setPosixFilePermissions(file.toPath(), permissions)
    }
}
