package com.jarvys.agent;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class CoreAgentLoopFailureRecoveryTest {
    @Test public void thirdIdenticalFailureTerminatesWithoutAnotherModelCall() {
        AtomicInteger effects = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        CoreTool tool = tool((arguments, token) -> {
            effects.incrementAndGet();
            return CoreToolResult.failure("permission denied");
        });
        CoreAgentLoop loop = loop(tool, (transcript, prompt, declarations, token) -> {
            int call = modelCalls.incrementAndGet();
            return reply(call("call-" + call, "/project"));
        });

        CoreAgentLoop.Result result = loop.run("List project files", Collections.emptyList(), CancellationToken.uncancellable(), null);

        assertEquals("PARTIAL", result.outcome);
        assertEquals(CoreAgentLoop.InterruptionReason.NO_PROGRESS, result.interruptionReason);
        assertEquals(3, effects.get());
        assertEquals(3, modelCalls.get());
        assertEquals(1, result.turns);
    }

    @Test public void differentFailureResultsDoNotAccumulateAsOneRepeatedError() {
        AtomicInteger effects = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        CoreTool tool = tool((arguments, token) -> CoreToolResult.failure("failure " + effects.incrementAndGet()));
        CoreAgentLoop loop = loop(tool, (transcript, prompt, declarations, token) -> {
            int call = modelCalls.incrementAndGet();
            return call <= 3 ? reply(call("call-" + call, "/project"))
                    : new ModelReply("The observed failures differed.", Collections.emptyList());
        });

        CoreAgentLoop.Result result = loop.run("List project files", Collections.emptyList(), CancellationToken.uncancellable(), null);

        assertEquals("COMPLETED", result.outcome);
        assertEquals(3, effects.get());
        assertEquals(4, modelCalls.get());
    }

    @Test public void thirdIdenticalErrorInBatchBlocksTheRemainingCallsAndEmitsNotStartedEvents() {
        AtomicInteger effects = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        CoreTool tool = tool((arguments, token) -> {
            effects.incrementAndGet();
            return CoreToolResult.failure("permission denied");
        });
        ModelReply.Call[] calls = new ModelReply.Call[5];
        for (int i = 0; i < calls.length; i++) calls[i] = call("batch-" + (i + 1), "/project");
        CoreAgentLoop loop = loop(tool, (transcript, prompt, declarations, token) -> {
            modelCalls.incrementAndGet();
            return new ModelReply("", Arrays.asList(calls));
        });
        java.util.List<ToolActivity> events = new java.util.ArrayList<>();
        CoreAgentLoop.ProgressListener listener = new CoreAgentLoop.ProgressListener() {
            @Override public void onProgress(String stage, String message) { }
            @Override public void onToolActivity(ToolActivity activity) { events.add(activity); }
        };

        CoreAgentLoop.Result result = loop.run("List project files", Collections.emptyList(), CancellationToken.uncancellable(), listener);

        assertEquals("PARTIAL", result.outcome);
        assertEquals(3, effects.get());
        assertEquals(1, modelCalls.get());
        CoreAgentLoop.Checkpoint checkpoint = loop.checkpointSnapshot().reconciled();
        assertEquals("NEVER_LAUNCHED", checkpoint.toolLifecycle.get("batch-4"));
        assertEquals("NEVER_LAUNCHED", checkpoint.toolLifecycle.get("batch-5"));
        assertEquals(5, checkpoint.transcript.stream().filter(turn -> turn.kind == ConversationTurn.Kind.TOOL_RESULT).count());
        assertEquals(2, events.stream().filter(event -> event.stage.equals("tool_not_started")).count());
    }

    @Test public void cancellationAfterAnUncertainEffectNeverExecutesTheNextCall() {
        AtomicInteger effects = new AtomicInteger();
        CancellationToken token = CancellationToken.cancellable();
        CoreTool tool = tool((arguments, current) -> {
            effects.incrementAndGet();
            current.cancel();
            throw new CancellationException("Result was not recorded");
        });
        CoreAgentLoop loop = loop(tool, (transcript, prompt, declarations, current) ->
                new ModelReply("", Arrays.asList(call("uncertain", "/project"), call("later", "/project"))));
        assertThrows(CancellationException.class, () -> loop.run("Perform work", Collections.emptyList(), token, null));
        assertEquals(1, effects.get());
        CoreAgentLoop.Checkpoint checkpoint = loop.checkpointSnapshot().reconciled();
        assertEquals("INTERRUPTED_UNCERTAIN", checkpoint.toolLifecycle.get("uncertain"));
        assertEquals("NEVER_LAUNCHED", checkpoint.toolLifecycle.get("later"));
    }

    private interface Effect {
        CoreToolResult execute(Map<String, Object> arguments, CancellationToken token);
    }

    private static CoreTool tool(Effect effect) {
        return new CoreTool() {
            @Override public ToolSpec declaration() {
                return new ToolSpec("ls", "test", "test tool", "test", ToolSpec.Status.IMPLEMENTED,
                        Collections.singletonMap("path", "string"), Collections.singletonList("path"));
            }
            @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
                return effect.execute(arguments, token);
            }
        };
    }

    private static CoreAgentLoop loop(CoreTool tool, CoreAgentLoop.Model model) {
        return new CoreAgentLoop(model, new CoreToolRegistry(Collections.singletonList(tool)), "", "test");
    }

    private static ModelReply reply(ModelReply.Call call) {
        return new ModelReply("", Collections.singletonList(call));
    }

    private static ModelReply.Call call(String id, String path) {
        return new ModelReply.Call(id, "ls", Collections.singletonMap("path", path));
    }
}
