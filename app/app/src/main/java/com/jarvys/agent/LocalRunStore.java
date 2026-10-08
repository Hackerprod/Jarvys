package com.jarvys.agent;

import android.content.Context;
import android.util.Base64;
import java.io.IOException;
import java.util.Collections;
import com.jarvys.agent.proactive.ProactiveConversation;
import com.jarvys.agent.tasks.ScheduledTaskConversation;

import com.jarvys.agent.device.ScreenData;
import com.jarvys.agent.crew.CrewMissionSnapshot;
import com.jarvys.agent.crew.CrewProcessIdentity;
import com.jarvys.agent.proactive.ProactiveReplyClaim;
import com.jarvys.agent.proactive.ProactiveSuggestedReply;
import com.jarvys.agent.UserDecisionOption;
import com.jarvys.agent.UserDecisionRole;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** App-private append-only run records and notes for graph/history tools. */
public final class LocalRunStore {
    private static final Object SESSION_TITLE_LOCK = new Object();
    private static final Set<String> SESSION_TITLES_IN_PROGRESS = new HashSet<>();
    private static final Map<String, String> CREW_SNAPSHOT_DIGESTS = new LinkedHashMap<>();
    private final File root;
    private final String runBoundary;
    private final ConversationMetadataStore conversationMetadata;
    private final Map<String, String> runSessions = new LinkedHashMap<>();
    private final File notes;
    private final File conversations;
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
        this(context.getFilesDir());
    }

    LocalRunStore(File filesDirectory) {
        File jarvys = new File(filesDirectory, "jarvys");
        root = new File(jarvys, "runs");
        notes = new File(jarvys, "notes");
        conversations = new File(jarvys, "conversations");
        conversationMetadata = new ConversationMetadataStore(filesDirectory);
        try {
            runBoundary = new File(new File(filesDirectory.getCanonicalFile(), "jarvys"), "runs").getPath();
            verifyRunPath(root);
        } catch (IOException error) { throw new IllegalStateException("Unsafe local run directory", error); }
        ensureDirectory(root);
        ensureDirectory(notes);
        ensureDirectory(conversations);
    }

    public synchronized String beginRun(String goal) {
        return beginRun(goal, null);
    }

    public synchronized String beginRun(String goal, String sessionId) {
        synchronized (SESSION_TITLE_LOCK) {
            if (sessionId != null && !sessionId.trim().isEmpty() && conversationMetadata.read(sessionId).deleted) {
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

    /** Append a versioned, recoverable Crew snapshot to the existing per-conversation JSONL ledger. */
    public synchronized void appendCrewMissionSnapshot(CrewMissionSnapshot snapshot) {
        if (snapshot == null || snapshot.conversationId == null || snapshot.conversationId.trim().isEmpty()) {
            throw new IllegalArgumentException("Crew snapshot requires a conversation id");
        }
        JSONObject row = snapshot.toJson();
        String digest;
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(row.toString().getBytes(StandardCharsets.UTF_8));
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

    /** Reads the latest schema-supported Crew snapshot for each mission, preserving insertion order. */
    public synchronized List<CrewMissionSnapshot> readCrewMissionSnapshots(String sessionId) {
        Map<String, CrewMissionSnapshot> latest = new LinkedHashMap<>();
        for (JSONObject row : readConversationRows(sessionId)) {
            if (!"crew_snapshot".equals(row.optString("type"))) continue;
            CrewMissionSnapshot snapshot = CrewMissionSnapshot.fromJson(row);
            if (snapshot != null && sessionId.equals(snapshot.conversationId)) latest.put(snapshot.missionId, snapshot);
        }
        return new ArrayList<>(latest.values());
    }

    /** Legacy conversations have no Crew rows and remain untouched. Active rows from an older process are interrupted, never resumed. */
    public synchronized List<CrewMissionSnapshot> recoverCrewMissions(String sessionId) {
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
        File ledger = conversationFile(sessionId);
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new IllegalArgumentException("Conversation role must be user or assistant");
        }
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
    public synchronized String appendProactiveAssistantMessageIfAbsent(String sessionId, String decisionId,
                                                                          String content) {
        return appendProactiveAssistantMessageIfAbsent(sessionId, decisionId, content, null, "");
    }

    public synchronized String appendProactiveAssistantMessageIfAbsent(String sessionId, String decisionId,
                                                                          String content,
                                                                          List<ProactiveSuggestedReply> replies,
                                                                          String threadKey) {
        if (decisionId == null || decisionId.trim().isEmpty()) {
            throw new IllegalArgumentException("A proactive decision id is required");
        }
        File ledger = conversationFile(sessionId);
        synchronized (SESSION_TITLE_LOCK) {
            for (JSONObject existing : readConversationRows(sessionId)) {
                if (decisionId.equals(existing.optString("proactiveDecisionId"))) {
                    return existing.optString("messageId", "");
                }
            }
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

    /** Appends the user-selected proactive reply before dispatching an ordinary agent turn. */
    public synchronized String appendProactiveUserMessage(String sessionId, String content, String threadKey,
                                                           String replyToMessageId) {
        if (content == null || content.trim().isEmpty() || threadKey == null || threadKey.trim().isEmpty()) {
            throw new IllegalArgumentException("A proactive user reply and thread key are required");
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
    public synchronized ProactiveReplyClaim claimProactiveSuggestedReply(String sessionId, String messageId, int index) {
        synchronized (SESSION_TITLE_LOCK) {
            List<JSONObject> rows = readConversationRows(sessionId);
            for (JSONObject row : rows) {
                if ("proactive_reply_used".equals(row.optString("type")) && messageId.equals(row.optString("messageId"))) return null;
            }
            JSONObject message = null;
            for (JSONObject row : rows) {
                if ("proactive_message".equals(row.optString("type")) && messageId.equals(row.optString("messageId"))) {
                    message = row; break;
                }
            }
            if (message == null || index < 0) return null;
            JSONArray replies = message.optJSONArray("suggestedReplies");
            if (replies == null || index >= replies.length()) return null;
            JSONObject reply = replies.optJSONObject(index);
            if (reply == null) return null;
            String text = reply.optString("text").trim();
            String threadKey = message.optString("proactiveThreadKey").trim();
            if (text.isEmpty() || threadKey.isEmpty()) return null;
            JSONObject claimed = new JSONObject();
            try {
                claimed.put("type", "proactive_reply_used");
                claimed.put("messageId", messageId);
                claimed.put("timestamp", System.currentTimeMillis() / 1000.0);
            } catch (Exception error) { throw new IllegalStateException("Could not claim proactive reply", error); }
            appendSessionRowLocked(conversationFile(sessionId), claimed);
            return new ProactiveReplyClaim(text, threadKey, "");
        }
    }

    public synchronized String proactiveThreadKeyForUserMessage(String sessionId, String userMessageId) {
        if (userMessageId == null || userMessageId.isEmpty()) return null;
        synchronized (SESSION_TITLE_LOCK) {
            for (JSONObject row : readConversationRows(sessionId)) {
                if ("user".equals(row.optString("role")) && userMessageId.equals(row.optString("messageId"))) {
                    String key = row.optString("proactiveThreadKey", "");
                    return key.isEmpty() ? null : key;
                }
            }
        }
        return null;
    }

    public synchronized String readConversationMessage(String sessionId, String messageId) {
        if (messageId == null || messageId.isEmpty()) return null;
        synchronized (SESSION_TITLE_LOCK) {
            for (JSONObject row : readConversationRows(sessionId)) {
                if ("user".equals(row.optString("role")) && messageId.equals(row.optString("messageId"))) {
                    return row.optString("content", "");
                }
            }
        }
        return null;
    }

    public synchronized void appendAssistantTranslation(String sessionId, String messageId,
                                                         String language, String translation) {
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

    public synchronized void appendAssistantRegenerated(String sessionId, String messageId) {
        appendMessageMarker(sessionId, "assistant_regenerated", messageId);
    }

    public synchronized void appendAssistantDeleted(String sessionId, String messageId) {
        appendMessageMarker(sessionId, "assistant_deleted", messageId);
    }

    public synchronized void appendAssistantTranslationHidden(String sessionId, String messageId) {
        appendMessageMarker(sessionId, "assistant_translation_hidden", messageId);
    }

    /** Persists an image reference and prompt only; raw image bytes stay in GeneratedImageStore. */
    public synchronized void appendGeneratedImageEvent(String sessionId, String relativePath,
                                                        String prompt, String revisedPrompt,
                                                        String size, String mimeType) {
        new GeneratedImageStore(root.getParentFile()).resolve(sessionId, relativePath);
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

    /** Removes a conversation ledger and only that conversation's generated image directory. */
    public synchronized boolean deleteConversation(String sessionId) {
        File ledger = conversationFile(sessionId);
        synchronized (SESSION_TITLE_LOCK) {
            conversationMetadata.update(sessionId, "deleted", true);
            SESSION_TITLES_IN_PROGRESS.remove(sessionId);
            boolean complete = true;
            try { complete &= !ledger.exists() || ledger.delete(); }
            catch (RuntimeException failure) { complete = false; }
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
    }

    public ConversationMetadataStore.Snapshot readConversationMetadata(String sessionId) {
        return conversationMetadata.read(sessionId);
    }

    public void renameConversation(String sessionId, String title) {
        conversationMetadata.update(sessionId, "title", ConversationMetadataStore.normalizeTitle(title));
    }

    public void setConversationPinned(String sessionId, boolean pinned) {
        conversationMetadata.update(sessionId, "pinned", pinned);
    }

    public void setConversationArchived(String sessionId, boolean archived) {
        conversationMetadata.update(sessionId, "archived", archived);
    }

    public synchronized List<ChatAttachment> readConversationAttachments(String sessionId, String messageId) {
        for (JSONObject row : readConversationRows(sessionId)) {
            if (messageId != null && messageId.equals(row.optString("messageId", ""))) return attachmentsForRow(sessionId, row);
        }
        return Collections.emptyList();
    }

    public synchronized boolean conversationHasAttachments(String sessionId) {
        for (JSONObject row : readConversationRows(sessionId)) if (!attachmentsForRow(sessionId, row).isEmpty()) return true;
        return false;
    }

    public synchronized boolean conversationHasPrivateImagesOrAttachments(String sessionId) {
        for (JSONObject row : readConversationRows(sessionId)) {
            if (!attachmentsForRow(sessionId, row).isEmpty() || "generated_image".equals(row.optString("type"))) return true;
        }
        return false;
    }

    public void markConversationHasPrivateCode(String sessionId) {
        if (!conversationMetadata.read(sessionId).privateCode) conversationMetadata.update(sessionId, "privateCode", true);
    }

    public boolean conversationHasPrivateCode(String sessionId) {
        ConversationMetadataStore.Snapshot privacy = conversationMetadata.read(sessionId);
        return privacy.privateCode || privacy.deleted;
    }

    synchronized List<ConversationImageReference> readConversationImageReferences(String sessionId) {
        List<ConversationImageReference> result = new ArrayList<>();
        if (ProactiveConversation.SESSION_ID.equals(sessionId) || ScheduledTaskConversation.SESSION_ID.equals(sessionId)) return result;
        String userMessageId = "";
        for (JSONObject row : readConversationRows(sessionId)) {
            if ("user".equals(row.optString("role"))) userMessageId = row.optString("messageId", "");
            for (ChatAttachment attachment : attachmentsForRow(sessionId, row)) {
                if (attachment.isImage()) result.add(ConversationImageReference.attachment(userMessageId, attachment));
            }
            if ("generated_image".equals(row.optString("type")) && "COMPLETED".equals(row.optString("status"))) {
                ConversationImageReference reference = ConversationImageReference.generated(
                        userMessageId, row.optString("imagePath", ""), row.optString("prompt", ""));
                if (reference != null) result.add(reference);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static List<ChatAttachment> attachmentsForRow(String sessionId, JSONObject row) {
        if (ProactiveConversation.SESSION_ID.equals(sessionId) || ScheduledTaskConversation.SESSION_ID.equals(sessionId)
                || !"user".equals(row.optString("role")) || !row.optString("proactiveThreadKey", "").isEmpty()) {
            return Collections.emptyList();
        }
        return ChatAttachment.fromJsonArray(row.optJSONArray("attachments"));
    }

    public synchronized void cleanupOrphanAttachments() {
        Map<String, Set<String>> references = new LinkedHashMap<>();
        synchronized (SESSION_TITLE_LOCK) {
            for (String sessionId : listConversationLedgerIds()) {
                Set<String> paths = new HashSet<>();
                for (JSONObject row : readConversationRows(sessionId)) {
                    for (ChatAttachment attachment : attachmentsForRow(sessionId, row)) paths.add(attachment.relativePath);
                }
                references.put(sessionId, paths);
            }
            new AttachmentStore(root.getParentFile()).cleanupOrphans(references);
        }
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
    public synchronized void appendConversationToolPresentation(String sessionId, String userMessageId,
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
            if (previewId != null && previewId.equals(WorkspaceStore.projectIdForSession(sessionId))) row.put("previewId", previewId);
            synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
        } catch (Exception failure) { throw new IllegalStateException("Could not persist tool details and preview", failure); }
    }

    /** Only reconnect a preview to this chat's canonical existing workspace. Never create a file. */
    private String verifiedPreview(String sessionId, String recordedId) {
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

    /** Persists only a trust marker; connector/MCP/workspace result bodies never enter reflection storage. */
    public synchronized void appendReflectionToolEvent(String sessionId, String userMessageId,
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
        synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
    }

    public static final class RegenerationPrompt {
        public final String text;
        public final String userMessageId;
        RegenerationPrompt(String text, String userMessageId) {
            this.text = text;
            this.userMessageId = userMessageId;
        }
    }

    public synchronized RegenerationPrompt findUserMessageBeforeAssistant(String sessionId, String messageId) {
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

    public synchronized boolean isLatestActiveAssistant(String sessionId, String messageId) {
        List<JSONObject> messages = readConversationMessages(sessionId);
        Set<String> hidden = hiddenAssistantIds(readConversationRows(sessionId));
        String latestId = null;
        for (JSONObject row : messages) {
            if ("assistant".equals(row.optString("role")) && !hidden.contains(row.optString("messageId"))) {
                latestId = row.optString("messageId", "");
            }
        }
        return messageId != null && messageId.equals(latestId);
    }

    /** Append-only reaction state; original message rows, indices and compaction boundaries stay intact. */
    public synchronized boolean setMessageReaction(String sessionId, String messageId, String emoji) {
        if (!MessageReactionTool.isOrdinaryChat(sessionId) || emoji == null
                || !emoji.isEmpty() && !MessageReactionEmoji.isValid(emoji))
            throw new IllegalArgumentException("Invalid chat reaction");
        synchronized (SESSION_TITLE_LOCK) {
            List<JSONObject> messages = readConversationMessages(sessionId);
            int matches = 0;
            boolean user = false;
            for (int index = 0; index < messages.size(); index++) {
                JSONObject message = messages.get(index);
                if (MessageReactionTool.stableMessageId(sessionId, message, index).equals(messageId)) {
                    matches++;
                    user = "user".equals(message.optString("role")) && message.optString("proactiveThreadKey").isEmpty();
                }
            }
            if (matches != 1 || !user) throw new IllegalArgumentException("Reaction target is not a unique user message in this chat");
            String previous = readMessageReactions(readConversationRows(sessionId)).getOrDefault(messageId, "");
            if (previous.equals(emoji)) return false;
            JSONObject row = new JSONObject();
            try {
                row.put("type", "message_reaction");
                row.put("version", 1);
                row.put("messageId", messageId);
                row.put("emoji", emoji);
                row.put("timestamp", System.currentTimeMillis() / 1000.0);
            } catch (org.json.JSONException error) { throw new IllegalStateException("Could not create reaction record", error); }
            appendSessionRowLocked(conversationFile(sessionId), row);
            return true;
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

    public synchronized List<JSONObject> readConversationMessages(String sessionId) {
        File ledger = conversationFile(sessionId);
        List<JSONObject> messages = new ArrayList<>();
        synchronized (SESSION_TITLE_LOCK) {
        if (conversationMetadata.read(sessionId).deleted || !ledger.isFile()) return messages;
        try (BufferedReader reader = new BufferedReader(new FileReader(ledger))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JSONObject row = new JSONObject(line);
                    String role = row.optString("role");
                    if (("user".equals(role) || "assistant".equals(role)) && row.has("content")) messages.add(row);
                } catch (Exception ignored) { }
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not read conversation messages", error);
        }
        }
        return messages;
    }

    /** Loads model context as the latest summary plus the raw tail after its persisted boundary. */
    public synchronized List<ConversationTurn> loadConversationContext(String sessionId) {
        List<JSONObject> rows = readConversationRows(sessionId);
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
        int messageIndex = 0;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            JSONObject row = rows.get(rowIndex);
            String role = row.optString("role");
            if (("user".equals(role) || "assistant".equals(role)) && row.has("content")) {
                int index = messageIndex++;
                if (index < firstKept || ("assistant".equals(role) && hidden.contains(row.optString("messageId", "")))) continue;
                context.add(ConversationTurn.messageWithAttachments(role, row.optString("content", ""), index, attachmentsForRow(sessionId, row)));
            } else if (toolGroups.containsKey(rowIndex)) {
                context.addAll(toolGroups.get(rowIndex));
            }
        }
        return context;
    }

    public synchronized int latestUserMessageIndex(String sessionId) {
        List<JSONObject> messages = readConversationMessages(sessionId);
        for (int index = messages.size() - 1; index >= 0; index--) {
            if ("user".equals(messages.get(index).optString("role"))) return index;
        }
        return -1;
    }

    public synchronized String latestUserMessageId(String sessionId) {
        List<JSONObject> messages = readConversationMessages(sessionId);
        for (int index = messages.size() - 1; index >= 0; index--) {
            JSONObject row = messages.get(index);
            if ("user".equals(row.optString("role"))) return row.optString("messageId", "");
        }
        return "";
    }

    File filesDirectory() { return root.getParentFile().getParentFile(); }

    synchronized List<JSONObject> readModelTranscriptRows(String sessionId) {
        List<JSONObject> modelRows = new ArrayList<>();
        for (JSONObject row : readConversationRows(sessionId))
            if (row.optString("type").startsWith("model_")) modelRows.add(row);
        return modelRows;
    }

    synchronized void appendModelTranscriptRow(String sessionId, JSONObject row) {
        String type = row.optString("type");
        if (!("model_tool_calls".equals(type) || "model_tool_started".equals(type)
                || "model_tool_result".equals(type) || "model_artifact".equals(type)) || row.has("role"))
            throw new IllegalArgumentException("Invalid model transcript record");
        synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
    }

    private List<JSONObject> readConversationRows(String sessionId) {
        File ledger = conversationFile(sessionId);
        List<JSONObject> rows = new ArrayList<>();
        synchronized (SESSION_TITLE_LOCK) {
            if (conversationMetadata.read(sessionId).deleted || !ledger.isFile()) return rows;
            try (BufferedReader reader = new BufferedReader(new FileReader(ledger))) {
                String line;
                while ((line = reader.readLine()) != null) try { rows.add(new JSONObject(line)); }
                catch (Exception ignored) { }
            } catch (Exception error) {
                throw new IllegalStateException("Could not read conversation ledger", error);
            }
        }
        return rows;
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

    public synchronized List<JSONObject> readActiveConversationMessages(String sessionId) {
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
        synchronized (SESSION_TITLE_LOCK) {
            List<String> ids = new ArrayList<>();
            for (String sessionId : listConversationLedgerIds()) {
                try { if (!conversationMetadata.read(sessionId).deleted) ids.add(sessionId); }
                catch (RuntimeException unsafeOrUnreadable) { }
            }
            return ids;
        }
    }

    private List<String> listConversationLedgerIds() {
        File[] files = conversations.listFiles((directory, name) -> name.endsWith(".jsonl"));
        if (files == null) return Collections.emptyList();
        List<String> ids = new ArrayList<>();
        for (File file : files) {
            String id = file.getName().substring(0, file.getName().length() - ".jsonl".length());
            try { conversationFile(id); ids.add(id); } catch (RuntimeException unsafe) { }
        }
        Collections.sort(ids);
        return ids;
    }

    public synchronized void appendReflectionCheckpoint(String sessionId, ReflectionPayload payload,
                                                        String reflectionId, String result) {
        if (payload == null || payload.endMessageId.isEmpty()) throw new IllegalArgumentException("Reflection checkpoint has no message boundary");
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
        if (payload.endMessageId.isEmpty()) {
            if (!payload.compactionIds.isEmpty()) appendCompactionReflectionAcknowledgement(sessionId, payload.compactionIds);
            return;
        }
        appendReflectionCheckpoint(sessionId, payload, "skipped", reason);
    }

    public synchronized void appendReflectionEvent(String sessionId, String reflectionId, String summary,
                                                   List<Long> revisionIds, String status) {
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
        JSONObject row = new JSONObject();
        try { row.put("type", "reflection_undo"); row.put("reflectionId", reflectionId); row.put("timestamp", System.currentTimeMillis() / 1000.0); }
        catch (Exception error) { throw new IllegalStateException("Could not create reflection undo event", error); }
        synchronized (SESSION_TITLE_LOCK) { appendSessionRowLocked(conversationFile(sessionId), row); }
    }

    public synchronized boolean hasReflectionCommit(String sessionId, String reflectionId) {
        for (JSONObject row : readConversationRows(sessionId)) {
            if ("reflection_commit".equals(row.optString("type"))
                    && reflectionId.equals(row.optString("reflectionId"))) return true;
        }
        return false;
    }

    public synchronized List<String> pendingCompactionIds(String sessionId) {
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

    /** UI timeline preserves every original user/assistant row and inserts read-only compaction notices. */
    public synchronized List<AgentRunUiEvent> readConversationTimeline(String sessionId) {
        List<AgentRunUiEvent> events = new ArrayList<>();
        List<JSONObject> rows = readConversationRows(sessionId);
        Map<String, CrewMissionSnapshot> latestCrew = new LinkedHashMap<>();
        Map<String, Integer> latestCrewRow = new LinkedHashMap<>();
        Set<String> consumedProactiveReplies = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            JSONObject row = rows.get(index);
            if ("proactive_reply_used".equals(row.optString("type"))) {
                String usedMessageId = row.optString("messageId", "");
                if (!usedMessageId.isEmpty()) consumedProactiveReplies.add(usedMessageId);
            }
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
        Map<String, JSONObject> userDecisionResolutions = new LinkedHashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            JSONObject row = rows.get(index);
            String type = row.optString("type");
            if ("user_decision_resolution".equals(type)) {
                String decisionId = row.optString("decisionId", "");
                if (!decisionId.isEmpty()) userDecisionResolutions.put(decisionId, row);
            }
            if ("assistant_regenerated".equals(type) || "assistant_deleted".equals(type)) {
                latestInvalidationRow = index;
                String userMessageId = row.optString("userMessageId", "");
                if (!userMessageId.isEmpty()) latestInvalidatedToolTurnRow.put(userMessageId, index);
            }
            if ("reflection_undo".equals(type)) undoneReflectionIds.add(rows.get(index).optString("reflectionId", ""));
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
        Map<String, JSONObject> presentations = new LinkedHashMap<>();
        Map<String, JSONObject> reflectionTools = new LinkedHashMap<>();
        Map<String, JSONObject> modelCalls = new LinkedHashMap<>();
        Set<String> modelResults = new HashSet<>();
        Set<String> startedModelCalls = new HashSet<>();
        for (JSONObject row : rows) {
            String type = row.optString("type");
            if ("tool_presentation".equals(type)) presentations.put(row.optString("callId"), row);
            else if ("reflection_tool".equals(type)) reflectionTools.put(row.optString("callId"), row);
            else if ("model_tool_calls".equals(type)) {
                JSONArray calls = row.optJSONArray("calls");
                if (calls != null) for (int i = 0; i < calls.length(); i++) {
                    JSONObject call = calls.optJSONObject(i);
                    if (call != null) modelCalls.put(call.optString("id"), row);
                }
            } else if ("model_tool_result".equals(type)) modelResults.add(row.optString("callId"));
            else if ("model_tool_started".equals(type)) startedModelCalls.add(row.optString("callId"));
        }
        Map<String, String> reactions = MessageReactionTool.isOrdinaryChat(sessionId)
                ? readMessageReactions(rows) : java.util.Collections.emptyMap();
        int messageIndex = 0;
        long id = 1;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            JSONObject row = rows.get(rowIndex);
            String role = row.optString("role");
            String type = row.optString("type");
            String messageId = row.optString("messageId", "");
            if (("user".equals(role) || "assistant".equals(role)) && row.has("content")) {
                if ("user".equals(role) && MessageReactionTool.isOrdinaryChat(sessionId)
                        && row.optString("proactiveThreadKey").isEmpty())
                    messageId = MessageReactionTool.stableMessageId(sessionId, row, messageIndex);
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
            } else if ("generated_image".equals(type)) {
                events.add(AgentRunUiEvent.generatedImageEvent(id++,
                        row.has("imagePath") ? row.optString("imagePath", null) : null,
                        row.optString("prompt", ""), row.optString("revisedPrompt", ""),
                        row.optString("mimeType", "image/png"), row.optString("size", ""),
                        row.optString("status", "FAILED"), row.optString("error", ""),
                        (long) (row.optDouble("timestamp", 0) * 1000)));
            } else if ("model_tool_calls".equals(type)) {
                String userMessageId = row.optString("userMessageId", "");
                if (userMessageId.isEmpty() || rowIndex < latestInvalidatedToolTurnRow.getOrDefault(userMessageId, -1)) continue;
                JSONArray calls = row.optJSONArray("calls");
                if (calls != null) for (int callIndex = 0; callIndex < calls.length(); callIndex++) {
                    JSONObject call = calls.optJSONObject(callIndex);
                    if (call == null) continue;
                    String callId = call.optString("id");
                    if (modelResults.contains(callId) || presentations.containsKey(callId) || reflectionTools.containsKey(callId)) continue;
                    boolean started = startedModelCalls.contains(callId);
                    String detail = started
                            ? "Execution started, but no final result was durably recorded. The run may have been interrupted; effects may have occurred. Inspect current evidence before retrying."
                            : "This tool intent was recorded but was never launched. It was not automatically replayed.";
                    events.add(AgentRunUiEvent.toolEvent(id++, started ? "tool_interrupted" : "tool_not_started",
                            CoreToolRegistry.humanizeToolName(call.optString("name", "tool")), detail, callId, null,
                            (long) (row.optDouble("timestamp", 0) * 1000)));
                }
            } else if ("tool_presentation".equals(type) || "model_tool_result".equals(type) || "reflection_tool".equals(type)) {
                String callId = row.optString("callId", "");
                boolean presentation = "tool_presentation".equals(type);
                boolean modelResult = "model_tool_result".equals(type);
                if (presentation ? presentations.get(callId) != row
                        : presentations.containsKey(callId) || (!modelResult && modelResults.contains(callId))) continue;
                JSONObject sourceCall = modelCalls.get(callId);
                String userMessageId = modelResult && sourceCall != null ? sourceCall.optString("userMessageId") : row.optString("userMessageId", "");
                if (userMessageId.isEmpty() || rowIndex < latestInvalidatedToolTurnRow.getOrDefault(userMessageId, -1)) continue;
                String detail = presentation ? row.optString("detail", "") : modelResult ? row.optString("output", "") : "";
                String stage = modelResult ? (detail.startsWith("Tool error:") ? "tool_error" : "tool_result") : row.optString("stage", "tool_result");
                if (!"tool_result".equals(stage) && !"tool_error".equals(stage)) continue;
                String toolName = row.optString("toolName", "tool");
                if (modelResult) toolName = reflectionTools.containsKey(callId)
                        ? reflectionTools.get(callId).optString("toolName", toolName) : CoreToolRegistry.humanizeToolName(toolName);
                String recordedPreview = presentation ? row.optString("previewId", "") : "";
                boolean legacyPreview = recordedPreview.isEmpty() && "tool_result".equals(stage) && isPreviewTool(toolName);
                String preview = verifiedPreview(sessionId, legacyPreview ? WorkspaceStore.projectIdForSession(sessionId) : recordedPreview);
                if (legacyPreview && preview != null) detail += (detail.isEmpty() ? "" : "\n\n")
                        + "Original preview details are unavailable. This opens the current verified index.html in this conversation's workspace.";
                else if (!recordedPreview.isEmpty() && preview == null) detail += "\n\nThe recorded preview file is no longer available in this conversation's workspace.";
                events.add(AgentRunUiEvent.toolEvent(id++, stage, toolName, detail.isEmpty() ? null : detail,
                        callId.isEmpty() ? null : callId, preview,
                        (long) (row.optDouble("timestamp", 0) * 1000))
                        .copyToolPresentation(presentation ? row.optString("auditDetail", "") : null, legacyPreview && preview != null));
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
                if (snapshot != null && latestCrewRow.getOrDefault(snapshot.missionId, -1) == rowIndex) {
                    events.add(AgentRunUiEvent.crewMissionEvent(id++, latestCrew.get(snapshot.missionId)));
                }
            } else if ("user_decision_request".equals(type)) {
                events.add(userDecisionEvent(id++, row, userDecisionResolutions.get(row.optString("decisionId", ""))));
            }
        }
        return events;
    }

    private static AgentRunUiEvent userDecisionEvent(long id, JSONObject row, JSONObject resolution) {
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
        String status = resolution == null ? "UNANSWERED" : resolution.optString("status", "UNANSWERED");
        return AgentRunUiEvent.userDecisionEvent(id, row.optString("decisionId", ""),
                row.optString("title", ""), row.optString("body", ""), options,
                row.optBoolean("allowDismiss", true), status,
                resolution == null ? null : resolution.optString("optionId", null),
                resolution == null ? null : resolution.optString("optionLabel", null),
                (long) (row.optDouble("timestamp", 0) * 1000));
    }

    public synchronized int conversationMessageCount(String sessionId) {
        return readConversationMessages(sessionId).size();
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
        byte[] record = (row.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(ledger, true)) {
            output.write(record);
            output.flush();
            output.getFD().sync();
        } catch (Exception error) {
            throw new IllegalStateException("Could not append conversation ledger entry", error);
        }
    }

    public String readFirstUserMessage(String sessionId) {
        File ledger = conversationFile(sessionId);
        synchronized (SESSION_TITLE_LOCK) {
            if (conversationMetadata.read(sessionId).deleted || !ledger.isFile()) return null;
            try (BufferedReader reader = new BufferedReader(new FileReader(ledger))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JSONObject row = new JSONObject(line);
                        if ("user".equals(row.optString("role")) && row.has("content")) {
                            return row.optString("content", "");
                        }
                    } catch (Exception ignored) { }
                }
            } catch (Exception error) {
                throw new IllegalStateException("Could not read first conversation message", error);
            }
        }
        return null;
    }

    public String readConversationTitle(String sessionId) {
        synchronized (SESSION_TITLE_LOCK) {
            ConversationMetadataStore.Snapshot metadata = conversationMetadata.read(sessionId);
            if (metadata.deleted) return null;
            return metadata.title != null ? metadata.title : readConversationTitleLocked(conversationFile(sessionId));
        }
    }

    public boolean claimConversationTitleGeneration(String sessionId) {
        File ledger = conversationFile(sessionId);
        synchronized (SESSION_TITLE_LOCK) {
            return !conversationMetadata.read(sessionId).deleted && readConversationTitle(sessionId) == null && SESSION_TITLES_IN_PROGRESS.add(sessionId);
        }
    }

    public boolean appendConversationTitleIfAbsent(String sessionId, String title) {
        File ledger = conversationFile(sessionId);
        String normalizedTitle = title == null ? "" : title.trim();
        if (normalizedTitle.isEmpty()) return false;
        synchronized (SESSION_TITLE_LOCK) {
            if (conversationMetadata.read(sessionId).deleted || readConversationTitle(sessionId) != null) return false;
            JSONObject row = new JSONObject();
            try {
                row.put("role", "session_title");
                row.put("content", normalizedTitle);
                row.put("timestamp", System.currentTimeMillis() / 1000.0);
                appendSessionRowLocked(ledger, row);
                return true;
            } catch (Exception error) {
                throw new IllegalStateException("Could not persist conversation title", error);
            }
        }
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
        File ledger = new File(conversations, sessionId + ".jsonl");
        try {
            String expectedRoot = new File(new File(runBoundary).getParentFile(), "conversations").getPath();
            if (!conversations.getCanonicalPath().equals(expectedRoot)
                    || !ledger.getCanonicalPath().equals(new File(expectedRoot, ledger.getName()).getPath())) {
                throw new IOException("Conversation paths cannot use symbolic links");
            }
        } catch (IOException error) { throw new IllegalArgumentException("Unsafe conversation ledger", error); }
        return ledger;
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
        return collectConversationHistory(Math.max(0, Math.min(limit, 100)));
    }

    public synchronized List<JSONObject> listRecentRuns(int limit, boolean includeArchived) {
        int bounded = Math.max(0, Math.min(limit, 100));
        List<JSONObject> result = collectConversationHistory(Integer.MAX_VALUE);
        if (!includeArchived) result.removeIf(row -> row.optBoolean("archived"));
        result.sort(LocalRunStore::comparePinnedHistory);
        return result.size() > bounded ? new ArrayList<>(result.subList(0, bounded)) : result;
    }

    public synchronized List<JSONObject> listConversations() {
        Map<String, JSONObject> sessions = new LinkedHashMap<>();
        for (JSONObject row : collectConversationHistory(Integer.MAX_VALUE)) sessions.putIfAbsent(row.optString("session_id"), row);
        List<JSONObject> result = new ArrayList<>(sessions.values());
        result.sort(LocalRunStore::comparePinnedHistory);
        return result;
    }

    private static int comparePinnedHistory(JSONObject left, JSONObject right) {
        int pinned = Boolean.compare(right.optBoolean("pinned"), left.optBoolean("pinned"));
        return pinned != 0 ? pinned : Double.compare(right.optDouble("timestamp"), left.optDouble("timestamp"));
    }

    private List<JSONObject> collectConversationHistory(int boundedLimit) {
        synchronized (SESSION_TITLE_LOCK) {
            List<JSONObject> recent = new ArrayList<>();
            if (boundedLimit == 0) return recent;
            File[] runs = root.listFiles(File::isDirectory);
            if (runs != null) java.util.Arrays.sort(runs, (left, right) -> right.getName().compareTo(left.getName()));
            if (runs != null) for (File run : runs) {
                try {
                    String sessionId = sessionForRunDirectory(run);
                    if (sessionId == null || conversationMetadata.read(sessionId).deleted) continue;
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
                    if (summary != null) recent.add(summary);
                } catch (Exception unsafeOrUnreadable) { }
            }
            for (String sessionId : listConversationLedgerIds()) {
                try {
                    if (conversationMetadata.read(sessionId).deleted) continue;
                    String title = readConversationTitle(sessionId);
                    if (title == null) title = ConversationTitle.fromFirstMessage(readFirstUserMessage(sessionId));
                    List<JSONObject> messages = readActiveConversationMessages(sessionId);
                    if (messages.isEmpty()) continue;
                    JSONObject latest = messages.get(messages.size() - 1);
                    String latestGoal = "";
                    for (int index = messages.size() - 1; index >= 0; index--) {
                        if ("user".equals(messages.get(index).optString("role"))) {
                            latestGoal = messages.get(index).optString("content", "");
                            break;
                        }
                    }
                    boolean found = false;
                    for (JSONObject summary : recent) {
                        if (!sessionId.equals(summary.optString("session_id"))) continue;
                        found = true;
                        if (!latestGoal.isEmpty()) summary.put("goal", latestGoal);
                        if (title != null) summary.put("title", title);
                        summary.put("timestamp", latest.optDouble("timestamp", summary.optDouble("timestamp")));
                        summary.put("turns", Math.max(summary.optInt("turns"), (messages.size() + 1) / 2));
                        summary.put("outcome", "CHAT").put("steps", 0);
                    }
                    if (!found) recent.add(new JSONObject().put("run_id", "chat_" + sessionId)
                            .put("session_id", sessionId).put("goal", latestGoal).put("title", title)
                            .put("timestamp", latest.optDouble("timestamp", 0)).put("outcome", "CHAT")
                            .put("steps", 0).put("turns", (messages.size() + 1) / 2));
                } catch (Exception unsafeOrUnreadable) { }
            }
            java.util.Iterator<JSONObject> iterator = recent.iterator();
            while (iterator.hasNext()) {
                JSONObject row = iterator.next();
                try {
                    ConversationMetadataStore.Snapshot metadata = conversationMetadata.read(row.optString("session_id"));
                    if (metadata.deleted) { iterator.remove(); continue; }
                    row.put("pinned", metadata.pinned).put("archived", metadata.archived);
                    if (metadata.title != null) row.put("title", metadata.title);
                } catch (Exception unsafeOrUnreadable) { iterator.remove(); }
            }
            recent.sort((left, right) -> Double.compare(right.optDouble("timestamp"), left.optDouble("timestamp")));
            return recent.size() > boundedLimit ? new ArrayList<>(recent.subList(0, boundedLimit)) : recent;
        }
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
        if (!directory.exists() && !directory.mkdirs()) {
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
