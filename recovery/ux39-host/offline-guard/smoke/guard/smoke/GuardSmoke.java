package guard.smoke;

import com.sun.net.httpserver.HttpServer;
import guard.bootstrap.OfflineGuard;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;

public final class GuardSmoke {
    interface Work { void run() throws Exception; }
    static int checks;
    static void pass(String name) { checks++; System.out.println("PASS " + name); }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void blocked(String name, Work work) throws Exception {
        long before = OfflineGuard.blockedAttempts();
        try { work.run(); throw new AssertionError(name + ": did not block"); }
        catch (IOException exception) {
            Throwable cursor = exception;
            boolean found = false;
            while (cursor != null) {
                if (cursor instanceof OfflineGuard.Blocked) found = true;
                cursor = cursor.getCause();
            }
            require(found, name + ": failed somewhere other than guard");
            require(OfflineGuard.blockedAttempts() > before, name + ": did not count");
            require(!exception.toString().contains(".invalid"), name + ": exception leaked URL");
        }
        pass(name);
    }
    static URL url(String value) throws MalformedURLException { return new URL(value); }
    static URLConnection connection(URL value) throws IOException {
        URLConnection result = value.openConnection(Proxy.NO_PROXY);
        result.setConnectTimeout(1500); result.setReadTimeout(1500);
        return result;
    }
    static String read(URL value) throws IOException {
        try (InputStream in = connection(value).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    static Proxy externalProxy(Proxy.Type type) {
        return new Proxy(type, InetSocketAddress.createUnresolved("proxy.offline-guard.invalid", 9));
    }
    static void customHandlers() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        URLStreamHandler handler = new URLStreamHandler() {
            protected URLConnection openConnection(URL value) {
                calls.incrementAndGet();
                return new URLConnection(value) {
                    public void connect() {}
                    public InputStream getInputStream() { return new ByteArrayInputStream(new byte[] {42}); }
                };
            }
            protected URLConnection openConnection(URL value, Proxy proxy) { return openConnection(value); }
        };
        for (String scheme : List.of("http", "https")) {
            URL external = new URL(null, scheme + "://custom.offline-guard.invalid/x", handler);
            blocked(scheme + " custom handler URL", external::openConnection);
            blocked(scheme + " custom handler Proxy overload", () -> external.openConnection(Proxy.NO_PROXY));
            require(calls.get() == 0, "custom handler invoked for external URL");
        }
        for (String host : List.of("127.0.0.1", "127.9.8.7", "[::1]", "[0:0:0:0:0:0:0:1]", "[::0001]", "localhost")) {
            URL local = new URL(null, "https://" + host + "/", handler);
            require(local.openStream().read() == 42, "loopback custom handler changed");
            pass("custom handler loopback allowed " + host);
        }
        URL nonHttp = new URL(null, "fixture://nonloopback.invalid/", handler);
        require(nonHttp.openStream().read() == 42, "non-HTTP behavior changed");
        pass("non-HTTP custom protocol untouched");
    }
    static HttpServer server(InetAddress address, AtomicInteger handled) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(address, 0), 0);
        server.createContext("/ok", exchange -> {
            handled.incrementAndGet();
            byte[] data = "loopback-ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(data); }
        });
        server.createContext("/echo", exchange -> {
            handled.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/local-redirect", exchange -> {
            handled.incrementAndGet(); exchange.getResponseHeaders().add("Location", "/ok");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        for (int code : new int[] {301, 302, 303, 305, 307}) {
            server.createContext("/external-" + code, exchange -> {
                handled.incrementAndGet();
                exchange.getResponseHeaders().add("Location", "http://redirect.offline-guard.invalid/secret-token-not-for-logs");
                exchange.sendResponseHeaders(code, -1); exchange.close();
            });
        }
        server.start(); return server;
    }
    static void httpFixtures() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        HttpServer server = server(InetAddress.getByAddress(new byte[] {127,0,0,1}), handled);
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            long before = OfflineGuard.blockedAttempts();
            require(read(url(base + "/ok")).equals("loopback-ok"), "HTTP fixture response");
            pass("real IPv4 loopback HTTP GET");
            require(read(url("http://localhost:" + server.getAddress().getPort() + "/ok")).equals("loopback-ok"), "localhost fixture response");
            pass("verified localhost HTTP GET");
            require(read(url(base + "/local-redirect")).equals("loopback-ok"), "loopback redirect response");
            pass("real HTTP relative loopback redirect");
            HttpURLConnection echo = (HttpURLConnection) connection(url(base + "/echo"));
            echo.setRequestMethod("POST"); echo.setDoOutput(true);
            byte[] data = "synthetic-fixture-payload".getBytes(StandardCharsets.UTF_8);
            echo.setFixedLengthStreamingMode(data.length);
            try (OutputStream out = echo.getOutputStream()) { out.write(data); }
            require(Arrays.equals(echo.getInputStream().readAllBytes(), data), "HTTP fixture POST body");
            echo.disconnect(); pass("real loopback HTTP POST body preserved");
            require(OfflineGuard.blockedAttempts() == before, "loopback fixtures counted as blocked");
            for (int code : new int[] {301, 302, 303, 305, 307}) {
                blocked("real HTTP redirect " + code + " denied before DNS", () -> read(url(base + "/external-" + code)));
            }
            ProxySelector previous = ProxySelector.getDefault();
            try {
                ProxySelector.setDefault(new ProxySelector() {
                    public List<Proxy> select(URI uri) { return List.of(externalProxy(Proxy.Type.HTTP)); }
                    public void connectFailed(URI uri, SocketAddress address, IOException error) {}
                });
                blocked("implicit HTTP proxy endpoint", () -> {
                    URLConnection c = url(base + "/ok").openConnection();
                    c.setConnectTimeout(1500); c.getInputStream();
                });
                ProxySelector.setDefault(new ProxySelector() {
                    public List<Proxy> select(URI uri) { return List.of(externalProxy(Proxy.Type.SOCKS)); }
                    public void connectFailed(URI uri, SocketAddress address, IOException error) {}
                });
                blocked("implicit SOCKS proxy endpoint", () -> {
                    URLConnection c = url(base + "/ok").openConnection();
                    c.setConnectTimeout(1500); c.getInputStream();
                });
            } finally { ProxySelector.setDefault(previous); }
            Proxy localProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}), server.getAddress().getPort()));
            URLConnection proxied = url(base + "/ok").openConnection(localProxy);
            proxied.setConnectTimeout(1500); proxied.setReadTimeout(1500);
            require(new String(proxied.getInputStream().readAllBytes(), StandardCharsets.UTF_8).equals("loopback-ok"), "local HTTP proxy fixture");
            pass("real loopback HTTP explicit local proxy");
            require(handled.get() == 11, "unexpected fixture request count: " + handled.get());
            pass("fixture request count exact (no proxy bypass requests)");
        } finally { server.stop(0); }
        AtomicInteger v6Handled = new AtomicInteger();
        byte[] v6 = new byte[16]; v6[15] = 1;
        HttpServer v6Server = server(InetAddress.getByAddress(v6), v6Handled);
        try {
            require(read(url("http://[::1]:" + v6Server.getAddress().getPort() + "/ok")).equals("loopback-ok"), "IPv6 fixture response");
            require(v6Handled.get() == 1, "IPv6 request count");
            pass("real IPv6 loopback HTTP GET");
        } finally { v6Server.stop(0); }
    }
    static void httpsLoopbackHandshake() throws Exception {
        long before = OfflineGuard.blockedAttempts();
        try (ServerSocket local = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127,0,0,1}))) {
            local.setSoTimeout(2000);
            CompletableFuture<Integer> firstByte = CompletableFuture.supplyAsync(() -> {
                try (Socket accepted = local.accept()) {
                    accepted.setSoTimeout(2000);
                    return accepted.getInputStream().read();
                } catch (IOException e) { throw new CompletionException(e); }
            });
            try {
                read(url("https://127.0.0.1:" + local.getLocalPort() + "/"));
                throw new AssertionError("Expected TLS peer closure without certificate");
            } catch (SSLException expected) {
                require(firstByte.get(3, TimeUnit.SECONDS) == 22, "Did not reach loopback TLS ClientHello");
            }
            require(OfflineGuard.blockedAttempts() == before, "HTTPS loopback denied");
            pass("real HTTPS loopback TCP and TLS ClientHello (no credentials created)");
        }
    }
    static void concurrency() throws Exception {
        long before = OfflineGuard.blockedAttempts();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 64; i++) futures.add(executor.submit(() -> {
                try { url("http://concurrent.offline-guard.invalid/").openConnection(); throw new AssertionError(); }
                catch (OfflineGuard.Blocked expected) {}
                catch (Exception error) { throw new RuntimeException(error); }
            }));
            for (Future<?> future : futures) future.get();
        }
        require(OfflineGuard.blockedAttempts() == before + 64, "counter lost concurrent attempts");
        pass("64 concurrent blocked attempts counted exactly");
    }
    public static void main(String[] args) throws Exception {
        require(OfflineGuard.active(), "Agent missing");
        require(OfflineGuard.blockedAttempts() == 0, "startup probe contaminated counter");
        pass("premain installed and per-JVM count initially zero");
        for (String scheme : List.of("http", "https")) {
            URL external = url(scheme + "://user:secret@target.offline-guard.invalid/private?credential=do-not-log");
            blocked(scheme + " default URL openConnection", external::openConnection);
            blocked(scheme + " explicit direct Proxy overload", () -> external.openConnection(Proxy.NO_PROXY));
            blocked(scheme + " openStream shortcut", external::openStream);
            blocked(scheme + " external HTTP proxy with loopback target", () -> url(scheme + "://127.0.0.1/").openConnection(externalProxy(Proxy.Type.HTTP)));
            blocked(scheme + " external SOCKS proxy with loopback target", () -> url(scheme + "://127.0.0.1/").openConnection(externalProxy(Proxy.Type.SOCKS)));
        }
        for (String host : List.of("192.0.2.1", "198.51.100.7", "203.0.113.2", "0.0.0.0", "[::]", "[2001:db8::1]", "service.localhost", "localhost.evil.invalid", "127.1", "2130706433", "127.000.0.1", "[::ffff:127.0.0.1]")) {
            // openConnection alone never connects if the guard unexpectedly fails.
            blocked("strict non-loopback or ambiguous-host rejection", () -> url("http://" + host + "/").openConnection());
        }
        blocked("direct JDK HttpURLConnection constructor path", () -> {
            var direct = new sun.net.www.protocol.http.HttpURLConnection(url("http://direct.offline-guard.invalid/"), Proxy.NO_PROXY);
            direct.setConnectTimeout(1000); direct.getInputStream();
        });
        customHandlers();
        httpFixtures();
        httpsLoopbackHandshake();
        concurrency();
        require(NoExternalDns.DENIED_LOOKUPS.get() == 0, "A blocked target reached DNS tripwire");
        pass("zero non-localhost DNS resolver calls");
        System.out.println("SMOKE_OK checks=" + checks + " blockedAttempts=" + OfflineGuard.blockedAttempts() + " nonLocalDnsCalls=" + NoExternalDns.DENIED_LOOKUPS.get());
    }
}
