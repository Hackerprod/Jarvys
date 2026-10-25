package com.jarvys.agent;

import static org.junit.Assert.*;
import android.content.Context;
import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.CancellationException;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Offline synthetic failures only; no provider calls or real process handler installation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class,
    shadows = {ArtifactOsShadow.class, ArtifactOsShadow.Descriptor.class})
public class ForegroundFailureDiagnosticsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private LocalDiagnostics store, previous;
    private Field instance;
    private Context context;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        store = new LocalDiagnostics(temporary.newFolder("no-backup"), () -> 1_800_000_000_000L);
        instance = LocalDiagnostics.class.getDeclaredField("instance");
        instance.setAccessible(true);
        previous = (LocalDiagnostics) instance.get(null);
        instance.set(null, store);
    }
    @After public void tearDown() throws Exception {
        instance.set(null, previous);
        ArtifactOsShadow.reset();
    }
    public static void fixtureMethod() { }
    private static RuntimeException failure() {
        RuntimeException error = new IllegalStateException("CANARY_MESSAGE /private/path sk-private");
        error.setStackTrace(new StackTraceElement[]{new StackTraceElement(
            ForegroundFailureDiagnosticsTest.class.getName(), "fixtureMethod", "CANARY_FILENAME", 123)});
        error.addSuppressed(new IllegalArgumentException("CANARY_SUPPRESSED"));
        return error;
    }
    @Test public void serviceBoundaryRetainsOnlySanitizedStructureWithoutReplacingOsHandler() throws Exception {
        Thread.UncaughtExceptionHandler prior = Thread.getDefaultUncaughtExceptionHandler();
        LocalDiagnostics.recoverable(context, LocalDiagnostics.Origin.SERVICE, failure());
        JSONObject row = store.snapshot(null, 1, true).getJSONArray("records").getJSONObject(0);
        assertEquals("SERVICE", row.getString("origin"));
        assertEquals("exception", row.getString("kind"));
        assertEquals("recoverable", row.getString("severity"));
        assertEquals("java.lang.IllegalStateException", row.getJSONArray("causes").getJSONObject(0).getString("type"));
        assertEquals("fixtureMethod", row.getJSONArray("causes").getJSONObject(0).getJSONArray("frames").getJSONObject(0).getString("method"));
        assertFalse(row.toString().contains("CANARY")); assertFalse(row.toString().contains("sk-private"));
        assertFalse(row.toString().contains("/private/path"));
        assertSame(prior, Thread.getDefaultUncaughtExceptionHandler());
    }
    @Test public void cancellationAndWrappedCancellationStillDoNotCreateServiceRecords() throws Exception {
        LocalDiagnostics.recoverable(context, LocalDiagnostics.Origin.SERVICE, new CancellationException("CANARY"));
        LocalDiagnostics.recoverable(context, LocalDiagnostics.Origin.SERVICE,
            new IllegalStateException(new CancellationException("CANARY")));
        assertEquals(0, store.snapshot(null, 10, true).getJSONArray("records").length());
    }
    @Test public void localFailureWordingDoesNotInferProviderFromExceptionText() {
        String generic = AppLanguageRuntime.localizedContext(context).getString(R.string.agent_failure_generic);
        for (String text : new String[]{"HTTP 401 local fixture", "network local fixture", "Connector failed", "turn limit"}) {
            assertEquals(generic, AgentResultPresentation.failure(context, text));
            assertEquals(generic, AgentResultPresentation.failure(context, new IllegalStateException(text)));
        }
    }
    @Test public void typedProviderEvidenceKeepsStatusAndTransportClassification() {
        Context localized = AppLanguageRuntime.localizedContext(context);
        assertEquals(localized.getString(R.string.agent_failure_http, "401"), AgentResultPresentation.failure(context,
            new IllegalStateException("CANARY", new ProviderHttpException("CANARY", 401, 0, null))));
        assertEquals(localized.getString(R.string.agent_failure_network), AgentResultPresentation.failure(context,
            new ProviderTransportException("CANARY", new java.io.IOException("CANARY"))));
        assertEquals(localized.getString(R.string.agent_failure_generic), AgentResultPresentation.failure(context,
            new ProviderHttpException("CANARY", 999, 0, null)));
    }
    @Test public void hostileMessagesAndCyclicOrInaccessibleCausesStayBounded() {
        String generic = AppLanguageRuntime.localizedContext(context).getString(R.string.agent_failure_generic);
        RuntimeException hostile = new RuntimeException() {
            @Override public String getMessage() { throw new AssertionError("must not read message"); }
            @Override public synchronized Throwable getCause() { throw new AssertionError("unavailable"); }
        };
        assertEquals(generic, AgentResultPresentation.failure(context, hostile));
        RuntimeException a = new IllegalStateException(), b = new IllegalArgumentException();
        a.initCause(b); b.initCause(a);
        assertEquals(generic, AgentResultPresentation.failure(context, a));
    }

    @Test public void successfulDiagnosticReadsThenBlankModelResponseCompletesWithoutFailureRecord() throws Exception {
        java.util.concurrent.atomic.AtomicInteger turns = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        CoreTool diagnostics = new CoreTool() {
            @Override public ToolSpec declaration() {
                return new ToolSpec("app_diagnostics", "test", "Synthetic read-only diagnostics", "diagnostics",
                    ToolSpec.Status.IMPLEMENTED, java.util.Collections.emptyMap(), java.util.Collections.emptyList());
            }
            @Override public CoreToolResult execute(java.util.Map<String, Object> arguments, CancellationToken token) {
                reads.incrementAndGet();
                return CoreToolResult.success("{\"status\":\"ok\",\"records\":[]}");
            }
        };
        CoreAgentLoop loop = new CoreAgentLoop((history, prompt, declarations, token) -> {
            int turn = turns.incrementAndGet();
            if (turn > 2) return new ModelReply("", java.util.Collections.emptyList());
            java.util.Map<String, Object> arguments = new java.util.LinkedHashMap<>();
            arguments.put("action", turn == 1 ? "list" : "read");
            if (turn == 2) arguments.put("id", "12345678-1234-1234-1234-123456789abc");
            return new ModelReply("", java.util.Collections.singletonList(
                new ModelReply.Call("diagnostic-" + turn, "app_diagnostics", arguments)));
        }, new CoreToolRegistry(java.util.Collections.singletonList(diagnostics)), "", "synthetic-main-chat");
        CoreAgentLoop.Result result = loop.run("Explain the prior failure", java.util.Collections.emptyList(), CancellationToken.uncancellable(), null);
        assertEquals("COMPLETED", result.outcome);
        assertEquals("", result.text);
        assertEquals(2, reads.get()); assertEquals(3, turns.get());
        assertEquals(2, loop.transcriptSnapshot().stream()
            .filter(turn -> turn.kind == ConversationTurn.Kind.TOOL_RESULT).count());
        assertEquals(0, store.snapshot(null, 1, true).getJSONArray("records").length());
    }
}
