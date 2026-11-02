package com.jarvys.agent.chatdb

import com.jarvys.agent.ConversationInput
import com.jarvys.agent.WorkspaceContinuationDelivery
import com.jarvys.agent.connectors.ConnectionStateCoordinator
import com.jarvys.agent.workspaces.WorkspaceIdentity

/** Immutable canonical input evidence only. Service's private actual-owner constructor owns execution. */
class NativeOrdinaryInputSource private constructor(
    val sessionId:String,val messageId:String,val sourceKind:String,
    private val text:String,private val parts:List<MessagePartInput>,
    private val attachments:List<MessageAttachmentInput>,internal val followUpLedger:List<String>,
    val storageGeneration:String,internal val lifecycle:ConnectionStateCoordinator.DestinationEvidence,
) {
    internal fun requireSame(row:StoredMessage) {
        check(row.conversationId==sessionId && row.messageId==messageId && row.role==(if(sourceKind=="AGENT") "agent" else "user") && row.hiddenKind==null)
        check(row.inputState==null || row.inputState=="incorporated") {"Ordinary input is already resolved"}
        check(row.text==text && row.parts==parts && row.attachments==attachments) {"Ordinary input canonical source changed"}
    }
    companion object {
        internal fun captureFollowUp(row:StoredMessage,input:ConversationInput,generation:String,
            lifecycle:ConnectionStateCoordinator.DestinationEvidence,ledger:List<String>):NativeOrdinaryInputSource {
            check(WorkspaceIdentity.isCentral(input.conversationId) && input.origin==ConversationInput.Origin.USER && input.provenance==null)
            check(row.conversationId==input.conversationId && row.messageId==input.messageId && row.role=="user"
                && row.hiddenKind==null && row.inputState==null && row.text==input.content)
            check(row.parts.none {it.kind==ConversationInput.PROVENANCE_PART_KIND || it.kind==CentralSubmissionRequest.PART_KIND})
            return NativeOrdinaryInputSource(row.conversationId,row.messageId,"FOLLOW_UP",row.text,
                java.util.Collections.unmodifiableList(ArrayList(row.parts)),java.util.Collections.unmodifiableList(ArrayList(row.attachments)),
                java.util.Collections.unmodifiableList(ArrayList(ledger)),generation,lifecycle)
        }
        internal fun capture(row:StoredMessage,input:ConversationInput,generation:String,
            lifecycle:ConnectionStateCoordinator.DestinationEvidence):NativeOrdinaryInputSource {
            check(WorkspaceIdentity.isCentral(input.conversationId) && input.origin==ConversationInput.Origin.AGENT
                && input.provenance!=null && !WorkspaceContinuationDelivery.isProtocol(input.provenance))
            check(row.conversationId==input.conversationId && row.messageId==input.messageId && row.role=="agent"
                && row.hiddenKind==null && row.inputState==null && row.text==input.content)
            val encoded=org.json.JSONObject(row.parts.single {it.kind==ConversationInput.PROVENANCE_PART_KIND}.payload)
            check(encoded.getString("origin")=="AGENT")
            val provenance=checkNotNull(ConversationInput.Provenance.fromJson(encoded.getJSONObject("provenance")))
            check(provenance.toJson().toString()==input.provenance.toJson().toString())
            return NativeOrdinaryInputSource(row.conversationId,row.messageId,"AGENT",row.text,
                java.util.Collections.unmodifiableList(ArrayList(row.parts)),
                java.util.Collections.unmodifiableList(ArrayList(row.attachments)),emptyList(),generation,lifecycle)
        }
    }
}
