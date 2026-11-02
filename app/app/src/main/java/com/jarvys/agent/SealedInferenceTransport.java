package com.jarvys.agent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;

/** Principal inference only. The worker owns sealed network bytes, never runtime/source authority. */
final class SealedInferenceTransport {
    private static final ExecutorService NETWORK = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "JarvysInferenceTransport");
        thread.setDaemon(true);
        return thread;
    });
    enum Framing { JSON, CODEX_SSE }

    /** Immutable, transient request. Credentials are never exposed through toString or persisted. */
    static final class Request {
        final String endpoint;
        final Map<String, String> headers;
        private final byte[] body;
        final Boolean followRedirects;
        final Framing framing;
        final int responseLimit;
        final boolean captureRetryAfter;
        final ProviderHttp.ConnectionFactory connectionFactory;
        Request(String endpoint, Map<String, String> headers, byte[] body, Boolean followRedirects,
                Framing framing, int responseLimit, boolean captureRetryAfter,
                ProviderHttp.ConnectionFactory connectionFactory) {
            this.endpoint = Objects.requireNonNull(endpoint);
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            this.body = Objects.requireNonNull(body).clone();
            this.followRedirects = followRedirects;
            this.framing = Objects.requireNonNull(framing);
            if (responseLimit <= 0) throw new IllegalArgumentException("Existing response bound required");
            this.responseLimit = responseLimit;
            this.captureRetryAfter = captureRetryAfter;
            this.connectionFactory = Objects.requireNonNull(connectionFactory);
        }
    }

    private static final class Delivery {
        final ArrayDeque<String> deltas = new ArrayDeque<>();
        synchronized void delta(String value, CancellationToken transport, ProviderAttemptLease lease) {
            transport.throwIfCancelled();
            lease.requireCurrent();
            deltas.addLast(value);
            lease.signalTransportProgress();
        }
        synchronized String poll() { return deltas.pollFirst(); }
    }

    private SealedInferenceTransport() { }

    static ProviderHttp.Response exchange(ProviderAttemptLease lease, Request request,
            CancellationToken ownerToken, ModelProviderClient.TextDeltaListener listener) {
        lease.requireOwnerCurrent();
        ownerToken.throwIfCancelled();
        // This lexical hook cannot be silently lost by moving ProviderHttp.post to another thread.
        // Only the explicit principal adapter can reach this boundary; ordinary effect HTTP is unchanged.
        NativeEffectDispatchScope.beforeDispatch();
        lease.requireOwnerCurrent();
        CancellationToken transport = lease.transportToken();
        Delivery delivery = new Delivery();
        CompletableFuture<ProviderHttp.Response> completed = new CompletableFuture<>();
        // Register wake before socket disconnect: a pathological disconnect must not retain the owner lane.
        Runnable unregister = transport.registerCancelAction(() ->
                completed.completeExceptionally(new CancellationException("Inference transport cancelled")));
        boolean dispatched = false;
        try {
            lease.beginInference();
            dispatched = true;
            transport.throwIfCancelled();
            NETWORK.execute(() -> {
                try { completed.complete(network(request, transport, delivery, lease)); }
                catch (Throwable failure) { completed.completeExceptionally(failure); }
            });
            ProviderHttp.Response response = lease.awaitTransport(completed, () -> {
                String delta;
                while ((delta = delivery.poll()) != null) {
                    lease.requireOwnerCurrent();
                    ownerToken.throwIfCancelled();
                    if (listener != null) listener.onTextDelta(delta);
                }
            });
            lease.requireOwnerCurrent();
            ownerToken.throwIfCancelled();
            return response;
        } finally {
            unregister.run();
            if (!completed.isDone()) transport.cancelExactRunAsync();
            // Refresh and response interpretation are preparation on the native owner, not inference.
            if (dispatched) lease.transportReturned();
        }
    }

    /** No Context, credentials provider, source callback, diagnostics, reply parser or mutable settings. */
    private static ProviderHttp.Response network(Request request, CancellationToken transport, Delivery delivery, ProviderAttemptLease lease) {
        HttpURLConnection connection = null;
        InputStream input = null;
        Runnable unregister = () -> { };
        try {
            transport.throwIfCancelled();
            lease.requireCurrent();
            connection = request.connectionFactory.open(request.endpoint);
            HttpURLConnection active = connection;
            unregister = transport.registerCancelAction(active::disconnect);
            connection.setRequestMethod("POST");
            if (request.followRedirects != null) connection.setInstanceFollowRedirects(request.followRedirects);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(90000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            for (Map.Entry<String, String> header : request.headers.entrySet())
                connection.setRequestProperty(header.getKey(), header.getValue());
            transport.throwIfCancelled();
            lease.requireCurrent();
            try (OutputStream output = connection.getOutputStream()) {
                transport.throwIfCancelled();
                lease.requireCurrent();
                output.write(request.body); output.flush();
            }
            transport.throwIfCancelled();
            int status = connection.getResponseCode();
            lease.requireCurrent();
            boolean successful = status >= 200 && status < 300;
            input = request.framing == Framing.JSON
                    ? (status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream())
                    : (successful ? connection.getInputStream() : connection.getErrorStream());
            String raw = input == null ? "" : request.framing == Framing.CODEX_SSE
                    ? CodexResponseStream.read(input, transport, request.responseLimit, successful,
                        delta -> delivery.delta(delta, transport, lease))
                    : readBounded(input, request.responseLimit, transport);
            transport.throwIfCancelled();
            return new ProviderHttp.Response(status, raw, raw, request.captureRetryAfter
                    ? ProviderHttp.parseRetryAfterMillis(connection.getHeaderField("Retry-After")) : 0L);
        } catch (java.io.IOException failure) {
            transport.throwIfCancelled();
            throw new ProviderTransportException("Provider request failed: " + failure.getClass().getSimpleName(), failure);
        } finally {
            unregister.run();
            if (connection != null) connection.disconnect();
            if (input != null) try { input.close(); } catch (java.io.IOException ignored) { }
        }
    }

    private static String readBounded(InputStream input, int bound, CancellationToken token) throws java.io.IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                token.throwIfCancelled();
                if (output.size() + read > bound) throw new java.io.IOException("Provider response exceeded the size limit");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
