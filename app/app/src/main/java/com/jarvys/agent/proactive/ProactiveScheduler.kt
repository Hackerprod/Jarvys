package com.jarvys.agent.proactive

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object ProactiveWorkNames {
    const val PERIODIC = "jarvys_proactive_periodic"
    const val RUN_NOW = "jarvys_proactive_run_now"
}

object ProactiveScheduler {
    const val PERIODIC_INTERVAL_MILLIS = PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        val workManager = WorkManager.getInstance(app)
        if (enabled && ProactivePreferences(app).enabled) {
            val request = PeriodicWorkRequest.Builder(
                ProactiveWorker::class.java,
                PERIODIC_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            ).build()
            workManager.enqueueUniquePeriodicWork(
                ProactiveWorkNames.PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        } else {
            workManager.cancelUniqueWork(ProactiveWorkNames.PERIODIC)
            workManager.cancelUniqueWork(ProactiveWorkNames.RUN_NOW)
        }
    }

    fun requestRunNow(context: Context): Boolean {
        val app = context.applicationContext
        if (!ProactivePreferences(app).enabled) return false
        WorkManager.getInstance(app).enqueueUniqueWork(
            ProactiveWorkNames.RUN_NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(ProactiveWorker::class.java).build(),
        )
        return true
    }
}

data class ProactiveStatus(
    val enabled: Boolean,
    val lastCheckMillis: Long = 0L,
    val nextCheckMillis: Long? = null,
    val periodicIntervalMillis: Long = ProactiveScheduler.PERIODIC_INTERVAL_MILLIS,
    val pendingCount: Int = 0,
    val discardedCount: Int = 0,
    val lastBatchesSeen: Int = 0,
    val lastFailureReason: String? = null,
    val notificationPermissionGranted: Boolean = true,
)

class ProactiveReviewStateStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun lastCheckMillis(): Long = preferences.getLong(KEY_LAST_CHECK, 0L)

    fun lastBatchesSeen(): Int = preferences.getInt(KEY_LAST_BATCHES, 0)

    fun lastFailureReason(): String? = preferences.getString(KEY_LAST_FAILURE, null)

    fun recordReview(checkedAtMillis: Long, batchesSeen: Int) {
        check(preferences.edit()
            .putLong(KEY_LAST_CHECK, checkedAtMillis)
            .putInt(KEY_LAST_BATCHES, batchesSeen)
            .commit()) { "Could not persist proactive review status" }
    }

    fun recordFailure(reason: String) {
        require(reason.isNotBlank()) { "A proactive failure reason is required" }
        check(preferences.edit().putString(KEY_LAST_FAILURE, reason).commit()) {
            "Could not persist proactive failure state"
        }
    }

    fun clearFailure() {
        check(preferences.edit().remove(KEY_LAST_FAILURE).commit()) { "Could not clear proactive failure state" }
    }

    companion object {
        private const val PREFERENCES_NAME = "jarvys_proactive_review_state"
        private const val KEY_LAST_CHECK = "last_check_millis"
        private const val KEY_LAST_BATCHES = "last_batches_seen"
        private const val KEY_LAST_FAILURE = "last_failure_reason"
    }
}

class ProactiveReviewRecorder(private val stateStore: ProactiveReviewStateStore) {
    fun recordReview(batchesSeen: Int) {
        stateStore.recordReview(System.currentTimeMillis(), batchesSeen)
    }

    fun recordFailure(reason: String) = stateStore.recordFailure(reason)
    fun clearFailure() = stateStore.clearFailure()
}

sealed interface ProactiveProcessingResult {
    data object Completed : ProactiveProcessingResult
    data class Retryable(val reason: String) : ProactiveProcessingResult
    data class Failed(val reason: String) : ProactiveProcessingResult
    data object Stopped : ProactiveProcessingResult
}

interface ProactiveBatchProcessor {
    fun review(batches: List<ProactiveEventBatch>, token: com.jarvys.agent.CancellationToken): ProactiveProcessingResult
}

/** Single production composition point for the silent turn; the recorder remains its review-state writer. */
object ProactiveBatchProcessorFactory {
    fun create(context: Context): ProactiveBatchProcessor = ProactiveAgentProcessor(
        context = context,
        loopFactory = ConfiguredProactiveCoreLoopFactory(),
        readOnlyTools = { app, sink -> ProactiveReadOnlyToolFactory.create(app, sink) },
        reviewRecorder = ProactiveReviewRecorder(ProactiveReviewStateStore(context)),
        auditStore = ProactiveDecisionAuditStore(context),
        conversationStore = com.jarvys.agent.LocalRunStore(context),
    )
}

object ProactiveStatusProvider {
    suspend fun read(context: Context): ProactiveStatus = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val workInfo = runCatching {
            WorkManager.getInstance(app).getWorkInfosForUniqueWork(ProactiveWorkNames.PERIODIC).get()
                .firstOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        }.getOrNull()
        val events = ProactiveEventStore(app).readAll()
        val review = ProactiveReviewStateStore(app)
        ProactiveStatus(
            enabled = ProactivePreferences(app).enabled,
            lastCheckMillis = review.lastCheckMillis(),
            nextCheckMillis = workInfo?.nextScheduleTimeMillis?.takeIf { it > 0L && it != Long.MAX_VALUE },
            pendingCount = events.count { it.state == ProactiveEvent.PENDING },
            discardedCount = events.count { it.state == ProactiveEvent.DISCARDED },
            lastBatchesSeen = review.lastBatchesSeen(),
            lastFailureReason = review.lastFailureReason(),
            notificationPermissionGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                app, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
}
