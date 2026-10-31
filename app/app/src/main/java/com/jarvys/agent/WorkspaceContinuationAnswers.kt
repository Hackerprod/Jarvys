package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.crew.CrewInputBinding
import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.workspaces.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Exact retained answers use the existing supervisor outbox, never the extinguished live question capability. */
internal class WorkspaceContinuationAnswers(context: Context) {
    private val app = context.applicationContext
    private val ledger get() = LocalRunStore(app)
    private val checkpoints get() = CrewCheckpointStore(app.filesDir)

    fun canHandle(reference: WorkspaceCoordinationReference): Boolean {
        val saved = checkpoints.load(reference.executionSessionId, reference.botId) ?: return false
        if (!saved.metadata.has(CentralContinuationRecord.FIELD)) return false
        record(saved.metadata, reference)
        return true
    }

    /** No mailbox/runtime access: an answer after process death is only retained, not executed. */
    fun retain(reference: WorkspaceCoordinationReference, key: String, text: String,
               expectedRevision: Long, token: CancellationToken, admission: Runnable): JSONObject =
        synchronized(WorkspaceCoordinator.controlDispatchLock(reference.assignmentId)) {
            require(key.isNotBlank() && text.isNotBlank())
            token.throwIfCancelled(); admission.run()
            val assignment = requireSource(reference)
            val saved = confirmed(reference)
            val current = record(saved.metadataJson(), reference)
            val outboxKey = UUID.nameUUIDFromBytes((current.getString("waitId") + "\u0000" +
                reference.messageId + "\u0000" + key).toByteArray(StandardCharsets.UTF_8)).toString()
            val bindings = current.getJSONArray("answerBindings")
            val existing = rows(bindings).firstOrNull { it.getString("questionReferenceId") == reference.referenceId }
            if (existing != null) {
                val expectedId = WorkspaceConversationInputStore.supervisorMessageId(reference.workspaceId,
                    reference.assignmentId, outboxKey)
                val prior = retainedInput(reference.executionSessionId, existing)
                check(existing.getString("outboxMessageId") == expectedId && prior.content == text &&
                    payloadDigest(prior) == existing.getString("outboxPayloadSha256")) {
                    "Question already has a different retained answer"
                }
                token.throwIfCancelled(); admission.run()
                return@synchronized receipt(existing)
            }
            check(assignment.revision == expectedRevision) { "Answer assignment revision changed" }
            requireNoCorrectionOrControl(reference, assignment)
            check(current.getString("dispatchState") !in setOf("CANCELLED", "STARTED", "ACKNOWLEDGED")) {
                "Continuation is not accepting new answers"
            }
            check(saved.metadataJson().optString("status") !in setOf("STOPPED", "DONE") &&
                current.isNull("selectedResult")) { "Terminal task cannot accept an answer" }
            check(questionCycleAllowed(current, reference.cycleId)) { "Question cycle is no longer linked" }
            token.throwIfCancelled(); admission.run()
            val input = WorkspaceConversationInputStore.appendSupervisorAnswerIntent(app,
                reference.executionSessionId, assignment, reference.botId, outboxKey, text, reference)
            val digest = payloadDigest(input)
            val binding = JSONObject().put("questionReferenceId", reference.referenceId)
                .put("questionMessageId", reference.messageId).put("questionSha256", reference.contentSha256)
                .put("originalCycleId", reference.cycleId).put("outboxMessageId", input.messageId)
                .put("outboxStableId", input.stableId()).put("outboxPayloadSha256", digest)
                .put("assignmentRevision", assignment.revision).put("admittedAssignmentRevision", 0L).put("admissionCycleId", "")
                .put("deliveryState", "PREPARED")
            bindings.put(binding)
            current.put("revision", current.getLong("revision") + 1)
            token.throwIfCancelled(); admission.run()
            checkpoints.updateCentralContinuation(saved, CentralContinuationRecord.fromJson(current))
            WorkspaceChanges.changed()
            receipt(binding)
        }

    /** Invoke only at native explicit-resume admission; an uncertain STARTED binding is never enqueued again. */
    fun deliverAfterExplicitResume(manager: CrewManager, botId: String, token: CancellationToken,
                                   admission: Runnable, nativeOwner: CancellationToken? = null) {
        val bot = requireNotNull(manager.bot(botId))
        val session = manager.conversationId()
        val first = checkpoints.load(session, botId) ?: return
        if (!first.metadata.has(CentralContinuationRecord.FIELD)) return
        val initial = CentralContinuationRecord.fromJson(first.metadata.getJSONObject(CentralContinuationRecord.FIELD)).toJson()
        val assignmentId = initial.getString("assignmentId")
        synchronized(WorkspaceCoordinator.controlDispatchLock(assignmentId)) {
            val references = rows(initial.getJSONArray("answerBindings")).map { it.getString("questionReferenceId") }
            for (referenceId in references) {
                token.throwIfCancelled(); admission.run()
                val ref = requireNotNull(ledger.readWorkspaceCoordinationReference(session, referenceId))
                val assigned = requireSource(ref)
                val saved = confirmed(ref)
                val data = record(saved.metadataJson(), ref)
                val row = rows(data.getJSONArray("answerBindings")).single { it.getString("questionReferenceId") == referenceId }
                if (row.getString("deliveryState") != "PREPARED") continue
                val expectedCycle = data.getString("expectedCycleId")
                val link = rows(data.getJSONArray("links")).lastOrNull {
                    it.getString("state") == "BOUND" && it.getString("newCycleId") == expectedCycle
                } ?: throw IllegalStateException("Answer requires its exact native resume link")
                check(questionCycleAllowed(data, row.getString("originalCycleId")))
                requireResumedAdmission(ref, assigned, row, link, bot, expectedCycle, nativeOwner)
                val input = retainedInput(ref.executionSessionId, row)
                check(payloadDigest(input) == row.getString("outboxPayloadSha256")) { "Retained answer changed" }
                row.put("admissionCycleId", expectedCycle).put("admittedAssignmentRevision", assigned.revision)
                    .put("deliveryState", "STARTED")
                data.put("revision", data.getLong("revision") + 1)
                token.throwIfCancelled(); admission.run()
                checkpoints.updateCentralContinuation(saved, CentralContinuationRecord.fromJson(data))
                val source = WorkspaceInputSources.continuationAnswer(input, expectedCycle, payloadDigest(input))
                val exactToken = bot.token
                manager.sendContinuationAnswerExact(bot.id, exactToken, input.content, input.provenance, source, Runnable {
                    token.throwIfCancelled(); admission.run()
                    requireResumedAdmission(ref, requireSource(ref), row, link, bot, expectedCycle, nativeOwner)
                })
            }
        }
    }

    /** Derives incorporation from the exact confirmed checkpoint, never from mailbox admission alone. */
    fun reconcileIncorporations(executionSessionId: String, botId: String, token: CancellationToken,
                                admission: Runnable): Boolean {
        val initial = checkpoints.load(executionSessionId, botId) ?: return false
        if (!initial.metadata.has(CentralContinuationRecord.FIELD)) return false
        val identity = CentralContinuationRecord.fromJson(initial.metadata.getJSONObject(CentralContinuationRecord.FIELD)).toJson()
        return synchronized(WorkspaceCoordinator.controlDispatchLock(identity.getString("assignmentId"))) {
            token.throwIfCancelled(); admission.run()
            val saved = requireNotNull(checkpoints.confirmDurable(executionSessionId, botId, initial.scopeIdentity))
            val data = CentralContinuationRecord.fromJson(saved.metadataJson().getJSONObject(CentralContinuationRecord.FIELD)).toJson()
            var changed = false
            for (row in rows(data.getJSONArray("answerBindings"))) {
                if (row.getString("deliveryState") != "STARTED") continue
                val proof = saved.loop.incorporations[row.getString("outboxMessageId")] ?: continue
                resolveBindingSource(proof.binding)
                check(proof.binding.sourceKind == CrewInputBinding.SourceKind.CONTINUATION_ANSWER &&
                    proof.binding.admissionCycleId == row.getString("admissionCycleId"))
                row.put("deliveryState", "INCORPORATED")
                changed = true
            }
            if (changed) {
                data.put("revision", data.getLong("revision") + 1)
                token.throwIfCancelled(); admission.run()
                checkpoints.updateCentralContinuation(saved, CentralContinuationRecord.fromJson(data))
                WorkspaceChanges.changed()
            }
            changed
        }
    }

    /** Historical incorporation validation: current revision may advance, but original source cannot change. */
    fun resolveBindingSource(input: CrewInputBinding): Pair<WorkspaceAssignment, String> {
        check(input.sourceKind == CrewInputBinding.SourceKind.CONTINUATION_ANSWER)
        val snapshot = requireNotNull(checkpoints.confirmDurable(input.executionSessionId, input.botId,
            "native-workspace:${input.workspaceId}"))
        val data = CentralContinuationRecord.fromJson(snapshot.metadataJson().getJSONObject(CentralContinuationRecord.FIELD)).toJson()
        val row = rows(data.getJSONArray("answerBindings")).single {
            it.getString("outboxMessageId") == input.sourceId
        }
        check(row.getString("deliveryState") in setOf("STARTED", "INCORPORATED") &&
            row.getString("admissionCycleId") == input.admissionCycleId)
        val ref = requireNotNull(ledger.readWorkspaceCoordinationReference(input.executionSessionId,
            row.getString("questionReferenceId")))
        record(snapshot.metadataJson(), ref)
        val assignment = requireSource(ref)
        val link = rows(data.getJSONArray("links")).single {
            it.getString("state") == "BOUND" && it.getString("newCycleId") == input.admissionCycleId
        }
        WorkspaceRegistry(app).use { registry ->
            val control = requireNotNull(registry.getControl(link.getString("controlId")))
            check(control.type == WorkspaceControlType.RESUME &&
                control.state in setOf(WorkspaceControlState.STARTED, WorkspaceControlState.COMPLETE) &&
                control.workspaceId == input.workspaceId && control.assignmentId == input.assignmentId &&
                control.botId == input.botId && control.admittedAssignmentRevision == row.getLong("admittedAssignmentRevision") &&
                link.getLong("admittedAssignmentRevision") == row.getLong("admittedAssignmentRevision") &&
                assignment.revision >= row.getLong("admittedAssignmentRevision")) {
                "Answer incorporation has no exact native admission control"
            }
        }
        val retained = retainedInput(input.executionSessionId, row)
        check(row.getString("questionMessageId") == ref.messageId &&
            row.getString("questionSha256") == ref.contentSha256 &&
            row.getString("originalCycleId") == ref.cycleId &&
            payloadDigest(retained) == row.getString("outboxPayloadSha256"))
        val expected = WorkspaceInputSources.continuationAnswer(retained, input.admissionCycleId, payloadDigest(retained))
        check(expected.samePayload(input)) { "Retained answer does not match incorporation source" }
        return assignment to ref.referenceId
    }

    private fun requireSource(ref: WorkspaceCoordinationReference): WorkspaceAssignment = WorkspaceRegistry(app).use { registry ->
        check(ref.kind == "QUESTION" || ref.kind == "BLOCKER") { "Only a question or blocker accepts an answer" }
        val workspace = requireNotNull(registry.getWorkspace(ref.workspaceId))
        check(workspace.executionSessionId == ref.executionSessionId &&
            workspace.ownerCentralSessionId == WorkspaceIdentity.CENTRAL_SESSION &&
            ref.ownerCentralSessionId == WorkspaceIdentity.CENTRAL_SESSION)
        val assignment = requireNotNull(registry.getAssignment(ref.workspaceId, ref.assignmentId))
        check(assignment.state == WorkspaceAssignmentState.BOUND && assignment.botId == ref.botId &&
            assignment.missionId == ref.missionId && assignment.requestId == ref.requestId)
        val persisted = requireNotNull(ledger.readWorkspaceCoordinationReference(ref.executionSessionId, ref.referenceId))
        check(persisted.toJson().toString() == ref.toJson().toString())
        ledger.requireWorkspaceCoordinationDisclosureLive(ref, true)
        check(ref.matchesEvidence(requireNotNull(ledger.readCrewCoordinationEvidence(ref.executionSessionId, ref.evidenceId))))
        assignment
    }

    private fun requireNoCorrectionOrControl(ref: WorkspaceCoordinationReference, assignment: WorkspaceAssignment) {
        WorkspaceRegistry(app).use { registry ->
            val correction = registry.getLatestCorrection(ref.workspaceId, ref.assignmentId)?.admittedAssignmentRevision
            check(correction == null || correction <= ref.observedAssignmentRevision) { "A correction superseded the question" }
            check(registry.getUnsettledControl(ref.workspaceId, ref.assignmentId) == null &&
                registry.getPendingControl(ref.workspaceId, ref.assignmentId, assignment.revision) == null) {
                "Native control takes precedence over answer retention"
            }
        }
        check(!WorkspaceControlOwners.hasNativeControlOwner(ref.assignmentId))
    }

    private fun requireResumedAdmission(ref: WorkspaceCoordinationReference, assignment: WorkspaceAssignment,
                                       answer: JSONObject, link: JSONObject, bot: CrewManager.Bot,
                                       cycle: String, nativeOwner: CancellationToken?) {
        check(!bot.requiresExplicitResume() && bot.status() in setOf(CrewManager.Status.QUEUED,
            CrewManager.Status.RUNNING, CrewManager.Status.WAITING) && bot.cycleId() == cycle &&
            bot.assignmentId() == ref.assignmentId && bot.missionId == ref.missionId)
        check(!WorkspaceControlOwners.hasNativeControlOwner(ref.assignmentId, nativeOwner))
        WorkspaceRegistry(app).use { registry ->
            val control = requireNotNull(registry.getControl(link.getString("controlId")))
            check(control.type == WorkspaceControlType.RESUME && control.state in setOf(WorkspaceControlState.STARTED,
                WorkspaceControlState.COMPLETE) && control.assignmentId == ref.assignmentId && control.botId == ref.botId &&
                control.admittedAssignmentRevision == assignment.revision &&
                link.getLong("admittedAssignmentRevision") == assignment.revision)
            check(registry.getPendingControl(ref.workspaceId, ref.assignmentId, assignment.revision) == null)
            val unsettled = registry.getUnsettledControl(ref.workspaceId, ref.assignmentId)
            check(unsettled == null || unsettled.requestId == control.requestId && unsettled.state == WorkspaceControlState.STARTED)
            val correction = registry.getLatestCorrection(ref.workspaceId, ref.assignmentId)?.admittedAssignmentRevision
            check(correction == null || correction <= answer.getLong("assignmentRevision"))
        }
    }

    private fun confirmed(ref: WorkspaceCoordinationReference): CrewCheckpointStore.ConfirmedSnapshot =
        requireNotNull(checkpoints.confirmDurable(ref.executionSessionId, ref.botId, "native-workspace:${ref.workspaceId}"))

    private fun record(metadata: JSONObject, ref: WorkspaceCoordinationReference): JSONObject {
        val data = CentralContinuationRecord.fromJson(metadata.getJSONObject(CentralContinuationRecord.FIELD)).toJson()
        check(data.getString("ownerCentralSessionId") == ref.ownerCentralSessionId &&
            data.getString("executionSessionId") == ref.executionSessionId && data.getString("workspaceId") == ref.workspaceId &&
            data.getString("assignmentId") == ref.assignmentId && data.getString("requestId") == ref.requestId &&
            data.getString("missionId") == ref.missionId && data.getString("botId") == ref.botId)
        return data
    }

    private fun questionCycleAllowed(data: JSONObject, cycle: String): Boolean =
        data.getString("expectedCycleId") == cycle || rows(data.getJSONArray("links")).any {
            it.getString("state") == "BOUND" && it.getString("oldCycleId") == cycle
        }

    private fun retainedInput(session: String, row: JSONObject): ConversationInput {
        val stored = requireNotNull(ledger.readConversationMessageByStableId(session, row.getString("outboxStableId")))
        val saved = requireNotNull(ledger.readConversationInput(session, stored.getString("messageId")))
        check(saved.provenance?.messageId == row.getString("outboxMessageId") &&
            saved.provenance?.kind == ConversationInput.Kind.ANSWER)
        return ConversationInput(session, requireNotNull(saved.provenance).messageId, saved.content, saved.origin, saved.provenance)
    }

    companion object {
        private fun rows(array: JSONArray): List<JSONObject> = (0 until array.length()).map(array::getJSONObject)
        private fun receipt(row: JSONObject): JSONObject = JSONObject().put("state", row.getString("deliveryState"))
            .put("admission_committed", true).put("delivery_confirmed", row.getString("deliveryState") == "INCORPORATED")
            .put("incorporated_in_worker_checkpoint", row.getString("deliveryState") == "INCORPORATED")
            .put("mailbox_message_id", row.getString("outboxMessageId"))
            .put("requires_explicit_resume", row.getString("deliveryState") == "PREPARED")

        internal fun payloadDigest(input: ConversationInput): String {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                listOf("workspace-continuation-answer-v1", input.conversationId, input.messageId, input.content,
                    input.origin.name, requireNotNull(input.provenance).toJson().toString()).forEach { value ->
                    out.writeInt(value.length); value.forEach { out.writeChar(it.code) }
                }
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
