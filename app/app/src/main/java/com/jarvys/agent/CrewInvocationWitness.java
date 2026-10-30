package com.jarvys.agent;

import java.util.Objects;

/** One live invocation's exact same-save checkpoint witness. Never grants a permission or durable job result. */
public final class CrewInvocationWitness {
    private final CrewInvocationSource source;
    private final EffectInvocation owner;
    private final Runnable current;
    private final CoreAgentLoop.PreparedInvocationSnapshot prepared;
    CrewInvocationWitness(CrewInvocationSource source, EffectInvocation owner, Runnable current) {
        this(source, owner, current, null);
    }
    CrewInvocationWitness(CrewInvocationSource source, EffectInvocation owner, Runnable current, CoreAgentLoop.PreparedInvocationSnapshot prepared) {
        this.prepared = prepared;
        this.source = Objects.requireNonNull(source); this.owner = Objects.requireNonNull(owner);
        this.current = Objects.requireNonNull(current);
    }
    /** Historical source metadata remains readable after dispatch. The live prepared binding is separately
     * retained only by reference; the loop discards its argument payload at invocation completion. */
    public CrewInvocationSource source() { return source; }
    public void requireArguments(java.util.Map<String,Object> arguments) {
        requireInvocationCurrent();
        if (prepared == null || prepared.arguments() != arguments)
            throw new IllegalStateException("workspace_memory_arguments_changed");
    }
    boolean ownedBy(EffectInvocation invocation) { return owner == invocation; }
    public void requireInvocationCurrent() { owner.requireCrewSourceActive(this); current.run(); }
}
