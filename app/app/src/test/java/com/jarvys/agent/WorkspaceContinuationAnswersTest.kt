package com.jarvys.agent

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ChatDbResult
import com.jarvys.agent.crew.CrewCoordinationEvidence
import com.jarvys.agent.crew.CrewMissionAccess
import com.jarvys.agent.workspaces.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** Real Room outbox and checkpoint CAS; no provider or resurrected coordination capability. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ArtifactOsShadow::class])
class WorkspaceContinuationAnswersTest {
    @get:Rule val storage = ChatTestStore(bridgeExecutorFactory = { ChatTestStore.directExecutorService() })

    private inner class Fixture {
        val files = storage.newFilesDirectory("continuation-answer")
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name) else File(files, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        val registry: WorkspaceRegistry
        val workspace: WorkspaceRecord
        val assignment: WorkspaceAssignment
        val reference: WorkspaceCoordinationReference
        val cycle = UUID.randomUUID().toString()
        val ledger: LocalRunStore
        val checkpoints: CrewCheckpointStore
        val answers = WorkspaceContinuationAnswers(context)
        init {
            storage.localRunStore(files)
            val bridge = ChatDatabaseBridge.getInstance(context, ChatTestStore.directExecutorService())
            FreshStorageBootstrap.ensure(context)
            assertTrue(bridge.ensureConversationSync(WorkspaceIdentity.CENTRAL_SESSION,"chat") is ChatDbResult.Success)
            registry = WorkspaceRegistry(context)
            workspace = registry.createWorkspace("workspace-${UUID.randomUUID()}","Itinerary")
            assertTrue(bridge.ensureConversationSync(workspace.executionSessionId,"workspace") is ChatDbResult.Success)
            val prepared = registry.prepareAssignment(workspace.workspaceId,"assignment","request","Plan a trip",
                "analyst","Ask for the travel date",listOf("board_read"),"Trip",CrewMissionAccess.STANDARD)
            val started = registry.markStarted(workspace.workspaceId,prepared.assignmentId,prepared.revision,"fixture")
            assignment = registry.bindBot(workspace.workspaceId,started.assignmentId,started.revision,"bot-answer")
            ledger = LocalRunStore(context)
            val evidence = CrewCoordinationEvidence.create(workspace.executionSessionId,assignment.missionId,
                "bot-answer",cycle,"question-date","QUESTION","What date?",emptyList(),1L)
            ledger.appendCrewCoordinationEvidence(evidence)
            reference = WorkspaceCoordinationReference.create(evidence,WorkspaceIdentity.CENTRAL_SESSION,
                workspace.workspaceId,assignment.assignmentId,assignment.requestId,assignment.revision)
            ledger.appendWorkspaceCoordinationReferenceIfAbsent(reference,Runnable {})
            ledger.appendWorkspaceCoordinationNoticeIfAbsent(reference,Runnable {})
            checkpoints = CrewCheckpointStore(files)
            val saved = checkpoints.saveConfirmed(workspace.executionSessionId,"bot-answer",JSONObject()
                .put("conversationId",workspace.executionSessionId).put("botId","bot-answer")
                .put("missionId",assignment.missionId).put("assignmentId",assignment.assignmentId)
                .put("cycleId",cycle).put("nativeWorkspaceCheckpoint",true).put("status","INTERRUPTED"),
                "native-workspace:${workspace.workspaceId}",CoreAgentLoop.Checkpoint.empty(),JSONObject())
            checkpoints.createCentralContinuation(saved,CentralContinuationRecord.create(workspace.workspaceId,
                assignment.assignmentId,assignment.requestId,assignment.missionId,"bot-answer",cycle))
        }
        fun retain(key: String = "answer-date", text: String = "June 4"): JSONObject = answers.retain(reference,key,text,
            assignment.revision,CancellationToken.uncancellable(),Runnable {})
        fun record(): JSONObject = checkpoints.load(workspace.executionSessionId,"bot-answer")!!.metadata
            .getJSONObject(CentralContinuationRecord.FIELD)
    }

    @Test fun retainedAnswerAfterRestartUsesExistingOutboxAndNeverGenericMessageRecovery() {
        val f = Fixture()
        assertTrue(f.answers.canHandle(f.reference))
        val receipt = f.retain()
        assertEquals("PREPARED",receipt.getString("state"))
        assertTrue(receipt.getBoolean("requires_explicit_resume"))
        assertFalse(receipt.getBoolean("incorporated_in_worker_checkpoint"))
        val binding = f.record().getJSONArray("answerBindings").getJSONObject(0)
        val row = f.ledger.readConversationMessageByStableId(f.workspace.executionSessionId,binding.getString("outboxStableId"))!!
        val provenance = row.getJSONObject("provenance")
        assertEquals("ANSWER",provenance.getString("kind"))
        assertEquals(f.cycle,provenance.getString("cycleId"))
        assertEquals(f.reference.referenceId,provenance.getString("referenceId"))
        assertEquals(f.reference.contentSha256,provenance.getString("contentSha256"))
        assertTrue(WorkspaceConversationInputStore.pendingSupervisorIntents(f.context,f.workspace.executionSessionId).isEmpty())
        assertEquals("PREPARED",WorkspaceContinuationAnswers(f.context).retain(f.reference,"answer-date","June 4",
            f.assignment.revision,CancellationToken.uncancellable(),Runnable {}).getString("state"))
        assertEquals(1,f.record().getJSONArray("answerBindings").length())
    }

    @Test fun conflictingAnswerAndStaleRevisionDoNotReplaceOriginalBinding() {
        val f = Fixture(); f.retain()
        val original = f.record().toString()
        assertThrows(IllegalStateException::class.java) { f.retain(text="July 5") }
        assertEquals(original,f.record().toString())
        assertThrows(IllegalStateException::class.java) {
            f.answers.retain(f.reference,"other","June 4",f.assignment.revision + 1,
                CancellationToken.uncancellable(),Runnable {})
        }
        assertEquals(original,f.record().toString())
    }

    @Test fun cancellationAndAdmissionRevocationCommitNoAnswer() {
        val f = Fixture(); val token=CancellationToken.cancellable();token.cancel()
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            f.answers.retain(f.reference,"answer","June 4",f.assignment.revision,token,Runnable {})
        }
        assertThrows(IllegalStateException::class.java) {
            f.answers.retain(f.reference,"answer","June 4",f.assignment.revision,
                CancellationToken.uncancellable(),Runnable { error("revoked") })
        }
        assertEquals(0,f.record().getJSONArray("answerBindings").length())
        assertTrue(f.ledger.readPendingInternalConversationInputs(f.workspace.executionSessionId).isEmpty())
    }

    @Test fun differentQuestionCycleCannotMintBinding() {
        val f = Fixture()
        val evidence= CrewCoordinationEvidence.create(f.workspace.executionSessionId,f.assignment.missionId,
            "bot-answer",UUID.randomUUID().toString(),"question-stale","QUESTION","Other date?",emptyList(),2L)
        f.ledger.appendCrewCoordinationEvidence(evidence)
        val ref= WorkspaceCoordinationReference.create(evidence,WorkspaceIdentity.CENTRAL_SESSION,
            f.workspace.workspaceId,f.assignment.assignmentId,f.assignment.requestId,f.assignment.revision)
        f.ledger.appendWorkspaceCoordinationReferenceIfAbsent(ref,Runnable {})
        f.ledger.appendWorkspaceCoordinationNoticeIfAbsent(ref,Runnable {})
        assertThrows(IllegalStateException::class.java) {
            f.answers.retain(ref,"answer","June 4",f.assignment.revision,CancellationToken.uncancellable(),Runnable {})
        }
        assertEquals(0,f.record().getJSONArray("answerBindings").length())
    }
    @Test fun correctionAndPendingStopRejectRetainedAnswerWithoutOutbox() {
        val f=Fixture()
        val control=f.registry.nativeControls().submit(UUID.randomUUID().toString(),f.workspace.workspaceId,
            f.assignment.assignmentId,"bot-answer",f.assignment.revision,WorkspaceControlType.PAUSE,"")
        assertThrows(IllegalStateException::class.java) { f.retain() }
        assertEquals(0,f.record().getJSONArray("answerBindings").length())
        val started=f.registry.markControlStarted(control.requestId,control.revision,"fixture")
        f.registry.completeControl(control.requestId,started.revision)
        assertThrows(IllegalStateException::class.java) { f.retain() }
        assertTrue(f.ledger.readPendingInternalConversationInputs(f.workspace.executionSessionId).isEmpty())
    }

    @Test fun ordinarySupervisorMessageRemainsSeparateAndReplayEligible() {
        val f=Fixture()
        WorkspaceConversationInputStore.appendSupervisorIntent(f.context,f.workspace.executionSessionId,
            f.assignment,"bot-answer","ordinary","Keep the itinerary concise",null)
        f.retain()
        val pending=WorkspaceConversationInputStore.pendingSupervisorIntents(f.context,f.workspace.executionSessionId)
        assertEquals(1,pending.size)
        assertEquals(ConversationInput.Kind.MESSAGE,pending.single().provenance.kind)
        assertEquals("Keep the itinerary concise",pending.single().content)
    }

    @Test fun retentionAndMissingIncorporationCannotClaimConsumption() {
        val f=Fixture(); f.retain()
        assertFalse(f.answers.reconcileIncorporations(f.workspace.executionSessionId,"bot-answer",
            CancellationToken.uncancellable(),Runnable {}))
        assertEquals("PREPARED",f.record().getJSONArray("answerBindings").getJSONObject(0).getString("deliveryState"))
        val row=f.record().getJSONArray("answerBindings").getJSONObject(0)
        val stored=f.ledger.readConversationMessageByStableId(f.workspace.executionSessionId,row.getString("outboxStableId"))!!
        val saved=f.ledger.readConversationInput(f.workspace.executionSessionId,stored.getString("messageId"))!!
        val source=ConversationInput(f.workspace.executionSessionId,saved.provenance.messageId,saved.content,saved.origin,saved.provenance)
        val fakeBinding=WorkspaceInputSources.continuationAnswer(source,UUID.randomUUID().toString(),
            WorkspaceContinuationAnswers.payloadDigest(source))
        assertThrows(IllegalStateException::class.java) { f.answers.resolveBindingSource(fakeBinding) }
        assertFalse(f.ledger.readConversationInputState(f.workspace.executionSessionId,stored.getString("messageId")) != null)
    }

}
