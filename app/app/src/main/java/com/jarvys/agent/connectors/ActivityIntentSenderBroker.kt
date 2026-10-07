package com.jarvys.agent.connectors

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** MainActivity-owned ActivityResult bridge shared with optional Full-flavor authorization APIs. */
class ActivityIntentSenderBroker private constructor() {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var launcher: ((IntentSenderRequest) -> Unit)? = null
    private var pending: CompletableFuture<ActivityResult>? = null

    fun attach(launch: (IntentSenderRequest) -> Unit) = synchronized(lock) { launcher = launch }

    fun detach() = synchronized(lock) {
        launcher = null
        pending?.completeExceptionally(IllegalStateException("Authorization UI is no longer available"))
        pending = null
    }

    fun complete(result: ActivityResult) = synchronized(lock) { pending?.complete(result) }

    fun launch(pendingIntent: PendingIntent, timeoutMillis: Long = 180_000): Intent {
        val future = CompletableFuture<ActivityResult>()
        val currentLauncher = synchronized(lock) {
            check(pending == null) { "Another system authorization prompt is active" }
            val available = launcher ?: error("Return to Jarvys to continue authorization")
            pending = future
            available
        }
        try {
            val request = IntentSenderRequest.Builder(pendingIntent).build()
            mainHandler.post { runCatching { currentLauncher(request) }.onFailure { future.completeExceptionally(it) } }
            val result = future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            if (result.resultCode != Activity.RESULT_OK) throw ActivityAuthorizationCancelledException()
            return result.data ?: error("Authorization returned no result")
        } finally {
            synchronized(lock) { if (pending === future) pending = null }
        }
    }

    companion object {
        @Volatile private var instance: ActivityIntentSenderBroker? = null
        fun get(): ActivityIntentSenderBroker = instance ?: synchronized(this) {
            instance ?: ActivityIntentSenderBroker().also { instance = it }
        }
    }
}

class ActivityAuthorizationCancelledException : IllegalStateException("Authorization was cancelled")
