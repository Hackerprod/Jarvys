package com.jarvys.agent;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;

/** The existing complete-output contract rejects a second large result instead of slicing JSON. */
public class AppDiagnosticsBudgetTest {
    @Test public void boundedDiagnosticOutputsStayWholeOrProduceExplicitBudgetError() throws Exception {
        String json = new JSONObject().put("status", "ok").put("synthetic_metadata", "x".repeat(16000)).toString();
        AtomicInteger turns = new AtomicInteger(), reads = new AtomicInteger();
        List<ToolActivity> activities = new ArrayList<>();
        CoreTool tool = new CoreTool() {
            @Override public ToolSpec declaration() {
                return new ToolSpec("app_diagnostics", "test", "synthetic diagnostics", "diagnostics",
                    ToolSpec.Status.IMPLEMENTED, Collections.emptyMap(), Collections.emptyList());
            }
            @Override public CoreToolResult execute(Map<String,Object> arguments, CancellationToken token) {
                reads.incrementAndGet();
                return CoreToolResult.complete(json);
            }
        };
        CoreAgentLoop loop = new CoreAgentLoop((history, prompt, declarations, token) -> {
            if (turns.incrementAndGet() == 1) return new ModelReply("", Arrays.asList(
                new ModelReply.Call("first", "app_diagnostics", Collections.singletonMap("id", "one")),
                new ModelReply.Call("second", "app_diagnostics", Collections.singletonMap("id", "two"))));
            return new ModelReply("The second diagnostic must be requested by itself.", Collections.emptyList());
        }, new CoreToolRegistry(Collections.singletonList(tool)), "", "synthetic-diagnostics");
        CoreAgentLoop.Result result = loop.run("Inspect diagnostics", Collections.emptyList(), CancellationToken.uncancellable(),
            new CoreAgentLoop.ProgressListener() {
                @Override public void onProgress(String stage, String message) { }
                @Override public void onToolActivity(ToolActivity activity) { activities.add(activity); }
            });
        assertEquals("COMPLETED", result.outcome);
        assertEquals(2, turns.get()); assertEquals(2, reads.get());
        List<ConversationTurn> outputs = new ArrayList<>();
        for (ConversationTurn turn : loop.transcriptSnapshot())
            if (turn.kind == ConversationTurn.Kind.TOOL_RESULT) outputs.add(turn);
        assertEquals(2, outputs.size());
        assertEquals(json, outputs.get(0).content);
        assertEquals("ok", new JSONObject(outputs.get(0).content).getString("status"));
        assertTrue(outputs.get(1).content.startsWith("Tool error: Complete tool output exceeds"));
        assertTrue(outputs.get(1).content.contains("request this large result by itself"));
        assertFalse(outputs.get(1).content.contains("synthetic_metadata"));
        assertFalse(outputs.get(1).content.contains("truncated by per-turn budget"));
        assertTrue(activities.stream().anyMatch(a -> a.callId.equals("first") && a.stage.equals("tool_result")));
        assertTrue(activities.stream().anyMatch(a -> a.callId.equals("second") && a.stage.equals("tool_error")));
        assertFalse(activities.stream().anyMatch(a -> a.callId.equals("second") && a.stage.equals("tool_result")));
    }
}
