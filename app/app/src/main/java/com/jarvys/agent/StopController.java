package com.jarvys.agent;

import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Process-wide synchronous latch that invalidates a run before interrupting its worker. */
public final class StopController {
    private static final StopController INSTANCE = new StopController();
    private static final AtomicLong NEXT_GENERATION = new AtomicLong(0L);
    private static final Set<StopController> ACTIVE_CONTROLLERS = ConcurrentHashMap.newKeySet();
    private static final Executor ASYNC_STOP = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "JarvysStopController");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private final AtomicLong generation = new AtomicLong(0L);
    private final Map<Long, List<Runnable>> cancelActions = new HashMap<>();
    private volatile Future<?> runFuture;
    private volatile CancellationToken activeToken;
    private int activeDispatches;

    public StopController() {}

    /** Creates a cancellation scope owned by one run. */
    public static StopController forRun() { return new StopController(); }

    public static StopController getInstance() {
        return INSTANCE;
    }

    public synchronized CancellationToken beginRun() {
        if (!stopped.get()) {
            return null;
        }
        long runGeneration = NEXT_GENERATION.incrementAndGet();
        generation.set(runGeneration);
        stopped.set(false);
        runFuture = null;
        cancelActions.put(runGeneration, new ArrayList<>());
        CancellationToken token = new CancellationToken(this, runGeneration);
        activeToken = token;
        ACTIVE_CONTROLLERS.add(this);
        return token;
    }

    public synchronized void attachFuture(CancellationToken token, Future<?> future) {
        if (isActive(token.generation())) {
            runFuture = future;
        } else {
            future.cancel(true);
        }
    }

    public boolean stopRun() {
        return stopRun(true);
    }

    /** Invalidates this run immediately and completes lock-sensitive cleanup away from the caller. */
    public boolean stopRunAsync() {
        return stopRun(false);
    }

    private boolean stopRun(boolean waitForCleanup) {
        final Future<?> future;
        final List<Runnable> actions;
        synchronized (this) {
            if (!stopped.compareAndSet(false, true)) {
                return false;
            }
            // Invalidate every token before asking the worker thread to unwind.
            long stoppedGeneration = generation.get();
            CancellationToken token = activeToken;
            if (token != null && token.generation() == stoppedGeneration) token.markStoppedByUser();
            activeToken = null;
            actions = cancelActions.remove(stoppedGeneration);
            generation.incrementAndGet();
            future = runFuture;
            runFuture = null;
        }
        ACTIVE_CONTROLLERS.remove(this);
        if (future != null) {
            future.cancel(true);
        }
        Runnable cleanup = () -> {
            awaitActiveDispatches();
            if (actions != null) {
                for (Runnable action : actions) {
                    try {
                        action.run();
                    } catch (RuntimeException ignored) {
                        // Cancellation actions are best-effort cleanup; STOP's latch is already closed.
                    }
                }
            }
        };
        if (waitForCleanup) cleanup.run();
        else ASYNC_STOP.execute(cleanup);
        return true;
    }

    private void awaitActiveDispatches() {
        boolean interrupted = false;
        synchronized (this) {
            while (activeDispatches > 0) {
                try { wait(); }
                catch (InterruptedException stopWaitInterrupted) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    synchronized boolean completeRun(CancellationToken token) {
        if (!isActive(token.generation())) {
            return false;
        }
        ACTIVE_CONTROLLERS.remove(this);
        stopped.set(true);
        cancelActions.remove(token.generation());
        generation.incrementAndGet();
        runFuture = null;
        if (activeToken == token) activeToken = null;
        return true;
    }

    synchronized boolean isActive(long tokenGeneration) {
        return !stopped.get() && generation.get() == tokenGeneration;
    }

    synchronized boolean latchTimeout(long tokenGeneration, Runnable latch) {
        if (!isActive(tokenGeneration)) return false;
        latch.run();
        return true;
    }

    boolean runIfActive(long tokenGeneration, Runnable action) {
        synchronized (this) {
            if (!isActive(tokenGeneration)) return false;
            activeDispatches++;
        }
        try { action.run(); return true; }
        finally { finishDispatch(); }
    }

    <T> T callIfActive(long tokenGeneration, Callable<T> action, T cancelledValue) {
        synchronized (this) {
            if (!isActive(tokenGeneration)) return cancelledValue;
            activeDispatches++;
        }
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Action failed", e);
        } finally { finishDispatch(); }
    }

    private synchronized void finishDispatch() {
        activeDispatches--;
        if (activeDispatches == 0) notifyAll();
    }

    /** True while an action admitted through runIfActive/callIfActive is still running (STOP waits for it). */
    synchronized boolean hasActiveDispatch() {
        return activeDispatches > 0;
    }

    Runnable registerCancelAction(long tokenGeneration, Runnable action) {
        synchronized (this) {
            if (isActive(tokenGeneration)) {
                List<Runnable> actions = cancelActions.get(tokenGeneration);
                if (actions == null) {
                    actions = new ArrayList<>();
                    cancelActions.put(tokenGeneration, actions);
                }
                actions.add(action);
                List<Runnable> registered = actions;
                return () -> unregisterCancelAction(tokenGeneration, registered, action);
            }
        }
        // A callback may acquire an approval/UI lock whose owner checks this controller.
        // Keep immediate cancellation synchronous, but never invoke foreign code under our monitor.
        action.run();
        return () -> { };
    }

    void cancelActionsForTimeout(long tokenGeneration) {
        final List<Runnable> actions;
        synchronized (this) {
            List<Runnable> registered = cancelActions.get(tokenGeneration);
            if (registered == null) return;
            actions = new ArrayList<>(registered);
        }
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (RuntimeException ignored) {
                // Closing active I/O is best-effort; the timeout state is already latched.
            }
        }
    }

    private synchronized void unregisterCancelAction(long tokenGeneration, List<Runnable> actions,
                                                       Runnable action) {
        if (generation.get() == tokenGeneration) actions.remove(action);
    }

    public boolean isStopped() {
        return stopped.get();
    }

    /** Process-level compatibility facade used by the notification and legacy global actions. */
    public static void stopAllRuns() {
        for (StopController controller : ACTIVE_CONTROLLERS.toArray(new StopController[0])) {
            controller.stopRunAsync();
        }
        INSTANCE.stopRunAsync();
    }

    public static boolean hasActiveRuns() { return !ACTIVE_CONTROLLERS.isEmpty(); }
}
