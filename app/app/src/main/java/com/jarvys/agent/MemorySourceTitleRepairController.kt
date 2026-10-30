package com.jarvys.agent

import android.content.Context
import android.os.Looper
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.MemoryRepairBackupStore
import com.jarvys.agent.chatdb.MemorySourceTitleRepairPlan
import com.jarvys.agent.chatdb.MemorySourceTitleRepairReceipt
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class MemorySourceTitleRepairOperationHandle internal constructor()

/** One app-owned operation remains occupied until its actual worker/SQLite/file work has drained. */
class MemorySourceTitleRepairController private constructor(private val context: Context) {
    private class Active(val token: CancellationToken,
        val handle: MemorySourceTitleRepairOperationHandle = MemorySourceTitleRepairOperationHandle())
    private val active = AtomicReference<Active?>()
    fun activeOperation(): MemorySourceTitleRepairOperationHandle? = active.get()?.handle
    fun cancelIfCurrent(expected: MemorySourceTitleRepairOperationHandle): Boolean {
        val operation = active.get() ?: return false
        if (operation.handle !== expected) return false
        operation.token.cancel()
        return true
    }
    fun projects(token: CancellationToken): List<String> = operation(token) {
        ChatDatabaseBridge.getInstance(context).personalAgentMemoryRepairProjects(token)
    }
    fun preview(project: String, token: CancellationToken): MemorySourceTitleRepairPlan = operation(token) {
        ChatDatabaseBridge.getInstance(context).personalAgentMemoryRepairPreview(project, token)
    }
    fun apply(plan: MemorySourceTitleRepairPlan, confirmedWholeDatabaseCopy: Boolean,
        token: CancellationToken): MemorySourceTitleRepairReceipt = operation(token) {
        ChatDatabaseBridge.getInstance(context).personalAgentMemoryRepairApply(plan, confirmedWholeDatabaseCopy, token)
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
        private val instances = ConcurrentHashMap<String, MemorySourceTitleRepairController>()
        @JvmStatic fun get(context: Context): MemorySourceTitleRepairController {
            val app = context.applicationContext
            val root = MemoryDoctorPaths.root(app)
            return instances.computeIfAbsent(root.absolutePath) { MemorySourceTitleRepairController(app) }
        }
    }
}
