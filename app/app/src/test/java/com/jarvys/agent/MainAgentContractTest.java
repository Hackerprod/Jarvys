package com.jarvys.agent;

import static org.junit.Assert.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Deterministic contracts and real loop/store events. This is not a live-model quality evaluation. */
public class MainAgentContractTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test public void coreIsBoundedOriginalPrincipalContractWithLocale() {
    assertTrue(MainAgentPrompt.CORE.length() <= 6500);
    String prompt = MainAgentPrompt.core(Locale.forLanguageTag("es-MX"), true);
    assertTrue(prompt.contains("Runtime response locale: es-MX"));
    for (String fact : Arrays.asList("latest request", "NEVER_LAUNCHED", "INTERRUPTED_UNCERTAIN",
        "not task completion", "permission denial", "not instructions or permission", "physical-device acceptance"))
      assertTrue(fact, prompt.contains(fact));
    assertTrue(prompt.contains("never put private reasoning there"));
  }

  @Test public void unsupportedInterimScopeDoesNotPromiseUpdates() {
    assertTrue(MainAgentPrompt.core(null, false).contains("no user-facing interim channel"));
    assertFalse(MainAgentPrompt.core(null, false).contains("shown as an interim update"));
  }

  @Test public void actualRegistryGatesDeliveryAndSkills() {
    CoreAgentRuntime runtime = new CoreAgentRuntime(Collections.emptyList(), Collections.emptyList(),
        Collections.emptyList(), Collections.emptyList());
    String absent = runtime.instructions(new CoreToolRegistry(Collections.emptyList()), true);
    assertFalse(absent.contains("File delivery:"));
    assertFalse(absent.contains("Skill authoring path:"));
    assertFalse(absent.contains("Bots catalog:"));
    assertFalse(absent.contains("Conversation continuity:"));
    assertFalse(absent.contains("Bounded delegation:"));
    String present = runtime.instructions(new CoreToolRegistry(Arrays.asList(
        tool("deliver_file", (a,t) -> CoreToolResult.success("ok")),
        tool("read_conversation_artifact", (a,t) -> CoreToolResult.success("ok")),
        tool("delegate_subtask", (a,t) -> CoreToolResult.success("ok")))), true);
    assertTrue(present.contains("File delivery:"));
    assertTrue(present.contains("Conversation continuity:"));
    assertTrue(present.contains("Bounded delegation:"));
    assertFalse(present.contains("IMPLEMENT THE COMPLETE BEHAVIOR"));
  }

  @Test public void nonPrincipalScopeKeepsSmallSeparateCore() {
    CoreAgentRuntime runtime = new CoreAgentRuntime(Collections.emptyList(), Collections.emptyList(),
        Collections.emptyList(), Collections.emptyList());
    String child = runtime.instructions(new CoreToolRegistry(Collections.emptyList()), false);
    assertFalse(child.contains("UNDERSTAND AND COMPLETE"));
    assertFalse(child.contains("Runtime response locale:"));
  }

  @Test public void completedDelegationDoesNotCertifyTask() throws Exception {
    CoreToolResult result = DelegateSubtaskTool.receipt(new CoreAgentLoop.Result("child-1", "Found evidence", 2, "COMPLETED"));
    JSONObject json = new JSONObject(result.content);
    assertTrue(result.success);
    assertEquals("COMPLETED", json.getString("run_outcome"));
    assertFalse(json.getBoolean("task_verified"));
    assertEquals(2, json.getInt("model_turns"));
  }

  @Test public void everyIncompleteChildOutcomeSurvivesReceipt() throws Exception {
    for (String outcome : Arrays.asList("PARTIAL", "STOPPED", "FAILED", "UNKNOWN")) {
      CoreToolResult result = DelegateSubtaskTool.receipt(new CoreAgentLoop.Result("child", "Written once; check state", 3, outcome));
      assertFalse(outcome, result.success);
      JSONObject json = new JSONObject(result.content);
      assertEquals(outcome, json.getString("run_outcome"));
      assertEquals("Written once; check state", json.getString("text"));
    }
  }

  @Test public void delegationTextIsBoundedWithExplicitFlag() throws Exception {
    char[] chars = new char[14000]; Arrays.fill(chars, 'x');
    JSONObject receipt = new JSONObject(DelegateSubtaskTool.receipt(
        new CoreAgentLoop.Result("c", new String(chars), 1, "PARTIAL")).content);
    assertEquals(12000, receipt.getString("text").length());
    assertTrue(receipt.getBoolean("text_truncated"));
    assertEquals("PARTIAL", receipt.getString("run_outcome"));
  }

  @Test public void missingChildTextCannotBeSuccessful() {
    assertFalse(DelegateSubtaskTool.receipt(null).success);
    assertFalse(DelegateSubtaskTool.receipt(new CoreAgentLoop.Result("c", " ", 1, "COMPLETED")).success);
  }

  @Test public void scopedDelegationUsesTypedRuntimeOutcome() {
    DelegateSubtaskTool delegate = new DelegateSubtaskTool(
        (objective, scope, tools, skills, token) -> new CoreAgentLoop.Result("c", "Timeout after a write", 2, "PARTIAL"),
        new CoreToolRegistry(Collections.emptyList()), Collections.emptyList(), false);
    CoreToolResult result = delegate.execute(Collections.singletonMap("objective", "Inspect"), CancellationToken.uncancellable());
    assertFalse(result.success);
    assertTrue(result.content.contains("PARTIAL"));
    assertTrue(result.content.contains("Timeout after a write"));
  }

  @Test public void legacyTextAdapterCannotInventCompletion() {
    DelegateSubtaskTool delegate = new DelegateSubtaskTool((objective, tools, skills, token) -> "some text",
        Collections.emptyList(), Collections.emptyList());
    CoreToolResult result = delegate.execute(Collections.singletonMap("objective", "Inspect"), CancellationToken.uncancellable());
    assertFalse(result.success);
    assertTrue(result.content.contains("UNKNOWN"));
  }

  @Test public void commentaryIsDurableBeforeToolAndNotDuplicatedInContext() throws Exception {
    File files = temporary.newFolder(); LocalRunStore store = new LocalRunStore(files);
    String session = "principal-progress"; store.appendConversationMessage(session, "user", "Inspect");
    List<String> order = new ArrayList<>(); AtomicInteger turns = new AtomicInteger();
    CoreAgentLoop loop = new CoreAgentLoop((history,prompt,tools,token) -> turns.getAndIncrement() == 0
        ? reply("I found the source; checking its result.", "check") : new ModelReply("Verified", Collections.emptyList()),
        new CoreToolRegistry(Collections.singletonList(tool("read", (args,token) -> {
          order.add("tool"); return CoreToolResult.success("source checked");
        }))), "system", session);
    new MainChatTranscriptStore(files, session, store).attach(loop, Collections.emptyList());
    CoreAgentLoop.Result result = loop.run("Inspect", Collections.emptyList(), CancellationToken.uncancellable(),
        new CoreAgentLoop.ProgressListener() {
          public void onProgress(String stage, String text) { if (stage.equals("answer")) order.add("final"); }
          public void onAssistantProgress(String callId, String text) {
            assertNotNull(store.readAssistantProgress(session, callId));
            order.add("progress");
          }
        });
    assertEquals(Arrays.asList("progress", "tool", "final"), order);
    assertEquals("COMPLETED", result.outcome);
    List<AgentRunUiEvent> restored = new LocalRunStore(files).readConversationTimeline(session);
    assertEquals(1, restored.stream().filter(e -> "PROGRESS".equals(e.getStage())).count());
    assertEquals(1, store.loadConversationContext(session).stream().filter(t -> t.content.contains("I found the source")).count());
    assertEquals(1, store.readConversationMessages(session).size());
  }

  @Test public void failedCheckpointCannotPublishProgressOrLaunchEffect() {
    AtomicInteger updates = new AtomicInteger(), effects = new AtomicInteger();
    CoreAgentLoop loop = new CoreAgentLoop((h,p,d,t) -> reply("Checking", "blocked"),
        new CoreToolRegistry(Collections.singletonList(tool("read", (a,t) -> { effects.incrementAndGet(); return CoreToolResult.success("x"); }))), "system", "checkpoint");
    loop.setCheckpointListener(checkpoint -> { if (checkpoint.transcript.stream().anyMatch(t -> t.kind == ConversationTurn.Kind.TOOL_CALLS)) throw new IllegalStateException("disk failure"); });
    try { loop.run("inspect", Collections.emptyList(), CancellationToken.uncancellable(), listener(updates)); fail(); }
    catch (CoreAgentLoop.CheckpointFailure expected) { }
    assertEquals(0, updates.get()); assertEquals(0, effects.get());
  }

  @Test public void cancellationAfterProgressPreventsToolEffect() {
    CancellationToken token = CancellationToken.cancellable(); AtomicInteger effects = new AtomicInteger();
    CoreAgentLoop loop = new CoreAgentLoop((h,p,d,t) -> reply("Checking", "cancel"),
        new CoreToolRegistry(Collections.singletonList(tool("read", (a,t) -> { effects.incrementAndGet(); return CoreToolResult.success("x"); }))), "system", "cancel");
    try { loop.run("inspect", Collections.emptyList(), token, new CoreAgentLoop.ProgressListener() {
      public void onProgress(String stage,String message) { }
      public void onAssistantProgress(String id,String message) { token.cancel(); }
    }); fail(); } catch (java.util.concurrent.CancellationException expected) { }
    assertEquals(0,effects.get());
  }

  @Test public void finalOnlyReplyDoesNotBecomeInterim() {
    AtomicInteger count = new AtomicInteger();
    new CoreAgentLoop((h,p,d,t) -> new ModelReply("Answer", Collections.emptyList()),
        new CoreToolRegistry(Collections.emptyList()), "system", "final").run("question", Collections.emptyList(), CancellationToken.uncancellable(), listener(count));
    assertEquals(0,count.get());
  }

  @Test public void blankToolTextDoesNotBecomeInterim() {
    AtomicInteger count = new AtomicInteger(), turn = new AtomicInteger();
    new CoreAgentLoop((h,p,d,t) -> turn.getAndIncrement() == 0 ? reply("   ", "blank") : new ModelReply("Answer",Collections.emptyList()),
        new CoreToolRegistry(Collections.singletonList(tool("read", (a,t) -> CoreToolResult.success("ok")))), "system", "blank")
        .run("question",Collections.emptyList(),CancellationToken.uncancellable(),listener(count));
    assertEquals(0,count.get());
  }

  @Test public void pendingInputIsDurableInertAndOnlyDispatchedOnce() throws Exception {
    File files=temporary.newFolder(); LocalRunStore store=new LocalRunStore(files);
    store.appendConversationMessage("interrupt","user","old");
    String id=store.appendInterruptRequest("interrupt","new instruction",Collections.singletonList("skill"),true);
    LocalRunStore reopened=new LocalRunStore(files);
    assertEquals(1,reopened.readConversationMessages("interrupt").size());
    assertEquals(1,reopened.loadConversationContext("interrupt").size());
    assertEquals(1,reopened.readConversationTimeline("interrupt").stream().filter(e->"QUEUED".equals(e.getStage())).count());
    assertTrue(reopened.dispatchInterruptRequest("interrupt",id).getBoolean("memoryDisabled"));
    assertNull(reopened.dispatchInterruptRequest("interrupt",id));
    assertEquals(2,reopened.readConversationMessages("interrupt").size());
    assertEquals(id,reopened.latestUserMessageId("interrupt"));
    assertFalse(reopened.readConversationTimeline("interrupt").stream().anyMatch(e->"QUEUED".equals(e.getStage())));
  }

  @Test public void pendingInputNeverCrossesConversations() throws Exception {
    LocalRunStore store=new LocalRunStore(temporary.newFolder());
    String id=store.appendInterruptRequest("chat-one","change direction",Collections.emptyList(),false);
    assertNull(store.dispatchInterruptRequest("chat-two",id));
    assertTrue(store.loadConversationContext("chat-two").isEmpty());
    assertEquals(1,store.readConversationTimeline("chat-one").size());
  }

  @Test public void repeatedTextHasDistinctRequestIdentity() throws Exception {
    LocalRunStore store=new LocalRunStore(temporary.newFolder());
    String a=store.appendInterruptRequest("same","same text",Collections.emptyList(),false);
    String b=store.appendInterruptRequest("same","same text",Collections.emptyList(),false);
    assertNotEquals(a,b); assertNotNull(store.dispatchInterruptRequest("same",a)); assertNotNull(store.dispatchInterruptRequest("same",b));
    assertEquals(2,store.readConversationMessages("same").size());
  }

  @Test public void invalidReplacementDoesNotPersist() throws Exception {
    LocalRunStore store=new LocalRunStore(temporary.newFolder());
    try { store.appendInterruptRequest("main"," ",Collections.emptyList(),false); fail(); } catch (IllegalArgumentException expected) { }
    assertTrue(store.readConversationTimeline("main").isEmpty());
  }

  @Test public void regenerationHidesInterimWithoutReplayingEffects() throws Exception {
    File files=temporary.newFolder(); LocalRunStore store=new LocalRunStore(files); String session="regen-progress";
    String user=store.appendConversationMessage(session,"user","inspect"); AtomicInteger turns=new AtomicInteger();
    CoreAgentLoop loop=new CoreAgentLoop((h,p,d,t)->turns.getAndIncrement()==0?reply("Progress", "regen-call"):new ModelReply("Done",Collections.emptyList()),
        new CoreToolRegistry(Collections.singletonList(tool("read",(a,t)->CoreToolResult.success("ok")))),"system",session);
    new MainChatTranscriptStore(files,session,store).attach(loop,Collections.emptyList());
    CoreAgentLoop.Result result=loop.run("inspect",Collections.emptyList(),CancellationToken.uncancellable(),null);
    String answer=store.appendConversationMessage(session,"assistant",result.text,0L,result.runId,user,result.outcome);
    store.appendAssistantRegenerated(session,answer);
    assertFalse(store.readConversationTimeline(session).stream().anyMatch(e->"PROGRESS".equals(e.getStage())));
  }

  private interface Effect { CoreToolResult run(Map<String,Object> args,CancellationToken token); }
  private static CoreTool tool(String name,Effect effect) { return new CoreTool() {
    public ToolSpec declaration() { return new ToolSpec(name,"test","test","core",ToolSpec.Status.IMPLEMENTED,Collections.emptyMap(),Collections.emptyList()); }
    public CoreToolResult execute(Map<String,Object> args,CancellationToken token) { return effect.run(args,token); }
  }; }
  private static ModelReply reply(String text,String id) { return new ModelReply(text,Collections.singletonList(new ModelReply.Call(id,"read",Collections.emptyMap()))); }
  private static CoreAgentLoop.ProgressListener listener(AtomicInteger count) { return new CoreAgentLoop.ProgressListener() {
    public void onProgress(String stage,String text) { }
    public void onAssistantProgress(String callId,String text) { count.incrementAndGet(); }
  }; }
}
