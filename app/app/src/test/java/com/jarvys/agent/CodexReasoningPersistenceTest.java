package com.jarvys.agent;

import static org.junit.Assert.*;

import com.jarvys.agent.crew.CrewResultEvidence;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Provider-private Responses items survive the Codex transcript boundary without UI projection. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CodexReasoningPersistenceTest {
    @org.junit.Rule public final ChatTestStore chatTestStore = new ChatTestStore();
    private static final String ENCRYPTED = "encrypted-reasoning-sentinel";
    private static final String CODEX_MODEL = "gpt-codex-test";

    @Test public void codexReasoningRoundTripsAndReplaysBeforeItsMessageAndCall() throws Exception {
        File files = chatTestStore.newFilesDirectory();
        String session = "codex-reasoning-tool-turn";
        LocalRunStore store = chatTestStore.localRunStore(files);
        store.appendConversationMessage(session, "user", "Look up the current value");
        List<ConversationTurn> prior = store.loadConversationContext(session);
        ModelReply parsed = OpenAICodexResponsesClient.parseResponse(
                "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":["
                        + "{\"type\":\"reasoning\",\"id\":\"rs_17\",\"encrypted_content\":\"" + ENCRYPTED
                        + "\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"private summary\"}]},"
                        + "{\"type\":\"message\",\"id\":\"msg_18\",\"content\":[{\"type\":\"output_text\",\"text\":\"Looking up\"}]},"
                        + "{\"type\":\"function_call\",\"id\":\"fc_19\",\"call_id\":\"lookup-19\","
                        + "\"name\":\"lookup\",\"arguments\":\"{}\",\"status\":\"completed\"}]}}");
        assertEquals(3, parsed.providerOutputItems.size());
        assertEquals(ModelReply.ProviderOutputItem.Kind.REASONING, parsed.providerOutputItems.get(0).kind);
        assertEquals(ModelReply.ProviderOutputItem.Kind.MESSAGE, parsed.providerOutputItems.get(1).kind);
        assertEquals(ModelReply.ProviderOutputItem.Kind.TOOL_CALL, parsed.providerOutputItems.get(2).kind);
        assertFalse(parsed.text.contains(ENCRYPTED));
        List<ModelReply.ProviderOutputItem> identifiedItems = new java.util.ArrayList<>();
        for (ModelReply.ProviderOutputItem item : parsed.providerOutputItems)
            identifiedItems.add(item.withIdentity(OpenAICodexResponsesClient.OUTPUT_PROVIDER,
                    OpenAICodexResponsesClient.OUTPUT_API, CODEX_MODEL));
        ModelReply identifiedReply = new ModelReply(parsed.text, parsed.calls, "", null, CODEX_MODEL,
                parsed.contextTokensUsed, parsed.diagnostics, identifiedItems);

        AtomicInteger toolEffects = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        CoreAgentLoop.Model model = (transcript, prompt, declarations, token) ->
                requests.getAndIncrement() == 0 ? identifiedReply : new ModelReply("Lookup complete", Collections.emptyList());
        CoreAgentLoop loop = new CoreAgentLoop(model, new CoreToolRegistry(Collections.singletonList(lookupTool(toolEffects))),
                "", session);
        new MainChatTranscriptStore(files, session, store).attach(loop, prior);

        CoreAgentLoop.Result result = loop.run("", prior, CancellationToken.uncancellable(), null);

        assertEquals("COMPLETED", result.outcome);
        assertEquals(1, toolEffects.get());
        List<ConversationTurn> restored = chatTestStore.localRunStore(files).loadConversationContext(session);
        ConversationTurn callTurn = restored.stream()
                .filter(turn -> turn.kind == ConversationTurn.Kind.TOOL_CALLS).findFirst().orElseThrow(AssertionError::new);
        assertEquals(3, callTurn.providerOutputItems.size());
        assertEquals(OpenAICodexResponsesClient.OUTPUT_PROVIDER, callTurn.providerOutputItems.get(0).provider);
        assertEquals(OpenAICodexResponsesClient.OUTPUT_API, callTurn.providerOutputItems.get(0).api);
        assertEquals(CODEX_MODEL, callTurn.providerOutputItems.get(0).model);
        assertFalse("Opaque encrypted material is not assistant-visible text", callTurn.content.contains(ENCRYPTED));
        assertFalse("Encrypted reasoning is not part of the UI timeline",
                store.readConversationTimeline(session).stream().anyMatch(event -> event.getText().contains(ENCRYPTED)));

        JSONArray input = OpenAICodexResponsesClient.userInput("", Collections.emptyList(), restored, CODEX_MODEL);
        JSONObject reasoning = input.getJSONObject(1);
        JSONObject assistantMessage = input.getJSONObject(2);
        JSONObject functionCall = input.getJSONObject(3);
        JSONObject functionOutput = input.getJSONObject(4);
        assertEquals("reasoning", reasoning.optString("type"));
        assertEquals("rs_17", reasoning.optString("id"));
        assertEquals(ENCRYPTED, reasoning.optString("encrypted_content"));
        assertEquals("assistant", assistantMessage.optString("role"));
        assertEquals("Looking up", assistantMessage.optString("content"));
        assertEquals("function_call", functionCall.optString("type"));
        assertEquals("lookup-19", functionCall.optString("call_id"));
        assertEquals("function_call_output", functionOutput.optString("type"));
        assertEquals("lookup-19", functionOutput.optString("call_id"));
    }

    @Test public void assistantMessageReasoningIsStoredStructurally() throws Exception {
        File files = chatTestStore.newFilesDirectory();
        String session = "codex-reasoning-message-turn";
        LocalRunStore store = chatTestStore.localRunStore(files);
        String userMessageId = store.appendConversationMessage(session, "user", "Answer this");
        List<ConversationTurn> prior = store.loadConversationContext(session);
        String raw = new JSONObject().put("type", "reasoning").put("id", "rs_message")
                .put("encrypted_content", ENCRYPTED).put("summary", new JSONArray()).toString();
        List<ModelReply.ProviderOutputItem> items = Collections.singletonList(
                ModelReply.ProviderOutputItem.reasoning(raw, OpenAICodexResponsesClient.OUTPUT_PROVIDER,
                        OpenAICodexResponsesClient.OUTPUT_API, CODEX_MODEL));
        CoreAgentLoop loop = new CoreAgentLoop((transcript, prompt, declarations, token) ->
                new ModelReply("The answer", Collections.emptyList(), "", null, "fake", null,
                        ResponseDiagnostics.unknown(), items), new CoreToolRegistry(Collections.emptyList()),
                "", session);
        new MainChatTranscriptStore(files, session, store).attach(loop, prior);

        CoreAgentLoop.Result result = loop.run("", prior, CancellationToken.uncancellable(), null);
        store.appendConversationMessage(session, "assistant", result.text, result.durationMs,
                result.runId, userMessageId, result.outcome);

        List<ConversationTurn> restored = chatTestStore.localRunStore(files).loadConversationContext(session);
        ConversationTurn assistant = restored.stream().filter(turn -> "assistant".equals(turn.role)
                && "The answer".equals(turn.content)).findFirst().orElseThrow(AssertionError::new);
        assertEquals(1, assistant.providerOutputItems.size());
        assertEquals(raw, assistant.providerOutputItems.get(0).value);
        assertFalse(assistant.content.contains(ENCRYPTED));
        assertFalse(store.readConversationTimeline(session).stream().anyMatch(event -> event.getText().contains(ENCRYPTED)));

    }

    @Test public void agentInputHasIndependentProviderAndToolOwnershipAndCannotRegeneratePriorPrompt() throws Exception {
        File files = chatTestStore.newFilesDirectory();
        String session = com.jarvys.agent.workspaces.WorkspaceIdentity.CENTRAL_SESSION;
        LocalRunStore store = chatTestStore.localRunStore(files);
        String userMessageId = store.appendConversationMessage(session, "user", "Earlier request");
        List<ConversationTurn> prior = store.loadConversationContext(session);
        String priorRaw = new JSONObject().put("type", "reasoning").put("id", "rs_prior")
                .put("encrypted_content", "prior-response-state").toString();
        ModelReply.ProviderOutputItem priorOutput = ModelReply.ProviderOutputItem.reasoning(priorRaw,
                OpenAICodexResponsesClient.OUTPUT_PROVIDER, OpenAICodexResponsesClient.OUTPUT_API, CODEX_MODEL);
        CoreAgentLoop priorLoop = new CoreAgentLoop((transcript, prompt, declarations, token) ->
                new ModelReply("Earlier answer", Collections.emptyList(), "", null, CODEX_MODEL, null,
                        ResponseDiagnostics.unknown(), Collections.singletonList(priorOutput)),
                new CoreToolRegistry(Collections.emptyList()), "", session);
        new MainChatTranscriptStore(files, session, store).attach(priorLoop, prior);
        CoreAgentLoop.Result priorResult = priorLoop.run("", prior, CancellationToken.uncancellable(), null);
        String priorAssistantId = store.appendConversationMessage(session, "assistant", priorResult.text,
                priorResult.durationMs, priorResult.runId, userMessageId, priorResult.outcome);

        String agentMessageId = store.appendConversationInput(new ConversationInput(session, "source-result-1",
                "Worker final text", ConversationInput.Origin.AGENT,
                new ConversationInput.Provenance("workspace-execution-1", "workspace-1", "assignment-1",
                        "bot-1", "source-result-1", ConversationInput.Kind.RESULT)));
        List<ConversationTurn> agentHistory = store.loadConversationContext(session);
        ConversationTurn internal = agentHistory.stream()
                .filter(turn -> agentMessageId.equals(turn.durableMessageId)).findFirst().orElseThrow(AssertionError::new);
        assertEquals(ConversationInput.Origin.AGENT, internal.incomingMetadata.origin);
        assertEquals("Worker final text", internal.content);
        assertEquals("AGENT input uses an explicit untrusted assistant-role envelope", "AGENT",
                internal.incomingMetadata.origin.name());

        String toolRaw = new JSONObject().put("type", "reasoning").put("id", "rs_agent_tool")
                .put("encrypted_content", "agent-tool-state").toString();
        String answerRaw = new JSONObject().put("type", "reasoning").put("id", "rs_agent_answer")
                .put("encrypted_content", "agent-answer-state").toString();
        ModelReply.ProviderOutputItem toolOutput = ModelReply.ProviderOutputItem.reasoning(toolRaw,
                OpenAICodexResponsesClient.OUTPUT_PROVIDER, OpenAICodexResponsesClient.OUTPUT_API, CODEX_MODEL);
        ModelReply.ProviderOutputItem answerOutput = ModelReply.ProviderOutputItem.reasoning(answerRaw,
                OpenAICodexResponsesClient.OUTPUT_PROVIDER, OpenAICodexResponsesClient.OUTPUT_API, CODEX_MODEL);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger toolEffects = new AtomicInteger();
        CoreAgentLoop loop = new CoreAgentLoop((transcript, prompt, declarations, token) -> {
            if (calls.getAndIncrement() == 0) return new ModelReply("Inspecting worker evidence",
                    Collections.singletonList(new ModelReply.Call("agent-inspect-call", "lookup", Collections.emptyMap())),
                    "", null, CODEX_MODEL, null, ResponseDiagnostics.unknown(), Collections.singletonList(toolOutput));
            assertTrue(transcript.stream().anyMatch(turn -> agentMessageId.equals(turn.durableMessageId)));
            return new ModelReply("I checked the worker report", Collections.emptyList(), "", null, CODEX_MODEL, null,
                    ResponseDiagnostics.unknown(), Collections.singletonList(answerOutput));
        }, new CoreToolRegistry(Collections.singletonList(lookupTool(toolEffects))), "", session);
        new MainChatTranscriptStore(files, session, store).attach(loop, agentHistory, agentMessageId);
        CoreAgentLoop.Result result = loop.run("", agentHistory, CancellationToken.uncancellable(), null);
        String assistantId = store.appendConversationMessage(session, "assistant", result.text,
                result.durationMs, result.runId, agentMessageId, result.outcome);
        assertEquals("COMPLETED", result.outcome);
        assertEquals(1, toolEffects.get());
        assertEquals(2, calls.get());

        LocalRunStore reopened = chatTestStore.localRunStore(files);
        List<ConversationTurn> restored = reopened.loadConversationContext(session);
        ConversationTurn priorAnswer = restored.stream().filter(turn -> "Earlier answer".equals(turn.content))
                .findFirst().orElseThrow(AssertionError::new);
        ConversationTurn answer = restored.stream().filter(turn -> "I checked the worker report".equals(turn.content))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(priorRaw, priorAnswer.providerOutputItems.get(0).value);
        assertEquals(answerRaw, answer.providerOutputItems.get(0).value);

        List<AgentRunUiEvent> timeline = reopened.readConversationTimeline(session);
        assertFalse("Internal agent input is absent from the chat projection",
                timeline.stream().anyMatch(event -> event.getText().contains("Worker final text")));
        AgentRunUiEvent tool = timeline.stream().filter(event -> "agent-inspect-call".equals(event.getToolCallId()))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(agentMessageId, tool.getToolTurnMessageId());
        AgentRunUiEvent answerEvent = timeline.stream().filter(event -> assistantId.equals(event.getMessageId()))
                .findFirst().orElseThrow(AssertionError::new);
        assertFalse("An answer to an agent input cannot regenerate the preceding user request", answerEvent.getCanRegenerate());

        reopened.hideAssistantWithOwner(session, priorAssistantId, "assistant_regenerated");
        timeline = reopened.readConversationTimeline(session);
        assertTrue(timeline.stream().anyMatch(event -> "agent-inspect-call".equals(event.getToolCallId())
                && agentMessageId.equals(event.getToolTurnMessageId())));
        int callsBefore = calls.get();
        try {
            reopened.regenerateAssistantIfLatest(session, assistantId);
            fail("Agent-origin answer must not fall back to the preceding user's prompt");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("regenerat"));
        }
        assertEquals(callsBefore, calls.get());
        assertEquals(1, reopened.readConversationMessages(session).stream()
                .filter(row -> "user".equals(row.optString("role"))).count());
    }
    @Test public void chatCompletionsAdapterIgnoresCodexReasoningItems() throws Exception {
        String raw = new JSONObject().put("type", "reasoning").put("id", "rs_private")
                .put("encrypted_content", ENCRYPTED).toString();
        ConversationTurn assistant = ConversationTurn.assistantReply("Ordinary assistant text",
                Collections.singletonList(ModelReply.ProviderOutputItem.reasoning(raw)));

        JSONArray messages = OpenRouterClient.conversationMessages("system", Collections.singletonList(assistant),
                "", Collections.emptyList());

        String serialized = messages.toString();
        assertTrue(serialized.contains("Ordinary assistant text"));
        assertFalse("Chat Completions must not receive Responses-only encrypted reasoning", serialized.contains(ENCRYPTED));
        assertEquals(2, messages.length());
    }

    @Test public void codexReplaysOnlyReasoningFromTheCurrentProviderApiAndModel() throws Exception {
        String matching = new JSONObject().put("type", "reasoning").put("id", "rs_current")
                .put("encrypted_content", "current-model-secret").toString();
        String otherModel = new JSONObject().put("type", "reasoning").put("id", "rs_other")
                .put("encrypted_content", "other-model-secret").toString();
        String legacy = new JSONObject().put("type", "reasoning").put("id", "rs_legacy")
                .put("encrypted_content", "legacy-secret").toString();
        List<ModelReply.ProviderOutputItem> items = new java.util.ArrayList<>();
        items.add(ModelReply.ProviderOutputItem.reasoning(matching, OpenAICodexResponsesClient.OUTPUT_PROVIDER,
                OpenAICodexResponsesClient.OUTPUT_API, CODEX_MODEL));
        items.add(ModelReply.ProviderOutputItem.reasoning(otherModel, OpenAICodexResponsesClient.OUTPUT_PROVIDER,
                OpenAICodexResponsesClient.OUTPUT_API, "different-model"));
        items.add(ModelReply.ProviderOutputItem.reasoning(legacy));
        ConversationTurn turn = ConversationTurn.assistantReply("Visible answer", items);

        JSONArray input = OpenAICodexResponsesClient.userInput("", Collections.emptyList(),
                Collections.singletonList(turn), CODEX_MODEL);

        assertEquals(2, input.length());
        assertEquals("current-model-secret", input.getJSONObject(0).optString("encrypted_content"));
        assertEquals("assistant", input.getJSONObject(1).optString("role"));
        assertEquals("Visible answer", input.getJSONObject(1).optString("content"));
        assertFalse(input.toString().contains("other-model-secret"));
        assertFalse(input.toString().contains("legacy-secret"));
    }

    @Test public void systemStatusAssistantRowsAndModelErrorsStayVisibleButLeaveModelHistory() throws Exception {
        File files = chatTestStore.newFilesDirectory();
        String session = "system-status-history";
        LocalRunStore store = chatTestStore.localRunStore(files);
        store.appendConversationMessage(session, "user", "Continue");
        store.appendConversationMessage(session, "assistant", "localized stop text", null, "", "", "STOPPED");
        store.appendConversationMessage(session, "assistant", "localized timeout text", null, "", "", "PARTIAL");
        store.appendConversationMessage(session, "assistant", "localized provider failure", null, "", "", "FAILED");
        store.appendConversationMessage(session, "assistant", "Task stopped", null, "", "", "COMPLETED");
        store.appendModelTranscriptRow(session, new JSONObject().put("type", "model_error")
                .put("errorCode", "provider_unavailable").put("messageIndex", 0));

        List<ConversationTurn> history = chatTestStore.localRunStore(files).loadConversationContext(session);
        List<String> contents = new java.util.ArrayList<>();
        for (ConversationTurn turn : history) {
            if (turn.kind == ConversationTurn.Kind.MESSAGE) contents.add(turn.content);
        }
        assertEquals(java.util.Arrays.asList("Continue", "Task stopped"), contents);
        assertFalse(history.stream().anyMatch(turn -> turn.kind == ConversationTurn.Kind.MODEL_ERROR));
        assertTrue("Status messages remain in the rendered conversation",
                store.readConversationTimeline(session).stream().anyMatch(event -> "localized stop text".equals(event.getText())));

        JSONArray providerMessages = OpenRouterClient.conversationMessages("system", history, "", Collections.emptyList());
        assertFalse(providerMessages.toString().contains("localized stop text"));
        assertFalse(providerMessages.toString().contains("localized timeout text"));
        assertFalse(providerMessages.toString().contains("localized provider failure"));
        assertTrue("Filtering is structural, not based on the rendered text", providerMessages.toString().contains("Task stopped"));
    }

    @Test public void compactionDropsReasoningForSummarizedAssistantTurns() throws Exception {
        File files = chatTestStore.newFilesDirectory();
        String session = "codex-reasoning-compacted";
        LocalRunStore store = chatTestStore.localRunStore(files);
        String oldUserId = store.appendConversationMessage(session, "user", "Earlier question");
        store.appendConversationMessage(session, "assistant", "Earlier answer", null, "", oldUserId);
        String raw = new JSONObject().put("type", "reasoning").put("id", "rs_old")
                .put("encrypted_content", ENCRYPTED).toString();
        store.appendModelTranscriptRow(session, new JSONObject().put("type", "model_provider_output_items")
                .put("userMessageId", oldUserId).put("messageIndex", 0)
                .put("items", ProviderOutputItemsCodec.encode(Collections.singletonList(
                        ModelReply.ProviderOutputItem.reasoning(raw)))));
        store.appendConversationMessage(session, "user", "Current question");
        List<ConversationTurn> before = store.loadConversationContext(session);
        assertTrue(before.stream().anyMatch(turn -> turn.providerOutputItems.size() == 1));

        ModelProviderClient summarizer = new ModelProviderClient() {
            @Override public ModelReply complete(String systemPrompt, String userPrompt, List<com.jarvys.agent.device.ScreenData> images,
                    List<ToolSpec> tools, String sessionId, CancellationToken token) {
                return new ModelReply("A concise summary", Collections.emptyList());
            }
            @Override public ModelReply completeConversation(String systemPrompt, List<ConversationTurn> history,
                    String userPrompt, List<com.jarvys.agent.device.ScreenData> images, List<ToolSpec> tools,
                    String sessionId, CancellationToken token) {
                return new ModelReply("A concise summary", Collections.emptyList());
            }
        };
        ConversationCompactor compactor = new ConversationCompactor(session, new CoreAgentModel(summarizer, session), store);

        ConversationCompactor.Outcome outcome = compactor.compact(before, 32_768, "manual",
                ConversationCompactionPolicy.Mode.ALL, CancellationToken.uncancellable(), null);

        assertNotNull(outcome);
        assertTrue(outcome.context.stream().anyMatch(turn -> turn.kind == ConversationTurn.Kind.COMPACTION_SUMMARY));
        assertTrue(outcome.context.stream().anyMatch(turn -> "Current question".equals(turn.content)));
        assertFalse(outcome.context.stream().anyMatch(turn -> !turn.providerOutputItems.isEmpty()));
        List<ConversationTurn> restored = chatTestStore.localRunStore(files).loadConversationContext(session);
        assertTrue(restored.stream().anyMatch(turn -> "Current question".equals(turn.content)));
        assertFalse(restored.stream().anyMatch(turn -> turn.providerOutputItems.stream()
                .anyMatch(item -> item.value.contains(ENCRYPTED))));
    }

    private static CoreTool lookupTool(AtomicInteger effects) {
        return new CoreTool() {
            @Override public ToolSpec declaration() {
                return new ToolSpec("lookup", "test", "lookup value", "test", ToolSpec.Status.IMPLEMENTED,
                        Collections.emptyMap(), Collections.emptyList());
            }
            @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
                effects.incrementAndGet();
                return CoreToolResult.success("value found");
            }
        };
    }

}
