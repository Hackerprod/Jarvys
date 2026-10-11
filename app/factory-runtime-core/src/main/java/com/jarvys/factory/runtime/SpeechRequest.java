package com.jarvys.factory.runtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/** Typed platform TTS requests. The bound is the transport budget, not a single-utterance limit. */
public final class SpeechRequest {
    public static final int MAX_ARGS_BYTES = BridgeProtocol.MAX_MESSAGE_BYTES;
    private SpeechRequest() { }

    public static final class Speak {
        public final String text, language, engine, voice;
        public final double rate, pitch, volume;
        private Speak(String text, String language, String engine, String voice, double rate, double pitch, double volume) {
            this.text = text; this.language = language; this.engine = engine; this.voice = voice;
            this.rate = rate; this.pitch = pitch; this.volume = volume;
        }
        public JSONObject authorizationDetails() {
            try {
                return new JSONObject().put("text", text).put("language", language).put("engine", engine)
                        .put("voice", voice).put("rate", rate).put("pitch", pitch).put("volume", volume);
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    public static Speak parseSpeak(String json) throws FactoryException {
        return parseSpeak(StrictJson.object(json, MAX_ARGS_BYTES));
    }
    public static Speak parseSpeak(JSONObject args) throws FactoryException {
        bounded(args);
        FactoryConfig.exactKeys(args, "text", "language", "engine", "voice", "rate", "pitch", "volume");
        try {
            String text = text(FactoryConfig.string(args, "text"), BridgeProtocol.MAX_TEXT_BYTES, true);
            if (text.trim().isEmpty()) throw invalid();
            String language = optional(args, "language", 64);
            if (!language.isEmpty()) {
                try {
                    Locale locale = new Locale.Builder().setLanguageTag(language).build();
                    if (locale.getLanguage().isEmpty()) throw invalid();
                } catch (IllformedLocaleException invalidLanguage) { throw invalid(); }
            }
            return new Speak(text, language, engine(optional(args, "engine", 255)), optional(args, "voice", 512),
                    number(args, "rate", false), number(args, "pitch", false), number(args, "volume", true));
        } catch (JSONException invalid) { throw invalid(); }
    }

    public static String parseVoices(String json) throws FactoryException {
        return parseVoices(StrictJson.object(json, 1024));
    }
    public static String parseVoices(JSONObject args) throws FactoryException {
        bounded(args); FactoryConfig.exactKeys(args, "engine");
        try { return engine(optional(args, "engine", 255)); }
        catch (JSONException invalid) { throw invalid(); }
    }
    public static JSONObject voicesAuthorizationDetails(String engine) throws FactoryException {
        try { return new JSONObject().put("engine", engine(engine)); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    public static void parseStatus(String json) throws FactoryException { parseEmpty(StrictJson.object(json, 64)); }
    public static void parseStop(String json) throws FactoryException { parseEmpty(StrictJson.object(json, 64)); }
    public static void parseEmpty(JSONObject args) throws FactoryException { bounded(args); FactoryConfig.exactKeys(args); }

    /** Sequential chunks preserve every UTF-16 unit and never divide a Unicode surrogate pair. */
    static List<String> chunks(String text, int maxLength) throws FactoryException {
        if (maxLength < 2 || text == null || text.isEmpty()) throw invalid();
        List<String> result = new ArrayList<>();
        for (int from = 0; from < text.length();) {
            int end = Math.min(text.length(), from + maxLength);
            if (end < text.length()) {
                if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
                // Prefer the last word boundary in the latter half; don't create tiny chunks.
                for (int at = end; at > from + (end - from) / 2; at--) {
                    if (Character.isWhitespace(text.charAt(at - 1))) { end = at; break; }
                }
            }
            result.add(text.substring(from, end)); from = end;
        }
        return Collections.unmodifiableList(result);
    }

    static String engine(String value) throws FactoryException {
        if (value == null || value.length() > 255 || (!value.isEmpty() &&
                !value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) throw invalid();
        return value;
    }
    private static double number(JSONObject args, String key, boolean volume) throws JSONException, FactoryException {
        if (!args.has(key)) return 1.0;
        Object value = args.get(key);
        if (!(value instanceof Number)) throw invalid();
        double number = ((Number) value).doubleValue();
        float platform = (float) number;
        if (Double.isNaN(number) || Double.isInfinite(number) || Float.isInfinite(platform) ||
                (volume ? number < 0 || number > 1 : number <= 0 || platform <= 0)) throw invalid();
        return number;
    }
    private static String optional(JSONObject args, String key, int maxBytes) throws JSONException, FactoryException {
        return args.has(key) ? text(FactoryConfig.string(args, key), maxBytes, false) : "";
    }
    private static String text(String value, int maxBytes, boolean multiline) throws FactoryException {
        if (value.length() > maxBytes || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw invalid();
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
            throw new FactoryException("TOO_LARGE", "Speech arguments exceed the transport limit.");
    }
    private static FactoryException invalid() { return new FactoryException("INVALID_ARGUMENT", "Invalid typed speech arguments."); }
}
