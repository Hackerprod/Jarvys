package com.jarvys.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ApprovalSummary

/** Native UI effect boundary. The selected store is trusted, never reconstructed from model paths. */
internal class MemoryNativeActions private constructor(private val policy: NativeActionPolicy,
    private val review: (ApprovalSummary, CancellationToken) -> com.jarvys.agent.connectors.ApprovalDecision) {
    constructor(context: Context) : this(NativeActionPolicy(context), { summary, token -> NativeMemoryReview.request(context, summary, token) })
    companion object {
        fun forTool(store: MemoryStore) = MemoryNativeActions(store.nativeActionPolicy()) { summary, token -> ApprovalGate.INSTANCE.request(summary, token) }
    }
    fun prepare(operation: NativeActionPolicy.Operation, store: MemoryStore, args: List<String>,
                token: CancellationToken): Prepared {
        // DENY resolves before private state reads; no implicit authority is borrowed from memory.view.
        var request = policy.prepare(operation, NativeActionPolicy.binding(store.nativeScopeIdentity(), *args.toTypedArray()))
        try {
            token.throwIfCancelled()
            // Existing initialization/recovery is preparation, before exact contents are reviewed.
            request.requireCurrent()
            store.ensureInitialized()
            val state = store.nativeStateFingerprint(true)
            request = request.rebind(NativeActionPolicy.binding(store.nativeScopeIdentity(), state, *args.toTypedArray()))
            return Prepared(operation, store, args.toList(), state, request, token)
        } catch (failure: Throwable) { request.close(); throw failure }
    }
    fun <T> run(operation: NativeActionPolicy.Operation, store: MemoryStore, args: List<String>,
                token: CancellationToken, validate: () -> Unit = {}, effect: () -> T): T =
        prepare(operation, store, args, token).use { it.execute(validate, effect) }

    inner class Prepared internal constructor(private val operation: NativeActionPolicy.Operation,
        private val store: MemoryStore, private var args: List<String>, private val state: String,
        private var request: NativeActionPolicy.Request, private val token: CancellationToken) : AutoCloseable {
        fun appendExactArguments(additional: List<String>) {
            token.throwIfCancelled()
            val exact = args + additional
            request = request.rebind(NativeActionPolicy.binding(store.nativeScopeIdentity(), state, *exact.toTypedArray()))
            args = exact
        }
        private fun authorize(validate: () -> Unit) {
            token.throwIfCancelled(); validate()
            request.authorize(ApprovalSummary(
                title = operation.id,
                lines = listOf("Selected memory scope: ${store.nativeScopeIdentity()}", "Exact state: $state") + args,
                allowAlwaysAvailable = false,
            ), token, review)
        }
        fun <T> execute(validate: () -> Unit = {}, effect: () -> T): T {
            authorize(validate)
            val parent = NativeEffectDispatchScope.captureAdmission()
            val admission = Runnable { request.admit(token) { validate(); parent.run() } }
            return store.withNativeState(state, true, admission) {
                NativeEffectDispatchScope.enter(admission).use { effect() }
            }
        }
        fun <T> executeUnlocked(validate: () -> Unit = {}, effect: () -> T): T {
            authorize(validate)
            val parent = NativeEffectDispatchScope.captureAdmission()
            val admission = Runnable {
                store.withNativeState(state, true, { request.admit(token) { validate(); parent.run() } }) { Unit }
            }
            request.admit(token) { validate(); parent.run() }
            return NativeEffectDispatchScope.enter(admission).use { effect() }
        }
        override fun close() = request.close()
    }
}

/** Effect lifetime follows the screen/selection, not a capture-content generation refresh. */
@androidx.compose.runtime.Composable
internal fun rememberMemoryEffectToken(vararg keys: Any?): CancellationToken {
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var generation by androidx.compose.runtime.remember(*keys) { androidx.compose.runtime.mutableIntStateOf(0) }
    val token = androidx.compose.runtime.remember(*keys, owner, generation) { CancellationToken.cancellable() }
    androidx.compose.runtime.DisposableEffect(token, owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) token.cancel()
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && token.isCancellationRequested) generation++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); token.cancel() }
    }
    return token
}
