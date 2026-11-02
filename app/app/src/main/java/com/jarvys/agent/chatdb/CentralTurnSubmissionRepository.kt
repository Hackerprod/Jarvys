package com.jarvys.agent.chatdb

import android.database.Cursor
import org.json.JSONObject
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CentralTreeAdmission
import com.jarvys.agent.CentralSubmissionAdmission
import com.jarvys.agent.workspaces.WorkspaceIdentity

/** Native writer/read transactions own all calls. No read may mint a dispatch permission. */
internal class CentralTurnSubmissionRepository(private val database: JarvysChatDatabase) {
    private val sql get()=database.chatSql
    fun available():Boolean = sql.query("PRAGMA user_version").use { check(it.moveToFirst());it.getInt(0)==3 }
    fun requireAvailable() {check(available()) {"Central submission schema is not ready"}}
    fun find(sessionId:String,submissionId:String):CentralSubmissionSnapshot? {
        if(!available())return null
        return sql.query("SELECT * FROM central_turn_submissions WHERE conversation_id=? AND submission_id=?",arrayOf(sessionId,submissionId)).use { c ->if(c.moveToFirst())read(c) else null}
    }
    fun list(sessionId:String):List<CentralSubmissionSnapshot> {
        if(!available())return emptyList()
        return sql.query("SELECT * FROM central_turn_submissions WHERE conversation_id=? ORDER BY user_seq,submission_id",arrayOf(sessionId)).use { c ->buildList {while(c.moveToNext())add(read(c))}}
    }
    fun historyExcluded(sessionId:String):Set<String> = list(sessionId).filterNot(::confirmedCompleted).mapTo(linkedSetOf()){it.userMessageId}
    private fun terminal(row:CentralSubmissionSnapshot):JSONObject? {
        val encoded=row.terminalEvidence ?: return null
        val receipt=JSONObject(encoded)
        check(receipt.get("version") is Int && receipt.getInt("version")==1)
        val kind=receipt.getString("kind")
        val keys=mutableSetOf("version","kind","assistantId","runId","originRunId","sourceSha256","finalOwnerMessageId")
        if(row.state=="COMPLETED")keys.add("chainDigest")
        if(kind=="SUSPENSION")keys.addAll(listOf("resumeId","successorCertificateSha256")) else check(kind=="DIRECT")
        check(receipt.keys().asSequence().toSet()==keys)
        for(key in keys-"version")check(receipt.get(key) is String && receipt.getString(key).isNotBlank())
        check(receipt.getString("originRunId")==row.claimRunId && receipt.getString("sourceSha256")==row.sourceSha256)
        if(row.state=="COMPLETED")check(receipt.getString("chainDigest").matches(Regex("[a-f0-9]{64}")))
        if(kind=="DIRECT")check(receipt.getString("runId")==row.claimRunId)
        else check(receipt.getString("successorCertificateSha256").matches(Regex("[a-f0-9]{64}")))
        return receipt
    }
    fun confirmedCompleted(row:CentralSubmissionSnapshot):Boolean {
        if(row.state!="COMPLETED" || row.terminalEvidence==null || row.claimRunId==null)return false
        val receipt=checkNotNull(terminal(row))
        if(captureCentralUserRootDigestInTransaction(database,row.sessionId,row.userMessageId)!=row.sourceSha256)return false
        return sql.query("SELECT role,status,owner_user_message_id,run_id,hidden_kind FROM messages WHERE conversation_id=? AND message_id=?",arrayOf(row.sessionId,receipt.getString("assistantId"))).use { c ->
            c.moveToFirst() && c.getString(0)=="assistant" && c.getString(1)=="COMPLETED" && c.getString(2)==receipt.getString("finalOwnerMessageId") && c.getString(3)==receipt.getString("runId") && c.isNull(4) && !c.moveToNext()
        }
    }
    private fun changed() {check(sql.query("SELECT changes()").use {it.moveToFirst() && it.getLong(0)==1L}) {"Central submission CAS conflict"}}
    private fun generation():String=sql.query("SELECT value FROM storage_meta WHERE key=?",arrayOf(ConnectionProjectionRepository.GENERATION_KEY)).use { c ->check(c.moveToFirst() && c.getType(0)==Cursor.FIELD_TYPE_STRING);c.getString(0)}
    private fun requireSource(row:CentralSubmissionSnapshot) {
        CentralSubmissionRequest.read(database,row.sessionId,row.userMessageId)
        check(generation()==row.storageGeneration) {"Central submission storage generation changed"}
        check(captureCentralUserRootDigestInTransaction(database,row.sessionId,row.userMessageId)==row.sourceSha256) {"Central submission source changed"}
        check(sql.query("SELECT seq FROM messages WHERE conversation_id=? AND message_id=?",arrayOf(row.sessionId,row.userMessageId)).use {it.moveToFirst() && it.getLong(0)==row.userSeq})
    }
    /** Called after appendMessage under the same native writer transaction. */
    fun insertPrepared(message:StoredMessage,submissionId:String,processId:String,storageGeneration:String):CentralSubmissionSnapshot {
        requireAvailable();check(WorkspaceIdentity.isCentral(message.conversationId) && message.role=="user" && message.hiddenKind==null)
        check(submissionId.isNotBlank() && processId.isNotBlank() && storageGeneration==generation())
        CentralSubmissionRequest.read(database,message.conversationId,message.messageId)
        val digest=captureCentralUserRootDigestInTransaction(database,message.conversationId,message.messageId)
        find(message.conversationId,submissionId)?.let { existing ->
            check(existing.userMessageId==message.messageId && existing.sourceSha256==digest && existing.storageGeneration==storageGeneration && existing.originalAdmissionProcess==processId) {"Conflicting Central submission delivery"}
            return existing
        }
        sql.execSQL("INSERT INTO central_turn_submissions(conversation_id,submission_id,user_message_id,user_seq,source_sha256,storage_generation,original_admission_process,current_admission_process,revision,state) VALUES(?,?,?,?,?,?,?,?,1,'PREPARED')",arrayOf(message.conversationId,submissionId,message.messageId,message.seq,digest,storageGeneration,processId,processId))
        return checkNotNull(find(message.conversationId,submissionId))
    }
    fun claim(permit:CentralSubmissionAdmission.LivePermit,authority:CentralSubmissionAdmission.ClaimAuthority):CentralSubmissionSnapshot {
        requireAvailable();authority.requireTransactionOwner()
        val row=checkNotNull(find(permit.sessionId,permit.submissionId));requireSource(row)
        check(row.state=="PREPARED" && row.revision==permit.revision && row.userMessageId==permit.userMessageId && row.sourceSha256==permit.sourceSha256 && row.storageGeneration==permit.storageGeneration && row.currentAdmissionProcess==permit.processId && authority.processId==permit.processId)
        val claimed=claimPreparedInTransaction(row,authority.processId,authority.runId,authority.tokenGeneration,authority.eligibleSubmissionIds.toSet())
        authority.requireTransactionOwner()
        return claimed
    }
    /** Exact CAS persistence primitive, invoked only in a native writer transaction. */
    internal fun claimPreparedInTransaction(expected:CentralSubmissionSnapshot,processId:String,runId:String,
        tokenGeneration:Long,eligible:Set<String>):CentralSubmissionSnapshot {
        requireAvailable();val row=checkNotNull(find(expected.sessionId,expected.submissionId));requireSource(row)
        check(row==expected && row.state=="PREPARED" && row.currentAdmissionProcess==processId && runId.isNotBlank())
        check(row.submissionId in eligible)
        val oldest=list(row.sessionId).firstOrNull {it.state=="PREPARED" && it.currentAdmissionProcess==processId && it.submissionId in eligible}
        check(oldest?.submissionId==row.submissionId) {"A prior eligible Central submission owns priority"}
        sql.execSQL("UPDATE central_turn_submissions SET state='STARTED',revision=revision+1,claim_process=?,claim_run_id=?,claim_token_generation=? WHERE conversation_id=? AND submission_id=? AND revision=? AND state='PREPARED' AND current_admission_process=?",arrayOf(processId,runId,tokenGeneration,row.sessionId,row.submissionId,row.revision,processId));changed()
        return checkNotNull(find(row.sessionId,row.submissionId))
    }
    fun requireClaim(authority:CentralSubmissionAdmission.ClaimAuthority):CentralSubmissionSnapshot {
        val permit=authority.permit;authority.requireTransactionOwner()
        val row=checkNotNull(find(permit.sessionId,permit.submissionId));requireSource(row)
        check(row.state=="STARTED" && row.revision==permit.revision+1 && row.userMessageId==permit.userMessageId && row.sourceSha256==permit.sourceSha256 && row.storageGeneration==permit.storageGeneration && row.currentAdmissionProcess==permit.processId)
        check(row.claimProcess==authority.processId && row.claimRunId==authority.runId && row.claimTokenGeneration==authority.tokenGeneration)
        return row
    }
    fun recover(sessionId:String,submissionId:String,revision:Long,authority:CentralSubmissionAdmission.RecoveryAuthority):CentralSubmissionSnapshot {
        requireAvailable();authority.requireCurrent(sessionId,submissionId,revision)
        val row=checkNotNull(find(sessionId,submissionId));requireSource(row)
        check(row.state=="PREPARED" && row.revision==revision)
        val result=recoverPreparedInTransaction(row,authority.processId,authority.receiptId)
        authority.requireCurrent(sessionId,submissionId,revision)
        return result
    }
    internal fun recoverPreparedInTransaction(expected:CentralSubmissionSnapshot,processId:String,receiptId:String):CentralSubmissionSnapshot {
        val row=checkNotNull(find(expected.sessionId,expected.submissionId));requireSource(row)
        check(row==expected && row.state=="PREPARED" && processId.isNotBlank() && receiptId.isNotBlank())
        sql.execSQL("UPDATE central_turn_submissions SET current_admission_process=?,revision=revision+1,recovery_receipt=? WHERE conversation_id=? AND submission_id=? AND revision=? AND state='PREPARED'",arrayOf(processId,receiptId,row.sessionId,row.submissionId,row.revision));changed()
        return checkNotNull(find(row.sessionId,row.submissionId))
    }
    private fun finishOwned(row:CentralSubmissionSnapshot,assistantMessageId:String,runId:String,
        terminalState:String,receipt:JSONObject,evidence:CentralTreeAdmission.TerminalEvidence?):CentralSubmissionSnapshot {
        if(terminalState=="COMPLETED") {
            checkNotNull(evidence) {"Successful Central submission requires native terminal evidence"}
            check(evidence.assistantId==assistantMessageId && evidence.runId==runId && evidence.originalUserMessageId==row.userMessageId && evidence.storageGeneration==row.storageGeneration && evidence.originDigest==row.sourceSha256)
            receipt.put("chainDigest",evidence.chainDigest)
        } else check(evidence==null)
        val finalOwner=evidence?.finalOwnerMessageId ?: row.userMessageId
        receipt.put("finalOwnerMessageId",finalOwner)
        check(terminalState in setOf("COMPLETED","FAILED"))
        sql.query("SELECT role,status,owner_user_message_id,run_id,hidden_kind FROM messages WHERE conversation_id=? AND message_id=?",arrayOf(row.sessionId,assistantMessageId)).use { c ->
            check(c.moveToFirst() && c.getString(0)=="assistant" && c.getString(2)==finalOwner && c.getString(3)==runId && c.isNull(4))
            check(if(terminalState=="COMPLETED") c.getString(1)=="COMPLETED" else c.getString(1) in setOf("FAILED","STOPPED","PARTIAL","INTERRUPTED"))
        }
        sql.execSQL("UPDATE central_turn_submissions SET state=?,revision=revision+1,terminal_evidence=? WHERE conversation_id=? AND submission_id=? AND revision=? AND state='STARTED'",arrayOf(terminalState,receipt.toString(),row.sessionId,row.submissionId,row.revision));changed()
        return checkNotNull(find(row.sessionId,row.submissionId))
    }
    private fun terminalReceipt(row:CentralSubmissionSnapshot,assistantId:String,runId:String,kind:String)=JSONObject()
        .put("version",1).put("kind",kind).put("assistantId",assistantId).put("runId",runId)
        .put("originRunId",row.claimRunId).put("sourceSha256",row.sourceSha256)
    fun finish(authority:CentralSubmissionAdmission.ClaimAuthority,assistantMessageId:String,terminalState:String):CentralSubmissionSnapshot {
        val row=requireClaim(authority)
        val evidence=if(terminalState=="COMPLETED")authority.terminalEvidence.also {checkNotNull(it);authority.requireTransactionOwner()} else null
        val finished=finishOwned(row,assistantMessageId,authority.runId,terminalState,terminalReceipt(row,assistantMessageId,authority.runId,"DIRECT"),evidence)
        if(evidence!=null) {check(authority.terminalEvidence===evidence);authority.requireTransactionOwner()}
        return finished
    }
    fun requireSuspension(claim:CentralTreeAdmission.SuspensionClaim,token:CancellationToken):CentralSubmissionSnapshot? {
        claim.requireOwnerPure(token)
        check(generation()==claim.storageGeneration && captureCentralUserRootDigestInTransaction(database,WorkspaceIdentity.CENTRAL_SESSION,claim.originalUserMessageId)==claim.originDigest)
        // Older genuine roots have no submission row; never backfill one or infer queue authority.
        val row=list(WorkspaceIdentity.CENTRAL_SESSION).singleOrNull {it.userMessageId==claim.originalUserMessageId} ?: return null
        requireSource(row)
        check(row.claimRunId==claim.originalRunId && row.sourceSha256==claim.originDigest && row.storageGeneration==claim.storageGeneration)
        if(row.state=="COMPLETED") {check(confirmedCompleted(row));return null}
        check(row.state=="STARTED")
        return row
    }
    fun finishSuspension(claim:CentralTreeAdmission.SuspensionClaim,token:CancellationToken,
        assistantMessageId:String,terminalState:String,evidence:CentralTreeAdmission.TerminalEvidence?):CentralSubmissionSnapshot? {
        val row=requireSuspension(claim,token) ?: return null
        val receipt=terminalReceipt(row,assistantMessageId,claim.runId,"SUSPENSION")
            .put("resumeId",claim.resumeId).put("successorCertificateSha256",claim.successorCertificateSha256)
        if(terminalState=="COMPLETED")checkNotNull(evidence).requireOwnerPure(token)
        val finished=finishOwned(row,assistantMessageId,claim.runId,terminalState,receipt,evidence)
        if(evidence!=null)evidence.requireOwnerPure(token)
        return finished
    }
    fun cancel(authority:CentralSubmissionAdmission.CancelAuthority):List<CentralSubmissionSnapshot> {
        authority.requireCurrent()
        val result=cancelCapturedInTransaction(authority.captured)
        authority.requireCurrent();return result
    }
    /** Internal exact-cohort CAS, composed with existing STEER disposition in one writer lease. */
    internal fun cancelCapturedInTransaction(capturedRows:List<CentralSubmissionSnapshot>):List<CentralSubmissionSnapshot> {
        if(capturedRows.isEmpty())return emptyList()
        requireAvailable()
        val result=mutableListOf<CentralSubmissionSnapshot>()
        for(captured in capturedRows) {
            val row=checkNotNull(find(captured.sessionId,captured.submissionId));requireSource(row)
            check(row.userMessageId==captured.userMessageId && row.sourceSha256==captured.sourceSha256 && row.storageGeneration==captured.storageGeneration)
            if(row.state in setOf("COMPLETED","FAILED","CANCELLED")) {result.add(row);continue}
            check(row==captured) {"Captured Central STOP cohort changed"}
            sql.execSQL("UPDATE central_turn_submissions SET state='CANCELLED',revision=revision+1 WHERE conversation_id=? AND submission_id=? AND revision=? AND state IN ('PREPARED','STARTED')",arrayOf(row.sessionId,row.submissionId,row.revision));changed()
            result.add(checkNotNull(find(row.sessionId,row.submissionId)))
        }
        return result
    }
    private fun read(c:Cursor):CentralSubmissionSnapshot {
        fun text(name:String):String {val i=c.getColumnIndexOrThrow(name);check(c.getType(i)==Cursor.FIELD_TYPE_STRING);return c.getString(i)}
        fun nullable(name:String):String? {val i=c.getColumnIndexOrThrow(name);return if(c.isNull(i))null else text(name)}
        fun number(name:String):Long {val i=c.getColumnIndexOrThrow(name);check(c.getType(i)==Cursor.FIELD_TYPE_INTEGER);return c.getLong(i)}
        val token=if(c.isNull(c.getColumnIndexOrThrow("claim_token_generation")))null else number("claim_token_generation")
        val result=CentralSubmissionSnapshot(text("conversation_id"),text("submission_id"),text("user_message_id"),number("user_seq"),text("source_sha256"),text("storage_generation"),text("original_admission_process"),text("current_admission_process"),number("revision"),text("state"),nullable("claim_process"),nullable("claim_run_id"),token,nullable("terminal_evidence"),nullable("recovery_receipt"))
        check(WorkspaceIdentity.isCentral(result.sessionId) && result.submissionId.isNotBlank() && result.userMessageId.isNotBlank() && result.userSeq>0 && result.revision>0)
        check(result.sourceSha256.matches(Regex("[a-f0-9]{64}")) && result.storageGeneration.isNotBlank() && result.originalAdmissionProcess.isNotBlank() && result.currentAdmissionProcess.isNotBlank())
        check(result.state in setOf("PREPARED","STARTED","COMPLETED","FAILED","CANCELLED"))
        val claimed=result.claimProcess!=null && result.claimRunId!=null && result.claimTokenGeneration!=null
        check(claimed || (result.claimProcess==null && result.claimRunId==null && result.claimTokenGeneration==null))
        check(result.state!="PREPARED" || (!claimed && result.terminalEvidence==null))
        check(result.state !in setOf("STARTED","COMPLETED","FAILED") || claimed)
        check(result.state!="STARTED" || result.terminalEvidence==null)
        check(result.state !in setOf("COMPLETED","FAILED") || result.terminalEvidence!=null)
        return result
    }
}
