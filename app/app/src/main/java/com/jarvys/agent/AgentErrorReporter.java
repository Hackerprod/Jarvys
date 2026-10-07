package com.jarvys.agent;

import android.content.Context;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** Best-effort, asynchronous reporting of provider failures and empty replies to the diagnostic endpoint. */
final class AgentErrorReporter {
    private static final String ENDPOINT = "http://217.216.83.217:8090/jarvys/errors";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "JarvysErrorReporter");
        thread.setDaemon(true);
        return thread;
    });
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern OPENAI_KEY = Pattern.compile("sk-(?:or-v1-)?[A-Za-z0-9_-]{12,}");

    private AgentErrorReporter() { }

    static void report(Context context, String provider, String model, Integer httpStatus,
                       String responseBody, String exceptionMessage, String exceptionType,
                       String stackTrace) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> postOnce(appContext, provider, model, httpStatus, responseBody,
                exceptionMessage, exceptionType, stackTrace));
    }

    private static void postOnce(Context context, String provider, String model, Integer httpStatus,
                                 String responseBody, String exceptionMessage, String exceptionType,
                                 String stackTrace) {
        HttpURLConnection connection = null;
        try {
            List<String> secrets = loadSecrets(context);
            JSONObject payload = new JSONObject();
            payload.put("timestamp", timestamp());
            payload.put("provider", safe(provider, secrets));
            payload.put("model", safe(model, secrets));
            payload.put("http_status", httpStatus == null ? JSONObject.NULL : httpStatus);
            payload.put("provider_response_body", responseBody == null ? JSONObject.NULL : safe(responseBody, secrets));
            payload.put("openai_error_body", httpStatus != null && httpStatus >= 400 && responseBody != null
                    ? safe(responseBody, secrets) : JSONObject.NULL);
            payload.put("exception_message", safe(exceptionMessage, secrets));
            payload.put("exception_type", safe(exceptionType, secrets));
            payload.put("stacktrace", safe(stackTrace, secrets));

            connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("User-Agent", "Jarvys/1.2.0 Android error reporter");
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
                output.flush();
            }
            connection.getResponseCode();
        } catch (Exception ignored) {
            // Diagnostics are best-effort and must never change the agent's result or retry.
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static List<String> loadSecrets(Context context) {
        List<String> values = new ArrayList<>();
        try {
            SecretStore store = SecretStore.get(context);
            SecretStore.CodexCredentials credentials = store.getCodexCredentials();
            if (credentials != null) {
                addSecret(values, credentials.accessToken);
                addSecret(values, credentials.refreshToken);
            }
            addSecret(values, store.getOpenRouterKey());
        } catch (RuntimeException ignored) { }
        values.sort((left, right) -> Integer.compare(right.length(), left.length()));
        return values;
    }

    private static void addSecret(List<String> values, String secret) {
        if (secret != null && secret.length() >= 8) values.add(secret);
    }

    private static String safe(String value, List<String> secrets) {
        if (value == null || value.isEmpty()) return "";
        String sanitized = value;
        for (String secret : secrets) sanitized = sanitized.replace(secret, "[REDACTED]");
        sanitized = BEARER.matcher(sanitized).replaceAll("Bearer [REDACTED]");
        return OPENAI_KEY.matcher(sanitized).replaceAll("[REDACTED_API_KEY]");
    }

    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
