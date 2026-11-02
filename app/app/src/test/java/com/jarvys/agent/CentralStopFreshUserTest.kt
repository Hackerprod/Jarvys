package com.jarvys.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
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
@Config(sdk=[34],application=CentralFlowTestApplication::class,shadows=[ArtifactOsShadow::class])
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
    private var startId=0
    @Before fun setup() {
        storage.localRunStore(context.filesDir)
        storage.onDatabaseWorker {FreshStorageBootstrap.ensure(context);ChatDatabaseBridge.getInstance(context).ensureConversationSync(session,"chat")}
        ProviderSettings(context).apply {setProvider(ProviderSettings.Provider.OPENROUTER);setOpenRouterModel("offline/stop-fresh")}
        check(context.getSharedPreferences("provider_context_metadata",0).edit().putInt("model_offline/stop-fresh",32768)
            .putLong("fetched_offline/stop-fresh",System.currentTimeMillis()).commit())
        AgentRunUiState.resetSession(session)
        controller=Robolectric.buildService(AgentForegroundService::class.java).create();service=controller!!.get()
        val provider=object:ModelProviderClient {
            override fun complete(systemPrompt:String,userPrompt:String,images:List<ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply=error("Conversation provider expected")
            override fun completeConversation(systemPrompt:String,history:List<ConversationTurn>,userPrompt:String,images:List<ScreenData>,tools:List<ToolSpec>,sessionId:String,token:CancellationToken):ModelReply {
                if(calls.incrementAndGet()==1) {
                    entered.countDown()
                    while(release.count>0)try {release.await()}catch(_:InterruptedException) { /* Deliberately noninterruptible physical owner. */ }
                    return ModelReply("STALE-OLD-ANSWER",emptyList())
                }
                token.throwIfCancelled();freshEntered.countDown()
                return ModelReply("Fresh answer",emptyList())
            }
        }
        service.runtimeFactory=AgentForegroundService.RuntimeFactory {c,id,skills -> CoreAgentRuntime(c,id,skills,CorePromptBudget.standard(),provider)}
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
        while(!condition()){pump();check(System.nanoTime()<deadline){"$description; ${AgentForegroundService.getLastRunReport()}"};Thread.sleep(10)}
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
