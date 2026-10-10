package com.jarvys.factory.runtime;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import static org.junit.Assert.*;

/** Synthetic streams only; no user file/provider or device acceptance. */
public class DocumentSnapshotTest {
    private final Queue<Runnable> cleanup=new ConcurrentLinkedQueue<>();
    private long now;
    private final DocumentHandles handles=new DocumentHandles(() -> now,cleanup::add);
    @After public void close() { handles.revokeAll(); Runnable r; while((r=cleanup.poll())!=null) r.run(); }
    private void rejects(String code,Operation operation) throws Exception {
        try { operation.run(); fail("Expected "+code); } catch (FactoryException expected) { assertEquals(code,expected.code); }
    }
    private interface Operation { void run() throws Exception; }
    private void retired(String token) throws Exception { rejects("INVALID_HANDLE",() -> handles.read(token,0,1)); }
    @Test public void smallAndLargeBinarySnapshotsConsumeHandleAndCloseAsynchronously() throws Exception {
        for(int size:new int[]{1,32767,32768,32769,262144,8*1024*1024}) {
            byte[] data=new byte[size]; new Random(17).nextBytes(data);
            final boolean[] closed={false};
            String token=handles.grantRead(new ByteArrayInputStream(data) { public void close() { closed[0]=true; } });
            assertArrayEquals(data,handles.snapshotForShare(token)); retired(token);
            assertFalse(closed[0]); cleanup.remove().run(); assertTrue(closed[0]);
        }
    }
    @Test public void emptyOversizedAndNonterminatingStreamsCannotBecomeShares() throws Exception {
        String empty=handles.grantRead(new ByteArrayInputStream(new byte[0]));
        rejects("EMPTY_FILE",() -> handles.snapshotForShare(empty)); retired(empty); cleanup.remove().run();
        String large=handles.grantRead(new ByteArrayInputStream(new byte[8*1024*1024+1]));
        rejects("SHARE_TOO_LARGE",() -> handles.snapshotForShare(large)); retired(large); cleanup.remove().run();
        String infinite=handles.grantRead(new InputStream() { public int read(){return 1;} public int read(byte[] bytes){return bytes.length;} });
        rejects("SHARE_TOO_LARGE",() -> handles.snapshotForShare(infinite)); retired(infinite);
    }
    @Test public void partialWriteForeignAndExpiredHandlesCannotShare() throws Exception {
        String read=handles.grantRead(new ByteArrayInputStream(new byte[]{1,2})); handles.read(read,0,1);
        rejects("INVALID_OFFSET",() -> handles.snapshotForShare(read));
        String write=handles.grantWrite(new ByteArrayOutputStream()); rejects("WRONG_MODE",() -> handles.snapshotForShare(write));
        rejects("INVALID_HANDLE",() -> handles.snapshotForShare("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        now=DocumentHandles.LIFETIME_MILLIS; rejects("INVALID_HANDLE",() -> handles.snapshotForShare(read));
    }
    @Test public void providerFailureAndZeroReadConsumeAuthority() throws Exception {
        for(boolean zero:new boolean[]{true,false}) {
            String token=handles.grantRead(new InputStream() { public int read() throws IOException { throw new IOException(); }
                public int read(byte[] bytes) throws IOException {if(zero)return 0;throw new IOException();} });
            rejects("DOCUMENT_IO",() -> handles.snapshotForShare(token)); retired(token); cleanup.remove().run();
        }
    }
    @Test public void sharingPreservesSessionQuotaAndEofCannotProbePastIt() throws Exception {
        String chunk=Base64.getEncoder().encodeToString(new byte[32768]);
        for(int document=0;document<2;document++) {
            String write=handles.grantWrite(new ByteArrayOutputStream());
            int limit=document==0?16*1024*1024:16*1024*1024-32768;
            for(int n=0;n<limit;n+=32768) handles.write(write,n,chunk);
            if(document==1) handles.write(write,limit,Base64.getEncoder().encodeToString(new byte[32767]));
            handles.close(write); cleanup.remove().run();
        }
        final int[] reads={0};
        String token=handles.grantRead(new ByteArrayInputStream(new byte[]{1}) {public int read(byte[] bytes){reads[0]++;return super.read(bytes,0,bytes.length);}});
        rejects("DOCUMENT_QUOTA",() -> handles.snapshotForShare(token)); assertEquals(0,reads[0]); retired(token);
    }
    @Test public void cancellationWhileReadingDiscardsStaleBytesAndConcurrentReadIsBusy() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        String token=handles.grantRead(new InputStream(){public int read(){return 1;} public int read(byte[] bytes){entered.countDown();try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}bytes[0]=1;return 1;}});
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread reader=new Thread(() -> {try{handles.snapshotForShare(token);failure.set(new AssertionError("Stale snapshot"));}catch(Throwable e){failure.set(e);}}); reader.start();
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS)); rejects("HANDLE_BUSY",() -> handles.read(token,0,1));
            handles.cancelAll(); release.countDown(); reader.join(5000); assertFalse(reader.isAlive());
            assertTrue(failure.get() instanceof FactoryException); assertEquals("INVALID_HANDLE",((FactoryException)failure.get()).code); retired(token);
        } finally { release.countDown(); reader.join(5000); }
    }
}
