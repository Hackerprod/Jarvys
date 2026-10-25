package com.jarvys.agent.chatdb

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
    /** Artifacts holding the summarized source text; stored as `compaction` owners of the protected closure. */
    val artifactIds: List<String> = emptyList(),
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
     * Adds one summary at its timeline position. The persisted ID includes the append timestamp and
     * count of existing timeline rows so readers can recover its summary boundary deterministically.
     */
    suspend fun appendCompaction(input: CompactionInput): ChatDbResult<Compaction> = transaction {
        if (input.conversationId.isBlank() || input.summary.isBlank() || input.summary.trim().isEmpty())
            return@transaction ChatDbResult.Invalid("compaction summary and conversation are required")
        ensureConversationExists(input.conversationId)
        val time = input.createdAtSeconds ?: nowSeconds()
        val nextSeq = nextSequence(input.conversationId)
        val rowIndex = timelineRowCountBefore(input.conversationId, nextSeq, 0)
        val id = "legacy-${jsonNumberToken(time)}-$rowIndex"
        if (readCompaction(input.conversationId, id) != null) return@transaction ChatDbResult.Conflict("compaction identity already exists")
        val seq = insertLedgerItem(input.conversationId, "compaction", id, time)
        sql.execSQL(
            "INSERT INTO compactions(conversation_id,compaction_id,seq,summary,first_kept,summarized_messages,trigger,mode,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            arrayOf(input.conversationId, id, seq, input.summary, input.firstKept.coerceAtLeast(0L),
                input.summarizedMessages.coerceAtLeast(0L), input.trigger ?: "manual", input.mode ?: "all", time),
        )
        input.artifactIds.distinct().forEach { artifactId ->
            val owned = sql.query("SELECT EXISTS(SELECT 1 FROM model_artifacts WHERE conversation_id=? AND artifact_id=?)", arrayOf(input.conversationId, artifactId))
                .use { it.moveToFirst(); it.getInt(0) != 0 }
            if (!owned) throw M1bAbort("model_artifact_reference_missing")
            sql.execSQL(
                "INSERT OR IGNORE INTO transcript_artifact_refs(conversation_id,owner_kind,batch_id,call_id,artifact_id) VALUES(?,?,?,NULL,?)",
                arrayOf(input.conversationId, "compaction", id, artifactId),
            )
        }
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
    suspend fun hasReflectionCommit(conversationId: String, reflectionId: String): Boolean = JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
        sql.query("SELECT EXISTS(SELECT 1 FROM reflections WHERE conversation_id=? AND reflection_id=? AND kind='reflection_commit')",
            arrayOf(conversationId, reflectionId)).use { it.moveToFirst(); it.getInt(0) != 0 }
    } }

    /** Reads compaction IDs still awaiting reflection, in source order (`LocalRunStore.java:3468-3484`). */
    suspend fun pendingCompactionIds(conversationId: String): List<String> = JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
        val pending = LinkedHashSet<String>()
        sql.query("SELECT i.item_kind,i.item_key,i.seq,a.compaction_id FROM conversation_items i " +
            "LEFT JOIN compaction_reflection_acks a ON a.conversation_id=i.conversation_id AND a.seq=i.seq " +
            "WHERE i.conversation_id=? AND i.item_kind IN ('compaction','reflection_checkpoint','reflection_commit','compaction_reflection_consumed') " +
            "ORDER BY i.sort_seq,i.sort_sub,i.seq,a.compaction_id",
            arrayOf(conversationId)).use { c ->
            while (c.moveToNext()) {
                when (c.getString(0)) {
                    "compaction" -> pending.add(c.getString(1))
                    "reflection_checkpoint", "reflection_commit", "compaction_reflection_consumed" ->
                        c.getStringOrNullMemory("compaction_id")?.let(pending::remove)
                }
            }
        }
        pending.toList()
    } }

    /** Returns the latest active `compaction` as `loadConversationContext` does (`LocalRunStore.java:2879-2896`). */
    suspend fun activeCompaction(conversationId: String): Compaction? = JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction { latestActiveCompaction(conversationId) } }

    /**
     * SQL-backed equivalent of `LocalRunStore.buildReflectionPayload`
     * (`LocalRunStore.java:3239-3368`), including its `reflection_tool` trust markers and checkpoint boundary.
     */
    suspend fun buildReflectionPayload(conversationId: String, throughMessageId: String? = null, replayAll: Boolean = false): ReflectionPayloadSnapshot = JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
        val highWater = sql.query("SELECT COALESCE(next_seq-1,0) FROM conversations WHERE id=?", arrayOf(conversationId))
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val rows = readTimelineRows(conversationId, highWater)
        val messages = rows.mapIndexedNotNull { index, row ->
            if (row.role == "user" || row.role == "assistant") row.messageId?.let { id ->
                ReflectionMessage(id, row.role, row.text.orEmpty(), row.status.orEmpty(), row.ownerUserMessageId.orEmpty(), index)
            } else null
        }
        val indexById = LinkedHashMap<String, Int>()
        messages.forEachIndexed { index, message -> indexById[message.id] = index }
        val latestCheckpoint = if (replayAll) null else sql.query(
            "SELECT r.through_message_id FROM reflections r JOIN conversation_items i ON i.conversation_id=r.conversation_id AND i.seq=r.seq " +
                "WHERE r.conversation_id=? AND i.seq<=? AND r.kind IN ('reflection_checkpoint','reflection_commit') " +
                "ORDER BY i.sort_seq DESC,i.sort_sub DESC,i.seq DESC LIMIT 1",
            arrayOf(conversationId, highWater),
        ).use { if (it.moveToFirst()) it.getStringOrNullMemory("through_message_id") else null }
        val checkpointIndex = latestCheckpoint?.let { indexById[it] } ?: -1
        val maxRowIndex = if (throughMessageId.isNullOrEmpty()) rows.lastIndex else rows.indexOfFirst { it.messageId == throughMessageId }
        val hidden = sql.query("SELECT message_id FROM messages WHERE conversation_id=? AND hidden_seq<=? AND hidden_kind IN ('assistant_regenerated','assistant_deleted')",
            arrayOf(conversationId, highWater)).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        val end = messages.indices.reversed().firstOrNull { i -> messages[i].rowIndex <= maxRowIndex && !(messages[i].role == "assistant" && messages[i].id in hidden) } ?: -1
        val endMessageId = if (end < 0) "" else messages[end].id
        if (end <= checkpointIndex) return@withChatReadTransaction ReflectionPayloadSnapshot("[]", "", emptyList(), 0, 0, 0, false)

        val connectorUsers = sql.query("SELECT owner_user_message_id,source FROM reflection_tool_markers WHERE conversation_id=? AND seq<=? AND seq IN (SELECT seq FROM conversation_items WHERE conversation_id=? AND seq<=? AND item_kind NOT IN $RESULT_ONLY_KINDS ORDER BY sort_seq,sort_sub,seq LIMIT ?)",
            arrayOf(conversationId, highWater, conversationId, highWater, (maxRowIndex + 1).coerceAtLeast(0))).use { c ->
            buildSet { while (c.moveToNext()) { val source = c.getString(1); if (source.startsWith("connector") || source in setOf("mcp", "delegate", "web")) c.getStringOrNullMemory("owner_user_message_id")?.let(::add) } }
        }
        val reflectionMarkers = sql.query("SELECT * FROM reflection_tool_markers WHERE conversation_id=? AND seq<=?", arrayOf(conversationId, highWater)).use { c ->
            buildMap { while (c.moveToNext()) { val marker = readReflectionMarker(c); put(marker.seq, marker) } }
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
                val marker = reflectionMarkers[row.seq] ?: return@forEachIndexed
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
        if (includedIndex < 0) return@withChatReadTransaction ReflectionPayloadSnapshot(serialized, "", emptyList(), 0, 0, 0, end >= 0)
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
        val compacted = pendingCompactionIdsBefore(conversationId, rows, boundary, highWater)
        ReflectionPayloadSnapshot(serialized, includedEnd, compacted, completedSteps, userCharacters, messageCount, hasMore)
    } }

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
        persistCrewSnapshot(conversationId, row, snapshot, digest, existing?.first_snapshot_seq ?: seq, seq)
        ChatDbResult.Success(readCrewSnapshot(conversationId, snapshot.missionId)!!)
    }

    private fun persistCrewSnapshot(
        conversationId: String,
        row: JSONObject,
        snapshot: CrewMissionSnapshot,
        digest: String,
        firstSnapshotSeq: Long,
        lastSnapshotSeq: Long,
    ) {
        val existing = readMissionHeader(conversationId, snapshot.missionId)
        if (existing == null) {
            sql.execSQL("INSERT INTO crew_missions(conversation_id,mission_id,first_snapshot_seq,last_snapshot_seq,schema_version,process_id,snapshot_digest,title,original_instructions,title_source,status,synthesis,started_at_ms,finished_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(conversationId, snapshot.missionId, firstSnapshotSeq, lastSnapshotSeq, row.optInt("crewSchemaVersion"), snapshot.processId,
                    digest, snapshot.title, ExactUtf16.encode(snapshot.originalInstructions), snapshot.titleSource, snapshot.status,
                    ExactUtf16.encode(snapshot.synthesis), snapshot.startedAtMillis, snapshot.finishedAtMillis.takeIf { it > 0L }))
        } else {
            sql.execSQL("DELETE FROM crew_bots WHERE conversation_id=? AND mission_id=?", arrayOf(conversationId, snapshot.missionId))
            sql.execSQL("DELETE FROM crew_messages WHERE conversation_id=? AND mission_id=?", arrayOf(conversationId, snapshot.missionId))
            sql.execSQL("UPDATE crew_missions SET first_snapshot_seq=?,last_snapshot_seq=?,schema_version=?,process_id=?,snapshot_digest=?,title=?,original_instructions=?,title_source=?,status=?,synthesis=?,started_at_ms=?,finished_at_ms=? WHERE conversation_id=? AND mission_id=?",
                arrayOf(firstSnapshotSeq, lastSnapshotSeq, row.optInt("crewSchemaVersion"), snapshot.processId, digest, snapshot.title, ExactUtf16.encode(snapshot.originalInstructions),
                    snapshot.titleSource, snapshot.status, ExactUtf16.encode(snapshot.synthesis), snapshot.startedAtMillis,
                    snapshot.finishedAtMillis.takeIf { it > 0L }, conversationId, snapshot.missionId))
        }
        insertCrewChildren(conversationId, snapshot)
    }

    /** Returns current snapshots in `LinkedHashMap` first-insertion order (`LocalRunStore.java:1056-1094`). */
    suspend fun readCrewMissionSnapshots(conversationId: String): List<CrewMissionSnapshot> = JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
        readCrewSnapshots(conversationId)
    } }

    /** Hydrates only missions referenced by one timeline page. */
    internal suspend fun readCrewMissionSnapshots(
        conversationId: String,
        missionIds: Collection<String>,
    ): List<CrewMissionSnapshot> = JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
        val ids = missionIds.distinct()
        ids.chunked(MAX_MISSION_IDS_PER_QUERY).flatMap { chunk -> readCrewSnapshots(conversationId, chunk) }
    } }

    /**
     * Active snapshots from another process are persisted as interrupted, matching
     * `LocalRunStore.recoverCrewMissions` (`LocalRunStore.java:1167-1173`). The supplied process ID
     * is used only to decide whether recovery is needed. When recovery writes a changed snapshot,
     * `CrewMissionSnapshot.interrupted()` persists the current `CrewProcessIdentity.ID`, matching
     * the legacy path; this parameter never becomes the persisted process ID.
     */
    suspend fun recoverCrewMissions(conversationId: String, processIdForComparison: String = com.jarvys.agent.crew.CrewProcessIdentity.ID): List<CrewMissionSnapshot> {
        val snapshots = readCrewMissionSnapshots(conversationId)
        val recovered = ArrayList<CrewMissionSnapshot>(snapshots.size)
        for (snapshot in snapshots) {
            val value = if (snapshot.processId != processIdForComparison) snapshot.interrupted() else snapshot
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
    suspend fun readGlobalTimelineMarkers(): GlobalTimelineMarkers = JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
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
        val interrupts = readPendingInterruptRows(sql).map { it.toPendingInterrupt() }
        GlobalTimelineMarkers(decisions, reactions, usedReplies, crew, interrupts)
    } }

    /** Reads pending `main_interrupt_request` rows across conversations, replacing `LocalRunStore.readPendingInterruptRequests()` (`LocalRunStore.java:2816-2819`). */
    suspend fun pendingInterruptRequests(): List<PendingInterrupt> = JarvysChatDatabaseProvider.onQueryExecutor(database) {
        database.withChatReadTransaction { readPendingInterruptRows(sql).map { it.toPendingInterrupt() } }
    }

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

    private fun pendingCompactionIdsBefore(conversationId: String, rows: List<TimelineRow>, rowBoundaryExclusive: Int, highWater: Long): List<String> {
        val ids = LinkedHashSet<String>()
        val limit = rowBoundaryExclusive.coerceIn(0, rows.size)
        if (limit == 0) return emptyList()
        sql.query(
            "SELECT i.item_kind,i.item_key,a.compaction_id FROM conversation_items i " +
                "LEFT JOIN compaction_reflection_acks a ON a.conversation_id=i.conversation_id AND a.seq=i.seq AND a.seq<=? " +
            "WHERE i.conversation_id=? AND i.seq<=? AND i.item_kind IN ('compaction','reflection_checkpoint','reflection_commit','compaction_reflection_consumed') " +
                "AND i.seq IN (SELECT eligible.seq FROM conversation_items eligible WHERE eligible.conversation_id=? AND eligible.seq<=? " +
                "AND eligible.item_kind NOT IN $RESULT_ONLY_KINDS ORDER BY eligible.sort_seq,eligible.sort_sub,eligible.seq LIMIT ?) " +
                "ORDER BY i.sort_seq,i.sort_sub,i.seq,a.compaction_id",
            arrayOf(highWater, conversationId, highWater, conversationId, highWater, limit),
        ).use { c -> while (c.moveToNext()) {
            when (c.getString(0)) {
                "compaction" -> ids.add(c.getString(1))
                else -> c.getStringOrNullMemory("compaction_id")?.let(ids::remove)
            }
        } }
        return ids.toList()
    }

    private fun readTimelineRows(conversationId: String, highWater: Long): List<TimelineRow> = sql.query(
        "SELECT i.seq,i.item_kind,i.item_key,i.sort_seq,i.sort_sub,i.created_at,m.message_id,m.role,m.status,m.owner_user_message_id," +
            "(SELECT group_concat(payload,'') FROM (SELECT p.payload FROM message_parts p " +
            "WHERE p.conversation_id=i.conversation_id AND p.message_id=m.message_id AND p.kind='text' ORDER BY p.ordinal)) AS message_text " +
            "FROM conversation_items i LEFT JOIN messages m ON m.conversation_id=i.conversation_id AND m.seq=i.seq " +
            "WHERE i.conversation_id=? AND i.seq<=? AND i.item_kind NOT IN $RESULT_ONLY_KINDS " +
            "ORDER BY i.sort_seq,i.sort_sub,i.seq", arrayOf(conversationId, highWater),
    ).use { c -> buildList {
        while (c.moveToNext()) add(TimelineRow(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4),
            c.getDouble(5), c.getStringOrNullMemory("message_id"), c.getStringOrNullMemory("role"),
            c.getStringOrNullMemory("status"), c.getStringOrNullMemory("owner_user_message_id"), c.getStringOrNullMemory("message_text")))
    } }

    private fun readReflectionMarker(c: android.database.Cursor) = ReflectionToolMarker(c.getString(0), c.getLong(1), c.getStringOrNullMemory("owner_user_message_id"),
        c.getStringOrNullMemory("batch_id"), c.getStringOrNullMemory("call_id"), c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getDouble(9))

    private fun sanitizeForReflection(text: String): String {
        val entry = ReflectionTranscriptBuilder.Entry("user", text, "user_authored")
        return JSONArray(ReflectionTranscriptBuilder.build(listOf(entry))).optJSONObject(0)?.optString("text", text) ?: text
    }

    private fun InterruptRequestValue.toPendingInterrupt() = PendingInterrupt(
        conversationId, requestId, seq, content, skills, dispatchedMessageId,
        dispatchedSeq, inputState, alreadyDispatched, acknowledgementRequired,
    )

    private fun readCompaction(conversationId: String, id: String): Compaction? = sql.query(
        "SELECT * FROM compactions WHERE conversation_id=? AND compaction_id=?", arrayOf(conversationId, id),
    ).use { if (it.moveToFirst()) readCompaction(it) else null }

    private fun readCompaction(c: android.database.Cursor) = Compaction(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getLong(4), c.getLong(5), c.getString(6), c.getString(7), c.getDouble(8))

    private fun readMissionHeader(conversationId: String, missionId: String): CrewMission? = sql.query(
        "SELECT * FROM crew_missions WHERE conversation_id=? AND mission_id=?", arrayOf(conversationId, missionId),
    ).use { if (it.moveToFirst()) CrewMission(it.getString(0), it.getString(1), it.getLong(2), it.getLong(3), it.getInt(4),
        it.getStringOrNullMemory("process_id"), it.getString(6), it.getString(7), it.getExactUtf16String(it.getColumnIndexOrThrow("original_instructions")), it.getStringOrNullMemory("title_source"),
        it.getString(10), it.getBlobOrNullMemory("synthesis"), it.getLong(12), it.getLongOrNullMemory("finished_at_ms")) else null }

    private fun readCrewSnapshot(conversationId: String, missionId: String): CrewMissionSnapshot? =
        readCrewSnapshots(conversationId, listOf(missionId)).firstOrNull()

    /** Hydrates every mission and child collection with a fixed number of set queries, not per bot/message. */
    private fun readCrewSnapshots(conversationId: String, missionIds: List<String>? = null): List<CrewMissionSnapshot> {
        if (missionIds != null && missionIds.isEmpty()) return emptyList()
        val missionClause = missionIds?.let { ids ->
            " AND mission_id IN (${ids.joinToString(",") { "?" }})"
        }.orEmpty()
        val missionArgs = arrayOf<Any?>(conversationId, *(missionIds?.toTypedArray() ?: emptyArray()))
        val qualifiedMissionClause = missionIds?.let { ids ->
            " AND m.mission_id IN (${ids.joinToString(",") { "?" }})"
        }.orEmpty()
        val qualifiedMissionArgs = missionArgs
        val missions = sql.query("SELECT * FROM crew_missions WHERE conversation_id=?$missionClause ORDER BY first_snapshot_seq", missionArgs).use { c ->
            buildList {
                while (c.moveToNext()) add(CrewMission(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4),
                    c.getStringOrNullMemory("process_id"), c.getString(6), c.getString(7), c.getExactUtf16String(c.getColumnIndexOrThrow("original_instructions")), c.getStringOrNullMemory("title_source"),
                    c.getString(10), c.getBlobOrNullMemory("synthesis"), c.getLong(12), c.getLongOrNullMemory("finished_at_ms")))
            }
        }.filter { missionIds == null || it.mission_id in missionIds }
        if (missions.isEmpty()) return emptyList()
        val byMission = missions.associateBy { it.mission_id }
        val tools = mutableMapOf<Pair<String, String>, MutableList<String>>()
        sql.query("SELECT mission_id,bot_id,tool FROM crew_bot_tools WHERE conversation_id=?$missionClause ORDER BY mission_id,bot_id,ordinal", missionArgs).use { c ->
            while (c.moveToNext()) tools.getOrPut(c.getString(0) to c.getString(1)) { mutableListOf() }.add(c.getString(2))
        }
        val evidence = mutableMapOf<String, com.jarvys.agent.crew.CrewResultEvidence>()
        sql.query("SELECT * FROM crew_result_evidence WHERE conversation_id=?$missionClause", missionArgs).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(c.getColumnIndexOrThrow("evidence_id"))
                val row = JSONObject().put("type", "crew_result_evidence").put("crewResultSchemaVersion", c.getInt(c.getColumnIndexOrThrow("schema_version")))
                    .put("evidenceId", id).put("conversationId", conversationId).put("missionId", c.getString(c.getColumnIndexOrThrow("mission_id")))
                    .put("botId", c.getString(c.getColumnIndexOrThrow("bot_id"))).put("cycleId", c.getString(c.getColumnIndexOrThrow("cycle_id")))
                    .put("reportedStatus", c.getString(c.getColumnIndexOrThrow("reported_status")))
                    .put("resultText", ExactUtf16.decode(c.getBlob(c.getColumnIndexOrThrow("result_text"))))
                    .put("producedAtMillis", c.getLong(c.getColumnIndexOrThrow("produced_at_ms")))
                    .put("contentSha256", c.getString(c.getColumnIndexOrThrow("content_sha256")))
                evidence[id] = com.jarvys.agent.crew.CrewResultEvidence.fromJsonStrict(row)
            }
        }
        val bots = mutableMapOf<String, MutableList<CrewBotSnapshot>>()
        sql.query("SELECT * FROM crew_bots WHERE conversation_id=?$missionClause ORDER BY mission_id,ordinal", missionArgs).use { c ->
            while (c.moveToNext()) {
                val missionId = c.getString(c.getColumnIndexOrThrow("mission_id"))
                if (missionId !in byMission) continue
                val botId = c.getString(c.getColumnIndexOrThrow("bot_id"))
                val evidenceId = c.getStringOrNullMemory("result_evidence_id")
                if (evidenceId != null && evidence[evidenceId] == null) throw IllegalStateException("crew_snapshot_result_evidence_missing")
                bots.getOrPut(missionId) { mutableListOf() }.add(CrewBotSnapshot(
                    botId, c.getStringOrNullMemory("role_id"), c.getStringOrNullMemory("role_name"), c.getString(c.getColumnIndexOrThrow("name")),
                    c.getStringOrNullMemory("color_key"), c.getStringOrNullMemory("mission"), c.getString(c.getColumnIndexOrThrow("status")),
                    c.getBlobOrNullMemory("error")?.let(ExactUtf16::decode), c.getBlobOrNullMemory("result")?.let(ExactUtf16::decode),
                    c.getStringOrNullMemory("waiting_reason"), tools[missionId to botId].orEmpty(), c.getLongOrNullMemory("started_at_ms") ?: 0L,
                    c.getLongOrNullMemory("finished_at_ms") ?: 0L, c.getInt(c.getColumnIndexOrThrow("can_resume")) != 0,
                    c.getStringOrNullMemory("recovery_note"), c.getInt(c.getColumnIndexOrThrow("resume_required")) != 0,
                    c.getStringOrNullMemory("phase"), c.getStringOrNullMemory("last_progress"),
                    c.getLongOrNullMemory("last_progress_at_ms") ?: 0L, c.getStringOrNullMemory("cycle_id"), evidenceId?.let(evidence::get),
                ))
            }
        }
        val refs = mutableMapOf<Pair<String, String>, JSONArray>()
        sql.query("SELECT mission_id,message_id,ref FROM crew_message_refs WHERE conversation_id=?$missionClause ORDER BY mission_id,message_id,ordinal", missionArgs).use { c ->
            while (c.moveToNext()) refs.getOrPut(c.getString(0) to c.getString(1)) { JSONArray() }.put(c.getString(2))
        }
        val coordination = mutableMapOf<Pair<String, String>, JSONObject>()
        sql.query("SELECT m.mission_id,m.message_id,m.coordination_evidence_id,m.reference_message_id,m.evidence_content_sha256,e.bot_id,e.cycle_id " +
            "FROM crew_messages m LEFT JOIN crew_coordination_evidence e ON e.conversation_id=m.conversation_id AND e.evidence_id=m.coordination_evidence_id " +
            "WHERE m.conversation_id=? AND m.coordination_evidence_id IS NOT NULL$qualifiedMissionClause", qualifiedMissionArgs).use { c ->
            while (c.moveToNext()) {
                val messageId = c.getStringOrNullMemory("reference_message_id") ?: throw IllegalStateException("crew_snapshot_coordination_reference_message_id_null")
                val digest = c.getStringOrNullMemory("evidence_content_sha256") ?: throw IllegalStateException("crew_snapshot_coordination_reference_digest_null")
                coordination[c.getString(0) to c.getString(1)] = JSONObject().put("schemaVersion", 1)
                    .put("evidenceId", c.getString(2)).put("conversationId", conversationId).put("missionId", c.getString(0))
                    .put("botId", c.getStringOrNullMemory("bot_id") ?: "").put("cycleId", c.getStringOrNullMemory("cycle_id") ?: "")
                    .put("messageId", messageId).put("contentSha256", digest)
            }
        }
        val bindings = mutableMapOf<Pair<String, String>, JSONObject>()
        sql.query("SELECT * FROM crew_message_input_bindings WHERE conversation_id=?$missionClause", missionArgs).use { c ->
            while (c.moveToNext()) bindings[c.getString(c.getColumnIndexOrThrow("mission_id")) to c.getString(c.getColumnIndexOrThrow("message_id"))] = inputBindingJson(c)
        }
        val activities = mutableMapOf<Pair<String, String>, JSONObject>()
        sql.query("SELECT * FROM crew_message_activities WHERE conversation_id=?$missionClause", missionArgs).use { a ->
            while (a.moveToNext()) activities[a.getString(a.getColumnIndexOrThrow("mission_id")) to a.getString(a.getColumnIndexOrThrow("message_id"))] =
                JSONObject().put("schemaVersion", a.getInt(a.getColumnIndexOrThrow("schema_version")))
                    .put("eventId", a.getString(a.getColumnIndexOrThrow("event_id"))).put("executionId", a.getString(a.getColumnIndexOrThrow("execution_id")))
                    .put("callId", a.getString(a.getColumnIndexOrThrow("call_id"))).put("toolName", a.getString(a.getColumnIndexOrThrow("tool_name")))
                    .put("displayName", a.getString(a.getColumnIndexOrThrow("display_name"))).put("skillId", a.getString(a.getColumnIndexOrThrow("skill_id")))
                    .put("skillName", a.getString(a.getColumnIndexOrThrow("skill_name"))).put("stage", a.getString(a.getColumnIndexOrThrow("stage")))
                    .put("detail", a.getString(a.getColumnIndexOrThrow("detail"))).put("previewId", a.getString(a.getColumnIndexOrThrow("preview_id")))
                    .put("auditDetail", a.getString(a.getColumnIndexOrThrow("audit_detail"))).put("reflectionSource", a.getString(a.getColumnIndexOrThrow("reflection_source")))
                    .put("timestampMillis", a.getLong(a.getColumnIndexOrThrow("timestamp_millis")))
        }
        val messages = mutableMapOf<String, MutableList<CrewMessage>>()
        sql.query("SELECT * FROM crew_messages WHERE conversation_id=?$missionClause ORDER BY mission_id,ordinal", missionArgs).use { c ->
            while (c.moveToNext()) {
                val missionId = c.getString(c.getColumnIndexOrThrow("mission_id"))
                if (missionId !in byMission) continue
                val id = c.getString(c.getColumnIndexOrThrow("message_id"))
                val json = JSONObject().put("id", id).put("conversationId", conversationId)
                    .put("from", c.getStringOrNullMemory("from_bot") ?: "").put("to", c.getStringOrNullMemory("to_bot") ?: "")
                    .put("type", c.getString(c.getColumnIndexOrThrow("type"))).put("text", ExactUtf16.decode(c.getBlob(c.getColumnIndexOrThrow("text"))))
                    .put("timestampMillis", c.getLong(c.getColumnIndexOrThrow("timestamp_ms"))).put("refs", refs[missionId to id] ?: JSONArray())
                coordination[missionId to id]?.let { json.put("coordination", it) }
                bindings[missionId to id]?.let { json.put("inputBinding", it) }
                activities[missionId to id]?.let { json.put("origin", "local_activity").put("activity", it) }
                messages.getOrPut(missionId) { mutableListOf() }.add(CrewMessage.fromJson(json))
            }
        }
        return missions.map { mission -> CrewMissionSnapshot(mission.mission_id, mission.conversation_id, mission.process_id, mission.title,
            mission.original_instructions, mission.title_source, mission.status, mission.synthesis?.let(ExactUtf16::decode) ?: "",
            mission.started_at_ms, mission.finished_at_ms ?: 0L, bots[mission.mission_id].orEmpty(), messages[mission.mission_id].orEmpty()) }
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
        val messageId = c.getStringOrNullMemory("reference_message_id")
            ?: throw IllegalStateException("crew_snapshot_coordination_reference_message_id_null")
        val contentSha256 = c.getStringOrNullMemory("evidence_content_sha256")
            ?: throw IllegalStateException("crew_snapshot_coordination_reference_digest_null")
        JSONObject().put("schemaVersion", 1).put("evidenceId", id).put("conversationId", conversationId)
            .put("missionId", c.getString(c.getColumnIndexOrThrow("mission_id")))
            .put("botId", sql.query("SELECT bot_id FROM crew_coordination_evidence WHERE conversation_id=? AND evidence_id=?", arrayOf(conversationId, id)).use { e -> if (e.moveToFirst()) e.getString(0) else "" })
            .put("cycleId", sql.query("SELECT cycle_id FROM crew_coordination_evidence WHERE conversation_id=? AND evidence_id=?", arrayOf(conversationId, id)).use { e -> if (e.moveToFirst()) e.getString(0) else "" })
            .put("messageId", messageId)
            .put("contentSha256", contentSha256)
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
        .use { if (it.moveToFirst()) it.getLong(0) else 1L }

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
        /** Historic wake markers remain audit-only; new agent content uses typed message rows. */
        private const val RESULT_ONLY_KINDS = "('crew_result_evidence','workspace_result_reference','workspace_result_notice','workspace_result_wake_pending','workspace_result_wake_started','workspace_result_wake_acknowledged','crew_coordination_evidence','crew_coordination_evidence_ref','workspace_coordination_reference','workspace_coordination_notice','workspace_coordination_reply_intent','workspace_coordination_reply_state','workspace_input_incorporation')"
        /** Leave room for the conversation bind under SQLite's per-statement bind-argument limit. */
        private const val MAX_MISSION_IDS_PER_QUERY = 900
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
private fun android.database.Cursor.getExactUtf16String(index: Int): String =
    if (getType(index) == android.database.Cursor.FIELD_TYPE_BLOB) ExactUtf16.decode(getBlob(index)) else getString(index)

private fun <T> ChatDbResult<T>.requireSuccess(): T = when (this) {
    is ChatDbResult.Success -> value
    is ChatDbResult.NotFound -> error("unexpected not found")
    is ChatDbResult.Tombstoned -> error("conversation tombstoned")
    is ChatDbResult.Conflict -> error(reason)
    is ChatDbResult.Invalid -> error(reason)
}
