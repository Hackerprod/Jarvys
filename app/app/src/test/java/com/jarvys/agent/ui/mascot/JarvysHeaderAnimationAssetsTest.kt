package com.jarvys.agent.ui.mascot

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class JarvysHeaderAnimationAssetsTest {
    private val bytes = byteArrayOf(82, 73, 86, 69, 0, 127, -1, -128)
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected integrity rejection") } catch (_: IllegalArgumentException) { } }
    @Test fun exactDeclaredBytesHashAndMagicAreRequired() {
        assertArrayEquals(bytes, JarvysHeaderAnimationAssets.readVerified(bytes.inputStream(), bytes.size, hash(bytes)))
    }
    @Test fun chunkedInputReturnsTheSameOriginalBytes() {
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(length, 1))
        }
        assertArrayEquals(bytes, JarvysHeaderAnimationAssets.readVerified(input, bytes.size, hash(bytes)))
    }
    @Test fun truncatedAndExtraBytesFailClosed() {
        rejected { JarvysHeaderAnimationAssets.readVerified(bytes.copyOf(7).inputStream(), 8, hash(bytes)) }
        rejected { JarvysHeaderAnimationAssets.readVerified((bytes + 1.toByte()).inputStream(), 8, hash(bytes)) }
    }
    @Test fun wrongHashAndMagicFailClosed() {
        rejected { JarvysHeaderAnimationAssets.readVerified(bytes.inputStream(), 8, "0".repeat(64)) }
        val invalid = bytes.copyOf().also { it[0] = 0 }
        rejected { JarvysHeaderAnimationAssets.readVerified(invalid.inputStream(), 8, hash(invalid)) }
    }
    @Test fun invalidDeclaredMetadataIsRejectedBeforeAllocation() {
        for (size in listOf(-1, 0, 7)) rejected { JarvysHeaderAnimationAssets.readVerified(bytes.inputStream(), size, hash(bytes)) }
        for (sha in listOf("", "a".repeat(63), "G".repeat(64), "A".repeat(64)))
            rejected { JarvysHeaderAnimationAssets.readVerified(bytes.inputStream(), 8, sha) }
    }
}
