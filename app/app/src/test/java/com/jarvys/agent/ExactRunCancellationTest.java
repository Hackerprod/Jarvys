package com.jarvys.agent;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class ExactRunCancellationTest {
    @Test public void staleOwnerCannotCancelReplacementRun() {
        StopController controller = StopController.forRun();
        CancellationToken old = controller.beginRun();
        assertTrue(old.completeRun());
        CancellationToken current = controller.beginRun();
        assertFalse(old.cancelExactRunAsync());
        assertFalse(current.isCancellationRequested());
        assertTrue(current.cancelExactRunAsync());
        assertTrue(current.isCancellationRequested());
        assertFalse(current.cancelExactRunAsync());
    }

    @Test public void exactStopLatchesBeforeAsyncCallbacksAndFutureCancellation() throws Exception {
        StopController controller = StopController.forRun();
        CancellationToken token = controller.beginRun();
        Thread caller = Thread.currentThread();
        CountDownLatch callbacks = new CountDownLatch(2);
        AtomicReference<Thread> futureThread = new AtomicReference<>();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        FutureTask<Void> future = new FutureTask<Void>(() -> null) {
            @Override protected void done() { futureThread.set(Thread.currentThread()); callbacks.countDown(); }
        };
        controller.attachFuture(token, future);
        token.registerCancelAction(() -> { callbackThread.set(Thread.currentThread()); callbacks.countDown(); });
        assertTrue(token.cancelExactRunAsync());
        assertTrue(token.isCancellationRequested());
        assertTrue(callbacks.await(10, TimeUnit.SECONDS));
        assertNotSame(caller, futureThread.get());
        assertNotSame(caller, callbackThread.get());
        assertTrue(future.isCancelled());
    }

    @Test public void standaloneExactStopIsAsyncAndUncancellableScopeStaysUntouched() throws Exception {
        CancellationToken token = CancellationToken.cancellable();
        CountDownLatch callback = new CountDownLatch(1);
        AtomicReference<Thread> thread = new AtomicReference<>();
        token.registerCancelAction(() -> { thread.set(Thread.currentThread()); callback.countDown(); });
        assertTrue(token.cancelExactRunAsync());
        assertTrue(token.isCancellationRequested());
        assertTrue(callback.await(10, TimeUnit.SECONDS));
        assertNotSame(Thread.currentThread(), thread.get());
        assertFalse(token.cancelExactRunAsync());
        assertFalse(CancellationToken.uncancellable().cancelExactRunAsync());
    }
}
