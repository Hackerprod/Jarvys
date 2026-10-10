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
public final class FactoryRuntime implements AutoCloseable {
    private static final String BRIDGE_NAME = "JarvysNative";
    private static final int EXPORT_REQUEST = 41;
    private static final int MAX_PENDING = 16;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING), new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, Reply> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile WebView webView;
    private FrameLayout root;
    private FactoryConfig config;
    private BoundedStore store;
    private volatile int generation;
    private volatile boolean destroyed;
    private Reply uiOwner;
    private long rateWindow;
    private int rateCount;
    private Export export;

    public interface AssetSource { InputStream open(String path) throws IOException; }
    public interface Host extends AssetSource {
        String configuration() throws IOException;
        boolean isPreview();
        BoundedStore.Backend storage();
        /** Preview sessions share this monitor with revoke; never hold it across external I/O. */
        default Object lifecycleLock() { return this; }
        default boolean isActive() { return true; }
        /** Nonblocking revocation flag only; preview adapters must not perform I/O here. */
        default boolean isSessionOpen() { return isActive(); }
        default void onTrace(String operation, String outcome) { }
        default void resetStorage() { }
    }
    private final Activity activity;
    private final Host host;
    private final Object lifecycle;
    public FactoryRuntime(Activity activity, FrameLayout root, Host host) {
        this.activity = activity; this.root = root; this.host = host;
        this.lifecycle = host.lifecycleLock();
    }
    public static Host installedHost(Activity activity) {
        return new Host() {
            public InputStream open(String path) throws IOException { return activity.getAssets().open(path); }
            public String configuration() throws IOException { return readConfiguration(open("factory-app.json")); }
            public boolean isPreview() { return false; }
            public BoundedStore.Backend storage() { return new PreferencesBackend(activity); }
        };
    }
    public static Host previewHost(String configuration, AssetSource assets) {
        return new Host() {
            private final MemoryBackend memory = new MemoryBackend();
            public InputStream open(String path) throws IOException { return assets.open(path); }
            public String configuration() { return configuration; }
            public boolean isPreview() { return true; }
            public BoundedStore.Backend storage() { return memory; }
            public void resetStorage() { memory.clear(); }
        };
    }
    private static String readConfiguration(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = source.read(buffer)) != -1) {
                if (output.size() + count > 8192) throw new IOException("Configuration too large");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    public boolean start() {
        if (destroyed || !host.isActive()) return false;
        if (webView != null) return true;
        try {
            config = host.isPreview() ? FactoryConfig.parsePreview(host.configuration())
                    : FactoryConfig.parse(host.configuration(), activity.getPackageName());
            store = new BoundedStore(host.storage());
            return initializeWebView();
        } catch (IOException | FactoryException | RuntimeException e) {
            showError("This application's runtime or configuration is invalid. Rebuild it with Jarvys.");
            return false;
        }
    }
    /** Invalidates callbacks before clearing isolated preview data and reloading the same snapshot. */
    public boolean reset() {
        if (destroyed || !host.isActive()) return false;
        synchronized (lifecycle) { invalidate(); host.resetStorage(); }
        if (host.isPreview()) {
            disposeWebView(); root.removeAllViews();
            if (!destroyed && host.isActive()) return start();
        } else if (!destroyed && host.isActive() && webView != null) { webView.reload(); return true; }
        return false;
    }
    private void invalidate() {
        generation++; pending.clear(); uiOwner = null; io.getQueue().clear();
        // Keep an outstanding picker tombstone until its callback: request code 41 must never
        // attach an old result to a new page's export request.
        if (export != null) export.text = null;
        if (prompt != null) { prompt.dismiss(); prompt = null; }
    }
    private AlertDialog prompt;
    private String previewProfile;
    private static final PreviewProfiles PROFILES = new PreviewProfiles();
    private static final PreviewProfiles.Provider PROFILE_PROVIDER = new PreviewProfiles.Provider() {
        public java.util.List<String> names() { return androidx.webkit.ProfileStore.getInstance().getAllProfileNames(); }
        public boolean delete(String name) { return androidx.webkit.ProfileStore.getInstance().deleteProfile(name); }
    };
    // JavaScript is required for reviewed local assets; network, file access and frames are blocked,
    // and the native message listener independently enforces exact origin and main-frame identity.
    @SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    private boolean initializeWebView() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            showError("Please update Android System WebView to use this application safely.");
            return false;
        }
        if (host.isPreview() && !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            showError("Factory preview requires an Android System WebView with isolated profile support. Update WebView or test the installed APK separately.");
            return false;
        }
        if (host.isPreview() && !PROFILES.cleanup(PROFILE_PROVIDER)) host.onTrace("runtime", "cleanup_pending");
        webView = new WebView(activity);
        if (host.isPreview()) {
            previewProfile = PROFILES.reserve();
            WebViewCompat.setProfile(webView, previewProfile);
            WebViewCompat.getProfile(webView).getCookieManager().setAcceptCookie(false);
        }
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
        if (!host.isPreview()) CookieManager.getInstance().setAcceptCookie(false);
        if (host.isPreview()) WebViewCompat.getProfile(webView).getCookieManager().setAcceptThirdPartyCookies(webView, false);
        else CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        if (!host.isPreview()) WebView.setWebContentsDebuggingEnabled(false);
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
                return view != webView || destroyed || !host.isActive() || !"GET".equals(request.getMethod()) || !AssetPolicy.navigationAllowed(request.getUrl().toString(), request.isForMainFrame());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return view != webView || destroyed || !host.isActive() || !AssetPolicy.navigationAllowed(url, true);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return serve(view, request.getUrl().toString(), request.getMethod());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) { return serve(view, url, "GET"); }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) { handler.cancel(); }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (view != webView || destroyed || !host.isActive()) return;
                host.onTrace("page", "started");
                synchronized (lifecycle) { invalidate(); }
                if (!AssetPolicy.navigationAllowed(url, true)) { view.stopLoading(); showError("Navigation outside this offline application was blocked."); }
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (view == webView && !destroyed && host.isActive() && AssetPolicy.navigationAllowed(url, true))
                    host.onTrace("page", "finished");
            }
        });
        WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, Collections.singleton(BridgeProtocol.ORIGIN),
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    // Validate actual sender for every invocation, even though WebKit also checks origin rules.
                    if (!BridgeProtocol.trustedSender(sourceOrigin.toString(), isMainFrame) || view != webView || destroyed || !host.isActive()) return;
                    if (message.getType() != WebMessageCompat.TYPE_STRING) {
                        safePost(replyProxy, BridgeProtocol.failure(null, "INVALID_REQUEST", "Only JSON string messages are accepted.")); return;
                    }
                    receive(message.getData(), replyProxy);
                });
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        webView.loadUrl(BridgeProtocol.ORIGIN + "/" + config.entryPoint);
        host.onTrace("runtime", "requested");
        return true;
    }

    private WebResourceResponse serve(WebView source, String url, String method) {
        String path = source == webView && !destroyed && host.isActive() && "GET".equals(method) ? AssetPolicy.assetPath(url) : null;
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Security-Policy", AssetPolicy.CSP);
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Cache-Control", "no-store");
        headers.put("Referrer-Policy", "no-referrer");
        headers.put("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
        try {
            if (path != null) return new WebResourceResponse(AssetPolicy.mimeType(path), "UTF-8", 200, "OK", headers, host.open(path));
        } catch (IOException ignored) { }
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", headers, new ByteArrayInputStream(new byte[0]));
    }

    private void receive(String raw, JavaScriptReplyProxy proxy) {
        if (destroyed || !host.isActive()) return;
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
        Reply reply = new Reply(request.id, request.method, proxy, generation);
        pending.put(request.id, reply);
        host.onTrace(request.method, "requested");
        if ("storage".equals(request.operation.capability)) {
            background(reply, () -> {
                return FactoryDispatcher.dispatch(request, config, store, metadata(), FactoryDispatcher.simulatedEffects());
            });
        } else {
            try { dispatch(request, reply); }
            catch (RuntimeException e) {
                if (uiOwner == reply) {
                    uiOwner = null;
                    if (export != null && export.reply == reply) export = null;
                }
                reply.fail("NATIVE_ERROR", "The native operation could not be completed.");
            }
        }
    }

    private void dispatch(BridgeProtocol.Request request, Reply reply) {
        if (!reply.current()) return;
        try {
            FactoryDispatcher.Effects effects = host.isPreview() ? FactoryDispatcher.simulatedEffects() : (method, args) -> {
                dispatchInstalledEffect(request, reply);
                return DEFERRED;
            };
            Object result = FactoryDispatcher.dispatch(request, config, store, metadata(), effects);
            if (result != DEFERRED) reply.ok(result);
        } catch (Exception e) {
            if (uiOwner == reply) uiOwner = null;
            if (export != null && export.reply == reply) export = null;
            reply.fail("NATIVE_ERROR", "The native operation could not be completed.");
        }
    }
    private static final Object DEFERRED = new Object();
    /** Installed side-effect adapter, never invoked by preview hosts. */
    private void dispatchInstalledEffect(BridgeProtocol.Request request, Reply reply) throws JSONException {
        if (!reply.current()) return;
        switch (request.operation) {
            case HAPTICS_PERFORM:
                int kind = request.args.optString("kind", "tap").equals("longPress") ? HapticFeedbackConstants.LONG_PRESS : HapticFeedbackConstants.KEYBOARD_TAP;
                reply.ok(webView.performHapticFeedback(kind)); // Respects the user's system haptic setting.
                break;
            case SHARE_TEXT:
                if (!claimUi(reply)) break;
                try {
                    Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, request.args.getString("text"));
                    if (request.args.has("title")) share.putExtra(Intent.EXTRA_SUBJECT, request.args.getString("title"));
                    activity.startActivity(Intent.createChooser(share, "Share text"));
                    reply.ok(new JSONObject().put("chooserOpened", true)); // Never claims delivery.
                } catch (ActivityNotFoundException e) { reply.fail("UNAVAILABLE", "No text sharing application is available."); }
                finally { if (uiOwner == reply) uiOwner = null; }
                break;
            case CLIPBOARD_WRITE:
                if (!claimUi(reply)) break;
                String text = request.args.getString("text");
                String preview = text.length() > 160 ? text.substring(0, 160) + "…" : text;
                prompt = new AlertDialog.Builder(activity).setTitle("Copy text to clipboard?")
                        .setMessage("Other applications may be able to read copied text.\n\n" + preview)
                        .setPositiveButton("Copy", (dialog, which) -> {
                            if (uiOwner == reply) uiOwner = null;
                            if (!reply.current()) return;
                            try {
                                ClipboardManager manager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
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
                    activity.startActivityForResult(intent, EXPORT_REQUEST);
                } catch (ActivityNotFoundException e) { export = null; if (uiOwner == reply) uiOwner = null; reply.fail("UNAVAILABLE", "No system document picker is available."); }
                break;
            default: reply.fail("UNKNOWN_METHOD", "Native method is not implemented.");
        }
    }

    private boolean claimUi(Reply reply) {
        if (uiOwner != null || export != null || !activity.hasWindowFocus() || activity.isFinishing()) {
            reply.fail("BUSY", "Another native prompt is open, or the application is not in the foreground."); return false;
        }
        uiOwner = reply; return true;
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != EXPORT_REQUEST) return;
        Export work = export; export = null;
        if (work != null && uiOwner == work.reply) uiOwner = null;
        if (work == null || !work.reply.current()) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) { work.reply.fail("CANCELLED", "Export cancelled."); return; }
        Uri uri = data.getData();
        if (!"content".equals(uri.getScheme()) || activity.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            work.reply.fail("PERMISSION_DENIED", "The document picker did not grant write access."); return;
        }
        background(work.reply, () -> {
            if (!work.reply.current()) return null;
            try (OutputStream output = activity.getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException("No output stream");
                if (!work.reply.current()) return null;
                output.write(work.text.getBytes(StandardCharsets.UTF_8)); output.flush();
            }
            return new JSONObject().put("saved", true);
        });
    }

    private FactoryDispatcher.Metadata metadata() {
        return FactoryDispatcher.metadata(host.isPreview() ? "preview" : "installed", activity.getPackageName(),
                Build.VERSION.SDK_INT, activity.getApplicationInfo().targetSdkVersion);
    }
    private interface Work { Object run() throws Exception; }
    private void background(Reply reply, Work work) {
        try {
            io.execute(() -> {
                try {
                    Object result;
                    if (host.isPreview()) {
                        // Full scope/authority validation may involve I/O, so it stays outside
                        // the session monitor. Recheck the fast revocation flag inside it.
                        if (!reply.current()) return;
                        synchronized (lifecycle) {
                            if (!reply.live()) return;
                            result = work.run();
                        }
                    } else {
                        // Never block main-thread teardown on a SharedPreferences commit or
                        // document provider. A started installed operation may finish after close;
                        // stale queued operations and replies are always discarded.
                        if (!reply.current()) return;
                        result = work.run();
                    }
                    main.post(() -> reply.ok(result));
                }
                catch (FactoryException e) { main.post(() -> reply.fail(e.code, e.getMessage())); }
                catch (SecurityException e) { main.post(() -> reply.fail("PERMISSION_DENIED", "Permission is no longer available.")); }
                catch (Exception e) { main.post(() -> reply.fail("IO_ERROR", "The operation could not be saved.")); }
            });
        } catch (RejectedExecutionException e) { reply.fail("BUSY", "Native operation queue is full."); }
    }
    private final class Reply {
        final String id, method; final JavaScriptReplyProxy proxy; final int page;
        Reply(String id, String method, JavaScriptReplyProxy proxy, int page) { this.id = id; this.method = method; this.proxy = proxy; this.page = page; }
        boolean live() { return !destroyed && host.isSessionOpen() && page == generation && pending.get(id) == this; }
        boolean current() { return live() && host.isActive() && live(); }
        void ok(Object value) { if (current()) host.onTrace(method, value instanceof JSONObject && ((JSONObject) value).optBoolean("simulated") ? "simulated" : "success"); complete(BridgeProtocol.success(id, value)); }
        void fail(String code, String message) { if (current()) host.onTrace(method, "error"); complete(BridgeProtocol.failure(id, code, message)); }
        void complete(String response) {
            if (!current()) return;
            pending.remove(id); safePost(proxy, response);
        }
    }
    private final class Export {
        final Reply reply; String text;
        Export(Reply reply, String text) { this.reply = reply; this.text = text; }
    }
    private void safePost(JavaScriptReplyProxy proxy, String response) {
        if (destroyed || !host.isActive() || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        try { proxy.postMessage(response); } catch (RuntimeException ignored) { /* originating frame was destroyed */ }
    }
    private void showError(String message) {
        synchronized (lifecycle) { invalidate(); }
        disposeWebView();
        host.onTrace("runtime", "failed");
        TextView text = new TextView(activity); text.setText(message); text.setTextSize(18);
        text.setTextColor(FactoryWindowPolicy.isDark(activity) ? FactoryWindowPolicy.DARK_TEXT : FactoryWindowPolicy.LIGHT_TEXT);
        int padding = (int) (24 * activity.getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding * 2, padding, padding);
        root.removeAllViews();
        root.addView(text, new FrameLayout.LayoutParams(-1, -1));
    }
    @Override public void close() {
        synchronized (lifecycle) { destroyed = true; invalidate(); host.resetStorage(); }
        io.shutdownNow();
        disposeWebView();
    }
    private void disposeWebView() {
        WebView old = webView;
        String profile = previewProfile;
        webView = null; previewProfile = null;
        Runnable destroy = () -> {
            if (old == null) return;
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
                    WebViewCompat.removeWebMessageListener(old, BRIDGE_NAME);
            } catch (RuntimeException ignored) { }
            try { old.stopLoading(); } catch (RuntimeException ignored) { }
            if (old.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) old.getParent()).removeView(old);
            old.destroy();
        };
        if (profile != null) {
            if (!PROFILES.retire(profile, destroy, PROFILE_PROVIDER)) host.onTrace("runtime", "cleanup_pending");
        } else {
            try { destroy.run(); } catch (RuntimeException ignored) { }
        }
    }
}
