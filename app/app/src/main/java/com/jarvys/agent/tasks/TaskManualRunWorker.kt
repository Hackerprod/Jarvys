package com.jarvys.agent.tasks

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.jarvys.agent.CancellationToken
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

/** Enqueues one isolated manual run per task; scheduled tick work keeps its own unique name and identity. */
object TaskManualRunScheduler {
    fun uniqueWorkName(taskId: String) = "jarvys_task_manual_$taskId"

    fun isActive(context: Context, taskId: String): Boolean = runCatching {
        WorkManager.getInstance(context.applicationContext).getWorkInfosForUniqueWork(uniqueWorkName(taskId)).get()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
    }.getOrDefault(false)

    fun enqueue(context: Context, taskId: String, occurrenceId: String = "manual:$taskId:${UUID.randomUUID()}",
                expectedTask: ScheduledTask? = null) {
        require(occurrenceId.startsWith("manual:$taskId:"))
        require(expectedTask == null || expectedTask.id == taskId) { "Manual task target changed" }
        val runId = occurrenceId.substringAfterLast(':')
        val request = OneTimeWorkRequest.Builder(TaskManualRunWorker::class.java)
            .addTag(TaskManualRunWorker.TAG_ALL_MANUAL_RUNS)
            .addTag(TaskManualRunWorker.TAG_TASK_PREFIX + taskId)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder()
                .putString(TaskManualRunWorker.KEY_TASK_ID, taskId)
                .putString(TaskManualRunWorker.KEY_OCCURRENCE_ID, occurrenceId)
                .putString(TaskManualRunWorker.KEY_RUN_ID, runId)
                .putLong(TaskManualRunWorker.KEY_SCHEDULED_FOR, System.currentTimeMillis())
                .apply {
                    // Agent create-and-test is bound to the reviewed task. Human recovery keeps
                    // its existing behavior, without inventing a stored policy approval grant.
                    if (expectedTask != null) {
                        putLong(TaskManualRunWorker.KEY_EXPECTED_REVISION, expectedTask.revision)
                        putString(TaskManualRunWorker.KEY_EXPECTED_BINDING,
                            TaskActionBinding.task("", "manual", expectedTask))
                    }
                }
                .build()).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueWorkName(taskId), ExistingWorkPolicy.KEEP, request)
        TaskDataChanges.invalidate()
    }
}

class TaskManualRunWorker @JvmOverloads constructor(
    appContext: Context,
    params: WorkerParameters,
    private val processor: ScheduledTaskProcessor? = null,
    private val tasks: TaskStore = TaskStore(appContext.applicationContext),
    private val runs: TaskRunLedger = TaskRunLedger(appContext.applicationContext),
    private val clock: Clock = Clock.systemUTC(),
    private val pendingActions: TaskPendingActionStore = TaskPendingActionStore(appContext.applicationContext),
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        try {
            com.jarvys.agent.SingleSessionReset.awaitReady(applicationContext)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            com.jarvys.agent.AgentErrorReporter.reportBackgroundFailure(applicationContext, failure)
            return Result.retry()
        }
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val occurrenceId = inputData.getString(KEY_OCCURRENCE_ID) ?: return Result.failure()
        val scheduledFor = inputData.getLong(KEY_SCHEDULED_FOR, clock.millis())
        if (!occurrenceId.startsWith("manual:$taskId:")) return Result.failure()
        val runId = inputData.getString(KEY_RUN_ID)?.takeIf(String::isNotBlank)
            ?: occurrenceId.substringAfterLast(':')
        val task = tasks.get(taskId)
        if (task == null) {
            saveRejectedRun(taskId, runId, occurrenceId, scheduledFor, TaskRunCause.TASK_CHANGED)
            TaskScheduler.rearm(applicationContext)
            return Result.success()
        }
        val expectedBinding = inputData.getString(KEY_EXPECTED_BINDING)
        val expectedRevision = inputData.getLong(KEY_EXPECTED_REVISION, -1L)
        if (expectedBinding != null || expectedRevision != -1L) {
            // A changed queued task is terminal, never retried with a newer scope or payload.
            if (expectedRevision != task.revision || expectedBinding != TaskActionBinding.task("", "manual", task)) {
                saveRejectedRun(taskId, runId, occurrenceId, scheduledFor,
                    if (expectedBinding == null) TaskRunCause.EXECUTION_FAILED else TaskRunCause.TASK_CHANGED)
                TaskScheduler.rearm(applicationContext)
                return Result.failure()
            }
        }
        if (pendingActions.stateForTask(task.id, task.revision, task.state) == TaskState.AwaitingUser) {
            saveRejectedRun(taskId, runId, occurrenceId, scheduledFor, TaskRunCause.AWAITING_USER)
            TaskScheduler.rearm(applicationContext)
            return Result.success()
        }
        val queued = TaskRunRecord(task.id, runId, scheduledFor, scheduledFor, status = "QUEUED",
            deliveryStatus = "NOT_APPLICABLE", manualOccurrenceId = occurrenceId)
        runs.appendIfAbsent(queued)
        runs.saveSnapshot(queued.copy(startedAt = clock.millis(), status = "RUNNING"))
        val started = clock.millis()
        val token = CancellationToken.cancellable()
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { token.cancel() }
        val result = try {
            (processor ?: ScheduledTaskProcessor(applicationContext))
                .executeManual(task, scheduledFor, started, runId, occurrenceId, token)
        } finally {
            cancellation.dispose()
        }
        val record = TaskRunRecord(task.id, runId, scheduledFor, started,
            clock.millis(), result.status, result.deliveryStatus, result.reason, result.skipReason,
            result.notified, result.toolsCalled, result.errorCause, result.model, result.usageTokens,
            manualOccurrenceId = occurrenceId, causeCode = result.causeCode, causeDetail = result.causeDetail,
            deliveredResult = result.deliveredResult, conversationMessageId = result.conversationMessageId)
        if (result.status == "RETRYABLE" || result.status == "INTERRUPTED") {
            runs.saveSnapshot(record.copy(status = "RETRYABLE"))
            return Result.retry()
        }
        runs.saveSnapshot(record)
        val current = tasks.get(task.id)
        if (current != null && current.revision == task.revision) {
            val nextState = when (result.status) {
                "ERROR" -> TaskState.NeedsAttention((result.causeCode ?: TaskRunCause.EXECUTION_FAILED).code)
                "PARTIAL" -> TaskState.NeedsAttention((result.causeCode ?: TaskRunCause.EFFECT_OCCURRED).code)
                "OK", "SILENT", "NO_RESULT" -> if (current.state is TaskState.NeedsAttention) TaskState.Active else current.state
                else -> current.state
            }
            val autoDeleteAfterRun = task.deleteAfterRun && result.status in setOf("OK", "SILENT", "NO_RESULT")
            val pendingApproval = pendingActions.stateForTask(current.id, current.revision, current.state) ==
                TaskState.AwaitingUser
            val updated = current.copy(
                state = if (autoDeleteAfterRun) TaskState.Done else nextState,
                nextRunAt = when {
                    autoDeleteAfterRun -> null
                    nextState == TaskState.Active && current.state is TaskState.NeedsAttention ->
                        ScheduleCalculator(clock).nextRunAfter(current, clock.millis())
                    else -> current.nextRunAt
                },
                lastRun = TaskLastRun(runId, scheduledFor, started, clock.millis(), result.status,
                    result.deliveryStatus, result.causeCode),
                updatedAt = clock.millis(),
            )
            if (pendingApproval) {
                // Preserve the reviewed revision until its queued action is resolved. The resolver
                // performs deleteAfterRun cleanup after the final approval or rejection.
                runCatching {
                    tasks.updateRuntimeSnapshot(updated, current.revision, current)
                }
            } else if (autoDeleteAfterRun) {
                runCatching { tasks.delete(task.id, current.revision, clock.millis(), expectedTask = current) }
            } else if (nextState != current.state) {
                runCatching { tasks.update(updated, current.revision) }
            }
        }
        TaskScheduler.rearm(applicationContext)
        return Result.success()
    }

    private fun saveRejectedRun(taskId: String, runId: String, occurrenceId: String,
                                scheduledFor: Long, cause: TaskRunCause) {
        val existing = runs.forTask(taskId).firstOrNull { it.idempotencyKey == occurrenceId }
        val rejected = (existing ?: TaskRunRecord(taskId, runId, scheduledFor, scheduledFor,
            manualOccurrenceId = occurrenceId)).copy(
            finishedAt = clock.millis(), status = "REJECTED", deliveryStatus = "NOT_APPLICABLE",
            reason = null, skipReason = null, notified = false, toolsCalled = emptyList(),
            errorCause = null, causeCode = cause, causeDetail = null,
            deliveredResult = null, conversationMessageId = null,
        )
        if (existing != null) {
            runs.saveSnapshot(rejected)
        } else if (!runs.appendIfAbsent(rejected)) {
            runs.forTask(taskId).firstOrNull { it.idempotencyKey == occurrenceId }?.let { current ->
                runs.saveSnapshot(current.copy(finishedAt = rejected.finishedAt, status = rejected.status,
                    deliveryStatus = rejected.deliveryStatus, causeCode = cause))
            }
        }
    }

    companion object {
        const val TAG_ALL_MANUAL_RUNS = "jarvys_task_manual_run"
        const val TAG_TASK_PREFIX = "jarvys_task_manual_id:"
        const val KEY_TASK_ID = "task_id"
        const val KEY_OCCURRENCE_ID = "occurrence_id"
        const val KEY_RUN_ID = "run_id"
        const val KEY_SCHEDULED_FOR = "scheduled_for"
        const val KEY_EXPECTED_REVISION = "expected_revision"
        const val KEY_EXPECTED_BINDING = "expected_binding"
    }
}
