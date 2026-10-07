package com.jarvys.agent.proactive

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/** Append-only, app-private event ledger. State changes append a new snapshot rather than rewriting history. */
class ProactiveEventStore(private val ledger: File) {
    constructor(context: Context) : this(File(File(context.filesDir, "jarvys"), "proactive/events.jsonl"))
    private val captureBoundaryFile: File get() = File(ledger.parentFile, "notification-capture-boundary")

    /** Returns false when this exact source/content/source-time key is already present. */
    fun append(event: ProactiveEvent): Boolean = withFileLock {
        val latest = readLatestLocked().values
        if (latest.any { it.dedupKey == event.dedupKey }) return@withFileLock false
        val existingSourceEvent = event.sourceInstanceKey?.let { sourceKey ->
            latest.firstOrNull { it.sourceInstanceKey == sourceKey }
        }
        if (existingSourceEvent != null) {
            if (existingSourceEvent.state != ProactiveEvent.PENDING) return@withFileLock false
            val originalTime = existingSourceEvent.receivedAtMillis
            val updated = event.copy(
                id = existingSourceEvent.id,
                receivedAtMillis = originalTime,
                dedupKey = notificationKeyAtOriginalTime(event, originalTime),
            )
            appendLineLocked(encode(updated))
        } else {
            appendLineLocked(encode(event))
        }
        true
    }

    fun pending(): List<ProactiveEvent> = withFileLock {
        readLatestLocked().values.filter { it.state == ProactiveEvent.PENDING }
    }

    /** Greatest source postTime already processed or deterministically discarded. */
    fun watermarkMillis(): Long = withFileLock {
        readLatestLocked().values.asSequence()
            .filter { it.state == ProactiveEvent.PROCESSED || it.state == ProactiveEvent.DISCARDED }
            .maxOfOrNull(ProactiveEvent::receivedAtMillis) ?: Long.MIN_VALUE
    }

    /** Effective lower bound also excludes notifications already active when the user enables capture. */
    fun candidatePostTimeBoundaryMillis(): Long = withFileLock {
        maxOf(watermarkMillisLocked(), readCaptureBoundaryLocked())
    }

    fun hasCaptureBoundary(): Boolean = withFileLock { readCaptureBoundaryLocked() != Long.MIN_VALUE }

    fun readAll(): List<ProactiveEvent> = withFileLock { readLatestLocked().values.toList() }

    fun markProcessed(id: String): Boolean = withFileLock {
        val current = readLatestLocked()[id] ?: return@withFileLock false
        if (current.state == ProactiveEvent.PROCESSED) return@withFileLock true
        if (current.state == ProactiveEvent.DISCARDED) return@withFileLock false
        appendLineLocked(encode(current.copy(state = ProactiveEvent.PROCESSED)))
        true
    }

    fun recordDiscard(event: ProactiveEvent, reason: String): Boolean {
        require(reason.isNotBlank()) { "A discard reason is required" }
        val stored = append(event.copy(state = ProactiveEvent.DISCARDED, discardReason = reason))
        return stored
    }

    fun markDiscarded(id: String, reason: String): Boolean = withFileLock {
        require(reason.isNotBlank()) { "A discard reason is required" }
        val current = readLatestLocked()[id] ?: return@withFileLock false
        if (current.state == ProactiveEvent.DISCARDED) return@withFileLock true
        if (current.state == ProactiveEvent.PROCESSED) return@withFileLock false
        appendLineLocked(encode(current.copy(state = ProactiveEvent.DISCARDED, discardReason = reason)))
        true
    }

    /** Purges current pending payloads on opt-out while retaining processed/discarded audit entries. */
    fun clearPending(): Int {
        val canonical = ledger.canonicalFile
        val lock = LOCKS.computeIfAbsent(canonical.path) { Any() }
        return synchronized(lock) {
            if (!canonical.isFile) return@synchronized 0
            RandomAccessFile(canonical, "rw").use { file ->
                file.channel.lock().use {
                    val latest = readLatestLocked()
                    val removed = latest.values.count { it.state == ProactiveEvent.PENDING }
                    val retained = latest.values.filter { it.state != ProactiveEvent.PENDING }
                    val scrubbed = retained.map { event ->
                        event.copy(
                            appLabel = null,
                            sender = null,
                            title = null,
                            body = null,
                            sourceInstanceKey = null,
                            conversationThread = null,
                        )
                    }
                    val needsRewrite = removed > 0 ||
                        retained.zip(scrubbed).any { (before, after) -> before != after } ||
                        containsInvalidRowsLocked()
                    if (needsRewrite) rewriteLocked(scrubbed)
                    removed
                }
            }
        }
    }

    private fun readLatestLocked(): LinkedHashMap<String, ProactiveEvent> {
        val events = LinkedHashMap<String, ProactiveEvent>()
        if (!ledger.isFile) return events
        ledger.forEachLine(StandardCharsets.UTF_8) { line ->
            val event = runCatching { decode(JSONObject(line)) }.getOrNull() ?: return@forEachLine
            events[event.id] = event
        }
        return events
    }

    private fun containsInvalidRowsLocked(): Boolean {
        if (!ledger.isFile) return false
        var invalid = false
        ledger.forEachLine(StandardCharsets.UTF_8) { line ->
            if (runCatching { decode(JSONObject(line)) }.isFailure) invalid = true
        }
        return invalid
    }

    private fun rewriteLocked(events: List<ProactiveEvent>) {
        val parent = ledger.parentFile ?: error("Proactive event ledger has no parent directory")
        val temporary = File(parent, ledger.name + ".pending-clear")
        try {
            FileOutputStream(temporary, false).use { output ->
                events.forEach { event ->
                    output.write((encode(event) + "\n").toByteArray(StandardCharsets.UTF_8))
                }
                output.fd.sync()
            }
            check(temporary.renameTo(ledger)) { "Could not atomically replace proactive event ledger" }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun notificationKeyAtOriginalTime(event: ProactiveEvent, originalTime: Long): String =
        ProactiveNormalizer.dedupKey(
            event.sourceId,
            listOf(
                event.appPackage.orEmpty(),
                event.sender.orEmpty(),
                ProactiveNormalizer.normalizeText(event.title.orEmpty()),
                ProactiveNormalizer.normalizeText(event.body.orEmpty()),
            ).joinToString("\u001f"),
            originalTime,
        )

    private fun appendLineLocked(line: String) {
        ledger.parentFile?.let { parent -> check(parent.isDirectory || parent.mkdirs()) { "Could not create proactive event directory" } }
        val bytes = (line + "\n").toByteArray(StandardCharsets.UTF_8)
        FileOutputStream(ledger, true).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun readCaptureBoundaryLocked(): Long = runCatching {
        captureBoundaryFile.takeIf(File::isFile)?.readText(StandardCharsets.UTF_8)?.trim()?.toLong()
    }.getOrNull() ?: Long.MIN_VALUE

    fun initializeCaptureBoundary(timestampMillis: Long) = withFileLock {
        if (timestampMillis <= readCaptureBoundaryLocked()) return@withFileLock
        captureBoundaryFile.parentFile?.let { parent -> check(parent.isDirectory || parent.mkdirs()) }
        FileOutputStream(captureBoundaryFile, false).use { output ->
            output.write(timestampMillis.toString().toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun watermarkMillisLocked(): Long = readLatestLocked().values.asSequence()
        .filter { it.state == ProactiveEvent.PROCESSED || it.state == ProactiveEvent.DISCARDED }
        .maxOfOrNull(ProactiveEvent::receivedAtMillis) ?: Long.MIN_VALUE

    private inline fun <T> withFileLock(block: () -> T): T {
        val canonical = ledger.canonicalFile
        val lock = LOCKS.computeIfAbsent(canonical.path) { Any() }
        return synchronized(lock) {
            canonical.parentFile?.let { parent -> check(parent.isDirectory || parent.mkdirs()) { "Could not create proactive event directory" } }
            RandomAccessFile(canonical, "rw").use { file ->
                val channel: FileChannel = file.channel
                channel.lock().use { _: FileLock -> block() }
            }
        }
    }

    private fun encode(event: ProactiveEvent): String = JSONObject()
        .put("id", event.id)
        .put("sourceId", event.sourceId)
        .put("dedupKey", event.dedupKey)
        .put("receivedAtMillis", event.receivedAtMillis)
        .put("observedAtMillis", event.observedAtMillis)
        .putNullable("appPackage", event.appPackage)
        .putNullable("appLabel", event.appLabel)
        .putNullable("sender", event.sender)
        .putNullable("title", event.title)
        .putNullable("body", event.body)
        .put("category", event.category)
        .putNullable("direction", event.direction)
        .put("state", event.state)
        .putNullable("discardReason", event.discardReason)
        .put("ongoing", event.ongoing)
        .put("foregroundService", event.foregroundService)
        .put("groupSummary", event.groupSummary)
        .putNullable("androidCategory", event.androidCategory)
        .putNullable("sourceInstanceKey", event.sourceInstanceKey)
        .putNullable("conversationThread", event.conversationThread)
        .putNullable("prefilterMark", event.prefilterMark)
        .toString()

    private fun decode(row: JSONObject): ProactiveEvent = ProactiveEvent(
        id = row.getString("id"),
        sourceId = row.getString("sourceId"),
        dedupKey = row.getString("dedupKey"),
        receivedAtMillis = row.getLong("receivedAtMillis"),
        observedAtMillis = row.getLong("observedAtMillis"),
        appPackage = row.optNullableString("appPackage"),
        appLabel = row.optNullableString("appLabel"),
        sender = row.optNullableString("sender"),
        title = row.optNullableString("title"),
        body = row.optNullableString("body"),
        category = row.getString("category"),
        direction = row.optNullableString("direction"),
        state = row.optString("state", ProactiveEvent.PENDING),
        discardReason = row.optNullableString("discardReason"),
        ongoing = row.optBoolean("ongoing", false),
        foregroundService = row.optBoolean("foregroundService", false),
        groupSummary = row.optBoolean("groupSummary", false),
        androidCategory = row.optNullableString("androidCategory"),
        sourceInstanceKey = row.optNullableString("sourceInstanceKey"),
        conversationThread = row.optNullableString("conversationThread"),
        prefilterMark = row.optNullableString("prefilterMark"),
    )

    private fun JSONObject.putNullable(key: String, value: String?): JSONObject = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key)

    companion object {
        private val LOCKS = ConcurrentHashMap<String, Any>()
    }
}

/** Shared production boundary; the listener uses this to normalize, structurally filter, and persist each captured event. */
object ProactiveNotificationCapture {
    fun capture(input: NotificationInput, store: ProactiveEventStore, ownPackage: String): ProactiveCaptureResult =
        ProactiveIngestion.ingest(ProactiveNormalizer.notification(input), store, ownPackage)
}

/** Immediate runs spend provider tokens, so only time-sensitive event categories bypass the periodic queue. */
object ProactiveImmediatePolicy {
    fun shouldRequestRunNow(category: String): Boolean = when (category) {
        "msg", "call", "missed_call", "email" -> true
        else -> false
    }
}

/** Listener entry point: only candidates enqueue punctual review, and input extraction itself is lazy behind opt-in. */
object ProactiveNotificationDispatch {
    fun onNotification(
        context: Context,
        preferences: ProactivePreferences,
        input: () -> NotificationInput,
        ownPackage: String,
    ): ProactiveCaptureResult {
        var result: ProactiveCaptureResult = ProactiveCaptureResult.Disabled
        val entered = preferences.captureIfEnabled {
            result = ProactiveNotificationCapture.capture(input(), ProactiveEventStore(context), ownPackage)
        }
        if (!entered) return ProactiveCaptureResult.Disabled
        val outcome = result
        if (outcome is ProactiveCaptureResult.CandidateStored &&
            ProactiveImmediatePolicy.shouldRequestRunNow(outcome.category)) {
            ProactiveScheduler.requestRunNow(context)
        }
        return result
    }
}
