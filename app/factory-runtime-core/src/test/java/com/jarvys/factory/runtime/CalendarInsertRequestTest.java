package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CalendarInsertRequestTest {
    static JSONObject args() throws Exception {
        return new JSONObject().put("title", "Synthetic event").put("location", "").put("description", "Fixture\n\tbody")
                .put("startTimeMillis", 0).put("endTimeMillis", 86400000).put("timeZone", "UTC").put("allDay", true);
    }
    private void reject(String raw) { assertThrows(FactoryException.class, () -> ExternalLaunchRequest.parse("calendar.insert", raw)); }
    @Test public void allSevenFieldsAreRequiredWithExactTypes() throws Exception {
        assertEquals("Synthetic event", ExternalLaunchRequest.parse("calendar.insert", args().toString()).calendar.title);
        for (String field : new String[]{"title", "location", "description", "startTimeMillis", "endTimeMillis", "timeZone", "allDay"}) {
            JSONObject missing = args(); missing.remove(field); reject(missing.toString());
            for (Object bad : new Object[]{JSONObject.NULL, new JSONArray(), new JSONObject()}) reject(args().put(field, bad).toString());
        }
        for (String field : new String[]{"title", "location", "description", "timeZone"})
            for (Object bad : new Object[]{1, true, 1.5}) reject(args().put(field, bad).toString());
        for (Object bad : new Object[]{"true", 1, "false", 0}) reject(args().put("allDay", bad).toString());
        for (String field : new String[]{"startTimeMillis", "endTimeMillis"})
            for (Object bad : new Object[]{"0", true, false, 1.5}) reject(args().put(field, bad).toString());
    }
    @Test public void unknownFieldsNeverGrantIntentCalendarDatabaseOrRecipientAuthority() throws Exception {
        for (String field : new String[]{"uri", "url", "component", "package", "extras", "flags", "action", "intent", "mimeType", "selector", "callback", "calendarId", "eventId", "account", "attendees", "attendee", "organizer", "recurrence", "rrule", "rdate", "duration", "reminders", "availability", "accessLevel", "save", "send", "read", "write", "network", "permissions", "start", "end", "timezone"})
            reject(args().put(field, "forbidden").toString());
    }
    @Test public void integerWireFormsRejectFractionExponentOverflowAndCoercion() throws Exception {
        String valid = args().put("allDay", false).toString();
        for (String field : new String[]{"startTimeMillis", "endTimeMillis"}) {
            String number = field.equals("startTimeMillis") ? "0" : "86400000";
            for (String value : new String[]{number + ".0", number + "e0", number + "E+0", "1.5", "9223372036854775808", "-9223372036854775809", "\"" + number + "\"", "true", "null"})
                reject(valid.replace("\"" + field + "\":" + number, "\"" + field + "\":" + value));
        }
        assertEquals(4102444800000L, ExternalLaunchRequest.parse("calendar.insert", args().put("startTimeMillis", 4102358400000L).put("endTimeMillis", 4102444800000L).toString()).calendar.endTimeMillis);
    }
    @Test public void strictJsonRejectsDuplicateEscapedKeysAndNonJsonForms() throws Exception {
        String valid = args().toString();
        for (String bad : new String[]{null, "", "[]", "null", "{} trailing", "{'title':'x'}", valid + " trailing", valid.substring(0, valid.length() - 1) + ",}", valid.substring(0, valid.length() - 1) + ",\"title\":\"other\"}", valid.substring(0, valid.length() - 1) + ",\"ti\\u0074le\":\"other\"}"}) reject(bad);
        reject(valid.replace("Synthetic event", "\\ud800")); reject(valid.replace("Synthetic event", "\\udc00"));
        for (String method : new String[]{null, "calendar.save", "calendar.read", "calendar.write", "calendar.update", "calendar.delete", "calendar.insertEvent", "CALENDAR.INSERT", "intent.launch"})
            assertThrows(FactoryException.class, () -> ExternalLaunchRequest.parse(method, valid));
    }
    @Test public void escapedMaximumDescriptionAndArgumentByteCeilingAreBounded() throws Exception {
        String raw = args().put("description", "PLACEHOLDER").toString().replace("PLACEHOLDER", CalendarInsertSpecTest.repeat("\\u000a", 4096));
        assertTrue(raw.length() > 8192); assertTrue(raw.length() < 32768);
        assertEquals(CalendarInsertSpecTest.repeat("\n", 4096), ExternalLaunchRequest.parse("calendar.insert", raw).calendar.description);
        String valid = args().toString();
        assertNotNull(ExternalLaunchRequest.parse("calendar.insert", valid + CalendarInsertSpecTest.repeat(" ", 32768 - valid.length())));
        reject(valid + CalendarInsertSpecTest.repeat(" ", 32769 - valid.length()));
        reject(args().put("description", CalendarInsertSpecTest.repeat("é", 2049)).toString());
    }
    @Test public void bridgeRejectsDecimalAndExponentBeforeAnyReserialization() throws Exception {
        FactoryConfig config = FactoryConfig.parsePreview(FactoryDispatcherTest.configuration("[\"calendar\"]"));
        String valid = args().put("allDay", false).toString();
        for (String value : new String[]{"0.0", "0e0", "0E+0"}) {
            String body = valid.replace("\"startTimeMillis\":0", "\"startTimeMillis\":" + value);
            String envelope = "{\"v\":1,\"id\":\"calendar\",\"method\":\"calendar.insert\",\"args\":" + body + "}";
            FactoryException invalid = assertThrows(FactoryException.class, () -> BridgeProtocol.validate(BridgeProtocol.ORIGIN, true, envelope, config));
            assertEquals("INVALID_ARGUMENT", invalid.code);
        }
    }
}
