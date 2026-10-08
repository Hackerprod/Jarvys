package com.jarvys.agent;

import android.content.Context;
import com.jarvys.agent.coding.ProjectScope;
import com.jarvys.agent.coding.ProjectScopeStore;
import com.jarvys.agent.crew.CrewBotSnapshot;
import com.jarvys.agent.crew.CrewManager;
import com.jarvys.agent.crew.CrewMessage;
import com.jarvys.agent.crew.CrewMissionSnapshot;
import com.jarvys.agent.crew.CrewProfile;
import com.jarvys.agent.crew.CrewRole;
import com.jarvys.agent.crew.CrewRoleTemplates;
import com.jarvys.agent.flavor.FlavorLinuxTools;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class CrewCheckpointCoordinator implements CrewManager.CheckpointSupport {
    private final Context context;
    private final String conversation;
    private final Function<CrewManager.Bot, CrewRole> currentRole;
    private final CrewCheckpointStore store;

    CrewCheckpointCoordinator(Context context, String conversation, Function<CrewManager.Bot, CrewRole> currentRole) {
        this.context = context.getApplicationContext();
        this.conversation = conversation;
        this.store = new CrewCheckpointStore(context.getFilesDir());
        this.currentRole = currentRole;
    }

    @Override
    public boolean canPersist(CrewManager.Bot bot) {
        return bot.role.profileVersion > 0 && bot.role.workspaceMode == CrewProfile.WorkspaceMode.CONVERSATION_PROJECT;
    }

    @Override
    public void persist(CrewManager.Bot bot, List<CrewMessage> messages, List<CrewMessage> pending) {
        if (bot.role.profileVersion <= 0 || bot.role.workspaceMode != CrewProfile.WorkspaceMode.CONVERSATION_PROJECT) {
            return;
        }
        try {
            if (bot.scopeIdentity().isEmpty()) {
                bot.setScopeIdentity(new ProjectScopeStore(this.context.getFilesDir()).open(this.conversation).durableIdentity());
            }
            JSONObject metadata = new JSONObject().put("conversationId", this.conversation).put("botId", bot.id).put("missionId", bot.missionId).put("mission", bot.mission).put("missionAccess", bot.role.missionAccess.value).put("name", bot.name).put("status", bot.status().name()).put("result", bot.result()).put("error", bot.error()).put("startedAtMillis", bot.startedAtMillis()).put("finishedAtMillis", bot.finishedAtMillis()).put("completedCycles", bot.completedCycles()).put("jobOwners", new JSONArray((Collection)bot.recoveredJobOwners())).put("role", new CrewProfile(bot.role.id, bot.role.profileVersion, bot.role.name, bot.role.description, bot.role.missionPrompt, bot.role.skillIds, bot.role.tools, bot.role.workspaceMode).toJson()).put("messages", encodeMessages(messages, bot.id)).put("pending", encodeMessages(pending, bot.id));
            this.store.save(this.conversation, bot.id, metadata, bot.scopeIdentity(), bot.checkpoint(), bot.artifactOwnership());
        } catch (Exception failure) {
            throw new IllegalStateException("Could not save the bot checkpoint; no further work is safe", failure);
        }
    }

    void restore(CrewManager manager, List<CrewMissionSnapshot> priorMissions) {
        List<CrewMissionSnapshot> missions = new ArrayList<>(priorMissions);
        Set<String> known = new HashSet<>();
        for (CrewMissionSnapshot mission : missions) {
            for (CrewBotSnapshot bot : mission.bots) known.add(bot.id);
        }
        for (CrewCheckpointStore.Snapshot saved : this.store.list(this.conversation)) {
            if (!known.add(saved.botId)) continue;
            try {
                JSONObject data = saved.metadata;
                CrewProfile profile = CrewProfile.fromJson(data.getJSONObject("role"));
                CrewBotSnapshot bot = new CrewBotSnapshot(saved.botId, profile.id, profile.name, data.getString("name"), profile.id, data.getString("mission"), data.getString("status"), data.optString("error"), data.optString("result"), "", profile.capabilities, data.getLong("startedAtMillis"), data.getLong("finishedAtMillis"));
                missions.add(new CrewMissionSnapshot(data.getString("missionId"), this.conversation, "recovered", data.getString("mission"), "INTERRUPTED", "", data.getLong("startedAtMillis"), saved.savedAtMs, Collections.singletonList(bot), Collections.emptyList()));
            } catch (Exception invalid) {
                restoreIssue(manager, "A saved checkpoint has invalid mission metadata. Original evidence has been preserved.");
            }
        }
        List<String> issues = this.store.listIssues(this.conversation);
        if (!issues.isEmpty()) {
            restoreIssue(manager, "Some checkpoints could not be read. Original evidence has been preserved. " + String.join("; ", issues));
        }
        for (CrewMissionSnapshot mission : missions) {
            for (CrewBotSnapshot visible : mission.bots) {
                if (manager.bot(visible.id) != null) continue;
                String issue = "Historical snapshot: no resumable transcript is available.";
                try {
                    CrewCheckpointStore.Snapshot saved = this.store.load(this.conversation, visible.id);
                    if (saved != null) {
                        JSONObject data = saved.metadata;
                        if (!this.conversation.equals(data.getString("conversationId")) || !visible.id.equals(data.getString("botId")) || !mission.missionId.equals(data.getString("missionId")) || !CrewCheckpointStore.sanitizeText(visible.mission).equals(data.getString("mission"))) {
                            throw new IllegalStateException("Checkpoint identity does not match its mission");
                        }
                        CrewProfile profile = CrewProfile.fromJson(data.getJSONObject("role"));
                        if (profile.workspaceMode != CrewProfile.WorkspaceMode.CONVERSATION_PROJECT) {
                            throw new IllegalStateException("Checkpoint workspace is not resumable");
                        }
                        CrewRole role = profile.resolveRole(profile.capabilities, profile.skillIds)
                                .withMissionAccess(com.jarvys.agent.crew.CrewMissionAccess.parse(
                                        data.has("missionAccess") ? data.getString("missionAccess") : null));
                        List<String> owners = strings(data.getJSONArray("jobOwners"));
                        for (String owner : owners) validateOwner(visible.id, owner);
                        String state = data.getString("status");
                        CrewManager.Status.valueOf(state);
                        CrewBotSnapshot precise = new CrewBotSnapshot(visible.id, role.id, role.name, data.getString("name"), role.colorKey, data.getString("mission"), state, data.optString("error"), data.optString("result"), "", role.tools, data.getLong("startedAtMillis"), data.getLong("finishedAtMillis"));
                        manager.restoreBot(mission, precise, role, saved.loop, saved.artifactOwnership, saved.scopeIdentity, owners, data.getLong("completedCycles"), decodeMessages(data.getJSONArray("messages"), visible.id, false), decodeMessages(data.getJSONArray("pending"), visible.id, true), "");
                        continue;
                    }
                } catch (Exception invalid) {
                    issue = "Checkpoint unavailable: " + (invalid.getMessage() == null ? "invalid saved state" : invalid.getMessage()) + ". Original evidence has been preserved.";
                }
                CrewRole historical = new CrewRole(visible.roleId, visible.roleName, visible.colorKey, "Historical view only.", Collections.emptyList(), null);
                manager.restoreBot(mission, visible, historical, null, new JSONObject(), "", Collections.emptyList(), 0L, mission.messages, Collections.emptyList(), issue);
            }
        }
    }

    private void restoreIssue(CrewManager manager, String note) {
        if (manager.bot("checkpoint-recovery-issue") != null) {
            return;
        }
        CrewBotSnapshot visible = new CrewBotSnapshot("checkpoint-recovery-issue", "checkpoint", "Saved work", "Saved work", CrewRoleTemplates.CODING, "Review unavailable saved work", "FAILED", note, "", "", Collections.emptyList(), 0L, 0L);
        CrewMissionSnapshot mission = new CrewMissionSnapshot("checkpoint-recovery-issue", this.conversation, "recovered", "Saved work", "FAILED", "", 0L, 0L, Collections.singletonList(visible), Collections.emptyList());
        manager.restoreBot(mission, visible, new CrewRole("checkpoint", "Saved work", CrewRoleTemplates.CODING, "History only", Collections.emptyList(), null), null, new JSONObject(), "", Collections.emptyList(), 0L, Collections.emptyList(), Collections.emptyList(), note);
    }

    @Override
    public CrewManager.ResumePlan reconcile(CrewManager.Bot bot) {
        try {
            CrewCheckpointStore.Snapshot saved = this.store.load(this.conversation, bot.id);
            if (saved == null || !saved.scopeIdentity.equals(bot.scopeIdentity())) {
                throw new IllegalStateException("Saved checkpoint is missing or changed");
            }
            CrewRole role = this.currentRole.apply(bot);
            FlavorLinuxTools.validateProfileResume(this.context, role.tools);
            ProjectScope scope = new ProjectScopeStore(this.context.getFilesDir()).open(this.conversation);
            if (!scope.durableIdentity().equals(bot.scopeIdentity())) {
                throw new IllegalStateException("Project root identity changed; the saved scope cannot be resumed");
            }
            CrewContextArtifacts artifacts = new CrewContextArtifacts(this.context.getFilesDir(), this.conversation, bot.id);
            artifacts.restoreOwnership(saved.artifactOwnership);
            StringBuilder artifactRefs = new StringBuilder();
            Iterator<String> artifactIds = saved.artifactOwnership.keys();
            while (artifactIds.hasNext()) {
                artifactRefs.append(artifactIds.next()).append("\n");
            }
            String retainedArtifacts = artifactRefs.length() == 0 ? "" : "Verified retained artifact IDs (including any receipt whose old page cursor expired):\n" + ((Object)artifactRefs) + "Recover complete evidence with read_crew_artifact(artifact_id, offset=0).\n";
            for (String owner : bot.recoveredJobOwners()) {
                validateOwner(bot.id, owner);
            }
            String mutations = scope.mutationRecovery().summary();
            String jobs = FlavorLinuxTools.checkpointRecovery(this.context, this.conversation, scope, bot.recoveredJobOwners());
            return new CrewManager.ResumePlan(role, "Explicit user Resume. Reconciliation is an observation, not authorization. Continue by reviewing retained transcript, file versions, diffs and checks. Do not repeat any pending or uncertain tool call, command, write, patch, or external action. Pending approvals were discarded; every new action uses current policy and availability. No saved permission is reusable. A recovered process may still have unknown liveness; no saved PID was signalled.\nProfile version " + bot.role.profileVersion + " -> " + role.profileVersion + "; capabilities remain within the saved/current intersection.\n" + retainedArtifacts + mutations + "\n" + jobs);
        } catch (Exception failure) {
            throw new IllegalStateException(failure.getMessage() == null ? "Checkpoint reconciliation failed" : failure.getMessage(), failure);
        }
    }

    private void validateOwner(String botId, String owner) {
        String prefix = this.conversation + "/" + botId + "/";
        if (!owner.startsWith(prefix) || !owner.substring(prefix.length()).matches("-?[0-9]+")) {
            throw new IllegalStateException("Checkpoint job belongs to another bot");
        }
        Long.parseLong(owner.substring(prefix.length()));
    }

    private static JSONArray encodeMessages(List<CrewMessage> values, String botId) throws JSONException {
        JSONArray rows = new JSONArray();
        for (CrewMessage value : values) {
            if (botId.equals(value.from) || botId.equals(value.to)) {
                rows.put(new JSONObject().put("id", value.id).put("conversationId", value.conversationId).put("from", value.from).put("to", value.to).put("type", value.type.name()).put("text", value.text).put("refs", new JSONArray((Collection)value.refs)).put("timestampMillis", value.timestampMillis));
            }
        }
        return rows;
    }

    private List<CrewMessage> decodeMessages(JSONArray values, String botId, boolean pending) throws JSONException {
        List<CrewMessage> result = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.getJSONObject(index);
            if (!this.conversation.equals(value.getString("conversationId")) || (!botId.equals(value.getString("to")) && (pending || !botId.equals(value.getString("from"))))) {
                throw new IllegalStateException("Checkpoint mailbox belongs to another bot");
            }
            CrewMessage.Type type = CrewMessage.Type.valueOf(value.getString("type"));
            if (type == CrewMessage.Type.USER && !"user".equals(value.getString("from"))) {
                throw new IllegalStateException("Checkpoint USER provenance is invalid");
            }
            result.add(new CrewMessage(value.getString("id"), this.conversation, value.getString("from"), value.getString("to"), type, value.getString("text"), strings(value.getJSONArray("refs")), value.getLong("timestampMillis")));
        }
        return result;
    }

    private static List<String> strings(JSONArray values) throws JSONException {
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            result.add(values.getString(index));
        }
        return result;
    }
}
