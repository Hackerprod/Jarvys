package com.jarvys.agent.connectors;

import static com.jarvys.agent.connectors.ConnectionStateCoordinator.*;
import android.content.Context;
import android.os.Looper;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/** Staged owner only: no registry, UI, reader, runtime or Room wiring is enabled by this class. */
final class ConnectionPreferencesOwner {
    static final String PREFERENCES = "jarvys_connectors";
    private static final String PROCESS_IDENTITY = java.util.UUID.randomUUID().toString();
    static final class MetadataUnconfirmedException extends IllegalStateException {
        MetadataUnconfirmedException() { super("Connection metadata persistence remains unconfirmed"); }
    }
    static final class InitializationFailedException extends IllegalStateException {
        InitializationFailedException() { super("Connection bootstrap did not complete"); }
    }
    private static final ConcurrentHashMap<String, ConnectionPreferencesOwner> OWNERS = new ConcurrentHashMap<>();
    private final Object providerIdentity;
    private final ReentrantLock sharedFence;
    private final Runnable beforeInitialize;
    private final ConnectionStateCoordinator coordinator;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "JarvysConnectionBootstrap"); thread.setDaemon(true); return thread;
    });
    private CompletableFuture<BootstrapAttempt> inFlight;
    private BootstrapPlan plan;
    private volatile BootstrapAttempt lastAttempt;

    /** Acquisition canonicalizes filesDir and is worker-only; existing-handle reads/enqueue are nonblocking. */
    static ConnectionPreferencesOwner forContext(Context context, ReentrantLock sharedFence, Object providerIdentity) {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Connection owner acquisition requires a worker thread");
        Context app = Objects.requireNonNull(context).getApplicationContext();
        Objects.requireNonNull(sharedFence); Objects.requireNonNull(providerIdentity);
        final String root;
        try { root = app.getFilesDir().getCanonicalPath(); }
        catch (IOException failure) { throw new IllegalStateException("Connection storage identity is unavailable"); }
        String key = root + "\n" + PREFERENCES;
        ConnectionPreferencesOwner owner = OWNERS.computeIfAbsent(key, ignored -> new ConnectionPreferencesOwner(app,
                sharedFence, providerIdentity, () -> { }));
        if (owner.sharedFence != sharedFence || owner.providerIdentity != providerIdentity)
            throw new IllegalStateException("Connection projection provider identity does not match this owner");
        return owner;
    }

    /** Initialization hook is test-only: operational prefs bootstrap never waits for Room startup. */
    ConnectionPreferencesOwner(Context app, ReentrantLock sharedFence, Object providerIdentity, Runnable beforeInitialize) {
        this(sharedFence, providerIdentity, beforeInitialize, lazyBackend(app.getApplicationContext()));
    }
    /** Same-package fault injection, never a second production owner for a namespace. */
    ConnectionPreferencesOwner(ReentrantLock sharedFence, Object providerIdentity, Runnable beforeInitialize,
                               ConnectionPreferenceBackend backend) {
        this.providerIdentity = Objects.requireNonNull(providerIdentity);
        this.sharedFence = Objects.requireNonNull(sharedFence);
        this.beforeInitialize = Objects.requireNonNull(beforeInitialize);
        coordinator = new ConnectionStateCoordinator(new SharedPreferencesConnectionStorage(backend),
                System::currentTimeMillis, PROCESS_IDENTITY, sharedFence, providerIdentity);
    }
    private static ConnectionPreferenceBackend lazyBackend(Context application) {
        return new ConnectionPreferenceBackend() {
            private ConnectionPreferenceBackend delegate;
            private ConnectionPreferenceBackend get() {
                if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Connection storage requires a worker thread");
                if (delegate == null) delegate = new ConnectionPreferenceBackend.Android(
                        application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE));
                return delegate;
            }
            @Override public Map<String, ?> snapshot() { return get().snapshot(); }
            @Override public boolean commit(Map<String, Object> writes, Set<String> removals) { return get().commit(writes, removals); }
        };
    }
    ReadView snapshot() { return coordinator.read(); }
    RequestToken beginRequest(Command command) { return coordinator.beginIntent(command); }
    boolean isCurrent(RequestToken token) { return coordinator.isCurrent(token); }
    CompletableFuture<Result> persist(RequestToken token) { return local(() -> coordinator.persistSelection(token)); }
    CompletableFuture<Result> activationReturned(ActivationPermit permit) { return local(() -> coordinator.activationReturned(permit)); }
    CompletableFuture<Result> activationFailed(ActivationPermit permit) { return local(() -> coordinator.activationFailed(permit)); }
    CompletableFuture<Result> activationNotStarted(ActivationPermit permit) { return local(() -> coordinator.activationNotStarted(permit)); }
    CompletableFuture<AbortResult> abortBeforeSelection(RequestToken token) { return local(() -> coordinator.abortBeforePersistence(token)); }
    CompletableFuture<OffRestoration> restoreOffAfterPermissionAbort(RequestToken off, RequestToken on) {
        return local(() -> coordinator.restoreOffAfterPermissionAbort(off, on));
    }
    private <T> CompletableFuture<T> local(java.util.function.Supplier<T> operation) {
        CompletableFuture<T> owned = new CompletableFuture<>();
        worker.execute(() -> {
            try { owned.complete(operation.get()); }
            catch (Exception failure) { owned.completeExceptionally(new IllegalStateException("Connection operation did not complete")); }
        });
        return owned.thenApply(value -> value);
    }
    /** Projector-only, off-main, and deliberately not queued behind local bootstrap/mutations. */
    DestinationBinding bindDestination(DestinationEvidence evidence) {
        requireProjectionWorker(); return coordinator.bindDestination(evidence);
    }
    void invalidateDestination() { requireProjectionWorker(); coordinator.invalidateDestination(); }
    ProjectionBatch prepareProjection(DestinationBinding expectedBinding) {
        requireProjectionWorker();
        if (expectedBinding == null || coordinator.read().binding != expectedBinding)
            throw new IllegalStateException("Connection projection binding is no longer current");
        ProjectionBatch batch = coordinator.prepareProjection(expectedBinding.generation());
        if (batch.binding != expectedBinding)
            throw new IllegalStateException("Connection projection binding changed during preparation");
        return batch;
    }
    Outcome ackProjection(ProjectionBatch batch, Ack acknowledgment) {
        requireProjectionWorker(); return coordinator.ackProjection(batch, acknowledgment);
    }
    private static void requireProjectionWorker() {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Connection projection binding requires a worker thread");
    }

    /** Cancelling one dependent observer never cancels the process-owned bootstrap or another observer. */
    synchronized CompletableFuture<BootstrapAttempt> initializeAsync() {
        if (lastAttempt != null && lastAttempt.status == BootstrapStatus.READY)
            return CompletableFuture.completedFuture(lastAttempt);
        if (inFlight != null && !inFlight.isDone()) return inFlight.thenApply(value -> value);
        CompletableFuture<BootstrapAttempt> task = new CompletableFuture<>(); inFlight = task;
        worker.execute(() -> {
            try {
                beforeInitialize.run(); // Test hook; production does not await Room or conversation reset.
                if (plan == null) plan = coordinator.prepareBootstrap();
                BootstrapAttempt result = coordinator.applyBootstrap(plan);
                lastAttempt = result;
                task.complete(result);
            } catch (StorageUnconfirmedException pending) {
                task.completeExceptionally(new MetadataUnconfirmedException());
            } catch (Exception failure) {
                task.completeExceptionally(new InitializationFailedException());
            }
        });
        return task.thenApply(value -> value);
    }
}
