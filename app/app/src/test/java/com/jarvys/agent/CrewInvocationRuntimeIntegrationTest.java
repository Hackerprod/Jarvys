package com.jarvys.agent;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.core.app.ApplicationProvider;
import com.jarvys.agent.crew.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Real Manager + Coordinator + Runtime installer; the probe substitutes only the local tool/model. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, shadows = ArtifactOsShadow.class)
public class CrewInvocationRuntimeIntegrationTest {
    @Rule public final ChatTestStore fixture = new ChatTestStore();

    @Test public void realWorkerPersistsStartedThenDispatchesOwnerBoundSourceWhileMemoryOff() throws Exception {
        File files = fixture.newFilesDirectory(); fixture.localRunStore(files);
        Context context = new ContextWrapper(ApplicationProvider.getApplicationContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return files; }
        };
        String session = "crew-source-integration";
        AtomicReference<CrewInvocationWitness> witness = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch observed = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        CoreTool probe = new CoreTool() {
            final ToolSpec spec = new ToolSpec("source_probe","test","Observe local source","test",ToolSpec.Status.IMPLEMENTED,
                    Collections.emptyMap(),Collections.emptyList());
            @Override public ToolSpec declaration() { return spec; }
            @Override public CoreToolResult execute(Map<String,Object> args,CancellationToken token) {
                throw new AssertionError("Must dispatch with invocation context");
            }
            @Override public CoreToolResult execute(Map<String,Object> args,CancellationToken token,ProgressListener progress,EffectInvocation invocation) {
                try {
                    CrewInvocationWitness actual = invocation.crewSource(); assertNotNull(actual);
                    assertNull(invocation.centralSource()); actual.requireInvocationCurrent();
                    assertFalse(MemoryWritePolicy.forContext(context).isEnabled());
                    witness.set(actual); calls.incrementAndGet();
                    return CoreToolResult.success("Observed exact source");
                } catch (RuntimeException | Error error) { failure.set(error); throw error; }
                finally { observed.countDown(); }
            }
        };
        CoreToolRegistry base = new CoreToolRegistry(Collections.singletonList(probe));
        CrewRole role = new CrewRole("fixture-profile","Fixture","coding","Observe source",Collections.singletonList("source_probe"),
                null,"Fixture profile",1,Collections.emptyList(),CrewProfile.WorkspaceMode.CONVERSATION_PROJECT);
        AtomicInteger turns = new AtomicInteger();
        CrewManager manager = new CrewManager(session,base,
                (bot,crew) -> CoreAgentRuntime.withCrewInvocationSource(base,bot,crew),
                (bot,tools,incoming) -> new CoreAgentLoop((history,prompt,specs,token) -> turns.getAndIncrement()==0
                        ? new ModelReply("",Collections.singletonList(new ModelReply.Call("same-call","source_probe",Collections.emptyMap())))
                        : new ModelReply("Done",Collections.emptyList()),tools,"",session,CorePromptBudget.standard(),null,
                        CoreAgentLoop.ToolResultBudget.UNBOUNDED,incoming,null),null);
        manager.configureProfiles(id -> role,Collections.singletonList("source_probe"));
        manager.configureCheckpoints(new CrewCheckpointCoordinator(context,session,bot -> role));
        MemoryWritePolicy policy = MemoryWritePolicy.forContext(context);
        boolean previous = policy.isEnabled();
        try {
            policy.setEnabledBlocking(false);
            CrewManager.Bot bot = manager.spawn(role.id,"Observe source",role.tools,"Fixture");
            assertTrue("Worker must reach the real instrumented dispatch", observed.await(15,TimeUnit.SECONDS));
            manager.stop(bot.id); bot.awaitTermination();
            if (failure.get()!=null) throw new AssertionError(failure.get());
            assertEquals(1,calls.get());
            CrewInvocationSource source = witness.get().source();
            assertEquals(session,source.executionSessionId); assertEquals(bot.id,source.botId);
            assertEquals(bot.missionId,source.missionId); assertEquals(bot.scopeIdentity(),source.scopeIdentity);
            assertEquals("same-call",source.callId); assertEquals("source_probe",source.toolName);
            assertFalse(source.cycleId.isEmpty()); assertTrue(source.checkpointPayloadSha256.matches("[0-9a-f]{64}"));
            assertThrows(IllegalStateException.class,() -> witness.get().requireInvocationCurrent());
            assertFalse(policy.isEnabled());
        } finally { manager.close(); policy.setEnabledBlocking(previous); }
    }
    @Test public void reusedWorkerLoopCannotCarryAnOldCycleGuardIntoItsNextInvocation() throws Exception {
        File files = fixture.newFilesDirectory(); fixture.localRunStore(files);
        Context context = new ContextWrapper(ApplicationProvider.getApplicationContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return files; }
        };
        String session = "crew-source-cycle-reuse";
        List<CrewInvocationWitness> witnesses = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Runnable> previousGuard = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger loopCreations = new AtomicInteger(), turns = new AtomicInteger();
        CoreTool probe = new CoreTool() {
            final ToolSpec spec = new ToolSpec("source_probe","test","Observe cycle","test",ToolSpec.Status.IMPLEMENTED,
                    Collections.emptyMap(),Collections.emptyList());
            @Override public ToolSpec declaration() { return spec; }
            @Override public CoreToolResult execute(Map<String,Object> args,CancellationToken token) {
                throw new AssertionError("Invocation context required");
            }
            @Override public CoreToolResult execute(Map<String,Object> args,CancellationToken token,ProgressListener progress,EffectInvocation invocation) {
                try {
                    CrewInvocationWitness current = invocation.crewSource(); assertNotNull(current); current.requireInvocationCurrent();
                    Runnable prior = previousGuard.get();
                    if (prior != null) {
                        // Isolate the cycle guard: a sealed owner alone must not make this oracle pass.
                        assertThrows(IllegalStateException.class,prior::run);
                        assertNotEquals(witnesses.get(0).source().cycleId,current.source().cycleId);
                    }
                    java.lang.reflect.Field field = CrewInvocationWitness.class.getDeclaredField("current");
                    field.setAccessible(true); previousGuard.set((Runnable)field.get(current));
                    witnesses.add(current);
                    return CoreToolResult.success("Cycle observed");
                } catch (ReflectiveOperationException error) { failure.set(error); throw new AssertionError(error); }
                catch (RuntimeException | Error error) { failure.set(error); throw error; }
            }
        };
        CoreToolRegistry base = new CoreToolRegistry(Collections.singletonList(probe));
        CrewRole role = new CrewRole("fixture-cycle-profile","Fixture","coding","Observe cycle",Collections.singletonList("source_probe"),
                null,"Fixture profile",1,Collections.emptyList(),CrewProfile.WorkspaceMode.CONVERSATION_PROJECT);
        CrewManager manager = new CrewManager(session,base,
                (bot,crew) -> CoreAgentRuntime.withCrewInvocationSource(base,bot,crew),
                (bot,tools,incoming) -> {
                    loopCreations.incrementAndGet();
                    return new CoreAgentLoop((history,prompt,specs,token) -> {
                        int turn = turns.getAndIncrement();
                        return turn % 2 == 0 ? new ModelReply("",Collections.singletonList(
                                new ModelReply.Call("cycle-call-"+turn,"source_probe",Collections.emptyMap())))
                                : new ModelReply("Done",Collections.emptyList());
                    },tools,"",session,CorePromptBudget.standard(),null,CoreAgentLoop.ToolResultBudget.UNBOUNDED,incoming,null);
                },null);
        manager.configureProfiles(id -> role,Collections.singletonList("source_probe"));
        manager.configureCheckpoints(new CrewCheckpointCoordinator(context,session,bot -> role));
        try {
            CrewManager.Bot bot = manager.spawn(role.id,"Observe first cycle",role.tools,"Fixture");
            awaitTermination(bot);
            if (failure.get()!=null) throw new AssertionError(failure.get());
            assertEquals(CrewManager.Status.DONE,bot.status()); assertEquals(1,witnesses.size());
            String firstCycle = bot.cycleId(); CancellationToken firstToken = bot.token;
            manager.sendUserMessage(bot.id,"Observe next cycle without repeating an old call");
            awaitTermination(bot);
            if (failure.get()!=null) throw new AssertionError(failure.get());
            assertEquals(CrewManager.Status.DONE,bot.status()); assertEquals(2,witnesses.size());
            assertEquals("The existing worker loop must actually be reused",1,loopCreations.get());
            assertNotSame(firstToken,bot.token); assertNotEquals(firstCycle,bot.cycleId());
            assertEquals(bot.id,witnesses.get(1).source().botId);
            assertEquals(bot.cycleId(),witnesses.get(1).source().cycleId);
            assertEquals("cycle-call-2",witnesses.get(1).source().callId);
            assertNotEquals(witnesses.get(0).source().checkpointPayloadSha256,witnesses.get(1).source().checkpointPayloadSha256);
        } finally { manager.close(); }
    }

    private static void awaitTermination(CrewManager.Bot bot) throws Exception {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try { bot.awaitTermination(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
        }).get(15,TimeUnit.SECONDS);
    }

}
