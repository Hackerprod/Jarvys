package com.jarvys.factory.runtime;

import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;

/** Version-two native backup lease. No archive, URI, path or reusable database authority crosses it. */
public final class BackupCommitAuthorization extends Binder implements AutoCloseable {
    public interface Verifier { boolean allowed(int uid); }
    private static final String HOST = "com.jarvys.factory.runtime.BackupCommitAuthorization.v2";
    private static final String SOURCE = "com.jarvys.factory.runtime.BackupCommitChallenge.v2";
    private static final int CONNECT = IBinder.FIRST_CALL_TRANSACTION, CHECK = CONNECT + 1, FINISH = CONNECT + 2;
    private final String nonce, plan;
    private final long deadline;
    private final Verifier source;
    private final Runnable current, terminal;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean connected = new AtomicBoolean();
    public BackupCommitAuthorization(String nonce, String plan, long deadline, Verifier source, Runnable current, Runnable terminal) {
        if (!hex(nonce) || !hex(plan) || source == null || current == null || terminal == null || deadline <= SystemClock.elapsedRealtime())
            throw new IllegalArgumentException();
        this.nonce = nonce; this.plan = plan; this.deadline = deadline; this.source = source; this.current = current; this.terminal = terminal;
    }
    private void requireCurrent() {
        if (closed.get() || SystemClock.elapsedRealtime() >= deadline) throw new SecurityException("Backup authorization ended");
        current.run();
        if (closed.get() || SystemClock.elapsedRealtime() >= deadline) throw new SecurityException("Backup authorization ended");
    }
    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (flags != 0 || reply == null || data.dataSize() > 1024 || data.hasFileDescriptors() || code < CONNECT || code > FINISH) return false;
        try {
            if (!source.allowed(Binder.getCallingUid())) return false;
            data.enforceInterface(HOST);
            if (!nonce.equals(data.readString()) || !plan.equals(data.readString())) return false;
            IBinder challenge = code == CONNECT ? data.readStrongBinder() : null;
            if (data.dataAvail() != 0) return false;
            if (code == FINISH) { close(); reply.writeNoException(); reply.writeInt(1); return true; }
            requireCurrent();
            if (code == CONNECT) {
                if (challenge == null || !connected.compareAndSet(false, true)) return false;
                Parcel question = Parcel.obtain(), answer = Parcel.obtain();
                long identity = Binder.clearCallingIdentity();
                try {
                    question.writeInterfaceToken(SOURCE); question.writeString(nonce); question.writeString(plan);
                    if (!challenge.transact(CONNECT, question, answer, 0) || answer.dataSize() > 512 || answer.hasFileDescriptors()) throw new SecurityException();
                    answer.readException();
                    if (answer.readInt() != 1 || answer.dataAvail() != 0) throw new SecurityException();
                } finally { Binder.restoreCallingIdentity(identity); question.recycle(); answer.recycle(); }
            } else if (!connected.get()) return false;
            requireCurrent(); reply.writeNoException(); reply.writeInt(1); return true;
        } catch (Exception failure) { close(); return false; }
    }
    /** Terminal revocation is immediate. The callback may enqueue bounded durable cleanup. */
    @Override public void close() { if (closed.compareAndSet(false, true)) terminal.run(); }
    public boolean isClosed() { return closed.get(); }

    public static final class Client implements AutoCloseable {
        private final IBinder host;
        private final String nonce, plan;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Client(IBinder host, String nonce, String plan) { this.host = host; this.nonce = nonce; this.plan = plan; }
        private void transact(int code, IBinder challenge) throws Exception {
            if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Backup authorization requires worker");
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(HOST); data.writeString(nonce); data.writeString(plan);
                if (code == CONNECT) data.writeStrongBinder(challenge);
                if (!host.transact(code, data, reply, 0) || reply.dataSize() > 512 || reply.hasFileDescriptors()) throw new SecurityException("Backup authorization unavailable");
                reply.readException(); if (reply.readInt() != 1 || reply.dataAvail() != 0) throw new SecurityException("Invalid authorization reply");
            } finally { data.recycle(); reply.recycle(); }
        }
        public void requireCurrent() throws Exception {
            if (closed.get()) throw new SecurityException("Backup authorization closed");
            transact(CHECK, null);
            if (closed.get()) throw new SecurityException("Backup authorization closed");
        }
        /** False never means a prior effect was rolled back. */
        public boolean allowed() { try { requireCurrent(); return true; } catch (Exception denied) { return false; } }
        @Override public void close() throws Exception { if (closed.compareAndSet(false, true)) transact(FINISH, null); }
    }
    /** Both directions authenticate kernel-supplied UID; a fabricated Binder reply cannot approve. */
    public static Client connect(IBinder endpoint, String nonce, String plan, Verifier verifiedHost) throws Exception {
        if (endpoint == null || !hex(nonce) || !hex(plan) || verifiedHost == null) throw new IllegalArgumentException();
        AtomicBoolean authenticated = new AtomicBoolean();
        Binder challenge = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code != CONNECT || flags != 0 || reply == null || data.dataSize() > 1024 || data.hasFileDescriptors()) return false;
                try {
                    if (!verifiedHost.allowed(Binder.getCallingUid())) return false;
                    data.enforceInterface(SOURCE);
                    if (!nonce.equals(data.readString()) || !plan.equals(data.readString()) || data.dataAvail() != 0 || !authenticated.compareAndSet(false, true)) return false;
                    reply.writeNoException(); reply.writeInt(1); return true;
                } catch (RuntimeException denied) { return false; }
            }
        };
        Client client = new Client(endpoint, nonce, plan);
        client.transact(CONNECT, challenge);
        if (!authenticated.get()) throw new SecurityException("Unverified backup authorization host");
        return client;
    }
    private static boolean hex(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
}
