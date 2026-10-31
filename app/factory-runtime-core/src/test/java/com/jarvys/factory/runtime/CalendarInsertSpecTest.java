package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ExternalLaunchSpec;
import com.jarvys.factory.contract.ExternalLaunchSpec.CalendarInsertSpec;
import org.junit.Test;
import java.lang.reflect.Modifier;
import static org.junit.Assert.*;

/** Pure synthetic prefills. Does not query a calendar or start an editor. */
public class CalendarInsertSpecTest {
    static String repeat(String value, int count) { return new String(new char[count]).replace("\0", value); }
    private ExternalLaunchSpec timed(String title, String location, String description) {
        return ExternalLaunchSpec.calendar(title, location, description, 0, 1, "UTC", false);
    }
    @Test public void immutableTypedPayloadPreservesAllSevenFieldsWithoutUriOrMessageAuthority() {
        String title = " Café %2F & + # 東京 😀 ", location = " Room &url=content://untrusted ", description = "Line one\n\tLine two";
        ExternalLaunchSpec spec = ExternalLaunchSpec.calendar(title, location, description, 1234, 5678, "America/New_York", false);
        assertEquals(ExternalLaunchSpec.Kind.CALENDAR_INSERT, spec.kind); assertEquals("calendar", spec.capability);
        assertNull(spec.uri); assertNull(spec.recipient); assertNull(spec.subject); assertNull(spec.body); assertEquals(title, spec.display);
        CalendarInsertSpec event = spec.calendar;
        assertNotNull(event); assertEquals(title, event.title); assertEquals(location, event.location); assertEquals(description, event.description);
        assertEquals(1234, event.startTimeMillis); assertEquals(5678, event.endTimeMillis); assertEquals("America/New_York", event.timeZone); assertFalse(event.allDay);
        assertTrue(Modifier.isFinal(CalendarInsertSpec.class.getModifiers()));
        for (java.lang.reflect.Field field : CalendarInsertSpec.class.getFields()) assertTrue(Modifier.isFinal(field.getModifiers()));
        for (java.lang.reflect.Constructor<?> constructor : CalendarInsertSpec.class.getDeclaredConstructors()) if (!constructor.isSynthetic()) assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        assertNull(ExternalLaunchSpec.query("fixture").calendar); assertNull(ExternalLaunchSpec.email("a@example.invalid", "", "").calendar);
    }
    @Test public void titleMustContainUnicodeNonspaceButEmptyLocationDescriptionAreExplicitlyAllowed() {
        for (String blank : new String[]{null, "", " ", "\u00a0", "\u2002\u2003\u3000"})
            assertThrows(IllegalArgumentException.class, () -> timed(blank, "", ""));
        assertEquals(" e\u0301 ", timed(" e\u0301 ", "", "").calendar.title);
        assertEquals("", timed("x", "", "").calendar.location); assertEquals("", timed("x", "", "").calendar.description);
        assertEquals(" ", timed("x", " ", " ").calendar.location);
    }
    @Test public void titleLocationAndDescriptionEnforceCodepointAndByteBoundaries() {
        String emoji = new String(Character.toChars(0x1f600));
        String title = repeat(emoji, 256), location = repeat(emoji, 256), description = repeat(emoji, 1024);
        CalendarInsertSpec max = timed(title, location, description).calendar;
        assertEquals(title, max.title); assertEquals(location, max.location); assertEquals(description, max.description);
        assertEquals(repeat("x", 4096), timed("x", "", repeat("x", 4096)).calendar.description);
        for (String extra : new String[]{title + "x", repeat("x", 257)}) {
            assertThrows(IllegalArgumentException.class, () -> timed(extra, "", ""));
            assertThrows(IllegalArgumentException.class, () -> timed("x", extra, ""));
        }
        for (String extra : new String[]{description + "x", repeat("x", 4097), repeat("é", 2049)})
            assertThrows(IllegalArgumentException.class, () -> timed("x", "", extra));
    }
    @Test public void onlyDescriptionAllowsLfTabAndUnsafeUnicodeAlwaysFails() {
        for (String bad : new String[]{null, "\0", "\r", "\u001f", "\u007f", "\u0085", "\u009f", "\u200b", "\u200d", "\u202e", "\u2066", "\ufeff", "\u2028", "\u2029", "\ud800", "\udc00", "\ud800a", new String(Character.toChars(0xe0001))}) {
            assertThrows(IllegalArgumentException.class, () -> timed(bad, "", ""));
            assertThrows(IllegalArgumentException.class, () -> timed("x", bad, ""));
            assertThrows(IllegalArgumentException.class, () -> timed("x", "", bad));
        }
        for (String control : new String[]{"\n", "\t"}) {
            assertThrows(IllegalArgumentException.class, () -> timed("x" + control, "", ""));
            assertThrows(IllegalArgumentException.class, () -> timed("x", control, ""));
            assertEquals(control, timed("x", "", control).calendar.description);
        }
    }
    @Test public void epochBoundsDurationAndOrderingAreExactAndOverflowSafe() {
        long max = 4102444800000L, duration = 366L * 86400000L;
        assertEquals(0, timed("x", "", "").calendar.startTimeMillis);
        assertEquals(max, ExternalLaunchSpec.calendar("x", "", "", max - 1, max, "UTC", false).calendar.endTimeMillis);
        assertEquals(duration, ExternalLaunchSpec.calendar("x", "", "", 0, duration, "UTC", false).calendar.endTimeMillis);
        for (long[] bounds : new long[][]{{-1, 1}, {0, 0}, {1, 0}, {0, duration + 1}, {max, max + 1}, {max - 1, max + 1}, {Long.MIN_VALUE, Long.MAX_VALUE}, {Long.MAX_VALUE - 1, Long.MAX_VALUE}, {0, Long.MIN_VALUE}})
            assertThrows(IllegalArgumentException.class, () -> ExternalLaunchSpec.calendar("x", "", "", bounds[0], bounds[1], "UTC", false));
    }
    @Test public void timeZoneUsesExactRuntimeIdsWithoutNormalizationOrFallback() {
        for (String zone : java.util.TimeZone.getAvailableIDs())
            assertEquals(zone, ExternalLaunchSpec.calendar("x", "", "", 0, 1, zone, false).calendar.timeZone);
        for (String zone : new String[]{null, "", "utc", " UTC", "UTC ", "America/new_york", "Invalid/Zone", "GMT+05:30", "+05:30", "Z", "UTC\n", "UTC\0"})
            assertThrows(IllegalArgumentException.class, () -> ExternalLaunchSpec.calendar("x", "", "", 0, 1, zone, false));
    }
    @Test public void allDayRequiresUtcMidnightWithPositiveExclusiveEnd() {
        long day = 86400000L;
        CalendarInsertSpec event = ExternalLaunchSpec.calendar("x", "", "", 0, day, "UTC", true).calendar;
        assertTrue(event.allDay); assertEquals(day, event.endTimeMillis);
        assertEquals(366 * day, ExternalLaunchSpec.calendar("x", "", "", 0, 366 * day, "UTC", true).calendar.endTimeMillis);
        assertEquals(4102444800000L, ExternalLaunchSpec.calendar("x", "", "", 4102444800000L - day, 4102444800000L, "UTC", true).calendar.endTimeMillis);
        for (String zone : new String[]{"GMT", "Etc/UTC", "America/New_York"})
            assertThrows(IllegalArgumentException.class, () -> ExternalLaunchSpec.calendar("x", "", "", 0, day, zone, true));
        for (long[] bounds : new long[][]{{0, 0}, {1, day}, {0, day + 1}, {day, day}, {day, 0}, {0, 367 * day}})
            assertThrows(IllegalArgumentException.class, () -> ExternalLaunchSpec.calendar("x", "", "", bounds[0], bounds[1], "UTC", true));
    }
}
