package com.jarvys.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.crew.*
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
class CentralAvailabilityBoundaryAcceptanceTest {
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
    private var holdFirst=true
    private var holdSecond=false
    private var holdThird=false
    private val thirdEntered=CountDownLatch(1)
    private val releaseThird=CountDownLatch(1)
    private var socketReply:((Int)->String)?=null
    private val secondEntered=CountDownLatch(1)
    private val releaseSecond=CountDownLatch(1)
    private val yields=java.util.Collections.synchronizedList(mutableListOf<MainChatTranscriptStore.ConfirmedCentralYield>())
    private val requests=java.util.Collections.synchronizedList(mutableListOf<String>())
    private var startId=0
    private inner class Socket(private val index:Int):HttpURLConnection(URL("https://example.invalid/fixture")) {
        private val request=ByteArrayOutputStream()
        override fun getOutputStream()=request
        override fun getResponseCode():Int {
            requests.add(request.toString("UTF-8"))
            if(index==1 && holdFirst) {
                firstEntered.countDown()
                while(releaseFirst.count>0)try {releaseFirst.await()}catch(_:InterruptedException){}
                staleReturned.countDown()
            }
            if(index==2 && holdSecond) {secondEntered.countDown();while(releaseSecond.count>0)try {releaseSecond.await()}catch(_:InterruptedException){}}
            if(index==3 && holdThird) {thirdEntered.countDown();while(releaseThird.count>0)try {releaseThird.await()}catch(_:InterruptedException){}}
            return 200
        }
        override fun getInputStream()=ByteArrayInputStream((socketReply?.invoke(index) ?: when(index) {
            1 -> """{"choices":[{"message":{"role":"assistant","content":"STALE PROPOSAL","tool_calls":[{"id":"stale-effect","type":"function","function":{"name":"crew_spawn","arguments":"{}"}}]}}]}"""
            2 -> """{"choices":[{"message":{"role":"assistant","content":"Fresh request completed"}}]}"""
            else -> """{"choices":[{"message":{"role":"assistant","content":"Original request resumed"}}]}"""
        }).toByteArray())
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
            try {runtime.runMainChat(goal,history,attachments,token,progress).also {if(it.isYielded) yields.add(it.yieldReceipt())}}
            catch(cancelled:java.util.concurrent.CancellationException){throw cancelled}
            catch(problem:Throwable){failure.set(problem);throw AssertionError("Native availability runtime failed",problem)}
        }
        drafts=AttachmentDraftViewModel(context as Application).apply {switchSession(session)}
    }
    @After fun cleanup() {
        releaseFirst.countDown();releaseSecond.countDown();releaseThird.countDown();StopController.stopAllRuns()
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
            val input=java.io.File(context.cacheDir,"availability-attachment.txt").apply {writeText("Immutable attachment")}
            drafts.addUris(session,listOf(android.net.Uri.fromFile(input)))
            await("Attachment copied before native Send") {drafts.drafts.value.singleOrNull()?.attachment!=null}
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



    private fun installDirectProvider(answer:(String,CancellationToken)->String) {
        val direct=object:ModelProviderClient {
            override fun complete(systemPrompt:String,userPrompt:String,images:List<com.jarvys.agent.device.ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply = error("Conversation path required")
            override fun completeConversation(systemPrompt:String,history:List<ConversationTurn>,userPrompt:String,images:List<com.jarvys.agent.device.ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply = ModelReply(answer(userPrompt,token),emptyList())
        }
        service.runtimeFactory=AgentForegroundService.RuntimeFactory {c,id,skills ->CoreAgentRuntime(c,id,skills,CorePromptBudget.standard(),direct)}
    }
    private fun stopSession() {
        AgentForegroundService.stopRun(session)
        service.awaitStopTasksForTesting().get(10,TimeUnit.SECONDS)
    }
    @Test fun positiveStopCohortDoesNotCancelLaterSendBeforeOldPhysicalOwnerReleases() {
        val oldEntered=CountDownLatch(1);val releaseOwner=CountDownLatch(1)
        val oldToken=AtomicReference<CancellationToken>();val providerCalls=AtomicInteger()
        installDirectProvider {_,token ->
            if(providerCalls.incrementAndGet()==1) {
                oldToken.set(token);oldEntered.countDown()
                while(releaseOwner.count>0)try {releaseOwner.await()}catch(_:InterruptedException){}
                "STALE AFTER STOP"
            } else {token.throwIfCancelled();"Fresh after STOP"}
        }
        try {
            val old=send("Old positive request")
            await("Old physical owner entered") {oldEntered.count==0L}
            val queued=send("Captured queued positive request")
            await("Queued NEW_TURN is durable") {submissions().any {it.userMessageId==queued && it.state=="PREPARED"}}
            stopSession()
            assertTrue(oldToken.get().wasStoppedByUser())
            assertEquals(setOf(old,queued),submissions().filter {it.state=="CANCELLED"}.map {it.userMessageId}.toSet())
            val fresh=send("Independent request after STOP")
            assertEquals("PREPARED",submissions().single {it.userMessageId==fresh}.state)
            assertEquals("No new provider before exact old owner physically exits",1,providerCalls.get())
            assertFalse(rows().any {it.optString("role")=="assistant" && it.optString("userMessageId")==fresh})
            releaseOwner.countDown()
            await("Fresh positive request executes once after physical release") {submissions().single {it.userMessageId==fresh}.state=="COMPLETED"}
            await("All lane work released") {!AgentForegroundService.hasActiveRun(session)}
            assertEquals(2,providerCalls.get())
            assertFalse(rows().any {it.optString("content").contains("STALE AFTER STOP")})
            assertEquals("STOPPED",root(old).closeReason)
            assertEquals("COMPLETED",root(fresh).closeReason)
            assertNotEquals(root(old).rootId,root(fresh).rootId)
            repeat(3){AgentForegroundService.nudgeCentralHandoff(context)}
            service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS);pump()
            assertEquals(2,providerCalls.get())
        } finally {releaseOwner.countDown()}
    }
    @Test fun reopenedPreparedRowHasNoPermissionUntilExactDisplayedNativeRetry() {
        attachmentWriteFailure(false) // Genuine native Send committed; no catalog permission was minted.
        val before=submissions().single()
        storage.onDatabaseWorker {ChatDatabaseBridge.closeInstanceForTests(context.filesDir)}
        val recoveredStore=storage.onDatabaseWorker {LocalRunStore(context)}
        val display=storage.onDatabaseWorker {recoveredStore.readRecentConversationTimeline(session)}
        AgentRunUiState.restoreSession(session,display)
        val selected=display.single {it.kind=="user" && it.messageId==before.userMessageId}
        assertTrue(requireNotNull(selected.centralAvailability).canRetry)
        repeat(3){pump();AgentForegroundService.nudgeCentralHandoff(context)}
        service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS);pump()
        assertEquals("Reading/reopening does not mint dispatch",0,calls.get())
        assertEquals(before,submissions().single())
        assertNull(CentralSubmissionAdmission.find(session,before.submissionId))
        val providerCalls=AtomicInteger()
        installDirectProvider {_,token ->token.throwIfCancelled();providerCalls.incrementAndGet();"Explicit recovery answer"}
        drafts=AttachmentDraftViewModel(context as Application).apply {switchSession(session)}
        assertTrue(drafts.recoverPreparedSubmission(selected))
        await("Exact existing source completes after native tap") {submissions().single().state=="COMPLETED"}
        await("Recovery lane idle") {!AgentForegroundService.hasActiveRun(session)}
        val after=submissions().single()
        assertEquals(before.submissionId,after.submissionId);assertEquals(before.userMessageId,after.userMessageId)
        assertEquals(before.sourceSha256,after.sourceSha256);assertEquals(before.storageGeneration,after.storageGeneration)
        assertTrue(after.revision>before.revision);assertEquals(1,providerCalls.get())
        assertEquals(1,rows().count {it.optString("role")=="user"})
        assertEquals("COMPLETED",root(before.userMessageId).closeReason)
        assertEquals(1L,root(before.userMessageId).executions)
    }
    @Test fun everyLiveNewTurnPrecedesSuspensionAndLosingNudgesDoNotDuplicateClaims() {
        val entered=CountDownLatch(1);val releaseOwner=CountDownLatch(1)
        val observations=java.util.Collections.synchronizedList(mutableListOf<List<String>>())
        val providerCalls=AtomicInteger()
        installDirectProvider {_,token ->
            val call=providerCalls.incrementAndGet()
            if(call==1) {
                entered.countDown()
                while(releaseOwner.count>0)try {releaseOwner.await()}catch(_:InterruptedException){}
            } else {
                token.throwIfCancelled()
                observations.add(CentralSubmissionAdmission.prepared(session).map {it.userMessageId})
            }
            "Native completed answer $call"
        }
        try {
            val old=send("Original request before two independent Sends")
            await("Initial owner entered") {entered.count==0L}
            val first=send("Earlier admitted independent request")
            val second=send("Later admitted independent request")
            await("Both actual live requests retained") {submissions().count {it.state=="PREPARED"}==2}
            repeat(4){AgentForegroundService.nudgeCentralHandoff(context)}
            assertEquals(1,providerCalls.get())
            releaseOwner.countDown()
            await("Every distinct admitted source eventually finishes") {submissions().count {it.state=="COMPLETED"}==3}
            await("Serial lane released") {!AgentForegroundService.hasActiveRun(session)}
            assertTrue("A parked continuation never reaches inference while a newer live NEW_TURN remains eligible",observations.all {it.isEmpty()})
            assertEquals(setOf(old,first,second),submissions().map {it.userMessageId}.toSet())
            val roots=listOf(old,first,second).map(::root)
            assertEquals(3,roots.map {it.rootId}.toSet().size)
            assertTrue(roots.all {it.closeReason=="COMPLETED" && it.windowEpoch==0L && it.requests==0L && it.wakes==0L})
            val count=providerCalls.get()
            repeat(5){AgentForegroundService.nudgeCentralHandoff(context)}
            service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS);pump()
            assertEquals(count,providerCalls.get())
            storage.onDatabaseWorker {WorkspaceRegistry(context).use {registry ->registry.treeRead {db ->
                db.rawQuery("SELECT resume_id,state FROM bot_tree_central_resume",emptyArray()).use {c ->
                    val ids=mutableSetOf<String>();while(c.moveToNext()) {assertTrue(ids.add(c.getString(0)));assertEquals("INCORPORATED",c.getString(1))}
                }
            }}}
        } finally {releaseOwner.countDown()}
    }

    /** Simulated loss of process capability only; durable checkpoint and real Room reopen are exercised. */
    private fun parkedWithoutLivePermission(withSteer:Boolean=false):AgentRunUiEvent {
        holdSecond=true
        val old=send("Original durable suspension request")
        await("Original sealed socket entered") {firstEntered.count==0L}
        if(withSteer) assertTrue(storage.onDatabaseWorker {AgentForegroundService.interruptAndSend(context,session,"Retained steering mutation target",arrayListOf())})
        val fresh=send("Fresh owner holds the serial lane")
        await("Fresh owner entered after actual confirmed park") {secondEntered.count==0L}
        val descriptor=storage.onDatabaseWorker {CentralTreeAdmission.listRecoverableSuspensions(context,session).single()}
        assertEquals(old,descriptor.originalUserMessageId)
        val field=AgentForegroundService::class.java.getDeclaredField("LIVE_SUSPENSIONS").apply {isAccessible=true}
        @Suppress("UNCHECKED_CAST")
        val catalog=field.get(null) as Map<String,CentralTreeAdmission.SuspensionPermit>
        val actualPermit=catalog.values.single {it.resumeId==descriptor.resumeId}
        actualPermit.requireCurrent()
        actualPermit.invalidate() // Denial only; no constructor, row mutation, insertion, or permission fabrication.
        releaseSecond.countDown()
        await("Fresh owner finishes and physical lane empties") {submissions().single {it.userMessageId==fresh}.state=="COMPLETED" && !AgentForegroundService.hasActiveRun(session)}
        val unchanged=storage.onDatabaseWorker {CentralTreeAdmission.listRecoverableSuspensions(context,session).single()}
        assertEquals(descriptor.revision,unchanged.revision);assertEquals(descriptor.sourceDigest,unchanged.sourceDigest)
        assertEquals("STARTED",submissions().single {it.userMessageId==old}.state)
        assertNull(root(old).closeReason);assertEquals(1L,root(old).executions)
        releaseFirst.countDown();assertTrue(staleReturned.await(10,TimeUnit.SECONDS))
        repeat(3){AgentForegroundService.nudgeCentralHandoff(context)}
        service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS);pump()
        assertEquals("Obsolete transport/callback cannot resurrect permission",2,calls.get())
        storage.onDatabaseWorker {ChatDatabaseBridge.closeInstanceForTests(context.filesDir)}
        val events=storage.onDatabaseWorker {LocalRunStore(context).readRecentConversationTimeline(session)}
        AgentRunUiState.restoreSession(session,events)
        val selected=events.single {it.kind=="user" && it.messageId==old}
        assertTrue(requireNotNull(selected.centralAvailability).canRetry)
        assertEquals(descriptor.resumeId,selected.centralAvailability!!.suspension!!.resumeId)
        repeat(3){pump();AgentForegroundService.nudgeCentralHandoff(context)}
        service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS);pump()
        assertEquals("App-open/projection never remints a live suspension",2,calls.get())
        return selected
    }
    @Test fun exactDurableSuspensionNeedsFreshNativeTapAfterCapabilityLossAndRoomReopen() {
        val selected=parkedWithoutLivePermission()
        val before=root(selected.messageId)
        val initial=submissions().single {it.userMessageId==selected.messageId}
        assertTrue(drafts.recoverPreparedSuspension(selected))
        await("Original exact suspended source completes after fresh native tap") {submissions().single {it.userMessageId==selected.messageId}.state=="COMPLETED"}
        await("Recovered suspension owner released") {!AgentForegroundService.hasActiveRun(session)}
        val after=root(selected.messageId)
        assertEquals(before.rootId,after.rootId);assertEquals(before.origin,after.origin)
        assertEquals(2L,after.executions);assertEquals(0L,after.windowEpoch);assertEquals("COMPLETED",after.closeReason)
        val completed=submissions().single {it.userMessageId==selected.messageId}
        assertEquals(initial.claimRunId,completed.claimRunId);assertEquals(initial.sourceSha256,completed.sourceSha256)
        assertEquals(3,calls.get());assertEquals(2,rows().count {it.optString("role")=="user"})
        assertTrue(requests[2].contains("Original durable suspension request"))
        assertFalse("Fresh USER cannot enter restored old-root provider request",requests[2].contains("Fresh owner holds the serial lane"))
        assertFalse("Fresh-root assistant also remains excluded",requests[2].contains("Fresh request completed"))
        repeat(3){AgentForegroundService.nudgeCentralHandoff(context)}
        service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS);pump();assertEquals(3,calls.get())
    }

    @Test fun sameFilesGenerationResetRejectsFreshNativeSuspensionRecoveryWithIntactBlob() {
        val selected=parkedWithoutLivePermission()
        val descriptor=requireNotNull(selected.centralAvailability!!.suspension)
        val before=root(selected.messageId)
        val receipt=yields.single()
        val original=submissions().single {it.userMessageId==selected.messageId}
        assertNotNull(CrewCheckpointStore(context.filesDir).readCentralYieldConfirmed(
            session,receipt.checkpointReference(),receipt.checkpointDigest(),original.storageGeneration))
        storage.onDatabaseWorker {
            ChatDatabaseBridge.closeInstanceForTests(context.filesDir)
            val file=java.io.File(StorageGeneration(context).chatRoot(),"agent.db")
            android.database.sqlite.SQLiteDatabase.openDatabase(file.path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {db ->
                val values=android.content.ContentValues().apply {put("value",java.util.UUID.randomUUID().toString())}
                assertEquals(1,db.update("storage_meta",values,"key=?",arrayOf("connection_projection_generation_v1")))
            }
            LocalRunStore(context).readCentralSubmissions(session) // Real new Room instance, not a passed expected-generation tweak.
        }
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        val unused=object:AttachmentMessageSubmitter {
            override fun persistMessage(application:Application,session:String,text:String,attachments:List<ChatAttachment>):String=error("Recovery cannot append USER")
            override fun startAgent(application:Application,session:String,messageId:String,skills:ArrayList<String>)=error("No generic recovery")
        }
        val retry=AttachmentDraftViewModel(context as Application,unused,executor).apply {switchSession(session)}
        val owner=androidx.lifecycle.ViewModelStore().apply {put("recovery",retry)}
        try {
            assertTrue(retry.recoverPreparedSuspension(selected)) // Fresh actual tap; validation must reject changed storage.
            executor.submit {}.get(10,TimeUnit.SECONDS);pump()
            assertEquals(2,calls.get());assertEquals(before,root(selected.messageId))
            assertFalse(AgentForegroundService.hasLiveCentralSuspension(descriptor))
            assertNotNull("Blob is intact, but is not permission in the new generation",CrewCheckpointStore(context.filesDir).readCentralYieldConfirmed(
                session,receipt.checkpointReference(),receipt.checkpointDigest(),original.storageGeneration))
        } finally {owner.clear();executor.shutdownNow()}
    }
    @Test fun confirmedYieldBlobThenRegistryParkFailureNeverRegistersOrReplaysOldRoot() {
        val old=send("Original whose Registry park will fail")
        await("Actual provider before park failure") {firstEntered.count==0L}
        storage.onDatabaseWorker {WorkspaceRegistry(context).use {registry ->registry.treeRead {db ->
            db.execSQL("CREATE TRIGGER fail_actual_suspension BEFORE INSERT ON bot_tree_central_resume WHEN NEW.cause_kind='SUSPENSION' BEGIN SELECT RAISE(ABORT,'synthetic park failure'); END")
        }}}
        try {
            val fresh=send("Fresh request survives failed old park")
            await("Fresh admitted source finishes after failed park owner unwinds") {submissions().single {it.userMessageId==fresh}.state=="COMPLETED"}
            await("Both physical owners release") {!AgentForegroundService.hasActiveRun(session)}
            val confirmed=yields.single()
            val original=submissions().single {it.userMessageId==old}
            assertNotNull(CrewCheckpointStore(context.filesDir).readCentralYieldConfirmed(
                session,confirmed.checkpointReference(),confirmed.checkpointDigest(),original.storageGeneration))
            val oldRootId=root(old).rootId
            storage.onDatabaseWorker {WorkspaceRegistry(context).use {registry ->registry.treeRead {db ->
                db.rawQuery("SELECT COUNT(*) FROM bot_tree_central_resume WHERE root_id=?",arrayOf(oldRootId)).use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
                db.rawQuery("SELECT COUNT(*) FROM bot_tree_event WHERE root_id=? AND kind='CENTRAL_YIELD'",arrayOf(oldRootId)).use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
            }}}
            assertTrue(storage.onDatabaseWorker {CentralTreeAdmission.listRecoverableSuspensions(context,session).isEmpty()})
            assertEquals("FAILED",root(old).closeReason);assertEquals(1L,root(old).executions)
            assertEquals("COMPLETED",root(fresh).closeReason)
            val count=calls.get();repeat(4){AgentForegroundService.nudgeCentralHandoff(context)}
            service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS);pump();assertEquals(count,calls.get())
            releaseFirst.countDown();assertTrue(staleReturned.await(10,TimeUnit.SECONDS));assertEquals(count,calls.get())
        } finally {storage.onDatabaseWorker {WorkspaceRegistry(context).use {registry ->registry.treeRead {db ->db.execSQL("DROP TRIGGER IF EXISTS fail_actual_suspension")}}}}
    }

    @Test fun nativeP1ResultLosesToLiveSuspensionAndFreshUserWithoutConsumingEitherTwice() {
        holdFirst=false;holdSecond=true;holdThird=true
        val childEntered=CountDownLatch(1);val releaseChild=CountDownLatch(1);val childCalls=AtomicInteger()
        var manager:CrewManager?=null
        val (workspace,profile)=storage.onDatabaseWorker {
            val scope=WorkspaceRegistry(context).use {it.createWorkspace(java.util.UUID.randomUUID().toString(),"P1 arbitration native workspace")}
            ChatDatabaseBridge.getInstance(context).ensureConversationSync(scope.executionSessionId,"workspace")
            val role=CrewProfileRepository(context).create(CrewProfile(CrewProfileRepository.newCustomId(),1,"Generalist","General-purpose planning","Use exact evidence",emptyList(),emptyList(),CrewProfile.WorkspaceMode.LEGACY_CHAT),emptyList(),emptyList())
            val childProvider=object:ModelProviderClient {
                override fun complete(systemPrompt:String,userPrompt:String,images:List<com.jarvys.agent.device.ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply=error("Conversation required")
                override fun completeConversation(systemPrompt:String,history:List<ConversationTurn>,userPrompt:String,images:List<com.jarvys.agent.device.ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply {
                    childCalls.incrementAndGet();childEntered.countDown();check(releaseChild.await(20,TimeUnit.SECONDS));token.throwIfCancelled()
                    return ModelReply("Canonical child result for shared arbitration",emptyList())
                }
            }
            val runtime=CoreAgentRuntime(context,scope.executionSessionId,emptyList(),CorePromptBudget.standard())
            manager=runtime.configureCrewManager(runtime.createTools(),CoreAgentModel(context,childProvider,scope.executionSessionId),null)
            scope to role
        }
        fun response(content:String,call:String?=null,args:Map<String,Any> = emptyMap()):String {
            val message=org.json.JSONObject().put("role","assistant").put("content",content)
            if(call!=null) message.put("tool_calls",org.json.JSONArray().put(org.json.JSONObject().put("id",call).put("type","function")
                .put("function",org.json.JSONObject().put("name",com.jarvys.agent.workspaces.CentralWorkspaceTool.NAME).put("arguments",org.json.JSONObject(args).toString()))))
            return org.json.JSONObject().put("choices",org.json.JSONArray().put(org.json.JSONObject().put("message",message))).toString()
        }
        val selected=AtomicReference<org.json.JSONObject>()
        socketReply={index ->when(index) {
            1->response("", "native-p1-delegate",mapOf("action" to "delegate","workspace_id" to workspace.workspaceId,"key" to "native-p1-arbitration","role" to profile.id,"mission" to "Produce exact native evidence","tools" to emptyList<String>(),"continue_result" to true))
            2->response("STALE superseded old inference")
            3->response("Independent fresh request done")
            4->response("Original checkpoint resumed before pending child continuation")
            5->response("", "native-selected-read",mapOf("action" to "read_result","workspace_id" to workspace.workspaceId,
                "result_id" to selected.get().getString("resultReferenceId"),"expected_sha256" to selected.get().getString("contentSha256")))
            else->response("Exact child synthesis after serial incorporation")
        }}
        try {
            val old=send("Delegate and retain original native work")
            await("Actual child started and Central entered next inference") {childEntered.count==0L && secondEntered.count==0L}
            val fresh=send("Fresh independent request wins next lane")
            await("Fresh inference holds the lane after old confirmed suspension") {thirdEntered.count==0L}
            val bot=manager!!.bots().single()
            fun record()=storage.onDatabaseWorker {requireNotNull(CrewCheckpointStore(context.filesDir).load(workspace.executionSessionId,bot.id)).metadata.getJSONObject(CentralContinuationRecord.FIELD)}
            releaseChild.countDown()
            await("Actual P1 selected result is durable but cannot claim active lane") {record().optString("dispatchState")=="PREPARED" && bot.isQuiescent}
            selected.set(record().getJSONObject("selectedResult"))
            repeat(3){AgentForegroundService.nudgeCentralHandoff(context);pump()}
            assertEquals("P1 loser retains exact PREPARED claim", "PREPARED",record().getString("dispatchState"))
            assertEquals(3,calls.get())
            val oldRoot=root(old)
            storage.onDatabaseWorker {WorkspaceRegistry(context).use {registry ->registry.treeRead {db ->
                db.rawQuery("SELECT cause_kind,state FROM bot_tree_central_resume WHERE root_id=?",arrayOf(oldRoot.rootId)).use {c ->
                    assertTrue(c.moveToFirst());assertEquals("SUSPENSION",c.getString(0));assertEquals("PREPARED",c.getString(1));assertFalse(c.moveToNext())
                }
            }}}
            releaseThird.countDown()
            await("Suspension then exact P1 source both incorporated") {record().optString("dispatchState")=="ACKNOWLEDGED" && root(old).closeReason=="COMPLETED"}
            await("Serial owner gone") {!AgentForegroundService.hasActiveRun(session)}
            assertEquals("COMPLETED",root(fresh).closeReason);assertEquals(oldRoot.origin,root(old).origin)
            assertEquals(1,childCalls.get());assertEquals(6,calls.get())
            storage.onDatabaseWorker {WorkspaceRegistry(context).use {registry ->registry.treeRead {db ->
                db.rawQuery("SELECT cause_kind,state FROM bot_tree_central_resume WHERE root_id=? ORDER BY created_at,resume_id",arrayOf(oldRoot.rootId)).use {c ->
                    val causes=mutableMapOf<String,String>();while(c.moveToNext()) {assertNull(causes.put(c.getString(0),c.getString(1)))}
                    assertEquals(mapOf("SUSPENSION" to "INCORPORATED","CHILD_RESULT" to "INCORPORATED"),causes)
                }
            }}}
            val count=calls.get();repeat(4){AgentForegroundService.nudgeCentralHandoff(context)}
            service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS);pump();assertEquals(count,calls.get())
        } finally {releaseChild.countDown();releaseSecond.countDown();releaseThird.countDown();manager?.close()}
    }

    @Test fun retainedSteerOwnsResumedTerminalWithoutChangingOriginalRootOrSubmission() {
        val original=send("Original root before steering")
        await("Original actual inference entered") {firstEntered.count==0L}
        assertTrue(storage.onDatabaseWorker {AgentForegroundService.interruptAndSend(context,session,"Steer original request only",arrayListOf())})
        val steer=rows().single {it.optString("role")=="user" && it.optString("content")=="Steer original request only"}.getString("messageId")
        val fresh=send("Fresh root after retained steering")
        await("Both independent submissions finish after actual steering drain and yield") {submissions().count {it.state=="COMPLETED"}==2}
        await("Final physical owner released") {!AgentForegroundService.hasActiveRun(session)}
        assertEquals(3,calls.get())
        val receipt=yields.single()
        assertEquals(original,receipt.ownerMessageId())
        assertEquals(steer,receipt.finalOwnerMessageId())
        val originalRoot=root(original);val freshRoot=root(fresh)
        assertEquals(original,originalRoot.origin.originUserMessageId)
        assertEquals(fresh,freshRoot.origin.originUserMessageId)
        assertNotEquals(originalRoot.rootId,freshRoot.rootId)
        assertEquals(2L,originalRoot.executions);assertEquals(1L,freshRoot.executions)
        assertEquals(0L,originalRoot.windowEpoch)
        val originalSubmission=submissions().single {it.userMessageId==original}
        assertEquals("COMPLETED",originalSubmission.state)
        val terminal=org.json.JSONObject(requireNotNull(originalSubmission.terminalEvidence))
        assertEquals(steer,terminal.getString("finalOwnerMessageId"))
        val answer=rows().single {it.optString("role")=="assistant" && it.optString("content")=="Original request resumed"}
        assertEquals(steer,answer.getString("userMessageId"))
        assertFalse(rows().any {it.optString("role")=="assistant" && it.optString("content")=="Original request resumed" && it.optString("userMessageId")==fresh})
        assertEquals(2,submissions().size) // STEER never creates a positive NEW_TURN or renews a budget.
        releaseFirst.countDown();assertTrue(staleReturned.await(10,TimeUnit.SECONDS))
        assertFalse(modelRows().any {it.toString().contains("stale-effect")})
    }

    private fun exactRetrySettled(selected:AgentRunUiEvent) {
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        val unused=object:AttachmentMessageSubmitter {
            override fun persistMessage(application:Application,session:String,text:String,attachments:List<ChatAttachment>):String=error("Recovery cannot append")
            override fun startAgent(application:Application,session:String,messageId:String,skills:ArrayList<String>)=error("No generic recovery")
        }
        val retry=AttachmentDraftViewModel(context as Application,unused,executor).apply {switchSession(session)}
        val owner=androidx.lifecycle.ViewModelStore().apply {put("recovery",retry)}
        try {assertTrue(retry.recoverPreparedSuspension(selected));executor.submit {}.get(10,TimeUnit.SECONDS);pump()}
        finally {owner.clear();executor.shutdownNow()}
    }
    private fun mutatedNativeRecovery(kind:String) {
        val selected=parkedWithoutLivePermission(kind=="STEER")
        val descriptor=requireNotNull(selected.centralAvailability!!.suspension)
        val before=root(selected.messageId);val receipt=yields.single()
        if(kind=="BLOB") {
            val file=java.io.File(StorageGeneration(context).directory("crew-context"),
                CrewCheckpointStore.digest(session.toByteArray(Charsets.UTF_8))+"/central-yields/"+receipt.checkpointDigest()+".json")
            assertTrue(file.isFile);file.writeText("invalid retained checkpoint")
        } else storage.onDatabaseWorker {
            val message=if(kind=="USER") selected.messageId else rows().single {it.optString("content")=="Retained steering mutation target"}.getString("messageId")
            ChatDatabaseBridge.closeInstanceForTests(context.filesDir)
            android.database.sqlite.SQLiteDatabase.openDatabase(java.io.File(StorageGeneration(context).chatRoot(),"agent.db").path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {db ->
                val value=android.content.ContentValues().apply {put("payload","MUTATED CANONICAL SOURCE")}
                assertEquals(1,db.update("message_parts",value,"conversation_id=? AND message_id=? AND kind='text'",arrayOf(session,message)))
            }
            LocalRunStore(context).readCentralSubmissions(session)
        }
        exactRetrySettled(selected)
        assertEquals(2,calls.get());assertEquals(before,root(selected.messageId))
        assertFalse(AgentForegroundService.hasLiveCentralSuspension(descriptor))
        storage.onDatabaseWorker {WorkspaceRegistry(context).use {registry ->registry.treeRead {db ->
            db.rawQuery("SELECT state,revision,source_digest FROM bot_tree_central_resume WHERE resume_id=?",arrayOf(descriptor.resumeId)).use {
                assertTrue(it.moveToFirst());assertEquals("PREPARED",it.getString(0));assertEquals(descriptor.revision,it.getLong(1));assertEquals(descriptor.sourceDigest,it.getString(2))
            }
        }}}
    }
    @Test fun nativeRetryRejectsChangedRetainedBlobWithoutMintingPermission()=mutatedNativeRecovery("BLOB")
    @Test fun nativeRetryRejectsChangedCanonicalOriginalUser()=mutatedNativeRecovery("USER")
    @Test fun nativeRetryRejectsChangedCanonicalRetainedSteer()=mutatedNativeRecovery("STEER")

    private fun actualClaim(runtime:CoreAgentRuntime):CentralTreeAdmission.SuspensionClaim? {
        val field=CoreAgentRuntime::class.java.getDeclaredField("suspensionClaim").apply {isAccessible=true}
        return field.get(runtime) as CentralTreeAdmission.SuspensionClaim? // Observes actual minted claim; never creates or injects one.
    }
    @Test fun actualSuspensionClaimRejectsWrongTokenAndUnconfirmedForeignRunSourceWitness() {
        val previous=service.mainChatRunner;val checked=AtomicInteger()
        service.mainChatRunner=AgentForegroundService.MainChatRunner {runtime,goal,history,attachments,token,progress ->
            val claim=actualClaim(runtime)
            if(claim!=null) {
                checked.incrementAndGet()
                assertThrows(IllegalStateException::class.java) {claim.requireCurrent(CancellationToken.uncancellable())}
                assertThrows(IllegalStateException::class.java) {claim.currentCheckpointSources(token)}
                val foreign=MainChatTranscriptStore(context.filesDir,session,LocalRunStore(context))
                assertThrows(IllegalStateException::class.java) {claim.attachCheckpointSourceWitness(foreign,claim.runId(),token)}
                val noProvider=CoreAgentLoop(CoreAgentLoop.Model {_,_,_,_->error("Witness check cannot infer")},CoreToolRegistry(emptyList()),"",session)
                foreign.attach(noProvider,emptyList(),claim.getOriginalUserMessageId(),"foreign-native-run")
                assertThrows(IllegalStateException::class.java) {claim.attachCheckpointSourceWitness(foreign,claim.runId(),token)}
                claim.requireCurrent(token)
            }
            previous.run(runtime,goal,history,attachments,token,progress).also {
                if(claim!=null) {claim.requireBound(token);assertNotNull(claim.currentCheckpointSources(token))}
            }
        }
        val selected=parkedWithoutLivePermission()
        assertTrue(drafts.recoverPreparedSuspension(selected))
        await("Actual verified owner succeeds after denied foreign witnesses") {submissions().single {it.userMessageId==selected.messageId}.state=="COMPLETED"}
        await("Claim lane released") {!AgentForegroundService.hasActiveRun(session)}
        assertEquals(1,checked.get());assertEquals(3,calls.get())
    }
    private fun rejectActualPrebindClaim(stop:Boolean) {
        val selected=parkedWithoutLivePermission();val before=root(selected.messageId)
        val previous=service.mainChatRunner;val observed=CountDownLatch(1);val denial=AtomicReference<Throwable>()
        service.mainChatRunner=AgentForegroundService.MainChatRunner {runtime,goal,history,attachments,token,progress ->
            val claim=actualClaim(runtime)
            if(claim==null) previous.run(runtime,goal,history,attachments,token,progress)
            else {
                try {
                    if(stop) {stopSession();assertTrue(token.wasStoppedByUser())}
                    else WorkspaceRegistry(context).use {registry ->registry.treeRead {db ->
                        db.execSQL("UPDATE bot_tree_central_resume SET owner_run='different-owner', revision=revision+1 WHERE resume_id=?",arrayOf(claim.resumeId()))
                    }}
                    try {runtime.runMainChat(goal,history,attachments,token,progress);error("Revoked native claim must not bind/infer")}
                    catch(expected:RuntimeException) {denial.set(expected);throw expected}
                } finally {observed.countDown()}
            }
        }
        assertTrue(drafts.recoverPreparedSuspension(selected))
        await("Native reserved claim reaches deterministic prebind boundary") {observed.count==0L}
        await("Denied owner physically releases") {!AgentForegroundService.hasActiveRun(session)}
        assertNotNull(denial.get());assertEquals(2,calls.get());assertEquals(before.executions,root(selected.messageId).executions)
        storage.onDatabaseWorker {WorkspaceRegistry(context).use {registry ->registry.treeRead {db ->
            db.rawQuery("SELECT state FROM bot_tree_central_resume WHERE root_id=?",arrayOf(before.rootId)).use {
                assertTrue(it.moveToFirst());assertTrue(it.getString(0) in setOf("RESERVED","UNCERTAIN","CANCELLED"))
            }
        }}}
        repeat(3){AgentForegroundService.nudgeCentralHandoff(context)}
        service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS);pump();assertEquals(2,calls.get())
    }
    @Test fun actualReservedClaimRejectsDurableOwnerAndRevisionMutationBeforeSuccessorBinding()=rejectActualPrebindClaim(false)
    @Test fun realServiceStopAfterReservationDeniesSuccessorBindingAndRetainsNoReplayEvidence()=rejectActualPrebindClaim(true)

}
