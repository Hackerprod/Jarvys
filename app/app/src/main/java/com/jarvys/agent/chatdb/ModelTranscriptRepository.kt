package com.jarvys.agent.chatdb

import java.util.UUID

data class ModelCallInput(val callId: String, val toolName: String, val argumentsJson: String)

data class ModelToolBatchInput(
    val conversationId: String,
    val batchId: String = UUID.randomUUID().toString(),
    val ownerUserMessageId: String?,
    val messageIndex: Long,
    val assistantText: String,
    val assistantProgress: Boolean = true,
    val calls: List<ModelCallInput>,
    val rawCalls: String? = null,
    val callsDecodeError: String? = null,
    val providerOutputItemsJson: String? = null,
    val createdAtSeconds: Double? = null,
)

data class ModelToolResultInput(
    val conversationId: String,
    val batchId: String,
    val callId: String,
    val toolName: String,
    val lifecycle: String,
    /** `MainChatTranscriptStore.retain` already bounds archived text and inserts its recovery reference. */
    val output: String,
    val artifactId: String? = null,
    val createdAtSeconds: Double? = null,
)

data class ModelCallState(
    val call: ToolCall,
    val result: ToolResult?,
)

data class ModelToolBatchSnapshot(
    val batch: ToolBatch,
    val calls: List<ModelCallState>,
    val complete: Boolean,
)

sealed interface ModelTranscriptEntry {
    val seq: Long
    val sortSeq: Long
    val sortSub: Int

    data class Batch(override val seq: Long, override val sortSeq: Long, override val sortSub: Int, val value: ModelToolBatchSnapshot) : ModelTranscriptEntry
    data class Lifecycle(override val seq: Long, override val sortSeq: Long, override val sortSub: Int, val batchId: String, val callId: String, val state: String) : ModelTranscriptEntry
    data class ResultActivity(override val seq: Long, override val sortSeq: Long, override val sortSub: Int, val batchId: String, val callId: String, val firstResult: ToolResult?) : ModelTranscriptEntry
    data class Error(override val seq: Long, override val sortSeq: Long, override val sortSub: Int, val value: ModelError) : ModelTranscriptEntry
    data class ProviderOutput(override val seq: Long, override val sortSeq: Long, override val sortSub: Int, val value: ModelProviderOutput) : ModelTranscriptEntry
    data class Artifact(override val seq: Long, override val sortSeq: Long, override val sortSub: Int, val value: ModelArtifact) : ModelTranscriptEntry
}

data class ModelTranscriptSnapshot(
    val conversationId: String,
    /** Logical ledger order after assistant-before-steering sorting, as seen by `loadConversationContext`. */
    val entries: List<ModelTranscriptEntry>,
    val latestProviderOutputItemsByOwner: Map<String, ModelProviderOutput>,
    val compactionBoundarySeq: Long?,
    /** Position of the boundary in the filtered/reordered non-result-only ledger projection. */
    val compactionBoundaryRowIndex: Long?,
    val firstKeptMessageIndex: Long?,
)

data class ToolActivityInput(
    val conversationId: String,
    val eventId: String,
    val ownerUserMessageId: String?,
    val executionId: String?,
    val callId: String?,
    val toolName: String,
    val displayName: String? = null,
    val skillId: String? = null,
    val skillName: String? = null,
    val stage: String,
    val detail: String? = null,
    val previewId: String? = null,
    val auditDetail: String? = null,
    val reflectionSource: String? = null,
    val timestampMillis: Long,
    val createdAtSeconds: Double? = null,
    /** Original ledger batch identity. Normal appends continue resolving this from indexed history. */
    val sourceBatchId: String? = null,
)

data class ToolPresentationInput(
    val conversationId: String,
    val ownerUserMessageId: String?,
    val batchId: String? = null,
    val callId: String?,
    val toolName: String,
    val stage: String,
    val detail: String? = null,
    val auditDetail: String? = null,
    val previewExpected: Boolean = false,
    val previewId: String? = null,
    val createdAtSeconds: Double? = null,
)

data class ReflectionToolInput(
    val conversationId: String,
    val ownerUserMessageId: String?,
    val batchId: String? = null,
    val callId: String?,
    val toolName: String,
    val source: String,
    val stage: String,
    val marker: String,
    val createdAtSeconds: Double? = null,
)

data class ToolBatchResolution(val batchId: String?, val exactExecutionMatch: Boolean)

/**
 * M1b operations for `model_*`, `tool_activity`, `tool_presentation`, and `reflection_tool` ledger
 * rows. Arguments/provider item payloads stay opaque; normalized keys and lifecycle state remain
 * indexed by conversation, batch, call, owner, and execution.
 */
internal class ModelTranscriptRepository internal constructor(database: JarvysChatDatabase) : M1bWriteSupport(database) {
    /**
     * Appends a `model_tool_calls` row as a batch plus ordered calls. Replaces
     * `MainChatTranscriptStore.attach` (`MainChatTranscriptStore.java:91-105`); arguments larger than
     * 8000 characters must already be represented by the retained-argument reference produced by
     * `MainChatTranscriptStore.boundedArguments` (`MainChatTranscriptStore.java:141-156`).
     */
    suspend fun appendToolBatch(input: ModelToolBatchInput): ChatDbResult<ModelToolBatchSnapshot> = transaction {
        // Legacy transcript rows treated batch IDs as opaque strings; only blank IDs are unusable.
        if (input.conversationId.isBlank() || input.batchId.isBlank() || input.messageIndex < 0L || input.calls.any {
                it.callId.isBlank() || it.toolName.isBlank() || it.argumentsJson.isBlank() ||
                    it.callId.length > 512 || it.toolName.length > 256 || it.argumentsJson.length > INLINE_TOOL_OUTPUT_CHARS
            }) return@transaction ChatDbResult.Invalid("batch, owner index, call IDs, names, and opaque arguments are required")
        if (input.callsDecodeError == null && input.rawCalls != null) return@transaction ChatDbResult.Invalid("raw_calls is reserved for undecodable call arrays")
        if (input.callsDecodeError != null && input.rawCalls == null) return@transaction ChatDbResult.Invalid("undecodable calls must retain raw_calls")
        if (input.callsDecodeError != null && input.calls.isNotEmpty()) return@transaction ChatDbResult.Invalid("undecodable calls cannot also contain decoded call rows")
        if (input.calls.isEmpty() && input.callsDecodeError == null) return@transaction ChatDbResult.Invalid("a decoded model_tool_calls row needs at least one call")
        if (input.calls.map { it.callId }.toSet().size != input.calls.size) return@transaction ChatDbResult.Invalid("call IDs must be unique in one batch")
        ensureConversationExists(input.conversationId)
        val existing = readBatch(input.conversationId, input.batchId)
        if (existing != null) {
            val same = existing.owner_user_message_id == input.ownerUserMessageId && existing.message_index == input.messageIndex &&
                existing.assistant_text == input.assistantText && existing.assistant_progress == input.assistantProgress &&
                existing.raw_calls == input.rawCalls && existing.calls_decode_error == input.callsDecodeError &&
                existing.provider_output_items_json == input.providerOutputItemsJson &&
                readCalls(input.conversationId, input.batchId).map { Triple(it.call_id, it.name, it.arguments_json) } ==
                    input.calls.map { Triple(it.callId, it.toolName, it.argumentsJson) }
            return@transaction if (same) ChatDbResult.Success(readBatchSnapshot(input.conversationId, input.batchId)!!) else ChatDbResult.Conflict("model_tool_calls batch identity has different payload")
        }
        if (input.ownerUserMessageId != null) {
            if (ownerMessageSeq(input.conversationId, input.ownerUserMessageId) == null)
                return@transaction ChatDbResult.Invalid("tool batch owner must name a user message in this conversation")
        }
        val time = input.createdAtSeconds ?: nowSeconds()
        val seq = insertLedgerItem(input.conversationId, "model_tool_calls", input.batchId, time, ownerMessageSeq(input.conversationId, input.ownerUserMessageId))
        sql.execSQL(
            "INSERT INTO tool_batches(conversation_id,batch_id,seq,owner_user_message_id,message_index,assistant_text,assistant_progress,created_at,last_activity_seq,calls_decode_error,raw_calls,provider_output_items_json) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(input.conversationId, input.batchId, seq, input.ownerUserMessageId, input.messageIndex, input.assistantText,
                input.assistantProgress, time, seq, input.callsDecodeError, input.rawCalls, input.providerOutputItemsJson),
        )
        input.calls.forEachIndexed { ordinal, call ->
            sql.execSQL(
                "INSERT INTO tool_calls(conversation_id,batch_id,ordinal,call_id,name,arguments_json,lifecycle,lifecycle_seq) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf(input.conversationId, input.batchId, ordinal, call.callId, call.toolName, call.argumentsJson, "INTENT", seq),
            )
        }
        ChatDbResult.Success(readBatchSnapshot(input.conversationId, input.batchId)!!)
    }

    /**
     * Appends a `model_tool_started` or `model_tool_not_started` lifecycle row. Replaces
     * `MainChatTranscriptStore.attach` (`MainChatTranscriptStore.java:108-124`); the latest state and
     * its `lifecycle_seq` are indexed on the normalized call row for restart recovery.
     */
    suspend fun setCallLifecycle(conversationId: String, batchId: String, callId: String, lifecycle: String): ChatDbResult<Long> = transaction {
        if (lifecycle !in setOf("STARTED", "NEVER_LAUNCHED")) return@transaction ChatDbResult.Invalid("lifecycle must be STARTED or NEVER_LAUNCHED")
        ensureConversationExists(conversationId)
        if (readCall(conversationId, batchId, callId) == null) return@transaction ChatDbResult.NotFound
        val kind = if (lifecycle == "STARTED") "model_tool_started" else "model_tool_not_started"
        val seq = insertLedgerItem(conversationId, kind, encodeCallItemKey(batchId, callId), turnAnchorSeq = ownerMessageSeq(conversationId, readBatch(conversationId, batchId)?.owner_user_message_id))
        sql.execSQL("UPDATE tool_calls SET lifecycle=?,lifecycle_seq=? WHERE conversation_id=? AND batch_id=? AND call_id=?",
            arrayOf(lifecycle, seq, conversationId, batchId, callId))
        sql.execSQL("UPDATE tool_batches SET last_activity_seq=? WHERE conversation_id=? AND batch_id=?", arrayOf(seq, conversationId, batchId))
        ChatDbResult.Success(seq)
    }

    /**
     * Appends a `model_tool_result` row. Replaces `MainChatTranscriptStore.attach`
     * (`MainChatTranscriptStore.java:128-137`): the first result per call wins, even when its
     * `toolName` differs from the call. Transcript restoration interprets that mismatch as a placeholder;
     * later result attempts still advance `last_activity_seq` without replacing the first row.
     */
    suspend fun appendToolResult(input: ModelToolResultInput): ChatDbResult<ToolResult?> = transaction {
        if (input.output.length > INLINE_TOOL_OUTPUT_CHARS)
            return@transaction ChatDbResult.Invalid("tool output must be retained as an artifact reference before storage")
        ensureConversationExists(input.conversationId)
        val call = readCall(input.conversationId, input.batchId, input.callId) ?: return@transaction ChatDbResult.NotFound
        if (input.lifecycle !in setOf("RESULT", "INTERRUPTED_UNCERTAIN", "NEVER_LAUNCHED"))
            return@transaction ChatDbResult.Invalid("tool result lifecycle is unsupported")
        val time = input.createdAtSeconds ?: nowSeconds()
        val seq = insertLedgerItem(input.conversationId, "model_tool_result", encodeCallItemKey(input.batchId, input.callId), time,
            ownerMessageSeq(input.conversationId, readBatch(input.conversationId, input.batchId)?.owner_user_message_id))
        if (input.artifactId != null) {
            val owned = sql.query("SELECT EXISTS(SELECT 1 FROM model_artifacts WHERE conversation_id=? AND artifact_id=?)", arrayOf(input.conversationId, input.artifactId))
                .use { it.moveToFirst(); it.getInt(0) != 0 }
            if (!owned) throw M1bAbort("model_artifact_reference_missing")
        }
        val first = sql.query("SELECT 1 FROM tool_results WHERE conversation_id=? AND batch_id=? AND call_id=?", arrayOf(input.conversationId, input.batchId, input.callId))
            .use { !it.moveToFirst() }
        if (first) sql.execSQL(
            "INSERT INTO tool_results(conversation_id,batch_id,call_id,seq,tool_name,lifecycle,output,created_at,file_artifact_id) VALUES(?,?,?,?,?,?,?,?,?)",
            arrayOf(input.conversationId, input.batchId, input.callId, seq, input.toolName, input.lifecycle, input.output, time, input.artifactId),
        )
        sql.execSQL("UPDATE tool_batches SET last_activity_seq=? WHERE conversation_id=? AND batch_id=?", arrayOf(seq, input.conversationId, input.batchId))
        ChatDbResult.Success(readToolResult(input.conversationId, input.batchId, input.callId))
    }

    /**
     * Stores a `model_error` row, replacing `MainChatTranscriptStore.attach`
     * (`MainChatTranscriptStore.java:66-79`). It remains queryable for restore even though
     * `LocalRunStore.loadConversationContext` (`LocalRunStore.java:2898-2913`) excludes it from the
     * returned conversation turns.
     */
    suspend fun appendModelError(conversationId: String, ownerUserMessageId: String?, messageIndex: Long, errorCode: String): ChatDbResult<Long> = transaction {
        if (conversationId.isBlank() || messageIndex < 0L || errorCode.isBlank()) return@transaction ChatDbResult.Invalid("model error fields are required")
        ensureConversationExists(conversationId)
        val seq = insertLedgerItem(conversationId, "model_error", "$ownerUserMessageId/$messageIndex")
        sql.execSQL("INSERT INTO model_errors(conversation_id,seq,owner_user_message_id,message_index,error_code) VALUES(?,?,?,?,?)",
            arrayOf(conversationId, seq, ownerUserMessageId, messageIndex, errorCode))
        ChatDbResult.Success(seq)
    }

    /**
     * Stores `model_provider_output_items`, replacing `MainChatTranscriptStore.attach`
     * (`MainChatTranscriptStore.java:80-89`). Payload is provider-owned opaque JSON; reads select the
     * last row for each owner, as `restoreProviderOutputItems` (`MainChatTranscriptStore.java:327-340`) does.
     */
    suspend fun appendProviderOutputItems(conversationId: String, ownerUserMessageId: String, messageIndex: Long, itemsJson: String): ChatDbResult<Long> = transaction {
        if (conversationId.isBlank() || ownerUserMessageId.isBlank() || messageIndex < 0L || itemsJson.isBlank()) return@transaction ChatDbResult.Invalid("provider output identity and payload are required")
        ensureConversationExists(conversationId)
        val seq = insertLedgerItem(conversationId, "model_provider_output_items", ownerUserMessageId,
            turnAnchorSeq = ownerMessageSeq(conversationId, ownerUserMessageId))
        sql.execSQL("INSERT INTO model_provider_outputs(conversation_id,seq,owner_user_message_id,message_index,items_json) VALUES(?,?,?,?,?)",
            arrayOf(conversationId, seq, ownerUserMessageId, messageIndex, itemsJson))
        ChatDbResult.Success(seq)
    }

    /**
     * Persists one `model_artifact` ownership row; replaces `MainChatTranscriptStore.archive`
     * (`MainChatTranscriptStore.java:160-192`), including its conversation-wide 16 MiB retention
     * quota. Artifact bytes remain on disk and `file_refs` stores only path, digest, and byte count.
     */
    suspend fun appendModelArtifact(
        conversationId: String, artifactId: String, relativePath: String, sha256: String, chars: Long, bytes: Long,
        mimeType: String = "text/plain",
    ): ChatDbResult<ModelArtifact> = transaction {
        if (conversationId.isBlank() || artifactId.isBlank() || relativePath.isBlank() || sha256.isBlank() || chars < 0L || bytes < 0L)
            return@transaction ChatDbResult.Invalid("model artifact metadata is invalid")
        ensureConversationExists(conversationId)
        val existing = readModelArtifact(conversationId, artifactId)
        if (existing != null) return@transaction if (existing.path == relativePath && existing.sha256 == sha256 && existing.chars == chars && existing.bytes == bytes)
            ChatDbResult.Success(existing) else ChatDbResult.Conflict("model artifact identity has different metadata")
        val total = sql.query("SELECT COALESCE(SUM(bytes),0) FROM model_artifacts WHERE conversation_id=?", arrayOf(conversationId))
            .use { it.moveToFirst(); it.getLong(0) }
        if (bytes > MAX_MODEL_ARTIFACT_BYTES - total) return@transaction ChatDbResult.Invalid("model artifact retention quota exceeded")
        val time = nowSeconds()
        val fileRefId = ensureFileRef(relativePath, sha256, bytes, mimeType, (time * 1000.0).toLong())
        val seq = insertLedgerItem(conversationId, "model_artifact", artifactId, time)
        sql.execSQL("INSERT INTO model_artifacts(conversation_id,artifact_id,seq,path,sha256,chars,bytes,file_ref_id) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(conversationId, artifactId, seq, relativePath, sha256, chars, bytes, fileRefId))
        ChatDbResult.Success(readModelArtifact(conversationId, artifactId)!!)
    }

    /**
     * Appends a `tool_activity` row and resolves its source batch through indexed `(executionId,
     * callId)` history, falling back to the latest indexed `callId` match. Replaces
     * `LocalRunStore.appendToolActivity` (`LocalRunStore.java:appendToolActivity`) and its reverse scans
     * (`LocalRunStore.java:1935-1996`, `2140-2229`). Duplicate `(conversation_id,event_id)` is first-wins.
     */
    suspend fun appendToolActivity(input: ToolActivityInput): ChatDbResult<ToolActivity?> = transaction {
        if (input.conversationId.isBlank() || input.eventId.isBlank() || input.toolName.isBlank() || input.stage.isBlank())
            return@transaction ChatDbResult.Invalid("tool activity identity, name, and stage are required")
        ensureConversationExists(input.conversationId)
        val prior = readToolActivityByEvent(input.conversationId, input.eventId)
        if (prior != null) return@transaction ChatDbResult.Success(prior)
        val resolution = resolveActivityBatch(input.conversationId, input.ownerUserMessageId, input.executionId, input.callId)
        val time = input.createdAtSeconds ?: input.timestampMillis / 1000.0
        val seq = insertLedgerItem(input.conversationId, "tool_activity", input.eventId, time,
            ownerMessageSeq(input.conversationId, input.ownerUserMessageId))
        sql.execSQL(
            "INSERT INTO tool_activities(conversation_id,seq,event_id,owner_user_message_id,source_batch_id,execution_id,call_id,tool_name,display_name,skill_id,skill_name,stage,detail,preview_id,audit_detail,reflection_source,timestamp_millis,created_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(input.conversationId, seq, input.eventId, input.ownerUserMessageId,
                input.sourceBatchId?.takeIf(String::isNotBlank) ?: resolution.batchId, input.executionId, input.callId,
                input.toolName, input.displayName, input.skillId, input.skillName, input.stage, input.detail, input.previewId,
                input.auditDetail, input.reflectionSource, input.timestampMillis, time),
        )
        ChatDbResult.Success(readToolActivityByEvent(input.conversationId, input.eventId))
    }

    /**
     * Appends a last-wins `tool_presentation` row, replacing
     * `LocalRunStore.appendConversationToolPresentation` (`LocalRunStore.java:appendConversationToolPresentation`). Its
     * batch comes from the explicit ID or the newest indexed call for this owner.
     */
    suspend fun appendToolPresentation(input: ToolPresentationInput): ChatDbResult<ToolPresentation> = transaction {
        if (input.conversationId.isBlank() || input.toolName.isBlank() || input.stage.isBlank()) return@transaction ChatDbResult.Invalid("presentation name and stage are required")
        ensureConversationExists(input.conversationId)
        val batch = input.batchId?.takeIf(String::isNotBlank) ?: latestBatchForOwnerCall(input.conversationId, input.ownerUserMessageId, input.callId)
        if (batch != null && input.callId != null && readCall(input.conversationId, batch, input.callId) == null)
            return@transaction ChatDbResult.Conflict("tool presentation call is not in its source batch")
        val time = input.createdAtSeconds ?: nowSeconds()
        val seq = insertLedgerItem(input.conversationId, "tool_presentation", "${batch.orEmpty()}/${input.callId.orEmpty()}", time,
            ownerMessageSeq(input.conversationId, input.ownerUserMessageId))
        sql.execSQL("INSERT INTO tool_presentations(conversation_id,seq,owner_user_message_id,batch_id,call_id,tool_name,stage,detail,audit_detail,preview_expected,preview_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(input.conversationId, seq, input.ownerUserMessageId, batch, input.callId, input.toolName, input.stage,
                input.detail, input.auditDetail, input.previewExpected, input.previewId, time))
        ChatDbResult.Success(readToolPresentation(input.conversationId, seq)!!)
    }

    /**
     * Appends a `reflection_tool` marker, replacing `LocalRunStore.appendReflectionToolEvent`
     * (`LocalRunStore.java:appendReflectionToolEvent`); its owner/call association uses the same indexed newest-batch lookup as presentations.
     */
    suspend fun appendReflectionTool(input: ReflectionToolInput): ChatDbResult<ReflectionToolMarker> = transaction {
        if (input.conversationId.isBlank() || input.toolName.isBlank() || input.source.isBlank() || input.stage.isBlank()) return@transaction ChatDbResult.Invalid("reflection tool marker fields are required")
        ensureConversationExists(input.conversationId)
        val batch = input.batchId?.takeIf(String::isNotBlank) ?: latestBatchForOwnerCall(input.conversationId, input.ownerUserMessageId, input.callId)
        val time = input.createdAtSeconds ?: nowSeconds()
        val seq = insertLedgerItem(input.conversationId, "reflection_tool", "${batch.orEmpty()}/${input.callId.orEmpty()}", time,
            ownerMessageSeq(input.conversationId, input.ownerUserMessageId))
        sql.execSQL("INSERT INTO reflection_tool_markers(conversation_id,seq,owner_user_message_id,batch_id,call_id,tool_name,source,stage,marker,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            arrayOf(input.conversationId, seq, input.ownerUserMessageId, batch, input.callId, input.toolName, input.source, input.stage, input.marker, time))
        ChatDbResult.Success(readReflectionToolMarker(input.conversationId, seq)!!)
    }

    /**
     * Reads typed `model_tool_calls`, lifecycle, result, error, provider-output and artifact entries in logical ledger order. Replaces
     * `LocalRunStore.readModelTranscriptRows` (`LocalRunStore.java:2985-2992`) plus
     * `MainChatTranscriptStore.restore` (`MainChatTranscriptStore.java:238-325`): each batch stays
     * at its `model_tool_calls` position; calls expose the latest lifecycle, first matching result,
     * and completeness. Consumers synthesize interruption placeholders from these exact states.
     * `compactionBoundaryRowIndex` is the count of timeline rows before the compaction, persisted
     * in the suffix of its generated `legacy-<timestamp>-<rowIndex>` ID. This keeps the summary
     * boundary stable when the reader reconstructs the model context.
     */
    suspend fun readModelTranscript(conversationId: String): ModelTranscriptSnapshot = JarvysChatDatabaseProvider.onQueryExecutor(database) {
        database.withChatReadTransaction {
            val highWater = sql.query("SELECT COALESCE(next_seq-1,0) FROM conversations WHERE id=?", arrayOf(conversationId))
                .use { if (it.moveToFirst()) it.getLong(0) else 0L }
            val rows = sql.query(
                "SELECT seq,item_kind,item_key,sort_seq,sort_sub FROM conversation_items WHERE conversation_id=? " +
                    "AND seq<=? AND item_kind IN ('model_tool_calls','model_tool_started','model_tool_not_started','model_tool_result','model_error','model_provider_output_items','model_artifact') " +
                    "ORDER BY sort_seq,sort_sub,seq",
                arrayOf(conversationId, highWater),
            ).use { c -> buildList { while (c.moveToNext()) add(SpineEntry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4))) } }

            val batchIds = rows.mapNotNull { item ->
                when (item.kind) {
                    "model_tool_calls" -> item.key
                    "model_tool_started", "model_tool_not_started", "model_tool_result" -> decodeCallItemKey(item.key)?.first
                    else -> null
                }
            }.distinct()
            val lifecycleAtHighWater = rows.mapNotNull { item ->
                if (item.kind != "model_tool_started" && item.kind != "model_tool_not_started") return@mapNotNull null
                decodeCallItemKey(item.key)?.let { key ->
                    Triple(key, if (item.kind == "model_tool_started") "STARTED" else "NEVER_LAUNCHED", item.seq)
                }
            }.groupBy({ it.first }, { it.second to it.third })
                .mapValues { (_, revisions) -> revisions.maxBy { it.second } }
            val lastActivityAtHighWater = rows.mapNotNull { item ->
                val batchId = if (item.kind == "model_tool_calls") item.key else decodeCallItemKey(item.key)?.first
                batchId?.let { it to item.seq }
            }.groupBy({ it.first }, { it.second }).mapValues { (_, sequences) -> sequences.maxOrNull()!! }
            val batchSnapshots = loadBatchSnapshots(conversationId, batchIds, highWater, lifecycleAtHighWater, lastActivityAtHighWater)
            val callsByKey = batchSnapshots.flatMap { (batchId, snapshot) ->
                snapshot.calls.map { (batchId to it.call.call_id) to it }
            }.toMap()
            val errors = loadRowsBySeq(conversationId, rows.filter { it.kind == "model_error" }.map { it.seq }, "model_errors") { readModelError(it) }
            val providers = loadRowsBySeq(conversationId, rows.filter { it.kind == "model_provider_output_items" }.map { it.seq }, "model_provider_outputs") { readProviderOutput(it) }
            val artifacts = loadRowsBySeq(conversationId, rows.filter { it.kind == "model_artifact" }.map { it.seq }, "model_artifacts") { readModelArtifact(it) }

            val events = rows.mapNotNull { item ->
                when (item.kind) {
                    "model_tool_calls" -> batchSnapshots[item.key]?.let { ModelTranscriptEntry.Batch(item.seq, item.sortSeq, item.sortSub, it) }
                    "model_tool_started", "model_tool_not_started" -> {
                        val parts = decodeCallItemKey(item.key)
                        if (parts == null || callsByKey[parts] == null) null else ModelTranscriptEntry.Lifecycle(
                            item.seq, item.sortSeq, item.sortSub, parts.first, parts.second,
                            if (item.kind == "model_tool_started") "STARTED" else "NEVER_LAUNCHED",
                        )
                    }
                    "model_tool_result" -> {
                        val parts = decodeCallItemKey(item.key)
                        if (parts == null || callsByKey[parts] == null) null else ModelTranscriptEntry.ResultActivity(
                            item.seq, item.sortSeq, item.sortSub, parts.first, parts.second, callsByKey[parts]?.result,
                        )
                    }
                    "model_error" -> errors[item.seq]?.let { ModelTranscriptEntry.Error(item.seq, item.sortSeq, item.sortSub, it) }
                    "model_provider_output_items" -> providers[item.seq]?.let { ModelTranscriptEntry.ProviderOutput(item.seq, item.sortSeq, item.sortSub, it) }
                    "model_artifact" -> artifacts[item.seq]?.let { ModelTranscriptEntry.Artifact(item.seq, item.sortSeq, item.sortSub, it) }
                    else -> null
                }
            }

            val boundary = sql.query(
                "SELECT c.seq,c.compaction_id,c.first_kept,ci.sort_seq,ci.sort_sub FROM compactions c " +
                    "JOIN conversation_items ci ON ci.conversation_id=c.conversation_id AND ci.seq=c.seq " +
                "WHERE c.conversation_id=? AND c.seq<=? AND NOT EXISTS (SELECT 1 FROM messages m JOIN conversation_items hi " +
                    "ON hi.conversation_id=m.conversation_id AND hi.seq=m.hidden_seq WHERE m.conversation_id=c.conversation_id " +
                    "AND m.hidden_seq<=? AND hi.seq<=? AND m.hidden_kind IN ('assistant_regenerated','assistant_deleted') AND " +
                    "(hi.sort_seq>ci.sort_seq OR (hi.sort_seq=ci.sort_seq AND hi.sort_sub>ci.sort_sub) " +
                    "OR (hi.sort_seq=ci.sort_seq AND hi.sort_sub=ci.sort_sub AND hi.seq>ci.seq))) " +
                    "ORDER BY ci.sort_seq DESC,ci.sort_sub DESC,ci.seq DESC LIMIT 1",
                arrayOf(conversationId, highWater, highWater, highWater),
            ).use { c ->
                if (c.moveToFirst()) CompactionBoundary(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4)) else null
            }
            val boundaryPosition = boundary?.let { Triple(it.sortSeq, it.sortSub, it.seq) }
            val latestProviders = linkedMapOf<String, ModelProviderOutput>()
            rows.asSequence().filter { it.kind == "model_provider_output_items" }.forEach { item ->
                val provider = providers[item.seq] ?: return@forEach
                if (boundary != null && provider.message_index < boundary.firstKept) return@forEach
                if (boundary != null) {
                    val afterBoundary = boundaryPosition?.let { position ->
                        item.sortSeq > position.first ||
                            (item.sortSeq == position.first && item.sortSub > position.second) ||
                            (item.sortSeq == position.first && item.sortSub == position.second && item.seq >= position.third)
                    } ?: false
                    if (!afterBoundary) return@forEach
                }
                latestProviders[provider.owner_user_message_id] = provider
            }
            val boundaryIndex = boundary?.let { value ->
                legacyCompactionRowIndex(value.compactionId) ?: sql.query(
                    "SELECT COUNT(*) FROM conversation_items i JOIN conversation_items boundary " +
                        "ON boundary.conversation_id=i.conversation_id AND boundary.seq=? " +
                    "WHERE i.conversation_id=? AND i.seq<=? AND i.item_kind NOT IN " + eligibleTimelineExclusionSql() +
                        " AND (i.sort_seq<boundary.sort_seq OR (i.sort_seq=boundary.sort_seq AND i.sort_sub<boundary.sort_sub) " +
                        "OR (i.sort_seq=boundary.sort_seq AND i.sort_sub=boundary.sort_sub AND i.seq<boundary.seq))",
                    arrayOf(value.seq, conversationId, highWater),
                ).use { it.moveToFirst(); it.getLong(0) }
            }
            ModelTranscriptSnapshot(conversationId, events, latestProviders, boundary?.seq, boundaryIndex, boundary?.firstKept)
        }
    }

    /**
     * Resolves a `tool_activity` batch by indexed execution/call history or newest matching call,
     * replacing `LocalRunStore.toolActivitySourceBatch` (`LocalRunStore.java:1925-1996`) without a ledger scan.
     */
    suspend fun resolveActivitySourceBatch(conversationId: String, ownerUserMessageId: String?, executionId: String?, callId: String?): ToolBatchResolution =
        JarvysChatDatabaseProvider.onQueryExecutor(database) {
            database.withChatReadTransaction { resolveActivityBatch(conversationId, ownerUserMessageId, executionId, callId) }
        }

    /** Indexed activity rows replacing the owner/execution grouping in `LocalRunStore.projectConversationTimeline` (`LocalRunStore.java:4275-4286`). */
    suspend fun readToolActivities(conversationId: String, ownerUserMessageId: String, executionId: String): List<ToolActivity> =
        JarvysChatDatabaseProvider.onQueryExecutor(database) {
            database.withChatReadTransaction {
                sql.query("SELECT * FROM tool_activities WHERE conversation_id=? AND owner_user_message_id=? AND execution_id=? ORDER BY seq",
                    arrayOf(conversationId, ownerUserMessageId, executionId)).use { c -> buildList { while (c.moveToNext()) add(readToolActivity(c)) } }
            }
        }

    /** Most recent batch for an owner, backed by `idx_tool_batches_owner`; replaces `LocalRunStore.readAssistantProgress` (`LocalRunStore.java:2803-2848`). */
    suspend fun latestBatchForOwner(conversationId: String, ownerUserMessageId: String): ModelToolBatchSnapshot? =
        JarvysChatDatabaseProvider.onQueryExecutor(database) {
            database.withChatReadTransaction {
                val id = sql.query("SELECT batch_id FROM tool_batches WHERE conversation_id=? AND owner_user_message_id=? ORDER BY seq DESC LIMIT 1",
                    arrayOf(conversationId, ownerUserMessageId)).use { if (it.moveToFirst()) it.getString(0) else null }
                id?.let { readBatchSnapshot(conversationId, it) }
            }
        }

    /** Reads first-wins `model_tool_result` rows for an owner, replacing `MainChatTranscriptStore.restore` (`MainChatTranscriptStore.java:238-325`). */
    suspend fun toolResultsForOwner(conversationId: String, ownerUserMessageId: String): List<ToolResult> =
        JarvysChatDatabaseProvider.onQueryExecutor(database) {
            database.withChatReadTransaction {
                sql.query("SELECT r.* FROM tool_results r JOIN tool_batches b ON b.conversation_id=r.conversation_id AND b.batch_id=r.batch_id " +
                    "WHERE r.conversation_id=? AND b.owner_user_message_id=? ORDER BY r.seq", arrayOf(conversationId, ownerUserMessageId)).use { c ->
                    buildList { while (c.moveToNext()) add(readToolResult(c)) }
                }
            }
        }

    /** Reads latest `tool_presentation` for `(batch,call)`, replacing timeline presentation projection in `LocalRunStore.projectConversationTimeline` (`LocalRunStore.java:4275-4286`). */
    suspend fun latestToolPresentation(conversationId: String, batchId: String, callId: String): ToolPresentation? =
        JarvysChatDatabaseProvider.onQueryExecutor(database) {
            database.withChatReadTransaction {
                sql.query("SELECT * FROM tool_presentations WHERE conversation_id=? AND batch_id=? AND call_id=? ORDER BY seq DESC LIMIT 1",
                    arrayOf(conversationId, batchId, callId)).use { if (it.moveToFirst()) readToolPresentation(it) else null }
            }
        }

    private fun resolveActivityBatch(conversationId: String, owner: String?, executionId: String?, callId: String?): ToolBatchResolution {
        if (owner.isNullOrEmpty() || callId.isNullOrEmpty()) return ToolBatchResolution(null, false)
        if (!executionId.isNullOrEmpty()) {
            val exact = sql.query(
                "SELECT source_batch_id FROM tool_activities WHERE conversation_id=? AND owner_user_message_id=? AND execution_id=? AND call_id=? AND source_batch_id IS NOT NULL ORDER BY seq DESC LIMIT 1",
                arrayOf(conversationId, owner, executionId, callId),
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            if (exact != null) return ToolBatchResolution(exact, true)
        }
        return ToolBatchResolution(latestBatchForOwnerCall(conversationId, owner, callId), false)
    }

    private fun latestBatchForOwnerCall(conversationId: String, owner: String?, callId: String?): String? {
        if (owner.isNullOrEmpty() || callId.isNullOrEmpty()) return null
        return sql.query(
            "SELECT b.batch_id FROM tool_calls c JOIN tool_batches b ON b.conversation_id=c.conversation_id AND b.batch_id=c.batch_id " +
                "WHERE c.conversation_id=? AND b.owner_user_message_id=? AND c.call_id=? ORDER BY b.seq DESC LIMIT 1",
            arrayOf(conversationId, owner, callId),
        ).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun readBatchSnapshot(conversationId: String, batchId: String): ModelToolBatchSnapshot? =
        loadBatchSnapshots(conversationId, listOf(batchId))[batchId]

    /** Loads batch headers, calls, and first-wins results in one query per table and SQLite-sized ID chunk. */
    private fun loadBatchSnapshots(
        conversationId: String,
        batchIds: Collection<String>,
        highWater: Long? = null,
        lifecycleAtHighWater: Map<Pair<String, String>, Pair<String, Long>>? = null,
        lastActivityAtHighWater: Map<String, Long>? = null,
    ): Map<String, ModelToolBatchSnapshot> {
        val distinctIds = batchIds.distinct()
        if (distinctIds.isEmpty()) return emptyMap()
        val batches = linkedMapOf<String, ToolBatch>()
        val calls = linkedMapOf<String, MutableList<ToolCall>>()
        val results = mutableMapOf<Pair<String, String>, ToolResult>()
        distinctIds.chunked(MAX_IN_QUERY_IDS).forEach { ids ->
            val inClause = ids.joinToString(",") { "?" }
            val args = arrayOf<Any?>(conversationId, *ids.toTypedArray())
            val batchWaterClause = if (highWater == null) "" else " AND seq<=?"
            val batchArgs = if (highWater == null) args else arrayOf<Any?>(*args, highWater)
            sql.query("SELECT * FROM tool_batches WHERE conversation_id=? AND batch_id IN ($inClause)$batchWaterClause", batchArgs).use { c ->
                while (c.moveToNext()) readBatch(c).also { batches[it.batch_id] = it }
            }
            sql.query("SELECT * FROM tool_calls WHERE conversation_id=? AND batch_id IN ($inClause) ORDER BY batch_id,ordinal", args).use { c ->
                while (c.moveToNext()) readCall(c).also { calls.getOrPut(it.batch_id) { mutableListOf() }.add(it) }
            }
            val resultWaterClause = if (highWater == null) "" else " AND seq<=?"
            val resultArgs = if (highWater == null) args else arrayOf<Any?>(*args, highWater)
            sql.query("SELECT * FROM tool_results WHERE conversation_id=? AND batch_id IN ($inClause)$resultWaterClause", resultArgs).use { c ->
                while (c.moveToNext()) readToolResult(c).also { results[it.batch_id to it.call_id] = it }
            }
        }
        return batches.mapValues { (batchId, batch) ->
            val states = calls[batchId].orEmpty().map { storedCall ->
                val revision = lifecycleAtHighWater?.get(batchId to storedCall.call_id)
                val call = if (highWater == null) storedCall else storedCall.copy(
                    lifecycle = revision?.first ?: "INTENT",
                    lifecycle_seq = revision?.second ?: batch.seq,
                )
                ModelCallState(call, results[batchId to call.call_id])
            }
            val cappedBatch = if (highWater == null) batch else batch.copy(last_activity_seq = lastActivityAtHighWater?.get(batchId) ?: batch.seq)
            ModelToolBatchSnapshot(cappedBatch, states, batch.calls_decode_error == null && states.isNotEmpty() && states.all { it.result != null })
        }
    }

    private fun <T> loadRowsBySeq(
        conversationId: String,
        sequences: Collection<Long>,
        table: String,
        read: (android.database.Cursor) -> T,
    ): Map<Long, T> {
        val distinctSequences = sequences.distinct()
        if (distinctSequences.isEmpty()) return emptyMap()
        val rows = linkedMapOf<Long, T>()
        distinctSequences.chunked(MAX_IN_QUERY_IDS).forEach { ids ->
            val inClause = ids.joinToString(",") { "?" }
            val args = arrayOf<Any?>(conversationId, *ids.map { it as Any? }.toTypedArray())
            sql.query("SELECT * FROM $table WHERE conversation_id=? AND seq IN ($inClause)", args).use { c ->
                while (c.moveToNext()) rows[c.getLong(c.getColumnIndexOrThrow("seq"))] = read(c)
            }
        }
        return rows
    }

    private fun readBatch(conversationId: String, batchId: String): ToolBatch? = sql.query(
        "SELECT * FROM tool_batches WHERE conversation_id=? AND batch_id=? LIMIT 1", arrayOf(conversationId, batchId),
    ).use { if (it.moveToFirst()) readBatch(it) else null }

    private fun readBatch(c: android.database.Cursor) = ToolBatch(
        c.getString(c.getColumnIndexOrThrow("conversation_id")), c.getString(c.getColumnIndexOrThrow("batch_id")), c.getLong(c.getColumnIndexOrThrow("seq")),
        c.getStringOrNullM1b("owner_user_message_id"), c.getLong(c.getColumnIndexOrThrow("message_index")), c.getString(c.getColumnIndexOrThrow("assistant_text")),
        c.getInt(c.getColumnIndexOrThrow("assistant_progress")) != 0, c.getDouble(c.getColumnIndexOrThrow("created_at")), c.getLongOrNullM1b("last_activity_seq"),
        c.getStringOrNullM1b("calls_decode_error"), c.getStringOrNullM1b("raw_calls"), c.getStringOrNullM1b("provider_output_items_json"),
    )

    private fun readCalls(conversationId: String, batchId: String): List<ToolCall> = sql.query(
        "SELECT * FROM tool_calls WHERE conversation_id=? AND batch_id=? ORDER BY ordinal", arrayOf(conversationId, batchId),
    ).use { c -> buildList { while (c.moveToNext()) add(readCall(c)) } }

    private fun readCall(conversationId: String, batchId: String, callId: String): ToolCall? = sql.query(
        "SELECT * FROM tool_calls WHERE conversation_id=? AND batch_id=? AND call_id=? LIMIT 1", arrayOf(conversationId, batchId, callId),
    ).use { if (it.moveToFirst()) readCall(it) else null }

    private fun readCall(c: android.database.Cursor) = ToolCall(
        c.getString(c.getColumnIndexOrThrow("conversation_id")), c.getString(c.getColumnIndexOrThrow("batch_id")),
        c.getInt(c.getColumnIndexOrThrow("ordinal")), c.getString(c.getColumnIndexOrThrow("call_id")), c.getString(c.getColumnIndexOrThrow("name")),
        c.getString(c.getColumnIndexOrThrow("arguments_json")), c.getString(c.getColumnIndexOrThrow("lifecycle")), c.getLongOrNullM1b("lifecycle_seq"),
    )

    private fun readToolResult(conversationId: String, batchId: String, callId: String): ToolResult? = sql.query(
        "SELECT * FROM tool_results WHERE conversation_id=? AND batch_id=? AND call_id=? LIMIT 1", arrayOf(conversationId, batchId, callId),
    ).use { if (it.moveToFirst()) readToolResult(it) else null }

    private fun readToolResult(c: android.database.Cursor) = ToolResult(
        c.getString(c.getColumnIndexOrThrow("conversation_id")), c.getString(c.getColumnIndexOrThrow("batch_id")),
        c.getString(c.getColumnIndexOrThrow("call_id")), c.getLong(c.getColumnIndexOrThrow("seq")), c.getString(c.getColumnIndexOrThrow("tool_name")),
        c.getString(c.getColumnIndexOrThrow("lifecycle")), c.getString(c.getColumnIndexOrThrow("output")), c.getDouble(c.getColumnIndexOrThrow("created_at")),
        c.getStringOrNullM1b("file_artifact_id"),
    )

    private fun readModelError(conversationId: String, seq: Long): ModelError? = sql.query(
        "SELECT * FROM model_errors WHERE conversation_id=? AND seq=?", arrayOf(conversationId, seq),
    ).use { if (it.moveToFirst()) ModelError(it.getString(0), it.getLong(1), it.getStringOrNullM1b("owner_user_message_id"), it.getLong(3), it.getString(4)) else null }

    private fun readModelError(it: android.database.Cursor) = ModelError(it.getString(0), it.getLong(1), it.getStringOrNullM1b("owner_user_message_id"), it.getLong(3), it.getString(4))

    private fun readProviderOutput(conversationId: String, seq: Long): ModelProviderOutput? = sql.query(
        "SELECT * FROM model_provider_outputs WHERE conversation_id=? AND seq=?", arrayOf(conversationId, seq),
    ).use { if (it.moveToFirst()) readProviderOutput(it) else null }

    private fun readProviderOutput(c: android.database.Cursor) = ModelProviderOutput(c.getString(0), c.getLong(1), c.getString(2), c.getLong(3), c.getString(4))

    private fun readModelArtifact(conversationId: String, id: String): ModelArtifact? = sql.query(
        "SELECT * FROM model_artifacts WHERE conversation_id=? AND artifact_id=?", arrayOf(conversationId, id),
    ).use { if (it.moveToFirst()) readModelArtifact(it) else null }

    private fun readModelArtifactBySeq(conversationId: String, seq: Long): ModelArtifact? = sql.query(
        "SELECT * FROM model_artifacts WHERE conversation_id=? AND seq=?", arrayOf(conversationId, seq),
    ).use { if (it.moveToFirst()) readModelArtifact(it) else null }

    private fun readModelArtifact(c: android.database.Cursor) = ModelArtifact(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4), c.getLong(5), c.getLong(6), c.getStringOrNullM1b("file_ref_id"))

    private fun ensureFileRef(relativePath: String, sha256: String, bytes: Long, mimeType: String, nowMillis: Long): String {
        val existing = sql.query("SELECT id,sha256,bytes FROM file_refs WHERE relative_path=?", arrayOf(relativePath)).use { c ->
            if (c.moveToFirst()) Triple(c.getString(0), c.getString(1).orEmpty(), c.getLong(2)) else null
        }
        if (existing != null) {
            val (id, storedSha256, storedBytes) = existing
            if (storedBytes != bytes || (storedSha256.isNotEmpty() && sha256.isNotEmpty() && storedSha256 != sha256))
                throw M1bAbort("file_reference_content_conflict")
            // Empty means the existing owner did not know the digest; preserve a known digest or
            // complete the unknown value when this artifact supplies one.
            val resolvedSha256 = if (storedSha256.isEmpty()) sha256 else storedSha256
            sql.execSQL("UPDATE file_refs SET sha256=?,mime_type=?,last_referenced_at=?,not_before=NULL,attempts=0,generation=generation+1 WHERE id=?",
                arrayOf(resolvedSha256, mimeType, nowMillis, id))
            return id
        }
        val created = UUID.randomUUID().toString()
        sql.execSQL("INSERT INTO file_refs(id,sha256,relative_path,bytes,mime_type,last_referenced_at,attempts,generation) VALUES(?,?,?,?,?,?,0,0)",
            arrayOf(created, sha256, relativePath, bytes, mimeType, nowMillis))
        return created
    }

    private fun readToolActivityByEvent(conversationId: String, eventId: String): ToolActivity? = sql.query(
        "SELECT * FROM tool_activities WHERE conversation_id=? AND event_id=? LIMIT 1", arrayOf(conversationId, eventId),
    ).use { if (it.moveToFirst()) readToolActivity(it) else null }

    private fun readToolActivity(c: android.database.Cursor) = ToolActivity(
        c.getString(0), c.getLong(1), c.getString(2), c.getStringOrNullM1b("owner_user_message_id"), c.getStringOrNullM1b("source_batch_id"),
        c.getStringOrNullM1b("execution_id"), c.getStringOrNullM1b("call_id"), c.getString(7), c.getStringOrNullM1b("display_name"),
        c.getStringOrNullM1b("skill_id"), c.getStringOrNullM1b("skill_name"), c.getString(11), c.getStringOrNullM1b("detail"),
        c.getStringOrNullM1b("preview_id"), c.getStringOrNullM1b("audit_detail"), c.getStringOrNullM1b("reflection_source"), c.getLong(16), c.getDouble(17),
    )

    private fun readToolPresentation(conversationId: String, seq: Long): ToolPresentation? = sql.query(
        "SELECT * FROM tool_presentations WHERE conversation_id=? AND seq=?", arrayOf(conversationId, seq),
    ).use { if (it.moveToFirst()) readToolPresentation(it) else null }

    private fun readToolPresentation(c: android.database.Cursor) = ToolPresentation(
        c.getString(0), c.getLong(1), c.getStringOrNullM1b("owner_user_message_id"), c.getStringOrNullM1b("batch_id"), c.getStringOrNullM1b("call_id"),
        c.getString(5), c.getString(6), c.getStringOrNullM1b("detail"), c.getStringOrNullM1b("audit_detail"), c.getInt(9) != 0,
        c.getStringOrNullM1b("preview_id"), c.getDouble(11),
    )

    private fun readReflectionToolMarker(conversationId: String, seq: Long): ReflectionToolMarker? = sql.query(
        "SELECT * FROM reflection_tool_markers WHERE conversation_id=? AND seq=?", arrayOf(conversationId, seq),
    ).use { if (it.moveToFirst()) ReflectionToolMarker(it.getString(0), it.getLong(1), it.getStringOrNullM1b("owner_user_message_id"),
        it.getStringOrNullM1b("batch_id"), it.getStringOrNullM1b("call_id"), it.getString(5), it.getString(6), it.getString(7), it.getString(8), it.getDouble(9)) else null }

    private data class SpineEntry(val seq: Long, val kind: String, val key: String, val sortSeq: Long, val sortSub: Int)
    private data class CompactionBoundary(
        val seq: Long,
        val compactionId: String,
        val firstKept: Long,
        val sortSeq: Long,
        val sortSub: Int,
    )

    private fun encodeCallItemKey(batchId: String, callId: String): String =
        "v2|${batchId.length}:$batchId:${callId.length}:$callId"

    private fun decodeCallItemKey(value: String): Pair<String, String>? {
        if (value.startsWith("v2|")) {
            val batchLengthEnd = value.indexOf(':', 3)
            if (batchLengthEnd <= 3) return null
            val batchLength = value.substring(3, batchLengthEnd).toIntOrNull() ?: return null
            val batchStart = batchLengthEnd + 1
            if (batchLength < 0 || batchLength > value.length - batchStart) return null
            val batchEnd = batchStart + batchLength
            if (batchEnd >= value.length || value[batchEnd] != ':') return null
            val callLengthEnd = value.indexOf(':', batchEnd + 1)
            if (callLengthEnd <= batchEnd + 1) return null
            val callLength = value.substring(batchEnd + 1, callLengthEnd).toIntOrNull() ?: return null
            val callStart = callLengthEnd + 1
            if (callLength < 0 || callLength != value.length - callStart) return null
            return value.substring(batchStart, batchEnd) to value.substring(callStart)
        }

        // Rows written before the opaque-ID format used a delimiter-only batch prefix.
        val batchEnd = value.indexOf(':')
        val lengthEnd = if (batchEnd < 0) -1 else value.indexOf(':', batchEnd + 1)
        if (batchEnd <= 0 || lengthEnd <= batchEnd + 1) return null
        val callLength = value.substring(batchEnd + 1, lengthEnd).toIntOrNull() ?: return null
        val callId = value.substring(lengthEnd + 1)
        if (callLength != callId.length) return null
        return value.substring(0, batchEnd) to callId
    }

    companion object {
        private const val INLINE_TOOL_OUTPUT_CHARS = 8_000
        private const val MAX_MODEL_ARTIFACT_BYTES = 16L * 1024L * 1024L
        /** Keep the conversation bind plus the IN values under SQLite's conservative variable limit. */
        private const val MAX_IN_QUERY_IDS = 900

        private fun legacyCompactionRowIndex(compactionId: String): Long? {
            if (!compactionId.startsWith("legacy-")) return null
            val rowIndex = compactionId.substringAfterLast('-', missingDelimiterValue = "")
            return rowIndex.toLongOrNull()?.takeIf { it >= 0L }
        }

        /** Result-only strict-journal rows never contribute to the filtered legacy rowIndex. */
        private fun eligibleTimelineExclusionSql() = "('crew_result_evidence','workspace_result_reference','workspace_result_notice','workspace_result_wake_pending','workspace_result_wake_started','workspace_result_wake_acknowledged','crew_coordination_evidence','crew_coordination_evidence_ref','workspace_coordination_reference','workspace_coordination_notice','workspace_coordination_reply_intent','workspace_coordination_reply_state','workspace_input_incorporation')"
    }
}

internal fun android.database.Cursor.getStringOrNullM1b(name: String): String? = getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getString(it) }
private fun android.database.Cursor.getLongOrNullM1b(name: String): Long? = getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getLong(it) }
