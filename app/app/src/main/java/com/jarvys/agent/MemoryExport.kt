package com.jarvys.agent

import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Portable data only; never a resumed runtime or an imported capability. */
data class MemoryExportOptions(val project: String? = null, val includePrompts: Boolean = false)
class MemoryExportOperationHandle internal constructor()
class PreparedMemoryExport internal constructor(
    internal val owner: Any, internal val file: File, internal val root: File,
    internal val identity: Pair<Long, Long>, internal val rootIdentity: Pair<Long, Long>,
    internal val snapshot: com.jarvys.agent.chatdb.MemoryExportSnapshot,
    val operationHandle: MemoryExportOperationHandle,
) {
    internal val consumed = AtomicBoolean(false)
    val sha256: String get() = snapshot.sha256
    val sizeBytes: Long get() = snapshot.sizeBytes
    val exportedAt: String get() = snapshot.exportedAt
    val projects: List<String> = Collections.unmodifiableList(ArrayList(snapshot.projects))
    val counts: Map<String, Long> = Collections.unmodifiableMap(LinkedHashMap(snapshot.counts))
    val includePrompts: Boolean get() = snapshot.options.includePrompts
    val unboundObservations: Long get() = snapshot.unboundObservations
    val warnings: List<String> = Collections.unmodifiableList(listOf(
        "This is selected memory data, not a complete application/history backup.",
        "Runtime authority, revision history and review-clear intent are excluded.",
        "Relations with endpoints outside the selected retained observations are excluded.",
        "Go re-export does not preserve Jarvys raw-byte/null/deletion extensions."
    ) + if (snapshot.unboundObservations > 0) listOf("Unbound observations are preserved, but this file cannot be imported directly by upstream Go 0.2.0.") else emptyList())
}
data class MemoryExportPreviewPage(val text: String, val nextOffset: Long?, val totalBytes: Long)
data class MemoryExportCleanupResult(val cleanupState: String, val physicalBusy: Boolean)
data class MemoryExportReceipt(val status: String, val sizeBytes: Long, val sha256: String,
    val cleanupState: String, val externalMayExist: Boolean)
