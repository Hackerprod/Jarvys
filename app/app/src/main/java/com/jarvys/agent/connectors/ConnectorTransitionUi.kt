package com.jarvys.agent.connectors

import android.content.Context
import androidx.core.content.ContextCompat
import com.jarvys.agent.R
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Observation belongs to the screen; accepted work belongs to the process. Never cancel the latter. */
internal class ConnectorUiObserver(private val main: Executor) : AutoCloseable {
    constructor(context: Context) : this(ContextCompat.getMainExecutor(context.applicationContext))
    private val active = AtomicBoolean(true)

    fun <T> observe(operation: CompletableFuture<T>, callback: (T?, Boolean) -> Unit) {
        operation.whenComplete { value, failure ->
            main.execute { if (active.get()) callback(value, failure != null) }
        }
    }
    override fun close() { active.set(false) }
}

/** Local settings IO only; not a grant, runtime lane, or replacement for Registry requests. */
internal object ConnectorSettingsWork {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "JarvysConnectorSettings").apply { isDaemon = true }
    }
    fun <T> submit(operation: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(operation, worker)
}

internal fun connectionTransitionMessage(result: ConnectorRegistry.ConnectorTransitionResult?, failed: Boolean): Int? {
    if (failed || result == null) return R.string.connector_transition_failed
    return when (result.status) {
        ConnectorRegistry.ConnectorTransitionResult.Status.COMPLETED ->
            if (result.persistenceConfirmed) null else R.string.connector_transition_uncertain
        ConnectorRegistry.ConnectorTransitionResult.Status.ABORTED -> null
        ConnectorRegistry.ConnectorTransitionResult.Status.NOT_READY -> R.string.connector_transition_loading
        ConnectorRegistry.ConnectorTransitionResult.Status.MISSING,
        ConnectorRegistry.ConnectorTransitionResult.Status.SUPERSEDED,
        ConnectorRegistry.ConnectorTransitionResult.Status.CONFLICT,
        ConnectorRegistry.ConnectorTransitionResult.Status.ABORT_REJECTED -> R.string.connector_transition_obsolete
        ConnectorRegistry.ConnectorTransitionResult.Status.PERMISSION_DENIED -> R.string.connector_transition_permission_denied
        ConnectorRegistry.ConnectorTransitionResult.Status.PENDING,
        ConnectorRegistry.ConnectorTransitionResult.Status.PERSISTENCE_UNCERTAIN -> R.string.connector_transition_uncertain
        ConnectorRegistry.ConnectorTransitionResult.Status.RUNTIME_FAILED -> R.string.connector_transition_runtime_failed
        ConnectorRegistry.ConnectorTransitionResult.Status.CLEANUP_FAILED -> R.string.connector_transition_cleanup_failed
    }
}

internal fun connectionTransitionPersisted(result: ConnectorRegistry.ConnectorTransitionResult?): Boolean =
    result != null && result.persistenceConfirmed && result.status in setOf(
        ConnectorRegistry.ConnectorTransitionResult.Status.COMPLETED,
        ConnectorRegistry.ConnectorTransitionResult.Status.CLEANUP_FAILED,
    )

/** Context supplied by Compose may be themed/wrapped; lifecycle belongs to the real Activity. */
internal fun connectionActivity(context: Context): android.app.Activity? {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
    var current = context
    while (seen.add(current)) {
        if (current is android.app.Activity) return current
        if (current !is android.content.ContextWrapper) return null
        current = current.baseContext
    }
    return null
}
