package com.jarvys.factory.runtime;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One bounded foreground reading from TYPE_STEP_COUNTER. Never estimates steps, resets the device
 * counter, caches readings, calculates daily totals, or maintains a background listener. Android's
 * counter covers steps since reboot only while that sensor is activated; its event timestamp is
 * the last step's time, which can legitimately precede this request. The lifecycle owner must
 * cancel on pause/page replacement/revocation and close on destruction. Polling is a fallback.
 * Eight process-wide listener admissions bound the working set even across helper replacement;
 * a slot is retained until its exact listener's unregister is confirmed, including partial starts.
 */
public final class NativeSteps implements AutoCloseable {
    public interface Callback { void complete(JSONObject result); void error(FactoryException error); }
    /** Owns only the operation that returned this handle. */
    public interface Handle { void cancel(); }
    interface Registration {
        boolean register(Listener listener);
        /** Return normally only after the exact listener's unregister is confirmed. */
        void cancel();
    }
    interface Listener { void sample(long timestampNanos, float steps); }
    /** Tests inject this seam; no live hardware, accelerometer, or permission dialog is used. */
    interface Backend {
        boolean available();
        /** Allocate a cleanup handle without registering; the helper owns it before register(). */
        Registration create();
    }

    public static final int MAX_PROCESS_LISTENERS = 8;
    private static final Semaphore PROCESS_ADMISSION = new Semaphore(MAX_PROCESS_LISTENERS);
    static final long AUTHORIZATION_POLL_MS = 1000;
    private static final double MAX_SAFE_INTEGER = 9007199254740991d;
    private static final String SCOPE = "since_boot_while_sensor_activated";
    private final Context context;
    private final String appId;
    private final BooleanSupplier foreground;
    private final LongSupplier elapsedNanos;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Backend backend;
    private final Semaphore admissions;
    private Operation active;
    private Operation pendingCleanup;
    private boolean closed, cleanupFailed;
    private String state = "idle", lastError = "", lastRejectedSampleReason = "";

    /** Installed apps only; preview adapters must label any preview as simulated. */
    public NativeSteps(Context context, String appId, BooleanSupplier foreground) {
        this(context, appId, foreground, null, SystemClock::elapsedRealtimeNanos);
    }
    NativeSteps(Context context, String appId, BooleanSupplier foreground, Backend backend, LongSupplier elapsedNanos) {
        this(context, appId, foreground, backend, elapsedNanos, PROCESS_ADMISSION);
    }
    /** An injected shared ledger isolates synthetic failure tests from the production process cap. */
    NativeSteps(Context context, String appId, BooleanSupplier foreground, Backend backend, LongSupplier elapsedNanos, Semaphore admissions) {
        if (context == null || appId == null || !appId.equals(context.getPackageName()) || foreground == null || elapsedNanos == null)
            throw new IllegalArgumentException("Step-counter identity must match the installed package and lifecycle.");
        if (admissions == null) throw new IllegalArgumentException("A step-counter admission ledger is required.");
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        this.appId = appId; this.foreground = foreground; this.elapsedNanos = elapsedNanos;
        this.admissions = admissions;
        this.backend = backend == null ? new PlatformBackend(this.context, main) : backend;
    }

    /** Read-only readiness; availability is discovery, never a successful measurement probe. */
    public synchronized JSONObject status() {
        boolean declared = false, declarationsKnown = true, available = false, discoveryKnown = true;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(appId, PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null) for (String permission : info.requestedPermissions)
                if (Manifest.permission.ACTIVITY_RECOGNITION.equals(permission)) declared = true;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { declarationsKnown = false; }
        try { available = backend.available(); } catch (RuntimeException unavailable) { discoveryKnown = false; }
        try {
            return metadata().put("status", closed ? "closed" : state).put("busy", active != null)
                    .put("available", available).put("sensorDiscoveryAvailable", discoveryKnown)
                    .put("declarationsKnown", declarationsKnown).put("activityRecognitionDeclared", declared)
                    .put("activityRecognitionGranted", granted()).put("permissionRequired", permissionRequired())
                    .put("foreground", isForeground()).put("cleanupFailed", cleanupFailed)
                    .put("error", lastError).put("lastRejectedSampleReason", lastRejectedSampleReason);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    public synchronized Handle current(StepsRequest.Current request, NativeOperationAuthorization.Access authorization,
                                       Callback callback) throws FactoryException {
        if (request == null) throw new FactoryException("INVALID_ARGUMENT", "A step-counter request is required.");
        if (authorization == null) throw new SecurityException("Live step-counter authorization required.");
        authorization.check();
        if (callback == null) throw new IllegalArgumentException("Step-counter callback is required.");
        if (closed) throw cancelled();
        if (cleanupFailed) throw cleanupError();
        if (!isForeground()) throw foregroundEnded();
        if (!permissionAvailable()) throw permissionDenied();
        if (active != null) throw new FactoryException("BUSY", "Another foreground step-counter request is active.");
        Operation work = new Operation(request, authorization, callback);
        active = work; state = "requesting"; lastError = ""; lastRejectedSampleReason = "";
        main.post(work.begin);
        return () -> cancel(work);
    }

    /** Never obtains fresh authority or prompts; permission loss must not prevent cleanup. */
    public synchronized JSONObject cancel() {
        boolean cancelled = active != null;
        if (cancelled) fail(active, cancelled());
        try { return new JSONObject().put("status", cleanupFailed ? "cleanup_failed" : "cancelled")
                .put("cancelled", cancelled).put("cleanupFailed", cleanupFailed).put("foregroundOnly", true); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private synchronized void cancel(Operation work) { if (active == work) fail(work, cancelled()); }
    @Override public synchronized void close() {
        closed = true;
        if (active != null) fail(active, cancelled());
        // Retain a failed unregister for a best-effort retry, never re-enable sampling afterward.
        if (pendingCleanup != null) releasePending();
    }

    private void begin(Operation work) throws FactoryException {
        if (!backend.available()) throw unavailable();
        // The timeout starts at registration, not while an Android main-thread job is queued.
        work.startedNanos = elapsedNanos.getAsLong();
        if (work.startedNanos < 0 || work.startedNanos > Long.MAX_VALUE - work.request.timeoutMs * 1000000L) throw unavailable();
        work.deadlineNanos = work.startedNanos + work.request.timeoutMs * 1000000L;
        main.postDelayed(work.timeout, work.request.timeoutMs);
        main.postDelayed(work.poll, AUTHORIZATION_POLL_MS);
        check(work);
        if (!admissions.tryAcquire()) throw new FactoryException("BUSY", "The process step-counter listener working set is full.");
        work.admitted = true;
        // A two-phase handle guarantees ownership even when register() throws after partially
        // attaching, or synchronously reenters cancellation/page replacement.
        work.registration = backend.create();
        if (work.registration == null) throw unavailable();
        check(work); // Adjacent to register, after discovery may have changed the live authority.
        boolean registered;
        work.registering = true;
        try { registered = work.registration.register((timestamp, count) -> enqueue(work, timestamp, count)); }
        finally {
            work.registering = false;
            if (work.pendingFailure != null) {
                // Cancellation inside register is deferred until unregister can confirm release.
                if (!cleanup(work)) work.pendingFailure = cleanupError();
                finishFailure(work, work.pendingFailure);
            }
        }
        if (active != work || work.terminal) return;
        if (!registered) throw unavailable();
        check(work);
    }
    private synchronized void enqueue(Operation work, long timestamp, float count) {
        if (active != work || closed || work.terminal || work.finishing || work.sampleDelivery != null) return;
        String rejection = rejection(timestamp, count);
        if (!rejection.isEmpty()) { lastRejectedSampleReason = rejection; return; }
        // At most one copied scalar event can wait for delivery. No unbounded callback/sample queue.
        work.sampleDelivery = () -> {
            synchronized (NativeSteps.this) {
                work.sampleDelivery = null;
                guarded(work, () -> receive(work, timestamp, count));
            }
        };
        main.post(work.sampleDelivery);
    }
    private void receive(Operation work, long timestamp, float count) throws FactoryException {
        String rejection = rejection(timestamp, count);
        if (!rejection.isEmpty()) { lastRejectedSampleReason = rejection; return; }
        succeed(work, timestamp, count);
    }
    private String rejection(long timestamp, float count) {
        if (Float.isNaN(count) || Float.isInfinite(count) || count < 0 || (double) count > MAX_SAFE_INTEGER || count != Math.rint(count))
            return "invalid_counter";
        if (timestamp <= 0 || timestamp > elapsedNanos.getAsLong()) return "invalid_timestamp";
        return "";
    }
    private void succeed(Operation work, long timestamp, float count) {
        work.finishing = true;
        if (!cleanup(work)) work.pendingFailure = cleanupError();
        try {
            if (work.pendingFailure != null) throw work.pendingFailure;
            check(work);
            String rejection = rejection(timestamp, count);
            if (!rejection.isEmpty()) { lastRejectedSampleReason = rejection; throw unavailable(); }
            JSONObject result = metadata().put("status", "measured").put("stepsSinceBoot", (long) count)
                    // A decimal string preserves nanoseconds across the JavaScript safe-integer limit.
                    .put("sensorTimestampNanos", Long.toString(timestamp))
                    .put("sensorAgeMs", (elapsedNanos.getAsLong() - timestamp) / 1000000L)
                    .put("eventBeforeRequest", timestamp < work.startedNanos);
            check(work); // Actual OS grant, live lease and current foreground at delivery.
            if (work.pendingFailure != null) throw work.pendingFailure;
            work.terminal = true; active = null; state = "measured"; lastError = "";
            work.callback.complete(result);
        } catch (SecurityException denied) { finishFailure(work, permissionDenied()); }
        catch (FactoryException failure) { finishFailure(work, failure); }
        catch (JSONException | RuntimeException unavailable) { finishFailure(work, unavailable()); }
    }
    private interface Action { void run() throws FactoryException; }
    private synchronized void guarded(Operation work, Action action) {
        if (active != work || work.terminal || closed) return;
        try { check(work); action.run(); }
        catch (SecurityException denied) { fail(work, permissionDenied()); }
        catch (FactoryException failure) { fail(work, failure); }
        catch (RuntimeException unavailable) { fail(work, unavailable()); }
    }
    private void check(Operation work) throws FactoryException {
        work.authorization.check();
        if (active != work || work.terminal || closed) throw cancelled();
        if (!isForeground()) throw foregroundEnded();
        if (!permissionAvailable()) throw permissionDenied();
        if (work.deadlineNanos != 0) {
            long now = elapsedNanos.getAsLong();
            if (now < work.startedNanos) throw unavailable();
            if (now >= work.deadlineNanos) throw timeout();
        }
    }
    private void fail(Operation work, FactoryException failure) {
        if (active != work || work.terminal) return;
        if (work.finishing) { work.pendingFailure = failure; return; }
        work.finishing = true;
        work.pendingFailure = failure;
        if (work.registering) return;
        if (!cleanup(work)) work.pendingFailure = cleanupError();
        finishFailure(work, work.pendingFailure);
    }
    private void finishFailure(Operation work, FactoryException failure) {
        if (active != work || work.terminal) return;
        work.terminal = true; active = null;
        state = "CANCELLED".equals(failure.code) ? "cancelled" : "error"; lastError = failure.code;
        main.post(() -> work.callback.error(failure));
    }
    private boolean cleanup(Operation work) {
        main.removeCallbacks(work.begin); main.removeCallbacks(work.timeout); main.removeCallbacks(work.poll);
        if (work.sampleDelivery != null) { main.removeCallbacks(work.sampleDelivery); work.sampleDelivery = null; }
        release(work);
        return !cleanupFailed;
    }
    private void release(Operation work) {
        if (!work.admitted || work.registering || work.cleaning) return;
        work.cleaning = true;
        try {
            if (work.registration != null) work.registration.cancel();
        } catch (RuntimeException failure) {
            pendingCleanup = work; cleanupFailed = true; lastError = "CLEANUP_FAILED";
            return; // Retain the process slot across helper destruction/replacement.
        } finally { work.cleaning = false; }
        work.registration = null;
        if (pendingCleanup == work) pendingCleanup = null;
        work.admitted = false;
        admissions.release(); // Exactly once, only after confirmed unregister or no registration.
    }
    private void releasePending() {
        Operation work = pendingCleanup; pendingCleanup = null; release(work);
    }
    private boolean isForeground() { try { return foreground.getAsBoolean(); } catch (RuntimeException unavailable) { return false; } }
    private boolean granted() {
        try { return context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED; }
        catch (RuntimeException unavailable) { return false; }
    }
    private static boolean permissionRequired() { return Build.VERSION.SDK_INT >= 29; }
    private boolean permissionAvailable() { return !permissionRequired() || granted(); }
    private static JSONObject metadata() throws JSONException {
        return new JSONObject().put("sensorType", "step_counter").put("counterScope", SCOPE)
                .put("resetsOn", "system_reboot").put("foregroundOnly", true).put("continuousTracking", false);
    }
    private static FactoryException cancelled() { return new FactoryException("CANCELLED", "Step-counter request or lifecycle ended."); }
    private static FactoryException permissionDenied() { return new FactoryException("PERMISSION_DENIED", "Current step-counter authorization or Android activity-recognition grant is unavailable."); }
    private static FactoryException foregroundEnded() { return new FactoryException("FOREGROUND_REQUIRED", "Steps are available only while this application is in the foreground."); }
    private static FactoryException unavailable() { return new FactoryException("STEPS_UNAVAILABLE", "Android step-counter measurement is unavailable."); }
    private static FactoryException cleanupError() { return new FactoryException("CLEANUP_FAILED", "Step-counter cleanup is unconfirmed; further measurements are disabled."); }
    private static FactoryException timeout() { return new FactoryException("TIMEOUT", "No valid step-counter event arrived within the requested foreground timeout."); }

    private final class Operation {
        final StepsRequest.Current request;
        final NativeOperationAuthorization.Access authorization;
        final Callback callback;
        long startedNanos, deadlineNanos;
        boolean finishing, terminal, admitted, registering, cleaning;
        FactoryException pendingFailure;
        Registration registration;
        Runnable sampleDelivery;
        final Runnable begin = () -> guarded(this, () -> begin(this));
        final Runnable timeout = () -> guarded(this, () -> { throw timeout(); });
        final Runnable poll = new Runnable() {
            @Override public void run() { guarded(Operation.this, () -> {
                if (!backend.available()) throw unavailable();
                main.postDelayed(this, AUTHORIZATION_POLL_MS);
            }); }
        };
        Operation(StepsRequest.Current request, NativeOperationAuthorization.Access authorization, Callback callback) {
            this.request = request; this.authorization = authorization; this.callback = callback;
        }
    }
    private static final class PlatformBackend implements Backend {
        private final SensorManager manager;
        private final Handler main;
        PlatformBackend(Context context, Handler main) {
            manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE); this.main = main;
        }
        @Override public boolean available() { return manager != null && manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null; }
        @SuppressLint("MissingPermission")
        @Override public Registration create() {
            Sensor sensor = manager == null ? null : manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            return new Registration() {
                private SensorEventListener callback;
                @Override public boolean register(Listener listener) {
                    if (sensor == null || manager == null) return false;
                    callback = new SensorEventListener() {
                        @Override public void onSensorChanged(SensorEvent event) {
                            if (event != null && event.sensor == sensor)
                                listener.sample(event.timestamp, event.values == null || event.values.length == 0 ? Float.NaN : event.values[0]);
                        }
                        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
                    };
                    return manager.registerListener(callback, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0, main);
                }
                @Override public void cancel() {
                    if (manager != null && callback != null) manager.unregisterListener(callback, sensor);
                    callback = null; // Keep the exact callback for a retry when unregister throws.
                }
            };
        }
    }
}
