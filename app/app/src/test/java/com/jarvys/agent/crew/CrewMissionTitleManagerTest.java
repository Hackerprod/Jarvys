package com.jarvys.agent.crew;

import static org.junit.Assert.*;

import com.jarvys.agent.CancellationToken;
import com.jarvys.agent.CoreAgentLoop;
import com.jarvys.agent.CorePromptBudget;
import com.jarvys.agent.CoreToolRegistry;
import com.jarvys.agent.CoreToolResult;
import com.jarvys.agent.ModelReply;
import com.jarvys.agent.ToolSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

public class CrewMissionTitleManagerTest {
    private static final CoreToolRegistry EMPTY = new CoreToolRegistry(Collections.emptyList());

    @Test public void firstAcceptedTitlePrecedesPublishedSnapshotAndSurvivesBotsAndRetries() throws Exception {
        String original = "  Revisa el proyecto completo.\nRespeta todos los límites y presenta las pruebas.  ";
        String workerMission = "  Revisa sólo los archivos originales.\nNo los cambies.  ";
        List<CrewMissionSnapshot> published = new CopyOnWriteArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        try (CrewManager manager = manager((history, prompt, specs, token) -> {
            modelCalls.incrementAndGet();
            assertTrue(history.stream().anyMatch(turn -> turn.content.contains(workerMission.trim())));
            assertFalse(history.stream().anyMatch(turn -> turn.content.contains("Revisar calidad del proyecto")));
            return done();
        }, published)) {
            manager.beginMission("mission-one", original);
            CrewManager.Bot first = manager.spawn("custom", workerMission, Collections.emptyList(), "Coding",
                    CrewMissionAccess.STANDARD, " Revisar\n calidad del proyecto ");
            await(first);
            assertFalse(published.isEmpty());
            assertEquals("Revisar calidad del proyecto", published.get(0).title);
            assertEquals(original, published.get(0).originalInstructions);
            assertEquals(workerMission, first.mission);
            manager.beginMission("mission-one", "retry must not replace the original");
            CrewManager.Bot second = manager.spawn("custom", workerMission, Collections.emptyList(), "Critic",
                    CrewMissionAccess.STANDARD, "Un título diferente");
            await(second);
            assertEquals(2, modelCalls.get());
            for (CrewMissionSnapshot snapshot : published) assertEquals("Revisar calidad del proyecto", snapshot.title);
            CrewMissionSnapshot snapshot = manager.missionSnapshots().get(0);
            assertEquals(original, snapshot.originalInstructions);
            assertEquals(CrewMissionTitle.AGENT, snapshot.titleSource);
            assertEquals("Coding", first.name);
            assertEquals("Critic", second.name);
        }
    }

    @Test public void invalidOrAbsentTitleNeverCancelsWorkAndFallbackCanUpgradeExactlyOnce() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        try (CrewManager manager = manager((h, p, s, t) -> { modelCalls.incrementAndGet(); return done(); }, null)) {
            manager.beginMission("mission", "The exact original request");
            CoreToolRegistry captain = new CoreToolRegistry(CrewTools.captain(manager, EMPTY));
            for (Object invalid : Arrays.asList(null, 42, new JSONObject(), "", "\u0000", repeat("x", 61))) {
                Map<String,Object> args = args("mission", "  Full worker instructions\nwith all details  ");
                if (invalid != null) args.put("task_title", invalid);
                CoreToolResult result = captain.invoke("crew_spawn", args, CancellationToken.uncancellable());
                assertTrue(result.content, result.success);
            }
            for (CrewManager.Bot bot : manager.bots()) await(bot);
            assertEquals(6, modelCalls.get());
            assertEquals("", manager.missionSnapshots().get(0).title);
            assertEquals(CrewMissionTitle.FALLBACK, manager.missionSnapshots().get(0).titleSource);
            Map<String,Object> valid = args("mission", "Full worker instructions");
            valid.put("task_title", "Review project architecture");
            assertTrue(captain.invoke("crew_spawn", valid, CancellationToken.uncancellable()).success);
            valid.put("task_title", "Replace a valid title");
            assertTrue(captain.invoke("crew_spawn", valid, CancellationToken.uncancellable()).success);
            for (CrewManager.Bot bot : manager.bots()) await(bot);
            assertEquals(8, modelCalls.get());
            assertEquals("Review project architecture", manager.missionSnapshots().get(0).title);
            assertEquals("  Full worker instructions\nwith all details  ", manager.bots().stream()
                    .filter(bot -> bot.mission.startsWith("  ")).findFirst().get().mission);
        }
    }

    @Test public void concurrentCandidatesAndCheckpointReadsDoNotRenameOrDeadlock() throws Exception {
        List<CrewMissionSnapshot> published = new CopyOnWriteArrayList<>();
        List<String> checkpointTitles = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (CrewManager manager = manager((h, p, s, t) -> done(), published)) {
            manager.beginMission("mission", "Original user request");
            manager.configureCheckpoints(new CrewManager.CheckpointSupport() {
                @Override public void persist(CrewManager.Bot bot, List<CrewMessage> messages, List<CrewMessage> pending) {
                    checkpointTitles.add(bot.missionTitle().title);
                    assertEquals("Original user request", bot.originalInstructions());
                }
                @Override public CrewManager.ResumePlan reconcile(CrewManager.Bot bot) { return new CrewManager.ResumePlan(bot.role, ""); }
            });
            CountDownLatch start = new CountDownLatch(1), completed = new CountDownLatch(8);
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                final String title = "Review component " + i;
                Thread thread = new Thread(() -> {
                    try {
                        start.await();
                        await(manager.spawn("custom", "Full instructions", Collections.emptyList(), "Worker",
                                CrewMissionAccess.STANDARD, title));
                    } catch (Throwable invalid) { failure.compareAndSet(null, invalid); }
                    finally { completed.countDown(); }
                });
                thread.setDaemon(true);
                threads.add(thread);
                thread.start();
            }
            start.countDown();
            assertTrue("Concurrent spawns/checkpoints stalled", completed.await(10, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertEquals(1, manager.missionSnapshots().size());
            String accepted = manager.missionSnapshots().get(0).title;
            assertTrue(accepted.startsWith("Review component "));
            assertEquals(8, manager.bots().size());
            for (CrewMissionSnapshot snapshot : published) assertEquals(accepted, snapshot.title);
            for (String checkpointTitle : checkpointTitles) assertEquals(accepted, checkpointTitle);
        }
    }

    @Test public void schemaOffersSameTurnSemanticTitleWithoutMakingItRequired() {
        try (CrewManager manager = manager((h, p, s, t) -> done(), null)) {
            ToolSpec spawn = new CoreToolRegistry(CrewTools.captain(manager, EMPTY)).declarations().stream()
                    .filter(spec -> "crew_spawn".equals(spec.name)).findFirst().get();
            Map<String,Object> schema = spawn.jsonSchema();
            Map<?,?> properties = (Map<?,?>) schema.get("properties");
            Map<?,?> title = (Map<?,?>) properties.get("task_title");
            assertEquals("string", title.get("type"));
            assertEquals(60, title.get("maxLength"));
            assertFalse(((List<?>) schema.get("required")).contains("task_title"));
            String description = title.get("description").toString();
            for (String expected : Arrays.asList("semantic", "entire user mission", "same turn", "3–6 words",
                    "user's language", "independently", "full execution instructions", "first accepted"))
                assertTrue(expected, description.contains(expected));
        }
    }

    @Test public void blockedFallbackCallbackCannotOverwriteLaterValidTitleOrBlockOtherWorkers() throws Exception {
        assertOrderedPublication(false);
    }

    @Test public void throwingListenerReleasesDrainerAndStillPublishesQueuedValidTitle() throws Exception {
        assertOrderedPublication(true);
    }

    @Test public void restorePreservesPresentationAndBotExecutionInstructionsWithoutModelCall() {
        try (CrewManager manager = manager((h, p, s, t) -> { throw new AssertionError("Unexpected model call"); }, null)) {
            CrewBotSnapshot bot = new CrewBotSnapshot("saved-bot", "custom", "Worker", "Coding", "coding",
                    "Full saved worker instructions\nFinal requirement", "RUNNING", "", "", "", Collections.emptyList(), 1L, 0L);
            CrewMissionSnapshot saved = new CrewMissionSnapshot("saved-mission", manager.conversationId(), "old",
                    "Restore project review", " Full original request\nwith every condition ", CrewMissionTitle.AGENT,
                    "RUNNING", "", 1L, 0L, Collections.singletonList(bot), Collections.emptyList());
            CrewRole role = new CrewRole("custom", "Worker", "coding", "Worker only", Collections.emptyList(), null);
            manager.restoreBot(saved.interrupted(), bot, role, null, new JSONObject(), "", Collections.emptyList(),
                    0L, Collections.emptyList(), Collections.emptyList(), "Historical evidence");
            CrewMissionSnapshot restored = manager.missionSnapshots().get(0);
            assertEquals(saved.title, restored.title);
            assertEquals(saved.titleSource, restored.titleSource);
            assertEquals(saved.originalInstructions, restored.originalInstructions);
            assertEquals(bot.mission, restored.bots.get(0).mission);
            assertEquals("INTERRUPTED", restored.status);
        }
    }

    @Test public void legacyDirectSpawnDoesNotClaimWorkerTextIsTheOriginalRequest() throws Exception {
        try (CrewManager manager = manager((h, p, s, t) -> done(), null)) {
            CrewManager.Bot bot = manager.spawn("custom", "Full worker-only instructions", Collections.emptyList(), "Worker");
            await(bot);
            CrewMissionSnapshot snapshot = manager.missionSnapshots().get(0);
            assertEquals("", snapshot.title);
            assertEquals("", snapshot.originalInstructions);
            assertEquals("Full worker-only instructions", snapshot.bots.get(0).mission);
        }
    }

    private static CrewManager manager(CoreAgentLoop.Model model, List<CrewMissionSnapshot> published) {
        CrewManager.ToolFactory tools = (bot, manager) -> EMPTY;
        CrewManager.LoopFactory loops = (bot, registry, inbox) -> new CoreAgentLoop(model, registry, "Worker system",
                "title-chat", CorePromptBudget.standard(), null, CoreAgentLoop.Limits.UNBOUNDED, inbox, null);
        CrewManager manager = new CrewManager("title-chat", EMPTY, tools, loops, null);
        if (published != null) manager.configure(EMPTY, tools, loops, null, null, published::add);
        return manager;
    }

    private static void assertOrderedPublication(boolean throwFirst) throws Exception {
        CountDownLatch fallbackEntered = new CountDownLatch(1), releaseFallback = new CountDownLatch(1);
        CountDownLatch firstFinished = new CountDownLatch(1), secondFinished = new CountDownLatch(1);
        AtomicBoolean blockOnce = new AtomicBoolean(true);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>(), secondFailure = new AtomicReference<>();
        List<CrewMissionSnapshot> delivered = new CopyOnWriteArrayList<>();
        CrewManager.ToolFactory tools = (bot, manager) -> EMPTY;
        CrewManager.LoopFactory loops = (bot, registry, inbox) -> new CoreAgentLoop((h, p, s, t) -> done(),
                registry, "", "ordered-title", CorePromptBudget.standard(), null, CoreAgentLoop.Limits.UNBOUNDED, inbox, null);
        try (CrewManager manager = new CrewManager("ordered-title", EMPTY, tools, loops, null)) {
            manager.configure(EMPTY, tools, loops, null, null, snapshot -> {
                if (snapshot.title.isEmpty() && blockOnce.compareAndSet(true, false)) {
                    fallbackEntered.countDown();
                    try { assertTrue("Fallback callback was not released", releaseFallback.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    if (throwFirst) throw new IllegalStateException("Listener failed once");
                }
                delivered.add(snapshot);
            });
            manager.beginMission("ordered-mission", "Original user mission");
            Thread first = new Thread(() -> {
                try { await(manager.spawn("custom", "First worker mission", Collections.emptyList(), "First")); }
                catch (Throwable failure) { firstFailure.set(failure); }
                finally { firstFinished.countDown(); }
            });
            first.setDaemon(true); first.start();
            try {
                assertTrue(fallbackEntered.await(5, TimeUnit.SECONDS));
                Thread second = new Thread(() -> {
                    try { await(manager.spawn("custom", "Second worker mission", Collections.emptyList(), "Second",
                            CrewMissionAccess.STANDARD, "Review the whole mission")); }
                    catch (Throwable failure) { secondFailure.set(failure); }
                    finally { secondFinished.countDown(); }
                });
                second.setDaemon(true); second.start();
                assertTrue("A blocked presentation must not block another worker", secondFinished.await(5, TimeUnit.SECONDS));
                if (secondFailure.get() != null) throw new AssertionError(secondFailure.get());
            } finally { releaseFallback.countDown(); }
            assertTrue(firstFinished.await(5, TimeUnit.SECONDS));
            if (throwFirst) {
                assertNotNull(firstFailure.get());
                assertEquals("Listener failed once", firstFailure.get().getMessage());
            } else if (firstFailure.get() != null) throw new AssertionError(firstFailure.get());
            assertFalse(delivered.isEmpty());
            boolean hasTitle = false;
            for (CrewMissionSnapshot snapshot : delivered) {
                if (!snapshot.title.isEmpty()) hasTitle = true;
                if (hasTitle) assertEquals("Review the whole mission", snapshot.title);
            }
            assertTrue(hasTitle);
            assertEquals("Review the whole mission", delivered.get(delivered.size() - 1).title);
        } finally { releaseFallback.countDown(); }
    }

    private static Map<String,Object> args(String key, String value) {
        Map<String,Object> args = new LinkedHashMap<>();
        args.put("role", "custom"); args.put("tools", Collections.emptyList()); args.put("name", "Worker");
        args.put(key, value);
        return args;
    }

    private static ModelReply done() { return new ModelReply("Verified result", Collections.emptyList()); }

    private static void await(CrewManager.Bot bot) throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        Thread waiter = new Thread(() -> { try { bot.awaitTermination(); ended.countDown(); } catch (InterruptedException ignored) { } });
        waiter.setDaemon(true); waiter.start();
        try { assertTrue("Worker did not terminate", ended.await(5, TimeUnit.SECONDS)); }
        finally { waiter.interrupt(); }
        assertEquals(bot.error(), CrewManager.Status.DONE, bot.status());
    }

    private static String repeat(String text, int count) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < count; i++) value.append(text);
        return value.toString();
    }
}
