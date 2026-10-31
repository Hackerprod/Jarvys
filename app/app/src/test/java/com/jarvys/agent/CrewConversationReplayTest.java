package com.jarvys.agent;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.jarvys.agent.chatdb.ChatDatabaseBridge;
import com.jarvys.agent.chatdb.ChatDbResult;
import com.jarvys.agent.crew.CrewBotSnapshot;
import com.jarvys.agent.crew.CrewManager;
import com.jarvys.agent.crew.CrewMessage;
import com.jarvys.agent.crew.CrewProfile;
import com.jarvys.agent.crew.CrewMissionAccess;
import com.jarvys.agent.crew.CrewRole;
import com.jarvys.agent.workspaces.WorkspaceIdentity;
import com.jarvys.agent.workspaces.WorkspaceRegistry;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class, shadows = ArtifactOsShadow.class)
public final class CrewConversationReplayTest {
    @Rule public final ChatTestStore chatTestStore = new ChatTestStore(false, true);

    private final Context context = ApplicationProvider.getApplicationContext();

    @Test public void uncertifiedLegacyCheckpointCannotBaselineMessagesOrCreateCentralInputs() throws Exception {
        assertUncertifiedCheckpointDoesNotDispatch(fixture(false,false,Collections.emptyList()),true);
    }
    @Test public void boundAssignmentAloneCannotCertifyCheckpointOrReplayItsOutbox() throws Exception {
        assertUncertifiedCheckpointDoesNotDispatch(fixture(true,true,Collections.emptyList()),false);
    }
    @Test public void productionListenerBeforeRestoreCannotAuthorizeAnUncertifiedOutbox() throws Exception {
        assertUncertifiedCheckpointDoesNotDispatch(fixture(true,true,Collections.emptyList()),true);
    }
    @Test public void unresolvedAssignmentRemainsVisibleWithoutCreatingDeliveryReceipt() throws Exception {
        assertUncertifiedCheckpointDoesNotDispatch(fixture(false,true,Collections.emptyList()),true);
    }
    private void assertUncertifiedCheckpointDoesNotDispatch(Fixture fixture,boolean listenerBeforeRestore) throws Exception {
        CrewCheckpointStore store=new CrewCheckpointStore(context.getFilesDir());
        String before=store.confirmDurable(fixture.sessionId,fixture.botId,"saved-scope").payloadSha256;
        for(int restart=0;restart<2;restart++) try(CrewManager manager=manager(fixture.sessionId)) {
            if(listenerBeforeRestore)configureProductionDelivery(manager,fixture);
            else manager.configureCheckpoints(coordinator(fixture));
            coordinator(fixture).restore(manager,Collections.emptyList());
            if(!listenerBeforeRestore)manager.configureConversationMessageListener((bot,message)->WorkspaceAgentInputDelivery.deliver(context,bot,message));
            CrewManager.Bot restored=manager.bot(fixture.botId);
            assertNotNull("Uncertified evidence remains visible, not silently discarded",restored);
            assertFalse("A synthetic saved-scope cannot authorize current recovery",restored.canResume());
            assertFalse("No delivery receipt may be invented",restored.conversationMessageProcessed(fixture.message.id));
            assertFalse(hasCentralInput(fixture));assertEquals(0,countCentralInput(fixture));
            assertNull("Restore/scan must not queue a Central turn",Shadows.shadowOf((Application)context).getNextStartedService());
            assertEquals("Uncertified bytes are preserved without baseline/migration",before,
                store.confirmDurable(fixture.sessionId,fixture.botId,"saved-scope").payloadSha256);
        }
    }

    private void configureProductionDelivery(CrewManager manager, Fixture fixture) {
        manager.configureCheckpoints(coordinator(fixture));
        manager.configureConversationMessageListener((bot, message) ->
                WorkspaceAgentInputDelivery.deliver(context, bot, message));
    }

    private CrewCheckpointCoordinator coordinator(Fixture fixture) {
        return new CrewCheckpointCoordinator(context, fixture.sessionId, bot -> bot.role);
    }

    private Fixture fixture(boolean boundAssignment, boolean versioned, java.util.List<String> receipts) throws Exception {
        String key = UUID.randomUUID().toString();
        WorkspaceRegistry registry = new WorkspaceRegistry(context);
        try {
            com.jarvys.agent.workspaces.WorkspaceRecord workspace = registry.createWorkspace(
                    "crew-replay-" + key, "Crew replay test");
            String session = workspace.getExecutionSessionId();
            assertTrue(ChatDatabaseBridge.getInstance(context).ensureConversationSync(session, "workspace")
                    instanceof ChatDbResult.Success);
            assertTrue(ChatDatabaseBridge.getInstance(context).ensureConversationSync(
                    WorkspaceIdentity.CENTRAL_SESSION, "chat") instanceof ChatDbResult.Success);

            String botId = "worker-" + key;
            CrewProfile profile = profile();
            String missionId = "mission-" + key;
            String assignmentId = "";
            if (boundAssignment) {
                com.jarvys.agent.workspaces.WorkspaceAssignment prepared = registry.prepareAssignment(
                        workspace.getWorkspaceId(), "assignment-" + key, "request-" + key,
                        "Owner request", profile.id, "Review the project", Collections.emptyList(), "Worker",
                        CrewMissionAccess.STANDARD);
                com.jarvys.agent.workspaces.WorkspaceAssignment started = registry.markStarted(
                        workspace.getWorkspaceId(), prepared.getAssignmentId(), prepared.getRevision(), "process-" + key);
                registry.bindBot(workspace.getWorkspaceId(), prepared.getAssignmentId(), started.getRevision(), botId);
                missionId = prepared.getMissionId();
                assignmentId = prepared.getAssignmentId();
            }

            CrewMessage message = new CrewMessage("source-" + key, session, botId, "chief",
                    CrewMessage.Type.RESULT, "Worker result " + key, Collections.emptyList(), System.currentTimeMillis());
            JSONObject metadata = new JSONObject().put("conversationId", session).put("botId", botId)
                    .put("missionId", missionId).put("mission", "Review the project")
                    .put("missionAccess", CrewMissionAccess.STANDARD.value)
                    .put("name", "Worker").put("status", "DONE").put("result", message.text)
                    .put("error", "").put("startedAtMillis", 1L).put("finishedAtMillis", 2L)
                    .put("completedCycles", 1L).put("jobOwners", new JSONArray())
                    .put("role", profile.toJson()).put("messages", new JSONArray().put(message.toJson()))
                    .put("pending", new JSONArray());
            if (!assignmentId.isEmpty()) metadata.put("assignmentId", assignmentId);
            if (versioned) metadata.put("conversationMessageDeliveryVersion", 1)
                    .put("processedConversationMessageIds", new JSONArray(receipts));
            new CrewCheckpointStore(context.getFilesDir()).save(session, botId, metadata, "saved-scope",
                    CoreAgentLoop.Checkpoint.empty(), new JSONObject());
            return new Fixture(session, workspace.getWorkspaceId(), botId, missionId, assignmentId, message, profile);
        } finally {
            registry.close();
        }
    }

    private boolean hasCentralInput(Fixture fixture) {
        return centralRows().stream().anyMatch(row -> {
            JSONObject provenance = row.optJSONObject("provenance");
            return provenance != null && fixture.message.id.equals(provenance.optString("messageId"));
        });
    }

    private long countCentralInput(Fixture fixture) {
        return centralRows().stream().filter(row -> {
            JSONObject provenance = row.optJSONObject("provenance");
            return provenance != null && fixture.message.id.equals(provenance.optString("messageId"));
        }).count();
    }

    private java.util.List<JSONObject> centralRows() {
        return chatTestStore.onDatabaseWorker(() ->
                new LocalRunStore(context).readConversationMessages(WorkspaceIdentity.CENTRAL_SESSION));
    }

    private static boolean arrayContains(JSONArray values, String expected) {
        for (int index = 0; index < values.length(); index++) if (expected.equals(values.optString(index))) return true;
        return false;
    }

    private static CrewManager manager(String session) {
        CoreToolRegistry empty = new CoreToolRegistry(Collections.emptyList());
        return new CrewManager(session, empty, (bot, manager) -> empty,
                (bot, tools, inbox) -> new CoreAgentLoop((history, prompt, declarations, token) -> {
                    throw new AssertionError("Checkpoint restore must not run a worker model");
                }, tools, "", session, CorePromptBudget.standard(), null,
                        CoreAgentLoop.ToolResultBudget.UNBOUNDED, inbox, null), null);
    }

    private static CrewProfile profile() {
        return new CrewProfile("replay-worker", 1, "Worker", "Replay test worker", "Review the assigned project",
                Collections.emptyList(), Collections.emptyList(), CrewProfile.WorkspaceMode.CONVERSATION_PROJECT);
    }

    private static final class Fixture {
        final String sessionId, workspaceId, botId, missionId, assignmentId;
        final CrewMessage message;
        final CrewProfile profile;
        Fixture(String sessionId, String workspaceId, String botId, String missionId, String assignmentId,
                CrewMessage message, CrewProfile profile) {
            this.sessionId = sessionId;
            this.workspaceId = workspaceId;
            this.botId = botId;
            this.missionId = missionId;
            this.assignmentId = assignmentId;
            this.message = message;
            this.profile = profile;
        }
    }
}
