package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ResourceBudgets;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Synthetic PCM only: verifies admission, accounting and ownership across bridge delivery. */
public class RecordingEffectsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final AtomicBoolean allowed = new AtomicBoolean(true), foreground = new AtomicBoolean(true);
    private final Scheduler scheduler = new Scheduler();
    private final Queue<Runnable> cleanup = new ConcurrentLinkedQueue<>();
    private final List<Driver> made = new ArrayList<>();
    private final NativeOperationAuthorization.Access access = () -> { if (!allowed.get()) throw new SecurityException("revoked"); };
    private DocumentHandles documents;
    private RecordingEffects effects;
    private File directory;
    private Result result;
    private boolean failRelease;

    @Before public void setup() throws Exception { configure(100, 100, 1000); }
    private void configure(long handleBytes, long sessionBytes, long leaseMs) throws Exception {
        if (effects != null) { effects.close(); scheduler.runDue(); documents.revokeAll(); drain(); }
        Map<String, Object> documentBudget = new HashMap<>();
        documentBudget.put("openHandles", 1); documentBudget.put("handleBytes", handleBytes);
        documentBudget.put("sessionBytes", sessionBytes); documentBudget.put("leaseMs", leaseMs);
        Map<String, Object> declaration = new HashMap<>();
        declaration.put("version", 1); declaration.put("documents", documentBudget);
        ResourceBudgets budgets = ResourceBudgets.parse(declaration);
        documents = new DocumentHandles(() -> scheduler.now, cleanup::add, budgets);
        directory = temporary.newFolder();
        NativeAudioRecording recorder = new NativeAudioRecording(directory, request -> {
            Driver driver = new Driver(request.sampleRate, request.channels, failRelease); made.add(driver); return driver;
        }, new NativeAudioRecording.Permission() {
            public boolean granted() { return true; }
            public boolean available() { return true; }
        }, scheduler, () -> scheduler.now);
        effects = new RecordingEffects(recorder, () -> documents, budgets, cleanup::add);
        result = new Result();
    }
    @After public void finish() {
        effects.close(); scheduler.runDue(); drain(); documents.revokeAll(); drain();
    }
    private RecordingEffects.Handle record() throws Exception {
        return effects.record(RecordingRequest.parseRecord("{\"durationMs\":100,\"maxBytes\":48}"), access, foreground::get, result);
    }
    private int files() { String[] names = directory.list(); return names == null ? 0 : names.length; }
    private void drain() { Runnable task; while ((task = cleanup.poll()) != null) task.run(); }
    private void quota(long bytes) {
        assertEquals("DOCUMENT_QUOTA", assertThrows(FactoryException.class, () -> documents.reserveNativeBytes(bytes)).code);
    }
    private void slotHeld() {
        assertEquals("HANDLE_LIMIT", assertThrows(FactoryException.class, () -> documents.reserveRead()).code);
    }

    @Test public void deniedAccessReservesNothingAndNeverCreatesDriver() throws Exception {
        allowed.set(false);
        assertThrows(SecurityException.class, this::record);
        scheduler.runDue(); assertTrue(made.isEmpty()); assertEquals(0, files());
        DocumentHandles.NativeByteReservation bytes = documents.reserveNativeBytes(100); bytes.close();
        documents.reserveRead().cancel();
    }
    @Test public void overBudgetAndLeaseDurationFailBeforeNativeCreation() throws Exception {
        configure(47, 47, 1000);
        assertEquals("DOCUMENT_QUOTA", assertThrows(FactoryException.class, this::record).code);
        documents.reserveRead().cancel();
        configure(100, 100, 100);
        assertEquals("DOCUMENT_QUOTA", assertThrows(FactoryException.class, this::record).code);
        documents.reserveRead().cancel(); assertTrue(made.isEmpty()); assertEquals(0, files());
    }
    @Test public void acceptedCaptureHoldsSlotAndMaximumBytesBeforeMicrophoneStarts() throws Exception {
        record(); assertTrue(made.isEmpty()); slotHeld(); quota(53);
        assertTrue(effects.status().getBoolean("busy"));
        assertEquals("BUSY", assertThrows(FactoryException.class, this::record).code);
    }
    @Test public void cancellationDoesNotRefundUntilWorkerConfirmsCleanup() throws Exception {
        RecordingEffects.Handle handle = record();
        allowed.set(false); foreground.set(false); assertTrue(handle.cancel());
        slotHeld(); quota(53); assertEquals(0, result.errors);
        scheduler.runDue();
        assertEquals("CANCELLED", result.failure.code); assertEquals(1, result.errors);
        assertEquals(0, files()); assertTrue(made.isEmpty());
        documents.reserveRead().cancel(); documents.reserveNativeBytes(100).close();
    }
    @Test public void nativeCleanupFailurePermanentlyRetainsBothClaims() throws Exception {
        failRelease = true; record(); scheduler.runDue();
        assertEquals("CLEANUP_FAILED", result.failure.code); assertNull(result.value);
        assertFalse(effects.status().getBoolean("cleanupConfirmed"));
        slotHeld(); quota(53);
        assertEquals("CLEANUP_FAILED", assertThrows(FactoryException.class, this::record).code);
    }
    @Test public void successfulDeliveryGivesOnlyReadHandleAndChargesAcquisitionAndReadsSeparately() throws Exception {
        record(); scheduler.runDue(); assertNotNull(result.value); assertEquals(1, files());
        assertFalse(result.value.has("path")); assertFalse(result.value.has("uri"));
        assertEquals(48, result.value.getLong("bytes")); assertFalse(result.value.getBoolean("contentVerified"));
        String token = result.value.getString("handle"); assertTrue(token.matches("[0-9a-f]{64}"));
        result.delivered.run();
        assertEquals("WRONG_MODE", assertThrows(FactoryException.class, () -> documents.write(token, 0, "AA==")).code);
        assertEquals(48, documents.read(token, 0, 48).nextOffset);
        documents.reserveNativeBytes(4).close(); quota(5);
        documents.close(token); slotHeld(); drain(); assertEquals(0, files());
        documents.reserveRead().cancel(); quota(5); // Neither acquisition nor actual reads are refunded.
    }
    @Test public void deliveryRejectionClosesHandleAsynchronouslyWithoutRefundingAcquiredBytes() throws Exception {
        record(); scheduler.runDue(); String token = result.value.getString("handle");
        result.rejected.run(); assertEquals(1, files()); slotHeld();
        drain(); assertEquals(0, files()); documents.reserveRead().cancel();
        assertEquals("INVALID_HANDLE", assertThrows(FactoryException.class, () -> documents.read(token, 0, 1)).code);
        documents.reserveNativeBytes(52).close(); quota(53);
    }
    @Test public void stalePageAfterCaptureBeforeDeliveryIsDiscardedAndCannotOwnNewPageSlot() throws Exception {
        record(); scheduler.runNext(); scheduler.runNext();
        assertEquals(1, files()); assertNull(result.value);
        documents = documents.revokeAndRenew();
        slotHeld(); scheduler.runDue(); drain();
        assertEquals(0, files()); assertNull(result.value);
        assertEquals("CANCELLED", result.failure.code);
        documents.reserveRead().cancel(); documents.reserveNativeBytes(100).close();
    }
    @Test public void expiryBetweenNativeDeliveryAndDocumentGrantDiscardsOwnedOutput() throws Exception {
        configure(100, 100, 101);
        // The supplier remains the same, so this exercises grant/commit expiration separately
        // from the exact-page guard. No real capture is delayed or opened.
        record(); scheduler.runNext(); scheduler.runNext(); scheduler.now = 101;
        scheduler.runDue(); drain();
        assertNull(result.value); assertEquals("INVALID_HANDLE", result.failure.code);
        assertEquals(0, files()); documents.reserveRead().cancel(); documents.reserveNativeBytes(100).close();
    }
    @Test public void deliveryCallbackExceptionStillTransfersOwnershipAndDiscardsOnWorker() throws Exception {
        result.throwOnComplete = true; record(); scheduler.runDue(); drain();
        assertEquals(0, files()); assertNotNull(result.failure);
        documents.reserveRead().cancel(); quota(53);
        assertFalse(effects.status().getBoolean("busy"));
    }
    @Test public void stopFinalizesExistingCaptureAndOldControlCannotCancelDeliveredDocument() throws Exception {
        configure(100, 100, 10000);
        RecordingEffects.Handle handle = effects.record(RecordingRequest.parseRecord("{\"durationMs\":1000,\"maxBytes\":100}"),
                access, foreground::get, result);
        scheduler.runDue(); assertTrue(handle.stop()); scheduler.runDue();
        assertEquals("stopped", result.value.getString("reason"));
        result.delivered.run(); assertFalse(handle.cancel()); assertFalse(handle.stop());
        effects.close(); scheduler.runDue(); assertEquals(1, files());
        documents.close(result.value.getString("handle")); drain(); assertEquals(0, files());
    }

    private static final class Result implements RecordingEffects.Callback {
        JSONObject value; FactoryException failure; Runnable delivered, rejected; int errors; boolean throwOnComplete;
        public boolean current() { return true; }
        public void complete(JSONObject value, Runnable delivered, Runnable rejected) {
            this.value = value; this.delivered = delivered; this.rejected = rejected;
            if (throwOnComplete) throw new IllegalStateException("synthetic delivery failure");
        }
        public void error(FactoryException failure) { this.failure = failure; errors++; }
    }
    private static final class Driver implements NativeAudioRecording.Driver {
        final int sampleRate, channels; final boolean failRelease; boolean started;
        Driver(int sampleRate, int channels, boolean failRelease) {
            this.sampleRate = sampleRate; this.channels = channels; this.failRelease = failRelease;
        }
        public boolean initialized() { return true; }
        public int sampleRate() { return sampleRate; }
        public int channels() { return channels; }
        public void start() { started = true; }
        public boolean recording() { return started; }
        public int read(short[] samples, int max) { int count = Math.min(max, 2); for (int i=0;i<count;i++) samples[i] = 7; return count; }
        public void stop() { started = false; }
        public void release() { if (failRelease) throw new IllegalStateException("synthetic release failure"); }
    }
    private static final class Scheduler implements NativeAudioRecording.Scheduler {
        long now, sequence;
        private static final class Task {
            final Runnable action; final long due, sequence;
            Task(Runnable action, long due, long sequence) { this.action=action; this.due=due; this.sequence=sequence; }
        }
        final List<Task> tasks = new ArrayList<>();
        public void post(Runnable action, long delay) { tasks.add(new Task(action, now+delay, sequence++)); }
        public void remove(Runnable action) { tasks.removeIf(task -> task.action == action); }
        public void close() { }
        Task next() { return tasks.stream().min(Comparator.comparingLong((Task task) -> task.due).thenComparingLong(task -> task.sequence)).orElse(null); }
        void runNext() { Task task=next(); if (task == null) throw new AssertionError("No task queued"); tasks.remove(task); now=Math.max(now,task.due); task.action.run(); }
        void runDue() { while (next() != null && next().due <= now) runNext(); }
    }
}
