package com.jarvys.agent;

import static org.junit.Assert.*;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;

/** Real loop/workspace/store/provider-request boundary; no provider or authentication requests. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class MainChatToolContinuityTest {
    @org.junit.Rule public final ChatTestStore chatTestStore = new ChatTestStore();

  @Test public void successfulWorkspaceEffectsSurviveNextUserTurnAndFreshStore() throws Exception {
    File files = chatTestStore.newFilesDirectory();
    LocalRunStore store = chatTestStore.localRunStore(files);
    String session = "workspace-continuity";
    String user = store.appendConversationMessage(session, "user", "Create a page");
    WorkspaceStore workspace = new WorkspaceStore(new File(files, "jarvys/workspaces"),
        WorkspaceStore.projectIdForSession(session));
    AtomicInteger requests = new AtomicInteger();
    List<ModelReply.Call> calls = Arrays.asList(
        new ModelReply.Call("provider-list-17", "ls", Collections.singletonMap("path", ".")),
        new ModelReply.Call("provider-write-18", "write", arguments("path", "index.html", "content", "<h1>Done</h1>")));
    CoreAgentLoop loop = new CoreAgentLoop((history, prompt, tools, token) ->
        requests.getAndIncrement() == 0 ? new ModelReply("Creating the page", calls)
            : new ModelReply("Page created", Collections.emptyList()),
        new CoreToolRegistry(WorkspaceTools.create(workspace)), "Use tools", session);
    new MainChatTranscriptStore(files, session, store).attach(loop, Collections.emptyList());
    CoreAgentLoop.Result result = loop.run("Create a page", Collections.emptyList(),
        CancellationToken.uncancellable(), new CoreAgentLoop.ProgressListener() {
          @Override public void onProgress(String stage, String message) { }
          @Override public void onToolProgress(String stage, String callId, String name,
              String detail, String previewId, String source, String auditDetail) {
            if ("tool_result".equals(stage) || "tool_error".equals(stage))
              store.appendReflectionToolEvent(session, user, name, source, stage, callId);
          }
        });
    store.appendConversationMessage(session, "assistant", result.text, result.durationMs,
        result.runId, user, result.outcome);
    assertEquals("<h1>Done</h1>", workspace.read("index.html"));
    store.appendConversationMessage(session, "user", "Did you really write it?");
    List<ConversationTurn> reloaded = chatTestStore.localRunStore(files).loadConversationContext(session);
    assertTrue("Successful write proof must be present in model context",
        reloaded.stream().anyMatch(t -> t.kind == ConversationTurn.Kind.TOOL_RESULT
            && "provider-write-18".equals(t.toolCallId) && t.content.contains("Wrote index.html")));
    assertProviderRequests(reloaded, "provider-write-18", "Wrote index.html");
  }

  @Test public void completedWriteSurvivesProviderFailureAndIsNeverReplayedOnRead() throws Exception {
    File files = chatTestStore.newFilesDirectory();
    String session = "failed-after-write";
    LocalRunStore store = chatTestStore.localRunStore(files);
    store.appendConversationMessage(session, "user", "Write the file");
    AtomicInteger effects = new AtomicInteger(), turns = new AtomicInteger();
    CoreTool write = testTool("write", (args, token) -> {
      effects.incrementAndGet();
      return CoreToolResult.success("Wrote index.html");
    });
    CoreAgentLoop loop = new CoreAgentLoop((history, prompt, tools, token) -> {
      if (turns.getAndIncrement() == 0) return new ModelReply("", Collections.singletonList(
          new ModelReply.Call("write-before-provider-failure", "write", arguments("path", "index.html"))));
      throw new IllegalStateException("provider unavailable");
    }, new CoreToolRegistry(Collections.singletonList(write)), "system", session);
    new MainChatTranscriptStore(files, session, store).attach(loop, Collections.emptyList());
    try { loop.run("Write the file", Collections.emptyList(), CancellationToken.uncancellable(), null); fail(); }
    catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("provider unavailable")); }
    for (int i = 0; i < 3; i++) {
      List<ConversationTurn> restored = chatTestStore.localRunStore(files).loadConversationContext(session);
      assertTrue(restored.stream().anyMatch(t -> t.kind == ConversationTurn.Kind.TOOL_RESULT
          && t.content.contains("Wrote index.html")));
    }
    assertEquals(1, effects.get());
  }

  @Test public void unavailableToolErrorSurvivesReloadAsExactModelResult() throws Exception {
    File files = chatTestStore.newFilesDirectory();
    String session = "unavailable-tool-error";
    LocalRunStore store = chatTestStore.localRunStore(files);
    String user = store.appendConversationMessage(session, "user", "Use the missing tool");
    String expected = "Tool error: Tool is not available in this agent scope: missing_tool";
    AtomicInteger turns = new AtomicInteger();
    CoreAgentLoop loop = new CoreAgentLoop((history, prompt, tools, token) -> turns.getAndIncrement() == 0
        ? new ModelReply("", Collections.singletonList(new ModelReply.Call("missing-tool-call", "missing_tool", Collections.emptyMap())))
        : new ModelReply("The error was reported.", Collections.emptyList()),
        new CoreToolRegistry(Collections.emptyList()), "system", session);
    new MainChatTranscriptStore(files, session, store).attach(loop, Collections.emptyList());
    CoreAgentLoop.Result result = loop.run("Use the missing tool", Collections.emptyList(), CancellationToken.uncancellable(), null);
    store.appendConversationMessage(session, "assistant", result.text, result.durationMs, result.runId, user, result.outcome);
    store.appendConversationMessage(session, "user", "Continue");

    List<ConversationTurn> reloaded = chatTestStore.localRunStore(files).loadConversationContext(session);
    List<ConversationTurn> results = new ArrayList<>();
    for (ConversationTurn turn : reloaded) if (turn.kind == ConversationTurn.Kind.TOOL_RESULT
        && "missing-tool-call".equals(turn.toolCallId)) results.add(turn);
    assertEquals(1, results.size());
    assertEquals(expected, results.get(0).content);
    assertFalse(results.get(0).content.contains("recorded tool intent was not launched"));
  }

  @Test public void completedPreflightErrorBatchIsNotReinjectedAfterCompaction() throws Exception {
    File files = chatTestStore.newFilesDirectory();
    String session = "compacted-preflight-error";
    LocalRunStore store = chatTestStore.localRunStore(files);
    String user = store.appendConversationMessage(session, "user", "Use the missing tool");
    AtomicInteger turns = new AtomicInteger();
    CoreAgentLoop loop = new CoreAgentLoop((history, prompt, tools, token) -> turns.getAndIncrement() == 0
        ? new ModelReply("", Collections.singletonList(new ModelReply.Call("compacted-missing-tool", "missing_tool", Collections.emptyMap())))
        : new ModelReply("The tool error was received.", Collections.emptyList()),
        new CoreToolRegistry(Collections.emptyList()), "system", session);
    new MainChatTranscriptStore(files, session, store).attach(loop, Collections.emptyList());
    CoreAgentLoop.Result result = loop.run("Use the missing tool", Collections.emptyList(), CancellationToken.uncancellable(), null);
    store.appendConversationMessage(session, "assistant", result.text, result.durationMs, result.runId, user, result.outcome);
    store.appendConversationMessage(session, "user", "Continue");
    assertTrue(store.readModelTranscriptRows(session).stream().anyMatch(row -> "model_tool_result".equals(row.optString("type"))
        && "compacted-missing-tool".equals(row.optString("callId"))
        && "NEVER_LAUNCHED".equals(row.optString("lifecycle"))
        && row.optString("output").equals("Tool error: Tool is not available in this agent scope: missing_tool")));
    ModelProviderClient summarizer = new ModelProviderClient() {
      @Override public ModelReply complete(String system, String prompt, List<com.jarvys.agent.device.ScreenData> images,
          List<ToolSpec> tools, String session, CancellationToken token) {
        return new ModelReply("The missing tool returned an error.", Collections.emptyList());
      }
      @Override public ModelReply completeConversation(String system, List<ConversationTurn> history, String prompt,
          List<com.jarvys.agent.device.ScreenData> images, List<ToolSpec> tools, String session, CancellationToken token) {
        return complete(system, prompt, images, tools, session, token);
      }
    };
    ConversationCompactor compactor = new ConversationCompactor(session, new CoreAgentModel(summarizer, session), store);
    compactor.compact(store.loadConversationContext(session), 32_768, "manual",
        ConversationCompactionPolicy.Mode.ALL, CancellationToken.uncancellable(), null);

    List<ConversationTurn> restored = chatTestStore.localRunStore(files).loadConversationContext(session);
    int summaryIndex = -1;
    for (int index = 0; index < restored.size(); index++)
      if (restored.get(index).kind == ConversationTurn.Kind.COMPACTION_SUMMARY) summaryIndex = index;
    assertTrue("Compaction summary should be restored", summaryIndex >= 0);
    for (int index = summaryIndex + 1; index < restored.size(); index++) {
      ConversationTurn turn = restored.get(index);
      assertFalse("Completed call must not be reinserted after the summary",
          turn.kind == ConversationTurn.Kind.TOOL_CALLS && turn.toolCalls.stream()
              .anyMatch(call -> "compacted-missing-tool".equals(call.id)));
      assertFalse("Completed result must not be reinserted after the summary",
          turn.kind == ConversationTurn.Kind.TOOL_RESULT && "compacted-missing-tool".equals(turn.toolCallId));
    }
  }

  @Test public void interruptedToolRemainsUncertainRatherThanSuccessOrAutomaticReplay() throws Exception {
    File files = chatTestStore.newFilesDirectory();
    String session = "interrupted-write";
    LocalRunStore store = chatTestStore.localRunStore(files);
    store.appendConversationMessage(session, "user", "Write the file");
    AtomicInteger effects = new AtomicInteger();
    CoreTool write = testTool("write", (args, token) -> {
      effects.incrementAndGet();
      token.cancelForTimeout();
      token.throwIfCancelled();
      return CoreToolResult.success("unreachable");
    }, CoreTool.ExecutionMode.SEQUENTIAL);
    CoreAgentLoop loop = new CoreAgentLoop((history, prompt, tools, token) -> new ModelReply("",
        Arrays.asList(new ModelReply.Call("started-write", "write", arguments("path", "index.html")),
            new ModelReply.Call("pending-write", "write", arguments("path", "second.html")))),
        new CoreToolRegistry(Collections.singletonList(write)), "system", session);
    new MainChatTranscriptStore(files, session, store).attach(loop, Collections.emptyList());
    loop.run("Write the file", Collections.emptyList(), CancellationToken.uncancellable(), null);
    List<ConversationTurn> restored = chatTestStore.localRunStore(files).loadConversationContext(session);
    assertTrue(restored.stream().anyMatch(t -> "started-write".equals(t.toolCallId)
        && t.content.contains("INTERRUPTED_UNCERTAIN")));
    assertTrue(restored.stream().anyMatch(t -> "pending-write".equals(t.toolCallId)
        && t.content.contains("NEVER_LAUNCHED")));
    assertEquals(1, effects.get());
    assertProviderRequests(restored, "started-write", "INTERRUPTED_UNCERTAIN");
  }

  @Test public void legacyToolCardsRetainOnlyHonestLimitedEvidence() {
    File files;
    try { files = chatTestStore.newFilesDirectory(); } catch (Exception e) { throw new RuntimeException(e); }
    LocalRunStore store = chatTestStore.localRunStore(files);
    String user = store.appendConversationMessage("legacy", "user", "Make a page");
    store.appendReflectionToolEvent("legacy", user, "Write", "workspace", "tool_result", "legacy-call");
    List<ConversationTurn> context = store.loadConversationContext("legacy");
    assertTrue(context.stream().anyMatch(t -> t.content.contains("legacy-call")
        && t.content.contains("unavailable")));
    assertFalse(context.stream().anyMatch(t -> t.kind == ConversationTurn.Kind.TOOL_CALLS));
    assertFalse(context.stream().anyMatch(t -> t.content.contains("index.html")));
  }

  @Test public void missingProviderCallIdsAreUniqueAcrossResponsesAndSuppliedIdsArePreserved() throws Exception {
    Method chatParser = OpenRouterClient.class.getDeclaredMethod("parseResponse", String.class);
    chatParser.setAccessible(true);
    String chat = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"write\",\"arguments\":\"{}\"}}]}}]}";
    ModelReply one = (ModelReply) chatParser.invoke(null, chat);
    ModelReply two = (ModelReply) chatParser.invoke(null, chat);
    assertFalse(one.calls.get(0).id.isEmpty());
    assertNotEquals(one.calls.get(0).id, two.calls.get(0).id);
    String supplied = chat.replace("\"function\":", "\"id\":\"actual-call-id\",\"function\":");
    assertEquals("actual-call-id", ((ModelReply) chatParser.invoke(null, supplied)).calls.get(0).id);

    Method codexParser = OpenAICodexResponsesClient.class.getDeclaredMethod("parseResponse", String.class);
    codexParser.setAccessible(true);
    String codex = "{\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"write\",\"arguments\":\"{}\"}]}}";
    ModelReply first = (ModelReply) codexParser.invoke(null, codex);
    ModelReply next = (ModelReply) codexParser.invoke(null, codex);
    assertNotEquals(first.calls.get(0).id, next.calls.get(0).id);
    assertEquals("actual-call-id", ((ModelReply) codexParser.invoke(null,
        codex.replace("\"name\":", "\"call_id\":\"actual-call-id\",\"name\":"))).calls.get(0).id);
  }

  @Test public void codexStreamUpdatesDoNotExecuteOneMissingIdCallTwice() throws Exception {
    Method parser = OpenAICodexResponsesClient.class.getDeclaredMethod("parseResponse", String.class);
    parser.setAccessible(true);
    String item = "{\"type\":\"function_call\",\"name\":\"write\",\"arguments\":\"{}\"}";
    String stream = "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":" + item + "}\n"
        + "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":" + item + "}\n"
        + "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[" + item + "]}}";
    ModelReply result = (ModelReply) parser.invoke(null, stream);
    assertEquals(1, result.calls.size());
  }

  @Test public void largeResultsUseRedactedArtifactsRecoverableAfterRestartAndScopedToChat() throws Exception {
    File files = chatTestStore.newFilesDirectory();
    String session = "large-output";
    LocalRunStore store = chatTestStore.localRunStore(files);
    store.appendConversationMessage(session, "user", "Inspect data");
    String secret = "private-secret-sentinel";
    String output = "password=" + secret + "\n" + String.join("", Collections.nCopies(20_000, "x")) + "\nEND_PROOF";
    AtomicInteger turns = new AtomicInteger();
    CoreAgentLoop loop = new CoreAgentLoop((history, prompt, tools, token) -> turns.getAndIncrement() == 0
        ? new ModelReply("", Collections.singletonList(new ModelReply.Call("large-call", "read", arguments("path", "large.txt"))))
        : new ModelReply("done", Collections.emptyList()), new CoreToolRegistry(Collections.singletonList(
            testTool("read", (args, token) -> CoreToolResult.success(output)))), "system", session);
    new MainChatTranscriptStore(files, session, store).attach(loop, Collections.emptyList());
    loop.run("Inspect data", Collections.emptyList(), CancellationToken.uncancellable(), null);
    LocalRunStore restartedStore = chatTestStore.localRunStore(files);
    List<ConversationTurn> restored = restartedStore.loadConversationContext(session);
    String evidence = restored.stream().filter(t -> t.kind == ConversationTurn.Kind.TOOL_RESULT).findFirst().get().content;
    assertTrue(evidence.length() < 12_000);
    assertFalse(evidence.contains(secret));
    java.util.regex.Matcher id = java.util.regex.Pattern.compile("artifact_id=\"([0-9a-f]{64})\"").matcher(evidence);
    assertTrue(evidence, id.find());
    String artifactId = id.group(1);
    JSONObject restoredArtifact = restartedStore.readModelTranscriptRows(session).stream()
        .filter(row -> "model_artifact".equals(row.optString("type")) && artifactId.equals(row.optString("artifactId")))
        .findFirst().orElseThrow(AssertionError::new);
    JSONObject metadata = restoredArtifact.getJSONObject("artifact");
    assertEquals(artifactId, restoredArtifact.getString("artifactId"));
    assertEquals(artifactId + ".txt", metadata.getString("path"));
    assertEquals(artifactId, metadata.getString("sha256"));
    assertTrue(metadata.getLong("chars") > 0L);
    assertTrue(metadata.getLong("bytes") > 0L);
    Map<String, Object> request = arguments("artifact_id", id.group(1));
    request.put("limit_chars", 30_000);
    CoreToolResult page = new MainChatTranscriptStore(files, session, restartedStore)
        .recoveryTool().execute(request, CancellationToken.uncancellable());
    assertTrue(page.content, page.success);
    assertFalse(page.content.contains(secret));
    assertFalse(new MainChatTranscriptStore(files, "another-chat", restartedStore)
        .recoveryTool().execute(request, CancellationToken.uncancellable()).success);
    assertTrue(restartedStore.readModelTranscriptRows("another-chat").stream()
        .noneMatch(row -> "model_artifact".equals(row.optString("type")) && artifactId.equals(row.optString("artifactId"))));
    try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(files.toPath())) {
      for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths.filter(java.nio.file.Files::isRegularFile)::iterator)
        assertFalse(new String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8).contains(secret));
    }
    store.deleteConversation(session);
    assertTrue(chatTestStore.localRunStore(files).loadConversationContext(session).isEmpty());
  }

  @Test public void regeneratingAnswerCannotErasePreviouslyCompletedSideEffects() throws Exception {
    File files = chatTestStore.newFilesDirectory();
    String session = "regenerated-effects";
    LocalRunStore store = chatTestStore.localRunStore(files);
    String user = store.appendConversationMessage(session, "user", "Write it");
    AtomicInteger turns = new AtomicInteger();
    CoreAgentLoop loop = new CoreAgentLoop((history, prompt, tools, token) -> turns.getAndIncrement() == 0
        ? new ModelReply("", Collections.singletonList(new ModelReply.Call("effect-call", "write", arguments("path", "index.html"))))
        : new ModelReply("old answer", Collections.emptyList()), new CoreToolRegistry(Collections.singletonList(
            testTool("write", (args, token) -> CoreToolResult.success("Wrote index.html")))), "system", session);
    new MainChatTranscriptStore(files, session, store).attach(loop, Collections.emptyList());
    CoreAgentLoop.Result result = loop.run("Write it", Collections.emptyList(), CancellationToken.uncancellable(), null);
    String answer = store.appendConversationMessage(session, "assistant", result.text, result.durationMs, result.runId, user, result.outcome);
    store.appendAssistantRegenerated(session, answer);
    List<ConversationTurn> restored = chatTestStore.localRunStore(files).loadConversationContext(session);
    assertFalse(restored.stream().anyMatch(t -> t.content.equals("old answer")));
    assertTrue(restored.stream().anyMatch(t -> t.kind == ConversationTurn.Kind.TOOL_RESULT && t.content.contains("Wrote index.html")));
  }

  @Test public void compactionCannotEraseObservedWriteEvenWhenModelSummaryOmitsIt() throws Exception {
    File files = chatTestStore.newFilesDirectory();
    String session = "compacted-effects";
    LocalRunStore store = chatTestStore.localRunStore(files);
    String user = store.appendConversationMessage(session, "user", "Write it");
    AtomicInteger turns = new AtomicInteger();
    CoreAgentLoop loop = new CoreAgentLoop((history, prompt, tools, token) -> turns.getAndIncrement() == 0
        ? new ModelReply("", Collections.singletonList(new ModelReply.Call("compacted-write", "write", arguments("path", "index.html"))))
        : new ModelReply("done", Collections.emptyList()), new CoreToolRegistry(Collections.singletonList(
            testTool("write", (args, token) -> CoreToolResult.success("Wrote index.html")))), "system", session);
    new MainChatTranscriptStore(files, session, store).attach(loop, Collections.emptyList());
    CoreAgentLoop.Result result = loop.run("Write it", Collections.emptyList(), CancellationToken.uncancellable(), null);
    store.appendConversationMessage(session, "assistant", result.text, result.durationMs, result.runId, user, result.outcome);
    store.appendConversationMessage(session, "user", "next");
    ModelProviderClient summarizer = new ModelProviderClient() {
      @Override public ModelReply complete(String system, String prompt, List<com.jarvys.agent.device.ScreenData> images,
          List<ToolSpec> tools, String session, CancellationToken token) {
        return new ModelReply("The user requested a website.", Collections.emptyList());
      }
      @Override public ModelReply completeConversation(String system, List<ConversationTurn> history, String prompt,
          List<com.jarvys.agent.device.ScreenData> images, List<ToolSpec> tools, String session, CancellationToken token) {
        return complete(system, prompt, images, tools, session, token);
      }
    };
    ConversationCompactor compactor = new ConversationCompactor(session, new CoreAgentModel(summarizer, session), store);
    ConversationCompactor.Outcome compacted = compactor.compact(store.loadConversationContext(session), 32_768,
        "manual", ConversationCompactionPolicy.Mode.ALL, CancellationToken.uncancellable(), null);
    assertTrue(compacted.summary.contains("Wrote index.html"));
    assertTrue(compacted.summary.contains("compacted-write"));
    List<ConversationTurn> reloaded = chatTestStore.localRunStore(files).loadConversationContext(session);
    assertTrue(reloaded.stream().anyMatch(t -> t.content.contains("Wrote index.html")));
    assertFalse(reloaded.stream().anyMatch(t -> t.kind == ConversationTurn.Kind.TOOL_CALLS));
    store.appendConversationMessage(session, "assistant", "next answer");
    compactor.compact(store.loadConversationContext(session), 32_768, "manual", ConversationCompactionPolicy.Mode.ALL,
        CancellationToken.uncancellable(), null);
    assertTrue(chatTestStore.localRunStore(files).loadConversationContext(session).stream()
        .anyMatch(t -> t.content.contains("Wrote index.html")));
  }

  @Test public void compactedToolTranscriptWithEmptyPromptSendsSummaryWithoutEmptyUserTurn() throws Exception {
    File files = chatTestStore.newFilesDirectory();
    String session = "compacted-tool-empty-prompt";
    LocalRunStore store = chatTestStore.localRunStore(files);
    store.appendConversationMessage(session, "user", "Inspect the project");
    List<ConversationTurn> toolTranscript = Arrays.asList(
        new ConversationTurn("user", "Inspect the project", 0),
        ConversationTurn.toolCalls("", Collections.singletonList(
            new ModelReply.Call("inspect-call", "inspect", Collections.emptyMap()))),
        ConversationTurn.toolResult("inspect-call", "inspect", "Project contents inspected"));
    ModelProviderClient summarizer = new ModelProviderClient() {
      @Override public ModelReply complete(String system, String prompt, List<com.jarvys.agent.device.ScreenData> images,
          List<ToolSpec> tools, String session, CancellationToken token) {
        return new ModelReply("The project contents were inspected.", Collections.emptyList());
      }
      @Override public ModelReply completeConversation(String system, List<ConversationTurn> history, String prompt,
          List<com.jarvys.agent.device.ScreenData> images, List<ToolSpec> tools, String session, CancellationToken token) {
        return complete(system, prompt, images, tools, session, token);
      }
    };
    ConversationCompactor compactor = new ConversationCompactor(session, new CoreAgentModel(summarizer, session), store);

    ConversationCompactor.Outcome compacted = compactor.compact(toolTranscript, 32_768, "test",
        ConversationCompactionPolicy.Mode.ALL, CancellationToken.uncancellable(), null);

    assertEquals(2, compacted.context.size());
    assertEquals(ConversationTurn.Kind.COMPACTION_SUMMARY, compacted.context.get(0).kind);
    assertEquals("Inspect the project", compacted.context.get(1).content);
    Method codex = OpenAICodexResponsesClient.class.getDeclaredMethod("userInput",
        String.class, List.class, List.class);
    codex.setAccessible(true);
    JSONArray codexInput = (JSONArray) codex.invoke(null, "", Collections.emptyList(), compacted.context);
    assertEquals(2, codexInput.length());
    assertTrue(codexInput.getJSONObject(0).getString("content").contains("project contents were inspected"));
    assertEquals("Inspect the project", codexInput.getJSONObject(1).getString("content"));

    Method chat = OpenRouterClient.class.getDeclaredMethod("conversationMessages",
        String.class, List.class, String.class, List.class);
    chat.setAccessible(true);
    JSONArray chatMessages = (JSONArray) chat.invoke(null, "system", compacted.context, "", Collections.emptyList());
    assertEquals(3, chatMessages.length());
    assertTrue(chatMessages.getJSONObject(1).getString("content").contains("project contents were inspected"));
    assertEquals("Inspect the project", chatMessages.getJSONObject(2).getString("content"));
  }

  @Test public void modelToolBodiesNeverBecomeReflectionInput() throws Exception {
    File files = chatTestStore.newFilesDirectory(); String session = "reflection-isolation";
    LocalRunStore store = chatTestStore.localRunStore(files);
    String user = store.appendConversationMessage(session, "user", "Inspect the file");
    AtomicInteger turns = new AtomicInteger();
    String privateText = "private-workspace-body-sentinel";
    CoreAgentLoop loop = new CoreAgentLoop((history, prompt, tools, token) -> turns.getAndIncrement() == 0
        ? new ModelReply("", Collections.singletonList(new ModelReply.Call("reflection-call", "read", arguments("path", "private.txt"))))
        : new ModelReply("done", Collections.emptyList()), new CoreToolRegistry(Collections.singletonList(
            testTool("read", (args, token) -> CoreToolResult.success(privateText)))), "system", session);
    new MainChatTranscriptStore(files, session, store).attach(loop, Collections.emptyList());
    CoreAgentLoop.Result result = loop.run("Inspect the file", Collections.emptyList(), CancellationToken.uncancellable(), null);
    store.appendReflectionToolEvent(session, user, "Read", "workspace", "tool_result", "reflection-call");
    store.appendConversationMessage(session, "assistant", result.text, result.durationMs, result.runId, user, result.outcome);
    assertTrue(store.loadConversationContext(session).stream().anyMatch(t -> t.content.equals(privateText)));
    assertFalse(store.buildReflectionPayload(session).text.contains(privateText));
  }

  @Test public void preCreatedRecoveryToolReadsFreshSameRunCompactionArtifact() throws Exception {
    File files = chatTestStore.newFilesDirectory(); String session = "same-run-artifact";
    LocalRunStore store = chatTestStore.localRunStore(files);
    store.appendConversationMessage(session, "user", "Inspect data");
    CoreTool originalReader = new MainChatTranscriptStore(files, session, store).recoveryTool();
    MainChatTranscriptStore compactorStore = new MainChatTranscriptStore(files, session, chatTestStore.localRunStore(files));
    String reference = compactorStore.archive("Fresh source after model context compaction");
    java.util.regex.Matcher id = java.util.regex.Pattern.compile("artifact_id=\"([0-9a-f]{64})\"").matcher(reference);
    assertTrue(reference, id.find());
    CoreToolResult recovered = originalReader.execute(arguments("artifact_id", id.group(1)), CancellationToken.uncancellable());
    assertTrue(recovered.content, recovered.success);
    assertTrue(recovered.content.contains("Fresh source after model context compaction"));
  }

  @Test public void persistenceFailureStopsBeforeAnyToolEffect() throws Exception {
    File files = chatTestStore.newFilesDirectory(); String session = "deleted-before-tool-checkpoint";
    LocalRunStore store = chatTestStore.localRunStore(files);
    store.appendConversationMessage(session, "user", "Write it");
    AtomicInteger effects = new AtomicInteger();
    CoreAgentLoop loop = new CoreAgentLoop((history, prompt, tools, token) -> new ModelReply("",
        Collections.singletonList(new ModelReply.Call("never-started", "write", arguments("path", "index.html")))),
        new CoreToolRegistry(Collections.singletonList(testTool("write", (args, token) -> {
          effects.incrementAndGet(); return CoreToolResult.success("Wrote index.html");
        }))), "system", session);
    new MainChatTranscriptStore(files, session, store).attach(loop, Collections.emptyList());
    assertTrue(store.deleteConversation(session));
    try { loop.run("Write it", Collections.emptyList(), CancellationToken.uncancellable(), null); fail(); }
    catch (CoreAgentLoop.CheckpointFailure expected) { }
    assertEquals(0, effects.get());
  }

  @Test public void preRequestPlanAllCompactionRetainsLaterToolEffectOnReload() throws Exception {
    File files = chatTestStore.newFilesDirectory(); String session = "pending-compaction";
    LocalRunStore store = chatTestStore.localRunStore(files);
    String earlierUser = store.appendConversationMessage(session, "user", "Earlier request");
    store.appendConversationMessage(session, "assistant", "Earlier answer", null, "", earlierUser);
    String user = store.appendConversationMessage(session, "user", "Write a page");
    ModelProviderClient summaryProvider = new ModelProviderClient() {
      @Override public ModelReply complete(String system, String prompt, List<com.jarvys.agent.device.ScreenData> images,
          List<ToolSpec> tools, String session, CancellationToken token) {
        return new ModelReply("User requested a page; no effect has completed yet.", Collections.emptyList());
      }
      @Override public ModelReply completeConversation(String system, List<ConversationTurn> history, String prompt,
          List<com.jarvys.agent.device.ScreenData> images, List<ToolSpec> tools, String session, CancellationToken token) {
        return complete(system, prompt, images, tools, session, token);
      }
    };
    ConversationCompactor compactor = new ConversationCompactor(session, new CoreAgentModel(summaryProvider, session), store);
    ConversationCompactor.Outcome compacted = compactor.compact(store.loadConversationContext(session), 32_768,
        "before_tool_request", ConversationCompactionPolicy.Mode.ALL, CancellationToken.uncancellable(), null);
    assertNotNull(compacted);
    assertTrue(compacted.context.stream().anyMatch(t -> t.kind == ConversationTurn.Kind.COMPACTION_SUMMARY));
    AtomicInteger turns = new AtomicInteger(), effects = new AtomicInteger();
    CoreAgentLoop.Model provider = new CoreAgentLoop.Model() {
      @Override public ModelReply complete(List<ConversationTurn> history, String prompt, List<ToolSpec> tools, CancellationToken token) {
        return turns.getAndIncrement() == 0
            ? new ModelReply("", Collections.singletonList(new ModelReply.Call("write-after-summary", "write", arguments("path", "index.html"))))
            : new ModelReply("done", Collections.emptyList());
      }
      @Override public int contextWindow(CancellationToken token) { return 32_768; }
    };
    CoreAgentLoop loop = new CoreAgentLoop(provider, new CoreToolRegistry(Collections.singletonList(testTool("write", (args, token) -> {
      effects.incrementAndGet(); return CoreToolResult.success("Wrote index.html after compaction");
    }))), "system", session, CorePromptBudget.standard(), compactor);
    new MainChatTranscriptStore(files, session, store).attach(loop, compacted.context);
    CoreAgentLoop.Result result = loop.run("", compacted.context, CancellationToken.uncancellable(), null);
    store.appendConversationMessage(session, "assistant", result.text, result.durationMs, result.runId, user, result.outcome);
    store.appendConversationMessage(session, "user", "Did it work?");
    List<ConversationTurn> restored = chatTestStore.localRunStore(files).loadConversationContext(session);
    assertEquals(1, effects.get());
    assertTrue(restored.stream().anyMatch(t -> t.kind == ConversationTurn.Kind.COMPACTION_SUMMARY));
    assertTrue(restored.stream().anyMatch(t -> t.kind == ConversationTurn.Kind.TOOL_RESULT
        && "write-after-summary".equals(t.toolCallId) && t.content.contains("Wrote index.html after compaction")));
    assertProviderRequests(restored, "write-after-summary", "Wrote index.html after compaction");
  }

  @Test public void isolatedCompactorKeepsFactsWithoutAdvertisingUnavailableRecoveryOrSavingArtifacts() throws Exception {
    File files = chatTestStore.newFilesDirectory(); String session = "isolated-worker";
    LocalRunStore store = chatTestStore.localRunStore(files);
    store.appendConversationMessage(session, "user", "Write one scoped file");
    ModelProviderClient provider = new ModelProviderClient() {
      @Override public ModelReply complete(String system, String prompt, List<com.jarvys.agent.device.ScreenData> images,
          List<ToolSpec> tools, String session, CancellationToken token) {
        return new ModelReply("The scoped task finished.", Collections.emptyList());
      }
      @Override public ModelReply completeConversation(String system, List<ConversationTurn> history, String prompt,
          List<com.jarvys.agent.device.ScreenData> images, List<ToolSpec> tools, String session, CancellationToken token) {
        return complete(system, prompt, images, tools, session, token);
      }
    };
    List<ConversationTurn> transcript = Arrays.asList(new ConversationTurn("user", "Write one scoped file", 0),
        ConversationTurn.toolCalls("", Collections.singletonList(new ModelReply.Call("worker-write", "write", arguments("path", "index.html")))),
        ConversationTurn.toolResult("worker-write", "write", "Wrote index.html"));
    ConversationCompactor compactor = new ConversationCompactor(session, new CoreAgentModel(provider, session), store, false);
    ConversationCompactor.Outcome outcome = compactor.compact(transcript, 32_768, "manual",
        ConversationCompactionPolicy.Mode.ALL, CancellationToken.uncancellable(), null);
    assertTrue(outcome.summary.contains("Wrote index.html"));
    assertFalse(outcome.summary.contains(MainChatTranscriptStore.READ_TOOL));
    assertTrue(store.readModelTranscriptRows(session).isEmpty());
    assertFalse(new File(files, "jarvys/crew-context").exists());
  }

  interface Execution { CoreToolResult run(Map<String, Object> args, CancellationToken token); }
  static CoreTool testTool(String name, Execution execute) {
    return testTool(name, execute, CoreTool.ExecutionMode.PARALLEL);
  }
  static CoreTool testTool(String name, Execution execute, CoreTool.ExecutionMode executionMode) {
    return new CoreTool() {
      @Override public ToolSpec declaration() { return new ToolSpec(name, "test", "test", "test",
          ToolSpec.Status.IMPLEMENTED, Collections.emptyMap(), Collections.emptyList()); }
      @Override public ExecutionMode executionMode() { return executionMode; }
      @Override public CoreToolResult execute(Map<String, Object> args, CancellationToken token) {
        return execute.run(args, token);
      }
    };
  }

  static Map<String, Object> arguments(String... pairs) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i], pairs[i + 1]);
    return values;
  }

  static void assertProviderRequests(List<ConversationTurn> context, String callId, String result) throws Exception {
    Method chat = OpenRouterClient.class.getDeclaredMethod("conversationMessages",
        String.class, List.class, String.class, List.class);
    chat.setAccessible(true);
    JSONArray messages = (JSONArray) chat.invoke(null, "system", context, "", Collections.emptyList());
    Method codex = OpenAICodexResponsesClient.class.getDeclaredMethod("userInput", String.class, List.class, List.class);
    codex.setAccessible(true);
    JSONArray input = (JSONArray) codex.invoke(null, "", Collections.emptyList(), context);
    boolean chatCall = false, chatResult = false, codexCall = false, codexResult = false;
    for (int i = 0; i < messages.length(); i++) {
      JSONObject row = messages.getJSONObject(i);
      JSONArray calls = row.optJSONArray("tool_calls");
      if (calls != null) for (int j = 0; j < calls.length(); j++)
        chatCall |= callId.equals(calls.getJSONObject(j).optString("id"));
      if (callId.equals(row.optString("tool_call_id"))) {
        chatResult = true;
        assertEquals("tool", row.getString("role"));
        assertTrue(row.getString("content").contains(result));
      }
    }
    for (int i = 0; i < input.length(); i++) {
      JSONObject row = input.getJSONObject(i);
      if (!callId.equals(row.optString("call_id"))) continue;
      if ("function_call".equals(row.optString("type"))) codexCall = true;
      if ("function_call_output".equals(row.optString("type"))) {
        codexResult = true;
        assertTrue(row.getString("output").contains(result));
      }
    }
    assertTrue(chatCall && chatResult && codexCall && codexResult);
  }
}
