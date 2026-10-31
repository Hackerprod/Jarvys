package com.jarvys.agent.workspaces

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** Process-local denial only. Never clears, grants, restores, or transfers a capability. */
object WorkspaceTreeStops {
    private val admissionLock=Any()
    private val predecessors=ConcurrentHashMap<String,String>()
    private val stopped=ConcurrentHashMap.newKeySet<String>()
    private fun key(storage:String,root:String,node:String,cycle:String)="$storage\u0000$root\u0000$node\u0000$cycle"
    @JvmStatic fun latchRoot(context:Context,root:String,cycle:String)=latchNode(context,root,root,cycle)
    @JvmStatic fun latchNode(context:Context,root:String,node:String,cycle:String) {
        latchExact(context.applicationContext.getDatabasePath(WorkspaceRegistry.DATABASE_NAME).canonicalPath,root,node,cycle)
    }
    internal fun latchExact(storage:String,root:String,node:String,cycle:String) {
        val identity=key(storage,root,node,cycle)
        synchronized(admissionLock) { stopped.add(identity) }
    }
    internal fun requireAllowed(storage:String,root:String,node:String,cycle:String) {
        var current=key(storage,root,node,cycle)
        val visited=HashSet<String>()
        while(true) {
            if(!visited.add(current))throw TreeIntegrityException("Cyclic process successor denial")
            if(current in stopped)throw TreeIneligibleException("Exact tree cycle STOP latched")
            current=predecessors[current] ?: return
        }
    }
    internal fun linkChildWait(storage:String,root:String,node:String,oldCycle:String,newCycle:String) {
        synchronized(admissionLock) {
            requireAllowed(storage,root,node,oldCycle)
            val target=key(storage,root,node,newCycle);val source=key(storage,root,node,oldCycle)
            check(target!=source && predecessors.putIfAbsent(target,source).let { it==null || it==source })
        }
    }
    /** Native cycle -> denial -> mailbox. No Registry, storage, callbacks/listeners, or new locks in action. */
    internal fun admitNativeEnqueue(storage:String,root:String,rootCycle:String,node:String,nodeCycle:String,action:Runnable) {
        synchronized(admissionLock) {
            requireAllowed(storage,root,root,rootCycle);requireAllowed(storage,root,node,nodeCycle)
            action.run()
        }
    }
}
