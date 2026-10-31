package com.jarvys.agent.workspaces

import android.content.Context
import com.jarvys.agent.*
import com.jarvys.agent.crew.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Projection invalidation only; durable registry and Crew evidence remain the source of truth. */
object WorkspaceChanges {
    private val counter = java.util.concurrent.atomic.AtomicLong()
    private val mutable = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutable
    private val issues = ConcurrentHashMap<String, String>()
    private val resultIssues = ConcurrentHashMap<String, String>()
    private val coordinationIssues = ConcurrentHashMap<String, String>()
    @JvmStatic fun changed() { mutable.value = counter.incrementAndGet() }
    @JvmStatic fun snapshotSaved(sessionId: String, missionId: String) {
        if (!WorkspaceIdentity.isExecutionSession(sessionId)) return
        issues.remove("$sessionId/$missionId")
        changed()
    }
    @JvmStatic fun snapshotFailed(sessionId: String, missionId: String) {
        if (WorkspaceIdentity.isExecutionSession(sessionId)) {
            val key = "$sessionId/$missionId"
            val message = "The latest worker snapshot could not be saved; completion is unverified."
            if (issues.put(key, message) != message) changed()
        }
    }
    @JvmStatic fun resultEvidenceSaved(sessionId: String, missionId: String, evidenceId: String) {
        if (resultIssues.remove("$sessionId/$missionId/$evidenceId") != null) changed()
    }
    @JvmStatic fun resultEvidenceFailed(sessionId: String, missionId: String, evidenceId: String) {
        val key = "$sessionId/$missionId/$evidenceId"
        val message = "The worker result could not be saved for central delivery."
        if (resultIssues.put(key, message) != message) changed()
    }
    @JvmStatic fun coordinationEvidenceSaved(sessionId: String, missionId: String, evidenceId: String) {
        if (coordinationIssues.remove("$sessionId/$missionId/$evidenceId") != null) changed()
    }
    @JvmStatic fun coordinationEvidenceFailed(sessionId: String, missionId: String, evidenceId: String) {
        val key = "$sessionId/$missionId/$evidenceId"
        val message = "The exact worker message could not be saved for central coordination."
        if (coordinationIssues.put(key, message) != message) changed()
    }
    fun issue(sessionId: String, missionId: String): String? =
        listOfNotNull(issues["$sessionId/$missionId"], coordinationIssues.entries.firstOrNull { it.key.startsWith("$sessionId/$missionId/") }?.value, resultIssues.entries.firstOrNull { it.key.startsWith("$sessionId/$missionId/") }?.value).joinToString("\n").takeIf { it.isNotEmpty() }
}

/** An additive adapter over existing per-session Crew, checkpoints and project ownership. */
class WorkspaceCoordinator internal constructor(context: Context,
    private val managerFor: (String) -> CrewManager,
    private val keepalive: () -> Unit,
    private val persistMissionSnapshot: (Context, CrewMissionSnapshot) -> Unit = { target, snapshot ->
        LocalRunStore(target).appendCrewMissionSnapshot(snapshot)
    },
) : CentralWorkspaceTool.Backend {
    constructor(context: Context) : this(context,
        { session -> CoreAgentRuntime.prepareWorkspaceCrew(context.applicationContext, session) },
        { AgentForegroundService.ensureCrewKeepalive(context.applicationContext) })
    override fun hasCompleteMutationAdmissionCoverage() = true

    private val app = context.applicationContext
    private fun registry() = WorkspaceRegistry(app)
    private fun lock(workspaceId: String): Any = locks.getOrPut(WorkspaceIdentity.requireWorkspaceId(workspaceId)) { Any() }
    private fun workspace(store: WorkspaceRegistry, id: String) = requireNotNull(store.getWorkspace(id)) { "Unknown workspace" }

    override fun create(key: String, title: String, requestId: String, originalUserRequest: String, admission: Runnable): JSONObject =
        createInternal(key, title, admission, null)

    override fun create(key: String, title: String, requestId: String, originalUserRequest: String,
                        admission: Runnable, context: WorkspaceMutationContext): JSONObject =
        createInternal(key, title, admission, context)

    private fun createInternal(key: String, title: String, admission: Runnable, context: WorkspaceMutationContext?): JSONObject =
        registry().use { store ->
            if (context == null) {
                val legacy = store.createWorkspace(key, title, admission)
                WorkspaceChanges.changed()
                return@use workspaceJson(legacy)
            }
            admission.run()
            store.existingWorkspace(key, title)?.let { existing ->
                admission.run()
                context?.existing(EffectInvocation.EffectReferenceKind.WORKSPACE, existing.workspaceId, existing.workspaceId)
                return@use workspaceJson(existing)
            }
            context?.admitMutation() // Outside the registry transaction/monitor.
            val outcome = store.createWorkspaceOutcome(key, title, admission)
            if (outcome.created) context?.createdWorkspace(outcome.record.workspaceId)
            else context?.existing(EffectInvocation.EffectReferenceKind.WORKSPACE, outcome.record.workspaceId, outcome.record.workspaceId)
            WorkspaceChanges.changed()
            workspaceJson(outcome.record)
        }

    override fun results(workspaceId: String, limit: Int, afterResultId: String?, admission: Runnable): JSONObject =
        WorkspaceResultDelivery(app).list(workspaceId, limit, afterResultId, admission)

    override fun readResult(workspaceId: String, resultId: String, expectedSha256: String, offset: Int,
                            token: CancellationToken, admission: Runnable): JSONObject =
        WorkspaceResultDelivery(app).read(workspaceId, resultId, expectedSha256, offset, token, admission)

    override fun continueResult(workspaceId: String, resultId: String, expectedSha256: String,
                                expectedAssignmentRevision: Long, token: CancellationToken, admission: Runnable,
                                context: WorkspaceMutationContext): JSONObject =
        WorkspaceContinuationDelivery.continueResult(app, workspaceId, resultId, expectedSha256,
            expectedAssignmentRevision, token, admission, context)

    override fun inbox(workspaceId: String, limit: Int, afterMessageId: String?, admission: Runnable): JSONObject =
        WorkspaceCoordinationDelivery(app).inbox(workspaceId, limit, afterMessageId, admission)

    override fun readMessage(workspaceId: String, messageId: String, expectedSha256: String, offset: Int,
                             token: CancellationToken, admission: Runnable): JSONObject =
        WorkspaceCoordinationDelivery(app).read(workspaceId, messageId, expectedSha256, offset, token, admission)

    override fun reply(workspaceId: String, messageId: String, expectedSha256: String, key: String, text: String,
                       expectedAssignmentRevision: Long, requestId: String, originalUserRequest: String,
                       token: CancellationToken, admission: Runnable): JSONObject =
        WorkspaceCoordinationDelivery(app).reply(workspaceId, messageId, expectedSha256, key, text,
            expectedAssignmentRevision, requestId, originalUserRequest, token, admission)

    override fun message(workspaceId: String, assignmentId: String, botId: String,
                         expectedAssignmentRevision: Long, key: String, text: String,
                         correlationMessageId: String?, requestId: String, originalUserRequest: String,
                         token: CancellationToken, admission: Runnable): JSONObject =
        WorkspaceCoordinationDelivery(app, LocalRunStore(app), managerFor).message(
            workspaceId, assignmentId, botId, expectedAssignmentRevision, key, text,
            correlationMessageId, requestId, originalUserRequest, token, admission)

    override fun inspect(workspaceId: String, path: String, token: CancellationToken, admission: Runnable): JSONObject =
        WorkspaceArtifactDelivery(app).inspect(workspaceId, path, token, admission)

    override fun deliver(workspaceId: String, path: String, expectedSha256: String, filename: String?,
                         token: CancellationToken, admission: Runnable): JSONObject =
        WorkspaceArtifactDelivery(app).deliver(workspaceId, path, expectedSha256, filename, token, admission)

    override fun reply(workspaceId: String, messageId: String, expectedSha256: String, key: String, text: String,
                       expectedAssignmentRevision: Long, requestId: String, originalUserRequest: String,
                       token: CancellationToken, admission: Runnable, context: WorkspaceMutationContext): JSONObject =
        WorkspaceCoordinationDelivery(app).reply(workspaceId, messageId, expectedSha256, key, text,
            expectedAssignmentRevision, requestId, originalUserRequest, token, admission, context)

    override fun message(workspaceId: String, assignmentId: String, botId: String,
                         expectedAssignmentRevision: Long, key: String, text: String, correlationMessageId: String?,
                         requestId: String, originalUserRequest: String, token: CancellationToken,
                         admission: Runnable, context: WorkspaceMutationContext): JSONObject =
        WorkspaceCoordinationDelivery(app, LocalRunStore(app), managerFor).message(workspaceId, assignmentId, botId,
            expectedAssignmentRevision, key, text, correlationMessageId, requestId, originalUserRequest, token, admission, context)

    override fun deliver(workspaceId: String, path: String, expectedSha256: String, filename: String?,
                         token: CancellationToken, admission: Runnable, context: WorkspaceMutationContext): JSONObject =
        WorkspaceArtifactDelivery(app).deliver(workspaceId, path, expectedSha256, filename, token, admission, context)

    override fun list(limit: Int, afterWorkspaceId: String?, admission: Runnable): JSONObject = registry().use { store ->
        admission.run()
        val rows = store.listWorkspaces(limit, afterWorkspaceId)
        JSONObject().put("workspaces", JSONArray(rows.map(::workspaceJson)))
            .put("next_after_workspace_id", if (rows.size == limit) rows.last().workspaceId else JSONObject.NULL)
    }

    override fun delegate(workspaceId: String, key: String, role: String, mission: String, tools: List<String>?,
                          name: String?, missionAccess: String, requestId: String, originalUserRequest: String,
                          token: CancellationToken, admission: Runnable): JSONObject =
        delegateInternal(workspaceId, key, role, mission, tools, name, missionAccess, requestId, originalUserRequest, token, admission, null)

    override fun delegate(workspaceId: String, key: String, role: String, mission: String, tools: List<String>?,
                          name: String?, missionAccess: String, requestId: String, originalUserRequest: String,
                          token: CancellationToken, admission: Runnable, context: WorkspaceMutationContext): JSONObject =
        delegateInternal(workspaceId, key, role, mission, tools, name, missionAccess, requestId, originalUserRequest, token, admission, context)

    override fun delegateContinuing(workspaceId: String, key: String, role: String, mission: String,
                                    tools: List<String>?, name: String?, missionAccess: String,
                                    requestId: String, originalUserRequest: String, token: CancellationToken,
                                    admission: Runnable, context: WorkspaceMutationContext?): JSONObject {
        check(WorkspaceContinuationDelivery.NEW_ADMISSION_ENABLED) { "Durable continuation admission is not enabled" }
        return delegateInternal(workspaceId, key, role, mission, tools, name, missionAccess,
            requestId, originalUserRequest, token, admission, context, true)
    }

    private fun delegateInternal(workspaceId: String, userKey: String, role: String, mission: String, tools: List<String>?,
                          name: String?, missionAccess: String, requestId: String, originalUserRequest: String,
                          token: CancellationToken, admission: Runnable, context: WorkspaceMutationContext?, continueResult: Boolean = false): JSONObject {
        require(!userKey.startsWith("continuation-v1:")) { "Reserved continuation key prefix" }
        val continuingKey = "continuation-v1:" + UUID.nameUUIDFromBytes(userKey.toByteArray(Charsets.UTF_8))
        val key = if (continueResult) continuingKey else userKey
        admission.run()
        registry().use { existingStore ->
            check(existingStore.existingAssignment(workspaceId, if (continueResult) userKey else continuingKey,
                requestId, originalUserRequest, role, mission, tools, name, CrewMissionAccess.parse(missionAccess)) == null) {
                "Delegation key conflicts with its original continuation mode"
            }
        }
        var sourcePrepared: WorkspaceAssignment? = null
        if (context != null) {
            admission.run()
            val existing = registry().use { store ->
                store.existingAssignment(workspaceId, key, requestId, originalUserRequest, role, mission, tools, name,
                    CrewMissionAccess.parse(missionAccess))
            }
            if (existing != null && existing.state != WorkspaceAssignmentState.PREPARED) {
                admission.run()
                context?.existing(EffectInvocation.EffectReferenceKind.ASSIGNMENT, workspaceId, existing.assignmentId)
                return assignmentJson(existing, null, 2000)
            }
            context.admitMutation() // PREPARED retry may still spawn: it is not a no-effect fastpath.
            sourcePrepared = context.withCurrentWorkspaceTransaction {
                synchronized(lock(workspaceId)) {
                    registry().use { store ->
                        admission.run()
                        context.requireInsideCurrentLease()
                        store.prepareAssignment(workspaceId, key, requestId, originalUserRequest,
                            role, mission, tools, name, CrewMissionAccess.parse(missionAccess), Runnable {
                                admission.run(); context.requireInsideCurrentLease()
                            }, context.rootSource)
                    }
                }
            }
        }
        // The source lease ends before any Crew/Room/listener work. Another caller may win
        // dispatch in this gap; re-read the exact row and never replay STARTED/BOUND.
        return synchronized(lock(workspaceId)) {
            registry().use { store ->
                val scope = workspace(store, workspaceId)
                if (context != null) admission.run()
                val prepared = sourcePrepared?.let { inserted ->
                    requireNotNull(store.getAssignment(workspaceId, inserted.assignmentId)) { "Prepared assignment is unavailable" }
                } ?: store.prepareAssignment(workspaceId, key, requestId, originalUserRequest,
                    role, mission, tools, name, CrewMissionAccess.parse(missionAccess), admission)
                // A duplicate request returns the original receipt. STARTED is not proof of failed spawning.
                if (prepared.state != WorkspaceAssignmentState.PREPARED) {
                    if (context != null) admission.run()
                    context?.existing(EffectInvocation.EffectReferenceKind.ASSIGNMENT, workspaceId, prepared.assignmentId)
                    return@use assignmentJson(prepared, null, 2000)
                }
                if (!CrewMode.read(app).enabled())
                    throw WorkspacePreAdmissionException(WorkspacePreAdmissionException.Reason.CREW_DISABLED)
                val manager = managerFor(scope.executionSessionId)
                // Only this explicitly non-mutating preflight can report NOT_STARTED. A later
                // revocation/race or arbitrary spawn failure remains uncertain and is never replayed.
                admission.run()
                try {
                    manager.validateSpawnForMission(prepared.missionId, prepared.originalUserRequest,
                        prepared.role, prepared.missionText, prepared.tools, prepared.name, prepared.missionAccess)
                } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                catch (rejected: IllegalArgumentException) {
                    throw WorkspacePreAdmissionException(WorkspacePreAdmissionException.Reason.WORKER_CONFIGURATION_REJECTED, rejected)
                } catch (rejected: IllegalStateException) {
                    throw WorkspacePreAdmissionException(WorkspacePreAdmissionException.Reason.WORKER_CONFIGURATION_REJECTED, rejected)
                }
                val started = store.markStarted(workspaceId, prepared.assignmentId, prepared.revision,
                    CrewProcessIdentity.ID, admission)
                WorkspaceChanges.changed()
                val admittedBot = AtomicReference<Pair<CrewManager.Bot, CancellationToken>?>()
                val unregister = token.registerCancelAction { admittedBot.get()?.let { manager.stopExact(it.first.id, it.second) } }
                try {
                    lateinit var bot: CrewManager.Bot
                    if (!token.runIfActive {
                            admission.run()
                            bot = manager.spawnForMissionDeferred(started.missionId, started.originalUserRequest,
                                started.role, started.missionText, started.tools, started.name, started.missionAccess,
                                scope.title, started)
                            admittedBot.set(bot to bot.token)
                        }) throw java.util.concurrent.CancellationException("Workspace delegation stopped before spawning")
                    if (token.isCancellationRequested) manager.stopExact(bot.id, admittedBot.get()!!.second)
                    val bound = store.bindBot(workspaceId, started.assignmentId, started.revision, bot.id)
                    context?.acceptedAssignment(workspaceId, bound.assignmentId) // Local proof only, no Room/listener.
                    if (!token.isCancellationRequested) {
                        if (continueResult) WorkspaceContinuationDelivery.initialize(app, bound, manager, bot)
                        manager.startPreparedForMission(bot)
                    }
                    WorkspaceResultDelivery.schedule(app, scope.executionSessionId)
                    WorkspaceMissionCards.delegated(app, scope, bound)
                    WorkspaceChanges.changed()
                    keepalive()
                    cleanup.execute { try { bot.awaitTermination() } finally { unregister.run(); WorkspaceChanges.changed() } }
                    assignmentJson(bound, null, 2000)
                } catch (failure: RuntimeException) {
                    admittedBot.get()?.let { runCatching { manager.stopExact(it.first.id, it.second) } }
                    unregister.run()
                    runCatching { store.markUncertain(workspaceId, started.assignmentId, started.revision) }
                    WorkspaceChanges.changed()
                    throw failure
                }
            }
        }
    }

    override fun read(workspaceId: String, assignmentId: String?, limit: Int, afterAssignmentId: String?, admission: Runnable): JSONObject =
        registry().use { store ->
            val scope = workspace(store, workspaceId)
            admission.run()
            val assignments = if (assignmentId != null) listOf(requireNotNull(store.getAssignment(workspaceId, assignmentId)))
                else store.listAssignments(workspaceId, limit, afterAssignmentId)
            val snapshots = LocalRunStore(app).readCrewMissionSnapshots(scope.executionSessionId)
            val rows = assignments.map { assignment ->
                val snapshot = snapshots.firstOrNull { it.missionId == assignment.missionId }
                val observed = if (snapshot?.processId == CrewProcessIdentity.ID) snapshot else snapshot?.interrupted()
                val row = assignmentJson(assignment, observed, if (assignmentId != null) 16000 else 500)
                row.put("continuation", assignment.botId?.let {
                    WorkspaceContinuationDelivery.status(app, scope.executionSessionId, it) } ?: JSONObject.NULL)
                val unsettled = store.getUnsettledControl(workspaceId, assignment.assignmentId)
                val pending = store.getPendingControl(workspaceId, assignment.assignmentId, assignment.revision)
                row.put("pending_control", pending?.let { JSONObject().put("request_id", it.requestId)
                    .put("type", it.type.name).put("state", it.state.name)
                    .put("dispatched", false).put("automatic_retry", false) } ?: JSONObject.NULL)
                if (unsettled != null) row.put("status", "CONTROL_UNCERTAIN")
                    .put("control_state", unsettled.state.name).put("result_is_prior_evidence", true)
                row.put("evidence_issue", listOfNotNull(WorkspaceChanges.issue(scope.executionSessionId, assignment.missionId),
                    WorkspaceResultDelivery.issue(workspaceId)).joinToString("\n").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                val digest = sha256(row.toString())
                store.markRead(workspaceId, assignment.assignmentId, digest, admission)
                row.put("observation_digest", digest)
            }
            JSONObject().put("workspace", workspaceJson(scope)).put("assignments", JSONArray(rows))
                .put("next_after_assignment_id", if (assignmentId == null && assignments.size == limit) assignments.last().assignmentId else JSONObject.NULL)
                .put("delivery_semantics", "Read receipts record this tool observation; they do not prove the model or user consumed it.")
        }

    /** Called only from native central controls. Model tool arguments cannot author this record. */
    fun submitNativeControl(workspaceId: String, assignmentId: String, revision: Long,
                            type: WorkspaceControlType, userText: String): WorkspaceUserControl = registry().use { store ->
        val assignment = requireNotNull(store.getAssignment(workspaceId, assignmentId))
        check(assignment.revision == revision && assignment.botId != null) { "Workspace target changed" }
        store.nativeControls().submit(UUID.randomUUID().toString(), workspaceId, assignmentId,
            assignment.botId, revision, type, userText).also { WorkspaceChanges.changed() }
    }

    fun enqueueNativeControl(workspaceId: String, assignmentId: String, revision: Long,
                             type: WorkspaceControlType, userText: String,
                             onAdmitted: () -> Unit = {},
                             completion: (Result<String>) -> Unit) {
        // Keep the caller off the assignment monitor: a concurrent durable supervisor message
        // may hold it while Room and the worker checkpoint finish. Register STOP before doing
        // the native mutation, but on this cleanup executor so the main thread never waits there.
        try {
            cleanup.execute {
                val result = runCatching {
                    val token = synchronized(controlDispatchLock(assignmentId)) {
                        WorkspaceControlOwners.register(type, assignmentId)
                    }
                    var completed = false
                    try {
                        lateinit var control: WorkspaceUserControl
                        if (!token.runIfActive {
                                control = submitNativeControl(workspaceId, assignmentId, revision, type, userText)
                            }) throw java.util.concurrent.CancellationException("Native workspace control stopped before recording")
                        val ledger = LocalRunStore(app)
                        ledger.appendConversationMessage(WorkspaceIdentity.CENTRAL_SESSION, "user",
                            "$userText\n[Workspace $workspaceId; assignment $assignmentId; native control ${control.requestId}]")
                        runCatching { onAdmitted() } // UI observation must not cancel a durable user command.
                        val receipt = executeNativeControl(control.requestId, token)
                        completed = true
                        ledger.appendConversationMessage(WorkspaceIdentity.CENTRAL_SESSION, "assistant", receipt)
                        receipt
                    } finally {
                        WorkspaceControlOwners.release(token)
                        // A fast child result may have arrived while this owner still fenced its
                        // continuation. Reconcile after release, never infer permission from a timer.
                        if (completed && !token.isCancellationRequested)
                            WorkspaceResultDelivery.schedule(app, WorkspaceIdentity.executionSessionId(workspaceId))
                    }
                }
                WorkspaceChanges.changed()
                completion(result)
            }
        } catch (failure: RuntimeException) {
            WorkspaceChanges.changed()
            completion(Result.failure(failure))
        }
    }

    /** Idempotent dispatch of an exact persisted native control, never replayed on startup. */
    fun executeNativeControl(controlId: String, token: CancellationToken): String {
        return registry().use { store ->
                val control = requireNotNull(store.getControl(controlId))
                if (control.state != WorkspaceControlState.PENDING)
                    return@use "Control ${control.requestId}: ${control.state}. No action was repeated."
                val scope = workspace(store, control.workspaceId)
                val assignment = requireNotNull(store.getAssignment(control.workspaceId, control.assignmentId))
                check(assignment.revision == control.assignmentRevision && assignment.botId == control.botId)
                val manager = managerFor(scope.executionSessionId)
                val bot = requireNotNull(manager.bot(control.botId)) { "Worker evidence is unavailable; no action was repeated" }
                check(bot.missionId == assignment.missionId)
                val expectedToken = bot.token
                val generation = expectedToken.generation()
                val validate = Runnable {
                    token.throwIfCancelled()
                    check(bot.token.generation() == generation) { "Worker generation changed" }
                }
                    var started: WorkspaceUserControl? = null
                    try {
                        synchronized(controlDispatchLock(control.assignmentId)) {
                            if (!token.runIfActive {
                        if (control.type == WorkspaceControlType.PAUSE || control.type == WorkspaceControlType.CORRECT)
                            WorkspaceContinuationDelivery.cancelActiveOwner(app, control.workspaceId, bot.id)
                        val continuationPrepared = if ((control.type == WorkspaceControlType.RESUME && bot.canResume()) ||
                            (control.type == WorkspaceControlType.CORRECT && bot.requiresExplicitResume() && bot.canResume()))
                            WorkspaceContinuationDelivery.prepareResume(app, control, bot) else null
                        if (control.type == WorkspaceControlType.CORRECT && continuationPrepared == null)
                            WorkspaceContinuationDelivery.cancel(app, control.workspaceId, bot.id)
                        started = store.markControlStarted(controlId, control.revision, CrewProcessIdentity.ID, validate)
                        WorkspaceChanges.changed()
                        validate.run()
                        check(store.getAssignment(control.workspaceId, control.assignmentId)?.revision == started!!.admittedAssignmentRevision)
                        when (control.type) {
                            WorkspaceControlType.PAUSE -> {
                                WorkspaceContinuationDelivery.cancel(app, control.workspaceId, bot.id)
                                manager.stopForUserControl(bot.id, expectedToken)
                            }
                            WorkspaceControlType.RESUME -> {
                                check(bot.isQuiescent) { "Worker cleanup is not complete" }
                                if (bot.canResume()) {
                                    if (continuationPrepared == null) manager.resumeExact(bot.id, expectedToken, null)
                                    else manager.resumeExact(bot.id, expectedToken, null, null,
                                        WorkspaceContinuationDelivery.resumeCommit(app, continuationPrepared, requireNotNull(started), manager, token))
                                }
                                else {
                                    check(assignment.processId == CrewProcessIdentity.ID && !bot.requiresExplicitResume()) {
                                        "This historical worker has no resumable checkpoint"
                                    }
                                    manager.sendUserMessageExact(bot.id, expectedToken, control.userText)
                                }
                                WorkspaceConversationInputStore.deliverPendingSupervisorIntentsAfterExplicitResume(
                                    app, scope.executionSessionId, manager, bot.id, token,
                                    Runnable { token.throwIfCancelled() })
                                keepalive()
                            }
                            WorkspaceControlType.CORRECT -> {
                                // Legacy checkpoints may lack a cycle identity; preserve their control path
                                // without inventing an exact incorporation proof.
                                val input = bot.cycleId().takeIf { it.isNotEmpty() }?.let { cycle ->
                                    WorkspaceInputSources.correction(scope,
                                        requireNotNull(store.getAssignment(control.workspaceId, control.assignmentId)),
                                        requireNotNull(started), cycle)
                                }
                                if (bot.requiresExplicitResume()) {
                                    check(bot.canResume() && bot.isQuiescent) { "Worker is not ready for checkpoint recovery" }
                                    if (continuationPrepared != null) manager.resumeExact(bot.id, expectedToken, control.userText, input,
                                        WorkspaceContinuationDelivery.resumeCommit(app, continuationPrepared, requireNotNull(started), manager, token))
                                    else if (input == null) manager.resumeExact(bot.id, expectedToken, control.userText)
                                    else manager.resumeExact(bot.id, expectedToken, control.userText, input)
                                } else if (input == null) manager.sendUserMessageExact(bot.id, expectedToken, control.userText)
                                else manager.sendUserMessageExact(bot.id, expectedToken, control.userText, input)
                                if (bot.status() == CrewManager.Status.QUEUED || bot.status() == CrewManager.Status.RUNNING
                                    || bot.status() == CrewManager.Status.WAITING) {
                                    WorkspaceConversationInputStore.deliverPendingSupervisorIntentsAfterExplicitResume(
                                        app, scope.executionSessionId, manager, bot.id, token,
                                        Runnable { token.throwIfCancelled() })
                                }
                                keepalive()
                            }
                        }
                            }) throw java.util.concurrent.CancellationException("Native workspace control stopped before dispatch")
                        }
                        if (control.type == WorkspaceControlType.PAUSE) {
                            // No UI, assignment lock or transaction is held while owned work is cleaning up.
                            bot.awaitTermination()
                            check(!manager.hasPendingOwnedWork(bot)) { "Worker cleanup is not complete" }
                        }
                        // Do not accept a swallowed snapshot-listener failure as a durable control result.
                        val currentSnapshot = manager.missionSnapshots().first { it.missionId == assignment.missionId }
                        persistMissionSnapshot(app, currentSnapshot)
                        WorkspaceChanges.snapshotSaved(scope.executionSessionId, assignment.missionId)
                        store.completeControl(controlId, started!!.revision)

                        WorkspaceResultDelivery.schedule(app, scope.executionSessionId)
                        WorkspaceChanges.changed()
                        "${control.type}: completed for assignment ${control.assignmentId}. This receipt does not verify its work result."
                    } catch (failure: Exception) {
                        started?.let { claimed -> runCatching { store.markControlUncertain(controlId, claimed.revision) } }
                        WorkspaceChanges.changed()
                        throw failure
                    }
        }
    }

    companion object {
        /** Called before stopping Crew managers, so a pending native operation cannot restart them afterward. */
        @JvmStatic fun cancelPendingControls() = WorkspaceControlOwners.cancelPending()
        private val locks = ConcurrentHashMap<String, Any>()
        private val controlLocks = ConcurrentHashMap<String, Any>()
        internal fun controlDispatchLock(assignmentId: String): Any = controlLocks.getOrPut(assignmentId) { Any() }
        private val cleanup = Executors.newCachedThreadPool { runnable -> Thread(runnable, "workspace-control-cleanup").apply { isDaemon = true } }
        private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun workspaceJson(scope: WorkspaceRecord) = JSONObject().put("workspace_id", scope.workspaceId)
            .put("title", scope.title).put("created_at", scope.createdAt)
        private fun assignmentJson(assignment: WorkspaceAssignment, snapshot: CrewMissionSnapshot?, excerpt: Int): JSONObject {
            val bot = snapshot?.bots?.firstOrNull { it.id == assignment.botId }
            return JSONObject().put("assignment_id", assignment.assignmentId).put("workspace_id", assignment.workspaceId)
                .put("request_id", assignment.requestId).put("mission_id", assignment.missionId)
                .put("revision", assignment.revision).put("admission_state", assignment.state.name)
                .put("bot_id", assignment.botId ?: JSONObject.NULL).put("role", assignment.role)
                .put("status", if (assignment.needsReconciliation) "INTERRUPTED_UNCERTAIN" else bot?.status ?: "EVIDENCE_UNAVAILABLE")
                .put("mission_excerpt", assignment.missionText.take(excerpt))
                .put("result_excerpt", bot?.result?.take(excerpt) ?: JSONObject.NULL)
                .put("result_truncated", (bot?.result?.length ?: 0) > excerpt)
                .put("result_verified", false)
                .put("recovery_note", bot?.recoveryNote?.take(1000) ?: JSONObject.NULL)
                .put("no_replay", assignment.needsReconciliation)
        }
    }
}
