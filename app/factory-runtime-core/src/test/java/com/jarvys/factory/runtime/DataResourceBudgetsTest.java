package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ResourceBudgets;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public class DataResourceBudgetsTest {
    static ResourceBudgets budget(String section,Object... fields) {
        Map<String,Object> members=new LinkedHashMap<>(), root=new LinkedHashMap<>();
        for(int i=0;i<fields.length;i+=2) members.put((String)fields[i],fields[i+1]);
        root.put("version",1); root.put(section,members); return ResourceBudgets.parse(root);
    }
    private static final class Backend implements BoundedStore.Backend {
        Map<String,String> values=new HashMap<>(); int writes;
        public Map<String,String> read() { return new HashMap<>(values); }
        public boolean replace(Map<String,String> next) { writes++; values=new HashMap<>(next); return true; }
    }
    private static final class Sink extends OutputStream {
        long written;
        public void write(int value) { written++; }
        public void write(byte[] bytes) { written+=bytes.length; }
    }
    private static final class Harness {
        long now; final Queue<Runnable> cleanup=new ConcurrentLinkedQueue<>(); final DocumentHandles handles;
        Harness(ResourceBudgets budgets) { handles=new DocumentHandles(()->now,cleanup::add,budgets); }
        void drain() { Runnable next; while((next=cleanup.poll())!=null) next.run(); }
    }
    private final List<Harness> harnesses=new ArrayList<>();
    private Harness handles(ResourceBudgets budgets) { Harness result=new Harness(budgets); harnesses.add(result); return result; }
    @After public void closeHandles() { for(Harness harness:harnesses) { harness.handles.revokeAll(); harness.drain(); } }
    private static void quota(Checked action) throws Exception {
        try { action.run(); fail("Expected quota rejection"); }
        catch(FactoryException failure) { assertEquals("QUOTA_EXCEEDED",failure.code); }
    }
    interface Checked { void run() throws Exception; }
    private static void fails(String code,Checked action) throws Exception {
        try { action.run(); fail("Expected "+code); }
        catch(FactoryException failure) { assertEquals(code,failure.code); }
    }
    @Test public void fixedEnvelopeConstantsAgreeWithImmutableContractCeilings() {
        assertEquals(ResourceBudgets.DOCUMENT_CHUNK_BYTES,DocumentHandles.MAX_CHUNK_BYTES);
        assertEquals(ResourceBudgets.DATABASE_REQUEST_BYTES,DatabaseRequest.MAX_ARGUMENT_BYTES);
        assertEquals(ResourceBudgets.DATABASE_RESPONSE_BYTES,DatabaseRequest.MAX_RESULT_BYTES);
        assertEquals(ResourceBudgets.maxima().database.tables,DatabaseRequest.HARD_MAX_TABLES);
        assertEquals(ResourceBudgets.maxima().database.columnsPerTable,DatabaseRequest.HARD_MAX_COLUMNS);
        assertEquals(ResourceBudgets.maxima().database.rows,DatabaseRequest.HARD_MAX_ROWS);
        assertEquals(ResourceBudgets.maxima().database.mainFileBytes,DatabaseRequest.HARD_MAX_DATABASE_BYTES);
        assertEquals(ResourceBudgets.legacy().storage.totalBytes,BoundedStore.MAX_TOTAL_BYTES);
        assertEquals(ResourceBudgets.legacy().storage.entries,BoundedStore.MAX_ENTRIES);
    }
    @Test public void storageCapacityIsPerArtifactAndLoweringNeverDeletesData() throws Exception {
        Backend backend=new Backend(); BoundedStore legacy=new BoundedStore(backend);
        legacy.set("first","abcdefghij"); legacy.set("second","retained");
        BoundedStore small=new BoundedStore(backend,budget("storage","entries",1,"totalBytes",8,"valueBytes",3));
        assertEquals("abcdefghij",small.get("first")); assertEquals(2,small.list().size());
        int before=backend.writes;
        quota(()->small.set("third","x")); quota(()->small.set("first","abcdefghijk"));
        assertEquals(before,backend.writes);
        small.set("first","abcdefgh"); assertEquals("abcdefgh",small.get("first"));
        small.remove("second"); small.set("first","a");
        assertEquals(Collections.singletonList("first"),small.list());
        quota(()->small.set("first","abcd")); assertEquals("a",small.get("first"));
    }
    @Test public void storageBackupUsesSameLoweredAdmissionAndPreservesUnselectedEntries() throws Exception {
        Backend backend=new Backend(); backend.values.put("theme","very-long-existing-theme"); backend.values.put("extra","kept");
        BoundedStore small=new BoundedStore(backend,budget("storage","entries",1,"totalBytes",8,"valueBytes",3));
        StorageBackupFormat.Archive archive=StorageBackupFormatTest.archive(new org.json.JSONObject().put("theme","dark"));
        BoundedStore.BackupBaseline before=small.backupBaseline(archive,()->true);
        assertTrue(small.backupImport(archive,"replaceSelectedKeys",before,()->true).getBoolean("committed"));
        assertEquals("dark",small.get("theme")); assertEquals("kept",small.get("extra"));
        StorageBackupFormat.Archive growth=StorageBackupFormatTest.archive(new org.json.JSONObject().put("theme","longer"));
        BoundedStore.BackupBaseline current=small.backupBaseline(growth,()->true);
        quota(()->small.backupImport(growth,"replaceSelectedKeys",current,()->true));
        assertEquals("dark",small.get("theme"));
    }
    @Test public void customDocumentReservationRefundAndCloseNeverResetCumulativeBytes() throws Exception {
        Harness h=handles(budget("documents","openHandles",1,"handleBytes",3L,"sessionBytes",4L));
        String read=h.handles.grantRead(new ByteArrayInputStream(new byte[]{1}));
        assertEquals(1,h.handles.read(read,0,3).nextOffset);
        h.handles.close(read);
        fails("HANDLE_LIMIT",()->h.handles.reserveRead()); // Actual close completion owns admission.
        h.drain(); String write=h.handles.grantWrite(new Sink());
        h.handles.write(write,0,"AQID");
        fails("DOCUMENT_QUOTA",()->h.handles.write(write,3,"AA=="));
        h.handles.close(write); h.drain();
        String next=h.handles.grantWrite(new Sink());
        fails("DOCUMENT_QUOTA",()->h.handles.write(next,0,"AA=="));
        h.handles.cancelAll(); h.drain();
        String afterCancel=h.handles.grantWrite(new Sink());
        fails("DOCUMENT_QUOTA",()->h.handles.write(afterCancel,0,"AA=="));
    }
    @Test public void expandedDocumentSlotsAndLeaseRemainFinite() throws Exception {
        Harness h=handles(budget("documents","openHandles",6,"leaseMs",DocumentHandles.LIFETIME_MILLIS+1000));
        List<DocumentHandles.Reservation> pending=new ArrayList<>();
        for(int i=0;i<6;i++) pending.add(h.handles.reserveRead());
        fails("HANDLE_LIMIT",()->h.handles.reserveRead());
        h.now=DocumentHandles.LIFETIME_MILLIS; h.handles.expire();
        String token=pending.get(0).grantRead(new ByteArrayInputStream(new byte[0]));
        assertTrue(h.handles.read(token,0,1).eof);
        h.now+=1000; h.handles.expire();
        fails("INVALID_HANDLE",()->h.handles.read(token,0,1)); h.drain();
        for(int i=0;i<6;i++) h.handles.reserveRead();
    }
    @Test public void documentOffsetsBeyondTwoGiBAreLongAndDoNotAllocateFileSizedBuffers() throws Exception {
        Harness h=handles(ResourceBudgets.maxima()); Sink sink=new Sink(); String token=h.handles.grantWrite(sink);
        Field entries=DocumentHandles.class.getDeclaredField("entries"); entries.setAccessible(true);
        Object entry=((Map<?,?>)entries.get(h.handles)).get(token);
        Field offset=entry.getClass().getDeclaredField("offset"), charged=entry.getClass().getDeclaredField("charged");
        offset.setAccessible(true); charged.setAccessible(true);
        long alreadyStreamed=2147483648L; offset.setLong(entry,alreadyStreamed); charged.setLong(entry,alreadyStreamed);
        Field shared=DocumentHandles.class.getDeclaredField("ledger"); shared.setAccessible(true); Object ledger=shared.get(h.handles);
        Field session=ledger.getClass().getDeclaredField("charged"); session.setAccessible(true); session.setLong(ledger,alreadyStreamed);
        assertEquals(alreadyStreamed+1,h.handles.write(token,alreadyStreamed,"AA==").nextOffset);
        assertEquals(1,sink.written); assertEquals(alreadyStreamed+1,session.getLong(ledger));
        fails("INVALID_CHUNK",()->h.handles.read(token,alreadyStreamed+1,DocumentHandles.MAX_CHUNK_BYTES+1));
    }
    @Test public void pageAuthorityRenewalKeepsBytesAndActualCloseAdmission() throws Exception {
        Harness h=handles(budget("documents","openHandles",1,"handleBytes",3L,"sessionBytes",3L));
        String old=h.handles.grantWrite(new Sink()); h.handles.write(old,0,"AQI=");
        DocumentHandles next=h.handles.revokeAndRenew();
        try {
            fails("INVALID_HANDLE",()->h.handles.write(old,2,"AA=="));
            fails("SESSION_REVOKED",()->h.handles.reserveRead());
            fails("HANDLE_LIMIT",()->next.reserveWrite());
            h.drain(); String live=next.grantWrite(new Sink()); next.write(live,0,"AA==");
            fails("DOCUMENT_QUOTA",()->next.write(live,1,"AA=="));
            DocumentHandles finalPage=next.revokeAndRenew();
            try {
                fails("HANDLE_LIMIT",()->finalPage.reserveRead()); h.drain();
                String fresh=finalPage.grantWrite(new Sink());
                fails("DOCUMENT_QUOTA",()->finalPage.write(fresh,0,"AA=="));
            } finally { finalPage.revokeAll(); h.drain(); }
        } finally { next.revokeAll(); h.drain(); }
    }
    @Test public void pendingNativeOpenCannotBypassAdmissionAcrossPageRenewal() throws Exception {
        Harness h=handles(budget("documents","openHandles",1));
        DocumentHandles.Reservation old=h.handles.reserveRead(); old.beginOpen();
        DocumentHandles next=h.handles.revokeAndRenew();
        try {
            fails("HANDLE_LIMIT",()->next.reserveRead());
            fails("INVALID_HANDLE",()->old.grantRead(new ByteArrayInputStream(new byte[0])));
            fails("HANDLE_LIMIT",()->next.reserveRead()); h.drain();
            DocumentHandles.Reservation fresh=next.reserveRead(); fresh.cancel();
            fails("SESSION_REVOKED",()->h.handles.reserveRead());
        } finally { next.revokeAll(); h.drain(); }
    }
    @Test public void nativeOutputReservationsRefundUnusedBytesExactlyOnce() throws Exception {
        Harness h=handles(budget("documents","handleBytes",6L,"sessionBytes",8L));
        DocumentHandles.NativeByteReservation output=h.handles.reserveNativeBytes(6);
        fails("DOCUMENT_QUOTA",()->h.handles.reserveNativeBytes(3));
        fails("INVALID_ARGUMENT",()->output.commit(7));
        fails("DOCUMENT_QUOTA",()->h.handles.reserveNativeBytes(3)); // Invalid commit retained full claim.
        output.commit(3); output.commit(3);
        fails("INVALID_ARGUMENT",()->output.commit(4));
        DocumentHandles.NativeByteReservation pending=h.handles.reserveNativeBytes(5);
        output.close(); output.close();
        fails("DOCUMENT_QUOTA",()->h.handles.reserveNativeBytes(1)); // Committed bytes were not refunded.
        pending.close(); pending.close();
        fails("INVALID_HANDLE",()->pending.commit(0));
        DocumentHandles.NativeByteReservation empty=h.handles.reserveNativeBytes(5);
        empty.commit(0); empty.close();
        try(DocumentHandles.NativeByteReservation available=h.handles.reserveNativeBytes(5)) {
            fails("DOCUMENT_QUOTA",()->h.handles.reserveNativeBytes(1));
        }
    }
    @Test public void staleNativeCaptureClaimsPersistUntilCleanupAcrossPageAndCancel() throws Exception {
        Harness h=handles(budget("documents","handleBytes",6L,"sessionBytes",6L,"leaseMs",10L));
        DocumentHandles.NativeByteReservation old=h.handles.reserveNativeBytes(6);
        DocumentHandles next=h.handles.revokeAndRenew();
        try {
            fails("SESSION_REVOKED",()->old.commit(1));
            fails("SESSION_REVOKED",()->h.handles.reserveNativeBytes(1));
            fails("DOCUMENT_QUOTA",()->next.reserveNativeBytes(1));
            old.close(); // The producer owner has stopped and discarded its incomplete output.
            DocumentHandles.NativeByteReservation cancelled=next.reserveNativeBytes(6);
            next.cancelAll(); fails("INVALID_HANDLE",()->cancelled.commit(1));
            fails("DOCUMENT_QUOTA",()->next.reserveNativeBytes(1)); cancelled.close();
            DocumentHandles.NativeByteReservation expired=next.reserveNativeBytes(6);
            h.now=10; fails("INVALID_HANDLE",()->expired.commit(1));
            fails("DOCUMENT_QUOTA",()->next.reserveNativeBytes(1)); expired.close();
            try(DocumentHandles.NativeByteReservation available=next.reserveNativeBytes(6)) { available.commit(0); }
        } finally { old.close(); next.revokeAll(); h.drain(); }
    }
    @Test public void concurrentNativeCapturesReserveLongBudgetsBeforeAnyOutput() throws Exception {
        final long gib=1024L*1024*1024;
        Harness h=handles(budget("documents","handleBytes",3*gib,"sessionBytes",4*gib));
        java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(2), go=new java.util.concurrent.CountDownLatch(1);
        Queue<DocumentHandles.NativeByteReservation> claims=new ConcurrentLinkedQueue<>();
        java.util.concurrent.atomic.AtomicInteger rejected=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> unexpected=new java.util.concurrent.atomic.AtomicReference<>();
        Runnable capture=()-> {
            try {
                ready.countDown(); go.await(); claims.add(h.handles.reserveNativeBytes(3*gib));
            } catch(FactoryException failure) {
                if("DOCUMENT_QUOTA".equals(failure.code)) rejected.incrementAndGet(); else unexpected.set(failure);
            } catch(Throwable failure) { unexpected.set(failure); }
        };
        Thread first=new Thread(capture), second=new Thread(capture); first.start(); second.start();
        try { assertTrue(ready.await(2,java.util.concurrent.TimeUnit.SECONDS)); }
        finally { go.countDown(); }
        first.join(2000); second.join(2000);
        assertFalse(first.isAlive()); assertFalse(second.isAlive()); assertNull(unexpected.get());
        assertEquals(1,claims.size()); assertEquals(1,rejected.get());
        DocumentHandles.NativeByteReservation completed=claims.remove(); completed.commit(2*gib); completed.close();
        fails("DOCUMENT_QUOTA",()->h.handles.reserveNativeBytes(3*gib));
        try(DocumentHandles.NativeByteReservation remaining=h.handles.reserveNativeBytes(2*gib)) {
            fails("DOCUMENT_QUOTA",()->h.handles.reserveNativeBytes(1));
        }
    }
    @Test public void uncertainDocumentIoConsumesReservationAtCustomLimits() throws Exception {
        Harness h=handles(budget("documents","handleBytes",3L,"sessionBytes",3L));
        String broken=h.handles.grantWrite(new OutputStream() { public void write(int value) throws IOException { throw new IOException(); } });
        fails("DOCUMENT_IO",()->h.handles.write(broken,0,"AQID")); h.drain();
        String next=h.handles.grantWrite(new Sink()); fails("DOCUMENT_QUOTA",()->h.handles.write(next,0,"AA=="));
    }
    @Test public void failedReadCloseRetainsAdmissionAcrossPageRenewal() throws Exception {
        Harness h=handles(budget("documents","openHandles",1));
        String token=h.handles.grantRead(new ByteArrayInputStream(new byte[0]) {
            @Override public void close() throws IOException { throw new IOException("synthetic close failure"); }
        });
        h.handles.close(token); h.drain();
        DocumentHandles next=h.handles.revokeAndRenew();
        fails("HANDLE_LIMIT",()->next.reserveRead()); next.revokeAll(); h.drain();
    }
    @Test public void failedPoisonedWriteCloseRetainsAdmissionAcrossPageRenewal() throws Exception {
        Harness h=handles(budget("documents","openHandles",1));
        String token=h.handles.grantWrite(new OutputStream() {
            public void write(int value) throws IOException { throw new IOException("synthetic write failure"); }
            @Override public void close() throws IOException { throw new IOException("synthetic close failure"); }
        });
        fails("DOCUMENT_IO",()->h.handles.write(token,0,"AA==")); h.drain();
        DocumentHandles next=h.handles.revokeAndRenew();
        fails("HANDLE_LIMIT",()->next.reserveWrite()); next.revokeAll(); h.drain();
    }
    @Test public void failedStaleNativeOpenCloseRetainsAdmissionAcrossPageRenewal() throws Exception {
        Harness h=handles(budget("documents","openHandles",1));
        DocumentHandles.Reservation pending=h.handles.reserveRead(); pending.beginOpen();
        DocumentHandles next=h.handles.revokeAndRenew();
        fails("INVALID_HANDLE",()->pending.grantRead(new ByteArrayInputStream(new byte[0]) {
            @Override public void close() throws IOException { throw new IOException("synthetic close failure"); }
        })); h.drain();
        fails("HANDLE_LIMIT",()->next.reserveRead()); next.revokeAll(); h.drain();
    }

}
