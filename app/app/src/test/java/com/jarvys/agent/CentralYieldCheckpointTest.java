package com.jarvys.agent;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Structural conservation only; these tests do not mint a native suspension capability. */
public class CentralYieldCheckpointTest {
    private CoreAgentLoop loop() {
        return new CoreAgentLoop((CoreAgentLoop.Model) (history, prompt, tools, token) -> {
            throw new AssertionError("Checkpoint confirmation must never dispatch inference");
        }, new CoreToolRegistry(Collections.emptyList()), "", "central");
    }
    private CoreAgentLoop.Checkpoint checkpoint(String state, boolean result) {
        List<ConversationTurn> turns = new ArrayList<>();
        turns.add(ConversationTurn.toolCalls("", Collections.singletonList(
                new ModelReply.Call("call", "read_only", Collections.emptyMap()))));
        if (result) turns.add(ConversationTurn.toolResult("call", "read_only", "retained evidence"));
        return new CoreAgentLoop.Checkpoint(turns, Collections.singleton("already-applied"),
                Collections.singletonMap("call", state), -1);
    }
    @Test public void confirmedSuccessorConservesExactTurnsInputsAndLifecycleBeforeInference() {
        CoreAgentLoop loop = loop();
        CoreAgentLoop.Checkpoint original = checkpoint("RESULT", true);
        AtomicReference<CoreAgentLoop.Checkpoint> saved = new AtomicReference<>();
        loop.setCheckpointListener(saved::set);
        loop.restoreAndConfirmNativeSuccessor(original);
        assertSame(saved.get(), loop.checkpointSnapshot());
        assertEquals(original.transcript, saved.get().transcript);
        assertSame(original.transcript.get(0), saved.get().transcript.get(0));
        assertEquals(original.appliedIncomingIds, saved.get().appliedIncomingIds);
        assertEquals(original.toolLifecycle, saved.get().toolLifecycle);
    }
    @Test public void unresolvedEffectsAndMissingResultsRejectBeforeAnySuccessorWrite() {
        for (String state : Arrays.asList("INTENT", "STARTED", "INTERRUPTED_UNCERTAIN", "RESULT", "NEVER_LAUNCHED")) {
            CoreAgentLoop loop = loop();
            AtomicInteger writes = new AtomicInteger();
            loop.setCheckpointListener(value -> writes.incrementAndGet());
            assertThrows(IllegalStateException.class, () -> loop.restoreAndConfirmNativeSuccessor(checkpoint(state, false)));
            assertEquals(0, writes.get());
        }
    }
    @Test public void historicalNeverLaunchedObservationIsConservedButNeverReplayed() {
        CoreAgentLoop loop = loop();
        CoreAgentLoop.Checkpoint original = checkpoint("NEVER_LAUNCHED", true);
        loop.setCheckpointListener(value -> assertEquals("NEVER_LAUNCHED", value.toolLifecycle.get("call")));
        loop.restoreAndConfirmNativeSuccessor(original);
        assertEquals(original.transcript, loop.checkpointSnapshot().transcript);
    }
    @Test public void phantomLifecycleAndMissingDurableWriterFailClosed() {
        CoreAgentLoop loop = loop();
        assertThrows(IllegalStateException.class, () -> loop.restoreAndConfirmNativeSuccessor(checkpoint("RESULT", true)));
        loop.setCheckpointListener(value -> fail("No writes for invalid lifecycle"));
        CoreAgentLoop.Checkpoint phantom = new CoreAgentLoop.Checkpoint(Collections.emptyList(),
                Collections.emptySet(), Collections.singletonMap("phantom", "RESULT"), -1);
        assertThrows(IllegalStateException.class, () -> loop.restoreAndConfirmNativeSuccessor(phantom));
    }
    @Test public void persistenceFailureDoesNotBecomeConfirmedSuccessor() {
        CoreAgentLoop loop = loop();
        loop.setCheckpointListener(value -> { throw new IllegalStateException("unconfirmed commit"); });
        assertThrows(CoreAgentLoop.CheckpointFailure.class,
                () -> loop.restoreAndConfirmNativeSuccessor(checkpoint("RESULT", true)));
    }
}
