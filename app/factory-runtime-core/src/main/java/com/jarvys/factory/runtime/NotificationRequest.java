package com.jarvys.factory.runtime;

import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** Closed, bounded notification grammar. App input never supplies an Intent, URI or component. */
public final class NotificationRequest {
    public static final int MAX_ARGS_BYTES = 32768;
    public static final int MAX_TITLE_BYTES = 240, MAX_BODY_BYTES = 4096;
    private NotificationRequest() { }

    public enum Priority { LOW, DEFAULT, HIGH }

    public static final class Show {
        public final int id;
        public final String channelId, title, body;
        public final Priority priority;
        private Show(int id, String channelId, String title, String body, Priority priority) {
            this.id = id; this.channelId = channelId; this.title = title;
            this.body = body; this.priority = priority;
        }
        public JSONObject authorizationDetails() {
            try {
                return new JSONObject().put("id", id).put("channelId", channelId)
                        .put("title", title).put("body", body).put("priority", word(priority));
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    public static final class Channel {
        public final String id, name, description;
        public final Priority importance;
        private Channel(String id, String name, String description, Priority importance) {
            this.id = id; this.name = name; this.description = description; this.importance = importance;
        }
        public JSONObject authorizationDetails() {
            try {
                return new JSONObject().put("id", id).put("name", name).put("description", description)
                        .put("importance", word(importance));
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    public static Show parseShow(String json) throws FactoryException {
        return parseShow(StrictJson.object(json, MAX_ARGS_BYTES));
    }

    /** Preserve original JSON number types instead of normalizing 1.0 to 1 while serializing. */
    public static Show parseShow(JSONObject args) throws FactoryException {
        bounded(args);
        try {
            FactoryConfig.exactKeys(args, "id", "channelId", "title", "body", "priority");
            return new Show(id(args), channelId(FactoryConfig.string(args, "channelId")),
                    text(FactoryConfig.string(args, "title"), MAX_TITLE_BYTES, false, false),
                    text(FactoryConfig.string(args, "body"), MAX_BODY_BYTES, true, true),
                    priority(args, "priority"));
        } catch (JSONException invalid) { throw invalid(); }
    }

    public static Channel parseChannel(String json) throws FactoryException {
        return parseChannel(StrictJson.object(json, MAX_ARGS_BYTES));
    }

    public static Channel parseChannel(JSONObject args) throws FactoryException {
        bounded(args);
        try {
            FactoryConfig.exactKeys(args, "id", "name", "description", "importance");
            return new Channel(channelId(FactoryConfig.string(args, "id")),
                    text(FactoryConfig.string(args, "name"), 160, false, false),
                    args.has("description") ? text(FactoryConfig.string(args, "description"), 512, true, true) : "",
                    priority(args, "importance"));
        } catch (JSONException invalid) { throw invalid(); }
    }

    public static int parseCancel(String json) throws FactoryException {
        return parseCancel(StrictJson.object(json, 128));
    }

    public static int parseCancel(JSONObject args) throws FactoryException {
        bounded(args);
        FactoryConfig.exactKeys(args, "id");
        try { return id(args); } catch (JSONException invalid) { throw invalid(); }
    }

    /** Null requests aggregate app status; a supplied identifier additionally checks that channel. */
    public static String parseStatus(String json) throws FactoryException {
        JSONObject args = StrictJson.object(json, 256);
        FactoryConfig.exactKeys(args, "channelId");
        try { return args.has("channelId") ? channelId(FactoryConfig.string(args, "channelId")) : null; }
        catch (JSONException invalid) { throw invalid(); }
    }

    public static void parsePermission(String json) throws FactoryException {
        FactoryConfig.exactKeys(StrictJson.object(json, 64));
    }

    static String channelId(String value) throws FactoryException {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) throw invalid();
        return value;
    }

    static String word(Priority priority) { return priority.name().toLowerCase(java.util.Locale.ROOT); }

    private static void bounded(JSONObject args) throws FactoryException {
        if (args == null) throw invalid();
        String encoded = args.toString();
        if (encoded == null || encoded.length() > MAX_ARGS_BYTES || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_ARGS_BYTES)
            throw new FactoryException("TOO_LARGE", "Notification arguments exceed the size limit.");
    }

    private static int id(JSONObject object) throws JSONException, FactoryException {
        Object value = object.get("id");
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid();
        long id = ((Number) value).longValue();
        if (id < 1 || id > Integer.MAX_VALUE) throw invalid();
        return (int) id;
    }

    private static Priority priority(JSONObject object, String key) throws JSONException, FactoryException {
        if (!object.has(key)) return Priority.DEFAULT;
        switch (FactoryConfig.string(object, key)) {
            case "low": return Priority.LOW;
            case "default": return Priority.DEFAULT;
            case "high": return Priority.HIGH;
            default: throw invalid();
        }
    }

    private static String text(String value, int maxBytes, boolean empty, boolean multiline) throws FactoryException {
        if ((!empty && value.trim().isEmpty()) || value.length() > maxBytes ||
                value.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw invalid();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\t'))) throw invalid();
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(c)) throw invalid();
        }
        return value;
    }

    private static FactoryException invalid() {
        return new FactoryException("INVALID_ARGUMENT", "Invalid typed notification arguments or limit exceeded.");
    }
}
