package com.jarvys.agent.crew;

import static org.junit.Assert.*;

import com.jarvys.agent.CoreAgentLoop;
import com.jarvys.agent.CorePromptBudget;
import com.jarvys.agent.CoreToolRegistry;
import com.jarvys.agent.ModelReply;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Proposed focused regressions for UX43 admission. Prepared externally; not yet run. */
public class CrewWorkspaceMissionAdmissionTest {
    private static final CoreToolRegistry EMPTY = new CoreToolRegistry(Collections.emptyList());

    @Test public void preflightCreatesNoMissionBotOrModelCallAndDoesNotChangeLegacyCursor() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (CrewManager manager = manager(calls)) {
            manager.beginMission("legacy", "Original legacy request");
            manager.validateSpawnForMission("workspace-A", "Original request", "custom", "Task",
                    Collections.emptyList(), "Worker", CrewMissionAccess.STANDARD);
            assertTrue(manager.bots().isEmpty());
            assertEquals(0, calls.get());
            assertEquals(1, manager.missionSnapshots().size());
            assertEquals("legacy", manager.missionSnapshots().get(0).missionId);
            CrewManager.Bot legacy = manager.spawn("custom", "Legacy task", Collections.emptyList(), "Legacy");
            assertEquals("legacy", legacy.missionId);
            await(legacy);
        }
    }

    @Test public void preflightUsesExistingRoleToolAndReadOnlyRulesWithoutCreatingAMission() {
        try (CrewManager manager = manager(new AtomicInteger())) {
            assertThrows(IllegalArgumentException.class, () -> manager.validateSpawnForMission(
                    "workspace-A", "Original", "missing-role", "Task", null, null, CrewMissionAccess.STANDARD));
            assertThrows(IllegalArgumentException.class, () -> manager.validateSpawnForMission(
                    "workspace-A", "Original", "custom", "Task", Collections.singletonList("apk_factory"),
                    "Worker", CrewMissionAccess.STANDARD));
            assertThrows(IllegalArgumentException.class, () -> manager.validateSpawnForMission(
                    "workspace-A", "Original", "custom", "Task", Collections.emptyList(),
                    "Worker", CrewMissionAccess.READ_ONLY));
            assertTrue(manager.bots().isEmpty());
            assertTrue(manager.missionSnapshots().isEmpty());
        }
    }

    @Test public void explicitMissionsKeepExactRequestsAndDoNotReplaceLegacyCursor() throws Exception {
        try (CrewManager manager = manager(new AtomicInteger())) {
            manager.beginMission("legacy", "Original legacy request");
            String original = "  Exact workspace request.\nKeep the whitespace.  ";
            CrewManager.Bot first = spawn(manager, "workspace-A", original);
            CrewManager.Bot sibling = spawn(manager, "workspace-A", original);
            CrewManager.Bot second = spawn(manager, "workspace-B", "Independent request");
            CrewManager.Bot legacy = manager.spawn("custom", "Legacy worker", Collections.emptyList(), "Legacy");
            assertEquals("workspace-A", first.missionId);
            assertEquals("workspace-A", sibling.missionId);
            assertEquals(original, first.originalInstructions());
            assertEquals(original, sibling.originalInstructions());
            assertEquals("workspace-B", second.missionId);
            assertEquals("legacy", legacy.missionId);
            assertEquals("Original legacy request", legacy.originalInstructions());
            for (CrewManager.Bot bot : manager.bots()) await(bot);
        }
    }

    @Test public void conflictingOriginalRequestRejectsBeforeCreatingAnotherWorker() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (CrewManager manager = manager(calls)) {
            CrewManager.Bot first = spawn(manager, "workspace-A", "Original request");
            await(first);
            assertThrows(IllegalStateException.class,
                    () -> spawn(manager, "workspace-A", "Changed request"));
            assertEquals(1, manager.bots().size());
            assertEquals(1, calls.get());
            assertEquals("Original request", first.originalInstructions());
        }
    }

    @Test public void failedRoleResolutionLeavesLegacyCursorUnchanged() throws Exception {
        try (CrewManager manager = manager(new AtomicInteger())) {
            manager.beginMission("legacy", "Original legacy request");
            assertThrows(IllegalArgumentException.class, () -> manager.spawnForMission(
                    "workspace-A", "New request", "unknown-role", "Worker request",
                    null, null, CrewMissionAccess.STANDARD, "Workspace title"));
            CrewManager.Bot legacy = manager.spawn("custom", "Legacy worker", Collections.emptyList(), "Legacy");
            assertEquals("legacy", legacy.missionId);
            assertEquals("Original legacy request", legacy.originalInstructions());
            await(legacy);
        }
    }

    @Test public void completedMissionRejectsFurtherAssignmentWithoutStartingWorker() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (CrewManager manager = manager(calls)) {
            await(spawn(manager, "workspace-A", "Original request"));
            manager.finishMission("workspace-A", "Verified result", "COMPLETED");
            assertThrows(IllegalStateException.class,
                    () -> spawn(manager, "workspace-A", "Original request"));
            assertEquals(1, manager.bots().size());
            assertEquals(1, calls.get());
        }
    }

    @Test public void parallelAssignmentsStayInTheirOwnMissionAndKeepLegacyCursor() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (CrewManager manager = manager(calls)) {
            manager.beginMission("legacy", "Legacy original");
            CountDownLatch start = new CountDownLatch(1), completed = new CountDownLatch(8);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Thread> threads = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                final String mission = index % 2 == 0 ? "workspace-A" : "workspace-B";
                Thread thread = new Thread(() -> {
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        CrewManager.Bot bot = spawn(manager, mission, "Original " + mission);
                        assertEquals(mission, bot.missionId);
                        assertEquals("Original " + mission, bot.originalInstructions());
                        await(bot);
                    } catch (Throwable problem) { failure.compareAndSet(null, problem); }
                    finally { completed.countDown(); }
                }, "workspace-admission-test-" + index);
                thread.setDaemon(true);
                threads.add(thread);
                thread.start();
            }
            try {
                start.countDown();
                assertTrue("Concurrent admissions stalled", completed.await(10, TimeUnit.SECONDS));
                if (failure.get() != null) throw new AssertionError(failure.get());
                assertEquals(8, manager.bots().size());
                assertEquals(4L, manager.bots().stream().filter(bot -> "workspace-A".equals(bot.missionId)).count());
                assertEquals(4L, manager.bots().stream().filter(bot -> "workspace-B".equals(bot.missionId)).count());
                CrewManager.Bot legacy = manager.spawn("custom", "Legacy worker", Collections.emptyList(), "Legacy");
                assertEquals("legacy", legacy.missionId);
                await(legacy);
                assertEquals(9, calls.get());
            } finally { for (Thread thread : threads) thread.interrupt(); }
        }
    }

    @Test public void finishWhileRoleIsResolvingCannotCreateWorkerInAnotherMission() throws Exception {
        interruptedAdmission(false, "COMPLETED", "SYNTHESIZED");
    }

    @Test public void stopAllWhileRoleIsResolvingCannotLaunchAnyWorker() throws Exception {
        interruptedAdmission(true, "", "STOPPED");
    }

    @Test public void explicitEmptyAdmissionRetainsStoppedOutcome() throws Exception {
        interruptedAdmission(false, "STOPPED", "STOPPED");
    }

    @Test public void explicitEmptyAdmissionRetainsPartialOutcome() throws Exception {
        interruptedAdmission(false, "PARTIAL", "PARTIAL");
    }

    private static void interruptedAdmission(boolean stopAll, String outcome, String expectedStatus) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch resolving = new CountDownLatch(1), release = new CountDownLatch(1), complete = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (CrewManager manager = manager(calls)) {
            manager.configureProfiles(id -> {
                resolving.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Profile resolution was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                return new CrewRole("test-profile", "Test profile", "test", "Profile instructions", Collections.emptyList(), null);
            });
            Thread admission = new Thread(() -> {
                try {
                    manager.spawnForMission("workspace-A", "Original request", "test-profile", "Worker request",
                            null, null, CrewMissionAccess.STANDARD, "Workspace title");
                    failure.set(new AssertionError("Terminal admission launched a worker"));
                } catch (IllegalStateException expected) {
                    // A closed explicit mission must reject before publication or execution.
                } catch (Throwable problem) { failure.set(problem); }
                finally { complete.countDown(); }
            }, "workspace-interrupted-admission-test");
            admission.setDaemon(true);
            admission.start();
            try {
                assertTrue("Did not reach profile resolution", resolving.await(5, TimeUnit.SECONDS));
                if (stopAll) manager.stopAll();
                else manager.finishMission("workspace-A", "Observed terminal state", outcome);
                release.countDown();
                assertTrue("Admission did not stop", complete.await(5, TimeUnit.SECONDS));
                if (failure.get() != null) throw new AssertionError(failure.get());
                assertTrue(manager.bots().isEmpty());
                assertEquals(0, calls.get());
                CrewMissionSnapshot retained = manager.missionSnapshots().stream()
                        .filter(mission -> "workspace-A".equals(mission.missionId)).findFirst().get();
                assertEquals(expectedStatus, retained.status);
                assertEquals("Original request", retained.originalInstructions);
            } finally { release.countDown(); admission.interrupt(); }
        }
    }

    private static CrewManager.Bot spawn(CrewManager manager, String mission, String request) {
        return manager.spawnForMission(mission, request, "custom", "Worker request", Collections.emptyList(),
                "Worker", CrewMissionAccess.STANDARD, "Workspace title");
    }

    private static CrewManager manager(AtomicInteger calls) {
        CoreAgentLoop.Model model = (history, prompt, specs, token) -> {
            calls.incrementAndGet();
            return new ModelReply("Verified test result", Collections.emptyList());
        };
        return new CrewManager("workspace-test", EMPTY, (bot, manager) -> EMPTY,
                (bot, tools, inbox) -> new CoreAgentLoop(model, tools, "", "workspace-test", CorePromptBudget.standard(),
                        null, CoreAgentLoop.Limits.UNBOUNDED, inbox, null), null);
    }

    private static void await(CrewManager.Bot bot) throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try { bot.awaitTermination(); ended.countDown(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, "workspace-test-worker-await");
        waiter.setDaemon(true);
        waiter.start();
        try { assertTrue("Worker did not terminate", ended.await(5, TimeUnit.SECONDS)); }
        finally { waiter.interrupt(); }
    }
}
