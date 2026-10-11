package com.jarvys.factory.runtime;

import android.Manifest;
import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** All fixes and providers are synthetic. No GPS, real registration, network or permission dialog. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 30, 35}, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeLocationTest {
    private static final NativeOperationAuthorization.Access ALLOW = () -> { };
    private final AtomicBoolean foreground = new AtomicBoolean(true), allowed = new AtomicBoolean(true);
    private final AtomicLong now = new AtomicLong(60000000000L);
    private final FakeBackend backend = new FakeBackend();
    private Application context;
    private NativeLocation location;
    private Result result;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        PackageInfo own = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
        own.requestedPermissions = new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION};
        Shadows.shadowOf(context.getPackageManager()).installPackage(own);
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION);
        location = new NativeLocation(context, context.getPackageName(), foreground::get, backend, now::get);
        result = new Result();
    }
    @After public void cleanup() { location.close(); idle(); }

    @Test public void identityAndStatusNeverRegisterOrDiscloseCoordinates() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new NativeLocation(context, "another.package", foreground::get));
        assertThrows(IllegalArgumentException.class, () -> new NativeLocation(null, "another.package", foreground::get));
        assertThrows(IllegalArgumentException.class, () -> new NativeLocation(context, context.getPackageName(), null));
        JSONObject status = location.status();
        assertEquals("idle", status.getString("status")); assertEquals("fine", status.getString("permissionGranularity"));
        assertTrue(status.getBoolean("coarseDeclared")); assertTrue(status.getBoolean("fineDeclared"));
        assertTrue(status.getBoolean("coarseGranted")); assertTrue(status.getBoolean("fineGranted"));
        assertTrue(status.getBoolean("foregroundOnly")); assertEquals(2, status.getJSONArray("providers").length());
        assertFalse(status.has("latitude")); assertFalse(status.has("longitude")); assertTrue(backend.requests.isEmpty());
        assertFalse(location.cancel().getBoolean("cancelled")); assertTrue(backend.requests.isEmpty());
    }

    @Test public void declaredAndGrantedPermissionsAreDifferentStatusFacts() throws Exception {
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION);
        JSONObject status = location.status();
        assertTrue(status.getBoolean("coarseDeclared")); assertTrue(status.getBoolean("fineDeclared"));
        assertFalse(status.getBoolean("coarseGranted")); assertFalse(status.getBoolean("fineGranted"));
        assertEquals("none", status.getString("permissionGranularity")); assertTrue(backend.requests.isEmpty());
    }

    @Test public void deniedOrMissingPolicyNeverRegisters() throws Exception {
        allowed.set(false);
        assertThrows(SecurityException.class, () -> location.current(request(), access(), result));
        assertThrows(SecurityException.class, () -> location.current(request(), null, result));
        idle(); assertTrue(backend.requests.isEmpty()); assertEquals(0, result.calls);
    }

    @Test public void policyGrantCannotSubstituteForActualAndroidPermission() throws Exception {
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION);
        FactoryException denied = assertThrows(FactoryException.class, () -> location.current(request(), ALLOW, result));
        assertEquals("PERMISSION_DENIED", denied.code); idle(); assertTrue(backend.requests.isEmpty());
    }

    @Test public void backgroundOrClosedLifecycleCannotStart() throws Exception {
        foreground.set(false);
        assertEquals("FOREGROUND_REQUIRED", assertThrows(FactoryException.class, () -> location.current(request(), ALLOW, result)).code);
        foreground.set(true); location.close(); location.close();
        assertEquals("CANCELLED", assertThrows(FactoryException.class, () -> location.current(request(), ALLOW, result)).code);
        assertEquals("closed", location.status().getString("status")); assertTrue(backend.requests.isEmpty());
    }

    @Test public void queuedRevocationPreventsAnyRegistration() throws Exception {
        location.current(request(), access(), result); allowed.set(false); idle();
        assertTrue(backend.requests.isEmpty()); assertError("PERMISSION_DENIED");
    }

    @Test public void grantRevocationBetweenAcceptanceAndRegistrationPreventsAcquisition() throws Exception {
        location.current(request(), ALLOW, result);
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        idle(); assertTrue(backend.requests.isEmpty()); assertError("PERMISSION_DENIED");
    }

    @Test public void authorizationIsRecheckedAfterDiscoveryImmediatelyBeforeRegistration() throws Exception {
        backend.onDiscovery = () -> allowed.set(false);
        location.current(request(), access(), result); idle();
        assertTrue(backend.requests.isEmpty()); assertError("PERMISSION_DENIED");
    }

    @Test public void authorizationLossInsideRegistrationReleasesReturnedResource() throws Exception {
        backend.onRegister = () -> allowed.set(false);
        location.current(request(), access(), result); idle();
        assertEquals(1, backend.requests.size()); assertEquals(1, backend.latest().cancellations); assertError("PERMISSION_DENIED");
    }

    @Test public void apiBranchAndMeasuredResultAreHonestAndResourcesEndOnSuccess() throws Exception {
        location.current(request(), ALLOW, result); idle();
        Request nativeRequest = backend.latest(); assertEquals(Build.VERSION.SDK_INT >= 30, nativeRequest.single);
        assertEquals("requesting", location.status().getString("status")); assertEquals(0, result.calls);
        Location fix = fix(0, 75); fix.setAltitude(120.5); fix.setSpeed(2.5f); fix.setBearing(100f);
        nativeRequest.listener.location(fix); idle();
        assertEquals(1, result.calls); assertNull(result.error); assertEquals("located", result.value.getString("status"));
        assertEquals(12.25, result.value.getDouble("latitude"), 0); assertEquals(-45.5, result.value.getDouble("longitude"), 0);
        assertEquals(75, result.value.getDouble("accuracyMeters"), 0); assertEquals("fine", result.value.getString("permissionGranularity"));
        assertEquals("network", result.value.getString("provider")); assertEquals(0, result.value.getLong("ageMs"));
        assertFalse(result.value.getBoolean("acquiredBeforeRequest")); assertFalse(result.value.getBoolean("mock"));
        assertEquals(120.5, result.value.getDouble("altitudeMeters"), 0); assertEquals(2.5, result.value.getDouble("speedMetersPerSecond"), 0);
        assertEquals(1, nativeRequest.cancellations); assertFalse(location.status().has("latitude"));
    }

    @Test public void coarseOnlyGrantRemainsUsableWithoutClaimingPreciseAccuracy() throws Exception {
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        location.current(request(), ALLOW, result); idle(); backend.latest().listener.location(fix(0, 3000)); idle();
        assertEquals("coarse", result.value.getString("permissionGranularity"));
        assertEquals(3000, result.value.getDouble("accuracyMeters"), 0);
        assertFalse(result.value.has("precise")); assertFalse(result.value.has("accuracyGuaranteed"));
    }

    @Test public void autoRacesAccessibleProvidersAndReleasesAllAfterFirstAcceptableFix() throws Exception {
        location.current(LocationRequest.parseCurrent("{}"), ALLOW, result); idle();
        assertEquals(2, backend.requests.size());
        backend.requests.get(1).listener.location(fix(0, 120));
        backend.requests.get(0).listener.location(fix(0, 5)); idle();
        assertEquals(1, result.calls); assertEquals("network", result.value.getString("provider"));
        for (Request request : backend.requests) assertEquals(1, request.cancellations);
    }

    @Test public void coarseAutoUsesOnlyAccessibleProvidersForApiLevel() throws Exception {
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        location.current(LocationRequest.parseCurrent("{}"), ALLOW, result); idle();
        assertEquals(Build.VERSION.SDK_INT >= 31 ? 2 : 1, backend.requests.size());
        if (Build.VERSION.SDK_INT < 31) assertEquals("network", backend.latest().provider);
    }

    @Test public void pre31ExplicitGpsNeedsFineRatherThanMisrepresentingCoarse() throws Exception {
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        location.current(LocationRequest.parseCurrent("{\"provider\":\"gps\"}"), ALLOW, result); idle();
        if (Build.VERSION.SDK_INT < 31) { assertError("PERMISSION_DENIED"); assertTrue(backend.requests.isEmpty()); }
        else { assertEquals(1, backend.requests.size()); assertEquals("gps", backend.latest().provider); }
    }

    @Test public void freshDefaultRejectsCachedFixAndFallsBackOnlyToBoundedUpdates() throws Exception {
        location.current(request(), ALLOW, result); idle(); Request first = backend.latest();
        first.listener.location(fix(1000, 20)); idle();
        assertEquals(0, result.calls); assertEquals("stale_fix", location.status().getString("lastRejectedFixReason"));
        if (Build.VERSION.SDK_INT >= 30) { assertEquals(2, backend.requests.size()); assertEquals(1, first.cancellations); assertFalse(backend.latest().single); }
        else assertEquals(1, backend.requests.size());
        backend.latest().listener.location(fix(0, 20)); idle(); assertEquals(1, result.calls); assertNotNull(result.value);
    }

    @Test public void freshnessStartsWhenRegistrationRunsRatherThanWhenItWasQueued() throws Exception {
        Location queuedFix = fix(0, 10);
        location.current(request(), ALLOW, result); now.addAndGet(5000000000L); idle();
        backend.latest().listener.location(queuedFix); idle();
        assertEquals(0, result.calls); assertEquals("stale_fix", location.status().getString("lastRejectedFixReason"));
    }

    @Test public void explicitlyPermittedRecentFixReportsRealAgeAndPreRequestAcquisition() throws Exception {
        location.current(LocationRequest.parseCurrent("{\"provider\":\"network\",\"maxAgeMs\":5000}"), ALLOW, result); idle();
        backend.latest().listener.location(fix(4999, 50)); idle();
        assertEquals(4999, result.value.getLong("ageMs")); assertTrue(result.value.getBoolean("acquiredBeforeRequest"));
    }

    @Test public void overAgeInaccurateMissingAccuracyAndFutureTimestampDoNotSucceed() throws Exception {
        location.current(LocationRequest.parseCurrent("{\"provider\":\"network\",\"maxAgeMs\":5000,\"maxAccuracyMeters\":25}"), ALLOW, result); idle();
        backend.latest().listener.location(fix(5001, 10)); idle(); assertEquals("stale_fix", location.status().getString("lastRejectedFixReason"));
        backend.latest().listener.location(fix(0, 26)); idle(); assertEquals("accuracy_not_met", location.status().getString("lastRejectedFixReason"));
        Location missing = fix(0, 10); missing.removeAccuracy(); backend.latest().listener.location(missing); idle();
        assertEquals("invalid_fix", location.status().getString("lastRejectedFixReason"));
        backend.latest().listener.location(fix(-1, 10)); idle(); assertEquals("invalid_timestamp", location.status().getString("lastRejectedFixReason"));
        assertEquals(0, result.calls); backend.latest().listener.location(fix(0, 25)); idle(); assertEquals(1, result.calls);
    }

    @Test public void missingFixWaitsForActualResultAndNeverCreatesZeroCoordinates() throws Exception {
        location.current(request(), ALLOW, result); idle(); backend.latest().listener.location(null); idle();
        assertEquals(0, result.calls); assertEquals("no_fix", location.status().getString("lastRejectedFixReason"));
        backend.latest().listener.location(fix(0, 10)); idle(); assertEquals(12.25, result.value.getDouble("latitude"), 0);
    }

    @Test public void mockLocationsAreTransparentByDefaultAndCanBeExplicitlyRejected() throws Exception {
        location.current(request(), ALLOW, result); idle(); Location mock = fix(0, 10); markMock(mock);
        backend.latest().listener.location(mock); idle(); assertTrue(result.value.getBoolean("mock"));
        result = new Result();
        location.current(LocationRequest.parseCurrent("{\"provider\":\"network\",\"allowMock\":false,\"timeoutMs\":1000}"), ALLOW, result); idle();
        backend.latest().listener.location(mock); idle(); assertEquals(0, result.calls);
        assertEquals("mock_disallowed", location.status().getString("lastRejectedFixReason"));
        advance(1000); assertError("TIMEOUT"); assertTrue(result.error.getMessage().contains("mock_disallowed"));
    }

    @Test public void deadlineDoesNotResetForRejectedFixOrModernFallback() throws Exception {
        location.current(LocationRequest.parseCurrent("{\"provider\":\"network\",\"timeoutMs\":2000}"), ALLOW, result); idle();
        advance(1500); backend.latest().listener.location(fix(5000, 10)); idle(); advance(500);
        assertError("TIMEOUT"); assertNull(result.value);
        for (Request request : backend.requests) assertEquals(1, request.cancellations);
    }

    @Test public void elapsedDeadlineRejectsQueuedSuccessEvenBeforeTimerRunnableExecutes() throws Exception {
        location.current(LocationRequest.parseCurrent("{\"provider\":\"network\",\"timeoutMs\":1000}"), ALLOW, result); idle();
        backend.latest().listener.location(fix(0, 10)); now.addAndGet(1000000000L); idle();
        assertError("TIMEOUT"); assertNull(result.value); assertEquals(1, backend.latest().cancellations);
    }

    @Test public void absentServiceAndDisabledProvidersFailWithoutRegistration() throws Exception {
        backend.available = false; location.current(request(), ALLOW, result); idle(); assertError("LOCATION_UNAVAILABLE");
        backend.available = true; backend.networkEnabled = false; result = new Result();
        location.current(request(), ALLOW, result); idle(); assertError("PROVIDER_UNAVAILABLE"); assertTrue(backend.requests.isEmpty());
    }

    @Test public void providerDisablingCancelsUpdatesOrIsObservedByPoll() throws Exception {
        location.current(request(), ALLOW, result); idle(); Request request = backend.latest();
        backend.networkEnabled = false; request.listener.disabled(); idle();
        assertError("PROVIDER_UNAVAILABLE"); assertEquals(1, request.cancellations);
    }

    @Test public void providerPollCatchesDisabledSingleRequestWithoutCallback() throws Exception {
        location.current(request(), ALLOW, result); idle(); backend.networkEnabled = false; advance(1000);
        assertError("PROVIDER_UNAVAILABLE"); assertEquals(1, backend.latest().cancellations);
    }

    @Test public void oneDisabledAutoProviderDoesNotCancelTheOtherProvider() throws Exception {
        location.current(LocationRequest.parseCurrent("{}"), ALLOW, result); idle();
        backend.gpsEnabled = false; backend.requests.get(0).listener.disabled(); idle(); assertEquals(0, result.calls);
        backend.requests.get(1).listener.location(fix(0, 30)); idle(); assertEquals(1, result.calls); assertNotNull(result.value);
    }

    @Test public void permissionRevocationAndForegroundLossAreObservedWithoutAnyFix() throws Exception {
        location.current(request(), access(), result); idle(); allowed.set(false); advance(1000);
        assertError("PERMISSION_DENIED"); assertEquals(1, backend.latest().cancellations);
        allowed.set(true); result = new Result(); location.current(request(), access(), result); idle();
        foreground.set(false); advance(1000); assertError("FOREGROUND_REQUIRED"); assertEquals(1, backend.latest().cancellations);
    }

    @Test public void fineToCoarseDowngradeSuppressesAlreadyQueuedFineCoordinates() throws Exception {
        location.current(request(), ALLOW, result); idle(); backend.latest().listener.location(fix(0, 3));
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION); idle();
        assertError("PERMISSION_DENIED"); assertNull(result.value); assertEquals(1, backend.latest().cancellations);
    }

    @Test public void queuedSuccessRemainsCancellableBeforeItsDelivery() throws Exception {
        NativeLocation.Handle handle = location.current(request(), ALLOW, result); idle();
        backend.latest().listener.location(fix(0, 3)); handle.cancel(); idle();
        assertError("CANCELLED"); assertNull(result.value); assertEquals(1, backend.latest().cancellations);
    }

    @Test public void finalDeliveryRechecksPolicyAfterResourceCleanup() throws Exception {
        location.current(request(), access(), result); idle(); backend.onCancel = () -> allowed.set(false);
        backend.latest().listener.location(fix(0, 3)); idle(); assertError("PERMISSION_DENIED"); assertNull(result.value);
    }

    @Test public void ageIsRecheckedAfterCleanupBeforeCoordinatesCanEscape() throws Exception {
        location.current(LocationRequest.parseCurrent("{\"provider\":\"network\",\"maxAgeMs\":1000}"), ALLOW, result); idle();
        backend.onCancel = () -> now.addAndGet(1001000000L);
        backend.latest().listener.location(fix(0, 3)); idle(); assertError("LOCATION_UNAVAILABLE"); assertNull(result.value);
        assertEquals("stale_fix", location.status().getString("lastRejectedFixReason"));
    }

    @Test public void cleanupRechecksForegroundBeforeDeliveringCoordinates() throws Exception {
        location.current(request(), ALLOW, result); idle(); backend.onCancel = () -> foreground.set(false);
        backend.latest().listener.location(fix(0, 3)); idle(); assertError("FOREGROUND_REQUIRED"); assertNull(result.value);
    }

    @Test public void oldRequestHandleCannotCancelNewerWorkAndLateCallbacksCannotSettleIt() throws Exception {
        NativeLocation.Handle firstHandle = location.current(request(), ALLOW, result); idle(); Request first = backend.latest();
        firstHandle.cancel(); idle(); assertError("CANCELLED");
        Result next = new Result(); location.current(request(), ALLOW, next); idle(); Request second = backend.latest();
        firstHandle.cancel(); first.listener.location(fix(0, 1)); first.listener.disabled(); idle();
        assertEquals(0, next.calls); assertEquals(0, second.cancellations);
        second.listener.location(fix(0, 15)); idle(); assertEquals(1, next.calls); assertNotNull(next.value);
    }

    @Test public void cancelIsGrantFreeAndCloseIsIdempotentEvenAfterRevocation() throws Exception {
        location.current(request(), access(), result); idle(); allowed.set(false);
        assertTrue(location.cancel().getBoolean("cancelled")); location.close(); location.close(); idle();
        assertError("CANCELLED"); assertEquals(1, backend.latest().cancellations); assertFalse(location.cancel().getBoolean("cancelled"));
    }

    @Test public void closedBeforeQueuedBeginNeverAcquiresAnything() throws Exception {
        location.current(request(), ALLOW, result); location.close(); idle();
        assertError("CANCELLED"); assertTrue(backend.requests.isEmpty());
    }

    @Test public void reentrantCloseInsideRegistrationCannotLeakReturnedHandle() throws Exception {
        backend.onRegister = location::close;
        location.current(request(), ALLOW, result); idle(); assertError("CANCELLED"); assertEquals(1, backend.latest().cancellations);
    }

    @Test public void duplicateSynchronousCallbacksSettleExactlyOnceAfterHandleIsReturned() throws Exception {
        backend.synchronousFix = fix(0, 25);
        location.current(request(), ALLOW, result); idle();
        assertEquals(1, result.calls); assertNotNull(result.value); assertEquals(1, backend.latest().cancellations);
        backend.latest().listener.location(fix(0, 1)); idle(); assertEquals(1, result.calls);
    }

    @Test public void busyDoesNotInterruptTheOwnedRequest() throws Exception {
        location.current(request(), ALLOW, result); idle();
        assertEquals("BUSY", assertThrows(FactoryException.class, () -> location.current(request(), ALLOW, new Result())).code);
        assertEquals(1, backend.requests.size()); assertEquals(0, backend.latest().cancellations);
    }

    @Test public void platformFailureReleasesEarlierProviderAndDoesNotEchoNativeDetails() throws Exception {
        backend.failRegistration = 2;
        location.current(LocationRequest.parseCurrent("{}"), ALLOW, result); idle();
        assertError("LOCATION_UNAVAILABLE"); assertEquals(1, backend.requests.size());
        assertEquals(1, backend.latest().cancellations); assertFalse(result.error.getMessage().contains("synthetic-private"));
    }

    @Test public void mutablePlatformFixIsCopiedBeforeQueuedDelivery() throws Exception {
        location.current(request(), ALLOW, result); idle(); Location fix = fix(0, 10);
        backend.latest().listener.location(fix); fix.setLatitude(88); idle();
        assertEquals(12.25, result.value.getDouble("latitude"), 0);
    }

    private LocationRequest.Current request() throws Exception { return LocationRequest.parseCurrent("{\"provider\":\"network\"}"); }
    private NativeOperationAuthorization.Access access() { return () -> { if (!allowed.get()) throw new SecurityException("synthetic-revocation"); }; }
    private Location fix(long ageMs, float accuracy) {
        Location fix = new Location("network"); fix.setLatitude(12.25); fix.setLongitude(-45.5); fix.setAccuracy(accuracy);
        fix.setElapsedRealtimeNanos(now.get() - ageMs * 1000000L); fix.setTime(1700000000000L - ageMs); return fix;
    }
    private void advance(long milliseconds) { now.addAndGet(milliseconds * 1000000L); Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds)); }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private void assertError(String code) { assertEquals(1, result.calls); assertNotNull(result.error); assertEquals(code, result.error.code); assertNull(result.value); }
    private static void markMock(Location location) {
        if (Build.VERSION.SDK_INT >= 31) location.setMock(true);
        else ReflectionHelpers.callInstanceMethod(location, "setIsFromMockProvider", ReflectionHelpers.ClassParameter.from(boolean.class, true));
    }
    private static final class Result implements NativeLocation.Callback {
        int calls;
        JSONObject value;
        FactoryException error;
        @Override public void complete(JSONObject result) { calls++; value = result; }
        @Override public void error(FactoryException failure) { calls++; error = failure; }
    }
    private static final class Request implements NativeLocation.Registration {
        final String provider;
        final boolean single;
        final NativeLocation.Listener listener;
        final FakeBackend backend;
        int cancellations;
        Request(String provider, boolean single, NativeLocation.Listener listener, FakeBackend backend) {
            this.provider = provider; this.single = single; this.listener = listener; this.backend = backend;
        }
        @Override public void cancel() { cancellations++; if (backend.onCancel != null) backend.onCancel.run(); }
    }
    private static final class FakeBackend implements NativeLocation.Backend {
        final List<Request> requests = new ArrayList<>();
        boolean available = true, gpsEnabled = true, networkEnabled = true;
        int registrations, failRegistration;
        Runnable onRegister, onCancel, onDiscovery;
        Location synchronousFix;
        @Override public boolean available() { return available; }
        @Override public boolean hasProvider(String provider) { if (onDiscovery != null) onDiscovery.run(); return true; }
        @Override public boolean enabled(String provider) { return "gps".equals(provider) ? gpsEnabled : networkEnabled; }
        @Override public NativeLocation.Registration current(String provider, NativeLocation.Listener listener) { return register(provider, true, listener); }
        @Override public NativeLocation.Registration updates(String provider, NativeLocation.Listener listener) { return register(provider, false, listener); }
        Request latest() { return requests.get(requests.size() - 1); }
        private Request register(String provider, boolean single, NativeLocation.Listener listener) {
            if (++registrations == failRegistration) throw new IllegalStateException("synthetic-private-native-detail");
            Request request = new Request(provider, single, listener, this); requests.add(request);
            if (onRegister != null) onRegister.run();
            if (synchronousFix != null) { listener.location(synchronousFix); listener.location(synchronousFix); }
            return request;
        }
    }
}
