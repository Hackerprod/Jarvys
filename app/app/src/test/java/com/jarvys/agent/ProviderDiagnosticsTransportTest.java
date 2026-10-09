package com.jarvys.agent;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.jarvys.agent.providers.ChatCompletionsConfig;
import com.jarvys.agent.providers.CustomEndpointUrlValidator;
import com.jarvys.agent.providers.ProviderClientRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Synthetic connections exercise the actual writers/readers without DNS, sockets or user accounts. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ProviderDiagnosticsTransportTest {
    private Context context;
    private SecretStore secrets;
    private ProviderSettings settings;
    private static final String SESSION = "ux34-synthetic-conversation";
    private static final String FOOTER_LITERAL = "Synthetic answer.\n\n<memoria>\nPURGAR m23\n</memoria>";

    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("jarvys_provider_settings", Context.MODE_PRIVATE).edit().clear().commit();
        secrets = new SecretStore(context.getSharedPreferences("ux34-fake-credentials", Context.MODE_PRIVATE));
        secrets.saveOpenRouterKey("SYNTHETIC_ROUTER_TOKEN");
        secrets.saveOpenAiApiKey("SYNTHETIC_API_TOKEN");
        secrets.saveCustomEndpointKey("SYNTHETIC_CUSTOM_TOKEN");
        secrets.saveCodexTokens("SYNTHETIC_CODEX_TOKEN", "SYNTHETIC_REFRESH_TOKEN",
                Long.MAX_VALUE, "SYNTHETIC_ACCOUNT");
        settings = new ProviderSettings(context);
        settings.setProvider(ProviderSettings.Provider.OPENROUTER);
        settings.setOpenRouterModel("fixture-router-model");
    }

    @Test public void threeChatConfigurationsUseActualWireAndKeepLegacyFields() throws Exception {
        for (ProviderSettings.Provider provider : Arrays.asList(ProviderSettings.Provider.OPENAI_API,
                ProviderSettings.Provider.OPENROUTER, ProviderSettings.Provider.CUSTOM)) {
            settings.setProvider(provider);
            settings.setModel("fixture-model");
            ChatCompletionsConfig config;
            if (provider == ProviderSettings.Provider.OPENAI_API) config = ChatCompletionsConfig.openAiApiDefault();
            else if (provider == ProviderSettings.Provider.OPENROUTER) config = ChatCompletionsConfig.openRouterDefault();
            else {
                InetAddress syntheticPublicAddress = InetAddress.getByAddress(new byte[] {8, 8, 8, 8});
                CustomEndpointUrlValidator validator = new CustomEndpointUrlValidator(
                        host -> Collections.singletonList(syntheticPublicAddress));
                config = ChatCompletionsConfig.customOpenAiCompatible("https://fixture.example/v1/chat/completions",
                        SecretStore::getCustomEndpointKey, model -> 64, validator::validateChatCompletionsUrl);
                settings.saveCustomEndpoint("synthetic", "https://fixture.example/v1", "OPENAI_CHAT_COMPLETIONS", "fixture-model");
                assertTrue(ProviderClientRegistry.createClient(provider, secrets, settings) instanceof OpenRouterClient);
            }
            FakeConnection connection = new FakeConnection(200, chatText(FOOTER_LITERAL, "stop"));
            FakeFactory factory = new FakeFactory(connection);
            OpenRouterClient client = new OpenRouterClient(secrets, settings, config, factory);
            List<ConversationTurn> history = Collections.singletonList(new ConversationTurn("assistant", "prior source"));
            ModelReply reply = client.completeConversation("synthetic system", history, "current user",
                    Collections.singletonList(toolSpec()), SESSION, CancellationToken.uncancellable());
            JSONObject wire = connection.wire();
            assertEquals("POST", connection.getRequestMethod());
            assertEquals(config.getEndpoint(), factory.endpoints.get(0));
            assertEquals("fixture-model", wire.getString("model"));
            assertFalse(wire.getBoolean("stream"));
            assertEquals(3, wire.getJSONArray("messages").length());
            assertEquals("synthetic system", wire.getJSONArray("messages").getJSONObject(0).getString("content"));
            assertEquals("prior source", wire.getJSONArray("messages").getJSONObject(1).getString("content"));
            assertEquals("current user", wire.getJSONArray("messages").getJSONObject(2).getString("content"));
            assertEquals("fixture_read", wire.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"));
            assertEquals(FOOTER_LITERAL, reply.text); // No footer interpretation in phase 0.
            assertTrue(reply.calls.isEmpty());
            assertEquals(Integer.valueOf(15), reply.contextTokensUsed);
            assertEquals(ResponseDiagnostics.Completion.SUCCEEDED, reply.diagnostics.completion);
            assertTrue(reply.diagnostics.isSuccessfulFinalAnswer());
            assertEquals(Long.valueOf(10), reply.diagnostics.usage.inputTokens);
            assertEquals(Long.valueOf(5), reply.diagnostics.usage.outputTokens);
            assertEquals(Long.valueOf(4), reply.diagnostics.usage.cachedInputTokens);
            assertEquals(Long.valueOf(3), reply.diagnostics.usage.reasoningTokens);
            assertEquals(Long.valueOf(15), reply.diagnostics.usage.totalTokens);
            assertEquals(Integer.valueOf(200), reply.httpStatus);
            assertEquals(connection.responseText, reply.rawResponseBody);
            assertTrue(connection.disconnected);
            if (provider == ProviderSettings.Provider.OPENROUTER) {
                assertEquals("Jarvys Android Agent", connection.getRequestProperty("X-Title"));
                assertEquals("Bearer SYNTHETIC_ROUTER_TOKEN", connection.getRequestProperty("Authorization"));
            } else if (provider == ProviderSettings.Provider.OPENAI_API) {
                assertNull(connection.getRequestProperty("X-Title"));
                assertFalse(wire.has("max_tokens"));
                assertEquals("Bearer SYNTHETIC_API_TOKEN", connection.getRequestProperty("Authorization"));
            } else {
                assertEquals(64, wire.getInt("max_tokens"));
                assertFalse(connection.getInstanceFollowRedirects());
                assertEquals("Bearer SYNTHETIC_CUSTOM_TOKEN", connection.getRequestProperty("Authorization"));
            }
            byte[] captured = connection.requestBytes();
            byte expected = captured[0];
            captured[0] ^= 1;
            assertEquals(expected, connection.requestBytes()[0]);
        }
    }

    @Test public void codexActualWriterPreservesHeadersBodyMultibyteAndMetadata() throws Exception {
        settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX);
        String model = settings.getModel();
        FakeConnection connection = new FakeConnection(200, codexText("Texto ñ 😀 repetido repetido", "completed"));
        connection.chunkBytes = 1;
        OpenAICodexResponsesClient client = codex(new FakeFactory(connection), new ArrayList<>());
        ModelReply reply = client.completeConversation("synthetic system",
                Collections.singletonList(new ConversationTurn("assistant", "prior source")), "current user",
                Collections.singletonList(toolSpec()), SESSION, CancellationToken.uncancellable());
        JSONObject wire = connection.wire();
        assertEquals(model, wire.getString("model"));
        assertFalse(wire.getBoolean("store"));
        assertTrue(wire.getBoolean("stream"));
        assertEquals("synthetic system", wire.getString("instructions"));
        assertEquals(2, wire.getJSONArray("input").length());
        assertEquals("prior source", wire.getJSONArray("input").getJSONObject(0).getString("content"));
        assertEquals("current user", wire.getJSONArray("input").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text"));
        assertEquals("fixture_read", wire.getJSONArray("tools").getJSONObject(0).getString("name"));
        assertEquals(SESSION, connection.getRequestProperty("session-id"));
        assertEquals(SESSION, connection.getRequestProperty("session_id"));
        assertEquals(SESSION, connection.getRequestProperty("conversation_id"));
        assertEquals("Bearer SYNTHETIC_CODEX_TOKEN", connection.getRequestProperty("Authorization"));
        assertEquals("Texto ñ 😀 repetido repetido", reply.text);
        assertEquals(Integer.valueOf(15), reply.contextTokensUsed);
        assertEquals(ResponseDiagnostics.Completion.SUCCEEDED, reply.diagnostics.completion);
        assertTrue(reply.diagnostics.isSuccessfulFinalAnswer());
        assertEquals("response-fixture", reply.diagnostics.responseId);
        assertEquals(Long.valueOf(4), reply.diagnostics.usage.cachedInputTokens);
        assertEquals(Long.valueOf(3), reply.diagnostics.usage.reasoningTokens);
        assertEquals(connection.responseText, reply.rawResponseBody);
        assertTrue(connection.disconnected);
    }

    @Test public void codex401RetryUsesSameRequestAndSessionWithFakeRefreshOnly() throws Exception {
        settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX);
        FakeConnection first = new FakeConnection(401, "{\"error\":\"synthetic expiry\"}");
        FakeConnection second = new FakeConnection(200, codexText("ok", "completed"));
        FakeFactory factory = new FakeFactory(first, second);
        List<Boolean> refreshes = new ArrayList<>();
        ModelReply reply = codex(factory, refreshes).completeConversation("system", Collections.emptyList(), "user",
                Collections.emptyList(), SESSION, CancellationToken.uncancellable());
        assertEquals(Arrays.asList(false, true), refreshes);
        assertArrayEquals(first.requestBytes(), second.requestBytes());
        assertEquals(SESSION, first.getRequestProperty("session_id"));
        assertEquals(SESSION, second.getRequestProperty("session_id"));
        assertEquals("Bearer SYNTHETIC_CODEX_TOKEN", first.getRequestProperty("Authorization"));
        assertEquals("Bearer SYNTHETIC_CODEX_REFRESHED", second.getRequestProperty("Authorization"));
        assertEquals("ok", reply.text);
        assertEquals(1, reply.diagnostics.assistantItems.size());
        assertTrue(reply.diagnostics.isSuccessfulFinalAnswer());
        assertTrue(first.disconnected && second.disconnected);
    }

    @Test public void codexToolOnlyAndContinuationCaptureFunctionProtocolAtActualWriter() throws Exception {
        settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX);
        JSONObject response = new JSONObject().put("id", "response-tool-fixture").put("status", "completed")
                .put("output", new JSONArray().put(new JSONObject().put("type", "function_call")
                        .put("id", "item-tool-fixture").put("call_id", "codex-call-one")
                        .put("name", "fixture_read").put("arguments", "{}")));
        FakeConnection first = new FakeConnection(200, "data: " + new JSONObject()
                .put("type", "response.completed").put("response", response) + "\n\n");
        FakeConnection second = new FakeConnection(200, codexText("finished", "completed"));
        OpenAICodexResponsesClient client = codex(new FakeFactory(first, second), new ArrayList<>());
        ModelReply call = client.completeConversation("system", Collections.emptyList(), "synthetic task",
                Collections.singletonList(toolSpec()), SESSION, CancellationToken.uncancellable());
        assertEquals("", call.text);
        assertEquals(1, call.calls.size());
        assertEquals("codex-call-one", call.calls.get(0).id);
        assertEquals("fixture_read", call.calls.get(0).name);
        assertTrue(call.calls.get(0).arguments.isEmpty());
        assertEquals(ResponseDiagnostics.Completion.SUCCEEDED, call.diagnostics.completion);
        assertTrue(call.diagnostics.hasToolCalls);
        assertFalse(call.diagnostics.isSuccessfulFinalAnswer());
        ModelReply answer = client.completeConversation("system", Arrays.asList(
                new ConversationTurn("user", "synthetic task"),
                ConversationTurn.toolCalls(call.text, call.calls),
                ConversationTurn.toolResult("codex-call-one", "fixture_read", "synthetic output")),
                "Continue.", Collections.singletonList(toolSpec()), SESSION, CancellationToken.uncancellable());
        JSONArray input = second.wire().getJSONArray("input");
        assertEquals(4, input.length());
        assertEquals("synthetic task", input.getJSONObject(0).getString("content"));
        assertEquals("function_call", input.getJSONObject(1).getString("type"));
        assertEquals("codex-call-one", input.getJSONObject(1).getString("call_id"));
        assertEquals("{}", input.getJSONObject(1).getString("arguments"));
        assertEquals("function_call_output", input.getJSONObject(2).getString("type"));
        assertEquals("codex-call-one", input.getJSONObject(2).getString("call_id"));
        assertEquals("synthetic output", input.getJSONObject(2).getString("output"));
        assertEquals("Continue.", input.getJSONObject(3).getJSONArray("content").getJSONObject(0).getString("text"));
        assertEquals("finished", answer.text);
        assertTrue(answer.diagnostics.isSuccessfulFinalAnswer());
    }

    @Test public void bothFamiliesKeepPreparedSyntheticImagePartsAndDoNotDuplicateCurrentUser() throws Exception {
        ConversationTurn user = new ConversationTurn("user", "synthetic image task").withModelParts(
                "synthetic image task [image_ref=fixture]",
                Collections.singletonList(new ConversationTurn.Image("AA==", 1, 1)));
        FakeConnection chat = new FakeConnection(200, chatText("ok", "stop"));
        ModelReply chatReply = new OpenRouterClient(secrets, settings, ChatCompletionsConfig.openRouterDefault(),
                new FakeFactory(chat)).completeConversation("system", Collections.singletonList(user), "",
                Collections.emptyList(), SESSION, CancellationToken.uncancellable());
        JSONArray messages = chat.wire().getJSONArray("messages");
        assertEquals(2, messages.length());
        JSONArray chatParts = messages.getJSONObject(1).getJSONArray("content");
        assertEquals(user.modelContent, chatParts.getJSONObject(0).getString("text"));
        assertEquals("image_url", chatParts.getJSONObject(1).getString("type"));
        assertEquals("data:image/jpeg;base64,AA==", chatParts.getJSONObject(1).getJSONObject("image_url").getString("url"));
        assertTrue(chatReply.diagnostics.isSuccessfulFinalAnswer());
        settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX);
        FakeConnection codex = new FakeConnection(200, codexText("ok", "completed"));
        ModelReply codexReply = codex(new FakeFactory(codex), new ArrayList<>()).completeConversation("system",
                Collections.singletonList(user), "", Collections.emptyList(), SESSION, CancellationToken.uncancellable());
        JSONArray input = codex.wire().getJSONArray("input");
        assertEquals(1, input.length());
        JSONArray codexParts = input.getJSONObject(0).getJSONArray("content");
        assertEquals(user.modelContent, codexParts.getJSONObject(0).getString("text"));
        assertEquals("input_image", codexParts.getJSONObject(1).getString("type"));
        assertEquals("data:image/jpeg;base64,AA==", codexParts.getJSONObject(1).getString("image_url"));
        assertTrue(codexReply.diagnostics.isSuccessfulFinalAnswer());
        assertEquals("synthetic image task", user.content);
        assertEquals(1, user.images.size());
    }

    @Test public void codexTransportEofAndIncompleteRemainDiagnosticNotLegacyPolicyChanges() throws Exception {
        settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX);
        FakeConnection eof = new FakeConnection(200, "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"m-fixture\",\"output_index\":0,\"content_index\":0,\"delta\":\"partial\"}\n\n");
        ModelReply partial = codex(new FakeFactory(eof), new ArrayList<>()).completeConversation("sys", Collections.emptyList(), "user", Collections.emptyList(), SESSION, CancellationToken.uncancellable());
        assertEquals("partial", partial.text); // Existing parser result remains usable.
        assertEquals(ResponseDiagnostics.Completion.UNKNOWN, partial.diagnostics.completion);
        assertFalse(partial.diagnostics.isSuccessfulFinalAnswer());
        FakeConnection incomplete = new FakeConnection(200, codexText("partial", "incomplete"));
        ModelReply limited = codex(new FakeFactory(incomplete), new ArrayList<>()).completeConversation("sys", Collections.emptyList(), "user", Collections.emptyList(), SESSION, CancellationToken.uncancellable());
        assertEquals("partial", limited.text);
        assertEquals(ResponseDiagnostics.Completion.INCOMPLETE, limited.diagnostics.completion);
        assertFalse(limited.diagnostics.isSuccessfulFinalAnswer());
    }

    @Test public void chatLengthAndMissingFinishReasonKeepLegacyBodyAndUsage() throws Exception {
        for (String reason : Arrays.asList("length", null)) {
            FakeConnection connection = new FakeConnection(200, chatText("partial", reason));
            ModelReply reply = new OpenRouterClient(secrets, settings, ChatCompletionsConfig.openRouterDefault(),
                    new FakeFactory(connection)).completeConversation("sys", Collections.emptyList(), "user", Collections.emptyList(), SESSION, CancellationToken.uncancellable());
            assertEquals("partial", reply.text);
            assertEquals(Integer.valueOf(15), reply.contextTokensUsed);
            assertEquals(reason == null ? ResponseDiagnostics.Completion.UNKNOWN : ResponseDiagnostics.Completion.INCOMPLETE,
                    reply.diagnostics.completion);
            assertFalse(reply.diagnostics.isSuccessfulFinalAnswer());
        }
    }

    @Test public void cancellationBeforeSendDoesNotWriteOrProduceAReply() throws Exception {
        CancellationToken token = CancellationToken.cancellable();
        token.cancel();
        FakeConnection connection = new FakeConnection(200, chatText("must not be consumed", "stop"));
        FakeFactory factory = new FakeFactory(connection);
        OpenRouterClient client = new OpenRouterClient(secrets, settings, ChatCompletionsConfig.openRouterDefault(), factory);
        RuntimeException failure = assertThrows(RuntimeException.class, () -> client.completeConversation("sys", Collections.emptyList(), "user", Collections.emptyList(), SESSION, token));
        assertTrue(failure instanceof CancellationException || failure.getCause() instanceof CancellationException);
        assertEquals(0, factory.endpoints.size());
        assertEquals(0, connection.requestBytes().length);
    }

    @Test public void codexCancellationWhileReadingPreservesCancellationAndDisconnect() throws Exception {
        settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX);
        CancellationToken token = CancellationToken.cancellable();
        FakeConnection connection = new FakeConnection(200, codexText("never completed locally", "completed"));
        connection.chunkBytes = 1;
        connection.afterRead = token::cancel;
        assertThrows(CancellationException.class, () -> codex(new FakeFactory(connection), new ArrayList<>())
                .completeConversation("sys", Collections.emptyList(), "user", Collections.emptyList(), SESSION, token));
        assertTrue(token.isCancelled());
        assertTrue(connection.disconnected);
    }

    @Test public void truncatedTransportBodyDoesNotFabricateCompletedReply() throws Exception {
        settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX);
        FakeConnection connection = new FakeConnection(200, codexText("body", "completed"));
        connection.failAfterBytes = 20;
        connection.chunkBytes = 1;
        assertThrows(ProviderTransportException.class, () -> codex(new FakeFactory(connection), new ArrayList<>())
                .completeConversation("sys", Collections.emptyList(), "user", Collections.emptyList(), SESSION, CancellationToken.uncancellable()));
        assertTrue(connection.disconnected);
    }

    @Test public void malformedSseRetainsLegacyParseFailure() throws Exception {
        settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX);
        FakeConnection connection = new FakeConnection(200, "data: {\"type\":\"response.completed\",\"response\":\n\n");
        assertThrows(IllegalStateException.class, () -> codex(new FakeFactory(connection), new ArrayList<>())
                .completeConversation("sys", Collections.emptyList(), "user", Collections.emptyList(), SESSION, CancellationToken.uncancellable()));
        assertTrue(connection.disconnected);
    }

    @Test public void twoToolRoundsUseActualLegacyWireAndDurableRecordsWithoutMemoryActivation() throws Exception {
        FakeConnection one = new FakeConnection(200, chatCall("call-one"));
        FakeConnection two = new FakeConnection(200, chatCall("call-two"));
        FakeConnection three = new FakeConnection(200, chatText(FOOTER_LITERAL, "stop"));
        OpenRouterClient client = new OpenRouterClient(secrets, settings, ChatCompletionsConfig.openRouterDefault(),
                new FakeFactory(one, two, three));
        AtomicInteger effects = new AtomicInteger();
        CoreTool tool = new CoreTool() {
            @Override public ToolSpec declaration() { return toolSpec(); }
            @Override public CoreToolResult execute(Map<String, Object> args, CancellationToken token) {
                return CoreToolResult.success("synthetic result " + effects.incrementAndGet());
            }
        };
        File files = Files.createTempDirectory(context.getCacheDir().toPath(), "ux34-ledger-").toFile();
        LocalRunStore store = new LocalRunStore(files);
        String userId = store.appendConversationMessage(SESSION, "user", "synthetic task");
        CoreAgentLoop.Model model = (history, prompt, tools, token) -> {
            ModelReply reply = client.completeConversation("system", history, prompt, tools, SESSION, token);
            assertEquals(ResponseDiagnostics.Completion.SUCCEEDED, reply.diagnostics.completion);
            assertEquals(reply.calls.isEmpty(), reply.diagnostics.isSuccessfulFinalAnswer());
            return reply;
        };
        CoreAgentLoop loop = new CoreAgentLoop(model, new CoreToolRegistry(Collections.singletonList(tool)), "system", SESSION);
        new MainChatTranscriptStore(files, SESSION, store).attach(loop, Collections.emptyList());
        CoreAgentLoop.Result result = loop.run("synthetic task", Collections.emptyList(), CancellationToken.uncancellable(), null);
        assertEquals(2, effects.get());
        assertEquals(3, result.turns);
        assertEquals(FOOTER_LITERAL, result.text);
        store.appendConversationMessage(SESSION, "assistant", result.text, result.durationMs, result.runId, userId, result.outcome);
        for (FakeConnection c : Arrays.asList(one, two, three)) {
            JSONArray messages = c.wire().getJSONArray("messages");
            int occurrences = 0;
            for (int i = 0; i < messages.length(); i++) {
                if ("synthetic task".equals(messages.getJSONObject(i).optString("content"))) occurrences++;
            }
            assertEquals(1, occurrences);
        }
        assertEquals(2, one.wire().getJSONArray("messages").length());
        assertEquals(5, two.wire().getJSONArray("messages").length());
        assertEquals(7, three.wire().getJSONArray("messages").length());
        assertEquals("Continue.", three.wire().getJSONArray("messages").getJSONObject(6).getString("content"));
        List<ConversationTurn> restored = new LocalRunStore(files).loadConversationContext(SESSION);
        assertEquals(2, restored.stream().filter(t -> t.kind == ConversationTurn.Kind.TOOL_CALLS).count());
        assertEquals(2, restored.stream().filter(t -> t.kind == ConversationTurn.Kind.TOOL_RESULT).count());
        assertEquals(FOOTER_LITERAL, restored.get(restored.size() - 1).content);
        List<JSONObject> modelRows = store.readModelTranscriptRows(SESSION);
        assertEquals(6, modelRows.size()); // 2 intents, 2 started, 2 results; no new schema.
        assertFalse(restored.stream().anyMatch(t -> t.kind == ConversationTurn.Kind.COMPACTION_SUMMARY));
        assertEquals(2, effects.get()); // Reload does not invoke either synthetic tool.
    }

    @Test public void legacyModelReplyConstructorsHaveUnknownEvidenceWithoutChangingCounterRules() {
        ModelReply reply = new ModelReply("legacy", Collections.emptyList(), "raw", 200, "model", 0);
        assertEquals("legacy", reply.text);
        assertNull(reply.contextTokensUsed);
        assertEquals(ResponseDiagnostics.Completion.UNKNOWN, reply.diagnostics.completion);
        assertFalse(reply.diagnostics.isSuccessfulFinalAnswer());
        ModelReply copy = new ModelReply(reply.text, reply.calls, reply.rawResponseBody,
                reply.httpStatus, reply.model, 15, null);
        assertEquals(Integer.valueOf(15), copy.contextTokensUsed);
        assertEquals(ResponseDiagnostics.Completion.UNKNOWN, copy.diagnostics.completion);
    }

    private OpenAICodexResponsesClient codex(FakeFactory factory, List<Boolean> refreshes) {
        return new OpenAICodexResponsesClient(settings, (forceRefresh, token) -> {
            token.throwIfCancelled();
            refreshes.add(forceRefresh);
            if (forceRefresh) secrets.saveCodexTokens("SYNTHETIC_CODEX_REFRESHED", "SYNTHETIC_REFRESH_TOKEN",
                    Long.MAX_VALUE, "SYNTHETIC_ACCOUNT");
            return secrets.getCodexCredentials();
        }, factory);
    }

    private static ToolSpec toolSpec() {
        return new ToolSpec("fixture_read", "synthetic", "Read synthetic data", "test", ToolSpec.Status.IMPLEMENTED,
                Collections.emptyMap(), Collections.emptyList());
    }

    private static String chatText(String text, String reason) throws Exception {
        JSONObject choice = new JSONObject().put("index", 0)
                .put("message", new JSONObject().put("role", "assistant").put("content", text));
        if (reason != null) choice.put("finish_reason", reason);
        return new JSONObject().put("id", "chat-fixture").put("choices", new JSONArray().put(choice))
                .put("usage", new JSONObject().put("prompt_tokens", 10).put("completion_tokens", 5).put("total_tokens", 15)
                        .put("prompt_tokens_details", new JSONObject().put("cached_tokens", 4))
                        .put("completion_tokens_details", new JSONObject().put("reasoning_tokens", 3))).toString();
    }

    private static String chatCall(String id) throws Exception {
        JSONObject root = new JSONObject(chatText("", "tool_calls"));
        root.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                .put("tool_calls", new JSONArray().put(new JSONObject().put("id", id).put("type", "function")
                        .put("function", new JSONObject().put("name", "fixture_read").put("arguments", "{}"))));
        return root.toString();
    }

    private static String codexText(String text, String status) throws Exception {
        JSONObject response = new JSONObject().put("id", "response-fixture").put("status", status)
                .put("output", new JSONArray().put(new JSONObject().put("id", "message-fixture").put("type", "message")
                        .put("role", "assistant").put("status", "completed".equals(status) ? "completed" : "incomplete")
                        .put("content", new JSONArray().put(new JSONObject().put("type", "output_text").put("text", text)))))
                .put("usage", new JSONObject().put("input_tokens", 10).put("output_tokens", 5).put("total_tokens", 15)
                        .put("input_tokens_details", new JSONObject().put("cached_tokens", 4))
                        .put("output_tokens_details", new JSONObject().put("reasoning_tokens", 3)));
        if ("incomplete".equals(status)) response.put("incomplete_details", new JSONObject().put("reason", "max_output_tokens"));
        return "data: " + new JSONObject().put("type", "response." + status).put("response", response) + "\n\n";
    }

    private static final class FakeFactory implements ProviderHttp.ConnectionFactory {
        final Deque<FakeConnection> queued = new ArrayDeque<>();
        final List<String> endpoints = new ArrayList<>();
        FakeFactory(FakeConnection... connections) { queued.addAll(Arrays.asList(connections)); }
        @Override public HttpURLConnection open(String endpoint) {
            endpoints.add(endpoint);
            if (queued.isEmpty()) throw new AssertionError("Unexpected network attempt: no synthetic response queued");
            return queued.removeFirst();
        }
    }

    private static final class FakeConnection extends HttpURLConnection {
        final int status;
        final String responseText;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        int chunkBytes = Integer.MAX_VALUE;
        int failAfterBytes = Integer.MAX_VALUE;
        Runnable afterRead;
        boolean disconnected;
        FakeConnection(int status, String responseText) throws Exception {
            super(new URL("https://synthetic.invalid/never-opened"));
            this.status = status;
            this.responseText = responseText;
        }
        byte[] requestBytes() { return output.toByteArray(); }
        JSONObject wire() throws Exception { return new JSONObject(new String(requestBytes(), StandardCharsets.UTF_8)); }
        @Override public void connect() { }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() {
            ByteArrayInputStream bytes = new ByteArrayInputStream(responseText.getBytes(StandardCharsets.UTF_8));
            return new InputStream() {
                int count;
                @Override public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
                }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (count >= failAfterBytes) throw new IOException("synthetic truncated transport");
                    int size = bytes.read(b, off, Math.min(len, chunkBytes));
                    if (size > 0) {
                        count += size;
                        if (afterRead != null) { Runnable action = afterRead; afterRead = null; action.run(); }
                    }
                    return size;
                }
            };
        }
        @Override public InputStream getErrorStream() { return getInputStream(); }
    }
}
