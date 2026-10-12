package com.jarvys.agent.crew;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import org.json.JSONArray;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Exact untrusted worker-to-chief data from its producing execution cycle, never authority. */
public final class CrewCoordinationEvidence {
    public static final int SCHEMA_VERSION = 1;
    public static final String ROW_TYPE = "crew_coordination_evidence";
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("type", "crewCoordinationSchemaVersion",
            "evidenceId", "conversationId", "missionId", "botId", "cycleId", "messageId", "kind",
            "text", "refs", "producedAtMillis", "contentSha256"));
    public final String evidenceId;
    public final String conversationId;
    public final String missionId;
    public final String botId;
    public final String cycleId;
    public final String messageId;
    public final String kind;
    public final String text;
    public final List<String> refs;
    public final long producedAtMillis;
    public final String contentSha256;

    private CrewCoordinationEvidence(String conversationId, String missionId, String botId, String cycleId,
            String messageId, String kind, String text, List<String> refs, long producedAtMillis) {
        this.conversationId = required(conversationId, "conversationId");
        this.missionId = required(missionId, "missionId");
        this.botId = required(botId, "botId");
        this.cycleId = requireCycleId(cycleId);
        this.messageId = required(messageId, "messageId");
        if (!"QUESTION".equals(kind) && !"FINDING".equals(kind) && !"BLOCKER".equals(kind))
            throw new IllegalArgumentException("Invalid worker coordination kind");
        required(text, "text");
        if (refs == null) throw new IllegalArgumentException("refs is required");
        List<String> copy = new ArrayList<>();
        for (String ref : refs) {
            if (ref == null) throw new IllegalArgumentException("Invalid coordination ref");
            copy.add(ref);
        }
        if (producedAtMillis <= 0L) throw new IllegalArgumentException("Invalid result timestamp");
        this.kind = kind;
        this.text = text;
        this.refs = Collections.unmodifiableList(copy);
        this.producedAtMillis = producedAtMillis;
        this.evidenceId = identity(conversationId, missionId, botId, cycleId, messageId);
        this.contentSha256 = hash();
    }

    public static CrewCoordinationEvidence create(String conversationId, String missionId, String botId,
            String cycleId, String messageId, String kind, String text, List<String> refs, long producedAtMillis) {
        return new CrewCoordinationEvidence(conversationId, missionId, botId, cycleId,
                messageId, kind, text, refs, producedAtMillis);
    }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("type", ROW_TYPE).put("crewCoordinationSchemaVersion", SCHEMA_VERSION)
                    .put("evidenceId", evidenceId).put("conversationId", conversationId)
                    .put("missionId", missionId).put("botId", botId).put("cycleId", cycleId)
                    .put("messageId", messageId).put("kind", kind).put("text", text).put("refs", new JSONArray(refs))
                    .put("producedAtMillis", producedAtMillis).put("contentSha256", contentSha256);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not serialize worker coordination evidence", failure);
        }
    }

    /** No coercion, inferred identity, inferred success, or best-effort repair of damaged evidence. */
    public static CrewCoordinationEvidence fromJsonStrict(JSONObject row) {
        if (row == null) throw new IllegalArgumentException("Worker coordination row is required");
        if (row.length() != FIELDS.size()) throw new IllegalArgumentException("Unexpected worker coordination fields");
        java.util.Iterator<String> keys = row.keys();
        while (keys.hasNext()) if (!FIELDS.contains(keys.next()))
            throw new IllegalArgumentException("Unexpected worker coordination field");
        if (!ROW_TYPE.equals(string(row, "type")) || integer(row, "crewCoordinationSchemaVersion") != SCHEMA_VERSION)
            throw new IllegalArgumentException("Unsupported worker coordination evidence schema");
        CrewCoordinationEvidence evidence = create(string(row, "conversationId"), string(row, "missionId"),
                string(row, "botId"), string(row, "cycleId"), string(row, "messageId"), string(row, "kind"),
                string(row, "text"), strings(row, "refs"), integer(row, "producedAtMillis"));
        if (!evidence.evidenceId.equals(string(row, "evidenceId"))
                || !evidence.contentSha256.equals(string(row, "contentSha256")))
            throw new IllegalArgumentException("Worker coordination evidence identity or digest mismatch");
        return evidence;
    }

    public static CrewCoordinationEvidence fromJson(JSONObject row) { return fromJsonStrict(row); }

    /** Snapshots may use an empty identity only for legacy history, never for fresh evidence. */
    static String requireCycleId(String value) {
        required(value, "cycleId");
        try {
            if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid worker cycle identity", failure);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static String string(JSONObject row, String key) {
        Object value = row.opt(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("Invalid " + key);
        return (String) value;
    }

    private static long integer(JSONObject row, String key) {
        Object value = row.opt(key);
        if (!(value instanceof Byte) && !(value instanceof Short)
                && !(value instanceof Integer) && !(value instanceof Long))
            throw new IllegalArgumentException("Invalid " + key);
        return ((Number) value).longValue();
    }

    /**
     * Canonical v1: length-prefixed big-endian UTF-16 code units, then a big-endian timestamp.
     * Unlike replacement-based UTF-8 encoding, this also distinguishes unpaired surrogate text.
     */
    private String hash() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            write(output, "crew-coordination-content-v1");
            writeIdentity(output, conversationId, missionId, botId, cycleId, messageId);
            write(output, kind);
            write(output, text);
            output.writeInt(refs.size());
            for (String ref : refs) write(output, ref);
            output.writeLong(producedAtMillis);
            return digest(bytes.toByteArray());
        } catch (Exception failure) {
            throw new IllegalStateException("Could not hash worker coordination evidence", failure);
        }
    }

    private static String identity(String conversationId, String missionId, String botId, String cycleId, String messageId) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            write(output, "crew-coordination-identity-v1");
            writeIdentity(output, conversationId, missionId, botId, cycleId, messageId);
            return "crew-coordination-" + digest(bytes.toByteArray());
        } catch (Exception failure) {
            throw new IllegalStateException("Could not hash worker coordination identity", failure);
        }
    }

    private static String digest(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(hex[(value & 255) >>> 4]).append(hex[value & 15]);
        return result.toString();
    }

    private static void writeIdentity(DataOutputStream output, String conversationId, String missionId,
            String botId, String cycleId, String messageId) throws java.io.IOException {
        write(output, conversationId); write(output, missionId); write(output, botId);
        write(output, cycleId); write(output, messageId);
    }

    private static List<String> strings(JSONObject row, String key) {
        Object raw = row.opt(key);
        if (!(raw instanceof JSONArray)) throw new IllegalArgumentException("Invalid " + key);
        JSONArray array = (JSONArray) raw;
        List<String> result = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            Object value = array.opt(index);
            if (!(value instanceof String)) throw new IllegalArgumentException("Invalid coordination ref");
            result.add((String) value);
        }
        return result;
    }

    private static void write(DataOutputStream output, String value) throws java.io.IOException {
        output.writeInt(value.length());
        for (int index = 0; index < value.length(); index++) output.writeChar(value.charAt(index));
    }

    public boolean isReplyableKind() { return "QUESTION".equals(kind) || "BLOCKER".equals(kind); }

    public Reference reference() {
        return new Reference(evidenceId, conversationId, missionId, botId, cycleId, messageId, contentSha256);
    }

    /** Historical reference only. Copied message text/refs may be redacted and are NOT source bytes. */
    public static final class Reference {
        private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("schemaVersion", "evidenceId",
                "conversationId", "missionId", "botId", "cycleId", "messageId", "contentSha256"));
        public final String evidenceId, conversationId, missionId, botId, cycleId, messageId, contentSha256;
        private Reference(String evidenceId, String conversationId, String missionId, String botId,
                String cycleId, String messageId, String contentSha256) {
            this.conversationId = required(conversationId, "conversationId");
            this.missionId = required(missionId, "missionId");
            this.botId = required(botId, "botId");
            this.cycleId = requireCycleId(cycleId);
            this.messageId = required(messageId, "messageId");
            if (!identity(conversationId, missionId, botId, cycleId, messageId).equals(evidenceId))
                throw new IllegalArgumentException("Invalid coordination reference identity");
            if (contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid coordination reference digest");
            this.evidenceId = evidenceId;
            this.contentSha256 = contentSha256;
        }
        public boolean matches(CrewCoordinationEvidence evidence) {
            return evidence != null && evidenceId.equals(evidence.evidenceId)
                    && contentSha256.equals(evidence.contentSha256);
        }
        public JSONObject toJson() {
            try {
                return new JSONObject().put("schemaVersion", SCHEMA_VERSION).put("evidenceId", evidenceId)
                        .put("conversationId", conversationId).put("missionId", missionId).put("botId", botId)
                        .put("cycleId", cycleId).put("messageId", messageId).put("contentSha256", contentSha256);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        public static Reference fromJsonStrict(JSONObject row) {
            if (row == null || row.length() != FIELDS.size())
                throw new IllegalArgumentException("Invalid coordination reference fields");
            java.util.Iterator<String> keys = row.keys();
            while (keys.hasNext()) if (!FIELDS.contains(keys.next()))
                throw new IllegalArgumentException("Unknown coordination reference field");
            if (integer(row, "schemaVersion") != SCHEMA_VERSION)
                throw new IllegalArgumentException("Unsupported coordination reference schema");
            return new Reference(string(row, "evidenceId"), string(row, "conversationId"),
                    string(row, "missionId"), string(row, "botId"), string(row, "cycleId"),
                    string(row, "messageId"), string(row, "contentSha256"));
        }
    }
}
