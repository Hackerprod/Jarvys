package com.jarvys.agent;

import java.util.concurrent.Callable;

/** Cancellation view for one run generation. A stopped/replaced run stays cancelled. */
public final class CancellationToken {
    private static final java.util.concurrent.atomic.AtomicLong CREW_GENERATIONS =
            new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
    private final StopController controller;
    private final long generation;
    private final boolean standaloneCancellable;
    private final boolean crewRun;
    private final Object standaloneActionLock = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean standaloneCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> standaloneCancelActions = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean(false);

    CancellationToken(StopController controller, long generation) {
        this(controller, generation, false);
    }

    private CancellationToken(StopController controller, long generation, boolean standaloneCancellable) {
        this(controller, generation, standaloneCancellable, false);
    }

    private CancellationToken(StopController controller, long generation, boolean standaloneCancellable,
                              boolean crewRun) {
        this.controller = controller;
        this.generation = generation;
        this.standaloneCancellable = standaloneCancellable;
        this.crewRun = crewRun;
    }

    /** Token for non-device chat requests that do not participate in the STOP-overlay run latch. */
    public static CancellationToken uncancellable() {
        return new CancellationToken(null, 0L, false);
    }

    /** Independent cancellable token for background reflection, which must not occupy the user run latch. */
    public static CancellationToken cancellable() { return new CancellationToken(null, 0L, true); }

    /** Independently stoppable Crew worker token; its parent cancellation is linked by CrewManager. */
    public static CancellationToken crewChild() { return new CancellationToken(null, CREW_GENERATIONS.getAndIncrement(), true, true); }

    public boolean isCrewRun() { return crewRun; }

    public boolean cancel() {
        synchronized (standaloneActionLock) {
            if (!standaloneCancellable || !standaloneCancelled.compareAndSet(false, true)) return false;
        }
        cancelStandaloneActions();
        return true;
    }

    private void cancelStandaloneActions() {
        for (Runnable action : standaloneCancelActions) {
            try { action.run(); } catch (RuntimeException ignored) { }
        }
        standaloneCancelActions.clear();
    }

    public long generation() {
        return generation;
    }

    public boolean isCancelled() {
        return isStoppedByUser() || timedOut.get();
    }

    /** Cancellation state independent of the observing thread's interrupt flag. */
    public boolean isCancellationRequested() {
        return (controller != null && !controller.isActive(generation))
                || (standaloneCancellable && standaloneCancelled.get()) || timedOut.get();
    }

    public boolean isStoppedByUser() {
        return (controller != null && !controller.isActive(generation))
                || (standaloneCancellable && standaloneCancelled.get())
                || (Thread.currentThread().isInterrupted() && !timedOut.get());
    }

    public boolean isTimedOut() {
        return timedOut.get();
    }

    boolean cancelForTimeout() {
        boolean latched;
        if (controller == null) {
            synchronized (standaloneActionLock) { latched = timedOut.compareAndSet(false, true); }
        } else latched = controller.latchTimeout(generation, () -> timedOut.compareAndSet(false, true));
        if (latched && timedOut.get() && controller != null) {
            controller.cancelActionsForTimeout(generation);
        } else if (latched && controller == null) {
            cancelStandaloneActions();
        }
        return latched && timedOut.get();
    }

    public void throwIfCancelled() {
        if (isStoppedByUser()) {
            throw new java.util.concurrent.CancellationException("Jarvys run stopped");
        }
        if (timedOut.get()) {
            throw new AgentRunTimeoutException();
        }
    }

    /** Serializes the last action gate with STOP's latch: either action starts first or STOP wins. */
    public boolean runIfActive(Runnable action) {
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        if (controller == null) {
            synchronized (standaloneActionLock) {
                if (standaloneCancelled.get() || timedOut.get()) return false;
                action.run();
                return true;
            }
        }
        final boolean[] invoked = { false };
        controller.runIfActive(generation, () -> {
            if (!timedOut.get()) {
                invoked[0] = true;
                action.run();
            }
        });
        return invoked[0];
    }

    public <T> T callIfActive(Callable<T> action, T cancelledValue) {
        if (Thread.currentThread().isInterrupted()) {
            return cancelledValue;
        }
        if (controller == null) {
            synchronized (standaloneActionLock) {
                if (standaloneCancelled.get() || timedOut.get()) return cancelledValue;
                try {
                    return action.call();
                } catch (RuntimeException error) {
                    throw error;
                } catch (Exception error) {
                    throw new IllegalStateException("Action failed", error);
                }
            }
        }
        return controller.callIfActive(generation, () -> timedOut.get() ? cancelledValue : action.call(), cancelledValue);
    }

    /** Register an active blocking I/O handle so STOP can close it synchronously. */
    public Runnable registerCancelAction(Runnable action) {
        if (controller == null) {
            if (action == null) return () -> { };
            java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean(false);
            Runnable once = () -> { if (invoked.compareAndSet(false, true)) action.run(); };
            if (standaloneCancelled.get() || timedOut.get()) once.run();
            else {
                standaloneCancelActions.add(once);
                if (standaloneCancelled.get() || timedOut.get()) {
                    standaloneCancelActions.remove(once);
                    // Cancellation may already have cleared the list after taking its snapshot.
                    // The once wrapper, not membership in that list, owns exactly-once delivery.
                    once.run();
                }
            }
            return () -> standaloneCancelActions.remove(once);
        }
        java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable once = () -> {
            if (invoked.compareAndSet(false, true)) action.run();
        };
        if (timedOut.get()) {
            once.run();
            return () -> { };
        }
        Runnable unregister = controller.registerCancelAction(generation, once);
        if (timedOut.get()) once.run();
        return unregister;
    }
}
