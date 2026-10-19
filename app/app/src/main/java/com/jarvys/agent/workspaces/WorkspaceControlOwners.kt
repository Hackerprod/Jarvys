package com.jarvys.agent.workspaces

import com.jarvys.agent.CancellationToken
import java.util.LinkedHashMap

/** Process-local STOP ownership only. Durable commands stay in WorkspaceRegistry. */
internal object WorkspaceControlOwners {
    private val lock = Any()
    private data class Owner(
        val token: CancellationToken,
        val type: WorkspaceControlType?,
        val assignmentId: String?,
        val native: Boolean,
    )
    private val owners = LinkedHashMap<CancellationToken, Owner>()

    fun register(type: WorkspaceControlType, assignmentId: String? = null): CancellationToken {
        val token = CancellationToken.cancellable()
        synchronized(lock) { owners[token] = Owner(token, type, assignmentId, native = true) }
        return token
    }

    fun registerLocalReceipt(assignmentId: String? = null): CancellationToken = CancellationToken.cancellable().also {
        synchronized(lock) { owners[it] = Owner(it, null, assignmentId, native = false) }
    }

    fun hasNativeControlOwner(assignmentId: String, excluding: CancellationToken? = null): Boolean = synchronized(lock) {
        owners.values.any { it.native && it.token !== excluding && it.assignmentId == assignmentId }
    }

    fun release(token: CancellationToken) { synchronized(lock) { owners.remove(token) } }

    fun cancelPending() {
        val pending = synchronized(lock) { owners.values.filter { it.type != WorkspaceControlType.PAUSE }.map { it.token } }
        // Cancellation may close approval/I/O handles. Never call them under the owners lock.
        pending.forEach { it.cancel() }
    }
}
