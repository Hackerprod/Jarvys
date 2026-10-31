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
import org.robolectric.annotation.SQLiteMode

/** Real P1 checkpoint/outbox/CAS; named synthetic Registry exit/report receipts are NOT live-provider proofs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],shadows=[ArtifactOsShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkspaceTreeP1PreclaimTest {
    private val storage=ChatTestStore()
    @get:Rule val rules:RuleChain=RuleChain.outerRule(storage).around(TestSecretStoreRule())
    private inner class Episode(val f:WorkspaceMemoryFixture) {
        val registry=WorkspaceRegistry(f.context)
        val core=WorkspaceTreeStore(registry)
        val scope=f.createProject("Enrolled result fixture")
        val bot="native-fixture-bot"
        val cycle=java.util.UUID.randomUUID().toString()
        val originalUser=f.store.appendConversationMessage(WorkspaceIdentity.CENTRAL_SESSION,"user","Original request")
        val origin=f.store.captureCentralUserRoot(WorkspaceIdentity.CENTRAL_SESSION,originalUser)
        val root:TreeNode
        val child:TreeNode
        val assignment:WorkspaceAssignment
        val checkpoint=CrewCheckpointStore(f.files)
        init {
            registry.upgradeTreeSchema()
            val r=core.beginRoot(TreeOrigin(origin.storageGeneration,origin.ownerCentralSessionId,origin.originUserMessageId,origin.originDigest),
                "synthetic-original-native-source","fixture-process:17")
            root=core.getNode(r.rootId,r.rootId)!!
            val queued=core.admitChild(TreeNodeFence.of(root),TreeChildRequest(scope.workspaceId,"enrolled","request","Original request",
                "analyst","Prepare result",listOf("board_read"),"Analyst",TreeMode.CONSULT,TreeReply.AWAIT,"synthetic-admission"))
            val reservation=core.reserveFirstExecution(TreeNodeFence.of(queued),"fixture-process")
            assignment=registry.bindBot(scope.workspaceId,queued.assignmentId!!,reservation.assignmentRevision,bot)
            val cert=JSONObject().put("originCheckpointSha256","a".repeat(64)).put("originScopeIdentity","native-workspace:${scope.workspaceId}")
                .put("originSavedAtMs",1L).put("profileSha256","a".repeat(64)).put("transcriptSha256","a".repeat(64))
                .put("toolLifecycleSha256","a".repeat(64)).put("artifactOwnershipSha256","a".repeat(64)).toString()
            child=core.bindExecution(TreeNodeFence.of(core.getNode(root.rootId,queued.nodeId)!!),bot,cycle,cert)
            val saved=checkpoint.saveConfirmed(scope.executionSessionId,bot,JSONObject().put("conversationId",scope.executionSessionId)
                .put("botId",bot).put("missionId",assignment.missionId).put("assignmentId",assignment.assignmentId)
                .put("cycleId",cycle).put("nativeWorkspaceCheckpoint",true).put("status","DONE"),
                "native-workspace:${scope.workspaceId}",CoreAgentLoop.Checkpoint.empty(),JSONObject())
            checkpoint.createCentralContinuation(saved,CentralContinuationRecord.create(scope.workspaceId,assignment.assignmentId,
                assignment.requestId,assignment.missionId,bot,cycle))
        }
        fun publish(report:Boolean=true):WorkspaceResultReference {
            val evidence=CrewResultEvidence.create(scope.executionSessionId,assignment.missionId,bot,cycle,"DONE","Synthetic result",1L)
            f.store.appendCrewResultEvidence(evidence)
            val ref=WorkspaceResultReference.create(evidence,WorkspaceIdentity.CENTRAL_SESSION,scope.workspaceId,assignment.assignmentId,assignment.requestId,assignment.revision)
            f.store.appendWorkspaceResultReferenceIfAbsent(ref,Runnable {});f.store.appendWorkspaceResultNoticeIfAbsent(ref,Runnable {})
            WorkspaceContinuationDelivery.result(f.context,ref,CancellationToken.uncancellable())
            core.settle(TreeNodeFence.of(child),"SUCCESS",evidence.evidenceId,evidence.contentSha256,"synthetic-native-result-source")
            if(report) WorkspaceTreeEvidenceStore(core).markReported(root.rootId,child.nodeId,evidence.evidenceId,evidence.contentSha256,"synthetic-durable-outbox-receipt")
            return ref
        }
        fun record()=checkpoint.load(scope.executionSessionId,bot)!!.metadata.getJSONObject(CentralContinuationRecord.FIELD)
        fun admit() { val selected=record().getJSONObject("selectedResult");ExactContinuationTestAdmission.admit(f.context,scope.workspaceId,
            selected.getString("resultReferenceId"),selected.getString("contentSha256"),selected.getLong("assignmentRevision")) }
        fun input():ConversationInput { val row=f.store.readConversationMessageByStableId(WorkspaceIdentity.CENTRAL_SESSION,record().getString("inputStableId"))!!
            return f.store.readConversationInput(WorkspaceIdentity.CENTRAL_SESSION,row.getString("messageId"))!! }
        fun park()=WorkspaceTreeCentralStore(core).finish(TreeNodeFence.of(root),"synthetic-confirmed-central-exit",false)
    }
    @Test fun childBeforeFirstCentralExitStaysPreparedThenSameEligibleEpisodeClaimsAfterWait() {
        WorkspaceMemoryFixture(storage).use { f ->
            val e=Episode(f)
            try {
                e.publish();e.admit();val input=e.input();val before=e.record().toString()
                assertFalse(WorkspaceContinuationDelivery.isPendingDispatchEligible(f.context,input))
                assertThrows(WorkspaceContinuationDelivery.AdmissionIneligible::class.java) { WorkspaceContinuationDelivery.claim(f.context,input,CancellationToken.crewChild()) }
                assertEquals(before,e.record().toString())
                e.park();assertTrue(WorkspaceContinuationDelivery.isPendingDispatchEligible(f.context,input))
                val token=CancellationToken.crewChild();assertTrue(WorkspaceContinuationDelivery.claim(f.context,input,token))
                val claim=WorkspaceContinuationDelivery.claimedRunId(f.context,input,token)
                val parent=e.core.getNode(e.root.rootId,e.root.nodeId)!!
                WorkspaceTreeCentralStore(e.core).attach(TreeNodeFence.of(parent),"claimed-process:18","claimed-source",claim,true,e.child.nodeId)
                // Postclaim runtime identity is valid after root transitions to RUNNING, not only WAITING.
                assertEquals(claim,WorkspaceContinuationDelivery.claimedRunId(f.context,input,token))
                assertFalse(WorkspaceContinuationDelivery.claim(f.context,input,CancellationToken.crewChild()))
            } finally { e.registry.close() }
        }
    }
    @Test fun closedOrUnreportedRootNeverCrossesP1StartedBoundary() {
        for(report in listOf(false,true)) WorkspaceMemoryFixture(storage).use { f ->
            val e=Episode(f)
            try {
                e.publish(report);e.park();e.admit()
                if(report) e.core.stopRequester(TreeNodeFence.of(e.core.getNode(e.root.rootId,e.root.nodeId)!!))
                val before=e.record().toString();val input=e.input()
                assertFalse(WorkspaceContinuationDelivery.isPendingDispatchEligible(f.context,input))
                assertThrows(WorkspaceContinuationDelivery.AdmissionIneligible::class.java) { WorkspaceContinuationDelivery.claim(f.context,input,CancellationToken.crewChild()) }
                assertEquals(before,e.record().toString());assertEquals("PREPARED",e.record().getString("dispatchState"))
            } finally { e.registry.close() }
        }
    }
    @Test fun rootDenialAfterClaimBlocksRuntimeWithoutResettingOrReplaying() {
        WorkspaceMemoryFixture(storage).use { f ->
            val e=Episode(f)
            try {
                e.publish();e.park();e.admit();val input=e.input();val token=CancellationToken.crewChild()
                assertTrue(WorkspaceContinuationDelivery.claim(f.context,input,token))
                WorkspaceTreeStops.latchRoot(f.context,e.root.rootId,e.root.cycleId)
                assertThrows(IllegalStateException::class.java) { WorkspaceContinuationDelivery.claimedRunId(f.context,input,token) }
                assertEquals("STARTED",e.record().getString("dispatchState"))
                assertFalse(WorkspaceContinuationDelivery.claim(f.context,input,CancellationToken.crewChild()))
            } finally { e.registry.close() }
        }
    }
    @Test fun historicalV98AssignmentIsNotEnrolledOrUpgradedByPreclaimRead() {
        WorkspaceMemoryFixture(storage).use { f ->
            val scope=f.createProject("Historical v98 fixture")
            WorkspaceRegistry(f.context).use { registry ->
                val assignment=registry.prepareAssignment(scope.workspaceId,"historical","request","Original","analyst","Task",null,null,CrewMissionAccess.READ_ONLY)
                val value=JSONObject().put("workspaceId",scope.workspaceId).put("assignmentId",assignment.assignmentId)
                assertNull(WorkspaceTreeP1Preclaim.requireEligible(f.context,value))
                registry.treeRead { assertEquals(1,it.version) }
                assertNull(WorkspaceTreeStore(registry).findAssignment(scope.workspaceId,assignment.assignmentId))
            }
        }
    }

}
