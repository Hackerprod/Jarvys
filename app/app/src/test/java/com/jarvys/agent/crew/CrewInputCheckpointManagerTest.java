package com.jarvys.agent.crew;

import static org.junit.Assert.*;
import com.jarvys.agent.*;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

public class CrewInputCheckpointManagerTest {
    private static final CoreToolRegistry EMPTY = new CoreToolRegistry(Collections.emptyList());
    private static final String HASH = String.join("", Collections.nCopies(64, "a"));

    @Test(timeout=15000) public void boundCorrectionPreservesExactTextAndRefreshesReusedLoopScope() throws Exception {
        AtomicInteger turns = new AtomicInteger(), constructions = new AtomicInteger(), signals = new AtomicInteger();
        AtomicReference<CoreAgentLoop> loop = new AtomicReference<>();
        try (CrewManager manager = manager((history, prompt, tools, token) -> reply("done " + turns.incrementAndGet()), loop, constructions)) {
            manager.configureCheckpoints(support(true));
            CrewManager.Bot bot = spawn(manager);
            awaitFinished(bot);
            CoreAgentLoop.CheckpointListener oldListener = (CoreAgentLoop.CheckpointListener) field(loop.get(), "checkpointListener");
            String admission = bot.cycleId();
            String exact = "  exact correction\n ñ \ud800  ";
            CrewInputBinding first = binding(bot, "first", exact);
            Object cycleLock = field(bot, "cycleLock");
            manager.setCheckpointCommittedListener(() -> {
                assertFalse(Thread.holdsLock(cycleLock)); assertFalse(Thread.holdsLock(bot));
                signals.incrementAndGet();
                throw new IllegalStateException("scheduler accelerator unavailable");
            });
            CrewMessage admitted = manager.sendUserMessageExact(bot.id, bot.token, exact, first);
            assertEquals(exact, admitted.text);
            awaitFinished(bot);
            assertEquals(CrewManager.Status.DONE, bot.status());
            CrewInputIncorporation proof = bot.checkpoint().incorporations.get("first");
            assertEquals(admission, proof.binding.admissionCycleId);
            assertEquals(bot.cycleId(), proof.firstIncorporationCycleId);
            assertNotEquals(admission, proof.firstIncorporationCycleId);
            assertTrue(bot.checkpoint().transcript.stream().anyMatch(turn -> "user".equals(turn.role) && exact.equals(turn.content)));
            CoreAgentLoop.Checkpoint beforeStale = bot.checkpoint();
            assertThrows(IllegalStateException.class, () -> oldListener.persist(CoreAgentLoop.Checkpoint.empty()));
            assertSame(beforeStale, bot.checkpoint());
            CrewInputBinding next = binding(bot, "next", "  next  ");
            manager.sendUserMessageExact(bot.id, bot.token, "  next  ", next);
            awaitFinished(bot);
            assertEquals(1, constructions.get());
            assertTrue(proof.samePayload(bot.checkpoint().incorporations.get("first")));
            assertEquals(bot.cycleId(), bot.checkpoint().incorporations.get("next").firstIncorporationCycleId);
            assertTrue(signals.get() > 0);
            assertEquals(3, turns.get());
        }
    }

    @Test(timeout=15000) public void terminalCleanupAdmissionRetainsOldCycleWhileNextCycleIncorporates() throws Exception {
        CountDownLatch cleaning = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger ends = new AtomicInteger();
        CoreAgentLoop.Model model = (history, prompt, tools, token) -> reply("done");
        try (CrewManager manager = manager(model, new AtomicReference<>(), new AtomicInteger())) {
            manager.configure(EMPTY, (bot, owner) -> EMPTY, (bot, tools, inbox) -> loop(model, tools, inbox),
                    new CrewManager.WorkerLifecycle() {
                        @Override public void start(CrewManager.Bot bot) { }
                        @Override public void end(CrewManager.Bot bot) {
                            if (ends.incrementAndGet() == 1) { cleaning.countDown(); await(release); }
                        }
                    });
            CrewManager.Bot bot = spawn(manager);
            try {
                await(cleaning);
                String originalCycle = bot.cycleId();
                CrewInputBinding binding = binding(bot, "deferred", "  follow up\n ");
                manager.sendUserMessageExact(bot.id, bot.token, "  follow up\n ", binding);
                assertEquals(originalCycle, bot.cycleId());
                release.countDown(); awaitFinished(bot);
                CrewInputIncorporation proof = bot.checkpoint().incorporations.get("deferred");
                assertEquals(originalCycle, proof.binding.admissionCycleId);
                assertEquals(bot.cycleId(), proof.firstIncorporationCycleId);
                assertNotEquals(originalCycle, proof.firstIncorporationCycleId);
            } finally { release.countDown(); }
        }
    }

    @Test(timeout=15000) public void explicitResumeQueuesExactCorrectionBeforeFirstResumedTurn() throws Exception {
        String exact = "  resume\nwith Unicode ñ  ";
        AtomicInteger turns = new AtomicInteger();
        try (CrewManager manager = manager((history, prompt, tools, token) -> {
            assertTrue(history.stream().anyMatch(turn -> "user".equals(turn.role) && exact.equals(turn.content)));
            turns.incrementAndGet(); return reply("done");
        }, new AtomicReference<>(), new AtomicInteger())) {
            manager.configureCheckpoints(support(true));
            CrewManager.Bot bot = restore(manager);
            String admission = bot.cycleId();
            manager.resumeExact(bot.id, bot.token, exact, binding(bot, "resume-input", exact));
            awaitFinished(bot);
            assertEquals(1, turns.get());
            CrewInputIncorporation proof = bot.checkpoint().incorporations.get("resume-input");
            assertEquals(admission, proof.binding.admissionCycleId);
            assertEquals(bot.cycleId(), proof.firstIncorporationCycleId);
            assertNotEquals(admission, proof.firstIncorporationCycleId);
            assertEquals(CrewInputIncorporation.transportSha256(exact), proof.transportSha256);
        }
    }

    @Test(timeout=15000) public void absentOrSkippedSupportConsumesGenericInputWithoutCheckpointSignal() throws Exception {
        for (boolean skipped : new boolean[] {false, true}) {
            AtomicInteger signals = new AtomicInteger();
            try (CrewManager manager = manager((history, prompt, tools, token) -> reply("done"), new AtomicReference<>(), new AtomicInteger())) {
                if (skipped) manager.configureCheckpoints(support(false));
                manager.setCheckpointCommittedListener(signals::incrementAndGet);
                CrewManager.Bot bot = spawn(manager); awaitFinished(bot);
                CrewMessage message = manager.sendUserMessage(bot.id, "  generic input  ");
                awaitFinished(bot);
                assertEquals("generic input", message.text);
                assertTrue(manager.messageBus().pending(bot.id).isEmpty());
                assertTrue(bot.checkpoint().appliedIncomingIds.contains(message.id));
                assertTrue(bot.checkpoint().incorporations.isEmpty());
                assertEquals(0, signals.get());
            }
        }
    }

    @Test(timeout=15000) public void mismatchOrWrongCycleFailsBeforeAdmissionAndDeterministicIdNeverReplays() throws Exception {
        AtomicInteger turns = new AtomicInteger();
        try (CrewManager manager = manager((history, prompt, tools, token) -> { turns.incrementAndGet(); return reply("done"); },
                new AtomicReference<>(), new AtomicInteger())) {
            CrewManager.Bot bot = spawn(manager); awaitFinished(bot);
            CrewInputBinding binding = binding(bot, "exact-id", " exact ");
            assertThrows(IllegalArgumentException.class, () -> manager.sendUserMessageExact(bot.id, bot.token, "exact", binding));
            assertThrows(IllegalStateException.class, () -> manager.sendUserMessageExact(bot.id, CancellationToken.cancellable(), " exact ", binding));
            assertFalse(manager.messageBus().snapshot().stream().anyMatch(message -> message.id.equals(binding.mailboxMessageId)));
            manager.sendUserMessageExact(bot.id, bot.token, " exact ", binding); awaitFinished(bot);
            CrewInputBinding repeatedId = binding(bot, "exact-id", " exact ");
            assertThrows(IllegalStateException.class, () -> manager.sendUserMessageExact(bot.id, bot.token, " exact ", repeatedId));
            assertEquals(2, turns.get());
            assertEquals(1, manager.messageBus().snapshot().stream().filter(message -> message.id.equals(binding.mailboxMessageId)).count());
        }
    }

    @Test(timeout=15000) public void sameCycleStoppedCallbackRetainsHistoricalEvidence() throws Exception {
        AtomicReference<CoreAgentLoop> loop = new AtomicReference<>();
        AtomicInteger modelCalls = new AtomicInteger();
        CountDownLatch liveModel = new CountDownLatch(1), releaseModel = new CountDownLatch(1);
        try (CrewManager manager = manager((history, prompt, tools, token) -> {
            if (modelCalls.incrementAndGet() == 1) return reply("initial result");
            liveModel.countDown();
            // Keep the cancelled invocation alive until the test checks its historical callback.
            boolean interrupted = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            try {
                while (releaseModel.getCount() > 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) throw new AssertionError("Cancelled model was not released");
                    try { assertTrue(releaseModel.await(remaining, TimeUnit.NANOSECONDS)); }
                    catch (InterruptedException cancellation) { interrupted = true; }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
            token.throwIfCancelled();
            throw new AssertionError("Cancelled model must not produce another effect");
        }, loop, new AtomicInteger())) {
            manager.configureCheckpoints(support(true));
            CrewManager.Bot bot = spawn(manager); awaitFinished(bot);
            String exact = " correction before STOP ";
            manager.sendUserMessageExact(bot.id, bot.token, exact, binding(bot, "stop-input", exact));
            try {
                await(liveModel);
                String cycle = bot.cycleId();
                CancellationToken token = bot.token;
                CoreAgentLoop.Checkpoint saved = bot.checkpoint();
                assertNotNull(saved.incorporations.get("stop-input"));
                CoreAgentLoop.CheckpointListener listener =
                        (CoreAgentLoop.CheckpointListener) field(loop.get(), "checkpointListener");
                assertTrue("STOP must target a live invocation", manager.stop(bot.id));
                assertTrue(token.isCancellationRequested());
                assertEquals(CrewManager.Status.STOPPED, bot.status());
                listener.persist(saved);
                assertSame(token, bot.token);
                assertEquals(cycle, bot.cycleId());
                assertSame(saved, bot.checkpoint());
                assertEquals(cycle, saved.incorporations.get("stop-input").firstIncorporationCycleId);
                assertEquals(2, modelCalls.get());
            } finally { releaseModel.countDown(); }
            awaitFinished(bot);
            assertEquals(CrewManager.Status.STOPPED, bot.status());
            assertEquals(2, modelCalls.get());
        } finally { releaseModel.countDown(); }
    }

    private static CrewInputBinding binding(CrewManager.Bot bot, String id, String text) {
        return CrewInputBinding.create(CrewInputBinding.SourceKind.NATIVE_CORRECT, "control-" + id, "owner", "workspace",
                "input-chat", "assignment", bot.missionId, bot.id, id, HASH, bot.cycleId(), text);
    }
    private static CrewManager manager(CoreAgentLoop.Model model, AtomicReference<CoreAgentLoop> observed, AtomicInteger constructions) {
        return new CrewManager("input-chat", EMPTY, (bot, owner) -> EMPTY, (bot, tools, inbox) -> {
            constructions.incrementAndGet(); CoreAgentLoop loop = loop(model, tools, inbox); observed.set(loop); return loop;
        }, null);
    }
    private static CoreAgentLoop loop(CoreAgentLoop.Model model, CoreToolRegistry tools, CoreAgentLoop.TurnContextProvider inbox) {
        return new CoreAgentLoop(model, tools, "", "input-chat", CorePromptBudget.standard(), null,
                CoreAgentLoop.ToolResultBudget.UNBOUNDED, inbox, null);
    }
    private static CrewManager.Bot spawn(CrewManager manager) {
        return manager.spawnForMission("input-mission", "Original instructions", "custom", "Task",
                Collections.emptyList(), "Worker", CrewMissionAccess.STANDARD, "Input task");
    }
    private static ModelReply reply(String text) { return new ModelReply(text, Collections.emptyList()); }
    private static CrewManager.CheckpointSupport support(boolean canPersist) {
        return new CrewManager.CheckpointSupport() {
            @Override public boolean canPersist(CrewManager.Bot bot) { return canPersist; }
            @Override public void persist(CrewManager.Bot bot, List<CrewMessage> messages, List<CrewMessage> pending) {
                if (!canPersist) throw new AssertionError("Skipped checkpoint support called");
            }
            @Override public CrewManager.ResumePlan reconcile(CrewManager.Bot bot) { return new CrewManager.ResumePlan(bot.role, "Reconciled"); }
        };
    }
    private static CrewManager.Bot restore(CrewManager manager) {
        CrewProfile profile = new CrewProfile("coding", 1, "Coding", "Project work", "Mission",
                Collections.emptyList(), Collections.emptyList(), CrewProfile.WorkspaceMode.CONVERSATION_PROJECT);
        CrewBotSnapshot snapshot = new CrewBotSnapshot("saved-worker", "coding", "Coding", "Worker", "coding", "Task",
                "STOPPED", "", "Previous evidence", "", Collections.emptyList(), 1L, 2L, true, "", true,
                "", "", 0L, UUID.randomUUID().toString(), null);
        CrewMissionSnapshot mission = new CrewMissionSnapshot("input-mission", "input-chat", "old-process", "Mission",
                "STOPPED", "", 1L, 2L, Collections.singletonList(snapshot), Collections.emptyList());
        return manager.restoreBot(mission, snapshot, profile.resolveRole(Collections.emptyList(), Collections.emptyList()),
                CoreAgentLoop.Checkpoint.empty(), new JSONObject(), "scope", Collections.emptyList(), 1L,
                Collections.emptyList(), Collections.emptyList(), "");
    }
    private static Object field(Object object, String name) throws Exception {
        java.lang.reflect.Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue("Latch timed out", latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private static void awaitFinished(CrewManager.Bot bot) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = new Thread(() -> { try { bot.awaitTermination(); done.countDown(); } catch (InterruptedException ignored) { } });
        waiter.setDaemon(true); waiter.start();
        try { await(done); } finally { waiter.interrupt(); }
    }
}
