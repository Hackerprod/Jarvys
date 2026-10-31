package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.crew.*
import com.jarvys.agent.workspaces.*
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray

/** Projection and acknowledgement of exact Registry selections into the existing native inbox. */
internal object WorkspaceTreeDelivery {
    private fun message(app:Context,db:WorkspaceRegistry,parent:TreeNode,cause:TreeCause,wait:TreeWait,cycle:String):CrewMessage {
        val tree=WorkspaceTreeStore(db);val child=requireNotNull(tree.getNode(parent.rootId,cause.nodeId))
        check(child.parentNodeId==parent.nodeId && child.reply==TreeReply.AWAIT)
        val assignment=requireNotNull(db.getAssignment(requireNotNull(parent.workspaceId),requireNotNull(parent.assignmentId)))
        val session=WorkspaceIdentity.executionSessionId(parent.workspaceId)
        val ledger=LocalRunStore(app)
        val type:CrewMessage.Type;val text:String;val timestamp:Long
        val sender=child.botId ?: "registry:"+child.nodeId
        if(cause.kind=="TERMINAL" && child.botId==null && child.executionEventId==null) {
            val native=requireNotNull(db.getAssignment(requireNotNull(child.workspaceId),requireNotNull(child.assignmentId)))
            check(native.state==WorkspaceAssignmentState.PREPARED && native.botId==null && child.terminalOutcome=="CANCELLED")
            val receipt=db.treeRead { sql -> WorkspaceTreeStore.one(sql,"bot_tree_event","root_id=? AND event_key=?",arrayOf(child.rootId,cause.eventKey)) { c ->
                val raw=c.getString(c.getColumnIndexOrThrow("receipt"))
                check(c.getString(c.getColumnIndexOrThrow("kind"))=="TERMINAL" && WorkspaceTreeStore.identity(raw)==cause.evidenceHash &&
                    c.getString(c.getColumnIndexOrThrow("payload_ref"))==cause.evidenceRef)
                raw
            } } ?: error("Missing exact cancelled admission")
            val metadata=JSONObject(receipt)
            check(metadata.getString("kind")=="CANCELLED_ADMISSION" && metadata.getString("nodeId")==child.nodeId && metadata.getString("cycleId")==cause.cycleId)
            type=CrewMessage.Type.RESULT;text="Queued child cancelled before execution: "+receipt;timestamp=native.updatedAt
        } else if(cause.kind=="TERMINAL") {
            val e=requireNotNull(ledger.readCrewResultEvidence(session,cause.evidenceRef))
            check(e.contentSha256==cause.evidenceHash && e.botId==child.botId && e.cycleId==cause.cycleId && e.missionId==assignment.missionId)
            type=CrewMessage.Type.RESULT;text=e.resultText;timestamp=e.producedAtMillis
        } else {
            check(cause.kind=="NEEDS_INPUT")
            val e=requireNotNull(ledger.readCrewCoordinationEvidence(session,cause.evidenceRef))
            check(e.contentSha256==cause.evidenceHash && e.botId==child.botId && e.cycleId==cause.cycleId && e.missionId==assignment.missionId)
            type=CrewMessage.Type.QUESTION;text="Question message ID: "+e.messageId+"\n\n"+e.text+if(e.refs.isEmpty()) "" else "\n\nReferences:\n"+e.refs.joinToString("") { "- $it\n" };timestamp=e.producedAtMillis
        }
        val id=UUID.nameUUIDFromBytes((wait.waitId+":"+cause.eventKey).toByteArray(Charsets.UTF_8)).toString()
        val binding=CrewInputBinding.treeReport(wait.waitId,WorkspaceIdentity.CENTRAL_SESSION,parent.workspaceId,session,
            parent.assignmentId,assignment.missionId,requireNotNull(parent.botId),id,cause.evidenceHash,cycle,sender,type,text)
        val provenance=ConversationInput.Provenance(session,parent.workspaceId,child.assignmentId,child.botId,id,
            ConversationInput.Kind.valueOf(type.name),ConversationInput.Provenance.PrincipalRole.SUBORDINATE,cause.evidenceRef,"",cause.evidenceHash,
            0L,assignment.requestId,assignment.missionId,cause.cycleId)
        return CrewMessage(id,session,sender,parent.botId,type,text,emptyList(),timestamp).withInputBinding(binding).withInputProvenance(provenance)
    }
    fun stageReports(app:Context,session:String,manager:CrewManager,successor:CrewManager.Bot,claim:TreeWaitClaim) {
        WorkspaceRegistry(app).use { db ->
            val tree=WorkspaceTreeStore(db);val waits=WorkspaceTreeWaitStore(tree)
            check(waits.get(claim.wait.rootId,claim.wait.waitId)==claim.wait)
            val parent=requireNotNull(tree.getNode(claim.wait.rootId,claim.wait.parentNodeId))
            check(parent.controlIntent==claim.wait.successorIntent && parent.assignmentRevision==claim.admittedAssignmentRevision &&
                parent.botId==successor.id && session==WorkspaceIdentity.executionSessionId(requireNotNull(parent.workspaceId)))
            claim.causes.forEach { cause -> manager.enqueueTreeReportExact(successor.id,successor.token,message(app,db,parent,cause,claim.wait,successor.cycleId())) }
        }
    }
    fun reconcileAfterPause(app:Context,session:String,manager:CrewManager,bot:CrewManager.Bot,claim:TreeControlClaim) {
        check(claim.control.type==WorkspaceControlType.PAUSE && manager.bot(bot.id)===bot && bot.cycleId()==claim.node.cycleId)
        val saved=requireNotNull(CrewCheckpointStore(app.filesDir).confirmDurable(session,bot.id,bot.scopeIdentity()))
        WorkspaceTreeWorkerCoordinator.requireCertifiedCheckpointIdentity(app,session,saved)
        WorkspaceRegistry(app).use { db ->
            check(db.getControl(claim.control.requestId)==claim.control)
            val tree=WorkspaceTreeStore(db);val node=requireNotNull(tree.getNode(claim.node.rootId,claim.node.nodeId))
            check(node.cycleId==claim.node.cycleId && node.assignmentRevision==claim.node.assignmentRevision && node.controlIntent==null && node.wakesClosed)
            bot.resultEvidence()?.let { evidence ->
                val retained=requireNotNull(LocalRunStore(app).readCrewResultEvidence(session,evidence.evidenceId))
                check(retained.contentSha256==evidence.contentSha256 && retained.botId==node.botId && retained.cycleId==node.cycleId)
                val outcome=when(evidence.reportedStatus){"DONE"->"SUCCESS";"STOPPED"->"STOPPED";else->"FAILED"}
                tree.settle(TreeNodeFence.of(node),outcome,evidence.evidenceId,evidence.contentSha256,"native-result:"+evidence.evidenceId)
                WorkspaceTreeEvidenceStore(tree).markReported(node.rootId,node.nodeId,evidence.evidenceId,evidence.contentSha256,"native-result:"+evidence.contentSha256)
            }
            tree.children(node.rootId,node.nodeId).filter { it.state==TreeNodeState.TERMINAL && it.executionEventId==null && it.botId==null }.forEach { queued ->
                WorkspaceTreeEvidenceStore(tree).markReported(queued.rootId,queued.nodeId,requireNotNull(queued.terminalRef),requireNotNull(queued.terminalHash),"cancelled-admission:"+queued.terminalHash)
            }
        }
    }
    private fun answerMessage(db:WorkspaceRegistry,node:TreeNode,answer:TreeAnswer,controlId:String,cycle:String):CrewMessage {
        val assignment=requireNotNull(db.getAssignment(requireNotNull(node.workspaceId),requireNotNull(node.assignmentId)))
        val session=WorkspaceIdentity.executionSessionId(node.workspaceId)
        val id=UUID.nameUUIDFromBytes((answer.eventKey+":"+controlId).toByteArray(Charsets.UTF_8)).toString()
        val binding=CrewInputBinding.create(CrewInputBinding.SourceKind.TREE_ANSWER,controlId,WorkspaceIdentity.CENTRAL_SESSION,
            node.workspaceId,session,node.assignmentId,assignment.missionId,requireNotNull(node.botId),id,answer.digest,cycle,answer.text)
        val provenance=ConversationInput.Provenance(session,node.workspaceId,node.assignmentId,node.botId,id,
            ConversationInput.Kind.ANSWER,ConversationInput.Provenance.PrincipalRole.PEER,answer.questionId,"",answer.digest,
            node.assignmentRevision,assignment.requestId,assignment.missionId,answer.questionCycle)
        return CrewMessage(id,session,"chief",node.botId,CrewMessage.Type.ANSWER,answer.text,emptyList(),requireNotNull(db.getControl(controlId)).createdAt)
            .withInputBinding(binding).withInputProvenance(provenance)
    }
    fun stageAnswer(app:Context,manager:CrewManager,successor:CrewManager.Bot,claim:TreeControlClaim) {
        WorkspaceRegistry(app).use { db ->
            val tree=WorkspaceTreeStore(db);val node=requireNotNull(tree.getNode(claim.node.rootId,claim.node.nodeId))
            check(node==claim.node && node.botId==successor.id)
            val answer=WorkspaceTreeAnswerStore(tree).forControl(node.rootId,node.nodeId,claim.control.requestId) ?: return
            manager.enqueueTreeAnswerExact(successor.id,successor.token,answerMessage(db,node,answer,claim.control.requestId,successor.cycleId()))
        }
    }
    @JvmStatic fun reconcileCheckpoint(app:Context,saved:CrewCheckpointStore.ConfirmedSnapshot) {
        val raw=saved.loopForEvidence()
        val inputs=raw.incorporations.values.filter { it.binding.sourceKind in setOf(CrewInputBinding.SourceKind.TREE_REPORT,CrewInputBinding.SourceKind.TREE_ANSWER) }
        if(inputs.isEmpty())return // Initial deferred TASK is saved before first BIND.
        WorkspaceTreeWorkerCoordinator.requireCertifiedCheckpointIdentity(app,saved.conversationId,saved)
        WorkspaceRegistry(app).use { db ->
            val tree=WorkspaceTreeStore(db);val parent=requireNotNull(tree.findAssignment(WorkspaceIdentity.workspaceId(saved.conversationId),saved.metadataJson().getString("assignmentId")))
            val waits=WorkspaceTreeWaitStore(tree)
            inputs.groupBy { it.binding.sourceId }.forEach { (waitId,proofs) ->
                if(proofs.any { it.binding.sourceKind==CrewInputBinding.SourceKind.TREE_ANSWER }) {
                    check(proofs.size==1 && proofs.single().binding.sourceKind==CrewInputBinding.SourceKind.TREE_ANSWER)
                    val proof=proofs.single();val answers=WorkspaceTreeAnswerStore(tree)
                    val answer=requireNotNull(answers.forControl(parent.rootId,parent.nodeId,waitId))
                    val expected=answerMessage(db,parent,answer,waitId,proof.firstIncorporationCycleId)
                    check(proof.binding.samePayload(expected.inputBinding) && proof.transportKind==CrewInputIncorporation.TransportKind.INBOX)
                    val proofDigest=WorkspaceTreeStore.identity(WorkspaceTreeEvidenceStore.canonical(proof.toJson()))
                    val prior=answers.acknowledged(answer)
                    if(prior!=null)check(JSONObject(prior).getString("proofDigest")==proofDigest)
                    else {
                    val turn=raw.transcript.single { it.incomingMetadata?.id==expected.id }
                    check(turn.content==answer.text && turn.incomingMetadata.origin==ConversationInput.Origin.AGENT &&
                        turn.incomingMetadata.provenance?.principalRole==ConversationInput.Provenance.PrincipalRole.PEER &&
                        proof.transportSha256==CrewInputIncorporation.transportSha256(turn.content))
                    answers.acknowledge(answer,waitId,proof.firstIncorporationCycleId,
                        JSONObject().put("proofDigest",proofDigest).put("checkpoint",saved.payloadSha256).toString())
                    }
                    return@forEach
                }
                val wait=requireNotNull(waits.get(parent.rootId,waitId))
                check(wait.parentNodeId==parent.nodeId && wait.state in setOf(TreeWaitState.STARTED,TreeWaitState.ACKNOWLEDGED))
                val proofDigest=WorkspaceTreeStore.identity(WorkspaceTreeEvidenceStore.canonical(JSONArray(proofs.sortedBy { it.binding.mailboxMessageId }.map { it.toJson() })))
                val acknowledged=wait.state==TreeWaitState.ACKNOWLEDGED
                if(acknowledged) check(JSONObject(requireNotNull(wait.incorporationReceipt)).getString("proofDigest")==proofDigest)
                val selected=requireNotNull(wait.selectedCauses)
                val cycle=proofs.first().firstIncorporationCycleId
                val expected=selected.map { message(app,db,parent,it,wait,cycle) }
                if(expected.any { e -> proofs.none { it.binding.mailboxMessageId==e.id } })return@forEach
                check(proofs.size==expected.size)
                expected.forEach { e ->
                    val proof=proofs.single { it.binding.mailboxMessageId==e.id }
                    check(proof.binding.samePayload(e.inputBinding) && proof.firstIncorporationCycleId==cycle &&
                        proof.transportKind==CrewInputIncorporation.TransportKind.INBOX)
                    if(!acknowledged) {
                    val turn=raw.transcript.single { it.incomingMetadata?.id==e.id }
                    check(turn.content==e.conversationContent() && turn.incomingMetadata.origin==ConversationInput.Origin.AGENT &&
                        turn.incomingMetadata.provenance?.principalRole==ConversationInput.Provenance.PrincipalRole.SUBORDINATE &&
                        proof.transportSha256==CrewInputIncorporation.transportSha256(turn.content))
                    }
                }
                if(!acknowledged) waits.acknowledge(parent.rootId,waitId,cycle,
                    JSONObject().put("proofDigest",proofDigest).put("checkpoint",saved.payloadSha256).toString())
            }
        }
    }
}
