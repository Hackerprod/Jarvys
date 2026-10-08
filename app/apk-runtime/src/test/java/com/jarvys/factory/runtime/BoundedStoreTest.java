package com.jarvys.factory.runtime;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class BoundedStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    /** Durable fake verifies the store contract; Android sandbox/SAF still require device testing. */
    private static final class FileBackend implements BoundedStore.Backend {
        final File file;
        FileBackend(File file) { this.file = file; }
        public Map<String, String> read() {
            Properties data = new Properties();
            try { if (file.exists()) try (FileInputStream input = new FileInputStream(file)) { data.load(input); } }
            catch (Exception e) { throw new IllegalStateException(e); }
            Map<String, String> result = new HashMap<>();
            for (String key : data.stringPropertyNames()) result.put(key, data.getProperty(key));
            return result;
        }
        public boolean replace(Map<String, String> values) {
            Properties data = new Properties(); data.putAll(values);
            try (FileOutputStream output = new FileOutputStream(file)) { data.store(output, null); return true; }
            catch (Exception e) { return false; }
        }
    }
    @Test public void persistsAcrossStoreInstancesAndIsolatesBackends() throws Exception {
        File appA = new File(temporary.getRoot(), "app-a.prefs");
        File appB = new File(temporary.getRoot(), "app-b.prefs");
        new BoundedStore(new FileBackend(appA)).set("notes.v1", "private note");
        BoundedStore restored = new BoundedStore(new FileBackend(appA));
        BoundedStore other = new BoundedStore(new FileBackend(appB));
        assertEquals("private note", restored.get("notes.v1")); assertNull(other.get("notes.v1"));
        other.set("notes.v1", "independent"); assertEquals("private note", restored.get("notes.v1"));
        restored.remove("notes.v1"); assertNull(new BoundedStore(new FileBackend(appA)).get("notes.v1"));
        assertEquals("independent", other.get("notes.v1"));
    }
    @Test public void sortedListAndEntryQuota() throws Exception {
        BoundedStore store = new BoundedStore(new FileBackend(new File(temporary.getRoot(), "quota.prefs")));
        for (int i = 0; i < BoundedStore.MAX_ENTRIES; i++) store.set(String.format("key.%03d", i), "value");
        assertEquals("key.000", store.list().get(0));
        try { store.set("overflow", "x"); fail(); } catch (FactoryException e) { assertEquals("QUOTA_EXCEEDED", e.code); }
        store.set("key.000", "replacement"); assertEquals("replacement", store.get("key.000"));
        store.remove("key.001"); store.set("new", "accepted");
    }
    @Test public void byteQuotaIsEnforcedWithoutMutatingData() throws Exception {
        BoundedStore store = new BoundedStore(new FileBackend(new File(temporary.getRoot(), "bytes.prefs")));
        String big = new String(new char[65536]).replace('\0', 'a');
        for (int i = 0; i < 15; i++) store.set("k" + i, big);
        try { store.set("overflow", big); fail(); } catch (FactoryException e) { assertEquals("QUOTA_EXCEEDED", e.code); }
        assertNull(store.get("overflow")); assertEquals(15, store.list().size());
        try { store.set("tooBig", big + "x"); fail(); } catch (FactoryException e) { assertEquals("INVALID_ARGUMENT", e.code); }
    }
    @Test public void failedDurableWriteReportsFailure() throws Exception {
        BoundedStore store = new BoundedStore(new BoundedStore.Backend() {
            public Map<String, String> read() { return new HashMap<>(); }
            public boolean replace(Map<String, String> values) { return false; }
        });
        try { store.set("note", "content"); fail(); } catch (FactoryException e) { assertEquals("STORAGE_ERROR", e.code); }
    }
    @Test public void overlappingActivityStoresCannotLoseEachOthersKeys() throws Exception {
        Map<String, String> persisted = new HashMap<>();
        CountDownLatch firstRead = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        BoundedStore.Backend backend = new BoundedStore.Backend() {
            public Map<String, String> read() {
                Map<String, String> snapshot;
                synchronized (persisted) { snapshot = new HashMap<>(persisted); }
                if (Thread.currentThread().getName().equals("factory-first-writer")) {
                    firstRead.countDown();
                    try { if (!releaseFirst.await(5, TimeUnit.SECONDS)) throw new AssertionError("First read was not released"); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                }
                return snapshot;
            }
            public boolean replace(Map<String, String> values) {
                synchronized (persisted) { persisted.clear(); persisted.putAll(values); }
                return true;
            }
        };
        BoundedStore oldActivity = new BoundedStore(backend);
        BoundedStore newActivity = new BoundedStore(backend);
        Thread first = new Thread(() -> {
            try { oldActivity.set("oldActivity", "first"); } catch (Throwable e) { failure.set(e); }
        }, "factory-first-writer");
        Thread second = new Thread(() -> {
            try { newActivity.set("newActivity", "second"); } catch (Throwable e) { failure.set(e); }
        }, "factory-second-writer");
        first.start();
        assertTrue(firstRead.await(5, TimeUnit.SECONDS));
        second.start();
        // With the previous per-instance lock, second finishes before the first commits and
        // its key is overwritten. With the process-wide transaction lock it must block.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (second.isAlive() && second.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield();
        boolean blockedByTransaction = second.getState() == Thread.State.BLOCKED;
        releaseFirst.countDown();
        first.join(5000); second.join(5000);
        assertFalse(first.isAlive()); assertFalse(second.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertTrue("Both Activity instances must share one read/quota/write transaction lock", blockedByTransaction);
        assertEquals("first", oldActivity.get("oldActivity"));
        assertEquals("second", newActivity.get("newActivity"));
    }

}
