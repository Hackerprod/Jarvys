package com.jarvys.agent.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.MemorySourceTitleRepairController
import com.jarvys.agent.R
import com.jarvys.agent.chatdb.MemoryRepairBackupInfo
import com.jarvys.agent.chatdb.MemorySourceTitleRepairPlan
import com.jarvys.agent.chatdb.MemorySourceTitleRepairReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Human-only operation. The inventory entry path does not initialize the memory database. */
@Composable
internal fun MemorySourceTitleRepairDialog(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val controller = remember(context) { runCatching { MemorySourceTitleRepairController.get(context) }.getOrNull() }
    if (controller == null) {
        AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.memory_repair_title)) },
            text = { Text(stringResource(R.string.memory_repair_unavailable)) }, confirmButton = {
                TextButton(onClick = onClose, modifier = Modifier.testTag("memory-repair-close")) { Text(stringResource(R.string.memory_inspector_close)) }
            })
        return
    }
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<String>>(emptyList()) }
    var project by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<MemorySourceTitleRepairPlan?>(null) }
    var receipt by remember { mutableStateOf<MemorySourceTitleRepairReceipt?>(null) }
    var backups by remember { mutableStateOf<List<MemoryRepairBackupInfo>?>(null) }
    var consent by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var observed by remember { mutableStateOf(controller.activeOperation()) }
    var ownToken by remember { mutableStateOf<CancellationToken?>(null) }
    var ownJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var requestId by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf<Int?>(null) }
    fun clearPlan() { plan = null; receipt = null; consent = false }
    fun select(value: String) { project = value; clearPlan(); message = null }
    fun close() { requestId++; ownToken?.cancel(); ownJob?.cancel(); onClose() }
    DisposableEffect(Unit) { onDispose { ownToken?.cancel(); ownJob?.cancel() } }
    fun <T> request(mutation: Boolean, invalidatePlan: Boolean,
        produce: (CancellationToken) -> T, accept: (T) -> Unit) {
        if (pending || controller.activeOperation() != null) return
        val captured = ++requestId
        val token = CancellationToken.cancellable()
        if (invalidatePlan) { clearPlan(); message = null }
        if (mutation) backups = null
        pending = true; ownToken = token
        ownJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { produce(token) }
                coroutineContext.ensureActive(); token.throwIfCancelled()
                if (captured == requestId) accept(result)
            } catch (cancelled: CancellationException) {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && captured == requestId) {
                    clearPlan(); message = R.string.memory_repair_cancelled
                }
            } catch (_: Exception) {
                coroutineContext.ensureActive()
                if (captured == requestId) {
                    clearPlan()
                    // No message/payload from SQL, a copied database or the provider is rendered here.
                    message = if (mutation) R.string.memory_repair_unknown else R.string.memory_repair_failed
                }
            } finally {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && captured == requestId) {
                    pending = false; ownToken = null; ownJob = null; observed = controller.activeOperation()
                }
            }
        }
    }
    LaunchedEffect(controller) {
        // Merely opening this dialog does not bootstrap a database or create a backup directory.
        while (true) { observed = controller.activeOperation(); kotlinx.coroutines.delay(100) }
    }
    val busy = pending || observed != null
    AlertDialog(onDismissRequest = { close() }, title = { Text(stringResource(R.string.memory_repair_title)) }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp).testTag("memory-repair-content"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.memory_repair_explanation))
            }
            item { Text(stringResource(R.string.memory_repair_limitations)) }
            item {
                if (busy) Text(stringResource(R.string.memory_repair_busy), modifier = Modifier.testTag("memory-repair-busy"))
                message?.let { Text(stringResource(it), modifier = Modifier.testTag("memory-repair-message")) }
                TextButton(enabled = !busy, onClick = {
                    projects = emptyList(); project = null
                    request(false, true, { controller.projects(it) }) { projects = it }
                }, modifier = Modifier.testTag("memory-repair-projects-load")) { Text(stringResource(R.string.memory_repair_load_projects)) }
                Text(stringResource(R.string.memory_repair_select))
            }
            itemsIndexed(projects) { index, value ->
                TextButton(enabled = !busy, onClick = { select(value) }, modifier = Modifier.testTag("memory-repair-project-$index")) {
                    Text((if (project == value) "✓ " else "") + value)
                }
            }
            item {
                project?.let { Text(it, modifier = Modifier.testTag("memory-repair-selection")) }
                TextButton(enabled = !busy && project != null, onClick = {
                    val selected = project ?: return@TextButton
                    request(false, true, { controller.preview(selected, it) }) { plan = it }
                }, modifier = Modifier.testTag("memory-repair-preview")) { Text(stringResource(R.string.memory_repair_preview)) }
            }
            plan?.let { preview ->
                item {
                    Text(preview.project, modifier = Modifier.testTag("memory-repair-preview-project"))
                    Text(stringResource(R.string.memory_repair_preview_note))
                    Text(stringResource(R.string.memory_repair_findings, preview.findings.size), modifier = Modifier.testTag("memory-repair-findings-count"))
                    Text(stringResource(R.string.memory_repair_repairable, preview.repairableCount), modifier = Modifier.testTag("memory-repair-repairable"))
                }
                itemsIndexed(preview.findings) { index, finding ->
                    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("memory-repair-finding-$index")) {
                        Text(finding.id)
                        Text(stringResource(R.string.memory_repair_missing_fields, finding.missingFields.joinToString(", ")),
                            modifier = Modifier.testTag("memory-repair-fields-$index"))
                        val original = if (finding.existingTitleIsUtf8 == false) stringResource(R.string.memory_repair_invalid_title)
                            else finding.existingTitle?.takeUnless { it.isEmpty() }
                                ?: stringResource(if (finding.existingTitle == null) R.string.memory_repair_null_title else R.string.memory_repair_empty_title)
                        Text(stringResource(R.string.memory_repair_old_title, original), modifier = Modifier.testTag("memory-repair-old-title-$index"))
                        if (finding.proposedTitle != null) Text(stringResource(R.string.memory_repair_new_title, finding.proposedTitle),
                            modifier = Modifier.testTag("memory-repair-new-title-$index"))
                        else Text(stringResource(R.string.memory_repair_not_repairable))
                        Text(finding.explanation, modifier = Modifier.testTag("memory-repair-explanation-$index"))
                    }
                }
                item {
                    if (preview.repairableCount > 0L) {
                        Text(stringResource(R.string.memory_repair_backup_warning), modifier = Modifier.testTag("memory-repair-backup-warning"))
                    } else Text(stringResource(R.string.memory_repair_no_actions), modifier = Modifier.testTag("memory-repair-no-actions"))
                }
                if (preview.repairableCount > 0L) {
                    item { Text(stringResource(R.string.memory_repair_retention_warning), modifier = Modifier.testTag("memory-repair-retention-warning")) }
                    item {
                        Row {
                            Checkbox(checked = consent, enabled = !busy, onCheckedChange = { consent = it }, modifier = Modifier.testTag("memory-repair-consent"))
                            Text(stringResource(R.string.memory_repair_consent))
                        }
                    }
                }
                item {
                    TextButton(enabled = !busy && (preview.repairableCount == 0L || consent), onClick = {
                        if (busy || preview.repairableCount > 0L && !consent) return@TextButton
                        val confirmedCopy = consent
                        request(true, true, { controller.apply(preview, confirmedCopy, it) }) { receipt = it }
                    }, modifier = Modifier.testTag("memory-repair-apply")) {
                        Text(stringResource(if (preview.repairableCount > 0L) R.string.memory_repair_apply else R.string.memory_repair_apply_noop))
                    }
                }
            }
            receipt?.let { result ->
                item {
                    Text(stringResource(R.string.memory_repair_receipt), modifier = Modifier.testTag("memory-repair-receipt"))
                    Text(result.project)
                    Text(stringResource(R.string.memory_repair_repaired, result.repairedCount), modifier = Modifier.testTag("memory-repair-repaired"))
                    if (result.backup == null) Text(stringResource(R.string.memory_repair_no_backup), modifier = Modifier.testTag("memory-repair-no-backup"))
                    else Text(stringResource(R.string.memory_repair_backup_id, result.backup.operationId), modifier = Modifier.testTag("memory-repair-receipt-backup"))
                }
            }
            item {
                Text(stringResource(R.string.memory_repair_inventory), modifier = Modifier.testTag("memory-repair-inventory-title"))
            }
            item { Text(stringResource(R.string.memory_repair_inventory_note)) }
            item { Text(stringResource(R.string.memory_repair_retention_warning)) }
            item {
                TextButton(enabled = !busy, onClick = {
                    backups = null
                    request(false, false, { controller.backups(it) }) { backups = it }
                }, modifier = Modifier.testTag("memory-repair-inventory-refresh")) { Text(stringResource(R.string.memory_repair_inventory_refresh)) }
            }
            backups?.let { entries ->
                item {
                    Text(stringResource(R.string.memory_repair_inventory_count, entries.size), modifier = Modifier.testTag("memory-repair-inventory-count"))
                    if (entries.isEmpty()) Text(stringResource(R.string.memory_repair_inventory_empty), modifier = Modifier.testTag("memory-repair-inventory-empty"))
                }
                itemsIndexed(entries) { index, copy ->
                    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("memory-repair-backup-$index")) {
                        Text(stringResource(R.string.memory_repair_backup_id, copy.operationId))
                        copy.createdAt?.let { Text(stringResource(R.string.memory_repair_backup_time, it)) }
                        Text(stringResource(R.string.memory_repair_backup_status, copy.status), modifier = Modifier.testTag("memory-repair-backup-status-$index"))
                        Text(stringResource(R.string.memory_repair_backup_availability, copy.availability), modifier = Modifier.testTag("memory-repair-backup-availability-$index"))
                        Text(stringResource(R.string.memory_repair_backup_outcome, copy.repairOutcome), modifier = Modifier.testTag("memory-repair-backup-outcome-$index"))
                        copy.sizeBytes?.let { Text(stringResource(R.string.memory_repair_backup_size, it)) }
                        copy.sha256?.let { Text(stringResource(R.string.memory_repair_backup_hash, it)) }
                    }
                }
            }
        }
    }, confirmButton = {
        if (busy) TextButton(onClick = { if (ownToken != null) ownToken?.cancel() else observed?.let { controller.cancelIfCurrent(it) } },
            modifier = Modifier.testTag("memory-repair-cancel")) { Text(stringResource(R.string.memory_repair_cancel)) }
    }, dismissButton = {
        TextButton(onClick = { close() }, modifier = Modifier.testTag("memory-repair-close")) { Text(stringResource(R.string.memory_inspector_close)) }
    })
}
