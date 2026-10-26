package com.jarvys.agent.connectors

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Process-owned ordering only. Capture callbacks MUST be memory-only and silent. */
class GoogleUiAdmission internal constructor(private val executor: Executor) {
    class Ticket internal constructor(val sequence: Long)
    enum class Rejection { BUSY, REVOCATION_PENDING, NOT_READY }
    sealed class Submission {
        data class Accepted(val ticket: Ticket) : Submission()
        data class Rejected(val reason: Rejection) : Submission()
    }
    private val lock = Any()
    private val queue = ArrayDeque<() -> Unit>()
    private var draining = false
    private var next = 0L
    private var pending = 0
    private var failed = false
    private var interactive: Ticket? = null
    @Volatile private var ready = false
    private var revocationBlocked = false
    @Volatile private var epoch = 0L
    @Volatile private var denied = false
    fun isReady(): Boolean = ready
    fun epoch(): Long = epoch
    fun isLocallyDenied(): Boolean = denied
    fun isCurrent(ticket: Ticket): Boolean = ticket.sequence == epoch
    internal fun initialize(blocked: Boolean) = synchronized(lock) {
        revocationBlocked = blocked
        ready = true
    }
    internal fun updateRevocationBlocked(blocked: Boolean) = synchronized(lock) { revocationBlocked = blocked }

    fun <C> local(capture: () -> C, publish: (C) -> Unit,
                  execute: (Ticket, (Boolean) -> Unit) -> Unit): Submission {
        val ticket: Ticket
        val captured: C
        synchronized(lock) {
            ticket = Ticket(++next)
            epoch = ticket.sequence
            pending++
            denied = true
            interactive = null
            try { captured = capture() } catch (failure: Throwable) {
                pending--
                failed = true
                throw failure
            }
            queue.addLast {
                val completed = AtomicBoolean()
                val complete: (Boolean) -> Unit = complete@{ success ->
                    if (!completed.compareAndSet(false, true)) return@complete
                    synchronized(lock) {
                        pending--
                        if (!success) failed = true
                        // Only a later successful local operation can acknowledge recovery.
                        if (success && ticket.sequence == epoch) failed = false
                        denied = pending > 0 || failed
                    }
                    drainNext()
                }
                try { execute(ticket, complete) } catch (_: Exception) { complete(false) }
            }
        }
        try { publish(captured) } finally { startDrain() }
        return Submission.Accepted(ticket)
    }

    fun <C> authorize(capture: () -> C, publish: (C) -> Unit,
                      execute: (Ticket, C) -> Unit): Submission {
        val ticket: Ticket
        val captured: C
        synchronized(lock) {
            if (!ready) return Submission.Rejected(Rejection.NOT_READY)
            if (pending > 0 || failed || interactive != null) return Submission.Rejected(Rejection.BUSY)
            if (revocationBlocked) return Submission.Rejected(Rejection.REVOCATION_PENDING)
            ticket = Ticket(++next)
            epoch = ticket.sequence
            interactive = ticket
            try { captured = capture() } catch (failure: Throwable) {
                interactive = null
                throw failure
            }
            queue.addLast { try { execute(ticket, captured) } finally { drainNext() } }
        }
        try { publish(captured) } finally { startDrain() }
        return Submission.Accepted(ticket)
    }

    internal fun finishAuthorization(ticket: Ticket) = synchronized(lock) {
        if (interactive === ticket) interactive = null
    }
    private fun startDrain() {
        val start = synchronized(lock) {
            if (draining || queue.isEmpty()) false else { draining = true; true }
        }
        if (start) dispatchNext()
    }
    private fun drainNext() = dispatchNext()
    private fun dispatchNext() {
        val next = synchronized(lock) {
            if (queue.isEmpty()) { draining = false; null } else queue.removeFirst()
        }
        if (next != null) executor.execute(next)
    }
}
