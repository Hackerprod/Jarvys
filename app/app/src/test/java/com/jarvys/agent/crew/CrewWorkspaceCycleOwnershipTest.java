package com.jarvys.agent.crew;

import static org.junit.Assert.*;

import com.jarvys.agent.CancellationToken;
import com.jarvys.agent.CoreAgentLoop;
import com.jarvys.agent.CorePromptBudget;
import com.jarvys.agent.CoreToolRegistry;
import com.jarvys.agent.ModelReply;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;

/** Proposed focused regressions for typed correction and stale cancellation; not yet run. */
public class CrewWorkspaceCycleOwnershipTest {
    private static final CoreToolRegistry EMPTY = new CoreToolRegistry(Collections.emptyList());

    @Test public void checkpointCorrectionIsDurableAndGenuineBeforeFirstResumedTurn() throws Exception {
        String correction = "  Correct only the selected workspace.\nKeep original evidence.  ";
        AtomicBoolean durablyQueued = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        try (CrewManager manager = manager((history, prompt, specs, token) -> {
            assertTrue("Correction must persist before inference", durablyQueued.get());
            assertTrue(history.stream().anyMatch(turn -> "user".equals(turn.role) && correction.equals(turn.content)));
            calls.incrementAndGet();
            return done();
        })) {
            CrewManager.Bot bot = restore(manager);
            manager.configureCheckpoints(new CrewManager.CheckpointSupport() {
                @Override public void persist(CrewManager.Bot value, List<CrewMessage> messages, List<CrewMessage> pending) {
                    if (pending.stream().anyMatch(message -> "user".equals(message.from)
                            && message.type == CrewMessage.Type.USER && correction.equals(message.text))) durablyQueued.set(true);
                }
                @Override public CrewManager.ResumePlan reconcile(CrewManager.Bot value) {
                    return new CrewManager.ResumePlan(value.role, "Read-only reconciliation observation");
                }
            });
            manager.resumeWithUserMessage(bot.id, correction);
            await(bot);
            assertEquals(1, calls.get());
            assertEquals(CrewManager.Status.DONE, bot.status());
            assertEquals(1L, manager.messageBus().snapshot().stream().filter(message ->
                    message.type == CrewMessage.Type.USER && correction.equals(message.text)).count());
        }
    }

    @Test public void failedCorrectionCheckpointDoesNotLaunchResumedModel() {
        AtomicInteger calls = new AtomicInteger();
        try (CrewManager manager = manager((history, prompt, specs, token) -> { calls.incrementAndGet(); return done(); })) {
            CrewManager.Bot bot = restore(manager);
            manager.configureCheckpoints(new CrewManager.CheckpointSupport() {
                @Override public void persist(CrewManager.Bot value, List<CrewMessage> messages, List<CrewMessage> pending) {
                    throw new IllegalStateException("Synthetic checkpoint failure");
                }
                @Override public CrewManager.ResumePlan reconcile(CrewManager.Bot value) {
                    return new CrewManager.ResumePlan(value.role, "Read-only reconciliation observation");
                }
            });
            assertThrows(IllegalStateException.class, () -> manager.resumeWithUserMessage(bot.id, "Exact user correction"));
            assertEquals(0, calls.get());
            assertTrue(bot.requiresExplicitResume());
            assertEquals(CrewManager.Status.INTERRUPTED, bot.status());
            assertTrue(manager.messageBus().pending(bot.id).stream().anyMatch(message ->
                    message.type == CrewMessage.Type.USER && "Exact user correction".equals(message.text)));
        }
    }

    @Test public void staleParentCancellationCannotStopDoneBotFollowupCycle() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch followupStarted = new CountDownLatch(1), release = new CountDownLatch(1);
        try (CrewManager manager = manager((history, prompt, specs, token) -> {
            if (calls.incrementAndGet() > 1) {
                followupStarted.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Follow-up was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    token.throwIfCancelled();
                    throw new AssertionError(interrupted);
                }
            }
            return done();
        })) {
            CrewManager.Bot bot = manager.spawnForMission("mission-A", "Original request", "custom", "First task",
                    Collections.emptyList(), "Worker", CrewMissionAccess.STANDARD, "Workspace title");
            CancellationToken originalToken = bot.token;
            await(bot);
            assertTrue(bot.isQuiescent());
            manager.sendUserMessage(bot.id, "Now apply this user-requested correction");
            try {
                assertTrue(followupStarted.await(5, TimeUnit.SECONDS));
                assertNotSame("A distinct cycle needs distinct cancellation ownership", originalToken, bot.token);
                assertFalse(manager.stopExact(bot.id, originalToken));
                assertFalse(bot.token.isCancellationRequested());
                release.countDown();
                await(bot);
                assertEquals(CrewManager.Status.DONE, bot.status());
                assertEquals(2, calls.get());
            } finally { release.countDown(); }
        }
    }

    @Test public void correctionDuringTerminalCleanupAlsoGetsIndependentCycleOwnership() throws Exception {
        AtomicInteger calls = new AtomicInteger(), ended = new AtomicInteger();
        CountDownLatch firstCleanup = new CountDownLatch(1), releaseCleanup = new CountDownLatch(1);
        CountDownLatch followupStarted = new CountDownLatch(1), releaseFollowup = new CountDownLatch(1);
        CoreAgentLoop.Model model = (history, prompt, specs, token) -> {
            if (calls.incrementAndGet() > 1) {
                followupStarted.countDown();
                awaitLatch(releaseFollowup, "Follow-up was not released");
            }
            return done();
        };
        try (CrewManager manager = manager(model)) {
            manager.configure(EMPTY, (bot, owner) -> EMPTY,
                    (bot, tools, inbox) -> new CoreAgentLoop(model, tools, "", "workspace-test", CorePromptBudget.standard(),
                            null, CoreAgentLoop.ToolResultBudget.UNBOUNDED, inbox, null), new CrewManager.WorkerLifecycle() {
                        @Override public void start(CrewManager.Bot bot) { }
                        @Override public void end(CrewManager.Bot bot) {
                            if (ended.incrementAndGet() == 1) {
                                firstCleanup.countDown();
                                awaitLatch(releaseCleanup, "Lifecycle cleanup was not released");
                            }
                        }
                    });
            CrewManager.Bot bot = manager.spawnForMission("mission-A", "Original request", "custom", "First task",
                    Collections.emptyList(), "Worker", CrewMissionAccess.STANDARD, "Workspace title");
            try {
                assertTrue(firstCleanup.await(5, TimeUnit.SECONDS));
                CancellationToken originalToken = bot.token;
                assertEquals(CrewManager.Status.DONE, bot.status());
                assertFalse(bot.isQuiescent());
                manager.sendUserMessage(bot.id, "Correction arriving during terminal cleanup");
                releaseCleanup.countDown();
                assertTrue(followupStarted.await(5, TimeUnit.SECONDS));
                assertNotSame("A deferred reanimation needs distinct cancellation ownership", originalToken, bot.token);
                assertFalse(manager.stopExact(bot.id, originalToken));
                assertFalse(bot.token.isCancellationRequested());
                releaseFollowup.countDown();
                await(bot);
                assertEquals(2, calls.get());
                assertEquals(CrewManager.Status.DONE, bot.status());
            } finally { releaseCleanup.countDown(); releaseFollowup.countDown(); }
        }
    }

    private static void awaitLatch(CountDownLatch latch, String message) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError(message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static CrewManager.Bot restore(CrewManager manager) {
        CrewProfile profile = new CrewProfile("coding", 1, "Coding", "Project work", "Mission",
                Collections.emptyList(), Collections.emptyList(), CrewProfile.WorkspaceMode.CONVERSATION_PROJECT);
        CrewBotSnapshot saved = new CrewBotSnapshot("bot-one", "coding", "Coding", "Worker", "coding", "mission",
                "INTERRUPTED", "", "", "", Collections.emptyList(), 1L, 0L);
        CrewMissionSnapshot mission = new CrewMissionSnapshot("mission-A", "workspace-test", "old-process", "Mission",
                "INTERRUPTED", "", 1L, 0L, Collections.singletonList(saved), Collections.emptyList());
        return manager.restoreBot(mission, saved, profile.resolveRole(Collections.emptyList(), Collections.emptyList()),
                CoreAgentLoop.Checkpoint.empty(), new JSONObject(), "scope-one", Collections.emptyList(), 1L,
                Collections.emptyList(), Collections.emptyList(), "");
    }

    private static ModelReply done() { return new ModelReply("Verified test result", Collections.emptyList()); }
    private static CrewManager manager(CoreAgentLoop.Model model) {
        return new CrewManager("workspace-test", EMPTY, (bot, manager) -> EMPTY,
                (bot, tools, inbox) -> new CoreAgentLoop(model, tools, "", "workspace-test", CorePromptBudget.standard(),
                        null, CoreAgentLoop.ToolResultBudget.UNBOUNDED, inbox, null), null);
    }
    private static void await(CrewManager.Bot bot) throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try { bot.awaitTermination(); ended.countDown(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, "workspace-cycle-test-await");
        waiter.setDaemon(true);
        waiter.start();
        try { assertTrue("Worker did not terminate", ended.await(5, TimeUnit.SECONDS)); }
        finally { waiter.interrupt(); }
    }
}
