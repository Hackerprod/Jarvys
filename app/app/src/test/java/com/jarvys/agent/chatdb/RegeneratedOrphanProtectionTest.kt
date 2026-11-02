package com.jarvys.agent.chatdb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.*
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Native hide evidence affects protection accounting only, never grants regeneration or execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],shadows=[ArtifactOsShadow::class])
class RegeneratedOrphanProtectionTest {
    @get:Rule val chat=ChatTestStore(registerApplicationFilesDirectory=true)
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val session=WorkspaceIdentity.CENTRAL_SESSION
    @Before fun setup(){FreshStorageBootstrap.ensure(context)}
    private data class Marker(val assistantMessageId:String,val userMessageId:String,val hiddenSeq:Long)
    private fun seed(status:String?="FAILED"):Marker {
        val db=JarvysChatDatabaseProvider.getInstance(context)
        val assistant=runBlocking {
            val repo=ConversationRepository(db)
            check(repo.appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","Retained USER")))) is ChatDbResult.Success)
            (repo.appendMessage(AppendMessageInput(session,"assistant",listOf(MessagePartInput("text","Retained hidden response")),status=status)) as ChatDbResult.Success).value
        }
        assertTrue(ChatDatabaseBridge.getInstance(context).readInteractionProtectionSync(session).unsafeAnchor)
        // Exercise the real data writer with admission OFF too, not the gated live-action bridge.
        val prompt=runBlocking {
            (ConversationRepository(db).regenerateAvailabilitySource(session,assistant.messageId) as ChatDbResult.Success).value!!
        }
        assertNull(prompt.nativeRegenerationReceipt)
        assertNull(prompt.resumeReceipt)
        assertTrue(ChatDatabaseBridge.getInstance(context).readCentralSubmissionsSync(session).isEmpty())
        val hidden=ChatDatabaseBridge.getInstance(context).readMessageSync(session,assistant.messageId)!!
        return Marker(assistant.messageId,prompt.userMessageId,requireNotNull(hidden.hiddenSeq))
    }
    @Test fun exactNativeMarkerRetainsUncertainProtectedSourceAcrossReopenWithoutPermission() {
        val receipt=seed(null);val bridge=ChatDatabaseBridge.getInstance(context)
        val before=bridge.readMessageSync(session,receipt.assistantMessageId)!!
        val protection=bridge.readInteractionProtectionSync(session)
        assertFalse(protection.unsafeAnchor)
        assertTrue(protection.protectedSources.contains(ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE,session,receipt.assistantMessageId,"")))
        assertNull(before.ownerUserMessageId);assertNull(before.status)
        assertTrue(bridge.loadConversationContextSync(session).none {it.content==before.text})
        bridge.validateProtectedContextSync(session,bridge.loadConversationContextSync(session))
        ChatDatabaseBridge.closeInstanceForTests(context.filesDir)
        val reopened=ChatDatabaseBridge.getInstance(context)
        assertFalse(reopened.readInteractionProtectionSync(session).unsafeAnchor)
        assertEquals(before,reopened.readMessageSync(session,receipt.assistantMessageId))
        assertTrue(reopened.readCentralSubmissionsSync(session).isEmpty())
    }
    @Test fun unrelatedOrphanIsStillUnsafeAfterExactNativeRegeneration() {
        val receipt=seed();val db=JarvysChatDatabaseProvider.getInstance(context)
        runBlocking {ConversationRepository(db).appendMessage(AppendMessageInput(session,"assistant",listOf(MessagePartInput("text","Unproven orphan")),status="FAILED"))}
        assertTrue(ChatDatabaseBridge.getInstance(context).readInteractionProtectionSync(session).unsafeAnchor)
    }
    @Test fun changedMarkerKindKeyOrSequenceNeverReceivesSafeAccounting() {
        val receipt=seed();val db=JarvysChatDatabaseProvider.getInstance(context)
        val sql=db.testChatSql
        for(change in listOf("item_kind='assistant_deleted'","item_key='different-source'")) {
            sql.execSQL("UPDATE conversation_items SET $change WHERE conversation_id=? AND seq=?",arrayOf(session,receipt.hiddenSeq))
            assertTrue(ChatDatabaseBridge.getInstance(context).readInteractionProtectionSync(session).unsafeAnchor)
            sql.execSQL("UPDATE conversation_items SET item_kind='assistant_regenerated',item_key=? WHERE conversation_id=? AND seq=?",arrayOf(receipt.assistantMessageId,session,receipt.hiddenSeq))
        }
        sql.execSQL("UPDATE messages SET hidden_seq=seq WHERE conversation_id=? AND message_id=?",arrayOf(session,receipt.assistantMessageId))
        assertTrue(ChatDatabaseBridge.getInstance(context).readInteractionProtectionSync(session).unsafeAnchor)
    }
    @Test fun lostCanonicalUserOrNonterminalResponseRemainsUnsafe() {
        val receipt=seed();val sql=JarvysChatDatabaseProvider.getInstance(context).testChatSql
        sql.execSQL("UPDATE messages SET status='RUNNING' WHERE conversation_id=? AND message_id=?",arrayOf(session,receipt.assistantMessageId))
        assertTrue(ChatDatabaseBridge.getInstance(context).readInteractionProtectionSync(session).unsafeAnchor)
        sql.execSQL("UPDATE messages SET status='FAILED' WHERE conversation_id=? AND message_id=?",arrayOf(session,receipt.assistantMessageId))
        sql.execSQL("UPDATE messages SET hidden_kind='assistant_deleted' WHERE conversation_id=? AND message_id=?",arrayOf(session,receipt.userMessageId))
        assertTrue(ChatDatabaseBridge.getInstance(context).readInteractionProtectionSync(session).unsafeAnchor)
    }
}
