package com.jarvys.agent

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.AtomicFile
import androidx.work.WorkManager
import com.jarvys.agent.chatdb.AppendMessageInput
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ConversationRepository
import com.jarvys.agent.chatdb.JarvysChatDatabaseProvider
import com.jarvys.agent.chatdb.MessagePartInput
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Recoverable generation switch. Old data is retired only after a successful Central run. */
object SingleSessionReset {
    const val GENERATION_EXTRA = "storage_generation"
    private val lock = Any()
    private val retirementLock = Any()
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
    private val phases = listOf("created", "registry_created", "invalidated", "done")
    internal val oldTrees = listOf("attachments", "delivered", "generated", "crew-context", "workspaces",
        "workspace-inspections", "coding-projects", "coding-project-state", "runs", "notes", "memory", "index", "download_results")

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
        executor.execute {
            try {
                prepareOnWorker(app)
                completion.complete(Unit)
                resumeRetirement(app)
            } catch (failure: Exception) {
                completion.completeExceptionally(failure)
                AgentErrorReporter.reportBackgroundFailure(app, failure)
            }
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
        try { prepare(context) {} } finally { preparing.remove() }
    }

    internal fun prepare(context: Context, afterPhase: (String) -> Unit) = synchronized(lock) {
        val app = context.applicationContext
        val key = app.filesDir.canonicalPath
        if (key in prepared) return@synchronized
        resume(app.filesDir, afterPhase) { phase ->
            when (phase) {
                "created" -> {
                    check(StorageGeneration(app).chatRoot().let { it.isDirectory || it.mkdirs() })
                    val database = JarvysChatDatabaseProvider.getInstance(app)
                    runBlocking {
                        ChatDatabaseBridge.requireSuccessSync(
                            ConversationRepository(database).ensureConversation(WorkspaceIdentity.CENTRAL_SESSION, "central"),
                            "Create Central")
                    }
                }
                "registry_created" -> WorkspaceRegistry(app).use { it.openForReset() }
                "invalidated" -> invalidate(app)
            }
        }
        prepared += key
    }

    @JvmStatic fun takeStartupNotice(context: Context): Boolean = synchronized(lock) {
        val state = readState(context.filesDir)
        if (!state.optBoolean("had_old_data") || state.optBoolean("notice_shown")) return@synchronized false
        state.put("notice_shown", true)
        writeState(context.filesDir, state)
        true
    }

    internal fun resume(filesDir: File, afterPhase: (String) -> Unit = {}, action: (String) -> Unit) {
        val state = readState(filesDir)
        if (state.optString("phase") == "retired") return
        if (!state.has("had_old_data")) state.put("had_old_data", File(filesDir, "jarvys/jarvys-chat.db").exists())
        var completed = phases.indexOf(state.optString("phase"))
        for ((index, phase) in phases.withIndex()) {
            if (index <= completed) continue
            action(phase)
            state.put("phase", phase)
            writeState(filesDir, state)
            completed = index
            afterPhase(phase)
        }
    }

    private fun invalidate(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.forEach { posted ->
            val notification = posted.notification
            // A foreground service can be started by Android while storage is being prepared.
            if (notification.extras.getString(GENERATION_EXTRA) == StorageGeneration.NAME) return@forEach
            notification.contentIntent?.cancel()
            notification.deleteIntent?.cancel()
            notification.fullScreenIntent?.cancel()
            notification.actions?.forEach { it.actionIntent?.cancel() }
            manager.cancel(posted.tag, posted.id)
        }
        PersonalMemoryExtractionWorker.cancelAll(context).result.get()
        WorkManager.getInstance(context).cancelUniqueWork("jarvys_personal_memory_daily_consolidation").result.get()
        val preferences = context.getSharedPreferences("jarvys_chat", Context.MODE_PRIVATE)
        val editor = preferences.edit().remove("active_session_id")
        preferences.all.keys.filter { it.startsWith("chat_without_memory_") }.forEach { editor.remove(it) }
        check(editor.commit()) { "Could not invalidate conversation preferences" }
    }

    @JvmStatic fun onSuccessfulRun(context: Context, sessionId: String) {
        if (sessionId != WorkspaceIdentity.CENTRAL_SESSION) return
        ensure(context)
        synchronized(lock) {
            val state = readState(context.filesDir)
            if (state.optString("phase") != "done") return
            state.put("successful_run", true)
            writeState(context.filesDir, state)
        }
        retire(context)
    }

    @JvmStatic fun resumeRetirement(context: Context) {
        val pending = synchronized(lock) {
            val state = readState(context.filesDir)
            state.optString("phase") == "done" && state.optBoolean("successful_run")
        }
        if (pending) retire(context)
    }

    private fun retire(context: Context) = synchronized(retirementLock) {
        if (readState(context.filesDir).optString("phase") == "retired") return@synchronized
        val bridge = ChatDatabaseBridge.getInstance(context)
        ChatDatabaseBridge.requireSuccessSync(bridge.appendMessageSync(AppendMessageInput(
            WorkspaceIdentity.CENTRAL_SESSION, "assistant",
            listOf(MessagePartInput("text", context.getString(R.string.single_session_retired))),
            conversationKind = "central", stableId = "single-session-retired-g3")), "Record data retirement")
        retireFiles(context.filesDir, context.getDatabasePath("jarvys-workspaces-v1.db"))
        synchronized(lock) {
            val state = readState(context.filesDir).put("phase", "retired")
            writeState(context.filesDir, state)
        }
    }

    internal fun retireFiles(filesDir: File, oldRegistry: File) {
        val jarvys = File(filesDir.canonicalFile, "jarvys")
        oldTrees.forEach { removeTree(File(jarvys, it)) }
        listOf("", "-wal", "-shm", "-journal").forEach {
            removeTree(File(jarvys, "jarvys-chat.db$it"))
            removeTree(File(oldRegistry.path + it))
        }
    }

    private fun removeTree(file: File) {
        if (file.canonicalFile == file.absoluteFile && file.isDirectory) {
            checkNotNull(file.listFiles()) { "Could not enumerate old storage" }.forEach(::removeTree)
        }
        check(!file.exists() || file.delete()) { "Could not retire old storage" }
    }

    internal fun readState(filesDir: File): JSONObject {
        val file = AtomicFile(File(filesDir, "jarvys/single_session_reset.json"))
        return try {
            JSONObject(file.openRead().bufferedReader().use { it.readText() })
        } catch (missing: java.io.FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw missing
            JSONObject()
        }
    }

    private fun writeState(filesDir: File, state: JSONObject) {
        val file = AtomicFile(File(filesDir, "jarvys/single_session_reset.json"))
        check(file.baseFile.parentFile!!.let { it.isDirectory || it.mkdirs() })
        val output = file.startWrite()
        try {
            output.write(state.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }
}
