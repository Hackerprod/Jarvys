package com.jarvys.agent

import android.content.Context
import android.os.Looper
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.MemoryProjectPruneException
import com.jarvys.agent.chatdb.MemoryProjectPrunePreview
import com.jarvys.agent.chatdb.MemoryProjectPruneReceipt
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class MemoryProjectPruneOperationHandle internal constructor()

/** App-owned slot survives dialog disposal until the real Bridge worker has drained. */
class MemoryProjectPruneController private constructor(private val context:Context) {
    private class Active(val token:CancellationToken,val handle:MemoryProjectPruneOperationHandle=MemoryProjectPruneOperationHandle())
    private val active=AtomicReference<Active?>()
    fun activeOperation():MemoryProjectPruneOperationHandle?=active.get()?.handle
    fun cancelIfCurrent(expected:MemoryProjectPruneOperationHandle):Boolean {
        val operation=active.get()?:return false
        if(operation.handle!==expected)return false
        operation.token.cancel();return true
    }
    fun projects(token:CancellationToken):List<String> = operation(token) { ChatDatabaseBridge.getInstance(context).personalAgentMemoryPruneProjects(token) }
    fun preview(project:String,token:CancellationToken):MemoryProjectPrunePreview = operation(token) { ChatDatabaseBridge.getInstance(context).personalAgentMemoryPrunePreview(project,token) }
    fun apply(preview:MemoryProjectPrunePreview,token:CancellationToken):MemoryProjectPruneReceipt = operation(token) {
        // Preview is not a CAS or a grant. Apply re-derives the whole predicate in the current writer.
        ChatDatabaseBridge.getInstance(context).personalAgentMemoryPruneApply(preview.project,token)
    }
    private fun <T> operation(token:CancellationToken,block:()->T):T {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "memory_prune_worker_required" }
        token.throwIfCancelled()
        val operation=Active(token)
        if(!active.compareAndSet(null,operation))throw MemoryProjectPruneException("memory_prune_busy")
        try { return block() } finally { active.compareAndSet(operation,null) }
    }
    companion object {
        private val instances=ConcurrentHashMap<String,MemoryProjectPruneController>()
        @JvmStatic fun get(context:Context):MemoryProjectPruneController {
            val app=context.applicationContext
            return instances.computeIfAbsent(app.filesDir.absolutePath) { MemoryProjectPruneController(app) }
        }
    }
}
