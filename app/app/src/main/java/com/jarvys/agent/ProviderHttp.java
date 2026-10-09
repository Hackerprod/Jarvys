package com.jarvys.agent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Small bounded HTTP transport. Never logs request bodies, auth headers or response bodies. */
final class ProviderHttp {
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    /** Instance-injected connection seam; production has no mutable transport override. */
    interface ConnectionFactory {
        HttpURLConnection open(String endpoint) throws java.io.IOException;
    }

    static final ConnectionFactory DEFAULT_CONNECTION_FACTORY =
            endpoint -> (HttpURLConnection) new URL(endpoint).openConnection();

    static final class Response {
        final int status;
        final String body;
        final String rawBody;
        final long retryAfterMillis;
        Response(int status, String body) { this(status, body, body, 0L); }
        Response(int status, String body, String rawBody) { this(status, body, rawBody, 0L); }
        Response(int status, String body, String rawBody, long retryAfterMillis) {
            this.status = status;
            this.body = body;
            this.rawBody = rawBody == null ? "" : rawBody;
            this.retryAfterMillis = Math.max(0L, retryAfterMillis);
        }
    }

    private ProviderHttp() { }

    static Response post(String endpoint, Map<String, String> headers, byte[] body, CancellationToken token) {
        return post(endpoint, headers, body, token, true);
    }

    static Response post(String endpoint, Map<String, String> headers, byte[] body, CancellationToken token,
                         boolean followRedirects) {
        return post(endpoint, headers, body, token, followRedirects, DEFAULT_CONNECTION_FACTORY);
    }

    static Response post(String endpoint, Map<String, String> headers, byte[] body, CancellationToken token,
                         boolean followRedirects, ConnectionFactory connectionFactory) {
        token.throwIfCancelled();
        HttpURLConnection connection = null;
        Runnable unregister = () -> { };
        try {
            connection = connectionFactory.open(endpoint);
            HttpURLConnection activeConnection = connection;
            unregister = token.registerCancelAction(activeConnection::disconnect);
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(followRedirects);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(90000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            token.throwIfCancelled();
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
                output.flush();
            }
            token.throwIfCancelled();
            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            String responseBody = input == null ? "" : readBounded(input);
            token.throwIfCancelled();
            return new Response(status, responseBody, responseBody, parseRetryAfterMillis(connection.getHeaderField("Retry-After")));
        } catch (java.io.InterruptedIOException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Provider request interrupted by STOP");
        } catch (java.io.IOException e) {
            if (token.isCancelled()) throw new java.util.concurrent.CancellationException("Provider request cancelled by STOP");
            throw new ProviderTransportException("Provider request failed: " + e.getClass().getSimpleName(), e);
        } finally {
            unregister.run();
            if (connection != null) connection.disconnect();
        }
    }

    static long parseRetryAfterMillis(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try { return Math.max(0L, Math.multiplyExact(Long.parseLong(value.trim()), 1000L)); }
        catch (RuntimeException ignored) { }
        try {
            java.time.ZonedDateTime date = java.time.ZonedDateTime.parse(value.trim(),
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
            return Math.max(0L, java.time.Duration.between(java.time.Instant.now(), date.toInstant()).toMillis());
        } catch (RuntimeException ignored) { return 0L; }
    }

    private static String readBounded(InputStream input) throws java.io.IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > MAX_RESPONSE_BYTES) {
                    throw new java.io.IOException("Provider response exceeded the size limit");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
