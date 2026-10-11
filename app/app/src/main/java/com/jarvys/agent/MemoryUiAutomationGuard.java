package com.jarvys.agent;

import android.content.Context;

/** Process-local boundary between automated device tools and user-only memory surfaces.
 * Native callers must acquire a lease before rendering private content and gate every native action.
 * An interrupted in-flight automated action permanently taints that surface until it is closed.
 */
public final class MemoryUiAutomationGuard {
    private static final Object MONITOR = new Object();
    private static long epoch;
    private static int protectedSurfaces;
    private static int taintedSurfaces;
    private static int activeActions;

    private MemoryUiAutomationGuard() { }

    public static final class Lease implements AutoCloseable {
        private final boolean tainted;
        private boolean closed;
        private Lease(boolean tainted) { this.tainted = tainted; }

        public boolean isReadyForUser() {
            synchronized (MONITOR) {
                return !closed && !tainted && taintedSurfaces == 0 && activeActions == 0;
            }
        }

        public void requireHumanUiInteraction() {
            if (!isReadyForUser()) throw blocked();
        }

        @Override public void close() {
            synchronized (MONITOR) {
                if (closed) return;
                closed = true;
                protectedSurfaces--;
                if (tainted) taintedSurfaces--;
                epoch++;
            }
        }
    }

    /** Remains open until actual completion/cancellation, even if the waiting tool times out. */
    public static final class Action implements AutoCloseable {
        private final AutoCloseable peer;
        private boolean closed;
        private Action() { this(null); }
        private Action(AutoCloseable peer) { this.peer = peer; }
        @Override public void close() {
            synchronized (MONITOR) {
                if (closed) return;
                closed = true;
                activeActions--;
            }
            // Peer IPC must never run while the local guard monitor is held.
            if (peer != null) try { peer.close(); } catch (Exception ignored) { }
        }
    }

    /** Immutable local and alternate-host observation. A new capture never refreshes queued work. */
    public static final class AutomationEpoch {
        private final long local;
        private final PeerUiAutomationClient.Proof peer;
        private AutomationEpoch(long local, PeerUiAutomationClient.Proof peer) {
            this.local = local;
            this.peer = peer;
        }
    }

    /** Device entry points require proof from every installed alternate Jarvys host. */
    public static AutomationEpoch captureAutomationEpoch(Context context) {
        long local = captureAutomationEpoch();
        PeerUiAutomationClient.Proof peer = PeerUiAutomationClient.capture(context);
        AutomationEpoch captured = new AutomationEpoch(local, peer);
        requireAutomationEpoch(captured);
        return captured;
    }

    static AutomationEpoch captureWithProof(PeerUiAutomationClient.Proof peer) {
        AutomationEpoch captured = new AutomationEpoch(captureAutomationEpoch(), peer);
        requireAutomationEpoch(captured);
        return captured;
    }

    public static boolean isAutomationEpochValid(AutomationEpoch captured) {
        return captured != null && isAutomationEpochValid(captured.local)
                && captured.peer.isValid() && isAutomationEpochValid(captured.local);
    }

    public static void requireAutomationEpoch(AutomationEpoch captured) {
        if (!isAutomationEpochValid(captured)) throw blocked();
    }

    public static boolean runAutomated(AutomationEpoch captured, Runnable action) {
        if (action == null) throw new IllegalArgumentException("Action is required");
        if (!isAutomationEpochValid(captured)) return false;
        Action lease;
        try { lease = beginAsyncAction(captured); }
        catch (IllegalStateException unavailable) { return false; }
        try { action.run(); }
        finally { lease.close(); }
        return true;
    }

    /** Keep both hosts' action leases until the actual terminal gesture callback, never a wait timeout. */
    public static Action beginAsyncAction(AutomationEpoch captured) {
        if (captured == null) throw blocked();
        Action local = beginAsyncAction(captured.local);
        AutoCloseable remote = null;
        try {
            remote = captured.peer.beginAction();
            requireAutomationEpoch(captured);
            final AutoCloseable ownedRemote = remote;
            // Replace the temporary local lease without exposing an idle gap.
            synchronized (MONITOR) { activeActions++; }
            return new Action(ownedRemote);
        } catch (RuntimeException failure) {
            if (remote != null) try { remote.close(); } catch (Exception ignored) { }
            throw blocked();
        } finally { local.close(); }
    }

    /** Provider-only endpoints. These must remain strictly local to avoid recursive peer IPC. */
    public static long[] localSnapshot() {
        synchronized (MONITOR) { return new long[] {epoch, protectedSurfaces == 0 ? 0L : 1L}; }
    }

    public static boolean localIsAutomationEpochValid(long capturedEpoch) {
        return isAutomationEpochValid(capturedEpoch);
    }

    public static Action localBeginAsyncAction(long capturedEpoch) {
        return beginAsyncAction(capturedEpoch);
    }

    public static Lease enterProtectedSurface() {
        synchronized (MONITOR) {
            boolean tainted = activeActions != 0 || taintedSurfaces != 0;
            protectedSurfaces++;
            if (tainted) taintedSurfaces++;
            epoch++;
            return new Lease(tainted);
        }
    }

    public static boolean isProtected() {
        synchronized (MONITOR) { return protectedSurfaces != 0; }
    }

    /** Capture once when an observation or action begins; never replace it after an intervening UI. */
    public static long captureAutomationEpoch() {
        synchronized (MONITOR) {
            if (protectedSurfaces != 0) throw blocked();
            return epoch;
        }
    }

    public static boolean isAutomationEpochValid(long capturedEpoch) {
        synchronized (MONITOR) { return protectedSurfaces == 0 && epoch == capturedEpoch; }
    }

    public static void requireAutomationEpoch(long capturedEpoch) {
        if (!isAutomationEpochValid(capturedEpoch)) throw blocked();
    }

    /** Gate at the actual dispatch, not at command enqueue time. Reentrant native entry is tainted. */
    public static boolean runAutomated(long capturedEpoch, Runnable action) {
        if (action == null) throw new IllegalArgumentException("Action is required");
        synchronized (MONITOR) {
            if (protectedSurfaces != 0 || epoch != capturedEpoch) return false;
            activeActions++;
        }
        // Never hold MONITOR through Binder: a node action may need the native main thread.
        // Entry racing this dispatch is permanently tainted before private UI is rendered.
        try { action.run(); }
        finally { synchronized (MONITOR) { activeActions--; } }
        return true;
    }

    /** Call inside runAutomated at dispatch; caller closes only from real terminal callbacks. */
    public static Action beginAsyncAction(long capturedEpoch) {
        synchronized (MONITOR) {
            if (protectedSurfaces != 0 || epoch != capturedEpoch) throw blocked();
            activeActions++;
            return new Action();
        }
    }

    private static IllegalStateException blocked() {
        return new IllegalStateException("User-only memory review is protected from automated device access");
    }
}
