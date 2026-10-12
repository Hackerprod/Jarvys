package com.jarvys.agent.crew;

import static org.junit.Assert.*;

import com.jarvys.agent.CancellationToken;
import com.jarvys.agent.CoreAgentLoop;
import com.jarvys.agent.CorePromptBudget;
import com.jarvys.agent.CoreToolRegistry;
import com.jarvys.agent.ModelReply;
import com.jarvys.agent.ProviderTransportException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

public class CrewResultCaptureTest {
    private static final CoreToolRegistry EMPTY = new CoreToolRegistry(Collections.emptyList());

    @Test(timeout = 15000L) public void initialAndFastQuiescentCyclesDeliverExactlyOneDetachedResultEach() throws Exception {
        AtomicInteger turns = new AtomicInteger();
        List<CrewResultEvidence> results = new CopyOnWriteArrayList<>();
        try (CrewManager manager = manager((history, prompt, tools, token) -> reply("result-" + turns.incrementAndGet()))) {
            manager.configureResultEvidenceListener(results::add);
            CrewManager.Bot bot = spawn(manager);
            List<String> identities = new ArrayList<>();
            for (int cycle = 1; cycle <= 8; cycle++) {
                awaitFinished(bot);
                identities.add(bot.cycleId());
                assertEquals(bot.cycleId(), UUID.fromString(bot.cycleId()).toString());
                assertEquals(cycle, results.size());
                CrewResultEvidence evidence = results.get(cycle - 1);
                assertEquals(bot.cycleId(), evidence.cycleId);
                assertEquals("DONE", evidence.reportedStatus);
                assertEquals("result-" + cycle, evidence.resultText);
                assertSame(evidence, bot.resultEvidence());
                assertEquals(evidence.contentSha256, manager.missionSnapshots().get(0).bots.get(0).resultEvidence.contentSha256);
                if (cycle < 8) manager.sendUserMessage(bot.id, "Next correction " + cycle);
            }
            assertEquals(8, new HashSet<>(identities).size());
            assertEquals(8, results.stream().map(result -> result.evidenceId).distinct().count());
            assertEquals("result-1", results.get(0).resultText);
        }
    }

    @Test(timeout = 15000L) public void deferredReanimationCannotReplaceOriginalTerminalStatusOrCycle() throws Exception {
        CountDownLatch firstCleanup = new CountDownLatch(1), releaseCleanup = new CountDownLatch(1);
        AtomicInteger turns = new AtomicInteger(), ends = new AtomicInteger();
        List<CrewResultEvidence> results = new CopyOnWriteArrayList<>();
        CoreAgentLoop.Model model = (history, prompt, tools, token) -> reply("cycle " + turns.incrementAndGet());
        try (CrewManager manager = manager(model)) {
            configure(manager, model, new CrewManager.WorkerLifecycle() {
                @Override public void start(CrewManager.Bot bot) { }
                @Override public void end(CrewManager.Bot bot) {
                    if (ends.incrementAndGet() == 1) {
                        firstCleanup.countDown();
                        awaitLatch(releaseCleanup, "Cleanup never released");
                    }
                }
            });
            manager.configureResultEvidenceListener(results::add);
            CrewManager.Bot bot = spawn(manager);
            try {
                awaitLatch(firstCleanup, "Worker did not reach terminal cleanup");
                String original = bot.cycleId();
                CancellationToken originalToken = bot.token;
                manager.sendUserMessage(bot.id, "Follow up during cleanup");
                assertEquals(CrewManager.Status.QUEUED, bot.status());
                releaseCleanup.countDown();
                awaitFinished(bot);
                assertEquals(2, results.size());
                assertEquals(original, results.get(0).cycleId);
                assertEquals("DONE", results.get(0).reportedStatus);
                assertEquals("cycle 1", results.get(0).resultText);
                assertNotEquals(original, bot.cycleId());
                assertNotSame(originalToken, bot.token);
                assertEquals(bot.cycleId(), results.get(1).cycleId);
                assertEquals("cycle 2", results.get(1).resultText);
            } finally { releaseCleanup.countDown(); }
        }
    }

    @Test(timeout = 15000L) public void resumeKeepsRestoredIdentityUntilAdmissionAndDoesNotRelabelOldOutput() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        List<CrewResultEvidence> results = new CopyOnWriteArrayList<>();
        String savedCycle = UUID.randomUUID().toString();
        CrewResultEvidence saved = CrewResultEvidence.create("result-chat", "result-mission", "saved-worker",
                savedCycle, "PARTIAL", "Previous cycle evidence", 20L);
        try (CrewManager manager = manager((history, prompt, tools, token) -> {
            started.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); token.throwIfCancelled(); }
            token.throwIfCancelled();
            return reply("must not be reported");
        })) {
            manager.configureCheckpoints(checkpoints());
            manager.configureResultEvidenceListener(results::add);
            CrewManager.Bot bot = restore(manager, savedCycle, saved);
            assertEquals(savedCycle, bot.cycleId());
            assertSame(saved, bot.resultEvidence());
            assertTrue(results.isEmpty());
            manager.resume(bot.id);
            try {
                awaitLatch(started, "Resumed model did not start");
                String freshCycle = bot.cycleId();
                assertNotEquals(savedCycle, freshCycle);
                assertNull(bot.resultEvidence());
                assertEquals("Previous cycle evidence", bot.result());
                assertTrue(manager.stop(bot.id));
                release.countDown();
                awaitFinished(bot);
                assertEquals(1, results.size());
                assertEquals(freshCycle, results.get(0).cycleId);
                assertEquals("STOPPED", results.get(0).reportedStatus);
                assertEquals("", results.get(0).resultText);
                assertEquals("Previous cycle evidence", bot.result());
            } finally { release.countDown(); }
        }
    }

    @Test(timeout = 15000L) public void legacyRestoreStaysUnknownUntilExplicitResumeCreatesFreshIdentity() throws Exception {
        List<CrewResultEvidence> results = new CopyOnWriteArrayList<>();
        try (CrewManager manager = manager((history, prompt, tools, token) -> reply("new retained review"))) {
            manager.configureCheckpoints(checkpoints());
            manager.configureResultEvidenceListener(results::add);
            CrewManager.Bot bot = restore(manager, "", null);
            assertEquals("", bot.cycleId());
            assertNull(bot.resultEvidence());
            assertTrue(results.isEmpty());
            manager.resume(bot.id);
            awaitFinished(bot);
            assertFalse(bot.cycleId().isEmpty());
            assertEquals(1, results.size());
            assertEquals(bot.cycleId(), results.get(0).cycleId);
            assertEquals("new retained review", results.get(0).resultText);
        }
    }

    @Test(timeout = 15000L) public void failedResumeReconciliationDoesNotCreateEvidenceOrFreshCycle() {
        try (CrewManager manager = manager((history, prompt, tools, token) -> { throw new AssertionError("No model turn"); })) {
            String original = UUID.randomUUID().toString();
            CrewManager.Bot bot = restore(manager, original, null);
            List<CrewResultEvidence> results = new CopyOnWriteArrayList<>();
            manager.configureResultEvidenceListener(results::add);
            manager.configureCheckpoints(new CrewManager.CheckpointSupport() {
                @Override public void persist(CrewManager.Bot value, List<CrewMessage> messages, List<CrewMessage> pending) { }
                @Override public CrewManager.ResumePlan reconcile(CrewManager.Bot value) { throw new IllegalStateException("Blocked"); }
            });
            assertThrows(IllegalStateException.class, () -> manager.resume(bot.id));
            assertEquals(original, bot.cycleId());
            assertTrue(results.isEmpty());
        }
    }

    @Test(timeout = 15000L) public void partialAndFailedOutcomesRetainTheirExactTerminalReport() throws Exception {
        for (String expected : new String[]{"PARTIAL", "FAILED"}) {
            List<CrewResultEvidence> results = new CopyOnWriteArrayList<>();
            try (CrewManager manager = manager((history, prompt, tools, token) -> {
                if ("PARTIAL".equals(expected)) throw new ProviderTransportException("Unavailable", new IOException("timeout"));
                throw new IllegalStateException("synthetic execution failure");
            })) {
                manager.configureResultEvidenceListener(results::add);
                CrewManager.Bot bot = spawn(manager);
                awaitFinished(bot);
                assertEquals(expected, bot.status().name());
                assertEquals(1, results.size());
                assertEquals(expected, results.get(0).reportedStatus);
                assertEquals(bot.result(), results.get(0).resultText);
                assertEquals(bot.finishedAtMillis(), results.get(0).producedAtMillis);
            }
        }
    }

    @Test(timeout = 15000L) public void explicitReportDoneTextWinsOverTheLaterProviderTail() throws Exception {
        AtomicReference<CrewManager> owner = new AtomicReference<>();
        List<CrewResultEvidence> results = new CopyOnWriteArrayList<>();
        try (CrewManager manager = manager((history, prompt, tools, token) -> {
            CrewManager current = owner.get();
            current.reportDone(current.bots().get(0), "  exact worker report  ", Collections.emptyList());
            return reply("later provider tail");
        })) {
            owner.set(manager);
            manager.configureResultEvidenceListener(results::add);
            CrewManager.Bot bot = spawn(manager);
            awaitFinished(bot);
            assertEquals(1, results.size());
            assertEquals("DONE", results.get(0).reportedStatus);
            assertEquals("exact worker report", results.get(0).resultText);
            assertEquals(bot.result(), results.get(0).resultText);
        }
    }

    @Test(timeout = 15000L) public void stopAfterFreshOutputRetainsOnlyThisCyclesProducedText() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicBoolean pending = new AtomicBoolean(true);
        List<CrewResultEvidence> results = new CopyOnWriteArrayList<>();
        CoreAgentLoop.Model model = (history, prompt, tools, token) -> reply("fresh output before owned work ended");
        try (CrewManager manager = manager(model)) {
            manager.configure(EMPTY, (bot, owner) -> EMPTY, (bot, tools, inbox) -> loop(model, tools, inbox),
                    new CrewManager.WorkerLifecycle() {
                        @Override public void start(CrewManager.Bot bot) {
                            manager.registerOwnedWork(bot, new CrewManager.OwnedWork() {
                                @Override public void cancelAndAwait() { pending.set(false); }
                                @Override public boolean pending() { return pending.get(); }
                            });
                        }
                        @Override public void end(CrewManager.Bot bot) { }
                    }, null, snapshot -> {
                        if (snapshot.bots.stream().anyMatch(bot -> "WAITING".equals(bot.status))) waiting.countDown();
                    });
            manager.configureResultEvidenceListener(results::add);
            CrewManager.Bot bot = spawn(manager);
            awaitLatch(waiting, "Worker did not wait for owned work");
            assertTrue(manager.stop(bot.id));
            awaitFinished(bot);
            assertEquals(1, results.size());
            assertEquals("STOPPED", results.get(0).reportedStatus);
            assertEquals("fresh output before owned work ended", results.get(0).resultText);
            assertFalse(pending.get());
        }
    }

    @Test(timeout = 15000L) public void callbackFailuresAreObservableAndNeverCreateFailedOutcomesOrReplayWork() throws Exception {
        AtomicInteger turns = new AtomicInteger(), calls = new AtomicInteger(), failures = new AtomicInteger();
        AtomicReference<CrewResultEvidence> last = new AtomicReference<>();
        try (CrewManager manager = manager((history, prompt, tools, token) -> reply("done " + turns.incrementAndGet()))) {
            manager.configureResultEvidenceListener(new CrewManager.ResultEvidenceListener() {
                @Override public void onResult(CrewResultEvidence evidence) {
                    calls.incrementAndGet();
                    last.set(evidence);
                    throw new IllegalStateException("storage unavailable");
                }
                @Override public void onDeliveryFailure(CrewResultEvidence evidence, RuntimeException failure) {
                    failures.incrementAndGet();
                    assertSame(last.get(), evidence);
                    throw new IllegalStateException("diagnostic sink also unavailable");
                }
            });
            CrewManager.Bot bot = spawn(manager);
            awaitFinished(bot);
            assertEquals(CrewManager.Status.DONE, bot.status());
            assertEquals(1, turns.get());
            assertEquals(1, calls.get());
            assertEquals(1, failures.get());
            assertEquals("storage unavailable", manager.resultEvidenceDeliveryFailures().get(last.get().evidenceId));
            assertFalse(manager.messageBus().snapshot().stream().anyMatch(message -> message.text.contains("Bot Worker failed")));
            manager.sendUserMessage(bot.id, "An explicitly requested follow-up");
            awaitFinished(bot);
            assertEquals(CrewManager.Status.DONE, bot.status());
            assertEquals(2, turns.get());
            assertEquals(2, calls.get());
            assertEquals(2, failures.get());
            assertEquals(2, manager.resultEvidenceDeliveryFailures().size());
        }
    }

    @Test(timeout = 15000L) public void exactDeliveryRunsOutsideCycleLockAndAllowsConcurrentControl() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (CrewManager manager = manager((history, prompt, tools, token) -> reply("done"))) {
            manager.configureResultEvidenceListener(evidence -> {
                CountDownLatch acquired = new CountDownLatch(1);
                Thread control = new Thread(() -> {
                    try {
                        CrewManager.Bot bot = manager.bot(evidence.botId);
                        assertFalse(manager.stopExact(bot.id, bot.token));
                    } catch (Throwable issue) { failure.set(issue); }
                    finally { acquired.countDown(); }
                });
                control.setDaemon(true);
                control.start();
                try { if (!acquired.await(3, TimeUnit.SECONDS)) failure.set(new AssertionError("Delivery held cycleLock")); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); failure.set(interrupted); }
            });
            CrewManager.Bot bot = spawn(manager);
            awaitFinished(bot);
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertEquals(CrewManager.Status.DONE, bot.status());
        }
    }

    private static CrewManager manager(CoreAgentLoop.Model model) {
        return new CrewManager("result-chat", EMPTY, (bot, owner) -> EMPTY,
                (bot, tools, inbox) -> loop(model, tools, inbox), null);
    }
    private static CoreAgentLoop loop(CoreAgentLoop.Model model, CoreToolRegistry tools, CoreAgentLoop.TurnContextProvider inbox) {
        return new CoreAgentLoop(model, tools, "", "result-chat", CorePromptBudget.standard(), null,
                CoreAgentLoop.Limits.UNBOUNDED, inbox, null);
    }
    private static void configure(CrewManager manager, CoreAgentLoop.Model model, CrewManager.WorkerLifecycle lifecycle) {
        manager.configure(EMPTY, (bot, owner) -> EMPTY, (bot, tools, inbox) -> loop(model, tools, inbox), lifecycle);
    }
    private static ModelReply reply(String text) { return new ModelReply(text, Collections.emptyList()); }
    private static CrewManager.Bot spawn(CrewManager manager) {
        return manager.spawnForMission("result-mission", "Original instructions", "custom", "Task",
                Collections.emptyList(), "Worker", CrewMissionAccess.STANDARD, "Result task");
    }
    private static CrewManager.CheckpointSupport checkpoints() {
        return new CrewManager.CheckpointSupport() {
            @Override public void persist(CrewManager.Bot bot, List<CrewMessage> messages, List<CrewMessage> pending) { }
            @Override public CrewManager.ResumePlan reconcile(CrewManager.Bot bot) { return new CrewManager.ResumePlan(bot.role, "Retained evidence was reconciled"); }
        };
    }
    private static CrewManager.Bot restore(CrewManager manager, String cycleId, CrewResultEvidence evidence) {
        CrewProfile profile = new CrewProfile("coding", 1, "Coding", "Project work", "Mission",
                Collections.emptyList(), Collections.emptyList(), CrewProfile.WorkspaceMode.CONVERSATION_PROJECT);
        CrewBotSnapshot snapshot = new CrewBotSnapshot("saved-worker", "coding", "Coding", "Worker", "coding", "Task",
                "STOPPED", "", "Previous cycle evidence", "", Collections.emptyList(), 1L, 2L, true, "", true,
                "", "", 0L, cycleId, evidence);
        CrewMissionSnapshot mission = new CrewMissionSnapshot("result-mission", "result-chat", "old-process", "Mission",
                "STOPPED", "", 1L, 2L, Collections.singletonList(snapshot), Collections.emptyList());
        return manager.restoreBot(mission, snapshot, profile.resolveRole(Collections.emptyList(), Collections.emptyList()),
                CoreAgentLoop.Checkpoint.empty(), new JSONObject(), "scope", Collections.emptyList(), 1L,
                Collections.emptyList(), Collections.emptyList(), "");
    }
    private static void awaitLatch(CountDownLatch latch, String message) {
        try { assertTrue(message, latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private static void awaitFinished(CrewManager.Bot bot) throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try { bot.awaitTermination(); ended.countDown(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        waiter.setDaemon(true);
        waiter.start();
        try { assertTrue("Worker did not terminate", ended.await(5, TimeUnit.SECONDS)); }
        finally { waiter.interrupt(); }
    }
}
