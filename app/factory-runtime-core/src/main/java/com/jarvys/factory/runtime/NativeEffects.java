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
    private final NativeOperationSessions sessions;
    private final Executor io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor networkIo;
    private final NativeNetwork network;
    private final Supplier<DocumentHandles> documents;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private static final java.util.concurrent.ScheduledThreadPoolExecutor HTTP_WATCH = httpWatch();
    private static final ThreadPoolExecutor HTTP_CLEANUP = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(32), task -> { Thread thread = new Thread(task, "factory-http-job-close"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());
    static { HTTP_CLEANUP.allowCoreThreadTimeOut(true); }
    private static java.util.concurrent.ScheduledThreadPoolExecutor httpWatch() {
        java.util.concurrent.ScheduledThreadPoolExecutor executor = new java.util.concurrent.ScheduledThreadPoolExecutor(1,
                task -> { Thread thread = new Thread(task, "factory-http-job-lifetime"); thread.setDaemon(true); return thread; });
        executor.setRemoveOnCancelPolicy(true); return executor;
    }
    private NativeSpeech speech;
    private NativeVoiceRecognition recognition;
    private NativeLocation location;
    private NativeSteps steps;
    private NativeNfc nfc;
    private NativeBleEffects bluetooth;
    private SensorSubscriptions subscriptions;
    private final Map<String, Job> subscriptionJobs = new ConcurrentHashMap<>();
    private RecordingEffects recording;
    private SecretEffects secrets;
    private NotificationEffects notificationEffects;
    private Job exactAlarmJob;
    private NotificationEffects.ExactAccess exactAlarmAccess;
    private boolean exactAlarmPaused;
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
        volatile NativeOperationSessions.Lease lease;
        volatile Runnable cancellation;
        volatile NotificationScheduleEngine.Session notificationSession;
        volatile HttpJobEngine.Session httpSession;
        volatile String httpJobId;
        volatile java.util.concurrent.ScheduledFuture<?> httpTimeout;
        void endHttpSession() {
            if (httpSession != null) httpSession.stop();
            java.util.concurrent.ScheduledFuture<?> timer = httpTimeout; if (timer != null) timer.cancel(false);
        }
        volatile boolean ownData;
        Job(String id, String method, Reply reply) { this.id = id; this.method = method; this.reply = reply; }
        boolean current() { return !closed && !done.get() && (retained ? reply.pageCurrent() : reply.current()) && jobs.get(key) == this; }

        void checkAuthority() {
            if (!foreground || !current()) throw new SecurityException("Native page ownership ended.");
            if (notificationSession != null) notificationSession.check();
            if (httpSession != null) httpSession.check();
            if (!ownData) {
                if (lease == null) throw new SecurityException("Native operation has no live host session.");
                lease.check();
            }
        }
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
                    checkAuthority();
                    if (!done.compareAndSet(false, true)) return;
                    jobs.remove(key, this);
                    if (notificationSession != null) notificationSession.invalidate();
                    endHttpSession();
                    if (lease != null) lease.close();
                    if (speechOwner == this) speechOwner = null;
                    reply.ok(result);
                } catch (Exception failure) { fail(failure); }
            });
        }
        /** Secret results have no post-delivery resource owner; retire even a stale queued reply. */
        void completeSecret(Object result) {
            Runnable delivery = () -> {
                try {
                    if (!current() || !foreground || lease == null) throw new SecurityException();
                    lease.check();
                    if (!current() || !foreground) throw new SecurityException();
                    if (!done.compareAndSet(false, true)) return;
                    jobs.remove(key, this);
                    cancellation = null;
                    lease.close();
                    reply.ok(result);
                } catch (Exception failure) { fail(failure); }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) delivery.run();
            else if (!main.post(delivery)) fail("CANCELLED", "Secret response worker ended; earlier effects are not undone.");
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
            if (notificationSession != null) notificationSession.invalidate();
            endHttpSession();
            Runnable action = cancellation; if (action != null) try { action.run(); } catch (RuntimeException ignored) { }
            if (lease != null) lease.close();
            main.post(() -> { if ((permissionJob != this || permissionReturned) && !(recognitionUiJob == this && hasPendingActivity()) && exactAlarmJob != this) reply.releaseUi(); reply.fail(code, message); });
        }
        void fail(Exception error) {
            if (error instanceof FactoryException) fail(((FactoryException) error).code, error.getMessage());
            else if (error instanceof SecurityException) fail("PERMISSION_DENIED", "Native operation permission ended; earlier effects are not undone.");
            else fail("NATIVE_ERROR", "Native operation could not be completed.");
        }
        void worker(Executor executor, Work work) {
            try { executor.execute(() -> { try { if (!current()) return; checkAuthority(); work.run(this); } catch (Exception failure) { fail(failure); } }); }
            catch (RuntimeException busy) { fail("BUSY", "Native operation worker is busy."); }
        }
    }
    NativeEffects(Activity activity, FactoryConfig config, NativeOperationSessions sessions, Executor io, boolean foreground) {
        this(activity, config, sessions, io, foreground, (Supplier<DocumentHandles>) null);
    }
    NativeEffects(Activity activity, FactoryConfig config, NativeOperationSessions sessions, Executor io, boolean foreground,
                  Supplier<DocumentHandles> documents) {
        this(activity, config, sessions, io, foreground,
                new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16),
                        task -> { Thread thread = new Thread(task, "factory-network"); thread.setDaemon(true); return thread; },
                        new ThreadPoolExecutor.AbortPolicy()), new NativeNetwork(), null, documents);
    }
    NativeEffects(Activity activity, FactoryConfig config, NativeOperationSessions sessions, Executor io, boolean foreground,
                  ThreadPoolExecutor networkIo, NativeNetwork network, NativeSpeech speech) {
        this(activity, config, sessions, io, foreground, networkIo, network, speech, null);
    }
    NativeEffects(Activity activity, FactoryConfig config, NativeOperationSessions sessions, Executor io, boolean foreground,
                  ThreadPoolExecutor networkIo, NativeNetwork network, NativeSpeech speech, Supplier<DocumentHandles> documents) {
        this.activity = activity; this.config = config; this.sessions = sessions; this.io = io; this.foreground = foreground;
        this.networkIo = networkIo; this.network = network; this.speech = speech;
        this.documents = documents == null ? () -> null : documents;
        networkIo.allowCoreThreadTimeOut(true);
    }
    void perform(BridgeProtocol.Request request, Reply reply) throws Exception {
        boolean nfcCleanup = request.operation == com.jarvys.factory.contract.CapabilityCatalog.Method.NFC_CANCEL;
        if (closed || (nfcCleanup ? !reply.pageCurrent() : !foreground || !reply.current()))
            throw new FactoryException("UNAVAILABLE", "Native operations require the foreground application; cleanup still requires the current page.");
        switch (request.operation) {
            case BLUETOOTH_STATUS: case BLUETOOTH_REQUEST_PERMISSIONS: case BLUETOOTH_SCAN:
            case BLUETOOTH_POLL: case BLUETOOTH_STOP_SCAN: case BLUETOOTH_CONNECT:
            case BLUETOOTH_SERVICES: case BLUETOOTH_READ: case BLUETOOTH_WRITE:
            case BLUETOOTH_REQUEST_MTU: case BLUETOOTH_SUBSCRIBE: case BLUETOOTH_UNSUBSCRIBE:
            case BLUETOOTH_DISCONNECT: case BLUETOOTH_CANCEL:
                bluetooth().perform(request.method, request.args, reply); return;
            case JOBS_ENQUEUE_HTTP: case JOBS_STATUS: case JOBS_CANCEL:
            case JOBS_RESUME: case JOBS_READ_RESULT: case JOBS_FORGET:
                performHttpJob(request, reply); return;
            case WIDGETS_AVAILABILITY: case WIDGETS_LIST: case WIDGETS_READ:
            case WIDGETS_REQUEST_PIN: case WIDGETS_UPDATE: case WIDGETS_REMOVE:
            case WIDGETS_POLL_ACTIONS: case WIDGETS_ACKNOWLEDGE_ACTION:
                performWidgets(request, reply); return;
            case NFC_STATUS:
                NfcRequest.empty(request.args); reply.ok(nfc().status()); return;
            case NFC_CANCEL:
                NfcRequest.empty(request.args); cancelNfc(reply); return;
            case NFC_READ: case NFC_WRITE:
                NfcRequest.Operation nfcOperation = request.operation == com.jarvys.factory.contract.CapabilityCatalog.Method.NFC_WRITE
                        ? NfcRequest.write(request.args, nfcLimits()) : NfcRequest.read(request.args, nfcLimits());
                for (Job pending : new ArrayList<>(jobs.values()))
                    if (isNfcOperation(pending.method) && !pending.done.get())
                        throw new FactoryException("BUSY", "This page already owns a pending or active NFC operation.");
                authorize(request, reply, nfcOperation.authorizationDetails(), job -> {
                    NfcSessionMachine.Handle handle = nfc().execute(nfcOperation, job::checkAuthority, new NfcSessionMachine.Callback() {
                        public void complete(JSONObject result) { job.complete(result); }
                        public void error(FactoryException failure) { job.fail(failure); }
                    });
                    job.cancellation = handle::cancel;
                    job.lease.onCancelled(handle::cancel);
                    if (!job.current() || !foreground) handle.cancel();
                }); return;
            case SECRETS_PUT: case SECRETS_CAPTURE: case SECRETS_GET: case SECRETS_LIST:
            case SECRETS_DESCRIBE: case SECRETS_REMOVE: case SECRETS_RESET: case SECRETS_REQUEST:
                SecretEffects.Prepared prepared = SecretEffects.prepare(request.method, request.args);
                try {
                    // Own the native put bytes before caller authentication can time out or lose its page.
                    // The same immutable parse and single lease bind storage and native HTTP injection.
                    authorize(request, reply, prepared.authorizationDetails(), prepared::close, job -> {
                        SecretEffects.Handle handle = secrets().execute(prepared, job.lease,
                                () -> foreground && !closed && job.current(), new SecretEffects.Callback() {
                            public boolean current() { return job.current(); }
                            public boolean claimUi() { return foreground && job.current() && job.reply.claimUi(); }
                            public void releaseUi() { job.reply.releaseUi(); }
                            public void complete(Object result) { job.completeSecret(result); }
                            public void error(FactoryException failure) { job.fail(failure); }
                        });
                        job.cancellation = handle::cancel;
                        job.lease.onCancelled(handle::cancel);
                        if (!job.current() || !foreground) handle.cancel();
                    });
                } catch (Exception failure) { prepared.close(); throw failure; }
                return;
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
                if (config.documentBroker == null) throw new FactoryException("UNAVAILABLE", "Sensor subscriptions require a generated app with an authenticated host; legacy finite capture remains available.");
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
                reply.ok(notificationEffects().status(NotificationRequest.parseStatus(request.args.toString()))); return;
            case SPEECH_STATUS: reply.ok(speech().status()); return;
            case NETWORK_UPLOAD: case NETWORK_DOWNLOAD:
                NetworkTransferRequest transfer = request.operation == com.jarvys.factory.contract.CapabilityCatalog.Method.NETWORK_UPLOAD
                        ? NetworkTransferRequest.upload(request.args, config.resources) : NetworkTransferRequest.download(request.args, config.resources);
                final DocumentHandles transferDocuments = documents.get();
                if (transferDocuments == null) throw new FactoryException("UNAVAILABLE", "The current page document registry is unavailable.");
                authorize(request, reply, transfer.authorizationDetails(), job -> {
                    NativeNetwork.Cancellation cancellation = new NativeNetwork.Cancellation(); job.cancellation = cancellation::cancel;
                    job.lease.onCancelled(cancellation::cancel);
                    job.worker(networkIo, value -> value.complete(NativeEffects.this.network.executeTransfer(transfer, transferDocuments, value.lease, cancellation)));
                }); return;
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
                NotificationScheduleRequest.Notification notification = NotificationScheduleRequest.parseNotification(request.args);
                authorize(request, reply, notification.details(), job -> notificationWorker(job,
                        value -> value.complete(notificationEffects().show(notification, value.lease, value.notificationSession)))); return;
            case NOTIFICATIONS_CANCEL:
                int id = NotificationRequest.parseCancel(request.args);
                ownNotificationWork(request, reply, job -> job.complete(notificationEffects().cancelVisible(id, job.notificationSession))); return;
            case NOTIFICATIONS_SCHEDULE:
                NotificationScheduleRequest.Schedule scheduled = NotificationScheduleRequest.parseSchedule(request.args);
                authorize(request, reply, scheduled.authorizationDetails(), job -> notificationWorker(job,
                        value -> value.complete(notificationEffects().schedule(scheduled, null, value.lease, value.notificationSession)))); return;
            case NOTIFICATIONS_RESUME_SCHEDULED:
                String resumedId = NotificationScheduleRequest.parseScheduleId(request.args.toString());
                ownNotificationWork(request, reply, job -> {
                    NotificationScheduleEngine.Row original = notificationEffects().storedSchedule(resumedId, job.notificationSession);
                    if (original == null) { job.complete(new JSONObject().put("status", "not_found").put("scheduleId", resumedId)); return; }
                    main.post(() -> {
                        try {
                            job.checkAuthority();
                            // Resume discloses and reauthorizes the complete immutable original schedule.
                            // A separate resume allow must never bypass notifications.schedule denial.
                            job.ownData = false;
                            authorize(job, "notifications.schedule", original.request.authorizationDetails(), activeJob -> notificationWorker(activeJob,
                                    value -> value.complete(notificationEffects().schedule(original.request, original, value.lease, value.notificationSession))));
                        } catch (Exception failure) { job.fail(failure); }
                    });
                }); return;
            case NOTIFICATIONS_SCHEDULE_STATUS:
                String statusId = NotificationScheduleRequest.parseScheduleId(request.args.toString());
                ownNotificationWork(request, reply, job -> job.complete(notificationEffects().scheduleStatus(statusId, job.notificationSession))); return;
            case NOTIFICATIONS_CANCEL_SCHEDULED:
                String cancelledId = NotificationScheduleRequest.parseScheduleId(request.args.toString());
                ownNotificationWork(request, reply, job -> job.complete(notificationEffects().cancelScheduled(cancelledId, job.notificationSession))); return;
            case NOTIFICATIONS_FORGET_SCHEDULED:
                NotificationScheduleControl.Forget forgotten = NotificationScheduleControl.parseForget(request.args.toString());
                ownNotificationWork(request, reply, job -> job.complete(notificationEffects().forgetScheduled(forgotten, job.notificationSession))); return;
            case NOTIFICATIONS_GENERATION_STATUS:
                String generationId = NotificationScheduleControl.parseGenerationStatus(request.args.toString());
                ownNotificationWork(request, reply, job -> job.complete(notificationEffects().generationStatus(generationId, job.notificationSession))); return;
            case NOTIFICATIONS_FORGET_GENERATION:
                NotificationScheduleControl.Generation generation = NotificationScheduleControl.parseForgetGeneration(request.args.toString());
                ownNotificationWork(request, reply, job -> job.complete(notificationEffects().forgetGeneration(generation, job.notificationSession))); return;
            case NOTIFICATIONS_POLL_ACTIONS:
                NotificationScheduleRequest.parsePollActions(request.args.toString());
                ownNotificationWork(request, reply, job -> job.complete(notificationEffects().pollActions(job.notificationSession))); return;
            case NOTIFICATIONS_ACK_ACTION:
                String eventId = NotificationScheduleRequest.parseEventAck(request.args.toString());
                ownNotificationWork(request, reply, job -> job.complete(notificationEffects().acknowledgeAction(eventId, job.notificationSession))); return;
            case NOTIFICATIONS_REQUEST_EXACT_ALARM_ACCESS:
                NotificationScheduleRequest.parsePollActions(request.args.toString());
                authorize(request, reply, new JSONObject(), job -> notificationWorker(job, value -> {
                    NotificationEffects.ExactAccess access = notificationEffects().exactAccess(value.notificationSession);
                    main.post(() -> requestExactAlarmAccess(value, access));
                })); return;
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
    /** An inert inbox operation owns no remote session; resume uses only its durable stored payload. */
    private void performHttpJob(BridgeProtocol.Request request, Reply reply) throws Exception {
        NativeHttpJobs.validate(request.method, request.args);
        if (!config.capabilities.contains("jobs") || !config.capabilities.contains("network"))
            throw new FactoryException("CAPABILITY_REQUIRED", "Finite HTTP jobs require jobs and network capabilities.");
        Job job = new Job(request.id, request.method, reply); job.ownData = true;
        HttpJobEngine.Session session = new HttpJobEngine.Session(FactoryHttpJobServices.CLOCK, HttpJobEngine.Session.MAX_LIFETIME_MS);
        job.httpSession = session; job.httpJobId = request.args.getString("jobId");
        job.cancellation = () -> {
            session.stop();
            try { HTTP_CLEANUP.execute(session.cancellation::cancel); }
            catch (java.util.concurrent.RejectedExecutionException busy) { /* Checks are fenced; NativeNetwork's independent deadline remains bounded. */ }
        };
        jobs.put(job.key, job);
        job.httpTimeout = HTTP_WATCH.schedule(() -> job.fail("TIMEOUT", "Finite job page session ended; a committed HTTP effect may already have occurred."),
                HttpJobEngine.Session.MAX_LIFETIME_MS, TimeUnit.MILLISECONDS);
        // Host session IPC uses io. Never wait for that IPC while occupying the same executor.
        job.worker(networkIo, current -> {
            JSONObject result = NativeHttpJobs.execute(activity, config, request.method, request.args,
                    (row, source, mode, active) -> acquireHttpJobAuthority(current, row, source, mode, active), session);
            if ("jobs.cancel".equals(request.method)) {
                for (Job pending : new ArrayList<>(jobs.values()))
                    if (pending != current && "jobs.resume".equals(pending.method) && current.httpJobId.equals(pending.httpJobId))
                        pending.fail("CANCELLED", "This HTTP job was cancelled; an external effect may already have occurred.");
            }
            current.complete(result);
        });
    }
    /** Bounded cancellable worker-to-main rendezvous; the live source session owns its authenticated host lease. */
    private HttpJobEngine.Decision acquireHttpJobAuthority(Job job, HttpJobEngine.Row row, HttpJobEngine.Source source,
                                                          HttpJobEngine.Mode mode, HttpJobEngine.Session session) throws FactoryException {
        if (mode == HttpJobEngine.Mode.BACKGROUND_NO_UI || !source.owner.equals(row.source.owner))
            throw new FactoryException("PERMISSION_DENIED", "Foreground job authority does not match the stored owner.");
        session.check();
        final JSONObject details = row.request.authorizationDetails("foreground_now", mode == HttpJobEngine.Mode.FOREGROUND_REPLAY);
        final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<NativeOperationSessions.Lease> grant = new java.util.concurrent.atomic.AtomicReference<>();
        final AtomicBoolean awaiting = new AtomicBoolean(true);
        if (!main.post(() -> {
            if (!awaiting.get() || !session.active() || !job.current()) { ready.countDown(); return; }
            try {
                job.checkAuthority(); job.ownData = false;
                authorize(job, "jobs.enqueueHttp", details, approved -> {
                    NativeOperationSessions.Lease lease = approved.lease;
                    if (!awaiting.get() || !session.active() || !approved.current()) {
                        lease.close(); ready.countDown();
                        approved.fail("CANCELLED", "Finite job session outlived its page session."); return;
                    }
                    lease.onCancelled(approved.cancellation); grant.set(lease); ready.countDown();
                });
            } catch (Exception failure) { job.fail(failure); ready.countDown(); }
        })) throw new FactoryException("CANCELLED", "Foreground job session could not reach the UI thread.");
        try {
            while (!ready.await(50, TimeUnit.MILLISECONDS)) {
                session.check();
                // Host session acquisition may temporarily pause the application. No HTTP runs here.
                if (!job.current()) throw new FactoryException("CANCELLED", "Foreground job session ended.");
            }
            session.check();
            NativeOperationSessions.Lease lease = grant.get();
            if (lease == null || !job.current()) throw new FactoryException("PERMISSION_DENIED", "No current foreground job session was received.");
            job.checkAuthority(); lease.check();
            return new HttpJobEngine.Decision(HttpJobEngine.SessionStatus.ACTIVE, new HttpJobEngine.Lease() {
                private final AtomicBoolean released = new AtomicBoolean();
                public void check() {
                    if (released.get()) throw new SecurityException("Borrowed job session ended");
                    session.check(); job.checkAuthority(); lease.check(); session.check();
                }
                public void close() {
                    // The Job retains the real live lease through final result delivery and owns cleanup.
                    released.set(true);
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new FactoryException("CANCELLED", "Foreground job session worker was interrupted.");
        } finally { awaiting.set(false); }
    }
    /** Widget management uses one operation session even though the public API has three effect methods. */
    private void performWidgets(BridgeProtocol.Request request, Reply reply) throws Exception {
        WidgetModel.Limits limits = WidgetModel.Limits.fromJson(ResourceBudgetsJson.toJson(config.resources).getJSONObject("widgets"));
        NativeWidgetEffects.Prepared prepared = NativeWidgetEffects.prepare(request.method, request.args, limits);
        Job job = new Job(request.id, request.method, reply);
        job.ownData = !prepared.requiresSession();
        jobs.put(job.key, job);
        Work execute = authorized -> authorized.worker(io, current -> {
            long deadline = android.os.SystemClock.elapsedRealtime() + 8000L;
            Object result = prepared.execute(activity, deadline, () -> foreground && !closed && current.current(),
                    prepared.requiresSession() ? current::checkAuthority : null);
            current.complete(result);
        });
        if (prepared.requiresSession()) authorize(job, prepared.operation(), prepared.authorizationDetails(), execute);
        else execute.run(job);
    }
    private NfcRequest.Limits nfcLimits() {
        return new NfcRequest.Limits(config.resources.nfc.messageBytes, config.resources.nfc.records, config.resources.nfc.timeoutMs);
    }
    private NativeNfc nfc() {
        if (nfc == null) nfc = new NativeNfc(activity, config.appId, () -> foreground && !closed, nfcLimits());
        return nfc;
    }
    private static boolean isNfcOperation(String method) { return "nfc.read".equals(method) || "nfc.write".equals(method); }
    private void cancelNfc(Reply reply) throws Exception {
        ArrayList<Job> owned = new ArrayList<>();
        for (Job job : new ArrayList<>(jobs.values())) if (isNfcOperation(job.method) && !job.done.get()) owned.add(job);
        JSONObject result = nfc().cancel();
        // Closing operation sessions on cancel prevents a stale callback from starting RF.
        for (Job job : owned) job.fail("CANCELLED", "NFC operation cancelled; a write already started may have changed the tag.");
        result.put("cancelledRequests", owned.size());
        if (!owned.isEmpty()) result.put("cancelled", true);
        reply.ok(result);
    }
    private void ownSpeech(Job job, NativeSpeech owner) {
        speechOwner = job;
        Runnable close = () -> main.post(() -> {
            if (speech == owner && speechOwner == job) { owner.close(); speech = null; speechOwner = null; }
        });
        job.cancellation = close; job.lease.onCancelled(close);
    }
    private NativeNotifications notifications() { return new NativeNotifications(activity, config.appId); }
    private NotificationEffects notificationEffects() {
        if (notificationEffects == null) notificationEffects = new NotificationEffects(activity, config);
        return notificationEffects;
    }
    private void notificationWorker(Job job, Work work) {
        if (job.notificationSession == null) job.notificationSession = new NotificationScheduleEngine.Session(FactoryNotificationServices.CLOCK, 300_000L);
        job.worker(io, work);
    }
    private void ownNotificationWork(BridgeProtocol.Request request, Reply reply, Work work) {
        Job job = new Job(request.id, request.method, reply); job.ownData = true;
        jobs.put(job.key, job); notificationWorker(job, work);
    }
    private NativeVoiceRecognition recognition() { if (recognition == null) recognition = new NativeVoiceRecognition(activity, config.appId, foreground); return recognition; }
    private NativeSteps steps() { if (steps == null) steps = new NativeSteps(activity, config.appId, () -> foreground && !closed); return steps; }
    private SensorSubscriptions subscriptions() { if (subscriptions == null) subscriptions = SensorSubscriptions.installed(activity, main, config.resources.sensors); return subscriptions; }
    private NativeLocation location() { if (location == null) location = new NativeLocation(activity, config.appId, () -> foreground && !closed); return location; }
    private NativeSpeech speech() { if (speech == null) speech = new NativeSpeech(activity, config.appId); return speech; }
    private RecordingEffects recording() {
        if (recording == null) recording = new RecordingEffects(new NativeAudioRecording(activity, config.appId), documents, config.resources);
        return recording;
    }
    /** Invoked only from a current authenticated Job; constructing the adapter still does not open the vault. */
    private SecretEffects secrets() {
        if (secrets == null) secrets = new SecretEffects(activity, new NativeSecrets.Limits(
                config.resources.secrets.entries, config.resources.secrets.valueBytes, config.resources.secrets.vaultBytes), network);
        return secrets;
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
        authorize(request, reply, details, null, work);
    }
    private void authorize(BridgeProtocol.Request request, Reply reply, JSONObject details, Runnable initialCancellation, Work work) throws Exception {
        Job job = new Job(request.id, request.method, reply);
        job.cancellation = initialCancellation;
        jobs.put(job.key, job);
        authorize(job, request.method, details, work);
    }
    private void authorize(Job job, String operation, JSONObject details, Work work) throws Exception {
        try {
            job.lease = sessions.authorize(operation, details, new NativeOperationSessions.Callback() {
                public boolean current() { return job.current(); }
                public boolean claimUi() { return job.current() && job.reply.claimUi(); }
                public void releaseUi() { job.reply.releaseUi(); }
                public void ready(NativeOperationSessions.Lease lease) {
                    job.lease = lease;
                    if (!job.current()) { job.fail("CANCELLED", "Native operation page ended; earlier effects are not undone."); lease.close(); return; }
                    try { lease.check(); work.run(job); } catch (Exception failure) { job.fail(failure); }
                }
                public void failed(String code, String message) { job.fail(code, message); }
            });
            // An executor may reject authorization synchronously, before its returned lease is assigned.
            if (job.done.get()) job.lease.close();
        } catch (Exception unavailable) { job.fail(unavailable); }
    }
    private void requestExactAlarmAccess(Job job, NotificationEffects.ExactAccess access) {
        try {
            job.checkAuthority();
            if (exactAlarmJob != null || !job.reply.claimUi()) { job.fail("BUSY", "Another native settings request is already open."); return; }
            exactAlarmJob = job; exactAlarmAccess = access; exactAlarmPaused = false;
            job.lease.externalHandoff(true);
            JSONObject result = access.request(activity, job.lease);
            if (!result.optBoolean("exactAlarmAccessRequested")) {
                exactAlarmJob = null; exactAlarmAccess = null;
                job.lease.externalHandoff(false); job.reply.releaseUi(); job.complete(result);
            }
        } catch (Exception failure) {
            if (exactAlarmJob == job) { exactAlarmJob = null; exactAlarmAccess = null; exactAlarmPaused = false; job.reply.releaseUi(); }
            if (job.lease != null) job.lease.externalHandoff(false);
            job.fail(failure);
        }
    }
    private void finishExactAlarmAccess() {
        Job job = exactAlarmJob;
        if (job == null || !exactAlarmPaused || !foreground) return;
        NotificationEffects.ExactAccess access = exactAlarmAccess;
        exactAlarmJob = null; exactAlarmAccess = null; exactAlarmPaused = false;
        job.reply.releaseUi(); if (job.lease != null) job.lease.externalHandoff(false);
        if (!job.done.get()) notificationWorker(job, value -> value.complete(access.status()));
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
        if (bluetooth != null && bluetooth.onRequestPermissionsResult(request, names, grants)) return true;
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
    private NativeBleEffects bluetooth() throws FactoryException {
        if (bluetooth == null) {
            if (config.bluetooth == null) throw new FactoryException("INVALID_CONFIG", "Bluetooth requires its signed privacy declaration.");
            com.jarvys.factory.contract.ResourceBudgets.Bluetooth b = config.resources.bluetooth;
            BleCentral.Limits limits = new BleCentral.Limits(b.connections, b.discoveredDevices, b.queuedOperations,
                    b.bufferedEvents, b.pollEvents, b.services, b.attributes, b.subscriptions, b.valueBytes,
                    b.scanDurationMs, b.operationTimeoutMs);
            bluetooth = new NativeBleEffects(activity, sessions, limits,
                    config.bluetooth.neverForLocation() ? BlePermissions.ScanPrivacy.NEVER_FOR_LOCATION : BlePermissions.ScanPrivacy.FULL,
                    foreground);
        }
        return bluetooth;
    }
    boolean isClosed() { return closed; }
    boolean hasPendingPermission() { return permissionJob != null && !permissionReturned || bluetooth != null && bluetooth.hasPendingPermission(); }
    boolean hasPendingActivity() { return exactAlarmJob != null || (recognition != null && recognition.hasPendingActivity()); }
    boolean onActivityResult(int request, int result, Intent data) {
        if (request != NativeVoiceRecognition.REQUEST_CODE) return false;
        if (recognition != null) recognition.onActivityResult(request, result, data);
        return true;
    }
    void onWindowFocusChanged(boolean focused) { if (recognition != null) recognition.onWindowFocusChanged(focused); }
    void onPause() { foreground = false; if (bluetooth != null) bluetooth.onPause(); if (recognition != null) recognition.onPause(); if (location != null) location.cancel(); if (recording != null) recording.cancel(); if (steps != null) steps.cancel(); if (nfc != null) nfc.cancel();
        if (exactAlarmJob != null) exactAlarmPaused = true;
        for (Job job : new ArrayList<>(jobs.values())) if (job.method.startsWith("notifications.") && job != exactAlarmJob && job != permissionJob)
            job.fail("CANCELLED", "Notification page operation ended in background; committed schedules and prior effects are not undone.");
        for (Job job : new ArrayList<>(jobs.values()))
            if (isNfcOperation(job.method) || job.method.startsWith("widgets.") || job.method.startsWith("jobs."))
                job.fail("CANCELLED", "Native NFC/widget/job page operation ended in background; earlier effects are not undone.");
        // Secret operations, including input capture, end when the generated app leaves foreground.
        for (Job job : new ArrayList<>(jobs.values())) if (job.method.startsWith("secrets."))
            job.fail("CANCELLED", "Secret operation ended in background; earlier effects are not undone.");
        if (secrets != null) secrets.cancelAll();
        for (Job job : new ArrayList<>(subscriptionJobs.values())) job.fail("CANCELLED", "Sensor subscriptions require the foreground page.");
        if (subscriptions != null) subscriptions.cancelAll(); }
    void onResume() { foreground = true; if (bluetooth != null) bluetooth.onResume(); finishPermission(); finishExactAlarmAccess(); if (recognition != null) recognition.onResume(); }
    @Override public void close() {
        closed = true;
        for (Job job : new ArrayList<>(jobs.values())) job.fail("CANCELLED", "Native operation page ended; earlier effects are not undone.");
        if (speech != null) { speech.close(); speech = null; speechOwner = null; }
        if (recognition != null) recognition.close();
        if (location != null) location.close();
        if (steps != null) steps.close();
        if (nfc != null) nfc.close();
        if (bluetooth != null) bluetooth.close();
        if (subscriptions != null) subscriptions.close();
        if (recording != null) recording.close();
        if (secrets != null) secrets.close();
        networkIo.shutdown();
    }
}
