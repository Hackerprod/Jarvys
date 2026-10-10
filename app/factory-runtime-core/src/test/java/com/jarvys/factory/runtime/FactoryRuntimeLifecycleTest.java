package com.jarvys.factory.runtime;

import android.app.Activity;
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
    private void create(Host host) throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
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
    @Test public void outstandingExportTombstoneSurvivesPageInvalidationUntilItsResult() throws Exception {
        Host host = new Host(); create(host);
        Class<?> replyType = Class.forName(FactoryRuntime.class.getName() + "$Reply");
        java.lang.reflect.Constructor<?> replyConstructor = replyType.getDeclaredConstructors()[0]; replyConstructor.setAccessible(true);
        Object reply = replyConstructor.newInstance(runtime, "old", "export.text", null, 0);
        Class<?> exportType = Class.forName(FactoryRuntime.class.getName() + "$Export");
        java.lang.reflect.Constructor<?> exportConstructor = exportType.getDeclaredConstructors()[0]; exportConstructor.setAccessible(true);
        Object export = exportConstructor.newInstance(runtime, reply, "private-fixture"); field("export").set(runtime, export);
        Method invalidate = FactoryRuntime.class.getDeclaredMethod("invalidate"); invalidate.setAccessible(true); invalidate.invoke(runtime);
        assertSame(export, field("export").get(runtime));
        Field text = exportType.getDeclaredField("text"); text.setAccessible(true); assertNull(text.get(export));
        runtime.onActivityResult(41, Activity.RESULT_CANCELED, null);
        assertNull(field("export").get(runtime));
    }
}
