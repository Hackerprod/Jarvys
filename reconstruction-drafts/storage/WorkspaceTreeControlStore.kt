package com.jarvys.agent.workspaces

import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.identity
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.values
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.next
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.eligible
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.integrity
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.nonempty
import org.json.JSONObject
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.one
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.s

/** Existing registered USER rows remain authority; never synthesizes a control for child-wait. */
internal class WorkspaceTreeControlStore(private val core:WorkspaceTreeStore) {
    fun claim(fence:TreeNodeFence,controlId:String,process:String,startsExecution:Boolean,sourceCertificate:String,beforeWrite:Runnable):TreeControlClaim = core.registry.treeTransaction { db ->
        var node=core.requireNode(db,fence.rootId,fence.nodeId)
        eligible(TreeNodeFence.of(node)==fence,"Control target changed")
        val control=core.registry.getControl(controlId) ?: throw TreeIntegrityException("Missing native USER control")
        eligible(control.state==WorkspaceControlState.PENDING && control.assignmentId==node.assignmentId &&
            control.workspaceId==node.workspaceId && control.botId==node.botId && control.assignmentRevision==node.assignmentRevision,
            "Control is not the exact registered target")
        core.requireNativeAssignment(node)
        eligible(control.origin==WorkspaceRegistry.NATIVE_UI_ORIGIN,"Only registered USER authority can resume")
        val root=core.requireRoot(db,node.rootId)
        if(control.type!=WorkspaceControlType.PAUSE) {
            eligible(!root.admissionsClosed && !root.wakesClosed,"Original root is closed")
            eligible(one(db,"bot_tree_event","root_id=? AND node_id=? AND kind='CANCEL_TARGET'",arrayOf(node.rootId,node.nodeId)){it.s("event_key")}==null,
                "Explicitly cancelled owned subtree cannot resume")
            eligible(node.state!=TreeNodeState.UNCERTAIN && node.controlIntent==null,"Uncertain/pending execution cannot replay")
            eligible(core.event(db,node.rootId,identity("control",controlId))==null,"Control already reserved")
        }
        eligible(!startsExecution || control.type!=WorkspaceControlType.PAUSE,"PAUSE cannot execute")
        if(startsExecution) eligible(root.executions<WorkspaceTreePolicy.MAX_EXECUTIONS,"Execution window exhausted")
        WorkspaceTreeStore.requireSourceCertificate(sourceCertificate,"native-workspace:"+requireNotNull(node.workspaceId))
        val originalCycle=node.cycleId
        if(control.type==WorkspaceControlType.PAUSE) {
            core.stopRequester(fence) { targets -> targets.forEach {
                WorkspaceTreeStops.latchExact(core.registry.treeStorageIdentity,it.rootId,it.nodeId,it.cycleId)
            } }
            node=core.requireNode(db,node.rootId,node.nodeId)
        }
        core.appendEvent(db,node,identity("control-predecessor",controlId),"CONTROL_PREDECESSOR",sourceCertificate,
            controlId,identity(sourceCertificate),originalCycle)
        val started=core.registry.markControlStarted(controlId,control.revision,process,beforeWrite)
        if(startsExecution) WorkspaceTreeAnswerStore(core).claimInTransaction(db,node,started)
        val admitted=requireNotNull(started.admittedAssignmentRevision)
        integrity(admitted==next(node.assignmentRevision),"Control revision changed")
        val receipt=JSONObject().put("controlId",controlId).put("sourceRevision",node.assignmentRevision)
            .put("admittedRevision",admitted).put("process",process).put("type",control.type.name).put("oldCycle",node.cycleId).toString()
        core.appendEvent(db,node,identity("control",controlId),"USER_CONTROL",receipt,controlId,identity(receipt),node.cycleId)
        val execution=if(startsExecution) identity("control-execution",controlId) else null
        if(execution!=null) {
            core.appendEvent(db,node,execution,"USER_EXECUTION",receipt,controlId,identity(receipt),node.nodeId)
            core.updateRoot(db,root,values("executions" to next(root.executions)))
        }
        val claimed=core.updateNode(db,node,values("assignment_revision" to admitted,"control_intent" to controlId))
        if(control.type==WorkspaceControlType.PAUSE) {
            WorkspaceTreeStops.latchExact(core.registry.treeStorageIdentity,node.rootId,node.nodeId,node.cycleId)
            core.cancelWaits(db,node)
        }
        TreeControlClaim(claimed,started,execution)
    }
    fun bind(claim:TreeControlClaim,newCycle:String,certificate:String):TreeNode=core.registry.treeTransaction { db ->
        val node=core.requireNode(db,claim.node.rootId,claim.node.nodeId)
        eligible(node==claim.node && node.controlIntent==claim.control.requestId,"USER successor target changed")
        val control=core.registry.getControl(claim.control.requestId) ?: throw TreeIntegrityException("Missing USER receipt")
        integrity(control==claim.control && control.state==WorkspaceControlState.STARTED,"USER claim changed")
        core.requireNativeAssignment(node)
        if(control.type!=WorkspaceControlType.PAUSE) {
            val root=core.requireRoot(db,node.rootId)
            eligible(!root.admissionsClosed && !root.wakesClosed,"Root closed after USER claim")
        }
        nonempty(newCycle)
        val source=WorkspaceTreeStore.requireSourceCertificate(certificate,"native-workspace:"+requireNotNull(node.workspaceId))
        if(claim.executionEventId!=null) eligible(newCycle!=node.cycleId,"New execution must bind a distinct cycle")
        if(control.type==WorkspaceControlType.PAUSE) eligible(newCycle==node.cycleId,"PAUSE cannot replace cycle")
        core.appendEvent(db,node,identity("control-successor",control.requestId),"CONTROL_SUCCESSOR",certificate,control.requestId,identity(certificate),newCycle)
        core.updateNode(db,node,values("cycle_id" to newCycle,"control_intent" to null,
            "admissions_closed" to if(control.type==WorkspaceControlType.PAUSE) 1L else 0L,
            "wakes_closed" to if(control.type==WorkspaceControlType.PAUSE) 1L else 0L,
            "state" to if(control.type==WorkspaceControlType.PAUSE) "WAITING" else "RUNNING"))
    }
    companion object {
        fun claimDigest(claim:TreeControlClaim):String {
            val c=claim.control
            integrity(c.state==WorkspaceControlState.STARTED && c.admittedAssignmentRevision==claim.node.assignmentRevision &&
                c.assignmentId==claim.node.assignmentId && c.botId==claim.node.botId && claim.node.controlIntent==c.requestId,"Malformed control claim")
            return identity(WorkspaceTreeEvidenceStore.canonical(JSONObject().put("root",claim.node.rootId).put("node",claim.node.nodeId)
                .put("cycle",claim.node.cycleId).put("revision",claim.node.revision).put("control",c.requestId)
                .put("sourceRevision",c.assignmentRevision).put("admittedRevision",c.admittedAssignmentRevision)
                .put("type",c.type.name).put("process",c.processId).put("execution",claim.executionEventId ?: JSONObject.NULL)))
        }
    }

}
