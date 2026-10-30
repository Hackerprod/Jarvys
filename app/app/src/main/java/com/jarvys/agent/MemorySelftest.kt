package com.jarvys.agent

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import com.jarvys.agent.chatdb.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Synthetic local diagnostics, never a test/repair of the user's retained memory. */
data class MemorySelftestOptions(val suite: String = "all", val quick: Boolean = false) {
    internal fun normalized(): MemorySelftestOptions {
        val selected = MemoryObservationContent.trimSpace(suite).lowercase(java.util.Locale.ROOT)
        require(selected in setOf("all", "reliability", "performance")) { "memory_selftest_suite_invalid" }
        return copy(suite = selected)
    }
}
data class MemorySelftestScenario(val name: String, val suite: String, val passed: Boolean,
    val durationMs: Long, val error: String? = null, val metrics: Map<String, Double> = emptyMap())
data class MemorySelftestReport(val schemaVersion: String = "engram-self-test/v1", val suite: String,
    val quick: Boolean, val passed: Boolean, val durationMs: Long, val scenarios: List<MemorySelftestScenario>)
data class MemorySelftestAdapter(val platform: String = "android-room", val corpusProject: String = "jarvys-central",
    val sessionMode: String = "shared-manual", val userStoreChecked: Boolean = false)
data class MemorySelftestResult(val report: MemorySelftestReport, val executionState: String,
    val cleanupState: String, val error: String? = null, val adapter: MemorySelftestAdapter = MemorySelftestAdapter())
internal enum class MemorySelftestBoundary { ROOT_CREATED, BEFORE_CLEANUP }

internal data class MemorySelftestCompletion(val result: MemorySelftestResult, val physicallyUnwound: Boolean)

internal object MemorySelftestRunner {
    private fun elapsed(start: Long) = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(0)
    private fun safeError(failure: Throwable): String = when (failure) {
        is CancellationException -> "cancelled"
        is android.database.SQLException -> "database_error"
        is java.io.IOException -> "filesystem_error"
        else -> "scenario_error"
    }

    fun run(context: Context, options: MemorySelftestOptions, token: CancellationToken,
        observer: ((MemorySelftestBoundary, File) -> Unit)? = null): MemorySelftestCompletion {
        check(Looper.myLooper() != Looper.getMainLooper()) { "memory_selftest_worker_required" }
        val started = System.nanoTime()
        val scenarios = ArrayList<MemorySelftestScenario>()
        var root: OwnedRoot? = null
        var physical = true
        var cleanup = "cleaned"
        var failureCode: String? = null
        var cancelled = false
        try {
            token.throwIfCancelled()
            root = OwnedRoot.create(context)
            val owned = root
            observer?.invoke(MemorySelftestBoundary.ROOT_CREATED, owned.path())
            fun scenario(name: String, suite: String, block: (JarvysChatDatabase, PersonalAgentRepository) -> Map<String, Double>) {
                token.throwIfCancelled()
                val start = System.nanoTime()
                var owner: JarvysChatDatabaseProvider.ScratchDatabase? = null
                var problem: Throwable? = null
                var metrics: Map<String, Double> = emptyMap()
                try {
                    val directory = owned.child(name)
                    val scratch = ScratchContext(context, directory)
                    owner = JarvysChatDatabaseProvider.ScratchDatabase(scratch, File(directory, "agent.db"))
                    val database = owner.open()
                    token.throwIfCancelled()
                    metrics = block(database, PersonalAgentRepository(database))
                    token.throwIfCancelled()
                } catch (caught: Throwable) {
                    problem = caught
                } finally {
                    try { owner?.closeAndAwait() } catch (_: Throwable) {
                        physical = false
                        cleanup = "pending"
                        failureCode = "resource_close_unconfirmed"
                    }
                }
                if (problem is CancellationException || token.isCancellationRequested) {
                    cancelled = true
                    scenarios += MemorySelftestScenario(name, suite, false, elapsed(start), "cancelled")
                    throw CancellationException("memory_selftest_cancelled")
                }
                scenarios += MemorySelftestScenario(name, suite, problem == null && physical, elapsed(start),
                    problem?.let(::safeError) ?: if (!physical) "resource_close_unconfirmed" else null,
                    if (problem == null && physical) Collections.unmodifiableMap(LinkedHashMap(metrics)) else emptyMap())
                if (!physical) throw UnconfirmedClose()
            }
            if (options.suite == "all" || options.suite == "reliability") {
                scenario("database_save_search_context", "reliability") { db, repo ->
                    save(db, repo, token, "Self-test database migration",
                        "Self-test verifies local database initialization, save, search, and context.", "decision")
                    val results = read(db, token) { repo.search("database migration", 5, MemorySearchFilter(), MemoryNamespace.centralRead()) }
                    check(results.firstOrNull()?.title == "Self-test database migration")
                    val contextText = read(db, token) { repo.canonicalContext(MemoryNamespace.centralRead()) }
                    check(contextText.contains("Self-test database migration"))
                    emptyMap()
                }
                scenario("concurrent_local_writes", "reliability") { db, repo ->
                    val workers = if (options.quick) 4 else 8
                    val writes = if (options.quick) 10 else 50
                    concurrent(workers) { worker ->
                        repeat(writes) { index ->
                            save(db, repo, token, "Concurrent local write $worker-$index",
                                "Self-test concurrent local write $worker-$index", "note")
                        }
                    }
                    val stats = read(db, token) { repo.memoryStats(MemoryNamespace.centralRead()) }
                    check(stats.totalObservations == workers.toLong() * writes)
                    emptyMap()
                }
            }
            if (options.suite == "all" || options.suite == "performance") {
                scenario("store_search", "performance") { db, repo ->
                    val records = if (options.quick) 100 else 1000
                    val searches = if (options.quick) 20 else 200
                    repeat(records) { index ->
                        val number = String.format(java.util.Locale.ROOT, "%04d", index)
                        save(db, repo, token, "Self-test search record $number",
                            "Self-test search corpus shared keyword record $number", "note")
                    }
                    val searchStart = System.nanoTime()
                    repeat(searches) {
                        val results = read(db, token) { repo.search("shared keyword", 20, MemorySearchFilter(), MemoryNamespace.centralRead()) }
                        check(results.isNotEmpty())
                    }
                    val nanos = (System.nanoTime() - searchStart).coerceAtLeast(0)
                    linkedMapOf("operations" to searches.toDouble(), "throughput_ops_per_second" to
                        if (nanos == 0L) 0.0 else searches.toDouble() * 1_000_000_000.0 / nanos.toDouble())
                }
            }
        } catch (_: CancellationException) {
            cancelled = true
        } catch (failure: Throwable) {
            if (failure is UnconfirmedRoot) { physical = false; cleanup = "pending" }
            if (failureCode == null) failureCode = safeError(failure)
            if (scenarios.isEmpty()) scenarios += MemorySelftestScenario("setup", options.suite, false, elapsed(started), failureCode)
        } finally {
            if (physical) {
                try {
                    root?.let { owned ->
                        observer?.invoke(MemorySelftestBoundary.BEFORE_CLEANUP, owned.path())
                        owned.remove()
                    }
                } catch (_: Throwable) { cleanup = "failed"; failureCode = "cleanup_failed" }
            } else cleanup = "pending"
        }
        cancelled = cancelled || token.isCancellationRequested
        val passed = !cancelled && physical && cleanup == "cleaned" && failureCode == null && scenarios.isNotEmpty() && scenarios.all { it.passed }
        val state = if (cancelled) "cancelled" else if (passed) "completed" else "failed"
        return MemorySelftestCompletion(MemorySelftestResult(
            MemorySelftestReport(suite = options.suite, quick = options.quick, passed = passed,
                durationMs = elapsed(started), scenarios = Collections.unmodifiableList(ArrayList(scenarios))),
            state, cleanup, failureCode), physical)
    }

    private class UnconfirmedClose : IllegalStateException()
    private class UnconfirmedRoot(cause: Throwable) : IllegalStateException(cause)
    private fun save(db: JarvysChatDatabase, repo: PersonalAgentRepository, token: CancellationToken,
        title: String, content: String, type: String) = runBlocking {
        db.withChatWriteTransaction {
            token.throwIfCancelled()
            repo.saveMemory(content, "curated", null, .5, null, MemorySaveOptions(title = title, type = type), MemoryNamespace.central())
            token.throwIfCancelled()
        }
    }
    private fun <T> read(db: JarvysChatDatabase, token: CancellationToken, operation: suspend () -> T): T = runBlocking {
        JarvysChatDatabaseProvider.onQueryExecutor(db) { db.withChatReadTransaction {
            token.throwIfCancelled(); operation().also { token.throwIfCancelled() }
        } }
    }
    private fun concurrent(workers: Int, block: (Int) -> Unit) {
        val executor = Executors.newFixedThreadPool(workers)
        val tasks = ArrayList<Future<*>>()
        var failure: Throwable? = null
        var interrupted = false
        try {
            repeat(workers) { worker -> tasks += executor.submit { block(worker) } }
        } catch (caught: Throwable) { failure = caught }
        finally {
            // Join every submitted worker, even after one fails. Never equate cancel() with termination.
            tasks.forEach { task ->
                var done = false
                while (!done) try { task.get(); done = true }
                catch (_: InterruptedException) { interrupted = true }
                catch (caught: java.util.concurrent.ExecutionException) { if (failure == null) failure = caught.cause ?: caught; done = true }
            }
            executor.shutdown()
            while (!executor.isTerminated) try { executor.awaitTermination(100, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { interrupted = true }
            if (interrupted) Thread.currentThread().interrupt()
        }
        failure?.let { throw it }
    }

    /** Context identity and every auxiliary file remain within this operation-owned directory. */
    private class ScratchContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getCacheDir(): File = root
        override fun getDatabasePath(name: String): File {
            require(name == "agent.db" || File(name).absoluteFile == File(root, "agent.db").absoluteFile) { "memory_selftest_path_invalid" }
            return File(root, "agent.db")
        }
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
            throw IllegalStateException("memory_selftest_preferences_forbidden")
    }
    private class OwnedRoot(private val file: File, private val device: Long, private val inode: Long) {
        private fun verify() {
            val stat = Os.lstat(file.absolutePath)
            check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_dev == device && stat.st_ino == inode && file.canonicalFile == file.absoluteFile) { "memory_selftest_root_changed" }
        }
        fun path(): File = file
        fun child(name: String): File {
            verify()
            require(name in setOf("database_save_search_context", "concurrent_local_writes", "store_search"))
            return Files.createDirectory(File(file, name).toPath()).toFile()
        }
        fun remove() {
            verify()
            fun removeOwned(entry: File) {
                val stat = Os.lstat(entry.absolutePath)
                check(entry.canonicalFile == entry.absoluteFile) { "memory_selftest_symlink" }
                when {
                    OsConstants.S_ISDIR(stat.st_mode) -> {
                        val children = checkNotNull(entry.listFiles()) { "memory_selftest_cleanup_list" }
                        children.forEach(::removeOwned)
                    }
                    else -> check(OsConstants.S_ISREG(stat.st_mode)) { "memory_selftest_cleanup_type" }
                }
                check(entry.delete()) { "memory_selftest_cleanup_delete" }
            }
            removeOwned(file)
        }
        companion object {
            fun create(context: Context): OwnedRoot {
                val cache = context.cacheDir.absoluteFile
                check(cache.canonicalFile == cache && OsConstants.S_ISDIR(Os.lstat(cache.path).st_mode)) { "memory_selftest_cache_invalid" }
                val root = Files.createTempDirectory(cache.toPath(), "memory-selftest-").toFile().absoluteFile
                return try {
                    val stat = Os.lstat(root.path)
                    OwnedRoot(root, stat.st_dev, stat.st_ino)
                } catch (failure: Throwable) {
                    // A directory was created, but ownership cannot be proven. Do not claim cleanup.
                    throw UnconfirmedRoot(failure)
                }
            }
        }
    }
}
