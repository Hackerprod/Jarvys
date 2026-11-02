package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import java.util.concurrent.atomic.AtomicReference

/** Actual native Send, sealed sockets, confirmed checkpoints and claimed successors; no constructed permit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=TreeNativeTestApplication::class,shadows=[ArtifactOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class OriginalUserSuspensionAuthorityTest {
    private val base=CentralAvailabilityBoundaryAcceptanceTest()
    @get:Rule val rules=base.rules
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val session=WorkspaceIdentity.CENTRAL_SESSION
    private lateinit var storage:ChatTestStore
    private lateinit var service:AgentForegroundService
    private lateinit var workspace:String
    private lateinit var profile:String
    private var manager:CrewManager?=null
    private val observed=java.util.Collections.synchronizedList(mutableListOf<Pair<String,String>>())
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
    private fun assignments()=storage.onDatabaseWorker {WorkspaceRegistry(context).use {it.listAssignments(workspace)}}
    @Before fun setup() {
        base.setup();storage=get("storage");service=get("service")
        storage.onDatabaseWorker {
            profile=CrewProfileRepository(context).create(CrewProfile(CrewProfileRepository.newCustomId(),1,"Analyst","General purpose analysis","Use exact evidence",emptyList(),emptyList(),CrewProfile.WorkspaceMode.LEGACY_CHAT),emptyList(),emptyList()).id
            val scope=WorkspaceRegistry(context).use {it.createWorkspace(UUID.randomUUID().toString(),"Retained original delegation")}
            workspace=scope.workspaceId
            com.jarvys.agent.chatdb.ChatDatabaseBridge.getInstance(context).ensureConversationSync(scope.executionSessionId,"workspace")
            val childProvider=object:ModelProviderClient {
                override fun complete(systemPrompt:String,userPrompt:String,images:List<com.jarvys.agent.device.ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply=error("Conversation required")
                override fun completeConversation(systemPrompt:String,history:List<ConversationTurn>,userPrompt:String,images:List<com.jarvys.agent.device.ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply {
                    token.throwIfCancelled();return ModelReply("Canonical native child evidence",emptyList())
                }
            }
            val runtime=CoreAgentRuntime(context,scope.executionSessionId,emptyList(),CorePromptBudget.standard())
            manager=runtime.configureCrewManager(runtime.createTools(),CoreAgentModel(context,childProvider,scope.executionSessionId),null)
        }
        val actual=service.mainChatRunner
        service.mainChatRunner=AgentForegroundService.MainChatRunner {runtime,goal,history,attachments,token,progress ->
            val claim=CoreAgentRuntime::class.java.getDeclaredField("suspensionClaim").apply {isAccessible=true}.get(runtime) as CentralTreeAdmission.SuspensionClaim?
            if(claim!=null)observed.add(goal to claim.originalOperationRequest(token))
            actual.run(runtime,goal,history,attachments,token,progress)
        }
    }
    @After fun cleanup() {base.cleanup();manager?.close()}
    private fun script(delegateCall:Int) {
        field("socketReply").set(base,{index:Int ->
            val message=JSONObject().put("role","assistant")
            if(index==delegateCall) {
                val args=JSONObject().put("action","delegate").put("workspace_id",workspace).put("key","retained-original-work").put("role",profile)
                    .put("mission","Analyze the exact original request").put("tools",JSONArray()).put("continue_result",false)
                message.put("content","").put("tool_calls",JSONArray().put(JSONObject().put("id","retained-delegate").put("type","function")
                    .put("function",JSONObject().put("name","workspaces").put("arguments",args.toString()))))
            } else message.put("content","Native answer $index")
            JSONObject().put("choices",JSONArray().put(JSONObject().put("message",message))).toString()
        })
    }
    private fun finish(original:String,before:TreeRoot,request:String) {
        until("Original native successor actually delegates and completes") {
            assignments().size==1 && storage.onDatabaseWorker {LocalRunStore(context).readCentralSubmissions(session).single {it.userMessageId==original}.state}=="COMPLETED"
        }
        service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS)
        assertTrue(observed.isNotEmpty());assertTrue(observed.all {it.first.isEmpty() && it.second==request})
        val after=root(original)
        assertEquals(before.rootId,after.rootId);assertEquals(before.origin,after.origin);assertEquals(before.windowEpoch,after.windowEpoch)
        assertEquals(before.requests+1,after.requests)
        val yields=get<List<MainChatTranscriptStore.ConfirmedCentralYield>>("yields")
        assertEquals(before.executions+1+yields.size,after.executions) // Each successor and child retain their real charge.
        assertTrue(yields.isNotEmpty())
        yields.forEach {assertEquals(CentralOperationLineage.USER,it.operationLineage()!!.kind);assertEquals(before.rootId,it.rootId())}
        assertFalse(storage.onDatabaseWorker {LocalRunStore(context).readModelTranscriptRows(session).any {it.toString().contains("Workspace request could not complete")}})
    }
    @Test fun originalUserDelegatesAfterSuspensionWithEmptyReplayAndExactOperationSource() {
        script(3);val request="Analyze my task using workspace $workspace"
        val original=send(request);until("Original socket entered") {get<CountDownLatch>("firstEntered").count==0L}
        val before=root(original);send("Independent newer request")
        finish(original,before,request)
        assertEquals("Old inference remains physically blocked",1L,get<CountDownLatch>("releaseFirst").count)
    }
    @Test fun repeatedSuspensionConservesOriginalDelegationWithoutResettingRootBudget() {
        field("holdThird").setBoolean(base,true);script(5)
        val request="Repeatedly suspended original workspace $workspace"
        val original=send(request);until("Original socket entered") {get<CountDownLatch>("firstEntered").count==0L}
        val before=root(original);send("First independent newer request")
        until("First successor sealed socket entered") {get<CountDownLatch>("thirdEntered").count==0L}
        send("Second independent newer request");finish(original,before,request)
        assertEquals(2,get<List<MainChatTranscriptStore.ConfirmedCentralYield>>("yields").size)
        assertEquals(1L,get<CountDownLatch>("releaseFirst").count);assertEquals(1L,get<CountDownLatch>("releaseThird").count)
    }
    @Test fun freshExplicitPreparedRecoveryRetainsOriginalDelegationButDoesNotReplayUser() {
        script(3)
        val selected=call("parkedWithoutLivePermission",false) as AgentRunUiEvent
        val before=root(selected.messageId)
        assertTrue(get<AttachmentDraftViewModel>("drafts").recoverPreparedSuspension(selected))
        finish(selected.messageId,before,"Original durable suspension request")
        assertEquals(2,storage.onDatabaseWorker {LocalRunStore(context).readConversationMessages(session).count {it.optString("role")=="user"}})
    }
}
