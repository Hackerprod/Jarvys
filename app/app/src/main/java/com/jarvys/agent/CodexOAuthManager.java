package com.jarvys.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.text.InputType;
import android.util.Base64;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.browser.customtabs.CustomTabsIntent;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** PKCE + authorization-code/refresh flow ported from the two studied Codex auth plugins. */
public final class CodexOAuthManager {
    public interface Listener {
        void onSuccess(String accountId);
        void onFailure(String message);
    }

    /** HTTP seam used only by the additive device-code token exchange. */
    public interface DeviceCodeTokenTransport {
        DeviceCodeTokenResponse request(String endpoint, Map<String, String> form, CancellationToken token) throws Exception;
    }

    public static final class DeviceCodeTokenResponse {
        public final int statusCode;
        public final JSONObject body;

        public DeviceCodeTokenResponse(int statusCode, JSONObject body) {
            this.statusCode = statusCode;
            this.body = body == null ? new JSONObject() : body;
        }
    }

    public static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    public static final String AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize";
    public static final String TOKEN_URL = "https://auth.openai.com/oauth/token";
    public static final String REDIRECT_URI = "http://localhost:1455/auth/callback";
    public static final String DEVICE_REDIRECT_URI = "https://auth.openai.com/deviceauth/callback";
    public static final String SCOPE = "openid profile email offline_access";
    private static final int CALLBACK_PORT = 1455;
    private static final long TOKEN_REFRESH_SKEW_MS = TimeUnit.MINUTES.toMillis(1);
    private static final ExecutorService AUTH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "JarvysCodexOAuth");
        thread.setDaemon(true);
        return thread;
    });

    private final SecretStore secrets;
    private volatile ServerSocket activeServer;

    public CodexOAuthManager(SecretStore secrets) {
        this.secrets = secrets;
    }

    public void authorize(Activity activity, Listener listener) {
        OAuthFlow flow = createFlow();
        ServerSocket server;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), CALLBACK_PORT), 1);
            server.setSoTimeout((int) TimeUnit.MINUTES.toMillis(5));
            activeServer = server;
            AUTH_EXECUTOR.execute(() -> waitForCallbackAndExchange(activity, server, flow, listener));
            try {
                launchCustomTab(activity, flow.authorizationUrl);
            } catch (RuntimeException browserFailure) {
                cancelAuthorization();
                activity.runOnUiThread(() -> listener.onFailure(activity.getString(R.string.oauth_browser_missing)));
            }
        } catch (Exception bindFailure) {
            showManualCallbackFallback(activity, flow, listener, bindFailure);
        }
    }

    public void cancelAuthorization() {
        ServerSocket server = activeServer;
        activeServer = null;
        if (server != null) {
            try { server.close(); } catch (Exception ignored) { }
        }
    }

    /** Exchanges OpenAI's device-code authorization response and stores credentials identically to browser PKCE. */
    public synchronized String exchangeDeviceAuthorizationCode(
            String authorizationCode,
            String codeVerifier,
            String redirectUri,
            CancellationToken token,
            DeviceCodeTokenTransport transport) throws Exception {
        if (authorizationCode == null || authorizationCode.isEmpty()
                || codeVerifier == null || codeVerifier.isEmpty()
                || !DEVICE_REDIRECT_URI.equals(redirectUri) || transport == null) {
            throw new IllegalArgumentException("Invalid OpenAI device authorization response");
        }
        TokenReply tokens = exchangeCode(authorizationCode, codeVerifier, redirectUri, token, transport);
        token.throwIfCancelled();
        String accountId = extractAccountId(tokens.accessToken);
        token.throwIfCancelled();
        secrets.saveCodexTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAtMillis, accountId);
        if (secrets.getCodexCredentials() == null) {
            throw new IllegalStateException("ChatGPT credentials could not be stored");
        }
        return accountId;
    }

    /** Returns a valid access token and rotates/persists the refresh token when expiring. */
    public synchronized SecretStore.CodexCredentials getValidCredentials() {
        return getValidCredentials(false, null);
    }

    public synchronized SecretStore.CodexCredentials getValidCredentials(boolean forceRefresh) {
        return getValidCredentials(forceRefresh, null);
    }

    public synchronized SecretStore.CodexCredentials getValidCredentials(CancellationToken token) {
        return getValidCredentials(false, token);
    }

    public synchronized SecretStore.CodexCredentials getValidCredentials(boolean forceRefresh,
                                                                          CancellationToken token) {
        if (token != null) token.throwIfCancelled();
        SecretStore.CodexCredentials current = secrets.getCodexCredentials();
        if (current == null) throw new IllegalStateException("Sign in with ChatGPT before selecting OpenAI Codex");
        if (!forceRefresh && current.expiresAtMillis - System.currentTimeMillis() > TOKEN_REFRESH_SKEW_MS) {
            return current;
        }
        TokenReply refreshed = refreshToken(current.refreshToken, token);
        String account = extractAccountId(refreshed.accessToken);
        secrets.saveCodexTokens(refreshed.accessToken, refreshed.refreshToken,
                refreshed.expiresAtMillis, account);
        return secrets.getCodexCredentials();
    }

    private void waitForCallbackAndExchange(Activity activity, ServerSocket server, OAuthFlow flow, Listener listener) {
        try (ServerSocket closeable = server) {
            long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
            while (System.currentTimeMillis() < deadline) {
                try (Socket client = closeable.accept()) {
                    if (!client.getInetAddress().isLoopbackAddress()) continue;
                    client.setSoTimeout(5000);
                    String request = readRequestLine(client);
                    Callback callback = parseCallback(request);
                    if (callback == null) {
                        writeHttpResponse(client, 404, "Not found");
                        continue;
                    }
                    if (callback.error != null) {
                        writeHttpResponse(client, 400, "Authorization was not completed. You may close this tab.");
                        throw new IllegalStateException("ChatGPT sign-in returned " + callback.error);
                    }
                    if (!constantTimeEquals(flow.state, callback.state)) {
                        writeHttpResponse(client, 400, "Authorization state did not match. Return to Jarvys and retry.");
                        continue;
                    }
                    if (callback.code == null || callback.code.isEmpty()) {
                        writeHttpResponse(client, 400, "The authorization response did not include a code.");
                        continue;
                    }
                    writeHttpResponse(client, 200,
                            "<html><meta name='viewport' content='width=device-width,initial-scale=1'>"
                                    + "<body style='font-family:sans-serif;padding:2rem'>"
                                    + "<h2>Jarvys sign-in received</h2><p>Return to Jarvys to continue.</p></body></html>");
                    finishExchange(activity, listener, callback.code, flow.verifier);
                    return;
                } catch (java.net.SocketTimeoutException ignored) {
                    break;
                }
            }
            notifyFailure(activity, listener, "Timed out waiting for the ChatGPT OAuth callback. Retry sign-in.");
        } catch (Exception error) {
            if (!server.isClosed()) {
                AgentErrorReporter.report(activity, "OPENAI_CODEX_OAUTH", "oauth-callback", null,
                        null, safeMessage(error), error.getClass().getSimpleName(),
                        android.util.Log.getStackTraceString(error));
                notifyFailure(activity, listener, safeMessage(error));
            }
        } finally {
            if (activeServer == server) activeServer = null;
        }
    }

    private void finishExchange(Activity activity, Listener listener, String code, String verifier) {
        try {
            TokenReply tokens = exchangeCode(code, verifier);
            String accountId = extractAccountId(tokens.accessToken);
            secrets.saveCodexTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAtMillis, accountId);
            boolean readBack = secrets.getCodexCredentials() != null;
            AgentErrorReporter.report(activity, "OPENAI_CODEX_OAUTH", "oauth-exchange", null, null,
                    "OAuth exchange succeeded, saved, readBack=" + readBack + ", accountId=" + accountId,
                    "OAuthExchangeSucceeded", "");
            activity.runOnUiThread(() -> listener.onSuccess(accountId));
        } catch (Exception error) {
            AgentErrorReporter.report(activity, "OPENAI_CODEX_OAUTH", "oauth-exchange", null,
                    null, safeMessage(error), error.getClass().getSimpleName(),
                    android.util.Log.getStackTraceString(error));
            notifyFailure(activity, listener, safeMessage(error));
        }
    }

    private void showManualCallbackFallback(Activity activity, OAuthFlow flow, Listener listener, Exception bindFailure) {
        activity.runOnUiThread(() -> {
            EditText callbackUrl = new EditText(activity);
            callbackUrl.setHint(activity.getString(R.string.oauth_callback_input_hint));
            callbackUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            TextView explanation = new TextView(activity);
            explanation.setText(activity.getString(R.string.oauth_callback_fallback_body, CALLBACK_PORT));
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(48, 24, 48, 8);
            content.addView(explanation, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(callbackUrl, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            new AlertDialog.Builder(activity).setTitle(R.string.oauth_chatgpt_signin_title)
                    .setView(content)
                    .setNeutralButton(R.string.oauth_open_signin, (dialog, which) -> launchCustomTab(activity, flow.authorizationUrl))
                    .setNegativeButton(R.string.oauth_cancel, (dialog, which) -> listener.onFailure(
                            activity.getString(R.string.oauth_callback_listener_unavailable, safeMessage(bindFailure))))
                    .setPositiveButton(R.string.oauth_validate_continue, (dialog, which) -> {
                        Callback callback = parseCallbackInput(callbackUrl.getText().toString());
                        if (callback == null || callback.code == null || !constantTimeEquals(flow.state, callback.state)) {
                            listener.onFailure(activity.getString(R.string.oauth_callback_invalid));
                        } else {
                            AUTH_EXECUTOR.execute(() -> finishExchange(activity, listener, callback.code, flow.verifier));
                        }
                    }).show();
        });
    }

    private static OAuthFlow createFlow() {
        try {
            SecureRandom random = new SecureRandom();
            byte[] verifierBytes = new byte[32];
            byte[] stateBytes = new byte[16];
            random.nextBytes(verifierBytes);
            random.nextBytes(stateBytes);
            String verifier = Base64.encodeToString(verifierBytes,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            String challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256")
                            .digest(verifier.getBytes(StandardCharsets.US_ASCII)),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            StringBuilder state = new StringBuilder(32);
            for (byte b : stateBytes) state.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            Uri uri = Uri.parse(AUTHORIZE_URL).buildUpon()
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("client_id", CLIENT_ID)
                    .appendQueryParameter("redirect_uri", REDIRECT_URI)
                    .appendQueryParameter("scope", SCOPE)
                    .appendQueryParameter("code_challenge", challenge)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("state", state.toString())
                    .appendQueryParameter("id_token_add_organizations", "true")
                    .appendQueryParameter("codex_cli_simplified_flow", "true")
                    .appendQueryParameter("originator", "codex_cli_rs")
                    .build();
            return new OAuthFlow(verifier, state.toString(), uri.toString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not create PKCE OAuth flow", e);
        }
    }

    private static void launchCustomTab(Activity activity, String url) {
        try {
            new CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, Uri.parse(url));
        } catch (Exception error) {
            activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)));
        }
    }

    private TokenReply exchangeCode(String code, String verifier) throws Exception {
        return exchangeCode(code, verifier, REDIRECT_URI, null, null);
    }

    private TokenReply exchangeCode(String code, String verifier, String redirectUri,
                                   CancellationToken token, DeviceCodeTokenTransport transport) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("client_id", CLIENT_ID);
        body.put("code", code);
        body.put("code_verifier", verifier);
        body.put("redirect_uri", redirectUri);
        return transport == null
                ? tokenRequest(body, "authorization-code exchange", token)
                : tokenRequest(body, "authorization-code exchange", token, transport);
    }

    private TokenReply refreshToken(String refresh, CancellationToken token) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refresh);
        body.put("client_id", CLIENT_ID);
        try {
            return tokenRequest(body, "refresh", token);
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ChatGPT token refresh failed; sign in again. " + safeMessage(e));
        }
    }

    private static TokenReply tokenRequest(Map<String, String> form, String operation,
                                           CancellationToken token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
        Runnable unregister = () -> { };
        try {
            if (token != null) unregister = token.registerCancelAction(connection::disconnect);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            StringBuilder encoded = new StringBuilder();
            for (Map.Entry<String, String> entry : form.entrySet()) {
                if (encoded.length() > 0) encoded.append('&');
                encoded.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
            if (token != null) token.throwIfCancelled();
            try (OutputStream output = connection.getOutputStream()) {
                output.write(encoded.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                closeQuietly(connection.getErrorStream());
                throw new IllegalStateException("ChatGPT OAuth " + operation + " returned HTTP " + status);
            }
            String response = readText(connection.getInputStream(), 128 * 1024);
            if (token != null) token.throwIfCancelled();
            return parseTokenReply(new JSONObject(response), operation);
        } finally {
            unregister.run();
            connection.disconnect();
        }
    }

    private static TokenReply tokenRequest(Map<String, String> form, String operation,
                                           CancellationToken token, DeviceCodeTokenTransport transport) throws Exception {
        if (token != null) token.throwIfCancelled();
        DeviceCodeTokenResponse response = transport.request(TOKEN_URL, form, token);
        if (token != null) token.throwIfCancelled();
        if (response.statusCode < 200 || response.statusCode >= 300) {
            throw new IllegalStateException("ChatGPT OAuth " + operation + " returned HTTP " + response.statusCode);
        }
        return parseTokenReply(response.body, operation);
    }

    private static TokenReply parseTokenReply(JSONObject json, String operation) {
        String access = json.optString("access_token", "");
        String refresh = json.optString("refresh_token", "");
        long expiresIn = json.optLong("expires_in", 0L);
        if (access.isEmpty() || refresh.isEmpty() || expiresIn <= 0) {
            throw new IllegalStateException("ChatGPT OAuth " + operation + " response omitted required token fields");
        }
        return new TokenReply(access, refresh, System.currentTimeMillis() + expiresIn * 1000L);
    }

    private static String extractAccountId(String accessToken) {
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length != 3) throw new IllegalStateException("OAuth access token is not a JWT");
            byte[] payload = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            JSONObject root = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            JSONObject auth = root.optJSONObject("https://api.openai.com/auth");
            String account = auth == null ? "" : auth.optString("chatgpt_account_id", "");
            if (account.isEmpty()) throw new IllegalStateException("JWT omitted chatgpt_account_id claim");
            return account;
        } catch (Exception e) {
            throw new IllegalStateException("Could not extract ChatGPT account id from OAuth access token", e);
        }
    }

    private static String readRequestLine(Socket socket) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String first = reader.readLine();
        if (first == null || first.length() > 8192) return "";
        int headers = 0;
        while (headers++ < 64) {
            String line = reader.readLine();
            if (line == null || line.isEmpty()) break;
        }
        return first;
    }

    private static Callback parseCallback(String requestLine) {
        if (requestLine == null || !requestLine.startsWith("GET ")) return null;
        int firstSpace = requestLine.indexOf(' ');
        int secondSpace = requestLine.indexOf(' ', firstSpace + 1);
        if (secondSpace <= firstSpace) return null;
        return parseCallbackUri(Uri.parse("http://localhost" + requestLine.substring(firstSpace + 1, secondSpace)));
    }

    private static Callback parseCallbackInput(String input) {
        if (input == null || input.trim().isEmpty()) return null;
        try {
            return parseCallbackUri(Uri.parse(input.trim()));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Callback parseCallbackUri(Uri uri) {
        if (!"localhost".equalsIgnoreCase(uri.getHost()) || !"/auth/callback".equals(uri.getPath())) return null;
        return new Callback(uri.getQueryParameter("code"), uri.getQueryParameter("state"),
                uri.getQueryParameter("error"));
    }

    private static void writeHttpResponse(Socket socket, int status, String body) throws Exception {
        String reason = status == 200 ? "OK" : status == 404 ? "Not Found" : "Bad Request";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
        writer.write("HTTP/1.1 " + status + " " + reason + "\r\n");
        writer.write("Content-Type: text/html; charset=utf-8\r\n");
        writer.write("Content-Length: " + bytes.length + "\r\n");
        writer.write("Connection: close\r\n\r\n");
        writer.flush();
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    private static void notifyFailure(Activity activity, Listener listener, String message) {
        activity.runOnUiThread(() -> listener.onFailure(message));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String readText(InputStream input, int limit) throws Exception {
        try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            StringBuilder result = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (result.length() + count > limit) throw new IllegalStateException("OAuth response exceeded limit");
                result.append(buffer, 0, count);
            }
            return result.toString();
        }
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) return;
        try { input.close(); } catch (Exception ignored) { }
    }

    private static final class OAuthFlow {
        final String verifier;
        final String state;
        final String authorizationUrl;
        OAuthFlow(String verifier, String state, String authorizationUrl) {
            this.verifier = verifier;
            this.state = state;
            this.authorizationUrl = authorizationUrl;
        }
    }

    private static final class Callback {
        final String code;
        final String state;
        final String error;
        Callback(String code, String state, String error) { this.code = code; this.state = state; this.error = error; }
    }

    private static final class TokenReply {
        final String accessToken;
        final String refreshToken;
        final long expiresAtMillis;
        TokenReply(String accessToken, String refreshToken, long expiresAtMillis) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
