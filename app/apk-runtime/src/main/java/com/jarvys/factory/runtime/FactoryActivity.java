package com.jarvys.factory.runtime;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.core.view.ViewCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Precompiled, resource-ID-free runtime. Generated applications supply assets, never native code. */
public final class FactoryActivity extends Activity {
    private static final String BRIDGE_NAME = "JarvysNative";
    private static final int EXPORT_REQUEST = 41;
    private static final int MAX_PENDING = 16;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING), new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, Reply> pending = new HashMap<>();
    private WebView webView;
    private FrameLayout root;
    private FactoryConfig config;
    private BoundedStore store;
    private int generation;
    private boolean destroyed;
    private Reply uiOwner;
    private long rateWindow;
    private int rateCount;
    private Export export;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = FactoryWindowPolicy.createRoot(this);
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
        try {
            config = FactoryConfig.parse(readConfig(), getPackageName());
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                showError("Please update Android System WebView to use this application safely.");
                return;
            }
            store = new BoundedStore(new PreferencesBackend(this));
            initializeWebView();
        } catch (IOException | FactoryException | RuntimeException e) {
            showError("This application's runtime or configuration is invalid. Rebuild it with Jarvys.");
        }
    }

    private String readConfig() throws IOException {
        try (InputStream input = getAssets().open("factory-app.json"); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 8192) throw new IOException("Configuration too large");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // JavaScript is required for reviewed local assets; network, file access and frames are blocked,
    // and the native message listener independently enforces exact origin and main-frame identity.
    @SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    private void initializeWebView() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            showError("Please update Android System WebView to use this application safely.");
            return;
        }
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBlockNetworkLoads(true);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        CookieManager.getInstance().setAcceptCookie(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        WebView.setWebContentsDebuggingEnabled(false);
        webView.setDownloadListener((url, userAgent, disposition, mime, length) -> { /* use export.text */ });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, false, false);
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                callback.onReceiveValue(null); return true;
            }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, android.os.Message result) { return false; }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !"GET".equals(request.getMethod()) || !AssetPolicy.navigationAllowed(request.getUrl().toString(), request.isForMainFrame());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !AssetPolicy.navigationAllowed(url, true);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return serve(request.getUrl().toString(), request.getMethod());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) { return serve(url, "GET"); }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) { handler.cancel(); }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                generation++; pending.clear();
                if (!AssetPolicy.navigationAllowed(url, true)) { view.stopLoading(); showError("Navigation outside this offline application was blocked."); }
            }
        });
        WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, Collections.singleton(BridgeProtocol.ORIGIN),
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    // Validate actual sender for every invocation, even though WebKit also checks origin rules.
                    if (!BridgeProtocol.trustedSender(sourceOrigin.toString(), isMainFrame) || view != webView || destroyed) return;
                    if (message.getType() != WebMessageCompat.TYPE_STRING) {
                        safePost(replyProxy, BridgeProtocol.failure(null, "INVALID_REQUEST", "Only JSON string messages are accepted.")); return;
                    }
                    receive(message.getData(), replyProxy);
                });
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        webView.loadUrl(BridgeProtocol.ORIGIN + "/" + config.entryPoint);
    }

    private WebResourceResponse serve(String url, String method) {
        String path = "GET".equals(method) ? AssetPolicy.assetPath(url) : null;
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Security-Policy", AssetPolicy.CSP);
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Cache-Control", "no-store");
        headers.put("Referrer-Policy", "no-referrer");
        headers.put("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
        try {
            if (path != null) return new WebResourceResponse(AssetPolicy.mimeType(path), "UTF-8", 200, "OK", headers, getAssets().open(path));
        } catch (IOException ignored) { }
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", headers, new ByteArrayInputStream(new byte[0]));
    }

    private void receive(String raw, JavaScriptReplyProxy proxy) {
        long now = SystemClock.elapsedRealtime();
        if (now - rateWindow >= 10000) { rateWindow = now; rateCount = 0; }
        if (++rateCount > 80) {
            safePost(proxy, BridgeProtocol.failure(null, "RATE_LIMITED", "Too many native requests; try again shortly.")); return;
        }
        final BridgeProtocol.Request request;
        try { request = BridgeProtocol.validate(BridgeProtocol.ORIGIN, true, raw, config); }
        catch (FactoryException e) { safePost(proxy, BridgeProtocol.failure(BridgeProtocol.requestId(raw), e.code, e.getMessage())); return; }
        if (pending.size() >= MAX_PENDING || pending.containsKey(request.id)) {
            safePost(proxy, BridgeProtocol.failure(request.id, "BUSY", "Too many pending requests or duplicate request ID.")); return;
        }
        Reply reply = new Reply(request.id, proxy, generation);
        pending.put(request.id, reply);
        if ("storage".equals(request.operation.capability)) {
            background(reply, () -> {
                switch (request.operation) {
                    case STORAGE_GET: return store.get(request.args.getString("key"));
                    case STORAGE_SET: store.set(request.args.getString("key"), request.args.getString("value")); return null;
                    case STORAGE_REMOVE: store.remove(request.args.getString("key")); return null;
                    case STORAGE_LIST: return new JSONArray(store.list());
                    default: throw new FactoryException("UNKNOWN_METHOD", "Unknown storage operation.");
                }
            });
        } else {
            try { dispatch(request, reply); }
            catch (JSONException | RuntimeException e) {
                if (uiOwner == reply) {
                    uiOwner = null;
                    if (export != null && export.reply == reply) export = null;
                }
                reply.fail("NATIVE_ERROR", "The native operation could not be completed.");
            }
        }
    }

    private void dispatch(BridgeProtocol.Request request, Reply reply) throws JSONException {
        switch (request.operation) {
            case RUNTIME_INFO:
                reply.ok(new JSONObject().put("sdkVersion", com.jarvys.factory.contract.CapabilityCatalog.SDK_VERSION).put("offline", true)
                        .put("implementedCapabilities", new JSONArray(FactoryConfig.SUPPORTED))
                        .put("declaredCapabilities", new JSONArray(config.capabilities))
                        .put("limits", new JSONObject().put("messageBytes", BridgeProtocol.MAX_MESSAGE_BYTES)
                                .put("textBytes", BridgeProtocol.MAX_TEXT_BYTES).put("storageValueBytes", BridgeProtocol.MAX_VALUE_BYTES)
                                .put("storageBytes", BoundedStore.MAX_TOTAL_BYTES).put("storageEntries", BoundedStore.MAX_ENTRIES)));
                break;
            case DEVICE_INFO:
                reply.ok(new JSONObject().put("platform", "android").put("apiLevel", Build.VERSION.SDK_INT)
                        .put("appId", getPackageName()).put("targetSdk", getApplicationInfo().targetSdkVersion));
                break;
            case HAPTICS_PERFORM:
                int kind = request.args.optString("kind", "tap").equals("longPress") ? HapticFeedbackConstants.LONG_PRESS : HapticFeedbackConstants.KEYBOARD_TAP;
                reply.ok(webView.performHapticFeedback(kind)); // Respects the user's system haptic setting.
                break;
            case SHARE_TEXT:
                if (!claimUi(reply)) break;
                try {
                    Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, request.args.getString("text"));
                    if (request.args.has("title")) share.putExtra(Intent.EXTRA_SUBJECT, request.args.getString("title"));
                    startActivity(Intent.createChooser(share, "Share text"));
                    reply.ok(new JSONObject().put("chooserOpened", true)); // Never claims delivery.
                } catch (ActivityNotFoundException e) { reply.fail("UNAVAILABLE", "No text sharing application is available."); }
                finally { if (uiOwner == reply) uiOwner = null; }
                break;
            case CLIPBOARD_WRITE:
                if (!claimUi(reply)) break;
                String text = request.args.getString("text");
                String preview = text.length() > 160 ? text.substring(0, 160) + "…" : text;
                new AlertDialog.Builder(this).setTitle("Copy text to clipboard?")
                        .setMessage("Other applications may be able to read copied text.\n\n" + preview)
                        .setPositiveButton("Copy", (dialog, which) -> {
                            if (uiOwner == reply) uiOwner = null;
                            if (!reply.current()) return;
                            try {
                                ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                if (manager == null) { reply.fail("UNAVAILABLE", "Clipboard is unavailable."); return; }
                                ClipData clip = ClipData.newPlainText(config.name, text);
                                if (Build.VERSION.SDK_INT >= 33) {
                                    android.os.PersistableBundle extras = new android.os.PersistableBundle();
                                    extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                                    clip.getDescription().setExtras(extras);
                                }
                                manager.setPrimaryClip(clip); reply.ok(null);
                            } catch (RuntimeException e) { reply.fail("NATIVE_ERROR", "Text could not be copied."); }
                        }).setNegativeButton("Cancel", (dialog, which) -> { if (uiOwner == reply) uiOwner = null; reply.fail("CANCELLED", "Copy cancelled."); })
                        .setOnCancelListener(dialog -> { if (uiOwner == reply) uiOwner = null; reply.fail("CANCELLED", "Copy cancelled."); }).show();
                break;
            case EXPORT_TEXT:
                if (!claimUi(reply)) break;
                export = new Export(reply, request.args.getString("text"));
                try {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(request.args.optString("mimeType", "text/plain"))
                            .putExtra(Intent.EXTRA_TITLE, request.args.getString("filename"))
                            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    startActivityForResult(intent, EXPORT_REQUEST);
                } catch (ActivityNotFoundException e) { export = null; if (uiOwner == reply) uiOwner = null; reply.fail("UNAVAILABLE", "No system document picker is available."); }
                break;
            default: reply.fail("UNKNOWN_METHOD", "Native method is not implemented.");
        }
    }

    private boolean claimUi(Reply reply) {
        if (uiOwner != null || !hasWindowFocus() || isFinishing()) {
            reply.fail("BUSY", "Another native prompt is open, or the application is not in the foreground."); return false;
        }
        uiOwner = reply; return true;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_REQUEST) return;
        Export work = export; export = null;
        if (work != null && uiOwner == work.reply) uiOwner = null;
        if (work == null || !work.reply.current()) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) { work.reply.fail("CANCELLED", "Export cancelled."); return; }
        Uri uri = data.getData();
        if (!"content".equals(uri.getScheme()) || checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            work.reply.fail("PERMISSION_DENIED", "The document picker did not grant write access."); return;
        }
        background(work.reply, () -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException("No output stream");
                output.write(work.text.getBytes(StandardCharsets.UTF_8)); output.flush();
            }
            return new JSONObject().put("saved", true);
        });
    }

    private interface Work { Object run() throws Exception; }
    private void background(Reply reply, Work work) {
        try {
            io.execute(() -> {
                try { Object result = work.run(); main.post(() -> reply.ok(result)); }
                catch (FactoryException e) { main.post(() -> reply.fail(e.code, e.getMessage())); }
                catch (SecurityException e) { main.post(() -> reply.fail("PERMISSION_DENIED", "Permission is no longer available.")); }
                catch (Exception e) { main.post(() -> reply.fail("IO_ERROR", "The operation could not be saved.")); }
            });
        } catch (RejectedExecutionException e) { reply.fail("BUSY", "Native operation queue is full."); }
    }
    private final class Reply {
        final String id; final JavaScriptReplyProxy proxy; final int page;
        Reply(String id, JavaScriptReplyProxy proxy, int page) { this.id = id; this.proxy = proxy; this.page = page; }
        boolean current() { return !destroyed && page == generation && pending.get(id) == this; }
        void ok(Object value) { complete(BridgeProtocol.success(id, value)); }
        void fail(String code, String message) { complete(BridgeProtocol.failure(id, code, message)); }
        void complete(String response) {
            if (!current()) return;
            pending.remove(id); safePost(proxy, response);
        }
    }
    private final class Export {
        final Reply reply; final String text;
        Export(Reply reply, String text) { this.reply = reply; this.text = text; }
    }
    private void safePost(JavaScriptReplyProxy proxy, String response) {
        if (destroyed || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        try { proxy.postMessage(response); } catch (RuntimeException ignored) { /* originating frame was destroyed */ }
    }
    private void showError(String message) {
        if (webView != null) { webView.stopLoading(); webView.setVisibility(android.view.View.GONE); }
        TextView text = new TextView(this); text.setText(message); text.setTextSize(18);
        text.setTextColor(FactoryWindowPolicy.isDark(this) ? FactoryWindowPolicy.DARK_TEXT : FactoryWindowPolicy.LIGHT_TEXT);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding * 2, padding, padding);
        root.removeAllViews();
        root.addView(text, new FrameLayout.LayoutParams(-1, -1));
    }
    @Override protected void onDestroy() {
        destroyed = true; generation++; pending.clear(); export = null; uiOwner = null; io.shutdownNow();
        if (webView != null) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                try { WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME); } catch (RuntimeException ignored) { }
            }
            webView.stopLoading(); webView.destroy(); webView = null;
        }
        super.onDestroy();
    }
}
