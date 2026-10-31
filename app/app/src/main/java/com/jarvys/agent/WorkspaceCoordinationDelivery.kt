package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.workspaces.WorkspaceMutationContext
import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.crew.CrewCoordinationEvidence
import com.jarvys.agent.workspaces.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Selected durable observations. Reconciliation never runs a model, restores a mailbox or resumes work. */
class WorkspaceCoordinationDelivery internal constructor(
    context: Context,
    private val ledger: LocalRunStore,
    private val existingManager: (String) -> com.jarvys.agent.crew.CrewManager? = { CoreAgentRuntime.crewManagerForSession(it) },
) {
    constructor(context: Context) : this(context, LocalRunStore(context))
    private val app = context.applicationContext
    private data class RegisteredReference(val reference: WorkspaceCoordinationReference, val inserted: Boolean)

    private fun owner(workspaceId: String): WorkspaceRecord = WorkspaceRegistry(app).use { registry ->
        requireNotNull(registry.getWorkspace(workspaceId)) { "Workspace no longer exists" }.also {
            check(it.ownerCentralSessionId == WorkspaceIdentity.CENTRAL_SESSION &&
                it.executionSessionId == WorkspaceIdentity.executionSessionId(workspaceId))
        }
    }

    private fun assignment(reference: WorkspaceCoordinationReference): WorkspaceAssignment = WorkspaceRegistry(app).use { registry ->
        requireNotNull(registry.getAssignment(reference.workspaceId, reference.assignmentId)).also {
            check(it.missionId == reference.missionId && it.botId == reference.botId &&
                it.requestId == reference.requestId && it.state == WorkspaceAssignmentState.BOUND)
        }
    }

    private fun source(reference: WorkspaceCoordinationReference): CrewCoordinationEvidence {
        val scope = owner(reference.workspaceId)
        check(scope.executionSessionId == reference.executionSessionId &&
            scope.ownerCentralSessionId == reference.ownerCentralSessionId)
        assignment(reference)
        val evidence = requireNotNull(ledger.readCrewCoordinationEvidence(reference.executionSessionId, reference.evidenceId))
        check(reference.matchesEvidence(evidence)) { "Exact coordination source changed" }
        return evidence
    }

    private fun register(workspaceId: String, evidenceId: String, token: CancellationToken): RegisteredReference? {
        token.throwIfCancelled()
        val scope = owner(workspaceId)
        val evidence = requireNotNull(ledger.readCrewCoordinationEvidence(scope.executionSessionId, evidenceId))
        val target = WorkspaceRegistry(app).use { it.findBoundAssignment(workspaceId, evidence.missionId, evidence.botId) }
            ?: return null // The normal bind callback schedules catch-up for a fast producer.
        val proposed = WorkspaceCoordinationReference.create(evidence, scope.ownerCentralSessionId, workspaceId,
            target.assignmentId, target.requestId, target.revision)
        return synchronized(DeliveredArtifactStore.DELIVERY_ACTION_LOCK) {
            active(token) {
                val inserted = ledger.readWorkspaceCoordinationReference(scope.executionSessionId,
                    proposed.referenceId) == null
                RegisteredReference(ledger.appendWorkspaceCoordinationReferenceIfAbsent(proposed,
                    Runnable { token.throwIfCancelled() }), inserted)
            }
        }
    }

    /** Registers the exact durable central reference before an internal input is exposed to Jarvys. */
    internal fun registerForIncomingInput(workspaceId: String, evidenceId: String): WorkspaceCoordinationReference? =
        register(workspaceId, evidenceId, CancellationToken.uncancellable())?.reference

    private fun notice(reference: WorkspaceCoordinationReference, token: CancellationToken, admission: Runnable): Boolean {
        token.throwIfCancelled(); admission.run(); source(reference)
        var inserted = false
        synchronized(DeliveredArtifactStore.DELIVERY_ACTION_LOCK) {
            active(token) {
                inserted = ledger.readWorkspaceCoordinationNotice(reference.ownerCentralSessionId,
                    reference.referenceId) == null
                ledger.appendWorkspaceCoordinationNoticeIfAbsent(reference, Runnable { token.throwIfCancelled(); admission.run() })
            }
        }
        return inserted
    }

    internal fun reconcile(workspaceId: String, token: CancellationToken) {
        val scope = owner(workspaceId)
        if (!WorkspaceRegistry(app).use { it.hasBoundAssignments(workspaceId) }) return
        var visibleChange = false
        var after: String? = null
        try {
            do {
                token.throwIfCancelled()
                val ids = ledger.listCrewCoordinationEvidence(scope.executionSessionId, PAGE, after)
                for (id in ids) {
                    val registered = register(workspaceId, id, token) ?: continue
                    visibleChange = registered.inserted || visibleChange
                    visibleChange = notice(registered.reference, token, Runnable { token.throwIfCancelled() }) || visibleChange
                }
                after = ids.lastOrNull()
            } while (ids.size == PAGE)
        } finally {
            // One batch of newly visible evidence is one catalog/detail refresh; an idempotent scan is none.
            if (visibleChange) WorkspaceChanges.changed()
        }
    }

    fun inbox(workspaceId: String, limit: Int, after: String?, admission: Runnable): JSONObject {
        require(limit in 1..PAGE); admission.run()
        val scope = owner(workspaceId)
        val references = ledger.listWorkspaceCoordinationReferences(scope.executionSessionId, limit, after)
        val rows = ArrayList<JSONObject>()
        val included = ArrayList<WorkspaceCoordinationReference>()
        var wireSize = 0
        for (reference in references) {
            admission.run()
            val evidence = source(reference)
            ledger.requireWorkspaceCoordinationDisclosureLive(reference, false)
            val row = metadata(reference).put("reply_state", replyState(reference, evidence))
                .put("incorporation", incorporation(reference, admission))
            // CentralWorkspaceTool escapes non-ASCII code units losslessly on the wire.
            val cost = row.toString().sumOf { if (it.code > 0x7e) 6 else 1 } + 1
            if (rows.isNotEmpty() && wireSize + cost > INBOX_WIRE_BUDGET) break
            check(cost <= INBOX_WIRE_BUDGET) { "Coordination metadata exceeds its page budget" }
            rows += row; included += reference; wireSize += cost
        }
        admission.run()
        included.forEach { ledger.requireWorkspaceCoordinationDisclosureLive(it, false) }
        val more = included.size < references.size || references.size == limit
        return JSONObject().put("messages", JSONArray(rows))
            .put("next_after_message_id", if (more) included.last().referenceId else JSONObject.NULL)
            .put("delivery_issue", issue(workspaceId) ?: JSONObject.NULL)
            .put("semantics", "Evidence only. Chief inputs are pushed into the worker conversation; incorporation is confirmed by the exact input receipt after a durable worker checkpoint.")
    }

    fun read(workspaceId: String, messageId: String, expectedSha256: String, offset: Int,
             token: CancellationToken, admission: Runnable): JSONObject {
        require(offset >= 0 && expectedSha256.matches(Regex("[a-f0-9]{64}")))
        token.throwIfCancelled(); admission.run()
        val scope = owner(workspaceId)
        val reference = requireNotNull(ledger.readWorkspaceCoordinationReference(scope.executionSessionId, messageId))
        check(reference.workspaceId == workspaceId && reference.contentSha256 == expectedSha256)
        val evidence = source(reference)
        val exactReply = ledger.readWorkspaceCoordinationReply(reference.ownerCentralSessionId, reference.referenceId)
        if (exactReply != null && exactReply.state != WorkspaceCoordinationReply.State.PREPARED)
            WorkspaceInputIncorporationDelivery(app, ledger).reconcile(workspaceId, token, admission,
                WorkspaceInputSources.reply(exactReply))
        val content = coordinationContent(evidence.text, evidence.refs)
        require(offset <= content.length)
        val end = offset + minOf(TEXT_PAGE, content.length - offset)
        if (notice(reference, token, admission)) WorkspaceChanges.changed()
        val current = assignment(reference)
        admission.run(); token.throwIfCancelled()
        ledger.requireWorkspaceCoordinationDisclosureLive(reference, true)
        return metadata(reference).put("content_json_fragment", content.substring(offset, end))
            .put("content_format", "coordination-content-json-ascii-v1")
            .put("content_sha256", java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it.toInt() and 255) })
            .put("refs_verified", false)
            .put("offset", offset).put("next_offset", if (end < content.length) end else JSONObject.NULL)
            .put("total_chars", content.length).put("current_assignment_revision", current.revision)
            .put("expected_assignment_revision", reference.observedAssignmentRevision)
            .put("reply_state", replyState(reference, evidence))
            .put("incorporation", incorporation(reference, admission))
            .put("revision_semantics", "Registration revision does not prove a correction was consumed.")
    }

    /** Free supervisor-to-worker steering through the worker's ordinary durable input queue. */
    fun message(workspaceId: String, assignmentId: String, botId: String, expectedRevision: Long,
                key: String, text: String, correlationMessageId: String?, requestId: String,
                originalUserRequest: String, token: CancellationToken, admission: Runnable,
                mutation: WorkspaceMutationContext? = null): JSONObject {
        token.throwIfCancelled(); admission.run()
        val scope = owner(workspaceId)
        val proposedAssignment = WorkspaceRegistry(app).use { registry ->
            requireNotNull(registry.getAssignment(workspaceId, assignmentId)) { "Unknown workspace assignment" }
        }
        check(proposedAssignment.state == WorkspaceAssignmentState.BOUND
            && proposedAssignment.revision == expectedRevision && proposedAssignment.botId == botId) {
            "Supervisor message target is not the exact bound assignment"
        }
        val correlation = correlationMessageId?.let { referenceId ->
            resolveMessageCorrelation(scope, workspaceId, assignmentId, botId, expectedRevision, referenceId)
        }
        val manager = requireNotNull(existingManager(scope.executionSessionId)) {
            "Worker manager is unavailable; no supervisor message was dispatched"
        }
        val bot = requireNotNull(manager.bot(botId)) { "Worker evidence is unavailable; no message was dispatched" }
        check(bot.assignmentId() == assignmentId && bot.missionId == proposedAssignment.missionId
            && bot.hasDurableCheckpoint()) { "Worker does not match the durable bound assignment" }
        val exactRetry = WorkspaceConversationInputStore.hasSupervisorIntent(
            app, scope.executionSessionId, workspaceId, assignmentId, key)
        if (!exactRetry) {
            check(!bot.requiresExplicitResume() && bot.status() in setOf(
                com.jarvys.agent.crew.CrewManager.Status.QUEUED,
                com.jarvys.agent.crew.CrewManager.Status.RUNNING,
                com.jarvys.agent.crew.CrewManager.Status.WAITING,
                com.jarvys.agent.crew.CrewManager.Status.DONE)) {
                "Worker requires native explicit resume before a new supervisor message"
            }
        }

        // Durable effect admission must not wait for the assignment/native-control monitor.
        mutation?.admitMutation()
        val ownerToken = WorkspaceControlOwners.registerLocalReceipt(assignmentId)
        val unlinkCancellation = token.registerCancelAction { ownerToken.cancel() }
        try {
            return synchronized(WorkspaceCoordinator.controlDispatchLock(assignmentId)) {
                ownerToken.throwIfCancelled(); token.throwIfCancelled(); admission.run()
                val exact = WorkspaceRegistry(app).use { registry ->
                    requireNotNull(registry.getAssignment(workspaceId, assignmentId))
                }
                check(exact.state == WorkspaceAssignmentState.BOUND && exact.revision == expectedRevision
                    && exact.botId == botId && exact.missionId == proposedAssignment.missionId
                    && exact.requestId == proposedAssignment.requestId)
                WorkspaceRegistry(app).use { registry ->
                    check(registry.getUnsettledControl(workspaceId, assignmentId) == null
                        && registry.getPendingControl(workspaceId, assignmentId, expectedRevision) == null) {
                        "A native control takes precedence over this message"
                    }
                }
                check(!WorkspaceControlOwners.hasNativeControlOwner(assignmentId)) {
                    "A native control takes precedence over this message"
                }
                val validate = Runnable {
                    token.throwIfCancelled(); ownerToken.throwIfCancelled()
                    check(!WorkspaceControlOwners.hasNativeControlOwner(assignmentId)) {
                        "A native control takes precedence over this message"
                    }
                }
                validate.run()
                val priorIntent = WorkspaceConversationInputStore.hasSupervisorIntent(
                    app, scope.executionSessionId, workspaceId, assignmentId, key)
                val outbox = WorkspaceConversationInputStore.appendSupervisorIntent(
                    app, scope.executionSessionId, exact, botId, key, text, correlation)
                val source = requireNotNull(outbox.provenance)
                val stored = requireNotNull(ledger.readConversationMessageByStableId(
                    scope.executionSessionId, outbox.stableId())) { "Supervisor outbox receipt is unavailable" }
                val rowId = stored.getString("messageId")
                fun receipt(state: String, admissionCommitted: Boolean, deliveryConfirmed: Boolean,
                            incorporated: Boolean, issue: Throwable? = null, semantics: String) =
                    JSONObject().put("workspace_id", workspaceId).put("assignment_id", assignmentId)
                        .put("assignment_revision", expectedRevision).put("bot_id", botId)
                        .put("worker_input_id", source.messageId).put("stable_key", key)
                        .put("correlation_message_id", correlationMessageId ?: JSONObject.NULL)
                        .put("state", state).put("admission_committed", admissionCommitted)
                        .put("durable_intent_committed", true).put("delivery_confirmed", deliveryConfirmed)
                        .put("incorporated_in_worker_checkpoint", incorporated)
                        .put("user_authority", false).put("automatic_resume", false)
                        .put("delivery_issue", issue?.javaClass?.simpleName ?: JSONObject.NULL)
                        .put("semantics", semantics)

                if (ledger.readConversationInputState(scope.executionSessionId, rowId) == "resolved")
                    return@synchronized receipt("REJECTED", false, false, false, semantics =
                        "This exact supervisor intent was already resolved without worker incorporation; it is a final receipt.")

                val admitted = try {
                    manager.sendSupervisorMessageExact(botId, bot.token, source.messageId,
                        text, emptyList(), source, validate)
                } catch (failure: java.util.concurrent.CancellationException) {
                    throw failure
                } catch (failure: CrewManager.AdmissionUncertainException) {
                    if (failure.admissionCommitted) {
                        val state = when (ledger.readConversationInputState(scope.executionSessionId, rowId)) {
                            "incorporated" -> "INCORPORATED_IN_CHECKPOINT"
                            "resolved" -> "RESOLVED_BEFORE_INCORPORATION"
                            else -> "PENDING_INCORPORATION"
                        }
                        return@synchronized receipt(state, true, true,
                            state == "INCORPORATED_IN_CHECKPOINT", failure,
                            "Crew durably admitted the exact supervisor input, but a later callback or worker start could not be confirmed. The saved mailbox receipt is authoritative; this status does not prove understanding or completion.")
                    }
                    return@synchronized receipt("PENDING_DELIVERY", false, false, false, failure,
                        "The durable supervisor intent remains pending because Crew admission or its checkpoint could not be confirmed. An exact retry or authorized resume reuses this input ID.")
                } catch (failure: CrewManager.AdmissionRejectedException) {
                    val state = try {
                        ledger.readConversationInputState(scope.executionSessionId, rowId)
                    } catch (stateFailure: Exception) {
                        stateFailure.addSuppressed(failure)
                        return@synchronized receipt("PENDING_DELIVERY", false, false, false, stateFailure,
                            "Crew rejected admission, but the durable intent state could not be read. It remains pending for exact reconciliation.")
                    }
                    when (state) {
                        "incorporated" -> return@synchronized receipt("INCORPORATED_IN_CHECKPOINT", true, true,
                            true, semantics = "The exact supervisor input is already present in the worker checkpoint.")
                        "resolved" -> return@synchronized receipt("REJECTED", false, false, false, failure,
                            "The exact supervisor intent was resolved before worker incorporation; it is a final receipt.")
                    }
                    try {
                        if (ledger.markConversationMessagesResolved(scope.executionSessionId, listOf(rowId)).isNotEmpty())
                            WorkspaceChanges.changed()
                    } catch (cancelled: java.util.concurrent.CancellationException) {
                        throw cancelled
                    } catch (settlementFailure: Exception) {
                        return@synchronized receipt("PENDING_DELIVERY", false, false, false, settlementFailure,
                            "Crew rejected admission, but the durable intent could not be settled. It remains pending for exact reconciliation.")
                    }
                    return@synchronized receipt("REJECTED", false, false, false, failure,
                        "Crew rejected this exact supervisor intent before mailbox admission; the durable row was resolved and will not be retried automatically.")
                } catch (failure: Exception) {
                    val state = try {
                        ledger.readConversationInputState(scope.executionSessionId, rowId)
                    } catch (stateFailure: Exception) {
                        stateFailure.addSuppressed(failure)
                        return@synchronized receipt("PENDING_DELIVERY", false, false, false, stateFailure,
                            "Crew admission failed and the durable intent state could not be read. It remains pending for exact reconciliation.")
                    }
                    when (state) {
                        "incorporated" -> return@synchronized receipt("INCORPORATED_IN_CHECKPOINT", true, true,
                            true, semantics = "The exact supervisor input is already present in the worker checkpoint.")
                        "resolved" -> return@synchronized receipt("REJECTED", false, false, false, failure,
                            "The exact supervisor intent was resolved before worker incorporation; it is a final receipt.")
                    }
                    return@synchronized receipt("PENDING_DELIVERY", false, false, false, failure,
                        "Supervisor delivery failed before mailbox admission. The durable intent remains pending for an exact retry or authorized resume.")
                }
                check(admitted.id == source.messageId && admitted.from == "chief" && admitted.to == botId
                    && admitted.type == com.jarvys.agent.crew.CrewMessage.Type.MESSAGE && admitted.text == text)
                // The outbox and mailbox protocol confirms local acceptance, not model consumption.
                // A retry refers to its original intent even when it had to continue first admission.
                if (priorIntent) mutation?.existing(EffectInvocation.EffectReferenceKind.WORKSPACE_INPUT,
                    workspaceId, source.messageId)
                else mutation?.acceptedInput(workspaceId, source.messageId)
                val state = when (ledger.readConversationInputState(scope.executionSessionId, rowId)) {
                    "incorporated" -> "INCORPORATED_IN_CHECKPOINT"
                    "resolved" -> "RESOLVED_BEFORE_INCORPORATION"
                    else -> "PENDING_INCORPORATION"
                }
                receipt(state, true, true, state == "INCORPORATED_IN_CHECKPOINT", semantics =
                    "The exact supervisor input is durably queued. INCORPORATED_IN_CHECKPOINT is written only after the worker checkpoint contains it; neither state proves understanding, obedience or completion.")
            }
        } finally {
            unlinkCancellation.run()
            WorkspaceControlOwners.release(ownerToken)
        }
    }

    /** Accepts either the central reference ID or its originating Crew message ID. */
    internal fun resolveMessageCorrelation(workspaceId: String, assignmentId: String, botId: String,
                                           expectedRevision: Long, correlationMessageId: String)
        : WorkspaceCoordinationReference = resolveMessageCorrelation(owner(workspaceId), workspaceId,
            assignmentId, botId, expectedRevision, correlationMessageId)

    private fun resolveMessageCorrelation(scope: WorkspaceRecord, workspaceId: String, assignmentId: String,
                                          botId: String, expectedRevision: Long, correlationMessageId: String)
        : WorkspaceCoordinationReference {
        val referencePattern = Regex("workspace-coordination-[a-f0-9]{64}")
        val reference = if (referencePattern.matches(correlationMessageId)) {
            ledger.readWorkspaceCoordinationReference(scope.executionSessionId, correlationMessageId)
        } else null
        val resolved = reference ?: ledger.findWorkspaceCoordinationReferenceBySourceMessageId(
            scope.executionSessionId, correlationMessageId)
            ?: throw IllegalArgumentException("Correlation message is unavailable")
        check(resolved.workspaceId == workspaceId && resolved.assignmentId == assignmentId
            && resolved.botId == botId && resolved.observedAssignmentRevision == expectedRevision) {
            "Correlation message does not match this exact assignment revision"
        }
        source(resolved)
        ledger.requireWorkspaceCoordinationDisclosureLive(resolved, true)
        return resolved
    }

    /** Called under the same per-assignment lock used by native controls. */
    fun reply(workspaceId: String, messageId: String, expectedSha256: String, key: String, text: String,
              expectedRevision: Long, requestId: String, originalUserRequest: String,
              token: CancellationToken, admission: Runnable,
              mutation: WorkspaceMutationContext? = null): JSONObject {
        token.throwIfCancelled(); admission.run()
        val scope = owner(workspaceId)
        val reference = requireNotNull(ledger.readWorkspaceCoordinationReference(scope.executionSessionId, messageId))
        check(reference.workspaceId == workspaceId && reference.contentSha256 == expectedSha256)
        check(expectedRevision == reference.observedAssignmentRevision) { "Reply revision does not match its source reference" }
        val continuationAnswers = WorkspaceContinuationAnswers(app)
        if (continuationAnswers.canHandle(reference) &&
            existingManager(scope.executionSessionId)?.canReplyToCoordination(source(reference)) != true) {
            mutation?.admitMutation()
            return continuationAnswers.retain(reference, key, text, expectedRevision, token, admission)
        }
        val proposed = WorkspaceCoordinationReply.create(reference, key, text, requestId, originalUserRequest, System.currentTimeMillis())
        mutation?.admitMutation()
        return try { synchronized(WorkspaceCoordinator.controlDispatchLock(reference.assignmentId)) {
            token.throwIfCancelled(); admission.run()
            source(reference)
            ledger.requireWorkspaceCoordinationDisclosureLive(reference, true)
            val old = ledger.readWorkspaceCoordinationReply(scope.ownerCentralSessionId, reference.referenceId)
            if (old != null && old.state != WorkspaceCoordinationReply.State.PREPARED) {
                // prepare validates identical key/payload/provenance, then returns the original state.
                val existing = active(token) { ledger.prepareWorkspaceCoordinationReply(proposed, admission) }
                admission.run()
                mutation?.existing(EffectInvocation.EffectReferenceKind.WORKSPACE_INPUT,
                    workspaceId, existing.mailboxMessageId)
                return@synchronized replyReceipt(existing, admission)
            }
            val evidence = source(reference)
            val manager = requireNotNull(existingManager(scope.executionSessionId)) {
                "Worker manager is unavailable; no reply was dispatched"
            }
            val validate = Runnable {
                token.throwIfCancelled(); admission.run()
                val current = assignment(reference)
                check(current.revision == expectedRevision) { "Assignment changed; old reply is stale" }
                WorkspaceRegistry(app).use { registry ->
                    check(registry.getUnsettledControl(workspaceId, reference.assignmentId) == null &&
                        registry.getPendingControl(workspaceId, reference.assignmentId, current.revision) == null) {
                        "A native control takes precedence over this reply"
                    }
                }
                ledger.requireWorkspaceCoordinationDisclosureLive(reference, true)
                check(manager.canReplyToCoordination(evidence)) { "This exact request is no longer live" }
                // Revalidate after potentially blocking source/registry reads, immediately before claim/enqueue.
                token.throwIfCancelled(); admission.run()
            }
            validate.run()
            val dispatchAdmission = Runnable { token.throwIfCancelled(); admission.run() }
            val prepared = active(token) { ledger.prepareWorkspaceCoordinationReply(proposed, dispatchAdmission) }
            if (prepared.state != WorkspaceCoordinationReply.State.PREPARED) {
                mutation?.existing(EffectInvocation.EffectReferenceKind.WORKSPACE_INPUT,
                    workspaceId, prepared.mailboxMessageId)
                return@synchronized replyReceipt(prepared, admission)
            }
            var accepted: WorkspaceCoordinationReply? = null
            if (!token.runIfActive {
                    validate.run()
                    val started = ledger.markWorkspaceCoordinationReplyStarted(prepared, dispatchAdmission)
                    check(started.state == WorkspaceCoordinationReply.State.STARTED)
                    // Any failure after STARTED remains uncertain and must never cause a resend.
                    manager.replyToCoordinationExact(evidence, started.mailboxMessageId, text, WorkspaceInputSources.reply(started)) {
                        validate.run(); true
                    }
                    accepted = ledger.markWorkspaceCoordinationReplyAccepted(started, Runnable {
                        token.throwIfCancelled(); admission.run()
                    })
                    mutation?.acceptedInput(workspaceId, requireNotNull(accepted).mailboxMessageId)
                }) token.throwIfCancelled()
            WorkspaceChanges.changed()
            admission.run()
            replyReceipt(requireNotNull(accepted), admission)
        } } finally { WorkspaceChanges.changed() }
    }

    private fun replyState(reference: WorkspaceCoordinationReference, evidence: CrewCoordinationEvidence): String {
        val reply = ledger.readWorkspaceCoordinationReply(reference.ownerCentralSessionId, reference.referenceId)
        if (reply?.state == WorkspaceCoordinationReply.State.STARTED) return "UNCERTAIN_NO_REPLAY"
        if (reply?.state == WorkspaceCoordinationReply.State.ACCEPTED_LOCAL) {
            val receipt = WorkspaceInputIncorporationDelivery(app, ledger)
                .read(WorkspaceInputSources.reply(reply), Runnable { })
            return if (receipt == null) "PENDING_INCORPORATION" else "INCORPORATED_IN_CHECKPOINT"
        }
        if (evidence.kind == "FINDING") return "READ_ONLY_FINDING"
        val current = assignment(reference)
        if (current.revision != reference.observedAssignmentRevision) return "HISTORICAL_OR_SUPERSEDED"
        val pendingControl = WorkspaceRegistry(app).use {
            it.getUnsettledControl(reference.workspaceId, reference.assignmentId) != null ||
                it.getPendingControl(reference.workspaceId, reference.assignmentId, current.revision) != null
        }
        if (pendingControl) return "NATIVE_CONTROL_PENDING"
        val manager = existingManager(reference.executionSessionId)
        if (manager?.canReplyToCoordination(evidence) != true) return "HISTORICAL_OR_INTERRUPTED"
        if (reply?.state == WorkspaceCoordinationReply.State.PREPARED) return "PREPARED_NOT_DISPATCHED"
        return if (manager.bot(evidence.botId)?.status() == com.jarvys.agent.crew.CrewManager.Status.DONE)
            "DONE_REOPENABLE" else "LIVE_REPLYABLE"
    }

    private fun incorporation(reference: WorkspaceCoordinationReference, admission: Runnable): JSONObject {
        val reply = ledger.readWorkspaceCoordinationReply(reference.ownerCentralSessionId, reference.referenceId)
        val receipt = if (reply == null || reply.state == WorkspaceCoordinationReply.State.PREPARED) null
            else WorkspaceInputIncorporationDelivery(app, ledger).read(WorkspaceInputSources.reply(reply), admission)
        return JSONObject().put("state", if (receipt == null) "PENDING_INCORPORATION" else "INCORPORATED_IN_CHECKPOINT")
            .put("first_incorporation_cycle_id", receipt?.firstIncorporationCycleId ?: JSONObject.NULL)
            .put("checkpoint_payload_sha256", receipt?.checkpointPayloadSha256 ?: JSONObject.NULL)
            .put("checkpoint_saved_at_millis", receipt?.checkpointSavedAtMillis ?: JSONObject.NULL)
            .put("semantics", "Historical exact input incorporation into a durability-confirmed checkpoint; not proof of understanding, current context, obedience or completion.")
            .also {
                source(reference)
                admission.run()
                ledger.requireWorkspaceCoordinationDisclosureLive(reference, false)
            }
    }

    private fun replyReceipt(reply: WorkspaceCoordinationReply, admission: Runnable): JSONObject {
        val incorporationReceipt = incorporation(reply.reference, admission)
        val incorporated = incorporationReceipt.optString("state") == "INCORPORATED_IN_CHECKPOINT"
        val state = when {
            reply.state == WorkspaceCoordinationReply.State.STARTED -> "UNCERTAIN_NO_REPLAY"
            reply.state == WorkspaceCoordinationReply.State.ACCEPTED_LOCAL && incorporated -> "INCORPORATED_IN_CHECKPOINT"
            reply.state == WorkspaceCoordinationReply.State.ACCEPTED_LOCAL -> "PENDING_INCORPORATION"
            else -> reply.state.name
        }
        return JSONObject()
        .put("reply_id", reply.replyId).put("message_id", reply.reference.referenceId)
        .put("worker_input_id", reply.mailboxMessageId)
        .put("state", state)
        .put("admission_committed", reply.state == WorkspaceCoordinationReply.State.ACCEPTED_LOCAL)
        .put("incorporation", incorporationReceipt)
        .put("user_or_model_consumption_verified", false).put("question_resolved", false)
        .put("automatic_work_replay", false)
    }

    private fun metadata(reference: WorkspaceCoordinationReference) = JSONObject()
        .put("message_id", reference.referenceId).put("source_message_id", reference.messageId)
        .put("expected_sha256", reference.contentSha256).put("workspace_id", reference.workspaceId)
        .put("assignment_id", reference.assignmentId).put("mission_id", reference.missionId)
        .put("bot_id", reference.botId).put("cycle_id", reference.cycleId)
        .put("execution_session_id", reference.executionSessionId).put("request_id", reference.requestId)
        .put("expected_assignment_revision", reference.observedAssignmentRevision)
        .put("kind", reference.kind).put("produced_at_millis", reference.producedAtMillis)
        .put("worker_claim_verified", false).put("user_or_model_consumption_verified", false)
        .put("automatic_work_replay", false)

    companion object {
        /** Stable ASCII wire content: pages may split JSON escapes; concatenate before parsing. */
        internal fun coordinationContent(text: String, refs: List<String>): String = buildString {
            fun quoted(value: String) {
                append('"')
                for (c in value) when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    else -> if (c.code < 0x20 || c.code > 0x7e) {
                        append("\\u"); append(c.code.toString(16).padStart(4, '0'))
                    } else append(c)
                }
                append('"')
            }
            append("{\"text\":"); quoted(text); append(",\"refs\":[")
            refs.forEachIndexed { index, ref -> if (index > 0) append(','); quoted(ref) }
            append("]}")
        }
        private fun <T : Any> active(token: CancellationToken, action: () -> T): T {
            var value: T? = null
            if (!token.runIfActive { value = action() }) {
                token.throwIfCancelled()
                throw java.util.concurrent.CancellationException("Coordination stopped before admission")
            }
            return requireNotNull(value)
        }
        private const val INBOX_WIRE_BUDGET = 48000
        private const val PAGE = 50
        private const val TEXT_PAGE = 8000
        private val errors = ConcurrentHashMap<String, String>()
        @JvmStatic fun issue(workspaceId: String): String? = errors[workspaceId]
        /** Reuses the existing finite receipt scheduler and its STOP ownership; no new work queue. */
        @JvmStatic fun schedule(context: Context, executionSessionId: String) = WorkspaceResultDelivery.schedule(context, executionSessionId)
        internal fun reconcileScheduled(context: Context, workspaceId: String, token: CancellationToken) {
            try {
                WorkspaceCoordinationDelivery(context).reconcile(workspaceId, token)
                WorkspaceInputIncorporationDelivery(context).reconcile(workspaceId, token)
                if (errors.remove(workspaceId) != null) WorkspaceChanges.changed()
            } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: Exception) {
                val message = "Coordination evidence is pending or unavailable; no worker action was repeated."
                if (errors.put(workspaceId, message) != message) WorkspaceChanges.changed()
            }
        }
        @JvmStatic fun isCanonicalWorkspace(context: Context, session: String): Boolean {
            if (!WorkspaceIdentity.isExecutionSession(session)) return false
            return WorkspaceRegistry(context).use { registry ->
                registry.getWorkspace(WorkspaceIdentity.workspaceId(session))?.let {
                    it.executionSessionId == session && it.ownerCentralSessionId == WorkspaceIdentity.CENTRAL_SESSION
                } == true
            }
        }
    }
}
