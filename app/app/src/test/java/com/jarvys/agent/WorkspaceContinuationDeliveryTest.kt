package com.jarvys.agent

import com.jarvys.agent.crew.*
import com.jarvys.agent.workspaces.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ArtifactOsShadow::class])
class WorkspaceContinuationDeliveryTest {
    private val storage = ChatTestStore()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(storage).around(TestSecretStoreRule())

    private inner class Episode(val f: WorkspaceMemoryFixture) {
        val workspace = f.createProject("Trip result")
        val cycle = UUID.randomUUID().toString()
        val bot = "native-planner"
        val checkpoint = CrewCheckpointStore(f.files)
        val assignment = WorkspaceRegistry(f.context).use { registry ->
            val p = registry.prepareAssignment(workspace.workspaceId, "trip-result", "trip-request", "Plan a trip",
                "analyst", "Prepare itinerary", listOf("board_read"), "Planner", CrewMissionAccess.READ_ONLY)
            val started = registry.markStarted(workspace.workspaceId, p.assignmentId, p.revision, "fixture")
            registry.bindBot(workspace.workspaceId, p.assignmentId, started.revision, bot)
        }
        init {
            val saved = checkpoint.saveConfirmed(workspace.executionSessionId, bot, JSONObject()
                .put("conversationId",workspace.executionSessionId).put("botId",bot)
                .put("missionId",assignment.missionId).put("assignmentId",assignment.assignmentId)
                .put("cycleId",cycle).put("nativeWorkspaceCheckpoint",true).put("status","DONE"),
                "native-workspace:${workspace.workspaceId}",CoreAgentLoop.Checkpoint.empty(),JSONObject())
            checkpoint.createCentralContinuation(saved,CentralContinuationRecord.create(workspace.workspaceId,
                assignment.assignmentId,assignment.requestId,assignment.missionId,bot,cycle))
        }
        fun publish(producer: String = cycle, status: String = "DONE"): WorkspaceResultReference {
            val evidence = CrewResultEvidence.create(workspace.executionSessionId,assignment.missionId,bot,producer,
                status,"Synthetic itinerary, not a booked reservation",1L)
            f.store.appendCrewResultEvidence(evidence)
            val ref = WorkspaceResultReference.create(evidence,WorkspaceIdentity.CENTRAL_SESSION,
                workspace.workspaceId,assignment.assignmentId,assignment.requestId,assignment.revision)
            f.store.appendWorkspaceResultReferenceIfAbsent(ref,Runnable {})
            f.store.appendWorkspaceResultNoticeIfAbsent(ref,Runnable {})
            WorkspaceContinuationDelivery.result(f.context,ref,CancellationToken.uncancellable())
            return ref
        }
        fun admitExplicitly() {
            val selected = record().getJSONObject("selectedResult")
            ExactContinuationTestAdmission.admit(f.context, workspace.workspaceId,
                selected.getString("resultReferenceId"), selected.getString("contentSha256"),
                selected.getLong("assignmentRevision"))
        }
        fun record() = checkpoint.load(workspace.executionSessionId,bot)!!.metadata.getJSONObject(CentralContinuationRecord.FIELD)
        fun input(): Pair<String,ConversationInput> {
            val row = f.store.readConversationMessageByStableId(WorkspaceIdentity.CENTRAL_SESSION,record().getString("inputStableId"))!!
            val id = row.getString("messageId")
            return id to checkNotNull(f.store.readConversationInput(WorkspaceIdentity.CENTRAL_SESSION,id))
        }
    }

    @Test fun resultSelectionAndInputAppendAreIdempotentAndDoNotEnterGenericHistory() {
        WorkspaceMemoryFixture(storage).use { f ->
            val e = Episode(f)
            val cursor=f.store.appendConversationMessage(WorkspaceIdentity.CENTRAL_SESSION,"user","Wait")
            val ref=e.publish(); e.publish()
            assertEquals("PREPARED",e.record().getString("dispatchState"))
            assertEquals(1,e.record().getJSONArray("consumedResults").length())
            assertEquals(ref.referenceId,e.record().getJSONObject("selectedResult").getString("resultReferenceId"))
            val (id,input)=e.input()
            assertTrue(WorkspaceContinuationDelivery.isProtocol(input.provenance))
            assertFalse(f.store.loadConversationContext(WorkspaceIdentity.CENTRAL_SESSION).any { it.durableMessageId==id })
            // A normal turn's steering path may see a deferred input but must never consume it.
            val provider=MainChatTurnContextProvider(f.store,WorkspaceIdentity.CENTRAL_SESSION,cursor)
            assertTrue(provider.takeDurableMessages().isEmpty())
        }
    }

    @Test fun oldOrUnlinkedCycleNeverSatisfiesWait() {
        WorkspaceMemoryFixture(storage).use { f ->
            val e=Episode(f); e.publish(UUID.randomUUID().toString())
            assertEquals("NONE",e.record().getString("dispatchState"))
            assertTrue(e.record().isNull("selectedResult"))
            assertTrue(f.store.readPendingInternalConversationInputs(WorkspaceIdentity.CENTRAL_SESSION).isEmpty())
        }
    }

    @Test fun durableClaimIsAdmittedOnceAndSecondConsumerNeverReclaims() {
        WorkspaceMemoryFixture(storage).use { f ->
            val e=Episode(f); e.publish(); val (_,input)=e.input(); val calls=AtomicInteger()
            e.admitExplicitly()
            val first=CancellationToken.crewChild()
            if(WorkspaceContinuationDelivery.claim(f.context,input,first)) calls.incrementAndGet()
            assertEquals(1,calls.get())
            assertEquals("STARTED",e.record().getString("dispatchState"))
            assertFalse(WorkspaceContinuationDelivery.isPendingDispatchEligible(f.context,input))
            assertEquals(e.record().getString("generationRunId"), WorkspaceContinuationDelivery.claimedRunId(f.context,input,first))
            if(WorkspaceContinuationDelivery.claim(f.context,input,CancellationToken.crewChild())) calls.incrementAndGet()
            assertEquals(1,calls.get())
            assertEquals(1,e.record().getJSONArray("consumedResults").length())
            assertTrue(f.rows("SELECT id FROM generation_run").size==1)
        }
    }

    @Test fun historicalSyntheticResultWithoutFreshAuthorityCannotClaim() {
        WorkspaceMemoryFixture(storage).use { f ->
            val e = Episode(f); e.publish()
            val (_, input) = e.input()
            val before = e.record().toString()
            assertFalse(WorkspaceContinuationDelivery.isPendingDispatchEligible(f.context, input))
            assertThrows(IllegalArgumentException::class.java) {
                WorkspaceContinuationDelivery.claim(f.context, input, CancellationToken.crewChild())
            }
            assertEquals(before, e.record().toString())
            assertTrue(f.rows("SELECT id FROM generation_run").isEmpty())
        }
    }

    @Test fun stoppedEpisodeKeepsResultButCannotClaimContinuation() {
        WorkspaceMemoryFixture(storage).use { f ->
            val e=Episode(f); e.publish(); val (_,input)=e.input()
            WorkspaceContinuationDelivery.cancel(f.context,e.workspace.workspaceId,e.bot)
            assertEquals("CANCELLED",e.record().getString("dispatchState"))
            assertFalse(WorkspaceContinuationDelivery.claim(f.context,input,CancellationToken.crewChild()))
            assertFalse(WorkspaceContinuationDelivery.isPendingDispatchEligible(f.context,input))
            assertFalse(e.record().isNull("selectedResult"))
            assertTrue(f.rows("SELECT id FROM generation_run").isEmpty())
        }
    }

    @Test fun exactInputAckIsDerivedWithoutAnotherDispatch() {
        WorkspaceMemoryFixture(storage).use { f ->
            val e=Episode(f); e.publish(); val (id,input)=e.input(); val token=CancellationToken.crewChild()
            e.admitExplicitly()
            assertTrue(WorkspaceContinuationDelivery.claim(f.context,input,token))
            f.store.markConversationMessagesIncorporated(WorkspaceIdentity.CENTRAL_SESSION,listOf(id))
            assertEquals("STARTED",e.record().getString("dispatchState"))
            e.publish() // Receipt recovery after lost live ACK callback: no provider or new journal.
            assertEquals("ACKNOWLEDGED",e.record().getString("dispatchState"))
            assertFalse(WorkspaceContinuationDelivery.claim(f.context,input,CancellationToken.crewChild()))
            val response=WorkspaceContinuationDelivery.finish(f.context,input,token,"Itinerary summary","COMPLETED",1L)
            assertFalse(response.isEmpty())
            assertEquals(listOf(listOf("completed")),f.rows("SELECT state FROM generation_run"))
        }
    }

    @Test fun payloadConflictAndCancellationCannotCreateGeneration() {
        WorkspaceMemoryFixture(storage).use { f ->
            val e=Episode(f); e.publish(); val (_,input)=e.input()
            e.admitExplicitly()
            val forged=ConversationInput(input.conversationId,input.messageId,"Different reference",input.origin,input.provenance)
            assertThrows(IllegalStateException::class.java) { WorkspaceContinuationDelivery.claim(f.context,forged,CancellationToken.crewChild()) }
            val stopped=CancellationToken.crewChild();stopped.cancel()
            assertThrows(java.util.concurrent.CancellationException::class.java) { WorkspaceContinuationDelivery.claim(f.context,input,stopped) }
            assertEquals("PREPARED",e.record().getString("dispatchState"))
            assertTrue(f.rows("SELECT id FROM generation_run").isEmpty())
        }
    }
    @Test fun failedTerminalIsAnOutcomeButStoppedAndQuestionPartialNeverWake() {
        WorkspaceMemoryFixture(storage).use { f ->
            val e=Episode(f); e.publish(status="FAILED")
            assertEquals("PREPARED",e.record().getString("dispatchState"))
            assertFalse(WorkspaceContinuationDelivery.isPendingDispatchEligible(f.context,e.input().second))
            e.admitExplicitly()
            assertTrue(WorkspaceContinuationDelivery.isPendingDispatchEligible(f.context,e.input().second))
        }
    }

    @Test fun stoppedTerminalNeverSatisfiesWait() {
        WorkspaceMemoryFixture(storage).use { f ->
            val e=Episode(f); e.publish(status="STOPPED")
            assertEquals("NONE",e.record().getString("dispatchState"))
            val partial=Episode(f);partial.publish(status="PARTIAL")
            assertEquals("NONE",partial.record().getString("dispatchState"))
            assertTrue(f.store.readPendingInternalConversationInputs(WorkspaceIdentity.CENTRAL_SESSION).isEmpty())
        }
    }

    @Test fun legacyCheckpointIsNotOptedInAndNoWaitIsInvented() {
        WorkspaceMemoryFixture(storage).use { f ->
            val workspace=f.createProject("Legacy")
            val checkpoint=CrewCheckpointStore(f.files)
            checkpoint.saveConfirmed(workspace.executionSessionId,"legacy",JSONObject()
                .put("conversationId",workspace.executionSessionId).put("botId","legacy"),
                "legacy-coding-scope",CoreAgentLoop.Checkpoint.empty(),JSONObject())
            assertFalse(WorkspaceContinuationDelivery.owns(f.context,workspace.executionSessionId,"legacy"))
            assertNull(WorkspaceContinuationDelivery.status(f.context,workspace.executionSessionId,"legacy"))
            assertFalse(checkpoint.load(workspace.executionSessionId,"legacy")!!.metadata.has(CentralContinuationRecord.FIELD))
        }
    }

    @Test fun startedWithoutAnyJournalRowIsUncertainNotPermissionToReplay() {
        WorkspaceMemoryFixture(storage).use { f ->
            val e=Episode(f); e.publish(); val (_,input)=e.input()
            val saved=e.checkpoint.confirmDurable(e.workspace.executionSessionId,e.bot,"native-workspace:${e.workspace.workspaceId}")!!
            val value=e.record(); val run=UUID.randomUUID().toString()
            value.put("revision",value.getLong("revision")+1).put("dispatchState","STARTED")
                .put("dispatchOwnerProcessId","lost-process").put("dispatchGenerationId","41")
                .put("generationRunId",run).put("assistantMessageStableId","continuation-assistant:$run")
            e.checkpoint.updateCentralContinuation(saved,CentralContinuationRecord.fromJson(value))
            assertTrue(f.rows("SELECT id FROM generation_run").isEmpty())
            assertFalse(WorkspaceContinuationDelivery.isPendingDispatchEligible(f.context,input))
            assertFalse(WorkspaceContinuationDelivery.claim(f.context,input,CancellationToken.crewChild()))
            assertEquals(true,WorkspaceContinuationDelivery.status(f.context,e.workspace.executionSessionId,e.bot)!!.getBoolean("uncertain"))
            assertTrue(f.rows("SELECT id FROM generation_run").isEmpty())
        }
    }

    @Test fun resolvedInputCannotBeClaimedAndPureStatusDoesNotDispatch() {
        WorkspaceMemoryFixture(storage).use { f ->
            val e=Episode(f); e.publish(); val (id,input)=e.input()
            e.admitExplicitly()
            f.store.markConversationMessagesIncorporated(WorkspaceIdentity.CENTRAL_SESSION,listOf(id))
            assertFalse(WorkspaceContinuationDelivery.isPendingDispatchEligible(f.context,input))
            assertThrows(IllegalStateException::class.java) { WorkspaceContinuationDelivery.claim(f.context,input,CancellationToken.crewChild()) }
            assertEquals("PREPARED", WorkspaceContinuationDelivery.status(f.context,e.workspace.executionSessionId,e.bot)!!.getString("state"))
            assertEquals("PREPARED",e.record().getString("dispatchState"))
            assertTrue(f.rows("SELECT id FROM generation_run").isEmpty())
        }
    }

    @Test fun controlAfterClaimCancelsOnlyExactLiveOwnerAndKeepsUncertainty() {
        WorkspaceMemoryFixture(storage).use { f ->
            val e=Episode(f); e.publish(); val (_,input)=e.input(); val token=CancellationToken.crewChild()
            e.admitExplicitly()
            assertTrue(WorkspaceContinuationDelivery.claim(f.context,input,token))
            WorkspaceContinuationDelivery.cancel(f.context,e.workspace.workspaceId,e.bot)
            assertTrue(token.isCancellationRequested)
            assertEquals("STARTED",e.record().getString("dispatchState"))
            WorkspaceContinuationDelivery.releaseClaim(input,token)
            val newer=CancellationToken.crewChild()
            WorkspaceContinuationDelivery.cancel(f.context,e.workspace.workspaceId,e.bot)
            assertFalse(newer.isCancellationRequested)
            assertFalse(WorkspaceContinuationDelivery.claim(f.context,input,newer))
        }
    }

    @Test fun stalePreparedStopSnapshotStillCancelsWinnerWhenCancellationCasLoses() {
        WorkspaceMemoryFixture(storage).use { f ->
            val e=Episode(f);e.publish();e.admitExplicitly()
            val before=e.checkpoint.confirmDurable(e.workspace.executionSessionId,e.bot,
                "native-workspace:${e.workspace.workspaceId}")!!
            val (_,input)=e.input();val token=CancellationToken.crewChild()
            assertTrue(WorkspaceContinuationDelivery.claim(f.context,input,token))
            // STOP already read PREPARED before the competing claim committed. Its canonical
            // denial step must cancel the installed winner even if the following CAS loses.
            WorkspaceContinuationDelivery.fenceCancelledEpisode(before)
            assertTrue(token.isCancellationRequested)
            val cancelled=CentralContinuationRecord.fromJson(before.metadataJson()
                .getJSONObject(CentralContinuationRecord.FIELD).also {
                    it.put("revision",it.getLong("revision")+1).put("dispatchState","CANCELLED")
                })
            assertThrows(RuntimeException::class.java) { e.checkpoint.updateCentralContinuation(before,cancelled) }
            assertEquals("STARTED",e.record().getString("dispatchState"))
            WorkspaceContinuationDelivery.releaseClaim(input,token)
            val replacement=CancellationToken.crewChild()
            WorkspaceContinuationDelivery.fenceCancelledEpisode(before)
            assertFalse(replacement.isCancellationRequested)
            assertFalse(WorkspaceContinuationDelivery.claim(f.context,input,replacement))
            assertEquals(1,e.record().getJSONArray("consumedResults").length())
        }
    }

    @Test fun localResultReceiptRecoveryNeverStartsAService() {
        WorkspaceMemoryFixture(storage).use { f ->
            val app=org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication())
            while(app.nextStartedService != null) { }
            val e=Episode(f);e.publish()
            assertEquals("PREPARED",e.record().getString("dispatchState"))
            assertNull(app.nextStartedService)
        }
    }

}
