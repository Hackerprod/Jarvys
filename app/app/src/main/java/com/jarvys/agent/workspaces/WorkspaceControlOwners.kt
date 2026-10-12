package com.jarvys.agent.workspaces

import com.jarvys.agent.CancellationToken

/** Process-local STOP ownership only. Durable commands stay in WorkspaceRegistry. */
internal object WorkspaceControlOwners {
    private val lock = Any()
    private val owners = LinkedHashSet<CancellationToken>()

    fun register(type: WorkspaceControlType): CancellationToken {
        val token = CancellationToken.cancellable()
        if (type != WorkspaceControlType.PAUSE) synchronized(lock) { owners.add(token) }
        return token
    }

    fun registerLocalReceipt(): CancellationToken = CancellationToken.cancellable().also {
        synchronized(lock) { owners.add(it) }
    }

    fun release(token: CancellationToken) { synchronized(lock) { owners.remove(token) } }

    fun cancelPending() {
        val pending = synchronized(lock) { owners.toList() }
        // Cancellation may close approval/I/O handles. Never call them under the owners lock.
        pending.forEach { it.cancel() }
    }
}
