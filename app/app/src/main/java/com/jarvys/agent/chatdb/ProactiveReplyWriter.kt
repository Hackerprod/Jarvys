package com.jarvys.agent.chatdb

import com.jarvys.agent.ProactiveReplyAdmission
import com.jarvys.agent.workspaces.WorkspaceIdentity

/** Read-only disposition. Only created=true may be registered by its still-live native owner. */
class ProactiveReplyAppendReceipt internal constructor(
    val created:Boolean,
    val receipt:CentralSubmissionAppendReceipt,
    val text:String,
    val threadKey:String,
)

internal data class ProactiveReplyWriteResult(val created:Boolean,val snapshot:CentralSubmissionSnapshot,val text:String,val threadKey:String)

/** Separate typed path: ordinary central writers retain both existing null-thread guards. */
internal class ProactiveReplyWriter(private val database:JarvysChatDatabase,private val conversations:ConversationRepository) {
    suspend fun append(action:ProactiveReplyAdmission.NativeAction):ProactiveReplyWriteResult? = database.withChatWriteTransaction {
        action.requireCurrent()
        val result=appendInTransaction(action.physicalGeneration,action.sourceMessageId,action.threadKey,action.sourceFingerprint,
            action.kind,action.selectedOrdinal,action.text,action.actionId,action.submissionId,action.processId) {
                check(com.jarvys.agent.CentralAvailabilityPolicy.admissionEnabled() && com.jarvys.agent.ProactiveReplyHandoffPolicy.admissionEnabled()) {"Proactive reply admission disabled pending D3"}
                action.requireCurrent()
            }
        action.requireCurrent()
        result
    }

    /** Internal D3 persistence seam only. Production entry requires the live typed NativeAction above. */
    internal suspend fun appendInTransaction(generation:String,sourceMessageId:String,threadKey:String,sourceFingerprint:String,
        kind:String,selectedOrdinal:Int?,text:String,actionId:String,submissionId:String,processId:String,
        beforeNewWrite:()->Unit):ProactiveReplyWriteResult? = database.withChatWriteTransaction {
        val session=WorkspaceIdentity.CENTRAL_SESSION
        check(generation==ProactiveReplySourceValidation.generation(database) && processId.isNotBlank())
        check(actionId==ProactiveReplyAcceptance.actionId(generation,session,threadKey,sourceMessageId,kind))
        check(submissionId==ProactiveReplyAcceptance.submissionId(actionId) && text.isNotBlank() && text==text.trim())
        check((kind==ProactiveReplyAcceptance.OPTION && selectedOrdinal!=null && selectedOrdinal>=0) ||
            (kind==ProactiveReplyAcceptance.INLINE && selectedOrdinal==null))
        val submissions=CentralTurnSubmissionRepository(database);submissions.requireAvailable()
        val source=checkNotNull(ProactiveReplySourceValidation.read(database,sourceMessageId,threadKey,false))
        check(source.physicalGeneration==generation && source.sourceFingerprint==sourceFingerprint) {"Captured proactive source changed"}
        val referent=ProactiveReplySourceValidation.referent(database,source,kind)
        val expected=ProactiveReplyAcceptanceValue(generation,session,kind,actionId,sourceMessageId,sourceFingerprint,threadKey,
            if(kind==ProactiveReplyAcceptance.OPTION)sourceMessageId else null,selectedOrdinal,ProactiveReplyAcceptance.textDigest(text),referent?.sourceMessageId,referent?.sourceFingerprint)
        val payload=ProactiveReplyAcceptance.encode(expected)
        // Reconcile the exact durable acceptance BEFORE latest/visible/unconsumed eligibility checks.
        submissions.find(session,submissionId)?.let { prior ->
            check(prior.storageGeneration==generation)
            check(ProactiveReplySourceValidation.validateUser(database,session,prior.userMessageId)==expected) {"Conflicting proactive action delivery"}
            check(captureCentralUserRootDigestInTransaction(database,session,prior.userMessageId)==prior.sourceSha256) {"Committed proactive source changed"}
            return@withChatWriteTransaction ProactiveReplyWriteResult(false,prior,text,threadKey)
        }
        // A partial/deleted receipt is corruption, not permission to backfill or create another USER.
        check(!database.chatSql.query("SELECT 1 FROM messages WHERE conversation_id=? AND stable_id=?",arrayOf(session,submissionId)).use {it.moveToFirst()}) {"Proactive acceptance is missing its submission receipt"}
        val current=checkNotNull(ProactiveReplySourceValidation.read(database,sourceMessageId,threadKey))
        if(kind==ProactiveReplyAcceptance.OPTION && source.consumed)return@withChatWriteTransaction null // Legacy marker is evidence only.
        ProactiveReplySourceValidation.requireAdmission(database,current,kind,selectedOrdinal)
        if(kind==ProactiveReplyAcceptance.OPTION)check(source.suggestedReplies[checkNotNull(selectedOrdinal)].text.trim()==text)
        beforeNewWrite()
        val input=AppendMessageInput(session,"user",listOf(MessagePartInput(MessagePartKinds.TEXT,text),
            MessagePartInput(CentralSubmissionRequest.PART_KIND,CentralSubmissionRequest.encode(emptyList())),MessagePartInput(ProactiveReplyAcceptance.PART_KIND,payload)),
            proactiveThreadKey=threadKey,proactiveReplyToMessageId=expected.replyToMessageId,stableId=submissionId)
        val message=requireSuccess(conversations.appendMessage(input))
        if(kind==ProactiveReplyAcceptance.OPTION)requireSuccess(conversations.consumeSuggestedReplies(session,sourceMessageId))
        // Root validation in insertPrepared also checks the exact consumed marker and source linkage.
        val snapshot=submissions.insertPrepared(message,submissionId,processId,generation)
        ProactiveReplyWriteResult(true,snapshot,text,threadKey)
    }
    private fun <T> requireSuccess(result:ChatDbResult<T>):T=when(result) {
        is ChatDbResult.Success ->result.value
        else ->error("Atomic proactive reply write failed: $result")
    }
}
