package com.jarvys.agent.workspaces

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.jarvys.agent.CentralTargetControlSource
import com.jarvys.agent.LocalRunStore
import com.jarvys.agent.chatdb.CentralUserRootCheckpoint
import org.json.JSONObject
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.identity
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.one
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.s

/** Non-serializable, invocation-local USER control. Deliberately has no admission/dispatch API. */
internal class TreeUserTargetControl private constructor(
    private val app:Context, private val invocation:WorkspaceMutationContext,
    private val arguments:String, private val action:String, private val target:TreeNode,
    private val origin:TreeOrigin, private val retained:CentralUserRootCheckpoint,
    private val owner:Thread, private val payload:String,
) {
    fun <T> transact(core:WorkspaceTreeStore,expectedAction:String,expectedPayload:String,
        work:(SQLiteDatabase,TreeNode,String)->T):T {
        check(Thread.currentThread()===owner && expectedAction==action && expectedPayload==payload)
        check(invocation.requireControlArguments()==arguments)
        val source=requireNotNull(invocation.rootSource)
        // Room must never be opened underneath Registry's SQLite transaction.
        val latest=CentralTargetControlSource.capture(LocalRunStore(app),source,arguments)
        check(latest.originDigest==requireNotNull(invocation.treeAdmission).originDigest())
        val questionText=validateReply(app,target,action,arguments)
        val original=LocalRunStore(app).captureCentralUserRoot(origin.ownerCentralSessionId,origin.originUserMessageId)
        check(original.originDigest==origin.originDigest && original.storageGeneration==origin.storageGeneration)
        check(original.lifecycle.fenceIdentity()===retained.lifecycle.fenceIdentity() &&
            latest.lifecycle.fenceIdentity()===retained.lifecycle.fenceIdentity())
        return invocation.withCurrentWorkspaceTransaction {
            check(retained.lifecycle.isCurrentPure() && latest.lifecycle.isCurrentPure() && original.lifecycle.isCurrentPure())
            invocation.requireInsideCurrentLease();check(invocation.requireControlArguments()==arguments)
            core.registry.treeTransaction { db ->
                val root=core.requireRoot(db,target.rootId)
                check(root.origin==origin && !root.admissionsClosed && !root.wakesClosed)
                val central=core.requireNode(db,root.rootId,root.rootId)
                WorkspaceTreeStops.requireAllowed(core.registry.treeStorageIdentity,root.rootId,root.rootId,central.cycleId)
                val current=core.current(db,TreeNodeFence.of(target))
                check(current==target && current.state!=TreeNodeState.UNCERTAIN)
                core.requireNativeAssignment(current,if(current.botId==null) WorkspaceAssignmentState.PREPARED else WorkspaceAssignmentState.BOUND)
                val a=JSONObject(arguments)
                check(a.getString("action")==action && a.getString("workspace_id")==current.workspaceId &&
                    a.getLong("expected_assignment_revision")==current.assignmentRevision)
                if(action=="cancel") {
                    check(a.getString("assignment_id")==current.assignmentId && a.optString("bot_id",null)==current.botId)
                } else {
                    check(current.controlIntent==null && core.registry.getUnsettledControl(current.workspaceId!!,current.assignmentId!!)==null &&
                        core.registry.getPendingControl(current.workspaceId,current.assignmentId,current.assignmentRevision)==null)
                    check(current.state==TreeNodeState.NEEDS_INPUT && current.reportReceipt!=null &&
                        !current.admissionsClosed && !current.wakesClosed && current.terminalRef!=null)
                    WorkspaceTreeStops.requireAllowed(core.registry.treeStorageIdentity,root.rootId,current.nodeId,current.cycleId)
                    val questionKey=identity("question",current.nodeId,current.cycleId,requireNotNull(current.terminalRef))
                    val sourceKey=requireNotNull(one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(root.rootId,questionKey)) { c ->
                        check(c.s("kind")=="NEEDS_INPUT" && c.s("node_id")==current.nodeId && c.s("cycle_id")==current.cycleId &&
                            c.s("payload_ref")==current.terminalRef && c.s("payload_digest")==current.terminalHash &&
                            c.s("receipt")==identity(current.terminalRef!!,current.terminalHash!!,c.s("source_receipt")))
                        c.s("source_receipt")
                    })
                    val armed=WorkspaceTreeEvidenceStore(core).invocationReceipts(root.rootId,current.nodeId,current.cycleId)
                        .single { it.sourceKey==sourceKey && it.kind=="QUESTION_ARM" }
                    val armSource=JSONObject(armed.sourceReceipt);val armArgs=JSONObject(armed.argsJson)
                    check(armSource.getString("toolName")=="ask_chief" && armArgs.keys().asSequence().toSet()==setOf("question") &&
                        armArgs.get("question") is String && armArgs.getString("question")==questionText)
                    val result=WorkspaceTreeEvidenceStore.parseObject(requireNotNull(armed.resultJson))
                    WorkspaceTreeEvidenceStore.requireKeys(result,setOf("success","content","completeContentRequired","finishRun"))
                    check(result.get("success")==true && result.get("finishRun")==true && result.get("completeContentRequired")==false &&
                        result.get("content")=="native-question-evidence:"+current.terminalRef+
                            "\nAwaiting exact answer and explicit native Resume; not task completion." &&
                        WorkspaceTreeEvidenceStore.canonical(result)==armed.resultJson && identity(armed.resultJson!!)==armed.resultHash)
                    requireNotNull(one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(root.rootId,identity("reported",questionKey))) { c ->
                        check(c.s("kind")=="REPORTED" && c.s("node_id")==current.nodeId && c.s("cycle_id")==current.cycleId &&
                            c.s("source_receipt")==questionKey && c.s("payload_ref")==current.terminalRef &&
                            c.s("receipt")==current.reportReceipt && c.s("payload_digest")==identity(c.s("receipt")))
                        c.s("receipt")
                    })
                    check(core.event(db,root.rootId,WorkspaceTreeStore.identity("answer-started",
                        WorkspaceTreeStore.identity("answer-prepared",current.nodeId,current.terminalRef!!)))==null)
                }
                val receipt=WorkspaceTreeEvidenceStore.canonical(JSONObject().put("action",action).put("arguments",JSONObject(arguments))
                    .put("payload",payload).put("root",root.rootId).put("node",current.nodeId).put("cycle",current.cycleId)
                    .put("revision",current.revision).put("assignmentRevision",current.assignmentRevision)
                    .put("generation",source.storageGeneration).put("owner",source.ownerCentralSessionId)
                    .put("user",source.sourceMessageId).put("batch",source.sourceBatchId).put("call",source.sourceCallId)
                    .put("run",source.sourceRunId).put("process",source.sourceProcessId))
                val key=WorkspaceTreeStore.identity("user-target-control",WorkspaceTreeStore.centralReceipt(source))
                core.appendEvent(db,current,key,"USER_CONTROL_AUTH",receipt,WorkspaceTreeStore.centralReceipt(source),WorkspaceTreeStore.identity(receipt),action)
                work(db,current,key)
            }
        }
    }
    companion object {
        private fun validateReply(app:Context,target:TreeNode,action:String,args:String):String? {
            if(action!="reply")return null
            val a=JSONObject(args);val ledger=LocalRunStore(app)
            val session=WorkspaceIdentity.executionSessionId(requireNotNull(target.workspaceId))
            val ref=requireNotNull(ledger.readWorkspaceCoordinationReference(session,a.getString("message_id")))
            check(ref.assignmentId==target.assignmentId && ref.observedAssignmentRevision==target.assignmentRevision &&
                ref.botId==target.botId && ref.cycleId==target.cycleId && ref.evidenceId==target.terminalRef &&
                ref.contentSha256==target.terminalHash && ref.contentSha256==a.getString("expected_sha256"))
            val evidence=requireNotNull(ledger.readCrewCoordinationEvidence(session,ref.evidenceId))
            check(evidence.contentSha256==ref.contentSha256 && evidence.botId==target.botId && evidence.cycleId==target.cycleId && evidence.kind=="QUESTION")
            return evidence.text
        }

        fun capture(app:Context,context:WorkspaceMutationContext,target:TreeNode,action:String,payload:String):TreeUserTargetControl {
            check(action in setOf("reply","cancel") && context.action==action)
            val args=context.requireControlArguments()
            check(payload==args) { "Control payload must be the entire exact invocation" }
            validateReply(app,target,action,args)
            val source=requireNotNull(context.rootSource)
            val ledger=LocalRunStore(app)
            val latest=CentralTargetControlSource.capture(ledger,source,args)
            check(latest.originDigest==requireNotNull(context.treeAdmission).originDigest())
            val root=WorkspaceRegistry(app).use { WorkspaceTreeStore(it).getRoot(target.rootId) } ?: error("No enrolled root")
            check(root.origin.ownerCentralSessionId==source.ownerCentralSessionId && root.origin.storageGeneration==source.storageGeneration)
            val old=ledger.captureCentralUserRoot(root.origin.ownerCentralSessionId,root.origin.originUserMessageId)
            check(old.originDigest==root.origin.originDigest && old.storageGeneration==root.origin.storageGeneration &&
                old.lifecycle.fenceIdentity()===latest.lifecycle.fenceIdentity())
            return TreeUserTargetControl(app,context,args,action,target,root.origin,old,Thread.currentThread(),payload)
        }
    }
}
