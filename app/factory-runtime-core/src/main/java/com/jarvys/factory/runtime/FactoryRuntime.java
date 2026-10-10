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
    private static final int DOCUMENT_REQUEST = 42;
    private static final int FILE_SHARE_REQUEST = 43;
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
    private DocumentHandles documents = new DocumentHandles();
    private volatile boolean documentForeground;
    private DocumentSelection documentSelection;
    private volatile FileShare fileShare;
    // No new selection while old descriptors/grants are closing: a delayed revoke for the
    // same URI must never revoke a newer selection. Provider cleanup may block indefinitely.
    private static final java.util.concurrent.atomic.AtomicInteger documentResources = new java.util.concurrent.atomic.AtomicInteger();
    private static final ThreadPoolExecutor DOCUMENT_CLEANUP = new ThreadPoolExecutor(2, 2, 1, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), runnable -> {
                Thread thread = new Thread(runnable, "factory-document-grants"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    static { DOCUMENT_CLEANUP.allowCoreThreadTimeOut(true); }
    private final Runnable expireDocuments = new Runnable() {
        public void run() {
            if (destroyed) return;
            documents.expire();
            FileShare share = fileShare;
            if (share != null && share.snapshot != null) share.snapshot.expire();
            main.postDelayed(this, 1000);
        }
    };

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
            main.removeCallbacks(expireDocuments);
            if (config.capabilities.contains("documents")) main.postDelayed(expireDocuments, 1000);
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
        generation++; pending.clear(); uiOwner = null;
        documents.revokeAll(); documents = new DocumentHandles();
        cancelDocumentSelection(); cancelFileShare();
        // Keep an outstanding picker tombstone until its callback: request code 41 must never
        // attach an old result to a new page's export request.
        if (export != null) export.text = null;
        if (prompt != null) { prompt.dismiss(); prompt = null; }
    }
    private AlertDialog prompt;
    private String previewProfile;
    private static final PreviewProfiles PROFILES = new PreviewProfiles();
    private static final PreviewProfiles.Provider PROFILE_PROVIDER = new PreviewProfiles.Provider() {
        public java.util.List<String> names() {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
                return androidx.webkit.ProfileStore.getInstance().getAllProfileNames();
            throw new UnsupportedOperationException("Isolated WebView profiles are unavailable");
        }
        public boolean delete(String name) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
                return androidx.webkit.ProfileStore.getInstance().deleteProfile(name);
            return false;
        }
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
        if (host.isPreview()) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                if (!PROFILES.cleanup(PROFILE_PROVIDER)) host.onTrace("runtime", "cleanup_pending");
                webView = new WebView(activity);
                previewProfile = PROFILES.reserve();
                WebViewCompat.setProfile(webView, previewProfile);
                CookieManager cookies = WebViewCompat.getProfile(webView).getCookieManager();
                cookies.setAcceptCookie(false);
                cookies.setAcceptThirdPartyCookies(webView, false);
            } else {
                showError("Factory preview requires an Android System WebView with isolated profile support. Update WebView or test the installed APK separately.");
                return false;
            }
        } else {
            webView = new WebView(activity);
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
        if (!host.isPreview()) {
            CookieManager.getInstance().setAcceptCookie(false);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        }
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
        } catch (FactoryException e) {
            if (uiOwner == reply) uiOwner = null;
            reply.fail(e.code, e.getMessage());
        } catch (Exception e) {
            if (uiOwner == reply) uiOwner = null;
            if (export != null && export.reply == reply) export = null;
            reply.fail("NATIVE_ERROR", "The native operation could not be completed.");
        }
    }
    private static final Object DEFERRED = new Object();
    /** Installed side-effect adapter, never invoked by preview hosts. */
    private void dispatchInstalledEffect(BridgeProtocol.Request request, Reply reply) throws Exception {
        if (!reply.current()) return;
        switch (request.operation) {
            case SHARE_FILE:
                shareFile(request, reply); break;
            case DOCUMENTS_OPEN: case DOCUMENTS_CREATE:
                openDocument(request, reply); break;
            case DOCUMENTS_READ:
                requireDocumentForeground();
                final DocumentHandles readHandles = documents;
                background(reply, () -> {
                    DocumentHandles.ReadResult value = readHandles.read(request.args.getString("handle"), request.args.getLong("offset"), request.args.getInt("length"));
                    return new JSONObject().put("data", value.base64).put("offset", value.offset)
                            .put("nextOffset", value.nextOffset).put("eof", value.eof);
                }); break;
            case DOCUMENTS_WRITE:
                requireDocumentForeground();
                final DocumentHandles writeHandles = documents;
                background(reply, () -> {
                    DocumentHandles.WriteResult value = writeHandles.write(request.args.getString("handle"), request.args.getLong("offset"), request.args.getString("data"));
                    return new JSONObject().put("offset", value.offset).put("nextOffset", value.nextOffset)
                            .put("bytesWritten", value.bytesWritten).put("providerCommitConfirmed", false);
                }); break;
            case DOCUMENTS_CLOSE:
                documents.close(request.args.getString("handle"));
                reply.ok(new JSONObject().put("status", "close_requested").put("providerCommitConfirmed", false)); break;
            case DOCUMENTS_CANCEL:
                documents.cancelAll(); cancelDocumentSelection(); cancelFileShare();
                reply.ok(new JSONObject().put("cancelled", true).put("rollbackConfirmed", false)
                        .put("pickerMayRemainOpen", documentSelection != null)); break;
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
        if (uiOwner != null || export != null || documentSelection != null || fileShare != null || !activity.hasWindowFocus() || activity.isFinishing()) {
            reply.fail("BUSY", "Another native prompt is open, or the application is not in the foreground."); return false;
        }
        uiOwner = reply; return true;
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_SHARE_REQUEST) {
            FileShare work = fileShare;
            if (work == null) return;
            work.returned = true; work.resultCode = resultCode; work.result = data;
            if (work.snapshot != null) work.snapshot.close();
            if (documentForeground) finishFileShare(work);
            return;
        }
        if (requestCode == DOCUMENT_REQUEST) {
            DocumentSelection work = documentSelection;
            if (work == null) { releaseDocumentGrant(data); return; }
            work.returned = true;
            if (work.cancelled || !work.reply.current()) {
                releaseDocumentGrant(data); finishDocumentSelection(work); return;
            }
            if (resultCode != Activity.RESULT_OK || data == null) {
                releaseDocumentGrant(data); finishDocumentSelection(work); work.reply.fail("CANCELLED", "Document selection ended without access. An empty file may remain."); return;
            }
            work.result = data;
            if (documentForeground) acceptDocumentSelection(work);
            return;
        }
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

    /** Pause always revokes existing handles. The external broker alone may retain a picker tombstone. */
    public void onPause() {
        documentForeground = false;
        FileShare share = fileShare;
        if (share != null && !share.launched) cancelFileShare();
        documents.cancelAll();
        DocumentSelection work = documentSelection;
        if (work != null && work.returned) cancelDocumentSelection();
        for (Reply reply : new java.util.ArrayList<>(pending.values())) {
            if (reply.method.startsWith("documents.") && (work == null || reply != work.reply))
                reply.fail("CANCELLED", "Document access ended in background. A started write may have partially completed.");
        }
    }
    public void onResume() {
        documentForeground = true;
        FileShare share = fileShare;
        if (share != null && share.returned) finishFileShare(share);
        DocumentSelection work = documentSelection;
        if (work != null && work.result != null) acceptDocumentSelection(work);
    }
    private void requireDocumentForeground() throws FactoryException {
        if (!documentForeground || destroyed || activity.isFinishing() || !host.isActive())
            throw new FactoryException("UNAVAILABLE", "Document access requires the foreground application.");
    }
    private static final class FileShare {
        final Reply reply;
        final String nonce;
        volatile FileShareTransfer snapshot;
        volatile boolean cancelled, launched;
        boolean returned;
        int resultCode;
        Intent result;
        FileShare(Reply reply, String nonce) { this.reply=reply; this.nonce=nonce; }
    }
    private void shareFile(BridgeProtocol.Request request, Reply reply) throws Exception {
        requireDocumentForeground();
        DocumentBrokerIdentity.verify(activity, config.documentBroker);
        if (!claimUi(reply)) return;
        final FileShareTransfer.Admission admission = FileShareTransfer.reserve();
        byte[] random = new byte[32]; new java.security.SecureRandom().nextBytes(random);
        StringBuilder nonce = new StringBuilder();
        for (byte b:random) nonce.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
        FileShare work = new FileShare(reply,nonce.toString()); fileShare=work;
        final DocumentHandles source=documents;
        try { io.execute(() -> {
            byte[] bytes=null;
            try {
                requireDocumentForeground();
                if (work.cancelled || !reply.current()) throw new FactoryException("CANCELLED","File sharing was cancelled.");
                bytes=source.snapshotForShare(request.args.getString("handle"));
                requireDocumentForeground();
                DocumentBrokerIdentity.verify(activity,config.documentBroker);
                final int hostUid=activity.getPackageManager().getApplicationInfo(config.documentBroker.packageName,0).uid;
                String[] packages=activity.getPackageManager().getPackagesForUid(hostUid);
                if (packages == null || packages.length != 1 || !packages[0].equals(config.documentBroker.packageName))
                    throw new FactoryException("UNAVAILABLE","The sharing host has an ambiguous UID.");
                work.snapshot=admission.complete(bytes,work.nonce,uid -> {
                    if (uid != hostUid || work.cancelled || !work.launched || destroyed || fileShare != work || !reply.current()) return false;
                    try {
                        DocumentBrokerIdentity.verify(activity,config.documentBroker);
                        String[] current=activity.getPackageManager().getPackagesForUid(uid);
                        return current != null && current.length == 1 && current[0].equals(config.documentBroker.packageName)
                                && activity.getPackageManager().getApplicationInfo(config.documentBroker.packageName,0).uid == uid;
                    } catch (Exception denied) { return false; }
                });
                bytes=null; // Ownership moved to bounded native endpoint, never JavaScript.
                main.post(() -> {
                    if (work.cancelled || !documentForeground || !reply.current() || fileShare != work) {
                        cancelFileShare(); finishUnlaunchedShare(work); return;
                    }
                    try {
                        DocumentBrokerIdentity.verify(activity,config.documentBroker);
                        android.os.Bundle extras=new android.os.Bundle();
                        extras.putInt("protocolVersion",1); extras.putString("nonce",work.nonce);
                        extras.putString("filename",request.args.getString("filename"));
                        extras.putString("mimeType",request.args.getString("mimeType"));
                        extras.putInt("size",work.snapshot.size); extras.putString("sha256",work.snapshot.sha256);
                        extras.putBinder("transfer",work.snapshot);
                        Intent intent=new Intent().setComponent(new android.content.ComponentName(config.documentBroker.packageName,
                                "com.jarvys.agent.apkfactory.FactoryFileShareActivity")).putExtras(extras);
                        work.launched=true;
                        activity.startActivityForResult(intent,FILE_SHARE_REQUEST);
                    } catch (Exception failure) {
                        work.launched=false; reply.fail("SHARE_UNAVAILABLE","The matching native sharing broker could not open.");
                        cancelFileShare(); finishUnlaunchedShare(work);
                    }
                });
            } catch (Exception failure) {
                if (bytes != null) java.util.Arrays.fill(bytes,(byte)0);
                if (work.snapshot != null) work.snapshot.close();
                admission.close();
                main.post(() -> {
                    if (fileShare == work) fileShare=null;
                    if (uiOwner == reply) uiOwner=null;
                    reply.fail(failure instanceof FactoryException ? ((FactoryException)failure).code : "SHARE_UNAVAILABLE",
                            "The bounded file snapshot could not be prepared. Reopen the document to retry.");
                });
            }
        }); } catch (RejectedExecutionException failure) {
            admission.close(); fileShare=null; if (uiOwner == reply) uiOwner=null;
            reply.fail("BUSY","The native file queue is full.");
        }
    }
    private void cancelFileShare() {
        FileShare work=fileShare;
        if (work == null) return;
        work.cancelled=true;
        work.reply.fail("CANCELLED","File sharing was cancelled. Host or recipient copies may remain; close the native sharing flow.");
        if (work.snapshot != null) work.snapshot.close();
        // A launched broker remains a tombstone until its own callback. Cancellation cannot
        // recall its staged copy or a recipient's copy, and never releases the host's guard.
    }
    private void finishUnlaunchedShare(FileShare work) {
        if (fileShare == work) fileShare=null;
        if (uiOwner == work.reply) uiOwner=null;
        work.reply.fail("SHARE_UNAVAILABLE","File sharing could not open. Reopen the document to retry.");
    }
    private void finishFileShare(FileShare work) {
        if (fileShare != work) return;
        fileShare=null; if (uiOwner == work.reply) uiOwner=null;
        if (work.cancelled || !work.reply.current()) {
            work.reply.fail("CANCELLED","Sharing ended. Recipient copies or an external task may remain."); return;
        }
        try {
            DocumentBrokerIdentity.verify(activity,config.documentBroker);
            Intent result=work.result;
            if (work.resultCode != Activity.RESULT_OK || result == null || result.getData() != null || result.getClipData() != null
                    || result.getSelector() != null || result.getFlags() != 0) throw new IllegalArgumentException();
            android.os.Bundle extras=result.getExtras();
            if (extras == null || !extras.keySet().equals(new java.util.HashSet<>(java.util.Arrays.asList("nonce","chooserOpened","deliveryConfirmed")))
                    || !work.nonce.equals(extras.get("nonce")) || !(extras.get("chooserOpened") instanceof Boolean)
                    || !Boolean.FALSE.equals(extras.get("deliveryConfirmed"))) throw new IllegalArgumentException();
            work.reply.ok(new JSONObject().put("chooserOpened",extras.getBoolean("chooserOpened")).put("deliveryConfirmed",false));
        } catch (Exception denied) {
            work.reply.fail("SHARE_UNAVAILABLE","The sharing outcome is unavailable. Opening a chooser never proves delivery.");
        }
    }
    private void openDocument(BridgeProtocol.Request request, Reply reply) throws Exception {
        requireDocumentForeground();
        DocumentBrokerIdentity.verify(activity, config.documentBroker);
        documents.cancelAll();
        if (documentResources.get() != 0)
            throw new FactoryException("BUSY", "A previous document is still closing. Try again after provider cleanup.");
        if (!claimUi(reply)) return;
        byte[] nonceBytes = new byte[32]; new java.security.SecureRandom().nextBytes(nonceBytes);
        StringBuilder nonce = new StringBuilder();
        for (byte b : nonceBytes) nonce.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        boolean write = request.operation == com.jarvys.factory.contract.CapabilityCatalog.Method.DOCUMENTS_CREATE;
        DocumentSelection work = new DocumentSelection(reply, nonce.toString(), write);
        documentSelection = work;
        Intent intent = new Intent().setComponent(new android.content.ComponentName(config.documentBroker.packageName, DocumentBrokerIdentity.ACTIVITY))
                .putExtra("operation", write ? "create" : "open").putExtra("nonce", work.nonce)
                .putExtra("mimeType", request.args.getString("mimeType"));
        if (write) intent.putExtra("filename", request.args.getString("filename"));
        try { activity.startActivityForResult(intent, DOCUMENT_REQUEST); }
        catch (RuntimeException e) {
            finishDocumentSelection(work);
            reply.fail("UNAVAILABLE", "The matching human-only Jarvys document broker is unavailable.");
        }
    }
    private void cancelDocumentSelection() {
        DocumentSelection work = documentSelection;
        if (work == null) return;
        work.cancelled = true;
        if (!work.cancelScheduled) {
            work.cancelScheduled = true;
            try { DOCUMENT_CLEANUP.execute(work.signal::cancel); }
            catch (RejectedExecutionException ignored) { host.onTrace("documents.cancel", "cleanup_unconfirmed"); }
        }
        work.reply.fail("CANCELLED", "Document access cancelled. Close the Jarvys picker/review yourself; created files are not rolled back.");
        if (work.result != null) { releaseDocumentGrant(work.result); work.result = null; }
        // Never dismiss the broker from JavaScript: its protected picker and latch need native closure.
        if (work.returned) finishDocumentSelection(work);
    }
    private void finishDocumentSelection(DocumentSelection work) {
        if (documentSelection == work) documentSelection = null;
        if (uiOwner == work.reply) uiOwner = null;
    }
    private void acceptDocumentSelection(DocumentSelection work) {
        if (work != documentSelection || work.result == null || work.opening) return;
        final Intent result = work.result;
        final Uri uri = result.getData();
        final int mode = work.write ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            requireDocumentForeground(); DocumentBrokerIdentity.verify(activity, config.documentBroker);
            if (work.cancelled || !work.reply.current() || !work.nonce.equals(result.getStringExtra("nonce"))
                    || uri == null || !"content".equals(uri.getScheme()) || uri.getAuthority() == null
                    || uri.getAuthority().isEmpty() || uri.getAuthority().contains("@") || uri.toString().length() > 8192
                    || result.getSelector() != null || result.getExtras() == null
                    || !result.getExtras().keySet().equals(java.util.Collections.singleton("nonce"))
                    || (result.getFlags() & (Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) != 0
                    || (result.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) != mode
                    || (result.getClipData() != null && (result.getClipData().getItemCount() != 1
                    || !uri.equals(result.getClipData().getItemAt(0).getUri())
                    || result.getClipData().getItemAt(0).getIntent() != null || result.getClipData().getItemAt(0).getText() != null))
                    || activity.checkUriPermission(uri, Process.myPid(), Process.myUid(), mode) != PackageManager.PERMISSION_GRANTED)
                throw new FactoryException("PERMISSION_DENIED", "The verified broker did not return one scoped document grant.");
            work.opening = true;
            final DocumentHandles handles = documents;
            final DocumentHandles.Reservation reservation = work.write ? handles.reserveWrite() : handles.reserveRead();
            documentResources.incrementAndGet();
            try { io.execute(() -> {
                boolean streamOwnsResource = false;
                try {
                    if (work.cancelled || !documentForeground || !work.reply.current()) throw new FactoryException("CANCELLED", "Document selection expired.");
                    reservation.beginOpen();
                    android.os.ParcelFileDescriptor descriptor = activity.getContentResolver().openFileDescriptor(uri, work.write ? "w" : "r", work.signal);
                    if (descriptor == null) throw new IOException("Document stream unavailable");
                    String handle;
                    if (work.write) {
                        OutputStream raw = new android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
                        OutputStream stream = new java.io.FilterOutputStream(raw) {
                            private void allowed() throws IOException { documentIoAllowed(work, uri, mode); }
                            public void write(int value) throws IOException { allowed(); out.write(value); }
                            public void write(byte[] data, int offset, int count) throws IOException { allowed(); out.write(data, offset, count); }
                            public void close() throws IOException { try { out.close(); } finally { releaseDocumentGrant(uri, mode); documentResources.decrementAndGet(); } }
                        };
                        streamOwnsResource = true;
                        handle = reservation.grantWrite(stream);
                    } else {
                        InputStream raw = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                        InputStream stream = new java.io.FilterInputStream(raw) {
                            private void allowed() throws IOException { documentIoAllowed(work, uri, mode); }
                            public int read() throws IOException { allowed(); return in.read(); }
                            public int read(byte[] data, int offset, int count) throws IOException { allowed(); return in.read(data, offset, count); }
                            public void close() throws IOException { try { in.close(); } finally { releaseDocumentGrant(uri, mode); documentResources.decrementAndGet(); } }
                        };
                        streamOwnsResource = true;
                        handle = reservation.grantRead(stream);
                    }
                    if (work.cancelled || !documentForeground || !work.reply.current()) {
                        handles.close(handle); throw new FactoryException("CANCELLED", "Document access expired.");
                    }
                    JSONObject response = new JSONObject().put("handle", handle).put("mode", work.write ? "write" : "read")
                            .put("expiresAfterMs", 300000).put("maximumBytes", 16 * 1024 * 1024)
                            .put("providerCommitConfirmed", false);
                    main.post(() -> {
                        if (work.cancelled || !documentForeground) {
                            try { handles.close(handle); } catch (FactoryException ignored) { }
                            work.reply.fail("CANCELLED", "Document access ended before delivery.");
                        } else work.reply.ok(response);
                    });
                } catch (Exception error) {
                    reservation.cancel();
                    if (!streamOwnsResource) { reservation.abortOpen(); releaseDocumentGrant(uri, mode); documentResources.decrementAndGet(); }
                    main.post(() -> work.reply.fail(error instanceof FactoryException ? ((FactoryException) error).code : "IO_ERROR",
                            "The document stream could not be opened. A created file may remain."));
                } finally { main.post(() -> finishDocumentSelection(work)); }
            }); } catch (RejectedExecutionException rejected) {
                reservation.cancel(); reservation.abortOpen();
                releaseDocumentGrant(uri, mode); documentResources.decrementAndGet();
                finishDocumentSelection(work); work.reply.fail("BUSY", "Document I/O queue is full.");
            }
        } catch (Exception error) {
            releaseDocumentGrant(result); finishDocumentSelection(work);
            work.reply.fail(error instanceof FactoryException ? ((FactoryException) error).code : "PERMISSION_DENIED",
                    "Document access could not be admitted. No handle was issued; a created file may remain.");
        }
    }
    private void documentIoAllowed(DocumentSelection work, Uri uri, int mode) throws IOException {
        if (destroyed || !documentForeground || work.cancelled || work.page != generation || !host.isActive()
                || activity.checkUriPermission(uri, Process.myPid(), Process.myUid(), mode) != PackageManager.PERMISSION_GRANTED)
            throw new IOException("Document authority revoked");
    }
    private void releaseDocumentGrant(Intent data) {
        if (data != null && data.getData() != null) releaseDocumentGrant(data.getData(),
                data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
    }
    private void releaseDocumentGrant(Uri uri, int mode) {
        if (uri == null || !"content".equals(uri.getScheme()) || mode == 0) return;
        documentResources.incrementAndGet();
        try {
            DOCUMENT_CLEANUP.execute(() -> {
                try {
                    activity.revokeUriPermission(uri, mode);
                    if (activity.checkUriPermission(uri, Process.myPid(), Process.myUid(), mode) == PackageManager.PERMISSION_GRANTED)
                        host.onTrace("documents.close", "grant_cleanup_unconfirmed");
                } catch (RuntimeException ignored) { host.onTrace("documents.close", "grant_cleanup_unconfirmed"); }
                finally { documentResources.decrementAndGet(); }
            });
        } catch (RejectedExecutionException saturated) {
            // Leave selection disabled for this runtime. Do not claim cleanup or spawn more threads.
            host.onTrace("documents.close", "grant_cleanup_unconfirmed");
        }
    }
    private final class DocumentSelection {
        final Reply reply;
        final String nonce;
        final boolean write;
        final int page;
        final android.os.CancellationSignal signal = new android.os.CancellationSignal();
        volatile boolean cancelled;
        boolean returned, opening, cancelScheduled;
        Intent result;
        DocumentSelection(Reply reply, String nonce, boolean write) {
            this.reply = reply; this.nonce = nonce; this.write = write; this.page = generation;
        }
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
        if (destroyed || !host.isActive()) return;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            try { proxy.postMessage(response); } catch (RuntimeException ignored) { /* originating frame was destroyed */ }
        }
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
        main.removeCallbacks(expireDocuments);
        documents.revokeAll();
        io.shutdown();
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
