package com.jarvys.agent.tasks

import com.jarvys.agent.EffectInvocation
import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.workspaces.WorkspaceIdentity
import org.json.JSONObject

enum class TaskPendingActionExecutionStatus {
    EXECUTED, WAITING_VISIBLE_APP, INTERRUPTED_UNCERTAIN, STALE, NOT_PENDING, NOT_FOUND,
}

/** Single-use approval boundary. CLAIMED is durable before any real tool can run. */
class TaskPendingActionExecution(
    private val context: Context,
    private val store: TaskPendingActionStore,
    private val currentTask: (String) -> ScheduledTask?,
    private val dismissNotification: (String) -> Unit = { TaskNotifier.dismissPendingAction(context, it) },
    private val onVisibleAppRequired: (ScheduledTask, TaskPendingAction) -> Unit = { _, _ -> },
    private val onVisibleAppActionExecuted: (ScheduledTask, TaskPendingAction) -> Unit = { _, _ -> },
) {
    fun execute(
        actionId: String,
        taskId: String,
        executor: TaskPendingActionExecutor,
    ): TaskPendingActionExecutionStatus {
        val pending = store.get(actionId) ?: return TaskPendingActionExecutionStatus.NOT_FOUND
        if (pending.taskId != taskId) return TaskPendingActionExecutionStatus.NOT_FOUND
        val attemptId = java.util.UUID.randomUUID().toString()
        val classification = currentTask(taskId)?.let { task ->
            runCatching { executor.actionEffectClassification(context, task, pending) }.getOrNull()
        }
        val excludesExternalEvidence = classification in setOf(TaskActionEffectClassification.READ, TaskActionEffectClassification.MEMORY)
        if (!excludesExternalEvidence) recordEvidence(pending, attemptId, "intent", "approval_attempt")
        fun finishAttempt(action: TaskPendingAction, attempt: String, outcome: String, state: String,
                          actionStatus: String? = null, authorization: String? = null) {
            if (!excludesExternalEvidence) finishEvidence(action, attempt, outcome, state, actionStatus, authorization)
        }

        try {
        return when (val claim = store.claim(actionId, currentTask(taskId), attemptId, classification?.name)) {
            is TaskPendingActionClaim.Stale -> {
                finishAttempt(pending, attemptId, "not_executed", "stale_action")
                dismissNotification(actionId)
                TaskPendingActionExecutionStatus.STALE
            }
            is TaskPendingActionClaim.NotPending -> {
                finishAttempt(pending, attemptId, "not_executed", "duplicate_attempt",
                    claim.action?.status?.name)
                if (claim.action?.status != TaskPendingActionStatus.PENDING) dismissNotification(actionId)
                TaskPendingActionExecutionStatus.NOT_PENDING
            }
            is TaskPendingActionClaim.Claimed -> {
                val action = claim.action
                val resumedAfterVisibleWait = claim.wasWaitingForVisibleApp
                val task = currentTask(taskId)
                if (task == null || !action.isCurrentFor(task)) {
                    store.markInterruptedUncertain(actionId, action.actionBinding,
                        uncertainReceipt("task_changed_before_dispatch"))
                    finishAttempt(action, attemptId, "not_executed", "task_changed_before_dispatch")
                    dismissNotification(actionId)
                    return TaskPendingActionExecutionStatus.INTERRUPTED_UNCERTAIN
                }
                val invocation = EffectInvocation { admission ->
                    check(!excludesExternalEvidence) { "Task capability changed from READ/MEMORY to external WRITE; no write intent exists" }
                    ChatDatabaseBridge.getInstance(context).personalAgentAdmitEpisodeToolEvidenceSync(
                        WorkspaceIdentity.CENTRAL_SESSION, attemptId,
                        admission.authorization.name.lowercase(java.util.Locale.ROOT),
                        evidenceFacts(action, attemptId, "started"), attemptId, runId = attemptId,
                        connectionObservation = admission.connectionObservation,
                    )
                }
                val result = try {
                    executor.execute(context, task, action, invocation)
                } catch (_: Exception) {
                    val effect = invocation.resolveFailure(executor.hasCompleteEffectAdmissionCoverage())
                    invocation.seal()
                    store.markInterruptedUncertain(actionId, action.actionBinding,
                        uncertainReceipt("tool_execution_interrupted"))
                    finishAttempt(action, attemptId, effect.outcome.name.lowercase(java.util.Locale.ROOT),
                        "tool_execution_interrupted", authorization = effect.admission?.authorization?.takeUnless { it == EffectInvocation.Authorization.UNSPECIFIED }?.name?.lowercase(java.util.Locale.ROOT))
                    dismissNotification(actionId)
                    return TaskPendingActionExecutionStatus.INTERRUPTED_UNCERTAIN
                }
                val effect = invocation.resolve(result, executor.hasCompleteEffectAdmissionCoverage())
                val consistentAdministrativeResult = result.success && (result.effectReceipt == null ||
                    effect.outcome == EffectInvocation.Outcome.NOT_EXECUTED)
                val readCompleted = consistentAdministrativeResult && invocation.ownsReadCompletion(result.administrativeReceipt)
                val memoryCompleted = consistentAdministrativeResult && invocation.ownsMemoryCompletion(result.administrativeReceipt)
                val administrativeKind = when {
                    memoryCompleted -> EffectInvocation.AdministrativeKind.MEMORY_COMPLETED
                    readCompleted -> EffectInvocation.AdministrativeKind.READ_COMPLETED
                    else -> null
                }
                invocation.seal()
                val receipt = JSONObject().put("success", result.success).put("content", result.content)
                    .put("admission_receipt_version", 1)
                    .put("read_completed", readCompleted)
                    .put("administrative_kind", administrativeKind?.name)
                    .put("effect_outcome", effect.outcome.name)
                    .put("authorization", effect.admission?.authorization?.name).toString()
                var receiptDurable = true
                val status = runCatching {
                    if (administrativeKind != null) store.markExecuted(actionId, action.actionBinding, receipt)?.status
                    else when (effect.outcome) {
                        EffectInvocation.Outcome.DEFERRED -> store.waitForVisibleApp(actionId, action.actionBinding, receipt)?.status
                        EffectInvocation.Outcome.OK -> store.markExecuted(actionId, action.actionBinding, receipt)?.status
                        else -> store.markInterruptedUncertain(actionId, action.actionBinding, receipt)?.status
                    }
                }.getOrElse {
                    receiptDurable = false
                    runCatching { store.markInterruptedUncertain(actionId, action.actionBinding,
                        uncertainReceipt("result_receipt_not_durable")) }.getOrNull()?.status
                }
                val evidenceOutcome = if (receiptDurable && status != null)
                    effect.outcome.name.lowercase(java.util.Locale.ROOT) else "uncertain"
                finishAttempt(action, attemptId, evidenceOutcome,
                    when (administrativeKind) {
                        EffectInvocation.AdministrativeKind.READ_COMPLETED -> "read_only"
                        EffectInvocation.AdministrativeKind.MEMORY_COMPLETED -> "memory_only"
                        null -> status?.name ?: "receipt_unavailable"
                    },
                    authorization = effect.admission?.authorization?.takeUnless { it == EffectInvocation.Authorization.UNSPECIFIED }?.name?.lowercase(java.util.Locale.ROOT))
                dismissNotification(actionId)
                when (status) {
                    TaskPendingActionStatus.WAITING_VISIBLE_APP -> {
                        onVisibleAppRequired(task, action)
                        TaskPendingActionExecutionStatus.WAITING_VISIBLE_APP
                    }
                    TaskPendingActionStatus.EXECUTED -> {
                        if (resumedAfterVisibleWait) onVisibleAppActionExecuted(task, action)
                        TaskPendingActionExecutionStatus.EXECUTED
                    }
                    else -> TaskPendingActionExecutionStatus.INTERRUPTED_UNCERTAIN
                }
            }
        }
        } finally {
            if (!excludesExternalEvidence) runCatching { ChatDatabaseBridge.getInstance(context).personalAgentReconcileEpisodeRunSync(attemptId) }
        }
    }

    private fun uncertainReceipt(reason: String) = JSONObject()
        .put("status", TaskPendingActionStatus.INTERRUPTED_UNCERTAIN.name)
        .put("reason", reason)
        .toString()

    private fun recordEvidence(action: TaskPendingAction, attemptId: String, outcome: String, state: String) {
        val facts = evidenceFacts(action, attemptId, state)
        ChatDatabaseBridge.getInstance(context).personalAgentRecordEpisodeToolStartedSync(
            WorkspaceIdentity.CENTRAL_SESSION,
            episodeSourceMessageId(action),
            attemptId,
            action.toolName,
            null,
            null,
            facts,
            outcome,
            action.id,
            attemptId,
            runId = attemptId, processId = com.jarvys.agent.crew.CrewProcessIdentity.ID,
        )
    }

    private fun finishEvidence(action: TaskPendingAction, attemptId: String, outcome: String,
                               state: String, actionStatus: String? = null, authorization: String? = null) {
        ChatDatabaseBridge.getInstance(context).personalAgentFinishEpisodeToolEvidenceSync(
            WorkspaceIdentity.CENTRAL_SESSION,
            attemptId,
            outcome,
            evidenceFacts(action, attemptId, state, actionStatus),
            attemptId, runId = attemptId, authorization = authorization,
        )
    }

    private fun evidenceFacts(action: TaskPendingAction, attemptId: String, state: String,
                              actionStatus: String? = null) = JSONObject()
        .put("classification", "WRITE")
        .put("action_id", action.id)
        .put("attempt_id", attemptId)
        .put("action_binding", action.actionBinding)
        .put("task_id", action.taskId)
        .put("occurrence_id", action.occurrenceId)
        .put("state", state)
        .put("action_status", actionStatus)
        .toString()

    private fun episodeSourceMessageId(action: TaskPendingAction) = "task:${action.taskId}:${action.occurrenceId}"
}

/** Notification and detail approvals are unique WorkManager requests, independent of task runs. */
object TaskPendingActionWork {
    fun enqueue(context: Context, action: TaskPendingAction): Boolean = enqueue(context, action, ExistingWorkPolicy.KEEP)

    private fun enqueue(context: Context, action: TaskPendingAction, policy: ExistingWorkPolicy): Boolean = runCatching {
        val request = androidx.work.OneTimeWorkRequest.Builder(TaskPendingActionWorker::class.java)
            .setInputData(Data.Builder()
                .putString(TaskPendingActionWorker.KEY_ACTION_ID, action.id)
                .putString(TaskPendingActionWorker.KEY_TASK_ID, action.taskId)
                .build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "$WORK_NAME_PREFIX${action.id}", policy, request)
        true
    }.getOrDefault(false)

    /** Requeues already-approved module calls when an Activity gives them a visible WebView host. */
    fun resumeWaitingForVisibleApp(context: Context) {
        TaskPendingActionStore(context.applicationContext).waitingForVisibleApp()
            .forEach { enqueue(context, it, ExistingWorkPolicy.REPLACE) }
    }

    internal fun execution(
        context: Context,
        pendingStore: TaskPendingActionStore,
        taskStore: TaskStore = TaskStore(context.applicationContext),
        notifications: ScheduledTaskNotifications = TaskNotifier,
        now: () -> Long = System::currentTimeMillis,
    ): TaskPendingActionExecution = TaskPendingActionExecution(
        context = context.applicationContext,
        store = pendingStore,
        currentTask = taskStore::get,
        onVisibleAppRequired = { task, _ ->
            val current = taskStore.get(task.id)
            if (current != null && current.revision == task.revision) {
                val cause = TaskRunCause.VISIBLE_APP_REQUIRED
                runCatching {
                    taskStore.updateRuntimeSnapshot(
                        current.copy(
                            state = TaskState.NeedsAttention(cause.code),
                            nextRunAt = null,
                            openIncidentCause = cause,
                            updatedAt = maxOf(current.updatedAt, now()),
                        ),
                        current.revision,
                        current,
                    )
                }.onSuccess { notifications.attention(context.applicationContext, it, cause) }
            }
        },
        onVisibleAppActionExecuted = { task, action ->
            val current = taskStore.get(task.id)
            if (current != null && current.revision == task.revision &&
                current.openIncidentCause == TaskRunCause.VISIBLE_APP_REQUIRED) {
                val completedAt = maxOf(current.updatedAt, now())
                val resumedState = if (action.resumeState == "PAUSED") TaskState.Paused else TaskState.Active
                val nextRun = if (resumedState == TaskState.Active) {
                    ScheduleCalculator().nextRunAfter(current.copy(state = TaskState.Active), completedAt)
                } else current.nextRunAt
                runCatching {
                    taskStore.updateRuntimeSnapshot(
                        current.copy(
                            state = resumedState,
                            nextRunAt = nextRun,
                            openIncidentCause = null,
                            lastIncidentRecoveredAt = completedAt,
                            updatedAt = completedAt,
                        ),
                        current.revision,
                        current,
                    )
                }.onSuccess { notifications.recovered(context.applicationContext, it) }
            }
        },
    )

    internal const val WORK_NAME_PREFIX = "scheduled-task-approved-action:"
}

class TaskPendingActionWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        try {
            com.jarvys.agent.SingleSessionReset.awaitReadyBlocking(applicationContext.filesDir)
        } catch (failure: Exception) {
            com.jarvys.agent.AgentErrorReporter.reportBackgroundFailure(applicationContext, failure)
            return Result.retry()
        }
        val actionId = inputData.getString(KEY_ACTION_ID)?.takeIf(String::isNotBlank) ?: return Result.failure()
        val taskId = inputData.getString(KEY_TASK_ID)?.takeIf(String::isNotBlank) ?: return Result.failure()
        val app = applicationContext
        val provider = app as? TaskPendingActionHandlerProvider
        val executor = provider?.taskPendingActionExecutor()
            ?: TaskPendingActionExecutor { _, _, _ -> error("Task action executor is unavailable") }
        val pendingStore = TaskPendingActionStore(app)
        val pending = pendingStore.get(actionId)
        val taskStore = TaskStore(app)
        val status = TaskPendingActionWork.execution(app, pendingStore, taskStore)
            .execute(actionId, taskId, executor)
        if (status == TaskPendingActionExecutionStatus.WAITING_VISIBLE_APP &&
            com.jarvys.agent.modules.ModuleHostVisibility.isVisible()) {
            // Close the race where the Activity resumed just before WAITING_VISIBLE_APP was persisted.
            TaskPendingActionWork.resumeWaitingForVisibleApp(app)
        }
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
