package com.jarvys.agent.tasks.ui

import android.content.Context
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.jarvys.agent.tasks.ScheduledTask
import com.jarvys.agent.tasks.TaskGlobalPauseStore
import com.jarvys.agent.tasks.TaskManualRunWorker
import com.jarvys.agent.tasks.TaskNotifier
import com.jarvys.agent.tasks.TaskRepository
import com.jarvys.agent.tasks.TaskRunLedger
import com.jarvys.agent.tasks.TaskRunRecord
import com.jarvys.agent.tasks.TaskScheduler
import com.jarvys.agent.tasks.TaskWorkNames
import com.jarvys.agent.proactive.ProactiveWorkNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class TasksScreenSnapshot(
    val tasks: List<ScheduledTask>,
    val runsByTask: Map<String, List<TaskRunRecord>>,
    val taskDiagnostics: Int,
    val runDiagnostics: Int,
    val allPaused: Boolean,
    val notificationAllowed: Boolean,
    val batteryOptimizationIgnored: Boolean,
)

internal sealed interface TasksScreenLoad {
    data object Loading : TasksScreenLoad
    data class Ready(val snapshot: TasksScreenSnapshot) : TasksScreenLoad
    data class Failed(val cause: Throwable) : TasksScreenLoad
}

internal suspend fun loadTasksScreen(context: Context, repository: TaskRepository,
                                     ledger: TaskRunLedger): TasksScreenLoad = withContext(Dispatchers.IO) {
    try {
        val tasks = repository.list()
        val runs = ledger.read()
        val app = context.applicationContext
        val power = app.getSystemService(PowerManager::class.java)
        TasksScreenLoad.Ready(TasksScreenSnapshot(
            tasks = tasks,
            runsByTask = runs.runs.groupBy { it.taskId },
            taskDiagnostics = repository.readDiagnostics(),
            runDiagnostics = runs.ignoredCorruptRows,
            allPaused = TaskGlobalPauseStore(app).isPaused(),
            notificationAllowed = TaskNotifier.notificationPermissionGranted(app) &&
                    NotificationManagerCompat.from(app).areNotificationsEnabled(),
            batteryOptimizationIgnored = power?.isIgnoringBatteryOptimizations(app.packageName) ?: true,
        ))
    } catch (failure: RuntimeException) {
        TasksScreenLoad.Failed(failure)
    }
}

internal data class TaskWorkObservation(
    val tickRunning: Boolean,
    val proactiveRunning: Boolean,
    val manualTaskIds: Set<String>,
) {
    fun isTaskBusy(taskId: String): Boolean = tickRunning || proactiveRunning || taskId in manualTaskIds
}

internal fun taskWorkObservation(infos: List<List<WorkInfo>>): TaskWorkObservation {
    fun running(rows: List<WorkInfo>) = rows.any { it.state == WorkInfo.State.RUNNING }
    val manualIds = infos.getOrNull(3).orEmpty().asSequence()
        .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
        .mapNotNull { info -> info.tags.firstOrNull { it.startsWith(TaskManualRunWorker.TAG_TASK_PREFIX) }
            ?.removePrefix(TaskManualRunWorker.TAG_TASK_PREFIX) }
        .toSet()
    return TaskWorkObservation(
        tickRunning = running(infos.getOrNull(0).orEmpty()),
        proactiveRunning = running(infos.getOrNull(1).orEmpty()) || running(infos.getOrNull(2).orEmpty()),
        manualTaskIds = manualIds,
    )
}

internal fun workInfosForTaskUi(context: Context) = listOf(
    WorkManager.getInstance(context.applicationContext).getWorkInfosForUniqueWorkFlow(TaskWorkNames.TICK),
    WorkManager.getInstance(context.applicationContext).getWorkInfosForUniqueWorkFlow(ProactiveWorkNames.PERIODIC),
    WorkManager.getInstance(context.applicationContext).getWorkInfosForUniqueWorkFlow(ProactiveWorkNames.RUN_NOW),
    WorkManager.getInstance(context.applicationContext).getWorkInfosByTagFlow(TaskManualRunWorker.TAG_ALL_MANUAL_RUNS),
)
