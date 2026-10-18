package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationScheduleRequestTest {
    static final String SHOW = "{\"id\":7,\"channelId\":\"reminders\",\"title\":\"Review\",\"body\":\"Open the app\"}";
    static final String SCHEDULE = "{\"scheduleId\":\"daily-review\",\"atEpochMs\":2000,\"expiresAtEpochMs\":5000,\"notification\":" + SHOW + "}";
    static final String ACTION = "{\"id\":\"view\",\"title\":\"Open review\",\"data\":{\"key\":\"draft-1\",\"filters\":[1,true,null,{\"status\":\"open\"}]}}";
    static JSONObject withAction() throws Exception { return new JSONObject(SHOW).put("actions", new JSONArray().put(new JSONObject(ACTION))); }
    static NotificationScheduleRequest.Schedule request() throws Exception { return NotificationScheduleRequest.parseSchedule(SCHEDULE); }

    @Test public void defaultsAreInexactNonIdleAndActionsAreOptional() throws Exception {
        NotificationScheduleRequest.Schedule value = request();
        assertEquals(NotificationScheduleRequest.Precision.INEXACT, value.precision); assertFalse(value.allowWhileIdle);
        assertEquals(0, value.notification.actions.size()); assertEquals(7, value.notification.show.id);
        assertEquals(2000, value.atEpochMs); assertEquals(5000, value.expiresAtEpochMs);
        assertEquals(6, value.authorizationDetails().length());
    }
    @Test public void explicitPrecisionAndIdleArePreserved() throws Exception {
        NotificationScheduleRequest.Schedule value = NotificationScheduleRequest.parseSchedule(new JSONObject(SCHEDULE).put("precision", "exact").put("allowWhileIdle", true));
        assertEquals(NotificationScheduleRequest.Precision.EXACT, value.precision); assertTrue(value.allowWhileIdle);
    }
    @Test public void futureCheckIsSeparateFromPersistedRequestParsing() throws Exception {
        request().requireFuture(1999);
        assertThrows(FactoryException.class, () -> request().requireFuture(2000));
        assertThrows(FactoryException.class, () -> request().requireFuture(-1));
        assertNotNull(request());
    }
    @Test public void epochValuesRequireExactSafeIntegers() throws Exception {
        for (String value : new String[]{"0", "-1", "1.0", "1e3", "9007199254740992", "true", "null", "\"2000\""})
            assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseSchedule(SCHEDULE.replace("\"atEpochMs\":2000", "\"atEpochMs\":" + value)));
        assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseSchedule(new JSONObject(SCHEDULE).put("atEpochMs", 2000.0)));
        assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseSchedule(new JSONObject(SCHEDULE).put("expiresAtEpochMs", 1999)));
        assertEquals(NotificationScheduleRequest.MAX_SAFE_INTEGER,
                NotificationScheduleRequest.parseSchedule(new JSONObject(SCHEDULE).put("expiresAtEpochMs", NotificationScheduleRequest.MAX_SAFE_INTEGER)).expiresAtEpochMs);
        FactoryException field = assertThrows(FactoryException.class,
                () -> NotificationScheduleRequest.parseSchedule(new JSONObject(SCHEDULE).put("expiresAtEpochMs", 1999)));
        assertEquals("notifications.schedule: expiresAtEpochMs must be greater than or equal to atEpochMs.", field.getMessage());
    }

    @Test public void typedFailuresNameNotificationMethodAndFieldWithoutEchoingValues() throws Exception {
        FactoryException schedule = assertThrows(FactoryException.class,
                () -> NotificationScheduleRequest.parseSchedule(new JSONObject(SCHEDULE).put("atEpochMs", "private-value")));
        assertEquals("notifications.schedule: atEpochMs must be a positive safe integer.", schedule.getMessage());

        FactoryException scheduleId = assertThrows(FactoryException.class,
                () -> NotificationScheduleRequest.parseScheduleId("{\"scheduleId\":\"private/value\"}", "notifications.cancelScheduled"));
        assertEquals("notifications.cancelScheduled: scheduleId must be 1 to 64 letters, digits, dots, underscores, or hyphens, starting with a letter or digit.", scheduleId.getMessage());
        assertFalse(scheduleId.getMessage().contains("private/value"));

        FactoryException actions = assertThrows(FactoryException.class,
                () -> NotificationScheduleRequest.parseNotification(new JSONObject(SHOW).put("actions", JSONObject.NULL)));
        assertEquals("notifications.show: actions must be an array of at most 3 objects.", actions.getMessage());

        FactoryException nestedTitle = assertThrows(FactoryException.class,
                () -> NotificationScheduleRequest.parseSchedule(new JSONObject(SCHEDULE).put("notification", new JSONObject(SHOW).put("title", 42))));
        assertEquals("notifications.schedule.notification.title must be a string.", nestedTitle.getMessage());
    }
    @Test public void unknownScheduleFieldsCannotRequestBackgroundCodeOrExternalActions() throws Exception {
        for (String key : new String[]{"script", "callback", "url", "intent", "component", "package", "repeat", "intervalMs", "exactFallback", "granted", "flags"})
            assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseSchedule(new JSONObject(SCHEDULE).put(key, "x")));
        for (String key : new String[]{"scheduleId", "atEpochMs", "expiresAtEpochMs", "notification"}) {
            JSONObject value = new JSONObject(SCHEDULE); value.remove(key);
            assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseSchedule(value));
        }
    }
    @Test public void malformedModesAreRejectedWithoutCoercion() throws Exception {
        for (Object value : new Object[]{"EXACT", " exact", "auto", 1, JSONObject.NULL, true})
            assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseSchedule(new JSONObject(SCHEDULE).put("precision", value)));
        for (Object value : new Object[]{"true", 1, JSONObject.NULL})
            assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseSchedule(new JSONObject(SCHEDULE).put("allowWhileIdle", value)));
    }
    @Test public void optionalActionsDoNotScheduleAnImmediateNotification() throws Exception {
        NotificationScheduleRequest.Notification value = NotificationScheduleRequest.parseNotification(withAction());
        assertEquals("view", value.actions.get(0).id); assertEquals("draft-1", value.actions.get(0).data().getString("key"));
        assertFalse(value.details().has("atEpochMs"));
        assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseNotification(withAction().put("atEpochMs", 2000)));
    }
    @Test public void actionPayloadAndDetailsAreImmutableSnapshots() throws Exception {
        JSONObject input = withAction(); NotificationScheduleRequest.Notification value = NotificationScheduleRequest.parseNotification(input);
        input.getJSONArray("actions").getJSONObject(0).getJSONObject("data").put("key", "mutated");
        value.actions.get(0).data().put("key", "changed-again");
        value.details().getJSONArray("actions").getJSONObject(0).getJSONObject("data").put("key", "changed-again");
        assertEquals("draft-1", value.actions.get(0).data().getString("key"));
        assertThrows(UnsupportedOperationException.class, () -> value.actions.clear());
    }
    @Test public void fingerprintCanonicalizesNestedKeyOrderButBindsAllSemantics() throws Exception {
        JSONObject a = new JSONObject(SCHEDULE).put("notification", withAction());
        JSONObject b = new JSONObject(a.toString());
        b.getJSONObject("notification").getJSONArray("actions").getJSONObject(0).put("data", new JSONObject("{\"filters\":[1,true,null,{\"status\":\"open\"}],\"key\":\"draft-1\"}"));
        String first = NotificationScheduleRequest.parseSchedule(a).fingerprint();
        assertEquals(first, NotificationScheduleRequest.parseSchedule(b).fingerprint());
        assertNotEquals(first, NotificationScheduleRequest.parseSchedule(b.put("allowWhileIdle", true)).fingerprint());
        assertEquals(64, first.length());
        assertNotEquals(request().fingerprint(), NotificationScheduleRequest.parseSchedule(new JSONObject(SCHEDULE).put("expiresAtEpochMs", 5001)).fingerprint());
    }
    @Test public void actionIdsAreUniqueAndCountMatchesAndroidButtons() throws Exception {
        JSONArray duplicate = new JSONArray().put(new JSONObject(ACTION)).put(new JSONObject(ACTION));
        assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseNotification(new JSONObject(SHOW).put("actions", duplicate)));
        JSONArray list = new JSONArray();
        for (int i = 0; i < 3; i++) list.put(new JSONObject(ACTION).put("id", "action" + i));
        assertEquals(3, NotificationScheduleRequest.parseNotification(new JSONObject(SHOW).put("actions", list)).actions.size());
        list.put(new JSONObject(ACTION).put("id", "action3"));
        assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseNotification(new JSONObject(SHOW).put("actions", list)));
    }
    @Test public void actionsRejectExecutableIntentFieldsAndUnboundedData() throws Exception {
        for (String key : new String[]{"intent", "uri", "component", "package", "flags", "remoteInput", "script"}) {
            JSONArray actions = new JSONArray().put(new JSONObject(ACTION).put(key, "x"));
            assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseNotification(new JSONObject(SHOW).put("actions", actions)));
        }
        JSONObject action = new JSONObject(ACTION).put("data", new JSONObject().put("long", repeat("a", 4096)));
        assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseNotification(new JSONObject(SHOW).put("actions", new JSONArray().put(action))));
        assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseNotification(new JSONObject(SHOW).put("actions", JSONObject.NULL)));
        assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseNotification(new JSONObject(SHOW).put("actions", new JSONArray().put(1))));
    }
    @Test public void identifiersAndUnicodeAreStrict() throws Exception {
        for (String id : new String[]{"", "../x", "a/b", "a:b", " x", repeat("x", 65)})
            assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseSchedule(new JSONObject(SCHEDULE).put("scheduleId", id)));
        for (String title : new String[]{"", " ", "bad\ntext", "\ud800", repeat("é", 61)}) {
            JSONObject action = new JSONObject(ACTION).put("title", title);
            assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseNotification(new JSONObject(SHOW).put("actions", new JSONArray().put(action))));
        }
    }
    @Test public void strictJsonRejectsDuplicatesAndOversizeBeforeUse() throws Exception {
        assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseSchedule(SCHEDULE.replace("\"atEpochMs\":2000", "\"atEpochMs\":2000,\"atEpochMs\":3000")));
        assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseSchedule(SCHEDULE + " trailing"));
        assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseSchedule(SCHEDULE + repeat(" ", 32768)));
        assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseSchedule((JSONObject) null));
    }
    @Test public void actionPayloadRejectsMalformedUnicodeAndExcessiveNesting() throws Exception {
        JSONObject payload = new JSONObject().put("value", "\ud800");
        JSONObject action = new JSONObject(ACTION).put("data", payload);
        assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseNotification(new JSONObject(SHOW).put("actions", new JSONArray().put(action))));
        JSONObject nested = new JSONObject(); JSONObject root = nested;
        for (int i = 0; i < 12; i++) { JSONObject child = new JSONObject(); nested.put("child", child); nested = child; }
        JSONObject deepAction = new JSONObject(ACTION).put("data", root);
        assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseNotification(new JSONObject(SHOW).put("actions", new JSONArray().put(deepAction))));
        JSONObject primitive = new JSONObject(ACTION).put("data", new JSONArray());
        assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseNotification(new JSONObject(SHOW).put("actions", new JSONArray().put(primitive))));
    }
    @Test public void controlRequestsAreExact() throws Exception {
        assertEquals("review", NotificationScheduleRequest.parseScheduleId("{\"scheduleId\":\"review\"}"));
        String token = repeat("a", 64); assertEquals(token, NotificationScheduleRequest.parseEventAck("{\"eventId\":\"" + token + "\"}"));
        NotificationScheduleRequest.parsePollActions("{}");
        assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseScheduleId("{\"scheduleId\":\"x\",\"all\":true}"));
        assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parseEventAck("{\"eventId\":\"../x\"}"));
        assertThrows(FactoryException.class, () -> NotificationScheduleRequest.parsePollActions("{\"eventId\":\"x\"}"));
    }
    private static String repeat(String value, int n) { StringBuilder out = new StringBuilder(); for (int i = 0; i < n; i++) out.append(value); return out.toString(); }
}
