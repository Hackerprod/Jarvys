package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ContactPickSpec;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContactPickSpecTest {
    private static String repeat(String value, int count) { StringBuilder b = new StringBuilder(); for (int i=0;i<count;i++) b.append(value); return b.toString(); }
    @Test public void onlyExactPhoneAndEmailKindsAreValidWithoutDefaults() {
        assertEquals("phone", ContactPickSpec.kind("phone")); assertEquals("email", ContactPickSpec.kind("email"));
        for (String kind : new String[]{null,"","Phone","EMAIL"," phone","email ","all","contact","tel","sms","phone,email"})
            assertThrows(IllegalArgumentException.class, () -> ContactPickSpec.kind(kind));
    }
    @Test public void valueIsPreservedWithoutTrimmingNormalizationOrActionSyntaxValidation() {
        for (String value : new String[]{" +1 (555) 0100 ext. 42 ","not-an-email", "*123#", "élève@例え.invalid", "e\u0301", "  Café %2F & + # 東京 😀  "})
            assertSame(value, ContactPickSpec.value(value));
        assertEquals(256, ContactPickSpec.MAX_VALUE_CODE_POINTS); assertEquals(1024, ContactPickSpec.MAX_VALUE_BYTES);
    }
    @Test public void exactly256ScalarValuesAnd1024Utf8BytesAreAccepted() {
        for (String unit : new String[]{"a","é","界","😀"}) {
            String limit = repeat(unit,256); assertEquals(limit,ContactPickSpec.value(limit));
            assertThrows(IllegalArgumentException.class,()->ContactPickSpec.value(limit+unit));
        }
        assertEquals(1024,ContactPickSpec.value(repeat("😀",256)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
    @Test public void nullBlankMalformedUnicodeControlsFormatsAndSeparatorsAreRejected() {
        for (String value : new String[]{null,""," ","\u00a0\u2007\u202f", "\ud800", "\udc00", "a\ud800x", "😀\udc00", "\u0000", "a\n", "a\t", "a\r", "a\u007f", "a\u0085", "a\u009f", "a\u00ad", "a\u200b", "a\u200c", "a\u200d", "a\u200e", "a\u202e", "a\u2066", "a\u2069", "a\ufeff", "a\u2028", "a\u2029", "a\udb40\udc01"})
            assertThrows("Rejected value",IllegalArgumentException.class,()->ContactPickSpec.value(value));
        for (int point=0;point<=0x9f;point++) if (Character.isISOControl(point)) {
            final String value="a"+new String(Character.toChars(point));
            assertThrows(IllegalArgumentException.class,()->ContactPickSpec.value(value));
        }
    }
}
