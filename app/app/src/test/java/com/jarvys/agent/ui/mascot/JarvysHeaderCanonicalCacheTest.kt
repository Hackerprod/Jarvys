package com.jarvys.agent.ui.mascot

import com.jarvys.agent.JarvysVerifiedAnimation
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Host-filesystem regression. The Android O_NOFOLLOW open still needs the owner's Android gate. */
class JarvysHeaderCanonicalCacheTest {
    private val bytes = byteArrayOf(82, 73, 86, 69, 0, 1, 2, 3)
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected path/integrity rejection") } catch (_: IllegalArgumentException) { } }
    private fun fixture(block: (File, File, File, JarvysVerifiedAnimation) -> Unit) {
        val temp = Files.createTempDirectory("jarvys-cache-alias-test").toFile()
        try {
            val realParent = File(temp, "real").apply { mkdirs() }
            val cache = File(realParent, "cache").apply { mkdirs() }
            val aliasParent = File(temp, "context-alias")
            Files.createSymbolicLink(aliasParent.toPath(), realParent.toPath())
            val contextCache = File(aliasParent, "cache")
            val directory = File(cache, "jarvys_animations").apply { mkdirs() }
            val file = File(directory, "sad-$sha.riv").apply { writeBytes(bytes) }
            block(temp, cache, contextCache, JarvysVerifiedAnimation(file.canonicalFile, sha, bytes.size.toLong(), "default", "sad"))
        } finally {
            // Do not follow symlink targets while deleting this test-owned directory.
            Files.walk(temp.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
    @Test fun contextAncestorAliasAcceptsCanonicalRepositoryHandleAndProvesOldRejection() = fixture { _, _, contextCache, handle ->
        val priorExpected = File(File(contextCache, "jarvys_animations"), handle.file.name)
        assertNotEquals(handle.file.absoluteFile, priorExpected.absoluteFile)
        assertEquals(handle.file, JarvysHeaderAnimationAssets.verifiedRemoteFile(contextCache, handle))
        assertArrayEquals(bytes, JarvysHeaderAnimationAssets.verifiedRemoteFile(contextCache, handle).inputStream().use {
            JarvysHeaderAnimationAssets.readVerified(it, bytes.size, sha)
        })
    }
    @Test fun contextSpellingItselfIsAlsoAllowedButAnUnrelatedAliasIsNot() = fixture { temp, cache, contextCache, handle ->
        val contextHandle = handle.copy(file = File(File(contextCache, "jarvys_animations"), handle.file.name))
        assertEquals(handle.file, JarvysHeaderAnimationAssets.verifiedRemoteFile(contextCache, contextHandle))
        val unrelated = File(temp, "unrelated")
        Files.createSymbolicLink(unrelated.toPath(), cache.toPath())
        rejected { JarvysHeaderAnimationAssets.verifiedRemoteFile(contextCache, handle.copy(file = File(File(unrelated, "jarvys_animations"), handle.file.name))) }
    }
    @Test fun dedicatedAnimationDirectoryAliasIsRejected() = fixture { _, cache, contextCache, handle ->
        val directory = File(cache, "jarvys_animations"); val moved = File(cache, "moved")
        assertTrue(directory.renameTo(moved)); Files.createSymbolicLink(directory.toPath(), moved.toPath())
        rejected { JarvysHeaderAnimationAssets.verifiedRemoteFile(contextCache, handle) }
    }
    @Test fun finalFileSymlinkOutsideTheDedicatedDirectoryIsRejected() = fixture { temp, _, contextCache, handle ->
        val outside = File(temp, "outside.riv").apply { writeBytes(bytes) }
        assertTrue(handle.file.delete()); Files.createSymbolicLink(handle.file.toPath(), outside.toPath())
        rejected { JarvysHeaderAnimationAssets.verifiedRemoteFile(contextCache, handle) }
    }
    @Test fun wrongFilenameIdPackageAndSizeAreRejected() = fixture { _, _, contextCache, handle ->
        for (bad in listOf(handle.copy(file = File(handle.file.parentFile, "different.riv")), handle.copy(animationId = "thinking"),
            handle.copy(animationId = "../sad"), handle.copy(packageId = " "), handle.copy(bytes = 7), handle.copy(bytes = Int.MAX_VALUE.toLong() + 1)))
            rejected { JarvysHeaderAnimationAssets.verifiedRemoteFile(contextCache, bad) }
        val exact = JarvysHeaderAnimationAssets.verifiedRemoteFile(contextCache, handle)
        rejected { exact.inputStream().use { JarvysHeaderAnimationAssets.readVerified(it, bytes.size + 1, sha) } }
    }
    @Test fun pathAcceptanceDoesNotReplaceHashAndLengthVerification() = fixture { _, _, contextCache, handle ->
        val wrongSha = "b".repeat(64)
        val tampered = File(handle.file.parentFile, "sad-$wrongSha.riv").apply { writeBytes(bytes) }
        val matchedPath = JarvysHeaderAnimationAssets.verifiedRemoteFile(contextCache, handle.copy(file = tampered, sha256 = wrongSha))
        rejected { matchedPath.inputStream().use { JarvysHeaderAnimationAssets.readVerified(it, bytes.size, wrongSha) } }
    }
}
