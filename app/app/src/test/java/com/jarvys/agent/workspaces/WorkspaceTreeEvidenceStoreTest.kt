package com.jarvys.agent.workspaces

import android.app.Application
import com.jarvys.agent.ArtifactOsShadow
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,shadows=[ArtifactOsShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkspaceTreeEvidenceStoreTest {
    private lateinit var app:Application
    private lateinit var registry:WorkspaceRegistry
    private val core get()=WorkspaceTreeStore(registry)
    private val evidence get()=WorkspaceTreeEvidenceStore(core)
    private val digest="a".repeat(64)
    private lateinit var root:TreeNode
    private lateinit var scope:WorkspaceRecord
    @Before fun setup() {
        app=RuntimeEnvironment.getApplication();app.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
        registry=WorkspaceRegistry(app);registry.upgradeTreeSchema()
        val r=core.beginRoot(TreeOrigin("generation",WorkspaceIdentity.CENTRAL_SESSION,"user",digest),"source","process:1")
        root=core.getNode(r.rootId,r.rootId)!!;scope=registry.createWorkspace("scope","General")
    }
    @After fun cleanup() { registry.close();app.deleteDatabase(WorkspaceRegistry.DATABASE_NAME) }
    private fun certificate()=JSONObject().put("originCheckpointSha256",digest).put("originScopeIdentity","native-workspace:${scope.workspaceId}")
        .put("originSavedAtMs",1L).put("profileSha256",digest).put("transcriptSha256",digest)
        .put("toolLifecycleSha256",digest).put("artifactOwnershipSha256",digest).toString()
    private fun running(key:String):TreeNode {
        val n=core.admitChild(TreeNodeFence.of(root),TreeChildRequest(scope.workspaceId,key,"request","Task","analyst","Task $key",
            emptyList(),null,TreeMode.WORK,TreeReply.AWAIT,"source-$key"))
        val reservation=core.reserveFirstExecution(TreeNodeFence.of(n),"process")
        registry.bindBot(scope.workspaceId,n.assignmentId!!,reservation.assignmentRevision,"bot-$key")
        return core.bindExecution(TreeNodeFence.of(core.getNode(n.rootId,n.nodeId)!!),"bot-$key","cycle-$key",certificate())
    }
    private fun source(n:TreeNode,call:String,args:String="{}")=JSONObject().put("version",1)
        .put("executionSessionId",scope.executionSessionId).put("botId",n.botId)
        .put("missionId",registry.getAssignment(scope.workspaceId,n.assignmentId!!)!!.missionId).put("cycleId",n.cycleId)
        .put("scopeIdentity","native-workspace:${scope.workspaceId}").put("checkpointPayloadSha256",digest)
        .put("callId",call).put("toolName","synthetic-execute4-fixture")
        .put("argumentsDigest",WorkspaceTreeStore.identity(WorkspaceTreeEvidenceStore.canonical(JSONObject(args)))).toString()
    private fun denied(block:()->Unit) { assertNotNull(runCatching(block).exceptionOrNull()) }
    private fun producer(n:TreeNode,profile:String=digest)=evidence.requireCertifiedProducer(n.rootId,n.nodeId,n.cycleId,n.assignmentId!!,
        n.botId!!,"native-workspace:${scope.workspaceId}",profile,"generation")
    @Test fun invocationConservesFullUtf16ArgumentsAndResultAcrossReopenAndDeniesMutation() {
        val n=running("one");val content="nul\u0000lone\ud800 unicode Ω"
        val args=JSONObject().put("text",content).toString();val source=source(n,"call",args)
        val key=evidence.recordInvocation(TreeNodeFence.of(n),"call","ADMISSION",source,args)
        val result=JSONObject().put("success",true).put("content",content).put("completeContentRequired",true).put("finishRun",false).toString()
        evidence.recordInvocationResult(n.rootId,n.nodeId,n.cycleId,"call",key,result)
        evidence.recordInvocationResult(n.rootId,n.nodeId,n.cycleId,"call",key,result)
        denied { evidence.recordInvocationResult(n.rootId,n.nodeId,n.cycleId,"call",key,JSONObject(result).put("content","changed").toString()) }
        denied { evidence.recordInvocation(TreeNodeFence.of(n),"call","ADMISSION",source,"{}") }
        registry.close();registry=WorkspaceRegistry(app)
        val record=evidence.invocationReceipts(n.rootId,n.nodeId,n.cycleId).single()
        assertEquals(content,JSONObject(record.argsJson).getString("text"));assertEquals(content,JSONObject(record.resultJson!!).getString("content"))
        assertEquals(WorkspaceTreeStore.identity(record.resultJson),record.resultHash)
        producer(n)
    }
    @Test fun certifiedBindRejectsCanonicalDigestOwnerProfileAndRevisionChanges() {
        val n=running("producer");producer(n);denied { producer(n,"b".repeat(64)) }
        denied { evidence.requireCertifiedProducer(n.rootId,n.nodeId,n.cycleId,n.assignmentId!!,"foreign-bot","native-workspace:${scope.workspaceId}",digest,"generation") }
        registry.treeTransaction { db -> db.execSQL("UPDATE bot_tree_event SET payload_digest=? WHERE root_id=? AND kind='BIND'",
            arrayOf("b".repeat(64),n.rootId)) }
        denied { producer(n) }
    }
    @Test fun needsInput49RetainsEveryQuestionAndReportBeforeBudgetCancellation() {
        val nodes=(1..49).map { running("question-$it") };var cancelled:List<TreeNode>?=null
        nodes.forEachIndexed { i,n ->
            val source=source(n,"ask-$i")
            evidence.recordInvocation(TreeNodeFence.of(n),"ask-$i","QUESTION_ARM",source,"{}")
            evidence.recordQuestion(TreeNodeFence.of(n),"question-$i",digest,source)
            evidence.markReported(n.rootId,n.nodeId,"question-$i",digest,"report-$i") { cancelled=it }
        }
        val closed=core.getRoot(root.rootId)!!;assertEquals(49L,closed.wakes);assertEquals("BUDGET",closed.closeReason)
        assertTrue(closed.admissionsClosed && closed.wakesClosed);assertNotNull(cancelled)
        registry.treeRead { db ->
            for(kind in listOf("NEEDS_INPUT","REPORTED")) db.rawQuery("SELECT COUNT(*) FROM bot_tree_event WHERE kind=?",arrayOf(kind)).use { c ->
                c.moveToFirst();assertEquals(49,c.getInt(0)) }
        }
        val last=nodes.last();assertNotNull(core.getNode(last.rootId,last.nodeId)!!.reportReceipt)
        evidence.markReported(last.rootId,last.nodeId,"question-48",digest,"report-48")
        assertEquals(closed,core.getRoot(root.rootId))
    }
    @Test fun terminalReportsAreUncappedAndLateClosedReportsNeverResumeOrReset() {
        val nodes=(1..64).map { running("terminal-$it") }
        nodes.forEach { core.settle(TreeNodeFence.of(it),"SUCCESS","result-${it.nodeId}",digest,"native-source") }
        nodes.forEach { evidence.markReported(it.rootId,it.nodeId,"result-${it.nodeId}",digest,"receipt-${it.nodeId}") }
        val before=core.getRoot(root.rootId)!!;assertEquals(64L,before.wakes);assertFalse(before.wakesClosed)
        core.stopRequester(TreeNodeFence.of(root));val closed=core.getRoot(root.rootId)
        nodes.forEach { evidence.markReported(it.rootId,it.nodeId,"result-${it.nodeId}",digest,"receipt-${it.nodeId}") }
        assertEquals(closed,core.getRoot(root.rootId))
    }
    @Test fun completeProducerChainKeepsBothSameCycleControlsAndRejectsChangedPredecessor() {
        var n=running("controls");val firstCycle=n.cycleId
        val ids=ArrayList<String>()
        repeat(2) { index ->
            val id=java.util.UUID.randomUUID().toString();ids.add(id)
            registry.submitNativeControl(id,scope.workspaceId,n.assignmentId!!,n.botId!!,n.assignmentRevision,
                WorkspaceControlType.CORRECT,"samecycle correction $index",Runnable {})
            // Synthetic immutable preclaim source fixture: native adapter must confirm this snapshot before claim.
            registry.treeTransaction { db -> core.appendEvent(db,n,WorkspaceTreeStore.identity("control-predecessor",id),
                "CONTROL_PREDECESSOR",certificate(),id,WorkspaceTreeStore.identity(certificate()),n.cycleId) }
            val control=WorkspaceTreeControlStore(core)
            val claim=control.claim(TreeNodeFence.of(n),id,"process",false,Runnable {})
            denied { producer(claim.node) }
            evidence.requireCertifiedPredecessor(n.rootId,n.nodeId,n.cycleId,n.assignmentId!!,n.botId!!,
                "native-workspace:${scope.workspaceId}",digest,"generation",certificate(),digest,pendingControlId=id)
            denied { evidence.requireCertifiedPredecessor(n.rootId,n.nodeId,n.cycleId,n.assignmentId!!,n.botId!!,
                "native-workspace:${scope.workspaceId}",digest,"generation",certificate(),digest,pendingControlId="foreign-control") }
            n=control.bind(claim,n.cycleId,certificate())
            registry.completeControl(id,claim.control.revision)
            producer(n)
        }
        assertEquals(firstCycle,n.cycleId)
        registry.close();registry=WorkspaceRegistry(app);producer(n)
        val key=WorkspaceTreeStore.identity("control-successor",ids.first())
        val changed=JSONObject(certificate()).put("profileSha256","b".repeat(64)).toString()
        registry.treeTransaction { db -> db.execSQL("UPDATE bot_tree_event SET receipt=?,payload_digest=? WHERE root_id=? AND event_key=?",
            arrayOf(changed,WorkspaceTreeStore.identity(changed),n.rootId,key)) }
        // Latest certificate remains valid; every predecessor must still be checked.
        denied { producer(n) }
    }
    @Test fun childWaitSuccessorChainRequiresExactCountedWaitAndNativeRevision() {
        val parent=running("wait-parent")
        val child=core.admitChild(TreeNodeFence.of(parent),TreeChildRequest(scope.workspaceId,"wait-child","request","Task","analyst","Task child",
            emptyList(),null,TreeMode.WORK,TreeReply.AWAIT,"child-source"))
        val reservation=core.reserveFirstExecution(TreeNodeFence.of(child),"process")
        registry.bindBot(scope.workspaceId,child.assignmentId!!,reservation.assignmentRevision,"child-bot")
        val bound=core.bindExecution(TreeNodeFence.of(core.getNode(child.rootId,child.nodeId)!!),"child-bot","child-cycle",certificate())
        val waits=WorkspaceTreeWaitStore(core)
        val armed=waits.armWait(TreeNodeFence.of(parent),listOf(child.nodeId),TreeJoin.ALL,"wait-source")
        val confirmed=waits.confirmWait(armed,certificate(),digest)
        core.settle(TreeNodeFence.of(bound),"SUCCESS","child-result",digest,"result-source")
        evidence.markReported(child.rootId,child.nodeId,"child-result",digest,"reported-child")
        val claim=waits.claimWait(confirmed,"process","generation","token")
        val pending=core.getNode(parent.rootId,parent.nodeId)!!
        denied { producer(pending) } // Ordinary current producer remains strict after STARTED.
        fun predecessor(cert:String=certificate(),sha:String=digest,waitId:String=claim.wait.waitId) =
            evidence.requireCertifiedPredecessor(parent.rootId,parent.nodeId,parent.cycleId,parent.assignmentId!!,parent.botId!!,
                "native-workspace:${scope.workspaceId}",digest,"generation",cert,sha,pendingWaitId=waitId)
        predecessor()
        denied { predecessor(sha="b".repeat(64)) }
        denied { predecessor(waitId="another-wait") }
        denied { predecessor(cert=JSONObject(certificate()).put("transcriptSha256","b".repeat(64)).toString()) }
        denied { evidence.requireCertifiedPredecessor(parent.rootId,parent.nodeId,parent.cycleId,parent.assignmentId!!,parent.botId!!,
            "native-workspace:${scope.workspaceId}",digest,"generation",certificate(),digest,
            pendingWaitId=claim.wait.waitId,pendingControlId="fake-control") }
        val successor=waits.bindSuccessor(claim.wait,"parent-successor",claim.admittedAssignmentRevision,certificate(),"process","new-generation","new-token")
        producer(successor)
        denied { predecessor() } // Binding never leaves reusable predecessor permission.
        registry.treeTransaction { db -> db.execSQL("UPDATE assignment SET revision=revision+1 WHERE workspace_id=? AND assignment_id=?",
            arrayOf(scope.workspaceId,successor.assignmentId!!)) }
        denied { producer(successor) }
    }

}
