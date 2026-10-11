package com.jarvys.factory.runtime;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
import java.util.function.Supplier;
import org.json.JSONObject;

/** Installed native adapters; no preview/host-package effects. */
final class NativeEffects implements AutoCloseable {
    static final int NOTIFICATION_PERMISSION_REQUEST = 51;
    interface Reply {
        boolean current(); boolean claimUi(); void releaseUi();
        default boolean pageCurrent() { return current(); }
        void ok(Object value); void fail(String code, String message);
        /** True confirms bridge-post acceptance, not JavaScript execution. */
        default boolean deliver(Object value) { ok(value); return true; }
    }
    private interface Work { void run(Job job) throws Exception; }
    private final Activity activity;
    private final FactoryConfig config;
    private final NativePermissionSessions permissions;
    private final Executor io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor networkIo;
    private final NativeNetwork network;
    private final Supplier<DocumentHandles> documents;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private NativeSpeech speech;
    private NativeVoiceRecognition recognition;
    private NativeLocation location;
    private NativeSteps steps;
    private SensorSubscriptions subscriptions;
    private final Map<String, Job> subscriptionJobs = new ConcurrentHashMap<>();
    private RecordingEffects recording;
    private Job recognitionUiJob;
    private String[] permissionNames;
    private Job speechOwner;
    private Job permissionJob;
    private boolean permissionReturned;
    private volatile boolean foreground, closed;
    private final class Job {
        final String id, method; final String key = java.util.UUID.randomUUID().toString(); final Reply reply;
        volatile boolean retained;
        final AtomicBoolean done = new AtomicBoolean();
        volatile NativePermissionSessions.Lease lease;
        volatile Runnable cancellation;
        Job(String id, String method, Reply reply) { this.id = id; this.method = method; this.reply = reply; }
        boolean current() { return !closed && !done.get() && (retained ? reply.pageCurrent() : reply.current()) && jobs.get(key) == this; }
        void retain(Object result) {
            main.post(() -> {
                try {
                    if (!current() || !foreground || lease == null) throw new SecurityException();
                    lease.check(); retained = true;
                    if (!reply.deliver(result)) fail("CANCELLED", "Subscription reply could not be delivered.");
                } catch (Exception failure) { fail(failure); }
            });
        }
        void complete(Object result) {
            main.post(() -> {
                if (!current()) return;
                try {
                    if (!foreground || lease == null) throw new SecurityException("Native delivery authority ended.");
                    lease.check();
                    if (!done.compareAndSet(false, true)) return;
                    jobs.remove(key, this);
                    lease.close();
                    if (speechOwner == this) speechOwner = null;
                    reply.ok(result);
                } catch (Exception failure) { fail(failure); }
            });
        }
        /** A native-owned stream must be retired if the bridge cannot accept its handle. */
        void completeOwned(Object result, Runnable delivered, Runnable rejected) {
            Runnable delivery = () -> {
                boolean accepted = false;
                try {
                    if (!current()) { fail("CANCELLED", "Recording page ended before delivery."); return; }
                    if (!foreground || lease == null) throw new SecurityException("Native delivery authority ended.");
                    lease.check();
                    if (!done.compareAndSet(false, true)) return;
                    jobs.remove(key, this);
                    lease.close();
                    if (reply.deliver(result)) {
                        accepted = true;
                        cancellation = null;
                        delivered.run();
                    }
                } catch (Exception failure) { fail(failure); }
                finally { if (!accepted) rejected.run(); }
            };
            if (!main.post(delivery)) {
                rejected.run();
                fail("CANCELLED", "Recording response worker ended.");
            }
        }
        void fail(String code, String message) {
            if (!done.compareAndSet(false, true)) return;
            jobs.remove(key, this);
            Runnable action = cancellation; if (action != null) try { action.run(); } catch (RuntimeException ignored) { }
            if (lease != null) lease.close();
            main.post(() -> { if ((permissionJob != this || permissionReturned) && !(recognitionUiJob == this && hasPendingActivity())) reply.releaseUi(); reply.fail(code, message); });
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
        this(activity, config, permissions, io, foreground, (Supplier<DocumentHandles>) null);
    }
    NativeEffects(Activity activity, FactoryConfig config, NativePermissionSessions permissions, Executor io, boolean foreground,
                  Supplier<DocumentHandles> documents) {
        this(activity, config, permissions, io, foreground,
                new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16),
                        task -> { Thread thread = new Thread(task, "factory-network"); thread.setDaemon(true); return thread; },
                        new ThreadPoolExecutor.AbortPolicy()), new NativeNetwork(), null, documents);
    }
    NativeEffects(Activity activity, FactoryConfig config, NativePermissionSessions permissions, Executor io, boolean foreground,
                  ThreadPoolExecutor networkIo, NativeNetwork network, NativeSpeech speech) {
        this(activity, config, permissions, io, foreground, networkIo, network, speech, null);
    }
    NativeEffects(Activity activity, FactoryConfig config, NativePermissionSessions permissions, Executor io, boolean foreground,
                  ThreadPoolExecutor networkIo, NativeNetwork network, NativeSpeech speech, Supplier<DocumentHandles> documents) {
        this.activity = activity; this.config = config; this.permissions = permissions; this.io = io; this.foreground = foreground;
        this.networkIo = networkIo; this.network = network; this.speech = speech;
        this.documents = documents == null ? () -> null : documents;
        networkIo.allowCoreThreadTimeOut(true);
    }
    void perform(BridgeProtocol.Request request, Reply reply) throws Exception {
        if (closed || !foreground || !reply.current()) throw new FactoryException("UNAVAILABLE", "Native operations require the foreground application.");
        switch (request.operation) {
            case STEPS_STATUS: reply.ok(steps().status()); return;
            case STEPS_CURRENT:
                StepsRequest.Current stepRequest = StepsRequest.parseCurrent(request.args);
                authorize(request, reply, stepRequest.authorizationDetails(), job -> {
                    NativeSteps.Handle handle = steps().current(stepRequest, job.lease, new NativeSteps.Callback() {
                        public void complete(JSONObject result) { job.complete(result); }
                        public void error(FactoryException error) { job.fail(error); }
                    });
                    job.cancellation = handle::cancel; job.lease.onCancelled(handle::cancel);
                    if (!job.current()) handle.cancel();
                }); return;
            case STEPS_CANCEL:
                JSONObject stepCancellation = steps().cancel();
                for (Job job : new ArrayList<>(jobs.values())) if ("steps.current".equals(job.method)) job.fail("CANCELLED", "Step capture cancelled.");
                reply.ok(stepCancellation); return;
            case STEPS_REQUEST_PERMISSION:
                authorize(request, reply, new JSONObject(), this::requestStepsPermission); return;
            case SENSORS_SUBSCRIBE:
                if (config.documentBroker == null) throw new FactoryException("UNAVAILABLE", "Sensor subscriptions require a generated app with a pinned policy host; legacy finite capture remains available.");
                SensorSubscriptionRequest.Subscribe subscribed = SensorSubscriptionRequest.parseSubscribe(request.args);
                authorize(request, reply, subscribed.authorizationDetails(), job -> {
                    AtomicBoolean settingUp = new AtomicBoolean(true), terminated = new AtomicBoolean();
                    JSONObject result;
                    try { result = subscriptions().subscribe(subscribed, new SensorSubscriptions.Authority() {
                        public boolean active() {
                        if (!foreground || !job.current()) return false;
                        try { job.lease.check(); return job.current(); }
                        catch (RuntimeException revoked) { job.fail("PERMISSION_DENIED", "Sensor subscription authority ended."); return false; }
                        }
                        public void ended() { terminated.set(true); if (!settingUp.get()) job.fail("CANCELLED", "Sensor subscription ended; native cleanup may remain pending."); }
                    }); } finally { settingUp.set(false); }
                    if (terminated.get()) { job.fail("CANCELLED", "Sensor subscription ended before delivery."); return; }
                    String token = result.getString("subscriptionId"); subscriptionJobs.put(token, job);
                    Runnable stop = () -> {
                        subscriptionJobs.remove(token, job);
                        try { subscriptions().unsubscribe(SensorSubscriptionRequest.parseHandle(new JSONObject().put("subscriptionId", token))); }
                        catch (Exception ignored) { /* Helper retains unconfirmed native cleanup admission. */ }
                    };
                    job.cancellation = stop; job.lease.onCancelled(() -> job.fail("PERMISSION_DENIED", "Sensor subscription authority ended."));
                    if (!job.current()) { stop.run(); return; }
                    job.retain(result);
                }); return;
            case SENSORS_POLL:
                SensorSubscriptionRequest.Handle polled = SensorSubscriptionRequest.parseHandle(request.args);
                Job polledJob = subscriptionJobs.get(polled.subscriptionId);
                try {
                    if (polledJob == null || !polledJob.current()) throw new FactoryException("INVALID_HANDLE", "Sensor subscription is no longer owned by this page.");
                    polledJob.lease.check(); JSONObject result = subscriptions().poll(polled);
                    polledJob.lease.check(); if (!polledJob.current()) throw new SecurityException(); reply.ok(result);
                } catch (Exception failure) { if (polledJob != null) polledJob.fail(failure); throw failure; }
                return;
            case SENSORS_UNSUBSCRIBE:
                SensorSubscriptionRequest.Handle unsubscribed = SensorSubscriptionRequest.parseHandle(request.args);
                Job unsubscribedJob = subscriptionJobs.remove(unsubscribed.subscriptionId);
                try { reply.ok(subscriptions().unsubscribe(unsubscribed)); }
                finally { if (unsubscribedJob != null) unsubscribedJob.fail("CANCELLED", "Sensor subscription ended."); }
                return;
            case NETWORK_CANCEL:
                int cancelled = 0;
                for (Job job : new ArrayList<>(jobs.values())) if (job.method.startsWith("network.")) {
                    cancelled++; job.fail("CANCELLED", "Network operation cancellation requested; bytes already sent are not recalled.");
                }
                reply.ok(new JSONObject().put("cancelled", cancelled).put("rollbackConfirmed", false)); return;
            case RECORDING_STATUS:
                RecordingRequest.parseEmpty(request.args.toString()); reply.ok(recordingStatus()); return;
            case RECORDING_REQUEST_PERMISSION:
                RecordingRequest.parseEmpty(request.args.toString());
                authorize(request, reply, new JSONObject(), this::requestRecordingPermission); return;
            case RECORDING_RECORD:
                RecordingRequest.Record captured = RecordingRequest.parseRecord(request.args);
                authorize(request, reply, captured.authorizationDetails(), job -> {
                    RecordingEffects.Handle handle = recording().record(captured, job.lease,
                            () -> foreground && !closed && job.current(), new RecordingEffects.Callback() {
                        public boolean current() { return job.current(); }
                        public void complete(JSONObject result, Runnable delivered, Runnable rejected) {
                            job.completeOwned(result, delivered, rejected);
                        }
                        public void error(FactoryException error) { job.fail(error); }
                    });
                    job.cancellation = handle::cancel;
                    job.lease.onCancelled(handle::cancel);
                    if (!job.current()) handle.cancel();
                }); return;
            case RECORDING_STOP:
                RecordingRequest.parseEmpty(request.args.toString());
                boolean stopping = recording != null && recording.stop();
                reply.ok(new JSONObject().put("status", stopping ? "stop_requested" : "idle")
                        .put("stopRequested", stopping).put("cleanupConfirmed", false)); return;
            case RECORDING_CANCEL:
                RecordingRequest.parseEmpty(request.args.toString());
                int recordingCancelled = 0;
                for (Job job : new ArrayList<>(jobs.values())) if ("recording.record".equals(job.method)) {
                    recordingCancelled++; job.fail("CANCELLED", "Recording cancellation requested; prior reads are not recalled.");
                }
                if (recording != null) recording.cancel();
                reply.ok(new JSONObject().put("cancelled", recordingCancelled).put("rollbackConfirmed", false)
                        .put("cleanupConfirmed", false)); return;
            case VOICE_RECOGNITION_STATUS: reply.ok(recognition().status()); return;
            case LOCATION_STATUS: reply.ok(location().status()); return;
            case VOICE_RECOGNITION_CANCEL: case LOCATION_CANCEL:
                String target = request.operation == com.jarvys.factory.contract.CapabilityCatalog.Method.LOCATION_CANCEL ? "location.current" : "voiceRecognition.recognize";
                int count = 0;
                for (Job job : new ArrayList<>(jobs.values())) if (target.equals(job.method)) { count++; job.fail("CANCELLED", "Native cancellation requested; prior effects are not recalled."); }
                reply.ok(new JSONObject().put("cancelled", count).put("rollbackConfirmed", false).put("externalActivityClosedVerified", false)); return;
            case VOICE_RECOGNITION_RECOGNIZE:
                VoiceRecognitionRequest.Recognize recognized = VoiceRecognitionRequest.parseRecognize(request.args);
                authorize(request, reply, recognized.authorizationDetails(), job -> {
                    NativeVoiceRecognition.Handle handle = recognition().recognize(recognized, job.lease, new NativeVoiceRecognition.Callback() {
                        public boolean current() { return job.current(); }
                        public boolean claimUi() { boolean held = job.current() && job.reply.claimUi(); if (held) recognitionUiJob = job; return held; }
                        public void releaseUi() { if (recognitionUiJob == job) recognitionUiJob = null; job.reply.releaseUi(); }
                        public void externalHandoff(boolean value) { job.lease.externalHandoff(value); }
                        public void complete(JSONObject result) { job.complete(result); }
                        public void error(FactoryException error) { job.fail(error); }
                    });
                    job.cancellation = handle::cancel; job.lease.onCancelled(handle::cancel);
                }); return;
            case LOCATION_CURRENT:
                LocationRequest.Current located = LocationRequest.parseCurrent(request.args);
                authorize(request, reply, located.authorizationDetails(), job -> {
                    NativeLocation.Handle handle = location().current(located, job.lease, new NativeLocation.Callback() {
                        public void complete(JSONObject result) { job.complete(result); }
                        public void error(FactoryException error) { job.fail(error); }
                    });
                    job.cancellation = handle::cancel; job.lease.onCancelled(handle::cancel);
                }); return;
            case LOCATION_REQUEST_PERMISSION:
                LocationRequest.Permission locationPermission = LocationRequest.parsePermission(request.args);
                authorize(request, reply, locationPermission.authorizationDetails(), job -> requestLocationPermission(job, locationPermission.precision)); return;
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
    private NativeVoiceRecognition recognition() { if (recognition == null) recognition = new NativeVoiceRecognition(activity, config.appId, foreground); return recognition; }
    private NativeSteps steps() { if (steps == null) steps = new NativeSteps(activity, config.appId, () -> foreground && !closed); return steps; }
    private SensorSubscriptions subscriptions() { if (subscriptions == null) subscriptions = SensorSubscriptions.installed(activity, main, config.resources.sensors); return subscriptions; }
    private NativeLocation location() { if (location == null) location = new NativeLocation(activity, config.appId, () -> foreground && !closed); return location; }
    private NativeSpeech speech() { if (speech == null) speech = new NativeSpeech(activity, config.appId); return speech; }
    private RecordingEffects recording() {
        if (recording == null) recording = new RecordingEffects(new NativeAudioRecording(activity, config.appId), documents, config.resources);
        return recording;
    }
    private JSONObject recordingStatus() throws Exception {
        return recording().status().put("permissionDeclared", microphonePermissionDeclared());
    }
    private boolean microphonePermissionDeclared() {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(config.appId, PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null) for (String name : info.requestedPermissions)
                if (Manifest.permission.RECORD_AUDIO.equals(name)) return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { /* Fail closed. */ }
        return false;
    }

    private void authorize(BridgeProtocol.Request request, Reply reply, JSONObject details, Work work) throws Exception {
        Job job = new Job(request.id, request.method, reply); jobs.put(job.key, job);
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
        permissionJob = job; permissionNames = new String[] { Manifest.permission.POST_NOTIFICATIONS }; permissionReturned = false; job.lease.externalHandoff(true);
        try {
            JSONObject result = notifications().requestPermission(activity, NOTIFICATION_PERMISSION_REQUEST, job.lease);
            if (!"permission_requested".equals(result.optString("status"))) {
                permissionJob = null; job.lease.externalHandoff(false); job.reply.releaseUi(); job.complete(result);
            }
        } catch (Exception failed) { permissionJob = null; job.lease.externalHandoff(false); job.reply.releaseUi(); job.fail(failed); }
    }
    private void requestStepsPermission(Job job) throws Exception {
        JSONObject before = steps().status();
        if (!before.optBoolean("permissionRequired") || before.optBoolean("activityRecognitionGranted")) { job.complete(before); return; }
        if (!before.optBoolean("activityRecognitionDeclared")) { job.complete(before.put("permissionRequestStatus", "permission_not_declared")); return; }
        if (permissionJob != null || !job.reply.claimUi()) { job.fail("BUSY", "A native permission request is already open."); return; }
        permissionJob = job; permissionReturned = false; permissionNames = new String[] { Manifest.permission.ACTIVITY_RECOGNITION };
        job.lease.externalHandoff(true);
        try { job.lease.check(); activity.requestPermissions(permissionNames.clone(), NOTIFICATION_PERMISSION_REQUEST); }
        catch (Exception failure) { permissionJob = null; permissionNames = null; job.lease.externalHandoff(false); job.reply.releaseUi(); job.fail(failure); }
    }
    private void requestLocationPermission(Job job, String precision) throws Exception {
        JSONObject before = location().status();
        boolean precise = "precise".equals(precision);
        if (!before.optBoolean("coarseDeclared") || (precise && !before.optBoolean("fineDeclared"))) {
            job.complete(before.put("permissionRequestStatus", "permission_not_declared")); return;
        }
        if (before.optBoolean("fineGranted") || (!precise && before.optBoolean("coarseGranted"))) {
            job.complete(before.put("permissionRequestStatus", "already_granted")); return;
        }
        if (permissionJob != null || !job.reply.claimUi()) { job.fail("BUSY", "A native permission request is already open."); return; }
        permissionJob = job; permissionReturned = false;
        permissionNames = precise ? new String[] { Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION }
                : new String[] { Manifest.permission.ACCESS_COARSE_LOCATION };
        job.lease.externalHandoff(true);
        try { job.lease.check(); activity.requestPermissions(permissionNames.clone(), NOTIFICATION_PERMISSION_REQUEST); }
        catch (Exception failed) { permissionJob = null; permissionNames = null; job.lease.externalHandoff(false); job.reply.releaseUi(); job.fail(failed); }
    }
    private void requestRecordingPermission(Job job) throws Exception {
        JSONObject before = recordingStatus();
        if (!before.optBoolean("permissionDeclared")) {
            job.complete(before.put("permissionRequestStatus", "permission_not_declared")); return;
        }
        if (before.optBoolean("permissionGranted")) {
            job.complete(before.put("permissionRequestStatus", "already_granted")); return;
        }
        if (permissionJob != null || !job.reply.claimUi()) { job.fail("BUSY", "A native permission request is already open."); return; }
        permissionJob = job; permissionReturned = false;
        permissionNames = new String[] { Manifest.permission.RECORD_AUDIO };
        job.lease.externalHandoff(true);
        try { job.lease.check(); activity.requestPermissions(permissionNames.clone(), NOTIFICATION_PERMISSION_REQUEST); }
        catch (Exception failed) { permissionJob = null; permissionNames = null; job.lease.externalHandoff(false); job.reply.releaseUi(); job.fail(failed); }
    }
    boolean onRequestPermissionsResult(int request, String[] names, int[] grants) {
        if (request != NOTIFICATION_PERMISSION_REQUEST) return false;
        Job job = permissionJob; if (job == null || permissionReturned) return true;
        permissionReturned = true;
        if (permissionNames == null || !java.util.Arrays.equals(names, permissionNames) || grants == null || grants.length != permissionNames.length)
            job.fail("PERMISSION_DENIED", "Unexpected Android permission result.");
        finishPermission(); return true;
    }
    private void finishPermission() {
        Job job = permissionJob;
        if (job == null || !permissionReturned || !foreground) return;
        permissionJob = null; job.reply.releaseUi(); if (job.lease != null) job.lease.externalHandoff(false);
        if (job.done.get()) return;
        job.worker(io, value -> value.complete("location.requestPermission".equals(value.method) ? location().status()
                : "recording.requestPermission".equals(value.method) ? recordingStatus() : "steps.requestPermission".equals(value.method) ? steps().status() : notifications().status(null))); // Re-read OS truth, not callback's Boolean.
    }
    boolean isClosed() { return closed; }
    boolean hasPendingPermission() { return permissionJob != null && !permissionReturned; }
    boolean hasPendingActivity() { return recognition != null && recognition.hasPendingActivity(); }
    boolean onActivityResult(int request, int result, Intent data) {
        if (request != NativeVoiceRecognition.REQUEST_CODE) return false;
        if (recognition != null) recognition.onActivityResult(request, result, data);
        return true;
    }
    void onWindowFocusChanged(boolean focused) { if (recognition != null) recognition.onWindowFocusChanged(focused); }
    void onPause() { foreground = false; if (recognition != null) recognition.onPause(); if (location != null) location.cancel(); if (recording != null) recording.cancel(); if (steps != null) steps.cancel();
        for (Job job : new ArrayList<>(subscriptionJobs.values())) job.fail("CANCELLED", "Sensor subscriptions require the foreground page.");
        if (subscriptions != null) subscriptions.cancelAll(); }
    void onResume() { foreground = true; finishPermission(); if (recognition != null) recognition.onResume(); }
    @Override public void close() {
        closed = true;
        for (Job job : new ArrayList<>(jobs.values())) job.fail("CANCELLED", "Native operation page ended; earlier effects are not undone.");
        if (speech != null) { speech.close(); speech = null; speechOwner = null; }
        if (recognition != null) recognition.close();
        if (location != null) location.close();
        if (steps != null) steps.close();
        if (subscriptions != null) subscriptions.close();
        if (recording != null) recording.close();
        networkIo.shutdown();
    }
}
