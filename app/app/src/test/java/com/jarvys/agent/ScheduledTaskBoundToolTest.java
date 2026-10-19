package com.jarvys.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Map;
import org.junit.Test;

public class ScheduledTaskBoundToolTest {
    @Test public void preservesResponseAdmissionAndVisibleResultMetadata() {
        CoreTool delegate = new CoreTool() {
            private final ToolSpec declaration = new ToolSpec("test", "test", "Test", "test",
                    ToolSpec.Status.IMPLEMENTED, Collections.emptyMap(), Collections.emptyList());

            @Override public ToolSpec declaration() { return declaration; }
            @Override public ResponseCallPolicy responseCallPolicy() { return ResponseCallPolicy.ONE_PER_RESPONSE; }
            @Override public String additionalCallNotStartedMessage() { return "Ask again in the next response."; }
            @Override public String preflightErrorMessage(Map<String, Object> arguments, String reason) {
                return "Scoped preflight: " + reason;
            }
            @Override public String completeResultIdentity(Map<String, Object> arguments) {
                return arguments == null ? null : String.valueOf(arguments.get("reference"));
            }
            @Override public boolean isCompleteResult(Map<String, Object> arguments, String content) {
                return "complete result".equals(content);
            }
            @Override public String completeResultAlreadyVisibleMessage() { return "Already visible."; }
            @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
                return CoreToolResult.success("complete result");
            }
        };
        CoreTool wrapped = new CoreAgentRuntime.ScheduledTaskBoundTool(delegate, false, false, null);

        assertEquals(CoreTool.ResponseCallPolicy.ONE_PER_RESPONSE, wrapped.responseCallPolicy());
        assertEquals("Ask again in the next response.", wrapped.additionalCallNotStartedMessage());
        assertEquals("Scoped preflight: invalid", wrapped.preflightErrorMessage(Collections.emptyMap(), "invalid"));
        assertEquals("database", wrapped.completeResultIdentity(Collections.singletonMap("reference", "database")));
        assertTrue(wrapped.isCompleteResult(Collections.emptyMap(), "complete result"));
        assertEquals("Already visible.", wrapped.completeResultAlreadyVisibleMessage());
    }
}
