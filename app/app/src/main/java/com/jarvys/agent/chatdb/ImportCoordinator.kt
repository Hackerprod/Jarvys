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
            val mutex = locks.computeIfAbsent(sessionId) { Mutex() }
            mutex.lock()
            try {
                val report = if (ledger != null) {
                    importer.importConversation(sessionId, ledger, meta, options.copy(onProgress = progress))
                } else {
                    importer.importDeletedMetaIfPresent(sessionId, meta) ?: continue
                }
                reports += report
                val size = ledger?.length() ?: 0L
                progress(ImportProgress(sessionId, report.state, size, size))
            } finally {
                mutex.unlock()
            }
        }
        reports
    }

    suspend fun isImported(sessionId: String): Boolean = conversations.isImported(sessionId)

    /** M4 holds this lock while it stops JSONL writes and establishes the source-of-truth cutover. */
    suspend fun beginCutover(sessionId: String) {
        require(sessionId.isNotBlank())
        val lock = locks.computeIfAbsent(sessionId) { Mutex() }
        lock.lock()
        try {
            check(conversations.isImported(sessionId)) { "conversation import has not validated as done" }
            check(cutovers.add(sessionId)) { "cutover already held for this conversation" }
        } catch (failure: Throwable) {
            lock.unlock()
            throw failure
        }
    }

    /** Releases the lock acquired by [beginCutover] after the service has switched its readers/writers. */
    fun endCutover(sessionId: String) {
        val lock = locks[sessionId] ?: error("cutover was not begun for this conversation")
        check(cutovers.remove(sessionId)) { "cutover was not begun for this conversation" }
        lock.unlock()
    }

    private fun sessionId(file: File): String = file.name.removeSuffix(".jsonl")

    private fun importPriority(sessionId: String): Int = when {
        WorkspaceIdentity.isExecutionSession(sessionId) -> 0
        sessionId == WorkspaceIdentity.CENTRAL_SESSION -> 2
        else -> 1
    }

    private class SessionLocks {
        val locks = ConcurrentHashMap<String, Mutex>()
        val cutovers = ConcurrentHashMap.newKeySet<String>()
    }

    private companion object {
        private val registries = WeakHashMap<JarvysChatDatabase, SessionLocks>()

        private fun sharedSessionLocks(database: JarvysChatDatabase): SessionLocks = synchronized(registries) {
            registries.getOrPut(database) { SessionLocks() }
        }
    }
}
