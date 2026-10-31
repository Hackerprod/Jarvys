package com.jarvys.agent.apkfactory

import com.jarvys.factory.contract.ResourceBudgets
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class FactoryAudioStreamingTest {
    private class VirtualWav(val dataBytes: Long, rate: Int = 48000, channels: Int = 2, val shortReads: Boolean = false) : InputStream() {
        private val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt()); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(channels.toShort()); putInt(rate)
            putInt(rate * channels * 2); putShort((channels * 2).toShort()); putShort(16)
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }.array()
        var readBytes = 0L; var largestRead = 0; var closed = false; var closes = 0; var closeFails = false
        override fun read(): Int = error("Chunk reads only")
        override fun read(out: ByteArray, offset: Int, length: Int): Int {
            check(!closed); largestRead = maxOf(largestRead, length)
            if (readBytes == dataBytes + 44) return -1
            val count = minOf(length.toLong(), dataBytes + 44 - readBytes, if (shortReads) 7L else Long.MAX_VALUE).toInt()
            repeat(count) { i -> val position = readBytes + i; out[offset + i] = if (position < 44) header[position.toInt()] else ((position - 44) and 255).toByte() }
            readBytes += count; return count
        }
        override fun close() { closes++; if (closeFails) error("close failed"); closed = true }
    }
    private fun parse(source: VirtualWav) = FactoryAudioPcm.parseStream(source, source.dataBytes + 44, ResourceBudgets.maxima().audio)
    @Test fun largeStereoClipNeverAllocatesOrReadsWholePayloadAndHandlesPartialNativeWrites() {
        val input = VirtualWav(12L * 1024 * 1024, shortReads = true); val pcm = parse(input)
        assertEquals(44L, input.readBytes); assertEquals(0, pcm.bytes.size)
        var written = 0L; var calls = 0; var waits = 0
        val feeder = FactoryAudioFeeder(pcm, { buffer, offset, count ->
            calls++
            if (calls % 11 == 0) 0 else {
                val accepted = minOf(count, 1020)
                repeat(accepted) { i -> assertEquals(((written + i) and 255).toByte(), buffer[offset + i]) }
                written += accepted; accepted
            }
        }, { waits++ })
        feeder.prime(); assertTrue(input.readBytes <= 44 + 32 * 1024)
        feeder.run()
        assertEquals(input.dataBytes, written); assertTrue(waits > 0); assertTrue(input.largestRead <= 32768)
        pcm.close(); assertTrue(input.closed)
    }
    @Test fun fourGibEnvelopeAndLongDurationAreParsedUsingOnlyHeaderWithSafeMarker() {
        val input = VirtualWav(FactoryAudioPcm.MAX_STREAM_BYTES - 44, rate = 8000, channels = 1)
        val pcm = parse(input)
        assertEquals(2147483626, pcm.frames); assertEquals(268435454L, pcm.durationMs)
        assertEquals(44L, input.readBytes); assertEquals(FactoryAudioPcm.MAX_STREAM_BYTES - 44, pcm.pcmBytes)
        pcm.close()
    }
    @Test fun smallSignedDurationAndSourceBudgetsRemainAuthoritative() {
        val source = VirtualWav(16)
        val budget = ResourceBudgets.parse(mapOf("version" to 1, "audio" to mapOf("sourceBytes" to 59))).audio
        assertThrows(IllegalArgumentException::class.java) { FactoryAudioPcm.parseStream(source, 60, budget) }
        assertEquals(0L, source.readBytes)
        val input = VirtualWav(192004)
        val duration = ResourceBudgets.parse(mapOf("version" to 1, "audio" to mapOf("durationMs" to 1000))).audio
        assertThrows(IllegalArgumentException::class.java) { FactoryAudioPcm.parseStream(input, 192048, duration) }
        assertEquals(44L, input.readBytes)
    }
    @Test fun zeroNativeCapacityCanBeCancelledWithoutRetryingOrReadingAnotherChunk() {
        val input = VirtualWav(12L * 1024 * 1024); val pcm = parse(input)
        var writes = 0; var waits = 0
        lateinit var feeder: FactoryAudioFeeder
        feeder = FactoryAudioFeeder(pcm, { _, _, _ -> writes++; 0 }, { waits++; feeder.cancel() })
        feeder.prime(); feeder.run()
        assertEquals(2, writes); assertEquals(1, waits); assertEquals(44L + 32768, input.readBytes)
        feeder.discard(); pcm.close()
    }
    @Test fun nativeNegativeOversizedAndUnalignedWritesFailClosed() {
        for (answer in listOf(-1, 32769, 1)) {
            val input = VirtualWav(32768); val pcm = parse(input)
            val feeder = FactoryAudioFeeder(pcm, { _, _, _ -> answer })
            assertThrows(IllegalArgumentException::class.java) { feeder.prime() }
            feeder.cancel(); feeder.discard(); pcm.close()
        }
    }
    @Test fun incompleteDiskReadAndCloseFailureDoNotLoseOwnership() {
        val input = VirtualWav(32768); val pcm = parse(input)
        input.closed = true
        val feeder = FactoryAudioFeeder(pcm, { _, _, count -> count })
        assertThrows(IllegalStateException::class.java) { feeder.prime() }
        input.closed = false; input.closeFails = true
        assertThrows(IllegalStateException::class.java) { pcm.close() }
        assertFalse(input.closed)
        input.closeFails = false; pcm.close(); assertTrue(input.closed); assertEquals(2, input.closes)
    }
}
