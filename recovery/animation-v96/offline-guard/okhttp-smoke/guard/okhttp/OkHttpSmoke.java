package guard.okhttp;

import com.sun.net.httpserver.HttpServer;
import guard.bootstrap.OfflineGuard;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** All negative targets are reserved .invalid or RFC5737; safety tripwires never resolve/connect them. */
public final class OkHttpSmoke {
    private static int checks;
    private static final AtomicInteger dns = new AtomicInteger();
    private static final AtomicInteger externalDns = new AtomicInteger();
    private static final AtomicInteger sockets = new AtomicInteger();
    private static final AtomicInteger externalSockets = new AtomicInteger();
    private static final AtomicInteger hits = new AtomicInteger();
    private static final Dns DNS = host -> {
        dns.incrementAndGet();
        if (!"localhost".equalsIgnoreCase(host)) {
            externalDns.incrementAndGet();
            throw new UnknownHostException("Smoke blocked external DNS");
        }
        return List.of(InetAddress.getByAddress("localhost", new byte[]{127,0,0,1}));
    };

    private static final class GuardedSockets extends SocketFactory {
        public Socket createSocket() {
            sockets.incrementAndGet();
            return new Socket() {
                @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
                    if (!(endpoint instanceof InetSocketAddress a) || a.getAddress() == null || !a.getAddress().isLoopbackAddress()) {
                        externalSockets.incrementAndGet();
                        throw new IOException("Smoke blocked external socket");
                    }
                    super.connect(endpoint, timeout);
                }
            };
        }
        public Socket createSocket(String h, int p) { throw new AssertionError("unexpected socket overload"); }
        public Socket createSocket(String h, int p, InetAddress l, int lp) { throw new AssertionError("unexpected socket overload"); }
        public Socket createSocket(InetAddress h, int p) { throw new AssertionError("unexpected socket overload"); }
        public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) { throw new AssertionError("unexpected socket overload"); }
    }
    private interface Action { void run() throws Throwable; }
    private static void check(boolean passed, String label) {
        if (!passed) throw new AssertionError(label);
        checks++;
    }
    private static void denied(Action action, int expectedSockets, int expectedDns, String label) throws Throwable {
        int s = sockets.get(), d = dns.get();
        long blocked = OfflineGuard.blockedAttempts();
        try { action.run(); throw new AssertionError(label + " was not blocked"); }
        catch (OfflineGuard.Blocked expected) { checks++; }
        check(sockets.get() - s == expectedSockets, label + " socket factory count");
        check(dns.get() - d == expectedDns, label + " DNS count");
        check(OfflineGuard.blockedAttempts() > blocked, label + " guard attribution");
        check(externalDns.get() == 0 && externalSockets.get() == 0, label + " external tripwire reached");
    }
    private static Request request(String url) { return new Request.Builder().url(url).build(); }
    private static void execute(OkHttpClient client, String url) throws IOException {
        try (Response response = client.newCall(request(url)).execute()) {
            if (response.code() != 200 || !"ok".equals(response.body().string())) throw new AssertionError("fixture response");
        }
    }
    private static void enqueue(OkHttpClient client, String url) throws Throwable {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        client.newCall(request(url)).enqueue(new Callback() {
            public void onFailure(Call call, IOException failure) { error.set(failure); finished.countDown(); }
            public void onResponse(Call call, Response response) {
                try (response) {
                    if (response.code() != 200 || !"ok".equals(response.body().string())) error.set(new AssertionError("fixture async response"));
                } catch (Throwable failure) { error.set(failure); }
                finally { finished.countDown(); }
            }
        });
        if (!finished.await(10, TimeUnit.SECONDS)) throw new AssertionError("async timeout");
        if (error.get() != null) throw error.get();
    }
    public static void main(String[] args) throws Throwable {
        check(OfflineGuard.active(), "guard active");
        check(OkHttpClient.class.getClassLoader() == OkHttpSmoke.class.getClassLoader(), "actual test dependency loader");
        // Exercise automatic verification on first RealCall entry, not a separately preloaded copy.
        OkHttpClient client = new OkHttpClient.Builder().dns(DNS).socketFactory(new GuardedSockets())
            .proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false).fastFallback(false)
            .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS)).build();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}), 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            exchange.getResponseHeaders().add("Connection", "close");
            if (path.startsWith("/external")) {
                exchange.getResponseHeaders().add("Location", "http://redirect.invalid/private?secret=not-logged");
                exchange.sendResponseHeaders(path.endsWith("307") ? 307 : 302, -1);
            } else if (path.equals("/local")) {
                exchange.getResponseHeaders().add("Location", "/ok");
                exchange.sendResponseHeaders(302, -1);
            } else {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write(new byte[]{'o','k'});
            }
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            for (String target : List.of("http://execute.invalid/private?secret=not-logged", "https://execute.invalid/", "http://203.0.113.7/")) {
                denied(() -> execute(client, target), 0, 0, "execute external");
                denied(() -> enqueue(client, target), 0, 0, "enqueue external");
            }
            OkHttpClient copied = client.newBuilder().build();
            denied(() -> execute(copied, "http://copy.invalid/"), 0, 0, "copied client newCall");
            Call cloned = copied.newCall(request("http://clone.invalid/")).clone();
            denied(() -> { try (Response ignored = cloned.execute()) {} }, 0, 0, "cloned call");
            execute(client, base + "/ok"); checks++;
            enqueue(client, base + "/ok"); checks++;
            execute(copied, base + "/local"); checks++;
            execute(client, "http://localhost:" + server.getAddress().getPort() + "/ok"); checks++;
            denied(() -> execute(copied, base + "/external302"), 1, 0, "internal redirect302");
            denied(() -> enqueue(copied, base + "/external307"), 1, 0, "async internal redirect307");
            OkHttpClient rewritten = client.newBuilder().addInterceptor(chain -> chain.proceed(
                chain.request().newBuilder().url("http://interceptor.invalid/").build())).build();
            denied(() -> execute(rewritten, base + "/ok"), 0, 0, "application interceptor rewrite");
            OkHttpClient networkRewritten = client.newBuilder().addNetworkInterceptor(chain -> chain.proceed(
                chain.request().newBuilder().url("http://network-interceptor.invalid/").build())).build();
            denied(() -> execute(networkRewritten, base + "/ok"), 1, 0, "network interceptor rewrite");
            OkHttpClient localProxy = client.newBuilder().proxy(new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}), server.getAddress().getPort()))).build();
            execute(localProxy, "http://127.0.0.1:1/ok"); checks++;
            for (Proxy.Type type : List.of(Proxy.Type.HTTP, Proxy.Type.SOCKS)) {
                OkHttpClient proxied = client.newBuilder().proxy(new Proxy(type, InetSocketAddress.createUnresolved("proxy.invalid", 8080))).build();
                denied(() -> execute(proxied, base + "/ok"), 0, 0, "unresolved external proxy");
                OkHttpClient resolved = client.newBuilder().proxy(new Proxy(type, new InetSocketAddress(
                    InetAddress.getByAddress("localhost", new byte[]{(byte)203,0,113,8}), 8080))).build();
                denied(() -> execute(resolved, base + "/ok"), 0, 0, "resolved nonloopback proxy");
            }
            OkHttpClient selected = client.newBuilder().proxy(null).proxySelector(new ProxySelector() {
                public List<Proxy> select(URI uri) { return List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("selected.invalid", 8080))); }
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {}
            }).build();
            denied(() -> execute(selected, base + "/ok"), 0, 0, "proxy selector external");
            OkHttpClient misresolved = client.newBuilder().dns(host -> {
                dns.incrementAndGet();
                return List.of(InetAddress.getByAddress("localhost", new byte[]{(byte)203,0,113,9}));
            }).build();
            denied(() -> execute(misresolved, "http://localhost:" + server.getAddress().getPort() + "/ok"), 0, 1, "nonloopback DNS result");
            OfflineGuard.verifyOkHttp(OkHttpSmoke.class.getClassLoader()); checks++;
            check(externalDns.get() == 0 && externalSockets.get() == 0, "final external counters");
            System.out.println("OKHTTP_SMOKE_PASS checks=" + checks + " blocked=" + OfflineGuard.blockedAttempts() +
                " externalDns=" + externalDns.get() + " externalSockets=" + externalSockets.get() +
                " fixtureHits=" + hits.get() + " loader=" + OkHttpSmoke.class.getClassLoader().getClass().getSimpleName());
        } finally {
            server.stop(0);
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }
}
