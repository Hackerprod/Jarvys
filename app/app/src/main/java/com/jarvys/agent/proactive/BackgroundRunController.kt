package com.jarvys.agent.proactive

import com.jarvys.agent.CancellationToken

enum class BackgroundRunKind { PROACTIVE, TASK }

/** Single-flight coordinator shared by silent Proactive reviews and scheduled-task runs. */
object BackgroundRunController {
    private data class Active(val kind: BackgroundRunKind, val token: CancellationToken)
    private val lock = Any()
    private var active: Active? = null
    private var interactiveActive = false
    private val interactiveGenerations = mutableSetOf<Long>()

    fun tryStart(kind: BackgroundRunKind): CancellationToken? = synchronized(lock) {
        if (interactiveActive || interactiveGenerations.isNotEmpty()) return@synchronized null
        val current = active
        if (current != null) {
            // Explicit tasks outrank passive Proactive review, but wait for its worker to unwind.
            if (kind == BackgroundRunKind.TASK && current.kind == BackgroundRunKind.PROACTIVE
                && !current.token.isCancelled) current.token.cancel()
            return@synchronized null
        }
        CancellationToken.cancellable().also { active = Active(kind, it) }
    }

    fun finish(kind: BackgroundRunKind, token: CancellationToken) = synchronized(lock) {
        if (active?.kind == kind && active?.token === token) active = null
    }

    fun cancel(kind: BackgroundRunKind) = synchronized(lock) {
        if (active?.kind == kind) active?.token?.cancel()
    }

    fun cancelAll() = synchronized(lock) {
        active?.token?.cancel()
    }

    /** Called after an interactive run has secured its foreground run token. */
    fun interactiveStarted() = synchronized(lock) {
        interactiveActive = true
        active?.token?.cancel()
    }

    fun interactiveFinished() = synchronized(lock) { interactiveActive = false }

    /** Scoped reservations overlap safely while a cancelled worker unwinds. */
    fun interactiveStarted(generation: Long) = synchronized(lock) {
        interactiveGenerations.add(generation)
        active?.token?.cancel()
        Unit
    }

    fun interactiveFinished(generation: Long) = synchronized(lock) {
        interactiveGenerations.remove(generation)
        Unit
    }

    fun isInteractiveActive(): Boolean = synchronized(lock) { interactiveActive || interactiveGenerations.isNotEmpty() }
}
