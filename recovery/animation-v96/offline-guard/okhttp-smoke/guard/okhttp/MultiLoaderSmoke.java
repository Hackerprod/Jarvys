package guard.okhttp;

import guard.bootstrap.OfflineGuard;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Independent child definitions must each get hooks; a failed loader must poison final evidence. */
public final class MultiLoaderSmoke {
    public static void main(String[] paths) throws Throwable {
        boolean missing = Boolean.getBoolean("jarvys.offlineGuard.smokeMissingLoader");
        int goodOffset = missing ? 1 : 0;
        URL[] goodUrls = new URL[paths.length - goodOffset];
        for (int i = 0; i < goodUrls.length; i++) goodUrls[i] = Path.of(paths[i + goodOffset]).toUri().toURL();
        if (missing) {
            URL[] badUrls = goodUrls.clone();
            badUrls[0] = Path.of(paths[0]).toUri().toURL();
            try (URLClassLoader bad = new URLClassLoader(badUrls, ClassLoader.getPlatformClassLoader())) {
                try { OfflineGuard.verifyOkHttp(bad); throw new AssertionError("missing target accepted"); }
                catch (IllegalStateException expected) { /* Required targets unavailable. */ }
            }
        }
        try (URLClassLoader first = new URLClassLoader(goodUrls, ClassLoader.getPlatformClassLoader());
             URLClassLoader second = new URLClassLoader(goodUrls, ClassLoader.getPlatformClassLoader())) {
            CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread a = worker(first, ready, go, error), b = worker(second, ready, go, error);
            a.start(); b.start(); ready.await(); go.countDown(); a.join(10000); b.join(10000);
            if (a.isAlive() || b.isAlive()) throw new AssertionError("verification deadlock");
            if (error.get() != null) throw error.get();
            if (first.loadClass("okhttp3.internal.connection.RealCall") == second.loadClass("okhttp3.internal.connection.RealCall"))
                throw new AssertionError("target definitions unexpectedly shared");
            System.out.println("OKHTTP_MULTILOADER_PASS independentLoaders=2 missingLoader=" + missing);
        }
    }
    private static Thread worker(ClassLoader loader, CountDownLatch ready, CountDownLatch go, AtomicReference<Throwable> error) {
        return new Thread(() -> {
            ready.countDown();
            try { go.await(); OfflineGuard.verifyOkHttp(loader); }
            catch (Throwable failure) { error.compareAndSet(null, failure); }
        });
    }
}
