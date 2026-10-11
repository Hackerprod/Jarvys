package com.jarvys.factory.runtime;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceRecognitionRequestTest {
    @Test public void defaultsProduceExactlyTheBoundAuthorizationOptions() throws Exception {
        VoiceRecognitionRequest.Recognize request = VoiceRecognitionRequest.parseRecognize("{}");
        JSONObject details = request.authorizationDetails();
        assertEquals(6, details.length()); assertEquals("", details.getString("language"));
        assertEquals("free_form", details.getString("languageModel")); assertEquals("", details.getString("prompt"));
        assertEquals(5, details.getInt("maxResults")); assertFalse(details.getBoolean("preferOffline"));
        assertEquals(120000, details.getInt("timeoutMs"));
        VoiceRecognitionRequest.parseStatus("{}"); VoiceRecognitionRequest.parseCancel("{}");
        details.put("language", "fr"); assertEquals("", request.authorizationDetails().getString("language"));
    }
    @Test public void acceptsTypedPlatformOptionsWithoutClaimingOfflineEnforcement() throws Exception {
        VoiceRecognitionRequest.Recognize request = VoiceRecognitionRequest.parseRecognize(new JSONObject()
                .put("language", "es-MX").put("languageModel", "web_search").put("prompt", "Habla 👋")
                .put("maxResults", 32).put("preferOffline", true).put("timeoutMs", 600000));
        assertEquals("es-MX", request.language); assertEquals("web_search", request.languageModel);
        assertEquals("Habla 👋", request.prompt); assertTrue(request.preferOffline); assertEquals(32, request.maxResults);
        assertEquals(600000, request.timeoutMs);
        assertEquals(1, VoiceRecognitionRequest.parseRecognize("{\"maxResults\":1,\"timeoutMs\":1}").maxResults);
    }
    @Test public void rejectsCoercionsUnknownKeysNullDuplicateAndMalformedOptions() {
        for (String value : new String[]{
                "null", "[]", "{language:'es'}", "{\"language\":\"en\",\"language\":\"fr\"}",
                "{\"engine\":\"com.example\"}", "{\"offline\":true}", "{\"audioSource\":\"content://secret\"}",
                "{\"language\":null}", "{\"language\":8}", "{\"language\":\"es_MX\"}",
                "{\"languageModel\":\"other\"}", "{\"languageModel\":null}", "{\"prompt\":true}",
                "{\"maxResults\":\"5\"}", "{\"maxResults\":1.5}", "{\"maxResults\":0}", "{\"maxResults\":33}",
                "{\"timeoutMs\":true}", "{\"timeoutMs\":0}", "{\"timeoutMs\":600001}",
                "{\"preferOffline\":\"true\"}", "{\"preferOffline\":null}"
        }) assertThrows(value, FactoryException.class, () -> VoiceRecognitionRequest.parseRecognize(value));
        assertThrows(FactoryException.class, () -> VoiceRecognitionRequest.parseStatus("{\"initialize\":true}"));
        assertThrows(FactoryException.class, () -> VoiceRecognitionRequest.parseCancel("{\"allApps\":true}"));
        assertThrows(FactoryException.class, () -> VoiceRecognitionRequest.parseRecognize((JSONObject) null));
    }
    @Test public void appliesUtf8AndUnicodeBoundsWithoutSilentlyTruncating() throws Exception {
        assertEquals(512, VoiceRecognitionRequest.parseRecognize(new JSONObject().put("prompt", repeat("é", 512))).prompt.length());
        for (String value : new String[]{repeat("é", 513), "broken\ud800", "\udc00", "a\u0000b", "two\nlines"})
            assertThrows(FactoryException.class, () -> VoiceRecognitionRequest.parseRecognize(new JSONObject().put("prompt", value)));
        assertThrows(FactoryException.class, () -> VoiceRecognitionRequest.parseRecognize(new JSONObject().put("language", repeat("a", 65))));
        assertThrows(FactoryException.class, () -> VoiceRecognitionRequest.parseRecognize(new JSONObject().put("prompt", repeat("a", 8193))));
    }
    private static String repeat(String text, int count) { StringBuilder value = new StringBuilder(); for (int i = 0; i < count; i++) value.append(text); return value.toString(); }
}
