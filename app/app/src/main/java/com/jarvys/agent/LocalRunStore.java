package com.jarvys.agent;

import android.content.Context;
import android.util.Base64;
import java.io.IOException;
import java.util.Collections;
import com.jarvys.agent.proactive.ProactiveConversation;
import com.jarvys.agent.tasks.ScheduledTaskConversation;

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
import com.jarvys.agent.chatdb.ChatDbResult;
import com.jarvys.agent.chatdb.ConversationMetadataValue;
import com.jarvys.agent.chatdb.ConversationImageReferenceValue;
import com.jarvys.agent.chatdb.ConversationSummary;
import com.jarvys.agent.chatdb.DispatchedInterrupt;
import com.jarvys.agent.chatdb.InterruptRequestValue;
import com.jarvys.agent.chatdb.MessageAttachmentInput;
import com.jarvys.agent.chatdb.MessagePartInput;
import com.jarvys.agent.chatdb.ReactionUpdate;
import com.jarvys.agent.chatdb.RegenerationPromptValue;
import com.jarvys.agent.chatdb.StoredMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** App-private append-only run records and notes for graph/history tools. */
public final class LocalRunStore {
    /** Sentinel used by keyset timeline pages when the caller wants the newest window. */
    public static final long NO_TIMELINE_CURSOR = -1L;
    private static final Object SESSION_TITLE_LOCK = new Object();
    private static final Set<String> SESSION_TITLES_IN_PROGRESS = new HashSet<>();
    private static final Map<String, String> CREW_SNAPSHOT_DIGESTS = new LinkedHashMap<>();
    private static final Object CREW_SNAPSHOT_CACHE_LOCK = new Object();
    private static final int MAX_CACHED_CREW_SNAPSHOT_FILES = 16;
    private static final Object TIMELINE_PAGE_CACHE_LOCK = new Object();
    private static final long MAX_TIMELINE_PAGE_CACHE_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_INDEXED_LEDGER_FILES = 16;
    private static final int MAX_CACHED_TOOL_PAIRS_PER_FILE = 128;
    private static final int MAX_CACHED_TOOL_ACTIVITY_LOOKUPS_PER_FILE = 128;
    private static long timelinePageCacheBytes;
    private static long timelinePagePagerCallCount;
    private static long conversationRowsFullParseCount;
    private static long crewSnapshotLedgerScanCount;
    private static long crewSnapshotLedgerScanBytes;
    private static long crewSnapshotRowsParsedCount;
    private static long crewSnapshotCacheHitCount;
    private static long toolActivityLedgerScanCount;
    private static long toolActivityLedgerScanBytes;
    private static long toolActivityScanUnderSessionLockCount;
    private static long userMessagesAfterScanCount;
    private static long userMessagesAfterScanBytes;
    private static long userMessagesAfterScanUnderSessionLockCount;
    private static volatile Runnable toolActivityScanTestHook;
    private static final LinkedHashMap<String, CachedTimelinePage> TIMELINE_PAGE_CACHE =
            new LinkedHashMap<>(16, 0.75f, true);
    private static final LinkedHashMap<String, CrewSnapshotCache> CREW_SNAPSHOT_CACHE =
            new LinkedHashMap<>(16, 0.75f, true);
    private static final LinkedHashMap<String, ToolBatchCache> TOOL_BATCH_CACHES =
            new LinkedHashMap<>(16, 0.75f, true);
    private static final LinkedHashMap<String, ToolActivityBatchCache> TOOL_ACTIVITY_BATCH_CACHES =
            new LinkedHashMap<>(16, 0.75f, true);
    private final File root;
    private final String runBoundary;
    private final ConversationMetadataStore conversationMetadata;
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
        File filesDirectory = context.getFilesDir();
        File jarvys = new File(filesDirectory, "jarvys");
        root = new File(jarvys, "runs");
        notes = new File(jarvys, "notes");
        conversationMetadata = new ConversationMetadataStore(filesDirectory);
        chatDatabaseBridge = ChatDatabaseBridge.getInstance(context);
        try {
            runBoundary = new File(new File(filesDirectory.getCanonicalFile(), "jarvys"), "runs").getPath();
            verifyRunPath(root);
        } catch (IOException error) { throw new IllegalStateException("Unsafe local run directory", error); }
        ensureDirectory(root);
        ensureDirectory(notes);
    }

    /** Non-production file fixture for run-store tests; conversation operations require a registered DB bridge. */
    LocalRunStore(File filesDirectory) {
        File jarvys = new File(filesDirectory, "jarvys");
        root = new File(jarvys, "runs");
        notes = new File(jarvys, "notes");
        conversationMetadata = new ConversationMetadataStore(filesDirectory);
        chatDatabaseBridge = ChatDatabaseBridge.findInstanceForFilesDirectory(filesDirectory);
        try {
            runBoundary = new File(new File(filesDirectory.getCanonicalFile(), "jarvys"), "runs").getPath();
            verifyRunPath(root);
        } catch (IOException error) { throw new IllegalStateException("Unsafe local run directory", error); }
        ensureDirectory(root);
        ensureDirectory(notes);
    }

    private boolean isConversationDeleted(String sessionId) {
        return chatDatabaseBridge != null
                ? chatDatabaseBridge.isTombstonedSync(sessionId)
                : conversationMetadata.read(sessionId).deleted;
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
        try { complete &= new DeliveredArtifactStore(root.getParentFile()).deleteSession(sessionId); }
        catch (RuntimeException failure) { complete = false; }
        try { complete &= new AttachmentStore(root.getParentFile()).deleteSession(sessionId); }
        catch (RuntimeException failure) { complete = false; }
        try {
            complete &= new WorkspaceStore(new File(root.getParentFile(), "workspaces"),
                    WorkspaceStore.projectIdForSession(sessionId)).deleteImportedAttachments();
        } catch (RuntimeException failure) { complete = false; }
        try { complete &= CrewContextArtifacts.deleteConversation(root.getParentFile().getParentFile(), sessionId); }
        catch (RuntimeException failure) { complete = false; }
        return complete;
    }

    private static String conversationKindFor(String sessionId) {
        if (ProactiveConversation.SESSION_ID.equals(sessionId)) return "proactive";
        if (ScheduledTaskConversation.SESSION_ID.equals(sessionId)) return "tasks";
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
                    .put("memoryDisabled", request.getMemoryDisabled())
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
            if (message.getInputState() != null) row.put("inputState", message.getInputState());
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
        synchronized (SESSION_TITLE_LOCK) {
            if (sessionId != null && !sessionId.trim().isEmpty() && isConversationDeleted(sessionId)) {
                throw new IllegalStateException("This chat has been deleted");
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            activeRunId = stamp + "_" + Long.toHexString(System.nanoTime());
            activeRunDirectory = new File(root, activeRunId);
            try { verifyRunPath(activeRunDirectory); }
            catch (IOException error) { throw new IllegalStateException("Unsafe local run directory", error); }
            ensureDirectory(activeRunDirectory);
            runDirectories.put(activeRunId, activeRunDirectory);
            runSessions.put(activeRunId, sessionId == null || sessionId.trim().isEmpty() ? activeRunId : sessionId);
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
        if (chatDatabaseBridge != null) {
            requireChatDbSuccess(chatDatabaseBridge.appendCrewSnapshotSync(
                    snapshot.conversationId, snapshot.toJson()), "append Crew snapshot");
            return;
        }
        JSONObject row = snapshot.toJson();
        String digest;
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(losslessResultRecord(row));
            StringBuilder encoded = new StringBuilder(hash.length * 2);
            for (byte value : hash) encoded.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            digest = encoded.toString();
        } catch (Exception error) { throw new IllegalStateException("Could not fingerprint Crew snapshot", error); }
        try { row.put("timestamp", System.currentTimeMillis() / 1000.0); }
        catch (Exception error) { throw new IllegalStateException("Could not create Crew ledger row", error); }
        String key = snapshot.conversationId + "\u0000" + snapshot.missionId;
        synchronized (SESSION_TITLE_LOCK) {
            if (digest.equals(CREW_SNAPSHOT_DIGESTS.get(key))) return;
            appendSessionRowLocked(conversationFile(snapshot.conversationId), row);
            CREW_SNAPSHOT_DIGESTS.put(key, digest);
        }
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
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.appendCrewResultEvidenceSync(
                evidence, beforeWrite::run), "append Crew result evidence");
        requireResultSource(evidence.conversationId);
        synchronized (SESSION_TITLE_LOCK) {
            CrewResultEvidence existing = readCrewResultEvidence(evidence.conversationId, evidence.evidenceId);
            if (existing != null) {
                if (!sameResultEvidence(existing, evidence)) resultConflict();
                return existing;
            }
            appendResultRowLocked(evidence.conversationId, evidence.toJson(), false, beforeWrite);
            return evidence;
        }
    }

    public CrewResultEvidence appendCrewResultEvidence(CrewResultEvidence evidence) {
        return appendCrewResultEvidence(evidence, () -> { });
    }

    /** Exact raw evidence retrieval; callers must page resultText before exposing it to a model/UI. */
    public synchronized CrewResultEvidence readCrewResultEvidence(String sourceSessionId, String evidenceId) {
        WorkspaceResultReference.requireEvidenceId(evidenceId);
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.readCrewResultEvidenceSync(
                sourceSessionId, evidenceId), "read Crew result evidence");
        requireResultSource(sourceSessionId);
        synchronized (SESSION_TITLE_LOCK) {
            final CrewResultEvidence[] found = {null};
            scanResultLedgerLocked(sourceSessionId, false, (evidence, reference, notice) -> {
                if (evidence != null && evidenceId.equals(evidence.evidenceId)) found[0] = evidence;
            });
            return found[0];
        }
    }

    /** Lexically paged evidence IDs only; never an unbounded list of worker result text. */
    public synchronized List<String> listCrewResultEvidence(String sourceSessionId, int limit, String afterEvidenceId) {
        resultPage(limit);
        if (afterEvidenceId != null) WorkspaceResultReference.requireEvidenceId(afterEvidenceId);
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.listCrewResultEvidenceIdsSync(
                sourceSessionId, limit, afterEvidenceId), "list Crew result evidence");
        requireResultSource(sourceSessionId);
        synchronized (SESSION_TITLE_LOCK) {
            java.util.TreeSet<String> found = new java.util.TreeSet<>();
            scanResultLedgerLocked(sourceSessionId, false, (evidence, reference, notice) -> {
                if (evidence != null && (afterEvidenceId == null || evidence.evidenceId.compareTo(afterEvidenceId) > 0)) {
                    found.add(evidence.evidenceId);
                    if (found.size() > limit) found.pollLast();
                }
            });
            return Collections.unmodifiableList(new ArrayList<>(found));
        }
    }

    public synchronized WorkspaceResultReference appendWorkspaceResultReferenceIfAbsent(
            WorkspaceResultReference reference, Runnable beforeWrite) {
        requireResultReference(reference, beforeWrite);
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.appendWorkspaceResultReferenceSync(
                reference, beforeWrite::run), "append workspace result reference");
        synchronized (SESSION_TITLE_LOCK) {
            CrewResultEvidence evidence = readCrewResultEvidence(reference.executionSessionId, reference.evidenceId);
            if (!reference.matchesEvidence(evidence)) throw new IllegalStateException("Reference has no matching durable evidence");
            WorkspaceResultReference existing = readWorkspaceResultReference(reference.executionSessionId, reference.referenceId);
            if (existing != null) {
                if (!existing.sameBinding(reference)) resultConflict();
                // A later registry revision is an observation, not a new result identity.
                return existing;
            }
            appendResultRowLocked(reference.executionSessionId, reference.toJson(), false, beforeWrite);
            return reference;
        }
    }

    public synchronized WorkspaceResultReference readWorkspaceResultReference(String sourceSessionId, String referenceId) {
        WorkspaceResultReference.requireReferenceId(referenceId);
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.readWorkspaceResultReferenceSync(
                sourceSessionId, referenceId, false), "read workspace result reference");
        requireResultSource(sourceSessionId);
        synchronized (SESSION_TITLE_LOCK) {
            return findResultReferenceLocked(sourceSessionId, referenceId, false);
        }
    }

    public synchronized List<WorkspaceResultReference> listWorkspaceResultReferences(
            String sourceSessionId, int limit, String afterReferenceId) {
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.listWorkspaceResultReferencesSync(
                sourceSessionId, false, limit, afterReferenceId), "list workspace result references");
        requireResultSource(sourceSessionId);
        synchronized (SESSION_TITLE_LOCK) {
            return listResultReferencesLocked(sourceSessionId, limit, afterReferenceId, false);
        }
    }

    /** Receipt existence proves local delivery only; no worker text, role, or approval is copied. */
    public synchronized WorkspaceResultReference appendWorkspaceResultNoticeIfAbsent(
            WorkspaceResultReference reference, Runnable beforeWrite) {
        requireResultReference(reference, beforeWrite);
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.appendWorkspaceResultNoticeSync(
                reference, beforeWrite::run), "append workspace result notice");
        synchronized (SESSION_TITLE_LOCK) {
            WorkspaceResultReference durable = readWorkspaceResultReference(reference.executionSessionId, reference.referenceId);
            if (durable == null || !durable.samePayload(reference))
                throw new IllegalStateException("Notice has no exact durable source reference");
            WorkspaceResultReference existing = findResultReferenceLocked(reference.ownerCentralSessionId, reference.referenceId, true);
            if (existing != null) {
                if (!existing.samePayload(reference)) resultConflict();
                return existing;
            }
            appendResultRowLocked(reference.ownerCentralSessionId, reference.toNoticeJson(), true, beforeWrite);
            return reference;
        }
    }

    public synchronized WorkspaceResultReference readWorkspaceResultNotice(String centralSessionId, String referenceId) {
        WorkspaceResultReference.requireReferenceId(referenceId);
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.readWorkspaceResultReferenceSync(
                centralSessionId, referenceId, true), "read workspace result notice");
        requireResultCentral(centralSessionId);
        synchronized (SESSION_TITLE_LOCK) { return findResultReferenceLocked(centralSessionId, referenceId, true); }
    }

    public synchronized List<WorkspaceResultReference> listWorkspaceResultNotices(
            String centralSessionId, int limit, String afterReferenceId) {
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.listWorkspaceResultReferencesSync(
                centralSessionId, true, limit, afterReferenceId), "list workspace result notices");
        requireResultCentral(centralSessionId);
        synchronized (SESSION_TITLE_LOCK) { return listResultReferencesLocked(centralSessionId, limit, afterReferenceId, true); }
    }

    private WorkspaceResultReference findResultReferenceLocked(String session, String id, boolean notices) {
        final WorkspaceResultReference[] found = {null};
        scanResultLedgerLocked(session, notices, (evidence, reference, notice) -> {
            if (reference != null && notice == notices && id.equals(reference.referenceId)) found[0] = reference;
        });
        return found[0];
    }

    private List<WorkspaceResultReference> listResultReferencesLocked(String session, int limit, String after, boolean notices) {
        resultPage(limit);
        if (after != null) WorkspaceResultReference.requireReferenceId(after);
        java.util.TreeMap<String, WorkspaceResultReference> found = new java.util.TreeMap<>();
        scanResultLedgerLocked(session, notices, (evidence, reference, notice) -> {
            if (reference != null && notice == notices && (after == null || reference.referenceId.compareTo(after) > 0)) {
                found.put(reference.referenceId, reference);
                if (found.size() > limit) found.pollLastEntry();
            }
        });
        return Collections.unmodifiableList(new ArrayList<>(found.values()));
    }

    public synchronized CrewCoordinationEvidence appendCrewCoordinationEvidence(CrewCoordinationEvidence evidence, Runnable beforeWrite) {
        if (evidence == null || beforeWrite == null) throw new IllegalArgumentException("Evidence and admission are required");
        CrewCoordinationEvidence.fromJsonStrict(evidence.toJson());
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.appendCrewCoordinationEvidenceSync(
                evidence, beforeWrite::run), "append Crew coordination evidence");
        requireResultSource(evidence.conversationId);
        synchronized (SESSION_TITLE_LOCK) {
            CrewCoordinationEvidence existing = readCrewCoordinationEvidence(evidence.conversationId, evidence.evidenceId);
            if (existing != null) {
                if (!sameCoordinationEvidence(existing, evidence)) resultConflict();
                return existing;
            }
            appendResultRowLocked(evidence.conversationId, evidence.toJson(), false, beforeWrite);
            return evidence;
        }
    }

    public CrewCoordinationEvidence appendCrewCoordinationEvidence(CrewCoordinationEvidence evidence) {
        return appendCrewCoordinationEvidence(evidence, () -> { });
    }

    /** Exact raw evidence retrieval; callers must page text before exposing it to a model/UI. */
    public synchronized CrewCoordinationEvidence readCrewCoordinationEvidence(String sourceSessionId, String evidenceId) {
        WorkspaceCoordinationReference.requireEvidenceId(evidenceId);
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.readCrewCoordinationEvidenceSync(
                sourceSessionId, evidenceId), "read Crew coordination evidence");
        requireResultSource(sourceSessionId);
        synchronized (SESSION_TITLE_LOCK) {
            final CrewCoordinationEvidence[] found = {null};
            scanCoordinationLedgerLocked(sourceSessionId, false, (evidence, reference, notice, reply, transition) -> {
                if (evidence != null && evidenceId.equals(evidence.evidenceId)) found[0] = evidence;
            });
            return found[0];
        }
    }

    /** Lexically paged evidence IDs only; never an unbounded list of worker result text. */
    public synchronized List<String> listCrewCoordinationEvidence(String sourceSessionId, int limit, String afterEvidenceId) {
        resultPage(limit);
        if (afterEvidenceId != null) WorkspaceCoordinationReference.requireEvidenceId(afterEvidenceId);
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.listCrewCoordinationEvidenceIdsSync(
                sourceSessionId, limit, afterEvidenceId), "list Crew coordination evidence");
        requireResultSource(sourceSessionId);
        synchronized (SESSION_TITLE_LOCK) {
            java.util.TreeSet<String> found = new java.util.TreeSet<>();
            scanCoordinationLedgerLocked(sourceSessionId, false, (evidence, reference, notice, reply, transition) -> {
                if (evidence != null && (afterEvidenceId == null || evidence.evidenceId.compareTo(afterEvidenceId) > 0)) {
                    found.add(evidence.evidenceId);
                    if (found.size() > limit) found.pollLast();
                }
            });
            return Collections.unmodifiableList(new ArrayList<>(found));
        }
    }

    public synchronized WorkspaceCoordinationReference appendWorkspaceCoordinationReferenceIfAbsent(
            WorkspaceCoordinationReference reference, Runnable beforeWrite) {
        requireCoordinationReference(reference, beforeWrite);
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.appendWorkspaceCoordinationReferenceSync(
                reference, beforeWrite::run), "append workspace coordination reference");
        synchronized (SESSION_TITLE_LOCK) {
            CrewCoordinationEvidence evidence = readCrewCoordinationEvidence(reference.executionSessionId, reference.evidenceId);
            if (!reference.matchesEvidence(evidence)) throw new IllegalStateException("Reference has no matching durable evidence");
            WorkspaceCoordinationReference existing = readWorkspaceCoordinationReference(reference.executionSessionId, reference.referenceId);
            if (existing != null) {
                if (!existing.sameBinding(reference)) resultConflict();
                // A later registry revision is an observation, not a new result identity.
                return existing;
            }
            appendResultRowLocked(reference.executionSessionId, reference.toJson(), false, beforeWrite);
            return reference;
        }
    }

    public synchronized WorkspaceCoordinationReference readWorkspaceCoordinationReference(String sourceSessionId, String referenceId) {
        WorkspaceCoordinationReference.requireReferenceId(referenceId);
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.readWorkspaceCoordinationReferenceSync(
                sourceSessionId, referenceId, false), "read workspace coordination reference");
        requireResultSource(sourceSessionId);
        synchronized (SESSION_TITLE_LOCK) {
            return findCoordinationReferenceLocked(sourceSessionId, referenceId, false);
        }
    }

    public synchronized List<WorkspaceCoordinationReference> listWorkspaceCoordinationReferences(
            String sourceSessionId, int limit, String afterReferenceId) {
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.listWorkspaceCoordinationReferencesSync(
                sourceSessionId, false, limit, afterReferenceId), "list workspace coordination references");
        requireResultSource(sourceSessionId);
        synchronized (SESSION_TITLE_LOCK) {
            return listCoordinationReferencesLocked(sourceSessionId, limit, afterReferenceId, false);
        }
    }

    /** Receipt existence proves local delivery only; no worker text, role, or approval is copied. */
    public synchronized WorkspaceCoordinationReference appendWorkspaceCoordinationNoticeIfAbsent(
            WorkspaceCoordinationReference reference, Runnable beforeWrite) {
        requireCoordinationReference(reference, beforeWrite);
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.appendWorkspaceCoordinationNoticeSync(
                reference, beforeWrite::run), "append workspace coordination notice");
        synchronized (SESSION_TITLE_LOCK) {
            WorkspaceCoordinationReference durable = readWorkspaceCoordinationReference(reference.executionSessionId, reference.referenceId);
            if (durable == null || !durable.samePayload(reference))
                throw new IllegalStateException("Notice has no exact durable source reference");
            WorkspaceCoordinationReference existing = findCoordinationReferenceLocked(reference.ownerCentralSessionId, reference.referenceId, true);
            if (existing != null) {
                if (!existing.samePayload(reference)) resultConflict();
                return existing;
            }
            appendResultRowLocked(reference.ownerCentralSessionId, reference.toNoticeJson(), true, beforeWrite);
            return reference;
        }
    }

    public synchronized WorkspaceCoordinationReference readWorkspaceCoordinationNotice(String centralSessionId, String referenceId) {
        WorkspaceCoordinationReference.requireReferenceId(referenceId);
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.readWorkspaceCoordinationReferenceSync(
                centralSessionId, referenceId, true), "read workspace coordination notice");
        requireResultCentral(centralSessionId);
        synchronized (SESSION_TITLE_LOCK) { return findCoordinationReferenceLocked(centralSessionId, referenceId, true); }
    }

    public synchronized List<WorkspaceCoordinationReference> listWorkspaceCoordinationNotices(
            String centralSessionId, int limit, String afterReferenceId) {
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.listWorkspaceCoordinationReferencesSync(
                centralSessionId, true, limit, afterReferenceId), "list workspace coordination notices");
        requireResultCentral(centralSessionId);
        synchronized (SESSION_TITLE_LOCK) { return listCoordinationReferencesLocked(centralSessionId, limit, afterReferenceId, true); }
    }

    private WorkspaceCoordinationReference findCoordinationReferenceLocked(String session, String id, boolean notices) {
        final WorkspaceCoordinationReference[] found = {null};
        scanCoordinationLedgerLocked(session, notices, (evidence, reference, notice, reply, transition) -> {
            if (reference != null && notice == notices && id.equals(reference.referenceId)) found[0] = reference;
        });
        return found[0];
    }

    private List<WorkspaceCoordinationReference> listCoordinationReferencesLocked(String session, int limit, String after, boolean notices) {
        resultPage(limit);
        if (after != null) WorkspaceCoordinationReference.requireReferenceId(after);
        java.util.TreeMap<String, WorkspaceCoordinationReference> found = new java.util.TreeMap<>();
        scanCoordinationLedgerLocked(session, notices, (evidence, reference, notice, reply, transition) -> {
            if (reference != null && notice == notices && (after == null || reference.referenceId.compareTo(after) > 0)) {
                found.put(reference.referenceId, reference);
                if (found.size() > limit) found.pollLastEntry();
            }
        });
        return Collections.unmodifiableList(new ArrayList<>(found.values()));
    }

    /** A single durable reply claim per exact source request. No dispatch happens inside the store. */
    public synchronized WorkspaceCoordinationReply prepareWorkspaceCoordinationReply(
            WorkspaceCoordinationReply proposed, Runnable finalAdmission) {
        requireCoordinationReply(proposed, finalAdmission);
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.prepareWorkspaceCoordinationReplySync(
                proposed, finalAdmission::run), "prepare workspace coordination reply");
        synchronized (SESSION_TITLE_LOCK) {
            WorkspaceCoordinationReply existing = readWorkspaceCoordinationReply(
                    proposed.reference.ownerCentralSessionId, proposed.reference.referenceId);
            if (existing != null) {
                if (!existing.samePayload(proposed)) coordinationConflict();
                return existing;
            }
            // An alternate assignment/reference cannot circumvent the source-wide reply claim.
            scanCoordinationLedgerLocked(proposed.reference.ownerCentralSessionId, true,
                    (evidence, reference, notice, reply, transition) -> {
                if (reply != null && reply.reference.evidenceId.equals(proposed.reference.evidenceId)) coordinationConflict();
            });
            if (proposed.state != WorkspaceCoordinationReply.State.PREPARED)
                throw new IllegalArgumentException("A new reply must be PREPARED");
            requireExactCoordinationReplySource(proposed.reference);
            appendResultRowLocked(proposed.reference.ownerCentralSessionId, proposed.toJson(), true, finalAdmission);
            return proposed;
        }
    }

    /** Only a newly appended STARTED record authorizes the caller's one exact synchronous dispatch. */
    public synchronized WorkspaceCoordinationReply markWorkspaceCoordinationReplyStarted(
            WorkspaceCoordinationReply proposed, Runnable finalAdmission) {
        requireCoordinationReply(proposed, finalAdmission);
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.markWorkspaceCoordinationReplyStartedSync(
                proposed, finalAdmission::run), "mark workspace coordination reply started");
        synchronized (SESSION_TITLE_LOCK) {
            WorkspaceCoordinationReply durable = requireExistingCoordinationReply(proposed);
            if (durable.state == WorkspaceCoordinationReply.State.ACCEPTED_LOCAL) return durable;
            if (durable.state == WorkspaceCoordinationReply.State.STARTED)
                throw new IllegalStateException("Coordination reply is UNCERTAIN; dispatch must not be retried");
            requireExactCoordinationReplySource(durable.reference);
            WorkspaceCoordinationReply started = durable.withState(WorkspaceCoordinationReply.State.STARTED,
                    Math.max(System.currentTimeMillis(), durable.stateAtMillis));
            appendResultRowLocked(durable.reference.ownerCentralSessionId, started.toStateJson(), true, finalAdmission);
            return started;
        }
    }

    /** Record only after the exact live mailbox synchronously accepted; a failed write leaves STARTED/UNCERTAIN. */
    public synchronized WorkspaceCoordinationReply markWorkspaceCoordinationReplyAccepted(
            WorkspaceCoordinationReply proposed, Runnable finalAdmission) {
        requireCoordinationReply(proposed, finalAdmission);
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.markWorkspaceCoordinationReplyAcceptedSync(
                proposed, finalAdmission::run), "mark workspace coordination reply accepted");
        synchronized (SESSION_TITLE_LOCK) {
            WorkspaceCoordinationReply durable = requireExistingCoordinationReply(proposed);
            if (durable.state == WorkspaceCoordinationReply.State.ACCEPTED_LOCAL) return durable;
            if (durable.state != WorkspaceCoordinationReply.State.STARTED)
                throw new IllegalStateException("Coordination acceptance requires a durable STARTED claim");
            requireExactCoordinationReplySource(durable.reference);
            WorkspaceCoordinationReply accepted = durable.withState(WorkspaceCoordinationReply.State.ACCEPTED_LOCAL,
                    Math.max(System.currentTimeMillis(), durable.stateAtMillis));
            appendResultRowLocked(durable.reference.ownerCentralSessionId, accepted.toStateJson(), true, finalAdmission);
            return accepted;
        }
    }

    /** Historical receipt only. STARTED means UNCERTAIN, including after reopening; never resume or replay. */
    public synchronized WorkspaceCoordinationReply readWorkspaceCoordinationReply(String centralSessionId, String referenceId) {
        WorkspaceCoordinationReference.requireReferenceId(referenceId);
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.readWorkspaceCoordinationReplySync(
                centralSessionId, referenceId, null), "read workspace coordination reply");
        requireResultCentral(centralSessionId);
        synchronized (SESSION_TITLE_LOCK) {
            final WorkspaceCoordinationReply[] found = {null};
            scanCoordinationLedgerLocked(centralSessionId, true, (evidence, reference, notice, reply, transition) -> {
                if (reply != null && referenceId.equals(reply.reference.referenceId)) found[0] = reply;
                if (transition != null && referenceId.equals(transition.referenceId))
                    found[0] = found[0].withState(transition.state, transition.stateAtMillis);
            });
            return found[0];
        }
    }

    public synchronized WorkspaceCoordinationReply readWorkspaceCoordinationReply(
            String centralSessionId, String referenceId, String replyKey) {
        if (replyKey == null || !replyKey.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}"))
            throw new IllegalArgumentException("Invalid coordination reply key");
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.readWorkspaceCoordinationReplySync(
                centralSessionId, referenceId, replyKey), "read keyed workspace coordination reply");
        WorkspaceCoordinationReply reply = readWorkspaceCoordinationReply(centralSessionId, referenceId);
        if (reply != null && !reply.replyKey.equals(replyKey)) coordinationConflict();
        return reply;
    }

    /** Exact bounded identity lookup; at most two strict scans, with no retained reply bodies or new index. */
    public synchronized WorkspaceCoordinationReply readWorkspaceCoordinationReplyById(String centralSessionId, String replyId) {
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.readWorkspaceCoordinationReplyByIdSync(
                centralSessionId, replyId), "read workspace coordination reply by ID");
        requireResultCentral(centralSessionId);
        if (replyId == null || !replyId.matches("workspace-coordination-reply-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid coordination reply id");
        synchronized (SESSION_TITLE_LOCK) {
            final String[] referenceId = {null};
            scanCoordinationLedgerLocked(centralSessionId, true, (evidence, reference, notice, reply, transition) -> {
                if (reply != null && replyId.equals(reply.replyId)) {
                    if (referenceId[0] != null && !referenceId[0].equals(reply.reference.referenceId)) coordinationConflict();
                    referenceId[0] = reply.reference.referenceId;
                }
            });
            return referenceId[0] == null ? null : readWorkspaceCoordinationReply(centralSessionId, referenceId[0]);
        }
    }

    public synchronized void requireWorkspaceCoordinationDisclosureLive(
            WorkspaceCoordinationReference reference, boolean noticeRequired) {
        requireCoordinationReference(reference, () -> { });
        if (chatDatabaseBridge != null) {
            requireChatDbSuccess(chatDatabaseBridge.requireWorkspaceCoordinationDisclosureLiveSync(reference, noticeRequired),
                    "check workspace coordination disclosure");
            return;
        }
        synchronized (SESSION_TITLE_LOCK) {
            requireResultLedgerLocked(reference.executionSessionId, false);
            File central = requireResultLedgerLocked(reference.ownerCentralSessionId, true);
            if (noticeRequired && !central.isFile()) throw new IllegalStateException("Central coordination receipt is unavailable");
        }
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
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.appendWorkspaceInputIncorporationSync(
                proposed, finalAdmission::run), "append workspace input incorporation");
        synchronized (SESSION_TITLE_LOCK) {
            requireIncorporationCentralLocked(proposed.ownerCentralSessionId);
            requireIncorporationSourceLocked(proposed);
            WorkspaceInputIncorporation existing = findInputIncorporationLocked(
                    proposed.ownerCentralSessionId, proposed.receiptId);
            if (existing != null) {
                if (!existing.sameBinding(proposed)) incorporationConflict();
                return existing;
            }
            // A mailbox identity cannot be repurposed under another source claim.
            scanInputIncorporationLedgerLocked(proposed.ownerCentralSessionId, value -> {
                if (value.mailboxMessageId.equals(proposed.mailboxMessageId)) incorporationConflict();
            });
            appendResultRowLocked(proposed.ownerCentralSessionId, proposed.toJson(), true, () -> {
                finalAdmission.run();
                requireIncorporationCentralLocked(proposed.ownerCentralSessionId);
            });
            return proposed;
        }
    }

    /** Exact metadata only; current registry ownership and READ policy remain the caller's responsibility. */
    public synchronized WorkspaceInputIncorporation readWorkspaceInputIncorporation(String centralSessionId,
            WorkspaceInputIncorporation.SourceKind sourceKind, String sourceId) {
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.readWorkspaceInputIncorporationSync(
                centralSessionId, sourceKind, sourceId), "read workspace input incorporation");
        requireResultCentral(centralSessionId);
        String receiptId = WorkspaceInputIncorporation.receiptId(sourceKind, sourceId);
        synchronized (SESSION_TITLE_LOCK) {
            WorkspaceInputIncorporation found = findInputIncorporationLocked(centralSessionId, receiptId);
            if (found != null) requireIncorporationSourceLocked(found);
            return found;
        }
    }

    /** Bounded lexical pages, never raw source bodies or an unbounded receipt collection. */
    public synchronized List<WorkspaceInputIncorporation> listWorkspaceInputIncorporations(
            String centralSessionId, int limit, String afterReceiptId) {
        if (chatDatabaseBridge != null) return requireChatDbSuccess(chatDatabaseBridge.listWorkspaceInputIncorporationsSync(
                centralSessionId, limit, afterReceiptId), "list workspace input incorporations");
        requireResultCentral(centralSessionId); resultPage(limit);
        if (afterReceiptId != null) WorkspaceInputIncorporation.requireReceiptId(afterReceiptId);
        synchronized (SESSION_TITLE_LOCK) {
            java.util.TreeMap<String, WorkspaceInputIncorporation> found = new java.util.TreeMap<>();
            scanInputIncorporationLedgerLocked(centralSessionId, value -> {
                if (afterReceiptId == null || value.receiptId.compareTo(afterReceiptId) > 0) {
                    found.put(value.receiptId, value);
                    if (found.size() > limit) found.pollLastEntry();
                }
            });
            for (WorkspaceInputIncorporation value : found.values()) requireIncorporationSourceLocked(value);
            return Collections.unmodifiableList(new ArrayList<>(found.values()));
        }
    }

    /** Final local tombstone/source boundary; callers still enforce current READ/owner admission. */
    public synchronized void requireWorkspaceInputIncorporationDisclosureLive(WorkspaceInputIncorporation value) {
        if (value == null) throw new IllegalArgumentException("Incorporation is required");
        WorkspaceInputIncorporation.fromJsonStrict(value.toJson());
        if (chatDatabaseBridge != null) {
            requireChatDbSuccess(chatDatabaseBridge.requireWorkspaceInputIncorporationDisclosureLiveSync(value),
                    "check workspace input incorporation disclosure");
            return;
        }
        synchronized (SESSION_TITLE_LOCK) {
            requireIncorporationCentralLocked(value.ownerCentralSessionId);
            WorkspaceInputIncorporation durable = findInputIncorporationLocked(value.ownerCentralSessionId, value.receiptId);
            if (durable == null || !durable.samePayload(value)) incorporationConflict();
            requireIncorporationSourceLocked(durable);
        }
    }

    private WorkspaceInputIncorporation findInputIncorporationLocked(String central, String receiptId) {
        final WorkspaceInputIncorporation[] found = {null};
        scanInputIncorporationLedgerLocked(central, value -> {
            if (receiptId.equals(value.receiptId)) found[0] = value;
        });
        return found[0];
    }
    private void requireIncorporationCentralLocked(String central) {
        if (!requireResultLedgerLocked(central, true).isFile())
            throw new IllegalStateException("Central input incorporation ledger is unavailable");
    }
    private void requireIncorporationSourceLocked(WorkspaceInputIncorporation value) {
        // Native sources live in SQL, not a fabricated conversation row. Still reject a missing,
        // deleted or corrupt execution ledger, and keep exact reply evidence authoritative.
        scanResultLedgerLocked(value.executionSessionId, false, (evidence, reference, notice) -> { });
        if (value.sourceKind == WorkspaceInputIncorporation.SourceKind.REPLY) {
            WorkspaceCoordinationReply reply = readWorkspaceCoordinationReply(value.ownerCentralSessionId, value.sourceReferenceId);
            if (!value.matchesReply(reply)) throw new IllegalStateException("Incorporation has no exact admitted reply source");
            requireExactCoordinationReplySource(reply.reference);
        }
    }
    private static void incorporationConflict() {
        throw new IllegalStateException("Input source already has a different immutable incorporation claim");
    }
    private interface InputIncorporationRowVisitor { void accept(WorkspaceInputIncorporation value); }

    private WorkspaceCoordinationReply requireExistingCoordinationReply(WorkspaceCoordinationReply proposed) {
        WorkspaceCoordinationReply durable = readWorkspaceCoordinationReply(
                proposed.reference.ownerCentralSessionId, proposed.reference.referenceId);
        if (durable == null) throw new IllegalStateException("Coordination reply intent is missing");
        if (!durable.samePayload(proposed)) coordinationConflict();
        return durable;
    }
    private void requireExactCoordinationReplySource(WorkspaceCoordinationReference reference) {
        WorkspaceCoordinationReference source = readWorkspaceCoordinationReference(reference.executionSessionId, reference.referenceId);
        WorkspaceCoordinationReference notice = readWorkspaceCoordinationNotice(reference.ownerCentralSessionId, reference.referenceId);
        if (source == null || notice == null || !source.samePayload(reference) || !notice.samePayload(reference))
            throw new IllegalStateException("Reply requires an exact durable source reference and Central notice");
    }
    private static void requireCoordinationReply(WorkspaceCoordinationReply value, Runnable admission) {
        if (value == null || admission == null) throw new IllegalArgumentException("Reply and admission are required");
        WorkspaceCoordinationReply.fromJsonStrict(value.toJson());
    }
    private static void requireCoordinationReference(WorkspaceCoordinationReference value, Runnable admission) {
        if (value == null || admission == null) throw new IllegalArgumentException("Reference and admission are required");
        WorkspaceCoordinationReference.fromJsonStrict(value.toJson());
    }
    private static boolean sameCoordinationEvidence(CrewCoordinationEvidence a, CrewCoordinationEvidence b) {
        return a.evidenceId.equals(b.evidenceId) && a.contentSha256.equals(b.contentSha256)
                && a.conversationId.equals(b.conversationId) && a.missionId.equals(b.missionId)
                && a.botId.equals(b.botId) && a.cycleId.equals(b.cycleId) && a.messageId.equals(b.messageId)
                && a.kind.equals(b.kind) && a.text.equals(b.text) && a.refs.equals(b.refs) && a.producedAtMillis == b.producedAtMillis;
    }
    private static void validateCoordinationDuplicate(Map<String, WorkspaceCoordinationReference> seen,
            WorkspaceCoordinationReference value) throws IOException {
        WorkspaceCoordinationReference prior = seen.put(value.referenceId, value);
        if (prior != null && !prior.samePayload(value)) throw new IOException("Conflicting coordination reference identity");
    }
    private static void coordinationConflict() {
        throw new IllegalStateException("Coordination request already has a different immutable reply claim");
    }
    private interface CoordinationRowVisitor {
        void accept(CrewCoordinationEvidence evidence, WorkspaceCoordinationReference reference, boolean notice,
                WorkspaceCoordinationReply reply, WorkspaceCoordinationReply.Transition transition);
    }
    private static final class CoordinationEvidenceIdentity {
        final String digest, session, mission, bot, cycle, message, kind;
        final long producedAt;
        CoordinationEvidenceIdentity(CrewCoordinationEvidence evidence) {
            digest = evidence.contentSha256; session = evidence.conversationId; mission = evidence.missionId;
            bot = evidence.botId; cycle = evidence.cycleId; message = evidence.messageId;
            kind = evidence.kind; producedAt = evidence.producedAtMillis;
        }
        boolean matches(WorkspaceCoordinationReference reference) {
            return digest.equals(reference.contentSha256) && session.equals(reference.executionSessionId)
                    && mission.equals(reference.missionId) && bot.equals(reference.botId) && cycle.equals(reference.cycleId)
                    && message.equals(reference.messageId) && kind.equals(reference.kind) && producedAt == reference.producedAtMillis;
        }
    }
    /** Metadata only during whole-tail scans; no accumulation of immutable reply bodies. */
    private static final class CoordinationReplyIdentity {
        final String replyId, payloadSha256, referenceId, executionSessionId, mailboxMessageId;
        final WorkspaceCoordinationReference reference;
        final long preparedAtMillis;
        WorkspaceCoordinationReply.State state;
        long stateAtMillis;
        CoordinationReplyIdentity(WorkspaceCoordinationReply reply) {
            replyId = reply.replyId; payloadSha256 = reply.payloadSha256; referenceId = reply.reference.referenceId;
            executionSessionId = reply.reference.executionSessionId; preparedAtMillis = reply.preparedAtMillis;
            mailboxMessageId = reply.mailboxMessageId; reference = reply.reference;
            state = reply.state; stateAtMillis = reply.stateAtMillis;
        }
        boolean matches(WorkspaceCoordinationReply reply) {
            return replyId.equals(reply.replyId) && payloadSha256.equals(reply.payloadSha256)
                    && referenceId.equals(reply.reference.referenceId) && executionSessionId.equals(reply.reference.executionSessionId)
                    && preparedAtMillis == reply.preparedAtMillis;
        }
        boolean matches(WorkspaceInputIncorporation value) {
            return state != WorkspaceCoordinationReply.State.PREPARED
                    && replyId.equals(value.sourceId) && payloadSha256.equals(value.sourcePayloadSha256)
                    && referenceId.equals(value.sourceReferenceId) && mailboxMessageId.equals(value.mailboxMessageId)
                    && executionSessionId.equals(value.executionSessionId)
                    && reference.ownerCentralSessionId.equals(value.ownerCentralSessionId)
                    && reference.workspaceId.equals(value.workspaceId) && reference.assignmentId.equals(value.assignmentId)
                    && reference.requestId.equals(value.requestId) && reference.missionId.equals(value.missionId)
                    && reference.botId.equals(value.botId) && reference.cycleId.equals(value.admissionCycleId);
        }
        void advance(WorkspaceCoordinationReply.Transition next) throws IOException {
            if (!replyId.equals(next.replyId) || !payloadSha256.equals(next.payloadSha256)
                    || !referenceId.equals(next.referenceId) || !executionSessionId.equals(next.executionSessionId)
                    || next.stateAtMillis < stateAtMillis) throw new IOException("Conflicting coordination reply transition binding");
            if (state == next.state) {
                if (stateAtMillis != next.stateAtMillis) throw new IOException("Conflicting duplicate coordination reply state");
                return;
            }
            if (!(state == WorkspaceCoordinationReply.State.PREPARED && next.state == WorkspaceCoordinationReply.State.STARTED)
                    && !(state == WorkspaceCoordinationReply.State.STARTED && next.state == WorkspaceCoordinationReply.State.ACCEPTED_LOCAL))
                throw new IOException("Missing or out-of-order coordination reply transition");
            state = next.state; stateAtMillis = next.stateAtMillis;
        }
    }

    private interface ResultRowVisitor {
        void accept(CrewResultEvidence evidence, WorkspaceResultReference reference, boolean notice);
    }

    /** Only metadata is retained while scanning. Full result strings do not accumulate in paging. */
    private static final class ResultEvidenceIdentity {
        final String digest, session, mission, bot, cycle, status;
        final long producedAt;
        ResultEvidenceIdentity(CrewResultEvidence evidence) {
            digest = evidence.contentSha256; session = evidence.conversationId; mission = evidence.missionId;
            bot = evidence.botId; cycle = evidence.cycleId; status = evidence.reportedStatus; producedAt = evidence.producedAtMillis;
        }
        boolean matches(WorkspaceResultReference reference) {
            return digest.equals(reference.contentSha256) && session.equals(reference.executionSessionId)
                    && mission.equals(reference.missionId) && bot.equals(reference.botId) && cycle.equals(reference.cycleId)
                    && status.equals(reference.reportedStatus) && producedAt == reference.producedAtMillis;
        }
    }

    /**
     * Do not reuse readConversationRows: its legacy skip-on-error behavior cannot prove absence.
     * Validate every row, including the tail, before returning even a found exact result. Never
     * repair/truncate bytes, skip malformed rows, append beyond torn tails, or replay an action.
     */
    private void scanResultLedgerLocked(String session, boolean allowMissingCentral, ResultRowVisitor visitor) {
        scanWorkspaceLedgerLocked(session, allowMissingCentral, visitor, (evidence, reference, notice, reply, transition) -> { });
    }
    private void scanCoordinationLedgerLocked(String session, boolean allowMissingCentral, CoordinationRowVisitor visitor) {
        scanWorkspaceLedgerLocked(session, allowMissingCentral, (evidence, reference, notice) -> { }, visitor);
    }
    private void scanInputIncorporationLedgerLocked(String central, InputIncorporationRowVisitor visitor) {
        scanWorkspaceLedgerLocked(central, true, (evidence, reference, notice) -> { },
                (evidence, reference, notice, reply, transition) -> { }, visitor);
    }
    private void scanWorkspaceLedgerLocked(String session, boolean allowMissingCentral,
            ResultRowVisitor visitor, CoordinationRowVisitor coordinationVisitor) {
        scanWorkspaceLedgerLocked(session, allowMissingCentral, visitor, coordinationVisitor, value -> { });
    }
    private void scanWorkspaceLedgerLocked(String session, boolean allowMissingCentral,
            ResultRowVisitor visitor, CoordinationRowVisitor coordinationVisitor, InputIncorporationRowVisitor incorporationVisitor) {
        File ledger = requireResultLedgerLocked(session, allowMissingCentral);
        if (!ledger.exists()) return;
        Map<String, ResultEvidenceIdentity> evidenceKeys = new LinkedHashMap<>();
        Map<String, WorkspaceResultReference> referenceKeys = new LinkedHashMap<>();
        Map<String, CoordinationEvidenceIdentity> coordinationEvidenceKeys = new LinkedHashMap<>();
        Map<String, WorkspaceCoordinationReference> coordinationReferenceKeys = new LinkedHashMap<>();
        Map<String, CoordinationReplyIdentity> coordinationReplyKeys = new LinkedHashMap<>();
        Map<String, String> coordinationReplyEvidenceClaims = new LinkedHashMap<>();
        Map<String, WorkspaceInputIncorporation> incorporationKeys = new LinkedHashMap<>();
        Map<String, String> incorporationMailboxClaims = new LinkedHashMap<>();
        try (java.io.InputStreamReader decoded = new java.io.InputStreamReader(new java.io.FileInputStream(ledger),
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT));
             BufferedReader reader = new BufferedReader(decoded)) {
            requireCompleteResultTail(ledger);
            String line;
            while ((line = reader.readLine()) != null) {
                JSONObject row = strictResultJson(line);
                String type = row.optString("type", "");
                if ("crew_result_evidence".equals(type)) {
                    requireResultSource(session);
                    CrewResultEvidence evidence = CrewResultEvidence.fromJsonStrict(row);
                    if (!session.equals(evidence.conversationId)) throw new IOException("Wrong evidence ledger owner");
                    ResultEvidenceIdentity previous = evidenceKeys.get(evidence.evidenceId);
                    if (previous != null && !previous.digest.equals(evidence.contentSha256))
                        throw new IOException("Conflicting evidence identity");
                    evidenceKeys.put(evidence.evidenceId, new ResultEvidenceIdentity(evidence));
                    visitor.accept(evidence, null, false);
                } else if (WorkspaceResultReference.REFERENCE_TYPE.equals(type)) {
                    requireResultSource(session);
                    WorkspaceResultReference reference = WorkspaceResultReference.fromJsonStrict(row);
                    ResultEvidenceIdentity evidence = evidenceKeys.get(reference.evidenceId);
                    if (!session.equals(reference.executionSessionId) || evidence == null || !evidence.matches(reference))
                        throw new IOException("Reference has no preceding exact source evidence");
                    validateResultDuplicate(referenceKeys, reference);
                    visitor.accept(null, reference, false);
                } else if (WorkspaceResultReference.NOTICE_TYPE.equals(type)) {
                    requireResultCentral(session);
                    WorkspaceResultReference reference = WorkspaceResultReference.fromNoticeJsonStrict(row);
                    if (!session.equals(reference.ownerCentralSessionId)) throw new IOException("Wrong notice ledger owner");
                    validateResultDuplicate(referenceKeys, reference);
                    visitor.accept(null, reference, true);
                } else if (CrewCoordinationEvidence.ROW_TYPE.equals(type)) {
                    requireResultSource(session);
                    CrewCoordinationEvidence evidence = CrewCoordinationEvidence.fromJsonStrict(row);
                    if (!session.equals(evidence.conversationId)) throw new IOException("Wrong coordination evidence owner");
                    CoordinationEvidenceIdentity previous = coordinationEvidenceKeys.get(evidence.evidenceId);
                    if (previous != null && !previous.digest.equals(evidence.contentSha256))
                        throw new IOException("Conflicting coordination evidence identity");
                    coordinationEvidenceKeys.put(evidence.evidenceId, new CoordinationEvidenceIdentity(evidence));
                    coordinationVisitor.accept(evidence, null, false, null, null);
                } else if (WorkspaceCoordinationReference.REFERENCE_TYPE.equals(type)) {
                    requireResultSource(session);
                    WorkspaceCoordinationReference reference = WorkspaceCoordinationReference.fromJsonStrict(row);
                    CoordinationEvidenceIdentity evidence = coordinationEvidenceKeys.get(reference.evidenceId);
                    if (!session.equals(reference.executionSessionId) || evidence == null || !evidence.matches(reference))
                        throw new IOException("Coordination reference has no preceding exact source evidence");
                    validateCoordinationDuplicate(coordinationReferenceKeys, reference);
                    coordinationVisitor.accept(null, reference, false, null, null);
                } else if (WorkspaceCoordinationReference.NOTICE_TYPE.equals(type)) {
                    requireResultCentral(session);
                    WorkspaceCoordinationReference reference = WorkspaceCoordinationReference.fromNoticeJsonStrict(row);
                    if (!session.equals(reference.ownerCentralSessionId)) throw new IOException("Wrong coordination notice owner");
                    validateCoordinationDuplicate(coordinationReferenceKeys, reference);
                    coordinationVisitor.accept(null, reference, true, null, null);
                } else if (WorkspaceCoordinationReply.INTENT_TYPE.equals(type)) {
                    requireResultCentral(session);
                    WorkspaceCoordinationReply reply = WorkspaceCoordinationReply.fromJsonStrict(row);
                    WorkspaceCoordinationReference notice = coordinationReferenceKeys.get(reply.reference.referenceId);
                    if (!session.equals(reply.reference.ownerCentralSessionId) || notice == null || !notice.samePayload(reply.reference))
                        throw new IOException("Coordination reply has no preceding exact notice");
                    String previousReference = coordinationReplyEvidenceClaims.put(reply.reference.evidenceId, reply.reference.referenceId);
                    if (previousReference != null && !previousReference.equals(reply.reference.referenceId))
                        throw new IOException("Multiple reply claims for one exact coordination source");
                    CoordinationReplyIdentity previous = coordinationReplyKeys.get(reply.reference.referenceId);
                    if (previous != null && (!previous.matches(reply) || previous.state != WorkspaceCoordinationReply.State.PREPARED))
                        throw new IOException("Conflicting or out-of-order coordination reply claim");
                    coordinationReplyKeys.put(reply.reference.referenceId, new CoordinationReplyIdentity(reply));
                    coordinationVisitor.accept(null, null, false, reply, null);
                } else if (WorkspaceCoordinationReply.STATE_TYPE.equals(type)) {
                    requireResultCentral(session);
                    WorkspaceCoordinationReply.Transition transition = WorkspaceCoordinationReply.fromStateJsonStrict(row);
                    CoordinationReplyIdentity previous = coordinationReplyKeys.get(transition.referenceId);
                    if (previous == null) throw new IOException("Coordination reply state has no preceding intent");
                    previous.advance(transition);
                    coordinationVisitor.accept(null, null, false, null, transition);
                } else if (WorkspaceInputIncorporation.ROW_TYPE.equals(type)) {
                    requireResultCentral(session);
                    WorkspaceInputIncorporation value = WorkspaceInputIncorporation.fromJsonStrict(row);
                    if (!session.equals(value.ownerCentralSessionId)) throw new IOException("Wrong incorporation ledger owner");
                    if (value.sourceKind == WorkspaceInputIncorporation.SourceKind.REPLY) {
                        CoordinationReplyIdentity reply = coordinationReplyKeys.get(value.sourceReferenceId);
                        if (reply == null || !reply.matches(value))
                            throw new IOException("Incorporation has no preceding exact admitted reply");
                    }
                    String priorClaim = incorporationMailboxClaims.put(value.mailboxMessageId, value.receiptId);
                    if (priorClaim != null && !priorClaim.equals(value.receiptId))
                        throw new IOException("Multiple incorporation sources claim the same mailbox message");
                    WorkspaceInputIncorporation previous = incorporationKeys.get(value.receiptId);
                    if (previous != null && !previous.sameBinding(value))
                        throw new IOException("Conflicting input incorporation identity");
                    // Later checkpoints can support the same proof; never overwrite the first observation.
                    if (previous == null) {
                        incorporationKeys.put(value.receiptId, value);
                        incorporationVisitor.accept(value);
                    }
                } else if (type.startsWith("crew_coordination_") || type.startsWith("workspace_coordination_")
                        || row.has("crewCoordinationSchemaVersion") || row.has("workspaceCoordinationSchemaVersion")
                        || row.has("workspaceCoordinationReplySchemaVersion") || row.has("replyId")
                        || type.startsWith("workspace_input_") || row.has("workspaceInputIncorporationSchemaVersion")
                        || row.has("receiptId")
                        || type.startsWith("crew_result_") || type.startsWith("workspace_result_")
                        || row.has("crewResultSchemaVersion") || row.has("workspaceResultSchemaVersion")
                        || row.has("evidenceId") || row.has("referenceId")) {
                    throw new IOException("Unsupported result ledger row");
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("Result ledger is unreadable or corrupt; no repair or replay was attempted", error);
        }
    }

    /** Lightweight last disclosure boundary; no evidence content or callbacks under this lock. */
    public synchronized void requireWorkspaceResultDisclosureLive(WorkspaceResultReference reference, boolean noticeRequired) {
        if (chatDatabaseBridge != null) {
            requireChatDbSuccess(chatDatabaseBridge.requireWorkspaceResultDisclosureLiveSync(reference, noticeRequired),
                    "check workspace result disclosure");
            return;
        }
        synchronized (SESSION_TITLE_LOCK) {
            requireResultLedgerLocked(reference.executionSessionId, false);
            File central = requireResultLedgerLocked(reference.ownerCentralSessionId, true);
            if (noticeRequired && !central.isFile()) throw new IllegalStateException("Central receipt is unavailable");
        }
    }

    private File requireResultLedgerLocked(String session, boolean allowMissingCentral) {
        if (allowMissingCentral) requireResultCentral(session); else requireResultSource(session);
        File ledger = conversationFile(session);
        if (conversationMetadata.read(session).deleted) throw new IllegalStateException("Result conversation was deleted");
        if (ledger.exists() && !ledger.isFile()) throw new IllegalStateException("Result ledger is not a regular file");
        if (!allowMissingCentral && !ledger.isFile()) throw new IllegalStateException("Source result ledger is unavailable");
        return ledger;
    }

    private void appendResultRowLocked(String session, JSONObject row, boolean allowMissingCentral, Runnable beforeWrite) {
        scanResultLedgerLocked(session, allowMissingCentral, (evidence, reference, notice) -> { });
        byte[] record = losslessResultRecord(row);
        // Expensive validation and serialization precede the final live admission check. The
        // shared lock excludes ordinary deletion; only lightweight path/tombstone checks follow.
        beforeWrite.run();
        File ledger = requireResultLedgerLocked(session, allowMissingCentral);
        if (allowMissingCentral) requireResultLedgerLocked(row.optString("executionSessionId"), false);
        try {
            if (ledger.exists()) requireCompleteResultTail(ledger);
            try (FileOutputStream output = new FileOutputStream(ledger, true)) {
                output.write(record); output.flush(); output.getFD().sync();
            }
        } catch (Exception error) { throw new IllegalStateException("Could not append durable result ledger row", error); }
    }

    private static byte[] losslessResultRecord(JSONObject row) {
        // JSONObject may emit literal lone surrogates. Escape UTF-16 units before UTF-8 encoding
        // so exact worker strings survive without replacement by the charset encoder.
        String serialized = row.toString();
        StringBuilder escaped = new StringBuilder(serialized.length() + 1);
        for (int index = 0; index < serialized.length(); index++) {
            char value = serialized.charAt(index);
            if (Character.isSurrogate(value)) escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) value));
            else escaped.append(value);
        }
        return escaped.append('\n').toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void requireCompleteResultTail(File ledger) throws IOException {
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(ledger, "r")) {
            if (input.length() > 0) {
                input.seek(input.length() - 1);
                if (input.read() != '\n') throw new IOException("Incomplete result ledger tail");
            }
        }
    }

    private static JSONObject strictResultJson(String line) throws Exception {
        try (android.util.JsonReader reader = new android.util.JsonReader(new java.io.StringReader(line))) {
            reader.setLenient(false);
            Object value = strictResultJsonValue(reader, 0);
            if (!(value instanceof JSONObject) || reader.peek() != android.util.JsonToken.END_DOCUMENT)
                throw new IOException("Expected one JSON object per ledger row");
            return (JSONObject) value;
        }
    }

    private static Object strictResultJsonValue(android.util.JsonReader reader, int depth) throws Exception {
        if (depth > 128) throw new IOException("Excessively nested ledger JSON");
        switch (reader.peek()) {
            case BEGIN_OBJECT: {
                JSONObject result = new JSONObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (result.has(name)) throw new IOException("Duplicate ledger JSON field");
                    result.put(name, strictResultJsonValue(reader, depth + 1));
                }
                reader.endObject(); return result;
            }
            case BEGIN_ARRAY: {
                JSONArray result = new JSONArray(); reader.beginArray();
                while (reader.hasNext()) result.put(strictResultJsonValue(reader, depth + 1));
                reader.endArray(); return result;
            }
            case STRING: return reader.nextString();
            case BOOLEAN: return reader.nextBoolean();
            case NULL: reader.nextNull(); return JSONObject.NULL;
            case NUMBER: {
                String value = reader.nextString();
                if (value.matches("-?(0|[1-9][0-9]*)")) return Long.parseLong(value);
                double number = Double.parseDouble(value);
                if (Double.isInfinite(number) || Double.isNaN(number)) throw new IOException("Invalid JSON number");
                return number;
            }
            default: throw new IOException("Invalid ledger JSON value");
        }
    }

    private static void validateResultDuplicate(Map<String, WorkspaceResultReference> seen, WorkspaceResultReference value)
            throws IOException {
        WorkspaceResultReference prior = seen.put(value.referenceId, value);
        if (prior != null && !prior.samePayload(value)) throw new IOException("Conflicting result reference identity");
    }
    private static boolean sameResultEvidence(CrewResultEvidence first, CrewResultEvidence second) {
        return first.evidenceId.equals(second.evidenceId) && first.contentSha256.equals(second.contentSha256)
                && first.conversationId.equals(second.conversationId) && first.missionId.equals(second.missionId)
                && first.botId.equals(second.botId) && first.cycleId.equals(second.cycleId)
                && first.reportedStatus.equals(second.reportedStatus) && first.resultText.equals(second.resultText)
                && first.producedAtMillis == second.producedAtMillis;
    }
    private static void requireResultReference(WorkspaceResultReference value, Runnable beforeWrite) {
        if (value == null || beforeWrite == null) throw new IllegalArgumentException("Reference and admission are required");
        WorkspaceResultReference.fromJsonStrict(value.toJson());
    }
    private static void requireResultSource(String session) {
        if (!WorkspaceIdentity.isExecutionSession(session)) throw new IllegalArgumentException("Canonical workspace session required");
    }
    private static void requireResultCentral(String session) {
        if (!WorkspaceIdentity.CENTRAL_SESSION.equals(session)) throw new IllegalArgumentException("Canonical Central owner required");
    }
    private static void resultPage(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Result page limit must be 1..100");
    }
    private static void resultConflict() { throw new IllegalStateException("Result identity already has a different payload"); }

    /** Reads the latest schema-supported Crew snapshot for each mission, preserving insertion order. */
    public List<CrewMissionSnapshot> readCrewMissionSnapshots(String sessionId) {
        if (chatDatabaseBridge != null) return chatDatabaseBridge.readCrewMissionSnapshotsSync(sessionId);
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) return new ArrayList<>();
            String path = ledger.getAbsolutePath();
            CrewSnapshotCache prior;
            CrewSnapshotCache cached = null;
            synchronized (CREW_SNAPSHOT_CACHE_LOCK) {
                prior = CREW_SNAPSHOT_CACHE.get(path);
                if (prior != null && prior.matches(snapshot)) {
                    cached = prior;
                    prior = null;
                } else if (prior == null || !prior.canExtend(snapshot)) prior = null;
                else prior = prior.copy();
            }
            if (cached != null) {
                synchronized (SESSION_TITLE_LOCK) {
                    if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                }
                synchronized (CREW_SNAPSHOT_CACHE_LOCK) { crewSnapshotCacheHitCount++; }
                return new ArrayList<>(cached.latest.values());
            }

            long startOffset = prior == null ? 0L : prior.length;
            LinkedHashMap<String, CrewMissionSnapshot> latest = prior == null
                    ? new LinkedHashMap<>() : prior.latest;
            if (snapshot.exists && snapshot.length > startOffset) {
                synchronized (CREW_SNAPSHOT_CACHE_LOCK) {
                    crewSnapshotLedgerScanCount++;
                    crewSnapshotLedgerScanBytes += snapshot.length - startOffset;
                }
                readCrewSnapshotsFromSnapshot(sessionId, snapshot, startOffset, latest);
            }
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
            }
            synchronized (CREW_SNAPSHOT_CACHE_LOCK) {
                CREW_SNAPSHOT_CACHE.put(path, new CrewSnapshotCache(snapshot, latest));
                while (CREW_SNAPSHOT_CACHE.size() > MAX_CACHED_CREW_SNAPSHOT_FILES)
                    CREW_SNAPSHOT_CACHE.remove(CREW_SNAPSHOT_CACHE.keySet().iterator().next());
            }
            return new ArrayList<>(latest.values());
        }
    }

    private static void readCrewSnapshotsFromSnapshot(String sessionId, LedgerSnapshot snapshot, long startOffset,
                                                       LinkedHashMap<String, CrewMissionSnapshot> latest) {
        if (startOffset < 0L || startOffset > snapshot.length) throw new IllegalArgumentException("Invalid Crew snapshot offset");
        try {
            FileInputStream input = new FileInputStream(snapshot.ledger);
            input.getChannel().position(startOffset);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new BoundedInputStream(input, snapshot.length - startOffset), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Normal ledger rows are minified JSON. This cheap substring test avoids a
                    // JSONObject allocation for ordinary chat, tool, task and result records.
                    if (line.indexOf("crew_snapshot") < 0 || !hasCrewSnapshotTypeToken(line)) continue;
                    JSONObject row;
                    try {
                        synchronized (CREW_SNAPSHOT_CACHE_LOCK) { crewSnapshotRowsParsedCount++; }
                        row = new JSONObject(line);
                    } catch (Exception ignored) { continue; }
                    if (!"crew_snapshot".equals(row.optString("type"))) continue;
                    CrewMissionSnapshot value = CrewMissionSnapshot.fromJson(row);
                    if (value != null && sessionId.equals(value.conversationId))
                        latest.put(value.missionId, value);
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not read Crew snapshots from conversation ledger", error);
        }
    }

    private static boolean hasCrewSnapshotTypeToken(String line) {
        if (line.indexOf("crew_snapshot") < 0) return false;
        int depth = 0;
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (value == '"') {
                int end = jsonStringEnd(line, index);
                if (end < 0) return false;
                if (depth == 1 && "type".equals(line.substring(index + 1, end))) {
                    int cursor = end + 1;
                    while (cursor < line.length() && Character.isWhitespace(line.charAt(cursor))) cursor++;
                    if (cursor < line.length() && line.charAt(cursor) == ':') {
                        cursor++;
                        while (cursor < line.length() && Character.isWhitespace(line.charAt(cursor))) cursor++;
                        if (line.startsWith("\"crew_snapshot\"", cursor)) return true;
                    }
                }
                index = end;
            } else if (value == '{') depth++;
            else if (value == '}') depth--;
        }
        return false;
    }

    private static int jsonStringEnd(String line, int start) {
        boolean escaped = false;
        for (int index = start + 1; index < line.length(); index++) {
            char value = line.charAt(index);
            if (escaped) escaped = false;
            else if (value == '\\') escaped = true;
            else if (value == '"') return index;
        }
        return -1;
    }

    /** Legacy conversations have no Crew rows and remain untouched. Active rows from an older process are interrupted, never resumed. */
    public synchronized List<CrewMissionSnapshot> recoverCrewMissions(String sessionId) {
        if (chatDatabaseBridge != null)
            return chatDatabaseBridge.recoverCrewMissionsSync(sessionId, CrewProcessIdentity.ID);
        List<CrewMissionSnapshot> recovered = new ArrayList<>();
        for (CrewMissionSnapshot snapshot : readCrewMissionSnapshots(sessionId)) {
            CrewMissionSnapshot value = snapshot.processId.equals(CrewProcessIdentity.ID) ? snapshot : snapshot.interrupted();
            if (value != snapshot) appendCrewMissionSnapshot(value);
            recovered.add(value);
        }
        return recovered;
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
                || (proactiveThreadKey != null && !proactiveThreadKey.isEmpty())
                || ProactiveConversation.SESSION_ID.equals(sessionId) || ScheduledTaskConversation.SESSION_ID.equals(sessionId))) {
            throw new IllegalArgumentException("Attachments are supported only in main-chat user messages");
        }
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new IllegalArgumentException("Conversation role must be user or assistant");
        }
        if (chatDatabaseBridge != null) {
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
                    System.currentTimeMillis() / 1000.0, conversationKindFor(sessionId)),
                    "append conversation message"));
            return saved.getMessageId();
        }
        File ledger = conversationFile(sessionId);
        JSONObject row = new JSONObject();
        String messageId = java.util.UUID.randomUUID().toString();
        try {
            row.put("role", role);
            row.put("content", content == null ? "" : content);
            row.put("messageId", messageId);
            if (attachments != null && !attachments.isEmpty()) row.put("attachments", ChatAttachment.toJsonArray(attachments));
            if ("assistant".equals(role)) {
                if (status != null && !status.isEmpty()) row.put("status", status);
                if (runId != null && !runId.isEmpty()) row.put("runId", runId);
                if (userMessageId != null && !userMessageId.isEmpty()) row.put("userMessageId", userMessageId);
                if (durationMs != null && durationMs > 0) row.put("durationMs", durationMs);
            }
            if (proactiveThreadKey != null && !proactiveThreadKey.isEmpty()) row.put("proactiveThreadKey", proactiveThreadKey);
            row.put("timestamp", System.currentTimeMillis() / 1000.0);
            synchronized (SESSION_TITLE_LOCK) {
                appendSessionRowLocked(ledger, row);
            }
            return messageId;
        } catch (Exception error) {
            throw new IllegalStateException("Could not persist conversation message", error);
        }
    }

    /** Idempotent system-authored assistant message for the dedicated Proactive conversation. */
    public String appendProactiveAssistantMessageIfAbsent(String sessionId, String decisionId,
            String content) {
        return appendProactiveAssistantMessageIfAbsent(sessionId, decisionId, content, null, "");
    }

    public String appendProactiveAssistantMessageIfAbsent(String sessionId, String decisionId,
            String content, List<ProactiveSuggestedReply> replies, String threadKey) {
        if (decisionId == null || decisionId.trim().isEmpty()) {
            throw new IllegalArgumentException("A proactive decision id is required");
        }
        if (chatDatabaseBridge != null) {
            List<com.jarvys.agent.chatdb.SuggestedReplyInput> storedReplies = new ArrayList<>();
            if (replies != null) for (ProactiveSuggestedReply reply : replies)
                storedReplies.add(new com.jarvys.agent.chatdb.SuggestedReplyInput(reply.getLabel(), reply.getText()));
            StoredMessage saved = requireChatDbSuccess(chatDatabaseBridge.appendProactiveAssistantMessageIfAbsentSync(
                    sessionId, decisionId, content == null ? "" : content, threadKey, storedReplies),
                    "append proactive message");
            return saved.getMessageId();
        }
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) throw new IllegalStateException("This chat has been deleted");
            String existingMessageId = "";
            boolean existingFound = false;
            List<JSONObject> rows = readConversationRowsFromSnapshot(snapshot);
            orderAssistantMessagesBeforeSteering(rows);
            for (JSONObject existing : rows) {
                if (decisionId.equals(existing.optString("proactiveDecisionId"))) {
                    existingMessageId = existing.optString("messageId", "");
                    existingFound = true;
                    break;
                }
            }
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                if (existingFound) return existingMessageId;
                String messageId = java.util.UUID.randomUUID().toString();
                JSONObject row = new JSONObject();
                try {
                    row.put("role", "assistant");
                    row.put("content", content == null ? "" : content);
                    row.put("messageId", messageId);
                    row.put("status", "COMPLETED");
                    row.put("type", "proactive_message");
                    row.put("proactiveDecisionId", decisionId);
                    row.put("proactiveThreadKey", threadKey == null ? "" : threadKey);
                    JSONArray replyRows = new JSONArray();
                    if (replies != null) for (ProactiveSuggestedReply reply : replies) {
                        replyRows.put(new JSONObject().put("label", reply.getLabel()).put("text", reply.getText()));
                    }
                    row.put("suggestedReplies", replyRows);
                    row.put("timestamp", System.currentTimeMillis() / 1000.0);
                    appendSessionRowLocked(ledger, row);
                    return messageId;
                } catch (Exception error) {
                    throw new IllegalStateException("Could not persist proactive conversation message", error);
                }
            }
        }
    }

    /** Appends the user-selected proactive reply before dispatching an ordinary agent turn. */
    public synchronized String appendProactiveUserMessage(String sessionId, String content, String threadKey,
                                                           String replyToMessageId) {
        if (content == null || content.trim().isEmpty() || threadKey == null || threadKey.trim().isEmpty()) {
            throw new IllegalArgumentException("A proactive user reply and thread key are required");
        }
        if (chatDatabaseBridge != null) {
            StoredMessage saved = requireChatDbSuccess(chatDatabaseBridge.appendMessageSync(new AppendMessageInput(
                    sessionId, "user", Collections.singletonList(new MessagePartInput("text", content)), null,
                    null, null, null, null, threadKey, replyToMessageId, null, null, false,
                    Collections.emptyList(), Collections.emptyList(), System.currentTimeMillis() / 1000.0,
                    conversationKindFor(sessionId)), "append proactive reply"));
            return saved.getMessageId();
        }
        String messageId = java.util.UUID.randomUUID().toString();
        JSONObject row = new JSONObject();
        try {
            row.put("role", "user");
            row.put("content", content);
            row.put("messageId", messageId);
            row.put("proactiveThreadKey", threadKey);
            row.put("proactiveReplyToMessageId", replyToMessageId == null ? "" : replyToMessageId);
            row.put("timestamp", System.currentTimeMillis() / 1000.0);
        } catch (Exception error) {
            throw new IllegalStateException("Could not create proactive reply message", error);
        }
        synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
        return messageId;
    }

    /** Atomically consumes every quick reply on a proactive message and returns only the selected text. */
    public ProactiveReplyClaim claimProactiveSuggestedReply(String sessionId, String messageId, int index) {
        if (chatDatabaseBridge != null) {
            return requireChatDbSuccess(chatDatabaseBridge.claimProactiveSuggestedReplySync(sessionId, messageId, index),
                    "claim proactive suggested reply");
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) return null;
            List<JSONObject> rows = readConversationRowsFromSnapshot(snapshot);
            orderAssistantMessagesBeforeSteering(rows);
            boolean alreadyUsed = false;
            JSONObject message = null;
            for (JSONObject row : rows) {
                if ("proactive_reply_used".equals(row.optString("type"))
                        && messageId.equals(row.optString("messageId"))) alreadyUsed = true;
                if (message == null && "proactive_message".equals(row.optString("type"))
                        && messageId.equals(row.optString("messageId"))) message = row;
            }
            String text = "";
            String threadKey = "";
            if (message != null && index >= 0) {
                JSONArray replies = message.optJSONArray("suggestedReplies");
                JSONObject reply = replies == null || index >= replies.length() ? null : replies.optJSONObject(index);
                if (reply != null) {
                    text = reply.optString("text").trim();
                    threadKey = message.optString("proactiveThreadKey").trim();
                }
            }
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                if (alreadyUsed || text.isEmpty() || threadKey.isEmpty()) return null;
                JSONObject claimed = new JSONObject();
                try {
                    claimed.put("type", "proactive_reply_used");
                    claimed.put("messageId", messageId);
                    claimed.put("timestamp", System.currentTimeMillis() / 1000.0);
                } catch (Exception error) { throw new IllegalStateException("Could not claim proactive reply", error); }
                appendSessionRowLocked(ledger, claimed);
                return new ProactiveReplyClaim(text, threadKey, "");
            }
        }
    }

    public String proactiveThreadKeyForUserMessage(String sessionId, String userMessageId) {
        if (userMessageId == null || userMessageId.isEmpty()) return null;
        if (chatDatabaseBridge != null) {
            StoredMessage message = chatDatabaseBridge.readMessageSync(sessionId, userMessageId);
            if (message == null || !"user".equals(message.getRole())) return null;
            String key = message.getProactiveThreadKey();
            return key == null || key.isEmpty() ? null : key;
        }
        JSONObject row = readConversationRowByMessageId(sessionId, userMessageId);
        if (row == null || !"user".equals(row.optString("role"))) return null;
        String key = row.optString("proactiveThreadKey", "");
        return key.isEmpty() ? null : key;
    }

    public String readConversationMessage(String sessionId, String messageId) {
        if (messageId == null || messageId.isEmpty()) return null;
        if (chatDatabaseBridge != null) {
            StoredMessage message = chatDatabaseBridge.readMessageSync(sessionId, messageId);
            return message != null && "user".equals(message.getRole()) ? message.getText() : null;
        }
        JSONObject row = readConversationRowByMessageId(sessionId, messageId);
        return row != null && "user".equals(row.optString("role"))
                ? row.optString("content", "") : null;
    }

    private JSONObject readConversationRowByMessageId(String sessionId, String messageId) {
        if (sessionId == null || messageId == null || messageId.isEmpty()) return null;
        if (chatDatabaseBridge != null) {
            StoredMessage message = chatDatabaseBridge.readMessageSync(sessionId, messageId);
            return message == null ? null : messageJson(message);
        }
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) return null;
            JSONObject result = null;
            if (snapshot.exists && snapshot.length > 0L) try (java.nio.channels.FileChannel channel =
                    java.nio.channels.FileChannel.open(ledger.toPath(), java.nio.file.StandardOpenOption.READ)) {
                ReverseLedgerLineReader reader = new ReverseLedgerLineReader(channel, snapshot.length);
                String line;
                while ((line = reader.previousLine()) != null) try {
                    JSONObject row = new JSONObject(line);
                    if (isConversationTimelineLedgerRow(row, false)
                            && messageId.equals(row.optString("messageId", ""))) {
                        result = row;
                        break;
                    }
                } catch (Exception ignored) { }
            } catch (Exception error) {
                throw new IllegalStateException("Could not read conversation message", error);
            }
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                return result;
            }
        }
    }

    public synchronized void appendAssistantTranslation(String sessionId, String messageId,
                                                         String language, String translation) {
        if (chatDatabaseBridge != null) {
            requireChatDbSuccess(chatDatabaseBridge.setTranslationSync(
                    sessionId, messageId, language, translation == null ? "" : translation),
                    "store assistant translation");
            return;
        }
        JSONObject row = new JSONObject();
        try {
            row.put("type", "assistant_translation");
            row.put("messageId", messageId);
            row.put("language", language);
            row.put("content", translation == null ? "" : translation);
            row.put("timestamp", System.currentTimeMillis() / 1000.0);
        } catch (Exception error) {
            throw new IllegalStateException("Could not create translation record", error);
        }
        synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
    }

    /** Durable chat-card data so an interrupted choice can be shown as unanswered, never resumed. */
    public synchronized void appendUserDecisionRequest(String sessionId, String decisionId, String title,
                                                        String body, List<UserDecisionOption> options,
                                                        boolean allowDismiss) {
        if (chatDatabaseBridge != null) {
            List<com.jarvys.agent.chatdb.DecisionOptionInput> storedOptions = new ArrayList<>();
            if (options != null) for (UserDecisionOption option : options) storedOptions.add(
                    new com.jarvys.agent.chatdb.DecisionOptionInput(option.getId(), option.getLabel(),
                            option.getDescription(), option.getRole().name().toLowerCase(Locale.ROOT)));
            requireChatDbSuccess(chatDatabaseBridge.appendDecisionRequestSync(
                    sessionId, decisionId, title == null ? "" : title, body == null ? "" : body,
                    allowDismiss, storedOptions), "store decision request");
            return;
        }
        JSONObject row = new JSONObject();
        JSONArray choices = new JSONArray();
        try {
            for (UserDecisionOption option : options) {
                JSONObject item = new JSONObject();
                item.put("id", option.getId());
                item.put("label", option.getLabel());
                item.put("description", option.getDescription());
                item.put("role", option.getRole().name().toLowerCase(Locale.ROOT));
                choices.put(item);
            }
            row.put("type", "user_decision_request");
            row.put("decisionId", decisionId);
            row.put("title", title);
            row.put("body", body);
            row.put("options", choices);
            row.put("allowDismiss", allowDismiss);
            row.put("timestamp", System.currentTimeMillis() / 1000.0);
            synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
        } catch (Exception error) {
            throw new IllegalStateException("Could not persist user decision card", error);
        }
    }

    public synchronized void appendUserDecisionResolution(String sessionId, String decisionId, String status,
                                                           String optionId, String optionLabel) {
        if (chatDatabaseBridge != null) {
            requireChatDbSuccess(chatDatabaseBridge.resolveDecisionSync(
                    sessionId, decisionId, status, optionId, optionLabel), "store decision resolution");
            return;
        }
        JSONObject row = new JSONObject();
        try {
            row.put("type", "user_decision_resolution");
            row.put("decisionId", decisionId);
            row.put("status", status);
            if (optionId != null) row.put("optionId", optionId);
            if (optionLabel != null) row.put("optionLabel", optionLabel);
            row.put("timestamp", System.currentTimeMillis() / 1000.0);
            synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
        } catch (Exception error) {
            throw new IllegalStateException("Could not persist user decision result", error);
        }
    }

    public void appendAssistantRegenerated(String sessionId, String messageId) {
        if (chatDatabaseBridge != null) {
            regenerateAssistantIfLatest(sessionId, messageId);
            return;
        }
        appendMessageMarker(sessionId, "assistant_regenerated", messageId);
    }

    public void appendAssistantDeleted(String sessionId, String messageId) {
        if (chatDatabaseBridge != null) {
            hideAssistantWithOwner(sessionId, messageId, "assistant_deleted");
            return;
        }
        appendMessageMarker(sessionId, "assistant_deleted", messageId);
    }

    public void appendAssistantTranslationHidden(String sessionId, String messageId) {
        if (chatDatabaseBridge != null) {
            requireChatDbSuccess(chatDatabaseBridge.hideTranslationSync(sessionId, messageId), "hide assistant translation");
            return;
        }
        appendMessageMarker(sessionId, "assistant_translation_hidden", messageId);
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
        DeliveredArtifactStore store = new DeliveredArtifactStore(root.getParentFile());
        store.resolve(sessionId, artifact);
        if (chatDatabaseBridge != null) {
            try {
                requireChatDbSuccess(chatDatabaseBridge.appendDeliveredArtifactSync(
                        new com.jarvys.agent.chatdb.DeliveredArtifactInput(sessionId, artifact.id, artifact.name,
                                artifact.mimeType, artifact.sizeBytes, artifact.kind.name(), artifact.relativePath,
                                store.sha256(sessionId, artifact), null, null, null, null, null,
                                System.currentTimeMillis() / 1000.0)), "append delivered artifact");
                return;
            } catch (IOException error) { throw new IllegalStateException("Could not verify delivered artifact", error); }
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) throw new IllegalStateException("This chat has been deleted");
            ChatAttachment existing = findDeliveredFileInSnapshot(snapshot, artifact.id);
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                if (existing != null) {
                    if (!existing.equals(artifact)) throw new IllegalStateException("Delivered artifact metadata differs");
                    return;
                }
                try {
                    JSONObject row = new JSONObject().put("type", "delivered_file").put("artifact", artifact.toJson())
                            .put("timestamp", System.currentTimeMillis() / 1000.0);
                    appendSessionRowLocked(ledger, row);
                    return;
                } catch (Exception failure) { throw new IllegalStateException("Could not persist delivered file", failure); }
            }
        }
    }

    public ChatAttachment findChatFile(String sessionId, String kind, String id) {
        if (sessionId == null || id == null || id.isEmpty()
                || !"delivered".equals(kind) && !"attachment".equals(kind)) return null;
        if (chatDatabaseBridge != null) {
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
        if (chatDatabaseBridge == null) throw noChatDatabase();
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) return null;
            ChatAttachment found = findChatFileInSnapshot(snapshot, sessionId, kind, id);
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                return found;
            }
        }
    }

    private static ChatAttachment findDeliveredFileInSnapshot(LedgerSnapshot snapshot, String id) {
        return findChatFileInSnapshot(snapshot, null, "delivered", id);
    }

    private static ChatAttachment findChatFileInSnapshot(LedgerSnapshot snapshot, String sessionId,
            String kind, String id) {
        if (!snapshot.exists || snapshot.length <= 0L) return null;
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                snapshot.ledger.toPath(), java.nio.file.StandardOpenOption.READ)) {
            ReverseLedgerLineReader reader = new ReverseLedgerLineReader(channel, snapshot.length);
            String line;
            while ((line = reader.previousLine()) != null) try {
                JSONObject row = new JSONObject(line);
                if (!isConversationTimelineLedgerRow(row, false)) continue;
                if ("delivered".equals(kind) && "delivered_file".equals(row.optString("type"))) {
                    ChatAttachment attachment = ChatAttachment.fromJson(row.optJSONObject("artifact"));
                    if (attachment != null && attachment.id.equals(id)) return attachment;
                } else if ("attachment".equals(kind)) {
                    for (ChatAttachment attachment : attachmentsForRow(sessionId, row))
                        if (attachment.id.equals(id)) return attachment;
                }
            } catch (Exception ignored) { }
        } catch (Exception error) {
            throw new IllegalStateException("Could not read chat file ownership", error);
        }
        return null;
    }

    public boolean ownsGeneratedFile(String sessionId, String path) {
        if (sessionId == null || path == null) return false;
        if (chatDatabaseBridge != null) return chatDatabaseBridge.ownsGeneratedFileSync(sessionId, path);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) return false;
            boolean found = false;
            if (snapshot.exists && snapshot.length > 0L) try (java.nio.channels.FileChannel channel =
                    java.nio.channels.FileChannel.open(ledger.toPath(), java.nio.file.StandardOpenOption.READ)) {
                ReverseLedgerLineReader reader = new ReverseLedgerLineReader(channel, snapshot.length);
                String line;
                while ((line = reader.previousLine()) != null) try {
                    JSONObject row = new JSONObject(line);
                    if ("generated_image".equals(row.optString("type"))
                            && "COMPLETED".equals(row.optString("status"))
                            && path.equals(row.optString("imagePath"))) {
                        found = true;
                        break;
                    }
                } catch (Exception ignored) { }
            } catch (Exception error) {
                throw new IllegalStateException("Could not verify generated chat file ownership", error);
            }
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                return found;
            }
        }
    }

    /** Persists an image reference and prompt only; raw image bytes stay in GeneratedImageStore. */
    public synchronized void appendGeneratedImageEvent(String sessionId, String relativePath,
                                                        String prompt, String revisedPrompt,
                                                        String size, String mimeType) {
        File image = new GeneratedImageStore(root.getParentFile()).resolve(sessionId, relativePath);
        if (chatDatabaseBridge != null) {
            try {
                requireChatDbSuccess(chatDatabaseBridge.appendGeneratedImageSync(
                        new com.jarvys.agent.chatdb.GeneratedImageInput(sessionId, prompt == null ? "" : prompt,
                                revisedPrompt == null ? "" : revisedPrompt, size == null ? "" : size,
                                mimeType == null ? "image/png" : mimeType, "COMPLETED", relativePath,
                                fileSha256(image), image.length(), null, System.currentTimeMillis() / 1000.0)),
                        "append generated image");
                return;
            } catch (IOException error) { throw new IllegalStateException("Could not verify generated image", error); }
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        JSONObject row = new JSONObject();
        try {
            row.put("type", "generated_image");
            row.put("imagePath", relativePath);
            row.put("prompt", prompt == null ? "" : prompt);
            row.put("revisedPrompt", revisedPrompt == null ? "" : revisedPrompt);
            row.put("size", size == null ? "" : size);
            row.put("mimeType", mimeType == null ? "image/png" : mimeType);
            row.put("status", "COMPLETED");
            row.put("timestamp", System.currentTimeMillis() / 1000.0);
            synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
        } catch (Exception error) {
            throw new IllegalStateException("Could not persist generated image reference", error);
        }
    }

    public synchronized void appendGeneratedImageFailure(String sessionId, String prompt, String error) {
        if (chatDatabaseBridge != null) {
            requireChatDbSuccess(chatDatabaseBridge.appendGeneratedImageSync(
                    new com.jarvys.agent.chatdb.GeneratedImageInput(sessionId, prompt == null ? "" : prompt,
                            "", "", "image/png", "FAILED", null, null, null,
                            error == null ? "Image generation failed." : error,
                            System.currentTimeMillis() / 1000.0)), "append generated image failure");
            return;
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        JSONObject row = new JSONObject();
        try {
            row.put("type", "generated_image");
            row.put("prompt", prompt == null ? "" : prompt);
            row.put("revisedPrompt", "");
            row.put("size", "");
            row.put("mimeType", "image/png");
            row.put("status", "FAILED");
            row.put("error", error == null ? "Image generation failed." : error);
            row.put("timestamp", System.currentTimeMillis() / 1000.0);
            synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
        } catch (Exception failure) {
            throw new IllegalStateException("Could not persist generated image failure", failure);
        }
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

    public ConversationMetadataStore.Snapshot readConversationMetadata(String sessionId) {
        if (chatDatabaseBridge != null) {
            ConversationMetadataValue value = chatDatabaseBridge.readConversationMetadataSync(sessionId);
            return value == null
                    ? new ConversationMetadataStore.Snapshot(null, false, false, false, false)
                    : new ConversationMetadataStore.Snapshot(value.getTitle(), value.getPinned(),
                            value.getArchived(), value.getDeleted(), value.getPrivateCode());
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return conversationMetadata.read(sessionId);
    }

    public void renameConversation(String sessionId, String title) {
        if (chatDatabaseBridge != null) {
            requireChatDbSuccess(chatDatabaseBridge.renameConversationSync(
                    sessionId, ConversationMetadataStore.normalizeTitle(title)), "rename conversation");
            return;
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        conversationMetadata.update(sessionId, "title", ConversationMetadataStore.normalizeTitle(title));
    }

    public void setConversationPinned(String sessionId, boolean pinned) {
        if (chatDatabaseBridge != null) {
            requireChatDbSuccess(chatDatabaseBridge.updateMetadataSync(sessionId, null, null, pinned, null, null),
                    "update conversation pinned state");
            return;
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        conversationMetadata.update(sessionId, "pinned", pinned);
    }

    public void setConversationArchived(String sessionId, boolean archived) {
        if (chatDatabaseBridge != null) {
            requireChatDbSuccess(chatDatabaseBridge.updateMetadataSync(sessionId, null, null, null, archived, null),
                    "update conversation archived state");
            return;
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        conversationMetadata.update(sessionId, "archived", archived);
    }

    public List<ChatAttachment> readConversationAttachments(String sessionId, String messageId) {
        if (chatDatabaseBridge != null) return chatDatabaseBridge.readMessageAttachmentsSync(sessionId, messageId);
        JSONObject row = readConversationRowByMessageId(sessionId, messageId);
        return row == null ? Collections.emptyList() : attachmentsForRow(sessionId, row);
    }

    public boolean conversationHasAttachments(String sessionId) {
        return conversationContainsPrivateData(sessionId, false);
    }

    public boolean conversationHasPrivateImagesOrAttachments(String sessionId) {
        return conversationContainsPrivateData(sessionId, true);
    }

    private boolean conversationContainsPrivateData(String sessionId, boolean includeGeneratedArtifacts) {
        if (chatDatabaseBridge != null) return includeGeneratedArtifacts
                ? chatDatabaseBridge.conversationHasPrivateImagesOrAttachmentsSync(sessionId)
                : chatDatabaseBridge.conversationHasAttachmentsSync(sessionId);
        if (sessionId == null || conversationMetadata.read(sessionId).deleted) return false;
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null || !snapshot.exists || snapshot.length <= 0L) return false;
            boolean found = false;
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    ledger.toPath(), java.nio.file.StandardOpenOption.READ)) {
                ReverseLedgerLineReader reader = new ReverseLedgerLineReader(channel, snapshot.length);
                String line;
                while ((line = reader.previousLine()) != null) try {
                    JSONObject row = new JSONObject(line);
                    if (!attachmentsForRow(sessionId, row).isEmpty()
                            || includeGeneratedArtifacts && ("generated_image".equals(row.optString("type"))
                                    || "delivered_file".equals(row.optString("type")))) {
                        found = true;
                        break;
                    }
                } catch (Exception ignored) { }
            } catch (Exception error) {
                throw new IllegalStateException("Could not inspect conversation attachments", error);
            }
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                return found;
            }
        }
    }

    public void markConversationHasPrivateCode(String sessionId) {
        if (!conversationMetadata.read(sessionId).privateCode) conversationMetadata.update(sessionId, "privateCode", true);
    }

    public boolean conversationHasPrivateCode(String sessionId) {
        ConversationMetadataStore.Snapshot privacy = conversationMetadata.read(sessionId);
        return privacy.privateCode || privacy.deleted;
    }

    List<ConversationImageReference> readConversationImageReferences(String sessionId) {
        List<ConversationImageReference> empty = Collections.emptyList();
        if (sessionId == null || ProactiveConversation.SESSION_ID.equals(sessionId)
                || ScheduledTaskConversation.SESSION_ID.equals(sessionId)) return empty;
        if (chatDatabaseBridge != null) {
            List<ConversationImageReference> result = new ArrayList<>();
            for (ConversationImageReferenceValue value : chatDatabaseBridge.readConversationImageReferencesSync(sessionId)) {
                ConversationImageReference reference = value.getAttachment() != null
                        ? ConversationImageReference.attachment(value.getMessageId(), value.getAttachment())
                        : ConversationImageReference.generated(value.getMessageId(), value.getGeneratedPath(), value.getPrompt());
                if (reference != null) result.add(reference);
            }
            return Collections.unmodifiableList(result);
        }
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) return empty;
            List<ConversationImageReference> result = new ArrayList<>();
            String userMessageId = "";
            if (snapshot.exists && snapshot.length > 0L) try (BufferedReader reader = snapshotReader(snapshot)) {
                String line;
                while ((line = reader.readLine()) != null) try {
                    JSONObject row = new JSONObject(line);
                    if (!isConversationTimelineLedgerRow(row, false)) continue;
                    if ("user".equals(row.optString("role"))) userMessageId = row.optString("messageId", "");
                    for (ChatAttachment attachment : attachmentsForRow(sessionId, row)) {
                        if (attachment.isImage()) result.add(ConversationImageReference.attachment(userMessageId, attachment));
                    }
                    if ("generated_image".equals(row.optString("type"))
                            && "COMPLETED".equals(row.optString("status"))) {
                        ConversationImageReference reference = ConversationImageReference.generated(
                                userMessageId, row.optString("imagePath", ""), row.optString("prompt", ""));
                        if (reference != null) result.add(reference);
                    }
                } catch (Exception ignored) { }
            } catch (Exception error) {
                throw new IllegalStateException("Could not read conversation image references", error);
            }
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                return Collections.unmodifiableList(result);
            }
        }
    }

    private static List<ChatAttachment> attachmentsForRow(String sessionId, JSONObject row) {
        if (ProactiveConversation.SESSION_ID.equals(sessionId) || ScheduledTaskConversation.SESSION_ID.equals(sessionId)
                || !"user".equals(row.optString("role")) || !row.optString("proactiveThreadKey", "").isEmpty()) {
            return Collections.emptyList();
        }
        return ChatAttachment.fromJsonArray(row.optJSONArray("attachments"));
    }

    public void cleanupOrphanAttachments() {
        if (chatDatabaseBridge != null) {
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
            return;
        }
        throw noChatDatabase();
    }

    private boolean runWasDeleted(String runId) {
        String sessionId = runSessions.get(runId);
        return sessionId != null && conversationMetadata.read(sessionId).deleted;
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
                            ConversationMetadataStore.validateSessionId(id);
                            return id;
                        }
                    } catch (Exception incomplete) { }
                }
            }
            return null;
        } catch (Exception error) { throw new IllegalStateException("Could not safely read run ownership", error); }
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

    private void appendMessageMarker(String sessionId, String type, String messageId) {
        if (messageId == null || messageId.isBlank()) throw new IllegalArgumentException("A message ID is required");
        JSONObject row = new JSONObject();
        try {
            row.put("type", type);
            row.put("messageId", messageId);
            for (JSONObject message : readConversationMessages(sessionId)) {
                if (messageId.equals(message.optString("messageId"))) {
                    String runId = message.optString("runId", "");
                    if (!runId.isEmpty()) row.put("runId", runId);
                    String userMessageId = message.optString("userMessageId", "");
                    if (!userMessageId.isEmpty()) row.put("userMessageId", userMessageId);
                    break;
                }
            }
            row.put("timestamp", System.currentTimeMillis() / 1000.0);
        } catch (Exception error) { throw new IllegalStateException(error); }
        synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
    }

    /** Persist terminal presentation before publishing it to the live timeline; never used as reflection input. */
    public void appendConversationToolPresentation(String sessionId, String userMessageId,
            String displayName, String stage, String callId, String detail, String previewId, String auditDetail) {
        if (!("tool_result".equals(stage) || "tool_error".equals(stage)))
            throw new IllegalArgumentException("A terminal tool presentation is required");
        if (callId == null || callId.isEmpty()) throw new IllegalArgumentException("Tool presentation needs its call ID");
        try {
            MainChatTranscriptStore retained = new MainChatTranscriptStore(filesDirectory(), sessionId, this);
            JSONObject row = new JSONObject().put("type", "tool_presentation").put("schemaVersion", 1)
                    .put("userMessageId", userMessageId == null ? "" : userMessageId)
                    .put("toolName", displayName == null ? "tool" : displayName).put("stage", stage)
                    .put("callId", callId).put("detail", retained.retain(detail == null ? "" : detail))
                    .put("auditDetail", MainChatTranscriptStore.shortText(
                            CrewCheckpointStore.sanitizeText(auditDetail == null ? "" : auditDetail), 4_000))
                    .put("timestamp", System.currentTimeMillis() / 1000.0);
            if (previewId != null) {
                row.put("previewExpected", true);
                if (verifiedPreview(sessionId, previewId) != null) row.put("previewId", previewId);
            }
            if (chatDatabaseBridge != null) {
                requireChatDbSuccess(chatDatabaseBridge.appendToolPresentationSync(
                        new com.jarvys.agent.chatdb.ToolPresentationInput(sessionId,
                                emptyToNull(userMessageId), null, callId,
                                displayName == null ? "tool" : displayName, stage, row.optString("detail"),
                                row.optString("auditDetail"), previewId != null, row.optString("previewId", ""),
                                row.optDouble("timestamp"))), "append tool presentation");
                return;
            }
            appendToolEventWithLatestBatch(sessionId, userMessageId, callId, row);
        } catch (Exception failure) { throw new IllegalStateException("Could not persist tool details and preview", failure); }
    }

    public void appendToolActivity(String sessionId, String userMessageId, ToolActivity activity) {
        try {
            JSONObject value = activity.toJson();
            value.put("detail", new MainChatTranscriptStore(filesDirectory(),sessionId,this).retain(activity.detail));
            value.put("auditDetail", MainChatTranscriptStore.shortText(CrewCheckpointStore.sanitizeText(activity.auditDetail),4000));
            if (chatDatabaseBridge != null) {
                com.jarvys.agent.chatdb.ToolActivityInput input = new com.jarvys.agent.chatdb.ToolActivityInput(
                        sessionId, activity.eventId, emptyToNull(userMessageId), activity.executionId,
                        emptyToNull(activity.callId), activity.toolName, activity.displayName, emptyToNull(activity.skillId),
                        emptyToNull(activity.skillName), activity.stage, value.optString("detail"),
                        emptyToNull(activity.previewId), value.optString("auditDetail"),
                        emptyToNull(activity.reflectionSource), activity.timestampMillis, activity.timestampMillis / 1000.0,
                        null);
                requireChatDbSuccess(chatDatabaseBridge.appendToolActivitySync(input), "append tool activity");
                return;
            }
            File ledger = conversationFile(sessionId);
            JSONObject row = new JSONObject().put("type","tool_activity").put("schemaVersion",1)
                    .put("userMessageId",userMessageId).put("batchId","").put("activity",value)
                    .put("timestamp",activity.timestampMillis/1000.0);
            while (true) {
                ToolActivityBatchLookup lookup = toolActivitySourceBatch(
                        sessionId, userMessageId, activity.executionId, activity.callId);
                row.put("batchId", lookup.resolution.sourceBatch);
                synchronized (SESSION_TITLE_LOCK) {
                    if (!lookup.matches(ledger)) continue;
                    if (conversationMetadata.read(sessionId).deleted)
                        throw new IllegalStateException("This chat has been deleted");
                    ToolActivityBatchCache cache = toolActivityCacheForSnapshotLocked(ledger,
                            lookup.fileIdentity, lookup.length, lookup.lastModified);
                    cache.remember(lookup.pairKey, lookup.resolution);
                    appendSessionRowLocked(ledger,row);
                    cacheToolActivityAfterAppendLocked(ledger, lookup, row);
                    return;
                }
            }
        } catch(Exception failure) { throw new IllegalStateException("Could not persist tool activity",failure); }
    }

    /** Resolves activity ownership newest-first without materializing or parsing the full timeline. */
    private ToolActivityBatchLookup toolActivitySourceBatch(String sessionId, String userMessageId,
            String executionId, String callId) {
        File ledger = conversationFile(sessionId);
        String path = ledger.getAbsolutePath();
        String pair = toolActivityPairKey(userMessageId, executionId, callId);
        Object identity;
        long length;
        long modified;
        synchronized (SESSION_TITLE_LOCK) {
            if (conversationMetadata.read(sessionId).deleted || !ledger.isFile()) {
                identity = fileIdentity(ledger);
                length = ledger.isFile() ? ledger.length() : 0L;
                modified = ledger.isFile() ? ledger.lastModified() : 0L;
                return new ToolActivityBatchLookup(ledger, ledger.isFile(), identity, length, modified,
                        pair, new ActivityBatchResolution("", false));
            }
            identity = fileIdentity(ledger);
            length = ledger.length();
            modified = ledger.lastModified();
            ToolActivityBatchCache cache = toolActivityCacheForSnapshotLocked(ledger, identity, length, modified);
            ActivityBatchResolution cached = pair == null ? null : cache.lookups.get(pair);
            if (cached != null)
                return new ToolActivityBatchLookup(ledger, true, identity, length, modified, pair, cached);
        }

        ActivityBatchResolution discovered = scanToolActivitySourceBatch(
                ledger, length, userMessageId, executionId, callId);
        synchronized (SESSION_TITLE_LOCK) {
            if (!conversationMetadata.read(sessionId).deleted && ledger.isFile()
                    && sameFileIdentity(identity, fileIdentity(ledger))
                    && length == ledger.length() && modified == ledger.lastModified()) {
                ToolActivityBatchCache cache = toolActivityCacheForSnapshotLocked(
                        ledger, identity, length, modified);
                if (pair != null) cache.remember(pair, discovered);
            }
        }
        return new ToolActivityBatchLookup(ledger, true, identity, length, modified, pair, discovered);
    }

    private ActivityBatchResolution scanToolActivitySourceBatch(File ledger, long length,
            String userMessageId, String executionId, String callId) {
        Runnable testHook = toolActivityScanTestHook;
        if (testHook != null) {
            toolActivityScanTestHook = null;
            testHook.run();
        }
        synchronized (LocalRunStore.class) {
            toolActivityLedgerScanCount++;
            if (Thread.holdsLock(SESSION_TITLE_LOCK)) toolActivityScanUnderSessionLockCount++;
        }
        String fallbackBatch = "";
        boolean fallbackFound = false;
        String activityBatch = "";
        boolean activityFound = false;
        if (length > 0L) {
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    ledger.toPath(), java.nio.file.StandardOpenOption.READ)) {
                ReverseLedgerLineReader reader = new ReverseLedgerLineReader(channel, length);
                String line;
                while ((line = reader.previousLine()) != null) {
                    try {
                        JSONObject row = new JSONObject(line);
                        if (!userMessageId.equals(row.optString("userMessageId", ""))) continue;
                        String type = row.optString("type");
                        if ("tool_activity".equals(type)) {
                            JSONObject priorActivity = row.optJSONObject("activity");
                            if (priorActivity != null && executionId != null && !executionId.isEmpty()
                                    && executionId.equals(priorActivity.optString("executionId", ""))) {
                                activityBatch = row.optString("batchId", "");
                                activityFound = true;
                                // A single execution keeps its source batch across all lifecycle rows.
                                // The newest matching activity therefore has the same association as
                                // the full forward scan's first matching activity.
                                break;
                            }
                        } else if ("model_tool_calls".equals(type) && !fallbackFound) {
                            JSONArray calls = row.optJSONArray("calls");
                            if (calls == null) continue;
                            for (int index = 0; index < calls.length(); index++) {
                                JSONObject call = calls.optJSONObject(index);
                                if (call != null && callId.equals(call.optString("id", ""))) {
                                    fallbackBatch = row.optString("batchId", "");
                                    fallbackFound = true;
                                }
                            }
                            if (fallbackFound) {
                                // For a new execution its call row is the nearest ownership
                                // record; older callId reuse belongs to an earlier execution.
                                break;
                            }
                        }
                    } catch (Exception ignored) { }
                }
                synchronized (LocalRunStore.class) { toolActivityLedgerScanBytes += reader.bytesRead(); }
            } catch (Exception error) {
                throw new IllegalStateException("Could not resolve tool activity batch", error);
            }
        }
        return activityFound ? new ActivityBatchResolution(activityBatch, true)
                : new ActivityBatchResolution(fallbackBatch, false);
    }

    private static String toolActivityPairKey(String userMessageId, String executionId, String callId) {
        if (userMessageId == null || userMessageId.isEmpty() || callId == null || callId.isEmpty()) return null;
        return userMessageId + '\u0000' + (executionId == null ? "" : executionId) + '\u0000' + callId;
    }

    private static ToolActivityBatchCache toolActivityCacheForSnapshotLocked(File ledger, Object identity,
            long length, long modified) {
        String path = ledger.getAbsolutePath();
        ToolActivityBatchCache cache = TOOL_ACTIVITY_BATCH_CACHES.get(path);
        if (cache == null || !cache.matches(identity, length, modified)) {
            cache = new ToolActivityBatchCache(path, identity, length, modified);
            TOOL_ACTIVITY_BATCH_CACHES.put(path, cache);
            trimIndexedLedgerCachesLocked();
        }
        return cache;
    }

    private static void cacheToolActivityAfterAppendLocked(File ledger, ToolActivityBatchLookup lookup,
            JSONObject appendedRow) {
        Object identity = fileIdentity(ledger);
        long length = ledger.length();
        long modified = ledger.lastModified();
        ToolActivityBatchCache cache = TOOL_ACTIVITY_BATCH_CACHES.get(ledger.getAbsolutePath());
        if (cache == null || !cache.matches(identity, length, modified)) {
            cache = new ToolActivityBatchCache(ledger.getAbsolutePath(), identity, length, modified);
            TOOL_ACTIVITY_BATCH_CACHES.put(ledger.getAbsolutePath(), cache);
            trimIndexedLedgerCachesLocked();
            if (lookup.pairKey != null) cache.remember(lookup.pairKey, lookup.resolution);
            cache.advanceAppendedRow(appendedRow);
            cache.length = length;
            cache.lastModified = modified;
        } else if (lookup.pairKey != null && !cache.lookups.containsKey(lookup.pairKey)) {
            cache.remember(lookup.pairKey, lookup.resolution);
            cache.advanceAppendedRow(appendedRow);
        }
    }

    private static void trimIndexedLedgerCachesLocked() {
        while (TOOL_BATCH_CACHES.size() > MAX_INDEXED_LEDGER_FILES)
            TOOL_BATCH_CACHES.remove(TOOL_BATCH_CACHES.keySet().iterator().next());
        while (TOOL_ACTIVITY_BATCH_CACHES.size() > MAX_INDEXED_LEDGER_FILES)
            TOOL_ACTIVITY_BATCH_CACHES.remove(TOOL_ACTIVITY_BATCH_CACHES.keySet().iterator().next());
    }

    /** Only reconnect a preview to this chat's canonical existing workspace. Never create a file. */
    private String verifiedPreview(String sessionId, String recordedId) {
        return verifiedPreview(sessionId, recordedId, null);
    }

    private String verifiedPreview(String sessionId, String recordedId, Map<String, ChatAttachment> ownedFiles) {
        if (HtmlPreviewDescriptor.isSnapshotToken(recordedId)) {
            try {
                DeliveredArtifactStore artifacts = new DeliveredArtifactStore(root.getParentFile());
                String id = recordedId.substring(HtmlPreviewDescriptor.TOKEN_PREFIX.length());
                ChatAttachment owner = ownedFiles == null ? findChatFile(sessionId, "delivered", id) : ownedFiles.get(id);
                HtmlPreviewDescriptor preview = owner == null ? null : artifacts.previewMetadataForAttachment(sessionId, owner);
                return preview != null && recordedId.equals(preview.token) ? recordedId : null;
            } catch (Exception unavailable) { return null; }
        }
        String expected = WorkspaceStore.projectIdForSession(sessionId);
        if (recordedId == null || !expected.equals(recordedId)) return null;
        try {
            File workspaces = new File(filesDirectory().getCanonicalFile(), "jarvys/workspaces");
            File project = new File(workspaces, expected);
            if (!project.isDirectory() || !project.getCanonicalFile().equals(project.getAbsoluteFile())) return null;
            File index = new WorkspaceStore(workspaces, expected).resolvePreviewPath("index.html");
            return index.isFile() && index.canRead() ? expected : null;
        } catch (Exception unsafeOrMissing) { return null; }
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
        if (chatDatabaseBridge != null) {
            requireChatDbSuccess(chatDatabaseBridge.appendReflectionToolSync(
                    new com.jarvys.agent.chatdb.ReflectionToolInput(sessionId, emptyToNull(userMessageId), null,
                            emptyToNull(callId), row.optString("toolName"), row.optString("source"),
                            row.optString("stage"), row.optString("marker"), row.optDouble("timestamp"))),
                    "append reflection tool marker");
            return;
        }
        appendToolEventWithLatestBatch(sessionId, userMessageId, callId, row);
    }

    /**
     * Resolves provider call IDs against the most recent owning batch. Reads backward from a
     * locked length snapshot, then publishes the result only if that append-only snapshot remains current.
     */
    private void appendToolEventWithLatestBatch(String sessionId, String userMessageId, String callId,
                                                JSONObject row) {
        File ledger = conversationFile(sessionId);
        while (true) {
            ToolBatchLookup lookup = latestToolBatchId(sessionId, userMessageId, callId);
            row.remove("batchId");
            if (!lookup.batchId.isEmpty()) {
                try { row.put("batchId", lookup.batchId); }
                catch (Exception error) { throw new IllegalStateException("Could not attach tool batch", error); }
            }
            synchronized (SESSION_TITLE_LOCK) {
                if (!lookup.matches(ledger)) continue;
                appendSessionRowLocked(ledger, row);
                return;
            }
        }
    }

    private ToolBatchLookup latestToolBatchId(String sessionId, String userMessageId, String callId) {
        File ledger = conversationFile(sessionId);
        long length;
        long modified;
        Object fileIdentity;
        String path = ledger.getAbsolutePath();
        String pair = toolBatchPairKey(userMessageId, callId);
        synchronized (SESSION_TITLE_LOCK) {
            if (conversationMetadata.read(sessionId).deleted || !ledger.isFile())
                return new ToolBatchLookup(ledger, ledger.isFile(), fileIdentity(ledger),
                        ledger.isFile() ? ledger.length() : 0L, ledger.isFile() ? ledger.lastModified() : 0L, "");
            fileIdentity = fileIdentity(ledger);
            length = ledger.length();
            modified = ledger.lastModified();
            ToolBatchCache cache = TOOL_BATCH_CACHES.get(path);
            if (cache == null || !cache.matches(fileIdentity, length, modified)) {
                cache = new ToolBatchCache(path, fileIdentity, length, modified);
                TOOL_BATCH_CACHES.put(path, cache);
                trimIndexedLedgerCachesLocked();
            }
            if (pair != null && cache.batchByPair.containsKey(pair))
                return new ToolBatchLookup(ledger, true, fileIdentity, length, modified,
                        cache.batchByPair.get(pair));
        }

        LinkedHashMap<String, String> discovered = new LinkedHashMap<>();
        String found = "";
        if (pair != null && length > 0L) {
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    ledger.toPath(), java.nio.file.StandardOpenOption.READ)) {
                ReverseLedgerLineReader reader = new ReverseLedgerLineReader(channel, length);
                String line;
                while ((line = reader.previousLine()) != null) {
                    try {
                        JSONObject row = new JSONObject(line);
                        String batchId = row.optString("batchId", "");
                        if (batchId.isEmpty()) continue;
                        String type = row.optString("type");
                        String owner = row.optString("userMessageId", "");
                        if (owner.isEmpty()) continue;
                        boolean targetFound = false;
                        if ("model_tool_calls".equals(type)) {
                            JSONArray calls = row.optJSONArray("calls");
                            if (calls == null) continue;
                            for (int index = 0; index < calls.length(); index++) {
                                JSONObject call = calls.optJSONObject(index);
                                if (call == null) continue;
                                String discoveredPair = toolBatchPairKey(owner, call.optString("id", ""));
                                if (discoveredPair == null) continue;
                                rememberRecentToolPair(discovered, discoveredPair, batchId);
                                if (pair.equals(discoveredPair)) {
                                    found = discovered.get(pair);
                                    targetFound = true;
                                    break;
                                }
                            }
                        } else if ("tool_activity".equals(type)) {
                            JSONObject activity = row.optJSONObject("activity");
                            if (activity != null) {
                                String discoveredPair = toolBatchPairKey(owner, activity.optString("callId", ""));
                                if (discoveredPair != null) {
                                    rememberRecentToolPair(discovered, discoveredPair, batchId);
                                    targetFound = pair.equals(discoveredPair);
                                    if (targetFound) found = discovered.get(pair);
                                }
                            }
                        }
                        if (targetFound) break;
                    } catch (Exception ignored) { }
                }
            } catch (Exception error) {
                throw new IllegalStateException("Could not find tool call batch", error);
            }
        }
        synchronized (SESSION_TITLE_LOCK) {
            if (!conversationMetadata.read(sessionId).deleted && ledger.isFile()
                    && sameFileIdentity(fileIdentity, fileIdentity(ledger))
                    && length == ledger.length() && modified == ledger.lastModified()) {
                ToolBatchCache cache = TOOL_BATCH_CACHES.get(path);
                if (cache == null || !cache.matches(fileIdentity, length, modified)) {
                    cache = new ToolBatchCache(path, fileIdentity, length, modified);
                    TOOL_BATCH_CACHES.put(path, cache);
                    trimIndexedLedgerCachesLocked();
                }
                cache.merge(discovered);
                if (pair != null) cache.rememberLookup(pair, found);
            }
        }
        return new ToolBatchLookup(ledger, true, fileIdentity, length, modified, found);
    }

    private static void rememberRecentToolPair(LinkedHashMap<String, String> discovered,
            String pair, String batchId) {
        discovered.putIfAbsent(pair, batchId);
        while (discovered.size() > MAX_CACHED_TOOL_PAIRS_PER_FILE)
            discovered.remove(discovered.keySet().iterator().next());
    }

    private static Object fileIdentity(File file) {
        try {
            return java.nio.file.Files.readAttributes(file.toPath(),
                    java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        } catch (Exception ignored) { return null; }
    }

    private static boolean sameFileIdentity(Object expected, Object actual) {
        return expected == null ? actual == null : expected.equals(actual);
    }

    private static String toolBatchPairKey(String userMessageId, String callId) {
        if (userMessageId == null || userMessageId.isEmpty() || callId == null || callId.isEmpty()) return null;
        return userMessageId + '\u0000' + callId;
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
        if (chatDatabaseBridge != null) {
            ReactionUpdate update = requireChatDbSuccess(
                    chatDatabaseBridge.setReactionSync(sessionId, messageId, emoji), "store message reaction");
            return update.getChanged();
        }
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) throw new IllegalArgumentException("Reaction target is not a unique user message in this chat");
            List<JSONObject> rows = readConversationRowsFromSnapshot(snapshot);
            List<JSONObject> messages = new ArrayList<>();
            for (JSONObject row : rows) {
                String role = row.optString("role");
                if (("user".equals(role) || "assistant".equals(role)) && row.has("content"))
                    messages.add(row);
            }
            int matches = 0;
            boolean user = false;
            for (int index = 0; index < messages.size(); index++) {
                JSONObject message = messages.get(index);
                if (MessageReactionTool.stableMessageId(sessionId, message, index).equals(messageId)) {
                    matches++;
                    user = "user".equals(message.optString("role")) && message.optString("proactiveThreadKey").isEmpty();
                }
            }
            String previous = readMessageReactions(rows).getOrDefault(messageId, "");
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                if (matches != 1 || !user)
                    throw new IllegalArgumentException("Reaction target is not a unique user message in this chat");
                if (previous.equals(emoji)) return false;
                JSONObject row = new JSONObject();
                try {
                    row.put("type", "message_reaction");
                    row.put("version", 1);
                    row.put("messageId", messageId);
                    row.put("emoji", emoji);
                    row.put("timestamp", System.currentTimeMillis() / 1000.0);
                } catch (org.json.JSONException error) { throw new IllegalStateException("Could not create reaction record", error); }
                appendSessionRowLocked(ledger, row);
                return true;
            }
        }
    }

    private static Map<String, String> readMessageReactions(List<JSONObject> rows) {
        Map<String, String> reactions = new LinkedHashMap<>();
        for (JSONObject row : rows) {
            if (!"message_reaction".equals(row.optString("type")) || row.optInt("version") != 1
                    || !(row.opt("emoji") instanceof String)) continue;
            String emoji = row.optString("emoji");
            if (emoji.isEmpty() || MessageReactionEmoji.isValid(emoji)) reactions.put(row.optString("messageId"), emoji);
        }
        return reactions;
    }

    public List<JSONObject> readConversationMessages(String sessionId) {
        if (chatDatabaseBridge != null) {
            List<JSONObject> messages = new ArrayList<>();
            for (StoredMessage message : chatDatabaseBridge.readConversationMessagesSync(sessionId))
                messages.add(messageJson(message));
            return messages;
        }
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) return new ArrayList<>();
            List<JSONObject> messages = new ArrayList<>();
            for (JSONObject row : readConversationRowsFromSnapshot(snapshot)) {
                String role = row.optString("role");
                if (("user".equals(role) || "assistant".equals(role)) && row.has("content"))
                    messages.add(row);
            }
            synchronized (SESSION_TITLE_LOCK) {
                if (snapshot.matches(sessionId, ledger, conversationMetadata)) return messages;
            }
        }
    }

    /** Loads model context as the latest summary plus the raw tail after its persisted boundary. */
    /** Durable, inert input. It is not model history or permission until explicitly dispatched. */
    public synchronized String appendInterruptRequest(String sessionId, String text,
            List<String> skills, boolean memoryDisabled) {
        if (!MessageReactionTool.isOrdinaryChat(sessionId) || text == null || text.trim().isEmpty()
                || text.length() > 32000) throw new IllegalArgumentException("Invalid replacement message");
        if (chatDatabaseBridge != null) {
            InterruptRequestValue request = requireChatDbSuccess(chatDatabaseBridge.appendInterruptRequestSync(
                    sessionId, text, skills == null ? Collections.emptyList() : skills, memoryDisabled),
                    "append replacement request");
            return request.getRequestId();
        }
        if (conversationMetadata.read(sessionId).deleted)
            throw new IllegalStateException("This conversation was deleted");
        String id = java.util.UUID.randomUUID().toString();
        try {
            JSONObject row = new JSONObject().put("type", "main_interrupt_request")
                .put("requestId", id).put("content", text).put("skills", new JSONArray(skills))
                .put("memoryDisabled", memoryDisabled).put("timestamp", System.currentTimeMillis() / 1000.0);
            synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
            return id;
        } catch (Exception error) { throw new IllegalStateException("Could not preserve replacement message", error); }
    }

    /** Called only after the old worker has unwound. Duplicate IDs never replay a started turn. */
    JSONObject dispatchInterruptRequest(String sessionId, String requestId) {
        if (chatDatabaseBridge != null) {
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
        if (sessionId == null || requestId == null || conversationMetadata.read(sessionId).deleted) return null;
        File ledger = conversationFile(sessionId);
        while (true) {
            long length;
            long modified;
            Object identity;
            boolean existed;
            synchronized (SESSION_TITLE_LOCK) {
                if (conversationMetadata.read(sessionId).deleted) return null;
                existed = ledger.isFile();
                identity = fileIdentity(ledger);
                length = existed ? ledger.length() : 0L;
                modified = existed ? ledger.lastModified() : 0L;
            }
            JSONObject pending = findPendingInterruptRequest(ledger, requestId, length);
            synchronized (SESSION_TITLE_LOCK) {
                if (conversationMetadata.read(sessionId).deleted) return null;
                if (ledger.isFile() != existed
                        || !sameFileIdentity(identity, fileIdentity(ledger))
                        || (ledger.isFile() && (length != ledger.length() || modified != ledger.lastModified())))
                    continue;
                if (pending == null) return null;
                try {
                    JSONObject user = new JSONObject().put("role", "user").put("messageId", requestId)
                        .put("interruptRequestId", requestId).put("mainChatAcknowledgementRequired", true)
                        .put("content", pending.optString("content"))
                        .put("timestamp", System.currentTimeMillis() / 1000.0);
                    appendSessionRowLocked(ledger, user);
                    return pending;
                } catch (Exception error) { throw new IllegalStateException("Could not dispatch replacement message", error); }
            }
        }
    }

    private static JSONObject findPendingInterruptRequest(File ledger, String requestId, long length) {
        if (length <= 0L) return null;
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                ledger.toPath(), java.nio.file.StandardOpenOption.READ)) {
            ReverseLedgerLineReader reader = new ReverseLedgerLineReader(channel, length);
            String line;
            while ((line = reader.previousLine()) != null) try {
                JSONObject row = new JSONObject(line);
                if (requestId.equals(row.optString("interruptRequestId", ""))) return null;
                if ("main_interrupt_request".equals(row.optString("type"))
                        && requestId.equals(row.optString("requestId", ""))) return row;
            } catch (Exception ignored) { }
        } catch (Exception error) {
            throw new IllegalStateException("Could not find replacement message", error);
        }
        return null;
    }

    /** Durable acknowledgement that an existing user row entered a run transcript. */
    public void markConversationMessagesIncorporated(String sessionId, Iterable<String> messageIds) {
        if (chatDatabaseBridge != null) {
            if (sessionId == null || messageIds == null) return;
            requireChatDbSuccess(chatDatabaseBridge.markInputStateSync(
                    sessionId, copyIds(messageIds), "incorporated"), "acknowledge incorporated chat inputs");
            return;
        }
        if (sessionId == null || messageIds == null || conversationMetadata.read(sessionId).deleted) return;
        List<String> requested = nonEmptyIds(messageIds);
        if (requested.isEmpty()) return;
        Set<String> targets = new HashSet<>(requested);
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) return;
            InputStatus status = readInputStatus(snapshot, targets);
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                JSONArray newlyIncorporated = new JSONArray();
                for (String id : requested) {
                    if (status.userIds.contains(id) && !status.resolved.contains(id)
                            && status.incorporated.add(id)) newlyIncorporated.put(id);
                }
                if (newlyIncorporated.length() == 0) return;
                try {
                    JSONObject row = new JSONObject().put("type", "main_chat_input_incorporated")
                            .put("messageIds", newlyIncorporated)
                            .put("timestamp", System.currentTimeMillis() / 1000.0);
                    appendSessionRowLocked(ledger, row);
                    return;
                } catch (Exception error) {
                    throw new IllegalStateException("Could not persist incorporated chat inputs", error);
                }
            }
        }
    }

    /** Resolves durable chat inputs that will not enter a run transcript. */
    public void markConversationMessagesResolved(String sessionId, Iterable<String> messageIds) {
        if (chatDatabaseBridge != null) {
            if (sessionId == null || messageIds == null) return;
            requireChatDbSuccess(chatDatabaseBridge.markInputStateSync(
                    sessionId, copyIds(messageIds), "resolved"), "resolve chat inputs");
            return;
        }
        if (sessionId == null || messageIds == null || conversationMetadata.read(sessionId).deleted) return;
        List<String> requested = nonEmptyIds(messageIds);
        if (requested.isEmpty()) return;
        Set<String> targets = new HashSet<>(requested);
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) return;
            InputStatus status = readInputStatus(snapshot, targets);
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                JSONArray newlyResolved = new JSONArray();
                for (String id : requested) {
                    if (status.knownIds.contains(id) && !status.incorporated.contains(id)
                            && status.resolved.add(id)) newlyResolved.put(id);
                }
                if (newlyResolved.length() == 0) return;
                try {
                    JSONObject row = new JSONObject().put("type", "main_chat_input_resolved")
                            .put("messageIds", newlyResolved)
                            .put("timestamp", System.currentTimeMillis() / 1000.0);
                    appendSessionRowLocked(ledger, row);
                    return;
                } catch (Exception error) {
                    throw new IllegalStateException("Could not persist resolved chat inputs", error);
                }
            }
        }
    }

    private static void addMessageIds(JSONObject row, Set<String> destination) {
        JSONArray ids = row.optJSONArray("messageIds");
        if (ids != null) for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i, "");
            if (!id.isEmpty()) destination.add(id);
        }
    }

    private static List<String> nonEmptyIds(Iterable<String> values) {
        List<String> ids = new ArrayList<>();
        for (String id : values) if (id != null && !id.isEmpty()) ids.add(id);
        return ids;
    }

    private LedgerSnapshot conversationLedgerSnapshot(String sessionId, File ledger) {
        synchronized (SESSION_TITLE_LOCK) {
            if (conversationMetadata.read(sessionId).deleted) return null;
            boolean exists = ledger.isFile();
            return new LedgerSnapshot(ledger, exists, fileIdentity(ledger),
                    exists ? ledger.length() : 0L, exists ? ledger.lastModified() : 0L);
        }
    }

    /** Reads only the requested message identities while walking backwards from one length snapshot. */
    private static InputStatus readInputStatus(LedgerSnapshot snapshot, Set<String> targets) {
        InputStatus status = new InputStatus();
        if (!snapshot.exists || snapshot.length <= 0L) return status;
        Set<String> sourceRowsFound = new HashSet<>();
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                snapshot.ledger.toPath(), java.nio.file.StandardOpenOption.READ)) {
            ReverseLedgerLineReader reader = new ReverseLedgerLineReader(channel, snapshot.length);
            String line;
            while ((line = reader.previousLine()) != null) try {
                JSONObject row = new JSONObject(line);
                String role = row.optString("role", "");
                String type = row.optString("type", "");
                if ("user".equals(role)) {
                    String id = row.optString("messageId", "");
                    if (!id.isEmpty() && (targets == null || targets.contains(id))) {
                        status.userIds.add(id);
                        status.knownIds.add(id);
                        sourceRowsFound.add(id);
                        if (!row.optString("interruptRequestId", "").isEmpty()
                                && !row.optBoolean("mainChatAcknowledgementRequired", false))
                            status.resolved.add(id);
                    }
                } else if ("main_interrupt_request".equals(type)) {
                    String id = row.optString("requestId", "");
                    if (!id.isEmpty() && (targets == null || targets.contains(id))) {
                        status.knownIds.add(id);
                        sourceRowsFound.add(id);
                    }
                } else if ("main_chat_input_incorporated".equals(type)) {
                    addTargetMessageIds(row, status.incorporated, targets);
                } else if ("main_chat_input_resolved".equals(type)) {
                    addTargetMessageIds(row, status.resolved, targets);
                } else if ("assistant".equals(role)) {
                    String state = row.optString("status", "");
                    String ownerId = row.optString("userMessageId", "");
                    if (("FAILED".equals(state) || "PARTIAL".equals(state) || "STOPPED".equals(state))
                            && !ownerId.isEmpty() && (targets == null || targets.contains(ownerId)))
                        status.resolved.add(ownerId);
                }
                if (targets != null && sourceRowsFound.containsAll(targets)) break;
            } catch (Exception ignored) { }
        } catch (Exception error) {
            throw new IllegalStateException("Could not scan conversation input state", error);
        }
        return status;
    }

    private static void addTargetMessageIds(JSONObject row, Set<String> destination, Set<String> targets) {
        if (targets == null) {
            addMessageIds(row, destination);
            return;
        }
        JSONArray ids = row.optJSONArray("messageIds");
        if (ids != null) for (int index = 0; index < ids.length(); index++) {
            String id = ids.optString(index, "");
            if (!id.isEmpty() && targets.contains(id)) destination.add(id);
        }
    }

    private static List<String> readUserMessageIdsThrough(LedgerSnapshot snapshot, String messageId) {
        if (!snapshot.exists || snapshot.length <= 0L) return Collections.emptyList();
        List<String> reverseIds = new ArrayList<>();
        boolean reachedTarget = false;
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                snapshot.ledger.toPath(), java.nio.file.StandardOpenOption.READ)) {
            ReverseLedgerLineReader reader = new ReverseLedgerLineReader(channel, snapshot.length);
            String line;
            while ((line = reader.previousLine()) != null) try {
                JSONObject row = new JSONObject(line);
                if (!"user".equals(row.optString("role", ""))) continue;
                String id = row.optString("messageId", "");
                if (id.isEmpty()) continue;
                if (!reachedTarget) {
                    if (messageId.equals(id)) {
                        reachedTarget = true;
                        reverseIds.add(id);
                    }
                } else reverseIds.add(id);
            } catch (Exception ignored) { }
        } catch (Exception error) {
            throw new IllegalStateException("Could not scan conversation message range", error);
        }
        if (!reachedTarget) return Collections.emptyList();
        Collections.reverse(reverseIds);
        return reverseIds;
    }

    private static void addResolvedMessageIds(List<JSONObject> rows, Set<String> destination) {
        for (JSONObject row : rows) {
            if ("user".equals(row.optString("role"))) {
                String id = row.optString("messageId", "");
                if (!id.isEmpty() && !row.optString("interruptRequestId", "").isEmpty()
                        && !row.optBoolean("mainChatAcknowledgementRequired", false))
                    destination.add(id);
            } else if ("main_chat_input_resolved".equals(row.optString("type"))) {
                addMessageIds(row, destination);
            } else if ("assistant".equals(row.optString("role"))) {
                String status = row.optString("status", "");
                if ("FAILED".equals(status) || "PARTIAL".equals(status) || "STOPPED".equals(status)) {
                    String ownerId = row.optString("userMessageId", "");
                    if (!ownerId.isEmpty()) destination.add(ownerId);
                }
            }
        }
    }

    /** Acknowledges user rows already present in the loaded context through its current owner. */
    public void markConversationMessagesIncorporatedThrough(String sessionId, String messageId) {
        if (sessionId == null || messageId == null || messageId.isEmpty()) return;
        if (chatDatabaseBridge != null) {
            List<StoredMessage> messages = chatDatabaseBridge.readConversationMessagesSync(sessionId);
            long throughSeq = -1L;
            for (StoredMessage message : messages)
                if (messageId.equals(message.getMessageId())) { throughSeq = message.getSeq(); break; }
            if (throughSeq < 0L) return;
            List<String> idsThrough = new ArrayList<>();
            for (StoredMessage message : messages)
                if (message.getSeq() <= throughSeq && "user".equals(message.getRole()))
                    idsThrough.add(message.getMessageId());
            if (!idsThrough.isEmpty()) markConversationMessagesIncorporated(sessionId, idsThrough);
            return;
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) return;
            List<String> idsThrough = readUserMessageIdsThrough(snapshot, messageId);
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
            }
            if (!idsThrough.isEmpty()) markConversationMessagesIncorporated(sessionId, idsThrough);
            return;
        }
    }

    Set<String> incorporatedConversationMessageIds(String sessionId) {
        if (sessionId == null) return Collections.emptySet();
        if (chatDatabaseBridge != null) return inputStateMessageIds(sessionId, "incorporated", null);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) return Collections.emptySet();
            Set<String> incorporated = readInputStatus(snapshot, null).incorporated;
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                return Collections.unmodifiableSet(incorporated);
            }
        }
    }

    Set<String> resolvedConversationMessageIds(String sessionId) {
        if (sessionId == null) return Collections.emptySet();
        if (chatDatabaseBridge != null) return inputStateMessageIds(sessionId, "resolved", null);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) return Collections.emptySet();
            Set<String> resolved = readInputStatus(snapshot, null).resolved;
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                return Collections.unmodifiableSet(resolved);
            }
        }
    }

    /** Returns only resolved/incorporated state for the supplied recent message identities. */
    Set<String> handledConversationMessageIds(String sessionId, Iterable<String> messageIds) {
        if (sessionId == null || messageIds == null) return Collections.emptySet();
        if (chatDatabaseBridge != null) {
            Set<String> requested = new HashSet<>(nonEmptyIds(messageIds));
            return inputStateMessageIds(sessionId, null, requested);
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        Set<String> targets = new HashSet<>(nonEmptyIds(messageIds));
        if (targets.isEmpty()) return Collections.emptySet();
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) return Collections.emptySet();
            InputStatus status = readInputStatus(snapshot, targets);
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                status.incorporated.addAll(status.resolved);
                return Collections.unmodifiableSet(status.incorporated);
            }
        }
    }

    private Set<String> inputStateMessageIds(String sessionId, String wantedState, Set<String> requestedIds) {
        Set<String> result = new HashSet<>();
        for (StoredMessage message : chatDatabaseBridge.readConversationMessagesSync(sessionId)) {
            if (!"user".equals(message.getRole())) continue;
            if (requestedIds != null && !requestedIds.contains(message.getMessageId())) continue;
            if (wantedState == null ? message.getInputState() != null : wantedState.equals(message.getInputState()))
                result.add(message.getMessageId());
        }
        return Collections.unmodifiableSet(result);
    }

    /** Persists an intermediate assistant turn at its logical point before the steering row already on disk. */
    public synchronized String appendAssistantMessageBefore(String sessionId, String content,
            String runId, String userMessageId, String beforeMessageId) {
        if (beforeMessageId == null || beforeMessageId.isEmpty())
            throw new IllegalArgumentException("An intermediate assistant turn needs its following user message");
        if (chatDatabaseBridge != null) {
            StoredMessage saved = requireChatDbSuccess(chatDatabaseBridge.appendMessageSync(new AppendMessageInput(
                    sessionId, "assistant", Collections.singletonList(new MessagePartInput("text", content == null ? "" : content)),
                    "COMPLETED", emptyToNull(runId), emptyToNull(userMessageId), null, beforeMessageId,
                    null, null, null, null, false, Collections.emptyList(), Collections.emptyList(),
                    System.currentTimeMillis() / 1000.0, conversationKindFor(sessionId)),
                    "append assistant message before steering input"));
            return saved.getMessageId();
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        JSONObject row = new JSONObject();
        String messageId = java.util.UUID.randomUUID().toString();
        try {
            row.put("role", "assistant");
            row.put("content", content == null ? "" : content);
            row.put("messageId", messageId);
            row.put("status", "COMPLETED");
            if (runId != null && !runId.isEmpty()) row.put("runId", runId);
            if (userMessageId != null && !userMessageId.isEmpty()) row.put("userMessageId", userMessageId);
            row.put("beforeMessageId", beforeMessageId);
            row.put("timestamp", System.currentTimeMillis() / 1000.0);
            synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
            return messageId;
        } catch (Exception error) {
            throw new IllegalStateException("Could not persist intermediate assistant message", error);
        }
    }

    /** Unincorporated durable follow-ups, in ledger order, for service recovery after a worker exit. */
    public List<JSONObject> readPendingInterruptRequests(String sessionId) {
        if (chatDatabaseBridge != null) {
            List<JSONObject> pending = new ArrayList<>();
            for (InterruptRequestValue value : chatDatabaseBridge.pendingInterruptRequestsSync(sessionId))
                pending.add(interruptRequestJson(value));
            return Collections.unmodifiableList(pending);
        }
        if (sessionId == null) return Collections.emptyList();
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) return Collections.emptyList();
            List<JSONObject> rows = readConversationRowsFromSnapshot(snapshot);
            Set<String> dispatched = new HashSet<>();
            Set<String> dispatchedRequiringAcknowledgement = new HashSet<>();
            Set<String> incorporated = new HashSet<>();
            Set<String> resolved = new HashSet<>();
            for (JSONObject row : rows) {
                String id = row.optString("interruptRequestId", "");
                if (!id.isEmpty()) {
                    dispatched.add(id);
                    if (row.optBoolean("mainChatAcknowledgementRequired", false))
                        dispatchedRequiringAcknowledgement.add(id);
                }
                if ("main_chat_input_incorporated".equals(row.optString("type"))) {
                    JSONArray ids = row.optJSONArray("messageIds");
                    if (ids != null) for (int i = 0; i < ids.length(); i++) {
                        String incorporatedId = ids.optString(i, "");
                        if (!incorporatedId.isEmpty()) incorporated.add(incorporatedId);
                    }
                }
            }
            addResolvedMessageIds(rows, resolved);
            List<JSONObject> pending = new ArrayList<>();
            for (JSONObject row : rows) {
                if (!"main_interrupt_request".equals(row.optString("type"))) continue;
                String id = row.optString("requestId", "");
                boolean alreadyDispatched = dispatchedRequiringAcknowledgement.contains(id);
                if (id.isEmpty() || resolved.contains(id)
                        || dispatched.contains(id) && !alreadyDispatched
                        || alreadyDispatched && incorporated.contains(id)) continue;
                try { pending.add(new JSONObject(row.toString()).put("sessionId", sessionId)
                        .put("alreadyDispatched", alreadyDispatched)); }
                catch (Exception malformed) { throw new IllegalStateException("Could not read pending chat follow-up", malformed); }
            }
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                return Collections.unmodifiableList(pending);
            }
        }
    }

    /** All undispatched follow-ups, in stable session and ledger order, for service startup recovery. */
    public synchronized List<JSONObject> readPendingInterruptRequests() {
        if (chatDatabaseBridge != null) {
            List<JSONObject> pending = new ArrayList<>();
            for (InterruptRequestValue value : chatDatabaseBridge.pendingInterruptRequestsSync(null))
                pending.add(interruptRequestJson(value));
            return Collections.unmodifiableList(pending);
        }
        List<JSONObject> pending = new ArrayList<>();
        for (String sessionId : listConversationSessionIds()) pending.addAll(readPendingInterruptRequests(sessionId));
        return Collections.unmodifiableList(pending);
    }

    public synchronized JSONObject readInterruptRequest(String sessionId, String requestId) {
        if (chatDatabaseBridge != null) {
            for (JSONObject value : readPendingInterruptRequests(sessionId))
                if (requestId != null && requestId.equals(value.optString("requestId"))) return value;
            return null;
        }
        if (sessionId == null || requestId == null || conversationMetadata.read(sessionId).deleted) return null;
        for (JSONObject row : readConversationRows(sessionId)) {
            if ("main_interrupt_request".equals(row.optString("type"))
                    && requestId.equals(row.optString("requestId"))) return row;
        }
        return null;
    }

    /** UI projection only: the text already exists once in the model's tool-call protocol. */
    public AgentRunUiEvent readAssistantProgress(String sessionId, String firstCallId) {
        if (!MessageReactionTool.isOrdinaryChat(sessionId)) return null;
        if (firstCallId == null || firstCallId.isEmpty()) return null;
        if (chatDatabaseBridge != null) {
            for (JSONObject row : chatDatabaseBridge.readModelTranscriptRowsSync(sessionId)) {
                JSONArray calls = row.optJSONArray("calls");
                if ("model_tool_calls".equals(row.optString("type")) && calls != null
                        && calls.length() > 0 && calls.optJSONObject(0) != null
                        && firstCallId.equals(calls.optJSONObject(0).optString("id")))
                    return assistantProgressEvent(row, 0L);
            }
            return null;
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) return null;
            AgentRunUiEvent result = null;
            if (snapshot.exists && snapshot.length > 0L) try (java.nio.channels.FileChannel channel =
                    java.nio.channels.FileChannel.open(ledger.toPath(), java.nio.file.StandardOpenOption.READ)) {
                ReverseLedgerLineReader reader = new ReverseLedgerLineReader(channel, snapshot.length);
                String line;
                while ((line = reader.previousLine()) != null) try {
                    JSONObject row = new JSONObject(line);
                    JSONArray calls = row.optJSONArray("calls");
                    if ("model_tool_calls".equals(row.optString("type")) && calls != null
                            && calls.length() > 0 && calls.optJSONObject(0) != null
                            && firstCallId.equals(calls.optJSONObject(0).optString("id"))) {
                        result = assistantProgressEvent(row, 0L);
                        break;
                    }
                } catch (Exception ignored) { }
            } catch (Exception error) {
                throw new IllegalStateException("Could not read assistant progress", error);
            }
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                return result;
            }
        }
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
        if (chatDatabaseBridge != null) return chatDatabaseBridge.loadConversationContextSync(sessionId);
        List<JSONObject> rows = readConversationRowsFromValidatedSnapshot(sessionId);
        List<JSONObject> messages = new ArrayList<>();
        JSONObject latestCompaction = null;
        int latestCompactionRow = -1;
        int latestAssistantInvalidationRow = -1;
        Set<String> hidden = hiddenAssistantIds(rows);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            JSONObject row = rows.get(rowIndex);
            String type = row.optString("type");
            String role = row.optString("role");
            if (("user".equals(role) || "assistant".equals(role)) && row.has("content")) messages.add(row);
            else if ("compaction".equals(type)) {
                latestCompaction = row;
                latestCompactionRow = rowIndex;
            } else if ("assistant_regenerated".equals(type) || "assistant_deleted".equals(type)) {
                latestAssistantInvalidationRow = rowIndex;
            }
        }
        // A prior summary may contain the now-replaced response. Rebuild from raw active rows
        // until a newer compaction is appended from the filtered context.
        if (latestAssistantInvalidationRow > latestCompactionRow) latestCompaction = null;
        List<ConversationTurn> context = new ArrayList<>();
        int firstKept = 0;
        if (latestCompaction != null) {
            firstKept = Math.max(0, Math.min(messages.size(), latestCompaction.optInt("firstKept", 0)));
            String summary = latestCompaction.optString("summary", "");
            if (!summary.isEmpty()) context.add(ConversationTurn.compactionSummary(summary,
                    latestCompaction.optInt("summarizedMessages", firstKept)));
        }
        Map<Integer, List<ConversationTurn>> toolGroups = MainChatTranscriptStore.restore(
                rows, firstKept, latestCompaction == null ? -1 : latestCompactionRow);
        Map<String, List<ModelReply.ProviderOutputItem>> providerOutputItems =
                MainChatTranscriptStore.restoreProviderOutputItems(rows, firstKept,
                        latestCompaction == null ? -1 : latestCompactionRow);
        int messageIndex = 0;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            JSONObject row = rows.get(rowIndex);
            String role = row.optString("role");
            if (("user".equals(role) || "assistant".equals(role)) && row.has("content")) {
                int index = messageIndex++;
                if (index < firstKept || ("assistant".equals(role) && hidden.contains(row.optString("messageId", "")))) continue;
                if (isAssistantRunStatusRow(row)) continue;
                List<ModelReply.ProviderOutputItem> assistantItems = "assistant".equals(role)
                        ? providerOutputItems.get(row.optString("userMessageId")) : null;
                if (assistantItems != null && !assistantItems.isEmpty())
                    context.add(ConversationTurn.assistantReply(row.optString("content", ""), index, assistantItems));
                else context.add(ConversationTurn.messageWithAttachments(role, row.optString("content", ""), index,
                        attachmentsForRow(sessionId, row)));
            } else if (toolGroups.containsKey(rowIndex)) {
                for (ConversationTurn turn : toolGroups.get(rowIndex)) {
                    if (turn.kind != ConversationTurn.Kind.MODEL_ERROR) context.add(turn);
                }
            }
        }
        return context;
    }

    private static boolean isAssistantRunStatusRow(JSONObject row) {
        if (!"assistant".equals(row.optString("role"))) return false;
        String status = row.optString("status", "").trim().toUpperCase(Locale.ROOT);
        return "STOPPED".equals(status) || "PARTIAL".equals(status) || "FAILED".equals(status)
                || "ERROR".equals(status) || "MODEL_ERROR".equals(status)
                || "TIMED_OUT".equals(status) || "TIMEOUT".equals(status)
                || "INTERRUPTED".equals(status) || "ABORTED".equals(status) || "CANCELLED".equals(status);
    }

    public int latestUserMessageIndex(String sessionId) {
        List<JSONObject> messages = readConversationMessages(sessionId);
        for (int index = messages.size() - 1; index >= 0; index--) {
            if ("user".equals(messages.get(index).optString("role"))) return index;
        }
        return -1;
    }

    public String latestUserMessageId(String sessionId) {
        if (sessionId == null) return "";
        if (chatDatabaseBridge != null) {
            String id = chatDatabaseBridge.latestUserMessageIdSync(sessionId);
            return id == null ? "" : id;
        }
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null || !snapshot.exists || snapshot.length <= 0L) return "";
            String result = "";
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    ledger.toPath(), java.nio.file.StandardOpenOption.READ)) {
                ReverseLedgerLineReader reader = new ReverseLedgerLineReader(channel, snapshot.length);
                String line;
                while ((line = reader.previousLine()) != null) try {
                    JSONObject row = new JSONObject(line);
                    if (!isResultOnlyRow(row) && "user".equals(row.optString("role", ""))
                            && row.has("content")) {
                        result = row.optString("messageId", "");
                        break;
                    }
                } catch (Exception ignored) { }
            } catch (Exception error) {
                throw new IllegalStateException("Could not find latest user message", error);
            }
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                return result;
            }
        }
    }

    File filesDirectory() { return root.getParentFile().getParentFile(); }

    synchronized List<JSONObject> readModelTranscriptRows(String sessionId) {
        if (chatDatabaseBridge != null) return chatDatabaseBridge.readModelTranscriptRowsSync(sessionId);
        List<JSONObject> modelRows = new ArrayList<>();
        for (JSONObject row : readConversationRows(sessionId))
            if (row.optString("type").startsWith("model_")) modelRows.add(row);
        return modelRows;
    }

    synchronized void appendModelTranscriptRow(String sessionId, JSONObject row) {
        String type = row.optString("type");
        if (!("model_tool_calls".equals(type) || "model_tool_started".equals(type)
                || "model_tool_not_started".equals(type)
                || "model_tool_result".equals(type) || "model_artifact".equals(type)
                || "model_error".equals(type) || "model_provider_output_items".equals(type)) || row.has("role"))
            throw new IllegalArgumentException("Invalid model transcript record");
        if (chatDatabaseBridge != null) {
            appendModelTranscriptRowToDatabase(sessionId, row, type);
            return;
        }
        synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
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
                            call.optString("id"), call.optString("name"), arguments.toString()));
                }
                String provider = row.optString("providerOutputItems", "");
                if (provider.isEmpty()) provider = null;
                com.jarvys.agent.chatdb.ModelToolBatchInput batch = new com.jarvys.agent.chatdb.ModelToolBatchInput(
                        sessionId, row.optString("batchId"), emptyToNull(row.optString("userMessageId")),
                        row.optLong("messageIndex"), row.optString("assistantText", ""),
                        row.optBoolean("assistantProgress", true), calls, null, null, provider, time);
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
                        emptyToNull(row.optString("artifactId")), time);
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
                        artifact.optLong("chars"), artifact.optLong("bytes"), artifact.optString("mimeType", "text/plain")),
                        "append model artifact");
                return;
            }
            default: throw new IllegalArgumentException("Unsupported model transcript record");
        }
    }

    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }

    static boolean isResultOnlyRow(JSONObject row) {
        String type = row.optString("type", "");
        return type.startsWith("crew_coordination_") || type.startsWith("workspace_coordination_")
                || row.has("crewCoordinationSchemaVersion") || row.has("workspaceCoordinationSchemaVersion")
                || row.has("workspaceCoordinationReplySchemaVersion") || row.has("replyId")
                || type.startsWith("workspace_input_") || row.has("workspaceInputIncorporationSchemaVersion")
                || row.has("receiptId")
                || type.startsWith("crew_result_") || type.startsWith("workspace_result_")
                || row.has("crewResultSchemaVersion") || row.has("workspaceResultSchemaVersion")
                || row.has("evidenceId") || row.has("referenceId");
    }

    private List<JSONObject> readConversationRows(String sessionId) { return readConversationRows(sessionId, false); }

    private List<JSONObject> readConversationRows(String sessionId, boolean includeResultNotices) {
        List<JSONObject> rows = readConversationRowsInLedgerOrder(sessionId, includeResultNotices);
        orderAssistantMessagesBeforeSteering(rows);
        return rows;
    }

    /** Reads a point-in-time conversation projection without holding the append lock during file IO. */
    private List<JSONObject> readConversationRowsFromValidatedSnapshot(String sessionId) {
        return readConversationRowsFromValidatedSnapshot(sessionId, false, true);
    }

    private List<JSONObject> readConversationRowsFromValidatedSnapshot(String sessionId,
            boolean includeResultNotices, boolean orderBeforeSteering) {
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) return new ArrayList<>();
            List<JSONObject> rows = readConversationRowsFromSnapshot(snapshot, includeResultNotices);
            if (orderBeforeSteering) orderAssistantMessagesBeforeSteering(rows);
            synchronized (SESSION_TITLE_LOCK) {
                if (snapshot.matches(sessionId, ledger, conversationMetadata)) return rows;
            }
        }
    }

    private List<JSONObject> readConversationRowsInLedgerOrder(String sessionId, boolean includeResultNotices) {
        return readConversationRowsFromValidatedSnapshot(sessionId, includeResultNotices, false);
    }

    /** Full conversation projection read outside the append lock and tied to one validated snapshot. */
    private List<JSONObject> readConversationRowsFromSnapshot(LedgerSnapshot snapshot) {
        return readConversationRowsFromSnapshot(snapshot, false);
    }

    private List<JSONObject> readConversationRowsFromSnapshot(LedgerSnapshot snapshot,
            boolean includeResultNotices) {
        List<JSONObject> rows = new ArrayList<>();
        if (!snapshot.exists || snapshot.length <= 0L) return rows;
        synchronized (LocalRunStore.class) { conversationRowsFullParseCount++; }
        try (BufferedReader reader = snapshotReader(snapshot)) {
            String line;
            while ((line = reader.readLine()) != null) try {
                JSONObject row = new JSONObject(line);
                if (isConversationTimelineLedgerRow(row, includeResultNotices)) rows.add(row);
            } catch (Exception ignored) { }
        } catch (Exception error) {
            throw new IllegalStateException("Could not read conversation ledger snapshot", error);
        }
        return rows;
    }

    /** Visits eligible ledger rows one at a time and keeps memory bounded to the current line. */
    private static void streamConversationRowsFromSnapshot(LedgerSnapshot snapshot,
            boolean includeResultNotices, Consumer<JSONObject> visitor) {
        if (!snapshot.exists || snapshot.length <= 0L) return;
        try (BufferedReader reader = snapshotReader(snapshot)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JSONObject row;
                try {
                    row = new JSONObject(line);
                    if (!isConversationTimelineLedgerRow(row, includeResultNotices)) continue;
                } catch (Exception ignored) { continue; }
                visitor.accept(row);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not stream conversation ledger snapshot", error);
        }
    }

    private static BufferedReader snapshotReader(LedgerSnapshot snapshot) throws IOException {
        return new BufferedReader(new InputStreamReader(new BoundedInputStream(
                new FileInputStream(snapshot.ledger), snapshot.length), StandardCharsets.UTF_8));
    }

    /** Prevents a concurrent append from extending a read past its captured ledger length. */
    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream input, long limit) {
            super(input);
            remaining = Math.max(0L, limit);
        }

        @Override public int read() throws IOException {
            if (remaining <= 0L) return -1;
            int value = in.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining <= 0L) return -1;
            int requested = (int) Math.min((long) length, remaining);
            int count = in.read(buffer, offset, requested);
            if (count > 0) remaining -= count;
            return count;
        }
    }

    private static List<Integer> timelineMessageIndices(List<JSONObject> rows, int firstMessageIndex) {
        List<Integer> indices = new ArrayList<>(rows.size());
        int messageIndex = firstMessageIndex;
        for (JSONObject row : rows) {
            String role = row.optString("role");
            if (("user".equals(role) || "assistant".equals(role)) && row.has("content")) {
                indices.add(messageIndex++);
            } else {
                indices.add(-1);
            }
        }
        return indices;
    }

    /** Shared allowlist rule for result-only rows in full and paged conversation projections. */
    private static boolean isConversationTimelineLedgerRow(JSONObject row, boolean includeResultNotices) {
        if (!isResultOnlyRow(row)) return true;
        return includeResultNotices && isEligibleWorkspaceResultNoticeRow(row);
    }

    /** A workspace notice is eligible only in its inert, role-less and content-less ledger form. */
    private static boolean isEligibleWorkspaceResultNoticeRow(JSONObject row) {
        return "workspace_result_notice".equals(row.optString("type"))
                && !row.has("role") && !row.has("content");
    }

    private static void orderAssistantMessagesBeforeSteering(List<JSONObject> rows) {
        for (JSONObject row : new ArrayList<>(rows)) {
            if (!"assistant".equals(row.optString("role"))) continue;
            String beforeMessageId = row.optString("beforeMessageId", "");
            if (beforeMessageId.isEmpty()) continue;
            int source = rows.indexOf(row);
            int target = -1;
            for (int index = 0; index < rows.size(); index++) {
                if (beforeMessageId.equals(rows.get(index).optString("messageId", ""))) {
                    target = index;
                    break;
                }
            }
            if (source < 0 || target < 0 || source == target) continue;
            rows.remove(source);
            if (source < target) target--;
            rows.add(target, row);
        }
    }

    private static Set<String> hiddenAssistantIds(List<JSONObject> rows) {
        Set<String> hidden = new HashSet<>();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            JSONObject row = rows.get(rowIndex);
            String type = row.optString("type");
            if ("assistant_regenerated".equals(type) || "assistant_deleted".equals(type)) {
                String id = row.optString("messageId", "");
                if (!id.isEmpty()) hidden.add(id);
            }
        }
        return hidden;
    }

    private static Set<String> hiddenTranslationIds(List<JSONObject> rows) {
        Set<String> hidden = new HashSet<>();
        for (JSONObject row : rows) {
            if (!"assistant_translation_hidden".equals(row.optString("type"))) continue;
            String id = row.optString("messageId", "");
            if (!id.isEmpty()) hidden.add(id);
        }
        return hidden;
    }

    public synchronized List<JSONObject> readActiveConversationMessages(String sessionId) {
        if (chatDatabaseBridge != null) {
            List<JSONObject> active = new ArrayList<>();
            for (StoredMessage message : chatDatabaseBridge.readActiveConversationMessagesSync(sessionId))
                active.add(messageJson(message));
            return active;
        }
        List<JSONObject> rows = readConversationRows(sessionId);
        Set<String> hidden = hiddenAssistantIds(rows);
        List<JSONObject> active = new ArrayList<>();
        for (JSONObject row : rows) {
            String role = row.optString("role");
            if (("user".equals(role) || "assistant".equals(role))
                    && !("assistant".equals(role) && hidden.contains(row.optString("messageId", "")))) {
                active.add(row);
            }
        }
        return active;
    }

    /** Durable steering inputs appended after the user message that started the active run. */
    public List<JSONObject> readUserMessagesAfter(String sessionId, String messageId) {
        if (sessionId == null || messageId == null || messageId.isEmpty()) return Collections.emptyList();
        if (chatDatabaseBridge != null) {
            List<JSONObject> later = new ArrayList<>();
            StoredMessage cursor = chatDatabaseBridge.readMessageSync(sessionId, messageId);
            if (cursor == null) return Collections.emptyList();
            for (StoredMessage message : chatDatabaseBridge.readConversationMessagesSync(sessionId))
                if (message.getSeq() > cursor.getSeq() && "user".equals(message.getRole()))
                    later.add(messageJson(message));
            return Collections.unmodifiableList(later);
        }
        File ledger = conversationFile(sessionId);
        while (true) {
            LedgerSnapshot snapshot = conversationLedgerSnapshot(sessionId, ledger);
            if (snapshot == null) return Collections.emptyList();
            List<JSONObject> reversePending = new ArrayList<>();
            boolean cursorFound = false;
            if (snapshot.exists && snapshot.length > 0L) try (java.nio.channels.FileChannel channel =
                    java.nio.channels.FileChannel.open(ledger.toPath(), java.nio.file.StandardOpenOption.READ)) {
                ReverseLedgerLineReader reader = new ReverseLedgerLineReader(channel, snapshot.length);
                String line;
                while ((line = reader.previousLine()) != null) try {
                    JSONObject row = new JSONObject(line);
                    if (!isConversationTimelineLedgerRow(row, false)) continue;
                    if (messageId.equals(row.optString("messageId", ""))) {
                        cursorFound = true;
                        break;
                    }
                    if ("user".equals(row.optString("role")) && row.has("content"))
                        reversePending.add(row);
                } catch (Exception ignored) { }
                synchronized (LocalRunStore.class) {
                    userMessagesAfterScanCount++;
                    userMessagesAfterScanBytes += reader.bytesRead();
                    if (Thread.holdsLock(SESSION_TITLE_LOCK)) userMessagesAfterScanUnderSessionLockCount++;
                }
            } catch (Exception error) {
                throw new IllegalStateException("Could not read durable chat follow-ups", error);
            }
            synchronized (SESSION_TITLE_LOCK) {
                if (!snapshot.matches(sessionId, ledger, conversationMetadata)) continue;
                if (!cursorFound) return Collections.emptyList();
                Collections.reverse(reversePending);
                return Collections.unmodifiableList(reversePending);
            }
        }
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
        if (chatDatabaseBridge != null) {
            com.jarvys.agent.chatdb.ReflectionPayloadSnapshot value =
                    chatDatabaseBridge.buildReflectionPayloadSync(sessionId, emptyToNull(throughMessageId), replayAll);
            return new ReflectionPayload(value.getText(), value.getEndMessageId(), value.getCompactionIds(),
                    value.getCompletedAssistantSteps(), value.getUserCharacters(), value.getMessageCount(),
                    value.getHasMoreMessages());
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        List<JSONObject> rows = readConversationRows(sessionId);
        List<JSONObject> messages = new ArrayList<>();
        Map<String, Integer> indexById = new LinkedHashMap<>();
        Map<JSONObject, Integer> indexByRow = new java.util.IdentityHashMap<>();
        Map<String, Integer> rowIndexByMessageId = new LinkedHashMap<>();
        Set<String> hidden = hiddenAssistantIds(rows);
        JSONObject latestCheckpoint = null;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            JSONObject row = rows.get(rowIndex);
            String role = row.optString("role");
            if ("user".equals(role) || "assistant".equals(role)) {
                int index = messages.size();
                JSONObject copy;
                try { copy = new JSONObject(row.toString()); }
                catch (Exception error) { throw new IllegalStateException("Could not copy reflection transcript row", error); }
                if (copy.optString("messageId").isEmpty()) {
                    try { copy.put("messageId", "legacy-" + index); } catch (Exception ignored) { }
                }
                messages.add(copy);
                indexByRow.put(row, index);
                indexById.put(copy.optString("messageId"), index);
                rowIndexByMessageId.put(copy.optString("messageId"), rowIndex);
            } else if ("reflection_checkpoint".equals(row.optString("type"))
                    || "reflection_commit".equals(row.optString("type"))) {
                latestCheckpoint = row;
            }
        }
        int checkpointIndex = replayAll ? -1 : latestCheckpoint == null ? -1
                : indexById.getOrDefault(latestCheckpoint.optString("throughMessageId", ""), -1);
        int maxRowIndex = throughMessageId == null || throughMessageId.isEmpty()
                ? rows.size() - 1 : rowIndexByMessageId.getOrDefault(throughMessageId, -1);
        int endIndex = -1;
        for (int index = messages.size() - 1; index >= 0; index--) {
            JSONObject message = messages.get(index);
            if (rowIndexByMessageId.getOrDefault(message.optString("messageId"), Integer.MAX_VALUE) <= maxRowIndex
                    && !("assistant".equals(message.optString("role")) && hidden.contains(message.optString("messageId")))) {
                endIndex = index;
                break;
            }
        }
        String endMessageId = endIndex < 0 ? "" : messages.get(endIndex).optString("messageId", "");
        if (endIndex <= checkpointIndex) {
            return new ReflectionPayload("[]", "", java.util.Collections.emptyList(), 0, 0, 0, false);
        }
        Set<String> connectorDerivedUsers = new HashSet<>();
        for (int rowIndex = 0; rowIndex <= maxRowIndex && rowIndex < rows.size(); rowIndex++) {
            JSONObject row = rows.get(rowIndex);
            if (!"reflection_tool".equals(row.optString("type"))) continue;
            String source = row.optString("source", "");
            if (source.startsWith("connector") || "mcp".equals(source) || "delegate".equals(source) || "web".equals(source)) {
                String userId = row.optString("userMessageId", "");
                if (!userId.isEmpty()) connectorDerivedUsers.add(userId);
            }
        }

        List<ReflectionTranscriptBuilder.Entry> transcript = new ArrayList<>();
        for (int rowIndex = 0; rowIndex <= maxRowIndex && rowIndex < rows.size(); rowIndex++) {
            JSONObject row = rows.get(rowIndex);
            String role = row.optString("role");
            if ("user".equals(role) || "assistant".equals(role)) {
                String id = row.optString("messageId", "");
                int index = indexByRow.getOrDefault(row, -1);
                if (id.isEmpty() && index >= 0) id = "legacy-" + index;
                if (index <= checkpointIndex || index > endIndex) continue;
                if ("assistant".equals(role) && hidden.contains(id)) continue;
                String text = row.optString("content", "");
                if ("assistant".equals(role) && connectorDerivedUsers.contains(row.optString("userMessageId", ""))) {
                    text = "[assistant response following connector/MCP data omitted; use user-authored confirmation only]";
                }
                String safe = ReflectionTranscriptBuilder.sanitize(text);
                transcript.add(new ReflectionTranscriptBuilder.Entry(role, safe,
                        "assistant".equals(role) ? "context_only" : "user_authored",
                        id, reflectionTimestamp(row), "", "", true));
            } else if ("reflection_tool".equals(row.optString("type"))) {
                String userId = row.optString("userMessageId", "");
                int userIndex = indexById.getOrDefault(userId, -1);
                if (userIndex <= checkpointIndex || userIndex > endIndex) continue;
                transcript.add(new ReflectionTranscriptBuilder.Entry("tool_call", row.optString("marker", "[tool result omitted]"),
                        row.optString("source", "tool"), userId, reflectionTimestamp(row),
                        row.optString("toolName", "tool"), row.optString("callId", ""),
                        "tool_result".equals(row.optString("stage"))));
            }
        }
        String serialized = ReflectionTranscriptBuilder.build(transcript);
        String includedEndMessageId = ReflectionTranscriptBuilder.lastMessageId(serialized);
        int includedEndIndex = indexById.getOrDefault(includedEndMessageId, -1);
        if (includedEndIndex < 0) {
            return new ReflectionPayload(serialized, "", java.util.Collections.emptyList(), 0, 0, 0, endIndex >= 0);
        }
        boolean hasMoreMessages = includedEndIndex < endIndex;
        int userCharacters = 0;
        int messageCount = 0;
        int completedSteps = 0;
        for (int index = checkpointIndex + 1; index <= includedEndIndex; index++) {
            JSONObject message = messages.get(index);
            String role = message.optString("role");
            if ("assistant".equals(role) && hidden.contains(message.optString("messageId", ""))) continue;
            String text = ReflectionTranscriptBuilder.sanitize(message.optString("content", ""));
            if ("user".equals(role) && !text.startsWith("[message omitted:")) userCharacters += text.length();
            if ("assistant".equals(role) && (message.optString("status").isEmpty()
                    || "COMPLETED".equals(message.optString("status")))) completedSteps++;
            messageCount++;
        }
        int checkpointRowIndex = rowIndexByMessageId.getOrDefault(includedEndMessageId, -1);
        int compactionBoundary = hasMoreMessages ? checkpointRowIndex + 1 : maxRowIndex + 1;
        List<String> compacted = checkpointRowIndex < 0 ? java.util.Collections.emptyList()
                : pendingCompactionIds(rows.subList(0, Math.max(0, Math.min(rows.size(), compactionBoundary))));
        return new ReflectionPayload(serialized, includedEndMessageId, compacted,
                completedSteps, userCharacters, messageCount, hasMoreMessages);
    }

    public synchronized List<String> listConversationSessionIds() {
        if (chatDatabaseBridge != null) {
            List<String> ids = new ArrayList<>();
            for (ConversationSummary summary : chatDatabaseBridge.listAllConversationsSync(Collections.emptySet()))
                if (!summary.getDeleted()) ids.add(summary.getId());
            return ids;
        }
        throw noChatDatabase();
    }

    public synchronized void appendReflectionCheckpoint(String sessionId, ReflectionPayload payload,
                                                        String reflectionId, String result) {
        if (payload == null || payload.endMessageId.isEmpty()) throw new IllegalArgumentException("Reflection checkpoint has no message boundary");
        if (chatDatabaseBridge != null) {
            requireChatDbSuccess(chatDatabaseBridge.appendReflectionCheckpointSync(
                    new com.jarvys.agent.chatdb.ReflectionInput(sessionId, "reflection_checkpoint", reflectionId,
                            payload.endMessageId, (long) payload.completedAssistantSteps, result, null, null, null,
                            Collections.emptyList(), payload.compactionIds, System.currentTimeMillis() / 1000.0)),
                    "append reflection checkpoint");
            return;
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        JSONObject row = new JSONObject();
        try {
            row.put("type", "reflection_checkpoint");
            row.put("throughMessageId", payload.endMessageId);
            row.put("reflectionId", reflectionId);
            row.put("completedAssistantSteps", payload.completedAssistantSteps);
            row.put("result", result == null ? "" : result);
            row.put("acknowledgedCompactionIds", new JSONArray(payload.compactionIds));
            row.put("timestamp", System.currentTimeMillis() / 1000.0);
        } catch (Exception error) { throw new IllegalStateException("Could not create reflection checkpoint", error); }
        synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
    }

    /** One append-only record commits a reflection checkpoint, learned-summary row, and group identity together. */
    public synchronized void appendReflectionCommit(String sessionId, ReflectionPayload payload, String reflectionId,
                                                    String summary, List<Long> revisionIds, String status, String trigger) {
        if (payload == null || payload.endMessageId.isEmpty()) throw new IllegalArgumentException("Reflection checkpoint has no message boundary");
        if (chatDatabaseBridge != null) {
            requireChatDbSuccess(chatDatabaseBridge.appendReflectionCommitSync(
                    new com.jarvys.agent.chatdb.ReflectionInput(sessionId, "reflection_commit", reflectionId,
                            payload.endMessageId, (long) payload.completedAssistantSteps, null,
                            summary == null ? "" : summary, status == null ? "completed" : status,
                            trigger == null ? "manual" : trigger,
                            revisionIds == null ? Collections.emptyList() : revisionIds,
                            payload.compactionIds, System.currentTimeMillis() / 1000.0)), "append reflection commit");
            return;
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        JSONObject row = new JSONObject();
        try {
            row.put("type", "reflection_commit");
            row.put("throughMessageId", payload.endMessageId);
            row.put("reflectionId", reflectionId);
            row.put("summary", summary == null ? "" : summary);
            row.put("status", status == null ? "completed" : status);
            row.put("trigger", trigger == null ? "manual" : trigger);
            row.put("completedAssistantSteps", payload.completedAssistantSteps);
            row.put("acknowledgedCompactionIds", new JSONArray(payload.compactionIds));
            JSONArray ids = new JSONArray();
            if (revisionIds != null) for (Long revisionId : revisionIds) if (revisionId != null) ids.put(revisionId);
            row.put("revisionIds", ids);
            row.put("timestamp", System.currentTimeMillis() / 1000.0);
        } catch (Exception error) { throw new IllegalStateException("Could not create reflection commit", error); }
        synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
    }

    public synchronized void advanceReflectionCheckpoint(String sessionId, String reason) {
        advanceReflectionCheckpoint(sessionId, reason, null);
    }

    public synchronized void advanceReflectionCheckpoint(String sessionId, String reason, String throughMessageId) {
        ReflectionPayload payload = buildReflectionPayload(sessionId, throughMessageId);
        if (chatDatabaseBridge != null) {
            if (payload.endMessageId.isEmpty()) {
                if (!payload.compactionIds.isEmpty()) appendCompactionReflectionAcknowledgement(sessionId, payload.compactionIds);
                return;
            }
            appendReflectionCheckpoint(sessionId, payload, "skipped", reason);
            return;
        }
        if (payload.endMessageId.isEmpty()) {
            if (!payload.compactionIds.isEmpty()) appendCompactionReflectionAcknowledgement(sessionId, payload.compactionIds);
            return;
        }
        appendReflectionCheckpoint(sessionId, payload, "skipped", reason);
    }

    public synchronized void appendReflectionEvent(String sessionId, String reflectionId, String summary,
                                                   List<Long> revisionIds, String status) {
        if (chatDatabaseBridge != null) {
            requireChatDbSuccess(chatDatabaseBridge.appendReflectionEventSync(
                    new com.jarvys.agent.chatdb.ReflectionInput(sessionId, "reflection_event", reflectionId,
                            null, null, null, summary == null ? "" : summary,
                            status == null ? "completed" : status, null,
                            revisionIds == null ? Collections.emptyList() : revisionIds,
                            Collections.emptyList(), System.currentTimeMillis() / 1000.0)), "append reflection event");
            return;
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        JSONObject row = new JSONObject();
        try {
            row.put("type", "reflection_event");
            row.put("reflectionId", reflectionId);
            row.put("summary", summary == null ? "" : summary);
            row.put("status", status == null ? "completed" : status);
            JSONArray ids = new JSONArray();
            if (revisionIds != null) for (Long id : revisionIds) if (id != null) ids.put(id);
            row.put("revisionIds", ids);
            row.put("timestamp", System.currentTimeMillis() / 1000.0);
        } catch (Exception error) { throw new IllegalStateException("Could not create reflection event", error); }
        synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
    }

    public synchronized void appendReflectionUndoEvent(String sessionId, String reflectionId) {
        if (chatDatabaseBridge != null) {
            requireChatDbSuccess(chatDatabaseBridge.appendReflectionUndoSync(sessionId, reflectionId,
                    System.currentTimeMillis() / 1000.0), "append reflection undo");
            return;
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        JSONObject row = new JSONObject();
        try { row.put("type", "reflection_undo"); row.put("reflectionId", reflectionId); row.put("timestamp", System.currentTimeMillis() / 1000.0); }
        catch (Exception error) { throw new IllegalStateException("Could not create reflection undo event", error); }
        synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
    }

    public synchronized boolean hasReflectionCommit(String sessionId, String reflectionId) {
        if (chatDatabaseBridge != null) return chatDatabaseBridge.hasReflectionCommitSync(sessionId, reflectionId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        for (JSONObject row : readConversationRows(sessionId)) {
            if ("reflection_commit".equals(row.optString("type"))
                    && reflectionId.equals(row.optString("reflectionId"))) return true;
        }
        return false;
    }

    public synchronized List<String> pendingCompactionIds(String sessionId) {
        if (chatDatabaseBridge != null) return chatDatabaseBridge.pendingCompactionIdsSync(sessionId);
        if (chatDatabaseBridge == null) throw noChatDatabase();
        return pendingCompactionIds(readConversationRows(sessionId));
    }

    private static List<String> pendingCompactionIds(List<JSONObject> rows) {
        Set<String> pending = new java.util.LinkedHashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            JSONObject row = rows.get(index);
            if ("compaction".equals(row.optString("type"))) {
                pending.add(compactionId(row, index));
            } else if ("reflection_checkpoint".equals(row.optString("type"))
                    || "reflection_commit".equals(row.optString("type"))) {
                JSONArray acked = row.optJSONArray("acknowledgedCompactionIds");
                if (acked != null) for (int i = 0; i < acked.length(); i++) pending.remove(acked.optString(i));
            } else if ("compaction_reflection_consumed".equals(row.optString("type"))) {
                JSONArray ids = row.optJSONArray("compactionIds");
                if (ids == null) {
                    String id = row.optString("compactionId", "");
                    if (id.isEmpty()) pending.clear(); else pending.remove(id);
                } else for (int i = 0; i < ids.length(); i++) pending.remove(ids.optString(i));
            }
        }
        return new ArrayList<>(pending);
    }

    private static String reflectionTimestamp(JSONObject row) {
        long millis = (long) (row.optDouble("timestamp", 0.0) * 1_000.0);
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    private static String compactionId(JSONObject row, int index) {
        String value = row.optString("compactionId", "");
        return value.isEmpty() ? "legacy-" + row.optString("timestamp", "") + "-" + index : value;
    }

    private static String toolRowKey(JSONObject row, Map<String,JSONObject> batches) {
        String batchId=row.optString("batchId"),owner=row.optString("userMessageId"),call=row.optString("callId");
        if(!batchId.isEmpty() && !call.isEmpty()) return batchId+"/"+call;
        if(owner.isEmpty() || call.isEmpty()) return "legacy-row:"+System.identityHashCode(row);
        String candidate=null;
        for(Map.Entry<String,JSONObject> batch:batches.entrySet()) {
            if(!owner.equals(batch.getValue().optString("userMessageId"))) continue;
            JSONArray calls=batch.getValue().optJSONArray("calls");
            if(calls!=null) for(int i=0;i<calls.length();i++) {
                JSONObject value=calls.optJSONObject(i);
                if(value!=null && call.equals(value.optString("id"))) {
                    if(candidate!=null) return "legacy-row:"+System.identityHashCode(row);
                    candidate=batch.getKey()+"/"+call;
                }
            }
        }
        return candidate==null ? owner+"/"+call : candidate;
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

        private String cacheKey() {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, DecisionResolution> entry : resolvedDecisions.entrySet()) {
                DecisionResolution value = entry.getValue();
                parts.add("d:" + entry.getKey() + ":" + value.status + ":" + value.optionId + ":" + value.optionLabel);
            }
            for (String id : consumedProactiveReplyMessageIds) parts.add("p:" + id);
            for (Map.Entry<String, String> entry : reactionsByMessageId.entrySet())
                parts.add("r:" + entry.getKey() + ":" + entry.getValue());
            for (Map.Entry<String, CrewMissionSnapshot> entry : latestCrewSnapshots.entrySet())
                parts.add("c:" + entry.getKey() + ":" + entry.getValue().toJson());
            Collections.sort(parts);
            return parts.toString();
        }
    }

    private static final class CachedTimelinePage {
        final ConversationTimelinePage page;
        final long estimatedBytes;
        CachedTimelinePage(ConversationTimelinePage page, long estimatedBytes) {
            this.page = page;
            this.estimatedBytes = estimatedBytes;
        }
    }

    private static final class ToolBatchLookup {
        final File ledger;
        final boolean exists;
        final Object fileIdentity;
        final long length;
        final long lastModified;
        final String batchId;

        ToolBatchLookup(File ledger, boolean exists, Object fileIdentity, long length, long lastModified, String batchId) {
            this.ledger = ledger;
            this.exists = exists;
            this.fileIdentity = fileIdentity;
            this.length = length;
            this.lastModified = lastModified;
            this.batchId = batchId;
        }

        boolean matches(File file) {
            boolean fileExists = file.isFile();
            return ledger.getAbsolutePath().equals(file.getAbsolutePath())
                    && fileExists == exists
                    && (!exists || (sameFileIdentity(fileIdentity, LocalRunStore.fileIdentity(file))
                            && file.length() == length && file.lastModified() == lastModified));
        }
    }

    /** Per-ledger pair lookups are populated newest-first and advanced by our own appends. */
    private static final class ToolBatchCache {
        final String path;
        final Object fileIdentity;
        final LinkedHashMap<String, String> batchByPair = new LinkedHashMap<>(16, 0.75f, true);
        long length;
        long lastModified;

        ToolBatchCache(String path, Object fileIdentity, long length, long lastModified) {
            this.path = path;
            this.fileIdentity = fileIdentity;
            this.length = length;
            this.lastModified = lastModified;
        }

        boolean matches(Object expectedFileIdentity, long expectedLength, long expectedModified) {
            return sameFileIdentity(fileIdentity, expectedFileIdentity)
                    && length == expectedLength && lastModified == expectedModified;
        }

        void merge(Map<String, String> latestPairs) {
            for (Map.Entry<String, String> entry : latestPairs.entrySet()) {
                rememberLookup(entry.getKey(), entry.getValue());
            }
        }

        void rememberLookup(String pair, String batchId) {
            batchByPair.put(pair, batchId == null ? "" : batchId);
            while (batchByPair.size() > MAX_CACHED_TOOL_PAIRS_PER_FILE)
                batchByPair.remove(batchByPair.keySet().iterator().next());
        }

        void rememberAppendedRow(JSONObject row) {
            String batchId = row.optString("batchId", "");
            String owner = row.optString("userMessageId", "");
            if (batchId.isEmpty() || owner.isEmpty()) return;
            String type = row.optString("type");
            if ("model_tool_calls".equals(type)) {
                JSONArray calls = row.optJSONArray("calls");
                if (calls == null) return;
                for (int index = 0; index < calls.length(); index++) {
                    JSONObject call = calls.optJSONObject(index);
                    if (call == null) continue;
                    String pair = toolBatchPairKey(owner, call.optString("id", ""));
                    if (pair != null) rememberLookup(pair, batchId);
                }
            } else if ("tool_activity".equals(type)) {
                JSONObject activity = row.optJSONObject("activity");
                if (activity == null) return;
                String pair = toolBatchPairKey(owner, activity.optString("callId", ""));
                if (pair != null) rememberLookup(pair, batchId);
            }
        }
    }

    private static final class ActivityBatchResolution {
        final String sourceBatch;
        final boolean activityFound;

        ActivityBatchResolution(String sourceBatch, boolean activityFound) {
            this.sourceBatch = sourceBatch == null ? "" : sourceBatch;
            this.activityFound = activityFound;
        }
    }

    private static final class InputStatus {
        final Set<String> userIds = new HashSet<>();
        final Set<String> knownIds = new HashSet<>();
        final Set<String> incorporated = new HashSet<>();
        final Set<String> resolved = new HashSet<>();
    }

    private static final class LedgerSnapshot {
        final File ledger;
        final boolean exists;
        final Object fileIdentity;
        final long length;
        final long lastModified;

        LedgerSnapshot(File ledger, boolean exists, Object fileIdentity, long length, long lastModified) {
            this.ledger = ledger;
            this.exists = exists;
            this.fileIdentity = fileIdentity;
            this.length = length;
            this.lastModified = lastModified;
        }

        boolean matches(String sessionId, File currentLedger, ConversationMetadataStore metadata) {
            if (metadata.read(sessionId).deleted || !ledger.getAbsolutePath().equals(currentLedger.getAbsolutePath()))
                return false;
            boolean fileExists = currentLedger.isFile();
            return exists == fileExists && (!exists || (sameFileIdentity(fileIdentity,
                    LocalRunStore.fileIdentity(currentLedger)) && length == currentLedger.length()
                    && lastModified == currentLedger.lastModified()));
        }
    }

    /** Latest Crew snapshot per mission for one validated append-only ledger prefix. */
    private static final class CrewSnapshotCache {
        final String path;
        final boolean exists;
        final Object fileIdentity;
        final long length;
        final long lastModified;
        final LinkedHashMap<String, CrewMissionSnapshot> latest;

        CrewSnapshotCache(LedgerSnapshot snapshot, LinkedHashMap<String, CrewMissionSnapshot> latest) {
            path = snapshot.ledger.getAbsolutePath();
            exists = snapshot.exists;
            fileIdentity = snapshot.fileIdentity;
            length = snapshot.length;
            lastModified = snapshot.lastModified;
            this.latest = new LinkedHashMap<>(latest);
        }

        boolean matches(LedgerSnapshot snapshot) {
            return path.equals(snapshot.ledger.getAbsolutePath()) && exists == snapshot.exists
                    && (!exists || (sameFileIdentity(fileIdentity, snapshot.fileIdentity)
                    && length == snapshot.length && lastModified == snapshot.lastModified));
        }

        boolean canExtend(LedgerSnapshot snapshot) {
            return exists && snapshot.exists && path.equals(snapshot.ledger.getAbsolutePath())
                    && sameFileIdentity(fileIdentity, snapshot.fileIdentity)
                    && snapshot.length > length && snapshot.lastModified >= lastModified;
        }

        CrewSnapshotCache copy() {
            return new CrewSnapshotCache(new LedgerSnapshot(new File(path), exists, fileIdentity,
                    length, lastModified), latest);
        }
    }

    private static final class ToolActivityBatchCache {
        final String path;
        final Object fileIdentity;
        final LinkedHashMap<String, ActivityBatchResolution> lookups =
                new LinkedHashMap<>(16, 0.75f, true);
        long length;
        long lastModified;

        ToolActivityBatchCache(String path, Object fileIdentity, long length, long lastModified) {
            this.path = path;
            this.fileIdentity = fileIdentity;
            this.length = length;
            this.lastModified = lastModified;
        }

        boolean matches(Object identity, long expectedLength, long expectedModified) {
            return sameFileIdentity(fileIdentity, identity)
                    && length == expectedLength && lastModified == expectedModified;
        }

        void remember(String pairKey, ActivityBatchResolution resolution) {
            lookups.put(pairKey, resolution);
            while (lookups.size() > MAX_CACHED_TOOL_ACTIVITY_LOOKUPS_PER_FILE)
                lookups.remove(lookups.keySet().iterator().next());
        }

        void advanceAppendedRow(JSONObject row) {
            String owner = row.optString("userMessageId", "");
            String type = row.optString("type");
            String batch = row.optString("batchId", "");
            if ("model_tool_calls".equals(type)) {
                JSONArray calls = row.optJSONArray("calls");
                if (calls == null) return;
                for (Map.Entry<String, ActivityBatchResolution> entry : lookups.entrySet()) {
                    ActivityBatchResolution previous = entry.getValue();
                    if (previous.activityFound) continue;
                    String[] query = splitToolActivityPairKey(entry.getKey());
                    if (!owner.equals(query[0])) continue;
                    for (int index = 0; index < calls.length(); index++) {
                        JSONObject call = calls.optJSONObject(index);
                        if (call != null && query[2].equals(call.optString("id", "")))
                            entry.setValue(new ActivityBatchResolution(batch, false));
                    }
                }
            } else if ("tool_activity".equals(type)) {
                JSONObject activity = row.optJSONObject("activity");
                if (activity == null) return;
                String execution = activity.optString("executionId", "");
                for (Map.Entry<String, ActivityBatchResolution> entry : lookups.entrySet()) {
                    ActivityBatchResolution previous = entry.getValue();
                    String[] query = splitToolActivityPairKey(entry.getKey());
                    if (!previous.activityFound && owner.equals(query[0]) && execution.equals(query[1]))
                        entry.setValue(new ActivityBatchResolution(batch, true));
                }
            }
        }

        private static String[] splitToolActivityPairKey(String key) {
            int first = key.indexOf('\u0000');
            int second = first < 0 ? -1 : key.indexOf('\u0000', first + 1);
            if (second < 0) return new String[] {"", "", ""};
            return new String[] {key.substring(0, first), key.substring(first + 1, second), key.substring(second + 1)};
        }
    }

    private static final class ToolActivityBatchLookup {
        final File ledger;
        final boolean exists;
        final Object fileIdentity;
        final long length;
        final long lastModified;
        final String pairKey;
        final ActivityBatchResolution resolution;

        ToolActivityBatchLookup(File ledger, boolean exists, Object fileIdentity, long length,
                long lastModified, String pairKey, ActivityBatchResolution resolution) {
            this.ledger = ledger;
            this.exists = exists;
            this.fileIdentity = fileIdentity;
            this.length = length;
            this.lastModified = lastModified;
            this.pairKey = pairKey;
            this.resolution = resolution;
        }

        boolean matches(File file) {
            boolean fileExists = file.isFile();
            return ledger.getAbsolutePath().equals(file.getAbsolutePath()) && exists == fileExists
                    && (!exists || (sameFileIdentity(fileIdentity, LocalRunStore.fileIdentity(file))
                    && file.length() == length && file.lastModified() == lastModified));
        }
    }

    /** Reads JSONL rows backwards in blocks, retaining only one candidate line at a time. */
    private static final class ReverseLedgerLineReader {
        private static final int BLOCK_SIZE = 8 * 1024;
        private final java.nio.channels.FileChannel channel;
        private final byte[] block = new byte[BLOCK_SIZE];
        private long position;
        private long blockStart = -1L;
        private int blockLength;
        private long bytesRead;

        ReverseLedgerLineReader(java.nio.channels.FileChannel channel, long endOffset) {
            this.channel = channel;
            this.position = endOffset;
        }

        String previousLine() throws IOException {
            if (position <= 0L) return null;
            long end = position;
            if (byteAt(end - 1L) == '\n') end--;
            long scan = end - 1L;
            while (scan >= 0L && byteAt(scan) != '\n') scan--;
            long start = scan + 1L;
            position = start;
            long length = end - start;
            if (length > Integer.MAX_VALUE) throw new IOException("Tool batch row is too large");
            byte[] bytes = new byte[(int) length];
            for (int index = 0; index < bytes.length; index++) bytes[index] = byteAt(end - index - 1L);
            for (int left = 0, right = bytes.length - 1; left < right; left++, right--) {
                byte value = bytes[left];
                bytes[left] = bytes[right];
                bytes[right] = value;
            }
            int decodedLength = bytes.length;
            if (decodedLength > 0 && bytes[decodedLength - 1] == '\r') decodedLength--;
            return new String(bytes, 0, decodedLength, StandardCharsets.UTF_8);
        }

        long bytesRead() { return bytesRead; }

        private byte byteAt(long offset) throws IOException {
            if (blockStart < 0L || offset < blockStart || offset >= blockStart + blockLength) {
                blockStart = (offset / BLOCK_SIZE) * BLOCK_SIZE;
                java.nio.ByteBuffer target = java.nio.ByteBuffer.wrap(block);
                target.clear();
                long readPosition = blockStart;
                while (target.hasRemaining()) {
                    int count = channel.read(target, readPosition);
                    if (count <= 0) break;
                    readPosition += count;
                }
                blockLength = target.position();
                bytesRead += blockLength;
            }
            int index = (int) (offset - blockStart);
            if (index < 0 || index >= blockLength) throw new IOException("Ledger changed while resolving tool batch");
            return block[index];
        }
    }

    public static final int INITIAL_TIMELINE_TURNS = 40;
    public static final int OLDER_TIMELINE_TURNS = 20;

    /** UI timeline preserves every original user/assistant row and inserts read-only compaction notices. */
    public List<AgentRunUiEvent> readConversationTimeline(String sessionId) {
        if (chatDatabaseBridge != null) {
            List<AgentRunUiEvent> all = new ArrayList<>();
            ChatTimelineEventPage page = chatDatabaseBridge.readRecentConversationTimelineSync(
                    sessionId, Math.max(INITIAL_TIMELINE_TURNS, OLDER_TIMELINE_TURNS));
            all.addAll(page.getEvents());
            Long cursor = page.getOlderCursorSeq();
            while (cursor != null) {
                ChatTimelineEventPage older = chatDatabaseBridge.readConversationTimelineBeforeSync(
                        sessionId, cursor, Math.max(INITIAL_TIMELINE_TURNS, OLDER_TIMELINE_TURNS));
                if (older.getEvents().isEmpty()) break;
                all.addAll(0, older.getEvents());
                cursor = older.getOlderCursorSeq();
            }
            return all;
        }
        List<JSONObject> rows = readConversationRowsFromValidatedSnapshot(sessionId, true, false);
        java.util.IdentityHashMap<JSONObject, Integer> messageIndicesByIdentity = new java.util.IdentityHashMap<>();
        List<Integer> messageIndices = timelineMessageIndices(rows, 0);
        for (int index = 0; index < rows.size(); index++)
            messageIndicesByIdentity.put(rows.get(index), messageIndices.get(index));
        orderAssistantMessagesBeforeSteering(rows);
        messageIndices.clear();
        for (JSONObject row : rows) messageIndices.add(messageIndicesByIdentity.getOrDefault(row, -1));
        return projectConversationTimeline(sessionId, rows, null, Collections.emptySet(),
                timelineProjectionMarkers(sessionId, rows), new TimelineProjectionMarkers(), 0, messageIndices);
    }

    /**
     * Reads only a recent or preceding turn window for the UI. Model context and transcript restore
     * continue to use their existing full-history readers.
     */
    public ConversationTimelinePage readConversationTimelinePage(
            String sessionId, long beforeOffset, int turnCount) {
        return readConversationTimelinePage(sessionId, beforeOffset, turnCount, Collections.emptySet());
    }

    public ConversationTimelinePage readConversationTimelinePage(
            String sessionId, long beforeOffset, int turnCount, Set<String> newerInvalidatedToolTurnMessageIds) {
        return readConversationTimelinePage(sessionId, beforeOffset, turnCount,
                newerInvalidatedToolTurnMessageIds, new TimelineProjectionMarkers());
    }

    /** Reads a page while applying state already learned from newer loaded windows. */
    public ConversationTimelinePage readConversationTimelinePage(
            String sessionId, long beforeOffset, int turnCount,
            Set<String> newerInvalidatedToolTurnMessageIds, TimelineProjectionMarkers newerProjectionMarkers) {
        if (turnCount < 1 || turnCount > 200) throw new IllegalArgumentException("Timeline page size must be 1..200");
        if (chatDatabaseBridge != null) {
            ChatTimelineEventPage dbPage = beforeOffset == NO_TIMELINE_CURSOR
                    ? chatDatabaseBridge.readRecentConversationTimelineSync(sessionId, turnCount)
                    : chatDatabaseBridge.readConversationTimelineBeforeSync(sessionId, beforeOffset, turnCount);
            Set<String> invalidated = newerInvalidatedToolTurnMessageIds == null
                    ? Collections.emptySet() : newerInvalidatedToolTurnMessageIds;
            TimelineProjectionMarkers markers = newerProjectionMarkers == null
                    ? new TimelineProjectionMarkers() : newerProjectionMarkers;
            Set<String> translations = new HashSet<>();
            for (AgentRunUiEvent event : dbPage.getEvents())
                if ("assistant_translation".equals(event.getKind()) && event.getDetail() != null)
                    translations.add(event.getDetail());
            Long cursor = dbPage.getOlderCursorSeq();
            return new ConversationTimelinePage(dbPage.getEvents(), cursor == null ? NO_TIMELINE_CURSOR : cursor,
                    cursor != null, Collections.emptySet(), Collections.emptySet(), translations,
                    invalidated, markers);
        }
        Set<String> newerInvalidations = newerInvalidatedToolTurnMessageIds == null
                ? Collections.emptySet() : newerInvalidatedToolTurnMessageIds;
        TimelineProjectionMarkers newerMarkers = newerProjectionMarkers == null
                ? new TimelineProjectionMarkers() : newerProjectionMarkers;
        File ledger = conversationFile(sessionId);
        ConversationLedgerPager.Page rawPage;
        List<JSONObject> rows = new ArrayList<>();
        java.util.IdentityHashMap<JSONObject, Long> rowOffsetsByIdentity = new java.util.IdentityHashMap<>();
        java.util.IdentityHashMap<JSONObject, Integer> messageIndicesByIdentity = new java.util.IdentityHashMap<>();
        String pageCacheKey;
        Object snapshotIdentity;
        long snapshotLength;
        long snapshotModified;
        synchronized (SESSION_TITLE_LOCK) {
            if (conversationMetadata.read(sessionId).deleted || !ledger.isFile()) {
                return new ConversationTimelinePage(Collections.emptyList(), ConversationLedgerPager.NO_CURSOR, false,
                        Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                        new TimelineProjectionMarkers());
            }
            snapshotIdentity = fileIdentity(ledger);
            snapshotLength = ledger.length();
            snapshotModified = ledger.lastModified();
            pageCacheKey = timelinePageCacheKey(ledger, snapshotIdentity, snapshotLength, snapshotModified,
                    beforeOffset, turnCount, newerInvalidations, newerMarkers);
            ConversationTimelinePage cached = cachedTimelinePage(pageCacheKey);
            if (cached != null) return cached;
        }
        try {
            synchronized (TIMELINE_PAGE_CACHE_LOCK) { timelinePagePagerCallCount++; }
            rawPage = beforeOffset == ConversationLedgerPager.NO_CURSOR
                    ? ConversationLedgerPager.readTail(ledger, turnCount, snapshotLength)
                    : ConversationLedgerPager.readPrevious(ledger, beforeOffset, turnCount, snapshotLength);
        } catch (IOException error) {
            throw new IllegalStateException("Could not read conversation timeline page", error);
        }
        {
            for (int lineIndex = 0; lineIndex < rawPage.lines.size(); lineIndex++) try {
                String line = rawPage.lines.get(lineIndex);
                JSONObject row = new JSONObject(line);
                if (!isConversationTimelineLedgerRow(row, true)) continue;
                rows.add(row);
                rowOffsetsByIdentity.put(row, rawPage.lineOffsets.get(lineIndex));
            } catch (Exception ignored) { }
            List<Integer> pageMessageIndices = timelineMessageIndices(rows, rawPage.messageIndexBeforeStart);
            for (int index = 0; index < rows.size(); index++)
                messageIndicesByIdentity.put(rows.get(index), pageMessageIndices.get(index));
        }
        orderAssistantMessagesBeforeSteering(rows);
        List<Long> rowOffsets = new ArrayList<>(rows.size());
        List<Integer> rowMessageIndices = new ArrayList<>(rows.size());
        for (JSONObject row : rows) {
            rowOffsets.add(rowOffsetsByIdentity.getOrDefault(row, 0L));
            rowMessageIndices.add(messageIndicesByIdentity.getOrDefault(row, -1));
        }
        TimelineProjectionMarkers pageMarkers = timelineProjectionMarkers(sessionId, rows);
        TimelineProjectionMarkers combinedMarkers = newerMarkers.withOlderPage(pageMarkers);
        List<AgentRunUiEvent> events = projectConversationTimeline(sessionId, rows, rowOffsets,
                newerInvalidations, combinedMarkers, newerMarkers, rawPage.messageIndexBeforeStart, rowMessageIndices);
        Set<String> translationMessageIds = new HashSet<>();
        for (AgentRunUiEvent event : events) {
            if ("assistant_translation".equals(event.getKind()) && event.getDetail() != null && !event.getDetail().isEmpty())
                translationMessageIds.add(event.getDetail());
        }
        Set<String> invalidatedToolTurnMessageIds = new HashSet<>(newerInvalidations);
        for (JSONObject row : rows) {
            String type = row.optString("type");
            String userMessageId = row.optString("userMessageId", "");
            if (("assistant_regenerated".equals(type) || "assistant_deleted".equals(type))
                    && !userMessageId.isEmpty()) invalidatedToolTurnMessageIds.add(userMessageId);
        }
        ConversationTimelinePage result = new ConversationTimelinePage(events, rawPage.olderCursor, rawPage.hasMore,
                hiddenAssistantIds(rows), hiddenTranslationIds(rows), translationMessageIds,
                invalidatedToolTurnMessageIds, combinedMarkers);
        synchronized (SESSION_TITLE_LOCK) {
            if (!conversationMetadata.read(sessionId).deleted
                    && pageCacheKey.equals(timelinePageCacheKey(ledger, fileIdentity(ledger),
                            ledger.length(), ledger.lastModified(),
                            beforeOffset, turnCount, newerInvalidations, newerMarkers)))
                cacheTimelinePage(pageCacheKey, result);
        }
        return result;
    }

    /** Recent persisted UI events for run-service refreshes; the model transcript reader is separate. */
    public List<AgentRunUiEvent> readRecentConversationTimeline(String sessionId) {
        if (chatDatabaseBridge != null)
            return chatDatabaseBridge.readRecentConversationTimelineSync(sessionId, INITIAL_TIMELINE_TURNS).getEvents();
        return readConversationTimelinePage(sessionId, ConversationLedgerPager.NO_CURSOR, INITIAL_TIMELINE_TURNS).events;
    }

    private static String timelinePageCacheKey(File ledger, Object fileIdentity, long length, long lastModified,
                                               long beforeOffset, int turnCount,
                                               Set<String> newerInvalidations,
                                               TimelineProjectionMarkers newerMarkers) {
        List<String> sortedInvalidations = new ArrayList<>(newerInvalidations);
        Collections.sort(sortedInvalidations);
        return ledger.getAbsolutePath() + '\u0000' + String.valueOf(fileIdentity) + '\u0000' + length + '\u0000'
                + lastModified + '\u0000' + beforeOffset + '\u0000' + turnCount
                + '\u0000' + sortedInvalidations
                + '\u0000' + newerMarkers.cacheKey();
    }

    static long timelinePagePagerCallCountForTests() {
        synchronized (TIMELINE_PAGE_CACHE_LOCK) { return timelinePagePagerCallCount; }
    }

    static void resetHotPathMetricsForTests() {
        synchronized (LocalRunStore.class) {
            conversationRowsFullParseCount = 0L;
            toolActivityLedgerScanCount = 0L;
            toolActivityLedgerScanBytes = 0L;
            toolActivityScanUnderSessionLockCount = 0L;
            userMessagesAfterScanCount = 0L;
            userMessagesAfterScanBytes = 0L;
            userMessagesAfterScanUnderSessionLockCount = 0L;
        }
        synchronized (CREW_SNAPSHOT_CACHE_LOCK) {
            crewSnapshotLedgerScanCount = 0L;
            crewSnapshotLedgerScanBytes = 0L;
            crewSnapshotRowsParsedCount = 0L;
            crewSnapshotCacheHitCount = 0L;
        }
    }

    static long conversationRowsFullParseCountForTests() {
        synchronized (LocalRunStore.class) { return conversationRowsFullParseCount; }
    }

    static long crewSnapshotLedgerScanCountForTests() {
        synchronized (CREW_SNAPSHOT_CACHE_LOCK) { return crewSnapshotLedgerScanCount; }
    }

    static long crewSnapshotLedgerScanBytesForTests() {
        synchronized (CREW_SNAPSHOT_CACHE_LOCK) { return crewSnapshotLedgerScanBytes; }
    }

    static long crewSnapshotRowsParsedCountForTests() {
        synchronized (CREW_SNAPSHOT_CACHE_LOCK) { return crewSnapshotRowsParsedCount; }
    }

    static long crewSnapshotCacheHitCountForTests() {
        synchronized (CREW_SNAPSHOT_CACHE_LOCK) { return crewSnapshotCacheHitCount; }
    }

    static long toolActivityLedgerScanCountForTests() {
        synchronized (LocalRunStore.class) { return toolActivityLedgerScanCount; }
    }

    static long toolActivityLedgerScanBytesForTests() {
        synchronized (LocalRunStore.class) { return toolActivityLedgerScanBytes; }
    }

    static long toolActivityScanUnderSessionLockCountForTests() {
        synchronized (LocalRunStore.class) { return toolActivityScanUnderSessionLockCount; }
    }

    static long userMessagesAfterScanCountForTests() {
        synchronized (LocalRunStore.class) { return userMessagesAfterScanCount; }
    }

    static long userMessagesAfterScanBytesForTests() {
        synchronized (LocalRunStore.class) { return userMessagesAfterScanBytes; }
    }

    static long userMessagesAfterScanUnderSessionLockCountForTests() {
        synchronized (LocalRunStore.class) { return userMessagesAfterScanUnderSessionLockCount; }
    }

    static void setToolActivityScanTestHookForTests(Runnable hook) { toolActivityScanTestHook = hook; }

    private static TimelineProjectionMarkers timelineProjectionMarkers(String sessionId, List<JSONObject> rows) {
        Map<String, TimelineProjectionMarkers.DecisionResolution> decisions = new LinkedHashMap<>();
        Set<String> consumedReplies = new HashSet<>();
        Map<String, CrewMissionSnapshot> crewSnapshots = new LinkedHashMap<>();
        for (JSONObject row : rows) {
            String type = row.optString("type");
            if ("user_decision_resolution".equals(type)) {
                String id = row.optString("decisionId", "");
                if (!id.isEmpty()) decisions.put(id, new TimelineProjectionMarkers.DecisionResolution(
                        row.optString("status", "UNANSWERED"),
                        row.has("optionId") ? row.optString("optionId") : null,
                        row.has("optionLabel") ? row.optString("optionLabel") : null));
            } else if ("proactive_reply_used".equals(type)) {
                String id = row.optString("messageId", "");
                if (!id.isEmpty()) consumedReplies.add(id);
            } else if ("crew_snapshot".equals(type)) {
                CrewMissionSnapshot snapshot = CrewMissionSnapshot.fromJson(row);
                if (snapshot != null && sessionId.equals(snapshot.conversationId))
                    crewSnapshots.put(snapshot.missionId, snapshot);
            }
        }
        return new TimelineProjectionMarkers(decisions, consumedReplies, readMessageReactions(rows), crewSnapshots);
    }

    private static ConversationTimelinePage cachedTimelinePage(String key) {
        synchronized (TIMELINE_PAGE_CACHE_LOCK) {
            CachedTimelinePage cached = TIMELINE_PAGE_CACHE.get(key);
            return cached == null ? null : cached.page;
        }
    }

    private static void cacheTimelinePage(String key, ConversationTimelinePage page) {
        long weight = 256L;
        for (AgentRunUiEvent event : page.events) {
            weight += 384L + 2L * (event.getText().length()
                    + (event.getDetail() == null ? 0 : event.getDetail().length()));
        }
        if (weight > MAX_TIMELINE_PAGE_CACHE_BYTES) return;
        synchronized (TIMELINE_PAGE_CACHE_LOCK) {
            CachedTimelinePage old = TIMELINE_PAGE_CACHE.put(key, new CachedTimelinePage(page, weight));
            if (old != null) timelinePageCacheBytes -= old.estimatedBytes;
            timelinePageCacheBytes += weight;
            java.util.Iterator<Map.Entry<String, CachedTimelinePage>> iterator = TIMELINE_PAGE_CACHE.entrySet().iterator();
            while (timelinePageCacheBytes > MAX_TIMELINE_PAGE_CACHE_BYTES && iterator.hasNext()) {
                CachedTimelinePage evicted = iterator.next().getValue();
                timelinePageCacheBytes -= evicted.estimatedBytes;
                iterator.remove();
            }
        }
    }

    private static void invalidateTimelinePageCache(File ledger) {
        String prefix = ledger.getAbsolutePath() + '\u0000';
        synchronized (TIMELINE_PAGE_CACHE_LOCK) {
            java.util.Iterator<Map.Entry<String, CachedTimelinePage>> iterator = TIMELINE_PAGE_CACHE.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, CachedTimelinePage> entry = iterator.next();
                if (!entry.getKey().startsWith(prefix)) continue;
                timelinePageCacheBytes -= entry.getValue().estimatedBytes;
                iterator.remove();
            }
        }
    }

    private List<AgentRunUiEvent> projectConversationTimeline(
            String sessionId, List<JSONObject> rows, List<Long> rowOffsets) {
        return projectConversationTimeline(sessionId, rows, rowOffsets, Collections.emptySet());
    }

    private List<AgentRunUiEvent> projectConversationTimeline(
            String sessionId, List<JSONObject> rows, List<Long> rowOffsets,
            Set<String> newerInvalidatedToolTurnMessageIds) {
        return projectConversationTimeline(sessionId, rows, rowOffsets,
                newerInvalidatedToolTurnMessageIds, timelineProjectionMarkers(sessionId, rows),
                new TimelineProjectionMarkers());
    }

    private List<AgentRunUiEvent> projectConversationTimeline(
            String sessionId, List<JSONObject> rows, List<Long> rowOffsets,
            Set<String> newerInvalidatedToolTurnMessageIds,
            TimelineProjectionMarkers effectiveMarkers, TimelineProjectionMarkers newerMarkers) {
        return projectConversationTimeline(sessionId, rows, rowOffsets, newerInvalidatedToolTurnMessageIds,
                effectiveMarkers, newerMarkers, 0);
    }

    private List<AgentRunUiEvent> projectConversationTimeline(
            String sessionId, List<JSONObject> rows, List<Long> rowOffsets,
            Set<String> newerInvalidatedToolTurnMessageIds,
            TimelineProjectionMarkers effectiveMarkers, TimelineProjectionMarkers newerMarkers,
            int firstMessageIndex) {
        return projectConversationTimeline(sessionId, rows, rowOffsets, newerInvalidatedToolTurnMessageIds,
                effectiveMarkers, newerMarkers, firstMessageIndex, null);
    }

    private List<AgentRunUiEvent> projectConversationTimeline(
            String sessionId, List<JSONObject> rows, List<Long> rowOffsets,
            Set<String> newerInvalidatedToolTurnMessageIds,
            TimelineProjectionMarkers effectiveMarkers, TimelineProjectionMarkers newerMarkers,
            int firstMessageIndex, List<Integer> rowMessageIndices) {
        List<AgentRunUiEvent> events = new ArrayList<>();
        Map<String, ChatAttachment> ownedDeliveredFiles = new LinkedHashMap<>();
        for (JSONObject row : rows) {
            if ("delivered_file".equals(row.optString("type"))) {
                ChatAttachment attachment = ChatAttachment.fromJson(row.optJSONObject("artifact"));
                if (attachment != null) ownedDeliveredFiles.put(attachment.id, attachment);
            }
        }
        Map<String, CrewMissionSnapshot> latestCrew = new LinkedHashMap<>();
        Map<String, Integer> latestCrewRow = new LinkedHashMap<>();
        Set<String> consumedProactiveReplies = new HashSet<>(effectiveMarkers.consumedProactiveReplyMessageIds);
        for (int index = 0; index < rows.size(); index++) {
            JSONObject row = rows.get(index);
            if (!"crew_snapshot".equals(row.optString("type"))) continue;
            CrewMissionSnapshot snapshot = CrewMissionSnapshot.fromJson(row);
            if (snapshot != null && sessionId.equals(snapshot.conversationId)) {
                latestCrew.put(snapshot.missionId, snapshot);
                latestCrewRow.put(snapshot.missionId, index);
            }
        }
        Set<String> hiddenMessages = hiddenAssistantIds(rows);
        Map<String, Integer> latestInvalidatedToolTurnRow = new LinkedHashMap<>();
        int latestInvalidationRow = -1;
        Set<String> undoneReflectionIds = new HashSet<>();
        Map<String, TimelineProjectionMarkers.DecisionResolution> userDecisionResolutions =
                new LinkedHashMap<>(effectiveMarkers.resolvedDecisions);
        for (int index = 0; index < rows.size(); index++) {
            JSONObject row = rows.get(index);
            String type = row.optString("type");
            if ("user_decision_resolution".equals(type)) {
                String decisionId = row.optString("decisionId", "");
                if (!decisionId.isEmpty()) userDecisionResolutions.putIfAbsent(decisionId,
                        new TimelineProjectionMarkers.DecisionResolution(row.optString("status", "UNANSWERED"),
                                row.has("optionId") ? row.optString("optionId") : null,
                                row.has("optionLabel") ? row.optString("optionLabel") : null));
            }
            if ("assistant_regenerated".equals(type) || "assistant_deleted".equals(type)) {
                latestInvalidationRow = index;
                String userMessageId = row.optString("userMessageId", "");
                if (!userMessageId.isEmpty()) latestInvalidatedToolTurnRow.put(userMessageId, index);
            }
            if ("reflection_undo".equals(type)) undoneReflectionIds.add(rows.get(index).optString("reflectionId", ""));
        }
        for (String userMessageId : newerInvalidatedToolTurnMessageIds) {
            if (userMessageId != null && !userMessageId.isEmpty())
                latestInvalidatedToolTurnRow.put(userMessageId, rows.size());
        }
        Map<String, Integer> translationHiddenAt = new LinkedHashMap<>();
        Map<String, Integer> latestTranslation = new LinkedHashMap<>();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            JSONObject row = rows.get(rowIndex);
            if ("assistant_translation_hidden".equals(row.optString("type"))) {
                translationHiddenAt.put(row.optString("messageId", ""), rowIndex);
            } else if ("assistant_translation".equals(row.optString("type"))) {
                latestTranslation.put(row.optString("messageId", ""), rowIndex);
            }
        }
        Map<String, List<ToolActivity>> activityGroups = new LinkedHashMap<>();
        Map<String, Integer> activityFirst = new LinkedHashMap<>();
        Set<String> typedCalls = new HashSet<>();
        Map<String,String> activitySources = new LinkedHashMap<>();
        Set<String> typedReflectionCalls = new HashSet<>();
        Map<String, JSONObject> modelBatches = new LinkedHashMap<>();
        for (JSONObject row : rows) if ("model_tool_calls".equals(row.optString("type"))) modelBatches.put(row.optString("batchId"),row);
        for (int i=0;i<rows.size();i++) {
            JSONObject row=rows.get(i); if(!"tool_activity".equals(row.optString("type"))) continue;
            String owner=row.optString("userMessageId");
            if(owner.isEmpty() || i<latestInvalidatedToolTurnRow.getOrDefault(owner,-1)) continue;
            ToolActivity activity=ToolActivity.fromJson(row.optJSONObject("activity")); if(activity==null) continue;
            String key=owner+"/"+activity.executionId;
            activityGroups.computeIfAbsent(key,ignored->new ArrayList<>()).add(activity);
            activityFirst.putIfAbsent(key,i);
            String batch=row.optString("batchId");
            String source=batch.isEmpty()?owner+"/"+activity.callId:batch+"/"+activity.callId;
            typedCalls.add(source);activitySources.putIfAbsent(key,source);typedReflectionCalls.add(owner+"/"+activity.callId);
        }
        Map<String, JSONObject> presentations = new LinkedHashMap<>();
        Map<String, JSONObject> reflectionTools = new LinkedHashMap<>();
        Map<String, JSONObject> modelCalls = new LinkedHashMap<>();
        Set<String> modelResults = new HashSet<>();
        Set<String> startedModelCalls = new HashSet<>();
        for (JSONObject row : rows) {
            String type = row.optString("type");
            if ("tool_presentation".equals(type)) presentations.put(toolRowKey(row,modelBatches), row);
            else if ("reflection_tool".equals(type)) reflectionTools.put(toolRowKey(row,modelBatches), row);
            else if ("model_tool_calls".equals(type)) {
                JSONArray calls = row.optJSONArray("calls");
                if (calls != null) for (int i = 0; i < calls.length(); i++) {
                    JSONObject call = calls.optJSONObject(i);
                    if (call != null) modelCalls.put(row.optString("batchId")+"/"+call.optString("id"), row);
                }
            } else if ("model_tool_result".equals(type)) modelResults.add(toolRowKey(row,modelBatches));
            else if ("model_tool_started".equals(type)) {
                String key = toolRowKey(row, modelBatches);
                startedModelCalls.add(key);
            } else if ("model_tool_not_started".equals(type)) {
                String key = toolRowKey(row, modelBatches);
                startedModelCalls.remove(key);
            }
        }
        for(Map.Entry<String,List<ToolActivity>> group:activityGroups.entrySet()) {
            ToolActivity projected=ToolActivity.project(group.getValue(),true);
            if(ToolActivity.definitive(projected.stage)) continue;
            String source=activitySources.get(group.getKey());
            for(JSONObject candidate:rows) if("model_tool_result".equals(candidate.optString("type"))
                    && source.equals(toolRowKey(candidate,modelBatches))) {
                String output=candidate.optString("output");
                String lifecycle=candidate.optString("lifecycle", "RESULT");
                String stage;
                if ("INTERRUPTED_UNCERTAIN".equals(lifecycle)) stage="tool_interrupted";
                else if ("NEVER_LAUNCHED".equals(lifecycle)) stage="tool_not_started";
                else stage=output.startsWith("Tool error:")?"tool_error":projected.skill()?"tool_interrupted":"tool_result";
                group.getValue().add(new ToolActivity(projected.executionId+"/durable-result",projected.executionId,projected.callId,
                    projected.toolName,projected.displayName,projected.skillId,projected.skillName,stage,output,
                    modelPreviewToken(projected.toolName,output),projected.auditDetail,projected.reflectionSource,projected.timestampMillis));
                break;
            }
        }
        Set<String> dispatchedInterrupts = new HashSet<>();
        for (JSONObject row : rows) if (row.has("interruptRequestId"))
            dispatchedInterrupts.add(row.optString("interruptRequestId"));
        Map<String, String> reactions = MessageReactionTool.isOrdinaryChat(sessionId)
                ? readMessageReactions(rows) : java.util.Collections.emptyMap();
        if (MessageReactionTool.isOrdinaryChat(sessionId)) reactions.putAll(effectiveMarkers.reactionsByMessageId);
        int messageIndex = firstMessageIndex;
        long id = 1L;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            if (rowOffsets != null) id = Math.max(1L, rowOffsets.get(rowIndex)) * 1_000_000L + 1L;
            JSONObject row = rows.get(rowIndex);
            String role = row.optString("role");
            String type = row.optString("type");
            String messageId = row.optString("messageId", "");
            if (("user".equals(role) || "assistant".equals(role)) && row.has("content")) {
                int absoluteMessageIndex = rowMessageIndices == null
                        ? messageIndex : rowMessageIndices.get(rowIndex);
                if ("user".equals(role) && MessageReactionTool.isOrdinaryChat(sessionId)
                        && row.optString("proactiveThreadKey").isEmpty())
                    messageId = MessageReactionTool.stableMessageId(sessionId, row, absoluteMessageIndex);
                messageIndex++;
            }
            if ("user".equals(role)) {
                String threadKey = row.optString("proactiveThreadKey", "");
                long timestamp = (long) (row.optDouble("timestamp", 0) * 1000);
                events.add(threadKey.isEmpty()
                        ? AgentRunUiEvent.messageEvent(id++, role, row.optString("content", ""), timestamp).copyMetadata(messageId, 0L).copyAttachments(attachmentsForRow(sessionId, row))
                                .copyReaction(reactions.getOrDefault(messageId, ""))
                        : AgentRunUiEvent.proactiveMessageEvent(id++, role, row.optString("content", ""),
                                timestamp, messageId, threadKey, java.util.Collections.emptyList(), false, 0L, null, false));
            } else if ("assistant".equals(role)) {
                if (hiddenMessages.contains(messageId)) continue;
                long timestamp = (long) (row.optDouble("timestamp", 0) * 1000);
                String threadKey = row.optString("proactiveThreadKey", "");
                if ("proactive_message".equals(type)) {
                    events.add(AgentRunUiEvent.proactiveMessageEvent(id++, role, row.optString("content", ""),
                        timestamp, messageId, threadKey, proactiveSuggestedReplies(row),
                        consumedProactiveReplies.contains(messageId), row.optLong("durationMs", 0),
                        AgentRunUiEvent.assistantStageForOutcome(row.optString("status", "")), true));
                } else if (!threadKey.isEmpty()) {
                    events.add(AgentRunUiEvent.proactiveMessageEvent(id++, role, row.optString("content", ""),
                        timestamp, messageId, threadKey, java.util.Collections.emptyList(), false,
                        row.optLong("durationMs", 0),
                        AgentRunUiEvent.assistantStageForOutcome(row.optString("status", "")), false));
                } else {
                    events.add(AgentRunUiEvent.messageEvent(id++, role, row.optString("content", ""), timestamp)
                        .copyMetadata(messageId, row.optLong("durationMs", 0)).copyStage(
                            AgentRunUiEvent.assistantStageForOutcome(row.optString("status", ""))));
                }
            } else if ("main_interrupt_request".equals(type)) {
                String requestId = row.optString("requestId");
                if (!dispatchedInterrupts.contains(requestId)) events.add(
                    AgentRunUiEvent.messageEvent(id++, "user", row.optString("content"),
                        (long) (row.optDouble("timestamp", 0) * 1000))
                        .copyMetadata("pending-" + requestId, 0L).copyStage("QUEUED"));
            } else if ("workspace_result_notice".equals(type)) {
                if (!com.jarvys.agent.workspaces.WorkspaceIdentity.isCentral(sessionId)) continue;
                try {
                    com.jarvys.agent.workspaces.WorkspaceResultReference reference =
                        com.jarvys.agent.workspaces.WorkspaceResultReference.fromNoticeJsonStrict(row);
                    // Strict receipt read validates the complete ledger; tolerant UI scanning is not authority.
                    com.jarvys.agent.workspaces.WorkspaceResultReference exact =
                        readWorkspaceResultNotice(sessionId, reference.referenceId);
                    if (exact != null && events.stream().noneMatch(event ->
                        "workspace_result".equals(event.getKind()) && event.getWorkspaceResultReference() != null &&
                        exact.referenceId.equals(event.getWorkspaceResultReference().referenceId)))
                        events.add(AgentRunUiEvent.workspaceResultEvent(id++, exact, exact.producedAtMillis));
                } catch (RuntimeException invalidReceipt) {
                    // Preserve malformed evidence, withhold an unverified notice; never repair or replay.
                }
            } else if ("delivered_file".equals(type)) {
                ChatAttachment artifact = ChatAttachment.fromJson(row.optJSONObject("artifact"));
                if (artifact != null) events.add(AgentRunUiEvent.deliveredFileEvent(id++, artifact,
                        (long) (row.optDouble("timestamp", 0) * 1000)));
            } else if ("generated_image".equals(type)) {
                events.add(AgentRunUiEvent.generatedImageEvent(id++,
                        row.has("imagePath") ? row.optString("imagePath", null) : null,
                        row.optString("prompt", ""), row.optString("revisedPrompt", ""),
                        row.optString("mimeType", "image/png"), row.optString("size", ""),
                        row.optString("status", "FAILED"), row.optString("error", ""),
                        (long) (row.optDouble("timestamp", 0) * 1000)));
            } else if ("tool_activity".equals(type)) {
                ToolActivity activity=ToolActivity.fromJson(row.optJSONObject("activity")); if(activity==null) continue;
                String key=row.optString("userMessageId")+"/"+activity.executionId;
                if(activityFirst.getOrDefault(key,-1)!=rowIndex) continue;
                ToolActivity projected=ToolActivity.project(activityGroups.get(key),false);
                if(!projected.previewId.isEmpty() && verifiedPreview(sessionId,projected.previewId,ownedDeliveredFiles)==null) {
                    projected=projected.unavailablePreview();
                }
                String owner = row.optString("userMessageId", "");
                String identity = "execution:" + projected.executionId;
                events.add(AgentRunUiEvent.activityEvent(id++,projected)
                        .copyToolTimelineIdentity(identity, owner));
            } else if ("model_tool_calls".equals(type)) {
                String userMessageId = row.optString("userMessageId", "");
                if (userMessageId.isEmpty() || rowIndex < latestInvalidatedToolTurnRow.getOrDefault(userMessageId, -1)) continue;
                AgentRunUiEvent progress = assistantProgressEvent(row, id);
                if (progress != null) {
                    String progressScope = row.optString("batchId", "");
                    if (progressScope.isEmpty()) progressScope = userMessageId;
                    events.add(progress.copyToolTimelineIdentity(progressScope + "/progress",
                            row.optString("userMessageId", "")));
                    id++;
                }
                JSONArray calls = row.optJSONArray("calls");
                if (calls != null) for (int callIndex = 0; callIndex < calls.length(); callIndex++) {
                    JSONObject call = calls.optJSONObject(callIndex);
                    if (call == null) continue;
                    String callId = call.optString("id");
                    String callKey=row.optString("batchId")+"/"+callId;
                    String scopedCallIdentity = row.optString("batchId", "");
                    if (scopedCallIdentity.isEmpty()) scopedCallIdentity = userMessageId;
                    if (typedCalls.contains(callKey)) continue;
                    if (modelResults.contains(callKey) || presentations.containsKey(callKey) || reflectionTools.containsKey(callKey)) continue;
                    boolean started = startedModelCalls.contains(callKey);
                    String detail = started
                            ? "Execution started, but no final result was durably recorded. The run may have been interrupted; effects may have occurred. Inspect current evidence before retrying."
                            : "This tool intent was recorded but was never launched. It was not automatically replayed.";
                    events.add(AgentRunUiEvent.toolEvent(id++, started ? "tool_interrupted" : "tool_not_started",
                            CoreToolRegistry.humanizeToolName(call.optString("name", "tool")), detail, callId, null,
                            (long) (row.optDouble("timestamp", 0) * 1000))
                            .copyToolTimelineIdentity(scopedCallIdentity + "/" + callId, userMessageId));
                }
            } else if ("tool_presentation".equals(type) || "model_tool_result".equals(type) || "reflection_tool".equals(type)) {
                String callId = row.optString("callId", "");
                boolean presentation = "tool_presentation".equals(type);
                boolean modelResult = "model_tool_result".equals(type);
                String callKey=toolRowKey(row,modelBatches);
                if (presentation ? presentations.get(callKey) != row
                        : presentations.containsKey(callKey) || (!modelResult && modelResults.contains(callKey))) continue;
                JSONObject sourceCall = modelResult ? modelBatches.get(row.optString("batchId")) : modelCalls.get(callKey);
                String userMessageId = modelResult && sourceCall != null ? sourceCall.optString("userMessageId") : row.optString("userMessageId", "");
                if (userMessageId.isEmpty() || rowIndex < latestInvalidatedToolTurnRow.getOrDefault(userMessageId, -1)) continue;
                if (typedCalls.contains(callKey) || "reflection_tool".equals(type) && typedReflectionCalls.contains(userMessageId+"/"+callId)) continue;
                String detail = presentation ? row.optString("detail", "") : modelResult ? row.optString("output", "") : "";
                String stage;
                if (modelResult) {
                    String lifecycle = row.optString("lifecycle", "RESULT");
                    if ("INTERRUPTED_UNCERTAIN".equals(lifecycle)) stage = "tool_interrupted";
                    else if ("NEVER_LAUNCHED".equals(lifecycle)) stage = "tool_not_started";
                    else stage = detail.startsWith("Tool error:") ? "tool_error" : "tool_result";
                } else stage = row.optString("stage", "tool_result");
                if (!"tool_result".equals(stage) && !"tool_error".equals(stage)
                        && !"tool_interrupted".equals(stage) && !"tool_not_started".equals(stage)) continue;
                String toolName = row.optString("toolName", "tool");
                if (modelResult) toolName = reflectionTools.containsKey(callKey)
                        ? reflectionTools.get(callKey).optString("toolName", toolName) : CoreToolRegistry.humanizeToolName(toolName);
                String recordedPreview = presentation ? row.optString("previewId", "")
                        : modelResult ? modelPreviewToken(toolName, detail) : "";
                boolean previewExpected = row.optBoolean("previewExpected", false)
                        || (modelResult && (isExplicitPreviewCall(sourceCall, callId)
                            || (isPreviewTool(toolName) && sourceCall == null)));
                boolean legacyPreview = recordedPreview.isEmpty() && !previewExpected
                        && "tool_result".equals(stage) && isPreviewTool(toolName);
                String preview = verifiedPreview(sessionId, legacyPreview ? WorkspaceStore.projectIdForSession(sessionId) : recordedPreview, ownedDeliveredFiles);
                if (legacyPreview && preview != null) detail += (detail.isEmpty() ? "" : "\n\n")
                        + "Original preview details are unavailable. This opens the current verified index.html in this conversation's workspace.";
                else if ((!recordedPreview.isEmpty() || previewExpected) && preview == null) detail += "\n\nThe recorded preview is no longer available in this conversation.";
                events.add(AgentRunUiEvent.toolEvent(id++, stage, toolName, detail.isEmpty() ? null : detail,
                        callId.isEmpty() ? null : callId, preview,
                        (long) (row.optDouble("timestamp", 0) * 1000))
                        .copyToolPresentation(presentation ? row.optString("auditDetail", "") : null, legacyPreview && preview != null)
                        .copyToolTimelineIdentity(callKey, userMessageId));
            } else if ("compaction".equals(type)) {
                if (rowIndex < latestInvalidationRow) continue;
                int count = row.optInt("summarizedMessages", 0);
                events.add(AgentRunUiEvent.compactionEvent(id++, row.optString("summary", ""), count,
                        row.optString("mode", "all"), (long) (row.optDouble("timestamp", 0) * 1000)));
            } else if ("reflection_event".equals(type) || "reflection_commit".equals(type)) {
                String reflectionId = row.optString("reflectionId", "");
                JSONArray ids = row.optJSONArray("revisionIds");
                List<Long> revisionIds = new ArrayList<>();
                if (ids != null) for (int index = 0; index < ids.length(); index++) revisionIds.add(ids.optLong(index));
                String status = undoneReflectionIds.contains(reflectionId) ? "undone" : row.optString("status", "completed");
                if (!revisionIds.isEmpty()) {
                    events.add(AgentRunUiEvent.reflectionMemoryEvent(id++, row.optString("summary", ""),
                            reflectionId, revisionIds, status, (long) (row.optDouble("timestamp", 0) * 1000)));
                }
            } else if ("assistant_translation".equals(type)) {
                if (hiddenMessages.contains(messageId)
                        || rowIndex <= translationHiddenAt.getOrDefault(messageId, -1)
                        || latestTranslation.getOrDefault(messageId, -1) != rowIndex) continue;
                events.add(AgentRunUiEvent.translationEvent(id++, row.optString("language"),
                        row.optString("content", ""), messageId,
                        (long) (row.optDouble("timestamp", 0) * 1000)));
            } else if ("crew_snapshot".equals(type)) {
                CrewMissionSnapshot snapshot = CrewMissionSnapshot.fromJson(row);
                if (snapshot != null && !newerMarkers.latestCrewSnapshots.containsKey(snapshot.missionId)
                        && latestCrewRow.getOrDefault(snapshot.missionId, -1) == rowIndex) {
                    events.add(AgentRunUiEvent.crewMissionEvent(id++, latestCrew.get(snapshot.missionId)));
                }
            } else if ("user_decision_request".equals(type)) {
                events.add(userDecisionEvent(id++, row, userDecisionResolutions.get(row.optString("decisionId", ""))));
            }
        }
        return events;
    }

    private static AgentRunUiEvent userDecisionEvent(
            long id, JSONObject row, TimelineProjectionMarkers.DecisionResolution resolution) {
        List<UserDecisionOption> options = new ArrayList<>();
        JSONArray choices = row.optJSONArray("options");
        if (choices != null) for (int index = 0; index < choices.length(); index++) {
            JSONObject choice = choices.optJSONObject(index);
            if (choice == null) continue;
            String roleName = choice.optString("role", "default").toUpperCase(Locale.ROOT);
            UserDecisionRole role;
            try { role = UserDecisionRole.valueOf(roleName); }
            catch (IllegalArgumentException invalidRole) { role = UserDecisionRole.DEFAULT; }
            String optionId = choice.optString("id", "").trim();
            String label = choice.optString("label", "").trim();
            if (!optionId.isEmpty() && !label.isEmpty()) options.add(new UserDecisionOption(optionId, label,
                    choice.optString("description", ""), role));
        }
        String status = resolution == null ? "UNANSWERED" : resolution.status;
        return AgentRunUiEvent.userDecisionEvent(id, row.optString("decisionId", ""),
                row.optString("title", ""), row.optString("body", ""), options,
                row.optBoolean("allowDismiss", true), status,
                resolution == null ? null : resolution.optionId,
                resolution == null ? null : resolution.optionLabel,
                (long) (row.optDouble("timestamp", 0) * 1000));
    }

    public int conversationMessageCount(String sessionId) {
        if (chatDatabaseBridge != null)
            return (int) chatDatabaseBridge.messageCountsSync(sessionId).getTotal();
        throw noChatDatabase();
    }

    private static List<ProactiveSuggestedReply> proactiveSuggestedReplies(JSONObject row) {
        JSONArray values = row.optJSONArray("suggestedReplies");
        if (values == null) return java.util.Collections.emptyList();
        List<ProactiveSuggestedReply> replies = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject item = values.optJSONObject(index);
            if (item == null) continue;
            String label = item.optString("label", "");
            String text = item.optString("text", "");
            if (!label.trim().isEmpty() && !text.trim().isEmpty()) replies.add(new ProactiveSuggestedReply(label, text));
        }
        return replies;
    }

    /** True while at least one compaction id has not been acknowledged by a reflection checkpoint. */
    public synchronized boolean hasPendingCompactionReflection(String sessionId) {
        return !pendingCompactionIds(sessionId).isEmpty();
    }

    public synchronized void acknowledgePendingCompactionReflection(String sessionId) {
        appendCompactionReflectionAcknowledgement(sessionId, pendingCompactionIds(sessionId));
    }

    private void appendCompactionReflectionAcknowledgement(String sessionId, List<String> compactionIds) {
        if (chatDatabaseBridge != null) {
            if (compactionIds == null || compactionIds.isEmpty()) return;
            requireChatDbSuccess(chatDatabaseBridge.acknowledgeCompactionsSync(sessionId,
                    new ArrayList<>(compactionIds), false), "acknowledge compaction reflection");
            return;
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        JSONObject row = new JSONObject();
        try {
            row.put("type", "compaction_reflection_consumed");
            row.put("compactionIds", new JSONArray(compactionIds));
            row.put("timestamp", System.currentTimeMillis() / 1000.0);
        }
        catch (Exception error) { throw new IllegalStateException(error); }
        synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
    }

    public synchronized void appendCompaction(String sessionId, String summary, int firstKept,
                                               String trigger, String mode, int summarizedMessages) {
        if (summary == null || summary.trim().isEmpty()) throw new IllegalArgumentException("Compaction summary is empty");
        if (chatDatabaseBridge != null) {
            requireChatDbSuccess(chatDatabaseBridge.appendCompactionSync(
                    new com.jarvys.agent.chatdb.CompactionInput(sessionId, summary, Math.max(0, firstKept),
                            trigger == null ? "manual" : trigger, mode == null ? "all" : mode,
                            Math.max(0, summarizedMessages), System.currentTimeMillis() / 1000.0)),
                    "append compaction");
            return;
        }
        if (chatDatabaseBridge == null) throw noChatDatabase();
        JSONObject row = new JSONObject();
        try {
            row.put("type", "compaction");
            row.put("summary", summary);
            row.put("firstKept", Math.max(0, firstKept));
            row.put("trigger", trigger == null ? "manual" : trigger);
            row.put("mode", mode == null ? "all" : mode);
            row.put("timestamp", System.currentTimeMillis() / 1000.0);
            row.put("summarizedMessages", Math.max(0, summarizedMessages));
        } catch (Exception error) { throw new IllegalStateException("Could not create compaction record", error); }
        synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
    }

    private void appendSessionRowLocked(File ledger, JSONObject row) {
        String sessionId = ledger.getName().substring(0, ledger.getName().length() - ".jsonl".length());
        if (conversationMetadata.read(sessionId).deleted) throw new IllegalStateException("This chat has been deleted");
        conversationFile(sessionId);
        Object previousIdentity = ledger.isFile() ? fileIdentity(ledger) : null;
        long previousLength = ledger.isFile() ? ledger.length() : 0L;
        long previousModified = ledger.isFile() ? ledger.lastModified() : 0L;
        byte[] record = "crew_snapshot".equals(row.optString("type"))
                ? losslessResultRecord(row) : (row.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(ledger, true)) {
            output.write(record);
            output.flush();
            output.getFD().sync();
        } catch (Exception error) {
            throw new IllegalStateException("Could not append conversation ledger entry", error);
        }
        String path = ledger.getAbsolutePath();
        ToolBatchCache batchCache = TOOL_BATCH_CACHES.get(path);
        if (batchCache != null) {
            Object appendedIdentity = fileIdentity(ledger);
            if (batchCache.matches(previousIdentity, previousLength, previousModified)
                    && sameFileIdentity(batchCache.fileIdentity, appendedIdentity)) {
                batchCache.rememberAppendedRow(row);
                batchCache.length = ledger.length();
                batchCache.lastModified = ledger.lastModified();
            } else {
                TOOL_BATCH_CACHES.remove(path);
            }
        }
        ToolActivityBatchCache activityCache = TOOL_ACTIVITY_BATCH_CACHES.get(path);
        if (activityCache != null) {
            Object appendedIdentity = fileIdentity(ledger);
            if (activityCache.matches(previousIdentity, previousLength, previousModified)
                    && sameFileIdentity(activityCache.fileIdentity, appendedIdentity)) {
                activityCache.advanceAppendedRow(row);
                activityCache.length = ledger.length();
                activityCache.lastModified = ledger.lastModified();
            } else {
                TOOL_ACTIVITY_BATCH_CACHES.remove(path);
            }
        }
        ConversationLedgerPager.invalidatePages(ledger);
        invalidateTimelinePageCache(ledger);
    }

    public String readFirstUserMessage(String sessionId) {
        if (chatDatabaseBridge != null) {
            for (JSONObject row : readConversationMessages(sessionId))
                if ("user".equals(row.optString("role"))) return row.optString("content", "");
            return null;
        }
        throw noChatDatabase();
    }

    public String readConversationTitle(String sessionId) {
        if (chatDatabaseBridge != null) {
            ConversationMetadataValue metadata = chatDatabaseBridge.readConversationMetadataSync(sessionId);
            if (metadata == null || metadata.getDeleted()) return null;
            return metadata.getTitle() != null ? metadata.getTitle() : metadata.getGeneratedTitle();
        }
        throw noChatDatabase();
    }

    public boolean claimConversationTitleGeneration(String sessionId) {
        if (chatDatabaseBridge != null) {
            synchronized (SESSION_TITLE_LOCK) {
                return !isConversationDeleted(sessionId) && readConversationTitle(sessionId) == null
                        && SESSION_TITLES_IN_PROGRESS.add(sessionId);
            }
        }
        throw noChatDatabase();
    }

    public boolean appendConversationTitleIfAbsent(String sessionId, String title) {
        if (chatDatabaseBridge != null) {
            String normalized = title == null ? "" : ConversationMetadataStore.normalizeTitle(title);
            if (normalized.isEmpty()) return false;
            return requireChatDbSuccess(chatDatabaseBridge.appendConversationTitleIfAbsentSync(sessionId, normalized),
                    "store generated conversation title");
        }
        throw noChatDatabase();
    }

    public void releaseConversationTitleGeneration(String sessionId) {
        synchronized (SESSION_TITLE_LOCK) {
            SESSION_TITLES_IN_PROGRESS.remove(sessionId);
        }
    }

    private static String readConversationTitleLocked(File ledger) {
        if (!ledger.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(ledger))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JSONObject row = new JSONObject(line);
                    if ("session_title".equals(row.optString("role"))) {
                        String title = row.optString("content", "").trim();
                        if (!title.isEmpty()) return title;
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not read conversation title", error);
        }
        return null;
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

    private File conversationFile(String sessionId) {
        ConversationMetadataStore.validateSessionId(sessionId);
        throw new IllegalStateException(chatDatabaseBridge == null
                ? noChatDatabase().getMessage()
                : "Conversation JSONL access is disabled; use ConversationRepository or ConversationReader");
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
            try {
                String sessionId = sessionForRunDirectory(run);
                if (sessionId == null || conversationMetadata.read(sessionId).deleted) continue;
            } catch (RuntimeException unsafeOrUnreadable) { continue; }
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

        // Run execution history remains in its existing, non-conversation steps.jsonl files.
        File[] runs = root.listFiles(File::isDirectory);
        if (runs != null) java.util.Arrays.sort(runs, (left, right) -> right.getName().compareTo(left.getName()));
        if (runs != null) for (File run : runs) {
            try {
                String sessionId = sessionForRunDirectory(run);
                if (sessionId == null) continue;
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

        for (ConversationSummary summary : chatDatabaseBridge.listAllConversationsSync(Collections.emptySet())) {
            if (summary.getDeleted()) continue;
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
                row.put("goal", summary.getGoal() == null ? row.optString("goal", "") : summary.getGoal())
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
        synchronized (SESSION_TITLE_LOCK) {
            String key = "post".equals(which) ? "post_screenshot" : "pre_screenshot";
            String fileName = row.optString(key, "");
            if (fileName.isEmpty() || "null".equals(fileName)) throw new IllegalStateException("No " + which + " screenshot was recorded for step " + stepNumber);
            File run = new File(root, row.optString("run_id"));
            try {
                String sessionId = sessionForRunDirectory(run);
                if (sessionId == null || conversationMetadata.read(sessionId).deleted) throw new IllegalStateException("This chat is no longer available");
                File screenshot = new File(run, fileName);
                verifyRunPath(screenshot);
                return Base64.encodeToString(readBytes(screenshot), Base64.NO_WRAP);
            } catch (Exception error) { throw new IllegalStateException("Could not load screenshot for step " + stepNumber, error); }
        }
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
