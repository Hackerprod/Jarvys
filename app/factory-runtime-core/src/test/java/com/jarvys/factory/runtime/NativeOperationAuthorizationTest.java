package com.jarvys.factory.runtime;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic Binder tests, not a claim of real cross-process Binder/device scheduling. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34, manifest=Config.NONE)
public class NativeOperationAuthorizationTest {
    private static final String NONCE = repeat('a'), BINDING = repeat('b');
    private static String repeat(char value) { return new String(new char[64]).replace('\0', value); }
    private <T> T worker(Callable<T> work) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try { return executor.submit(work).get(5, TimeUnit.SECONDS); } finally { executor.shutdownNow(); }
    }
    private NativeOperationAuthorization host(AtomicInteger state, AtomicBoolean current, AtomicInteger effects, Runnable terminal) {
        return new NativeOperationAuthorization(NONCE, BINDING, SystemClock.elapsedRealtime() + 300000,
            uid -> true, new NativeOperationAuthorization.Authority() {
                public int status() { if (!current.get()) throw new SecurityException(); return state.get(); }
                public void checkBeforeEffect() { if (!current.get() || state.get() != NativeOperationAuthorization.APPROVED) throw new SecurityException(); effects.incrementAndGet(); }
            }, terminal);
    }
    @Test public void pendingPollAndPrematureEffectDoNotEndLaterApproval() throws Exception {
        AtomicInteger state = new AtomicInteger(NativeOperationAuthorization.PENDING), effects = new AtomicInteger();
        NativeOperationAuthorization host = host(state, new AtomicBoolean(true), effects, () -> {});
        NativeOperationAuthorization.Client client = worker(() -> NativeOperationAuthorization.connect(host, NONCE, BINDING, uid -> true, () -> {}));
        assertEquals(NativeOperationAuthorization.PENDING, (int) worker(client::status));
        assertThrows(SecurityException.class, client::check);
        assertFalse(host.isClosed()); assertFalse(client.isClosed()); assertEquals(0, effects.get());
        state.set(NativeOperationAuthorization.APPROVED); client.check();
        assertEquals(1, effects.get()); worker(() -> { client.close(); return null; }); assertTrue(host.isClosed());
    }
    @Test public void revokeAfterApprovalIsStickyAndClosesExactlyOnce() throws Exception {
        AtomicBoolean current = new AtomicBoolean(true); AtomicInteger ended = new AtomicInteger(), callbacks = new AtomicInteger();
        NativeOperationAuthorization host = host(new AtomicInteger(1), current, new AtomicInteger(), ended::incrementAndGet);
        NativeOperationAuthorization.Client client = worker(() -> NativeOperationAuthorization.connect(host, NONCE, BINDING, uid -> true, callbacks::incrementAndGet));
        client.check(); current.set(false); assertThrows(SecurityException.class, client::check);
        current.set(true); assertThrows(SecurityException.class, client::check); host.close(); client.close();
        assertEquals(1, ended.get()); assertEquals(1, callbacks.get());
    }
    @Test public void forgedReplyCannotSubstituteForAuthenticatedReverseChallenge() {
        Binder fake = new Binder() { @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            reply.writeNoException(); reply.writeInt(1); return true;
        }};
        assertThrows(ExecutionException.class, () -> worker(() -> NativeOperationAuthorization.connect(fake, NONCE, BINDING, uid -> true, () -> {})));
    }
    @Test public void wrongUidHostAndBindingAreRejected() throws Exception {
        NativeOperationAuthorization host = host(new AtomicInteger(1), new AtomicBoolean(true), new AtomicInteger(), () -> {});
        assertThrows(ExecutionException.class, () -> worker(() -> NativeOperationAuthorization.connect(host, NONCE, BINDING, uid -> false, () -> {})));
        NativeOperationAuthorization next = host(new AtomicInteger(1), new AtomicBoolean(true), new AtomicInteger(), () -> {});
        assertThrows(ExecutionException.class, () -> worker(() -> NativeOperationAuthorization.connect(next, NONCE, NONCE, uid -> true, () -> {})));
        next.close();
        NativeOperationAuthorization source = new NativeOperationAuthorization(NONCE, BINDING, SystemClock.elapsedRealtime()+300000,
            uid -> false, new NativeOperationAuthorization.Authority() { public int status(){ return 1; } public void checkBeforeEffect(){} }, () -> {});
        assertThrows(ExecutionException.class, () -> worker(() -> NativeOperationAuthorization.connect(source, NONCE, BINDING, uid -> true, () -> {})));
        source.close();
    }
    @Test public void replayedConnectionCannotCloseOriginalLease() throws Exception {
        NativeOperationAuthorization host = host(new AtomicInteger(1), new AtomicBoolean(true), new AtomicInteger(), () -> {});
        NativeOperationAuthorization.Client original = worker(() -> NativeOperationAuthorization.connect(host, NONCE, BINDING, uid -> true, () -> {}));
        assertThrows(ExecutionException.class, () -> worker(() -> NativeOperationAuthorization.connect(host, NONCE, BINDING, uid -> true, () -> {})));
        original.check(); worker(() -> { original.close(); return null; });
    }
    @Test public void liveUiCheckIsAllowedButConnectAndPollingStayOffMain() throws Exception {
        NativeOperationAuthorization host = host(new AtomicInteger(1), new AtomicBoolean(true), new AtomicInteger(), () -> {});
        assertThrows(IllegalStateException.class, () -> NativeOperationAuthorization.connect(host, NONCE, BINDING, uid -> true, () -> {}));
        NativeOperationAuthorization.Client client = worker(() -> NativeOperationAuthorization.connect(host, NONCE, BINDING, uid -> true, () -> {}));
        assertThrows(IllegalStateException.class, client::status); client.check(); worker(() -> { client.close(); return null; });
    }
    @Test public void terminalCallbackIsDeliveredWithoutWaitingForAnotherCheck() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        NativeOperationAuthorization host = host(new AtomicInteger(0), new AtomicBoolean(true), new AtomicInteger(), () -> {});
        NativeOperationAuthorization.Client client = worker(() -> NativeOperationAuthorization.connect(host, NONCE, BINDING, uid -> true, ended::countDown));
        host.close(); assertTrue(ended.await(5, TimeUnit.SECONDS)); assertTrue(client.isClosed());
    }
    @Test public void leaseDeadlineIsAnIrreversibleTerminalBoundary() throws Exception {
        AtomicInteger terminal = new AtomicInteger();
        NativeOperationAuthorization host = new NativeOperationAuthorization(NONCE, BINDING, SystemClock.elapsedRealtime()+1,
            uid -> true, new NativeOperationAuthorization.Authority() { public int status(){ return 0; } public void checkBeforeEffect(){} }, terminal::incrementAndGet);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(2, TimeUnit.MILLISECONDS);
        assertEquals(NativeOperationAuthorization.TERMINAL, host.currentStatus()); host.close(); assertEquals(1, terminal.get());
    }
    @Test public void alreadyApprovedConnectedWorkIsNotCutOffByTheReviewDeadline() throws Exception {
        NativeOperationAuthorization host = host(new AtomicInteger(1), new AtomicBoolean(true), new AtomicInteger(), () -> {});
        NativeOperationAuthorization.Client client = worker(() -> NativeOperationAuthorization.connect(host, NONCE, BINDING, uid -> true, () -> {}));
        org.robolectric.shadows.ShadowSystemClock.advanceBy(301, TimeUnit.SECONDS);
        client.check(); assertFalse(host.isClosed()); worker(() -> { client.close(); return null; });
    }
    @Test public void hostDeathTerminatesClientBeforeAnotherNativeEffect() throws Exception {
        NativeOperationAuthorization host = host(new AtomicInteger(1), new AtomicBoolean(true), new AtomicInteger(), () -> {});
        class Endpoint extends Binder {
            IBinder.DeathRecipient death;
            @Override public void linkToDeath(IBinder.DeathRecipient recipient, int flags) { death = recipient; }
            @Override public boolean unlinkToDeath(IBinder.DeathRecipient recipient, int flags) { if (death == recipient) death = null; return true; }
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
                return host.transact(code, data, reply, flags);
            }
            void die() { IBinder.DeathRecipient value = death; if (value != null) value.binderDied(); }
        }
        Endpoint endpoint = new Endpoint(); AtomicInteger ended = new AtomicInteger();
        NativeOperationAuthorization.Client client = worker(() -> NativeOperationAuthorization.connect(endpoint, NONCE, BINDING, uid -> true, ended::incrementAndGet));
        endpoint.die(); assertTrue(client.isClosed()); assertEquals(1, ended.get());
        assertThrows(SecurityException.class, client::check); host.close();
    }
}
