package com.jarvys.factory.runtime;

import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Single-use, native-only authority over an immutable file snapshot. The wire format contains
 * no URI, path, filename, caller-selected read length, or JavaScript-visible authority.
 *
 * <p>The caller reserves admission BEFORE materializing bytes, then transfers exclusive ownership
 * with {@link Admission#complete}. On success it must never read or mutate that array again. On
 * failure the caller still owns the array and must discard it before closing the admission. Keep
 * an admission while materialization is running, even if that work has been cancelled or expired.
 * Expired pending work cannot complete, and retains its slot until its owner finishes cleanup.
 */
public final class FileShareTransfer extends Binder implements AutoCloseable {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final int CHUNK_BYTES = 32 * 1024;
    public static final long LIFETIME_MILLIS = 5L * 60 * 1000;

    static final String DESCRIPTOR = "com.jarvys.factory.runtime.FileShareTransfer.v1";
    static final int TRANSACTION_READ = IBinder.FIRST_CALL_TRANSACTION;
    static final int TRANSACTION_CANCEL = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int REQUEST_MAX_BYTES = 512;
    static final int REPLY_MAX_BYTES = CHUNK_BYTES + 1024;
    private static final Object LOCK = new Object();
    private static final ScheduledThreadPoolExecutor EXPIRY = new ScheduledThreadPoolExecutor(1, r -> {
        Thread thread = new Thread(r, "factory-share-expire");
        thread.setDaemon(true);
        return thread;
    });
    static { EXPIRY.setRemoveOnCancelPolicy(true); }
    private static Admission active;

    /** Must authenticate the original Binder calling UID, without accepting caller-owned names. */
    public interface HostVerifier { boolean allowed(int callingUid); }
    interface Clock { long millis(); }

    public final int size;
    public final String sha256;
    private final Admission admission;
    private final String nonce;
    private final HostVerifier verifier;
    // Guarded by LOCK; deliberately nullable so a retained Binder cannot retain expired bytes.
    private byte[] snapshot;
    private int nextOffset;

    private FileShareTransfer(Admission admission, byte[] snapshot, String nonce,
            HostVerifier verifier, String sha256) {
        this.admission = admission;
        this.snapshot = snapshot;
        this.size = snapshot.length;
        this.nonce = nonce;
        this.verifier = verifier;
        this.sha256 = sha256;
    }

    /** Acquires the one process-wide slot, including while bytes are being materialized. */
    public static Admission reserve() throws FactoryException {
        return reserve(SystemClock::elapsedRealtime, true);
    }

    // Deterministic monotonic clock for synthetic tests; production admission always schedules expiry.
    static Admission reserve(Clock clock) throws FactoryException { return reserve(clock, false); }

    private static Admission reserve(Clock clock, boolean schedule) throws FactoryException {
        if (clock == null) throw invalid();
        synchronized (LOCK) {
            if (active != null) active.expireLocked();
            if (active != null) throw new FactoryException("SHARE_BUSY", "A file share is already active.");
            Admission admission = new Admission(clock);
            active = admission;
            if (schedule) {
                try {
                    admission.expiry = EXPIRY.schedule(() -> {
                        synchronized (LOCK) { admission.expireLocked(); }
                    }, LIFETIME_MILLIS, TimeUnit.MILLISECONDS);
                } catch (RuntimeException unavailable) {
                    admission.releaseLocked();
                    throw new FactoryException("UNAVAILABLE", "File sharing is unavailable.");
                }
            }
            return admission;
        }
    }

    public static final class Admission implements AutoCloseable {
        private final Clock clock;
        private final long created;
        private ScheduledFuture<?> expiry;
        private FileShareTransfer endpoint;
        private boolean closed, expired;

        private Admission(Clock clock) { this.clock = clock; this.created = clock.millis(); }

        /** Transfers array ownership only on success, without copying the snapshot. */
        public FileShareTransfer complete(byte[] snapshot, String nonce, HostVerifier verifier)
                throws FactoryException {
            if (snapshot == null || snapshot.length == 0 || snapshot.length > MAX_BYTES || !canonicalHex(nonce) || verifier == null)
                throw invalid();
            synchronized (LOCK) {
                expireLocked();
                if (closed || expired || active != this || endpoint != null) throw invalid();
                String digest;
                try { digest = hex(MessageDigest.getInstance("SHA-256").digest(snapshot)); }
                catch (Exception unavailable) {
                    throw new FactoryException("UNAVAILABLE", "File sharing is unavailable.");
                }
                // Digesting is bounded, but do not grant a snapshot if its deadline passed meanwhile.
                expireLocked();
                if (expired) throw invalid();
                endpoint = new FileShareTransfer(this, snapshot, nonce, verifier, digest);
                return endpoint;
            }
        }

        /** After complete(), ownership belongs to the endpoint and this close is a no-op. */
        @Override public void close() {
            synchronized (LOCK) { if (endpoint == null) releaseLocked(); }
        }

        private void expireLocked() {
            if (closed) return;
            long age = clock.millis() - created;
            if (age < 0 || age >= LIFETIME_MILLIS) {
                expired = true;
                // Pending snapshot materialization can still be blocked. Do not admit another
                // allocation until its owner has disposed of those bytes and closed admission.
                if (endpoint != null) releaseLocked();
            }
        }

        private void releaseLocked() {
            if (closed) return;
            closed = true;
            if (endpoint != null && endpoint.snapshot != null) {
                Arrays.fill(endpoint.snapshot, (byte) 0);
                endpoint.snapshot = null;
            }
            if (expiry != null) { expiry.cancel(false); expiry = null; }
            if (active == this) active = null;
        }
    }

    /** Synchronous, bounded memory cleanup only; never performs provider, file, or Binder I/O. */
    @Override public void close() { synchronized (LOCK) { admission.releaseLocked(); } }

    /** Enforces the monotonic deadline. Calling this repeatedly before or after expiry is safe. */
    public void expire() { synchronized (LOCK) { admission.expireLocked(); } }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        // Authenticate EVERY transaction, including unknown codes and descriptor probes, before
        // parsing. Never clear identity: the verifier observes the original, kernel-supplied UID.
        try { if (!verifier.allowed(Binder.getCallingUid())) throw denied(); }
        catch (RuntimeException verificationFailed) { throw denied(); }
        if ((code != TRANSACTION_READ && code != TRANSACTION_CANCEL) || flags != 0
                || data == null || reply == null || data.dataPosition() != 0
                || data.dataSize() > REQUEST_MAX_BYTES || data.hasFileDescriptors()) throw denied();
        String requestedNonce;
        int offset = 0;
        try {
            if (!DESCRIPTOR.equals(readFixedString(data, DESCRIPTOR.length()))) throw denied();
            requestedNonce = readFixedString(data, 64);
            if (code == TRANSACTION_READ) {
                if (data.dataAvail() != 4) throw denied();
                offset = data.readInt();
            }
            if (data.dataAvail() != 0) throw denied();
        } catch (RuntimeException malformed) { throw denied(); }
        synchronized (LOCK) {
            admission.expireLocked();
            if (!nonce.equals(requestedNonce)) throw denied();
            if (code == TRANSACTION_CANCEL) {
                // Authenticated cancellation is idempotent, including a race with final EOF.
                admission.releaseLocked();
                writeChunk(reply, null, 0, 0);
                return true;
            }
            if (admission.closed || admission.expired || snapshot == null
                    || offset < 0 || offset != nextOffset) throw denied();
            int count = Math.min(CHUNK_BYTES, size - nextOffset);
            writeChunk(reply, snapshot, nextOffset, count);
            nextOffset += count;
            // The explicit zero-byte EOF is consumed exactly once and closes the capability.
            if (count == 0) admission.releaseLocked();
            return true;
        }
    }

    /**
     * Streams into provisional native output, validating exact length, EOF, and SHA-256. The caller
     * must not expose output until this returns successfully and must discard partial output on
     * any failure. Neither flushes nor closes output. Run on a bounded worker: an arbitrary remote
     * Binder can block, and interrupt/cancellation does not make that transaction safe to retry.
     * checkActive is required and runs on that worker before and after every Binder transaction,
     * and around output writes. It should throw if cancelled, expired, or superseded.
     */
    public static void copy(IBinder binder, String nonce, int size, String sha256,
            OutputStream output, Runnable checkActive) throws Exception {
        requireWorker();
        if (binder == null || !canonicalHex(nonce) || size < 1 || size > MAX_BYTES
                || !canonicalHex(sha256) || output == null || checkActive == null) throw invalid();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int offset = 0;
        while (true) {
            checkActive.run();
            Parcel request = Parcel.obtain(), reply = Parcel.obtain();
            byte[] bytes;
            int expected = Math.min(CHUNK_BYTES, size - offset);
            try {
                writeRequest(request, nonce);
                request.writeInt(offset);
                if (!binder.transact(TRANSACTION_READ, request, reply, 0)) throw invalid();
                checkActive.run();
                bytes = readChunk(reply, expected);
            } finally { reply.recycle(); request.recycle(); }
            try {
                checkActive.run();
                if (expected == 0) break;
                digest.update(bytes);
                output.write(bytes);
                offset += expected;
                checkActive.run();
            } finally { Arrays.fill(bytes, (byte) 0); }
        }
        if (!sha256.equals(hex(digest.digest()))) throw invalid();
        checkActive.run();
    }

    /** Best-effort callers must also isolate this synchronous Binder operation on a bounded worker. */
    public static void cancel(IBinder binder, String nonce) throws Exception {
        requireWorker();
        if (binder == null || !canonicalHex(nonce)) throw invalid();
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            writeRequest(request, nonce);
            if (!binder.transact(TRANSACTION_CANCEL, request, reply, 0)) throw invalid();
            readChunk(reply, 0);
        } finally { reply.recycle(); request.recycle(); }
    }

    // Fixed ASCII fields are byte arrays, with both encoded and available lengths checked before
    // bounded allocation. No remote-controlled string length reaches a framework string reader.
    // This is a private protocol, not an AIDL interface token.
    private static String readFixedString(Parcel parcel, int length) {
        if (parcel.dataAvail() < 4) throw denied();
        int start = parcel.dataPosition();
        if (parcel.readInt() != length || parcel.dataAvail() < aligned(length)) throw denied();
        parcel.setDataPosition(start);
        byte[] bytes = new byte[length];
        parcel.readByteArray(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static void writeRequest(Parcel request, String nonce) {
        request.writeByteArray(DESCRIPTOR.getBytes(StandardCharsets.US_ASCII));
        request.writeByteArray(nonce.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeChunk(Parcel reply, byte[] bytes, int offset, int count) {
        reply.writeInt(count);
        if (count == 0) reply.writeByteArray(new byte[0]);
        else reply.writeByteArray(bytes, offset, count);
    }

    private static byte[] readChunk(Parcel reply, int expected) throws FactoryException {
        try {
            if (reply.dataSize() > REPLY_MAX_BYTES || reply.dataSize() != 8 + aligned(expected)
                    || reply.hasFileDescriptors()) throw invalid();
            reply.setDataPosition(0);
            if (reply.readInt() != expected || reply.readInt() != expected) throw invalid();
            // Never use createByteArray(): a remote-provided length must not control allocation.
            reply.setDataPosition(4);
            byte[] bytes = new byte[expected];
            reply.readByteArray(bytes);
            if (reply.dataAvail() != 0) throw invalid();
            return bytes;
        } catch (RuntimeException malformed) { throw invalid(); }
    }

    private static int aligned(int size) { return (size + 3) & ~3; }
    private static boolean canonicalHex(String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }
    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        String digits = "0123456789abcdef";
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = digits.charAt((bytes[i] & 255) >>> 4);
            out[2 * i + 1] = digits.charAt(bytes[i] & 15);
        }
        return new String(out);
    }
    private static void requireWorker() {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("File transfer requires a worker thread.");
    }
    private static FactoryException invalid() {
        return new FactoryException("INVALID_SHARE", "File share could not be completed.");
    }
    private static SecurityException denied() { return new SecurityException("File share request denied."); }
}
