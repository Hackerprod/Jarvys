package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Installed, user-visible Android recognition Activity. Never records audio in this process.
 * Android chooses its handler; that handler may transmit audio even with preferOffline requested.
 * Cancellation cannot recall audio or verify the external Activity actually stopped recording.
 */
public final class NativeVoiceRecognition implements AutoCloseable {
    public static final int REQUEST_CODE = 52;
    private static final long AUTHORIZATION_POLL_MS = 1000;
    public interface Callback {
        boolean current();
        boolean claimUi();
        void releaseUi();
        void externalHandoff(boolean value);
        void complete(JSONObject result);
        void error(FactoryException error);
    }
    /** This exact operation only; a stale cancellation cannot affect its successor. */
    public interface Handle { void cancel(); }
    /** Package-test injection; no real system recognition, microphone, or network in tests. */
    interface Platform {
        boolean available();
        boolean focused();
        void launch(Intent intent, int requestCode);
        void cancel(int requestCode);
    }
    private final Platform platform;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean foreground, closed;
    private Operation active;
    private String state = "idle", lastError = "";

    public NativeVoiceRecognition(Activity activity, String appId, boolean foreground) {
        this(activity, appId, foreground, null);
    }
    NativeVoiceRecognition(Activity activity, String appId, boolean foreground, Platform platform) {
        if (activity == null || appId == null || !appId.equals(activity.getPackageName()))
            throw new IllegalArgumentException("Voice recognition identity must match the installed package.");
        this.foreground = foreground;
        this.platform = platform == null ? new AndroidPlatform(activity) : platform;
    }

    /** Package-manager discovery only. Availability is not a promise of language, network, or engine readiness. */
    public synchronized JSONObject status() {
        boolean available = false, discovered = true;
        try { available = platform.available(); } catch (RuntimeException unavailable) { discovered = false; }
        try {
            return disclosure(new JSONObject()).put("status", closed ? "closed" : state)
                    .put("available", available).put("handlerDiscoveryAvailable", discovered)
                    .put("busy", active != null).put("pendingActivity", hasPendingActivity()).put("error", lastError);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    public synchronized Handle recognize(VoiceRecognitionRequest.Recognize request,
                                         NativeOperationAuthorization.Access authorization, Callback callback) throws FactoryException {
        if (authorization == null) throw new SecurityException("Live voice recognition authorization required.");
        authorization.check();
        if (request == null) throw new FactoryException("INVALID_ARGUMENT", "Voice recognition options are required.");
        if (callback == null) throw new IllegalArgumentException("Voice recognition callback is required.");
        if (closed) throw new FactoryException("CANCELLED", "Voice recognition lifecycle has ended.");
        if (active != null) throw new FactoryException("BUSY", "A voice recognition Activity is active or still returning.");
        if (!foreground || !callback.current()) throw new FactoryException("UNAVAILABLE", "Voice recognition requires the current foreground page.");
        Operation work = new Operation(request, authorization, callback);
        active = work; state = "pending"; lastError = "";
        main.postDelayed(work.timeout, request.timeoutMs);
        main.post(() -> launch(work));
        return () -> cancel(work, new FactoryException("CANCELLED", "Voice recognition was cancelled; transmitted audio cannot be recalled."));
    }
    private synchronized void launch(Operation work) {
        if (active != work || work.done) return;
        try {
            check(work);
            if (!foreground || !platform.focused()) throw new FactoryException("UNAVAILABLE", "Voice recognition requires the active application window.");
            if (!platform.available()) throw unavailable();
            if (!work.callback.claimUi()) throw new FactoryException("BUSY", "Another native UI already owns the page.");
            work.uiHeld = true;
            check(work);
            work.callback.externalHandoff(true); work.handoff = true;
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, work.request.languageModel)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, work.request.maxResults)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, work.request.preferOffline);
            if (!work.request.language.isEmpty()) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, work.request.language);
            if (!work.request.prompt.isEmpty()) intent.putExtra(RecognizerIntent.EXTRA_PROMPT, work.request.prompt);
            check(work); // Fresh lease check at the last boundary before the external effect.
            work.launched = true;
            try { platform.launch(intent, REQUEST_CODE); }
            catch (RuntimeException failure) { work.launched = false; throw failure; }
            if (!work.done && !work.returned) {
                state = "awaiting_result";
                main.postDelayed(work.poll, AUTHORIZATION_POLL_MS);
            }
        } catch (FactoryException failure) { cancel(work, failure); }
        catch (SecurityException denied) { cancel(work, denied()); }
        catch (RuntimeException failure) { cancel(work, unavailable()); }
    }

    /** No fresh authority is necessary to cancel this adapter's own work. */
    public synchronized JSONObject cancel() {
        boolean cancelled = active != null && !active.done;
        if (cancelled) cancel(active, new FactoryException("CANCELLED", "Voice recognition cancellation was requested; transmitted audio cannot be recalled."));
        try {
            return disclosure(new JSONObject()).put("status", "cancel_requested").put("cancelled", cancelled)
                    .put("pendingActivity", hasPendingActivity()).put("externalActivityClosedVerified", false)
                    .put("transmittedAudioCannotBeRecalled", true);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private synchronized void cancel(Operation work, FactoryException error) {
        if (active != work || work.done) return;
        work.done = true; work.result = null;
        main.removeCallbacks(work.timeout); main.removeCallbacks(work.poll);
        state = "CANCELLED".equals(error.code) ? "cancelled" : "error"; lastError = error.code;
        if (work.launched && !work.returned) {
            // Keep the tombstone and UI ownership until Android's real terminal callback.
            main.post(() -> requestFinish(work));
        } else release(work);
        main.post(() -> work.callback.error(error));
    }
    private synchronized void requestFinish(Operation work) {
        if (active != work || !work.launched || work.returned || work.finishRequested) return;
        work.finishRequested = true;
        try { platform.cancel(REQUEST_CODE); } catch (RuntimeException ignored) { }
    }

    /** Android supplies one terminal result per launch; this reserved code is never shared with other actions. */
    public synchronized boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE) return false;
        Operation work = active;
        if (work == null || !work.launched || work.returned) return true;
        work.returned = true;
        if (work.done) { release(work); return true; }
        try {
            if (!work.callback.current()) throw new FactoryException("CANCELLED", "The requesting page no longer owns the recognition result.");
            if (expired(work)) throw timeout();
            if (resultCode != Activity.RESULT_OK) throw resultError(resultCode);
            work.result = results(work.request, data); // Only bounded, copied strings and numbers survive.
            finishReturn(work);
        } catch (FactoryException failure) { cancel(work, failure); }
        catch (RuntimeException malformed) { cancel(work, invalidResult()); }
        return true;
    }
    private synchronized void finishReturn(Operation work) {
        if (active != work || work.done || !work.returned || !foreground || !platform.focused()) return;
        try {
            check(work);
            JSONObject result = work.result;
            if (result == null) throw invalidResult();
            work.result = null; work.done = true;
            main.removeCallbacks(work.timeout); main.removeCallbacks(work.poll);
            state = "recognized"; lastError = "";
            release(work);
            work.callback.complete(result);
        } catch (FactoryException failure) { cancel(work, failure); }
        catch (SecurityException denied) { cancel(work, denied()); }
        catch (RuntimeException failure) { cancel(work, unavailable()); }
    }
    private void check(Operation work) throws FactoryException {
        if (closed || !work.callback.current()) throw new FactoryException("CANCELLED", "The requesting page no longer owns voice recognition.");
        if (expired(work)) throw timeout();
        work.authorization.check();
    }
    private static boolean expired(Operation work) {
        long elapsed = SystemClock.elapsedRealtime() - work.started;
        return elapsed < 0 || elapsed >= work.request.timeoutMs;
    }
    private synchronized void poll(Operation work) {
        if (active != work || work.done) return;
        try {
            if (!work.callback.current()) throw new FactoryException("CANCELLED", "Voice recognition response ownership ended.");
            if (expired(work)) throw timeout();
            // A lease deliberately cannot CHECK while its own external system UI backgrounds the app.
            // Binder revocation still invokes the operation-scoped Handle.cancel supplied to the owner.
            if (foreground) work.authorization.check();
            main.postDelayed(work.poll, AUTHORIZATION_POLL_MS);
        } catch (FactoryException failure) { cancel(work, failure); }
        catch (SecurityException denied) { cancel(work, denied()); }
        catch (RuntimeException unavailable) { cancel(work, unavailable()); }
    }
    private void release(Operation work) {
        if (active == work) active = null;
        boolean handoff = work.handoff, uiHeld = work.uiHeld;
        work.handoff = false; work.uiHeld = false;
        // Revocation may arrive on a Binder worker. Native UI ownership always changes on main.
        Runnable released = () -> {
            if (handoff) work.callback.externalHandoff(false);
            if (uiHeld) work.callback.releaseUi();
        };
        if (Looper.myLooper() == main.getLooper()) released.run(); else main.post(released);
    }
    public synchronized boolean hasPendingActivity() { return active != null && active.launched && !active.returned; }
    public synchronized boolean isClosed() { return closed; }
    public synchronized void onPause() {
        foreground = false;
        if (active != null && (!active.launched || active.returned))
            cancel(active, new FactoryException("CANCELLED", "Voice recognition response ownership ended in background."));
    }
    public synchronized void onResume() { foreground = true; if (active != null) finishReturn(active); }
    public synchronized void onWindowFocusChanged(boolean focused) { if (focused && active != null) finishReturn(active); }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (active != null) cancel(active, new FactoryException("CANCELLED", "Voice recognition lifecycle ended; transmitted audio cannot be recalled."));
    }

    private static JSONObject results(VoiceRecognitionRequest.Recognize request, Intent data) throws FactoryException {
        try {
            Bundle extras = data == null ? null : data.getExtras();
            if (extras == null || extras.hasFileDescriptors()) throw invalidResult();
            // This is an Android result, not a host authorization receipt. OEM handlers may add
            // metadata. Read only the two documented result fields; never forward, stringify,
            // resolve, or act on unrelated extras, routing fields, URIs, ClipData, or grants.
            Object raw = extras.get(RecognizerIntent.EXTRA_RESULTS);
            if (!(raw instanceof ArrayList)) throw invalidResult();
            ArrayList<?> values = (ArrayList<?>) raw;
            if (values.isEmpty()) throw resultError(RecognizerIntent.RESULT_NO_MATCH);
            if (values.size() > request.maxResults || values.size() > VoiceRecognitionRequest.MAX_RESULTS) throw invalidResult();
            JSONArray hypotheses = new JSONArray(); int bytes = 0;
            for (Object value : values) {
                if (!(value instanceof String)) throw invalidResult();
                String text;
                try { text = VoiceRecognitionRequest.text((String) value, VoiceRecognitionRequest.MAX_RESULT_BYTES, true); }
                catch (FactoryException malformed) { throw invalidResult(); }
                if (text.trim().isEmpty()) throw invalidResult();
                bytes += text.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > VoiceRecognitionRequest.MAX_RESULT_BYTES) throw invalidResult();
                hypotheses.put(text);
            }
            if (hypotheses.toString().getBytes(StandardCharsets.UTF_8).length > VoiceRecognitionRequest.MAX_RESULT_BYTES) throw invalidResult();
            Object scores = JSONObject.NULL;
            if (extras.containsKey(RecognizerIntent.EXTRA_CONFIDENCE_SCORES)) {
                Object rawScores = extras.get(RecognizerIntent.EXTRA_CONFIDENCE_SCORES);
                if (!(rawScores instanceof float[])) throw invalidResult();
                float[] confidences = (float[]) rawScores;
                if (confidences.length != values.size()) throw invalidResult();
                JSONArray copied = new JSONArray();
                for (float confidence : confidences) {
                    if (Float.isNaN(confidence) || Float.isInfinite(confidence) ||
                            (confidence != -1f && (confidence < 0f || confidence > 1f))) throw invalidResult();
                    copied.put(confidence == -1f ? JSONObject.NULL : (Object) (double) confidence);
                }
                scores = copied;
            }
            JSONObject result = disclosure(new JSONObject()).put("status", "recognized").put("results", hypotheses)
                    .put("confidenceScores", scores).put("requestedLanguage", request.language)
                    .put("requestedLanguageModel", request.languageModel).put("preferOfflineRequested", request.preferOffline)
                    .put("completionMeans", "recognizer_reported_results");
            if (result.toString().getBytes(StandardCharsets.UTF_8).length > BridgeProtocol.MAX_MESSAGE_BYTES) throw invalidResult();
            return result;
        } catch (JSONException | RuntimeException malformed) { throw invalidResult(); }
    }
    private static JSONObject disclosure(JSONObject value) throws JSONException {
        return value.put("mode", "installed").put("simulated", false).put("engineSelection", "android_system_activity")
                .put("engineVerified", false).put("networkMayBeUsed", true).put("networkUseVerified", false)
                .put("offlineGuaranteed", false).put("accuracyVerified", false).put("audioReturned", false);
    }
    private static FactoryException resultError(int result) {
        switch (result) {
            case Activity.RESULT_CANCELED: return new FactoryException("CANCELLED", "The recognition Activity ended without a result; the reason is not reported.");
            case RecognizerIntent.RESULT_NO_MATCH: return new FactoryException("VOICE_RECOGNITION_NO_MATCH", "The recognizer reported no matching speech.");
            case RecognizerIntent.RESULT_NETWORK_ERROR: return new FactoryException("VOICE_RECOGNITION_NETWORK_ERROR", "The recognizer reported a network error.");
            case RecognizerIntent.RESULT_AUDIO_ERROR: return new FactoryException("VOICE_RECOGNITION_AUDIO_ERROR", "The recognizer reported an audio error.");
            case RecognizerIntent.RESULT_SERVER_ERROR: return new FactoryException("VOICE_RECOGNITION_SERVER_ERROR", "The recognizer reported a server error.");
            default: return new FactoryException("VOICE_RECOGNITION_ERROR", "The recognition Activity did not return a successful result.");
        }
    }
    private static FactoryException unavailable() { return new FactoryException("VOICE_RECOGNITION_UNAVAILABLE", "Android speech recognition Activity is unavailable."); }
    private static FactoryException invalidResult() { return new FactoryException("INVALID_RECOGNITION_RESULT", "The recognition Activity returned an invalid or oversized result."); }
    private static FactoryException denied() { return new FactoryException("PERMISSION_DENIED", "Voice recognition authorization ended; transmitted audio cannot be recalled."); }
    private static FactoryException timeout() { return new FactoryException("TIMEOUT", "Voice recognition exceeded its request deadline; cancellation was requested."); }

    private final class Operation {
        final VoiceRecognitionRequest.Recognize request;
        final NativeOperationAuthorization.Access authorization;
        final Callback callback;
        final long started = SystemClock.elapsedRealtime();
        boolean launched, returned, done, uiHeld, handoff, finishRequested;
        JSONObject result;
        final Runnable timeout = () -> cancel(this, timeout());
        final Runnable poll = () -> poll(this);
        Operation(VoiceRecognitionRequest.Recognize request, NativeOperationAuthorization.Access authorization, Callback callback) {
            this.request = request; this.authorization = authorization; this.callback = callback;
        }
    }
    private static final class AndroidPlatform implements Platform {
        final Activity activity;
        AndroidPlatform(Activity activity) { this.activity = activity; }
        public boolean available() {
            PackageManager manager = activity.getPackageManager();
            List<ResolveInfo> handlers = manager.queryIntentActivities(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), PackageManager.MATCH_DEFAULT_ONLY);
            if (handlers != null) for (ResolveInfo handler : handlers) {
                if (handler.activityInfo != null && handler.activityInfo.enabled && handler.activityInfo.exported &&
                        handler.activityInfo.applicationInfo != null && handler.activityInfo.applicationInfo.enabled &&
                        (handler.activityInfo.permission == null || activity.checkSelfPermission(handler.activityInfo.permission) == PackageManager.PERMISSION_GRANTED)) return true;
            }
            return false;
        }
        public boolean focused() { return activity.hasWindowFocus() && !activity.isFinishing() && !activity.isDestroyed(); }
        public void launch(Intent intent, int requestCode) throws ActivityNotFoundException { activity.startActivityForResult(intent, requestCode); }
        public void cancel(int requestCode) { activity.finishActivity(requestCode); }
    }
}
