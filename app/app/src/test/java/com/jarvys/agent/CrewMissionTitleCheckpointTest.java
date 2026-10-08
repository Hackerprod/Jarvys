package com.jarvys.agent;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.jarvys.agent.crew.CrewManager;
import com.jarvys.agent.crew.CrewBotSnapshot;
import com.jarvys.agent.crew.CrewMissionAccess;
import com.jarvys.agent.crew.CrewMissionSnapshot;
import com.jarvys.agent.crew.CrewMissionTitle;
import com.jarvys.agent.crew.CrewProfile;
import com.jarvys.agent.crew.CrewRole;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CrewMissionTitleCheckpointTest {
    private static final CoreToolRegistry EMPTY = new CoreToolRegistry(Collections.emptyList());

    @Test public void currentCheckpointOnlyRecoveryPreservesTitleAndBothFullInstructionFields() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String session = "title-checkpoint-" + System.nanoTime();
        String original = "  Inspecciona todas las condiciones del proyecto.\n" + repeat("Conserva todo el contexto.\n", 30) + " Fin.  ";
        String worker = "  Revisa exclusivamente el módulo asignado.\n" + repeat("No pierdas ningún requisito.\n", 25);
        CrewProfile profile = profile();
        CrewRole role = profile.resolveRole(Collections.emptyList(), Collections.emptyList());
        CrewCheckpointCoordinator coordinator = new CrewCheckpointCoordinator(context, session, bot -> role);
        AtomicInteger modelCalls = new AtomicInteger();
        String botId;
        try (CrewManager manager = manager(session, modelCalls)) {
            manager.configureProfiles(id -> role);
            manager.configureCheckpoints(coordinator);
            manager.beginMission("original-mission", original);
            CrewManager.Bot bot = manager.spawn(profile.id, worker, null, "Reviewer", CrewMissionAccess.READ_ONLY,
                    "Revisar condiciones del proyecto");
            await(bot);
            botId = bot.id;
            assertEquals(1, modelCalls.get());
        }
        CrewCheckpointStore.Snapshot checkpoint = new CrewCheckpointStore(context.getFilesDir()).load(session, botId);
        JSONObject presentation = checkpoint.metadata.getJSONObject("missionPresentation");
        assertEquals(original, presentation.getString("originalInstructions"));
        assertEquals("Revisar condiciones del proyecto", presentation.getString("title"));
        assertEquals(worker, checkpoint.metadata.getString("mission"));

        try (CrewManager restored = manager(session, modelCalls)) {
            coordinator.restore(restored, Collections.emptyList());
            CrewMissionSnapshot snapshot = restored.missionSnapshots().get(0);
            assertEquals("Revisar condiciones del proyecto", snapshot.title);
            assertEquals(CrewMissionTitle.AGENT, snapshot.titleSource);
            assertEquals(original, snapshot.originalInstructions);
            assertEquals(worker, snapshot.bots.get(0).mission);
            assertEquals(CrewMissionAccess.READ_ONLY, restored.bot(botId).role.missionAccess);
            assertTrue(restored.bot(botId).requiresExplicitResume());
            assertEquals(1, modelCalls.get());
        }
    }

    @Test public void legacyOrphanCheckpointDoesNotInventOriginalUserRequestOrAgentTitle() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String session = "title-legacy-checkpoint-" + System.nanoTime();
        String worker = "  Worker-only mission.\n" + repeat("Retain all original bot instructions.\n", 15);
        saveCheckpoint(context, session, "legacy-bot", worker, null);
        AtomicInteger calls = new AtomicInteger();
        try (CrewManager manager = manager(session, calls)) {
            new CrewCheckpointCoordinator(context, session, bot -> bot.role).restore(manager, Collections.emptyList());
            CrewMissionSnapshot snapshot = manager.missionSnapshots().get(0);
            assertEquals("", snapshot.title);
            assertEquals(CrewMissionTitle.FALLBACK, snapshot.titleSource);
            assertEquals("", snapshot.originalInstructions);
            assertEquals(worker, snapshot.bots.get(0).mission);
            assertTrue(manager.bot("legacy-bot").canResume());
            assertEquals(0, calls.get());
        }
    }

    @Test public void invalidCheckpointTitleCannotPreventSafeRecoveryOfFullInstructions() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String session = "title-invalid-checkpoint-" + System.nanoTime();
        String original = "Entire user request\n" + repeat("Preserve every requirement. ", 10);
        JSONObject presentation = new JSONObject().put("schemaVersion", 1).put("title", repeat("x", 61))
                .put("titleSource", CrewMissionTitle.AGENT).put("originalInstructions", original);
        saveCheckpoint(context, session, "saved-bot", "Only the worker's task", presentation);
        try (CrewManager manager = manager(session, new AtomicInteger())) {
            new CrewCheckpointCoordinator(context, session, bot -> bot.role).restore(manager, Collections.emptyList());
            CrewMissionSnapshot snapshot = manager.missionSnapshots().get(0);
            assertEquals("", snapshot.title);
            assertEquals(CrewMissionTitle.FALLBACK, snapshot.titleSource);
            assertEquals(original, snapshot.originalInstructions);
            assertEquals("Only the worker's task", snapshot.bots.get(0).mission);
            assertTrue(manager.bot("saved-bot").canResume());
        }
    }

    @Test public void presentationMetadataUsesExistingCheckpointSecretRedaction() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String session = "title-redaction-checkpoint-" + System.nanoTime();
        JSONObject presentation = new JSONObject().put("schemaVersion", 1).put("title", "Review configured access")
                .put("titleSource", CrewMissionTitle.AGENT).put("originalInstructions", "Inspect this setting: password=do-not-store");
        saveCheckpoint(context, session, "saved-bot", "Review settings", presentation);
        JSONObject saved = new CrewCheckpointStore(context.getFilesDir()).load(session, "saved-bot").metadata;
        assertFalse(saved.toString().contains("do-not-store"));
        assertTrue(saved.getJSONObject("missionPresentation").getString("originalInstructions").contains("REDACTED"));
    }

    @Test public void validatedCheckpointUpgradesStaleLedgerFallbackWithoutReplacingUserRequest() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String session = "title-stale-ledger-" + System.nanoTime();
        JSONObject presentation = new JSONObject().put("schemaVersion", 1).put("title", "Review complete project")
                .put("titleSource", CrewMissionTitle.AGENT).put("originalInstructions", "Checkpoint request copy");
        saveCheckpoint(context, session, "known-bot", "Full worker mission", presentation);
        CrewMissionSnapshot ledger = ledger(session, "", "Exact original ledger request\nAll conditions retained", CrewMissionTitle.FALLBACK,
                "Full worker mission");
        try (CrewManager manager = manager(session, new AtomicInteger())) {
            new CrewCheckpointCoordinator(context, session, bot -> bot.role).restore(manager, Collections.singletonList(ledger));
            CrewMissionSnapshot restored = manager.missionSnapshots().get(0);
            assertEquals("Review complete project", restored.title);
            assertEquals(CrewMissionTitle.AGENT, restored.titleSource);
            assertEquals(ledger.originalInstructions, restored.originalInstructions);
            assertEquals(ledger.startedAtMillis, restored.startedAtMillis);
            assertEquals(ledger.finishedAtMillis, restored.finishedAtMillis);
            assertEquals(ledger.status, restored.status);
            assertEquals(ledger.synthesis, restored.synthesis);
            assertTrue(manager.bot("known-bot").canResume());
        }
    }

    @Test public void checkpointCannotReplaceFirstValidLedgerTitleButCanRecoverUnknownRequest() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String session = "title-stable-ledger-" + System.nanoTime();
        JSONObject presentation = new JSONObject().put("schemaVersion", 1).put("title", "Different checkpoint title")
                .put("titleSource", CrewMissionTitle.AGENT).put("originalInstructions", "Recovered actual user request\nwith every detail");
        saveCheckpoint(context, session, "known-bot", "Full worker mission", presentation);
        CrewMissionSnapshot ledger = ledger(session, "First accepted mission title", "", CrewMissionTitle.AGENT, "Full worker mission");
        try (CrewManager manager = manager(session, new AtomicInteger())) {
            new CrewCheckpointCoordinator(context, session, bot -> bot.role).restore(manager, Collections.singletonList(ledger));
            CrewMissionSnapshot restored = manager.missionSnapshots().get(0);
            assertEquals(ledger.title, restored.title);
            assertEquals(presentation.getString("originalInstructions"), restored.originalInstructions);
        }
    }

    @Test public void mismatchedCheckpointCannotSupplyPresentationForKnownLedgerMission() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String session = "title-mismatch-ledger-" + System.nanoTime();
        JSONObject presentation = new JSONObject().put("schemaVersion", 1).put("title", "Unrelated checkpoint title")
                .put("titleSource", CrewMissionTitle.AGENT).put("originalInstructions", "Unrelated request");
        saveCheckpoint(context, session, "known-bot", "Different bot mission", presentation);
        CrewMissionSnapshot ledger = ledger(session, "", "", CrewMissionTitle.FALLBACK, "Full worker mission");
        try (CrewManager manager = manager(session, new AtomicInteger())) {
            new CrewCheckpointCoordinator(context, session, bot -> bot.role).restore(manager, Collections.singletonList(ledger));
            CrewMissionSnapshot restored = manager.missionSnapshots().get(0);
            assertEquals("", restored.title);
            assertEquals("", restored.originalInstructions);
            assertEquals("Full worker mission", restored.bots.get(0).mission);
            assertFalse(manager.bot("known-bot").canResume());
            assertTrue(manager.bot("known-bot").recoveryNote().contains("identity"));
        }
    }

    private static CrewMissionSnapshot ledger(String session, String title, String original, String source, String worker) {
        CrewBotSnapshot bot = new CrewBotSnapshot("known-bot", profile().id, "Reviewer", "Saved worker", profile().id,
                worker, "INTERRUPTED", "", "", "", Collections.emptyList(), 1L, 2L);
        return new CrewMissionSnapshot("saved-mission", session, "old-process", title, original, source,
                "INTERRUPTED", "Prior synthesis", 10L, 20L, Collections.singletonList(bot), Collections.emptyList());
    }

    private static void saveCheckpoint(Context context, String session, String id, String worker, JSONObject presentation) throws Exception {
        JSONObject metadata = new JSONObject().put("conversationId", session).put("botId", id)
                .put("missionId", "saved-mission").put("mission", worker).put("name", "Saved worker")
                .put("status", "RUNNING").put("role", profile().toJson()).put("startedAtMillis", 1L)
                .put("finishedAtMillis", 0L).put("completedCycles", 0L).put("jobOwners", new JSONArray())
                .put("messages", new JSONArray()).put("pending", new JSONArray());
        if (presentation != null) metadata.put("missionPresentation", presentation);
        new CrewCheckpointStore(context.getFilesDir()).save(session, id, metadata, "saved-scope",
                CoreAgentLoop.Checkpoint.empty(), new JSONObject());
    }

    private static CrewProfile profile() {
        return new CrewProfile("title-reviewer", 1, "Reviewer", "Review the project", "Inspect only the assigned scope",
                Collections.emptyList(), Collections.emptyList(), CrewProfile.WorkspaceMode.CONVERSATION_PROJECT);
    }

    private static CrewManager manager(String session, AtomicInteger calls) {
        return new CrewManager(session, EMPTY, (bot, manager) -> EMPTY,
                (bot, tools, incoming) -> new CoreAgentLoop((history, prompt, specs, token) -> {
                    calls.incrementAndGet(); return new ModelReply("Reviewed", Collections.emptyList());
                }, tools, "", session, CorePromptBudget.standard(), null,
                        CoreAgentLoop.Limits.UNBOUNDED, incoming, null), null);
    }

    private static void await(CrewManager.Bot bot) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        Thread waiter = new Thread(() -> { try { bot.awaitTermination(); finished.countDown(); } catch (InterruptedException ignored) { } });
        waiter.setDaemon(true); waiter.start();
        try { assertTrue(finished.await(10, TimeUnit.SECONDS)); }
        finally { waiter.interrupt(); }
        assertEquals(bot.error(), CrewManager.Status.DONE, bot.status());
    }

    private static String repeat(String text, int count) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < count; i++) value.append(text);
        return value.toString();
    }
}
