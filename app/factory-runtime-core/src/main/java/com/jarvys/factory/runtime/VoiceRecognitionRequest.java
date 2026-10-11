package com.jarvys.factory.runtime;

import java.nio.charset.StandardCharsets;
import java.util.IllformedLocaleException;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/** Closed, bounded options for Android's user-visible speech recognition Activity. */
public final class VoiceRecognitionRequest {
    public static final int MAX_ARGS_BYTES = 8192;
    public static final int MAX_LANGUAGE_BYTES = 64, MAX_PROMPT_BYTES = 1024;
    public static final int MAX_RESULTS = 32, DEFAULT_MAX_RESULTS = 5;
    public static final int MAX_TIMEOUT_MS = 600000, DEFAULT_TIMEOUT_MS = 120000;
    public static final int MAX_RESULT_BYTES = BridgeProtocol.MAX_TEXT_BYTES;
    private VoiceRecognitionRequest() { }

    public static final class Recognize {
        public final String language, languageModel, prompt;
        public final int maxResults, timeoutMs;
        public final boolean preferOffline;
        private Recognize(String language, String languageModel, String prompt, int maxResults,
                          boolean preferOffline, int timeoutMs) {
            this.language = language; this.languageModel = languageModel; this.prompt = prompt;
            this.maxResults = maxResults; this.preferOffline = preferOffline; this.timeoutMs = timeoutMs;
        }
        /** New copy containing all defaults, for exact authorization binding and review. */
        public JSONObject authorizationDetails() {
            try {
                return new JSONObject().put("language", language).put("languageModel", languageModel)
                        .put("prompt", prompt).put("maxResults", maxResults)
                        .put("preferOffline", preferOffline).put("timeoutMs", timeoutMs);
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    public static Recognize parseRecognize(String json) throws FactoryException {
        return parseRecognize(StrictJson.object(json, MAX_ARGS_BYTES));
    }
    public static Recognize parseRecognize(JSONObject args) throws FactoryException {
        bounded(args);
        FactoryConfig.exactKeys(args, "language", "languageModel", "prompt", "maxResults", "preferOffline", "timeoutMs");
        try {
            String language = optional(args, "language", "", MAX_LANGUAGE_BYTES);
            if (!language.isEmpty()) {
                try {
                    Locale locale = new Locale.Builder().setLanguageTag(language).build();
                    if (locale.getLanguage().isEmpty()) throw invalid();
                } catch (IllformedLocaleException malformed) { throw invalid(); }
            }
            String model = optional(args, "languageModel", "free_form", 32);
            if (!"free_form".equals(model) && !"web_search".equals(model)) throw invalid();
            String prompt = optional(args, "prompt", "", MAX_PROMPT_BYTES);
            boolean offline = false;
            if (args.has("preferOffline")) {
                Object value = args.get("preferOffline");
                if (!(value instanceof Boolean)) throw invalid();
                offline = (Boolean) value;
            }
            return new Recognize(language, model, prompt, integer(args, "maxResults", DEFAULT_MAX_RESULTS, MAX_RESULTS),
                    offline, integer(args, "timeoutMs", DEFAULT_TIMEOUT_MS, MAX_TIMEOUT_MS));
        } catch (JSONException malformed) { throw invalid(); }
    }
    public static void parseStatus(String json) throws FactoryException { parseEmpty(StrictJson.object(json, 64)); }
    public static void parseCancel(String json) throws FactoryException { parseEmpty(StrictJson.object(json, 64)); }
    public static void parseEmpty(JSONObject args) throws FactoryException { bounded(args); FactoryConfig.exactKeys(args); }

    private static String optional(JSONObject args, String key, String fallback, int limit) throws JSONException, FactoryException {
        return args.has(key) ? text(FactoryConfig.string(args, key), limit, false) : fallback;
    }
    private static int integer(JSONObject args, String key, int fallback, int maximum) throws JSONException, FactoryException {
        if (!args.has(key)) return fallback;
        Object value = args.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid();
        long number = ((Number) value).longValue();
        if (number < 1 || number > maximum) throw invalid();
        return (int) number;
    }
    static String text(String value, int limit, boolean multiline) throws FactoryException {
        if (value == null || value.length() > limit || value.getBytes(StandardCharsets.UTF_8).length > limit) throw invalid();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\r' || c == '\t'))) throw invalid();
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(c)) throw invalid();
        }
        return value;
    }
    private static void bounded(JSONObject args) throws FactoryException {
        if (args == null) throw invalid();
        String encoded = args.toString();
        if (encoded == null || encoded.length() > MAX_ARGS_BYTES || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_ARGS_BYTES)
            throw new FactoryException("TOO_LARGE", "Voice recognition options exceed the implementation's bounded intent budget.");
    }
    private static FactoryException invalid() {
        return new FactoryException("INVALID_ARGUMENT", "Invalid typed voice recognition options.");
    }
}
