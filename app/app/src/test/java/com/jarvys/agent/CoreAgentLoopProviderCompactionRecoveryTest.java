package com.jarvys.agent;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Provider failures in context-management operations share the resumable UX38 contract. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CoreAgentLoopProviderCompactionRecoveryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void contextWindowTransportFailureReturnsPartialBeforeAnyInference() throws Exception {
        AtomicInteger windows = new AtomicInteger();
        AtomicInteger summaries = new AtomicInteger();
        ConversationCompactor compactor = compactor(summaries);
        CoreAgentLoop.Model model = new CoreAgentLoop.Model() {
            @Override public int contextWindow(CancellationToken token) {
                windows.incrementAndGet();
                throw unavailable();
            }
            @Override public ModelReply complete(List<ConversationTurn> transcript, String prompt,
                    List<ToolSpec> declarations, CancellationToken token) {
                throw new AssertionError("Unavailable context metadata must not trigger another model request");
            }
        };
        List<ConversationTurn> history = history("Retain the original observation");
        CancellationToken token = CancellationToken.cancellable();
        AtomicInteger cancellations = new AtomicInteger();
        token.registerCancelAction(cancellations::incrementAndGet);
        CoreAgentLoop loop = loop(model, new CoreToolRegistry(Collections.emptyList()), compactor);
        assertPartial(loop.run("Continue", history, token, null), token, cancellations);
        assertEquals(1, windows.get());
        assertEquals(0, summaries.get());
        assertEquals(history, loop.transcriptSnapshot());
    }

    @Test public void preflightSummaryProviderFailureKeepsFullTranscriptWithoutRetrying() throws Exception {
        AtomicInteger summaries = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        CoreAgentLoop.Model model = new CoreAgentLoop.Model() {
            @Override public int contextWindow(CancellationToken token) { return 8192; }
            @Override public ModelReply complete(List<ConversationTurn> transcript, String prompt,
                    List<ToolSpec> declarations, CancellationToken token) {
                requests.incrementAndGet();
                throw new AssertionError("Failed preflight compaction must not fall through to inference");
            }
        };
        List<ConversationTurn> history = history(String.join("", Collections.nCopies(4000, "Historical evidence. ")));
        CoreAgentLoop loop = loop(model, new CoreToolRegistry(Collections.emptyList()), compactor(summaries));
        CancellationToken token = CancellationToken.cancellable();
        AtomicInteger cancellations = new AtomicInteger();
        token.registerCancelAction(cancellations::incrementAndGet);
        assertPartial(loop.run("Continue", history, token, null), token, cancellations);
        assertEquals(1, summaries.get());
        assertEquals(0, requests.get());
        assertEquals(history, loop.transcriptSnapshot());
        assertEquals(history, loop.checkpointSnapshot().transcript);
    }

    @Test public void overflowSummaryProviderFailureDoesNotRetryTheOriginalInference() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger summaries = new AtomicInteger();
        CoreAgentLoop.Model model = new CoreAgentLoop.Model() {
            @Override public int contextWindow(CancellationToken token) { return 8192; }
            @Override public ModelReply complete(List<ConversationTurn> transcript, String prompt,
                    List<ToolSpec> declarations, CancellationToken token) {
                requests.incrementAndGet();
                throw new IllegalStateException("maximum context length exceeded");
            }
        };
        List<ConversationTurn> history = history("Observed the original project output");
        CoreAgentLoop loop = loop(model, new CoreToolRegistry(Collections.emptyList()), compactor(summaries));
        CancellationToken token = CancellationToken.cancellable();
        AtomicInteger cancellations = new AtomicInteger();
        token.registerCancelAction(cancellations::incrementAndGet);
        assertPartial(loop.run("Continue", history, token, null), token, cancellations);
        assertEquals(1, requests.get());
        assertEquals(1, summaries.get());
        assertEquals(history, loop.transcriptSnapshot());
    }

    @Test public void providerFailureWhileRetainingToolOutputKeepsTheCommittedReceiptAndNeverRepeatsEffect() throws Exception {
        AtomicInteger windows = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        AtomicInteger summaries = new AtomicInteger();
        CoreTool tool = new CoreTool() {
            @Override public ToolSpec declaration() {
                return new ToolSpec("project_effect", "test", "Record an effect", "test",
                        ToolSpec.Status.IMPLEMENTED, Collections.emptyMap(), Collections.emptyList());
            }
            @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
                effects.incrementAndGet();
                return CoreToolResult.success("Committed job-55 output");
            }
        };
        CoreToolRegistry tools = new CoreToolRegistry(Collections.singletonList(tool));
        CoreAgentLoop.Model model = new CoreAgentLoop.Model() {
            @Override public int contextWindow(CancellationToken token) {
                // Preflight and post-reply checks succeed. The check after the actual effect fails.
                if (windows.incrementAndGet() == 3) throw unavailable();
                return 8192;
            }
            @Override public ModelReply complete(List<ConversationTurn> transcript, String prompt,
                    List<ToolSpec> declarations, CancellationToken token) {
                assertEquals("No model request may be retried", 1, requests.incrementAndGet());
                return new ModelReply("", Collections.singletonList(
                        new ModelReply.Call("effect-55", "project_effect", Collections.emptyMap())));
            }
        };
        CoreAgentLoop interrupted = loop(model, tools, compactor(summaries));
        CancellationToken token = CancellationToken.cancellable();
        AtomicInteger cancellations = new AtomicInteger();
        token.registerCancelAction(cancellations::incrementAndGet);
        assertPartial(interrupted.run("Create an output", Collections.emptyList(), token, null), token, cancellations);
        assertEquals(1, effects.get());
        assertEquals(1, requests.get());
        assertEquals(3, windows.get());
        assertEquals(0, summaries.get());
        assertEquals("RESULT", interrupted.checkpointSnapshot().toolLifecycle.get("effect-55"));
        assertEquals(1L, interrupted.transcriptSnapshot().stream().filter(turn ->
                turn.kind == ConversationTurn.Kind.TOOL_RESULT && "effect-55".equals(turn.toolCallId)
                        && "Committed job-55 output".equals(turn.content)).count());

        CoreAgentLoop continued = new CoreAgentLoop((transcript, prompt, declarations, current) -> {
            assertTrue(transcript.stream().anyMatch(turn -> "effect-55".equals(turn.toolCallId)
                    && "Committed job-55 output".equals(turn.content)));
            return new ModelReply("Reviewed committed output", Collections.emptyList());
        }, tools, "", "compaction-provider-recovery");
        continued.restoreCheckpoint(interrupted.checkpointSnapshot());
        assertEquals("COMPLETED", continued.run("Explicitly continue", continued.transcriptSnapshot(), token, null).outcome);
        assertEquals(1, effects.get());
        assertEquals(0, cancellations.get());
    }

    private ConversationCompactor compactor(AtomicInteger summaries) throws Exception {
        return ConversationCompactor.forCrew("compaction-provider-recovery", (instructions, source, token) -> {
            summaries.incrementAndGet();
            throw new ProviderHttpException("Summary provider unavailable", 503, 0L, null);
        }, new CrewContextArtifacts(temporary.newFolder(), "worker-one"));
    }

    private static CoreAgentLoop loop(CoreAgentLoop.Model model, CoreToolRegistry tools, ConversationCompactor compactor) {
        return new CoreAgentLoop(model, tools, "", "compaction-provider-recovery",
                CorePromptBudget.standard(), compactor);
    }

    private static List<ConversationTurn> history(String oldEvidence) {
        return Arrays.asList(new ConversationTurn("assistant", oldEvidence),
                new ConversationTurn("user", "Keep the user's latest correction"));
    }

    private static ProviderTransportException unavailable() {
        return new ProviderTransportException("Context metadata request timed out", new IOException("socket timeout"));
    }

    private static void assertPartial(CoreAgentLoop.Result result, CancellationToken token, AtomicInteger cancellations) {
        assertEquals("PARTIAL", result.outcome);
        assertEquals(CoreAgentLoop.InterruptionReason.PROVIDER_UNAVAILABLE, result.interruptionReason);
        assertEquals(0, cancellations.get());
        assertFalse(token.isCancellationRequested());
        assertFalse(token.isTimedOut());
    }
}
