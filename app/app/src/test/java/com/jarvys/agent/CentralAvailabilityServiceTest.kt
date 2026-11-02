package com.jarvys.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.providers.ChatCompletionsConfig
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceRegistry
import com.jarvys.agent.workspaces.WorkspaceTreeStore
import com.jarvys.agent.workspaces.TreeRoot
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** UNRUN enabled-only acceptance: actual native UI authority, Room, serial service, sealed synthetic sockets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=TreeNativeTestApplication::class,shadows=[ArtifactOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CentralAvailabilityServiceTest {
    private val storage=ChatTestStore(runTestOnWorker=false,registerApplicationFilesDirectory=true)
    @get:Rule val rules:RuleChain=RuleChain.outerRule(storage).around(TestSecretStoreRule())
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val session=WorkspaceIdentity.CENTRAL_SESSION
    private lateinit var service:AgentForegroundService
    private lateinit var drafts:AttachmentDraftViewModel
    private var controller:org.robolectric.android.controller.ServiceController<AgentForegroundService>?=null
    private val calls=AtomicInteger()
    private val failure=AtomicReference<Throwable>()
    private val firstEntered=CountDownLatch(1)
    private val releaseFirst=CountDownLatch(1)
    private val staleReturned=CountDownLatch(1)
    private val requests=java.util.Collections.synchronizedList(mutableListOf<String>())
    private var startId=0
    private inner class Socket(private val index:Int):HttpURLConnection(URL("https://example.invalid/fixture")) {
        private val request=ByteArrayOutputStream()
        override fun getOutputStream()=request
        override fun getResponseCode():Int {
            requests.add(request.toString("UTF-8"))
            if(index==1) {
                firstEntered.countDown()
                while(releaseFirst.count>0)try {releaseFirst.await()}catch(_:InterruptedException){}
                staleReturned.countDown()
            }
            return 200
        }
        override fun getInputStream()=ByteArrayInputStream(when(index) {
            1 -> """{"choices":[{"message":{"role":"assistant","content":"STALE PROPOSAL","tool_calls":[{"id":"stale-effect","type":"function","function":{"name":"crew_spawn","arguments":"{}"}}]}}]}"""
            2 -> """{"choices":[{"message":{"role":"assistant","content":"Fresh request completed"}}]}"""
            else -> """{"choices":[{"message":{"role":"assistant","content":"Original request resumed"}}]}"""
        }.toByteArray())
        override fun disconnect(){} // Deliberately cannot stop the synthetic remote operation.
        override fun connect(){}
        override fun usingProxy()=false
    }
    @Before fun setup() {
        check(CentralAvailabilityPolicy.admissionEnabled()) {"Requires explicit post-D3 local acceptance activation"}
        storage.localRunStore(context.filesDir)
        storage.onDatabaseWorker {
            FreshStorageBootstrap.ensure(context)
            (context as TreeNativeTestApplication).connectionAccess().initializeAsync().get()
            check((context as TreeNativeTestApplication).connectionAccess().isReady())
            ChatDatabaseBridge.getInstance(context).ensureConversationSync(session,"chat")
        }
        val settings=ProviderSettings(context).apply {setProvider(ProviderSettings.Provider.OPENROUTER);setOpenRouterModel("offline/availability")}
        check(context.getSharedPreferences("provider_context_metadata",0).edit().putInt("model_offline/availability",32768)
            .putLong("fetched_offline/availability",System.currentTimeMillis()).commit())
        val secrets=SecretStore(context.getSharedPreferences("availability-fixture-secret",0)).apply {saveOpenRouterKey("synthetic-test-only")}
        val provider=OpenRouterClient(secrets,settings,ChatCompletionsConfig.openRouterDefault(),ProviderHttp.ConnectionFactory {Socket(calls.incrementAndGet())})
        AgentRunUiState.resetSession(session)
        controller=Robolectric.buildService(AgentForegroundService::class.java).create();service=controller!!.get()
        service.runtimeFactory=AgentForegroundService.RuntimeFactory {c,id,skills ->CoreAgentRuntime(c,id,skills,CorePromptBudget.standard(),provider)}
        service.mainChatRunner=AgentForegroundService.MainChatRunner {runtime,goal,history,attachments,token,progress ->
            try {runtime.runMainChat(goal,history,attachments,token,progress)}
            catch(cancelled:java.util.concurrent.CancellationException){throw cancelled}
            catch(problem:Throwable){failure.set(problem);throw AssertionError("Native availability runtime failed",problem)}
        }
        drafts=AttachmentDraftViewModel(context as Application).apply {switchSession(session)}
    }
    @After fun cleanup() {
        releaseFirst.countDown();StopController.stopAllRuns()
        controller?.let {runCatching {it.destroy()}}
        context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
    }
    private fun pump() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val app=Shadows.shadowOf(context as Application)
        var next=app.nextStartedService
        while(next!=null){service.onStartCommand(next,0,++startId);next=app.nextStartedService}
    }
    private fun await(description:String,predicate:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25)
        while(!predicate()) {
            failure.get()?.let {throw AssertionError(description,it)}
            pump();check(System.nanoTime()<end){"$description; ${AgentForegroundService.getLastRunReport()}"};Thread.sleep(10)
        }
    }
    private fun rows()=storage.onDatabaseWorker {LocalRunStore(context).readConversationMessages(session)}
    private fun submissions()=storage.onDatabaseWorker {LocalRunStore(context).readCentralSubmissions(session)}
    private fun root(origin:String):TreeRoot=storage.onDatabaseWorker {WorkspaceRegistry(context).use {registry ->
        val id=registry.treeRead {db -> db.rawQuery("SELECT root_id FROM bot_tree_root WHERE origin_user_message_id=?",arrayOf(origin)).use {rows ->
            check(rows.moveToFirst());val id=rows.getString(0);check(!rows.moveToNext());id
        }}
        requireNotNull(WorkspaceTreeStore(registry).getRoot(id))
    }}
    private fun modelRows()=storage.onDatabaseWorker {LocalRunStore(context).readModelTranscriptRows(session)}
    private fun send(text:String):String {
        assertTrue(drafts.submit(session,text,arrayListOf()))
        await("Native Send atomically persisted its exact row"){rows().any {it.optString("role")=="user" && it.optString("content")==text}}
        return rows().single {it.optString("role")=="user" && it.optString("content")==text}.getString("messageId")
    }
    private fun attachmentWriteFailure(committedReceiptKnown:Boolean) {
        val completed=CountDownLatch(1)
        val actual=AtomicReference<com.jarvys.agent.chatdb.CentralSubmissionAppendReceipt>()
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        val custom=object:AttachmentMessageSubmitter {
            override fun persistMessage(application:Application,session:String,text:String,attachments:List<ChatAttachment>):String = error("No legacy fallback")
            override fun startAgent(application:Application,session:String,messageId:String,skills:ArrayList<String>) = error("No generic dispatch")
            override fun persistNativeMessage(application:Application,action:AttachmentDraftViewModel.NativeSendAction):CentralSubmissionAdmission.LivePermit {
                try {
                    CentralSubmissionAdmission.beginSend(action).use {authority ->
                        WorkspaceRegistry(context).use {it.upgradeCentralResumeSchema()}
                        authority.markWriteAttempted()
                        val receipt=LocalRunStore(context).appendCentralSubmission(authority)
                        actual.set(receipt)
                        if(committedReceiptKnown) {
                            authority.recordCommitted(receipt)
                            drafts.cancelSubmission(session)
                            return CentralSubmissionAdmission.register(authority,receipt) // Must fail exact cancelled action.
                        }
                        throw IllegalStateException("Synthetic lost commit acknowledgement")
                    }
                } finally {completed.countDown()}
            }
        }
        drafts=AttachmentDraftViewModel(context as Application,custom,executor).apply {switchSession(session)}
        val owner=androidx.lifecycle.ViewModelStore().apply {put("drafts",drafts)}
        try {
            val input=java.io.File(context.cacheDir,"jarvys/camera/availability-attachment.txt").apply {parentFile!!.mkdirs();writeText("Immutable attachment")}
            drafts.addUris(session,listOf(androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".generated-images",input)))
            await("Attachment copied before native Send") {
                val pending=drafts.drafts.value.singleOrNull();check(pending?.error==null) {"Native content attachment copy failed: ${pending?.error}"};pending?.attachment!=null
            }
            val attachment=requireNotNull(drafts.drafts.value.single().attachment)
            val storedFile=AttachmentStore(context).resolve(session,attachment)
            assertTrue(drafts.submit(session,"Retain committed bytes",arrayListOf()))
            assertTrue(completed.await(10,TimeUnit.SECONDS))
            await("Native failed handoff settled without granting a permit") {!drafts.isSending(session)}
            drafts.switchSession("different-session");owner.clear()
            val receipt=requireNotNull(actual.get())
            assertNull(CentralSubmissionAdmission.find(session,receipt.snapshot.submissionId))
            assertEquals("PREPARED",submissions().single().state)
            assertTrue("Actual referenced bytes survive failed/uncertain handoff",storedFile.isFile)
            assertEquals("Immutable attachment",storedFile.readText())
            val references=storage.onDatabaseWorker {LocalRunStore(context).readConversationAttachments(session,receipt.snapshot.userMessageId)}
            assertEquals(listOf(attachment.id),references.map {it.id})
            assertTrue(rows().any {it.optString("messageId")==receipt.snapshot.userMessageId})
            assertEquals(0,calls.get())
            if(committedReceiptKnown) assertEquals(receipt.snapshot.userMessageId,
                AgentRunUiState.snapshotForSession(session).events.single {it.kind=="user"}.messageId)
        } finally {owner.clear();executor.shutdownNow()}
    }
    @Test fun committedAppendThenRegistrationFailureRetainsExactUserAndAttachment() = attachmentWriteFailure(true)
    @Test fun uncertainAppendAcknowledgementNeverDeletesReferencedAttachmentOrMintsPermit() = attachmentWriteFailure(false)

    @Test fun canvasRecoveryRejectsStaleDisplayedFenceBeforeAndAfterMainQueue() {
        attachmentWriteFailure(false)
        val row=submissions().single()
        val event=AgentRunUiEvent.messageEvent(701,"user","Retain committed bytes",1).copy(messageId=row.userMessageId,
            centralAvailability=CentralAvailabilityDisplay(row,null,"PREPARED",true))
        val fence=requireNotNull(event.centralAvailability).recoveryFence()
        fun allowed(value:String)=com.jarvys.agent.canvas.ChatCanvasActionPolicy.check("retry",event,value,false,null,false).allowed
        assertTrue(allowed(fence));assertFalse(allowed(""))
        for(index in listOf(1,2,3,4,5,6,7)) {
            val changed=org.json.JSONArray(fence).put(index,if(index==5) row.revision+1 else "different-fence")
            assertFalse("Changed kind/session/source/id/revision/hash/lifetime is denied",allowed(changed.toString()))
        }
        var current=listOf(event);var dispatched=0
        val bridge=com.jarvys.agent.canvas.ChatCanvasNativeBridge(session,{current},{false},{true},
            hasOlder={false},loadingOlder={false},benchmarkStartedAtNanos=null,
            callbacks=com.jarvys.agent.canvas.ChatCanvasCallbacks(action={_,_,_->dispatched++}))
        bridge.dispatch("retry",event.id,fence)
        current=listOf(event.copy(centralAvailability=CentralAvailabilityDisplay(row.copy(revision=row.revision+1),null,"PREPARED",true)))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals("Queued tap cannot silently borrow new current descriptor",0,dispatched)
        bridge.dispatch("retry",event.id,fence)
        org.robolectric.shadows.ShadowLooper.idleMainLooper();assertEquals(0,dispatched)
        current=listOf(event)
        bridge.dispatch("retry",event.id,fence)
        org.robolectric.shadows.ShadowLooper.idleMainLooper();assertEquals(1,dispatched)
    }

    @Test fun freshSendSupersedesOnlyInferenceAndOriginalResumesWithoutFutureChildCallbacks() {
        val old=send("Original independent request")
        await("Original sealed inference entered"){firstEntered.count==0L}
        val fresh=send("Fresh independent request")
        await("Fresh then original both finish through distinct roots"){submissions().count {it.state=="COMPLETED"}==2}
        await("Serial service physically idle"){!AgentForegroundService.hasActiveRun(session)}
        assertEquals("No child event is needed for old-root handoff",3,calls.get())
        assertEquals("Original synthetic provider still physically held",1L,releaseFirst.count)
        val completed=rows().filter {it.optString("role")=="assistant" && it.optString("status")=="COMPLETED"}
        assertEquals(setOf(old,fresh),completed.map {it.getString("userMessageId")}.toSet())
        assertTrue(completed.any {it.getString("userMessageId")==fresh && it.getString("content")=="Fresh request completed"})
        assertTrue(completed.any {it.getString("userMessageId")==old && it.getString("content")=="Original request resumed"})
        assertFalse(rows().any {it.optString("content").contains("STALE PROPOSAL") || it.optString("kind")=="stale-effect"})
        repeat(3){AgentForegroundService.nudgeCentralHandoff(context)}
        service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS);pump()
        assertEquals(3,calls.get())
        releaseFirst.countDown();assertTrue(staleReturned.await(10,TimeUnit.SECONDS))
        assertEquals(3,calls.get())
        assertFalse(rows().any {it.optString("content").contains("STALE PROPOSAL")})
        assertFalse(modelRows().any {it.toString().contains("stale-effect") || it.toString().contains("STALE PROPOSAL")})
        val originalRoot=root(old);val freshRoot=root(fresh)
        assertNotEquals(originalRoot.rootId,freshRoot.rootId)
        assertEquals(old,originalRoot.origin.originUserMessageId)
        assertEquals(fresh,freshRoot.origin.originUserMessageId)
        listOf(originalRoot,freshRoot).forEach {
            assertEquals("COMPLETED",it.closeReason);assertTrue(it.admissionsClosed);assertTrue(it.wakesClosed)
            assertEquals(0L,it.windowEpoch);assertEquals(0L,it.requests);assertEquals(0L,it.wakes)
        }
        assertEquals("Original execution plus its one resumed execution",2L,originalRoot.executions)
        assertEquals("Fresh request has its own initial execution",1L,freshRoot.executions)
        storage.onDatabaseWorker {WorkspaceRegistry(context).use {registry ->registry.treeRead {db ->
            db.rawQuery("SELECT cause_kind,state,incorporation_receipt FROM bot_tree_central_resume WHERE root_id=?",arrayOf(originalRoot.rootId)).use {
                assertTrue(it.moveToFirst());assertEquals("SUSPENSION",it.getString(0));assertEquals("INCORPORATED",it.getString(1))
                assertFalse(it.getString(2).isNullOrBlank());assertFalse(it.moveToNext())
            }
            db.rawQuery("SELECT kind,COUNT(*) FROM bot_tree_event WHERE root_id=? AND kind IN ('CENTRAL_YIELD','CENTRAL_RESUME_INCORPORATED') GROUP BY kind",arrayOf(originalRoot.rootId)).use {
                val events=mutableMapOf<String,Int>();while(it.moveToNext())events[it.getString(0)]=it.getInt(1)
                assertEquals(mapOf("CENTRAL_YIELD" to 1,"CENTRAL_RESUME_INCORPORATED" to 1),events)
            }
        }}}

    }
}
