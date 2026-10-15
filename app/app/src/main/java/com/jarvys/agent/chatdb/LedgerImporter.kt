package com.jarvys.agent.chatdb

import com.jarvys.agent.ChatAttachment
import com.jarvys.agent.crew.CrewCoordinationEvidence
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewResultEvidence
import com.jarvys.agent.workspaces.WorkspaceCoordinationReference
import com.jarvys.agent.workspaces.WorkspaceCoordinationReply
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceInputIncorporation
import com.jarvys.agent.workspaces.WorkspaceResultReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** M2 one-conversation importer. It only reads the legacy ledger and writes through M1 repositories. */
internal class LedgerImporter(
    private val database: JarvysChatDatabase,
    private val options: LedgerImportOptions = LedgerImportOptions(),
) {
    private val conversations = ConversationRepository(database)
    private val model = ModelTranscriptRepository(database)
    private val memory = MemoryTimelineRepository(database)
    private val artifacts = ConversationArtifactsRepository(database)
    private val journals = StrictJournalRepository(database)

    suspend fun importDeletedMetaIfPresent(sessionId: String, metaFile: File): ImportReport? = withContext(Dispatchers.IO) {
        if (!metaFile.isFile || !readMetadata(metaFile).deleted) return@withContext null
        importConversation(sessionId, File(metaFile.parentFile, "$sessionId.jsonl"), metaFile)
    }

    suspend fun importConversation(
        sessionId: String,
        ledgerFile: File,
        metaFile: File,
        options: LedgerImportOptions = this.options,
    ): ImportReport = withContext(Dispatchers.IO) {
        require(sessionId.isNotBlank())
        require(options.batchSize > 0)
        val started = System.currentTimeMillis()
        val bytes = ledgerFile.length()
        val databaseBefore = databaseBytes()
        val conversationPayloadBefore = conversationPayloadBytes(sessionId)
        val metadata = readMetadata(metaFile)
        if (metadata.deleted) {
            val tombstone = conversations.deleteConversation(sessionId)
            check(tombstone is ChatDbResult.Success || tombstone is ChatDbResult.Tombstoned) { "could_not_store_tombstone" }
            return@withContext ImportReport(
                sessionId, "tombstone", started, System.currentTimeMillis() - started, bytes,
                databaseBefore, databaseBytes(), 0, 0, 0, 0, emptyMap(), emptyList(),
                listOf(ImportValidation("tombstone", true, "deleted metadata prevents import")),
                conversationPayloadBytesBefore = conversationPayloadBefore,
                conversationPayloadBytesAfter = conversationPayloadBytes(sessionId),
            )
        }
        if (!ledgerFile.isFile) {
            val validation = listOf(ImportValidation("ledger_exists", false, "ledger file is missing"))
            return@withContext reportFailure(sessionId, started, bytes, databaseBefore, 0, 0, 0, 0, emptyMap(), emptyList(), validation, "ledger_missing",
                conversationPayloadBytesBefore = conversationPayloadBefore)
        }

        val preflight = try {
            preflight(sessionId, ledgerFile)
        } catch (failure: Throwable) {
            withContext(NonCancellable) { runCatching { clearPreflightStage(sessionId) } }
            throw failure
        }
        try {
        val sha256 = sha256(ledgerFile)
        val firstTimestamp = preflight.firstTimestampMillis ?: 0L
        val conversation = Conversation(
            id = sessionId,
            kind = conversationKind(sessionId),
            title = metadata.title,
            generated_title = preflight.firstTitle,
            pinned = metadata.pinned,
            archived = metadata.archived,
            private_code = metadata.privateCode,
            created_at = firstTimestamp,
            updated_at = firstTimestamp,
            import_state = "in_progress",
            legacy_ledger_bytes = bytes,
            legacy_ledger_sha256 = sha256Prefix(ledgerFile, 0L),
            import_offset = 0L,
        )
        val ensure = conversations.ensureConversationForImport(conversation)
        if (ensure !is ChatDbResult.Success) {
            val reason = "conversation_setup_${ensure.javaClass.simpleName.lowercase()}"
            return@withContext reportFailure(sessionId, started, bytes, databaseBefore, preflight.lines, 0, preflight.unknown,
                preflight.lines - preflight.unknown, preflight.typeCounts, stagedIssues(sessionId),
                preflight.validations + ImportValidation("line_accounting", true, "all source lines accounted for; conversation setup prevented writes"), reason,
                conversationPayloadBytesBefore = conversationPayloadBefore)
        }
        val existingImport = ensure.value
        val hasPriorImport = existingImport.import_state in setOf("in_progress", "failed", "done")
        val storedOffset = existingImport.import_offset
        val prefixHash = if (storedOffset <= bytes) sha256Prefix(ledgerFile, storedOffset) else null
        // New checkpoints store SHA-256([0, import_offset)). Accept legacy whole-snapshot hashes
        // only when the corresponding old snapshot is still an exact prefix of the current file.
        val priorSourceMatches = when {
            existingImport.legacy_ledger_sha256 == null && storedOffset == 0L -> true
            prefixHash != null && existingImport.legacy_ledger_sha256 == prefixHash -> true
            existingImport.legacy_ledger_bytes != null && existingImport.legacy_ledger_bytes <= bytes &&
                existingImport.legacy_ledger_sha256 == sha256Prefix(ledgerFile, existingImport.legacy_ledger_bytes) -> true
            else -> false
        }
        if (hasPriorImport && !priorSourceMatches) {
            val reason = "resume_source_prefix_changed"
            runCatching {
                conversations.setImportCheckpoint(
                    sessionId, "failed", existingImport.import_offset,
                )
            }
            val validations = preflight.validations + ImportValidation(
                "resume_source_prefix_matches_checkpoint", false,
                "storedOffset=$storedOffset, currentBytes=$bytes, prefixSha256Matches=${existingImport.legacy_ledger_sha256 == prefixHash}",
            )
            return@withContext ImportReport(
                sessionId, "failed", started, System.currentTimeMillis() - started, bytes,
                databaseBefore, databaseBytes(), preflight.lines, 0,
                preflight.lines - preflight.unknown, preflight.unknown, preflight.typeCounts, stagedIssues(sessionId),
                validations + ImportValidation("line_accounting", true, "all source lines accounted for; changed source was not imported"),
                reason, ledgerRowsByType = preflight.typeCounts,
                databaseRowsByType = databaseRowsByType(sessionId), unknownRowsByType = stagedUnknownTypeCounts(sessionId),
                conversationPayloadBytesBefore = conversationPayloadBefore,
                conversationPayloadBytesAfter = conversationPayloadBytes(sessionId),
            )
        }
        database.withChatWriteTransaction {
            database.chatSql.execSQL(
                "UPDATE chatdb_import_messages SET imported=1 WHERE session_id=? AND source_offset<? AND EXISTS(" +
                    "SELECT 1 FROM chatdb_import_message_sources src JOIN messages m ON m.conversation_id=? AND m.seq=src.source_seq " +
                    "WHERE src.session_id=chatdb_import_messages.session_id AND src.source_offset=chatdb_import_messages.source_offset)",
                arrayOf(sessionId, ensure.value.import_offset.coerceAtMost(bytes), sessionId),
            )
            // Appended assistant rows can steer before a message in the already-imported prefix.
            // Reconcile both its display index and the item's sort tuple from the full-ledger
            // preflight, preserving the same ordering a clean import would have written.
            database.chatSql.execSQL(
                "UPDATE messages SET message_index=(SELECT s.message_index FROM chatdb_import_messages s " +
                    "JOIN chatdb_import_message_sources src ON src.session_id=s.session_id AND src.source_offset=s.source_offset " +
                    "WHERE s.session_id=? AND s.imported=1 AND src.source_seq=messages.seq) " +
                    "WHERE conversation_id=? AND EXISTS(SELECT 1 FROM chatdb_import_messages s " +
                    "JOIN chatdb_import_message_sources src ON src.session_id=s.session_id AND src.source_offset=s.source_offset " +
                    "WHERE s.session_id=? AND s.imported=1 AND src.source_seq=messages.seq)",
                arrayOf(sessionId, sessionId, sessionId),
            )
            database.chatSql.execSQL(
                "UPDATE conversation_items SET " +
                    "sort_seq=(SELECT s.sort_seq FROM chatdb_import_messages s " +
                    "JOIN chatdb_import_message_sources src ON src.session_id=s.session_id AND src.source_offset=s.source_offset " +
                    "WHERE s.session_id=? AND s.imported=1 AND src.source_seq=conversation_items.seq)," +
                    "sort_sub=(SELECT s.sort_sub FROM chatdb_import_messages s " +
                    "JOIN chatdb_import_message_sources src ON src.session_id=s.session_id AND src.source_offset=s.source_offset " +
                    "WHERE s.session_id=? AND s.imported=1 AND src.source_seq=conversation_items.seq) " +
                    "WHERE conversation_id=? AND EXISTS(SELECT 1 FROM chatdb_import_messages s " +
                    "JOIN chatdb_import_message_sources src ON src.session_id=s.session_id AND src.source_offset=s.source_offset " +
                    "WHERE s.session_id=? AND s.imported=1 AND src.source_seq=conversation_items.seq)",
                arrayOf(sessionId, sessionId, sessionId, sessionId),
            )
        }
        val exactDoneSource = existingImport.import_state == "done" && existingImport.import_offset == bytes &&
            existingImport.legacy_ledger_bytes == bytes && existingImport.legacy_ledger_sha256 == sha256
        if (exactDoneSource) {
            val previous = conversations.readImportConversation(sessionId)
            val storedSkips = databaseSkippedRows(sessionId)
            val validations = validate(sessionId, ledgerFile, preflight, storedSkips.counts, bytes, sha256)
            val allIssues = (stagedIssues(sessionId) + databaseSkippedIssues(sessionId)).distinctBy { it.lineNumber to it.reason }.sortedBy { it.lineNumber }
            val skipped = allIssues.size.toLong()
            val state = if (validations.all { it.passed }) "done" else "failed"
            if (state == "failed") requireSuccess(conversations.setImportCheckpoint(sessionId, "failed", previous?.import_offset ?: bytes, bytes, sha256))
            return@withContext ImportReport(sessionId, state, started,
                System.currentTimeMillis() - started, bytes, databaseBefore, databaseBytes(), preflight.lines,
                preflight.knownLines - storedSkips.counts.values.sum(), skipped, preflight.unknown, preflight.typeCounts,
                allIssues, validations, if (state == "done") null else "validation_failed",
                ledgerRowsByType = preflight.typeCounts, databaseRowsByType = databaseRowsByType(sessionId),
                unknownRowsByType = stagedUnknownTypeCounts(sessionId),
                conversationPayloadBytesBefore = conversationPayloadBefore,
                conversationPayloadBytesAfter = conversationPayloadBytes(sessionId))
        }
        if (preflight.strictFailure != null) {
            requireSuccess(conversations.setImportCheckpoint(
                sessionId, "failed", ensure.value.import_offset, bytes, prefixHash ?: sha256Prefix(ledgerFile, ensure.value.import_offset),
            ))
            return@withContext reportFailure(
                sessionId, started, bytes, databaseBefore, preflight.lines, 0, preflight.unknown,
                preflight.lines - preflight.unknown, preflight.typeCounts, stagedIssues(sessionId),
                preflight.validations + ImportValidation("strict_journal", false, preflight.strictFailure) +
                    ImportValidation("line_accounting", true, "all ${preflight.lines} source lines are accounted for; strict validation prevented writes"),
                preflight.strictFailure,
                conversationPayloadBytesBefore = conversationPayloadBefore,
            )
        }

        var sourceOffset = ensure.value.import_offset.coerceAtMost(bytes)
        var lineNumber = countLinesBefore(ledgerFile, sourceOffset) + 1L
        val previouslyStoredSkips = databaseSkippedRows(sessionId)
        val skippedByType = previouslyStoredSkips.counts.toMutableMap()
        var importedLines = 0L
        var skippedLines = 0L
        var unknownLines = 0L
        var batchNumber = 0
        var lastTurnAnchor = lastOrdinaryUserSeq(sessionId)
        var sourceDigest = sha256DigestPrefix(ledgerFile, sourceOffset)
        requireSuccess(conversations.setImportCheckpoint(
            sessionId, "in_progress", sourceOffset, bytes, digestAtOffset(sourceDigest, ledgerFile, sourceOffset),
        ))

        try {
            NonInterruptibleLedgerFile(ledgerFile, sourceOffset, startLineNumber = lineNumber).use { reader ->
                while (true) {
                    var completedInBatch = 0
                    conversations.withImportBatch(sessionId) {
                            while (completedInBatch < options.batchSize) {
                                val line = reader.readLine() ?: break
                                updateDigest(sourceDigest, line)
                                sourceOffset = line.endOffset
                                lineNumber = line.lineNumber + 1L
                                completedInBatch++
                                val outcome = importLine(sessionId, line, preflight, lastTurnAnchor)
                                when (outcome.kind) {
                                    LineOutcomeKind.IMPORTED -> {
                                        importedLines++
                                        if (outcome.ordinaryUserSeq != null) lastTurnAnchor = outcome.ordinaryUserSeq
                                    }
                                    LineOutcomeKind.SKIPPED -> {
                                        skippedLines++
                                        if (outcome.type.isNotEmpty()) skippedByType[outcome.type] = (skippedByType[outcome.type] ?: 0L) + 1L
                                    }
                                    LineOutcomeKind.UNKNOWN -> unknownLines++
                                }
                            }
                            requireSuccess(conversations.setImportCheckpoint(
                                sessionId, "in_progress", sourceOffset, bytes,
                                digestAtOffset(sourceDigest, ledgerFile, sourceOffset),
                            ))
                            batchNumber++
                            if (completedInBatch > 0) options.beforeBatchCommit?.invoke(batchNumber)
                    }
                    if (completedInBatch == 0) break
                    options.onProgress?.invoke(ImportProgress(sessionId, "in_progress", sourceOffset, bytes))
                }
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            val stored = conversations.readImportConversation(sessionId)
            val committedOffset = stored?.import_offset ?: sourceOffset
            runCatching {
                conversations.setImportCheckpoint(
                    sessionId, "failed", committedOffset, bytes,
                    stored?.legacy_ledger_sha256 ?: sha256Prefix(ledgerFile, committedOffset),
                )
            }
            val committedSkips = databaseSkippedRows(sessionId)
            val strictFailure = failure as? StrictLedgerFailure
            val strictIssue = strictFailure?.let { ImportLineIssue(it.lineNumber, it.reason, it.bytes) }
            val committedIssues = (stagedIssues(sessionId, committedOffset) + databaseSkippedIssues(sessionId) + listOfNotNull(strictIssue))
                .distinctBy { it.lineNumber to it.reason }.sortedBy { it.lineNumber }
            val committedPrefix = database.chatSql.query(
                "SELECT COUNT(*),SUM(CASE WHEN status IN ('known','title') THEN 1 ELSE 0 END)," +
                    "SUM(CASE WHEN status='malformed' THEN 1 ELSE 0 END),SUM(CASE WHEN status='unknown' THEN 1 ELSE 0 END) " +
                    "FROM chatdb_import_rows WHERE session_id=? AND source_offset<?",
                arrayOf(sessionId, committedOffset),
            ).use { c -> c.moveToFirst(); LongArray(4) { index -> if (c.isNull(index)) 0L else c.getLong(index) } }
            val unknownCommitted = scalarLong(
                "SELECT COUNT(*) FROM chatdb_import_rows WHERE session_id=? AND status='unknown' AND source_offset<?",
                arrayOf(sessionId, committedOffset),
            )
            val semanticSkipped = committedSkips.counts.values.sum()
            val importedCommitted = (committedPrefix[1] - semanticSkipped).coerceAtLeast(0L)
            val committedLines = committedPrefix[0] + if (strictFailure != null && strictFailure.sourceOffset >= committedOffset) 1L else 0L
            val skippedCommitted = committedPrefix[2] + semanticSkipped + if (strictIssue != null) 1L else 0L
            val validation = preflight.validations + if (strictFailure != null) {
                ImportValidation("strict_journal", false, failure.message ?: failure.javaClass.simpleName)
            } else {
                ImportValidation("import_completed", false, failure.message ?: failure.javaClass.simpleName)
            }
            return@withContext ImportReport(sessionId, "failed", started, System.currentTimeMillis() - started, bytes,
                databaseBefore, databaseBytes(), committedLines,
                importedCommitted, skippedCommitted, unknownCommitted,
                preflight.typeCounts, committedIssues, validation, failure.message ?: failure.javaClass.simpleName,
                ledgerRowsByType = preflight.typeCounts, databaseRowsByType = databaseRowsByType(sessionId),
                unknownRowsByType = stagedUnknownTypeCounts(sessionId, committedOffset),
                conversationPayloadBytesBefore = conversationPayloadBefore,
                conversationPayloadBytesAfter = conversationPayloadBytes(sessionId))
        }

        // The imported offset is advanced only after row operations and the checkpoint commit together.
        importedLines = preflight.knownLines - skippedByType.values.sum()
        val finalIssues = (stagedIssues(sessionId) + databaseSkippedIssues(sessionId))
            .distinctBy { it.lineNumber to it.reason }.sortedBy { it.lineNumber }
        skippedLines = finalIssues.size.toLong()
        unknownLines = preflight.unknown
        val validations = validate(sessionId, ledgerFile, preflight, skippedByType, bytes, sha256)
        val state = if (validations.all { it.passed }) "done" else "failed"
        val databaseRowsByType = databaseRowsByType(sessionId)
        val report = ImportReport(sessionId, state, started, System.currentTimeMillis() - started, bytes,
            databaseBefore, databaseBytes(), preflight.lines, importedLines, skippedLines, unknownLines,
            preflight.typeCounts, finalIssues, validations,
            if (state == "done") null else "validation_failed",
            ledgerRowsByType = preflight.typeCounts, databaseRowsByType = databaseRowsByType,
            unknownRowsByType = stagedUnknownTypeCounts(sessionId),
            conversationPayloadBytesBefore = conversationPayloadBefore,
            conversationPayloadBytesAfter = conversationPayloadBytes(sessionId))
        requireSuccess(conversations.setImportCheckpoint(
            sessionId, state, sourceOffset, bytes, digestAtOffset(sourceDigest, ledgerFile, sourceOffset),
            importedAtMillis = if (state == "done") System.currentTimeMillis() else null,
        ))
        report
        } finally {
            clearPreflightStage(sessionId)
        }
    }

    /** True only when the durable done receipt still describes the complete current JSONL file. */
    internal suspend fun isValidatedSourceCurrent(sessionId: String, ledgerFile: File): Boolean = withContext(Dispatchers.IO) {
        if (!ledgerFile.isFile) return@withContext false
        val conversation = conversations.readImportConversation(sessionId) ?: return@withContext false
        val bytes = ledgerFile.length()
        conversation.import_state == "done" && conversation.import_offset == bytes &&
            conversation.legacy_ledger_bytes == bytes && conversation.legacy_ledger_sha256 == sha256(ledgerFile)
    }

    /**
     * Purges this conversation's partially imported domain and staging rows, preserving its
     * identity/metadata and leaving an explicit not_started receipt for a clean reimport.
     */
    internal suspend fun resetConversationImport(sessionId: String): ChatDbResult<Boolean> = withContext(Dispatchers.IO) {
        require(sessionId.isNotBlank())
        createPreflightTables()
        database.withChatWriteTransaction {
            val tombstoned = database.chatSql.query(
                "SELECT EXISTS(SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=?)",
                arrayOf(sessionId),
            ).use { it.moveToFirst(); it.getInt(0) != 0 }
            if (tombstoned) return@withChatWriteTransaction ChatDbResult.Tombstoned
            val metadata = database.chatSql.query(
                "SELECT kind,title,pinned,archived,private_code,created_at,updated_at FROM conversations WHERE id=?",
                arrayOf(sessionId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else arrayOf<Any?>(
                    cursor.getString(0), if (cursor.isNull(1)) null else cursor.getString(1), cursor.getInt(2), cursor.getInt(3),
                    cursor.getInt(4), cursor.getLong(5), cursor.getLong(6),
                )
            } ?: return@withChatWriteTransaction ChatDbResult.NotFound

            // This is a migration reset, not a user delete: suppress only the delete-to-tombstone
            // trigger while the parent row and all cascades are replaced in this same transaction.
            database.chatSql.execSQL("DROP TRIGGER IF EXISTS trg_conversation_tombstone_after_delete")
            database.chatSql.execSQL("DELETE FROM conversations WHERE id=?", arrayOf(sessionId))
            database.chatSql.execSQL(
                "INSERT INTO conversations(id,kind,title,generated_title,pinned,archived,private_code,created_at,updated_at,last_message_at,message_count,next_seq,import_state,legacy_ledger_bytes,legacy_ledger_sha256,imported_at,import_offset) " +
                    "VALUES(?,?,?,NULL,?,?,?,?,?,NULL,0,1,'not_started',NULL,NULL,NULL,0)",
                arrayOf(sessionId, metadata[0], metadata[1], metadata[2], metadata[3], metadata[4], metadata[5], metadata[6]),
            )
            JarvysChatDatabaseProvider.DatabaseTriggers.create(database.chatSql)
            clearPreflightStageRows(sessionId)
            ChatDbResult.Success(true)
        }
    }

    private suspend fun importLine(
        sessionId: String,
        line: NonInterruptibleLedgerFile.Line,
        preflight: Preflight,
        turnAnchorSeq: Long?,
    ): LineOutcome {
        if (!line.terminated) {
            val reason = "truncated_final_line"
            if (isStrictLedger(sessionId) || likelyStrictRow(line.bytes))
                throw StrictLedgerFailure(line.lineNumber, line.startOffset, line.bytesIncludingTerminator, reason, "line ${line.lineNumber}:$reason")
            return LineOutcome(LineOutcomeKind.SKIPPED, "", reason)
        }
        val row = try {
            parseLedgerObject(StrictLedgerJson.decodeUtf8(line.bytes))
        } catch (failure: Throwable) {
            val reason = when (failure) {
                is DuplicateLedgerJsonKey -> "duplicate_key:${failure.key}"
                is java.nio.charset.CharacterCodingException -> "invalid_utf8"
                else -> "invalid_json:${failure.javaClass.simpleName}"
            }
            if (isStrictLedger(sessionId) || likelyStrictRow(line.bytes))
                throw StrictLedgerFailure(line.lineNumber, line.startOffset, line.bytesIncludingTerminator, reason, "line ${line.lineNumber}:$reason")
            return LineOutcome(LineOutcomeKind.SKIPPED, "", reason)
        }
        val type = ledgerType(row)
        if (type !in KNOWN_TYPES) return LineOutcome(LineOutcomeKind.UNKNOWN, type.ifBlank { "(untyped)" })
        if (type == "session_title") {
            val title = row.optString("content", "").trim()
            if (title.isNotEmpty()) {
                val position = ImportedLedgerPosition(
                    conversationId = sessionId,
                    seq = nextSequence(sessionId),
                    createdAtSeconds = timestampSeconds(row, type),
                    updatedAtMillis = sourceUpdatedAtMillis(row),
                )
                val result = database.withImportPosition(position) { conversations.appendConversationTitleIfAbsent(sessionId, title) }
                requireSuccess(result)
            }
            return LineOutcome(LineOutcomeKind.IMPORTED, type)
        }
        val timestamp = timestampSeconds(row, type)
        val sequence = nextSequence(sessionId)
        val messageMeta = readMessageMeta(sessionId, line.startOffset)
        val position = ImportedLedgerPosition(
            conversationId = sessionId,
            seq = sequence,
            createdAtSeconds = timestamp,
            sortSeq = messageMeta?.sortSeq ?: sequence,
            sortSub = messageMeta?.sortSub ?: 0,
            turnAnchorSeq = if (messageMeta?.ordinaryUser == true) {
                sequence
            } else {
                turnAnchorSeq ?: lastOrdinaryUserSeq(sessionId)
            },
            updatedAtMillis = sourceUpdatedAtMillis(row),
        )
        val savepoint = "import_row_${line.lineNumber}"
        database.chatSql.execSQL("SAVEPOINT $savepoint")
        return try {
            database.withImportPosition(position) {
                applyKnownRow(sessionId, row, type, line, preflight, messageMeta, position)
            }
            if (type != "session_title" && nextSequence(sessionId) == sequence) {
                val markerKey = markerKey(type, row, line.startOffset)
                requireSuccess(conversations.appendImportMarker(position, type, markerKey))
            }
            val after = nextSequence(sessionId)
            check(type == "session_title" || after == sequence + 1L) { "import row did not consume exactly one sequence ($type)" }
            if (messageMeta != null) {
                val persisted = database.chatSql.query(
                    "SELECT COUNT(*) FROM messages WHERE conversation_id=? AND seq=?", arrayOf(sessionId, sequence),
                ).use { it.moveToFirst(); it.getLong(0) > 0L }
                database.chatSql.execSQL(
                    "UPDATE chatdb_import_messages SET imported=? WHERE session_id=? AND source_offset=?",
                    arrayOf(persisted, sessionId, line.startOffset),
                )
            }
            database.chatSql.execSQL("RELEASE SAVEPOINT $savepoint")
            LineOutcome(LineOutcomeKind.IMPORTED, type,
                ordinaryUserSeq = if (messageMeta?.ordinaryUser == true) sequence else null)
        } catch (failure: Throwable) {
            runCatching { database.chatSql.execSQL("ROLLBACK TO SAVEPOINT $savepoint") }
            runCatching { database.chatSql.execSQL("RELEASE SAVEPOINT $savepoint") }
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            val reason = "invalid_row:${failure.javaClass.simpleName}"
            stageIssue(sessionId, line.startOffset, line.lineNumber, reason, line.bytesIncludingTerminator)
            if (type in STRICT_TYPES) throw StrictLedgerFailure(
                line.lineNumber, line.startOffset, line.bytesIncludingTerminator, reason,
                "line ${line.lineNumber}:strict_row:${failure.message ?: failure.javaClass.simpleName}",
            )
            val skipPosition = position.copy(turnAnchorSeq = turnAnchorSeq)
            requireSuccess(conversations.appendImportMarker(skipPosition, "import_skipped_row",
                "$type:${line.lineNumber}:${line.startOffset}:${line.bytesIncludingTerminator}:$reason"))
            LineOutcome(LineOutcomeKind.SKIPPED, type, reason)
        }
    }

    private suspend fun applyKnownRow(
        sessionId: String,
        row: JSONObject,
        type: String,
        line: NonInterruptibleLedgerFile.Line,
        preflight: Preflight,
        messageMeta: MessageMeta?,
        position: ImportedLedgerPosition,
    ): Any? {
        fun success(result: ChatDbResult<*>): Any? = requireSuccess(result)
        return when (type) {
            "user", "assistant", "proactive_message" -> {
                val messageId = messageMeta?.rawMessageId ?: row.stringOrNull("messageId")
                    ?: legacyMessageId(sessionId, messageMeta?.messageIndex ?: 0L)
                val stableId = messageId
                val index = messageMeta?.messageIndex ?: 0L
                val parts = listOf(MessagePartInput(MessagePartKinds.TEXT, row.optString("content", "")))
                val attachments = if (type == "user") parseAttachments(row.optJSONArray("attachments")) else emptyList()
                if (type == "proactive_message") {
                    conversations.appendProactiveAssistantMessageImported(
                        sessionId, row.getString("proactiveDecisionId"), row.optString("content", ""),
                        row.stringOrNull("proactiveThreadKey"), parseReplies(row.optJSONArray("suggestedReplies")),
                        messageId, stableId, index,
                    )
                } else {
                    conversations.appendMessageImported(AppendMessageInput(
                        conversationId = sessionId,
                        role = type,
                        parts = parts,
                        status = row.stringOrNull("status"),
                        runId = row.stringOrNull("runId"),
                        ownerUserMessageId = row.stringOrNull("userMessageId"),
                        durationMillis = row.longOrNull("durationMs"),
                        beforeMessageId = row.stringOrNull("beforeMessageId"),
                        proactiveThreadKey = row.stringOrNull("proactiveThreadKey"),
                        proactiveReplyToMessageId = row.stringOrNull("proactiveReplyToMessageId"),
                        interruptRequestId = row.stringOrNull("interruptRequestId"),
                        acknowledgementRequired = row.optBoolean("mainChatAcknowledgementRequired", false),
                        attachments = attachments,
                        createdAtSeconds = position.createdAtSeconds,
                    ), messageId, stableId, index)
                }
            }
            "assistant_regenerated", "assistant_deleted" -> conversations.hideAssistant(sessionId, row.getString("messageId"), type)
            "assistant_translation" -> conversations.setTranslation(sessionId, row.getString("messageId"), row.getString("language"), row.getString("content"))
            "assistant_translation_hidden" -> conversations.hideTranslation(sessionId, row.getString("messageId"))
            "message_reaction" -> conversations.setReaction(sessionId, row.getString("messageId"), row.optString("emoji", ""))
            "proactive_reply_used" -> conversations.consumeSuggestedReplies(sessionId, row.getString("messageId"))
            "user_decision_request" -> conversations.appendDecisionRequest(
                sessionId, row.getString("decisionId"), row.optString("title", ""), row.optString("body", ""),
                row.optBoolean("allowDismiss", false), parseDecisionOptions(row.optJSONArray("options")),
            )
            "user_decision_resolution" -> conversations.resolveDecision(
                sessionId, row.getString("decisionId"), row.getString("status"), row.stringOrNull("optionId"), row.stringOrNull("optionLabel"),
            )
            "delivered_file" -> {
                val attachment = ChatAttachment.fromJson(row.optJSONObject("artifact")) ?: error("invalid delivered artifact")
                artifacts.appendDeliveredArtifactImported(DeliveredArtifactInput(
                    sessionId, attachment.id, attachment.name, attachment.mimeType, attachment.sizeBytes,
                    attachment.kind.name, attachment.relativePath, "",
                    createdAtSeconds = position.createdAtSeconds,
                ))
            }
            "generated_image" -> artifacts.appendGeneratedImageImported(GeneratedImageInput(
                conversationId = sessionId,
                prompt = row.optString("prompt", ""),
                revisedPrompt = row.optString("revisedPrompt", ""),
                size = row.optString("size", ""),
                mimeType = row.optString("mimeType", "image/png"),
                status = row.optString("status", "FAILED"),
                imagePath = row.stringOrNull("imagePath"),
                sha256 = row.stringOrNull("sha256"),
                bytes = row.longOrNull("bytes"),
                error = row.stringOrNull("error"),
                createdAtSeconds = position.createdAtSeconds,
            ))
            "main_interrupt_request" -> conversations.appendInterruptRequestImported(
                sessionId, row.getString("requestId"), row.getString("content"), parseStringArray(row.optJSONArray("skills")),
                row.optBoolean("memoryDisabled", false),
            )
            "main_chat_input_incorporated" -> conversations.markInputStateImported(
                sessionId, parseStringArray(row.optJSONArray("messageIds")), ConversationRepository.INPUT_INCORPORATED,
            )
            "main_chat_input_resolved" -> conversations.markInputStateImported(
                sessionId, parseStringArray(row.optJSONArray("messageIds")), ConversationRepository.INPUT_RESOLVED,
            )
            "model_tool_calls" -> model.appendToolBatchImported(ModelToolBatchInput(
                conversationId = sessionId,
                batchId = row.getString("batchId"),
                ownerUserMessageId = row.stringOrNull("userMessageId"),
                messageIndex = row.optLong("messageIndex", 0L),
                assistantText = row.optString("assistantText", ""),
                assistantProgress = row.optBoolean("assistantProgress", false),
                calls = parseModelCalls(row.optJSONArray("calls")),
                providerOutputItemsJson = row.optJSONArray("providerOutputItems")?.toString(),
                createdAtSeconds = position.createdAtSeconds,
            ))
            "model_tool_started", "model_tool_not_started" -> model.setCallLifecycle(
                sessionId, row.getString("batchId"), row.getString("callId"), if (type == "model_tool_started") "STARTED" else "NEVER_LAUNCHED",
            )
            "model_tool_result" -> model.appendToolResultImported(ModelToolResultInput(
                sessionId, row.getString("batchId"), row.getString("callId"), row.getString("toolName"),
                row.getString("lifecycle"), row.optString("output", ""), row.stringOrNull("artifactId"), position.createdAtSeconds,
            ))
            "model_artifact" -> {
                val value = row.getJSONObject("artifact")
                model.appendModelArtifactImported(sessionId, row.getString("artifactId"), value.getString("path"), value.getString("sha256"),
                    value.getLong("chars"), value.getLong("bytes"), value.optString("mimeType", "text/plain"))
            }
            "model_error" -> model.appendModelError(sessionId, row.stringOrNull("userMessageId"), row.optLong("messageIndex", 0L), row.getString("errorCode"))
            "model_provider_output_items" -> model.appendProviderOutputItems(sessionId, row.getString("userMessageId"), row.optLong("messageIndex", 0L), row.getJSONArray("items").toString())
            "tool_presentation" -> model.appendToolPresentation(ToolPresentationInput(
                sessionId, row.stringOrNull("userMessageId"), row.stringOrNull("batchId"), row.stringOrNull("callId"), row.getString("toolName"),
                row.getString("stage"), row.stringOrNull("detail"), row.stringOrNull("auditDetail"), row.optBoolean("previewExpected", false),
                row.stringOrNull("previewId"), position.createdAtSeconds,
            ))
            "tool_activity" -> {
                val activity = row.optJSONObject("activity") ?: row
                model.appendToolActivity(ToolActivityInput(
                    conversationId = sessionId,
                    eventId = activity.getString("eventId"),
                    ownerUserMessageId = row.stringOrNull("userMessageId"),
                    executionId = activity.stringOrNull("executionId"),
                    callId = activity.stringOrNull("callId"),
                    toolName = activity.getString("toolName"),
                    displayName = activity.stringOrNull("displayName"),
                    skillId = activity.stringOrNull("skillId"),
                    skillName = activity.stringOrNull("skillName"),
                    stage = activity.getString("stage"),
                    detail = activity.stringOrNull("detail"),
                    previewId = activity.stringOrNull("previewId"),
                    auditDetail = activity.stringOrNull("auditDetail"),
                    reflectionSource = activity.stringOrNull("reflectionSource"),
                    timestampMillis = activity.longOrNull("timestampMillis") ?: (position.createdAtSeconds * 1000.0).toLong(),
                    createdAtSeconds = position.createdAtSeconds,
                    sourceBatchId = row.stringOrNull("batchId"),
                ))
            }
            "reflection_tool" -> model.appendReflectionTool(ReflectionToolInput(
                sessionId, row.stringOrNull("userMessageId"), row.stringOrNull("batchId"), row.stringOrNull("callId"), row.getString("toolName"),
                row.optString("source", ""), row.getString("stage"), row.optString("marker", ""), position.createdAtSeconds,
            ))
            "compaction" -> {
                // The staged projection index mirrors LocalRunStore.pendingCompactionIds: it counts
                // valid non-result-only rows after steering, including session_title and each
                // input marker once, even when those rows do not map one-to-one to spine items.
                val rowIndex = readCompactionRowIndex(sessionId, line.startOffset) ?: error("missing legacy compaction row index")
                memory.appendCompaction(CompactionInput(
                    sessionId, row.getString("summary"), row.optLong("firstKept", 0L), row.stringOrNull("trigger"), row.stringOrNull("mode"),
                    row.optLong("summarizedMessages", 0L), position.createdAtSeconds,
                    "legacy-${timestampToken(line.bytes)}-$rowIndex",
                ))
            }
            "compaction_reflection_consumed" -> {
                val ids = parseStringArray(row.optJSONArray("compactionIds")).ifEmpty {
                    row.stringOrNull("compactionId")?.let(::listOf).orEmpty()
                }
                memory.acknowledgeCompactions(sessionId, ids.takeIf { it.isNotEmpty() }, clearAll = ids.isEmpty())
            }
            "reflection_checkpoint" -> memory.appendReflectionCheckpoint(reflectionInput(sessionId, row, position.createdAtSeconds, "reflection_checkpoint"))
            "reflection_commit" -> memory.appendReflectionCommit(reflectionInput(sessionId, row, position.createdAtSeconds, "reflection_commit"))
            "reflection_event" -> memory.appendReflectionEvent(reflectionInput(sessionId, row, position.createdAtSeconds, "reflection_event"))
            "reflection_undo" -> memory.appendReflectionUndo(sessionId, row.getString("reflectionId"), position.createdAtSeconds)
            "crew_snapshot" -> {
                val missionId = row.getString("missionId")
                val lastOffset = readLatestCrewOffset(sessionId, missionId) ?: line.startOffset
                if (line.startOffset == lastOffset) {
                    val firstSeq = firstCrewSnapshotSeq(sessionId, missionId) ?: position.seq
                    memory.appendCrewSnapshotImported(sessionId, row, firstSeq)
                } else {
                    memory.appendCrewSnapshotMarkerImported(sessionId, missionId)
                }
            }
            "crew_result_evidence" -> journals.appendCrewResultEvidence(CrewResultEvidence.fromJsonStrict(row))
            "workspace_result_reference" -> journals.appendWorkspaceResultReference(WorkspaceResultReference.fromJsonStrict(row))
            "workspace_result_notice" -> journals.appendWorkspaceResultNotice(WorkspaceResultReference.fromNoticeJsonStrict(row))
            "crew_coordination_evidence" -> journals.appendCrewCoordinationEvidence(CrewCoordinationEvidence.fromJsonStrict(row))
            "workspace_coordination_reference" -> journals.appendWorkspaceCoordinationReference(WorkspaceCoordinationReference.fromJsonStrict(row))
            "workspace_coordination_notice" -> journals.appendWorkspaceCoordinationNotice(WorkspaceCoordinationReference.fromNoticeJsonStrict(row))
            "workspace_coordination_reply_intent" -> journals.prepareWorkspaceCoordinationReply(WorkspaceCoordinationReply.fromJsonStrict(row))
            "workspace_coordination_reply_state" -> {
                val transition = WorkspaceCoordinationReply.fromStateJsonStrict(row)
                val durable = success(journals.readWorkspaceCoordinationReply(sessionId, transition.referenceId)) as WorkspaceCoordinationReply?
                    ?: error("reply state has no prepared intent")
                check(durable.replyId == transition.replyId && durable.payloadSha256 == transition.payloadSha256 &&
                    durable.reference.executionSessionId == transition.executionSessionId) { "reply state payload differs from intent" }
                val proposed = durable.withState(transition.state, transition.stateAtMillis)
                when (transition.state) {
                    WorkspaceCoordinationReply.State.STARTED -> journals.markWorkspaceCoordinationReplyStarted(proposed)
                    WorkspaceCoordinationReply.State.ACCEPTED_LOCAL -> journals.markWorkspaceCoordinationReplyAccepted(proposed)
                    WorkspaceCoordinationReply.State.PREPARED -> error("PREPARED is not a transition")
                }
            }
            "workspace_input_incorporation" -> journals.appendWorkspaceInputIncorporation(WorkspaceInputIncorporation.fromJsonStrict(row))
            else -> error("unknown type reached known-row mapper")
        }
    }

    /** Large preflight indexes live in SQLite so heap use does not grow with message/marker count. */
    private suspend fun createPreflightTables() {
        database.chatSql.execSQL("CREATE TABLE IF NOT EXISTS chatdb_import_rows (session_id TEXT NOT NULL, source_offset INTEGER NOT NULL, row_type TEXT NOT NULL, status TEXT NOT NULL, PRIMARY KEY(session_id,source_offset))")
        database.chatSql.execSQL("CREATE TABLE IF NOT EXISTS chatdb_import_issues (session_id TEXT NOT NULL, source_offset INTEGER NOT NULL, line_number INTEGER NOT NULL, reason TEXT NOT NULL, bytes INTEGER NOT NULL, PRIMARY KEY(session_id,line_number))")
        database.chatSql.execSQL("CREATE TABLE IF NOT EXISTS chatdb_import_unknown_types (session_id TEXT NOT NULL, row_type TEXT NOT NULL, row_count INTEGER NOT NULL, PRIMARY KEY(session_id,row_type))")
        database.chatSql.execSQL("CREATE TABLE IF NOT EXISTS chatdb_import_messages (session_id TEXT NOT NULL, source_offset INTEGER NOT NULL, raw_message_id TEXT, before_message_id TEXT, is_assistant INTEGER NOT NULL, ordinary_user INTEGER NOT NULL, projection_index INTEGER NOT NULL, sort_seq INTEGER NOT NULL, sort_sub INTEGER NOT NULL, message_index INTEGER NOT NULL DEFAULT -1, imported INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(session_id,source_offset))")
        database.chatSql.execSQL("CREATE TABLE IF NOT EXISTS chatdb_import_message_sources (session_id TEXT NOT NULL, source_offset INTEGER NOT NULL, source_seq INTEGER NOT NULL, PRIMARY KEY(session_id,source_offset))")
        database.chatSql.execSQL("CREATE INDEX IF NOT EXISTS chatdb_import_messages_projection ON chatdb_import_messages(session_id,projection_index,source_offset)")
        database.chatSql.execSQL("CREATE INDEX IF NOT EXISTS chatdb_import_messages_id ON chatdb_import_messages(session_id,raw_message_id,source_offset)")
        database.chatSql.execSQL("CREATE TABLE IF NOT EXISTS chatdb_import_compactions (session_id TEXT NOT NULL, source_offset INTEGER NOT NULL, logical_index INTEGER NOT NULL, PRIMARY KEY(session_id,source_offset))")
        database.chatSql.execSQL("CREATE INDEX IF NOT EXISTS chatdb_import_compactions_logical ON chatdb_import_compactions(session_id,logical_index)")
        database.chatSql.execSQL("CREATE TABLE IF NOT EXISTS chatdb_import_crews (session_id TEXT NOT NULL, mission_id TEXT NOT NULL, source_offset INTEGER NOT NULL, snapshot_digest TEXT NOT NULL, PRIMARY KEY(session_id,mission_id))")
        database.chatSql.execSQL("CREATE TABLE IF NOT EXISTS chatdb_import_hidden (session_id TEXT NOT NULL, message_id TEXT NOT NULL, hidden_kind TEXT NOT NULL, PRIMARY KEY(session_id,message_id))")
        database.chatSql.execSQL("CREATE TABLE IF NOT EXISTS chatdb_import_translations (session_id TEXT NOT NULL, message_id TEXT NOT NULL, language TEXT NOT NULL, PRIMARY KEY(session_id,message_id,language))")
        database.chatSql.execSQL("CREATE TABLE IF NOT EXISTS chatdb_import_translation_hidden (session_id TEXT NOT NULL, message_id TEXT NOT NULL, PRIMARY KEY(session_id,message_id))")
    }

    private suspend fun clearPreflightStageRows(sessionId: String) {
        for (table in PREFLIGHT_TABLES) database.chatSql.execSQL("DELETE FROM $table WHERE session_id=?", arrayOf(sessionId))
    }

    private suspend fun stageRow(sessionId: String, offset: Long, type: String, status: String) {
        database.chatSql.execSQL("INSERT INTO chatdb_import_rows(session_id,source_offset,row_type,status) VALUES(?,?,?,?)", arrayOf(sessionId, offset, type, status))
    }

    private suspend fun stageIssue(sessionId: String, offset: Long, lineNumber: Long, reason: String, bytes: Long) {
        database.chatSql.execSQL(
            "INSERT OR IGNORE INTO chatdb_import_issues(session_id,source_offset,line_number,reason,bytes) VALUES(?,?,?,?,?)",
            arrayOf(sessionId, offset, lineNumber, reason, bytes),
        )
    }

    private suspend fun stageUnknownType(sessionId: String, type: String) {
        database.chatSql.execSQL(
            "INSERT INTO chatdb_import_unknown_types(session_id,row_type,row_count) VALUES(?,?,1) ON CONFLICT(session_id,row_type) DO UPDATE SET row_count=row_count+1",
            arrayOf(sessionId, type),
        )
    }

    private suspend fun stagedIssues(sessionId: String, beforeOffset: Long? = null): List<ImportLineIssue> {
        val sql = if (beforeOffset == null) {
            "SELECT line_number,reason,bytes FROM chatdb_import_issues WHERE session_id=? ORDER BY line_number"
        } else {
            "SELECT line_number,reason,bytes FROM chatdb_import_issues WHERE session_id=? AND source_offset<? ORDER BY line_number"
        }
        val args = if (beforeOffset == null) arrayOf(sessionId) else arrayOf(sessionId, beforeOffset)
        return database.chatSql.query(sql, args).use { c ->
            buildList { while (c.moveToNext()) add(ImportLineIssue(c.getLong(0), c.getString(1), c.getLong(2))) }
        }
    }

    private suspend fun stagedUnknownTypeCounts(sessionId: String, beforeOffset: Long? = null): Map<String, Long> {
        if (beforeOffset == null) return database.chatSql.query(
            "SELECT row_type,row_count FROM chatdb_import_unknown_types WHERE session_id=? ORDER BY row_type", arrayOf(sessionId),
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) } }
        return database.chatSql.query(
            "SELECT r.row_type,COUNT(*) FROM chatdb_import_rows r WHERE r.session_id=? AND r.status='unknown' AND r.source_offset<? GROUP BY r.row_type ORDER BY r.row_type",
            arrayOf(sessionId, beforeOffset),
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) } }
    }

    private suspend fun stageMessage(
        sessionId: String,
        offset: Long,
        sourceSeq: Long,
        rawMessageId: String?,
        beforeMessageId: String?,
        isAssistant: Boolean,
        ordinaryUser: Boolean,
        projectionIndex: Long,
        sortSeq: Long,
    ) {
        database.chatSql.execSQL(
            "INSERT INTO chatdb_import_messages(session_id,source_offset,raw_message_id,before_message_id,is_assistant,ordinary_user,projection_index,sort_seq,sort_sub) VALUES(?,?,?,?,?,?,?,?,0)",
            arrayOf(sessionId, offset, rawMessageId, beforeMessageId, isAssistant, ordinaryUser, projectionIndex, sortSeq),
        )
        database.chatSql.execSQL(
            "INSERT INTO chatdb_import_message_sources(session_id,source_offset,source_seq) VALUES(?,?,?)",
            arrayOf(sessionId, offset, sourceSeq),
        )
    }

    private suspend fun stageCompaction(sessionId: String, offset: Long, logicalIndex: Long) {
        database.chatSql.execSQL("INSERT INTO chatdb_import_compactions(session_id,source_offset,logical_index) VALUES(?,?,?)", arrayOf(sessionId, offset, logicalIndex))
    }

    private suspend fun stageCrewSnapshot(sessionId: String, missionId: String, offset: Long, digest: String) {
        database.chatSql.execSQL(
            "INSERT INTO chatdb_import_crews(session_id,mission_id,source_offset,snapshot_digest) VALUES(?,?,?,?) ON CONFLICT(session_id,mission_id) DO UPDATE SET source_offset=excluded.source_offset,snapshot_digest=excluded.snapshot_digest",
            arrayOf(sessionId, missionId, offset, digest),
        )
    }

    private suspend fun stageHidden(sessionId: String, messageId: String, kind: String) {
        database.chatSql.execSQL(
            "INSERT INTO chatdb_import_hidden(session_id,message_id,hidden_kind) VALUES(?,?,?) ON CONFLICT(session_id,message_id) DO UPDATE SET hidden_kind=excluded.hidden_kind",
            arrayOf(sessionId, messageId, kind),
        )
    }

    private suspend fun stageTranslation(sessionId: String, messageId: String, language: String) {
        database.chatSql.execSQL("INSERT OR IGNORE INTO chatdb_import_translations(session_id,message_id,language) VALUES(?,?,?)", arrayOf(sessionId, messageId, language))
    }

    private suspend fun stageTranslationHidden(sessionId: String, messageId: String) {
        database.chatSql.execSQL("INSERT OR IGNORE INTO chatdb_import_translation_hidden(session_id,message_id) VALUES(?,?)", arrayOf(sessionId, messageId))
    }

    private suspend fun stageCount(table: String, sessionId: String): Long = database.chatSql.query(
        "SELECT COUNT(*) FROM $table WHERE session_id=?", arrayOf(sessionId),
    ).use { it.moveToFirst(); it.getLong(0) }

    private suspend fun readMessageMeta(sessionId: String, offset: Long): MessageMeta? = database.chatSql.query(
        "SELECT raw_message_id,before_message_id,is_assistant,ordinary_user,projection_index,sort_seq,sort_sub,message_index FROM chatdb_import_messages WHERE session_id=? AND source_offset=?",
        arrayOf(sessionId, offset),
    ).use { c ->
        if (!c.moveToFirst()) null else MessageMeta(
            offset, c.getStringOrNullM1b("raw_message_id"), c.getStringOrNullM1b("before_message_id"),
            c.getInt(2) != 0, c.getInt(3) != 0, c.getLong(4), c.getLong(5), c.getInt(6), c.getLong(7),
        )
    }

    private suspend fun readCompactionRowIndex(sessionId: String, offset: Long): Long? = database.chatSql.query(
        "SELECT logical_index FROM chatdb_import_compactions WHERE session_id=? AND source_offset=?", arrayOf(sessionId, offset),
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    private suspend fun readLatestCrewOffset(sessionId: String, missionId: String): Long? = database.chatSql.query(
        "SELECT source_offset FROM chatdb_import_crews WHERE session_id=? AND mission_id=?", arrayOf(sessionId, missionId),
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    private suspend fun calculateSteering(sessionId: String) {
        var afterOffset = -1L
        while (true) {
            val moving = database.chatSql.query(
                "SELECT source_offset,raw_message_id,before_message_id,is_assistant,projection_index,sort_seq,sort_sub FROM chatdb_import_messages WHERE session_id=? AND source_offset>? ORDER BY source_offset LIMIT 1",
                arrayOf(sessionId, afterOffset),
            ).use { c ->
                if (!c.moveToFirst()) null else MessageMeta(
                    c.getLong(0), c.getStringOrNullM1b("raw_message_id"), c.getStringOrNullM1b("before_message_id"),
                    c.getInt(3) != 0, false, c.getLong(4), c.getLong(5), c.getInt(6),
                )
            } ?: break
            afterOffset = moving.offset
            if (!moving.isAssistant || moving.beforeMessageId.isNullOrBlank()) continue
            val target = database.chatSql.query(
                "SELECT source_offset,projection_index,sort_seq,sort_sub FROM chatdb_import_messages WHERE session_id=? AND raw_message_id=? ORDER BY source_offset LIMIT 1",
                arrayOf(sessionId, moving.beforeMessageId),
            ).use { c ->
                if (!c.moveToFirst()) null else longArrayOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3))
            } ?: continue
            if (target[0] == moving.offset) continue
            val sourcePos = moving.projectionIndex
            val targetPos = target[1]
            val tupleSeq = target[2]
            val tupleSub = target[3].toInt()
            database.chatSql.execSQL(
                "UPDATE chatdb_import_messages SET sort_sub=sort_sub+1 WHERE session_id=? AND source_offset<>? AND sort_seq=? AND sort_sub>=?",
                arrayOf(sessionId, moving.offset, tupleSeq, tupleSub),
            )
            if (sourcePos < targetPos) {
                database.chatSql.execSQL(
                    "UPDATE chatdb_import_messages SET projection_index=projection_index-1 WHERE session_id=? AND source_offset<>? AND projection_index>? AND projection_index<=?",
                    arrayOf(sessionId, moving.offset, sourcePos, targetPos),
                )
                database.chatSql.execSQL(
                    "UPDATE chatdb_import_compactions SET logical_index=logical_index-1 WHERE session_id=? AND logical_index>? AND logical_index<=?",
                    arrayOf(sessionId, sourcePos, targetPos),
                )
                database.chatSql.execSQL(
                    "UPDATE chatdb_import_messages SET projection_index=?,sort_seq=?,sort_sub=? WHERE session_id=? AND source_offset=?",
                    arrayOf(targetPos - 1L, tupleSeq, tupleSub, sessionId, moving.offset),
                )
            } else {
                database.chatSql.execSQL(
                    "UPDATE chatdb_import_messages SET projection_index=projection_index+1 WHERE session_id=? AND source_offset<>? AND projection_index>=? AND projection_index<?",
                    arrayOf(sessionId, moving.offset, targetPos, sourcePos),
                )
                database.chatSql.execSQL(
                    "UPDATE chatdb_import_compactions SET logical_index=logical_index+1 WHERE session_id=? AND logical_index>=? AND logical_index<?",
                    arrayOf(sessionId, targetPos, sourcePos),
                )
                database.chatSql.execSQL(
                    "UPDATE chatdb_import_messages SET projection_index=?,sort_seq=?,sort_sub=? WHERE session_id=? AND source_offset=?",
                    arrayOf(targetPos, tupleSeq, tupleSub, sessionId, moving.offset),
                )
            }
        }
    }

    private suspend fun assignMessageIndexes(sessionId: String) {
        var previousIndex = Long.MIN_VALUE
        var previousOffset = Long.MIN_VALUE
        var messageIndex = 0L
        while (true) {
            val next = database.chatSql.query(
                "SELECT source_offset,projection_index FROM chatdb_import_messages WHERE session_id=? AND (projection_index>? OR (projection_index=? AND source_offset>?)) ORDER BY projection_index,source_offset LIMIT 1",
                arrayOf(sessionId, previousIndex, previousIndex, previousOffset),
            ).use { c -> if (c.moveToFirst()) longArrayOf(c.getLong(0), c.getLong(1)) else null } ?: break
            database.chatSql.execSQL(
                "UPDATE chatdb_import_messages SET message_index=? WHERE session_id=? AND source_offset=?",
                arrayOf(messageIndex++, sessionId, next[0]),
            )
            previousOffset = next[0]
            previousIndex = next[1]
        }
    }

    private suspend fun clearPreflightStage(sessionId: String) = database.withChatWriteTransaction {
        clearPreflightStageRows(sessionId)
    }

    private suspend fun preflight(sessionId: String, ledger: File): Preflight {
        database.withChatWriteTransaction {
            createPreflightTables()
            clearPreflightStageRows(sessionId)
        }
        val counts = linkedMapOf<String, Long>()
        val validations = mutableListOf<ImportValidation>()
        var lines = 0L
        var known = 0L
        var unknown = 0L
        var seq = 1L
        // Mirrors the old readConversationRows list used by compactionId: malformed rows and
        // result-only journal rows are omitted; session_title and input-marker JSONL rows count once.
        var logicalIndex = 0L
        var firstTitle: String? = null
        var firstTimestampMillis: Long? = null
        var strictFailure: String? = null
        NonInterruptibleLedgerFile(ledger).use { reader ->
            while (true) {
                var stagedInBatch = 0
                database.withChatWriteTransaction {
                    while (stagedInBatch < options.batchSize) {
                        val line = reader.readLine() ?: break
                        stagedInBatch++
                        lines++
                        if (!line.terminated) {
                            stageRow(sessionId, line.startOffset, "", "malformed")
                            stageIssue(sessionId, line.startOffset, line.lineNumber, "truncated_final_line", line.bytesIncludingTerminator)
                            if (isStrictLedger(sessionId) || likelyStrictRow(line.bytes)) strictFailure = "truncated strict ledger row at line ${line.lineNumber}"
                            continue
                        }
                        val row = try { parseLedgerObject(StrictLedgerJson.decodeUtf8(line.bytes)) }
                        catch (failure: Throwable) {
                            val reason = when (failure) {
                                is DuplicateLedgerJsonKey -> "duplicate_key:${failure.key}"
                                is java.nio.charset.CharacterCodingException -> "invalid_utf8"
                                else -> "invalid_json:${failure.javaClass.simpleName}"
                            }
                            stageRow(sessionId, line.startOffset, "", "malformed")
                            stageIssue(sessionId, line.startOffset, line.lineNumber, reason, line.bytesIncludingTerminator)
                            if ((isStrictLedger(sessionId) || likelyStrictRow(line.bytes)) && strictFailure == null)
                                strictFailure = "invalid strict journal ledger row at line ${line.lineNumber}:$reason"
                            continue
                        }
                        val type = ledgerType(row)
                        if (type !in KNOWN_TYPES) {
                            stageRow(sessionId, line.startOffset, type, "unknown")
                            unknown++
                            val unknownType = type.ifBlank { "(untyped)" }
                            stageUnknownType(sessionId, unknownType)
                            if (!isResultOnly(row)) logicalIndex++
                            continue
                        }
                        stageRow(sessionId, line.startOffset, type, if (type == "session_title") "title" else "known")
                        counts[type] = (counts[type] ?: 0L) + 1L
                        known++
                        if (type != "session_title") seq++
                        val timestamp = timestampSeconds(row, type)
                        if (timestamp.isFinite()) {
                            val millis = (timestamp * 1000.0).toLong()
                            val currentFirst = firstTimestampMillis
                            if (currentFirst == null || millis < currentFirst) firstTimestampMillis = millis
                        }
                        if (type == "session_title" && firstTitle == null) row.optString("content", "").trim().takeIf(String::isNotEmpty)?.let { firstTitle = it }
                        when (type) {
                            "assistant_regenerated", "assistant_deleted" -> row.stringOrNull("messageId")?.let { stageHidden(sessionId, it, type) }
                            "assistant_translation" -> {
                                val id = row.stringOrNull("messageId")
                                val language = row.stringOrNull("language")
                                if (!id.isNullOrBlank() && !language.isNullOrBlank()) stageTranslation(sessionId, id, language)
                            }
                            "assistant_translation_hidden" -> row.stringOrNull("messageId")?.let { stageTranslationHidden(sessionId, it) }
                        }
                        if (type == "crew_snapshot") {
                            val missionId = row.stringOrNull("missionId")
                            if (!missionId.isNullOrBlank()) stageCrewSnapshot(sessionId, missionId, line.startOffset, crewSnapshotDigest(row))
                        }
                        if (type == "compaction") stageCompaction(sessionId, line.startOffset, logicalIndex)
                        if (type in MESSAGE_TYPES) {
                            val rawId = row.stringOrNull("messageId")?.takeIf(String::isNotBlank)
                            stageMessage(
                                sessionId,
                                offset = line.startOffset,
                                sourceSeq = seq - 1L,
                                rawMessageId = rawId,
                                beforeMessageId = row.stringOrNull("beforeMessageId"),
                                isAssistant = type != "user",
                                ordinaryUser = type == "user" && row.optString("interruptRequestId").isBlank(),
                                projectionIndex = logicalIndex,
                                sortSeq = seq - 1L,
                            )
                        }
                        if (!isResultOnly(row)) logicalIndex++
                    }
                }
                if (stagedInBatch == 0) break
            }
        }
        return database.withChatWriteTransaction {
        calculateSteering(sessionId)
        assignMessageIndexes(sessionId)
        val messageCount = stageCount("chatdb_import_messages", sessionId)
        val issueCount = scalarLong("SELECT COUNT(*) FROM chatdb_import_issues WHERE session_id=?", arrayOf(sessionId))
        validations += ImportValidation("preflight_lines", true, "${lines} physical lines; $known known rows; $unknown unknown rows; $issueCount malformed/truncated")
        validations += ImportValidation("documented_row_catalog", true, "${KNOWN_TYPES.size} row forms are enumerated by ledger-inventory.md §A (the document heading says 46; the current table contains 43)")
        Preflight(lines, known, unknown, counts, validations,
            strictFailure, firstTitle, firstTimestampMillis, messageCount, issueCount)
        }
    }

    private suspend fun validate(
        sessionId: String,
        ledger: File,
        preflight: Preflight,
        skippedByType: Map<String, Long>,
        sourceBytes: Long,
        sourceSha256: String,
    ): List<ImportValidation> {
        val results = mutableListOf<ImportValidation>()
        val currentSha = sha256(ledger)
        val sourceMatches = sourceBytes == ledger.length() && sourceSha256 == currentSha
        results += ImportValidation("source_ledger_unchanged", sourceMatches,
            "validatedBytes=$sourceBytes, currentBytes=${ledger.length()}, sha256Matches=${sourceSha256 == currentSha}")
        val itemCounts = database.chatSql.query(
            "SELECT item_kind,COUNT(*) FROM conversation_items WHERE conversation_id=? GROUP BY item_kind", arrayOf(sessionId),
        ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1)) } }
        val ledgerItemCounts = KNOWN_TYPES.filter { it != "session_title" }
            .associateWith { type -> (preflight.typeCounts[type] ?: 0L) - (skippedByType[type] ?: 0L) }
        val databaseCounts = ledgerItemCounts.keys.associateWith { type -> databaseCountForType(sessionId, type, itemCounts) }
        val countMatches = databaseCounts == ledgerItemCounts
        results += ImportValidation("row_counts_by_type", countMatches,
            "ledger=${ledgerItemCounts.toSortedMap()}; database=${databaseCounts.toSortedMap()}")
        val semanticSkipped = skippedByType.values.sum()
        val importedKnown = preflight.knownLines - semanticSkipped
        val skippedTotal = preflight.issueCount + semanticSkipped
        results += ImportValidation("line_accounting", preflight.lines == importedKnown + skippedTotal + preflight.unknown,
            "lines=${preflight.lines}, imported=$importedKnown, skipped=$skippedTotal, unknown=${preflight.unknown}")

        val sequence = database.chatSql.query(
            "SELECT c.next_seq,COUNT(i.seq),COALESCE(MIN(i.seq),0),COALESCE(MAX(i.seq),0) FROM conversations c LEFT JOIN conversation_items i ON i.conversation_id=c.id WHERE c.id=? GROUP BY c.id",
            arrayOf(sessionId),
        ).use { c -> if (c.moveToFirst()) longArrayOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3)) else null }
        val expectedSpineRows = preflight.knownLines - (preflight.typeCounts["session_title"] ?: 0L)
        val sequenceOk = sequence != null && sequence[0] == sequence[1] + 1L &&
            sequence[1] == expectedSpineRows &&
            (sequence[1] == 0L || sequence[2] == 1L && sequence[3] == sequence[1])
        results += ImportValidation("conversation_spine_contiguous", sequenceOk,
            sequence?.let { "next_seq=${it[0]}, rows=${it[1]}, expected=$expectedSpineRows, min=${it[2]}, max=${it[3]}" } ?: "conversation missing")

        val foreignKeys = database.chatSql.query("PRAGMA foreign_key_check").use { it.countRows() }
        results += ImportValidation("foreign_key_check", foreignKeys == 0, "violations=$foreignKeys")
        val integrity = database.chatSql.query("PRAGMA integrity_check").use { c -> if (c.moveToFirst()) c.getString(0) else "empty" }
        results += ImportValidation("integrity_check", integrity.equals("ok", ignoreCase = true), integrity)

        val messageCount = database.chatSql.query("SELECT COUNT(*) FROM messages WHERE conversation_id=?", arrayOf(sessionId))
            .use { it.moveToFirst(); it.getLong(0) }
        val expectedMessages = stageImportedMessageCount(sessionId)
        val indexRange = database.chatSql.query(
            "SELECT COALESCE(MIN(message_index),0),COALESCE(MAX(message_index),-1),COUNT(DISTINCT message_index) FROM messages WHERE conversation_id=?",
            arrayOf(sessionId),
        ).use { it.moveToFirst(); longArrayOf(it.getLong(0), it.getLong(1), it.getLong(2)) }
        val messageIndexDiff = scalarLong(
            "SELECT (SELECT COUNT(*) FROM messages m WHERE m.conversation_id=? AND NOT EXISTS(" +
                "SELECT 1 FROM chatdb_import_messages s WHERE s.session_id=? AND s.imported=1 AND s.message_index=m.message_index)) + " +
                "(SELECT COUNT(*) FROM chatdb_import_messages s WHERE s.session_id=? AND s.imported=1 AND NOT EXISTS(" +
                "SELECT 1 FROM messages m WHERE m.conversation_id=? AND m.message_index=s.message_index))",
            arrayOf(sessionId, sessionId, sessionId, sessionId),
        )
        val indexOk = messageCount == expectedMessages && messageIndexDiff == 0L &&
            (messageCount == 0L || indexRange[2] == messageCount)
        results += ImportValidation("message_order_and_indices", indexOk,
            "expected=$expectedMessages actual=$messageCount index=${indexRange.contentToString()}, sourceIndexDifferences=$messageIndexDiff")

        val sourceDigest = digestSourceMessages(ledger, sessionId, expectedMessages)
        val databaseDigest = digestDatabaseMessages(sessionId, messageCount)
        results += ImportValidation("message_text_sha256", sourceDigest == databaseDigest,
            "ledger=$sourceDigest database=$databaseDigest")

        val markerCheck = database.chatSql.query(
            "SELECT COUNT(*) FROM messages m WHERE m.conversation_id=? AND " +
                "((m.hidden_seq IS NOT NULL AND NOT EXISTS(SELECT 1 FROM conversation_items i WHERE i.conversation_id=m.conversation_id AND i.seq=m.hidden_seq AND i.item_kind=m.hidden_kind)) OR " +
                "(m.translation_hidden_seq IS NOT NULL AND NOT EXISTS(SELECT 1 FROM conversation_items i WHERE i.conversation_id=m.conversation_id AND i.seq=m.translation_hidden_seq AND i.item_kind='assistant_translation_hidden'))) ",
            arrayOf(sessionId),
        ).use { it.moveToFirst(); it.getLong(0) }
        results += ImportValidation("hidden_and_translation_markers", markerCheck == 0L, "invalid message markers=$markerCheck")

        val hiddenSetDiff = scalarLong(
            "SELECT (SELECT COUNT(*) FROM messages m WHERE m.conversation_id=? AND m.hidden_kind IS NOT NULL AND NOT EXISTS(" +
                "SELECT 1 FROM chatdb_import_hidden h JOIN chatdb_import_messages s ON s.session_id=h.session_id AND s.raw_message_id=h.message_id AND s.imported=1 " +
                "WHERE h.session_id=? AND h.message_id=m.message_id AND h.hidden_kind=m.hidden_kind)) + " +
                "(SELECT COUNT(*) FROM chatdb_import_hidden h WHERE h.session_id=? AND EXISTS(" +
                "SELECT 1 FROM chatdb_import_messages s WHERE s.session_id=h.session_id AND s.raw_message_id=h.message_id AND s.imported=1) AND NOT EXISTS(" +
                "SELECT 1 FROM messages m WHERE m.conversation_id=? AND m.message_id=h.message_id AND m.hidden_kind=h.hidden_kind))",
            arrayOf(sessionId, sessionId, sessionId, sessionId),
        )
        val hiddenExpectedCount = scalarLong(
            "SELECT COUNT(*) FROM chatdb_import_hidden h WHERE h.session_id=? AND EXISTS(SELECT 1 FROM chatdb_import_messages s WHERE s.session_id=h.session_id AND s.raw_message_id=h.message_id AND s.imported=1)",
            arrayOf(sessionId),
        )
        val hiddenDatabaseCount = scalarLong("SELECT COUNT(*) FROM messages WHERE conversation_id=? AND hidden_kind IS NOT NULL", arrayOf(sessionId))
        results += ImportValidation("hidden_message_set", hiddenSetDiff == 0L,
            "ledger=$hiddenExpectedCount, database=$hiddenDatabaseCount, differences=$hiddenSetDiff")

        val translationSetDiff = scalarLong(
            "SELECT (SELECT COUNT(*) FROM message_translations d WHERE d.conversation_id=? AND NOT EXISTS(" +
                "SELECT 1 FROM chatdb_import_translations t JOIN chatdb_import_messages s ON s.session_id=t.session_id AND s.raw_message_id=t.message_id AND s.imported=1 " +
                "WHERE t.session_id=? AND t.message_id=d.message_id AND t.language=d.language)) + " +
                "(SELECT COUNT(*) FROM chatdb_import_translations t WHERE t.session_id=? AND EXISTS(" +
                "SELECT 1 FROM chatdb_import_messages s WHERE s.session_id=t.session_id AND s.raw_message_id=t.message_id AND s.imported=1) AND NOT EXISTS(" +
                "SELECT 1 FROM message_translations d WHERE d.conversation_id=? AND d.message_id=t.message_id AND d.language=t.language))",
            arrayOf(sessionId, sessionId, sessionId, sessionId),
        )
        val translationExpectedCount = scalarLong(
            "SELECT COUNT(*) FROM chatdb_import_translations t WHERE t.session_id=? AND EXISTS(SELECT 1 FROM chatdb_import_messages s WHERE s.session_id=t.session_id AND s.raw_message_id=t.message_id AND s.imported=1)",
            arrayOf(sessionId),
        )
        val translationDatabaseCount = scalarLong("SELECT COUNT(*) FROM message_translations WHERE conversation_id=?", arrayOf(sessionId))
        results += ImportValidation("translation_set", translationSetDiff == 0L,
            "ledger=$translationExpectedCount, database=$translationDatabaseCount, differences=$translationSetDiff")

        val translationHiddenDiff = scalarLong(
            "SELECT (SELECT COUNT(*) FROM messages m WHERE m.conversation_id=? AND m.translation_hidden_seq IS NOT NULL AND NOT EXISTS(" +
                "SELECT 1 FROM chatdb_import_translation_hidden h JOIN chatdb_import_messages s ON s.session_id=h.session_id AND s.raw_message_id=h.message_id AND s.imported=1 " +
                "WHERE h.session_id=? AND h.message_id=m.message_id)) + " +
                "(SELECT COUNT(*) FROM chatdb_import_translation_hidden h WHERE h.session_id=? AND EXISTS(" +
                "SELECT 1 FROM chatdb_import_messages s WHERE s.session_id=h.session_id AND s.raw_message_id=h.message_id AND s.imported=1) AND NOT EXISTS(" +
                "SELECT 1 FROM messages m WHERE m.conversation_id=? AND m.message_id=h.message_id AND m.translation_hidden_seq IS NOT NULL))",
            arrayOf(sessionId, sessionId, sessionId, sessionId),
        )
        results += ImportValidation("translation_hidden_set", translationHiddenDiff == 0L,
            "differences=$translationHiddenDiff")

        val crewDiff = scalarLong(
            "SELECT (SELECT COUNT(*) FROM crew_missions d WHERE d.conversation_id=? AND NOT EXISTS(" +
                "SELECT 1 FROM chatdb_import_crews s WHERE s.session_id=? AND s.mission_id=d.mission_id AND s.snapshot_digest=d.snapshot_digest)) + " +
                "(SELECT COUNT(*) FROM chatdb_import_crews s WHERE s.session_id=? AND NOT EXISTS(" +
                "SELECT 1 FROM crew_missions d WHERE d.conversation_id=? AND d.mission_id=s.mission_id AND d.snapshot_digest=s.snapshot_digest))",
            arrayOf(sessionId, sessionId, sessionId, sessionId),
        )
        val stagedCrewCount = scalarLong("SELECT COUNT(*) FROM chatdb_import_crews WHERE session_id=?", arrayOf(sessionId))
        val badCrewPositions = database.chatSql.query(
            "SELECT COUNT(*) FROM crew_missions m WHERE m.conversation_id=? AND (m.first_snapshot_seq<>(SELECT MIN(i.seq) FROM conversation_items i WHERE i.conversation_id=m.conversation_id AND i.item_kind='crew_snapshot' AND i.item_key=m.mission_id) OR " +
                "m.last_snapshot_seq<>(SELECT MAX(i.seq) FROM conversation_items i WHERE i.conversation_id=m.conversation_id AND i.item_kind='crew_snapshot' AND i.item_key=m.mission_id))",
            arrayOf(sessionId),
        ).use { it.moveToFirst(); it.getLong(0) }
        results += ImportValidation("crew_latest_snapshot_parity", crewDiff == 0L && badCrewPositions == 0L,
            "ledgerMissions=$stagedCrewCount, databaseDifferences=$crewDiff, badPositions=$badCrewPositions")

        val movedMessageErrors = database.chatSql.query(
            "SELECT COUNT(*) FROM messages m JOIN conversation_items moving ON moving.conversation_id=m.conversation_id AND moving.seq=m.seq " +
                "JOIN messages target ON target.conversation_id=m.conversation_id AND target.message_id=m.before_message_id " +
                "JOIN conversation_items target_item ON target_item.conversation_id=target.conversation_id AND target_item.seq=target.seq " +
                "WHERE m.conversation_id=? AND m.role='assistant' AND (moving.sort_seq>target_item.sort_seq OR " +
                "(moving.sort_seq=target_item.sort_seq AND (moving.sort_sub>target_item.sort_sub OR " +
                "(moving.sort_sub=target_item.sort_sub AND moving.seq>=target.seq))))",
            arrayOf(sessionId),
        ).use { it.moveToFirst(); it.getLong(0) }
        var indexesFollowSortErrors = 0L
        var previousSortMessageIndex = Long.MIN_VALUE
        database.chatSql.query(
            "SELECT m.message_index FROM messages m JOIN conversation_items i ON i.conversation_id=m.conversation_id AND i.seq=m.seq " +
                "WHERE m.conversation_id=? ORDER BY i.sort_seq,i.sort_sub,i.seq",
            arrayOf(sessionId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val current = cursor.getLong(0)
                if (current <= previousSortMessageIndex) indexesFollowSortErrors++
                previousSortMessageIndex = current
            }
        }
        val indexesFollowSort = indexesFollowSortErrors == 0L
        results += ImportValidation("message_sort_and_index_coherence", movedMessageErrors == 0L && indexesFollowSort,
            "steeringOrderErrors=$movedMessageErrors, messageIndicesFollowSort=$indexesFollowSort")

        val batchCounts = database.chatSql.query(
            "SELECT COUNT(*),SUM(CASE WHEN NOT EXISTS(" +
                "SELECT 1 FROM tool_calls c WHERE c.conversation_id=b.conversation_id AND c.batch_id=b.batch_id AND NOT EXISTS(" +
                "SELECT 1 FROM tool_results r WHERE r.conversation_id=c.conversation_id AND r.batch_id=c.batch_id AND r.call_id=c.call_id)) " +
                "THEN 1 ELSE 0 END) FROM tool_batches b WHERE b.conversation_id=?",
            arrayOf(sessionId),
        ).use { cursor ->
            cursor.moveToFirst()
            val total = cursor.getLong(0)
            val complete = if (cursor.isNull(1)) 0L else cursor.getLong(1)
            total to complete
        }
        val invalidToolState = database.chatSql.query(
            "SELECT COUNT(*) FROM tool_calls c WHERE c.conversation_id=? AND (c.lifecycle NOT IN ('INTENT','STARTED','NEVER_LAUNCHED') OR " +
                "EXISTS(SELECT 1 FROM tool_results r WHERE r.conversation_id=c.conversation_id AND r.batch_id=c.batch_id AND r.call_id=c.call_id " +
                "AND (r.tool_name<>c.name OR r.lifecycle NOT IN ('RESULT','INTERRUPTED_UNCERTAIN','NEVER_LAUNCHED'))))",
            arrayOf(sessionId),
        ).use { it.moveToFirst(); it.getLong(0) }
        val orphanToolResults = database.chatSql.query(
            "SELECT COUNT(*) FROM tool_results r WHERE r.conversation_id=? AND NOT EXISTS(" +
                "SELECT 1 FROM tool_calls c WHERE c.conversation_id=r.conversation_id AND c.batch_id=r.batch_id AND c.call_id=r.call_id)",
            arrayOf(sessionId),
        ).use { it.moveToFirst(); it.getLong(0) }
        results += ImportValidation(
            "tool_batches_complete_or_incomplete",
            invalidToolState == 0L && orphanToolResults == 0L && batchCounts.second <= batchCounts.first,
            "batches=${batchCounts.first}, complete=${batchCounts.second}, incomplete=${batchCounts.first - batchCounts.second}, " +
                "invalidCallOrResultStates=$invalidToolState, orphanResults=$orphanToolResults",
        )

        val dbBytes = databaseBytes()
        results += ImportValidation("database_size_measured", true, "database pages=$dbBytes bytes; delta is reported on ImportReport")
        return results
    }

    private suspend fun databaseRowsByType(sessionId: String): Map<String, Long> {
        val itemCounts = database.chatSql.query(
            "SELECT item_kind,COUNT(*) FROM conversation_items WHERE conversation_id=? GROUP BY item_kind", arrayOf(sessionId),
        ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1)) } }
        return KNOWN_TYPES.associateWith { type -> databaseCountForType(sessionId, type, itemCounts) }
    }

    private suspend fun scalarLong(sql: String, args: Array<out Any?>): Long = database.chatSql.query(sql, args).use {
        check(it.moveToFirst()) { "scalar query returned no row" }
        it.getLong(0)
    }

    private suspend fun stageImportedMessageCount(sessionId: String): Long = scalarLong(
        "SELECT COUNT(*) FROM chatdb_import_messages WHERE session_id=? AND imported=1", arrayOf(sessionId),
    )

    private suspend fun databaseSkippedRows(sessionId: String): StoredSkips {
        val counts = database.chatSql.query(
            "SELECT substr(item_key,1,instr(item_key,':')-1),COUNT(*) FROM conversation_items " +
                "WHERE conversation_id=? AND item_kind='import_skipped_row' GROUP BY 1",
            arrayOf(sessionId),
        ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1)) } }
        return StoredSkips(counts)
    }

    private suspend fun databaseSkippedIssues(sessionId: String): List<ImportLineIssue> {
        val issues = mutableListOf<ImportLineIssue>()
        var afterSeq = 0L
        while (true) {
            val next = database.chatSql.query(
                "SELECT seq,item_key FROM conversation_items WHERE conversation_id=? AND item_kind='import_skipped_row' AND seq>? ORDER BY seq LIMIT 1",
                arrayOf(sessionId, afterSeq),
            ).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null } ?: break
            afterSeq = next.first
            val parts = next.second.split(':', limit = 5)
            if (parts.size < 4) continue
            val lineNumber = parts[1].toLongOrNull() ?: continue
            if (parts[2].toLongOrNull() == null) continue
            val byteCount = parts[3].toLongOrNull() ?: continue
            issues += ImportLineIssue(lineNumber, parts.getOrNull(4)?.takeIf(String::isNotBlank) ?: "invalid_row", byteCount)
        }
        return issues
    }

    private suspend fun databaseCountForType(sessionId: String, type: String, itemCounts: Map<String, Long>): Long {
        val normalizedRows = when (type) {
            "session_title" -> 0L
            "user" -> database.chatSql.query("SELECT COUNT(*) FROM messages WHERE conversation_id=? AND role='user'", arrayOf(sessionId)).use { it.moveToFirst(); it.getLong(0) } + (itemCounts[type] ?: 0L)
            "assistant" -> database.chatSql.query("SELECT COUNT(*) FROM messages WHERE conversation_id=? AND role='assistant' AND proactive_decision_id IS NULL", arrayOf(sessionId)).use { it.moveToFirst(); it.getLong(0) } + (itemCounts[type] ?: 0L)
            "proactive_message" -> database.chatSql.query("SELECT COUNT(*) FROM messages WHERE conversation_id=? AND role='assistant' AND proactive_decision_id IS NOT NULL", arrayOf(sessionId)).use { it.moveToFirst(); it.getLong(0) } + (itemCounts[type] ?: 0L)
            "main_chat_input_incorporated" -> (itemCounts[ConversationRepository.INPUT_INCORPORATED] ?: 0L) + (itemCounts[type] ?: 0L)
            "main_chat_input_resolved" -> (itemCounts[ConversationRepository.INPUT_RESOLVED] ?: 0L) + (itemCounts[type] ?: 0L)
            else -> itemCounts[type] ?: 0L
        }
        return normalizedRows
    }

    private suspend fun digestDatabaseMessages(sessionId: String, count: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var previousIndex = Long.MIN_VALUE
        var read = 0L
        while (read < count) {
            val index = database.chatSql.query(
                "SELECT message_index FROM messages WHERE conversation_id=? AND message_index>? ORDER BY message_index LIMIT 1",
                arrayOf(sessionId, previousIndex),
            ).use { c -> check(c.moveToFirst()) { "missing message index after $previousIndex" }; c.getLong(0) }
            val text = database.chatSql.query(
                "SELECT COALESCE((SELECT group_concat(payload,'') FROM (SELECT p.payload FROM message_parts p WHERE p.conversation_id=m.conversation_id AND p.message_id=m.message_id AND p.kind='text' ORDER BY p.ordinal)), '') " +
                    "FROM messages m WHERE m.conversation_id=? AND m.message_index=?", arrayOf(sessionId, index),
            ).use { c -> check(c.moveToFirst()) { "missing message_index=$index" }; c.getString(0) }
            updateLengthPrefixed(digest, text)
            previousIndex = index
            read++
        }
        return digest.digest().toHex()
    }

    private suspend fun digestSourceMessages(ledger: File, sessionId: String, messageCount: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        val lineBuffer = ByteArray(64 * 1024)
        val lineBytes = ByteArrayOutputStream()
        database.chatSql.query(
            "SELECT source_offset,message_index FROM chatdb_import_messages WHERE session_id=? AND imported=1 ORDER BY message_index",
            arrayOf(sessionId),
        ).use { cursor ->
            RandomAccessFile(ledger, "r").use { input ->
                while (cursor.moveToNext()) {
                    check(read < messageCount) { "more imported messages than expected ($messageCount)" }
                    val offset = cursor.getLong(0)
                    val line = readSourceLine(input, offset, lineBuffer, lineBytes)
                    val row = parseLedgerObject(StrictLedgerJson.decodeUtf8(line))
                    updateLengthPrefixed(digest, row.optString("content", ""))
                    read++
                }
            }
        }
        check(read == messageCount) { "read $read source messages; expected $messageCount" }
        return digest.digest().toHex()
    }

    private fun readSourceLine(
        input: RandomAccessFile,
        offset: Long,
        buffer: ByteArray,
        output: ByteArrayOutputStream,
    ): ByteArray {
        input.seek(offset)
        output.reset()
        while (true) {
            val count = input.read(buffer)
            check(count >= 0) { "message source row disappeared at byte $offset" }
            var newline = -1
            for (index in 0 until count) {
                if (buffer[index] == '\n'.code.toByte()) {
                    newline = index
                    break
                }
            }
            if (newline >= 0) {
                output.write(buffer, 0, newline)
                break
            }
            output.write(buffer, 0, count)
        }
        val raw = output.toByteArray()
        return if (raw.lastOrNull() == '\r'.code.toByte()) raw.copyOf(raw.size - 1) else raw
    }

    private fun readMetadata(file: File): ImportMetadata {
        if (!file.isFile) return ImportMetadata()
        val value = parseLedgerObject(StrictLedgerJson.decodeUtf8(file.readBytes()))
        return ImportMetadata(
            deleted = value.optBoolean("deleted", false),
            title = value.stringOrNull("title")?.trim()?.takeIf(String::isNotEmpty),
            pinned = value.optBoolean("pinned", false),
            archived = value.optBoolean("archived", false),
            privateCode = value.optBoolean("privateCode", false),
        )
    }

    private suspend fun reportFailure(
        sessionId: String,
        started: Long,
        bytes: Long,
        databaseBefore: Long,
        lines: Long,
        imported: Long,
        unknown: Long,
        skipped: Long,
        rows: Map<String, Long>,
        issues: List<ImportLineIssue>,
        validations: List<ImportValidation>,
        reason: String,
        conversationPayloadBytesBefore: Long = 0L,
    ): ImportReport {
        return ImportReport(sessionId, "failed", started, System.currentTimeMillis() - started, bytes,
            databaseBefore, databaseBytes(), lines, imported, skipped, unknown, rows, issues, validations, reason,
            conversationPayloadBytesBefore = conversationPayloadBytesBefore,
            conversationPayloadBytesAfter = conversationPayloadBytes(sessionId))
    }

    private suspend fun nextSequence(sessionId: String): Long = database.chatSql.query(
        "SELECT next_seq FROM conversations WHERE id=?", arrayOf(sessionId),
    ).use { check(it.moveToFirst()) { "conversation missing: $sessionId" }; it.getLong(0) }

    private suspend fun lastOrdinaryUserSeq(sessionId: String): Long? = database.chatSql.query(
        "SELECT seq FROM messages WHERE conversation_id=? AND role='user' AND interrupt_request_id IS NULL ORDER BY seq DESC LIMIT 1", arrayOf(sessionId),
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    private suspend fun firstCrewSnapshotSeq(sessionId: String, missionId: String): Long? = database.chatSql.query(
        "SELECT MIN(seq) FROM conversation_items WHERE conversation_id=? AND item_kind='crew_snapshot' AND item_key=?", arrayOf(sessionId, missionId),
    ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    private suspend fun databaseBytes(): Long = database.chatSql.query("PRAGMA page_count").use { pages ->
        pages.moveToFirst()
        val count = pages.getLong(0)
        val pageSize = database.chatSql.query("PRAGMA page_size").use { size -> size.moveToFirst(); size.getLong(0) }
        count * pageSize
    }

    /** Measures this conversation's normalized TEXT/BLOB payload bytes, excluding shared page/index overhead. */
    private suspend fun conversationPayloadBytes(sessionId: String): Long {
        var total = database.chatSql.query(
            "SELECT COALESCE(length(CAST(id AS BLOB)),0)+COALESCE(length(CAST(title AS BLOB)),0)+" +
                "COALESCE(length(CAST(generated_title AS BLOB)),0)+COALESCE(length(CAST(legacy_ledger_sha256 AS BLOB)),0) " +
                "FROM conversations WHERE id=?",
            arrayOf(sessionId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        val tables = database.chatSql.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        for (table in tables) {
            val quotedTable = quoteIdentifier(table)
            val columns = database.chatSql.query("PRAGMA table_info($quotedTable)").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                        val declaredType = cursor.getString(cursor.getColumnIndexOrThrow("type")).uppercase(Locale.ROOT)
                        add(name to declaredType)
                    }
                }
            }
            if (columns.none { it.first == "conversation_id" }) continue
            val payloadColumns = columns.filter { (_, type) -> type.contains("TEXT") || type.contains("BLOB") }
                .map { (name, _) -> quoteIdentifier(name) }
            if (payloadColumns.isEmpty()) continue
            val rowBytes = payloadColumns.joinToString("+") { "COALESCE(length(CAST($it AS BLOB)),0)" }
            total += database.chatSql.query(
                "SELECT COALESCE(SUM($rowBytes),0) FROM $quotedTable WHERE conversation_id=?",
                arrayOf(sessionId),
            ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
        }
        return total
    }

    private fun quoteIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun conversationKind(sessionId: String): String = when {
        sessionId == WorkspaceIdentity.CENTRAL_SESSION -> "central"
        WorkspaceIdentity.isExecutionSession(sessionId) -> "workspace_exec"
        sessionId == "jarvys-proactive" -> "proactive"
        sessionId == "jarvys-tasks" -> "tasks"
        else -> "chat"
    }

    private fun isStrictLedger(sessionId: String): Boolean = sessionId == WorkspaceIdentity.CENTRAL_SESSION || WorkspaceIdentity.isExecutionSession(sessionId)

    private fun parseAttachments(array: JSONArray?): List<MessageAttachmentInput> = buildList {
        if (array == null) return@buildList
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: error("attachment[$i] is not an object")
            val attachment = ChatAttachment.fromJson(item) ?: error("attachment[$i] is invalid")
            add(MessageAttachmentInput(attachment.id, attachment.name, attachment.mimeType, attachment.sizeBytes,
                attachment.kind.name, attachment.relativePath))
        }
    }

    private fun parseReplies(array: JSONArray?): List<SuggestedReplyInput> = buildList {
        if (array == null) return@buildList
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: error("suggestedReplies[$i] is not an object")
            add(SuggestedReplyInput(item.getString("label"), item.getString("text")))
        }
    }

    private fun parseDecisionOptions(array: JSONArray?): List<DecisionOptionInput> = buildList {
        if (array == null) return@buildList
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: error("decision option[$i] is not an object")
            add(DecisionOptionInput(item.getString("id"), item.getString("label"), item.stringOrNull("description"), item.stringOrNull("role")))
        }
    }

    private fun parseModelCalls(array: JSONArray?): List<ModelCallInput> = buildList {
        require(array != null) { "model_tool_calls has no calls array" }
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: error("tool call[$i] is not an object")
            val arguments = item.opt("arguments") ?: error("tool call[$i] has no arguments")
            add(ModelCallInput(item.getString("id"), item.getString("name"), arguments.toJsonValue()))
        }
    }

    private fun parseStringArray(array: JSONArray?): List<String> = buildList {
        if (array == null) return@buildList
        for (i in 0 until array.length()) add(array.getString(i))
    }

    private fun reflectionInput(sessionId: String, row: JSONObject, time: Double, kind: String) = ReflectionInput(
        conversationId = sessionId,
        kind = kind,
        reflectionId = row.stringOrNull("reflectionId"),
        throughMessageId = row.stringOrNull("throughMessageId"),
        completedAssistantSteps = row.longOrNull("completedAssistantSteps"),
        result = row.stringOrNull("result"),
        summary = row.stringOrNull("summary"),
        status = row.stringOrNull("status"),
        trigger = row.stringOrNull("trigger"),
        revisionIds = row.optJSONArray("revisionIds")?.let { values -> (0 until values.length()).map(values::getLong) }.orEmpty(),
        acknowledgedCompactionIds = parseStringArray(row.optJSONArray("acknowledgedCompactionIds")),
        createdAtSeconds = time,
    )

    private fun timestampSeconds(row: JSONObject, type: String? = null): Double {
        val timestamp = row.opt("timestamp")
        if (timestamp is Number) return timestamp.toDouble()
        val millis = row.longOrNull("producedAtMillis") ?: row.longOrNull("timestampMillis")
            ?: row.longOrNull("preparedAtMillis") ?: row.longOrNull("stateAtMillis")
            ?: row.longOrNull("checkpointSavedAtMillis") ?: row.longOrNull("recordedAtMillis")
            ?: if (type == "tool_activity") row.optJSONObject("activity")?.longOrNull("timestampMillis") else null
        return (millis ?: 0L) / 1000.0
    }

    private fun sourceUpdatedAtMillis(row: JSONObject): Long? = row.longOrNull("producedAtMillis")
        ?: row.longOrNull("timestampMillis") ?: row.longOrNull("preparedAtMillis") ?: row.longOrNull("stateAtMillis")
        ?: row.longOrNull("checkpointSavedAtMillis") ?: row.longOrNull("recordedAtMillis")
        ?: row.optJSONObject("activity")?.longOrNull("timestampMillis")

    private fun compoundKey(first: String, second: String): String = "${first.length}:$first$second"

    private fun crewSnapshotDigest(row: JSONObject): String {
        val serialized = JSONObject(row.toString()).apply { remove("timestamp") }.toString()
        val escaped = buildString(serialized.length + 1) {
            serialized.forEach { char ->
                if (Character.isSurrogate(char)) append("\\u").append(char.code.toString(16).padStart(4, '0')) else append(char)
            }
            append('\n')
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(escaped.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun markerKey(type: String, row: JSONObject, offset: Long): String {
        val identity = when (type) {
            "user", "assistant", "proactive_message", "assistant_regenerated", "assistant_deleted",
            "assistant_translation", "assistant_translation_hidden", "message_reaction", "proactive_reply_used" -> row.stringOrNull("messageId")
            "user_decision_request", "user_decision_resolution" -> row.stringOrNull("decisionId")
            "main_interrupt_request" -> row.stringOrNull("requestId")
            "model_tool_calls", "model_tool_started", "model_tool_not_started", "model_tool_result" ->
                listOfNotNull(row.stringOrNull("batchId"), row.stringOrNull("callId")).joinToString("/").takeIf(String::isNotEmpty)
            "model_artifact" -> row.stringOrNull("artifactId")
            "tool_activity" -> row.optJSONObject("activity")?.stringOrNull("eventId") ?: row.stringOrNull("eventId")
            "crew_snapshot" -> row.stringOrNull("missionId")
            "compaction" -> row.stringOrNull("compactionId")
            else -> row.stringOrNull("eventId") ?: row.stringOrNull("evidenceId") ?: row.stringOrNull("referenceId")
        }
        return identity?.takeIf(String::isNotBlank) ?: "source-offset-$offset"
    }

    private fun ledgerType(row: JSONObject): String {
        val type = row.optString("type", "")
        val role = row.optString("role", "")
        if (role == "session_title") return "session_title"
        if (role == "user" && row.has("content")) return "user"
        if (role == "assistant" && row.has("content")) return if (type == "proactive_message") "proactive_message" else "assistant"
        return type
    }

    /** Mirrors LocalRunStore.isResultOnlyRow for the filtered/reordered timeline index. */
    private fun isResultOnly(row: JSONObject): Boolean {
        val type = row.optString("type", "")
        return type.startsWith("crew_coordination_") || type.startsWith("workspace_coordination_") ||
            row.has("crewCoordinationSchemaVersion") || row.has("workspaceCoordinationSchemaVersion") ||
            row.has("workspaceCoordinationReplySchemaVersion") || row.has("replyId") ||
            type.startsWith("workspace_input_") || row.has("workspaceInputIncorporationSchemaVersion") || row.has("receiptId") ||
            type.startsWith("crew_result_") || type.startsWith("workspace_result_") ||
            row.has("crewResultSchemaVersion") || row.has("workspaceResultSchemaVersion") ||
            row.has("evidenceId") || row.has("referenceId")
    }

    private fun likelyStrictRow(bytes: ByteArray): Boolean {
        val ascii = String(bytes, StandardCharsets.ISO_8859_1)
        return STRICT_TYPES.any { type ->
            Regex("\\\"type\\\"\\s*:\\s*\\\"${Regex.escape(type)}\\\"").containsMatchIn(ascii)
        }
    }

    private fun countLinesBefore(file: File, offset: Long): Long {
        if (offset == 0L) return 0L
        val reader = RandomAccessFile(file, "r")
        reader.use { input ->
            val buffer = ByteArray(64 * 1024)
            var remaining = offset
            var lines = 0L
            while (remaining > 0L) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) break
                for (index in 0 until count) if (buffer[index] == '\n'.code.toByte()) lines++
                remaining -= count
            }
            return lines
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256Prefix(file: File, offset: Long): String = sha256DigestPrefix(file, offset).digest().toHex()

    private fun sha256DigestPrefix(file: File, offset: Long): MessageDigest {
        require(offset >= 0L && offset <= file.length()) { "invalid source prefix offset $offset" }
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { input ->
            val buffer = ByteArray(128 * 1024)
            var remaining = offset
            while (remaining > 0L) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(count > 0) { "ledger shortened while hashing prefix $offset" }
                digest.update(buffer, 0, count)
                remaining -= count
            }
        }
        return digest
    }

    private fun digestAtOffset(digest: MessageDigest, file: File, offset: Long): String =
        try {
            (digest.clone() as MessageDigest).digest().toHex()
        } catch (_: CloneNotSupportedException) {
            // Providers without clone support retain correctness; normal Android/JVM SHA-256 is cloneable.
            sha256Prefix(file, offset)
        }

    private fun updateDigest(digest: MessageDigest, line: NonInterruptibleLedgerFile.Line) {
        digest.update(line.bytes)
        if (line.terminated) {
            if (line.bytesIncludingTerminator - line.bytes.size > 1L) digest.update('\r'.code.toByte())
            digest.update('\n'.code.toByte())
        }
    }

    private fun requireSuccess(result: ChatDbResult<*>): Any? = when (result) {
        is ChatDbResult.Success<*> -> result.value
        ChatDbResult.NotFound -> error("repository_not_found")
        ChatDbResult.Tombstoned -> error("conversation_tombstoned")
        is ChatDbResult.Conflict -> error("repository_conflict:${result.reason}")
        is ChatDbResult.Invalid -> error("repository_invalid:${result.reason}")
    }

    private fun <T> requireSuccessTyped(result: ChatDbResult<T>): T = when (result) {
        is ChatDbResult.Success -> result.value
        ChatDbResult.NotFound -> error("repository_not_found")
        ChatDbResult.Tombstoned -> error("conversation_tombstoned")
        is ChatDbResult.Conflict -> error("repository_conflict:${result.reason}")
        is ChatDbResult.Invalid -> error("repository_invalid:${result.reason}")
    }

    private fun JSONObject.stringOrNull(name: String): String? = if (!has(name) || isNull(name)) null else getString(name)
    private fun JSONObject.longOrNull(name: String): Long? = if (!has(name) || isNull(name)) null else getLong(name)

    /**
     * The strict line parser keeps every JSON number as BigDecimal to avoid precision loss. The
     * legacy JSONObject reader used Integer/Long for plain integer tokens, which strict journal
     * validators intentionally require. Restore that representation without accepting decimal or
     * out-of-range values as integers.
     */
    private fun parseLedgerObject(text: String): JSONObject =
        normalizeIntegralJsonNumbers(StrictLedgerJson.parseObject(text)) as JSONObject

    private fun normalizeIntegralJsonNumbers(value: Any): Any = when (value) {
        is JSONObject -> {
            val keys = value.keys().asSequence().toList()
            keys.forEach { key ->
                val child = value.get(key)
                if (child !== JSONObject.NULL) value.put(key, normalizeIntegralJsonNumbers(child))
            }
            value
        }
        is JSONArray -> {
            for (index in 0 until value.length()) {
                val child = value.get(index)
                if (child !== JSONObject.NULL) value.put(index, normalizeIntegralJsonNumbers(child))
            }
            value
        }
        is BigDecimal -> if (value.scale() == 0) {
            val integer = runCatching { value.longValueExact() }.getOrNull()
            when {
                integer == null -> value
                integer >= Int.MIN_VALUE.toLong() && integer <= Int.MAX_VALUE.toLong() -> integer.toInt()
                else -> integer
            }
        } else value
        else -> value
    }

    private fun updateLengthPrefixed(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val length = bytes.size.toLong()
        for (shift in 56 downTo 0 step 8) digest.update((length ushr shift).toByte())
        digest.update(bytes)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun android.database.Cursor.countRows(): Int { var count = 0; while (moveToNext()) count++; return count }

    private fun Any.toJsonValue(): String = when (this) {
        is JSONObject, is JSONArray -> toString()
        else -> JSONObject().put("value", this).get("value").toString()
    }

    private fun timestampToken(lineBytes: ByteArray): String {
        val text = StrictLedgerJson.decodeUtf8(lineBytes)
        // LocalRunStore builds an org.json JSONObject and calls optString("timestamp", "");
        // preserve that coercion (including empty/missing values) for legacy compaction IDs.
        return JSONObject(text).optString("timestamp", "")
    }

    private fun legacyMessageId(sessionId: String, messageIndex: Long): String {
        val name = "$sessionId\u0000$messageIndex".toByteArray(StandardCharsets.UTF_8)
        return "legacy-${UUID.nameUUIDFromBytes(name)}"
    }

    private data class ImportMetadata(
        val deleted: Boolean = false,
        val title: String? = null,
        val pinned: Boolean = false,
        val archived: Boolean = false,
        val privateCode: Boolean = false,
    )

    private data class StoredSkips(val counts: Map<String, Long>)

    private data class Preflight(
        val lines: Long,
        val knownLines: Long,
        val unknown: Long,
        val typeCounts: Map<String, Long>,
        val validations: List<ImportValidation>,
        val strictFailure: String?,
        val firstTitle: String?,
        val firstTimestampMillis: Long?,
        val messageCount: Long,
        val issueCount: Long,
    )
    private data class MessageMeta(
        val offset: Long,
        val rawMessageId: String?,
        val beforeMessageId: String?,
        val isAssistant: Boolean,
        val ordinaryUser: Boolean,
        var projectionIndex: Long,
        var sortSeq: Long,
        var sortSub: Int,
        var messageIndex: Long = -1L,
    )
    private data class LineOutcome(
        val kind: LineOutcomeKind,
        val type: String,
        val reason: String? = null,
        val ordinaryUserSeq: Long? = null,
        val failure: String? = null,
    )
    private enum class LineOutcomeKind { IMPORTED, SKIPPED, UNKNOWN }
    private class StrictLedgerFailure(
        val lineNumber: Long,
        val sourceOffset: Long,
        val bytes: Long,
        val reason: String,
        message: String,
    ) : IllegalStateException(message)

    private companion object {
        val MESSAGE_TYPES = setOf("user", "assistant", "proactive_message")
        val STRICT_TYPES = setOf(
            "crew_result_evidence", "workspace_result_reference", "workspace_result_notice", "crew_coordination_evidence",
            "workspace_coordination_reference", "workspace_coordination_notice", "workspace_coordination_reply_intent",
            "workspace_coordination_reply_state", "workspace_input_incorporation",
        )
        val PREFLIGHT_TABLES = listOf(
            "chatdb_import_rows", "chatdb_import_issues", "chatdb_import_unknown_types", "chatdb_import_messages", "chatdb_import_compactions", "chatdb_import_crews",
            "chatdb_import_message_sources", "chatdb_import_hidden", "chatdb_import_translations", "chatdb_import_translation_hidden",
        )
        val KNOWN_TYPES = setOf(
            "user", "assistant", "proactive_message", "session_title",
            "assistant_regenerated", "assistant_deleted", "assistant_translation", "assistant_translation_hidden", "message_reaction", "proactive_reply_used",
            "user_decision_request", "user_decision_resolution", "delivered_file", "generated_image",
            "main_interrupt_request", "main_chat_input_incorporated", "main_chat_input_resolved",
            "model_tool_calls", "model_tool_started", "model_tool_not_started", "model_tool_result", "model_artifact", "model_error", "model_provider_output_items",
            "tool_presentation", "tool_activity", "reflection_tool",
            "compaction", "compaction_reflection_consumed", "reflection_checkpoint", "reflection_commit", "reflection_event", "reflection_undo",
            "crew_snapshot", "crew_result_evidence", "workspace_result_reference", "workspace_result_notice", "crew_coordination_evidence",
            "workspace_coordination_reference", "workspace_coordination_notice", "workspace_coordination_reply_intent", "workspace_coordination_reply_state",
            "workspace_input_incorporation",
        )
    }
}
