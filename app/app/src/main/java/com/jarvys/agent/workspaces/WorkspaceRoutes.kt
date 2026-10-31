package com.jarvys.agent.workspaces

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvys.agent.*
import com.jarvys.agent.R
import com.jarvys.agent.coding.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.EnumSet

/** File viewing is scoped to the registry owner and uses existing path/identity-checked readers. */
@Composable
fun WorkspaceFilesRoute(workspaceId: String) {
    val context = LocalContext.current
    var coding by rememberSaveable(workspaceId) { mutableStateOf(true) }
    var path by rememberSaveable(workspaceId) { mutableStateOf("") }
    var file by rememberSaveable(workspaceId) { mutableStateOf<String?>(null) }
    var cursor by rememberSaveable(workspaceId) { mutableStateOf<String?>(null) }
    var history by rememberSaveable(workspaceId) { mutableStateOf(emptyList<String>()) }
    var reload by remember { mutableIntStateOf(0) }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf(emptyList<String>()) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(workspaceId, coding, path, file, cursor, reload) {
        busy = true; error = null; nextCursor = null
        // Capture the exact page request before the IO dispatcher; navigation cannot retarget it.
        val requestedCoding = coding
        val requestedPath = path
        val requestedFile = file
        val requestedCursor = cursor
        withContext(Dispatchers.IO) {
            runCatching {
                WorkspaceIdentity.requireWorkspaceId(workspaceId)
                check(WorkspaceProjection.registryExists(context)) { "Workspace registry is unavailable" }
                WorkspaceRegistry(context).use { registry ->
                    val workspace = requireNotNull(registry.getWorkspace(workspaceId))
                    WorkspaceProjection.requireWorkspaceScope(workspace)
                    if (requestedCoding) {
                        val scope = ProjectScopeStore(context.filesDir).openExisting(workspace.executionSessionId)
                            .restrict(EnumSet.of(ProjectScope.Capability.READ))
                        if (requestedFile == null) {
                            val page = ProjectSearch(scope).list(requestedPath.ifEmpty { "." }, true, requestedCursor, 12000, CancellationToken.uncancellable())
                            Triple(page.entries, "", page.nextCursor)
                        } else {
                            val page = ProjectFileReader(scope).read(requestedFile, 1, null, requestedCursor, 16000, CancellationToken.uncancellable())
                            Triple(emptyList<String>(), page.text, page.nextCursor)
                        }
                    } else {
                        val store = WorkspaceStore.forCrewBoard(context, workspace.executionSessionId).forDelegatedAgent()
                        if (requestedFile == null) Triple(store.listExisting(requestedPath.ifEmpty { "." }), "", null)
                        else {
                            val page = workspaceSharedTextPage(workspace.executionSessionId, requestedFile,
                                store.read(requestedFile), requestedCursor)
                            Triple(emptyList<String>(), page.text, page.nextCursor)
                        }
                    }
                }
            }
        }.fold(onSuccess = { (rows, content, next) -> entries = rows; text = content; nextCursor = next },
            onFailure = { if (it is kotlinx.coroutines.CancellationException) throw it
                error = context.getString(R.string.workspace_file_unavailable); entries = emptyList(); text = ""; nextCursor = null })
        busy = false
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row {
            TextButton(enabled = !busy, onClick = { if (!busy) {
                busy = true; coding = true; path = ""; file = null; cursor = null; history = emptyList(); reload++
            } }) { Text(stringResource(R.string.workspace_coding_files)) }
            TextButton(enabled = !busy, onClick = { if (!busy) {
                busy = true; coding = false; path = ""; file = null; cursor = null; history = emptyList(); reload++
            } }) { Text(stringResource(R.string.workspace_shared_files)) }
        }
        Text(stringResource(R.string.workspace_file_read_only), style = MaterialTheme.typography.bodySmall)
        if (!coding && file == null && !busy && error == null && entries.size >= 200)
            Text(stringResource(R.string.workspace_directory_limit), style = MaterialTheme.typography.bodySmall)
        if (file != null || path.isNotEmpty()) TextButton(enabled = !busy, onClick = { if (!busy) {
            busy = true
            if (file != null) file = null else path = path.trimEnd('/').substringBeforeLast('/', "")
            cursor = null; history = emptyList()
        } }) { Text(stringResource(R.string.workspace_parent_folder)) }
        when {
            busy -> CircularProgressIndicator()
            error != null -> Column {
                Text(error!!)
                TextButton(enabled = !busy, onClick = { if (!busy) { busy = true; cursor = null; history = emptyList(); reload++ } }) {
                    Text(stringResource(R.string.workspace_retry_from_start))
                }
            }
            file != null -> LazyColumn(Modifier.weight(1f)) { item { SelectionContainer { Text(text) } } }
            else -> LazyColumn(Modifier.weight(1f)) {
                items(entries, key = { it }) { entry ->
                    TextButton(enabled = !busy, onClick = { if (!busy) {
                        busy = true
                        if (coding) {
                            if (entry.endsWith('/')) path = entry.trimEnd('/') else file = entry
                        } else {
                            val directory = entry.startsWith("DIR  ")
                            val name = if (directory) entry.removePrefix("DIR  ") else entry.removePrefix("FILE ").substringBeforeLast(" (")
                            val selected = if (path.isEmpty()) name else "$path/$name"
                            if (directory) path = selected else file = selected
                        }
                        cursor = null; history = emptyList()
                    } }) { Text(entry) }
                }
            }
        }
        if (cursor != null || nextCursor != null) WorkspacePageButtons(history.isNotEmpty(), cursor != null,
            nextCursor != null, !busy && error == null,
            onPrevious = { if (!busy && history.isNotEmpty()) { busy = true; cursor = history.last().ifEmpty { null }; history = history.dropLast(1) } },
            onNext = { if (!busy) nextCursor?.let { next -> busy = true; history = workspacePushCursor(history, cursor); cursor = next } },
            onFirst = { if (!busy) { busy = true; cursor = null; history = emptyList() } })
    }
}
