package com.jarvys.agent.chatdb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ConversationReader
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Internal persistence proof with admission OFF, not a synthetic live UI/provider capability. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],shadows=[com.jarvys.agent.ArtifactOsShadow::class])
class CentralSubmissionPersistenceTest {
    @get:Rule val temporary=TemporaryFolder()
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var db:JarvysChatDatabase
    private val session=WorkspaceIdentity.CENTRAL_SESSION
    private val file get()=File(temporary.root,"submissions.db")
    @Before fun open() {db=JarvysChatDatabaseProvider.openDatabaseFileForTests(context,file,true,true)}
    @After fun close() {if(::db.isInitialized)JarvysChatDatabaseProvider.closeDatabaseFileForTests(db);worker.shutdownNow()}
    private fun <T> work(body:suspend ()->T):T=worker.submit<T>{runBlocking {body()}}.get(60,TimeUnit.SECONDS)
    private fun writer()=ConversationModelTurnWriter(db,ConversationRepository(db),ModelTranscriptRepository(db))
    private fun input(id:String,text:String="Prompt $id",attachment:Boolean=false)=AppendMessageInput(session,"user",listOf(MessagePartInput("text",text),MessagePartInput(CentralSubmissionRequest.PART_KIND,CentralSubmissionRequest.encode(listOf("selected-skill")))),
        attachments=if(attachment)listOf(MessageAttachmentInput("file-$id","note.txt","text/plain",4,"document","$id/note.txt","a".repeat(64))) else emptyList(),stableId=id)
    private suspend fun prepared(id:String,process:String="process-A",attachment:Boolean=false)=writer().appendPreparedCentralSubmission(input(id,attachment=attachment),id,process)
    private suspend fun read(id:String)=db.withChatReadTransaction {CentralTurnSubmissionRepository(db).find(session,id)}
    private suspend fun claim(row:CentralSubmissionSnapshot,eligible:Set<String> = setOf(row.submissionId))=db.withChatWriteTransaction {
        CentralTurnSubmissionRepository(db).claimPreparedInTransaction(row,"process-A","run-${row.submissionId}",7,eligible)
    }
    private suspend fun rejected(body:suspend ()->Unit) {var failure:Throwable?=null;try {body()}catch(t:Throwable){failure=t};assertNotNull("Expected strict rejection",failure)}
    @Test fun atomicUserAttachmentsAndPositiveSubmissionRoundTripWithoutBackfill()=work {
        val row=prepared("one",attachment=true)
        val original=ConversationRepository(db).getMessage(session,row.userMessageId)!!
        assertEquals("PREPARED",row.state);assertEquals(1L,row.revision)
        assertEquals(1,original.attachments.size);assertEquals("a".repeat(64),original.attachments.single().sha256)
        assertEquals(row.sourceSha256,captureCentralUserRootDigest(db,session,row.userMessageId))
        assertEquals(row,prepared("one",attachment=true))
        ConversationRepository(db).appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","ordinary history"))))
        assertEquals(1,db.withChatReadTransaction {CentralTurnSubmissionRepository(db).list(session).size})
        JarvysChatDatabaseProvider.closeDatabaseFileForTests(db);open()
        assertEquals(row,read("one"));assertEquals(original.attachments,ConversationRepository(db).getMessage(session,row.userMessageId)!!.attachments)
    }
    @Test fun failedSubmissionInsertRollsBackUserAttachmentsSpineAndFileReferences()=work {
        val before=prepared("before")
        fun count(table:String)=db.testChatSql.query("SELECT COUNT(*) FROM $table").use {check(it.moveToFirst());it.getLong(0)}
        val tables=listOf("messages","message_parts","message_attachments","conversation_items","file_refs","central_turn_submissions")
        val counts=tables.associateWith(::count)
        db.testChatSql.execSQL("CREATE TRIGGER fail_submission BEFORE INSERT ON central_turn_submissions BEGIN SELECT RAISE(ABORT,'submission_insert_failed'); END")
        try {rejected {prepared("must-rollback",attachment=true)}} finally {db.testChatSql.execSQL("DROP TRIGGER fail_submission")}
        assertEquals(counts,tables.associateWith(::count));assertEquals(before,read("before"));assertNull(read("must-rollback"))
    }
    @Test fun claimCasRejectsStaleRepeatProcessMismatchAndLaterEligibleSource()=work {
        val first=prepared("first");val second=prepared("second")
        rejected {claim(second,setOf(first.submissionId,second.submissionId))}
        rejected {db.withChatWriteTransaction {CentralTurnSubmissionRepository(db).claimPreparedInTransaction(first,"other-process","run",7,setOf(first.submissionId))}}
        val started=claim(first,setOf(first.submissionId,second.submissionId))
        assertEquals("STARTED",started.state);assertEquals(2L,started.revision)
        rejected {claim(first)};assertEquals(started,read("first"));assertEquals(second,read("second"))
    }
    @Test fun onlyEligibleLiveCatalogIdsParticipateInPriorityWithoutMintingMissingPermission()=work {
        val orphan=prepared("no-live-permit");val admitted=prepared("admitted")
        val started=claim(admitted,setOf(admitted.submissionId))
        assertEquals("STARTED",started.state);assertEquals(orphan,read(orphan.submissionId))
        // This primitive accepts an already validated native catalog snapshot; a persisted row is not that catalog.
    }
    @Test fun changedPartOrAttachmentFailsExactSourceCasWithoutReset()=work {
        for(attachment in listOf(false,true)) {
            val id="changed-$attachment";val row=prepared(id,attachment=attachment)
            if(attachment)db.testChatSql.execSQL("UPDATE message_attachments SET sha256=? WHERE conversation_id=? AND message_id=?",arrayOf("b".repeat(64),session,row.userMessageId))
            else db.testChatSql.execSQL("UPDATE message_parts SET payload='changed' WHERE conversation_id=? AND message_id=? AND kind='text'",arrayOf(session,row.userMessageId))
            rejected {claim(row)};assertEquals(row,read(id))
        }
    }
    @Test fun concurrentExactCasHasOneWinnerAndNoStartedReplayOnReopen()=work {
        val row=prepared("race");val pool=Executors.newFixedThreadPool(2)
        try {
            val start=java.util.concurrent.CountDownLatch(1)
            val outcomes=(1..2).map {pool.submit<Boolean> {start.await();runCatching {runBlocking {claim(row)}}.isSuccess}}
            start.countDown();assertEquals(1,outcomes.count {it.get(30,TimeUnit.SECONDS)})
        } finally {pool.shutdownNow()}
        val started=read("race")!!;assertEquals("STARTED",started.state)
        JarvysChatDatabaseProvider.closeDatabaseFileForTests(db);open()
        assertEquals(started,read("race"));rejected {claim(row)}
    }
    @Test fun queuedAndUncertainSourcesStayVisibleInUiButOutOfHistorySteeringAndCompaction()=work {
        val plain=(ConversationRepository(db).appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","old ordinary history")))) as ChatDbResult.Success).value
        val queued=prepared("queued");val uncertain=prepared("uncertain");claim(uncertain,setOf(uncertain.submissionId))
        val reader=ConversationReader(db)
        assertTrue(reader.readConversationMessages(session).any {it.messageId==queued.userMessageId})
        assertTrue(reader.readConversationMessages(session).any {it.messageId==uncertain.userMessageId})
        val context=reader.loadConversationContext(session)
        assertTrue(context.any {it.content=="old ordinary history"});assertFalse(context.any {it.content.startsWith("Prompt ")})
        assertEquals(plain.messageId,reader.latestUserMessageId(session));assertTrue(reader.readUserMessagesAfter(session,plain.messageId).isEmpty())
        val protection=reader.readInteractionProtection(session)
        assertTrue(protection.protectedSources.any {it.sourceId==queued.userMessageId})
        assertTrue(protection.protectedSources.any {it.sourceId==uncertain.userMessageId})
        assertEquals(0,protection.protectedPrefixLimit(context,emptySet()))
    }
    @Test fun genericMarkThroughAndFailureCannotAcknowledgeProtectedSubmissions()=work {
        val queued=prepared("queued-ack");val active=prepared("active-ack");claim(active,setOf(active.submissionId))
        val ordinary=(ConversationRepository(db).appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","ordinary later USER")))) as ChatDbResult.Success).value
        ConversationRepository(db).markIncorporatedThrough(session,ordinary.messageId)
        assertNull(ConversationRepository(db).getMessage(session,queued.userMessageId)!!.inputState)
        assertNull(ConversationRepository(db).getMessage(session,active.userMessageId)!!.inputState)
        ConversationRepository(db).markInputState(session,listOf(queued.userMessageId,active.userMessageId),"resolved")
        ConversationRepository(db).appendMessage(AppendMessageInput(session,"assistant",listOf(MessagePartInput("text","failure")),status="FAILED",ownerUserMessageId=active.userMessageId,runId="run-${active.submissionId}"))
        assertNull(ConversationRepository(db).getMessage(session,active.userMessageId)!!.inputState)
        assertEquals("STARTED",read(active.submissionId)!!.state)
    }
    @Test fun preparedRecoveryCasConservesOriginalSourceAndCannotResetStarted()=work {
        val original=prepared("recover","old-process",attachment=true)
        val recovered=db.withChatWriteTransaction {CentralTurnSubmissionRepository(db).recoverPreparedInTransaction(original,"process-A","fresh-native-action")}
        assertEquals(original.userMessageId,recovered.userMessageId);assertEquals(original.sourceSha256,recovered.sourceSha256)
        assertEquals(original.originalAdmissionProcess,recovered.originalAdmissionProcess);assertEquals("process-A",recovered.currentAdmissionProcess)
        assertEquals(original.revision+1,recovered.revision);assertEquals("fresh-native-action",recovered.recoveryReceipt)
        rejected {db.withChatWriteTransaction {CentralTurnSubmissionRepository(db).recoverPreparedInTransaction(original,"process-A","stale-action")}}
        val started=claim(recovered)
        rejected {db.withChatWriteTransaction {CentralTurnSubmissionRepository(db).recoverPreparedInTransaction(started,"process-A","must-not-replay")}}
        assertEquals(started,read(original.submissionId))
    }
    @Test fun secondHalfStopFailureRollsBackExactSteerAndSubmissionTogether()=work {
        val queued=prepared("stop-atomic")
        val steering=(writer().appendAndDispatchInterruptRequest(session,"old steering",emptyList()) as ChatDbResult.Success).value
        val id=steering.request.requestId
        fun assistantCount()=db.testChatSql.query("SELECT COUNT(*) FROM messages WHERE role='assistant'").use {check(it.moveToFirst());it.getLong(0)}
        val before=assistantCount()
        db.testChatSql.execSQL("CREATE TRIGGER reject_stop BEFORE UPDATE ON central_turn_submissions BEGIN SELECT RAISE(ABORT,'stop_second_half_failed'); END")
        try {rejected {writer().persistCentralStopCohortInTransaction(session,listOf(id),"Stopped",listOf(queued))}}
        finally {db.testChatSql.execSQL("DROP TRIGGER reject_stop")}
        assertEquals(queued,read(queued.submissionId));assertEquals(before,assistantCount())
        assertNull(ConversationRepository(db).readInterruptRequest(session,id)!!.inputState)
        assertEquals(listOf(id),writer().persistCentralStopCohortInTransaction(session,listOf(id),"Stopped",listOf(queued)))
        assertEquals("CANCELLED",read(queued.submissionId)!!.state)
        assertEquals("resolved",ConversationRepository(db).readInterruptRequest(session,id)!!.inputState)
    }
    @Test fun capturedStopDoesNotTouchLaterSendOrSteerAndDuplicateIsIdempotent()=work {
        val captured=prepared("captured")
        val old=(writer().appendAndDispatchInterruptRequest(session,"old steer",emptyList()) as ChatDbResult.Success).value.request.requestId
        val later=prepared("later")
        val newSteer=(writer().appendAndDispatchInterruptRequest(session,"later steer",emptyList()) as ChatDbResult.Success).value.request.requestId
        writer().persistCentralStopCohortInTransaction(session,listOf(old),"Stopped",listOf(captured))
        val cancelled=read(captured.submissionId)
        assertEquals(later,read(later.submissionId));assertNull(ConversationRepository(db).readInterruptRequest(session,newSteer)!!.inputState)
        assertTrue(writer().persistCentralStopCohortInTransaction(session,listOf(old),"Stopped",listOf(captured)).isEmpty())
        assertEquals(cancelled,read(captured.submissionId));assertEquals(later,read(later.submissionId))
    }
    @Test fun revisionAndStateChangesInvalidateProtectionFingerprintAndMalformedRowsFailClosed()=work {
        val row=prepared("fingerprint");val reader=ConversationReader(db)
        val before=reader.readInteractionProtection(session).fingerprint
        claim(row)
        assertNotEquals(before,reader.readInteractionProtection(session).fingerprint)
        db.testChatSql.execSQL("UPDATE central_turn_submissions SET revision=1.25 WHERE submission_id='fingerprint'")
        rejected {reader.loadConversationContext(session)}
    }
    @Test fun reflectionExcludesQueuedAndUncertainSourcesButRetainsConfirmedAndOrdinaryHistory()=work {
        val ordinary=(ConversationRepository(db).appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","ordinary reflection")))) as ChatDbResult.Success).value
        val queued=prepared("reflection-queued");val uncertain=prepared("reflection-started");claim(uncertain)
        val completed=claim(prepared("reflection-completed"))
        val assistant=(ConversationRepository(db).appendMessage(AppendMessageInput(session,"assistant",listOf(MessagePartInput("text","confirmed reflection answer")),status="COMPLETED",ownerUserMessageId=completed.userMessageId,runId=completed.claimRunId)) as ChatDbResult.Success).value
        // Persistence-reader fixture only: no native TerminalEvidence or execution permit is minted.
        val receipt=org.json.JSONObject().put("version",1).put("kind","DIRECT").put("assistantId",assistant.messageId)
            .put("runId",completed.claimRunId).put("originRunId",completed.claimRunId).put("sourceSha256",completed.sourceSha256)
            .put("finalOwnerMessageId",completed.userMessageId).put("chainDigest","a".repeat(64)).toString()
        db.testChatSql.execSQL("UPDATE central_turn_submissions SET state='COMPLETED',revision=revision+1,terminal_evidence=? WHERE conversation_id=? AND submission_id=?",arrayOf(receipt,session,completed.submissionId))
        val payload=MemoryTimelineRepository(db).buildReflectionPayload(session).text
        assertTrue(payload.contains("ordinary reflection"));assertTrue(payload.contains("confirmed reflection answer"))
        assertTrue(payload.contains("Prompt reflection-completed"));assertFalse(payload.contains("Prompt reflection-queued"));assertFalse(payload.contains("Prompt reflection-started"))
        assertTrue(CentralTurnSubmissionRepository(db).historyExcluded(session).containsAll(listOf(queued.userMessageId,uncertain.userMessageId)))
        assertNotEquals(ordinary.messageId,ConversationReader(db).latestUserMessageId(session))
    }
    @Test fun selectedSkillRequestIsExactImmutableEvidenceAcrossRecoveryAndReopen()=work {
        val original=prepared("skill-source","old-process")
        assertEquals(listOf("selected-skill"),CentralSubmissionRequest.read(db,session,original.userMessageId))
        JarvysChatDatabaseProvider.closeDatabaseFileForTests(db);open()
        val recovered=db.withChatWriteTransaction {CentralTurnSubmissionRepository(db).recoverPreparedInTransaction(original,"process-A","explicit-recovery")}
        assertEquals(original.sourceSha256,recovered.sourceSha256)
        assertEquals(listOf("selected-skill"),CentralSubmissionRequest.read(db,session,original.userMessageId))
        assertFalse(ConversationReader(db).readConversationMessages(session).joinToString {it.text}.contains(CentralSubmissionRequest.PART_KIND))
        db.testChatSql.execSQL("UPDATE message_parts SET payload=? WHERE conversation_id=? AND message_id=? AND kind=?",arrayOf(CentralSubmissionRequest.encode(listOf("different-skill")),session,original.userMessageId,CentralSubmissionRequest.PART_KIND))
        rejected {claim(recovered)};assertEquals(recovered,read(original.submissionId))
    }
    @Test fun requestCodecRejectsNullDuplicatesUnknownShapeAndVersion()=work {
        for(payload in listOf("null","{}","{\"version\":1,\"selectedSkillIds\":null}","{\"version\":1,\"selectedSkillIds\":[null]}","{\"version\":1,\"selectedSkillIds\":[\"a\",\"a\"]}","{\"version\":2,\"selectedSkillIds\":[]}","{\"version\":1,\"selectedSkillIds\":[],\"grant\":true}")) {
            rejected {CentralSubmissionRequest.decode(payload)}
        }
        assertEquals(emptyList<String>(),CentralSubmissionRequest.decode(CentralSubmissionRequest.encode(emptyList())))
        assertEquals(listOf("actual-selection"),CentralSubmissionRequest.decode(CentralSubmissionRequest.encode(listOf("actual-selection"))))
    }
}
