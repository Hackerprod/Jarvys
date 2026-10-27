package com.jarvys.agent;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * Serializes automatic-memory commits with a preference change. One shared instance must own all
 * accesses for a storage root; this class does not provide that registry or enforce worker threads.
 * All preference changes must use setEnabled; out-of-band OFF/ON writes cannot preserve epochs.
 * Capture before model work, then enter commit only on the transaction-owning thread. Callbacks
 * must not call an external bridge, run a model, notify listeners, or change this gate's setting.
 * A structured Room transaction hop may be awaited by the owning callable only when its writer
 * never reacquires this gate; arbitrary asynchronous dispatch/waits are forbidden.
 * The effect must finish any rollback before returning/throwing; the lock covers that entire call.
 * Explicit user writes and cleanup do not use this automatic-memory gate.
 */
public final class MemoryWriteGate {
    public interface Settings {
        /** Fast, thread-safe raw preference read; never acquire database or writer locks. */
        boolean readEnabled();
        boolean commitEnabled(boolean enabled);
    }

    /** Opaque snapshot, valid only for the gate and preference epoch that issued it. */
    public static final class Ticket {
        private final MemoryWriteGate owner;
        private final long epoch;
        private final boolean enabled;
        /** Observed immutable policy metadata, not current write permission. */
        public boolean enabledAtCapture() { return enabled; }
        private Ticket(MemoryWriteGate owner, long epoch, boolean enabled) {
            this.owner = owner;
            this.epoch = epoch;
            this.enabled = enabled;
        }
    }

    public static final class RejectedException extends IllegalStateException {
        private RejectedException() { super("Automatic memory write is no longer authorized"); }
    }

    private final Settings settings;
    private final ReentrantLock lock = new ReentrantLock();
    private static final class State {
        final long epoch;
        final boolean denyOverlay;
        final boolean observedEnabled;
        State(long epoch, boolean denyOverlay, boolean observedEnabled) {
            this.epoch = epoch;
            this.denyOverlay = denyOverlay;
            this.observedEnabled = observedEnabled;
        }
    }
    private volatile State state;

    MemoryWriteGate(Settings settings) {
        this.settings = Objects.requireNonNull(settings);
        state = new State(0, false, settings.readEnabled());
    }

    public boolean isEnabled() {
        State snapshot = state;
        return !snapshot.denyOverlay && snapshot.observedEnabled && settings.readEnabled();
    }

    public Ticket capture() {
        State snapshot = state;
        return new Ticket(this, snapshot.epoch,
            !snapshot.denyOverlay && snapshot.observedEnabled && settings.readEnabled());
    }

    /** A failed durable preference commit invalidates outstanding tickets and denies future ones. */
    public void setEnabled(boolean enabled) {
        rejectReentrantWrite();
        lock.lock();
        boolean committed = false;
        State previous = state;
        try {
            boolean persistedBefore = settings.readEnabled();
            boolean changed = previous.denyOverlay || previous.observedEnabled != enabled
                || persistedBefore != enabled;
            if (!settings.commitEnabled(enabled)) {
                throw new IllegalStateException("Memory preference could not be persisted");
            }
            committed = true;
            state = new State(previous.epoch + (changed ? 1 : 0), false, enabled);
        } finally {
            if (!committed) {
                state = new State(previous.epoch + 1, true, false);
            }
            lock.unlock();
        }
    }

    /** pureCheck is a quick, side-effect-free cancellation/eligibility check, not a dispatcher. */
    public <T> T commit(Ticket ticket, BooleanSupplier pureCheck, Callable<T> effect) throws Exception {
        rejectReentrantWrite();
        Objects.requireNonNull(pureCheck);
        Objects.requireNonNull(effect);
        lock.lock();
        try {
            State current = state;
            if (ticket == null || ticket.owner != this || ticket.epoch != current.epoch || !ticket.enabled
                    || current.denyOverlay || !current.observedEnabled || !settings.readEnabled()
                    || !pureCheck.getAsBoolean()) {
                throw new RejectedException();
            }
            return effect.call();
        } finally { lock.unlock(); }
    }
    private void rejectReentrantWrite() {
        if (lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Memory gate writes cannot be re-entered from a callback");
        }
    }
}
