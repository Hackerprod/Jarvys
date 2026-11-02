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

/** Private UNRUN acceptance: exact native late-STEER claim, never arbitrary historical USER. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=TreeNativeTestApplication::class,shadows=[ArtifactOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class LateSteerAvailabilityAcceptanceTest {
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
    @Test fun nativeLateSteerFollowUpYieldsHeldInferenceToFreshSend() {
        field("holdFirst").setBoolean(base,false);field("holdSecond").setBoolean(base,true)
        val oldFinished=CountDownLatch(1);val releaseOld=CountDownLatch(1)
        val first=java.util.concurrent.atomic.AtomicBoolean(true)
        val prior=service.mainChatRunner
        service.mainChatRunner=AgentForegroundService.MainChatRunner {runtime,goal,history,attachments,token,progress ->
            prior.run(runtime,goal,history,attachments,token,progress).also {
                if(first.compareAndSet(true,false)) {oldFinished.countDown();check(releaseOld.await(20,TimeUnit.SECONDS))}
            }
        }
        val staleReturned=CountDownLatch(1)
        field("socketReply").set(base,{index:Int ->if(index==2) {staleReturned.countDown();reply("LATE FOLLOWUP ANSWER","late-followup-effect",mapOf("action" to "list"))} else reply("Actual answer $index")})
        try {
            val original=send("Original completes before a late native STEER")
            until("Actual old loop returned and closed its native input reader") {oldFinished.count==0L}
            val steerText="Explicit late STEER after old reader closure"
            assertTrue(storage.onDatabaseWorker {AgentForegroundService.interruptAndSend(context,session,steerText,arrayListOf())})
            val steer=storage.onDatabaseWorker {LocalRunStore(context).readConversationMessages(session).single {it.optString("role")=="user" && it.optString("content")==steerText}.getString("messageId")}
            assertTrue(storage.onDatabaseWorker {LocalRunStore(context).readPendingInterruptRequests(session)}.any {it.optString("requestId")==steer})
            assertFalse(storage.onDatabaseWorker {LocalRunStore(context).readCentralSubmissions(session)}.any {it.userMessageId==steer})
            releaseOld.countDown()
            until("Native pending interrupt recovery enters held follow-up provider") {get<CountDownLatch>("secondEntered").count==0L}
            assertEquals("incorporated",storage.onDatabaseWorker {LocalRunStore(context).readConversationInputState(session,steer)})
            fun ackSequence()=storage.onDatabaseWorker {kotlinx.coroutines.runBlocking {
                val db=JarvysChatDatabaseProvider.getInstance(context)
                db.withChatReadTransaction {db.chatSql.query("SELECT input_state_seq FROM messages WHERE conversation_id=? AND message_id=?",arrayOf(session,steer)).use {c ->check(c.moveToFirst());c.getLong(0)}}
            }}
            val originalAck=ackSequence()
            val fresh=send("Fresh native Send while exact late STEER provider is silent")
            until("Fresh native USER completes while late-STEER socket stays blocked") {storage.onDatabaseWorker {LocalRunStore(context).readCentralSubmissions(session).single {it.userMessageId==fresh}}.state=="COMPLETED"}
            until("Serial lane released") {!AgentForegroundService.hasActiveRun(session)}
            assertEquals(1L,get<CountDownLatch>("releaseSecond").count)
            val partial=storage.onDatabaseWorker {LocalRunStore(context).readConversationMessages(session)}.filter {it.optString("role")=="assistant" && it.optString("userMessageId")==steer}
            assertEquals(1,partial.size);assertEquals("PARTIAL",partial.single().getString("status"))
            get<CountDownLatch>("releaseSecond").countDown();assertTrue(staleReturned.await(10,TimeUnit.SECONDS))
            assertFalse(storage.onDatabaseWorker {LocalRunStore(context).readModelTranscriptRows(session)}.any {it.toString().contains("late-followup-effect")})
            assertFalse(storage.onDatabaseWorker {LocalRunStore(context).readConversationMessages(session)}.any {it.optString("content")=="LATE FOLLOWUP ANSWER"})
            assertEquals(3,get<AtomicInteger>("calls").get())
            assertEquals("incorporated",storage.onDatabaseWorker {LocalRunStore(context).readConversationInputState(session,steer)})
            assertEquals(originalAck,ackSequence())
            assertEquals(2,storage.onDatabaseWorker {LocalRunStore(context).readCentralSubmissions(session)}.size)
            assertNotEquals(root(original).rootId,root(fresh).rootId)
            assertEquals(1L,root(original).executions);assertEquals(1L,root(fresh).executions)
            assertFalse(storage.onDatabaseWorker {LocalRunStore(context).readPendingInterruptRequests(session)}.any {it.optString("requestId")==steer})
        } finally {releaseOld.countDown()}
    }
    @Test fun actualLateSteerOwnerRejectsOtherNativeClaimAndReleasedClaim() {
        field("holdFirst").setBoolean(base,false)
        val oldFinished=CountDownLatch(1);val releaseOld=CountDownLatch(1)
        val followupEntered=CountDownLatch(1);val releaseFollowup=CountDownLatch(1)
        val actualOwner=AtomicReference<AgentForegroundService.OrdinaryRunOwner>()
        val actualToken=AtomicReference<CancellationToken>()
        val first=java.util.concurrent.atomic.AtomicBoolean(true)
        val prior=service.mainChatRunner
        val steerText="Exact late STEER whose claim must not transfer"
        service.mainChatRunner=AgentForegroundService.MainChatRunner {runtime,goal,history,attachments,token,progress ->
            if(goal==steerText) {
                actualOwner.set(CoreAgentRuntime::class.java.getDeclaredField("ordinaryRunOwner").apply {isAccessible=true}.get(runtime) as AgentForegroundService.OrdinaryRunOwner)
                actualToken.set(token);followupEntered.countDown();check(releaseFollowup.await(20,TimeUnit.SECONDS))
            }
            prior.run(runtime,goal,history,attachments,token,progress).also {
                if(first.compareAndSet(true,false)) {oldFinished.countDown();check(releaseOld.await(20,TimeUnit.SECONDS))}
            }
        }
        field("socketReply").set(base,{index:Int ->reply("Native answer $index")})
        try {
            send("Original source for exact follow-up negative")
            until("Original reader closed") {oldFinished.count==0L}
            assertTrue(storage.onDatabaseWorker {AgentForegroundService.interruptAndSend(context,session,steerText,arrayListOf())})
            releaseOld.countDown()
            until("Actual follow-up owner captured before ACK/provider") {followupEntered.count==0L}
            val owner=actualOwner.get();val token=actualToken.get()
            storage.onDatabaseWorker {owner.revalidateSource(token)}
            val sourceId=owner.inputMessageId
            val otherText="Another genuine STEER is not this owner claim"
            assertTrue(storage.onDatabaseWorker {AgentForegroundService.interruptAndSend(context,session,otherText,arrayListOf())})
            val other=storage.onDatabaseWorker {LocalRunStore(context).readConversationMessages(session).single {it.optString("role")=="user" && it.optString("content")==otherText}.getString("messageId")}
            assertNotEquals(sourceId,other)
            val physical=owner.javaClass.getDeclaredField("physicalOwner").apply {isAccessible=true}.get(owner) as AgentForegroundService.CentralSubmissionOwner
            physical.requireFollowUpClaim(sourceId)
            try {physical.requireFollowUpClaim(other);fail("One native follow-up claim authorized a different real USER")}
            catch(expected:IllegalStateException) {}
            // Invoke the existing denial-only native cleanup; never fabricate a replacement claim.
            AgentForegroundService::class.java.getDeclaredMethod("releaseFollowUpClaim",String::class.java,String::class.java).apply {isAccessible=true}.invoke(null,session,sourceId)
            try {owner.requireCurrent(token);fail("Released native claim remained current")}
            catch(expected:IllegalStateException) {}
            var rejected=false
            try {storage.onDatabaseWorker {owner.revalidateSource(token)}}catch(expected:Exception){rejected=true}
            assertTrue(rejected)
            assertEquals(1,get<AtomicInteger>("calls").get())
            assertNull(storage.onDatabaseWorker {LocalRunStore(context).readConversationInputState(session,sourceId)})
        } finally {AgentForegroundService.stopRun(session);releaseOld.countDown();releaseFollowup.countDown()}
    }
    @Test fun preAckLateSteerRemainsPendingUntilExistingRecoveryRunsAfterFreshUser() {
        field("holdFirst").setBoolean(base,false)
        val oldFinished=CountDownLatch(1);val releaseOld=CountDownLatch(1)
        val followupEntered=CountDownLatch(1);val releaseFollowup=CountDownLatch(1)
        val freshEntered=CountDownLatch(1);val releaseFresh=CountDownLatch(1)
        val first=java.util.concurrent.atomic.AtomicBoolean(true)
        val followups=AtomicInteger()
        val firstOwner=AtomicReference<Any>()
        val prior=service.mainChatRunner
        val steerText="Late STEER still pending when initial boundary is displaced"
        val freshText="Fresh USER owns priority over pending late STEER"
        service.mainChatRunner=AgentForegroundService.MainChatRunner {runtime,goal,history,attachments,token,progress ->
            if(goal==steerText) {
                val owner=CoreAgentRuntime::class.java.getDeclaredField("ordinaryRunOwner").apply {isAccessible=true}.get(runtime)
                if(followups.incrementAndGet()==1) {
                    firstOwner.set(owner);followupEntered.countDown();check(releaseFollowup.await(20,TimeUnit.SECONDS))
                } else assertNotSame("Exact pending recovery gets a new actual physical owner",firstOwner.get(),owner)
            } else if(goal==freshText) {
                freshEntered.countDown();check(releaseFresh.await(20,TimeUnit.SECONDS))
            }
            prior.run(runtime,goal,history,attachments,token,progress).also {
                if(first.compareAndSet(true,false)) {oldFinished.countDown();check(releaseOld.await(20,TimeUnit.SECONDS))}
            }
        }
        field("socketReply").set(base,{index:Int ->reply("Native safe answer $index")})
        try {
            val original=send("Initial USER before late STEER pre-ACK edge")
            until("Initial native reader closed") {oldFinished.count==0L}
            assertTrue(storage.onDatabaseWorker {AgentForegroundService.interruptAndSend(context,session,steerText,arrayListOf())})
            val steer=storage.onDatabaseWorker {LocalRunStore(context).readConversationMessages(session).single {it.optString("role")=="user" && it.optString("content")==steerText}.getString("messageId")}
            releaseOld.countDown()
            until("Actual late-STEER owner before first provider/ACK") {followupEntered.count==0L}
            val fresh=send(freshText)
            releaseFollowup.countDown()
            until("Fresh owner starts before pending late-STEER retry") {freshEntered.count==0L}
            val store=LocalRunStore(context)
            assertNull(storage.onDatabaseWorker {store.readConversationInputState(session,steer)})
            assertFalse(storage.onDatabaseWorker {store.handledConversationMessageIds(session,listOf(steer))}.contains(steer))
            assertTrue(storage.onDatabaseWorker {store.readPendingInterruptRequests(session)}.any {it.optString("requestId")==steer})
            val partial=storage.onDatabaseWorker {store.readConversationMessages(session)}.filter {it.optString("role")=="assistant" && it.optString("userMessageId")==steer}
            assertEquals(1,partial.size);assertEquals("PARTIAL",partial.single().getString("status"))
            assertEquals("Interrupted pre-ACK run made no provider/effect call",1,get<AtomicInteger>("calls").get())
            assertEquals(1,followups.get())
            releaseFresh.countDown()
            until("Existing pending interrupt recovery runs safely after fresh USER") {
                storage.onDatabaseWorker {store.readCentralSubmissions(session).single {it.userMessageId==fresh}}.state=="COMPLETED" &&
                    storage.onDatabaseWorker {store.readConversationMessages(session)}.any {it.optString("role")=="assistant" && it.optString("userMessageId")==steer && it.optString("status")=="COMPLETED"}
            }
            until("Physical owner released") {!AgentForegroundService.hasActiveRun(session)}
            assertEquals(2,followups.get());assertEquals(3,get<AtomicInteger>("calls").get())
            assertEquals("incorporated",storage.onDatabaseWorker {store.readConversationInputState(session,steer)})
            assertFalse(storage.onDatabaseWorker {store.readPendingInterruptRequests(session)}.any {it.optString("requestId")==steer})
            assertEquals(2,storage.onDatabaseWorker {store.readCentralSubmissions(session)}.size)
            assertEquals(1L,root(original).executions);assertEquals(1L,root(fresh).executions)
            val requests=get<List<String>>("requests")
            assertTrue(requests[1].contains(freshText));assertTrue(requests[2].contains(steerText))
            repeat(3){storage.onDatabaseWorker {service.runPendingInputRecoverySafely(session)};call("pump")}
            service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS)
            assertEquals("No stale pending request or duplicate queued intent replays the source",3,get<AtomicInteger>("calls").get())
        } finally {AgentForegroundService.stopRun(session);releaseOld.countDown();releaseFollowup.countDown();releaseFresh.countDown()}
    }
}
