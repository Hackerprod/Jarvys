package com.jarvys.agent.tasks

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject

enum class TaskPendingActionExecutionStatus { EXECUTED, INTERRUPTED_UNCERTAIN, STALE, NOT_PENDING, NOT_FOUND }

/** Single-use approval boundary. CLAIMED is durable before any real tool can run. */
class TaskPendingActionExecution(
    private val context: Context,
    private val store: TaskPendingActionStore,
    private val currentTask: (String) -> ScheduledTask?,
    private val dismissNotification: (String) -> Unit = { TaskNotifier.dismissPendingAction(context, it) },
) {
    fun execute(
        actionId: String,
        taskId: String,
        executor: TaskPendingActionExecutor,
    ): TaskPendingActionExecutionStatus {
        val pending = store.get(actionId) ?: return TaskPendingActionExecutionStatus.NOT_FOUND
        if (pending.taskId != taskId) return TaskPendingActionExecutionStatus.NOT_FOUND

        return when (val claim = store.claim(actionId, currentTask(taskId))) {
            is TaskPendingActionClaim.Stale -> {
                dismissNotification(actionId)
                TaskPendingActionExecutionStatus.STALE
            }
            is TaskPendingActionClaim.NotPending -> {
                if (claim.action?.status != TaskPendingActionStatus.PENDING) dismissNotification(actionId)
                TaskPendingActionExecutionStatus.NOT_PENDING
            }
            is TaskPendingActionClaim.Claimed -> {
                val action = claim.action
                val task = currentTask(taskId)
                if (task == null || !action.isCurrentFor(task)) {
                    store.markInterruptedUncertain(actionId, action.actionBinding,
                        uncertainReceipt("task_changed_before_dispatch"))
                    dismissNotification(actionId)
                    return TaskPendingActionExecutionStatus.INTERRUPTED_UNCERTAIN
                }
                val result = try {
                    executor.execute(context, task, action)
                } catch (_: Exception) {
                    store.markInterruptedUncertain(actionId, action.actionBinding,
                        uncertainReceipt("tool_execution_interrupted"))
                    dismissNotification(actionId)
                    return TaskPendingActionExecutionStatus.INTERRUPTED_UNCERTAIN
                }
                val receipt = JSONObject().put("success", result.success).put("content", result.content).toString()
                val status = runCatching {
                    if (result.success) store.markExecuted(actionId, action.actionBinding, receipt)?.status
                    else store.markInterruptedUncertain(actionId, action.actionBinding,
                        JSONObject().put("success", false).put("content", result.content)
                            .put("status", TaskPendingActionStatus.INTERRUPTED_UNCERTAIN.name).toString())?.status
                }.getOrElse {
                    runCatching { store.markInterruptedUncertain(actionId, action.actionBinding,
                        uncertainReceipt("result_receipt_not_durable")) }.getOrNull()?.status
                }
                dismissNotification(actionId)
                if (status == TaskPendingActionStatus.EXECUTED) TaskPendingActionExecutionStatus.EXECUTED
                else TaskPendingActionExecutionStatus.INTERRUPTED_UNCERTAIN
            }
        }
    }

    private fun uncertainReceipt(reason: String) = JSONObject()
        .put("status", TaskPendingActionStatus.INTERRUPTED_UNCERTAIN.name)
        .put("reason", reason)
        .toString()
}

/** Notification and detail approvals are unique WorkManager requests, independent of task runs. */
object TaskPendingActionWork {
    fun enqueue(context: Context, action: TaskPendingAction): Boolean = runCatching {
        val request = androidx.work.OneTimeWorkRequest.Builder(TaskPendingActionWorker::class.java)
            .setInputData(Data.Builder()
                .putString(TaskPendingActionWorker.KEY_ACTION_ID, action.id)
                .putString(TaskPendingActionWorker.KEY_TASK_ID, action.taskId)
                .build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "$WORK_NAME_PREFIX${action.id}", ExistingWorkPolicy.KEEP, request)
        true
    }.getOrDefault(false)

    internal const val WORK_NAME_PREFIX = "scheduled-task-approved-action:"
}

class TaskPendingActionWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val actionId = inputData.getString(KEY_ACTION_ID)?.takeIf(String::isNotBlank) ?: return Result.failure()
        val taskId = inputData.getString(KEY_TASK_ID)?.takeIf(String::isNotBlank) ?: return Result.failure()
        val app = applicationContext
        val provider = app as? TaskPendingActionHandlerProvider
        val executor = provider?.taskPendingActionExecutor()
            ?: TaskPendingActionExecutor { _, _, _ -> error("Task action executor is unavailable") }
        val pendingStore = TaskPendingActionStore(app)
        val pending = pendingStore.get(actionId)
        val status = TaskPendingActionExecution(app, pendingStore, TaskStore(app)::get)
            .execute(actionId, taskId, executor)
        if (status == TaskPendingActionExecutionStatus.STALE && pending?.taskId == taskId) {
            TaskStore(app).get(taskId)?.let { current -> TaskNotifier.pendingActionExpired(app, current, pending) }
        }
        TaskScheduler.pendingActionResolved(app, taskId)
        return Result.success()
    }

    companion object {
        const val KEY_ACTION_ID = "pending_action_id"
        const val KEY_TASK_ID = "scheduled_task_id"
    }
}
