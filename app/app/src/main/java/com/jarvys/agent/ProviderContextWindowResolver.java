package com.jarvys.agent;

import android.content.Context;
import android.content.SharedPreferences;
import com.jarvys.agent.providers.ProviderClientRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Resolves models.dev Codex limits and OpenRouter /models context_length; absent metadata uses one conservative fallback. */
public final class ProviderContextWindowResolver {
    public static final int FALLBACK_CONTEXT_WINDOW = ConversationCompactionPolicy.DEFAULT_CONTEXT_WINDOW;
    static final String OPENROUTER_MODELS_ENDPOINT = "https://openrouter.ai/api/v1/models";
    private static final String PREFS = "provider_context_metadata";
    private static final long CACHE_TTL_MILLIS = 24L * 60L * 60L * 1000L;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private ProviderContextWindowResolver() { }

    public static int resolve(Context context, ProviderSettings settings, CancellationToken token) {
        String model = settings.getModel();
        Integer metadata = ProviderClientRegistry.contextWindow(settings.getProvider(), context, model, token);
        return metadata == null ? FALLBACK_CONTEXT_WINDOW : metadata;
    }

    public static Integer openRouterContextWindow(Context context, String model, CancellationToken token) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String modelKey = "model_" + model;
        String fetchedKey = "fetched_" + model;
        int cached = prefs.getInt(modelKey, 0);
        long age = System.currentTimeMillis() - prefs.getLong(fetchedKey, 0L);
        if (age >= 0 && age < CACHE_TTL_MILLIS) {
            return cached > 0 ? cached : null;
        }
        try {
            String catalog = fetchOpenRouterCatalog(token);
            Integer value = parseOpenRouterContextWindow(catalog, model);
            prefs.edit().putInt(modelKey, value == null ? -1 : value)
                    .putLong(fetchedKey, System.currentTimeMillis()).apply();
            return value;
        } catch (RuntimeException failure) {
            if (token.isCancelled()) throw failure;
            return cached > 0 ? cached : null;
        }
    }

    static Integer parseOpenRouterContextWindow(String catalog, String model) {
        try {
            JSONArray rows = new JSONObject(catalog).optJSONArray("data");
            if (rows == null) return null;
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.optJSONObject(index);
                if (row == null || !model.equals(row.optString("id"))) continue;
                int context = row.optInt("context_length", 0);
                return context > 0 ? context : null;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static String fetchOpenRouterCatalog(CancellationToken token) {
        HttpURLConnection connection = null;
        Runnable unregister = () -> { };
        try {
            connection = (HttpURLConnection) new URL(OPENROUTER_MODELS_ENDPOINT).openConnection();
            HttpURLConnection active = connection;
            unregister = token.registerCancelAction(active::disconnect);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(12_000);
            connection.setRequestProperty("Accept", "application/json");
            token.throwIfCancelled();
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("OpenRouter model catalog HTTP " + status);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    token.throwIfCancelled();
                    if (output.size() + read > MAX_RESPONSE_BYTES) throw new IllegalStateException("OpenRouter model catalog is too large");
                    output.write(buffer, 0, read);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } catch (java.io.IOException error) {
            if (token.isCancelled()) throw new java.util.concurrent.CancellationException("Model metadata lookup cancelled");
            throw new IllegalStateException("OpenRouter model metadata unavailable", error);
        } finally {
            unregister.run();
            if (connection != null) connection.disconnect();
        }
    }
}
