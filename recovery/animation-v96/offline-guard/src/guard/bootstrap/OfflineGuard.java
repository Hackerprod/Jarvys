package guard.bootstrap;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Bootstrap-only policy. No app dependencies, URI logging, or non-loopback lookup. */
public final class OfflineGuard {
    private static final AtomicLong BLOCKED = new AtomicLong();
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private static final boolean LOCALHOST_OK = verifyLocalhost();
    private static final String BLOCK_MESSAGE = "Offline guard blocked non-loopback HTTP(S)";

    private static volatile boolean OKHTTP_REQUIRED;
    private static final java.util.Map<String, Integer> OKHTTP_TARGETS = java.util.Map.of(
        "okhttp3.internal.connection.RealCall", 2,
        "okhttp3.internal.http.RealInterceptorChain", 1,
        "okhttp3.internal.connection.RouteSelector", 1,
        "okhttp3.internal.connection.ConnectPlan", 1);
    private static final java.util.Map<ClassLoader, java.util.Map<String, Integer>> OKHTTP_HOOKS = new java.util.IdentityHashMap<>();
    private static final java.util.Set<ClassLoader> OKHTTP_VERIFIED = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static final java.util.Set<ClassLoader> OKHTTP_FAILED = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static long okhttpFailures;

    private OfflineGuard() {}

    public static void configureOkHttp(boolean required) { OKHTTP_REQUIRED = required; }

    public static synchronized void recordOkHttpHooks(ClassLoader loader, String name, int count) {
        OKHTTP_HOOKS.computeIfAbsent(loader, ignored -> new java.util.HashMap<>()).put(name, count);
    }

    public static synchronized void recordOkHttpFailure() { okhttpFailures++; }

    /** Explicit preflight is useful for tests that never make a call. No class initialization or I/O. */
    public static void verifyOkHttp(ClassLoader loader) {
        if (!OKHTTP_REQUIRED || loader == null)
            throw new IllegalStateException("Offline guard OkHttp mode/loader unavailable");
        synchronized (OfflineGuard.class) {
            if (okhttpFailures != 0 || OKHTTP_FAILED.contains(loader))
                throw new IllegalStateException("Offline guard OkHttp installation previously failed");
            if (OKHTTP_VERIFIED.contains(loader)) return;
        }
        // Never hold the registry monitor during class loading (parallel-capable loaders can deadlock).
        try {
            for (String target : OKHTTP_TARGETS.keySet()) {
                Class<?> type = Class.forName(target, false, loader);
                if (type.getClassLoader() != loader)
                    throw new IllegalStateException("Offline guard mixed OkHttp defining loaders");
            }
        } catch (ClassNotFoundException | LinkageError | RuntimeException error) {
            synchronized (OfflineGuard.class) { OKHTTP_FAILED.add(loader); }
            throw new IllegalStateException("Offline guard required OkHttp target unavailable", error);
        }
        synchronized (OfflineGuard.class) {
            if (okhttpFailures != 0 || !OKHTTP_TARGETS.equals(OKHTTP_HOOKS.get(loader))) {
                OKHTTP_FAILED.add(loader);
                throw new IllegalStateException("Offline guard OkHttp installation incomplete");
            }
            if (OKHTTP_VERIFIED.add(loader))
                System.err.println("[jarvys-offline-guard] okhttp-verified hooks=5 loader=" + OKHTTP_VERIFIED.size());
        }
    }

    public static void verifyOkHttpClass(Class<?> anchor) {
        if (!OKHTTP_TARGETS.containsKey(anchor.getName()))
            throw new IllegalStateException("Offline guard invalid OkHttp verification anchor");
        verifyOkHttp(anchor.getClassLoader());
    }

    public static void checkOkHttpHost(String host) throws Blocked {
        if (!allowedHost(host)) deny();
    }

    public static void checkOkHttpProxy(String host, Proxy proxy) throws Blocked {
        checkOkHttpHost(host);
        if (proxy == null || proxy.type() == Proxy.Type.DIRECT) return;
        if (!(proxy.address() instanceof InetSocketAddress address)) { deny(); return; }
        if (!allowedHost(address.getHostString()) ||
                (address.getAddress() != null && !address.getAddress().isLoopbackAddress())) deny();
    }

    public static void checkOkHttpTransport(String host, Proxy proxy, InetSocketAddress endpoint) throws Blocked {
        checkOkHttpProxy(host, proxy);
        if (endpoint == null || !allowedHost(endpoint.getHostString()) ||
                (endpoint.getAddress() != null && !endpoint.getAddress().isLoopbackAddress())) deny();
        // Unresolved targets are only expected for a checked SOCKS loopback route.
        if (endpoint.isUnresolved() && (proxy == null || proxy.type() != Proxy.Type.SOCKS)) deny();
    }

    private static synchronized String okhttpReport(boolean shutdown) {
        if (!OKHTTP_REQUIRED) return "";
        if (!shutdown) return ",\"okhttpRequired\":true";
        return ",\"okhttpRequired\":true,\"okhttpVerifiedLoaders\":" + OKHTTP_VERIFIED.size() +
            ",\"okhttpVerifiedHooks\":" + (OKHTTP_VERIFIED.size() * 5) +
            ",\"okhttpTransformFailures\":" + okhttpFailures +
            ",\"okhttpVerificationFailures\":" + OKHTTP_FAILED.size() +
            ",\"okhttpComplete\":" + (!OKHTTP_VERIFIED.isEmpty() && okhttpFailures == 0 && OKHTTP_FAILED.isEmpty());
    }

    private static boolean verifyLocalhost() {
        try {
            InetAddress[] addresses = InetAddress.getAllByName("localhost");
            if (addresses.length == 0) return false;
            for (InetAddress address : addresses) if (!address.isLoopbackAddress()) return false;
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    public static final class Blocked extends SocketException {
        public Blocked() { super(BLOCK_MESSAGE); }
    }

    private static void deny() throws Blocked {
        BLOCKED.incrementAndGet();
        throw new Blocked();
    }

    public static boolean isHttp(URL url) {
        return url != null && ("http".equalsIgnoreCase(url.getProtocol()) ||
                "https".equalsIgnoreCase(url.getProtocol()));
    }

    /** Strict literals only, plus exact localhost after startup resolution validation. */
    public static boolean allowedHost(String input) {
        if (input == null || input.isEmpty()) return false;
        if ("localhost".equalsIgnoreCase(input)) return LOCALHOST_OK;
        String host = input;
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.indexOf(':') >= 0) return ipv6Loopback(host);
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4 || !octets[0].equals("127")) return false;
        for (String part : octets) {
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')) return false;
            int number = 0;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') return false;
                number = number * 10 + c - '0';
            }
            if (number > 255) return false;
        }
        return true;
    }

    private static boolean ipv6Loopback(String value) {
        // No zone IDs, embedded IPv4, percent escapes or ambiguous numeric formats.
        if (!value.matches("[0-9a-fA-F:]+")) return false;
        int compression = value.indexOf("::");
        if (compression != value.lastIndexOf("::")) return false;
        String[] pieces;
        if (compression < 0) {
            pieces = value.split(":", -1);
            if (pieces.length != 8) return false;
        } else {
            String left = value.substring(0, compression);
            String right = value.substring(compression + 2);
            String[] l = left.isEmpty() ? new String[0] : left.split(":", -1);
            String[] r = right.isEmpty() ? new String[0] : right.split(":", -1);
            if (l.length + r.length >= 8) return false;
            pieces = new String[8];
            java.util.Arrays.fill(pieces, "0");
            System.arraycopy(l, 0, pieces, 0, l.length);
            System.arraycopy(r, 0, pieces, 8 - r.length, r.length);
        }
        for (int i = 0; i < 8; i++) {
            if (pieces[i].isEmpty() || pieces[i].length() > 4) return false;
            int n = Integer.parseInt(pieces[i], 16);
            if (n != (i == 7 ? 1 : 0)) return false;
        }
        return true;
    }

    public static void checkUrl(URL url) throws Blocked {
        if (isHttp(url) && !allowedHost(url.getHost())) deny();
    }

    public static void checkUrlProxy(URL url, Proxy proxy) throws Blocked {
        checkUrl(url);
        if (!isHttp(url) || proxy == null || proxy.type() == Proxy.Type.DIRECT) return;
        if (!(proxy.address() instanceof InetSocketAddress address)) { deny(); return; }
        if (!allowedHost(address.getHostString()) ||
                (address.getAddress() != null && !address.getAddress().isLoopbackAddress())) deny();
    }

    public static void checkTransport(URL url, String endpoint, Proxy proxy) throws Blocked {
        if (!isHttp(url)) return;
        checkUrlProxy(url, proxy);
        if (!allowedHost(endpoint)) deny();
    }

    public static long blockedAttempts() { return BLOCKED.get(); }
    public static boolean localhostVerified() { return LOCALHOST_OK; }
    public static boolean active() { return ACTIVE.get(); }

    /** Called exactly once after the premain synthetic, network-free installation probe. */
    public static synchronized void markInstalled() throws IOException {
        if (ACTIVE.get()) throw new IllegalStateException("Offline guard duplicate installation");
        if (BLOCKED.get() != 1) throw new IllegalStateException("Offline guard startup probe count mismatch");
        Report report = Report.prepare(System.getProperty("jarvys.offlineGuard.reportDir"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!ACTIVE.get()) return;
            long count = BLOCKED.get();
            System.err.println("[jarvys-offline-guard] blockedAttempts=" + count);
            if (OKHTTP_REQUIRED) System.err.println("[jarvys-offline-guard] okhttp-shutdown " + okhttpReport(true).substring(1));
            if (report != null) {
                try { report.writeShutdown(count); }
                catch (IOException error) {
                    // Do not print path/exception details. Missing final evidence is a failed attribution.
                    System.err.println("[jarvys-offline-guard] shutdown-report-write-failed");
                }
            }
        }, "offline-guard-count"));
        if (report != null) report.writeInstall(); // Failure escapes premain, before active/main.
        BLOCKED.set(0);
        ACTIVE.set(true);
    }

    private static final class Report {
        final Path directory;
        final long pid;
        final long jvmStartedAt;
        final long installedAt;
        final String stem;

        private Report(Path directory) {
            this.directory = directory;
            ProcessHandle process = ProcessHandle.current();
            pid = process.pid();
            jvmStartedAt = process.info().startInstant().orElseThrow(() ->
                new IllegalStateException("Offline guard process start time unavailable")).toEpochMilli();
            installedAt = System.currentTimeMillis();
            stem = "guard-" + pid + "-" + jvmStartedAt;
        }

        static Report prepare(String configured) throws IOException {
            if (configured == null) return null;
            Path directory = Path.of(configured);
            if (!directory.isAbsolute() || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Offline guard reportDir must be an existing absolute directory");
            return new Report(directory.toRealPath());
        }

        String fields() {
            return "\"pid\":" + pid + ",\"jvmStartedAtEpochMillis\":" + jvmStartedAt +
                   ",\"installedAtEpochMillis\":" + installedAt;
        }

        void writeInstall() throws IOException {
            Files.writeString(directory.resolve(stem + ".installed.json"), "{" + fields() + okhttpReport(false) + "}\n",
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }

        void writeShutdown(long count) throws IOException {
            Files.writeString(directory.resolve(stem + ".shutdown.json"), "{" + fields() +
                ",\"shutdownAtEpochMillis\":" + System.currentTimeMillis() +
                ",\"blockedAttempts\":" + count + okhttpReport(true) + "}\n",
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }
}
