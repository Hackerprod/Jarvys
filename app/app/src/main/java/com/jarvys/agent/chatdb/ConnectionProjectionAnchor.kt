package com.jarvys.agent.chatdb

import com.jarvys.agent.connectors.ConnectionStateCoordinator
import java.util.concurrent.locks.ReentrantLock

/** Process-lifetime identity for one physical destination. Obtaining it never opens Room. */
class ConnectionProjectionAnchor internal constructor() {
    val fence = ReentrantLock()
    val providerIdentity: Any = Any()
    private val databases = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<JarvysChatDatabase, Boolean>())
    private var version = 0L
    private var generation: String? = null
    private var available = false

    internal fun <T> fenced(operation: () -> T): T {
        check(LeasedConnectionElement.current() == null && RawReadConnectionElement.current() == null) {
            "Connection destination lifecycle cannot be entered from a database lease"
        }
        fence.lock()
        try { return operation() } finally { fence.unlock() }
    }

    /** Opening/migration may mutate the destination even when the attempt subsequently fails. */
    internal fun opening() {
        check(fence.isHeldByCurrentThread)
        version++
        generation = null
        available = false
    }

    internal fun opened(value: JarvysChatDatabase) {
        check(fence.isHeldByCurrentThread)
        // Schema/lease tests may open multiple legitimate handles to the same file. Each new
        // handle conservatively invalidates prior tokens; no handle can impersonate another.
        if (databases.add(value)) {
            version++
            generation = null
            available = false
        }
    }

    /** Invalidate before close, including a close that subsequently throws. */
    internal fun invalidate(value: JarvysChatDatabase) {
        check(fence.isHeldByCurrentThread)
        if (databases.contains(value)) {
            version++
            available = false
            generation = null
        }
    }

    internal fun closed(value: JarvysChatDatabase) {
        check(fence.isHeldByCurrentThread)
        databases.remove(value)
    }

    internal fun attest(value: JarvysChatDatabase, durableGeneration: String): ConnectionStateCoordinator.DestinationEvidence {
        check(fence.isHeldByCurrentThread && databases.contains(value))
        if (!available || generation != durableGeneration) version++
        generation = durableGeneration
        available = true
        return Evidence(value, version, durableGeneration)
    }

    internal fun accepts(evidence: ConnectionStateCoordinator.DestinationEvidence, value: JarvysChatDatabase): Boolean {
        check(fence.isHeldByCurrentThread)
        return evidence is Evidence && evidence.owner === this && evidence.value === value && evidence.isCurrentPure()
    }

    private inner class Evidence(val value: JarvysChatDatabase, val observedVersion: Long,
                                 private val durableGeneration: String) : ConnectionStateCoordinator.DestinationEvidence {
        val owner: ConnectionProjectionAnchor get() = this@ConnectionProjectionAnchor
        override fun generation(): String = durableGeneration
        override fun providerIdentity(): Any = this@ConnectionProjectionAnchor.providerIdentity
        override fun fenceIdentity(): ReentrantLock = fence
        override fun isCurrentPure(): Boolean = fence.isHeldByCurrentThread && available &&
            databases.contains(value) && version == observedVersion && generation == durableGeneration
    }
}
