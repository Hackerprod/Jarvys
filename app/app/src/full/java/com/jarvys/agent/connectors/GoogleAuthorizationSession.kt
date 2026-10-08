package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/** Monotonic session ownership serializes persistence with disconnect/account changes. */
internal class GoogleAuthorizationSession {
    class Lease internal constructor(val generation: Long, val token: CancellationToken, val interactive: Boolean)
    private var generation = epochs.incrementAndGet()
    private val leases = mutableSetOf<Lease>()
    @Synchronized fun currentEpoch(): Long = generation
    @Synchronized fun acquire(interactive: Boolean = false, expectedEpoch: Long? = null): Lease {
        check(expectedEpoch == null || expectedEpoch == generation) { "Google connection changed; prepare and approve this operation again" }
        check(!interactive || leases.none { it.interactive }) { "Google authorization is already in progress" }
        if (interactive) invalidate()
        return Lease(generation, CancellationToken.cancellable(), interactive).also(leases::add)
    }
    @Synchronized fun hasInteractive(): Boolean = leases.any { it.interactive && !it.token.isCancellationRequested }
    @Synchronized fun invalidate() {
        generation = epochs.incrementAndGet()
        leases.toList().forEach { it.token.cancel() }
        leases.clear()
    }
    @Synchronized fun isCurrent(lease: Lease): Boolean = generation == lease.generation && !lease.token.isCancellationRequested
    @Synchronized fun <T> withCurrent(lease: Lease, block: () -> T): T {
        if (!isCurrent(lease)) throw CancellationException("Google connection changed; retry from the current account")
        lease.token.throwIfCancelled()
        return block()
    }
    @Synchronized fun release(lease: Lease) { leases.remove(lease) }
    companion object { private val epochs = AtomicLong() }
}

enum class GoogleRevocationState { NOT_REQUESTED, PENDING, VERIFIED, FAILED }
