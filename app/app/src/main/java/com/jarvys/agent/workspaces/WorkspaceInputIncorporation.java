package com.jarvys.agent.workspaces;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;

/**
 * Immutable metadata identifying an exact input in a durability-confirmed worker checkpoint.
 * This is historical incorporation evidence, never dispatch state, authority, understanding,
 * completion, or a claim that the input remains in the current compacted model context.
 * Both kinds live in the existing Central conversation ledger; native SQL remains authoritative.
 */
public final class WorkspaceInputIncorporation {
    public static final int SCHEMA_VERSION = 1;
    public static final String ROW_TYPE = "workspace_input_incorporation";
    public enum SourceKind { REPLY, NATIVE_CORRECT }
    public final String receiptId;
    public final SourceKind sourceKind;
    public final String sourceId, sourceReferenceId, mailboxMessageId;
    public final String ownerCentralSessionId, workspaceId, executionSessionId, assignmentId, requestId, missionId, botId;
    public final String admissionCycleId, firstIncorporationCycleId;
    public final String sourcePayloadSha256, deliveredContentSha256, transportKind, transportSha256;
    public final String checkpointPayloadSha256;
    public final long checkpointSavedAtMillis, recordedAtMillis;

    private WorkspaceInputIncorporation(SourceKind sourceKind, String sourceId, String sourceReferenceId,
            String mailboxMessageId, String ownerCentralSessionId, String workspaceId, String executionSessionId,
            String assignmentId, String requestId, String missionId, String botId, String admissionCycleId,
            String firstIncorporationCycleId, String sourcePayloadSha256, String deliveredContentSha256,
            String transportKind, String transportSha256, String checkpointPayloadSha256,
            long checkpointSavedAtMillis, long recordedAtMillis) {
        requireSource(sourceKind, sourceId);
        if (!WorkspaceIdentity.CENTRAL_SESSION.equals(ownerCentralSessionId)) invalid("Central owner");
        WorkspaceIdentity.requireWorkspaceId(workspaceId);
        if (!WorkspaceIdentity.executionSessionId(workspaceId).equals(executionSessionId)) invalid("execution owner");
        uuid(assignmentId); token(requestId); uuid(missionId); token(botId); uuid(mailboxMessageId);
        uuid(admissionCycleId); uuid(firstIncorporationCycleId);
        String expectedMission = UUID.nameUUIDFromBytes(("jarvys-workspace-mission-v1\u0000" + workspaceId
                + "\u0000" + requestId).getBytes(StandardCharsets.UTF_8)).toString();
        if (!expectedMission.equals(missionId)) invalid("request mission ownership");
        if (sourceKind == SourceKind.REPLY) {
            WorkspaceCoordinationReference.requireReferenceId(sourceReferenceId);
            if (!"INBOX".equals(transportKind) && !"ASK_CHIEF".equals(transportKind)) invalid("reply transport");
        } else {
            if (!sourceId.equals(sourceReferenceId)) invalid("native source reference");
            if (!"USER".equals(transportKind)) invalid("native transport");
        }
        digest(sourcePayloadSha256); digest(deliveredContentSha256); digest(transportSha256); digest(checkpointPayloadSha256);
        if (checkpointSavedAtMillis < 1 || recordedAtMillis < 1) invalid("timestamp");
        this.receiptId = receiptId(sourceKind, sourceId);
        this.sourceKind = sourceKind; this.sourceId = sourceId; this.sourceReferenceId = sourceReferenceId;
        this.mailboxMessageId = mailboxMessageId; this.ownerCentralSessionId = ownerCentralSessionId;
        this.workspaceId = workspaceId; this.executionSessionId = executionSessionId; this.assignmentId = assignmentId;
        this.requestId = requestId; this.missionId = missionId; this.botId = botId;
        this.admissionCycleId = admissionCycleId; this.firstIncorporationCycleId = firstIncorporationCycleId;
        this.sourcePayloadSha256 = sourcePayloadSha256; this.deliveredContentSha256 = deliveredContentSha256;
        this.transportKind = transportKind; this.transportSha256 = transportSha256;
        this.checkpointPayloadSha256 = checkpointPayloadSha256;
        this.checkpointSavedAtMillis = checkpointSavedAtMillis; this.recordedAtMillis = recordedAtMillis;
    }

    public static WorkspaceInputIncorporation create(SourceKind sourceKind, String sourceId, String sourceReferenceId,
            String mailboxMessageId, String ownerCentralSessionId, String workspaceId, String executionSessionId,
            String assignmentId, String requestId, String missionId, String botId, String admissionCycleId,
            String firstIncorporationCycleId, String sourcePayloadSha256, String deliveredContentSha256,
            String transportKind, String transportSha256, String checkpointPayloadSha256,
            long checkpointSavedAtMillis, long recordedAtMillis) {
        return new WorkspaceInputIncorporation(sourceKind, sourceId, sourceReferenceId, mailboxMessageId,
                ownerCentralSessionId, workspaceId, executionSessionId, assignmentId, requestId, missionId, botId,
                admissionCycleId, firstIncorporationCycleId, sourcePayloadSha256, deliveredContentSha256,
                transportKind, transportSha256, checkpointPayloadSha256, checkpointSavedAtMillis, recordedAtMillis);
    }

    /** A later supporting checkpoint is an observation; retain the complete first receipt. */
    public boolean sameBinding(WorkspaceInputIncorporation other) {
        return other != null && receiptId.equals(other.receiptId) && sourceKind == other.sourceKind
                && sourceId.equals(other.sourceId) && sourceReferenceId.equals(other.sourceReferenceId)
                && mailboxMessageId.equals(other.mailboxMessageId) && ownerCentralSessionId.equals(other.ownerCentralSessionId)
                && workspaceId.equals(other.workspaceId) && executionSessionId.equals(other.executionSessionId)
                && assignmentId.equals(other.assignmentId) && requestId.equals(other.requestId)
                && missionId.equals(other.missionId) && botId.equals(other.botId)
                && admissionCycleId.equals(other.admissionCycleId) && firstIncorporationCycleId.equals(other.firstIncorporationCycleId)
                && sourcePayloadSha256.equals(other.sourcePayloadSha256) && deliveredContentSha256.equals(other.deliveredContentSha256)
                && transportKind.equals(other.transportKind) && transportSha256.equals(other.transportSha256);
    }

    /** Exact persisted observation, for disclosure after idempotent appends returned the first row. */
    public boolean samePayload(WorkspaceInputIncorporation other) {
        return sameBinding(other) && checkpointPayloadSha256.equals(other.checkpointPayloadSha256)
                && checkpointSavedAtMillis == other.checkpointSavedAtMillis && recordedAtMillis == other.recordedAtMillis;
    }

    /** Exact durable reply identity; PREPARED never proves a dispatch was admitted. */
    public boolean matchesReply(WorkspaceCoordinationReply reply) {
        if (reply == null || sourceKind != SourceKind.REPLY || reply.state == WorkspaceCoordinationReply.State.PREPARED) return false;
        WorkspaceCoordinationReference ref = reply.reference;
        return sourceId.equals(reply.replyId) && sourceReferenceId.equals(ref.referenceId)
                && mailboxMessageId.equals(reply.mailboxMessageId) && sourcePayloadSha256.equals(reply.payloadSha256)
                && ownerCentralSessionId.equals(ref.ownerCentralSessionId) && workspaceId.equals(ref.workspaceId)
                && executionSessionId.equals(ref.executionSessionId) && assignmentId.equals(ref.assignmentId)
                && requestId.equals(ref.requestId) && missionId.equals(ref.missionId) && botId.equals(ref.botId)
                && admissionCycleId.equals(ref.cycleId);
    }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("type", ROW_TYPE).put("workspaceInputIncorporationSchemaVersion", SCHEMA_VERSION)
                    .put("receiptId", receiptId).put("sourceKind", sourceKind.name()).put("sourceId", sourceId)
                    .put("sourceReferenceId", sourceReferenceId).put("mailboxMessageId", mailboxMessageId)
                    .put("ownerCentralSessionId", ownerCentralSessionId).put("workspaceId", workspaceId)
                    .put("executionSessionId", executionSessionId).put("assignmentId", assignmentId)
                    .put("requestId", requestId).put("missionId", missionId).put("botId", botId)
                    .put("admissionCycleId", admissionCycleId).put("firstIncorporationCycleId", firstIncorporationCycleId)
                    .put("sourcePayloadSha256", sourcePayloadSha256).put("deliveredContentSha256", deliveredContentSha256)
                    .put("transportKind", transportKind).put("transportSha256", transportSha256)
                    .put("checkpointPayloadSha256", checkpointPayloadSha256)
                    .put("checkpointSavedAtMillis", checkpointSavedAtMillis).put("recordedAtMillis", recordedAtMillis);
        } catch (Exception error) { throw new IllegalStateException("Could not encode checkpoint incorporation", error); }
    }

    public static WorkspaceInputIncorporation fromJsonStrict(JSONObject row) {
        fields(row, "type", "workspaceInputIncorporationSchemaVersion", "receiptId", "sourceKind", "sourceId",
                "sourceReferenceId", "mailboxMessageId", "ownerCentralSessionId", "workspaceId", "executionSessionId",
                "assignmentId", "requestId", "missionId", "botId", "admissionCycleId", "firstIncorporationCycleId",
                "sourcePayloadSha256", "deliveredContentSha256", "transportKind", "transportSha256",
                "checkpointPayloadSha256", "checkpointSavedAtMillis", "recordedAtMillis");
        if (!ROW_TYPE.equals(string(row, "type")) || integer(row, "workspaceInputIncorporationSchemaVersion") != SCHEMA_VERSION)
            invalid("schema");
        WorkspaceInputIncorporation receipt = create(SourceKind.valueOf(string(row, "sourceKind")), string(row, "sourceId"),
                string(row, "sourceReferenceId"), string(row, "mailboxMessageId"), string(row, "ownerCentralSessionId"),
                string(row, "workspaceId"), string(row, "executionSessionId"), string(row, "assignmentId"),
                string(row, "requestId"), string(row, "missionId"), string(row, "botId"), string(row, "admissionCycleId"),
                string(row, "firstIncorporationCycleId"), string(row, "sourcePayloadSha256"), string(row, "deliveredContentSha256"),
                string(row, "transportKind"), string(row, "transportSha256"), string(row, "checkpointPayloadSha256"),
                integer(row, "checkpointSavedAtMillis"), integer(row, "recordedAtMillis"));
        if (!receipt.receiptId.equals(string(row, "receiptId"))) invalid("identity");
        return receipt;
    }

    /** Source identity alone is the claim. Changed mailbox/content/cycle must conflict, not create another claim. */
    public static String receiptId(SourceKind sourceKind, String sourceId) {
        requireSource(sourceKind, sourceId);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            for (String value : new String[] {"workspace-input-incorporation-id-v1", sourceKind.name(), sourceId}) {
                out.writeInt(value.length()); for (int i = 0; i < value.length(); i++) out.writeChar(value.charAt(i));
            }
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            StringBuilder encoded = new StringBuilder("workspace-input-incorporation-");
            for (byte value : hash) encoded.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return encoded.toString();
        } catch (Exception error) { throw new IllegalStateException("Could not fingerprint incorporation identity", error); }
    }

    public static void requireReceiptId(String value) {
        if (value == null || !value.matches("workspace-input-incorporation-[0-9a-f]{64}")) invalid("receipt id");
    }
    private static void requireSource(SourceKind kind, String id) {
        if (kind == null) invalid("source kind");
        if (kind == SourceKind.REPLY) {
            if (id == null || !id.matches("workspace-coordination-reply-[0-9a-f]{64}")) invalid("reply id");
        } else token(id);
    }
    private static void fields(JSONObject row, String... names) {
        Set<String> expected = new HashSet<>(Arrays.asList(names));
        if (row == null || row.length() != expected.size()) invalid("fields");
        java.util.Iterator<String> keys = row.keys(); while (keys.hasNext()) if (!expected.contains(keys.next())) invalid("field");
    }
    private static String string(JSONObject row, String key) {
        Object value = row.opt(key); if (!(value instanceof String)) invalid(key); return (String) value;
    }
    private static long integer(JSONObject row, String key) {
        Object value = row.opt(key); if (!(value instanceof Integer) && !(value instanceof Long)) invalid(key);
        return ((Number) value).longValue();
    }
    private static void token(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}")) invalid("token");
    }
    private static void uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) invalid("UUID");
    }
    private static void digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) invalid("digest");
    }
    private static void invalid(String field) { throw new IllegalArgumentException("Invalid input incorporation " + field); }
}
