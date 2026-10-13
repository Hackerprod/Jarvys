package com.jarvys.agent;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class CoreAgentLoopBatchSemanticsTest {
    @Test public void steeringArrivingDuringModelResponseWaitsForModeCheckpoint() {
        assertSteeringCheckpoint(CoreTool.ExecutionMode.SEQUENTIAL, 1);
        assertSteeringCheckpoint(CoreTool.ExecutionMode.PARALLEL, 2);
    }

    @Test public void unknownCallPreventsTerminalToolFromFinishingTheBatch() {
        AtomicInteger modelCalls = new AtomicInteger();
        CoreTool finish = tool("finish", CoreTool.ExecutionMode.PARALLEL,
                (arguments, token) -> CoreToolResult.finish("Terminal tool result"));
        CoreAgentLoop loop = new CoreAgentLoop((transcript, prompt, declarations, token) -> {
            if (modelCalls.incrementAndGet() == 1) return new ModelReply("", Arrays.asList(
                    new ModelReply.Call("finish-call", "finish", Collections.emptyMap()),
                    new ModelReply.Call("unknown-call", "missing_tool", Collections.emptyMap())));
            return new ModelReply("The remaining call failed admission, so I am continuing.", Collections.emptyList());
        }, new CoreToolRegistry(Collections.singletonList(finish)), "", "terminal-admission");

        CoreAgentLoop.Result result = loop.run("Finish the task", Collections.emptyList(),
                CancellationToken.uncancellable(), null);

        assertEquals("COMPLETED", result.outcome);
        assertEquals("The remaining call failed admission, so I am continuing.", result.text);
        assertEquals(2, modelCalls.get());
        assertTrue(loop.transcriptSnapshot().stream().anyMatch(turn -> turn.kind == ConversationTurn.Kind.TOOL_RESULT
                && "unknown-call".equals(turn.toolCallId) && turn.content.startsWith("Tool error:")));
    }

    @Test public void parallelToolCancellationIsOnlyThatCallsFailure() {
        AtomicInteger cancellations = new AtomicInteger();
        AtomicInteger otherEffects = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicBoolean otherInterrupted = new AtomicBoolean();
        CountDownLatch otherStarted = new CountDownLatch(1);
        CountDownLatch releaseOther = new CountDownLatch(1);
        CoreTool cancel = tool("cancel", CoreTool.ExecutionMode.PARALLEL, (arguments, token) -> {
            cancellations.incrementAndGet();
            await(otherStarted);
            throw new CancellationException("tool-local cancellation") {
                @Override public String getMessage() {
                    releaseOther.countDown();
                    return super.getMessage();
                }
            };
        });
        CoreTool other = tool("other", CoreTool.ExecutionMode.PARALLEL, (arguments, token) -> {
            otherEffects.incrementAndGet();
            otherStarted.countDown();
            try {
                releaseOther.await();
            } catch (InterruptedException interrupted) {
                otherInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return CoreToolResult.success("other work completed");
        });
        CoreAgentLoop loop = new CoreAgentLoop((transcript, prompt, declarations, token) -> {
            if (modelCalls.incrementAndGet() == 1) return new ModelReply("", Arrays.asList(
                    new ModelReply.Call("cancel-call", "cancel", Collections.emptyMap()),
                    new ModelReply.Call("other-call", "other", Collections.emptyMap())));
            return new ModelReply("Both calls were accounted for.", Collections.emptyList());
        }, new CoreToolRegistry(Arrays.asList(cancel, other)), "", "tool-local-cancel");

        CoreAgentLoop.Result result = loop.run("Run both calls", Collections.emptyList(),
                CancellationToken.uncancellable(), null);

        assertEquals("COMPLETED", result.outcome);
        assertEquals(2, modelCalls.get());
        assertEquals(1, cancellations.get());
        assertEquals(1, otherEffects.get());
        assertFalse("a sibling tool is not interrupted by a tool-local cancellation", otherInterrupted.get());
        assertTrue(loop.transcriptSnapshot().stream().anyMatch(turn -> turn.kind == ConversationTurn.Kind.TOOL_RESULT
                && "cancel-call".equals(turn.toolCallId) && turn.content.contains("tool-local cancellation")));
        assertTrue(loop.transcriptSnapshot().stream().anyMatch(turn -> turn.kind == ConversationTurn.Kind.TOOL_RESULT
                && "other-call".equals(turn.toolCallId) && turn.content.equals("other work completed")));
    }

    private static void assertSteeringCheckpoint(CoreTool.ExecutionMode mode, int expectedExecutions) {
        AtomicBoolean steeringPending = new AtomicBoolean();
        AtomicInteger effects = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        CoreAgentLoop.TurnContextProvider inbox = new CoreAgentLoop.TurnContextProvider() {
            @Override public List<CoreAgentLoop.IncomingMessage> takeDurableMessages() {
                return steeringPending.compareAndSet(true, false)
                        ? Collections.singletonList(CoreAgentLoop.IncomingMessage.mainChat(
                                "steering-id", "Steering arrived during the model response."))
                        : Collections.emptyList();
            }
        };
        CoreTool effect = tool("effect", mode, (arguments, token) -> {
            effects.incrementAndGet();
            return CoreToolResult.success("effect completed");
        });
        CoreAgentLoop.Model model = (transcript, prompt, declarations, token) -> {
            if (modelCalls.incrementAndGet() == 1) {
                steeringPending.set(true);
                return new ModelReply("", Arrays.asList(
                        new ModelReply.Call("first-call", "effect", Collections.emptyMap()),
                        new ModelReply.Call("second-call", "effect", Collections.emptyMap())));
            }
            assertTrue("steering reaches the next model checkpoint", transcript.stream()
                    .anyMatch(turn -> turn.kind == ConversationTurn.Kind.MESSAGE
                            && "Steering arrived during the model response.".equals(turn.content)));
            return new ModelReply("Continued after steering.", Collections.emptyList());
        };
        CoreAgentLoop loop = new CoreAgentLoop(model, new CoreToolRegistry(Collections.singletonList(effect)), "",
                "steering-" + mode, CorePromptBudget.standard(), null,
                CoreAgentLoop.ToolResultBudget.UNBOUNDED, inbox, null);

        CoreAgentLoop.Result result = loop.run("Perform the requested work", Collections.emptyList(),
                CancellationToken.uncancellable(), null);

        assertEquals("COMPLETED", result.outcome);
        assertEquals(expectedExecutions, effects.get());
        assertEquals(2, modelCalls.get());
    }

    private interface Effect {
        CoreToolResult execute(Map<String, Object> arguments, CancellationToken token);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static CoreTool tool(String name, CoreTool.ExecutionMode mode, Effect effect) {
        return new CoreTool() {
            private final ToolSpec declaration = new ToolSpec(name, "test", name, "test",
                    ToolSpec.Status.IMPLEMENTED, Collections.emptyMap(), Collections.emptyList());
            @Override public ToolSpec declaration() { return declaration; }
            @Override public ExecutionMode executionMode() { return mode; }
            @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
                return effect.execute(arguments, token);
            }
        };
    }
}
