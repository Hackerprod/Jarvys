package com.jarvys.factory.runtime;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Lifecycle-owned Android TTS, with no policy store and no promise of offline or exact-engine output.
 * The selected installed engine can use its own network permissions. Caller must close on lifecycle
 * termination/revocation. Stop/shutdown cannot recall already emitted audio or transmitted text.
 */
public final class NativeSpeech implements AutoCloseable {
    public interface Callback {
        void complete(JSONObject result);
        void error(FactoryException error);
    }
    interface Initialization { void complete(int status); }
    interface Progress {
        void started(String id);
        void done(String id);
        void error(String id, int code);
        void stopped(String id);
    }
    /** Injectable in package tests; tests never initialize a real engine or produce sound. */
    interface EngineFactory { Engine create(String preferredEngine, Initialization callback); }
    interface Engine {
        int listen(Progress listener);
        Set<Voice> voices();
        Voice currentVoice();
        String defaultEngine();
        int language(Locale locale);
        int voice(Voice voice);
        int rate(float rate);
        int pitch(float pitch);
        int maxInputLength();
        int speak(String text, float volume, String id);
        int stop();
        void shutdown();
    }

    static final long INITIALIZATION_TIMEOUT_MS = 30000;
    static final long CHUNK_TIMEOUT_MS = 10 * 60 * 1000;
    private static final long AUTHORIZATION_POLL_MS = 1000;
    private static final int MAX_VOICE_RESULT_BYTES = BridgeProtocol.MAX_TEXT_BYTES;
    private final Context context;
    private final EngineFactory engines;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Operation active;
    private boolean closed;
    private long sequence;
    private String state = "uninitialized", lastError = "";
    private int completedChunks;

    /** Installed apps only. Preview must use a simulated adapter. No TTS engine is initialized here. */
    public NativeSpeech(Context context, String appId) { this(context, appId, null); }
    NativeSpeech(Context context, String appId, EngineFactory engines) {
        if (context == null || appId == null || !appId.equals(context.getPackageName()))
            throw new IllegalArgumentException("Speech identity must match the installed package.");
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        this.engines = engines == null ? (preferred, callback) -> new PlatformEngine(this.context, preferred, callback) : engines;
    }

    /** Package discovery and our last observed state only. Never binds or initializes an engine. */
    public synchronized JSONObject status() {
        JSONArray installed = new JSONArray();
        boolean discoveryAvailable = true;
        try {
            List<ResolveInfo> services = context.getPackageManager().queryIntentServices(
                    new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0);
            Set<String> names = new TreeSet<>();
            if (services != null) for (ResolveInfo service : services)
                if (service.serviceInfo != null && service.serviceInfo.enabled && service.serviceInfo.exported &&
                        service.serviceInfo.packageName != null) names.add(service.serviceInfo.packageName);
            for (String name : names) installed.put(name);
        } catch (RuntimeException unavailable) { discoveryAvailable = false; }
        try {
            return disclosure(new JSONObject()).put("status", closed ? "closed" : state)
                    .put("engineDiscoveryAvailable", discoveryAvailable).put("installedEngines", installed)
                    .put("available", discoveryAvailable && installed.length() > 0).put("busy", active != null)
                    .put("engineInitialized", active != null && active.initialized)
                    .put("completedChunks", completedChunks).put("error", lastError)
                    .put("completionMeans", "engine_reported_completion");
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Settles once, on final engine completion or error/cancellation; submission is visible via status. */
    public void speak(SpeechRequest.Speak request, NativeOperationAuthorization.Access authorization, Callback callback)
            throws FactoryException {
        if (request == null) throw new FactoryException("INVALID_ARGUMENT", "A speech request is required.");
        begin(request, request.engine, authorization, callback);
    }
    /** Voice enumeration itself may bind an installed engine, so it requires a live authorization. */
    public void voices(String engine, NativeOperationAuthorization.Access authorization, Callback callback)
            throws FactoryException {
        begin(null, SpeechRequest.engine(engine), authorization, callback);
    }
    private synchronized void begin(SpeechRequest.Speak request, String engine,
                                    NativeOperationAuthorization.Access authorization, Callback callback) throws FactoryException {
        require(authorization);
        if (callback == null) throw new IllegalArgumentException("Speech callback is required.");
        if (closed) throw new FactoryException("CANCELLED", "Speech lifecycle has ended.");
        if (active != null) throw new FactoryException("BUSY", "Another speech operation is active.");
        Operation work = new Operation(++sequence, request, engine, authorization, callback);
        active = work; state = "initializing"; lastError = ""; completedChunks = 0;
        main.post(() -> guarded(work, () -> initialize(work)));
    }
    private void initialize(Operation work) throws FactoryException {
        deadline(work, INITIALIZATION_TIMEOUT_MS);
        work.authorization.check();
        // An engine is allowed to invoke its callback synchronously before create() returns.
        work.engine = engines.create(work.preferredEngine, result -> main.post(() -> guarded(work, () -> initialized(work, result))));
        if (work.engine == null) throw unavailable();
        main.postDelayed(work.poll, AUTHORIZATION_POLL_MS);
    }
    private void initialized(Operation work, int result) throws FactoryException {
        if (work.initialized) return; // Ignore buggy duplicate initialization callbacks.
        if (result != TextToSpeech.SUCCESS) throw new FactoryException("SPEECH_UNAVAILABLE", "Android speech initialization failed.");
        work.initialized = true;
        work.authorization.check();
        work.systemDefaultEngine = safe(work.engine.defaultEngine());
        if (work.request == null) { enumerate(work); return; }
        work.authorization.check();
        if (work.engine.listen(new Progress() {
            @Override public void started(String id) { main.post(() -> guarded(work, () -> { if (matches(work, id)) state = "speaking"; })); }
            @Override public void done(String id) { main.post(() -> guarded(work, () -> chunkDone(work, id))); }
            @Override public void error(String id, int code) { main.post(() -> guarded(work, () -> {
                if (matches(work, id)) throw new FactoryException("SPEECH_ERROR", "The installed speech engine reported an error.");
            })); }
            @Override public void stopped(String id) { main.post(() -> guarded(work, () -> {
                if (matches(work, id)) finish(work, null, new FactoryException("CANCELLED", "The speech engine stopped this utterance."));
            })); }
        }) != TextToSpeech.SUCCESS) throw unavailable();
        SpeechRequest.Speak request = work.request;
        if (!request.language.isEmpty()) {
            work.authorization.check();
            int language = work.engine.language(Locale.forLanguageTag(request.language));
            if (language == TextToSpeech.LANG_MISSING_DATA)
                throw new FactoryException("SPEECH_DATA_MISSING", "The installed engine needs language data.");
            if (language < 0) throw new FactoryException("LANGUAGE_UNAVAILABLE", "The installed engine does not support this language.");
        }
        if (!request.voice.isEmpty()) {
            work.authorization.check();
            Set<Voice> choices = work.engine.voices();
            Voice selected = null;
            if (choices != null) for (Voice voice : choices)
                if (voice != null && request.voice.equals(voice.getName())) { selected = voice; break; }
            if (selected == null) throw new FactoryException("VOICE_UNAVAILABLE", "The installed engine did not offer this voice.");
            work.authorization.check();
            if (work.engine.voice(selected) != TextToSpeech.SUCCESS)
                throw new FactoryException("VOICE_UNAVAILABLE", "The installed engine could not select this voice.");
        }
        work.authorization.check();
        if (work.engine.rate((float) request.rate) != TextToSpeech.SUCCESS)
            throw new FactoryException("SPEECH_CONFIGURATION_ERROR", "The installed engine rejected the speech rate.");
        work.authorization.check();
        if (work.engine.pitch((float) request.pitch) != TextToSpeech.SUCCESS)
            throw new FactoryException("SPEECH_CONFIGURATION_ERROR", "The installed engine rejected the speech pitch.");
        work.authorization.check();
        work.selectedVoice = work.engine.currentVoice();
        work.chunks = SpeechRequest.chunks(request.text, work.engine.maxInputLength());
        submit(work);
    }
    private void enumerate(Operation work) throws FactoryException {
        work.authorization.check();
        Set<Voice> values = work.engine.voices();
        List<Voice> ordered = new ArrayList<>();
        if (values != null) for (Voice voice : values) if (voice != null) ordered.add(voice);
        Collections.sort(ordered, Comparator.comparing(voice -> safe(voice.getName())));
        JSONArray voices = new JSONArray();
        int bytes = 0;
        boolean truncated = false;
        for (Voice voice : ordered) {
            JSONObject item = describe(voice);
            int size = item.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
            if (bytes + size > MAX_VOICE_RESULT_BYTES) { truncated = true; break; }
            voices.put(item); bytes += size;
        }
        try {
            JSONObject result = receipt(work, "ready").put("voices", voices).put("truncated", truncated)
                    .put("totalVoices", ordered.size());
            work.authorization.check();
            finish(work, result, null);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private void submit(Operation work) throws FactoryException {
        work.currentId = "factory-speech:" + work.id + ":" + work.completed;
        deadline(work, CHUNK_TIMEOUT_MS);
        work.authorization.check();
        if (work.engine.speak(work.chunks.get(work.completed), (float) work.request.volume, work.currentId) != TextToSpeech.SUCCESS)
            throw new FactoryException("SPEECH_ERROR", "The installed speech engine rejected the utterance.");
        work.submitted++; state = "submitted";
    }
    private void chunkDone(Operation work, String id) throws FactoryException {
        if (!matches(work, id)) return;
        work.currentId = null; work.completed++; completedChunks = work.completed;
        if (work.completed < work.chunks.size()) submit(work);
        else {
            work.authorization.check();
            finish(work, receipt(work, "completed"), null);
        }
    }

    /** Stops only this helper's current operation, without constructing any engine. */
    public synchronized JSONObject stop(NativeOperationAuthorization.Access authorization) {
        require(authorization);
        boolean hadActive = active != null;
        if (hadActive) {
            authorization.check();
            finish(active, null, new FactoryException("CANCELLED", "Speech operation was cancelled by this application."));
        }
        try {
            return disclosure(new JSONObject()).put("status", "stop_requested").put("cancelled", hadActive)
                    .put("emittedAudioCannotBeRecalled", true);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    /** Cleanup cannot depend on a lease which may already have expired or been revoked. Idempotent. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (active != null) finish(active, null, new FactoryException("CANCELLED", "Speech lifecycle ended."));
    }

    private interface Action { void run() throws FactoryException; }
    private synchronized void guarded(Operation work, Action action) {
        if (active != work || closed) return;
        try { work.authorization.check(); action.run(); }
        catch (SecurityException revoked) { finish(work, null, new FactoryException("PERMISSION_DENIED", "Speech authorization ended.")); }
        catch (FactoryException failure) { finish(work, null, failure); }
        catch (RuntimeException failure) { finish(work, null, unavailable()); }
    }
    private void deadline(Operation work, long delay) {
        main.removeCallbacks(work.timeout); main.postDelayed(work.timeout, delay);
    }
    private void finish(Operation work, JSONObject result, FactoryException error) {
        if (active != work) return;
        active = null;
        main.removeCallbacks(work.timeout); main.removeCallbacks(work.poll);
        state = error == null ? "completed" : "CANCELLED".equals(error.code) ? "cancelled" : "error";
        lastError = error == null ? "" : error.code;
        completedChunks = work.completed;
        // No authorization is required for best-effort cancellation/release of our own resources.
        if (work.engine != null) {
            if (error != null) try { work.engine.stop(); } catch (RuntimeException ignored) { }
            try { work.engine.shutdown(); } catch (RuntimeException ignored) { }
        }
        main.post(() -> { if (error == null) work.callback.complete(result); else work.callback.error(error); });
    }
    private static boolean matches(Operation work, String id) { return work.currentId != null && work.currentId.equals(id); }
    private static void require(NativeOperationAuthorization.Access authorization) {
        if (authorization == null) throw new SecurityException("Live speech authorization required.");
        authorization.check();
    }
    private static JSONObject receipt(Operation work, String status) {
        try {
            JSONObject result = disclosure(new JSONObject()).put("status", status).put("requestedEngine", work.preferredEngine)
                    .put("systemDefaultEngine", work.systemDefaultEngine).put("submittedChunks", work.submitted)
                    .put("completedChunks", work.completed).put("completionMeans", "engine_reported_completion");
            if (work.selectedVoice != null) result.put("selectedVoice", describe(work.selectedVoice));
            return result;
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JSONObject describe(Voice voice) {
        try {
            return new JSONObject().put("name", safe(voice.getName()))
                    .put("language", voice.getLocale() == null ? "" : voice.getLocale().toLanguageTag())
                    .put("quality", voice.getQuality()).put("latency", voice.getLatency())
                    .put("networkRequired", voice.isNetworkConnectionRequired());
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JSONObject disclosure(JSONObject result) {
        try {
            return result.put("networkMayBeUsed", true).put("engineSelection", "android_preference_may_fallback")
                    .put("audibilityVerified", false);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static FactoryException unavailable() { return new FactoryException("SPEECH_UNAVAILABLE", "Android speech is unavailable."); }

    private final class Operation {
        final long id;
        final SpeechRequest.Speak request;
        final String preferredEngine;
        final NativeOperationAuthorization.Access authorization;
        final Callback callback;
        Engine engine;
        boolean initialized;
        String currentId, systemDefaultEngine = "";
        List<String> chunks;
        Voice selectedVoice;
        int submitted, completed;
        final Runnable timeout = () -> guarded(this, () -> {
            throw new FactoryException("TIMEOUT", "The installed speech engine did not finish within the operation deadline.");
        });
        final Runnable poll = new Runnable() {
            @Override public void run() { guarded(Operation.this, () -> main.postDelayed(this, AUTHORIZATION_POLL_MS)); }
        };
        Operation(long id, SpeechRequest.Speak request, String preferredEngine,
                  NativeOperationAuthorization.Access authorization, Callback callback) {
            this.id = id; this.request = request; this.preferredEngine = preferredEngine;
            this.authorization = authorization; this.callback = callback;
        }
    }

    private static final class PlatformEngine implements Engine {
        final TextToSpeech tts;
        PlatformEngine(Context context, String preferred, Initialization callback) {
            tts = preferred.isEmpty() ? new TextToSpeech(context, callback::complete) :
                    new TextToSpeech(context, callback::complete, preferred);
        }
        @Override public int listen(Progress listener) {
            return tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { listener.started(id); }
                @Override public void onDone(String id) { listener.done(id); }
                @Override public void onError(String id) { listener.error(id, TextToSpeech.ERROR); }
                @Override public void onError(String id, int code) { listener.error(id, code); }
                @Override public void onStop(String id, boolean interrupted) { listener.stopped(id); }
            });
        }
        @Override public Set<Voice> voices() { return tts.getVoices(); }
        @Override public Voice currentVoice() { return tts.getVoice(); }
        @Override public String defaultEngine() { return tts.getDefaultEngine(); }
        @Override public int language(Locale locale) { return tts.setLanguage(locale); }
        @Override public int voice(Voice voice) { return tts.setVoice(voice); }
        @Override public int rate(float value) { return tts.setSpeechRate(value); }
        @Override public int pitch(float value) { return tts.setPitch(value); }
        @Override public int maxInputLength() { return TextToSpeech.getMaxSpeechInputLength(); }
        @Override public int speak(String text, float volume, String id) {
            Bundle params = new Bundle(); params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
            return tts.speak(text, TextToSpeech.QUEUE_ADD, params, id);
        }
        @Override public int stop() { return tts.stop(); }
        @Override public void shutdown() { tts.shutdown(); }
    }
}
