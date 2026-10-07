package com.jarvys.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded JSON reflection payload. Tool results are represented only by trust-labelled markers. */
public final class ReflectionTranscriptBuilder {
    public static final int MAX_PAYLOAD_CHARS = 32_000;
    public static final int MAX_ENTRY_CHARS = 8_000;
    private static final Pattern COORDINATES = Pattern.compile("(?<![\\d.])-?\\d{1,3}\\.\\d{3,}\\s*[,;]\\s*-?\\d{1,3}\\.\\d{3,}(?![\\d.])");
    private static final Pattern STREET_ADDRESS = Pattern.compile("(?i)(?:\\b(?:street|st\\.?|avenue|ave\\.?|road|rd\\.?|calle|avenida|carrera)\\s+\\d{1,6}\\b[^\\n]{0,48}|\\b\\d{1,6}\\s+(?:[A-Za-z0-9.'-]+\\s+){0,4}(?:street|st\\.?|avenue|ave\\.?|road|rd\\.?|calle|avenida|carrera)\\b[^\\n]{0,24})");

    public static final class Entry {
        public final String role;
        public final String content;
        public final String source;
        public final String sourceMessageId;
        public final String capturedAt;
        public final String toolName;
        public final String callId;
        public final boolean resultOk;
        public Entry(String role, String content, String source) {
            this(role, content, source, "", "", "", "", true);
        }
        public Entry(String role, String content, String source, String sourceMessageId,
                     String capturedAt, String toolName, String callId, boolean resultOk) {
            this.role = role;
            this.content = content == null ? "" : content;
            this.source = source == null ? "" : source;
            this.sourceMessageId = sourceMessageId == null ? "" : sourceMessageId;
            this.capturedAt = capturedAt == null ? "" : capturedAt;
            this.toolName = toolName == null ? "" : toolName;
            this.callId = callId == null ? "" : callId;
            this.resultOk = resultOk;
        }
    }

    private ReflectionTranscriptBuilder() { }

    public static String limitSerializedPayload(String payload, int maximumChars) {
        int limit = Math.max(256, Math.min(MAX_PAYLOAD_CHARS, maximumChars));
        JSONArray array;
        try { array = new JSONArray(payload); }
        catch (Exception error) { throw new IllegalArgumentException("Reflection transcript is not valid JSON", error); }
        while (array.length() > 1 && array.toString().length() > limit) array.remove(array.length() - 1);
        if (array.toString().length() <= limit) return array.toString();
        if (array.length() == 0) return "[]";
        JSONObject last = array.optJSONObject(0);
        if (last == null) return "[]";
        String textField = last.has("text") ? "text" : "content";
        String content = last.optString(textField, "");
        int contentBudget = Math.max(0, content.length() - (array.toString().length() - limit));
        try {
            last.put(textField, contentBudget < 16 ? "[entry omitted to fit reflection context budget]"
                    : ReflectionTranscriptBuilder.sanitize(content.substring(Math.max(0, content.length() - contentBudget))));
        } catch (Exception error) { throw new IllegalStateException("Could not bound reflection transcript", error); }
        return array.toString();
    }

    public static String build(List<Entry> entries) {
        List<Entry> bounded = new ArrayList<>();
        for (Entry entry : entries) {
            String safe = sanitize(entry.content);
            if (safe.length() > MAX_ENTRY_CHARS) safe = safe.substring(0, MAX_ENTRY_CHARS) + "…[message truncated]";
            bounded.add(new Entry(entry.role, safe, entry.source, entry.sourceMessageId,
                    entry.capturedAt, entry.toolName, entry.callId, entry.resultOk));
        }
        boolean removed = false;
        String payload = serialize(bounded);
        while (payload.length() > MAX_PAYLOAD_CHARS && bounded.size() > 1) {
            bounded.remove(bounded.size() - 1);
            removed = true;
            payload = serialize(bounded);
        }
        if (removed) {
            bounded.add(new Entry("system", "[Later reflection transcript entries omitted to fit the local size limit.]", "system"));
            payload = serialize(bounded);
            if (payload.length() > MAX_PAYLOAD_CHARS) bounded.remove(bounded.size() - 1);
        }
        return serialize(bounded);
    }

    public static String lastMessageId(String serializedPayload) {
        try {
            JSONArray entries = new JSONArray(serializedPayload);
            for (int index = entries.length() - 1; index >= 0; index--) {
                JSONObject entry = entries.optJSONObject(index);
                if (entry == null) continue;
                String kind = entry.optString("kind", "");
                String id = entry.optString("source_message_id", "");
                if (("user".equals(kind) || "assistant".equals(kind)) && !id.isEmpty()) return id;
            }
        } catch (Exception ignored) { }
        return "";
    }

    static String sanitize(String value) {
        if (MemoryStore.containsLikelySecret(value)) return "[message omitted: possible secret or credential]";
        String safe = COORDINATES.matcher(value).replaceAll("[exact coordinates omitted]");
        safe = STREET_ADDRESS.matcher(safe).replaceAll("[exact address omitted]");
        return safe;
    }

    public static boolean containsPreciseLocation(String value) {
        return value != null && (COORDINATES.matcher(value).find() || STREET_ADDRESS.matcher(value).find());
    }

    private static String serialize(List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            JSONObject object = new JSONObject();
            try {
                object.put("kind", entry.role);
                object.put("text", entry.content);
                if (!entry.source.isEmpty()) object.put("source", entry.source);
                if (!entry.sourceMessageId.isEmpty()) object.put("source_message_id", entry.sourceMessageId);
                if (!entry.capturedAt.isEmpty()) object.put("captured_at", entry.capturedAt);
                if ("tool_call".equals(entry.role)) {
                    object.put("name", entry.toolName);
                    object.put("call_id", entry.callId);
                    object.put("resultText", entry.content);
                    object.put("resultOk", entry.resultOk);
                }
            } catch (Exception error) { throw new IllegalStateException("Could not serialize reflection transcript", error); }
            array.put(object);
        }
        return array.toString();
    }
}
