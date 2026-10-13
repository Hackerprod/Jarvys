package com.jarvys.agent;

import static org.junit.Assert.*;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Provider-private Responses items survive the Codex transcript boundary without UI projection. */
public class CodexReasoningPersistenceTest {
    private static final String ENCRYPTED = "encrypted-reasoning-sentinel";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void codexReasoningRoundTripsAndReplaysBeforeItsMessageAndCall() throws Exception {
        File files = temporary.newFolder();
        String session = "codex-reasoning-tool-turn";
        LocalRunStore store = new LocalRunStore(files);
        store.appendConversationMessage(session, "user", "Look up the current value");
        List<ConversationTurn> prior = store.loadConversationContext(session);
        ModelReply parsed = OpenAICodexResponsesClient.parseResponse(
                "{\"response\":{\"status\":\"completed\",\"output\":["
                        + "{\"type\":\"reasoning\",\"id\":\"rs_17\",\"encrypted_content\":\"" + ENCRYPTED
                        + "\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"private summary\"}]},"
                        + "{\"type\":\"message\",\"id\":\"msg_18\",\"content\":[{\"type\":\"output_text\",\"text\":\"Looking up\"}]},"
                        + "{\"type\":\"function_call\",\"id\":\"fc_19\",\"call_id\":\"lookup-19\","
                        + "\"name\":\"lookup\",\"arguments\":\"{}\"}]}}");
        assertEquals(3, parsed.providerOutputItems.size());
        assertEquals(ModelReply.ProviderOutputItem.Kind.REASONING, parsed.providerOutputItems.get(0).kind);
        assertEquals(ModelReply.ProviderOutputItem.Kind.MESSAGE, parsed.providerOutputItems.get(1).kind);
        assertEquals(ModelReply.ProviderOutputItem.Kind.TOOL_CALL, parsed.providerOutputItems.get(2).kind);
        assertFalse(parsed.text.contains(ENCRYPTED));

        AtomicInteger toolEffects = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        CoreAgentLoop.Model model = (transcript, prompt, declarations, token) ->
                requests.getAndIncrement() == 0 ? parsed : new ModelReply("Lookup complete", Collections.emptyList());
        CoreAgentLoop loop = new CoreAgentLoop(model, new CoreToolRegistry(Collections.singletonList(lookupTool(toolEffects))),
                "", session);
        new MainChatTranscriptStore(files, session, store).attach(loop, prior);

        CoreAgentLoop.Result result = loop.run("", prior, CancellationToken.uncancellable(), null);

        assertEquals("COMPLETED", result.outcome);
        assertEquals(1, toolEffects.get());
        List<ConversationTurn> restored = new LocalRunStore(files).loadConversationContext(session);
        ConversationTurn callTurn = restored.stream()
                .filter(turn -> turn.kind == ConversationTurn.Kind.TOOL_CALLS).findFirst().orElseThrow(AssertionError::new);
        assertEquals(3, callTurn.providerOutputItems.size());
        assertFalse("Opaque encrypted material is not assistant-visible text", callTurn.content.contains(ENCRYPTED));
        assertFalse("Encrypted reasoning is not part of the UI timeline",
                store.readConversationTimeline(session).stream().anyMatch(event -> event.text.contains(ENCRYPTED)));

        JSONArray input = OpenAICodexResponsesClient.userInput("", Collections.emptyList(), restored);
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

    @Test public void assistantMessageReasoningIsStoredStructurallyAndLegacyTranscriptStillLoads() throws Exception {
        File files = temporary.newFolder();
        String session = "codex-reasoning-message-turn";
        LocalRunStore store = new LocalRunStore(files);
        String userMessageId = store.appendConversationMessage(session, "user", "Answer this");
        List<ConversationTurn> prior = store.loadConversationContext(session);
        String raw = new JSONObject().put("type", "reasoning").put("id", "rs_message")
                .put("encrypted_content", ENCRYPTED).put("summary", new JSONArray()).toString();
        List<ModelReply.ProviderOutputItem> items = Collections.singletonList(
                ModelReply.ProviderOutputItem.reasoning(raw));
        CoreAgentLoop loop = new CoreAgentLoop((transcript, prompt, declarations, token) ->
                new ModelReply("The answer", Collections.emptyList(), "", null, "fake", null,
                        ResponseDiagnostics.unknown(), items), new CoreToolRegistry(Collections.emptyList()),
                "", session);
        new MainChatTranscriptStore(files, session, store).attach(loop, prior);

        CoreAgentLoop.Result result = loop.run("", prior, CancellationToken.uncancellable(), null);
        store.appendConversationMessage(session, "assistant", result.text, result.durationMs,
                result.runId, userMessageId, result.outcome);

        List<ConversationTurn> restored = new LocalRunStore(files).loadConversationContext(session);
        ConversationTurn assistant = restored.stream().filter(turn -> "assistant".equals(turn.role)
                && "The answer".equals(turn.content)).findFirst().orElseThrow(AssertionError::new);
        assertEquals(1, assistant.providerOutputItems.size());
        assertEquals(raw, assistant.providerOutputItems.get(0).value);
        assertFalse(assistant.content.contains(ENCRYPTED));
        assertFalse(store.readConversationTimeline(session).stream().anyMatch(event -> event.text.contains(ENCRYPTED)));

        String legacy = "old-codex-transcript";
        LocalRunStore legacyStore = new LocalRunStore(files);
        legacyStore.appendConversationMessage(legacy, "user", "Old request");
        legacyStore.appendConversationMessage(legacy, "assistant", "Old answer");
        List<ConversationTurn> oldContext = legacyStore.loadConversationContext(legacy);
        assertTrue(oldContext.stream().allMatch(turn -> turn.providerOutputItems.isEmpty()));
        JSONArray oldInput = OpenAICodexResponsesClient.userInput("", Collections.emptyList(), oldContext);
        assertEquals("Old request", oldInput.getJSONObject(0).optString("content"));
        assertEquals("Old answer", oldInput.getJSONObject(1).optString("content"));
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

    @Test public void compactionDropsReasoningForSummarizedAssistantTurns() throws Exception {
        File files = temporary.newFolder();
        String session = "codex-reasoning-compacted";
        LocalRunStore store = new LocalRunStore(files);
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
        List<ConversationTurn> restored = new LocalRunStore(files).loadConversationContext(session);
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
