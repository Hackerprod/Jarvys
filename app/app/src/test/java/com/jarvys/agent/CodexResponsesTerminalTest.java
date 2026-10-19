package com.jarvys.agent;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Protocol regression fixtures only. No network, account access, or provider replay. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class,
        shadows = {ArtifactOsShadow.class, ArtifactOsShadow.Descriptor.class})
public class CodexResponsesTerminalTest {
    @Rule public final TemporaryFolder diagnosticsFolder = new TemporaryFolder();
    private ProviderSettings settings;
    private SecretStore secrets;
    @Before public void setup() {
        Context context = ApplicationProvider.getApplicationContext();
        settings = new ProviderSettings(context);
        settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX);
        secrets = new SecretStore(context.getSharedPreferences("terminal-synthetic", Context.MODE_PRIVATE));
        secrets.saveCodexTokens("SYNTHETIC_ACCESS", "SYNTHETIC_REFRESH", Long.MAX_VALUE, "SYNTHETIC_ACCOUNT");
    }

    @Test public void completedReturnsWithoutWaitingForEof() throws Exception {
        Fixture fixture = fixture(sse(completed("Listo")), true, 8192);
        assertEquals("Listo", fixture.complete().text);
        fixture.assertClosedOnce();
        assertEquals(0, fixture.connection.readsPastPayload);
    }

    @Test public void utf8AndCrLfCanBeSplitAtEveryByte() throws Exception {
        Fixture fixture = fixture(sse(completed("Español 😀 漢字")).replace("\n", "\r\n"), true, 1);
        assertEquals("Español 😀 漢字", fixture.complete().text);
        fixture.assertClosedOnce();
    }

    @Test public void commentsAndMultilineDataDoNotBecomeCompletion() throws Exception {
        String event = "data: {\"type\":\"response.completed\",\n"
                + "data: \"response\":" + completed("ok").getJSONObject("response") + "}\n\n";
        Fixture fixture = fixture(": heartbeat\n\nevent: response.created\ndata: {\"type\":\"response.created\"}\n\n" + event, true, 3);
        assertEquals("ok", fixture.complete().text);
        fixture.assertClosedOnce();
    }

    @Test public void completedToolCallIsReturnedOnce() throws Exception {
        JSONObject response = completed("").getJSONObject("response");
        response.put("output", new JSONArray().put(call()));
        Fixture fixture = fixture(sse(new JSONObject().put("type", "response.completed").put("response", response)), true, 7);
        ModelReply reply = fixture.complete();
        assertEquals(1, reply.calls.size());
        assertEquals("fixture_write", reply.calls.get(0).name);
        fixture.assertClosedOnce();
    }

    @Test public void selectedMaximumEffortIsUsedByThePreparedResponsesRequest() throws Exception {
        settings.setCodexModelAndVariant("gpt-6-astra", "max");
        Context context = ApplicationProvider.getApplicationContext();
        Field singleton = LocalDiagnostics.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        Object previousDiagnostics = singleton.get(null);
        LocalDiagnostics diagnostics = new LocalDiagnostics(diagnosticsFolder.newFolder("no-backup"), System::currentTimeMillis);
        singleton.set(null, diagnostics);
        List<ToolSpec> declarations = Collections.singletonList(new ToolSpec("search", "test", "", "web",
                ToolSpec.Status.IMPLEMENTED, Collections.emptyMap(), Collections.emptyList()));
        Fixture fixture = fixture(sse(completed("Done")), true, 5);
        fixture.diagnosticsContext = context;
        try {
            assertEquals("Done", fixture.completeWithTools(declarations).text);

            JSONObject request = new JSONObject(fixture.connection.requestBody.toString(StandardCharsets.UTF_8.name()));
            assertEquals("gpt-6-astra", request.getString("model"));
            assertEquals("max", request.getJSONObject("reasoning").getString("effort"));
            assertEquals("detailed", request.getJSONObject("reasoning").getString("summary"));
            assertEquals("medium", request.getJSONObject("text").getString("verbosity"));
            assertEquals(1, request.getJSONArray("tools").length());

            JSONObject snapshot = diagnostics.snapshot(null, 10, false);
            assertEquals("ok", snapshot.getString("status"));
            JSONArray records = snapshot.getJSONArray("records");
            assertEquals(1, records.length());
            JSONObject event = records.getJSONObject(0);
            assertEquals("model_request", event.getString("kind"));
            JSONObject parameters = event.getJSONObject("model_request");
            assertEquals("gpt-6-astra", parameters.getString("model"));
            assertEquals("max", parameters.getString("reasoning_effort"));
            assertEquals("detailed", parameters.getString("reasoning_summary"));
            assertEquals("medium", parameters.getString("text_verbosity"));
            assertEquals(1, parameters.getInt("declared_tool_count"));
            assertTrue(parameters.getBoolean("web_available"));
        } finally {
            singleton.set(null, previousDiagnostics);
        }
    }

    @Test public void completedCodexReplyRetainsOrderedReasoningItemsThroughTheAdapter() throws Exception {
        JSONObject reasoning = new JSONObject().put("type", "reasoning").put("id", "rs_adapter")
                .put("encrypted_content", "encrypted-adapter-sentinel").put("summary", new JSONArray());
        JSONObject message = message("Checking the source").put("id", "msg_adapter");
        JSONObject response = completed("").getJSONObject("response");
        response.put("output", new JSONArray().put(reasoning).put(message).put(call()));
        Fixture fixture = fixture(sse(new JSONObject().put("type", "response.completed").put("response", response)), true, 5);

        ModelReply reply = fixture.complete();

        assertEquals(3, reply.providerOutputItems.size());
        assertEquals(ModelReply.ProviderOutputItem.Kind.REASONING, reply.providerOutputItems.get(0).kind);
        assertTrue(reply.providerOutputItems.get(0).value.contains("encrypted-adapter-sentinel"));
        assertEquals(ProviderSettings.Provider.OPENAI_CODEX.name(), reply.providerOutputItems.get(0).provider);
        assertEquals("responses", reply.providerOutputItems.get(0).api);
        assertEquals(settings.getModel(), reply.providerOutputItems.get(0).model);
        assertEquals(ModelReply.ProviderOutputItem.Kind.MESSAGE, reply.providerOutputItems.get(1).kind);
        assertEquals("Checking the source", reply.providerOutputItems.get(1).value);
        assertEquals(ModelReply.ProviderOutputItem.Kind.TOOL_CALL, reply.providerOutputItems.get(2).kind);
        assertEquals("synthetic-call", reply.providerOutputItems.get(2).value);
        assertTrue(reply.diagnostics.assistantItems.stream().noneMatch(item ->
                String.valueOf(item.text).contains("encrypted-adapter-sentinel")
                        || String.valueOf(item.providerId).contains("encrypted-adapter-sentinel")));
        fixture.assertClosedOnce();
    }

    @Test public void emptyCompletedOutputRecoversMessageFromOutputItemDone() throws Exception {
        JSONObject value = completed("");
        value.getJSONObject("response").put("output", new JSONArray());
        Fixture fixture = fixture(sse(doneMessage("Texto recuperado")) + sse(value), true, 3);
        assertEquals("Texto recuperado", fixture.complete().text);
        fixture.assertClosedOnce();
    }

    @Test public void emptyCompletedOutputRecoversOnlyCompletedFunctionCall() throws Exception {
        JSONObject value = completed("");
        value.getJSONObject("response").put("output", new JSONArray());
        Fixture fixture = fixture(sse(doneCall(call())) + sse(value), true, 2);
        ModelReply reply = fixture.complete();
        assertEquals(1, reply.calls.size());
        assertEquals("fixture_write", reply.calls.get(0).name);
        fixture.assertClosedOnce();
    }

    @Test public void outputItemDoneWithNonCompletedFunctionCallDoesNotCreateToolCall() throws Exception {
        JSONObject value = completed("");
        value.getJSONObject("response").put("output", new JSONArray());
        Fixture fixture = fixture(sse(doneCall(call().put("status", "in_progress"))) + sse(value), true, 2);
        ModelReply reply = fixture.complete();
        assertTrue(reply.calls.isEmpty());
        assertEquals("", reply.text);
        fixture.assertClosedOnce();
    }

    @Test public void completedOutputWinsWithoutDuplicatingMatchingDoneItems() throws Exception {
        JSONObject value = completed("");
        value.getJSONObject("response").put("output", new JSONArray()
                .put(message("Answer")).put(call()));
        String stream = sse(doneMessage("Answer")) + sse(doneCall(call())) + sse(value);
        ModelReply reply = fixture(stream, true, 4).complete();
        assertEquals("Answer", reply.text);
        assertEquals(1, reply.calls.size());
        assertEquals("synthetic-call", reply.calls.get(0).id);
    }

    @Test public void textDeltasAreLastResortAndNeverCreateCalls() throws Exception {
        JSONObject value = completed("");
        value.getJSONObject("response").put("output", new JSONArray());
        String delta = sse(new JSONObject().put("type", "response.output_text.delta")
                .put("item_id", "synthetic-message").put("output_index", 0).put("content_index", 0)
                .put("delta", "Texto desde delta"));
        assertEquals("Texto desde delta", fixture(delta + sse(value), true, 2).complete().text);
        String repeated = sse(new JSONObject().put("type", "response.output_text.delta")
                .put("item_id", "synthetic-message").put("output_index", 0).put("content_index", 0)
                .put("delta", "ha"));
        assertEquals("haha", fixture(repeated + repeated + sse(value), true, 2).complete().text);
        String arguments = sse(new JSONObject().put("type", "response.function_call_arguments.delta")
                .put("item_id", "synthetic-call").put("output_index", 0).put("delta", "{}"));
        ModelReply reply = fixture(arguments + sse(value), true, 2).complete();
        assertTrue(reply.calls.isEmpty());
    }

    @Test public void streamingListenerReceivesOutputTextButNeverToolArgumentsOrReasoning() throws Exception {
        JSONObject output = completed("Authoritative answer").getJSONObject("response");
        output.put("output", new JSONArray().put(message("Authoritative answer")).put(call()));
        JSONObject messageAdded = new JSONObject().put("type", "response.output_item.added")
                .put("output_index", 0).put("item", new JSONObject().put("type", "message").put("id", "stream-message"));
        JSONObject toolDelta = new JSONObject().put("type", "response.function_call_arguments.delta")
                .put("output_index", 1).put("item_id", "stream-call").put("delta", "SECRET_TOOL_ARGUMENTS");
        JSONObject reasoningDelta = new JSONObject().put("type", "response.reasoning_summary_text.delta")
                .put("delta", "PRIVATE_REASONING");
        JSONObject textDelta = new JSONObject().put("type", "response.output_text.delta")
                .put("output_index", 0).put("item_id", "stream-message").put("delta", "Visible text");
        Fixture fixture = fixture(sse(messageAdded) + sse(toolDelta) + sse(reasoningDelta)
                + sse(textDelta) + sse(new JSONObject().put("type", "response.completed").put("response", output)), true, 2);
        List<String> deltas = new ArrayList<>();

        ModelReply reply = fixture.complete(deltas::add);

        assertEquals(Collections.singletonList("Visible text"), deltas);
        assertEquals("Authoritative answer", reply.text);
        assertEquals(1, reply.calls.size());
        fixture.assertClosedOnce();
    }

    @Test public void failedAfterPartialCallNeverReturnsExecutableReply() throws Exception {
        assertRejected(partialCall() + sse(new JSONObject().put("type", "response.failed")
                .put("response", new JSONObject().put("status", "failed").put("output", new JSONArray().put(call())))), true);
    }

    @Test public void incompleteAfterPartialCallNeverReturnsExecutableReply() throws Exception {
        assertRejected(partialCall() + sse(new JSONObject().put("type", "response.incomplete")
                .put("response", new JSONObject().put("status", "incomplete").put("output", new JSONArray().put(call())))), true);
    }

    @Test public void errorAfterPartialCallNeverReturnsExecutableReply() throws Exception {
        assertRejected(partialCall() + "data: {\"type\":\"error\",\"message\":\"synthetic failure\"}\n\n", true);
    }

    @Test public void eofAfterPartialCallIsNotSuccess() throws Exception { assertRejected(partialCall(), false); }
    @Test public void doneWithoutCompletedPayloadIsNotSuccess() throws Exception { assertRejected("data: [DONE]\n\n", false); }
    @Test public void malformedEventIsNotSuccess() throws Exception { assertRejected("data: {broken}\n\n", false); }
    @Test public void completedWithoutResponseIsNotSuccess() throws Exception { assertRejected("data: {\"type\":\"response.completed\"}\n\n", false); }
    @Test public void completedWithoutOutputIsNotSuccess() throws Exception {
        assertRejected("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n", false);
    }
    @Test public void completedWithIncompleteStatusIsNotSuccess() throws Exception {
        JSONObject value = completed("partial"); value.getJSONObject("response").put("status", "incomplete");
        assertRejected(sse(value), false);
    }

    @Test public void completeNonSseJsonRemainsSupported() throws Exception {
        Fixture fixture = fixture(completed("JSON completo").toString(), false, 2);
        assertEquals("JSON completo", fixture.complete().text);
        fixture.assertClosedOnce();
    }

    @Test public void httpErrorRemainsHttpErrorWithoutReplay() throws Exception {
        Fixture fixture = fixture("{\"error\":{\"message\":\"synthetic unavailable\"}}", false, 8);
        fixture.connection.status = 503;
        try { fixture.complete(); fail("Expected HTTP failure"); }
        catch (CodexHttpException expected) { }
        fixture.assertClosedOnce();
    }

    @Test public void idleTimeoutDoesNotManufactureStopOrReplay() throws Exception {
        Fixture fixture = fixture(": heartbeat\n\n", false, 1);
        fixture.connection.atEnd = () -> { throw new SocketTimeoutException("synthetic idle timeout"); };
        try { fixture.complete(); fail("Expected transport failure"); }
        catch (ProviderTransportException expected) { assertTrue(expected.getCause() instanceof SocketTimeoutException); }
        assertFalse(fixture.token.isCancellationRequested());
        assertFalse(Thread.currentThread().isInterrupted());
        fixture.assertClosedOnce();
    }

    @Test public void genuineStopDuringReadKeepsCancellationAndDisconnects() throws Exception {
        Fixture fixture = fixture(": heartbeat\n\n", false, 1);
        fixture.connection.atEnd = () -> { fixture.token.cancel(); throw new IOException("closed after stop"); };
        try { fixture.complete(); fail("Expected cancellation"); }
        catch (CancellationException expected) { }
        assertTrue(fixture.token.isCancellationRequested());
        fixture.assertClosedOnce();
    }

    @Test public void imageSharedTransportRetainsCompletedPayloadWithoutEof() throws Exception {
        JSONObject value = completed("");
        value.getJSONObject("response").put("output", new JSONArray().put(new JSONObject()
                .put("type", "image_generation_call").put("status", "completed").put("result", "c3ludGhldGlj")));
        Fixture fixture = fixture(sse(value), true, 1);
        ProviderHttp.Response response = OpenAICodexResponsesClient.sendImageRequest(new JSONObject(),
                secrets.getCodexCredentials(), "synthetic-image", fixture.token, fixture);
        assertTrue(response.body.contains("c3ludGhldGlj"));
        assertFalse(fixture.connection.getInstanceFollowRedirects());
        fixture.assertClosedOnce();
    }

    @Test public void bomBeforeCompletedEventIsSupported() throws Exception {
        Fixture fixture = fixture("\ufeff" + sse(completed("Listo")), true, 1);
        assertEquals("Listo", fixture.complete().text);
        fixture.assertClosedOnce();
    }

    @Test public void partialArgumentsInEarlierEventsAreIgnoredUntilFinalSnapshot() throws Exception {
        JSONObject added = new JSONObject().put("type", "response.output_item.added")
                .put("item", call().put("arguments", "{\"partial\":"));
        Fixture fixture = fixture(sse(added) + sse(completed("Final")), true, 2);
        ModelReply reply = fixture.complete();
        assertEquals("Final", reply.text);
        assertTrue(reply.calls.isEmpty());
    }

    @Test public void malformedFinalToolArgumentsStayPerCallWithoutRejectingTheResponse() throws Exception {
        for (String arguments : new String[] {"{unquoted:1}", "{\"x\":"}) {
            JSONObject value = completed("");
            value.getJSONObject("response").put("output", new JSONArray().put(call().put("arguments", arguments)));
            ModelReply reply = fixture(sse(value), true, 1).complete();
            assertEquals(1, reply.calls.size());
            assertEquals("synthetic-call", reply.calls.get(0).id);
            assertNotNull(reply.calls.get(0).invalidArgumentsReason);
            assertTrue(reply.calls.get(0).arguments.isEmpty());
        }
    }

    @Test public void emptyFinalToolArgumentsAreParsedAsAnEmptyObject() throws Exception {
        for (String arguments : new String[] {"", "   \t\r\n"}) {
            JSONObject value = completed("");
            value.getJSONObject("response").put("output", new JSONArray().put(call().put("arguments", arguments)));

            ModelReply reply = fixture(sse(value), true, 1).complete();

            assertEquals(1, reply.calls.size());
            assertEquals(Collections.emptyMap(), reply.calls.get(0).arguments);
            assertNull(reply.calls.get(0).invalidArgumentsReason);
        }
    }

    @Test public void nonStringFinalToolArgumentsStayPerCallWithoutRejectingResponse() throws Exception {
        JSONObject value = completed("");
        value.getJSONObject("response").put("output", new JSONArray()
                .put(call().put("arguments", new JSONObject().put("path", "note.txt")))
                .put(call().put("call_id", "null-arguments").put("arguments", JSONObject.NULL)));

        ModelReply reply = fixture(sse(value), true, 1).complete();

        assertEquals(2, reply.calls.size());
        assertTrue(reply.calls.get(0).arguments.isEmpty());
        assertNotNull(reply.calls.get(0).invalidArgumentsReason);
        assertEquals("null-arguments", reply.calls.get(1).id);
        assertTrue(reply.calls.get(1).arguments.isEmpty());
        assertNotNull(reply.calls.get(1).invalidArgumentsReason);
    }

    @Test public void emptyAssistantTurnIsOmittedFromResponsesRequest() throws Exception {
        ConversationTurn emptyAssistant = ConversationTurn.assistantReply("", Collections.emptyList());

        JSONArray input = OpenAICodexResponsesClient.userInput("next user message", Collections.emptyList(),
                Collections.singletonList(emptyAssistant));

        assertEquals(1, input.length());
        assertEquals("user", input.getJSONObject(0).getString("role"));
        assertEquals("input_text", input.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("type"));
    }

    @Test public void prettyPrintedNonSseJsonPreservesText() throws Exception {
        Fixture fixture = fixture(completed("JSON pretty").getJSONObject("response").toString(2), false, 1);
        assertEquals("JSON pretty", fixture.complete().text);
    }

    @Test public void incompleteFinalToolItemNeverExecutes() throws Exception {
        JSONObject value = completed("");
        value.getJSONObject("response").put("output", new JSONArray().put(call().put("status", "in_progress")));
        assertRejected(sse(value), true);
    }

    @Test public void nonSseCreatedEventCannotPretendToBeComplete() throws Exception {
        JSONObject value = completed("partial").put("type", "response.created");
        value.getJSONObject("response").remove("status");
        assertRejected(value.toString(), false);
    }

    private void assertRejected(String data, boolean terminal) throws Exception {
        Fixture fixture = fixture(data, terminal, 1);
        try { fixture.complete(); fail("Partial or invalid response must not return tool calls"); }
        catch (CodexResponseException expected) {
            assertNotNull(expected.kind);
        }
        assertFalse(fixture.token.isCancellationRequested());
        fixture.assertClosedOnce();
    }
    private Fixture fixture(String data, boolean failPastPayload, int chunk) throws Exception {
        return new Fixture(new FakeConnection(data, failPastPayload, chunk));
    }
    private static JSONObject completed(String text) throws Exception {
        return new JSONObject().put("type", "response.completed").put("response", new JSONObject()
                .put("id", "synthetic-response").put("status", "completed")
                .put("output", new JSONArray().put(new JSONObject().put("type", "message")
                    .put("role", "assistant").put("status", "completed")
                    .put("content", new JSONArray().put(new JSONObject().put("type", "output_text").put("text", text))))));
    }
    private static JSONObject call() throws Exception {
        return new JSONObject().put("type", "function_call").put("call_id", "synthetic-call")
                .put("name", "fixture_write").put("arguments", "{\"path\":\"note.txt\"}")
                .put("status", "completed");
    }
    private static JSONObject message(String text) throws Exception {
        return new JSONObject().put("type", "message").put("role", "assistant").put("status", "completed")
                .put("content", new JSONArray().put(new JSONObject().put("type", "output_text").put("text", text)));
    }
    private static JSONObject doneMessage(String text) throws Exception {
        return new JSONObject().put("type", "response.output_item.done").put("output_index", 0)
                .put("item", message(text));
    }
    private static JSONObject doneCall(JSONObject item) throws Exception {
        return new JSONObject().put("type", "response.output_item.done").put("output_index", 1)
                .put("item", item);
    }
    private static String partialCall() throws Exception {
        return sse(new JSONObject().put("type", "response.output_item.done").put("output_index", 0).put("item", call()));
    }
    private static String sse(JSONObject value) { return "data: " + value + "\n\n"; }

    private final class Fixture implements ProviderHttp.ConnectionFactory {
        final FakeConnection connection;
        final CancellationToken token = CancellationToken.cancellable();
        Context diagnosticsContext;
        int opens;
        int credentialRequests;
        Fixture(FakeConnection connection) { this.connection = connection; }
        @Override public HttpURLConnection open(String endpoint) {
            opens++;
            assertEquals("No request replay", 1, opens);
            return connection;
        }
        ModelReply complete() {
            return complete(null);
        }
        ModelReply complete(ModelProviderClient.TextDeltaListener listener) {
            return completeWithTools(listener, Collections.emptyList());
        }
        ModelReply completeWithTools(List<ToolSpec> tools) {
            return completeWithTools(null, tools);
        }
        private ModelReply completeWithTools(ModelProviderClient.TextDeltaListener listener, List<ToolSpec> tools) {
            CodexAuthenticatedRequestExecutor.CredentialProvider credentialProvider = (refresh, ignored) -> {
                credentialRequests++;
                assertFalse("No refresh/replay for stream failures", refresh);
                return secrets.getCodexCredentials();
            };
            OpenAICodexResponsesClient client = new OpenAICodexResponsesClient(
                    diagnosticsContext, settings, credentialProvider, this);
            return client.completeConversation("synthetic system", Collections.emptyList(), "synthetic user",
                    Collections.emptyList(), tools, "synthetic-terminal", token, listener);
        }
        void assertClosedOnce() {
            assertEquals(1, opens);
            assertTrue(connection.disconnected);
            assertTrue(connection.streamClosed);
            assertEquals(20000, connection.getConnectTimeout());
            assertEquals(90000, connection.getReadTimeout());
            assertTrue(credentialRequests <= 1);
        }
    }
    private interface EndAction { void run() throws IOException; }
    private static final class FakeConnection extends HttpURLConnection {
        final byte[] payload;
        final boolean failPastPayload;
        final int chunk;
        final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
        int offset, readsPastPayload, status = 200;
        boolean disconnected, streamClosed;
        EndAction atEnd;
        FakeConnection(String payload, boolean failPastPayload, int chunk) throws Exception {
            super(new URL("https://fixture.invalid/no-network"));
            this.payload = payload.getBytes(StandardCharsets.UTF_8);
            this.failPastPayload = failPastPayload; this.chunk = chunk;
        }
        @Override public void connect() { }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public OutputStream getOutputStream() { return requestBody; }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getErrorStream() { return getInputStream(); }
        @Override public InputStream getInputStream() {
            return new InputStream() {
                @Override public int read() throws IOException {
                    byte[] one = new byte[1]; int count = read(one, 0, 1); return count < 0 ? -1 : one[0] & 255;
                }
                @Override public int read(byte[] out, int start, int length) throws IOException {
                    if (length == 0) return 0;
                    if (offset == payload.length) {
                        readsPastPayload++;
                        if (atEnd != null) atEnd.run();
                        if (failPastPayload) throw new AssertionError("Reader waited for EOF after terminal SSE event");
                        return -1;
                    }
                    int size = Math.min(Math.min(chunk, length), payload.length - offset);
                    System.arraycopy(payload, offset, out, start, size); offset += size; return size;
                }
                @Override public void close() { assertTrue("Disconnect before stream close can drain", disconnected); streamClosed = true; }
            };
        }
    }
}
