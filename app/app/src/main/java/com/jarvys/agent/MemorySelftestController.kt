package com.jarvys.agent

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** An observed cancellation identity, never permission over a user store. */
class MemorySelftestOperationHandle internal constructor()

class MemorySelftestController private constructor(private val context: Context) {
    private class Active(val token: CancellationToken, val handle: MemorySelftestOperationHandle = MemorySelftestOperationHandle())
    private val active = AtomicReference<Active?>()
    /** Instance-local observation seam; production has no observer and never exposes a scratch path. */
    @Volatile internal var boundaryObserver: ((MemorySelftestBoundary, java.io.File) -> Unit)? = null
    fun isRunning(): Boolean = active.get() != null
    fun activeOperation(): MemorySelftestOperationHandle? = active.get()?.handle
    fun cancelIfCurrent(expected: MemorySelftestOperationHandle): Boolean {
        val observed = active.get() ?: return false
        if (observed.handle !== expected) return false
        observed.token.cancel()
        return true
    }
    /** Blocking off-UI operation. Dialog/coroutine disposal never releases its physical ownership. */
    fun execute(options: MemorySelftestOptions, token: CancellationToken): MemorySelftestResult {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "memory_selftest_worker_required" }
        val normalized = options.normalized() // No temp allocation for invalid options.
        val observer = boundaryObserver // One immutable observer selection for the whole operation.
        val operation = Active(token)
        check(active.compareAndSet(null, operation)) { "memory_selftest_busy" }
        var release = false
        try {
            val completion = MemorySelftestRunner.run(context, normalized, token, observer)
            release = completion.physicallyUnwound
            return completion.result
        } finally {
            if (release) active.compareAndSet(operation, null)
        }
    }
    companion object {
        private val instances = ConcurrentHashMap<String, MemorySelftestController>()
        @JvmStatic fun get(context: Context): MemorySelftestController {
            val application = context.applicationContext
            val key = application.cacheDir.absolutePath
            return instances.computeIfAbsent(key) { MemorySelftestController(application) }
        }
    }
}
