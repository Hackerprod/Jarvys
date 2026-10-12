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

/** Exact untrusted ANSWER data. A durable local acceptance is never model consumption or completion. */
public final class WorkspaceCoordinationReply {
    public static final int SCHEMA_VERSION = 1;
    public static final String INTENT_TYPE = "workspace_coordination_reply_intent";
    public static final String STATE_TYPE = "workspace_coordination_reply_state";
    public enum State { PREPARED, STARTED, ACCEPTED_LOCAL }
    public final WorkspaceCoordinationReference reference;
    public final String replyId, replyKey, text, centralRequestId, originalUserRequest;
    public final String mailboxMessageId, payloadSha256;
    public final long preparedAtMillis, stateAtMillis;
    public final State state;

    private WorkspaceCoordinationReply(WorkspaceCoordinationReference reference, String replyKey,
            String text, String centralRequestId, String originalUserRequest, long preparedAtMillis,
            State state, long stateAtMillis) {
        if (reference == null) invalid("reference");
        WorkspaceCoordinationReference.fromJsonStrict(reference.toJson());
        if (!"QUESTION".equals(reference.kind) && !"BLOCKER".equals(reference.kind)) invalid("replyable kind");
        token(replyKey); token(centralRequestId);
        text(text); text(originalUserRequest);
        if (preparedAtMillis < 1 || stateAtMillis < preparedAtMillis || state == null) invalid("state time");
        if (state == State.PREPARED && stateAtMillis != preparedAtMillis) invalid("prepared time");
        this.reference = reference; this.replyKey = replyKey; this.text = text;
        this.centralRequestId = centralRequestId; this.originalUserRequest = originalUserRequest;
        this.preparedAtMillis = preparedAtMillis; this.state = state; this.stateAtMillis = stateAtMillis;
        this.replyId = "workspace-coordination-reply-" + hash(false);
        this.payloadSha256 = hash(true);
        this.mailboxMessageId = UUID.nameUUIDFromBytes(("jarvys-workspace-coordination-mailbox-v1\u0000" + replyId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static WorkspaceCoordinationReply create(WorkspaceCoordinationReference reference, String replyKey,
            String text, String centralRequestId, String originalUserRequest, long preparedAtMillis) {
        return new WorkspaceCoordinationReply(reference, replyKey, text, centralRequestId, originalUserRequest,
                preparedAtMillis, State.PREPARED, preparedAtMillis);
    }

    public boolean samePayload(WorkspaceCoordinationReply other) {
        // Creation time is the first observation, retained on same-payload retries.
        return other != null && replyId.equals(other.replyId) && payloadSha256.equals(other.payloadSha256)
                && reference.samePayload(other.reference) && replyKey.equals(other.replyKey)
                && text.equals(other.text) && centralRequestId.equals(other.centralRequestId)
                && originalUserRequest.equals(other.originalUserRequest);
    }

    public WorkspaceCoordinationReply withState(State value, long atMillis) {
        return new WorkspaceCoordinationReply(reference, replyKey, text, centralRequestId, originalUserRequest,
                preparedAtMillis, value, atMillis);
    }

    /** Full immutable intent only; state records never duplicate the answer/request body. */
    public JSONObject toJson() {
        try {
            return new JSONObject().put("type", INTENT_TYPE).put("workspaceCoordinationReplySchemaVersion", SCHEMA_VERSION)
                    .put("reference", reference.toJson()).put("referenceId", reference.referenceId)
                    .put("executionSessionId", reference.executionSessionId).put("replyId", replyId).put("replyKey", replyKey)
                    .put("text", text).put("centralRequestId", centralRequestId).put("originalUserRequest", originalUserRequest)
                    .put("mailboxMessageId", mailboxMessageId).put("payloadSha256", payloadSha256)
                    .put("preparedAtMillis", preparedAtMillis);
        } catch (Exception error) { throw new IllegalStateException("Could not encode coordination reply", error); }
    }

    public JSONObject toStateJson() {
        if (state == State.PREPARED) throw new IllegalStateException("PREPARED is an intent, not a transition");
        try {
            return new JSONObject().put("type", STATE_TYPE).put("workspaceCoordinationReplySchemaVersion", SCHEMA_VERSION)
                    .put("referenceId", reference.referenceId).put("executionSessionId", reference.executionSessionId)
                    .put("replyId", replyId).put("payloadSha256", payloadSha256).put("state", state.name())
                    .put("stateAtMillis", stateAtMillis);
        } catch (Exception error) { throw new IllegalStateException("Could not encode coordination reply state", error); }
    }

    public static WorkspaceCoordinationReply fromJsonStrict(JSONObject row) {
        fields(row, "type", "workspaceCoordinationReplySchemaVersion", "reference", "referenceId", "executionSessionId",
                "replyId", "replyKey", "text", "centralRequestId", "originalUserRequest", "mailboxMessageId", "payloadSha256", "preparedAtMillis");
        if (!INTENT_TYPE.equals(string(row, "type")) || integer(row, "workspaceCoordinationReplySchemaVersion") != SCHEMA_VERSION)
            invalid("schema");
        WorkspaceCoordinationReference reference = WorkspaceCoordinationReference.fromJsonStrict(row.optJSONObject("reference"));
        WorkspaceCoordinationReply reply = create(reference, string(row, "replyKey"), string(row, "text"),
                string(row, "centralRequestId"), string(row, "originalUserRequest"), integer(row, "preparedAtMillis"));
        if (!reply.replyId.equals(string(row, "replyId")) || !reply.payloadSha256.equals(string(row, "payloadSha256"))
                || !reply.mailboxMessageId.equals(string(row, "mailboxMessageId"))
                || !reference.referenceId.equals(string(row, "referenceId"))
                || !reference.executionSessionId.equals(string(row, "executionSessionId"))) invalid("binding");
        return reply;
    }

    /** Metadata-only transition, strict even when encountered by an old result scan. */
    public static final class Transition {
        public final String referenceId, executionSessionId, replyId, payloadSha256;
        public final State state;
        public final long stateAtMillis;
        private Transition(String referenceId, String executionSessionId, String replyId, String payloadSha256,
                State state, long stateAtMillis) {
            WorkspaceCoordinationReference.requireReferenceId(referenceId);
            if (!WorkspaceIdentity.isExecutionSession(executionSessionId)) invalid("source");
            if (!replyId.matches("workspace-coordination-reply-[0-9a-f]{64}")) invalid("reply id");
            if (!payloadSha256.matches("[0-9a-f]{64}")) invalid("payload hash");
            if (state == State.PREPARED || stateAtMillis < 1) invalid("transition");
            this.referenceId = referenceId; this.executionSessionId = executionSessionId;
            this.replyId = replyId; this.payloadSha256 = payloadSha256;
            this.state = state; this.stateAtMillis = stateAtMillis;
        }
    }

    public static Transition fromStateJsonStrict(JSONObject row) {
        fields(row, "type", "workspaceCoordinationReplySchemaVersion", "referenceId", "executionSessionId",
                "replyId", "payloadSha256", "state", "stateAtMillis");
        if (!STATE_TYPE.equals(string(row, "type")) || integer(row, "workspaceCoordinationReplySchemaVersion") != SCHEMA_VERSION)
            invalid("state schema");
        return new Transition(string(row, "referenceId"), string(row, "executionSessionId"), string(row, "replyId"),
                string(row, "payloadSha256"), State.valueOf(string(row, "state")), integer(row, "stateAtMillis"));
    }

    private String hash(boolean payload) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            write(out, payload ? "workspace-coordination-reply-payload-v1" : "workspace-coordination-reply-id-v1");
            write(out, reference.referenceId); write(out, replyKey);
            if (payload) {
                write(out, reference.evidenceId); write(out, reference.contentSha256); write(out, reference.ownerCentralSessionId);
                write(out, reference.workspaceId); write(out, reference.executionSessionId); write(out, reference.assignmentId);
                write(out, reference.requestId); write(out, reference.missionId); write(out, reference.botId);
                write(out, reference.cycleId); write(out, reference.messageId); write(out, reference.kind);
                out.writeLong(reference.observedAssignmentRevision); out.writeLong(reference.producedAtMillis);
                write(out, text); write(out, centralRequestId); write(out, originalUserRequest);
            }
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            StringBuilder value = new StringBuilder(64);
            for (byte item : hash) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
            return value.toString();
        } catch (Exception error) { throw new IllegalStateException("Could not fingerprint coordination reply", error); }
    }
    private static void write(DataOutputStream out, String value) throws java.io.IOException {
        out.writeInt(value.length()); for (int i = 0; i < value.length(); i++) out.writeChar(value.charAt(i));
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
    private static void text(String value) {
        // Existing CentralWorkspaceTool input bound, not a truncation or a new broader budget.
        if (value == null || value.trim().isEmpty() || value.length() > 65536
                || value.getBytes(StandardCharsets.UTF_8).length > 65536) invalid("text bound");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) invalid("NUL text");
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) invalid("text encoding");
            } else if (Character.isLowSurrogate(c)) invalid("text encoding");
        }
    }
    private static void invalid(String field) { throw new IllegalArgumentException("Invalid coordination reply " + field); }
}
