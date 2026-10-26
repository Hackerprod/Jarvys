package com.jarvys.agent;

import com.jarvys.agent.connectors.ConnectionStateCoordinator;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exact committed Central-call provenance, bound to one live invocation. Not effect authorization.
 * Historical source metadata remains readable after the invocation ends. The lifecycle lease is
 * ONLY for source+assignment SQLite persistence; never run Room, spawn, listeners or UI inside it.
 */
public final class CentralInvocationWitness {
    private final CentralInvocationSource source;
    private final EffectInvocation owner;
    private final Runnable checkAttachment;
    private final ConnectionStateCoordinator.DestinationEvidence lifecycle;

    CentralInvocationWitness(CentralInvocationSource source, EffectInvocation owner, Runnable checkAttachment,
                             ConnectionStateCoordinator.DestinationEvidence lifecycle) {
        this.source = Objects.requireNonNull(source); this.owner = Objects.requireNonNull(owner);
        this.checkAttachment = Objects.requireNonNull(checkAttachment); this.lifecycle = Objects.requireNonNull(lifecycle);
        if (!source.storageGeneration.equals(lifecycle.generation()))
            throw new IllegalArgumentException("Source lifecycle identity mismatch");
    }
    public CentralInvocationSource source() { return source; }
    boolean ownedBy(EffectInvocation invocation) { return owner == invocation; }

    /** Local active-invocation check only. Does not acquire or attest the database lifecycle fence. */
    public void requireInvocationCurrent() {
        owner.requireCentralSourceActive(this);
        checkAttachment.run();
    }
    /** Must be called only while the lifecycle lease already encloses the workspace transaction. */
    public void requireInsideCurrentLease() {
        requireInvocationCurrent();
        if (!lifecycle.fenceIdentity().isHeldByCurrentThread() || !lifecycle.isCurrentPure())
            throw new IllegalStateException("Central source database lifecycle changed");
    }
    /** Lock order: DB lifecycle anchor -> workspace monitor -> operational SQLite. No bridge waits. */
    public <T> T withCurrentWorkspaceTransaction(Callable<T> transaction) throws Exception {
        Objects.requireNonNull(transaction);
        ReentrantLock fence = lifecycle.fenceIdentity();
        fence.lock();
        try { requireInsideCurrentLease(); return transaction.call(); }
        finally { fence.unlock(); }
    }
}
