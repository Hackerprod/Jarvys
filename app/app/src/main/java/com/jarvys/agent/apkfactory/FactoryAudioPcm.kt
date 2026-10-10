package com.jarvys.agent.apkfactory

/** A strictly bounded, canonical PCM16 WAV. Ownership of [bytes] transfers to this object. */
class FactoryAudioPcm private constructor(
    val bytes: ByteArray,
    val pcmBytes: Int,
    val channels: Int,
    val sampleRate: Int,
    val frames: Int,
    val durationMs: Long
) {
    val pcmOffset: Int get() = 44

    companion object {
        const val MAX_BYTES = 6 * 1024 * 1024
        fun parseOwned(bytes: ByteArray): FactoryAudioPcm {
            require(bytes.size in 44..MAX_BYTES) { "WAV envelope outside limit" }
            fun tag(offset: Int, value: String) = value.indices.all { bytes[offset + it].toInt() == value[it].code }
            fun u16(offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
            fun u32(offset: Int): Long = (0..3).fold(0L) { value, i -> value or ((bytes[offset + i].toLong() and 255L) shl (8 * i)) }
            require(tag(0, "RIFF") && tag(8, "WAVE") && tag(12, "fmt ") && tag(36, "data")) { "Noncanonical WAV chunks" }
            require(u32(4) == bytes.size.toLong() - 8 && u32(16) == 16L) { "Invalid WAV lengths" }
            require(u16(20) == 1 && u16(34) == 16) { "Only PCM16 supported" }
            val channels = u16(22)
            val rate = u32(24)
            require(channels in 1..2 && rate in 8000L..48000L) { "Unsupported PCM format" }
            val alignment = channels * 2
            require(u16(32) == alignment && u32(28) == rate * alignment) { "Invalid PCM alignment or rate" }
            val size = u32(40)
            require(size == bytes.size.toLong() - 44 && size > 0 && size % alignment == 0L) { "Invalid PCM data length" }
            val frames = size / alignment
            require(frames <= rate * 30L) { "Audio exceeds 30 seconds" }
            return FactoryAudioPcm(bytes, size.toInt(), channels, rate.toInt(), frames.toInt(), (frames * 1000L + rate - 1L) / rate)
        }
    }
}
