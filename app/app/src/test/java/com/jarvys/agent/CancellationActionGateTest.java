package com.jarvys.agent;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class CancellationActionGateTest {
    @Test public void cancelledStandaloneAndCrewTokensRejectEveryLaterDispatch() {
        for (CancellationToken token : Arrays.asList(CancellationToken.cancellable(), CancellationToken.crewChild())) {
            AtomicInteger effects = new AtomicInteger();
            assertTrue(token.cancel());
            assertFalse(token.runIfActive(effects::incrementAndGet));
            assertEquals("cancelled", token.callIfActive(() -> { effects.incrementAndGet(); return "executed"; }, "cancelled"));
            assertEquals(0, effects.get());
        }
    }

    @Test public void timeoutClosesStandaloneHandlesAndPreventsDispatch() {
        for (CancellationToken token : Arrays.asList(CancellationToken.cancellable(), CancellationToken.crewChild(), CancellationToken.uncancellable())) {
            AtomicInteger closed = new AtomicInteger();
            token.registerCancelAction(closed::incrementAndGet);
            assertTrue(token.cancelForTimeout());
            assertEquals(1, closed.get());
            assertFalse(token.runIfActive(() -> fail("Timed-out action executed")));
            assertEquals("cancelled", token.callIfActive(() -> "executed", "cancelled"));
            token.registerCancelAction(closed::incrementAndGet);
            assertEquals(2, closed.get());
        }
    }

    @Test public void stopAndTimeoutWaitForOnlyTheActiveDispatchAndCallbacksRunOutsideItsLock() throws Exception {
        for (boolean timeout : new boolean[] {false, true}) {
            CancellationToken token = CancellationToken.crewChild();
            ExecutorService executor = Executors.newFixedThreadPool(3);
            CountDownLatch dispatched = new CountDownLatch(1), release = new CountDownLatch(1), cancelling = new CountDownLatch(1);
            AtomicInteger effects = new AtomicInteger();
            token.registerCancelAction(() -> {
                try {
                    // A callback must not retain the action gate while another thread inspects it.
                    assertFalse(executor.submit(() -> token.runIfActive(effects::incrementAndGet)).get(1, TimeUnit.SECONDS));
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            try {
                Future<Boolean> action = executor.submit(() -> token.runIfActive(() -> {
                    dispatched.countDown();
                    try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                    effects.incrementAndGet();
                }));
                assertTrue(dispatched.await(1, TimeUnit.SECONDS));
                Future<Boolean> cancel = executor.submit(() -> {
                    cancelling.countDown();
                    return timeout ? token.cancelForTimeout() : token.cancel();
                });
                assertTrue(cancelling.await(1, TimeUnit.SECONDS));
                assertFalse(cancel.isDone());
                release.countDown();
                assertTrue(action.get(1, TimeUnit.SECONDS));
                assertTrue(cancel.get(1, TimeUnit.SECONDS));
                assertEquals(1, effects.get());
                assertFalse(token.runIfActive(effects::incrementAndGet));
            } finally {
                release.countDown();
                token.cancel();
                executor.shutdownNow();
            }
        }
    }

    @Test public void controllerTimeoutAlsoClosesTheLastActionGate() {
        StopController controller = StopController.getInstance();
        CancellationToken token = controller.beginRun();
        assertNotNull(token);
        try {
            assertTrue(token.cancelForTimeout());
            assertFalse(token.runIfActive(() -> fail("Timed-out controller action executed")));
            assertEquals("cancelled", token.callIfActive(() -> "executed", "cancelled"));
        } finally { controller.stopRun(); }
    }

    @Test public void cancellationCannotLoseAHandleRegisteredBetweenItsSnapshotAndClear() throws Exception {
        for (boolean timeout : new boolean[] {false, true}) {
            CancellationToken token = CancellationToken.crewChild();
            CountDownLatch beforeAdd = new CountDownLatch(1), snapshotTaken = new CountDownLatch(1);
            CountDownLatch added = new CountDownLatch(1), cleared = new CountDownLatch(1);
            CopyOnWriteArrayList<Runnable> racing = new CopyOnWriteArrayList<Runnable>() {
                @Override public boolean add(Runnable action) {
                    beforeAdd.countDown();
                    await(snapshotTaken);
                    boolean result = super.add(action);
                    added.countDown();
                    await(cleared);
                    return result;
                }
                @Override public Iterator<Runnable> iterator() {
                    Iterator<Runnable> emptySnapshot = super.iterator();
                    snapshotTaken.countDown();
                    await(added);
                    return emptySnapshot;
                }
                @Override public void clear() {
                    super.clear();
                    cleared.countDown();
                }
            };
            java.lang.reflect.Field callbacks = CancellationToken.class.getDeclaredField("standaloneCancelActions");
            callbacks.setAccessible(true);
            callbacks.set(token, racing);
            AtomicInteger closed = new AtomicInteger();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Runnable> registration = executor.submit(() -> token.registerCancelAction(closed::incrementAndGet));
                assertTrue(beforeAdd.await(1, TimeUnit.SECONDS));
                assertTrue(timeout ? token.cancelForTimeout() : token.cancel());
                registration.get(1, TimeUnit.SECONDS);
                assertEquals("The newly registered handle must be closed exactly once", 1, closed.get());
            } finally {
                snapshotTaken.countDown();
                cleared.countDown();
                executor.shutdownNow();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(2, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
