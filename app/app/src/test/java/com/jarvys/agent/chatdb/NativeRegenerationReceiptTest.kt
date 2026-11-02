package com.jarvys.agent.chatdb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ArtifactOsShadow
import com.jarvys.agent.ChatTestStore
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Storage fixture proves receipt issuance/revalidation, not synthetic service/ActiveRun authority. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],shadows=[ArtifactOsShadow::class])
class NativeRegenerationReceiptTest {
    @get:Rule val chat=ChatTestStore(registerApplicationFilesDirectory=true)
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val session=WorkspaceIdentity.CENTRAL_SESSION
    @Before fun bootstrap() {com.jarvys.agent.FreshStorageBootstrap.ensure(context)}
    private fun issue(id:String):NativeRegenerationReceipt {
        val db=JarvysChatDatabaseProvider.getInstance(context)
        val assistant=runBlocking {
            val repo=ConversationRepository(db)
            val writer=ConversationModelTurnWriter(db,repo,ModelTranscriptRepository(db))
            val prepared=writer.appendPreparedCentralSubmission(AppendMessageInput(session,"user",listOf(MessagePartInput("text",id),MessagePartInput(CentralSubmissionRequest.PART_KIND,CentralSubmissionRequest.encode(emptyList()))),stableId=id),id,"fixture-process")
            val row=db.withChatWriteTransaction {CentralTurnSubmissionRepository(db).claimPreparedInTransaction(prepared,"fixture-process","run-$id",1,setOf(id))}
            val answer=(repo.appendMessage(AppendMessageInput(session,"assistant",listOf(MessagePartInput("text","answer")),status="COMPLETED",ownerUserMessageId=row.userMessageId,runId=row.claimRunId)) as ChatDbResult.Success).value
            val terminal=org.json.JSONObject().put("version",1).put("kind","DIRECT").put("assistantId",answer.messageId).put("runId",row.claimRunId).put("originRunId",row.claimRunId).put("sourceSha256",row.sourceSha256).put("finalOwnerMessageId",row.userMessageId).put("chainDigest","a".repeat(64)).toString()
            db.testChatSql.execSQL("UPDATE central_turn_submissions SET state='COMPLETED',revision=revision+1,terminal_evidence=? WHERE conversation_id=? AND submission_id=?",arrayOf(terminal,session,id))
            answer
        }
        return (ChatDatabaseBridge.getInstance(context).regenerateIfLatestAssistantSync(session,assistant.messageId) as ChatDbResult.Success).value!!.nativeRegenerationReceipt!!
    }
    @Test fun onlyActualSuccessfulRegenerationIssuesLiveReceiptAndDuplicateCannotRemint() {
        val receipt=issue("one")
        val bridge=ChatDatabaseBridge.getInstance(context)
        assertSame(receipt,NativeRegenerationReceipt.lookupLive(receipt.opaqueId));assertTrue(bridge.validateNativeRegenerationReceiptSync(receipt))
        assertNull((bridge.regenerateIfLatestAssistantSync(session,receipt.assistantMessageId) as ChatDbResult.Success).value)
        assertNull(NativeRegenerationReceipt.lookupLive("arbitrary-serialized-id"))
        NativeRegenerationReceipt.invalidate(receipt)
        assertNull(NativeRegenerationReceipt.lookupLive(receipt.opaqueId))
        assertThrows(IllegalStateException::class.java) {bridge.validateNativeRegenerationReceiptSync(receipt)}
    }
    @Test fun laterActiveAssistantSupersedesExecutableReceiptWithoutChangingCompletedHistory() {
        val receipt=issue("superseded")
        val db=JarvysChatDatabaseProvider.getInstance(context)
        runBlocking {
            ConversationRepository(db).appendMessage(AppendMessageInput(session,"assistant",listOf(MessagePartInput("text","later active answer")),status="COMPLETED",ownerUserMessageId=receipt.userMessageId,runId="later-run"))
            assertTrue(db.withChatReadTransaction {CentralTurnSubmissionRepository(db).confirmedCompleted(CentralTurnSubmissionRepository(db).list(session).single())})
        }
        assertThrows(IllegalStateException::class.java) {ChatDatabaseBridge.getInstance(context).validateNativeRegenerationReceiptSync(receipt)}
        NativeRegenerationReceipt.invalidate(receipt)
    }
    @Test fun changedCanonicalSourceAndClosedDatabaseLifetimeCannotValidateLiveReceipt() {
        val receipt=issue("changed")
        val db=JarvysChatDatabaseProvider.getInstance(context)
        db.testChatSql.execSQL("UPDATE message_parts SET payload='mutated' WHERE conversation_id=? AND message_id=? AND kind='text'",arrayOf(session,receipt.userMessageId))
        assertThrows(IllegalStateException::class.java) {ChatDatabaseBridge.getInstance(context).validateNativeRegenerationReceiptSync(receipt)}
        NativeRegenerationReceipt.invalidate(receipt)
        val next=issue("closed-lifetime")
        JarvysChatDatabaseProvider.closeInstance()
        assertThrows(IllegalStateException::class.java) {ChatDatabaseBridge.getInstance(context).validateNativeRegenerationReceiptSync(next)}
        NativeRegenerationReceipt.invalidate(next)
    }
    @Test fun removedPositiveAdmissionCannotFallBackToRetainedHistory() {
        val receipt=issue("removed-positive")
        assertEquals("POSITIVE",receipt.sourceKind)
        val db=JarvysChatDatabaseProvider.getInstance(context)
        db.testChatSql.execSQL("DELETE FROM central_turn_submissions WHERE conversation_id=? AND user_message_id=?",arrayOf(session,receipt.userMessageId))
        assertThrows(IllegalStateException::class.java) {ChatDatabaseBridge.getInstance(context).validateNativeRegenerationReceiptSync(receipt)}
        assertEquals("POSITIVE",receipt.sourceKind)
        NativeRegenerationReceipt.invalidate(receipt)
    }

    private fun rejectChangedDestination(replacement:String?) {
        val receipt=issue("exact-destination")
        val db=JarvysChatDatabaseProvider.getInstance(context);val bridge=ChatDatabaseBridge.getInstance(context)
        val source=bridge.readMessageSync(session,receipt.userMessageId)
        val target=bridge.readMessageSync(session,receipt.assistantMessageId)
        val beforeSeq=db.testChatSql.query("SELECT next_seq FROM conversations WHERE id=?",arrayOf(session)).use {it.moveToFirst();it.getLong(0)}
        if(replacement==null)db.testChatSql.execSQL("DELETE FROM storage_meta WHERE key=?",arrayOf(ConnectionProjectionRepository.GENERATION_KEY))
        else db.testChatSql.execSQL("UPDATE storage_meta SET value=? WHERE key=?",arrayOf(replacement,ConnectionProjectionRepository.GENERATION_KEY))
        assertThrows(IllegalStateException::class.java) {bridge.validateNativeRegenerationReceiptSync(receipt)}
        assertEquals("POSITIVE",receipt.sourceKind)
        assertEquals(source,bridge.readMessageSync(session,receipt.userMessageId))
        assertEquals(target,bridge.readMessageSync(session,receipt.assistantMessageId))
        assertEquals(beforeSeq,db.testChatSql.query("SELECT next_seq FROM conversations WHERE id=?",arrayOf(session)).use {it.moveToFirst();it.getLong(0)})
        assertEquals(replacement,db.testChatSql.query("SELECT value FROM storage_meta WHERE key=?",arrayOf(ConnectionProjectionRepository.GENERATION_KEY)).use {if(it.moveToFirst())it.getString(0)else null})
        NativeRegenerationReceipt.invalidate(receipt)
    }
    @Test fun removedGenerationCannotValidateAlreadyIssuedPositiveReceipt()=rejectChangedDestination(null)
    @Test fun replacedGenerationCannotValidateAlreadyIssuedPositiveReceipt()=rejectChangedDestination("different-storage-destination")

}
