package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.speech.RecognizerIntent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** All external activity/recognizer behavior is fake. No audio, engine binding, network, or real launch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 35}, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeVoiceRecognitionTest {
    private static final NativeOperationAuthorization.Access ALLOW = () -> { };
    private Activity activity;
    private FakePlatform platform;
    private NativeVoiceRecognition recognition;
    private Result reply;

    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        platform = new FakePlatform();
        recognition = new NativeVoiceRecognition(activity, activity.getPackageName(), true, platform);
        reply = new Result();
    }
    @After public void cleanup() {
        recognition.close(); idle();
        recognition.onActivityResult(NativeVoiceRecognition.REQUEST_CODE, Activity.RESULT_CANCELED, null); idle();
    }

    @Test public void identityStatusAndCancellationNeverStartRecognition() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new NativeVoiceRecognition(activity, "other.package", true));
        assertThrows(IllegalArgumentException.class, () -> new NativeVoiceRecognition(null, "other.package", true));
        JSONObject status = recognition.status();
        assertEquals("idle", status.getString("status")); assertTrue(status.getBoolean("available"));
        assertTrue(status.getBoolean("networkMayBeUsed")); assertFalse(status.getBoolean("engineVerified"));
        assertFalse(status.getBoolean("networkUseVerified")); assertFalse(status.getBoolean("offlineGuaranteed"));
        assertFalse(status.getBoolean("accuracyVerified")); assertFalse(status.getBoolean("audioReturned"));
        assertFalse(recognition.cancel().getBoolean("cancelled")); idle();
        assertEquals(0, platform.launches.size()); assertEquals(0, platform.cancels); assertEquals(0, reply.calls);
    }
    @Test public void missingOrRevokedAuthorityCannotLaunch() throws Exception {
        assertThrows(SecurityException.class, () -> recognition.recognize(request(), null, reply));
        assertThrows(SecurityException.class, () -> recognition.recognize(request(), () -> { throw new SecurityException(); }, reply));
        AtomicBoolean allowed = new AtomicBoolean(true);
        recognition.recognize(request(), access(allowed), reply); allowed.set(false); idle();
        assertEquals("PERMISSION_DENIED", reply.error.code); assertEquals(1, reply.calls);
        assertTrue(platform.launches.isEmpty()); assertEquals(0, reply.claims);
    }
    @Test public void authorityIsRecheckedAfterUiClaimBeforeLaunching() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true); reply.afterClaim = () -> allowed.set(false);
        recognition.recognize(request(), access(allowed), reply); idle();
        assertEquals("PERMISSION_DENIED", reply.error.code); assertTrue(platform.launches.isEmpty());
        assertEquals(1, reply.claims); assertEquals(1, reply.releases); assertFalse(reply.handoff);
    }
    @Test public void noHandlerAndDisappearingHandlerAreTruthfullyUnavailable() throws Exception {
        platform.available = false; recognition.recognize(request(), ALLOW, reply); idle();
        assertFalse(recognition.status().getBoolean("available")); assertEquals("VOICE_RECOGNITION_UNAVAILABLE", reply.error.code);
        assertTrue(platform.launches.isEmpty()); assertEquals(0, reply.claims);
        platform.available = true; platform.reject = true; Result rejected = new Result();
        recognition.recognize(request(), ALLOW, rejected); idle();
        assertEquals("VOICE_RECOGNITION_UNAVAILABLE", rejected.error.code);
        assertEquals(1, rejected.claims); assertEquals(1, rejected.releases); assertFalse(rejected.handoff);
        assertFalse(recognition.hasPendingActivity()); assertEquals(0, platform.cancels);
    }
    @Test public void platformIntentOnlyContainsTypedOptionsAndNoInventedEngineOrAudio() throws Exception {
        VoiceRecognitionRequest.Recognize request = VoiceRecognitionRequest.parseRecognize(new JSONObject()
                .put("language", "es-MX").put("languageModel", "web_search").put("prompt", "Habla")
                .put("maxResults", 2).put("preferOffline", true));
        recognition.recognize(request, ALLOW, reply); idle();
        Intent launched = platform.launches.get(0);
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, launched.getAction());
        assertEquals("es-MX", launched.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));
        assertEquals("web_search", launched.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL));
        assertEquals("Habla", launched.getStringExtra(RecognizerIntent.EXTRA_PROMPT));
        assertEquals(2, launched.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, -1));
        assertTrue(launched.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false));
        assertEquals(5, launched.getExtras().size()); assertNull(launched.getPackage()); assertNull(launched.getComponent());
        assertNull(launched.getClipData()); assertNull(launched.getData()); assertEquals(0, launched.getFlags());
        assertTrue(reply.handoff); assertTrue(reply.held); assertEquals(0, reply.calls);
        assertTrue(recognition.hasPendingActivity()); assertEquals("awaiting_result", recognition.status().getString("status"));
    }
    @Test public void resultsAreCopiedDeferredUntilResumeAndFocusAndDiscloseUnknowns() throws Exception {
        recognize(); recognition.onPause(); platform.focused = false;
        ArrayList<String> values = new ArrayList<>(Arrays.asList("Hola", "Ola")); float[] scores = {0.75f, -1f};
        Intent returned = new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, values)
                .putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, scores);
        result(Activity.RESULT_OK, returned);
        values.set(0, "MUTATED"); scores[0] = 0; // The caller-owned parcel/list must not change buffered output.
        result(Activity.RESULT_CANCELED, null); idle(); assertEquals(0, reply.calls); assertEquals(0, reply.releases);
        recognition.onResume(); idle(); assertEquals(0, reply.calls);
        platform.focused = true; recognition.onWindowFocusChanged(true); idle();
        assertEquals(1, reply.calls); assertEquals(1, reply.releases); assertFalse(reply.handoff);
        assertEquals("Hola", reply.value.getJSONArray("results").getString(0));
        assertEquals(0.75, reply.value.getJSONArray("confidenceScores").getDouble(0), 0);
        assertTrue(reply.value.getJSONArray("confidenceScores").isNull(1));
        assertEquals("recognized", reply.value.getString("status"));
        assertEquals("recognizer_reported_results", reply.value.getString("completionMeans"));
        assertEquals("android_system_activity", reply.value.getString("engineSelection"));
        assertTrue(reply.value.getBoolean("networkMayBeUsed")); assertFalse(reply.value.getBoolean("accuracyVerified"));
        assertFalse(reply.value.getBoolean("offlineGuaranteed")); assertFalse(reply.value.getBoolean("engineVerified"));
        result(Activity.RESULT_OK, returned()); idle(); assertEquals(1, reply.calls);
        assertFalse(recognition.hasPendingActivity());
    }
    @Test public void optionalScoresRemainUnknownRatherThanFabricatedConfidence() throws Exception {
        recognize(); result(Activity.RESULT_OK, returned()); idle();
        assertTrue(reply.value.isNull("confidenceScores")); assertEquals("Hello", reply.value.getJSONArray("results").getString(0));
        assertEquals(1, reply.calls); assertEquals(1, reply.releases);
    }
    @Test public void revocationBeforeBufferedResultDeliveryDiscardsRecognizedText() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        recognition.recognize(request(), access(allowed), reply); idle(); recognition.onPause();
        result(Activity.RESULT_OK, returned()); allowed.set(false); recognition.onResume(); idle();
        assertEquals("PERMISSION_DENIED", reply.error.code); assertNull(reply.value); assertEquals(1, reply.calls);
        assertEquals(1, reply.releases); assertFalse(recognition.hasPendingActivity());
    }
    @Test public void ownExternalPauseDoesNotPollForegroundLeaseOrCancelRecognition() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        recognition.recognize(request(), access(allowed), reply); idle(); recognition.onPause(); allowed.set(false);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(0, reply.calls); assertEquals(0, platform.cancels); assertTrue(recognition.hasPendingActivity());
        allowed.set(true); result(Activity.RESULT_OK, returned()); recognition.onResume(); idle();
        assertNotNull(reply.value); assertEquals(1, reply.calls);
    }
    @Test public void cancelKeepsTombstoneUntilRealReturnAndStaleHandleCannotCancelSuccessor() throws Exception {
        NativeVoiceRecognition.Handle old = recognition.recognize(request(), ALLOW, reply); idle();
        JSONObject cancellation = recognition.cancel(); old.cancel(); idle();
        assertTrue(cancellation.getBoolean("cancelled")); assertFalse(cancellation.getBoolean("externalActivityClosedVerified"));
        assertEquals("CANCELLED", reply.error.code); assertEquals(1, reply.calls); assertEquals(1, platform.cancels);
        assertEquals(0, reply.releases); assertTrue(reply.handoff); assertTrue(recognition.hasPendingActivity());
        FactoryException busy = assertThrows(FactoryException.class, () -> recognition.recognize(request(), ALLOW, new Result()));
        assertEquals("BUSY", busy.code);
        result(Activity.RESULT_OK, returned()); idle(); assertEquals(1, reply.calls); assertEquals(1, reply.releases);
        Result next = new Result(); recognition.recognize(request(), ALLOW, next); idle(); old.cancel(); idle();
        assertEquals(2, platform.launches.size()); assertEquals(1, platform.cancels); assertEquals(0, next.calls);
        result(Activity.RESULT_OK, returned()); idle(); assertEquals(1, next.calls); assertNotNull(next.value);
    }
    @Test public void cancelBeforeQueuedLaunchHasNoEffectAndNeedsNoFreshGrant() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        NativeVoiceRecognition.Handle handle = recognition.recognize(request(), access(allowed), reply);
        allowed.set(false); handle.cancel(); recognition.cancel(); idle();
        assertTrue(platform.launches.isEmpty()); assertEquals(0, platform.cancels); assertEquals(0, reply.claims);
        assertEquals("CANCELLED", reply.error.code); assertEquals(1, reply.calls);
    }
    @Test public void timeoutCancelsOnceButCannotPretendExternalUiHasClosed() throws Exception {
        recognition.recognize(VoiceRecognitionRequest.parseRecognize("{\"timeoutMs\":10}"), ALLOW, reply); idle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));
        assertEquals("TIMEOUT", reply.error.code); assertEquals(1, reply.calls); assertEquals(1, platform.cancels);
        assertTrue(recognition.hasPendingActivity()); assertEquals(0, reply.releases);
        result(Activity.RESULT_OK, returned()); idle(); assertNull(reply.value); assertEquals(1, reply.calls);
        assertEquals(1, reply.releases); assertFalse(recognition.hasPendingActivity());
    }
    @Test public void closeCancelsAndRetainsExternalTombstoneWithoutResurrection() throws Exception {
        recognize(); recognition.close(); recognition.close(); idle();
        assertTrue(recognition.isClosed()); assertTrue(recognition.hasPendingActivity()); assertEquals(1, platform.cancels);
        result(Activity.RESULT_OK, returned()); recognition.onResume(); recognition.onWindowFocusChanged(true); idle();
        assertEquals("CANCELLED", reply.error.code); assertEquals(1, reply.calls); assertNull(reply.value);
        assertEquals(1, reply.releases); assertFalse(recognition.hasPendingActivity());
        assertThrows(FactoryException.class, () -> recognition.recognize(request(), ALLOW, new Result()));
    }
    @Test public void lostOwnerAndPauseBeforeLaunchCannotStartAnExternalActivity() throws Exception {
        recognition.recognize(request(), ALLOW, reply); reply.current = false; idle();
        assertEquals("CANCELLED", reply.error.code); assertTrue(platform.launches.isEmpty());
        Result paused = new Result(); recognition.recognize(request(), ALLOW, paused); recognition.onPause(); idle();
        assertEquals("CANCELLED", paused.error.code); assertTrue(platform.launches.isEmpty());
    }
    @Test public void busyUiAndLostWindowFocusAreNotBypassed() throws Exception {
        reply.allowClaim = false; recognize(); assertEquals("BUSY", reply.error.code); assertTrue(platform.launches.isEmpty());
        platform.focused = false; Result hidden = new Result(); recognition.recognize(request(), ALLOW, hidden); idle();
        assertEquals("UNAVAILABLE", hidden.error.code); assertEquals(0, hidden.claims); assertTrue(platform.launches.isEmpty());
    }
    @Test public void systemErrorsPreserveWhatAndroidActuallyReported() throws Exception {
        int[] codes = {Activity.RESULT_CANCELED, RecognizerIntent.RESULT_NO_MATCH, RecognizerIntent.RESULT_NETWORK_ERROR,
                RecognizerIntent.RESULT_AUDIO_ERROR, RecognizerIntent.RESULT_SERVER_ERROR, RecognizerIntent.RESULT_CLIENT_ERROR, 999};
        String[] expected = {"CANCELLED", "VOICE_RECOGNITION_NO_MATCH", "VOICE_RECOGNITION_NETWORK_ERROR", "VOICE_RECOGNITION_AUDIO_ERROR",
                "VOICE_RECOGNITION_SERVER_ERROR", "VOICE_RECOGNITION_ERROR", "VOICE_RECOGNITION_ERROR"};
        for (int i = 0; i < codes.length; i++) {
            Result value = new Result(); recognition.recognize(request(), ALLOW, value); idle(); result(codes[i], null); idle();
            assertEquals(expected[i], value.error.code); assertEquals(1, value.calls); assertNull(value.value);
            assertEquals(1, value.releases); assertFalse(recognition.hasPendingActivity());
        }
    }
    @Test public void oemMetadataAndUnusedIntentFieldsAreIgnoredWithExactlyWhitelistedOutput() throws Exception {
        List<Consumer<Intent>> mutations = new ArrayList<>(Arrays.asList(
                value -> value.setAction("metadata-marker"), value -> value.setData(Uri.parse("content://metadata-marker/item")),
                value -> value.setType("text/metadata-marker"), value -> value.setClipData(ClipData.newPlainText("metadata-marker", "metadata-marker")),
                value -> value.setSelector(new Intent("metadata-marker")), value -> value.setComponent(new ComponentName("metadata.marker", "metadata.marker.Screen")),
                value -> value.setPackage("metadata.marker"), value -> value.addCategory("metadata-marker"),
                value -> value.setSourceBounds(new Rect(0, 0, 1, 1)), value -> value.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                value -> value.putExtra("sent", true), value -> value.putExtra("nonce", "metadata-marker"),
                value -> value.putExtra("vendorMetadata", new UnrelatedMetadata())));
        if (Build.VERSION.SDK_INT >= 29) mutations.add(value -> value.setIdentifier("metadata-marker"));
        Set<String> expected = new HashSet<>(Arrays.asList("mode", "simulated", "engineSelection", "engineVerified",
                "networkMayBeUsed", "networkUseVerified", "offlineGuaranteed", "accuracyVerified", "audioReturned",
                "status", "results", "confidenceScores", "requestedLanguage", "requestedLanguageModel",
                "preferOfflineRequested", "completionMeans"));
        for (Consumer<Intent> mutation : mutations) {
            Intent returned = returned().putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[]{0.5f});
            mutation.accept(returned);
            Result value = new Result(); recognition.recognize(request(), ALLOW, value); idle();
            result(Activity.RESULT_OK, returned); idle();
            assertNull(value.error); assertEquals(1, value.calls); assertEquals(1, value.releases);
            assertEquals("Hello", value.value.getJSONArray("results").getString(0));
            assertEquals(0.5, value.value.getJSONArray("confidenceScores").getDouble(0), 0);
            Set<String> actual = new HashSet<>();
            for (Iterator<String> keys = value.value.keys(); keys.hasNext();) actual.add(keys.next());
            assertEquals(expected, actual); assertFalse(value.value.toString().contains("metadata-marker"));
        }
        assertEquals(mutations.size(), platform.launches.size()); assertEquals(0, platform.cancels);
    }
    @Test public void malformedHypothesesAndConfidenceCannotReachTheBridge() throws Exception {
        invalid(null); invalid(new Intent());
        invalid(new Intent().putExtra(RecognizerIntent.EXTRA_RESULTS, "text"));
        invalid(new Intent().putExtra(RecognizerIntent.EXTRA_RESULTS, (java.io.Serializable) new ArrayList<>(Arrays.asList(7))));
        for (String value : new String[]{"", "   ", "bad\ud800", "\udc00", "a\u0000b", repeat("a", VoiceRecognitionRequest.MAX_RESULT_BYTES)})
            invalid(new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, new ArrayList<>(Arrays.asList(value))));
        invalid(new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, new ArrayList<>(Arrays.asList("a", "b", "c", "d", "e", "f"))));
        invalid(returned().putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, "0.5"));
        for (float[] values : new float[][]{{}, {0, 1}, {Float.NaN}, {Float.POSITIVE_INFINITY}, {-0.1f}, {1.01f}})
            invalid(returned().putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, values));
    }
    @Test public void emptyResultsMeanNoMatchAndUnknownRequestCodesAreNotConsumed() throws Exception {
        recognize(); assertFalse(recognition.onActivityResult(51, Activity.RESULT_OK, returned())); assertEquals(0, reply.calls);
        result(Activity.RESULT_OK, new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, new ArrayList<>())); idle();
        assertEquals("VOICE_RECOGNITION_NO_MATCH", reply.error.code); assertNull(reply.value);
    }
    @Test public void completedOperationsCanRepeatWithoutLifetimeQuota() throws Exception {
        for (int i = 0; i < 80; i++) {
            Result value = new Result(); recognition.recognize(request(), ALLOW, value); idle();
            result(Activity.RESULT_OK, returned()); idle(); assertEquals(1, value.calls); assertNotNull(value.value);
        }
        assertEquals(80, platform.launches.size()); assertEquals(0, platform.cancels);
    }

    private void invalid(Intent data) throws Exception {
        Result value = new Result(); recognition.recognize(request(), ALLOW, value); idle(); result(Activity.RESULT_OK, data); idle();
        assertNotNull(value.error); assertEquals("INVALID_RECOGNITION_RESULT", value.error.code);
        assertEquals(1, value.calls); assertNull(value.value); assertEquals(1, value.releases);
    }
    private void recognize() throws Exception { recognition.recognize(request(), ALLOW, reply); idle(); }
    private void result(int result, Intent data) { assertTrue(recognition.onActivityResult(NativeVoiceRecognition.REQUEST_CODE, result, data)); }
    private static VoiceRecognitionRequest.Recognize request() throws Exception { return VoiceRecognitionRequest.parseRecognize("{}"); }
    private static Intent returned() { return new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, new ArrayList<>(Arrays.asList("Hello"))); }
    private static NativeOperationAuthorization.Access access(AtomicBoolean allowed) { return () -> { if (!allowed.get()) throw new SecurityException(); }; }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private static String repeat(String value, int count) { StringBuilder result = new StringBuilder(); for (int i = 0; i < count; i++) result.append(value); return result.toString(); }

    private static final class FakePlatform implements NativeVoiceRecognition.Platform {
        boolean available = true, focused = true, reject;
        int cancels;
        final List<Intent> launches = new ArrayList<>();
        public boolean available() { return available; }
        public boolean focused() { return focused; }
        public void launch(Intent intent, int requestCode) {
            assertEquals(NativeVoiceRecognition.REQUEST_CODE, requestCode);
            if (reject) throw new ActivityNotFoundException(); launches.add(new Intent(intent));
        }
        public void cancel(int requestCode) { assertEquals(NativeVoiceRecognition.REQUEST_CODE, requestCode); cancels++; }
    }
    private static final class UnrelatedMetadata implements java.io.Serializable {
        @Override public String toString() { throw new AssertionError("Unrelated OEM metadata must not be stringified."); }
    }
    private static final class Result implements NativeVoiceRecognition.Callback {
        boolean current = true, allowClaim = true, held, handoff;
        int calls, claims, releases;
        JSONObject value;
        FactoryException error;
        Runnable afterClaim;
        public boolean current() { return current; }
        public boolean claimUi() { claims++; if (!allowClaim) return false; assertFalse(held); held = true; if (afterClaim != null) afterClaim.run(); return true; }
        public void releaseUi() { assertTrue(held); held = false; releases++; }
        public void externalHandoff(boolean value) { handoff = value; }
        public void complete(JSONObject value) { calls++; this.value = value; }
        public void error(FactoryException error) { calls++; this.error = error; }
    }
}
