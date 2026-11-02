package com.jarvys.agent;

import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic native retry waits; no provider requests or rollout activation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class ProviderRetryLeaseTest {
    private static ProviderAttemptLease lease(CancellationToken token) {
        return new ProviderAttemptLease("central", "run", "retry",
                new CoreAgentLoop.Checkpoint(Collections.emptyList(), Collections.emptySet(),
                        Collections.emptyMap(), -1), token);
    }
    private static ProviderHttpException retryAfter() {
        return new ProviderHttpException("synthetic retry", 429, 600_000L, null);
    }

    @Test public void newInputWakesNativeRetryAfterWithoutStoppingOwnerOrChildren() throws Exception {
        ExecutorService owner = Executors.newSingleThreadExecutor();
        CancellationToken token = CancellationToken.cancellable();
        AtomicInteger childStops = new AtomicInteger();
        token.registerCancelAction(childStops::incrementAndGet);
        AtomicReference<ProviderAttemptLease> active = new AtomicReference<>();
        CountDownLatch registered = new CountDownLatch(1);
        Future<Boolean> done = owner.submit(() -> {
            try (ProviderAttemptLease current = lease(token)) {
                active.set(current); registered.countDown();
                try {
                    new ProviderRetryWaiter().await(retryAfter(), 0, token);
                    return false;
                } catch (ProviderAttemptLease.YieldRequested expected) {
                    assertEquals(ProviderAttemptLease.Stage.PREPARING, current.stage());
                    return true;
                }
            }
        });
        try {
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            active.get().requestYield();
            assertTrue(done.get(5, TimeUnit.SECONDS));
            assertFalse(token.isCancellationRequested());
            assertEquals(0, childStops.get());
        } finally { token.cancelExactRunAsync(); owner.shutdownNow(); }
    }

    @Test public void stopStillWakesRetryAsCancellationNotYield() throws Exception {
        ExecutorService owner = Executors.newSingleThreadExecutor();
        CancellationToken token = CancellationToken.cancellable();
        CountDownLatch registered = new CountDownLatch(1);
        Future<Boolean> done = owner.submit(() -> {
            try (ProviderAttemptLease current = lease(token)) {
                registered.countDown();
                try { new ProviderRetryWaiter().await(retryAfter(), 0, token); return false; }
                catch (CancellationException expected) { return true; }
            }
        });
        try {
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            token.cancelExactRunAsync();
            assertTrue(done.get(5, TimeUnit.SECONDS));
        } finally { token.cancelExactRunAsync(); owner.shutdownNow(); }
    }

    @Test public void injectedClockRetainsExactDelayTokenThreadAndPendingYieldCheck() {
        CancellationToken token = CancellationToken.cancellable();
        Thread owner = Thread.currentThread();
        AtomicInteger sleeps = new AtomicInteger();
        try (ProviderAttemptLease current = lease(token)) {
            ProviderRetryWaiter waiter = new ProviderRetryWaiter((millis, received) -> {
                sleeps.incrementAndGet();
                assertEquals(600_000L, millis);
                assertSame(token, received);
                assertSame(owner, Thread.currentThread());
                current.requestYield();
            }, () -> 1.0d);
            try { waiter.await(retryAfter(), 20, token); fail("Yield was lost after clock"); }
            catch (ProviderAttemptLease.YieldRequested expected) { }
        }
        assertEquals(1, sleeps.get());
        assertFalse(token.isCancellationRequested());
    }

    @Test public void yieldBeforeTransportReturnPreservesTypedYield() throws Exception {
        assertTransportReturnOrdering(true);
    }

    @Test public void transportReturnBeforeYieldCannotCommitReply() throws Exception {
        assertTransportReturnOrdering(false);
    }

    // Deliberately tests the two ordered outcomes, not an instrumented internal micro-race.
    private static void assertTransportReturnOrdering(boolean yieldFirst) throws Exception {
        ExecutorService owner = Executors.newSingleThreadExecutor();
        CancellationToken token = CancellationToken.cancellable();
        AtomicInteger childStops = new AtomicInteger();
        token.registerCancelAction(childStops::incrementAndGet);
        AtomicReference<ProviderAttemptLease> active = new AtomicReference<>();
        CountDownLatch boundary = new CountDownLatch(1), yielded = new CountDownLatch(1);
        Future<Boolean> result = owner.submit(() -> {
            try (ProviderAttemptLease current = lease(token)) {
                current.beginInference();
                active.set(current);
                if (!yieldFirst) {
                    current.transportReturned();
                    assertEquals(ProviderAttemptLease.Stage.PREPARING, current.stage());
                }
                boundary.countDown();
                assertTrue(yielded.await(5, TimeUnit.SECONDS));
                try {
                    if (yieldFirst) current.transportReturned();
                    else current.tryCommitReply();
                    return false;
                } catch (ProviderAttemptLease.YieldRequested expected) {
                    assertEquals(yieldFirst ? ProviderAttemptLease.Stage.SUPERSEDED
                            : ProviderAttemptLease.Stage.PREPARING, current.stage());
                    return true;
                }
            }
        });
        try {
            assertTrue(boundary.await(5, TimeUnit.SECONDS));
            active.get().requestYield();
            yielded.countDown();
            assertTrue(result.get(5, TimeUnit.SECONDS));
            assertFalse(token.isCancellationRequested());
            assertEquals(0, childStops.get());
        } finally {
            yielded.countDown(); token.cancelExactRunAsync(); owner.shutdownNow();
        }
    }

    @Test public void committedReplyCannotBeRetroactivelySuperseded() {
        CancellationToken token = CancellationToken.cancellable();
        try (ProviderAttemptLease current = lease(token)) {
            current.beginInference();
            current.transportReturned();
            assertTrue(current.tryCommitReply());
            current.requestYield();
            current.requireOwnerCurrent();
            assertEquals(ProviderAttemptLease.Stage.COMMITTING, current.stage());
            assertFalse(current.transportToken().isCancellationRequested());
            assertFalse(current.acceptsDelta());
            assertFalse(token.isCancellationRequested());
        }
    }

    @Test public void supersededAttemptRejectsLateDeltaAndReplyAdmission() {
        CancellationToken token = CancellationToken.cancellable();
        try (ProviderAttemptLease current = lease(token)) {
            current.beginInference();
            assertTrue(current.acceptsDelta());
            current.requestYield();
            assertFalse(current.acceptsDelta());
            try { current.requireCurrent(); fail("Late callback retained authority"); }
            catch (ProviderAttemptLease.YieldRequested expected) { }
            try { current.tryCommitReply(); fail("Late reply retained authority"); }
            catch (ProviderAttemptLease.YieldRequested expected) { }
            assertFalse(token.isCancellationRequested());
        }
    }

    @Test public void actualStopDominatesPendingYieldAndDoesNotBecomeRegeneration() {
        StopController controller = new StopController();
        CancellationToken token = controller.beginRun();
        try (ProviderAttemptLease current = lease(token)) {
            current.beginInference();
            current.requestYield();
            assertTrue(controller.stopRunAsync());
            assertTrue(token.wasStoppedByUser());
            try { current.requireOwnerCurrent(); fail("STOP was ignored"); }
            catch (CancellationException expected) { }
            try { current.transportReturned(); fail("STOP became transport return"); }
            catch (CancellationException expected) { }
            assertFalse(current.acceptsDelta());
        }
    }

    @Test public void backoffRejectsInferenceStageWithoutReclassifyingIt() {
        CancellationToken token = CancellationToken.cancellable();
        try (ProviderAttemptLease current = lease(token)) {
            current.beginInference();
            try { current.awaitBackoff(0); fail("Inference is not a retry boundary"); }
            catch (IllegalStateException expected) { }
            assertEquals(ProviderAttemptLease.Stage.INFERENCE, current.stage());
        }
    }
}
