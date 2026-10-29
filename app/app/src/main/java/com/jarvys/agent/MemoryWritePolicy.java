package com.jarvys.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-local owner of the automatic-memory gate for one canonical application files root. */
public final class MemoryWritePolicy {
    private static final ConcurrentHashMap<String, MemoryWritePolicy> POLICIES = new ConcurrentHashMap<>();
    private final Context app;
    private final Runnable awaitReady;
    private final MemoryWriteGate gate;
    private final ExecutorService requests = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "JarvysMemorySettings");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean readinessFailed;
    private volatile boolean settingUnconfirmed;
    private volatile boolean captureSettingUnconfirmed;

    public interface AfterAttempt {
        /** Fixed runtime follow-up, outside the gate and on the ordered worker; no Activity capture. */
        void run(boolean effectiveEnabled);
    }

    public static final class SettingResult {
        /** Confirmation of preference state, not a promise that maintenance scheduling succeeded. */
        public final boolean confirmed;
        public final boolean effectiveEnabled;
        public final boolean followUpSucceeded;
        private SettingResult(boolean confirmed, boolean effectiveEnabled, boolean followUpSucceeded) {
            this.confirmed = confirmed;
            this.effectiveEnabled = effectiveEnabled;
            this.followUpSucceeded = followUpSucceeded;
        }
    }

    public static MemoryWritePolicy forContext(Context context) {
        Context app = Objects.requireNonNull(context).getApplicationContext();
        final String key;
        try { key = app.getFilesDir().getCanonicalPath(); }
        catch (IOException failure) { throw new IllegalStateException("Memory storage identity is unavailable"); }
        return POLICIES.computeIfAbsent(key, ignored -> new MemoryWritePolicy(app,
            () -> FreshStorageBootstrap.awaitReadyBlocking(app.getFilesDir())));
    }

    /** Package-private readiness injection for Android adapter tests; production uses forContext. */
    MemoryWritePolicy(Context app, Runnable awaitReady) {
        this.app = app.getApplicationContext();
        this.awaitReady = Objects.requireNonNull(awaitReady);
        SharedPreferences preferences = this.app.getSharedPreferences(MemoryConstants.PREFERENCES_FILE, Context.MODE_PRIVATE);
        this.gate = new MemoryWriteGate(new MemoryWriteGate.Settings() {
            @Override public boolean readEnabled() {
                if (readinessFailed) return false;
                try { return preferences.getBoolean(MemoryConstants.ENABLED_KEY, true); }
                catch (RuntimeException failure) { settingUnconfirmed = true; return false; }
            }
            @Override public boolean readPromptCaptureEnabled() {
                try { return !readinessFailed && !captureSettingUnconfirmed && preferences.getBoolean(MemoryConstants.PROMPT_CAPTURE_ENABLED_KEY, false); }
                catch (RuntimeException failure) { captureSettingUnconfirmed = true; return false; }
            }
            @Override public boolean commitPromptCaptureEnabled(boolean enabled) {
                requireWorker();
                return preferences.edit().putBoolean(MemoryConstants.PROMPT_CAPTURE_ENABLED_KEY, enabled).commit();
            }
            @Override public boolean commitEnabled(boolean enabled) {
                requireWorker();
                return preferences.edit().putBoolean(MemoryConstants.ENABLED_KEY, enabled).commit();
            }
        });
    }

    public MemoryWriteGate gate() { return gate; }
    public MemoryWriteGate.Ticket captureTicket() {
        if (!FreshStorageBootstrap.isReady(app) || readinessFailed)
            throw new IllegalStateException("Memory storage is not ready");
        return gate.capture();
    }
    public boolean isEnabled() {
        return !readinessFailed && FreshStorageBootstrap.isReady(app) && gate.isEnabled();
    }

    public boolean isPromptCaptureEnabled() {
        return !readinessFailed && FreshStorageBootstrap.isReady(app) && gate.isPromptCaptureEnabled();
    }
    public MemoryWriteGate.PromptCaptureTicket capturePromptTicket(MemoryWriteGate.Ticket originalMemoryTicket) {
        if (!FreshStorageBootstrap.isReady(app) || readinessFailed) throw new IllegalStateException("Memory storage is not ready");
        return gate.capturePromptTicket(originalMemoryTicket);
    }
    public CompletableFuture<SettingResult> requestSetPromptCaptureEnabled(boolean enabled) {
        CompletableFuture<SettingResult> answer = new CompletableFuture<>();
        requests.execute(() -> {
            boolean confirmed = true;
            try { awaitStorage(); gate.setPromptCaptureEnabled(enabled); captureSettingUnconfirmed = false; }
            catch (RuntimeException failure) { confirmed = false; captureSettingUnconfirmed = true; }
            boolean effective = isPromptCaptureEnabled();
            answer.complete(new SettingResult(confirmed && !captureSettingUnconfirmed, effective, true));
        });
        return answer;
    }
    public CompletableFuture<SettingResult> readPromptCaptureStateAsync() {
        CompletableFuture<SettingResult> answer = new CompletableFuture<>();
        requests.execute(() -> {
            boolean confirmed = true;
            try { awaitStorage(); } catch (RuntimeException failure) { confirmed = false; }
            boolean effective = isPromptCaptureEnabled();
            answer.complete(new SettingResult(confirmed && !captureSettingUnconfirmed, effective, true));
        });
        return answer;
    }

    /** No gate/registry lock is held while startup readiness is awaited. */
    public void setEnabledBlocking(boolean enabled) {
        requireWorker();
        try {
            awaitStorage();
            gate.setEnabled(enabled);
            settingUnconfirmed = false;
        } catch (RuntimeException failure) {
            settingUnconfirmed = true;
            throw failure;
        }
    }

    /** Owned by the process, not by a UI lifecycle. Cancelling an await does not cancel this request. */
    public CompletableFuture<SettingResult> requestSetEnabled(boolean enabled, AfterAttempt afterAttempt) {
        CompletableFuture<SettingResult> answer = new CompletableFuture<>();
        requests.execute(() -> {
            boolean confirmed = true;
            try { setEnabledBlocking(enabled); }
            catch (RuntimeException failure) { confirmed = false; }
            boolean effective = isEnabled();
            boolean followUp = true;
            try { if (afterAttempt != null) afterAttempt.run(effective); }
            catch (RuntimeException failure) { followUp = false; }
            answer.complete(new SettingResult(confirmed, effective, followUp));
        });
        return answer;
    }

    /** A recreated/resumed UI reads behind all requests already queued for the same root. */
    public CompletableFuture<SettingResult> readStateAsync() { return readStateAsync(null); }

    public CompletableFuture<SettingResult> readStateAsync(AfterAttempt afterAttempt) {
        CompletableFuture<SettingResult> answer = new CompletableFuture<>();
        requests.execute(() -> {
            boolean ready = true;
            try { awaitStorage(); }
            catch (RuntimeException failure) { ready = false; settingUnconfirmed = true; }
            boolean effective = isEnabled();
            boolean followUp = true;
            try { if (afterAttempt != null) afterAttempt.run(effective); }
            catch (RuntimeException failure) { followUp = false; }
            answer.complete(new SettingResult(ready && !settingUnconfirmed, effective, followUp));
        });
        return answer;
    }

    private void awaitStorage() {
        if (readinessFailed) throw new IllegalStateException("Memory storage startup failed");
        try { awaitReady.run(); }
        catch (RuntimeException failure) {
            // Startup failure cannot recover in this process; never revive an older ticket on retry.
            readinessFailed = true;
            throw new IllegalStateException("Memory storage startup failed");
        }
    }
    private static void requireWorker() {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Memory preference persistence must run off the main thread");
    }
}
