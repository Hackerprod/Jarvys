package com.jarvys.factory.runtime;

import android.content.Context;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** All synthesis is fake and injectable: no real engine initialization, sound, downloads or network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 35}, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeSpeechTest {
    private static final NativeOperationAuthorization.Access ALLOW = () -> { };
    private final List<FakeEngine> made = new ArrayList<>();
    private NativeSpeech speech;
    private Result result;
    private boolean synchronousInitialization;

    @Before public void setup() {
        Context context = RuntimeEnvironment.getApplication();
        speech = new NativeSpeech(context, context.getPackageName(), (engine, callback) -> {
            FakeEngine value = new FakeEngine(engine, callback); made.add(value);
            if (synchronousInitialization) callback.complete(TextToSpeech.SUCCESS);
            return value;
        });
        result = new Result();
    }
    @After public void cleanup() { speech.close(); idle(); }

    @Test public void identityAndStatusNeverInitializeAnEngine() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        assertThrows(IllegalArgumentException.class, () -> new NativeSpeech(context, "another.package"));
        assertThrows(IllegalArgumentException.class, () -> new NativeSpeech(null, "another.package"));
        assertEquals("uninitialized", speech.status().getString("status"));
        assertTrue(speech.status().getBoolean("networkMayBeUsed"));
        assertFalse(speech.status().getBoolean("engineInitialized"));
        assertFalse(speech.status().getBoolean("audibilityVerified"));
        speech.stop(ALLOW); assertTrue(made.isEmpty());
    }

    @Test public void denialPreventsConstructionAndCannotBeBypassedByMissingLease() throws Exception {
        NativeOperationAuthorization.Access denied = () -> { throw new SecurityException("denied"); };
        assertThrows(SecurityException.class, () -> speech.speak(request("hello"), denied, result));
        assertThrows(SecurityException.class, () -> speech.voices("", denied, result));
        assertThrows(SecurityException.class, () -> speech.speak(request("hello"), null, result));
        assertThrows(SecurityException.class, () -> speech.stop(denied));
        idle(); assertTrue(made.isEmpty()); assertEquals(0, result.calls);
    }

    @Test public void revocationBetweenAcceptanceAndInitializationPreventsEngineCreation() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        speech.speak(request("hello"), access(allowed), result);
        allowed.set(false); idle();
        assertTrue(made.isEmpty()); assertEquals("PERMISSION_DENIED", result.error.code);
        assertEquals(1, result.calls);
    }

    @Test public void asynchronousSubmissionIsDistinctFromEngineCompletionAndAudibility() throws Exception {
        speech.speak(request("hello"), ALLOW, result); idle();
        FakeEngine engine = made.get(0);
        assertEquals("initializing", speech.status().getString("status")); assertTrue(engine.texts.isEmpty());
        engine.initialized.complete(TextToSpeech.SUCCESS); idle();
        assertEquals("submitted", speech.status().getString("status")); assertEquals(0, result.calls);
        engine.progress.started(engine.lastId()); idle();
        assertEquals("speaking", speech.status().getString("status"));
        engine.progress.done(engine.lastId()); idle();
        assertEquals("completed", result.value.getString("status"));
        assertEquals("engine_reported_completion", result.value.getString("completionMeans"));
        assertFalse(result.value.getBoolean("audibilityVerified"));
        assertTrue(result.value.getBoolean("networkMayBeUsed"));
        assertEquals(1, result.value.getInt("completedChunks")); assertEquals(1, result.calls);
        assertEquals(1, engine.shutdowns); assertEquals(0, engine.stops);
    }

    @Test public void synchronousInitializationStillUsesReturnedEngineSafely() throws Exception {
        synchronousInitialization = true;
        speech.speak(request("hello"), ALLOW, result); idle();
        assertEquals(Arrays.asList("hello"), made.get(0).texts);
        made.get(0).progress.done(made.get(0).lastId()); idle();
        assertEquals("completed", result.value.getString("status"));
    }

    @Test public void longTextIsSequentialAndChecksFreshAuthorizationBeforeEveryChunk() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        speech.speak(request("abcdefghijklmnop"), access(allowed), result); idle();
        FakeEngine engine = made.get(0); engine.limit = 6; engine.ready(); idle();
        assertEquals(Arrays.asList("abcdef"), engine.texts);
        String first = engine.lastId(); engine.progress.done(first); idle();
        assertEquals(Arrays.asList("abcdef", "ghijkl"), engine.texts);
        engine.progress.done(first); idle(); assertEquals(2, engine.texts.size());
        allowed.set(false); engine.progress.done(engine.lastId()); idle();
        assertEquals(2, engine.texts.size()); assertEquals("PERMISSION_DENIED", result.error.code);
        assertEquals(1, engine.stops); assertEquals(1, engine.shutdowns);
    }

    @Test public void completedChunkCountsAndUnicodeMatchOriginalLongText() throws Exception {
        String text = "ab😀 cd😀 ef😀 gh😀";
        speech.speak(request(text), ALLOW, result); idle();
        FakeEngine engine = made.get(0); engine.limit = 5; engine.ready(); idle();
        int completed = 0;
        while (result.calls == 0) { engine.progress.done(engine.lastId()); idle(); assertTrue(++completed < 20); }
        assertEquals(text, String.join("", engine.texts));
        assertEquals(engine.texts.size(), result.value.getInt("completedChunks"));
        assertEquals(engine.texts.size(), result.value.getInt("submittedChunks"));
    }

    @Test public void eachConfigurationEffectRechecksAuthorization() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        speech.speak(request("hello"), access(allowed), result); idle();
        FakeEngine engine = made.get(0); engine.onRate = () -> allowed.set(false); engine.ready(); idle();
        assertEquals(1, engine.rates); assertEquals(0, engine.pitches); assertTrue(engine.texts.isEmpty());
        assertEquals("PERMISSION_DENIED", result.error.code);
    }

    @Test public void revocationPollingStopsAnUtteranceWithoutWaitingForEngineCallback() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        speech.speak(request("hello"), access(allowed), result); idle();
        FakeEngine engine = made.get(0); engine.ready(); idle();
        allowed.set(false); advance(1000);
        assertEquals("PERMISSION_DENIED", result.error.code); assertEquals(1, engine.stops);
    }

    @Test public void stopCancelsOnlyOwnedWorkAndLateCallbacksCannotCompleteOrRestartIt() throws Exception {
        speech.speak(request("hello"), ALLOW, result); idle();
        FakeEngine engine = made.get(0); engine.ready(); idle(); String id = engine.lastId();
        JSONObject stopped = speech.stop(ALLOW); idle();
        assertTrue(stopped.getBoolean("cancelled")); assertTrue(stopped.getBoolean("emittedAudioCannotBeRecalled"));
        assertEquals("CANCELLED", result.error.code); assertEquals(1, engine.stops); assertEquals(1, engine.shutdowns);
        engine.progress.done(id); engine.progress.error(id, -1); engine.ready(); idle();
        assertEquals(1, result.calls); assertEquals(1, engine.texts.size());
        assertFalse(speech.stop(ALLOW).getBoolean("cancelled"));
        assertEquals("cancelled", speech.status().getString("status"));
    }

    @Test public void cancellationBeforeInitializationCallbackPreventsAnyUtterance() throws Exception {
        speech.speak(request("hello"), ALLOW, result); idle();
        FakeEngine engine = made.get(0); speech.stop(ALLOW); engine.ready(); idle();
        assertTrue(engine.texts.isEmpty()); assertEquals("CANCELLED", result.error.code); assertEquals(1, result.calls);
    }

    @Test public void deniedExplicitStopPreservesWorkButLifecycleCleanupNeedsNoLease() throws Exception {
        speech.speak(request("hello"), ALLOW, result); idle();
        FakeEngine engine = made.get(0); engine.ready(); idle();
        assertThrows(SecurityException.class, () -> speech.stop(() -> { throw new SecurityException("denied"); }));
        assertEquals(0, engine.stops); assertEquals(0, result.calls);
        speech.close(); speech.close(); idle();
        assertEquals(1, engine.stops); assertEquals(1, engine.shutdowns); assertEquals("CANCELLED", result.error.code);
    }

    @Test public void closedLifecycleIsIdempotentAndCannotInitializeOrRestart() throws Exception {
        speech.speak(request("hello"), ALLOW, result); speech.close(); speech.close(); idle();
        assertTrue(made.isEmpty()); assertEquals("CANCELLED", result.error.code); assertEquals(1, result.calls);
        assertEquals("closed", speech.status().getString("status"));
        assertEquals("CANCELLED", assertThrows(FactoryException.class, () -> speech.speak(request("again"), ALLOW, new Result())).code);
    }

    @Test public void busyDoesNotInterruptExistingOperationAndNewWorkUsesFreshEngine() throws Exception {
        speech.speak(request("one"), ALLOW, result); idle();
        assertEquals("BUSY", assertThrows(FactoryException.class, () -> speech.voices("", ALLOW, new Result())).code);
        assertEquals(1, made.size()); assertEquals(0, made.get(0).stops);
        speech.stop(ALLOW); idle();
        Result next = new Result(); speech.speak(request("two"), ALLOW, next); idle();
        FakeEngine first = made.get(0), second = made.get(1);
        first.ready(); second.ready(); idle();
        assertTrue(first.texts.isEmpty()); assertEquals(Arrays.asList("two"), second.texts);
    }

    @Test public void voicesReflectEngineMetadataWithoutOfflineOrExactEngineGuarantee() throws Exception {
        speech.voices("com.preferred.engine", ALLOW, result); idle();
        FakeEngine engine = made.get(0); assertEquals("com.preferred.engine", engine.preferred); engine.ready(); idle();
        assertEquals("ready", result.value.getString("status"));
        assertEquals("com.preferred.engine", result.value.getString("requestedEngine"));
        assertEquals("com.system.default", result.value.getString("systemDefaultEngine"));
        assertEquals("android_preference_may_fallback", result.value.getString("engineSelection"));
        assertEquals(2, result.value.getJSONArray("voices").length());
        assertFalse(result.value.getJSONArray("voices").getJSONObject(0).getBoolean("networkRequired"));
        assertTrue(result.value.getJSONArray("voices").getJSONObject(1).getBoolean("networkRequired"));
        assertTrue(result.value.getBoolean("networkMayBeUsed")); assertTrue(engine.texts.isEmpty());
        assertEquals(1, engine.shutdowns);
    }

    @Test public void requestedLanguageVoiceAndParametersReachEngineAndSelectionIsReported() throws Exception {
        speech.speak(SpeechRequest.parseSpeak(new JSONObject().put("text", "hola").put("language", "es-MX")
                .put("voice", "online").put("rate", 5.25).put("pitch", 0.25).put("volume", 0.4)), ALLOW, result);
        idle(); FakeEngine engine = made.get(0); engine.ready(); idle();
        assertEquals("es-MX", engine.language.toLanguageTag()); assertEquals("online", engine.voice.getName());
        assertEquals(5.25f, engine.rate, 0); assertEquals(0.25f, engine.pitch, 0); assertEquals(0.4f, engine.volume, 0);
        engine.progress.done(engine.lastId()); idle();
        assertEquals("online", result.value.getJSONObject("selectedVoice").getString("name"));
    }

    @Test public void unavailableLanguageOrVoiceAndRejectedSubmissionAreErrorsNotCompletion() throws Exception {
        speech.speak(SpeechRequest.parseSpeak("{\"text\":\"hello\",\"language\":\"en-US\"}"), ALLOW, result); idle();
        FakeEngine missing = made.get(0); missing.languageResult = TextToSpeech.LANG_MISSING_DATA; missing.ready(); idle();
        assertEquals("SPEECH_DATA_MISSING", result.error.code); assertTrue(missing.texts.isEmpty());
        result = new Result(); speech.speak(SpeechRequest.parseSpeak("{\"text\":\"hello\",\"voice\":\"missing\"}"), ALLOW, result); idle();
        FakeEngine voice = made.get(1); voice.ready(); idle(); assertEquals("VOICE_UNAVAILABLE", result.error.code);
        result = new Result(); speech.speak(request("hello"), ALLOW, result); idle();
        FakeEngine rejected = made.get(2); rejected.speakResult = TextToSpeech.ERROR; rejected.ready(); idle();
        assertEquals("SPEECH_ERROR", result.error.code); assertEquals("error", speech.status().getString("status"));
    }

    @Test public void engineErrorsAndForeignOrDuplicateCallbacksCannotInventCompletion() throws Exception {
        speech.speak(request("hello"), ALLOW, result); idle(); FakeEngine engine = made.get(0); engine.ready(); idle();
        engine.progress.done("another-utterance"); engine.progress.error("another-utterance", -1); idle();
        assertEquals(0, result.calls);
        engine.progress.error(engine.lastId(), TextToSpeech.ERROR_NETWORK); engine.progress.done(engine.lastId()); idle();
        assertEquals("SPEECH_ERROR", result.error.code); assertEquals(1, result.calls); assertNull(result.value);
    }

    @Test public void platformStopCallbackIsCancellation() throws Exception {
        speech.speak(request("hello"), ALLOW, result); idle(); FakeEngine engine = made.get(0); engine.ready(); idle();
        engine.progress.stopped(engine.lastId()); idle();
        assertEquals("CANCELLED", result.error.code); assertEquals("cancelled", speech.status().getString("status"));
    }

    @Test public void initializationFailureAndTimeoutReleaseResourcesAndSettleOnce() throws Exception {
        speech.speak(request("hello"), ALLOW, result); idle();
        FakeEngine failed = made.get(0); failed.initialized.complete(TextToSpeech.ERROR); idle();
        assertEquals("SPEECH_UNAVAILABLE", result.error.code); assertEquals(1, failed.shutdowns);
        result = new Result(); speech.speak(request("hello"), ALLOW, result); idle();
        FakeEngine hung = made.get(1); advance(NativeSpeech.INITIALIZATION_TIMEOUT_MS);
        assertEquals("TIMEOUT", result.error.code); assertEquals(1, hung.shutdowns);
        hung.ready(); idle(); assertTrue(hung.texts.isEmpty()); assertEquals(1, result.calls);
    }

    @Test public void missingCompletionCallbackHasBoundedWatchdogRatherThanFalseSuccess() throws Exception {
        speech.speak(request("hello"), ALLOW, result); idle(); FakeEngine engine = made.get(0); engine.ready(); idle();
        advance(NativeSpeech.CHUNK_TIMEOUT_MS);
        assertEquals("TIMEOUT", result.error.code); assertNull(result.value); assertEquals(1, engine.stops);
    }

    private static SpeechRequest.Speak request(String text) throws Exception { return SpeechRequest.parseSpeak(new JSONObject().put("text", text)); }
    private static NativeOperationAuthorization.Access access(AtomicBoolean allowed) {
        return () -> { if (!allowed.get()) throw new SecurityException("revoked"); };
    }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private static void advance(long milliseconds) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds)); }
    private static final class Result implements NativeSpeech.Callback {
        JSONObject value; FactoryException error; int calls;
        @Override public void complete(JSONObject value) { this.value = value; calls++; }
        @Override public void error(FactoryException error) { this.error = error; calls++; }
    }
    private static final class FakeEngine implements NativeSpeech.Engine {
        final String preferred;
        final NativeSpeech.Initialization initialized;
        final List<String> texts = new ArrayList<>(), ids = new ArrayList<>();
        final Set<Voice> choices = new LinkedHashSet<>(Arrays.asList(
                new Voice("local", Locale.US, 300, 200, false, new LinkedHashSet<>()),
                new Voice("online", Locale.forLanguageTag("es-MX"), 400, 300, true, new LinkedHashSet<>())));
        NativeSpeech.Progress progress;
        Locale language;
        Voice voice = choices.iterator().next();
        int limit = 4000, rates, pitches, stops, shutdowns;
        int languageResult = TextToSpeech.LANG_COUNTRY_AVAILABLE, speakResult = TextToSpeech.SUCCESS;
        float rate, pitch, volume;
        Runnable onRate = () -> { };
        FakeEngine(String preferred, NativeSpeech.Initialization initialized) { this.preferred = preferred; this.initialized = initialized; }
        void ready() { initialized.complete(TextToSpeech.SUCCESS); }
        String lastId() { return ids.get(ids.size() - 1); }
        @Override public int listen(NativeSpeech.Progress value) { progress = value; return TextToSpeech.SUCCESS; }
        @Override public Set<Voice> voices() { return choices; }
        @Override public Voice currentVoice() { return voice; }
        @Override public String defaultEngine() { return "com.system.default"; }
        @Override public int language(Locale locale) { language = locale; return languageResult; }
        @Override public int voice(Voice value) { voice = value; return TextToSpeech.SUCCESS; }
        @Override public int rate(float value) { rate = value; rates++; onRate.run(); return TextToSpeech.SUCCESS; }
        @Override public int pitch(float value) { pitch = value; pitches++; return TextToSpeech.SUCCESS; }
        @Override public int maxInputLength() { return limit; }
        @Override public int speak(String text, float volume, String id) {
            assertTrue(text.length() <= limit); texts.add(text); ids.add(id); this.volume = volume; return speakResult;
        }
        @Override public int stop() { stops++; return TextToSpeech.SUCCESS; }
        @Override public void shutdown() { shutdowns++; }
    }
}
