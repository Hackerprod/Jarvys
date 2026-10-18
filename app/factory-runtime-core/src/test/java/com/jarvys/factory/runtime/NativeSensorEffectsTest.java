package com.jarvys.factory.runtime;

import android.Manifest;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static com.jarvys.factory.runtime.NativeOperationSessionsTest.idle;
import static org.junit.Assert.*;

/** Adapter-only page lease, sensor, grant and bridge fixtures. No physical sensor is accessed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeSensorEffectsTest {
    private NativeOperationSessionsTest.Fixture fixture;
    private NativeEffects effects;
    private FactoryConfig config;
    private SensorSubscriptions subscriptions;
    private Driver driver;
    private Semaphore admissions;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long now;
    private int sequence;

    @Before public void setup() throws Exception {
        fixture = new NativeOperationSessionsTest.Fixture("[\"sensors\",\"steps\"]");
        config = FactoryConfig.parse(new JSONObject(FactoryDispatcherTest.configuration("[\"sensors\",\"steps\"]"))
                .put("appId", fixture.config.appId).put("documentBroker", new JSONObject()
                        .put("packageName", fixture.config.documentBroker.packageName)
                        .put("certificateSha256", fixture.config.documentBroker.certificateSha256)).toString(), fixture.config.appId);
        PackageInfo own = fixture.screen.getPackageManager().getPackageInfo(config.appId, PackageManager.GET_PERMISSIONS);
        own.requestedPermissions = new String[]{Manifest.permission.ACTIVITY_RECOGNITION};
        Shadows.shadowOf(fixture.screen.getPackageManager()).installPackage(own);
        Shadows.shadowOf(fixture.screen.getPackageManager()).setPackagesForUid(android.os.Process.myUid(), DocumentBrokerIdentity.PRIMARY);
        Shadows.shadowOf(fixture.screen.getApplication()).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION);
        effects = new NativeEffects(fixture.screen, config, fixture.sessions, fixture.io, true);
        driver = new Driver(); now = 1000000000L;
        admissions = new Semaphore(SensorSubscriptions.MAX_PROCESS_LISTENERS);
        subscriptions = new SensorSubscriptions(driver, () -> now, main, config.resources.sensors, admissions);
        ReflectionHelpers.setField(effects, "subscriptions", subscriptions);
    }

    @After public void cleanup() throws Exception {
        driver.cleanupThrows = false;
        effects.close(); subscriptions.close(); idle(); fixture.close();
    }

    @Test public void subscribePreservesPermissionDeniedFromRegistrationInsteadOfMaskingItAsCancellation() throws Exception {
        driver.securityFailure = true;
        Result result = subscribe("light"); fixture.pump();
        assertFailure(result, "PERMISSION_DENIED"); assertEquals(1, driver.last().unregisters);
        assertNoRetainedJobs(); assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
    }

    @Test public void falseAndThrowingRegistrationPreserveUnavailableAndDoNotRetainJobs() throws Exception {
        driver.registered = false;
        Result failed = subscribe("light"); fixture.pump();
        assertFailure(failed, "UNAVAILABLE"); assertEquals(1, driver.last().unregisters); assertNoRetainedJobs();
        driver.registered = true; driver.registrationFailure = true;
        Result thrown = subscribe("light"); fixture.pump();
        assertFailure(thrown, "UNAVAILABLE"); assertEquals(1, driver.last().unregisters); assertNoRetainedJobs();
        assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
    }

    @Test public void settledSubscribePromiseKeepsItsPageLeaseAndPollReusesTheSameApprovalBeyondFiveMinutes() throws Exception {
        Result started = subscribe("light"); fixture.pump();
        assertEquals(1, started.successes); assertFalse(started.current()); assertTrue(started.pageCurrent());
        NativeOperationSessions.Lease lease = onlyLease();
        assertFalse(lease.ended.get()); assertEquals(1, jobs("jobs").size()); assertEquals(1, jobs("subscriptionJobs").size());
        advance(300001);
        emit(driver.last(), 12);
        Result polled = perform("sensors.poll", handle(started));
        assertEquals(1, polled.value.getJSONArray("samples").length());
        assertTrue(fixture.provider.issued.isEmpty()); assertFalse(lease.ended.get());
        Result stopped = perform("sensors.unsubscribe", handle(started)); idle();
        assertTrue(stopped.value.getBoolean("unsubscribed")); assertTrue(lease.ended.get()); assertNoRetainedJobs();
        assertTrue(fixture.provider.issued.isEmpty()); assertEquals(1, started.successes); assertNull(started.error);
    }

    @Test public void repeatedAutonomousGrantLossClosesRetainedJobsAndLeasesWithoutAnyPoll() throws Exception {
        for (int i = 0; i < 4; i++) {
            driver.permitted = true;
            Result started = subscribe("light"); fixture.pump();
            NativeOperationSessions.Lease lease = onlyLease();
            assertEquals(1, started.successes); assertEquals(1, jobs("subscriptionJobs").size());
            driver.permitted = false; advance(SensorSubscriptions.AUTHORIZATION_POLL_MS);
            assertTrue(lease.ended.get()); assertNoRetainedJobs();
            assertEquals(1, driver.last().unregisters);
            assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
            assertEquals(1, started.successes); assertNull(started.error);
        }
    }

    @Test public void pageCancellationAfterSettledReplyClosesItsRetainedLease() throws Exception {
        Result started = subscribe("light"); fixture.pump();
        NativeOperationSessions.Lease lease = onlyLease();
        fixture.sessions.cancelAll(); idle();
        assertTrue(lease.ended.get()); assertNoRetainedJobs(); assertEquals(1, driver.last().unregisters);
        assertTrue(fixture.provider.issued.isEmpty()); assertEquals(1, started.successes); assertNull(started.error);
    }

    @Test public void unavailableHostDoesNotBlockAppOwnedSensorSubscription() throws Exception {
        fixture.provider.unavailable = true;
        Result started = subscribe("light"); fixture.pump();
        assertEquals(1, started.successes); assertEquals(1, driver.registrations.size());
        assertTrue(fixture.provider.issued.isEmpty()); assertEquals(1, jobs("subscriptionJobs").size());
        perform("sensors.unsubscribe", handle(started)); idle(); assertNoRetainedJobs();
        assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
    }

    @Test public void autonomousFailureClosesLeaseEvenWhenNativeCleanupStillHoldsAdmission() throws Exception {
        Result started = subscribe("light"); fixture.pump();
        NativeOperationSessions.Lease lease = onlyLease();
        driver.cleanupThrows = true; driver.permitted = false;
        advance(SensorSubscriptions.AUTHORIZATION_POLL_MS);
        assertTrue(lease.ended.get()); assertNoRetainedJobs(); assertTrue(subscriptions.hasUnconfirmedCleanup());
        assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS - 1, admissions.availablePermits());
        assertEquals(1, started.successes);
        driver.cleanupThrows = false; assertTrue(subscriptions.cancelAll());
        assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits()); assertNoRetainedJobs();
    }

    @Test public void helperTerminationQueuedBeforeRetainCannotDeliverAStaleSubscriptionHandle() throws Exception {
        driver.duringRegister = () -> main.post(() -> {
            driver.permitted = false;
            emit(driver.last(), 8);
        });
        Result result = subscribe("light"); fixture.pump();
        assertFailure(result, "CANCELLED"); assertEquals(0, result.deliveries); assertNull(result.value);
        assertNoRetainedJobs(); assertEquals(1, driver.last().unregisters);
        assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
    }

    @Test public void failedSubscribeBridgeDeliveryCancelsTheListenerAndClosesTheLease() throws Exception {
        Result result = new Result(); result.accept = false;
        perform("reject_delivery", "sensors.subscribe", args("light"), result); fixture.pump();
        assertFailure(result, "CANCELLED"); assertEquals(1, result.deliveries); assertNull(result.value);
        assertNoRetainedJobs(); assertEquals(1, driver.last().unregisters);
        assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
    }

    @Test public void reusedBridgeRequestIdDoesNotReplaceAnotherRetainedSubscriptionJob() throws Exception {
        Result light = new Result(), gyro = new Result();
        perform("reused_id", "sensors.subscribe", args("light"), light); fixture.pump();
        Driver.Registration lightRegistration = driver.last();
        perform("reused_id", "sensors.subscribe", args("gyroscope"), gyro); fixture.pump();
        assertEquals(1, light.successes); assertEquals(1, gyro.successes);
        assertEquals(2, jobs("jobs").size()); assertEquals(2, jobs("subscriptionJobs").size()); assertEquals(2, leases().size());
        perform("sensors.unsubscribe", handle(light)); idle();
        assertEquals(1, lightRegistration.unregisters); assertEquals(0, driver.last().unregisters);
        assertEquals(1, jobs("jobs").size()); assertEquals(1, jobs("subscriptionJobs").size()); assertEquals(1, leases().size());
        emit(driver.last(), 1, 2, 3);
        Result polled = perform("sensors.poll", handle(gyro));
        assertEquals(1, polled.value.getJSONArray("samples").length()); assertTrue(fixture.provider.issued.isEmpty());
        perform("sensors.unsubscribe", handle(gyro)); idle(); assertNoRetainedJobs();
    }

    @Test public void pageReplacementAndPauseEndRetainedOwnershipWithoutReusingSettledReplies() throws Exception {
        Result oldPage = subscribe("light"); fixture.pump();
        NativeOperationSessions.Lease oldLease = onlyLease(); oldPage.page = false;
        advance(SensorSubscriptions.AUTHORIZATION_POLL_MS);
        assertTrue(oldLease.ended.get()); assertNoRetainedJobs(); assertEquals(1, driver.last().unregisters);
        Result next = subscribe("light"); fixture.pump(); NativeOperationSessions.Lease nextLease = onlyLease();
        effects.onPause(); fixture.sessions.onPause(); idle();
        assertTrue(nextLease.ended.get()); assertNoRetainedJobs(); assertEquals(1, driver.last().unregisters);
        fixture.sessions.onResume(); effects.onResume(); fixture.pump();
        assertEquals(1, oldPage.successes); assertEquals(1, next.successes); assertEquals(2, driver.registrations.size());
    }

    @Test public void stepCancellationReportsTrueBeforeEndingItsJobAndNeedsNoFreshApproval() throws Exception {
        Semaphore stepSlots = new Semaphore(1);
        int[] releases = {0};
        NativeSteps stepHelper = new NativeSteps(fixture.screen, config.appId, () -> true, new NativeSteps.Backend() {
            public boolean available() { return true; }
            public NativeSteps.Registration create() {
                return new NativeSteps.Registration() {
                    public boolean register(NativeSteps.Listener listener) { return true; }
                    public void cancel() { releases[0]++; }
                };
            }
        }, () -> now, stepSlots);
        ReflectionHelpers.setField(effects, "steps", stepHelper);
        Result current = perform("steps.current", new JSONObject()); fixture.pump();
        assertTrue(stepHelper.status().getBoolean("busy")); assertEquals(0, stepSlots.availablePermits());
        fixture.provider.unavailable = true;
        Result stopped = perform("steps.cancel", new JSONObject()); idle();
        assertTrue(stopped.value.getBoolean("cancelled")); assertFalse(stopped.value.getBoolean("cleanupFailed"));
        assertFailure(current, "CANCELLED"); assertEquals(1, releases[0]); assertEquals(1, stepSlots.availablePermits());
        assertTrue(fixture.provider.issued.isEmpty()); assertNoRetainedJobs();
    }

    private Result subscribe(String kind) throws Exception { return perform("sensors.subscribe", args(kind)); }
    private JSONObject args(String kind) throws Exception { return new JSONObject().put("kind", kind).put("samplingPeriodUs", 100000); }
    private JSONObject handle(Result result) throws Exception { return new JSONObject().put("subscriptionId", result.value.getString("subscriptionId")); }
    private Result perform(String method, JSONObject args) throws Exception {
        Result result = new Result(); perform("sensor_effect_" + ++sequence, method, args, result); return result;
    }
    private void perform(String id, String method, JSONObject args, Result reply) throws Exception {
        effects.perform(BridgeProtocol.validate(BridgeProtocol.ORIGIN, true,
                new JSONObject().put("v", 1).put("id", id).put("method", method).put("args", args).toString(), config), reply);
    }
    private void advance(long ms) { now += ms * 1000000L; Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
    private void emit(Driver.Registration registration, float... values) { now += 1000000L; registration.events.sample(now, values); }
    private Map<?, ?> jobs(String name) { return ReflectionHelpers.getField(effects, name); }
    private Set<NativeOperationSessions.Lease> leases() { return ReflectionHelpers.getField(fixture.sessions, "leases"); }
    private NativeOperationSessions.Lease onlyLease() { assertEquals(1, leases().size()); return leases().iterator().next(); }
    private void assertNoRetainedJobs() { assertTrue(jobs("jobs").isEmpty()); assertTrue(jobs("subscriptionJobs").isEmpty()); assertTrue(leases().isEmpty()); }
    private static void assertFailure(Result result, String error) { assertEquals(error, result.error); assertEquals(1, result.failures); assertEquals(0, result.successes); }

    /** A settled Promise is no longer current, while its original page can remain current. */
    private static final class Result implements NativeEffects.Reply {
        boolean pending = true, page = true, accept = true, held;
        JSONObject value; String error;
        int successes, failures, deliveries;
        public boolean current() { return pending && page; }
        public boolean pageCurrent() { return page; }
        public boolean claimUi() { if (held) return false; held = true; return true; }
        public void releaseUi() { held = false; }
        public void ok(Object result) { if (current()) { value = (JSONObject) result; successes++; pending = false; } }
        public boolean deliver(Object result) { deliveries++; if (!accept || !current()) return false; ok(result); return true; }
        public void fail(String code, String message) { if (current()) { error = code; failures++; pending = false; } }
    }

    private static final class Driver implements SensorSubscriptions.Driver {
        boolean permitted = true, registered = true, securityFailure, registrationFailure, cleanupThrows;
        Runnable duringRegister;
        final List<Registration> registrations = new ArrayList<>();
        public boolean available(String kind) { return true; }
        public boolean permitted(String kind, int periodUs) { return permitted; }
        public SensorSubscriptions.Registration create(String kind) {
            Registration registration = new Registration(); registrations.add(registration); return registration;
        }
        Registration last() { return registrations.get(registrations.size() - 1); }
        final class Registration implements SensorSubscriptions.Registration {
            SensorSubscriptions.Events events;
            int unregisters;
            public boolean register(int periodUs, SensorSubscriptions.Events events) {
                this.events = events;
                if (duringRegister != null) duringRegister.run();
                if (securityFailure) throw new SecurityException("Synthetic registration denial");
                if (registrationFailure) throw new IllegalStateException("Synthetic registration failure");
                return registered;
            }
            public void unregister() { unregisters++; if (cleanupThrows) throw new IllegalStateException("Synthetic cleanup uncertainty"); }
        }
    }
}
