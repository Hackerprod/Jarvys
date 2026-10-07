package com.jarvys.agent.tasks

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import com.jarvys.agent.CancellationToken
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

/** Enqueues one isolated manual run per task; scheduled tick work keeps its own unique name and identity. */
object TaskManualRunScheduler {
    fun uniqueWorkName(taskId: String) = "jarvys_task_manual_$taskId"

    fun isActive(context: Context, taskId: String): Boolean = runCatching {
        WorkManager.getInstance(context.applicationContext).getWorkInfosForUniqueWork(uniqueWorkName(taskId)).get()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
    }.getOrDefault(false)

    fun enqueue(context: Context, taskId: String, occurrenceId: String = "manual:$taskId:${UUID.randomUUID()}") {
        require(occurrenceId.startsWith("manual:$taskId:"))
        val request = OneTimeWorkRequest.Builder(TaskManualRunWorker::class.java)
            .addTag(TaskManualRunWorker.TAG_ALL_MANUAL_RUNS)
            .addTag(TaskManualRunWorker.TAG_TASK_PREFIX + taskId)
            .setInputData(Data.Builder()
                .putString(TaskManualRunWorker.KEY_TASK_ID, taskId)
                .putString(TaskManualRunWorker.KEY_OCCURRENCE_ID, occurrenceId)
                .putLong(TaskManualRunWorker.KEY_SCHEDULED_FOR, System.currentTimeMillis())
                .build()).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueWorkName(taskId), ExistingWorkPolicy.KEEP, request)
        TaskDataChanges.invalidate()
    }
}

class TaskManualRunWorker @JvmOverloads constructor(
    appContext: Context,
    params: WorkerParameters,
    private val processor: ScheduledTaskProcessor = ScheduledTaskProcessor(appContext.applicationContext),
    private val tasks: TaskStore = TaskStore(appContext.applicationContext),
    private val runs: TaskRunLedger = TaskRunLedger(appContext.applicationContext),
    private val clock: Clock = Clock.systemUTC(),
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val occurrenceId = inputData.getString(KEY_OCCURRENCE_ID) ?: return Result.failure()
        val scheduledFor = inputData.getLong(KEY_SCHEDULED_FOR, clock.millis())
        if (!occurrenceId.startsWith("manual:$taskId:")) return Result.failure()
        val task = tasks.get(taskId) ?: return Result.success()
        val started = clock.millis()
        val token = CancellationToken.cancellable()
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { token.cancel() }
        val result = try {
            processor.executeManual(task, scheduledFor, started, UUID.randomUUID().toString(), occurrenceId, token)
        } finally {
            cancellation.dispose()
        }
        if (result.status == "RETRYABLE" || result.status == "INTERRUPTED") return Result.retry()
        runs.appendIfAbsent(TaskRunRecord(task.id, UUID.randomUUID().toString(), scheduledFor, started,
            clock.millis(), result.status, result.deliveryStatus, result.reason, result.skipReason,
            result.notified, result.toolsCalled, result.errorCause, result.model, result.usageTokens,
            manualOccurrenceId = occurrenceId))
        return Result.success()
    }

    companion object {
        const val TAG_ALL_MANUAL_RUNS = "jarvys_task_manual_run"
        const val TAG_TASK_PREFIX = "jarvys_task_manual_id:"
        const val KEY_TASK_ID = "task_id"
        const val KEY_OCCURRENCE_ID = "occurrence_id"
        const val KEY_SCHEDULED_FOR = "scheduled_for"
    }
}
