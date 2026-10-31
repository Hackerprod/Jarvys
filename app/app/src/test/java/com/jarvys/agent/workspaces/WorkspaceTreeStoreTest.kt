package com.jarvys.agent.workspaces

import android.app.Application
import com.jarvys.agent.ArtifactOsShadow
import com.jarvys.agent.CentralInvocationSource
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fresh reconstruction low-level native SQLite proofs, never live invocation/provider authority. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,shadows=[ArtifactOsShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkspaceTreeStoreTest {
    private lateinit var context:Application
    private lateinit var registry:WorkspaceRegistry
    private val tree get()=WorkspaceTreeStore(registry)
    private val hash="a".repeat(64)
    private val origin=TreeOrigin("storage-fixture",WorkspaceIdentity.CENTRAL_SESSION,"original-user",hash)
    @Before fun setup() { context=RuntimeEnvironment.getApplication();context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME);registry=WorkspaceRegistry(context) }
    @After fun cleanup() { registry.close();context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME) }
    private fun root():TreeNode { registry.upgradeTreeSchema();val r=tree.beginRoot(origin,"original-source","process:17");return tree.getNode(r.rootId,r.rootId)!! }
    private fun workspace()=registry.createWorkspace("scope-${UUID.randomUUID()}","General purpose")
    private fun request(scope:WorkspaceRecord,key:String)=TreeChildRequest(scope.workspaceId,key,"request","Original task","analyst",
        "Task $key",listOf("board_read"),"Generalist",TreeMode.WORK,TreeReply.AWAIT,"source-$key")
    private fun queued(parent:TreeNode,scope:WorkspaceRecord,key:String)=tree.admitChild(TreeNodeFence.of(parent),request(scope,key))
    private fun certificate(scope:String)=JSONObject().put("originCheckpointSha256",hash).put("originScopeIdentity","native-workspace:$scope")
        .put("originSavedAtMs",123L).put("profileSha256",hash).put("transcriptSha256",hash)
        .put("toolLifecycleSha256",hash).put("artifactOwnershipSha256",hash).toString()
    private fun running(child:TreeNode):TreeNode {
        val reserved=tree.reserveFirstExecution(TreeNodeFence.of(child),"fixture-process")
        val node=tree.getNode(reserved.rootId,reserved.nodeId)!!
        registry.bindBot(node.workspaceId!!,node.assignmentId!!,reserved.assignmentRevision,"bot-${node.nodeId}")
        return tree.bindExecution(TreeNodeFence.of(node),"bot-${node.nodeId}","cycle-${node.nodeId}",certificate(node.workspaceId))
    }
    private fun settle(node:TreeNode)=tree.settle(TreeNodeFence.of(node),"SUCCESS","result-${node.nodeId}",hash,"terminal-source")
    private fun denied(block:()->Unit) { val failure=runCatching(block).exceptionOrNull();assertTrue("Expected fail closed: $failure",failure is IllegalStateException || failure is IllegalArgumentException) }
    private fun count(table:String)=registry.treeRead { db -> db.rawQuery("SELECT COUNT(*) FROM $table",null).use { c->c.moveToFirst();c.getLong(0) } }

    @Test fun v1ReadsDoNotUpgradeEnrollOrMutateHistoricalAssignments() {
        val scope=workspace();val a=registry.prepareAssignment(scope.workspaceId,"historical","request","Original task","analyst","Task",null,null,com.jarvys.agent.crew.CrewMissionAccess.STANDARD)
        assertNull(tree.getRoot("absent"));assertNull(tree.findAssignment(scope.workspaceId,a.assignmentId));assertTrue(tree.children("absent","absent").isEmpty())
        registry.treeRead { assertEquals(1,it.version) }
        val parent=root()
        denied { tree.admitChild(TreeNodeFence.of(parent),request(scope,"historical").copy(mission="Task",tools=null,name=null)) }
        assertEquals(0L,tree.getRoot(parent.rootId)!!.requests);assertEquals(1L,count("assignment"))
        assertNull(tree.findAssignment(scope.workspaceId,a.assignmentId))
    }
    @Test fun rootIdentityInitialExecutionAndClosedStateSurviveRuntimeWorkspaceAndReopen() {
        val parent=root();val scope=workspace();queued(parent,scope,"one")
        val before=tree.getRoot(parent.rootId)!!;assertEquals(1L,before.executions);assertEquals(1L,before.requests)
        assertEquals(before,tree.beginRoot(origin,"different-runtime-source","different-cycle"))
        tree.stopRequester(TreeNodeFence.of(parent));val closed=tree.getRoot(parent.rootId)!!
        registry.close();registry=WorkspaceRegistry(context);workspace()
        assertEquals(closed,tree.beginRoot(origin,"another-source","another-cycle"))
        denied { queued(tree.getNode(parent.rootId,parent.nodeId)!!,scope,"after-stop") }
    }
    @Test fun sixtyFourConcurrentRequestsAreAtomicExactDuplicatesDoNotCharge() {
        val parent=root();val scope=workspace();val start=CountDownLatch(1);val pool=Executors.newFixedThreadPool(8)
        try {
            val jobs=(0 until 128).map { i -> pool.submit(Callable { start.await();queued(parent,scope,"key-${i%64}").nodeId }) }
            start.countDown();assertEquals(64,jobs.map { it.get(20,TimeUnit.SECONDS) }.toSet().size)
            assertEquals(64L,tree.getRoot(parent.rootId)!!.requests);assertEquals(64L,count("assignment"))
            denied { queued(parent,scope,"overflow") }
            denied { tree.admitChild(TreeNodeFence.of(parent),request(scope,"key-0").copy(mission="changed")) }
            assertEquals(64L,count("assignment"))
        } finally { start.countDown();pool.shutdownNow() }
    }
    @Test fun depthFourAllowedFiveDeniedAndConsultAndForeignScopeCannotDelegate() {
        val central=root();val scope=workspace();var parent=central
        repeat(4) { depth -> parent=running(queued(parent,scope,"depth-$depth")) }
        assertEquals(4,parent.depth);denied { queued(parent,scope,"depth-five") }
        val ordinary=running(queued(central,scope,"ordinary"))
        denied { queued(ordinary,workspace(),"foreign") }
        denied { tree.admitChild(TreeNodeFence.of(ordinary),request(scope,"wrong-mission").copy(requestId="other-request")) }
        denied { tree.admitChild(TreeNodeFence.of(ordinary),request(scope,"excess-tools").copy(tools=listOf("shell"))) }
        val consult=tree.admitChild(TreeNodeFence.of(central),request(scope,"consult").copy(mode=TreeMode.CONSULT))
        denied { queued(running(consult),scope,"consult-child") }
    }
    @Test fun execution96ReservationIsAtomicAndUncertainNeverRefundsOrReplays() {
        val parent=root();val scope=workspace();val first=(0 until 64).map { queued(parent,scope,"first-$it") }
        settle(running(first.first())) // Real first child settlement starts fresh window, no fabricated counters.
        val pending=first.drop(1)+(0 until 34).map { queued(parent,scope,"next-$it") }
        pending.take(96).forEach { tree.reserveFirstExecution(TreeNodeFence.of(it),"process") }
        assertEquals(96L,tree.getRoot(parent.rootId)!!.executions)
        denied { tree.reserveFirstExecution(TreeNodeFence.of(pending.last()),"process") }
        val started=tree.getNode(parent.rootId,pending.first().nodeId)!!
        val uncertain=tree.markUncertain(TreeNodeFence.of(started))
        assertEquals(TreeNodeState.UNCERTAIN,uncertain.state)
        assertEquals(WorkspaceAssignmentState.UNCERTAIN,registry.getAssignment(scope.workspaceId,uncertain.assignmentId!!)!!.state)
        assertEquals(96L,tree.getRoot(parent.rootId)!!.executions)
        denied { tree.reserveFirstExecution(TreeNodeFence.of(uncertain),"another-process") }
    }
    @Test fun settlementResetsOnlyOnceAndLateSettlementCannotRenewClosedRoot() {
        val parent=root();val scope=workspace();val first=running(queued(parent,scope,"one"));val sibling=running(queued(parent,scope,"two"))
        val terminal=settle(first);val renewed=tree.getRoot(parent.rootId)!!
        assertEquals(0L,renewed.executions);assertEquals(1L,renewed.windowEpoch)
        assertEquals(terminal,settle(first));assertEquals(renewed,tree.getRoot(parent.rootId))
        tree.stopRequester(TreeNodeFence.of(parent));val closed=tree.getRoot(parent.rootId)
        settle(sibling);assertEquals(closed,tree.getRoot(parent.rootId))
    }
    @Test fun requesterStopCancelsOnlyQueuedAndPureDenialPrecedesCommit() {
        val central=root();val scope=workspace();val parent=running(queued(central,scope,"parent"))
        val queued=queued(parent,scope,"queued");val started=running(queued(parent,scope,"started"));val sibling=running(queued(central,scope,"sibling"))
        var captured:List<TreeNode>?=null
        val cancelled=tree.stopRequester(TreeNodeFence.of(parent)) { targets ->
            captured=targets
            assertEquals(parent,tree.getNode(parent.rootId,parent.nodeId));assertEquals(queued,tree.getNode(queued.rootId,queued.nodeId))
        }
        assertEquals(setOf(parent.nodeId,queued.nodeId),captured!!.map { it.nodeId }.toSet())
        assertEquals(listOf(queued.nodeId),cancelled.map { it.nodeId });assertNull(cancelled.single().botId)
        assertEquals(WorkspaceAssignmentState.PREPARED,registry.getAssignment(scope.workspaceId,queued.assignmentId!!)!!.state)
        assertEquals(started,tree.requireDispatch(TreeNodeFence.of(started)));assertEquals(sibling,tree.requireDispatch(TreeNodeFence.of(sibling)))
        assertFalse(tree.getRoot(central.rootId)!!.admissionsClosed)
        denied { tree.requireDispatch(TreeNodeFence.of(parent)) }
    }
    @Test fun explicitSubtreeCapturesExactlyOnceAndCannotRetargetSiblingOrReplacement() {
        val central=root();val scope=workspace();val parent=running(queued(central,scope,"parent"))
        val leaf=queued(parent,scope,"leaf");val sibling=running(queued(central,scope,"sibling"));var calls=0
        val targets=tree.cancelSubtree(TreeNodeFence.of(parent),"CANCELLED","request-key") { calls++;assertEquals(2,it.size) }
        assertEquals(setOf(parent.nodeId,leaf.nodeId),targets.map { it.nodeId }.toSet());assertEquals(1,calls)
        val after=tree.getRoot(parent.rootId);val events=count("bot_tree_event")
        assertEquals(listOf(parent.nodeId),tree.cancelSubtree(TreeNodeFence.of(parent),"CANCELLED","request-key") { calls++ }.map {it.nodeId})
        denied { tree.cancelSubtree(TreeNodeFence.of(sibling),"CANCELLED","request-key") }
        denied { tree.cancelSubtree(TreeNodeFence.of(parent).copy(cycleId="replacement"),"CANCELLED","request-key") }
        assertEquals(2,calls);assertEquals(after,tree.getRoot(parent.rootId));assertEquals(events,count("bot_tree_event"))
        assertEquals(sibling,tree.requireDispatch(TreeNodeFence.of(sibling)))
    }
    @Test fun cancellationRetryRepairsFailedStopWithoutCapturingSiblingOrLaterReplacement() {
        val central=root();val scope=workspace();val parent=running(queued(central,scope,"cleanup-parent"))
        val child=running(queued(parent,scope,"cleanup-child"));val sibling=running(queued(central,scope,"cleanup-sibling"))
        val captured=tree.cancelSubtree(TreeNodeFence.of(parent),"CANCELLED","cleanup-request")
        val attempted=mutableListOf<String>()
        val failure=runCatching {WorkspaceCoordinator.performCapturedCancellationStops(captured) { target ->
            attempted.add(target.nodeId);if(target.nodeId==parent.nodeId)throw IllegalStateException("controlled first STOP failure")
        }}.exceptionOrNull()
        assertTrue(failure is IllegalStateException);assertEquals(1,failure!!.suppressed.size)
        assertEquals(setOf(parent.nodeId,child.nodeId),attempted.toSet())
        registry.close();registry=WorkspaceRegistry(context)
        val before=count("bot_tree_event");val retry=tree.cancelSubtree(TreeNodeFence.of(parent),"CANCELLED","cleanup-request")
        val repaired=mutableListOf<String>();WorkspaceCoordinator.performCapturedCancellationStops(retry) {repaired.add(it.nodeId)}
        assertEquals(attempted.toSet(),repaired.toSet());assertEquals(before,count("bot_tree_event"))
        // Simulate a distinct later native owner; immutable capture must never adopt it.
        registry.treeTransaction {db ->
            db.execSQL("UPDATE bot_tree_node SET bot_id=?,cycle_id=?,assignment_revision=assignment_revision+1 WHERE root_id=? AND node_id=?",
                arrayOf("replacement-bot","replacement-cycle",child.rootId,child.nodeId))
            db.execSQL("UPDATE assignment SET bot_id=?,revision=revision+1 WHERE workspace_id=? AND assignment_id=?",
                arrayOf("replacement-bot",scope.workspaceId,child.assignmentId))
        }
        val safe=tree.cancelSubtree(TreeNodeFence.of(parent),"CANCELLED","cleanup-request")
        assertEquals(listOf(parent.nodeId),safe.map {it.nodeId});assertFalse(safe.any {it.nodeId==sibling.nodeId})
        assertEquals(sibling,tree.requireDispatch(TreeNodeFence.of(sibling)))
    }
    @Test fun terminalCancellationPreservesEvidenceAndFirstDispositionButForbidsUserResume() {
        val central=root();val scope=workspace();val done=settle(running(queued(central,scope,"done")))
        tree.cancelSubtree(TreeNodeFence.of(done),"CANCELLED","first-cancel")
        assertEquals(done,tree.getNode(done.rootId,done.nodeId))
        val first=registry.treeRead {db -> WorkspaceTreeStore.one(db,"bot_tree_event","root_id=? AND event_key=?",
            arrayOf(done.rootId,WorkspaceTreeStore.identity("cancel-target",done.nodeId,done.cycleId))) {it.getString(it.getColumnIndexOrThrow("source_receipt"))}}
        tree.cancelSubtree(TreeNodeFence.of(done),"CANCELLED","second-cancel")
        val second=registry.treeRead {db -> WorkspaceTreeStore.one(db,"bot_tree_event","root_id=? AND event_key=?",
            arrayOf(done.rootId,WorkspaceTreeStore.identity("cancel-target",done.nodeId,done.cycleId))) {it.getString(it.getColumnIndexOrThrow("source_receipt"))}}
        assertEquals(first,second);assertEquals(done,tree.getNode(done.rootId,done.nodeId))
        val control=registry.nativeControls().submit(UUID.randomUUID().toString(),scope.workspaceId,done.assignmentId!!,done.botId!!,
            done.assignmentRevision,WorkspaceControlType.RESUME,"Resume cancelled worker")
        denied {WorkspaceTreeControlStore(tree).claim(TreeNodeFence.of(done),control.requestId,"process",true,certificate(scope.workspaceId),Runnable {})}
        assertEquals(WorkspaceControlState.PENDING,registry.getControl(control.requestId)!!.state)
    }
    @Test fun cancellationCaptureRejectsSiblingInjectionEvenIfItsOwnDigestIsRepaired() {
        val central=root();val scope=workspace();val parent=running(queued(central,scope,"capture-parent"))
        val child=running(queued(parent,scope,"capture-child"));val sibling=running(queued(central,scope,"capture-sibling"))
        tree.cancelSubtree(TreeNodeFence.of(parent),"CANCELLED","capture-request")
        val captureKey=WorkspaceTreeStore.identity("cancel-capture","capture-request")
        val original=registry.treeRead {db -> tree.event(db,parent.rootId,captureKey)!!}
        val forged=JSONObject(original);forged.getJSONArray("targets").put(JSONObject().put("root",sibling.rootId).put("node",sibling.nodeId)
            .put("parent",sibling.parentNodeId).put("workspace",sibling.workspaceId).put("assignment",sibling.assignmentId)
            .put("bot",sibling.botId).put("cycle",sibling.cycleId).put("assignmentRevision",sibling.assignmentRevision))
        val raw=WorkspaceTreeEvidenceStore.canonical(forged)
        for(repairOwnDigest in listOf(false,true)) {
            registry.treeTransaction {db ->db.execSQL("UPDATE bot_tree_event SET receipt=?,payload_digest=? WHERE root_id=? AND event_key=?",
                arrayOf(raw,WorkspaceTreeStore.identity(if(repairOwnDigest)raw else original),parent.rootId,captureKey))}
            var callbacks=0
            val failure=runCatching {tree.cancelSubtree(TreeNodeFence.of(parent),"CANCELLED","capture-request") {callbacks++}}.exceptionOrNull()
            assertTrue("Corruption must surface: $failure",failure is TreeIntegrityException);assertEquals(0,callbacks)
        }
        assertEquals(sibling,tree.requireDispatch(TreeNodeFence.of(sibling)))
        assertNotEquals(child.nodeId,sibling.nodeId)
    }
    @Test fun cancellationReplayRejectsFullEventMetadataMutationBeforeAnyCleanup() {
        val central=root();val scope=workspace();val parent=running(queued(central,scope,"metadata-parent"))
        val sibling=running(queued(central,scope,"metadata-sibling"))
        tree.cancelSubtree(TreeNodeFence.of(parent),"CANCELLED","metadata-request")
        val mutations=mapOf("kind" to "OTHER","node_id" to sibling.nodeId,"cycle_id" to "replacement-cycle",
            "source_receipt" to "other-request","payload_ref" to sibling.nodeId,"payload_digest" to "b".repeat(64),
            "node_revision" to 999L,"wait_id" to "unrelated-wait")
        for(kind in listOf("cancel-capture","cancel-request")) {
            val key=WorkspaceTreeStore.identity(kind,"metadata-request")
            for((column,changed) in mutations) {
                val old=registry.treeRead {db -> WorkspaceTreeStore.one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(parent.rootId,key)) {c ->
                    val i=c.getColumnIndexOrThrow(column);if(c.isNull(i))null else if(column=="node_revision")c.getLong(i) else c.getString(i)
                }}
                registry.treeTransaction {db ->db.execSQL("UPDATE bot_tree_event SET $column=? WHERE root_id=? AND event_key=?",arrayOf(changed,parent.rootId,key))}
                try {
                    var callbacks=0
                    val failure=runCatching {tree.cancelSubtree(TreeNodeFence.of(parent),"CANCELLED","metadata-request") {callbacks++}}.exceptionOrNull()
                    assertTrue("$kind/$column must reject: $failure",failure is TreeIntegrityException);assertEquals(0,callbacks)
                } finally {registry.treeTransaction {db ->db.execSQL("UPDATE bot_tree_event SET $column=? WHERE root_id=? AND event_key=?",arrayOf(old,parent.rootId,key))}}
            }
        }
    }
    @Test fun cancellationCaptureRequiresRootedActualAncestryAndStrictVersionEvenWithReboundDigests() {
        val central=root();val scope=workspace();val parent=running(queued(central,scope,"ancestry-parent"))
        val child=running(queued(parent,scope,"ancestry-child"));val sibling=running(queued(central,scope,"ancestry-sibling"))
        tree.cancelSubtree(TreeNodeFence.of(parent),"CANCELLED","ancestry-request")
        val captureKey=WorkspaceTreeStore.identity("cancel-capture","ancestry-request");val requestKey=WorkspaceTreeStore.identity("cancel-request","ancestry-request")
        val originals=registry.treeRead {db -> tree.event(db,parent.rootId,captureKey)!! to tree.event(db,parent.rootId,requestKey)!!}
        for(case in listOf("outside-parent","cycle","version-string","request-version-string")) {
            val capture=JSONObject(originals.first);val request=JSONObject(originals.second)
            val childRow=(0 until capture.getJSONArray("targets").length()).map {capture.getJSONArray("targets").getJSONObject(it)}.single {it.getString("node")==child.nodeId}
            when(case) {
                "outside-parent"->childRow.put("parent",sibling.nodeId)
                "cycle"->childRow.put("parent",child.nodeId)
                "version-string"->capture.put("version","1")
                else->request.put("version","1")
            }
            val raw=WorkspaceTreeEvidenceStore.canonical(capture);request.put("captureSha256",WorkspaceTreeStore.identity(raw))
            val bound=WorkspaceTreeEvidenceStore.canonical(request)
            registry.treeTransaction {db ->
                db.execSQL("UPDATE bot_tree_event SET receipt=?,payload_digest=? WHERE root_id=? AND event_key=?",arrayOf(raw,WorkspaceTreeStore.identity(raw),parent.rootId,captureKey))
                db.execSQL("UPDATE bot_tree_event SET receipt=?,payload_digest=? WHERE root_id=? AND event_key=?",arrayOf(bound,WorkspaceTreeStore.identity(bound),parent.rootId,requestKey))
            }
            var callbacks=0
            val failure=runCatching {tree.cancelSubtree(TreeNodeFence.of(parent),"CANCELLED","ancestry-request") {callbacks++}}.exceptionOrNull()
            assertTrue("$case must reject: $failure",failure is TreeIntegrityException);assertEquals(0,callbacks)
        }
    }
    @Test fun preparedFreshCentralRetryConservesChargeSourceAndOnlyRecordsDistinctTrigger() {
        val parent=root();val scope=workspace()
        fun source(call:String)=CentralInvocationSource(origin.storageGeneration,origin.ownerCentralSessionId,origin.originUserMessageId,"batch-$call",call,"native-runtime-run-distinct-from-cycle","process")
        val originalSource=source("original");val freshSource=source("fresh")
        val original=request(scope,"retry").copy(centralSource=originalSource,sourceReceipt=WorkspaceTreeStore.centralReceipt(originalSource))
        assertNotEquals(parent.cycleId,originalSource.sourceRunId)
        val wrongUser=CentralInvocationSource(origin.storageGeneration,origin.ownerCentralSessionId,"different-user",
            "batch-wrong-user","wrong-user",originalSource.sourceRunId,"process")
        denied { tree.admitChild(TreeNodeFence.of(parent),original.copy(centralSource=wrongUser,
            sourceReceipt=WorkspaceTreeStore.centralReceipt(wrongUser))) }
        val child=tree.admitChild(TreeNodeFence.of(parent),original)
        val fresh=original.copy(centralSource=freshSource,sourceReceipt=WorkspaceTreeStore.centralReceipt(freshSource))
        val before=tree.getRoot(parent.rootId);val events=count("bot_tree_event")
        denied { tree.retryPreparedCentral(TreeNodeFence.of(parent),TreeNodeFence.of(child),fresh.copy(centralSource=wrongUser,
            sourceReceipt=WorkspaceTreeStore.centralReceipt(wrongUser))) }
        denied { tree.retryPreparedCentral(TreeNodeFence.of(parent),TreeNodeFence.of(child),original) }
        assertEquals(child,tree.retryPreparedCentral(TreeNodeFence.of(parent),TreeNodeFence.of(child),fresh))
        assertEquals(child,tree.retryPreparedCentral(TreeNodeFence.of(parent),TreeNodeFence.of(child),fresh))
        assertEquals(events+1,count("bot_tree_event"));assertEquals(before,tree.getRoot(parent.rootId))
        denied { tree.retryPreparedCentral(TreeNodeFence.of(parent),TreeNodeFence.of(child),fresh.copy(mission="changed")) }
        denied { tree.retryPreparedCentral(TreeNodeFence.of(parent),TreeNodeFence.of(child),fresh.copy(centralSource=null)) }
        tree.reserveFirstExecution(TreeNodeFence.of(child),"process")
        denied { tree.retryPreparedCentral(TreeNodeFence.of(parent),TreeNodeFence.of(tree.getNode(child.rootId,child.nodeId)!!),fresh) }
        assertEquals(before!!.executions+1,tree.getRoot(parent.rootId)!!.executions)
    }
    @Test fun malformedSourceCertificateAndStaleBoundRevisionFailClosedWithoutNewTreeState() {
        val parent=root();val scope=workspace();val child=queued(parent,scope,"typed")
        val reservation=tree.reserveFirstExecution(TreeNodeFence.of(child),"process");val started=tree.getNode(child.rootId,child.nodeId)!!
        registry.bindBot(scope.workspaceId,child.assignmentId!!,reservation.assignmentRevision,"bot")
        val events=count("bot_tree_event")
        denied { tree.bindExecution(TreeNodeFence.of(started),"bot","cycle","opaque-hash") }
        denied { tree.bindExecution(TreeNodeFence.of(started),"bot","cycle",certificate("foreign-scope")) }
        denied { tree.bindExecution(TreeNodeFence.of(started),"other-bot","cycle",certificate(scope.workspaceId)) }
        assertEquals(started,tree.getNode(child.rootId,child.nodeId));assertEquals(events,count("bot_tree_event"))
        val bound=tree.bindExecution(TreeNodeFence.of(started),"bot","cycle",certificate(scope.workspaceId))
        assertEquals(TreeNodeState.RUNNING,bound.state)
        denied { tree.bindExecution(TreeNodeFence.of(started),"bot","cycle",certificate(scope.workspaceId)) }
    }
    @Test fun explicitNeverStartedWithdrawalNeedsRealCentralExitButNoFabricatedIncorporation() {
        val central=root();val scope=workspace();val child=queued(central,scope,"withdraw-before-exit")
        tree.cancelSubtree(TreeNodeFence.of(child),"CANCELLED","explicit-withdrawal")
        assertEquals(TreeNodeState.RUNNING,tree.getNode(central.rootId,central.nodeId)!!.state)
        assertFalse(tree.getRoot(central.rootId)!!.admissionsClosed)
        val terminal=tree.finishCentral(TreeNodeFence.of(central),"confirmed-exit",false)
        assertEquals(TreeNodeState.TERMINAL,terminal.state);assertEquals("COMPLETED",tree.getRoot(central.rootId)!!.closeReason)
        registry.treeRead {db ->db.rawQuery("SELECT COUNT(*) FROM bot_tree_event WHERE root_id=? AND kind IN ('CENTRAL_CLAIM','CENTRAL_INCORPORATED')",arrayOf(central.rootId)).use {c->c.moveToFirst();assertEquals(0,c.getInt(0))}}
        val cancelled=tree.getNode(child.rootId,child.nodeId)!!;assertNull(cancelled.botId);assertNull(cancelled.executionEventId)
        assertNotNull(cancelled.terminalHash);assertNotNull(cancelled.reportReceipt)
    }
    @Test fun withdrawalReconcilesExistingConfirmedCentralWaitWithoutNewExecutionOrExit() {
        val central=root();val scope=workspace();val child=queued(central,scope,"withdraw-after-exit")
        tree.finishCentral(TreeNodeFence.of(central),"confirmed-retained-exit",false)
        val exitKey=WorkspaceTreeStore.identity("central-exit",central.cycleId)
        val before=registry.treeRead {db ->tree.event(db,central.rootId,exitKey)!!}
        val executions=registry.treeRead {db ->db.rawQuery("SELECT COUNT(*) FROM bot_tree_event WHERE root_id=? AND kind IN ('EXECUTION','USER_EXECUTION','CHILD_WAIT_EXECUTION')",arrayOf(central.rootId)).use {c->c.moveToFirst();c.getInt(0)}}
        tree.cancelSubtree(TreeNodeFence.of(child),"CANCELLED","late-explicit-withdrawal")
        val closed=tree.getRoot(central.rootId)!!;assertEquals("COMPLETED",closed.closeReason)
        val events=count("bot_tree_event");registry.close();registry=WorkspaceRegistry(context)
        assertEquals(TreeNodeState.TERMINAL,WorkspaceTreeCentralStore(tree).reconcileWithdrawals(central.rootId).state)
        assertEquals(closed,tree.getRoot(central.rootId));assertEquals(events,count("bot_tree_event"))
        registry.treeRead {db ->
            assertEquals(before,tree.event(db,central.rootId,exitKey))
            db.rawQuery("SELECT COUNT(*) FROM bot_tree_event WHERE root_id=? AND kind IN ('EXECUTION','USER_EXECUTION','CHILD_WAIT_EXECUTION')",arrayOf(central.rootId)).use {c->c.moveToFirst();assertEquals(executions,c.getInt(0))}
            db.rawQuery("SELECT COUNT(*) FROM bot_tree_event WHERE root_id=? AND kind='CENTRAL_EXIT'",arrayOf(central.rootId)).use {c->c.moveToFirst();assertEquals(1,c.getInt(0))}
        }
    }
    @Test fun withdrawnQueuedSiblingDoesNotExemptStartedOrUnincorporatedWork() {
        val central=root();val scope=workspace();val queued=queued(central,scope,"withdraw-only-this")
        val running=running(queued(central,scope,"still-await"))
        tree.finishCentral(TreeNodeFence.of(central),"confirmed-mixed-exit",false)
        tree.cancelSubtree(TreeNodeFence.of(queued),"CANCELLED","withdraw-one")
        assertEquals(TreeNodeState.WAITING,WorkspaceTreeCentralStore(tree).reconcileWithdrawals(central.rootId).state)
        val terminal=settle(running)
        WorkspaceTreeEvidenceStore(tree).markReported(terminal.rootId,terminal.nodeId,terminal.terminalRef!!,terminal.terminalHash!!,"real-running-report")
        assertEquals(TreeNodeState.WAITING,WorkspaceTreeCentralStore(tree).reconcileWithdrawals(central.rootId).state)
        assertFalse(tree.getRoot(central.rootId)!!.admissionsClosed)
    }
    @Test fun budgetCancellationIsNotSuccessfulDependencyWithdrawal() {
        val central=root();val scope=workspace();val child=queued(central,scope,"budget-not-withdrawal")
        tree.finishCentral(TreeNodeFence.of(central),"confirmed-budget-exit",false)
        tree.cancelSubtree(TreeNodeFence.of(child),"BUDGET","budget-disposition")
        assertEquals(TreeNodeState.WAITING,WorkspaceTreeCentralStore(tree).reconcileWithdrawals(central.rootId).state)
        assertFalse(tree.getRoot(central.rootId)!!.admissionsClosed)
    }
    @Test fun withdrawalCannotCloseWithoutConfirmedExitOrWithCorruptedCapture() {
        val central=root();val scope=workspace();val child=queued(central,scope,"withdraw-proof")
        tree.cancelSubtree(TreeNodeFence.of(child),"CANCELLED","proof-withdrawal")
        registry.treeTransaction {db ->db.execSQL("UPDATE bot_tree_node SET state='WAITING' WHERE root_id=? AND node_id=?",arrayOf(central.rootId,central.nodeId))}
        assertEquals(TreeNodeState.WAITING,WorkspaceTreeCentralStore(tree).reconcileWithdrawals(central.rootId).state)
        registry.treeTransaction {db ->db.execSQL("UPDATE bot_tree_event SET payload_digest=? WHERE root_id=? AND event_key=?",
            arrayOf("b".repeat(64),central.rootId,WorkspaceTreeStore.identity("cancel-capture","proof-withdrawal")))}
        denied {tree.finishCentral(TreeNodeFence.of(tree.getNode(central.rootId,central.nodeId)!!),"would-be-confirmed-exit",false)}
        assertFalse(tree.getRoot(central.rootId)!!.admissionsClosed)
        registry.treeRead {db ->assertNull(tree.event(db,central.rootId,WorkspaceTreeStore.identity("central-exit",central.cycleId)))}
    }
    @Test fun centralCompletionWaitsForReportedAndIncorporatedAwaitedChild() {
        val parent=root();val scope=workspace();val child=running(queued(parent,scope,"awaited"))
        val waiting=tree.finishCentral(TreeNodeFence.of(parent),"confirmed-exit-fixture",false)
        assertEquals(TreeNodeState.WAITING,waiting.state);assertFalse(tree.getRoot(parent.rootId)!!.admissionsClosed)
        val terminal=settle(child)
        denied { tree.attachCentralExecution(TreeNodeFence.of(waiting),"new-central-cycle","fixture-source","claim",true,child.nodeId) }
        WorkspaceTreeEvidenceStore(tree).markReported(child.rootId,child.nodeId,terminal.terminalRef!!,terminal.terminalHash!!,"reported-fixture")
        val attached=tree.attachCentralExecution(TreeNodeFence.of(waiting),"new-central-cycle","fixture-source","claim",true,child.nodeId)
        denied { tree.finishCentral(TreeNodeFence.of(attached),"second-exit",false) }
        tree.recordCentralIncorporated(child.rootId,child.nodeId,"claim","confirmed-checkpoint-fixture")
        val current=tree.getNode(parent.rootId,parent.nodeId)!!
        val completed=tree.finishCentral(TreeNodeFence.of(current),"second-exit",false)
        assertEquals(TreeNodeState.TERMINAL,completed.state);assertEquals("COMPLETED",tree.getRoot(parent.rootId)!!.closeReason)
        val closed=tree.getRoot(parent.rootId)
        assertEquals(closed,tree.beginRoot(origin,"new-source","new-runtime"))
    }
    @Test fun replyNoneDoesNotBlockConfirmedCentralCompletionOrReopenOnLateReport() {
        val parent=root();val scope=workspace()
        val child=running(tree.admitChild(TreeNodeFence.of(parent),request(scope,"detached").copy(reply=TreeReply.NONE)))
        tree.finishCentral(TreeNodeFence.of(parent),"confirmed-exit",false)
        val closed=tree.getRoot(parent.rootId)
        assertEquals(child,tree.requireDispatch(TreeNodeFence.of(child)))
        val terminal=settle(child)
        WorkspaceTreeEvidenceStore(tree).markReported(child.rootId,child.nodeId,terminal.terminalRef!!,terminal.terminalHash!!,"late-report")
        assertEquals(closed,tree.getRoot(parent.rootId))
    }

}
