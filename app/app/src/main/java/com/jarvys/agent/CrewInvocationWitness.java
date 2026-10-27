package com.jarvys.agent;

import java.util.Objects;

/** One live invocation's exact same-save checkpoint witness. Never grants a permission or durable job result. */
public final class CrewInvocationWitness {
    private final CrewInvocationSource source;
    private final EffectInvocation owner;
    private final Runnable current;
    CrewInvocationWitness(CrewInvocationSource source, EffectInvocation owner, Runnable current) {
        this.source = Objects.requireNonNull(source); this.owner = Objects.requireNonNull(owner);
        this.current = Objects.requireNonNull(current);
    }
    /** Historical metadata remains readable after dispatch; no raw/effective arguments are retained here. */
    public CrewInvocationSource source() { return source; }
    boolean ownedBy(EffectInvocation invocation) { return owner == invocation; }
    public void requireInvocationCurrent() { owner.requireCrewSourceActive(this); current.run(); }
}
