package com.jarvys.agent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** One process-local inference attempt. Never a tool, memory, tree, or run cancellation capability. */
final class ProviderAttemptLease implements AutoCloseable {
    enum Stage { PREPARING, INFERENCE, COMMITTING, SUPERSEDED, FINISHED }
    static final class YieldRequested extends RuntimeException {
        private YieldRequested() { super("Native Central inference yielded to admitted input"); }
    }
    private static final ThreadLocal<ProviderAttemptLease> CURRENT = new ThreadLocal<>();
    final String sessionId, runId, attemptId;
    final long generation;
    final CoreAgentLoop.Checkpoint checkpoint;
    private final Thread owner = Thread.currentThread();
    private final CancellationToken ownerToken;
    private final CancellationToken transport = CancellationToken.cancellable();
    private final ProviderAttemptLease previous;
    private final Runnable unregisterStop;
    private Stage stage = Stage.PREPARING;
    private boolean yieldRequested;
    private long signals;

    ProviderAttemptLease(String sessionId, String runId, String attemptId,
            CoreAgentLoop.Checkpoint checkpoint, CancellationToken ownerToken) {
        this.sessionId = Objects.requireNonNull(sessionId);
        this.runId = Objects.requireNonNull(runId);
        this.attemptId = Objects.requireNonNull(attemptId);
        this.checkpoint = Objects.requireNonNull(checkpoint);
        this.ownerToken = Objects.requireNonNull(ownerToken);
        generation = ownerToken.generation();
        ownerToken.throwIfCancelled();
        previous = CURRENT.get();
        if (previous != null) throw new IllegalStateException("Inference attempt cannot nest");
        CURRENT.set(this);
        unregisterStop = ownerToken.registerCancelAction(() -> {
            transport.cancelExactRunAsync(); signalTransportProgress();
        });
    }
    static ProviderAttemptLease current() { return CURRENT.get(); }
    synchronized Stage stage() { return stage; }
    CancellationToken transportToken() { return transport; }
    void requireOwnerCurrent() {
        if (Thread.currentThread() != owner || CURRENT.get() != this)
            throw new IllegalStateException("Provider preparation belongs to its native owner thread");
        requireCurrent();
    }
    void requireCurrent() {
        ownerToken.throwIfCancelled();
        synchronized (this) {
            if (stage == Stage.SUPERSEDED || yieldRequested && stage == Stage.PREPARING) throw new YieldRequested();
            if (stage == Stage.FINISHED) throw new IllegalStateException("Provider attempt already closed");
        }
    }
    /** Called only by the service's validated native input boundary; does not cancel the owner. */
    void requestYield() {
        boolean cancelTransport = false;
        synchronized (this) {
            if (stage == Stage.COMMITTING || stage == Stage.FINISHED) return;
            yieldRequested = true;
            if (stage == Stage.INFERENCE) { stage = Stage.SUPERSEDED; cancelTransport = true; }
            signals++; notifyAll();
        }
        if (cancelTransport) transport.cancelExactRunAsync();
    }
    void beginInference() {
        requireOwnerCurrent();
        synchronized (this) {
            if (yieldRequested) throw new YieldRequested();
            if (stage != Stage.PREPARING) throw new IllegalStateException("Provider inference already dispatched");
            stage = Stage.INFERENCE;
        }
    }
    /** Auth refresh, diagnostics and full response parsing resume on the owner, never on transport. */
    void transportReturned() {
        requireOwnerCurrent();
        synchronized (this) {
            if (stage != Stage.INFERENCE) throw new IllegalStateException("No current inference transport");
            stage = Stage.PREPARING;
        }
    }
    /** Native retry delay: PREPARING only, with exact input/STOP wake and no polling. */
    void awaitBackoff(long millis) {
        requireOwnerCurrent();
        // Saturating conversion preserves an uncapped Retry-After floor without overflow.
        long remaining = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0L, millis));
        long observedAt = System.nanoTime();
        synchronized (this) {
            while (true) {
                requireOwnerCurrent();
                if (stage != Stage.PREPARING)
                    throw new IllegalStateException("Provider backoff requires preparation boundary");
                long now = System.nanoTime();
                long elapsed = now - observedAt;
                if (elapsed > 0L) remaining = elapsed >= remaining ? 0L : remaining - elapsed;
                observedAt = now;
                if (remaining <= 0L) return;
                try {
                    long waitMillis = remaining / 1_000_000L;
                    int waitNanos = (int) (remaining % 1_000_000L);
                    wait(waitMillis, waitNanos);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    ownerToken.throwIfCancelled();
                    throw new java.util.concurrent.CancellationException("Provider backoff owner interrupted");
                }
            }
        }
    }
    boolean tryCommitReply() {
        requireOwnerCurrent();
        synchronized (this) {
            if (yieldRequested) throw new YieldRequested();
            if (stage != Stage.PREPARING) throw new IllegalStateException("Response is not ready to commit");
            stage = Stage.COMMITTING;
            return true;
        }
    }
    synchronized boolean acceptsDelta() {
        return stage == Stage.INFERENCE && !yieldRequested && !ownerToken.isCancellationRequested();
    }
    synchronized void signalTransportProgress() { signals++; notifyAll(); }
    <T> T awaitTransport(CompletableFuture<T> future, Runnable drainOwnerEvents) {
        future.whenComplete((value, failure) -> signalTransportProgress());
        while (true) {
            requireOwnerCurrent();
            long observed;
            synchronized (this) { observed = signals; }
            drainOwnerEvents.run();
            requireOwnerCurrent();
            if (future.isDone()) {
                drainOwnerEvents.run();
                requireOwnerCurrent();
                try { return future.get(); }
                catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt(); ownerToken.throwIfCancelled();
                    throw new java.util.concurrent.CancellationException("Provider owner interrupted");
                } catch (ExecutionException failed) {
                    requireOwnerCurrent();
                    Throwable cause = failed.getCause();
                    if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                    if (cause instanceof Error) throw (Error) cause;
                    throw new IllegalStateException("Sealed inference transport failed", cause);
                }
            }
            synchronized (this) {
                if (signals != observed || future.isDone()) continue;
                try { wait(); }
                catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt(); ownerToken.throwIfCancelled();
                    throw new java.util.concurrent.CancellationException("Provider owner interrupted");
                }
            }
        }
    }
    @Override public void close() {
        if (Thread.currentThread() != owner || CURRENT.get() != this)
            throw new IllegalStateException("Provider attempt must close on its owner thread");
        synchronized (this) { stage = Stage.FINISHED; signals++; notifyAll(); }
        unregisterStop.run();
        transport.cancelExactRunAsync();
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
