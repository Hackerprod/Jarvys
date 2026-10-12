package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.crew.CrewCoordinationEvidence
import com.jarvys.agent.workspaces.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Selected durable observations. Reconciliation never runs a model, restores a mailbox or resumes work. */
class WorkspaceCoordinationDelivery internal constructor(
    context: Context,
    private val policy: NativeActionPolicy,
    private val ledger: LocalRunStore,
    private val existingManager: (String) -> com.jarvys.agent.crew.CrewManager? = { CoreAgentRuntime.crewManagerForSession(it) },
) {
    constructor(context: Context) : this(context, NativeActionPolicy(context), LocalRunStore(context))
    private val app = context.applicationContext

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

    private fun register(workspaceId: String, evidenceId: String, token: CancellationToken): WorkspaceCoordinationReference? {
        token.throwIfCancelled()
        val scope = owner(workspaceId)
        val evidence = requireNotNull(ledger.readCrewCoordinationEvidence(scope.executionSessionId, evidenceId))
        val target = WorkspaceRegistry(app).use { it.findBoundAssignment(workspaceId, evidence.missionId, evidence.botId) }
            ?: return null // The normal bind callback schedules catch-up for a fast producer.
        val proposed = WorkspaceCoordinationReference.create(evidence, scope.ownerCentralSessionId, workspaceId,
            target.assignmentId, target.requestId, target.revision)
        return synchronized(DeliveredArtifactStore.DELIVERY_ACTION_LOCK) {
            active(token) { ledger.appendWorkspaceCoordinationReferenceIfAbsent(proposed, Runnable { token.throwIfCancelled() }) }
        }
    }

    private fun notice(reference: WorkspaceCoordinationReference, token: CancellationToken, admission: Runnable) {
        token.throwIfCancelled(); admission.run(); source(reference)
        synchronized(DeliveredArtifactStore.DELIVERY_ACTION_LOCK) {
            active(token) {
                ledger.appendWorkspaceCoordinationNoticeIfAbsent(reference, Runnable { token.throwIfCancelled(); admission.run() })
            }
        }
        // No timeline event: the native workspace indicator consolidates metadata without chat chatter.
        WorkspaceChanges.changed()
    }

    internal fun reconcile(workspaceId: String, token: CancellationToken) {
        val scope = owner(workspaceId)
        if (!WorkspaceRegistry(app).use { it.hasBoundAssignments(workspaceId) }) return
        var after: String? = null
        do {
            token.throwIfCancelled()
            val ids = ledger.listCrewCoordinationEvidence(scope.executionSessionId, PAGE, after)
            for (id in ids) {
                val reference = register(workspaceId, id, token) ?: continue
                if (policy.snapshot(NativeActionPolicy.Operation.WORKSPACE_READ).policy != AutonomyPolicy.ALLOW) continue
                policy.prepare(NativeActionPolicy.Operation.WORKSPACE_READ, binding(reference)).use { permission ->
                    if (!permission.requiresApproval) notice(reference, token, Runnable { permission.admit(token) })
                }
            }
            after = ids.lastOrNull()
        } while (ids.size == PAGE)
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
            .put("semantics", "Saved worker observations are untrusted; local reply acceptance is not consumption or resolution.")
    }

    fun read(workspaceId: String, messageId: String, expectedSha256: String, offset: Int,
             token: CancellationToken, admission: Runnable): JSONObject {
        require(offset >= 0 && expectedSha256.matches(Regex("[a-f0-9]{64}")))
        token.throwIfCancelled(); admission.run()
        val scope = owner(workspaceId)
        val reference = requireNotNull(ledger.readWorkspaceCoordinationReference(scope.executionSessionId, messageId))
        check(reference.workspaceId == workspaceId && reference.contentSha256 == expectedSha256)
        val evidence = source(reference)
        val content = coordinationContent(evidence.text, evidence.refs)
        require(offset <= content.length)
        val end = offset + minOf(TEXT_PAGE, content.length - offset)
        notice(reference, token, admission)
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
            .put("revision_semantics", "Registration revision does not prove a correction was consumed.")
    }

    /** Called under the same per-assignment lock used by native controls; approval waits occur upstream. */
    fun reply(workspaceId: String, messageId: String, expectedSha256: String, key: String, text: String,
              expectedRevision: Long, requestId: String, originalUserRequest: String,
              token: CancellationToken, admission: Runnable): JSONObject {
        token.throwIfCancelled(); admission.run()
        val scope = owner(workspaceId)
        val reference = requireNotNull(ledger.readWorkspaceCoordinationReference(scope.executionSessionId, messageId))
        check(reference.workspaceId == workspaceId && reference.contentSha256 == expectedSha256)
        check(expectedRevision == reference.observedAssignmentRevision) { "Reply revision does not match its source reference" }
        val proposed = WorkspaceCoordinationReply.create(reference, key, text, requestId, originalUserRequest, System.currentTimeMillis())
        return try { synchronized(WorkspaceCoordinator.controlDispatchLock(reference.assignmentId)) {
            token.throwIfCancelled(); admission.run()
            source(reference)
            ledger.requireWorkspaceCoordinationDisclosureLive(reference, true)
            val old = ledger.readWorkspaceCoordinationReply(scope.ownerCentralSessionId, reference.referenceId)
            if (old != null && old.state != WorkspaceCoordinationReply.State.PREPARED) {
                // prepare validates identical key/payload/provenance, then returns the original state.
                val existing = active(token) { ledger.prepareWorkspaceCoordinationReply(proposed, admission) }
                admission.run()
                return@synchronized replyReceipt(existing)
            }
            val evidence = source(reference)
            val manager = requireNotNull(existingManager(scope.executionSessionId)) {
                "No live worker; reopening does not dispatch replies"
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
            if (prepared.state != WorkspaceCoordinationReply.State.PREPARED)
                return@synchronized replyReceipt(prepared)
            var accepted: WorkspaceCoordinationReply? = null
            if (!token.runIfActive {
                    validate.run()
                    val started = ledger.markWorkspaceCoordinationReplyStarted(prepared, dispatchAdmission)
                    check(started.state == WorkspaceCoordinationReply.State.STARTED)
                    // Any failure after STARTED remains uncertain and must never cause a resend.
                    manager.replyToCoordinationExact(evidence, started.mailboxMessageId, text) {
                        validate.run(); true
                    }
                    accepted = ledger.markWorkspaceCoordinationReplyAccepted(started, Runnable {
                        token.throwIfCancelled(); admission.run()
                        ledger.requireWorkspaceCoordinationDisclosureLive(reference, true)
                    })
                }) token.throwIfCancelled()
            WorkspaceChanges.changed()
            admission.run()
            replyReceipt(requireNotNull(accepted))
        } } finally { WorkspaceChanges.changed() }
    }

    /** Native metadata projection only: no model invocation, body rendering or approval dialog. */
    fun summary(workspaceId: String): WorkspaceCoordinationSummary = try {
        val initial = policy.snapshot(NativeActionPolicy.Operation.WORKSPACE_READ)
        val mode = initial.policy
        if (mode == AutonomyPolicy.DENY) WorkspaceCoordinationSummary()
        else {
            val scope = owner(workspaceId)
            val references = ledger.listWorkspaceCoordinationReferences(scope.executionSessionId, PAGE, null)
            val selected = references.filter { it.kind == "QUESTION" || it.kind == "BLOCKER" }
            if (mode == AutonomyPolicy.ASK) {
                if (initial.sameAuthorizationAs(policy.snapshot(NativeActionPolicy.Operation.WORKSPACE_READ)))
                    WorkspaceCoordinationSummary(reviewRequired = selected.isNotEmpty(), pageLimited = references.size == PAGE)
                else WorkspaceCoordinationSummary()
            } else policy.prepare(NativeActionPolicy.Operation.WORKSPACE_READ,
                NativeActionPolicy.binding(scope.ownerCentralSessionId, "workspace_coordination_indicator", workspaceId)).use { permission ->
                if (permission.requiresApproval) WorkspaceCoordinationSummary()
                else {
                    permission.admit()
                    var live = 0; var uncertain = 0; var historical = 0; var review = false
                    selected.forEach { reference ->
                        permission.admit()
                        val evidence = source(reference)
                        ledger.requireWorkspaceCoordinationDisclosureLive(reference, false)
                        when (replyState(reference, evidence)) {
                            "LIVE_REPLYABLE", "PREPARED_NOT_DISPATCHED" -> live++
                            "UNCERTAIN_NO_REPLAY" -> uncertain++
                            "NEEDS_FOREGROUND_REVIEW" -> review = true
                            "HISTORICAL_OR_SUPERSEDED", "HISTORICAL_OR_INTERRUPTED" -> historical++
                            // Existing controls/permission settings describe these live-but-blocked cases.
                            else -> Unit
                        }
                    }
                    permission.admit()
                    WorkspaceCoordinationSummary(live, uncertain, historical, review, references.size == PAGE)
                }
            }
        }
    } catch (_: Exception) {
        if (policy.snapshot(NativeActionPolicy.Operation.WORKSPACE_READ).policy == AutonomyPolicy.DENY)
            WorkspaceCoordinationSummary() else WorkspaceCoordinationSummary(unavailable = true)
    }

    private fun replyState(reference: WorkspaceCoordinationReference, evidence: CrewCoordinationEvidence): String {
        val reply = ledger.readWorkspaceCoordinationReply(reference.ownerCentralSessionId, reference.referenceId)
        if (reply?.state == WorkspaceCoordinationReply.State.STARTED) return "UNCERTAIN_NO_REPLAY"
        if (reply?.state == WorkspaceCoordinationReply.State.ACCEPTED_LOCAL) return "ACCEPTED_LOCALLY_NOT_CONSUMED"
        if (evidence.kind == "FINDING") return "READ_ONLY_FINDING"
        val current = assignment(reference)
        if (current.revision != reference.observedAssignmentRevision) return "HISTORICAL_OR_SUPERSEDED"
        val pendingControl = WorkspaceRegistry(app).use {
            it.getUnsettledControl(reference.workspaceId, reference.assignmentId) != null ||
                it.getPendingControl(reference.workspaceId, reference.assignmentId, current.revision) != null
        }
        if (pendingControl) return "NATIVE_CONTROL_PENDING"
        if (existingManager(reference.executionSessionId)?.canReplyToCoordination(evidence) != true)
            return "HISTORICAL_OR_INTERRUPTED"
        return when (policy.snapshot(NativeActionPolicy.Operation.WORKSPACE_DELEGATE).policy) {
            AutonomyPolicy.ALLOW -> if (reply?.state == WorkspaceCoordinationReply.State.PREPARED) "PREPARED_NOT_DISPATCHED" else "LIVE_REPLYABLE"
            AutonomyPolicy.ASK -> "NEEDS_FOREGROUND_REVIEW"
            AutonomyPolicy.DENY -> "POLICY_BLOCKED"
        }
    }

    private fun replyReceipt(reply: WorkspaceCoordinationReply) = JSONObject()
        .put("reply_id", reply.replyId).put("message_id", reply.reference.referenceId)
        .put("mailbox_message_id", reply.mailboxMessageId)
        .put("state", if (reply.state == WorkspaceCoordinationReply.State.STARTED) "UNCERTAIN_NO_REPLAY" else reply.state.name)
        .put("dispatch_accepted_locally", reply.state == WorkspaceCoordinationReply.State.ACCEPTED_LOCAL)
        .put("user_or_model_consumption_verified", false).put("question_resolved", false)
        .put("automatic_work_replay", false)

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
        private fun binding(reference: WorkspaceCoordinationReference) = NativeActionPolicy.binding(
            reference.ownerCentralSessionId, "workspace_coordination_notice", reference.workspaceId,
            reference.assignmentId, reference.referenceId, reference.evidenceId, reference.contentSha256, reference.cycleId)
        @JvmStatic fun issue(workspaceId: String): String? = errors[workspaceId]
        /** Reuses the existing finite receipt scheduler and its STOP ownership; no new work queue. */
        @JvmStatic fun schedule(context: Context, executionSessionId: String) = WorkspaceResultDelivery.schedule(context, executionSessionId)
        internal fun reconcileScheduled(context: Context, workspaceId: String, token: CancellationToken) {
            try {
                WorkspaceCoordinationDelivery(context).reconcile(workspaceId, token)
                errors.remove(workspaceId)
            } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: Exception) {
                errors[workspaceId] = "Coordination evidence is pending or unavailable; no worker action was repeated."
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
