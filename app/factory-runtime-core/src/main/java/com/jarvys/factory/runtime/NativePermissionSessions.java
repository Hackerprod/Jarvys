package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Shared live authority adapter. ALLOW uses IPC only; ASK alone launches host review. */
final class NativePermissionSessions implements AutoCloseable {
    static final int REVIEW_REQUEST = 50;
    interface Callback {
        boolean current();
        boolean claimUi();
        void releaseUi();
        void ready(Lease lease);
        void failed(String code, String message);
    }
    final class Lease implements NativeOperationAuthorization.Access, AutoCloseable {
        final Callback callback;
        final NativeOperationRequest request;
        final String nonce;
        final AtomicBoolean ended = new AtomicBoolean();
        volatile NativeOperationAuthorization.Client client;
        volatile boolean externalHandoff, reviewLaunched, reviewReturned, delivered;
        volatile Runnable cancellation;
        String reviewToken;
        boolean reviewUiHeld;
        void releaseReviewUi() { if (reviewUiHeld) { reviewUiHeld = false; callback.releaseUi(); } }
        final Runnable expiry = () -> fail(this, "TIMEOUT", "Native operation authorization expired; earlier effects are not undone.");
        Lease(NativeOperationRequest request, Callback callback) {
            this.request = request; this.callback = callback;
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            StringBuilder value = new StringBuilder();
            for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            nonce = value.toString();
        }
        @Override public void check() {
            if (ended.get() || closed || !foreground || !callback.current() || client == null)
                throw new SecurityException("Native operation no longer owns the foreground page");
            client.check();
            if (ended.get() || closed || !foreground || !callback.current()) throw new SecurityException("Native operation authority ended");
        }
        void onCancelled(Runnable action) {
            cancellation = action;
            if (ended.get() && action != null) action.run();
        }
        /** Only trusted runtime code can preserve its own Android picker/permission handoff. */
        void externalHandoff(boolean value) { externalHandoff = value; }
        @Override public void close() {
            if (!ended.compareAndSet(false, true)) return;
            leases.remove(this); main.removeCallbacks(expiry);
            NativeOperationAuthorization.Client value = client;
            if (value != null) value.close();
            main.post(() -> {
                if (review == this && reviewLaunched && !reviewReturned) return;
                if (review == this) review = null;
                releaseReviewUi();
            });
        }
    }
    private final Activity activity;
    private final FactoryConfig config;
    private final Executor io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Lease> leases = ConcurrentHashMap.newKeySet();
    private volatile boolean foreground, closed;
    private Lease review;
    NativePermissionSessions(Activity activity, FactoryConfig config, Executor io, boolean foreground) {
        this.activity = activity; this.config = config; this.io = io; this.foreground = foreground;
    }
    Lease authorize(String operation, JSONObject details, Callback callback) throws Exception {
        if (closed || !foreground || !callback.current()) throw new FactoryException("UNAVAILABLE", "Native operations require the active foreground page.");
        NativeOperationRequest request = NativeOperationRequest.parse(operation, details);
        Lease lease = new Lease(request, callback); leases.add(lease); main.postDelayed(lease.expiry, 300_000);
        try { io.execute(() -> acquire(lease)); }
        catch (RuntimeException busy) { fail(lease, "BUSY", "Native authorization worker is busy."); }
        return lease;
    }
    private void acquire(Lease lease) {
        try {
            if (lease.ended.get() || !lease.callback.current()) throw new SecurityException();
            int hostUid = verifyHost();
            Bundle input = new Bundle(); input.putInt("protocolVersion", 1); input.putString("operation", lease.request.operation);
            input.putString("nonce", lease.nonce); input.putString("details", lease.request.canonicalJson());
            Bundle response = activity.getContentResolver().call(Uri.parse("content://" + config.documentBroker.packageName + ".factory.operations"), "acquire", null, input);
            if (response == null || response.hasFileDescriptors()) throw new SecurityException();
            Set<String> expected = new HashSet<>(Arrays.asList("protocolVersion", "nonce", "binding", "lease", "status"));
            if (response.containsKey("reviewToken")) expected.add("reviewToken");
            if (!response.keySet().equals(expected) || response.getInt("protocolVersion", -1) != 1 || !lease.nonce.equals(response.getString("nonce")) ||
                    !lease.request.binding(lease.nonce).equals(response.getString("binding"))) throw new SecurityException();
            lease.reviewToken = response.getString("reviewToken");
            NativeOperationAuthorization.Client client = NativeOperationAuthorization.connect(response.getBinder("lease"), lease.nonce,
                    lease.request.binding(lease.nonce), uid -> uid == hostUid && verifiedUid(hostUid),
                    () -> fail(lease, "CANCELLED", "Native operation permission was revoked or its host ended; earlier effects are not undone."));
            lease.client = client;
            if (lease.ended.get()) { client.close(); return; }
            int status = client.status();
            if (status == NativeOperationAuthorization.APPROVED) {
                if (lease.reviewToken != null) throw new SecurityException();
                main.post(() -> deliver(lease));
            } else if (status == NativeOperationAuthorization.PENDING) {
                if (lease.reviewToken == null || !lease.reviewToken.matches("[a-f0-9]{64}")) throw new SecurityException();
                main.post(() -> launchReview(lease));
            } else throw new SecurityException();
        } catch (Exception unavailable) { fail(lease, "PERMISSION_DENIED", "Native operation is denied or its matching policy host is unavailable."); }
    }
    private int verifyHost() throws Exception {
        DocumentBrokerIdentity.verify(activity, config.documentBroker);
        String name = config.documentBroker.packageName;
        ProviderInfo provider = activity.getPackageManager().resolveContentProvider(name + ".factory.operations", 0);
        int uid = activity.getPackageManager().getApplicationInfo(name, 0).uid;
        if (provider == null || !name.equals(provider.packageName) || !provider.exported || !provider.enabled ||
                provider.applicationInfo == null || provider.applicationInfo.uid != uid || !provider.applicationInfo.enabled ||
                !"com.jarvys.agent.apkfactory.FactoryOperationAuthorizationProvider".equals(provider.name) || !verifiedUid(uid)) throw new SecurityException();
        return uid;
    }
    private boolean verifiedUid(int uid) {
        try {
            DocumentBrokerIdentity.verify(activity, config.documentBroker);
            String[] names = activity.getPackageManager().getPackagesForUid(uid);
            return names != null && names.length == 1 && names[0].equals(config.documentBroker.packageName) &&
                    activity.getPackageManager().getApplicationInfo(names[0], 0).uid == uid;
        } catch (Exception unavailable) { return false; }
    }
    private void launchReview(Lease lease) {
        if (lease.ended.get()) return;
        if (closed || !foreground || !activity.hasWindowFocus() || !lease.callback.current() || review != null || !lease.callback.claimUi()) {
            fail(lease, "BUSY", "Another native UI already owns the page."); return;
        }
        review = lease; lease.reviewUiHeld = true;
        try {
            verifyHost();
            Intent intent = new Intent().setComponent(new ComponentName(config.documentBroker.packageName,
                    "com.jarvys.agent.apkfactory.FactoryOperationApprovalActivity"));
            intent.putExtra("reviewToken", lease.reviewToken).putExtra("nonce", lease.nonce);
            lease.reviewLaunched = true;
            activity.startActivityForResult(intent, REVIEW_REQUEST);
        } catch (Exception unavailable) { lease.reviewLaunched = false; fail(lease, "UNAVAILABLE", "The native policy review could not be opened."); }
    }
    boolean onActivityResult(int request, int result, Intent data) {
        if (request != REVIEW_REQUEST) return false;
        Lease value = review;
        if (value == null || value.reviewReturned) return true;
        value.reviewReturned = true;
        if (value.ended.get()) { review = null; value.releaseReviewUi(); return true; }
        if (result != Activity.RESULT_OK) fail(value, "CANCELLED", "Native operation review was cancelled.");
        else {
            Bundle extras = data == null ? null : data.getExtras();
            if (data == null || data.getData() != null || data.getClipData() != null || data.getSelector() != null ||
                    data.getAction() != null || data.getType() != null || data.getComponent() != null || data.getPackage() != null ||
                    data.getCategories() != null || data.getFlags() != 0 || extras == null || extras.hasFileDescriptors() ||
                    !extras.keySet().equals(java.util.Collections.singleton("nonce")) || !value.nonce.equals(extras.getString("nonce")))
                fail(value, "PERMISSION_DENIED", "Native review result did not match this request.");
            else resumeReview(value);
        }
        return true;
    }
    private void resumeReview(Lease value) {
        if (!foreground || !activity.hasWindowFocus() || !value.reviewReturned || value.ended.get()) return;
        review = null; value.releaseReviewUi();
        try { io.execute(() -> {
            try { value.client.check(); main.post(() -> deliver(value)); }
            catch (RuntimeException denied) { fail(value, "PERMISSION_DENIED", "Native operation review did not authorize this request."); }
        }); } catch (RuntimeException busy) { fail(value, "BUSY", "Native authorization worker is busy."); }
    }
    private void deliver(Lease value) {
        if (value.ended.get() || value.delivered) return;
        if (closed || !foreground || !value.callback.current()) { fail(value, "CANCELLED", "The requesting page is no longer active."); return; }
        value.delivered = true; main.removeCallbacks(value.expiry);
        try { value.callback.ready(value); }
        catch (RuntimeException failed) { fail(value, "NATIVE_ERROR", "Native operation could not start."); }
    }
    private void fail(Lease value, String code, String message) {
        if (!value.ended.compareAndSet(false, true)) return;
        leases.remove(value); main.removeCallbacks(value.expiry);
        Runnable cancel = value.cancellation;
        if (cancel != null) try { cancel.run(); } catch (RuntimeException ignored) { }
        NativeOperationAuthorization.Client client = value.client;
        if (client != null) client.close();
        main.post(() -> {
            if (!(review == value && value.reviewLaunched && !value.reviewReturned)) {
                if (review == value) review = null; value.releaseReviewUi();
            }
            value.callback.failed(code, message);
        });
    }
    boolean isClosed() { return closed; }
    boolean hasPendingReview() { return review != null && review.reviewLaunched && !review.reviewReturned; }
    void onPause() {
        foreground = false;
        for (Lease value : new ArrayList<>(leases))
            if (!(value.reviewLaunched && !value.reviewReturned) && !value.externalHandoff)
                fail(value, "CANCELLED", "Native operation ended in background; earlier effects are not undone.");
    }
    void onResume() { foreground = true; if (review != null) resumeReview(review); }
    void onWindowFocusChanged(boolean focused) { if (focused && foreground && review != null) resumeReview(review); }
    void cancelAll() {
        for (Lease value : new ArrayList<>(leases)) fail(value, "CANCELLED", "Native response ownership ended; earlier effects are not undone.");
    }
    @Override public void close() {
        closed = true;
        for (Lease value : new ArrayList<>(leases)) fail(value, "CANCELLED", "Native operation page ended; earlier effects are not undone.");
    }
}
