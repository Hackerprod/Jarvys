package com.jarvys.agent.chatdb

import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Orders directory imports around the cross-conversation workspace journal references. */
internal class ImportCoordinator(
    private val database: JarvysChatDatabase,
    private val options: LedgerImportOptions = LedgerImportOptions(),
) {
    private val conversations = ConversationRepository(database)
    private val sessionLocks = sharedSessionLocks(database)
    private val locks = sessionLocks.locks
    private val cutovers = sessionLocks.cutovers
    private val sourceFiles = sessionLocks.sourceFiles

    /** Imports workspace execution ledgers first, ordinary conversations next, then Central. */
    suspend fun importAll(
        conversationsDir: File,
        progress: suspend (ImportProgress) -> Unit = {},
    ): List<ImportReport> = withContext(Dispatchers.IO) {
        require(conversationsDir.isDirectory) { "conversation directory does not exist" }
        val files = conversationsDir.listFiles { file -> file.isFile }?.toList().orEmpty()
        val ledgersBySession = files.filter { it.name.endsWith(".jsonl") }.associateBy(::sessionId)
        val metadataOnlySessions = files.asSequence()
            .filter { it.name.endsWith(".meta.json") }
            .map { it.name.removeSuffix(".meta.json") }
            .filter { it !in ledgersBySession }
            .toSet()
        val sessionIds = (ledgersBySession.keys + metadataOnlySessions)
            .sortedWith(compareBy<String>({ importPriority(it) }, { it }))
        val importer = LedgerImporter(database)
        val reports = mutableListOf<ImportReport>()
        for (sessionId in sessionIds) {
            val ledger = ledgersBySession[sessionId]
            val meta = File(conversationsDir, "$sessionId.meta.json")
            val writerLock = acquireLedgerWriterLock(sessionId)
            try {
                val report = if (ledger != null) {
                    sourceFiles[sessionId] = ledger
                    importer.importConversation(sessionId, ledger, meta, options.copy(onProgress = progress))
                } else {
                    importer.importDeletedMetaIfPresent(sessionId, meta) ?: continue
                }
                reports += report
                val size = ledger?.length() ?: 0L
                progress(ImportProgress(sessionId, report.state, size, size))
            } finally {
                writerLock.release()
            }
        }
        reports
    }

    suspend fun isImported(sessionId: String): Boolean = conversations.isImported(sessionId)

    /** Clears partial import data and its checkpoint under the same lock used by legacy writers. */
    suspend fun resetConversationImport(sessionId: String): ChatDbResult<Boolean> {
        val writerLock = acquireLedgerWriterLock(sessionId)
        return try {
            LedgerImporter(database, options).resetConversationImport(sessionId)
        } finally {
            writerLock.release()
        }
    }

    /**
     * M4 must acquire this token before each legacy JSONL append and hold it until the file write
     * is complete. Import and cutover use the same per-session lock. Releasing twice is an error.
     */
    suspend fun acquireLedgerWriterLock(sessionId: String): LedgerWriterLock {
        require(sessionId.isNotBlank())
        val lock = locks.computeIfAbsent(sessionId) { Mutex() }
        lock.lock()
        return LedgerWriterLock(sessionId, lock)
    }

    /**
     * Locks legacy writers, revalidates the actual file, and imports an append-only suffix before
     * allowing the service to switch to SQLite. Call [endCutover] after the source switch.
     */
    suspend fun beginCutover(sessionId: String) {
        val ledger = sourceFiles[sessionId]
            ?: error("ledger path is unknown; call beginCutover(sessionId, ledgerFile, metaFile)")
        beginCutover(sessionId, ledger)
    }

    suspend fun beginCutover(
        sessionId: String,
        ledgerFile: File,
        metaFile: File = File(ledgerFile.parentFile ?: File("."), "$sessionId.meta.json"),
    ) {
        require(sessionId.isNotBlank())
        check(!cutovers.containsKey(sessionId)) { "cutover already held for this conversation" }
        require(ledgerFile.isFile) { "conversation ledger does not exist: $sessionId" }
        val writerLock = acquireLedgerWriterLock(sessionId)
        try {
            sourceFiles[sessionId] = ledgerFile
            val importer = LedgerImporter(database, options)
            if (!importer.isValidatedSourceCurrent(sessionId, ledgerFile)) {
                val report = importer.importConversation(sessionId, ledgerFile, metaFile, options)
                check(report.state == "done") {
                    "conversation import is not cutover-ready: ${report.failureReason ?: report.state}"
                }
            }
            check(importer.isValidatedSourceCurrent(sessionId, ledgerFile)) {
                "conversation ledger changed while cutover was being validated"
            }
            check(cutovers.putIfAbsent(sessionId, writerLock) == null) {
                "cutover already held for this conversation"
            }
        } catch (failure: Throwable) {
            writerLock.release()
            throw failure
        }
    }

    /** Releases the lock acquired by [beginCutover] after the service has switched its readers/writers. */
    fun endCutover(sessionId: String) {
        val writerLock = cutovers.remove(sessionId) ?: error("cutover was not begun for this conversation")
        writerLock.release()
    }

    private fun sessionId(file: File): String = file.name.removeSuffix(".jsonl")

    private fun importPriority(sessionId: String): Int = when {
        WorkspaceIdentity.isExecutionSession(sessionId) -> 0
        sessionId == WorkspaceIdentity.CENTRAL_SESSION -> 2
        else -> 1
    }

    private class SessionLocks {
        val locks = ConcurrentHashMap<String, Mutex>()
        val cutovers = ConcurrentHashMap<String, LedgerWriterLock>()
        val sourceFiles = ConcurrentHashMap<String, File>()
    }

    class LedgerWriterLock internal constructor(
        val sessionId: String,
        private val mutex: Mutex,
    ) : AutoCloseable {
        @Volatile private var released = false

        @Synchronized fun release() {
            check(!released) { "ledger writer lock already released: $sessionId" }
            released = true
            mutex.unlock()
        }

        override fun close() = release()
    }

    private companion object {
        private val registries = WeakHashMap<JarvysChatDatabase, SessionLocks>()

        private fun sharedSessionLocks(database: JarvysChatDatabase): SessionLocks = synchronized(registries) {
            registries.getOrPut(database) { SessionLocks() }
        }
    }
}
