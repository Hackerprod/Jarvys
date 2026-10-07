package com.jarvys.agent.tasks

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

data class TaskRunRecord(
    val taskId: String,
    val runId: String,
    val scheduledFor: Long,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: String = "STARTED",
    val deliveryStatus: String = "NOT_APPLICABLE",
    val reason: String? = null,
    val skipReason: String? = null,
    val notified: Boolean = false,
    val toolsCalled: List<TaskToolCallRecord> = emptyList(),
    val errorCause: String? = null,
    val model: String? = null,
    val usageTokens: Int? = null,
    val manualOccurrenceId: String? = null,
) {
    val occurrenceId: String get() = manualOccurrenceId ?: "$taskId:$scheduledFor"
    val idempotencyKey: String get() = occurrenceId
}

data class TaskToolCallRecord(val name: String, val write: Boolean, val outcome: String)

data class TaskRunLedgerReadResult(val runs: List<TaskRunRecord>, val ignoredCorruptRows: Int)

/** Append-only occurrence ledger, except a user-confirmed per-task history purge. */
class TaskRunLedger(private val ledger: File) {
    constructor(context: Context) : this(File(File(context.applicationContext.filesDir, "jarvys"), "tasks/runs.jsonl"))

    fun read(): TaskRunLedgerReadResult = withLock {
        val latest = readLatestLocked()
        TaskRunLedgerReadResult(latest.runs.values.toList(), latest.ignored)
    }
    /** WorkManager is unique cross-process; this monitor closes same-process engine races too. */
    fun <T> withOccurrenceLock(block: () -> T): T = synchronized(OCCURRENCE_LOCK, block)
    fun forTask(taskId: String): List<TaskRunRecord> = read().runs.filter { it.taskId == taskId }
    fun find(taskId: String, scheduledFor: Long): TaskRunRecord? =
        read().runs.firstOrNull { it.idempotencyKey == "$taskId:$scheduledFor" }

    /** Atomically claims an occurrence before its executor can produce effects. */
    fun claim(record: TaskRunRecord): Boolean = withLock {
        val latest = readLatestLocked().runs
        val previous = latest[record.idempotencyKey]
        if (previous != null && (previous.status !in RETRYABLE_STATES
                || previous.toolsCalled.any { it.write })) {
            return@withLock false
        }
        appendLocked(encode(record.copy(status = "STARTED", finishedAt = null)))
        true
    }

    fun saveSnapshot(record: TaskRunRecord) = withLock {
        require(readLatestLocked().runs.containsKey(record.idempotencyKey)) {
            "Task occurrence must be claimed before saving its result"
        }
        appendLocked(encode(record))
    }

    fun markRetryable(taskId: String, scheduledFor: Long): Boolean = withLock {
        val key = "$taskId:$scheduledFor"
        val record = readLatestLocked().runs[key] ?: return@withLock false
        if (record.status == "RETRYABLE") return@withLock true
        if (record.status != "ERROR" && record.status != "INTERRUPTED") return@withLock false
        appendLocked(encode(record.copy(status = "RETRYABLE", reason = null, errorCause = null)))
        true
    }

    /** Import/test seam with the same compare-and-append idempotency guarantee as a worker claim. */
    fun appendIfAbsent(record: TaskRunRecord): Boolean = withLock {
        if (readLatestLocked().runs.containsKey(record.idempotencyKey)) return@withLock false
        appendLocked(encode(record))
        true
    }

    /** A confirmed history deletion removes that task's rows and preserves all other tasks' ledger rows. */
    fun clearTask(taskId: String) = withLock {
        if (!ledger.isFile) return@withLock
        val temporary = File(ledger.parentFile, "${ledger.name}.${java.util.UUID.randomUUID()}.tmp")
        try {
            FileInputStream(ledger).bufferedReader(StandardCharsets.UTF_8).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.forEachLine { line ->
                        val belongsToTask = runCatching { JSONObject(line).optString("taskId") == taskId }.getOrDefault(false)
                        if (!belongsToTask) output.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
                    }
                    output.fd.sync()
                }
            }
            try {
                java.nio.file.Files.move(temporary.toPath(), ledger.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(temporary.toPath(), ledger.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            TaskDataChanges.invalidate()
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    @Volatile var lastReadDiagnostics: Int = 0
        private set

    private data class Latest(val runs: LinkedHashMap<String, TaskRunRecord>, val ignored: Int)

    private fun readLatestLocked(): Latest {
        val rows = LinkedHashMap<String, TaskRunRecord>()
        var ignored = 0
        if (ledger.isFile) BufferedReader(InputStreamReader(FileInputStream(ledger), StandardCharsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val record = runCatching { decode(JSONObject(line)) }.getOrNull()
                if (record == null) ignored++ else rows[record.idempotencyKey] = record
            }
        }
        lastReadDiagnostics = ignored
        return Latest(rows, ignored)
    }

    private fun encode(record: TaskRunRecord): String = JSONObject()
        .put("taskId", record.taskId)
        .put("occurrenceId", record.idempotencyKey)
        .put("runId", record.runId)
        .put("scheduledFor", record.scheduledFor)
        .put("startedAt", record.startedAt)
        .putNullableLong("finishedAt", record.finishedAt)
        .put("status", record.status)
        .put("deliveryStatus", record.deliveryStatus)
        .putNullableString("reason", record.reason)
        .putNullableString("skipReason", record.skipReason)
        .put("notified", record.notified)
        .put("toolsCalled", org.json.JSONArray().apply {
            record.toolsCalled.forEach { tool -> put(JSONObject().put("name", tool.name)
                .put("write", tool.write).put("outcome", tool.outcome)) }
        })
        .putNullableString("errorCause", record.errorCause)
        .putNullableString("model", record.model)
        .put("usageTokens", record.usageTokens ?: JSONObject.NULL)
        .toString()

    private fun decode(row: JSONObject): TaskRunRecord {
        val calls = row.optJSONArray("toolsCalled")
        val tools = if (calls == null) emptyList() else (0 until calls.length()).mapNotNull { index ->
            calls.optJSONObject(index)?.let { item -> TaskToolCallRecord(item.getString("name"),
                item.optBoolean("write", false), item.getString("outcome")) }
        }
        val record = TaskRunRecord(
            taskId = row.getString("taskId"), runId = row.getString("runId"),
            scheduledFor = row.getLong("scheduledFor"), startedAt = row.getLong("startedAt"),
            finishedAt = row.optNullableLong("finishedAt"), status = row.getString("status"),
            deliveryStatus = row.getString("deliveryStatus"), reason = row.optNullableString("reason"),
            skipReason = row.optNullableString("skipReason"), notified = row.optBoolean("notified", false),
            toolsCalled = tools, errorCause = row.optNullableString("errorCause"),
            model = row.optNullableString("model"), usageTokens = if (row.isNull("usageTokens")) null else row.optInt("usageTokens"),
            manualOccurrenceId = row.optString("occurrenceId", "").takeIf {
                it.isNotEmpty() && it != "${row.getString("taskId")}:${row.getLong("scheduledFor")}" },
        )
        return record
    }

    private fun appendLocked(line: String) {
        val parent = ledger.parentFile ?: error("Task run ledger has no parent directory")
        check(parent.isDirectory || parent.mkdirs()) { "Could not create task run ledger directory" }
        FileOutputStream(ledger, true).use { stream ->
            stream.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
            stream.fd.sync()
        }
        TaskDataChanges.invalidate()
    }

    private inline fun <T> withLock(block: () -> T): T {
        val canonical = ledger.canonicalFile
        val lock = LOCKS.computeIfAbsent(canonical.path) { Any() }
        return synchronized(lock) {
            val parent = canonical.parentFile ?: error("Task run ledger has no parent directory")
            check(parent.isDirectory || parent.mkdirs()) { "Could not create task run ledger directory" }
            val coordinationFile = File(parent, canonical.name + ".lock")
            RandomAccessFile(coordinationFile, "rw").use { file -> file.channel.lock().use { _: FileLock -> block() } }
        }
    }

    companion object {
        private val LOCKS = ConcurrentHashMap<String, Any>()
        private val OCCURRENCE_LOCK = Any()
        private val RETRYABLE_STATES = setOf("RETRYABLE", "INTERRUPTED")
    }
}

private fun JSONObject.putNullableLong(key: String, value: Long?): JSONObject = put(key, value ?: JSONObject.NULL)
private fun JSONObject.putNullableString(key: String, value: String?): JSONObject = put(key, value ?: JSONObject.NULL)
private fun JSONObject.optNullableLong(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
private fun JSONObject.optNullableString(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)
