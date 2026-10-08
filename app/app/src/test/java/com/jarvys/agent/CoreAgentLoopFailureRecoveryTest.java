package com.jarvys.agent;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class CoreAgentLoopFailureRecoveryTest {
    @Test public void volatileJournalFailuresStopAfterThreeEffectsAndOneCorrectionTurn() {
        AtomicInteger effects = new AtomicInteger();
        AtomicInteger turns = new AtomicInteger();
        CoreTool tool = tool((arguments, token) -> CoreToolResult.failure(
                "identity.json -> .journal-" + effects.incrementAndGet() + ".tmp"));
        CoreAgentLoop loop = loop(tool, (transcript, prompt, declarations, token) -> {
            int turn = turns.incrementAndGet();
            assertTrue("Repeated failures must not exhaust the model-turn limit", turn <= 5);
            return reply(call("call-" + turn, "/project"));
        });
        CoreAgentLoop.Result result = loop.run("List project files", Collections.emptyList(), CancellationToken.uncancellable(), null);
        assertEquals("PARTIAL", result.outcome);
        assertEquals(3, effects.get());
        assertEquals(5, result.turns);
        CoreAgentLoop.Checkpoint checkpoint = loop.checkpointSnapshot().reconciled();
        assertEquals("NEVER_LAUNCHED", checkpoint.toolLifecycle.get("call-4"));
        assertEquals("NEVER_LAUNCHED", checkpoint.toolLifecycle.get("call-5"));
        assertEquals(5, checkpoint.transcript.stream().filter(t -> t.kind == ConversationTurn.Kind.TOOL_RESULT).count());
        assertTrue(checkpoint.transcript.stream().anyMatch(t -> t.content.contains("This attempt was not executed")));
    }

    @Test public void duplicateFailuresWithinOneBatchCannotBypassTheGuard() {
        AtomicInteger effects = new AtomicInteger();
        AtomicInteger turns = new AtomicInteger();
        CoreTool tool = tool((arguments, token) -> CoreToolResult.failure("failure " + effects.incrementAndGet()));
        CoreAgentLoop loop = loop(tool, (transcript, prompt, declarations, token) -> turns.incrementAndGet() == 1
                ? new ModelReply("", Arrays.asList(call("a", "/project"), call("b", "/project"),
                        call("c", "/project"), call("d", "/project")))
                : new ModelReply("The project remains blocked.", Collections.emptyList()));
        loop.run("List project files", Collections.emptyList(), CancellationToken.uncancellable(), null);
        assertEquals(3, effects.get());
        assertEquals("NEVER_LAUNCHED", loop.checkpointSnapshot().reconciled().toolLifecycle.get("d"));
    }

    @Test public void aBlockedCallDoesNotSuppressChangedArgumentsInTheSameReply() {
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        AtomicInteger turns = new AtomicInteger();
        CoreTool tool = tool((arguments, token) -> {
            if ("/project".equals(arguments.get("path"))) return CoreToolResult.failure("failure " + failures.incrementAndGet());
            recoveries.incrementAndGet();
            return CoreToolResult.success("Files are available in the corrected location.");
        });
        CoreAgentLoop loop = loop(tool, (transcript, prompt, declarations, token) -> {
            int turn = turns.incrementAndGet();
            if (turn <= 3) return reply(call("failed-" + turn, "/project"));
            if (turn == 4) return new ModelReply("", Arrays.asList(call("blocked", "/project"), call("corrected", "/memory")));
            assertTrue(transcript.stream().anyMatch(t -> t.content.equals("Files are available in the corrected location.")));
            return new ModelReply("Listed the corrected location.", Collections.emptyList());
        });
        CoreAgentLoop.Result result = loop.run("List files", Collections.emptyList(), CancellationToken.uncancellable(), null);
        assertEquals("COMPLETED", result.outcome);
        assertEquals(3, failures.get());
        assertEquals(1, recoveries.get());
        CoreAgentLoop.Checkpoint checkpoint = loop.checkpointSnapshot().reconciled();
        assertEquals("NEVER_LAUNCHED", checkpoint.toolLifecycle.get("blocked"));
        assertEquals("RESULT", checkpoint.toolLifecycle.get("corrected"));
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

    @Test public void freshCorrectiveSuccessAllowsTheOriginalArgumentsAgain() {
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        AtomicInteger turns = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean repaired = new java.util.concurrent.atomic.AtomicBoolean();
        CoreTool tool = tool((arguments, token) -> {
            if ("repair".equals(arguments.get("path"))) {
                repaired.set(true);
                return CoreToolResult.success("Repaired project access");
            }
            if (!repaired.get()) return CoreToolResult.failure("failure " + failures.incrementAndGet());
            effects.incrementAndGet();
            return CoreToolResult.success("Project is readable now");
        });
        CoreAgentLoop loop = loop(tool, (transcript, prompt, declarations, token) -> {
            int turn = turns.incrementAndGet();
            if (turn <= 3) return reply(call("failure-" + turn, "/project"));
            if (turn == 4) return new ModelReply("", Arrays.asList(call("blocked", "/project"), call("repair", "repair")));
            if (turn == 5) return reply(call("retry-after-repair", "/project"));
            assertEquals(6, turn);
            return new ModelReply("Recovered", Collections.emptyList());
        });
        assertEquals("COMPLETED", loop.run("Read project", Collections.emptyList(), CancellationToken.uncancellable(), null).outcome);
        assertEquals(3, failures.get());
        assertEquals(1, effects.get());
        assertEquals("RESULT", loop.checkpointSnapshot().toolLifecycle.get("retry-after-repair"));
    }

    @Test public void alternatingUnchangedSuccessCannotHideAnUnboundedFailureLoop() {
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger turns = new AtomicInteger();
        CoreTool tool = tool((arguments, token) -> "stable".equals(arguments.get("path"))
                ? CoreToolResult.success("unchanged") : CoreToolResult.failure("failure " + failures.incrementAndGet()));
        CoreAgentLoop.Model model = (transcript, prompt, declarations, token) -> {
            int turn = turns.incrementAndGet();
            assertTrue("An unbounded Crew run must still stop a no-progress failure loop", turn <= 6);
            return new ModelReply("", Arrays.asList(call("failed-" + turn, "/project"), call("stable-" + turn, "stable")));
        };
        CoreAgentLoop loop = new CoreAgentLoop(model, new CoreToolRegistry(Collections.singletonList(tool)), "", "test",
                CorePromptBudget.standard(), null, CoreAgentLoop.Limits.UNBOUNDED, null, null);
        assertEquals("PARTIAL", loop.run("Read project", Collections.emptyList(), CancellationToken.uncancellable(), null).outcome);
        assertEquals(4, failures.get());
        assertEquals(6, turns.get());
        loop.checkpointSnapshot().reconciled();
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
