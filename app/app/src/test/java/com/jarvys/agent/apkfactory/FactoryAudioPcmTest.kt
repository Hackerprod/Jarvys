package com.jarvys.agent.apkfactory

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun syntheticWav(rate: Int = 8000, channels: Int = 1, frames: Int = 80): ByteArray {
    val data = frames * channels * 2
    return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + data); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(channels.toShort()); putInt(rate)
        putInt(rate * channels * 2); putShort((channels * 2).toShort()); putShort(16)
        put("data".toByteArray()); putInt(data)
    }.array()
}
class FactoryAudioPcmTest {
    @Test fun ownsBytesWithoutCopyAndRoundsDurationUp() {
        val bytes = syntheticWav(48000, 2, 1)
        val pcm = FactoryAudioPcm.parseOwned(bytes)
        assertSame(bytes, pcm.bytes); assertEquals(44, pcm.pcmOffset)
        assertEquals(4, pcm.pcmBytes); assertEquals(1L, pcm.durationMs)
    }
    @Test fun exactThirtySecondBoundary() {
        assertEquals(30000L, FactoryAudioPcm.parseOwned(syntheticWav(48000, 2, 1440000)).durationMs)
        reject(syntheticWav(48000, 2, 1440001))
    }
    @Test fun rejectsTruncationTrailingDataAndEnvelope() {
        reject(ByteArray(43)); reject(ByteArray(FactoryAudioPcm.MAX_BYTES + 1))
        reject(syntheticWav().dropLast(1).toByteArray()); reject(syntheticWav() + byteArrayOf(0))
    }
    @Test fun rejectsNoncanonicalFieldsAndUnsignedOverflow() {
        for (offset in listOf(0, 8, 12, 16, 20, 22, 24, 28, 32, 34, 36, 40, 4)) {
            val bytes = syntheticWav(); bytes[offset] = 0xFF.toByte(); reject(bytes)
        }
        reject(syntheticWav(frames = 0)); reject(syntheticWav(rate = 7999)); reject(syntheticWav(rate = 48001))
        reject(syntheticWav(channels = 3))
    }
    @Test fun rejectsFractionalFrames() {
        val bytes = syntheticWav(channels = 2).copyOf(47)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 39).putInt(40, 3)
        reject(bytes)
    }
    private fun reject(bytes: ByteArray) {
        try { FactoryAudioPcm.parseOwned(bytes); fail("Accepted invalid WAV") } catch (_: IllegalArgumentException) { }
    }
}
