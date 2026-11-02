package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.workspaces.WorkspaceIdentity
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Codec evidence only: actual canonical Room reader output never mints a continuation permission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=TreeNativeTestApplication::class,shadows=[ArtifactOsShadow::class])
class NativeAssistantYieldCheckpointTest {
    private val storage=ChatTestStore(runTestOnWorker=false,registerApplicationFilesDirectory=true)
    @get:Rule val rules:RuleChain=RuleChain.outerRule(storage).around(TestSecretStoreRule())
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val session=WorkspaceIdentity.CENTRAL_SESSION
    private lateinit var inputId:String
    private lateinit var assistantId:String
    private lateinit var checkpoint:CoreAgentLoop.Checkpoint
    @Before fun setup() {
        storage.localRunStore(context.filesDir)
        storage.onDatabaseWorker {
            FreshStorageBootstrap.ensure(context)
            (context as TreeNativeTestApplication).connectionAccess().initializeAsync().get()
            ChatDatabaseBridge.getInstance(context).ensureConversationSync(session,"chat")
            val store=LocalRunStore(context)
            inputId=store.appendConversationMessage(session,"user","Exact native source USER")
            assistantId=store.appendConversationMessage(session,"assistant","Confirmed ordinary assistant",null,"native-codec-run",inputId,"COMPLETED")
            checkpoint=CoreAgentLoop.Checkpoint(store.loadConversationContext(session),emptySet(),emptyMap(),-1)
        }
    }
    private fun encoded()=CentralYieldCheckpointCodec.encode(checkpoint)
    private fun turn(value:JSONObject,role:String):JSONObject {
        val turns=value.getJSONArray("transcript")
        return (0 until turns.length()).map {turns.getJSONObject(it)}.single {it.getString("role")==role}
    }
    @Test fun actualRoomAssistantRowSourceDoesNotBecomeAnInputAcknowledgementIdentity() {
        val before=checkpoint.transcript.single {it.role=="assistant"}
        assertEquals("",before.durableMessageId)
        assertEquals(ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE,session,assistantId,""),before.source())
        val restored=CentralYieldCheckpointCodec.decode(encoded())
        val assistant=restored.transcript.single {it.role=="assistant"}
        val user=restored.transcript.single {it.role=="user"}
        assertEquals("",assistant.durableMessageId)
        assertEquals(before.source(),assistant.source())
        assertEquals(before.content,assistant.content)
        assertEquals(before.originalMessageIndex,assistant.originalMessageIndex)
        assertNull(assistant.incomingMetadata)
        assertEquals(inputId,user.durableMessageId)
        assertEquals(inputId,user.source()!!.sourceId)
        assertEquals(checkpoint.appliedIncomingIds,restored.appliedIncomingIds)
        assertEquals(CentralYieldCheckpointCodec.canonical(encoded()),CentralYieldCheckpointCodec.canonical(CentralYieldCheckpointCodec.encode(restored)))
    }
    @Test fun nonemptyAssistantInputIdCannotPointAtADifferentCanonicalRow() {
        val value=encoded();turn(value,"assistant").put("durableMessageId",inputId)
        assertThrows(IllegalArgumentException::class.java) {CentralYieldCheckpointCodec.decode(value)}
    }
    @Test fun sourcedUserStillRequiresItsExactNonemptyInputId() {
        val value=encoded();turn(value,"user").put("durableMessageId","")
        assertThrows(IllegalArgumentException::class.java) {CentralYieldCheckpointCodec.decode(value)}
    }
    @Test fun ordinaryAssistantCannotChangeCanonicalSourceKind() {
        val value=encoded();turn(value,"assistant").getJSONObject("source").put("kind","BATCH")
        assertThrows(IllegalArgumentException::class.java) {CentralYieldCheckpointCodec.decode(value)}
    }
    @Test fun internalAssistantCannotUseTheOrdinaryAssistantEmptyInputException() {
        val source=ConversationInput.Provenance("workspace-source","workspace","assignment","bot","message",
            ConversationInput.Kind.FINDING)
        val internal=ConversationTurn.internalInput("Retained internal data",-1,
            ConversationTurn.IncomingMetadata.forInternalInput(source),"message")
        internal.bindSource(ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE,session,"message",""))
        val value=CentralYieldCheckpointCodec.encode(CoreAgentLoop.Checkpoint(listOf(internal),setOf("message"),emptyMap(),-1))
        turn(value,"assistant").put("durableMessageId","")
        assertThrows(IllegalArgumentException::class.java) {CentralYieldCheckpointCodec.decode(value)}
    }
}
