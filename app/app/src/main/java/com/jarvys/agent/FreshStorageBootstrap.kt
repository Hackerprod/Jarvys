package com.jarvys.agent

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ConversationRepository
import com.jarvys.agent.chatdb.JarvysChatDatabaseProvider
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Existing installations are never imported, reset, moved or deleted by this binary. */
class CleanInstallRequiredException : IllegalStateException("clean_install_required")

/** Idempotent initialization of the current fresh store; no historical upgrade protocol. */
object FreshStorageBootstrap {
    const val GENERATION_EXTRA = "storage_generation"
    private val lock = Any()
    private val prepared = mutableSetOf<String>()
    private class Startup {
        val completion = CompletableDeferred<Unit>()
        var started = false
    }
    private val startups = mutableMapOf<String, Startup>()
    private val preparing = ThreadLocal<Boolean>()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "JarvysStorageStartup").apply { isDaemon = true }
    }
    @JvmStatic fun stamp(intent: Intent): Intent = intent.putExtra(GENERATION_EXTRA, StorageGeneration.NAME).addCategory("com.jarvys.agent.storage." + StorageGeneration.NAME)
    @JvmStatic fun isCurrent(intent: Intent?): Boolean = intent?.getStringExtra(GENERATION_EXTRA) == StorageGeneration.NAME

    internal var executorForTests: Executor? = null

    @JvmStatic fun start(context: Context) = start(context, executorForTests ?: worker)

    // Android installs providers (including WorkManager) before Application.onCreate.
    internal fun register(filesDir: File) = synchronized(startups) {
        startups.getOrPut(filesDir.canonicalPath) { Startup() }
        Unit
    }

    internal fun start(context: Context, executor: Executor) {
        val app = context.applicationContext
        val completion = synchronized(startups) {
            val startup = startups.getOrPut(app.filesDir.canonicalPath) { Startup() }
            if (startup.started) return
            startup.started = true
            startup.completion
        }
        try {
            executor.execute {
                try {
                    prepareOnWorker(app)
                    completion.complete(Unit)
                } catch (failure: Exception) {
                    completion.completeExceptionally(failure)
                    AgentErrorReporter.reportBackgroundFailure(app, failure)
                }
            }
        } catch (rejected: java.util.concurrent.RejectedExecutionException) {
            completion.completeExceptionally(rejected)
            throw rejected
        }
    }

    private fun completion(filesDir: File): CompletableDeferred<Unit>? =
        synchronized(startups) { startups[filesDir.canonicalPath]?.completion }

    @JvmStatic fun isReady(context: Context): Boolean = completion(context.filesDir)?.let {
        it.isCompleted && !it.isCancelled
    } ?: true

    suspend fun awaitReady(context: Context) { completion(context.filesDir)?.await() }

    /** Synchronous stores may wait only on a worker; UI entrypoints suspend before constructing them. */
    @JvmStatic fun awaitReadyBlocking(filesDir: File) {
        if (preparing.get() == true) return
        val completion = completion(filesDir) ?: return
        if (!completion.isCompleted) check(Looper.myLooper() != Looper.getMainLooper()) {
            "Conversation storage is still starting; await it off the main thread"
        }
        runBlocking { completion.await() }
    }

    @JvmStatic fun ensure(context: Context) {
        if (completion(context.filesDir) != null) {
            awaitReadyBlocking(context.filesDir)
            return
        }
        prepareOnWorker(context)
    }

    private fun prepareOnWorker(context: Context) {
        preparing.set(true)
        try {
            synchronized(lock) {
                val app = context.applicationContext
                requireFreshInstall(app)
                val key = app.filesDir.canonicalPath
                if (key !in prepared) {
                    // Inspect both existing stores before creating either. SQLite read-only probes
                    // do not write application data/schema; WAL coordination may still use SHM.
                    JarvysChatDatabaseProvider.preflightFreshStorage(app)
                    WorkspaceRegistry.preflightFreshStorage(app)
                    check(StorageGeneration(app).chatRoot().let { it.isDirectory || it.mkdirs() })
                    val database = JarvysChatDatabaseProvider.getInstance(app)
                    runBlocking {
                        ChatDatabaseBridge.requireSuccessSync(
                            ConversationRepository(database).ensureConversation(WorkspaceIdentity.CENTRAL_SESSION, "central"),
                            "Create Central")
                    }
                    WorkspaceRegistry(app).use { it.openForBootstrap() }
                    prepared += key
                }
            }
        } finally { preparing.remove() }
    }

    /** Presence only: never open old SQLite, parse markers, enumerate old trees or delete anything. */
    @JvmStatic fun requireFreshInstall(context: Context) {
        requireFreshFiles(context.applicationContext.filesDir)
        listOf("jarvys-workspaces-v1.db", "jarvys-workspaces-v2.db").forEach { name ->
            rejectDatabaseGroup(context.applicationContext.getDatabasePath(name))
        }
    }

    @JvmStatic fun requireFreshFiles(filesDir: File) {
        val root = File(filesDir, "jarvys")
        listOf("g3", "single_session_reset.json", "single_session_reset.json.bak",
            "single_session_reset.json.new", "attachments", "delivered", "generated", "crew-context",
            "workspaces", "workspace-inspections", "coding-projects", "coding-project-state",
            "runs", "notes", "memory", "index", "download_results").forEach {
            if (present(File(root, it))) throw CleanInstallRequiredException()
        }
        listOf("jarvys-chat.db", "jarvys-chat-v3.db").forEach { rejectDatabaseGroup(File(root, it)) }
    }

    private fun rejectDatabaseGroup(database: File) {
        listOf("", "-wal", "-shm", "-journal").forEach {
            if (present(File(database.path + it))) throw CleanInstallRequiredException()
        }
    }

    private fun present(file: File): Boolean = try {
        Os.lstat(file.absolutePath)
        true
    } catch (missing: ErrnoException) {
        if (missing.errno == OsConstants.ENOENT) false else throw missing
    }
}
