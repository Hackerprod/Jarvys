package com.jarvys.factory.runtime;

import android.app.Application;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import com.jarvys.factory.contract.ResourceBudgets;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Synthetic events, clock, grants and lifecycle only. No physical sensor acceptance claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 35}, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class SensorSubscriptionsTest {
    static class Driver implements SensorSubscriptions.Driver {
        boolean available = true, permitted = true, result = true;
        boolean throwRegister, throwSecurity, throwCreate, throwCleanup;
        Runnable registering, unregistering;
        final List<Registration> registrations = new ArrayList<>();
        class Registration implements SensorSubscriptions.Registration {
            final String kind;
            int period = -1, unregisters;
            SensorSubscriptions.Events events;
            Registration(String kind) { this.kind = kind; }
            @Override public boolean register(int period, SensorSubscriptions.Events events) {
                this.period = period; this.events = events;
                if (registering != null) registering.run();
                if (throwSecurity) throw new SecurityException("Synthetic denial");
                if (throwRegister) throw new IllegalStateException("Synthetic failed register");
                return result;
            }
            @Override public void unregister() {
                unregisters++;
                if (unregistering != null) unregistering.run();
                if (throwCleanup) throw new IllegalStateException("Synthetic unconfirmed unregister");
            }
        }
        @Override public boolean available(String kind) { return available; }
        @Override public boolean permitted(String kind, int period) { return permitted; }
        @Override public Registration create(String kind) {
            if (throwCreate) throw new IllegalStateException("Synthetic allocation failure");
            Registration registration = new Registration(kind); registrations.add(registration); return registration;
        }
        Registration last() { return registrations.get(registrations.size() - 1); }
    }
    Driver driver;
    SensorSubscriptions streams;
    Semaphore admissions;
    long now;
    boolean pageActive;
    Handler handler;

    @Before public void setup() {
        driver = new Driver(); now = 1000000000L; pageActive = true;
        admissions = new Semaphore(SensorSubscriptions.MAX_PROCESS_LISTENERS);
        handler = new Handler(Looper.getMainLooper());
        streams = create(driver, ResourceBudgets.legacy().sensors);
    }
    @After public void cleanup() { driver.throwCleanup = false; streams.close(); }
    SensorSubscriptions create(Driver backend, ResourceBudgets.Sensors budget) {
        return new SensorSubscriptions(backend, () -> now, handler, budget, admissions);
    }
    ResourceBudgets.Sensors budget(int capacity, int minimumUs) throws Exception {
        return ResourceBudgetsJson.parse(new JSONObject().put("version", 1).put("sensors", new JSONObject()
                .put("captureSamples", 1).put("bufferedSamples", capacity).put("minimumIntervalUs", minimumUs))).sensors;
    }
    SensorSubscriptionRequest.Subscribe request(String kind, int period) throws Exception {
        return SensorSubscriptionRequest.parseSubscribe(new JSONObject().put("kind", kind).put("samplingPeriodUs", period));
    }
    SensorSubscriptionRequest.Handle handle(JSONObject started) throws Exception {
        return SensorSubscriptionRequest.parseHandle(new JSONObject().put("subscriptionId", started.getString("subscriptionId")));
    }
    SensorSubscriptionRequest.Handle start(String kind, int period) throws Exception {
        return handle(streams.subscribe(request(kind, period), () -> pageActive));
    }
    void emit(Driver.Registration registration, float... values) { now += 1000000L; registration.events.sample(now, values); }
    void emit(float... values) { emit(driver.last(), values); }
    void advance(long ms) { now += ms * 1000000L; Shadows.shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS); }
    String error(org.junit.function.ThrowingRunnable action) { return assertThrows(FactoryException.class, action).code; }

    @Test public void continuesBeyondFiveSecondsAndFiftyTotalSamplesWithOnlyBoundedPolls() throws Exception {
        SensorSubscriptionRequest.Handle id = start("accelerometer", 100000);
        for (int batch = 0; batch < 200; batch++) {
            for (int sample = 0; sample < 50; sample++) emit(1, 2, 3);
            JSONObject poll = streams.poll(id);
            assertEquals(50, poll.getJSONArray("samples").length());
            assertEquals(Integer.toString((batch + 1) * 50), poll.getString("receivedSamples"));
            assertEquals("0", poll.getString("droppedSamples"));
            assertTrue(poll.toString().getBytes(StandardCharsets.UTF_8).length <= ResourceBudgets.SENSOR_RESULT_BYTES);
        }
        advance(300001);
        assertEquals(0, driver.last().unregisters);
        assertEquals(0, streams.poll(id).getJSONArray("samples").length());
        assertEquals(100000, driver.last().period);
        assertTrue(streams.unsubscribe(id).getBoolean("unsubscribed"));
        assertEquals(1, driver.last().unregisters);
    }
    @Test public void ringOverwritesOldestWithExactCumulativeDropCountAndDrain() throws Exception {
        streams.close(); streams = create(driver, budget(3, 100000));
        SensorSubscriptionRequest.Handle id = start("light", 100000);
        for (int i = 1; i <= 10; i++) emit(i);
        JSONObject poll = streams.poll(id); JSONArray samples = poll.getJSONArray("samples");
        assertEquals("10", poll.getString("receivedSamples")); assertEquals("7", poll.getString("droppedSamples"));
        assertEquals(3, samples.length());
        for (int i = 0; i < 3; i++) {
            assertEquals(Integer.toString(i + 8), samples.getJSONObject(i).getString("sequence"));
            assertEquals(i + 8, samples.getJSONObject(i).getJSONArray("values").getInt(0));
        }
        JSONObject empty = streams.poll(id); assertEquals(0, empty.getJSONArray("samples").length());
        assertEquals("7", empty.getString("droppedSamples"));
        emit(11); assertEquals("11", streams.poll(id).getJSONArray("samples").getJSONObject(0).getString("sequence"));
    }
    @Test public void samplesAreCopiedWithExactTimestampUnitsAxesAndNoFabricatedReads() throws Exception {
        SensorSubscriptionRequest.Handle id = start("gyroscope", 100000);
        assertEquals(0, streams.poll(id).getJSONArray("samples").length());
        float[] values = new float[]{1, 2, 3, 900}; emit(values); long timestamp = now; values[0] = 99;
        JSONObject poll = streams.poll(id), sample = poll.getJSONArray("samples").getJSONObject(0);
        assertEquals("rad/s", poll.getString("unit")); assertEquals("device-natural", poll.getString("frame"));
        assertFalse(poll.getBoolean("includesGravity")); assertFalse(poll.getBoolean("simulated"));
        assertFalse(poll.getBoolean("rateGuaranteed")); assertTrue(poll.getBoolean("foregroundOnly"));
        assertEquals("elapsedRealtimeNanos", poll.getString("timestampClock"));
        assertEquals(Long.toString(timestamp), sample.getString("timestampNs")); assertEquals(1, sample.getLong("elapsedMs"));
        assertEquals(3, sample.getJSONArray("values").length()); assertEquals(1, sample.getJSONArray("values").getInt(0));
        Set<String> keys = new HashSet<>(); sample.keys().forEachRemaining(keys::add);
        assertEquals(new HashSet<>(Arrays.asList("sequence", "timestampNs", "elapsedMs", "ageMs", "eventBeforeSubscription", "values")), keys);
        assertEquals(0, sample.getLong("ageMs")); assertFalse(sample.getBoolean("eventBeforeSubscription"));
    }
    @Test public void legitimateInitialLightReplayKeepsItsOriginalAgeWithoutFabricatingAnotherReading() throws Exception {
        SensorSubscriptionRequest.Handle id = start("light", 100000); Driver.Registration reg = driver.last();
        long measured = now - 250000000L;
        reg.events.sample(measured, new float[]{12.5f});
        advance(500);
        JSONObject poll = streams.poll(id), sample = poll.getJSONArray("samples").getJSONObject(0);
        assertEquals("1", poll.getString("receivedSamples")); assertEquals("0", poll.getString("invalidSamples"));
        assertEquals(Long.toString(measured), sample.getString("timestampNs"));
        assertTrue(sample.getBoolean("eventBeforeSubscription")); assertEquals(-250, sample.getLong("elapsedMs"));
        assertEquals(750, sample.getLong("ageMs"));
        assertEquals(12.5, sample.getJSONArray("values").getDouble(0), 0);
        reg.events.sample(measured, new float[]{12.5f});
        JSONObject duplicate = streams.poll(id); assertEquals(0, duplicate.getJSONArray("samples").length());
        assertEquals("1", duplicate.getString("invalidSamples"));
        emit(13); assertFalse(streams.poll(id).getJSONArray("samples").getJSONObject(0).getBoolean("eventBeforeSubscription"));
    }
    @Test public void initialLightReplayStillRejectsInvalidTimesAndRevokedPage() throws Exception {
        SensorSubscriptionRequest.Handle id = start("light", 100000); Driver.Registration reg = driver.last();
        reg.events.sample(0, new float[]{5}); reg.events.sample(-1, new float[]{5});
        reg.events.sample(now + 1, new float[]{5});
        assertEquals("3", streams.poll(id).getString("invalidSamples"));
        pageActive = false; reg.events.sample(now - 1, new float[]{5});
        assertEquals(1, reg.unregisters); assertEquals("INVALID_HANDLE", error(() -> streams.poll(id)));
    }
    @Test public void invalidOldFutureNonfiniteAndMalformedEventsNeverBecomeReadings() throws Exception {
        SensorSubscriptionRequest.Handle id = start("accelerometer", 100000); Driver.Registration reg = driver.last();
        reg.events.sample(now - 1, new float[]{1, 2, 3});
        reg.events.sample(now + 1, new float[]{1, 2, 3});
        reg.events.sample(now, null); reg.events.sample(now, new float[]{1});
        reg.events.sample(now, new float[]{Float.NaN, 2, 3});
        reg.events.sample(now, new float[]{1, Float.POSITIVE_INFINITY, 3});
        emit(1, 2, 3); reg.events.sample(now, new float[]{4, 5, 6});
        JSONObject poll = streams.poll(id);
        assertEquals("7", poll.getString("invalidSamples")); assertEquals("1", poll.getString("receivedSamples"));
        assertEquals(1, poll.getJSONArray("samples").length()); assertTrue(poll.getBoolean("includesGravity"));
    }
    @Test public void timestampsBeyondJavascriptIntegerPrecisionAndExtremeFiniteValuesStayBounded() throws Exception {
        now = 9007199254740993L;
        SensorSubscriptionRequest.Handle id = start("gyroscope", 100000);
        for (int i = 0; i < 50; i++) emit(Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE);
        JSONObject poll = streams.poll(id); JSONArray samples = poll.getJSONArray("samples");
        assertEquals(Long.toString(now), samples.getJSONObject(49).getString("timestampNs"));
        assertEquals((double) Float.MAX_VALUE, samples.getJSONObject(0).getJSONArray("values").getDouble(0), 0);
        assertEquals(50, samples.length()); assertFalse(poll.getBoolean("countersSaturated"));
        assertTrue(poll.toString().getBytes(StandardCharsets.UTF_8).length <= ResourceBudgets.SENSOR_RESULT_BYTES);
    }
    @Test public void signedMinimumIsRejectedWithoutClampingAndExplicitFastestCanBeDeclared() throws Exception {
        assertEquals("INVALID_ARGUMENT", error(() -> start("gyroscope", 5000)));
        assertEquals("INVALID_ARGUMENT", error(() -> start("gyroscope", 0)));
        assertTrue(driver.registrations.isEmpty());
        streams.close(); streams = create(driver, budget(50, 0));
        SensorSubscriptionRequest.Handle id = start("gyroscope", 0); assertEquals(0, driver.last().period);
        emit(1, 2, 3); emit(4, 5, 6);
        assertEquals(2, streams.poll(id).getJSONArray("samples").length());
        streams.unsubscribe(id); start("light", Integer.MAX_VALUE); assertEquals(Integer.MAX_VALUE, driver.last().period);
    }
    @Test public void noSoftwareThrottleAtRequestedRateAndNoCrossSubscriptionEventSuppression() throws Exception {
        SensorSubscriptionRequest.Handle light = start("light", 100000); Driver.Registration lightReg = driver.last();
        SensorSubscriptionRequest.Handle gyro = start("gyroscope", 100000); Driver.Registration gyroReg = driver.last();
        for (int i = 0; i < 10; i++) { emit(lightReg, i); emit(gyroReg, i, i, i); }
        assertEquals(10, streams.poll(light).getJSONArray("samples").length());
        assertEquals(10, streams.poll(gyro).getJSONArray("samples").length());
        assertEquals("BUSY", error(() -> start("light", 100000)));
        assertEquals(2, driver.registrations.size());
    }
    @Test public void endedPageIsCancelledOnEventAndDiscardsQueuedDataAndStaleCallback() throws Exception {
        SensorSubscriptionRequest.Handle id = start("light", 100000); Driver.Registration old = driver.last();
        emit(1); pageActive = false; emit(2);
        assertEquals(1, old.unregisters); assertEquals("INVALID_HANDLE", error(() -> streams.poll(id)));
        pageActive = true; SensorSubscriptionRequest.Handle next = start("light", 100000);
        emit(old, 999); assertEquals(0, streams.poll(next).getJSONArray("samples").length());
        assertEquals("INVALID_HANDLE", error(() -> streams.unsubscribe(id)));
        assertEquals(0, driver.last().unregisters);
    }
    @Test public void idleWatchdogUnregistersWhenPolicyOrForegroundAuthorityEnds() throws Exception {
        start("light", 100000); pageActive = false; advance(SensorSubscriptions.AUTHORIZATION_POLL_MS);
        assertEquals(1, driver.last().unregisters); assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
        pageActive = true;
        start("gyroscope", 100000); driver.permitted = false; advance(SensorSubscriptions.AUTHORIZATION_POLL_MS);
        assertEquals(1, driver.last().unregisters);
    }
    @Test public void pollRechecksLiveAuthorityBeforeDeliveringBufferedData() throws Exception {
        SensorSubscriptionRequest.Handle id = start("light", 100000); emit(1); pageActive = false;
        assertEquals("CANCELLED", error(() -> streams.poll(id))); assertEquals(1, driver.last().unregisters);
        assertEquals("INVALID_HANDLE", error(() -> streams.poll(id)));
    }
    @Test public void grantDenialIsHonestAndNeverTreatedAsTheProductMinimum() throws Exception {
        driver.permitted = false;
        assertEquals("PERMISSION_DENIED", error(() -> start("gyroscope", 100000)));
        assertTrue(driver.registrations.isEmpty());
        driver.permitted = true; driver.throwSecurity = true;
        assertEquals("PERMISSION_DENIED", error(() -> start("gyroscope", 100000)));
        assertEquals(1, driver.last().unregisters);
    }
    @Test public void failedAndThrowingRegistrationAlwaysUnregisterBeforeReleasingAdmission() throws Exception {
        driver.unregistering = () -> assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS - 1, admissions.availablePermits());
        driver.result = false; assertEquals("UNAVAILABLE", error(() -> start("light", 100000)));
        assertEquals(1, driver.last().unregisters); assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
        driver.result = true; driver.throwRegister = true; assertEquals("UNAVAILABLE", error(() -> start("light", 100000)));
        assertEquals(1, driver.last().unregisters); assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
    }
    @Test public void synchronousEventDuringRegistrationIsCopiedOnlyIfRegistrationSucceeds() throws Exception {
        driver.registering = () -> emit(7);
        SensorSubscriptionRequest.Handle id = start("light", 100000);
        assertEquals(7, streams.poll(id).getJSONArray("samples").getJSONObject(0).getJSONArray("values").getInt(0));
        streams.unsubscribe(id);
        driver.result = false; assertEquals("UNAVAILABLE", error(() -> start("light", 100000)));
        assertEquals(1, driver.last().unregisters); assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
    }
    @Test public void pageOrOsGrantRevocationDuringRegisterPreventsSuccessAndUnregisters() throws Exception {
        driver.registering = () -> pageActive = false;
        assertEquals("CANCELLED", error(() -> start("light", 100000)));
        assertEquals(1, driver.last().unregisters);
        pageActive = true; driver.registering = () -> driver.permitted = false;
        assertEquals("PERMISSION_DENIED", error(() -> start("gyroscope", 100000)));
        assertEquals(1, driver.last().unregisters); assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
    }
    @Test public void rejectedWatchdogSchedulingCancelsTheRegisteredListener() throws Exception {
        streams.close();
        Handler rejecting = new Handler(Looper.getMainLooper()) {
            @Override public boolean sendMessageAtTime(Message message, long uptimeMillis) { return false; }
        };
        streams = new SensorSubscriptions(driver, () -> now, rejecting, ResourceBudgets.legacy().sensors, admissions);
        assertEquals("UNAVAILABLE", error(() -> start("light", 100000)));
        assertEquals(1, driver.last().unregisters); assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
    }
    @Test public void unconfirmedCleanupRetainsAdmissionAndTombstoneUntilActualRetrySucceeds() throws Exception {
        SensorSubscriptionRequest.Handle id = start("light", 100000); emit(1); driver.throwCleanup = true;
        assertEquals("UNAVAILABLE", error(() -> streams.unsubscribe(id)));
        assertTrue(streams.hasUnconfirmedCleanup()); assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS - 1, admissions.availablePermits());
        assertEquals("UNAVAILABLE", error(() -> start("gyroscope", 100000)));
        emit(999); assertEquals("UNAVAILABLE", error(() -> streams.poll(id)));
        assertFalse(streams.cancelAll());
        driver.throwCleanup = false; assertTrue(streams.cancelAll()); assertFalse(streams.hasUnconfirmedCleanup());
        assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
        assertEquals("INVALID_HANDLE", error(() -> streams.poll(id)));
        start("light", 100000);
    }
    @Test public void failedRegisterCleanupAlsoKeepsAdmissionAndPreventsBypass() throws Exception {
        driver.throwRegister = true; driver.throwCleanup = true;
        assertEquals("UNAVAILABLE", error(() -> start("light", 100000)));
        assertTrue(streams.hasUnconfirmedCleanup()); assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS - 1, admissions.availablePermits());
        driver.throwRegister = false; assertEquals("UNAVAILABLE", error(() -> start("gyroscope", 100000)));
        driver.throwCleanup = false; assertTrue(streams.cancelAll());
        assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
    }
    @Test public void reentrantCancellationWaitsForRegistrationReturnThenActuallyUnregisters() throws Exception {
        driver.registering = () -> {
            assertFalse(streams.cancelAll()); assertEquals(0, driver.last().unregisters);
            assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS - 1, admissions.availablePermits());
        };
        assertEquals("CANCELLED", error(() -> start("light", 100000)));
        assertEquals(1, driver.last().unregisters); assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
        assertFalse(streams.hasUnconfirmedCleanup());
    }
    @Test public void reentrantAuthorityRevocationCannotRegisterAfterItReleasesTheAdmission() throws Exception {
        int[] checks = {0};
        SensorSubscriptions.Authority authority = () -> {
            if (++checks[0] == 2) assertTrue(streams.cancelAll());
            return true;
        };
        assertEquals("CANCELLED", error(() -> streams.subscribe(request("light", 100000), authority)));
        assertEquals(-1, driver.last().period); assertEquals(1, driver.last().unregisters);
        assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
    }
    @Test public void cleanupCallbacksCannotReleaseTwiceAndCloseCanRetryButNeverRestart() throws Exception {
        start("light", 100000);
        driver.unregistering = () -> assertFalse(streams.cancelAll());
        assertTrue(streams.cancelAll()); assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
        driver.unregistering = null; start("light", 100000); driver.throwCleanup = true; streams.close();
        assertTrue(streams.hasUnconfirmedCleanup()); assertEquals("CANCELLED", error(() -> start("gyroscope", 100000)));
        driver.throwCleanup = false; streams.close(); assertFalse(streams.hasUnconfirmedCleanup());
        assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
    }
    @Test public void foreignHandleCannotReadOrCancelAnotherHelpersListener() throws Exception {
        SensorSubscriptionRequest.Handle id = start("light", 100000); Driver otherDriver = new Driver();
        SensorSubscriptions other = create(otherDriver, ResourceBudgets.legacy().sensors);
        try {
            assertEquals("INVALID_HANDLE", error(() -> other.poll(id)));
            assertEquals("INVALID_HANDLE", error(() -> other.unsubscribe(id)));
            assertEquals(0, driver.last().unregisters);
        } finally { other.close(); }
    }
    @Test public void processAdmissionIsSharedAndNeverExpandedAfterCleanupFailure() throws Exception {
        List<SensorSubscriptions> helpers = new ArrayList<>(); List<Driver> drivers = new ArrayList<>();
        try {
            for (int i = 0; i < SensorSubscriptions.MAX_PROCESS_LISTENERS; i++) {
                Driver backend = new Driver(); SensorSubscriptions helper = create(backend, ResourceBudgets.legacy().sensors);
                helpers.add(helper); drivers.add(backend); helper.subscribe(request("light", 100000), () -> true);
            }
            assertEquals("BUSY", error(() -> start("gyroscope", 100000)));
            drivers.get(0).throwCleanup = true; assertFalse(helpers.get(0).cancelAll());
            assertEquals("BUSY", error(() -> start("gyroscope", 100000)));
            drivers.get(0).throwCleanup = false; assertTrue(helpers.get(0).cancelAll());
            start("gyroscope", 100000); assertEquals(0, admissions.availablePermits());
        } finally {
            for (Driver backend : drivers) backend.throwCleanup = false;
            for (SensorSubscriptions helper : helpers) helper.close();
        }
    }
    @Test public void unavailableAndAllocationFailureHaveNoListenerOrAdmissionLeak() throws Exception {
        driver.available = false; assertEquals("UNAVAILABLE", error(() -> start("light", 100000)));
        assertTrue(driver.registrations.isEmpty()); assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
        driver.available = true; driver.throwCreate = true; assertEquals("UNAVAILABLE", error(() -> start("light", 100000)));
        assertEquals(SensorSubscriptions.MAX_PROCESS_LISTENERS, admissions.availablePermits());
    }
    @Test public void androidHighRatePermissionThresholdAndTargetSdkAreIndependentOfLegacyTenHzBudget() {
        Application app = RuntimeEnvironment.getApplication(); int previousTarget = app.getApplicationInfo().targetSdkVersion;
        String permission = "android.permission.HIGH_SAMPLING_RATE_SENSORS";
        try {
            app.getApplicationInfo().targetSdkVersion = 35;
            Shadows.shadowOf(app).denyPermissions(permission);
            SensorSubscriptions.Driver platform = SensorSubscriptions.android(app, handler);
            assertTrue(platform.permitted("light", 0)); assertTrue(platform.permitted("accelerometer", 5000));
            assertTrue(platform.permitted("gyroscope", 100000));
            assertEquals(Build.VERSION.SDK_INT < 31, platform.permitted("accelerometer", 4999));
            assertEquals(Build.VERSION.SDK_INT < 31, platform.permitted("gyroscope", 0));
            Shadows.shadowOf(app).grantPermissions(permission);
            assertTrue(platform.permitted("accelerometer", 0)); assertTrue(platform.permitted("gyroscope", 4));
            Shadows.shadowOf(app).denyPermissions(permission); app.getApplicationInfo().targetSdkVersion = 30;
            assertTrue(platform.permitted("gyroscope", 0));
        } finally { app.getApplicationInfo().targetSdkVersion = previousTarget; }
    }
    @Test public void autonomousGrantLossNotifiesRetainedOwnerOnce() throws Exception {
        final int[] ended={0};
        streams.subscribe(request("gyroscope",100000),new SensorSubscriptions.Authority() {
            public boolean active() { return pageActive; }
            public void ended() { ended[0]++; }
        });
        driver.permitted=false; advance(SensorSubscriptions.AUTHORIZATION_POLL_MS);
        assertEquals(1,ended[0]); streams.cancelAll(); streams.close(); assertEquals(1,ended[0]);
    }
    @Test public void uncertainCleanupStillEndsRetainedLeaseExactlyOnce() throws Exception {
        final int[] ended={0}; int available=admissions.availablePermits();
        streams.subscribe(request("light",100000),new SensorSubscriptions.Authority() {
            public boolean active() { return pageActive; }
            public void ended() { ended[0]++; }
        });
        driver.throwCleanup=true; pageActive=false; advance(SensorSubscriptions.AUTHORIZATION_POLL_MS);
        assertEquals(1,ended[0]); assertEquals(available-1,admissions.availablePermits());
        driver.throwCleanup=false; assertTrue(streams.cancelAll()); assertEquals(1,ended[0]);
        assertEquals(available,admissions.availablePermits());
    }
    @Test public void terminalCallbackMayReenterCancellationWithoutDoubleRelease() throws Exception {
        final int[] ended={0}; int available=admissions.availablePermits();
        SensorSubscriptionRequest.Handle id=handle(streams.subscribe(request("light",100000),new SensorSubscriptions.Authority() {
            public boolean active() { return pageActive; }
            public void ended() { ended[0]++; streams.cancelAll(); }
        }));
        streams.unsubscribe(id); assertEquals(1,ended[0]); assertEquals(available,admissions.availablePermits());
    }

}
