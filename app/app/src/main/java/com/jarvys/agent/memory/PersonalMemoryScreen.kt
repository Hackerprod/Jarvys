package com.jarvys.agent.memory

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.MemoryWritePolicy
import com.jarvys.agent.PersonalMemoryConsolidationWorker
import com.jarvys.agent.R
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.PersonalMemorySessionView
import com.jarvys.agent.chatdb.PersonalMemoryPromptView
import com.jarvys.agent.chatdb.PersonalMemoryProjectSummary
import com.jarvys.agent.chatdb.PersonalMemoryProjectDeletionPreview
import com.jarvys.agent.chatdb.MemoryStats
import com.jarvys.agent.chatdb.MemoryProjectAlias
import com.jarvys.agent.chatdb.MemoryProjectAliasResolution
import com.jarvys.agent.chatdb.MemoryProjectAliases
import com.jarvys.agent.chatdb.MemoryProjectInventory
import com.jarvys.agent.chatdb.MemoryProjectInventoryItem
import com.jarvys.agent.chatdb.MemoryConsolidationRequest
import com.jarvys.agent.chatdb.MemoryConsolidationPlan
import com.jarvys.agent.chatdb.MemoryConsolidationResult
import com.jarvys.agent.chatdb.MemoryConsolidationArguments
import com.jarvys.agent.chatdb.MemoryRelationScanOptions
import com.jarvys.agent.chatdb.MemoryRelationScanResult
import com.jarvys.agent.chatdb.MemoryRelationDetails
import com.jarvys.agent.chatdb.MemoryRelationPage
import com.jarvys.agent.chatdb.PersonalMemoryHit
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.chatdb.PersonalMemoryItem
import com.jarvys.agent.chatdb.MemorySaveOptions
import com.jarvys.agent.chatdb.MemoryObservationContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext

/** SQLite-only memory UI. It deliberately has no MemoryStore or legacy file parameter. */
@Composable
fun PersonalMemoryScreen(conversationId: String, onClose: (() -> Unit)? = null) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(emptyList<PersonalMemoryItem>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf("nickname") }
    var draft by remember { mutableStateOf("") }
    var noteTitle by remember { mutableStateOf("") }
    var pinPendingId by remember { mutableStateOf<String?>(null) }
    var inspectorTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<PersonalMemoryItem?>(null) }
    var deletePending by remember { mutableStateOf(false) }
    var hardDelete by remember { mutableStateOf(false) }
    var showRemoved by remember { mutableStateOf(false) }
    var dueOnly by remember { mutableStateOf(false) }
    var showProjectMemories by remember { mutableStateOf(false) }
    var showMemorySessions by remember { mutableStateOf(false) }
    var showMemoryStats by remember { mutableStateOf(false) }
    var showMemoryAliases by remember { mutableStateOf(false) }
    var showMemoryProjectInventory by remember { mutableStateOf(false) }
    var showMemorySelftest by remember { mutableStateOf(false) }
    var showMemoryDoctor by remember { mutableStateOf(false) }
    var showMemoryExport by remember { mutableStateOf(false) }
    var showMemoryImport by remember { mutableStateOf(false) }
    var showPendingRelations by remember { mutableStateOf(false) }
    var showRelationScan by remember { mutableStateOf(false) }
    var judgmentTarget by remember { mutableStateOf<String?>(null) }
    var consolidationSelecting by remember { mutableStateOf(false) }
    var consolidationIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var consolidationProject by remember { mutableStateOf<String?>(null) }
    var consolidationTarget by remember { mutableStateOf<Pair<List<String>, String>?>(null) }
    var reloadGeneration by remember { mutableStateOf(0L) }
    val deleteFailure = stringResource(R.string.memory_delete_failed)
    val pinFailure = stringResource(R.string.memory_pin_failed)
    var dailyConsolidationEnabled by remember {
        mutableStateOf(context.getSharedPreferences(PersonalMemoryConsolidationWorker.PREFERENCES, android.content.Context.MODE_PRIVATE)
            .getBoolean(PersonalMemoryConsolidationWorker.DAILY_ENABLED_KEY, true))
    }

    fun reload() {
        consolidationIds = emptyList(); consolidationProject = null; consolidationSelecting = false
        val requestedFilter = showRemoved
        val requestedDue = dueOnly
        val request = ++reloadGeneration
        loading = true
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).let { if (requestedDue) it.personalAgentMemoriesNeedingReview() else it.personalAgentBrowse(requestedFilter) } }
                if (request == reloadGeneration) { items = loaded; error = null }
            } catch (failure: Exception) {
                if (request == reloadGeneration) error = failure.message ?: "No se pudo leer el perfil guardado."
            } finally { if (request == reloadGeneration) loading = false }
        }
    }

    LaunchedEffect(conversationId) { reload() }

    fun persist(profile: Boolean) {
        val value = if (profile) draft.trim() else MemoryObservationContent.trimSpace(draft)
        val title = MemoryObservationContent.trimSpace(noteTitle)
        if (value.isEmpty() || (!profile && title.isEmpty())) return
        scope.launch {
            try {
                val receipt = withContext(Dispatchers.IO) {
                    val bridge = ChatDatabaseBridge.getInstance(context)
                    if (profile) bridge.personalAgentSetDirective(section.trim().ifBlank { "profile" }, value, null, "User entered through the memory screen.")
                    else bridge.personalAgentSaveMemory(value, "curated", "user_note", 0.5, "memory-ui",
                        MemorySaveOptions(type = "manual", title = title, actor = "user"))
                }
                notice = "Guardado con evidencia: ${receipt.id} · revisión ${receipt.revision}."
                draft = ""
                noteTitle = ""
                showProfileDialog = false
                showNoteDialog = false
                reload()
            } catch (failure: Exception) {
                notice = "No se guardó: ${failure.message ?: "error de base de datos"}."
            }
        }
    }

    Box(Modifier.fillMaxSize().then(
        if (!loading && items.isNotEmpty()) Modifier.testTag("personal-memory-list") else Modifier,
    )) {
        LazyColumn(Modifier.fillMaxSize().padding(20.dp).testTag("memory-screen-scroll"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "memory-controls") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Memoria personal", style = MaterialTheme.typography.headlineSmall)
                        if (onClose != null) TextButton(onClick = onClose) { Text("Cerrar") }
                    }
                    Text("El perfil se comparte entre tus conversaciones normales. Los chats privados o sin memoria no lo leen ni lo modifican.", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Consolidar notas diarias (una vez al día)")
                        Switch(checked = dailyConsolidationEnabled, onCheckedChange = { enabled ->
                            dailyConsolidationEnabled = enabled
                            PersonalMemoryConsolidationWorker.setDailyEnabled(context, enabled)
                        }, modifier = Modifier.testTag("memory-daily-consolidation"))
                    }
                    PromptCapturePreference()
                    TextButton(enabled = !deletePending && pinPendingId == null,
                        onClick = { if (!deletePending && pinPendingId == null) showMemoryStats = true },
                        modifier = Modifier.testTag("memory-stats-open")) { Text(stringResource(R.string.memory_stats_action)) }
                    TextButton(enabled = !deletePending && pinPendingId == null,
                        onClick = { if (!deletePending && pinPendingId == null) showMemoryProjectInventory = true },
                        modifier = Modifier.testTag("memory-project-inventory-open")) { Text(stringResource(R.string.memory_project_inventory_action)) }
                    TextButton(enabled = !deletePending && pinPendingId == null,
                        onClick = { if (!deletePending && pinPendingId == null) showMemoryAliases = true },
                        modifier = Modifier.testTag("memory-aliases-open")) { Text(stringResource(R.string.memory_aliases_action)) }
                    TextButton(enabled = !deletePending && pinPendingId == null,
                        onClick = { if (!deletePending && pinPendingId == null) showMemorySelftest = true },
                        modifier = Modifier.testTag("memory-selftest-open")) { Text(stringResource(R.string.memory_selftest_action)) }
                    TextButton(enabled = !deletePending && pinPendingId == null,
                        onClick = { if (!deletePending && pinPendingId == null) showMemoryDoctor = true },
                        modifier = Modifier.testTag("memory-doctor-open")) { Text(stringResource(R.string.memory_doctor_action)) }
                    TextButton(enabled = !deletePending && pinPendingId == null,
                        onClick = { if (!deletePending && pinPendingId == null) showMemoryExport = true },
                        modifier = Modifier.testTag("memory-export-open")) { Text(stringResource(R.string.memory_export_json_action)) }
                    TextButton(enabled = !deletePending && pinPendingId == null,
                        onClick = { if (!deletePending && pinPendingId == null) showMemoryImport = true },
                        modifier = Modifier.testTag("memory-import-open")) { Text(stringResource(R.string.memory_import_json_action)) }
                    TextButton(enabled = !deletePending && pinPendingId == null,
                        onClick = { if (!deletePending && pinPendingId == null) showMemorySessions = true },
                        modifier = Modifier.testTag("memory-sessions-open")) { Text(stringResource(R.string.memory_sessions_action)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { draft = ""; section = "nickname"; showProfileDialog = true }, modifier = Modifier.testTag("memory-add-profile")) { Text("Añadir dato de perfil") }
                        Button(onClick = { draft = ""; noteTitle = ""; showNoteDialog = true }, modifier = Modifier.testTag("memory-add-note")) { Text("Añadir recuerdo") }
                    }
                    TextButton(enabled = !deletePending && pinPendingId == null, onClick = { if (!deletePending && pinPendingId == null) showProjectMemories = true },
                        modifier = Modifier.testTag("memory-projects-open")) { Text(stringResource(R.string.memory_projects_action)) }
                    TextButton(enabled = !deletePending && pinPendingId == null, onClick = { if (!deletePending && pinPendingId == null) showPendingRelations = true },
                        modifier = Modifier.testTag("memory-relations-open")) { Text(stringResource(R.string.memory_relations_open)) }
                    TextButton(enabled = !deletePending && pinPendingId == null, onClick = { if (!deletePending && pinPendingId == null) showRelationScan = true },
                        modifier = Modifier.testTag("memory-scan-open")) { Text(stringResource(R.string.memory_scan_open)) }
                    TextButton(enabled = !loading && !deletePending && pinPendingId == null, onClick = {
                        consolidationSelecting = !consolidationSelecting
                        consolidationIds = emptyList(); consolidationProject = null
                    }, modifier = Modifier.testTag("memory-consolidate-select")) { Text(stringResource(if (consolidationSelecting) R.string.memory_consolidate_cancel_selection else R.string.memory_consolidate_select)) }
                    if (consolidationSelecting) {
                        Text(stringResource(R.string.memory_consolidate_selection, consolidationIds.size, consolidationProject ?: "—"))
                        TextButton(enabled = consolidationIds.size >= 2 && consolidationProject != null && !deletePending && pinPendingId == null,
                            onClick = { if (consolidationIds.size >= 2) consolidationProject?.let { consolidationTarget = consolidationIds.toList() to it } },
                            modifier = Modifier.testTag("memory-consolidate-open")) { Text(stringResource(R.string.memory_consolidate_open)) }
                    }
                    notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("memory-notice")) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("memory-error")) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.memory_review_due_only))
                        Switch(checked = dueOnly, enabled = !deletePending && pinPendingId == null,
                            onCheckedChange = { enabled -> if (!deletePending && pinPendingId == null) {
                                dueOnly = enabled; if (enabled) showRemoved = false; reload()
                            } }, modifier = Modifier.testTag("memory-review-due-only"))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.memory_show_removed))
                        Switch(checked = showRemoved, enabled = !dueOnly && !deletePending && pinPendingId == null,
                            onCheckedChange = { enabled -> if (!dueOnly && !deletePending && pinPendingId == null) { showRemoved = enabled; reload() } },
                            modifier = Modifier.testTag("memory-show-removed"))
                    }
                }
            }
            if (loading) item(key = "memory-loading") { Text("Cargando memoria…") }
            else if (items.isEmpty()) item(key = "memory-empty") {
                Text(if (dueOnly) stringResource(R.string.memory_review_none_due) else "Tu perfil nuevo está vacío. Los archivos de memoria anteriores no se han importado.", modifier = Modifier.testTag("memory-empty"))
            } else {
                items(items, key = { "${it.kind}:${it.id}" }) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (consolidationSelecting && item.kind == "memory" && item.deletedAt == null && item.project != null) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    androidx.compose.material3.Checkbox(checked = item.id in consolidationIds,
                                        enabled = !deletePending && pinPendingId == null && (consolidationProject == null || consolidationProject == item.project),
                                        onCheckedChange = { selected ->
                                            if (!deletePending && pinPendingId == null && (consolidationProject == null || consolidationProject == item.project)) {
                                                consolidationIds = if (selected) (consolidationIds + item.id).distinct() else consolidationIds - item.id
                                                consolidationProject = if (consolidationIds.isEmpty()) null else item.project
                                            }
                                        }, modifier = Modifier.testTag("memory-consolidate-pick-${item.id}"))
                                    Text(stringResource(R.string.memory_consolidate_pick))
                                }
                            }
                            item.supersededById?.let { survivorId ->
                                Text(stringResource(R.string.memory_consolidate_superseded_reference, survivorId), modifier = Modifier.testTag("memory-superseded-${item.id}"))
                            }
                            val episode = item.memoryKind == "episode"
                            Text(
                                when {
                                    item.kind == "profile" -> "Perfil · ${item.section}"
                                    episode -> stringResource(R.string.memory_episode_label,
                                        java.text.DateFormat.getDateInstance().format(java.util.Date(item.eventAt ?: item.updatedAt)))
                                    else -> "Recuerdo · ${item.section}"
                                },
                                style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("memory-item-kind"),
                            )
                            // An episode is recognizable before its narration exists: title, else the label from its evidence.
                            Text(if (episode) item.title ?: item.label else item.text)
                            if (episode && item.text.isNotBlank()) Text(item.text)
                            if (episode && item.narration == "pending") Text(stringResource(R.string.memory_episode_narration_pending), style = MaterialTheme.typography.labelSmall)
                            if (episode && item.narration == "failed") Text(stringResource(R.string.memory_episode_narration_failed), style = MaterialTheme.typography.labelSmall)
                            if (item.kind == "memory" && item.deletedAt != null) {
                                Text(stringResource(R.string.memory_removed_state), modifier = Modifier.testTag("memory-removed-${item.id}"))
                            }
                            if (item.kind == "memory" && item.deletedAt == null) {
                                TextButton(enabled = !deletePending && pinPendingId == null,
                                    modifier = Modifier.testTag("memory-inspect-${item.id}"),
                                    onClick = { if (!deletePending && pinPendingId == null) inspectorTarget = item.id }) {
                                    Text(stringResource(R.string.memory_inspector_action))
                                }
                                val pinned = item.pinned == true
                                Text(stringResource(if (pinned) R.string.memory_pinned else R.string.memory_not_pinned),
                                    modifier = Modifier.testTag("memory-pin-state-${item.id}"))
                                TextButton(enabled = pinPendingId == null && !deletePending, modifier = Modifier.testTag("memory-pin-toggle-${item.id}"),
                                    onClick = pinClick@ {
                                        if (pinPendingId != null || deletePending) return@pinClick
                                        pinPendingId = item.id
                                        error = null
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentSetPinned(item.id, !pinned) }
                                                // Read the committed state; never optimistically flip the card.
                                                items = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).let { if (dueOnly) it.personalAgentMemoriesNeedingReview() else it.personalAgentBrowse(showRemoved) } }
                                            } catch (failure: Exception) {
                                                error = failure.message ?: pinFailure
                                            } finally { pinPendingId = null }
                                        }
                                    }) { Text(stringResource(if (pinned) R.string.memory_unpin_action else R.string.memory_pin_action)) }
                            }
                            if (item.kind == "memory" && item.deletedAt == null) {
                                TextButton(enabled = !deletePending && pinPendingId == null && item.project != null,
                                    modifier = Modifier.testTag("memory-delete-open-${item.id}"),
                                    onClick = { if (!deletePending && pinPendingId == null) { hardDelete = false; error = null; deleteTarget = item } }) {
                                    Text(stringResource(R.string.memory_delete_action))
                                }
                            }
                            if (item.kind == "memory") {
                                TextButton(enabled = !deletePending && pinPendingId == null && item.project != null,
                                    modifier = Modifier.testTag("memory-hard-delete-open-${item.id}"),
                                    onClick = { if (!deletePending && pinPendingId == null) { hardDelete = true; error = null; deleteTarget = item } }) {
                                    Text(stringResource(R.string.memory_hard_delete_action))
                                }
                            } else if (item.kind == "profile") {
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            val receipt = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentForgetDirective(item.id) }
                                            notice = "Olvidado con evidencia: ${receipt.id} · revision ${receipt.revision}."
                                            reload()
                                        } catch (failure: Exception) { error = failure.message ?: "No se pudo eliminar." }
                                    }
                                }, modifier = Modifier.testTag("memory-profile-remove-${item.id}")) { Text("Olvidar") }
                            }
                        }
                    }
                }
            }
        }
    }

    consolidationTarget?.let { target ->
        MemoryConsolidationDialog(target.first, target.second, onClose = { consolidationTarget = null }, onCommitted = { reload() })
    }

    if (showMemorySessions) MemorySessionsDialog { showMemorySessions = false }
    if (showMemoryStats) MemoryStatsDialog { showMemoryStats = false }
    if (showMemoryAliases) MemoryAliasesDialog { showMemoryAliases = false }
    if (showMemoryProjectInventory) MemoryProjectInventoryDialog { showMemoryProjectInventory = false }
    if (showMemorySelftest) MemorySelftestDialog { showMemorySelftest = false }
    if (showMemoryDoctor) MemoryDoctorDialog { showMemoryDoctor = false }
    if (showMemoryExport) MemoryExportDialog { showMemoryExport = false }
    if (showMemoryImport) MemoryImportDialog(onClose = { showMemoryImport = false }, onCommitted = { reload() })

    if (showProjectMemories) ProjectMemoryDeleteDialog(
        onClose = { showProjectMemories = false },
        onCommitted = { showProjectMemories = false; reload() },
    )

    if (showRelationScan) MemoryRelationScanDialog(onClose = { showRelationScan = false }, onChanged = { reload() }, onPending = {
        showRelationScan = false; showPendingRelations = true
    })

    if (showPendingRelations) PendingMemoryRelationsDialog(onClose = { showPendingRelations = false }, onInspect = { id ->
        showPendingRelations = false; inspectorTarget = id
    }, onJudge = { id -> showPendingRelations = false; judgmentTarget = id })
    inspectorTarget?.let { target ->
        key(target) { MemoryInspectorDialog(target, onChanged = { reload() }, onJudge = { id -> inspectorTarget = null; judgmentTarget = id }) { inspectorTarget = null } }
    }

    judgmentTarget?.let { target ->
        key(target) { MemoryJudgmentDialog(target, onChanged = { reload() }, onClose = { judgmentTarget = null }) }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!deletePending) deleteTarget = null },
            title = { Text(stringResource(if (hardDelete) R.string.memory_hard_delete_title else R.string.memory_observation_delete_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(target.title ?: target.label.ifBlank { target.id })
                Text(stringResource(R.string.memory_delete_target, target.id, requireNotNull(target.project)),
                    modifier = Modifier.testTag("memory-delete-target"))
                Text(stringResource(if (hardDelete) R.string.memory_hard_delete_description else R.string.memory_delete_soft_description))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("memory-delete-error")) }
            } },
            confirmButton = { TextButton(enabled = !deletePending && pinPendingId == null,
                modifier = Modifier.testTag("memory-delete-confirm"), onClick = confirmDelete@ {
                    if (deletePending || pinPendingId != null) return@confirmDelete
                    val permanentlyDelete = hardDelete
                    deletePending = true
                    error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context)
                                .let { bridge ->
                                    if (permanentlyDelete) bridge.personalAgentHardDeleteObservation(target.id, requireNotNull(target.project))
                                    else bridge.personalAgentDeleteObservation(target.id, requireNotNull(target.project))
                                } }
                            items = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).let { if (dueOnly) it.personalAgentMemoriesNeedingReview() else it.personalAgentBrowse(showRemoved) } }
                            deleteTarget = null
                        } catch (failure: Exception) { error = failure.message ?: deleteFailure }
                        finally { deletePending = false }
                    }
                }) { Text(stringResource(if (hardDelete) R.string.memory_hard_delete_confirm else R.string.memory_delete_action)) } },
            dismissButton = { TextButton(enabled = !deletePending,
                modifier = Modifier.testTag("memory-delete-cancel"),
                onClick = { if (!deletePending) deleteTarget = null }) { Text(stringResource(R.string.memory_delete_cancel)) } },
        )
    }

    if (showProfileDialog) AlertDialog(
        onDismissRequest = { showProfileDialog = false },
        title = { Text("Dato de perfil") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(section, { section = it }, label = { Text("Sección, por ejemplo nickname o profession") }, singleLine = true, modifier = Modifier.testTag("memory-profile-section"))
                OutlinedTextField(draft, { draft = it }, label = { Text("Dato que quieres guardar") }, modifier = Modifier.testTag("memory-profile-value"))
            }
        },
        confirmButton = { TextButton(onClick = { persist(true) }, modifier = Modifier.testTag("memory-save")) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = { showProfileDialog = false }) { Text("Cancelar") } },
    )
    if (showNoteDialog) AlertDialog(
        onDismissRequest = { showNoteDialog = false },
        title = { Text("Recuerdo duradero") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(noteTitle, { noteTitle = it }, label = { Text(stringResource(R.string.memory_note_title)) },
                    singleLine = true, modifier = Modifier.testTag("memory-note-title"))
                OutlinedTextField(draft, { draft = it }, label = { Text("Nota") }, modifier = Modifier.testTag("memory-note-value"))
            }
        },
        confirmButton = { TextButton(onClick = { persist(false) },
            enabled = MemoryObservationContent.trimSpace(noteTitle).isNotEmpty() && MemoryObservationContent.trimSpace(draft).isNotEmpty(),
            modifier = Modifier.testTag("memory-save")) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = { showNoteDialog = false }) { Text("Cancelar") } },
    )
}

/** Manual live-observation read; each page uses the existing authorized get transaction. */
@Composable
private fun MemoryInspectorDialog(targetId: String, onChanged: () -> Unit, onJudge: (String) -> Unit = {}, retainedRelationId: String? = null, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var mutationPending by remember { mutableStateOf(false) }
    var candidateNotice by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf(0L) }
    var cursor by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var details by remember { mutableStateOf<PersonalMemoryHit?>(null) }
    var previous by remember { mutableStateOf<PersonalMemoryHit?>(null) }
    var state by remember { mutableStateOf<String?>(null) }
    fun requestPage(next: Long) {
        if (loading || mutationPending) return
        candidateNotice = null
        previous = if (next == 0L) null else details ?: previous
        details = null
        state = null
        cursor = next
        loading = true
        request++
    }
    AlertDialog(onDismissRequest = { if (!mutationPending) onClose() },
        title = { Text(stringResource(R.string.memory_inspector_title)) },
        text = {
            LaunchedEffect(targetId, request) {
                val thisRequest = request
                val requestedCursor = cursor
                val baseline = previous
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        val bridge = ChatDatabaseBridge.getInstance(context)
                        if (retainedRelationId == null) bridge.personalAgentGet(listOf(targetId), false, WorkspaceIdentity.CENTRAL_SESSION, true, requestedCursor).singleOrNull()
                        else bridge.personalAgentRelationDetails(retainedRelationId).let { retained ->
                            listOfNotNull(retained.source, retained.target).firstOrNull { it.id == targetId }
                        }
                    }
                    if (loaded != null && retainedRelationId == null) checkNotNull(loaded.history) { "memory_history_missing" }
                    // A closed/replaced dialog must never publish a late Bridge completion.
                    coroutineContext.ensureActive()
                    if (request == thisRequest) {
                        val changed = requestedCursor != 0L && baseline != null && loaded != null &&
                            (loaded.id != baseline.id || loaded.project != baseline.project || loaded.scope != baseline.scope ||
                                loaded.revision != baseline.revision || loaded.history?.total != baseline.history?.total)
                        details = if (changed) null else loaded
                        state = when { loaded == null -> "unavailable"; changed -> "changed"; else -> null }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    coroutineContext.ensureActive()
                    if (request == thisRequest) {
                        details = null
                        state = if (generateSequence<Throwable>(failure) { it.cause }.any { it.message == "memory_owner_or_target_denied" }) "unavailable" else "error"
                    }
                } finally {
                    if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && request == thisRequest) loading = false
                }
            }
            when {
                loading -> Text(stringResource(R.string.memory_inspector_loading), modifier = Modifier.testTag("memory-inspector-loading"))
                state != null -> Text(stringResource(when (state) {
                    "unavailable" -> R.string.memory_inspector_unavailable
                    "changed" -> R.string.memory_inspector_changed
                    else -> R.string.memory_inspector_error
                }), modifier = Modifier.testTag("memory-inspector-$state"))
                else -> details?.let { hit ->
                    LazyColumn(Modifier.heightIn(max = 440.dp).testTag("memory-inspector-content"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.memory_inspector_id, hit.id), modifier = Modifier.testTag("memory-inspector-id"))
                                hit.supersededById?.let { survivorId -> Text(stringResource(R.string.memory_consolidate_superseded_reference, survivorId), modifier = Modifier.testTag("memory-inspector-superseded")) }
                                Text(stringResource(R.string.memory_inspector_project, hit.project), modifier = Modifier.testTag("memory-inspector-project"))
                                Text(stringResource(R.string.memory_inspector_scope, hit.scope), modifier = Modifier.testTag("memory-inspector-scope"))
                                Text(stringResource(R.string.memory_inspector_type, hit.observationType), modifier = Modifier.testTag("memory-inspector-type"))
                                Text(stringResource(if (hit.reviewState == "needs_review") R.string.memory_review_due else R.string.memory_review_active), modifier = Modifier.testTag("memory-inspector-review-state"))
                                Text(stringResource(R.string.memory_review_date, hit.reviewAfter ?: stringResource(R.string.memory_review_no_date)), modifier = Modifier.testTag("memory-inspector-review-date"))
                                Text(stringResource(R.string.memory_review_metadata_only))
                                TextButton(enabled = retainedRelationId == null && !mutationPending && !loading && hit.deletedAt == null, modifier = Modifier.testTag("memory-review-mark"), onClick = mark@ {
                                    if (retainedRelationId != null || mutationPending || loading) return@mark
                                    val capturedId = hit.id
                                    val capturedProject = hit.project
                                    mutationPending = true
                                    scope.launch {
                                        try {
                                            val receipt = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentMarkReviewed(capturedId, capturedProject) }
                                            coroutineContext.ensureActive()
                                            check(receipt.id == capturedId && receipt.project == capturedProject)
                                            previous = null; details = null; state = null; cursor = 0L; loading = true; request++
                                            onChanged()
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) {
                                            coroutineContext.ensureActive()
                                            details = null
                                            state = if (generateSequence<Throwable>(failure) { it.cause }.any { it.message in setOf("memory_not_found", "memory_owner_or_target_denied", "memory_owner_denied") }) "unavailable" else "error"
                                        } finally { if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) mutationPending = false }
                                    }
                                }) { Text(stringResource(R.string.memory_review_mark)) }
                                if (hit.deletedAt != null) Text(stringResource(R.string.memory_judgment_deleted), modifier = Modifier.testTag("memory-inspector-retained-deleted"))
                                Text(hit.title ?: stringResource(R.string.memory_inspector_untitled), modifier = Modifier.testTag("memory-inspector-current-title"))
                                Text(hit.text.ifEmpty { stringResource(R.string.memory_inspector_empty_content) }, modifier = Modifier.testTag("memory-inspector-current-body"))
                                Text(stringResource(if (hit.pinned) R.string.memory_pinned else R.string.memory_not_pinned), modifier = Modifier.testTag("memory-inspector-pin"))
                                Text(stringResource(R.string.memory_relations_questions))
                                candidateNotice?.let { Text(it, modifier = Modifier.testTag("memory-relations-detection-result")) }
                                TextButton(enabled = retainedRelationId == null && !mutationPending && !loading && hit.deletedAt == null, modifier = Modifier.testTag("memory-relations-detect"), onClick = detect@ {
                                    if (retainedRelationId != null || mutationPending || loading) return@detect
                                    val capturedId = hit.id
                                    val capturedProject = hit.project
                                    mutationPending = true; candidateNotice = null
                                    scope.launch {
                                        try {
                                            val result = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentFindRelationCandidates(capturedId, capturedProject) }
                                            coroutineContext.ensureActive()
                                            candidateNotice = context.getString(if (result.status == "complete") R.string.memory_relations_detection_complete else R.string.memory_relations_detection_incomplete, result.candidates.size)
                                            onChanged()
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) {
                                            coroutineContext.ensureActive()
                                            candidateNotice = context.getString(R.string.memory_relations_detection_unknown)
                                        } finally { if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) mutationPending = false }
                                    }
                                }) { Text(stringResource(R.string.memory_relations_detect)) }
                                hit.relations.forEach { relation ->
                                    Text("${relation.relation} · ${relation.status} · ${relation.judgmentId}", modifier = Modifier.testTag("memory-judgment-annotation-${relation.judgmentId}"))
                                    TextButton(enabled = retainedRelationId == null && !mutationPending && !loading && relation.status != "orphaned", onClick = {
                                        if (!mutationPending && !loading) onJudge(relation.judgmentId)
                                    }, modifier = Modifier.testTag("memory-judgment-open-${relation.judgmentId}")) { Text(stringResource(R.string.memory_judgment_open)) }
                                }
                                Text(stringResource(R.string.memory_inspector_duplicates, hit.duplicateCount), modifier = Modifier.testTag("memory-inspector-duplicates"))
                                Text(stringResource(R.string.memory_inspector_last_seen, hit.lastSeenAt?.toString() ?: stringResource(R.string.memory_inspector_unknown)), modifier = Modifier.testTag("memory-inspector-last-seen"))
                                Text(stringResource(R.string.memory_inspector_revision, hit.revision), modifier = Modifier.testTag("memory-inspector-revision"))
                                hit.history?.let { Text(stringResource(R.string.memory_inspector_total, it.total), modifier = Modifier.testTag("memory-inspector-total")) }
                            }
                        }
                        items(hit.history?.versions.orEmpty(), key = { it.version }) { version ->
                            Column(Modifier.testTag("memory-inspector-version-${version.version}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.memory_inspector_version, version.version, version.createdAt))
                                Text(version.title ?: stringResource(R.string.memory_inspector_untitled), modifier = Modifier.testTag("memory-inspector-version-title-${version.version}"))
                                Text(version.content.ifEmpty { stringResource(R.string.memory_inspector_empty_content) }, modifier = Modifier.testTag("memory-inspector-version-body-${version.version}"))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (!loading && !mutationPending && (cursor != 0L || state == "changed")) TextButton(onClick = { requestPage(0L) }, modifier = Modifier.testTag("memory-inspector-newest")) {
                    Text(stringResource(R.string.memory_inspector_newest))
                }
                val page = details?.history
                if (retainedRelationId == null && !loading && !mutationPending && page?.hasMore == true && page.nextCursor != null) TextButton(onClick = { requestPage(page.nextCursor) }, modifier = Modifier.testTag("memory-inspector-older")) {
                    Text(stringResource(R.string.memory_inspector_older))
                }
                if (!loading && !mutationPending && state == "error") TextButton(onClick = { requestPage(cursor) }, modifier = Modifier.testTag("memory-inspector-retry")) {
                    Text(stringResource(R.string.memory_inspector_retry))
                }
            }
        },
        dismissButton = { TextButton(enabled = !mutationPending, onClick = { if (!mutationPending) onClose() }, modifier = Modifier.testTag("memory-inspector-close")) { Text(stringResource(R.string.memory_inspector_close)) } },
    )
}

/** Explicit local import. A picker result and a preview never authorize an application write. */
@Composable
private fun MemoryImportDialog(onClose: () -> Unit, onCommitted: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val controller = remember(context) { try { com.jarvys.agent.MemoryImportController.get(context) } catch (_: Exception) { null } }
    if (controller == null) {
        AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.memory_import_json_title)) }, text = {
            Text(stringResource(R.string.memory_import_json_failed), modifier = Modifier.testTag("memory-import-init-error"))
        }, confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("memory-import-close")) { Text(stringResource(R.string.memory_inspector_close)) } })
        return
    }
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<com.jarvys.agent.MemoryImportSource?>(null) }
    var prepared by remember { mutableStateOf<com.jarvys.agent.PreparedMemoryImport?>(null) }
    var mappings by remember { mutableStateOf<com.jarvys.agent.MemoryImportProjectPage?>(null) }
    var mappingOffset by remember { mutableStateOf(0L) }
    var destinations by remember { mutableStateOf<List<String>>(emptyList()) }
    var choosingMapping by remember { mutableStateOf<com.jarvys.agent.MemoryImportProjectRef?>(null) }
    var page by remember { mutableStateOf<com.jarvys.agent.MemoryImportPreviewPage?>(null) }
    var pageOffset by remember { mutableStateOf(0L) }
    var inspectedProject by remember { mutableStateOf<com.jarvys.agent.MemoryImportProjectRef?>(null) }
    var riskConfirmed by remember { mutableStateOf(false) }
    var promptsConfirmed by remember { mutableStateOf(false) }
    var contentConfirmed by remember { mutableStateOf(false) }
    var receipt by remember { mutableStateOf<com.jarvys.agent.MemoryImportReceipt?>(null) }
    var cleanup by remember { mutableStateOf<com.jarvys.agent.MemoryImportCleanupResult?>(null) }
    var token by remember { mutableStateOf<CancellationToken?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var observed by remember { mutableStateOf(controller.activeOperation()) }
    var pending by remember { mutableStateOf(false) }
    var requestId by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    fun invalidateConsent() { prepared = null; riskConfirmed = false; promptsConfirmed = false; contentConfirmed = false; choosingMapping = null }
    fun clearSource() { invalidateConsent(); source = null; mappings = null; mappingOffset = 0; destinations = emptyList(); page = null; pageOffset = 0; inspectedProject = null }
    fun discard() { requestId++; token?.cancel(); clearSource(); receipt = null; error = null }
    fun close() { discard(); job?.cancel(); onClose() }
    DisposableEffect(Unit) { onDispose { token?.cancel(); job?.cancel() } }
    LaunchedEffect(controller) { while (true) { observed = controller.activeOperation(); cleanup = controller.lastCleanup(); kotlinx.coroutines.delay(100) } }
    fun launchAction(currentToken: CancellationToken, action: suspend (Long) -> Unit) {
        if (pending) return
        val request = ++requestId
        pending = true; error = null; receipt = null; token = currentToken; choosingMapping = null
        job = scope.launch {
            try { action(request) }
            catch (cancelled: CancellationException) {
                currentToken.cancel()
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && request == requestId) { clearSource(); error = "cancelled" }
            } catch (_: Exception) {
                currentToken.cancel(); coroutineContext.ensureActive()
                if (request == requestId) { clearSource(); error = "unconfirmed" }
            } finally {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && request == requestId) {
                    pending = false; job = null; observed = controller.activeOperation(); cleanup = controller.lastCleanup()
                }
            }
        }
    }
    fun chooseFile() {
        if (pending || controller.activeOperation() != null) return
        clearSource(); cleanup = null
        val currentToken = CancellationToken.cancellable()
        launchAction(currentToken) { request ->
            val received = withContext(Dispatchers.IO) { controller.read(currentToken) {
                val uri = com.jarvys.agent.connectors.SafDocumentPickerCoordinator.get(context)
                    .pickForMemoryImport(arrayOf("application/json", "text/plain", "application/octet-stream"), currentToken) { currentToken.throwIfCancelled() }
                if (uri == null) { currentToken.cancel(); throw CancellationException("Memory import picker cancelled") }
                currentToken.throwIfCancelled(); require(uri.scheme == "content") { "memory_import_source_invalid" }
                val rawName = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                val unknown = context.getString(R.string.memory_export_json_destination_unknown)
                val provider = uri.authority?.takeIf { it.length <= 200 && it.matches(Regex("[A-Za-z0-9._-]+")) } ?: unknown
                val name = rawName?.take(256)?.filter { !Character.isISOControl(it) && it !in '\u202a'..'\u202e' && it !in '\u2066'..'\u2069' }?.trim()?.takeIf { it.isNotEmpty() && !it.contains("://") } ?: unknown
                currentToken.throwIfCancelled()
                val input = context.contentResolver.openInputStream(uri) ?: error("memory_import_source_unavailable")
                com.jarvys.agent.MemoryImportInput(input, provider, name)
            } }
            val choices = withContext(Dispatchers.IO) { controller.destinations(currentToken) }
            val first = withContext(Dispatchers.IO) { controller.projectPage(received, 0L, 32, currentToken) }
            coroutineContext.ensureActive(); currentToken.throwIfCancelled()
            if (request == requestId) { source = received; mappings = first; mappingOffset = 0; destinations = choices; observed = received.operationHandle }
        }
    }
    fun readMappings(offset: Long) {
        val current = source ?: return; val currentToken = token ?: return
        launchAction(currentToken) { request ->
            val received = withContext(Dispatchers.IO) { controller.projectPage(current, offset, 32, currentToken) }
            coroutineContext.ensureActive(); currentToken.throwIfCancelled()
            if (request == requestId) { mappings = received; mappingOffset = offset }
        }
    }
    fun mapProject(ref: com.jarvys.agent.MemoryImportProjectRef, destination: String) {
        val current = source ?: return; val currentToken = token ?: return
        invalidateConsent()
        launchAction(currentToken) { request ->
            val received = withContext(Dispatchers.IO) {
                controller.setProject(current, ref, destination, currentToken)
                controller.projectPage(current, mappingOffset, 32, currentToken)
            }
            coroutineContext.ensureActive(); currentToken.throwIfCancelled()
            if (request == requestId) mappings = received
        }
    }
    fun readContent(offset: Long) {
        val current = source ?: return; val currentToken = token ?: return
        launchAction(currentToken) { request ->
            val received = withContext(Dispatchers.IO) { controller.preview(current, offset, 32768, currentToken) }
            coroutineContext.ensureActive(); currentToken.throwIfCancelled()
            if (request == requestId) { page = received; pageOffset = offset; inspectedProject = null }
        }
    }
    fun readProject(ref: com.jarvys.agent.MemoryImportProjectRef, offset: Long) {
        val current = source ?: return; val currentToken = token ?: return
        launchAction(currentToken) { request ->
            val received = withContext(Dispatchers.IO) { controller.previewProject(current, ref, offset, 32768, currentToken) }
            coroutineContext.ensureActive(); currentToken.throwIfCancelled()
            if (request == requestId) { page = received; pageOffset = offset; inspectedProject = ref }
        }
    }
    fun validate() {
        val current = source ?: return; val currentToken = token ?: return
        if (current.riskyFieldCount > 0 && !riskConfirmed) return
        prepared = null; contentConfirmed = false; promptsConfirmed = false
        val capturedRisk = riskConfirmed
        launchAction(currentToken) { request ->
            val received = withContext(Dispatchers.IO) { controller.validate(current, capturedRisk, currentToken) }
            coroutineContext.ensureActive(); currentToken.throwIfCancelled()
            if (request == requestId) prepared = received
        }
    }
    fun apply() {
        val current = prepared ?: return; val currentToken = token ?: return
        if (!contentConfirmed || (current.source.requiresPromptConsent && !promptsConfirmed) || (current.riskyFieldCount > 0 && !riskConfirmed)) return
        val capturedPrompts = promptsConfirmed; val capturedRisk = riskConfirmed
        launchAction(currentToken) { request ->
            val received = withContext(Dispatchers.IO) { controller.apply(current, capturedPrompts, capturedRisk, currentToken) }
            coroutineContext.ensureActive(); currentToken.throwIfCancelled()
            if (request == requestId) { clearSource(); token = null; receipt = received; onCommitted() }
        }
    }
    val busy = pending || (observed != null && observed !== source?.operationHandle)
    AlertDialog(onDismissRequest = { close() }, title = { Text(stringResource(R.string.memory_import_json_title)) }, text = {
        LazyColumn(Modifier.heightIn(max = 540.dp).testTag("memory-import-content"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.memory_import_json_intro))
                Text(stringResource(R.string.memory_import_json_future))
                if (busy) Text(stringResource(R.string.memory_import_json_busy), modifier = Modifier.testTag("memory-import-busy"))
                TextButton(enabled = !busy && source == null, onClick = { chooseFile() }, modifier = Modifier.testTag("memory-import-choose")) { Text(stringResource(R.string.memory_import_json_choose)) }
                error?.let { Text(stringResource(if (it == "cancelled") R.string.memory_import_json_cancelled else R.string.memory_import_json_failed), modifier = Modifier.testTag("memory-import-error")) }
            }
            source?.let { current ->
                item {
                    Text(stringResource(R.string.memory_import_json_source, current.sizeBytes, current.sha256), modifier = Modifier.testTag("memory-import-source"))
                    Text(stringResource(R.string.memory_import_json_provider, current.providerLabel, current.displayName))
                    Text(stringResource(R.string.memory_import_json_mapping))
                }
                items(current.counts.entries.toList()) { Text("${it.key}: ${it.value}", modifier = Modifier.testTag("memory-import-count-${it.key}")) }
                mappings?.entries?.let { entries -> itemsIndexed(entries) { index, entry ->
                    Column {
                        Text(if (entry.kind == "undeclared") stringResource(R.string.memory_import_json_undeclared) else entry.display)
                        if (entry.displayTruncated) Text(stringResource(R.string.memory_import_json_label_truncated), modifier = Modifier.testTag("memory-import-group-truncated-${mappingOffset + index}"))
                        if (entry.kind != "undeclared") TextButton(enabled = !pending, onClick = { readProject(entry.ref, 0) }, modifier = Modifier.testTag("memory-import-group-inspect-${mappingOffset + index}")) { Text(stringResource(R.string.memory_import_json_inspect_group)) }
                        if (!entry.editable) {
                            Text(stringResource(R.string.memory_import_json_anchor))
                            Text(entry.destination?.let { if (it == WorkspaceIdentity.CENTRAL_SESSION) context.getString(R.string.memory_import_json_central) else it } ?: stringResource(R.string.memory_import_json_unmapped))
                        } else Box {
                            TextButton(enabled = !pending, onClick = { choosingMapping = entry.ref }, modifier = Modifier.testTag("memory-import-group-destination-${mappingOffset + index}")) {
                                Text(entry.destination?.let { if (it == WorkspaceIdentity.CENTRAL_SESSION) context.getString(R.string.memory_import_json_central) else it } ?: stringResource(R.string.memory_import_json_unmapped))
                            }
                            DropdownMenu(expanded = choosingMapping === entry.ref, onDismissRequest = { choosingMapping = null }) {
                                destinations.forEach { destination -> DropdownMenuItem(text = { Text(if (destination == WorkspaceIdentity.CENTRAL_SESSION) stringResource(R.string.memory_import_json_central) else destination) }, onClick = { mapProject(entry.ref, destination) }) }
                            }
                        }
                    }
                } }
                item {
                    TextButton(enabled = !pending && mappingOffset > 0, onClick = { readMappings(0) }, modifier = Modifier.testTag("memory-import-mapping-first")) { Text(stringResource(R.string.memory_import_json_mapping_first)) }
                    mappings?.nextOffset?.let { next -> TextButton(enabled = !pending, onClick = { readMappings(next) }, modifier = Modifier.testTag("memory-import-mapping-next")) { Text(stringResource(R.string.memory_import_json_mapping_next)) } }
                    if (current.riskyFieldCount > 0) {
                        Text(stringResource(R.string.memory_import_json_risk, current.riskyFieldCount), modifier = Modifier.testTag("memory-import-risk"))
                        Row { Checkbox(checked = riskConfirmed, enabled = !pending, onCheckedChange = { prepared = null; contentConfirmed = false; promptsConfirmed = false; riskConfirmed = it }, modifier = Modifier.testTag("memory-import-risk-consent")); Text(stringResource(R.string.memory_import_json_risk_consent)) }
                    }
                    TextButton(enabled = !pending, onClick = { readContent(0) }, modifier = Modifier.testTag("memory-import-view")) { Text(stringResource(R.string.memory_import_json_view)) }
                    TextButton(enabled = !pending && (current.riskyFieldCount == 0L || riskConfirmed), onClick = { validate() }, modifier = Modifier.testTag("memory-import-validate")) { Text(stringResource(R.string.memory_import_json_validate)) }
                }
            }
            page?.let { shown -> item {
                Text(stringResource(if (inspectedProject == null) R.string.memory_import_json_page else R.string.memory_import_json_project_page, pageOffset, shown.totalBytes), modifier = Modifier.testTag("memory-import-page-range"))
                Text(shown.text, modifier = Modifier.testTag("memory-import-json-page"))
                TextButton(enabled = !pending && pageOffset > 0, onClick = { inspectedProject?.let { readProject(it, 0) } ?: readContent(0) }, modifier = Modifier.testTag("memory-import-page-first")) { Text(stringResource(R.string.memory_import_json_first)) }
                shown.nextOffset?.let { next -> TextButton(enabled = !pending, onClick = { inspectedProject?.let { readProject(it, next) } ?: readContent(next) }, modifier = Modifier.testTag("memory-import-page-next")) { Text(stringResource(R.string.memory_import_json_next)) } }
            } }
            prepared?.let { validated ->
                item { Text(stringResource(R.string.memory_import_json_impact, validated.fingerprint), modifier = Modifier.testTag("memory-import-impact")) }
                items(validated.impact.entries.toList()) { Text("${it.key}: ${it.value}", modifier = Modifier.testTag("memory-import-impact-${it.key}")) }
                item {
                    if (validated.source.requiresPromptConsent) Row { Checkbox(checked = promptsConfirmed, enabled = !pending, onCheckedChange = { promptsConfirmed = it }, modifier = Modifier.testTag("memory-import-prompts-consent")); Text(stringResource(R.string.memory_import_json_prompts)) }
                    Row { Checkbox(checked = contentConfirmed, enabled = !pending, onCheckedChange = { contentConfirmed = it }, modifier = Modifier.testTag("memory-import-content-consent")); Text(stringResource(R.string.memory_import_json_content_consent)) }
                    TextButton(enabled = !pending && contentConfirmed && (!validated.source.requiresPromptConsent || promptsConfirmed) && (validated.riskyFieldCount == 0L || riskConfirmed), onClick = { apply() }, modifier = Modifier.testTag("memory-import-apply")) { Text(stringResource(R.string.memory_import_json_apply)) }
                }
            }
            item {
                if (source != null) TextButton(enabled = !pending, onClick = { discard() }, modifier = Modifier.testTag("memory-import-discard")) { Text(stringResource(R.string.memory_import_json_discard)) }
                receipt?.let { received ->
                    Text(stringResource(R.string.memory_import_json_receipt, received.status, received.cleanupState), modifier = Modifier.testTag("memory-import-receipt"))
                    Text(stringResource(R.string.memory_import_json_source, received.sizeBytes, received.sha256))
                    received.counts.forEach { (name, count) -> Text("$name: $count") }
                }
                if (source == null && !busy) cleanup?.let { Text(stringResource(R.string.memory_import_json_cleanup, it.cleanupState, it.physicalBusy.toString()), modifier = Modifier.testTag("memory-import-cleanup")) }
            }
        }
    }, confirmButton = {
        TextButton(onClick = { close() }, modifier = Modifier.testTag("memory-import-close")) { Text(stringResource(R.string.memory_inspector_close)) }
    }, dismissButton = {
        TextButton(enabled = pending || observed != null, onClick = { if (pending) token?.cancel() else observed?.let { controller.cancelIfCurrent(it) }; clearSource() }, modifier = Modifier.testTag("memory-import-cancel")) { Text(stringResource(R.string.memory_import_json_cancel)) }
    })
}

/** Explicit export of one sealed private snapshot. Only the final action may open the selected output. */
@Composable
private fun MemoryExportDialog(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val controller = remember(context) { try { com.jarvys.agent.MemoryExportController.get(context) } catch (_: Exception) { null } }
    if (controller == null) {
        AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.memory_export_json_title)) }, text = {
            Column(Modifier.testTag("memory-export-content"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.memory_export_json_failed), modifier = Modifier.testTag("memory-export-init-error"))
                TextButton(enabled = false, onClick = {}, modifier = Modifier.testTag("memory-export-prepare")) { Text(stringResource(R.string.memory_export_json_prepare)) }
            }
        }, confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("memory-export-close")) { Text(stringResource(R.string.memory_inspector_close)) } })
        return
    }
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<String>?>(null) }
    var projectsFailed by remember { mutableStateOf(false) }
    var project by remember { mutableStateOf<String?>(null) }
    var includePrompts by remember { mutableStateOf(false) }
    var promptsConfirmed by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf<com.jarvys.agent.PreparedMemoryExport?>(null) }
    var page by remember { mutableStateOf<com.jarvys.agent.MemoryExportPreviewPage?>(null) }
    var pageOffset by remember { mutableStateOf(0L) }
    var contentConfirmed by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf<android.net.Uri?>(null) }
    var destinationProvider by remember { mutableStateOf("") }
    var destinationName by remember { mutableStateOf("") }
    var receipt by remember { mutableStateOf<com.jarvys.agent.MemoryExportReceipt?>(null) }
    var cleanup by remember { mutableStateOf<com.jarvys.agent.MemoryExportCleanupResult?>(null) }
    var activeToken by remember { mutableStateOf<CancellationToken?>(null) }
    var activeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var observedHandle by remember { mutableStateOf(controller.activeOperation()) }
    var pending by remember { mutableStateOf(false) }
    var requestId by remember { mutableStateOf(0L) }
    var failure by remember { mutableStateOf<String?>(null) }
    var choicesToken by remember { mutableStateOf<CancellationToken?>(null) }
    var choicesJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun clearPrepared() { prepared = null; page = null; pageOffset = 0; contentConfirmed = false; destination = null; destinationProvider = ""; destinationName = "" }
    fun invalidate() {
        requestId++; activeToken?.cancel(); clearPrepared(); receipt = null; cleanup = null; failure = null
        // The owner cancellation callback discards staging after physical unwind, even if a return was lost.
    }
    fun close() { invalidate(); activeJob?.cancel(); choicesToken?.cancel(); choicesJob?.cancel(); onClose() }
    DisposableEffect(Unit) { onDispose { activeToken?.cancel(); activeJob?.cancel(); choicesToken?.cancel(); choicesJob?.cancel() } }
    fun loadProjects() {
        choicesToken?.cancel(); choicesJob?.cancel(); projects = null; projectsFailed = false
        val token = CancellationToken.cancellable(); choicesToken = token
        choicesJob = scope.launch {
            try { val received = withContext(Dispatchers.IO) { controller.projects(token) }; coroutineContext.ensureActive(); token.throwIfCancelled(); projects = received }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { coroutineContext.ensureActive(); projectsFailed = true }
        }
    }
    fun launchAction(token: CancellationToken, action: suspend (Long) -> Unit) {
        if (pending) return
        val captured = ++requestId
        pending = true; failure = null; receipt = null; cleanup = null; activeToken = token
        activeJob = scope.launch {
            try { action(captured) }
            catch (cancelled: CancellationException) {
                token.cancel()
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && captured == requestId) { clearPrepared(); failure = "cancelled" }
            } catch (_: Exception) {
                token.cancel(); coroutineContext.ensureActive()
                if (captured == requestId) { clearPrepared(); failure = "unconfirmed" }
            } finally {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) { pending = false; activeJob = null; observedHandle = controller.activeOperation(); cleanup = controller.lastCleanup() }
            }
        }
    }
    fun prepareSnapshot() {
        if (pending || controller.activeOperation() != null || projects == null || (includePrompts && !promptsConfirmed)) return
        clearPrepared()
        val options = com.jarvys.agent.MemoryExportOptions(project, includePrompts)
        val token = CancellationToken.cancellable()
        launchAction(token) { captured ->
            val snapshot = withContext(Dispatchers.IO) { controller.prepare(options, token) }
            val first = withContext(Dispatchers.IO) { controller.preview(snapshot, 0L, 32768, token) }
            coroutineContext.ensureActive(); token.throwIfCancelled()
            if (captured == requestId) { prepared = snapshot; page = first; pageOffset = 0; contentConfirmed = false; observedHandle = snapshot.operationHandle }
        }
    }
    fun readPage(offset: Long) {
        val snapshot = prepared ?: return; val token = activeToken ?: return
        launchAction(token) { captured ->
            val next = withContext(Dispatchers.IO) { controller.preview(snapshot, offset, 32768, token) }
            coroutineContext.ensureActive(); token.throwIfCancelled()
            if (captured == requestId) { page = next; pageOffset = offset }
        }
    }
    fun chooseDestination() {
        if (!contentConfirmed) return
        val snapshot = prepared ?: return
        val token = activeToken ?: return
        destination = null; destinationProvider = ""; destinationName = ""
        launchAction(token) { captured ->
            val picked = withContext(Dispatchers.IO) { controller.destination(snapshot, token) {
                val uri = com.jarvys.agent.connectors.SafDocumentPickerCoordinator.get(context).createForMemoryExport("jarvys-memory.json", "application/json", token) { token.throwIfCancelled() }
                if (uri == null) null else {
                    token.throwIfCancelled(); require(uri.scheme == "content") { "memory_export_destination_invalid" }
                    val rawName = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    val unknown = context.getString(R.string.memory_export_json_destination_unknown)
                    val provider = uri.authority?.takeIf { it.length <= 200 && it.matches(Regex("[A-Za-z0-9._-]+")) } ?: unknown
                    val name = rawName?.take(256)?.filter { !Character.isISOControl(it) && it !in '\u202a'..'\u202e' && it !in '\u2066'..'\u2069' }?.trim()?.takeIf { it.isNotEmpty() && !it.contains("://") } ?: unknown
                    token.throwIfCancelled(); Triple(uri, provider, name)
                }
            } }
            coroutineContext.ensureActive(); token.throwIfCancelled()
            if (captured == requestId) {
                if (picked == null) { token.cancel(); clearPrepared(); failure = "picker_cancelled" }
                else { destination = picked.first; destinationProvider = picked.second; destinationName = picked.third }
            }
        }
    }
    fun writeSnapshot() {
        val snapshot = prepared ?: return; val uri = destination ?: return; val token = activeToken ?: return
        if (!contentConfirmed || pending) return
        launchAction(token) { captured ->
            val received = withContext(Dispatchers.IO) {
                controller.write(snapshot, true, true, {
                    token.throwIfCancelled()
                    context.contentResolver.openOutputStream(uri, "wt") ?: error("memory_export_destination_unavailable")
                }, token)
            }
            coroutineContext.ensureActive(); token.throwIfCancelled()
            if (captured == requestId) { clearPrepared(); receipt = received; activeToken = null }
        }
    }
    val foreignBusy = observedHandle != null && observedHandle !== prepared?.operationHandle
    val busy = pending || foreignBusy
    AlertDialog(onDismissRequest = { close() }, title = { Text(stringResource(R.string.memory_export_json_title)) }, text = {
        LaunchedEffect(Unit) { loadProjects() }
        LaunchedEffect(controller) { while (true) { observedHandle = controller.activeOperation(); if (prepared == null && !pending) cleanup = controller.lastCleanup(); kotlinx.coroutines.delay(100) } }
        LazyColumn(Modifier.heightIn(max = 480.dp).testTag("memory-export-content"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.memory_export_json_explanation))
                TextButton(enabled = !pending && projects != null, onClick = { if (project != null) { invalidate(); project = null; promptsConfirmed = false } }, modifier = Modifier.testTag("memory-export-project-all")) { Text((if (project == null) "✓ " else "") + stringResource(R.string.memory_doctor_all_projects)) }
            }
            items(projects.orEmpty(), key = { "project-$it" }) { selected ->
                TextButton(enabled = !pending, onClick = { if (project != selected) { invalidate(); project = selected; promptsConfirmed = false } }, modifier = Modifier.testTag("memory-export-project-$selected")) { Text((if (project == selected) "✓ " else "") + selected) }
            }
            item {
                if (projectsFailed) Text(stringResource(R.string.memory_export_json_projects_failed), modifier = Modifier.testTag("memory-export-projects-failed"))
                TextButton(enabled = !busy && prepared == null, onClick = { loadProjects() }, modifier = Modifier.testTag("memory-export-projects-reload")) { Text(stringResource(R.string.memory_doctor_choices_reload)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.memory_export_json_prompts))
                    Switch(checked = includePrompts, enabled = !pending, onCheckedChange = { invalidate(); includePrompts = it; promptsConfirmed = false }, modifier = Modifier.testTag("memory-export-prompts"))
                }
                if (includePrompts) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Checkbox(checked = promptsConfirmed, enabled = !pending, onCheckedChange = { invalidate(); promptsConfirmed = it }, modifier = Modifier.testTag("memory-export-prompts-consent"))
                    Text(stringResource(R.string.memory_export_json_prompts_consent))
                }
                TextButton(enabled = !busy && prepared == null && projects != null && (!includePrompts || promptsConfirmed), onClick = { prepareSnapshot() }, modifier = Modifier.testTag("memory-export-prepare")) { Text(stringResource(R.string.memory_export_json_prepare)) }
                if (busy) Text(stringResource(R.string.memory_export_json_busy), modifier = Modifier.testTag("memory-export-busy"))
                failure?.let { Text(stringResource(when (it) { "cancelled" -> R.string.memory_export_json_cancelled; "picker_cancelled" -> R.string.memory_export_json_picker_cancelled; else -> R.string.memory_export_json_failed }), modifier = Modifier.testTag("memory-export-error")) }
            }
            prepared?.let { snapshot ->
                item {
                    Text(stringResource(R.string.memory_export_json_snapshot, snapshot.sizeBytes, snapshot.sha256, snapshot.exportedAt, snapshot.includePrompts.toString()), modifier = Modifier.testTag("memory-export-snapshot"))
                    Text(stringResource(R.string.memory_export_json_compatibility, snapshot.unboundObservations))
                    Text(stringResource(R.string.memory_export_json_unresolved_references, snapshot.unresolvedRequiredReferences), modifier = Modifier.testTag("memory-export-unresolved-references"))
                    if (snapshot.unresolvedRequiredReferences > 0) Text(stringResource(R.string.memory_export_json_not_self_contained))
                    Text(stringResource(R.string.memory_export_json_exclusions))
                    Text(stringResource(R.string.memory_export_json_privacy))
                }
                item { Text(stringResource(R.string.memory_export_json_requested_projects)) }
                items(snapshot.requestedProjects, key = { "selected-$it" }) { Text(it, modifier = Modifier.testTag("memory-export-selected-$it")) }
                item {
                    Text(stringResource(R.string.memory_export_json_support_sessions, snapshot.supportingSessions), modifier = Modifier.testTag("memory-export-supporting-sessions"))
                    if (snapshot.supportingSessionProjects.isNotEmpty()) Text(stringResource(R.string.memory_export_json_support_projects))
                }
                items(snapshot.supportingSessionProjects, key = { "support-$it" }) { Text(it, modifier = Modifier.testTag("memory-export-supporting-$it")) }
                items(snapshot.counts.entries.toList(), key = { "count-${it.key}" }) { Text("${it.key}: ${it.value}", modifier = Modifier.testTag("memory-export-count-${it.key}")) }
                items(snapshot.warnings) { Text(it) }
                page?.let { shown ->
                    item {
                        Text(stringResource(R.string.memory_export_json_page, pageOffset, shown.totalBytes), modifier = Modifier.testTag("memory-export-page-range"))
                        Text(shown.text, modifier = Modifier.testTag("memory-export-json-page"))
                    }
                    item {
                        TextButton(enabled = !pending && pageOffset > 0, onClick = { readPage(0) }, modifier = Modifier.testTag("memory-export-page-first")) { Text(stringResource(R.string.memory_export_json_first_page)) }
                        shown.nextOffset?.let { next -> TextButton(enabled = !pending, onClick = { readPage(next) }, modifier = Modifier.testTag("memory-export-page-next")) { Text(stringResource(R.string.memory_export_json_next_page)) } }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.Checkbox(checked = contentConfirmed, enabled = !pending, onCheckedChange = { contentConfirmed = it; destination = null }, modifier = Modifier.testTag("memory-export-content-consent"))
                        Text(stringResource(R.string.memory_export_json_content_consent))
                    }
                    TextButton(enabled = !pending && contentConfirmed, onClick = { chooseDestination() }, modifier = Modifier.testTag("memory-export-pick")) { Text(stringResource(R.string.memory_export_json_pick)) }
                    Text(stringResource(R.string.memory_export_json_destination_caveat))
                    if (destination != null) {
                        Text(stringResource(R.string.memory_export_json_destination, destinationProvider, destinationName), modifier = Modifier.testTag("memory-export-destination"))
                        TextButton(enabled = !pending && contentConfirmed, onClick = { writeSnapshot() }, modifier = Modifier.testTag("memory-export-write")) { Text(stringResource(R.string.memory_export_json_write)) }
                    }
                    TextButton(enabled = !pending, onClick = { invalidate() }, modifier = Modifier.testTag("memory-export-discard")) { Text(stringResource(R.string.memory_export_json_discard)) }
                }
            }
            receipt?.let { received -> item {
                Text(stringResource(R.string.memory_export_json_receipt, received.status, received.sizeBytes, received.sha256, received.cleanupState, received.externalMayExist.toString()), modifier = Modifier.testTag("memory-export-receipt"))
                Text(stringResource(R.string.memory_export_json_receipt_limit))
            } }
            if (prepared == null && !busy) cleanup?.let { actual -> item { Text(stringResource(R.string.memory_export_json_cleanup, actual.cleanupState, actual.physicalBusy.toString()), modifier = Modifier.testTag("memory-export-cleanup")) } }
        }
    }, confirmButton = {
        if (busy) TextButton(onClick = { if (pending) activeToken?.cancel() else observedHandle?.let { controller.cancelIfCurrent(it) } }, modifier = Modifier.testTag("memory-export-cancel")) { Text(stringResource(R.string.memory_export_json_cancel)) }
    }, dismissButton = { TextButton(onClick = { close() }, modifier = Modifier.testTag("memory-export-close")) { Text(stringResource(R.string.memory_inspector_close)) } })
}

/** Explicit observation only. Neither choices nor execution may construct a user store or repair it. */
@Composable
private fun MemoryDoctorDialog(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val initialized = remember(context) {
        try { Pair<com.jarvys.agent.MemoryDoctorController?, String?>(com.jarvys.agent.MemoryDoctorController.get(context), null) }
        catch (_: com.jarvys.agent.MemoryDoctorUnavailable) { Pair(null, "unavailable") }
        catch (_: Exception) { Pair(null, "failed") }
    }
    val controller = initialized.first
    if (controller == null) {
        AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.memory_doctor_title)) }, text = {
            Column(Modifier.testTag("memory-doctor-content"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.memory_doctor_explanation))
                Text(stringResource(if (initialized.second == "unavailable") R.string.memory_doctor_choices_failed else R.string.memory_doctor_failed), modifier = Modifier.testTag("memory-doctor-init-error"))
                Text(stringResource(R.string.memory_doctor_selection, stringResource(R.string.memory_doctor_all_projects), stringResource(R.string.memory_doctor_all_checks)))
                TextButton(enabled = false, onClick = {}, modifier = Modifier.testTag("memory-doctor-start")) { Text(stringResource(R.string.memory_doctor_start)) }
            }
        }, confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("memory-doctor-close")) { Text(stringResource(R.string.memory_inspector_close)) } })
        return
    }
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf<String?>(null) }
    var check by remember { mutableStateOf<String?>(null) }
    var choices by remember { mutableStateOf<List<String>?>(null) }
    var choicesLoading by remember { mutableStateOf(false) }
    var choicesFailed by remember { mutableStateOf(false) }
    var choicesUnavailable by remember { mutableStateOf(false) }
    var choicesToken by remember { mutableStateOf<CancellationToken?>(null) }
    var choicesJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var report by remember { mutableStateOf<com.jarvys.agent.MemoryDoctorReport?>(null) }
    var requestId by remember { mutableStateOf(0L) }
    var pending by remember { mutableStateOf(false) }
    var observedOperation by remember { mutableStateOf(controller.activeOperation()) }
    var ownToken by remember { mutableStateOf<CancellationToken?>(null) }
    var ownJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    fun invalidate() { requestId++; ownToken?.cancel(); report = null; failure = null }
    fun close() { invalidate(); ownJob?.cancel(); choicesToken?.cancel(); choicesJob?.cancel(); onClose() }
    DisposableEffect(Unit) { onDispose { ownToken?.cancel(); ownJob?.cancel(); choicesToken?.cancel(); choicesJob?.cancel() } }
    fun loadChoices() {
        if (choicesLoading) return
        invalidate(); choices = null; choicesFailed = false; choicesUnavailable = false; choicesLoading = true
        val token = CancellationToken.cancellable(); choicesToken = token
        choicesJob = scope.launch {
            try {
                val received = withContext(Dispatchers.IO) { controller.projects(token) }
                coroutineContext.ensureActive(); token.throwIfCancelled()
                choices = received
                if (project != null && project !in received) project = null
            } catch (cancelled: CancellationException) {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && choicesToken === token) { choices = null; report = null; failure = "cancelled" }
            }
            catch (_: com.jarvys.agent.MemoryDoctorUnavailable) { coroutineContext.ensureActive(); project = null; choicesUnavailable = true }
            catch (_: Exception) { coroutineContext.ensureActive(); choicesFailed = true }
            finally { if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && choicesToken === token) { choicesLoading = false; choicesToken = null; choicesJob = null } }
        }
    }
    fun start() {
        if (pending || controller.isRunning() || (choices == null && !choicesUnavailable) || choicesLoading || choicesFailed) return
        val options = com.jarvys.agent.MemoryDoctorOptions(project, check)
        val captured = ++requestId
        val token = CancellationToken.cancellable()
        report = null; failure = null; ownToken = token; pending = true
        ownJob = scope.launch {
            try {
                val received = withContext(Dispatchers.IO) { controller.execute(options, token) }
                coroutineContext.ensureActive(); token.throwIfCancelled()
                if (captured == requestId) report = received
            } catch (cancelled: CancellationException) {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && captured == requestId) failure = "cancelled"
            } catch (_: Exception) {
                coroutineContext.ensureActive()
                if (captured == requestId) { report = null; failure = "failed" }
            } finally {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && ownToken === token) {
                    pending = false; ownToken = null; ownJob = null; observedOperation = controller.activeOperation()
                }
            }
        }
    }
    val busy = pending || observedOperation != null
    AlertDialog(onDismissRequest = { close() }, title = { Text(stringResource(R.string.memory_doctor_title)) }, text = {
        LaunchedEffect(Unit) { loadChoices() }
        LaunchedEffect(controller) { while (true) { observedOperation = controller.activeOperation(); kotlinx.coroutines.delay(100) } }
        LazyColumn(Modifier.heightIn(max = 440.dp).testTag("memory-doctor-content"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.memory_doctor_explanation))
                TextButton(enabled = choices != null || choicesUnavailable, onClick = { if (project != null) { invalidate(); project = null } }, modifier = Modifier.testTag("memory-doctor-project-all")) {
                    Text((if (project == null) "✓ " else "") + stringResource(R.string.memory_doctor_all_projects))
                }
            }
            items(choices.orEmpty(), key = { "project-$it" }) { selected ->
                TextButton(onClick = { if (project != selected) { invalidate(); project = selected } }, modifier = Modifier.testTag("memory-doctor-project-$selected")) { Text((if (project == selected) "✓ " else "") + selected) }
            }
            item {
                if (choicesLoading) Text(stringResource(R.string.memory_doctor_choices_loading), modifier = Modifier.testTag("memory-doctor-choices-loading"))
                if (choicesFailed || choicesUnavailable) Text(stringResource(R.string.memory_doctor_choices_failed), modifier = Modifier.testTag("memory-doctor-choices-failed"))
                TextButton(enabled = !busy && !choicesLoading, onClick = { loadChoices() }, modifier = Modifier.testTag("memory-doctor-choices-reload")) { Text(stringResource(R.string.memory_doctor_choices_reload)) }
                TextButton(onClick = { if (check != null) { invalidate(); check = null } }, modifier = Modifier.testTag("memory-doctor-check-all")) { Text((if (check == null) "✓ " else "") + stringResource(R.string.memory_doctor_all_checks)) }
            }
            items(com.jarvys.agent.MemoryDoctorChecks.ids, key = { "option-$it" }) { selected ->
                TextButton(onClick = { if (check != selected) { invalidate(); check = selected } }, modifier = Modifier.testTag("memory-doctor-check-$selected")) { Text((if (check == selected) "✓ " else "") + selected) }
            }
            item {
                Text(stringResource(R.string.memory_doctor_selection, project ?: stringResource(R.string.memory_doctor_all_projects), check ?: stringResource(R.string.memory_doctor_all_checks)), modifier = Modifier.testTag("memory-doctor-selection"))
                TextButton(enabled = !busy && (choices != null || choicesUnavailable) && !choicesLoading && !choicesFailed, onClick = { start() }, modifier = Modifier.testTag("memory-doctor-start")) { Text(stringResource(R.string.memory_doctor_start)) }
                if (busy) Text(stringResource(R.string.memory_doctor_busy), modifier = Modifier.testTag("memory-doctor-busy"))
                failure?.let { Text(stringResource(if (it == "cancelled") R.string.memory_doctor_cancelled else R.string.memory_doctor_failed), modifier = Modifier.testTag("memory-doctor-error")) }
            }
            report?.let { received ->
                item {
                    Text(stringResource(R.string.memory_doctor_report, received.status, received.selectedProject ?: stringResource(R.string.memory_doctor_all_projects), received.coverageComplete.toString()), modifier = Modifier.testTag("memory-doctor-report"))
                    Text(stringResource(R.string.memory_doctor_coverage_notice))
                    Text(stringResource(R.string.memory_doctor_evaluations, received.summary.total, received.summary.ok, received.summary.warnings, received.summary.blocked, received.summary.errors), modifier = Modifier.testTag("memory-doctor-evaluations"))
                    Text(stringResource(R.string.memory_doctor_coverages, received.summary.complete, received.summary.partial, received.summary.notApplicable, received.summary.unavailable, received.summary.domainPending), modifier = Modifier.testTag("memory-doctor-coverages"))
                }
                items(received.checks, key = { it.checkId }) { item ->
                    Column(Modifier.testTag("memory-doctor-result-${item.checkId}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.checkId)
                        Text(stringResource(R.string.memory_doctor_check_result, item.result ?: stringResource(R.string.memory_doctor_not_evaluated), item.coverage))
                        Text(item.reasonCode); Text(item.message)
                        item.evidence.forEach { (key, value) -> Text("$key: $value") }
                        item.findings.forEachIndexed { index, finding ->
                            Column(Modifier.testTag("memory-doctor-finding-${item.checkId}-$index")) {
                                finding.forEach { (key, value) -> Text("$key: $value") }
                            }
                        }
                        Text(stringResource(R.string.memory_doctor_next_step, item.safeNextStep))
                        Text(stringResource(R.string.memory_doctor_confirmation, item.requiresConfirmation.toString()))
                    }
                }
            }
        }
    }, confirmButton = {
        if (busy) TextButton(onClick = { if (ownToken != null) ownToken?.cancel() else observedOperation?.let { controller.cancelIfCurrent(it) } }, modifier = Modifier.testTag("memory-doctor-cancel")) { Text(stringResource(R.string.memory_doctor_cancel)) }
    }, dismissButton = { TextButton(onClick = { close() }, modifier = Modifier.testTag("memory-doctor-close")) { Text(stringResource(R.string.memory_inspector_close)) } })
}

/** Local synthetic selftest only; this dialog never opens a user Bridge or provider configuration. */
@Composable
private fun MemorySelftestDialog(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val controller = remember(context) { com.jarvys.agent.MemorySelftestController.get(context) }
    val scope = rememberCoroutineScope()
    var suite by remember { mutableStateOf("all") }
    var quick by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<com.jarvys.agent.MemorySelftestResult?>(null) }
    var pending by remember { mutableStateOf(false) }
    var observedOperation by remember { mutableStateOf(controller.activeOperation()) }
    var ownToken by remember { mutableStateOf<CancellationToken?>(null) }
    var ownJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var failed by remember { mutableStateOf(false) }
    fun invalidate() { report = null; failed = false }
    fun close() { ownToken?.cancel(); ownJob?.cancel(); onClose() }
    DisposableEffect(Unit) { onDispose { ownToken?.cancel(); ownJob?.cancel() } }
    fun start() {
        if (pending || controller.isRunning()) return
        val options = com.jarvys.agent.MemorySelftestOptions(suite, quick)
        val token = CancellationToken.cancellable()
        invalidate(); ownToken = token; pending = true
        ownJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { controller.execute(options, token) }
                coroutineContext.ensureActive()
                // A cancelled run's received envelope is meaningful; cancellation is never a successful report.
                report = result
            } catch (cancelled: CancellationException) {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) failed = true
            } catch (_: Exception) { coroutineContext.ensureActive(); failed = true }
            finally {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
                    pending = false; ownToken = null; ownJob = null; observedOperation = controller.activeOperation()
                }
            }
        }
    }
    val busy = pending || observedOperation != null
    AlertDialog(onDismissRequest = { close() }, title = { Text(stringResource(R.string.memory_selftest_title)) }, text = {
        // Observation only. The standalone controller owns physical drain/cleanup and admission.
        LaunchedEffect(controller) { while (true) { observedOperation = controller.activeOperation(); kotlinx.coroutines.delay(100) } }
        LazyColumn(Modifier.heightIn(max = 440.dp).testTag("memory-selftest-content"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.memory_selftest_explanation)) }
            items(listOf("all", "reliability", "performance")) { choice ->
                TextButton(enabled = !busy, onClick = { suite = choice; invalidate() }, modifier = Modifier.testTag("memory-selftest-suite-$choice")) {
                    Text((if (suite == choice) "✓ " else "") + stringResource(when (choice) { "reliability" -> R.string.memory_selftest_reliability; "performance" -> R.string.memory_selftest_performance; else -> R.string.memory_selftest_all }))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.memory_selftest_quick))
                    Switch(checked = quick, enabled = !busy, onCheckedChange = { quick = it; invalidate() }, modifier = Modifier.testTag("memory-selftest-quick"))
                }
                Text(stringResource(R.string.memory_selftest_full_cost))
                TextButton(enabled = !busy, onClick = { start() }, modifier = Modifier.testTag("memory-selftest-start")) { Text(stringResource(R.string.memory_selftest_start)) }
                if (busy) Text(stringResource(R.string.memory_selftest_busy), modifier = Modifier.testTag("memory-selftest-busy"))
                if (failed) Text(stringResource(R.string.memory_selftest_failed), modifier = Modifier.testTag("memory-selftest-failed"))
            }
            report?.let { result ->
                item {
                    val passed = result.executionState == "completed" && result.cleanupState == "cleaned" && result.report.passed
                    Text(stringResource(if (passed) R.string.memory_selftest_passed else R.string.memory_selftest_not_passed), modifier = Modifier.testTag("memory-selftest-result"))
                    Text(stringResource(R.string.memory_selftest_states, result.executionState, result.cleanupState), modifier = Modifier.testTag("memory-selftest-states"))
                    Text(stringResource(R.string.memory_selftest_report, result.report.schemaVersion, result.report.suite, result.report.quick.toString(), result.report.durationMs), modifier = Modifier.testTag("memory-selftest-report"))
                    Text(stringResource(R.string.memory_selftest_adapter, result.adapter.platform, result.adapter.corpusProject, result.adapter.sessionMode, result.adapter.userStoreChecked.toString()), modifier = Modifier.testTag("memory-selftest-adapter"))
                    result.error?.let { Text(it, modifier = Modifier.testTag("memory-selftest-error")) }
                }
                items(result.report.scenarios, key = { it.name }) { scenario ->
                    Column(Modifier.testTag("memory-selftest-scenario-${scenario.name}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.memory_selftest_scenario, scenario.name, scenario.suite, scenario.passed.toString(), scenario.durationMs))
                        scenario.error?.let { Text(it) }
                        scenario.metrics.forEach { (metric, value) -> Text("$metric: $value", modifier = Modifier.testTag("memory-selftest-metric-${scenario.name}-$metric")) }
                    }
                }
            }
        }
    }, confirmButton = {
        if (busy) TextButton(onClick = { if (ownToken != null) ownToken?.cancel() else observedOperation?.let { controller.cancelIfCurrent(it) } }, modifier = Modifier.testTag("memory-selftest-cancel")) { Text(stringResource(R.string.memory_selftest_cancel)) }
    }, dismissButton = { TextButton(onClick = { close() }, modifier = Modifier.testTag("memory-selftest-close")) { Text(stringResource(R.string.memory_inspector_close)) } })
}

/** Explicit manual read, independent of automatic memory capture; no model or background refresh. */
@Composable
private fun MemoryAliasesDialog(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<MemoryProjectAlias>?>(null) }
    var choices by remember { mutableStateOf<List<String>>(emptyList()) }
    var mode by remember { mutableStateOf("list") }
    var kind by remember { mutableStateOf("key") }
    var input by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<MemoryProjectAlias?>(null) }
    var savePreview by remember { mutableStateOf<MemoryProjectAlias?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    var rawKey by remember { mutableStateOf("") }
    var rawCwd by remember { mutableStateOf("") }
    var resolution by remember { mutableStateOf<MemoryProjectAliasResolution?>(null) }
    var resolvedInputs by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var mutating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var requestId by remember { mutableStateOf(0L) }
    var activeToken by remember { mutableStateOf<CancellationToken?>(null) }
    var activeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun invalidate() { savePreview = null; confirmed = false; message = null }
    fun close() { requestId++; activeToken?.cancel(); activeJob?.cancel(); onClose() }
    DisposableEffect(Unit) { onDispose { activeToken?.cancel(); activeJob?.cancel() } }
    fun <T> request(mutation: Boolean, produce: (CancellationToken) -> T, accept: (T) -> Unit) {
        if (mutating) return
        activeToken?.cancel(); activeJob?.cancel()
        val captured = ++requestId
        val token = CancellationToken.cancellable()
        activeToken = token; busy = true; mutating = mutation; message = null
        activeJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { produce(token) }
                coroutineContext.ensureActive(); token.throwIfCancelled()
                if (captured == requestId) accept(result)
            } catch (cancelled: CancellationException) {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && captured == requestId) {
                    rows = null; resolution = null; savePreview = null; confirmed = false
                    if (mutation) { selected = null; choices = emptyList(); mode = "list" }
                    message = if (mutation) R.string.memory_aliases_uncertain else R.string.memory_aliases_failed
                }
                throw cancelled
            } catch (failure: Exception) {
                coroutineContext.ensureActive()
                if (captured == requestId) {
                    rows = null; resolution = null; savePreview = null; confirmed = false
                    if (mutation) { selected = null; choices = emptyList(); mode = "list" }
                    message = if (mutation) R.string.memory_aliases_uncertain else R.string.memory_aliases_failed
                }
            } finally {
                if (captured == requestId) { busy = false; mutating = false; activeToken = null; activeJob = null }
            }
        }
    }
    fun load() {
        if (mutating) return
        rows = null; choices = emptyList(); selected = null; resolution = null; resolvedInputs = null; target = null; invalidate(); mode = "list"
        request(false, { token ->
            val bridge = ChatDatabaseBridge.getInstance(context)
            bridge.personalAgentMemoryAliasProjects(token) to bridge.personalAgentMemoryAliasList(token)
        }) { (allowed, aliases) -> choices = allowed; rows = aliases }
    }
    fun edit(row: MemoryProjectAlias?) {
        if (busy) return
        kind = row?.kind ?: "key"; input = row?.key ?: ""; target = row?.project
        selected = null; invalidate(); mode = "edit"
    }
    LaunchedEffect(Unit) { load() }
    AlertDialog(onDismissRequest = { close() }, title = { Text(stringResource(R.string.memory_aliases_title)) }, text = {
        Column {
            TextButton(enabled = !mutating, onClick = { load() }, modifier = Modifier.testTag("memory-aliases-refresh")) { Text(stringResource(R.string.memory_aliases_refresh)) }
            if (busy) Text(stringResource(R.string.memory_aliases_loading), modifier = Modifier.testTag("memory-aliases-loading"))
            message?.let { Text(stringResource(it), modifier = Modifier.testTag("memory-aliases-message")) }
            key(mode) {
                LazyColumn(Modifier.heightIn(max = 410.dp).testTag("memory-aliases-content"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text(stringResource(R.string.memory_aliases_explanation)) }
                    if (mode != "list") item {
                        TextButton(enabled = !busy, onClick = { mode = "list"; selected = null; resolution = null; invalidate() }, modifier = Modifier.testTag("memory-aliases-back")) { Text(stringResource(R.string.memory_aliases_back)) }
                    }
                    when (mode) {
                        "list" -> {
                            item {
                                TextButton(enabled = !busy && choices.isNotEmpty(), onClick = { edit(null) }, modifier = Modifier.testTag("memory-aliases-new")) { Text(stringResource(R.string.memory_aliases_new)) }
                                TextButton(enabled = !busy, onClick = { resolution = null; resolvedInputs = null; mode = "resolve"; invalidate() }, modifier = Modifier.testTag("memory-aliases-resolve-open")) { Text(stringResource(R.string.memory_aliases_resolve)) }
                                if (rows?.isEmpty() == true) Text(stringResource(R.string.memory_aliases_empty), modifier = Modifier.testTag("memory-aliases-empty"))
                            }
                            rows?.let { aliases -> itemsIndexed(aliases) { index, row ->
                                Column(Modifier.testTag("memory-aliases-row-$index")) {
                                    Text(row.kind, modifier = Modifier.testTag("memory-aliases-kind-$index"))
                                    Text(row.key, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.testTag("memory-aliases-key-$index"))
                                    Text(row.project, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.testTag("memory-aliases-target-$index"))
                                    TextButton(enabled = !busy, onClick = { selected = row; mode = "inspect"; invalidate() }, modifier = Modifier.testTag("memory-aliases-inspect-$index")) { Text(stringResource(R.string.memory_aliases_inspect)) }
                                    TextButton(enabled = !busy, onClick = { edit(row) }, modifier = Modifier.testTag("memory-aliases-edit-$index")) { Text(stringResource(R.string.memory_aliases_edit)) }
                                    TextButton(enabled = !busy, onClick = { selected = row; mode = "remove"; invalidate() }, modifier = Modifier.testTag("memory-aliases-remove-$index")) { Text(stringResource(R.string.memory_aliases_remove)) }
                                }
                            } }
                        }
                        "edit" -> {
                            item {
                                Text(stringResource(R.string.memory_aliases_kind))
                                Row { listOf("key", "cwd").forEach { option -> TextButton(enabled = !busy, onClick = { kind = option; invalidate() }, modifier = Modifier.testTag("memory-aliases-kind-choice-$option")) { Text((if (kind == option) "✓ " else "") + option) } } }
                                OutlinedTextField(value = input, onValueChange = { input = it; invalidate() }, enabled = !busy, label = { Text(stringResource(R.string.memory_aliases_key)) }, modifier = Modifier.testTag("memory-aliases-input"))
                                Text(stringResource(R.string.memory_aliases_target))
                            }
                            itemsIndexed(choices) { index, choice -> TextButton(enabled = !busy, onClick = { target = choice; invalidate() }, modifier = Modifier.testTag("memory-aliases-target-choice-$index")) { Text((if (target == choice) "✓ " else "") + choice) } }
                            item {
                                TextButton(enabled = !busy && target != null, onClick = {
                                    invalidate()
                                    try { savePreview = MemoryProjectAlias(kind, MemoryProjectAliases.normalize(kind, input), requireNotNull(target)) }
                                    catch (_: IllegalArgumentException) { message = R.string.memory_aliases_invalid }
                                }, modifier = Modifier.testTag("memory-aliases-prepare")) { Text(stringResource(R.string.memory_aliases_prepare)) }
                            }
                            savePreview?.let { preview ->
                                item { Text(stringResource(R.string.memory_aliases_save_warning)); Text(preview.kind); Text(preview.key, modifier = Modifier.testTag("memory-aliases-preview-key")); Text(preview.project, modifier = Modifier.testTag("memory-aliases-preview-target")) }
                                item {
                                    Row { Checkbox(checked = confirmed, enabled = !busy, onCheckedChange = { confirmed = it }, modifier = Modifier.testTag("memory-aliases-consent")); Text(stringResource(R.string.memory_aliases_confirm)) }
                                    TextButton(enabled = !busy && confirmed, onClick = {
                                        val captured = preview
                                        if (!confirmed || busy) return@TextButton
                                        request(true, { token -> ChatDatabaseBridge.getInstance(context).personalAgentMemoryAliasSet(captured.kind, captured.key, captured.project, token) }) {
                                            rows = null; selected = null; savePreview = null; confirmed = false; mode = "list"; message = R.string.memory_aliases_saved
                                        }
                                    }, modifier = Modifier.testTag("memory-aliases-save")) { Text(stringResource(R.string.memory_aliases_save)) }
                                }
                            }
                        }
                        "inspect", "remove" -> selected?.let { row ->
                            item { Text(row.kind); Text(stringResource(R.string.memory_aliases_full_key)); Text(row.key, modifier = Modifier.testTag("memory-aliases-full-key")); Text(stringResource(R.string.memory_aliases_full_target)); Text(row.project, modifier = Modifier.testTag("memory-aliases-full-target")) }
                            if (mode == "remove") item {
                                Text(stringResource(R.string.memory_aliases_remove_warning))
                                Row { Checkbox(checked = confirmed, enabled = !busy, onCheckedChange = { confirmed = it }, modifier = Modifier.testTag("memory-aliases-consent")); Text(stringResource(R.string.memory_aliases_confirm)) }
                                TextButton(enabled = !busy && confirmed, onClick = {
                                    val captured = row
                                    if (!confirmed || busy) return@TextButton
                                    request(true, { token -> ChatDatabaseBridge.getInstance(context).personalAgentMemoryAliasRemove(captured.kind, captured.key, token) }) { removed ->
                                        rows = null; selected = null; confirmed = false; mode = "list"; message = if (removed) R.string.memory_aliases_removed else R.string.memory_aliases_absent
                                    }
                                }, modifier = Modifier.testTag("memory-aliases-delete-confirm")) { Text(stringResource(R.string.memory_aliases_remove)) }
                            }
                        }
                        "resolve" -> {
                            item {
                                OutlinedTextField(value = rawKey, onValueChange = { rawKey = it; resolution = null; resolvedInputs = null; message = null }, enabled = !busy, label = { Text(stringResource(R.string.memory_aliases_raw_key)) }, modifier = Modifier.testTag("memory-aliases-raw-key"))
                                OutlinedTextField(value = rawCwd, onValueChange = { rawCwd = it; resolution = null; resolvedInputs = null; message = null }, enabled = !busy, label = { Text(stringResource(R.string.memory_aliases_raw_cwd)) }, modifier = Modifier.testTag("memory-aliases-raw-cwd"))
                                TextButton(enabled = !busy, onClick = {
                                    val keyInput = rawKey; val cwdInput = rawCwd; resolution = null; resolvedInputs = null
                                    request(false, { token -> ChatDatabaseBridge.getInstance(context).personalAgentMemoryAliasResolve(keyInput, cwdInput, token) }) { result -> resolvedInputs = keyInput to cwdInput; resolution = result }
                                }, modifier = Modifier.testTag("memory-aliases-resolve-run")) { Text(stringResource(R.string.memory_aliases_resolve)) }
                            }
                            resolution?.let { result ->
                                item {
                                    Text(stringResource(if (result.conflict) R.string.memory_aliases_conflict else if (result.matched) R.string.memory_aliases_matched else R.string.memory_aliases_unmatched), modifier = Modifier.testTag("memory-aliases-resolution-status"))
                                    Text(stringResource(R.string.memory_aliases_raw_key)); Text(requireNotNull(resolvedInputs).first, modifier = Modifier.testTag("memory-aliases-resolved-raw-key"))
                                    Text(stringResource(R.string.memory_aliases_raw_cwd)); Text(requireNotNull(resolvedInputs).second, modifier = Modifier.testTag("memory-aliases-resolved-raw-cwd"))
                                    Text(stringResource(R.string.memory_aliases_normalized_key)); Text(result.normalizedKey ?: stringResource(R.string.memory_aliases_none), modifier = Modifier.testTag("memory-aliases-normalized-key"))
                                    Text(stringResource(R.string.memory_aliases_normalized_cwd)); Text(result.normalizedCwd ?: stringResource(R.string.memory_aliases_none), modifier = Modifier.testTag("memory-aliases-normalized-cwd"))
                                    Text(stringResource(R.string.memory_aliases_key_match)); Text(result.keyAlias?.project ?: stringResource(R.string.memory_aliases_none), modifier = Modifier.testTag("memory-aliases-key-match"))
                                    Text(stringResource(R.string.memory_aliases_cwd_match)); Text(result.cwdAlias?.project ?: stringResource(R.string.memory_aliases_none), modifier = Modifier.testTag("memory-aliases-cwd-match"))
                                    Text(stringResource(R.string.memory_aliases_result)); Text(result.project, modifier = Modifier.testTag("memory-aliases-result"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { close() }, modifier = Modifier.testTag("memory-aliases-close")) { Text(stringResource(R.string.memory_inspector_close)) } })
}

@Composable
private fun MemoryProjectInventoryDialog(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<MemoryProjectInventory?>(null) }
    var inspected by remember { mutableStateOf<MemoryProjectInventoryItem?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var requestId by remember { mutableStateOf(0L) }
    var activeToken by remember { mutableStateOf<CancellationToken?>(null) }
    var activeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun close() { requestId++; activeToken?.cancel(); activeJob?.cancel(); report = null; inspected = null; onClose() }
    DisposableEffect(Unit) { onDispose { activeToken?.cancel(); activeJob?.cancel() } }
    fun load() {
        activeToken?.cancel(); activeJob?.cancel()
        val captured = ++requestId
        val token = CancellationToken.cancellable()
        report = null; inspected = null; failed = false; loading = true; activeToken = token
        activeJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentMemoryProjectInventory(token) }
                coroutineContext.ensureActive(); token.throwIfCancelled()
                if (captured == requestId) report = result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                coroutineContext.ensureActive(); token.throwIfCancelled()
                if (captured == requestId) { report = null; inspected = null; failed = true }
            } finally {
                if (captured == requestId) { loading = false; activeToken = null; activeJob = null }
            }
        }
    }
    LaunchedEffect(Unit) { load() }
    AlertDialog(onDismissRequest = { close() }, title = { Text(stringResource(R.string.memory_project_inventory_title)) }, text = {
        Column {
            TextButton(onClick = { load() }, modifier = Modifier.testTag("memory-project-inventory-refresh")) { Text(stringResource(R.string.memory_project_inventory_refresh)) }
            if (loading) Text(stringResource(R.string.memory_project_inventory_loading), modifier = Modifier.testTag("memory-project-inventory-loading"))
            if (failed) Text(stringResource(R.string.memory_project_inventory_failed), modifier = Modifier.testTag("memory-project-inventory-failed"))
            val selected = inspected
            if (selected != null) {
                TextButton(onClick = { inspected = null }, modifier = Modifier.testTag("memory-project-inventory-back")) { Text(stringResource(R.string.memory_project_inventory_back)) }
                key(selected.name) {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("memory-project-inventory-inspection"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.memory_project_inventory_name))
                        Text(selected.name, modifier = Modifier.testTag("memory-project-inventory-full-name"))
                        Text(stringResource(R.string.memory_project_inventory_observations, selected.observationCount))
                        Text(stringResource(R.string.memory_project_inventory_sessions, selected.sessionCount))
                        Text(stringResource(R.string.memory_project_inventory_prompts, selected.promptCount))
                        Text(stringResource(R.string.memory_project_inventory_directories))
                        if (selected.directories == null) Text(stringResource(R.string.memory_project_inventory_none), modifier = Modifier.testTag("memory-project-inventory-no-directories"))
                        else selected.directories.forEachIndexed { index, directory -> Text(directory, modifier = Modifier.testTag("memory-project-inventory-full-directory-$index")) }
                    }
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp).testTag("memory-project-inventory-content"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text(stringResource(R.string.memory_project_inventory_explanation)) }
                    report?.let { received ->
                        item {
                            Text(stringResource(R.string.memory_project_inventory_count, received.count), modifier = Modifier.testTag("memory-project-inventory-count"))
                            if (received.projects.isEmpty()) Text(stringResource(R.string.memory_project_inventory_empty), modifier = Modifier.testTag("memory-project-inventory-empty"))
                        }
                        itemsIndexed(received.projects, key = { _, project -> project.name }) { index, project ->
                            Column(Modifier.testTag("memory-project-inventory-item-$index"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(project.name, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.testTag("memory-project-inventory-name-$index"))
                                Text(stringResource(R.string.memory_project_inventory_observations, project.observationCount), modifier = Modifier.testTag("memory-project-inventory-observations-$index"))
                                Text(stringResource(R.string.memory_project_inventory_sessions, project.sessionCount), modifier = Modifier.testTag("memory-project-inventory-sessions-$index"))
                                Text(stringResource(R.string.memory_project_inventory_prompts, project.promptCount), modifier = Modifier.testTag("memory-project-inventory-prompts-$index"))
                                if (project.directories == null) Text(stringResource(R.string.memory_project_inventory_none), modifier = Modifier.testTag("memory-project-inventory-no-directories-$index"))
                                TextButton(onClick = { inspected = project }, modifier = Modifier.testTag("memory-project-inventory-inspect-$index")) { Text(stringResource(R.string.memory_project_inventory_inspect)) }
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { close() }, modifier = Modifier.testTag("memory-project-inventory-close")) { Text(stringResource(R.string.memory_inspector_close)) } })
}

@Composable
private fun MemoryStatsDialog(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf<String?>(null) }
    var choices by remember { mutableStateOf<List<String>>(emptyList()) }
    var stats by remember { mutableStateOf<MemoryStats?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var requestId by remember { mutableStateOf(0L) }
    var activeToken by remember { mutableStateOf<CancellationToken?>(null) }
    var activeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun close() { requestId++; activeToken?.cancel(); activeJob?.cancel(); onClose() }
    DisposableEffect(Unit) { onDispose { activeToken?.cancel(); activeJob?.cancel() } }
    fun load(selection: String?) {
        if (loading && project == selection) return
        activeToken?.cancel(); activeJob?.cancel()
        val captured = ++requestId
        val token = CancellationToken.cancellable()
        project = selection; stats = null; failed = false; loading = true; activeToken = token
        activeJob = scope.launch {
            try {
                val allowed = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentMemoryStatsProjects(token) }
                coroutineContext.ensureActive()
                if (captured != requestId) return@launch
                choices = allowed
                val result = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentMemoryStats(selection, token) }
                coroutineContext.ensureActive()
                if (captured == requestId) stats = result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                coroutineContext.ensureActive()
                if (captured == requestId) { stats = null; failed = true }
            } finally {
                if (captured == requestId) { loading = false; activeToken = null; activeJob = null }
            }
        }
    }
    AlertDialog(onDismissRequest = { close() }, title = { Text(stringResource(R.string.memory_stats_title)) }, text = {
        LaunchedEffect(Unit) { load(null) }
        LazyColumn(Modifier.heightIn(max = 440.dp).testTag("memory-stats-content"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.memory_stats_explanation))
                TextButton(onClick = { load(null) }, modifier = Modifier.testTag("memory-stats-project-all")) {
                    Text((if (project == null) "✓ " else "") + stringResource(R.string.memory_stats_all))
                }
            }
            items(choices, key = { it }) { choice ->
                TextButton(onClick = { load(choice) }, modifier = Modifier.testTag("memory-stats-project-$choice")) { Text((if (project == choice) "✓ " else "") + choice) }
            }
            item {
                Text(if (project == null) stringResource(R.string.memory_stats_all) else stringResource(R.string.memory_stats_project, requireNotNull(project)), modifier = Modifier.testTag("memory-stats-selection"))
                TextButton(enabled = !loading, onClick = { load(project) }, modifier = Modifier.testTag("memory-stats-reload")) { Text(stringResource(R.string.memory_stats_reload)) }
                if (loading) Text(stringResource(R.string.memory_stats_loading), modifier = Modifier.testTag("memory-stats-loading"))
                if (failed) Text(stringResource(R.string.memory_stats_failed), modifier = Modifier.testTag("memory-stats-failed"))
            }
            stats?.let { received ->
                item {
                    Text(stringResource(R.string.memory_stats_sessions, received.totalSessions), modifier = Modifier.testTag("memory-stats-sessions"))
                    Text(stringResource(R.string.memory_stats_observations, received.totalObservations), modifier = Modifier.testTag("memory-stats-observations"))
                    Text(stringResource(R.string.memory_stats_prompts, received.totalPrompts), modifier = Modifier.testTag("memory-stats-prompts"))
                    Text(stringResource(R.string.memory_stats_projects), modifier = Modifier.testTag("memory-stats-projects-heading"))
                    if (received.projects.isEmpty()) Text(stringResource(R.string.memory_stats_no_projects), modifier = Modifier.testTag("memory-stats-projects-empty"))
                }
                items(received.projects, key = { "reported-$it" }) { name -> Text(name, modifier = Modifier.testTag("memory-stats-reported-$name")) }
            }
        }
    }, confirmButton = { TextButton(onClick = { close() }, modifier = Modifier.testTag("memory-stats-close")) { Text(stringResource(R.string.memory_inspector_close)) } })
}

/** A returned preview is only information; Apply starts a fresh operation on the same incoming page. */
@Composable
private fun MemoryRelationScanDialog(onClose: () -> Unit, onChanged: () -> Unit, onPending: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var semanticOptions by remember { mutableStateOf<MemoryRelationScanOptions?>(null) }
    var projects by remember { mutableStateOf<List<String>?>(null) }
    var projectsFailed by remember { mutableStateOf(false) }
    var project by remember { mutableStateOf<String?>(WorkspaceIdentity.CENTRAL_SESSION) }
    var since by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf("100") }
    var cap by remember { mutableStateOf("100") }
    var incoming by remember { mutableStateOf<MemoryRelationScanOptions?>(null) }
    var report by remember { mutableStateOf<MemoryRelationScanResult?>(null) }
    var pending by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<String?>(null) }
    var activeToken by remember { mutableStateOf<CancellationToken?>(null) }
    var activeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun invalidate() { incoming = null; report = null; state = null }
    fun close() { activeToken?.cancel(); activeJob?.cancel(); onClose() }
    DisposableEffect(Unit) { onDispose { activeToken?.cancel(); activeJob?.cancel() } }
    fun launchScan(options: MemoryRelationScanOptions) {
        if (pending || projects == null || projectsFailed) return
        val token = CancellationToken.cancellable()
        // This is deliberately NOT the report's nextCursor.
        incoming = options.copy(apply = false)
        report = null; state = null; pending = true; cancelling = false; activeToken = token
        activeJob = scope.launch {
            try {
                val received = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentScanRelations(options, token) }
                coroutineContext.ensureActive()
                token.throwIfCancelled()
                report = received
                if (received.confirmedRelations.isNotEmpty()) onChanged()
            } catch (cancelled: CancellationException) {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) state = "cancelled"
            } catch (failure: Exception) {
                coroutineContext.ensureActive(); state = "unknown"
            } finally {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) { pending = false; cancelling = false; activeToken = null; activeJob = null }
            }
        }
    }
    fun dryRun() {
        if (pending) return
        val count = limit.toIntOrNull()
        val maximum = cap.toIntOrNull()
        val date = try { if (since.isBlank()) null else java.time.Instant.parse(since.trim()).toEpochMilli() } catch (_: Exception) { state = "invalid"; return }
        if (count == null || count !in 0..100 || maximum == null) { state = "invalid"; return }
        launchScan(MemoryRelationScanOptions(project, date, count, maximum, false, incoming?.cursor))
    }
    semanticOptions?.let { options ->
        MemorySemanticScanDialog(options, onClose = { semanticOptions = null }, onChanged = onChanged)
        return
    }
    AlertDialog(onDismissRequest = { close() }, title = { Text(stringResource(R.string.memory_scan_title)) }, text = {
        LaunchedEffect(Unit) {
            try {
                val loaded = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentMemoryProjects().map { it.project } }
                coroutineContext.ensureActive(); projects = (listOf(WorkspaceIdentity.CENTRAL_SESSION) + loaded).distinct()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { coroutineContext.ensureActive(); projectsFailed = true }
        }
        when {
            projectsFailed -> Text(stringResource(R.string.memory_scan_project_error), modifier = Modifier.testTag("memory-scan-project-error"))
            projects == null -> Text(stringResource(R.string.memory_scan_loading_projects))
            else -> LazyColumn(Modifier.heightIn(max = 440.dp).testTag("memory-scan-content"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(stringResource(R.string.memory_scan_explanation))
                    TextButton(enabled = !pending, onClick = { if (!pending) { project = null; invalidate() } }, modifier = Modifier.testTag("memory-scan-project-all")) { Text((if (project == null) "✓ " else "") + stringResource(R.string.memory_scan_all)) }
                }
                items(projects.orEmpty(), key = { it }) { choice ->
                    TextButton(enabled = !pending, onClick = { if (!pending) { project = choice; invalidate() } }, modifier = Modifier.testTag("memory-scan-project-$choice")) { Text((if (project == choice) "✓ " else "") + choice) }
                }
                item {
                    OutlinedTextField(value = since, onValueChange = { since = it; invalidate() }, enabled = !pending, label = { Text(stringResource(R.string.memory_scan_since)) }, modifier = Modifier.fillMaxWidth().testTag("memory-scan-since"))
                    OutlinedTextField(value = limit, onValueChange = { limit = it; report = null; state = null }, enabled = !pending, label = { Text(stringResource(R.string.memory_scan_limit)) }, modifier = Modifier.fillMaxWidth().testTag("memory-scan-limit"))
                    OutlinedTextField(value = cap, onValueChange = { cap = it; report = null; state = null }, enabled = !pending, label = { Text(stringResource(R.string.memory_scan_cap)) }, modifier = Modifier.fillMaxWidth().testTag("memory-scan-cap"))
                    TextButton(enabled = !pending, onClick = { dryRun() }, modifier = Modifier.testTag("memory-scan-dry")) { Text(stringResource(R.string.memory_scan_dry)) }
                    TextButton(enabled = !pending, onClick = {
                        val count = limit.toIntOrNull()
                        val date = try { if (since.isBlank()) null else java.time.Instant.parse(since.trim()).toEpochMilli() } catch (_: Exception) { state = "invalid"; return@TextButton }
                        if (count == null || count !in 0..100) state = "invalid"
                        else semanticOptions = MemoryRelationScanOptions(project, date, count, cursor = incoming?.cursor)
                    }, modifier = Modifier.testTag("memory-scan-semantic")) { Text(stringResource(R.string.memory_semantic_open)) }

                    if (incoming != null) TextButton(enabled = !pending, onClick = { if (!pending) invalidate() }, modifier = Modifier.testTag("memory-scan-restart")) { Text(stringResource(R.string.memory_scan_restart)) }
                    if (pending) Text(stringResource(if (cancelling) R.string.memory_scan_cancelling else R.string.memory_scan_running), modifier = Modifier.testTag("memory-scan-running"))
                    state?.let { status -> Text(stringResource(when (status) { "invalid" -> R.string.memory_scan_invalid; "cancelled" -> R.string.memory_scan_cancelled; else -> R.string.memory_scan_unknown }), modifier = Modifier.testTag("memory-scan-$status")) }
                }
                report?.let { received ->
                    item {
                        Text(stringResource(if (received.dryRun) R.string.memory_scan_dry_report else R.string.memory_scan_apply_report), modifier = Modifier.testTag("memory-scan-report"))
                        Text(stringResource(R.string.memory_scan_counts, received.inspected, received.rankedQueries, received.candidatesFound, received.alreadyRelated, received.relationsInserted), modifier = Modifier.testTag("memory-scan-counts"))
                        Text(stringResource(R.string.memory_scan_path, received.rankingPath), modifier = Modifier.testTag("memory-scan-path"))
                        if (received.capped) Text(stringResource(R.string.memory_scan_capped), modifier = Modifier.testTag("memory-scan-capped"))
                        if (received.incomplete || received.stopped) Text(stringResource(R.string.memory_scan_partial), modifier = Modifier.testTag("memory-scan-partial"))
                        Text(stringResource(R.string.memory_scan_continuation))
                        // A conscious rerun, never a promise that this report authorizes the earlier candidate set.
                        TextButton(enabled = !pending, onClick = { incoming?.let { launchScan(it.copy(apply = true)) } }, modifier = Modifier.testTag("memory-scan-apply")) { Text(stringResource(R.string.memory_scan_apply)) }
                        val next = received.nextCursor
                        if (!received.capped && !received.stopped && next != null) TextButton(enabled = !pending, onClick = { incoming?.let { launchScan(it.copy(apply = false, cursor = next)) } }, modifier = Modifier.testTag("memory-scan-next")) { Text(stringResource(R.string.memory_scan_next)) }
                    }
                    items(received.sources, key = { it.id }) { source ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.memory_scan_source, source.id, source.rankingPath, source.candidates.size), modifier = Modifier.testTag("memory-scan-source-${source.id}"))
                            source.candidates.forEach { candidate -> Text(stringResource(R.string.memory_scan_candidate, candidate.id, candidate.score.toString()), modifier = Modifier.testTag("memory-scan-candidate-${source.id}-${candidate.id}")) }
                        }
                    }
                    items(received.confirmedRelations, key = { it.judgmentId }) { pair -> Text(stringResource(R.string.memory_scan_confirmed, pair.judgmentId), modifier = Modifier.testTag("memory-scan-confirmed-${pair.judgmentId}")) }
                    items(received.diagnostics) { diagnostic -> Text(listOfNotNull(diagnostic.code, diagnostic.sourceId, diagnostic.targetId).joinToString(" · ")) }
                }
                item { TextButton(enabled = !pending, onClick = { if (!pending) onPending() }, modifier = Modifier.testTag("memory-scan-pending")) { Text(stringResource(R.string.memory_scan_pending)) } }
            }
        }
    }, confirmButton = {
        if (pending) TextButton(enabled = !cancelling, onClick = { cancelling = true; activeToken?.cancel() }, modifier = Modifier.testTag("memory-scan-cancel")) { Text(stringResource(R.string.memory_scan_cancel)) }
    }, dismissButton = {
        TextButton(onClick = { close() }, modifier = Modifier.testTag("memory-scan-close")) { Text(stringResource(R.string.memory_inspector_close)) }
    })
}

/** Explicit human compound write; only a complete displayed plan can enable confirmation. */
@Composable
private fun MemoryConsolidationDialog(ids: List<String>, project: String, onClose: () -> Unit, onCommitted: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var request by remember { mutableStateOf<MemoryConsolidationRequest?>(null) }
    var plan by remember { mutableStateOf<MemoryConsolidationPlan?>(null) }
    var result by remember { mutableStateOf<MemoryConsolidationResult?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<String?>(null) }
    var activeToken by remember { mutableStateOf<CancellationToken?>(null) }
    DisposableEffect(Unit) { onDispose { activeToken?.cancel() } }
    fun invalidate() { request = null; plan = null; result = null; confirmed = false; state = null }
    fun preview() {
        if (pending || result?.status == "consolidated") return
        invalidate()
        val prepared = try { MemoryConsolidationArguments.prepare(mapOf(
            "ids_to_delete" to ids, "new_title" to title, "new_content" to content,
            "expected_project" to project, "type" to type, "topic_key" to topic,
        )) } catch (_: Exception) { state = "invalid"; return }
        val token = CancellationToken.cancellable(); activeToken = token; pending = true
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentConsolidationPreview(prepared, token) }
                coroutineContext.ensureActive(); token.throwIfCancelled(); request = prepared; plan = loaded
            } catch (cancelled: CancellationException) {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) { invalidate(); state = "cancelled" }
            } catch (_: Exception) { coroutineContext.ensureActive(); invalidate(); state = "failed" }
            finally { if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) { pending = false; activeToken = null } }
        }
    }
    fun confirm() {
        val original = request ?: return; val displayed = plan ?: return
        if (!confirmed || pending || result != null) return
        val token = CancellationToken.cancellable(); activeToken = token; pending = true; confirmed = false; state = null
        scope.launch {
            try {
                val received = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentConsolidationConfirm(original, displayed.fingerprint, token) }
                coroutineContext.ensureActive(); token.throwIfCancelled()
                when (received.status) {
                    "fingerprint_mismatch" -> { plan = received.plan; state = "mismatch" }
                    "consolidated" -> { result = received; plan = null; request = null; onCommitted() }
                    else -> { invalidate(); state = "unknown" }
                }
            } catch (cancelled: CancellationException) {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) { invalidate(); state = "cancelled" }
            } catch (_: Exception) { coroutineContext.ensureActive(); invalidate(); state = "unknown" }
            finally { if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) { pending = false; activeToken = null } }
        }
    }
    AlertDialog(onDismissRequest = { if (!pending) onClose() }, title = { Text(stringResource(R.string.memory_consolidate_title)) }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp).testTag("memory-consolidate-content"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(stringResource(R.string.memory_consolidate_warning))
                Text(stringResource(R.string.memory_consolidate_requested, project, ids.joinToString("\n")))
                OutlinedTextField(value = title, onValueChange = { title = it; invalidate() }, enabled = !pending && result == null,
                    label = { Text(stringResource(R.string.memory_consolidate_new_title)) }, modifier = Modifier.fillMaxWidth().testTag("memory-consolidate-title"))
                OutlinedTextField(value = content, onValueChange = { content = it; invalidate() }, enabled = !pending && result == null,
                    label = { Text(stringResource(R.string.memory_consolidate_new_content)) }, modifier = Modifier.fillMaxWidth().testTag("memory-consolidate-body"))
                OutlinedTextField(value = type, onValueChange = { type = it; invalidate() }, enabled = !pending && result == null,
                    label = { Text(stringResource(R.string.memory_consolidate_type)) }, modifier = Modifier.fillMaxWidth().testTag("memory-consolidate-type"))
                OutlinedTextField(value = topic, onValueChange = { topic = it; invalidate() }, enabled = !pending && result == null,
                    label = { Text(stringResource(R.string.memory_consolidate_topic)) }, modifier = Modifier.fillMaxWidth().testTag("memory-consolidate-topic"))
                TextButton(enabled = !pending && result == null, onClick = { preview() }, modifier = Modifier.testTag("memory-consolidate-preview")) { Text(stringResource(R.string.memory_consolidate_preview)) }
                state?.let { value -> Text(stringResource(when (value) { "invalid" -> R.string.memory_consolidate_invalid; "failed" -> R.string.memory_consolidate_failed; "mismatch" -> R.string.memory_consolidate_mismatch; "cancelled" -> R.string.memory_consolidate_cancelled; else -> R.string.memory_consolidate_unknown }), modifier = Modifier.testTag("memory-consolidate-$value")) }
            }
            plan?.let { displayed ->
                item {
                    Text(stringResource(R.string.memory_consolidate_plan), modifier = Modifier.testTag("memory-consolidate-plan"))
                    Text(displayed.fingerprint, modifier = Modifier.testTag("memory-consolidate-fingerprint"))
                    Text(stringResource(R.string.memory_consolidate_retire, displayed.sourceIds.joinToString("\n")))
                    val survivor = displayed.survivor
                    Text(survivor.title, modifier = Modifier.testTag("memory-consolidate-survivor-title"))
                    Text(survivor.content, modifier = Modifier.testTag("memory-consolidate-survivor-content"))
                    Text(stringResource(R.string.memory_consolidate_survivor_metadata, survivor.project, survivor.scope, survivor.type, survivor.memorySessionId ?: "NULL", survivor.topicKeyBytesBase64 ?: "NULL"))
                    Text(stringResource(R.string.memory_consolidate_defaults))
                }
                items(displayed.sources, key = { it.id }) { source ->
                    Column(Modifier.testTag("memory-consolidate-source-${source.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${source.id} · ${source.rowId}\n${source.title ?: "NULL"}")
                        Text(source.content, modifier = Modifier.testTag("memory-consolidate-source-body-${source.id}"))
                        Text("${source.project} · ${source.scope} · ${source.type}\nSession: ${source.memorySessionId ?: "NULL"}")
                        Text(stringResource(R.string.memory_consolidate_raw_fields))
                        source.fields.forEach { field ->
                            val decoded = if (field.storageType == "text" && field.value != null) runCatching {
                                Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                                    .decode(java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(field.value))).toString()
                            }.getOrNull() else null
                            Text("${field.name} [${field.storageType}]: ${decoded ?: field.value ?: "NULL"}")
                            if (field.storageType == "text" && decoded != null) Text("Base64: ${field.value}")
                        }
                    }
                }
                item {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = confirmed, onCheckedChange = { confirmed = it }, enabled = !pending,
                            modifier = Modifier.testTag("memory-consolidate-consent"))
                        Text(stringResource(R.string.memory_consolidate_consent))
                    }
                    TextButton(enabled = confirmed && !pending, onClick = { confirm() }, modifier = Modifier.testTag("memory-consolidate-confirm")) { Text(stringResource(R.string.memory_consolidate_confirm)) }
                }
            }
            result?.let { receipt -> item {
                Text(stringResource(R.string.memory_consolidate_complete, receipt.survivor?.id ?: "—", receipt.softDeletedIds.joinToString("\n")), modifier = Modifier.testTag("memory-consolidate-complete"))
                receipt.survivor?.let { Text(it.content) }
            } }
        }
    }, confirmButton = {
        if (pending) TextButton(onClick = { activeToken?.cancel() }, modifier = Modifier.testTag("memory-consolidate-cancel")) { Text(stringResource(R.string.memory_scan_cancel)) }
    }, dismissButton = {
        TextButton(enabled = !pending, onClick = { if (!pending) onClose() }, modifier = Modifier.testTag("memory-consolidate-close")) { Text(stringResource(R.string.memory_inspector_close)) }
    })
}

/** Disclosure is local; only the explicit Send action can consume this exact preparation. */
@Composable
private fun MemorySemanticScanDialog(scan: MemoryRelationScanOptions, onClose: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val controller = remember(context) { ChatDatabaseBridge.getInstance(context).semanticScan }
    val scope = rememberCoroutineScope()
    var concurrency by remember { mutableStateOf("5") }
    var timeout by remember { mutableStateOf("60") }
    var maximum by remember { mutableStateOf("100") }
    var incomingCursor by remember { mutableStateOf(scan.cursor) }
    var prepared by remember { mutableStateOf<com.jarvys.agent.PreparedMemorySemanticScan?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<com.jarvys.agent.MemorySemanticScanResult?>(null) }
    var pending by remember { mutableStateOf(false) }
    var observedOperation by remember { mutableStateOf(controller.activeOperation()) }
    var state by remember { mutableStateOf<String?>(null) }
    var ownToken by remember { mutableStateOf<CancellationToken?>(null) }
    var ownJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun invalidate() { prepared = null; confirmed = false; report = null; state = null }
    fun close() { ownToken?.cancel(); ownJob?.cancel(); onClose() }
    DisposableEffect(Unit) { onDispose { ownToken?.cancel(); ownJob?.cancel() } }
    fun prepare(apply: Boolean, cursor: com.jarvys.agent.chatdb.MemoryRelationScanCursor? = incomingCursor) {
        if (pending || controller.isRunning()) return
        val workers = concurrency.toIntOrNull(); val seconds = timeout.toIntOrNull(); val cap = maximum.toIntOrNull()
        invalidate()
        if (workers == null || workers !in 1..20 || seconds == null || seconds !in 1..600 || cap == null) { state = "invalid"; return }
        incomingCursor = cursor
        val options = com.jarvys.agent.MemorySemanticScanOptions(scan.copy(apply = apply, cursor = cursor), workers, seconds, cap)
        val token = CancellationToken.cancellable(); ownToken = token; pending = true
        ownJob = scope.launch {
            try {
                val local = withContext(Dispatchers.IO) { controller.prepare(options, token) }
                coroutineContext.ensureActive(); token.throwIfCancelled(); prepared = local
            } catch (cancelled: CancellationException) {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) state = "cancelled"
            } catch (_: Exception) { coroutineContext.ensureActive(); state = "unknown" }
            finally { if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) { pending = false; ownToken = null; ownJob = null } }
        }
    }
    fun send() {
        val approved = prepared ?: return
        if (!confirmed || pending || controller.isRunning()) return
        val token = CancellationToken.cancellable(); ownToken = token; pending = true
        confirmed = false; prepared = null; report = null; state = null
        ownJob = scope.launch {
            try {
                val received = withContext(Dispatchers.IO) { controller.execute(approved, true, token) }
                coroutineContext.ensureActive(); token.throwIfCancelled(); report = received
                if (received.outcomes.any { it.relation != null }) onChanged()
            } catch (cancelled: CancellationException) {
                if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) state = "cancelled"
            } catch (_: Exception) { coroutineContext.ensureActive(); state = "unknown" }
            finally { if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) { pending = false; ownToken = null; ownJob = null; observedOperation = controller.activeOperation() } }
        }
    }
    val busy = pending || observedOperation != null
    AlertDialog(onDismissRequest = { close() }, title = { Text(stringResource(R.string.memory_semantic_title)) }, text = {
        // Observe physical lifetime only; the Bridge CAS, not this display, controls admission.
        LaunchedEffect(controller) { while (true) { observedOperation = controller.activeOperation(); kotlinx.coroutines.delay(100) } }
        LazyColumn(Modifier.heightIn(max = 440.dp).testTag("memory-semantic-content"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.memory_semantic_warning))
                Text(stringResource(R.string.memory_semantic_selector, scan.project ?: stringResource(R.string.memory_scan_all), scan.sinceMillis?.toString() ?: "—"))
                OutlinedTextField(value = concurrency, onValueChange = { concurrency = it; invalidate() }, enabled = !busy, label = { Text(stringResource(R.string.memory_semantic_workers)) }, modifier = Modifier.testTag("memory-semantic-workers"))
                OutlinedTextField(value = timeout, onValueChange = { timeout = it; invalidate() }, enabled = !busy, label = { Text(stringResource(R.string.memory_semantic_timeout)) }, modifier = Modifier.testTag("memory-semantic-timeout"))
                OutlinedTextField(value = maximum, onValueChange = { maximum = it; invalidate() }, enabled = !busy, label = { Text(stringResource(R.string.memory_semantic_maximum)) }, modifier = Modifier.testTag("memory-semantic-maximum"))
                TextButton(enabled = !busy, onClick = { prepare(false) }, modifier = Modifier.testTag("memory-semantic-prepare-preview")) { Text(stringResource(R.string.memory_semantic_prepare_preview)) }
                TextButton(enabled = !busy, onClick = { prepare(true) }, modifier = Modifier.testTag("memory-semantic-prepare-apply")) { Text(stringResource(R.string.memory_semantic_prepare_apply)) }
                if (busy) Text(stringResource(R.string.memory_semantic_busy), modifier = Modifier.testTag("memory-semantic-busy"))
                state?.let { value -> Text(stringResource(when (value) { "invalid" -> R.string.memory_semantic_invalid; "cancelled" -> R.string.memory_semantic_cancelled; else -> R.string.memory_semantic_unknown }), modifier = Modifier.testTag("memory-semantic-$value")) }
            }
            prepared?.let { local ->
                item {
                    Text(stringResource(if (local.options.scan.apply) R.string.memory_semantic_apply_disclosure else R.string.memory_semantic_preview_disclosure), modifier = Modifier.testTag("memory-semantic-disclosure"))
                    Text(stringResource(R.string.memory_semantic_route, local.route.provider, local.route.model, local.route.destination, local.route.reasoningVariant ?: "—", local.route.compatibility ?: "—", local.route.authenticationMode ?: "—"), modifier = Modifier.testTag("memory-semantic-route"))
                    Text(stringResource(R.string.memory_semantic_parameters, local.pairs.size, local.options.concurrency, local.options.timeoutSeconds, local.options.maxSemantic))
                    Text(stringResource(R.string.memory_semantic_privacy))
                }
                items(local.pairs, key = { it.index }) { pair ->
                    Column(Modifier.testTag("memory-semantic-pair-${pair.index}")) {
                        Text("A: ${pair.sourceId}\n${pair.sourceTitle}\n${pair.sourceContent}", modifier = Modifier.testTag("memory-semantic-source-${pair.index}"))
                        Text("B: ${pair.targetId}\n${pair.targetTitle}\n${pair.targetContent}", modifier = Modifier.testTag("memory-semantic-target-${pair.index}"))
                        if (pair.blockedReason != null) Text(stringResource(R.string.memory_semantic_blocked), modifier = Modifier.testTag("memory-semantic-blocked-${pair.index}"))
                    }
                }
                item {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = confirmed, onCheckedChange = { confirmed = it }, enabled = !busy, modifier = Modifier.testTag("memory-semantic-consent"))
                        Text(stringResource(R.string.memory_semantic_consent))
                    }
                    TextButton(enabled = confirmed && !busy, onClick = { send() }, modifier = Modifier.testTag("memory-semantic-send")) { Text(stringResource(if (local.options.scan.apply) R.string.memory_semantic_send_apply else R.string.memory_semantic_send_preview)) }
                }
            }
            report?.let { received ->
                item {
                    Text(stringResource(if (received.dryRun) R.string.memory_semantic_preview_report else R.string.memory_semantic_apply_report), modifier = Modifier.testTag("memory-semantic-report"))
                    Text(stringResource(R.string.memory_semantic_counts, received.semanticJudged, received.semanticSkipped, received.semanticErrors), modifier = Modifier.testTag("memory-semantic-counts"))
                    Text(stringResource(R.string.memory_scan_path, received.rankingPath))
                    if (received.capped) Text(stringResource(R.string.memory_semantic_capped))
                    if (!received.capped && received.nextCursor != null) TextButton(enabled = !busy, onClick = { prepare(false, received.nextCursor) }, modifier = Modifier.testTag("memory-semantic-next")) { Text(stringResource(R.string.memory_semantic_next)) }
                }
                items(received.outcomes, key = { it.index }) { outcome ->
                    Column(Modifier.testTag("memory-semantic-outcome-${outcome.index}")) {
                        Text("${outcome.index}: ${outcome.sourceId} → ${outcome.targetId}\n${outcome.status}")
                        outcome.verdict?.let { Text("${it.relation} · ${it.confidence}\n${it.reasoning}") }
                        outcome.reportedModel?.let { Text(stringResource(R.string.memory_semantic_reported_model, it)) }
                        outcome.relation?.let { Text(stringResource(R.string.memory_semantic_confirmed, it.judgmentId)) }
                        outcome.error?.let { Text(it) }
                    }
                }
            }
        }
    }, confirmButton = {
        if (busy) TextButton(onClick = { if (ownToken != null) ownToken?.cancel() else observedOperation?.let { controller.cancelIfCurrent(it) } }, modifier = Modifier.testTag("memory-semantic-cancel")) { Text(stringResource(R.string.memory_scan_cancel)) }
    }, dismissButton = {
        TextButton(onClick = { close() }, modifier = Modifier.testTag("memory-semantic-close")) { Text(stringResource(R.string.memory_inspector_close)) }
    })
}

/** Human-authored metadata, bound to the actual displayed endpoint snapshot, never a content editor. */
@Composable
private fun MemoryJudgmentDialog(targetId: String, onChanged: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<MemoryRelationDetails?>(null) }
    var request by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var verdict by remember { mutableStateOf<String?>(null) }
    var reason by remember { mutableStateOf("") }
    var evidence by remember { mutableStateOf("") }
    var confidence by remember { mutableStateOf("") }
    var inspecting by remember { mutableStateOf<String?>(null) }
    val parsedConfidence = confidence.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val confidenceValid = confidence.isBlank() || (parsedConfidence != null && parsedConfidence.isFinite() && parsedConfidence in 0.0..1.0)
    inspecting?.let { endpoint ->
        MemoryInspectorDialog(endpoint, onChanged = {}, retainedRelationId = targetId, onClose = { inspecting = null })
        return
    }
    AlertDialog(onDismissRequest = { if (!pending) onClose() }, title = { Text(stringResource(R.string.memory_judgment_title)) }, text = {
        LaunchedEffect(targetId, request) {
            // Returning from endpoint inspection must not replace the evidence approved by this form.
            if (details != null || failed) return@LaunchedEffect
            val capturedRequest = request
            try {
                val loaded = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentRelationDetails(targetId) }
                coroutineContext.ensureActive()
                if (request == capturedRequest) details = loaded
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { coroutineContext.ensureActive(); if (request == capturedRequest) failed = true }
            finally { if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && request == capturedRequest) loading = false }
        }
        when {
            loading -> Text(stringResource(R.string.memory_inspector_loading), modifier = Modifier.testTag("memory-judgment-loading"))
            failed -> Text(stringResource(R.string.memory_judgment_error), modifier = Modifier.testTag("memory-judgment-error"))
            else -> details?.let { shown ->
                LazyColumn(Modifier.heightIn(max = 440.dp).testTag("memory-judgment-form"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(stringResource(R.string.memory_judgment_metadata_only))
                        Text("${shown.relation.judgmentId} · ${shown.relation.relation} · ${shown.relation.status}", modifier = Modifier.testTag("memory-judgment-current"))
                        shown.relation.reason?.let { Text(it, modifier = Modifier.testTag("memory-judgment-current-reason")) }
                        shown.relation.evidence?.let { Text(it, modifier = Modifier.testTag("memory-judgment-current-evidence")) }
                        shown.relation.confidence?.let { Text(it.toString(), modifier = Modifier.testTag("memory-judgment-current-confidence")) }
                    }
                    listOf("source" to shown.source, "target" to shown.target).forEach { (side, endpoint) ->
                        item {
                            if (endpoint == null) Text(stringResource(R.string.memory_relations_missing), modifier = Modifier.testTag("memory-judgment-$side-missing"))
                            else {
                                Text("${endpoint.id} · ${endpoint.project} · ${endpoint.scope} · ${endpoint.observationType}")
                                if (endpoint.deletedAt != null) Text(stringResource(R.string.memory_judgment_deleted), modifier = Modifier.testTag("memory-judgment-$side-deleted"))
                                Text(endpoint.title ?: stringResource(R.string.memory_inspector_untitled), modifier = Modifier.testTag("memory-judgment-$side-title"))
                                Text(stringResource(R.string.memory_judgment_topic, if (endpoint.topicKeyIsUtf8 == false) "base64: ${endpoint.topicKeyBytesBase64.orEmpty()}" else endpoint.topicKey.orEmpty()), modifier = Modifier.testTag("memory-judgment-$side-topic"))
                                if (endpoint.titleIsUtf8 == false) Text("base64: ${endpoint.titleBytesBase64.orEmpty()}", modifier = Modifier.testTag("memory-judgment-$side-title-bytes"))
                                Text(endpoint.text, modifier = Modifier.testTag("memory-judgment-$side-body"))
                                TextButton(enabled = !pending, onClick = { if (!pending) inspecting = endpoint.id }, modifier = Modifier.testTag("memory-judgment-$side-inspect")) { Text(stringResource(if (side == "source") R.string.memory_relations_inspect_source else R.string.memory_relations_inspect_target)) }
                            }
                        }
                    }
                    item { Text(stringResource(R.string.memory_judgment_choose)) }
                    listOf("related", "compatible", "scoped", "conflicts_with", "supersedes", "not_conflict").forEach { value ->
                        item {
                            val label = stringResource(when (value) {
                                "related" -> R.string.memory_judgment_related
                                "compatible" -> R.string.memory_judgment_compatible
                                "scoped" -> R.string.memory_judgment_scoped
                                "conflicts_with" -> R.string.memory_judgment_conflicts
                                "supersedes" -> R.string.memory_judgment_supersedes
                                else -> R.string.memory_judgment_not_conflict
                            })
                            TextButton(enabled = !pending, onClick = { if (!pending) verdict = value }, modifier = Modifier.testTag("memory-judgment-verdict-$value")) { Text((if (verdict == value) "✓ " else "") + label + " ($value)") }
                        }
                    }
                    item {
                        OutlinedTextField(value = reason, onValueChange = { reason = it }, enabled = !pending, label = { Text(stringResource(R.string.memory_judgment_reason)) }, modifier = Modifier.fillMaxWidth().testTag("memory-judgment-reason"))
                        OutlinedTextField(value = evidence, onValueChange = { evidence = it }, enabled = !pending, label = { Text(stringResource(R.string.memory_judgment_evidence)) }, modifier = Modifier.fillMaxWidth().testTag("memory-judgment-evidence"))
                        OutlinedTextField(value = confidence, onValueChange = { confidence = it }, enabled = !pending, isError = !confidenceValid, label = { Text(stringResource(R.string.memory_judgment_confidence)) }, modifier = Modifier.fillMaxWidth().testTag("memory-judgment-confidence"))
                        Text(stringResource(R.string.memory_judgment_omitted))
                    }
                }
            }
        }
    }, confirmButton = {
        val shown = details
        TextButton(enabled = !loading && !pending && !failed && verdict != null && confidenceValid && shown?.source != null && shown.target != null && shown.relation.status != "orphaned", modifier = Modifier.testTag("memory-judgment-submit"), onClick = submit@ {
            val captured = details ?: return@submit
            val selected = verdict ?: return@submit
            if (pending || loading || failed || !confidenceValid || captured.source == null || captured.target == null || captured.relation.status == "orphaned") return@submit
            val capturedReason = reason.takeUnless { it.isBlank() }
            val capturedEvidence = evidence.takeUnless { it.isBlank() }
            val capturedConfidence = parsedConfidence
            pending = true
            scope.launch {
                try {
                    val receipt = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentJudgeRelation(captured, selected, capturedReason, capturedEvidence, capturedConfidence) }
                    coroutineContext.ensureActive()
                    check(receipt.judgmentId == targetId)
                    onChanged(); onClose()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { coroutineContext.ensureActive(); details = null; failed = true }
                finally { if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) pending = false }
            }
        }) { Text(stringResource(R.string.memory_judgment_submit)) }
    }, dismissButton = {
        Row {
            if (failed && !pending) TextButton(onClick = { details = null; failed = false; loading = true; verdict = null; request++ }, modifier = Modifier.testTag("memory-judgment-reload")) { Text(stringResource(R.string.memory_judgment_reload)) }
            TextButton(enabled = !pending, onClick = { if (!pending) onClose() }, modifier = Modifier.testTag("memory-judgment-close")) { Text(stringResource(R.string.memory_inspector_close)) }
        }
    })
}

/** Pending questions are separate from explicit links and do not contain a verdict. */
@Composable
private fun PendingMemoryRelationsDialog(onClose: () -> Unit, onInspect: (String) -> Unit, onJudge: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    var request by remember { mutableStateOf(0L) }
    var offset by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf<MemoryRelationPage?>(null) }
    var failed by remember { mutableStateOf(false) }
    fun load(next: Int) {
        if (loading) return
        offset = next; page = null; failed = false; loading = true; request++
    }
    AlertDialog(onDismissRequest = onClose,
        title = { Text(stringResource(R.string.memory_relations_open)) },
        text = {
            LaunchedEffect(request) {
                val currentRequest = request
                val currentOffset = offset
                try {
                    val result = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentPendingRelations(20, currentOffset) }
                    coroutineContext.ensureActive()
                    if (request == currentRequest) { page = result; failed = false }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    coroutineContext.ensureActive()
                    if (request == currentRequest) { page = null; failed = true }
                } finally { if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && request == currentRequest) loading = false }
            }
            when {
                loading -> Text(stringResource(R.string.memory_inspector_loading), modifier = Modifier.testTag("memory-relations-loading"))
                failed -> Text(stringResource(R.string.memory_relations_read_failed), modifier = Modifier.testTag("memory-relations-error"))
                else -> page?.let { result ->
                    LazyColumn(Modifier.heightIn(max = 440.dp).testTag("memory-relations-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Text(stringResource(R.string.memory_relations_questions))
                            Text(stringResource(R.string.memory_relations_page, result.total, offset), modifier = Modifier.testTag("memory-relations-page"))
                            if (result.items.isEmpty()) Text(stringResource(R.string.memory_relations_empty), modifier = Modifier.testTag("memory-relations-empty"))
                        }
                        items(result.items, key = { it.judgmentId }) { relation ->
                            Column(Modifier.testTag("memory-relation-${relation.judgmentId}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.memory_relations_identity, relation.judgmentId, relation.relation, relation.status))
                                Text(stringResource(R.string.memory_relations_endpoint, relation.sourceId, if (relation.sourceMissing) stringResource(R.string.memory_relations_missing) else relation.sourceTitle.orEmpty()), modifier = Modifier.testTag("memory-relation-source-${relation.judgmentId}"))
                                TextButton(enabled = !loading && relation.status != "orphaned", onClick = { if (!loading) onJudge(relation.judgmentId) }, modifier = Modifier.testTag("memory-judgment-open-${relation.judgmentId}")) { Text(stringResource(R.string.memory_judgment_open)) }
                                TextButton(enabled = !relation.sourceMissing, onClick = { if (!relation.sourceMissing && !loading) onInspect(relation.sourceId) }, modifier = Modifier.testTag("memory-relation-source-open-${relation.judgmentId}")) { Text(stringResource(R.string.memory_relations_inspect_source)) }
                                Text(stringResource(R.string.memory_relations_endpoint, relation.targetId, if (relation.targetMissing) stringResource(R.string.memory_relations_missing) else relation.targetTitle.orEmpty()), modifier = Modifier.testTag("memory-relation-target-${relation.judgmentId}"))
                                TextButton(enabled = !relation.targetMissing, onClick = { if (!relation.targetMissing && !loading) onInspect(relation.targetId) }, modifier = Modifier.testTag("memory-relation-target-open-${relation.judgmentId}")) { Text(stringResource(R.string.memory_relations_inspect_target)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (!loading && failed) TextButton(onClick = { load(offset) }, modifier = Modifier.testTag("memory-relations-retry")) { Text(stringResource(R.string.memory_inspector_retry)) }
                if (!loading && offset > 0) TextButton(onClick = { load(maxOf(0, offset - 20)) }, modifier = Modifier.testTag("memory-relations-previous")) { Text(stringResource(R.string.memory_relations_previous)) }
                val next = page?.nextOffset
                if (!loading && page?.hasMore == true && next != null) TextButton(onClick = { load(next) }, modifier = Modifier.testTag("memory-relations-next")) { Text(stringResource(R.string.memory_relations_next)) }
            }
        },
        dismissButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("memory-relations-close")) { Text(stringResource(R.string.memory_inspector_close)) } },
    )
}

/** Manual observations-only operation; previews are comparison data, never permission tokens. */
@Composable
private fun ProjectMemoryDeleteDialog(onClose: () -> Unit, onCommitted: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf(0L) }
    var selectedProject by remember { mutableStateOf<String?>(null) }
    var hard by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf(false) }
    var summaries by remember { mutableStateOf(emptyList<PersonalMemoryProjectSummary>()) }
    var preview by remember { mutableStateOf<PersonalMemoryProjectDeletionPreview?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    fun load(project: String?, hardDelete: Boolean) {
        if (pending) return
        selectedProject = project; hard = hardDelete; preview = null; failure = null
        loading = true; request++
    }
    val genericFailure = stringResource(R.string.memory_projects_failed)
    AlertDialog(onDismissRequest = { if (!pending) onClose() },
        title = { Text(stringResource(if (hard) R.string.memory_projects_hard_title else R.string.memory_projects_title)) },
        text = {
            // Start only after this dialog subcomposition is committed, including fast empty responses.
            LaunchedEffect(request) {
                val current = request; val project = selectedProject; val mode = hard
                try {
                    if (project == null) {
                        val rows = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentMemoryProjects() }
                        coroutineContext.ensureActive()
                        if (current == request) summaries = rows
                    } else {
                        val captured = withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentPreviewProjectDeletion(project, mode) }
                        coroutineContext.ensureActive()
                        if (current == request) preview = captured
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { if (current == request) failure = error.message ?: genericFailure }
                finally { if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && current == request) loading = false }
            }
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.memory_projects_scope))
                when {
                    loading -> Text(stringResource(R.string.memory_inspector_loading), modifier = Modifier.testTag("memory-project-loading"))
                    failure != null -> {
                        Text(requireNotNull(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("memory-project-error"))
                        TextButton(enabled = !pending, onClick = { load(null, false) }, modifier = Modifier.testTag("memory-project-refresh")) {
                            Text(stringResource(R.string.memory_projects_refresh))
                        }
                    }
                    preview != null -> {
                        val captured = requireNotNull(preview)
                        Text(captured.project, modifier = Modifier.testTag("memory-project-target"))
                        Text(stringResource(R.string.memory_projects_counts, captured.liveCount, captured.removedCount, captured.totalCount, captured.pinnedCount, captured.promptCount, captured.sessionCount),
                            modifier = Modifier.testTag("memory-project-counts"))
                        Text(stringResource(if (captured.hardDelete) R.string.memory_projects_hard_warning else R.string.memory_projects_soft_warning))
                        if (!captured.hardDelete) TextButton(enabled = !pending, onClick = { if (!pending) load(captured.project, true) },
                            modifier = Modifier.testTag("memory-project-hard-choice")) { Text(stringResource(R.string.memory_projects_hard_choice)) }
                    }
                    summaries.isEmpty() -> Text(stringResource(R.string.memory_projects_empty), modifier = Modifier.testTag("memory-project-empty"))
                    else -> LazyColumn(Modifier.heightIn(max = 300.dp).testTag("memory-project-list")) {
                        items(summaries, key = { it.project }) { item ->
                            TextButton(enabled = !pending, onClick = { if (!pending) load(item.project, false) },
                                modifier = Modifier.testTag("memory-project-select-${item.project}")) {
                                Column {
                                    Text(item.project)
                                    Text(stringResource(R.string.memory_projects_counts, item.liveCount, item.removedCount, item.totalCount, item.pinnedCount, item.promptCount, item.sessionCount))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val captured = preview
            if (captured != null && failure == null) TextButton(enabled = !loading && !pending,
                modifier = Modifier.testTag("memory-project-confirm"), onClick = confirm@ {
                    if (loading || pending || preview !== captured) return@confirm
                    pending = true; failure = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).personalAgentDeleteProjectMemories(captured) }
                            coroutineContext.ensureActive()
                            onCommitted()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            // Never silently obtain a new preview and retry an enlarged target set.
                            preview = null; failure = error.message ?: genericFailure
                        } finally { pending = false }
                    }
                }) { Text(stringResource(if (captured.hardDelete) R.string.memory_projects_hard_confirm else R.string.memory_projects_soft_confirm)) }
        },
        dismissButton = { TextButton(enabled = !pending, modifier = Modifier.testTag("memory-project-cancel"),
            onClick = { if (!pending) onClose() }) { Text(stringResource(R.string.memory_delete_cancel)) } },
    )
}

/** Capture opt-in is independent of transcripts and remains subordinate to the actual memory gate. */
@Composable
private fun PromptCapturePreference() {
    val context = LocalContext.current.applicationContext
    val policy = remember(context) { MemoryWritePolicy.forContext(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var memoryEnabled by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(policy) {
        try {
            val states = withContext(Dispatchers.IO) { policy.readStateAsync().get() to policy.readPromptCaptureStateAsync().get() }
            coroutineContext.ensureActive()
            memoryEnabled = states.first.effectiveEnabled
            enabled = states.second.effectiveEnabled
            confirmed = states.first.confirmed && states.second.confirmed
            error = !confirmed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { confirmed = false; error = true }
        finally { pending = false }
    }
    fun persistChoice(requested: Boolean) {
        if (pending) return
        pending = true
        scope.launch {
            try {
                val states = withContext(Dispatchers.IO) {
                    policy.requestSetPromptCaptureEnabled(requested).get() to policy.readStateAsync().get()
                }
                coroutineContext.ensureActive()
                enabled = states.first.effectiveEnabled
                memoryEnabled = states.second.effectiveEnabled
                confirmed = states.first.confirmed && states.second.confirmed
                error = !confirmed
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { confirmed = false; error = true }
            finally { pending = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.memory_prompt_capture_label), modifier = Modifier.weight(1f))
            Switch(checked = enabled, enabled = confirmed && !pending && (memoryEnabled || enabled),
                modifier = Modifier.testTag("memory-prompt-capture"), onCheckedChange = change@ { requested ->
                    if (!confirmed || pending || (requested && !memoryEnabled)) return@change
                    persistChoice(requested)
                })
        }
        Text(stringResource(R.string.memory_prompt_capture_description))
        if (!memoryEnabled) Text(stringResource(R.string.memory_prompt_capture_memory_off), modifier = Modifier.testTag("memory-prompt-capture-inactive"))
        if (error) {
            Text(stringResource(R.string.memory_prompt_capture_failed), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("memory-prompt-capture-error"))
            TextButton(enabled = !pending, onClick = { if (!pending) persistChoice(false) },
                modifier = Modifier.testTag("memory-prompt-capture-recover")) { Text(stringResource(R.string.memory_prompt_capture_recover)) }
        }
    }
}

private data class MemoryRecordDeletionTarget(val id: String, val project: String, val prompt: Boolean)

/** Inspection only: choosing a row here never supplies a session binding to any writer. */
@Composable
private fun MemorySessionsDialog(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf(0L) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var sessions by remember { mutableStateOf(emptyList<PersonalMemorySessionView>()) }
    var prompts by remember { mutableStateOf(emptyList<PersonalMemoryPromptView>()) }
    var selected by remember { mutableStateOf<PersonalMemorySessionView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<MemoryRecordDeletionTarget?>(null) }
    val unavailable = stringResource(R.string.memory_sessions_failed)
    fun refresh(id: String?) {
        if (pending) return
        selectedId = id; selected = null; sessions = emptyList(); prompts = emptyList()
        error = null; loading = true; request++
    }
    AlertDialog(onDismissRequest = { if (!pending) onClose() },
        title = { Text(stringResource(R.string.memory_sessions_title)) },
        text = {
            LaunchedEffect(request) {
                val current = request; val id = selectedId
                try {
                    val result = withContext(Dispatchers.IO) {
                        val bridge = ChatDatabaseBridge.getInstance(context)
                        val all = bridge.personalAgentMemorySessions()
                        val row = id?.let { sought -> all.singleOrNull { it.id == sought } }
                        Triple(all, row, if (row == null) emptyList() else bridge.personalAgentMemoryPrompts(row.id))
                    }
                    coroutineContext.ensureActive()
                    if (current == request) {
                        sessions = result.first; selected = result.second; prompts = result.third
                        if (id != null && result.second == null) error = unavailable
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { if (current == request) error = failure.message ?: unavailable }
                finally { if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true && current == request) loading = false }
            }
            LazyColumn(Modifier.heightIn(max = 400.dp).testTag("memory-sessions-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(stringResource(R.string.memory_sessions_description)) }
                if (loading) item { Text(stringResource(R.string.memory_inspector_loading), modifier = Modifier.testTag("memory-sessions-loading")) }
                else if (error != null) item {
                    Text(requireNotNull(error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("memory-sessions-error"))
                }
                else if (selectedId == null) {
                    if (sessions.isEmpty()) item { Text(stringResource(R.string.memory_sessions_empty), modifier = Modifier.testTag("memory-sessions-empty")) }
                    items(sessions, key = { it.id }) { session ->
                        TextButton(enabled = !pending, onClick = { if (!pending) refresh(session.id) },
                            modifier = Modifier.testTag("memory-session-open-${session.id}")) {
                            Column {
                                Text(stringResource(R.string.memory_sessions_target, session.id, session.project))
                                Text(stringResource(R.string.memory_sessions_state, session.state, session.observationCount, session.promptCount))
                                if (session.state == "active" && session.endedAt == null) Text(stringResource(R.string.memory_sessions_unconfirmed_close))
                            }
                        }
                    }
                } else selected?.let { session ->
                    item {
                        Text(stringResource(R.string.memory_sessions_target, session.id, session.project), modifier = Modifier.testTag("memory-session-details"))
                        Text(stringResource(R.string.memory_sessions_state, session.state, session.observationCount, session.promptCount))
                                if (session.state == "active" && session.endedAt == null) Text(stringResource(R.string.memory_sessions_unconfirmed_close))
                        Text(stringResource(R.string.memory_sessions_times, session.startedAt.toString(), session.endedAt?.toString() ?: "—"))
                        TextButton(enabled = !pending, modifier = Modifier.testTag("memory-session-delete"),
                            onClick = { if (!pending) target = MemoryRecordDeletionTarget(session.id, session.project, false) }) {
                            Text(stringResource(R.string.memory_sessions_delete))
                        }
                    }
                    if (prompts.isEmpty()) item { Text(stringResource(R.string.memory_sessions_no_prompts), modifier = Modifier.testTag("memory-session-no-prompts")) }
                    items(prompts, key = { it.id }) { prompt ->
                        Column {
                            Text(stringResource(R.string.memory_sessions_target, prompt.id, prompt.project))
                            Text(prompt.content, modifier = Modifier.testTag("memory-prompt-content-${prompt.id}"))
                            Text(stringResource(R.string.memory_prompt_created, prompt.createdAt.toString()))
                            TextButton(enabled = !pending, modifier = Modifier.testTag("memory-prompt-delete-${prompt.id}"),
                                onClick = { if (!pending) target = MemoryRecordDeletionTarget(prompt.id, prompt.project, true) }) {
                                Text(stringResource(R.string.memory_prompt_delete))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { if (!loading) TextButton(enabled = !pending, modifier = Modifier.testTag("memory-sessions-refresh"),
            onClick = { if (!pending) refresh(null) }) { Text(stringResource(if (selectedId == null) R.string.memory_sessions_refresh else R.string.memory_sessions_back)) } },
        dismissButton = { TextButton(enabled = !pending, modifier = Modifier.testTag("memory-sessions-close"),
            onClick = { if (!pending) onClose() }) { Text(stringResource(R.string.memory_delete_cancel)) } },
    )
    target?.let { captured ->
        AlertDialog(onDismissRequest = { if (!pending) target = null },
            title = { Text(stringResource(if (captured.prompt) R.string.memory_prompt_delete else R.string.memory_sessions_delete)) },
            text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.memory_sessions_target, captured.id, captured.project), modifier = Modifier.testTag("memory-record-delete-target"))
                Text(stringResource(if (captured.prompt) R.string.memory_prompt_delete_warning else R.string.memory_sessions_delete_warning))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("memory-record-delete-error")) }
            } },
            confirmButton = { TextButton(enabled = !pending, modifier = Modifier.testTag("memory-record-delete-confirm"), onClick = confirm@ {
                if (pending || target !== captured) return@confirm
                pending = true; error = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { ChatDatabaseBridge.getInstance(context).let { bridge ->
                            if (captured.prompt) bridge.personalAgentDeleteMemoryPrompt(captured.id, captured.project)
                            else bridge.personalAgentDeleteMemorySession(captured.id, captured.project)
                        } }
                        coroutineContext.ensureActive()
                        target = null; pending = false
                        refresh(if (captured.prompt) selectedId else null)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: unavailable }
                    finally { pending = false }
                }
            }) { Text(stringResource(R.string.memory_sessions_confirm)) } },
            dismissButton = { TextButton(enabled = !pending, modifier = Modifier.testTag("memory-record-delete-cancel"),
                onClick = { if (!pending) { target = null; error = null } }) { Text(stringResource(R.string.memory_delete_cancel)) } },
        )
    }
}
