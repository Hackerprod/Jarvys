package com.jarvys.factory.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Pure grammar for one-shot notifications and optional own-app action events. No Android effects. */
public final class NotificationScheduleRequest {
    public static final int MAX_ARGS_BYTES = 32768, MAX_ACTIONS = 3, MAX_DATA_BYTES = 4096;
    public static final long MAX_SAFE_INTEGER = 9007199254740991L;
    private NotificationScheduleRequest() { }
    public enum Precision { INEXACT, EXACT }

    public static final class Action {
        public final String id, title;
        private final String data;
        private Action(String id, String title, String data) { this.id = id; this.title = title; this.data = data; }
        public JSONObject data() { return object(data); }
        public JSONObject details() {
            try { return new JSONObject().put("id", id).put("title", title).put("data", data()); }
            catch (JSONException impossible) { throw new AssertionError(impossible); }
        }
    }
    public static final class Notification {
        public final NotificationRequest.Show show;
        public final List<Action> actions;
        private Notification(NotificationRequest.Show show, List<Action> actions) {
            this.show = show; this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
        }
        public JSONObject details() {
            try {
                JSONObject result = show.authorizationDetails(); JSONArray values = new JSONArray();
                for (Action action : actions) values.put(action.details());
                return result.put("actions", values);
            } catch (JSONException impossible) { throw new AssertionError(impossible); }
        }
    }
    public static final class Schedule {
        public final String scheduleId;
        public final long atEpochMs, expiresAtEpochMs;
        public final Precision precision;
        public final boolean allowWhileIdle;
        public final Notification notification;
        private Schedule(String id, long at, long expires, Precision precision, boolean idle, Notification notification) {
            this.scheduleId = id; this.atEpochMs = at; this.expiresAtEpochMs = expires;
            this.precision = precision; this.allowWhileIdle = idle; this.notification = notification;
        }
        public JSONObject authorizationDetails() {
            try {
                return new JSONObject().put("scheduleId", scheduleId).put("atEpochMs", atEpochMs)
                        .put("expiresAtEpochMs", expiresAtEpochMs).put("precision", precision == Precision.EXACT ? "exact" : "inexact")
                        .put("allowWhileIdle", allowWhileIdle).put("notification", notification.details());
            } catch (JSONException impossible) { throw new AssertionError(impossible); }
        }
        /** Request identity only; source artifact proof and live policy remain separate requirements. */
        public String fingerprint() { return sha256(canonical(authorizationDetails(), 0)); }
        public void requireFuture(long nowEpochMs) throws FactoryException {
            if (nowEpochMs < 0 || atEpochMs <= nowEpochMs) throw invalid();
        }
    }

    public static Schedule parseSchedule(String json) throws FactoryException {
        return parseSchedule(StrictJson.object(json, MAX_ARGS_BYTES));
    }
    public static Schedule parseSchedule(JSONObject args) throws FactoryException {
        bounded(args, MAX_ARGS_BYTES);
        FactoryConfig.exactKeys(args, "scheduleId", "atEpochMs", "expiresAtEpochMs", "precision", "allowWhileIdle", "notification");
        try {
            String id = identifier(args.get("scheduleId"));
            long at = integer(args.get("atEpochMs")), expires = integer(args.get("expiresAtEpochMs"));
            if (expires < at) throw invalid();
            String precision = args.has("precision") ? FactoryConfig.string(args, "precision") : "inexact";
            if (!"inexact".equals(precision) && !"exact".equals(precision)) throw invalid();
            Object idle = args.has("allowWhileIdle") ? args.get("allowWhileIdle") : Boolean.FALSE;
            if (!(idle instanceof Boolean) || !(args.get("notification") instanceof JSONObject)) throw invalid();
            return new Schedule(id, at, expires, "exact".equals(precision) ? Precision.EXACT : Precision.INEXACT,
                    (Boolean) idle, parseNotification((JSONObject) args.get("notification")));
        } catch (JSONException failure) { throw invalid(); }
    }
    /** Also suitable for immediate show; scheduling remains a separate operation. */
    public static Notification parseNotification(String json) throws FactoryException {
        return parseNotification(StrictJson.object(json, MAX_ARGS_BYTES));
    }
    public static Notification parseNotification(JSONObject args) throws FactoryException {
        bounded(args, MAX_ARGS_BYTES);
        FactoryConfig.exactKeys(args, "id", "channelId", "title", "body", "priority", "actions");
        try {
            JSONObject show = new JSONObject();
            for (String key : new String[]{"id", "channelId", "title", "body", "priority"})
                if (args.has(key)) show.put(key, args.get(key)); // Preserve exact number types.
            NotificationRequest.Show request = NotificationRequest.parseShow(show);
            List<Action> actions = new ArrayList<>(); Set<String> ids = new HashSet<>();
            if (args.has("actions")) {
                Object raw = args.get("actions"); if (!(raw instanceof JSONArray)) throw invalid();
                JSONArray list = (JSONArray) raw; if (list.length() > MAX_ACTIONS) throw invalid();
                for (int i = 0; i < list.length(); i++) {
                    Object item = list.get(i); if (!(item instanceof JSONObject)) throw invalid();
                    JSONObject action = (JSONObject) item;
                    FactoryConfig.exactKeys(action, "id", "title", "data");
                    String id = identifier(action.get("id")); if (!ids.add(id)) throw invalid();
                    String title = label(FactoryConfig.string(action, "title"), 120);
                    Object payload = action.has("data") ? action.get("data") : new JSONObject();
                    if (!(payload instanceof JSONObject)) throw invalid();
                    JSONObject data = (JSONObject) payload; bounded(data, MAX_DATA_BYTES);
                    String snapshot;
                    try { snapshot = canonical(data, 0); } catch (IllegalArgumentException invalid) { throw invalid(); }
                    actions.add(new Action(id, title, snapshot));
                }
            }
            return new Notification(request, actions);
        } catch (JSONException failure) { throw invalid(); }
    }
    /** Status, cancelScheduled and resume operate on one immutable identifier. No cancel-all. */
    public static String parseScheduleId(String json) throws FactoryException {
        JSONObject args = StrictJson.object(json, 256); FactoryConfig.exactKeys(args, "scheduleId");
        try { return identifier(args.get("scheduleId")); } catch (JSONException failure) { throw invalid(); }
    }
    public static String parseEventAck(String json) throws FactoryException {
        JSONObject args = StrictJson.object(json, 256); FactoryConfig.exactKeys(args, "eventId");
        try {
            Object value = args.get("eventId");
            if (!(value instanceof String) || !((String) value).matches("[a-f0-9]{64}")) throw invalid();
            return (String) value;
        } catch (JSONException failure) { throw invalid(); }
    }
    public static void parsePollActions(String json) throws FactoryException {
        FactoryConfig.exactKeys(StrictJson.object(json, 64));
    }
    private static long integer(Object value) throws FactoryException {
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid();
        long number = ((Number) value).longValue();
        if (number < 1 || number > MAX_SAFE_INTEGER) throw invalid(); return number;
    }
    private static String identifier(Object value) throws FactoryException {
        if (!(value instanceof String) || !((String) value).matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) throw invalid();
        return (String) value;
    }
    private static String label(String value, int max) throws FactoryException {
        if (value.trim().isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > max) throw invalid();
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) throw invalid();
        try { unicode(value); } catch (IllegalArgumentException failure) { throw invalid(); }
        return value;
    }
    private static void bounded(JSONObject args, int bytes) throws FactoryException {
        if (args == null) throw invalid();
        String encoded = args.toString(); if (encoded == null) throw invalid();
        // Guard direct JSONObject entry points against permissive JSON and excessive nesting.
        StrictJson.object(encoded, bytes);
    }
    /** Arrays and null are required for actions; NativeOperationRequest's old canonicalizer lacks them. */
    static String canonical(Object item, int depth) {
        if (depth > 8) throw new IllegalArgumentException("JSON depth");
        try {
            if (item instanceof JSONObject) {
                JSONObject value = (JSONObject) item; List<String> keys = new ArrayList<>();
                for (Iterator<String> it = value.keys(); it.hasNext();) keys.add(it.next()); Collections.sort(keys);
                StringBuilder result = new StringBuilder("{");
                for (String key : keys) {
                    unicode(key); if (result.length() > 1) result.append(',');
                    result.append(JSONObject.quote(key)).append(':').append(canonical(value.get(key), depth + 1));
                }
                return result.append('}').toString();
            }
            if (item instanceof JSONArray) {
                JSONArray values = (JSONArray) item; StringBuilder result = new StringBuilder("[");
                for (int i = 0; i < values.length(); i++) { if (i > 0) result.append(','); result.append(canonical(values.get(i), depth + 1)); }
                return result.append(']').toString();
            }
            if (item == JSONObject.NULL) return "null";
            if (item instanceof String) { unicode((String) item); return JSONObject.quote((String) item); }
            if (item instanceof Boolean) return item.toString();
            if (item instanceof Number) {
                double value = ((Number) item).doubleValue();
                if (Double.isNaN(value) || Double.isInfinite(value)) throw new IllegalArgumentException("JSON number");
                return JSONObject.numberToString((Number) item);
            }
        } catch (JSONException failure) { throw new IllegalArgumentException("JSON value", failure); }
        throw new IllegalArgumentException("JSON type");
    }
    private static void unicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new IllegalArgumentException("Unicode"); }
            else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Unicode");
        }
    }
    private static JSONObject object(String value) {
        try { return new JSONObject(value); } catch (JSONException impossible) { throw new AssertionError(impossible); }
    }
    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : bytes) result.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static FactoryException invalid() { return new FactoryException("INVALID_ARGUMENT", "Invalid scheduled notification or own-app action."); }
}
