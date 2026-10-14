package com.jarvys.agent

import android.app.Application
import com.jarvys.agent.coding.ProjectScopeStore
import com.jarvys.agent.crew.*
import com.jarvys.agent.workspaces.*
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real registry, checkpoint envelope and ledger; no worker, model, provider or effect is invoked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkspaceInputIncorporationDeliveryTest {
    private lateinit var app: Application
    private lateinit var registry: WorkspaceRegistry
    private lateinit var ledger: LocalRunStore
    private lateinit var scope: WorkspaceRecord
    private lateinit var assignment: WorkspaceAssignment
    private lateinit var delivery: WorkspaceInputIncorporationDelivery
    private val admission = Runnable {}
    private val cycle = UUID.randomUUID().toString()

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
        File(app.filesDir, "jarvys").deleteRecursively()
        registry = WorkspaceRegistry(app); ledger = LocalRunStore(app)
        scope = registry.createWorkspace("input-proof", "Synthetic workspace")
        val pending = registry.prepareAssignment(scope.workspaceId, "assignment", "source-request", "Synthetic request",
            "coding", "Synthetic mission", emptyList(), null, CrewMissionAccess.STANDARD)
        val started = registry.markStarted(scope.workspaceId, pending.assignmentId, pending.revision, "synthetic-process")
        assignment = registry.bindBot(scope.workspaceId, started.assignmentId, started.revision, "synthetic-bot")
        ledger.appendConversationMessage(scope.executionSessionId, "user", "Synthetic worker ledger")
        ledger.appendConversationMessage(WorkspaceIdentity.CENTRAL_SESSION, "user", "Synthetic central ledger")
        delivery = WorkspaceInputIncorporationDelivery(app, ledger)
    }
    @After fun close() { registry.close(); app.deleteDatabase(WorkspaceRegistry.DATABASE_NAME) }

    private fun correction(text: String = "  Exact correction\n") : Pair<WorkspaceUserControl, CrewInputBinding> {
        val control = registry.submitNativeControl(UUID.randomUUID().toString(), scope.workspaceId, assignment.assignmentId,
            requireNotNull(assignment.botId), assignment.revision, WorkspaceControlType.CORRECT, text, admission)
        val started = registry.markControlStarted(control.requestId, control.revision, "synthetic-process")
        assignment = requireNotNull(registry.getAssignment(scope.workspaceId, assignment.assignmentId))
        return started to WorkspaceInputSources.correction(scope, assignment, started, cycle)
    }
    private fun save(binding: CrewInputBinding, firstCycle: String = cycle, withProof: Boolean = true) {
        val proof = CrewInputIncorporation(binding, firstCycle, CrewInputIncorporation.TransportKind.USER,
            CrewInputIncorporation.transportSha256("  Exact correction\n"))
        val checkpoint = CoreAgentLoop.Checkpoint(listOf(ConversationTurn("user", "  Exact correction\n")),
            setOf(binding.mailboxMessageId), emptyMap(), 0,
            if (withProof) mapOf(binding.mailboxMessageId to proof) else emptyMap())
        val identity = ProjectScopeStore(app.filesDir).open(scope.executionSessionId).durableIdentity()
        CrewCheckpointStore(app.filesDir).save(scope.executionSessionId, requireNotNull(assignment.botId),
            JSONObject().put("conversationId", scope.executionSessionId).put("botId", assignment.botId)
                .put("missionId", assignment.missionId), identity, checkpoint, JSONObject())
    }
    private fun reconcile() = delivery.reconcile(scope.workspaceId, CancellationToken.uncancellable())
    private fun receipt(binding: CrewInputBinding) = ledger.readWorkspaceInputIncorporation(WorkspaceIdentity.CENTRAL_SESSION,
        WorkspaceInputIncorporation.SourceKind.NATIVE_CORRECT, binding.sourceId)

    @Test fun durableProofDoesNotRewriteUncertainDispatchAndDeduplicatesLaterCheckpoint() {
        val (control, binding) = correction(); save(binding)
        registry.markControlUncertain(control.requestId, control.revision)
        reconcile()
        val first = requireNotNull(receipt(binding))
        assertEquals(WorkspaceControlState.UNCERTAIN, registry.getControl(control.requestId)?.state)
        assertEquals(cycle, first.firstIncorporationCycleId)
        save(binding); reconcile()
        assertEquals(first.checkpointPayloadSha256, receipt(binding)?.checkpointPayloadSha256)
        assertEquals(first.recordedAtMillis, receipt(binding)?.recordedAtMillis)
        assertFalse(ledger.readConversationContext(WorkspaceIdentity.CENTRAL_SESSION).contains(binding.sourcePayloadSha256))
        assertEquals(1, ledger.readConversationMessages(WorkspaceIdentity.CENTRAL_SESSION).size)
    }

    @Test fun boundWorkerWithoutCheckpointDoesNotCreateProjectOrReceipt() {
        reconcile()
        assertFalse(File(app.filesDir, "jarvys/coding-projects").exists())
        assertTrue(ledger.listWorkspaceInputIncorporations(WorkspaceIdentity.CENTRAL_SESSION, 50, null).isEmpty())
    }

    @Test fun appliedIdsWithoutNewEvidenceRemainUnverified() {
        val (_, binding) = correction(); save(binding, withProof = false); reconcile()
        assertNull(receipt(binding))
    }

    @Test fun durableExactCheckpointProofProducesAutomaticReceipt() {
        val (_, binding) = correction(); save(binding)
        reconcile()
        assertNotNull(receipt(binding))
    }

    @Test fun exactReplyReconciliationSelectsOnlyItsMatchingInputProof() {
        val (_, native) = correction()
        fun reply(key: String): CrewInputBinding {
            val evidence = CrewCoordinationEvidence.create(scope.executionSessionId, assignment.missionId,
                requireNotNull(assignment.botId), cycle, UUID.randomUUID().toString(), "QUESTION", key,
                emptyList(), 1234L)
            ledger.appendCrewCoordinationEvidence(evidence, admission)
            val reference = WorkspaceCoordinationReference.create(evidence, scope.ownerCentralSessionId,
                scope.workspaceId, assignment.assignmentId, assignment.requestId, assignment.revision)
            ledger.appendWorkspaceCoordinationReferenceIfAbsent(reference, admission)
            ledger.appendWorkspaceCoordinationNoticeIfAbsent(reference, admission)
            val prepared = ledger.prepareWorkspaceCoordinationReply(WorkspaceCoordinationReply.create(reference,
                key, "answer $key", "central-$key", "Synthetic request", 1234L), admission)
            return WorkspaceInputSources.reply(ledger.markWorkspaceCoordinationReplyStarted(prepared, admission))
        }
        val selected = reply("selected")
        val other = reply("other")
        val proofs = listOf(native, selected, other).associate { input ->
            input.mailboxMessageId to CrewInputIncorporation(input, cycle,
                if (input.sourceKind == CrewInputBinding.SourceKind.NATIVE_CORRECT)
                    CrewInputIncorporation.TransportKind.USER else CrewInputIncorporation.TransportKind.INBOX,
                CrewInputIncorporation.transportSha256("Synthetic already-appended transport"))
        }
        val identity = ProjectScopeStore(app.filesDir).open(scope.executionSessionId).durableIdentity()
        CrewCheckpointStore(app.filesDir).save(scope.executionSessionId, requireNotNull(assignment.botId),
            JSONObject().put("missionId", assignment.missionId), identity,
            CoreAgentLoop.Checkpoint(emptyList(), proofs.keys, emptyMap(), -1, proofs), JSONObject())
        delivery.reconcile(scope.workspaceId, CancellationToken.uncancellable(), Runnable {}, selected)
        val receipts = ledger.listWorkspaceInputIncorporations(WorkspaceIdentity.CENTRAL_SESSION, 50, null)
        assertEquals(listOf(selected.sourceId), receipts.map { it.sourceId })
        assertNull(receipt(native))
        assertNull(ledger.readWorkspaceInputIncorporation(WorkspaceIdentity.CENTRAL_SESSION,
            WorkspaceInputIncorporation.SourceKind.REPLY, other.sourceId))
        assertThrows(IllegalArgumentException::class.java) {
            delivery.reconcile(scope.workspaceId, CancellationToken.uncancellable(), Runnable {}, native)
        }
    }

    @Test fun cancelledReconciliationDoesNotAcknowledgeOrReplay() {
        val (_, binding) = correction(); save(binding)
        val token = CancellationToken.cancellable(); token.cancel()
        assertThrows(java.util.concurrent.CancellationException::class.java) { delivery.reconcile(scope.workspaceId, token) }
        assertNull(receipt(binding))
    }

    @Test fun changedSourcePayloadCannotGainReceiptAndWhitespaceIsExact() {
        val (control, binding) = correction()
        val forged = WorkspaceInputSources.correction(scope, assignment, control.copy(userText = control.userText.trim()), cycle)
        assertNotEquals(binding.deliverySha256, forged.deliverySha256)
        assertNotEquals(binding.sourcePayloadSha256, forged.sourcePayloadSha256)
        save(forged)
        assertThrows(IllegalStateException::class.java) { reconcile() }
        assertNull(receipt(binding))
    }

    @Test fun deletionInFinalAdmissionPreventsPositiveDisclosure() {
        val (_, binding) = correction(); save(binding); reconcile()
        var admissions = 0
        assertThrows(Exception::class.java) {
            delivery.read(binding, Runnable {
                admissions++
                if (admissions == 2) ledger.deleteConversation(scope.executionSessionId)
            })
        }
        assertEquals(2, admissions)
    }

    @Test fun copiedNativeHashesWithAnotherAssignmentAreRejectedAtProjection() {
        val (_, binding) = correction()
        val forged = WorkspaceInputIncorporation.create(WorkspaceInputIncorporation.SourceKind.NATIVE_CORRECT,
            binding.sourceId, binding.sourceId, binding.mailboxMessageId, binding.ownerCentralSessionId,
            binding.workspaceId, binding.executionSessionId, UUID.randomUUID().toString(), assignment.requestId,
            binding.missionId, binding.botId, binding.admissionCycleId, cycle, binding.sourcePayloadSha256,
            binding.deliverySha256, "USER", "a".repeat(64), "b".repeat(64), 1234L, 1235L)
        ledger.appendWorkspaceInputIncorporationIfAbsent(forged, admission)
        assertThrows(IllegalStateException::class.java) { delivery.read(binding, admission) }
    }

    @Test fun pendingResumeAndNonNativeSourcesCannotBecomeCorrectionProof() {
        val (control, binding) = correction()
        for (invalid in listOf(control.copy(state = WorkspaceControlState.PENDING),
            control.copy(type = WorkspaceControlType.RESUME), control.copy(origin = "MODEL"),
            control.copy(admittedAssignmentRevision = null))) {
            assertThrows(IllegalStateException::class.java) {
                WorkspaceInputSources.correction(scope, assignment, invalid, cycle)
            }
        }
        val updated = control.copy(state = WorkspaceControlState.COMPLETE, revision = control.revision + 1,
            updatedAt = control.updatedAt + 1)
        assertEquals(binding.sourcePayloadSha256,
            WorkspaceInputSources.correction(scope, assignment, updated, cycle).sourcePayloadSha256)
    }

    @Test fun deletedSourceNeverReappearsDuringReceiptReconciliation() {
        val (_, binding) = correction(); save(binding)
        ledger.deleteConversation(scope.executionSessionId)
        // Deletion may remove the checkpoint entirely; either rejection or no candidate is honest.
        runCatching { reconcile() }
        assertNull(runCatching { receipt(binding) }.getOrNull())
        assertTrue(ConversationMetadataStore(app.filesDir).read(scope.executionSessionId).deleted)
    }
}
