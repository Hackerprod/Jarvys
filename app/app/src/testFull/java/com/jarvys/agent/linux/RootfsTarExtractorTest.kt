package com.jarvys.agent.linux

import android.system.OsConstants
import android.system.ErrnoException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RootfsTarExtractorTest {
    @Test fun extractsRegularFilesRestrictedDirectoriesLinksPaxAndGnuLongNames() {
        val longPath = "usr/share/doc/" + "long-component-".repeat(8) + "guide.txt"
        val root = tempDir()
        try {
            val archive = TestTar()
                .directory("etc", 0x140) // read/execute only after extraction
                .file("etc/config", "read only parent did not block extraction".toByteArray())
                .directory("var")
                .file("var/real", byteArrayOf(1, 2, 3))
                .symlink("var/alias", "real")
                .hardlink("var/copy", "var/real")
                .paxPath(longPath, "pax".toByteArray())
                .gnuLongName("usr/share/doc/gnu-long-name.txt", "gnu".toByteArray())
            extractor().extract(ByteArrayInputStream(archive.bytes()), root)
            assertEquals("read only parent did not block extraction", File(root, "etc/config").readText())
            assertEquals("pax", File(root, longPath).readText())
            assertEquals("gnu", File(root, "usr/share/doc/gnu-long-name.txt").readText())
            assertEquals(File(root, "var/real").canonicalPath, File(root, "var/alias").canonicalPath)
            assertArrayEquals(byteArrayOf(1, 2, 3), File(root, "var/copy").readBytes())
            assertEquals(OsConstants.S_IFDIR, LinuxFileModes.mode(File(root, "etc")))
        } finally { remove(root) }
    }

    @Test fun rejectsTraversalAbsolutePathsSymlinkEscapesAndFollowingArchiveSymlinks() {
        rejects(TestTar().file("../escape", byteArrayOf(1)).bytes())
        rejects(TestTar().file("/etc/passwd", byteArrayOf(1)).bytes())
        rejects(TestTar().symlink("dir/link", "../../outside").bytes())
        rejects(TestTar().hardlink("copy", "../outside").bytes())
        rejects(TestTar().symlink("a", "/").file("a/etc/x", byteArrayOf(1)).bytes())
    }

    @Test fun rejectsTruncationDeclaredSizeMismatchAndPaxNulName() {
        val truncated = TestTar().rawTruncatedFile("short", 12, byteArrayOf(1, 2, 3))
        rejects(truncated)
        val nulPax = TestTar().paxRawPath("bad\u0000name".toByteArray(), byteArrayOf(1)).bytes()
        rejects(nulPax)
    }

    @Test fun boundsRepeatedGnuLongNameMetadataRecords() {
        val root = tempDir()
        try {
            val tar = TestTar().gnuLongNameChain(9, "usr/share/final-name").bytes()
            val failure = runCatching { extractor().extract(ByteArrayInputStream(tar), root) }.exceptionOrNull()
            assertTrue("nested metadata limit must reject malformed tar: $failure", failure is IOException)
            assertFalse(File(root, "usr/share/final-name").exists())
        } finally { remove(root) }
    }

    @Test fun ignoresDeviceAndFifoEntriesWithAnObservableRecord() {
        val ignored = mutableListOf<Pair<String, Char>>()
        val archive = TestTar().special("dev/char", '3').special("dev/block", '4').special("run/fifo", '6')
        val root = tempDir()
        try {
            RootfsTarExtractor({ path, type -> ignored += path to type }, TestFileOps)
                .extract(ByteArrayInputStream(archive.bytes()), root)
            assertEquals(listOf("dev/char" to '3', "dev/block" to '4', "run/fifo" to '6'), ignored)
            assertFalse(File(root, "dev/char").exists())
            assertFalse(File(root, "dev/block").exists())
            assertFalse(File(root, "run/fifo").exists())
            val tar = archive.bytes()
            assertEquals(3, RootfsTarExtractor({ _, _ -> }, TestFileOps)
                .extract(ByteArrayInputStream(tar), root).skippedEntries)
        } finally { remove(root) }
    }

    @Test fun rootEntryKeepsRootModeAbsoluteGuestLinksStayLinksAndParentsAreCreatedOnDemand() {
        val root = tempDir()
        try {
            val originalRootMode = LinuxFileModes.permissions(root)
            val archive = TestTar().directory("", 0x1C0)
                .symlink("bin", "usr/bin")
                .file("./var/lib/dpkg/status", "package-state".toByteArray())
                .base256File("var/lib/dpkg/time-base256", byteArrayOf(1), 0x1A4, 1_700_000_000)
                .paxMtime("var/lib/dpkg/time-pax", byteArrayOf(2), "1700000100.0")
                .symlink("usr/bin/awk", "/etc/alternatives/awk")
                .directory("etc")
                .hardlink("var/lib/dpkg/status-copy", "var/lib/dpkg/status")
                .bytes()
            val report = RootfsTarExtractor(fileOps = TestFileOps).extract(ByteArrayInputStream(archive), root)
            assertEquals(originalRootMode, LinuxFileModes.permissions(root))
            assertEquals("package-state", File(root, "var/lib/dpkg/status").readText())
            assertEquals("package-state", File(root, "var/lib/dpkg/status-copy").readText())
            assertEquals(1_700_000_000_000L, File(root, "var/lib/dpkg/time-base256").lastModified())
            assertEquals(1_700_000_100_000L, File(root, "var/lib/dpkg/time-pax").lastModified())
            assertFalse(Files.isSameFile(File(root, "var/lib/dpkg/status").toPath(),
                File(root, "var/lib/dpkg/status-copy").toPath()))
            assertEquals(java.nio.file.Paths.get("/etc/alternatives/awk"),
                Files.readSymbolicLink(File(root, "usr/bin/awk").toPath()))
            assertEquals(1, report.hardlinksCopied)
        } finally { remove(root) }
    }

    @Test fun deniedHardlinksCopySafeInRootContentAndSpecialModeDenialsAreDegraded() {
        val root = tempDir()
        try {
            val archive = TestTar()
                .directory("tmp", 0x3FF) // 01777 sticky
                .directory("run")
                .directory("run/lock", 0x3FF)
                .directory("usr")
                .directory("usr/bin")
                .file("usr/bin/perl", "perl-binary".toByteArray(), 0x1ED)
                .hardlink("usr/bin/perl5.38.2", "usr/bin/perl")
                .file("usr/bin/gunzip", "gunzip-binary".toByteArray(), 0x1ED)
                .hardlink("usr/bin/uncompress", "usr/bin/gunzip")
                .file("usr/bin/chfn", "setuid-tool".toByteArray(), 0x9ED) // 04755
                .symlink("bin", "usr/bin")

            val report = RootfsTarExtractor(fileOps = AndroidDeniedOps).extract(
                ByteArrayInputStream(archive.bytes()), root)

            assertEquals(2, report.hardlinksCopied)
            assertEquals(3, report.degradedEntries)
            assertEquals("perl-binary", File(root, "usr/bin/perl5.38.2").readText())
            assertEquals("gunzip-binary", File(root, "usr/bin/uncompress").readText())
            assertEquals(LinuxFileModes.permissions(File(root, "usr/bin/perl")),
                LinuxFileModes.permissions(File(root, "usr/bin/perl5.38.2")))
            assertTrue(Files.isSymbolicLink(File(root, "bin").toPath()))
            assertEquals(java.nio.file.Paths.get("usr/bin"), Files.readSymbolicLink(File(root, "bin").toPath()))
            assertEquals(File(root, "usr/bin").canonicalPath, File(root, "bin").canonicalPath)
            assertEquals(OsConstants.S_IFDIR, LinuxFileModes.mode(File(root, "tmp")))
            assertEquals(OsConstants.S_IFDIR, LinuxFileModes.mode(File(root, "run/lock")))
            assertEquals(0, (LinuxFileModes.permissions(File(root, "usr/bin/chfn")) ?: 0) and OsConstants.S_ISUID)
        } finally { remove(root) }
    }

    @Test fun hardlinksAlwaysCopyAndOrdinaryChmodEioIsNotSilentlyDegraded() {
        val root = tempDir()
        try {
            val archive = TestTar().directory("bin")
                .file("bin/source", "x".toByteArray()).hardlink("bin/copy", "bin/source").bytes()
            val fileOps = object : RootfsTarFileOps by TestFileOps {
                override fun hardlink(source: File, link: File) {
                    throw ErrnoException("link", android.system.OsConstants.ENOSPC)
                }
            }
            var hardlinkAttempted = false
            val report = RootfsTarExtractor(fileOps = object : RootfsTarFileOps by fileOps {
                override fun hardlink(source: File, link: File) { hardlinkAttempted = true; fileOps.hardlink(source, link) }
            }).extract(ByteArrayInputStream(archive), root)
            assertFalse("Android extraction copies hardlinks without attempting Os.link", hardlinkAttempted)
            assertEquals(1, report.hardlinksCopied)
            assertEquals("x", File(root, "bin/copy").readText())
        } finally { remove(root) }

        val chmodRoot = tempDir()
        try {
            val specialFile = TestTar().directory("bin").file("bin/chfn", "special".toByteArray(), 0x9ED).bytes()
            val eioOps = object : RootfsTarFileOps by TestFileOps {
                override fun chmod(file: File, mode: Int) {
                    if ((mode and OsConstants.S_ISUID) != 0) throw ErrnoException("chmod", OsConstants.EIO)
                    TestFileOps.chmod(file, mode)
                }
            }
            val failure = runCatching {
                RootfsTarExtractor(fileOps = eioOps).extract(ByteArrayInputStream(specialFile), chmodRoot)
            }.exceptionOrNull()
            val captured = failure as? LinuxTarEntryFailure
            assertTrue("ordinary chmod I/O errors must abort: $failure", captured != null)
            assertEquals(0, captured!!.degradedEntries)
            val detail = LinuxFailureDiagnostics.format("EXTRACTING", captured, sanitize = { it })
            assertTrue(detail, detail.contains("errno=EIO"))
            assertTrue(detail, detail.contains("tar_entry=bin/chfn"))
        } finally { remove(chmodRoot) }
    }

    private fun rejects(tar: ByteArray) {
        val root = tempDir()
        try {
            val error = runCatching { extractor().extract(ByteArrayInputStream(tar), root) }.exceptionOrNull()
        assertTrue("expected archive rejection", error is IOException || error is android.system.ErrnoException)
        } finally { remove(root) }
    }

    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "jarvys-l0-${java.util.UUID.randomUUID()}")
        .apply { check(mkdirs()) }

    private fun remove(file: File) {
        val tempRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile
        val canonicalRoot = tempRoot.path
        val target = file.canonicalFile.path
        assertTrue("refusing test cleanup outside java.io.tmpdir: $target",
            target == canonicalRoot || target.startsWith(canonicalRoot + File.separator))
        RootfsInstaller.deletePrivateTree(file, NioFileKindReader)
    }

    private fun extractor() = RootfsTarExtractor(fileOps = TestFileOps)

    private object TestFileOps : RootfsTarFileOps {
        override fun openNewRegular(file: File) = Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        override fun symlink(target: String, link: File) { Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target)) }
        override fun hardlink(source: File, link: File) { Files.createLink(link.toPath(), source.toPath()) }
        override fun chmod(file: File, mode: Int) { Files.setPosixFilePermissions(file.toPath(), testPermissions(mode)) }
    }

    private object AndroidDeniedOps : RootfsTarFileOps by TestFileOps {
        override fun hardlink(source: File, link: File) { throw ErrnoException("link", android.system.OsConstants.EACCES) }
        override fun chmod(file: File, mode: Int) {
            val special = android.system.OsConstants.S_ISUID or android.system.OsConstants.S_ISGID or
                android.system.OsConstants.S_ISVTX
            if (mode and special != 0) throw ErrnoException("chmod", android.system.OsConstants.EPERM)
            TestFileOps.chmod(file, mode)
        }
    }
}

private fun testPermissions(mode: Int): Set<PosixFilePermission> = EnumSet.noneOf(PosixFilePermission::class.java).apply {
    val bits = listOf(0x100 to PosixFilePermission.OWNER_READ, 0x80 to PosixFilePermission.OWNER_WRITE,
        0x40 to PosixFilePermission.OWNER_EXECUTE, 0x20 to PosixFilePermission.GROUP_READ,
        0x10 to PosixFilePermission.GROUP_WRITE, 0x8 to PosixFilePermission.GROUP_EXECUTE,
        0x4 to PosixFilePermission.OTHERS_READ, 0x2 to PosixFilePermission.OTHERS_WRITE,
        0x1 to PosixFilePermission.OTHERS_EXECUTE)
    bits.filter { mode and it.first != 0 }.forEach { add(it.second) }
}
