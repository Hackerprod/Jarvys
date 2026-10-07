package com.jarvys.agent.tasks.ui

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.jarvys.agent.JarvysGroup
import com.jarvys.agent.JarvysListRow
import com.jarvys.agent.JarvysSectionLabel
import com.jarvys.agent.JarvysTag
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R
import com.jarvys.agent.ScrollableDialogContent
import com.jarvys.agent.proactive.ProactiveWorkNames
import com.jarvys.agent.tasks.ScheduledTask
import com.jarvys.agent.tasks.TaskDataChanges
import com.jarvys.agent.tasks.TaskDelivery
import com.jarvys.agent.tasks.TaskManualRunScheduler
import com.jarvys.agent.tasks.TaskRepository
import com.jarvys.agent.tasks.TaskRunLedger
import com.jarvys.agent.tasks.TaskRunRecord
import com.jarvys.agent.tasks.TaskSchedule
import com.jarvys.agent.tasks.TaskSchedulePresentation
import com.jarvys.agent.tasks.TaskScheduler
import com.jarvys.agent.tasks.TaskState
import com.jarvys.agent.tasks.TaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TasksListScreen(
    context: Context,
    repository: TaskRepository = remember(context) { TaskRepository(context.applicationContext) },
    ledger: TaskRunLedger = remember(context) { TaskRunLedger(context.applicationContext) },
    onOpenTask: (String) -> Unit,
    onRequestChange: (ScheduledTask) -> Unit,
    onConfigureProvider: () -> Unit,
    onMessage: (String) -> Unit,
    onReturnToSettings: () -> Unit,
    pauseAll: (Boolean) -> Unit = { TaskScheduler.setAllPaused(context.applicationContext, it) },
    enqueueManualRun: (String) -> Unit = { TaskManualRunScheduler.enqueue(context.applicationContext, it) },
    healthIssuesOverride: ((List<ScheduledTask>) -> List<TaskHealthIssue>)? = null,
    backgroundWorkBusyForAll: Boolean? = null,
) {
    val revision by TaskDataChanges.revision.collectAsState()
    var load by remember { mutableStateOf<TasksScreenLoad>(TasksScreenLoad.Loading) }
    val app = context.applicationContext
    LaunchedEffect(revision, repository, ledger) { load = loadTasksScreen(app, repository, ledger) }
    val workState = if (backgroundWorkBusyForAll != null) {
        TaskWorkObservation(backgroundWorkBusyForAll, backgroundWorkBusyForAll, emptySet())
    } else rememberTaskWorkObservation(app)
    val scope = rememberCoroutineScope()
    var taskToDelete by remember { mutableStateOf<ScheduledTask?>(null) }

    when (val current = load) {
        TasksScreenLoad.Loading -> Column(Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        is TasksScreenLoad.Failed -> Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text(stringResource(R.string.tasks_load_error), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { load = TasksScreenLoad.Loading }) { Text(stringResource(R.string.tasks_retry)) }
        }
        is TasksScreenLoad.Ready -> {
            val snapshot = current.snapshot
            if (snapshot.tasks.isEmpty()) {
                LaunchedEffect(revision) {
                    onMessage(app.getString(R.string.tasks_empty_returned))
                    onReturnToSettings()
                }
                return
            }
            val now = System.currentTimeMillis()
            val delayed = if (snapshot.allPaused) emptyList() else snapshot.tasks.filter { task ->
                val runInProgress = workState.tickRunning || task.id in workState.manualTaskIds ||
                        snapshot.runsByTask[task.id].orEmpty().any { it.status == "STARTED" }
                TaskUiProjection.isDelayed(task, now, runInProgress)
            }
            val health = healthIssuesOverride?.invoke(snapshot.tasks) ?: TaskHealthPolicy.evaluate(TaskHealthInput(
                notificationsAllowed = snapshot.notificationAllowed,
                batteryOptimizationIgnored = snapshot.batteryOptimizationIgnored,
                delayedTasks = delayed,
                attentionTasks = snapshot.tasks.filter { it.state is TaskState.NeedsAttention },
            ))
            val ordered = remember(snapshot.tasks) { orderTasks(snapshot.tasks) }
            val presentation = remember(app) { TaskSchedulePresentation(app) }
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("tasks-list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "global-controls") {
                    GlobalPauseCard(
                        paused = snapshot.allPaused,
                        onToggle = { paused -> scope.launch(Dispatchers.IO) {
                            runCatching { pauseAll(paused) }
                                .onFailure { withContext(Dispatchers.Main) { onMessage(app.getString(R.string.tasks_pause_failed)) } }
                        } },
                    )
                }
                if (health.isNotEmpty()) item(key = "health") {
                    TaskHealthCard(
                        issues = health,
                        presentation = presentation,
                        onOpenNotifications = { launchSettingsIntent(app, TaskUiIntents.notificationSettings(app), onMessage) },
                        onOpenBattery = { launchSettingsIntent(app, TaskUiIntents.batteryOptimizationSettings(), onMessage) },
                        onConfigureProvider = onConfigureProvider,
                        onOpenTask = onOpenTask,
                    )
                }
                if (snapshot.taskDiagnostics + snapshot.runDiagnostics > 0) item(key = "diagnostics") {
                    JarvysGroup {
                        Text(stringResource(R.string.tasks_store_diagnostics), Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        val count = snapshot.taskDiagnostics + snapshot.runDiagnostics
                        Text(app.resources.getQuantityString(R.plurals.tasks_ignored_rows, count, count),
                            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                items(ordered, key = { it.id }) { task ->
                    val manualBusy = task.id in workState.manualTaskIds
                    val sharedBusy = workState.tickRunning || workState.proactiveRunning
                    TaskListItem(
                        task = task,
                        lastRun = snapshot.runsByTask[task.id].orEmpty().maxByOrNull { it.startedAt }
                            ?: task.lastRun?.let { it.toRecord(task.id) },
                        presentation = presentation,
                        manualBusy = manualBusy,
                        backgroundBusy = manualBusy || sharedBusy,
                        onOpen = { onOpenTask(task.id) },
                        onToggle = { activate -> scope.launch(Dispatchers.IO) {
                            runCatching {
                                if (activate) repository.resume(task.id, task.revision)
                                else repository.pause(task.id, task.revision)
                            }.onSuccess {
                                withContext(Dispatchers.Main) { onMessage(app.getString(
                                    if (activate) R.string.tasks_resumed_saved else R.string.tasks_paused_saved)) }
                            }.onFailure { withContext(Dispatchers.Main) { onMessage(app.getString(R.string.tasks_update_failed)) } }
                        } },
                        onRunNow = {
                            if (manualBusy || sharedBusy) onMessage(app.getString(R.string.tasks_manual_already_running))
                            else runCatching { enqueueManualRun(task.id) }
                                .onSuccess { onMessage(app.getString(R.string.tasks_manual_queued)) }
                                .onFailure { onMessage(app.getString(R.string.tasks_manual_failed)) }
                        },
                        onRequestChange = { onRequestChange(task) },
                        onDelete = { taskToDelete = task },
                    )
                }
            }
            taskToDelete?.let { task ->
                val taskRuns = snapshot.runsByTask[task.id].orEmpty()
                val hasHistory = taskRuns.isNotEmpty() || task.lastRun != null
                DeleteTaskDialog(
                    task = task,
                    hasHistory = hasHistory,
                    onDismiss = { taskToDelete = null },
                    onDelete = { keepHistory ->
                        taskToDelete = null
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                val currentTask = repository.get(task.id) ?: return@runCatching
                                repository.delete(task.id, currentTask.revision)
                                if (!keepHistory) ledger.clearTask(task.id)
                            }.onSuccess { withContext(Dispatchers.Main) { onMessage(app.getString(R.string.tasks_deleted)) } }
                                .onFailure { withContext(Dispatchers.Main) { onMessage(app.getString(R.string.tasks_delete_failed)) } }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun GlobalPauseCard(paused: Boolean, onToggle: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    JarvysGroup {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(if (paused) R.string.tasks_resume_all else R.string.tasks_pause_all),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (paused) Text(stringResource(R.string.tasks_all_paused_banner),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = paused, onCheckedChange = onToggle,
                modifier = Modifier.testTag("tasks-global-pause").semantics {
                    contentDescription = context.getString(if (paused) R.string.tasks_resume_all else R.string.tasks_pause_all)
                })
        }
    }
}

@Composable
internal fun TaskHealthCard(
    issues: List<TaskHealthIssue>,
    presentation: TaskSchedulePresentation,
    onOpenNotifications: () -> Unit,
    onOpenBattery: () -> Unit,
    onConfigureProvider: () -> Unit,
    onOpenTask: (String) -> Unit,
) {
    JarvysGroup {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            JarvysSectionLabel(stringResource(R.string.tasks_health_title))
            issues.forEach { issue ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (issue.kind) {
                        TaskHealthKind.NOTIFICATIONS -> {
                            Text(stringResource(R.string.tasks_health_notifications), style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = onOpenNotifications) { Text(stringResource(R.string.tasks_health_open_notifications)) }
                        }
                        TaskHealthKind.BATTERY -> {
                            Text(stringResource(R.string.tasks_health_battery), style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = onOpenBattery) { Text(stringResource(R.string.tasks_health_open_battery)) }
                        }
                        TaskHealthKind.DELAYED -> {
                            val taskName = issue.taskName.orEmpty()
                            val time = issue.scheduledFor?.let { presentation.localTime(it) }.orEmpty()
                            Text(stringResource(R.string.tasks_health_delayed, taskName, time),
                                style = MaterialTheme.typography.bodyMedium)
                            issue.taskId?.let { id -> OutlinedButton(onClick = { onOpenTask(id) }) {
                                Text(stringResource(R.string.tasks_open_delayed))
                            } }
                        }
                        TaskHealthKind.ATTENTION -> {
                            val reason = issue.reason.orEmpty()
                            val providerMissing = reason.contains("provider", ignoreCase = true)
                            Text(stringResource(R.string.tasks_health_attention, issue.taskName.orEmpty(),
                                if (providerMissing) stringResource(R.string.tasks_attention_provider) else reason),
                                style = MaterialTheme.typography.bodyMedium)
                            if (providerMissing) OutlinedButton(onClick = onConfigureProvider) {
                                Text(stringResource(R.string.tasks_health_configure_provider))
                            } else issue.taskId?.let { id ->
                                OutlinedButton(onClick = { onOpenTask(id) }) {
                                    Text(stringResource(R.string.tasks_health_review_task))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskListItem(
    task: ScheduledTask,
    lastRun: TaskRunRecord?,
    presentation: TaskSchedulePresentation,
    manualBusy: Boolean,
    backgroundBusy: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onRequestChange: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var expanded by remember(task.id) { mutableStateOf(false) }
    val stateLabel = taskStateLabel(context, task.state)
    val next = task.nextRunAt?.let { stringResource(R.string.tasks_next_run, presentation.localTime(it, presentation.zoneFor(task.schedule))) }
        ?: stringResource(R.string.tasks_no_next_run)
    val last = lastRun?.let { stringResource(R.string.tasks_last_run, runStatusLabel(context, it.status, it.skipReason)) }
        ?: stringResource(R.string.tasks_status_never)
    val switchActive = task.state == TaskState.Active
    JarvysGroup(Modifier.testTag("task-row-${task.id}")) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(presentation.scheduleHuman(task.schedule), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                JarvysTag(stateLabel)
                IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)
                    .testTag("task-menu-${task.id}").semantics {
                    contentDescription = context.getString(R.string.tasks_task_menu)
                }) { Icon(LucideIcons.Ellipsis, contentDescription = null) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.tasks_run_now)) },
                        enabled = !backgroundBusy,
                        onClick = { expanded = false; onRunNow() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.tasks_request_change)) },
                        onClick = { expanded = false; onRequestChange() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.tasks_delete)) },
                        onClick = { expanded = false; onDelete() })
                }
            }
            Text(next, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(last, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                if (backgroundBusy) Text(stringResource(R.string.tasks_run_in_progress),
                    color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                Switch(
                    checked = switchActive,
                    enabled = task.state != TaskState.Done,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("task-toggle-${task.id}").semantics {
                        contentDescription = context.getString(R.string.tasks_toggle_state)
                    },
                )
            }
        }
    }
}

@Composable
private fun DeleteTaskDialog(
    task: ScheduledTask,
    hasHistory: Boolean,
    onDismiss: () -> Unit,
    onDelete: (keepHistory: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tasks_delete_title)) },
        text = { ScrollableDialogContent {
            Text(stringResource(if (hasHistory) R.string.tasks_delete_history_message else R.string.tasks_delete_message, task.name))
        } },
        confirmButton = {
            if (hasHistory) Row {
                Button(onClick = { onDelete(false) }, modifier = Modifier.testTag("task-delete-clear-history")) {
                    Text(stringResource(R.string.tasks_delete_and_clear))
                }
                TextButton(onClick = { onDelete(true) }, modifier = Modifier.testTag("task-delete-keep-history")) {
                    Text(stringResource(R.string.tasks_delete_and_keep))
                }
            } else Button(onClick = { onDelete(false) }, modifier = Modifier.testTag("task-delete-confirm")) {
                Text(stringResource(R.string.tasks_delete))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tasks_cancel)) } },
    )
}

@Composable
internal fun TaskSettingsEntry(onClick: () -> Unit) {
    JarvysListRow(
        title = stringResource(R.string.tasks_settings_entry),
        subtitle = stringResource(R.string.tasks_settings_entry_summary),
        icon = LucideIcons.Calendar,
        modifier = Modifier.testTag("settings-scheduled-tasks-row"),
        onClick = onClick,
    )
}

private fun taskStateLabel(context: Context, state: TaskState): String = when (state) {
    TaskState.Active -> context.getString(R.string.tasks_state_active)
    TaskState.Paused -> context.getString(R.string.tasks_state_paused)
    TaskState.Done -> context.getString(R.string.tasks_state_done)
    is TaskState.NeedsAttention -> context.getString(R.string.tasks_state_attention)
    TaskState.AwaitingUser -> context.getString(R.string.tasks_state_awaiting)
}

internal fun runStatusLabel(context: Context, status: String, skipReason: String? = null): String = when {
    skipReason == "no_provider" || status == "SKIPPED" && skipReason?.contains("provider", true) == true ->
        context.getString(R.string.tasks_status_no_provider)
    status == "OK" -> context.getString(R.string.tasks_status_ok)
    status == "SILENT" -> context.getString(R.string.tasks_status_silent)
    status == "ERROR" -> context.getString(R.string.tasks_status_error)
    status == "INTERRUPTED" -> context.getString(R.string.tasks_status_interrupted)
    status == "PARTIAL" -> context.getString(R.string.tasks_status_partial)
    status == "SKIPPED" -> context.getString(R.string.tasks_status_skipped)
    status == "RETRYABLE" || status == "STARTED" -> context.getString(R.string.tasks_status_retrying)
    else -> status
}

internal fun orderTasks(tasks: List<ScheduledTask>): List<ScheduledTask> = tasks.sortedWith(
    compareBy<ScheduledTask> {
        when (it.state) {
            TaskState.Active -> 0
            is TaskState.NeedsAttention -> 1
            TaskState.AwaitingUser -> 2
            TaskState.Paused -> 3
            TaskState.Done -> 4
        }
    }.thenBy { if (it.state == TaskState.Active) it.nextRunAt ?: Long.MAX_VALUE else Long.MAX_VALUE }
        .thenBy { it.name.lowercase() }
)

private fun com.jarvys.agent.tasks.TaskLastRun.toRecord(taskId: String) = TaskRunRecord(
    taskId = taskId, runId = runId, scheduledFor = scheduledFor, startedAt = startedAt,
    finishedAt = finishedAt, status = status, deliveryStatus = deliveryStatus,
)

private fun launchSettingsIntent(context: Context, intent: android.content.Intent, onMessage: (String) -> Unit) {
    runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { onMessage(context.getString(R.string.tasks_settings_unavailable)) }
}

@Composable
internal fun rememberTaskWorkObservation(context: Context): TaskWorkObservation {
    val flows = remember(context) { workInfosForTaskUi(context) }
    val combined = remember(flows) {
        combine(flows[0], flows[1], flows[2], flows[3]) { tick, proactive, runNow, manual ->
            listOf(tick, proactive, runNow, manual)
        }
    }
    val infos by combined.collectAsState(initial = List(flows.size) { emptyList() })
    return remember(infos) { taskWorkObservation(infos) }
}
