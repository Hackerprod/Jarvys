package com.jarvys.agent;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** UX38: a bounded provider operation failure is resumable, not a run-wide STOP. */
public class CoreAgentLoopProviderUnavailableTest {
    @Test public void codexTerminalAndMalformedResponsesAreRecoverableWithoutAutomaticReplay() {
        for (CodexResponseException.Kind kind : CodexResponseException.Kind.values())
            assertProviderPartial(new CodexResponseException(kind));
    }

    @Test public void transportFailureReturnsPartialWithoutCancellingOrRetrying() {
        assertProviderPartial(new ProviderTransportException("Read timed out", new IOException("socket timeout")));
    }

    @Test public void http408ReturnsPartialWithoutCancellingOrRetrying() {
        assertProviderPartial(new ProviderHttpException("Request timeout", 408, 0L, null));
    }

    @Test public void http429WithoutAWaiterReturnsPartialWithoutCancellingOrRetrying() {
        assertProviderPartial(new ProviderRateLimitException("429", 0L));
    }

    @Test public void http503ReturnsPartialWithoutCancellingOrRetrying() {
        assertProviderPartial(new ProviderHttpException("Service unavailable", 503, 0L, null));
    }

    @Test public void wrappedTransportFailureKeepsItsRecoverableClassification() {
        assertProviderPartial(new IllegalStateException("Provider request failed",
                new ProviderTransportException("Connection lost", new IOException("reset"))));
    }

    @Test public void toolEvidenceSurvivesProviderFailureAndExplicitCheckpointContinuationNeverReplaysIt() {
        assertCheckpointContinuation(new ProviderTransportException("Provider disconnected after tool completion", new IOException("reset")));
    }

    @Test public void completedToolEvidenceSurvivesMalformedCodexResponseWithoutReplay() {
        assertCheckpointContinuation(new CodexResponseException(CodexResponseException.Kind.MALFORMED));
    }

    private static void assertCheckpointContinuation(RuntimeException providerFailure) {
        AtomicInteger effects = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<CoreAgentLoop.Checkpoint> durable = new AtomicReference<>();
        CoreTool tool = new CoreTool() {
            @Override public ToolSpec declaration() {
                return new ToolSpec("project_effect", "test", "Record an effect", "test",
                        ToolSpec.Status.IMPLEMENTED, Collections.emptyMap(), Collections.emptyList());
            }
            @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
                assertEquals("A committed effect must execute exactly once", 1, effects.incrementAndGet());
                return CoreToolResult.success("Saved artifact job-42 at /project/output.txt");
            }
        };
        CoreToolRegistry tools = new CoreToolRegistry(Collections.singletonList(tool));
        CoreAgentLoop interrupted = new CoreAgentLoop((transcript, prompt, declarations, token) -> {
            if (requests.incrementAndGet() == 1) return new ModelReply("", Collections.singletonList(
                    new ModelReply.Call("effect-call-42", "project_effect", Collections.emptyMap())));
            assertEquals("Provider failures must not trigger an automatic model retry", 2, requests.get());
            assertRetainedEffect(transcript);
            throw providerFailure;
        }, tools, "", "provider-recovery");
        interrupted.setCheckpointListener(durable::set);
        CancellationToken token = CancellationToken.cancellable();
        AtomicInteger cancelled = new AtomicInteger();
        token.registerCancelAction(cancelled::incrementAndGet);

        CoreAgentLoop.Result partial = interrupted.run("Create the artifact", Collections.emptyList(), token, null);
        assertEquals("PARTIAL", partial.outcome);
        assertEquals(CoreAgentLoop.InterruptionReason.PROVIDER_UNAVAILABLE, partial.interruptionReason);
        assertEquals(1, partial.turns);
        assertEquals(2, requests.get());
        assertEquals(1, effects.get());
        assertEquals(0, cancelled.get());
        assertFalse(token.isCancellationRequested());
        assertNotNull(durable.get());
        assertEquals("RESULT", durable.get().toolLifecycle.get("effect-call-42"));
        assertRetainedEffect(durable.get().transcript);
        assertRetainedEffect(interrupted.transcriptSnapshot());

        AtomicInteger continuationRequests = new AtomicInteger();
        CoreAgentLoop continuation = new CoreAgentLoop((transcript, prompt, declarations, current) -> {
            continuationRequests.incrementAndGet();
            assertRetainedEffect(transcript);
            return new ModelReply("Verified the saved artifact without repeating the effect", Collections.emptyList());
        }, tools, "", "provider-recovery");
        continuation.restoreCheckpoint(durable.get());
        assertEquals("Restoring a checkpoint must not infer or execute anything", 0, continuationRequests.get());
        CoreAgentLoop.Result completed = continuation.run("Explicitly continue and inspect the retained result",
                continuation.transcriptSnapshot(), token, null);
        assertEquals("COMPLETED", completed.outcome);
        assertEquals(CoreAgentLoop.InterruptionReason.NONE, completed.interruptionReason);
        completed.throwIfProviderUnavailable();
        assertEquals(1, continuationRequests.get());
        assertEquals(1, effects.get());
        assertEquals("RESULT", continuation.checkpointSnapshot().toolLifecycle.get("effect-call-42"));
        assertRetainedEffect(continuation.transcriptSnapshot());
        assertEquals(0, cancelled.get());
    }

    @Test public void genuineStopStillThrowsEvenIfProviderAlsoFails() {
        CancellationToken token = CancellationToken.cancellable();
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        token.registerCancelAction(cancelled::incrementAndGet);
        CoreAgentLoop loop = emptyLoop((transcript, prompt, declarations, current) -> {
            requests.incrementAndGet();
            current.cancel();
            throw new ProviderTransportException("Interrupted network request", new IOException("closed"));
        });
        assertThrows(CancellationException.class, () -> loop.run("Run", Collections.emptyList(), token, null));
        assertEquals(1, requests.get());
        assertEquals(1, cancelled.get());
        assertTrue(token.isCancellationRequested());
    }

    @Test public void preexistingStopNeverCallsTheProvider() {
        CancellationToken token = CancellationToken.cancellable();
        token.cancel();
        CoreAgentLoop loop = emptyLoop((transcript, prompt, declarations, current) -> {
            throw new AssertionError("A stopped run must not contact the model");
        });
        assertThrows(CancellationException.class, () -> loop.run("Run", Collections.emptyList(), token, null));
    }

    @Test public void unauthorizedProviderResponseRemainsAFailure() {
        assertFatal(new ProviderHttpException("Unauthorized", 401, 0L, null));
    }

    @Test public void unrelatedRuntimeErrorRemainsAFailure() {
        assertFatal(new IllegalStateException("Local model adapter invariant failed"));
    }

    private static void assertProviderPartial(RuntimeException failure) {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        CancellationToken token = CancellationToken.cancellable();
        token.registerCancelAction(cancelled::incrementAndGet);
        CoreAgentLoop loop = emptyLoop((transcript, prompt, declarations, current) -> {
            requests.incrementAndGet();
            throw failure;
        });
        CoreAgentLoop.Result result = loop.run("Continue the task", Collections.emptyList(), token, null);
        assertEquals("PARTIAL", result.outcome);
        assertEquals(CoreAgentLoop.InterruptionReason.PROVIDER_UNAVAILABLE, result.interruptionReason);
        assertFalse(result.text.trim().isEmpty());
        assertSame(failure, assertThrows(RuntimeException.class, result::throwIfProviderUnavailable));
        assertSame(failure, assertThrows(RuntimeException.class,
                result.withText("Localized presentation")::throwIfProviderUnavailable));
        assertEquals(0, result.turns);
        assertEquals(1, requests.get());
        assertEquals(0, cancelled.get());
        assertFalse(token.isCancellationRequested());
        assertFalse(token.isTimedOut());
    }

    private static void assertFatal(RuntimeException failure) {
        AtomicInteger requests = new AtomicInteger();
        CancellationToken token = CancellationToken.cancellable();
        CoreAgentLoop loop = emptyLoop((transcript, prompt, declarations, current) -> {
            requests.incrementAndGet();
            throw failure;
        });
        assertSame(failure, assertThrows(RuntimeException.class,
                () -> loop.run("Run", Collections.emptyList(), token, null)));
        assertEquals(1, requests.get());
        assertFalse(token.isCancellationRequested());
    }

    private static void assertRetainedEffect(List<ConversationTurn> transcript) {
        assertEquals(1L, transcript.stream().filter(turn -> turn.kind == ConversationTurn.Kind.TOOL_RESULT
                && "effect-call-42".equals(turn.toolCallId)
                && "project_effect".equals(turn.toolName)
                && "Saved artifact job-42 at /project/output.txt".equals(turn.content)).count());
        assertEquals(1L, transcript.stream().flatMap(turn -> turn.toolCalls.stream())
                .filter(call -> "effect-call-42".equals(call.id)).count());
    }

    private static CoreAgentLoop emptyLoop(CoreAgentLoop.Model model) {
        return new CoreAgentLoop(model, new CoreToolRegistry(Collections.emptyList()), "", "provider-recovery");
    }
}
