package com.jarvys.agent;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.jarvys.agent.providers.ChatCompletionsConfig;
import com.jarvys.agent.workspaces.WorkspaceIdentity;
import org.junit.*;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import java.lang.reflect.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk={34},application=TreeNativeTestApplication.class,shadows={ArtifactOsShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class OrdinaryRegenerationAvailabilityTest {
 final CentralAvailabilityServiceTest base=new CentralAvailabilityServiceTest();
 @Rule public TestRule rules=base.getRules();
 final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
 final AtomicInteger calls=new AtomicInteger();
 final AtomicBoolean socketReturned=new AtomicBoolean();
 final AtomicReference<Throwable> nativeFailure=new AtomicReference<>();
 boolean commitWins, steeringMode, retainedMode;
 final CountDownLatch steerEntered=new CountDownLatch(1),steerRelease=new CountDownLatch(1);
 final AtomicReference<CancellationToken> regenerationToken=new AtomicReference<>();
 final AtomicReference<CoreAgentLoop.Result> regenerationResult=new AtomicReference<>();
 Context context; AgentForegroundService service; ChatTestStore storage;

 final String session=WorkspaceIdentity.CENTRAL_SESSION;
 Object field(String name)throws Exception {Field f=base.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(base);}
 Object invoke(String name,Class<?>[] types,Object...args)throws Exception {Method m=base.getClass().getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(base,args);}catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}}
 void pump()throws Exception {invoke("pump",new Class[]{});}
 void until(java.util.function.BooleanSupplier test)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!test.getAsBoolean()){pump();if(nativeFailure.get()!=null)throw new AssertionError("Actual native runtime failed; provider calls="+calls.get(),nativeFailure.get());if(System.nanoTime()>end)fail("Native condition did not settle; provider calls="+calls.get()+"; states="+storage.onDatabaseWorker(()->new LocalRunStore(context).readCentralSubmissions(session)));Thread.sleep(10);}}
 String send(String text)throws Exception{return (String)invoke("send",new Class[]{String.class},text);}
 @Before public void setup()throws Exception {
  base.setup();context=ApplicationProvider.getApplicationContext();service=(AgentForegroundService)field("service");storage=(ChatTestStore)field("storage");
  SecretStore secrets=new SecretStore(context.getSharedPreferences("availability-fixture-secret",0));
  OpenRouterClient provider=new OpenRouterClient(secrets,new ProviderSettings(context),ChatCompletionsConfig.openRouterDefault(),endpoint->new HttpURLConnection(new URL("https://example.invalid/review")) {
   final int index=calls.incrementAndGet(); final ByteArrayOutputStream body=new ByteArrayOutputStream();
   public OutputStream getOutputStream(){return body;}
   public int getResponseCode(){if(steeringMode && index==2){steerEntered.countDown();try{assertTrue(steerRelease.await(20,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}if(index==(retainedMode?1:steeringMode?3:2) && !commitWins){entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){} socketReturned.set(true);}return 200;}
   public InputStream getInputStream(){
    if(index==(retainedMode?1:steeringMode?3:2) && !commitWins) return new ByteArrayInputStream("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"answer 2\",\"tool_calls\":[{\"id\":\"late-regeneration-effect\",\"type\":\"function\",\"function\":{\"name\":\"workspaces\",\"arguments\":\"{}\"}}]}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new ByteArrayInputStream(("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"answer "+index+"\"}}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
   public void connect(){} public void disconnect(){} public boolean usingProxy(){return false;}
  });
  service.runtimeFactory=(c,id,skills)->new CoreAgentRuntime(c,id,skills,CorePromptBudget.standard(),provider);
  service.mainChatRunner=(runtime,goal,history,attachments,token,progress)->{
   boolean regeneration=calls.get()==(retainedMode?0:1);if(regeneration)regenerationToken.set(token);
   CoreAgentLoop.Result result;
   try {result=runtime.runMainChat(goal,history,attachments,token,progress);}
   catch(java.util.concurrent.CancellationException stopped){throw stopped;}
   catch(RuntimeException failure){nativeFailure.compareAndSet(null,failure);throw failure;}
   if(regeneration){regenerationResult.set(result);if(commitWins){entered.countDown();try{assertTrue(release.await(20,TimeUnit.SECONDS));}catch(InterruptedException failure){throw new AssertionError(failure);}}}
   return result;
  };
 }
 @After public void cleanup(){steerRelease.countDown();release.countDown();base.cleanup();}
 static String requiredMessageId(org.json.JSONObject row) {
  try {return row.getString("messageId");}
  catch(org.json.JSONException malformed) {throw new AssertionError("Native messageId is required",malformed);}
 }
 @Test public void freshUserDisplacesRegenerationBeforeStaleSocketReturns()throws Exception {
  String original=send("Original completed request");
  until(()->storage.onDatabaseWorker(()->new LocalRunStore(context).readCentralSubmissions(session)).stream().anyMatch(r->r.getUserMessageId().equals(original)&&r.getState().equals("COMPLETED")));
  service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS);pump();
  com.jarvys.agent.chatdb.CentralSubmissionSnapshot originalRow=storage.onDatabaseWorker(()->new LocalRunStore(context).readCentralSubmissions(session)).stream().filter(r->r.getUserMessageId().equals(original)).findFirst().get();
  com.jarvys.agent.workspaces.TreeRoot originalRoot=(com.jarvys.agent.workspaces.TreeRoot)invoke("root",new Class[]{String.class},original);
  com.jarvys.agent.chatdb.RegenerationPromptValue event=storage.onDatabaseWorker(()->{LocalRunStore store=new LocalRunStore(context);String assistant=requiredMessageId(store.readConversationMessages(session).stream().filter(r->r.optString("role").equals("assistant")).findFirst().get());return store.regenerateAssistantIfLatest(session,assistant);});
  AgentForegroundService.startRegenerateAssistant(context,event.getText(),new ArrayList<>(),session,event.getUserMessageId(),event.getResumeReceipt(),event.getNativeRegenerationReceipt());
  until(()->entered.getCount()==0);
  List<com.jarvys.agent.chatdb.PersonalMemorySessionView> heldMemory=storage.onDatabaseWorker(()->com.jarvys.agent.chatdb.ChatDatabaseBridge.getInstance(context).personalAgentMemorySessions()).stream().filter(r->original.equals(r.getOwnerMessageId())).collect(java.util.stream.Collectors.toList());
  String fresh=send("Fresh request while ordinary regeneration is held");
  until(()->storage.onDatabaseWorker(()->new LocalRunStore(context).readCentralSubmissions(session)).stream().anyMatch(r->r.getUserMessageId().equals(fresh)&&r.getState().equals("COMPLETED")));
  service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS);
  assertEquals("Fresh USER completed while stale transport is still physically held",1L,release.getCount());
  assertEquals(3,calls.get());
  assertNotNull(regenerationResult.get());assertTrue(regenerationResult.get().isOrdinaryInterrupted());
  assertFalse(regenerationToken.get().wasStoppedByUser());
  assertEquals("Ordinary interruption cannot falsely finalize or capture passive memory",heldMemory,
    storage.onDatabaseWorker(()->com.jarvys.agent.chatdb.ChatDatabaseBridge.getInstance(context).personalAgentMemorySessions()).stream().filter(r->original.equals(r.getOwnerMessageId())).collect(java.util.stream.Collectors.toList()));
  List<com.jarvys.agent.chatdb.StoredMessage> rows=storage.onDatabaseWorker(()->com.jarvys.agent.chatdb.ChatDatabaseBridge.getInstance(context).readConversationMessagesSync(session));
  assertEquals(1L,rows.stream().filter(r->original.equals(r.getOwnerUserMessageId())&&"PARTIAL".equals(r.getStatus())).count());
  assertEquals(originalRow,storage.onDatabaseWorker(()->new LocalRunStore(context).readCentralSubmissions(session)).stream().filter(r->r.getUserMessageId().equals(original)).findFirst().get());
  assertEquals(originalRoot,storage.onDatabaseWorker(()->{try(com.jarvys.agent.workspaces.WorkspaceRegistry db=new com.jarvys.agent.workspaces.WorkspaceRegistry(context)){return new com.jarvys.agent.workspaces.WorkspaceTreeStore(db).getRoot(originalRoot.getRootId());}}));
  assertNull(com.jarvys.agent.chatdb.NativeRegenerationReceipt.lookupLive(event.getNativeRegenerationReceipt().getOpaqueId()));
  release.countDown();until(()->socketReturned.get());pump();
  assertEquals(3,calls.get());
  assertFalse(storage.onDatabaseWorker(()->new LocalRunStore(context).readModelTranscriptRows(session)).stream().anyMatch(r->r.toString().contains("late-regeneration-effect")));
  assertFalse(storage.onDatabaseWorker(()->new LocalRunStore(context).readConversationMessages(session)).stream().anyMatch(r->r.optString("content").contains("answer 2")));
 }
 String beginRegeneration()throws Exception {
  String original=send("Original completed request");
  until(()->storage.onDatabaseWorker(()->new LocalRunStore(context).readCentralSubmissions(session)).stream().anyMatch(r->r.getUserMessageId().equals(original)&&r.getState().equals("COMPLETED")));
  service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS);pump();
  com.jarvys.agent.chatdb.RegenerationPromptValue event=storage.onDatabaseWorker(()->{LocalRunStore store=new LocalRunStore(context);String assistant=requiredMessageId(store.readConversationMessages(session).stream().filter(r->r.optString("role").equals("assistant")).findFirst().get());return store.regenerateAssistantIfLatest(session,assistant);});
  AgentForegroundService.startRegenerateAssistant(context,event.getText(),new ArrayList<>(),session,event.getUserMessageId(),event.getResumeReceipt(),event.getNativeRegenerationReceipt());
  until(()->entered.getCount()==0);return original;
 }
 @Test public void committedRegenerationAnswerWinsWithoutInterruptionOrReplay()throws Exception {
  commitWins=true;String original=beginRegeneration();
  assertFalse(regenerationResult.get().isOrdinaryInterrupted());
  String fresh;
  try {fresh=send("New USER after actual regeneration proposal committed");assertEquals(2,calls.get());}
  finally {release.countDown();}
  until(()->storage.onDatabaseWorker(()->new LocalRunStore(context).readCentralSubmissions(session)).stream().anyMatch(r->r.getUserMessageId().equals(fresh)&&r.getState().equals("COMPLETED")));
  service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS);
  List<com.jarvys.agent.chatdb.StoredMessage> rows=storage.onDatabaseWorker(()->com.jarvys.agent.chatdb.ChatDatabaseBridge.getInstance(context).readConversationMessagesSync(session));
  assertEquals(0L,rows.stream().filter(r->original.equals(r.getOwnerUserMessageId())&&"PARTIAL".equals(r.getStatus())).count());
  assertTrue(rows.stream().anyMatch(r->original.equals(r.getOwnerUserMessageId())&&"COMPLETED".equals(r.getStatus())&&r.getText().contains("answer 2")));
  assertEquals(3,calls.get());
 }
 @Test public void actualStopOfRegenerationIsNotOrdinaryInterruption()throws Exception {
  String original=beginRegeneration();
  AgentForegroundService.stopRun(session);service.awaitStopTasksForTesting().get(10,TimeUnit.SECONDS);
  service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS);
  assertTrue(regenerationToken.get().wasStoppedByUser());
  assertTrue(regenerationResult.get()==null || !regenerationResult.get().isOrdinaryInterrupted());
  assertEquals(1L,release.getCount());
  String fresh=send("Genuine USER after native STOP");
  until(()->storage.onDatabaseWorker(()->new LocalRunStore(context).readCentralSubmissions(session)).stream().anyMatch(r->r.getUserMessageId().equals(fresh)&&r.getState().equals("COMPLETED")));
  release.countDown();until(()->socketReturned.get());
  assertEquals(3,calls.get());
  assertFalse(storage.onDatabaseWorker(()->new LocalRunStore(context).readModelTranscriptRows(session)).stream().anyMatch(r->r.toString().contains("late-regeneration-effect")));
 }

 @Test public void steeredRegenerationInterruptionRetainsActualFinalOwner()throws Exception {
  steeringMode=true;
  String original=send("Original successful operation");
  until(()->storage.onDatabaseWorker(()->new LocalRunStore(context).readCentralSubmissions(session)).stream().anyMatch(r->r.getUserMessageId().equals(original)&&r.getState().equals("COMPLETED")));
  service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS);pump();
  com.jarvys.agent.chatdb.CentralSubmissionSnapshot originalRow=storage.onDatabaseWorker(()->new LocalRunStore(context).readCentralSubmissions(session)).stream().filter(r->r.getUserMessageId().equals(original)).findFirst().get();
  com.jarvys.agent.chatdb.RegenerationPromptValue event=storage.onDatabaseWorker(()->{LocalRunStore store=new LocalRunStore(context);String assistant=requiredMessageId(store.readConversationMessages(session).stream().filter(r->r.optString("role").equals("assistant")).findFirst().get());return store.regenerateAssistantIfLatest(session,assistant);});
  AgentForegroundService.startRegenerateAssistant(context,event.getText(),new ArrayList<>(),session,event.getUserMessageId(),event.getResumeReceipt(),event.getNativeRegenerationReceipt());
  until(()->steerEntered.getCount()==0);
  String steer;
  try {
   assertTrue(storage.onDatabaseWorker(()->AgentForegroundService.interruptAndSend(context,session,"Exact STEER during regeneration",new ArrayList<>())));
   steer=storage.onDatabaseWorker(()->new LocalRunStore(context).readConversationMessages(session)).stream().filter(r->r.optString("role").equals("user")&&r.optString("content").equals("Exact STEER during regeneration")).findFirst().get().getString("messageId");
  } finally {steerRelease.countDown();}
  until(()->entered.getCount()==0);
  String fresh=send("Independent fresh USER after accepted STEER");
  until(()->storage.onDatabaseWorker(()->new LocalRunStore(context).readCentralSubmissions(session)).stream().anyMatch(r->r.getUserMessageId().equals(fresh)&&r.getState().equals("COMPLETED")));
  service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS);
  assertEquals(1L,release.getCount());assertEquals(4,calls.get());
  List<com.jarvys.agent.chatdb.StoredMessage> rows=storage.onDatabaseWorker(()->com.jarvys.agent.chatdb.ChatDatabaseBridge.getInstance(context).readConversationMessagesSync(session));
  assertEquals(1L,rows.stream().filter(r->steer.equals(r.getOwnerUserMessageId())&&"PARTIAL".equals(r.getStatus())).count());
  assertEquals(0L,rows.stream().filter(r->original.equals(r.getOwnerUserMessageId())&&"PARTIAL".equals(r.getStatus())).count());
  assertEquals(originalRow,storage.onDatabaseWorker(()->new LocalRunStore(context).readCentralSubmissions(session)).stream().filter(r->r.getUserMessageId().equals(original)).findFirst().get());
  assertTrue(regenerationResult.get().isOrdinaryInterrupted());assertFalse(regenerationToken.get().wasStoppedByUser());
 }

 @Test public void retainedV99CompletedHistoryRegenerationHasSameInterruptionBoundaryWithoutEnrollment()throws Exception {
  retainedRegeneration("COMPLETED","retained-v99-run",true);
 }
 @Test public void retainedFailedPositionalManualRegenerationKeepsOrdinaryInterruptionWithoutInventedAuthority()throws Exception {
  retainedRegeneration("FAILED",null,false);
 }
 @Test public void retainedUnspecifiedLegacyStatusRegeneratesWithoutInferringCompletionOrRootAuthority()throws Exception {
  retainedRegeneration(null,null,false);
 }
 private void retainedRegeneration(String priorStatus,String priorRun,boolean explicitOwner)throws Exception {
  retainedMode=true;
  String original=storage.onDatabaseWorker(()->{LocalRunStore store=new LocalRunStore(context);String user=store.appendConversationMessage(session,"user","Retained v99 USER request");store.appendConversationMessage(session,"assistant","Retained successful answer",0L,priorRun,explicitOwner?user:null,priorStatus,null);return user;});
  assertTrue(storage.onDatabaseWorker(()->new LocalRunStore(context).readCentralSubmissions(session)).isEmpty());
  com.jarvys.agent.chatdb.RegenerationPromptValue event=storage.onDatabaseWorker(()->{LocalRunStore store=new LocalRunStore(context);String assistant=requiredMessageId(store.readConversationMessages(session).stream().filter(r->r.optString("role").equals("assistant")).findFirst().get());return store.regenerateAssistantIfLatest(session,assistant);});
  assertNotNull(event.getNativeRegenerationReceipt());assertEquals("RETAINED",event.getNativeRegenerationReceipt().getSourceKind());assertNull(event.getResumeReceipt());
  AgentForegroundService.startRegenerateAssistant(context,event.getText(),new ArrayList<>(),session,event.getUserMessageId(),event.getResumeReceipt(),event.getNativeRegenerationReceipt());
  until(()->entered.getCount()==0);
  String fresh=send("First native NEW_TURN after retained regeneration");
  until(()->storage.onDatabaseWorker(()->new LocalRunStore(context).readCentralSubmissions(session)).stream().anyMatch(r->r.getUserMessageId().equals(fresh)&&r.getState().equals("COMPLETED")));
  service.awaitSessionIdleForTesting(session).get(10,TimeUnit.SECONDS);
  assertEquals(1L,release.getCount());assertEquals(2,calls.get());assertTrue(regenerationResult.get().isOrdinaryInterrupted());
  List<com.jarvys.agent.chatdb.CentralSubmissionSnapshot> submissions=storage.onDatabaseWorker(()->new LocalRunStore(context).readCentralSubmissions(session));assertEquals(1,submissions.size());assertEquals(fresh,submissions.get(0).getUserMessageId());
  List<com.jarvys.agent.chatdb.StoredMessage> rows=storage.onDatabaseWorker(()->com.jarvys.agent.chatdb.ChatDatabaseBridge.getInstance(context).readConversationMessagesSync(session));
  assertEquals(1L,rows.stream().filter(r->original.equals(r.getOwnerUserMessageId())&&"PARTIAL".equals(r.getStatus())).count());
  release.countDown();until(()->socketReturned.get());
  assertFalse(storage.onDatabaseWorker(()->new LocalRunStore(context).readModelTranscriptRows(session)).stream().anyMatch(r->r.toString().contains("late-regeneration-effect")));
 }

}
