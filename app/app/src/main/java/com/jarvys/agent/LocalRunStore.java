package com.jarvys.agent;

import android.content.Context;
import android.os.Build;
import android.util.Base64;
import java.io.IOException;
import java.util.Collections;

import com.jarvys.agent.device.ScreenData;
import com.jarvys.agent.crew.CrewMissionSnapshot;
import com.jarvys.agent.crew.CrewResultEvidence;
import com.jarvys.agent.crew.CrewCoordinationEvidence;
import com.jarvys.agent.workspaces.WorkspaceIdentity;
import com.jarvys.agent.workspaces.WorkspaceResultReference;
import com.jarvys.agent.workspaces.WorkspaceCoordinationReference;
import com.jarvys.agent.workspaces.WorkspaceCoordinationReply;
import com.jarvys.agent.workspaces.WorkspaceInputIncorporation;
import com.jarvys.agent.crew.CrewProcessIdentity;
import com.jarvys.agent.proactive.ProactiveReplyClaim;
import com.jarvys.agent.proactive.ProactiveSuggestedReply;
import com.jarvys.agent.UserDecisionOption;
import com.jarvys.agent.UserDecisionRole;
import com.jarvys.agent.chatdb.AppendMessageInput;
import com.jarvys.agent.chatdb.AssistantMarkerValue;
import com.jarvys.agent.chatdb.ChatDatabaseBridge;
import com.jarvys.agent.chatdb.ChatTimelineEventPage;
import com.jarvys.agent.chatdb.ChatDbResult;
import com.jarvys.agent.chatdb.ConversationMetadataValue;
import com.jarvys.agent.chatdb.ConversationImageReferenceValue;
import com.jarvys.agent.chatdb.ConversationSummary;
import com.jarvys.agent.chatdb.DispatchedInterrupt;
import com.jarvys.agent.chatdb.InterruptRequestValue;
import com.jarvys.agent.chatdb.MessageAttachmentInput;
import com.jarvys.agent.chatdb.MessagePartInput;
import com.jarvys.agent.chatdb.ProactiveReferent;
import com.jarvys.agent.chatdb.ReactionUpdate;
import com.jarvys.agent.chatdb.RegenerationPromptValue;
import com.jarvys.agent.chatdb.StoredMessage;
import com.jarvys.agent.chatdb.StoppedSubmissionResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** App-private append-only run records and notes for graph/history tools. */
public final class LocalRunStore {
    /** Sentinel used by keyset timeline pages when the caller wants the newest window. */
    public static final long NO_TIMELINE_CURSOR = -1L;
    private static final Object SESSION_TITLE_LOCK = new Object();
    private static final Set<String> SESSION_TITLES_IN_PROGRESS = new HashSet<>();
    private final Context appContext;
    private volatile StoragePressure storagePressure;
    private final File root;
    private final File filesDirectory;
    private final String runBoundary;
    /** Null only for the package-private file fixture; production instances always use Room. */
    private final ChatDatabaseBridge chatDatabaseBridge;
    private final Map<String, String> runSessions = new LinkedHashMap<>();
    private final File notes;
    private final Map<String, File> runDirectories = new LinkedHashMap<>();
    private String activeRunId;
    private File activeRunDirectory;

    public static final class ReflectionPayload {
        public final String text;
        public final String endMessageId;
        public final List<String> compactionIds;
        public final int completedAssistantSteps;
        public final int userCharacters;
        public final int messageCount;
        public final boolean hasMoreMessages;
        ReflectionPayload(String text, String endMessageId, List<String> compactionIds,
                          int completedAssistantSteps, int userCharacters, int messageCount,
                          boolean hasMoreMessages) {
            this.text = text;
            this.endMessageId = endMessageId;
            this.compactionIds = java.util.Collections.unmodifiableList(new ArrayList<>(compactionIds));
            this.completedAssistantSteps = completedAssistantSteps;
            this.userCharacters = userCharacters;
            this.messageCount = messageCount;
            this.hasMoreMessages = hasMoreMessages;
        }
    }

    public LocalRunStore(Context context) {
        this.appContext = context.getApplicationContext();
        this.storagePressure = StoragePressure.system(appContext);
        File filesDirectory = context.getFilesDir();
        this.filesDirectory = filesDirectory;
        File jarvys = new StorageGeneration(filesDirectory).chatRoot();
        root = new File(jarvys, "runs");
        notes = new File(jarvys, "notes");
        chatDatabaseBridge = ChatDatabaseBridge.getInstance(context);
        try {
            runBoundary = new StorageGeneration(filesDirectory.getCanonicalFile()).directory("runs").getPath();
            verifyRunPath(root);
        } catch (IOException error) { throw new IllegalStateException("Unsafe local run directory", error); }
        ensureDirectory(root);
        ensureDirectory(notes);
    }

    /** Non-production file fixture for run-store tests; conversation operations require a registered DB bridge. */
    LocalRunStore(File filesDirectory) {
        this.appContext = null;
        this.storagePressure = StoragePressure.NONE;
        this.filesDirectory = filesDirectory;
        File jarvys = new StorageGeneration(filesDirectory).chatRoot();
        root = new File(jarvys, "runs");
        notes = new File(jarvys, "notes");
        chatDatabaseBridge = ChatDatabaseBridge.findInstanceForFilesDirectory(filesDirectory);
        try {
            runBoundary = new StorageGeneration(filesDirectory.getCanonicalFile()).directory("runs").getPath();
            verifyRunPath(root);
        } catch (IOException error) { throw new IllegalStateException("Unsafe local run directory", error); }
        ensureDirectory(root);
        ensureDirectory(notes);
    }

    private boolean isConversationAvailable(String sessionId) {
        if (sessionId == null || sessionId.isEmpty() || chatDatabaseBridge == null) return false;
        ConversationMetadataValue metadata = chatDatabaseBridge.readConversationMetadataSync(sessionId);
        return metadata != null && !metadata.getDeleted() && !chatDatabaseBridge.isTombstonedSync(sessionId);
    }

    private boolean isConversationDeleted(String sessionId) {
        if (sessionId == null || sessionId.isEmpty() || chatDatabaseBridge == null) return false;
        ConversationMetadataValue metadata = chatDatabaseBridge.readConversationMetadataSync(sessionId);
        return metadata != null && metadata.getDeleted() || chatDatabaseBridge.isTombstonedSync(sessionId);
    }

    private static IllegalStateException noChatDatabase() {
        return new IllegalStateException(
                "Conversation storage requires a registered ChatDatabaseBridge; file-only LocalRunStore is not production-ready");
    }

    private boolean deleteConversationOwnedFiles(String sessionId) {
        synchronized (SESSION_TITLE_LOCK) { SESSION_TITLES_IN_PROGRESS.remove(sessionId); }
        boolean complete = true;
        try {
            verifyRunPath(root);
            File[] runs = root.listFiles();
            if (runs == null) complete = false;
            else for (File run : runs) {
                try { if (sessionId.equals(sessionForRunDirectory(run))) complete &= deleteRunTree(run); }
                catch (RuntimeException failure) { complete = false; }
            }
        } catch (IOException | RuntimeException failure) { complete = false; }
        try { complete &= new GeneratedImageStore(root.getParentFile()).deleteSession(sessionId); }
        catch (RuntimeException failure) { complete = false; }
        try { complete &= new DeliveredArtifactStore(filesDirectory).deleteSession(sessionId); }
        catch (RuntimeException failure) { complete = false; }
        try { complete &= new AttachmentStore(root.getParentFile()).deleteSession(sessionId); }
        catch (RuntimeException failure) { complete = false; }
        try {
            complete &= new WorkspaceStore(new File(root.getParentFile(), "workspaces"),
                    WorkspaceStore.projectIdForSession(sessionId)).deleteImportedAttachments();
        } catch (RuntimeException failure) { complete = false; }
        try { complete &= CrewContextArtifacts.deleteConversation(filesDirectory, sessionId); }
        catch (RuntimeException failure) { complete = false; }
        return complete;
    }

    private static String conversationKindFor(String sessionId) {
        if (WorkspaceIdentity.CENTRAL_SESSION.equals(sessionId)) return "central";
        if (WorkspaceIdentity.isExecutionSession(sessionId)) return "workspace_exec";
        return "chat";
    }

    private static <T> T requireChatDbSuccess(ChatDbResult<T> result, String operation) {
        if (result instanceof ChatDbResult.Success) {
            @SuppressWarnings("unchecked")
            ChatDbResult.Success<T> success = (ChatDbResult.Success<T>) result;
            return success.getValue();
        }
        throw new IllegalStateException("Could not " + operation + ": " + result);
    }

    private static List<String> copyIds(Iterable<String> values) {
        List<String> result = new ArrayList<>();
        if (values != null) for (String value : values)
            if (value != null && !value.isEmpty() && !result.contains(value)) result.add(value);
        return result;
    }

    private static JSONObject interruptRequestJson(InterruptRequestValue request) {
        try {
            JSONObject row = new JSONObject().put("type", "main_interrupt_request")
                    .put("sessionId", request.getConversationId()).put("requestId", request.getRequestId())
                    .put("content", request.getContent()).put("skills", new JSONArray(request.getSkills()))
                    .put("alreadyDispatched", request.getAlreadyDispatched());
            if (request.getDispatchedMessageId() != null) row.put("messageId", request.getDispatchedMessageId());
            return row;
        } catch (Exception error) {
            throw new IllegalStateException("Could not project chat replacement request", error);
        }
    }

    private static JSONObject messageJson(StoredMessage message) {
        JSONObject row = new JSONObject();
        try {
            row.put("role", message.getRole());
            row.put("content", message.getText());
            row.put("messageId", message.getMessageId());
            row.put("stableId", message.getStableId());
            row.put("timestamp", message.getCreatedAtSeconds());
            if (message.getProactiveDecisionId() != null) {
                row.put("type", "proactive_message");
                row.put("proactiveDecisionId", message.getProactiveDecisionId());
            }
            for (MessagePartInput part : message.getParts()) if (com.jarvys.agent.workspaces.MissionRef.PART_KIND.equals(part.getKind())) {
                row.put("type", "mission_ref");
                break;
            }
            if (message.getInputState() != null) row.put("inputState", message.getInputState());
            if ("agent".equals(message.getRole()) || "system".equals(message.getRole())) {
                row.put("inputOrigin", message.getRole().toUpperCase(java.util.Locale.ROOT));
                for (MessagePartInput part : message.getParts()) if (ConversationInput.PROVENANCE_PART_KIND.equals(part.getKind())) {
                    JSONObject encoded = new JSONObject(part.getPayload());
                    row.put("inputOrigin", encoded.optString("origin", message.getRole().toUpperCase(java.util.Locale.ROOT)));
                    JSONObject provenance = encoded.optJSONObject("provenance");
                    if (provenance != null) row.put("provenance", provenance);
                    break;
                }
            }
            if (message.getStatus() != null) row.put("status", message.getStatus());
            if (message.getRunId() != null) row.put("runId", message.getRunId());
            if (message.getOwnerUserMessageId() != null) row.put("userMessageId", message.getOwnerUserMessageId());
            if (message.getDurationMillis() != null) row.put("durationMs", message.getDurationMillis());
            if (message.getProactiveThreadKey() != null) row.put("proactiveThreadKey", message.getProactiveThreadKey());
            if (message.getProactiveReplyToMessageId() != null)
                row.put("proactiveReplyToMessageId", message.getProactiveReplyToMessageId());
            if (message.getInterruptRequestId() != null) row.put("interruptRequestId", message.getInterruptRequestId());
            if (message.getAcknowledgementRequired()) row.put("mainChatAcknowledgementRequired", true);
            if (!message.getAttachments().isEmpty()) {
                JSONArray attachments = new JSONArray();
                for (MessageAttachmentInput attachment : message.getAttachments()) {
                    ChatAttachment.Kind kind;
                    if ("IMAGE".equals(attachment.getKind())) kind = ChatAttachment.Kind.IMAGE;
                    else if ("FILE".equals(attachment.getKind())) kind = ChatAttachment.Kind.FILE;
                    else throw new IllegalStateException("Unknown chat attachment kind");
                    attachments.put(new ChatAttachment(attachment.getAttachmentId(), attachment.getName(),
                            attachment.getMimeType(), attachment.getSizeBytes(),
                            kind, attachment.getRelativePath()).toJson());
                }
                row.put("attachments", attachments);
            }
            if (message.getTranslation() != null) {
                row.put("translation", message.getTranslation().getContent());
                row.put("translationLanguage", message.getTranslation().getLanguage());
            }
            if (!message.getSuggestedReplies().isEmpty()) {
                JSONArray replies = new JSONArray();
                for (com.jarvys.agent.chatdb.SuggestedReplyInput reply : message.getSuggestedReplies())
                    replies.put(new JSONObject().put("label", reply.getLabel()).put("text", reply.getText()));
                row.put("suggestedReplies", replies);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not project database conversation message", error);
        }
        return row;
    }

    public synchronized String beginRun(String goal) {
        return beginRun(goal, null);
    }

    public synchronized String beginRun(String goal, String sessionId) {
        if (sessionId != null && !sessionId.trim().isEmpty() && isConversationDeleted(sessionId)) {
            throw new IllegalStateException("This chat has been deleted");
        }
        synchronized (SESSION_TITLE_LOCK) {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            activeRunId = stamp + "_" + Long.toHexString(System.nanoTime());
            activeRunDirectory = new File(root, activeRunId);
            try { verifyRunPath(activeRunDirectory); }
            catch (IOException error) { throw new IllegalStateException("Unsafe local run directory", error); }
            ensureDirectory(activeRunDirectory);
            runDirectories.put(activeRunId, activeRunDirectory);
            runSessions.put(activeRunId, sessionId == null || sessionId.trim().isEmpty() ? null : sessionId);
            JSONObject start = new JSONObject();
            try {
                start.put("event", "run_start");
                start.put("run_id", activeRunId);
                if (sessionId != null && !sessionId.trim().isEmpty()) start.put("session_id", sessionId);
                start.put("goal", goal);
                start.put("timestamp", System.currentTimeMillis() / 1000.0);
            } catch (Exception error) { throw new IllegalStateException("Could not create run metadata", error); }
            appendJson(start);
            return activeRunId;
        }
    }

    public synchronized String appendConversationMessage(String sessionId, String role, String content) {
        return appendConversationMessage(sessionId, role, content, null, "", "", "");
    }

    public synchronized String appendConversationMessage(String sessionId, String role, String content,
                                                          Long durationMs, String runId, String userMessageId) {
        return appendConversationMessage(sessionId, role, content, durationMs, runId, userMessageId, "");
    }

    /** Append a versioned, recoverable Crew snapshot to this conversation. */
    public synchronized void appendCrewMissionSnapshot(CrewMissionSnapshot snapshot) {
        if (snapshot == null || snapshot.conversationId == null || snapshot.conversationId.trim().isEmpty()) {
            throw new IllegalArgumentException("Crew snapshot requires a conversation id");
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        requireChatDbSuccess(chatDatabaseBridge.appendCrewSnapshotSync(
                snapshot.conversationId, snapshot.toJson()), "append Crew snapshot");
    }

    /**
     * UX43 local result journal. All comparisons and fsynced appends share SESSION_TITLE_LOCK
     * across store instances. beforeWrite runs immediately before a new append, never on reads.
     * Callers must acquire their outer delivery/STOP gate before entering these methods; the
     * callback may check current policy but must not read the registry, acquire another store,
     * start work, or make provider calls. No approval or runtime action is retained in these rows.
     * Source ledgers must already exist. Only the first Central notice may create a ledger;
     * the existing deletion tombstone always wins, so neither path resurrects a deleted chat.
     */
    public synchronized CrewResultEvidence appendCrewResultEvidence(CrewResultEvidence evidence, Runnable beforeWrite) {
        if (evidence == null || beforeWrite == null) throw new IllegalArgumentException("Evidence and admission are required");
        CrewResultEvidence.fromJsonStrict(evidence.toJson());
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.appendCrewResultEvidenceSync(
                evidence, beforeWrite::run), "append Crew result evidence");
    }

    public CrewResultEvidence appendCrewResultEvidence(CrewResultEvidence evidence) {
        return appendCrewResultEvidence(evidence, () -> { });
    }

    /** Exact raw evidence retrieval; callers must page resultText before exposing it to a model/UI. */
    public synchronized CrewResultEvidence readCrewResultEvidence(String sourceSessionId, String evidenceId) {
        requireExecutionSession(sourceSessionId);
        WorkspaceResultReference.requireEvidenceId(evidenceId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.readCrewResultEvidenceSync(
                sourceSessionId, evidenceId), "read Crew result evidence");
    }

    /** Lexically paged evidence IDs only; never an unbounded list of worker result text. */
    public synchronized List<String> listCrewResultEvidence(String sourceSessionId, int limit, String afterEvidenceId) {
        requireExecutionSession(sourceSessionId);
        resultPage(limit);
        if (afterEvidenceId != null) WorkspaceResultReference.requireEvidenceId(afterEvidenceId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.listCrewResultEvidenceIdsSync(
                sourceSessionId, limit, afterEvidenceId), "list Crew result evidence");
    }

    public synchronized WorkspaceResultReference appendWorkspaceResultReferenceIfAbsent(
            WorkspaceResultReference reference, Runnable beforeWrite) {
        requireResultReference(reference, beforeWrite);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.appendWorkspaceResultReferenceSync(
                reference, beforeWrite::run), "append workspace result reference");
    }

    public synchronized WorkspaceResultReference readWorkspaceResultReference(String sourceSessionId, String referenceId) {
        requireExecutionSession(sourceSessionId);
        WorkspaceResultReference.requireReferenceId(referenceId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.readWorkspaceResultReferenceSync(
                sourceSessionId, referenceId, false), "read workspace result reference");
    }

    public synchronized List<WorkspaceResultReference> listWorkspaceResultReferences(
            String sourceSessionId, int limit, String afterReferenceId) {
        requireExecutionSession(sourceSessionId);
        resultPage(limit);
        if (afterReferenceId != null) WorkspaceResultReference.requireReferenceId(afterReferenceId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.listWorkspaceResultReferencesSync(
                sourceSessionId, false, limit, afterReferenceId), "list workspace result references");
    }

    /** Receipt existence proves local delivery only; no worker text, role, or approval is copied. */
    public WorkspaceResultReference appendWorkspaceResultNoticeIfAbsent(
            WorkspaceResultReference reference, Runnable beforeWrite) {
        requireResultReference(reference, beforeWrite);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.appendWorkspaceResultNoticeSync(
                reference, beforeWrite::run), "append workspace result notice");
    }

    public synchronized WorkspaceResultReference readWorkspaceResultNotice(String centralSessionId, String referenceId) {
        requireCentralSession(centralSessionId);
        WorkspaceResultReference.requireReferenceId(referenceId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.readWorkspaceResultReferenceSync(
                centralSessionId, referenceId, true), "read workspace result notice");
    }

    public synchronized List<WorkspaceResultReference> listWorkspaceResultNotices(
            String centralSessionId, int limit, String afterReferenceId) {
        requireCentralSession(centralSessionId);
        resultPage(limit);
        if (afterReferenceId != null) WorkspaceResultReference.requireReferenceId(afterReferenceId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.listWorkspaceResultReferencesSync(
                centralSessionId, true, limit, afterReferenceId), "list workspace result notices");
    }

    public synchronized CrewCoordinationEvidence appendCrewCoordinationEvidence(CrewCoordinationEvidence evidence, Runnable beforeWrite) {
        if (evidence == null || beforeWrite == null) throw new IllegalArgumentException("Evidence and admission are required");
        CrewCoordinationEvidence.fromJsonStrict(evidence.toJson());
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.appendCrewCoordinationEvidenceSync(
                evidence, beforeWrite::run), "append Crew coordination evidence");
    }

    public CrewCoordinationEvidence appendCrewCoordinationEvidence(CrewCoordinationEvidence evidence) {
        return appendCrewCoordinationEvidence(evidence, () -> { });
    }

    /** Exact raw evidence retrieval; callers must page text before exposing it to a model/UI. */
    public synchronized CrewCoordinationEvidence readCrewCoordinationEvidence(String sourceSessionId, String evidenceId) {
        requireExecutionSession(sourceSessionId);
        WorkspaceCoordinationReference.requireEvidenceId(evidenceId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.readCrewCoordinationEvidenceSync(
                sourceSessionId, evidenceId), "read Crew coordination evidence");
    }

    /** Lexically paged evidence IDs only; never an unbounded list of worker result text. */
    public synchronized List<String> listCrewCoordinationEvidence(String sourceSessionId, int limit, String afterEvidenceId) {
        requireExecutionSession(sourceSessionId);
        resultPage(limit);
        if (afterEvidenceId != null) WorkspaceCoordinationReference.requireEvidenceId(afterEvidenceId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.listCrewCoordinationEvidenceIdsSync(
                sourceSessionId, limit, afterEvidenceId), "list Crew coordination evidence");
    }

    public synchronized WorkspaceCoordinationReference appendWorkspaceCoordinationReferenceIfAbsent(
            WorkspaceCoordinationReference reference, Runnable beforeWrite) {
        requireCoordinationReference(reference, beforeWrite);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.appendWorkspaceCoordinationReferenceSync(
                reference, beforeWrite::run), "append workspace coordination reference");
    }

    public synchronized WorkspaceCoordinationReference readWorkspaceCoordinationReference(String sourceSessionId, String referenceId) {
        requireExecutionSession(sourceSessionId);
        WorkspaceCoordinationReference.requireReferenceId(referenceId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.readWorkspaceCoordinationReferenceSync(
                sourceSessionId, referenceId, false), "read workspace coordination reference");
    }

    /** Resolves a source Crew message ID to its existing immutable coordination reference. */
    public synchronized WorkspaceCoordinationReference findWorkspaceCoordinationReferenceBySourceMessageId(
            String sourceSessionId, String sourceMessageId) {
        requireExecutionSession(sourceSessionId);
        if (sourceMessageId == null || !sourceMessageId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}"))
            throw new IllegalArgumentException("Invalid Crew source message ID");
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.findWorkspaceCoordinationReferenceBySourceMessageIdSync(
                sourceSessionId, sourceMessageId), "find workspace coordination reference by source message ID");
    }

    public synchronized List<WorkspaceCoordinationReference> listWorkspaceCoordinationReferences(
            String sourceSessionId, int limit, String afterReferenceId) {
        requireExecutionSession(sourceSessionId);
        resultPage(limit);
        if (afterReferenceId != null) WorkspaceCoordinationReference.requireReferenceId(afterReferenceId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.listWorkspaceCoordinationReferencesSync(
                sourceSessionId, false, limit, afterReferenceId), "list workspace coordination references");
    }

    /** Receipt existence proves local delivery only; no worker text, role, or approval is copied. */
    public synchronized WorkspaceCoordinationReference appendWorkspaceCoordinationNoticeIfAbsent(
            WorkspaceCoordinationReference reference, Runnable beforeWrite) {
        requireCoordinationReference(reference, beforeWrite);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.appendWorkspaceCoordinationNoticeSync(
                reference, beforeWrite::run), "append workspace coordination notice");
    }

    public synchronized WorkspaceCoordinationReference readWorkspaceCoordinationNotice(String centralSessionId, String referenceId) {
        requireCentralSession(centralSessionId);
        WorkspaceCoordinationReference.requireReferenceId(referenceId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.readWorkspaceCoordinationReferenceSync(
                centralSessionId, referenceId, true), "read workspace coordination notice");
    }

    public synchronized List<WorkspaceCoordinationReference> listWorkspaceCoordinationNotices(
            String centralSessionId, int limit, String afterReferenceId) {
        requireCentralSession(centralSessionId);
        resultPage(limit);
        if (afterReferenceId != null) WorkspaceCoordinationReference.requireReferenceId(afterReferenceId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.listWorkspaceCoordinationReferencesSync(
                centralSessionId, true, limit, afterReferenceId), "list workspace coordination notices");
    }

    /** A single durable reply claim per exact source request. No dispatch happens inside the store. */
    public synchronized WorkspaceCoordinationReply prepareWorkspaceCoordinationReply(
            WorkspaceCoordinationReply proposed, Runnable finalAdmission) {
        requireCoordinationReply(proposed, finalAdmission);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.prepareWorkspaceCoordinationReplySync(
                proposed, finalAdmission::run), "prepare workspace coordination reply");
    }

    /** Only a newly appended STARTED record authorizes the caller's one exact synchronous dispatch. */
    public synchronized WorkspaceCoordinationReply markWorkspaceCoordinationReplyStarted(
            WorkspaceCoordinationReply proposed, Runnable finalAdmission) {
        requireCoordinationReply(proposed, finalAdmission);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.markWorkspaceCoordinationReplyStartedSync(
                proposed, finalAdmission::run), "mark workspace coordination reply started");
    }

    /** Record only after the exact live mailbox synchronously accepted; a failed write leaves STARTED/UNCERTAIN. */
    public synchronized WorkspaceCoordinationReply markWorkspaceCoordinationReplyAccepted(
            WorkspaceCoordinationReply proposed, Runnable finalAdmission) {
        requireCoordinationReply(proposed, finalAdmission);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.markWorkspaceCoordinationReplyAcceptedSync(
                proposed, finalAdmission::run), "mark workspace coordination reply accepted");
    }

    /** Historical receipt only. STARTED means UNCERTAIN, including after reopening; never resume or replay. */
    public synchronized WorkspaceCoordinationReply readWorkspaceCoordinationReply(String centralSessionId, String referenceId) {
        requireCentralSession(centralSessionId);
        WorkspaceCoordinationReference.requireReferenceId(referenceId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.readWorkspaceCoordinationReplySync(
                centralSessionId, referenceId, null), "read workspace coordination reply");
    }

    public synchronized WorkspaceCoordinationReply readWorkspaceCoordinationReply(
            String centralSessionId, String referenceId, String replyKey) {
        requireCentralSession(centralSessionId);
        if (replyKey == null || !replyKey.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}"))
            throw new IllegalArgumentException("Invalid coordination reply key");
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.readWorkspaceCoordinationReplySync(
                centralSessionId, referenceId, replyKey), "read keyed workspace coordination reply");
    }

    /** Exact bounded identity lookup; at most two strict scans, with no retained reply bodies or new index. */
    public synchronized WorkspaceCoordinationReply readWorkspaceCoordinationReplyById(String centralSessionId, String replyId) {
        requireCentralSession(centralSessionId);
        if (replyId == null || !replyId.matches("workspace-coordination-reply-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid coordination reply id");
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.readWorkspaceCoordinationReplyByIdSync(
                centralSessionId, replyId), "read workspace coordination reply by ID");
    }

    /** One indexed Central-ledger aggregate; only STARTED QUESTION/BLOCKER replies are counted. */
    public synchronized Map<String, Integer> countStartedQuestionBlockerRepliesByWorkspace(
            String centralSessionId, Set<String> workspaceIds) {
        requireCentralSession(centralSessionId);
        if (workspaceIds == null) throw new IllegalArgumentException("Workspace identities are required");
        for (String workspaceId : workspaceIds) WorkspaceIdentity.requireWorkspaceId(workspaceId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return Collections.unmodifiableMap(requireChatDbSuccess(
                chatDatabaseBridge.countStartedQuestionBlockerRepliesByWorkspaceSync(centralSessionId, workspaceIds),
                "count started QUESTION/BLOCKER replies by workspace"));
    }

    public synchronized void requireWorkspaceCoordinationDisclosureLive(
            WorkspaceCoordinationReference reference, boolean noticeRequired) {
        requireCoordinationReference(reference, () -> { });
        if (chatDatabaseBridge == null) throw noChatDatabase();
        requireChatDbSuccess(chatDatabaseBridge.requireWorkspaceCoordinationDisclosureLiveSync(reference, noticeRequired),
                "check workspace coordination disclosure");
    }

    /**
     * Append historical checkpoint incorporation without changing reply/control dispatch state.
     * The caller must first validate the confirmed checkpoint and authoritative source (including
     * SQL for native CORRECT) outside Crew/store locks. finalAdmission obeys the same lightweight
     * STOP/policy-only callback contract as the existing result journal. Neither ledger is created.
     */
    public synchronized WorkspaceInputIncorporation appendWorkspaceInputIncorporationIfAbsent(
            WorkspaceInputIncorporation proposed, Runnable finalAdmission) {
        if (proposed == null || finalAdmission == null)
            throw new IllegalArgumentException("Incorporation and admission are required");
        WorkspaceInputIncorporation.fromJsonStrict(proposed.toJson());
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.appendWorkspaceInputIncorporationSync(
                proposed, finalAdmission::run), "append workspace input incorporation");
    }

    public synchronized boolean appendWorkspaceInputIncorporationIfAbsentAndReport(
            WorkspaceInputIncorporation proposed, Runnable finalAdmission) {
        if (proposed == null || finalAdmission == null)
            throw new IllegalArgumentException("Incorporation and admission are required");
        WorkspaceInputIncorporation.fromJsonStrict(proposed.toJson());
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.appendWorkspaceInputIncorporationIfAbsentSync(
                proposed, finalAdmission::run), "append workspace input incorporation");
    }

    /** Exact metadata only; current registry ownership and READ policy remain the caller's responsibility. */
    public synchronized WorkspaceInputIncorporation readWorkspaceInputIncorporation(String centralSessionId,
            WorkspaceInputIncorporation.SourceKind sourceKind, String sourceId) {
        requireCentralSession(centralSessionId);
        WorkspaceInputIncorporation.receiptId(sourceKind, sourceId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.readWorkspaceInputIncorporationSync(
                centralSessionId, sourceKind, sourceId), "read workspace input incorporation");
    }

    /** Bounded lexical pages, never raw source bodies or an unbounded receipt collection. */
    public synchronized List<WorkspaceInputIncorporation> listWorkspaceInputIncorporations(
            String centralSessionId, int limit, String afterReceiptId) {
        requireCentralSession(centralSessionId);
        resultPage(limit);
        if (afterReceiptId != null) WorkspaceInputIncorporation.requireReceiptId(afterReceiptId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.listWorkspaceInputIncorporationsSync(
                centralSessionId, limit, afterReceiptId), "list workspace input incorporations");
    }

    /** Final local tombstone/source boundary; callers still enforce current READ/owner admission. */
    public synchronized void requireWorkspaceInputIncorporationDisclosureLive(WorkspaceInputIncorporation value) {
        if (value == null) throw new IllegalArgumentException("Incorporation is required");
        WorkspaceInputIncorporation.fromJsonStrict(value.toJson());
        if (chatDatabaseBridge == null) throw noChatDatabase();
        requireChatDbSuccess(chatDatabaseBridge.requireWorkspaceInputIncorporationDisclosureLiveSync(value),
                "check workspace input incorporation disclosure");
    }

    private static void requireCoordinationReply(WorkspaceCoordinationReply value, Runnable admission) {
        if (value == null || admission == null) throw new IllegalArgumentException("Reply and admission are required");
        WorkspaceCoordinationReply.fromJsonStrict(value.toJson());
    }
    private static void requireCoordinationReference(WorkspaceCoordinationReference value, Runnable admission) {
        if (value == null || admission == null) throw new IllegalArgumentException("Reference and admission are required");
        WorkspaceCoordinationReference.fromJsonStrict(value.toJson());
    }
    /** Lightweight last disclosure boundary; no evidence content or callbacks under this lock. */
    public synchronized void requireWorkspaceResultDisclosureLive(WorkspaceResultReference reference, boolean noticeRequired) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        requireChatDbSuccess(chatDatabaseBridge.requireWorkspaceResultDisclosureLiveSync(reference, noticeRequired),
                "check workspace result disclosure");
    }

    private static void requireResultReference(WorkspaceResultReference value, Runnable beforeWrite) {
        if (value == null || beforeWrite == null) throw new IllegalArgumentException("Reference and admission are required");
        WorkspaceResultReference.fromJsonStrict(value.toJson());
    }
    private static void resultPage(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Result page limit must be 1..100");
    }

    private static void requireExecutionSession(String sessionId) {
        if (!WorkspaceIdentity.isExecutionSession(sessionId))
            throw new IllegalArgumentException("Invalid workspace execution identity");
    }

    private static void requireCentralSession(String sessionId) {
        if (!WorkspaceIdentity.isCentral(sessionId))
            throw new IllegalArgumentException("Invalid Central conversation identity");
    }

    /** Reads the latest schema-supported Crew snapshot for each mission, preserving insertion order. */
    public List<CrewMissionSnapshot> readCrewMissionSnapshots(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.readCrewMissionSnapshotsSync(sessionId);
    }

    /** Active database rows from an older process are interrupted, never resumed. */
    public synchronized List<CrewMissionSnapshot> recoverCrewMissions(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.recoverCrewMissionsSync(sessionId, CrewProcessIdentity.ID);
    }

    public synchronized String appendConversationMessage(String sessionId, String role, String content,
                                                          Long durationMs,
                                                          String runId, String userMessageId, String status) {
        return appendConversationMessage(sessionId, role, content, durationMs, runId, userMessageId, status, null);
    }

    public synchronized String appendConversationMessage(String sessionId, String role, String content,
                                                          List<ChatAttachment> attachments) {
        return appendConversationMessage(sessionId, role, content, null, "", "", "", null, attachments);
    }

    public synchronized String appendConversationMessage(String sessionId, String role, String content,
                                                          Long durationMs, String runId, String userMessageId,
                                                          String status, String proactiveThreadKey) {
        return appendConversationMessage(sessionId, role, content, durationMs, runId, userMessageId,
                status, proactiveThreadKey, Collections.emptyList());
    }

    public synchronized String appendConversationMessage(String sessionId, String role, String content,
                                                          Long durationMs, String runId, String userMessageId,
                                                          String status, String proactiveThreadKey,
                                                          List<ChatAttachment> attachments) {
        if (attachments != null && !attachments.isEmpty() && (!"user".equals(role)
                || (proactiveThreadKey != null && !proactiveThreadKey.isEmpty()))) {
            throw new IllegalArgumentException("Attachments are supported only in main-chat user messages");
        }
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new IllegalArgumentException("Conversation role must be user or assistant");
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        List<MessageAttachmentInput> storedAttachments = new ArrayList<>();
        AttachmentStore attachmentStore = attachments == null || attachments.isEmpty()
                ? null : new AttachmentStore(root.getParentFile());
        if (attachments != null) for (ChatAttachment attachment : attachments) {
            if (attachment != null) try {
                File file = attachmentStore.resolve(sessionId, attachment);
                storedAttachments.add(new MessageAttachmentInput(
                        attachment.id, attachment.name, attachment.mimeType, attachment.sizeBytes,
                        attachment.kind.name(), attachment.relativePath, fileSha256(file)));
            } catch (IOException error) {
                throw new IllegalStateException("Could not verify conversation attachment", error);
            }
        }
        StoredMessage saved = requireChatDbSuccess(chatDatabaseBridge.appendMessageSync(new AppendMessageInput(
                sessionId, role, Collections.singletonList(new MessagePartInput("text", content == null ? "" : content)),
                status, runId, userMessageId, durationMs, null, proactiveThreadKey, null, null,
                null, false, storedAttachments, Collections.emptyList(),
                System.currentTimeMillis() / 1000.0, conversationKindFor(sessionId))),
                "append conversation message");
        return saved.getMessageId();
    }

    /** Persists one content-bearing, attributed input in the existing conversation message ledger. */
    public synchronized String appendConversationInput(ConversationInput input) {
        if (input == null) throw new IllegalArgumentException("Conversation input is required");
        if (chatDatabaseBridge == null) throw noChatDatabase();
        String role = input.origin.name().toLowerCase(java.util.Locale.ROOT);
        List<MessagePartInput> parts = new ArrayList<>();
        parts.add(new MessagePartInput("text", input.content));
        MessagePartInput provenance = input.provenancePart();
        if (provenance != null) parts.add(provenance);
        StoredMessage saved = requireChatDbSuccess(chatDatabaseBridge.appendMessageSync(new AppendMessageInput(
                input.conversationId, role, parts, null, null, null, null, null, null, null, null,
                null, false, Collections.emptyList(), Collections.emptyList(),
                System.currentTimeMillis() / 1000.0, conversationKindFor(input.conversationId), input.stableId())),
                "append attributed conversation input");
        return saved.getMessageId();
    }

    /**
     * Appends the assistant message that points a Central thread at a bot mission. The reference is a
     * separate message part. It is a timeline card, not conversation: model context and compaction skip it.
     * The same stable id never appends twice.
     */
    public synchronized String appendMissionReferenceMessage(String sessionId, String text, String referencePayload,
                                                              String stableId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        List<MessagePartInput> parts = new ArrayList<>();
        parts.add(new MessagePartInput("text", text));
        parts.add(new MessagePartInput(com.jarvys.agent.workspaces.MissionRef.PART_KIND, referencePayload));
        StoredMessage saved = requireChatDbSuccess(chatDatabaseBridge.appendMessageSync(new AppendMessageInput(
                sessionId, "assistant", parts, null, null, null, null, null, null, null, null,
                null, false, Collections.emptyList(), Collections.emptyList(),
                System.currentTimeMillis() / 1000.0, conversationKindFor(sessionId), stableId)),
                "append mission reference");
        return saved.getMessageId();
    }

    /** Restores the typed input metadata stored with an AGENT/SYSTEM message row. */
    public ConversationInput readConversationInput(String sessionId, String messageId) {
        if (sessionId == null || messageId == null || chatDatabaseBridge == null) return null;
        StoredMessage message = chatDatabaseBridge.readMessageSync(sessionId, messageId);
        if (message == null) return null;
        ConversationInput.Origin origin;
        if ("user".equals(message.getRole())) origin = ConversationInput.Origin.USER;
        else if ("agent".equals(message.getRole())) origin = ConversationInput.Origin.AGENT;
        else if ("system".equals(message.getRole())) origin = ConversationInput.Origin.SYSTEM;
        else return null;
        ConversationInput.Provenance provenance = null;
        for (MessagePartInput part : message.getParts()) {
            if (!ConversationInput.PROVENANCE_PART_KIND.equals(part.getKind())) continue;
            try {
                JSONObject encoded = new JSONObject(part.getPayload());
                ConversationInput.Origin taggedOrigin = ConversationInput.parseOrigin(encoded.optString("origin", ""));
                if (taggedOrigin != null) origin = taggedOrigin;
                provenance = ConversationInput.Provenance.fromJson(encoded.optJSONObject("provenance"));
            } catch (Exception invalid) {
                throw new IllegalStateException("Stored conversation input provenance is invalid", invalid);
            }
            break;
        }
        if (origin != ConversationInput.Origin.USER && provenance == null)
            throw new IllegalStateException("Stored internal input has no typed provenance");
        return new ConversationInput(sessionId, messageId, message.getText(), origin, provenance);
    }

    /** Indexed lookup used to suppress a retry of an already accepted source message. */
    public JSONObject readConversationMessageByStableId(String sessionId, String stableId) {
        if (sessionId == null || stableId == null || chatDatabaseBridge == null) return null;
        StoredMessage message = chatDatabaseBridge.readMessageByStableIdSync(sessionId, stableId);
        return message == null ? null : messageJson(message);
    }

    public String readConversationInputState(String sessionId, String messageId) {
        if (sessionId == null || messageId == null || chatDatabaseBridge == null) return null;
        StoredMessage message = chatDatabaseBridge.readMessageSync(sessionId, messageId);
        return message != null && isConversationInputRole(message.getRole()) ? message.getInputState() : null;
    }

    /** Persists a STOP/start-failure result and resolves its queued follow-up in one DB transaction. */
    public String persistStoppedSubmission(String sessionId, String followUpRequestId,
            String ownerUserMessageId, String userMessageContentIfMissing, String assistantMessage) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        StoppedSubmissionResult result = requireChatDbSuccess(chatDatabaseBridge.persistStoppedSubmissionSync(
                sessionId, emptyToNull(followUpRequestId), emptyToNull(ownerUserMessageId),
                userMessageContentIfMissing, assistantMessage, conversationKindFor(sessionId)),
                "persist stopped submission");
        return result.getAssistantMessage().getMessageId();
    }

    /** Idempotent system-authored assistant message (proactive notice or task result) in the given conversation. */
    public String appendProactiveAssistantMessageIfAbsent(String sessionId, String decisionId,
            String content) {
        return appendProactiveAssistantMessageIfAbsent(sessionId, decisionId, content, null, "");
    }

    public String appendProactiveAssistantMessageIfAbsent(String sessionId, String decisionId,
            String content, List<ProactiveSuggestedReply> replies, String threadKey) {
        if (decisionId == null || decisionId.trim().isEmpty()) {
            throw new IllegalArgumentException("A proactive decision id is required");
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        List<com.jarvys.agent.chatdb.SuggestedReplyInput> storedReplies = new ArrayList<>();
        if (replies != null) for (ProactiveSuggestedReply reply : replies)
            storedReplies.add(new com.jarvys.agent.chatdb.SuggestedReplyInput(reply.getLabel(), reply.getText()));
        StoredMessage saved = requireChatDbSuccess(chatDatabaseBridge.appendProactiveAssistantMessageIfAbsentSync(
                sessionId, decisionId, content == null ? "" : content, threadKey, storedReplies),
                "append proactive message");
        return saved.getMessageId();
    }

    /** Appends the user-selected proactive reply before dispatching an ordinary agent turn. */
    public synchronized String appendProactiveUserMessage(String sessionId, String content, String threadKey,
                                                           String replyToMessageId) {
        if (content == null || content.trim().isEmpty() || threadKey == null || threadKey.trim().isEmpty()) {
            throw new IllegalArgumentException("A proactive user reply and thread key are required");
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        StoredMessage saved = requireChatDbSuccess(chatDatabaseBridge.appendMessageSync(new AppendMessageInput(
                sessionId, "user", Collections.singletonList(new MessagePartInput("text", content)), null,
                null, null, null, null, threadKey, replyToMessageId, null, null, false,
                Collections.emptyList(), Collections.emptyList(), System.currentTimeMillis() / 1000.0,
                conversationKindFor(sessionId))), "append proactive reply");
        return saved.getMessageId();
    }

    /** Atomically consumes every quick reply on a proactive message and returns only the selected text. */
    public ProactiveReplyClaim claimProactiveSuggestedReply(String sessionId, String messageId, int index) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.claimProactiveSuggestedReplySync(sessionId, messageId, index),
                "claim proactive suggested reply");
    }

    public String proactiveThreadKeyForUserMessage(String sessionId, String userMessageId) {
        if (userMessageId == null || userMessageId.isEmpty()) return null;
        if (chatDatabaseBridge == null) throw noChatDatabase();
        StoredMessage message = chatDatabaseBridge.readMessageSync(sessionId, userMessageId);
        if (message == null || !"user".equals(message.getRole())) return null;
        String key = message.getProactiveThreadKey();
        return key == null || key.isEmpty() ? null : key;
    }

    /** One stored row by message id, whatever its role. */
    public StoredMessage readStoredMessage(String sessionId, String messageId) {
        if (messageId == null || messageId.isEmpty()) return null;
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.readMessageSync(sessionId, messageId);
    }

    /** Visible rows of one proactive or task thread, read through the thread index instead of the timeline. */
    public List<StoredMessage> readProactiveThreadMessages(String sessionId, String threadKey) {
        if (threadKey == null || threadKey.isEmpty()) return Collections.emptyList();
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.readThreadMessagesSync(sessionId, threadKey);
    }

    /** The compacted notice a thread reply answers and its exchange; null while the notice is still live. */
    public ProactiveReferent readCompactedProactiveReferent(String sessionId, String replyMessageId) {
        if (replyMessageId == null || replyMessageId.isEmpty()) return null;
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.readCompactedProactiveReferentSync(sessionId, replyMessageId);
    }

    public String readConversationMessage(String sessionId, String messageId) {
        if (messageId == null || messageId.isEmpty()) return null;
        if (chatDatabaseBridge == null) throw noChatDatabase();
        StoredMessage message = chatDatabaseBridge.readMessageSync(sessionId, messageId);
        return message != null && "user".equals(message.getRole()) ? message.getText() : null;
    }

    public synchronized void appendAssistantTranslation(String sessionId, String messageId,
                                                         String language, String translation) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        requireChatDbSuccess(chatDatabaseBridge.setTranslationSync(
                sessionId, messageId, language, translation == null ? "" : translation),
                "store assistant translation");
    }

    /** Durable chat-card data so an interrupted choice can be shown as unanswered, never resumed. */
    public synchronized void appendUserDecisionRequest(String sessionId, String decisionId, String title,
                                                        String body, List<UserDecisionOption> options,
                                                        boolean allowDismiss) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        List<com.jarvys.agent.chatdb.DecisionOptionInput> storedOptions = new ArrayList<>();
        if (options != null) for (UserDecisionOption option : options) storedOptions.add(
                new com.jarvys.agent.chatdb.DecisionOptionInput(option.getId(), option.getLabel(),
                        option.getDescription(), option.getRole().name().toLowerCase(Locale.ROOT)));
        requireChatDbSuccess(chatDatabaseBridge.appendDecisionRequestSync(
                sessionId, decisionId, title == null ? "" : title, body == null ? "" : body,
                allowDismiss, storedOptions), "store decision request");
    }

    public synchronized void appendUserDecisionResolution(String sessionId, String decisionId, String status,
                                                           String optionId, String optionLabel) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        requireChatDbSuccess(chatDatabaseBridge.resolveDecisionSync(
                sessionId, decisionId, status, optionId, optionLabel), "store decision resolution");
    }

    public void appendAssistantRegenerated(String sessionId, String messageId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        regenerateAssistantIfLatest(sessionId, messageId);
    }

    public void appendAssistantDeleted(String sessionId, String messageId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        hideAssistantWithOwner(sessionId, messageId, "assistant_deleted");
    }

    public void appendAssistantTranslationHidden(String sessionId, String messageId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        requireChatDbSuccess(chatDatabaseBridge.hideTranslationSync(sessionId, messageId), "hide assistant translation");
    }

    /** Atomically checks the latest active assistant, resolves its prompt owner, and hides it for regeneration. */
    public RegenerationPromptValue regenerateAssistantIfLatest(String sessionId, String messageId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.regenerateIfLatestAssistantSync(sessionId, messageId),
                "regenerate assistant response");
    }

    /** One transaction captures the assistant's owner and appends its hidden marker. */
    public AssistantMarkerValue hideAssistantWithOwner(String sessionId, String messageId, String kind) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.hideAssistantWithOwnerSync(sessionId, messageId, kind),
                "hide assistant response");
    }

    /** Commits ownership before the runtime reports a successful native attachment. Repeats are idempotent. */
    public void appendDeliveredFile(String sessionId, ChatAttachment artifact) {
        appendDeliveredFile(sessionId, artifact, null, null, null, null);
    }

    /** Commits one module-produced artifact with its opaque manifest and declared presentation type. */
    public void appendDeliveredFile(String sessionId, ChatAttachment artifact, String artifactType,
                                    String moduleId, String manifestJson, String title) {
        appendDeliveredModuleArtifact(sessionId, artifact, artifactType, moduleId, manifestJson, title, null);
    }

    /** Commits a module artifact and its disk-backed thumbnail through the existing Room v2 references. */
    public String appendDeliveredModuleArtifact(String sessionId, ChatAttachment artifact, String artifactType,
                                    String moduleId, String manifestJson, String title,
                                    DeliveredArtifactStore.ThumbnailFile thumbnail) {
        DeliveredArtifactStore store = new DeliveredArtifactStore(filesDirectory);
        store.resolve(sessionId, artifact);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        try {
            String effectiveType = artifactType == null || artifactType.trim().isEmpty()
                    ? artifact.mimeType : artifactType;
            com.jarvys.agent.modules.ModuleManifest owner = appContext == null ? null
                    : com.jarvys.agent.modules.ModuleRegistry.get(appContext).artifactModuleForType(effectiveType);
            String effectiveModuleId = moduleId == null && owner != null ? owner.getId() : moduleId;
            String effectiveManifest = manifestJson == null && owner != null ? owner.getManifestJson() : manifestJson;
            com.jarvys.agent.chatdb.ConversationArtifact persisted = requireChatDbSuccess(chatDatabaseBridge.appendDeliveredArtifactSync(
                    new com.jarvys.agent.chatdb.DeliveredArtifactInput(sessionId, artifact.id, artifact.name,
                            artifact.mimeType, artifact.sizeBytes, artifact.kind.name(), artifact.relativePath,
                            store.sha256(sessionId, artifact), effectiveType, effectiveModuleId, effectiveManifest,
                            title, null,
                            thumbnail == null ? null : thumbnail.relativePath,
                            thumbnail == null ? null : thumbnail.sha256,
                            thumbnail == null ? null : thumbnail.sizeBytes,
                            thumbnail == null ? null : thumbnail.mimeType,
                            System.currentTimeMillis() / 1000.0)), "append delivered artifact");
            return persisted.getThumbnail_ref();
        } catch (IOException error) { throw new IllegalStateException("Could not verify delivered artifact", error); }
    }

    public ChatAttachment findChatFile(String sessionId, String kind, String id) {
        if (sessionId == null || id == null || id.isEmpty()
                || !"delivered".equals(kind) && !"attachment".equals(kind)) return null;
        if (chatDatabaseBridge == null) throw noChatDatabase();
        com.jarvys.agent.chatdb.ChatFileOwnership ownership = chatDatabaseBridge.findChatFileSync(sessionId, kind, id);
        if (ownership instanceof com.jarvys.agent.chatdb.ChatFileOwnership.Attachment)
            return ((com.jarvys.agent.chatdb.ChatFileOwnership.Attachment) ownership).getValue();
        if (ownership instanceof com.jarvys.agent.chatdb.ChatFileOwnership.Delivered) {
            com.jarvys.agent.chatdb.ConversationArtifact artifact =
                    ((com.jarvys.agent.chatdb.ChatFileOwnership.Delivered) ownership).getValue();
            try {
                ChatAttachment.Kind attachmentKind = "IMAGE".equalsIgnoreCase(artifact.getKind())
                        ? ChatAttachment.Kind.IMAGE : ChatAttachment.Kind.FILE;
                return new ChatAttachment(artifact.getArtifact_id(), artifact.getName(), artifact.getMime_type(),
                        artifact.getSize_bytes(), attachmentKind, artifact.getRelative_path());
            } catch (IllegalArgumentException malformedAttachmentProjection) { return null; }
        }
        return null;
    }

    public boolean ownsGeneratedFile(String sessionId, String path) {
        if (sessionId == null || path == null) return false;
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.ownsGeneratedFileSync(sessionId, path);
    }

    /** Persists an image reference and prompt only; raw image bytes stay in GeneratedImageStore. */
    public synchronized void appendGeneratedImageEvent(String sessionId, String relativePath,
                                                        String prompt, String revisedPrompt,
                                                        String size, String mimeType) {
        File image = new GeneratedImageStore(root.getParentFile()).resolve(sessionId, relativePath);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        try {
            String effectiveType = mimeType == null ? "image/png" : mimeType;
            com.jarvys.agent.modules.ModuleManifest owner = appContext == null ? null
                    : com.jarvys.agent.modules.ModuleRegistry.get(appContext).artifactModuleForType(effectiveType);
            requireChatDbSuccess(chatDatabaseBridge.appendGeneratedImageSync(
                    new com.jarvys.agent.chatdb.GeneratedImageInput(sessionId, prompt == null ? "" : prompt,
                            revisedPrompt == null ? "" : revisedPrompt, size == null ? "" : size,
                            mimeType == null ? "image/png" : mimeType, "COMPLETED", relativePath,
                            fileSha256(image), image.length(), null, System.currentTimeMillis() / 1000.0,
                            effectiveType, owner == null ? null : owner.getId(), prompt,
                            owner == null ? null : owner.getManifestJson())),
                    "append generated image");
        } catch (IOException error) { throw new IllegalStateException("Could not verify generated image", error); }
    }

    public synchronized void appendGeneratedImageFailure(String sessionId, String prompt, String error) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        requireChatDbSuccess(chatDatabaseBridge.appendGeneratedImageSync(
                new com.jarvys.agent.chatdb.GeneratedImageInput(sessionId, prompt == null ? "" : prompt,
                        "", "", "image/png", "FAILED", null, null, null,
                        error == null ? "Image generation failed." : error,
                        System.currentTimeMillis() / 1000.0, "image/png", null, prompt, null)), "append generated image failure");
    }

    private static String fileSha256(File file) throws IOException {
        try (java.io.InputStream input = new FileInputStream(file)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    /** Tombstones the database row, then best-effort collects files no longer owned by any row. */
    public synchronized boolean deleteConversation(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        boolean tombstoned = requireChatDbSuccess(chatDatabaseBridge.deleteConversationSync(sessionId),
                "tombstone conversation");
        boolean filesRemoved = deleteConversationOwnedFiles(sessionId);
        return tombstoned && filesRemoved;
    }

    public ConversationMetadataAccess.Snapshot readConversationMetadata(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        ConversationMetadataValue value = chatDatabaseBridge.readConversationMetadataSync(sessionId);
        return value == null
                ? new ConversationMetadataAccess.Snapshot(null, false, false, false)
                : new ConversationMetadataAccess.Snapshot(value.getTitle(), value.getPinned(),
                        value.getArchived(), value.getDeleted());
    }

    public void renameConversation(String sessionId, String title) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        requireChatDbSuccess(chatDatabaseBridge.renameConversationSync(
                sessionId, ConversationMetadataAccess.normalizeTitle(title)), "rename conversation");
    }

    public void setConversationPinned(String sessionId, boolean pinned) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        requireChatDbSuccess(chatDatabaseBridge.updateMetadataSync(sessionId, null, null, pinned, null),
                "update conversation pinned state");
    }

    public void setConversationArchived(String sessionId, boolean archived) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        requireChatDbSuccess(chatDatabaseBridge.updateMetadataSync(sessionId, null, null, null, archived),
                "update conversation archived state");
    }

    public List<ChatAttachment> readConversationAttachments(String sessionId, String messageId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.readMessageAttachmentsSync(sessionId, messageId);
    }

    public boolean conversationHasAttachments(String sessionId) {
        if (sessionId == null) return false;
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.conversationHasAttachmentsSync(sessionId);
    }

    List<ConversationImageReference> readConversationImageReferences(String sessionId) {
        List<ConversationImageReference> empty = Collections.emptyList();
        if (sessionId == null) return empty;
        if (chatDatabaseBridge == null) throw noChatDatabase();
        List<ConversationImageReference> result = new ArrayList<>();
        for (ConversationImageReferenceValue value : chatDatabaseBridge.readConversationImageReferencesSync(sessionId)) {
            ConversationImageReference reference = value.getAttachment() != null
                    ? ConversationImageReference.attachment(value.getMessageId(), value.getAttachment())
                    : ConversationImageReference.generated(value.getMessageId(), value.getGeneratedPath(), value.getPrompt());
            if (reference != null) result.add(reference);
        }
        return Collections.unmodifiableList(result);
    }

    public void cleanupOrphanAttachments() {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        Map<String, Set<String>> persistedReferences = new LinkedHashMap<>();
        for (ConversationSummary summary : chatDatabaseBridge.listAllConversationsSync(Collections.emptySet())) {
            if (summary.getDeleted()) continue;
            Set<String> paths = new HashSet<>();
            for (StoredMessage message : chatDatabaseBridge.readConversationMessagesSync(summary.getId())) {
                for (MessageAttachmentInput attachment : message.getAttachments())
                    paths.add(attachment.getRelativePath());
            }
            persistedReferences.put(summary.getId(), paths);
        }
        new AttachmentStore(root.getParentFile()).cleanupOrphans(persistedReferences);
    }

    private boolean runWasDeleted(String runId) {
        String sessionId = runSessions.get(runId);
        return sessionId != null && isConversationDeleted(sessionId);
    }

    private String sessionForRunDirectory(File run) {
        try {
            if (!root.getAbsoluteFile().equals(run.getAbsoluteFile().getParentFile())) throw new IOException("Run must be a direct child of private storage");
            verifyRunPath(run);
            if (!run.isDirectory()) return null;
            File ledger = new File(run, "steps.jsonl");
            verifyRunPath(ledger);
            if (!ledger.isFile()) return null;
            try (BufferedReader reader = new BufferedReader(new FileReader(ledger))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JSONObject row = new JSONObject(line);
                        if ("run_start".equals(row.optString("event"))) {
                            String id = row.optString("session_id", "");
                            if (id.isEmpty()) id = row.optString("run_id", run.getName());
                            if (id.isEmpty()) id = run.getName();
                            ConversationMetadataAccess.validateSessionId(id);
                            return id;
                        }
                    } catch (Exception incomplete) { }
                }
            }
            return null;
        } catch (Exception error) { throw new IllegalStateException("Could not safely read run ownership", error); }
    }

    /** Returns only an explicit conversation association; independent runs have no session id. */
    private String conversationSessionForRunDirectory(File run) {
        try {
            if (!root.getAbsoluteFile().equals(run.getAbsoluteFile().getParentFile()))
                throw new IOException("Run must be a direct child of private storage");
            verifyRunPath(run);
            if (!run.isDirectory()) return null;
            File ledger = new File(run, "steps.jsonl");
            verifyRunPath(ledger);
            if (!ledger.isFile()) return null;
            try (BufferedReader reader = new BufferedReader(new FileReader(ledger))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JSONObject row = new JSONObject(line);
                        if (!"run_start".equals(row.optString("event"))) continue;
                        String sessionId = row.optString("session_id", "");
                        if (sessionId.isEmpty()) return null;
                        ConversationMetadataAccess.validateSessionId(sessionId);
                        return sessionId;
                    } catch (Exception incomplete) { }
                }
            }
            return null;
        } catch (Exception error) {
            throw new IllegalStateException("Could not safely read run ownership", error);
        }
    }

    private void verifyRunPath(File file) throws IOException {
        String logicalRoot = root.getAbsolutePath();
        String logical = file.getAbsolutePath();
        if (!logical.equals(logicalRoot) && !logical.startsWith(logicalRoot + File.separator)) throw new IOException("Run path escapes private storage");
        String relative = logical.equals(logicalRoot) ? "" : logical.substring(logicalRoot.length() + 1);
        String expected = relative.isEmpty() ? runBoundary : new File(runBoundary, relative).getPath();
        if (!root.getCanonicalPath().equals(runBoundary) || !file.getCanonicalPath().equals(expected)) {
            throw new IOException("Run paths cannot use symbolic links");
        }
        if (Build.VERSION.SDK_INT >= 26 && Nio.containsPathAlias(new File(expected))) {
            throw new IOException("Run paths cannot use directory aliases");
        }
    }

    @androidx.annotation.RequiresApi(26)
    private static final class Nio {
        private Nio() { }

        static boolean containsPathAlias(File expected) throws IOException {
            java.nio.file.Path cursor = expected.toPath().toAbsolutePath().normalize();
            while (cursor != null && !java.nio.file.Files.exists(cursor, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                cursor = cursor.getParent();
            }
            if (cursor == null) throw new IOException("Run path has no existing ancestor");
            java.nio.file.Path logical = cursor.toAbsolutePath().normalize();
            java.nio.file.Path resolved = cursor.toRealPath();
            return !resolved.equals(logical);
        }
    }

    /** Keep steps.jsonl until the rest is removed, so a partial cleanup retains verifiable ownership. */
    private boolean deleteRunTree(File file) {
        try {
            verifyRunPath(file);
            if (file.getAbsoluteFile().equals(root.getAbsoluteFile())) return false;
            if (!file.exists()) return true;
            boolean complete = true;
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children == null) return false;
                File ownership = null;
                for (File child : children) {
                    if ("steps.jsonl".equals(child.getName())) ownership = child;
                    else complete &= deleteRunTree(child);
                }
                if (complete && ownership != null) complete = deleteRunTree(ownership);
            }
            return complete && file.delete();
        } catch (IOException | RuntimeException failure) { return false; }
    }

    /** Persist terminal presentation before publishing it to the live timeline; never used as reflection input. */
    public void appendConversationToolPresentation(String sessionId, String userMessageId,
            String displayName, String stage, String callId, String detail, String previewId, String auditDetail) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        if (!("tool_result".equals(stage) || "tool_error".equals(stage)))
            throw new IllegalArgumentException("A terminal tool presentation is required");
        if (callId == null || callId.isEmpty()) throw new IllegalArgumentException("Tool presentation needs its call ID");
        try {
            MainChatTranscriptStore retained = new MainChatTranscriptStore(filesDirectory(), sessionId, this);
            JSONObject row = new JSONObject().put("type", "tool_presentation").put("schemaVersion", 1)
                    .put("userMessageId", userMessageId == null ? "" : userMessageId)
                    .put("toolName", displayName == null ? "tool" : displayName).put("stage", stage)
                    .put("callId", callId).put("detail", retained.retain(detail == null ? "" : detail).text)
                    .put("auditDetail", MainChatTranscriptStore.shortText(
                            CrewCheckpointStore.sanitizeText(auditDetail == null ? "" : auditDetail), 4_000))
                    .put("timestamp", System.currentTimeMillis() / 1000.0);
            if (previewId != null) {
                row.put("previewExpected", true);
                if (verifiedPreview(sessionId, previewId) != null) row.put("previewId", previewId);
            }
            requireChatDbSuccess(chatDatabaseBridge.appendToolPresentationSync(
                    new com.jarvys.agent.chatdb.ToolPresentationInput(sessionId,
                            emptyToNull(userMessageId), null, callId,
                            displayName == null ? "tool" : displayName, stage, row.optString("detail"),
                            row.optString("auditDetail"), previewId != null, row.optString("previewId", ""),
                            row.optDouble("timestamp"))), "append tool presentation");
        } catch (Exception failure) { throw new IllegalStateException("Could not persist tool details and preview", failure); }
    }

    public void appendToolActivity(String sessionId, String userMessageId, ToolActivity activity) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        try {
            JSONObject value = activity.toJson();
            value.put("detail", new MainChatTranscriptStore(filesDirectory(),sessionId,this).retain(activity.detail).text);
            value.put("auditDetail", MainChatTranscriptStore.shortText(CrewCheckpointStore.sanitizeText(activity.auditDetail),4000));
            com.jarvys.agent.chatdb.ToolActivityInput input = new com.jarvys.agent.chatdb.ToolActivityInput(
                    sessionId, activity.eventId, emptyToNull(userMessageId), activity.executionId,
                    emptyToNull(activity.callId), activity.toolName, activity.displayName, emptyToNull(activity.skillId),
                    emptyToNull(activity.skillName), activity.stage, value.optString("detail"),
                    emptyToNull(activity.previewId), value.optString("auditDetail"),
                    emptyToNull(activity.reflectionSource), activity.timestampMillis, activity.timestampMillis / 1000.0,
                    null);
            requireChatDbSuccess(chatDatabaseBridge.appendToolActivitySync(input), "append tool activity");
        } catch(Exception failure) { throw new IllegalStateException("Could not persist tool activity",failure); }
    }

    /** Resolves activity ownership newest-first without materializing or parsing the full timeline. */
    /** Only a snapshot this conversation owns is a preview; a workspace id has no viewer to open. */
    private String verifiedPreview(String sessionId, String recordedId) {
        return verifiedPreview(sessionId, recordedId, null);
    }

    private String verifiedPreview(String sessionId, String recordedId, Map<String, ChatAttachment> ownedFiles) {
        if (!HtmlPreviewDescriptor.isSnapshotToken(recordedId)) return null;
        try {
            DeliveredArtifactStore artifacts = new DeliveredArtifactStore(filesDirectory);
            String id = recordedId.substring(HtmlPreviewDescriptor.TOKEN_PREFIX.length());
            ChatAttachment owner = ownedFiles == null ? findChatFile(sessionId, "delivered", id) : ownedFiles.get(id);
            HtmlPreviewDescriptor preview = owner == null ? null : artifacts.previewMetadataForAttachment(sessionId, owner);
            return preview != null && recordedId.equals(preview.token) ? recordedId : null;
        } catch (Exception unavailable) { return null; }
    }

    private static boolean isPreviewTool(String name) {
        return "preview_workspace".equals(name) || "Preview Workspace".equalsIgnoreCase(name);
    }

    private static boolean isExplicitPreviewCall(JSONObject batch, String callId) {
        JSONArray calls = batch == null ? null : batch.optJSONArray("calls");
        if (calls == null) return false;
        for (int index = 0; index < calls.length(); index++) {
            JSONObject call = calls.optJSONObject(index);
            if (call == null || !callId.equals(call.optString("id")) || !isPreviewTool(call.optString("name"))) continue;
            JSONObject arguments = call.optJSONObject("arguments");
            return arguments != null && arguments.has("path");
        }
        return false;
    }

    /** A tool result may be durable before its UI presentation. Tokens still require owned manifests. */
    private static String modelPreviewToken(String toolName, String output) {
        if (!(isPreviewTool(toolName) || "deliver_file".equals(toolName) || "Deliver File".equalsIgnoreCase(toolName))
                || output == null || output.length() > 16_384) return "";
        try {
            JSONObject result = new JSONObject(output);
            String token = result.optString("preview_id", "");
            return result.optBoolean("attached", false) && result.optBoolean("preview_available", false)
                    && HtmlPreviewDescriptor.isSnapshotToken(token) ? token : "";
        } catch (Exception incomplete) { return ""; }
    }

    /** Persists only a trust marker; connector/MCP/workspace result bodies never enter reflection storage. */
    public void appendReflectionToolEvent(String sessionId, String userMessageId,
                                                       String toolName, String source, String stage, String callId) {
        String normalizedSource = source == null ? "tool" : source.toLowerCase(Locale.ROOT);
        JSONObject row = new JSONObject();
        try {
            row.put("type", "reflection_tool");
            row.put("userMessageId", userMessageId == null ? "" : userMessageId);
            row.put("toolName", toolName == null ? "tool" : toolName);
            row.put("callId", callId == null ? "" : callId);
            row.put("source", normalizedSource);
            row.put("stage", stage == null ? "tool_result" : stage);
            row.put("marker", normalizedSource.startsWith("connector")
                    ? "[connector result omitted as sensitive, untrusted data]"
                    : normalizedSource.equals("mcp")
                        ? "[MCP result omitted as untrusted external data]"
                        : "[tool result omitted; only user-confirmed facts may be reflected]");
            row.put("timestamp", System.currentTimeMillis() / 1000.0);
        } catch (Exception error) { throw new IllegalStateException("Could not create reflection tool marker", error); }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        requireChatDbSuccess(chatDatabaseBridge.appendReflectionToolSync(
                new com.jarvys.agent.chatdb.ReflectionToolInput(sessionId, emptyToNull(userMessageId), null,
                        emptyToNull(callId), row.optString("toolName"), row.optString("source"),
                        row.optString("stage"), row.optString("marker"), row.optDouble("timestamp"))),
                "append reflection tool marker");
    }

    public static final class RegenerationPrompt {
        public final String text;
        public final String userMessageId;
        RegenerationPrompt(String text, String userMessageId) {
            this.text = text;
            this.userMessageId = userMessageId;
        }
    }

    public RegenerationPrompt findUserMessageBeforeAssistant(String sessionId, String messageId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        List<JSONObject> messages = readConversationMessages(sessionId);
        int assistantIndex = -1;
        for (int index = 0; index < messages.size(); index++) {
            JSONObject row = messages.get(index);
            if ("assistant".equals(row.optString("role")) && messageId.equals(row.optString("messageId"))) {
                assistantIndex = index;
                break;
            }
        }
        if (assistantIndex < 0) return null;
        for (int index = assistantIndex - 1; index >= 0; index--) {
            JSONObject row = messages.get(index);
            if ("user".equals(row.optString("role"))) {
                return new RegenerationPrompt(row.optString("content", ""), row.optString("messageId", ""));
            }
        }
        return null;
    }

    /** Returns the owner recorded on an assistant row, which is the same owner copied to its tombstone. */
    public String assistantOwnerUserMessageId(String sessionId, String messageId) {
        if (messageId == null || messageId.isEmpty()) return "";
        if (chatDatabaseBridge == null) throw noChatDatabase();
        for (JSONObject row : readConversationMessages(sessionId)) {
            if ("assistant".equals(row.optString("role"))
                    && messageId.equals(row.optString("messageId")))
                return row.optString("userMessageId", "");
        }
        return "";
    }

    public boolean isLatestActiveAssistant(String sessionId, String messageId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        List<JSONObject> active = readActiveConversationMessages(sessionId);
        for (int index = active.size() - 1; index >= 0; index--) {
            JSONObject row = active.get(index);
            if ("assistant".equals(row.optString("role")))
                return messageId != null && messageId.equals(row.optString("messageId", ""));
        }
        return false;
    }

    /** Append-only reaction state; original message rows, indices and compaction boundaries stay intact. */
    public boolean setMessageReaction(String sessionId, String messageId, String emoji) {
        if (!MessageReactionTool.isOrdinaryChat(sessionId) || emoji == null
                || !emoji.isEmpty() && !MessageReactionEmoji.isValid(emoji))
            throw new IllegalArgumentException("Invalid chat reaction");
        if (chatDatabaseBridge == null) throw noChatDatabase();
        ReactionUpdate update = requireChatDbSuccess(
                chatDatabaseBridge.setReactionSync(sessionId, messageId, emoji), "store message reaction");
        return update.getChanged();
    }

    public List<JSONObject> readConversationMessages(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        List<JSONObject> messages = new ArrayList<>();
        for (StoredMessage message : chatDatabaseBridge.readConversationMessagesSync(sessionId))
            messages.add(messageJson(message));
        return messages;
    }

    /** Loads model context as the latest summary plus the raw tail after its persisted boundary. */
    /** Durable, inert input. It is not model history or permission until explicitly dispatched. */
    public synchronized String appendInterruptRequest(String sessionId, String text,
            List<String> skills) {
        if (!MessageReactionTool.isOrdinaryChat(sessionId) || text == null || text.trim().isEmpty()
                || text.length() > 32000) throw new IllegalArgumentException("Invalid replacement message");
        if (chatDatabaseBridge == null) throw noChatDatabase();
        InterruptRequestValue request = requireChatDbSuccess(chatDatabaseBridge.appendInterruptRequestSync(
                sessionId, text, skills == null ? Collections.emptyList() : skills),
                "append replacement request");
        return request.getRequestId();
    }

    /** Atomically admits a steering receipt and inserts its ordered user row before provider dispatch. */
    public synchronized String appendAndDispatchInterruptRequest(String sessionId, String text,
            List<String> skills) {
        if (!MessageReactionTool.isOrdinaryChat(sessionId) || text == null || text.trim().isEmpty()
                || text.length() > 32000) throw new IllegalArgumentException("Invalid replacement message");
        if (chatDatabaseBridge == null) throw noChatDatabase();
        DispatchedInterrupt dispatched = requireChatDbSuccess(chatDatabaseBridge.appendAndDispatchInterruptRequestSync(
                sessionId, text, skills == null ? Collections.emptyList() : skills),
                "append and dispatch replacement request");
        return dispatched.getRequest().getRequestId();
    }

    /** Called only after the old worker has unwound. Duplicate IDs never replay a started turn. */
    JSONObject dispatchInterruptRequest(String sessionId, String requestId) {
        if (sessionId == null || requestId == null) return null;
        if (chatDatabaseBridge == null) throw noChatDatabase();
        ChatDbResult<DispatchedInterrupt> outcome = chatDatabaseBridge.dispatchInterruptRequestSync(sessionId, requestId);
        if (!(outcome instanceof ChatDbResult.Success)) return null;
        DispatchedInterrupt dispatched = ((ChatDbResult.Success<DispatchedInterrupt>) outcome).getValue();
        InterruptRequestValue request = dispatched.getRequest();
        try {
            return interruptRequestJson(request)
                    .put("messageId", dispatched.getMessage().getMessageId())
                    .put("alreadyDispatched", !dispatched.getInserted());
        } catch (Exception error) { throw new IllegalStateException("Could not project dispatched replacement", error); }
    }

    /** Durable acknowledgement that an existing user row entered a run transcript. */
    public List<String> markConversationMessagesIncorporated(String sessionId, Iterable<String> messageIds) {
        if (sessionId == null || messageIds == null) return Collections.emptyList();
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.markInputStateSync(
                sessionId, copyIds(messageIds), "incorporated"), "acknowledge incorporated chat inputs");
    }

    /** Resolves durable chat inputs that will not enter a run transcript. */
    public List<String> markConversationMessagesResolved(String sessionId, Iterable<String> messageIds) {
        if (sessionId == null || messageIds == null) return Collections.emptyList();
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return requireChatDbSuccess(chatDatabaseBridge.markInputStateSync(
                sessionId, copyIds(messageIds), "resolved"), "resolve chat inputs");
    }

    private static List<String> nonEmptyIds(Iterable<String> values) {
        List<String> ids = new ArrayList<>();
        for (String id : values) if (id != null && !id.isEmpty()) ids.add(id);
        return ids;
    }

    /** Acknowledges user rows already present in the loaded context through its current owner. */
    public void markConversationMessagesIncorporatedThrough(String sessionId, String messageId) {
        if (sessionId == null || messageId == null || messageId.isEmpty()) return;
        if (chatDatabaseBridge == null) throw noChatDatabase();
        requireChatDbSuccess(chatDatabaseBridge.markIncorporatedThroughSync(sessionId, messageId),
                "acknowledge incorporated chat inputs");
    }

    Set<String> incorporatedConversationMessageIds(String sessionId) {
        if (sessionId == null) return Collections.emptySet();
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return inputStateMessageIds(sessionId, "incorporated", null);
    }

    Set<String> resolvedConversationMessageIds(String sessionId) {
        if (sessionId == null) return Collections.emptySet();
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return inputStateMessageIds(sessionId, "resolved", null);
    }

    /** Returns only resolved/incorporated state for the supplied recent message identities. */
    Set<String> handledConversationMessageIds(String sessionId, Iterable<String> messageIds) {
        if (sessionId == null || messageIds == null) return Collections.emptySet();
        if (chatDatabaseBridge == null) throw noChatDatabase();
        Set<String> targets = new HashSet<>(nonEmptyIds(messageIds));
        return inputStateMessageIds(sessionId, null, targets);
    }

    private Set<String> inputStateMessageIds(String sessionId, String wantedState, Set<String> requestedIds) {
        return chatDatabaseBridge.inputStateMessageIdsSync(sessionId, wantedState,
                requestedIds == null ? null : new ArrayList<>(requestedIds));
    }

    /** Persists an intermediate assistant turn at its logical point before the steering row already on disk. */
    public synchronized String appendAssistantMessageBefore(String sessionId, String content,
            String runId, String userMessageId, String beforeMessageId) {
        if (beforeMessageId == null || beforeMessageId.isEmpty())
            throw new IllegalArgumentException("An intermediate assistant turn needs its following user message");
        if (chatDatabaseBridge == null) throw noChatDatabase();
        StoredMessage saved = requireChatDbSuccess(chatDatabaseBridge.appendMessageSync(new AppendMessageInput(
                sessionId, "assistant", Collections.singletonList(new MessagePartInput("text", content == null ? "" : content)),
                "COMPLETED", emptyToNull(runId), emptyToNull(userMessageId), null, beforeMessageId,
                null, null, null, null, false, Collections.emptyList(), Collections.emptyList(),
                System.currentTimeMillis() / 1000.0, conversationKindFor(sessionId))),
                "append assistant message before steering input");
        return saved.getMessageId();
    }

    /** Unincorporated durable follow-ups, in ledger order, for service recovery after a worker exit. */
    public List<JSONObject> readPendingInterruptRequests(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        List<JSONObject> pending = new ArrayList<>();
        for (InterruptRequestValue value : chatDatabaseBridge.pendingInterruptRequestsSync(sessionId))
            pending.add(interruptRequestJson(value));
        return Collections.unmodifiableList(pending);
    }

    /** All undispatched follow-ups, in stable session and ledger order, for service startup recovery. */
    public synchronized List<JSONObject> readPendingInterruptRequests() {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        List<JSONObject> pending = new ArrayList<>();
        for (InterruptRequestValue value : chatDatabaseBridge.pendingInterruptRequestsSync(null))
            pending.add(interruptRequestJson(value));
        return Collections.unmodifiableList(pending);
    }

    public synchronized JSONObject readInterruptRequest(String sessionId, String requestId) {
        if (sessionId == null || requestId == null) return null;
        if (chatDatabaseBridge == null) throw noChatDatabase();
        for (JSONObject value : readPendingInterruptRequests(sessionId))
            if (requestId.equals(value.optString("requestId"))) return value;
        return null;
    }

    /** UI projection only: the text already exists once in the model's tool-call protocol. */
    public AgentRunUiEvent readAssistantProgress(String sessionId, String firstCallId) {
        if (!MessageReactionTool.isOrdinaryChat(sessionId)) return null;
        if (firstCallId == null || firstCallId.isEmpty()) return null;
        if (chatDatabaseBridge == null) throw noChatDatabase();
        for (JSONObject row : chatDatabaseBridge.readModelTranscriptRowsSync(sessionId)) {
            JSONArray calls = row.optJSONArray("calls");
            if ("model_tool_calls".equals(row.optString("type")) && calls != null
                    && calls.length() > 0 && calls.optJSONObject(0) != null
                    && firstCallId.equals(calls.optJSONObject(0).optString("id")))
                return assistantProgressEvent(row, 0L);
        }
        return null;
    }

    private static AgentRunUiEvent assistantProgressEvent(JSONObject row, long id) {
        String content = row.optString("assistantText", "").trim();
        JSONArray calls = row.optJSONArray("calls");
        if (!row.optBoolean("assistantProgress", false) || content.isEmpty() || calls == null
                || calls.length() == 0 || calls.optJSONObject(0) == null) return null;
        String scope = row.optString("batchId", "");
        if (scope.isEmpty()) scope = row.optString("userMessageId", "");
        String key = "progress-" + scope + "/" + calls.optJSONObject(0).optString("id");
        return AgentRunUiEvent.messageEvent(id, "assistant", WebSearchCitationMarkup.resolve(content),
                (long) (row.optDouble("timestamp", 0) * 1000))
                .copyMetadata(key, 0L).copyStage("PROGRESS")
                .copyToolTimelineIdentity("progress:" + scope + "/" + calls.optJSONObject(0).optString("id"),
                        row.optString("userMessageId", ""));
    }

    public List<ConversationTurn> loadConversationContext(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.loadConversationContextSync(sessionId);
    }

    public int latestUserMessageIndex(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return Math.toIntExact(chatDatabaseBridge.latestUserMessageIndexSync(sessionId));
    }

    /** Returns the persisted model-order index for the exact message, regardless of input role. */
    public long conversationMessageIndex(String sessionId, String messageId) {
        if (sessionId == null || sessionId.isEmpty() || messageId == null || messageId.isEmpty()) return -1L;
        if (chatDatabaseBridge == null) throw noChatDatabase();
        StoredMessage message = chatDatabaseBridge.readMessageSync(sessionId, messageId);
        return message == null ? -1L : message.getMessageIndex();
    }

    public String latestUserMessageId(String sessionId) {
        if (sessionId == null) return "";
        if (chatDatabaseBridge == null) throw noChatDatabase();
        String id = chatDatabaseBridge.latestUserMessageIdSync(sessionId);
        return id == null ? "" : id;
    }

    File filesDirectory() { return filesDirectory; }

    JSONObject readConversationMessageRow(String sessionId, String messageId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        StoredMessage message = chatDatabaseBridge.readMessageSync(sessionId, messageId);
        return message == null ? null : messageJson(message);
    }

    JSONObject readConversationMessageAtIndex(String sessionId, int index) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        StoredMessage message = chatDatabaseBridge.readMessageAtIndexSync(sessionId, index);
        return message == null ? null : messageJson(message);
    }

    synchronized List<JSONObject> readModelArtifactRows(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.readModelArtifactRowsSync(sessionId);
    }

    synchronized List<JSONObject> readModelTranscriptRows(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.readModelTranscriptRowsSync(sessionId);
    }

    synchronized void appendModelTranscriptRow(String sessionId, JSONObject row) {
        String type = row.optString("type");
        if (!("model_tool_calls".equals(type) || "model_tool_started".equals(type)
                || "model_tool_not_started".equals(type)
                || "model_tool_result".equals(type) || "model_artifact".equals(type)
                || "model_error".equals(type) || "model_provider_output_items".equals(type)) || row.has("role"))
            throw new IllegalArgumentException("Invalid model transcript record");
        if (chatDatabaseBridge == null) throw noChatDatabase();
        appendModelTranscriptRowToDatabase(sessionId, row, type);
    }

    /** Persists one checkpoint and acknowledges its inputs in the same Room transaction. */
    synchronized void appendModelTranscriptCheckpoint(String sessionId, List<JSONObject> rows,
            List<String> incorporatedMessageIds) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        List<com.jarvys.agent.chatdb.ModelTranscriptWrite> writes = new ArrayList<>();
        for (JSONObject row : rows) writes.add(modelTranscriptWrite(sessionId, row));
        List<String> messageIds = incorporatedMessageIds == null
                ? Collections.emptyList() : new ArrayList<>(incorporatedMessageIds);
        String inputState = messageIds.isEmpty() ? null : "incorporated";
        requireChatDbSuccess(chatDatabaseBridge.appendModelTurnSync(
                new com.jarvys.agent.chatdb.ConversationModelTurnInput(
                        sessionId, writes, messageIds, inputState)),
                "persist model checkpoint and input acknowledgements");
    }

    private static com.jarvys.agent.chatdb.ModelTranscriptWrite modelTranscriptWrite(
            String sessionId, JSONObject row) {
        String type = row.optString("type");
        double time = row.optDouble("timestamp", System.currentTimeMillis() / 1000.0);
        switch (type) {
            case "model_tool_calls": {
                JSONArray callsJson = row.optJSONArray("calls");
                if (callsJson == null) throw new IllegalArgumentException("Tool batch requires calls");
                List<com.jarvys.agent.chatdb.ModelCallInput> calls = new ArrayList<>();
                for (int index = 0; index < callsJson.length(); index++) {
                    JSONObject call = callsJson.optJSONObject(index);
                    if (call == null) throw new IllegalArgumentException("Malformed tool call");
                    JSONObject arguments = call.optJSONObject("arguments");
                    if (arguments == null) arguments = new JSONObject();
                    calls.add(new com.jarvys.agent.chatdb.ModelCallInput(
                            call.optString("id"), call.optString("name"), arguments.toString(),
                            artifactIds(call.optJSONArray("artifactIds"))));
                }
                String provider = row.optString("providerOutputItems", "");
                if (provider.isEmpty()) provider = null;
                return new com.jarvys.agent.chatdb.ModelTranscriptBatchWrite(
                        new com.jarvys.agent.chatdb.ModelToolBatchInput(
                                sessionId, row.optString("batchId"), emptyToNull(row.optString("userMessageId")),
                                row.optLong("messageIndex"), row.optString("assistantText", ""),
                                row.optBoolean("assistantProgress", true), calls, null, null, provider, time,
                                artifactIds(row.optJSONArray("assistantTextArtifactIds"))));
            }
            case "model_tool_started":
            case "model_tool_not_started":
                return new com.jarvys.agent.chatdb.ModelTranscriptLifecycleWrite(
                        new com.jarvys.agent.chatdb.ToolCallLifecycleWrite(row.optString("batchId"),
                                row.optString("callId"), "model_tool_started".equals(type) ? "STARTED" : "NEVER_LAUNCHED"));
            case "model_tool_result":
                return new com.jarvys.agent.chatdb.ModelTranscriptResultWrite(
                        new com.jarvys.agent.chatdb.ModelToolResultInput(
                                sessionId, row.optString("batchId"), row.optString("callId"), row.optString("toolName"),
                                row.optString("lifecycle", "RESULT"), row.optString("output", ""),
                                emptyToNull(row.optString("artifactId")), time, artifactIds(row.optJSONArray("artifactIds"))));
            case "model_error":
                return new com.jarvys.agent.chatdb.ModelTranscriptErrorWrite(
                        new com.jarvys.agent.chatdb.ModelErrorWrite(emptyToNull(row.optString("userMessageId")),
                                row.optLong("messageIndex"), row.optString("errorCode")));
            case "model_provider_output_items":
                return new com.jarvys.agent.chatdb.ModelTranscriptProviderOutputWrite(
                        new com.jarvys.agent.chatdb.ProviderOutputItemsWrite(row.optString("userMessageId"),
                                row.optLong("messageIndex"), row.opt("items") instanceof JSONArray
                                        ? row.optJSONArray("items").toString() : row.optString("items")));
            case "model_artifact": {
                JSONObject artifact = row.optJSONObject("artifact");
                if (artifact == null) throw new IllegalArgumentException("Model artifact metadata is required");
                return new com.jarvys.agent.chatdb.ModelTranscriptArtifactWrite(
                        new com.jarvys.agent.chatdb.ModelArtifactWrite(row.optString("artifactId"),
                                artifact.optString("path"), artifact.optString("sha256"),
                                artifact.optLong("chars"), artifact.optLong("bytes"),
                                artifact.optString("mimeType", "text/plain"),
                                artifact.optString("state", com.jarvys.agent.chatdb.ModelArtifactState.RETAINED),
                                emptyToNull(artifact.optString("stateReason", ""))));
            }
            default: throw new IllegalArgumentException("Invalid model transcript record: " + type);
        }
    }

    private void appendModelTranscriptRowToDatabase(String sessionId, JSONObject row, String type) {
        double time = row.optDouble("timestamp", System.currentTimeMillis() / 1000.0);
        switch (type) {
            case "model_tool_calls": {
                JSONArray callsJson = row.optJSONArray("calls");
                if (callsJson == null) throw new IllegalArgumentException("Tool batch requires calls");
                List<com.jarvys.agent.chatdb.ModelCallInput> calls = new ArrayList<>();
                for (int index = 0; index < callsJson.length(); index++) {
                    JSONObject call = callsJson.optJSONObject(index);
                    if (call == null) throw new IllegalArgumentException("Malformed tool call");
                    JSONObject arguments = call.optJSONObject("arguments");
                    if (arguments == null) arguments = new JSONObject();
                    calls.add(new com.jarvys.agent.chatdb.ModelCallInput(
                            call.optString("id"), call.optString("name"), arguments.toString(),
                            artifactIds(call.optJSONArray("artifactIds"))));
                }
                String provider = row.optString("providerOutputItems", "");
                if (provider.isEmpty()) provider = null;
                com.jarvys.agent.chatdb.ModelToolBatchInput batch = new com.jarvys.agent.chatdb.ModelToolBatchInput(
                        sessionId, row.optString("batchId"), emptyToNull(row.optString("userMessageId")),
                        row.optLong("messageIndex"), row.optString("assistantText", ""),
                        row.optBoolean("assistantProgress", true), calls, null, null, provider, time,
                        artifactIds(row.optJSONArray("assistantTextArtifactIds")));
                requireChatDbSuccess(chatDatabaseBridge.appendToolBatchSync(batch), "append model tool batch");
                return;
            }
            case "model_tool_started":
            case "model_tool_not_started":
                requireChatDbSuccess(chatDatabaseBridge.setCallLifecycleSync(sessionId, row.optString("batchId"),
                        row.optString("callId"), "model_tool_started".equals(type) ? "STARTED" : "NEVER_LAUNCHED"),
                        "store tool call lifecycle");
                return;
            case "model_tool_result": {
                com.jarvys.agent.chatdb.ModelToolResultInput result = new com.jarvys.agent.chatdb.ModelToolResultInput(
                        sessionId, row.optString("batchId"), row.optString("callId"), row.optString("toolName"),
                        row.optString("lifecycle", "RESULT"), row.optString("output", ""),
                        emptyToNull(row.optString("artifactId")), time, artifactIds(row.optJSONArray("artifactIds")));
                requireChatDbSuccess(chatDatabaseBridge.appendToolResultSync(result), "append model tool result");
                return;
            }
            case "model_error":
                requireChatDbSuccess(chatDatabaseBridge.appendModelErrorSync(sessionId,
                        emptyToNull(row.optString("userMessageId")), row.optLong("messageIndex"),
                        row.optString("errorCode")), "append model error");
                return;
            case "model_provider_output_items":
                requireChatDbSuccess(chatDatabaseBridge.appendProviderOutputItemsSync(sessionId,
                        row.optString("userMessageId"), row.optLong("messageIndex"), row.optString("items")),
                        "append provider output items");
                return;
            case "model_artifact": {
                JSONObject artifact = row.optJSONObject("artifact");
                if (artifact == null) throw new IllegalArgumentException("Model artifact metadata is required");
                requireChatDbSuccess(chatDatabaseBridge.appendModelArtifactSync(sessionId,
                        row.optString("artifactId"), artifact.optString("path"), artifact.optString("sha256"),
                        artifact.optLong("chars"), artifact.optLong("bytes"), artifact.optString("mimeType", "text/plain"),
                        artifact.optString("state", com.jarvys.agent.chatdb.ModelArtifactState.RETAINED),
                        emptyToNull(artifact.optString("stateReason", ""))),
                        "append model artifact");
                return;
            }
            default: throw new IllegalArgumentException("Unsupported model transcript record");
        }
    }

    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }

    private static List<String> artifactIds(JSONArray ids) {
        if (ids == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (int index = 0; index < ids.length(); index++) {
            String id = ids.optString(index, "");
            if (!id.isEmpty()) result.add(id);
        }
        return result;
    }

    public synchronized List<JSONObject> readActiveConversationMessages(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        List<JSONObject> active = new ArrayList<>();
        for (StoredMessage message : chatDatabaseBridge.readActiveConversationMessagesSync(sessionId))
            active.add(messageJson(message));
        return active;
    }

    /** Durable steering inputs appended after the user message that started the active run. */
    public List<JSONObject> readUserMessagesAfter(String sessionId, String messageId) {
        if (sessionId == null || messageId == null || messageId.isEmpty()) return Collections.emptyList();
        if (chatDatabaseBridge == null) throw noChatDatabase();
        List<JSONObject> later = new ArrayList<>();
        StoredMessage cursor = chatDatabaseBridge.readMessageSync(sessionId, messageId);
        if (cursor == null) return Collections.emptyList();
        for (StoredMessage message : chatDatabaseBridge.readConversationMessagesSync(sessionId))
            if (message.getSeq() > cursor.getSeq() && "user".equals(message.getRole()))
                later.add(messageJson(message));
        return Collections.unmodifiableList(later);
    }

    /** All user and internal conversation inputs after a durable cursor, in ledger order. */
    public List<JSONObject> readConversationInputsAfter(String sessionId, String messageId) {
        if (sessionId == null || messageId == null || messageId.isEmpty()) return Collections.emptyList();
        if (chatDatabaseBridge == null) throw noChatDatabase();
        StoredMessage cursor = chatDatabaseBridge.readMessageSync(sessionId, messageId);
        if (cursor == null) return Collections.emptyList();
        List<JSONObject> later = new ArrayList<>();
        for (StoredMessage message : chatDatabaseBridge.readInputsAfterSync(sessionId, cursor.getSeq()))
            if (message.getSeq() > cursor.getSeq() && isConversationInputRole(message.getRole()))
                later.add(messageJson(message));
        return Collections.unmodifiableList(later);
    }

        /** Pending agent messages start turns; SYSTEM context never starts an automatic reply. */
    public List<JSONObject> readPendingInternalConversationInputs(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        List<JSONObject> pending = new ArrayList<>();
        for (StoredMessage message : chatDatabaseBridge.readPendingAgentInputsSync(sessionId))
            pending.add(messageJson(message));
        return Collections.unmodifiableList(pending);
    }

    /** Reads pending internal rows whose typed provenance names one of the supplied source IDs. */
    public List<JSONObject> readPendingInternalConversationInputsBySourceIds(
            String sessionId, Iterable<String> sourceMessageIds) {
        if (sessionId == null || sourceMessageIds == null) return Collections.emptyList();
        if (chatDatabaseBridge == null) throw noChatDatabase();
        Set<String> sourceIds = new LinkedHashSet<>(nonEmptyIds(sourceMessageIds));
        if (sourceIds.isEmpty()) return Collections.emptyList();

        Set<String> stableIds = new LinkedHashSet<>();
        ConversationInput.Origin[] origins = {
                ConversationInput.Origin.AGENT, ConversationInput.Origin.SYSTEM };
        for (String sourceId : sourceIds) {
            for (ConversationInput.Origin origin : origins) {
                stableIds.add(ConversationInput.stableId(origin, WorkspaceIdentity.CENTRAL_SESSION, sourceId));
                stableIds.add(ConversationInput.stableId(origin, sessionId, sourceId));
            }
        }

        List<JSONObject> pending = new ArrayList<>();
        for (StoredMessage message : chatDatabaseBridge.readPendingInternalMessagesByStableIdsSync(
                sessionId, new ArrayList<>(stableIds))) {
            if (!"agent".equals(message.getRole()) && !"system".equals(message.getRole())) continue;
            JSONObject row = messageJson(message);
            JSONObject provenance = row.optJSONObject("provenance");
            if (provenance != null && sourceIds.contains(provenance.optString("messageId"))) pending.add(row);
        }
        return Collections.unmodifiableList(pending);
    }

    /** One pending-state query for the workspace sidebar; no transcript bodies are hydrated. */
    public Map<String, Integer> countPendingAgentInputsByWorkspace(Map<String, String> conversationWorkspaceIds) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.countPendingAgentInputsByWorkspaceSync(conversationWorkspaceIds);
    }

    private static boolean isConversationInputRole(String role) {
        return "user".equals(role) || "agent".equals(role) || "system".equals(role);
    }

    public synchronized ReflectionPayload buildReflectionPayload(String sessionId) {
        return buildReflectionPayload(sessionId, null, false);
    }

    /** Snapshot an automatic post-turn payload through its completed assistant message, excluding later turns. */
    // Message-id checkpoint and bounded unreflected slice follow reflection-transcript.ts:30-37,1644-1677.
    public synchronized ReflectionPayload buildReflectionPayload(String sessionId, String throughMessageId) {
        return buildReflectionPayload(sessionId, throughMessageId, false);
    }

    public synchronized ReflectionPayload buildReflectionPayload(String sessionId, String throughMessageId,
                                                                  boolean replayAll) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        com.jarvys.agent.chatdb.ReflectionPayloadSnapshot value =
                chatDatabaseBridge.buildReflectionPayloadSync(sessionId, emptyToNull(throughMessageId), replayAll);
        return new ReflectionPayload(value.getText(), value.getEndMessageId(), value.getCompactionIds(),
                value.getCompletedAssistantSteps(), value.getUserCharacters(), value.getMessageCount(),
                value.getHasMoreMessages());
    }

    public synchronized List<String> listConversationSessionIds() {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        List<String> ids = new ArrayList<>();
        for (ConversationSummary summary : chatDatabaseBridge.listAllConversationsSync(Collections.emptySet()))
            if (!summary.getDeleted() && !chatDatabaseBridge.isTombstonedSync(summary.getId())) ids.add(summary.getId());
        return ids;
    }

    /** Internal delivery recovery cannot use the UI conversation projection, which omits agent-only sessions. */
    public synchronized List<String> listConversationsWithPendingAgentInputs() {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.listConversationsWithPendingAgentInputsSync();
    }

    public synchronized void appendReflectionCheckpoint(String sessionId, ReflectionPayload payload,
                                                        String reflectionId, String result) {
        if (payload == null || payload.endMessageId.isEmpty()) throw new IllegalArgumentException("Reflection checkpoint has no message boundary");
        if (chatDatabaseBridge == null) throw noChatDatabase();
        requireChatDbSuccess(chatDatabaseBridge.appendReflectionCheckpointSync(
                new com.jarvys.agent.chatdb.ReflectionInput(sessionId, "reflection_checkpoint", reflectionId,
                        payload.endMessageId, (long) payload.completedAssistantSteps, result, null, null, null,
                        Collections.emptyList(), payload.compactionIds, System.currentTimeMillis() / 1000.0)),
                "append reflection checkpoint");
    }

    /** One append-only record commits a reflection checkpoint, learned-summary row, and group identity together. */
    public synchronized void appendReflectionCommit(String sessionId, ReflectionPayload payload, String reflectionId,
                                                    String summary, List<Long> revisionIds, String status, String trigger) {
        if (payload == null || payload.endMessageId.isEmpty()) throw new IllegalArgumentException("Reflection checkpoint has no message boundary");
        if (chatDatabaseBridge == null) throw noChatDatabase();
        requireChatDbSuccess(chatDatabaseBridge.appendReflectionCommitSync(
                new com.jarvys.agent.chatdb.ReflectionInput(sessionId, "reflection_commit", reflectionId,
                        payload.endMessageId, (long) payload.completedAssistantSteps, null,
                        summary == null ? "" : summary, status == null ? "completed" : status,
                        trigger == null ? "manual" : trigger,
                        revisionIds == null ? Collections.emptyList() : revisionIds,
                        payload.compactionIds, System.currentTimeMillis() / 1000.0)), "append reflection commit");
    }

    public synchronized void advanceReflectionCheckpoint(String sessionId, String reason) {
        advanceReflectionCheckpoint(sessionId, reason, null);
    }

    public synchronized void advanceReflectionCheckpoint(String sessionId, String reason, String throughMessageId) {
        ReflectionPayload payload = buildReflectionPayload(sessionId, throughMessageId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        if (payload.endMessageId.isEmpty()) {
            if (!payload.compactionIds.isEmpty()) appendCompactionReflectionAcknowledgement(sessionId, payload.compactionIds);
            return;
        }
        appendReflectionCheckpoint(sessionId, payload, "skipped", reason);
    }

    public synchronized void appendReflectionEvent(String sessionId, String reflectionId, String summary,
                                                   List<Long> revisionIds, String status) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        requireChatDbSuccess(chatDatabaseBridge.appendReflectionEventSync(
                new com.jarvys.agent.chatdb.ReflectionInput(sessionId, "reflection_event", reflectionId,
                        null, null, null, summary == null ? "" : summary,
                        status == null ? "completed" : status, null,
                        revisionIds == null ? Collections.emptyList() : revisionIds,
                        Collections.emptyList(), System.currentTimeMillis() / 1000.0)), "append reflection event");
    }

    public synchronized void appendReflectionUndoEvent(String sessionId, String reflectionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        requireChatDbSuccess(chatDatabaseBridge.appendReflectionUndoSync(sessionId, reflectionId,
                System.currentTimeMillis() / 1000.0), "append reflection undo");
    }

    public synchronized boolean hasReflectionCommit(String sessionId, String reflectionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.hasReflectionCommitSync(sessionId, reflectionId);
    }

    public synchronized List<String> pendingCompactionIds(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.pendingCompactionIdsSync(sessionId);
    }

    /** A materialized UI window and the byte cursor for the next older turn window. */
    public static final class ConversationTimelinePage {
        public final List<AgentRunUiEvent> events;
        public final long olderCursor;
        public final boolean hasOlder;
        public final Set<String> hiddenAssistantMessageIds;
        public final Set<String> hiddenTranslationMessageIds;
        public final Set<String> translationMessageIds;
        public final Set<String> invalidatedToolTurnMessageIds;
        /** State from this and all newer loaded pages, usable while projecting older pages. */
        public final TimelineProjectionMarkers projectionMarkers;

        private ConversationTimelinePage(List<AgentRunUiEvent> events, long olderCursor, boolean hasOlder,
                                         Set<String> hiddenAssistantMessageIds,
                                         Set<String> hiddenTranslationMessageIds,
                                         Set<String> translationMessageIds,
                                         Set<String> invalidatedToolTurnMessageIds,
                                         TimelineProjectionMarkers projectionMarkers) {
            this.events = Collections.unmodifiableList(new ArrayList<>(events));
            this.olderCursor = olderCursor;
            this.hasOlder = hasOlder;
            this.hiddenAssistantMessageIds = Collections.unmodifiableSet(new HashSet<>(hiddenAssistantMessageIds));
            this.hiddenTranslationMessageIds = Collections.unmodifiableSet(new HashSet<>(hiddenTranslationMessageIds));
            this.translationMessageIds = Collections.unmodifiableSet(new HashSet<>(translationMessageIds));
            this.invalidatedToolTurnMessageIds = Collections.unmodifiableSet(new HashSet<>(invalidatedToolTurnMessageIds));
            this.projectionMarkers = projectionMarkers;
        }
    }

    /**
     * Cross-page state that can change how rows in an older window should appear. Merge an
     * older page with {@link #withOlderPage(TimelineProjectionMarkers)}; values already known
     * by this instance win because they came from a newer window.
     */
    public static final class TimelineProjectionMarkers {
        public static final class DecisionResolution {
            public final String status;
            public final String optionId;
            public final String optionLabel;

            public DecisionResolution(String status, String optionId, String optionLabel) {
                this.status = status == null ? "UNANSWERED" : status;
                this.optionId = optionId;
                this.optionLabel = optionLabel;
            }
        }

        public final Map<String, DecisionResolution> resolvedDecisions;
        public final Set<String> consumedProactiveReplyMessageIds;
        public final Map<String, String> reactionsByMessageId;
        public final Map<String, CrewMissionSnapshot> latestCrewSnapshots;

        public TimelineProjectionMarkers() {
            this(Collections.emptyMap(), Collections.emptySet(), Collections.emptyMap(), Collections.emptyMap());
        }

        private TimelineProjectionMarkers(Map<String, DecisionResolution> resolvedDecisions,
                Set<String> consumedProactiveReplyMessageIds, Map<String, String> reactionsByMessageId,
                Map<String, CrewMissionSnapshot> latestCrewSnapshots) {
            this.resolvedDecisions = Collections.unmodifiableMap(new LinkedHashMap<>(resolvedDecisions));
            this.consumedProactiveReplyMessageIds = Collections.unmodifiableSet(
                    new HashSet<>(consumedProactiveReplyMessageIds));
            this.reactionsByMessageId = Collections.unmodifiableMap(new LinkedHashMap<>(reactionsByMessageId));
            this.latestCrewSnapshots = Collections.unmodifiableMap(new LinkedHashMap<>(latestCrewSnapshots));
        }

        /** Returns a combined marker set where this newer page wins over the supplied older page. */
        public TimelineProjectionMarkers withOlderPage(TimelineProjectionMarkers older) {
            if (older == null) return this;
            Map<String, DecisionResolution> decisions = new LinkedHashMap<>(older.resolvedDecisions);
            decisions.putAll(resolvedDecisions);
            Set<String> consumed = new HashSet<>(older.consumedProactiveReplyMessageIds);
            consumed.addAll(consumedProactiveReplyMessageIds);
            Map<String, String> reactions = new LinkedHashMap<>(older.reactionsByMessageId);
            reactions.putAll(reactionsByMessageId);
            Map<String, CrewMissionSnapshot> snapshots = new LinkedHashMap<>(older.latestCrewSnapshots);
            snapshots.putAll(latestCrewSnapshots);
            return new TimelineProjectionMarkers(decisions, consumed, reactions, snapshots);
        }

    }

    public static final int INITIAL_TIMELINE_TURNS = 40;
    public static final int OLDER_TIMELINE_TURNS = 20;

    /** UI timeline is projected from Room events and paged by stable sequence IDs. */
    public List<AgentRunUiEvent> readConversationTimeline(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.readConversationTimelineSync(
                sessionId, Math.max(INITIAL_TIMELINE_TURNS, OLDER_TIMELINE_TURNS));
    }

    public ConversationTimelinePage readConversationTimelinePage(
            String sessionId, long beforeOffset, int turnCount) {
        return readConversationTimelinePage(sessionId, beforeOffset, turnCount, Collections.emptySet());
    }

    public ConversationTimelinePage readConversationTimelinePage(
            String sessionId, long beforeOffset, int turnCount, Set<String> newerInvalidatedToolTurnMessageIds) {
        return readConversationTimelinePage(sessionId, beforeOffset, turnCount,
                newerInvalidatedToolTurnMessageIds, new TimelineProjectionMarkers());
    }

    public ConversationTimelinePage readConversationTimelinePage(
            String sessionId, long beforeOffset, int turnCount,
            Set<String> newerInvalidatedToolTurnMessageIds, TimelineProjectionMarkers newerProjectionMarkers) {
        if (turnCount < 1 || turnCount > 200) throw new IllegalArgumentException("Timeline page size must be 1..200");
        if (chatDatabaseBridge == null) throw noChatDatabase();
        ChatTimelineEventPage dbPage = beforeOffset == NO_TIMELINE_CURSOR
                ? chatDatabaseBridge.readRecentConversationTimelineSync(sessionId, turnCount)
                : chatDatabaseBridge.readConversationTimelineBeforeSync(sessionId, beforeOffset, turnCount);
        Set<String> invalidated = new HashSet<>(dbPage.getInvalidatedToolTurnMessageIds());
        if (newerInvalidatedToolTurnMessageIds != null) invalidated.addAll(newerInvalidatedToolTurnMessageIds);
        TimelineProjectionMarkers markers = newerProjectionMarkers == null
                ? new TimelineProjectionMarkers() : newerProjectionMarkers;
        Set<String> translations = new HashSet<>();
        for (AgentRunUiEvent event : dbPage.getEvents()) {
            if ("assistant_translation".equals(event.getKind()) && event.getDetail() != null)
                translations.add(event.getDetail());
        }
        Long cursor = dbPage.getOlderCursorSeq();
        return new ConversationTimelinePage(dbPage.getEvents(), cursor == null ? NO_TIMELINE_CURSOR : cursor,
                cursor != null, Collections.emptySet(), Collections.emptySet(), translations,
                invalidated, markers);
    }

    /** Recent persisted UI events for run-service refreshes; the model transcript reader is separate. */
    public List<AgentRunUiEvent> readRecentConversationTimeline(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.readRecentConversationTimelineSync(sessionId, INITIAL_TIMELINE_TURNS).getEvents();
    }

    public int conversationMessageCount(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return (int) chatDatabaseBridge.messageCountsSync(sessionId).getTotal();
    }

    /** True while at least one compaction id has not been acknowledged by a reflection checkpoint. */
    public synchronized boolean hasPendingCompactionReflection(String sessionId) {
        return !pendingCompactionIds(sessionId).isEmpty();
    }

    public synchronized void acknowledgePendingCompactionReflection(String sessionId) {
        appendCompactionReflectionAcknowledgement(sessionId, pendingCompactionIds(sessionId));
    }

    private void appendCompactionReflectionAcknowledgement(String sessionId, List<String> compactionIds) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        if (compactionIds == null || compactionIds.isEmpty()) return;
        requireChatDbSuccess(chatDatabaseBridge.acknowledgeCompactionsSync(sessionId,
                new ArrayList<>(compactionIds), false), "acknowledge compaction reflection");
    }

    public synchronized void appendCompaction(String sessionId, String summary, int firstKept,
                                               String trigger, String mode, int summarizedMessages) {
        appendCompaction(sessionId, summary, firstKept, trigger, mode, summarizedMessages, Collections.emptyList());
    }

    /** [sourceArtifactIds] hold the summarized source text; they become the compaction owners of the retention closure. */
    public synchronized void appendCompaction(String sessionId, String summary, int firstKept,
                                               String trigger, String mode, int summarizedMessages,
                                               List<String> sourceArtifactIds) {
        if (summary == null || summary.trim().isEmpty()) throw new IllegalArgumentException("Compaction summary is empty");
        if (chatDatabaseBridge == null) throw noChatDatabase();
        requireChatDbSuccess(chatDatabaseBridge.appendCompactionSync(
                new com.jarvys.agent.chatdb.CompactionInput(sessionId, summary, Math.max(0, firstKept),
                        trigger == null ? "manual" : trigger, mode == null ? "all" : mode,
                        Math.max(0, summarizedMessages), System.currentTimeMillis() / 1000.0,
                        sourceArtifactIds == null ? Collections.emptyList() : new ArrayList<>(sourceArtifactIds))),
                "append compaction");
    }

    /** Free-space signal used by the artifact retention policy; a store without a context never reports pressure. */
    StoragePressure storagePressure() { return storagePressure; }

    void setStoragePressureForTests(StoragePressure pressure) { this.storagePressure = pressure; }

    /** The oldest retained artifact outside the protected closure of any episode, or null when none may be expelled. */
    synchronized JSONObject nextExpellableArtifact(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        com.jarvys.agent.chatdb.ExpellableArtifact artifact = chatDatabaseBridge.nextExpellableArtifactSync(sessionId);
        if (artifact == null) return null;
        try {
            return new JSONObject().put("artifactId", artifact.getArtifactId()).put("path", artifact.getPath())
                    .put("bytes", artifact.getBytes());
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    synchronized JSONObject readToolResultSource(String sessionId, String callId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.readToolResultSourceSync(sessionId, callId);
    }

    synchronized boolean expelArtifact(String sessionId, String artifactId, String reason) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return chatDatabaseBridge.expelArtifactSync(sessionId, artifactId, reason);
    }

    synchronized void setStorageWarning(boolean active) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        chatDatabaseBridge.setStorageWarningSync(active);
    }

    public String readFirstUserMessage(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        for (JSONObject row : readConversationMessages(sessionId))
            if ("user".equals(row.optString("role"))) return row.optString("content", "");
        return null;
    }

    public String readConversationTitle(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        ConversationMetadataValue metadata = chatDatabaseBridge.readConversationMetadataSync(sessionId);
        if (metadata == null || metadata.getDeleted()) return null;
        return metadata.getTitle() != null ? metadata.getTitle() : metadata.getGeneratedTitle();
    }

    public boolean claimConversationTitleGeneration(String sessionId) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        if (!isConversationAvailable(sessionId) || readConversationTitle(sessionId) != null) return false;
        synchronized (SESSION_TITLE_LOCK) {
            return SESSION_TITLES_IN_PROGRESS.add(sessionId);
        }
    }

    public boolean appendConversationTitleIfAbsent(String sessionId, String title) {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        String normalized = title == null ? "" : ConversationMetadataAccess.normalizeTitle(title);
        if (normalized.isEmpty()) return false;
        return requireChatDbSuccess(chatDatabaseBridge.appendConversationTitleIfAbsentSync(sessionId, normalized),
                "store generated conversation title");
    }

    public void releaseConversationTitleGeneration(String sessionId) {
        synchronized (SESSION_TITLE_LOCK) {
            SESSION_TITLES_IN_PROGRESS.remove(sessionId);
        }
    }

    public synchronized String readConversationContext(String sessionId) {
        List<JSONObject> messages = readActiveConversationMessages(sessionId);
        StringBuilder context = new StringBuilder();
        int first = Math.max(0, messages.size() - 16);
        for (int index = first; index < messages.size(); index++) {
            JSONObject message = messages.get(index);
            String content = message.optString("content", "");
            if (content.length() > 3000) content = content.substring(0, 3000) + "…";
            if (context.length() + content.length() > 16_000) break;
            context.append(message.optString("role").toUpperCase(Locale.ROOT)).append(": ")
                    .append(content).append('\n');
        }
        return context.toString();
    }

    public synchronized void beginStep(StepRecord step) {
        if (activeRunDirectory == null) throw new IllegalStateException("No active local run");
        String prefix = String.format(Locale.ROOT, "step_%04d", step.number);
        File beforeFile = writeImage(prefix + "_pre.jpg", step.before);
        JSONObject record = new JSONObject();
        try {
            record.put("event", "step_start");
            record.put("run_id", activeRunId);
            record.put("step", step.number);
            record.put("timestamp", step.startedAtMillis / 1000.0);
            record.put("pre_screenshot", beforeFile.getName());
            record.put("post_screenshot", JSONObject.NULL);
            record.put("xml", step.before.uiHierarchyXml == null ? "" : step.before.uiHierarchyXml);
            record.put("width", step.before.width);
            record.put("height", step.before.height);
            record.put("pre_width", step.before.width);
            record.put("pre_height", step.before.height);
            record.put("decisions", new JSONArray(step.decisions));
            appendJson(record);
        } catch (Exception e) {
            throw new IllegalStateException("Could not persist execution_check for step " + step.number, e);
        }
    }

    public synchronized void finishStep(StepRecord step) {
        if (activeRunDirectory == null) throw new IllegalStateException("No active local run");
        String prefix = String.format(Locale.ROOT, "step_%04d", step.number);
        File afterFile = step.after == null ? null : writeImage(prefix + "_post.jpg", step.after);
        JSONObject record = new JSONObject();
        try {
            record.put("event", "step_result");
            record.put("run_id", activeRunId);
            record.put("step", step.number);
            record.put("post_screenshot", afterFile == null ? JSONObject.NULL : afterFile.getName());
            record.put("post_width", step.after == null ? 0 : step.after.width);
            record.put("post_height", step.after == null ? 0 : step.after.height);
            record.put("success", step.success);
            record.put("result", step.result == null ? "" : step.result);
            appendJson(record);
        } catch (Exception e) {
            throw new IllegalStateException("Could not persist Validator result for step " + step.number, e);
        }
    }

    public synchronized void recordSummary(String runId, int stepNumber, String summary) {
        synchronized (SESSION_TITLE_LOCK) {
            File runDirectory = runDirectories.get(runId);
            if (runDirectory == null || runWasDeleted(runId)) return;
            JSONObject record = new JSONObject();
            try {
                record.put("event", "step_summary");
                record.put("run_id", runId);
                record.put("step", stepNumber);
                record.put("summary", summary);
                record.put("timestamp", System.currentTimeMillis() / 1000.0);
                File ledger = new File(runDirectory, "steps.jsonl");
                verifyRunPath(ledger);
                try (FileWriter writer = new FileWriter(ledger, true)) {
                    writer.write(record.toString());
                    writer.write('\n');
                    writer.flush();
                }
            } catch (Exception error) { throw new IllegalStateException("Could not persist asynchronous step summary", error); }
        }
    }

    public synchronized void finishRun(AgentState state, String outcome) {
        if (activeRunDirectory == null) return;
        JSONObject record = new JSONObject();
        try {
            record.put("event", "run_end");
            record.put("run_id", activeRunId);
            record.put("outcome", outcome);
            record.put("turns", state.turn);
            record.put("steps", state.steps.size());
            record.put("timestamp", System.currentTimeMillis() / 1000.0);
            appendJson(record);
        } catch (Exception e) {
            throw new IllegalStateException("Could not persist run outcome", e);
        } finally {
            activeRunId = null;
            activeRunDirectory = null;
        }
    }

    public synchronized void saveNote(String key, String content) {
        File file = noteFile(key);
        writeText(file, content == null ? "" : content, false);
    }

    public synchronized void appendNote(String key, String content) {
        File file = noteFile(key);
        writeText(file, content == null ? "" : content, true);
    }

    public synchronized String readNote(String key) {
        return readNote(key, 0, 0);
    }

    public synchronized String readNote(String key, int startLine, int endLine) {
        File file = noteFile(key);
        if (!file.isFile()) return "Note not found: " + key;
        String content = readText(file);
        if (startLine <= 0 && endLine <= 0) return content;
        String[] lines = content.split("\\n", -1);
        int first = Math.max(1, startLine <= 0 ? 1 : startLine);
        int last = Math.min(lines.length, endLine <= 0 ? lines.length : endLine);
        if (first > last) return "";
        StringBuilder selected = new StringBuilder();
        for (int i = first; i <= last; i++) selected.append(lines[i - 1]).append('\n');
        return selected.toString();
    }

    public synchronized String updateNote(String key, String target, String replacement) {
        if (target == null || target.isEmpty()) throw new IllegalArgumentException("target must not be empty");
        File file = noteFile(key);
        String current = file.isFile() ? readText(file) : "";
        int at = current.indexOf(target);
        if (at < 0) return "Target text not found; note was not changed.";
        String updated = current.substring(0, at) + (replacement == null ? "" : replacement)
                + current.substring(at + target.length());
        writeText(file, updated, false);
        return "Updated note " + key;
    }

    public synchronized List<String> listNotes() {
        List<String> keys = new ArrayList<>();
        File[] files = notes.listFiles((dir, name) -> name.endsWith(".md"));
        if (files != null) {
            for (File file : files) keys.add(file.getName().substring(0, file.getName().length() - 3));
        }
        java.util.Collections.sort(keys);
        return keys;
    }

    public synchronized List<JSONObject> readAllSteps() {
        synchronized (SESSION_TITLE_LOCK) {
        Map<String, JSONObject> combined = new LinkedHashMap<>();
        File[] runs = root.listFiles(File::isDirectory);
        if (runs == null) return new ArrayList<>();
        java.util.Arrays.sort(runs, java.util.Comparator.comparing(File::getName));
        for (File run : runs) {
            File ledger = new File(run, "steps.jsonl");
            if (!ledger.isFile()) continue;
            try (BufferedReader reader = new BufferedReader(new FileReader(ledger))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JSONObject row;
                    try {
                        row = new JSONObject(line);
                    } catch (Exception incompleteRecord) {
                        // Another LocalRunStore instance may be appending a row at the same time.
                        continue;
                    }
                    String event = row.optString("event");
                    if (!"step_start".equals(event) && !"step_result".equals(event)
                            && !"step_summary".equals(event)) continue;
                    String id = row.optString("run_id") + ":" + row.optInt("step");
                    JSONObject merged = combined.get(id);
                    if (merged == null && "step_start".equals(event)) {
                        merged = new JSONObject(row.toString());
                        merged.put("event", "step");
                        combined.put(id, merged);
                    } else if (merged != null) {
                        if ("step_summary".equals(event)) merged.put("summary", row.optString("summary"));
                        else if ("step_result".equals(event)) {
                            merged.put("post_screenshot", row.opt("post_screenshot"));
                            merged.put("post_width", row.optInt("post_width", 0));
                            merged.put("post_height", row.optInt("post_height", 0));
                            merged.put("success", row.optBoolean("success", false));
                            merged.put("result", row.optString("result", ""));
                        }
                    }
                }
            } catch (Exception unsafeOrUnreadable) { }

        }
        return new ArrayList<>(combined.values());
        }
    }

    /** Recent run headers for the Compose navigation drawer; run ledgers remain append-only. */
    /** Recent run rows preserve the legacy all-conversations view, including archive metadata. */
    public synchronized List<JSONObject> listRecentRuns(int limit) {
        List<JSONObject> result = collectConversationHistory();
        result.sort(LocalRunStore::comparePinnedHistory);
        int bounded = Math.max(0, Math.min(limit, 100));
        return result.size() > bounded ? new ArrayList<>(result.subList(0, bounded)) : result;
    }

    public synchronized List<JSONObject> listRecentRuns(int limit, boolean includeArchived) {
        int bounded = Math.max(0, Math.min(limit, 100));
        List<JSONObject> result = collectConversationHistory();
        if (!includeArchived) result.removeIf(row -> row.optBoolean("archived"));
        result.sort(LocalRunStore::comparePinnedHistory);
        return result.size() > bounded ? new ArrayList<>(result.subList(0, bounded)) : result;
    }

    public synchronized List<JSONObject> listConversations() {
        Map<String, JSONObject> sessions = new LinkedHashMap<>();
        for (JSONObject row : collectConversationHistory()) sessions.putIfAbsent(row.optString("session_id"), row);
        List<JSONObject> result = new ArrayList<>(sessions.values());
        result.sort(LocalRunStore::comparePinnedHistory);
        return result;
    }

    private static int comparePinnedHistory(JSONObject left, JSONObject right) {
        int pinned = Boolean.compare(right.optBoolean("pinned"), left.optBoolean("pinned"));
        return pinned != 0 ? pinned : Double.compare(right.optDouble("timestamp"), left.optDouble("timestamp"));
    }

    private List<JSONObject> collectConversationHistory() {
        if (chatDatabaseBridge == null) throw noChatDatabase();
        List<JSONObject> history = new ArrayList<>();
        Map<String, JSONObject> bySession = new LinkedHashMap<>();
        Map<String, ConversationSummary> liveConversations = new LinkedHashMap<>();
        for (ConversationSummary summary : chatDatabaseBridge.listAllConversationsSync(Collections.emptySet())) {
            if (!summary.getDeleted() && !chatDatabaseBridge.isTombstonedSync(summary.getId()))
                liveConversations.put(summary.getId(), summary);
        }

        // Run execution history remains in its existing, non-conversation steps.jsonl files.
        File[] runs = root.listFiles(File::isDirectory);
        if (runs != null) java.util.Arrays.sort(runs, (left, right) -> right.getName().compareTo(left.getName()));
        if (runs != null) for (File run : runs) {
            try {
                String sessionId = conversationSessionForRunDirectory(run);
                if (sessionId == null || !liveConversations.containsKey(sessionId)) continue;
                File ledger = new File(run, "steps.jsonl");
                JSONObject summary = null;
                try (BufferedReader reader = new BufferedReader(new FileReader(ledger))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        try {
                            JSONObject row = new JSONObject(line);
                            String event = row.optString("event");
                            if ("run_start".equals(event) && summary == null) {
                                summary = new JSONObject().put("run_id", row.optString("run_id", run.getName()))
                                        .put("session_id", sessionId).put("goal", row.optString("goal", ""))
                                        .put("timestamp", row.optDouble("timestamp", 0)).put("outcome", "RUNNING")
                                        .put("steps", 0).put("turns", 0);
                            } else if (summary != null && "step_start".equals(event)) {
                                summary.put("steps", summary.optInt("steps") + 1);
                            } else if (summary != null && "run_end".equals(event)) {
                                summary.put("outcome", row.optString("outcome", "UNKNOWN"));
                                summary.put("turns", row.optInt("turns", 0));
                            }
                        } catch (Exception incomplete) { }
                    }
                }
                if (summary != null) {
                    bySession.putIfAbsent(sessionId, summary);
                    history.add(summary);
                }
            } catch (Exception unsafeOrUnreadable) { }
        }

        for (ConversationSummary summary : liveConversations.values()) {
            try {
                Long lastMessageAt = summary.getLastMessageAtMillis();
                long timestampMillis = lastMessageAt == null ? summary.getUpdatedAtMillis() : lastMessageAt;
                JSONObject row = bySession.get(summary.getId());
                if (row == null) {
                    row = new JSONObject().put("run_id", "chat_" + summary.getId())
                            .put("session_id", summary.getId()).put("outcome", "CHAT").put("steps", 0);
                    history.add(row);
                    bySession.put(summary.getId(), row);
                }
                String runGoal = row.optString("goal", "");
                if (runGoal.isEmpty() && summary.getGoal() != null) runGoal = summary.getGoal();
                row.put("goal", runGoal)
                        .put("timestamp", timestampMillis / 1000.0)
                        .put("turns", Math.max(row.optLong("turns"), summary.getTurnCount()))
                        .put("pinned", summary.getPinned()).put("archived", summary.getArchived());
                if (summary.getTitle() != null) row.put("title", summary.getTitle());
                if (summary.getPreview() != null) row.put("preview", summary.getPreview());
            } catch (Exception error) {
                throw new IllegalStateException("Could not project conversation history", error);
            }
        }
        history.sort(LocalRunStore::comparePinnedHistory);
        return history;
    }

    public synchronized String searchHistory(String query, int startStep, int endStep, int maxResults) {
        List<JSONObject> rows = readAllSteps();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (JSONObject row : rows) {
            int number = row.optInt("step");
            if ((startStep > 0 && number < startStep) || (endStep > 0 && number > endStep)) continue;
            String text = row.toString();
            if (needle.isEmpty() || text.toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add("step " + number + ": " + row.optString("result", "")
                        + " decisions=" + row.optString("decisions", "[]"));
                if (matches.size() >= maxResults) break;
            }
        }
        return matches.isEmpty() ? "No stored steps matched the search." : join(matches, "\n");
    }

    public synchronized String replaySteps(int startStep, int endStep) {
        int first = Math.min(startStep, endStep);
        int last = Math.max(startStep, endStep);
        List<String> matches = new ArrayList<>();
        for (JSONObject row : readAllSteps()) {
            int number = row.optInt("step");
            if (number >= first && number <= last) matches.add(row.toString());
        }
        return matches.isEmpty() ? "No stored steps in that range." : join(matches, "\n");
    }

    public synchronized String readScreenshot(int stepNumber, String which) {
        if ("overlay".equals(which)) {
            throw new UnsupportedOperationException("get_step_screenshot(which=overlay) is not implemented: Stage C stores raw pre/post frames but not action-annotated overlays");
        }
        if (!"pre".equals(which) && !"post".equals(which)) {
            throw new IllegalArgumentException("which must be pre, post, or overlay");
        }
        List<JSONObject> rows = readAllSteps();
        String preferredRun = activeRunId;
        for (int i = rows.size() - 1; i >= 0; i--) {
            JSONObject row = rows.get(i);
            if (row.optInt("step") != stepNumber) continue;
            if (preferredRun != null && !preferredRun.equals(row.optString("run_id"))) continue;
            return readScreenshotFromRow(row, which, stepNumber);
        }
        throw new IllegalArgumentException("History step not found: " + stepNumber);
    }

    public synchronized Map<String, Object> readScreenshotImage(int stepNumber, String which) {
        String base64 = readScreenshot(stepNumber, which);
        List<JSONObject> rows = readAllSteps();
        String preferredRun = activeRunId;
        for (int i = rows.size() - 1; i >= 0; i--) {
            JSONObject row = rows.get(i);
            if (row.optInt("step") != stepNumber) continue;
            if (preferredRun != null && !preferredRun.equals(row.optString("run_id"))) continue;
            boolean post = "post".equals(which);
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("mime_type", "image/jpeg");
            image.put("base64", base64);
            image.put("width", row.optInt(post ? "post_width" : "pre_width", row.optInt("width", 0)));
            image.put("height", row.optInt(post ? "post_height" : "pre_height", row.optInt("height", 0)));
            image.put("label", "step " + stepNumber + " " + which + " screenshot");
            return image;
        }
        throw new IllegalArgumentException("History step not found: " + stepNumber);
    }

    private String readScreenshotFromRow(JSONObject row, String which, int stepNumber) {
        String key = "post".equals(which) ? "post_screenshot" : "pre_screenshot";
        String fileName = row.optString(key, "");
        if (fileName.isEmpty() || "null".equals(fileName)) throw new IllegalStateException("No " + which + " screenshot was recorded for step " + stepNumber);
        File run = new File(root, row.optString("run_id"));
        try {
            String sessionId = conversationSessionForRunDirectory(run);
            if (sessionId != null && isConversationDeleted(sessionId))
                throw new IllegalStateException("This chat has been deleted");
            synchronized (SESSION_TITLE_LOCK) {
                File screenshot = new File(run, fileName);
                verifyRunPath(screenshot);
                return Base64.encodeToString(readBytes(screenshot), Base64.NO_WRAP);
            }
        } catch (Exception error) { throw new IllegalStateException("Could not load screenshot for step " + stepNumber, error); }
    }

    private File writeImage(String name, ScreenData screen) {
        synchronized (SESSION_TITLE_LOCK) {
            if (runWasDeleted(activeRunId)) throw new IllegalStateException("This chat has been deleted");
            File output = new File(activeRunDirectory, name);
            try {
                verifyRunPath(output);
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    stream.write(screen.screenshotBytes);
                    stream.flush();
                    return output;
                }
            } catch (Exception error) { throw new IllegalStateException("Could not persist screenshot " + name, error); }
        }
    }

    private void appendJson(JSONObject record) {
        synchronized (SESSION_TITLE_LOCK) {
            if (runWasDeleted(activeRunId)) throw new IllegalStateException("This chat has been deleted");
            File ledger = new File(activeRunDirectory, "steps.jsonl");
            try {
                verifyRunPath(ledger);
                try (FileWriter writer = new FileWriter(ledger, true)) {
                    writer.write(record.toString());
                    writer.write('\n');
                    writer.flush();
                }
            } catch (Exception error) { throw new IllegalStateException("Could not append local run ledger", error); }
        }
    }

    private File noteFile(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_.-]{1,80}")) {
            throw new IllegalArgumentException("Note key must contain 1-80 letters, digits, dot, underscore or dash");
        }
        return new File(notes, key + ".md");
    }

    private void writeText(File file, String content, boolean append) {
        try (FileWriter writer = new FileWriter(file, append)) {
            writer.write(content);
            writer.flush();
        } catch (Exception e) {
            throw new IllegalStateException("Could not write local note: " + e.getMessage(), e);
        }
    }

    private String readText(File file) {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
            return output.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not read local note: " + e.getMessage(), e);
        }
    }

    private static byte[] readBytes(File file) throws Exception {
        try (java.io.FileInputStream input = new java.io.FileInputStream(file);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static void ensureDirectory(File directory) {
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Could not create app-private storage at " + directory);
        }
    }

    private static String join(List<String> lines, String separator) {
        StringBuilder output = new StringBuilder();
        for (String line : lines) {
            if (output.length() > 0) output.append(separator);
            output.append(line);
        }
        return output.toString();
    }
}
