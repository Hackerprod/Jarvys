package com.jarvys.agent.chatdb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ArtifactOsShadow
import com.jarvys.agent.CentralAvailabilityPolicy
import com.jarvys.agent.ChatTestStore
import com.jarvys.agent.FreshStorageBootstrap
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Enabled-only native operation bootstrap; ordinary read/open never creates storage-generation evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],shadows=[ArtifactOsShadow::class])
class LegacyRegenerationGenerationTest {
    @get:Rule val chat=ChatTestStore(registerApplicationFilesDirectory=true)
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val session=WorkspaceIdentity.CENTRAL_SESSION
    @Before fun setup() {check(CentralAvailabilityPolicy.admissionEnabled());FreshStorageBootstrap.ensure(context)}
    private fun generation(db:JarvysChatDatabase):String?=db.testChatSql.query("SELECT value FROM storage_meta WHERE key=?",arrayOf(ConnectionProjectionRepository.GENERATION_KEY)).use {if(it.moveToFirst())it.getString(0)else null}
    @Test fun readOnlyRetainedHistoryDoesNotInitializeGenerationButActualRegenerationDoes() {
        val db=JarvysChatDatabaseProvider.getInstance(context)
        val (user,assistant)=runBlocking {val repo=ConversationRepository(db)
            val user=(repo.appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","Retained USER")))) as ChatDbResult.Success).value
            val assistant=(repo.appendMessage(AppendMessageInput(session,"assistant",listOf(MessagePartInput("text","Retained answer")),status="COMPLETED",ownerUserMessageId=user.messageId,runId="legacy-native-run")) as ChatDbResult.Success).value
            user to assistant
        }
        assertNull(generation(db))
        val bridge=ChatDatabaseBridge.getInstance(context)
        bridge.readConversationMessagesSync(session)
        bridge.readCentralAvailabilityUiEvidenceSync(session,listOf(assistant.messageId))
        assertNull(generation(db))
        val prompt=(bridge.regenerateIfLatestAssistantSync(session,assistant.messageId) as ChatDbResult.Success).value!!
        val receipt=requireNotNull(prompt.nativeRegenerationReceipt)
        assertEquals("RETAINED",receipt.sourceKind);assertEquals(generation(db),receipt.storageGeneration)
        assertEquals(user.messageId,receipt.userMessageId);assertEquals(user.parts,bridge.readMessageSync(session,user.messageId)!!.parts)
        assertTrue(runBlocking {db.withChatReadTransaction {CentralTurnSubmissionRepository(db).list(session).isEmpty()}})
        assertTrue(bridge.validateNativeRegenerationReceiptSync(receipt));NativeRegenerationReceipt.invalidate(receipt)
    }
    @Test fun missingPositiveGenerationNeverBecomesRetainedBootstrap() {
        val db=JarvysChatDatabaseProvider.getInstance(context)
        val assistant=runBlocking {val repo=ConversationRepository(db);val writer=ConversationModelTurnWriter(db,repo,ModelTranscriptRepository(db))
            val prepared=writer.appendPreparedCentralSubmission(AppendMessageInput(session,"user",listOf(MessagePartInput("text","Positive USER"),MessagePartInput(CentralSubmissionRequest.PART_KIND,CentralSubmissionRequest.encode(emptyList()))),stableId="positive"),"positive","fixture-process")
            val row=db.withChatWriteTransaction {CentralTurnSubmissionRepository(db).claimPreparedInTransaction(prepared,"fixture-process","positive-run",1,setOf("positive"))}
            val answer=(repo.appendMessage(AppendMessageInput(session,"assistant",listOf(MessagePartInput("text","Answer")),status="COMPLETED",ownerUserMessageId=row.userMessageId,runId=row.claimRunId)) as ChatDbResult.Success).value
            val terminal=org.json.JSONObject().put("version",1).put("kind","DIRECT").put("assistantId",answer.messageId).put("runId",row.claimRunId).put("originRunId",row.claimRunId).put("sourceSha256",row.sourceSha256).put("finalOwnerMessageId",row.userMessageId).put("chainDigest","a".repeat(64)).toString()
            db.testChatSql.execSQL("UPDATE central_turn_submissions SET state='COMPLETED',terminal_evidence=? WHERE conversation_id=? AND submission_id='positive'",arrayOf(terminal,session))
            answer
        }
        db.testChatSql.execSQL("DELETE FROM storage_meta WHERE key=?",arrayOf(ConnectionProjectionRepository.GENERATION_KEY))
        val bridge=ChatDatabaseBridge.getInstance(context)
        val before=bridge.readMessageSync(session,assistant.messageId)
        val source=bridge.readMessageSync(session,assistant.ownerUserMessageId!!)
        val result=bridge.regenerateIfLatestAssistantSync(session,assistant.messageId)
        assertTrue("Exact native regeneration returned $result; before=$before; original=$assistant; source=$source",result is ChatDbResult.Invalid)
        assertEquals("Queued or uncertain Central submission cannot be regenerated",(result as ChatDbResult.Invalid).reason)
        assertEquals(before,bridge.readMessageSync(session,assistant.messageId))
        assertEquals(source,bridge.readMessageSync(session,assistant.ownerUserMessageId!!))
        assertEquals(0L,db.testChatSql.query("SELECT COUNT(*) FROM conversation_items WHERE conversation_id=? AND item_kind='assistant_regenerated'",arrayOf(session)).use {it.moveToFirst();it.getLong(0)})
        assertNull(generation(db))
    }
    private fun assertUnsupportedConserved(status:String?,runId:String?,explicitOwner:Boolean=true) {
        val db=JarvysChatDatabaseProvider.getInstance(context)
        val assistant=runBlocking {val repo=ConversationRepository(db)
            val user=(repo.appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","Retained operation")))) as ChatDbResult.Success).value
            (repo.appendMessage(AppendMessageInput(session,"assistant",listOf(MessagePartInput("text","Retained outcome")),status=status,ownerUserMessageId=if(explicitOwner)user.messageId else null,runId=runId)) as ChatDbResult.Success).value
        }
        val beforeGeneration=generation(db)
        val bridge=ChatDatabaseBridge.getInstance(context)
        val before=bridge.readMessageSync(session,assistant.messageId)
        val markerCount=db.testChatSql.query("SELECT COUNT(*) FROM conversation_items WHERE conversation_id=? AND item_kind='assistant_regenerated'",arrayOf(session)).use {it.moveToFirst();it.getLong(0)}
        assertTrue(bridge.regenerateIfLatestAssistantSync(session,assistant.messageId) is ChatDbResult.Invalid)
        assertEquals(before,bridge.readMessageSync(session,assistant.messageId));assertEquals(beforeGeneration,generation(db))
        assertEquals(markerCount,db.testChatSql.query("SELECT COUNT(*) FROM conversation_items WHERE conversation_id=? AND item_kind='assistant_regenerated'",arrayOf(session)).use {it.moveToFirst();it.getLong(0)})
        assertTrue(runBlocking {db.withChatReadTransaction {CentralTurnSubmissionRepository(db).list(session).isEmpty()}})
    }

    private fun manualRetained(status:String?,runId:String?,explicitOwner:Boolean=true):NativeRegenerationReceipt {
        val db=JarvysChatDatabaseProvider.getInstance(context)
        val (user,assistant)=runBlocking {val repo=ConversationRepository(db)
            val user=(repo.appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","Exact retained USER")))) as ChatDbResult.Success).value
            val answer=(repo.appendMessage(AppendMessageInput(session,"assistant",listOf(MessagePartInput("text","Prior terminal outcome")),status=status,ownerUserMessageId=if(explicitOwner)user.messageId else null,runId=runId)) as ChatDbResult.Success).value
            user to answer
        }
        val bridge=ChatDatabaseBridge.getInstance(context)
        val prompt=(bridge.regenerateIfLatestAssistantSync(session,assistant.messageId) as ChatDbResult.Success).value!!
        val receipt=requireNotNull(prompt.nativeRegenerationReceipt)
        assertEquals(user.messageId,prompt.userMessageId);assertEquals(user.text,prompt.text)
        assertEquals("RETAINED",receipt.sourceKind);assertNull(prompt.resumeReceipt)
        assertEquals(user.parts,bridge.readMessageSync(session,user.messageId)!!.parts)
        assertEquals("assistant_regenerated",bridge.readMessageSync(session,assistant.messageId)!!.hiddenKind)
        assertTrue(bridge.validateNativeRegenerationReceiptSync(receipt))
        assertTrue(runBlocking {db.withChatReadTransaction {CentralTurnSubmissionRepository(db).list(session).isEmpty()}})
        assertNull((bridge.regenerateIfLatestAssistantSync(session,assistant.messageId) as ChatDbResult.Success).value)
        return receipt
    }
    @Test fun legacyUnspecifiedStatusIsPreservedWithoutInferringCompletion() {NativeRegenerationReceipt.invalidate(manualRetained(null,null,false))}
    @Test fun failedRetainedNativeManualActionKeepsExactSourceWithoutNewSubmission() {NativeRegenerationReceipt.invalidate(manualRetained("FAILED","failed-run"))}
    @Test fun stoppedRetainedNativeManualActionKeepsExactSourceWithoutNewSubmission() {NativeRegenerationReceipt.invalidate(manualRetained("STOPPED","stopped-run"))}
    @Test fun partialRetainedNativeManualActionKeepsExactSourceWithoutNewSubmission() {NativeRegenerationReceipt.invalidate(manualRetained("PARTIAL","partial-run"))}
    @Test fun missingOldRunDoesNotInventMemoryResumeOrRootAuthority() {NativeRegenerationReceipt.invalidate(manualRetained("COMPLETED",null))}
    @Test fun exactNativePositionalResolutionDoesNotInventExplicitOwnerOrMemoryResume() {NativeRegenerationReceipt.invalidate(manualRetained("COMPLETED",null,false))}
    @Test fun runningSourceDeniedBeforePresentationOrGenerationWrite() = assertUnsupportedConserved("RUNNING","active-run")
    @Test fun unknownSourceDeniedBeforePresentationOrGenerationWrite() = assertUnsupportedConserved("UNKNOWN","unknown-run")
    @Test fun retainedTargetStatusOwnerRunAndPositionAreBoundNotFallbackAuthority() {
        val receipt=manualRetained("FAILED",null)
        val db=JarvysChatDatabaseProvider.getInstance(context);val bridge=ChatDatabaseBridge.getInstance(context)
        db.testChatSql.execSQL("UPDATE messages SET status='STOPPED' WHERE conversation_id=? AND message_id=?",arrayOf(session,receipt.assistantMessageId))
        assertThrows(IllegalStateException::class.java) {bridge.validateNativeRegenerationReceiptSync(receipt)}
        db.testChatSql.execSQL("UPDATE messages SET status='FAILED',owner_user_message_id=NULL WHERE conversation_id=? AND message_id=?",arrayOf(session,receipt.assistantMessageId))
        assertThrows(IllegalStateException::class.java) {bridge.validateNativeRegenerationReceiptSync(receipt)}
        db.testChatSql.execSQL("UPDATE messages SET owner_user_message_id=?,run_id='changed-run' WHERE conversation_id=? AND message_id=?",arrayOf(receipt.userMessageId,session,receipt.assistantMessageId))
        assertThrows(IllegalStateException::class.java) {bridge.validateNativeRegenerationReceiptSync(receipt)}
        db.testChatSql.execSQL("UPDATE messages SET run_id=NULL WHERE conversation_id=? AND message_id=?",arrayOf(session,receipt.assistantMessageId))
        db.testChatSql.execSQL("UPDATE conversation_items SET sort_seq=sort_seq+100 WHERE conversation_id=? AND seq=(SELECT seq FROM messages WHERE conversation_id=? AND message_id=?)",arrayOf(session,session,receipt.assistantMessageId))
        assertThrows(IllegalStateException::class.java) {bridge.validateNativeRegenerationReceiptSync(receipt)}
        NativeRegenerationReceipt.invalidate(receipt)
    }
    @Test fun failedNativeMarkerCommitRollsBackGenerationAndPresentationTogether() {
        val db=JarvysChatDatabaseProvider.getInstance(context)
        val assistant=runBlocking {val repo=ConversationRepository(db)
            val user=(repo.appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","Retained USER")))) as ChatDbResult.Success).value
            (repo.appendMessage(AppendMessageInput(session,"assistant",listOf(MessagePartInput("text","Answer")),status="COMPLETED",ownerUserMessageId=user.messageId,runId="retained-run")) as ChatDbResult.Success).value
        }
        assertNull(generation(db))
        val failure=runCatching {runBlocking {db.withChatWriteTransaction {
            db.testChatSql.execSQL("CREATE TEMP TRIGGER reject_regeneration_marker BEFORE INSERT ON main.conversation_items WHEN NEW.item_kind='assistant_regenerated' BEGIN SELECT RAISE(ABORT,'synthetic regeneration marker failure'); END")
            try {ConversationRepository(db).regenerateIfLatestAssistant(session,assistant.messageId)}
            finally {db.testChatSql.execSQL("DROP TRIGGER IF EXISTS temp.reject_regeneration_marker")}
        }}}.exceptionOrNull()
        assertNotNull(failure);assertTrue(generateSequence(failure){it.cause}.any {it.message.orEmpty().contains("synthetic regeneration marker failure")})
        assertNull(generation(db));assertNull(ChatDatabaseBridge.getInstance(context).readMessageSync(session,assistant.messageId)!!.hiddenKind)
        assertEquals(0L,db.testChatSql.query("SELECT COUNT(*) FROM conversation_items WHERE conversation_id=? AND item_kind='assistant_regenerated'",arrayOf(session)).use {it.moveToFirst();it.getLong(0)})
    }
}
