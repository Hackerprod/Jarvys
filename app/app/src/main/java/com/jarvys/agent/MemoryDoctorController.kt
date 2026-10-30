package com.jarvys.agent

import android.content.Context
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class MemoryDoctorOperationHandle internal constructor()
class MemoryDoctorController private constructor(private val context: Context) {
    private class Active(val token: CancellationToken, val handle: MemoryDoctorOperationHandle = MemoryDoctorOperationHandle())
    private val active = AtomicReference<Active?>()
    fun isRunning(): Boolean = active.get() != null
    fun activeOperation(): MemoryDoctorOperationHandle? = active.get()?.handle
    fun cancelIfCurrent(expected: MemoryDoctorOperationHandle): Boolean {
        val observed = active.get() ?: return false
        if (observed.handle !== expected) return false
        observed.token.cancel(); return true
    }
    fun projects(token: CancellationToken): List<String> = withOperation(token) {
        MemoryDoctorReader.projects(context, token).also { token.throwIfCancelled() }
    }
    fun execute(options: MemoryDoctorOptions, token: CancellationToken): MemoryDoctorReport {
        val selected = options.normalized() // Unknown check rejected before any acquisition.
        return withOperation(token) {
            val result = try { MemoryDoctorReader.execute(context, selected, token) }
            catch (unavailable: MemoryDoctorUnavailable) {
                token.throwIfCancelled()
                MemoryDoctorReader.unavailable(selected, unavailable.reasonCode)
            }
            token.throwIfCancelled()
            result
        }
    }
    private fun <T> withOperation(token: CancellationToken, block: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "doctor_worker_required" }
        token.throwIfCancelled()
        val operation = Active(token)
        check(active.compareAndSet(null, operation)) { "doctor_busy" }
        var physical = true
        try { return block() }
        catch (uncertain: MemoryDoctorCloseUnconfirmed) { physical = false; throw uncertain }
        finally { if (physical) active.compareAndSet(operation, null) }
    }
    companion object {
        private val instances = ConcurrentHashMap<String, MemoryDoctorController>()
        @JvmStatic fun get(context: Context): MemoryDoctorController {
            val app = context.applicationContext
            return instances.computeIfAbsent(MemoryDoctorPaths.root(app).absolutePath) { MemoryDoctorController(app) }
        }
    }
}
