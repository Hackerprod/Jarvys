package com.jarvys.agent.workspaces

import android.content.Context
import com.jarvys.agent.ConversationInput
import com.jarvys.agent.crew.CrewInputBinding
import com.jarvys.agent.crew.CrewMessage
import org.json.JSONObject
import com.jarvys.agent.workspaces.WorkspaceTreeEvidenceStore.Companion.parseObject
import com.jarvys.agent.workspaces.WorkspaceTreeEvidenceStore.Companion.requireKeys
import com.jarvys.agent.workspaces.WorkspaceTreeEvidenceStore.Companion.string
import com.jarvys.agent.workspaces.WorkspaceTreeEvidenceStore.Companion.integer
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.identity
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.integrity
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.one
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.s
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.positive

/** Historical outbound TASK evidence only. Never returns a mailbox item, capability or execution claim. */
internal object WorkspaceTreeTaskEvidence {
    @JvmStatic fun requireOutbound(context:Context,session:String,parentBot:String,mission:String,message:CrewMessage) {
        val b=requireNotNull(message.inputBinding);val p=requireNotNull(message.inputProvenance)
        integrity(b.sourceKind==CrewInputBinding.SourceKind.TREE_TASK && b.matchesDelivery(message) &&
            message.type==CrewMessage.Type.TASK && message.from==parentBot && message.to!=parentBot &&
            message.conversationId==session && b.executionSessionId==session && b.botId==message.to &&
            b.missionId==mission && p.missionId==mission && p.botId==parentBot && p.sessionId==session &&
            p.principalRole==ConversationInput.Provenance.PrincipalRole.PEER && p.kind==ConversationInput.Kind.TASK &&
            b.sourceId==b.assignmentId && p.assignmentId==b.assignmentId && p.messageId==message.id &&
            b.workspaceId==WorkspaceIdentity.workspaceId(session) && p.workspaceId==b.workspaceId &&
            b.ownerCentralSessionId==WorkspaceIdentity.CENTRAL_SESSION,"Outbound task routing changed")
        WorkspaceRegistry(context).use { registry -> registry.treeRead { db ->
            val tree=WorkspaceTreeStore(registry)
            val child=requireNotNull(tree.findAssignment(b.workspaceId,b.assignmentId))
            val parent=tree.requireNode(db,child.rootId,requireNotNull(child.parentNodeId))
            val root=tree.requireRoot(db,child.rootId)
            integrity(parent.botId==parentBot && parent.workspaceId==b.workspaceId && child.botId==b.botId &&
                child.workspaceId==b.workspaceId && root.origin.ownerCentralSessionId==WorkspaceIdentity.CENTRAL_SESSION,
                "Outbound task parent relation changed")
            val native=requireNotNull(registry.getAssignment(b.workspaceId,b.assignmentId))
            val parentNative=requireNotNull(registry.getAssignment(b.workspaceId,requireNotNull(parent.assignmentId)))
            integrity(native.botId==b.botId && native.missionId==mission && native.requestId==p.requestId &&
                parentNative.missionId==mission && parentNative.requestId==native.requestId &&
                parentNative.originalUserRequest==native.originalUserRequest && native.missionText==message.text,
                "Outbound task native assignment changed")
            val source=parseObject(child.sourceReceipt)
            requireKeys(source,setOf("version","executionSessionId","botId","missionId","cycleId","scopeIdentity",
                "checkpointPayloadSha256","callId","toolName","argumentsDigest"))
            integrity(integer(source,"version")==1L,"Outbound task invocation version changed")
            listOf("executionSessionId","botId","missionId","cycleId","scopeIdentity","checkpointPayloadSha256",
                "callId","toolName","argumentsDigest").forEach {string(source,it)}
            integrity(string(source,"scopeIdentity")=="native-workspace:${b.workspaceId}","Outbound task invocation scope changed")
            integrity(string(source,"botId")==parentBot && string(source,"cycleId")==p.cycleId &&
                string(source,"executionSessionId")==session && string(source,"missionId")==mission &&
                string(source,"toolName")=="delegate_subtask","Outbound task invocation owner changed")
            val invocation=WorkspaceTreeEvidenceStore(tree).invocationReceipts(child.rootId,parent.nodeId,p.cycleId)
                .singleOrNull { it.sourceReceipt==child.sourceReceipt } ?: throw TreeIntegrityException("Missing unique original task invocation")
            integrity(invocation.kind=="ADMISSION" && native.assignmentKey=="tree-call:"+invocation.sourceKey && string(parseObject(invocation.argsJson),"objective")==message.text,
                "Outbound task original objective changed")
            val result=parseObject(requireNotNull(invocation.resultJson))
            requireKeys(result,setOf("success","content","completeContentRequired","finishRun"))
            listOf("success","finishRun","completeContentRequired").forEach {
                integrity(result.get(it) is Boolean,"Outbound task result boolean type changed")
            }
            integrity(result.getBoolean("success") && !result.getBoolean("finishRun") && result.getBoolean("completeContentRequired"),
                "Outbound task admission did not complete")
            val content=string(result,"content");val prefix="native-tree-child:${child.nodeId}\n"
            integrity(content.startsWith(prefix),"Outbound task committed result changed")
            val admitted=parseObject(content.substring(prefix.length))
            requireKeys(admitted,setOf("assignment_id","workspace_id","request_id","mission_id","revision","admission_state",
                "bot_id","role","status","mission_excerpt","result_excerpt","result_truncated","result_verified",
                "recovery_note","no_replay","tree_node_id","tree_root_id"))
            listOf("result_truncated","result_verified","no_replay").forEach {
                integrity(admitted.get(it) is Boolean,"Outbound task assignment boolean type changed")
            }
            listOf("role","status","mission_excerpt").forEach {string(admitted,it)}
            listOf("result_excerpt","recovery_note").forEach {
                integrity(admitted.get(it) === JSONObject.NULL || admitted.get(it) is String,"Outbound task optional text type changed")
            }
            val boundRevision=integer(admitted,"revision")
            integrity(string(admitted,"tree_root_id")==child.rootId && string(admitted,"tree_node_id")==child.nodeId &&
                string(admitted,"assignment_id")==native.assignmentId && string(admitted,"workspace_id")==b.workspaceId &&
                string(admitted,"bot_id")==b.botId && string(admitted,"mission_id")==mission &&
                string(admitted,"request_id")==p.requestId && string(admitted,"admission_state")=="BOUND" &&
                p.assignmentRevision>0 && p.assignmentRevision<Long.MAX_VALUE && p.assignmentRevision+1==boundRevision &&
                native.revision>=boundRevision,"Outbound task initial assignment revision changed")
            val queued=identity("queued",child.assignmentId!!)
            val request=TreeChildRequest(native.workspaceId,native.assignmentKey,native.requestId,native.originalUserRequest,
                native.role,native.missionText,native.tools,native.name,child.mode,child.reply,child.sourceReceipt)
            val admissionDigest=WorkspaceTreeStore.admissionDigest(request)
            val admissionRevision=one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(child.rootId,identity("admit",parent.nodeId,b.workspaceId,native.assignmentKey))) { row ->
                integrity(row.s("kind")=="ADMISSION" && row.s("node_id")==child.nodeId && row.s("cycle_id")==queued &&
                    row.s("source_receipt")==child.sourceReceipt && row.s("receipt")==admissionDigest &&
                    row.s("payload_digest")==admissionDigest && row.s("payload_ref")==child.nodeId,"Outbound task original admission changed")
                row.positive("node_revision")
            } ?: error("Missing original task admission")
            val executionRevision=one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(child.rootId,identity("first-execution",child.nodeId))) { row ->
                integrity(row.s("kind")=="EXECUTION" && row.s("node_id")==child.nodeId && row.s("cycle_id")==queued &&
                    row.s("source_receipt")==child.sourceReceipt && row.s("payload_digest")==identity(row.s("receipt")) &&
                    row.s("receipt").isNotBlank() && row.s("payload_ref")==child.nodeId,"Outbound task original execution changed")
                row.positive("node_revision")
            } ?: error("Missing original task execution")
            one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(child.rootId,identity("bind",child.nodeId,b.admissionCycleId))) { row ->
                integrity(row.s("kind")=="BIND" && row.s("node_id")==child.nodeId && row.s("cycle_id")==queued &&
                    row.s("source_receipt")==child.sourceReceipt && row.s("payload_ref")==b.admissionCycleId &&
                    row.s("payload_digest")==identity(row.s("receipt")) && admissionRevision<=executionRevision &&
                    executionRevision<row.positive("node_revision"),"Outbound task original cycle binding changed")
                WorkspaceTreeStore.requireSourceCertificate(row.s("receipt"),"native-workspace:${b.workspaceId}")
            } ?: error("Missing original task cycle binding")
            // Deliberately no equality with child's *current* cycle/revision: original immutable receipts own history.
        } }
    }
}
