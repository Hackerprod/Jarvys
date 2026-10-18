package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationRequestTest {
    private static final String SHOW = "{\"id\":7,\"channelId\":\"probe\",\"title\":\"Network changed\",\"body\":\"Host reachable\"}";
    private static final String CHANNEL = "{\"id\":\"probe\",\"name\":\"Network probes\"}";

    @Test public void canonicalRequestsBindEveryFieldAndReturnFreshDetails() throws Exception {
        NotificationRequest.Show show = NotificationRequest.parseShow(SHOW);
        assertEquals(7, show.id); assertEquals("probe", show.channelId);
        assertEquals(NotificationRequest.Priority.DEFAULT, show.priority);
        JSONObject details = show.authorizationDetails();
        assertEquals(5, details.length()); assertEquals("default", details.getString("priority"));
        details.put("title", "mutated"); assertEquals("Network changed", show.authorizationDetails().getString("title"));
        NotificationRequest.Channel channel = NotificationRequest.parseChannel(CHANNEL);
        assertEquals("", channel.description); assertEquals(NotificationRequest.Priority.DEFAULT, channel.importance);
        assertEquals(4, channel.authorizationDetails().length());
        assertEquals("default", channel.authorizationDetails().getString("importance"));
        assertEquals(7, NotificationRequest.parseCancel("{\"id\":7}"));
        assertNull(NotificationRequest.parseStatus("{}")); assertEquals("probe", NotificationRequest.parseStatus("{\"channelId\":\"probe\"}"));
        NotificationRequest.parsePermission("{}");
    }

    @Test public void priorityIsExplicitTypedAndCaseSensitive() throws Exception {
        for (String value : new String[]{"low", "default", "high"}) {
            assertEquals(value, NotificationRequest.parseShow(new JSONObject(SHOW).put("priority", value).toString()).authorizationDetails().getString("priority"));
            assertEquals(value, NotificationRequest.parseChannel(new JSONObject(CHANNEL).put("importance", value).toString()).authorizationDetails().getString("importance"));
        }
        for (Object value : new Object[]{"max", "HIGH", " default", "", 1, true, JSONObject.NULL, new JSONObject(), new JSONArray()}) {
            assertThrows(FactoryException.class, () -> NotificationRequest.parseShow(new JSONObject(SHOW).put("priority", value).toString()));
            assertThrows(FactoryException.class, () -> NotificationRequest.parseChannel(new JSONObject(CHANNEL).put("importance", value).toString()));
        }
    }

    @Test public void notificationIdsArePositiveExactIntegers() throws Exception {
        assertEquals(Integer.MAX_VALUE, NotificationRequest.parseCancel("{\"id\":2147483647}"));
        for (String bad : new String[]{"0", "-1", "2147483648", "1.5", "1.0", "1e0", "true", "null", "\"7\"", "{}", "[]"}) {
            assertThrows(FactoryException.class, () -> NotificationRequest.parseCancel("{\"id\":" + bad + "}"));
            assertThrows(FactoryException.class, () -> NotificationRequest.parseShow(SHOW.replace("\"id\":7", "\"id\":" + bad)));
        }
        JSONObject numeric = new JSONObject(SHOW).put("id", 1.0d);
        assertThrows(FactoryException.class, () -> NotificationRequest.parseShow(numeric));
        assertThrows(FactoryException.class, () -> NotificationRequest.parseCancel(new JSONObject().put("id", 1.0d)));
        assertThrows(FactoryException.class, () -> NotificationRequest.parseShow((JSONObject) null));
    }
    @Test public void typedNotificationErrorsNameTheFieldWithoutEchoingItsValue() throws Exception {
        try {
            NotificationRequest.parseShow(new JSONObject(SHOW).put("title", "synthetic-private-title".repeat(20)));
            fail("Expected INVALID_ARGUMENT");
        } catch (FactoryException failure) {
            assertEquals("INVALID_ARGUMENT", failure.code);
            assertTrue(failure.getMessage().contains("notifications.show.title"));
            assertFalse(failure.getMessage().contains("synthetic-private-title"));
        }
        try {
            NotificationRequest.parseShow(new JSONObject(SHOW).put("id", "synthetic-private-id"));
            fail("Expected INVALID_ARGUMENT");
        } catch (FactoryException failure) {
            assertEquals("INVALID_ARGUMENT", failure.code);
            assertTrue(failure.getMessage().contains("notifications.show.id"));
            assertFalse(failure.getMessage().contains("synthetic-private-id"));
        }
    }

    @Test public void closedGrammarRejectsIntentAndSchedulingAuthority() throws Exception {
        for (String field : new String[]{"url", "uri", "intent", "component", "package", "extras", "flags", "action", "icon", "sound", "fullScreenIntent", "ongoing", "when", "delayMs", "repeat", "schedule", "all"}) {
            assertThrows(FactoryException.class, () -> NotificationRequest.parseShow(new JSONObject(SHOW).put(field, "x").toString()));
            assertThrows(FactoryException.class, () -> NotificationRequest.parseChannel(new JSONObject(CHANNEL).put(field, "x").toString()));
        }
        assertThrows(FactoryException.class, () -> NotificationRequest.parseCancel("{\"id\":7,\"all\":true}"));
        assertThrows(FactoryException.class, () -> NotificationRequest.parseStatus("{\"all\":true}"));
        assertThrows(FactoryException.class, () -> NotificationRequest.parsePermission("{\"granted\":true}"));
        for (String missing : new String[]{"id", "channelId", "title", "body"}) {
            JSONObject incomplete = new JSONObject(SHOW); incomplete.remove(missing);
            assertThrows(FactoryException.class, () -> NotificationRequest.parseShow(incomplete.toString()));
        }
    }

    @Test public void boundedTextPreservesUnicodeAndBodyNewlinesButRejectsMalformedSurrogates() throws Exception {
        String body = "Reachable\nRound trip\t12 ms 😀";
        assertEquals(body, NotificationRequest.parseShow(new JSONObject(SHOW).put("body", body).toString()).body);
        assertEquals("", NotificationRequest.parseShow(new JSONObject(SHOW).put("body", "").toString()).body);
        for (String bad : new String[]{"", " ", "\n", "bad\u0000title", "bad\ntitle", "\ud800", "\udc00", repeat("é", 121)})
            assertThrows(FactoryException.class, () -> NotificationRequest.parseShow(new JSONObject(SHOW).put("title", bad).toString()));
        for (String bad : new String[]{"bad\u0000body", "\ud800", "\udc00", repeat("é", 2049)})
            assertThrows(FactoryException.class, () -> NotificationRequest.parseShow(new JSONObject(SHOW).put("body", bad).toString()));
        assertEquals(240, NotificationRequest.parseShow(new JSONObject(SHOW).put("title", repeat("a", 240)).toString()).title.length());
        assertEquals(4096, NotificationRequest.parseShow(new JSONObject(SHOW).put("body", repeat("a", 4096)).toString()).body.length());
    }

    @Test public void channelNamesAndIdsAreBoundedWithoutPathsOrNamespaceEscape() throws Exception {
        for (String bad : new String[]{"", "a/b", "../x", ".hidden", " x", "x:y", "x\n", repeat("a", 65)}) {
            assertThrows(FactoryException.class, () -> NotificationRequest.parseChannel(new JSONObject(CHANNEL).put("id", bad).toString()));
            assertThrows(FactoryException.class, () -> NotificationRequest.parseShow(new JSONObject(SHOW).put("channelId", bad).toString()));
            assertThrows(FactoryException.class, () -> NotificationRequest.parseStatus(new JSONObject().put("channelId", bad).toString()));
        }
        assertThrows(FactoryException.class, () -> NotificationRequest.parseChannel(new JSONObject(CHANNEL).put("name", repeat("a", 161)).toString()));
        assertThrows(FactoryException.class, () -> NotificationRequest.parseChannel(new JSONObject(CHANNEL).put("description", repeat("a", 513)).toString()));
    }

    @Test public void strictJsonAndByteBudgetApplyBeforeParsing() throws Exception {
        for (String bad : new String[]{null, "", "[]", "null", "{'id':7}", "{id:7}", "{\"id\":7,}", "{\"id\":7} trailing", "{\"id\":7,\"id\":8}", "{\"id\":7,\"i\\u0064\":8}"})
            assertThrows(FactoryException.class, () -> NotificationRequest.parseCancel(bad));
        assertThrows(FactoryException.class, () -> NotificationRequest.parseShow(SHOW + repeat(" ", NotificationRequest.MAX_ARGS_BYTES)));
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(); for (int i = 0; i < count; i++) result.append(value); return result.toString();
    }
}
