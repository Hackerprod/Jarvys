package com.jarvys.agent;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class CoreAgentLoopToolAdmissionTest {
    @Test public void unknownToolIsNeverStartedAndDoesNotSuppressTheRestOfItsBatch() {
        AtomicInteger effects = new AtomicInteger();
        CoreTool known = tool(spec("known", Collections.emptyMap(), Collections.emptyList()), effects);
        AtomicInteger modelCalls = new AtomicInteger();
        CoreAgentLoop loop = new CoreAgentLoop((transcript, prompt, declarations, token) -> {
            if (modelCalls.incrementAndGet() == 1) return new ModelReply("", Arrays.asList(
                    new ModelReply.Call("unknown-call", "missing_tool", Collections.emptyMap()),
                    new ModelReply.Call("known-call", "known", Collections.emptyMap())));
            return new ModelReply("The known tool ran.", Collections.emptyList());
        }, new CoreToolRegistry(Collections.singletonList(known)), "", "unknown-tool");
        List<ToolActivity> events = new ArrayList<>();
        CoreAgentLoop.ProgressListener listener = new CoreAgentLoop.ProgressListener() {
            @Override public void onProgress(String stage, String message) { }
            @Override public void onToolActivity(ToolActivity activity) { events.add(activity); }
        };

        CoreAgentLoop.Result result = loop.run("Run both", Collections.emptyList(), CancellationToken.uncancellable(), listener);

        assertEquals("COMPLETED", result.outcome);
        assertEquals(1, effects.get());
        assertEquals("NEVER_LAUNCHED", loop.checkpointSnapshot().toolLifecycle.get("unknown-call"));
        assertEquals("RESULT", loop.checkpointSnapshot().toolLifecycle.get("known-call"));
        assertEquals(1, events.stream().filter(event -> event.callId.equals("unknown-call")
                && event.stage.equals("tool_not_started")).count());
        assertFalse(events.stream().anyMatch(event -> event.callId.equals("unknown-call")
                && event.stage.equals("tool_call")));
        assertTrue(loop.transcriptSnapshot().stream().anyMatch(turn -> turn.toolCallId.equals("unknown-call")
                && turn.content.equals("Tool error: Tool is not available in this agent scope: missing_tool")));
    }

    @Test public void malformedArgumentsArePerCallAndTheBatchContinues() {
        AtomicInteger effects = new AtomicInteger();
        CoreTool known = tool(spec("write", Collections.singletonMap("path", "string"), Collections.singletonList("path")), effects);
        AtomicInteger modelCalls = new AtomicInteger();
        CoreAgentLoop loop = new CoreAgentLoop((transcript, prompt, declarations, token) -> {
            if (modelCalls.incrementAndGet() == 1) return new ModelReply("", Arrays.asList(
                    new ModelReply.Call("bad-json", "write", Collections.emptyMap(), "Malformed tool arguments JSON object"),
                    new ModelReply.Call("valid", "write", Collections.singletonMap("path", "note.txt"))));
            assertTrue(transcript.stream().anyMatch(turn -> turn.toolCallId.equals("bad-json")
                    && turn.content.equals("Tool error: Malformed tool arguments JSON object")));
            return new ModelReply("Completed the valid call.", Collections.emptyList());
        }, new CoreToolRegistry(Collections.singletonList(known)), "", "invalid-json");

        CoreAgentLoop.Result result = loop.run("Write a note", Collections.emptyList(), CancellationToken.uncancellable(), null);

        assertEquals("COMPLETED", result.outcome);
        assertEquals(1, effects.get());
        assertEquals("NEVER_LAUNCHED", loop.checkpointSnapshot().toolLifecycle.get("bad-json"));
        assertEquals("RESULT", loop.checkpointSnapshot().toolLifecycle.get("valid"));
    }

    @Test public void requiredTypesAndClosedPropertiesAreValidatedBeforeExecution() {
        AtomicInteger effects = new AtomicInteger();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Collections.singletonMap("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("path"));
        schema.put("additionalProperties", false);
        CoreTool known = tool(new ToolSpec("write", "test", "write", "test", ToolSpec.Status.IMPLEMENTED,
                Collections.singletonMap("path", "string"), Collections.singletonList("path"), schema), effects);
        AtomicInteger modelCalls = new AtomicInteger();
        CoreAgentLoop loop = new CoreAgentLoop((transcript, prompt, declarations, token) -> {
            if (modelCalls.incrementAndGet() == 1) return new ModelReply("", Arrays.asList(
                    new ModelReply.Call("missing", "write", Collections.emptyMap()),
                    new ModelReply.Call("wrong-type", "write", Collections.singletonMap("path", Collections.singletonMap("nested", 5))),
                    new ModelReply.Call("extra", "write", map("path", "note.txt", "extra", true)),
                    new ModelReply.Call("valid", "write", Collections.singletonMap("path", "note.txt"))));
            return new ModelReply("All validation failures were isolated.", Collections.emptyList());
        }, new CoreToolRegistry(Collections.singletonList(known)), "", "schema-validation");
        List<ToolActivity> events = new ArrayList<>();
        CoreAgentLoop.ProgressListener listener = new CoreAgentLoop.ProgressListener() {
            @Override public void onProgress(String stage, String message) { }
            @Override public void onToolActivity(ToolActivity activity) { events.add(activity); }
        };

        CoreAgentLoop.Result result = loop.run("Write a note", Collections.emptyList(), CancellationToken.uncancellable(), listener);

        assertEquals("COMPLETED", result.outcome);
        assertEquals(1, effects.get());
        assertEquals("NEVER_LAUNCHED", loop.checkpointSnapshot().toolLifecycle.get("missing"));
        assertEquals("NEVER_LAUNCHED", loop.checkpointSnapshot().toolLifecycle.get("wrong-type"));
        assertEquals("NEVER_LAUNCHED", loop.checkpointSnapshot().toolLifecycle.get("extra"));
        assertEquals("RESULT", loop.checkpointSnapshot().toolLifecycle.get("valid"));
        List<String> errors = new ArrayList<>();
        for (ConversationTurn turn : loop.transcriptSnapshot()) if (turn.kind == ConversationTurn.Kind.TOOL_RESULT
                && !turn.toolCallId.equals("valid")) errors.add(turn.content);
        assertTrue(errors.contains("Tool error: Missing required argument 'path'"));
        assertTrue(errors.contains("Tool error: Argument 'path' must be string"));
        assertTrue(errors.contains("Tool error: Unexpected argument 'extra'"));
        assertEquals(3, events.stream().filter(event -> event.stage.equals("tool_not_started")).count());
    }

    @Test public void parallelToolInheritsAndRestoresCallerScopes() {
        AtomicBoolean privateScopeInTool = new AtomicBoolean();
        AtomicInteger nativeAdmissions = new AtomicInteger();
        AtomicReference<Thread> scopedWorker = new AtomicReference<>();
        CoreTool scoped = new CoreTool() {
            private final ToolSpec declaration = spec("scoped", Collections.emptyMap(), Collections.emptyList());
            @Override public ToolSpec declaration() { return declaration; }
            @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
                scopedWorker.set(Thread.currentThread());
                privateScopeInTool.set(AgentErrorReporter.attachmentReportsSuppressed());
                NativeEffectDispatchScope.beforeDispatch();
                return CoreToolResult.success("done");
            }
        };
        AtomicInteger modelCalls = new AtomicInteger();
        CoreAgentLoop loop = new CoreAgentLoop((transcript, prompt, declarations, token) ->
                modelCalls.incrementAndGet() == 1
                        ? new ModelReply("", Collections.singletonList(
                                new ModelReply.Call("scoped-call", "scoped", Collections.emptyMap())))
                        : new ModelReply("Done", Collections.emptyList()),
                new CoreToolRegistry(Collections.singletonList(scoped)), "", "tool-scopes");

        try (AgentErrorReporter.AttachmentScope ignored = AgentErrorReporter.suppressForPrivateContent();
             NativeEffectDispatchScope dispatch = NativeEffectDispatchScope.enter(nativeAdmissions::incrementAndGet)) {
            CoreAgentLoop.Result result = loop.run("Run scoped tool", Collections.emptyList(),
                    CancellationToken.uncancellable(), null);
            assertEquals("COMPLETED", result.outcome);
            assertTrue("the parallel worker inherits private-content suppression", privateScopeInTool.get());
            assertEquals("native dispatch admission is preserved on the worker", 1, nativeAdmissions.get());
            assertTrue("the caller keeps its scope until its lexical close", AgentErrorReporter.attachmentReportsSuppressed());
        }
        assertFalse("closing the caller scope restores its thread", AgentErrorReporter.attachmentReportsSuppressed());

        AtomicBoolean leakedSuppression = new AtomicBoolean(true);
        AtomicReference<Thread> unscopedWorker = new AtomicReference<>();
        CoreTool unscoped = new CoreTool() {
            private final ToolSpec declaration = spec("unscoped", Collections.emptyMap(), Collections.emptyList());
            @Override public ToolSpec declaration() { return declaration; }
            @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
                unscopedWorker.set(Thread.currentThread());
                leakedSuppression.set(AgentErrorReporter.attachmentReportsSuppressed());
                return CoreToolResult.success("done");
            }
        };
        AtomicInteger unscopedModelCalls = new AtomicInteger();
        CoreAgentLoop unscopedLoop = new CoreAgentLoop((transcript, prompt, declarations, token) ->
                unscopedModelCalls.incrementAndGet() == 1
                        ? new ModelReply("", Collections.singletonList(
                                new ModelReply.Call("unscoped-call", "unscoped", Collections.emptyMap())))
                        : new ModelReply("Done", Collections.emptyList()),
                new CoreToolRegistry(Collections.singletonList(unscoped)), "", "tool-scopes-restored");

        assertEquals("COMPLETED", unscopedLoop.run("Run unscoped tool", Collections.emptyList(),
                CancellationToken.uncancellable(), null).outcome);
        assertSame("the follow-up call reuses the parallel executor worker", scopedWorker.get(), unscopedWorker.get());
        assertFalse("the previous run's private-content scope is restored before worker reuse", leakedSuppression.get());
    }

    @Test public void capturedPrivacyScopeRestoresAWorkersPriorValue() {
        AgentErrorReporter.ScopeSnapshot unscopedSnapshot = AgentErrorReporter.captureScope();
        assertFalse(AgentErrorReporter.attachmentReportsSuppressed());
        try (AgentErrorReporter.AttachmentScope existingWorkerScope = AgentErrorReporter.suppressForAttachments()) {
            assertTrue(AgentErrorReporter.attachmentReportsSuppressed());
            try (AgentErrorReporter.Scope ignored = unscopedSnapshot.install()) {
                assertFalse("the captured unscoped context replaces a stale worker value",
                        AgentErrorReporter.attachmentReportsSuppressed());
            }
            assertTrue("closing the captured scope restores the worker's previous value",
                    AgentErrorReporter.attachmentReportsSuppressed());
        }
        assertFalse(AgentErrorReporter.attachmentReportsSuppressed());
    }

    private static ToolSpec spec(String name, Map<String, String> properties, List<String> required) {
        return new ToolSpec(name, "test", name, "test", ToolSpec.Status.IMPLEMENTED, properties, required);
    }

    private static CoreTool tool(ToolSpec spec, AtomicInteger effects) {
        return new CoreTool() {
            @Override public ToolSpec declaration() { return spec; }
            @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
                effects.incrementAndGet();
                return CoreToolResult.success("done");
            }
        };
    }

    private static Map<String, Object> map(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key1, value1);
        result.put(key2, value2);
        return result;
    }
}
