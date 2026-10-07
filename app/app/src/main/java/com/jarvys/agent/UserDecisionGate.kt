package com.jarvys.agent

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

enum class UserDecisionRole { PRIMARY, DEFAULT, DESTRUCTIVE }

data class UserDecisionOption(
    val id: String,
    val label: String,
    val description: String = "",
    val role: UserDecisionRole = UserDecisionRole.DEFAULT,
)

data class UserDecisionSpec(
    val title: String,
    val body: String,
    val options: List<UserDecisionOption>,
    val allowDismiss: Boolean = true,
)

sealed class UserDecisionResult {
    data class Selected(val option: UserDecisionOption) : UserDecisionResult()
    object Dismissed : UserDecisionResult()
    object Cancelled : UserDecisionResult()
    object Unavailable : UserDecisionResult()
}

interface UserDecisionPresenter {
    fun isAvailable(): Boolean
    fun show(id: String, spec: UserDecisionSpec)
    fun update(id: String, result: UserDecisionResult)
}

/** An unbounded, cancellation-aware UI rendezvous. It deliberately has no expiry timer. */
class UserDecisionGate {
    private class Pending(val id: String, val spec: UserDecisionSpec, val presenter: UserDecisionPresenter) {
        val latch = CountDownLatch(1)
        val result = AtomicReference<UserDecisionResult?>(null)
    }

    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()

    fun request(spec: UserDecisionSpec, token: CancellationToken, presenter: UserDecisionPresenter): UserDecisionResult {
        token.throwIfCancelled()
        if (!presenter.isAvailable()) return UserDecisionResult.Unavailable
        val request = Pending(UUID.randomUUID().toString(), spec.copy(options = spec.options.toList()), presenter)
        pending[request.id] = request
        try {
            presenter.show(request.id, request.spec)
        } catch (failure: RuntimeException) {
            pending.remove(request.id, request)
            return UserDecisionResult.Unavailable
        }
        val unregister = token.registerCancelAction { resolve(request.id, UserDecisionResult.Cancelled) }
        try {
            request.latch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            resolve(request.id, UserDecisionResult.Cancelled)
        } finally {
            unregister.run()
            pending.remove(request.id, request)
        }
        return request.result.get() ?: UserDecisionResult.Cancelled
    }

    fun resolve(id: String, result: UserDecisionResult): Boolean {
        val request = pending[id] ?: return false
        if (result is UserDecisionResult.Selected && request.spec.options.none { it.id == result.option.id }) return false
        if (result is UserDecisionResult.Dismissed && !request.spec.allowDismiss) return false
        if (!request.result.compareAndSet(null, result)) return false
        try {
            request.presenter.update(id, result)
        } finally {
            request.latch.countDown()
        }
        return true
    }

    fun isPending(id: String): Boolean = pending[id]?.let { it.result.get() == null } ?: false

    fun pendingIds(): Set<String> = pending.keys.toSet()
}
