package com.jarvys.agent.connectors

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.jarvys.agent.CancellationToken
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Each attempt has its own registry key: a stale result can never resolve a newer prompt. */
class ActivityIntentSenderBroker internal constructor() {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val processKey = UUID.randomUUID().toString()
    private var serial = 0L
    private var attachment: Long? = null
    private var registry: ActivityResultRegistry? = null
    private var pending: Pending? = null
    private class Pending(val id: Long, val owner: Long, val future: CompletableFuture<ActivityResult>,
                          var launcher: ActivityResultLauncher<IntentSenderRequest>? = null)

    fun attachRegistry(value: ActivityResultRegistry): Long = synchronized(lock) {
        invalidateLocked()
        val id = ++serial
        attachment = id
        registry = value
        id
    }
    fun detach(owner: Long) = synchronized(lock) {
        // The old Activity may finish disposing after its replacement has attached.
        if (attachment == owner) {
            attachment = null
            registry = null
            invalidateLocked()
        }
    }
    fun invalidate() = synchronized(lock) { invalidateLocked() }
    private fun invalidateLocked() {
        pending?.let { active ->
            active.future.completeExceptionally(ActivityAuthorizationCancelledException())
            mainHandler.post { active.launcher?.unregister() }
        }
        pending = null
        ++serial
    }
    internal fun complete(id: Long, result: ActivityResult) = synchronized(lock) {
        pending?.takeIf { it.id == id }?.future?.complete(result)
    }

    fun launch(pendingIntent: PendingIntent, timeoutMillis: Long = 180_000,
               token: CancellationToken = CancellationToken.uncancellable()): Intent {
        token.throwIfCancelled()
        val active = synchronized(lock) {
            check(pending == null) { "Another system authorization prompt is active" }
            val owner = attachment ?: error("Return to Jarvys to continue authorization")
            Pending(++serial, owner, CompletableFuture()).also { pending = it }
        }
        val unregisterCancellation = token.registerCancelAction {
            synchronized(lock) { if (pending === active) invalidateLocked() }
        }
        try {
            val request = IntentSenderRequest.Builder(pendingIntent).build()
            mainHandler.post {
                synchronized(lock) {
                    if (pending !== active || attachment != active.owner || token.isCancellationRequested) return@post
                    runCatching {
                        val current = registry ?: error("Authorization UI is unavailable")
                        val launcher = current.register("jarvys_google_${processKey}_${active.id}",
                            ActivityResultContracts.StartIntentSenderForResult()) { result -> complete(active.id, result) }
                        active.launcher = launcher
                        launcher.launch(request)
                    }.onFailure { active.future.completeExceptionally(it) }
                }
            }
            val result = active.future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            token.throwIfCancelled()
            return authorizationResultData(result)
        } finally {
            unregisterCancellation.run()
            synchronized(lock) { if (pending === active) pending = null }
            mainHandler.post { active.launcher?.unregister() }
        }
    }

    companion object {
        /** Google interprets any returned Intent, including data accompanying RESULT_CANCELED. */
        internal fun authorizationResultData(result: ActivityResult): Intent {
            result.data?.let { return it }
            if (result.resultCode == Activity.RESULT_CANCELED) throw ActivityAuthorizationCancelledException()
            error("Authorization returned no result")
        }
        @Volatile private var instance: ActivityIntentSenderBroker? = null
        fun get(): ActivityIntentSenderBroker = instance ?: synchronized(this) {
            instance ?: ActivityIntentSenderBroker().also { instance = it }
        }
    }
}

class ActivityAuthorizationCancelledException : IllegalStateException("Authorization was cancelled")
