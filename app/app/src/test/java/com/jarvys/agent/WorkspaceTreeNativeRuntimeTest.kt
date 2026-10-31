package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.crew.*
import com.jarvys.agent.device.ScreenData
import com.jarvys.agent.workspaces.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real saved generalist/runtime/Registry worker path. Root is explicitly a backend fixture,
 * not fabricated Central USER or invocation authority; Central origin admission has separate tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=TreeNativeTestApplication::class,shadows=[ArtifactOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class WorkspaceTreeNativeRuntimeTest {
    private val storage=ChatTestStore(runTestOnWorker=false,registerApplicationFilesDirectory=true)
    @get:Rule val rules:RuleChain=RuleChain.outerRule(storage).around(TestSecretStoreRule())
    private val app get()=ApplicationProvider.getApplicationContext<Context>()
    private val pool=Executors.newCachedThreadPool()
    private val managers=mutableListOf<CrewManager>()
    private val caps=listOf("delegate_subtask","ask_chief","msg_send","report_done","board_read","board_post")
    private lateinit var profileId:String
    private lateinit var liveManager:CrewManager
    @Before fun setup() {
        storage.localRunStore(app.filesDir)
        ProviderSettings(app).apply { setProvider(ProviderSettings.Provider.OPENROUTER);setOpenRouterModel("offline/tree-native") }
        check(app.getSharedPreferences("provider_context_metadata",0).edit().putInt("model_offline/tree-native",32768)
            .putLong("fetched_offline/tree-native",System.currentTimeMillis()).commit())
        storage.onDatabaseWorker {
            FreshStorageBootstrap.ensure(app)
            (app as TreeNativeTestApplication).connectionAccess().initializeAsync().get()
            check((app as TreeNativeTestApplication).connectionAccess().isReady())
            ChatDatabaseBridge.getInstance(app).ensureConversationSync(WorkspaceIdentity.CENTRAL_SESSION,"chat")
            WorkspaceRegistry(app).use { it.upgradeTreeSchema() }
            profileId=CrewProfileRepository(app).create(CrewProfile(CrewProfileRepository.newCustomId(),1,"Planner","Generalist planning",
                "Perform this planning task. Children remain ordinary workers, not captains.",emptyList(),caps,CrewProfile.WorkspaceMode.LEGACY_CHAT),caps,emptyList()).id
        }
    }
    @After fun cleanup() { managers.forEach { it.close() };pool.shutdownNow() }
    private data class Fixture(val scope:WorkspaceRecord,val manager:CrewManager,val root:String,val bot:CrewManager.Bot)
    private fun start(beforeMessage:((CrewManager,CrewManager.Bot,CrewMessage)->Unit)?=null,
        configureManager:((CrewManager)->Unit)?=null,script:(List<ConversationTurn>,List<ToolSpec>,CancellationToken)->ModelReply):Fixture=storage.onDatabaseWorker {
        val scope=WorkspaceRegistry(app).use { it.createWorkspace(UUID.randomUUID().toString(),"Native tree") }
        ChatDatabaseBridge.getInstance(app).ensureConversationSync(scope.executionSessionId,"workspace")
        val provider=object:ModelProviderClient {
            override fun complete(systemPrompt:String,userPrompt:String,images:List<ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply=error("Conversation provider required")
            override fun completeConversation(systemPrompt:String,history:List<ConversationTurn>,userPrompt:String,images:List<ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken)=script(history,tools,token)
        }
        val runtime=CoreAgentRuntime(app,scope.executionSessionId,emptyList(),CorePromptBudget.standard())
        val manager=runtime.configureCrewManager(runtime.createTools(),CoreAgentModel(app,provider,scope.executionSessionId),null)
        managers.add(manager);liveManager=manager;configureManager?.invoke(manager)
        if(beforeMessage!=null)manager.configureConversationMessageListener { bot,message ->
            beforeMessage(manager,bot,message)
            WorkspaceAgentInputDelivery.deliver(app,bot,message)
        }
        val pair=WorkspaceRegistry(app).use { db ->
            val tree=WorkspaceTreeStore(db);val generation=ChatDatabaseBridge.getInstance(app).connectionProjectionEvidenceSync().generation()
            val root=tree.beginRoot(TreeOrigin(generation,WorkspaceIdentity.CENTRAL_SESSION,"backend-fixture-${UUID.randomUUID()}","a".repeat(64)),
                "test_backend_root_no_invocation_authority",CrewProcessIdentity.ID+":fixture")
            val parent=tree.getNode(root.rootId,root.rootId)!!
            val child=tree.admitChild(TreeNodeFence.of(parent),TreeChildRequest(scope.workspaceId,"parent","request","Plan itinerary",profileId,
                "PARENT_PLAN",caps,"Planner",TreeMode.WORK,TreeReply.AWAIT,"test_backend_top_assignment_no_invocation_authority"))
            root.rootId to tree.reserveFirstExecution(TreeNodeFence.of(child),CrewProcessIdentity.ID)
        }
        val result=WorkspaceCoordinator(app,{manager},{}).startReservedTreeAssignment(pair.second,CancellationToken.uncancellable(),Runnable {})
        Fixture(scope,manager,pair.first,manager.bot(result.getString("bot_id"))!!)
    }
    private fun task(history:List<ConversationTurn>)=history.firstOrNull { it.incomingMetadata?.provenance?.kind==ConversationInput.Kind.TASK }?.content.orEmpty()
    private fun call(id:String,name:String,args:Map<String,Any?>)=ModelReply("",listOf(ModelReply.Call(id,name,args)))
    private fun child(history:List<ConversationTurn>):String=history.last { it.kind==ConversationTurn.Kind.TOOL_RESULT && it.content.startsWith("native-tree-child:") }
        .content.substringAfter("native-tree-child:").substringBefore('\n')
    private fun await(bot:CrewManager.Bot) { pool.submit { bot.awaitTermination() }.get(15,TimeUnit.SECONDS) }
    private fun deny(block:()->Unit) { val e=runCatching(block).exceptionOrNull();assertTrue("Expected exact native denial, got $e",e is IllegalStateException || e is IllegalArgumentException) }

    @Test fun actualDelegateAndExplicitAllConsumeExactChildOnceAfterItFinishedBeforeWait()=vertical(false,false)
    @Test fun actualImplicitFinishParksAllAndDoesNotPublishDraftAsSuccess()=vertical(true,false)
    @Test fun actualConsultChildHasReadOnlyToolsNoDelegateOrPeerMutation()=vertical(false,true)
    private fun vertical(implicit:Boolean,consult:Boolean) {
        val parentCalls=AtomicInteger();val childCalls=AtomicInteger();val finalProvider=CountDownLatch(1)
        val fixture=start { history,tools,_ ->
            if(task(history).contains("CHILD_PLAN")) {
                childCalls.incrementAndGet()
                if(consult) {
                    val names=tools.map { it.name };assertFalse(names.contains("delegate_subtask"));assertFalse(names.contains("board_post"))
                    assertFalse(names.contains("crew_spawn"));assertFalse(names.contains("workspaces"))
                }
                assertTrue(history.any { it.incomingMetadata?.provenance?.principalRole==ConversationInput.Provenance.PrincipalRole.PEER })
                ModelReply("CHILD_EVIDENCE",emptyList())
            } else when(parentCalls.incrementAndGet()) {
                1->call("delegate-real","delegate_subtask",mapOf("objective" to "CHILD_PLAN","mode" to if(consult)"consult" else "work","tools" to listOf("board_read")))
                2->if(implicit)ModelReply("DRAFT_NOT_SUCCESS",emptyList()) else {
                    // Deterministic complete-before-wait: wait for actual native child callback/finalization, not a status poll.
                    await(liveManager.bots().single { it.mission=="CHILD_PLAN" })
                    call("wait-real","wait_children",mapOf("ids" to listOf(child(history)),"mode" to "ALL"))
                }
                else->{assertTrue(history.any { it.content.contains("CHILD_EVIDENCE") });finalProvider.countDown();ModelReply("PARENT_SYNTHESIS",emptyList())}
            }
        }
        assertTrue("No exact child-wait successor reached the provider",finalProvider.await(15,TimeUnit.SECONDS));await(fixture.bot)
        assertEquals(fixture.bot.error(),CrewManager.Status.DONE,fixture.bot.status());assertEquals(3,parentCalls.get());assertEquals(1,childCalls.get())
        storage.onDatabaseWorker { WorkspaceRegistry(app).use { db ->
            val tree=WorkspaceTreeStore(db);val parent=tree.findAssignment(fixture.scope.workspaceId,fixture.bot.assignmentId())!!
            val waits=WorkspaceTreeWaitStore(tree).list(fixture.root,parent.nodeId);assertEquals(1,waits.size)
            assertEquals(TreeWaitState.ACKNOWLEDGED,waits.single().state);assertEquals(1,waits.single().selectedCauses!!.size)
            val child=tree.children(fixture.root,parent.nodeId).single();assertEquals(TreeNodeState.TERMINAL,child.state);assertNotNull(child.reportReceipt)
            assertEquals(if(consult)TreeMode.CONSULT else TreeMode.WORK,child.mode)
            val receipts=WorkspaceTreeEvidenceStore(tree).invocationReceiptsForNode(fixture.root,parent.nodeId)
            assertEquals(if(implicit)1 else 2,receipts.size);assertTrue(receipts.all { it.resultJson!=null })
            assertTrue(WorkspaceTreeWorkerCoordinator.verifiedTreeCalls(app,CrewCheckpointStore(app.filesDir).confirmDurable(fixture.scope.executionSessionId,
                fixture.bot.id,fixture.bot.scopeIdentity())!!).contains("delegate-real"))
        } }
    }

    @Test fun actualAnyConsumesOnlyFirstReportAndImplicitAllStillAwaitsUnfinishedSibling() {
        val parentCalls=AtomicInteger();val firstCalls=AtomicInteger();val secondCalls=AtomicInteger()
        val firstEntered=CountDownLatch(1);val secondEntered=CountDownLatch(1)
        val releaseFirst=CountDownLatch(1);val releaseSecond=CountDownLatch(1);val anyResumed=CountDownLatch(1);val finalResumed=CountDownLatch(1)
        val f=start { history,_,token ->
            when {
                task(history).contains("ANY_FIRST")-> {firstCalls.incrementAndGet();firstEntered.countDown();assertTrue(releaseFirst.await(15,TimeUnit.SECONDS));token.throwIfCancelled();ModelReply("FIRST_EXACT_EVIDENCE",emptyList())}
                task(history).contains("ANY_SECOND")-> {secondCalls.incrementAndGet();secondEntered.countDown();assertTrue(releaseSecond.await(15,TimeUnit.SECONDS));token.throwIfCancelled();ModelReply("SECOND_EXACT_EVIDENCE",emptyList())}
                else->when(parentCalls.incrementAndGet()) {
                    1->call("any-first","delegate_subtask",mapOf("objective" to "ANY_FIRST","tools" to emptyList<String>()))
                    2->call("any-second","delegate_subtask",mapOf("objective" to "ANY_SECOND","tools" to emptyList<String>()))
                    3->{
                        val ids=history.filter {it.kind==ConversationTurn.Kind.TOOL_RESULT && it.content.startsWith("native-tree-child:")}
                            .map {it.content.substringAfter("native-tree-child:").substringBefore('\n')}
                        assertEquals(2,ids.size);call("join-any","wait_children",mapOf("ids" to ids,"mode" to "ANY"))
                    }
                    4->{
                        assertTrue(history.any {it.content.contains("FIRST_EXACT_EVIDENCE")});assertFalse(history.any {it.content.contains("SECOND_EXACT_EVIDENCE")})
                        assertFalse(liveManager.bots().single {it.mission=="ANY_SECOND"}.token.isCancellationRequested)
                        anyResumed.countDown();ModelReply("Draft after first result, not final while second is awaited",emptyList())
                    }
                    5->{assertTrue(history.any {it.content.contains("SECOND_EXACT_EVIDENCE")});finalResumed.countDown();ModelReply("Both results synthesized",emptyList())}
                    else->error("Duplicate child cause resumed the parent")
                }
            }
        }
        try {
            assertTrue(firstEntered.await(15,TimeUnit.SECONDS));assertTrue(secondEntered.await(15,TimeUnit.SECONDS));await(f.bot)
            assertEquals(CrewManager.Status.WAITING,f.bot.status());assertEquals(3,parentCalls.get())
            releaseFirst.countDown();assertTrue(anyResumed.await(15,TimeUnit.SECONDS));await(f.bot)
            assertEquals(f.bot.error(),CrewManager.Status.WAITING,f.bot.status());assertNull(f.bot.resultEvidence());assertEquals(4,parentCalls.get())
            val second=f.manager.bots().single {it.mission=="ANY_SECOND"};assertFalse(second.token.isCancellationRequested);assertNull(second.resultEvidence())
            storage.onDatabaseWorker {WorkspaceRegistry(app).use {db ->
                val tree=WorkspaceTreeStore(db);val parent=tree.findAssignment(f.scope.workspaceId,f.bot.assignmentId())!!
                val children=tree.children(f.root,parent.nodeId);val secondNode=children.single {it.botId==second.id}
                val waits=WorkspaceTreeWaitStore(tree).list(f.root,parent.nodeId);assertEquals(2,waits.size)
                val any=waits.single {!it.implicit};assertEquals(TreeJoin.ANY,any.join);assertEquals(TreeWaitState.ACKNOWLEDGED,any.state)
                assertEquals(1,any.selectedCauses!!.size);assertNotEquals(secondNode.nodeId,any.selectedCauses!!.single().nodeId)
                val remaining=waits.single {it.implicit};assertEquals(TreeJoin.ALL,remaining.join);assertEquals(TreeWaitState.WAITING,remaining.state)
                assertEquals(listOf(secondNode.nodeId),remaining.children)
            }}
            releaseSecond.countDown();assertTrue(finalResumed.await(15,TimeUnit.SECONDS));await(f.bot)
            assertEquals(f.bot.error(),CrewManager.Status.DONE,f.bot.status());assertEquals(5,parentCalls.get());assertEquals(1,firstCalls.get());assertEquals(1,secondCalls.get())
            storage.onDatabaseWorker {WorkspaceRegistry(app).use {db ->
                val tree=WorkspaceTreeStore(db);val parent=tree.findAssignment(f.scope.workspaceId,f.bot.assignmentId())!!
                val waits=WorkspaceTreeWaitStore(tree).list(f.root,parent.nodeId)
                assertTrue(waits.all {it.state==TreeWaitState.ACKNOWLEDGED})
                val causes=waits.flatMap {it.selectedCauses!!};assertEquals(2,causes.size);assertEquals(2,causes.map {it.eventKey}.distinct().size)
                fun events(kind:String,node:String)=db.treeRead {sql -> sql.rawQuery("SELECT COUNT(*) FROM bot_tree_event WHERE root_id=? AND node_id=? AND kind=?",arrayOf(f.root,node,kind)).use {c->c.moveToFirst();c.getInt(0)}}
                assertEquals(2,events("CHILD_WAIT_EXECUTION",parent.nodeId));assertEquals(2,events("CONSUMED",parent.nodeId))
                tree.children(f.root,parent.nodeId).forEach {child ->assertEquals(1,events("ADMISSION",child.nodeId));assertEquals(1,events("EXECUTION",child.nodeId))}
            }}
        } finally {releaseFirst.countDown();releaseSecond.countDown()}
    }

    @Test fun actualReplyNoneChildIsExcludedFromImplicitWaitAndCannotWakeFinishedParent() {
        val parentCalls=AtomicInteger();val childCalls=AtomicInteger();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val f=start {history,tools,token ->
            if(task(history).contains("NO_REPLY_CHILD")) {
                childCalls.incrementAndGet();assertFalse(tools.any {it.name in setOf("crew_spawn","workspaces")})
                entered.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));token.throwIfCancelled();ModelReply("NO_REPLY_EVIDENCE",emptyList())
            } else when(parentCalls.incrementAndGet()) {
                1->call("no-reply-admission","delegate_subtask",mapOf("objective" to "NO_REPLY_CHILD","mode" to "work","reply" to "none","tools" to emptyList<String>()))
                2->ModelReply("Parent completed without awaiting reply-none work",emptyList())
                else->error("Reply-none child must not wake the parent")
            }
        }
        try {
            assertTrue(entered.await(15,TimeUnit.SECONDS));await(f.bot)
            assertEquals(f.bot.error(),CrewManager.Status.DONE,f.bot.status());assertEquals(2,parentCalls.get());assertNotNull(f.bot.resultEvidence())
            val child=f.manager.bots().single {it.id!=f.bot.id};assertFalse(child.token.isCancellationRequested);assertNull(child.resultEvidence())
            val cycle=f.bot.cycleId()
            storage.onDatabaseWorker {WorkspaceRegistry(app).use {db ->
                val tree=WorkspaceTreeStore(db);val parent=tree.findAssignment(f.scope.workspaceId,f.bot.assignmentId())!!
                assertTrue(WorkspaceTreeWaitStore(tree).list(f.root,parent.nodeId).isEmpty())
                val node=tree.children(f.root,parent.nodeId).single();assertEquals(TreeReply.NONE,node.reply);assertEquals(TreeMode.WORK,node.mode)
            }}
            release.countDown();await(child);assertEquals(child.error(),CrewManager.Status.DONE,child.status())
            assertEquals(cycle,f.bot.cycleId());assertEquals(2,parentCalls.get());assertEquals(1,childCalls.get())
            storage.onDatabaseWorker {WorkspaceRegistry(app).use {db ->
                val tree=WorkspaceTreeStore(db);val parent=tree.findAssignment(f.scope.workspaceId,f.bot.assignmentId())!!
                assertTrue(WorkspaceTreeWaitStore(tree).list(f.root,parent.nodeId).isEmpty())
                val node=tree.children(f.root,parent.nodeId).single();assertNotNull(node.reportReceipt)
                db.treeRead {sql -> sql.rawQuery("SELECT COUNT(*) FROM bot_tree_event WHERE root_id=? AND node_id=? AND kind IN ('CHILD_WAIT_EXECUTION','CONSUMED')",arrayOf(f.root,parent.nodeId)).use {c->c.moveToFirst();assertEquals(0,c.getInt(0))}}
            }}
        } finally {release.countDown()}
    }

    @Test fun confirmedParkSourceRemainsExactThroughNativeFinalPublicationBeforeWake() {
        val calls=AtomicInteger();val childEntered=CountDownLatch(1);val releaseChild=CountDownLatch(1);val successor=CountDownLatch(1)
        val fixture=start { history,_,_ ->
            if(task(history).contains("CHILD_PLAN")) { childEntered.countDown();assertTrue(releaseChild.await(15,TimeUnit.SECONDS));ModelReply("CHILD_EVIDENCE",emptyList()) }
            else when(calls.incrementAndGet()) {
                1->call("delegate-seal","delegate_subtask",mapOf("objective" to "CHILD_PLAN","tools" to listOf("board_read")))
                2->ModelReply("WAITING_DRAFT",emptyList())
                else->{successor.countDown();ModelReply("Final synthesis",emptyList())}
            }
        }
        try {
            assertTrue(childEntered.await(15,TimeUnit.SECONDS));await(fixture.bot)
            assertEquals(fixture.bot.error(),CrewManager.Status.WAITING,fixture.bot.status());assertNull(fixture.bot.resultEvidence())
            storage.onDatabaseWorker { WorkspaceRegistry(app).use { db ->
                val tree=WorkspaceTreeStore(db);val node=tree.findAssignment(fixture.scope.workspaceId,fixture.bot.assignmentId())!!
                val wait=WorkspaceTreeWaitStore(tree).list(node.rootId,node.nodeId).single()
                val saved=CrewCheckpointStore(app.filesDir).confirmDurable(fixture.scope.executionSessionId,fixture.bot.id,fixture.bot.scopeIdentity())!!
                assertEquals(TreeWaitState.WAITING,wait.state);assertEquals(saved.payloadSha256,wait.checkpointSha256)
                assertEquals(2,calls.get());assertTrue(fixture.manager.isTreeParkQuiescent(fixture.bot,fixture.bot.token))
            } }
            releaseChild.countDown();assertTrue(successor.await(15,TimeUnit.SECONDS));await(fixture.bot)
            assertEquals(fixture.bot.error(),CrewManager.Status.DONE,fixture.bot.status());assertEquals(3,calls.get())
        } finally { releaseChild.countDown() }
    }

    @Test fun oldCycleStopAfterSuccessorBindBeforeExecutorDeniesExactSuccessorProvider() {
        val parentCalls=AtomicInteger();val childEntered=CountDownLatch(1);val releaseChild=CountDownLatch(1);val deniedBeforeExecute=CountDownLatch(1)
        val oldCycle=java.util.concurrent.atomic.AtomicReference<String>()
        val fixture=start(configureManager={ manager ->
            val field=CrewManager::class.java.getDeclaredField("executor").apply { isAccessible=true }
            val original=field.get(manager) as java.util.concurrent.ExecutorService
            val dispatches=AtomicInteger()
            // Test-only executor transport barrier, not a production permission/test hook.
            field.set(manager,object:java.util.concurrent.AbstractExecutorService() {
                override fun shutdown()=original.shutdown()
                override fun shutdownNow():MutableList<Runnable> = original.shutdownNow()
                override fun isShutdown()=original.isShutdown
                override fun isTerminated()=original.isTerminated
                override fun awaitTermination(timeout:Long,unit:TimeUnit)=original.awaitTermination(timeout,unit)
                override fun execute(command:Runnable) {
                    if(dispatches.incrementAndGet()==3) {
                        val parent=manager.bots().single { it.mission=="PARENT_PLAN" }
                        val old=requireNotNull(oldCycle.get())
                        WorkspaceRegistry(app).use { db ->
                            val node=WorkspaceTreeStore(db).findAssignment(WorkspaceIdentity.workspaceId(manager.conversationId()),parent.assignmentId())!!
                            assertNotEquals(old,node.cycleId);assertEquals(parent.cycleId(),node.cycleId)
                            // Registry successor is already BOUND, but its actual native runnable has not executed.
                            WorkspaceTreeStops.latchNode(app,node.rootId,node.nodeId,old)
                        }
                        deniedBeforeExecute.countDown()
                    }
                    original.execute(command)
                }
            })
        }) { history,_,_ ->
            if(task(history).contains("CHILD_PLAN")) { childEntered.countDown();assertTrue(releaseChild.await(15,TimeUnit.SECONDS));ModelReply("CHILD_EVIDENCE",emptyList()) }
            else when(parentCalls.incrementAndGet()) {
                1->call("delegate-stop","delegate_subtask",mapOf("objective" to "CHILD_PLAN","tools" to listOf("board_read")))
                2->ModelReply("DRAFT",emptyList())
                else->throw AssertionError("Old STOP escaped into successor provider")
            }
        }
        try {
            assertTrue(childEntered.await(15,TimeUnit.SECONDS));await(fixture.bot)
            assertEquals(fixture.bot.error(),CrewManager.Status.WAITING,fixture.bot.status())
            oldCycle.set(fixture.bot.cycleId());releaseChild.countDown()
            assertTrue("Successor never reached the post-bind executor fence",deniedBeforeExecute.await(15,TimeUnit.SECONDS));await(fixture.bot)
            assertEquals(2,parentCalls.get());assertNotEquals(CrewManager.Status.DONE,fixture.bot.status())
        } finally { releaseChild.countDown() }
    }

    @Test fun formalAskChiefYieldsExactNeedsInputWithoutTerminalResultOrLegacyAnswer() {
        val calls=AtomicInteger();val prepublication=AtomicInteger()
        val fixture=start(beforeMessage={ manager,bot,message ->
            if(message.type==CrewMessage.Type.QUESTION) {
                val evidence=LocalRunStore(app).readCrewCoordinationEvidence(message.conversationId,message.coordination!!.evidenceId)!!
                assertTrue(manager.canReplyToCoordination(evidence));assertFalse(manager.canReplyToOrdinaryCoordination(evidence))
                WorkspaceRegistry(app).use { db ->
                    assertEquals(TreeNodeState.RUNNING,WorkspaceTreeStore(db).findAssignment(WorkspaceIdentity.workspaceId(message.conversationId),bot.assignmentId())!!.state)
                }
                val admissions=AtomicInteger()
                deny { manager.replyToCoordinationExact(evidence,"forged-prepublication","June",null) { admissions.incrementAndGet();true } }
                assertEquals(0,admissions.get());prepublication.incrementAndGet()
            }
        }) { _,_,_ -> calls.incrementAndGet();call("formal-question","ask_chief",mapOf("question" to "Which travel dates?")) }
        await(fixture.bot)
        assertEquals(fixture.bot.error(),CrewManager.Status.PARTIAL,fixture.bot.status());assertEquals(1,calls.get())
        assertNull(fixture.bot.resultEvidence());assertTrue(fixture.bot.requiresExplicitResume());assertEquals(1,prepublication.get())
        storage.onDatabaseWorker {
            WorkspaceRegistry(app).use { db ->
                val tree=WorkspaceTreeStore(db);val node=tree.findAssignment(fixture.scope.workspaceId,fixture.bot.assignmentId())!!
                assertEquals(TreeNodeState.NEEDS_INPUT,node.state);assertNotNull(node.terminalRef);assertNotNull(node.reportReceipt)
                val source=LocalRunStore(app).readCrewCoordinationEvidence(fixture.scope.executionSessionId,node.terminalRef!!)!!
                assertFalse(fixture.manager.canReplyToOrdinaryCoordination(source))
                deny { fixture.manager.replyToCoordinationExact(source,"forged-answer","June",null) }
                val saved=CrewCheckpointStore(app.filesDir).confirmDurable(fixture.scope.executionSessionId,fixture.bot.id,fixture.bot.scopeIdentity())!!
                assertTrue(WorkspaceTreeWorkerCoordinator.verifiedTreeCalls(app,saved).contains("formal-question"))
                assertEquals("RESULT",saved.loop.toolLifecycle["formal-question"])
            }
        }
        assertEquals(1,calls.get())
    }
    @Test fun ordinaryQuestionIsNotFormalNeedsInput()=ordinaryMessage("QUESTION")
    @Test fun ordinaryBlockerIsNotFormalNeedsInput()=ordinaryMessage("BLOCKER")
    private fun ordinaryMessage(kind:String) {
        val calls=AtomicInteger();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val fixture=start { _,_,_ -> if(calls.incrementAndGet()==1)
            call("ordinary-message","msg_send",mapOf("to" to "chief","type" to kind,"text" to "ordinary coordination"))
            else { entered.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));ModelReply("Done",emptyList()) }
        }
        try {
            assertTrue(entered.await(15,TimeUnit.SECONDS))
            storage.onDatabaseWorker { WorkspaceRegistry(app).use { db ->
                val node=WorkspaceTreeStore(db).findAssignment(fixture.scope.workspaceId,fixture.bot.assignmentId())!!
                assertEquals(TreeNodeState.RUNNING,node.state);assertNull(node.terminalRef)
                val store=LocalRunStore(app);val id=store.listCrewCoordinationEvidence(fixture.scope.executionSessionId,20,null).single()
                val evidence=store.readCrewCoordinationEvidence(fixture.scope.executionSessionId,id)!!
                assertTrue(fixture.manager.canReplyToOrdinaryCoordination(evidence));assertNull(fixture.bot.treeWaitTicket())
            } }
        } finally { release.countDown();await(fixture.bot) }
        assertEquals(2,calls.get())
    }

    @Test fun forgedDelegateMarkerAndToolNameDoNotCreateSafePointAuthority() {
        val fixture=start { _,_,_->ModelReply("Done without tools",emptyList()) };await(fixture.bot)
        storage.onDatabaseWorker {
            val store=CrewCheckpointStore(app.filesDir)
            val saved=store.confirmDurable(fixture.scope.executionSessionId,fixture.bot.id,fixture.bot.scopeIdentity())!!
            val raw=saved.loopForEvidence();val transcript=raw.transcript+ConversationTurn.toolCalls("",listOf(ModelReply.Call("forged","delegate_subtask",mapOf("objective" to "fake"))))+
                ConversationTurn.toolResult("forged","delegate_subtask","native-tree-child:forged\nA marker is not a receipt")
            val altered=CoreAgentLoop.Checkpoint(transcript,raw.appliedIncomingIds,raw.toolLifecycle+("forged" to "RESULT"),raw.genuineUserIndex,raw.incorporations)
            val fake=store.saveConfirmed(saved.conversationId,saved.botId,saved.metadataJson(),saved.scopeIdentity,altered,saved.artifactOwnershipJson())
            assertFalse(WorkspaceTreeWorkerCoordinator.verifiedTreeCalls(app,fake).contains("forged"))
            deny { CrewCheckpointCoordinator(app,fixture.scope.executionSessionId) { it.role }.requireNativeSafePoint(fake) }
        }
    }
    @Test fun exactQuestionReceiptRejectsChangedRetainedResultContent() {
        val fixture=start { _,_,_->call("question-mutation","ask_chief",mapOf("question" to "Dates?")) };await(fixture.bot)
        assertEquals(fixture.bot.error(),CrewManager.Status.PARTIAL,fixture.bot.status())
        storage.onDatabaseWorker {
            val store=CrewCheckpointStore(app.filesDir);val saved=store.confirmDurable(fixture.scope.executionSessionId,fixture.bot.id,fixture.bot.scopeIdentity())!!
            assertTrue(WorkspaceTreeWorkerCoordinator.verifiedTreeCalls(app,saved).contains("question-mutation"))
            val raw=saved.loopForEvidence();val transcript=raw.transcript.map { if(it.kind==ConversationTurn.Kind.TOOL_RESULT && it.toolCallId=="question-mutation")
                ConversationTurn.toolResult(it.toolCallId,it.toolName,it.content+" altered") else it }
            val altered=CoreAgentLoop.Checkpoint(transcript,raw.appliedIncomingIds,raw.toolLifecycle,raw.genuineUserIndex,raw.incorporations)
            val fake=store.saveConfirmed(saved.conversationId,saved.botId,saved.metadataJson(),saved.scopeIdentity,altered,saved.artifactOwnershipJson())
            deny { WorkspaceTreeWorkerCoordinator.verifiedTreeCalls(app,fake) }
        }
    }

    @Test fun enrolledGenericAskCannotFallBackToBlockingWithoutInvocation()=whileNativeRunning { f ->
        deny { f.manager.askChiefAndYieldIfLinked(f.bot,"forged question",f.bot.token) }
        deny { f.manager.askChief(f.bot,"forged blocking question",f.bot.token) }
        assertTrue(LocalRunStore(app).listCrewCoordinationEvidence(f.scope.executionSessionId,20,null).isEmpty())
    }
    @Test fun enrolledLegacyReportDoneCannotBypassImplicitWaitReceipt()=whileNativeRunning { f ->
        deny { f.manager.reportDone(f.bot,"unattributed completion",emptyList()) };assertEquals("",f.bot.result())
    }
    @Test fun enrolledGenericUserSendCannotBypassCountedControl()=whileNativeRunning { f ->
        deny { f.manager.sendUserMessageExact(f.bot.id,f.bot.token,"fake correction",null) }
    }
    @Test fun enrolledGenericChiefAnswerCannotBypassRetainedExplicitResume()=whileNativeRunning { f ->
        deny { f.manager.send("chief",f.bot.id,CrewMessage.Type.ANSWER,"fake answer",emptyList()) }
    }
    @Test fun enrolledNormalResumeWithoutRegisteredClaimIsDenied()=whileNativeRunning { f ->
        deny { f.manager.resumeExact(f.bot.id,f.bot.token,null) }
    }
    @Test fun retainedReadyWaitReopensWithoutDispatchAndExactUserResumeConsumesOnce()=reopenedWait("ready")
    @Test fun retainedQuestionWaitUsesFreshUserAuthorityWithoutVanishedLiveSlot()=reopenedWait("question")
    @Test fun retainedStartedWaitNeverReplaysThroughNewUserResume()=reopenedWait("started")
    @Test fun retainedArmingWaitCannotBypassMissingConfirmationThroughResume()=reopenedWait("arming")
    @Test fun correctionCannotSilentlyDiscardRetainedUnconsumedWait()=reopenedWait("correct")
    private fun reopenedWait(mode:String) {
        val childEntered=CountDownLatch(1);val releaseChild=CountDownLatch(1);val originalCalls=AtomicInteger();val recoveredCalls=AtomicInteger()
        val f=start { history,_,_ ->
            if(task(history).contains("CHILD_RESTART")) {childEntered.countDown();assertTrue(releaseChild.await(15,TimeUnit.SECONDS));
                if(mode=="question")call("reopen-question","ask_chief",mapOf("question" to "RESTART_EVIDENCE question")) else ModelReply("RESTART_EVIDENCE",emptyList())}
            else when(originalCalls.incrementAndGet()) {
                1->call("restart-delegate","delegate_subtask",mapOf("objective" to "CHILD_RESTART","role" to profileId,"tools" to emptyList<String>()))
                2->call("restart-wait","wait_children",mapOf("ids" to listOf(child(history)),"mode" to "ALL"))
                else->error("Old process must not resume")
            }
        }
        try {
            assertTrue(childEntered.await(15,TimeUnit.SECONDS));await(f.bot)
            assertEquals(CrewManager.Status.WAITING,f.bot.status())
            // Process-loss simulation: erase only ephemeral parked capability, retaining actual disk/Registry bytes.
            val listenerField=CrewManager::class.java.getDeclaredField("treePublicationListener").apply {isAccessible=true}
            val listener=listenerField.get(f.manager)
            val parked=listener.javaClass.getDeclaredField("parked").apply {isAccessible=true}.get(listener) as MutableMap<*,*>
            parked.clear();releaseChild.countDown();await(f.manager.bots().single {it.id!=f.bot.id})
            val wait=storage.onDatabaseWorker {WorkspaceRegistry(app).use { db ->
                val store=WorkspaceTreeWaitStore(WorkspaceTreeStore(db));val node=WorkspaceTreeStore(db).findAssignment(f.scope.workspaceId,f.bot.assignmentId())!!
                val ready=store.prepareReady(node.rootId,store.list(node.rootId,node.nodeId).single().waitId)!!
                if(mode=="started")store.claimWait(ready,CrewProcessIdentity.ID,
                    ChatDatabaseBridge.getInstance(app).connectionProjectionEvidenceSync().generation(),
                    (f.manager.treeSupport(f.bot) as WorkspaceTreeWorkerCoordinator).claimOwnerToken(f.bot,f.bot.token)).wait
                else if(mode=="arming") {
                    // Fault boundary: typed checkpoint exists, but durable confirmation did not commit.
                    db.treeTransaction {sql -> sql.execSQL("UPDATE bot_tree_wait SET state='ARMING' WHERE root_id=? AND wait_id=?",arrayOf(ready.rootId,ready.waitId))}
                    store.get(ready.rootId,ready.waitId)!!
                } else ready
            }}
            val sealed=storage.onDatabaseWorker {CrewCheckpointStore(app.filesDir).confirmDurable(f.scope.executionSessionId,f.bot.id,f.bot.scopeIdentity())!!.payloadSha256}
            val crews=CoreAgentRuntime::class.java.getDeclaredField("CREWS").apply {isAccessible=true}.get(null) as MutableMap<*,*>
            crews.remove(f.scope.executionSessionId)
            val restored=storage.onDatabaseWorker {
                val provider=object:ModelProviderClient {
                    override fun complete(systemPrompt:String,userPrompt:String,images:List<ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply=error("Conversation required")
                    override fun completeConversation(systemPrompt:String,history:List<ConversationTurn>,userPrompt:String,images:List<ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply {
                        recoveredCalls.incrementAndGet();assertTrue(history.any {it.content.contains("RESTART_EVIDENCE")});return ModelReply("Recovered synthesis",emptyList())
                    }
                }
                val runtime=CoreAgentRuntime(app,f.scope.executionSessionId,emptyList(),CorePromptBudget.standard())
                runtime.configureCrewManager(runtime.createTools(),CoreAgentModel(app,provider,f.scope.executionSessionId),null).also {managers.add(it)}
            }
            val bot=restored.bot(f.bot.id)!!;assertNotSame(f.bot.token,bot.token);assertEquals(0,recoveredCalls.get())
            if(mode=="question")storage.onDatabaseWorker {WorkspaceRegistry(app).use {db ->
                val child=WorkspaceTreeStore(db).findAssignment(f.scope.workspaceId,restored.bots().single {it.id!=bot.id}.assignmentId())!!
                val question=LocalRunStore(app).readCrewCoordinationEvidence(f.scope.executionSessionId,child.terminalRef!!)!!
                assertFalse(restored.canReplyToCoordination(question))
            }}
            storage.onDatabaseWorker {
                assertEquals(sealed,CrewCheckpointStore(app.filesDir).confirmDurable(f.scope.executionSessionId,bot.id,bot.scopeIdentity())!!.payloadSha256)
                WorkspaceRegistry(app).use {db ->
                    val assignment=db.getAssignment(f.scope.workspaceId,bot.assignmentId())!!
                    val type=if(mode=="correct")WorkspaceControlType.CORRECT else WorkspaceControlType.RESUME
                    val control=db.nativeControls().submit(UUID.randomUUID().toString(),f.scope.workspaceId,assignment.assignmentId,bot.id,assignment.revision,type,"Explicit retained-wait action")
                    if(mode in setOf("ready","question"))WorkspaceCoordinator(app,{restored},{}).executeNativeControl(control.requestId,CancellationToken.uncancellable())
                    else deny {WorkspaceCoordinator(app,{restored},{}).executeNativeControl(control.requestId,CancellationToken.uncancellable())}
                }
            }
            if(mode in setOf("ready","question")) {await(bot);assertEquals(bot.error(),if(mode=="question")CrewManager.Status.WAITING else CrewManager.Status.DONE,bot.status());assertEquals(1,recoveredCalls.get())}
            else assertEquals(0,recoveredCalls.get())
            storage.onDatabaseWorker {WorkspaceRegistry(app).use {db ->
                val after=WorkspaceTreeWaitStore(WorkspaceTreeStore(db)).get(wait.rootId,wait.waitId)!!
                assertEquals(if(mode in setOf("ready","question"))TreeWaitState.ACKNOWLEDGED else wait.state,after.state)
            }}
        } finally {releaseChild.countDown()}
    }
    @Test fun actualParentMsgSendRetainsFormalAnswerWithoutDispatchUntilExplicitChildResume() {
        val parentCalls=AtomicInteger();val childCalls=AtomicInteger();val parentParked=CountDownLatch(1)
        val f=start { history,_,_ ->
            if(task(history).contains("CHILD_QUESTION")) {
                if(childCalls.incrementAndGet()==1)call("child-date","ask_chief",mapOf("question" to "Which date?"))
                else { assertTrue(history.any { it.content.contains("June 4") });ModelReply("CHILD_ANSWERED",emptyList()) }
            } else when(parentCalls.incrementAndGet()) {
                1->call("delegate-question","delegate_subtask",mapOf("objective" to "CHILD_QUESTION","role" to profileId,"tools" to listOf("ask_chief")))
                2->call("await-question","wait_children",mapOf("ids" to listOf(child(history)),"mode" to "ALL"))
                3->{
                    val childBot=liveManager.bots().single { it.mission.contains("CHILD_QUESTION") }
                    val question=history.last { it.incomingMetadata?.provenance?.kind==ConversationInput.Kind.QUESTION }
                    val questionId=question.content.lineSequence().first { it.startsWith("Question message ID: ") }.substringAfter("Question message ID: ")
                    assertTrue(questionId.isNotBlank())
                    call("answer-child","msg_send",mapOf("to" to childBot.id,"type" to "ANSWER","text" to "June 4","refs" to listOf(questionId,"additional untrusted reference")))
                }
                4->{assertTrue(history.any { it.kind==ConversationTurn.Kind.TOOL_RESULT && it.content.startsWith("native-tree-answer:") });parentParked.countDown();ModelReply("Wait for answered child",emptyList())}
                else->{assertTrue(history.any { it.content.contains("CHILD_ANSWERED") });ModelReply("Finished",emptyList())}
            }
        }
        assertTrue(parentParked.await(15,TimeUnit.SECONDS));await(f.bot)
        val childBot=f.manager.bots().single { it.id!=f.bot.id };await(childBot)
        assertEquals(1,childCalls.get());assertEquals(CrewManager.Status.PARTIAL,childBot.status());assertEquals(CrewManager.Status.WAITING,f.bot.status())
        storage.onDatabaseWorker { WorkspaceRegistry(app).use { db ->
            val assignment=db.getAssignment(f.scope.workspaceId,childBot.assignmentId())!!
            val control=db.nativeControls().submit(UUID.randomUUID().toString(),f.scope.workspaceId,assignment.assignmentId,childBot.id,
                assignment.revision,WorkspaceControlType.RESUME,"Use the retained answer")
            WorkspaceCoordinator(app,{f.manager},{}).executeNativeControl(control.requestId,CancellationToken.uncancellable())
        } }
        await(childBot);await(f.bot);assertEquals(2,childCalls.get());assertEquals(5,parentCalls.get())
        assertEquals(f.bot.error(),CrewManager.Status.DONE,f.bot.status())
    }
    @Test fun nativeRequesterStopDurablyClosesWakesAndCancelsOnlyNeverStartedChild()=whileNativeRunning { f ->
        val queued=WorkspaceRegistry(app).use { db ->
            val tree=WorkspaceTreeStore(db);val parent=tree.findAssignment(f.scope.workspaceId,f.bot.assignmentId())!!
            tree.admitChild(TreeNodeFence.of(parent),TreeChildRequest(f.scope.workspaceId,"queued-stop","queued-request","Queued task",profileId,
                "NEVER_STARTED",caps,"Planner",TreeMode.WORK,TreeReply.AWAIT,"test_backend_queued_admission_not_invocation"))
        }
        assertTrue(f.manager.stopExact(f.bot.id,f.bot.token))
        WorkspaceRegistry(app).use { reopened ->
            val tree=WorkspaceTreeStore(reopened);val parent=tree.findAssignment(f.scope.workspaceId,f.bot.assignmentId())!!
            assertTrue(parent.admissionsClosed);assertTrue(parent.wakesClosed)
            val cancelled=tree.getNode(queued.rootId,queued.nodeId)!!
            assertEquals(TreeNodeState.TERMINAL,cancelled.state);assertNull(cancelled.botId);assertNull(cancelled.executionEventId)
            assertEquals("CANCELLED",cancelled.terminalOutcome);assertEquals(1,f.manager.bots().size)
            deny { tree.reserveFirstExecution(TreeNodeFence.of(cancelled),CrewProcessIdentity.ID) }
        }
    }
    @Test fun nativeTerminalStopStillLatchesExactCycleBeforeReturningFalse() {
        val f=start { _,_,_->ModelReply("Done",emptyList()) };await(f.bot)
        assertEquals(CrewManager.Status.DONE,f.bot.status())
        storage.onDatabaseWorker {
            assertFalse(f.manager.stop(f.bot.id))
            WorkspaceRegistry(app).use { db ->
                val node=WorkspaceTreeStore(db).findAssignment(f.scope.workspaceId,f.bot.assignmentId())!!
                deny { WorkspaceTreeStops.requireAllowed(db.treeStorageIdentity,node.rootId,node.nodeId,node.cycleId) }
            }
        }
    }
    @Test fun retainedFormalAnswerNeverDispatchesUntilExplicitNativeResumeAndIsAcknowledgedOnce() {
        val calls=AtomicInteger()
        val fixture=start { history,_,_ -> if(calls.incrementAndGet()==1)call("dates-user-resume","ask_chief",mapOf("question" to "Travel dates?"))
            else { assertTrue(history.any { it.content.contains("June 4") && it.incomingMetadata?.origin==ConversationInput.Origin.AGENT &&
                it.incomingMetadata?.provenance?.principalRole==ConversationInput.Provenance.PrincipalRole.PEER });ModelReply("Itinerary confirmed",emptyList()) }
        }
        await(fixture.bot);assertEquals(fixture.bot.error(),CrewManager.Status.PARTIAL,fixture.bot.status())
        val answer=storage.onDatabaseWorker { WorkspaceRegistry(app).use { db ->
            val tree=WorkspaceTreeStore(db);val node=tree.findAssignment(fixture.scope.workspaceId,fixture.bot.assignmentId())!!
            val parent=tree.getNode(node.rootId,node.parentNodeId!!)!!
            WorkspaceTreeAnswerStore(tree).prepare(TreeNodeFence.of(parent),TreeNodeFence.of(node),"June 4","test_backend_exact_answer_data_not_USER_authority")
        } }
        assertEquals(1,calls.get());assertEquals(CrewManager.Status.PARTIAL,fixture.bot.status())
        val control=storage.onDatabaseWorker { WorkspaceRegistry(app).use { db ->
            val assignment=db.getAssignment(fixture.scope.workspaceId,fixture.bot.assignmentId())!!
            db.nativeControls().submit(UUID.randomUUID().toString(),fixture.scope.workspaceId,assignment.assignmentId,fixture.bot.id,
                assignment.revision,WorkspaceControlType.RESUME,"Resume with retained answer")
        } }
        storage.onDatabaseWorker { assertTrue(WorkspaceCoordinator(app,{fixture.manager},{}).executeNativeControl(control.requestId,CancellationToken.uncancellable()).contains("completed")) }
        await(fixture.bot);assertEquals(fixture.bot.error(),CrewManager.Status.DONE,fixture.bot.status());assertEquals(2,calls.get())
        storage.onDatabaseWorker { WorkspaceRegistry(app).use { db ->
            val answers=WorkspaceTreeAnswerStore(WorkspaceTreeStore(db));assertNotNull(answers.acknowledged(answer))
            val saved=CrewCheckpointStore(app.filesDir).confirmDurable(fixture.scope.executionSessionId,fixture.bot.id,fixture.bot.scopeIdentity())!!
            val original=answers.acknowledged(answer);WorkspaceTreeDelivery.reconcileCheckpoint(app,saved)
            assertEquals(original,answers.acknowledged(answer));assertEquals(WorkspaceControlState.COMPLETE,db.getControl(control.requestId)!!.state)
        } }
    }
    @Test fun oldStopCannotCrossFreshExplicitUserSuccessorBoundary() {
        val calls=AtomicInteger();val oldCycle=java.util.concurrent.atomic.AtomicReference<String>();val checked=CountDownLatch(1)
        val fixture=start(configureManager={ manager ->
            val field=CrewManager::class.java.getDeclaredField("executor").apply { isAccessible=true }
            val original=field.get(manager) as java.util.concurrent.ExecutorService;val submissions=AtomicInteger()
            field.set(manager,object:java.util.concurrent.AbstractExecutorService() {
                override fun shutdown()=original.shutdown();override fun shutdownNow():MutableList<Runnable> = original.shutdownNow()
                override fun isShutdown()=original.isShutdown;override fun isTerminated()=original.isTerminated
                override fun awaitTermination(timeout:Long,unit:TimeUnit)=original.awaitTermination(timeout,unit)
                override fun execute(command:Runnable) {
                    if(submissions.incrementAndGet()==2) {
                        val bot=manager.bots().single();val old=requireNotNull(oldCycle.get())
                        WorkspaceRegistry(app).use { db ->
                            val tree=WorkspaceTreeStore(db);val node=tree.findAssignment(WorkspaceIdentity.workspaceId(manager.conversationId()),bot.assignmentId())!!
                            assertNotEquals(old,node.cycleId)
                            WorkspaceTreeStops.latchNode(app,node.rootId,node.nodeId,old)
                            assertFalse(WorkspaceTreeStops.linkedFrom(db.treeStorageIdentity,node.rootId,node.nodeId,node.cycleId,old))
                            tree.requireDispatch(TreeNodeFence.of(node))
                        }
                        checked.countDown()
                    }
                    original.execute(command)
                }
            })
        }) { _,_,_->calls.incrementAndGet();ModelReply("Done",emptyList()) }
        await(fixture.bot);assertEquals(CrewManager.Status.DONE,fixture.bot.status());oldCycle.set(fixture.bot.cycleId())
        storage.onDatabaseWorker { WorkspaceRegistry(app).use { db ->
            val assignment=db.getAssignment(fixture.scope.workspaceId,fixture.bot.assignmentId())!!
            val control=db.nativeControls().submit(UUID.randomUUID().toString(),fixture.scope.workspaceId,assignment.assignmentId,fixture.bot.id,
                assignment.revision,WorkspaceControlType.RESUME,"Explicitly continue the completed worker")
            WorkspaceCoordinator(app,{fixture.manager},{}).executeNativeControl(control.requestId,CancellationToken.uncancellable())
        } }
        assertTrue(checked.await(15,TimeUnit.SECONDS));await(fixture.bot)
        assertEquals(fixture.bot.error(),CrewManager.Status.DONE,fixture.bot.status());assertEquals(2,calls.get())
    }

    @Test fun pendingUserControlRowCannotMintRegisteredResumeOrAlterSource()=whileNativeRunning { f ->
        val source=CrewCheckpointStore(app.filesDir).confirmDurable(f.scope.executionSessionId,f.bot.id,f.bot.scopeIdentity())!!
        WorkspaceRegistry(app).use { db ->
            val node=WorkspaceTreeStore(db).findAssignment(f.scope.workspaceId,f.bot.assignmentId())!!
            val control=db.nativeControls().submit(UUID.randomUUID().toString(),f.scope.workspaceId,f.bot.assignmentId(),f.bot.id,
                node.assignmentRevision,WorkspaceControlType.CORRECT,"Correct the itinerary")
            val owner=f.manager.treeSupport(f.bot) as WorkspaceTreeWorkerCoordinator
            deny { owner.authorizeUserControlResume(f.bot,f.bot.token,TreeControlClaim(node,control,null),source,null,null) }
            assertEquals(WorkspaceControlState.PENDING,db.getControl(control.requestId)!!.state)
            assertEquals(source.payloadSha256,CrewCheckpointStore(app.filesDir).confirmDurable(f.scope.executionSessionId,f.bot.id,f.bot.scopeIdentity())!!.payloadSha256)
        }
    }

    @Test fun nativeOwnerKeyCannotTransferToReplacementToken()=whileNativeRunning { f ->
        val owner=f.manager.treeSupport(f.bot) as WorkspaceTreeWorkerCoordinator
        val key=owner.claimOwnerToken(f.bot,f.bot.token);assertEquals(key,owner.claimOwnerToken(f.bot,f.bot.token))
        deny { owner.claimOwnerToken(f.bot,CancellationToken.uncancellable()) }
    }
    @Test fun restoredDtoCannotConstructOpaquePublicationOrParkLease() {
        for(type in listOf(CrewManager.TreePublicationLease::class.java,CrewManager.TreeParkLease::class.java)) {
            // Java 8 emits package-private ACC_SYNTHETIC constructor bridges for outer-class access.
            val sourceConstructors=type.declaredConstructors.filterNot {it.isSynthetic}
            assertTrue(sourceConstructors.isNotEmpty())
            assertTrue(sourceConstructors.all {java.lang.reflect.Modifier.isPrivate(it.modifiers)})
            assertTrue(type.constructors.isEmpty()) // No public DTO/JSON constructor may mint either capability.
        }
    }
    private fun whileNativeRunning(assertion:(Fixture)->Unit) {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
        val fixture=start { _,_,token -> calls.incrementAndGet();entered.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));token.throwIfCancelled();ModelReply("Done",emptyList()) }
        try { assertTrue(entered.await(15,TimeUnit.SECONDS));storage.onDatabaseWorker { assertion(fixture) };assertEquals(1,calls.get()) }
        finally { release.countDown();await(fixture.bot) }
    }
}
