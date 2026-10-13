package com.jarvys.agent;

import static org.junit.Assert.*;

import com.jarvys.agent.device.ScreenData;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Focused behavior tests for compaction at model-turn boundaries and its recovery path. */
public class CoreAgentLoopCompactionBehaviorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void midRunPreflightCompactionKeepsCurrentUserTurnWhenNextPromptIsEmpty() throws Exception {
        File files = temporary.newFolder();
        String session = "mid-run-current-user";
        LocalRunStore store = new LocalRunStore(files);
        store.appendConversationMessage(session, "assistant", "old context " + repeat('x', 20_000));
        store.appendConversationMessage(session, "user", "Do the current task");
        List<ConversationTurn> history = store.loadConversationContext(session);
        AtomicInteger contextChecks = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        CoreAgentLoop.Model model = new CoreAgentLoop.Model() {
            @Override public int contextWindow(CancellationToken token) {
                return contextChecks.incrementAndGet() == 1 ? 32_768 : 4_096;
            }

            @Override public ModelReply complete(List<ConversationTurn> transcript, String prompt,
                    List<ToolSpec> declarations, CancellationToken token) {
                if (requests.incrementAndGet() == 1) {
                    assertEquals("", prompt);
                    return new ModelReply("", Collections.singletonList(
                            new ModelReply.Call("mid-run-call", "work", Collections.emptyMap())));
                }
                assertEquals("", prompt);
                assertTrue(transcript.stream().anyMatch(turn -> turn.kind == ConversationTurn.Kind.MESSAGE
                        && "user".equals(turn.role) && "Do the current task".equals(turn.content)));
                assertTrue("The preflight should have summarized the older context",
                        transcript.stream().anyMatch(turn -> turn.kind == ConversationTurn.Kind.COMPACTION_SUMMARY));
                return new ModelReply("Finished the task", Collections.emptyList());
            }
        };
        CoreAgentLoop loop = new CoreAgentLoop(model, new CoreToolRegistry(Collections.singletonList(tool("work", (args, token) -> {
            effects.incrementAndGet();
            return CoreToolResult.success("work completed");
        }))), "", session, CorePromptBudget.standard(),
                compactor(session, store, "summary", new AtomicInteger(), false));

        CoreAgentLoop.Result result = loop.run("", history, CancellationToken.uncancellable(), null);

        assertEquals("COMPLETED", result.outcome);
        assertEquals("Finished the task", result.text);
        assertEquals(2, requests.get());
        assertEquals(1, effects.get());
        assertEquals(1, store.readConversationTimeline(session).stream().filter(row -> "compaction".equals(row.getKind())).count());
    }

    @Test public void modelReplyDoesNotTriggerCompactionBeforeItsToolCallsExecute() throws Exception {
        File files = temporary.newFolder();
        String session = "no-post-reply-compaction";
        LocalRunStore store = new LocalRunStore(files);
        AtomicInteger summaries = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        CoreAgentLoop.Model model = new CoreAgentLoop.Model() {
            @Override public int contextWindow(CancellationToken token) { return 32_768; }

            @Override public ModelReply complete(List<ConversationTurn> transcript, String prompt,
                    List<ToolSpec> declarations, CancellationToken token) {
                int request = requests.incrementAndGet();
                if (request == 1)
                    return new ModelReply("Calling the tool", Collections.singletonList(
                            new ModelReply.Call("post-reply-call", "work", Collections.emptyMap())),
                            "", null, "fake", 30_000);
                assertEquals("The tool effect must run before the next model request", 1, effects.get());
                return new ModelReply("Done", Collections.emptyList());
            }
        };
        CoreAgentLoop loop = new CoreAgentLoop(model, new CoreToolRegistry(Collections.singletonList(tool("work", (args, token) -> {
            effects.incrementAndGet();
            return CoreToolResult.success("effect recorded");
        }))), "", session, CorePromptBudget.standard(),
                compactor(session, store, "unexpected summary", summaries, false));

        CoreAgentLoop.Result result = loop.run("Please do the work", Collections.emptyList(),
                CancellationToken.uncancellable(), null);

        assertEquals("COMPLETED", result.outcome);
        assertEquals(2, requests.get());
        assertEquals(1, effects.get());
        assertEquals("A reported response token count must not compact between reply and tool execution", 0, summaries.get());
        assertTrue(store.readConversationTimeline(session).stream().noneMatch(row -> "compaction".equals(row.getKind())));
    }

    @Test public void failedCompactionTruncatesOnlyToolResultsAndKeepsMoreThanFortyMessages() throws Exception {
        File files = temporary.newFolder();
        String session = "failed-compaction-tool-budget";
        LocalRunStore store = new LocalRunStore(files);
        AtomicInteger summaryAttempts = new AtomicInteger();
        AtomicReference<List<ConversationTurn>> received = new AtomicReference<>();
        List<ConversationTurn> history = new ArrayList<>();
        for (int index = 0; index < 22; index++) {
            history.add(new ConversationTurn("user", "user-" + index + repeat('u', 180), index * 2));
            history.add(new ConversationTurn("assistant", "assistant-" + index + repeat('a', 180), index * 2 + 1));
        }
        List<ModelReply.Call> calls = Arrays.asList(
                new ModelReply.Call("large-result-1", "read", Collections.emptyMap()),
                new ModelReply.Call("large-result-2", "read", Collections.emptyMap()));
        history.add(ConversationTurn.toolCalls("Reading data", calls));
        history.add(ConversationTurn.toolResult("large-result-1", "read", repeat('r', 8_000)));
        history.add(ConversationTurn.toolResult("large-result-2", "read", repeat('s', 8_000)));
        history.add(new ConversationTurn("user", "latest request", 46));
        CoreAgentLoop.Model model = new CoreAgentLoop.Model() {
            @Override public int contextWindow(CancellationToken token) { return 4_096; }

            @Override public ModelReply complete(List<ConversationTurn> transcript, String prompt,
                    List<ToolSpec> declarations, CancellationToken token) {
                received.set(new ArrayList<>(transcript));
                return new ModelReply("Recovery used the existing transcript", Collections.emptyList());
            }
        };
        CorePromptBudget budget = new CorePromptBudget(4_096, 256, 1, 256, 3_000);
        ConversationCompactor compactor = compactor(session, store, "unused", summaryAttempts, true);

        CoreAgentLoop.Result result = new CoreAgentLoop(model, new CoreToolRegistry(Collections.emptyList()),
                "", session, budget, compactor).run("", history, CancellationToken.uncancellable(), null);

        List<ConversationTurn> request = received.get();
        assertEquals("COMPLETED", result.outcome);
        assertNotNull(request);
        assertEquals("The fallback must not discard a fixed-size message tail", history.size(), request.size());
        for (int index = 0; index < history.size(); index++) {
            ConversationTurn before = history.get(index), after = request.get(index);
            assertEquals(before.kind, after.kind);
            if (before.kind == ConversationTurn.Kind.TOOL_RESULT) {
                assertTrue("Tool result should be bounded by the configured transcript budget", after.content.length() < before.content.length());
            } else {
                assertEquals(before.content, after.content);
            }
        }
        assertTrue("More than forty original messages should remain available", request.size() > 40);
        assertEquals(1, summaryAttempts.get());
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

    private static ConversationCompactor compactor(String session, LocalRunStore store, String summary,
            AtomicInteger attempts, boolean fail) {
        ModelProviderClient provider = new ModelProviderClient() {
            @Override public ModelReply complete(String systemPrompt, String userPrompt, List<ScreenData> images,
                    List<ToolSpec> tools, String sessionId, CancellationToken token) {
                return new ModelReply(summary, Collections.emptyList());
            }
            @Override public ModelReply completeConversation(String systemPrompt, List<ConversationTurn> history,
                    String userPrompt, List<ScreenData> images, List<ToolSpec> tools, String sessionId,
                    CancellationToken token) {
                attempts.incrementAndGet();
                if (fail) throw new IllegalStateException("summary provider unavailable");
                return new ModelReply(summary, Collections.emptyList());
            }
        };
        return new ConversationCompactor(session, new CoreAgentModel(provider, session), store);
    }

    private static String repeat(char character, int count) {
        char[] value = new char[count];
        Arrays.fill(value, character);
        return new String(value);
    }
}
