package com.jarvys.factory.runtime;

import java.security.SecureRandom;
import org.json.JSONException;
import org.json.JSONObject;

/** Typed own-app navigation events. Tokens are opaque lookup keys, never approvals or scripts. */
public final class NotificationActionEvents {
    private static final SecureRandom RANDOM = new SecureRandom();
    private NotificationActionEvents() { }
    public static final class Event {
        public final String eventId, scheduleId, actionId;
        public final int notificationId;
        public final long receivedAtEpochMs;
        private final String payload;
        private Event(String eventId, String scheduleId, int notificationId,
                      NotificationScheduleRequest.Action action, long receivedAtEpochMs) {
            if (!validToken(eventId) || notificationId < 1 || receivedAtEpochMs < 1 || action == null ||
                    (scheduleId != null && !scheduleId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) throw new IllegalArgumentException();
            this.eventId = eventId; this.scheduleId = scheduleId; this.notificationId = notificationId;
            this.actionId = action.id; this.receivedAtEpochMs = receivedAtEpochMs;
            this.payload = action.data().toString();
        }
        public JSONObject details() {
            try {
                return new JSONObject().put("type", "notification_action").put("eventId", eventId)
                        .put("scheduleId", scheduleId == null ? JSONObject.NULL : scheduleId)
                        .put("notificationId", notificationId).put("actionId", actionId)
                        .put("receivedAtEpochMs", receivedAtEpochMs).put("data", new JSONObject(payload));
            } catch (JSONException impossible) { throw new AssertionError(impossible); }
        }
    }
    /** Implement with one durable transaction, not with separate token-delete and event-insert writes. */
    public interface Store {
        /**
         * Resolve token only under the current verified artifact, then atomically insert the event
         * and mark token consumed. Duplicate taps must resolve the same event, including after ack,
         * without re-enqueueing it. Unknown, expired, cancelled, or wrong-artifact tokens return null.
         * The incoming intent's arbitrary extras MUST NOT become event content.
         */
        Event acceptTap(String artifactFingerprint, String opaqueToken, long nowEpochMs);
        /** Read the oldest unacknowledged owned event; repeated polling returns the same eventId. */
        Event poll(String artifactFingerprint);
        /** Mark only this artifact's exact event acknowledged. Repeating ack is harmless. */
        boolean acknowledge(String artifactFingerprint, String eventId);
    }
    /** Only a Store resolving a previously persisted immutable action may construct a tap event. */
    public static Event fromPersistedAction(String eventId, String scheduleId, int notificationId,
                                            NotificationScheduleRequest.Action action, long receivedAtEpochMs) {
        return new Event(eventId, scheduleId, notificationId, action, receivedAtEpochMs);
    }
    public static String randomToken() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) result.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return result.toString();
    }
    public static boolean validToken(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    public static Event acceptTap(Store store, String artifact, String token, long nowEpochMs) {
        require(store, artifact); if (!validToken(token) || nowEpochMs < 1) return null;
        return store.acceptTap(artifact, token, nowEpochMs);
    }
    public static Event poll(Store store, String artifact) { require(store, artifact); return store.poll(artifact); }
    public static boolean acknowledge(Store store, String artifact, String eventId) {
        require(store, artifact); if (!validToken(eventId)) throw new IllegalArgumentException("Invalid event ID");
        return store.acknowledge(artifact, eventId);
    }
    private static void require(Store store, String artifact) {
        if (store == null || !validToken(artifact)) throw new IllegalArgumentException("Verified artifact identity required");
    }
}
