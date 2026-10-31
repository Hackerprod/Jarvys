package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.crew.*
import com.jarvys.agent.device.ScreenData
import com.jarvys.agent.workspaces.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Fresh enabled-admission acceptance. Not selected before D3; no policy shadow or seeded authority. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=CentralFlowTestApplication::class,shadows=[ArtifactOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CentralTreeServiceAcceptanceTest {
    private val storage=ChatTestStore(runTestOnWorker=false,registerApplicationFilesDirectory=true)
    @get:Rule val rules:RuleChain=RuleChain.outerRule(storage).around(TestSecretStoreRule())
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val central=WorkspaceIdentity.CENTRAL_SESSION
    private var controller:org.robolectric.android.controller.ServiceController<AgentForegroundService>?=null
    private lateinit var service:AgentForegroundService
    private val actualRuntime=AtomicReference<CoreAgentRuntime>()
    private val failure=AtomicReference<Throwable>()
    private val calls=AtomicInteger()
    private var manager:CrewManager?=null
    private val releaseChild=CountDownLatch(1)
    @Volatile private var reply:(List<ConversationTurn>)->ModelReply={ModelReply("Completed",emptyList())}
    private fun provider(block:(List<ConversationTurn>)->ModelReply)=object:ModelProviderClient {
        override fun complete(systemPrompt:String,userPrompt:String,images:List<ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply=error("Conversation expected")
        override fun completeConversation(systemPrompt:String,history:List<ConversationTurn>,userPrompt:String,images:List<ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply=block(history)
    }
    @Before fun setup() {
        check(WorkspaceTreePolicy.admissionEnabled()) {"Requires separately approved post-D3 enabled candidate"}
        storage.localRunStore(context.filesDir)
        ProviderSettings(context).apply {setProvider(ProviderSettings.Provider.OPENROUTER);setOpenRouterModel("offline/tree-service")}
        check(context.getSharedPreferences("provider_context_metadata",0).edit().putInt("model_offline/tree-service",32768)
            .putLong("fetched_offline/tree-service",System.currentTimeMillis()).commit())
        storage.onDatabaseWorker {FreshStorageBootstrap.ensure(context);ChatDatabaseBridge.getInstance(context).ensureConversationSync(central,"chat")}
        AgentRunUiState.resetSession(central)
        controller=Robolectric.buildService(AgentForegroundService::class.java).create();service=controller!!.get()
        val scripted=provider {history -> calls.incrementAndGet();reply(history)}
        service.runtimeFactory=AgentForegroundService.RuntimeFactory {c,id,skills ->
            CoreAgentRuntime(c,id,skills,CorePromptBudget.standard(),scripted).also {actualRuntime.set(it)}
        }
        service.mainChatRunner=AgentForegroundService.MainChatRunner {runtime,goal,history,attachments,token,progress ->
            try {runtime.runMainChat(goal,history,attachments,token,progress)}
            catch(problem:Throwable){failure.set(problem);throw AssertionError("Actual Central runtime failed",problem)}
        }
    }
    @After fun cleanup() {
        releaseChild.countDown();StopController.stopAllRuns();StopController.getInstance().stopRun()
        controller?.let {runCatching {it.destroy()}};manager?.let {runCatching {it.close()}}
        context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
    }
    private fun user(text:String)=storage.onDatabaseWorker {LocalRunStore(context).appendConversationMessage(central,"user",text)}
    private fun runStored(id:String,goal:String="") {
        val method=AgentForegroundService::class.java.getDeclaredMethod("runRealAgent",CancellationToken::class.java,
            String::class.java,List::class.java,String::class.java,String::class.java,Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,String::class.java).apply {isAccessible=true}
        storage.onDatabaseWorker {
            val token=StopController.getInstance().beginRun()
            try {method.invoke(service,token,goal,emptyList<String>(),central,goal,true,false,id)}finally{token.completeRun()}
        }
    }
    private fun root(origin:String):TreeRoot=storage.onDatabaseWorker {WorkspaceRegistry(context).use {registry ->
        val id=registry.treeRead {db -> db.rawQuery("SELECT root_id FROM bot_tree_root WHERE origin_user_message_id=?",arrayOf(origin)).use {rows ->
            check(rows.moveToFirst());val id=rows.getString(0);check(!rows.moveToNext());id
        }}
        requireNotNull(WorkspaceTreeStore(registry).getRoot(id))
    }}
    private fun waitUntil(description:String,test:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
        while(!test()) {failure.get()?.let {throw AssertionError(description,it)};check(System.nanoTime()<end){description};Thread.sleep(20)}
    }
    private fun steer(text:String) {
        val id=storage.onDatabaseWorker {LocalRunStore(context).appendInterruptRequest(central,text,emptyList())}
        assertTrue(actualRuntime.get().mainChatSteeringProvider()!!.dispatchPendingRequest(id))
    }
    private fun nextWake(id:String):android.content.Intent {
        val shadow=org.robolectric.Shadows.shadowOf(context as android.app.Application)
        var found:android.content.Intent?=null
        waitUntil("Exact native service request") {
            var item=shadow.nextStartedService
            while(item!=null) {
                if(item.action==AgentForegroundService.ACTION_REAL_AGENT && item.getStringExtra("chat_user_message_id")==id && found==null)found=item
                item=shadow.nextStartedService
            }
            found!=null
        }
        return requireNotNull(found)
    }
    @Test fun realSteeringChangesFinalOwnerButNotOriginalRootOrCounters() {
        val original=user("Original root request");val observed=mutableListOf<TreeRoot>();var step=0
        reply={history -> observed.add(root(original));if(step++==0){steer("Exact steering");ModelReply("A1",emptyList())}
            else {assertTrue(history.any {it.role=="user" && it.content=="Exact steering"});ModelReply("A2",emptyList())}}
        runStored(original);assertEquals(2,step);assertEquals(observed[0],observed[1])
        val done=root(original);assertEquals(observed[0].origin,done.origin);assertEquals("COMPLETED",done.closeReason)
        assertEquals(observed[0].requests,done.requests);assertEquals(observed[0].executions,done.executions);assertEquals(observed[0].wakes,done.wakes)
        val rows=storage.onDatabaseWorker {LocalRunStore(context).readConversationMessages(central)}
        val steering=rows.single {it.optString("role")=="user" && it.optString("content")=="Exact steering"}.getString("messageId")
        val answers=rows.filter {it.optString("role")=="assistant"}
        assertEquals(listOf("A1","A2"),answers.map {it.getString("content")})
        assertEquals(original,answers[0].getString("userMessageId"));assertEquals(steering,answers[1].getString("userMessageId"))
    }
    @Test fun finalOwnerRejectsChangedCanonicalContent()=mutatedOwner("content")
    @Test fun finalOwnerRejectsChangedCanonicalRole()=mutatedOwner("role")
    @Test fun finalOwnerRejectsInjectedCanonicalProvenance()=mutatedOwner("provenance")
    private fun mutatedOwner(kind:String) {
        val original=user("Do not adopt corrupted steering");var step=0;var changed=false
        reply={if(step++==0){steer("Exact steering");ModelReply("A1",emptyList())}else{
            storage.onDatabaseWorker {
                val ledger=LocalRunStore(context)
                val id=ledger.readConversationMessages(central).single {it.optString("role")=="user" && it.optString("content")=="Exact steering"}.getString("messageId")
                val incoming=CoreAgentLoop.IncomingMessage.mainChat(requireNotNull(ledger.readConversationInput(central,id)))
                assertEquals("incorporated",ledger.readConversationInputState(central,id))
                val bridge=ChatDatabaseBridge.getInstance(context);assertTrue(bridge.confirmCentralTreeIncomingSync(central,incoming).length==64)
                runBlocking {val db=JarvysChatDatabaseProvider.getInstance(context);db.withChatWriteTransaction {
                    when(kind){
                        "content"->db.chatSql.execSQL("UPDATE message_parts SET payload='changed' WHERE conversation_id=? AND message_id=? AND kind='text'",arrayOf(central,id))
                        "role"->db.chatSql.execSQL("UPDATE messages SET role='agent' WHERE conversation_id=? AND message_id=?",arrayOf(central,id))
                        else->db.chatSql.execSQL("INSERT INTO message_parts(conversation_id,message_id,ordinal,kind,payload) VALUES(?,?,?,?,?)",arrayOf(central,id,99,ConversationInput.PROVENANCE_PART_KIND,"{\"origin\":\"USER\",\"provenance\":{}}"))
                    }
                }}
                assertThrows(IllegalStateException::class.java){bridge.confirmCentralTreeIncomingSync(central,incoming)};changed=true
            };ModelReply("A2",emptyList())}}
        runStored(original);assertEquals(2,step);assertTrue(changed)
        val retained=root(original);assertEquals(original,retained.origin.originUserMessageId);assertNull(retained.closeReason);assertFalse(retained.admissionsClosed)
    }
    @Test fun nativeRegenerationRemainsPlainChatWithoutNewRootAuthority() {
        val original=user("Original plain request");runStored(original);val before=root(original)
        val event=storage.onDatabaseWorker {val ledger=LocalRunStore(context);val assistant=ledger.readConversationMessages(central).single {it.optString("role")=="assistant"}.getString("messageId");requireNotNull(ledger.regenerateAssistantIfLatest(central,assistant))}
        val workspace=storage.onDatabaseWorker {WorkspaceRegistry(context).use {it.createWorkspace(UUID.randomUUID().toString(),"No regeneration grant")}}
        var regenerated=0
        reply={history -> if(regenerated++==0)ModelReply("",listOf(ModelReply.Call("denied-regeneration-delegate",CentralWorkspaceTool.NAME,
            mapOf("action" to "delegate","workspace_id" to workspace.workspaceId,"key" to "not-authorized","role" to "analyst","mission" to "Must not start","tools" to emptyList<String>()))))
            else {assertTrue(history.single {it.toolCallId=="denied-regeneration-delegate" && it.kind==ConversationTurn.Kind.TOOL_RESULT}.content.contains("Workspace request could not complete"));ModelReply("Regenerated answer",emptyList())}}
        AgentForegroundService.startRegenerateAssistant(context,event.text,arrayListOf(),central,event.userMessageId,event.resumeReceipt)
        service.onStartCommand(nextWake(original),0,1)
        waitUntil("Plain regenerated completion") {storage.onDatabaseWorker {LocalRunStore(context).readConversationMessages(central).any {it.optString("role")=="assistant" && it.optString("content")=="Regenerated answer" && it.optString("status")=="COMPLETED"}}}
        assertEquals(before,root(original));assertEquals(3,calls.get());assertEquals(2,regenerated)
        storage.onDatabaseWorker {WorkspaceRegistry(context).use {assertTrue(it.listAssignments(workspace.workspaceId).isEmpty())}}
    }
    @Test fun sameOriginalUserNewRuntimeCannotRenewClosedRootOrInvokeProvider() {
        val original=user("Retained exact request");runStored(original)
        val before=root(original);val invoked=calls.get()
        reply={throw AssertionError("No new provider/tree authority for repeated origin")}
        runStored(original)
        assertEquals(invoked,calls.get());assertEquals(before,root(original))
    }
    @Test fun ordinaryAgentAndLatestUserFallbackCannotCreateTree() {
        user("Unrelated latest USER")
        val workspace=storage.onDatabaseWorker {WorkspaceRegistry(context).use {it.createWorkspace(UUID.randomUUID().toString(),"No inferred root")}}
        val input=storage.onDatabaseWorker {
            val id=UUID.randomUUID().toString()
            LocalRunStore(context).appendConversationInput(ConversationInput(central,id,"Ordinary agent update",ConversationInput.Origin.AGENT,
                ConversationInput.Provenance("worker","","","",id,ConversationInput.Kind.MESSAGE)))
        }
        for(mode in listOf("agent","fallback")) {
            var step=0;val call="denied-$mode"
            reply={history -> if(step++==0)ModelReply("",listOf(ModelReply.Call(call,CentralWorkspaceTool.NAME,
                mapOf("action" to "delegate","workspace_id" to workspace.workspaceId,"key" to "no-root-$mode","role" to "analyst","mission" to "Must not run","tools" to emptyList<String>()))))
                else {val result=history.single {it.toolCallId==call && it.kind==ConversationTurn.Kind.TOOL_RESULT}.content
                    if(mode=="agent")assertEquals("Tool error: Central invocation source is absent, hidden or does not match the committed call",result)
                    else assertTrue(result,result.contains("Workspace request could not complete"))
                    ModelReply("No inferred permission",emptyList())}}
            if(mode=="agent")runStored(input)else runStored("","Legacy fallback request")
            assertEquals(2,step)
            storage.onDatabaseWorker {WorkspaceRegistry(context).use {registry ->
                assertEquals(1,registry.schemaVersion());assertTrue(registry.listAssignments(workspace.workspaceId).isEmpty())
            }}
        }
    }
    @Test fun actualGeneralistResultReturnsThroughP1SerialContinuationOnSameRoot()=generalistResult(false)
    @Test fun resultCannotClaimCentralUntilExactTreeSettlementAndReportCommit()=generalistResult(true)
    private fun generalistResult(barrier:Boolean) {
        val treeEntered=CountDownLatch(1);val releaseTree=CountDownLatch(1)
        val entered=CountDownLatch(1);val childCalls=AtomicInteger()
        val (scope,profile)=storage.onDatabaseWorker {
            val scope=WorkspaceRegistry(context).use {it.createWorkspace(UUID.randomUUID().toString(),"Generalist workspace")}
            ChatDatabaseBridge.getInstance(context).ensureConversationSync(scope.executionSessionId,"workspace")
            val profile=CrewProfileRepository(context).create(CrewProfile(CrewProfileRepository.newCustomId(),1,"Generalist","General-purpose planning","Use retained evidence",emptyList(),emptyList(),CrewProfile.WorkspaceMode.LEGACY_CHAT),emptyList(),emptyList())
            val runtime=CoreAgentRuntime(context,scope.executionSessionId,emptyList(),CorePromptBudget.standard())
            manager=runtime.configureCrewManager(runtime.createTools(),CoreAgentModel(context,provider {childCalls.incrementAndGet();entered.countDown();check(releaseChild.await(20,TimeUnit.SECONDS));ModelReply("Native itinerary evidence",emptyList())},scope.executionSessionId),null)
            if(barrier) {
                val field=CrewManager::class.java.getDeclaredField("treePublicationListener").apply {isAccessible=true}
                val originalListener=field.get(manager!!) as CrewManager.TreePublicationListener
                field.set(manager!!,object:CrewManager.TreePublicationListener {
                    override fun onTreeResult(bot:CrewManager.Bot,evidence:com.jarvys.agent.crew.CrewResultEvidence,lease:CrewManager.TreePublicationLease) {
                        treeEntered.countDown();check(releaseTree.await(20,TimeUnit.SECONDS))
                        originalListener.onTreeResult(bot,evidence,lease)
                    }
                    override fun onTreeQuestion(bot:CrewManager.Bot,evidence:com.jarvys.agent.crew.CrewCoordinationEvidence,lease:CrewManager.TreePublicationLease)=originalListener.onTreeQuestion(bot,evidence,lease)
                })
            }
            scope to profile
        }
        val original=user("Plan a general itinerary");var step=0
        reply={history -> if(step++==0)ModelReply("",listOf(ModelReply.Call("native-delegate",CentralWorkspaceTool.NAME,mapOf("action" to "delegate","workspace_id" to scope.workspaceId,"key" to "native-generalist","role" to profile.id,"mission" to "Plan itinerary","tools" to emptyList<String>(),"continue_result" to true))))
            else {assertEquals("workspace_data",JSONObject(history.single {it.toolCallId=="native-delegate" && it.kind==ConversationTurn.Kind.TOOL_RESULT}.content).getString("kind"));ModelReply("Awaiting generalist",emptyList())}}
        runStored(original);assertTrue(entered.await(10,TimeUnit.SECONDS));val before=root(original)
        storage.onDatabaseWorker {WorkspaceRegistry(context).use {assertEquals(TreeNodeState.WAITING,WorkspaceTreeStore(it).getNode(before.rootId,before.rootId)!!.state)}}
        val bot=manager!!.bots().single();assertEquals(CrewProfile.WorkspaceMode.LEGACY_CHAT,bot.role.workspaceMode)
        fun record()=storage.onDatabaseWorker {requireNotNull(CrewCheckpointStore(context.filesDir).load(scope.executionSessionId,bot.id)).metadata.getJSONObject(CentralContinuationRecord.FIELD)}
        releaseChild.countDown()
        if(barrier) try {
            assertTrue(treeEntered.await(20,TimeUnit.SECONDS))
            storage.onDatabaseWorker {WorkspaceRegistry(context).use {db ->
                val node=WorkspaceTreeStore(db).findAssignment(scope.workspaceId,bot.assignmentId())!!
                assertNull(node.reportReceipt);assertNotEquals(TreeNodeState.TERMINAL,node.state)
                assertNotNull(LocalRunStore(context).readCrewResultEvidence(scope.executionSessionId,bot.resultEvidence()!!.evidenceId))
            };WorkspaceResultDelivery(context).reconcile(scope.workspaceId,CancellationToken.uncancellable())}
            assertEquals("PREPARED",record().getString("dispatchState"))
            val stablePending=record().getString("inputStableId")
            val pending=storage.onDatabaseWorker {LocalRunStore(context).readConversationMessageByStableId(central,stablePending)!!.getString("messageId")}
            val providerCount=calls.get();runStored(pending)
            assertEquals(providerCount,calls.get());assertEquals("PREPARED",record().getString("dispatchState"))
            assertEquals(before.executions,root(original).executions)
        } finally {releaseTree.countDown()}
        waitUntil("Native DONE"){bot.isQuiescent};assertEquals(CrewManager.Status.DONE,bot.status())
        waitUntil("Durable selected P1 result"){record().optString("dispatchState")=="PREPARED"}
        val selected=record().getJSONObject("selectedResult");val stable=record().getString("inputStableId")
        val input=storage.onDatabaseWorker {LocalRunStore(context).readConversationMessageByStableId(central,stable)!!.getString("messageId")}
        var synthesis=0
        reply={history -> if(synthesis++==0)ModelReply("",listOf(ModelReply.Call("exact-read",CentralWorkspaceTool.NAME,mapOf("action" to "read_result","workspace_id" to scope.workspaceId,"result_id" to selected.getString("resultReferenceId"),"expected_sha256" to selected.getString("contentSha256")))))
            else {assertTrue(history.single {it.toolCallId=="exact-read" && it.kind==ConversationTurn.Kind.TOOL_RESULT}.content.contains("Native itinerary evidence"));ModelReply("Exact synthesis",emptyList())}}
        service.onStartCommand(nextWake(input),0,2);waitUntil("Original root completion"){root(original).closeReason=="COMPLETED"}
        assertEquals(before.origin,root(original).origin);assertEquals(2,synthesis);assertEquals(1,childCalls.get());assertEquals("ACKNOWLEDGED",record().getString("dispatchState"))
        val count=calls.get();runStored(input);assertEquals(count,calls.get())
    }
    /** Insert in existing CentralTreeServiceAcceptanceTest; uses its real service, manager and offline providers. */
    @Test fun exactCancelStopsOnlyRealNativeTargetAndKeepsSiblingResultEvidence() {
        val entered=CountDownLatch(2)
        val (scope,profile)=storage.onDatabaseWorker {
            val scope=WorkspaceRegistry(context).use { it.createWorkspace(UUID.randomUUID().toString(),"Native scoped cancellation") }
            ChatDatabaseBridge.getInstance(context).ensureConversationSync(scope.executionSessionId,"workspace")
            val profile=CrewProfileRepository(context).create(CrewProfile(CrewProfileRepository.newCustomId(),1,"Generalist","General purpose",
                "Keep scope exact",emptyList(),emptyList(),CrewProfile.WorkspaceMode.LEGACY_CHAT),emptyList(),emptyList())
            val runtime=CoreAgentRuntime(context,scope.executionSessionId,emptyList(),CorePromptBudget.standard())
            val childProvider=object:ModelProviderClient {
                override fun complete(systemPrompt:String,userPrompt:String,images:List<ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply=error("Conversation required")
                override fun completeConversation(systemPrompt:String,history:List<ConversationTurn>,userPrompt:String,images:List<ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply {
                    entered.countDown()
                    while(!releaseChild.await(20,TimeUnit.MILLISECONDS)) token.throwIfCancelled()
                    token.throwIfCancelled();return ModelReply("Retained native sibling result",emptyList())
                }
            }
            manager=runtime.configureCrewManager(runtime.createTools(),CoreAgentModel(context,childProvider,scope.executionSessionId),null)
            scope to profile
        }
        var step=0;var target:TreeNode?=null;var sibling:TreeNode?=null
        val original=user("Cancel only the first of these owned workers")
        reply={ history ->
            when(step++) {
                0,1 -> {
                    val index=step
                    ModelReply("",listOf(ModelReply.Call("native-child-$index",CentralWorkspaceTool.NAME,mapOf("action" to "delegate",
                        "workspace_id" to scope.workspaceId,"key" to "cancel-child-$index","role" to profile.id,
                        "mission" to "Retain exact result $index","tools" to emptyList<String>(),"continue_result" to true))))
                }
                2 -> {
                    assertTrue(entered.await(10,TimeUnit.SECONDS))
                    storage.onDatabaseWorker { WorkspaceRegistry(context).use { db ->
                        val tree=WorkspaceTreeStore(db);val children=tree.children(root(original).rootId,root(original).rootId)
                        assertEquals(2,children.size)
                        target=children.first();sibling=children.last()
                    } }
                    val node=target!!
                    ModelReply("",listOf(ModelReply.Call("native-exact-cancel",CentralWorkspaceTool.NAME,mapOf("action" to "cancel",
                        "workspace_id" to scope.workspaceId,"assignment_id" to node.assignmentId!!,"bot_id" to node.botId!!,
                        "expected_assignment_revision" to node.assignmentRevision,"key" to "cancel-only-first"))))
                }
                else -> {
                    assertEquals("workspace_data",JSONObject(history.single { it.toolCallId=="native-exact-cancel" && it.kind==ConversationTurn.Kind.TOOL_RESULT }.content).getString("kind"))
                    ModelReply("Only the exact target was cancelled; sibling remains owned",emptyList())
                }
            }
        }
        runStored(original)
        val stopped=manager!!.bot(target!!.botId)!!;val running=manager!!.bot(sibling!!.botId)!!
        waitUntil("Exact native target stopped") { stopped.isQuiescent }
        assertTrue(stopped.token.isCancellationRequested);assertFalse(running.token.isCancellationRequested)
        storage.onDatabaseWorker { WorkspaceRegistry(context).use { db ->
            val tree=WorkspaceTreeStore(db)
            assertTrue(tree.getNode(target!!.rootId,target!!.nodeId)!!.admissionsClosed)
            assertFalse(tree.getNode(sibling!!.rootId,sibling!!.nodeId)!!.admissionsClosed)
        } }
        releaseChild.countDown();waitUntil("Sibling result persists") { running.isQuiescent }
        val result=running.resultEvidence()!!
        assertEquals("Retained native sibling result",result.resultText)
        storage.onDatabaseWorker {
            assertEquals(result.contentSha256,LocalRunStore(context).readCrewResultEvidence(scope.executionSessionId,result.evidenceId)!!.contentSha256)
        }
    }

}
