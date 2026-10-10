package com.jarvys.agent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun MemorySettingsScreen(
    store: MemoryStore,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    conversationId: String,
    onShowDisclosure: () -> Unit,
    onMemoryChanged: () -> Unit,
    reflectionEnabled: Boolean,
    reflectionStatus: String,
    lastReflectionMillis: Long,
    reflecting: Boolean,
    onReflectionEnabledChange: (Boolean) -> Unit,
    onReflectionDisclosure: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenFile: (String, Boolean) -> Unit,
    legacyStore: MemoryStore? = null,
    onCloseMemory: (() -> Unit)? = null,
) {
    val uiProtection = memoryUiProtection(onCloseMemory) ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(emptyList<MemoryStore.MemoryFileInfo>()) }
    var usedCharacters by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf("") }
    var inlineError by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var clearConfirmStep by remember { mutableStateOf(0) }
    var showAddNoteDialog by remember { mutableStateOf(false) }
    var addNotePath by remember { mutableStateOf("note.md") }
    var showDirectoryDialog by remember { mutableStateOf(false) }
    var directoryPath by remember { mutableStateOf("") }
    var showExportDialog by remember { mutableStateOf(false) }
    var includeJournal by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var showScopeReview by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            loading = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    uiProtection.requireHumanUiInteraction()
                    store.listFilesForUser().filterNot { it.path.startsWith("shared/") } to store.coreCharactersUsed()
                }
            }
            result.onSuccess { (nextFiles, chars) ->
                files = nextFiles
                usedCharacters = chars
                inlineError = null
            }.onFailure { inlineError = memoryErrorText(context, it.message) }
            loading = false
        }
    }

    LaunchedEffect(refreshKey) { reload() }
    LaunchedEffect(enabled) { if (!loading) reload() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null || !uiProtection.allowsHumanUiAction()) return@rememberLauncherForActivityResult
        scope.launch {
            exportStatus = context.getString(R.string.memory_exporting)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    uiProtection.requireHumanUiInteraction()
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: error(context.getString(R.string.memory_export_destination_error))
                    output.use { store.exportZip(it, includeJournal) }
                }
            }
            exportStatus = result.fold(
                { context.getString(R.string.memory_exported) },
                { context.getString(R.string.memory_export_failed, context.getString(R.string.memory_error_generic)) },
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
            MemoryHome(
                enabled = enabled,
                files = files,
                usedCharacters = usedCharacters,
                loading = loading,
                status = statusMessage ?: exportStatus,
                onEnabledChange = { if (uiProtection.allowsHumanUiAction()) onEnabledChange(it) },
                onShowDisclosure = onShowDisclosure,
                reflectionEnabled = reflectionEnabled,
                reflectionStatus = reflectionStatus,
                lastReflectionMillis = lastReflectionMillis,
                reflecting = reflecting,
                onReflectionEnabledChange = { if (uiProtection.allowsHumanUiAction()) onReflectionEnabledChange(it) },
                onReflectionDisclosure = onReflectionDisclosure,
                onOpenFile = { if (uiProtection.allowsHumanUiAction()) onOpenFile(it.path, false) },
                onAddNote = { addNotePath = "note.md"; showAddNoteDialog = true },
                onAddDirectory = { directoryPath = ""; showDirectoryDialog = true },
                onHistory = { if (uiProtection.allowsHumanUiAction()) onOpenHistory() },
                onExport = { showExportDialog = true },
                onClearMemory = { clearConfirmStep = 1 },
                onScopeReview = { if (uiProtection.allowsHumanUiAction()) showScopeReview = true },
            )
    }

    if (showScopeReview) {
        MemoryScopeReviewDialog(
            store = store,
            legacyStore = legacyStore,
            onDismiss = { showScopeReview = false },
            onMemoryChanged = { onMemoryChanged(); reload() },
        )
    }

    if (showAddNoteDialog) {
        AlertDialog(
            onDismissRequest = { showAddNoteDialog = false },
            title = { Text(stringResource(R.string.memory_add_note)) },
            text = {
                ScrollableDialogContent {
                    JarvysTextField(value = addNotePath, onValueChange = { addNotePath = it }, label = {
                        Text(stringResource(R.string.memory_file_path))
                    }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true)
                }
            },
            confirmButton = { Button(onClick = {
                val path = if (addNotePath.endsWith(".md")) addNotePath else "$addNotePath.md"
                showAddNoteDialog = false
                onOpenFile(path, true)
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.memory_continue), softWrap = true) } },
            dismissButton = { TextButton(onClick = { showAddNoteDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showDirectoryDialog) {
        AlertDialog(
            onDismissRequest = { showDirectoryDialog = false },
            title = { Text(stringResource(R.string.memory_add_section)) },
            text = {
                ScrollableDialogContent {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.memory_directory_explanation, MemoryConstants.MAX_DEPTH))
                        JarvysTextField(value = directoryPath, onValueChange = { directoryPath = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.memory_directory_path)) }, singleLine = true)
                        inlineError?.let { Text(memoryErrorText(context, it), color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = { Button(onClick = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { uiProtection.requireHumanUiInteraction(); store.createDirectoryForUser(directoryPath, conversationId) } }
                    result.onSuccess {
                        showDirectoryDialog = false
                        inlineError = null
                        statusMessage = context.getString(R.string.memory_section_created)
                        onMemoryChanged()
                        refreshKey = "folder-${System.currentTimeMillis()}"
                    }.onFailure { inlineError = memoryErrorText(context, it.message) }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.memory_create), softWrap = true) } },
            dismissButton = { TextButton(onClick = { showDirectoryDialog = false; inlineError = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.memory_export)) },
            text = {
                ScrollableDialogContent {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.memory_export_explanation))
                        Row(Modifier.fillMaxWidth().clickable { includeJournal = !includeJournal }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Switch(checked = includeJournal, onCheckedChange = { includeJournal = it })
                            Text(stringResource(R.string.memory_export_journal_optional), modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = {
                showExportDialog = false
                exportLauncher.launch("jarvys-memory.zip")
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.memory_export), softWrap = true) } },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (clearConfirmStep > 0) {
        val fileCount = files.size
        AlertDialog(
            onDismissRequest = { clearConfirmStep = 0 },
            title = { Text(if (clearConfirmStep == 1) stringResource(R.string.memory_clear_title) else stringResource(R.string.memory_clear_confirm_title)) },
            text = { ScrollableDialogContent {
                Text(if (clearConfirmStep == 1) stringResource(R.string.memory_clear_first_confirm, fileCount)
                    else stringResource(R.string.memory_clear_final_confirm))
            } },
            confirmButton = { Button(onClick = {
                if (clearConfirmStep == 1) clearConfirmStep = 2
                else scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { uiProtection.requireHumanUiInteraction(); store.clearAll(MemoryStore.Actor.USER) } }
                    result.onSuccess {
                        clearConfirmStep = 0
                        statusMessage = context.getString(R.string.memory_cleared)
                        onMemoryChanged()
                        refreshKey = "clear-${System.currentTimeMillis()}"
                    }.onFailure {
                        clearConfirmStep = 0
                        statusMessage = memoryErrorText(context, it.message)
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (clearConfirmStep == 1) stringResource(R.string.memory_continue) else stringResource(R.string.memory_clear_all), softWrap = true)
            } },
            dismissButton = { TextButton(onClick = { clearConfirmStep = 0 }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
internal fun MemoryEditorDestination(
    store: MemoryStore,
    conversationId: String,
    path: String,
    isNew: Boolean,
    onMemoryChanged: () -> Unit,
    onBack: () -> Unit,
    onMissingFile: () -> Unit,
) {
    val uiProtection = memoryUiProtection(onBack) ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by rememberSaveable(path, isNew) { mutableStateOf(true) }
    var name by rememberSaveable(path, isNew) { mutableStateOf("") }
    var description by rememberSaveable(path, isNew) { mutableStateOf("") }
    var body by rememberSaveable(path, isNew) { mutableStateOf("") }
    var savedName by rememberSaveable(path, isNew) { mutableStateOf("") }
    var savedDescription by rememberSaveable(path, isNew) { mutableStateOf("") }
    var savedBody by rememberSaveable(path, isNew) { mutableStateOf("") }
    var expectedRevision by rememberSaveable(path, isNew) { mutableStateOf(0L) }
    var editingNew by rememberSaveable(path, isNew) { mutableStateOf(isNew) }
    var error by rememberSaveable(path, isNew) { mutableStateOf<String?>(null) }
    var status by rememberSaveable(path, isNew) { mutableStateOf<String?>(null) }
    var conflict by rememberSaveable(path, isNew) { mutableStateOf(false) }
    var conflictDelete by rememberSaveable(path, isNew) { mutableStateOf(false) }
    var discardDialog by rememberSaveable(path, isNew) { mutableStateOf(false) }
    var deleteDialog by rememberSaveable(path, isNew) { mutableStateOf(false) }
    val dirty = editingNew || name != savedName || description != savedDescription || body != savedBody
    val isIndex = path == MemoryConstants.ROOT_INDEX || path.endsWith("/${MemoryConstants.ROOT_INDEX}")

    fun applyDocument(document: MemoryUiLogic.EditorDocument, revision: Long, newFile: Boolean) {
        name = document.name
        description = document.description
        body = document.body
        savedName = document.name
        savedDescription = document.description
        savedBody = document.body
        expectedRevision = revision
        editingNew = newFile
        loading = false
        error = null
        conflict = false
    }
    fun loadDisk() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                uiProtection.requireHumanUiInteraction()
                val text = store.readUserFile(path)
                MemoryUiLogic.parseDocument(path, text) to store.latestRevisionId(path)
            } }
            withContext(Dispatchers.Main) {
                result.onSuccess { (document, revision) -> applyDocument(document, revision, false) }
                    .onFailure {
                        if (!dirty) onMissingFile()
                        else {
                            editingNew = true
                            name = ""; description = ""; body = ""
                            savedName = ""; savedDescription = ""; savedBody = ""
                            expectedRevision = withContext(Dispatchers.IO) { store.latestRevisionId(path) }
                            status = context.getString(R.string.memory_conflict_file_removed)
                            conflict = false
                        }
                    }
            }
        }
    }
    LaunchedEffect(path, isNew) {
        if (isNew) {
            applyDocument(MemoryUiLogic.parseDocument(path, ""), withContext(Dispatchers.IO) { store.latestRevisionId(path) }, true)
        } else loadDisk()
    }

    fun requestBack() { if (dirty) discardDialog = true else onBack() }
    BackHandler(enabled = !discardDialog && !deleteDialog && !conflict) { requestBack() }
    fun save(overwrite: Boolean = false) {
        if (!uiProtection.allowsHumanUiAction()) return
        scope.launch {
            val serialized = try { MemoryUiLogic.serializeDocument(path, name, description, body) }
            catch (e: IllegalArgumentException) { error = memoryErrorText(context, e.message); return@launch }
            loading = true
            val result = withContext(Dispatchers.IO) { runCatching {
                uiProtection.requireHumanUiInteraction()
                store.writeUserFile(path, serialized, expectedRevision, overwrite, conversationId)
            } }
            withContext(Dispatchers.Main) {
                loading = false
                result.onSuccess {
                    onMemoryChanged()
                    onBack()
                }.onFailure {
                    if (it is MemoryStore.RevisionConflictException) { conflict = true; conflictDelete = false }
                    else error = memoryErrorText(context, it.message)
                }
            }
        }
    }
    fun delete(overwrite: Boolean = false) {
        if (!uiProtection.allowsHumanUiAction()) return
        scope.launch {
            loading = true
            val result = withContext(Dispatchers.IO) { runCatching {
                uiProtection.requireHumanUiInteraction()
                store.deleteUserFile(path, expectedRevision, overwrite, conversationId)
            } }
            withContext(Dispatchers.Main) {
                loading = false
                result.onSuccess {
                    onMemoryChanged()
                    onBack()
                }.onFailure {
                    if (it is MemoryStore.RevisionConflictException) { conflict = true; conflictDelete = true }
                    else error = memoryErrorText(context, it.message)
                }
            }
        }
    }
    MemoryEditor(path, isIndex, editingNew, name, description, body, loading, error, status,
        { name = it }, { description = it }, { body = it }, { save() }, { deleteDialog = true }, { status = null })
    if (discardDialog) AlertDialog(onDismissRequest = { discardDialog = false },
        title = { Text(stringResource(R.string.memory_unsaved_title)) },
        text = { ScrollableDialogContent { Text(stringResource(R.string.memory_unsaved_text)) } },
        confirmButton = { TextButton(onClick = { discardDialog = false; onBack() }) { Text(stringResource(R.string.memory_discard)) } },
        dismissButton = { TextButton(onClick = { discardDialog = false }) { Text(stringResource(R.string.cancel)) } })
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false },
        title = { Text(stringResource(R.string.memory_delete_title)) },
        text = { ScrollableDialogContent { Text(stringResource(R.string.memory_delete_file_confirm, path)) } },
        confirmButton = { Button(onClick = { deleteDialog = false; delete() }) { Text(stringResource(R.string.memory_delete)) } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text(stringResource(R.string.cancel)) } })
    if (conflict) AlertDialog(onDismissRequest = { conflict = false },
        title = { Text(stringResource(R.string.memory_conflict_title)) },
        text = { ScrollableDialogContent { Text(stringResource(R.string.memory_conflict_text)) } },
        confirmButton = { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { conflict = false; loadDisk() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.memory_reload)) }
            OutlinedButton(onClick = { conflict = false; if (conflictDelete) delete(true) else save(true) },
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.memory_overwrite)) }
        } }, dismissButton = { TextButton(onClick = { conflict = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun MemoryHistoryDestination(
    store: MemoryStore,
    conversationId: String,
    onMemoryChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val uiProtection = memoryUiProtection(onBack) ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revisions by remember { mutableStateOf(emptyList<MemoryStore.Revision>()) }
    var actor by remember { mutableStateOf<MemoryStore.Actor?>(null) }
    var actorMenu by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store) {
        val result = withContext(Dispatchers.IO) { runCatching {
            uiProtection.requireHumanUiInteraction()
            store.listRevisionsForUser(null, null)
        } }
        result.onSuccess { revisions = it }.onFailure { status = memoryErrorText(context, it.message) }
    }
    fun restore(id: Long) { scope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { uiProtection.requireHumanUiInteraction(); store.restoreRevisionForUser(id, conversationId) } }
        withContext(Dispatchers.Main) {
            result.onSuccess { onMemoryChanged(); onBack() }
                .onFailure { status = memoryErrorText(context, it.message) }
        }
    } }
    fun undo() { scope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { uiProtection.requireHumanUiInteraction(); store.undoLastForUser(conversationId) } }
        withContext(Dispatchers.Main) {
            result.onSuccess { onMemoryChanged(); onBack() }
                .onFailure { status = memoryErrorText(context, it.message) }
        }
    } }
    Column(Modifier.fillMaxSize()) {
        status?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        MemoryHistory(
            revisions = revisions.filter { (actor == null || it.actor == actor) && (path.isBlank() || it.path.contains(path.trim(), true)) },
            actorFilter = actor, actorMenu = actorMenu, pathFilter = path, expandedId = expanded,
            onActorMenu = { actorMenu = it }, onActorFilter = { actor = it; actorMenu = false },
            onPathFilter = { path = it }, onExpand = { expanded = if (expanded == it) null else it },
            onRestore = ::restore, onUndoLast = ::undo,
        )
    }
}

@Composable
private fun MemoryHome(
    enabled: Boolean,
    files: List<MemoryStore.MemoryFileInfo>,
    usedCharacters: Int,
    loading: Boolean,
    status: String?,
    onEnabledChange: (Boolean) -> Unit,
    onShowDisclosure: () -> Unit,
    reflectionEnabled: Boolean,
    reflectionStatus: String,
    lastReflectionMillis: Long,
    reflecting: Boolean,
    onReflectionEnabledChange: (Boolean) -> Unit,
    onReflectionDisclosure: () -> Unit,
    onOpenFile: (MemoryStore.MemoryFileInfo) -> Unit,
    onAddNote: () -> Unit,
    onAddDirectory: () -> Unit,
    onHistory: () -> Unit,
    onExport: () -> Unit,
    onClearMemory: () -> Unit,
    onScopeReview: () -> Unit,
) {
    val core = files.filter { it.core }
    val deferred = files.filterNot { it.core }
    val ratio = (usedCharacters.toFloat() / MemoryConstants.MAX_CORE_MEMORY_CHARACTERS).coerceIn(0f, 1f)
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("memory-home-list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AutomaticMemoryReflectionCard(
                memoryEnabled = enabled,
                reflectionEnabled = reflectionEnabled,
                reflectionStatus = reflectionStatus,
                lastReflectionMillis = lastReflectionMillis,
                reflecting = reflecting,
                onReflectionEnabledChange = onReflectionEnabledChange,
                onReflectionDisclosure = onReflectionDisclosure,
            )
        }
        item {
            JarvysSwitchRow(
                title = stringResource(R.string.memory_master_title),
                description = stringResource(R.string.memory_description) + "\n" +
                    stringResource(if (enabled) R.string.memory_state_on else R.string.memory_state_off),
                checked = enabled,
                onCheckedChange = onEnabledChange,
                icon = LucideIcons.Brain,
                modifier = Modifier.testTag("memory-master-toggle"),
            )
        }
        item {
            JarvysGroup(contentPadding = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.memory_storage_provider_disclosure), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp, lineHeight = 18.sp)
                    TextButton(onClick = onShowDisclosure, modifier = Modifier.fillMaxWidth()) {
                        Icon(LucideIcons.Info, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.size(8.dp, 1.dp))
                        Text(stringResource(R.string.memory_privacy_details), softWrap = true)
                    }
                }
            }
        }
        item {
            JarvysGroup(contentPadding = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.memory_core_usage), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.memory_usage_value, usedCharacters, MemoryConstants.MAX_CORE_MEMORY_CHARACTERS),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(ratio).height(8.dp).clip(CircleShape).background(
                            if (ratio >= 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
        item {
            JarvysGroup(contentPadding = PaddingValues(vertical = 4.dp)) {
                Column {
                    JarvysListRow(stringResource(R.string.memory_scope_manage), icon = LucideIcons.Shield,
                        modifier = Modifier.testTag("memory-scope-open"), onClick = onScopeReview)
                    HorizontalDivider(Modifier.padding(start = JarvysUiTokens.ScreenPadding))
                    JarvysListRow(stringResource(R.string.memory_add_note), icon = LucideIcons.Plus,
                        modifier = Modifier.testTag("memory-add-note"), onClick = onAddNote)
                    HorizontalDivider(Modifier.padding(start = JarvysUiTokens.ScreenPadding))
                    JarvysListRow(stringResource(R.string.memory_add_section), icon = LucideIcons.Layers2,
                        modifier = Modifier.testTag("memory-add-section"), onClick = onAddDirectory)
                    HorizontalDivider(Modifier.padding(start = JarvysUiTokens.ScreenPadding))
                    JarvysListRow(stringResource(R.string.memory_history), icon = LucideIcons.History,
                        modifier = Modifier.testTag("memory-history"), onClick = onHistory)
                    HorizontalDivider(Modifier.padding(start = JarvysUiTokens.ScreenPadding))
                    JarvysListRow(stringResource(R.string.memory_export), icon = LucideIcons.Download,
                        modifier = Modifier.testTag("memory-export"), onClick = onExport)
                    HorizontalDivider(Modifier.padding(start = JarvysUiTokens.ScreenPadding))
                    JarvysListRow(stringResource(R.string.memory_clear_all), subtitleColor = MaterialTheme.colorScheme.error,
                        icon = LucideIcons.Trash2, modifier = Modifier.testTag("memory-clear-all"), onClick = onClearMemory)
                }
            }
        }
        status?.let { item { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) } }
        if (loading) item { Text(stringResource(R.string.memory_loading), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { MemorySectionTitle(stringResource(R.string.memory_core_section), core.size) }
        if (core.isEmpty()) item { MemoryEmpty() }
        items(core, key = { "core:${it.path}" }) { file -> MemoryFileRow(file, onOpenFile) }
        item { MemorySectionTitle(stringResource(R.string.memory_deferred_section), deferred.size) }
        if (deferred.isEmpty()) item { MemoryEmpty() }
        items(deferred, key = { "deferred:${it.path}" }) { file -> MemoryFileRow(file, onOpenFile) }
        item {
            JarvysGroup(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                contentPadding = PaddingValues(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(LucideIcons.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.memory_connector_trust_note), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
internal fun AutomaticMemoryReflectionCard(
    memoryEnabled: Boolean,
    reflectionEnabled: Boolean,
    reflectionStatus: String,
    lastReflectionMillis: Long,
    reflecting: Boolean,
    onReflectionEnabledChange: (Boolean) -> Unit,
    onReflectionDisclosure: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("memory-reflection-card"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        JarvysSwitchRow(
            title = stringResource(R.string.reflection_title),
            description = stringResource(R.string.reflection_summary),
            checked = reflectionEnabled,
            onCheckedChange = onReflectionEnabledChange,
            enabled = memoryEnabled,
            icon = LucideIcons.Sparkles,
            modifier = Modifier.testTag("memory-reflection-toggle"),
        )
        JarvysGroup(
            modifier = Modifier.testTag("memory-reflection-status-zone"),
            contentPadding = PaddingValues(14.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                JarvysSectionLabel(stringResource(R.string.reflection_status_heading))
                Text(
                    if (reflecting) stringResource(R.string.reflection_status_running)
                    else reflectionStatus.ifBlank { stringResource(R.string.reflection_status_idle) },
                    modifier = Modifier.fillMaxWidth().testTag("memory-reflection-status"),
                    color = if (reflecting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (lastReflectionMillis > 0L) stringResource(R.string.reflection_status_last,
                        SimpleDateFormat.getDateTimeInstance(SimpleDateFormat.SHORT, SimpleDateFormat.SHORT,
                            LocalContext.current.resources.configuration.locales[0]).format(Date(lastReflectionMillis)))
                    else stringResource(R.string.reflection_status_never),
                    modifier = Modifier.fillMaxWidth().testTag("memory-reflection-last-status"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        TextButton(onClick = onReflectionDisclosure,
            modifier = Modifier.fillMaxWidth().testTag("memory-reflection-privacy-details")) {
            Icon(LucideIcons.Info, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.size(8.dp, 1.dp))
            Text(stringResource(R.string.reflection_privacy_details), softWrap = true)
            }
    }
}

@Composable
private fun MemorySectionTitle(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun MemoryEmpty() {
    Text(stringResource(R.string.memory_empty), modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
}

@Composable
private fun MemoryFileRow(file: MemoryStore.MemoryFileInfo, onOpen: (MemoryStore.MemoryFileInfo) -> Unit) {
    JarvysGroup(modifier = Modifier.clickable { onOpen(file) }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(file.name, modifier = Modifier.weight(1f), maxLines = 2, fontWeight = FontWeight.Medium,
                    overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                Text(stringResource(R.string.memory_file_character_count, file.characters), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp, maxLines = 1)
            }
            Text(file.path, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (file.description.isNotBlank()) Text(file.description, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.memory_modified_by, actorLabel(file.modifiedBy), displayTimestamp(file.modifiedAt)),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MemoryEditor(
    path: String,
    isIndex: Boolean,
    isNew: Boolean,
    name: String,
    description: String,
    body: String,
    loading: Boolean,
    error: String?,
    status: String?,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onClearStatus: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(path, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!isIndex) {
            JarvysTextField(value = name, onValueChange = onNameChange, modifier = Modifier.fillMaxWidth().testTag("memory-editor-name"),
                label = { Text(stringResource(R.string.memory_editor_name)) }, singleLine = true)
            JarvysTextField(value = description, onValueChange = onDescriptionChange, modifier = Modifier.fillMaxWidth().testTag("memory-editor-description"),
                label = { Text(stringResource(R.string.memory_editor_description)) }, minLines = 2, maxLines = 4)
        } else {
            Text(stringResource(R.string.memory_index_hint), color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp, lineHeight = 17.sp)
        }
        JarvysTextField(value = body, onValueChange = onBodyChange,
            modifier = Modifier.fillMaxWidth().height(300.dp).testTag("memory-editor-body"),
            label = { Text(stringResource(R.string.memory_editor_body)) }, minLines = 10, maxLines = 18,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
        Text(stringResource(R.string.memory_editor_limit, MemoryConstants.MAX_FILE_CHARACTERS),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        error?.let { Text(memoryErrorText(context, it), modifier = Modifier.testTag("memory-editor-error"),
            color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 18.sp) }
        status?.let { Text(it, modifier = Modifier.clickable(onClick = onClearStatus), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
        JarvysPrimaryButton(stringResource(R.string.memory_save), onSave,
            Modifier.testTag("memory-editor-save"), enabled = !loading)
        if (!isNew && path != MemoryConstants.ROOT_INDEX && !path.endsWith("/${MemoryConstants.ROOT_INDEX}")) {
            OutlinedButton(onClick = onDelete, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                Icon(LucideIcons.Trash2, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(8.dp, 1.dp)); Text(stringResource(R.string.memory_delete), color = MaterialTheme.colorScheme.error, softWrap = true)
            }
        }
    }
}

@Composable
private fun MemoryHistory(
    revisions: List<MemoryStore.Revision>,
    actorFilter: MemoryStore.Actor?,
    actorMenu: Boolean,
    pathFilter: String,
    expandedId: Long?,
    onActorMenu: (Boolean) -> Unit,
    onActorFilter: (MemoryStore.Actor?) -> Unit,
    onPathFilter: (String) -> Unit,
    onExpand: (Long) -> Unit,
    onRestore: (Long) -> Unit,
    onUndoLast: () -> Unit,
) {
    var pendingRestore by remember { mutableStateOf<Long?>(null) }
    var confirmUndo by remember { mutableStateOf(false) }
    val actorLabelText = actorFilter?.let { actorLabel(it) } ?: stringResource(R.string.memory_history_all_actors)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                JarvysTextField(value = pathFilter, onValueChange = onPathFilter, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.memory_history_path_filter)) }, singleLine = true)
                Box {
                    OutlinedButton(onClick = { onActorMenu(true) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.memory_history_actor_filter, actorLabelText), softWrap = true)
                    }
                    DropdownMenu(expanded = actorMenu, onDismissRequest = { onActorMenu(false) }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.memory_history_all_actors)) }, onClick = { onActorFilter(null) })
                        MemoryStore.Actor.values().forEach { actor ->
                            DropdownMenuItem(text = { Text(actorLabel(actor)) }, onClick = { onActorFilter(actor) })
                        }
                    }
                }
                Button(onClick = { confirmUndo = true }, enabled = revisions.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Icon(LucideIcons.RotateCcw, null); Spacer(Modifier.size(8.dp, 1.dp)); Text(stringResource(R.string.memory_undo_last), softWrap = true)
                }
            }
        }
        if (revisions.isEmpty()) item { MemoryEmpty() }
        items(revisions, key = { it.id }) { revision ->
            val expanded = expandedId == revision.id
            JarvysGroup(modifier = Modifier.clickable { onExpand(revision.id) }, contentPadding = PaddingValues(14.dp)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActorBadge(revision.actor)
                        Text(revision.operation, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("#${revision.id}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                    Text(revision.path, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(displayTimestamp(revision.timestamp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    if (expanded) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Text(stringResource(R.string.memory_before), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text(MemoryUiLogic.shortContent(revision.previousContent).ifBlank { stringResource(R.string.memory_empty_version) },
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.memory_after), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text(MemoryUiLogic.shortContent(revision.newContent).ifBlank { stringResource(R.string.memory_empty_version) },
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.primary)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            MemoryUiLogic.diffLines(revision.previousContent, revision.newContent).forEach { line ->
                                val (prefix, tint) = when (line.kind) {
                                    MemoryUiLogic.DiffKind.ADDED -> "+ " to MaterialTheme.colorScheme.primary
                                    MemoryUiLogic.DiffKind.REMOVED -> "− " to MaterialTheme.colorScheme.error
                                    MemoryUiLogic.DiffKind.UNCHANGED -> "  " to MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Text(prefix + line.text, color = tint, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp)
                            }
                        }
                        OutlinedButton(onClick = { pendingRestore = revision.id }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.memory_restore_version), softWrap = true)
                        }
                    }
                }
            }
        }
    }
    pendingRestore?.let { revisionId ->
        AlertDialog(onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.memory_restore_title)) },
            text = { ScrollableDialogContent { Text(stringResource(R.string.memory_restore_confirm, revisionId)) } },
            confirmButton = { Button(onClick = { pendingRestore = null; onRestore(revisionId) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.memory_restore_version), softWrap = true)
            } },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (confirmUndo) {
        AlertDialog(onDismissRequest = { confirmUndo = false },
            title = { Text(stringResource(R.string.memory_undo_title)) },
            text = { ScrollableDialogContent { Text(stringResource(R.string.memory_undo_confirm)) } },
            confirmButton = { Button(onClick = { confirmUndo = false; onUndoLast() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.memory_undo_last), softWrap = true)
            } },
            dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun ActorBadge(actor: MemoryStore.Actor) {
    val label = actorLabel(actor)
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(100.dp)) {
        Text(label, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun actorLabel(actor: MemoryStore.Actor): String = stringResource(when (actor) {
    MemoryStore.Actor.USER -> R.string.memory_actor_user
    MemoryStore.Actor.AGENT -> R.string.memory_actor_agent
    MemoryStore.Actor.REFLECTION -> R.string.memory_actor_reflection
})

private fun memoryErrorText(context: android.content.Context, message: String?): String = when (MemoryUiLogic.validationIssue(message)) {
    MemoryUiLogic.ValidationIssue.FRONTMATTER -> context.getString(R.string.memory_error_frontmatter)
    MemoryUiLogic.ValidationIssue.INDEX -> context.getString(R.string.memory_error_index)
    MemoryUiLogic.ValidationIssue.DEPTH -> context.getString(R.string.memory_error_depth, MemoryConstants.MAX_DEPTH)
    MemoryUiLogic.ValidationIssue.FILE_LIMIT -> context.getString(R.string.memory_error_file_limit, MemoryConstants.MAX_FILE_CHARACTERS)
    MemoryUiLogic.ValidationIssue.CORE_LIMIT -> context.getString(R.string.memory_error_core_limit, MemoryConstants.MAX_CORE_MEMORY_CHARACTERS)
    MemoryUiLogic.ValidationIssue.SECRET -> context.getString(R.string.memory_error_secret)
    MemoryUiLogic.ValidationIssue.PATH -> context.getString(R.string.memory_error_path)
    MemoryUiLogic.ValidationIssue.COLLISION -> context.getString(R.string.memory_error_collision)
    MemoryUiLogic.ValidationIssue.GENERAL -> context.getString(R.string.memory_error_generic)
}

private fun displayTimestamp(value: String): String {
    if (value.isBlank()) return ""
    return value.replace('T', ' ').removeSuffix("Z").take(16)
}
