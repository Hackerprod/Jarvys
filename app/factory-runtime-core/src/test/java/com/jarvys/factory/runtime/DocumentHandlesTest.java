package com.jarvys.factory.runtime;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.After;
import static org.junit.Assert.*;

public class DocumentHandlesTest {
    interface Operation { void run() throws Exception; }
    static void fails(String code,Operation op) throws Exception {
        try { op.run(); fail("Expected " + code); } catch (FactoryException e) { assertEquals(code,e.code); assertFalse(e.getMessage().contains("secret")); }
    }
    static final List<Harness> HARNESSES=new ArrayList<>();
    @After public void releaseResources() {
        for (Harness h:HARNESSES) { h.handles.revokeAll(); h.cleanup(); } HARNESSES.clear();
    }
    static final class Harness {
        Harness() { HARNESSES.add(this); }
        long now; final Queue<Runnable> closes=new ConcurrentLinkedQueue<>();
        final DocumentHandles handles=new DocumentHandles(() -> now,closes::add);
        void cleanup() { Runnable r; while ((r=closes.poll()) != null) r.run(); }
    }
    @Test public void randomTokensAreOpaqueAndInstanceBound() throws Exception {
        Harness a=new Harness(),b=new Harness();
        String one=a.handles.grantRead(new ByteArrayInputStream(new byte[0]));
        String two=b.handles.grantRead(new ByteArrayInputStream(new byte[0]));
        assertTrue(one.matches("[0-9a-f]{64}")); assertNotEquals(one,two);
        fails("INVALID_HANDLE",() -> b.handles.read(one,0,1));
        fails("INVALID_HANDLE",() -> a.handles.read("/storage/secret",0,1));
    }
    @Test public void reservationsCountTowardLimitAndCancelReleasesSlot() throws Exception {
        Harness h=new Harness(); DocumentHandles.Reservation r=h.handles.reserveRead();
        for (int i=0;i<3;i++) h.handles.reserveWrite();
        fails("HANDLE_LIMIT",() -> h.handles.reserveRead()); r.cancel(); h.handles.reserveRead();
        fails("INVALID_HANDLE",() -> r.grantRead(new ByteArrayInputStream(new byte[0])));
        assertEquals(0,h.closes.size());
    }
    @Test public void grantsCannotChangeModeOrBeReused() throws Exception {
        Harness h=new Harness(); DocumentHandles.Reservation r=h.handles.reserveRead();
        fails("INVALID_HANDLE",() -> r.grantWrite(new ByteArrayOutputStream()));
        String token=r.grantRead(new ByteArrayInputStream(new byte[]{1}));
        fails("INVALID_HANDLE",() -> r.grantRead(new ByteArrayInputStream(new byte[]{2})));
        assertEquals("AQ==",h.handles.read(token,0,1).base64);
        fails("WRONG_MODE",() -> h.handles.write(token,1,"AQ=="));
        String write=h.handles.grantWrite(new ByteArrayOutputStream());
        fails("WRONG_MODE",() -> h.handles.read(write,0,1));
    }
    @Test public void sequentialReadsRoundTripAndExplicitEof() throws Exception {
        Harness h=new Harness(); String t=h.handles.grantRead(new ByteArrayInputStream(new byte[]{0,1,2,3,4}));
        DocumentHandles.ReadResult a=h.handles.read(t,0,3);
        assertEquals("AAEC",a.base64); assertEquals(0,a.offset); assertEquals(3,a.nextOffset); assertFalse(a.eof);
        fails("INVALID_OFFSET",() -> h.handles.read(t,0,3));
        DocumentHandles.ReadResult b=h.handles.read(t,3,3); assertEquals("AwQ=",b.base64); assertEquals(5,b.nextOffset);
        DocumentHandles.ReadResult end=h.handles.read(t,5,3); assertTrue(end.eof); assertEquals("",end.base64);
        assertTrue(h.handles.read(t,5,1).eof);
    }
    @Test public void writesAreSequentialAndDoNotFlushOrClaimCommit() throws Exception {
        Harness h=new Harness(); ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        String t=h.handles.grantWrite(bytes); DocumentHandles.WriteResult r=h.handles.write(t,0,"AAEC");
        assertEquals(3,r.bytesWritten); assertEquals(3,r.nextOffset);
        fails("INVALID_OFFSET",() -> h.handles.write(t,0,"Aw=="));
        fails("INVALID_OFFSET",() -> h.handles.write(t,-1,"Aw=="));
        h.handles.write(t,3,"Aw=="); assertArrayEquals(new byte[]{0,1,2,3},bytes.toByteArray());
        DocumentHandles.CloseResult close=h.handles.close(t); assertEquals("close_requested",close.status); assertFalse(close.providerCommitConfirmed);
        fails("INVALID_HANDLE",() -> h.handles.write(t,4,"AA=="));
    }
    @Test public void base64RejectsNonCanonicalBeforeProviderWrite() throws Exception {
        Harness h=new Harness(); ByteArrayOutputStream bytes=new ByteArrayOutputStream(); String t=h.handles.grantWrite(bytes);
        String[] invalid={"", "A", "AAA", "AAAA=", "AA=", "A===", "====", "AA=A", "AB==", "AAB=", "AA-_", "AA\n=", "éAAA", "AAA\t", "AA==AAAA"};
        for (String value:invalid) fails("INVALID_CHUNK",() -> h.handles.write(t,0,value));
        fails("INVALID_CHUNK",() -> h.handles.write(t,0,null));
        assertEquals(0,bytes.size()); h.handles.write(t,0,"/w=="); h.handles.write(t,1,"//8="); h.handles.write(t,3,"////");
        assertEquals(6,bytes.size());
    }
    @Test public void chunkBoundsAreChecked() throws Exception {
        Harness h=new Harness(); String r=h.handles.grantRead(new ByteArrayInputStream(new byte[0]));
        fails("INVALID_CHUNK",() -> h.handles.read(r,0,0));
        fails("INVALID_CHUNK",() -> h.handles.read(r,0,-1));
        fails("INVALID_CHUNK",() -> h.handles.read(r,0,32769));
        String w=h.handles.grantWrite(new ByteArrayOutputStream());
        fails("INVALID_CHUNK",() -> h.handles.write(w,0,Base64.getEncoder().encodeToString(new byte[32769])));
        assertEquals(32768,h.handles.write(w,0,Base64.getEncoder().encodeToString(new byte[32768])).bytesWritten);
    }
    @Test public void base64RoundTripsBinaryAcrossPaddingAndChunkBoundaries() throws Exception {
        Harness h=new Harness(); Random random=new Random(47);
        for (int length:new int[]{1,2,3,4,255,256,257,32766,32767,32768}) {
            byte[] data=new byte[length]; random.nextBytes(data);
            String r=h.handles.grantRead(new ByteArrayInputStream(data));
            String encoded=h.handles.read(r,0,length).base64;
            assertEquals(Base64.getEncoder().encodeToString(data),encoded);
            ByteArrayOutputStream output=new ByteArrayOutputStream(); String w=h.handles.grantWrite(output);
            h.handles.write(w,0,encoded); assertArrayEquals(data,output.toByteArray());
            h.handles.close(r); h.handles.close(w); h.cleanup();
        }
    }
    @Test public void expiryCleansPendingAndLiveWithoutFurtherUse() throws Exception {
        Harness h=new Harness(); DocumentHandles.Reservation pending=h.handles.reserveRead();
        String t=h.handles.grantRead(new ByteArrayInputStream(new byte[0]));
        h.now=DocumentHandles.LIFETIME_MILLIS-1; h.handles.expire(); assertEquals(0,h.closes.size());
        h.now++; h.handles.expire(); assertEquals(1,h.closes.size()); h.cleanup();
        fails("INVALID_HANDLE",() -> h.handles.read(t,0,1));
        fails("INVALID_HANDLE",() -> pending.grantRead(new ByteArrayInputStream(new byte[0])));
        for (int i=0;i<4;i++) h.handles.reserveRead();
    }
    @Test public void lifetimeDoesNotRefreshWithActivity() throws Exception {
        Harness h=new Harness(); String t=h.handles.grantRead(new ByteArrayInputStream(new byte[]{1,2}));
        h.now=DocumentHandles.LIFETIME_MILLIS-1; h.handles.read(t,0,1); h.now++;
        fails("INVALID_HANDLE",() -> h.handles.read(t,1,1));
    }
    @Test public void revocationRejectsStalePickerAndFutureReservations() throws Exception {
        Harness h=new Harness(); DocumentHandles.Reservation p=h.handles.reserveRead();
        String t=h.handles.grantWrite(new ByteArrayOutputStream()); h.handles.revokeAll(); h.handles.revokeAll();
        assertEquals(1,h.closes.size()); fails("SESSION_REVOKED",() -> h.handles.reserveRead());
        fails("INVALID_HANDLE",() -> p.grantRead(new ByteArrayInputStream(new byte[0])));
        fails("INVALID_HANDLE",() -> h.handles.write(t,0,"AA=="));
    }
    static final class Sink extends OutputStream { public void write(int b) {} public void write(byte[] b) {} }
    static final class Source extends InputStream {
        long read;
        public int read() { read++; return 0; }
        public int read(byte[] b) { read+=b.length; return b.length; }
    }
    static String chunk() { return Base64.getEncoder().encodeToString(new byte[DocumentHandles.MAX_CHUNK_BYTES]); }
    static void fill(DocumentHandles h,String token,long count) throws Exception {
        String value=chunk(); for (long offset=0;offset<count;offset+=32768) h.write(token,offset,value);
    }
    @Test public void handleAndSessionBudgetsNeverResetOnClose() throws Exception {
        Harness h=new Harness(); String a=h.handles.grantWrite(new Sink()); fill(h.handles,a,DocumentHandles.MAX_HANDLE_BYTES);
        fails("DOCUMENT_QUOTA",() -> h.handles.write(a,DocumentHandles.MAX_HANDLE_BYTES,"AA==")); h.handles.close(a);
        String b=h.handles.grantWrite(new Sink()); fill(h.handles,b,DocumentHandles.MAX_HANDLE_BYTES); h.handles.close(b);
        String c=h.handles.grantWrite(new Sink()); fails("DOCUMENT_QUOTA",() -> h.handles.write(c,0,"AA=="));
    }
    @Test public void readBudgetReservedBeforeIoAndCannotProbeOverQuota() throws Exception {
        Harness h=new Harness(); Source source=new Source(); String t=h.handles.grantRead(source);
        for (long offset=0;offset<DocumentHandles.MAX_HANDLE_BYTES;offset+=32768) h.handles.read(t,offset,32768);
        fails("DOCUMENT_QUOTA",() -> h.handles.read(t,DocumentHandles.MAX_HANDLE_BYTES,1));
        assertEquals(DocumentHandles.MAX_HANDLE_BYTES,source.read);
    }
    @Test public void shortReadsRefundUnusedBudgetAndEofCostsNothing() throws Exception {
        Harness h=new Harness(); String t=h.handles.grantRead(new ByteArrayInputStream(new byte[]{1}));
        h.handles.read(t,0,32768); h.handles.read(t,1,32768); h.handles.close(t);
        String a=h.handles.grantWrite(new Sink()); fill(h.handles,a,DocumentHandles.MAX_HANDLE_BYTES); h.handles.close(a);
        String b=h.handles.grantWrite(new Sink()); fill(h.handles,b,DocumentHandles.MAX_HANDLE_BYTES-32768);
        h.handles.write(b,DocumentHandles.MAX_HANDLE_BYTES-32768,Base64.getEncoder().encodeToString(new byte[32767]));
        fails("DOCUMENT_QUOTA",() -> h.handles.write(b,DocumentHandles.MAX_HANDLE_BYTES-1,"AA=="));
    }
    @Test public void uncertainWritesPoisonAndAttemptedBytesConsumeSessionBudget() throws Exception {
        Harness h=new Harness(); OutputStream failing=new OutputStream() { public void write(int b) throws IOException { throw new IOException("secret provider"); } };
        String bad=h.handles.grantWrite(failing); fails("DOCUMENT_IO",() -> h.handles.write(bad,0,chunk()));
        fails("INVALID_HANDLE",() -> h.handles.write(bad,0,"AA=="));
        String a=h.handles.grantWrite(new Sink()); fill(h.handles,a,DocumentHandles.MAX_HANDLE_BYTES); h.handles.close(a);
        String b=h.handles.grantWrite(new Sink()); fill(h.handles,b,DocumentHandles.MAX_HANDLE_BYTES-32768);
        fails("DOCUMENT_QUOTA",() -> h.handles.write(b,DocumentHandles.MAX_HANDLE_BYTES-32768,"AA=="));
    }
    @Test public void uncertainReadsPoisonAndReserveFullAttempt() throws Exception {
        Harness h=new Harness(); InputStream failing=new InputStream() { public int read() throws IOException { throw new IOException("secret"); } };
        String t=h.handles.grantRead(failing); fails("DOCUMENT_IO",() -> h.handles.read(t,0,32768));
        fails("INVALID_HANDLE",() -> h.handles.read(t,0,1));
        String a=h.handles.grantWrite(new Sink()); fill(h.handles,a,DocumentHandles.MAX_HANDLE_BYTES); h.handles.close(a);
        String b=h.handles.grantWrite(new Sink()); fill(h.handles,b,DocumentHandles.MAX_HANDLE_BYTES-32768);
        fails("DOCUMENT_QUOTA",() -> h.handles.write(b,DocumentHandles.MAX_HANDLE_BYTES-32768,"AA=="));
    }
    @Test public void zeroLengthProviderReadPoisons() throws Exception {
        Harness h=new Harness(); String t=h.handles.grantRead(new InputStream() { public int read() { return 0; } public int read(byte[] b) { return 0; } });
        fails("DOCUMENT_IO",() -> h.handles.read(t,0,1)); fails("INVALID_HANDLE",() -> h.handles.read(t,0,1));
    }
    @Test public void blockedReadDoesNotHoldLifecycleLockAndStaleBytesAreDiscarded() throws Exception {
        Harness h=new Harness(); CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        String t=h.handles.grantRead(new InputStream() {
            public int read() { return 0; }
            public int read(byte[] b) throws IOException { entered.countDown(); await(release); b[0]=42; return 1; }
        });
        AtomicReference<Throwable> outcome=new AtomicReference<>(); Thread worker=run(() -> h.handles.read(t,0,1),outcome);
        assertTrue(entered.await(2,TimeUnit.SECONDS));
        fails("HANDLE_BUSY",() -> h.handles.read(t,0,1));
        h.handles.close(t); assertEquals(1,h.closes.size()); release.countDown(); worker.join(2000);
        assertFalse(worker.isAlive()); assertEquals("INVALID_HANDLE",((FactoryException)outcome.get()).code);
    }
    @Test public void blockedWriteRevocationRejectsSuccessAndDoesNotBlock() throws Exception {
        Harness h=new Harness(); CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        String t=h.handles.grantWrite(new OutputStream() {
            public void write(int b) throws IOException { entered.countDown(); await(release); }
        });
        AtomicReference<Throwable> outcome=new AtomicReference<>(); Thread worker=run(() -> h.handles.write(t,0,"AA=="),outcome);
        assertTrue(entered.await(2,TimeUnit.SECONDS));
        fails("HANDLE_BUSY",() -> h.handles.write(t,0,"AA=="));
        h.handles.revokeAll(); release.countDown(); worker.join(2000);
        assertFalse(worker.isAlive()); assertEquals("INVALID_HANDLE",((FactoryException)outcome.get()).code);
    }
    @Test public void expiryDuringProviderIoDiscardsResult() throws Exception {
        Harness h=new Harness(); String t=h.handles.grantRead(new InputStream() {
            public int read() { return 0; }
            public int read(byte[] b) { h.now=DocumentHandles.LIFETIME_MILLIS; return 1; }
        });
        fails("INVALID_HANDLE",() -> h.handles.read(t,0,1)); assertEquals(1,h.closes.size());
    }
    @Test public void cancellationAllowsFreshAuthorityWithoutRevivingPending() throws Exception {
        Harness h=new Harness(); DocumentHandles.Reservation old=h.handles.reserveRead();
        String t=h.handles.grantWrite(new Sink()); fill(h.handles,t,DocumentHandles.MAX_HANDLE_BYTES);
        h.handles.cancelAll(); h.cleanup();
        fails("INVALID_HANDLE",() -> old.grantRead(new ByteArrayInputStream(new byte[0])));
        String fresh=h.handles.grantWrite(new Sink()); fill(h.handles,fresh,DocumentHandles.MAX_HANDLE_BYTES);
        h.handles.cancelAll(); h.cleanup();
        String exhausted=h.handles.grantWrite(new Sink()); fails("DOCUMENT_QUOTA",() -> h.handles.write(exhausted,0,"AA=="));
    }
    @Test public void outstandingProviderClosuresCountTowardAdmission() throws Exception {
        Harness h=new Harness();
        for (int i=0;i<4;i++) h.handles.close(h.handles.grantWrite(new Sink()));
        fails("HANDLE_LIMIT",() -> h.handles.reserveRead());
        h.cleanup(); h.handles.reserveRead();
    }
    @Test public void blockedNativeOpensKeepAdmissionAcrossCancellation() throws Exception {
        List<DocumentHandles.Reservation> opening=new ArrayList<>();
        for (int i=0;i<32;i++) {
            Harness h=new Harness();
            for (int j=0;j<4;j++) { DocumentHandles.Reservation r=h.handles.reserveRead(); r.beginOpen(); opening.add(r); }
            h.handles.cancelAll();
        }
        Harness extra=new Harness(); fails("HANDLE_LIMIT",() -> extra.handles.reserveRead());
        opening.remove(0).abortOpen(); extra.handles.reserveRead();
        for (DocumentHandles.Reservation r:opening) r.abortOpen();
    }
    @Test public void staleNativeOpenClosesBeforeReleasingAdmission() throws Exception {
        Harness h=new Harness(); DocumentHandles.Reservation r=h.handles.reserveRead(); r.beginOpen(); h.handles.cancelAll();
        fails("INVALID_HANDLE",() -> r.grantRead(new ByteArrayInputStream(new byte[0])));
        assertEquals(1,h.closes.size()); h.cleanup();
        for (int i=0;i<4;i++) h.handles.reserveRead();
    }
    @Test public void globalAdmissionSurvivesSessionReplacement() throws Exception {
        for (int i=0;i<32;i++) { Harness h=new Harness(); for (int j=0;j<4;j++) h.handles.reserveRead(); }
        Harness extra=new Harness(); fails("HANDLE_LIMIT",() -> extra.handles.reserveRead());
        HARNESSES.get(0).handles.cancelAll(); extra.handles.reserveRead();
    }
    @Test public void closeErrorsNeverResurrectHandleOrClaimDurability() throws Exception {
        Harness h=new Harness(); boolean[] closed={false}; String t=h.handles.grantWrite(new OutputStream() {
            public void write(int b) {} public void close() throws IOException { closed[0]=true; throw new IOException("secret"); }
        });
        DocumentHandles.CloseResult result=h.handles.close(t); assertFalse(closed[0]); assertFalse(result.providerCommitConfirmed);
        h.cleanup(); assertTrue(closed[0]); fails("INVALID_HANDLE",() -> h.handles.close(t));
    }
    @Test public void photoSnapshotChargesPersistAfterCancellationAndExhaustSession() throws Exception {
        Harness h=new Harness();
        for(int i=0;i<4;i++) {
            DocumentHandles.Reservation r=h.handles.reserveRead();
            r.chargeSnapshot(8*1024*1024);r.beginOpen();r.cancel();r.abortOpen();
        }
        DocumentHandles.Reservation refused=h.handles.reserveRead();
        fails("DOCUMENT_QUOTA",()->refused.chargeSnapshot(1));refused.cancel();
        String read=h.handles.grantRead(new ByteArrayInputStream(new byte[]{1}));
        fails("DOCUMENT_QUOTA",()->h.handles.read(read,0,1));
    }
    @Test public void photoSnapshotAndSubsequentReadsShareCumulativeBudget() throws Exception {
        Harness h=new Harness();
        DocumentHandles.Reservation r=h.handles.reserveRead();r.chargeSnapshot(8*1024*1024);r.beginOpen();
        String token=r.grantRead(new ByteArrayInputStream(new byte[8*1024*1024]));
        for(int i=0;i<256;i++) h.handles.read(token,i*32768L,32768);
        assertTrue(h.handles.read(token,8*1024*1024,1).eof);
        h.handles.close(token);h.cleanup();
        for(int i=0;i<2;i++) {
            DocumentHandles.Reservation extra=h.handles.reserveRead();extra.chargeSnapshot(8*1024*1024);extra.cancel();
        }
        DocumentHandles.Reservation refused=h.handles.reserveRead();fails("DOCUMENT_QUOTA",()->refused.chargeSnapshot(1));
    }
    static Thread run(Operation op,AtomicReference<Throwable> outcome) {
        Thread t=new Thread(() -> { try { op.run(); } catch (Throwable error) { outcome.set(error); } }); t.setDaemon(true); t.start(); return t;
    }
    static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(3,TimeUnit.SECONDS)) throw new IOException("timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(); }
    }
}
