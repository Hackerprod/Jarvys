package com.jarvys.factory.runtime;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import com.jarvys.factory.contract.ResourceBudgets;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Foreground page-owned sensor listeners with fixed-size rings, never a growing capture array.
 * Owners must cancel on page replacement, pause, policy revocation and destruction. Authority must
 * outlive the initial bridge reply and bind the page generation, foreground state and policy lease.
 * The watchdog is a backstop, not permission for background sampling. No service or wake lock.
 *
 * There is no elapsed-duration or lifetime-sample quota. One listener per supported kind per helper
 * and eight subscription listeners per process are working-set admissions, not Android limits.
 * The signed minimumIntervalUs is enforced by rejection; delivered events are never rate-throttled.
 * A requested period is only a platform hint. Even an OS high-rate grant guarantees no actual rate.
 */
public final class SensorSubscriptions implements AutoCloseable {
    public static final int MAX_PROCESS_LISTENERS = 8, MAX_BUFFERED_SAMPLES = 50;
    public static final long AUTHORIZATION_POLL_MS = 100;
    private static final String HIGH_RATE_PERMISSION = "android.permission.HIGH_SAMPLING_RATE_SENSORS";
    private static final Semaphore PROCESS_ADMISSION = new Semaphore(MAX_PROCESS_LISTENERS);

    public interface Authority { boolean active(); default void ended() { } }
    interface Clock { long nanos(); }
    interface Events { void sample(long timestampNs, float[] values); }
    interface Registration {
        boolean register(int samplingPeriodUs, Events events);
        /** Return normally only after unregister is confirmed; exceptions retain the admission. */
        void unregister();
    }
    interface Driver {
        boolean available(String kind);
        boolean permitted(String kind, int samplingPeriodUs);
        /** Allocate a handle without registering anything; register is a separate owned step. */
        Registration create(String kind);
    }

    private final Driver driver;
    private final Clock clock;
    private final Handler handler;
    private final ResourceBudgets.Sensors budget;
    private final Semaphore admissions;
    private final Map<String, Work> subscriptions = new LinkedHashMap<>();
    private boolean closed;

    public static SensorSubscriptions installed(Context context, Handler handler, ResourceBudgets.Sensors budget) {
        return new SensorSubscriptions(android(context, handler), SystemClock::elapsedRealtimeNanos, handler, budget);
    }
    SensorSubscriptions(Driver driver, Clock clock, Handler handler, ResourceBudgets.Sensors budget) {
        this(driver, clock, handler, budget, PROCESS_ADMISSION);
    }
    /** Injected admission keeps failure-path fake tests isolated from production's process ledger. */
    SensorSubscriptions(Driver driver, Clock clock, Handler handler, ResourceBudgets.Sensors budget, Semaphore admissions) {
        this.driver = Objects.requireNonNull(driver); this.clock = Objects.requireNonNull(clock);
        this.handler = Objects.requireNonNull(handler); this.budget = Objects.requireNonNull(budget);
        this.admissions = Objects.requireNonNull(admissions);
        if (budget.bufferedSamples < 1 || budget.bufferedSamples > MAX_BUFFERED_SAMPLES)
            throw new IllegalArgumentException("Unsupported sensor ring working-set declaration.");
    }

    public synchronized JSONObject subscribe(SensorSubscriptionRequest.Subscribe request, Authority authority)
            throws FactoryException {
        if (request == null || authority == null) throw new FactoryException("INVALID_ARGUMENT", "A typed sensor request and live authority are required.");
        if (closed) throw cancelled();
        if (hasUnconfirmedCleanup()) throw cleanupError();
        if (!active(authority)) throw cancelled();
        if (request.samplingPeriodUs < budget.minimumIntervalUs)
            throw new FactoryException("INVALID_ARGUMENT", "Requested samplingPeriodUs is below the artifact's declared minimumIntervalUs.");
        for (Work existing : subscriptions.values()) if (existing.request.kind.equals(request.kind))
            throw new FactoryException("BUSY", "This page already owns a subscription for the requested sensor kind.");
        try {
            if (!driver.available(request.kind)) throw unavailable();
            if (!driver.permitted(request.kind, request.samplingPeriodUs)) throw permissionDenied();
        } catch (SecurityException denied) { throw permissionDenied(); }
        catch (RuntimeException failed) { throw unavailable(); }
        // Allocate bounded data and an unpredictable handle before reserving a process slot.
        Work work = new Work(request, authority, clock.nanos());
        if (!admissions.tryAcquire()) throw new FactoryException("BUSY", "The process sensor-listener working set is full.");
        work.admitted = true; subscriptions.put(work.id, work);
        FactoryException failure = null;
        try {
            // create() has no registration side effects; retain the handle before register().
            work.registration = driver.create(request.kind);
            if (work.registration == null) throw unavailable();
            check(work);
            work.registering = true;
            boolean registered;
            try { registered = work.registration.register(request.samplingPeriodUs, (timestamp, values) -> sample(work, timestamp, values)); }
            finally { work.registering = false; }
            if (!registered) throw unavailable();
            check(work);
            if (!handler.postDelayed(work.tick, AUTHORIZATION_POLL_MS)) throw unavailable();
            JSONObject result = metadata(work);
            ensureSize(result);
            check(work);
            return result;
        } catch (FactoryException failed) { failure = failed; }
        catch (SecurityException denied) { failure = permissionDenied(); }
        catch (RuntimeException | JSONException failed) { failure = unavailable(); }
        // register() may partly succeed even when it returns false or throws. Always unregister.
        if (!stop(work)) throw cleanupError();
        throw failure;
    }

    /** Drain only after the complete bounded result and its authority have been verified. */
    public synchronized JSONObject poll(SensorSubscriptionRequest.Handle request) throws FactoryException {
        Work work = owned(request);
        try {
            check(work);
            long polledAtNs = clock.nanos();
            if (polledAtNs < work.startedNs || polledAtNs < work.lastTimestamp) throw unavailable();
            JSONArray samples = new JSONArray();
            for (int i = 0; i < work.size; i++) {
                int slot = (work.head + i) % work.capacity;
                JSONArray values = new JSONArray();
                for (int j = 0; j < work.dimensions; j++) values.put((double) work.values[slot][j]);
                samples.put(new JSONObject().put("sequence", Long.toString(work.sequence[slot]))
                        .put("timestampNs", Long.toString(work.timestamp[slot]))
                        .put("elapsedMs", (work.timestamp[slot] - work.startedNs) / 1000000L)
                        .put("ageMs", (polledAtNs - work.timestamp[slot]) / 1000000L)
                        .put("eventBeforeSubscription", work.timestamp[slot] < work.startedNs).put("values", values));
            }
            JSONObject result = metadata(work).put("samples", samples).put("receivedSamples", Long.toString(work.received))
                    .put("droppedSamples", Long.toString(work.dropped)).put("invalidSamples", Long.toString(work.invalid))
                    .put("countersSaturated", work.countersSaturated).put("active", true);
            ensureSize(result);
            check(work);
            work.clear();
            return result;
        } catch (FactoryException failure) {
            if (!stop(work)) throw cleanupError();
            throw failure;
        } catch (RuntimeException | JSONException failure) {
            if (!stop(work)) throw cleanupError();
            throw unavailable();
        }
    }

    /** No grant is needed to clean up this helper's owned handle. Never fabricates cleanup success. */
    public synchronized JSONObject unsubscribe(SensorSubscriptionRequest.Handle request) throws FactoryException {
        Work work = owned(request);
        if (!stop(work)) throw cleanupError();
        try { return new JSONObject().put("subscriptionId", work.id).put("unsubscribed", true); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    /** False means one or more unregisters are unconfirmed and their admissions remain held. */
    public synchronized boolean cancelAll() {
        boolean complete = true;
        for (Work work : new ArrayList<>(subscriptions.values())) if (!stop(work)) complete = false;
        return complete;
    }
    public synchronized boolean hasUnconfirmedCleanup() {
        for (Work work : subscriptions.values()) if (work.cancelled) return true;
        return false;
    }
    @Override public synchronized void close() { closed = true; cancelAll(); }

    private Work owned(SensorSubscriptionRequest.Handle request) throws FactoryException {
        if (request == null) throw new FactoryException("INVALID_ARGUMENT", "A typed subscription handle is required.");
        Work work = subscriptions.get(request.subscriptionId);
        if (work == null) throw new FactoryException("INVALID_HANDLE", "Sensor subscription is unknown, ended or belongs to another page.");
        return work;
    }
    private void check(Work work) throws FactoryException {
        if (work.cancelled) throw cancelled();
        if (closed || subscriptions.get(work.id) != work || !active(work.authority)) throw cancelled();
        if (work.cancelled || closed || subscriptions.get(work.id) != work) throw cancelled();
        if (!driver.permitted(work.request.kind, work.request.samplingPeriodUs)) throw permissionDenied();
        // Authority/grant seams may synchronously revoke or close the helper while being checked.
        if (work.cancelled || closed || subscriptions.get(work.id) != work) throw cancelled();
    }
    private static boolean active(Authority authority) {
        try { return authority.active(); } catch (RuntimeException revoked) { return false; }
    }

    private synchronized void watchdog(Work work) {
        if (subscriptions.get(work.id) != work || work.cancelled) return;
        try {
            check(work);
            if (!handler.postDelayed(work.tick, AUTHORIZATION_POLL_MS)) stop(work);
        } catch (FactoryException | RuntimeException ended) { stop(work); }
    }
    private synchronized void sample(Work work, long timestampNs, float[] values) {
        if (subscriptions.get(work.id) != work || work.cancelled) return;
        try { check(work); }
        catch (FactoryException | RuntimeException ended) { stop(work); return; }
        long now = clock.nanos();
        // Android may replay the actual most recent event when an on-change light sensor is
        // already active. Keep its original timestamp and disclose its age; never invent a reading.
        boolean initialLight = "light".equals(work.request.kind) && work.lastTimestamp < 0;
        if (now < work.startedNs || timestampNs <= 0 || (!initialLight && timestampNs < work.startedNs) || timestampNs > now
                || timestampNs <= work.lastTimestamp || values == null || values.length < work.dimensions) {
            work.invalid = increment(work, work.invalid); return;
        }
        for (int i = 0; i < work.dimensions; i++) if (Float.isNaN(values[i]) || Float.isInfinite(values[i])) {
            work.invalid = increment(work, work.invalid); return;
        }
        work.lastTimestamp = timestampNs;
        work.received = increment(work, work.received);
        if (work.size == work.capacity) {
            work.head = (work.head + 1) % work.capacity;
            work.size--;
            work.dropped = increment(work, work.dropped);
        }
        int slot = (work.head + work.size) % work.capacity;
        work.timestamp[slot] = timestampNs;
        work.sequence[slot] = work.received;
        System.arraycopy(values, 0, work.values[slot], 0, work.dimensions);
        work.size++;
    }

    /** The listener remains owned until unregister returns, including reentrant register paths. */
    private boolean stop(Work work) {
        work.cancelled = true;
        if (!work.terminalNotified) { work.terminalNotified = true; try { work.authority.ended(); } catch (RuntimeException ignored) { } }
        handler.removeCallbacks(work.tick);
        work.clear();
        // A callback may cancel synchronously from inside register(). Cleanup must occur afterward.
        if (work.registering || work.cleaning) return false;
        if (!work.admitted) return true;
        work.cleaning = true;
        try {
            if (work.registration != null) work.registration.unregister();
        } catch (RuntimeException unconfirmed) { return false; }
        finally { work.cleaning = false; }
        work.registration = null;
        subscriptions.remove(work.id);
        work.admitted = false;
        admissions.release();
        return true;
    }
    private static long increment(Work work, long value) {
        if (value == Long.MAX_VALUE) { work.countersSaturated = true; return Long.MAX_VALUE; }
        return value + 1;
    }
    private JSONObject metadata(Work work) throws JSONException {
        String kind = work.request.kind;
        return new JSONObject().put("subscriptionId", work.id).put("kind", kind)
                .put("unit", "light".equals(kind) ? "lux" : "gyroscope".equals(kind) ? "rad/s" : "m/s2")
                .put("frame", "light".equals(kind) ? "scalar" : "device-natural")
                .put("includesGravity", "accelerometer".equals(kind))
                .put("timestampClock", "elapsedRealtimeNanos")
                .put("requestedSamplingPeriodUs", work.request.samplingPeriodUs)
                .put("bufferCapacity", work.capacity).put("foregroundOnly", true)
                .put("rateGuaranteed", false).put("mode", "installed").put("simulated", false);
    }
    private static void ensureSize(JSONObject value) throws FactoryException {
        if (value.toString().getBytes(StandardCharsets.UTF_8).length > ResourceBudgets.SENSOR_RESULT_BYTES)
            throw new FactoryException("TOO_LARGE", "Sensor poll exceeds the bounded transport envelope.");
    }
    private final class Work {
        final String id = UUID.randomUUID().toString();
        final SensorSubscriptionRequest.Subscribe request;
        final Authority authority;
        boolean terminalNotified;
        final long startedNs;
        final int capacity = budget.bufferedSamples, dimensions;
        final long[] timestamp = new long[capacity], sequence = new long[capacity];
        final float[][] values;
        final Runnable tick = () -> watchdog(this);
        Registration registration;
        boolean admitted, registering, cleaning, cancelled, countersSaturated;
        int head, size;
        long received, dropped, invalid, lastTimestamp = -1;
        Work(SensorSubscriptionRequest.Subscribe request, Authority authority, long startedNs) {
            this.request = request; this.authority = authority; this.startedNs = startedNs;
            dimensions = "light".equals(request.kind) ? 1 : 3;
            values = new float[capacity][dimensions];
        }
        void clear() {
            Arrays.fill(timestamp, 0); Arrays.fill(sequence, 0);
            for (float[] row : values) Arrays.fill(row, 0);
            head = 0; size = 0;
        }
    }

    static Driver android(Context context, Handler handler) {
        final SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        return new Driver() {
            private Sensor sensor(String kind) {
                return manager == null ? null : manager.getDefaultSensor("light".equals(kind) ? Sensor.TYPE_LIGHT
                        : "gyroscope".equals(kind) ? Sensor.TYPE_GYROSCOPE : Sensor.TYPE_ACCELEROMETER);
            }
            @Override public boolean available(String kind) { return sensor(kind) != null; }
            @Override public boolean permitted(String kind, int periodUs) {
                // The restriction targets 31+ apps on API31+. Light is not a restricted motion sensor.
                return Build.VERSION.SDK_INT < 31 || context.getApplicationInfo().targetSdkVersion < 31
                        || "light".equals(kind) || periodUs >= 5000
                        || context.checkSelfPermission(HIGH_RATE_PERMISSION) == PackageManager.PERMISSION_GRANTED;
            }
            @Override public Registration create(String kind) {
                final Sensor selected = sensor(kind);
                return new Registration() {
                    private SensorEventListener listener;
                    @Override public boolean register(int periodUs, Events events) {
                        if (selected == null || manager == null) return false;
                        listener = new SensorEventListener() {
                            @Override public void onSensorChanged(SensorEvent event) {
                                if (event.sensor == selected) events.sample(event.timestamp, event.values);
                            }
                            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
                        };
                        // No batching, adjustment, fabricated reads or requested-rate guarantee.
                        return manager.registerListener(listener, selected, periodUs, 0, handler);
                    }
                    @Override public void unregister() {
                        if (manager != null && listener != null) manager.unregisterListener(listener);
                        listener = null; // Keep the exact listener if unregister throws, for a real retry.
                    }
                };
            }
        };
    }
    private static FactoryException unavailable() { return new FactoryException("UNAVAILABLE", "Sensor subscription is unavailable."); }
    private static FactoryException cancelled() { return new FactoryException("CANCELLED", "Sensor subscription requires its current foreground page and live policy authority."); }
    private static FactoryException permissionDenied() { return new FactoryException("PERMISSION_DENIED", "The requested sensor sampling rate requires the actual Android HIGH_SAMPLING_RATE_SENSORS grant."); }
    private static FactoryException cleanupError() { return new FactoryException("UNAVAILABLE", "Sensor cleanup is unconfirmed; data discarded and listener admission retained until unregister succeeds."); }
}
