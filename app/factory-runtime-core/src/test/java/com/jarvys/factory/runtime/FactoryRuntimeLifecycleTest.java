package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.FrameLayout;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Host lifecycle checks only. Robolectric does not prove Chromium behavior or device isolation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class FactoryRuntimeLifecycleTest {
    private FactoryRuntime runtime;
    private final CountDownLatch release = new CountDownLatch(1);
    private static class Host implements FactoryRuntime.Host {
        final Object lock = new Object();
        final MemoryBackend memory = new MemoryBackend();
        volatile boolean active = true;
        boolean preview = true;
        BoundedStore.Backend backend = memory;
        public InputStream open(String path) { return new ByteArrayInputStream(new byte[0]); }
        public String configuration() { return FactoryDispatcherTest.configuration("[\"storage\"]"); }
        public boolean isPreview() { return preview; }
        public boolean isActive() { return active; }
        public boolean isSessionOpen() { return active; }
        public Object lifecycleLock() { return lock; }
        public BoundedStore.Backend storage() { return backend; }
        public void resetStorage() { memory.clear(); }
        void revoke() { synchronized (lock) { active = false; } }
    }
    public static class ExportActivity extends Activity {
        final CountDownLatch revokeEntered = new CountDownLatch(1), releaseRevoke = new CountDownLatch(1);
        volatile Uri revokedUri;
        volatile int revokedModes;
        @Override public boolean hasWindowFocus() { return true; }
        @Override public void revokeUriPermission(Uri uri, int modes) {
            revokedUri = uri; revokedModes = modes; revokeEntered.countDown();
            awaitUninterruptibly(releaseRevoke);
        }
    }
    private void create(Host host) throws Exception {
        create(host, Robolectric.buildActivity(Activity.class).setup().get());
    }
    private void create(Host host, Activity activity) throws Exception {
        runtime = new FactoryRuntime(activity, new FrameLayout(activity), host);
        field("config").set(runtime, FactoryDispatcherTest.config());
        field("store").set(runtime, new BoundedStore(host.backend));
    }
    private Field field(String name) throws Exception {
        Field f = FactoryRuntime.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private void request() throws Exception {
        Method receive = FactoryRuntime.class.getDeclaredMethod("receive", String.class, androidx.webkit.JavaScriptReplyProxy.class);
        receive.setAccessible(true);
        receive.invoke(runtime, new JSONObject().put("v", 1).put("id", "queued").put("method", "storage.set")
                .put("args", new JSONObject().put("key", "key").put("value", "fixture")).toString(), null);
    }
    private ThreadPoolExecutor executor() throws Exception { return (ThreadPoolExecutor) field("io").get(runtime); }
    private void blockWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        executor().execute(() -> { entered.countDown(); awaitUninterruptibly(release); });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
    }
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) { try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; } }
        if (interrupted) Thread.currentThread().interrupt();
    }
    @After public void cleanup() throws Exception {
        release.countDown();
        if (runtime != null) { runtime.close(); assertTrue(executor().awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test public void queuedPreviewMutationCannotRunAfterRevocation() throws Exception {
        Host host = new Host(); create(host); blockWorker(); request(); host.revoke();
        release.countDown(); executor().shutdown(); assertTrue(executor().awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(host.memory.read().isEmpty());
    }
    @Test public void closeDropsQueuedMutationAndClearsRam() throws Exception {
        Host host = new Host(); create(host); host.memory.replace(java.util.Collections.singletonMap("old", "value"));
        blockWorker(); request(); runtime.close(); release.countDown();
        assertTrue(executor().awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(host.memory.read().isEmpty()); assertFalse(runtime.start()); assertFalse(runtime.reset());
    }
    @Test public void pageInvalidationDropsOldQueueWithoutClearingStoredData() throws Exception {
        Host host = new Host(); create(host); host.memory.replace(java.util.Collections.singletonMap("old", "value"));
        blockWorker(); request();
        Method invalidate = FactoryRuntime.class.getDeclaredMethod("invalidate"); invalidate.setAccessible(true);
        synchronized (host.lock) { invalidate.invoke(runtime); }
        release.countDown(); executor().shutdown(); assertTrue(executor().awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(java.util.Collections.singletonMap("old", "value"), host.memory.read());
        assertTrue(((Map<?, ?>) field("pending").get(runtime)).isEmpty());
    }
    @Test(timeout = 10000) public void closeNeverWaitsForAlreadyStartedInstalledStorageIo() throws Exception {
        Host host = new Host(); host.preview = false;
        CountDownLatch entered = new CountDownLatch(1);
        host.backend = new BoundedStore.Backend() {
            public Map<String, String> read() { entered.countDown(); awaitUninterruptibly(release); return java.util.Collections.emptyMap(); }
            public boolean replace(Map<String, String> values) { return true; }
        };
        create(host); request(); assertTrue(entered.await(5, TimeUnit.SECONDS));
        runtime.close(); // Must return while release is still closed; no main-thread I/O monitor.
        assertEquals(1L, release.getCount());
        release.countDown(); assertTrue(executor().awaitTermination(5, TimeUnit.SECONDS));
    }
    @Test public void fullAuthorityValidationStaysOutsidePreviewMutationMonitor() throws Exception {
        Host host = new Host() {
            @Override public boolean isActive() {
                assertFalse("Full authority validation must not hold session lock", Thread.holdsLock(lock));
                return active;
            }
        };
        create(host); request(); executor().shutdown();
        assertTrue(executor().awaitTermination(5, TimeUnit.SECONDS));
        assertEquals("fixture", host.memory.read().get("key"));
    }
    @Test(timeout = 10000) public void closeDoesNotWaitForSlowPreviewAuthorityValidation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        Thread caller = Thread.currentThread();
        Host host = new Host() {
            @Override public boolean isActive() {
                if (Thread.currentThread() != caller) { entered.countDown(); awaitUninterruptibly(release); }
                return active;
            }
        };
        create(host); request(); assertTrue(entered.await(5, TimeUnit.SECONDS));
        runtime.close(); assertEquals(1L, release.getCount());
        release.countDown(); assertTrue(executor().awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(host.memory.read().isEmpty());
    }
    private Object createReply(String id) throws Exception {
        Class<?> replyType = Class.forName(FactoryRuntime.class.getName() + "$Reply");
        java.lang.reflect.Constructor<?> replyConstructor = replyType.getDeclaredConstructors()[0]; replyConstructor.setAccessible(true);
        Object reply = replyConstructor.newInstance(runtime, id, "export.text", null, field("generation").getInt(runtime));
        @SuppressWarnings("unchecked") Map<String, Object> pending = (Map<String, Object>) field("pending").get(runtime);
        pending.put(id, reply); return reply;
    }
    private Object createExport(String id) throws Exception {
        Object reply = createReply(id); field("uiOwner").set(runtime, reply);
        Class<?> exportType = Class.forName(FactoryRuntime.class.getName() + "$Export");
        java.lang.reflect.Constructor<?> exportConstructor = exportType.getDeclaredConstructors()[0]; exportConstructor.setAccessible(true);
        Object export = exportConstructor.newInstance(runtime, reply, "private-fixture"); field("export").set(runtime, export);
        return export;
    }
    private Object exportField(Object export, String name) throws Exception {
        Field value = export.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(export);
    }
    private boolean claimUi(Object reply) throws Exception {
        Method claim = FactoryRuntime.class.getDeclaredMethod("claimUi", reply.getClass()); claim.setAccessible(true);
        return (Boolean) claim.invoke(runtime, reply);
    }
    @Test public void outstandingExportTombstoneSurvivesPageInvalidationUntilItsResult() throws Exception {
        Host host = new Host(); create(host); runtime.onPause();
        Object export = createExport("old");
        Method invalidate = FactoryRuntime.class.getDeclaredMethod("invalidate"); invalidate.setAccessible(true); invalidate.invoke(runtime);
        assertSame(export, field("export").get(runtime));
        assertNull(exportField(export, "text"));
        runtime.onActivityResult(42, Activity.RESULT_CANCELED, null);
        assertSame(export, field("export").get(runtime));
        runtime.onActivityResult(41, Activity.RESULT_CANCELED, null);
        assertNull(field("export").get(runtime)); assertNull(field("uiOwner").get(runtime));
        runtime.onActivityResult(41, Activity.RESULT_CANCELED, null);
        assertNull(field("export").get(runtime));
    }
    @Test public void staleSuccessfulExportResultDrainsWithoutRetainingPrivateResult() throws Exception {
        Host host = new Host(); ExportActivity activity = Robolectric.buildActivity(ExportActivity.class).setup().get();
        create(host, activity); runtime.onPause();
        Uri uri = Uri.parse("content://synthetic.provider/old-export");
        java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        org.robolectric.Shadows.shadowOf(activity.getContentResolver()).registerOutputStreamSupplier(uri, () -> {
            opens.incrementAndGet(); return output;
        });
        Object export = createExport("old");
        Method invalidate = FactoryRuntime.class.getDeclaredMethod("invalidate"); invalidate.setAccessible(true); invalidate.invoke(runtime);
        Intent result = new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        ThreadPoolExecutor exports = (ThreadPoolExecutor) field("EXPORT_IO").get(null);
        long exportTasks = exports.getTaskCount();
        try {
            runtime.onActivityResult(41, Activity.RESULT_OK, result);
            assertNull(field("export").get(runtime)); assertNull(field("uiOwner").get(runtime));
            assertNull(exportField(export, "text")); assertNull(exportField(export, "result"));
            assertTrue(((Map<?, ?>) field("pending").get(runtime)).isEmpty());
            assertTrue(activity.revokeEntered.await(5, TimeUnit.SECONDS));
            assertEquals(uri, activity.revokedUri); assertEquals(Intent.FLAG_GRANT_WRITE_URI_PERMISSION, activity.revokedModes);
            assertEquals(1, ((java.util.concurrent.atomic.AtomicInteger) field("documentResources").get(runtime)).get());
            runtime.onResume();
            assertFalse(claimUi(createReply("blocked-until-cleanup")));
            assertNull(field("uiOwner").get(runtime)); assertNull(field("export").get(runtime));
            assertTrue(((Map<?, ?>) field("pending").get(runtime)).isEmpty());
            assertNull(org.robolectric.Shadows.shadowOf(activity).getNextStartedActivityForResult());
            assertEquals(exportTasks, exports.getTaskCount()); assertEquals(0, opens.get()); assertEquals(0, output.size());
        } finally { activity.releaseRevoke.countDown(); }
        // Hold both cleanup workers; a single barrier could overtake cleanup on the other worker.
        CountDownLatch cleanupIdle = new CountDownLatch(2), releaseBarriers = new CountDownLatch(1);
        java.util.concurrent.Executor cleanup = (java.util.concurrent.Executor) field("DOCUMENT_CLEANUP").get(null);
        Runnable barrier = () -> { cleanupIdle.countDown(); awaitUninterruptibly(releaseBarriers); };
        try {
            cleanup.execute(barrier); cleanup.execute(barrier);
            assertTrue(cleanupIdle.await(5, TimeUnit.SECONDS));
            assertEquals(0, ((java.util.concurrent.atomic.AtomicInteger) field("documentResources").get(runtime)).get());
        } finally { releaseBarriers.countDown(); }
        Object fresh = createReply("fresh-after-cleanup");
        assertTrue(claimUi(fresh)); assertSame(fresh, field("uiOwner").get(runtime));
        assertNull(field("export").get(runtime));
    }
    @Test public void currentExportResultStillWaitsForResume() throws Exception {
        Host host = new Host(); create(host); runtime.onPause();
        Object export = createExport("current");
        assertTrue(host.active); assertFalse(field("documentForeground").getBoolean(runtime));
        runtime.onActivityResult(41, Activity.RESULT_CANCELED, null);
        assertSame(export, field("export").get(runtime)); assertNotNull(field("uiOwner").get(runtime));
        assertEquals("private-fixture", exportField(export, "text"));
        assertTrue((Boolean) exportField(export, "returned"));
        runtime.onResume();
        assertNull(field("export").get(runtime)); assertNull(field("uiOwner").get(runtime));
        assertTrue(((Map<?, ?>) field("pending").get(runtime)).isEmpty());
    }
}
