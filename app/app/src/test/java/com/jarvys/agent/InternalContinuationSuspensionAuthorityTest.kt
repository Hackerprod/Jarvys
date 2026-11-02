package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
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

/** Paired negative: actual child result claims Central, then a genuine new Send suspends that owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=TreeNativeTestApplication::class,shadows=[ArtifactOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class InternalContinuationSuspensionAuthorityTest {
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
    @Test fun suspendedActualInternalContinuationKeepsOperationContextButCannotDelegate() {
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
        val selected=AtomicReference<JSONObject>()
        val operation=AtomicReference<String>()
        val denied=AtomicReference<String>()
        val initialP1=AtomicInteger()
        val resumedP1=AtomicInteger()
        val prior=service.mainChatRunner
        service.mainChatRunner=AgentForegroundService.MainChatRunner {runtime,goal,history,attachments,token,progress ->
            val claimed=CoreAgentRuntime::class.java.getDeclaredField("claimedContinuationInput").apply {isAccessible=true}.get(runtime) as ConversationInput?
            if(claimed!=null && WorkspaceContinuationDelivery.isProtocol(claimed.provenance))initialP1.incrementAndGet()
            val claim=CoreAgentRuntime::class.java.getDeclaredField("suspensionClaim").apply {isAccessible=true}.get(runtime) as CentralTreeAdmission.SuspensionClaim?
            if(claim!=null) {
                assertEquals("Suspension must not replay a new USER prompt","",goal)
                assertEquals(ConversationInput.Origin.AGENT,claim.confirmedYield().finalOwnerOrigin())
                operation.set(claim.originalOperationRequest(token))
            }
            prior.run(runtime,goal,history,attachments,token,progress).also {
                if(claim!=null) {
                    // Observe the actual bound owner after tool dispatch, never infer permission from a DTO.
                    claim.requireBound(token)
                    val authority=CoreAgentRuntime::class.java.getDeclaredField("centralTreeAdmission").apply {isAccessible=true}.get(runtime) as CentralTreeAdmission
                    try {authority.requireFreshDelegation(token);fail("Internal continuation gained original USER authority")}
                    catch(expected:IllegalStateException){denied.set(expected.message)}
                    resumedP1.incrementAndGet()
                }
            }
        }
        field("socketReply").set(base,{index:Int ->when(index) {
            1 -> reply("","initial-real-child",mapOf("action" to "delegate","workspace_id" to workspace.workspaceId,"key" to "native-internal-source","role" to profile.id,"mission" to "Produce exact internal evidence","tools" to emptyList<String>(),"continue_result" to true))
            2 -> reply("Waiting for my delegated evidence")
            3 -> reply("STALE internal proposal")
            4 -> reply("Fresh independent request completed")
            5 -> reply("","must-deny-internal-delegate",mapOf("action" to "delegate","workspace_id" to workspace.workspaceId,"key" to "forbidden-internal-renewal","role" to profile.id,"mission" to "This continuation must not gain delegation authority","tools" to emptyList<String>(),"continue_result" to false))
            6 -> reply("","exact-result-read",mapOf("action" to "read_result","workspace_id" to workspace.workspaceId,"result_id" to selected.get().getString("resultReferenceId"),"expected_sha256" to selected.get().getString("contentSha256")))
            else -> reply("Internal result synthesized without new delegation")
        }})
        val request="Delegate once and synthesize the exact returned evidence"
        val original=send(request)
        until("Original native owner is waiting with actual child") {childEntered.count==0L && !AgentForegroundService.hasActiveRun(session)}
        val before=root(original)
        val originalSubmission=storage.onDatabaseWorker {LocalRunStore(context).readCentralSubmissions(session).single {it.userMessageId==original}}
        val bot=manager!!.bots().single()
        fun record()=storage.onDatabaseWorker {requireNotNull(CrewCheckpointStore(context.filesDir).load(workspace.executionSessionId,bot.id)).metadata.getJSONObject(CentralContinuationRecord.FIELD)}
        releaseChild.countDown()
        until("Actual P1 result claims Central and enters held sealed inference") {get<CountDownLatch>("thirdEntered").count==0L}
        selected.set(record().getJSONObject("selectedResult"))
        assertEquals(1,initialP1.get())
        val fresh=send("Fresh request displaces only the internal inference")
        until("Fresh USER and resumed internal owner finish") {resumedP1.get()==1 && !AgentForegroundService.hasActiveRun(session)}
        assertEquals("Old sealed socket is still physically blocked",1L,get<CountDownLatch>("releaseThird").count)
        assertEquals(request,operation.get())
        assertEquals("Fresh native USER execution is required for delegation",denied.get())
        assertEquals(1,childCalls.get());assertEquals(7,get<AtomicInteger>("calls").get())
        assertEquals(originalSubmission,storage.onDatabaseWorker {LocalRunStore(context).readCentralSubmissions(session).single {it.userMessageId==original}})
        storage.onDatabaseWorker {WorkspaceRegistry(context).use {registry ->
            val assignments=registry.listAssignments(workspace.workspaceId)
            assertEquals("Denied internal delegation must not create another assignment",1,assignments.size)
            registry.treeRead {db -> db.rawQuery("SELECT COUNT(*) FROM bot_tree_root",emptyArray()).use {c ->assertTrue(c.moveToFirst());assertEquals(2,c.getInt(0))}}
        }}
        assertEquals(before.rootId,root(original).rootId)
        assertEquals(before.origin,root(original).origin)
        assertEquals(before.executions+2,root(original).executions)
        assertEquals(before.windowEpoch,root(original).windowEpoch)
        assertNotEquals(root(original).rootId,root(fresh).rootId)
        val transcript=storage.onDatabaseWorker {LocalRunStore(context).readModelTranscriptRows(session)}
        assertTrue("Actual delegate proposal received a retained tool result",transcript.any {it.toString().contains("must-deny-internal-delegate") && it.toString().contains("Workspace request could not complete")})
        assertFalse(storage.onDatabaseWorker {LocalRunStore(context).readConversationMessages(session)}.any {it.optString("content")=="STALE internal proposal"})
    }
}
