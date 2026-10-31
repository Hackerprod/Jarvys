package com.jarvys.factory.runtime;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Every capture uses fake PCM, permission, clock and scheduler. No live mic, network or Android UI. */
public class NativeAudioRecordingTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final AtomicBoolean allowed = new AtomicBoolean(true), foreground = new AtomicBoolean(true);
    private final List<FakeDriver> made = new ArrayList<>();
    private final List<OwnedRecording> owned = new ArrayList<>();
    private final FakeScheduler scheduler = new FakeScheduler();
    private final FakePermission permission = new FakePermission();
    private File directory;
    private NativeAudioRecording recorder;
    private Result result;
    private Runnable onCreate = () -> { };
    private FakeDriver next;

    @Before public void setup() throws Exception {
        directory = temporary.newFolder("capture");
        recorder = new NativeAudioRecording(directory, request -> {
            FakeDriver driver = next == null ? new FakeDriver() : next;
            next = null; driver.sampleRate = request.sampleRate; driver.channels = request.channels;
            made.add(driver); onCreate.run(); return driver;
        }, permission, scheduler, () -> scheduler.now);
        result = new Result();
    }
    @After public void cleanup() throws Exception {
        recorder.close(); scheduler.runDue();
        for (OwnedRecording value : owned) value.close();
    }
    private NativeOperationAuthorization.Access access() {
        return () -> { if (!allowed.get()) throw new SecurityException("revoked"); };
    }
    private NativeAudioRecording.Handle record(String json) throws Exception {
        return recorder.record(RecordingRequest.parseRecord(json), access(), foreground::get, result);
    }
    private int files() { return directory.list() == null ? 0 : directory.list().length; }

    @Test public void statusNeverConstructsOrStartsMicrophone() throws Exception {
        assertEquals("idle", recorder.status().getString("status")); assertTrue(made.isEmpty());
        assertTrue(recorder.status().getBoolean("foregroundOnly"));
        assertFalse(recorder.status().getBoolean("networkUsed"));
        assertFalse(recorder.status().getBoolean("contentVerified")); assertEquals(0, files());
    }
    @Test public void missingLeasePermissionOrForegroundPreventsNativeEffects() throws Exception {
        assertThrows(SecurityException.class, () -> recorder.record(RecordingRequest.parseRecord("{}"), null, () -> true, result));
        allowed.set(false); assertThrows(SecurityException.class, () -> record("{}")); allowed.set(true);
        foreground.set(false); assertEquals("CANCELLED", assertThrows(FactoryException.class, () -> record("{}")).code);
        foreground.set(true); permission.granted = false;
        assertEquals("PERMISSION_DENIED", assertThrows(FactoryException.class, () -> record("{}")).code);
        scheduler.runDue(); assertTrue(made.isEmpty()); assertEquals(0, files()); assertEquals(0, result.calls);
    }
    @Test public void unavailableHardwareFailsBeforeFileOrDriverCreation() {
        permission.available = false;
        assertEquals("RECORDING_UNAVAILABLE", assertThrows(FactoryException.class, () -> record("{}")).code);
        assertTrue(made.isEmpty()); assertEquals(0, files());
    }
    @Test public void revocationBetweenAcceptanceAndInitializationCreatesNothing() throws Exception {
        record("{}"); allowed.set(false); scheduler.runDue();
        assertEquals("PERMISSION_DENIED", result.error.code); assertEquals(1, result.calls);
        assertTrue(made.isEmpty()); assertEquals(0, files());
    }
    @Test public void revocationAfterDriverConstructionPreventsStartAndReleasesIt() throws Exception {
        onCreate = () -> allowed.set(false);
        record("{}"); scheduler.runDue();
        assertEquals(0, made.get(0).starts); assertEquals(1, made.get(0).releases);
        assertEquals("PERMISSION_DENIED", result.error.code); assertEquals(0, files());
    }
    @Test public void validCaptureIsCanonicalWavAndFileBackedWithDeleteOnCloseOwnership() throws Exception {
        record("{\"durationMs\":100}"); scheduler.runDue(); scheduler.advance(100);
        assertEquals(1, result.calls); assertNull(result.error); assertEquals(1, files());
        JSONObject metadata = result.value.metadata();
        assertEquals("duration", metadata.getString("reason")); assertEquals("audio/wav", metadata.getString("mimeType"));
        assertEquals(1, made.get(0).stops); assertEquals(1, made.get(0).releases);
        assertFalse(metadata.toString().contains(directory.getAbsolutePath()));
        assertFalse(metadata.has("path")); assertFalse(metadata.has("uri"));
        assertFalse(metadata.getBoolean("contentVerified")); assertFalse(metadata.getBoolean("networkUsed"));
        InputStream stream = result.value.openReadOnce();
        byte[] header = new byte[44]; assertEquals(44, stream.read(header));
        assertEquals("RIFF", new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("WAVEfmt ", new String(header, 8, 8, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("data", new String(header, 36, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(metadata.getLong("bytes") - 8, u32(header, 4));
        assertEquals(metadata.getLong("bytes") - 44, u32(header, 40));
        assertEquals(16000, u32(header, 24)); assertEquals(1, header[22]); assertEquals(16, header[34]);
        assertEquals(0, stream.read()); assertEquals(0x12, stream.read());
        assertThrows(IOException.class, () -> result.value.openReadOnce());
        stream.close(); stream.close(); assertEquals(0, files());
        assertThrows(IOException.class, () -> result.value.openReadOnce());
    }
    @Test public void byteLimitIsExactIncludingHeaderAndCannotReadBeyondTheLimit() throws Exception {
        record("{\"maxBytes\":53,\"channels\":2}"); scheduler.runDue();
        assertEquals(52, result.value.metadata().getLong("bytes"));
        assertEquals("byte_limit", result.value.metadata().getString("reason"));
        assertEquals(4, made.get(0).maximumRead); assertEquals(1, made.get(0).reads);
        assertEquals(1, made.get(0).stops); assertEquals(1, result.calls);
    }
    @Test public void sampledDurationCannotExceedTheRequestEvenWithInstantFakePcm() throws Exception {
        next = new FakeDriver(); next.samplesPerRead = 4096;
        record("{\"durationMs\":100,\"sampleRate\":8000}"); scheduler.runDue();
        assertEquals(1644, result.value.metadata().getLong("bytes"));
        assertEquals(100, result.value.metadata().getLong("durationMs"));
        assertEquals(800, made.get(0).maximumRead);
    }
    @Test public void explicitStopFinalizesEarlyWithOneExistingAuthorization() throws Exception {
        NativeAudioRecording.Handle handle = record("{}"); scheduler.runDue(); scheduler.advance(40);
        assertTrue(handle.stop()); assertFalse(handle.stop()); scheduler.runDue();
        assertEquals("stopped", result.value.metadata().getString("reason")); assertEquals(1, result.calls);
        assertTrue(result.value.metadata().getLong("durationMs") < 10000);
        assertFalse(handle.cancel()); assertFalse(handle.stop());
    }
    @Test public void stopBeforeInitializationCannotStartTheMicrophoneOrCreateEmptySuccess() throws Exception {
        NativeAudioRecording.Handle handle = record("{}"); assertTrue(handle.stop()); scheduler.runDue();
        assertTrue(made.isEmpty()); assertEquals("RECORDING_EMPTY", result.error.code);
        assertEquals(0, files()); assertEquals(1, result.calls);
    }
    @Test public void stopCannotDeliverAfterPermissionOrLeaseRevocation() throws Exception {
        NativeAudioRecording.Handle handle = record("{}"); scheduler.runDue();
        allowed.set(false); assertTrue(handle.stop()); scheduler.runDue();
        assertEquals("PERMISSION_DENIED", result.error.code); assertEquals(0, files());
        assertEquals(1, made.get(0).stops); assertEquals(1, made.get(0).releases);
    }
    @Test public void cancellationRequiresNoGrantAndDiscardsThePartialFile() throws Exception {
        NativeAudioRecording.Handle handle = record("{}"); scheduler.runDue(); assertEquals(1, files());
        allowed.set(false); permission.granted = false; foreground.set(false);
        assertTrue(handle.cancel()); assertFalse(handle.cancel()); scheduler.runDue(); scheduler.advance(1000);
        assertEquals("CANCELLED", result.error.code); assertEquals(0, files()); assertEquals(1, result.calls);
        assertEquals(1, made.get(0).stops); assertEquals(1, made.get(0).releases);
    }
    @Test public void foregroundLossAndActualPermissionRevocationArePolled() throws Exception {
        record("{}"); scheduler.runDue(); foreground.set(false); scheduler.advance(20);
        assertEquals("CANCELLED", result.error.code); assertEquals(0, files());
        foreground.set(true); result = new Result(); record("{}"); scheduler.runDue();
        permission.granted = false; scheduler.advance(20);
        assertEquals("PERMISSION_DENIED", result.error.code); assertEquals(0, files());
    }
    @Test public void revocationDuringPlatformReadDropsTheReturnedSamples() throws Exception {
        next = new FakeDriver(); next.onRead = () -> allowed.set(false);
        record("{}"); scheduler.runDue();
        assertEquals("PERMISSION_DENIED", result.error.code); assertEquals(0, files());
        assertEquals(1, made.get(0).reads); assertEquals(1, made.get(0).releases);
    }
    @Test public void staleCompletionIsCheckedAgainBeforeCallbackDelivery() throws Exception {
        record("{\"maxBytes\":48}"); scheduler.runNext(); scheduler.runNext();
        assertEquals("finalizing", recorder.status().getString("status")); assertEquals(0, result.calls);
        allowed.set(false); scheduler.runDue();
        assertEquals("PERMISSION_DENIED", result.error.code); assertNull(result.value); assertEquals(0, files());
    }
    @Test public void cancelBetweenFinalizationAndDeliveryStillDiscardsResult() throws Exception {
        NativeAudioRecording.Handle handle = record("{\"maxBytes\":48}");
        scheduler.runNext(); scheduler.runNext(); assertEquals(0, result.calls);
        assertTrue(handle.cancel()); scheduler.runDue();
        assertEquals("CANCELLED", result.error.code); assertEquals(0, files()); assertEquals(1, result.calls);
    }
    @Test public void callbackRejectionOrExceptionAutomaticallyClosesUnacceptedArtifacts() throws Exception {
        result.accept = false; record("{\"maxBytes\":48}"); scheduler.runDue();
        assertEquals(1, result.calls); assertEquals(0, files()); assertNull(result.error);
        assertEquals("DELIVERY_REJECTED", recorder.status().getString("error"));
        result = new Result(); result.throwOnComplete = true;
        record("{\"maxBytes\":48}"); scheduler.runDue();
        assertEquals(1, result.calls); assertEquals(0, files()); assertNull(result.error);
    }
    @Test public void busyAndOldHandlesDoNotAffectNewRecording() throws Exception {
        NativeAudioRecording.Handle previous = record("{}"); scheduler.runDue();
        assertEquals("BUSY", assertThrows(FactoryException.class, () -> record("{}")).code);
        assertTrue(previous.cancel()); scheduler.runDue();
        result = new Result(); NativeAudioRecording.Handle current = record("{}"); scheduler.runDue();
        assertFalse(previous.cancel()); assertFalse(previous.stop()); assertEquals(0, made.get(1).stops);
        assertTrue(current.cancel()); scheduler.runDue(); assertEquals(1, made.get(1).stops);
    }
    @Test public void partialStartAndReadErrorsReleaseAndDeleteWithoutNativeExceptionLeaks() throws Exception {
        next = new FakeDriver(); next.failStart = true;
        record("{}"); scheduler.runDue();
        assertEquals("RECORDING_UNAVAILABLE", result.error.code); assertEquals(1, made.get(0).releases);
        assertFalse(result.error.getMessage().contains("private")); assertEquals(0, files());
        result = new Result(); next = new FakeDriver(); next.samplesPerRead = -6;
        record("{}"); scheduler.runDue();
        assertEquals("RECORDING_ERROR", result.error.code); assertEquals(0, files());
    }
    @Test public void emptyCaptureAndOddStereoFramesAreErrorsNotSuccessfulRecordings() throws Exception {
        next = new FakeDriver(); next.samplesPerRead = 0;
        record("{\"durationMs\":100}"); scheduler.runDue(); scheduler.advance(100);
        assertEquals("RECORDING_EMPTY", result.error.code); assertEquals(0, files());
        result = new Result(); next = new FakeDriver(); next.samplesPerRead = 3;
        record("{\"channels\":2}"); scheduler.runDue();
        assertEquals("RECORDING_ERROR", result.error.code); assertEquals(0, files());
    }
    @Test public void unknownDriverReleaseQuarantinesHelperAndNeverReportsSuccess() throws Exception {
        next = new FakeDriver(); next.failRelease = true;
        record("{\"maxBytes\":48}"); scheduler.runDue();
        assertEquals("CLEANUP_FAILED", result.error.code); assertNull(result.value); assertEquals(0, files());
        assertFalse(recorder.status().getBoolean("cleanupConfirmed"));
        assertEquals("CLEANUP_FAILED", assertThrows(FactoryException.class, () -> record("{}")).code);
        assertEquals(1, made.size());
    }
    @Test public void closeIsIdempotentAndCancelsWithoutFreshPermission() throws Exception {
        record("{}"); scheduler.runDue(); allowed.set(false); recorder.close(); recorder.close(); scheduler.runDue();
        assertEquals("CANCELLED", result.error.code); assertEquals(1, made.get(0).releases);
        assertEquals(0, files()); assertEquals("closed", recorder.status().getString("status"));
        allowed.set(true); assertEquals("CANCELLED", assertThrows(FactoryException.class, () -> record("{}")).code);
    }
    @Test public void queuedInitializationAfterImmediateCloseCannotConstructDriver() throws Exception {
        record("{}"); recorder.close(); scheduler.runDue();
        assertTrue(made.isEmpty()); assertEquals(0, files()); assertEquals(1, result.calls);
    }
    @Test public void cancelReturnsWhileDriverReadIsBlockedAndRetainsAdmissionUntilCleanup() throws Exception {
        blockedReadControl(false);
    }
    @Test public void closeReturnsWhileDriverReadIsBlockedAndNeverCleansUpOnControlThread() throws Exception {
        blockedReadControl(true);
    }
    private void blockedReadControl(boolean close) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        next = new FakeDriver(); next.onRead = () -> {
            entered.countDown();
            try { if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("Read was not resumed"); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        };
        NativeAudioRecording.Handle handle = record("{}"); scheduler.runNext(); // Initialize only.
        Thread worker = new Thread(scheduler::runNext, "fake-recording-worker");
        ExecutorService controls = Executors.newSingleThreadExecutor(r -> new Thread(r, "fake-control-thread"));
        worker.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(controls.submit(() -> {
                if (close) { recorder.close(); return true; }
                return handle.cancel();
            }).get(1, TimeUnit.SECONDS));
            assertTrue(recorder.status().getBoolean("busy"));
            assertEquals(0, made.get(0).releases);
            assertEquals(0, result.calls);
            assertEquals(close ? "CANCELLED" : "BUSY",
                    assertThrows(FactoryException.class, () -> record("{}")).code);
        } finally {
            resume.countDown(); worker.join(2000); controls.shutdownNow();
        }
        assertFalse(worker.isAlive()); scheduler.runDue();
        assertEquals("CANCELLED", result.error.code); assertEquals(1, result.calls);
        assertEquals(0, files()); assertEquals("fake-recording-worker", made.get(0).cleanupThread);
    }

    private final class Result implements NativeAudioRecording.Callback {
        int calls;
        boolean accept = true, throwOnComplete;
        OwnedRecording value;
        FactoryException error;
        @Override public boolean complete(OwnedRecording value) {
            calls++; this.value = value; owned.add(value);
            if (throwOnComplete) throw new IllegalStateException("rejected");
            return accept;
        }
        @Override public void error(FactoryException error) { calls++; this.error = error; }
    }
    private static final class FakePermission implements NativeAudioRecording.Permission {
        volatile boolean granted = true, available = true;
        @Override public boolean granted() { return granted; }
        @Override public boolean available() { return available; }
    }
    private static final class FakeDriver implements NativeAudioRecording.Driver {
        int sampleRate, channels, starts, stops, releases, reads, maximumRead, samplesPerRead = 256;
        boolean started, failStart, failRelease;
        String cleanupThread;
        Runnable onRead = () -> { };
        @Override public boolean initialized() { return true; }
        @Override public int sampleRate() { return sampleRate; }
        @Override public int channels() { return channels; }
        @Override public void start() {
            starts++; started = true;
            if (failStart) throw new IllegalStateException("private native detail");
        }
        @Override public boolean recording() { return started; }
        @Override public int read(short[] samples, int maximumSamples) {
            reads++; maximumRead = Math.max(maximumRead, maximumSamples);
            int count = Math.min(maximumSamples, samplesPerRead);
            for (int i = 0; i < count; i++) samples[i] = (short) (0x1200 + i);
            onRead.run(); return count;
        }
        @Override public void stop() { stops++; started = false; }
        @Override public void release() {
            cleanupThread = Thread.currentThread().getName();
            releases++; if (failRelease) throw new IllegalStateException("release unknown");
        }
    }
    private static final class FakeScheduler implements NativeAudioRecording.Scheduler {
        long now, sequence;
        private static final class Task {
            Runnable action; long due, order;
            Task(Runnable action, long due, long order) { this.action = action; this.due = due; this.order = order; }
        }
        final List<Task> queue = new ArrayList<>();
        @Override public synchronized void post(Runnable action, long delayMs) { queue.add(new Task(action, now + delayMs, sequence++)); }
        @Override public synchronized void remove(Runnable action) { queue.removeIf(task -> task.action == action); }
        @Override public void close() { }
        private synchronized Task next() {
            return queue.stream().min(Comparator.comparingLong((Task task) -> task.due).thenComparingLong(task -> task.order)).orElse(null);
        }
        void runNext() {
            Task task;
            synchronized (this) {
                task = next(); if (task == null) throw new AssertionError("No queued task");
                queue.remove(task); now = Math.max(now, task.due);
            }
            task.action.run();
        }
        void runDue() { while (next() != null && next().due <= now) runNext(); }
        void advance(long amount) {
            long target = now + amount;
            while (next() != null && next().due <= target) runNext();
            now = target;
        }
    }
    private static long u32(byte[] bytes, int offset) {
        long result = 0;
        for (int i = 0; i < 4; i++) result |= (bytes[offset + i] & 255L) << (i * 8);
        return result;
    }
}
