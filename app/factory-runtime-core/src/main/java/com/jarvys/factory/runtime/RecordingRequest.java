package com.jarvys.factory.runtime;

import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** Typed, finite microphone capture. Paths, URIs, engines and audio sources are never accepted. */
public final class RecordingRequest {
    public static final int MAX_ARGS_BYTES = 1024;
    public static final long MAX_DURATION_MS = 60L * 60 * 1000;
    public static final long MAX_BYTES = 512L * 1024 * 1024;
    public static final long DEFAULT_DURATION_MS = 10000, DEFAULT_MAX_BYTES = 2L * 1024 * 1024;
    public static final int WAV_HEADER_BYTES = 44;
    private RecordingRequest() { }

    public static final class Record {
        public final String format = "wav-pcm16";
        public final long durationMs, maxBytes;
        public final int sampleRate, channels;
        private Record(long durationMs, long maxBytes, int sampleRate, int channels) {
            this.durationMs = durationMs; this.maxBytes = maxBytes;
            this.sampleRate = sampleRate; this.channels = channels;
        }
        /** Includes the header and only complete PCM frames. Does not allocate the recording. */
        public long maximumFileBytes() {
            long frameBytes = channels * 2L;
            long durationFrames = durationMs * sampleRate / 1000L;
            long sizeFrames = (maxBytes - WAV_HEADER_BYTES) / frameBytes;
            return WAV_HEADER_BYTES + Math.min(durationFrames, sizeFrames) * frameBytes;
        }
        public JSONObject authorizationDetails() {
            try {
                return new JSONObject().put("format", format).put("durationMs", durationMs)
                        .put("maxBytes", maxBytes).put("sampleRate", sampleRate).put("channels", channels);
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    public static Record parseRecord(String json) throws FactoryException {
        return parseRecord(StrictJson.object(json, MAX_ARGS_BYTES));
    }
    public static Record parseRecord(JSONObject args) throws FactoryException {
        bounded(args);
        FactoryConfig.exactKeys(args, "durationMs", "maxBytes", "format", "sampleRate", "channels");
        try {
            if (args.has("format") && !"wav-pcm16".equals(FactoryConfig.string(args, "format"))) throw invalid();
            long duration = integer(args, "durationMs", DEFAULT_DURATION_MS, 100, MAX_DURATION_MS);
            long bytes = integer(args, "maxBytes", DEFAULT_MAX_BYTES, WAV_HEADER_BYTES + 4, MAX_BYTES);
            int rate = (int) integer(args, "sampleRate", 16000, 8000, 48000);
            int channels = (int) integer(args, "channels", 1, 1, 2);
            return new Record(duration, bytes, rate, channels);
        } catch (JSONException invalid) { throw invalid(); }
    }
    public static void parseEmpty(String json) throws FactoryException {
        JSONObject args = StrictJson.object(json, MAX_ARGS_BYTES);
        FactoryConfig.exactKeys(args);
    }
    private static long integer(JSONObject args, String key, long fallback, long min, long max)
            throws JSONException, FactoryException {
        if (!args.has(key)) return fallback;
        Object value = args.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid();
        long number = ((Number) value).longValue();
        if (number < min || number > max) throw invalid();
        return number;
    }
    private static void bounded(JSONObject args) throws FactoryException {
        if (args == null) throw invalid();
        String json = args.toString();
        if (json == null || json.length() > MAX_ARGS_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_ARGS_BYTES)
            throw new FactoryException("TOO_LARGE", "Recording arguments exceed the transport limit.");
    }
    private static FactoryException invalid() {
        return new FactoryException("INVALID_ARGUMENT", "Invalid typed recording arguments.");
    }
}
