package com.jarvys.agent.crew;

import static org.junit.Assert.*;

import com.jarvys.agent.CancellationToken;
import com.jarvys.agent.ConversationTurn;
import com.jarvys.agent.CoreAgentLoop;
import com.jarvys.agent.CorePromptBudget;
import com.jarvys.agent.CoreToolRegistry;
import com.jarvys.agent.ModelReply;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;

public class CrewManagerRecoveryTest {
    @Test public void restoredBotNeedsExplicitResumeAndDoesNotReplayUncertainCall() throws Exception {
        AtomicInteger turns = new AtomicInteger();
        CoreAgentLoop.Model model = (transcript, prompt, tools, token) -> {
            turns.incrementAndGet();
            assertTrue(transcript.stream().anyMatch(turn -> turn.content.contains("INTERRUPTED_UNCERTAIN")));
            assertTrue(transcript.stream().anyMatch(turn -> turn.content.contains("Reconciliation observation")));
            return new ModelReply("Reviewed retained evidence", Collections.emptyList());
        };
        try (CrewManager manager = manager(model)) {
            manager.configureCheckpoints(support());
            CoreAgentLoop.Checkpoint saved = new CoreAgentLoop.Checkpoint(Collections.singletonList(
                    ConversationTurn.toolCalls("", Collections.singletonList(new ModelReply.Call("uncertain", "project_exec", Collections.emptyMap())))),
                    Collections.emptySet(), Collections.singletonMap("uncertain", "STARTED"), -1);
            CrewManager.Bot bot = restore(manager, "RUNNING", saved, Collections.emptyList(), "");
            assertEquals(CrewManager.Status.INTERRUPTED, bot.status());
            assertTrue(bot.requiresExplicitResume());
            assertTrue(bot.canResume());
            assertEquals(0, turns.get());
            assertThrows(IllegalArgumentException.class, () -> manager.sendUserMessage(bot.id, "continue"));
            manager.resume(bot.id);
            awaitFinished(bot);
            assertEquals(CrewManager.Status.DONE, bot.status());
            assertEquals(1, turns.get());
            assertEquals("INTERRUPTED_UNCERTAIN", bot.checkpoint().toolLifecycle.get("uncertain"));
        }
    }

    @Test public void failedPersistenceDoesNotAcknowledgeOrDrainPendingMessages() throws Exception {
        try (CrewManager manager = manager((transcript, prompt, tools, token) -> new ModelReply("done", Collections.emptyList()))) {
            CrewMessage pending = new CrewMessage("message-one", "chat-one", "user", "bot-one", CrewMessage.Type.USER,
                    "Retain this correction", Collections.emptyList(), 1L);
            CrewManager.Bot bot = restore(manager, "FAILED", CoreAgentLoop.Checkpoint.empty(), Collections.singletonList(pending), "");
            CoreAgentLoop.Checkpoint applied = new CoreAgentLoop.Checkpoint(Collections.singletonList(new ConversationTurn("user", pending.text)),
                    Collections.singleton(pending.id), Collections.emptyMap(), 0);
            manager.configureCheckpoints(new CrewManager.CheckpointSupport() {
                @Override public void persist(CrewManager.Bot value, List<CrewMessage> messages, List<CrewMessage> pendingRows) {
                    assertEquals(pending.id, pendingRows.get(0).id);
                    throw new IllegalStateException("storage failed");
                }
                @Override public CrewManager.ResumePlan reconcile(CrewManager.Bot value) { throw new AssertionError(); }
            });
            assertThrows(IllegalStateException.class, () -> manager.retainCheckpoint(bot, applied, new JSONObject()));
            assertEquals(1, manager.messageBus().pending(bot.id).size());
            AtomicBoolean observedPendingBeforeCommit = new AtomicBoolean();
            manager.configureCheckpoints(new CrewManager.CheckpointSupport() {
                @Override public void persist(CrewManager.Bot value, List<CrewMessage> messages, List<CrewMessage> pendingRows) {
                    observedPendingBeforeCommit.set(pendingRows.stream().anyMatch(message -> message.id.equals(pending.id)));
                }
                @Override public CrewManager.ResumePlan reconcile(CrewManager.Bot value) { return new CrewManager.ResumePlan(value.role, "observation"); }
            });
            manager.retainCheckpoint(bot, applied, new JSONObject());
            assertTrue(observedPendingBeforeCommit.get());
            assertTrue(manager.messageBus().pending(bot.id).isEmpty());
        }
    }

    @Test public void failedResumeAndHistoricalOnlyBotsNeverLaunch() throws Exception {
        AtomicInteger turns = new AtomicInteger();
        try (CrewManager manager = manager((transcript, prompt, tools, token) -> {
            turns.incrementAndGet(); return new ModelReply("done", Collections.emptyList());
        })) {
            manager.configureCheckpoints(new CrewManager.CheckpointSupport() {
                @Override public void persist(CrewManager.Bot bot, List<CrewMessage> messages, List<CrewMessage> pending) { }
                @Override public CrewManager.ResumePlan reconcile(CrewManager.Bot bot) { throw new IllegalStateException("Project root identity changed"); }
            });
            CrewManager.Bot bot = restore(manager, "INTERRUPTED", CoreAgentLoop.Checkpoint.empty(), Collections.emptyList(), "");
            assertThrows(IllegalStateException.class, () -> manager.resume(bot.id));
            assertTrue(bot.requiresExplicitResume());
            assertEquals(CrewManager.Status.INTERRUPTED, bot.status());
            assertTrue(bot.recoveryNote().contains("identity changed"));
            assertEquals(0, turns.get());
        }
        try (CrewManager manager = manager((transcript, prompt, tools, token) -> { throw new AssertionError("Historical execution"); })) {
            manager.configureCheckpoints(support());
            CrewManager.Bot history = restore(manager, "INTERRUPTED", null, Collections.emptyList(), "Historical view only");
            assertFalse(history.canResume());
            assertThrows(IllegalStateException.class, () -> manager.resume(history.id));
        }
    }

    @Test public void ownedWorkKeepsMissionOpenAndStopCancelsItBeforeTermination() throws Exception {
        CountDownLatch firstReply = new CountDownLatch(1);
        AtomicBoolean pending = new AtomicBoolean(true);
        AtomicBoolean cancelled = new AtomicBoolean();
        CoreAgentLoop.Model model = (transcript, prompt, tools, token) -> {
            firstReply.countDown();
            return new ModelReply("intermediate result", Collections.emptyList());
        };
        try (CrewManager manager = manager(model)) {
            manager.configureCheckpoints(support());
            CrewManager.Bot bot = restore(manager, "INTERRUPTED", CoreAgentLoop.Checkpoint.empty(), Collections.emptyList(), "");
            manager.registerOwnedWork(bot, new CrewManager.OwnedWork() {
                @Override public void cancelAndAwait() { cancelled.set(true); pending.set(false); }
                @Override public boolean pending() { return pending.get(); }
            });
            manager.resume(bot.id);
            assertTrue(firstReply.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> manager.reportDone(bot, "premature success", Collections.emptyList()));
            assertTrue(manager.stop(bot.id));
            awaitFinished(bot);
            assertTrue(cancelled.get());
            assertEquals(CrewManager.Status.STOPPED, bot.status());
            assertTrue(bot.requiresExplicitResume());
            assertTrue(bot.canResume());
            assertFalse(manager.messageBus().snapshot().stream().anyMatch(message -> message.type == CrewMessage.Type.RESULT
                    && "intermediate result".equals(message.text)));
        }
    }

    @Test public void mailboxRestoreDeduplicatesAndRejectsForeignConversations() {
        CrewMessageBus bus = new CrewMessageBus("chat-one");
        CrewMessage message = new CrewMessage("id-one", "chat-one", "chief", "bot-one", CrewMessage.Type.ANSWER,
                "Answer", Collections.emptyList(), 1L);
        bus.restore(Arrays.asList(message, message), Arrays.asList(message, message));
        bus.restore(Collections.singletonList(message), Collections.singletonList(message));
        assertEquals(1, bus.snapshot().size());
        assertEquals(1, bus.pending("bot-one").size());
        assertThrows(UnsupportedOperationException.class, () -> bus.pending("bot-one").clear());
        CrewMessage foreign = new CrewMessage("id-two", "another-chat", "chief", "bot-one", CrewMessage.Type.ANSWER,
                "Wrong scope", Collections.emptyList(), 1L);
        assertThrows(IllegalArgumentException.class, () -> bus.restore(Collections.singletonList(foreign), Collections.emptyList()));
        bus.record("bot-one", "chief", CrewMessage.Type.STATUS, "Presentation only", Collections.emptyList());
        assertFalse(bus.hasMessages("chief"));
    }

    @Test public void botSnapshotRoundTripRetainsRecoveryFlagsAndActiveHistoryRequiresResume() {
        CrewBotSnapshot bot = new CrewBotSnapshot("bot-one", "coding", "Coding", "Bot", "coding", "mission", "STOPPED",
                "", "evidence", "", Collections.emptyList(), 1L, 2L, true, "Review evidence", true);
        CrewMissionSnapshot mission = new CrewMissionSnapshot("mission-one", "chat-one", "process", "Mission", "STOPPED", "",
                1L, 2L, Collections.singletonList(bot), Collections.emptyList());
        CrewBotSnapshot restored = CrewMissionSnapshot.fromJson(mission.toJson()).bots.get(0);
        assertTrue(restored.canResume);
        assertTrue(restored.resumeRequired);
        assertEquals("Review evidence", restored.recoveryNote);
        CrewBotSnapshot active = new CrewBotSnapshot("bot-one", "coding", "Coding", "Bot", "coding", "mission", "RUNNING",
                "", "evidence", "", Collections.emptyList(), 1L, 0L);
        assertTrue(active.interrupted().resumeRequired);
        assertFalse(active.interrupted().canResume);
    }

    private static CrewManager manager(CoreAgentLoop.Model model) {
        CoreToolRegistry empty = new CoreToolRegistry(Collections.emptyList());
        return new CrewManager("chat-one", empty, (bot, manager) -> empty,
                (bot, tools, inbox) -> new CoreAgentLoop(model, tools, "", "chat-one", CorePromptBudget.standard(), null,
                        CoreAgentLoop.Limits.UNBOUNDED, inbox, null), null);
    }
    private static CrewManager.CheckpointSupport support() {
        return new CrewManager.CheckpointSupport() {
            @Override public void persist(CrewManager.Bot bot, List<CrewMessage> messages, List<CrewMessage> pending) { }
            @Override public CrewManager.ResumePlan reconcile(CrewManager.Bot bot) { return new CrewManager.ResumePlan(bot.role, "Reconciliation observation"); }
        };
    }
    private static CrewManager.Bot restore(CrewManager manager, String status, CoreAgentLoop.Checkpoint checkpoint,
                                           List<CrewMessage> pending, String issue) {
        CrewProfile profile = new CrewProfile("coding", 1, "Coding", "Project work", "Mission", Collections.emptyList(),
                Collections.emptyList(), CrewProfile.WorkspaceMode.CONVERSATION_PROJECT);
        CrewBotSnapshot bot = new CrewBotSnapshot("bot-one", "coding", "Coding", "Bot", "coding", "mission", status,
                "", "", "", Collections.emptyList(), 1L, 0L);
        CrewMissionSnapshot mission = new CrewMissionSnapshot("mission-one", "chat-one", "process", "Mission", "INTERRUPTED", "",
                1L, 0L, Collections.singletonList(bot), pending);
        return manager.restoreBot(mission, bot, profile.resolveRole(Collections.emptyList(), Collections.emptyList()), checkpoint,
                new JSONObject(), "scope-one", Collections.emptyList(), 1L, pending, pending, issue);
    }
    private static void awaitFinished(CrewManager.Bot bot) throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        Thread waiter = new Thread(() -> { try { bot.awaitTermination(); ended.countDown(); } catch (InterruptedException ignored) { } });
        waiter.setDaemon(true);
        waiter.start();
        try { assertTrue("Bot did not terminate", ended.await(3, TimeUnit.SECONDS)); }
        finally { waiter.interrupt(); }
    }
}
