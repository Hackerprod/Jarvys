package com.jarvys.agent;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Provider retries repeat only the model request and retain the failed attempt in context. */
public class CoreAgentLoopProviderRetryTest {
    @Test public void rateLimitHonorsRetryAfterAndDoesNotRepeatPriorToolEffects() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger toolEffects = new AtomicInteger();
        List<Long> waits = new java.util.ArrayList<>();
        AtomicReference<List<ConversationTurn>> retriedHistory = new AtomicReference<>();
        CoreAgentLoop.Model model = new CoreAgentLoop.Model() {
            @Override public ModelReply complete(List<ConversationTurn> transcript, String prompt,
                    List<ToolSpec> declarations, CancellationToken token) {
                int attempt = attempts.incrementAndGet();
                if (attempt == 1) return new ModelReply("Performing the effect", Collections.singletonList(
                        new ModelReply.Call("effect-once", "effect", Collections.emptyMap())));
                if (attempt == 2) {
                    assertEquals(1, toolEffects.get());
                    assertTrue(transcript.stream().anyMatch(turn -> turn.kind == ConversationTurn.Kind.TOOL_RESULT
                            && "effect-once".equals(turn.toolCallId)));
                    throw new ProviderRateLimitException("rate limited", 4_500L);
                }
                retriedHistory.set(new java.util.ArrayList<>(transcript));
                assertTrue("Retry sees the committed tool result", transcript.stream().anyMatch(turn ->
                        turn.kind == ConversationTurn.Kind.TOOL_RESULT && "effect-once".equals(turn.toolCallId)));
                assertTrue("Retry retains the failed assistant attempt", transcript.stream().anyMatch(turn ->
                        turn.kind == ConversationTurn.Kind.MODEL_ERROR && "http_429".equals(turn.modelErrorCode)));
                return new ModelReply("The work is complete", Collections.emptyList());
            }
        };
        ProviderRetryWaiter waiter = new ProviderRetryWaiter((millis, token) -> {
            token.throwIfCancelled();
            waits.add(millis);
        }, () -> 0.75d);
        CoreAgentLoop loop = new CoreAgentLoop(model,
                new CoreToolRegistry(Collections.singletonList(tool("effect", (arguments, token) -> {
                    toolEffects.incrementAndGet();
                    return CoreToolResult.success("effect committed");
                }))), "", "provider-retry", CorePromptBudget.standard(), null, waiter);

        CoreAgentLoop.Result result = loop.run("Perform the effect once", Collections.emptyList(),
                CancellationToken.uncancellable(), null);

        assertEquals("COMPLETED", result.outcome);
        assertEquals("The work is complete", result.text);
        assertEquals(3, attempts.get());
        assertEquals(1, toolEffects.get());
        assertEquals(Collections.singletonList(4_500L), waits);
        assertNotNull(retriedHistory.get());
    }

    @Test public void cancellationDuringProviderBackoffEndsTheLoopCleanly() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger waits = new AtomicInteger();
        CancellationToken token = CancellationToken.cancellable();
        ProviderRetryWaiter waiter = new ProviderRetryWaiter((millis, current) -> {
            waits.incrementAndGet();
            assertTrue(current.cancel());
            current.throwIfCancelled();
        }, () -> 0.5d);
        CoreAgentLoop loop = new CoreAgentLoop((transcript, prompt, declarations, current) -> {
            attempts.incrementAndGet();
            throw new ProviderRateLimitException("rate limited", 1_000L);
        }, new CoreToolRegistry(Collections.emptyList()), "", "provider-retry-cancel",
                CorePromptBudget.standard(), null, waiter);

        assertThrows(CancellationException.class,
                () -> loop.run("Wait and retry", Collections.emptyList(), token, null));

        assertEquals(1, attempts.get());
        assertEquals(1, waits.get());
    }

    @Test public void nonRecoverableProviderFailureIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger waits = new AtomicInteger();
        ProviderRetryWaiter waiter = new ProviderRetryWaiter((millis, token) -> waits.incrementAndGet(), () -> 0.5d);
        CoreAgentLoop loop = new CoreAgentLoop((transcript, prompt, declarations, token) -> {
            attempts.incrementAndGet();
            throw new ProviderHttpException("invalid request", 400, 0L, null);
        }, new CoreToolRegistry(Collections.emptyList()), "", "provider-retry-nonrecoverable",
                CorePromptBudget.standard(), null, waiter);

        assertThrows(ProviderHttpException.class,
                () -> loop.run("Try once", Collections.emptyList(), CancellationToken.uncancellable(), null));

        assertEquals(1, attempts.get());
        assertEquals(0, waits.get());
    }

    private interface Effect {
        CoreToolResult execute(Map<String, Object> arguments, CancellationToken token);
    }

    private static CoreTool tool(String name, Effect effect) {
        return new CoreTool() {
            @Override public ToolSpec declaration() {
                return new ToolSpec(name, "test", "test tool", "test", ToolSpec.Status.IMPLEMENTED,
                        Collections.emptyMap(), Collections.emptyList());
            }
            @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
                return effect.execute(arguments, token);
            }
        };
    }
}
