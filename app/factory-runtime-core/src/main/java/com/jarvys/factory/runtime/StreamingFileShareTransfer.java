package com.jarvys.factory.runtime;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Single-use, disk-backed native file snapshot. A <=8 MiB snapshot speaks the exact existing v1
 * wire protocol; only larger snapshots use v2's long offsets. No remote read length, path, URI,
 * descriptor or JavaScript authority is accepted. Photos/backups retain their v1 limits.
 *
 * Cancellation revokes access synchronously, but never releases admission until provider work,
 * disk reads and cleanup have actually returned. A blocked worker is never replaced or retried.
 */
public final class StreamingFileShareTransfer extends Binder implements AutoCloseable {
    public static final long MAX_BYTES = 4L * 1024 * 1024 * 1024;
    public static final long MAX_TIMEOUT_MILLIS = 86_400_000L;
    public static final int CHUNK_BYTES = FileShareTransfer.CHUNK_BYTES;
    static final String DESCRIPTOR_V2 = "com.jarvys.factory.runtime.FileShareTransfer.v2";
    private static final int READ = IBinder.FIRST_CALL_TRANSACTION, CANCEL = READ + 1;
    private static final ScheduledThreadPoolExecutor EXPIRY = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "factory-share-disk-expiry"); thread.setDaemon(true); return thread;
    });
    private static final ThreadPoolExecutor CLEANUP = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "factory-share-disk-cleanup"); thread.setDaemon(true); return thread;
            });
    static { EXPIRY.setRemoveOnCancelPolicy(true); CLEANUP.allowCoreThreadTimeOut(true); }

    @FunctionalInterface public interface SnapshotWriter extends AutoCloseable {
        void write(OutputStream output, Runnable checkActive) throws Exception;
        /** Native source ownership may outlive cancellation; cleanup must really finish. */
        @Override default void close() throws Exception { }
    }
    interface Clock { long millis(); }
    interface Snapshot extends AutoCloseable {
        void materialize(long maximum, SnapshotWriter writer, Runnable active) throws Exception;
        long size(); String sha256();
        int read(byte[] bytes, int offset, int count) throws Exception;
    }
    interface SnapshotFactory { Snapshot create(); }

    public final long size;
    public final String sha256;
    public final int protocolVersion;
    private final Admission admission;
    private final String nonce;
    private final FileShareTransfer.HostVerifier verifier;
    private long nextOffset;

    private StreamingFileShareTransfer(Admission admission, String nonce, FileShareTransfer.HostVerifier verifier, int legacyMaximum) {
        this.admission = admission; this.nonce = nonce; this.verifier = verifier;
        size = admission.snapshot.size(); sha256 = admission.snapshot.sha256();
        protocolVersion = size <= legacyMaximum ? 1 : 2;
    }

    /** Reserve BEFORE reading any provider or creating any disk snapshot. */
    public static Admission reserve(Context context, long maximum, long timeoutMs) throws FactoryException {
        if (context == null) throw invalid();
        return reserve(maximum, timeoutMs, SystemClock::elapsedRealtime, () -> new FileShareSnapshot(context), true);
    }
    static Admission reserve(long maximum, long timeoutMs, Clock clock, SnapshotFactory factory) throws FactoryException {
        return reserve(maximum, timeoutMs, clock, factory, false);
    }
    private static Admission reserve(long maximum, long timeoutMs, Clock clock, SnapshotFactory factory, boolean schedule)
            throws FactoryException {
        if (maximum < 1 || maximum > MAX_BYTES || timeoutMs < 1 || timeoutMs > MAX_TIMEOUT_MILLIS || clock == null || factory == null)
            throw invalid();
        // Reuse the original pending admission: its timeout never releases unfinished ownership.
        // This is the SAME process-wide slot used by v1 photo/backup materialization.
        FileShareTransfer.Admission guard = FileShareTransfer.reserve();
        Admission admission = new Admission(guard, maximum, timeoutMs, clock, factory);
        if (schedule) try { admission.expiry = EXPIRY.schedule(admission::close, timeoutMs, TimeUnit.MILLISECONDS); }
        catch (RuntimeException unavailable) { guard.close(); throw new FactoryException("UNAVAILABLE", "File sharing is unavailable."); }
        return admission;
    }

    public static final class Admission implements AutoCloseable {
        private final FileShareTransfer.Admission guard;
        private final long maximum, timeoutMs, created;
        private final Clock clock;
        private final SnapshotFactory factory;
        private Snapshot snapshot;
        private SnapshotWriter sourceOwner;
        private boolean preparing, reading, cancelled, cleaned, cleanupScheduled;
        private StreamingFileShareTransfer endpoint;
        private ScheduledFuture<?> expiry;
        private Admission(FileShareTransfer.Admission guard, long maximum, long timeoutMs, Clock clock, SnapshotFactory factory) {
            this.guard = guard; this.maximum = maximum; this.timeoutMs = timeoutMs;
            this.clock = clock; this.factory = factory; this.created = clock.millis();
        }

        /** Provider reads, disk output and digesting execute on the caller's bounded worker. */
        public StreamingFileShareTransfer prepare(SnapshotWriter source, String nonce,
                FileShareTransfer.HostVerifier verifier, Runnable checkActive) throws Exception {
            return prepare(source, nonce, verifier, checkActive, FileShareTransfer.MAX_BYTES);
        }
        /** A family with a smaller v1 envelope (such as 6 MiB PCM audio) may negotiate v2 earlier. */
        public StreamingFileShareTransfer prepare(SnapshotWriter source, String nonce,
                FileShareTransfer.HostVerifier verifier, Runnable checkActive, int legacyMaximum) throws Exception {
            requireWorker();
            if (source == null || !canonicalHex(nonce) || verifier == null || checkActive == null || legacyMaximum < 1 || legacyMaximum > FileShareTransfer.MAX_BYTES) throw invalid();
            synchronized (this) {
                if (preparing || sourceOwner != null || snapshot != null || endpoint != null || cleaned) throw invalid();
                sourceOwner = source;
                try { requireActive(); } catch (IllegalStateException inactive) { scheduleCleanup(); throw inactive; }
                preparing = true;
            }
            boolean success = false;
            try {
                Snapshot owned = factory.create();
                synchronized (this) { snapshot = owned; }
                if (owned == null) throw invalid();
                Runnable active = () -> { checkActive.run(); synchronized (Admission.this) { requireActive(); } };
                owned.materialize(maximum, source, active);
                active.run();
                synchronized (this) {
                    requireActive();
                    if (owned.size() < 1 || owned.size() > maximum || !canonicalHex(owned.sha256())) throw invalid();
                    endpoint = new StreamingFileShareTransfer(this, nonce, verifier, legacyMaximum);
                    success = true; return endpoint;
                }
            } finally {
                synchronized (this) { preparing = false; if (!success) cancelled = true; scheduleCleanup(); }
            }
        }
        private void requireActive() {
            long age = clock.millis() - created;
            if (age < 0 || age >= timeoutMs) cancelled = true;
            if (cancelled || cleaned) throw new IllegalStateException("File sharing cancelled or expired");
        }
        /** This revokes pending materialization too. Close never performs disk I/O on its caller. */
        @Override public synchronized void close() { cancelled = true; scheduleCleanup(); }
        private void scheduleCleanup() {
            if (!cancelled || cleaned || preparing || reading || cleanupScheduled) return;
            cleanupScheduled = true;
            try { CLEANUP.execute(this::cleanup); }
            catch (RuntimeException unavailable) { cleanupScheduled = false; /* Keep admission. */ }
        }
        private void cleanup() {
            boolean success = false;
            try {
                Snapshot owned; SnapshotWriter source;
                synchronized (this) { owned = snapshot; source = sourceOwner; }
                Exception failure = null;
                try { if (owned != null) owned.close(); } catch (Exception incomplete) { failure = incomplete; }
                try { if (source != null) source.close(); } catch (Exception incomplete) {
                    if (failure == null) failure = incomplete; else failure.addSuppressed(incomplete);
                }
                if (failure != null) throw failure;
                success = true;
            } catch (Exception incomplete) { /* Keep the slot and owned descriptors for a bounded retry. */ }
            synchronized (this) {
                cleanupScheduled = false;
                if (success) {
                    snapshot = null; sourceOwner = null; cleaned = true;
                    if (expiry != null) { expiry.cancel(false); expiry = null; }
                    guard.close();
                } else {
                    // Only local cleanup is retried, never a provider read or remote transaction.
                    if (expiry != null) expiry.cancel(false);
                    expiry = EXPIRY.schedule(this::close, 30, TimeUnit.SECONDS);
                }
            }
        }
        synchronized boolean cleanedForTest() { return cleaned; }
    }

    /** Fast revocation only; cleanup completion controls actual slot release. */
    @Override public void close() { admission.close(); }
    public void expire() {
        synchronized (admission) {
            try { admission.requireActive(); } catch (IllegalStateException expired) { admission.scheduleCleanup(); }
        }
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        // UID/certificate/source verification runs before all parsing, including descriptor probes.
        try { if (!verifier.allowed(Binder.getCallingUid())) throw denied(); }
        catch (RuntimeException failed) { throw denied(); }
        if ((code != READ && code != CANCEL) || flags != 0 || data == null || reply == null ||
                data.dataPosition() != 0 || data.dataSize() > FileShareTransfer.REQUEST_MAX_BYTES || data.hasFileDescriptors()) throw denied();
        long offset = 0;
        try {
            String descriptor = protocolVersion == 1 ? FileShareTransfer.DESCRIPTOR : DESCRIPTOR_V2;
            if (!descriptor.equals(readFixedString(data, descriptor.length())) || !nonce.equals(readFixedString(data, 64))) throw denied();
            if (code == READ) {
                if (data.dataAvail() != (protocolVersion == 1 ? 4 : 8)) throw denied();
                offset = protocolVersion == 1 ? data.readInt() : data.readLong();
            }
            if (data.dataAvail() != 0) throw denied();
        } catch (RuntimeException malformed) { throw denied(); }
        if (code == CANCEL) { close(); writeChunk(reply, new byte[0]); return true; }
        final Snapshot source;
        final int count;
        synchronized (admission) {
            try { admission.requireActive(); } catch (IllegalStateException inactive) { admission.scheduleCleanup(); throw denied(); }
            if (offset < 0 || offset != nextOffset || admission.reading || admission.snapshot == null) throw denied();
            source = admission.snapshot; count = (int)Math.min(CHUNK_BYTES, size - nextOffset); admission.reading = true;
        }
        byte[] bytes = new byte[count == 0 ? 1 : count];
        boolean success = false;
        try {
            if (count == 0) {
                if (source.read(bytes, 0, 1) != -1) throw denied();
            } else {
                int total = 0;
                while (total < count) {
                    int actual = source.read(bytes, total, count - total);
                    if (actual <= 0 || actual > count - total) throw denied();
                    total += actual;
                    synchronized (admission) { admission.requireActive(); }
                }
            }
            synchronized (admission) {
                admission.requireActive();
                writeChunk(reply, count == 0 ? new byte[0] : bytes);
                nextOffset += count;
                if (count == 0) admission.cancelled = true;
            }
            success = true; return true;
        } catch (Exception failure) { throw denied(); }
        finally {
            Arrays.fill(bytes, (byte)0);
            synchronized (admission) {
                admission.reading = false;
                if (!success) admission.cancelled = true;
                admission.scheduleCleanup();
            }
        }
    }

    /** Provisional output only. Exact length, EOF and hash are required; never retries Binder I/O. */
    public static void copy(IBinder binder, String nonce, long size, String sha256, int version,
            OutputStream output, Runnable checkActive) throws Exception {
        requireWorker();
        if (version == 1) {
            if (size < 1 || size > FileShareTransfer.MAX_BYTES) throw invalid();
            FileShareTransfer.copy(binder, nonce, (int)size, sha256, output, checkActive); return;
        }
        if (version != 2 || binder == null || !canonicalHex(nonce) || size < 1 || size > MAX_BYTES ||
                !canonicalHex(sha256) || output == null || checkActive == null) throw invalid();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long offset = 0;
        while (true) {
            checkActive.run();
            Parcel request = Parcel.obtain(), reply = Parcel.obtain();
            byte[] bytes;
            int expected = (int)Math.min(CHUNK_BYTES, size - offset);
            try {
                writeRequest(request, nonce); request.writeLong(offset);
                if (!binder.transact(READ, request, reply, 0)) throw invalid();
                checkActive.run(); bytes = readChunk(reply, expected);
            } finally { reply.recycle(); request.recycle(); }
            try {
                checkActive.run();
                if (expected == 0) break;
                digest.update(bytes); output.write(bytes); offset += expected;
                checkActive.run();
            } finally { Arrays.fill(bytes, (byte)0); }
        }
        if (!sha256.equals(hex(digest.digest()))) throw invalid();
        checkActive.run();
    }
    public static void cancel(IBinder binder, String nonce, int version) throws Exception {
        requireWorker();
        if (version == 1) { FileShareTransfer.cancel(binder, nonce); return; }
        if (version != 2 || binder == null || !canonicalHex(nonce)) throw invalid();
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            writeRequest(request, nonce);
            if (!binder.transact(CANCEL, request, reply, 0)) throw invalid();
            readChunk(reply, 0);
        } finally { reply.recycle(); request.recycle(); }
    }
    private static String readFixedString(Parcel parcel, int length) {
        if (parcel.dataAvail() < 4) throw denied();
        int start = parcel.dataPosition();
        if (parcel.readInt() != length || parcel.dataAvail() < aligned(length)) throw denied();
        parcel.setDataPosition(start); byte[] bytes = new byte[length]; parcel.readByteArray(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
    private static void writeRequest(Parcel parcel, String nonce) {
        parcel.writeByteArray(DESCRIPTOR_V2.getBytes(StandardCharsets.US_ASCII));
        parcel.writeByteArray(nonce.getBytes(StandardCharsets.US_ASCII));
    }
    private static void writeChunk(Parcel reply, byte[] bytes) { reply.writeInt(bytes.length); reply.writeByteArray(bytes); }
    private static byte[] readChunk(Parcel reply, int expected) throws FactoryException {
        try {
            if (reply.dataSize() > FileShareTransfer.REPLY_MAX_BYTES || reply.dataSize() != 8 + aligned(expected) || reply.hasFileDescriptors()) throw invalid();
            reply.setDataPosition(0);
            if (reply.readInt() != expected || reply.readInt() != expected) throw invalid();
            reply.setDataPosition(4); byte[] bytes = new byte[expected]; reply.readByteArray(bytes);
            if (reply.dataAvail() != 0) throw invalid();
            return bytes;
        } catch (RuntimeException malformed) { throw invalid(); }
    }
    private static int aligned(int count) { return (count + 3) & ~3; }
    private static boolean canonicalHex(String text) {
        if (text == null || text.length() != 64) return false;
        for (int i = 0; i < text.length(); i++) { char c = text.charAt(i); if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false; }
        return true;
    }
    static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2]; String alphabet = "0123456789abcdef";
        for (int i = 0; i < bytes.length; i++) { result[2*i] = alphabet.charAt((bytes[i] & 255) >>> 4); result[2*i+1] = alphabet.charAt(bytes[i] & 15); }
        return new String(result);
    }
    private static void requireWorker() { if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("File transfer requires a worker thread."); }
    private static FactoryException invalid() { return new FactoryException("INVALID_SHARE", "File share could not be completed."); }
    private static SecurityException denied() { return new SecurityException("File share request denied."); }
}
