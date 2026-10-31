package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fresh reconstructed source tests; prior unavailable results are not evidence for these bytes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,shadows=[ArtifactOsShadow::class])
class CentralTreeAdmissionTest {
    @get:Rule val fixture=ChatTestStore()
    private val central=WorkspaceIdentity.CENTRAL_SESSION
    private inner class Setup {
        val files=fixture.newFilesDirectory()
        val store=fixture.localRunStore(files)
        val context=object:ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=files
        }
        val token=CancellationToken.cancellable()
        val id=store.appendConversationMessage(central,"user","Exact original USER 🧭")
        fun capture()=CentralTreeAdmission.captureUser(store,central,id,token)
        fun sql(statement:String,vararg args:Any?)=runBlocking {
            val db=JarvysChatDatabaseProvider.getInstance(context)
            db.withChatWriteTransaction { db.chatSql.execSQL(statement,args) }
        }
        fun incoming():CoreAgentLoop.IncomingMessage {
            val row=store.appendConversationMessage(central,"user","Exact incorporated steering")
            store.markConversationMessagesIncorporated(central,listOf(row))
            return CoreAgentLoop.IncomingMessage.mainChat(requireNotNull(store.readConversationInput(central,row)))
        }
    }
    private fun denied(block:()->Unit) {
        try {block();fail("Exact source must reject")}
        catch(expected:IllegalStateException){}
        catch(expected:IllegalArgumentException){}
        catch(expected:java.util.concurrent.CancellationException){}
    }
    @Test fun exactOriginalSourceSurvivesNewerUserWithoutAdoptingIt() {
        val f=Setup();val a=f.capture();f.store.appendConversationMessage(central,"user","Not a root selector")
        val b=f.capture();assertEquals(f.id,a.originUserMessageId());assertEquals(a.originDigest(),b.originDigest())
        a.revalidateSource(f.token);assertEquals("current",a.withCurrentWorkspaceTransaction(f.token){"current"})
        a.close();b.close()
    }
    @Test fun absentAssistantAndCrossOwnerCannotCreateSource() {
        val f=Setup();val assistant=f.store.appendConversationMessage(central,"assistant","not a USER")
        denied {CentralTreeAdmission.captureUser(f.store,central,"missing",f.token)}
        denied {CentralTreeAdmission.captureUser(f.store,central,assistant,f.token)}
        denied {CentralTreeAdmission.captureUser(f.store,"other",f.id,f.token)}
    }
    @Test fun hiddenUserCannotCreateSource() {
        val f=Setup();f.sql("UPDATE messages SET hidden_kind='fixture-hidden' WHERE conversation_id=? AND message_id=?",central,f.id)
        denied {f.capture()}
    }
    @Test fun serializedProvenanceCannotOverrideUserRole() {
        val f=Setup();f.sql("INSERT INTO message_parts(conversation_id,message_id,ordinal,kind,payload) VALUES(?,?,?,?,?)",
            central,f.id,99,ConversationInput.PROVENANCE_PART_KIND,"{\"origin\":\"USER\",\"provenance\":{}}")
        denied {f.capture()}
    }
    @Test fun sourceMutationRevokesRatherThanAdoptsDigest() {
        val f=Setup();val a=f.capture()
        f.sql("UPDATE message_parts SET payload='changed' WHERE conversation_id=? AND message_id=? AND kind='text'",central,f.id)
        denied {a.revalidateSource(f.token)};a.close()
    }
    @Test fun wrongTokenClosedAndCancelledCapabilitiesFail() {
        val f=Setup();val a=f.capture();denied {a.requireCurrent(CancellationToken.cancellable())};a.close();denied {a.requireCurrent(f.token)}
        val b=f.capture();f.token.cancel();denied {b.requireCurrent(f.token)};b.close()
    }
    @Test fun databaseReplacementRevokesOriginalLifecycle() {
        val f=Setup();val a=f.capture();ChatDatabaseBridge.closeInstanceForTests(f.files)
        denied {a.requireCurrent(f.token)};a.close()
    }
    private fun receipt(f:Setup,run:String):Pair<MainChatTranscriptStore,CoreAgentLoop> {
        val writer=MainChatTranscriptStore(f.files,central,f.store)
        val loop=CoreAgentLoop(CoreAgentLoop.Model { _,_,_,_ -> ModelReply("complete",emptyList()) },
            CoreToolRegistry(emptyList()),"",central,CorePromptBudget.standard(),null)
        writer.enableTreeExitReceipt();writer.attach(loop,emptyList(),f.id,run)
        loop.run("Exact original USER 🧭",emptyList(),emptyList(),f.token,null,f.id,run)
        return writer to loop
    }
    @Test fun exitReceiptRequiresExactConfirmedCheckpointAndRun() {
        val f=Setup();val (writer,loop)=receipt(f,"exact-run")
        val r=writer.confirmTreeExit(loop.checkpointSnapshot(),"exact-run")
        assertEquals(f.id,r.ownerMessageId);assertTrue(r.digest.matches(Regex("[a-f0-9]{64}")))
        denied {writer.confirmTreeExit(loop.checkpointSnapshot(),"other")}
        denied {writer.confirmTreeExit(CoreAgentLoop.Checkpoint.empty(),"exact-run")}
    }
    @Test fun failedPersistenceCannotMintExit() {
        val f=Setup();val writer=MainChatTranscriptStore(f.files,central,f.store)
        val loop=CoreAgentLoop(CoreAgentLoop.Model { _,_,_,_ -> ModelReply("complete",emptyList()) },CoreToolRegistry(emptyList()),"",central,CorePromptBudget.standard(),null)
        writer.enableTreeExitReceipt();writer.attach(loop,emptyList(),f.id,"failed")
        MainChatTranscriptStore.checkpointStagedHookForTests=java.util.function.Consumer {throw IllegalStateException("fixture failure")}
        try {
            denied {loop.run("Exact original USER 🧭",emptyList(),emptyList(),f.token,null,f.id,"failed")}
            denied {writer.confirmTreeExit(loop.checkpointSnapshot(),"failed")}
        } finally {MainChatTranscriptStore.checkpointStagedHookForTests=null}
    }
    @Test fun finalOwnerWrongThreadTokenOrUncommittedCheckpointCannotBind() {
        val f=Setup();val a=f.capture()
        denied {a.bindIncomingOwner(f.context,emptyList(),CancellationToken.cancellable())}
        val thread=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {assertTrue(thread.submit<Boolean> {
            try {a.bindIncomingOwner(f.context,emptyList(),f.token);false}catch(expected:IllegalStateException){true}
        }.get(5,java.util.concurrent.TimeUnit.SECONDS))}finally{thread.shutdownNow()}
        val incoming=f.incoming();a.bindIncomingOwner(f.context,listOf(incoming),f.token)
        val (writer,loop)=receipt(f,"omitted-owner")
        val r=writer.confirmTreeExit(loop.checkpointSnapshot(),"omitted-owner")
        assertFalse(r.committedIncomingIds.contains(incoming.id));denied {a.confirmExit(r,f.token)};a.close()
    }
    @Test fun canonicalIncomingGuardRejectsContentRoleAndProvenanceMutation() {
        for(kind in listOf("content","role","provenance")) {
            val f=Setup();val incoming=f.incoming();val bridge=ChatDatabaseBridge.getInstance(f.context)
            assertTrue(bridge.confirmCentralTreeIncomingSync(central,incoming).matches(Regex("[a-f0-9]{64}")))
            when(kind) {
                "content" -> f.sql("UPDATE message_parts SET payload='changed' WHERE conversation_id=? AND message_id=? AND kind='text'",central,incoming.id)
                "role" -> f.sql("UPDATE messages SET role='agent' WHERE conversation_id=? AND message_id=?",central,incoming.id)
                else -> f.sql("INSERT INTO message_parts(conversation_id,message_id,ordinal,kind,payload) VALUES(?,?,?,?,?)",central,incoming.id,99,ConversationInput.PROVENANCE_PART_KIND,"{\"origin\":\"USER\",\"provenance\":{}}")
            }
            denied {bridge.confirmCentralTreeIncomingSync(central,incoming)}
        }
    }
}
