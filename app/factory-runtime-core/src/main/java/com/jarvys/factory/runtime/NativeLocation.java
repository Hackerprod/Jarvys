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
    interface Registration { void cancel(); }
    interface Listener {
        void location(Location location);
        void disabled();
    }
    /** Package-scoped fake seam: tests never register real sensors, obtain GPS or use the network. */
    interface Backend {
        boolean available();
        boolean hasProvider(String provider);
        boolean enabled(String provider);
        Registration current(String provider, Listener listener);
        Registration updates(String provider, Listener listener);
    }

    static final long AUTHORIZATION_POLL_MS = 1000;
    private final Context context;
    private final String appId;
    private final BooleanSupplier foreground;
    private final Backend backend;
    private final LongSupplier elapsedNanos;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Operation active;
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
        Operation work = new Operation(request, authorization, callback, grants);
        active = work; state = "requesting"; lastError = ""; lastRejectedFixReason = "";
        main.post(() -> guarded(work, () -> begin(work)));
        return () -> cancel(work, "Location request was cancelled by its owner.");
    }

    /** Grant-free cancellation releases only this helper's work and never prompts for permission. */
    public synchronized JSONObject cancel() {
        boolean cancelled = active != null;
        if (cancelled) fail(active, new FactoryException("CANCELLED", "Location request was cancelled by this application."));
        try { return new JSONObject().put("status", "cancelled").put("cancelled", cancelled).put("foregroundOnly", true); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private synchronized void cancel(Operation work, String reason) {
        if (active == work) fail(work, new FactoryException("CANCELLED", reason));
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (active != null) fail(active, new FactoryException("CANCELLED", "Location lifecycle ended."));
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
                // Copy at the platform callback boundary, before another thread can mutate it.
                Location snapshot = location == null ? null : new Location(location);
                main.post(() -> guarded(work, () -> receive(work, source, generation, single, snapshot)));
            }
            @Override public void disabled() { main.post(() -> guarded(work, () -> {
                if (source.generation != generation) return;
                source.stop(); source.disabled = true; ensureProvider(work);
            })); }
        };
        // Last live check is adjacent to registration, after provider discovery.
        check(work);
        Registration registration = single ? backend.current(source.provider, callback) : backend.updates(source.provider, callback);
        if (registration == null) throw unavailable();
        // An injected/platform registration can be reentrant. Never retain a resource after close.
        if (active != work || closed || generation != source.generation) { release(registration); return; }
        source.registration = registration;
        check(work);
    }
    private void receive(Operation work, Source source, long generation, boolean single, Location fix) throws FactoryException {
        if (source.generation != generation || source.disabled) return;
        String rejection = rejection(work, fix);
        if (rejection.isEmpty()) { succeed(work, source, fix); return; }
        lastRejectedFixReason = rejection;
        if (single) {
            // API30 getCurrentLocation may return a recent cached fix or null. Keep the original
            // deadline and use bounded updates to satisfy the caller's freshness/accuracy choice.
            source.stop(); register(work, source, false);
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
        cleanup(work);
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
            else { source.stop(); source.disabled = true; }
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
        cleanup(work);
        state = "CANCELLED".equals(failure.code) ? "cancelled" : "error"; lastError = failure.code;
        main.post(() -> work.callback.error(failure));
    }
    private void cleanup(Operation work) {
        main.removeCallbacks(work.timeout); main.removeCallbacks(work.poll);
        for (Source source : work.sources) source.stop();
    }
    private boolean isForeground() { try { return foreground.getAsBoolean(); } catch (RuntimeException unavailable) { return false; } }
    private boolean granted(String permission) { return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private int permissionTier() { return granted(Manifest.permission.ACCESS_FINE_LOCATION) ? 2 : granted(Manifest.permission.ACCESS_COARSE_LOCATION) ? 1 : 0; }
    @SuppressWarnings("deprecation")
    private static boolean isMock(Location location) { return Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider(); }
    private static void release(Registration registration) { try { registration.cancel(); } catch (RuntimeException ignored) { } }
    private static FactoryException permissionDenied() { return new FactoryException("PERMISSION_DENIED", "Current location authorization or Android location grant is unavailable or changed."); }
    private static FactoryException foregroundEnded() { return new FactoryException("FOREGROUND_REQUIRED", "Location is available only while this application is in the foreground."); }
    private static FactoryException unavailable() { return new FactoryException("LOCATION_UNAVAILABLE", "Android foreground location is unavailable."); }
    private FactoryException timeout() {
        return new FactoryException("TIMEOUT", lastRejectedFixReason.isEmpty() ?
                "No foreground location fix arrived within the requested timeout." :
                "No acceptable foreground location fix arrived within the requested timeout: " + lastRejectedFixReason + ".");
    }

    private static final class Source {
        final String provider;
        Registration registration;
        long generation;
        boolean disabled;
        Source(String provider) { this.provider = provider; }
        void stop() {
            generation++;
            Registration previous = registration; registration = null;
            if (previous != null) release(previous);
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
        // Permission is enforced immediately before these calls by the lifecycle-owned helper.
        @SuppressLint("MissingPermission")
        @Override public Registration current(String provider, Listener listener) {
            if (Build.VERSION.SDK_INT < 30 || manager == null) throw new IllegalStateException();
            CancellationSignal signal = new CancellationSignal();
            try { manager.getCurrentLocation(provider, signal, action -> main.post(action), listener::location); }
            catch (RuntimeException failure) { signal.cancel(); throw failure; }
            return signal::cancel;
        }
        @SuppressLint("MissingPermission")
        @SuppressWarnings("deprecation")
        @Override public Registration updates(String provider, Listener listener) {
            if (manager == null) throw new IllegalStateException();
            LocationListener callback = new LocationListener() {
                @Override public void onLocationChanged(Location location) { listener.location(location); }
                @Override public void onProviderDisabled(String provider) { listener.disabled(); }
                @Override public void onProviderEnabled(String provider) { }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            };
            try { manager.requestLocationUpdates(provider, 0L, 0f, callback, Looper.getMainLooper()); }
            catch (RuntimeException failure) {
                try { manager.removeUpdates(callback); } catch (RuntimeException ignored) { }
                throw failure;
            }
            return () -> manager.removeUpdates(callback);
        }
    }
}
