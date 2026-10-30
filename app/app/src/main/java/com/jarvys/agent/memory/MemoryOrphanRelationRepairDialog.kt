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
import com.jarvys.agent.MemoryOrphanRelationRepairController
import com.jarvys.agent.R
import com.jarvys.agent.chatdb.MemoryRepairBackupInfo
import com.jarvys.agent.chatdb.MemoryOrphanRelationRepairPlan
import com.jarvys.agent.chatdb.MemoryOrphanRelationRepairReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Human-only operation. The inventory entry path does not initialize the memory database. */
@Composable
internal fun MemoryOrphanRelationRepairDialog(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val controller = remember(context) { runCatching { MemoryOrphanRelationRepairController.get(context) }.getOrNull() }
    if (controller == null) {
        AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.memory_orphan_title)) },
            text = { Text(stringResource(R.string.memory_repair_unavailable)) }, confirmButton = {
                TextButton(onClick = onClose, modifier = Modifier.testTag("memory-orphan-close")) { Text(stringResource(R.string.memory_inspector_close)) }
            })
        return
    }
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<MemoryOrphanRelationRepairPlan?>(null) }
    var receipt by remember { mutableStateOf<MemoryOrphanRelationRepairReceipt?>(null) }
    var backups by remember { mutableStateOf<List<MemoryRepairBackupInfo>?>(null) }
    var consent by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var observed by remember { mutableStateOf(controller.activeOperation()) }
    var ownToken by remember { mutableStateOf<CancellationToken?>(null) }
    var ownJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var requestId by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf<Int?>(null) }
    fun clearPlan() { plan = null; receipt = null; consent = false }
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
    AlertDialog(onDismissRequest = { close() }, title = { Text(stringResource(R.string.memory_orphan_title)) }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp).testTag("memory-orphan-content"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.memory_orphan_scope), modifier = Modifier.testTag("memory-orphan-scope")) }
            item { Text(stringResource(R.string.memory_orphan_partial), modifier = Modifier.testTag("memory-orphan-partial")) }
            item { Text(stringResource(R.string.memory_orphan_terminal), modifier = Modifier.testTag("memory-orphan-terminal")) }
            item {
                if (busy) Text(stringResource(R.string.memory_repair_busy), modifier = Modifier.testTag("memory-orphan-busy"))
                message?.let { Text(stringResource(it), modifier = Modifier.testTag("memory-orphan-message")) }
                TextButton(enabled = !busy, onClick = {
                    request(false, true, { controller.preview(it) }) { plan = it }
                }, modifier = Modifier.testTag("memory-orphan-preview")) { Text(stringResource(R.string.memory_orphan_preview)) }
            }
            plan?.let { preview ->
                item { Text(stringResource(R.string.memory_orphan_estimate), modifier = Modifier.testTag("memory-orphan-estimate")) }
                item { Text(stringResource(R.string.memory_orphan_counts, preview.candidateCount, preview.oneRetiredCount, preview.livePendingCount), modifier = Modifier.testTag("memory-orphan-counts")) }
                itemsIndexed(preview.candidates) { index, candidate ->
                    androidx.compose.foundation.layout.Column(modifier = Modifier.testTag("memory-orphan-candidate-$index")) {
                        Text(stringResource(R.string.memory_orphan_relation, candidate.relationId))
                        Text(stringResource(R.string.memory_orphan_source, candidate.sourceId, candidate.sourceProject))
                        Text(stringResource(R.string.memory_orphan_target, candidate.targetId, candidate.targetProject))
                    }
                }
                item { Text(stringResource(R.string.memory_orphan_always_backup), modifier = Modifier.testTag("memory-orphan-always-backup")) }
                item { Text(stringResource(R.string.memory_orphan_backup_warning), modifier = Modifier.testTag("memory-orphan-backup-warning")) }
                item { Text(stringResource(R.string.memory_repair_retention_warning)) }
                item {
                    Row {
                        Checkbox(checked = consent, enabled = !busy, onCheckedChange = { consent = it }, modifier = Modifier.testTag("memory-orphan-consent"))
                        Text(stringResource(R.string.memory_orphan_consent))
                    }
                }
                item {
                    TextButton(enabled = !busy && consent, onClick = {
                        if (busy || !consent) return@TextButton
                        request(true, true, { controller.apply(preview, true, it) }) { receipt = it }
                    }, modifier = Modifier.testTag("memory-orphan-apply")) { Text(stringResource(R.string.memory_orphan_apply)) }
                }
            }
            receipt?.let { result ->
                item {
                    Text(stringResource(R.string.memory_orphan_receipt, result.reclassifiedCount), modifier = Modifier.testTag("memory-orphan-receipt"))
                    Text(stringResource(R.string.memory_orphan_counts, result.candidatesAtApply, result.oneRetiredCount, result.livePendingCount), modifier = Modifier.testTag("memory-orphan-final-counts"))
                    Text(stringResource(R.string.memory_repair_backup_id, result.backup.operationId), modifier = Modifier.testTag("memory-orphan-receipt-backup"))
                }
            }
            item {
                Text(stringResource(R.string.memory_repair_inventory), modifier = Modifier.testTag("memory-orphan-inventory-title"))
            }
            item { Text(stringResource(R.string.memory_repair_inventory_note)) }
            item { Text(stringResource(R.string.memory_repair_retention_warning)) }
            item {
                TextButton(enabled = !busy, onClick = {
                    backups = null
                    request(false, false, { controller.backups(it) }) { backups = it }
                }, modifier = Modifier.testTag("memory-orphan-inventory-refresh")) { Text(stringResource(R.string.memory_repair_inventory_refresh)) }
            }
            backups?.let { entries ->
                item {
                    Text(stringResource(R.string.memory_repair_inventory_count, entries.size), modifier = Modifier.testTag("memory-orphan-inventory-count"))
                    if (entries.isEmpty()) Text(stringResource(R.string.memory_repair_inventory_empty), modifier = Modifier.testTag("memory-orphan-inventory-empty"))
                }
                itemsIndexed(entries) { index, copy ->
                    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("memory-orphan-backup-$index")) {
                        Text(stringResource(R.string.memory_repair_backup_id, copy.operationId))
                        Text(stringResource(when (copy.kind) {
                            "source_title" -> R.string.memory_repair_kind_title
                            "orphan_pending_relations" -> R.string.memory_repair_kind_orphan
                            else -> R.string.memory_repair_kind_unspecified
                        }))
                        copy.createdAt?.let { Text(stringResource(R.string.memory_repair_backup_time, it)) }
                        Text(stringResource(R.string.memory_repair_backup_status, copy.status), modifier = Modifier.testTag("memory-orphan-backup-status-$index"))
                        Text(stringResource(R.string.memory_repair_backup_availability, copy.availability), modifier = Modifier.testTag("memory-orphan-backup-availability-$index"))
                        Text(stringResource(R.string.memory_repair_backup_outcome, copy.repairOutcome), modifier = Modifier.testTag("memory-orphan-backup-outcome-$index"))
                        copy.sizeBytes?.let { Text(stringResource(R.string.memory_repair_backup_size, it)) }
                        copy.sha256?.let { Text(stringResource(R.string.memory_repair_backup_hash, it)) }
                    }
                }
            }
        }
    }, confirmButton = {
        if (busy) TextButton(onClick = { if (ownToken != null) ownToken?.cancel() else observed?.let { controller.cancelIfCurrent(it) } },
            modifier = Modifier.testTag("memory-orphan-cancel")) { Text(stringResource(R.string.memory_repair_cancel)) }
    }, dismissButton = {
        TextButton(onClick = { close() }, modifier = Modifier.testTag("memory-orphan-close")) { Text(stringResource(R.string.memory_inspector_close)) }
    })
}
