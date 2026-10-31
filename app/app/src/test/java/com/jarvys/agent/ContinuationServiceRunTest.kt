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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real service entry + CoreAgentRuntime/loop + scripted provider, not a claim counter substituted for inference. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CentralFlowTestApplication::class, shadows = [ArtifactOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ContinuationServiceRunTest {
    private val storage=ChatTestStore(runTestOnWorker=false,registerApplicationFilesDirectory=true)
    @get:Rule val rules:RuleChain=RuleChain.outerRule(storage).around(TestSecretStoreRule())
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private var controller:org.robolectric.android.controller.ServiceController<AgentForegroundService>?=null
    private val central=WorkspaceIdentity.CENTRAL_SESSION
    private var replyOverride:((List<ConversationTurn>)->ModelReply)?=null

    @Before fun setup() {
        // The provider is scripted, including its context-budget metadata; never attempt HTTP.
        ProviderSettings(context).apply {
            setProvider(ProviderSettings.Provider.OPENROUTER)
            setOpenRouterModel("offline/continuation")
        }
        check(context.getSharedPreferences("provider_context_metadata", 0).edit()
            .putInt("model_offline/continuation",32768)
            .putLong("fetched_offline/continuation",System.currentTimeMillis()).commit())
        AgentRunUiState.resetSession(central)
        storage.localRunStore(context.filesDir)
        storage.onDatabaseWorker {
            FreshStorageBootstrap.ensure(context)
            ChatDatabaseBridge.getInstance(context).ensureConversationSync(central,"chat")
            val store=LocalRunStore(context)
            val old=store.readPendingInternalConversationInputs(central).map { it.getString("messageId") }
            if(old.isNotEmpty()) store.markConversationMessagesResolved(central,old)
        }
    }
    @After fun cleanup() {
        StopController.stopAllRuns();StopController.getInstance().stopRun()
        controller?.let { runCatching { it.destroy() } };controller=null
        context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
    }

    private inner class Episode(sharedWorkspace: WorkspaceRecord? = null) {
        val suffix=UUID.randomUUID().toString()
        val cycle=UUID.randomUUID().toString()
        val bot="bot-$suffix"
        var store=LocalRunStore(context)
        val checkpoints=CrewCheckpointStore(context.filesDir)
        val workspace:WorkspaceRecord
        val assignment:WorkspaceAssignment
        val inputId:String
        init {
            WorkspaceRegistry(context).use { registry ->
                workspace=sharedWorkspace ?: registry.createWorkspace("work-$suffix","Itinerary")
                ChatDatabaseBridge.getInstance(context).ensureConversationSync(workspace.executionSessionId,"workspace")
                val p=registry.prepareAssignment(workspace.workspaceId,"assign-$suffix","request-$suffix","Plan a trip",
                    "analyst","Prepare itinerary",listOf("board_read"),"Planner",CrewMissionAccess.READ_ONLY)
                val started=registry.markStarted(workspace.workspaceId,p.assignmentId,p.revision,"fixture")
                assignment=registry.bindBot(workspace.workspaceId,p.assignmentId,started.revision,bot)
            }
            val saved=checkpoints.saveConfirmed(workspace.executionSessionId,bot,JSONObject()
                .put("conversationId",workspace.executionSessionId).put("botId",bot)
                .put("missionId",assignment.missionId).put("assignmentId",assignment.assignmentId)
                .put("cycleId",cycle).put("nativeWorkspaceCheckpoint",true).put("status","DONE"),
                "native-workspace:${workspace.workspaceId}",CoreAgentLoop.Checkpoint.empty(),JSONObject())
            checkpoints.createCentralContinuation(saved,CentralContinuationRecord.create(workspace.workspaceId,
                assignment.assignmentId,assignment.requestId,assignment.missionId,bot,cycle))
            val evidence=com.jarvys.agent.crew.CrewResultEvidence.create(workspace.executionSessionId,assignment.missionId,bot,cycle,
                "DONE","Synthetic itinerary evidence",1L)
            store.appendCrewResultEvidence(evidence)
            val ref=WorkspaceResultReference.create(evidence,central,workspace.workspaceId,
                assignment.assignmentId,assignment.requestId,assignment.revision)
            store.appendWorkspaceResultReferenceIfAbsent(ref,Runnable {})
            store.appendWorkspaceResultNoticeIfAbsent(ref,Runnable {})
            WorkspaceContinuationDelivery.result(context,ref,CancellationToken.uncancellable())
            inputId=store.readConversationMessageByStableId(central,record().getString("inputStableId"))!!.getString("messageId")
        }
        fun record()=checkpoints.load(workspace.executionSessionId,bot)!!.metadata.getJSONObject(CentralContinuationRecord.FIELD)
        fun input()=store.readConversationInput(central,inputId)!!
        fun admitExplicitly() {
            val selected=record().getJSONObject("selectedResult")
            ExactContinuationTestAdmission.admit(context,workspace.workspaceId,selected.getString("resultReferenceId"),
                selected.getString("contentSha256"),assignment.revision)
        }
        fun retainedSourceSnapshot():String {
            val saved=checkpoints.load(workspace.executionSessionId,bot)!!
            val selected=record().getJSONObject("selectedResult")
            val evidence=store.readCrewResultEvidence(workspace.executionSessionId,selected.getString("evidenceId"))!!
            return WorkspaceRegistry(context).use { registry ->
                listOf(evidence.toJson().toString(),saved.scopeIdentity,saved.artifactOwnership.toString(),
                    saved.loop.transcript.size.toString(),
                    registry.listAssignments(workspace.workspaceId).map { listOf(it.assignmentId,it.revision,it.state,it.botId,it.missionText,it.tools,it.updatedAt) }.toString(),
                    registry.listControls(workspace.workspaceId).toString()).joinToString("\n")
            }
        }
    }

    private fun service(calls:AtomicInteger,resultId:AtomicReference<String>,
                        expectedEpisode:AtomicReference<Episode>?=null):AgentForegroundService {
        val c=Robolectric.buildService(AgentForegroundService::class.java).create();controller=c
        val service=c.get()
        val provider=object:ModelProviderClient {
            override fun complete(systemPrompt:String,userPrompt:String,images:List<ScreenData>,tools:List<ToolSpec>,
                                  sessionId:String,token:CancellationToken):ModelReply=error("Conversation provider required")
            override fun completeConversation(systemPrompt:String,history:List<ConversationTurn>,userPrompt:String,
                                              images:List<ScreenData>,tools:List<ToolSpec>,sessionId:String,
                                              token:CancellationToken):ModelReply {
                assertEquals(central,sessionId)
                val call=calls.incrementAndGet()
                replyOverride?.let { return it(history) }
                val episode=expectedEpisode?.get() ?: return ModelReply("Synthetic Central synthesis",emptyList())
                val input=episode.input()
                val readCallId="read-exact-result-${episode.suffix}"
                val record=episode.record()
                val selected=record.getJSONObject("selectedResult")
                val turn=history.single { it.incomingMetadata?.provenance?.messageId==input.provenance!!.messageId }
                assertEquals(input.content,turn.content)
                assertEquals(ConversationInput.Origin.AGENT,turn.incomingMetadata.origin)
                assertEquals(ConversationInput.Provenance.PrincipalRole.SUBORDINATE,turn.incomingMetadata.provenance.principalRole)
                assertEquals(input.provenance!!.toJson().toString(),turn.incomingMetadata.provenance.toJson().toString())
                assertTrue(turn.content.contains("result_id=${selected.getString("resultReferenceId")}"))
                assertTrue(turn.content.contains("expected_sha256=${selected.getString("contentSha256")}"))
                if(call==1) {
                    assertTrue(tools.any { it.name==CentralWorkspaceTool.NAME })
                    return ModelReply("",listOf(ModelReply.Call(readCallId,CentralWorkspaceTool.NAME,mapOf(
                        "action" to "read_result","workspace_id" to episode.workspace.workspaceId,
                        "result_id" to selected.getString("resultReferenceId"),
                        "expected_sha256" to selected.getString("contentSha256")))))
                }
                assertEquals("No additional generation/model retries expected",2,call)
                val result=history.single { it.kind==ConversationTurn.Kind.TOOL_RESULT && it.toolCallId==readCallId }
                assertEquals(CentralWorkspaceTool.NAME,result.toolName)
                val data=JSONObject(result.content).getJSONObject("data")
                assertEquals(selected.getString("resultReferenceId"),data.getString("referenceId"))
                assertEquals(selected.getString("contentSha256"),data.getString("contentSha256"))
                assertEquals("Synthetic itinerary evidence",data.getString("result_text"))
                assertTrue(data.getBoolean("is_prior_evidence"))
                assertFalse(data.getBoolean("current_generation_verified"))
                assertFalse(data.getBoolean("automatic_work_replay"))
                return ModelReply("Central synthesis from retained evidence: ${data.getString("result_text")}",emptyList())
            }
        }
        service.runtimeFactory=AgentForegroundService.RuntimeFactory { cxt,id,skills ->
            try { CoreAgentRuntime(cxt,id,skills,CorePromptBudget.standard(),provider) }
            catch (failure: RuntimeException) { throw AssertionError("Real runtime construction failed", failure) }
        }
        service.mainChatRunner=AgentForegroundService.MainChatRunner { runtime,goal,history,attachments,token,progress ->
            try { runtime.runMainChat(goal,history,attachments,token,progress).also { resultId.set(it.runId) } }
            catch (failure: RuntimeException) { throw AssertionError("Real runtime failed before synthesis", failure) }
        }
        return service
    }
    private fun runStored(service:AgentForegroundService,id:String) {
        val method=AgentForegroundService::class.java.getDeclaredMethod("runRealAgent",CancellationToken::class.java,
            String::class.java,List::class.java,String::class.java,String::class.java,
            Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,String::class.java).apply { isAccessible=true }
        storage.onDatabaseWorker {
            val token=StopController.getInstance().beginRun()
            try { method.invoke(service,token,"",emptyList<String>(),central,"",true,false,id) }
            finally { token.completeRun() }
        }
    }
    private fun rows(sql:String,vararg args:Any?):List<List<String?>> = storage.onDatabaseWorker { runBlocking {
        val db=JarvysChatDatabaseProvider.getInstance(context)
        JarvysChatDatabaseProvider.onQueryExecutor(db) { db.withChatReadTransaction {
            db.chatSql.query(sql,args).use { c -> buildList {
                while(c.moveToNext()) add((0 until c.columnCount).map { if(c.isNull(it)) null else c.getString(it) })
            } }
        } }
    } }

    @Test fun claimedPayloadValidationKeepsProtectionAndRejectsAlterationDuplicationAndWrongToken() {
        val service=service(AtomicInteger(),AtomicReference<String>())
        storage.onDatabaseWorker {
            val episode=Episode()
            episode.admitExplicitly()
            val token=StopController.getInstance().beginRun()
            try {
                val input=episode.input()
                assertTrue(WorkspaceContinuationDelivery.claim(context,input,token))
                val runtime=service.runtimeFactory.create(context,central,emptyList())
                runtime.bindClaimedContinuation(input,token)
                val ordinary=episode.store.loadConversationContext(central)
                val source=ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE,central,input.messageId,"")
                fun turn(content:String=input.content)=ConversationTurn.internalInput(content,-1,
                    ConversationTurn.IncomingMetadata.forInternalInput(input.provenance),input.messageId)
                val exact=turn().also { it.bindSource(source) }
                val history=ordinary+exact
                val projection=runtime.validateRuntimeProtectedContext(history,token)
                assertTrue("Claim remains protected from compaction",projection.protectedSources.contains(source))
                assertSame(exact,history.last())
                assertEquals(input.content,history.last().content)
                assertEquals("Compaction cannot cross the protected continuation",0,
                    projection.protectedPrefixLimit(history,emptySet()))
                fun sql(statement:String,vararg args:Any) = runBlocking {
                    val db=JarvysChatDatabaseProvider.getInstance(context)
                    db.withChatWriteTransaction { db.chatSql.execSQL(statement,args) }
                }
                // Test-only corruption of retained bytes must not be hidden by the filtered validator.
                sql("UPDATE message_parts SET payload=? WHERE conversation_id=? AND message_id=? AND kind='text'",
                    "Changed durable evidence",central,input.messageId)
                try {
                    assertThrows(IllegalStateException::class.java) {
                        runtime.validateRuntimeProtectedContext(history,token)
                    }
                } finally {
                    sql("UPDATE message_parts SET payload=? WHERE conversation_id=? AND message_id=? AND kind='text'",
                        input.content,central,input.messageId)
                }
                sql("UPDATE messages SET role='user' WHERE conversation_id=? AND message_id=?",central,input.messageId)
                try {
                    assertThrows(IllegalStateException::class.java) {
                        runtime.validateRuntimeProtectedContext(history,token)
                    }
                } finally {
                    sql("UPDATE messages SET role='agent' WHERE conversation_id=? AND message_id=?",central,input.messageId)
                }
                val provenancePart=runBlocking {
                    val db=JarvysChatDatabaseProvider.getInstance(context)
                    db.withChatReadTransaction { db.chatSql.query(
                        "SELECT payload FROM message_parts WHERE conversation_id=? AND message_id=? AND kind=?",
                        arrayOf(central,input.messageId,ConversationInput.PROVENANCE_PART_KIND)).use { cursor ->
                        check(cursor.moveToFirst());cursor.getString(0)
                    } }
                }
                val changedProvenance=JSONObject(provenancePart).also {
                    it.getJSONObject("provenance").put("sessionId","other-worker")
                }.toString()
                sql("UPDATE message_parts SET payload=? WHERE conversation_id=? AND message_id=? AND kind=?",
                    changedProvenance,central,input.messageId,ConversationInput.PROVENANCE_PART_KIND)
                try {
                    assertThrows(IllegalStateException::class.java) {
                        runtime.validateRuntimeProtectedContext(history,token)
                    }
                } finally {
                    sql("UPDATE message_parts SET payload=? WHERE conversation_id=? AND message_id=? AND kind=?",
                        provenancePart,central,input.messageId,ConversationInput.PROVENANCE_PART_KIND)
                }
                val foreign=ConversationTurn("assistant","Unrelated payload").also {
                    it.bindSource(ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE,central,"unknown-source",""))
                }
                assertThrows(IllegalStateException::class.java) {
                    runtime.validateRuntimeProtectedContext(history+foreign,token)
                }
                assertThrows(IllegalStateException::class.java) {
                    runtime.validateRuntimeProtectedContext(ordinary+turn("Changed evidence"),token)
                }
                assertThrows(IllegalStateException::class.java) {
                    runtime.validateRuntimeProtectedContext(history+turn(),token)
                }
                assertThrows(IllegalStateException::class.java) {
                    runtime.validateRuntimeProtectedContext(ordinary,token)
                }
                assertThrows(IllegalStateException::class.java) {
                    runtime.validateRuntimeProtectedContext(history,CancellationToken.crewChild())
                }
                val wrongSource=turn().also { it.bindSource(ConversationTurn.Source(
                    ConversationTurn.Source.Kind.MESSAGE,central,"other-message","")) }
                assertThrows(IllegalStateException::class.java) {
                    runtime.validateRuntimeProtectedContext(ordinary+wrongSource,token)
                }
                WorkspaceContinuationDelivery.cancel(context,episode.workspace.workspaceId,episode.bot)
                assertThrows(RuntimeException::class.java) {
                    runtime.validateRuntimeProtectedContext(history,token)
                }
            } finally { token.completeRun() }
        }
    }

    @Test fun claimedPayloadValidationRejectsStaleProcessPendingControlAndChangedRevision() {
        val service=service(AtomicInteger(),AtomicReference<String>())
        storage.onDatabaseWorker {
            for (mutation in listOf("process","pending-control","revision","released-owner")) {
                val episode=Episode()
                episode.admitExplicitly()
                val token=StopController.getInstance().beginRun()
                try {
                    val input=episode.input()
                    assertTrue(WorkspaceContinuationDelivery.claim(context,input,token))
                    val runtime=service.runtimeFactory.create(context,central,emptyList())
                    runtime.bindClaimedContinuation(input,token)
                    val history=episode.store.loadConversationContext(central)+ConversationTurn.internalInput(
                        input.content,-1,ConversationTurn.IncomingMetadata.forInternalInput(input.provenance),input.messageId)
                    runtime.validateRuntimeProtectedContext(history,token)
                    when(mutation) {
                        "process" -> {
                            val saved=episode.checkpoints.confirmDurable(episode.workspace.executionSessionId,episode.bot,
                                "native-workspace:${episode.workspace.workspaceId}")!!
                            val record=episode.record()
                            record.put("revision",record.getLong("revision")+1).put("dispatchOwnerProcessId","lost-process")
                            assertThrows(IllegalArgumentException::class.java) {
                                episode.checkpoints.updateCentralContinuation(saved,CentralContinuationRecord.fromJson(record))
                            }
                            // Native CAS prohibits changing a claimed process owner in the first place.
                            runtime.validateRuntimeProtectedContext(history,token)
                            continue
                        }
                        "released-owner" -> WorkspaceContinuationDelivery.releaseClaim(input,token)
                        else -> WorkspaceRegistry(context).use { registry ->
                            val control=registry.nativeControls().submit(UUID.randomUUID().toString(),episode.workspace.workspaceId,
                                episode.assignment.assignmentId,episode.bot,episode.assignment.revision,
                                WorkspaceControlType.PAUSE,"")
                            if(mutation=="revision") {
                                val started=registry.markControlStarted(control.requestId,control.revision,"fixture")
                                registry.completeControl(started.requestId,started.revision)
                                assertTrue(registry.getAssignment(episode.workspace.workspaceId,episode.assignment.assignmentId)!!.revision>
                                    episode.assignment.revision)
                            }
                        }
                    }
                    assertThrows("$mutation must revoke the exact live source",
                            if (mutation == "released-owner") IllegalArgumentException::class.java else IllegalStateException::class.java) {
                        runtime.validateRuntimeProtectedContext(history,token)
                    }
                } finally { token.completeRun() }
            }
        }
    }

    @Test fun claimedServiceProviderRunUsesPersistedGenerationAndDuplicateNeverRuns() {
        val calls=AtomicInteger();val run=AtomicReference<String>();val expectedEpisode=AtomicReference<Episode>()
        val service=service(calls,run,expectedEpisode)
        val episode=storage.onDatabaseWorker { Episode().also { it.admitExplicitly() } };expectedEpisode.set(episode)
        val sourceBefore=storage.onDatabaseWorker { episode.retainedSourceSnapshot() }
        val childBatchesBefore=rows("SELECT batch_id FROM tool_batches WHERE conversation_id=?",episode.workspace.executionSessionId)
        val centralBatchesBefore=rows("SELECT batch_id FROM tool_batches WHERE conversation_id=?",central)
        storage.onDatabaseWorker {
            val pending=episode.store.readPendingInternalConversationInputs(central)
            assertNull("App-open/generic recovery cannot infer even PREPARED",AgentForegroundService.firstEligiblePendingAgentInput(context,central,pending))
            assertEquals(episode.inputId,AgentForegroundService.admittedInternalInputId(context,central,episode.input(),pending))
        }
        runStored(service,episode.inputId)
        assertEquals("One generation must consult exact result then synthesize; service report="+AgentForegroundService.getLastRunReport(),2,calls.get())
        assertEquals(childBatchesBefore,rows("SELECT batch_id FROM tool_batches WHERE conversation_id=?",episode.workspace.executionSessionId))
        assertEquals(sourceBefore,storage.onDatabaseWorker { episode.retainedSourceSnapshot() })
        val record=storage.onDatabaseWorker { episode.record() }
        val expected=record.getString("generationRunId")
        assertEquals(expected,run.get())
        assertEquals(listOf(listOf(expected,"completed")),rows("SELECT id,state FROM generation_run WHERE id=?",expected))
        assertEquals(listOf(listOf(expected,episode.inputId)),rows("SELECT run_id,owner_user_message_id FROM messages WHERE stable_id=?",record.getString("assistantMessageStableId")))
        val assistantsBefore=rows("SELECT message_id FROM messages WHERE conversation_id=? AND role='assistant'",central)
        val batchesBefore=rows("SELECT batch_id FROM tool_batches WHERE conversation_id=?",central)
        assertEquals(centralBatchesBefore.size+1,batchesBefore.size)
        runStored(service,episode.inputId)
        assertEquals(2,calls.get())
        assertEquals(batchesBefore,rows("SELECT batch_id FROM tool_batches WHERE conversation_id=?",central))
        assertEquals(assistantsBefore,rows("SELECT message_id FROM messages WHERE conversation_id=? AND role='assistant'",central))
        assertEquals("incorporated",storage.onDatabaseWorker { episode.store.readConversationInputState(central,episode.inputId) })
    }

    private fun explicitCommand(service:AgentForegroundService,episode:Episode,text:String,
                                newerUser:Boolean=false, workspaceOverride:String?=null):JSONObject {
        val selected=storage.onDatabaseWorker { episode.record().getJSONObject("selectedResult") }
        val user=storage.onDatabaseWorker { episode.store.appendConversationMessage(central,"user",text) }
        if(newerUser) storage.onDatabaseWorker { episode.store.appendConversationMessage(central,"user","Newer unrelated instruction") }
        val observed=AtomicReference<String>()
        // Provider call IDs are unique across the retained conversation, not only one run.
        val callId="explicit-continue-${UUID.randomUUID()}"
        var step=0
        replyOverride={ history ->
            if(step++==0) ModelReply("",listOf(ModelReply.Call(callId,CentralWorkspaceTool.NAME,mapOf(
                "action" to "continue_result","workspace_id" to (workspaceOverride ?: episode.workspace.workspaceId),
                "result_id" to selected.getString("resultReferenceId"),
                "expected_sha256" to selected.getString("contentSha256"),
                "expected_assignment_revision" to episode.assignment.revision))))
            else {
                observed.set(history.last { it.kind==ConversationTurn.Kind.TOOL_RESULT && it.toolCallId==callId }.content)
                ModelReply("Explicit continuation request processed",emptyList())
            }
        }
        try { runStored(service,user) } finally { replyOverride=null }
        assertNotNull("Actual tool result must reach the user-command provider; service report="+AgentForegroundService.getLastRunReport(),observed.get())
        return runCatching { JSONObject(observed.get()) }.getOrElse { JSONObject().put("failure",observed.get()) }
    }

    @Test fun retainedEpisodeCannotBorrowUnrelatedWorkspaceWakeAfterReopen() {
        val calls=AtomicInteger();val run=AtomicReference<String>();val expected=AtomicReference<Episode>()
        val service=service(calls,run,expected)
        // A models crash after durable result/selection but before any process-local dispatch claim.
        val a=storage.onDatabaseWorker { Episode() }
        val retained=storage.onDatabaseWorker { a.record().toString() }
        storage.onDatabaseWorker {
            ChatDatabaseBridge.closeInstanceForTests(context.filesDir)
            a.store=LocalRunStore(context) // New process facade, never reuse a closed Room bridge.
        }
        val b=storage.onDatabaseWorker { Episode(a.workspace) }
        val executor=WorkspaceResultDelivery::class.java.getDeclaredField("executor").apply { isAccessible=true }
            .get(null) as java.util.concurrent.ExecutorService
        fun drainReceipts() { repeat(3) { executor.submit {}.get(10,java.util.concurrent.TimeUnit.SECONDS) } }
        val shadow=org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
        while(shadow.nextStartedService!=null) { }
        // Native delegation/checkpoint/result catch-up use this same schedule route. Neither B nor
        // catalog/open/read may convert A's retained evidence into fresh execution authority.
        WorkspaceResultDelivery.schedule(context,b.workspace.executionSessionId)
        WorkspaceResultDelivery.scheduleCatalog(context)
        drainReceipts()
        storage.onDatabaseWorker {
            WorkspaceResultDelivery(context).reconcile(a.workspace.workspaceId,CancellationToken.uncancellable())
            WorkspaceResultDelivery(context).list(a.workspace.workspaceId,50,null,Runnable {})
            assertFalse(WorkspaceContinuationDelivery.isPendingDispatchEligible(context,a.input()))
            assertFalse(WorkspaceContinuationDelivery.isPendingDispatchEligible(context,b.input()))
            assertNull(AgentForegroundService.firstEligiblePendingAgentInput(context,central,
                a.store.readPendingInternalConversationInputs(central)))
        }
        assertNull("Unrelated schedules and catalog must not enqueue retained A or B",shadow.nextStartedService)
        repeat(2) { runStored(service,a.inputId) } // stale/delayed service intent is not authority either.
        assertEquals(0,calls.get())
        assertEquals(retained,storage.onDatabaseWorker { a.record().toString() })
        storage.onDatabaseWorker {
            assertEquals("PREPARED",a.record().getString("dispatchState"))
            assertEquals(0,a.record().getJSONArray("centralAdmissions").length())
            assertEquals("PREPARED",b.record().getString("dispatchState"))
            assertNull(a.store.readConversationInputState(central,a.inputId))
        }
        val selected=storage.onDatabaseWorker { a.record().getJSONObject("selectedResult") }
        explicitCommand(service,a,"/continue_result ${selected.getString("resultReferenceId")}").getJSONObject("data")
        calls.set(0);expected.set(a)
        runStored(service,a.inputId);runStored(service,a.inputId)
        assertEquals(2,calls.get()) // exact result read, then synthesis, once.
        storage.onDatabaseWorker {
            assertEquals("ACKNOWLEDGED",a.record().getString("dispatchState"))
            assertEquals("incorporated",a.store.readConversationInputState(central,a.inputId))
            assertEquals("PREPARED",b.record().getString("dispatchState"))
            assertNull(b.store.readConversationInputState(central,b.inputId))
        }
        drainReceipts()
    }

    @Test fun freshExplicitUserCommandAdmitsRetainedResultThenOneExactSynthesis() {
        val calls=AtomicInteger();val run=AtomicReference<String>();val expected=AtomicReference<Episode>()
        val service=service(calls,run,expected)
        val episode=storage.onDatabaseWorker { Episode() }
        val selected=storage.onDatabaseWorker { episode.record().getJSONObject("selectedResult") }
        val result=explicitCommand(service,episode,"/continue_result ${selected.getString("resultReferenceId")}").getJSONObject("data")
        assertTrue(result.getBoolean("queued"));assertFalse(result.getBoolean("replayed"))
        assertEquals(episode.inputId,result.getString("input_message_id"))
        storage.onDatabaseWorker {
            assertEquals(1,episode.record().getJSONArray("centralAdmissions").length())
            assertEquals("PREPARED",episode.record().getString("dispatchState"))
            assertNull(AgentForegroundService.firstEligiblePendingAgentInput(context,central,episode.store.readPendingInternalConversationInputs(central)))
            assertEquals(episode.inputId,AgentForegroundService.admittedInternalInputId(context,central,episode.input(),episode.store.readPendingInternalConversationInputs(central)))
        }
        val shadow=org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
        val queued=generateSequence { shadow.nextStartedService }.toList()
        assertTrue("Explicit tool must enqueue exact retained source ID",queued.any { it.getStringExtra("chat_user_message_id")==episode.inputId })
        calls.set(0);expected.set(episode)
        runStored(service,episode.inputId)
        assertEquals(2,calls.get())
        runStored(service,episode.inputId)
        assertEquals(2,calls.get())
    }

    @Test fun explicitContinuationRejectsWrongCommandStaleUserAndUncertainStarted() {
        val calls=AtomicInteger();val run=AtomicReference<String>();val service=service(calls,run)
        val episode=storage.onDatabaseWorker { Episode() }
        val selected=storage.onDatabaseWorker { episode.record().getJSONObject("selectedResult") }
        explicitCommand(service,episode,"Please read something else")
        assertEquals(0,storage.onDatabaseWorker { episode.record().getJSONArray("centralAdmissions").length() })
        explicitCommand(service,episode,"/continue_result ${selected.getString("resultReferenceId")}",true)
        assertEquals(0,storage.onDatabaseWorker { episode.record().getJSONArray("centralAdmissions").length() })
        storage.onDatabaseWorker { episode.admitExplicitly(); assertTrue(WorkspaceContinuationDelivery.claim(context,episode.input(),CancellationToken.crewChild())) }
        explicitCommand(service,episode,"/continue_result ${selected.getString("resultReferenceId")}")
        storage.onDatabaseWorker {
            assertEquals(1,episode.record().getJSONArray("centralAdmissions").length())
            assertEquals("STARTED",episode.record().getString("dispatchState"))
            assertNull(episode.store.readConversationInputState(central,episode.inputId))
        }
    }

    @Test fun queuedExplicitAuthorityBecomesStaleAndFreshCommandRecoversSameInput() {
        val calls=AtomicInteger();val run=AtomicReference<String>();val expected=AtomicReference<Episode>()
        val service=service(calls,run,expected)
        val episode=storage.onDatabaseWorker { Episode() }
        val selected=storage.onDatabaseWorker { episode.record().getJSONObject("selectedResult") }
        val command="/continue_result ${selected.getString("resultReferenceId")}"
        explicitCommand(service,episode,command).getJSONObject("data")
        storage.onDatabaseWorker { episode.store.appendConversationMessage(central,"user","Newer instruction supersedes pending admission") }
        val before=calls.get()
        storage.onDatabaseWorker {
            assertEquals("",AgentForegroundService.admittedInternalInputId(context,central,episode.input(),episode.store.readPendingInternalConversationInputs(central)))
        }
        runStored(service,episode.inputId)
        assertEquals(before,calls.get())
        assertEquals("PREPARED",storage.onDatabaseWorker { episode.record().getString("dispatchState") })
        explicitCommand(service,episode,command).getJSONObject("data")
        assertEquals(2,storage.onDatabaseWorker { episode.record().getJSONArray("centralAdmissions").length() })
        calls.set(0);expected.set(episode)
        runStored(service,episode.inputId);assertEquals(2,calls.get())
    }

    @Test fun explicitResultCannotCrossWorkspaceOwner() {
        val calls=AtomicInteger();val run=AtomicReference<String>();val service=service(calls,run)
        val episode=storage.onDatabaseWorker { Episode() };val other=storage.onDatabaseWorker { Episode() }
        val selected=storage.onDatabaseWorker { episode.record().getJSONObject("selectedResult") }
        explicitCommand(service,episode,"/continue_result ${selected.getString("resultReferenceId")}",workspaceOverride=other.workspace.workspaceId)
        storage.onDatabaseWorker {
            assertEquals(0,episode.record().getJSONArray("centralAdmissions").length())
            assertEquals(0,other.record().getJSONArray("centralAdmissions").length())
            assertEquals("PREPARED",episode.record().getString("dispatchState"))
            assertEquals("PREPARED",other.record().getString("dispatchState"))
        }
    }

    @Test fun stopQueuedProtocolCancelsPreparedWithoutAckAndDuplicateCannotInfer() {
        val calls=AtomicInteger();val run=AtomicReference<String>();val service=service(calls,run)
        val episode=storage.onDatabaseWorker { Episode() }
        val user=storage.onDatabaseWorker {
            episode.admitExplicitly()
            // Hold the same latest USER turn; a newer unrelated USER would correctly revoke admission.
            episode.store.latestUserMessageId(central)!!
        }
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        replyOverride={ _ ->
            entered.countDown()
            release.await(10,java.util.concurrent.TimeUnit.SECONDS)
            ModelReply("Foreground released",emptyList())
        }
        try {
            service.onStartCommand(AgentForegroundService.storedChatMessageIntent(context,central,user),0,1)
            assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS))
            service.onStartCommand(AgentForegroundService.storedChatMessageIntent(context,central,episode.inputId),0,2)
            AgentForegroundService.stopRun(central)
            service.awaitStopTasksForTesting().get(10,java.util.concurrent.TimeUnit.SECONDS)
            release.countDown()
            service.awaitSessionIdleForTesting(central).get(10,java.util.concurrent.TimeUnit.SECONDS)
            storage.onDatabaseWorker {
                assertEquals("CANCELLED",episode.record().getString("dispatchState"))
                assertNull(episode.store.readConversationInputState(central,episode.inputId))
            }
            val before=calls.get();replyOverride=null
            service.onStartCommand(AgentForegroundService.storedChatMessageIntent(context,central,episode.inputId),0,3)
            service.awaitSessionIdleForTesting(central).get(10,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(before,calls.get())
        } finally { release.countDown();replyOverride=null;AgentForegroundService.stopRun(central) }
    }

    @Test fun uncertainCancelledAndMalformedClaimsDoNotStarveOrdinaryAgentOrFabricateAck() {
        val calls=AtomicInteger();val run=AtomicReference<String>();val service=service(calls,run)
        val started=storage.onDatabaseWorker { Episode() }
        val cancelled=storage.onDatabaseWorker { Episode() }
        val malformed=storage.onDatabaseWorker {
            val source=UUID.randomUUID().toString()
            LocalRunStore(context).appendConversationInput(ConversationInput(central,source,"Malformed continuation",
                ConversationInput.Origin.AGENT,ConversationInput.Provenance("invalid-source","","","",source,
                    ConversationInput.Kind.RESULT,ConversationInput.Provenance.PrincipalRole.SUBORDINATE,
                    "central-continuation:malformed","","",0L,"","","")))
        }
        val ordinary=storage.onDatabaseWorker {
            started.admitExplicitly()
            assertTrue(WorkspaceContinuationDelivery.claim(context,started.input(),CancellationToken.crewChild()))
            WorkspaceContinuationDelivery.cancel(context,cancelled.workspace.workspaceId,cancelled.bot)
            val source=UUID.randomUUID().toString()
            LocalRunStore(context).appendConversationInput(ConversationInput(central,source,"Ordinary follow-up",
                ConversationInput.Origin.AGENT,ConversationInput.Provenance("worker-session","","","",source,ConversationInput.Kind.MESSAGE)))
        }
        val before=rows("SELECT message_id FROM messages WHERE conversation_id=? AND role='assistant'",central)
        repeat(3) { runStored(service,started.inputId);runStored(service,cancelled.inputId);runStored(service,malformed) }
        assertEquals(0,calls.get())
        assertEquals(before,rows("SELECT message_id FROM messages WHERE conversation_id=? AND role='assistant'",central))
        val eligible=storage.onDatabaseWorker {
            AgentForegroundService.firstEligiblePendingAgentInput(context,central,
                LocalRunStore(context).readPendingInternalConversationInputs(central))
        }
        assertEquals(ordinary,eligible!!.getString("messageId"))
        runStored(service,ordinary)
        assertEquals(1,calls.get())
        storage.onDatabaseWorker {
            assertNull(started.store.readConversationInputState(central,started.inputId))
            assertNull(cancelled.store.readConversationInputState(central,cancelled.inputId))
            assertNull(started.store.readConversationInputState(central,malformed))
            assertEquals("incorporated",started.store.readConversationInputState(central,ordinary))
        }
    }
}
