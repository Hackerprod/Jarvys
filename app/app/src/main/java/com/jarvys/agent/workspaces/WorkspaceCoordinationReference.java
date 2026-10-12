package com.jarvys.agent.workspaces;

import com.jarvys.agent.crew.CrewCoordinationEvidence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;

/**
 * Immutable provenance metadata, never an instruction, approval, or verified/applied outcome.
 * A notice is the same metadata in Central; its existence proves local ledger delivery only.
 */
public final class WorkspaceCoordinationReference {
    public static final int SCHEMA_VERSION = 1;
    public static final String REFERENCE_TYPE = "workspace_coordination_reference";
    public static final String NOTICE_TYPE = "workspace_coordination_notice";
    public final String referenceId;
    public final String evidenceId;
    public final String contentSha256;
    public final String ownerCentralSessionId;
    public final String workspaceId;
    public final String executionSessionId;
    public final String assignmentId;
    public final String requestId;
    public final String missionId;
    public final String botId;
    public final String cycleId;
    public final String messageId;
    /** Registry revision observed at first registration, not the revision that produced/applied work. */
    public final long observedAssignmentRevision;
    public final String kind;
    public final long producedAtMillis;

    private WorkspaceCoordinationReference(String evidenceId, String contentSha256, String ownerCentralSessionId,
            String workspaceId, String executionSessionId, String assignmentId, String requestId,
            String missionId, String botId, String cycleId, String messageId, long observedAssignmentRevision,
            String kind, long producedAtMillis) {
        requireEvidenceId(evidenceId);
        if (contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")) invalid("content hash");
        if (!WorkspaceIdentity.CENTRAL_SESSION.equals(ownerCentralSessionId)) invalid("Central owner");
        WorkspaceIdentity.requireWorkspaceId(workspaceId);
        if (!WorkspaceIdentity.executionSessionId(workspaceId).equals(executionSessionId)) invalid("source owner");
        uuid(assignmentId, "assignment"); uuid(missionId, "mission"); uuid(cycleId, "cycle");
        token(requestId, "request"); token(botId, "bot"); token(messageId, "message");
        String expectedMission = UUID.nameUUIDFromBytes(("jarvys-workspace-mission-v1\u0000" + workspaceId
                + "\u0000" + requestId).getBytes(StandardCharsets.UTF_8)).toString();
        if (!expectedMission.equals(missionId)) invalid("request mission ownership");
        if (observedAssignmentRevision < 1 || producedAtMillis < 1) invalid("revision or timestamp");
        if (!Arrays.asList("QUESTION", "FINDING", "BLOCKER").contains(kind)) invalid("coordination kind");
        this.evidenceId = evidenceId; this.contentSha256 = contentSha256;
        this.ownerCentralSessionId = ownerCentralSessionId; this.workspaceId = workspaceId;
        this.executionSessionId = executionSessionId; this.assignmentId = assignmentId;
        this.requestId = requestId; this.missionId = missionId; this.botId = botId; this.cycleId = cycleId; this.messageId = messageId;
        this.observedAssignmentRevision = observedAssignmentRevision;
        this.kind = kind; this.producedAtMillis = producedAtMillis;
        this.referenceId = "workspace-coordination-" + sha256("jarvys-workspace-coordination-reference-v1\u0000"
                + evidenceId + "\u0000" + assignmentId);
    }

    public static WorkspaceCoordinationReference create(CrewCoordinationEvidence evidence, String ownerCentralSessionId,
            String workspaceId, String assignmentId, String requestId, long observedAssignmentRevision) {
        if (evidence == null) throw new IllegalArgumentException("Coordination evidence is required");
        CrewCoordinationEvidence.fromJsonStrict(evidence.toJson());
        return new WorkspaceCoordinationReference(evidence.evidenceId, evidence.contentSha256, ownerCentralSessionId,
                workspaceId, evidence.conversationId, assignmentId, requestId, evidence.missionId, evidence.botId,
                evidence.cycleId, evidence.messageId, observedAssignmentRevision, evidence.kind, evidence.producedAtMillis);
    }

    public JSONObject toJson() { return encode(REFERENCE_TYPE); }
    public JSONObject toNoticeJson() { return encode(NOTICE_TYPE); }
    private JSONObject encode(String type) {
        try {
            JSONObject row = new JSONObject();
            row.put("type", type); row.put("workspaceCoordinationSchemaVersion", SCHEMA_VERSION);
            row.put("referenceId", referenceId); row.put("evidenceId", evidenceId);
            row.put("contentSha256", contentSha256); row.put("ownerCentralSessionId", ownerCentralSessionId);
            row.put("workspaceId", workspaceId); row.put("executionSessionId", executionSessionId);
            row.put("assignmentId", assignmentId); row.put("requestId", requestId); row.put("missionId", missionId);
            row.put("botId", botId); row.put("cycleId", cycleId); row.put("messageId", messageId);
            row.put("observedAssignmentRevision", observedAssignmentRevision);
            row.put("kind", kind); row.put("producedAtMillis", producedAtMillis);
            return row;
        } catch (Exception error) { throw new IllegalStateException("Could not encode coordination reference", error); }
    }

    public static WorkspaceCoordinationReference fromJsonStrict(JSONObject row) { return decode(row, REFERENCE_TYPE); }
    public static WorkspaceCoordinationReference fromNoticeJsonStrict(JSONObject row) { return decode(row, NOTICE_TYPE); }
    private static WorkspaceCoordinationReference decode(JSONObject row, String type) {
        try {
            Set<String> fields = new HashSet<>(Arrays.asList("type", "workspaceCoordinationSchemaVersion", "referenceId",
                    "evidenceId", "contentSha256", "ownerCentralSessionId", "workspaceId", "executionSessionId",
                    "assignmentId", "requestId", "missionId", "botId", "cycleId", "messageId", "observedAssignmentRevision",
                    "kind", "producedAtMillis"));
            if (row == null || row.length() != fields.size()) invalid("reference schema");
            for (Iterator<String> keys = row.keys(); keys.hasNext();) if (!fields.contains(keys.next())) invalid("reference field");
            if (!type.equals(string(row, "type")) || integer(row, "workspaceCoordinationSchemaVersion") != SCHEMA_VERSION)
                invalid("reference version/type");
            WorkspaceCoordinationReference result = new WorkspaceCoordinationReference(string(row, "evidenceId"),
                    string(row, "contentSha256"), string(row, "ownerCentralSessionId"), string(row, "workspaceId"),
                    string(row, "executionSessionId"), string(row, "assignmentId"), string(row, "requestId"),
                    string(row, "missionId"), string(row, "botId"), string(row, "cycleId"), string(row, "messageId"),
                    integer(row, "observedAssignmentRevision"), string(row, "kind"), integer(row, "producedAtMillis"));
            if (!result.referenceId.equals(string(row, "referenceId"))) invalid("reference identity");
            return result;
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("Invalid coordination reference", error); }
    }

    public boolean matchesEvidence(CrewCoordinationEvidence evidence) {
        return evidence != null && evidenceId.equals(evidence.evidenceId) && contentSha256.equals(evidence.contentSha256)
                && executionSessionId.equals(evidence.conversationId) && missionId.equals(evidence.missionId)
                && botId.equals(evidence.botId) && cycleId.equals(evidence.cycleId) && messageId.equals(evidence.messageId)
                && kind.equals(evidence.kind) && producedAtMillis == evidence.producedAtMillis;
    }

    /** Revision alone may differ on retry; the persisted first observation must be returned unchanged. */
    public boolean sameBinding(WorkspaceCoordinationReference other) {
        return other != null && referenceId.equals(other.referenceId) && evidenceId.equals(other.evidenceId)
                && contentSha256.equals(other.contentSha256) && ownerCentralSessionId.equals(other.ownerCentralSessionId)
                && workspaceId.equals(other.workspaceId) && executionSessionId.equals(other.executionSessionId)
                && assignmentId.equals(other.assignmentId) && requestId.equals(other.requestId)
                && missionId.equals(other.missionId) && botId.equals(other.botId) && cycleId.equals(other.cycleId)
                && messageId.equals(other.messageId)
                && kind.equals(other.kind) && producedAtMillis == other.producedAtMillis;
    }

    public boolean samePayload(WorkspaceCoordinationReference other) {
        return sameBinding(other) && observedAssignmentRevision == other.observedAssignmentRevision;
    }

    public static void requireReferenceId(String value) {
        if (value == null || !value.matches("workspace-coordination-[0-9a-f]{64}")) invalid("reference id");
    }
    public static void requireEvidenceId(String value) {
        if (value == null || !value.matches("crew-coordination-[0-9a-f]{64}")) invalid("evidence id");
    }
    private static String string(JSONObject row, String key) throws Exception {
        Object value = row.get(key);
        if (!(value instanceof String)) invalid(key);
        return (String) value;
    }
    private static long integer(JSONObject row, String key) throws Exception {
        Object value = row.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) invalid(key);
        return ((Number) value).longValue();
    }
    private static void uuid(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) invalid(name);
    }
    private static void token(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}")) invalid(name);
    }
    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(64);
            for (byte item : hash) encoded.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
            return encoded.toString();
        } catch (Exception error) { throw new IllegalStateException("Could not fingerprint coordination reference", error); }
    }
    private static void invalid(String field) { throw new IllegalArgumentException("Invalid coordination " + field); }
}
