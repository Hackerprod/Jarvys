package com.jarvys.agent.crew;

import static org.junit.Assert.*;

import com.jarvys.agent.CancellationToken;
import com.jarvys.agent.ConversationTurn;
import com.jarvys.agent.CoreAgentLoop;
import com.jarvys.agent.CorePromptBudget;
import com.jarvys.agent.CoreToolRegistry;
import com.jarvys.agent.ModelReply;
import com.jarvys.agent.ProviderHttpException;
import com.jarvys.agent.ProviderTransportException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

/** UX38 runtime tests use real agent loops and latch-controlled, independently owned jobs. */
public class CrewProviderUnavailableRecoveryTest {
    private static final String CHAT = "provider-recovery-chat";
    private static final String MISSION = "provider-recovery-mission";

    @Test(timeout = 20000L)
    public void partialWorkerWaitsForOwnedWorkAndOnlyResumesAfterExplicitReconciliation() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger loops = new AtomicInteger();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch waitingAfterProgress = new CountDownLatch(1);
        FakeOwnedWork job = new FakeOwnedWork();
        RecordingCheckpoints checkpoints = new RecordingCheckpoints();
        CoreAgentLoop.Model model = (transcript, prompt, declarations, token) -> {
            if (requests.incrementAndGet() == 1) throw unavailable();
            assertEquals("Only explicit resume may make the next model request", 2, requests.get());
            assertTrue(transcript.stream().anyMatch(turn -> turn.kind == ConversationTurn.Kind.TOOL_RESULT
                    && turn.content.contains("job-17 completed; exit code 0")));
            assertTrue(transcript.stream().anyMatch(turn -> turn.content.contains("job-17 is still running")));
            return new ModelReply("Reviewed job-17's retained completion receipt", Collections.emptyList());
        };
        CrewManager.SnapshotListener listener = snapshot -> {
            if (!Thread.currentThread().getName().startsWith("jarvys-crew-")) return;
            if (snapshot.bots.stream().anyMatch(bot -> "WAITING".equals(bot.status))) {
                waiting.countDown();
                if (snapshot.messages.stream().anyMatch(message -> message.text.contains("job-17 is still running"))) {
                    waitingAfterProgress.countDown();
                }
            }
        };
        try (CrewManager manager = manager(model, loops, listener)) {
            manager.configureCheckpoints(checkpoints);
            CrewManager.Bot bot = restore(manager, "worker-one");
            manager.registerOwnedWork(bot, job);
            manager.resume(bot.id);
            CancellationToken generation = bot.token;
            await(waiting, "Partial worker did not wait for its existing owned job");

            assertEquals(CrewManager.Status.WAITING, bot.status());
            assertTrue(manager.hasPendingOwnedWork(bot));
            assertEquals(0L, bot.finishedAtMillis());
            assertTrue(bot.result().isEmpty());
            assertFalse(bot.canResume());
            assertFalse(generation.isCancellationRequested());
            assertEquals(0, job.cancellations.get());
            assertEquals(1, requests.get());
            assertFalse(hasResult(manager, bot));
            assertThrows(IllegalStateException.class,
                    () -> manager.reportDone(bot, "Premature success", Collections.emptyList()));

            manager.observeOwnedWork(bot, generation.generation(), "job-17-progress", "job-17 is still running");
            // An inbox observation must not let the partial path skip still-pending owned work.
            await(waitingAfterProgress, "Worker did not recheck the pending job after its observation");
            assertEquals(CrewManager.Status.WAITING, bot.status());
            assertTrue(manager.missionSnapshots().get(0).active());
            assertEquals(0L, manager.missionSnapshots().get(0).finishedAtMillis);
            assertEquals(1, requests.get());
            assertFalse(hasResult(manager, bot));

            job.pending.set(false);
            manager.observeOwnedWork(bot, generation.generation(), "job-17-done", "job-17 completed; exit code 0");
            awaitFinished(bot);
            assertEquals(CrewManager.Status.PARTIAL, bot.status());
            assertTrue(bot.requiresExplicitResume());
            assertTrue(bot.canResume());
            assertFalse(generation.isCancellationRequested());
            assertEquals(0, job.cancellations.get());
            assertEquals(1, requests.get());
            assertEquals(1, loops.get());
            assertEquals(1L, receiptCount(manager.messageBus().pending(bot.id), "job-17 completed; exit code 0"));
            assertThrows(IllegalArgumentException.class,
                    () -> manager.send("chief", bot.id, CrewMessage.Type.ANSWER, "Try again", Collections.emptyList()));
            assertThrows(IllegalArgumentException.class, () -> manager.sendUserMessage(bot.id, "Continue"));

            manager.finishMission(bot.missionId, bot.result(), "PARTIAL");
            CrewMissionSnapshot roundTrip = CrewMissionSnapshot.fromJson(manager.missionSnapshots().get(0).toJson());
            assertNotNull(roundTrip);
            assertEquals("PARTIAL", roundTrip.status);
            assertFalse(roundTrip.active());
            assertEquals("PARTIAL", roundTrip.bots.get(0).status);
            assertTrue(roundTrip.bots.get(0).resumeRequired);
            assertTrue(roundTrip.bots.get(0).canResume);

            manager.resume(bot.id);
            awaitFinished(bot);
            assertEquals(CrewManager.Status.DONE, bot.status());
            assertFalse(bot.requiresExplicitResume());
            assertNotEquals(generation.generation(), bot.token.generation());
            assertEquals(2, requests.get());
            assertEquals(2, loops.get());
            assertEquals(0, job.cancellations.get());
            assertTrue(manager.messageBus().pending(bot.id).isEmpty());
            CrewMissionSnapshot afterResume = manager.missionSnapshots().get(0);
            assertEquals("Worker recovery does not supply a captain synthesis", "PARTIAL", afterResume.status);
            assertFalse(afterResume.active());
            assertTrue(afterResume.finishedAtMillis >= bot.finishedAtMillis());
        }
    }

    @Test(timeout = 15000L)
    public void explicitUserContinuationKeepsMissionPartialUntilTheCaptainActuallySynthesizes() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch continued = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (CrewManager manager = manager((transcript, prompt, declarations, token) -> {
            if (requests.incrementAndGet() == 1) throw unavailable();
            assertEquals(2, requests.get());
            continued.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Continuation was never released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                token.throwIfCancelled();
                throw new IllegalStateException(interrupted);
            }
            return new ModelReply("Worker finished the explicit continuation", Collections.emptyList());
        }, new AtomicInteger(), null)) {
            manager.beginMission(MISSION, "Investigate the retained evidence");
            CrewManager.Bot bot = manager.spawn("custom", "Inspect source", Collections.emptyList(), "Inspector");
            try {
                awaitFinished(bot);
                assertEquals(CrewManager.Status.PARTIAL, bot.status());
                manager.finishMission(bot.missionId, "Captain retained a partial outcome", "PARTIAL");
                CrewMissionSnapshot initial = manager.missionSnapshots().get(0);
                assertEquals("PARTIAL", initial.status);
                assertFalse(initial.active());
                assertTrue(initial.finishedAtMillis > 0L);

                manager.sendUserMessage(bot.id, "Explicitly continue and inspect the retained evidence");
                await(continued, "Explicit user continuation never started");
                CrewMissionSnapshot active = manager.missionSnapshots().get(0);
                assertEquals("PARTIAL", active.status);
                assertEquals("Captain retained a partial outcome", active.synthesis);
                assertTrue(active.active());
                assertEquals(0L, active.finishedAtMillis);
                assertEquals("RUNNING", active.bots.get(0).status);

                release.countDown();
                awaitFinished(bot);
                CrewMissionSnapshot workerFinished = manager.missionSnapshots().get(0);
                assertEquals(CrewManager.Status.DONE, bot.status());
                assertEquals("PARTIAL", workerFinished.status);
                assertEquals("Captain retained a partial outcome", workerFinished.synthesis);
                assertFalse(workerFinished.active());
                assertEquals(Math.max(initial.finishedAtMillis, bot.finishedAtMillis()), workerFinished.finishedAtMillis);
                assertTrue(workerFinished.finishedAtMillis > 0L);
                assertEquals(2, requests.get());
                assertEquals("PARTIAL", CrewMissionSnapshot.fromJson(workerFinished.toJson()).status);

                manager.finishMission(bot.missionId, "Captain verified and synthesized the completed work", "COMPLETED");
                CrewMissionSnapshot synthesized = manager.missionSnapshots().get(0);
                assertEquals("SYNTHESIZED", synthesized.status);
                assertFalse(synthesized.active());
                assertEquals("Captain verified and synthesized the completed work", synthesized.synthesis);
                assertEquals(2, requests.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test(timeout = 15000L)
    public void receiptCheckpointFailureStillReleasesThePartialWorkersOwnedJobWait() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger failedCommits = new AtomicInteger();
        AtomicBoolean failReceiptOnce = new AtomicBoolean(true);
        AtomicReference<List<CrewMessage>> durablePending = new AtomicReference<>(Collections.emptyList());
        CountDownLatch waiting = new CountDownLatch(1);
        FakeOwnedWork job = new FakeOwnedWork();
        IllegalStateException storageFailure = new IllegalStateException("Injected receipt checkpoint failure");
        CrewManager.SnapshotListener listener = snapshot -> {
            if (Thread.currentThread().getName().startsWith("jarvys-crew-")
                    && snapshot.bots.stream().anyMatch(bot -> "WAITING".equals(bot.status))) waiting.countDown();
        };
        try (CrewManager manager = manager((transcript, prompt, declarations, token) -> {
            requests.incrementAndGet();
            throw unavailable();
        }, new AtomicInteger(), listener)) {
            manager.configureCheckpoints(new CrewManager.CheckpointSupport() {
                @Override public void persist(CrewManager.Bot bot, List<CrewMessage> messages, List<CrewMessage> pending) {
                    if (receiptCount(pending, "job-66 completed with a retained receipt") > 0L
                            && failReceiptOnce.compareAndSet(true, false)) {
                        assertEquals(CrewManager.Status.WAITING, bot.status());
                        // Change completion under the same callback lock, then fail persistence.
                        // No timer or other external event is available to release the worker.
                        job.pending.set(false);
                        failedCommits.incrementAndGet();
                        throw storageFailure;
                    }
                    durablePending.set(new ArrayList<>(pending));
                }
                @Override public CrewManager.ResumePlan reconcile(CrewManager.Bot bot) {
                    return new CrewManager.ResumePlan(bot.role, "Inspect existing owned-job receipts");
                }
            });
            CrewManager.Bot bot = restore(manager, "receipt-failure-worker");
            manager.registerOwnedWork(bot, job);
            manager.resume(bot.id);
            await(waiting, "Worker did not enter its partial owned-job wait");
            assertTrue(manager.hasPendingOwnedWork(bot));
            assertEquals(1, requests.get());

            assertSame(storageFailure, assertThrows(IllegalStateException.class,
                    () -> manager.observeOwnedWork(bot, bot.token.generation(), "job-66-done",
                            "job-66 completed with a retained receipt")));
            awaitFinished(bot);
            assertEquals(1, failedCommits.get());
            assertEquals(CrewManager.Status.PARTIAL, bot.status());
            assertFalse(manager.hasPendingOwnedWork(bot));
            assertEquals(1, requests.get());
            assertEquals(0, job.cancellations.get());
            assertFalse(bot.token.isCancellationRequested());
            assertTrue(bot.requiresExplicitResume());
            assertTrue(bot.canResume());
            assertEquals(1L, receiptCount(manager.messageBus().pending(bot.id), "job-66 completed with a retained receipt"));
            assertEquals(1L, receiptCount(durablePending.get(), "job-66 completed with a retained receipt"));
            manager.observeOwnedWork(bot, bot.token.generation(), "job-66-done", "job-66 completed with a retained receipt");
            assertEquals("An active-to-partial persistence retry must reuse the original receipt identity", 1L,
                    receiptCount(manager.messageBus().pending(bot.id), "job-66 completed with a retained receipt"));
            assertEquals(1L, receiptCount(durablePending.get(), "job-66 completed with a retained receipt"));
            assertEquals(1, requests.get());
        }
    }

    @Test(timeout = 15000L)
    public void lateReceiptAfterPartialIsDurableDeduplicatedAndNeverReanimatesTheWorker() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        FakeOwnedWork job = new FakeOwnedWork();
        RecordingCheckpoints checkpoints = new RecordingCheckpoints();
        try (CrewManager manager = manager((transcript, prompt, declarations, token) -> {
            requests.incrementAndGet();
            // The job has ended, but its completion callback is intentionally withheld.
            job.pending.set(false);
            throw unavailable();
        }, new AtomicInteger(), null)) {
            manager.configureCheckpoints(checkpoints);
            CrewManager.Bot bot = restore(manager, "late-receipt-worker");
            manager.registerOwnedWork(bot, job);
            assertTrue(manager.hasPendingOwnedWork(bot));
            manager.resume(bot.id);
            long generation = bot.token.generation();
            awaitFinished(bot);
            assertEquals(CrewManager.Status.PARTIAL, bot.status());
            long finishedAt = bot.finishedAtMillis();

            manager.observeOwnedWork(bot, generation, "job-99-done", "job-99 produced /project/output.txt");
            manager.observeOwnedWork(bot, generation, "job-99-done", "job-99 produced /project/output.txt");

            assertEquals(CrewManager.Status.PARTIAL, bot.status());
            assertEquals(finishedAt, bot.finishedAtMillis());
            assertTrue(bot.requiresExplicitResume());
            assertTrue(bot.canResume());
            assertEquals(1, requests.get());
            assertEquals(0, job.cancellations.get());
            assertFalse(bot.token.isCancellationRequested());
            assertEquals(1L, receiptCount(manager.messageBus().pending(bot.id), "job-99 produced /project/output.txt"));
            assertEquals(1L, receiptCount(manager.messageBus().snapshot(), "job-99 produced /project/output.txt"));
            assertEquals(1L, receiptCount(checkpoints.pending.get(), "job-99 produced /project/output.txt"));
            CrewMissionSnapshot restored = CrewMissionSnapshot.fromJson(manager.missionSnapshots().get(0).toJson());
            assertNotNull(restored);
            assertEquals(1L, receiptCount(restored.messages, "job-99 produced /project/output.txt"));
        }
    }

    @Test(timeout = 20000L)
    public void captainStopCancelsEveryOwnedJobAndLateReceiptsCannotRestartStoppedWorkers() throws Exception {
        CountDownLatch waiting = new CountDownLatch(2);
        Set<String> observedWaiting = ConcurrentHashMap.newKeySet();
        AtomicInteger requests = new AtomicInteger();
        CancellationToken captain = CancellationToken.cancellable();
        CrewManager.SnapshotListener listener = snapshot -> {
            for (CrewBotSnapshot bot : snapshot.bots) {
                if ("WAITING".equals(bot.status) && observedWaiting.add(bot.id)) waiting.countDown();
            }
        };
        try (CrewManager manager = manager((transcript, prompt, declarations, token) -> {
            requests.incrementAndGet();
            throw unavailable();
        }, new AtomicInteger(), listener)) {
            manager.configureCheckpoints(new RecordingCheckpoints());
            manager.attachCaptain(captain);
            CrewManager.Bot first = restore(manager, "stop-worker-one");
            CrewManager.Bot second = restore(manager, "stop-worker-two");
            List<FakeOwnedWork> jobs = Arrays.asList(new FakeOwnedWork(), new FakeOwnedWork(),
                    new FakeOwnedWork(), new FakeOwnedWork());
            manager.registerOwnedWork(first, jobs.get(0));
            manager.registerOwnedWork(first, jobs.get(1));
            manager.registerOwnedWork(second, jobs.get(2));
            manager.registerOwnedWork(second, jobs.get(3));
            manager.resume(first.id);
            manager.resume(second.id);
            long firstGeneration = first.token.generation();
            await(waiting, "Both partial workers must reach their owned-job waits");
            assertEquals(2, requests.get());
            for (FakeOwnedWork job : jobs) assertEquals(0, job.cancellations.get());

            assertTrue(captain.cancel());
            awaitFinished(first);
            awaitFinished(second);
            assertEquals(CrewManager.Status.STOPPED, first.status());
            assertEquals(CrewManager.Status.STOPPED, second.status());
            assertTrue(first.token.isCancellationRequested());
            assertTrue(second.token.isCancellationRequested());
            assertFalse(manager.hasPendingOwnedWork(first));
            assertFalse(manager.hasPendingOwnedWork(second));
            for (FakeOwnedWork job : jobs) assertEquals(1, job.cancellations.get());
            assertFalse(hasResult(manager, first));
            assertFalse(hasResult(manager, second));

            manager.observeOwnedWork(first, firstGeneration, "stopped-job-receipt", "job ended during STOP; inspect retained log");
            manager.observeOwnedWork(first, firstGeneration, "stopped-job-receipt", "job ended during STOP; inspect retained log");
            assertEquals(CrewManager.Status.STOPPED, first.status());
            assertTrue(first.requiresExplicitResume());
            assertEquals(2, requests.get());
            assertEquals(1L, receiptCount(manager.messageBus().pending(first.id), "job ended during STOP; inspect retained log"));
            assertEquals(1L, receiptCount(manager.missionSnapshots().get(0).messages, "job ended during STOP; inspect retained log"));
        }
    }

    @Test(timeout = 15000L)
    public void unpersistedPartialWorkerRejectsCaptainRetryButAllowsAnExplicitUserContinuation() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger loops = new AtomicInteger();
        try (CrewManager manager = manager((transcript, prompt, declarations, token) -> {
            if (requests.incrementAndGet() == 1) throw unavailable();
            assertEquals(2, requests.get());
            assertTrue(transcript.stream().anyMatch(turn -> "user".equals(turn.role)
                    && "Inspect the retained evidence and continue".equals(turn.content)));
            return new ModelReply("Continued with the user's explicit guidance", Collections.emptyList());
        }, loops, null)) {
            CrewManager.Bot bot = manager.spawn("custom", "Inspect source", Collections.emptyList(), "Inspector");
            awaitFinished(bot);
            assertEquals(CrewManager.Status.PARTIAL, bot.status());
            assertFalse(bot.requiresExplicitResume());
            assertFalse(bot.canResume());
            assertFalse(bot.token.isCancellationRequested());
            assertEquals(1, requests.get());
            assertThrows(IllegalArgumentException.class,
                    () -> manager.send("chief", bot.id, CrewMessage.Type.ANSWER, "Automatically retry", Collections.emptyList()));
            manager.sendUserMessage(bot.id, "Inspect the retained evidence and continue");
            awaitFinished(bot);
            assertEquals(CrewManager.Status.DONE, bot.status());
            assertEquals(2, requests.get());
            assertEquals("Explicit continuation reuses the retained loop", 1, loops.get());
        }
    }

    @Test(timeout = 15000L)
    public void delayedReceiptAfterNormalDoneStillReanimatesOnceAndConsumesItsEvidence() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger loops = new AtomicInteger();
        CountDownLatch resumed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (CrewManager manager = manager((transcript, prompt, declarations, token) -> {
            if (requests.incrementAndGet() == 1) return new ModelReply("Initial work complete", Collections.emptyList());
            assertEquals("A delayed receipt should start exactly one follow-up cycle", 2, requests.get());
            assertTrue(transcript.stream().anyMatch(turn -> turn.kind == ConversationTurn.Kind.TOOL_RESULT
                    && turn.content.contains("job-77 completed after the final answer")));
            resumed.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Follow-up was never released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                token.throwIfCancelled();
                throw new IllegalStateException(interrupted);
            }
            return new ModelReply("Reviewed late job-77 evidence", Collections.emptyList());
        }, loops, null)) {
            manager.configureCheckpoints(new RecordingCheckpoints());
            CrewManager.Bot bot = restore(manager, "done-worker");
            manager.resume(bot.id);
            awaitFinished(bot);
            assertEquals(CrewManager.Status.DONE, bot.status());
            assertFalse(bot.requiresExplicitResume());
            long generation = bot.token.generation();
            try {
                manager.observeOwnedWork(bot, generation, "job-77-done", "job-77 completed after the final answer");
                await(resumed, "Normal DONE worker did not process its delayed receipt");
                manager.observeOwnedWork(bot, generation, "job-77-done", "job-77 completed after the final answer");
                assertEquals(2, requests.get());
                release.countDown();
                awaitFinished(bot);
                assertEquals(CrewManager.Status.DONE, bot.status());
                assertEquals("Reviewed late job-77 evidence", bot.result());
                assertEquals(2, requests.get());
                assertEquals(1, loops.get());
                assertEquals(generation, bot.token.generation());
                assertFalse(bot.token.isCancellationRequested());
                assertEquals(1L, receiptCount(manager.messageBus().snapshot(), "job-77 completed after the final answer"));
                assertTrue(manager.messageBus().pending(bot.id).isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    @Test(timeout = 15000L)
    public void stopAllDoesNotRelabelAnAlreadyTerminalPartialMissionAsStopped() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (CrewManager manager = manager((transcript, prompt, declarations, token) -> {
            requests.incrementAndGet();
            throw unavailable();
        }, new AtomicInteger(), null)) {
            manager.configureCheckpoints(new RecordingCheckpoints());
            CrewManager.Bot bot = restore(manager, "partial-worker");
            manager.resume(bot.id);
            awaitFinished(bot);
            manager.finishMission(bot.missionId, bot.result(), "PARTIAL");
            long finishedAt = manager.missionSnapshots().get(0).finishedAtMillis;
            manager.stopAll();
            CrewMissionSnapshot snapshot = manager.missionSnapshots().get(0);
            assertEquals("PARTIAL", snapshot.status);
            assertEquals("PARTIAL", snapshot.bots.get(0).status);
            assertEquals(finishedAt, snapshot.finishedAtMillis);
            assertFalse(snapshot.active());
            assertEquals(1, requests.get());
            assertFalse(bot.token.isCancellationRequested());
            assertTrue(bot.canResume());
        }
    }

    @Test(timeout = 15000L)
    public void captainPartialKeepsSiblingRunningAndMissionUnfinishedUntilSiblingCompletes() throws Exception {
        CountDownLatch siblingStarted = new CountDownLatch(1);
        CountDownLatch releaseSibling = new CountDownLatch(1);
        AtomicInteger siblingRequests = new AtomicInteger();
        AtomicInteger captainRequests = new AtomicInteger();
        CancellationToken captain = CancellationToken.cancellable();
        AtomicInteger captainCancellations = new AtomicInteger();
        captain.registerCancelAction(captainCancellations::incrementAndGet);
        try (CrewManager manager = manager((transcript, prompt, declarations, token) -> {
            siblingRequests.incrementAndGet();
            siblingStarted.countDown();
            try {
                if (!releaseSibling.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Sibling was never released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                token.throwIfCancelled();
                throw new IllegalStateException(interrupted);
            }
            return new ModelReply("Sibling completed independently", Collections.emptyList());
        }, new AtomicInteger(), null)) {
            manager.beginMission(MISSION, "Research independently");
            manager.attachCaptain(captain);
            CrewManager.Bot sibling = manager.spawn("custom", "Continue useful work", Collections.emptyList(), "Sibling");
            try {
                await(siblingStarted, "Sibling did not start");
                CoreAgentLoop captainLoop = new CoreAgentLoop((transcript, prompt, declarations, token) -> {
                    captainRequests.incrementAndGet();
                    throw new ProviderHttpException("Service unavailable", 503, 0L, null);
                }, new CoreToolRegistry(Collections.emptyList()), "", CHAT);
                CoreAgentLoop.Result result = captainLoop.run("Coordinate the mission", Collections.emptyList(), captain, null);
                assertEquals("PARTIAL", result.outcome);
                manager.finishMission(MISSION, result.text, result.outcome);

                assertEquals(1, captainRequests.get());
                assertEquals(0, captainCancellations.get());
                assertFalse(captain.isCancellationRequested());
                assertFalse(sibling.token.isCancellationRequested());
                assertEquals(CrewManager.Status.RUNNING, sibling.status());
                CrewMissionSnapshot snapshot = manager.missionSnapshots().get(0);
                assertEquals("PARTIAL", snapshot.status);
                assertTrue(snapshot.active());
                assertEquals(0L, snapshot.finishedAtMillis);
                CrewMissionSnapshot afterProcessDeath = CrewMissionSnapshot.fromJson(snapshot.toJson()).interrupted();
                assertEquals("INTERRUPTED", afterProcessDeath.status);
                assertEquals("INTERRUPTED", afterProcessDeath.bots.get(0).status);
                assertTrue(afterProcessDeath.bots.get(0).resumeRequired);

                releaseSibling.countDown();
                awaitFinished(sibling);
                assertEquals(CrewManager.Status.DONE, sibling.status());
                assertEquals(1, siblingRequests.get());
                assertEquals(1, captainRequests.get());
                CrewMissionSnapshot finished = manager.missionSnapshots().get(0);
                assertEquals("PARTIAL", finished.status);
                assertFalse(finished.active());
                assertTrue(finished.finishedAtMillis > 0L);
                assertEquals("PARTIAL", CrewMissionSnapshot.fromJson(finished.toJson()).status);
            } finally {
                releaseSibling.countDown();
            }
        }
    }

    @Test(timeout = 15000L)
    public void nonrecoverable401RemainsFailedAndCancelsItsOwnedWork() throws Exception {
        assertFatalWorker(new ProviderHttpException("Unauthorized", 401, 0L, null));
    }

    @Test(timeout = 15000L)
    public void fatalRuntimeErrorRemainsFailedAndCancelsItsOwnedWork() throws Exception {
        assertFatalWorker(new IllegalStateException("Model adapter invariant failed"));
    }

    @Test public void partialAndFailedSnapshotsStayDistinctWhileActiveWorkersBecomeInterrupted() {
        CrewBotSnapshot partial = snapshot("partial-worker", "PARTIAL", true, "Retained evidence");
        CrewBotSnapshot failed = snapshot("failed-worker", "FAILED", true, "Actual failure");
        CrewBotSnapshot waiting = snapshot("active-worker", "WAITING", false, "");
        CrewMissionSnapshot mission = new CrewMissionSnapshot(MISSION, CHAT, "previous-process", "Mission",
                "PARTIAL", "Provider operation ended early", 1L, 0L,
                Arrays.asList(partial, failed, waiting), Collections.emptyList());
        CrewMissionSnapshot restored = CrewMissionSnapshot.fromJson(mission.toJson());
        assertNotNull(restored);
        assertEquals("PARTIAL", restored.status);
        assertEquals("PARTIAL", restored.bots.get(0).status);
        assertEquals("FAILED", restored.bots.get(1).status);
        assertTrue(restored.active());
        CrewMissionSnapshot recovered = restored.interrupted();
        assertEquals("INTERRUPTED", recovered.status);
        assertFalse(recovered.active());
        assertEquals("PARTIAL", recovered.bots.get(0).status);
        assertEquals("FAILED", recovered.bots.get(1).status);
        assertEquals("INTERRUPTED", recovered.bots.get(2).status);
        assertTrue(recovered.bots.get(2).resumeRequired);
        assertFalse(recovered.bots.get(2).canResume);
        assertEquals("Retained evidence", recovered.bots.get(0).result);
        assertEquals("Actual failure", recovered.bots.get(1).result);
    }

    private static void assertFatalWorker(RuntimeException failure) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        FakeOwnedWork job = new FakeOwnedWork();
        try (CrewManager manager = manager((transcript, prompt, declarations, token) -> {
            requests.incrementAndGet();
            throw failure;
        }, new AtomicInteger(), null)) {
            manager.configureCheckpoints(new RecordingCheckpoints());
            CrewManager.Bot bot = restore(manager, "failed-worker");
            manager.registerOwnedWork(bot, job);
            manager.resume(bot.id);
            awaitFinished(bot);
            assertEquals(CrewManager.Status.FAILED, bot.status());
            assertEquals(failure.getMessage(), bot.error());
            assertEquals(1, requests.get());
            assertEquals(1, job.cancellations.get());
            assertFalse(manager.hasPendingOwnedWork(bot));
            assertTrue(hasResult(manager, bot));
            manager.finishMission(bot.missionId, bot.result(), "FAILED");
            CrewMissionSnapshot restored = CrewMissionSnapshot.fromJson(manager.missionSnapshots().get(0).toJson());
            assertNotNull(restored);
            assertEquals("FAILED", restored.status);
            assertEquals("FAILED", restored.bots.get(0).status);
        }
    }

    private static CrewManager manager(CoreAgentLoop.Model model, AtomicInteger loops,
                                       CrewManager.SnapshotListener listener) {
        CoreToolRegistry empty = new CoreToolRegistry(Collections.emptyList());
        CrewManager.ToolFactory tools = (bot, manager) -> empty;
        CrewManager.LoopFactory factory = (bot, registry, incoming) -> {
            loops.incrementAndGet();
            return new CoreAgentLoop(model, registry, "", CHAT, CorePromptBudget.standard(), null,
                    CoreAgentLoop.Limits.UNBOUNDED, incoming, null);
        };
        CrewManager manager = new CrewManager(CHAT, empty, tools, factory, null);
        manager.configure(empty, tools, factory, null, null, listener);
        return manager;
    }

    private static CrewManager.Bot restore(CrewManager manager, String id) {
        CrewProfile profile = new CrewProfile("coding", 1, "Coding", "Project work", "Mission",
                Collections.emptyList(), Collections.emptyList(), CrewProfile.WorkspaceMode.CONVERSATION_PROJECT);
        CrewBotSnapshot bot = snapshot(id, "INTERRUPTED", true, "");
        CrewMissionSnapshot mission = new CrewMissionSnapshot(MISSION, CHAT, "previous-process", "Mission",
                "INTERRUPTED", "", 1L, 0L, Collections.singletonList(bot), Collections.emptyList());
        return manager.restoreBot(mission, bot, profile.resolveRole(Collections.emptyList(), Collections.emptyList()),
                CoreAgentLoop.Checkpoint.empty(), new JSONObject(), "scope-one", Collections.emptyList(), 1L,
                Collections.emptyList(), Collections.emptyList(), "");
    }

    private static CrewBotSnapshot snapshot(String id, String status, boolean resumeRequired, String result) {
        return new CrewBotSnapshot(id, "coding", "Coding", id, "coding", "Retain owned work", status,
                "", result, "", Collections.emptyList(), 1L, 0L, resumeRequired,
                "Inspect retained evidence before continuation", resumeRequired);
    }

    private static ProviderTransportException unavailable() {
        return new ProviderTransportException("Provider response timed out", new IOException("socket timeout"));
    }

    private static boolean hasResult(CrewManager manager, CrewManager.Bot bot) {
        return manager.messageBus().snapshot().stream()
                .anyMatch(message -> bot.id.equals(message.from) && message.type == CrewMessage.Type.RESULT);
    }

    private static long receiptCount(List<CrewMessage> messages, String receipt) {
        return messages.stream().filter(message -> message.type == CrewMessage.Type.FINDING
                && message.text.contains(receipt)).count();
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        assertTrue(message, latch.await(5, TimeUnit.SECONDS));
    }

    private static void awaitFinished(CrewManager.Bot bot) throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try { bot.awaitTermination(); }
            catch (Throwable error) { failure.set(error); }
            finally { ended.countDown(); }
        }, "test-await-crew-termination");
        waiter.setDaemon(true);
        waiter.start();
        try {
            await(ended, "Bot did not terminate: " + bot.id);
            if (failure.get() != null) throw new AssertionError("Termination waiter failed", failure.get());
        } finally {
            waiter.interrupt();
            waiter.join(1000L);
        }
    }

    private static final class FakeOwnedWork implements CrewManager.OwnedWork {
        final AtomicBoolean pending = new AtomicBoolean(true);
        final AtomicInteger cancellations = new AtomicInteger();
        @Override public boolean pending() { return pending.get(); }
        @Override public void cancelAndAwait() {
            cancellations.incrementAndGet();
            pending.set(false);
        }
    }

    private static final class RecordingCheckpoints implements CrewManager.CheckpointSupport {
        final AtomicReference<List<CrewMessage>> pending = new AtomicReference<>(Collections.emptyList());
        @Override public void persist(CrewManager.Bot bot, List<CrewMessage> messages, List<CrewMessage> pendingRows) {
            pending.set(new ArrayList<>(pendingRows));
        }
        @Override public CrewManager.ResumePlan reconcile(CrewManager.Bot bot) {
            return new CrewManager.ResumePlan(bot.role, "Reconciliation observation: inspect retained job evidence");
        }
    }
}
