package com.jarvys.factory.runtime;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic memory/Binder tests only: no device, user file, provider, or share-target I/O. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 32}, manifest = Config.NONE)
public class FileShareTransferTest {
    private static final String NONCE = repeat('a', 64), OTHER = repeat('b', 64);
    private final List<FileShareTransfer.Admission> admissions = new ArrayList<>();
    private final List<FileShareTransfer> endpoints = new ArrayList<>();
    private long now;
    interface Operation { void run() throws Exception; }
    interface ReplyWriter { void write(Parcel reply); }

    @After public void cleanup() {
        for (FileShareTransfer endpoint : endpoints) endpoint.close();
        for (FileShareTransfer.Admission admission : admissions) admission.close();
    }
    private FileShareTransfer.Admission reserve() throws Exception {
        FileShareTransfer.Admission admission = FileShareTransfer.reserve(() -> now);
        admissions.add(admission);
        return admission;
    }
    private FileShareTransfer endpoint(byte[] bytes) throws Exception {
        return endpoint(reserve(), bytes, uid -> true);
    }
    private FileShareTransfer endpoint(FileShareTransfer.Admission admission, byte[] bytes,
            FileShareTransfer.HostVerifier verifier) throws Exception {
        FileShareTransfer endpoint = admission.complete(bytes, NONCE, verifier);
        endpoints.add(endpoint);
        return endpoint;
    }
    private static String repeat(char c, int count) {
        char[] chars = new char[count]; Arrays.fill(chars, c); return new String(chars);
    }
    private static byte[] pattern(int count) {
        byte[] bytes = new byte[count];
        for (int i = 0; i < count; i++) bytes[i] = (byte) (i * 31 + 7);
        return bytes;
    }
    private static String digest(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
            text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
    private static void zeroed(byte[] bytes) {
        for (byte value : bytes) if (value != 0) fail("Retired snapshot was not wiped");
    }
    private static void fails(String code, Operation operation) throws Exception {
        try { operation.run(); fail("Expected " + code); }
        catch (FactoryException expected) { assertEquals(code, expected.code); }
    }
    private static void denied(Operation operation) throws Exception {
        try { operation.run(); fail("Malformed or unauthorized request accepted"); }
        catch (SecurityException expected) { assertEquals("File share request denied.", expected.getMessage()); }
    }
    private static <T> T worker(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Thread thread = new Thread(task, "synthetic-share-client");
        thread.setDaemon(true); thread.start();
        try { return task.get(10, TimeUnit.SECONDS); }
        catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }
    private static Parcel request(String nonce, Integer offset) {
        Parcel request = Parcel.obtain();
        request.writeString(FileShareTransfer.DESCRIPTOR);
        request.writeString(nonce);
        if (offset != null) request.writeInt(offset);
        request.setDataPosition(0);
        return request;
    }
    private static byte[] read(FileShareTransfer endpoint, String nonce, int offset) {
        Parcel request = request(nonce, offset), reply = Parcel.obtain();
        try {
            assertTrue(endpoint.onTransact(FileShareTransfer.TRANSACTION_READ, request, reply, 0));
            assertTrue(reply.dataSize() <= FileShareTransfer.REPLY_MAX_BYTES);
            reply.setDataPosition(0);
            int count = reply.readInt();
            byte[] bytes = reply.createByteArray();
            assertEquals(count, bytes.length); assertEquals(0, reply.dataAvail());
            return bytes;
        } finally { reply.recycle(); request.recycle(); }
    }
    private static void raw(FileShareTransfer endpoint, int code, Parcel request, int flags) {
        Parcel reply = Parcel.obtain();
        try {
            request.setDataPosition(0);
            endpoint.onTransact(code, request, reply, flags);
        } finally { reply.recycle(); request.recycle(); }
    }
    private static Binder remote(ReplyWriter writer) {
        return new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                writer.write(reply); return true;
            }
        };
    }
    private static void chunk(Parcel reply, byte[] bytes) {
        reply.writeInt(bytes.length); reply.writeByteArray(bytes);
    }
    private static void copy(IBinder binder, int size, String hash, OutputStream output,
            Runnable active) throws Exception {
        worker(() -> { FileShareTransfer.copy(binder, NONCE, size, hash, output, active); return null; });
    }

    @Test public void chunkedRoundTripsIncludePartial128KiBAndFull8MiB() throws Exception {
        for (int length : new int[] {1, 3, 32767, 32768, 32769, 131073, FileShareTransfer.MAX_BYTES}) {
            byte[] bytes = pattern(length);
            FileShareTransfer endpoint = endpoint(bytes);
            assertEquals(length, endpoint.size); assertEquals(digest(bytes), endpoint.sha256);
            AtomicInteger calls = new AtomicInteger();
            Binder forwarding = new Binder() {
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                    assertEquals(FileShareTransfer.TRANSACTION_READ, code);
                    assertTrue(data.dataSize() <= FileShareTransfer.REQUEST_MAX_BYTES);
                    assertEquals(0, flags);
                    calls.incrementAndGet();
                    return endpoint.onTransact(code, data, reply, flags);
                }
            };
            AtomicInteger written = new AtomicInteger();
            OutputStream output = new OutputStream() {
                @Override public void write(int value) { fail("Unexpected scalar write"); }
                @Override public void write(byte[] chunk) {
                    assertTrue(chunk.length <= FileShareTransfer.CHUNK_BYTES);
                    int start = written.getAndAdd(chunk.length);
                    for (int i = 0; i < chunk.length; i++)
                        if (chunk[i] != (byte) ((start + i) * 31 + 7)) fail("Changed snapshot byte");
                }
                @Override public void close() { fail("Caller owns output"); }
                @Override public void flush() { fail("Caller owns output"); }
            };
            copy(forwarding, length, endpoint.sha256, output, () -> { });
            assertEquals(length, written.get());
            assertEquals((length + FileShareTransfer.CHUNK_BYTES - 1) / FileShareTransfer.CHUNK_BYTES + 1,
                    calls.get());
            zeroed(bytes);
            denied(() -> read(endpoint, NONCE, length));
            FileShareTransfer.Admission next = reserve(); next.close();
        }
    }

    @Test public void reservationAndSnapshotShareOneProcessWideSlot() throws Exception {
        FileShareTransfer.Admission admission = reserve();
        fails("SHARE_BUSY", this::reserve);
        FileShareTransfer endpoint = endpoint(admission, pattern(8), uid -> true);
        admission.close(); // Successful complete transferred ownership.
        fails("SHARE_BUSY", this::reserve);
        assertEquals(8, read(endpoint, NONCE, 0).length);
        fails("SHARE_BUSY", this::reserve); // Data completion alone is not exact EOF.
        assertEquals(0, read(endpoint, NONCE, 8).length);
        FileShareTransfer.Admission next = reserve();
        endpoint.close(); admission.close(); // A stale close cannot release a newer admission.
        fails("SHARE_BUSY", this::reserve);
        next.close(); reserve();
    }

    @Test public void simultaneousAdmissionsGrantOnlyOneProcessWideOwner() throws Exception {
        CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
        List<FutureTask<FileShareTransfer.Admission>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            FutureTask<FileShareTransfer.Admission> task = new FutureTask<>(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Missing start signal");
                try { return FileShareTransfer.reserve(() -> now); }
                catch (FactoryException busy) { assertEquals("SHARE_BUSY", busy.code); return null; }
            });
            tasks.add(task);
            Thread thread = new Thread(task, "synthetic-share-admission");
            thread.setDaemon(true); thread.start();
        }
        try { assertTrue(ready.await(5, TimeUnit.SECONDS)); }
        finally { start.countDown(); }
        int winners = 0;
        for (FutureTask<FileShareTransfer.Admission> task : tasks) {
            FileShareTransfer.Admission admission = task.get(5, TimeUnit.SECONDS);
            if (admission != null) { winners++; admissions.add(admission); }
        }
        assertEquals(1, winners);
        fails("SHARE_BUSY", this::reserve);
        admissions.get(0).close(); reserve();
    }

    @Test public void failedCompleteDoesNotTakeArrayOwnershipOrReleaseAdmission() throws Exception {
        FileShareTransfer.Admission admission = reserve();
        byte[] bytes = pattern(3), original = bytes.clone();
        for (String nonce : new String[] {null, "", repeat('a', 63), repeat('A', 64), repeat('g', 64), "/secret/path"})
            fails("INVALID_SHARE", () -> admission.complete(bytes, nonce, uid -> true));
        fails("INVALID_SHARE", () -> admission.complete(null, NONCE, uid -> true));
        fails("INVALID_SHARE", () -> admission.complete(new byte[0], NONCE, uid -> true));
        fails("INVALID_SHARE", () -> admission.complete(bytes, NONCE, null));
        fails("INVALID_SHARE", () -> admission.complete(new byte[FileShareTransfer.MAX_BYTES + 1], NONCE, uid -> true));
        assertArrayEquals(original, bytes);
        fails("SHARE_BUSY", this::reserve);
        FileShareTransfer endpoint = endpoint(admission, bytes, uid -> true);
        fails("INVALID_SHARE", () -> admission.complete(pattern(1), NONCE, uid -> true));
        endpoint.close(); zeroed(bytes);
    }

    @Test public void closedAdmissionCannotCompleteAndCloseIsIdempotent() throws Exception {
        FileShareTransfer.Admission admission = reserve();
        admission.close(); admission.close();
        fails("INVALID_SHARE", () -> admission.complete(pattern(1), NONCE, uid -> true));
        reserve(); admission.close(); fails("SHARE_BUSY", this::reserve);
    }

    @Test public void expiredPendingMaterializationRetainsSlotUntilOwnerCleanup() throws Exception {
        FileShareTransfer.Admission admission = reserve();
        byte[] bytes = pattern(3), original = bytes.clone();
        now = FileShareTransfer.LIFETIME_MILLIS;
        fails("SHARE_BUSY", this::reserve);
        fails("INVALID_SHARE", () -> admission.complete(bytes, NONCE, uid -> true));
        assertArrayEquals(original, bytes);
        fails("SHARE_BUSY", this::reserve);
        Arrays.fill(bytes, (byte) 0); admission.close(); reserve();
    }

    @Test public void exactMonotonicExpiryWipesAndReleasesCompletedSnapshot() throws Exception {
        byte[] bytes = pattern(FileShareTransfer.CHUNK_BYTES + 1);
        FileShareTransfer endpoint = endpoint(bytes);
        now = FileShareTransfer.LIFETIME_MILLIS - 1; endpoint.expire();
        assertEquals(FileShareTransfer.CHUNK_BYTES, read(endpoint, NONCE, 0).length);
        now++; endpoint.expire(); endpoint.expire();
        zeroed(bytes);
        denied(() -> read(endpoint, NONCE, FileShareTransfer.CHUNK_BYTES));
        reserve();
    }

    @Test public void expiryIsCheckedByReadAndAdmissionWithoutExplicitExpiryCall() throws Exception {
        byte[] bytes = pattern(2); FileShareTransfer endpoint = endpoint(bytes);
        now = FileShareTransfer.LIFETIME_MILLIS;
        denied(() -> read(endpoint, NONCE, 0)); zeroed(bytes);
        FileShareTransfer replacement = endpoint(pattern(3));
        now += FileShareTransfer.LIFETIME_MILLIS;
        reserve(); denied(() -> read(replacement, NONCE, 0));
    }

    @Test public void clockReversalFailsClosed() throws Exception {
        now = 10; byte[] bytes = pattern(2); FileShareTransfer endpoint = endpoint(bytes);
        now = 9; endpoint.expire(); zeroed(bytes); reserve();
    }

    @Test public void originalCallingUidIsVerifiedForEveryTransactionIncludingUnknown() throws Exception {
        int expectedUid = Binder.getCallingUid(); AtomicInteger verified = new AtomicInteger();
        FileShareTransfer endpoint = endpoint(reserve(), pattern(2), uid -> {
            assertEquals(expectedUid, uid); assertEquals(Binder.getCallingUid(), uid);
            verified.incrementAndGet(); return true;
        });
        read(endpoint, NONCE, 0);
        denied(() -> read(endpoint, OTHER, 2));
        denied(() -> raw(endpoint, IBinder.INTERFACE_TRANSACTION, request(NONCE, 2), 0));
        denied(() -> raw(endpoint, FileShareTransfer.TRANSACTION_READ, request(NONCE, 2), IBinder.FLAG_ONEWAY));
        read(endpoint, NONCE, 2);
        denied(() -> read(endpoint, NONCE, 2));
        assertEquals(6, verified.get());
    }

    @Test public void wrongUidIsRejectedBeforeParsingAndNeverAdvancesOrWipes() throws Exception {
        AtomicInteger verified = new AtomicInteger(); byte[] bytes = pattern(3);
        FileShareTransfer endpoint = endpoint(reserve(), bytes, uid -> { verified.incrementAndGet(); return false; });
        denied(() -> read(endpoint, NONCE, 0));
        denied(() -> raw(endpoint, FileShareTransfer.TRANSACTION_CANCEL, request(NONCE, null), 0));
        Parcel nonsense = Parcel.obtain(); nonsense.writeInt(Integer.MAX_VALUE);
        denied(() -> raw(endpoint, IBinder.INTERFACE_TRANSACTION, nonsense, 0));
        assertEquals(3, verified.get()); assertArrayEquals(pattern(3), bytes);
        fails("SHARE_BUSY", this::reserve);
    }

    @Test public void verifierFailureIsGenericAndDoesNotGrantOrConsumeAuthority() throws Exception {
        byte[] bytes = pattern(3);
        FileShareTransfer endpoint = endpoint(reserve(), bytes, uid -> {
            throw new IllegalStateException("sensitive package-manager details");
        });
        denied(() -> read(endpoint, NONCE, 0));
        assertArrayEquals(pattern(3), bytes);
        fails("SHARE_BUSY", this::reserve);
    }

    @Test public void offsetsAreStrictlySequentialAndReplayNeverSucceeds() throws Exception {
        FileShareTransfer endpoint = endpoint(pattern(FileShareTransfer.CHUNK_BYTES + 3));
        for (int bad : new int[] {-1, 1, FileShareTransfer.CHUNK_BYTES, Integer.MAX_VALUE})
            denied(() -> read(endpoint, NONCE, bad));
        assertEquals(FileShareTransfer.CHUNK_BYTES, read(endpoint, NONCE, 0).length);
        denied(() -> read(endpoint, NONCE, 0));
        denied(() -> read(endpoint, NONCE, FileShareTransfer.CHUNK_BYTES + 3));
        assertEquals(3, read(endpoint, NONCE, FileShareTransfer.CHUNK_BYTES).length);
        denied(() -> read(endpoint, NONCE, FileShareTransfer.CHUNK_BYTES));
        assertEquals(0, read(endpoint, NONCE, FileShareTransfer.CHUNK_BYTES + 3).length);
        denied(() -> read(endpoint, NONCE, FileShareTransfer.CHUNK_BYTES + 3));
    }

    @Test public void nonceAndDescriptorCannotBeReplacedOrOmitted() throws Exception {
        FileShareTransfer endpoint = endpoint(pattern(1));
        for (String nonce : new String[] {null, "", OTHER, repeat('a', 63), repeat('a', 65)})
            denied(() -> read(endpoint, nonce, 0));
        Parcel wrong = Parcel.obtain(); wrong.writeString(repeat('x', FileShareTransfer.DESCRIPTOR.length()));
        wrong.writeString(NONCE); wrong.writeInt(0);
        denied(() -> raw(endpoint, FileShareTransfer.TRANSACTION_READ, wrong, 0));
        assertEquals(1, read(endpoint, NONCE, 0).length);
    }

    @Test public void trailingFieldsOversizeAndMalformedStringsAreRejectedBeforeReading() throws Exception {
        FileShareTransfer endpoint = endpoint(pattern(2));
        Parcel trailing = request(NONCE, 0); trailing.setDataPosition(trailing.dataSize()); trailing.writeInt(32);
        denied(() -> raw(endpoint, FileShareTransfer.TRANSACTION_READ, trailing, 0));
        denied(() -> raw(endpoint, FileShareTransfer.TRANSACTION_READ, request(NONCE, null), 0));
        Parcel oversized = request(NONCE, 0); oversized.setDataPosition(oversized.dataSize());
        oversized.writeByteArray(new byte[FileShareTransfer.REQUEST_MAX_BYTES]);
        denied(() -> raw(endpoint, FileShareTransfer.TRANSACTION_READ, oversized, 0));
        for (int length : new int[] {-1, 0, Integer.MAX_VALUE, FileShareTransfer.DESCRIPTOR.length()}) {
            Parcel malformed = Parcel.obtain(); malformed.writeInt(length);
            denied(() -> raw(endpoint, FileShareTransfer.TRANSACTION_READ, malformed, 0));
        }
        Parcel nonceLength = Parcel.obtain(); nonceLength.writeString(FileShareTransfer.DESCRIPTOR);
        nonceLength.writeInt(Integer.MAX_VALUE);
        denied(() -> raw(endpoint, FileShareTransfer.TRANSACTION_READ, nonceLength, 0));
        assertEquals(2, read(endpoint, NONCE, 0).length);
    }

    @Test public void cancelIsAuthenticatedIdempotentAndCannotIncludeOffsetOrExtraFields() throws Exception {
        byte[] bytes = pattern(12); FileShareTransfer endpoint = endpoint(bytes);
        denied(() -> raw(endpoint, FileShareTransfer.TRANSACTION_CANCEL, request(OTHER, null), 0));
        denied(() -> raw(endpoint, FileShareTransfer.TRANSACTION_CANCEL, request(NONCE, 0), 0));
        assertArrayEquals(pattern(12), bytes);
        worker(() -> { FileShareTransfer.cancel(endpoint, NONCE); FileShareTransfer.cancel(endpoint, NONCE); return null; });
        zeroed(bytes); denied(() -> read(endpoint, NONCE, 0)); reserve();
    }

    @Test public void cancelledEndpointCannotReleaseAnotherSnapshotSlot() throws Exception {
        FileShareTransfer retired = endpoint(pattern(1)); retired.close(); retired.close();
        FileShareTransfer current = endpoint(pattern(2));
        worker(() -> { FileShareTransfer.cancel(retired, NONCE); return null; });
        fails("SHARE_BUSY", this::reserve);
        assertEquals(2, read(current, NONCE, 0).length);
    }

    @Test public void copyAndCancelRejectMainThreadBeforeAnyBinderCall() throws Exception {
        AtomicInteger calls = new AtomicInteger(); Binder binder = remote(reply -> calls.incrementAndGet());
        try { FileShareTransfer.copy(binder, NONCE, 0, digest(new byte[0]), new ByteArrayOutputStream(), () -> { }); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("worker")); }
        try { FileShareTransfer.cancel(binder, NONCE); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("worker")); }
        assertEquals(0, calls.get());
    }

    @Test public void clientMetadataIsCanonicalAndBoundedBeforeBinder() throws Exception {
        AtomicInteger calls = new AtomicInteger(); Binder binder = remote(reply -> calls.incrementAndGet());
        for (int size : new int[] {-1, 0, FileShareTransfer.MAX_BYTES + 1, Integer.MAX_VALUE})
            fails("INVALID_SHARE", () -> copy(binder, size, NONCE, new ByteArrayOutputStream(), () -> { }));
        for (String hash : new String[] {null, "", repeat('A', 64), repeat('g', 64), repeat('a', 63), repeat('a', 65)})
            fails("INVALID_SHARE", () -> copy(binder, 1, hash, new ByteArrayOutputStream(), () -> { }));
        fails("INVALID_SHARE", () -> copy(null, 0, NONCE, new ByteArrayOutputStream(), () -> { }));
        fails("INVALID_SHARE", () -> copy(binder, 1, NONCE, null, () -> { }));
        fails("INVALID_SHARE", () -> copy(binder, 1, NONCE, new ByteArrayOutputStream(), null));
        fails("INVALID_SHARE", () -> worker(() -> { FileShareTransfer.copy(binder, "/path", 0, NONCE,
                new ByteArrayOutputStream(), () -> { }); return null; }));
        fails("INVALID_SHARE", () -> worker(() -> { FileShareTransfer.cancel(binder, "/path"); return null; }));
        assertEquals(0, calls.get());
    }

    @Test public void malformedRemoteChunkLengthsAreRejectedWithoutOutput() throws Exception {
        List<ReplyWriter> replies = Arrays.asList(
                reply -> { },
                reply -> reply.writeInt(1),
                reply -> { reply.writeInt(-1); reply.writeByteArray(new byte[1]); },
                reply -> { reply.writeInt(0); reply.writeByteArray(new byte[1]); },
                reply -> { reply.writeInt(2); reply.writeByteArray(new byte[1]); },
                reply -> { reply.writeInt(Integer.MAX_VALUE); reply.writeInt(Integer.MAX_VALUE); reply.writeInt(0); },
                reply -> { reply.writeInt(1); reply.writeInt(Integer.MAX_VALUE); reply.writeInt(0); },
                reply -> { reply.writeInt(1); reply.writeByteArray(null); reply.writeInt(0); },
                reply -> { reply.writeInt(1); reply.writeByteArray(new byte[2]); },
                reply -> { chunk(reply, new byte[1]); reply.writeInt(0); },
                reply -> chunk(reply, new byte[FileShareTransfer.REPLY_MAX_BYTES + 1]));
        for (ReplyWriter malformed : replies) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            fails("INVALID_SHARE", () -> copy(remote(malformed), 1, NONCE, output, () -> { }));
            assertEquals(0, output.size());
        }
    }

    @Test public void falseTransactIsFailureWithoutRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Binder unsupported = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                calls.incrementAndGet(); return false;
            }
        };
        fails("INVALID_SHARE", () -> copy(unsupported, 1, NONCE, new ByteArrayOutputStream(), () -> { }));
        assertEquals(1, calls.get());
        fails("INVALID_SHARE", () -> worker(() -> { FileShareTransfer.cancel(unsupported, NONCE); return null; }));
        assertEquals(2, calls.get());
    }

    @Test public void shorterLongerAndWrongHashCannotCompleteSuccessfully() throws Exception {
        FileShareTransfer shortSource = endpoint(pattern(1));
        fails("INVALID_SHARE", () -> copy(shortSource, 2, digest(pattern(2)), new ByteArrayOutputStream(), () -> { }));
        shortSource.close();
        FileShareTransfer longSource = endpoint(pattern(2));
        fails("INVALID_SHARE", () -> copy(longSource, 1, digest(pattern(1)), new ByteArrayOutputStream(), () -> { }));
        longSource.close();
        FileShareTransfer wrongHash = endpoint(pattern(3));
        fails("INVALID_SHARE", () -> copy(wrongHash, 3, NONCE, new ByteArrayOutputStream(), () -> { }));
        reserve(); // Exact EOF was consumed even though caller's expected digest was incorrect.
    }

    @Test public void exactEofIsRequiredAfterAllBytes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Binder extra = remote(reply -> { calls.incrementAndGet(); chunk(reply, pattern(1)); });
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        fails("INVALID_SHARE", () -> copy(extra, 1, digest(pattern(1)), output, () -> { }));
        assertArrayEquals(pattern(1), output.toByteArray()); assertEquals(2, calls.get());
        AtomicInteger nullEofCalls = new AtomicInteger();
        fails("INVALID_SHARE", () -> copy(remote(reply -> {
            if (nullEofCalls.incrementAndGet() == 1) chunk(reply, pattern(1));
            else { reply.writeInt(0); reply.writeByteArray(null); }
        }), 1, digest(pattern(1)), new ByteArrayOutputStream(), () -> { }));
    }

    @Test public void cancelReplyIsAlsoStrictAndBounded() throws Exception {
        for (ReplyWriter malformed : Arrays.<ReplyWriter>asList(
                reply -> { }, reply -> chunk(reply, new byte[1]),
                reply -> { chunk(reply, new byte[0]); reply.writeInt(1); },
                reply -> { reply.writeInt(0); reply.writeInt(Integer.MAX_VALUE); },
                reply -> { reply.writeInt(0); reply.writeByteArray(null); }))
            fails("INVALID_SHARE", () -> worker(() -> { FileShareTransfer.cancel(remote(malformed), NONCE); return null; }));
    }

    @Test public void callerCancellationBeforeAndAfterBinderPreventsOutputAndRetries() throws Exception {
        for (int cancelAt : new int[] {1, 2, 3}) {
            AtomicInteger calls = new AtomicInteger(), checks = new AtomicInteger();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Binder binder = remote(reply -> { calls.incrementAndGet(); chunk(reply, pattern(1)); });
            try {
                copy(binder, 1, digest(pattern(1)), output, () -> {
                    if (checks.incrementAndGet() == cancelAt) throw new IllegalStateException("cancelled");
                }); fail();
            } catch (IllegalStateException expected) { assertEquals("cancelled", expected.getMessage()); }
            assertEquals(cancelAt == 1 ? 0 : 1, calls.get()); assertEquals(0, output.size());
        }
    }

    @Test public void cancellationAfterWriteStopsBeforeSecondChunk() throws Exception {
        AtomicInteger calls = new AtomicInteger(), checks = new AtomicInteger();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Binder binder = remote(reply -> { calls.incrementAndGet(); chunk(reply, pattern(FileShareTransfer.CHUNK_BYTES)); });
        try {
            copy(binder, FileShareTransfer.CHUNK_BYTES + 1, NONCE, output, () -> {
                if (checks.incrementAndGet() == 4) throw new IllegalStateException("cancelled");
            }); fail();
        } catch (IllegalStateException expected) { assertEquals("cancelled", expected.getMessage()); }
        assertEquals(1, calls.get()); assertEquals(FileShareTransfer.CHUNK_BYTES, output.size());
    }

    @Test public void outputFailureDoesNotRetryAndWipesTransientChunk() throws Exception {
        AtomicInteger calls = new AtomicInteger(); final byte[][] received = {null};
        Binder binder = remote(reply -> { calls.incrementAndGet(); chunk(reply, pattern(1)); });
        OutputStream output = new OutputStream() {
            @Override public void write(int value) { fail(); }
            @Override public void write(byte[] bytes) throws IOException {
                received[0] = bytes; throw new IOException("synthetic output failure");
            }
        };
        try { copy(binder, 1, digest(pattern(1)), output, () -> { }); fail(); }
        catch (IOException expected) { assertEquals("synthetic output failure", expected.getMessage()); }
        assertEquals(1, calls.get()); assertNotNull(received[0]); zeroed(received[0]);
    }
}
