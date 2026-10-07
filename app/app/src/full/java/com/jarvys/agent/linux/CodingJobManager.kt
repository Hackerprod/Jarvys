package com.jarvys.agent.linux

import android.os.Build
import android.system.Os
import android.system.OsConstants
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CodingJobConversationGuard
import com.jarvys.agent.coding.ProjectScope
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Durable, owner-scoped jobs. Restoring a record never launches or signals a process. */
class CodingJobManager(
    filesDir: File,
    private val backend: Backend,
    private val redactor: (String) -> String = { it },
    private val executor: Executor = Executor { work -> Thread(work, "jarvys-project-job").apply { isDaemon = true; start() } },
    private val redactionBufferChars: Int = 65_536,
) {
    fun interface Backend {
        fun execute(scope: ProjectScope, command: String, cwd: String, timeoutMillis: Long?,
                    token: CancellationToken, callback: LinuxOutputCallback, beforeLaunch: () -> Unit): LinuxExecResult
    }
    enum class State(val terminal: Boolean) {
        LAUNCH_INTENT(false), RUNNING(false), CANCELLING(false), SUCCEEDED(true), FAILED(true),
        NOT_STARTED(true), CANCELLED(true), TIMED_OUT(true), INTERRUPTED(true), UNCERTAIN(true)
    }
    data class Snapshot(val id: String, val owner: String, val scopeId: String, val cwd: String,
        val redactedCommand: String, val state: State, val createdAt: Long,
        val startedAt: Long? = null, val finishedAt: Long? = null, val exitCode: Int? = null,
        val logArtifact: String = "coding-job:$id/log", val error: String? = null,
        val logBytes: Long = 0L, val logComplete: Boolean = false)
    data class RecoverySummary(val id: String, val state: State, val exitCode: Int? = null)
    data class RecoveryEvidence(val id: String, val state: State, val exitCode: Int?, val logArtifact: String,
        val logBytes: Long, val logComplete: Boolean, val legacyIdentity: Boolean, val detail: String?)
    data class LogPage(val snapshot: Snapshot, val text: String, val offset: Long, val nextOffset: Long, val hasMore: Boolean)
    private data class RecoveredAccess(val scopeId: String, val projectRoot: String, val durableIdentity: String, val owners: Set<String>)
    private class Job(var snapshot: Snapshot, val projectRoot: String, val conversationId: String, val durableIdentity: String?) {
        val lock = Any()
        val finished = CountDownLatch(if (snapshot.state.terminal) 0 else 1)
        val token = CancellationToken.cancellable()
        var unregister = Runnable { }
        var launchAttempted = false
        var recovered = false
        var needsReconciliation = false
    }
    private val conversations = CodingJobConversationGuard(filesDir)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val recoveredAccess = ConcurrentHashMap<String, RecoveredAccess>()
    private val recoveryLock = Any()
    private val restoreProblems = mutableListOf<String>()
    private val restoreProblemScopes = mutableMapOf<String, Int>()
    private val root: File

    init {
        require(redactionBufferChars > 0) { "Pending-output resource budget must be positive" }
        val anchor = filesDir.canonicalFile
        require(anchor.isDirectory) { "App files directory is unavailable" }
        root = File(anchor, "jarvys/crew-context")
        verifyPath(root)
        require(!root.exists() || root.isDirectory) { "Coding job storage is not a directory" }
        restore()
    }

    fun start(scope: ProjectScope, owner: String, command: String, cwd: String, expectedVersion: Long,
              timeoutMillis: Long?, token: CancellationToken, preflight: () -> Unit = { },
              completion: (Snapshot) -> Unit = { }): Snapshot {
        require(owner.isNotBlank()) { "A mission owner is required" }
        require(command.isNotBlank() && '\u0000' !in command) { "A nonempty command without NUL is required" }
        require(timeoutMillis == null || timeoutMillis > 0) { "Timeout must be positive" }
        token.throwIfCancelled()
        active(scope) { }
        val relative = scope.normalizePath(cwd)
        validateCwd(scope, relative)
        val project = scope.rootDirectory().canonicalPath
        check(root.path != project && !root.path.startsWith(project + File.separator)) { "Coding job journals must be outside the editable project" }
        reconcile(scope)
        val identity = scope.durableIdentity()
        val lease = scope.acquireWriter(owner, expectedVersion)
        val id = UUID.randomUUID().toString()
        val job = Job(Snapshot(id, owner, scope.id(), relative, sanitize(command), State.LAUNCH_INTENT,
            System.currentTimeMillis()), project, scope.conversationId(), identity)
        try {
            synchronized(job.lock) { persist(job) }
            jobs[id] = job
            job.unregister = token.registerCancelAction { requestCancel(job) }
            executor.execute { run(job, scope, command, timeoutMillis, lease, preflight, completion) }
            return snapshot(job)
        } catch (failure: Exception) {
            if (jobs[id] === job) finish(job, lease, State.NOT_STARTED, null, "Job worker could not start", completion, false)
            else lease.close()
            throw failure
        }
    }

    fun list(owner: String, scope: ProjectScope): List<Snapshot> {
        active(scope) { }
        reconcile(scope)
        val result = jobs.values.filter { matchesScope(it, scope) && canRead(owner, scope, it) }
            .map(::snapshot).sortedWith(compareBy<Snapshot> { it.createdAt }.thenBy { it.id })
        return active(scope) { result }
    }

    fun bindRecoveredOwners(owner: String, scope: ProjectScope, previousOwners: Collection<String>) {
        active(scope) { }
        val bot = ownerBot(owner, scope.conversationId())
        val allowed = previousOwners.filter { it != owner }.toSet()
        require(allowed.all { ownerBot(it, scope.conversationId()) == bot }) { "Recovered jobs must belong to the same conversation and bot" }
        val identity = scope.durableIdentity()
        check(jobs.values.none { it.snapshot.owner in allowed && matchesScope(it, scope) && !snapshot(it).state.terminal }) {
            "Previous jobs are still pending; stop and await the original owner before resuming"
        }
        recoveredAccess[owner] = RecoveredAccess(scope.id(), scope.rootDirectory().canonicalPath, identity, allowed)
    }

    fun checkpointEvidence(scope: ProjectScope, previousOwners: Collection<String>): List<RecoveryEvidence> {
        active(scope) { }
        previousOwners.forEach { ownerBot(it, scope.conversationId()) }
        val allowed = previousOwners.toSet()
        val result = jobs.values.filter { it.snapshot.owner in allowed && matchesScope(it, scope) }
            .sortedWith(compareBy<Job> { it.snapshot.createdAt }.thenBy { it.snapshot.id }).map { job ->
                val s = snapshot(job)
                RecoveryEvidence(s.id, s.state, s.exitCode, s.logArtifact, s.logBytes, s.logComplete,
                    job.durableIdentity == null, s.error)
            }
        return active(scope) { result }
    }

    fun hasPending(owner: String): Boolean = jobs.values.any { snapshot(it).let { s -> s.owner == owner && !s.state.terminal } }
    fun recoveryIssues(): List<String> = synchronized(recoveryLock) { restoreProblems.toList() }
    fun recoverySummaries(scope: ProjectScope): List<RecoverySummary> {
        active(scope) { }
        reconcile(scope)
        return active(scope) { jobs.values.filter { matchesScope(it, scope) && snapshot(it).state in setOf(State.UNCERTAIN, State.INTERRUPTED) }
            .map(::snapshot).sortedWith(compareBy<Snapshot> { it.createdAt }.thenBy { it.id })
            .map { RecoverySummary(it.id, it.state) } }
    }
    fun recoveryIssueCount(scope: ProjectScope): Int = active(scope) {
        val mismatched = jobs.values.count { it.conversationId == scope.conversationId() && !matchesScope(it, scope) }
        (restoreProblemScopes[hash(scope.conversationId())] ?: 0) + mismatched
    }

    fun read(owner: String, scope: ProjectScope, id: String, offset: Long, budget: Int): LogPage {
        require(offset >= 0 && budget >= 4) { "Nonnegative offset and at least four output bytes are required" }
        val job = owned(owner, scope, id)
        return synchronized(job.lock) { active(scope) {
            val file = logFile(job)
            val size = if (file.exists()) file.length() else 0L
            require(offset <= size) { "Log offset is beyond the available output" }
            if (offset == size) return@active LogPage(snapshot(job), "", offset, offset, false)
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                require(input.read() and 0xc0 != 0x80) { "Log offset must be on a UTF-8 boundary" }
                input.seek(offset)
                val bytes = ByteArray(minOf(budget.toLong(), 1_048_576L, size - offset).toInt())
                input.readFully(bytes)
                var count = bytes.size
                if (offset + count < size) {
                    var start = count - 1
                    while (start > 0 && bytes[start].toInt() and 0xc0 == 0x80) start--
                    val first = bytes[start].toInt() and 0xff
                    val width = when { first < 128 -> 1; first < 224 -> 2; first < 240 -> 3; else -> 4 }
                    if (count - start < width) count = start
                }
                LogPage(snapshot(job), String(bytes, 0, count, Charsets.UTF_8), offset, offset + count, offset + count < size)
            }
        } }
    }

    fun await(owner: String, scope: ProjectScope, id: String, waitMillis: Long, token: CancellationToken): Snapshot {
        require(waitMillis >= 0) { "Wait must be nonnegative" }
        val job = owned(owner, scope, id)
        val started = System.nanoTime()
        while (!snapshot(job).state.terminal) {
            token.throwIfCancelled()
            val remaining = waitMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            if (remaining <= 0) break
            try { job.finished.await(minOf(remaining, 100), TimeUnit.MILLISECONDS) }
            catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); token.throwIfCancelled(); throw interrupted }
        }
        token.throwIfCancelled()
        return active(scope) { snapshot(job) }
    }

    fun cancel(owner: String, scope: ProjectScope, id: String): Snapshot {
        val job = owned(owner, scope, id, allowRecoveredAccess = false)
        // A restored journal is evidence, never a process handle.
        if (!job.recovered) requestCancel(job)
        return active(scope) { snapshot(job) }
    }

    fun cancelAllAndAwait(owner: String) {
        val owned = jobs.values.filter { it.snapshot.owner == owner && !it.recovered }
        owned.forEach { requestCancel(it) }
        var interrupted = false
        try { owned.forEach { job -> while (true) {
            try { job.finished.await(); break } catch (_: InterruptedException) { interrupted = true }
        } } } finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    private fun run(job: Job, scope: ProjectScope, command: String, timeout: Long?, lease: ProjectScope.WriterLease,
                    preflight: () -> Unit, completion: (Snapshot) -> Unit) {
        val output = CodingJobLog(::sanitize, redactionBufferChars) { append(job, it) }
        var state = State.NOT_STARTED
        var exit: Int? = null
        var error: String? = null
        var complete = true
        try {
            job.token.throwIfCancelled(); validateCwd(scope, job.snapshot.cwd); lease.validate(); preflight()
            job.token.throwIfCancelled()
            val result = backend.execute(scope, command, job.snapshot.cwd, timeout, job.token,
                LinuxOutputCallback { stream, text -> output.accept(stream, text) }) {
                preflight(); validateCwd(scope, job.snapshot.cwd); lease.validate()
                synchronized(job.lock) {
                    job.token.throwIfCancelled()
                    check(!job.launchAttempted) { "A coding job cannot launch twice" }
                    val previous = job.snapshot
                    job.snapshot = previous.copy(state = State.RUNNING, startedAt = System.currentTimeMillis())
                    try {
                        // This fsynced intent precedes ProcessBuilder.start, not merely dispatch to a worker.
                        persist(job)
                        job.launchAttempted = true
                        preflight(); validateCwd(scope, job.snapshot.cwd); lease.validate(); job.token.throwIfCancelled()
                    } catch (failure: Exception) { job.snapshot = previous; throw failure }
                }
            }
            exit = result.exitCode
            state = when {
                job.token.isCancellationRequested || result.cancelled -> State.CANCELLED
                result.timedOut -> State.TIMED_OUT
                !job.launchAttempted -> State.NOT_STARTED
                result.exitCode == 0 -> State.SUCCEEDED
                else -> State.FAILED
            }
            if (!job.launchAttempted) { exit = null; error = "Backend did not confirm a launch" }
        } catch (_: CancellationException) { state = State.CANCELLED }
        catch (_: Exception) {
            state = if (job.launchAttempted) State.UNCERTAIN else State.NOT_STARTED
            if (job.launchAttempted) complete = false
            error = if (job.launchAttempted) "Execution result is unknown; inspect project and log before any retry"
                    else "Command was not launched; environment, policy, or project validation failed"
        } finally {
            try { output.finish() } catch (_: Exception) {
                if (job.launchAttempted && state != State.CANCELLED) state = State.UNCERTAIN
                error = "Output could not be fully persisted; inspect the project before any retry"; complete = false
            }
            finish(job, lease, state, exit, error, completion, complete)
        }
    }

    private fun finish(job: Job, lease: ProjectScope.WriterLease, result: State, exit: Int?, error: String?,
                       completion: (Snapshot) -> Unit, logComplete: Boolean) {
        var terminal: Snapshot? = null
        try { synchronized(job.lock) {
            if (job.snapshot.state.terminal) return
            var state = if (job.token.isCancellationRequested) State.CANCELLED else result
            var detail = error
            if (job.launchAttempted) try { lease.markChanged() } catch (_: Exception) {
                state = State.UNCERTAIN; detail = "Project version could not be reconciled after execution"
            }
            job.snapshot = job.snapshot.copy(state = state, finishedAt = System.currentTimeMillis(), exitCode = exit,
                error = detail, logComplete = logComplete)
            try { persist(job) } catch (_: Exception) {
                job.snapshot = job.snapshot.copy(state = State.UNCERTAIN, exitCode = null, logComplete = false,
                    error = "Terminal result could not be durably recorded; do not automatically repeat the command")
            }
            terminal = job.snapshot
        } } finally {
            lease.close(); job.unregister.run(); job.unregister = Runnable { }; job.finished.countDown()
        }
        terminal?.let { runCatching { completion(it) } }
    }

    private fun requestCancel(job: Job) {
        synchronized(job.lock) {
            if (job.snapshot.state.terminal || job.recovered) return
            job.snapshot = job.snapshot.copy(state = State.CANCELLING)
            runCatching { persist(job) }
            job.token.cancel()
        }
    }

    private fun append(job: Job, text: String) = synchronized(job.lock) {
        conversations.active(job.conversationId) {
            val file = logFile(job)
            FileOutputStream(file, true).use { output -> output.write(text.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            syncDirectory(file.parentFile!!)
            job.snapshot = job.snapshot.copy(logBytes = file.length())
        }
        Unit
    }
    internal fun sanitize(text: String): String = redactor(CodingJobRedactor.redact(text))
    private fun snapshot(job: Job) = synchronized(job.lock) { job.snapshot }
    private fun ownerBot(owner: String, conversation: String): String {
        require(owner.startsWith("$conversation/")) { "Invalid checkpoint job owner" }
        val pieces = owner.removePrefix("$conversation/").split('/')
        require(pieces.size == 2 && pieces[0].isNotBlank() && pieces[1].matches(Regex("-?[0-9]+")) && pieces[1].toLongOrNull() != null) { "Invalid checkpoint job owner" }
        return pieces[0]
    }
    private fun matchesScope(job: Job, scope: ProjectScope): Boolean = job.conversationId == scope.conversationId() &&
        job.snapshot.scopeId == scope.id() && job.projectRoot == scope.rootDirectory().canonicalPath &&
        (job.durableIdentity == null || job.durableIdentity == scope.durableIdentity())
    private fun canRead(owner: String, scope: ProjectScope, job: Job): Boolean {
        if (job.snapshot.owner == owner) return true
        val access = recoveredAccess[owner] ?: return false
        return job.snapshot.owner in access.owners && snapshot(job).state.terminal && access.scopeId == scope.id() &&
            access.projectRoot == job.projectRoot && job.durableIdentity != null &&
            access.durableIdentity == job.durableIdentity && access.durableIdentity == scope.durableIdentity()
    }
    private fun owned(owner: String, scope: ProjectScope, id: String, allowRecoveredAccess: Boolean = true): Job {
        active(scope) { }; reconcile(scope)
        val job = jobs[id] ?: throw IOException("Coding job not found for this mission")
        if (!matchesScope(job, scope) || !(if (allowRecoveredAccess) canRead(owner, scope, job) else snapshot(job).owner == owner))
            throw IOException("Coding job not found for this mission")
        return job
    }
    private fun validateCwd(scope: ProjectScope, cwd: String) {
        scope.require(ProjectScope.Capability.WRITE)
        check(scope.resolve(cwd).isDirectory) { "Project cwd is not an ordinary directory" }
        scope.validate()
    }
    private fun reconcile(scope: ProjectScope) = synchronized(recoveryLock) {
        val affected = jobs.values.filter { it.needsReconciliation && matchesScope(it, scope) }
        if (affected.isNotEmpty()) {
            scope.acquireWriter("coding-recovery:${scope.id()}", scope.version()).use { it.validate(); it.markChanged() }
            affected.forEach { it.needsReconciliation = false }
        }
    }
    private fun <T> active(scope: ProjectScope, action: () -> T): T {
        scope.validate()
        return conversations.active(scope.conversationId()) { action() }
    }
    private fun hash(value: String) = ProjectScope.sha256(value.toByteArray(Charsets.UTF_8))
    private fun directory(conversation: String, create: Boolean = false): File {
        var directory = root.parentFile!!
        verifyPath(directory)
        for (part in listOf("crew-context", hash(conversation), "coding-jobs")) {
            if (create && !directory.exists()) {
                check(directory.mkdir()) { "Cannot create private coding job storage" }; syncDirectory(directory.parentFile!!)
            }
            directory = File(directory, part)
            verifyPath(directory)
        }
        if (create && !directory.exists()) { check(directory.mkdir()) { "Could not create private coding job directory" }; syncDirectory(directory.parentFile!!) }
        verifyPath(directory)
        return directory
    }
    private fun logFile(job: Job) = File(directory(job.conversationId), "${job.snapshot.id}.log").also(::verifyPath)
    private fun persist(job: Job) = conversations.active(job.conversationId) {
        val destination = directory(job.conversationId, true)
        val s = job.snapshot
        val json = JSONObject().put("schema", if (job.durableIdentity == null) 1 else 2).put("id", s.id).put("owner", s.owner)
            .put("durableIdentity", job.durableIdentity ?: JSONObject.NULL).put("scopeId", s.scopeId)
            .put("conversationId", job.conversationId).put("projectRoot", job.projectRoot).put("cwd", s.cwd)
            .put("command", s.redactedCommand).put("state", s.state.name).put("createdAt", s.createdAt)
            .put("startedAt", s.startedAt ?: JSONObject.NULL).put("finishedAt", s.finishedAt ?: JSONObject.NULL)
            .put("exitCode", s.exitCode ?: JSONObject.NULL).put("error", s.error ?: JSONObject.NULL)
            .put("logBytes", s.logBytes).put("logComplete", s.logComplete)
        val target = File(destination, "${s.id}.json").also(::verifyPath)
        val staged = File(destination, "${s.id}.${UUID.randomUUID()}.tmp").also(::verifyPath)
        try {
            FileOutputStream(staged).use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
            verifyPath(target)
            check(staged.renameTo(target)) { "Could not commit coding job journal" }
            syncDirectory(destination)
        } finally { if (staged.exists()) staged.delete() }
    }
    private fun restore() {
        if (!root.exists()) return
        verifyPath(root)
        val conversationDirs = root.listFiles() ?: throw IOException("Cannot inspect coding job storage")
        for (conversationDir in conversationDirs) {
            if (!conversationDir.name.matches(Regex("[a-f0-9]{64}"))) continue
            val directory = File(conversationDir, "coding-jobs")
            if (!directory.exists()) continue
            try {
                verifyPath(directory)
                val entries = directory.listFiles() ?: throw IOException("Cannot inspect coding job journals")
                for (file in entries.filter { it.name.endsWith(".json") }) {
                    try { restoreRecord(file) }
                    catch (_: Exception) { recordProblem(conversationDir.name) }
                    catch (_: StackOverflowError) { recordProblem(conversationDir.name) }
                }
            } catch (_: Exception) { recordProblem(conversationDir.name) }
        }
    }
    private fun recordProblem(scopeHash: String) {
        restoreProblems += "A coding job journal could not be read; original evidence was preserved"
        restoreProblemScopes[scopeHash] = (restoreProblemScopes[scopeHash] ?: 0) + 1
    }
    private fun restoreRecord(file: File) {
        verifyPath(file)
        require(file.isFile && file.length() <= MAX_JOURNAL_BYTES) { "Job journal exceeds current memory headroom" }
        val bytes = file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break
                require(out.size() + count <= MAX_JOURNAL_BYTES) { "Job journal grew beyond current memory headroom" }; out.write(buffer, 0, count) }
            out.toByteArray()
        }
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val schema = json.getInt("schema"); require(schema == 1 || schema == 2)
        val identity = if (schema == 2) json.getString("durableIdentity").also { require(it.isNotBlank() && it != "null") } else null
        val id = json.getString("id"); require(UUID.fromString(id).toString() == id && file.name == "$id.json")
        val conversation = json.getString("conversationId")
        conversations.active(conversation) { }
        require(file.parentFile == directory(conversation))
        val previous = State.valueOf(json.getString("state"))
        val started = json.longOrNull("startedAt")
        val state = if (previous.terminal) previous else if (started == null) State.INTERRUPTED else State.UNCERTAIN
        val log = File(file.parentFile, "$id.log").also(::verifyPath)
        require(!log.exists() || log.isFile)
        val size = if (log.exists()) log.length() else 0L
        val expected = json.optLong("logBytes", 0); require(expected >= 0)
        val complete = schema == 2 && previous.terminal && json.optBoolean("logComplete", false) && size == expected
        val error = if (previous.terminal) json.optString("error").takeUnless { json.isNull("error") } else
            if (started == null) "Interrupted before launch; no command was replayed" else
                "Execution outcome and process liveness are unknown after restart; no command was replayed"
        val detail = listOfNotNull(error, if (!complete) "Redacted log evidence may be incomplete; retained bytes are available without replay" else null).joinToString("; ").ifEmpty { null }
        val s = Snapshot(id, json.getString("owner"), json.getString("scopeId"), json.getString("cwd"),
            sanitize(json.getString("command")), state, json.getLong("createdAt"), started,
            if (previous.terminal) json.longOrNull("finishedAt") else System.currentTimeMillis(),
            if (previous.terminal && !json.isNull("exitCode")) json.getInt("exitCode") else null,
            error = detail, logBytes = size, logComplete = complete)
        val job = Job(s, json.getString("projectRoot"), conversation, identity)
        job.recovered = true
        job.needsReconciliation = state == State.UNCERTAIN && started != null
        require(jobs.putIfAbsent(id, job) == null) { "Duplicate job identity" }
    }
    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key) || !has(key)) null else getLong(key)
    private fun verifyPath(file: File) {
        if (file.absoluteFile != file.canonicalFile) throw IOException("Coding job storage cannot use symlinks")
    }
    private fun syncDirectory(directory: File) {
        verifyPath(directory)
        if (Build.VERSION.SDK_INT >= 26 || System.getProperty("java.vm.name")?.contains("Dalvik") != true) {
            DirectorySync.sync(directory)
        } else {
            val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
        }
    }
    private object DirectorySync {
        fun sync(directory: File) = FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
    private companion object { const val MAX_JOURNAL_BYTES = 4 * 1024 * 1024 }
}

internal object CodingJobRedactor {
    private val privateKey = Regex("-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----.*?(?:-----END (?:[A-Z ]+ )?PRIVATE KEY-----|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val bearer = Regex("(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val assignment = Regex("(?i)((?:password|passwd|api[_-]?key|(?:access|refresh)[_-]?token|client[_-]?secret|token|secret)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s\\\"',;]+")
    private val apiToken = Regex("\\b(?:sk-[A-Za-z0-9_-]+|gh[pousr]_[A-Za-z0-9_]+)\\b")
    fun redact(text: String): String = apiToken.replace(assignment.replace(bearer.replace(privateKey.replace(text,
        "[REDACTED PRIVATE KEY]")) { it.groupValues[1] + "[REDACTED]" }) { it.groupValues[1] + "[REDACTED]" }, "[REDACTED]")
}

/** Buffers complete lines per stream so chunk boundaries cannot expose split credentials. */
internal class CodingJobLog(private val redact: (String) -> String, private val pendingBudget: Int,
                            private val sink: (String) -> Unit) {
    private enum class Prefix { NONE, VALUE, SEPARATOR }
    private class Stream {
        val pending = StringBuilder(); val marker = StringBuilder(); val context = StringBuilder()
        var count = 0L; var oversized = false; var privateBlock = false; var sawBegin = false; var sawEnd = false
        var carried = Prefix.NONE
    }
    private val streams = LinuxOutputStream.entries.associateWith { Stream() }
    init { require(pendingBudget > 0) }
    @Synchronized fun accept(kind: LinuxOutputStream, text: String) {
        val state = streams.getValue(kind)
        for (c in text) {
            state.count++
            if (c.isWhitespace()) { if (!state.context.endsWith(" ")) state.context.append(' ') } else state.context.append(c)
            while (state.context.length > 19) state.context.deleteCharAt(0)
            state.marker.append(c.uppercaseChar()); if (state.marker.length > 11) state.marker.deleteCharAt(0)
            if (state.marker.endsWith("-----BEGIN ")) state.sawBegin = true
            if (state.marker.endsWith("-----END ")) state.sawEnd = true
            if (!state.oversized) {
                if (state.pending.length < pendingBudget) state.pending.append(c)
                else { state.pending.setLength(0); state.oversized = true }
            }
            if (c == '\n') flush(kind, state, true)
        }
    }
    @Synchronized fun finish() { streams.forEach { (kind, state) -> if (state.count > 0) flush(kind, state, false) } }
    private fun flush(kind: LinuxOutputStream, state: Stream, newline: Boolean) {
        val ending = if (newline) "\n" else ""
        val text = when {
            state.privateBlock || state.sawBegin -> if (state.sawBegin) "[REDACTED PEM BLOCK]$ending" else ""
            state.oversized -> "[REDACTED LONG LINE: ${state.count} UTF-16 characters exceeded the $pendingBudget-character pending-output buffer]$ending"
            else -> redact(maskCarriedValue(state.pending.toString(), state.carried))
        }
        state.privateBlock = (state.privateBlock || state.sawBegin) && !state.sawEnd
        state.carried = when { VALUE_PREFIX.containsMatchIn(state.context) -> Prefix.VALUE
            LABEL_PREFIX.containsMatchIn(state.context) -> Prefix.SEPARATOR; else -> Prefix.NONE }
        state.pending.setLength(0); state.count = 0; state.oversized = false; state.sawBegin = false; state.sawEnd = false
        if (text.isNotEmpty()) sink("[${kind.name.lowercase(java.util.Locale.ROOT)}] $text")
    }
    private fun maskCarriedValue(line: String, prefix: Prefix): String {
        val start = when (prefix) { Prefix.NONE -> return line; Prefix.VALUE -> 0
            Prefix.SEPARATOR -> (LEADING_SEPARATOR.find(line)?.range?.last ?: return line) + 1 }
        val secret = LEADING_VALUE.find(line.substring(start))?.groups?.get(2) ?: return line
        return line.replaceRange(secret.range.first + start, secret.range.last + start + 1, "[REDACTED]")
    }
    private companion object {
        const val LABEL = "(?:password|passwd|api[_-]?key|(?:access|refresh)[_-]?token|client[_-]?secret|token|secret)"
        val VALUE_PREFIX = Regex("(?i)(?:\\bBearer\\s+|$LABEL[\\\"']?\\s*[:=]\\s*[\\\"']?\\s*)$")
        val LABEL_PREFIX = Regex("(?i)$LABEL[\\\"']?\\s*$")
        val LEADING_SEPARATOR = Regex("^[\\s\\\"']*[:=]")
        val LEADING_VALUE = Regex("^(\\s*[\\\"']*)([^\\s\\\"',;]+)")
    }
}
