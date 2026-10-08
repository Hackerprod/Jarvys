package com.jarvys.agent.crew;

import static org.junit.Assert.*;
import com.jarvys.agent.CoreAgentLoop;
import com.jarvys.agent.CorePromptBudget;
import com.jarvys.agent.CoreToolRegistry;
import com.jarvys.agent.ModelReply;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BotCatalogRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void disablingLegacyDefinitionCancelsAllMissionsAndBlocksSpawnAndFollowup() throws Exception {
        CrewProfileRepository repository = new CrewProfileRepository(temporary.newFolder());
        BotDefinition definition = custom(repository);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch hold = new CountDownLatch(1);
        AtomicBoolean cancelledOwnedWork = new AtomicBoolean();
        try (CrewManager manager = manager((transcript, prompt, tools, token) -> {
            started.countDown();
            try { hold.await(); } catch (InterruptedException interrupted) { token.throwIfCancelled(); }
            return new ModelReply("done", Collections.emptyList());
        })) {
            configure(manager, repository);
            manager.beginMission("mission-one", "One");
            CrewManager.Bot first = manager.spawn(definition.id, "First mission", null, "Renamed UI label");
            manager.beginMission("mission-two", "Two");
            CrewManager.Bot second = manager.spawn(definition.id, "Second mission", null, null);
            manager.registerOwnedWork(first, new CrewManager.OwnedWork() {
                @Override public void cancelAndAwait() { cancelledOwnedWork.set(true); }
                @Override public boolean pending() { return false; }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            repository.setEnabled(definition.id, definition.revision, false);
            await(first); await(second);
            assertTrue(cancelledOwnedWork.get());
            assertEquals(CrewManager.Status.STOPPED, first.status());
            assertEquals(CrewManager.Status.STOPPED, second.status());
            assertTrue(first.token.isCancellationRequested());
            assertFalse(first.canResume());
            assertThrows(IllegalArgumentException.class, () -> manager.spawn(definition.id, "Disabled", null, null));
            assertThrows(IllegalArgumentException.class, () -> manager.send("chief", first.id, CrewMessage.Type.ANSWER, "Follow up", Collections.emptyList()));
            assertThrows(IllegalArgumentException.class, () -> manager.sendUserMessage(second.id, "Continue"));
            assertThrows(IllegalStateException.class, () -> manager.resume(first.id));
            assertEquals(definition.id, manager.missionSnapshots().get(0).bots.get(0).roleId);
        } finally { hold.countDown(); }
    }

    @Test public void completedLegacyBotCannotReanimateAfterDisableEvenWithoutChangeListener() throws Exception {
        CrewProfileRepository repository = new CrewProfileRepository(temporary.newFolder());
        BotDefinition definition = custom(repository);
        AtomicInteger calls = new AtomicInteger();
        try (CrewManager manager = manager((transcript, prompt, tools, token) -> {
            calls.incrementAndGet(); return new ModelReply("done", Collections.emptyList());
        })) {
            manager.configureProfiles(id -> repository.resolveRole(id, Collections.emptyList(), Collections.emptyList()));
            CrewManager.Bot bot = manager.spawn(definition.id, "Finish", null, null);
            await(bot);
            assertEquals(CrewManager.Status.DONE, bot.status());
            repository.setEnabled(definition.id, definition.revision, false);
            assertThrows(IllegalArgumentException.class, () -> manager.sendUserMessage(bot.id, "Again"));
            assertThrows(IllegalArgumentException.class, () -> manager.send("chief", bot.id, CrewMessage.Type.ANSWER, "Again", Collections.emptyList()));
            assertEquals(1, calls.get());
        }
    }

    @Test public void metadataIconChangeKeepsRunButProfileChangeCancelsIt() throws Exception {
        CrewProfileRepository repository = new CrewProfileRepository(temporary.newFolder());
        BotDefinition definition = custom(repository);
        CountDownLatch started = new CountDownLatch(1);
        try (CrewManager manager = manager((transcript, prompt, tools, token) -> {
            started.countDown();
            try { new CountDownLatch(1).await(); } catch (InterruptedException interrupted) { token.throwIfCancelled(); }
            return new ModelReply("done", Collections.emptyList());
        })) {
            configure(manager, repository);
            CrewManager.Bot bot = manager.spawn(definition.id, "Work", null, null);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            BotDefinition icon = repository.setIcon(definition.id, definition.revision, "12345678-1234-1234-1234-123456789abc.png");
            assertFalse(bot.token.isCancellationRequested());
            assertEquals(CrewManager.Status.RUNNING, bot.status());
            repository.save(icon, Collections.emptyList(), Collections.emptyList());
            await(bot);
            assertEquals(CrewManager.Status.STOPPED, bot.status());
            assertTrue(bot.requiresExplicitResume());
        }
    }

    @Test public void explicitResumeRejectsDisabledProfileBeforeRunningModel() throws Exception {
        CrewProfileRepository repository = new CrewProfileRepository(temporary.newFolder());
        BotDefinition definition = custom(repository);
        AtomicInteger calls = new AtomicInteger();
        try (CrewManager manager = manager((transcript, prompt, tools, token) -> {
            calls.incrementAndGet(); return new ModelReply("done", Collections.emptyList());
        })) {
            manager.configureProfiles(id -> repository.resolveRole(id, Collections.emptyList(), Collections.emptyList()));
            manager.configureCheckpoints(new CrewManager.CheckpointSupport() {
                @Override public void persist(CrewManager.Bot bot, java.util.List<CrewMessage> all, java.util.List<CrewMessage> pending) { }
                @Override public CrewManager.ResumePlan reconcile(CrewManager.Bot bot) { return new CrewManager.ResumePlan(bot.role, "Review"); }
            });
            CrewBotSnapshot visible = new CrewBotSnapshot("bot-restored", definition.id, "Helper", "Helper", definition.id, "Mission", "INTERRUPTED", "", "", "", Collections.emptyList(), 1, 0);
            CrewMissionSnapshot mission = new CrewMissionSnapshot("mission", "catalog-test", "past-process", "Mission", "INTERRUPTED", "", 1, 0, Collections.singletonList(visible), Collections.emptyList());
            CrewManager.Bot bot = manager.restoreBot(mission, visible, definition.profile.resolveRole(Collections.emptyList(), Collections.emptyList()),
                    CoreAgentLoop.Checkpoint.empty(), new org.json.JSONObject(), "scope", Collections.emptyList(), 1, Collections.emptyList(), Collections.emptyList(), "");
            repository.setEnabled(definition.id, definition.revision, false);
            assertThrows(IllegalArgumentException.class, () -> manager.resume(bot.id));
            assertEquals(0, calls.get());
            assertEquals(CrewManager.Status.INTERRUPTED, bot.status());
        }
    }

    private static void configure(CrewManager manager, CrewProfileRepository repository) {
        manager.configureProfiles(id -> repository.resolveRole(id, Collections.emptyList(), Collections.emptyList()));
        manager.configureProfileSubscription(repository.addChangeListener(manager::definitionChanged));
    }
    private static BotDefinition custom(CrewProfileRepository repository) {
        CrewProfile profile = new CrewProfile(CrewProfileRepository.newCustomId(), 1, "Helper", "Short metadata", "PRIVATE CHILD PROMPT", Collections.emptyList(), Collections.emptyList());
        return repository.create(profile, Collections.emptyList(), Collections.emptyList());
    }
    private static CrewManager manager(CoreAgentLoop.Model model) {
        CoreToolRegistry empty = new CoreToolRegistry(Collections.emptyList());
        return new CrewManager("catalog-test", empty, (bot, crew) -> empty,
                (bot, tools, incoming) -> new CoreAgentLoop(model, tools, "", "catalog-test", CorePromptBudget.standard(), null,
                        CoreAgentLoop.Limits.UNBOUNDED, incoming, null), null);
    }
    private static void await(CrewManager.Bot bot) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        Thread waiter = new Thread(() -> { try { bot.awaitTermination(); complete.countDown(); } catch (InterruptedException ignored) { } });
        waiter.setDaemon(true); waiter.start();
        try { assertTrue("worker did not terminate", complete.await(5, TimeUnit.SECONDS)); }
        finally { waiter.interrupt(); }
    }
}
