package com.jarvys.agent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

internal class MemoryNativeExport {
    var status: String? by mutableStateOf(null)
    var launch: (Boolean) -> Unit = { }
}

/** Single-flight ownership includes cancelled launches until their Android result is drained. */
internal class MemoryExportRequests {
    internal class Ticket(val includeJournal: Boolean) {
        var prepared: MemoryNativeActions.Prepared? = null
        var launched = false
        var claimed = false
        var cancelled = false
    }
    private var current: Ticket? = null
    @Synchronized fun reserve(includeJournal: Boolean): Ticket? {
        if (current != null) return null
        return Ticket(includeJournal).also { current = it }
    }
    @Synchronized fun attach(ticket: Ticket, prepared: MemoryNativeActions.Prepared): Boolean {
        if (current !== ticket || ticket.cancelled) return false
        ticket.prepared = prepared
        return true
    }
    @Synchronized fun launched(ticket: Ticket) { check(current === ticket && !ticket.cancelled); ticket.launched = true }
    @Synchronized fun claim(): Ticket? {
        val ticket = current ?: return null
        if (!ticket.launched || ticket.claimed) return null
        ticket.claimed = true
        return ticket
    }
    @Synchronized fun cancel(ticket: Ticket) {
        ticket.cancelled = true
        if (current === ticket && !ticket.launched) current = null
    }
    @Synchronized fun complete(ticket: Ticket) { if (current === ticket) current = null }
}

/** Real SAF selection survives its expected pause. No content is written before exact final admission. */
@Composable
internal fun rememberMemoryExport(store: MemoryStore): MemoryNativeExport {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val result = remember(store) { MemoryNativeExport() }
    val actions = remember(context) { MemoryNativeActions(context) }
    val token = remember(store) { CancellationToken.cancellable() }
    // These outlive scope switches. A cancelled old launch must drain before a new one starts.
    val requests = remember { MemoryExportRequests() }
    var outstandingResult by rememberSaveable { mutableStateOf(false) }
    var owned by remember(store) { mutableStateOf<MemoryExportRequests.Ticket?>(null) }
    DisposableEffect(store, token) { onDispose {
        token.cancel()
        owned?.let { requests.cancel(it); it.prepared?.close() }
    } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        outstandingResult = false
        val ticket = requests.claim() ?: return@rememberLauncherForActivityResult
        val exact = ticket.prepared
        if (uri == null || exact == null || ticket.cancelled) {
            exact?.close(); requests.complete(ticket); return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                val outcome = runCatching {
                    awaitNativeMemoryResume(lifecycle)
                    withContext(Dispatchers.IO) {
                        exact.use {
                            it.execute({ check(!ticket.cancelled); check(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }) {
                                val output = context.contentResolver.openOutputStream(uri)
                                    ?: error(context.getString(R.string.memory_native_export_destination_unavailable))
                                output.use { stream -> store.exportZip(stream, ticket.includeJournal) }
                            }
                        }
                    }
                }
                result.status = outcome.fold(
                    { context.getString(R.string.memory_native_export_success) },
                    { context.getString(R.string.memory_native_export_incomplete) },
                )
            } finally { exact.close(); requests.complete(ticket) }
        }
    }
    result.launch = launch@ { includeJournal ->
        if (outstandingResult) return@launch
        val ticket = requests.reserve(includeJournal) ?: return@launch
        owned = ticket // Synchronous reservation precedes any coroutine or IO.
        scope.launch {
            val prepared = withContext(Dispatchers.IO) { runCatching {
                actions.prepare(store, token)
            } }
            prepared.onSuccess {
                if (!requests.attach(ticket, it)) { it.close(); return@onSuccess }
                runCatching {
                    requests.launched(ticket)
                    outstandingResult = true
                    launcher.launch("jarvys-memory.zip")
                }.onFailure { failure ->
                    outstandingResult = false; requests.complete(ticket); it.close(); result.status = failure.message
                }
            }.onFailure { requests.complete(ticket); result.status = it.message }
        }
    }
    return result
}

private suspend fun awaitNativeMemoryResume(lifecycle: Lifecycle) {
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    suspendCancellableCoroutine<Unit> { continuation ->
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_RESUME) { lifecycle.removeObserver(this); continuation.resume(Unit) }
                else if (event == Lifecycle.Event.ON_DESTROY) { lifecycle.removeObserver(this); continuation.cancel() }
            }
        }
        continuation.invokeOnCancellation { lifecycle.removeObserver(observer) }
        lifecycle.addObserver(observer)
    }
}
