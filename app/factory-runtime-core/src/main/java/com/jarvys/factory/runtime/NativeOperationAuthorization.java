package com.jarvys.factory.runtime;

import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One request's live policy lease. Pending is not approval, and terminal state is irreversible. */
public final class NativeOperationAuthorization extends Binder implements AutoCloseable {
    @FunctionalInterface public interface Access { void check(); }
    @FunctionalInterface public interface Verifier { boolean allowed(int uid); }
    public interface Authority { int status(); void checkBeforeEffect(); }
    public static final int PENDING = 0, APPROVED = 1, TERMINAL = 2;
    private static final String HOST = "com.jarvys.factory.runtime.NativeOperationAuthorization.v1";
    private static final String CLIENT = "com.jarvys.factory.runtime.NativeOperationCallback.v1";
    private static final int CONNECT = IBinder.FIRST_CALL_TRANSACTION, STATUS = CONNECT + 1,
            CHECK = CONNECT + 2, FINISH = CONNECT + 3, REVOKED = CONNECT + 1;
    private static final ThreadPoolExecutor SIGNALS = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(64));
    static { SIGNALS.allowCoreThreadTimeOut(true); }
    private final String nonce, binding;
    private final long deadline;
    private final Verifier source;
    private final Authority authority;
    private final Runnable terminal;
    private final AtomicBoolean closed = new AtomicBoolean(), connected = new AtomicBoolean();
    private final Object callbackLock = new Object();
    private IBinder callback;
    private final IBinder.DeathRecipient sourceDeath = this::close;

    public NativeOperationAuthorization(String nonce, String binding, long deadline, Verifier source,
            Authority authority, Runnable terminal) {
        if (!hex(nonce) || !hex(binding) || deadline <= SystemClock.elapsedRealtime() || source == null || authority == null || terminal == null)
            throw new IllegalArgumentException("Invalid native operation lease");
        this.nonce = nonce; this.binding = binding; this.deadline = deadline;
        this.source = source; this.authority = authority; this.terminal = terminal;
    }
    public boolean isClosed() { return closed.get(); }
    public boolean isConnected() { return connected.get(); }
    public int currentStatus() {
        if (closed.get()) return TERMINAL;
        try {
            int value = authority.status();
            if (value != PENDING && value != APPROVED) throw new SecurityException();
            if (closed.get() || ((!connected.get() || value == PENDING) && SystemClock.elapsedRealtime() >= deadline)) throw new SecurityException();
            return value;
        } catch (RuntimeException ended) { close(); return TERMINAL; }
    }
    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (flags != 0 || reply == null || data.dataSize() > 1024 || data.hasFileDescriptors() || code < CONNECT || code > FINISH) return false;
        try {
            if (!source.allowed(Binder.getCallingUid())) return false;
            data.enforceInterface(HOST);
            if (!nonce.equals(data.readString()) || !binding.equals(data.readString())) return false;
            IBinder incoming = code == CONNECT ? data.readStrongBinder() : null;
            if (data.dataAvail() != 0) return false;
            if (code == FINISH) { close(); reply.writeNoException(); reply.writeInt(TERMINAL); return true; }
            if (code == CONNECT) {
                if (SystemClock.elapsedRealtime() >= deadline) { close(); return false; }
                if (incoming == null || !connected.compareAndSet(false, true)) return false;
                // Never transact while holding this lock or the host policy's lock.
                synchronized (callbackLock) {
                    if (closed.get()) throw new SecurityException();
                    incoming.linkToDeath(sourceDeath, 0); callback = incoming;
                }
                challenge(incoming);
            } else if (!connected.get()) return false;
            int state = currentStatus();
            if (code == CHECK && state == APPROVED) {
                authority.checkBeforeEffect(); state = currentStatus();
            }
            reply.writeNoException(); reply.writeInt(state); return true;
        } catch (Exception failure) { close(); return false; }
    }
    private void challenge(IBinder target) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        long identity = Binder.clearCallingIdentity();
        try {
            data.writeInterfaceToken(CLIENT); data.writeString(nonce); data.writeString(binding);
            if (!target.transact(CONNECT, data, reply, 0) || reply.dataSize() > 512 || reply.hasFileDescriptors()) throw new SecurityException();
            reply.readException();
            if (reply.readInt() != 1 || reply.dataAvail() != 0) throw new SecurityException();
        } finally { Binder.restoreCallingIdentity(identity); data.recycle(); reply.recycle(); }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        final IBinder target;
        synchronized (callbackLock) { target = callback; callback = null; }
        if (target != null) { try { target.unlinkToDeath(sourceDeath, 0); } catch (RuntimeException ignored) { } }
        try { terminal.run(); } finally {
            if (target != null) signal(() -> {
                Parcel data = Parcel.obtain(); long identity = Binder.clearCallingIdentity();
                try {
                    data.writeInterfaceToken(CLIENT); data.writeString(nonce); data.writeString(binding);
                    target.transact(REVOKED, data, null, IBinder.FLAG_ONEWAY);
                } catch (Exception ignored) { /* Hint only; every effect still makes a live CHECK. */ }
                finally { Binder.restoreCallingIdentity(identity); data.recycle(); }
            });
        }
    }
    private static void signal(Runnable action) {
        try { SIGNALS.execute(action); } catch (java.util.concurrent.RejectedExecutionException ignored) { /* Expiry/live checks fail closed. */ }
    }

    public static final class Client implements Access, AutoCloseable {
        private final IBinder host;
        private final String nonce, binding;
        private final Runnable terminal;
        private final AtomicBoolean closed = new AtomicBoolean(), authenticated = new AtomicBoolean();
        private final IBinder.DeathRecipient hostDeath = this::ended;
        private Binder callback;
        private Client(IBinder host, String nonce, String binding, Runnable terminal) {
            this.host = host; this.nonce = nonce; this.binding = binding; this.terminal = terminal;
        }
        private int transact(int code) {
            // Android UI-only effects need an immediate live checkpoint, not a queued cached grant.
            // CHECK has no reverse calls/UI and the host only checks bounded identity/policy metadata.
            if (code != CHECK && Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Native authorization requires a worker");
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(HOST); data.writeString(nonce); data.writeString(binding);
                if (code == CONNECT) data.writeStrongBinder(callback);
                if (!host.transact(code, data, reply, 0) || reply.dataSize() > 512 || reply.hasFileDescriptors()) throw new SecurityException();
                reply.readException(); int state = reply.readInt();
                if ((state != PENDING && state != APPROVED && state != TERMINAL) || reply.dataAvail() != 0) throw new SecurityException();
                return state;
            } catch (Exception unavailable) { ended(); throw new SecurityException("Native operation authority unavailable", unavailable); }
            finally { data.recycle(); reply.recycle(); }
        }
        public int status() {
            if (closed.get()) return TERMINAL;
            int value = transact(STATUS);
            if (value == TERMINAL) ended();
            return closed.get() ? TERMINAL : value;
        }
        /** A pending CHECK fails without cancelling the outstanding human review. */
        @Override public void check() {
            if (closed.get()) throw new SecurityException("Native operation authority ended");
            int value = transact(CHECK);
            if (value == TERMINAL) ended();
            if (value != APPROVED || closed.get()) throw new SecurityException(value == PENDING ? "Native operation is awaiting approval" : "Native operation authority ended");
        }
        public void requireCurrent() { check(); }
        public boolean isClosed() { return closed.get(); }
        private void ended() {
            if (!closed.compareAndSet(false, true)) return;
            try { host.unlinkToDeath(hostDeath, 0); } catch (RuntimeException ignored) { }
            terminal.run();
        }
        @Override public void close() {
            if (closed.get()) return;
            ended();
            Runnable finish = () -> { try { transact(FINISH); } catch (RuntimeException ignored) { } };
            if (Looper.myLooper() == Looper.getMainLooper()) signal(finish); else finish.run();
        }
    }
    /** The reverse challenge authenticates the real Binder host, not a fabricated successful reply. */
    public static Client connect(IBinder endpoint, String nonce, String binding, Verifier verifiedHost, Runnable onTerminal) {
        if (endpoint == null || !hex(nonce) || !hex(binding) || verifiedHost == null || onTerminal == null) throw new IllegalArgumentException();
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Native authorization requires a worker");
        Client client = new Client(endpoint, nonce, binding, onTerminal);
        client.callback = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if ((code != CONNECT && code != REVOKED) || data.dataSize() > 1024 || data.hasFileDescriptors() ||
                        (code == CONNECT ? flags != 0 || reply == null : flags != IBinder.FLAG_ONEWAY)) return false;
                try {
                    if (!verifiedHost.allowed(Binder.getCallingUid())) return false;
                    data.enforceInterface(CLIENT);
                    if (!nonce.equals(data.readString()) || !binding.equals(data.readString()) || data.dataAvail() != 0) return false;
                    if (code == REVOKED) { client.ended(); return true; }
                    if (!client.authenticated.compareAndSet(false, true)) return false;
                    reply.writeNoException(); reply.writeInt(1); return true;
                } catch (RuntimeException denied) { return false; }
            }
        };
        try {
            endpoint.linkToDeath(client.hostDeath, 0);
            int state = client.transact(CONNECT);
            if (!client.authenticated.get() || state == TERMINAL || client.closed.get()) throw new SecurityException("Unverified or ended native authorization host");
            return client;
        } catch (Exception failure) { client.close(); throw new SecurityException("Native authorization connection failed", failure); }
    }
    private static boolean hex(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}
