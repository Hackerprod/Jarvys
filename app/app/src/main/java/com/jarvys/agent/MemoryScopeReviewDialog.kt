package com.jarvys.agent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ReadOnlyMemoryPreview(val content: String, val grant: MemoryScopeGrant? = null, val legacyPath: String? = null)

private enum class ReviewSource { CURRENT_CHAT, LEGACY, PERSONAL_SHARED }

/** The only UI entry for explicit cross-conversation memory grants. No automatic copy/migration. */
@Composable
internal fun MemoryScopeReviewDialog(
    store: MemoryStore,
    legacyStore: MemoryStore?,
    onDismiss: () -> Unit,
    onMemoryChanged: () -> Unit,
) {
    val uiProtection = memoryUiProtection(onDismiss) ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val session = remember(store, legacyStore) { MemoryScopeReviewSession() }
    var source by remember { mutableStateOf(ReviewSource.CURRENT_CHAT) }
    var localFiles by remember { mutableStateOf(emptyList<MemoryStore.MemoryFileInfo>()) }
    var legacyFiles by remember { mutableStateOf(emptyList<MemoryStore.MemoryFileInfo>()) }
    var grants by remember { mutableStateOf(emptyList<MemoryScopeGrant>()) }
    var loading by remember { mutableStateOf(true) }
    var mutationInFlight by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var snapshotRequest by remember { mutableLongStateOf(0L) }
    var open by remember { mutableStateOf(true) }
    var approvedSnapshot by remember { mutableStateOf<ReadOnlyMemoryPreview?>(null) }

    fun close() {
        if (!open) return
        open = false
        snapshotRequest++
        session.close()
        onDismiss()
    }

    DisposableEffect(session, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) close()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            session.close()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(store) {
        val listener = object : MemoryStore.MemoryChangeListener {
            override fun onMemoryChanged(revision: MemoryStore.Revision) = onMemoryCleared()
            override fun onMemoryCleared() { scope.launch { if (open) refresh++ } }
        }
        MemoryStore.addGlobalSearchListener(listener)
        onDispose { MemoryStore.removeGlobalSearchListener(listener) }
    }

    LaunchedEffect(store, legacyStore, refresh) {
        loading = true
        val result = withContext(Dispatchers.IO) { runCatching {
            uiProtection.requireHumanUiInteraction()
            Triple(
                store.listFilesForUser().filterNot { it.path.startsWith("shared/") },
                legacyStore?.listFilesForUser().orEmpty(),
                store.listSharedPersonalForUser(),
            )
        } }
        result.onSuccess { (local, legacy, shared) ->
            localFiles = local
            legacyFiles = legacy
            grants = shared
        }.onFailure { error = context.getString(R.string.memory_scope_load_failed) }
        loading = false
    }

    fun review(sourceStore: MemoryStore, path: String) {
        if (mutationInFlight || !open || !uiProtection.allowsHumanUiAction()) return
        snapshotRequest++
        val ticket = session.beginReview()
        error = null
        status = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { uiProtection.requireHumanUiInteraction(); sourceStore.reviewForSharing(path) } }
            result.onSuccess { session.present(ticket, sourceStore, it) }
                .onFailure {
                    if (session.failed(ticket)) error = context.getString(R.string.memory_scope_review_failed)
                }
        }
    }

    fun approve() {
        if (mutationInFlight || !uiProtection.allowsHumanUiAction()) return
        val pending = session.takeApproved() ?: return
        mutationInFlight = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { uiProtection.requireHumanUiInteraction(); pending.source.approveSharedPersonal(pending.review) } }
            mutationInFlight = false
            result.onSuccess {
                source = ReviewSource.PERSONAL_SHARED
                status = context.getString(R.string.memory_scope_shared_success)
                onMemoryChanged()
                refresh++
            }.onFailure {
                error = context.getString(R.string.memory_scope_approval_failed)
                refresh++
            }
        }
    }

    fun revoke(grant: MemoryScopeGrant) {
        if (mutationInFlight || !uiProtection.allowsHumanUiAction()) return
        mutationInFlight = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { uiProtection.requireHumanUiInteraction(); store.revokeSharedPersonal(grant.id) } }
            mutationInFlight = false
            result.onSuccess {
                status = context.getString(R.string.memory_scope_revoked_success)
                approvedSnapshot = null
                onMemoryChanged()
                refresh++
            }.onFailure { error = context.getString(R.string.memory_scope_revoke_failed) }
        }
    }

    fun back() {
        snapshotRequest++
        if (session.pending != null || session.loading) session.cancel()
        else if (approvedSnapshot != null) approvedSnapshot = null
        else close()
    }

    BackHandler { back() }
    Dialog(onDismissRequest = { back() }, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.95f).padding(12.dp)
            .testTag("memory-scope-dialog"), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.memory_scope_title), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { close() }, modifier = Modifier.testTag("memory-scope-close")) {
                        Text(stringResource(R.string.memory_scope_close))
                    }
                }
                val pending = session.pending
                val snapshot = approvedSnapshot
                if (pending != null) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
                        .testTag("memory-scope-review-scroll"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.memory_scope_review_title), fontWeight = FontWeight.SemiBold)
                        ScopeProvenance(pending.review.sourcePath, pending.review.sourceConversationId,
                            pending.review.legacy, pending.review.revisionId, pending.review.sha256)
                        Text(stringResource(R.string.memory_scope_share_explanation))
                        Text(stringResource(R.string.memory_scope_exact_content), fontWeight = FontWeight.SemiBold)
                        SelectionContainer {
                            Text(pending.review.content, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth().testTag("memory-scope-exact-content"))
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = session.acknowledged, role = Role.Checkbox,
                                onValueChange = { if (uiProtection.allowsHumanUiAction()) session.acknowledge(it) else session.cancel() })
                            .testTag("memory-scope-consent")) {
                            Checkbox(checked = session.acknowledged, onCheckedChange = null)
                            Text(stringResource(R.string.memory_scope_consent), modifier = Modifier.weight(1f))
                        }
                    }
                    Button(onClick = { approve() }, enabled = session.acknowledged && !mutationInFlight,
                        modifier = Modifier.fillMaxWidth().testTag("memory-scope-approve")) {
                        Text(stringResource(R.string.memory_scope_approve))
                    }
                    TextButton(onClick = { session.cancel() }, modifier = Modifier.fillMaxWidth()
                        .testTag("memory-scope-cancel")) { Text(stringResource(R.string.cancel)) }
                } else if (snapshot != null) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        snapshot.grant?.let { grant ->
                            ScopeProvenance(grant.sourcePath, grant.sourceConversationId, grant.legacy, grant.revisionId, grant.sha256)
                        } ?: run {
                            Text(stringResource(R.string.memory_scope_source_legacy), fontWeight = FontWeight.SemiBold)
                            Text(snapshot.legacyPath.orEmpty(), fontFamily = FontFamily.Monospace)
                            Text(stringResource(R.string.memory_scope_legacy_read_only))
                        }
                        SelectionContainer { Text(snapshot.content, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("memory-scope-approved-content")) }
                    }
                    TextButton(onClick = { approvedSnapshot = null }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.memory_scope_back))
                    }
                } else {
                    Row(Modifier.fillMaxWidth()) {
                        ReviewSource.entries.forEach { option ->
                            TextButton(onClick = { snapshotRequest++; session.cancel(); source = option; error = null; status = null },
                                enabled = !mutationInFlight,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .testTag("memory-scope-tab-${option.name}")) {
                                Text(stringResource(when (option) {
                                    ReviewSource.CURRENT_CHAT -> R.string.memory_scope_current_chat
                                    ReviewSource.LEGACY -> R.string.memory_scope_legacy
                                    ReviewSource.PERSONAL_SHARED -> R.string.memory_scope_shared
                                }), fontWeight = if (source == option) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                    Text(stringResource(when (source) {
                        ReviewSource.CURRENT_CHAT -> R.string.memory_scope_local_explanation
                        ReviewSource.LEGACY -> R.string.memory_scope_legacy_explanation
                        ReviewSource.PERSONAL_SHARED -> R.string.memory_scope_shared_explanation
                    }), style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("memory-scope-error")) }
                    status?.let { Text(it, modifier = Modifier.testTag("memory-scope-status")) }
                    if (loading || session.loading || mutationInFlight) Text(stringResource(R.string.memory_loading))
                    LazyColumn(Modifier.weight(1f).testTag("memory-scope-list"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (source == ReviewSource.PERSONAL_SHARED) {
                            if (!loading && grants.isEmpty()) item { Text(stringResource(R.string.memory_scope_no_shared)) }
                            items(grants, key = { it.id }) { grant ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ScopeProvenance(grant.sourcePath, grant.sourceConversationId, grant.legacy,
                                        grant.revisionId, grant.sha256)
                                    Text(stringResource(when (grant.status) {
                                        "ACTIVE" -> R.string.memory_scope_status_active
                                        "STALE" -> R.string.memory_scope_status_stale
                                        else -> R.string.memory_scope_status_revoked
                                    }), modifier = Modifier.testTag("memory-scope-grant-status-${grant.id}"))
                                    Text(stringResource(R.string.memory_scope_approved_at, grant.approvedAt),
                                        style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = {
                                        val request = ++snapshotRequest
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                runCatching { uiProtection.requireHumanUiInteraction(); store.readSharedPersonalForUser(grant.id) }
                                            }
                                            if (open && request == snapshotRequest) {
                                                result.onSuccess { approvedSnapshot = ReadOnlyMemoryPreview(it, grant = grant) }
                                                    .onFailure { error = context.getString(R.string.memory_scope_load_failed) }
                                            }
                                        }
                                    }, enabled = !mutationInFlight,
                                        modifier = Modifier.testTag("memory-scope-view-${grant.id}")) {
                                        Text(stringResource(R.string.memory_scope_view_approved))
                                    }
                                    if (grant.status != "REVOKED") OutlinedButton(onClick = { revoke(grant) },
                                        enabled = !mutationInFlight,
                                        modifier = Modifier.testTag("memory-scope-revoke-${grant.id}")) {
                                        Text(stringResource(R.string.memory_scope_revoke))
                                    }
                                    HorizontalDivider()
                                }
                            }
                        } else {
                            val files = if (source == ReviewSource.LEGACY) legacyFiles else localFiles
                            val sourceStore = if (source == ReviewSource.LEGACY) legacyStore else store
                            if (!loading && files.isEmpty()) item { Text(stringResource(R.string.memory_empty)) }
                            items(files, key = { it.path }) { file ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(file.name, fontWeight = FontWeight.SemiBold)
                                    Text(file.path, fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall)
                                    if (file.description.isNotBlank()) Text(file.description)
                                    if (source == ReviewSource.LEGACY && sourceStore != null) OutlinedButton(onClick = {
                                        val request = ++snapshotRequest
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) { runCatching { uiProtection.requireHumanUiInteraction(); sourceStore.readForUser(file.path) } }
                                            if (open && request == snapshotRequest) {
                                                result.onSuccess { approvedSnapshot = ReadOnlyMemoryPreview(it, legacyPath = file.path) }
                                                    .onFailure { error = context.getString(R.string.memory_scope_load_failed) }
                                            }
                                        }
                                    }, enabled = !mutationInFlight && !session.loading,
                                        modifier = Modifier.testTag("memory-scope-read-legacy-${file.path}")) {
                                        Text(stringResource(R.string.memory_scope_read_legacy))
                                    }
                                    OutlinedButton(onClick = { if (sourceStore != null) review(sourceStore, file.path) },
                                        enabled = sourceStore != null && !mutationInFlight && !session.loading,
                                        modifier = Modifier.testTag("memory-scope-review-${file.path}")) {
                                        Text(stringResource(R.string.memory_scope_review))
                                    }
                                    HorizontalDivider()
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
private fun ScopeProvenance(path: String, conversationId: String?, legacy: Boolean, revision: Long, hash: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(if (legacy) R.string.memory_scope_source_legacy else R.string.memory_scope_source_chat),
            fontWeight = FontWeight.SemiBold)
        if (!legacy) Text(stringResource(R.string.memory_scope_conversation, conversationId.orEmpty()),
            style = MaterialTheme.typography.bodySmall)
        Text(path, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("memory-scope-source-path"))
        Text(if (revision > 0L) stringResource(R.string.memory_scope_revision, revision)
            else stringResource(R.string.memory_scope_revision_unavailable), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.memory_scope_hash, hash), fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall)
    }
}
