package com.jarvys.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.*
import kotlinx.coroutines.runBlocking
import com.jarvys.agent.device.ScreenData
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real serial service/runtime with a provider that cannot physically unwind until released. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=TreeNativeTestApplication::class,shadows=[ArtifactOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CentralStopFreshUserTest {
    private val storage=ChatTestStore(runTestOnWorker=false,registerApplicationFilesDirectory=true)
    @get:Rule val rules:RuleChain=RuleChain.outerRule(storage).around(TestSecretStoreRule())
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val session=WorkspaceIdentity.CENTRAL_SESSION
    private var controller:org.robolectric.android.controller.ServiceController<AgentForegroundService>?=null
    private lateinit var service:AgentForegroundService
    private val entered=CountDownLatch(1)
    private val release=CountDownLatch(1)
    private val freshEntered=CountDownLatch(1)
    private val calls=AtomicInteger()
    private val heldToken=java.util.concurrent.atomic.AtomicReference<CancellationToken>()
    private val runtimeFailure=java.util.concurrent.atomic.AtomicReference<Throwable>()
    private var startId=0
    @Before fun setup() {
        storage.localRunStore(context.filesDir)
        storage.onDatabaseWorker {
            FreshStorageBootstrap.ensure(context)
            (context as TreeNativeTestApplication).connectionAccess().initializeAsync().get()
            check((context as TreeNativeTestApplication).connectionAccess().isReady())
            ChatDatabaseBridge.getInstance(context).ensureConversationSync(session,"chat")
        }
        ProviderSettings(context).apply {setProvider(ProviderSettings.Provider.OPENROUTER);setOpenRouterModel("offline/stop-fresh")}
        check(context.getSharedPreferences("provider_context_metadata",0).edit().putInt("model_offline/stop-fresh",32768)
            .putLong("fetched_offline/stop-fresh",System.currentTimeMillis()).commit())
        AgentRunUiState.resetSession(session)
        controller=Robolectric.buildService(AgentForegroundService::class.java).create();service=controller!!.get()
        val provider=object:ModelProviderClient {
            override fun complete(systemPrompt:String,userPrompt:String,images:List<ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply=error("Conversation provider expected")
            override fun completeConversation(systemPrompt:String,history:List<ConversationTurn>,userPrompt:String,images:List<ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply {
                if(calls.incrementAndGet()==1) {
                    heldToken.set(token)
                    entered.countDown()
                    while(release.count>0)try {release.await()}catch(_:InterruptedException) { /* Deliberately noninterruptible physical owner. */ }
                    return ModelReply("STALE-OLD-ANSWER",emptyList())
                }
                token.throwIfCancelled();freshEntered.countDown()
                return ModelReply("Fresh answer",emptyList())
            }
        }
        service.runtimeFactory=AgentForegroundService.RuntimeFactory {c,id,skills -> CoreAgentRuntime(c,id,skills,CorePromptBudget.standard(),provider)}
        service.mainChatRunner=AgentForegroundService.MainChatRunner {runtime,goal,history,attachments,token,progress ->
            try {runtime.runMainChat(goal,history,attachments,token,progress)}
            catch(cancelled:java.util.concurrent.CancellationException){throw cancelled}
            catch(problem:Throwable){runtimeFailure.set(problem);throw AssertionError("Actual Central runtime failed",problem)}
        }
    }
    @After fun cleanup() {
        release.countDown();StopController.stopAllRuns();StopController.getInstance().stopRun()
        controller?.let {runCatching {it.destroy()}}
        context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
    }
    private fun pump() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val app=Shadows.shadowOf(context as Application)
        var intent=app.nextStartedService
        while(intent!=null){service.onStartCommand(intent,0,++startId);intent=app.nextStartedService}
    }
    private fun await(description:String,condition:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
        while(!condition()){runtimeFailure.get()?.let {throw AssertionError(description,it)};pump();check(System.nanoTime()<deadline){"$description; ${AgentForegroundService.getLastRunReport()}"};Thread.sleep(10)}
    }
    private fun rows()=storage.onDatabaseWorker {LocalRunStore(context).readConversationMessages(session)}
    private fun submit(text:String):String {
        assertTrue(storage.onDatabaseWorker {AgentForegroundService.interruptAndSend(context,session,text,arrayListOf())})
        return rows().single {it.optString("role")=="user" && it.optString("content")==text}.getString("messageId")
    }
    private fun stop() {
        AgentForegroundService.stopRun(session)
        service.awaitStopTasksForTesting().get(10,TimeUnit.SECONDS)
    }
    private fun startHeld():String {
        val original=storage.onDatabaseWorker {LocalRunStore(context).appendConversationMessage(session,"user","Old request")}
        service.onStartCommand(AgentForegroundService.storedChatMessageIntent(context,session,original),0,++startId)
        await("Old provider entered"){entered.count==0L}
        return original
    }
    @Test fun stoppedQueuedFollowUpReleasesClaimAndAllowsExplicitRegeneration() {
        startHeld()
        val queued=submit("Queued follow-up stopped before execution")
        service.onStartCommand(AgentForegroundService.storedChatMessageIntent(context,session,queued)
            .putExtra("chat_follow_up_request_id",queued),0,++startId)
        val claims=AgentForegroundService::class.java.getDeclaredField("queuedInternalInputIds")
            .apply {isAccessible=true}.get(service) as Set<*>
        assertTrue(claims.any {it.toString().endsWith(queued)})
        stop()
        assertFalse("Atomic cancellation must release the queued input's independent transient claim",claims.any {it.toString().endsWith(queued)})
        val stopped=rows().filter {it.optString("role")=="assistant" && it.optString("userMessageId")==queued}
        assertEquals("Only the atomic cohort writes its terminal bubble",1,stopped.size)
        assertEquals(service.getString(R.string.agent_run_stopped),stopped.single().getString("content"))
        release.countDown()
        service.awaitSessionIdleForTesting(session).get(20,TimeUnit.SECONDS)
        service.onStartCommand(AgentForegroundService.storedChatMessageIntent(context,session,queued)
            .putExtra("chat_regeneration",true),0,++startId)
        await("Explicit regeneration of stopped stored USER reaches the real provider"){freshEntered.count==0L}
        await("Regenerated USER completes"){rows().any {it.optString("role")=="assistant" && it.optString("userMessageId")==queued && it.optString("status")=="COMPLETED"}}
        await("Serial lane idle"){!AgentForegroundService.hasActiveRun(session)}
        assertEquals(2,calls.get())
        assertEquals(1,rows().count {it.optString("role")=="assistant" && it.optString("userMessageId")==queued && it.optString("status")=="FAILED"})
    }
    @Test fun stopLatchesExactTokenBeforeBlockedDatabaseSnapshot() {
        startHeld()
        val bridge=ChatDatabaseBridge.getInstance(context)
        val executor=ChatDatabaseBridge::class.java.getDeclaredField("executor").apply {isAccessible=true}.get(bridge) as java.util.concurrent.ExecutorService
        val databaseHeld=CountDownLatch(1)
        val releaseDatabase=CountDownLatch(1)
        val blocker=executor.submit {databaseHeld.countDown();releaseDatabase.await()}
        assertTrue(databaseHeld.await(10,TimeUnit.SECONDS))
        try {
            AgentForegroundService.stopRun(session)
            await("Exact native cancellation must precede the blocked Room snapshot"){heldToken.get().wasStoppedByUser()}
            assertEquals("Provider remains physically owned until released",1,calls.get())
            assertEquals(1L,release.count)
            assertFalse("STOP persistence is still waiting for the blocked database",service.awaitStopTasksForTesting().isDone)
        } finally {
            releaseDatabase.countDown()
            blocker.get(10,TimeUnit.SECONDS)
            service.awaitStopTasksForTesting().get(10,TimeUnit.SECONDS)
        }
    }
    private fun sql(statement:String)=storage.onDatabaseWorker {runBlocking {
        val db=JarvysChatDatabaseProvider.getInstance(context)
        db.withChatWriteTransaction {db.chatSql.execSQL(statement)}
    }}
    private fun laneIsQuarantined():Boolean {
        val type=AgentForegroundService::class.java
        val lock=type.getDeclaredField("sessionLaneLock").apply {isAccessible=true}.get(service)
        synchronized(lock) {
            val lanes=type.getDeclaredField("sessionLanes").apply {isAccessible=true}.get(service) as Map<*,*>
            val lane=lanes[session] ?: return false
            synchronized(lane) {
                val draining=lane.javaClass.getDeclaredField("draining").apply {isAccessible=true}.getBoolean(lane)
                val active=lane.javaClass.getDeclaredField("active").apply {isAccessible=true}.get(lane)
                val queued=lane.javaClass.getDeclaredField("queued").apply {isAccessible=true}.get(lane) as Collection<*>
                return !draining && active==null && queued.isNotEmpty()
            }
        }
    }
    @Test fun directStoredUserWaitsInOriginalLaneUntilExactStopCommitCanRecover() {
        startHeld()
        val captured=submit("Captured before failed direct STOP")
        sql("CREATE TRIGGER fixture_stop_abort BEFORE INSERT ON messages WHEN NEW.role='assistant' AND NEW.owner_user_message_id='$captured' BEGIN SELECT RAISE(ABORT,'fixture_stop_failure'); END")
        stop()
        val fresh=storage.onDatabaseWorker {LocalRunStore(context).appendConversationMessage(session,"user","Fresh direct stored USER")}
        service.onStartCommand(AgentForegroundService.storedChatMessageIntent(context,session,fresh),0,++startId)
        release.countDown()
        await("Original native lane quarantined without a new token"){laneIsQuarantined()}
        assertEquals(1,calls.get())
        assertNull(storage.onDatabaseWorker {LocalRunStore(context).readConversationInputState(session,fresh)})
        assertFalse(rows().any {it.optString("role")=="assistant" && it.optString("userMessageId")==fresh})
        sql("DROP TRIGGER fixture_stop_abort")
        storage.onDatabaseWorker {service.runPendingInputRecoverySafely(session)}
        await("Original queued stored USER completes"){rows().any {it.optString("role")=="assistant" && it.optString("userMessageId")==fresh && it.optString("status")=="COMPLETED"}}
        await("Serial lane idle"){!AgentForegroundService.hasActiveRun(session)}
        assertEquals(2,calls.get())
        assertEquals("resolved",storage.onDatabaseWorker {LocalRunStore(context).readConversationInputState(session,captured)})
        assertEquals(1,rows().count {it.optString("role")=="assistant" && it.optString("userMessageId")==fresh})
    }
    @Test fun failedStopPersistenceDoesNotCancelFreshInputOrReplayCapturedInput() {
        startHeld()
        val captured=submit("Captured before failing STOP")
        sql("CREATE TRIGGER fixture_stop_abort BEFORE INSERT ON messages WHEN NEW.role='assistant' AND NEW.owner_user_message_id='$captured' BEGIN SELECT RAISE(ABORT,'fixture_stop_failure'); END")
        stop()
        val fresh=submit("Fresh after failed STOP")
        assertNull(storage.onDatabaseWorker {LocalRunStore(context).readConversationInputState(session,fresh)})
        assertNull(storage.onDatabaseWorker {LocalRunStore(context).readConversationInputState(session,captured)})
        release.countDown()
        service.awaitSessionIdleForTesting(session).get(20,TimeUnit.SECONDS)
        storage.onDatabaseWorker {service.runPendingInputRecoverySafely(session)}
        pump()
        assertEquals("Unresolved captured STOP must not infer a replay",1,calls.get())
        assertFalse(rows().any {it.optString("role")=="assistant" && it.optString("userMessageId")==fresh})
        assertNull(storage.onDatabaseWorker {LocalRunStore(context).readConversationInputState(session,fresh)})
        sql("DROP TRIGGER fixture_stop_abort")
        storage.onDatabaseWorker {service.runPendingInputRecoverySafely(session)}
        await("Fresh completion after exact captured cancellation retry"){rows().any {it.optString("role")=="assistant" && it.optString("userMessageId")==fresh && it.optString("status")=="COMPLETED"}}
        await("Serial lane idle"){!AgentForegroundService.hasActiveRun(session)}
        assertEquals(2,calls.get())
        assertEquals("resolved",storage.onDatabaseWorker {LocalRunStore(context).readConversationInputState(session,captured)})
        assertFalse(rows().any {it.optString("content")=="STALE-OLD-ANSWER"})
    }
    @Test fun freshUserAfterStopSurvivesOldPhysicalUnwindAndRunsExactlyOnce() {
        startHeld()
        val before=submit("Before STOP steering")
        stop()
        val fresh=submit("Fresh USER after STOP")
        assertEquals("Fresh USER must not run before physical owner leaves",1,calls.get())
        assertNull("Old STOP must not resolve a later USER",storage.onDatabaseWorker {LocalRunStore(context).readConversationInputState(session,fresh)})
        assertFalse(rows().any {it.optString("role")=="assistant" && it.optString("userMessageId")==fresh})
        release.countDown()
        await("Fresh USER runs after old physical release"){freshEntered.count==0L}
        await("Exact fresh completion"){rows().any {it.optString("role")=="assistant" && it.optString("userMessageId")==fresh && it.optString("status")=="COMPLETED"}}
        await("Serial lane idle"){!AgentForegroundService.hasActiveRun(session)}
        assertEquals(2,calls.get())
        assertFalse(rows().any {it.optString("content")=="STALE-OLD-ANSWER"})
        assertEquals(1,rows().count {it.optString("role")=="assistant" && it.optString("userMessageId")==fresh})
        assertEquals("incorporated",storage.onDatabaseWorker {LocalRunStore(context).readConversationInputState(session,fresh)})
        assertEquals("resolved",storage.onDatabaseWorker {LocalRunStore(context).readConversationInputState(session,before)})
        service.runPendingInputRecoverySafely(session);pump();assertEquals(2,calls.get())
    }
    @Test fun repeatedStopCancelsItsCurrentCohortButNotAUserSubmittedAfterIt() {
        startHeld();stop()
        val secondCohort=submit("Between two STOPs")
        stop()
        val fresh=submit("After second STOP")
        assertEquals("resolved",storage.onDatabaseWorker {LocalRunStore(context).readConversationInputState(session,secondCohort)})
        assertNull(storage.onDatabaseWorker {LocalRunStore(context).readConversationInputState(session,fresh)})
        assertEquals(1,calls.get())
        release.countDown()
        await("Newest fresh USER completion"){rows().any {it.optString("role")=="assistant" && it.optString("userMessageId")==fresh && it.optString("status")=="COMPLETED"}}
        await("Serial lane idle"){!AgentForegroundService.hasActiveRun(session)}
        assertEquals(2,calls.get())
        assertFalse(rows().any {it.optString("role")=="assistant" && it.optString("userMessageId")==secondCohort && it.optString("status")=="COMPLETED"})
    }
}
