package com.jarvys.agent;

/** Lexical, non-inheritable dispatch hook for an already authorized native mission. No ambient policy. */
final class NativeEffectDispatchScope implements AutoCloseable {
    private static final ThreadLocal<NativeEffectDispatchScope> CURRENT = new ThreadLocal<>();
    private final Thread owner = Thread.currentThread();
    private final NativeEffectDispatchScope previous;
    private final Runnable admission;
    private boolean closed;
    private NativeEffectDispatchScope(Runnable admission) {
        this.admission = java.util.Objects.requireNonNull(admission);
        previous = CURRENT.get();
        CURRENT.set(this);
    }
    static NativeEffectDispatchScope enter(Runnable admission) { return new NativeEffectDispatchScope(admission); }
    static void beforeDispatch() {
        NativeEffectDispatchScope scope = CURRENT.get();
        if (scope != null) scope.admit();
    }
    static Runnable captureAdmission() {
        NativeEffectDispatchScope scope = CURRENT.get();
        return scope == null ? () -> { } : scope::admit;
    }
    private void admit() {
        if (closed) throw new IllegalStateException("Native dispatch scope is closed");
        admission.run();
    }
    @Override public void close() {
        if (closed) return;
        if (Thread.currentThread() != owner || CURRENT.get() != this)
            throw new IllegalStateException("Native dispatch scopes must close on their owning thread in lexical order");
        closed = true;
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
