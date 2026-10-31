package com.jarvys.agent;

import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class MemoryWriteGateTest {
    private static final class Settings implements MemoryWriteGate.Settings {
        boolean enabled = true;
        boolean fail;
        boolean throwing;
        boolean readThrows;
        boolean mutateThenFail;
        public boolean readEnabled() {
            if (readThrows) throw new IllegalStateException("read unavailable");
            return enabled;
        }
        public boolean commitEnabled(boolean value) {
            if (throwing) throw new IllegalStateException("storage unavailable");
            if (mutateThenFail) { enabled = value; return false; }
            if (fail) return false;
            enabled = value;
            return true;
        }
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("worker did not reach checkpoint", latch.await(5, TimeUnit.SECONDS));
    }
    private static void rejected(MemoryWriteGate gate, MemoryWriteGate.Ticket ticket) throws Exception {
        assertThrows(MemoryWriteGate.RejectedException.class,
            () -> gate.commit(ticket, () -> true, () -> { fail("effect must not run"); return null; }));
    }

    @Test public void offBeforeCommitAndOffOnInvalidateCapturedTickets() throws Exception {
        MemoryWriteGate gate = new MemoryWriteGate(new Settings());
        MemoryWriteGate.Ticket before = gate.capture();
        gate.setEnabled(false);
        rejected(gate, before);
        MemoryWriteGate.Ticket off = gate.capture();
        gate.setEnabled(true);
        rejected(gate, before);
        rejected(gate, off);
        assertEquals("saved", gate.commit(gate.capture(), () -> true, () -> "saved"));
    }

    @Test public void failedPreferenceCommitDeniesEvenIfPersistedValueStayedEnabled() throws Exception {
        for (boolean throwing : new boolean[] {false, true}) {
            Settings settings = new Settings();
            MemoryWriteGate gate = new MemoryWriteGate(settings);
            MemoryWriteGate.Ticket before = gate.capture();
            settings.fail = true;
            settings.throwing = throwing;
            assertThrows(IllegalStateException.class, () -> gate.setEnabled(false));
            assertTrue(settings.enabled);
            assertFalse(gate.isEnabled());
            rejected(gate, before);
            MemoryWriteGate.Ticket denied = gate.capture();
            rejected(gate, denied);
            settings.fail = false;
            settings.throwing = false;
            gate.setEnabled(true);
            assertTrue(gate.isEnabled());
            rejected(gate, before);
            rejected(gate, denied);
            assertEquals("saved", gate.commit(gate.capture(), () -> true, () -> "saved"));
        }
    }

    @Test public void foreignTicketAndFailedPureCheckCannotRunEffect() throws Exception {
        MemoryWriteGate a = new MemoryWriteGate(new Settings());
        MemoryWriteGate b = new MemoryWriteGate(new Settings());
        rejected(a, b.capture());
        rejected(a, null);
        assertThrows(MemoryWriteGate.RejectedException.class,
            () -> a.commit(a.capture(), () -> false, () -> { fail(); return null; }));
        assertEquals("next", a.commit(a.capture(), () -> true, () -> "next"));
    }

    @Test public void captureDoesNotHoldLockAcrossModelWork() throws Exception {
        MemoryWriteGate gate = new MemoryWriteGate(new Settings());
        MemoryWriteGate.Ticket ticket = gate.capture();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> gate.setEnabled(false)).get(5, TimeUnit.SECONDS);
            rejected(gate, ticket);
        } finally { worker.shutdownNow(); }
    }

    @Test public void commitFinishesBeforeConcurrentOffReturns() throws Exception {
        exerciseConcurrentOff(false);
    }

    @Test public void rollbackFinishesBeforeConcurrentOffReturnsAndLockIsReleased() throws Exception {
        exerciseConcurrentOff(true);
    }

    @Test public void snapshotsDoNotWaitForAnOngoingCommit() throws Exception {
        MemoryWriteGate gate = new MemoryWriteGate(new Settings());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> effect = workers.submit(() -> {
                try { gate.commit(gate.capture(), () -> true, () -> {
                    entered.countDown(); await(release); return null;
                }); } catch (Exception failure) { throw new AssertionError(failure); }
            });
            await(entered);
            Future<MemoryWriteGate.Ticket> snapshot = workers.submit(() -> {
                assertTrue(gate.isEnabled());
                return gate.capture();
            });
            assertNotNull(snapshot.get(5, TimeUnit.SECONDS));
            release.countDown();
            effect.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test public void failedSetterReadAndFailedCommitAfterInMemoryMutationFailClosed() throws Exception {
        Settings settings = new Settings();
        MemoryWriteGate gate = new MemoryWriteGate(settings);
        MemoryWriteGate.Ticket ticket = gate.capture();
        settings.readThrows = true;
        assertThrows(IllegalStateException.class, () -> gate.setEnabled(false));
        assertFalse(gate.isEnabled());
        rejected(gate, ticket);
        settings.readThrows = false;
        settings.mutateThenFail = true;
        assertThrows(IllegalStateException.class, () -> gate.setEnabled(true));
        assertTrue(settings.enabled);
        assertFalse(gate.isEnabled());
        rejected(gate, gate.capture());
    }

    @Test public void successfulUnchangedSettingKeepsTicketAndReentrantWritesAreRejected() throws Exception {
        MemoryWriteGate gate = new MemoryWriteGate(new Settings());
        MemoryWriteGate.Ticket ticket = gate.capture();
        gate.setEnabled(true);
        assertEquals("saved", gate.commit(ticket, () -> true, () -> {
            assertThrows(IllegalStateException.class, () -> gate.setEnabled(false));
            assertThrows(IllegalStateException.class, () -> gate.commit(ticket, () -> true, () -> null));
            assertTrue(gate.isEnabled());
            return "saved";
        }));
    }

    private static void exerciseConcurrentOff(boolean rollback) throws Exception {
        Settings settings = new Settings();
        MemoryWriteGate gate = new MemoryWriteGate(settings);
        MemoryWriteGate.Ticket ticket = gate.capture();
        CountDownLatch entered = new CountDownLatch(1), offAttempt = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean effectFinished = new AtomicBoolean(), offReturned = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Thread> offThread = new java.util.concurrent.atomic.AtomicReference<>();
        java.lang.reflect.Field field = MemoryWriteGate.class.getDeclaredField("lock");
        field.setAccessible(true);
        java.util.concurrent.locks.ReentrantLock observedLock = (java.util.concurrent.locks.ReentrantLock) field.get(gate);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> effect = workers.submit(() -> {
                try {
                    gate.commit(ticket, () -> true, () -> {
                        entered.countDown();
                        await(release);
                        assertFalse(offReturned.get());
                        effectFinished.set(true); // In the failure case, rollback completed here.
                        if (rollback) throw new IllegalArgumentException("transaction rolled back");
                        return null;
                    });
                    assertFalse(rollback);
                } catch (IllegalArgumentException expected) { assertTrue(rollback); }
                catch (Exception unexpected) { throw new AssertionError(unexpected); }
            });
            await(entered);
            Future<?> off = workers.submit(() -> {
                offThread.set(Thread.currentThread());
                offAttempt.countDown();
                gate.setEnabled(false);
                assertTrue(effectFinished.get());
                offReturned.set(true);
            });
            await(offAttempt);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!observedLock.hasQueuedThread(offThread.get()) && !off.isDone() && System.nanoTime() < deadline) {
                Thread.yield();
            }
            if (off.isDone()) off.get(5, TimeUnit.SECONDS); // Preserve any worker assertion/exception.
            assertTrue("OFF must wait on the held commit lock", observedLock.hasQueuedThread(offThread.get()));
            assertFalse(offReturned.get());
            release.countDown();
            effect.get(5, TimeUnit.SECONDS);
            off.get(5, TimeUnit.SECONDS);
            assertTrue(offReturned.get());
            assertFalse(gate.isEnabled());
            rejected(gate, ticket);
        } finally { release.countDown(); workers.shutdownNow(); }
    }
}
