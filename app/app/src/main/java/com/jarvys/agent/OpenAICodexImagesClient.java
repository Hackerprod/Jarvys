package com.jarvys.agent;

import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import org.json.JSONObject;

/** Image-edit transport: credentials stay on the fixed Codex origin, including on redirect responses. */
final class OpenAICodexImagesClient {
    static final String EDIT_ENDPOINT = "https://chatgpt.com/backend-api/codex/images/edits";
    interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
    private OpenAICodexImagesClient() { }

    static ProviderHttp.Response sendEditRequest(JSONObject request, SecretStore.CodexCredentials credentials,
                                                 String sessionId, CancellationToken token) {
        return sendEditRequest(request, credentials, sessionId, token,
                url -> (HttpURLConnection) url.openConnection());
    }

    static ProviderHttp.Response sendEditRequest(JSONObject request, SecretStore.CodexCredentials credentials,
                                                 String sessionId, CancellationToken token, ConnectionFactory connections) {
        token.throwIfCancelled();
        HttpURLConnection connection = null;
        Runnable unregister = () -> { };
        try {
            connection = connections.open(new URL(EDIT_ENDPOINT));
            HttpURLConnection active = connection;
            unregister = token.registerCancelAction(active::disconnect);
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(90_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + credentials.accessToken);
            connection.setRequestProperty("chatgpt-account-id", credentials.accountId);
            connection.setRequestProperty("originator", "jarvys");
            connection.setRequestProperty("User-Agent", "Jarvys/1.2.0 (Android " + Build.VERSION.RELEASE + "; "
                    + (Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0]) + ")");
            if (sessionId != null && !sessionId.isEmpty()) {
                connection.setRequestProperty("session-id", sessionId);
                connection.setRequestProperty("session_id", sessionId);
                connection.setRequestProperty("conversation_id", sessionId);
            }
            token.throwIfCancelled();
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            token.throwIfCancelled();
            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String body = input == null ? "" : readBody(input, token);
            token.throwIfCancelled();
            return new ProviderHttp.Response(status, body, body,
                    ProviderHttp.parseRetryAfterMillis(connection.getHeaderField("Retry-After")));
        } catch (InterruptedIOException timedOut) {
            if (token.isCancelled() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Image edit request cancelled");
            }
            // Do not retain transport exceptions which can embed authenticated request data.
            throw new ProviderTransportException("Image edit request timed out", null);
        } catch (IOException failure) {
            if (token.isCancelled()) throw new CancellationException("Image edit request cancelled");
            throw new ProviderTransportException("Image edit request failed", null);
        } catch (OutOfMemoryError lowMemory) {
            throw new ProviderTransportException("Image edit response could not fit in available memory", null);
        } finally {
            unregister.run();
            if (connection != null) connection.disconnect();
        }
    }

    private static String readBody(InputStream input, CancellationToken token) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = source.read(buffer)) != -1) {
                token.throwIfCancelled();
                if (count == 0) {
                    int one = source.read();
                    if (one == -1) break;
                    if (output.size() >= CodexImageGenerationClient.MAX_RESPONSE_BYTES) throw new IOException("Image response exceeds limit");
                    output.write(one);
                } else {
                    if (count > CodexImageGenerationClient.MAX_RESPONSE_BYTES - output.size()) throw new IOException("Image response exceeds limit");
                    output.write(buffer, 0, count);
                }
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
