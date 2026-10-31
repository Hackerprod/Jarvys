package com.jarvys.agent.crew;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/** Immutable source description. It never grants permission or establishes source authenticity. */
public final class CrewInputBinding {
    public enum SourceKind { WORKSPACE_REPLY, NATIVE_CORRECT, CONTINUATION_ANSWER }
    public final SourceKind sourceKind;
    public final String sourceId, ownerCentralSessionId, workspaceId, executionSessionId, assignmentId;
    public final String missionId, botId, mailboxMessageId, sourcePayloadSha256, deliverySha256, admissionCycleId;

    private CrewInputBinding(SourceKind sourceKind, String sourceId, String ownerCentralSessionId,
            String workspaceId, String executionSessionId, String assignmentId, String missionId,
            String botId, String mailboxMessageId, String sourcePayloadSha256, String deliverySha256,
            String admissionCycleId) {
        if (sourceKind == null) throw new IllegalArgumentException("Input source kind is required");
        this.sourceKind = sourceKind;
        this.sourceId = required(sourceId); this.ownerCentralSessionId = required(ownerCentralSessionId);
        this.workspaceId = required(workspaceId); this.executionSessionId = required(executionSessionId);
        this.assignmentId = required(assignmentId); this.missionId = required(missionId);
        this.botId = required(botId); this.mailboxMessageId = required(mailboxMessageId);
        this.sourcePayloadSha256 = requireDigest(sourcePayloadSha256);
        this.deliverySha256 = requireDigest(deliverySha256);
        this.admissionCycleId = CrewCoordinationEvidence.requireCycleId(admissionCycleId);
    }

    /** Exact future payload, including whitespace; timestamps are deliberately not delivery identity. */
    public static CrewInputBinding create(SourceKind sourceKind, String sourceId, String ownerCentralSessionId,
            String workspaceId, String executionSessionId, String assignmentId, String missionId,
            String botId, String mailboxMessageId, String sourcePayloadSha256, String admissionCycleId,
            String exactText) {
        if (exactText == null || exactText.trim().isEmpty()) throw new IllegalArgumentException("Input text is required");
        String from = sourceKind == SourceKind.NATIVE_CORRECT ? "user" : "chief";
        CrewMessage.Type type = sourceKind == SourceKind.NATIVE_CORRECT ? CrewMessage.Type.USER : CrewMessage.Type.ANSWER;
        return new CrewInputBinding(sourceKind, sourceId, ownerCentralSessionId, workspaceId, executionSessionId,
                assignmentId, missionId, botId, mailboxMessageId, sourcePayloadSha256,
                deliverySha256(mailboxMessageId, executionSessionId, from, botId, type, exactText, Collections.emptyList()),
                admissionCycleId);
    }

    /** Sanitized copies may retain the binding, but can no longer mint original-input proof. */
    public boolean matchesDelivery(CrewMessage message) {
        return message != null && mailboxMessageId.equals(message.id)
                && executionSessionId.equals(message.conversationId) && botId.equals(message.to)
                && message.activity == null && (sourceKind == SourceKind.NATIVE_CORRECT
                    ? message.type == CrewMessage.Type.USER && "user".equals(message.from)
                    : message.type == CrewMessage.Type.ANSWER && "chief".equals(message.from))
                && deliverySha256.equals(deliverySha256(message));
    }

    public boolean samePayload(CrewInputBinding other) {
        return other != null && sourceKind == other.sourceKind && sourceId.equals(other.sourceId)
                && ownerCentralSessionId.equals(other.ownerCentralSessionId) && workspaceId.equals(other.workspaceId)
                && executionSessionId.equals(other.executionSessionId) && assignmentId.equals(other.assignmentId)
                && missionId.equals(other.missionId) && botId.equals(other.botId) && mailboxMessageId.equals(other.mailboxMessageId)
                && sourcePayloadSha256.equals(other.sourcePayloadSha256) && deliverySha256.equals(other.deliverySha256)
                && admissionCycleId.equals(other.admissionCycleId);
    }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("schemaVersion", 1).put("sourceKind", sourceKind.name()).put("sourceId", sourceId)
                    .put("ownerCentralSessionId", ownerCentralSessionId).put("workspaceId", workspaceId)
                    .put("executionSessionId", executionSessionId).put("assignmentId", assignmentId)
                    .put("missionId", missionId).put("botId", botId).put("mailboxMessageId", mailboxMessageId)
                    .put("sourcePayloadSha256", sourcePayloadSha256).put("deliverySha256", deliverySha256)
                    .put("admissionCycleId", admissionCycleId);
        } catch (Exception failure) { throw new IllegalStateException("Could not encode input binding", failure); }
    }

    public static CrewInputBinding fromJsonStrict(JSONObject row) {
        fields(row, "schemaVersion", "sourceKind", "sourceId", "ownerCentralSessionId", "workspaceId",
                "executionSessionId", "assignmentId", "missionId", "botId", "mailboxMessageId",
                "sourcePayloadSha256", "deliverySha256", "admissionCycleId");
        if (integer(row, "schemaVersion") != 1) throw new IllegalArgumentException("Unsupported input binding schema");
        return new CrewInputBinding(SourceKind.valueOf(string(row, "sourceKind")), string(row, "sourceId"),
                string(row, "ownerCentralSessionId"), string(row, "workspaceId"), string(row, "executionSessionId"),
                string(row, "assignmentId"), string(row, "missionId"), string(row, "botId"), string(row, "mailboxMessageId"),
                string(row, "sourcePayloadSha256"), string(row, "deliverySha256"), string(row, "admissionCycleId"));
    }

    public static String deliverySha256(CrewMessage message) {
        return deliverySha256(message.id, message.conversationId, message.from, message.to, message.type, message.text, message.refs);
    }

    private static String deliverySha256(String id, String conversation, String from, String to,
            CrewMessage.Type type, String text, List<String> refs) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            write(out, "crew-input-delivery-v1"); write(out, id); write(out, conversation); write(out, from);
            write(out, to); write(out, type.name()); write(out, text); out.writeInt(refs.size());
            for (String ref : refs) write(out, ref);
            return digest(bytes.toByteArray());
        } catch (Exception failure) { throw new IllegalStateException("Could not fingerprint input delivery", failure); }
    }

    static String hashText(String domain, String text) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            write(out, domain); write(out, text); return digest(bytes.toByteArray());
        } catch (Exception failure) { throw new IllegalStateException("Could not fingerprint input transport", failure); }
    }
    private static void write(DataOutputStream out, String text) throws java.io.IOException {
        if (text == null) throw new IllegalArgumentException("Canonical input text is required");
        out.writeInt(text.length()); for (int i = 0; i < text.length(); i++) out.writeChar(text.charAt(i));
    }
    private static String digest(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder(64);
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    static String requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid input digest");
        return value;
    }
    private static String required(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Input identity is required");
        return value;
    }
    static void fields(JSONObject row, String... expected) {
        if (row == null || row.length() != expected.length) throw new IllegalArgumentException("Invalid input proof fields");
        Set<String> names = new HashSet<>(Arrays.asList(expected));
        java.util.Iterator<String> keys = row.keys();
        while (keys.hasNext()) if (!names.contains(keys.next())) throw new IllegalArgumentException("Unexpected input proof field");
    }
    static String string(JSONObject row, String key) {
        Object value = row.opt(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("Invalid input proof " + key);
        return (String) value;
    }
    static long integer(JSONObject row, String key) {
        Object value = row.opt(key);
        if (!(value instanceof Integer) && !(value instanceof Long) && !(value instanceof Short) && !(value instanceof Byte))
            throw new IllegalArgumentException("Invalid input proof " + key);
        return ((Number) value).longValue();
    }
}
