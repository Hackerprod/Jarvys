package com.jarvys.agent.workspaces

import android.content.Context
import com.jarvys.agent.*
import com.jarvys.agent.crew.*
import org.json.JSONObject

/** Formal-question retention only. Ordinary live QUESTION/BLOCKER remains on the native existing route. */
internal object WorkspaceTreeCentralAnswers {
    fun retainIfFormal(app:Context,workspace:String,messageId:String,hash:String,text:String,revision:Long,
        token:CancellationToken,admission:Runnable,context:WorkspaceMutationContext):JSONObject? {
        admission.run();token.throwIfCancelled()
        val session=WorkspaceIdentity.executionSessionId(workspace)
        val ledger=LocalRunStore(app)
        val reference=ledger.readWorkspaceCoordinationReference(session,messageId) ?: return null
        val target=WorkspaceRegistry(app).use { db -> WorkspaceTreeStore(db).findAssignment(workspace,reference.assignmentId) } ?: return null
        if(target.state!=TreeNodeState.NEEDS_INPUT)return null // Existing ordinary coordination semantics are preserved.
        context.requireTreeAdmission()
        check(reference.contentSha256==hash && reference.assignmentRevision==revision && target.assignmentRevision==revision &&
            reference.botId==target.botId && reference.cycleId==target.cycleId && reference.evidenceId==target.terminalRef)
        val evidence=requireNotNull(ledger.readCrewCoordinationEvidence(session,reference.evidenceId))
        check(evidence.contentSha256==hash && evidence.botId==target.botId && evidence.cycleId==target.cycleId)
        context.admitMutation()
        val answer=context.withCurrentWorkspaceTransaction {
            WorkspaceRegistry(app).use { db ->
                admission.run();context.requireInsideCurrentLease();context.requireTreeAdmission()
                val tree=WorkspaceTreeStore(db);val parent=requireNotNull(tree.getNode(target.rootId,requireNotNull(target.parentNodeId)))
                check(parent.mode==TreeMode.CENTRAL) { "Only the actual parent may answer this formal request" }
                WorkspaceTreeAnswerStore(tree).prepare(TreeNodeFence.of(parent),TreeNodeFence.of(target),text,
                    WorkspaceTreeStore.centralReceipt(requireNotNull(context.rootSource)))
            }
        }
        context.acceptedInput(workspace,answer.eventKey)
        return JSONObject().put("answer_id",answer.eventKey).put("state","PREPARED").put("requires_explicit_resume",true)
    }
}
