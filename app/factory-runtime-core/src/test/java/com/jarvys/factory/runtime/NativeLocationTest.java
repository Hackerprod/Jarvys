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
    private final List<NativeLocation> helpers = new ArrayList<>();
    private final List<FakeBackend> backends = new ArrayList<>();
    private Application context;
    private NativeLocation location;
    private Result result;

    @Before public void setup() throws Exception {
        assertEquals("Previous synthetic test retained a registration", 0, NativeLocation.processRegistrations());
        context = RuntimeEnvironment.getApplication();
        PackageInfo own = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
        own.requestedPermissions = new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION};
        Shadows.shadowOf(context.getPackageManager()).installPackage(own);
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION);
        location = helper(backend);
        result = new Result();
    }
    @After public void cleanup() {
        for (FakeBackend fake : backends) {
            fake.failCancel = false;
            fake.onCancel = null;
            for (Request request : fake.requests) request.failCancel = false;
        }
        for (NativeLocation helper : helpers) helper.close();
        idle();
        assertEquals("Synthetic cleanup must release through its original owner", 0, NativeLocation.processRegistrations());
    }

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
        assertEquals(0, NativeLocation.processRegistrations()); assertFalse(location.status().getBoolean("cleanupPending"));
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

    @Test public void cleanupFailureAfterResultCannotReportLocatedOrForgetTheOwner() throws Exception {
        location.current(request(), ALLOW, result); idle(); Request first = backend.latest();
        first.failCancel = true;
        first.listener.location(fix(0, 10)); idle();
        assertError("CLEANUP_FAILED"); assertEquals(1, first.cancellations);
        assertEquals(1, NativeLocation.processRegistrations());
        assertTrue(location.status().getBoolean("cleanupPending"));
        assertEquals("CLEANUP_FAILED", location.status().getString("error"));
        assertEquals("CLEANUP_FAILED", assertThrows(FactoryException.class,
                () -> location.current(request(), ALLOW, new Result())).code);
        first.listener.location(fix(0, 1)); first.listener.disabled(); idle();
        assertEquals(1, result.calls); assertEquals(1, first.cancellations);
        first.failCancel = false;
        assertFalse(location.cancel().getBoolean("cleanupPending"));
        assertEquals(2, first.cancellations); assertEquals(0, NativeLocation.processRegistrations());
        location.cancel(); location.close(); location.close();
        assertEquals(2, first.cancellations); assertEquals(1, result.calls);
    }

    @Test public void cancelReportsUnconfirmedCleanupAndOriginalHandleRetriesWithoutGrants() throws Exception {
        NativeLocation.Handle handle = location.current(request(), access(), result); idle(); Request first = backend.latest();
        first.failCancel = true; allowed.set(false); foreground.set(false);
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION);
        JSONObject cancellation = location.cancel(); idle();
        assertTrue(cancellation.getBoolean("cancelled")); assertTrue(cancellation.getBoolean("cleanupPending"));
        assertEquals("cleanup_failed", cancellation.getString("status")); assertError("CLEANUP_FAILED");
        assertEquals(1, first.cancellations); assertEquals(1, NativeLocation.processRegistrations());
        handle.cancel(); assertEquals(2, first.cancellations); assertEquals(1, NativeLocation.processRegistrations());
        first.failCancel = false; handle.cancel(); handle.cancel(); idle();
        assertEquals(3, first.cancellations); assertEquals(0, NativeLocation.processRegistrations());
        assertEquals(1, result.calls); assertFalse(location.status().getBoolean("cleanupPending"));
    }

    @Test public void repeatedCloseRetriesRetainedOwnershipWithoutRedeliveringAnError() throws Exception {
        location.current(request(), ALLOW, result); idle(); Request first = backend.latest(); first.failCancel = true;
        location.close(); idle(); assertError("CLEANUP_FAILED");
        assertEquals("closed", location.status().getString("status")); assertEquals(1, NativeLocation.processRegistrations());
        first.failCancel = false; location.close(); location.close(); idle();
        assertEquals(2, first.cancellations); assertEquals(0, NativeLocation.processRegistrations()); assertEquals(1, result.calls);
    }

    @Test public void partialRegistrationFailureKeepsOriginalCallbackAndCanRetryItsCleanup() throws Exception {
        backend.failAfterRegistration = 1; backend.failCancel = true;
        location.current(request(), ALLOW, result); idle(); Request partial = backend.latest();
        assertError("CLEANUP_FAILED"); assertEquals(1, partial.cancellations); assertEquals(1, NativeLocation.processRegistrations());
        partial.listener.location(fix(0, 1)); partial.listener.disabled(); idle(); assertEquals(1, result.calls);
        backend.failCancel = false; location.cancel(); location.cancel();
        assertEquals(2, partial.cancellations); assertEquals(0, NativeLocation.processRegistrations());
        assertEquals(1, backend.preparations); assertEquals(1, backend.registrations);
    }

    @Test public void confirmedCleanupAfterPartialFailurePreservesOriginalFailure() throws Exception {
        backend.failAfterRegistration = 1;
        location.current(request(), ALLOW, result); idle();
        assertError("LOCATION_UNAVAILABLE"); assertEquals(1, backend.latest().cancellations);
        assertEquals(0, NativeLocation.processRegistrations());
    }

    @Test public void oneFailedAutoCleanupStillReleasesTheOtherProvider() throws Exception {
        location.current(LocationRequest.parseCurrent("{}"), ALLOW, result); idle();
        Request first = backend.requests.get(0), second = backend.requests.get(1); first.failCancel = true;
        second.listener.location(fix(0, 10)); idle();
        assertError("CLEANUP_FAILED"); assertEquals(1, first.cancellations); assertEquals(1, second.cancellations);
        assertEquals(1, NativeLocation.processRegistrations());
        first.failCancel = false; location.cancel();
        assertEquals(2, first.cancellations); assertEquals(1, second.cancellations); assertEquals(0, NativeLocation.processRegistrations());
    }

    @Test public void failedSingleCancellationCannotAcquireFallbackUpdates() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 30);
        location.current(request(), ALLOW, result); idle(); Request first = backend.latest(); first.failCancel = true;
        first.listener.location(fix(1000, 10)); idle();
        assertError("CLEANUP_FAILED"); assertTrue(first.single); assertEquals(1, backend.requests.size());
        assertEquals(1, first.cancellations); assertEquals(1, NativeLocation.processRegistrations());
    }

    @Test public void replacementHelpersCannotBypassEightHeldProcessAdmissions() throws Exception {
        List<NativeLocation> retiredHelpers = new ArrayList<>();
        List<Request> retained = new ArrayList<>();
        for (int i = 0; i < NativeLocation.MAX_PROCESS_REGISTRATIONS; i++) {
            FakeBackend fake = new FakeBackend(); fake.failCancel = true;
            NativeLocation owner = helper(fake); retiredHelpers.add(owner);
            Result failure = new Result(); owner.current(request(), ALLOW, failure); idle();
            owner.close(); idle();
            assertEquals("CLEANUP_FAILED", failure.error.code); retained.add(fake.latest());
            assertEquals(i + 1, NativeLocation.processRegistrations());
        }
        FakeBackend replacementBackend = new FakeBackend(); NativeLocation replacement = helper(replacementBackend);
        Result blocked = new Result(); replacement.current(request(), ALLOW, blocked); idle();
        assertEquals("RESOURCE_LIMIT", blocked.error.code); assertEquals(1, blocked.calls);
        assertEquals(0, replacementBackend.preparations); assertEquals(0, replacementBackend.registrations);
        assertEquals(NativeLocation.MAX_PROCESS_REGISTRATIONS, NativeLocation.processRegistrations());
        retained.get(0).backend.failCancel = false; retiredHelpers.get(0).close();
        assertEquals(NativeLocation.MAX_PROCESS_REGISTRATIONS - 1, NativeLocation.processRegistrations());
        Result accepted = new Result(); replacement.current(request(), ALLOW, accepted); idle();
        assertEquals(1, replacementBackend.preparations); assertEquals(1, replacementBackend.registrations);
        for (Request old : retained) { old.listener.location(fix(0, 1)); old.listener.disabled(); }
        idle(); assertEquals(0, accepted.calls);
        replacementBackend.latest().listener.location(fix(0, 10)); idle();
        assertEquals(1, accepted.calls); assertNotNull(accepted.value);
        assertEquals(NativeLocation.MAX_PROCESS_REGISTRATIONS - 1, NativeLocation.processRegistrations());
    }

    @Test public void admissionExistsBeforePreparationAndNativeRegistration() throws Exception {
        backend.onPrepare = () -> assertEquals(1, NativeLocation.processRegistrations());
        backend.onRegister = () -> assertEquals(1, NativeLocation.processRegistrations());
        location.current(request(), ALLOW, result); idle(); location.cancel(); idle();
        assertError("CANCELLED"); assertEquals(0, NativeLocation.processRegistrations());
    }

    @Test public void preparationFailureDoesNotPretendNativeAcquisitionOccurred() throws Exception {
        backend.failPreparation = true;
        location.current(request(), ALLOW, result); idle(); assertError("LOCATION_UNAVAILABLE");
        assertEquals(0, backend.registrations); assertTrue(backend.requests.isEmpty());
        assertEquals(0, NativeLocation.processRegistrations());
    }

    @Test public void authorizationIsRecheckedAfterPreparationBeforeNativeAcquisition() throws Exception {
        backend.onPrepare = () -> allowed.set(false);
        location.current(request(), access(), result); idle(); assertError("PERMISSION_DENIED");
        assertEquals(1, backend.preparations); assertEquals(0, backend.registrations);
        assertEquals(0, NativeLocation.processRegistrations());
    }

    @Test public void reentrantCloseDuringPartialStartFailureRetainsUncertainResource() throws Exception {
        backend.onRegister = location::close; backend.failAfterRegistration = 1; backend.failCancel = true;
        location.current(request(), ALLOW, result); idle(); assertError("CLEANUP_FAILED");
        assertEquals(1, backend.latest().cancellations); assertEquals(1, NativeLocation.processRegistrations());
        backend.failCancel = false; location.close(); location.close();
        assertEquals(2, backend.latest().cancellations); assertEquals(0, NativeLocation.processRegistrations());
    }

    @Test public void cancellationReentrancyCannotReleaseAnInFlightAdmissionOrDuplicateDelivery() throws Exception {
        location.current(request(), ALLOW, result); idle();
        backend.onCancel = () -> {
            location.close();
            assertEquals(1, NativeLocation.processRegistrations());
        };
        backend.latest().listener.location(fix(0, 10)); idle();
        assertError("CANCELLED"); assertEquals(1, backend.latest().cancellations);
        assertEquals(0, NativeLocation.processRegistrations());
    }

    @Test public void reentrantCurrentCannotBypassPublishedTerminatingOwnership() throws Exception {
        location.current(request(), ALLOW, result); idle(); backend.failCancel = true;
        backend.onCancel = () -> {
            FactoryException blocked = assertThrows(FactoryException.class,
                    () -> location.current(request(), ALLOW, new Result()));
            assertEquals("CLEANUP_FAILED", blocked.code);
        };
        location.cancel(); idle(); assertError("CLEANUP_FAILED");
        assertEquals(1, backend.preparations); assertEquals(1, backend.registrations);
        assertEquals(1, backend.requests.size()); assertEquals(1, NativeLocation.processRegistrations());
    }

    @Test public void platformCancellationFailureCannotBeClearedByALaterNoOp() {
        int[] calls = {0};
        NativeLocation.PlatformRegistration owner = new NativeLocation.PlatformRegistration() {
            @Override void startNative() { }
            @Override void cancelNative() { if (++calls[0] == 1) throw new IllegalStateException("transport already forgotten"); }
        };
        owner.start();
        assertThrows(IllegalStateException.class, owner::cancel);
        assertThrows(IllegalStateException.class, owner::cancel);
        assertEquals("Do not mistake CancellationSignal/removeUpdates no-op for recovery", 1, calls[0]);
    }

    @Test public void partialPlatformRegistrationCannotClaimReleaseFromAnUnregisteredLocalToken() {
        int[] calls = {0};
        NativeLocation.PlatformRegistration owner = new NativeLocation.PlatformRegistration() {
            @Override void startNative() { throw new IllegalStateException("registration outcome unknown"); }
            @Override void cancelNative() { calls[0]++; }
        };
        assertThrows(IllegalStateException.class, owner::start);
        assertThrows(IllegalStateException.class, owner::cancel);
        assertThrows(IllegalStateException.class, owner::cancel);
        assertEquals(1, calls[0]);
    }

    @Test public void platformSuccessfulCleanupIsIdempotentAndUnusedPreparationDoesNotRegister() {
        int[] starts = {0}, stops = {0};
        NativeLocation.PlatformRegistration owner = new NativeLocation.PlatformRegistration() {
            @Override void startNative() { starts[0]++; }
            @Override void cancelNative() { stops[0]++; }
        };
        owner.start(); owner.cancel(); owner.cancel(); assertEquals(1, starts[0]); assertEquals(1, stops[0]);
        NativeLocation.PlatformRegistration unused = new NativeLocation.PlatformRegistration() {
            @Override void startNative() { fail("Unused preparation must not start"); }
            @Override void cancelNative() { fail("Unused preparation owns no native registration"); }
        };
        unused.cancel(); unused.cancel(); assertThrows(IllegalStateException.class, unused::start);
    }

    private NativeLocation helper(FakeBackend fake) {
        NativeLocation helper = new NativeLocation(context, context.getPackageName(), foreground::get, fake, now::get);
        helpers.add(helper); backends.add(fake); return helper;
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
        boolean acquired, stopped, failCancel;
        Request(String provider, boolean single, NativeLocation.Listener listener, FakeBackend backend) {
            this.provider = provider; this.single = single; this.listener = listener; this.backend = backend;
        }
        @Override public void start() {
            if (++backend.registrations == backend.failRegistration) throw new IllegalStateException("synthetic-private-native-detail");
            acquired = true; backend.requests.add(this);
            if (backend.onRegister != null) backend.onRegister.run();
            if (backend.registrations == backend.failAfterRegistration) throw new IllegalStateException("synthetic-partial-registration");
            if (backend.synchronousFix != null) { listener.location(backend.synchronousFix); listener.location(backend.synchronousFix); }
        }
        @Override public void cancel() {
            if (!acquired || stopped) return;
            cancellations++;
            if (backend.onCancel != null) backend.onCancel.run();
            if (failCancel || backend.failCancel) throw new IllegalStateException("synthetic-cancellation-failed");
            stopped = true;
        }
    }
    private static final class FakeBackend implements NativeLocation.Backend {
        final List<Request> requests = new ArrayList<>();
        boolean available = true, gpsEnabled = true, networkEnabled = true, failCancel, failPreparation;
        int preparations, registrations, failRegistration, failAfterRegistration;
        Runnable onRegister, onCancel, onDiscovery, onPrepare;
        Location synchronousFix;
        @Override public boolean available() { return available; }
        @Override public boolean hasProvider(String provider) { if (onDiscovery != null) onDiscovery.run(); return true; }
        @Override public boolean enabled(String provider) { return "gps".equals(provider) ? gpsEnabled : networkEnabled; }
        @Override public NativeLocation.Registration current(String provider, NativeLocation.Listener listener) { return prepare(provider, true, listener); }
        @Override public NativeLocation.Registration updates(String provider, NativeLocation.Listener listener) { return prepare(provider, false, listener); }
        Request latest() { return requests.get(requests.size() - 1); }
        private Request prepare(String provider, boolean single, NativeLocation.Listener listener) {
            preparations++;
            if (onPrepare != null) onPrepare.run();
            if (failPreparation) throw new IllegalStateException("synthetic-preparation-failed");
            return new Request(provider, single, listener, this);
        }
    }
}
