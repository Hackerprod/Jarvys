package com.jarvys.agent.chatdb

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.jarvys.agent.crew.CrewCoordinationEvidence as CrewCoordinationLedgerEvidence
import com.jarvys.agent.crew.CrewResultEvidence as CrewResultLedgerEvidence
import com.jarvys.agent.workspaces.WorkspaceCoordinationReference as CoordinationReference
import com.jarvys.agent.workspaces.WorkspaceCoordinationReply as CoordinationReply
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceInputIncorporation as InputIncorporation
import com.jarvys.agent.workspaces.WorkspaceResultReference as ResultReference

/**
 * Normalized persistence for the fail-closed workspace and crew journals. It validates the same
 * source bindings as LocalRunStore's strict whole-ledger scan while keeping ledger identities
 * conversation-scoped in SQLite. Calls are synchronous DB work inside Room transactions and must
 * be made off the main thread, like ConversationRepository.
 */
internal class StrictJournalRepository(private val database: JarvysChatDatabase) {
    private val sql get() = database.openHelper.writableDatabase

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
        requireExecutionConversation(evidence.conversationId)
        val old = findCrewResult(evidence.conversationId, evidence.evidenceId)
        if (old != null) {
            if (!sameResult(old, evidence)) return@transaction ChatDbResult.Conflict(RESULT_CONFLICT)
            return@transaction ChatDbResult.Success(old)
        }
        beforeWrite()
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
    ): ChatDbResult<CrewResultLedgerEvidence?> = transaction {
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
    ): ChatDbResult<List<String>> = transaction {
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
        requireExecutionConversation(reference.executionSessionId)
        if (reference.ownerCentralSessionId != WorkspaceIdentity.CENTRAL_SESSION) return@transaction ChatDbResult.Invalid("non-canonical Central owner")
        val evidence = findCrewResult(reference.executionSessionId, reference.evidenceId)
            ?: return@transaction ChatDbResult.Conflict("Reference has no matching durable evidence")
        if (!reference.matchesEvidence(evidence)) return@transaction ChatDbResult.Conflict("Reference has no matching durable evidence")
        val old = findResultRef(reference.executionSessionId, reference.referenceId, false)
        if (old != null) {
            if (!old.samePayload(reference)) return@transaction ChatDbResult.Conflict(RESULT_CONFLICT)
            return@transaction ChatDbResult.Success(old)
        }
        beforeWrite()
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
        requireCentralConversation(reference.ownerCentralSessionId)
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
        beforeWrite()
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
    ): ChatDbResult<ResultReference?> = transaction {
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
    ): ChatDbResult<List<ResultReference>> = transaction {
        if (isNotice) requireCentralConversation(conversationId) else requireExecutionConversation(conversationId)
        requirePage(limit)
        val after = afterReferenceId?.also { ResultReference.requireReferenceId(it) }
        val where = if (after == null) "" else " AND reference_id>?"
        val noticeFlag = if (isNotice) 1 else 0
        val args = if (after == null) arrayOf<Any?>(conversationId, noticeFlag, limit)
            else arrayOf(conversationId, noticeFlag, after, limit)
        val ids = sql.query(
            "SELECT reference_id FROM workspace_result_refs WHERE conversation_id=? AND is_notice=?$where ORDER BY reference_id LIMIT ?", args,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        val values = ids.mapNotNull { findResultRef(conversationId, it, isNotice) }
        ChatDbResult.Success(values)
    }

    /**
     * Revalidates `workspace_result_reference` and optional Central `workspace_result_notice`
     * before disclosure, replacing `LocalRunStore.requireWorkspaceResultDisclosureLive`
     * (`LocalRunStore.java:requireWorkspaceResultDisclosureLive`).
     */
    suspend fun requireWorkspaceResultDisclosureLive(
        reference: ResultReference,
        noticeRequired: Boolean,
    ): ChatDbResult<Unit> = transaction {
        ResultReference.fromJsonStrict(reference.toJson())
        requireExecutionConversation(reference.executionSessionId)
        val source = findResultRef(reference.executionSessionId, reference.referenceId, false)
            ?: return@transaction ChatDbResult.Conflict("durable execution reference is unavailable")
        if (!source.samePayload(reference)) return@transaction ChatDbResult.Conflict("durable execution reference differs")
        if (isTombstoned(reference.ownerCentralSessionId)) return@transaction ChatDbResult.Tombstoned
        val centralExists = sql.query("SELECT 1 FROM conversations WHERE id=?", arrayOf(reference.ownerCentralSessionId)).use { it.moveToFirst() }
        if (noticeRequired && !centralExists) return@transaction ChatDbResult.Conflict("Central receipt is unavailable")
        if (centralExists) {
            val notice = findResultRef(reference.ownerCentralSessionId, reference.referenceId, true)
            if (notice != null && !notice.samePayload(reference)) return@transaction ChatDbResult.Conflict("Central receipt differs")
            if (noticeRequired && notice == null) return@transaction ChatDbResult.Conflict("Central receipt is unavailable")
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
        requireExecutionConversation(evidence.conversationId)
        val old = findCrewCoordinationEvidence(evidence.conversationId, evidence.evidenceId)
        if (old != null) {
            if (!sameCoordination(old, evidence)) return@transaction ChatDbResult.Conflict(COORDINATION_CONFLICT)
            return@transaction ChatDbResult.Success(old)
        }
        beforeWrite()
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
    ): ChatDbResult<CrewCoordinationLedgerEvidence?> = transaction {
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
    ): ChatDbResult<List<String>> = transaction {
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
        requireExecutionConversation(reference.executionSessionId)
        if (reference.ownerCentralSessionId != WorkspaceIdentity.CENTRAL_SESSION) return@transaction ChatDbResult.Invalid("non-canonical Central owner")
        val evidence = findCrewCoordinationEvidence(reference.executionSessionId, reference.evidenceId)
            ?: return@transaction ChatDbResult.Conflict("Coordination reference has no matching durable evidence")
        if (!reference.matchesEvidence(evidence)) return@transaction ChatDbResult.Conflict("Coordination reference has no matching durable evidence")
        val old = findCoordinationRef(reference.executionSessionId, reference.referenceId, false)
        if (old != null) {
            if (!old.samePayload(reference)) return@transaction ChatDbResult.Conflict(COORDINATION_CONFLICT)
            return@transaction ChatDbResult.Success(old)
        }
        beforeWrite()
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
        requireCentralConversation(reference.ownerCentralSessionId)
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
        beforeWrite()
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
    ): ChatDbResult<CoordinationReference?> = transaction {
        if (isNotice) requireCentralConversation(conversationId) else requireExecutionConversation(conversationId)
        CoordinationReference.requireReferenceId(referenceId)
        ChatDbResult.Success(findCoordinationRef(conversationId, referenceId, isNotice))
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
    ): ChatDbResult<List<CoordinationReference>> = transaction {
        if (isNotice) requireCentralConversation(conversationId) else requireExecutionConversation(conversationId)
        requirePage(limit)
        val after = afterReferenceId?.also { CoordinationReference.requireReferenceId(it) }
        val where = if (after == null) "" else " AND reference_id>?"
        val noticeFlag = if (isNotice) 1 else 0
        val args = if (after == null) arrayOf<Any?>(conversationId, noticeFlag, limit)
            else arrayOf(conversationId, noticeFlag, after, limit)
        val ids = sql.query(
            "SELECT reference_id FROM workspace_coordination_refs WHERE conversation_id=? AND is_notice=?$where ORDER BY reference_id LIMIT ?", args,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        ChatDbResult.Success(ids.mapNotNull { findCoordinationRef(conversationId, it, isNotice) })
    }

    /**
     * Revalidates source `workspace_coordination_reference` and optional Central notice before
     * disclosure, replacing `LocalRunStore.requireWorkspaceCoordinationDisclosureLive`
     * (`LocalRunStore.java:requireWorkspaceCoordinationDisclosureLive`).
     */
    suspend fun requireWorkspaceCoordinationDisclosureLive(
        reference: CoordinationReference,
        noticeRequired: Boolean,
    ): ChatDbResult<Unit> = transaction {
        CoordinationReference.fromJsonStrict(reference.toJson())
        requireExecutionConversation(reference.executionSessionId)
        val source = findCoordinationRef(reference.executionSessionId, reference.referenceId, false)
            ?: return@transaction ChatDbResult.Conflict("durable execution coordination reference is unavailable")
        if (!source.samePayload(reference)) return@transaction ChatDbResult.Conflict("durable execution coordination reference differs")
        if (isTombstoned(reference.ownerCentralSessionId)) return@transaction ChatDbResult.Tombstoned
        val centralExists = sql.query("SELECT 1 FROM conversations WHERE id=?", arrayOf(reference.ownerCentralSessionId)).use { it.moveToFirst() }
        if (noticeRequired && !centralExists) return@transaction ChatDbResult.Conflict("Central coordination receipt is unavailable")
        if (centralExists) {
            val notice = findCoordinationRef(reference.ownerCentralSessionId, reference.referenceId, true)
            if (notice != null && !notice.samePayload(reference)) return@transaction ChatDbResult.Conflict("Central coordination receipt differs")
            if (noticeRequired && notice == null) return@transaction ChatDbResult.Conflict("Central coordination receipt is unavailable")
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
        requireCentralConversation(proposed.reference.ownerCentralSessionId)
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
        beforeWrite()
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
    ): ChatDbResult<CoordinationReply?> = transaction {
        requireCentralConversation(centralSessionId)
        CoordinationReference.requireReferenceId(referenceId)
        if (replyKey != null && !replyKey.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}")))
            return@transaction ChatDbResult.Invalid("invalid coordination reply key")
        val reply = findCoordinationReply(centralSessionId, referenceId)
        if (replyKey != null && reply != null && reply.replyKey != replyKey) return@transaction ChatDbResult.Conflict(COORDINATION_CONFLICT)
        ChatDbResult.Success(reply)
    }

    /**
     * Looks up a reply by its unique `reply_id`, replacing
     * `LocalRunStore.readWorkspaceCoordinationReplyById` (`LocalRunStore.java:readWorkspaceCoordinationReplyById`).
     */
    suspend fun readWorkspaceCoordinationReplyById(
        centralSessionId: String,
        replyId: String,
    ): ChatDbResult<CoordinationReply?> = transaction {
        requireCentralConversation(centralSessionId)
        if (!replyId.matches(Regex("workspace-coordination-reply-[0-9a-f]{64}")))
            return@transaction ChatDbResult.Invalid("invalid coordination reply id")
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
    ): ChatDbResult<InputIncorporation> = transaction {
        InputIncorporation.fromJsonStrict(proposed.toJson())
        requireCentralConversation(proposed.ownerCentralSessionId)
        requireIncorporationSource(proposed)
        val old = findInputIncorporation(proposed.ownerCentralSessionId, proposed.receiptId)
        if (old != null) {
            if (!old.sameBinding(proposed)) return@transaction ChatDbResult.Conflict(INPUT_CONFLICT)
            return@transaction ChatDbResult.Success(old)
        }
        val mailboxClaim = sql.query(
            "SELECT receipt_id FROM workspace_input_incorporations WHERE conversation_id=? AND mailbox_message_id=? LIMIT 1",
            arrayOf(proposed.ownerCentralSessionId, proposed.mailboxMessageId),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (mailboxClaim != null) return@transaction ChatDbResult.Conflict(INPUT_CONFLICT)
        beforeWrite()
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
        ChatDbResult.Success(proposed)
    }

    /**
     * Reads an immutable `workspace_input_incorporation` by its derived source receipt,
     * replacing `LocalRunStore.readWorkspaceInputIncorporation` (`LocalRunStore.java:readWorkspaceInputIncorporation`).
     */
    suspend fun readWorkspaceInputIncorporation(
        centralSessionId: String,
        sourceKind: InputIncorporation.SourceKind,
        sourceId: String,
    ): ChatDbResult<InputIncorporation?> = transaction {
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
    ): ChatDbResult<Unit> = transaction {
        InputIncorporation.fromJsonStrict(value.toJson())
        requireCentralConversation(value.ownerCentralSessionId)
        val durable = findInputIncorporation(value.ownerCentralSessionId, value.receiptId)
            ?: return@transaction ChatDbResult.Conflict(INPUT_CONFLICT)
        if (!durable.samePayload(value)) return@transaction ChatDbResult.Conflict(INPUT_CONFLICT)
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
    ): ChatDbResult<List<InputIncorporation>> = transaction {
        requireCentralConversation(centralSessionId)
        requirePage(limit)
        val after = afterReceiptId?.also { InputIncorporation.requireReceiptId(it) }
        val where = if (after == null) "" else " AND receipt_id>?"
        val args = if (after == null) arrayOf<Any?>(centralSessionId, limit) else arrayOf(centralSessionId, after, limit)
        val ids = sql.query(
            "SELECT receipt_id FROM workspace_input_incorporations WHERE conversation_id=?$where ORDER BY receipt_id LIMIT ?", args,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        val values = ids.mapNotNull { findInputIncorporation(centralSessionId, it) }
        values.forEach(::requireIncorporationSource)
        ChatDbResult.Success(values)
    }

    private suspend fun transitionReply(
        proposed: CoordinationReply,
        target: CoordinationReply.State,
        beforeWrite: () -> Unit,
    ): ChatDbResult<CoordinationReply> = transaction {
        CoordinationReply.fromJsonStrict(proposed.toJson())
        requireCentralConversation(proposed.reference.ownerCentralSessionId)
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
        beforeWrite()
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
        val row = sql.query(
            "SELECT schema_version,reply_id,evidence_id,reply_key,text,central_request_id,original_user_request,mailbox_message_id,payload_sha256,prepared_at_ms,state,state_at_ms FROM workspace_coordination_replies WHERE conversation_id=? AND reference_id=?",
            arrayOf(central, referenceId),
        ).use { c -> if (!c.moveToFirst()) null else ReplyRow(c) } ?: return null
        val notice = findCoordinationRef(central, referenceId, true)
            ?: error("Corrupt workspace_coordination_replies: notice is missing")
        val reply = CoordinationReply.create(notice, row.replyKey, ExactUtf16.decode(row.text), row.centralRequestId,
            ExactUtf16.decode(row.originalRequest), row.preparedAtMillis).withState(
            CoordinationReply.State.valueOf(row.state), row.stateAtMillis,
        )
        check(row.schemaVersion == CoordinationReply.SCHEMA_VERSION && reply.replyId == row.replyId && reply.reference.evidenceId == row.evidenceId &&
            reply.mailboxMessageId == row.mailboxMessageId && reply.payloadSha256 == row.payloadSha256) {
            "Corrupt workspace_coordination_replies payload"
        }
        validateReplyTransitions(central, reply)
        return reply
    }

    private fun validateReplyTransitions(central: String, reply: CoordinationReply) {
        val states = sql.query(
            "SELECT schema_version,state,state_at_ms FROM workspace_coordination_reply_transitions WHERE conversation_id=? AND reply_id=? ORDER BY seq",
            arrayOf(central, reply.replyId),
        ).use { c -> buildList { while (c.moveToNext()) {
            check(c.getInt(0) == CoordinationReply.SCHEMA_VERSION) { "Unsupported workspace_coordination_reply_state schema" }
            add(c.getString(1) to c.getLong(2))
        } } }
        var state = CoordinationReply.State.PREPARED
        var at = reply.preparedAtMillis
        for ((name, nextAt) in states) {
            val next = CoordinationReply.State.valueOf(name)
            check((state == CoordinationReply.State.PREPARED && next == CoordinationReply.State.STARTED) ||
                (state == CoordinationReply.State.STARTED && next == CoordinationReply.State.ACCEPTED_LOCAL)) {
                "Corrupt workspace_coordination_reply_state ordering"
            }
            check(nextAt >= at) { "Corrupt workspace_coordination_reply_state timestamp" }
            state = next
            at = nextAt
        }
        check(state == reply.state && at == reply.stateAtMillis) { "Reply state differs from its transition history" }
    }

    private fun findInputIncorporation(central: String, receiptId: String): InputIncorporation? = sql.query(
        "SELECT schema_version,source_kind,source_id,source_reference_id,mailbox_message_id,owner_central_session_id,workspace_id,execution_session_id,assignment_id,request_id,mission_id,bot_id,admission_cycle_id,first_incorporation_cycle_id,source_payload_sha256,delivered_content_sha256,transport_kind,transport_sha256,checkpoint_payload_sha256,checkpoint_saved_at_ms,recorded_at_ms FROM workspace_input_incorporations WHERE conversation_id=? AND receipt_id=?",
        arrayOf(central, receiptId),
    ).use { c ->
        if (!c.moveToFirst()) null else {
            val schemaVersion = c.getInt(0)
            val value = InputIncorporation.create(InputIncorporation.SourceKind.valueOf(c.getString(1)), c.getString(2),
                c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8),
                c.getString(9), c.getString(10), c.getString(11), c.getString(12), c.getString(13), c.getString(14),
                c.getString(15), c.getString(16), c.getString(17), c.getString(18), c.getLong(19), c.getLong(20))
            check(schemaVersion == InputIncorporation.SCHEMA_VERSION && value.receiptId == receiptId) { "Corrupt workspace_input_incorporations identity" }
            value
        }
    }

    private fun requireExactCoordinationSource(reference: CoordinationReference) {
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
        val current = sql.query("SELECT next_seq FROM conversations WHERE id=?", arrayOf(conversationId)).use { c ->
            if (!c.moveToFirst()) throw JournalAbort("conversation_not_found")
            c.getLong(0)
        }
        val changed = sql.compileStatement("UPDATE conversations SET next_seq=?,updated_at=? WHERE id=? AND next_seq=?").run {
            bindLong(1, current + 1); bindLong(2, System.currentTimeMillis()); bindString(3, conversationId); bindLong(4, current); executeUpdateDelete()
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

    private fun requireCentralConversation(id: String) {
        if (id != WorkspaceIdentity.CENTRAL_SESSION) throw JournalAbort("invalid_central_session")
        requireWritableConversation(id)
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
        database.withTransaction { block() }
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
        constructor(c: Cursor) : this(c.getInt(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5),
            c.getBlob(6), c.getLong(7), c.getString(8), c.getString(9))
    }

    private data class CoordinationRefRow(
        val schemaVersion: Int,
        val evidenceId: String, val contentSha256: String, val ownerCentralSessionId: String, val workspaceId: String,
        val executionSessionId: String, val assignmentId: String, val requestId: String, val missionId: String,
        val botId: String, val cycleId: String, val messageId: String, val kind: String, val revision: Long,
        val producedAtMillis: Long,
    ) {
        constructor(c: Cursor) : this(c.getInt(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
            c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(9), c.getString(10), c.getString(11),
            c.getString(12), c.getLong(13), c.getLong(14))
    }

    private data class ReplyRow(
        val schemaVersion: Int,
        val replyId: String, val evidenceId: String, val replyKey: String, val text: ByteArray,
        val centralRequestId: String, val originalRequest: ByteArray, val mailboxMessageId: String,
        val payloadSha256: String, val preparedAtMillis: Long, val state: String, val stateAtMillis: Long,
    ) {
        constructor(c: Cursor) : this(c.getInt(0), c.getString(1), c.getString(2), c.getString(3), c.getBlob(4), c.getString(5),
            c.getBlob(6), c.getString(7), c.getString(8), c.getLong(9), c.getString(10), c.getLong(11))
    }

    private class JournalAbort(val reason: String) : RuntimeException(reason)

    private companion object {
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
