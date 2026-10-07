package com.jarvys.agent.linux

import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RootfsPatchSafetyTest {
    @Test fun danglingGuestAbsoluteResolvLinkIsReplacedWithoutTouchingItsHostTarget() {
        val sandbox = tempDir()
        try {
            val victim = File(sandbox, "host-victim").apply { writeText("host data") }
            val root = File(sandbox, "rootfs").apply { mkdirs() }
            val etc = File(root, "etc").apply { mkdirs() }
            Files.createSymbolicLink(File(etc, "resolv.conf").toPath(), victim.absoluteFile.toPath())
            val patcher = RootfsPatcher(androidDnsServers = { listOf("10.0.0.53", "2001:4860:4860::8888") },
                supplementalGroups = { intArrayOf() }, fileKindReader = NioFileKindReader)

            patcher.patch(root)

            assertEquals("host data", victim.readText())
            assertFalse(Files.isSymbolicLink(File(etc, "resolv.conf").toPath()))
            assertEquals("nameserver 10.0.0.53\nnameserver 2001:4860:4860::8888\noptions edns0 trust-ad\n",
                File(etc, "resolv.conf").readText())
        } finally { remove(sandbox) }
    }

    @Test fun patchingPreservesExistingHostLocaleAndAptBackupAndAddsGroupsIdempotently() {
        val sandbox = tempDir()
        try {
            val root = File(sandbox, "rootfs").apply { mkdirs() }
            val etc = File(root, "etc").apply { mkdirs() }
            File(etc, "hostname").writeText("jarvys-node\n")
            File(etc, "hosts").writeText("127.0.0.1 localhost\n::1 localhost\n192.0.2.10 custom\n")
            File(etc, "default").mkdirs()
            File(etc, "default/locale").writeText("LC_ALL=C\nLANG=fr_FR.UTF-8\n")
            File(etc, "group").writeText("root:x:0:\nandroid_gid_1234:x:999:\n")
            val sources = File(etc, "apt/sources.list.d/ubuntu.sources").apply {
                parentFile.mkdirs()
                writeText("original source stanza\n")
            }
            val caCount = intArrayOf(0)
            val ca = acceptedSystemCa()
            val fakeTrustManager = object : X509TrustManager {
                override fun getAcceptedIssuers() = ca
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
            }
            val chmodRequests = mutableMapOf<String, Int>()
            val patcher = RootfsPatcher(supplementalGroups = { intArrayOf(1234, 2345, 1234, 0) },
                trustManagers = { caCount[0]++; arrayOf(fakeTrustManager) }, fileKindReader = NioFileKindReader,
                chmod = { file, mode ->
                    chmodRequests[file.relativeTo(root).invariantSeparatorsPath] = mode
                    android.system.Os.chmod(file.absolutePath, mode and 0x1FF)
                })

            patcher.patch(root, "amd64")
            val backup = File(sources.parentFile, "ubuntu.sources.bak")
            val first = snapshot(root)
            patcher.patch(root, "amd64")

            assertEquals(first, snapshot(root))
            assertEquals("original source stanza\n", backup.readText())
            assertEquals(1, File(etc, "group").readLines().count { it == "android_gid_1234_workspace:x:1234:" })
            assertEquals(1, File(etc, "group").readLines().count { it == "android_gid_2345:x:2345:" })
            assertEquals("127.0.0.1 localhost jarvys-node", File(etc, "hosts").readLines()[0])
            assertEquals("::1 localhost ip6-localhost ip6-loopback", File(etc, "hosts").readLines()[1])
            assertTrue(File(etc, "default/locale").readText().contains("LANG=fr_FR.UTF-8"))
            assertFalse(File(etc, "default/locale").readText().contains("LANG=C.UTF-8"))
            assertTrue(sources.readText().contains("https://archive.ubuntu.com/ubuntu/"))
            assertTrue(sources.readText().contains("Suites: noble-security"))
            assertEquals(1, caCount[0])
            assertEquals("etc/ssl/certs/ca-certificates.crt", File(root, "etc/ssl/certs/ca-certificates.crt")
                .relativeTo(root).invariantSeparatorsPath)
            assertTrue(File(root, "etc/ssl/certs/ca-certificates.crt").readLines()
                .filter { !it.startsWith("-----") }.all { it.length <= 64 })
            val bundle = File(root, "etc/ssl/certs/ca-certificates.crt")
            assertFalse(File(root, "etc/ssl/certs/ca-certificates.crt.jarvys-tmp").exists())
            bundle.writeText("package-managed CA bundle\n")
            assertEquals("package-managed CA bundle\n", bundle.readText())
            assertEquals(0x3FF, chmodRequests["tmp"])
            assertEquals(0x3FF, chmodRequests["var/tmp"])
            assertEquals(0x1C0, chmodRequests["root"])
        } finally { remove(sandbox) }
    }

    @Test fun localOnlyResolversAreReplacedWithAndroidDnsAndEdnsOption() {
        val sandbox = tempDir()
        try {
            val root = File(sandbox, "rootfs").apply { mkdirs() }
            val etc = File(root, "etc").apply { mkdirs() }
            File(etc, "resolv.conf").writeText("nameserver 127.0.0.53\nnameserver ::1\n")
            RootfsPatcher(androidDnsServers = { listOf("192.0.2.53") }, supplementalGroups = { intArrayOf() },
                fileKindReader = NioFileKindReader).patch(root)
            assertEquals("nameserver 192.0.2.53\noptions edns0 trust-ad\n", File(etc, "resolv.conf").readText())
        } finally { remove(sandbox) }
    }

    @Test fun ordinaryPatchTargetsRejectEscapingLinksBeforeReadingOrWritingThem() {
        val sandbox = tempDir()
        try {
            val victim = File(sandbox, "victim").apply { writeText("must survive") }
            val root = File(sandbox, "rootfs").apply { mkdirs() }
            val etc = File(root, "etc").apply { mkdirs() }
            Files.createSymbolicLink(File(etc, "hostname").toPath(), victim.absoluteFile.toPath())
            val failure = runCatching {
                RootfsPatcher(supplementalGroups = { intArrayOf() }, fileKindReader = NioFileKindReader).patch(root)
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("escapes rootfs"))
            assertEquals("must survive", victim.readText())
        } finally { remove(sandbox) }
    }

    private fun acceptedSystemCa() = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
        init(null as KeyStore?)
        trustManagers.filterIsInstance<X509TrustManager>().first().acceptedIssuers
    }

    private fun snapshot(root: File): Map<String, String> = buildMap {
        fun visit(file: File) {
            if (NioFileKindReader.kind(file) == LinuxFileKind.DIRECTORY) file.listFiles()?.forEach(::visit)
            else if (NioFileKindReader.kind(file) == LinuxFileKind.OTHER) put(file.relativeTo(root).path, file.readText())
        }
        visit(root)
    }

    private fun tempDir() = File(System.getProperty("java.io.tmpdir"), "lk1-patcher-${UUID.randomUUID()}")
        .apply { check(mkdirs()) }

    private fun remove(file: File) {
        val tmp = File(System.getProperty("java.io.tmpdir")).canonicalFile.path
        val target = file.canonicalFile.path
        check(target == tmp || target.startsWith(tmp + File.separator))
        RootfsInstaller.deletePrivateTree(file, NioFileKindReader)
    }
}
