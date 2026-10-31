package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ExternalLaunchSpec;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExternalLaunchSpecTest {
    @Test public void coordinatesAreBoundedFiniteLocaleIndependentPlainDecimals() {
        Locale previous=Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            ExternalLaunchSpec spec=ExternalLaunchSpec.coordinates(12.5,-180);
            assertEquals(ExternalLaunchSpec.Kind.MAPS_COORDINATES,spec.kind);
            assertEquals("maps",spec.capability);assertEquals("geo:12.5,-180",spec.uri);assertEquals("12.5,-180",spec.display);
            assertEquals("geo:0,0",ExternalLaunchSpec.coordinates(-0.0,+0.0).uri);
            assertEquals("geo:-90,180",ExternalLaunchSpec.coordinates(-90,180).uri);
            assertEquals("geo:0.0000001,-0.0000001",ExternalLaunchSpec.coordinates(1e-7,-1e-7).uri);
            assertFalse(ExternalLaunchSpec.coordinates(Double.MIN_VALUE,0).uri.contains("E"));
            for(double latitude:new double[]{-90.0000001,90.0000001,Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY})
                assertThrows(IllegalArgumentException.class,()->ExternalLaunchSpec.coordinates(latitude,0));
            for(double longitude:new double[]{-180.0000001,180.0000001,Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY})
                assertThrows(IllegalArgumentException.class,()->ExternalLaunchSpec.coordinates(0,longitude));
        } finally {Locale.setDefault(previous);}
    }
    @Test public void queryPreservesUnicodeAndEncodesReservedBytesExactlyOnce() {
        String query=" Café %2F & + # 東京 😀 ";
        ExternalLaunchSpec spec=ExternalLaunchSpec.query(query);
        assertEquals(ExternalLaunchSpec.Kind.MAPS_QUERY,spec.kind);assertEquals("maps",spec.capability);assertEquals(query,spec.display);
        assertEquals("geo:0,0?q=%20Caf%C3%A9%20%252F%20%26%20%2B%20%23%20%E6%9D%B1%E4%BA%AC%20%F0%9F%98%80%20",spec.uri);
        assertEquals("geo:0,0?q=AZaz09-._~",ExternalLaunchSpec.query("AZaz09-._~").uri);
        // URI-like text is an escaped query, never executable routing data.
        assertEquals("geo:0,0?q=intent%3A%2F%2Fhost%23Intent%3Bend",ExternalLaunchSpec.query("intent://host#Intent;end").uri);
        assertEquals("e\u0301",ExternalLaunchSpec.query("e\u0301").display);
    }
    @Test public void queryRejectsBlankControlsFormattingSeparatorsAndMalformedUnicode() {
        for(String query:new String[]{null,""," ","\u00a0\u3000","a\0b","a\nb","a\tb","a\u007fb","a\u0085b","a\u200bb","a\u200db","a\u202eb","a\u2066b","a\u2028b","a\u2029b","a\ud800","a\udc00","\ud800a","\ud800\ud800"})
            assertThrows("query accepted",IllegalArgumentException.class,()->ExternalLaunchSpec.query(query));
        for(int cp:new int[]{0,0x1f,0x7f,0x9f,0x200e,0xfeff,0xe0001}) {
            String query="a"+new String(Character.toChars(cp));
            assertThrows(IllegalArgumentException.class,()->ExternalLaunchSpec.query(query));
        }
    }
    @Test public void queryBoundsCountCodePointsAndUtf8Bytes() {
        String emoji=new String(Character.toChars(0x1f600));
        String max=new String(new char[256]).replace("\0",emoji);
        assertEquals(1024,max.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(max,ExternalLaunchSpec.query(max).display);
        assertThrows(IllegalArgumentException.class,()->ExternalLaunchSpec.query(max+"a"));
        assertEquals(256,ExternalLaunchSpec.query(new String(new char[256]).replace('\0','a')).display.length());
        assertThrows(IllegalArgumentException.class,()->ExternalLaunchSpec.query(new String(new char[257]).replace('\0','a')));
    }
    @Test public void phoneIsOnlyOptionalPlusAndOneToFifteenAsciiDigits() {
        for(String number:new String[]{"0","00123","+123","123456789012345","+123456789012345"}) {
            ExternalLaunchSpec spec=ExternalLaunchSpec.dial(number);
            assertEquals(ExternalLaunchSpec.Kind.PHONE_DIAL,spec.kind);assertEquals("phone",spec.capability);
            assertEquals(number,spec.display);assertEquals("tel:"+number,spec.uri);
        }
        for(String number:new String[]{null,"","+","++1","1234567890123456","+1234567890123456"," 123","123 ","1 2","1-2","(12)","*123#","#21#","tel:123","1,2","1;2","1p2","1w2","%2B123","１２３","١٢٣","123\n","123\r","123\0","+１２"})
            assertThrows("number accepted",IllegalArgumentException.class,()->ExternalLaunchSpec.dial(number));
    }
}
