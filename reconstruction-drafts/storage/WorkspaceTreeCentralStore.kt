package com.jarvys.agent.workspaces

import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.identity
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.values
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.one
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.s
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.next
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.eligible
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.integrity
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.nonempty
import org.json.JSONObject

/** Atomic accounting only. Callers must hold the actual service/P1 capability, never a model DTO. */
internal class WorkspaceTreeCentralStore(private val core:WorkspaceTreeStore) {
    fun attach(fence:TreeNodeFence,cycle:String,source:String,claim:String,childDue:Boolean,childId:String):TreeNode = core.registry.treeTransaction { db ->
        nonempty(cycle);nonempty(source);nonempty(claim)
        val parent=core.active(db,fence)
        eligible(parent.mode==TreeMode.CENTRAL && parent.nodeId==parent.rootId,"Not the Central root")
        eligible(childDue,"Only exact claimed P1 continuation can attach")
        val child=core.requireNode(db,parent.rootId,childId)
        eligible(child.parentNodeId==parent.nodeId && child.reply==TreeReply.AWAIT && child.reportReceipt!=null &&
            child.state in setOf(TreeNodeState.TERMINAL,TreeNodeState.NEEDS_INPUT),"Child has no exact reported cause")
        val key=identity("central-claim",claim)
        val receipt=JSONObject().put("claim",claim).put("child",childId).put("cycle",cycle)
            .put("source",source).put("evidence",child.terminalRef).put("digest",child.terminalHash).toString()
        val prior=core.event(db,parent.rootId,key)
        if(prior!=null) { integrity(prior==receipt && parent.cycleId==cycle,"Central claim changed");return@treeTransaction parent }
        eligible(parent.state==TreeNodeState.WAITING,"Central request has no confirmed parked turn")
        eligible(one(db,"bot_tree_event","root_id=? AND kind='CENTRAL_CLAIM' AND payload_ref=?",arrayOf(parent.rootId,child.terminalRef!!)){it.s("event_key")}==null,
            "Result was already claimed")
        core.appendEvent(db,parent,key,"CENTRAL_CLAIM",receipt,source,identity(receipt),child.terminalRef)
        val root=core.requireRoot(db,parent.rootId)
        core.updateRoot(db,root,values("executions" to next(root.executions)))
        core.updateNode(db,parent,values("cycle_id" to cycle,"state" to "RUNNING","control_intent" to claim))
    }
    fun incorporated(rootId:String,childId:String,claim:String,receipt:String)=core.registry.treeTransaction { db ->
        nonempty(receipt);val child=core.requireNode(db,rootId,childId)
        val central=core.requireNode(db,rootId,rootId)
        val raw=core.event(db,rootId,identity("central-claim",claim)) ?: throw TreeIntegrityException("Missing Central claim")
        val proof=JSONObject(raw)
        integrity(proof.getString("child")==childId && proof.getString("evidence")==child.terminalRef &&
            proof.getString("digest")==child.terminalHash,"Claim evidence changed")
        val key=identity("central-incorporated",claim)
        core.event(db,rootId,key)?.let { integrity(it==receipt,"Incorporation changed");return@treeTransaction }
        eligible(central.controlIntent==claim && central.cycleId==proof.getString("cycle"),"Central claimant changed")
        core.appendEvent(db,central,key,"CENTRAL_INCORPORATED",receipt,claim,identity(receipt),child.terminalRef!!)
        core.updateNode(db,central,values("control_intent" to null))
    }
    fun finish(fence:TreeNodeFence,receipt:String,stopped:Boolean):TreeNode=core.registry.treeTransaction { db ->
        nonempty(receipt)
        val parent=core.requireNode(db,fence.rootId,fence.nodeId)
        eligible(parent.mode==TreeMode.CENTRAL && TreeNodeFence.of(parent)==fence,"Central owner changed")
        if(stopped) {
            core.stopRequester(fence) { targets -> targets.forEach { WorkspaceTreeStops.latchExact(core.registry.treeStorageIdentity,it.rootId,it.nodeId,it.cycleId) } }
            return@treeTransaction core.requireNode(db,parent.rootId,parent.nodeId)
        }
        core.active(db,fence)
        val key=identity("central-exit",parent.cycleId)
        core.appendEvent(db,parent,key,"CENTRAL_EXIT",receipt,parent.sourceReceipt,identity(receipt),parent.cycleId)
        eligible(parent.controlIntent==null,"Unresolved Central claim")
        val dependencies=core.childrenDb(db,parent.rootId,parent.nodeId).filter { it.reply==TreeReply.AWAIT }
        val pending=dependencies.any { child ->
            child.state!=TreeNodeState.TERMINAL || child.reportReceipt==null || child.controlIntent!=null ||
                one(db,"bot_tree_event","root_id=? AND kind='CENTRAL_INCORPORATED' AND payload_ref=?",
                    arrayOf(parent.rootId,requireNotNull(child.terminalRef))){it.s("event_key")}==null
        }
        if(pending) {
            val waiting=core.updateNode(db,parent,values("state" to "WAITING"))
            if(core.event(db,parent.rootId,identity("central-wait",parent.cycleId))==null) {
                core.appendEvent(db,waiting,identity("central-wait",parent.cycleId),"CENTRAL_WAIT",receipt,parent.sourceReceipt,identity(receipt),parent.cycleId)
                core.resetProgress(db,parent.rootId)
            }
            waiting
        } else {
            val terminal=core.updateNode(db,parent,values("state" to "TERMINAL","terminal_ref" to key,"terminal_hash" to identity(receipt),"terminal_outcome" to "SUCCESS"))
            core.closeRoot(db,parent.rootId,"COMPLETED");terminal
        }
    }
}
