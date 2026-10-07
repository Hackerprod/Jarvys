package com.jarvys.agent;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/** Secrets at rest use Android Keystore-backed EncryptedSharedPreferences. */
public final class SecretStore {
    private static final String FILE = "jarvys_encrypted_secrets";
    private static volatile SecretStore singleton;
    private final SharedPreferences preferences;

    private SecretStore(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            preferences = EncryptedSharedPreferences.create(
                    context.getApplicationContext(), FILE, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            throw new IllegalStateException("Android encrypted secret storage is unavailable", e);
        }
    }

    /** Package-local in-memory storage seam for Robolectric activity/navigation tests. */
    SecretStore(SharedPreferences testPreferences) { preferences = testPreferences; }

    public static SecretStore get(Context context) {
        SecretStore current = singleton;
        if (current == null) {
            synchronized (SecretStore.class) {
                current = singleton;
                if (current == null) singleton = current = new SecretStore(context);
            }
        }
        return current;
    }

    public synchronized void saveOpenRouterKey(String key) {
        if (key == null || key.trim().isEmpty()) throw new IllegalArgumentException("OpenRouter API key is empty");
        if (!preferences.edit().putString("openrouter_api_key", key.trim()).commit()) {
            throw new IllegalStateException("Could not persist the encrypted OpenRouter API key");
        }
    }

    public synchronized String getOpenRouterKey() {
        return preferences.getString("openrouter_api_key", null);
    }

    public synchronized void clearOpenRouterKey() {
        if (!preferences.edit().remove("openrouter_api_key").commit()) {
            throw new IllegalStateException("Could not remove the encrypted OpenRouter API key");
        }
    }

    public synchronized void saveOpenAiApiKey(String key) {
        if (key == null || key.trim().isEmpty()) throw new IllegalArgumentException("OpenAI API key is empty");
        if (!preferences.edit().putString("openai_api_key", key.trim()).commit()) {
            throw new IllegalStateException("Could not persist the encrypted OpenAI API key");
        }
    }

    public synchronized String getOpenAiApiKey() {
        return preferences.getString("openai_api_key", null);
    }

    public synchronized void clearOpenAiApiKey() {
        if (!preferences.edit().remove("openai_api_key").commit()) {
            throw new IllegalStateException("Could not remove the encrypted OpenAI API key");
        }
    }

    public synchronized void saveCustomEndpointKey(String key) {
        if (key == null || key.trim().isEmpty()) throw new IllegalArgumentException("Custom endpoint API key is empty");
        if (!preferences.edit().putString("custom_endpoint_api_key", key.trim()).commit()) {
            throw new IllegalStateException("Could not persist the encrypted custom endpoint API key");
        }
    }

    public synchronized String getCustomEndpointKey() {
        return preferences.getString("custom_endpoint_api_key", null);
    }

    public synchronized void clearCustomEndpointKey() {
        if (!preferences.edit().remove("custom_endpoint_api_key").commit()) {
            throw new IllegalStateException("Could not remove the encrypted custom endpoint API key");
        }
    }

    public synchronized void saveCodexTokens(String accessToken, String refreshToken,
                                              long expiresAtMillis, String accountId) {
        if (empty(accessToken) || empty(refreshToken) || empty(accountId)) {
            throw new IllegalArgumentException("Codex OAuth response omitted required credential fields");
        }
        boolean saved = preferences.edit()
                .putString("codex_access_token", accessToken)
                .putString("codex_refresh_token", refreshToken)
                .putLong("codex_expires_at", expiresAtMillis)
                .putString("codex_account_id", accountId)
                .commit();
        if (!saved) throw new IllegalStateException("Could not persist encrypted ChatGPT OAuth credentials");
    }

    public synchronized CodexCredentials getCodexCredentials() {
        String access = preferences.getString("codex_access_token", null);
        String refresh = preferences.getString("codex_refresh_token", null);
        String account = preferences.getString("codex_account_id", null);
        long expires = preferences.getLong("codex_expires_at", 0L);
        if (empty(access) || empty(refresh) || empty(account) || expires <= 0) return null;
        return new CodexCredentials(access, refresh, expires, account);
    }

    public synchronized void clearCodexTokens() {
        if (!preferences.edit().remove("codex_access_token").remove("codex_refresh_token")
                .remove("codex_expires_at").remove("codex_account_id").commit()) {
            throw new IllegalStateException("Could not remove encrypted ChatGPT OAuth credentials");
        }
    }

    /** Stores MCP credentials under an encrypted, server-scoped preference key. */
    public synchronized void saveMcpSecret(String serverId, String secretName, String value) {
        String key = mcpSecretKey(serverId, secretName);
        if (value == null || value.isEmpty()) {
            if (!preferences.edit().remove(key).commit()) {
                throw new IllegalStateException("Could not remove encrypted MCP credential");
            }
        } else if (!preferences.edit().putString(key, value).commit()) {
            throw new IllegalStateException("Could not persist encrypted MCP credential");
        }
    }

    public synchronized String getMcpSecret(String serverId, String secretName) {
        return preferences.getString(mcpSecretKey(serverId, secretName), null);
    }

    public synchronized void clearMcpSecrets(String serverId) {
        String prefix = "mcp_" + safeSecretPart(serverId) + "_";
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(prefix)) editor.remove(key);
        }
        if (!editor.commit()) throw new IllegalStateException("Could not remove encrypted MCP credentials");
    }

    /** Generic encrypted connector secrets keyed by a stable account/configuration id. */
    public synchronized void saveConnectorSecret(String ownerId, String secretName, String value) {
        String key = connectorSecretKey(ownerId, secretName);
        SharedPreferences.Editor editor = preferences.edit();
        if (value == null || value.isEmpty()) editor.remove(key);
        else editor.putString(key, value);
        if (!editor.commit()) throw new IllegalStateException("Could not persist encrypted connector credential");
    }

    public synchronized String getConnectorSecret(String ownerId, String secretName) {
        return preferences.getString(connectorSecretKey(ownerId, secretName), null);
    }

    public synchronized void clearConnectorSecrets(String ownerId) {
        String prefix = "connector_" + safeConnectorPart(ownerId) + "_";
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) if (key.startsWith(prefix)) editor.remove(key);
        if (!editor.commit()) throw new IllegalStateException("Could not remove encrypted connector credentials");
    }

    private static String connectorSecretKey(String ownerId, String secretName) {
        if (secretName == null || !secretName.matches("[a-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Invalid connector credential name");
        }
        return "connector_" + safeConnectorPart(ownerId) + "_" + secretName;
    }

    private static String safeConnectorPart(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,96}")) {
            throw new IllegalArgumentException("Invalid connector account identifier");
        }
        return value;
    }

    private static String mcpSecretKey(String serverId, String secretName) {
        if (secretName == null || !secretName.matches("[a-z_]{1,40}")) {
            throw new IllegalArgumentException("Invalid MCP credential name");
        }
        return "mcp_" + safeSecretPart(serverId) + "_" + secretName;
    }

    private static String safeSecretPart(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,96}")) {
            throw new IllegalArgumentException("Invalid MCP server id");
        }
        return value;
    }

    private static boolean empty(String value) {
        return value == null || value.isEmpty();
    }

    public static final class CodexCredentials {
        public final String accessToken;
        public final String refreshToken;
        public final long expiresAtMillis;
        public final String accountId;

        private CodexCredentials(String accessToken, String refreshToken, long expiresAtMillis, String accountId) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAtMillis = expiresAtMillis;
            this.accountId = accountId;
        }
    }
}
