package com.jarvys.agent.chatdb

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import com.jarvys.agent.crew.CrewCoordinationEvidence as CrewCoordinationLedgerEvidence
import com.jarvys.agent.crew.CrewResultEvidence as CrewResultLedgerEvidence
import com.jarvys.agent.workspaces.WorkspaceCoordinationReference as CoordinationReference
import com.jarvys.agent.workspaces.WorkspaceCoordinationReply as CoordinationReply
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceInputIncorporation as InputIncorporation
import com.jarvys.agent.workspaces.WorkspaceResultReference as ResultReference
import java.util.Collections

/**
 * Normalized persistence for the fail-closed workspace and crew journals. It validates the same
 * source bindings as LocalRunStore's strict whole-ledger scan while keeping ledger identities
 * conversation-scoped in SQLite. Reads use the query executor and a deferred reader transaction;
 * writes use Room's writer transaction. The synchronous `beforeWrite` callbacks below execute
 * while the writer transaction is held. They may perform cancellation/admission checks only: they
 * must not call a repository or a blocking facade, which can wait for the writer lease they hold.
 */
internal class StrictJournalRepository(private val database: JarvysChatDatabase) {
    private val sql get() = database.chatSql

    /**
     * Persists immutable `crew_result_evidence` rows, replacing
     * `LocalRunStore.appendCrewResultEvidence` (`LocalRunStore.java:appendCrewResultEvidence`).
     * An identical retry returns the first observation; any payload difference conflicts.
     */
    suspend fun appendCrewResultEvidence(
        evidence: CrewResultLedgerEvidence,
        beforeWrite: () -> Unit = {},
    ): ChatDbResult<CrewResultLedgerEvidence> = transaction {
        CrewResultLedgerEvidence.fromJsonStrict(evidence.toJson())
        ensureExecutionConversationForWrite(evidence.conversationId)
        val old = findCrewResult(evidence.conversationId, evidence.evidenceId)
        if (old != null) {
            if (!sameResult(old, evidence)) return@transaction ChatDbResult.Conflict(RESULT_CONFLICT)
            return@transaction ChatDbResult.Success(old)
        }
        ChatSqlBeforeWriteGuard.invoke(beforeWrite)
        val seq = appendItem(evidence.conversationId, CrewResultLedgerEvidence.ROW_TYPE, evidence.evidenceId)
        sql.execSQL(
            "INSERT INTO crew_result_evidence(conversation_id,evidence_id,seq,schema_version,mission_id,bot_id,cycle_id,reported_status,result_text,produced_at_ms,content_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(evidence.conversationId, evidence.evidenceId, seq, CrewResultLedgerEvidence.SCHEMA_VERSION,
                evidence.missionId, evidence.botId, evidence.cycleId, evidence.reportedStatus,
                ExactUtf16.encode(evidence.resultText), evidence.producedAtMillis, evidence.contentSha256),
        )
        ChatDbResult.Success(evidence)
    }

    /**
     * Reads exact `crew_result_evidence` by its conversation-scoped evidence id, replacing
     * `LocalRunStore.readCrewResultEvidence` (`LocalRunStore.java:readCrewResultEvidence`).
     */
    suspend fun readCrewResultEvidence(
        executionSessionId: String,
        evidenceId: String,
    ): ChatDbResult<CrewResultLedgerEvidence?> = readTransaction {
        requireExecutionConversation(executionSessionId)
        ResultReference.requireEvidenceId(evidenceId)
        ChatDbResult.Success(findCrewResult(executionSessionId, evidenceId))
    }

    /**
     * Lists lexical `crew_result_evidence` IDs, replacing
     * `LocalRunStore.listCrewResultEvidence` (`LocalRunStore.java:listCrewResultEvidence`).
     * The page size retains the existing LocalRunStore range of 1..100.
     */
    suspend fun listCrewResultEvidenceIds(
        executionSessionId: String,
        limit: Int,
        afterEvidenceId: String? = null,
    ): ChatDbResult<List<String>> = readTransaction {
        requireExecutionConversation(executionSessionId)
        requirePage(limit)
        val rows = mutableListOf<String>()
        val after = afterEvidenceId?.also { ResultReference.requireEvidenceId(it) }
        val where = if (after == null) "" else " AND evidence_id>?"
        val args = if (after == null) arrayOf<Any?>(executionSessionId, limit) else arrayOf(executionSessionId, after, limit)
        sql.query("SELECT evidence_id FROM crew_result_evidence WHERE conversation_id=?$where ORDER BY evidence_id LIMIT ?", args)
            .use { c -> while (c.moveToNext()) rows += c.getString(0) }
        ChatDbResult.Success(rows)
    }

    /**
     * Persists a source-ledger `workspace_result_reference`, replacing
     * `LocalRunStore.appendWorkspaceResultReferenceIfAbsent` (`LocalRunStore.java:appendWorkspaceResultReferenceIfAbsent`).
     * The exact evidence row must already exist in the stated execution conversation.
     */
    suspend fun appendWorkspaceResultReference(
        reference: ResultReference,
        beforeWrite: () -> Unit = {},
    ): ChatDbResult<ResultReference> = transaction {
        ResultReference.fromJsonStrict(reference.toJson())
        ensureExecutionConversationForWrite(reference.executionSessionId)
        if (reference.ownerCentralSessionId != WorkspaceIdentity.CENTRAL_SESSION) return@transaction ChatDbResult.Invalid("non-canonical Central owner")
        val evidence = findCrewResult(reference.executionSessionId, reference.evidenceId)
            ?: return@transaction ChatDbResult.Conflict("Reference has no matching durable evidence")
        if (!reference.matchesEvidence(evidence)) return@transaction ChatDbResult.Conflict("Reference has no matching durable evidence")
        val old = findResultRef(reference.executionSessionId, reference.referenceId, false)
        if (old != null) {
            if (!old.sameBinding(reference)) return@transaction ChatDbResult.Conflict(RESULT_CONFLICT)
            return@transaction ChatDbResult.Success(old)
        }
        ChatSqlBeforeWriteGuard.invoke(beforeWrite)
        insertResultRef(reference.executionSessionId, reference, false)
        ChatDbResult.Success(reference)
    }

    /**
     * Persists a Central `workspace_result_notice`, replacing
     * `LocalRunStore.appendWorkspaceResultNoticeIfAbsent` (`LocalRunStore.java:appendWorkspaceResultNoticeIfAbsent`).
     * The execution evidence and source reference are re-read and compared before the notice is written.
     */
    suspend fun appendWorkspaceResultNotice(
        reference: ResultReference,
        beforeWrite: () -> Unit = {},
    ): ChatDbResult<ResultReference> = transaction {
        ResultReference.fromNoticeJsonStrict(reference.toNoticeJson())
        ensureCentralConversationForWrite(reference.ownerCentralSessionId)
        val source = findResultRef(reference.executionSessionId, reference.referenceId, false)
            ?: return@transaction ChatDbResult.Conflict("Notice has no exact durable source reference")
        if (!source.samePayload(reference)) return@transaction ChatDbResult.Conflict("Notice has no exact durable source reference")
        val evidence = findCrewResult(reference.executionSessionId, reference.evidenceId)
            ?: return@transaction ChatDbResult.Conflict("Notice has no matching execution evidence")
        if (!reference.matchesEvidence(evidence)) return@transaction ChatDbResult.Conflict("Notice has no matching execution evidence")
        val old = findResultRef(reference.ownerCentralSessionId, reference.referenceId, true)
        if (old != null) {
            if (!old.samePayload(reference)) return@transaction ChatDbResult.Conflict(RESULT_CONFLICT)
            return@transaction ChatDbResult.Success(old)
        }
        ChatSqlBeforeWriteGuard.invoke(beforeWrite)
        insertResultRef(reference.ownerCentralSessionId, reference, true)
        ChatDbResult.Success(reference)
    }

    /**
     * Reads a `workspace_result_reference` or `workspace_result_notice`, replacing
     * `LocalRunStore.readWorkspaceResultReference` / `readWorkspaceResultNotice` (`LocalRunStore.java:readWorkspaceResultReference`, `LocalRunStore.java:readWorkspaceResultNotice`).
     * A stored reference without its exact execution evidence is treated as corruption.
     */
    suspend fun readWorkspaceResultReference(
        conversationId: String,
        referenceId: String,
        isNotice: Boolean,
    ): ChatDbResult<ResultReference?> = readTransaction {
        if (isNotice) requireCentralConversation(conversationId) else requireExecutionConversation(conversationId)
        ResultReference.requireReferenceId(referenceId)
        ChatDbResult.Success(findResultRef(conversationId, referenceId, isNotice))
    }

    /**
     * Lists a lexical page of `workspace_result_reference` or `_notice` rows, replacing
     * `LocalRunStore.listWorkspaceResultReferences` / `listWorkspaceResultNotices` (`LocalRunStore.java:listWorkspaceResultReferences`, `LocalRunStore.java:listWorkspaceResultNotices`).
     */
    suspend fun listWorkspaceResultReferences(
        conversationId: String,
        isNotice: Boolean,
        limit: Int,
        afterReferenceId: String? = null,
    ): ChatDbResult<List<ResultReference>> = readTransaction {
        if (isNotice) requireCentralConversation(conversationId) else requireExecutionConversation(conversationId)
        requirePage(limit)
        val after = afterReferenceId?.also { ResultReference.requireReferenceId(it) }
        val where = if (after == null) "" else " AND r.reference_id>?"
        val noticeFlag = if (isNotice) 1 else 0
        val args = if (after == null) arrayOf<Any?>(conversationId, noticeFlag, limit)
            else arrayOf(conversationId, noticeFlag, after, limit)
        val rows = sql.query(
            "SELECT r.schema_version,r.evidence_id,r.content_sha256,r.owner_central_session_id,r.workspace_id,r.execution_session_id," +
                "r.assignment_id,r.request_id,r.mission_id,r.bot_id,r.cycle_id,r.observed_assignment_revision,r.reported_status,r.produced_at_ms," +
                "r.reference_id,e.schema_version,e.mission_id,e.bot_id,e.cycle_id,e.reported_status,e.result_text,e.produced_at_ms,e.evidence_id,e.content_sha256," +
                "CASE WHEN r.is_notice=0 THEN 1 WHEN s.reference_id IS NOT NULL AND s.schema_version=r.schema_version AND " +
                "s.evidence_id=r.evidence_id AND s.content_sha256=r.content_sha256 AND s.owner_central_session_id=r.owner_central_session_id AND " +
                "s.workspace_id=r.workspace_id AND s.execution_session_id=r.execution_session_id AND s.assignment_id=r.assignment_id AND " +
                "s.request_id=r.request_id AND s.mission_id=r.mission_id AND s.bot_id=r.bot_id AND s.cycle_id=r.cycle_id AND " +
                "s.observed_assignment_revision=r.observed_assignment_revision AND s.reported_status=r.reported_status AND " +
                "s.produced_at_ms=r.produced_at_ms THEN 1 ELSE 0 END AS source_matches " +
                "FROM workspace_result_refs r LEFT JOIN crew_result_evidence e ON e.conversation_id=r.execution_session_id AND e.evidence_id=r.evidence_id " +
                "LEFT JOIN workspace_result_refs s ON r.is_notice=1 AND s.conversation_id=r.execution_session_id AND s.reference_id=r.reference_id AND s.is_notice=0 " +
                "WHERE r.conversation_id=? AND r.is_notice=?$where ORDER BY r.reference_id LIMIT ?", args,
        ).use { c -> buildList {
            while (c.moveToNext()) {
                check(!c.isNull(15)) { "Corrupt workspace_result_refs: execution evidence is missing" }
                val row = ResultRefRow(c.getInt(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5),
                    c.getString(6), c.getString(7), c.getString(8), c.getString(9), c.getString(10), c.getLong(11), c.getString(12), c.getLong(13))
                val referenceId = c.getString(14)
                val evidence = CrewResultLedgerEvidence.create(row.executionSessionId, c.getString(16), c.getString(17), c.getString(18),
                    c.getString(19), ExactUtf16.decode(c.getBlob(20)), c.getLong(21))
                check(c.getInt(15) == CrewResultLedgerEvidence.SCHEMA_VERSION && evidence.evidenceId == c.getString(22) &&
                    evidence.contentSha256 == c.getString(23)) { "Corrupt crew_result_evidence payload" }
                val value = ResultReference.create(evidence, row.ownerCentralSessionId, row.workspaceId, row.assignmentId, row.requestId, row.revision)
                check(row.schemaVersion == ResultReference.SCHEMA_VERSION && row.evidenceId == evidence.evidenceId &&
                    (if (isNotice) conversationId == row.ownerCentralSessionId else conversationId == row.executionSessionId) &&
                    value.referenceId == referenceId && value.contentSha256 == row.contentSha256 &&
                    value.missionId == row.missionId && value.botId == row.botId && value.cycleId == row.cycleId &&
                    value.reportedStatus == row.status && value.producedAtMillis == row.producedAtMillis && c.getInt(24) == 1) {
                    "Corrupt workspace_result_refs payload"
                }
                add(value)
            }
        } }
        ChatDbResult.Success(rows)
    }

    /**
     * Revalidates `workspace_result_reference` and optional Central `workspace_result_notice`
     * before disclosure, replacing `LocalRunStore.requireWorkspaceResultDisclosureLive`
     * (`LocalRunStore.java:requireWorkspaceResultDisclosureLive`).
     */
    suspend fun requireWorkspaceResultDisclosureLive(
        reference: ResultReference,
        noticeRequired: Boolean,
    ): ChatDbResult<Unit> = readTransaction {
        ResultReference.fromJsonStrict(reference.toJson())
        requireExecutionConversation(reference.executionSessionId)
        val source = findResultRef(reference.executionSessionId, reference.referenceId, false)
            ?: return@readTransaction ChatDbResult.Conflict("durable execution reference is unavailable")
        if (!source.samePayload(reference)) return@readTransaction ChatDbResult.Conflict("durable execution reference differs")
        if (isTombstoned(reference.ownerCentralSessionId)) return@readTransaction ChatDbResult.Tombstoned
        val centralExists = sql.query("SELECT 1 FROM conversations WHERE id=?", arrayOf(reference.ownerCentralSessionId)).use { it.moveToFirst() }
        if (noticeRequired && !centralExists) return@readTransaction ChatDbResult.Conflict("Central receipt is unavailable")
        if (centralExists) {
            val notice = findResultRef(reference.ownerCentralSessionId, reference.referenceId, true)
            if (notice != null && !notice.samePayload(reference)) return@readTransaction ChatDbResult.Conflict("Central receipt differs")
            if (noticeRequired && notice == null) return@readTransaction ChatDbResult.Conflict("Central receipt is unavailable")
        }
        ChatDbResult.Success(Unit)
    }

    /**
     * Persists immutable `crew_coordination_evidence` plus its normalized `refs[]`, replacing
     * `LocalRunStore.appendCrewCoordinationEvidence` (`LocalRunStore.java:appendCrewCoordinationEvidence`).
     * Text bytes are exact UTF-16 code units, including unpaired surrogates.
     */
    suspend fun appendCrewCoordinationEvidence(
        evidence: CrewCoordinationLedgerEvidence,
        beforeWrite: () -> Unit = {},
    ): ChatDbResult<CrewCoordinationLedgerEvidence> = transaction {
        CrewCoordinationLedgerEvidence.fromJsonStrict(evidence.toJson())
        ensureExecutionConversationForWrite(evidence.conversationId)
        val old = findCrewCoordinationEvidence(evidence.conversationId, evidence.evidenceId)
        if (old != null) {
            if (!sameCoordination(old, evidence)) return@transaction ChatDbResult.Conflict(COORDINATION_CONFLICT)
            return@transaction ChatDbResult.Success(old)
        }
        ChatSqlBeforeWriteGuard.invoke(beforeWrite)
        val seq = appendItem(evidence.conversationId, CrewCoordinationLedgerEvidence.ROW_TYPE, evidence.evidenceId)
        sql.execSQL(
            "INSERT INTO crew_coordination_evidence(conversation_id,evidence_id,seq,schema_version,mission_id,bot_id,cycle_id,message_id,kind,text,produced_at_ms,content_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(evidence.conversationId, evidence.evidenceId, seq, CrewCoordinationLedgerEvidence.SCHEMA_VERSION,
                evidence.missionId, evidence.botId, evidence.cycleId, evidence.messageId, evidence.kind,
                ExactUtf16.encode(evidence.text), evidence.producedAtMillis, evidence.contentSha256),
        )
        evidence.refs.forEachIndexed { ordinal, ref ->
            sql.execSQL(
                "INSERT INTO crew_coordination_evidence_refs(conversation_id,evidence_id,ordinal,ref) VALUES(?,?,?,?)",
                arrayOf(evidence.conversationId, evidence.evidenceId, ordinal, ref),
            )
        }
        ChatDbResult.Success(evidence)
    }

    /**
     * Reads `crew_coordination_evidence`, replacing `LocalRunStore.readCrewCoordinationEvidence`
     * (`LocalRunStore.java:readCrewCoordinationEvidence`); exact text and normalized refs are restored.
     */
    suspend fun readCrewCoordinationEvidence(
        executionSessionId: String,
        evidenceId: String,
    ): ChatDbResult<CrewCoordinationLedgerEvidence?> = readTransaction {
        requireExecutionConversation(executionSessionId)
        CoordinationReference.requireEvidenceId(evidenceId)
        ChatDbResult.Success(findCrewCoordinationEvidence(executionSessionId, evidenceId))
    }

    /**
     * Lists lexical `crew_coordination_evidence` IDs, replacing
     * `LocalRunStore.listCrewCoordinationEvidence` (`LocalRunStore.java:listCrewCoordinationEvidence`).
     */
    suspend fun listCrewCoordinationEvidenceIds(
        executionSessionId: String,
        limit: Int,
        afterEvidenceId: String? = null,
    ): ChatDbResult<List<String>> = readTransaction {
        requireExecutionConversation(executionSessionId)
        requirePage(limit)
        val after = afterEvidenceId?.also { CoordinationReference.requireEvidenceId(it) }
        val where = if (after == null) "" else " AND evidence_id>?"
        val args = if (after == null) arrayOf<Any?>(executionSessionId, limit) else arrayOf(executionSessionId, after, limit)
        val ids = sql.query("SELECT evidence_id FROM crew_coordination_evidence WHERE conversation_id=?$where ORDER BY evidence_id LIMIT ?", args)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        ChatDbResult.Success(ids)
    }

    /**
     * Persists source-ledger `workspace_coordination_reference`, replacing
     * `LocalRunStore.appendWorkspaceCoordinationReferenceIfAbsent` (`LocalRunStore.java:appendWorkspaceCoordinationReferenceIfAbsent`).
     */
    suspend fun appendWorkspaceCoordinationReference(
        reference: CoordinationReference,
        beforeWrite: () -> Unit = {},
    ): ChatDbResult<CoordinationReference> = transaction {
        CoordinationReference.fromJsonStrict(reference.toJson())
        ensureExecutionConversationForWrite(reference.executionSessionId)
        if (reference.ownerCentralSessionId != WorkspaceIdentity.CENTRAL_SESSION) return@transaction ChatDbResult.Invalid("non-canonical Central owner")
        val evidence = findCrewCoordinationEvidence(reference.executionSessionId, reference.evidenceId)
            ?: return@transaction ChatDbResult.Conflict("Coordination reference has no matching durable evidence")
        if (!reference.matchesEvidence(evidence)) return@transaction ChatDbResult.Conflict("Coordination reference has no matching durable evidence")
        val old = findCoordinationRef(reference.executionSessionId, reference.referenceId, false)
        if (old != null) {
            if (!old.sameBinding(reference)) return@transaction ChatDbResult.Conflict(COORDINATION_CONFLICT)
            return@transaction ChatDbResult.Success(old)
        }
        ChatSqlBeforeWriteGuard.invoke(beforeWrite)
        insertCoordinationRef(reference.executionSessionId, reference, false)
        ChatDbResult.Success(reference)
    }

    /**
     * Persists Central `workspace_coordination_notice`, replacing
     * `LocalRunStore.appendWorkspaceCoordinationNoticeIfAbsent` (`LocalRunStore.java:appendWorkspaceCoordinationNoticeIfAbsent`).
     */
    suspend fun appendWorkspaceCoordinationNotice(
        reference: CoordinationReference,
        beforeWrite: () -> Unit = {},
    ): ChatDbResult<CoordinationReference> = transaction {
        CoordinationReference.fromNoticeJsonStrict(reference.toNoticeJson())
        ensureCentralConversationForWrite(reference.ownerCentralSessionId)
        val source = findCoordinationRef(reference.executionSessionId, reference.referenceId, false)
            ?: return@transaction ChatDbResult.Conflict("Coordination notice has no exact durable source reference")
        if (!source.samePayload(reference)) return@transaction ChatDbResult.Conflict("Coordination notice has no exact durable source reference")
        val evidence = findCrewCoordinationEvidence(reference.executionSessionId, reference.evidenceId)
            ?: return@transaction ChatDbResult.Conflict("Coordination notice has no matching execution evidence")
        if (!reference.matchesEvidence(evidence)) return@transaction ChatDbResult.Conflict("Coordination notice has no matching execution evidence")
        val old = findCoordinationRef(reference.ownerCentralSessionId, reference.referenceId, true)
        if (old != null) {
            if (!old.samePayload(reference)) return@transaction ChatDbResult.Conflict(COORDINATION_CONFLICT)
            return@transaction ChatDbResult.Success(old)
        }
        ChatSqlBeforeWriteGuard.invoke(beforeWrite)
        insertCoordinationRef(reference.ownerCentralSessionId, reference, true)
        ChatDbResult.Success(reference)
    }

    /**
     * Reads `workspace_coordination_reference` or `_notice`, replacing
     * `LocalRunStore.readWorkspaceCoordinationReference` / `readWorkspaceCoordinationNotice` (`LocalRunStore.java:readWorkspaceCoordinationReference`, `LocalRunStore.java:readWorkspaceCoordinationNotice`).
     */
    suspend fun readWorkspaceCoordinationReference(
        conversationId: String,
        referenceId: String,
        isNotice: Boolean,
    ): ChatDbResult<CoordinationReference?> = readTransaction {
        if (isNotice) requireCentralConversation(conversationId) else requireExecutionConversation(conversationId)
        CoordinationReference.requireReferenceId(referenceId)
        ChatDbResult.Success(findCoordinationRef(conversationId, referenceId, isNotice))
    }

    /** Resolves an originating Crew message ID through the existing message index. */
    suspend fun findWorkspaceCoordinationReferenceBySourceMessageId(
        conversationId: String,
        sourceMessageId: String,
    ): ChatDbResult<CoordinationReference?> = readTransaction {
        requireExecutionConversation(conversationId)
        require(sourceMessageId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}")))
        val ids = sql.query(
            "SELECT reference_id FROM workspace_coordination_refs WHERE conversation_id=? AND is_notice=0 AND message_id=? ORDER BY reference_id LIMIT 2",
            arrayOf(conversationId, sourceMessageId),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        if (ids.size > 1) return@readTransaction ChatDbResult.Conflict(
            "Crew source message ID resolves to multiple coordination references")
        ChatDbResult.Success(ids.singleOrNull()?.let { findCoordinationRef(conversationId, it, false) })
    }

    /**
     * Lists a lexical page of coordination references/notices, replacing
     * `LocalRunStore.listWorkspaceCoordinationReferences` / `listWorkspaceCoordinationNotices` (`LocalRunStore.java:listWorkspaceCoordinationReferences`, `LocalRunStore.java:listWorkspaceCoordinationNotices`).
     */
    suspend fun listWorkspaceCoordinationReferences(
        conversationId: String,
        isNotice: Boolean,
        limit: Int,
        afterReferenceId: String? = null,
    ): ChatDbResult<List<CoordinationReference>> = readTransaction {
        if (isNotice) requireCentralConversation(conversationId) else requireExecutionConversation(conversationId)
        requirePage(limit)
        val after = afterReferenceId?.also { CoordinationReference.requireReferenceId(it) }
        val where = if (after == null) "" else " AND reference_id>?"
        val noticeFlag = if (isNotice) 1 else 0
        val args = if (after == null) arrayOf<Any?>(conversationId, noticeFlag, limit)
            else arrayOf(conversationId, noticeFlag, after, limit)
        val values = mutableListOf<CoordinationReference>()
        sql.query(
            "WITH page AS (SELECT * FROM workspace_coordination_refs WHERE conversation_id=? AND is_notice=?$where ORDER BY reference_id LIMIT ?) " +
                "SELECT r.schema_version,r.evidence_id,r.content_sha256,r.owner_central_session_id,r.workspace_id,r.execution_session_id," +
                "r.assignment_id,r.request_id,r.mission_id,r.bot_id,r.cycle_id,r.message_id,r.kind,r.observed_assignment_revision,r.produced_at_ms," +
                "r.reference_id,e.schema_version,e.mission_id,e.bot_id,e.cycle_id,e.message_id,e.kind,e.text,e.produced_at_ms,e.evidence_id,e.content_sha256," +
                "er.ordinal,er.ref,CASE WHEN r.is_notice=0 THEN 1 WHEN s.reference_id IS NOT NULL AND s.schema_version=r.schema_version AND " +
                "s.evidence_id=r.evidence_id AND s.content_sha256=r.content_sha256 AND s.owner_central_session_id=r.owner_central_session_id AND " +
                "s.workspace_id=r.workspace_id AND s.execution_session_id=r.execution_session_id AND s.assignment_id=r.assignment_id AND " +
                "s.request_id=r.request_id AND s.mission_id=r.mission_id AND s.bot_id=r.bot_id AND s.cycle_id=r.cycle_id AND " +
                "s.message_id=r.message_id AND s.kind=r.kind AND s.observed_assignment_revision=r.observed_assignment_revision AND " +
                "s.produced_at_ms=r.produced_at_ms THEN 1 ELSE 0 END AS source_matches " +
                "FROM page r LEFT JOIN crew_coordination_evidence e ON e.conversation_id=r.execution_session_id AND e.evidence_id=r.evidence_id " +
                "LEFT JOIN crew_coordination_evidence_refs er ON er.conversation_id=e.conversation_id AND er.evidence_id=e.evidence_id " +
                "LEFT JOIN workspace_coordination_refs s ON r.is_notice=1 AND s.conversation_id=r.execution_session_id AND s.reference_id=r.reference_id AND s.is_notice=0 " +
                "ORDER BY r.reference_id,er.ordinal", args,
        ).use { c ->
            var activeReferenceId: String? = null
            var refRow: CoordinationRefRow? = null
            var evidenceRow: CoordinationEvidenceRow? = null
            var evidenceRefs = mutableListOf<String>()
            var sourceMatches = false
            fun emit() {
                val ref = refRow ?: return
                val evidenceData = checkNotNull(evidenceRow) { "Corrupt crew_coordination_evidence payload" }
                check(evidenceData.schemaVersion == CrewCoordinationLedgerEvidence.SCHEMA_VERSION) {
                    "Unsupported crew_coordination_evidence schema"
                }
                val evidence = CrewCoordinationLedgerEvidence.create(ref.executionSessionId, evidenceData.missionId, evidenceData.botId,
                    evidenceData.cycleId, evidenceData.messageId, evidenceData.kind, ExactUtf16.decode(evidenceData.text), evidenceRefs,
                    evidenceData.producedAtMillis)
                check(evidence.evidenceId == evidenceData.evidenceId && evidence.contentSha256 == evidenceData.contentSha256) {
                    "Corrupt crew_coordination_evidence payload"
                }
                val value = CoordinationReference.create(evidence, ref.ownerCentralSessionId, ref.workspaceId, ref.assignmentId,
                    ref.requestId, ref.revision)
                check(ref.schemaVersion == CoordinationReference.SCHEMA_VERSION && ref.evidenceId == evidence.evidenceId &&
                    (if (isNotice) conversationId == ref.ownerCentralSessionId else conversationId == ref.executionSessionId) &&
                    value.referenceId == activeReferenceId && value.contentSha256 == ref.contentSha256 && value.messageId == ref.messageId &&
                    value.kind == ref.kind && value.missionId == ref.missionId && value.botId == ref.botId && value.cycleId == ref.cycleId &&
                    value.producedAtMillis == ref.producedAtMillis && sourceMatches) { "Corrupt workspace_coordination_refs payload" }
                values += value
            }
            while (c.moveToNext()) {
                val referenceId = c.getString(15)
                if (activeReferenceId != referenceId) {
                    emit()
                    check(!c.isNull(16)) { "Corrupt workspace_coordination_refs: execution evidence is missing" }
                    activeReferenceId = referenceId
                    refRow = CoordinationRefRow(c)
                    evidenceRow = CoordinationEvidenceRow(
                        c.getInt(16), c.getString(17), c.getString(18), c.getString(19), c.getString(20), c.getString(21),
                        c.getBlob(22), c.getLong(23), c.getString(24), c.getString(25),
                    )
                    evidenceRefs = mutableListOf()
                    sourceMatches = c.getInt(28) == 1
                }
                if (!c.isNull(26)) {
                    check(c.getInt(26) == evidenceRefs.size) { "Corrupt crew_coordination_evidence_refs ordering" }
                    evidenceRefs += c.getString(27)
                }
            }
            emit()
        }
        ChatDbResult.Success(values)
    }

    /** Counts uncertain QUESTION/BLOCKER replies for requested workspaces in one Central-ledger read. */
    suspend fun countStartedQuestionBlockerRepliesByWorkspace(
        centralSessionId: String,
        workspaceIds: Set<String>,
    ): ChatDbResult<Map<String, Int>> = readTransaction {
        requireCentralConversation(centralSessionId)
        val ids = workspaceIds.map(WorkspaceIdentity::requireWorkspaceId).distinct()
        if (ids.isEmpty()) return@readTransaction ChatDbResult.Success(emptyMap())

        val counts = linkedMapOf<String, Int>().apply { ids.forEach { put(it, 0) } }
        val placeholders = ids.joinToString(",") { "?" }
        val args = mutableListOf<Any?>(centralSessionId, centralSessionId)
        args.addAll(ids)
        sql.query(
            "WITH high_water AS (SELECT COALESCE(next_seq-1,0) AS seq FROM conversations WHERE id=?) " +
                "SELECT ref.workspace_id,COUNT(*) " +
                "FROM workspace_coordination_replies AS reply INDEXED BY idx_workspace_replies_state " +
                "JOIN workspace_coordination_refs AS ref ON ref.conversation_id=reply.conversation_id " +
                "AND ref.reference_id=reply.reference_id AND ref.is_notice=1 " +
                "CROSS JOIN high_water hw " +
                "WHERE reply.conversation_id=? AND reply.state='STARTED' AND reply.seq<=hw.seq " +
                "AND ref.seq<=hw.seq AND ref.kind IN ('QUESTION','BLOCKER') " +
                "AND ref.workspace_id IN ($placeholders) GROUP BY ref.workspace_id",
            args.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) counts[cursor.getString(0)] = cursor.getInt(1)
        }
        ChatDbResult.Success(counts)
    }

    /**
     * Revalidates source `workspace_coordination_reference` and optional Central notice before
     * disclosure, replacing `LocalRunStore.requireWorkspaceCoordinationDisclosureLive`
     * (`LocalRunStore.java:requireWorkspaceCoordinationDisclosureLive`).
     */
    suspend fun requireWorkspaceCoordinationDisclosureLive(
        reference: CoordinationReference,
        noticeRequired: Boolean,
    ): ChatDbResult<Unit> = readTransaction {
        CoordinationReference.fromJsonStrict(reference.toJson())
        requireExecutionConversation(reference.executionSessionId)
        val source = findCoordinationRef(reference.executionSessionId, reference.referenceId, false)
            ?: return@readTransaction ChatDbResult.Conflict("durable execution coordination reference is unavailable")
        if (!source.samePayload(reference)) return@readTransaction ChatDbResult.Conflict("durable execution coordination reference differs")
        if (isTombstoned(reference.ownerCentralSessionId)) return@readTransaction ChatDbResult.Tombstoned
        val centralExists = sql.query("SELECT 1 FROM conversations WHERE id=?", arrayOf(reference.ownerCentralSessionId)).use { it.moveToFirst() }
        if (noticeRequired && !centralExists) return@readTransaction ChatDbResult.Conflict("Central coordination receipt is unavailable")
        if (centralExists) {
            val notice = findCoordinationRef(reference.ownerCentralSessionId, reference.referenceId, true)
            if (notice != null && !notice.samePayload(reference)) return@readTransaction ChatDbResult.Conflict("Central coordination receipt differs")
            if (noticeRequired && notice == null) return@readTransaction ChatDbResult.Conflict("Central coordination receipt is unavailable")
        }
        ChatDbResult.Success(Unit)
    }

    /**
     * Stores a `workspace_coordination_reply_intent` claim, replacing
     * `LocalRunStore.prepareWorkspaceCoordinationReply` (`LocalRunStore.java:prepareWorkspaceCoordinationReply`).
     * Only one immutable intent may claim an evidence identity in a Central conversation.
     */
    suspend fun prepareWorkspaceCoordinationReply(
        proposed: CoordinationReply,
        beforeWrite: () -> Unit = {},
    ): ChatDbResult<CoordinationReply> = transaction {
        CoordinationReply.fromJsonStrict(proposed.toJson())
        if (proposed.state != CoordinationReply.State.PREPARED) return@transaction ChatDbResult.Invalid("a new reply must be PREPARED")
        ensureCentralConversationForWrite(proposed.reference.ownerCentralSessionId)
        requireExactCoordinationSource(proposed.reference)
        val existing = findCoordinationReply(proposed.reference.ownerCentralSessionId, proposed.reference.referenceId)
        if (existing != null) {
            if (!existing.samePayload(proposed)) return@transaction ChatDbResult.Conflict(COORDINATION_CONFLICT)
            return@transaction ChatDbResult.Success(existing)
        }
        val evidenceClaim = sql.query(
            "SELECT reference_id FROM workspace_coordination_replies WHERE conversation_id=? AND evidence_id=? LIMIT 1",
            arrayOf(proposed.reference.ownerCentralSessionId, proposed.reference.evidenceId),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (evidenceClaim != null) return@transaction ChatDbResult.Conflict(COORDINATION_CONFLICT)
        ChatSqlBeforeWriteGuard.invoke(beforeWrite)
        val seq = appendItem(proposed.reference.ownerCentralSessionId, CoordinationReply.INTENT_TYPE, proposed.reference.referenceId)
        sql.execSQL(
            "INSERT INTO workspace_coordination_replies(conversation_id,reference_id,is_notice,schema_version,reply_id,evidence_id,reply_key,text,central_request_id,original_user_request,mailbox_message_id,payload_sha256,prepared_at_ms,state,state_at_ms,seq) VALUES(?,?,1,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(proposed.reference.ownerCentralSessionId, proposed.reference.referenceId, CoordinationReply.SCHEMA_VERSION,
                proposed.replyId, proposed.reference.evidenceId, proposed.replyKey, ExactUtf16.encode(proposed.text),
                proposed.centralRequestId, ExactUtf16.encode(proposed.originalUserRequest), proposed.mailboxMessageId,
                proposed.payloadSha256, proposed.preparedAtMillis, proposed.state.name, proposed.stateAtMillis, seq),
        )
        ChatDbResult.Success(proposed)
    }

    /**
     * Records `workspace_coordination_reply_state=STARTED`, replacing
     * `LocalRunStore.markWorkspaceCoordinationReplyStarted` (`LocalRunStore.java:markWorkspaceCoordinationReplyStarted`).
     * A durable STARTED state is uncertain and is never authorized for retry.
     */
    suspend fun markWorkspaceCoordinationReplyStarted(
        proposed: CoordinationReply,
        beforeWrite: () -> Unit = {},
    ): ChatDbResult<CoordinationReply> = transitionReply(proposed, CoordinationReply.State.STARTED, beforeWrite)

    /**
     * Records `workspace_coordination_reply_state=ACCEPTED_LOCAL`, replacing
     * `LocalRunStore.markWorkspaceCoordinationReplyAccepted` (`LocalRunStore.java:markWorkspaceCoordinationReplyAccepted`).
     */
    suspend fun markWorkspaceCoordinationReplyAccepted(
        proposed: CoordinationReply,
        beforeWrite: () -> Unit = {},
    ): ChatDbResult<CoordinationReply> = transitionReply(proposed, CoordinationReply.State.ACCEPTED_LOCAL, beforeWrite)

    /**
     * Reads an intent and its monotonic state, replacing
     * `LocalRunStore.readWorkspaceCoordinationReply` (`LocalRunStore.java:readWorkspaceCoordinationReply`).
     */
    suspend fun readWorkspaceCoordinationReply(
        centralSessionId: String,
        referenceId: String,
        replyKey: String? = null,
    ): ChatDbResult<CoordinationReply?> = readTransaction {
        requireCentralConversation(centralSessionId)
        CoordinationReference.requireReferenceId(referenceId)
        if (replyKey != null && !replyKey.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}")))
            return@readTransaction ChatDbResult.Invalid("invalid coordination reply key")
        val reply = findCoordinationReply(centralSessionId, referenceId)
        if (replyKey != null && reply != null && reply.replyKey != replyKey) return@readTransaction ChatDbResult.Conflict(COORDINATION_CONFLICT)
        ChatDbResult.Success(reply)
    }

    /**
     * Looks up a reply by its unique `reply_id`, replacing
     * `LocalRunStore.readWorkspaceCoordinationReplyById` (`LocalRunStore.java:readWorkspaceCoordinationReplyById`).
     */
    suspend fun readWorkspaceCoordinationReplyById(
        centralSessionId: String,
        replyId: String,
    ): ChatDbResult<CoordinationReply?> = readTransaction {
        requireCentralConversation(centralSessionId)
        if (!replyId.matches(Regex("workspace-coordination-reply-[0-9a-f]{64}")))
            return@readTransaction ChatDbResult.Invalid("invalid coordination reply id")
        val referenceId = sql.query(
            "SELECT reference_id FROM workspace_coordination_replies WHERE conversation_id=? AND reply_id=? LIMIT 1",
            arrayOf(centralSessionId, replyId),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        ChatDbResult.Success(referenceId?.let { findCoordinationReply(centralSessionId, it) })
    }

    /**
     * Appends the first `workspace_input_incorporation` observation, replacing
     * `LocalRunStore.appendWorkspaceInputIncorporationIfAbsent` (`LocalRunStore.java:appendWorkspaceInputIncorporationIfAbsent`).
     * Later checkpoints with the same binding preserve the first receipt; another binding or mailbox claim conflicts.
     */
    suspend fun appendWorkspaceInputIncorporation(
        proposed: InputIncorporation,
        beforeWrite: () -> Unit = {},
    ): ChatDbResult<InputIncorporation> = when (val result = appendWorkspaceInputIncorporationCore(proposed, beforeWrite)) {
        is ChatDbResult.Success -> ChatDbResult.Success(result.value.first)
        is ChatDbResult.Conflict -> result
        is ChatDbResult.Invalid -> result
        ChatDbResult.NotFound -> ChatDbResult.NotFound
        ChatDbResult.Tombstoned -> ChatDbResult.Tombstoned
    }

    suspend fun appendWorkspaceInputIncorporationAndReport(
        proposed: InputIncorporation,
        beforeWrite: () -> Unit = {},
    ): ChatDbResult<Boolean> = when (val result = appendWorkspaceInputIncorporationCore(proposed, beforeWrite)) {
        is ChatDbResult.Success -> ChatDbResult.Success(result.value.second)
        is ChatDbResult.Conflict -> result
        is ChatDbResult.Invalid -> result
        ChatDbResult.NotFound -> ChatDbResult.NotFound
        ChatDbResult.Tombstoned -> ChatDbResult.Tombstoned
    }

    private suspend fun appendWorkspaceInputIncorporationCore(
        proposed: InputIncorporation,
        beforeWrite: () -> Unit,
    ): ChatDbResult<Pair<InputIncorporation, Boolean>> = transaction {
        InputIncorporation.fromJsonStrict(proposed.toJson())
        requireCentralConversation(proposed.ownerCentralSessionId)
        if (proposed.sourceKind == InputIncorporation.SourceKind.REPLY) {
            requireIncorporationSource(proposed)
        } else {
            ensureExecutionConversationForWrite(proposed.executionSessionId)
        }
        val old = findInputIncorporation(proposed.ownerCentralSessionId, proposed.receiptId)
        if (old != null) {
            if (!old.sameBinding(proposed)) return@transaction ChatDbResult.Conflict(INPUT_CONFLICT)
            return@transaction ChatDbResult.Success(old to false)
        }
        val mailboxClaim = sql.query(
            "SELECT receipt_id FROM workspace_input_incorporations WHERE conversation_id=? AND mailbox_message_id=? LIMIT 1",
            arrayOf(proposed.ownerCentralSessionId, proposed.mailboxMessageId),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (mailboxClaim != null) return@transaction ChatDbResult.Conflict(INPUT_CONFLICT)
        ChatSqlBeforeWriteGuard.invoke(beforeWrite)
        if (proposed.sourceKind == InputIncorporation.SourceKind.NATIVE_CORRECT)
            ensureWritableConversation(proposed.executionSessionId, "workspace_exec")
        val seq = appendItem(proposed.ownerCentralSessionId, InputIncorporation.ROW_TYPE, proposed.receiptId)
        sql.execSQL(
            "INSERT INTO workspace_input_incorporations(conversation_id,receipt_id,seq,schema_version,source_kind,source_id,source_reference_id,mailbox_message_id,owner_central_session_id,workspace_id,execution_session_id,assignment_id,request_id,mission_id,bot_id,admission_cycle_id,first_incorporation_cycle_id,source_payload_sha256,delivered_content_sha256,transport_kind,transport_sha256,checkpoint_payload_sha256,checkpoint_saved_at_ms,recorded_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(proposed.ownerCentralSessionId, proposed.receiptId, seq, InputIncorporation.SCHEMA_VERSION,
                proposed.sourceKind.name, proposed.sourceId, proposed.sourceReferenceId, proposed.mailboxMessageId,
                proposed.ownerCentralSessionId, proposed.workspaceId, proposed.executionSessionId, proposed.assignmentId,
                proposed.requestId, proposed.missionId, proposed.botId, proposed.admissionCycleId,
                proposed.firstIncorporationCycleId, proposed.sourcePayloadSha256, proposed.deliveredContentSha256,
                proposed.transportKind, proposed.transportSha256, proposed.checkpointPayloadSha256,
                proposed.checkpointSavedAtMillis, proposed.recordedAtMillis),
        )
        ChatDbResult.Success(proposed to true)
    }

    /**
     * Reads an immutable `workspace_input_incorporation` by its derived source receipt,
     * replacing `LocalRunStore.readWorkspaceInputIncorporation` (`LocalRunStore.java:readWorkspaceInputIncorporation`).
     */
    suspend fun readWorkspaceInputIncorporation(
        centralSessionId: String,
        sourceKind: InputIncorporation.SourceKind,
        sourceId: String,
    ): ChatDbResult<InputIncorporation?> = readTransaction {
        requireCentralConversation(centralSessionId)
        val receiptId = InputIncorporation.receiptId(sourceKind, sourceId)
        val value = findInputIncorporation(centralSessionId, receiptId)
        if (value != null) requireIncorporationSource(value)
        ChatDbResult.Success(value)
    }

    /**
     * Requires an exact persisted `workspace_input_incorporation` before disclosure, replacing
     * `LocalRunStore.requireWorkspaceInputIncorporationDisclosureLive`
     * (`LocalRunStore.java:requireWorkspaceInputIncorporationDisclosureLive`).
     */
    suspend fun requireWorkspaceInputIncorporationDisclosureLive(
        value: InputIncorporation,
    ): ChatDbResult<Unit> = readTransaction {
        InputIncorporation.fromJsonStrict(value.toJson())
        requireCentralConversation(value.ownerCentralSessionId)
        val durable = findInputIncorporation(value.ownerCentralSessionId, value.receiptId)
            ?: return@readTransaction ChatDbResult.Conflict(INPUT_CONFLICT)
        if (!durable.samePayload(value)) return@readTransaction ChatDbResult.Conflict(INPUT_CONFLICT)
        requireIncorporationSource(durable)
        ChatDbResult.Success(Unit)
    }

    /**
     * Lists lexical `workspace_input_incorporation` rows, replacing
     * `LocalRunStore.listWorkspaceInputIncorporations` (`LocalRunStore.java:listWorkspaceInputIncorporations`).
     */
    suspend fun listWorkspaceInputIncorporations(
        centralSessionId: String,
        limit: Int,
        afterReceiptId: String? = null,
    ): ChatDbResult<List<InputIncorporation>> = readTransaction {
        requireCentralConversation(centralSessionId)
        requirePage(limit)
        val after = afterReceiptId?.also { InputIncorporation.requireReceiptId(it) }
        val where = if (after == null) "" else " AND receipt_id>?"
        val args = if (after == null) arrayOf<Any?>(centralSessionId, limit) else arrayOf(centralSessionId, after, limit)
        val values = mutableListOf<InputIncorporation>()
        sql.query(
            "WITH page AS (SELECT * FROM workspace_input_incorporations WHERE conversation_id=?$where ORDER BY receipt_id LIMIT ?) " +
                "SELECT i.schema_version,i.source_kind,i.source_id,i.source_reference_id,i.mailbox_message_id,i.owner_central_session_id," +
                "i.workspace_id,i.execution_session_id,i.assignment_id,i.request_id,i.mission_id,i.bot_id,i.admission_cycle_id," +
                "i.first_incorporation_cycle_id,i.source_payload_sha256,i.delivered_content_sha256,i.transport_kind,i.transport_sha256," +
                "i.checkpoint_payload_sha256,i.checkpoint_saved_at_ms,i.recorded_at_ms,i.receipt_id,ec.id,CASE WHEN et.entity_id IS NULL THEN 0 ELSE 1 END," +
                "reply.schema_version,reply.reply_id,reply.evidence_id,reply.reply_key,reply.text,reply.central_request_id," +
                "reply.original_user_request,reply.mailbox_message_id,reply.payload_sha256,reply.prepared_at_ms,reply.state,reply.state_at_ms," +
                "n.schema_version,n.evidence_id,n.content_sha256,n.owner_central_session_id,n.workspace_id,n.execution_session_id," +
                "n.assignment_id,n.request_id,n.mission_id,n.bot_id,n.cycle_id,n.message_id,n.kind,n.observed_assignment_revision,n.produced_at_ms," +
                "CASE WHEN i.source_kind!='REPLY' THEN 1 WHEN s.reference_id IS NOT NULL AND s.schema_version=n.schema_version AND " +
                "s.evidence_id=n.evidence_id AND s.content_sha256=n.content_sha256 AND s.owner_central_session_id=n.owner_central_session_id AND " +
                "s.workspace_id=n.workspace_id AND s.execution_session_id=n.execution_session_id AND s.assignment_id=n.assignment_id AND " +
                "s.request_id=n.request_id AND s.mission_id=n.mission_id AND s.bot_id=n.bot_id AND s.cycle_id=n.cycle_id AND " +
                "s.message_id=n.message_id AND s.kind=n.kind AND s.observed_assignment_revision=n.observed_assignment_revision AND " +
                "s.produced_at_ms=n.produced_at_ms THEN 1 ELSE 0 END AS source_matches," +
                "e.schema_version,e.mission_id,e.bot_id,e.cycle_id,e.message_id,e.kind,e.text,e.produced_at_ms,e.evidence_id,e.content_sha256," +
                "er.ordinal,er.ref,tr.seq,tr.schema_version,tr.state,tr.state_at_ms " +
                "FROM page i LEFT JOIN conversations ec ON ec.id=i.execution_session_id " +
                "LEFT JOIN tombstones et ON et.scope='conversation' AND et.entity_id=i.execution_session_id " +
                "LEFT JOIN workspace_coordination_replies reply ON i.source_kind='REPLY' AND reply.conversation_id=i.owner_central_session_id AND reply.reference_id=i.source_reference_id " +
                "LEFT JOIN workspace_coordination_refs n ON i.source_kind='REPLY' AND n.conversation_id=i.owner_central_session_id AND n.reference_id=i.source_reference_id AND n.is_notice=1 " +
                "LEFT JOIN workspace_coordination_refs s ON i.source_kind='REPLY' AND s.conversation_id=i.execution_session_id AND s.reference_id=i.source_reference_id AND s.is_notice=0 " +
                "LEFT JOIN crew_coordination_evidence e ON e.conversation_id=i.execution_session_id AND e.evidence_id=n.evidence_id " +
                "LEFT JOIN crew_coordination_evidence_refs er ON er.conversation_id=e.conversation_id AND er.evidence_id=e.evidence_id " +
                "LEFT JOIN workspace_coordination_reply_transitions tr ON tr.conversation_id=i.owner_central_session_id AND tr.reply_id=reply.reply_id " +
                "ORDER BY i.receipt_id,er.ordinal,tr.seq", args,
        ).use { c ->
            var activeReceiptId: String? = null
            var value: InputIncorporation? = null
            var replyRow: ReplyRow? = null
            var noticeRow: CoordinationRefRow? = null
            var evidenceRow: CoordinationEvidenceRow? = null
            var evidenceRefs = sortedMapOf<Int, String>()
            var transitions = sortedMapOf<Long, Triple<Int, String, Long>>()
            var sourceMatches = false

            fun emit() {
                val current = value ?: return
                check(!current.executionSessionId.isBlank() && WorkspaceIdentity.isExecutionSession(current.executionSessionId)) {
                    "invalid_execution_session"
                }
                if (replyRow != null || current.sourceKind == InputIncorporation.SourceKind.REPLY) {
                    val durableReply = checkNotNull(replyRow) { "Incorporation has no exact admitted reply source" }
                    val storedNotice = checkNotNull(noticeRow) { "Reply has no exact Central coordination notice" }
                    val evidenceData = checkNotNull(evidenceRow) { "Corrupt workspace_coordination_refs: execution evidence is missing" }
                    check(evidenceData.schemaVersion == CrewCoordinationLedgerEvidence.SCHEMA_VERSION) {
                        "Unsupported crew_coordination_evidence schema"
                    }
                    check(evidenceRefs.keys.withIndex().all { (index, ordinal) -> index == ordinal }) {
                        "Corrupt crew_coordination_evidence_refs ordering"
                    }
                    val evidence = CrewCoordinationLedgerEvidence.create(current.executionSessionId, evidenceData.missionId,
                        evidenceData.botId, evidenceData.cycleId, evidenceData.messageId, evidenceData.kind,
                        ExactUtf16.decode(evidenceData.text), evidenceRefs.values.toList(), evidenceData.producedAtMillis)
                    check(evidence.evidenceId == evidenceData.evidenceId && evidence.contentSha256 == evidenceData.contentSha256) {
                        "Corrupt crew_coordination_evidence payload"
                    }
                    val notice = CoordinationReference.create(evidence, storedNotice.ownerCentralSessionId, storedNotice.workspaceId,
                        storedNotice.assignmentId, storedNotice.requestId, storedNotice.revision)
                    check(storedNotice.schemaVersion == CoordinationReference.SCHEMA_VERSION && storedNotice.evidenceId == evidence.evidenceId &&
                        notice.referenceId == current.sourceReferenceId && notice.contentSha256 == storedNotice.contentSha256 &&
                        notice.messageId == storedNotice.messageId && notice.kind == storedNotice.kind &&
                        notice.missionId == storedNotice.missionId && notice.botId == storedNotice.botId &&
                        notice.cycleId == storedNotice.cycleId && notice.producedAtMillis == storedNotice.producedAtMillis && sourceMatches) {
                        "Corrupt workspace_coordination_refs payload"
                    }
                    val reply = CoordinationReply.create(notice, durableReply.replyKey, ExactUtf16.decode(durableReply.text),
                        durableReply.centralRequestId, ExactUtf16.decode(durableReply.originalRequest), durableReply.preparedAtMillis)
                        .withState(CoordinationReply.State.valueOf(durableReply.state), durableReply.stateAtMillis)
                    check(durableReply.schemaVersion == CoordinationReply.SCHEMA_VERSION && reply.replyId == durableReply.replyId &&
                        reply.reference.evidenceId == durableReply.evidenceId && reply.mailboxMessageId == durableReply.mailboxMessageId &&
                        reply.payloadSha256 == durableReply.payloadSha256) { "Corrupt workspace_coordination_replies payload" }
                    if (!current.matchesReply(reply)) error("Incorporation has no exact admitted reply source")
                    validateReplyTransitions(reply, transitions.values.toList())
                }
                values += current
            }

            while (c.moveToNext()) {
                val identity = c.getString(21)
                if (activeReceiptId != identity) {
                    emit()
                    activeReceiptId = identity
                    value = readInputIncorporation(c)
                    check(checkNotNull(value).receiptId == identity) { "Corrupt workspace_input_incorporations identity" }
                    if (!WorkspaceIdentity.isExecutionSession(checkNotNull(value).executionSessionId))
                        throw JournalAbort("invalid_execution_session")
                    if (c.isNull(22)) throw JournalAbort(if (c.getInt(23) != 0) "conversation_tombstoned" else "conversation_not_found")
                    if (c.getInt(23) != 0) throw JournalAbort("conversation_tombstoned")
                    replyRow = if (c.isNull(24)) null else ReplyRow(c, 24, includeSeq = false)
                    noticeRow = if (c.isNull(36)) null else CoordinationRefRow(c, 36)
                    evidenceRow = if (c.isNull(52)) null else CoordinationEvidenceRow(c, 52)
                    evidenceRefs = sortedMapOf()
                    transitions = sortedMapOf()
                    sourceMatches = c.getInt(51) == 1
                }
                if (!c.isNull(62)) {
                    val ordinal = c.getInt(62)
                    val ref = c.getString(63)
                    val old = evidenceRefs.putIfAbsent(ordinal, ref)
                    check(old == null || old == ref) { "Corrupt crew_coordination_evidence_refs ordering" }
                }
                if (!c.isNull(64)) {
                    val transition = Triple(c.getInt(65), c.getString(66), c.getLong(67))
                    val old = transitions.putIfAbsent(c.getLong(64), transition)
                    check(old == null || old == transition) { "Corrupt workspace_coordination_reply_state ordering" }
                }
            }
            emit()
        }
        ChatDbResult.Success(Collections.unmodifiableList(values))
    }

    private suspend fun transitionReply(
        proposed: CoordinationReply,
        target: CoordinationReply.State,
        beforeWrite: () -> Unit,
    ): ChatDbResult<CoordinationReply> = transaction {
        CoordinationReply.fromJsonStrict(proposed.toJson())
        ensureCentralConversationForWrite(proposed.reference.ownerCentralSessionId)
        val durable = findCoordinationReply(proposed.reference.ownerCentralSessionId, proposed.reference.referenceId)
            ?: return@transaction ChatDbResult.Conflict("Coordination reply intent is missing")
        if (!durable.samePayload(proposed)) return@transaction ChatDbResult.Conflict(COORDINATION_CONFLICT)
        when (target) {
            CoordinationReply.State.STARTED -> {
                if (durable.state == CoordinationReply.State.ACCEPTED_LOCAL) return@transaction ChatDbResult.Success(durable)
                if (durable.state == CoordinationReply.State.STARTED) return@transaction ChatDbResult.Conflict("Coordination reply is UNCERTAIN; dispatch must not be retried")
                if (durable.state != CoordinationReply.State.PREPARED) return@transaction ChatDbResult.Conflict(COORDINATION_CONFLICT)
            }
            CoordinationReply.State.ACCEPTED_LOCAL -> {
                if (durable.state == CoordinationReply.State.ACCEPTED_LOCAL) return@transaction ChatDbResult.Success(durable)
                if (durable.state != CoordinationReply.State.STARTED) return@transaction ChatDbResult.Conflict("Coordination acceptance requires a durable STARTED claim")
            }
            CoordinationReply.State.PREPARED -> return@transaction ChatDbResult.Invalid("PREPARED is an intent, not a transition")
        }
        requireExactCoordinationSource(durable.reference)
        ChatSqlBeforeWriteGuard.invoke(beforeWrite)
        val transitionAtMillis = maxOf(System.currentTimeMillis(), durable.stateAtMillis)
        val transitioned = durable.withState(target, transitionAtMillis)
        val seq = appendItem(durable.reference.ownerCentralSessionId, CoordinationReply.STATE_TYPE, durable.reference.referenceId)
        // The SQL trigger validates the transition against the old reply state, so insert its
        // history row before updating the intent's current state, in this same transaction.
        sql.execSQL(
            "INSERT INTO workspace_coordination_reply_transitions(conversation_id,reply_id,seq,schema_version,state,state_at_ms) VALUES(?,?,?,?,?,?)",
            arrayOf(durable.reference.ownerCentralSessionId, durable.replyId, seq, CoordinationReply.SCHEMA_VERSION, target.name, transitionAtMillis),
        )
        sql.execSQL(
            "UPDATE workspace_coordination_replies SET state=?,state_at_ms=? WHERE conversation_id=? AND reference_id=?",
            arrayOf(target.name, transitionAtMillis, durable.reference.ownerCentralSessionId, durable.reference.referenceId),
        )
        ChatDbResult.Success(transitioned)
    }

    private fun insertResultRef(conversationId: String, ref: ResultReference, notice: Boolean) {
        val type = if (notice) ResultReference.NOTICE_TYPE else ResultReference.REFERENCE_TYPE
        val seq = appendItem(conversationId, type, ref.referenceId)
        sql.execSQL(
            "INSERT INTO workspace_result_refs(conversation_id,reference_id,is_notice,schema_version,seq,evidence_id,content_sha256,owner_central_session_id,workspace_id,execution_session_id,assignment_id,request_id,mission_id,bot_id,cycle_id,observed_assignment_revision,reported_status,produced_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(conversationId, ref.referenceId, if (notice) 1 else 0, ResultReference.SCHEMA_VERSION, seq, ref.evidenceId, ref.contentSha256,
                ref.ownerCentralSessionId, ref.workspaceId, ref.executionSessionId, ref.assignmentId, ref.requestId,
                ref.missionId, ref.botId, ref.cycleId, ref.observedAssignmentRevision, ref.reportedStatus, ref.producedAtMillis),
        )
    }

    private fun insertCoordinationRef(conversationId: String, ref: CoordinationReference, notice: Boolean) {
        val type = if (notice) CoordinationReference.NOTICE_TYPE else CoordinationReference.REFERENCE_TYPE
        val seq = appendItem(conversationId, type, ref.referenceId)
        sql.execSQL(
            "INSERT INTO workspace_coordination_refs(conversation_id,reference_id,is_notice,schema_version,seq,evidence_id,content_sha256,owner_central_session_id,workspace_id,execution_session_id,assignment_id,request_id,mission_id,bot_id,cycle_id,message_id,kind,observed_assignment_revision,reported_status,produced_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(conversationId, ref.referenceId, if (notice) 1 else 0, CoordinationReference.SCHEMA_VERSION, seq, ref.evidenceId, ref.contentSha256,
                ref.ownerCentralSessionId, ref.workspaceId, ref.executionSessionId, ref.assignmentId, ref.requestId,
                ref.missionId, ref.botId, ref.cycleId, ref.messageId, ref.kind, ref.observedAssignmentRevision,
                null, ref.producedAtMillis),
        )
    }

    private fun findCrewResult(conversationId: String, evidenceId: String): CrewResultLedgerEvidence? = sql.query(
        "SELECT schema_version,mission_id,bot_id,cycle_id,reported_status,result_text,produced_at_ms,evidence_id,content_sha256 FROM crew_result_evidence WHERE conversation_id=? AND evidence_id=?",
        arrayOf(conversationId, evidenceId),
    ).use { c ->
        if (!c.moveToFirst()) null else {
            val schemaVersion = c.getInt(0)
            val value = CrewResultLedgerEvidence.create(conversationId, c.getString(1), c.getString(2), c.getString(3),
                c.getString(4), ExactUtf16.decode(c.getBlob(5)), c.getLong(6))
            check(schemaVersion == CrewResultLedgerEvidence.SCHEMA_VERSION && value.evidenceId == c.getString(7) &&
                value.contentSha256 == c.getString(8)) { "Corrupt crew_result_evidence payload" }
            value
        }
    }

    private fun findResultRef(conversationId: String, referenceId: String, notice: Boolean): ResultReference? {
        val row = sql.query(
            "SELECT schema_version,evidence_id,content_sha256,owner_central_session_id,workspace_id,execution_session_id,assignment_id,request_id,mission_id,bot_id,cycle_id,observed_assignment_revision,reported_status,produced_at_ms FROM workspace_result_refs WHERE conversation_id=? AND reference_id=? AND is_notice=?",
        arrayOf(conversationId, referenceId, if (notice) 1 else 0),
        ).use { c -> if (!c.moveToFirst()) null else ResultRefRow(c) } ?: return null
        val evidence = findCrewResult(row.executionSessionId, row.evidenceId)
            ?: error("Corrupt workspace_result_refs: execution evidence is missing")
        val ref = ResultReference.create(evidence, row.ownerCentralSessionId, row.workspaceId, row.assignmentId, row.requestId, row.revision)
        check(row.schemaVersion == ResultReference.SCHEMA_VERSION &&
            (if (notice) conversationId == row.ownerCentralSessionId else conversationId == row.executionSessionId) &&
            ref.referenceId == referenceId && ref.contentSha256 == row.contentSha256 && ref.missionId == row.missionId &&
            ref.botId == row.botId && ref.cycleId == row.cycleId && ref.reportedStatus == row.status &&
            ref.producedAtMillis == row.producedAtMillis) { "Corrupt workspace_result_refs payload" }
        if (notice) {
            val source = findResultRef(row.executionSessionId, referenceId, false)
            check(source != null && source.samePayload(ref)) { "Central result notice has no exact execution reference" }
        }
        return ref
    }

    private fun findCrewCoordinationEvidence(conversationId: String, evidenceId: String): CrewCoordinationLedgerEvidence? {
        val row = sql.query(
            "SELECT schema_version,mission_id,bot_id,cycle_id,message_id,kind,text,produced_at_ms,evidence_id,content_sha256 FROM crew_coordination_evidence WHERE conversation_id=? AND evidence_id=?",
            arrayOf(conversationId, evidenceId),
        ).use { c -> if (!c.moveToFirst()) null else CoordinationEvidenceRow(c) } ?: return null
        val refs = sql.query(
            "SELECT ordinal,ref FROM crew_coordination_evidence_refs WHERE conversation_id=? AND evidence_id=? ORDER BY ordinal",
            arrayOf(conversationId, evidenceId),
        ).use { c -> buildList { while (c.moveToNext()) {
            check(c.getInt(0) == size) { "Corrupt crew_coordination_evidence_refs ordering" }
            add(c.getString(1))
        } } }
        check(row.schemaVersion == CrewCoordinationLedgerEvidence.SCHEMA_VERSION) { "Unsupported crew_coordination_evidence schema" }
        val value = CrewCoordinationLedgerEvidence.create(conversationId, row.missionId, row.botId, row.cycleId,
            row.messageId, row.kind, ExactUtf16.decode(row.text), refs, row.producedAtMillis)
        check(value.evidenceId == row.evidenceId && value.contentSha256 == row.contentSha256) { "Corrupt crew_coordination_evidence payload" }
        return value
    }

    private fun findCoordinationRef(conversationId: String, referenceId: String, notice: Boolean): CoordinationReference? {
        val row = sql.query(
            "SELECT schema_version,evidence_id,content_sha256,owner_central_session_id,workspace_id,execution_session_id,assignment_id,request_id,mission_id,bot_id,cycle_id,message_id,kind,observed_assignment_revision,produced_at_ms FROM workspace_coordination_refs WHERE conversation_id=? AND reference_id=? AND is_notice=?",
        arrayOf(conversationId, referenceId, if (notice) 1 else 0),
        ).use { c -> if (!c.moveToFirst()) null else CoordinationRefRow(c) } ?: return null
        val evidence = findCrewCoordinationEvidence(row.executionSessionId, row.evidenceId)
            ?: error("Corrupt workspace_coordination_refs: execution evidence is missing")
        val ref = CoordinationReference.create(evidence, row.ownerCentralSessionId, row.workspaceId, row.assignmentId, row.requestId, row.revision)
        check(row.schemaVersion == CoordinationReference.SCHEMA_VERSION &&
            (if (notice) conversationId == row.ownerCentralSessionId else conversationId == row.executionSessionId) &&
            ref.referenceId == referenceId && ref.contentSha256 == row.contentSha256 && ref.messageId == row.messageId &&
            ref.kind == row.kind && ref.missionId == row.missionId && ref.botId == row.botId && ref.cycleId == row.cycleId &&
            ref.producedAtMillis == row.producedAtMillis) { "Corrupt workspace_coordination_refs payload" }
        if (notice) {
            val source = findCoordinationRef(row.executionSessionId, referenceId, false)
            check(source != null && source.samePayload(ref)) { "Central coordination notice has no exact execution reference" }
        }
        return ref
    }

    private fun findCoordinationReply(central: String, referenceId: String): CoordinationReply? {
        val highWater = sql.query(
            "SELECT COALESCE(next_seq-1,0) FROM conversations WHERE id=?",
            arrayOf(central),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else return null }
        val row = sql.query(
            "SELECT schema_version,reply_id,evidence_id,reply_key,text,central_request_id,original_user_request,mailbox_message_id,payload_sha256,prepared_at_ms,state,state_at_ms,seq FROM workspace_coordination_replies WHERE conversation_id=? AND reference_id=? AND seq<=?",
            arrayOf(central, referenceId, highWater),
        ).use { c -> if (!c.moveToFirst()) null else ReplyRow(c) } ?: return null
        val transitions = readReplyTransitions(central, row.replyId, highWater)
        val (stateAtHighWater, stateTimeAtHighWater) = resolveReplyState(row.preparedAtMillis, transitions)
        val notice = findCoordinationRef(central, referenceId, true)
            ?: error("Corrupt workspace_coordination_replies: notice is missing")
        val reply = CoordinationReply.create(notice, row.replyKey, ExactUtf16.decode(row.text), row.centralRequestId,
            ExactUtf16.decode(row.originalRequest), row.preparedAtMillis)
            .withState(stateAtHighWater, stateTimeAtHighWater)
        check(row.schemaVersion == CoordinationReply.SCHEMA_VERSION && reply.replyId == row.replyId && reply.reference.evidenceId == row.evidenceId &&
            reply.mailboxMessageId == row.mailboxMessageId && reply.payloadSha256 == row.payloadSha256) {
            "Corrupt workspace_coordination_replies payload"
        }
        validateReplyTransitions(reply, transitions)
        return reply
    }

    private fun readReplyTransitions(central: String, replyId: String, highWater: Long): List<Triple<Int, String, Long>> = sql.query(
            "SELECT schema_version,state,state_at_ms FROM workspace_coordination_reply_transitions WHERE conversation_id=? AND reply_id=? AND seq<=? ORDER BY seq",
            arrayOf(central, replyId, highWater),
        ).use { c -> buildList { while (c.moveToNext()) {
            add(Triple(c.getInt(0), c.getString(1), c.getLong(2)))
        } } }

    private fun validateReplyTransitions(reply: CoordinationReply, states: List<Triple<Int, String, Long>>) {
        val (state, at) = resolveReplyState(reply.preparedAtMillis, states)
        check(state == reply.state && at == reply.stateAtMillis) { "Reply state differs from its transition history" }
    }

    private fun resolveReplyState(
        preparedAtMillis: Long,
        states: List<Triple<Int, String, Long>>,
    ): Pair<CoordinationReply.State, Long> {
        var state = CoordinationReply.State.PREPARED
        var at = preparedAtMillis
        for ((schemaVersion, name, nextAt) in states) {
            check(schemaVersion == CoordinationReply.SCHEMA_VERSION) { "Unsupported workspace_coordination_reply_state schema" }
            val next = CoordinationReply.State.valueOf(name)
            check((state == CoordinationReply.State.PREPARED && next == CoordinationReply.State.STARTED) ||
                (state == CoordinationReply.State.STARTED && next == CoordinationReply.State.ACCEPTED_LOCAL)) {
                "Corrupt workspace_coordination_reply_state ordering"
            }
            check(nextAt >= at) { "Corrupt workspace_coordination_reply_state timestamp" }
            state = next
            at = nextAt
        }
        return state to at
    }

    private fun findInputIncorporation(central: String, receiptId: String): InputIncorporation? = sql.query(
        "SELECT schema_version,source_kind,source_id,source_reference_id,mailbox_message_id,owner_central_session_id,workspace_id,execution_session_id,assignment_id,request_id,mission_id,bot_id,admission_cycle_id,first_incorporation_cycle_id,source_payload_sha256,delivered_content_sha256,transport_kind,transport_sha256,checkpoint_payload_sha256,checkpoint_saved_at_ms,recorded_at_ms FROM workspace_input_incorporations WHERE conversation_id=? AND receipt_id=?",
        arrayOf(central, receiptId),
    ).use { c ->
        if (!c.moveToFirst()) null else readInputIncorporation(c).also {
            check(it.receiptId == receiptId) { "Corrupt workspace_input_incorporations identity" }
        }
    }

    private fun readInputIncorporation(c: Cursor, offset: Int = 0): InputIncorporation {
        val schemaVersion = c.getInt(offset)
        val sourceReferenceId = if (c.isNull(offset + 3)) {
            throw IllegalStateException("Corrupt workspace_input_incorporations row: source_reference_id is null")
        } else c.getString(offset + 3)
        val value = InputIncorporation.create(InputIncorporation.SourceKind.valueOf(c.getString(offset + 1)), c.getString(offset + 2),
            sourceReferenceId, c.getString(offset + 4), c.getString(offset + 5), c.getString(offset + 6), c.getString(offset + 7),
            c.getString(offset + 8), c.getString(offset + 9), c.getString(offset + 10), c.getString(offset + 11), c.getString(offset + 12),
            c.getString(offset + 13), c.getString(offset + 14), c.getString(offset + 15), c.getString(offset + 16),
            c.getString(offset + 17), c.getString(offset + 18), c.getLong(offset + 19), c.getLong(offset + 20))
        check(schemaVersion == InputIncorporation.SCHEMA_VERSION) { "Corrupt workspace_input_incorporations schema" }
        return value
    }

    private fun requireExactCoordinationSource(reference: CoordinationReference) {
        requireExecutionConversation(reference.executionSessionId)
        val source = findCoordinationRef(reference.executionSessionId, reference.referenceId, false)
        val notice = findCoordinationRef(reference.ownerCentralSessionId, reference.referenceId, true)
        check(source != null && notice != null && source.samePayload(reference) && notice.samePayload(reference)) {
            "Reply requires an exact durable source reference and Central notice"
        }
        val evidence = findCrewCoordinationEvidence(reference.executionSessionId, reference.evidenceId)
        check(reference.matchesEvidence(evidence)) { "Coordination reference has no matching execution evidence" }
    }

    private fun requireIncorporationSource(value: InputIncorporation) {
        // Native CORRECT input is authoritative in its own store, but the owning execution
        // conversation must remain present and live. Replies must be exact, locally accepted.
        requireExecutionConversation(value.executionSessionId)
        if (value.sourceKind == InputIncorporation.SourceKind.REPLY) {
            val referenceId = value.sourceReferenceId ?: error("Reply incorporation has no source reference id")
            val reply = findCoordinationReply(value.ownerCentralSessionId, referenceId)
            check(value.matchesReply(reply)) { "Incorporation has no exact admitted reply source" }
            requireExactCoordinationSource(reply!!.reference)
        }
    }

    private fun appendItem(conversationId: String, kind: String, key: String): Long {
        ensureWritableConversation(conversationId, conversationKindForSession(conversationId))
        val current = sql.query("SELECT next_seq FROM conversations WHERE id=?", arrayOf(conversationId)).use { c ->
            if (!c.moveToFirst()) throw JournalAbort("conversation_not_found")
            c.getLong(0)
        }
        val changed = sql.compileStatement("UPDATE conversations SET next_seq=?,updated_at=? WHERE id=? AND next_seq=?").run {
            bindLong(1, current + 1L); bindLong(2, System.currentTimeMillis()); bindString(3, conversationId); bindLong(4, current); executeUpdateDelete()
        }
        if (changed != 1) throw JournalAbort("sequence_conflict")
        // LRS timeline projection admits `workspace_result_notice`; anchor it like other
        // non-message timeline rows so turn paging keeps the notice with the latest user turn.
        val turnAnchor = if (kind == ResultReference.NOTICE_TYPE) sql.query(
            "SELECT seq FROM messages WHERE conversation_id=? AND role='user' AND interrupt_request_id IS NULL ORDER BY seq DESC LIMIT 1",
            arrayOf(conversationId),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null } else null
        sql.execSQL(
            "INSERT INTO conversation_items(conversation_id,seq,item_kind,item_key,sort_seq,sort_sub,turn_anchor_seq,created_at) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(conversationId, current, kind, key, current, 0, turnAnchor, System.currentTimeMillis() / 1000.0),
        )
        return current
    }

    private fun requireExecutionConversation(id: String) {
        if (!WorkspaceIdentity.isExecutionSession(id)) throw JournalAbort("invalid_execution_session")
        requireWritableConversation(id)
    }

    private fun ensureExecutionConversationForWrite(id: String) {
        if (!WorkspaceIdentity.isExecutionSession(id)) throw JournalAbort("invalid_execution_session")
        if (isTombstoned(id)) throw JournalAbort("conversation_tombstoned")
    }

    private fun requireCentralConversation(id: String) {
        if (id != WorkspaceIdentity.CENTRAL_SESSION) throw JournalAbort("invalid_central_session")
        requireWritableConversation(id)
    }

    private fun ensureCentralConversationForWrite(id: String) {
        if (id != WorkspaceIdentity.CENTRAL_SESSION) throw JournalAbort("invalid_central_session")
        if (isTombstoned(id)) throw JournalAbort("conversation_tombstoned")
    }

    private fun ensureWritableConversation(id: String, kind: String) {
        if (isTombstoned(id)) throw JournalAbort("conversation_tombstoned")
        val exists = sql.query("SELECT 1 FROM conversations WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
        if (exists) return
        val now = System.currentTimeMillis()
        sql.execSQL(
            "INSERT INTO conversations(id,kind,pinned,archived,created_at,updated_at,message_count,next_seq) " +
                "SELECT ?,?,0,0,?,?,0,1 WHERE NOT EXISTS(SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=?)",
            arrayOf(id, kind, now, now, id),
        )
        if (!sql.query("SELECT 1 FROM conversations WHERE id=?", arrayOf(id)).use { it.moveToFirst() })
            throw JournalAbort(if (isTombstoned(id)) "conversation_tombstoned" else "conversation_not_found")
    }

    private fun requireWritableConversation(id: String) {
        val exists = sql.query("SELECT 1 FROM conversations WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
        if (!exists) throw JournalAbort(if (isTombstoned(id)) "conversation_tombstoned" else "conversation_not_found")
        if (isTombstoned(id)) throw JournalAbort("conversation_tombstoned")
    }

    private fun isTombstoned(id: String): Boolean = sql.query(
        "SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=?", arrayOf(id),
    ).use { it.moveToFirst() }

    private fun requirePage(limit: Int) {
        if (limit !in 1..100) throw JournalAbort("invalid_page_limit")
    }

    private suspend fun <T> transaction(block: suspend () -> ChatDbResult<T>): ChatDbResult<T> = try {
        database.withChatWriteTransaction { block() }
    } catch (abort: JournalAbort) {
        when (abort.reason) {
            "conversation_not_found" -> ChatDbResult.NotFound
            "conversation_tombstoned" -> ChatDbResult.Tombstoned
            "invalid_page_limit" -> ChatDbResult.Invalid("page limit must be 1..100")
            "invalid_execution_session" -> ChatDbResult.Invalid("canonical workspace execution session required")
            "invalid_central_session" -> ChatDbResult.Invalid("canonical Central owner required")
            "sequence_conflict" -> ChatDbResult.Conflict("conversation sequence changed")
            else -> ChatDbResult.Invalid(abort.reason)
        }
    } catch (invalid: IllegalArgumentException) {
        ChatDbResult.Invalid(invalid.message ?: "invalid strict journal row")
    } catch (constraint: SQLiteConstraintException) {
        ChatDbResult.Conflict(constraint.message ?: "strict journal constraint conflict")
    }

    private suspend fun <T> readTransaction(block: suspend () -> ChatDbResult<T>): ChatDbResult<T> = try {
        JarvysChatDatabaseProvider.onQueryExecutor(database) {
            database.withChatReadTransaction { block() }
        }
    } catch (abort: JournalAbort) {
        when (abort.reason) {
            "conversation_not_found" -> ChatDbResult.NotFound
            "conversation_tombstoned" -> ChatDbResult.Tombstoned
            "invalid_page_limit" -> ChatDbResult.Invalid("page limit must be 1..100")
            "invalid_execution_session" -> ChatDbResult.Invalid("canonical workspace execution session required")
            "invalid_central_session" -> ChatDbResult.Invalid("canonical Central owner required")
            else -> ChatDbResult.Invalid(abort.reason)
        }
    } catch (invalid: IllegalArgumentException) {
        ChatDbResult.Invalid(invalid.message ?: "invalid strict journal row")
    } catch (constraint: SQLiteConstraintException) {
        ChatDbResult.Conflict(constraint.message ?: "strict journal constraint conflict")
    }

    private data class ResultRefRow(
        val schemaVersion: Int,
        val evidenceId: String,
        val contentSha256: String,
        val ownerCentralSessionId: String,
        val workspaceId: String,
        val executionSessionId: String,
        val assignmentId: String,
        val requestId: String,
        val missionId: String,
        val botId: String,
        val cycleId: String,
        val revision: Long,
        val status: String,
        val producedAtMillis: Long,
    ) {
        constructor(c: Cursor) : this(c.getInt(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5),
            c.getString(6), c.getString(7), c.getString(8), c.getString(9), c.getString(10), c.getLong(11), c.getString(12), c.getLong(13))
    }

    private data class CoordinationEvidenceRow(
        val schemaVersion: Int,
        val missionId: String, val botId: String, val cycleId: String, val messageId: String, val kind: String,
        val text: ByteArray, val producedAtMillis: Long, val evidenceId: String, val contentSha256: String,
    ) {
        constructor(c: Cursor, offset: Int = 0) : this(c.getInt(offset), c.getString(offset + 1), c.getString(offset + 2),
            c.getString(offset + 3), c.getString(offset + 4), c.getString(offset + 5), c.getBlob(offset + 6), c.getLong(offset + 7),
            c.getString(offset + 8), c.getString(offset + 9))
    }

    private data class CoordinationRefRow(
        val schemaVersion: Int,
        val evidenceId: String, val contentSha256: String, val ownerCentralSessionId: String, val workspaceId: String,
        val executionSessionId: String, val assignmentId: String, val requestId: String, val missionId: String,
        val botId: String, val cycleId: String, val messageId: String, val kind: String, val revision: Long,
        val producedAtMillis: Long,
    ) {
        constructor(c: Cursor, offset: Int = 0) : this(c.getInt(offset), c.getString(offset + 1), c.getString(offset + 2),
            c.getString(offset + 3), c.getString(offset + 4), c.getString(offset + 5), c.getString(offset + 6), c.getString(offset + 7),
            c.getString(offset + 8), c.getString(offset + 9), c.getString(offset + 10), c.getString(offset + 11), c.getString(offset + 12),
            c.getLong(offset + 13), c.getLong(offset + 14))
    }

    private data class ReplyRow(
        val schemaVersion: Int,
        val replyId: String, val evidenceId: String, val replyKey: String, val text: ByteArray,
        val centralRequestId: String, val originalRequest: ByteArray, val mailboxMessageId: String,
        val payloadSha256: String, val preparedAtMillis: Long, val state: String, val stateAtMillis: Long,
        val seq: Long,
    ) {
        constructor(c: Cursor, offset: Int = 0, includeSeq: Boolean = true) : this(c.getInt(offset), c.getString(offset + 1), c.getString(offset + 2),
            c.getString(offset + 3), c.getBlob(offset + 4), c.getString(offset + 5), c.getBlob(offset + 6), c.getString(offset + 7),
            c.getString(offset + 8), c.getLong(offset + 9), c.getString(offset + 10), c.getLong(offset + 11),
            if (includeSeq) c.getLong(offset + 12) else 0L)
    }

    private class JournalAbort(val reason: String) : RuntimeException(reason)

    private companion object {
        val TERMINAL_RESULT_STATES = setOf("DONE", "PARTIAL", "FAILED", "STOPPED")
        const val RESULT_CONFLICT = "Result identity already has a different payload"
        const val COORDINATION_CONFLICT = "Coordination identity already has a different payload"
        const val INPUT_CONFLICT = "Input source already has a different immutable incorporation claim"

        fun sameResult(a: CrewResultLedgerEvidence, b: CrewResultLedgerEvidence): Boolean =
            a.evidenceId == b.evidenceId && a.contentSha256 == b.contentSha256 && a.conversationId == b.conversationId &&
                a.missionId == b.missionId && a.botId == b.botId && a.cycleId == b.cycleId &&
                a.reportedStatus == b.reportedStatus && a.resultText == b.resultText && a.producedAtMillis == b.producedAtMillis

        fun sameCoordination(a: CrewCoordinationLedgerEvidence, b: CrewCoordinationLedgerEvidence): Boolean =
            a.evidenceId == b.evidenceId && a.contentSha256 == b.contentSha256 && a.conversationId == b.conversationId &&
                a.missionId == b.missionId && a.botId == b.botId && a.cycleId == b.cycleId &&
                a.messageId == b.messageId && a.kind == b.kind && a.text == b.text && a.refs == b.refs &&
                a.producedAtMillis == b.producedAtMillis

    }
}
