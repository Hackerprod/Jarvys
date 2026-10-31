package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.coding.ProjectScopeStore
import com.jarvys.agent.crew.CrewInputBinding
import com.jarvys.agent.workspaces.*

/** Confirms saved input evidence only. Never restores a mailbox, resumes work or calls a model. */
internal class WorkspaceInputIncorporationDelivery(
    context: Context,
    private val ledger: LocalRunStore = LocalRunStore(context),
) {
    private val app = context.applicationContext

    private fun scope(workspaceId: String): WorkspaceRecord = WorkspaceRegistry(app).use { registry ->
        requireNotNull(registry.getWorkspace(workspaceId)).also {
            check(it.ownerCentralSessionId == WorkspaceIdentity.CENTRAL_SESSION &&
                it.executionSessionId == WorkspaceIdentity.executionSessionId(workspaceId))
        }
    }

    private fun source(input: CrewInputBinding): Pair<WorkspaceAssignment, String> {
        val owner = scope(input.workspaceId)
        check(owner.ownerCentralSessionId == input.ownerCentralSessionId &&
            owner.executionSessionId == input.executionSessionId)
        return WorkspaceRegistry(app).use { registry ->
            val assignment = requireNotNull(registry.getAssignment(input.workspaceId, input.assignmentId))
            check(assignment.state == WorkspaceAssignmentState.BOUND && assignment.botId == input.botId &&
                assignment.missionId == input.missionId)
            val referenceId: String
            val expected = when (input.sourceKind) {
                CrewInputBinding.SourceKind.WORKSPACE_REPLY -> {
                    // Bounded ledger lookup uses the immutable source key, never body matching.
                    val receiptReference = findReply(input)
                    check(receiptReference.reference.assignmentId == assignment.assignmentId &&
                        receiptReference.reference.requestId == assignment.requestId)
                    referenceId = receiptReference.reference.referenceId
                    WorkspaceInputSources.reply(receiptReference)
                }
                CrewInputBinding.SourceKind.CONTINUATION_ANSWER -> {
                    val resolved = WorkspaceContinuationAnswers(app).resolveBindingSource(input)
                    check(resolved.first.assignmentId == assignment.assignmentId)
                    referenceId = resolved.second
                    input // resolveBindingSource checks exact outbox/checkpoint source payload.
                }
                CrewInputBinding.SourceKind.TREE_REPORT, CrewInputBinding.SourceKind.TREE_ANSWER ->
                    error("Tree inputs require canonical Registry incorporation validation")
                CrewInputBinding.SourceKind.NATIVE_CORRECT -> {
                    val control = requireNotNull(registry.getControl(input.sourceId))
                    referenceId = control.requestId
                    WorkspaceInputSources.correction(owner, assignment, control, input.admissionCycleId)
                }
            }
            check(expected.samePayload(input)) { "Input source does not match saved checkpoint evidence" }
            assignment to referenceId
        }
    }

    private fun findReply(input: CrewInputBinding): WorkspaceCoordinationReply {
        val reply = requireNotNull(ledger.readWorkspaceCoordinationReplyById(input.ownerCentralSessionId, input.sourceId)) {
            "Saved reply source is unavailable"
        }
        ledger.requireWorkspaceCoordinationDisclosureLive(reply.reference, false)
        return reply
    }

    fun reconcile(workspaceId: String, token: CancellationToken, admittedRead: Runnable? = null,
                  admittedInput: CrewInputBinding? = null): Boolean {
        require((admittedRead == null) == (admittedInput == null))
        if (admittedInput != null) require(admittedInput.sourceKind == CrewInputBinding.SourceKind.WORKSPACE_REPLY &&
            admittedInput.workspaceId == workspaceId)
        var insertedAny = false
        try {
            token.throwIfCancelled()
            admittedRead?.run()
            val owner = scope(workspaceId)
            if (!WorkspaceRegistry(app).use { it.hasBoundAssignments(workspaceId) }) return false
            fun admit() {
                token.throwIfCancelled()
                admittedRead?.run()
            }
            admit()
            // Read-only admission rejects missing identity instead of repairing or creating a project.
            val project by lazy { ProjectScopeStore(app.filesDir).openExisting(owner.executionSessionId) }
            val identity by lazy { project.durableIdentity() }
            var after: String? = null
            do {
                val assignments = WorkspaceRegistry(app).use { it.listAssignments(workspaceId, PAGE, after) }
                for (assignment in assignments) {
                    token.throwIfCancelled(); admit()
                    if (assignment.state != WorkspaceAssignmentState.BOUND || assignment.botId == null) continue
                    if (admittedInput != null && admittedInput.assignmentId != assignment.assignmentId) continue
                    val checkpoints = CrewCheckpointStore(app.filesDir)
                    val candidate = checkpoints.load(owner.executionSessionId, assignment.botId) ?: continue
                    if (candidate.loop.incorporations.isEmpty()) continue
                    if (admittedInput != null && candidate.loop.incorporations.values.none {
                            it.binding.samePayload(admittedInput) }) continue
                    val native = candidate.metadata.optBoolean("nativeWorkspaceCheckpoint", false)
                    val checkpointIdentity = if (native) "native-workspace:$workspaceId" else identity
                    val saved = checkpoints.confirmDurable(owner.executionSessionId,
                        assignment.botId, checkpointIdentity) ?: continue
                    if (native) CrewCheckpointCoordinator.requireNativeIdentity(app, owner.executionSessionId, saved)
                    check(saved.metadataJson().getString("missionId") == assignment.missionId)
                    WorkspaceTreeDelivery.reconcileCheckpoint(app,saved)
                    for (evidence in saved.loop.incorporations.values) {
                        val input = evidence.binding
                        if(input.sourceKind in setOf(CrewInputBinding.SourceKind.TREE_REPORT,CrewInputBinding.SourceKind.TREE_ANSWER)) continue
                        if (admittedInput != null && !input.samePayload(admittedInput)) continue
                        if (input.workspaceId != workspaceId || input.assignmentId != assignment.assignmentId) continue
                        check(input.executionSessionId == owner.executionSessionId && input.botId == assignment.botId &&
                            input.missionId == assignment.missionId)
                        val (current, referenceId) = source(input)
                        val row = WorkspaceInputIncorporation.create(
                            kind(input.sourceKind), input.sourceId, referenceId, input.mailboxMessageId,
                            input.ownerCentralSessionId, input.workspaceId, input.executionSessionId,
                            input.assignmentId, current.requestId, input.missionId, input.botId,
                            input.admissionCycleId, evidence.firstIncorporationCycleId, input.sourcePayloadSha256,
                            input.deliverySha256, evidence.transportKind.name, evidence.transportSha256,
                            saved.payloadSha256, saved.savedAtMs, System.currentTimeMillis())
                        synchronized(DeliveredArtifactStore.DELIVERY_ACTION_LOCK) {
                            synchronized(WorkspaceCoordinator.controlDispatchLock(input.assignmentId)) {
                                token.throwIfCancelled(); admit()
                                source(input)
                                if (native) CrewCheckpointCoordinator.requireNativeIdentity(app, owner.executionSessionId, saved)
                                else check(project.durableIdentity() == identity)
                                var inserted = false
                                if (!token.runIfActive {
                                    inserted = ledger.appendWorkspaceInputIncorporationIfAbsentAndReport(row, Runnable {
                                        token.throwIfCancelled(); admit()
                                    })
                                }) token.throwIfCancelled()
                                insertedAny = insertedAny || inserted
                            }
                        }
                    }
                    if (native) WorkspaceContinuationAnswers(app).reconcileIncorporations(owner.executionSessionId,
                        assignment.botId, token, Runnable { admit() })
                }
                after = assignments.lastOrNull()?.assignmentId
            } while (assignments.size == PAGE)
            return insertedAny
        } finally {
            if (insertedAny) WorkspaceChanges.changed()
        }
    }

    fun read(input: CrewInputBinding, admission: Runnable): WorkspaceInputIncorporation? {
        admission.run(); source(input)
        val receipt = ledger.readWorkspaceInputIncorporation(input.ownerCentralSessionId, kind(input.sourceKind), input.sourceId)
        if (receipt != null) {
            val (assignment, referenceId) = source(input)
            check(receipt.sourceKind == kind(input.sourceKind) && receipt.sourceId == input.sourceId &&
                receipt.sourceReferenceId == referenceId && receipt.ownerCentralSessionId == input.ownerCentralSessionId &&
                receipt.workspaceId == input.workspaceId && receipt.executionSessionId == input.executionSessionId &&
                receipt.assignmentId == input.assignmentId && receipt.requestId == assignment.requestId &&
                receipt.missionId == input.missionId && receipt.botId == input.botId &&
                receipt.sourcePayloadSha256 == input.sourcePayloadSha256 &&
                receipt.deliveredContentSha256 == input.deliverySha256 && receipt.mailboxMessageId == input.mailboxMessageId &&
                receipt.admissionCycleId == input.admissionCycleId)
            admission.run()
            ledger.requireWorkspaceInputIncorporationDisclosureLive(receipt)
        } else admission.run()
        return receipt
    }

    /** Native metadata only; dispatch uncertainty is never erased. */
    fun correctionNote(workspaceId: String, assignmentId: String): String? {
            val owner = scope(workspaceId)
            val (assignment, control) = WorkspaceRegistry(app).use { registry ->
                requireNotNull(registry.getAssignment(workspaceId, assignmentId)) to
                    registry.getLatestCorrection(workspaceId, assignmentId)
            }
            if (control == null || control.state == WorkspaceControlState.PENDING) return null
            WorkspaceInputSources.validateCorrection(owner, assignment, control)
            val receipt = ledger.readWorkspaceInputIncorporation(owner.ownerCentralSessionId,
                WorkspaceInputIncorporation.SourceKind.NATIVE_CORRECT, control.requestId)
            if (receipt != null) read(WorkspaceInputSources.correction(owner, assignment, control,
                receipt.admissionCycleId), Runnable { })
            return AppLanguageRuntime.localizedContext(app).getString(if (receipt != null)
                R.string.workspace_correction_checkpoint_incorporated else R.string.workspace_correction_checkpoint_unverified)
    }

    companion object {
        private const val PAGE = 50
        private fun kind(kind: CrewInputBinding.SourceKind) = when (kind) {
            CrewInputBinding.SourceKind.WORKSPACE_REPLY -> WorkspaceInputIncorporation.SourceKind.REPLY
            CrewInputBinding.SourceKind.NATIVE_CORRECT -> WorkspaceInputIncorporation.SourceKind.NATIVE_CORRECT
            CrewInputBinding.SourceKind.CONTINUATION_ANSWER -> WorkspaceInputIncorporation.SourceKind.CONTINUATION_ANSWER
            CrewInputBinding.SourceKind.TREE_REPORT, CrewInputBinding.SourceKind.TREE_ANSWER -> error("Tree inputs use canonical Registry receipts")
        }
    }
}
