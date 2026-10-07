package com.jarvys.agent.tasks.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvys.agent.JarvysGroup
import com.jarvys.agent.JarvysSectionLabel
import com.jarvys.agent.JarvysTag
import com.jarvys.agent.R
import com.jarvys.agent.ScrollableDialogContent
import com.jarvys.agent.tasks.ScheduledTask
import com.jarvys.agent.tasks.TaskDataChanges
import com.jarvys.agent.tasks.TaskDelivery
import com.jarvys.agent.tasks.TaskManualRunScheduler
import com.jarvys.agent.tasks.TaskRepository
import com.jarvys.agent.tasks.TaskRunLedger
import com.jarvys.agent.tasks.TaskRunRecord
import com.jarvys.agent.tasks.TaskSchedulePresentation
import com.jarvys.agent.tasks.TaskState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class TaskDetailData(
    val task: ScheduledTask?,
    val runs: List<TaskRunRecord>,
    val ignoredRows: Int,
)

@Composable
fun TaskDetailScreen(
    context: Context,
    taskId: String,
    repository: TaskRepository,
    ledger: TaskRunLedger,
    onRequestChange: (ScheduledTask) -> Unit,
    onConfigureProvider: () -> Unit,
    onMessage: (String) -> Unit,
    onMissingTask: () -> Unit,
    backgroundWorkBusyForAll: Boolean? = null,
) {
    val revision by TaskDataChanges.revision.collectAsState()
    var detail by remember(taskId) { mutableStateOf<TaskDetailData?>(null) }
    var failed by remember(taskId) { mutableStateOf(false) }
    val app = context.applicationContext
    LaunchedEffect(taskId, revision) {
        failed = false
        detail = withContext(Dispatchers.IO) {
            runCatching {
                val task = repository.get(taskId)
                TaskDetailData(task, ledger.forTask(taskId).sortedWith(
                    compareByDescending<TaskRunRecord> { it.startedAt }.thenByDescending { it.finishedAt ?: Long.MIN_VALUE }),
                    repository.readDiagnostics() + ledger.read().ignoredCorruptRows)
            }.onFailure { failed = true }.getOrNull()
        }
        if (!failed && detail?.task == null) onMissingTask()
    }
    val work = if (backgroundWorkBusyForAll != null) {
        TaskWorkObservation(backgroundWorkBusyForAll, backgroundWorkBusyForAll, emptySet())
    } else rememberTaskWorkObservation(app)
    val scope = rememberCoroutineScope()
    var confirmClear by remember(taskId) { mutableStateOf(false) }

    val loaded = detail
    if (loaded == null) {
        if (failed) Text(stringResource(R.string.tasks_load_error), Modifier.padding(20.dp),
            color = MaterialTheme.colorScheme.error)
        else Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator() }
        return
    }
    val task = loaded.task ?: return
    val presentation = remember(app) { TaskSchedulePresentation(app) }
    val busy = work.isTaskBusy(task.id)
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("task-detail-history-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "task-overview") {
            JarvysGroup {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(task.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold)
                        JarvysTag(taskStateLabelForDetail(app, task.state))
                    }
                    Text(stringResource(R.string.task_detail_schedule), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(presentation.scheduleHuman(task.schedule), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.task_detail_precision_approx),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.tasks_next_run,
                        task.nextRunAt?.let { presentation.localTime(it, presentation.zoneFor(task.schedule)) }
                            ?: app.getString(R.string.tasks_no_next_run)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.task_detail_instruction), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(task.instruction, modifier = Modifier.testTag("task-detail-instruction"),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.task_detail_tools), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(task.toolScope.tools.joinToString(", ").ifBlank { stringResource(R.string.task_detail_no_tools) },
                        style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(if (task.toolScope.web) R.string.task_detail_web_yes else R.string.task_detail_web_no),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(if (task.createdBy.startsWith("AGENT_CHAT:"))
                        R.string.task_detail_origin_chat else R.string.task_detail_created_from_other),
                        style = MaterialTheme.typography.bodyMedium)
                    (task.state as? TaskState.NeedsAttention)?.let { attention ->
                        val providerMissing = attention.reason.contains("provider", ignoreCase = true)
                        Text(stringResource(if (providerMissing) R.string.tasks_attention_provider
                            else R.string.tasks_attention_connectors), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                        if (providerMissing) OutlinedButton(onClick = onConfigureProvider) {
                            Text(stringResource(R.string.tasks_health_configure_provider))
                        }
                    }
                    Text(stringResource(R.string.task_detail_delivery), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(if (task.delivery == TaskDelivery.ALWAYS)
                        R.string.task_management_delivery_always else R.string.task_management_delivery_notable),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.task_detail_expiry), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(task.validUntil?.let { presentation.localTime(it, presentation.zoneFor(task.schedule)) }
                        ?: stringResource(R.string.task_detail_no_expiry), style = MaterialTheme.typography.bodyMedium)
                    if (loaded.ignoredRows > 0) Text(stringResource(R.string.tasks_store_diagnostics),
                        color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                    if (busy) Text(stringResource(R.string.tasks_run_in_progress),
                        color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (busy) onMessage(app.getString(R.string.tasks_manual_already_running))
                            else runCatching { TaskManualRunScheduler.enqueue(app, task.id) }
                                .onSuccess { onMessage(app.getString(R.string.tasks_manual_queued)) }
                                .onFailure { onMessage(app.getString(R.string.tasks_manual_failed)) }
                        }, enabled = !busy, modifier = Modifier.testTag("task-detail-run-now")) {
                            Text(stringResource(R.string.task_detail_manual_run))
                        }
                        OutlinedButton(onClick = { onRequestChange(task) }, modifier = Modifier.testTag("task-detail-change")) {
                            Text(stringResource(R.string.tasks_request_change))
                        }
                        if (loaded.runs.isNotEmpty()) OutlinedButton(
                            onClick = { confirmClear = true }, modifier = Modifier.testTag("task-clear-history")) {
                            Text(stringResource(R.string.task_detail_clear_history))
                        }
                    }
                }
            }
        }
        item(key = "history-header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JarvysSectionLabel(stringResource(R.string.task_detail_history))
                Text(pluralStringResource(R.plurals.task_detail_run_count, loaded.runs.size, loaded.runs.size),
                    Modifier.testTag("task-detail-run-count"), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        if (loaded.runs.isEmpty()) item(key = "history-empty") {
            JarvysGroup { Text(stringResource(R.string.task_detail_no_history), Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(loaded.runs, key = { it.idempotencyKey }) { run ->
            TaskRunHistoryItem(run, task, presentation)
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.task_detail_clear_history_title)) },
        text = { ScrollableDialogContent { Text(stringResource(R.string.task_detail_clear_history_message, task.name)) } },
        confirmButton = { Button(onClick = {
            confirmClear = false
            scope.launch(Dispatchers.IO) {
                runCatching { ledger.clearTask(task.id) }
                    .onSuccess { withContext(Dispatchers.Main) { onMessage(app.getString(R.string.task_detail_history_cleared)) } }
                    .onFailure { withContext(Dispatchers.Main) { onMessage(app.getString(R.string.tasks_load_error)) } }
            }
        }, modifier = Modifier.testTag("task-clear-history-confirm")) { Text(stringResource(R.string.task_detail_clear_history)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.tasks_cancel)) } },
    )
}

@Composable
private fun TaskRunHistoryItem(run: TaskRunRecord, task: ScheduledTask, presentation: TaskSchedulePresentation) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val zone = presentation.zoneFor(task.schedule)
    val status = stringResourceFromRun(context, run)
    JarvysGroup(Modifier.testTag("task-run-${run.idempotencyKey}")) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status, Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall)
                JarvysTag(deliveryLabel(context, run.deliveryStatus))
            }
            Text(stringResource(R.string.task_detail_run_scheduled,
                presentation.localTime(run.scheduledFor, zone)), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.task_detail_run_started,
                presentation.localTime(run.startedAt, zone)), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.task_detail_tools_called,
                run.toolsCalled.joinToString(", ") { it.name }.ifBlank { context.getString(R.string.task_detail_no_tools) }),
                style = MaterialTheme.typography.bodySmall)
            val cause = run.errorCause ?: run.reason
            if (!cause.isNullOrBlank()) Text(stringResource(R.string.task_detail_error_cause, cause),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun stringResourceFromRun(context: Context, run: TaskRunRecord): String =
    runStatusLabel(context, run.status, run.skipReason)

private fun deliveryLabel(context: Context, status: String): String = when (status) {
    "DELIVERED" -> context.getString(R.string.task_detail_delivery_notified)
    "CHAT_ONLY" -> context.getString(R.string.task_detail_delivery_chat_only)
    "SILENT" -> context.getString(R.string.task_detail_delivery_silent)
    "NOT_APPLICABLE" -> context.getString(R.string.task_detail_delivery_not_applicable)
    else -> context.getString(R.string.task_detail_delivery_unknown)
}

private fun taskStateLabelForDetail(context: Context, state: TaskState): String = when (state) {
    TaskState.Active -> context.getString(R.string.tasks_state_active)
    TaskState.Paused -> context.getString(R.string.tasks_state_paused)
    TaskState.Done -> context.getString(R.string.tasks_state_done)
    is TaskState.NeedsAttention -> context.getString(R.string.tasks_state_attention)
    TaskState.AwaitingUser -> context.getString(R.string.tasks_state_awaiting)
}
