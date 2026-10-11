package com.jarvys.factory.runtime;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Installed network, notification and speech adapters; no preview/host-package effects. */
final class NativeEffects implements AutoCloseable {
    static final int NOTIFICATION_PERMISSION_REQUEST = 51;
    interface Reply {
        boolean current(); boolean claimUi(); void releaseUi();
        void ok(Object value); void fail(String code, String message);
    }
    private interface Work { void run(Job job) throws Exception; }
    private final Activity activity;
    private final FactoryConfig config;
    private final NativePermissionSessions permissions;
    private final Executor io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor networkIo;
    private final NativeNetwork network;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private NativeSpeech speech;
    private Job speechOwner;
    private Job permissionJob;
    private boolean permissionReturned;
    private volatile boolean foreground, closed;
    private final class Job {
        final String id, method; final Reply reply;
        final AtomicBoolean done = new AtomicBoolean();
        volatile NativePermissionSessions.Lease lease;
        volatile Runnable cancellation;
        Job(String id, String method, Reply reply) { this.id = id; this.method = method; this.reply = reply; }
        boolean current() { return !closed && !done.get() && reply.current() && jobs.get(id) == this; }
        void complete(Object result) {
            main.post(() -> {
                if (!current()) return;
                try {
                    if (!foreground || lease == null) throw new SecurityException("Native delivery authority ended.");
                    lease.check();
                    if (!done.compareAndSet(false, true)) return;
                    jobs.remove(id, this);
                    lease.close();
                    if (speechOwner == this) speechOwner = null;
                    reply.ok(result);
                } catch (Exception failure) { fail(failure); }
            });
        }
        void fail(String code, String message) {
            if (!done.compareAndSet(false, true)) return;
            jobs.remove(id, this);
            Runnable action = cancellation; if (action != null) try { action.run(); } catch (RuntimeException ignored) { }
            if (lease != null) lease.close();
            main.post(() -> { if (permissionJob != this || permissionReturned) reply.releaseUi(); reply.fail(code, message); });
        }
        void fail(Exception error) {
            if (error instanceof FactoryException) fail(((FactoryException) error).code, error.getMessage());
            else if (error instanceof SecurityException) fail("PERMISSION_DENIED", "Native operation permission ended; earlier effects are not undone.");
            else fail("NATIVE_ERROR", "Native operation could not be completed.");
        }
        void worker(Executor executor, Work work) {
            try { executor.execute(() -> { try { if (!current()) return; lease.check(); work.run(this); } catch (Exception failure) { fail(failure); } }); }
            catch (RuntimeException busy) { fail("BUSY", "Native operation worker is busy."); }
        }
    }
    NativeEffects(Activity activity, FactoryConfig config, NativePermissionSessions permissions, Executor io, boolean foreground) {
        this(activity, config, permissions, io, foreground,
                new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16),
                        task -> { Thread thread = new Thread(task, "factory-network"); thread.setDaemon(true); return thread; },
                        new ThreadPoolExecutor.AbortPolicy()), new NativeNetwork(), null);
    }
    NativeEffects(Activity activity, FactoryConfig config, NativePermissionSessions permissions, Executor io, boolean foreground,
                  ThreadPoolExecutor networkIo, NativeNetwork network, NativeSpeech speech) {
        this.activity = activity; this.config = config; this.permissions = permissions; this.io = io; this.foreground = foreground;
        this.networkIo = networkIo; this.network = network; this.speech = speech;
        networkIo.allowCoreThreadTimeOut(true);
    }
    void perform(BridgeProtocol.Request request, Reply reply) throws Exception {
        if (closed || !foreground || !reply.current()) throw new FactoryException("UNAVAILABLE", "Native operations require the foreground application.");
        switch (request.operation) {
            case NETWORK_CANCEL:
                int cancelled = 0;
                for (Job job : new ArrayList<>(jobs.values())) if (job.method.startsWith("network.")) {
                    cancelled++; job.fail("CANCELLED", "Network operation cancellation requested; bytes already sent are not recalled.");
                }
                reply.ok(new JSONObject().put("cancelled", cancelled).put("rollbackConfirmed", false)); return;
            case NOTIFICATIONS_STATUS:
                reply.ok(notifications().status(NotificationRequest.parseStatus(request.args.toString()))); return;
            case SPEECH_STATUS: reply.ok(speech().status()); return;
            case NETWORK_REQUEST: case NETWORK_PROBE:
                NetworkRequest network = request.operation == com.jarvys.factory.contract.CapabilityCatalog.Method.NETWORK_PROBE
                        ? NetworkRequest.fromProbe(request.args) : NetworkRequest.fromRequest(request.args);
                authorize(request, reply, network.authorizationDetails(), job -> {
                    NativeNetwork.Cancellation cancellation = new NativeNetwork.Cancellation(); job.cancellation = cancellation::cancel;
                    job.lease.onCancelled(cancellation::cancel);
                    job.worker(networkIo, value -> value.complete(NativeEffects.this.network.execute(network, value.lease, cancellation)));
                }); return;
            case NETWORK_PING:
                NetworkPingRequest ping = NetworkPingRequest.parse(request.args);
                authorize(request, reply, ping.authorizationDetails(), job -> {
                    NativePing.Cancellation cancellation = new NativePing.Cancellation(); job.cancellation = cancellation::cancel;
                    job.lease.onCancelled(cancellation::cancel);
                    job.worker(networkIo, value -> value.complete(new NativePing().execute(ping, value.lease, cancellation)));
                }); return;
            case NOTIFICATIONS_CHANNEL:
                NotificationRequest.Channel channel = NotificationRequest.parseChannel(request.args);
                authorize(request, reply, channel.authorizationDetails(), job -> job.worker(io, value -> value.complete(notifications().createChannel(channel, value.lease)))); return;
            case NOTIFICATIONS_SHOW:
                NotificationRequest.Show notification = NotificationRequest.parseShow(request.args);
                authorize(request, reply, notification.authorizationDetails(), job -> job.worker(io, value -> value.complete(notifications().show(notification, value.lease)))); return;
            case NOTIFICATIONS_CANCEL:
                int id = NotificationRequest.parseCancel(request.args);
                authorize(request, reply, new JSONObject().put("id", id), job -> job.worker(io, value -> value.complete(notifications().cancel(id, value.lease)))); return;
            case NOTIFICATIONS_REQUEST_PERMISSION:
                authorize(request, reply, new JSONObject(), this::requestPermission); return;
            case SPEECH_VOICES:
                String engine = SpeechRequest.parseVoices(request.args);
                authorize(request, reply, SpeechRequest.voicesAuthorizationDetails(engine), job -> {
                    NativeSpeech owner = speech(); owner.voices(engine, job.lease, speechCallback(job)); ownSpeech(job, owner);
                }); return;
            case SPEECH_SPEAK:
                SpeechRequest.Speak spoken = SpeechRequest.parseSpeak(request.args);
                authorize(request, reply, spoken.authorizationDetails(), job -> {
                    NativeSpeech owner = speech(); owner.speak(spoken, job.lease, speechCallback(job)); ownSpeech(job, owner);
                }); return;
            case SPEECH_STOP:
                authorize(request, reply, new JSONObject(), job -> job.complete(speech().stop(job.lease))); return;
            default: throw new FactoryException("UNKNOWN_METHOD", "Unknown native operation.");
        }
    }
    private NativeSpeech.Callback speechCallback(Job job) {
        return new NativeSpeech.Callback() {
            public void complete(JSONObject value) { job.complete(value); }
            public void error(FactoryException error) { job.fail(error); }
        };
    }
    private void ownSpeech(Job job, NativeSpeech owner) {
        speechOwner = job;
        Runnable close = () -> main.post(() -> {
            if (speech == owner && speechOwner == job) { owner.close(); speech = null; speechOwner = null; }
        });
        job.cancellation = close; job.lease.onCancelled(close);
    }
    private NativeNotifications notifications() { return new NativeNotifications(activity, config.appId); }
    private NativeSpeech speech() { if (speech == null) speech = new NativeSpeech(activity, config.appId); return speech; }
    private void authorize(BridgeProtocol.Request request, Reply reply, JSONObject details, Work work) throws Exception {
        Job job = new Job(request.id, request.method, reply); jobs.put(job.id, job);
        try {
            job.lease = permissions.authorize(request.method, details, new NativePermissionSessions.Callback() {
                public boolean current() { return job.current(); }
                public boolean claimUi() { return job.current() && reply.claimUi(); }
                public void releaseUi() { reply.releaseUi(); }
                public void ready(NativePermissionSessions.Lease lease) {
                    job.lease = lease;
                    if (!job.current()) { lease.close(); return; }
                    try { lease.check(); work.run(job); } catch (Exception failure) { job.fail(failure); }
                }
                public void failed(String code, String message) { job.fail(code, message); }
            });
        } catch (Exception denied) { job.fail(denied); }
    }
    private void requestPermission(Job job) throws Exception {
        JSONObject before = notifications().status(null);
        if (!"permission_required".equals(before.optString("status"))) { job.complete(before); return; }
        if (permissionJob != null || !job.reply.claimUi()) { job.fail("BUSY", "A native permission request is already open."); return; }
        permissionJob = job; permissionReturned = false; job.lease.externalHandoff(true);
        try {
            JSONObject result = notifications().requestPermission(activity, NOTIFICATION_PERMISSION_REQUEST, job.lease);
            if (!"permission_requested".equals(result.optString("status"))) {
                permissionJob = null; job.lease.externalHandoff(false); job.reply.releaseUi(); job.complete(result);
            }
        } catch (Exception failed) { permissionJob = null; job.lease.externalHandoff(false); job.reply.releaseUi(); job.fail(failed); }
    }
    boolean onRequestPermissionsResult(int request, String[] names, int[] grants) {
        if (request != NOTIFICATION_PERMISSION_REQUEST) return false;
        Job job = permissionJob; if (job == null || permissionReturned) return true;
        permissionReturned = true;
        if (names == null || names.length != 1 || !Manifest.permission.POST_NOTIFICATIONS.equals(names[0]) || grants == null || grants.length != 1)
            job.fail("PERMISSION_DENIED", "Unexpected Android permission result.");
        finishPermission(); return true;
    }
    private void finishPermission() {
        Job job = permissionJob;
        if (job == null || !permissionReturned || !foreground) return;
        permissionJob = null; job.reply.releaseUi(); if (job.lease != null) job.lease.externalHandoff(false);
        if (job.done.get()) return;
        job.worker(io, value -> value.complete(notifications().status(null))); // Re-read OS truth, not callback's Boolean.
    }
    boolean isClosed() { return closed; }
    boolean hasPendingPermission() { return permissionJob != null && !permissionReturned; }
    void onPause() { foreground = false; }
    void onResume() { foreground = true; finishPermission(); }
    @Override public void close() {
        closed = true;
        for (Job job : new ArrayList<>(jobs.values())) job.fail("CANCELLED", "Native operation page ended; earlier effects are not undone.");
        if (speech != null) { speech.close(); speech = null; speechOwner = null; }
        networkIo.shutdown();
    }
}
