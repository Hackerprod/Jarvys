package com.jarvys.factory.runtime;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One bounded foreground fix from Android LocationManager, with no Play Services or background
 * service. The lifecycle owner must cancel on stop and close on destruction/revocation. Every
 * registration and delivery also checks the live policy lease, foreground gate and actual OS grants.
 * No last-known-location call, coordinate retention in status, or guarantee of measured accuracy.
 */
public final class NativeLocation implements AutoCloseable {
    public interface Callback {
        void complete(JSONObject result);
        void error(FactoryException error);
    }
    /** Cancels only the request that returned this handle, never a subsequent request. */
    public interface Handle { void cancel(); }
    /** Prepared ownership exists before start; cancel returns only when release is confirmed. */
    interface Registration { void start(); void cancel(); }
    interface Listener {
        void location(Location location);
        void disabled();
    }
    /** Package-scoped fake seam: tests never register real sensors, obtain GPS or use the network. */
    interface Backend {
        boolean available();
        boolean hasProvider(String provider);
        boolean enabled(String provider);
        // Preparation MUST NOT register with Android. Acquisition happens only in start().
        Registration current(String provider, Listener listener);
        Registration updates(String provider, Listener listener);
    }

    static final long AUTHORIZATION_POLL_MS = 1000;
    static final int MAX_PROCESS_REGISTRATIONS = 8;
    // Strong, process-wide ownership: replacing a page/helper cannot discard an uncertain release.
    private static final List<Admission> ADMISSIONS = new ArrayList<>();
    private final Context context;
    private final String appId;
    private final BooleanSupplier foreground;
    private final Backend backend;
    private final LongSupplier elapsedNanos;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Operation active, mostRecent;
    private final List<Operation> retired = new ArrayList<>();
    private boolean closed;
    private String state = "idle", lastError = "", lastRejectedFixReason = "";

    /** Installed apps only; the preview adapter must return an explicitly simulated result. */
    public NativeLocation(Context context, String appId, BooleanSupplier foreground) {
        this(context, appId, foreground, null, SystemClock::elapsedRealtimeNanos);
    }
    NativeLocation(Context context, String appId, BooleanSupplier foreground, Backend backend, LongSupplier elapsedNanos) {
        if (context == null || appId == null || !appId.equals(context.getPackageName()) || foreground == null || elapsedNanos == null)
            throw new IllegalArgumentException("Location identity must match the installed package and lifecycle.");
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        this.appId = appId; this.foreground = foreground; this.elapsedNanos = elapsedNanos;
        this.backend = backend == null ? new PlatformBackend(this.context, main) : backend;
    }

    /** Read-only readiness and last-operation metadata. Never registers or includes coordinates. */
    public synchronized JSONObject status() {
        boolean coarseDeclared = false, fineDeclared = false, declarationsKnown = true;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(appId, PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null) for (String permission : info.requestedPermissions) {
                if (Manifest.permission.ACCESS_COARSE_LOCATION.equals(permission)) coarseDeclared = true;
                if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permission)) fineDeclared = true;
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { declarationsKnown = false; }
        boolean coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION), fine = granted(Manifest.permission.ACCESS_FINE_LOCATION);
        JSONArray providers = new JSONArray();
        boolean available = false, discoveryKnown = true;
        try {
            available = backend.available();
            for (String name : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                boolean exists = available && backend.hasProvider(name);
                providers.put(new JSONObject().put("provider", name).put("available", exists)
                        .put("enabled", exists && backend.enabled(name)));
            }
        } catch (RuntimeException unavailable) { discoveryKnown = false; }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        try {
            return new JSONObject().put("status", closed ? "closed" : state).put("busy", active != null)
                    .put("available", available).put("declarationsKnown", declarationsKnown)
                    .put("coarseDeclared", coarseDeclared).put("fineDeclared", fineDeclared)
                    .put("coarseGranted", coarse).put("fineGranted", fine)
                    .put("permissionGranularity", fine ? "fine" : coarse ? "coarse" : "none")
                    .put("providerDiscoveryAvailable", discoveryKnown).put("providers", providers)
                    .put("foreground", isForeground()).put("foregroundOnly", true)
                    .put("cleanupPending", cleanupPending()).put("processRegistrations", processRegistrations())
                    .put("maxProcessRegistrations", MAX_PROCESS_REGISTRATIONS)
                    .put("error", lastError).put("lastRejectedFixReason", lastRejectedFixReason);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    public synchronized Handle current(LocationRequest.Current request, NativeOperationAuthorization.Access authorization,
                                       Callback callback) throws FactoryException {
        if (request == null) throw new FactoryException("INVALID_ARGUMENT", "A location request is required.");
        if (authorization == null) throw new SecurityException("Live location authorization required.");
        authorization.check();
        if (callback == null) throw new IllegalArgumentException("Location callback is required.");
        if (closed) throw new FactoryException("CANCELLED", "Location lifecycle has ended.");
        if (!isForeground()) throw foregroundEnded();
        int grants = permissionTier();
        if (grants == 0) throw permissionDenied();
        if (active != null) throw new FactoryException("BUSY", "Another foreground location request is active.");
        if (cleanupPending()) throw cleanupFailed();
        Operation work = new Operation(request, authorization, callback, grants);
        active = mostRecent = work; state = "requesting"; lastError = ""; lastRejectedFixReason = "";
        main.post(() -> guarded(work, () -> begin(work)));
        return () -> cancel(work, "Location request was cancelled by its owner.");
    }

    /** Grant-free cancellation also retries this helper's retained cleanup, without reacquisition. */
    public synchronized JSONObject cancel() {
        boolean cancelled = active != null;
        if (cancelled) fail(active, new FactoryException("CANCELLED", "Location request was cancelled by this application."));
        else retryRetired();
        boolean pending = cleanupPending();
        try { return new JSONObject().put("status", pending ? "cleanup_failed" : "cancelled")
                .put("cancelled", cancelled).put("cleanupPending", pending)
                .put("error", pending ? "CLEANUP_FAILED" : "").put("foregroundOnly", true); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private synchronized void cancel(Operation work, String reason) {
        if (active == work) fail(work, new FactoryException("CANCELLED", reason));
        else if (retired.contains(work)) { cleanup(work, true); pruneRetired(); }
    }
    @Override public synchronized void close() {
        closed = true;
        if (active != null) fail(active, new FactoryException("CANCELLED", "Location lifecycle ended."));
        else retryRetired();
    }

    private void begin(Operation work) throws FactoryException {
        if (!backend.available()) throw unavailable();
        String requested = work.request.provider;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            if (!"auto".equals(requested) && !provider.equals(requested)) continue;
            // Before Android 12 the GPS provider requires fine access; coarse still uses network.
            if (work.permissionTier == 1 && Build.VERSION.SDK_INT < 31 && LocationManager.GPS_PROVIDER.equals(provider)) {
                if (!"auto".equals(requested)) throw permissionDenied();
                continue;
            }
            if (backend.hasProvider(provider) && backend.enabled(provider)) work.sources.add(new Source(provider));
        }
        if (work.sources.isEmpty()) throw new FactoryException("PROVIDER_UNAVAILABLE", "No requested foreground location provider is enabled and accessible.");
        // Freshness begins at registration, not when a potentially delayed main-thread job queued.
        work.startedNanos = elapsedNanos.getAsLong();
        work.deadlineNanos = work.startedNanos + work.request.timeoutMs * 1000000L;
        main.postDelayed(work.timeout, work.request.timeoutMs);
        main.postDelayed(work.poll, AUTHORIZATION_POLL_MS);
        for (Source source : work.sources) {
            if (active != work) return;
            register(work, source, Build.VERSION.SDK_INT >= 30);
        }
    }
    private void register(Operation work, Source source, boolean single) throws FactoryException {
        check(work);
        if (!backend.enabled(source.provider)) throw new FactoryException("PROVIDER_UNAVAILABLE", "The requested location provider was disabled.");
        final long generation = ++source.generation;
        Listener callback = new Listener() {
            @Override public void location(Location location) {
                // Suppress callbacks from retained failed registrations before queuing any work.
                synchronized (NativeLocation.this) {
                    if (active != work || closed || source.generation != generation || source.disabled) return;
                }
                // Copy at the platform callback boundary, before another thread can mutate it.
                Location snapshot = location == null ? null : new Location(location);
                main.post(() -> guarded(work, () -> receive(work, source, generation, single, snapshot)));
            }
            @Override public void disabled() {
                synchronized (NativeLocation.this) {
                    if (active != work || closed || source.generation != generation || source.disabled) return;
                }
                main.post(() -> guarded(work, () -> {
                    if (source.generation != generation) return;
                    if (!source.stop(false)) throw cleanupFailed();
                    source.disabled = true; ensureProvider(work);
                }));
            }
        };
        // Reserve before preparing/acquiring anything, and publish ownership before start().
        check(work);
        Admission admission = acquire();
        source.admission = admission;
        source.cleanupRequested = false;
        try {
            Registration registration;
            try {
                registration = single ? backend.current(source.provider, callback) : backend.updates(source.provider, callback);
                if (registration == null) throw new IllegalStateException("Location preparation failed.");
            } catch (RuntimeException failure) {
                // Backend preparation has no native side effects by contract.
                admission.abandonPreparation();
                throw failure;
            }
            admission.prepared(registration);
            if (active != work || closed || generation != source.generation) return;
            // Last live check is adjacent to native acquisition, after preparation.
            check(work);
            admission.start();
            if (active == work && !closed) check(work);
        } finally {
            // A backend can call close/cancel reentrantly before start returns (or throws).
            if (active != work || closed || generation != source.generation) {
                source.stop(false);
                pruneRetired();
            }
        }
    }
    private void receive(Operation work, Source source, long generation, boolean single, Location fix) throws FactoryException {
        if (source.generation != generation || source.disabled) return;
        String rejection = rejection(work, fix);
        if (rejection.isEmpty()) { succeed(work, source, fix); return; }
        lastRejectedFixReason = rejection;
        if (single) {
            // API30 getCurrentLocation may return a recent cached fix or null. Keep the original
            // deadline and use bounded updates to satisfy the caller's freshness/accuracy choice.
            if (!source.stop(false)) throw cleanupFailed();
            register(work, source, false);
        }
    }
    private String rejection(Operation work, Location fix) {
        if (fix == null) return "no_fix";
        long now = elapsedNanos.getAsLong(), measured = fix.getElapsedRealtimeNanos();
        if (measured <= 0 || now < measured || fix.getTime() <= 0) return "invalid_timestamp";
        if (work.request.maxAgeMs == 0 ? measured < work.startedNanos : now - measured > work.request.maxAgeMs * 1000000L)
            return "stale_fix";
        if (!LocationRequest.finite(fix.getLatitude()) || fix.getLatitude() < -90 || fix.getLatitude() > 90 ||
                !LocationRequest.finite(fix.getLongitude()) || fix.getLongitude() < -180 || fix.getLongitude() > 180 ||
                !fix.hasAccuracy() || !LocationRequest.finite(fix.getAccuracy()) || fix.getAccuracy() < 0)
            return "invalid_fix";
        if (!work.request.allowMock && isMock(fix)) return "mock_disallowed";
        if (work.request.maxAccuracyMeters > 0 && fix.getAccuracy() > work.request.maxAccuracyMeters) return "accuracy_not_met";
        return "";
    }
    private void succeed(Operation work, Source source, Location fix) throws FactoryException {
        if (!cleanup(work, false)) throw cleanupFailed();
        // Remain active/cancellable through cleanup and the actual delivery boundary. Re-evaluate
        // age here too: a queued callback or slow cancellation is not permission to send a stale fix.
        check(work);
        if (active != work || closed) return;
        String rejection = rejection(work, fix);
        if (!rejection.isEmpty()) {
            lastRejectedFixReason = rejection;
            throw new FactoryException("LOCATION_UNAVAILABLE", "The location fix no longer satisfies the request at delivery: " + rejection + ".");
        }
        JSONObject result;
        try {
            long now = elapsedNanos.getAsLong();
            result = new JSONObject().put("status", "located").put("latitude", fix.getLatitude())
                    .put("longitude", fix.getLongitude()).put("accuracyMeters", (double) fix.getAccuracy())
                    .put("provider", source.provider).put("timestamp", fix.getTime())
                    .put("ageMs", (now - fix.getElapsedRealtimeNanos()) / 1000000L)
                    .put("mock", isMock(fix)).put("permissionGranularity", work.permissionTier == 2 ? "fine" : "coarse")
                    .put("acquiredBeforeRequest", fix.getElapsedRealtimeNanos() < work.startedNanos)
                    .put("foregroundOnly", true);
            if (fix.hasAltitude() && LocationRequest.finite(fix.getAltitude())) result.put("altitudeMeters", fix.getAltitude());
            if (fix.hasSpeed() && LocationRequest.finite(fix.getSpeed()) && fix.getSpeed() >= 0) result.put("speedMetersPerSecond", (double) fix.getSpeed());
            if (fix.hasBearing() && LocationRequest.finite(fix.getBearing())) result.put("bearingDegrees", (double) fix.getBearing());
        } catch (JSONException malformed) { throw unavailable(); }
        check(work);
        if (active != work || closed) return;
        active = null; state = "located"; lastError = "";
        work.callback.complete(result);
    }
    private void ensureProvider(Operation work) throws FactoryException {
        boolean anyEnabled = false;
        for (Source source : work.sources) {
            if (source.disabled) continue;
            if (backend.enabled(source.provider)) anyEnabled = true;
            else {
                if (!source.stop(false)) throw cleanupFailed();
                source.disabled = true;
            }
        }
        if (!anyEnabled) throw new FactoryException("PROVIDER_UNAVAILABLE", "All requested location providers were disabled.");
    }
    private interface Action { void run() throws FactoryException; }
    private synchronized void guarded(Operation work, Action action) {
        if (active != work || closed) return;
        try { check(work); action.run(); }
        catch (SecurityException denied) { fail(work, permissionDenied()); }
        catch (FactoryException failure) { fail(work, failure); }
        catch (RuntimeException unavailable) { fail(work, unavailable()); }
    }
    private void check(Operation work) throws FactoryException {
        work.authorization.check();
        if (closed) throw new FactoryException("CANCELLED", "Location lifecycle ended.");
        if (!isForeground()) throw foregroundEnded();
        // A grant change requires a new operation. In particular, never expose a previously fine
        // fix after downgrade to coarse, even if a callback was already queued for delivery.
        if (permissionTier() != work.permissionTier) throw permissionDenied();
        if (work.deadlineNanos != 0 && elapsedNanos.getAsLong() >= work.deadlineNanos) throw timeout();
    }
    private void fail(Operation work, FactoryException failure) {
        if (active != work) return;
        active = null;
        // Publish terminating ownership before native cancellation can reenter current().
        if (!released(work) && !retired.contains(work)) retired.add(work);
        cleanup(work, false);
        pruneRetired();
        recordFailure(work, reportedFailure(work, failure));
        main.post(() -> {
            FactoryException reported;
            synchronized (NativeLocation.this) {
                // Reentrant termination while start/cancel is on-stack settles only after it exits.
                reported = reportedFailure(work, failure);
                recordFailure(work, reported);
            }
            work.callback.error(reported);
        });
    }
    private FactoryException reportedFailure(Operation work, FactoryException failure) {
        for (Source source : work.sources)
            if (source.admission != null && source.admission.failed()) return cleanupFailed();
        return released(work) ? failure : cleanupFailed();
    }
    private void recordFailure(Operation work, FactoryException failure) {
        if (mostRecent != work) return;
        state = "CANCELLED".equals(failure.code) ? "cancelled" : "error";
        lastError = failure.code;
    }
    private boolean cleanup(Operation work, boolean retry) {
        main.removeCallbacks(work.timeout); main.removeCallbacks(work.poll);
        boolean released = true;
        // Do not short-circuit: one failed cancellation must not leak the other provider.
        for (Source source : work.sources) if (!source.stop(retry)) released = false;
        return released;
    }
    private static boolean released(Operation work) {
        for (Source source : work.sources)
            if (source.admission != null && !source.admission.released()) return false;
        return true;
    }
    private void retryRetired() {
        // Snapshot permits a reentrant cancel/close without invalidating this iteration.
        for (Operation work : new ArrayList<>(retired)) cleanup(work, true);
        pruneRetired();
    }
    private void pruneRetired() { retired.removeIf(NativeLocation::released); }
    private boolean cleanupPending() {
        pruneRetired();
        return !retired.isEmpty();
    }
    private boolean isForeground() { try { return foreground.getAsBoolean(); } catch (RuntimeException unavailable) { return false; } }
    private boolean granted(String permission) { return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private int permissionTier() { return granted(Manifest.permission.ACCESS_FINE_LOCATION) ? 2 : granted(Manifest.permission.ACCESS_COARSE_LOCATION) ? 1 : 0; }
    @SuppressWarnings("deprecation")
    private static boolean isMock(Location location) { return Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider(); }
    private static FactoryException cleanupFailed() { return new FactoryException("CLEANUP_FAILED", "Android location cleanup is unconfirmed; ownership is retained and no fix is delivered."); }
    private static Admission acquire() throws FactoryException {
        synchronized (ADMISSIONS) {
            if (ADMISSIONS.size() >= MAX_PROCESS_REGISTRATIONS)
                throw new FactoryException("RESOURCE_LIMIT", "The process location-registration limit is reached; pending cleanup retains its admission.");
            Admission admission = new Admission();
            ADMISSIONS.add(admission);
            return admission;
        }
    }
    static int processRegistrations() { synchronized (ADMISSIONS) { return ADMISSIONS.size(); } }
    private static FactoryException permissionDenied() { return new FactoryException("PERMISSION_DENIED", "Current location authorization or Android location grant is unavailable or changed."); }
    private static FactoryException foregroundEnded() { return new FactoryException("FOREGROUND_REQUIRED", "Location is available only while this application is in the foreground."); }
    private static FactoryException unavailable() { return new FactoryException("LOCATION_UNAVAILABLE", "Android foreground location is unavailable."); }
    private FactoryException timeout() {
        return new FactoryException("TIMEOUT", lastRejectedFixReason.isEmpty() ?
                "No foreground location fix arrived within the requested timeout." :
                "No acceptable foreground location fix arrived within the requested timeout: " + lastRejectedFixReason + ".");
    }

    private static final class Admission {
        private Registration registration;
        private boolean preparing = true, starting, stopping, stopRequested, released, failed;
        synchronized void prepared(Registration registration) {
            this.registration = registration;
            preparing = false;
            if (stopRequested) cancel();
        }
        synchronized void abandonPreparation() {
            if (!preparing || registration != null) throw new IllegalStateException("Already prepared.");
            preparing = false;
            releaseConfirmed(); // No native registration was attempted.
        }
        synchronized void start() {
            if (released || stopRequested) return;
            starting = true;
            try { registration.start(); }
            finally {
                starting = false;
                if (stopRequested) cancel();
            }
        }
        synchronized boolean cancel() {
            stopRequested = true;
            if (released) return true;
            if (preparing || starting || stopping) return false;
            stopping = true;
            try {
                registration.cancel();
                releaseConfirmed();
                return true;
            } catch (RuntimeException uncertain) {
                failed = true;
                return false; // Keep both the original handle and its process admission.
            } finally { stopping = false; }
        }
        synchronized boolean released() { return released; }
        synchronized boolean failed() { return failed; }
        private void releaseConfirmed() {
            released = true;
            registration = null;
            synchronized (ADMISSIONS) { ADMISSIONS.remove(this); }
        }
    }
    private static final class Source {
        final String provider;
        Admission admission;
        long generation;
        boolean disabled, cleanupRequested;
        Source(String provider) { this.provider = provider; }
        boolean stop(boolean retry) {
            generation++;
            boolean attempt = retry || !cleanupRequested;
            cleanupRequested = true;
            return admission == null || (attempt ? admission.cancel() : admission.released());
        }
    }
    private final class Operation {
        final LocationRequest.Current request;
        final NativeOperationAuthorization.Access authorization;
        final Callback callback;
        final int permissionTier;
        long startedNanos, deadlineNanos;
        final List<Source> sources = new ArrayList<>();
        final Runnable timeout = () -> guarded(this, () -> { throw timeout(); });
        final Runnable poll = new Runnable() {
            @Override public void run() { guarded(Operation.this, () -> { ensureProvider(Operation.this); main.postDelayed(this, AUTHORIZATION_POLL_MS); }); }
        };
        Operation(LocationRequest.Current request, NativeOperationAuthorization.Access authorization, Callback callback, int permissionTier) {
            this.request = request; this.authorization = authorization; this.callback = callback;
            this.permissionTier = permissionTier;
        }
    }

    private static final class PlatformBackend implements Backend {
        final LocationManager manager;
        final Handler main;
        PlatformBackend(Context context, Handler main) {
            manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE); this.main = main;
        }
        @Override public boolean available() { return manager != null; }
        @Override public boolean hasProvider(String provider) { return manager != null && manager.getAllProviders().contains(provider); }
        @Override public boolean enabled(String provider) { return manager != null && manager.isProviderEnabled(provider); }
        // Permission is enforced immediately before Registration.start(), not just preparation.
        @Override public Registration current(String provider, Listener listener) {
            if (Build.VERSION.SDK_INT < 30 || manager == null) throw new IllegalStateException();
            CancellationSignal signal = new CancellationSignal();
            Consumer<Location> callback = listener::location;
            return new PlatformRegistration() {
                @android.annotation.TargetApi(30) // This owner is only prepared after the SDK gate.
                @SuppressLint("MissingPermission")
                @Override void startNative() {
                    manager.getCurrentLocation(provider, signal, action -> main.post(action), callback);
                }
                @Override void cancelNative() { signal.cancel(); }
            };
        }
        @Override public Registration updates(String provider, Listener listener) {
            if (manager == null) throw new IllegalStateException();
            LocationListener callback = new LocationListener() {
                @Override public void onLocationChanged(Location location) { listener.location(location); }
                @Override public void onProviderDisabled(String provider) { listener.disabled(); }
                @Override public void onProviderEnabled(String provider) { }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            };
            return new PlatformRegistration() {
                @SuppressLint("MissingPermission")
                @SuppressWarnings("deprecation")
                @Override void startNative() {
                    manager.requestLocationUpdates(provider, 0L, 0f, callback, Looper.getMainLooper());
                }
                @Override void cancelNative() { manager.removeUpdates(callback); }
            };
        }
    }

    /**
     * Android's signal can become canceled before throwing; removeUpdates may forget its local
     * listener before remote removal throws. A subsequent no-op cannot confirm native release.
     * Likewise a partially throwing registration might never publish a cancelable native transport.
     * Keep that uncertain slot for this process rather than pretending a retry repaired it. Backends
     * with genuinely retryable cleanup can implement Registration directly and confirm on retry.
     */
    abstract static class PlatformRegistration implements Registration {
        private boolean attempted, started, cancelled, uncertain;
        @Override public final void start() {
            if (attempted || cancelled) throw new IllegalStateException("Registration already used.");
            attempted = true;
            startNative();
            started = true;
        }
        @Override public final void cancel() {
            if (cancelled || !attempted) { cancelled = true; return; }
            if (uncertain) throw new IllegalStateException("Native location release remains unconfirmed.");
            try { cancelNative(); }
            catch (RuntimeException failure) { uncertain = true; throw failure; }
            if (!started) {
                uncertain = true;
                throw new IllegalStateException("Partial native registration cannot confirm release.");
            }
            cancelled = true;
        }
        abstract void startNative();
        abstract void cancelNative();
    }
}
