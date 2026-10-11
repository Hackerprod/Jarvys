package com.jarvys.agent.apkfactory

import java.util.concurrent.atomic.AtomicBoolean

/** One bounded buffer, partial native writes, and cooperative cancellation. No whole-clip array. */
internal class FactoryAudioFeeder(
    private val pcm: FactoryAudioPcm,
    private val write: (ByteArray, Int, Int) -> Int,
    private val waitForCapacity: () -> Unit = { Thread.sleep(10) },
) {
    private val cancelled = AtomicBoolean(false)
    private val buffer = ByteArray(32 * 1024)
    private var offset = 0
    private var length = 0
    private var primed = false
    fun cancel() { cancelled.set(true) }
    /** Worker-only. Prime at most one bounded read/write; zero capacity is normal before play. */
    fun prime(requireComplete: Boolean = false) {
        check(!primed && !cancelled.get()); primed = true
        length = pcm.read(buffer)
        check(length > 0)
        if (cancelled.get()) return
        writePending()
        if (requireComplete) require(offset == length) { "Incomplete short PCM prefill" }
    }
    /** Worker-only. Native sink must use nonblocking writes. Owner enforces playback deadline. */
    fun run() {
        check(primed)
        try {
            while (!cancelled.get()) {
                if (offset == length) {
                    length = pcm.read(buffer); offset = 0
                    if (length == -1) return
                }
                if (cancelled.get()) return
                if (writePending() == 0 && !cancelled.get()) waitForCapacity()
            }
        } finally { buffer.fill(0) }
    }
    private fun writePending(): Int {
        val count = write(buffer, offset, length - offset)
        require(count in 0..(length - offset) && count % (pcm.channels * 2) == 0) { "Native streaming PCM write failed" }
        offset += count
        return count
    }
    fun discard() { check(cancelled.get()); buffer.fill(0) }
}
