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
        return persistCheckpoint(bot, messages, pending, null, null);
    }

    @Override public CrewCheckpointStore.ConfirmedSnapshot persistSuccessorConfirmed(CrewManager.Bot bot,
            List<CrewMessage> messages, List<CrewMessage> pending,
            CrewCheckpointStore.ConfirmedSnapshot expected, CentralContinuationRecord successor) {
        if (expected == null || successor == null) throw new IllegalArgumentException("Exact successor source required");
        CrewCheckpointStore.ConfirmedSnapshot committed = persistCheckpoint(bot, messages, pending, expected, successor);
        if (committed == null) throw new IllegalStateException("Native successor checkpoint lost its current owner");
        return committed;
    }

    private CrewCheckpointStore.ConfirmedSnapshot persistCheckpoint(CrewManager.Bot bot,
            List<CrewMessage> messages, List<CrewMessage> pending,
            CrewCheckpointStore.ConfirmedSnapshot expected, CentralContinuationRecord successor) {
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
            if (successor != null)
                return this.store.saveCentralContinuationSuccessor(expected, successor, metadata,
                        bot.scopeIdentity(), bot.checkpoint(), bot.artifactOwnership());
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
                        boolean nativeTask = Boolean.TRUE.equals(data.opt("nativeWorkspaceCheckpoint"));
                        CrewRole role;
                        List<String> owners = strings(data.getJSONArray("jobOwners"));
                        if (nativeTask) {
                            requireNativeIdentity(context, conversation, saved);
                            requireNativeSafePoint(saved);
                            role = nativeRole(data);
                            manager.validateCheckpointRole(role);
                            if (!owners.isEmpty()) throw new IllegalStateException("Native checkpoint has uninspectable job ownership");
                        } else {
                            CrewProfile profile = CrewProfile.fromJson(data.getJSONObject("role"));
                            if (profile.workspaceMode != CrewProfile.WorkspaceMode.CONVERSATION_PROJECT)
                                throw new IllegalStateException("Checkpoint workspace is not resumable");
                            role = profile.resolveRole(profile.capabilities, profile.skillIds)
                                    .withMissionAccess(com.jarvys.agent.crew.CrewMissionAccess.parse(
                                            data.has("missionAccess") ? data.getString("missionAccess") : null));
                            ProjectScope recoveredScope = new ProjectScopeStore(this.context.getFilesDir()).openExisting(this.conversation);
                            if (!recoveredScope.durableIdentity().equals(saved.scopeIdentity))
                                throw new IllegalStateException("Saved project identity changed");
                            for (String owner : FlavorLinuxTools.correlatedCheckpointOwners(this.context, recoveredScope, saved))
                                if (!owners.contains(owner)) owners.add(owner);
                        }
                        WorkspaceConversationInputStore.acknowledgeApplied(this.context, this.conversation,
                                saved.loop.appliedIncomingIds);
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
            if (bot.scopeIdentity().startsWith("native-workspace:")) {
                CrewCheckpointStore.ConfirmedSnapshot saved = store.confirmDurable(conversation, bot.id, bot.scopeIdentity());
                if (saved == null) throw new IllegalStateException("Native checkpoint is missing");
                requireNativeIdentity(context, conversation, saved);
                requireNativeSafePoint(saved);
                return;
            }
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
            if (saved.scopeIdentity.startsWith("native-workspace:")) {
                requireNativeIdentity(context, conversation, saved);
                requireNativeSafePoint(saved);
                if (role.workspaceMode != CrewProfile.WorkspaceMode.LEGACY_CHAT
                        || role.missionAccess != bot.role.missionAccess
                        || !role.tools.containsAll(bot.role.tools))
                    throw new IllegalStateException("Native checkpoint role changed");
                if (!bot.recoveredJobOwners().isEmpty()) throw new IllegalStateException("Native checkpoint has owned jobs");
                new CrewContextArtifacts(context.getFilesDir(), conversation, bot.id).restoreOwnership(saved.artifactOwnershipJson());
                return new CrewManager.ResumePlan(role, "Explicit native Resume of a verified safe general-purpose checkpoint. "
                        + "Original question and historical evidence are retained, not new grants. "
                        + "Do not repeat prior effects; use only currently admitted scoped tools and the exact linked answer.");
            }
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

    @Override public void cancelContinuation(CrewManager.Bot bot) {
        if (!bot.scopeIdentity().startsWith("native-workspace:")) return;
        CrewCheckpointStore.ConfirmedSnapshot saved = store.confirmDurable(conversation, bot.id, bot.scopeIdentity());
        if (saved == null || !saved.metadataJson().has(CentralContinuationRecord.FIELD)) return;
        CentralContinuationRecord current = CrewCheckpointStore.continuation(saved);
        current.requireCheckpoint(conversation, bot.id, saved.scopeIdentity, saved.metadataJson());
        if ("STARTED".equals(current.dispatchState()) || "ACKNOWLEDGED".equals(current.dispatchState())) {
            WorkspaceContinuationDelivery.cancelClaimedOwner(saved);
            return;
        }
        if (!"NONE".equals(current.dispatchState()) && !"PREPARED".equals(current.dispatchState())) return;
        WorkspaceContinuationDelivery.fenceCancelledEpisode(saved);
        try {
            JSONObject next = current.toJson().put("dispatchState", "CANCELLED").put("revision", current.revision() + 1);
            store.updateCentralContinuation(saved, CentralContinuationRecord.fromJson(next));
        } catch (JSONException invalid) { throw new IllegalStateException("Cannot cancel native continuation", invalid); }
    }

    @Override public boolean requiresLinkedResume(CrewManager.Bot bot) {
        if (!bot.scopeIdentity().startsWith("native-workspace:")) return false;
        CrewCheckpointStore.ConfirmedSnapshot saved = store.confirmDurable(conversation, bot.id, bot.scopeIdentity());
        if (saved == null) throw new IllegalStateException("Native checkpoint is missing");
        JSONObject value = saved.metadataJson().optJSONObject(CentralContinuationRecord.FIELD);
        if (value == null) return false;
        CentralContinuationRecord.fromJson(value).requireCheckpoint(conversation, bot.id, saved.scopeIdentity, saved.metadataJson());
        return true;
    }

    static void requireNativeIdentity(Context context, String conversation, CrewCheckpointStore.ConfirmedSnapshot saved) {
        try {
            JSONObject data = saved.metadataJson();
            CentralContinuationRecord record = CentralContinuationRecord.fromJson(data.optJSONObject(CentralContinuationRecord.FIELD));
            record.requireCheckpoint(conversation, saved.botId, saved.scopeIdentity, data);
            JSONObject continuation = record.toJson();
            com.jarvys.agent.workspaces.WorkspaceRegistry registry = new com.jarvys.agent.workspaces.WorkspaceRegistry(context);
            try {
                String workspaceId = com.jarvys.agent.workspaces.WorkspaceIdentity.workspaceId(conversation);
                com.jarvys.agent.workspaces.WorkspaceRecord workspace = registry.getWorkspace(workspaceId);
                com.jarvys.agent.workspaces.WorkspaceAssignment assignment = registry.getAssignment(workspaceId, data.getString("assignmentId"));
                if (workspace == null || assignment == null
                        || !workspace.getExecutionSessionId().equals(conversation)
                        || !workspace.getOwnerCentralSessionId().equals(com.jarvys.agent.workspaces.WorkspaceIdentity.CENTRAL_SESSION)
                        || assignment.getState() != com.jarvys.agent.workspaces.WorkspaceAssignmentState.BOUND
                        || !saved.botId.equals(assignment.getBotId())
                        || !data.getString("missionId").equals(assignment.getMissionId())
                        || !continuation.getString("requestId").equals(assignment.getRequestId())
                        || !data.getJSONObject("role").getString("id").equals(assignment.getRole())
                        || !assignment.getMissionAccess().value.equals(data.getString("missionAccess")))
                    throw new IllegalStateException("Native checkpoint assignment ownership changed");
            } finally { registry.close(); }
        } catch (JSONException invalid) { throw new IllegalStateException("Invalid native checkpoint identity", invalid); }
    }

    private static CrewRole nativeRole(JSONObject data) throws JSONException {
        JSONObject role = data.getJSONObject("role");
        if (!CrewProfile.WorkspaceMode.LEGACY_CHAT.value.equals(role.getString("workspaceMode"))
                || role.getInt("version") < 1)
            throw new IllegalStateException("Native checkpoint profile is not versioned/current");
        return new CrewRole(role.getString("id"), role.getString("name"), role.getString("id"),
                role.getString("prompt"), strings(role.getJSONArray("capabilities")), null,
                role.getString("description"), role.getInt("version"), strings(role.getJSONArray("skills")),
                CrewProfile.WorkspaceMode.LEGACY_CHAT,
                com.jarvys.agent.crew.CrewMissionAccess.parse(data.getString("missionAccess")));
    }

    /** Conservative first profile: exact completed local coordination/reads, never external effects. */
    void requireNativeSafePoint(CrewCheckpointStore.ConfirmedSnapshot saved) {
        CoreAgentLoop.Checkpoint raw = saved.loopForEvidence();
        raw.reconciled(); // Structural validation only; it does not turn uncertainty into success.
        try {
            if (saved.metadataJson().getJSONArray("jobOwners").length() != 0)
                throw new IllegalStateException("Native checkpoint has uninspectable job ownership");
        } catch (JSONException invalid) { throw new IllegalStateException("Native job ownership is unavailable", invalid); }
        Set<String> retainedCalls = new HashSet<>();
        Set<String> usedQuestionReceipts = new HashSet<>();
        for (ConversationTurn turn : raw.transcript) for (ModelReply.Call call : turn.toolCalls) retainedCalls.add(call.id);
        if (!retainedCalls.containsAll(raw.toolLifecycle.keySet()))
            throw new IllegalStateException("Native tool identity was compacted; effect safety cannot be established");
        for (String lifecycle : raw.toolLifecycle.values())
            if (!"RESULT".equals(lifecycle) && !"NEVER_LAUNCHED".equals(lifecycle))
                throw new IllegalStateException("Native checkpoint contains an unresolved tool effect");
        for (ConversationTurn turn : raw.transcript) for (ModelReply.Call call : turn.toolCalls) {
            String state = raw.toolLifecycle.get(call.id);
            if ("NEVER_LAUNCHED".equals(state)) continue;
            if (!"RESULT".equals(state)) throw new IllegalStateException("Native checkpoint tool has no terminal receipt");
            if ("board_read".equals(call.name) || "memory_search".equals(call.name) || "memory_get".equals(call.name)) continue;
            if ("ask_chief".equals(call.name)) {
                String text = String.valueOf(call.arguments.get("question"));
                String evidenceId = null;
                for (ConversationTurn result : raw.transcript) {
                    if (result.kind != ConversationTurn.Kind.TOOL_RESULT || !call.id.equals(result.toolCallId)) continue;
                    String prefix = "native-question-evidence:";
                    if (evidenceId != null || !result.content.startsWith(prefix))
                        throw new IllegalStateException("Native question has no unique exact tool receipt");
                    int newline = result.content.indexOf('\n');
                    if (newline < 0) throw new IllegalStateException("Native question receipt is incomplete");
                    evidenceId = result.content.substring(prefix.length(), newline);
                }
                boolean verified = false;
                try {
                    JSONObject metadata = saved.metadataJson();
                    JSONObject continuation = CrewCheckpointStore.continuation(saved).toJson();
                    Set<String> cycles = new HashSet<>();
                    cycles.add(continuation.getString("initialCycleId"));
                    JSONArray links = continuation.getJSONArray("links");
                    for (int i = 0; i < links.length(); i++)
                        if ("BOUND".equals(links.getJSONObject(i).getString("state")))
                            cycles.add(links.getJSONObject(i).getString("newCycleId"));
                    for (CrewMessage message : decodeMessages(metadata.getJSONArray("messages"), saved.botId,
                            metadata.getString("missionId"), false)) {
                        if (!saved.botId.equals(message.from) || !"chief".equals(message.to)
                                || !text.equals(message.text) || message.type != CrewMessage.Type.QUESTION
                                || message.coordination == null || !message.coordination.evidenceId.equals(evidenceId)) continue;
                        com.jarvys.agent.crew.CrewCoordinationEvidence source = new LocalRunStore(context)
                                .readCrewCoordinationEvidence(conversation, evidenceId);
                        if (source != null && message.coordination.matches(source)
                                && source.conversationId.equals(conversation)
                                && source.cycleId.equals(message.coordination.cycleId)
                                && source.messageId.equals(message.id)
                                && source.messageId.equals(message.coordination.messageId)
                                && source.text.equals(text) && source.botId.equals(saved.botId)
                                && source.missionId.equals(metadata.getString("missionId")) && cycles.contains(source.cycleId)) verified = true;
                    }
                } catch (JSONException invalid) { throw new IllegalStateException("Native coordination proof is invalid", invalid); }
                if (verified && usedQuestionReceipts.add(evidenceId)) continue;
            }
            throw new IllegalStateException("Native checkpoint effect is not inspectable by the bounded recovery profile: " + call.name);
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
