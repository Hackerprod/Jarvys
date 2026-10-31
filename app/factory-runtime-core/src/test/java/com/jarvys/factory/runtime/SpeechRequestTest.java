package com.jarvys.factory.runtime;

import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechRequestTest {
    @Test public void defaultsHaveExactCanonicalAuthorizationShape() throws Exception {
        SpeechRequest.Speak request = SpeechRequest.parseSpeak("{\"text\":\"Hello\"}");
        JSONObject details = request.authorizationDetails();
        assertEquals(7, details.length()); assertEquals("Hello", details.getString("text"));
        for (String key : new String[]{"language", "engine", "voice"}) assertEquals("", details.getString(key));
        for (String key : new String[]{"rate", "pitch", "volume"}) assertEquals(1.0, details.getDouble(key), 0);
        assertEquals("", SpeechRequest.parseVoices("{}"));
        assertEquals(1, SpeechRequest.voicesAuthorizationDetails("").length());
        SpeechRequest.parseStatus("{}"); SpeechRequest.parseStop("{}");
    }

    @Test public void validatesOptionalPlatformSettingsWithoutArbitraryRateCeiling() throws Exception {
        SpeechRequest.Speak request = SpeechRequest.parseSpeak(new JSONObject().put("text", "Hola\n¿qué tal?")
                .put("language", "es-MX").put("engine", "com.example.tts").put("voice", "es-mx-x-network")
                .put("rate", 6.25).put("pitch", 0.025).put("volume", 0));
        assertEquals("es-MX", request.language); assertEquals(6.25, request.rate, 0);
        assertEquals(0.025, request.pitch, 0); assertEquals(0, request.volume, 0);
        assertEquals("com.example.tts", SpeechRequest.parseVoices("{\"engine\":\"com.example.tts\"}"));
        assertEquals((double) Float.MAX_VALUE, SpeechRequest.parseSpeak(new JSONObject().put("text", "x")
                .put("rate", (double) Float.MAX_VALUE)).rate, 0);
    }

    @Test public void rejectsUnknownNullCoercedAndMalformedFields() throws Exception {
        String[] requests = {
                "{}", "{\"text\":null}", "{\"text\":4}", "{\"text\":\" \t\"}",
                "{\"text\":\"x\",\"text\":\"y\"}", "{text:'hello'}",
                "{\"text\":\"x\",\"queue\":true}", "{\"text\":\"x\",\"offline\":true}",
                "{\"text\":\"x\",\"rate\":\"1\"}", "{\"text\":\"x\",\"pitch\":false}",
                "{\"text\":\"x\",\"engine\":null}", "{\"text\":\"x\",\"language\":\"es_MX\"}",
                "{\"text\":\"x\",\"engine\":\"https://example.com\"}",
                "{\"text\":\"x\",\"engine\":\"com.example/.Service\"}"
        };
        for (String json : requests) assertThrows(json, FactoryException.class, () -> SpeechRequest.parseSpeak(json));
        assertThrows(FactoryException.class, () -> SpeechRequest.parseVoices("{\"voice\":\"x\"}"));
        assertThrows(FactoryException.class, () -> SpeechRequest.parseStop("{\"allEngines\":true}"));
        assertThrows(FactoryException.class, () -> SpeechRequest.parseStatus("{\"initialize\":true}"));
    }

    @Test public void rejectsNumericOverflowUnderflowAndNonpositiveRateOrPitch() throws Exception {
        for (String key : new String[]{"rate", "pitch"}) {
            for (double value : new double[]{0, -1, 1e100, 1e-100})
                assertThrows(FactoryException.class, () -> SpeechRequest.parseSpeak(new JSONObject().put("text", "x").put(key, value)));
        }
        for (double volume : new double[]{-0.01, 1.01})
            assertThrows(FactoryException.class, () -> SpeechRequest.parseSpeak(new JSONObject().put("text", "x").put("volume", volume)));
        assertThrows(FactoryException.class, () -> SpeechRequest.parseSpeak("{\"text\":\"x\",\"rate\":1e1000}"));
        FactoryException field = assertThrows(FactoryException.class,
                () -> SpeechRequest.parseSpeak(new JSONObject().put("text", "x").put("rate", true)));
        assertEquals("speech.speak: rate must be a number.", field.getMessage());
    }

    @Test public void unicodeIsPreservedAndMalformedSurrogatesAndControlsRejected() throws Exception {
        String text = "Hola 👋\r\n漢字\tالعربية";
        assertEquals(text, SpeechRequest.parseSpeak(new JSONObject().put("text", text)).text);
        for (String invalid : new String[]{"\ud800", "\udc00", "a\u0000b", "a\u0008b"})
            assertThrows(FactoryException.class, () -> SpeechRequest.parseSpeak(new JSONObject().put("text", invalid)));
    }

    @Test public void longTextUsesExistingBridgeBoundRatherThanSingleUtteranceLimit() throws Exception {
        String text = repeat("a", BridgeProtocol.MAX_TEXT_BYTES);
        assertEquals(text, SpeechRequest.parseSpeak(new JSONObject().put("text", text)).text);
        assertThrows(FactoryException.class, () -> SpeechRequest.parseSpeak(new JSONObject().put("text", text + "b")));
        assertThrows(FactoryException.class, () -> SpeechRequest.parseSpeak(new JSONObject()
                .put("text", repeat("é", BridgeProtocol.MAX_TEXT_BYTES / 2 + 1))));
    }

    @Test public void chunksNeverLoseWhitespaceOrSplitSurrogates() throws Exception {
        for (int limit : new int[]{2, 3, 7, 19, 4000}) {
            String text = repeat("hello 👩🏽‍💻 hola\n", 350);
            List<String> chunks = SpeechRequest.chunks(text, limit);
            StringBuilder restored = new StringBuilder();
            for (String chunk : chunks) {
                assertTrue(chunk.length() <= limit); assertFalse(chunk.isEmpty());
                assertFalse(Character.isLowSurrogate(chunk.charAt(0)));
                assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
                restored.append(chunk);
            }
            assertEquals(text, restored.toString());
        }
        assertThrows(FactoryException.class, () -> SpeechRequest.chunks("x", 1));
    }

    @Test public void authorizationCopiesDoNotMutateParsedRequest() throws Exception {
        SpeechRequest.Speak request = SpeechRequest.parseSpeak("{\"text\":\"original\"}");
        request.authorizationDetails().put("text", "replaced");
        assertEquals("original", request.text);
        assertEquals("original", request.authorizationDetails().getString("text"));
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
