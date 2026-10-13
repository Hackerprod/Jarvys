package guard.okhttp;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;

/** Models Gradle's later dependency loader without exposing OkHttp to the premain system loader. */
public final class ChildLoaderSmoke {
    public static void main(String[] paths) throws Throwable {
        URL[] urls = new URL[paths.length];
        for (int i = 0; i < paths.length; i++) urls[i] = Path.of(paths[i]).toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            try { loader.loadClass(System.getProperty("jarvys.offlineGuard.smokeMain", "guard.okhttp.OkHttpSmoke")).getMethod("main", String[].class).invoke(null, (Object)new String[0]); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        }
    }
}
