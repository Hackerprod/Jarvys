package com.jarvys.agent.apkfactory

import com.jarvys.factory.contract.ResourceBudgets
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Canonical PCM16 WAV metadata plus a single owned, sequential PCM source. Never decodes media. */
class FactoryAudioPcm private constructor(
    /** Legacy test/compatibility ownership only. Disk-backed playback always has an empty array. */
    val bytes: ByteArray,
    val pcmBytes: Long,
    val channels: Int,
    val sampleRate: Int,
    val frames: Int,
    val durationMs: Long,
    private val source: InputStream,
) : AutoCloseable {
    val pcmOffset: Int get() = 44
    private var remaining = pcmBytes
    private var closed = false
    internal fun read(buffer: ByteArray): Int {
        check(!closed)
        if (remaining == 0L) return -1
        val wanted = minOf(buffer.size.toLong(), remaining).toInt()
        var total = 0
        while (total < wanted) {
            val count = source.read(buffer, total, wanted - total)
            require(count > 0 && count <= wanted - total) { "Truncated PCM snapshot" }
            total += count
        }
        remaining -= total
        return total
    }
    /** Worker-only ownership disposal; caller must first prove the feeder has stopped. */
    override fun close() {
        if (closed) return
        source.close() // A failed close retains the source and cannot free admission.
        bytes.fill(0)
        closed = true
    }

    companion object {
        /** Exact old v1/whole-array envelope. This is not the disk-streaming limit. */
        const val MAX_BYTES = 6 * 1024 * 1024
        /** Shared disk protocol + canonical RIFF unsigned length representation. */
        const val MAX_STREAM_BYTES = 4L * 1024 * 1024 * 1024
        const val MAX_DURATION_MS = 268435454L
        fun parseOwned(bytes: ByteArray): FactoryAudioPcm = parseOwned(bytes, ResourceBudgets.legacy().audio)
        fun parseOwned(bytes: ByteArray, budget: ResourceBudgets.Audio): FactoryAudioPcm {
            require(bytes.size in 44..MAX_BYTES) { "Legacy WAV envelope outside limit" }
            return parse(bytes.copyOfRange(0, 44), bytes.size.toLong(), budget, bytes,
                ByteArrayInputStream(bytes, 44, bytes.size - 44))
        }
        /** Ownership transfers only on success. Header reads are bounded; all PCM remains on disk. */
        internal fun parseStream(source: InputStream, size: Long, budget: ResourceBudgets.Audio): FactoryAudioPcm {
            require(size in 46..MAX_STREAM_BYTES && size <= budget.sourceBytes) { "WAV envelope outside limit" }
            val header = ByteArray(44)
            var offset = 0
            while (offset < header.size) {
                val count = source.read(header, offset, header.size - offset)
                require(count > 0 && count <= header.size - offset) { "Truncated WAV header" }
                offset += count
            }
            return parse(header, size, budget, ByteArray(0), source)
        }
        private fun parse(header: ByteArray, size: Long, budget: ResourceBudgets.Audio, owned: ByteArray, source: InputStream): FactoryAudioPcm {
            require(size in 46..MAX_STREAM_BYTES && size <= budget.sourceBytes) { "WAV envelope outside limit" }
            fun tag(offset: Int, value: String) = value.indices.all { header[offset + it].toInt() == value[it].code }
            fun u16(offset: Int) = (header[offset].toInt() and 255) or ((header[offset + 1].toInt() and 255) shl 8)
            fun u32(offset: Int): Long = (0..3).fold(0L) { value, i -> value or ((header[offset + i].toLong() and 255L) shl (8 * i)) }
            require(tag(0, "RIFF") && tag(8, "WAVE") && tag(12, "fmt ") && tag(36, "data")) { "Noncanonical WAV chunks" }
            require(u32(4) == size - 8 && u32(16) == 16L) { "Invalid WAV lengths" }
            require(u16(20) == 1 && u16(34) == 16) { "Only PCM16 supported" }
            val channels = u16(22)
            val rate = u32(24)
            require(channels in 1..2 && rate in 8000L..48000L) { "Unsupported PCM format" }
            val alignment = channels * 2
            require(u16(32) == alignment && u32(28) == rate * alignment) { "Invalid PCM alignment or rate" }
            val dataSize = u32(40)
            require(dataSize == size - 44 && dataSize > 0 && dataSize % alignment == 0L) { "Invalid PCM data length" }
            val frames = dataSize / alignment
            require(frames <= Int.MAX_VALUE && frames * 1000L <= rate * minOf(budget.durationMs, MAX_DURATION_MS)) { "Audio exceeds its declared duration budget" }
            return FactoryAudioPcm(owned, dataSize, channels, rate.toInt(), frames.toInt(), (frames * 1000L + rate - 1L) / rate, source)
        }
    }
}
