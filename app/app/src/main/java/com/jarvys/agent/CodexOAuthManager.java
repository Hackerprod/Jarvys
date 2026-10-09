package com.jarvys.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.browser.customtabs.CustomTabsIntent;
import com.jarvys.agent.ui.JarvysNativeTheme;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** PKCE + authorization-code/refresh flow ported from the two studied Codex auth plugins. */
public final class CodexOAuthManager {
    public interface Listener {
        void onSuccess(String accountId);
        void onFailure(String message);
        default void onExchanging() { }
        default void onDiagnostic(CodexAuthDiagnostic diagnostic) { }
    }

    public interface CredentialCommitGate { void commit(Runnable persist); }

    /** Injectable token transport, also used by deterministic sign-in tests. */
    public interface DeviceCodeTokenTransport {
        DeviceCodeTokenResponse request(String endpoint, Map<String, String> form, CancellationToken token) throws Exception;
    }

    public static final class DeviceCodeTokenResponse {
        public final int statusCode;
        public final JSONObject body;
        public final CodexAuthDiagnostic diagnostic;

        public DeviceCodeTokenResponse(int statusCode, JSONObject body) {
            this(statusCode, body, null);
        }

        public DeviceCodeTokenResponse(int statusCode, JSONObject body, CodexAuthDiagnostic diagnostic) {
            this.statusCode = statusCode;
            this.body = body == null ? new JSONObject() : body;
            this.diagnostic = diagnostic;
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
    private static final long UNKNOWN_TOKEN_REFRESH_INTERVAL_MS = TimeUnit.DAYS.toMillis(8);
    private static final Object REFRESH_LOCK = new Object();
    private static final ExecutorService AUTH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "JarvysCodexOAuth");
        thread.setDaemon(true);
        return thread;
    });

    private final SecretStore secrets;
    private final DeviceCodeTokenTransport browserTransport;
    private final Object authorizationLock = new Object();
    private volatile ServerSocket activeServer;
    private volatile CancellationToken activeAuthorization;
    private volatile CodexAuthDiagnostic lastDiagnostic;

    public CodexOAuthManager(SecretStore secrets) { this(secrets, null); }

    CodexOAuthManager(SecretStore secrets, DeviceCodeTokenTransport browserTransport) {
        this.secrets = secrets;
        this.browserTransport = browserTransport;
    }

    public void authorize(Activity activity, Listener listener) {
        cancelAuthorization();
        lastDiagnostic = null;
        OAuthFlow flow = createFlow();
        CancellationToken cancellation = CancellationToken.cancellable();
        synchronized (authorizationLock) { activeAuthorization = cancellation; }
        ServerSocket server = null;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), CALLBACK_PORT), 1);
            server.setSoTimeout((int) TimeUnit.MINUTES.toMillis(5));
            synchronized (authorizationLock) {
                cancellation.throwIfCancelled();
                activeServer = server;
            }
        } catch (CancellationException cancelled) {
            closeServer(server);
            return;
        } catch (Exception bindFailure) {
            closeServer(server);
            showManualCallbackFallback(activity, flow, listener, cancellation);
            return;
        }
        final ServerSocket callbackServer = server;
        AUTH_EXECUTOR.execute(() -> waitForCallbackAndExchange(activity, callbackServer, flow, listener, cancellation));
        try {
            launchCustomTab(activity, flow.authorizationUrl);
        } catch (RuntimeException browserFailure) {
            notifyCurrentFailure(activity, listener, cancellation, activity.getString(R.string.oauth_browser_missing),
                    CodexAuthDiagnostic.failure(CodexAuthDiagnostic.Stage.BROWSER_LAUNCH, browserFailure));
        }
    }

    public void cancelAuthorization() {
        synchronized (authorizationLock) {
            CancellationToken cancellation = activeAuthorization;
            activeAuthorization = null;
            if (cancellation != null) cancellation.cancel();
            ServerSocket server = activeServer;
            activeServer = null;
            closeServer(server);
        }
    }

    private static void closeServer(ServerSocket server) {
        if (server != null) try { server.close(); } catch (Exception ignored) { }
    }

    /** The gate serializes credential saving with cancellation/replacement of the device-code attempt. */
    public synchronized String exchangeDeviceAuthorizationCode(String authorizationCode, String codeVerifier,
            String redirectUri, CancellationToken token, DeviceCodeTokenTransport transport) throws Exception {
        return exchangeDeviceAuthorizationCode(authorizationCode, codeVerifier, redirectUri, token, transport, Runnable::run);
    }

    public synchronized String exchangeDeviceAuthorizationCode(String authorizationCode, String codeVerifier,
            String redirectUri, CancellationToken token, DeviceCodeTokenTransport transport,
            CredentialCommitGate commitGate) throws Exception {
        if (authorizationCode == null || authorizationCode.isEmpty() || codeVerifier == null || codeVerifier.isEmpty()
                || !DEVICE_REDIRECT_URI.equals(redirectUri) || transport == null || commitGate == null) {
            throw new CodexAuthDiagnostic.Failure(CodexAuthDiagnostic.validation(
                    CodexAuthDiagnostic.Stage.TOKEN_EXCHANGE, "invalid_device_response"));
        }
        CancellationToken cancellation = token == null ? CancellationToken.uncancellable() : token;
        TokenReply tokens = exchangeCode(authorizationCode, codeVerifier, redirectUri, cancellation, transport);
        cancellation.throwIfCancelled();
        commitGate.commit(() -> {
            cancellation.throwIfCancelled();
            persistTokens(tokens);
        });
        return tokens.accountId;
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
        synchronized (REFRESH_LOCK) {
            if (token != null) token.throwIfCancelled();
            SecretStore.CodexCredentials current = secrets.getCodexCredentials();
            if (current == null) throw new IllegalStateException("Sign in with ChatGPT before selecting OpenAI Codex");
            if (!forceRefresh && current.expiresAtMillis - System.currentTimeMillis() > TOKEN_REFRESH_SKEW_MS) return current;
            TokenReply refreshed = refreshToken(current, token);
            if (token != null) token.throwIfCancelled();
            synchronized (secrets) {
                SecretStore.CodexCredentials latest = secrets.getCodexCredentials();
                if (latest == null) throw new IllegalStateException("ChatGPT session was disconnected");
                if (!current.accessToken.equals(latest.accessToken) || !current.refreshToken.equals(latest.refreshToken)
                        || !current.accountId.equals(latest.accountId) || !current.authorizationId.equals(latest.authorizationId)
                        || current.expiresAtMillis != latest.expiresAtMillis) return latest;
                persistTokens(refreshed, true);
                return secrets.getCodexCredentials();
            }
        }
    }

    private void waitForCallbackAndExchange(Activity activity, ServerSocket server, OAuthFlow flow,
                                            Listener listener, CancellationToken cancellation) {
        try (ServerSocket closeable = server) {
            long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
            while (System.currentTimeMillis() < deadline) {
                cancellation.throwIfCancelled();
                try (Socket client = closeable.accept()) {
                    if (!client.getInetAddress().isLoopbackAddress()) continue;
                    client.setSoTimeout(5000);
                    Callback callback = parseCallback(readRequestLine(client));
                    if (callback == null) {
                        writeHttpResponse(client, 404, "Not found");
                        continue;
                    }
                    if (!constantTimeEquals(flow.state, callback.state)) {
                        writeHttpResponse(client, 400, "Authorization state did not match. Return to Jarvys and retry.");
                        continue;
                    }
                    if (callback.error != null) {
                        notifyCurrentFailure(activity, listener, cancellation, activity.getString(R.string.oauth_callback_denied),
                                CodexAuthDiagnostic.validation(CodexAuthDiagnostic.Stage.CALLBACK, "denied"));
                        writeHttpResponse(client, 400, callbackPage(activity, false));
                        return;
                    }
                    if (callback.code == null || callback.code.isEmpty()) {
                        writeHttpResponse(client, 400, "The authorization response did not include a code.");
                        continue;
                    }
                    boolean connected = finishExchange(activity, listener, callback.code, flow.verifier, cancellation);
                    try { writeHttpResponse(client, connected ? 200 : 400, callbackPage(activity, connected)); }
                    catch (java.io.IOException ignored) { }
                    return;
                } catch (java.net.SocketTimeoutException ignored) {
                    // A malformed local request must not terminate the still-current authorization attempt.
                }
            }
            notifyCurrentFailure(activity, listener, cancellation, activity.getString(R.string.oauth_callback_timeout),
                    CodexAuthDiagnostic.validation(CodexAuthDiagnostic.Stage.CALLBACK, "expired"));
        } catch (CancellationException ignored) {
        } catch (Exception error) {
            notifyCurrentFailure(activity, listener, cancellation, activity.getString(R.string.oauth_exchange_failed),
                    CodexAuthDiagnostic.failure(CodexAuthDiagnostic.Stage.CALLBACK, error));
        } finally {
            synchronized (authorizationLock) { if (activeServer == server) activeServer = null; }
        }
    }

    private boolean finishExchange(Activity activity, Listener listener, String code, String verifier,
                                   CancellationToken cancellation) {
        try {
            cancellation.throwIfCancelled();
            activity.runOnUiThread(() -> { if (isCurrent(cancellation)) listener.onExchanging(); });
            TokenReply tokens = exchangeCode(code, verifier, REDIRECT_URI, cancellation, browserTransport);
            synchronized (authorizationLock) {
                cancellation.throwIfCancelled();
                if (activeAuthorization != cancellation) return false;
                persistTokens(tokens);
                activity.runOnUiThread(() -> {
                    synchronized (authorizationLock) {
                        if (isCurrent(cancellation)) {
                            activeAuthorization = null;
                            listener.onSuccess(tokens.accountId);
                        }
                    }
                });
            }
            return true;
        } catch (CancellationException ignored) {
            return false;
        } catch (Exception error) {
            notifyCurrentFailure(activity, listener, cancellation, activity.getString(signInErrorResource(error)),
                    CodexAuthDiagnostic.failure(CodexAuthDiagnostic.Stage.TOKEN_EXCHANGE, error));
            return false;
        }
    }

    public static int signInErrorResource(Exception error) {
        if (error instanceof TokenFormatException) return R.string.oauth_exchange_invalid_response;
        if (error instanceof CredentialStorageException) return R.string.oauth_storage_failed;
        return R.string.oauth_exchange_failed;
    }

    private boolean isCurrent(CancellationToken cancellation) {
        return activeAuthorization == cancellation && !cancellation.isCancellationRequested();
    }

    private void notifyCurrentFailure(Activity activity, Listener listener, CancellationToken cancellation,
                                      String message, CodexAuthDiagnostic diagnostic) {
        synchronized (authorizationLock) {
            if (!isCurrent(cancellation)) return;
            lastDiagnostic = diagnostic;
        }
        activity.runOnUiThread(() -> {
            synchronized (authorizationLock) {
                if (isCurrent(cancellation)) {
                    activeAuthorization = null;
                    cancellation.cancel();
                    closeServer(activeServer);
                    activeServer = null;
                    listener.onDiagnostic(diagnostic);
                    listener.onFailure(message);
                }
            }
        });
    }

    private String callbackPage(Activity activity, boolean connected) {
        String title = activity.getString(connected ? R.string.oauth_callback_success_title : R.string.oauth_callback_failed_title);
        String body = activity.getString(connected ? R.string.oauth_callback_success_body : R.string.oauth_callback_failed_body);
        String diagnostic = connected || lastDiagnostic == null ? "" : "<pre style='font-size:12px;white-space:pre-wrap;overflow-wrap:anywhere'>"
                + html(lastDiagnostic.toDisplayText(CodexAuthDiagnostic.METHOD_BROWSER)) + "</pre>";
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<meta name='color-scheme' content='light dark'><title>Jarvys</title><style>"
                + JarvysNativeTheme.callbackPageCss() + "</style></head><body><main><small>JARVYS</small><h1>"
                + html(title) + "</h1><p>" + html(body) + "</p>" + diagnostic + "</main></body></html>";
    }

    private static String html(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void persistTokens(TokenReply tokens) { persistTokens(tokens, false); }

    private void persistTokens(TokenReply tokens, boolean refresh) {
        try {
            synchronized (secrets) {
                if (refresh) secrets.refreshCodexTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAtMillis, tokens.accountId);
                else secrets.saveCodexTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAtMillis, tokens.accountId);
                SecretStore.CodexCredentials saved = secrets.getCodexCredentials();
                if (saved == null || !tokens.accessToken.equals(saved.accessToken) || !tokens.refreshToken.equals(saved.refreshToken)
                        || !tokens.accountId.equals(saved.accountId) || tokens.expiresAtMillis != saved.expiresAtMillis) {
                    throw new CredentialStorageException(CodexAuthDiagnostic.validation(CodexAuthDiagnostic.Stage.SAVE_SESSION, "readback_failed"));
                }
            }
        } catch (CredentialStorageException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new CredentialStorageException(CodexAuthDiagnostic.storage(error));
        }
    }

    private void showManualCallbackFallback(Activity activity, OAuthFlow flow, Listener listener,
                                             CancellationToken cancellation) {
        activity.runOnUiThread(() -> {
            if (!isCurrent(cancellation)) return;
            android.content.Context context = JarvysNativeTheme.dialogContext(activity);
            EditText callbackUrl = new EditText(context);
            callbackUrl.setHint(activity.getString(R.string.oauth_callback_input_hint));
            callbackUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            TextView explanation = new TextView(context);
            explanation.setText(activity.getString(R.string.oauth_callback_fallback_body, CALLBACK_PORT));
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(48, 24, 48, 8);
            content.addView(explanation, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(callbackUrl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            Runnable denied = () -> notifyCurrentFailure(activity, listener, cancellation, activity.getString(R.string.oauth_callback_denied),
                    CodexAuthDiagnostic.validation(CodexAuthDiagnostic.Stage.CALLBACK, "denied"));
            AlertDialog dialog = new AlertDialog.Builder(context).setTitle(R.string.oauth_chatgpt_signin_title)
                    .setView(content).setNeutralButton(R.string.oauth_open_signin, null)
                    .setOnCancelListener(ignored -> denied.run())
                    .setNegativeButton(R.string.oauth_cancel, (ignored, which) -> denied.run())
                    .setPositiveButton(R.string.oauth_validate_continue, null).create();
            dialog.setOnShowListener(ignored -> {
                JarvysNativeTheme.applyDialog(activity, dialog, explanation);
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                    if (!isCurrent(cancellation)) { dialog.dismiss(); return; }
                    try { launchCustomTab(activity, flow.authorizationUrl); }
                    catch (RuntimeException error) {
                        notifyCurrentFailure(activity, listener, cancellation, activity.getString(R.string.oauth_browser_missing),
                                CodexAuthDiagnostic.failure(CodexAuthDiagnostic.Stage.BROWSER_LAUNCH, error));
                        dialog.dismiss();
                    }
                });
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    Callback callback = parseCallbackInput(callbackUrl.getText().toString());
                    if (callback == null || callback.code == null || callback.code.isEmpty() || callback.error != null
                            || !constantTimeEquals(flow.state, callback.state)) {
                        callbackUrl.setError(activity.getString(R.string.oauth_callback_invalid));
                        JarvysNativeTheme.styleInputError(activity, callbackUrl);
                    } else if (isCurrent(cancellation)) {
                        callbackUrl.setText("");
                        dialog.dismiss();
                        AUTH_EXECUTOR.execute(() -> finishExchange(activity, listener, callback.code, flow.verifier, cancellation));
                    }
                });
            });
            dialog.show();
            Runnable unregister = cancellation.registerCancelAction(() -> activity.runOnUiThread(dialog::dismiss));
            dialog.setOnDismissListener(ignored -> unregister.run());
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (dialog.isShowing() && isCurrent(cancellation)) {
                    notifyCurrentFailure(activity, listener, cancellation, activity.getString(R.string.oauth_callback_timeout),
                            CodexAuthDiagnostic.validation(CodexAuthDiagnostic.Stage.CALLBACK, "expired"));
                    dialog.dismiss();
                }
            }, TimeUnit.MINUTES.toMillis(5));
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

    private TokenReply refreshToken(SecretStore.CodexCredentials current, CancellationToken token) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", current.refreshToken);
        body.put("client_id", CLIENT_ID);
        try {
            return browserTransport == null ? tokenRequest(body, "refresh", token, current)
                    : tokenRequest(body, "refresh", token, browserTransport, current);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception error) {
            throw new CodexAuthDiagnostic.Failure(CodexAuthDiagnostic.failure(CodexAuthDiagnostic.Stage.REFRESH, error));
        }
    }

    private static TokenReply tokenRequest(Map<String, String> form, String operation, CancellationToken token) throws Exception {
        return tokenRequest(form, operation, token, (SecretStore.CodexCredentials) null);
    }

    private static TokenReply tokenRequest(Map<String, String> form, String operation, CancellationToken token,
                                          SecretStore.CodexCredentials previous) throws Exception {
        return CodexAuthConnectionRetry.execute(token, () -> executeTokenRequest(
                (HttpURLConnection) new URL(TOKEN_URL).openConnection(), form, operation, token, previous));
    }

    private static TokenReply executeTokenRequest(HttpURLConnection connection, Map<String, String> form,
            String operation, CancellationToken token, SecretStore.CodexCredentials previous) throws Exception {
        CodexAuthDiagnostic.Stage stage = "refresh".equals(operation) ? CodexAuthDiagnostic.Stage.REFRESH : CodexAuthDiagnostic.Stage.TOKEN_EXCHANGE;
        Runnable unregister = () -> { };
        try {
            if (token != null) unregister = token.registerCancelAction(connection::disconnect);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            StringBuilder encoded = new StringBuilder();
            for (Map.Entry<String, String> entry : form.entrySet()) {
                if (encoded.length() > 0) encoded.append('&');
                encoded.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=')
                        .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
            // Never mark failures from outputStream, write, or response reads as safe to retry.
            CodexAuthConnectionRetry.connectBeforeBody(connection, stage, token);
            try (OutputStream output = connection.getOutputStream()) { output.write(encoded.toString().getBytes(StandardCharsets.UTF_8)); }
            if (token != null) token.throwIfCancelled();
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                JSONObject errorBody = null;
                try { InputStream error = connection.getErrorStream(); if (error != null) errorBody = new JSONObject(readText(error, 128 * 1024)); }
                catch (Exception ignored) { if (token != null) token.throwIfCancelled(); }
                throw new CodexAuthDiagnostic.Failure(CodexAuthDiagnostic.http(stage, status, errorBody));
            }
            String response = readText(connection.getInputStream(), 128 * 1024);
            if (token != null) token.throwIfCancelled();
            try { return parseTokenReply(new JSONObject(response), previous); }
            catch (JSONException invalid) { throw new CodexAuthDiagnostic.Failure(CodexAuthDiagnostic.invalidJson(CodexAuthDiagnostic.Stage.TOKEN_RESPONSE, status)); }
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (CodexAuthDiagnostic.Failure failure) {
            throw failure;
        } catch (Exception error) {
            if (token != null) token.throwIfCancelled();
            throw new CodexAuthDiagnostic.Failure(CodexAuthDiagnostic.failure(stage, error));
        } finally {
            unregister.run();
            connection.disconnect();
        }
    }

    private static TokenReply tokenRequest(Map<String, String> form, String operation, CancellationToken token,
                                           DeviceCodeTokenTransport transport) throws Exception {
        return tokenRequest(form, operation, token, transport, null);
    }

    private static TokenReply tokenRequest(Map<String, String> form, String operation, CancellationToken token,
            DeviceCodeTokenTransport transport, SecretStore.CodexCredentials previous) throws Exception {
        if (token != null) token.throwIfCancelled();
        DeviceCodeTokenResponse response = transport.request(TOKEN_URL, form, token);
        if (token != null) token.throwIfCancelled();
        if (response.statusCode < 200 || response.statusCode >= 300) {
            throw new CodexAuthDiagnostic.Failure(response.diagnostic != null ? response.diagnostic
                    : CodexAuthDiagnostic.http("refresh".equals(operation) ? CodexAuthDiagnostic.Stage.REFRESH
                    : CodexAuthDiagnostic.Stage.TOKEN_EXCHANGE, response.statusCode, response.body));
        }
        return parseTokenReply(response.body, previous);
    }

    private static TokenReply parseTokenReply(JSONObject json, SecretStore.CodexCredentials previous) {
        String access = json.optString("access_token", previous == null ? "" : previous.accessToken);
        String refresh = json.optString("refresh_token", previous == null ? "" : previous.refreshToken);
        if (access.isEmpty()) throw new TokenFormatException("missing_access_token");
        if (refresh.isEmpty()) throw new TokenFormatException("missing_refresh_token");
        JSONObject accessClaims = jwtClaims(access);
        String account = accountId(jwtClaims(json.optString("id_token", "")));
        if (account.isEmpty()) account = accountId(accessClaims);
        if (account.isEmpty() && previous != null) account = previous.accountId;
        if (account.isEmpty()) throw new TokenFormatException("missing_account");
        long now = System.currentTimeMillis();
        long jwtExpiry = accessClaims == null ? 0L : accessClaims.optLong("exp", 0L);
        long expiresIn = json.optLong("expires_in", 0L);
        if ((accessClaims != null && accessClaims.has("exp") && jwtExpiry <= 0)
                || (json.has("expires_in") && !json.isNull("expires_in") && expiresIn <= 0)) throw new TokenFormatException("invalid_expiry");
        try {
            long expiresAt = jwtExpiry > 0 ? Math.multiplyExact(jwtExpiry, 1000L)
                    : expiresIn > 0 ? Math.addExact(now, Math.multiplyExact(expiresIn, 1000L))
                    : Math.addExact(now, UNKNOWN_TOKEN_REFRESH_INTERVAL_MS);
            return new TokenReply(access, refresh, expiresAt, account);
        } catch (ArithmeticException error) { throw new TokenFormatException("invalid_expiry"); }
    }

    private static JSONObject jwtClaims(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;
            byte[] payload = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            return new JSONObject(new String(payload, StandardCharsets.UTF_8));
        } catch (Exception ignored) { return null; }
    }

    private static String accountId(JSONObject claims) {
        JSONObject auth = claims == null ? null : claims.optJSONObject("https://api.openai.com/auth");
        return auth == null ? "" : auth.optString("chatgpt_account_id", "");
    }

    private static final class TokenFormatException extends CodexAuthDiagnostic.Failure {
        TokenFormatException(String reason) { super(CodexAuthDiagnostic.validation(CodexAuthDiagnostic.Stage.TOKEN_RESPONSE, reason)); }
    }

    private static final class CredentialStorageException extends CodexAuthDiagnostic.Failure {
        CredentialStorageException(CodexAuthDiagnostic diagnostic) { super(diagnostic); }
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

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String readText(InputStream input, int limit) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new CodexAuthDiagnostic.Failure(
                        CodexAuthDiagnostic.validation(CodexAuthDiagnostic.Stage.TOKEN_RESPONSE, "oversized_response"));
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
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
        final String accountId;
        TokenReply(String accessToken, String refreshToken, long expiresAtMillis, String accountId) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAtMillis = expiresAtMillis;
            this.accountId = accountId;
        }
    }
}
