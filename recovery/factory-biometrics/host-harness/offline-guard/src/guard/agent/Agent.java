package guard.agent;

import guard.bootstrap.OfflineGuard;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Map;
import java.util.Set;

/** Premain only: an installation failure escapes premain and prevents application startup. */
public final class Agent {
    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        if (arguments != null && !arguments.isEmpty()) throw new IllegalArgumentException("Offline guard does not accept arguments");
        if (Runtime.version().feature() != 21) throw new IllegalStateException("Offline guard requires verified JDK 21");
        if (!instrumentation.isRetransformClassesSupported()) throw new IllegalStateException("Offline guard requires retransformation");
        if (OfflineGuard.class.getClassLoader() != null) throw new IllegalStateException("Offline guard helper is not bootstrap-loaded");
        Module base = Object.class.getModule();
        instrumentation.redefineModule(base, Set.of(OfflineGuard.class.getModule()),
            Map.of("jdk.internal.org.objectweb.asm", Set.of(Agent.class.getModule())), Map.of(), Set.of(), Map.of());
        Transformer transformer = new Transformer();
        Class<?>[] targets = {
            Class.forName("java.net.URL", false, null),
            Class.forName("sun.net.www.protocol.http.HttpURLConnection", false, null),
            Class.forName("sun.net.www.http.HttpClient", false, null)
        };
        for (Class<?> target : targets) if (!instrumentation.isModifiableClass(target))
            throw new IllegalStateException("Offline guard target is not modifiable");
        instrumentation.addTransformer(transformer, true);
        instrumentation.retransformClasses(targets);
        transformer.requireComplete();
        // A custom handler sentinel cannot connect or resolve anything, even if installation is broken.
        URL sentinel = new URL(null, "https://offline-guard-probe.invalid/", new URLStreamHandler() {
            protected URLConnection openConnection(URL url) {
                throw new AssertionError("Offline guard startup probe reached handler");
            }
        });
        try {
            sentinel.openConnection();
            throw new IllegalStateException("Offline guard startup probe did not block");
        } catch (OfflineGuard.Blocked expected) {
            OfflineGuard.markInstalled();
        }
        System.err.println("[jarvys-offline-guard] installed hooks=7 localhost=" +
            (OfflineGuard.localhostVerified() ? "verified-loopback" : "denied"));
    }
}
