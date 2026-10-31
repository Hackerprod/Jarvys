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
    @Test public void transportsTheSameInvocationAndPreservesCompletionMetadata() {
        java.util.concurrent.atomic.AtomicInteger persisted = new java.util.concurrent.atomic.AtomicInteger();
        EffectInvocation invocation = new EffectInvocation(admission -> persisted.incrementAndGet());
        CoreTool delegate = new CoreTool() {
            @Override public ToolSpec declaration() {
                return new ToolSpec("native_write", "test", "Test", "test", ToolSpec.Status.IMPLEMENTED,
                        Collections.emptyMap(), Collections.emptyList());
            }
            @Override public EffectClass effectClass() { return EffectClass.WRITE; }
            @Override public CoreToolResult execute(Map<String, Object> args, CancellationToken token) {
                throw new AssertionError("Explicit context must not be dropped");
            }
            @Override public CoreToolResult execute(Map<String, Object> args, CancellationToken token,
                    ProgressListener progress, EffectInvocation received) {
                org.junit.Assert.assertSame(invocation, received);
                received.admit(new EffectInvocation.Admission(EffectInvocation.Authorization.ALLOW,
                        "test", "native_write", null, "test"));
                assertEquals(1, persisted.get());
                return CoreToolResult.preview("completed", "preview-id")
                        .withEffectReceipt(received.receipt(EffectInvocation.Outcome.OK));
            }
        };
        CoreTool wrapped = new CoreAgentRuntime.ScheduledTaskBoundTool(delegate, true, false, null);
        CoreToolResult result = wrapped.execute(Collections.emptyMap(), CancellationToken.cancellable(), null, invocation);
        assertEquals("preview-id", result.previewId);
        assertEquals(EffectInvocation.Outcome.OK, invocation.resolve(result, true).outcome);
        assertEquals(1, persisted.get());
    }

    @Test public void deferredTaskWriteHasTypedPendingDecisionWithoutAdmission() {
        CoreTool delegate = new CoreTool() {
            @Override public ToolSpec declaration() {
                return new ToolSpec("native_write", "test", "Test", "test", ToolSpec.Status.IMPLEMENTED,
                        Collections.emptyMap(), Collections.emptyList());
            }
            @Override public UnattendedAccess unattendedAccess() { return UnattendedAccess.WRITE; }
            @Override public CoreToolResult execute(Map<String, Object> args, CancellationToken token) {
                throw new AssertionError("Pending writes must not execute");
            }
        };
        EffectInvocation invocation = new EffectInvocation(admission -> { throw new AssertionError("No dispatch"); });
        CoreTool wrapped = new CoreAgentRuntime.ScheduledTaskBoundTool(delegate, false, false,
                (name, args, token) -> CoreToolResult.success("localized queue acknowledgement"));
        CoreToolResult result = wrapped.execute(Collections.emptyMap(), CancellationToken.cancellable(), null, invocation);
        assertEquals(EffectInvocation.Outcome.NOT_EXECUTED, result.effectReceipt.outcome);
        assertEquals(EffectInvocation.Authorization.ASK_PENDING, result.effectReceipt.admission.authorization);
        org.junit.Assert.assertFalse(invocation.admitted());
    }

}
