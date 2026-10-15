package com.jarvys.agent.chatdb

import androidx.room.withTransaction
import com.jarvys.agent.ReflectionTranscriptBuilder
import com.jarvys.agent.crew.CrewBotSnapshot
import com.jarvys.agent.crew.CrewInputBinding
import com.jarvys.agent.crew.CrewMessage
import com.jarvys.agent.crew.CrewMissionSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TimeZone

data class CompactionInput(
    val conversationId: String,
    val summary: String,
    val firstKept: Long,
    val trigger: String? = null,
    val mode: String? = null,
    val summarizedMessages: Long,
    val createdAtSeconds: Double? = null,
    /** M2 supplies the exact legacy id computed from the source timestamp token and logical row index. */
    val persistedCompactionId: String? = null,
)

data class ReflectionInput(
    val conversationId: String,
    val kind: String,
    val reflectionId: String? = null,
    val throughMessageId: String? = null,
    val completedAssistantSteps: Long? = null,
    val result: String? = null,
    val summary: String? = null,
    val status: String? = null,
    val trigger: String? = null,
    val revisionIds: List<Long> = emptyList(),
    val acknowledgedCompactionIds: List<String> = emptyList(),
    val createdAtSeconds: Double? = null,
)

data class ReflectionPayloadSnapshot(
    val text: String,
    val endMessageId: String,
    val compactionIds: List<String>,
    val completedAssistantSteps: Int,
    val userCharacters: Int,
    val messageCount: Int,
    val hasMoreMessages: Boolean,
)

data class CrewSnapshotMarker(val conversationId: String, val missionId: String, val firstSnapshotSeq: Long, val lastSnapshotSeq: Long)
data class PendingInterrupt(
    val conversationId: String,
    val requestId: String,
    val seq: Long,
    val content: String,
    val memoryDisabled: Boolean,
    val skills: List<String>,
    val dispatchedMessageId: String?,
    val dispatchedSeq: Long?,
    val inputState: String?,
    val alreadyDispatched: Boolean,
    val acknowledgementRequired: Boolean,
)

/**
 * Normalized persistence for `compaction`, reflection rows and `crew_snapshot`. It replaces
 * `LocalRunStore.appendCompaction`, `buildReflectionPayload`, `appendReflectionCheckpoint`,
 * `appendReflectionCommit`, `appendReflectionEvent`, `appendReflectionUndoEvent`,
 * `pendingCompactionIds`, `hasReflectionCommit`, `readCrewMissionSnapshots` and
 * `recoverCrewMissions` (`LocalRunStore.java:1056-1172`, `3239-3484`, `4635-4650`). Crew snapshots
 * accept the same source schemas 1–4 through `CrewMissionSnapshot.fromJson`; messages, refs,
 * activities, bindings, bots and tools are stored in typed child tables.
 */
internal class MemoryTimelineRepository internal constructor(database: JarvysChatDatabase) : M1bWriteSupport(database) {
    /**
     * Adds one `compaction` row (`LocalRunStore.appendCompaction`, `LocalRunStore.java:4635-4654`) and
     * persists `legacy-<timestamp>-<rowIndex>` exactly as `LocalRunStore.compactionId`
     * (`LocalRunStore.java:3500-3503`) does. The index counts eligible `conversation_items` in
     * `(sort_seq,sort_sub,seq)` order before the new row, excluding the result-only row kinds used
     * by `loadConversationContext` (`LocalRunStore.java:2879-2915`). M2 must reproduce the legacy
     * filtered list by importing those sort positions after `readConversationRows` applies
     * `isResultOnlyRow` and `orderAssistantMessagesBeforeSteering` (`LocalRunStore.java:3017-3018`,
     * `3041-3043`, `3143-3157`), then pass the ID built from the original timestamp token through
     * [CompactionInput.persistedCompactionId]. New rows derive the same index immediately before append.
     */
    suspend fun appendCompaction(input: CompactionInput): ChatDbResult<Compaction> = transaction {
        if (input.conversationId.isBlank() || input.summary.isBlank() || input.summary.trim().isEmpty())
            return@transaction ChatDbResult.Invalid("compaction summary and conversation are required")
        ensureConversationExists(input.conversationId)
        val time = input.createdAtSeconds ?: nowSeconds()
        val nextSeq = nextSequence(input.conversationId)
        val rowIndex = timelineRowCountBefore(input.conversationId, nextSeq, 0)
        val id = input.persistedCompactionId?.takeIf(String::isNotBlank) ?: "legacy-${jsonNumberToken(time)}-$rowIndex"
        if (readCompaction(input.conversationId, id) != null) return@transaction ChatDbResult.Conflict("compaction identity already exists")
        val seq = insertLedgerItem(input.conversationId, "compaction", id, time)
        sql.execSQL(
            "INSERT INTO compactions(conversation_id,compaction_id,seq,summary,first_kept,summarized_messages,trigger,mode,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            arrayOf(input.conversationId, id, seq, input.summary, input.firstKept.coerceAtLeast(0L),
                input.summarizedMessages.coerceAtLeast(0L), input.trigger ?: "manual", input.mode ?: "all", time),
        )
        ChatDbResult.Success(readCompaction(input.conversationId, id)!!)
    }

    /**
     * Appends `compaction_reflection_consumed`, replacing
     * `LocalRunStore.appendCompactionReflectionAcknowledgement` (`LocalRunStore.java:4624-4632`);
     * `clearAll` expands to each earlier compaction ID rather than storing an ambiguous empty ack.
     */
    suspend fun acknowledgeCompactions(conversationId: String, compactionIds: List<String>? = null, clearAll: Boolean = false): ChatDbResult<Long> = transaction {
        ensureConversationExists(conversationId)
        val time = nowSeconds()
        val nextSeq = nextSequence(conversationId)
        val ids = if (clearAll || compactionIds == null) {
            sql.query(
                "SELECT c.compaction_id FROM compactions c JOIN conversation_items i ON i.conversation_id=c.conversation_id AND i.seq=c.seq " +
                    "WHERE c.conversation_id=? AND (i.sort_seq<? OR (i.sort_seq=? AND i.sort_sub<?) OR " +
                    "(i.sort_seq=? AND i.sort_sub=? AND i.seq<?)) ORDER BY i.sort_seq,i.sort_sub,i.seq",
                arrayOf(conversationId, nextSeq, nextSeq, 0, nextSeq, 0, nextSeq),
            )
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        } else compactionIds.distinct()
        for (id in ids) {
            val prior = readCompaction(conversationId, id) ?: return@transaction ChatDbResult.Invalid("acknowledgement names an unknown compaction")
            if (!compactionPrecedesAppend(conversationId, prior.seq, nextSeq))
                return@transaction ChatDbResult.Invalid("acknowledgement can only consume an earlier compaction")
        }
        val seq = insertLedgerItem(conversationId, "compaction_reflection_consumed", ids.joinToString("\u001f"), time)
        insertCompactionAcks(conversationId, seq, "compaction_reflection_consumed", ids, time)
        ChatDbResult.Success(seq)
    }

    /** Appends `reflection_checkpoint`, replacing `LocalRunStore.appendReflectionCheckpoint` (`LocalRunStore.java:3386-3401`). */
    suspend fun appendReflectionCheckpoint(input: ReflectionInput): ChatDbResult<Long> = appendReflection(input, "reflection_checkpoint")

    /** Appends `reflection_commit`, replacing `LocalRunStore.appendReflectionCommit` (`LocalRunStore.java:3403-3428`). */
    suspend fun appendReflectionCommit(input: ReflectionInput): ChatDbResult<Long> = appendReflection(input, "reflection_commit")

    /** Appends `reflection_event`, replacing `LocalRunStore.appendReflectionEvent` (`LocalRunStore.java:3437-3451`). */
    suspend fun appendReflectionEvent(input: ReflectionInput): ChatDbResult<Long> = appendReflection(input, "reflection_event")

    /** Appends `reflection_undo`, replacing `LocalRunStore.appendReflectionUndoEvent` (`LocalRunStore.java:3453-3458`), and marks its latest matching commit row undone. */
    suspend fun appendReflectionUndo(conversationId: String, reflectionId: String, createdAtSeconds: Double? = null): ChatDbResult<Long> = transaction {
        if (conversationId.isBlank() || reflectionId.isBlank()) return@transaction ChatDbResult.Invalid("reflection identity is required")
        ensureConversationExists(conversationId)
        val target = sql.query("SELECT seq FROM reflections WHERE conversation_id=? AND reflection_id=? ORDER BY seq DESC LIMIT 1",
            arrayOf(conversationId, reflectionId)).use { if (it.moveToFirst()) it.getLong(0) else null }
        val time = createdAtSeconds ?: nowSeconds()
        val seq = insertLedgerItem(conversationId, "reflection_undo", reflectionId, time)
        sql.execSQL("INSERT INTO reflections(conversation_id,seq,kind,reflection_id,created_at) VALUES(?,?,?,?,?)",
            arrayOf(conversationId, seq, "reflection_undo", reflectionId, time))
        if (target != null) sql.execSQL("UPDATE reflections SET undone_seq=? WHERE conversation_id=? AND seq=?", arrayOf(seq, conversationId, target))
        ChatDbResult.Success(seq)
    }

    /** `hasReflectionCommit` equivalent, scoped to this conversation (`LocalRunStore.java:3460-3465`). */
    suspend fun hasReflectionCommit(conversationId: String, reflectionId: String): Boolean = database.withTransaction {
        sql.query("SELECT EXISTS(SELECT 1 FROM reflections WHERE conversation_id=? AND reflection_id=? AND kind='reflection_commit')",
            arrayOf(conversationId, reflectionId)).use { it.moveToFirst(); it.getInt(0) != 0 }
    }

    /** Reads compaction IDs still awaiting reflection, in source order (`LocalRunStore.java:3468-3484`). */
    suspend fun pendingCompactionIds(conversationId: String): List<String> = database.withTransaction {
        val pending = LinkedHashSet<String>()
        sql.query("SELECT item_kind,item_key,seq FROM conversation_items WHERE conversation_id=? AND item_kind IN ('compaction','reflection_checkpoint','reflection_commit','compaction_reflection_consumed') ORDER BY sort_seq,sort_sub,seq",
            arrayOf(conversationId)).use { c ->
            while (c.moveToNext()) {
                when (c.getString(0)) {
                    "compaction" -> pending.add(c.getString(1))
                    "reflection_checkpoint", "reflection_commit", "compaction_reflection_consumed" ->
                        sql.query("SELECT compaction_id FROM compaction_reflection_acks WHERE conversation_id=? AND seq=?",
                            arrayOf(conversationId, c.getLong(2))).use { a -> while (a.moveToNext()) pending.remove(a.getString(0)) }
                }
            }
        }
        pending.toList()
    }

    /** Returns the latest active `compaction` as `loadConversationContext` does (`LocalRunStore.java:2879-2896`). */
    suspend fun activeCompaction(conversationId: String): Compaction? = database.withTransaction { latestActiveCompaction(conversationId) }

    /**
     * SQL-backed equivalent of `LocalRunStore.buildReflectionPayload`
     * (`LocalRunStore.java:3239-3368`), including its `reflection_tool` trust markers and checkpoint boundary.
     */
    suspend fun buildReflectionPayload(conversationId: String, throughMessageId: String? = null, replayAll: Boolean = false): ReflectionPayloadSnapshot = database.withTransaction {
        val rows = readTimelineRows(conversationId)
        val messages = rows.mapIndexedNotNull { index, row ->
            if (row.role == "user" || row.role == "assistant") row.messageId?.let { id ->
                ReflectionMessage(id, row.role, row.text.orEmpty(), row.status.orEmpty(), row.ownerUserMessageId.orEmpty(), index)
            } else null
        }
        val indexById = LinkedHashMap<String, Int>()
        messages.forEachIndexed { index, message -> indexById[message.id] = index }
        val latestCheckpoint = if (replayAll) null else sql.query(
            "SELECT r.through_message_id FROM reflections r JOIN conversation_items i ON i.conversation_id=r.conversation_id AND i.seq=r.seq " +
                "WHERE r.conversation_id=? AND r.kind IN ('reflection_checkpoint','reflection_commit') " +
                "ORDER BY i.sort_seq DESC,i.sort_sub DESC,i.seq DESC LIMIT 1",
            arrayOf(conversationId),
        ).use { if (it.moveToFirst()) it.getStringOrNullMemory("through_message_id") else null }
        val checkpointIndex = latestCheckpoint?.let { indexById[it] } ?: -1
        val maxRowIndex = if (throughMessageId.isNullOrEmpty()) rows.lastIndex else rows.indexOfFirst { it.messageId == throughMessageId }
        val hidden = sql.query("SELECT message_id FROM messages WHERE conversation_id=? AND hidden_kind IN ('assistant_regenerated','assistant_deleted')",
            arrayOf(conversationId)).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        val end = messages.indices.reversed().firstOrNull { i -> messages[i].rowIndex <= maxRowIndex && !(messages[i].role == "assistant" && messages[i].id in hidden) } ?: -1
        val endMessageId = if (end < 0) "" else messages[end].id
        if (end <= checkpointIndex) return@withTransaction ReflectionPayloadSnapshot("[]", "", emptyList(), 0, 0, 0, false)

        val connectorUsers = sql.query("SELECT owner_user_message_id,source FROM reflection_tool_markers WHERE conversation_id=? AND seq IN (SELECT seq FROM conversation_items WHERE conversation_id=? AND item_kind NOT IN $RESULT_ONLY_KINDS ORDER BY sort_seq,sort_sub,seq LIMIT ?)",
            arrayOf(conversationId, conversationId, (maxRowIndex + 1).coerceAtLeast(0))).use { c ->
            buildSet { while (c.moveToNext()) { val source = c.getString(1); if (source.startsWith("connector") || source in setOf("mcp", "delegate", "web")) c.getStringOrNullMemory("owner_user_message_id")?.let(::add) } }
        }
        val transcript = ArrayList<ReflectionTranscriptBuilder.Entry>()
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > maxRowIndex) return@forEachIndexed
            if (row.role == "user" || row.role == "assistant") {
                val id = row.messageId ?: return@forEachIndexed
                val messageIndex = indexById[id] ?: return@forEachIndexed
                if (messageIndex <= checkpointIndex || messageIndex > end || row.role == "assistant" && id in hidden) return@forEachIndexed
                val text = if (row.role == "assistant" && row.ownerUserMessageId != null && row.ownerUserMessageId in connectorUsers)
                    "[assistant response following connector/MCP data omitted; use user-authored confirmation only]" else row.text.orEmpty()
                transcript += ReflectionTranscriptBuilder.Entry(row.role, text,
                    if (row.role == "assistant") "context_only" else "user_authored", id, reflectionTimestamp(row.createdAt), "", "", true)
            } else if (row.kind == "reflection_tool") {
                val marker = readReflectionMarker(conversationId, row.seq) ?: return@forEachIndexed
                val userIndex = indexById[marker.owner_user_message_id] ?: -1
                if (userIndex <= checkpointIndex || userIndex > end) return@forEachIndexed
                transcript += ReflectionTranscriptBuilder.Entry("tool_call", marker.marker, marker.source,
                    marker.owner_user_message_id.orEmpty(), reflectionTimestamp(marker.created_at), marker.tool_name,
                    marker.call_id.orEmpty(), marker.stage == "tool_result")
            }
        }
        val serialized = ReflectionTranscriptBuilder.build(transcript)
        val includedEnd = ReflectionTranscriptBuilder.lastMessageId(serialized)
        val includedIndex = indexById[includedEnd] ?: -1
        if (includedIndex < 0) return@withTransaction ReflectionPayloadSnapshot(serialized, "", emptyList(), 0, 0, 0, end >= 0)
        val hasMore = includedIndex < end
        var userCharacters = 0
        var completedSteps = 0
        var messageCount = 0
        for (index in checkpointIndex + 1..includedIndex) {
            val message = messages[index]
            if (message.role == "assistant" && message.id in hidden) continue
            if (message.role == "user" && !message.text.startsWith("[message omitted:")) userCharacters += sanitizeForReflection(message.text).length
            if (message.role == "assistant" && (message.status.isEmpty() || message.status == "COMPLETED")) completedSteps++
            messageCount++
        }
        val includedRowIndex = messages[includedIndex].rowIndex
        val boundary = if (hasMore) includedRowIndex + 1 else maxRowIndex + 1
        val compacted = pendingCompactionIdsBefore(conversationId, rows, boundary)
        ReflectionPayloadSnapshot(serialized, includedEnd, compacted, completedSteps, userCharacters, messageCount, hasMore)
    }

    /** Appends a `crew_snapshot`, replacing `LocalRunStore.appendCrewMissionSnapshot` (`LocalRunStore.java:175-198`); identical digest is a no-op and a later digest replaces that mission's normalized children. */
    suspend fun appendCrewSnapshot(conversationId: String, row: JSONObject): ChatDbResult<CrewMissionSnapshot> = transaction {
        if (conversationId.isBlank() || row.optString("type") != "crew_snapshot") return@transaction ChatDbResult.Invalid("crew_snapshot row and conversation are required")
        ensureConversationExists(conversationId)
        val snapshot = CrewMissionSnapshot.fromJson(row) ?: return@transaction ChatDbResult.Invalid("crew_snapshot schema or payload is unsupported")
        if (snapshot.conversationId != conversationId || snapshot.missionId.isBlank()) return@transaction ChatDbResult.Invalid("crew_snapshot identity does not match its conversation")
        val digest = losslessSnapshotDigest(row)
        val existing = readMissionHeader(conversationId, snapshot.missionId)
        if (existing != null && existing.snapshot_digest == digest) return@transaction ChatDbResult.Success(readCrewSnapshot(conversationId, snapshot.missionId)!!)
        verifySnapshotEvidence(snapshot)
        val time = row.optDouble("timestamp", nowSeconds())
        val seq = insertLedgerItem(conversationId, "crew_snapshot", snapshot.missionId, time)
        if (existing == null) {
            sql.execSQL("INSERT INTO crew_missions(conversation_id,mission_id,first_snapshot_seq,last_snapshot_seq,schema_version,process_id,snapshot_digest,title,original_instructions,title_source,status,synthesis,started_at_ms,finished_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(conversationId, snapshot.missionId, seq, seq, row.optInt("crewSchemaVersion"), snapshot.processId,
                    digest, snapshot.title, snapshot.originalInstructions, snapshot.titleSource, snapshot.status,
                    ExactUtf16.encode(snapshot.synthesis), snapshot.startedAtMillis, snapshot.finishedAtMillis.takeIf { it > 0L }))
        } else {
            sql.execSQL("DELETE FROM crew_bots WHERE conversation_id=? AND mission_id=?", arrayOf(conversationId, snapshot.missionId))
            sql.execSQL("DELETE FROM crew_messages WHERE conversation_id=? AND mission_id=?", arrayOf(conversationId, snapshot.missionId))
            sql.execSQL("UPDATE crew_missions SET last_snapshot_seq=?,schema_version=?,process_id=?,snapshot_digest=?,title=?,original_instructions=?,title_source=?,status=?,synthesis=?,started_at_ms=?,finished_at_ms=? WHERE conversation_id=? AND mission_id=?",
                arrayOf(seq, row.optInt("crewSchemaVersion"), snapshot.processId, digest, snapshot.title, snapshot.originalInstructions,
                    snapshot.titleSource, snapshot.status, ExactUtf16.encode(snapshot.synthesis), snapshot.startedAtMillis,
                    snapshot.finishedAtMillis.takeIf { it > 0L }, conversationId, snapshot.missionId))
        }
        insertCrewChildren(conversationId, snapshot)
        ChatDbResult.Success(readCrewSnapshot(conversationId, snapshot.missionId)!!)
    }

    /** Returns current snapshots in `LinkedHashMap` first-insertion order (`LocalRunStore.java:1056-1094`). */
    suspend fun readCrewMissionSnapshots(conversationId: String): List<CrewMissionSnapshot> = database.withTransaction {
        val ids = sql.query("SELECT mission_id FROM crew_missions WHERE conversation_id=? ORDER BY first_snapshot_seq",
            arrayOf(conversationId)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        ids.mapNotNull { readCrewSnapshot(conversationId, it) }
    }

    /** Active snapshots from another process are persisted as interrupted, matching `LocalRunStore.recoverCrewMissions` (`LocalRunStore.java:1167-1173`). */
    suspend fun recoverCrewMissions(conversationId: String, currentProcessId: String = com.jarvys.agent.crew.CrewProcessIdentity.ID): List<CrewMissionSnapshot> {
        val snapshots = readCrewMissionSnapshots(conversationId)
        val recovered = ArrayList<CrewMissionSnapshot>(snapshots.size)
        for (snapshot in snapshots) {
            val value = if (snapshot.processId != currentProcessId) snapshot.interrupted() else snapshot
            if (value !== snapshot) appendCrewSnapshot(conversationId, value.toJson()).requireSuccess()
            recovered += value
        }
        return recovered
    }

    /**
     * Reads global resolved `decision`, `reaction`, consumed proactive-message and latest
     * `crew_snapshot` markers for `LocalRunStore.projectConversationTimeline`
     * (`LocalRunStore.java:4140-4166`), plus pending `main_interrupt_request` rows from
     * `LocalRunStore.readPendingInterruptRequests()` (`LocalRunStore.java:2816-2819`), in SQL without
     * scanning timeline pages.
     */
    suspend fun readGlobalTimelineMarkers(): GlobalTimelineMarkers = database.withTransaction {
        val decisions = LinkedHashMap<Pair<String, String>, DecisionResolutionMarker>()
        sql.query("SELECT conversation_id,decision_id,status,option_id,option_label FROM user_decisions WHERE status IS NOT NULL ORDER BY resolved_seq",
            emptyArray()).use { c -> while (c.moveToNext()) decisions[c.getString(0) to c.getString(1)] = DecisionResolutionMarker(
            c.getString(2), c.getStringOrNullMemory("option_id"), c.getStringOrNullMemory("option_label")) }
        val reactions = LinkedHashMap<Pair<String, String>, String>()
        sql.query("SELECT conversation_id,target_stable_id,emoji FROM message_reactions WHERE emoji<>'' ORDER BY seq", emptyArray()).use { c ->
            while (c.moveToNext()) reactions[c.getString(0) to c.getString(1)] = c.getString(2)
        }
        val usedReplies = sql.query("SELECT conversation_id,message_id FROM messages WHERE suggested_replies_consumed_seq IS NOT NULL ORDER BY suggested_replies_consumed_seq", emptyArray())
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        val crew = sql.query("SELECT conversation_id,mission_id,first_snapshot_seq,last_snapshot_seq FROM crew_missions ORDER BY first_snapshot_seq", emptyArray())
            .use { c -> buildList { while (c.moveToNext()) add(CrewSnapshotMarker(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3))) } }
        val interrupts = pendingInterruptRequestsInternal()
        GlobalTimelineMarkers(decisions, reactions, usedReplies, crew, interrupts)
    }

    /** Reads pending `main_interrupt_request` rows across conversations, replacing `LocalRunStore.readPendingInterruptRequests()` (`LocalRunStore.java:2816-2819`). */
    suspend fun pendingInterruptRequests(): List<PendingInterrupt> = database.withTransaction { pendingInterruptRequestsInternal() }

    private fun latestActiveCompaction(conversationId: String): Compaction? = sql.query(
        "SELECT c.* FROM compactions c JOIN conversation_items ci ON ci.conversation_id=c.conversation_id AND ci.seq=c.seq " +
            "WHERE c.conversation_id=? AND NOT EXISTS (SELECT 1 FROM messages m JOIN conversation_items hi " +
            "ON hi.conversation_id=m.conversation_id AND hi.seq=m.hidden_seq WHERE m.conversation_id=c.conversation_id " +
            "AND m.hidden_kind IN ('assistant_regenerated','assistant_deleted') AND " +
            "(hi.sort_seq>ci.sort_seq OR (hi.sort_seq=ci.sort_seq AND hi.sort_sub>ci.sort_sub) " +
            "OR (hi.sort_seq=ci.sort_seq AND hi.sort_sub=ci.sort_sub AND hi.seq>ci.seq))) " +
            "ORDER BY ci.sort_seq DESC,ci.sort_sub DESC,ci.seq DESC LIMIT 1",
        arrayOf(conversationId),
    ).use { if (it.moveToFirst()) readCompaction(it) else null }

    private suspend fun appendReflection(input: ReflectionInput, kind: String): ChatDbResult<Long> = transaction {
        if (input.conversationId.isBlank() || kind !in setOf("reflection_checkpoint", "reflection_commit", "reflection_event") ||
            (kind != "reflection_event" && input.throughMessageId.isNullOrBlank()) ||
            (kind != "reflection_checkpoint" && input.reflectionId.isNullOrBlank()))
            return@transaction ChatDbResult.Invalid("reflection fields do not satisfy the ledger row shape")
        ensureConversationExists(input.conversationId)
        val time = input.createdAtSeconds ?: nowSeconds()
        val key = input.reflectionId ?: input.throughMessageId.orEmpty()
        val seq = insertLedgerItem(input.conversationId, kind, key, time, ownerMessageSeq(input.conversationId, input.throughMessageId))
        sql.execSQL("INSERT INTO reflections(conversation_id,seq,kind,reflection_id,through_message_id,completed_assistant_steps,result,summary,status,trigger,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(input.conversationId, seq, kind, input.reflectionId, input.throughMessageId, input.completedAssistantSteps,
                input.result ?: if (kind == "reflection_checkpoint") "" else null,
                input.summary ?: if (kind != "reflection_checkpoint") "" else null,
                input.status ?: if (kind == "reflection_commit" || kind == "reflection_event") "completed" else null,
                input.trigger ?: if (kind == "reflection_commit") "manual" else null, time))
        input.revisionIds.forEachIndexed { index, revision ->
            sql.execSQL("INSERT INTO reflection_revisions(conversation_id,reflection_seq,ordinal,revision_id) VALUES(?,?,?,?)",
                arrayOf(input.conversationId, seq, index, revision))
        }
        if (input.acknowledgedCompactionIds.isNotEmpty()) insertCompactionAcks(input.conversationId, seq, kind, input.acknowledgedCompactionIds.distinct(), time)
        ChatDbResult.Success(seq)
    }

    private fun insertCompactionAcks(conversationId: String, seq: Long, source: String, ids: List<String>, time: Double) {
        ids.forEach { id ->
            if (readCompaction(conversationId, id) == null) throw M1bAbort("acknowledgement_names_unknown_compaction")
            sql.execSQL("INSERT INTO compaction_reflection_acks(conversation_id,compaction_id,seq,source,created_at) VALUES(?,?,?,?,?)",
                arrayOf(conversationId, id, seq, source, time))
        }
    }

    private fun pendingCompactionIdsBefore(conversationId: String, rows: List<TimelineRow>, rowBoundaryExclusive: Int): List<String> {
        val ids = LinkedHashSet<String>()
        rows.take(rowBoundaryExclusive.coerceAtLeast(0)).forEach { row ->
            if (row.kind == "compaction") ids.add(row.itemKey)
            else if (row.kind in setOf("reflection_checkpoint", "reflection_commit", "compaction_reflection_consumed")) {
                sql.query("SELECT compaction_id FROM compaction_reflection_acks WHERE conversation_id=? AND seq=?", arrayOf(conversationId, row.seq))
                    .use { c -> while (c.moveToNext()) ids.remove(c.getString(0)) }
            }
        }
        return ids.toList()
    }

    private fun readTimelineRows(conversationId: String): List<TimelineRow> = sql.query(
        "SELECT i.seq,i.item_kind,i.item_key,i.sort_seq,i.sort_sub,i.created_at,m.message_id,m.role,m.status,m.owner_user_message_id " +
            "FROM conversation_items i LEFT JOIN messages m ON m.conversation_id=i.conversation_id AND m.seq=i.seq " +
            "WHERE i.conversation_id=? AND i.item_kind NOT IN $RESULT_ONLY_KINDS " +
            "ORDER BY i.sort_seq,i.sort_sub,i.seq", arrayOf(conversationId),
    ).use { c -> buildList {
        while (c.moveToNext()) add(TimelineRow(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4),
            c.getDouble(5), c.getStringOrNullMemory("message_id"), c.getStringOrNullMemory("role"),
            c.getStringOrNullMemory("status"), c.getStringOrNullMemory("owner_user_message_id"),
            c.getStringOrNullMemory("message_id")?.let { readMessageText(conversationId, it) }))
    } }

    private fun readMessageText(conversationId: String, messageId: String): String = sql.query(
        "SELECT payload FROM message_parts WHERE conversation_id=? AND message_id=? AND kind='text' ORDER BY ordinal",
        arrayOf(conversationId, messageId),
    ).use { c -> buildString { while (c.moveToNext()) append(c.getString(0)) } }

    private fun readReflectionMarker(conversationId: String, seq: Long): ReflectionToolMarker? = sql.query(
        "SELECT * FROM reflection_tool_markers WHERE conversation_id=? AND seq=?", arrayOf(conversationId, seq),
    ).use { if (it.moveToFirst()) ReflectionToolMarker(it.getString(0), it.getLong(1), it.getStringOrNullMemory("owner_user_message_id"),
        it.getStringOrNullMemory("batch_id"), it.getStringOrNullMemory("call_id"), it.getString(5), it.getString(6), it.getString(7), it.getString(8), it.getDouble(9)) else null }

    private fun sanitizeForReflection(text: String): String {
        val entry = ReflectionTranscriptBuilder.Entry("user", text, "user_authored")
        return JSONArray(ReflectionTranscriptBuilder.build(listOf(entry))).optJSONObject(0)?.optString("text", text) ?: text
    }

    private fun pendingInterruptRequestsInternal(): List<PendingInterrupt> {
        val pending = sql.query(
            "SELECT r.conversation_id,r.request_id,r.seq,r.content,r.memory_disabled," +
                "CASE WHEN r.dispatched_seq<=c.next_seq-1 THEN r.dispatched_message_id END AS dispatched_message_id," +
                "CASE WHEN r.dispatched_seq<=c.next_seq-1 THEN r.dispatched_seq END AS dispatched_seq," +
                "CASE WHEN r.input_state_seq<=c.next_seq-1 THEN r.input_state END AS input_state," +
                "CASE WHEN r.dispatched_seq<=c.next_seq-1 THEN COALESCE(m.ack_required,0) ELSE 0 END AS ack_required " +
                "FROM interrupt_requests r JOIN conversations c ON c.id=r.conversation_id " +
                "LEFT JOIN messages m ON m.conversation_id=r.conversation_id AND m.message_id=r.dispatched_message_id " +
                "WHERE r.seq<=c.next_seq-1 ORDER BY r.conversation_id,r.seq",
            emptyArray(),
        ).use { c -> buildList {
            while (c.moveToNext()) {
                val dispatched = c.getLongOrNullMemory("dispatched_seq")
                val inputState = c.getStringOrNullMemory("input_state")
                val ackRequired = c.getInt(c.getColumnIndexOrThrow("ack_required")) != 0
                if (inputState == "main_chat_input_resolved" || inputState == "main_chat_input_incorporated") continue
                if (dispatched != null && !ackRequired) continue
                add(PendingInterrupt(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getInt(4) != 0,
                    emptyList(), c.getStringOrNullMemory("dispatched_message_id"), dispatched, inputState, dispatched != null, ackRequired))
            }
        } }
        if (pending.isEmpty()) return emptyList()
        val skills = mutableMapOf<Pair<String, String>, MutableList<String>>()
        pending.forEach { request ->
            sql.query("SELECT skill_id FROM interrupt_request_skills WHERE conversation_id=? AND request_id=? ORDER BY ordinal",
                arrayOf(request.conversationId, request.requestId)).use { c -> while (c.moveToNext()) skills.getOrPut(request.conversationId to request.requestId) { ArrayList() }.add(c.getString(0)) }
        }
        return pending.map { it.copy(skills = skills[it.conversationId to it.requestId].orEmpty()) }
    }

    private fun readCompaction(conversationId: String, id: String): Compaction? = sql.query(
        "SELECT * FROM compactions WHERE conversation_id=? AND compaction_id=?", arrayOf(conversationId, id),
    ).use { if (it.moveToFirst()) readCompaction(it) else null }

    private fun readCompaction(c: android.database.Cursor) = Compaction(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getLong(4), c.getLong(5), c.getString(6), c.getString(7), c.getDouble(8))

    private fun readMissionHeader(conversationId: String, missionId: String): CrewMission? = sql.query(
        "SELECT * FROM crew_missions WHERE conversation_id=? AND mission_id=?", arrayOf(conversationId, missionId),
    ).use { if (it.moveToFirst()) CrewMission(it.getString(0), it.getString(1), it.getLong(2), it.getLong(3), it.getInt(4),
        it.getStringOrNullMemory("process_id"), it.getString(6), it.getString(7), it.getString(8), it.getStringOrNullMemory("title_source"),
        it.getString(10), it.getBlobOrNullMemory("synthesis"), it.getLong(12), it.getLongOrNullMemory("finished_at_ms")) else null }

    private fun readCrewSnapshot(conversationId: String, missionId: String): CrewMissionSnapshot? {
        val mission = readMissionHeader(conversationId, missionId) ?: return null
        val bots = sql.query("SELECT * FROM crew_bots WHERE conversation_id=? AND mission_id=? ORDER BY ordinal", arrayOf(conversationId, missionId)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val botId = c.getString(c.getColumnIndexOrThrow("bot_id"))
                    val tools = sql.query("SELECT tool FROM crew_bot_tools WHERE conversation_id=? AND mission_id=? AND bot_id=? ORDER BY ordinal",
                        arrayOf(conversationId, missionId, botId)).use { t -> buildList { while (t.moveToNext()) add(t.getString(0)) } }
                    val evidenceId = c.getStringOrNullMemory("result_evidence_id")
                    add(CrewBotSnapshot(botId, c.getStringOrNullMemory("role_id"), c.getStringOrNullMemory("role_name"), c.getString(c.getColumnIndexOrThrow("name")),
                        c.getStringOrNullMemory("color_key"), c.getStringOrNullMemory("mission"), c.getString(c.getColumnIndexOrThrow("status")),
                        c.getBlobOrNullMemory("error")?.let(ExactUtf16::decode), c.getBlobOrNullMemory("result")?.let(ExactUtf16::decode),
                        c.getStringOrNullMemory("waiting_reason"), tools, c.getLongOrNullMemory("started_at_ms") ?: 0L,
                        c.getLongOrNullMemory("finished_at_ms") ?: 0L, c.getInt(c.getColumnIndexOrThrow("can_resume")) != 0,
                        c.getStringOrNullMemory("recovery_note"), c.getInt(c.getColumnIndexOrThrow("resume_required")) != 0,
                        c.getStringOrNullMemory("phase"), c.getStringOrNullMemory("last_progress"),
                        c.getLongOrNullMemory("last_progress_at_ms") ?: 0L, c.getStringOrNullMemory("cycle_id"),
                        evidenceId?.let { readCrewResultEvidenceJson(conversationId, it) }))
                }
            }
        }
        val messages = sql.query("SELECT * FROM crew_messages WHERE conversation_id=? AND mission_id=? ORDER BY ordinal", arrayOf(conversationId, missionId)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val messageId = c.getString(c.getColumnIndexOrThrow("message_id"))
                    val json = JSONObject().put("id", messageId).put("conversationId", conversationId)
                        .put("from", c.getStringOrNullMemory("from_bot") ?: "").put("to", c.getStringOrNullMemory("to_bot") ?: "")
                        .put("type", c.getString(c.getColumnIndexOrThrow("type"))).put("text", ExactUtf16.decode(c.getBlob(c.getColumnIndexOrThrow("text"))))
                        .put("timestampMillis", c.getLong(c.getColumnIndexOrThrow("timestamp_ms")))
                    val refs = JSONArray()
                    sql.query("SELECT ref FROM crew_message_refs WHERE conversation_id=? AND mission_id=? AND message_id=? ORDER BY ordinal",
                        arrayOf(conversationId, missionId, messageId)).use { r -> while (r.moveToNext()) refs.put(r.getString(0)) }
                    json.put("refs", refs)
                    c.getStringOrNullMemory("coordination_evidence_id")?.let { id -> json.put("coordination", readCoordinationReferenceJson(conversationId, id)) }
                    sql.query("SELECT * FROM crew_message_input_bindings WHERE conversation_id=? AND mission_id=? AND message_id=?",
                        arrayOf(conversationId, missionId, messageId)).use { b -> if (b.moveToFirst()) json.put("inputBinding", inputBindingJson(b)) }
                    sql.query("SELECT * FROM crew_message_activities WHERE conversation_id=? AND mission_id=? AND message_id=?",
                        arrayOf(conversationId, missionId, messageId)).use { a -> if (a.moveToFirst()) {
                        json.put("origin", "local_activity")
                        json.put("activity", JSONObject().put("schemaVersion", a.getInt(a.getColumnIndexOrThrow("schema_version")))
                            .put("eventId", a.getString(a.getColumnIndexOrThrow("event_id"))).put("executionId", a.getString(a.getColumnIndexOrThrow("execution_id")))
                            .put("callId", a.getString(a.getColumnIndexOrThrow("call_id"))).put("toolName", a.getString(a.getColumnIndexOrThrow("tool_name")))
                            .put("displayName", a.getString(a.getColumnIndexOrThrow("display_name"))).put("skillId", a.getString(a.getColumnIndexOrThrow("skill_id")))
                            .put("skillName", a.getString(a.getColumnIndexOrThrow("skill_name"))).put("stage", a.getString(a.getColumnIndexOrThrow("stage")))
                            .put("detail", a.getString(a.getColumnIndexOrThrow("detail"))).put("previewId", a.getString(a.getColumnIndexOrThrow("preview_id")))
                            .put("auditDetail", a.getString(a.getColumnIndexOrThrow("audit_detail"))).put("reflectionSource", a.getString(a.getColumnIndexOrThrow("reflection_source")))
                            .put("timestampMillis", a.getLong(a.getColumnIndexOrThrow("timestamp_millis"))))
                    } }
                    add(CrewMessage.fromJson(json))
                }
            }
        }
        return CrewMissionSnapshot(mission.mission_id, mission.conversation_id, mission.process_id, mission.title,
            mission.original_instructions, mission.title_source, mission.status, mission.synthesis?.let(ExactUtf16::decode) ?: "",
            mission.started_at_ms, mission.finished_at_ms ?: 0L, bots, messages)
    }

    private fun insertCrewChildren(conversationId: String, snapshot: CrewMissionSnapshot) {
        snapshot.bots.forEachIndexed { ordinal, bot ->
            sql.execSQL("INSERT INTO crew_bots(conversation_id,mission_id,bot_id,ordinal,cycle_id,role_id,role_name,name,color_key,mission,status,error,result,waiting_reason,phase,last_progress,last_progress_at_ms,resume_required,can_resume,recovery_note,started_at_ms,finished_at_ms,result_evidence_id,evidence_content_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(conversationId, snapshot.missionId, bot.id, ordinal, bot.cycleId, bot.roleId, bot.roleName, bot.name, bot.colorKey,
                    bot.mission, bot.status, ExactUtf16.encode(bot.error), ExactUtf16.encode(bot.result), bot.waitingReason, bot.phase,
                    bot.lastProgress, bot.lastProgressAtMillis, bot.resumeRequired, bot.canResume, bot.recoveryNote,
                    bot.startedAtMillis, bot.finishedAtMillis, bot.resultEvidence?.evidenceId, bot.resultEvidence?.contentSha256))
            bot.tools.forEachIndexed { toolOrdinal, tool ->
                sql.execSQL("INSERT INTO crew_bot_tools(conversation_id,mission_id,bot_id,ordinal,tool) VALUES(?,?,?,?,?)",
                    arrayOf(conversationId, snapshot.missionId, bot.id, toolOrdinal, tool))
            }
        }
        snapshot.messages.forEachIndexed { ordinal, message ->
            val coordination = message.coordination
            sql.execSQL("INSERT INTO crew_messages(conversation_id,mission_id,ordinal,message_id,from_bot,to_bot,type,text,timestamp_ms,origin,coordination_evidence_id,reference_message_id,evidence_content_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(conversationId, snapshot.missionId, ordinal, message.id, message.from, message.to, message.type.name,
                    ExactUtf16.encode(message.text), message.timestampMillis, if (message.activity != null) "local_activity" else null,
                    coordination?.evidenceId, coordination?.messageId, coordination?.contentSha256))
            message.refs.forEachIndexed { refOrdinal, ref ->
                sql.execSQL("INSERT INTO crew_message_refs(conversation_id,mission_id,message_id,ordinal,ref) VALUES(?,?,?,?,?)",
                    arrayOf(conversationId, snapshot.missionId, message.id, refOrdinal, ref))
            }
            message.inputBinding?.let { binding ->
                sql.execSQL("INSERT INTO crew_message_input_bindings(conversation_id,mission_id,message_id,schema_version,source_kind,source_id,owner_central_session_id,workspace_id,execution_session_id,assignment_id,input_mission_id,bot_id,mailbox_message_id,source_payload_sha256,delivery_sha256,admission_cycle_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf(conversationId, snapshot.missionId, message.id, 1, binding.sourceKind.name, binding.sourceId,
                        binding.ownerCentralSessionId, binding.workspaceId, binding.executionSessionId, binding.assignmentId,
                        binding.missionId, binding.botId, binding.mailboxMessageId, binding.sourcePayloadSha256,
                        binding.deliverySha256, binding.admissionCycleId))
            }
            message.activity?.let { activity ->
                sql.execSQL("INSERT INTO crew_message_activities(conversation_id,mission_id,message_id,schema_version,event_id,execution_id,call_id,tool_name,display_name,skill_id,skill_name,stage,detail,preview_id,audit_detail,reflection_source,timestamp_millis) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf(conversationId, snapshot.missionId, message.id, 1, activity.eventId, activity.executionId, activity.callId,
                        activity.toolName, activity.displayName, activity.skillId, activity.skillName, activity.stage, activity.detail,
                        activity.previewId, activity.auditDetail, activity.reflectionSource, activity.timestampMillis))
            }
        }
    }

    private fun verifySnapshotEvidence(snapshot: CrewMissionSnapshot) {
        snapshot.bots.forEach { bot -> bot.resultEvidence?.let { evidence ->
            val matches = sql.query("SELECT 1 FROM crew_result_evidence WHERE conversation_id=? AND evidence_id=? AND mission_id=? AND bot_id=? AND cycle_id=? AND content_sha256=?",
                arrayOf(snapshot.conversationId, evidence.evidenceId, snapshot.missionId, bot.id, bot.cycleId, evidence.contentSha256)).use { it.moveToFirst() }
            if (!matches) throw M1bAbort("crew_snapshot_result_evidence_mismatch")
        } }
        snapshot.messages.forEach { message -> message.coordination?.let { reference ->
            val matches = sql.query("SELECT 1 FROM crew_coordination_evidence WHERE conversation_id=? AND evidence_id=? AND mission_id=? AND bot_id=? AND cycle_id=? AND message_id=? AND content_sha256=?",
                arrayOf(snapshot.conversationId, reference.evidenceId, snapshot.missionId, reference.botId, reference.cycleId,
                    reference.messageId, reference.contentSha256)).use { it.moveToFirst() }
            if (!matches) throw M1bAbort("crew_snapshot_coordination_evidence_mismatch")
        } }
    }

    private fun readCrewResultEvidenceJson(conversationId: String, id: String): com.jarvys.agent.crew.CrewResultEvidence {
        val row = sql.query("SELECT * FROM crew_result_evidence WHERE conversation_id=? AND evidence_id=?", arrayOf(conversationId, id)).use { c ->
            if (!c.moveToFirst()) throw IllegalStateException("crew_snapshot_result_evidence_missing")
            JSONObject().put("type", "crew_result_evidence").put("crewResultSchemaVersion", c.getInt(c.getColumnIndexOrThrow("schema_version")))
                .put("evidenceId", id).put("conversationId", conversationId).put("missionId", c.getString(c.getColumnIndexOrThrow("mission_id")))
                .put("botId", c.getString(c.getColumnIndexOrThrow("bot_id"))).put("cycleId", c.getString(c.getColumnIndexOrThrow("cycle_id")))
                .put("reportedStatus", c.getString(c.getColumnIndexOrThrow("reported_status")))
                .put("resultText", ExactUtf16.decode(c.getBlob(c.getColumnIndexOrThrow("result_text"))))
                .put("producedAtMillis", c.getLong(c.getColumnIndexOrThrow("produced_at_ms")))
                .put("contentSha256", c.getString(c.getColumnIndexOrThrow("content_sha256")))
        }
        return com.jarvys.agent.crew.CrewResultEvidence.fromJsonStrict(row)
    }

    private fun readCoordinationReferenceJson(conversationId: String, id: String): JSONObject = sql.query(
        "SELECT * FROM crew_messages WHERE conversation_id=? AND coordination_evidence_id=? LIMIT 1", arrayOf(conversationId, id),
    ).use { c ->
        if (!c.moveToFirst()) throw IllegalStateException("crew_snapshot_coordination_reference_missing")
        JSONObject().put("schemaVersion", 1).put("evidenceId", id).put("conversationId", conversationId)
            .put("missionId", c.getString(c.getColumnIndexOrThrow("mission_id")))
            .put("botId", sql.query("SELECT bot_id FROM crew_coordination_evidence WHERE conversation_id=? AND evidence_id=?", arrayOf(conversationId, id)).use { e -> if (e.moveToFirst()) e.getString(0) else "" })
            .put("cycleId", sql.query("SELECT cycle_id FROM crew_coordination_evidence WHERE conversation_id=? AND evidence_id=?", arrayOf(conversationId, id)).use { e -> if (e.moveToFirst()) e.getString(0) else "" })
            .put("messageId", c.getString(c.getColumnIndexOrThrow("reference_message_id")))
            .put("contentSha256", c.getString(c.getColumnIndexOrThrow("evidence_content_sha256")))
    }

    private fun inputBindingJson(c: android.database.Cursor): JSONObject = JSONObject()
        .put("schemaVersion", c.getInt(c.getColumnIndexOrThrow("schema_version")))
        .put("sourceKind", c.getString(c.getColumnIndexOrThrow("source_kind")))
        .put("sourceId", c.getString(c.getColumnIndexOrThrow("source_id")))
        .put("ownerCentralSessionId", c.getString(c.getColumnIndexOrThrow("owner_central_session_id")))
        .put("workspaceId", c.getString(c.getColumnIndexOrThrow("workspace_id")))
        .put("executionSessionId", c.getString(c.getColumnIndexOrThrow("execution_session_id")))
        .put("assignmentId", c.getString(c.getColumnIndexOrThrow("assignment_id")))
        .put("missionId", c.getString(c.getColumnIndexOrThrow("input_mission_id")))
        .put("botId", c.getString(c.getColumnIndexOrThrow("bot_id")))
        .put("mailboxMessageId", c.getString(c.getColumnIndexOrThrow("mailbox_message_id")))
        .put("sourcePayloadSha256", c.getString(c.getColumnIndexOrThrow("source_payload_sha256")))
        .put("deliverySha256", c.getString(c.getColumnIndexOrThrow("delivery_sha256")))
        .put("admissionCycleId", c.getString(c.getColumnIndexOrThrow("admission_cycle_id")))

    private fun timelineRowCountBefore(conversationId: String, sortSeq: Long, sortSub: Int): Long = sql.query(
        "SELECT COUNT(*) FROM conversation_items WHERE conversation_id=? AND item_kind NOT IN $RESULT_ONLY_KINDS AND " +
            "(sort_seq<? OR (sort_seq=? AND sort_sub<?) OR (sort_seq=? AND sort_sub=? AND seq<?))",
        arrayOf(conversationId, sortSeq, sortSeq, sortSub, sortSeq, sortSub, sortSeq),
    ).use { it.moveToFirst(); it.getLong(0) }

    private fun compactionPrecedesAppend(conversationId: String, compactionSeq: Long, nextSeq: Long): Boolean = sql.query(
        "SELECT EXISTS(SELECT 1 FROM conversation_items prior WHERE prior.conversation_id=? AND prior.seq=? AND " +
            "(prior.sort_seq<? OR (prior.sort_seq=? AND prior.sort_sub<?) OR " +
            "(prior.sort_seq=? AND prior.sort_sub=? AND prior.seq<?)))",
        arrayOf(conversationId, compactionSeq, nextSeq, nextSeq, 0, nextSeq, 0, nextSeq),
    ).use { it.moveToFirst(); it.getInt(0) != 0 }

    private fun nextSequence(conversationId: String): Long = sql.query("SELECT next_seq FROM conversations WHERE id=?", arrayOf(conversationId))
        .use { if (it.moveToFirst()) it.getLong(0) else throw M1bAbort("conversation_not_found") }

    private fun jsonNumberToken(value: Double): String {
        val serialized = JSONObject().put("timestamp", value).toString()
        return serialized.substringAfter(':').removeSuffix("}")
    }

    private fun losslessSnapshotDigest(row: JSONObject): String {
        val copy = JSONObject(row.toString()).apply { remove("timestamp") }
        val serialized = copy.toString()
        val escaped = buildString(serialized.length + 1) {
            serialized.forEach { char -> if (Character.isSurrogate(char)) append("\\u").append(char.code.toString(16).padStart(4, '0')) else append(char) }
            append('\n')
        }
        return MessageDigest.getInstance("SHA-256").digest(escaped.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun reflectionTimestamp(seconds: Double): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return format.format(Date((seconds * 1_000.0).toLong()))
    }

    private data class TimelineRow(
        val seq: Long, val kind: String, val itemKey: String, val sortSeq: Long, val sortSub: Int, val createdAt: Double,
        val messageId: String?, val role: String?, val status: String?, val ownerUserMessageId: String?, val text: String?,
    )
    private data class ReflectionMessage(val id: String, val role: String, val text: String, val status: String, val ownerUserMessageId: String, val rowIndex: Int)

    companion object {
        /** Mirrors `LocalRunStore.isResultOnlyRow` over the persisted row types (`LocalRunStore.java:3002-3010`). */
        private const val RESULT_ONLY_KINDS = "('crew_result_evidence','workspace_result_reference','workspace_result_notice','crew_coordination_evidence','crew_coordination_evidence_ref','workspace_coordination_reference','workspace_coordination_notice','workspace_coordination_reply_intent','workspace_coordination_reply_state','workspace_input_incorporation')"
    }
}

data class DecisionResolutionMarker(val status: String, val optionId: String?, val optionLabel: String?)
data class GlobalTimelineMarkers(
    val resolvedDecisions: Map<Pair<String, String>, DecisionResolutionMarker>,
    val reactionsByStableMessage: Map<Pair<String, String>, String>,
    val consumedProactiveReplies: List<Pair<String, String>>,
    val latestCrewSnapshots: List<CrewSnapshotMarker>,
    val pendingInterrupts: List<PendingInterrupt>,
)

private fun android.database.Cursor.getStringOrNullMemory(name: String): String? = getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getString(it) }
private fun android.database.Cursor.getLongOrNullMemory(name: String): Long? = getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getLong(it) }
private fun android.database.Cursor.getBlobOrNullMemory(name: String): ByteArray? = getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getBlob(it) }

private fun <T> ChatDbResult<T>.requireSuccess(): T = when (this) {
    is ChatDbResult.Success -> value
    is ChatDbResult.NotFound -> error("unexpected not found")
    is ChatDbResult.Tombstoned -> error("conversation tombstoned")
    is ChatDbResult.Conflict -> error(reason)
    is ChatDbResult.Invalid -> error(reason)
}
