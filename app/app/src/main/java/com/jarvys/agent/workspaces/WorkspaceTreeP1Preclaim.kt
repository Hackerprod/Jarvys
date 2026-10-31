package com.jarvys.agent.workspaces

import android.content.Context
import com.jarvys.agent.LocalRunStore
import com.jarvys.agent.WorkspaceContinuationDelivery
import org.json.JSONObject
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.identity
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.one
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.s
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.optional
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.integrity

/** Enrolled-only P2 constraint on P1 PREPARED. Never mints a result permit or changes P1 metadata. */
internal object WorkspaceTreeP1Preclaim {
    internal data class Enrollment(val storage:String,val rootId:String,val rootCycle:String) {
        fun requireNotStopped()=WorkspaceTreeStops.requireAllowed(storage,rootId,rootId,rootCycle)
    }
    fun requireEligible(context:Context,value:JSONObject):Enrollment? {
        val workspace=value.getString("workspaceId");val assignment=value.getString("assignmentId")
        return WorkspaceRegistry(context).use { registry ->
            val core=WorkspaceTreeStore(registry)
            val first=core.findAssignment(workspace,assignment) ?: return@use null // v98/un-enrolled P1 stays exactly P1.
            val original=core.getRoot(first.rootId) ?: throw TreeIntegrityException("Enrolled child root missing")
            // Canonical USER capture is Room IO outside Registry. Then hold its existing lifecycle fence for pure Registry reads.
            val source=LocalRunStore(context).captureCentralUserRoot(original.origin.ownerCentralSessionId,original.origin.originUserMessageId)
            integrity(source.storageGeneration==original.origin.storageGeneration && source.originDigest==original.origin.originDigest,
                "Original USER source changed")
            val lock=source.lifecycle.fenceIdentity();lock.lock()
            try {
                integrity(source.lifecycle.isCurrentPure(),"Original USER storage lifetime changed")
                registry.treeRead { db ->
                    val child=core.requireNode(db,first.rootId,first.nodeId)
                    val root=core.requireRoot(db,child.rootId);val parent=core.requireNode(db,root.rootId,root.rootId)
                    integrity(root.origin==original.origin && child.workspaceId==workspace && child.assignmentId==assignment &&
                        child.parentNodeId==parent.nodeId && parent.mode==TreeMode.CENTRAL &&
                        value.getString("ownerCentralSessionId")==root.origin.ownerCentralSessionId,"P1 tree owner changed")
                    eligible(value.getString("dispatchState")=="PREPARED" && !root.admissionsClosed && !root.wakesClosed &&
                        !parent.admissionsClosed && !parent.wakesClosed && parent.controlIntent==null && parent.state==TreeNodeState.WAITING,
                        "Original Central request is not eligible for a fresh result claim")
                    val enrollment=Enrollment(registry.treeStorageIdentity,root.rootId,parent.cycleId)
                    enrollment.requireNotStopped()
                    val wait=one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(root.rootId,identity("central-wait",parent.cycleId))) { c ->
                        integrity(c.s("kind")=="CENTRAL_WAIT" && c.s("node_id")==parent.nodeId && c.s("cycle_id")==parent.cycleId &&
                            c.s("payload_ref")==parent.cycleId && c.s("payload_digest")==identity(c.s("receipt")),"Confirmed Central WAIT source changed")
                        c.s("receipt") }
                    eligible(wait!=null,"Central WAIT has no confirmed native exit")
                    val selected=value.getJSONObject("selectedResult")
                    eligible(child.reply==TreeReply.AWAIT && child.state==TreeNodeState.TERMINAL && child.controlIntent==null && child.reportReceipt!=null,
                        "Child result is not terminal and reported")
                    integrity(child.botId==value.getString("botId") && child.cycleId==selected.getString("producerCycleId") &&
                        child.assignmentRevision==selected.getLong("assignmentRevision") && child.terminalRef==selected.getString("evidenceId") &&
                        child.terminalHash==selected.getString("contentSha256"),"Selected P1 result does not match current child evidence")
                    core.requireNativeAssignment(child)
                    val causeKey=identity("terminal",child.nodeId,child.cycleId)
                    val terminal=one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(root.rootId,causeKey)) { c ->
                        integrity(c.s("kind")=="TERMINAL" && c.s("node_id")==child.nodeId && c.s("cycle_id")==child.cycleId &&
                            c.s("payload_ref")==child.terminalRef && c.s("payload_digest")==child.terminalHash,"Terminal source changed");c.s("receipt") }
                    integrity(terminal!=null,"Missing terminal source")
                    val report=one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(root.rootId,identity("reported",causeKey))) { c ->
                        integrity(c.s("kind")=="REPORTED" && c.s("node_id")==child.nodeId && c.s("cycle_id")==child.cycleId &&
                            c.s("source_receipt")==causeKey && c.s("payload_ref")==child.terminalRef &&
                            c.s("payload_digest")==identity(c.s("receipt")),"Report source changed");c.s("receipt") }
                    integrity(report==child.reportReceipt,"Reported receipt changed")
                    eligible(one(db,"bot_tree_event","root_id=? AND kind='CENTRAL_CLAIM' AND payload_ref=?",
                        arrayOf(root.rootId,requireNotNull(child.terminalRef))) { it.s("event_key") }==null,"Result already belongs to a tree claim")
                    enrollment
                }
            } finally { lock.unlock() }
        }
    }
    private fun eligible(ok:Boolean,message:String) { if(!ok) throw WorkspaceContinuationDelivery.AdmissionIneligible(message) }
}
