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

/** OFF-safe denial/conservation; does not manufacture a confirmed runtime receipt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],shadows=[ArtifactOsShadow::class])
class OrdinaryInterruptionWriterCompatibilityTest {
    @get:Rule val chat=ChatTestStore(registerApplicationFilesDirectory=true)
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val session=WorkspaceIdentity.CENTRAL_SESSION
    @Before fun setup(){FreshStorageBootstrap.ensure(context)}
    @Test fun ordinaryPartialStillResolvesPendingUser()=runBlocking {
        val db=JarvysChatDatabaseProvider.getInstance(context);val repo=ConversationRepository(db)
        val user=(repo.appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","Ordinary pending USER")))) as ChatDbResult.Success).value
        val result=repo.appendMessage(AppendMessageInput(session,"assistant",listOf(MessagePartInput("text","Ordinary partial")),status="PARTIAL",runId="ordinary-run",ownerUserMessageId=user.messageId))
        assertTrue(result is ChatDbResult.Success)
        assertEquals("resolved",ChatDatabaseBridge.getInstance(context).readMessageSync(session,user.messageId)!!.inputState)
    }
    @Test fun absentRuntimeProofCannotUseInterruptionWriterOrMutatePendingSource() {
        val db=JarvysChatDatabaseProvider.getInstance(context);val repo=ConversationRepository(db)
        val user=runBlocking {(repo.appendMessage(AppendMessageInput(session,"user",listOf(MessagePartInput("text","Never granted interruption")))) as ChatDbResult.Success).value}
        val input=AppendMessageInput(session,"assistant",listOf(MessagePartInput("text","Not authority")),status="PARTIAL",runId="unclaimed",ownerUserMessageId=user.messageId)
        val method=ConversationRepository::class.java.declaredMethods.single {it.name.startsWith("appendOrdinaryInterruption")}
        val failure=runCatching {method.invoke(repo,input,null,null,null,null)}.exceptionOrNull()
        assertNotNull(failure)
        val rows=ChatDatabaseBridge.getInstance(context).readConversationMessagesSync(session)
        assertEquals(listOf(user),rows)
        assertNull(rows.single().inputState)
    }
}
