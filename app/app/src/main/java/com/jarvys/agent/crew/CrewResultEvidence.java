package com.jarvys.agent.crew;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Exact, untrusted worker output from one completed execution cycle; never execution authority. */
public final class CrewResultEvidence {
    public static final int SCHEMA_VERSION = 1;
    public static final String ROW_TYPE = "crew_result_evidence";
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("type", "crewResultSchemaVersion",
            "evidenceId", "conversationId", "missionId", "botId", "cycleId", "reportedStatus",
            "resultText", "producedAtMillis", "contentSha256"));
    public final String evidenceId;
    public final String conversationId;
    public final String missionId;
    public final String botId;
    public final String cycleId;
    public final String reportedStatus;
    public final String resultText;
    public final long producedAtMillis;
    public final String contentSha256;

    private CrewResultEvidence(String conversationId, String missionId, String botId, String cycleId,
            String reportedStatus, String resultText, long producedAtMillis) {
        this.conversationId = required(conversationId, "conversationId");
        this.missionId = required(missionId, "missionId");
        this.botId = required(botId, "botId");
        this.cycleId = requireCycleId(cycleId);
        if (!"DONE".equals(reportedStatus) && !"PARTIAL".equals(reportedStatus)
                && !"FAILED".equals(reportedStatus) && !"STOPPED".equals(reportedStatus))
            throw new IllegalArgumentException("Invalid worker result status");
        if (resultText == null) throw new IllegalArgumentException("resultText is required");
        if (producedAtMillis <= 0L) throw new IllegalArgumentException("Invalid result timestamp");
        this.reportedStatus = reportedStatus;
        this.resultText = resultText;
        this.producedAtMillis = producedAtMillis;
        this.evidenceId = "crew-result-" + hash(false);
        this.contentSha256 = hash(true);
    }

    public static CrewResultEvidence create(String conversationId, String missionId, String botId,
            String cycleId, String reportedStatus, String resultText, long producedAtMillis) {
        return new CrewResultEvidence(conversationId, missionId, botId, cycleId,
                reportedStatus, resultText, producedAtMillis);
    }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("type", ROW_TYPE).put("crewResultSchemaVersion", SCHEMA_VERSION)
                    .put("evidenceId", evidenceId).put("conversationId", conversationId)
                    .put("missionId", missionId).put("botId", botId).put("cycleId", cycleId)
                    .put("reportedStatus", reportedStatus).put("resultText", resultText)
                    .put("producedAtMillis", producedAtMillis).put("contentSha256", contentSha256);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not serialize worker result evidence", failure);
        }
    }

    /** No coercion, inferred identity, inferred success, or best-effort repair of damaged evidence. */
    public static CrewResultEvidence fromJsonStrict(JSONObject row) {
        if (row == null) throw new IllegalArgumentException("Worker result row is required");
        if (row.length() != FIELDS.size()) throw new IllegalArgumentException("Unexpected worker result fields");
        java.util.Iterator<String> keys = row.keys();
        while (keys.hasNext()) if (!FIELDS.contains(keys.next()))
            throw new IllegalArgumentException("Unexpected worker result field");
        if (!ROW_TYPE.equals(string(row, "type")) || integer(row, "crewResultSchemaVersion") != SCHEMA_VERSION)
            throw new IllegalArgumentException("Unsupported worker result evidence schema");
        CrewResultEvidence evidence = create(string(row, "conversationId"), string(row, "missionId"),
                string(row, "botId"), string(row, "cycleId"), string(row, "reportedStatus"),
                string(row, "resultText"), integer(row, "producedAtMillis"));
        if (!evidence.evidenceId.equals(string(row, "evidenceId"))
                || !evidence.contentSha256.equals(string(row, "contentSha256")))
            throw new IllegalArgumentException("Worker result evidence identity or digest mismatch");
        return evidence;
    }

    public static CrewResultEvidence fromJson(JSONObject row) { return fromJsonStrict(row); }

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
    private String hash(boolean includePayload) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            write(output, includePayload ? "crew-result-content-v1" : "crew-result-identity-v1");
            write(output, conversationId);
            write(output, missionId);
            write(output, botId);
            write(output, cycleId);
            if (includePayload) {
                write(output, reportedStatus);
                write(output, resultText);
                output.writeLong(producedAtMillis);
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(hex[(value & 255) >>> 4]).append(hex[value & 15]);
            return result.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not hash worker result evidence", failure);
        }
    }

    private static void write(DataOutputStream output, String value) throws java.io.IOException {
        output.writeInt(value.length());
        for (int index = 0; index < value.length(); index++) output.writeChar(value.charAt(index));
    }
}
