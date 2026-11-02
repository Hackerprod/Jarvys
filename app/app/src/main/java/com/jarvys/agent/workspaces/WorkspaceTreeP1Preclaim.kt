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
    internal data class Enrollment(val storage:String,val rootId:String,val rootCycle:String,val reservation:CentralResumeRecord?=null) {
        fun requireNotStopped()=WorkspaceTreeStops.requireAllowed(storage,rootId,rootId,rootCycle)
        fun uncertain(context:Context) {
            val saved=reservation ?: return
            WorkspaceRegistry(context).use{WorkspaceTreeStore(it).uncertainCentralResume(saved)}
        }
    }
    fun reserveForClaim(context:Context,value:JSONObject,token:com.jarvys.agent.CancellationToken,run:String):Enrollment? {
        val eligible=requireEligible(context,value) ?: return null
        if(!com.jarvys.agent.CentralAvailabilityPolicy.admissionEnabled())return eligible
        return WorkspaceRegistry(context).use{registry->
            // Only the actual P1 claim reaches this path, after its exact live permit and payload
            // were validated. Read-only eligibility inspection above never upgrades or enrolls.
            check(LocalRunStore(context).centralSubmissionSchemaReady()) {"Central submission companion schema not ready"}
            if(registry.schemaVersion()!=3) {
                check(com.jarvys.agent.CentralAvailabilityPolicy.registryMigrationEnabled()) {"Central resume migration disabled"}
                eligible.requireNotStopped();token.throwIfCancelled()
                registry.upgradeCentralResumeSchema()
                token.throwIfCancelled()
                val rechecked=requireNotNull(requireEligible(context,value))
                check(rechecked.storage==eligible.storage && rechecked.rootId==eligible.rootId && rechecked.rootCycle==eligible.rootCycle) {
                    "P1 original root changed during compatible schema upgrade"
                }
            }
            check(registry.schemaVersion()==3){"Central resume schema not ready"}
            val core=WorkspaceTreeStore(registry)
            val parent=requireNotNull(core.getNode(eligible.rootId,eligible.rootId))
            val child=requireNotNull(core.findAssignment(value.getString("workspaceId"),value.getString("assignmentId")))
            eligible.requireNotStopped();token.throwIfCancelled()
            val reserved=core.reserveCentralChild(TreeNodeFence.of(parent),child.nodeId,run,
                com.jarvys.agent.crew.CrewProcessIdentity.ID,run,token.generation().toString(),
                com.jarvys.agent.crew.CrewProcessIdentity.ID+":"+token.generation())
                ?: throw WorkspaceContinuationDelivery.AdmissionIneligible("Another exact Central resume has priority")
            eligible.copy(reservation=reserved)
        }
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
