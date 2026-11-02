package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.crew.*
import com.jarvys.agent.workspaces.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Private UNRUN acceptance: exact ordinary AGENT report is data, never new tree authority. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=TreeNativeTestApplication::class,shadows=[ArtifactOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class OrdinaryAgentAvailabilityAcceptanceTest {
    private val base=CentralAvailabilityBoundaryAcceptanceTest()
    @get:Rule val rules=base.rules
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val session=WorkspaceIdentity.CENTRAL_SESSION
    private lateinit var storage:ChatTestStore
    private lateinit var service:AgentForegroundService
    private var manager:CrewManager?=null
    private val releaseChild=CountDownLatch(1)
    private fun field(name:String)=base.javaClass.getDeclaredField(name).apply {isAccessible=true}
    @Suppress("UNCHECKED_CAST") private fun <T> get(name:String):T=field(name).get(base) as T
    private fun call(name:String,vararg args:Any?):Any? {
        val method=base.javaClass.declaredMethods.single {it.name==name && it.parameterTypes.size==args.size}.apply {isAccessible=true}
        try{return method.invoke(base,*args)}catch(failure:java.lang.reflect.InvocationTargetException){throw AssertionError("Native fixture $name failed",failure.cause)}
    }
    private fun until(description:String,predicate:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        while(!predicate()) {
            get<AtomicReference<Throwable>>("failure").get()?.let {throw AssertionError(description,it)}
            call("pump");check(System.nanoTime()<end){description};Thread.sleep(10)
        }
    }
    private fun send(text:String)=call("send",text) as String
    private fun root(id:String)=call("root",id) as TreeRoot
    @Before fun setup() {base.setup();storage=get("storage");service=get("service")}
    @After fun cleanup() {releaseChild.countDown();base.cleanup();manager?.close()}
    private fun reply(text:String,callId:String?=null,args:Map<String,Any> = emptyMap()):String {
        val message=JSONObject().put("role","assistant").put("content",text)
        if(callId!=null)message.put("tool_calls",JSONArray().put(JSONObject().put("id",callId).put("type","function")
            .put("function",JSONObject().put("name",CentralWorkspaceTool.NAME).put("arguments",JSONObject(args).toString()))))
        return JSONObject().put("choices",JSONArray().put(JSONObject().put("message",message))).toString()
    }
    @Test fun nativeNonOptedResultYieldsToFreshUserWithoutChangingItsAck() {
        field("holdFirst").setBoolean(base,false)
        field("holdThird").setBoolean(base,true)
        val childEntered=CountDownLatch(1)
        val childCalls=AtomicInteger()
        val (workspace,profile)=storage.onDatabaseWorker {
            val workspace=WorkspaceRegistry(context).use {it.createWorkspace(UUID.randomUUID().toString(),"Internal continuation authority")}
            ChatDatabaseBridge.getInstance(context).ensureConversationSync(workspace.executionSessionId,"workspace")
            val profile=CrewProfileRepository(context).create(CrewProfile(CrewProfileRepository.newCustomId(),1,"Analyst","General purpose analysis","Use exact evidence",emptyList(),emptyList(),CrewProfile.WorkspaceMode.LEGACY_CHAT),emptyList(),emptyList())
            val childProvider=object:ModelProviderClient {
                override fun complete(systemPrompt:String,userPrompt:String,images:List<com.jarvys.agent.device.ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply=error("Conversation required")
                override fun completeConversation(systemPrompt:String,history:List<ConversationTurn>,userPrompt:String,images:List<com.jarvys.agent.device.ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply {
                    childCalls.incrementAndGet();childEntered.countDown();check(releaseChild.await(30,TimeUnit.SECONDS));token.throwIfCancelled()
                    return ModelReply("Canonical internal continuation result",emptyList())
                }
            }
            val runtime=CoreAgentRuntime(context,workspace.executionSessionId,emptyList(),CorePromptBudget.standard())
            manager=runtime.configureCrewManager(runtime.createTools(),CoreAgentModel(context,childProvider,workspace.executionSessionId),null)
            workspace to profile
        }
        val lateReturned=CountDownLatch(1)
        field("socketReply").set(base,{index:Int ->when(index) {
            1 -> reply("","ordinary-real-child",mapOf("action" to "delegate","workspace_id" to workspace.workspaceId,"key" to "ordinary-source","role" to profile.id,"mission" to "Produce exact ordinary evidence","tools" to emptyList<String>(),"continue_result" to false))
            2 -> reply("Initial delegation complete")
            3 -> {lateReturned.countDown();reply("LATE ORDINARY ANSWER","late-ordinary-effect",mapOf("action" to "delegate","workspace_id" to workspace.workspaceId,"key" to "must-not-dispatch","role" to profile.id,"mission" to "Late proposal must not execute","tools" to emptyList<String>()))}
            else -> reply("Fresh request completed with retained report as data")
        }})
        send("Delegate once without an automatic result continuation")
        until("Original native owner ended with actual child") {childEntered.count==0L && !AgentForegroundService.hasActiveRun(session)}
        releaseChild.countDown()
        until("Actual ordinary AGENT source reaches held Central inference") {get<CountDownLatch>("thirdEntered").count==0L}
        val report=storage.onDatabaseWorker {
            LocalRunStore(context).readConversationMessages(session).single {
                it.optString("role")=="agent" && it.optString("content").contains("Canonical internal continuation result")
            }
        }
        val reportId=report.getString("messageId")
        val canonical=storage.onDatabaseWorker {LocalRunStore(context).readConversationInput(session,reportId)}!!
        assertEquals(ConversationInput.Origin.AGENT,canonical.origin)
        assertFalse(WorkspaceContinuationDelivery.isProtocol(canonical.provenance))
        assertEquals("incorporated",storage.onDatabaseWorker {LocalRunStore(context).readConversationInputState(session,reportId)})
        assertTrue(storage.onDatabaseWorker {LocalRunStore(context).readPendingInternalConversationInputs(session)}.none {it.optString("messageId")==reportId})
        fun ackSequence()=storage.onDatabaseWorker { kotlinx.coroutines.runBlocking {
            val db=JarvysChatDatabaseProvider.getInstance(context)
            db.withChatReadTransaction {db.chatSql.query("SELECT input_state_seq FROM messages WHERE conversation_id=? AND message_id=?",arrayOf(session,reportId)).use {c ->check(c.moveToFirst());c.getLong(0)}}
        }}
        val originalAckSequence=ackSequence()
        val originalAck=storage.onDatabaseWorker {checkNotNull(ChatDatabaseBridge.getInstance(context).readMessageSync(session,reportId))}
        val fresh=send("Fresh request while ordinary worker report inference is silent")
        until("Fresh native USER completes while stale ordinary socket remains held") {
            storage.onDatabaseWorker {LocalRunStore(context).readCentralSubmissions(session).single {it.userMessageId==fresh}}.state=="COMPLETED"
        }
        until("Serial lane releases") {!AgentForegroundService.hasActiveRun(session)}
        assertEquals(1L,get<CountDownLatch>("releaseThird").count)
        val interrupted=storage.onDatabaseWorker {LocalRunStore(context).readConversationMessages(session)}.filter {
            it.optString("role")=="assistant" && it.optString("userMessageId")==reportId
        }
        assertEquals(1,interrupted.size);assertEquals("PARTIAL",interrupted.single().getString("status"))
        get<CountDownLatch>("releaseThird").countDown()
        assertTrue(get<CountDownLatch>("thirdResponseReturned").await(10,TimeUnit.SECONDS))
        assertEquals("Revoked transport must not parse its late body",1L,lateReturned.count)
        assertFalse(storage.onDatabaseWorker {LocalRunStore(context).readModelTranscriptRows(session)}.any {it.toString().contains("late-ordinary-effect")})
        assertFalse(storage.onDatabaseWorker {LocalRunStore(context).readConversationMessages(session)}.any {it.optString("content")=="LATE ORDINARY ANSWER"})
        assertEquals(4,get<AtomicInteger>("calls").get())
        assertEquals(1,childCalls.get())
        assertEquals("incorporated",storage.onDatabaseWorker {LocalRunStore(context).readConversationInputState(session,reportId)})
        val retained=storage.onDatabaseWorker {checkNotNull(ChatDatabaseBridge.getInstance(context).readMessageSync(session,reportId))}
        assertEquals(originalAck,retained)
        assertEquals("No second ACK or rollback to pending",originalAckSequence,ackSequence())
        assertTrue("Fresh USER receives same tagged report as retained data",get<List<String>>("requests").last().contains("Canonical internal continuation result"))
        assertTrue(storage.onDatabaseWorker {LocalRunStore(context).readPendingInternalConversationInputs(session)}.none {it.optString("messageId")==reportId})
    }
    @Test fun preAckOrdinaryInterruptionLeavesPendingDataUntilFreshUserIncorporatesIt() {
        field("holdFirst").setBoolean(base,false)
        field("holdThird").setBoolean(base,false)
        val childEntered=CountDownLatch(1)
        val childCalls=AtomicInteger()
        val (workspace,profile)=storage.onDatabaseWorker {
            val workspace=WorkspaceRegistry(context).use {it.createWorkspace(UUID.randomUUID().toString(),"Internal continuation authority")}
            ChatDatabaseBridge.getInstance(context).ensureConversationSync(workspace.executionSessionId,"workspace")
            val profile=CrewProfileRepository(context).create(CrewProfile(CrewProfileRepository.newCustomId(),1,"Analyst","General purpose analysis","Use exact evidence",emptyList(),emptyList(),CrewProfile.WorkspaceMode.LEGACY_CHAT),emptyList(),emptyList())
            val childProvider=object:ModelProviderClient {
                override fun complete(systemPrompt:String,userPrompt:String,images:List<com.jarvys.agent.device.ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply=error("Conversation required")
                override fun completeConversation(systemPrompt:String,history:List<ConversationTurn>,userPrompt:String,images:List<com.jarvys.agent.device.ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply {
                    childCalls.incrementAndGet();childEntered.countDown();check(releaseChild.await(30,TimeUnit.SECONDS));token.throwIfCancelled()
                    return ModelReply("Canonical internal continuation result",emptyList())
                }
            }
            val runtime=CoreAgentRuntime(context,workspace.executionSessionId,emptyList(),CorePromptBudget.standard())
            manager=runtime.configureCrewManager(runtime.createTools(),CoreAgentModel(context,childProvider,workspace.executionSessionId),null)
            workspace to profile
        }
        val ordinaryEntered=CountDownLatch(1);val releaseOrdinary=CountDownLatch(1)
        val freshEntered=CountDownLatch(1);val releaseFresh=CountDownLatch(1)
        val inputId=AtomicReference<String>()
        val ordinaryRuns=AtomicInteger()
        val original=service.mainChatRunner
        val freshText="Fresh USER after an ordinary pre-ACK interruption"
        service.mainChatRunner=AgentForegroundService.MainChatRunner {runtime,goal,history,attachments,token,progress ->
            if(goal.isEmpty()) {
                ordinaryRuns.incrementAndGet()
                val owner=CoreAgentRuntime::class.java.getDeclaredField("ordinaryRunOwner").apply {isAccessible=true}.get(runtime) as AgentForegroundService.OrdinaryRunOwner
                inputId.set(owner.inputMessageId);ordinaryEntered.countDown()
                check(releaseOrdinary.await(20,TimeUnit.SECONDS))
            } else if(goal==freshText) {
                freshEntered.countDown();check(releaseFresh.await(20,TimeUnit.SECONDS))
            }
            original.run(runtime,goal,history,attachments,token,progress)
        }
        field("socketReply").set(base,{index:Int ->when(index) {
            1 -> reply("","preack-real-child",mapOf("action" to "delegate","workspace_id" to workspace.workspaceId,"key" to "preack-ordinary-source","role" to profile.id,"mission" to "Produce exact ordinary evidence","tools" to emptyList<String>(),"continue_result" to false))
            2 -> reply("Initial delegation complete")
            else -> reply("Fresh USER received the pending report as data")
        }})
        try {
            send("Delegate once without a continuation and retain its report")
            until("Original parent completes while native child is held") {childEntered.count==0L && !AgentForegroundService.hasActiveRun(session)}
            releaseChild.countDown()
            until("Actual ordinary owner pauses before first loop/ACK") {ordinaryEntered.count==0L}
            val reportId=inputId.get()
            assertNull(storage.onDatabaseWorker {LocalRunStore(context).readConversationInputState(session,reportId)})
            val fresh=send(freshText)
            releaseOrdinary.countDown()
            until("Fresh native physical owner pauses before its own loop/ACK") {freshEntered.count==0L}
            val store=LocalRunStore(context)
            assertNull("Ordinary partial must not ACK or resolve the unincorporated report",storage.onDatabaseWorker {store.readConversationInputState(session,reportId)})
            assertFalse(storage.onDatabaseWorker {store.handledConversationMessageIds(session,listOf(reportId))}.contains(reportId))
            assertTrue(storage.onDatabaseWorker {store.readPendingInternalConversationInputs(session)}.any {it.optString("messageId")==reportId})
            val terminal=storage.onDatabaseWorker {store.readConversationMessages(session)}.filter {it.optString("role")=="assistant" && it.optString("userMessageId")==reportId}
            assertEquals(1,terminal.size);assertEquals("PARTIAL",terminal.single().getString("status"))
            assertEquals("No provider request for superseded pre-ACK owner",2,get<AtomicInteger>("calls").get())
            storage.onDatabaseWorker {service.runPendingInputRecoverySafely(session)} // Existing queue, not a new grant.
            releaseFresh.countDown()
            until("Fresh USER incorporates data and completes") {storage.onDatabaseWorker {store.readCentralSubmissions(session).single {it.userMessageId==fresh}}.state=="COMPLETED"}
            until("Queued old ordinary action rechecks handled and leaves") {!AgentForegroundService.hasActiveRun(session)}
            assertEquals("Only the actual initial ordinary owner ran",1,ordinaryRuns.get())
            assertEquals(3,get<AtomicInteger>("calls").get());assertEquals(1,childCalls.get())
            assertEquals("incorporated",storage.onDatabaseWorker {store.readConversationInputState(session,reportId)})
            assertTrue(storage.onDatabaseWorker {store.handledConversationMessageIds(session,listOf(reportId))}.contains(reportId))
            assertFalse(storage.onDatabaseWorker {store.readPendingInternalConversationInputs(session)}.any {it.optString("messageId")==reportId})
            assertTrue(get<List<String>>("requests").last().contains("Canonical internal continuation result"))
        } finally {releaseOrdinary.countDown();releaseFresh.countDown()}
    }
    @Test fun changedOrdinaryTextAndWrongTokenCannotRetainOwner()=changedOrdinarySourceDenied("text")
    @Test fun changedOrdinaryProvenanceCannotRetainOwner()=changedOrdinarySourceDenied("provenance")
    @Test fun changedOrdinaryRoleCannotRetainOwner()=changedOrdinarySourceDenied("role")
    private fun changedOrdinarySourceDenied(kind:String) {
        field("holdFirst").setBoolean(base,false)
        field("holdThird").setBoolean(base,false)
        val childEntered=CountDownLatch(1)
        val childCalls=AtomicInteger()
        val (workspace,profile)=storage.onDatabaseWorker {
            val workspace=WorkspaceRegistry(context).use {it.createWorkspace(UUID.randomUUID().toString(),"Internal continuation authority")}
            ChatDatabaseBridge.getInstance(context).ensureConversationSync(workspace.executionSessionId,"workspace")
            val profile=CrewProfileRepository(context).create(CrewProfile(CrewProfileRepository.newCustomId(),1,"Analyst","General purpose analysis","Use exact evidence",emptyList(),emptyList(),CrewProfile.WorkspaceMode.LEGACY_CHAT),emptyList(),emptyList())
            val childProvider=object:ModelProviderClient {
                override fun complete(systemPrompt:String,userPrompt:String,images:List<com.jarvys.agent.device.ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply=error("Conversation required")
                override fun completeConversation(systemPrompt:String,history:List<ConversationTurn>,userPrompt:String,images:List<com.jarvys.agent.device.ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply {
                    childCalls.incrementAndGet();childEntered.countDown();check(releaseChild.await(30,TimeUnit.SECONDS));token.throwIfCancelled()
                    return ModelReply("Canonical internal continuation result",emptyList())
                }
            }
            val runtime=CoreAgentRuntime(context,workspace.executionSessionId,emptyList(),CorePromptBudget.standard())
            manager=runtime.configureCrewManager(runtime.createTools(),CoreAgentModel(context,childProvider,workspace.executionSessionId),null)
            workspace to profile
        }
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val actual=AtomicReference<AgentForegroundService.OrdinaryRunOwner>()
        val actualToken=AtomicReference<CancellationToken>()
        val previous=service.mainChatRunner
        service.mainChatRunner=AgentForegroundService.MainChatRunner {runtime,goal,history,attachments,token,progress ->
            if(goal.isEmpty()) {
                actual.set(CoreAgentRuntime::class.java.getDeclaredField("ordinaryRunOwner").apply {isAccessible=true}.get(runtime) as AgentForegroundService.OrdinaryRunOwner)
                actualToken.set(token);entered.countDown();check(release.await(20,TimeUnit.SECONDS))
            }
            previous.run(runtime,goal,history,attachments,token,progress)
        }
        field("socketReply").set(base,{index:Int ->if(index==1)
            reply("","negative-real-child",mapOf("action" to "delegate","workspace_id" to workspace.workspaceId,"key" to "negative-ordinary-source","role" to profile.id,"mission" to "Produce exact ordinary evidence","tools" to emptyList<String>(),"continue_result" to false))
            else reply("Parent completed")
        })
        try {
            send("Retain an ordinary native report for exact source validation")
            until("Original parent completes with child held") {childEntered.count==0L && !AgentForegroundService.hasActiveRun(session)}
            releaseChild.countDown()
            until("Real admitted ordinary owner before first inference") {entered.count==0L}
            val owner=actual.get();val token=actualToken.get();val id=owner.inputMessageId
            storage.onDatabaseWorker {owner.revalidateSource(token)}
            try {owner.requireCurrent(CancellationToken.cancellable());fail("Another token retained ordinary authority")}
            catch(expected:IllegalStateException) {}
            storage.onDatabaseWorker {kotlinx.coroutines.runBlocking {
                val db=JarvysChatDatabaseProvider.getInstance(context)
                db.withChatWriteTransaction {
                    when(kind) {
                        "text" -> db.chatSql.execSQL("UPDATE message_parts SET payload=? WHERE conversation_id=? AND message_id=? AND kind='text'",arrayOf("Changed native report",session,id))
                        "role" -> db.chatSql.execSQL("UPDATE messages SET role='user' WHERE conversation_id=? AND message_id=?",arrayOf(session,id))
                        else -> {
                            val raw=db.chatSql.query("SELECT payload FROM message_parts WHERE conversation_id=? AND message_id=? AND kind=?",arrayOf(session,id,ConversationInput.PROVENANCE_PART_KIND)).use {c ->check(c.moveToFirst());c.getString(0)}
                            val changed=JSONObject(raw);changed.getJSONObject("provenance").put("messageId",UUID.randomUUID().toString())
                            db.chatSql.execSQL("UPDATE message_parts SET payload=? WHERE conversation_id=? AND message_id=? AND kind=?",arrayOf(changed.toString(),session,id,ConversationInput.PROVENANCE_PART_KIND))
                        }
                    }
                }
            }}
            var rejected=false
            try {storage.onDatabaseWorker {owner.revalidateSource(token)}}catch(expected:Exception){rejected=true}
            assertTrue("Canonical $kind change must invalidate the exact owner",rejected)
            assertEquals("No changed-source provider dispatch",2,get<AtomicInteger>("calls").get())
            assertNull(storage.onDatabaseWorker {LocalRunStore(context).readConversationInputState(session,id)})
        } finally {AgentForegroundService.stopRun(session);release.countDown()}
    }
}
