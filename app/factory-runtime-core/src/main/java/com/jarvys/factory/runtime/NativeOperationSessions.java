package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Foreground operation lifetime adapter; only host-owned operations acquire a Binder lease. */
final class NativeOperationSessions implements AutoCloseable {
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
        final boolean local;
        volatile NativeOperationAuthorization.Client client;
        volatile boolean externalHandoff, delivered;
        volatile Runnable cancellation;
        final Runnable expiry = () -> fail(this, "TIMEOUT", "Native operation session expired; earlier effects are not undone.");
        Lease(NativeOperationRequest request, Callback callback) { this(request, callback, false); }
        Lease(NativeOperationRequest request, Callback callback, boolean local) {
            this.request = request; this.callback = callback; this.local = local;
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            StringBuilder value = new StringBuilder();
            for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            nonce = value.toString();
        }
        @Override public void check() {
            if (ended.get() || closed || !foreground || !callback.current() || (!local && client == null))
                throw new SecurityException("Native operation no longer owns the foreground page");
            if (!local) client.check();
            if (ended.get() || closed || !foreground || !callback.current()) throw new SecurityException("Native operation session ended");
        }
        void onCancelled(Runnable action) {
            cancellation = action;
            if (ended.get() && action != null) action.run();
        }
        /** Preserve an operation while Android's own selector or permission sheet is open. */
        void externalHandoff(boolean value) { externalHandoff = value; }
        @Override public void close() {
            if (!ended.compareAndSet(false, true)) return;
            leases.remove(this); main.removeCallbacks(expiry);
            NativeOperationAuthorization.Client value = client;
            if (value != null) value.close();
        }
    }
    private final Activity activity;
    private final FactoryConfig config;
    private final Executor io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Lease> leases = ConcurrentHashMap.newKeySet();
    private volatile boolean foreground, closed;
    NativeOperationSessions(Activity activity, FactoryConfig config, Executor io, boolean foreground) {
        this.activity = activity; this.config = config; this.io = io; this.foreground = foreground;
    }
    Lease authorize(String operation, JSONObject details, Callback callback) throws Exception {
        if (closed || !foreground || !callback.current()) throw new FactoryException("UNAVAILABLE", "Native operations require the active foreground page.");
        NativeOperationRequest request = NativeOperationRequest.parse(operation, details);
        Lease lease = new Lease(request, callback); leases.add(lease); main.postDelayed(lease.expiry, 300_000);
        try { io.execute(() -> acquire(lease)); }
        catch (RuntimeException busy) { fail(lease, "BUSY", "Native operation session worker is busy."); }
        return lease;
    }
    /** Authorize an app-owned effect against its packaged capability and current page, without contacting Jarvys. */
    Lease authorizeLocal(String operation, JSONObject details, Callback callback) throws Exception {
        if (closed || !foreground || !callback.current())
            throw new FactoryException("UNAVAILABLE", "Native operations require the active foreground page.");
        NativeOperationRequest request = NativeOperationRequest.parse(operation, details);
        requirePackagedCapabilities(request);
        Lease lease = new Lease(request, callback, true); leases.add(lease); main.postDelayed(lease.expiry, 300_000);
        if (!main.post(() -> deliver(lease))) fail(lease, "BUSY", "Native operation main-thread dispatcher is unavailable.");
        return lease;
    }
    private void requirePackagedCapabilities(NativeOperationRequest request) throws FactoryException {
        if (!config.capabilities.contains(request.capability))
            throw new FactoryException("CAPABILITY_DENIED", "Operation requires the declared " + request.capability + " capability.");
        if (("secrets.request".equals(request.operation) || "jobs.enqueueHttp".equals(request.operation)) &&
                !config.capabilities.contains("network"))
            throw new FactoryException("CAPABILITY_DENIED", "Operation also requires the declared network capability.");
        if (("network.upload".equals(request.operation) || "network.download".equals(request.operation) || "recording.record".equals(request.operation)) &&
                !config.capabilities.contains("documents"))
            throw new FactoryException("CAPABILITY_DENIED", "This operation also requires the declared documents capability.");
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
            if (!response.keySet().equals(expected) || response.getInt("protocolVersion", -1) != 1 || !lease.nonce.equals(response.getString("nonce")) ||
                    !lease.request.binding(lease.nonce).equals(response.getString("binding")) || response.getInt("status", -1) != NativeOperationAuthorization.ACTIVE)
                throw new SecurityException();
            NativeOperationAuthorization.Client client = NativeOperationAuthorization.connect(response.getBinder("lease"), lease.nonce,
                    lease.request.binding(lease.nonce), uid -> uid == hostUid && verifiedUid(hostUid),
                    () -> fail(lease, "CANCELLED", "This operation requires Jarvys installed and active; earlier effects are not undone."));
            lease.client = client;
            if (lease.ended.get()) { client.close(); return; }
            if (client.status() != NativeOperationAuthorization.ACTIVE) throw new SecurityException();
            main.post(() -> deliver(lease));
        } catch (Exception unavailable) { fail(lease, "UNAVAILABLE", "This operation requires Jarvys installed and active."); }
    }
    private int verifyHost() throws Exception { return NativeOperationHostIdentity.verify(activity, config.documentBroker); }
    private boolean verifiedUid(int uid) { return NativeOperationHostIdentity.verifiedUid(activity, config.documentBroker, uid); }
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
        main.post(() -> value.callback.failed(code, message));
    }
    boolean isClosed() { return closed; }
    void onPause() {
        foreground = false;
        for (Lease value : new ArrayList<>(leases))
            if (!value.externalHandoff) fail(value, "CANCELLED", "Native operation ended in background; earlier effects are not undone.");
    }
    void onResume() { foreground = true; }
    void cancelAll() {
        for (Lease value : new ArrayList<>(leases)) fail(value, "CANCELLED", "Native response ownership ended; earlier effects are not undone.");
    }
    @Override public void close() {
        closed = true;
        for (Lease value : new ArrayList<>(leases)) fail(value, "CANCELLED", "Native operation page ended; earlier effects are not undone.");
    }
}
