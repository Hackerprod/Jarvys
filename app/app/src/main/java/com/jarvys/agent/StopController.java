package com.jarvys.agent;

import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Process-wide synchronous latch that invalidates a run before interrupting its worker. */
public final class StopController {
    private static final StopController INSTANCE = new StopController();

    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private final AtomicLong generation = new AtomicLong(0L);
    private final Map<Long, List<Runnable>> cancelActions = new HashMap<>();
    private volatile Future<?> runFuture;

    private StopController() {}

    public static StopController getInstance() {
        return INSTANCE;
    }

    public synchronized CancellationToken beginRun() {
        if (!stopped.get()) {
            return null;
        }
        long runGeneration = generation.incrementAndGet();
        stopped.set(false);
        runFuture = null;
        cancelActions.put(runGeneration, new ArrayList<>());
        return new CancellationToken(this, runGeneration);
    }

    public synchronized void attachFuture(CancellationToken token, Future<?> future) {
        if (isActive(token.generation())) {
            runFuture = future;
        } else {
            future.cancel(true);
        }
    }

    public boolean stopRun() {
        final Future<?> future;
        final List<Runnable> actions;
        synchronized (this) {
            if (!stopped.compareAndSet(false, true)) {
                return false;
            }
            // Invalidate every token before asking the worker thread to unwind.
            long stoppedGeneration = generation.get();
            actions = cancelActions.remove(stoppedGeneration);
            generation.incrementAndGet();
            future = runFuture;
            runFuture = null;
        }
        if (future != null) {
            future.cancel(true);
        }
        if (actions != null) {
            for (Runnable action : actions) {
                try {
                    action.run();
                } catch (RuntimeException ignored) {
                    // Cancellation actions are best-effort cleanup; STOP's latch is already closed.
                }
            }
        }
        return true;
    }

    synchronized boolean completeRun(CancellationToken token) {
        if (!isActive(token.generation())) {
            return false;
        }
        stopped.set(true);
        cancelActions.remove(token.generation());
        generation.incrementAndGet();
        runFuture = null;
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

    synchronized boolean runIfActive(long tokenGeneration, Runnable action) {
        if (!isActive(tokenGeneration)) {
            return false;
        }
        action.run();
        return true;
    }

    synchronized <T> T callIfActive(long tokenGeneration, Callable<T> action, T cancelledValue) {
        if (!isActive(tokenGeneration)) {
            return cancelledValue;
        }
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Action failed", e);
        }
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
}
