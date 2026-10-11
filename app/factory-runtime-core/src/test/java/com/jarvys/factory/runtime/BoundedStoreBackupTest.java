package com.jarvys.factory.runtime;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static com.jarvys.factory.runtime.DatabaseRequestTest.repeat;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class BoundedStoreBackupTest {
    private static final class Backend implements BoundedStore.Backend {
        final Object lock=new Object(); final Map<String,String> values=new HashMap<>();
        int writes; boolean confirmed=true, throwAfterMutation; Runnable beforeWrite=()->{}, afterWrite=()->{};
        @Override public Object transactionLock(){return lock;}
        @Override public Map<String,String> read(){assertTrue(Thread.holdsLock(lock));return new HashMap<>(values);}
        @Override public boolean replace(Map<String,String> replacement){assertTrue(Thread.holdsLock(lock));values.clear();values.putAll(replacement);return true;}
        @Override public boolean putSelected(Map<String,String> selected){
            assertTrue(Thread.holdsLock(lock));beforeWrite.run();writes++;values.putAll(selected);afterWrite.run();
            if(throwAfterMutation)throw new IllegalStateException("private raw backend error");return confirmed;
        }
    }
    private StorageBackupFormat.Archive archive() throws Exception { return StorageBackupFormatTest.archive(new JSONObject().put("theme","dark")); }
    private BoundedStore.BackupBaseline baseline(BoundedStore store,StorageBackupFormat.Archive archive) throws Exception{return store.backupBaseline(archive,()->true);}
    @Test public void exportRequiresEveryRequestedKeyAndPreservesStringData() throws Exception {
        Backend backend=new Backend();BoundedStore store=new BoundedStore(backend);backend.values.put("a","");backend.values.put("theme","日本語\u0000");backend.values.put("unselected","private");
        StorageBackupFormat.Archive archive=store.backupExport(StorageBackupFormatTest.APP,StorageBackupFormatTest.CERT,1,Arrays.asList("a","theme"),()->true);
        assertEquals(Arrays.asList("a","theme"),archive.keys);assertEquals("",archive.data().getString("a"));assertEquals("日本語\u0000",archive.data().getString("theme"));assertFalse(archive.data().has("unselected"));
        assertEquals("BACKUP_CONFLICT",assertThrows(FactoryException.class,()->store.backupExport(StorageBackupFormatTest.APP,StorageBackupFormatTest.CERT,1,Arrays.asList("a","missing"),()->true)).code);
        assertThrows(FactoryException.class,()->store.backupExport(StorageBackupFormatTest.APP,StorageBackupFormatTest.CERT,2,Collections.singletonList("a"),()->true));assertEquals(0,backend.writes);
    }
    @Test public void insertOnlyAndReplaceNeverDeleteUnselectedData() throws Exception {
        Backend backend=new Backend();BoundedStore store=new BoundedStore(backend);backend.values.put("unselected","keep");StorageBackupFormat.Archive archive=archive();
        BoundedStore.BackupBaseline before=baseline(store,archive);assertFalse(before.present().getBoolean("theme"));before.present().put("theme",true);assertFalse(before.present().getBoolean("theme"));
        JSONObject result=store.backupImport(archive,"insertOnly",before,()->true);assertTrue(result.getBoolean("committed"));assertEquals(1,result.getInt("count"));assertEquals("keep",backend.values.get("unselected"));
        BoundedStore.BackupBaseline collision=baseline(store,archive);
        assertEquals("BACKUP_CONFLICT",assertThrows(FactoryException.class,()->store.backupImport(archive,"insertOnly",collision,()->true)).code);assertEquals(1,backend.writes);
        store.set("theme","light");BoundedStore.BackupBaseline replacing=baseline(store,archive);
        store.set("unselected","new independent value");store.set("other","retained");
        store.backupImport(archive,"replaceSelectedKeys",replacing,()->true);
        assertEquals("dark",backend.values.get("theme"));assertEquals("new independent value",backend.values.get("unselected"));assertEquals("retained",backend.values.get("other"));assertEquals(3,backend.values.size());
    }
    @Test public void preimageDistinguishesAbsenceEmptyAndEverySelectedChange() throws Exception {
        Backend backend=new Backend();BoundedStore store=new BoundedStore(backend);StorageBackupFormat.Archive archive=archive();
        BoundedStore.BackupBaseline absent=baseline(store,archive);store.set("theme","");BoundedStore.BackupBaseline empty=baseline(store,archive);
        assertNotEquals(absent.preimageSha256,empty.preimageSha256);assertTrue(empty.present().getBoolean("theme"));
        assertEquals("BACKUP_CONFLICT",assertThrows(FactoryException.class,()->store.backupImport(archive,"replaceSelectedKeys",absent,()->true)).code);
        store.set("theme","light");assertEquals("BACKUP_CONFLICT",assertThrows(FactoryException.class,()->store.backupImport(archive,"replaceSelectedKeys",empty,()->true)).code);
        BoundedStore.BackupBaseline present=baseline(store,archive);store.remove("theme");assertEquals("BACKUP_CONFLICT",assertThrows(FactoryException.class,()->store.backupImport(archive,"replaceSelectedKeys",present,()->true)).code);assertEquals(0,backend.writes);
    }
    @Test public void baselineIsBoundToArchiveAndBackend() throws Exception {
        Backend backend=new Backend();BoundedStore store=new BoundedStore(backend);StorageBackupFormat.Archive archive=archive();BoundedStore.BackupBaseline baseline=baseline(store,archive);
        StorageBackupFormat.Archive changed=StorageBackupFormatTest.archive(new JSONObject().put("theme","light"));
        assertEquals("BACKUP_CONFLICT",assertThrows(FactoryException.class,()->store.backupImport(changed,"insertOnly",baseline,()->true)).code);
        assertEquals("BACKUP_CONFLICT",assertThrows(FactoryException.class,()->new BoundedStore(new Backend()).backupImport(archive,"insertOnly",baseline,()->true)).code);
        assertEquals("BACKUP_CONFLICT",assertThrows(FactoryException.class,()->store.backupImport(archive,"insertOnly",null,()->true)).code);
        new BoundedStore(backend).backupImport(archive,"insertOnly",baseline,()->true);assertEquals(1,backend.writes);
    }
    @Test public void quotaUsesFreshCompleteMapAfterReviewAndAllFailuresAreAtomic() throws Exception {
        Backend backend=new Backend();BoundedStore store=new BoundedStore(backend);StorageBackupFormat.Archive archive=archive();
        for(int i=0;i<255;i++)backend.values.put("k"+i,"x");BoundedStore.BackupBaseline baseline=baseline(store,archive);store.set("last","x");
        assertEquals("QUOTA_EXCEEDED",assertThrows(FactoryException.class,()->store.backupImport(archive,"insertOnly",baseline,()->true)).code);assertEquals(0,backend.writes);assertFalse(backend.values.containsKey("theme"));assertEquals(256,backend.values.size());
        backend.values.clear();for(int i=0;i<16;i++)backend.values.put("k"+i,repeat("x",65000));
        StorageBackupFormat.Archive large=StorageBackupFormatTest.archive(new JSONObject().put("theme",repeat("x",10000)));
        BoundedStore.BackupBaseline largeBaseline=baseline(store,large);
        assertEquals("QUOTA_EXCEEDED",assertThrows(FactoryException.class,()->store.backupImport(large,"insertOnly",largeBaseline,()->true)).code);assertEquals(0,backend.writes);assertFalse(backend.values.containsKey("theme"));
        backend.values.clear();backend.values.put("theme",repeat("x",65536));store.backupImport(archive,"replaceSelectedKeys",baseline(store,archive),()->true);assertEquals("dark",backend.values.get("theme"));
    }
    @Test public void cancellationBeforeMutationPreventsWriteAndAfterCommitDoesNotClaimRollback() throws Exception {
        Backend backend=new Backend();BoundedStore store=new BoundedStore(backend);StorageBackupFormat.Archive archive=archive();BoundedStore.BackupBaseline baseline=baseline(store,archive);
        assertEquals("CANCELLED",assertThrows(FactoryException.class,()->store.backupImport(archive,"insertOnly",baseline,()->false)).code);
        AtomicInteger checks=new AtomicInteger();assertEquals("CANCELLED",assertThrows(FactoryException.class,()->store.backupImport(archive,"insertOnly",baseline,()->checks.incrementAndGet()<4)).code);assertEquals(0,backend.writes);
        AtomicBoolean allowed=new AtomicBoolean(true);backend.afterWrite=()->allowed.set(false);
        JSONObject result=store.backupImport(archive,"insertOnly",baseline,allowed::get);
        assertTrue(result.getBoolean("committed"));assertEquals("dark",backend.values.get("theme"));assertEquals(1,backend.writes);
    }
    @Test public void failedOrThrowingCommitHasUnknownOutcomeWithNoRollbackOrReplay() throws Exception {
        for(boolean throwsFailure:new boolean[]{false,true}) {
            Backend backend=new Backend();backend.confirmed=false;backend.throwAfterMutation=throwsFailure;backend.values.put("unselected","keep");
            BoundedStore store=new BoundedStore(backend);StorageBackupFormat.Archive archive=archive();BoundedStore.BackupBaseline baseline=baseline(store,archive);
            FactoryException result=assertThrows(FactoryException.class,()->store.backupImport(archive,"insertOnly",baseline,()->true));
            assertEquals("OUTCOME_UNKNOWN",result.code);assertFalse(result.getMessage().contains("private raw"));assertEquals("dark",backend.values.get("theme"));assertEquals("keep",backend.values.get("unselected"));assertEquals(1,backend.writes);
            assertEquals("BACKUP_CONFLICT",assertThrows(FactoryException.class,()->store.backupImport(archive,"insertOnly",baseline,()->true)).code);assertEquals(1,backend.writes);
        }
    }
    @Test public void importAndOrdinaryStorageShareTheTransactionLockAcrossInstances() throws Exception {
        Backend backend=new Backend();BoundedStore importing=new BoundedStore(backend),ordinary=new BoundedStore(backend);StorageBackupFormat.Archive archive=archive();
        BoundedStore.BackupBaseline baseline=baseline(importing,archive);CountDownLatch committing=new CountDownLatch(1),release=new CountDownLatch(1),started=new CountDownLatch(1),finished=new CountDownLatch(1);
        backend.beforeWrite=()->{committing.countDown();try {assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException failure){throw new AssertionError(failure);}};
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Future<?> importResult=workers.submit(()->{try{importing.backupImport(archive,"insertOnly",baseline,()->true);}catch(Exception e){throw new RuntimeException(e);}});
            assertTrue(committing.await(5,TimeUnit.SECONDS));
            Future<?> setResult=workers.submit(()->{started.countDown();try{ordinary.set("other","keep");finished.countDown();}catch(Exception e){throw new RuntimeException(e);}});
            assertTrue(started.await(5,TimeUnit.SECONDS));assertFalse(finished.await(100,TimeUnit.MILLISECONDS));release.countDown();
            importResult.get(5,TimeUnit.SECONDS);setResult.get(5,TimeUnit.SECONDS);assertEquals("dark",backend.values.get("theme"));assertEquals("keep",backend.values.get("other"));
        } finally {release.countDown();workers.shutdownNow();}
    }
    @Test public void previewBackendRemainsInMemoryAndUsesSameImportRules() throws Exception {
        MemoryBackend backend=new MemoryBackend();BoundedStore store=new BoundedStore(backend);StorageBackupFormat.Archive archive=archive();store.set("other","keep");
        store.backupImport(archive,"insertOnly",baseline(store,archive),()->true);assertEquals("dark",store.get("theme"));assertEquals("keep",store.get("other"));backend.clear();assertTrue(store.list().isEmpty());
    }
}
