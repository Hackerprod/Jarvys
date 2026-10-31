package com.jarvys.factory.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NfcSessionMachineTest {
    static final class Tasks implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        public synchronized void execute(Runnable work) { tasks.add(work); }
        synchronized boolean one() { Runnable work = tasks.poll(); if (work == null) return false; work.run(); return true; }
        void all() { int count = 0; while (one()) if (++count > 100) throw new AssertionError("Unbounded queue"); }
    }
    static final class Clock implements NfcSessionMachine.Loop {
        final Tasks immediate = new Tasks(); final List<Later> later = new ArrayList<>(); long now;
        static final class Later { Runnable task; long at; boolean cancelled; }
        public void post(Runnable work) { immediate.execute(work); }
        public synchronized NfcSessionMachine.Ticket later(Runnable work, long delay) {
            Later ticket = new Later(); ticket.task = work; ticket.at = now + delay; later.add(ticket); return () -> ticket.cancelled = true;
        }
        public synchronized long nowMs() { return now; }
        synchronized void advance(long ms) {
            now += ms;
            for (Later ticket : new ArrayList<>(later)) if (!ticket.cancelled && ticket.at <= now) { ticket.cancelled = true; post(ticket.task); }
            immediate.all();
        }
    }
    static final class Backend implements NfcSessionMachine.Backend {
        boolean available = true, enabled = true, permission = true, foreground = true, failEnable, failDisable;
        int enableCount, disableCount; NfcSessionMachine.Discovery callback; Runnable duringEnable;
        public NfcSessionMachine.Readiness readiness() { return new NfcSessionMachine.Readiness(available, enabled, permission, foreground); }
        public NfcSessionMachine.Reader create(NfcSessionMachine.Discovery callback) {
            this.callback = callback;
            return new NfcSessionMachine.Reader() {
                public void enable() { enableCount++; if (duringEnable != null) duringEnable.run(); if (failEnable) throw new IllegalStateException(); }
                public void disable() { disableCount++; if (failDisable) throw new IllegalStateException(); }
            };
        }
        void emit(Tag tag) { callback.found(tag); }
    }
    static class Tag implements NfcSessionMachine.Tag {
        int connects, reads, writes, closes; boolean format, failClose, writable = true; String error;
        Runnable beforeBoundary, afterBoundary, onConnect, onRead, onClose;
        NfcRequest.Message readValue; NfcRequest.Message written; boolean allowFormat;
        Tag() throws Exception { readValue = NfcRequest.write(NfcRequestTest.writeArgs(), NfcRequestTest.DEFAULTS).message; }
        public void connect(boolean write, boolean allowFormat) throws FactoryException { connects++; this.allowFormat = allowFormat; if (onConnect != null) onConnect.run(); if (error != null) throw NfcSessionMachine.error(error); }
        public NfcRequest.Message read(NfcRequest.Limits limits) { reads++; if (onRead != null) onRead.run(); return readValue; }
        public boolean write(NfcRequest.Message message, boolean allowFormat, NfcSessionMachine.WriteBoundary boundary) throws FactoryException {
            if (!writable) throw NfcSessionMachine.error("TAG_READ_ONLY");
            if (beforeBoundary != null) beforeBoundary.run(); boundary.beforeWrite(); writes++; written = message;
            if (afterBoundary != null) afterBoundary.run(); return format;
        }
        public void close() throws FactoryException { closes++; if (onClose != null) onClose.run(); if (failClose) throw NfcSessionMachine.error("CLEANUP_FAILED"); }
    }
    static final class Sink implements NfcSessionMachine.Callback {
        volatile int success, errors; JSONObject result; FactoryException failure;
        public void complete(JSONObject result) { success++; this.result = result; }
        public void error(FactoryException failure) { errors++; this.failure = failure; }
    }
    static final class Harness {
        final Backend backend = new Backend(); final Clock loop = new Clock(); final Tasks io = new Tasks(), closer = new Tasks();
        final Semaphore ledger; final NfcSessionMachine machine; final Sink sink = new Sink();
        volatile boolean allowed = true;
        Harness() { this(new Semaphore(1), null); }
        Harness(Semaphore ledger, Executor executor) {
            this.ledger = ledger; machine = new NfcSessionMachine(backend, loop, executor == null ? io : executor, closer, ledger, NfcRequestTest.DEFAULTS);
        }
        NfcSessionMachine.Handle read() throws Exception { return machine.start(NfcRequest.read(new JSONObject(), NfcRequestTest.DEFAULTS), () -> { if (!allowed) throw new SecurityException(); }, sink); }
        NfcSessionMachine.Handle write(boolean format) throws Exception { return machine.start(NfcRequest.write(NfcRequestTest.writeArgs().put("allowFormat", format), NfcRequestTest.DEFAULTS), () -> { if (!allowed) throw new SecurityException(); }, sink); }
        void begin() { loop.immediate.all(); }
        void settle() { for (int i = 0; i < 10; i++) { loop.immediate.all(); io.all(); closer.all(); } }
        void assertError(String code) { assertEquals(0, sink.success); assertEquals(1, sink.errors); assertEquals(code, sink.failure.code); }
    }
    @Test public void readOneTagAndIgnoreDuplicates() throws Exception {
        Harness h = new Harness(); h.read(); h.begin(); Tag first = new Tag(), duplicate = new Tag(); h.backend.emit(first); h.backend.emit(duplicate); h.settle();
        assertEquals(1, h.sink.success); assertEquals(1, first.reads); assertEquals(0, duplicate.connects);
        assertEquals(1, first.closes); assertEquals(1, h.backend.disableCount); assertEquals(1, h.ledger.availablePermits());
        assertFalse(h.sink.result.has("tagId")); assertFalse(h.machine.status().getBoolean("busy"));
    }
    @Test public void emptyNdefIsAnEmptyRead() throws Exception {
        Harness h = new Harness(); h.read(); h.begin(); Tag tag = new Tag(); tag.readValue = NfcRequest.emptyMessage(); h.backend.emit(tag); h.settle();
        assertEquals("empty", h.sink.result.getString("status")); assertEquals(0, h.sink.result.getJSONArray("records").length());
    }
    @Test public void writeExactPayloadOnceAndFormatIsExplicit() throws Exception {
        Harness h = new Harness(); h.write(true); h.begin(); Tag tag = new Tag(); tag.format = true; h.backend.emit(tag); h.settle();
        assertEquals(1, tag.writes); assertEquals(0, tag.reads); assertTrue(tag.allowFormat);
        assertEquals("02656e4869", tag.written.records.get(0).json().getString("payloadHex"));
        assertTrue(h.sink.result.getBoolean("formatted")); assertEquals("confirmed", h.sink.result.getString("writeOutcome"));
    }
    @Test public void denyNeverEnablesOrConnects() throws Exception {
        Harness h = new Harness(); h.allowed = false;
        try { h.read(); fail(); } catch (SecurityException expected) { }
        h.settle(); assertEquals(0, h.backend.enableCount); assertEquals(1, h.ledger.availablePermits());
    }
    @Test public void statusDoesNotProbeOrGrant() throws Exception {
        Harness h = new Harness(); h.allowed = false; assertTrue(h.machine.status().getBoolean("available"));
        assertEquals(0, h.backend.enableCount); assertEquals(1, h.ledger.availablePermits());
    }
    @Test public void missingPlatformConditionsNeverEnable() throws Exception {
        for (String code : new String[] {"UNAVAILABLE", "NFC_DISABLED", "PERMISSION_DENIED", "FOREGROUND_REQUIRED"}) {
            Harness h = new Harness(); h.backend.available = !code.equals("UNAVAILABLE"); h.backend.enabled = !code.equals("NFC_DISABLED");
            h.backend.permission = !code.equals("PERMISSION_DENIED"); h.backend.foreground = !code.equals("FOREGROUND_REQUIRED");
            NfcRequestTest.fails(code, h::read); assertEquals(0, h.backend.enableCount); assertEquals(1, h.ledger.availablePermits());
        }
    }
    @Test public void cancelledBeforeEnableDoesNoHardwareWork() throws Exception {
        Harness h = new Harness(); NfcSessionMachine.Handle handle = h.read(); handle.cancel(); h.settle(); h.assertError("CANCELLED");
        assertEquals(0, h.backend.enableCount); assertEquals(1, h.ledger.availablePermits());
    }
    @Test public void lateDiscoveryAfterCancelIsIgnored() throws Exception {
        Harness h = new Harness(); h.read(); h.begin(); h.machine.cancel(); Tag tag = new Tag(); h.backend.emit(tag); h.settle();
        h.assertError("CANCELLED"); assertEquals(0, tag.connects);
    }
    @Test public void revocationBeforeIoNeverConnects() throws Exception {
        Harness h = new Harness(); h.read(); h.begin(); Tag tag = new Tag(); h.backend.emit(tag); h.allowed = false; h.settle();
        h.assertError("PERMISSION_DENIED"); assertEquals(0, tag.connects); assertEquals(1, tag.closes);
    }
    @Test public void authorityRecheckedAtIrreversibleBoundary() throws Exception {
        Harness h = new Harness(); h.write(false); h.begin(); Tag tag = new Tag(); tag.beforeBoundary = () -> h.allowed = false; h.backend.emit(tag); h.settle();
        h.assertError("PERMISSION_DENIED"); assertEquals(0, tag.writes); assertEquals("not_started", h.machine.status().getString("lastWriteOutcome"));
    }
    @Test public void timeoutStopsReaderAndReleasesSlot() throws Exception {
        Harness h = new Harness(); h.read(); h.begin(); h.loop.advance(30000); h.settle(); h.assertError("TIMEOUT");
        assertEquals(1, h.backend.disableCount); assertEquals(1, h.ledger.availablePermits());
    }
    @Test public void pauseDuringConnectionPreventsRead() throws Exception {
        Harness h = new Harness(); h.read(); h.begin(); Tag tag = new Tag(); tag.onConnect = () -> h.backend.foreground = false; h.backend.emit(tag); h.settle();
        h.assertError("FOREGROUND_REQUIRED"); assertEquals(0, tag.reads); assertEquals(1, tag.closes);
    }
    @Test public void pollRevocationAndAdapterOffCancelSession() throws Exception {
        Harness h = new Harness(); h.read(); h.begin(); h.allowed = false; h.loop.advance(250); h.settle(); h.assertError("PERMISSION_DENIED");
        Harness other = new Harness(); other.read(); other.begin(); other.backend.enabled = false; other.loop.advance(250); other.settle(); other.assertError("NFC_DISABLED");
    }
    @Test public void tagLostDoesNotRetryOrSwitchTag() throws Exception {
        Harness h = new Harness(); h.read(); h.begin(); Tag tag = new Tag(); tag.error = "TAG_LOST"; h.backend.emit(tag); h.settle();
        h.assertError("TAG_LOST"); assertEquals(1, tag.connects); assertEquals(1, tag.closes); assertEquals(1, h.backend.enableCount);
    }
    @Test public void readonlyWriteDoesNotAttemptEffect() throws Exception {
        Harness h = new Harness(); h.write(false); h.begin(); Tag tag = new Tag(); tag.writable = false; h.backend.emit(tag); h.settle();
        h.assertError("TAG_READ_ONLY"); assertEquals(0, tag.writes);
    }
    @Test public void cancellationAfterWriteBoundaryHasNoRollbackClaim() throws Exception {
        Harness h = new Harness(); NfcSessionMachine.Handle handle = h.write(false); h.begin(); Tag tag = new Tag(); tag.afterBoundary = handle::cancel; h.backend.emit(tag); h.settle();
        h.assertError("CANCELLED"); assertEquals(1, tag.writes); assertTrue(h.sink.failure.getMessage().contains("does not restore"));
        assertEquals("confirmed", h.machine.status().getString("lastWriteOutcome"));
    }
    @Test public void processSlotPreventsOtherMachineUntilCleanup() throws Exception {
        Semaphore ledger = new Semaphore(1); Harness h = new Harness(ledger, null), other = new Harness(ledger, null);
        h.read(); h.begin(); NfcRequestTest.fails("BUSY", other::read);
        h.machine.cancel(); NfcRequestTest.fails("BUSY", other::read); h.settle(); other.read(); other.machine.close(); other.settle(); assertEquals(1, ledger.availablePermits());
    }
    @Test public void failedTagCloseQuarantinesUntilExplicitRetry() throws Exception {
        Harness h = new Harness(); h.read(); h.begin(); Tag tag = new Tag(); tag.failClose = true; h.backend.emit(tag); h.settle();
        h.assertError("CLEANUP_FAILED"); assertEquals(0, h.ledger.availablePermits()); assertTrue(h.machine.status().getBoolean("cleanupFailed"));
        tag.failClose = false; h.machine.close(); h.settle(); assertEquals(1, h.ledger.availablePermits()); assertEquals(1, h.sink.errors);
    }
    @Test public void failedDisableRetainsProcessSlotAndRetries() throws Exception {
        Harness h = new Harness(); h.read(); h.begin(); h.backend.failDisable = true; h.machine.cancel(); h.settle();
        assertEquals(0, h.ledger.availablePermits()); assertTrue(h.machine.status().getBoolean("cleanupFailed"));
        h.backend.failDisable = false; h.machine.close(); h.settle(); assertEquals(1, h.ledger.availablePermits());
    }
    @Test public void partiallyEnabledRegistrationIsStillDisabled() throws Exception {
        Harness h = new Harness(); h.backend.failEnable = true; h.read(); h.settle(); h.assertError("NFC_ERROR");
        assertEquals(1, h.backend.disableCount); assertEquals(1, h.ledger.availablePermits());
    }
    @Test public void reentrantCancelDuringEnableDoesNotResurrectReader() throws Exception {
        Harness h = new Harness(); h.backend.duringEnable = () -> h.machine.cancel(); h.read(); h.settle(); h.assertError("CANCELLED");
        assertEquals(1, h.backend.disableCount); assertEquals(1, h.ledger.availablePermits());
    }
    @Test public void cancelSuppressesQueuedSuccessfulDelivery() throws Exception {
        Harness h = new Harness(); NfcSessionMachine.Handle handle = h.read(); h.begin(); h.backend.emit(new Tag());
        h.io.all(); h.loop.immediate.all(); h.closer.all(); // Queues final result but does not deliver it.
        handle.cancel(); h.settle(); h.assertError("CANCELLED"); assertEquals(1, h.ledger.availablePermits());
    }
    @Test public void deliveryRechecksAuthorityAndPageLifecycle() throws Exception {
        Harness h = new Harness(); h.read(); h.begin(); h.backend.emit(new Tag()); h.io.all(); h.loop.immediate.all(); h.closer.all();
        h.allowed = false; h.settle(); h.assertError("PERMISSION_DENIED");
    }
    @Test public void taggedResultBudgetIsEnforcedAtDelivery() throws Exception {
        Harness h = new Harness(); h.read(); h.begin(); Tag tag = new Tag();
        NfcRequest.Limits large = new NfcRequest.Limits(128 * 1024, 16, 30000);
        tag.readValue = NfcRequest.records(java.util.Collections.singletonList(NfcRequest.record(5, new byte[0], new byte[0], new byte[9000], large)), large);
        h.backend.emit(tag); h.settle(); h.assertError("TOO_LARGE");
    }
    @Test public void closeFromOtherThreadAbortsBlockedReadAndDoesFinalClose() throws Exception {
        CountDownLatch reading = new CountDownLatch(1), unblocked = new CountDownLatch(1), exited = new CountDownLatch(1);
        Harness h = new Harness(new Semaphore(1), task -> {
            Thread worker = new Thread(() -> { try { task.run(); } finally { exited.countDown(); } }); worker.setDaemon(true); worker.start();
        });
        h.read(); h.begin(); Tag tag = new Tag();
        tag.onRead = () -> { reading.countDown(); try { if (!unblocked.await(2, TimeUnit.SECONDS)) throw new AssertionError("Close did not abort read"); } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); } };
        tag.onClose = unblocked::countDown; h.backend.emit(tag); assertTrue(reading.await(2, TimeUnit.SECONDS));
        h.machine.cancel(); assertEquals(0, h.ledger.availablePermits()); h.loop.immediate.all(); h.closer.all(); assertTrue(exited.await(2, TimeUnit.SECONDS)); h.settle();
        h.assertError("CANCELLED"); assertEquals(1, h.ledger.availablePermits()); assertEquals(2, tag.closes);
    }
    @Test public void deadlineStillAppliesWhileSuccessfulIoWaitsForCleanup() throws Exception {
        Harness h = new Harness(); h.read(); h.begin(); h.backend.emit(new Tag());
        h.io.all(); h.loop.immediate.all(); // Intentionally do not run close worker.
        assertEquals(0, h.sink.success); assertEquals(0, h.sink.errors);
        h.loop.advance(30000); h.assertError("TIMEOUT"); assertEquals(0, h.ledger.availablePermits());
        h.settle(); assertEquals(1, h.ledger.availablePermits()); assertEquals(0, h.sink.success);
    }
    @Test public void signedTimeoutIncludesTimeQueuedBeforeReaderStarts() throws Exception {
        Harness h = new Harness(); h.read(); h.loop.now = 30000; h.settle(); h.assertError("TIMEOUT");
        assertEquals(0, h.backend.enableCount);
    }
    @Test public void rejectedIoWorkerClosesOwnedTag() throws Exception {
        Harness h = new Harness(new Semaphore(1), work -> { throw new java.util.concurrent.RejectedExecutionException(); });
        h.read(); h.begin(); Tag tag = new Tag(); h.backend.emit(tag); h.settle(); h.assertError("BUSY");
        assertEquals(0, tag.connects); assertEquals(1, tag.closes); assertEquals(1, h.ledger.availablePermits());
    }
    @Test public void closedOwnerNeverReenables() throws Exception {
        Harness h = new Harness(); h.machine.close(); NfcRequestTest.fails("CANCELLED", h::read);
        assertEquals(0, h.backend.enableCount); assertEquals("closed", h.machine.status().getString("status"));
    }

    @Test public void startRechecksReentrantOwnerDestruction() throws Exception {
        Harness h = new Harness();
        NfcRequestTest.fails("CANCELLED", () -> h.machine.start(NfcRequest.read(new JSONObject(), NfcRequestTest.DEFAULTS), h.machine::close, h.sink));
        h.settle(); assertEquals(0, h.backend.enableCount); assertEquals(1, h.ledger.availablePermits());
    }
    @Test public void stableTagErrorsNeverRetryAnotherEffect() throws Exception {
        for (String code : new String[] {"NDEF_UNSUPPORTED", "TAG_TOO_SMALL", "TAG_INVALIDATED", "MALFORMED_NDEF", "NFC_IO_ERROR"}) {
            Harness h = new Harness(); h.write(true); h.begin(); Tag tag = new Tag(); tag.error = code; h.backend.emit(tag); h.settle();
            h.assertError(code); assertEquals(1, tag.connects); assertEquals(0, tag.writes); assertEquals(1, tag.closes);
        }
    }
    @Test public void callbackErrorsCannotLeakCancelledOwnership() throws Exception {
        Harness h = new Harness();
        NfcSessionMachine.Handle handle = h.machine.start(NfcRequest.read(new JSONObject(), NfcRequestTest.DEFAULTS), () -> {}, new NfcSessionMachine.Callback() {
            public void complete(JSONObject value) { throw new IllegalStateException(); }
            public void error(FactoryException failure) { throw new IllegalStateException(); }
        });
        handle.cancel(); h.settle(); assertEquals(1, h.ledger.availablePermits()); assertFalse(h.machine.status().getBoolean("busy"));
    }

}
