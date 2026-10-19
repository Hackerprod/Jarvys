package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.crew.CrewResultEvidence
import com.jarvys.agent.workspaces.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Reconciles local result evidence and audit notices. Model delivery carries worker message content. */
class WorkspaceResultDelivery internal constructor(
    context: Context,
    private val ledger: LocalRunStore,
) {
    constructor(context: Context) : this(context, LocalRunStore(context))
    private val app = context.applicationContext

    private fun owner(workspaceId: String): WorkspaceRecord = WorkspaceRegistry(app).use { registry ->
        requireNotNull(registry.getWorkspace(workspaceId)) { "Workspace no longer exists" }.also {
            check(it.ownerCentralSessionId == WorkspaceIdentity.CENTRAL_SESSION &&
                it.executionSessionId == WorkspaceIdentity.executionSessionId(workspaceId))
        }
    }

    private fun assignment(reference: WorkspaceResultReference): WorkspaceAssignment = WorkspaceRegistry(app).use { registry ->
        val current = requireNotNull(registry.getAssignment(reference.workspaceId, reference.assignmentId))
        check(current.missionId == reference.missionId && current.botId == reference.botId &&
            current.requestId == reference.requestId && current.state == WorkspaceAssignmentState.BOUND)
        current
    }

    private fun source(reference: WorkspaceResultReference): CrewResultEvidence {
        val scope = owner(reference.workspaceId)
        check(scope.executionSessionId == reference.executionSessionId &&
            scope.ownerCentralSessionId == reference.ownerCentralSessionId)
        assignment(reference)
        val evidence = requireNotNull(ledger.readCrewResultEvidence(reference.executionSessionId, reference.evidenceId))
        check(evidence.contentSha256 == reference.contentSha256 && evidence.cycleId == reference.cycleId &&
            evidence.missionId == reference.missionId && evidence.botId == reference.botId)
        return evidence
    }

    /** Register exact persisted evidence for automatic local delivery. */
    private fun register(workspaceId: String, evidenceId: String, token: CancellationToken): WorkspaceResultReference? {
        token.throwIfCancelled()
        val scope = owner(workspaceId)
        val evidence = requireNotNull(ledger.readCrewResultEvidence(scope.executionSessionId, evidenceId))
        val target = WorkspaceRegistry(app).use { it.findBoundAssignment(workspaceId, evidence.missionId, evidence.botId) }
            ?: return null // A fast worker may finish before bindBot; binding schedules catch-up.
        val proposed = WorkspaceResultReference.create(evidence, scope.ownerCentralSessionId, workspaceId,
            target.assignmentId, target.requestId, target.revision)
        return synchronized(DeliveredArtifactStore.DELIVERY_ACTION_LOCK) {
            ledger.appendWorkspaceResultReferenceIfAbsent(proposed, Runnable { token.throwIfCancelled() })
        }
    }

    private fun notice(reference: WorkspaceResultReference, token: CancellationToken, admission: Runnable): Boolean {
        token.throwIfCancelled(); admission.run()
        source(reference) // Registry and source reads stay outside the ledger append lock.
        var inserted = false
        synchronized(DeliveredArtifactStore.DELIVERY_ACTION_LOCK) {
            // Same lock order as artifact delivery and coordination: delivery → STOP → ledger.
            if (!token.runIfActive {
                    token.throwIfCancelled(); admission.run()
                    inserted = ledger.readWorkspaceResultNotice(reference.ownerCentralSessionId,
                        reference.referenceId) == null
                    val beforeWrite = Runnable { token.throwIfCancelled(); admission.run() }
                    ledger.appendWorkspaceResultNoticeIfAbsent(reference, beforeWrite)
                }) token.throwIfCancelled()
        }
        // This notice is audit metadata only. Content delivery uses the conversation input ledger.
        return inserted
    }

    private fun discloseAutomatically(reference: WorkspaceResultReference, token: CancellationToken): Boolean =
        notice(reference, token, Runnable { token.throwIfCancelled() })

    /** Finite scan of durable source evidence. Only idempotent local notice append is retried. */
    internal fun reconcile(workspaceId: String, token: CancellationToken) {
        val scope = owner(workspaceId)
        if (!WorkspaceRegistry(app).use { it.hasBoundAssignments(workspaceId) }) return
        var visibleChange = false
        var cursor: String? = null
        try {
            do {
                token.throwIfCancelled()
                val ids = ledger.listCrewResultEvidence(scope.executionSessionId, PAGE, cursor)
                for (id in ids) {
                    token.throwIfCancelled()
                    val reference = register(workspaceId, id, token) ?: continue
                    visibleChange = discloseAutomatically(reference, token) || visibleChange
                }
                cursor = ids.lastOrNull()
            } while (ids.size == PAGE)
        } finally {
            if (visibleChange) WorkspaceChanges.detailChanged()
        }
    }

    fun list(workspaceId: String, limit: Int, after: String?, admission: Runnable): JSONObject {
        require(limit in 1..50); admission.run()
        val scope = owner(workspaceId)
        val references = ledger.listWorkspaceResultReferences(scope.executionSessionId, limit, after)
        val data = references.map { reference ->
            admission.run(); source(reference)
            ledger.requireWorkspaceResultDisclosureLive(reference, false)
            metadata(reference).put("delivery_state", when {
                ledger.readWorkspaceResultNotice(scope.ownerCentralSessionId, reference.referenceId) != null -> "DELIVERED_LOCALLY"
                else -> "PENDING_LOCAL_DELIVERY"
            })
        }
        admission.run()
        references.forEach { ledger.requireWorkspaceResultDisclosureLive(it, false) }
        return JSONObject().put("results", JSONArray(data)).put("delivery_issue", issue(workspaceId) ?: JSONObject.NULL).put("next_after_result_id",
            if (references.size == limit) references.last().referenceId else JSONObject.NULL)
            .put("delivery_semantics", "A local notice is not proof of user/model consumption or verified completion.")
    }

    fun read(workspaceId: String, referenceId: String, expectedSha256: String, offset: Int,
             token: CancellationToken, admission: Runnable): JSONObject {
        require(offset >= 0 && expectedSha256.matches(Regex("[a-f0-9]{64}")))
        admission.run(); token.throwIfCancelled()
        val scope = owner(workspaceId)
        val reference = requireNotNull(ledger.readWorkspaceResultReference(scope.executionSessionId, referenceId))
        check(reference.workspaceId == workspaceId && reference.contentSha256 == expectedSha256)
        val evidence = source(reference)
        require(offset <= evidence.resultText.length)
        require(offset == 0 || offset == evidence.resultText.length ||
            !(Character.isLowSurrogate(evidence.resultText[offset]) && Character.isHighSurrogate(evidence.resultText[offset - 1])))
        var end = minOf(evidence.resultText.length, offset + minOf(RESULT_PAGE, evidence.resultText.length - offset))
        if (end < evidence.resultText.length && end > offset && Character.isHighSurrogate(evidence.resultText[end - 1]) &&
            Character.isLowSurrogate(evidence.resultText[end])) end--
        // Reading the exact evidence can also finish its corresponding local receipt.
        // The current turn is already attending to this evidence; record local disclosure without
        // creating another system-origin request for the same result.
        if (notice(reference, token, admission)) WorkspaceChanges.detailChanged()
        val current = assignment(reference)
        admission.run(); token.throwIfCancelled()
        ledger.requireWorkspaceResultDisclosureLive(reference, true)
        return metadata(reference).put("result_text", evidence.resultText.substring(offset, end))
            .put("offset", offset).put("next_offset", if (end < evidence.resultText.length) end else JSONObject.NULL)
            .put("total_chars", evidence.resultText.length).put("current_assignment_revision", current.revision)
            .put("is_prior_evidence", true).put("current_generation_verified", false)
            .put("observed_revision_matches_current", current.revision == reference.observedAssignmentRevision)
            .put("revision_semantics", "Observed revision is registration metadata, not proof a correction was consumed.")
    }

    private fun metadata(reference: WorkspaceResultReference) = reference.toJson()
        .put("result_verified", false).put("user_or_model_consumption_verified", false)
        .put("automatic_work_replay", false)

    companion object {
        private const val PAGE = 50
        private const val RESULT_PAGE = 8000
        private val executor = Executors.newSingleThreadExecutor()
        internal class PendingReceipt(val token: CancellationToken) { val dirty = AtomicBoolean(true) }
        private val pending = HashMap<String, PendingReceipt>()
        private val pendingLock = Any()
        private val errors = ConcurrentHashMap<String, String>()
        private val catalogPending = AtomicBoolean(false)
        @JvmStatic fun scheduleCatalog(context: Context) {
            if (!catalogPending.compareAndSet(false, true)) return
            val app = context.applicationContext
            val token = WorkspaceControlOwners.registerLocalReceipt()
            try { executor.execute {
                try {
                    WorkspaceRegistry(app).use { registry ->
                        var cursor: String? = null
                        do {
                            token.throwIfCancelled()
                            val rows = registry.listWorkspaces(PAGE, cursor)
                            for (row in rows) {
                                if (!token.runIfActive { enqueue(app, row.executionSessionId, token) }) token.throwIfCancelled()
                            }
                            cursor = rows.lastOrNull()?.workspaceId
                        } while (rows.size == PAGE)
                    }
                } catch (_: Exception) {
                    // Existing registry/source errors remain observable through explicit workspace reads.
                } finally { WorkspaceControlOwners.release(token); catalogPending.set(false) }
            } } catch (_: RuntimeException) { WorkspaceControlOwners.release(token); catalogPending.set(false) }
        }

        @JvmStatic fun issue(workspaceId: String): String? = errors[workspaceId]
        @JvmStatic fun schedule(context: Context, executionSessionId: String) = enqueue(context, executionSessionId, null)
        /** Only the map is locked here; cancellation gates always precede this lock. */
        internal fun claimPending(session: String, candidate: PendingReceipt): Boolean {
            var claimed = false
            candidate.token.runIfActive {
                synchronized(pendingLock) {
                    val existing = pending[session]
                    if (existing != null && !existing.token.isCancellationRequested) existing.dirty.set(true)
                    else { pending[session] = candidate; claimed = true }
                }
            }
            return claimed
        }
        internal fun beginPending(entry: PendingReceipt) { synchronized(pendingLock) { entry.dirty.set(false) } }
        internal fun finishPending(session: String, entry: PendingReceipt): Boolean = synchronized(pendingLock) {
            if (pending[session] !== entry) false
            else {
                pending.remove(session)
                entry.dirty.get() && !entry.token.isCancellationRequested
            }
        }
        internal fun retryPending(token: CancellationToken, again: Boolean, enqueue: () -> Unit): Boolean =
            again && token.runIfActive { enqueue() }

        private fun enqueue(context: Context, executionSessionId: String, parent: CancellationToken?) {
            parent?.throwIfCancelled()
            if (!WorkspaceIdentity.isExecutionSession(executionSessionId)) return
            val app = context.applicationContext
            val token = WorkspaceControlOwners.registerLocalReceipt()
            val unlink = parent?.registerCancelAction { token.cancel() }
            val entry = PendingReceipt(token)
            if (!claimPending(executionSessionId, entry)) {
                unlink?.run(); WorkspaceControlOwners.release(token)
                return
            }
            try {
                executor.execute {
                    val id = WorkspaceIdentity.workspaceId(executionSessionId)
                    try {
                        beginPending(entry)
                        WorkspaceCoordinationDelivery.reconcileScheduled(app, id, token)
                        WorkspaceResultDelivery(app).reconcile(id, token)
                        if (errors.remove(id) != null) WorkspaceChanges.detailChanged()
                    } catch (_: java.util.concurrent.CancellationException) {
                        // Durable evidence remains pending; STOP does not replay or delete it.
                    } catch (_: Exception) {
                        setIssue(id, "Result delivery is pending or unavailable; no worker action was repeated.")
                    } finally {
                        val again = finishPending(executionSessionId, entry)
                        try {
                            // Keep the original owner and parent link alive through child registration.
                            // STOP before or during this handoff cancels the continuation as well.
                            retryPending(token, again) { enqueue(app, executionSessionId, token) }
                        } finally {
                            unlink?.run()
                            WorkspaceControlOwners.release(token)
                        }
                    }
                }
            } catch (failure: RuntimeException) {
                finishPending(executionSessionId, entry)
                unlink?.run(); WorkspaceControlOwners.release(token)
                setIssue(WorkspaceIdentity.workspaceId(executionSessionId), "Local result delivery could not be scheduled.")
            }
        }

        private fun setIssue(workspaceId: String, message: String) {
            if (errors.put(workspaceId, message) != message) WorkspaceChanges.detailChanged()
        }
    }
}
