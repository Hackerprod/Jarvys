package com.jarvys.agent.workspaces

import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.identity
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.values
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.one
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.s
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.next
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.eligible
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.integrity
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.nonempty
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.optional
import android.database.sqlite.SQLiteDatabase
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
    private fun withdrawn(db:SQLiteDatabase,child:TreeNode):Boolean {
        if(child.state!=TreeNodeState.TERMINAL || child.terminalOutcome!="CANCELLED" || child.botId!=null || child.executionEventId!=null ||
            child.controlIntent!=null || child.reportReceipt==null)return false
        val disposition=one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(child.rootId,identity("cancel-target",child.nodeId,child.cycleId))) {c ->
            val receipt=c.s("receipt")
            integrity(c.s("kind")=="CANCEL_TARGET" && c.s("node_id")==child.nodeId && c.s("cycle_id")==child.cycleId &&
                c.s("payload_ref")==child.nodeId && c.s("payload_digest")==identity(receipt) && c.optional("wait_id")==null,"Withdrawal disposition corrupted")
            receipt to c.s("source_receipt")
        } ?: return false // Requester STOP is not dependency withdrawal.
        if(disposition.first!="CANCELLED")return false // BUDGET is never successful withdrawal.
        val requestKey=disposition.second
        val requestOwner=one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(child.rootId,identity("cancel-request",requestKey))) {c ->
            Triple(c.s("node_id"),c.s("cycle_id"),c.getLong(c.getColumnIndexOrThrow("node_revision")))
        } ?: throw TreeIntegrityException("Withdrawal has no explicit cancellation request")
        val raw=core.event(db,child.rootId,identity("cancel-capture",requestKey)) ?: throw TreeIntegrityException("Withdrawal has no capture")
        val preliminary=try { JSONObject(raw).getJSONArray("targets") } catch(e:org.json.JSONException) {throw TreeIntegrityException("Malformed withdrawal capture")}
        val roots=(0 until preliminary.length()).map {preliminary.getJSONObject(it)}.filter {it.optString("node")==requestOwner.first}
        integrity(roots.size==1,"Withdrawal request target missing")
        val requestFence=TreeNodeFence(child.rootId,requestOwner.first,requestOwner.second,requestOwner.third,roots.single().getLong("assignmentRevision"))
        val captures=core.confirmedCancellationCapture(db,requestFence,"CANCELLED",requestKey)
        val captured=captures[child.nodeId] ?: throw TreeIntegrityException("Withdrawal target absent from exact capture")
        integrity(captured.isNull("bot") && captured.getString("workspace")==child.workspaceId && captured.getString("assignment")==child.assignmentId &&
            captured.getString("cycle")==child.cycleId && captured.getLong("assignmentRevision")==child.assignmentRevision,"Withdrawal native identity changed")
        core.requireNativeAssignment(child,WorkspaceAssignmentState.PREPARED)
        integrity(one(db,"bot_tree_event","root_id=? AND node_id=? AND kind='EXECUTION'",arrayOf(child.rootId,child.nodeId)){it.s("event_key")}==null,
            "Executed work cannot be withdrawn as never-started")
        val terminalKey=identity("terminal",child.nodeId,child.cycleId)
        val terminal=one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(child.rootId,terminalKey)) {c ->
            val receipt=c.s("receipt")
            integrity(c.s("kind")=="TERMINAL" && c.s("node_id")==child.nodeId && c.s("cycle_id")==child.cycleId &&
                c.s("payload_ref")==child.terminalRef && child.terminalRef==identity("cancelled-admission",child.nodeId,child.cycleId) &&
                c.s("source_receipt")==core.requireNode(db,child.rootId,requestOwner.first).sourceReceipt && c.s("payload_digest")==identity(receipt) && c.s("payload_digest")==child.terminalHash &&
                c.optional("wait_id")==null,"Withdrawal terminal proof changed")
            receipt
        } ?: throw TreeIntegrityException("Withdrawal terminal missing")
        val evidence=JSONObject(terminal)
        integrity(evidence.keys().asSequence().toSet()==setOf("kind","rootId","nodeId","cycleId","assignmentId","cancelOwner","reason") &&
            evidence.keys().asSequence().all {evidence.get(it) is String} &&
            evidence.getString("kind")=="CANCELLED_ADMISSION" && evidence.getString("rootId")==child.rootId && evidence.getString("nodeId")==child.nodeId &&
            evidence.getString("cycleId")==child.cycleId && evidence.getString("assignmentId")==child.assignmentId && evidence.getString("reason")=="CANCELLED" &&
            evidence.getString("cancelOwner")==requestOwner.first,"Withdrawal is not exact never-started cancellation")
        val report=one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(child.rootId,identity("reported",terminalKey))) {c ->
            val receipt=c.s("receipt")
            integrity(c.s("kind")=="REPORTED" && c.s("node_id")==child.nodeId && c.s("cycle_id")==child.cycleId && c.s("source_receipt")==terminalKey &&
                c.s("payload_ref")==child.terminalRef && c.s("payload_digest")==identity(receipt) && c.optional("wait_id")==null,"Withdrawal report proof changed")
            receipt
        } ?: throw TreeIntegrityException("Withdrawal report missing")
        integrity(report==child.reportReceipt && report=="cancelled-admission:"+child.terminalHash,"Withdrawal report changed")
        return true
    }
    private fun pending(db:SQLiteDatabase,parent:TreeNode):Boolean = core.childrenDb(db,parent.rootId,parent.nodeId).filter {it.reply==TreeReply.AWAIT}.any { child ->
        child.state!=TreeNodeState.TERMINAL || child.reportReceipt==null || child.controlIntent!=null ||
            one(db,"bot_tree_event","root_id=? AND kind='CENTRAL_INCORPORATED' AND payload_ref=?",arrayOf(parent.rootId,requireNotNull(child.terminalRef))){it.s("event_key")}==null && !withdrawn(db,child)
    }
    /** Existing confirmed exit only: metadata reconciliation never creates a run, claim, input or progress reset. */
    fun reconcileWithdrawals(rootId:String):TreeNode=core.registry.treeTransaction {db ->
        val parent=core.requireNode(db,rootId,rootId);val root=core.requireRoot(db,rootId)
        if(parent.state!=TreeNodeState.WAITING || parent.controlIntent!=null || root.admissionsClosed || root.wakesClosed)return@treeTransaction parent
        fun confirmed(kind:String,key:String):String?=one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(rootId,key)) {c ->
            val receipt=c.s("receipt")
            integrity(c.s("kind")==kind && c.s("node_id")==parent.nodeId && c.s("cycle_id")==parent.cycleId && c.s("source_receipt")==parent.sourceReceipt &&
                c.s("payload_ref")==parent.cycleId && c.s("payload_digest")==identity(receipt) && c.optional("wait_id")==null,"Confirmed Central exit changed")
            receipt
        }
        val exitKey=identity("central-exit",parent.cycleId)
        val exit=confirmed("CENTRAL_EXIT",exitKey) ?: return@treeTransaction parent
        val wait=confirmed("CENTRAL_WAIT",identity("central-wait",parent.cycleId)) ?: return@treeTransaction parent
        integrity(wait==exit,"Central wait/exit receipt changed")
        if(pending(db,parent) || core.childrenDb(db,rootId,parent.nodeId).none {it.reply==TreeReply.AWAIT && withdrawn(db,it)})return@treeTransaction parent
        val terminal=core.updateNode(db,parent,values("state" to "TERMINAL","terminal_ref" to exitKey,"terminal_hash" to identity(exit),"terminal_outcome" to "SUCCESS"))
        core.closeRoot(db,rootId,"COMPLETED");terminal
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
        val pending=pending(db,parent)
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
