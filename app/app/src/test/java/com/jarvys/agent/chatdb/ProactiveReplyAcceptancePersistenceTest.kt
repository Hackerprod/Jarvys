package com.jarvys.agent.chatdb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ConversationInput
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

/** D3 storage-only seams. This suite does not mint a native action or prove provider/device delivery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],shadows=[com.jarvys.agent.ArtifactOsShadow::class])
class ProactiveReplyAcceptancePersistenceTest {
    @get:Rule val temporary=TemporaryFolder()
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var db:JarvysChatDatabase
    private val session=WorkspaceIdentity.CENTRAL_SESSION
    private val thread="proactive:reply-proof:😀:e\u0301"
    private val file get()=File(temporary.root,"reply-proof.db")
    @Before fun open() {db=JarvysChatDatabaseProvider.openDatabaseFileForTests(context,file,true,true)}
    @After fun close() {if(::db.isInitialized)JarvysChatDatabaseProvider.closeDatabaseFileForTests(db);worker.shutdownNow()}
    private fun <T> work(body:suspend ()->T):T=worker.submit<T>{runBlocking {body()}}.get(60,TimeUnit.SECONDS)
    private fun repository()=ConversationRepository(db)
    private suspend fun notice(id:String="notice",key:String=thread):StoredMessage {
        ConnectionProjectionRepository(db).generation()
        return (repository().appendProactiveAssistantMessageIfAbsent(session,id,"Exact notice 😀",key,
            listOf(SuggestedReplyInput("Yes","Do it 😀"),SuggestedReplyInput("Other","Do something else"))) as ChatDbResult.Success).value
    }
    private suspend fun source(id:String)=db.withChatReadTransaction {checkNotNull(ProactiveReplySourceValidation.read(db,id,null))}
    private suspend fun accept(source:ProactiveReplySourceSnapshot,kind:String=ProactiveReplyAcceptance.OPTION,ordinal:Int?=0,
        text:String=if(kind==ProactiveReplyAcceptance.OPTION)source.suggestedReplies[ordinal!!].text.trim() else "Same reply 😀",
        beforeNewWrite:()->Unit={}):ProactiveReplyWriteResult? {
        val action=ProactiveReplyAcceptance.actionId(source.physicalGeneration,session,source.threadKey,source.sourceMessageId,kind)
        return ProactiveReplyWriter(db,repository()).appendInTransaction(source.physicalGeneration,source.sourceMessageId,source.threadKey,
            source.sourceFingerprint,kind,ordinal,text,action,ProactiveReplyAcceptance.submissionId(action),"process-A",beforeNewWrite)
    }
    private suspend fun rejected(body:suspend ()->Unit) {var failure:Throwable?=null;try {body()}catch(t:Throwable){failure=t};assertNotNull("Expected strict rejection",failure)}
    private fun scalar(sql:String):Long=db.testChatSql.query(sql).use {check(it.moveToFirst());it.getLong(0)}
    private fun conservation():List<Long> = listOf("messages","message_parts","conversation_items","central_turn_submissions","proactive_suggested_replies")
        .map {scalar("SELECT COUNT(*) FROM $it")} + listOf(scalar("SELECT next_seq FROM conversations"),scalar("SELECT message_count FROM conversations"))

    @Test fun optionDuplicateIsDataOnlyAndConflictingOrdinalCannotCreateSecondUser()=work {
        val notice=notice();val captured=source(notice.messageId)
        var beforeWrites=0
        val first=checkNotNull(accept(captured,beforeNewWrite={beforeWrites++}));assertTrue(first.created)
        val duplicate=checkNotNull(accept(captured,beforeNewWrite={beforeWrites++}));assertFalse(duplicate.created)
        assertEquals(first.snapshot,duplicate.snapshot);assertEquals(1,beforeWrites)
        rejected {accept(captured,ordinal=1)}
        assertEquals(1L,scalar("SELECT COUNT(*) FROM central_turn_submissions"));assertEquals(1L,scalar("SELECT COUNT(*) FROM messages WHERE role='user'"))
        val user=repository().getMessage(session,first.snapshot.userMessageId)!!
        assertEquals(thread,user.proactiveThreadKey);assertEquals(notice.messageId,user.proactiveReplyToMessageId)
        assertEquals(first.snapshot.sourceSha256,captureCentralUserRootDigest(db,session,user.messageId))
        JarvysChatDatabaseProvider.closeDatabaseFileForTests(db);open()
        assertEquals(first.snapshot,checkNotNull(accept(captured)).snapshot)
    }

    @Test fun eachCompositeFailureRollsBackUserConsumptionQueueSpineAndCounters()=work {
        for(kind in listOf("user","marker","consume","submission")) {
            val notice=notice("notice-$kind");val captured=source(notice.messageId);val before=conservation()
            val trigger=when(kind) {
                "user"->"BEFORE INSERT ON messages WHEN NEW.role='user'"
                "marker"->"BEFORE INSERT ON conversation_items WHEN NEW.item_kind='proactive_reply_used'"
                "consume"->"BEFORE UPDATE OF suggested_replies_consumed_seq ON messages"
                else->"BEFORE INSERT ON central_turn_submissions"
            }
            db.testChatSql.execSQL("CREATE TRIGGER reject_reply $trigger BEGIN SELECT RAISE(ABORT,'injected_reply_failure'); END")
            try {rejected {accept(captured)}} finally {db.testChatSql.execSQL("DROP TRIGGER reject_reply")}
            assertEquals(before,conservation());assertNull(repository().getMessage(session,notice.messageId)!!.suggestedRepliesConsumedSeq)
        }
        JarvysChatDatabaseProvider.closeDatabaseFileForTests(db);open()
        assertEquals(0L,scalar("SELECT COUNT(*) FROM central_turn_submissions"));assertEquals(0L,scalar("SELECT COUNT(*) FROM messages WHERE role='user'"))
    }

    @Test fun oldConsumedNoticeIsNotBackfilled()=work {
        val notice=notice();val captured=source(notice.messageId)
        repository().consumeSuggestedReplies(session,notice.messageId)
        val before=conservation();assertNull(accept(captured));assertEquals(before,conservation())
    }

    @Test fun inlineUsesExactOccurrenceAndAcceptedUserBecomesNextIdenticalReplyAnchor()=work {
        val notice=notice();val captured=source(notice.messageId)
        val first=checkNotNull(accept(captured,ProactiveReplyAcceptance.INLINE,null))
        val duplicate=checkNotNull(accept(captured,ProactiveReplyAcceptance.INLINE,null));assertFalse(duplicate.created)
        rejected {accept(captured,ProactiveReplyAcceptance.INLINE,null,"Different text")}
        val next=db.withChatReadTransaction {checkNotNull(ProactiveReplySourceValidation.read(db,null,thread))}
        assertEquals(first.snapshot.userMessageId,next.sourceMessageId)
        assertNotEquals(captured.inlineActionId,next.inlineActionId)
        val second=checkNotNull(accept(next,ProactiveReplyAcceptance.INLINE,null));assertTrue(second.created)
        assertNotEquals(first.snapshot.submissionId,second.snapshot.submissionId)
        assertNull(repository().getMessage(session,second.snapshot.userMessageId)!!.proactiveReplyToMessageId)
        assertEquals(2L,scalar("SELECT COUNT(*) FROM central_turn_submissions"))
    }

    @Test fun staleHiddenAndUnfinishedSourcesCannotAdmitButLaterHideDoesNotRevoke()=work {
        val notice=notice();val captured=source(notice.messageId)
        val newer=(repository().appendMessage(AppendMessageInput(session,"assistant",listOf(MessagePartInput("text","unfinished")),
            status="streaming",proactiveThreadKey=thread)) as ChatDbResult.Success).value
        assertEquals(notice.messageId,db.withChatReadTransaction {ProactiveReplySourceValidation.read(db,null,thread)}!!.sourceMessageId)
        rejected {source(newer.messageId)}
        val accepted=checkNotNull(accept(captured,ProactiveReplyAcceptance.INLINE,null))
        db.testChatSql.execSQL("UPDATE messages SET hidden_kind='proactive_dismissed' WHERE message_id=?",arrayOf(notice.messageId))
        assertEquals(accepted.snapshot.sourceSha256,captureCentralUserRootDigest(db,session,accepted.snapshot.userMessageId))
        assertFalse(checkNotNull(accept(captured,ProactiveReplyAcceptance.INLINE,null)).created)
        rejected {accept(captured)} // New OPTION on a hidden notice is rejected despite old INLINE receipt.
        db.withChatWriteTransaction {CentralTurnSubmissionRepository(db).cancelCapturedInTransaction(listOf(accepted.snapshot))}
        assertEquals("CANCELLED",checkNotNull(accept(captured,ProactiveReplyAcceptance.INLINE,null)).snapshot.state)
    }

    @Test fun currentEligibleSourceExcludesInternalProvenanceAndStaleInline()=work {
        val first=notice();val captured=source(first.messageId)
        repository().appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","internal"),MessagePartInput(ConversationInput.PROVENANCE_PART_KIND,"{}")),proactiveThreadKey=thread))
        assertEquals(first.messageId,db.withChatReadTransaction {ProactiveReplySourceValidation.read(db,null,thread)}!!.sourceMessageId)
        notice("new-notice")
        rejected {accept(captured,ProactiveReplyAcceptance.INLINE,null)}
        assertNotNull(accept(captured)) // Visible unconsumed OPTION retains its original notice identity.
    }

    @Test fun changedUserThreadReplyToSourceOptionOrProofFailsEveryReadWithoutFallback()=work {
        // This attempted corruption is rejected by the native spine FK before any read validator.
        val pinnedNotice=notice("fk-sequence","fk-sequence-thread")
        val pinned=checkNotNull(accept(source(pinnedNotice.messageId)))
        val pinnedNoticeAfterAcceptance=repository().getMessage(session,pinnedNotice.messageId)
        val pinnedUser=repository().getMessage(session,pinned.snapshot.userMessageId)
        val before=conservation()
        val failure=runCatching {db.testChatSql.execSQL("UPDATE messages SET seq=seq+10000 WHERE message_id=?",arrayOf(pinnedNotice.messageId))}.exceptionOrNull()
        assertTrue("The actual FK must reject a missing spine parent",generateSequence(failure){it.cause}.any {it is android.database.SQLException && it.message.orEmpty().contains("Error code: 19") && it.message.orEmpty().contains("FOREIGN KEY constraint failed")})
        assertEquals(before,conservation())
        assertEquals(pinnedNoticeAfterAcceptance,repository().getMessage(session,pinnedNotice.messageId))
        assertEquals(pinnedUser,repository().getMessage(session,pinned.snapshot.userMessageId))
        assertEquals(pinned.snapshot,db.withChatReadTransaction {CentralTurnSubmissionRepository(db).find(session,pinned.snapshot.submissionId)})
        assertEquals(pinned.snapshot.sourceSha256,captureCentralUserRootDigest(db,session,pinned.snapshot.userMessageId))
        assertEquals(0L,scalar("SELECT COUNT(*) FROM pragma_foreign_key_check"))
        val mutations=listOf<(StoredMessage,ProactiveReplyWriteResult)->Unit>(
            {_,a->db.testChatSql.execSQL("UPDATE messages SET proactive_thread_key=NULL WHERE message_id=?",arrayOf(a.snapshot.userMessageId))},
            {_,a->db.testChatSql.execSQL("UPDATE messages SET proactive_reply_to_message_id=NULL WHERE message_id=?",arrayOf(a.snapshot.userMessageId))},
            {n,_->db.testChatSql.execSQL("UPDATE messages SET proactive_thread_key='other' WHERE message_id=?",arrayOf(n.messageId))},
            {n,_->db.testChatSql.execSQL("UPDATE proactive_suggested_replies SET text='changed' WHERE message_id=? AND ordinal=0",arrayOf(n.messageId))},
            {_,a->db.testChatSql.execSQL("DELETE FROM message_parts WHERE message_id=? AND kind=?",arrayOf(a.snapshot.userMessageId,ProactiveReplyAcceptance.PART_KIND))},
            // Existing consumed marker is a real spine parent, but cannot become the source's sequence.
            {n,_->db.testChatSql.execSQL("UPDATE messages SET seq=suggested_replies_consumed_seq WHERE message_id=?",arrayOf(n.messageId))},
            {n,_->db.testChatSql.execSQL("UPDATE messages SET role='agent' WHERE message_id=?",arrayOf(n.messageId))},
            {n,_->db.testChatSql.execSQL("UPDATE messages SET proactive_decision_id='changed-decision' WHERE message_id=?",arrayOf(n.messageId))},
            {n,_->db.testChatSql.execSQL("UPDATE messages SET suggested_replies_consumed_seq=NULL WHERE message_id=?",arrayOf(n.messageId))},
        )
        mutations.forEachIndexed { index,mutate ->
            val notice=notice("mutated-$index","thread-$index");val accepted=checkNotNull(accept(source(notice.messageId)))
            mutate(notice,accepted)
            rejected {captureCentralUserRootDigest(db,session,accepted.snapshot.userMessageId)}
            rejected {repository().readCompactedProactiveReferent(session,accepted.snapshot.userMessageId,Long.MAX_VALUE)}
            rejected {ConversationReader(db).readCompactedProactiveReferent(session,accepted.snapshot.userMessageId)}
            rejected {db.withChatWriteTransaction {CentralTurnSubmissionRepository(db).cancelCapturedInTransaction(listOf(accepted.snapshot))}}
        }
    }

    @Test fun pinnedOneLevelReferentSurvivesCompactionAndRejectsPriorNoticeMutation()=work {
        val notice=notice();val first=checkNotNull(accept(source(notice.messageId),ProactiveReplyAcceptance.INLINE,null))
        val second=checkNotNull(accept(source(first.snapshot.userMessageId),ProactiveReplyAcceptance.INLINE,null))
        val referent=repository().readCompactedProactiveReferent(session,second.snapshot.userMessageId,Long.MAX_VALUE)!!
        assertEquals(notice.messageId,referent.noticeMessageId);assertEquals(thread,referent.threadKey)
        db.testChatSql.execSQL("UPDATE message_parts SET payload='changed original notice' WHERE message_id=? AND kind='text'",arrayOf(notice.messageId))
        rejected {captureCentralUserRootDigest(db,session,second.snapshot.userMessageId)}
        rejected {repository().readCompactedProactiveReferent(session,second.snapshot.userMessageId,Long.MAX_VALUE)}
    }

    @Test fun physicalGenerationChangeFailsEvenWhenBootstrapFormatIsUnchanged()=work {
        val notice=notice();val captured=source(notice.messageId);val accepted=checkNotNull(accept(captured))
        db.testChatSql.execSQL("UPDATE storage_meta SET value=? WHERE key=?",arrayOf(java.util.UUID.randomUUID().toString(),ConnectionProjectionRepository.GENERATION_KEY))
        rejected {accept(captured)};rejected {captureCentralUserRootDigest(db,session,accepted.snapshot.userMessageId)}
    }

    @Test fun legacyOrdinaryAndThreadHistoryRemainUnmodifiedWithNoPositiveBackfill()=work {
        notice()
        val ordinary=(repository().appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","old ordinary 😀")))) as ChatDbResult.Success).value
        val threaded=(repository().appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","old threaded 😀")),proactiveThreadKey=thread)) as ChatDbResult.Success).value
        val before=listOf(captureCentralUserRootDigest(db,session,ordinary.messageId),captureCentralUserRootDigest(db,session,threaded.messageId))
        assertNull(db.withChatReadTransaction {ProactiveReplySourceValidation.validateUser(db,session,threaded.messageId)})
        JarvysChatDatabaseProvider.closeDatabaseFileForTests(db);open()
        assertEquals(before,listOf(captureCentralUserRootDigest(db,session,ordinary.messageId),captureCentralUserRootDigest(db,session,threaded.messageId)))
        assertEquals(0L,scalar("SELECT COUNT(*) FROM central_turn_submissions"));assertEquals(3L,scalar("PRAGMA user_version"))
        assertEquals(0L,scalar("SELECT COUNT(*) FROM pragma_foreign_key_check"))
    }

    @Test fun deletedSubmissionCannotBeBackfilledFromTypedUserAndNullProofCannotBecomeOrdinary()=work {
        val notice=notice();val captured=source(notice.messageId);val accepted=checkNotNull(accept(captured))
        db.testChatSql.execSQL("DELETE FROM central_turn_submissions WHERE submission_id=?",arrayOf(accepted.snapshot.submissionId))
        val before=conservation();rejected {accept(captured)};assertEquals(before,conservation())
        rejected {captureCentralUserRootDigest(db,session,accepted.snapshot.userMessageId)}
        db.testChatSql.execSQL("UPDATE message_parts SET payload='null' WHERE message_id=? AND kind=?",
            arrayOf(accepted.snapshot.userMessageId,ProactiveReplyAcceptance.PART_KIND))
        rejected {captureCentralUserRootDigest(db,session,accepted.snapshot.userMessageId)}
    }

    @Test fun selfConsistentProofAndTextTamperStillFailsCommittedRootAtContextReads()=work {
        val notice=notice();val accepted=checkNotNull(accept(source(notice.messageId),ProactiveReplyAcceptance.INLINE,null))
        val user=repository().getMessage(session,accepted.snapshot.userMessageId)!!
        val proof=ProactiveReplyAcceptance.decode(user.parts.single {it.kind==ProactiveReplyAcceptance.PART_KIND}.payload)
        val changed="tampered but self-consistent"
        db.testChatSql.execSQL("UPDATE message_parts SET payload=? WHERE message_id=? AND kind='text'",arrayOf(changed,user.messageId))
        db.testChatSql.execSQL("UPDATE message_parts SET payload=? WHERE message_id=? AND kind=?",arrayOf(
            ProactiveReplyAcceptance.encode(proof.copy(textSha256=ProactiveReplyAcceptance.textDigest(changed))),user.messageId,ProactiveReplyAcceptance.PART_KIND))
        rejected {captureCentralUserRootDigest(db,session,user.messageId)}
        rejected {repository().readCompactedProactiveReferent(session,user.messageId,Long.MAX_VALUE)}
        rejected {ConversationReader(db).readCompactedProactiveReferent(session,user.messageId)}
    }

    @Test fun coherentNoticeAndProofMutationCannotReplacePinnedContextWithoutStoredRoot()=work {
        val notice=notice();val accepted=checkNotNull(accept(source(notice.messageId),ProactiveReplyAcceptance.INLINE,null))
        val user=repository().getMessage(session,accepted.snapshot.userMessageId)!!
        val proof=ProactiveReplyAcceptance.decode(user.parts.single {it.kind==ProactiveReplyAcceptance.PART_KIND}.payload)
        db.testChatSql.execSQL("UPDATE message_parts SET payload='changed external notice' WHERE message_id=? AND kind='text'",arrayOf(notice.messageId))
        val changed=source(notice.messageId)
        val coherent=proof.copy(sourceFingerprint=changed.sourceFingerprint,referentFingerprint=changed.sourceFingerprint)
        db.testChatSql.execSQL("UPDATE message_parts SET payload=? WHERE message_id=? AND kind=?",
            arrayOf(ProactiveReplyAcceptance.encode(coherent),user.messageId,ProactiveReplyAcceptance.PART_KIND))
        assertEquals(coherent,db.withChatReadTransaction {ProactiveReplySourceValidation.validateUser(db,session,user.messageId)})
        rejected {db.withChatReadTransaction {ProactiveReplySourceValidation.validateContext(db,session,user.messageId)}}
        rejected {captureCentralUserRootDigest(db,session,user.messageId)}
        rejected {repository().readCompactedProactiveReferent(session,user.messageId,Long.MAX_VALUE)}
        rejected {ConversationReader(db).readCompactedProactiveReferent(session,user.messageId)}
    }

    @Test fun currentV100OrdinaryPreparedStartedAndConsumedRowsRetainExactSchemaAndBytes()=work {
        val oldNotice=notice("v100-consumed")
        repository().consumeSuggestedReplies(session,oldNotice.messageId)
        val writer=ConversationModelTurnWriter(db,repository(),ModelTranscriptRepository(db))
        suspend fun ordinary(id:String)=writer.appendPreparedCentralSubmission(AppendMessageInput(session,"user",
            listOf(MessagePartInput("text","v100 $id 😀"),MessagePartInput(CentralSubmissionRequest.PART_KIND,
                CentralSubmissionRequest.encode(listOf("existing-skill")))),stableId=id),id,"historical-process")
        val prepared=ordinary("old-prepared");val startedInput=ordinary("old-started")
        val started=db.withChatWriteTransaction {CentralTurnSubmissionRepository(db).claimPreparedInTransaction(startedInput,
            "historical-process","historical-run",7,setOf(startedInput.submissionId))}
        repository().appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","retained threaded 😀")),proactiveThreadKey=thread))
        fun schema():List<List<String?>> = db.testChatSql.query("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name").use {c ->
            buildList {while(c.moveToNext())add((0 until c.columnCount).map {if(c.isNull(it))null else c.getString(it)})}
        }
        fun rows():Map<String,List<List<String>>> {
            val tables=db.testChatSql.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use {c ->buildList {while(c.moveToNext())add(c.getString(0))}}
            fun quoted(name:String)="\""+name.replace("\"","\"\"")+"\""
            return tables.associateWith {table ->
                val columns=db.testChatSql.query("PRAGMA table_info("+quoted(table)+")").use {c ->buildList {while(c.moveToNext())add(c.getString(1))}}
                db.testChatSql.query("SELECT "+columns.joinToString(","){"typeof("+quoted(it)+"),hex("+quoted(it)+")"}+" FROM "+quoted(table)).use {c ->
                    buildList {while(c.moveToNext())add((0 until c.columnCount).map {c.getString(it) ?: "NULL"})}.sortedBy {it.joinToString("|")}
                }
            }
        }
        val schemaBefore=schema();val rowsBefore=rows()
        for(row in listOf(prepared,started))assertEquals(row.sourceSha256,captureCentralUserRootDigest(db,session,row.userMessageId))
        assertNull(accept(source(oldNotice.messageId)))
        JarvysChatDatabaseProvider.closeDatabaseFileForTests(db);open()
        assertEquals(schemaBefore,schema());assertEquals(rowsBefore,rows())
        assertEquals(prepared,db.withChatReadTransaction {CentralTurnSubmissionRepository(db).find(session,prepared.submissionId)})
        assertEquals(started,db.withChatReadTransaction {CentralTurnSubmissionRepository(db).find(session,started.submissionId)})
        assertEquals(0L,scalar("SELECT COUNT(*) FROM pragma_foreign_key_check"))
    }

    @Test fun pinnedPriorNoticeThreadCannotFallBackToEarlierNotice()=work {
        notice("earlier")
        val notice=notice("pinned");val first=checkNotNull(accept(source(notice.messageId),ProactiveReplyAcceptance.INLINE,null))
        val second=checkNotNull(accept(source(first.snapshot.userMessageId),ProactiveReplyAcceptance.INLINE,null))
        db.testChatSql.execSQL("UPDATE messages SET proactive_thread_key='other-thread' WHERE message_id=?",arrayOf(notice.messageId))
        rejected {captureCentralUserRootDigest(db,session,second.snapshot.userMessageId)}
        rejected {repository().readCompactedProactiveReferent(session,second.snapshot.userMessageId,Long.MAX_VALUE)}
    }

    @Test fun sourceUserFingerprintDoesNotRecursivelyInheritEarlierAuthority()=work {
        val notice=notice();val first=checkNotNull(accept(source(notice.messageId),ProactiveReplyAcceptance.INLINE,null))
        val second=checkNotNull(accept(source(first.snapshot.userMessageId),ProactiveReplyAcceptance.INLINE,null))
        db.testChatSql.execSQL("UPDATE message_parts SET payload='corrupt ancestor acceptance' WHERE message_id=? AND kind=?",
            arrayOf(first.snapshot.userMessageId,ProactiveReplyAcceptance.PART_KIND))
        rejected {captureCentralUserRootDigest(db,session,first.snapshot.userMessageId)}
        assertEquals(second.snapshot.sourceSha256,captureCentralUserRootDigest(db,session,second.snapshot.userMessageId))
    }

    @Test fun concurrentSameAndDifferentOptionsHaveOneCommittedAcceptance()=work {
        for(conflict in listOf(false,true)) {
            val notice=notice("race-$conflict","race-thread-$conflict");val captured=source(notice.messageId)
            val pool=Executors.newFixedThreadPool(2)
            try {
                val start=java.util.concurrent.CountDownLatch(1)
                val results=(0..1).map {index ->pool.submit<ProactiveReplyWriteResult?> {
                    start.await();runCatching {runBlocking {accept(captured,ordinal=if(conflict)index else 0)}}.getOrNull()
                }}
                start.countDown();val completed=results.map {it.get(30,TimeUnit.SECONDS)}
                assertEquals(1,completed.count {it?.created==true})
                assertEquals(if(conflict)1 else 2,completed.count {it!=null})
            } finally {pool.shutdownNow()}
        }
        assertEquals(2L,scalar("SELECT COUNT(*) FROM central_turn_submissions"))
    }

    @Test fun canonicalCodecRejectsDuplicateUnknownNullAndCoercedFieldsAndConservesUtf16() {
        val generation=java.util.UUID.randomUUID().toString();val thread="a\uD800b\uDC00😀e\u0301"
        val id=ProactiveReplyAcceptance.actionId(generation,session,thread,"source",ProactiveReplyAcceptance.INLINE)
        val value=ProactiveReplyAcceptanceValue(generation,session,ProactiveReplyAcceptance.INLINE,id,"source","a".repeat(64),thread,null,null,
            ProactiveReplyAcceptance.textDigest("x\uD800😀"),null,null)
        val payload=ProactiveReplyAcceptance.encode(value);assertEquals(value,ProactiveReplyAcceptance.decode(payload))
        for(bad in listOf(payload.replaceFirst("{","{\"version\":1,"),payload.replaceFirst("{","{\"extra\":1,"),
            payload.replace("\"version\":1","\"version\":null"),payload.replace("\"version\":1","\"version\":1.0"),payload+" ")) {
            assertThrows(RuntimeException::class.java) {ProactiveReplyAcceptance.decode(bad)}
        }
        assertNotEquals(ProactiveReplyAcceptance.textDigest("\uD800"),ProactiveReplyAcceptance.textDigest("\uD801"))
        assertNotEquals(ProactiveReplyAcceptance.digestFields("ab","c"),ProactiveReplyAcceptance.digestFields("a","bc"))
        assertNotEquals(ProactiveReplyAcceptance.digestFields(null),ProactiveReplyAcceptance.digestFields(""))
    }
}
