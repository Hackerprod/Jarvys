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
import java.util.concurrent.atomic.AtomicReference;
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

    @Test public void sequentialAdmissionResultsStayInProviderCallOrder() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        CoreTool sequential = tool("sequential", CoreTool.ExecutionMode.SEQUENTIAL, (arguments, token) -> {
            effects.incrementAndGet();
            return CoreToolResult.success("executed");
        });
        CoreAgentLoop loop = new CoreAgentLoop((transcript, prompt, declarations, token) ->
                modelCalls.incrementAndGet() == 1
                        ? new ModelReply("", Arrays.asList(
                                new ModelReply.Call("invalid-first", "missing_tool", Collections.emptyMap()),
                                new ModelReply.Call("valid-second", "sequential", Collections.emptyMap())))
                        : new ModelReply("Finished.", Collections.emptyList()),
                new CoreToolRegistry(Collections.singletonList(sequential)), "", "sequential-call-order");

        CoreAgentLoop.Result result = loop.run("Run sequential calls", Collections.emptyList(),
                CancellationToken.uncancellable(), null);

        List<String> resultIds = new java.util.ArrayList<>();
        for (ConversationTurn turn : loop.transcriptSnapshot())
            if (turn.kind == ConversationTurn.Kind.TOOL_RESULT) resultIds.add(turn.toolCallId);
        assertEquals("COMPLETED", result.outcome);
        assertEquals(1, effects.get());
        assertEquals(Arrays.asList("invalid-first", "valid-second"), resultIds);
    }

    @Test public void sequentialToolLocalCancellationIsOnlyThatCallsFailure() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger laterEffects = new AtomicInteger();
        CoreTool cancel = tool("cancel", CoreTool.ExecutionMode.SEQUENTIAL, (arguments, token) -> {
            throw new CancellationException("tool-local cancellation");
        });
        CoreTool later = tool("later", CoreTool.ExecutionMode.SEQUENTIAL, (arguments, token) -> {
            laterEffects.incrementAndGet();
            return CoreToolResult.success("later call completed");
        });
        CoreAgentLoop loop = new CoreAgentLoop((transcript, prompt, declarations, token) ->
                modelCalls.incrementAndGet() == 1
                        ? new ModelReply("", Arrays.asList(
                                new ModelReply.Call("cancel-call", "cancel", Collections.emptyMap()),
                                new ModelReply.Call("later-call", "later", Collections.emptyMap())))
                        : new ModelReply("Continued after the tool error.", Collections.emptyList()),
                new CoreToolRegistry(Arrays.asList(cancel, later)), "", "sequential-tool-local-cancel");

        CoreAgentLoop.Result result = loop.run("Run both calls", Collections.emptyList(),
                CancellationToken.uncancellable(), null);

        assertEquals("COMPLETED", result.outcome);
        assertEquals(1, laterEffects.get());
        assertTrue(loop.transcriptSnapshot().stream().anyMatch(turn -> turn.kind == ConversationTurn.Kind.TOOL_RESULT
                && "cancel-call".equals(turn.toolCallId) && turn.content.contains("tool-local cancellation")));
    }

    @Test public void cancellationAfterParallelToolCallAnnouncementInterruptsClaimedCallAndLeavesTailUnlaunched() {
        AtomicInteger announcedEffects = new AtomicInteger();
        AtomicInteger pendingEffects = new AtomicInteger();
        CoreTool announcedTool = tool("announced", CoreTool.ExecutionMode.PARALLEL, (arguments, token) -> {
            announcedEffects.incrementAndGet();
            return CoreToolResult.success("announced effect completed");
        });
        CoreTool pendingTool = tool("pending", CoreTool.ExecutionMode.PARALLEL, (arguments, token) -> {
            pendingEffects.incrementAndGet();
            return CoreToolResult.success("pending effect completed");
        });
        CoreAgentLoop loop = new CoreAgentLoop((transcript, prompt, declarations, token) ->
                new ModelReply("", Arrays.asList(
                        new ModelReply.Call("announced-call", "announced", Collections.emptyMap()),
                        new ModelReply.Call("pending-call", "pending", Collections.emptyMap()))),
                new CoreToolRegistry(Arrays.asList(announcedTool, pendingTool)), "", "announced-cancellation");
        CancellationToken token = CancellationToken.cancellable();
        List<ToolActivity> events = new java.util.ArrayList<>();
        loop.setCheckpointListener(checkpoint -> { });
        CoreAgentLoop.Result result = loop.run("Run the call", Collections.emptyList(), token,
                new CoreAgentLoop.ProgressListener() {
                    @Override public void onProgress(String stage, String message) { }
                    @Override public void onToolActivity(ToolActivity activity) {
                        events.add(activity);
                        if ("tool_call".equals(activity.stage) && "announced-call".equals(activity.callId))
                            token.cancelForTimeout();
                    }
                });

        assertEquals("PARTIAL", result.outcome);
        assertEquals(0, announcedEffects.get());
        assertEquals(0, pendingEffects.get());
        CoreAgentLoop.Checkpoint checkpoint = loop.checkpointSnapshot().reconciled();
        assertEquals("INTERRUPTED_UNCERTAIN", checkpoint.toolLifecycle.get("announced-call"));
        assertEquals("NEVER_LAUNCHED", checkpoint.toolLifecycle.get("pending-call"));
        assertEquals(1L, events.stream().filter(event -> "announced-call".equals(event.callId)
                && "tool_call".equals(event.stage)).count());
        assertEquals(1L, events.stream().filter(event -> "announced-call".equals(event.callId)
                && "tool_interrupted".equals(event.stage)).count());
        assertEquals(0L, events.stream().filter(event -> "announced-call".equals(event.callId)
                && "tool_not_started".equals(event.stage)).count());
        assertEquals(0L, events.stream().filter(event -> "pending-call".equals(event.callId)
                && "tool_call".equals(event.stage)).count());
        assertEquals(1L, events.stream().filter(event -> "pending-call".equals(event.callId)
                && "tool_not_started".equals(event.stage)).count());
        assertEquals(0L, events.stream().filter(event -> "pending-call".equals(event.callId)
                && "tool_interrupted".equals(event.stage)).count());
    }

    @Test public void interruptedParallelActivityIsEmittedOnlyOnce() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger interruptedEvents = new AtomicInteger();
        CoreTool blocked = tool("blocked", CoreTool.ExecutionMode.PARALLEL, (arguments, token) -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("worker interrupted");
            }
            return CoreToolResult.success("unreachable");
        });
        CoreAgentLoop loop = new CoreAgentLoop((transcript, prompt, declarations, token) ->
                new ModelReply("", Collections.singletonList(
                        new ModelReply.Call("blocked-call", "blocked", Collections.emptyMap()))),
                new CoreToolRegistry(Collections.singletonList(blocked)), "", "single-interrupted-event");
        CancellationToken token = CancellationToken.cancellable();
        java.util.concurrent.ExecutorService runThread = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<CoreAgentLoop.Result> result = runThread.submit(() -> loop.run(
                    "Run the blocking call", Collections.emptyList(), token,
                    new CoreAgentLoop.ProgressListener() {
                        @Override public void onProgress(String stage, String message) { }
                        @Override public void onToolActivity(ToolActivity activity) {
                            if ("tool_interrupted".equals(activity.stage)) interruptedEvents.incrementAndGet();
                        }
                    }));
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            token.cancelForTimeout();

            assertEquals("PARTIAL", result.get(5, java.util.concurrent.TimeUnit.SECONDS).outcome);
            assertEquals(1, interruptedEvents.get());
        } finally {
            runThread.shutdownNow();
        }
    }

    @Test public void cancelledRunStillBudgetsTranscriptButCheckpointsCompleteToolOutput() {
        String complete = "x".repeat(400);
        AtomicReference<CoreAgentLoop.Checkpoint> persisted = new AtomicReference<>();
        CoreTool cancelAfterCompletion = tool("complete", CoreTool.ExecutionMode.SEQUENTIAL, (arguments, token) -> {
            token.cancelForTimeout();
            return CoreToolResult.success(complete);
        });
        CoreAgentLoop loop = new CoreAgentLoop((transcript, prompt, declarations, token) ->
                new ModelReply("", Collections.singletonList(
                        new ModelReply.Call("complete-call", "complete", Collections.emptyMap()))),
                new CoreToolRegistry(Collections.singletonList(cancelAfterCompletion)), "", "cancelled-budget",
                new CorePromptBudget(4096, 256, 1, 256, 256), null);
        loop.setCheckpointListener(persisted::set);

        CoreAgentLoop.Result result = loop.run("Complete the call", Collections.emptyList(),
                CancellationToken.cancellable(), null);

        ConversationTurn output = loop.transcriptSnapshot().stream()
                .filter(turn -> turn.kind == ConversationTurn.Kind.TOOL_RESULT).findFirst().orElseThrow(AssertionError::new);
        assertEquals("PARTIAL", result.outcome);
        assertTrue(output.content.length() < complete.length());
        assertEquals(complete, persisted.get().completedToolResults.get("complete-call"));
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
