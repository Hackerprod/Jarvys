package com.jarvys.agent.chatdb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ArtifactOsShadow
import com.jarvys.agent.ChatTestStore
import com.jarvys.agent.FreshStorageBootstrap
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** OFF-safe shared native source resolution only: never creates an execution receipt or bypasses admission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],shadows=[ArtifactOsShadow::class])
class NativeRegenerationSourceCompatibilityTest {
    @get:Rule val chat=ChatTestStore(registerApplicationFilesDirectory=true)
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val session=WorkspaceIdentity.CENTRAL_SESSION
    @Before fun setup() {FreshStorageBootstrap.ensure(context)}
    private fun pair(explicit:Boolean):Pair<StoredMessage,StoredMessage> = runBlocking {
        val repo=ConversationRepository(JarvysChatDatabaseProvider.getInstance(context))
        val user=(repo.appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","Historical USER")))) as ChatDbResult.Success).value
        val answer=(repo.appendMessage(AppendMessageInput(session,"assistant",listOf(MessagePartInput("text","Historical terminal")),status="FAILED",ownerUserMessageId=if(explicit)user.messageId else null)) as ChatDbResult.Success).value
        user to answer
    }
    @Test fun sourceInspectionIsReadOnlyAndNativeWriterKeepsExactExplicitOwner() {
        val (user,answer)=pair(true);val db=JarvysChatDatabaseProvider.getInstance(context)
        val before=db.testChatSql.query("SELECT COUNT(*) FROM storage_meta").use {it.moveToFirst();it.getLong(0)}
        assertEquals(user.messageId,NativeRegenerationSource.ownerId(db,session,answer.messageId))
        assertNotNull(NativeRegenerationSource.position(db,session,answer.messageId))
        assertEquals(before,db.testChatSql.query("SELECT COUNT(*) FROM storage_meta").use {it.moveToFirst();it.getLong(0)})
        val prompt=runBlocking {(ConversationRepository(db).regenerateIfLatestAssistant(session,answer.messageId) as ChatDbResult.Success).value!!}
        assertEquals(user.messageId,prompt.userMessageId);assertNull(prompt.nativeRegenerationReceipt)
        assertEquals(user.messageId,NativeRegenerationSource.ownerId(db,session,answer.messageId))
    }
    @Test fun positionalSelectionRemainsExactToTargetNotLatestUser() {
        val (user,answer)=pair(false);val db=JarvysChatDatabaseProvider.getInstance(context)
        runBlocking {ConversationRepository(db).appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","Later unrelated USER"))))}
        assertEquals(user.messageId,NativeRegenerationSource.ownerId(db,session,answer.messageId))
        val prompt=runBlocking {(ConversationRepository(db).regenerateIfLatestAssistant(session,answer.messageId) as ChatDbResult.Success).value!!}
        assertEquals(user.messageId,prompt.userMessageId);assertNull(prompt.resumeReceipt);assertNull(prompt.nativeRegenerationReceipt)
        assertNull(ChatDatabaseBridge.getInstance(context).readMessageSync(session,answer.messageId)!!.ownerUserMessageId)
    }
    @Test fun explicitMissingOwnerNeverFallsBackAndTerminalClassificationDoesNotGrantAuthority() {
        val (_,answer)=pair(true);val db=JarvysChatDatabaseProvider.getInstance(context)
        db.testChatSql.execSQL("UPDATE messages SET owner_user_message_id='missing-owner' WHERE conversation_id=? AND message_id=?",arrayOf(session,answer.messageId))
        assertNull(NativeRegenerationSource.ownerId(db,session,answer.messageId))
        assertTrue(NativeRegenerationSource.terminal(null));assertFalse(NativeRegenerationSource.terminal("RUNNING"));assertFalse(NativeRegenerationSource.terminal("unknown"))
        listOf("COMPLETED","FAILED","STOPPED","PARTIAL").forEach {assertTrue(NativeRegenerationSource.terminal(it))}
        assertTrue(runBlocking {db.withChatReadTransaction {CentralTurnSubmissionRepository(db).list(session).isEmpty()}})
    }
    private fun generation(db:JarvysChatDatabase):String?=db.testChatSql.query("SELECT value FROM storage_meta WHERE key=?",arrayOf(ConnectionProjectionRepository.GENERATION_KEY)).use {if(it.moveToFirst())it.getString(0)else null}
    @Test fun actualAvailabilityWriterPrimitiveConservesSourceAndReturnsNoPermission() {
        val (user,answer)=pair(false);val db=JarvysChatDatabaseProvider.getInstance(context)
        assertNull(generation(db))
        assertEquals(user.messageId,NativeRegenerationSource.ownerId(db,session,answer.messageId));assertNull(generation(db))
        val prompt=runBlocking {(ConversationRepository(db).regenerateAvailabilitySource(session,answer.messageId) as ChatDbResult.Success).value!!}
        assertNotNull(generation(db));assertEquals(user.messageId,prompt.userMessageId);assertNull(prompt.nativeRegenerationReceipt);assertNull(prompt.resumeReceipt)
        assertEquals("assistant_regenerated",ChatDatabaseBridge.getInstance(context).readMessageSync(session,answer.messageId)!!.hiddenKind)
        assertEquals(user.parts,ChatDatabaseBridge.getInstance(context).readMessageSync(session,user.messageId)!!.parts)
        assertTrue(runBlocking {db.withChatReadTransaction {CentralTurnSubmissionRepository(db).list(session).isEmpty()}})
    }
    @Test fun actualAvailabilityWriterPrimitiveRejectsRunningBeforeGenerationOrHide() {
        val (_,answer)=pair(true);val db=JarvysChatDatabaseProvider.getInstance(context)
        db.testChatSql.execSQL("UPDATE messages SET status='RUNNING' WHERE conversation_id=? AND message_id=?",arrayOf(session,answer.messageId))
        assertTrue(runBlocking {ConversationRepository(db).regenerateAvailabilitySource(session,answer.messageId)} is ChatDbResult.Invalid)
        assertNull(generation(db));assertNull(ChatDatabaseBridge.getInstance(context).readMessageSync(session,answer.messageId)!!.hiddenKind)
        assertEquals(0L,db.testChatSql.query("SELECT COUNT(*) FROM conversation_items WHERE conversation_id=? AND item_kind='assistant_regenerated'",arrayOf(session)).use {it.moveToFirst();it.getLong(0)})
    }
    @Test fun actualAvailabilityWriterPrimitiveRollsBackGenerationAndHideOnMarkerFailure() {
        val (_,answer)=pair(true);val db=JarvysChatDatabaseProvider.getInstance(context)
        assertNull(generation(db))
        val failure=runCatching {runBlocking {db.withChatWriteTransaction {
            db.testChatSql.execSQL("CREATE TEMP TRIGGER reject_regeneration_marker BEFORE INSERT ON main.conversation_items WHEN NEW.item_kind='assistant_regenerated' BEGIN SELECT RAISE(ABORT,'synthetic regeneration marker failure'); END")
            try {ConversationRepository(db).regenerateAvailabilitySource(session,answer.messageId)}
            finally {db.testChatSql.execSQL("DROP TRIGGER IF EXISTS temp.reject_regeneration_marker")}
        }}}.exceptionOrNull()
        assertNotNull(failure);assertTrue(generateSequence(failure){it.cause}.any {it.message.orEmpty().contains("synthetic regeneration marker failure")})
        assertNull(generation(db));assertNull(ChatDatabaseBridge.getInstance(context).readMessageSync(session,answer.messageId)!!.hiddenKind)
        assertEquals(0L,db.testChatSql.query("SELECT COUNT(*) FROM conversation_items WHERE conversation_id=? AND item_kind='assistant_regenerated'",arrayOf(session)).use {it.moveToFirst();it.getLong(0)})
    }
    private fun positiveGenerationDenial(replacement:String?) {
        val db=JarvysChatDatabaseProvider.getInstance(context)
        val pair=runBlocking {val repo=ConversationRepository(db)
            val prepared=ConversationModelTurnWriter(db,repo,ModelTranscriptRepository(db)).appendPreparedCentralSubmission(
                AppendMessageInput(session,"user",listOf(MessagePartInput("text","Exact positive source"),MessagePartInput(CentralSubmissionRequest.PART_KIND,CentralSubmissionRequest.encode(emptyList()))),stableId="positive-generation"),"positive-generation","fixture-process")
            val row=db.withChatWriteTransaction {CentralTurnSubmissionRepository(db).claimPreparedInTransaction(prepared,"fixture-process","positive-run",1,setOf("positive-generation"))}
            val answer=(repo.appendMessage(AppendMessageInput(session,"assistant",listOf(MessagePartInput("text","Canonical terminal")),status="COMPLETED",ownerUserMessageId=row.userMessageId,runId=row.claimRunId)) as ChatDbResult.Success).value
            val terminal=org.json.JSONObject().put("version",1).put("kind","DIRECT").put("assistantId",answer.messageId).put("runId",row.claimRunId).put("originRunId",row.claimRunId).put("sourceSha256",row.sourceSha256).put("finalOwnerMessageId",row.userMessageId).put("chainDigest","a".repeat(64)).toString()
            db.testChatSql.execSQL("UPDATE central_turn_submissions SET state='COMPLETED',terminal_evidence=? WHERE conversation_id=? AND submission_id=?",arrayOf(terminal,session,row.submissionId))
            check(CentralTurnSubmissionRepository(db).confirmedCompleted(CentralTurnSubmissionRepository(db).find(session,row.submissionId)!!))
            row to answer
        }
        val row=pair.first;val answer=pair.second
        val source=ChatDatabaseBridge.getInstance(context).readMessageSync(session,row.userMessageId)
        val beforeSeq=db.testChatSql.query("SELECT next_seq FROM conversations WHERE id=?",arrayOf(session)).use {it.moveToFirst();it.getLong(0)}
        if(replacement==null)db.testChatSql.execSQL("DELETE FROM storage_meta WHERE key=?",arrayOf(ConnectionProjectionRepository.GENERATION_KEY))
        else db.testChatSql.execSQL("UPDATE storage_meta SET value=? WHERE key=?",arrayOf(replacement,ConnectionProjectionRepository.GENERATION_KEY))
        assertFalse(CentralTurnSubmissionRepository(db).confirmedCompleted(CentralTurnSubmissionRepository(db).find(session,row.submissionId)!!))
        val result=runBlocking {ConversationRepository(db).regenerateAvailabilitySource(session,answer.messageId)}
        assertEquals(ChatDbResult.Invalid("Queued or uncertain Central submission cannot be regenerated"),result)
        assertEquals(replacement,generation(db))
        assertEquals(beforeSeq,db.testChatSql.query("SELECT next_seq FROM conversations WHERE id=?",arrayOf(session)).use {it.moveToFirst();it.getLong(0)})
        assertEquals(answer,ChatDatabaseBridge.getInstance(context).readMessageSync(session,answer.messageId))
        assertEquals(source,ChatDatabaseBridge.getInstance(context).readMessageSync(session,row.userMessageId))
        assertEquals(0L,db.testChatSql.query("SELECT COUNT(*) FROM conversation_items WHERE conversation_id=? AND item_kind='assistant_regenerated'",arrayOf(session)).use {it.moveToFirst();it.getLong(0)})
    }
    @Test fun missingPositiveGenerationCannotBootstrapOrHideInNativeWriterPrimitive()=positiveGenerationDenial(null)
    @Test fun replacedPositiveGenerationCannotBorrowHistoricalReceiptInNativeWriterPrimitive()=positiveGenerationDenial("another-destination-generation")

}
