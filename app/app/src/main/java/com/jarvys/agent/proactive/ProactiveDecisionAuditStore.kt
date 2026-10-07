package com.jarvys.agent.proactive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

data class ProactiveAuditedEvent(
    val id: String,
    val sourceId: String,
    val appPackage: String?,
    val category: String,
    val receivedAtMillis: Long,
    val dedupKey: String,
)

data class ProactiveDecisionRecord(
    val decisionId: String,
    val events: List<ProactiveAuditedEvent>,
    val notify: Boolean,
    val urgency: String,
    val title: String?,
    val body: String?,
    val decidedAtMillis: Long,
    val eventIds: List<String> = events.map(ProactiveAuditedEvent::id),
    val threadKey: String = "",
    val suggestedReplies: List<ProactiveSuggestedReply> = emptyList(),
)

/** Stores decision metadata only; source text is never copied into this audit ledger. */
class ProactiveDecisionAuditStore(private val ledger: File) {
    constructor(context: Context) : this(File(File(context.filesDir, "jarvys"), "proactive/decisions.jsonl"))

    fun find(decisionId: String): ProactiveDecisionRecord? = withLock {
        readLatestLocked()[decisionId]
    }

    /** Idempotent across a worker retry; returns the original decision if already recorded. */
    fun saveIfAbsent(record: ProactiveDecisionRecord): ProactiveDecisionRecord = withLock {
        readLatestLocked()[record.decisionId]?.let { return@withLock it }
        appendLocked(encode(record))
        record
    }

    fun readAll(): List<ProactiveDecisionRecord> = withLock { readLatestLocked().values.toList() }

    /** Prior generated notice titles only; event content is never exposed to a later model turn. */
    fun recentNotificationTitles(): List<String> = withLock {
        readLatestLocked().values.asSequence().filter { it.notify }.sortedByDescending(ProactiveDecisionRecord::decidedAtMillis)
            .mapNotNull(ProactiveDecisionRecord::title).filter(String::isNotBlank).toList()
    }

    /** Opt-out removes generated notice copy while retaining decision/audit fields. */
    fun scrubNotificationText() = withLock {
        val latest = readLatestLocked().values.toList()
        if (latest.none { it.title != null || it.body != null } && !hasInvalidRowsLocked()) return@withLock
        rewriteLocked(latest.map { it.copy(title = null, body = null, suggestedReplies = emptyList()) })
    }

    private fun readLatestLocked(): LinkedHashMap<String, ProactiveDecisionRecord> {
        val records = LinkedHashMap<String, ProactiveDecisionRecord>()
        if (!ledger.isFile) return records
        ledger.forEachLine(StandardCharsets.UTF_8) { line ->
            val record = runCatching { decode(JSONObject(line)) }.getOrNull() ?: return@forEachLine
            records[record.decisionId] = record
        }
        return records
    }

    private fun hasInvalidRowsLocked(): Boolean {
        if (!ledger.isFile) return false
        var invalid = false
        ledger.forEachLine(StandardCharsets.UTF_8) { line ->
            if (runCatching { decode(JSONObject(line)) }.isFailure) invalid = true
        }
        return invalid
    }

    private fun appendLocked(line: String) {
        ledger.parentFile?.let { parent -> check(parent.isDirectory || parent.mkdirs()) }
        FileOutputStream(ledger, true).use { output ->
            output.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun rewriteLocked(records: List<ProactiveDecisionRecord>) {
        val parent = ledger.parentFile ?: error("Proactive decision ledger has no parent")
        val temporary = File(parent, ledger.name + ".scrub")
        try {
            FileOutputStream(temporary, false).use { output ->
                records.forEach { output.write((encode(it) + "\n").toByteArray(StandardCharsets.UTF_8)) }
                output.fd.sync()
            }
            check(temporary.renameTo(ledger)) { "Could not atomically scrub proactive decision ledger" }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private inline fun <T> withLock(block: () -> T): T {
        val canonical = ledger.canonicalFile
        val lock = LOCKS.computeIfAbsent(canonical.path) { Any() }
        return synchronized(lock) {
            canonical.parentFile?.let { parent -> check(parent.isDirectory || parent.mkdirs()) }
            RandomAccessFile(canonical, "rw").use { file -> file.channel.lock().use { _: FileLock -> block() } }
        }
    }

    private fun encode(record: ProactiveDecisionRecord): String {
        val events = JSONArray()
        record.events.forEach { event ->
            events.put(JSONObject()
                .put("id", event.id)
                .put("sourceId", event.sourceId)
                .putNullable("appPackage", event.appPackage)
                .put("category", event.category)
                .put("receivedAtMillis", event.receivedAtMillis)
                .put("dedupKey", event.dedupKey))
        }
        val json = JSONObject()
            .put("decisionId", record.decisionId)
            .put("events", events)
            .put("eventIds", JSONArray(record.eventIds))
            .put("threadKey", record.threadKey)
            .put("notify", record.notify)
            .put("urgency", record.urgency)
            .put("decidedAtMillis", record.decidedAtMillis)
        if (record.notify) {
            json.putNullable("title", record.title)
            json.putNullable("body", record.body)
            val replies = JSONArray()
            record.suggestedReplies.forEach { reply ->
                replies.put(JSONObject().put("label", reply.label).put("text", reply.text))
            }
            json.put("suggestedReplies", replies)
        }
        return json.toString()
    }

    private fun decode(row: JSONObject): ProactiveDecisionRecord {
        val events = mutableListOf<ProactiveAuditedEvent>()
        val rows = row.getJSONArray("events")
        for (index in 0 until rows.length()) {
            val item = rows.getJSONObject(index)
            events += ProactiveAuditedEvent(
                id = item.getString("id"),
                sourceId = item.getString("sourceId"),
                appPackage = item.optNullableString("appPackage"),
                category = item.getString("category"),
                receivedAtMillis = item.getLong("receivedAtMillis"),
                dedupKey = item.getString("dedupKey"),
            )
        }
        val notify = row.getBoolean("notify")
        val eventIds = row.optJSONArray("eventIds")?.let { ids ->
            (0 until ids.length()).mapNotNull { ids.optString(it).takeIf(String::isNotBlank) }
        } ?: events.map(ProactiveAuditedEvent::id)
        val suggestedReplies = row.optJSONArray("suggestedReplies")?.let { replies ->
            (0 until replies.length()).mapNotNull { index ->
                val reply = replies.optJSONObject(index) ?: return@mapNotNull null
                val label = reply.optString("label").trim()
                val text = reply.optString("text").trim()
                if (label.isEmpty() || text.isEmpty()) null else ProactiveSuggestedReply(label, text)
            }
        }.orEmpty()
        return ProactiveDecisionRecord(
            decisionId = row.getString("decisionId"),
            events = events,
            notify = notify,
            urgency = row.getString("urgency"),
            title = if (notify) row.optNullableString("title") else null,
            body = if (notify) row.optNullableString("body") else null,
            decidedAtMillis = row.getLong("decidedAtMillis"),
            eventIds = eventIds,
            threadKey = row.optString("threadKey", ""),
            suggestedReplies = if (notify) suggestedReplies else emptyList(),
        )
    }

    private fun JSONObject.putNullable(key: String, value: String?): JSONObject = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key)

    companion object {
        private val LOCKS = ConcurrentHashMap<String, Any>()
    }
}
