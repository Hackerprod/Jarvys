package com.jarvys.agent.tasks

import android.content.Context
import android.os.Process
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

enum class TaskPendingActionStatus { PENDING, CLAIMED, WAITING_VISIBLE_APP, EXECUTED, REJECTED, STALE, INTERRUPTED_UNCERTAIN }

data class TaskPendingAction(
    val id: String,
    val taskId: String,
    val taskRevision: Long,
    val runId: String,
    val toolName: String,
    val argumentsJson: String,
    val taskBinding: String,
    val actionBinding: String,
    val resumeState: String,
    val status: TaskPendingActionStatus,
    val resultJson: String? = null,
    internal val claimOwner: String? = null,
) {
    fun isCurrentFor(task: ScheduledTask): Boolean = task.id == taskId && task.revision == taskRevision &&
        taskBinding == TaskPendingActionStore.taskBindingFor(task, runId, toolName, argumentsJson)
}

sealed interface TaskPendingActionClaim {
    data class Claimed(val action: TaskPendingAction, val wasWaitingForVisibleApp: Boolean = false) : TaskPendingActionClaim
    data class Stale(val action: TaskPendingAction) : TaskPendingActionClaim
    data class NotPending(val action: TaskPendingAction?) : TaskPendingActionClaim
}

/** Append-only durable queue. A claimed action from another process is never replayed. */
class TaskPendingActionStore private constructor(
    private val ledger: File,
    private val processToken: String,
) {
    constructor(ledger: File) : this(ledger, CURRENT_PROCESS_TOKEN)
    constructor(context: Context) : this(
        File(File(context.applicationContext.filesDir, "jarvys"), "tasks/pending-actions.jsonl"),
        CURRENT_PROCESS_TOKEN,
    )

    internal constructor(ledger: File, testProcessToken: String, forTesting: Boolean) : this(
        ledger,
        if (forTesting) testProcessToken else CURRENT_PROCESS_TOKEN,
    )

    fun enqueue(
        task: ScheduledTask,
        runId: String,
        toolName: String,
        canonicalArgumentsJson: String,
    ): TaskPendingAction = withLock {
        require(runId.isNotBlank()) { "runId is required" }
        require(toolName.isNotBlank()) { "toolName is required" }
        val arguments = canonicalizeArguments(canonicalArgumentsJson)
        val currentRows = readLatestLocked().values
        val prior = currentRows.firstOrNull {
            it.taskId == task.id && it.taskRevision == task.revision && it.runId == runId &&
                it.toolName == toolName && it.argumentsJson == arguments
        }
        if (prior != null) return@withLock prior
        val resumeState = if (task.state == TaskState.AwaitingUser) {
            currentRows.firstOrNull { it.taskId == task.id && it.taskRevision == task.revision }?.resumeState
                ?: TaskPendingActionResumeState.ACTIVE
        } else when (task.state) {
            TaskState.Active -> TaskPendingActionResumeState.ACTIVE
            TaskState.Paused -> TaskPendingActionResumeState.PAUSED
            else -> TaskPendingActionResumeState.ACTIVE
        }
        val id = UUID.randomUUID().toString()
        val boundTask = taskBinding(task, runId, toolName, arguments)
        val actionBinding = actionBinding(id, task.id, task.revision, runId, toolName, arguments, boundTask)
        TaskPendingAction(id, task.id, task.revision, runId, toolName, arguments, boundTask,
            actionBinding, resumeState, TaskPendingActionStatus.PENDING).also { appendLocked(it) }
    }

    /** Returns the original scheduler state while any action in this task revision still needs attention. */
    fun stateForTask(taskId: String, revision: Long, underlyingState: TaskState? = null): TaskState? = withLock {
        recoverForeignClaimsLocked()
        val rows = readLatestLocked().values.filter { it.taskId == taskId && it.taskRevision == revision }
        if (rows.isEmpty()) return@withLock null
        if (rows.any { it.status in UNRESOLVED_STATUSES }) TaskState.AwaitingUser
        else underlyingState ?: rows.first().resumeState.toTaskState()
    }

    fun listForTask(taskId: String): List<TaskPendingAction> = withLock {
        recoverForeignClaimsLocked()
        readLatestLocked().values.filter { it.taskId == taskId }
    }

    fun waitingForVisibleApp(): List<TaskPendingAction> = withLock {
        recoverForeignClaimsLocked()
        readLatestLocked().values.filter { it.status == TaskPendingActionStatus.WAITING_VISIBLE_APP }
    }

    fun get(id: String): TaskPendingAction? = withLock {
        recoverForeignClaimsLocked()
        readLatestLocked()[id]
    }

    /** Persists CLAIMED before execution. A repeated click cannot claim a second time. */
    fun claim(id: String, currentTask: ScheduledTask?): TaskPendingActionClaim = withLock {
        recoverForeignClaimsLocked()
        val current = readLatestLocked()[id] ?: return@withLock TaskPendingActionClaim.NotPending(null)
        if (current.status !in setOf(TaskPendingActionStatus.PENDING, TaskPendingActionStatus.WAITING_VISIBLE_APP)) {
            return@withLock TaskPendingActionClaim.NotPending(current)
        }
        if (currentTask == null || !current.isCurrentFor(currentTask) || !validActionBinding(current)) {
            val stale = current.copy(status = TaskPendingActionStatus.STALE)
            appendLocked(stale)
            return@withLock TaskPendingActionClaim.Stale(stale)
        }
        val wasWaitingForVisibleApp = current.status == TaskPendingActionStatus.WAITING_VISIBLE_APP
        val claimed = current.copy(status = TaskPendingActionStatus.CLAIMED, claimOwner = processToken)
        appendLocked(claimed)
        TaskPendingActionClaim.Claimed(claimed, wasWaitingForVisibleApp)
    }

    fun reject(id: String, expectedBinding: String? = null): TaskPendingAction? = withLock {
        recoverForeignClaimsLocked()
        val current = readLatestLocked()[id] ?: return@withLock null
        if (current.status !in REJECTABLE_STATUSES ||
            (expectedBinding != null && current.actionBinding != expectedBinding)) return@withLock current
        current.copy(status = TaskPendingActionStatus.REJECTED, claimOwner = null).also(::appendLocked)
    }

    fun markStale(id: String, expectedBinding: String): TaskPendingAction? = withLock {
        recoverForeignClaimsLocked()
        val current = readLatestLocked()[id] ?: return@withLock null
        if (current.status != TaskPendingActionStatus.PENDING || current.actionBinding != expectedBinding) {
            return@withLock current
        }
        current.copy(status = TaskPendingActionStatus.STALE).also(::appendLocked)
    }

    fun markExecuted(id: String, expectedBinding: String, resultJson: String): TaskPendingAction? = withLock {
        recoverForeignClaimsLocked()
        val current = readLatestLocked()[id] ?: return@withLock null
        if (current.status != TaskPendingActionStatus.CLAIMED || current.claimOwner != processToken ||
            current.actionBinding != expectedBinding || !validActionBinding(current)) return@withLock current
        current.copy(status = TaskPendingActionStatus.EXECUTED, resultJson = resultJson, claimOwner = null)
            .also(::appendLocked)
    }

    /** Keeps an approved action resumable when its WebView host is not currently visible. */
    fun waitForVisibleApp(id: String, expectedBinding: String, resultJson: String): TaskPendingAction? = withLock {
        recoverForeignClaimsLocked()
        val current = readLatestLocked()[id] ?: return@withLock null
        if (current.status != TaskPendingActionStatus.CLAIMED || current.claimOwner != processToken ||
            current.actionBinding != expectedBinding || !validActionBinding(current)) return@withLock current
        current.copy(status = TaskPendingActionStatus.WAITING_VISIBLE_APP, resultJson = resultJson, claimOwner = null)
            .also(::appendLocked)
    }

    fun markInterruptedUncertain(
        id: String,
        expectedBinding: String,
        resultJson: String? = null,
    ): TaskPendingAction? = withLock {
        recoverForeignClaimsLocked()
        val current = readLatestLocked()[id] ?: return@withLock null
        if (current.status != TaskPendingActionStatus.CLAIMED || current.actionBinding != expectedBinding) {
            return@withLock current
        }
        current.copy(status = TaskPendingActionStatus.INTERRUPTED_UNCERTAIN,
            resultJson = resultJson, claimOwner = null).also(::appendLocked)
    }

    private fun recoverForeignClaimsLocked() {
        readLatestLocked().values.filter {
            it.status == TaskPendingActionStatus.CLAIMED && it.claimOwner != processToken
        }.forEach { appendLocked(it.copy(status = TaskPendingActionStatus.INTERRUPTED_UNCERTAIN, claimOwner = null)) }
    }

    private fun validActionBinding(action: TaskPendingAction): Boolean = runCatching {
        action.argumentsJson == canonicalizeArguments(action.argumentsJson) &&
            action.actionBinding == actionBinding(action.id, action.taskId, action.taskRevision,
                action.runId, action.toolName, action.argumentsJson, action.taskBinding)
    }.getOrDefault(false)

    private fun readLatestLocked(): LinkedHashMap<String, TaskPendingAction> {
        val rows = LinkedHashMap<String, TaskPendingAction>()
        if (ledger.isFile) BufferedReader(InputStreamReader(FileInputStream(ledger), StandardCharsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                runCatching { decode(JSONObject(line)) }.getOrNull()?.let { rows[it.id] = it }
            }
        }
        return rows
    }

    private fun appendLocked(action: TaskPendingAction) {
        val parent = ledger.parentFile ?: error("Pending-action store has no parent directory")
        check(parent.isDirectory || parent.mkdirs()) { "Could not create pending-action directory" }
        FileOutputStream(ledger, true).use { stream ->
            stream.write((encode(action) + "\n").toByteArray(StandardCharsets.UTF_8))
            stream.fd.sync()
        }
        TaskDataChanges.invalidate()
    }

    private fun encode(action: TaskPendingAction): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("id", action.id)
        .put("taskId", action.taskId)
        .put("taskRevision", action.taskRevision)
        .put("runId", action.runId)
        .put("toolName", action.toolName)
        .put("argumentsJson", action.argumentsJson)
        .put("taskBinding", action.taskBinding)
        .put("actionBinding", action.actionBinding)
        .put("resumeState", action.resumeState)
        .put("status", action.status.name)
        .put("resultJson", action.resultJson ?: JSONObject.NULL)
        .put("claimOwner", action.claimOwner ?: JSONObject.NULL)
        .toString()

    private fun decode(row: JSONObject): TaskPendingAction {
        require(row.getInt("schemaVersion") == SCHEMA_VERSION)
        val result = TaskPendingAction(
            id = row.getString("id"), taskId = row.getString("taskId"),
            taskRevision = row.getLong("taskRevision"), runId = row.getString("runId"),
            toolName = row.getString("toolName"), argumentsJson = row.getString("argumentsJson"),
            taskBinding = row.getString("taskBinding"), actionBinding = row.getString("actionBinding"),
            resumeState = row.getString("resumeState"),
            status = TaskPendingActionStatus.valueOf(row.getString("status")),
            resultJson = row.optNullableString("resultJson"), claimOwner = row.optNullableString("claimOwner"),
        )
        require(result.taskRevision > 0L && result.runId.isNotBlank() && result.toolName.isNotBlank())
        require(result.resumeState in setOf(TaskPendingActionResumeState.ACTIVE, TaskPendingActionResumeState.PAUSED))
        require(result.argumentsJson == canonicalizeArguments(result.argumentsJson))
        require(validActionBinding(result))
        return result
    }

    private inline fun <T> withLock(block: () -> T): T {
        val canonical = ledger.canonicalFile
        val lock = LOCKS.computeIfAbsent(canonical.path) { Any() }
        return synchronized(lock) {
            val parent = canonical.parentFile ?: error("Pending-action store has no parent directory")
            check(parent.isDirectory || parent.mkdirs()) { "Could not create pending-action directory" }
            val coordinationFile = File(parent, canonical.name + ".lock")
            RandomAccessFile(coordinationFile, "rw").use { file ->
                file.channel.lock().use { _: FileLock -> block() }
            }
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private val LOCKS = ConcurrentHashMap<String, Any>()
        private val CURRENT_PROCESS_TOKEN: String by lazy {
            "${runCatching { Process.myPid() }.getOrDefault(0)}:${UUID.randomUUID()}"
        }
        private val UNRESOLVED_STATUSES = setOf(TaskPendingActionStatus.PENDING,
            TaskPendingActionStatus.CLAIMED, TaskPendingActionStatus.STALE,
            TaskPendingActionStatus.INTERRUPTED_UNCERTAIN)
        private val REJECTABLE_STATUSES = setOf(TaskPendingActionStatus.PENDING,
            TaskPendingActionStatus.STALE, TaskPendingActionStatus.INTERRUPTED_UNCERTAIN)

        /** Sorts object keys recursively while retaining JSON number, array and string values. */
        @JvmStatic fun canonicalizeArguments(json: String): String {
            val parsed = JSONObject(json)
            return canonicalJson(parsed)
        }

        @JvmStatic fun canonicalizeArguments(arguments: Map<String, Any?>): String =
            canonicalJson(JSONObject(arguments))

        internal fun taskBindingFor(task: ScheduledTask, runId: String, toolName: String, arguments: String): String =
            taskBinding(task, runId, toolName, arguments)

        private fun taskBinding(task: ScheduledTask, runId: String, toolName: String, arguments: String): String {
            return TaskActionBinding.pendingAction(task, runId, toolName, arguments)
        }

        private fun actionBinding(
            id: String,
            taskId: String,
            revision: Long,
            runId: String,
            toolName: String,
            arguments: String,
            taskBinding: String,
        ): String = com.jarvys.agent.ExactActionBinding.digest(
            id, taskId, revision.toString(), runId, toolName, arguments, taskBinding,
        )

        private fun canonicalJson(value: Any?): String = when {
            value == null || value === JSONObject.NULL -> "null"
            value is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
                "${JSONObject.quote(key)}:${canonicalJson(value.opt(key))}"
            }
            value is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index ->
                canonicalJson(value.opt(index))
            }
            value is Map<*, *> -> value.entries.sortedBy { it.key.toString() }.joinToString(",", "{", "}") { entry ->
                "${JSONObject.quote(entry.key.toString())}:${canonicalJson(entry.value)}"
            }
            value is Iterable<*> -> value.joinToString(",", "[", "]") { canonicalJson(it) }
            value is Array<*> -> value.joinToString(",", "[", "]") { canonicalJson(it) }
            value is String -> JSONObject.quote(value)
            value is Boolean -> value.toString()
            value is Number -> JSONObject.numberToString(value)
            else -> error("Unsupported JSON argument value: ${value.javaClass.name}")
        }
    }
}

private object TaskPendingActionResumeState {
    const val ACTIVE = "ACTIVE"
    const val PAUSED = "PAUSED"
}

private fun String.toTaskState(): TaskState = when (this) {
    TaskPendingActionResumeState.PAUSED -> TaskState.Paused
    else -> TaskState.Active
}

