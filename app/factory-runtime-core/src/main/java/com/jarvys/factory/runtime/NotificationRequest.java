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
        return parseShow(args, "notifications.show");
    }

    static Show parseShow(JSONObject args, String method) throws FactoryException {
        bounded(args, method);
        try {
            FactoryConfig.exactKeys(args, "id", "channelId", "title", "body", "priority");
            return new Show(id(args, method + ".id"), channelId(string(args, "channelId", method + ".channelId"), method + ".channelId"),
                    text(string(args, "title", method + ".title"), method + ".title", MAX_TITLE_BYTES, false, false),
                    text(string(args, "body", method + ".body"), method + ".body", MAX_BODY_BYTES, true, true),
                    priority(args, "priority", method));
        } catch (JSONException invalid) { throw invalid(method + ".args requires a positive integer id and correctly typed fields."); }
    }

    public static Channel parseChannel(String json) throws FactoryException {
        return parseChannel(StrictJson.object(json, MAX_ARGS_BYTES));
    }

    public static Channel parseChannel(JSONObject args) throws FactoryException {
        bounded(args, "notifications.channel");
        try {
            FactoryConfig.exactKeys(args, "id", "name", "description", "importance");
            return new Channel(channelId(string(args, "id", "notifications.channel.id"), "notifications.channel.id"),
                    text(string(args, "name", "notifications.channel.name"), "notifications.channel.name", 160, false, false),
                    args.has("description") ? text(string(args, "description", "notifications.channel.description"), "notifications.channel.description", 512, true, true) : "",
                    priority(args, "importance", "notifications.channel"));
        } catch (JSONException invalid) { throw invalid("notifications.channel.args contains an invalid JSON shape."); }
    }

    public static int parseCancel(String json) throws FactoryException {
        return parseCancel(StrictJson.object(json, 128));
    }

    public static int parseCancel(JSONObject args) throws FactoryException {
        bounded(args, "notifications.cancel");
        FactoryConfig.exactKeys(args, "id");
        try { return id(args, "notifications.cancel.id"); } catch (JSONException invalid) { throw invalid("notifications.cancel.id must be a positive integer."); }
    }

    /** Null requests aggregate app status; a supplied identifier additionally checks that channel. */
    public static String parseStatus(String json) throws FactoryException {
        JSONObject args = StrictJson.object(json, 256);
        FactoryConfig.exactKeys(args, "channelId");
        try { return args.has("channelId") ? channelId(string(args, "channelId", "notifications.status.channelId"), "notifications.status.channelId") : null; }
        catch (JSONException invalid) { throw invalid("notifications.status.channelId must be a string."); }
    }

    public static void parsePermission(String json) throws FactoryException {
        FactoryConfig.exactKeys(StrictJson.object(json, 64));
    }

    static String channelId(String value) throws FactoryException {
        return channelId(value, "notifications.channelId");
    }
    private static String channelId(String value, String field) throws FactoryException {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) throw invalid(field + " must be 1 to 64 letters, digits, underscore, period, or hyphen.");
        return value;
    }

    static String word(Priority priority) { return priority.name().toLowerCase(java.util.Locale.ROOT); }

    private static void bounded(JSONObject args, String method) throws FactoryException {
        if (args == null) throw invalid(method + ".args must be an object.");
        String encoded = args.toString();
        if (encoded == null || encoded.length() > MAX_ARGS_BYTES || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_ARGS_BYTES)
            throw new FactoryException("TOO_LARGE", method + ".args exceeds the notification byte limit.");
    }

    private static int id(JSONObject object, String field) throws JSONException, FactoryException {
        Object value;
        try { value = object.get("id"); } catch (JSONException missing) { throw invalid(field + " must be a positive integer."); }
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid(field + " must be a positive integer.");
        long id = ((Number) value).longValue();
        if (id < 1 || id > Integer.MAX_VALUE) throw invalid(field + " must be from 1 through " + Integer.MAX_VALUE + ".");
        return (int) id;
    }

    private static Priority priority(JSONObject object, String key, String method) throws JSONException, FactoryException {
        if (!object.has(key)) return Priority.DEFAULT;
        switch (string(object, key, method + "." + key)) {
            case "low": return Priority.LOW;
            case "default": return Priority.DEFAULT;
            case "high": return Priority.HIGH;
            default: throw invalid(method + "." + key + " must be low, default, or high.");
        }
    }
    private static String string(JSONObject object, String key, String field) throws JSONException, FactoryException {
        Object value;
        try { value = object.get(key); } catch (JSONException missing) { throw invalid(field + " must be a string."); }
        if (!(value instanceof String)) throw invalid(field + " must be a string.");
        return (String) value;
    }

    private static String text(String value, String field, int maxBytes, boolean empty, boolean multiline) throws FactoryException {
        if ((!empty && value.trim().isEmpty()) || value.length() > maxBytes ||
                value.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw invalid(field + " must contain " + (empty ? "at most " : "non-empty text within ") + maxBytes + " UTF-8 bytes.");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\t'))) throw invalid(field + " contains an unsupported control character.");
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid(field + " must contain well-formed Unicode text.");
            } else if (Character.isLowSurrogate(c)) throw invalid(field + " must contain well-formed Unicode text.");
        }
        return value;
    }

    private static FactoryException invalid(String message) { return new FactoryException("INVALID_ARGUMENT", message); }
}
