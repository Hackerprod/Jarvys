package com.jarvys.agent;

import android.content.Context;
import com.jarvys.agent.crew.CrewManager;
import com.jarvys.agent.crew.CrewProfile;
import com.jarvys.agent.crew.CrewRole;
import com.jarvys.agent.workspaces.WorkspaceIdentity;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** Real live Crew provenance for local tests; never constructs a witness from arbitrary metadata. */
public final class CodingSubmissionTestSource {
    private CodingSubmissionTestSource() { }

    public static void run(Context context, BiConsumer<EffectInvocation, CancellationToken> action) throws Exception {
        String session = WorkspaceIdentity.executionSessionId(UUID.randomUUID().toString());
        new LocalRunStore(context).appendConversationMessage(session, "user", "Synthetic Coding source fixture");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch invoked = new CountDownLatch(1);
        CoreTool probe = new CoreTool() {
            private final ToolSpec spec = new ToolSpec("project_exec", "test", "Local source fixture", "test",
                    ToolSpec.Status.IMPLEMENTED, Collections.emptyMap(), Collections.emptyList());
            @Override public ToolSpec declaration() { return spec; }
            @Override public CoreToolResult execute(Map<String,Object> arguments, CancellationToken token) {
                throw new AssertionError("Explicit invocation transport is required");
            }
            @Override public CoreToolResult execute(Map<String,Object> arguments, CancellationToken token,
                    ProgressListener progress, EffectInvocation invocation) {
                try {
                    if (invocation.crewSource() == null) throw new AssertionError("Real Crew source missing");
                    invocation.crewSource().requireInvocationCurrent();
                    calls.incrementAndGet();
                    action.accept(invocation, token);
                    return CoreToolResult.success("Synthetic local callback completed");
                } catch (Throwable error) {
                    failure.set(error);
                    throw new AssertionError("Synthetic local callback failed", error);
                } finally { invoked.countDown(); }
            }
        };
        CoreToolRegistry registry = new CoreToolRegistry(Collections.singletonList(probe));
        CrewRole role = new CrewRole("coding-source-fixture", "Fixture", "coding", "Observe exact source",
                Collections.singletonList("project_exec"), null, "Local fixture profile", 1,
                Collections.emptyList(), CrewProfile.WorkspaceMode.CONVERSATION_PROJECT);
        AtomicInteger turns = new AtomicInteger();
        try (CrewManager manager = new CrewManager(session, registry,
                (bot, crew) -> CoreAgentRuntime.withCrewInvocationSource(registry, bot, crew),
                (bot, tools, incoming) -> new CoreAgentLoop((history, prompt, declarations, token) ->
                        turns.getAndIncrement() == 0
                                ? new ModelReply("", Collections.singletonList(new ModelReply.Call(
                                        "coding-fixture-call", "project_exec", Collections.emptyMap())))
                                : new ModelReply("Done", Collections.emptyList()),
                        tools, "", session, CorePromptBudget.standard(), null,
                        CoreAgentLoop.ToolResultBudget.UNBOUNDED, incoming, null), null)) {
            manager.configureProfiles(id -> role, role.tools);
            manager.configureCheckpoints(new CrewCheckpointCoordinator(context, session, bot -> bot.role));
            CrewManager.Bot bot = manager.spawn(role.id, "Exercise local Coding evidence", role.tools, "Fixture");
            if (!invoked.await(30, TimeUnit.SECONDS)) {
                manager.stop(bot.id);
                throw new AssertionError("Real Crew dispatch did not reach fixture");
            }
            CountDownLatch terminated = new CountDownLatch(1);
            Thread waiter = new Thread(() -> {
                try { bot.awaitTermination(); terminated.countDown(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, "coding-source-fixture-await");
            waiter.setDaemon(true);
            waiter.start();
            try {
                if (!terminated.await(30, TimeUnit.SECONDS)) {
                    manager.stop(bot.id);
                    throw new AssertionError("Fixture worker did not terminate");
                }
            } finally { waiter.interrupt(); }
            if (failure.get() != null) throw new AssertionError("Fixture callback failed", failure.get());
            if (calls.get() != 1) throw new AssertionError("Fixture requires exactly one real dispatch");
        }
    }
}
