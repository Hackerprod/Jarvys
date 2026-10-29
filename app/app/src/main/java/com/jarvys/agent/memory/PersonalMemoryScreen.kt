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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.jarvys.agent.MemoryWritePolicy
import com.jarvys.agent.PersonalMemoryConsolidationWorker
import com.jarvys.agent.R
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.PersonalMemorySessionView
import com.jarvys.agent.chatdb.PersonalMemoryPromptView
import com.jarvys.agent.chatdb.PersonalMemoryProjectSummary
import com.jarvys.agent.chatdb.PersonalMemoryProjectDeletionPreview
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
    var showPendingRelations by remember { mutableStateOf(false) }
    var judgmentTarget by remember { mutableStateOf<String?>(null) }
    var reloadGeneration by remember { mutableStateOf(0L) }
    val deleteFailure = stringResource(R.string.memory_delete_failed)
    val pinFailure = stringResource(R.string.memory_pin_failed)
    var dailyConsolidationEnabled by remember {
        mutableStateOf(context.getSharedPreferences(PersonalMemoryConsolidationWorker.PREFERENCES, android.content.Context.MODE_PRIVATE)
            .getBoolean(PersonalMemoryConsolidationWorker.DAILY_ENABLED_KEY, true))
    }

    fun reload() {
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

    if (showMemorySessions) MemorySessionsDialog { showMemorySessions = false }

    if (showProjectMemories) ProjectMemoryDeleteDialog(
        onClose = { showProjectMemories = false },
        onCommitted = { showProjectMemories = false; reload() },
    )

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
