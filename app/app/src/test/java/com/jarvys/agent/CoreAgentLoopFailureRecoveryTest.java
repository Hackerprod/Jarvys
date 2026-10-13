package com.jarvys.agent;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
        assertEquals(3, result.turns);
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
        assertEquals(1, result.turns);
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

    @Test public void repeatedUnknownToolTerminatesAsNoProgressAfterThreeModelTurns() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        CoreAgentLoop loop = loop(tool((arguments, token) -> {
            effects.incrementAndGet();
            return CoreToolResult.success("unexpected invocation");
        }), (transcript, prompt, declarations, token) -> {
            int turn = modelCalls.incrementAndGet();
            return reply(new ModelReply.Call("unknown-call-" + turn, "future_tool", Collections.emptyMap()));
        });

        CoreAgentLoop.Result result = loop.run("Do the requested work", Collections.emptyList(), CancellationToken.uncancellable(), null);

        assertEquals("PARTIAL", result.outcome);
        assertEquals(CoreAgentLoop.InterruptionReason.NO_PROGRESS, result.interruptionReason);
        assertEquals(3, result.turns);
        assertEquals(3, modelCalls.get());
        assertEquals(0, effects.get());
    }

    @Test public void invocationReceivesArgumentsCoercedByTheDeclaredSchema() {
        AtomicReference<Object> invokedPath = new AtomicReference<>();
        AtomicInteger modelCalls = new AtomicInteger();
        CoreTool tool = tool((arguments, token) -> {
            invokedPath.set(arguments.get("path"));
            return CoreToolResult.success("listed");
        });
        CoreAgentLoop loop = loop(tool, (transcript, prompt, declarations, token) ->
                modelCalls.incrementAndGet() == 1
                        ? reply(new ModelReply.Call("coerced-call", "ls", Collections.singletonMap("path", 42)))
                        : new ModelReply("Done", Collections.emptyList()));

        CoreAgentLoop.Result result = loop.run("List files", Collections.emptyList(), CancellationToken.uncancellable(), null);

        assertEquals("COMPLETED", result.outcome);
        assertEquals("42", invokedPath.get());
    }

    @Test public void noParameterToolExecutesWithEmptyArguments() {
        AtomicInteger effects = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        CoreTool noArguments = new CoreTool() {
            @Override public ToolSpec declaration() {
                return new ToolSpec("no_args", "test", "test tool", "test", ToolSpec.Status.IMPLEMENTED,
                        Collections.emptyMap(), Collections.emptyList());
            }
            @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
                assertTrue(arguments.isEmpty());
                effects.incrementAndGet();
                return CoreToolResult.success("executed");
            }
        };
        CoreAgentLoop loop = new CoreAgentLoop((transcript, prompt, declarations, token) ->
                modelCalls.incrementAndGet() == 1
                        ? reply(new ModelReply.Call("empty-arguments-call", "no_args", Collections.emptyMap()))
                        : new ModelReply("Done", Collections.emptyList()),
                new CoreToolRegistry(Collections.singletonList(noArguments)), "", "no-args-session");

        CoreAgentLoop.Result result = loop.run("Run the tool", Collections.emptyList(), CancellationToken.uncancellable(), null);

        assertEquals("COMPLETED", result.outcome);
        assertEquals(1, effects.get());
    }

    @Test public void invalidCallRestoredFromCheckpointIsNotExecutedAgain() {
        AtomicInteger effects = new AtomicInteger();
        AtomicInteger firstModelCalls = new AtomicInteger();
        CoreTool tool = tool((arguments, token) -> {
            effects.incrementAndGet();
            return CoreToolResult.success("unexpected invocation");
        });
        CoreAgentLoop first = loop(tool, (transcript, prompt, declarations, token) ->
                firstModelCalls.incrementAndGet() == 1
                        ? reply(new ModelReply.Call("invalid-call", "ls",
                                Collections.singletonMap("path", Collections.singletonList(7))))
                        : new ModelReply("The invalid call was reported.", Collections.emptyList()));

        CoreAgentLoop.Result firstResult = first.run("List project files", Collections.emptyList(), CancellationToken.uncancellable(), null);
        CoreAgentLoop.Checkpoint checkpoint = first.checkpointSnapshot().reconciled();
        assertEquals("COMPLETED", firstResult.outcome);
        assertEquals("NEVER_LAUNCHED", checkpoint.toolLifecycle.get("invalid-call"));

        AtomicInteger resumedModelCalls = new AtomicInteger();
        CoreAgentLoop resumed = loop(tool, (transcript, prompt, declarations, token) -> {
            resumedModelCalls.incrementAndGet();
            assertTrue(transcript.stream().anyMatch(turn -> turn.kind == ConversationTurn.Kind.TOOL_RESULT
                    && "invalid-call".equals(turn.toolCallId)));
            return new ModelReply("Continued safely.", Collections.emptyList());
        });
        resumed.restoreCheckpoint(checkpoint);

        CoreAgentLoop.Result resumedResult = resumed.run("", checkpoint.transcript, CancellationToken.uncancellable(), null);

        assertEquals("COMPLETED", resumedResult.outcome);
        assertEquals(1, resumedModelCalls.get());
        assertEquals(0, effects.get());
    }

    @Test public void crewWithEmptyTranscriptAndNoRealMessagesDoesNotCallTheModel() {
        AtomicInteger modelCalls = new AtomicInteger();
        CoreAgentLoop.TurnContextProvider noMessages = new CoreAgentLoop.TurnContextProvider() {
            @Override public String takeUntrustedContext() { return ""; }
        };
        CoreAgentLoop loop = new CoreAgentLoop((transcript, prompt, declarations, token) -> {
            modelCalls.incrementAndGet();
            return new ModelReply("unexpected", Collections.emptyList());
        }, new CoreToolRegistry(Collections.emptyList()), "", "crew-empty", CorePromptBudget.standard(), null,
                CoreAgentLoop.ToolResultBudget.UNBOUNDED, noMessages, null);

        CoreAgentLoop.Result result = loop.run("", Collections.emptyList(), CancellationToken.uncancellable(), null);

        assertEquals("COMPLETED", result.outcome);
        assertEquals(0, result.turns);
        assertEquals(0, modelCalls.get());
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
