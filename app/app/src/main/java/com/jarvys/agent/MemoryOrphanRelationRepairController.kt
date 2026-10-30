package com.jarvys.agent

import android.content.Context
import android.os.Looper
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.MemoryRepairBackupStore
import com.jarvys.agent.chatdb.MemoryOrphanRelationRepairPlan
import com.jarvys.agent.chatdb.MemoryOrphanRelationRepairReceipt
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class MemoryOrphanRelationRepairOperationHandle internal constructor()

/** One app-owned operation remains occupied until its actual worker/SQLite/file work has drained. */
class MemoryOrphanRelationRepairController private constructor(private val context: Context) {
    private class Active(val token: CancellationToken,
        val handle: MemoryOrphanRelationRepairOperationHandle = MemoryOrphanRelationRepairOperationHandle())
    private val active = AtomicReference<Active?>()
    fun activeOperation(): MemoryOrphanRelationRepairOperationHandle? = active.get()?.handle
    fun cancelIfCurrent(expected: MemoryOrphanRelationRepairOperationHandle): Boolean {
        val operation = active.get() ?: return false
        if (operation.handle !== expected) return false
        operation.token.cancel()
        return true
    }
    fun preview(token: CancellationToken): MemoryOrphanRelationRepairPlan = operation(token) {
        ChatDatabaseBridge.getInstance(context).personalAgentMemoryOrphanRelationRepairPreview(token)
    }
    fun apply(plan: MemoryOrphanRelationRepairPlan, confirmedWholeDatabaseCopy: Boolean,
        token: CancellationToken): MemoryOrphanRelationRepairReceipt = operation(token) {
        ChatDatabaseBridge.getInstance(context).personalAgentMemoryOrphanRelationRepairApply(plan, confirmedWholeDatabaseCopy, token)
    }
    // Deliberately does not obtain a Bridge, Room, Registry or directory-creating Context getter.
    fun backups(token: CancellationToken) = operation(token) { MemoryRepairBackupStore(context).inventory(token) }
    private fun <T> operation(token: CancellationToken, block: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "memory_repair_worker_required" }
        token.throwIfCancelled()
        val operation = Active(token)
        check(active.compareAndSet(null, operation)) { "memory_repair_busy" }
        try { return block() } finally { active.compareAndSet(operation, null) }
    }
    companion object {
        private val instances = ConcurrentHashMap<String, MemoryOrphanRelationRepairController>()
        @JvmStatic fun get(context: Context): MemoryOrphanRelationRepairController {
            val app = context.applicationContext
            val root = MemoryDoctorPaths.root(app)
            return instances.computeIfAbsent(root.absolutePath) { MemoryOrphanRelationRepairController(app) }
        }
    }
}
