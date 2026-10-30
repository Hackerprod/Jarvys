package com.jarvys.agent;

import android.content.Context;
import com.jarvys.agent.coding.ProjectScope;
import com.jarvys.agent.coding.ProjectScopeStore;
import com.jarvys.agent.crew.CrewBotSnapshot;
import com.jarvys.agent.crew.CrewManager;
import com.jarvys.agent.crew.CrewMessage;
import com.jarvys.agent.crew.CrewMissionSnapshot;
import com.jarvys.agent.crew.CrewMissionTitle;
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
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class CrewCheckpointCoordinator implements CrewManager.CheckpointSupport {
    private static final int CONVERSATION_DELIVERY_VERSION = 1;
    private static final String CONVERSATION_DELIVERY_VERSION_FIELD = "conversationMessageDeliveryVersion";
    private static final String CONVERSATION_DELIVERY_RECEIPTS_FIELD = "processedConversationMessageIds";
    private final Context context;
    private final String conversation;
    private final Function<CrewManager.Bot, CrewRole> currentRole;
    private final CrewCheckpointStore store;

    CrewCheckpointCoordinator(Context context, String conversation, Function<CrewManager.Bot, CrewRole> currentRole) {
        this.context = context.getApplicationContext();
        this.conversation = conversation;
        this.store = new CrewCheckpointStore(this.context.getFilesDir(), directory -> { },
                failure -> AgentErrorReporter.reportChatDatabaseFailure(this.context, failure));
        this.currentRole = currentRole;
    }

    @Override public boolean providesInvocationSource() { return true; }

    @Override
    public boolean canPersist(CrewManager.Bot bot) {
        return bot.role.profileVersion > 0 && bot.role.workspaceMode == CrewProfile.WorkspaceMode.CONVERSATION_PROJECT
                || WorkspaceMemoryTools.eligible(context, conversation, bot);
    }

    @Override
    public void persist(CrewManager.Bot bot, List<CrewMessage> messages, List<CrewMessage> pending) {
        persistConfirmed(bot, messages, pending);
    }

    @Override
    public CrewCheckpointStore.ConfirmedSnapshot persistConfirmed(CrewManager.Bot bot, List<CrewMessage> messages, List<CrewMessage> pending) {
        if (!canPersist(bot)) return null;
        try {
            boolean nativeTask = bot.role.workspaceMode != CrewProfile.WorkspaceMode.CONVERSATION_PROJECT;
            if (nativeTask) {
                String identity = "native-workspace:" + com.jarvys.agent.workspaces.WorkspaceIdentity.workspaceId(conversation);
                if (bot.scopeIdentity().isEmpty()) bot.setScopeIdentity(identity);
                if (!identity.equals(bot.scopeIdentity())) throw new IllegalStateException("Native workspace identity changed");
            } else if (bot.scopeIdentity().isEmpty()) {
                bot.setScopeIdentity(new ProjectScopeStore(this.context.getFilesDir()).open(this.conversation).durableIdentity());
            }
            JSONObject role = nativeTask ? new JSONObject().put("id",bot.role.id).put("version",bot.role.profileVersion)
                    .put("name",bot.role.name).put("description",bot.role.description).put("prompt",bot.role.missionPrompt)
                    .put("skills",new JSONArray(bot.role.skillIds)).put("capabilities",new JSONArray(bot.role.tools))
                    .put("workspaceMode",bot.role.workspaceMode.value)
                    : new CrewProfile(bot.role.id,bot.role.profileVersion,bot.role.name,bot.role.description,
                        bot.role.missionPrompt,bot.role.skillIds,bot.role.tools,bot.role.workspaceMode).toJson();
            JSONObject metadata = new JSONObject().put("conversationId", this.conversation).put("botId", bot.id).put("missionId", bot.missionId).put("mission", bot.mission).put("assignmentId", bot.assignmentId()).put("missionAccess", bot.role.missionAccess.value).put("name", bot.name).put("status", bot.status().name()).put("result", bot.result()).put("error", bot.error()).put("startedAtMillis", bot.startedAtMillis()).put("finishedAtMillis", bot.finishedAtMillis()).put("completedCycles", bot.completedCycles()).put("jobOwners", new JSONArray((Collection)bot.recoveredJobOwners())).put("role", role).put("nativeWorkspaceCheckpoint", nativeTask).put("messages", encodeMessages(messages, bot.id)).put("pending", encodeMessages(pending, bot.id))
                    .put(CONVERSATION_DELIVERY_VERSION_FIELD, CONVERSATION_DELIVERY_VERSION)
                    .put(CONVERSATION_DELIVERY_RECEIPTS_FIELD, new JSONArray(bot.processedConversationMessageIds()));
            if (!bot.cycleId().isEmpty()) metadata.put("cycleId", bot.cycleId());
            CrewMissionTitle title = bot.missionTitle();
            metadata.put("missionPresentation", new JSONObject().put("schemaVersion", 1)
                    .put("title", title.title).put("titleSource", title.source)
                    .put("originalInstructions", bot.originalInstructions()));
            return this.store.saveConfirmed(this.conversation, bot.id, metadata, bot.scopeIdentity(), bot.checkpoint(), bot.artifactOwnership());
        } catch (CancellationException cancelled) {
            throw cancelled;
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
                if (Boolean.TRUE.equals(data.opt("nativeWorkspaceCheckpoint"))) {
                    JSONObject role = data.getJSONObject("role");
                    CrewBotSnapshot nativeBot = new CrewBotSnapshot(saved.botId,role.getString("id"),role.getString("name"),
                        data.getString("name"),role.getString("id"),data.getString("mission"),data.getString("status"),
                        data.optString("error"),data.optString("result"),"",strings(role.getJSONArray("capabilities")),
                        data.getLong("startedAtMillis"),data.getLong("finishedAtMillis"),false,"",false,"","",0L,historicalCycleId(data),null);
                    missions.add(recoveredMission(data,nativeBot,saved.savedAtMs));
                    continue;
                }
                CrewProfile profile = CrewProfile.fromJson(data.getJSONObject("role"));
                CrewBotSnapshot bot = new CrewBotSnapshot(saved.botId, profile.id, profile.name, data.getString("name"), profile.id, data.getString("mission"), data.getString("status"), data.optString("error"), data.optString("result"), "", profile.capabilities, data.getLong("startedAtMillis"), data.getLong("finishedAtMillis"), false, "", false, "", "", 0L, historicalCycleId(data), null);
                missions.add(recoveredMission(data, bot, saved.savedAtMs));
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
                    CrewCheckpointStore.Snapshot discovered = this.store.load(this.conversation, visible.id);
                    CrewCheckpointStore.ConfirmedSnapshot saved = discovered == null ? null :
                            this.store.confirmDurable(this.conversation, visible.id, discovered.scopeIdentity);
                    if (saved != null) {
                        JSONObject data = saved.metadataJson();
                        if (!this.conversation.equals(data.getString("conversationId")) || !visible.id.equals(data.getString("botId")) || !mission.missionId.equals(data.getString("missionId")) || !CrewCheckpointStore.sanitizeText(visible.mission).equals(data.getString("mission"))) {
                            throw new IllegalStateException("Checkpoint identity does not match its mission");
                        }
                        if (Boolean.TRUE.equals(data.opt("nativeWorkspaceCheckpoint")))
                            throw new IllegalStateException("Native workspace checkpoint is historical; continue through a new bound assignment");
                        CrewProfile profile = CrewProfile.fromJson(data.getJSONObject("role"));
                        if (profile.workspaceMode != CrewProfile.WorkspaceMode.CONVERSATION_PROJECT) {
                            throw new IllegalStateException("Checkpoint workspace is not resumable");
                        }
                        CrewRole role = profile.resolveRole(profile.capabilities, profile.skillIds)
                                .withMissionAccess(com.jarvys.agent.crew.CrewMissionAccess.parse(
                                        data.has("missionAccess") ? data.getString("missionAccess") : null));
                        WorkspaceConversationInputStore.acknowledgeApplied(this.context, this.conversation,
                                saved.loop.appliedIncomingIds);
                        List<String> owners = strings(data.getJSONArray("jobOwners"));
                        ProjectScope recoveredScope = new ProjectScopeStore(this.context.getFilesDir()).openExisting(this.conversation);
                        if (!recoveredScope.durableIdentity().equals(saved.scopeIdentity))
                            throw new IllegalStateException("Saved project identity changed");
                        for (String owner : FlavorLinuxTools.correlatedCheckpointOwners(this.context, recoveredScope, saved))
                            if (!owners.contains(owner)) owners.add(owner);
                        for (String owner : owners) validateOwner(visible.id, owner);
                        String state = data.getString("status");
                        CrewManager.Status.valueOf(state);
                        CrewBotSnapshot precise = new CrewBotSnapshot(visible.id, role.id, role.name, data.getString("name"), role.colorKey, data.getString("mission"), state, data.optString("error"), data.optString("result"), "", role.tools, data.getLong("startedAtMillis"), data.getLong("finishedAtMillis"), false, "", false, "", visible.lastProgress, visible.lastProgressAtMillis, historicalCycleId(data), null);
                        CrewMissionSnapshot presentation = withCheckpointPresentation(mission, recoveredMission(data, precise, saved.savedAtMs));
                        List<CrewMessage> restoredMessages = decodeMessages(data.getJSONArray("messages"), visible.id, mission.missionId, false);
                        List<String> processedConversationMessageIds = conversationDeliveryReceipts(data, restoredMessages);
                        boolean deliveryStateVersioned = data.has(CONVERSATION_DELIVERY_VERSION_FIELD);
                        String assignmentId = resolveAssignmentId(mission.missionId, visible.id,
                                data.optString("assignmentId", ""));
                        manager.restoreBot(presentation, precise, role, saved.loopForEvidence(), saved.artifactOwnershipJson(),
                                saved.scopeIdentity, owners, data.getLong("completedCycles"),
                                restoredMessages,
                                decodeMessages(data.getJSONArray("pending"), visible.id, mission.missionId, true),
                                "", assignmentId, processedConversationMessageIds, deliveryStateVersioned);
                        continue;
                    }
                } catch (Exception invalid) {
                    issue = "Checkpoint unavailable: " + (invalid.getMessage() == null ? "invalid saved state" : invalid.getMessage()) + ". Original evidence has been preserved.";
                }
                CrewRole historical = new CrewRole(visible.roleId, visible.roleName, visible.colorKey, "Historical view only.", Collections.emptyList(), null);
                String assignmentId = resolveAssignmentId(mission.missionId, visible.id, "");
                manager.restoreBot(mission, visible, historical, null, new JSONObject(), "",
                        Collections.emptyList(), 0L, mission.messages, Collections.emptyList(), issue,
                        assignmentId);
            }
        }
    }

    private String resolveAssignmentId(String missionId, String botId, String persisted) {
        if (persisted != null && !persisted.isEmpty()) return persisted;
        if (!com.jarvys.agent.workspaces.WorkspaceIdentity.isExecutionSession(conversation)) return "";
        com.jarvys.agent.workspaces.WorkspaceRegistry registry = new com.jarvys.agent.workspaces.WorkspaceRegistry(context);
        try {
            com.jarvys.agent.workspaces.WorkspaceAssignment assignment = registry.findBoundAssignment(
                    com.jarvys.agent.workspaces.WorkspaceIdentity.workspaceId(conversation), missionId, botId);
            return assignment == null ? "" : assignment.getAssignmentId();
        } finally { registry.close(); }
    }

    /** Missing delivery metadata means every source message in this checkpoint predates the outbox protocol. */
    private List<String> conversationDeliveryReceipts(JSONObject data, List<CrewMessage> messages) throws JSONException {
        if (!data.has(CONVERSATION_DELIVERY_VERSION_FIELD)) return Collections.emptyList();
        Object rawVersion = data.opt(CONVERSATION_DELIVERY_VERSION_FIELD);
        if (!(rawVersion instanceof Number)
                || ((Number) rawVersion).doubleValue() != CONVERSATION_DELIVERY_VERSION)
            throw new IllegalStateException("Unsupported worker conversation delivery checkpoint");
        JSONArray rows = data.optJSONArray(CONVERSATION_DELIVERY_RECEIPTS_FIELD);
        if (rows == null) throw new IllegalStateException("Worker conversation delivery receipts are missing");
        Set<String> validSourceIds = new HashSet<>();
        for (CrewMessage message : messages)
            if (message.activity == null && "chief".equals(message.to)) validSourceIds.add(message.id);
        Set<String> unique = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (int index = 0; index < rows.length(); index++) {
            Object value = rows.get(index);
            if (!(value instanceof String) || ((String) value).isEmpty()
                    || !unique.add((String) value) || !validSourceIds.contains((String) value))
                throw new IllegalStateException("Invalid worker conversation delivery receipt");
            result.add((String) value);
        }
        return result;
    }

    private CrewMissionSnapshot recoveredMission(JSONObject data, CrewBotSnapshot bot, long savedAtMillis) throws JSONException {
        JSONObject presentation = data.optJSONObject("missionPresentation");
        CrewMissionTitle title = CrewMissionTitle.fallback();
        String instructions = "";
        if (presentation != null && presentation.optInt("schemaVersion", 0) == 1) {
            title = CrewMissionTitle.stored(presentation.opt("title"), presentation.optString("titleSource"));
            Object original = presentation.opt("originalInstructions");
            if (original instanceof String) instructions = (String) original;
        }
        // Older checkpoints contain only bot instructions; never relabel them as the user's request.
        return new CrewMissionSnapshot(data.getString("missionId"), this.conversation, "recovered", title.title,
                instructions, title.source, "INTERRUPTED", "", data.getLong("startedAtMillis"), savedAtMillis,
                Collections.singletonList(bot), Collections.emptyList());
    }

    private static CrewMissionSnapshot withCheckpointPresentation(CrewMissionSnapshot ledger, CrewMissionSnapshot checkpoint) {
        // Checkpoints commit before the ledger. Recover a first accepted title that reached its
        // checkpoint but not its ledger row, without renaming an already-titled ledger mission.
        boolean useCheckpointTitle = CrewMissionTitle.FALLBACK.equals(ledger.titleSource);
        return new CrewMissionSnapshot(ledger.missionId, ledger.conversationId, ledger.processId,
                useCheckpointTitle ? checkpoint.title : ledger.title,
                ledger.originalInstructions.isEmpty() ? checkpoint.originalInstructions : ledger.originalInstructions,
                useCheckpointTitle ? checkpoint.titleSource : ledger.titleSource, ledger.status, ledger.synthesis,
                ledger.startedAtMillis, ledger.finishedAtMillis, ledger.bots, ledger.messages);
    }

    private void restoreIssue(CrewManager manager, String note) {
        if (manager.bot("checkpoint-recovery-issue") != null) {
            return;
        }
        CrewBotSnapshot visible = new CrewBotSnapshot("checkpoint-recovery-issue", "checkpoint", "Saved work", "Saved work", CrewRoleTemplates.CODING, "Review unavailable saved work", "FAILED", note, "", "", Collections.emptyList(), 0L, 0L);
        CrewMissionSnapshot mission = new CrewMissionSnapshot("checkpoint-recovery-issue", this.conversation, "recovered",
                "", "", CrewMissionTitle.FALLBACK, "FAILED", "", 0L, 0L, Collections.singletonList(visible), Collections.emptyList());
        manager.restoreBot(mission, visible, new CrewRole("checkpoint", "Saved work", CrewRoleTemplates.CODING, "History only", Collections.emptyList(), null), null, new JSONObject(), "", Collections.emptyList(), 0L, Collections.emptyList(), Collections.emptyList(), note);
    }

    @Override
    public void beforeReconcile(CrewManager.Bot bot) {
        try {
            ProjectScope scope = new ProjectScopeStore(this.context.getFilesDir()).openExisting(this.conversation);
            if (!scope.durableIdentity().equals(bot.scopeIdentity()))
                throw new IllegalStateException("Saved project identity changed");
            FlavorLinuxTools.refreshCheckpointRecovery(this.context, scope);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Saved project recovery is unavailable", failure);
        }
    }

    @Override
    public CrewManager.ResumePlan reconcile(CrewManager.Bot bot) {
        try {
            CrewCheckpointStore.ConfirmedSnapshot saved = this.store.confirmDurable(this.conversation, bot.id, bot.scopeIdentity());
            if (saved == null || !saved.scopeIdentity.equals(bot.scopeIdentity())) {
                throw new IllegalStateException("Saved checkpoint is missing or changed");
            }
            CrewRole role = this.currentRole.apply(bot);
            FlavorLinuxTools.validateProfileResume(this.context, role.tools);
            ProjectScope scope = new ProjectScopeStore(this.context.getFilesDir()).openExisting(this.conversation);
            if (!scope.durableIdentity().equals(bot.scopeIdentity())) {
                throw new IllegalStateException("Project root identity changed; the saved scope cannot be resumed");
            }
            CrewContextArtifacts artifacts = new CrewContextArtifacts(this.context.getFilesDir(), this.conversation, bot.id);
            artifacts.restoreOwnership(saved.artifactOwnershipJson());
            StringBuilder artifactRefs = new StringBuilder();
            Iterator<String> artifactIds = saved.artifactOwnershipJson().keys();
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

    /** Historical provenance only. A legacy checkpoint cannot infer a later snapshot's cycle. */
    private static String historicalCycleId(JSONObject data) {
        if (!data.has("cycleId")) return "";
        Object value = data.opt("cycleId");
        if (!(value instanceof String) || ((String) value).isEmpty())
            throw new IllegalArgumentException("Invalid checkpoint cycle identity");
        return (String) value; // CrewBotSnapshot validates the canonical UUID.
    }

    private static JSONArray encodeMessages(List<CrewMessage> values, String botId) throws JSONException {
        JSONArray rows = new JSONArray();
        for (CrewMessage value : values) {
            if (botId.equals(value.from) || botId.equals(value.to)) {
                rows.put(value.toJson());
            }
        }
        return rows;
    }

    private List<CrewMessage> decodeMessages(JSONArray values, String botId, String missionId, boolean pending) throws JSONException {
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
            CrewMessage message = CrewMessage.fromJson(value);
            if (message.coordination != null && (!botId.equals(message.coordination.botId)
                    || !missionId.equals(message.coordination.missionId)))
                throw new IllegalStateException("Checkpoint causal source belongs to another mission");
            if (message.inputBinding != null && (!botId.equals(message.inputBinding.botId)
                    || !missionId.equals(message.inputBinding.missionId)
                    || !conversation.equals(message.inputBinding.executionSessionId)))
                throw new IllegalStateException("Checkpoint input source belongs to another mission");
            if (pending && message.activity != null) throw new IllegalStateException("Local activity cannot be mailbox work");
            result.add(message);
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
