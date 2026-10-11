package com.jarvys.factory.runtime;

import android.Manifest;
import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Synthetic scalar events and registrations only. No device sensors, dialogs or network calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 29, 35}, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeStepsTest {
    private static final NativeOperationAuthorization.Access ALLOW = () -> { };
    private final AtomicBoolean foreground = new AtomicBoolean(true), allowed = new AtomicBoolean(true);
    private final AtomicLong now = new AtomicLong(60000000000L);
    private final FakeBackend backend = new FakeBackend();
    private final Semaphore admissions = new Semaphore(NativeSteps.MAX_PROCESS_LISTENERS);
    private Application context;
    private NativeSteps steps;
    private Result result;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        PackageInfo own = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
        own.requestedPermissions = new String[]{Manifest.permission.ACTIVITY_RECOGNITION};
        Shadows.shadowOf(context.getPackageManager()).installPackage(own);
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION);
        steps = new NativeSteps(context, context.getPackageName(), foreground::get, backend, now::get, admissions);
        result = new Result();
    }
    @After public void cleanup() {
        backend.onCancel = null; backend.cleanupThrows = false; backend.partialCleanupFailure = false; steps.close(); idle();
    }

    @Test public void statusAndConstructionNeverRegisterOrExposeSample() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new NativeSteps(context, "another.package", foreground::get));
        assertThrows(IllegalArgumentException.class, () -> new NativeSteps(null, "another.package", foreground::get));
        assertThrows(IllegalArgumentException.class, () -> new NativeSteps(context, context.getPackageName(), null));
        assertThrows(IllegalArgumentException.class, () -> new NativeSteps(context, context.getPackageName(), foreground::get, backend, now::get, null));
        JSONObject status = steps.status();
        assertEquals("idle", status.getString("status")); assertTrue(status.getBoolean("available"));
        assertTrue(status.getBoolean("sensorDiscoveryAvailable")); assertTrue(status.getBoolean("declarationsKnown"));
        assertTrue(status.getBoolean("activityRecognitionDeclared")); assertTrue(status.getBoolean("activityRecognitionGranted"));
        assertEquals(Build.VERSION.SDK_INT >= 29, status.getBoolean("permissionRequired"));
        assertTrue(status.getBoolean("foregroundOnly")); assertFalse(status.getBoolean("continuousTracking"));
        assertFalse(status.getBoolean("busy")); assertFalse(status.getBoolean("cleanupFailed"));
        assertEquals("step_counter", status.getString("sensorType")); assertNoSample(status);
        assertFalse(steps.cancel().getBoolean("cancelled")); assertTrue(backend.registrations.isEmpty());
    }

    @Test public void permissionDeclarationGrantAndAvailabilityAreSeparateFacts() throws Exception {
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION);
        JSONObject status = steps.status();
        assertTrue(status.getBoolean("activityRecognitionDeclared")); assertFalse(status.getBoolean("activityRecognitionGranted"));
        assertTrue(status.getBoolean("available")); assertTrue(backend.registrations.isEmpty());
        backend.available = false;
        assertFalse(steps.status().getBoolean("available")); assertTrue(steps.status().getBoolean("sensorDiscoveryAvailable"));
    }

    @Test public void metadataDiscoveryFailureDoesNotPretendHardwareAbsentIsConfirmed() throws Exception {
        backend.discoveryThrows = true;
        JSONObject status = steps.status();
        assertFalse(status.getBoolean("available")); assertFalse(status.getBoolean("sensorDiscoveryAvailable"));
        assertNoSample(status); assertTrue(backend.registrations.isEmpty());
    }

    @Test public void policyAndAndroidPermissionCannotSubstituteForEachOther() throws Exception {
        allowed.set(false);
        assertThrows(SecurityException.class, () -> steps.current(request(), access(), result));
        assertThrows(SecurityException.class, () -> steps.current(request(), null, result));
        assertTrue(backend.registrations.isEmpty());
        allowed.set(true); Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION);
        if (Build.VERSION.SDK_INT >= 29) {
            assertEquals("PERMISSION_DENIED", assertThrows(FactoryException.class, () -> steps.current(request(), ALLOW, result)).code);
            idle(); assertTrue(backend.registrations.isEmpty());
        } else {
            steps.current(request(), access(), result); idle(); emit(20); assertSuccess(20);
        }
    }

    @Test public void invalidArgumentsBackgroundAndClosedLifecycleCannotStart() throws Exception {
        assertThrows(FactoryException.class, () -> steps.current(null, ALLOW, result));
        assertThrows(IllegalArgumentException.class, () -> steps.current(request(), ALLOW, null));
        foreground.set(false);
        assertEquals("FOREGROUND_REQUIRED", assertThrows(FactoryException.class, () -> steps.current(request(), ALLOW, result)).code);
        foreground.set(true); steps.close(); steps.close();
        assertEquals("CANCELLED", assertThrows(FactoryException.class, () -> steps.current(request(), ALLOW, result)).code);
        assertEquals("closed", steps.status().getString("status")); assertTrue(backend.registrations.isEmpty());
    }

    @Test public void queuedRevocationPreventsAnyRegistration() throws Exception {
        steps.current(request(), access(), result); allowed.set(false); idle();
        assertError("PERMISSION_DENIED"); assertTrue(backend.registrations.isEmpty());
    }

    @Test public void queuedActualGrantLossPreventsRegistrationOnAndroid29AndLater() throws Exception {
        if (Build.VERSION.SDK_INT < 29) return;
        steps.current(request(), ALLOW, result); Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION); idle();
        assertError("PERMISSION_DENIED"); assertTrue(backend.registrations.isEmpty());
    }

    @Test public void authorizationIsRecheckedAfterDiscoveryImmediatelyBeforeRegistration() throws Exception {
        backend.onDiscovery = () -> allowed.set(false);
        steps.current(request(), access(), result); idle();
        assertError("PERMISSION_DENIED"); assertTrue(backend.registrations.isEmpty());
    }

    @Test public void revocationInsideRegistrationReleasesReturnedResource() throws Exception {
        backend.onRegister = () -> allowed.set(false);
        steps.current(request(), access(), result); idle();
        assertError("PERMISSION_DENIED"); assertEquals(1, backend.latest().cancellations);
    }

    @Test public void firstHardwareEventReportsBootActivatedCounterAndLastStepTimestampHonestly() throws Exception {
        steps.current(request(), ALLOW, result); idle();
        assertEquals(0, result.calls); assertEquals("requesting", steps.status().getString("status"));
        long previousStep = now.get() - 12000000000L;
        backend.latest().listener.sample(previousStep, 456); idle();
        assertSuccess(456);
        assertEquals("since_boot_while_sensor_activated", result.value.getString("counterScope"));
        assertEquals("system_reboot", result.value.getString("resetsOn"));
        assertEquals(Long.toString(previousStep), result.value.getString("sensorTimestampNanos"));
        assertEquals(12000, result.value.getLong("sensorAgeMs")); assertTrue(result.value.getBoolean("eventBeforeRequest"));
        assertFalse(result.value.getBoolean("continuousTracking")); assertTrue(result.value.getBoolean("foregroundOnly"));
        assertFalse(result.value.has("dailySteps")); assertFalse(result.value.has("distance")); assertFalse(result.value.has("resetDetected"));
        assertEquals(1, backend.latest().cancellations); assertNoSample(steps.status());
    }

    @Test public void zeroCounterIsARealValidSampleAndNotATimeoutFallback() throws Exception {
        steps.current(request(), ALLOW, result); idle(); assertNull(result.value);
        emit(0); assertSuccess(0); assertFalse(result.value.getBoolean("eventBeforeRequest"));
    }

    @Test public void noCachedSampleIsReplayedOnSubsequentRequest() throws Exception {
        steps.current(request(), ALLOW, result); idle(); emit(123); assertSuccess(123);
        Result next = new Result(); steps.current(request(), ALLOW, next); idle();
        assertEquals(2, backend.registrations.size()); assertEquals(0, next.calls);
        // A lower reported total is not transformed into a daily delta or fabricated reboot claim.
        backend.latest().listener.sample(now.get(), 5); idle();
        assertEquals(5, next.value.getLong("stepsSinceBoot")); assertFalse(next.value.has("resetDetected"));
    }

    @Test public void unsafeOrMalformedCountersAreDiscardedUntilValidEvent() throws Exception {
        steps.current(request(), ALLOW, result); idle();
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1, 1.5f, 9007199254740992f}) {
            backend.latest().listener.sample(now.get(), invalid); idle();
            assertEquals(0, result.calls); assertEquals("invalid_counter", steps.status().getString("lastRejectedSampleReason"));
        }
        emit(42); assertSuccess(42);
    }

    @Test public void invalidTimestampsAreRejectedButOldLastStepTimestampIsAllowed() throws Exception {
        steps.current(request(), ALLOW, result); idle();
        for (long invalid : new long[]{-1, 0, now.get() + 1}) {
            backend.latest().listener.sample(invalid, 12); idle();
            assertEquals(0, result.calls); assertEquals("invalid_timestamp", steps.status().getString("lastRejectedSampleReason"));
        }
        backend.latest().listener.sample(1, 12); idle(); assertSuccess(12);
        assertTrue(result.value.getBoolean("eventBeforeRequest"));
    }

    @Test public void duplicateAndLateEventsCompleteOnlyOnce() throws Exception {
        steps.current(request(), ALLOW, result); idle(); Registration registration = backend.latest();
        for (int i = 0; i < 1000; i++) registration.listener.sample(now.get(), i + 1);
        idle(); assertSuccess(1); assertEquals(1, registration.cancellations);
        registration.listener.sample(now.get(), 999); idle(); assertEquals(1, result.calls);
    }

    @Test public void synchronousRegistrationEventIsDeliveredOnlyAfterRegistrationHandleIsOwned() throws Exception {
        backend.synchronousEvent = true;
        steps.current(request(), ALLOW, result); idle(); assertSuccess(7);
        assertEquals(1, backend.latest().cancellations);
    }

    @Test public void timeoutDoesNotInventZeroAndReleasesSilentListener() throws Exception {
        steps.current(StepsRequest.parseCurrent("{\"timeoutMs\":1000}"), ALLOW, result); idle();
        advance(1000); assertError("TIMEOUT"); assertEquals(1, backend.latest().cancellations);
        backend.latest().listener.sample(now.get(), 3); idle(); assertEquals(1, result.calls); assertNoSample(steps.status());
    }

    @Test public void queuedEventAtDeadlineIsNotDelivered() throws Exception {
        steps.current(StepsRequest.parseCurrent("{\"timeoutMs\":1000}"), ALLOW, result); idle();
        backend.latest().listener.sample(now.get(), 3); now.addAndGet(1000000000L); idle();
        assertError("TIMEOUT"); assertEquals(1, backend.latest().cancellations);
    }

    @Test public void backwardMonotonicClockFailsInsteadOfNegativeAgeOrExtendedTimeout() throws Exception {
        steps.current(request(), ALLOW, result); idle();
        now.decrementAndGet(); backend.latest().listener.sample(now.get(), 3); idle(); assertError("STEPS_UNAVAILABLE");
        assertEquals(1, backend.latest().cancellations);
    }

    @Test public void foregroundLossBetweenEventAndDeliveryReleasesWithoutSample() throws Exception {
        steps.current(request(), ALLOW, result); idle(); backend.latest().listener.sample(now.get(), 40);
        foreground.set(false); idle(); assertError("FOREGROUND_REQUIRED"); assertEquals(1, backend.latest().cancellations);
    }

    @Test public void liveGrantLossBetweenEventAndDeliveryReleasesWithoutSample() throws Exception {
        steps.current(request(), access(), result); idle(); backend.latest().listener.sample(now.get(), 40);
        allowed.set(false); idle(); assertError("PERMISSION_DENIED"); assertEquals(1, backend.latest().cancellations);
    }

    @Test public void actualGrantLossAtDeliveryIsCheckedOnAndroid29AndLater() throws Exception {
        if (Build.VERSION.SDK_INT < 29) return;
        steps.current(request(), ALLOW, result); idle(); backend.latest().listener.sample(now.get(), 40);
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION); idle();
        assertError("PERMISSION_DENIED"); assertEquals(1, backend.latest().cancellations);
    }

    @Test public void silentListenerIsStoppedByAuthorizationPoll() throws Exception {
        steps.current(request(), access(), result); idle(); allowed.set(false); advance(NativeSteps.AUTHORIZATION_POLL_MS);
        assertError("PERMISSION_DENIED"); assertEquals(1, backend.latest().cancellations);
    }

    @Test public void silentListenerIsStoppedByForegroundPoll() throws Exception {
        steps.current(request(), ALLOW, result); idle(); foreground.set(false); advance(NativeSteps.AUTHORIZATION_POLL_MS);
        assertError("FOREGROUND_REQUIRED"); assertEquals(1, backend.latest().cancellations);
    }

    @Test public void silentListenerIsStoppedByActualPermissionPoll() throws Exception {
        if (Build.VERSION.SDK_INT < 29) return;
        steps.current(request(), ALLOW, result); idle();
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION); advance(NativeSteps.AUTHORIZATION_POLL_MS);
        assertError("PERMISSION_DENIED"); assertEquals(1, backend.latest().cancellations);
    }

    @Test public void busyDoesNotReplaceExistingRequest() throws Exception {
        steps.current(request(), ALLOW, result); idle();
        assertEquals("BUSY", assertThrows(FactoryException.class, () -> steps.current(request(), ALLOW, new Result())).code);
        assertEquals(1, backend.registrations.size()); emit(11); assertSuccess(11);
    }

    @Test public void publicCancelRequiresNoFreshPermissionOrPolicyAndIsIdempotent() throws Exception {
        steps.current(request(), access(), result); idle(); allowed.set(false); foreground.set(false);
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION);
        assertTrue(steps.cancel().getBoolean("cancelled")); assertFalse(steps.cancel().getBoolean("cancelled"));
        idle(); assertError("CANCELLED"); assertEquals(1, backend.latest().cancellations);
    }

    @Test public void oldHandleCannotCancelNewRequest() throws Exception {
        NativeSteps.Handle old = steps.current(request(), ALLOW, result); idle(); emit(9); assertSuccess(9);
        Result next = new Result(); NativeSteps.Handle current = steps.current(request(), ALLOW, next); idle();
        old.cancel(); idle(); assertEquals(0, next.calls); assertEquals(0, backend.latest().cancellations);
        current.cancel(); current.cancel(); idle(); assertEquals(1, next.calls); assertEquals("CANCELLED", next.error.code);
        assertEquals(1, backend.latest().cancellations);
    }

    @Test public void cancelBeforeQueuedStartPreventsAnyRegistration() throws Exception {
        NativeSteps.Handle handle = steps.current(request(), ALLOW, result); handle.cancel(); idle();
        assertError("CANCELLED"); assertTrue(backend.registrations.isEmpty());
    }

    @Test public void reentrantCancellationInsideRegisterStillReleasesReturnedResource() throws Exception {
        backend.onRegister = () -> steps.cancel();
        steps.current(request(), ALLOW, result); idle(); assertError("CANCELLED");
        assertEquals(1, backend.latest().cancellations);
    }

    @Test public void reentrantCloseInsideRegisterStillReleasesReturnedResource() throws Exception {
        backend.onRegister = steps::close;
        steps.current(request(), ALLOW, result); idle(); assertError("CANCELLED");
        assertEquals(1, backend.latest().cancellations); assertEquals("closed", steps.status().getString("status"));
    }

    @Test public void authorizationIsRecheckedAfterUnregisterBeforeSuccess() throws Exception {
        backend.onCancel = () -> allowed.set(false);
        steps.current(request(), access(), result); idle(); emit(10); assertError("PERMISSION_DENIED");
        assertEquals(1, backend.latest().cancellations);
    }

    @Test public void actualPermissionIsRecheckedAfterUnregisterBeforeSuccess() throws Exception {
        if (Build.VERSION.SDK_INT < 29) return;
        backend.onCancel = () -> Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION);
        steps.current(request(), ALLOW, result); idle(); emit(10); assertError("PERMISSION_DENIED");
    }

    @Test public void reentrantCancelDuringSuccessCleanupWinsAndDeliversExactlyOnce() throws Exception {
        backend.onCancel = () -> steps.cancel();
        steps.current(request(), ALLOW, result); idle(); emit(10); assertError("CANCELLED");
        assertEquals(1, backend.latest().cancellations);
    }

    @Test public void reentrantCloseDuringSuccessCleanupDiscardsReading() throws Exception {
        backend.onCancel = steps::close;
        steps.current(request(), ALLOW, result); idle(); emit(10); assertError("CANCELLED");
        assertEquals(1, backend.latest().cancellations);
    }

    @Test public void cleanupFailureDiscardsResultBlocksNewAcquisitionAndCanRetryClose() throws Exception {
        backend.cleanupThrows = true;
        steps.current(request(), ALLOW, result); idle(); emit(10); assertError("CLEANUP_FAILED");
        assertTrue(steps.status().getBoolean("cleanupFailed")); assertEquals("CLEANUP_FAILED", steps.status().getString("error"));
        assertEquals("CLEANUP_FAILED", assertThrows(FactoryException.class, () -> steps.current(request(), ALLOW, new Result())).code);
        assertEquals("cleanup_failed", steps.cancel().getString("status"));
        backend.cleanupThrows = false; steps.close(); assertEquals(2, backend.latest().cancellations);
        assertTrue(steps.status().getBoolean("cleanupFailed")); assertEquals(1, result.calls);
    }

    @Test public void partialRegistrationCleanupFailureIsLatchedAndNeverReportedAsOrdinaryUnavailable() throws Exception {
        backend.partialCleanupFailure = true;
        steps.current(request(), ALLOW, result); idle(); assertError("CLEANUP_FAILED");
        assertTrue(steps.status().getBoolean("cleanupFailed"));
        assertEquals("CLEANUP_FAILED", assertThrows(FactoryException.class, () -> steps.current(request(), ALLOW, new Result())).code);
        assertEquals(NativeSteps.MAX_PROCESS_LISTENERS - 1, admissions.availablePermits());
        backend.partialCleanupFailure = false;
        steps.close(); assertEquals(2, backend.latest().cancellations);
        assertEquals(NativeSteps.MAX_PROCESS_LISTENERS, admissions.availablePermits());
    }

    @Test public void admissionIsAcquiredBeforeRegisterAndReleasedExactlyOnceAfterConfirmedCleanup() throws Exception {
        backend.onRegister = () -> assertEquals(NativeSteps.MAX_PROCESS_LISTENERS - 1, admissions.availablePermits());
        NativeSteps.Handle handle = steps.current(request(), ALLOW, result); idle();
        assertEquals(NativeSteps.MAX_PROCESS_LISTENERS - 1, admissions.availablePermits());
        emit(13); assertSuccess(13);
        assertEquals(NativeSteps.MAX_PROCESS_LISTENERS, admissions.availablePermits());
        handle.cancel(); steps.cancel(); steps.close(); steps.close(); idle();
        assertEquals(NativeSteps.MAX_PROCESS_LISTENERS, admissions.availablePermits());
        assertEquals(1, backend.latest().cancellations);
    }

    @Test public void processAdmissionSurvivesFailedCleanupAndReplacementHelpersUntilConfirmedUnregister() throws Exception {
        List<NativeSteps> owners = new ArrayList<>();
        List<FakeBackend> drivers = new ArrayList<>();
        FakeBackend replacementBackend = new FakeBackend();
        // The five-argument fake constructor deliberately uses the REAL static process ledger.
        // Each helper can be discarded/replaced; its unconfirmed listener still holds its slot.
        NativeSteps replacement = new NativeSteps(context, context.getPackageName(), foreground::get, replacementBackend, now::get);
        try {
            for (int i = 0; i < NativeSteps.MAX_PROCESS_LISTENERS; i++) {
                FakeBackend driver = new FakeBackend(); driver.cleanupThrows = true; drivers.add(driver);
                NativeSteps owner = new NativeSteps(context, context.getPackageName(), foreground::get, driver, now::get);
                owners.add(owner); Result value = new Result();
                owner.current(request(), ALLOW, value); idle();
                assertEquals(1, driver.registrations.size());
                driver.latest().listener.sample(now.get(), 10 + i); idle();
                assertEquals(1, value.calls); assertEquals("CLEANUP_FAILED", value.error.code); assertNull(value.value);
                owner.close(); idle(); // Even repeated failed teardown cannot refund the admission.
                assertTrue(owner.status().getBoolean("cleanupFailed"));
            }
            Result blocked = new Result(); replacement.current(request(), ALLOW, blocked); idle();
            assertEquals(1, blocked.calls); assertEquals("BUSY", blocked.error.code);
            assertTrue(replacementBackend.registrations.isEmpty());
            // Successful cleanup of just one retired helper creates exactly one usable slot.
            drivers.get(0).cleanupThrows = false; owners.get(0).close();
            Result admitted = new Result(); replacement.current(request(), ALLOW, admitted); idle();
            assertEquals(1, replacementBackend.registrations.size());
            replacementBackend.latest().listener.sample(now.get(), 17); idle();
            assertEquals(1, admitted.calls); assertEquals(17, admitted.value.getLong("stepsSinceBoot"));
            assertEquals(1, replacementBackend.latest().cancellations);
        } finally {
            replacement.close();
            for (int i = 0; i < owners.size(); i++) { drivers.get(i).cleanupThrows = false; owners.get(i).close(); }
            idle();
        }
    }

    @Test public void partialRegistrationFailureAlsoRetainsSharedAdmissionAcrossInstances() throws Exception {
        Semaphore shared = new Semaphore(1);
        FakeBackend partial = new FakeBackend(); partial.partialCleanupFailure = true;
        FakeBackend replacementBackend = new FakeBackend();
        NativeSteps original = new NativeSteps(context, context.getPackageName(), foreground::get, partial, now::get, shared);
        NativeSteps replacement = new NativeSteps(context, context.getPackageName(), foreground::get, replacementBackend, now::get, shared);
        try {
            Result failed = new Result(); original.current(request(), ALLOW, failed); idle();
            assertEquals("CLEANUP_FAILED", failed.error.code); assertEquals(0, shared.availablePermits());
            original.close(); assertEquals(0, shared.availablePermits());
            Result blocked = new Result(); replacement.current(request(), ALLOW, blocked); idle();
            assertEquals("BUSY", blocked.error.code); assertTrue(replacementBackend.registrations.isEmpty());
            partial.partialCleanupFailure = false; original.close(); assertEquals(1, shared.availablePermits());
            Result admitted = new Result(); replacement.current(request(), ALLOW, admitted); idle();
            assertEquals(0, shared.availablePermits()); replacement.cancel(); idle();
            assertEquals("CANCELLED", admitted.error.code); assertEquals(1, shared.availablePermits());
            original.close(); replacement.close(); assertEquals(1, shared.availablePermits());
        } finally { partial.partialCleanupFailure = false; original.close(); replacement.close(); idle(); }
    }

    @Test public void reentrantCancellationRetainsAdmissionUntilRegisterReturnsAndCleanupSucceeds() throws Exception {
        backend.onRegister = () -> {
            steps.cancel();
            assertEquals(NativeSteps.MAX_PROCESS_LISTENERS - 1, admissions.availablePermits());
            assertEquals(0, backend.latest().cancellations);
        };
        backend.onCancel = () -> assertEquals(NativeSteps.MAX_PROCESS_LISTENERS - 1, admissions.availablePermits());
        steps.current(request(), ALLOW, result); idle(); assertError("CANCELLED");
        assertEquals(NativeSteps.MAX_PROCESS_LISTENERS, admissions.availablePermits());
        assertEquals(1, backend.latest().cancellations);
    }

    @Test public void reentrantCancellationWithFailedUnregisterDoesNotRefundAdmission() throws Exception {
        backend.onRegister = () -> steps.cancel(); backend.cleanupThrows = true;
        steps.current(request(), ALLOW, result); idle(); assertError("CLEANUP_FAILED");
        assertEquals(NativeSteps.MAX_PROCESS_LISTENERS - 1, admissions.availablePermits());
        assertEquals(1, backend.latest().cancellations);
        backend.cleanupThrows = false; steps.close();
        assertEquals(NativeSteps.MAX_PROCESS_LISTENERS, admissions.availablePermits());
    }

    @Test public void falseOrThrowingRegistrationReleasesPermitOnlyAfterOwnedCleanup() throws Exception {
        backend.returnFalse = true;
        steps.current(request(), ALLOW, result); idle(); assertError("STEPS_UNAVAILABLE");
        assertEquals(1, backend.latest().cancellations); assertEquals(NativeSteps.MAX_PROCESS_LISTENERS, admissions.availablePermits());
        backend.returnFalse = false; backend.registrationThrows = true; Result next = new Result();
        steps.current(request(), ALLOW, next); idle();
        assertEquals("STEPS_UNAVAILABLE", next.error.code); assertEquals(1, backend.latest().cancellations);
        assertEquals(NativeSteps.MAX_PROCESS_LISTENERS, admissions.availablePermits());
    }

    @Test public void allocationFailureAndNullHandleDoNotLeakProcessAdmissions() throws Exception {
        backend.createThrows = true;
        steps.current(request(), ALLOW, result); idle(); assertError("STEPS_UNAVAILABLE");
        assertTrue(backend.registrations.isEmpty()); assertEquals(NativeSteps.MAX_PROCESS_LISTENERS, admissions.availablePermits());
        backend.createThrows = false; backend.returnNull = true; Result next = new Result();
        steps.current(request(), ALLOW, next); idle();
        assertEquals("STEPS_UNAVAILABLE", next.error.code); assertEquals(NativeSteps.MAX_PROCESS_LISTENERS, admissions.availablePermits());
    }

    @Test public void unavailableHardwareFailsWithoutSubstitutingAnotherSensor() throws Exception {
        backend.available = false; steps.current(request(), ALLOW, result); idle();
        assertError("STEPS_UNAVAILABLE"); assertTrue(backend.registrations.isEmpty());
    }

    @Test public void falseRegistrationIsNotASuccessfulZeroReading() throws Exception {
        backend.returnNull = true; steps.current(request(), ALLOW, result); idle(); assertError("STEPS_UNAVAILABLE");
        assertTrue(backend.registrations.isEmpty()); advance(20000); assertEquals(1, result.calls);
    }

    @Test public void nativeExceptionDoesNotLeakPlatformDetails() throws Exception {
        backend.registrationThrows = true; steps.current(request(), ALLOW, result); idle(); assertError("STEPS_UNAVAILABLE");
        assertFalse(result.error.getMessage().contains("synthetic-private")); advance(20000); assertEquals(1, result.calls);
    }

    @Test public void disappearanceOfHardwareEndsSilentRegistration() throws Exception {
        steps.current(request(), ALLOW, result); idle(); backend.available = false; advance(NativeSteps.AUTHORIZATION_POLL_MS);
        assertError("STEPS_UNAVAILABLE"); assertEquals(1, backend.latest().cancellations);
    }

    @Test public void throwingSuccessCallbackDoesNotCauseSecondErrorDelivery() throws Exception {
        steps.current(request(), ALLOW, new NativeSteps.Callback() {
            @Override public void complete(JSONObject value) { result.complete(value); throw new IllegalStateException("caller"); }
            @Override public void error(FactoryException failure) { result.error(failure); }
        });
        idle(); emit(19); assertSuccess(19); assertEquals(1, backend.latest().cancellations);
    }

    private StepsRequest.Current request() throws Exception { return StepsRequest.parseCurrent("{}"); }
    private NativeOperationAuthorization.Access access() { return () -> { if (!allowed.get()) throw new SecurityException("synthetic-revocation"); }; }
    private void emit(float count) { backend.latest().listener.sample(now.get(), count); idle(); }
    private void advance(long ms) { now.addAndGet(ms * 1000000L); Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private void assertSuccess(long expected) throws Exception {
        assertEquals(1, result.calls); assertNull(result.error); assertNotNull(result.value);
        assertEquals("measured", result.value.getString("status")); assertEquals(expected, result.value.getLong("stepsSinceBoot"));
    }
    private void assertError(String code) {
        assertEquals(1, result.calls); assertNotNull(result.error); assertEquals(code, result.error.code); assertNull(result.value);
    }
    private static void assertNoSample(JSONObject status) {
        for (String key : new String[]{"stepsSinceBoot", "sensorTimestampNanos", "sensorAgeMs", "eventBeforeRequest", "sample", "dailySteps", "distance"})
            assertFalse("Status unexpectedly includes " + key, status.has(key));
    }
    private static final class Result implements NativeSteps.Callback {
        int calls;
        JSONObject value;
        FactoryException error;
        @Override public void complete(JSONObject value) { calls++; this.value = value; }
        @Override public void error(FactoryException error) { calls++; this.error = error; }
    }
    private static final class Registration implements NativeSteps.Registration {
        final FakeBackend backend;
        NativeSteps.Listener listener;
        int cancellations;
        Registration(FakeBackend backend) { this.backend = backend; }
        @Override public boolean register(NativeSteps.Listener listener) {
            this.listener = listener; backend.registrations.add(this);
            if (backend.partialCleanupFailure || backend.registrationThrows) throw new IllegalStateException("synthetic-private-registration");
            if (backend.onRegister != null) backend.onRegister.run();
            if (backend.synchronousEvent) { listener.sample(60000000000L, 7); listener.sample(60000000000L, 8); }
            return !backend.returnFalse;
        }
        @Override public void cancel() {
            cancellations++;
            if (backend.onCancel != null) backend.onCancel.run();
            if (backend.cleanupThrows || backend.partialCleanupFailure) throw new IllegalStateException("synthetic-private-cleanup");
        }
    }
    private static final class FakeBackend implements NativeSteps.Backend {
        final List<Registration> registrations = new ArrayList<>();
        boolean available = true, discoveryThrows, createThrows, registrationThrows, returnNull, returnFalse, cleanupThrows, partialCleanupFailure, synchronousEvent;
        Runnable onRegister, onCancel, onDiscovery;
        @Override public boolean available() {
            if (onDiscovery != null) onDiscovery.run();
            if (discoveryThrows) throw new IllegalStateException("synthetic-private-discovery");
            return available;
        }
        @Override public NativeSteps.Registration create() {
            if (createThrows) throw new IllegalStateException("synthetic-private-allocation");
            if (returnNull) return null;
            return new Registration(this);
        }
        Registration latest() { return registrations.get(registrations.size() - 1); }
    }
}
