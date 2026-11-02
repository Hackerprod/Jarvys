package com.jarvys.agent.workspaces

import android.app.Application
import com.jarvys.agent.ChatTestStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,shadows=[com.jarvys.agent.ArtifactOsShadow::class])
class WorkspaceCentralResumeStoreTest {
    @get:Rule val chat=ChatTestStore(registerApplicationFilesDirectory=true)
    private lateinit var registry:WorkspaceRegistry
    private lateinit var trees:WorkspaceTreeStore
    private lateinit var root:String
    @Before fun setup(){
        val context=RuntimeEnvironment.getApplication();context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
        registry=WorkspaceRegistry(context);registry.upgradeCentralResumeSchema();trees=WorkspaceTreeStore(registry)
        root=trees.beginRoot(TreeOrigin("generation",WorkspaceIdentity.CENTRAL_SESSION,"user","a".repeat(64)),"source","cycle0").rootId
    }
    @After fun cleanup(){registry.close()}
    private fun fence()=TreeNodeFence.of(requireNotNull(trees.getNode(root,root)))
    private fun park()=trees.parkCentral(fence(),"run0","exact-source-certificate")
    private fun reserve(row:CentralResumeRecord)=trees.reserveCentralSuspension(row,"process","run1","token1","cycle1")!!
    @Test fun parkIsAtomicPendingWithoutIntentOrBudgetReset(){
        val before=trees.getRoot(root)!!;val row=park();val parent=trees.getNode(root,root)!!
        assertEquals("PREPARED",row.state);assertEquals(TreeNodeState.WAITING,parent.state);assertNull(parent.controlIntent)
        assertEquals(before,trees.getRoot(root));assertTrue(registry.treeRead{WorkspaceCentralResumeStore.hasPending(it,root)})
    }
    @Test fun duplicateConfirmedParkCannotCreateSecondContinuation(){
        val original=fence();val first=park();val second=trees.parkCentral(original,"run0","exact-source-certificate")
        assertEquals(first,second);assertEquals(1,registry.treeRead{db->db.rawQuery("SELECT count(*) FROM bot_tree_central_resume",null).use{it.moveToFirst();it.getInt(0)}})
    }
    @Test fun reservationHasOneWinnerAndChargeOccursOnlyOnBinding(){
        val row=park();val winner=reserve(row)
        assertNull(trees.reserveCentralSuspension(row,"other","run2","token2","cycle2"))
        assertEquals(1L,trees.getRoot(root)!!.executions);assertEquals(row.resumeId,trees.getNode(root,root)!!.controlIntent)
        val bound=trees.bindCentralResume(winner,"confirmed-successor")
        assertEquals(2L,trees.getRoot(root)!!.executions);assertEquals("BOUND",bound.state)
        assertEquals(bound,trees.bindCentralResume(winner,"confirmed-successor"));assertEquals(2L,trees.getRoot(root)!!.executions)
    }
    @Test fun exactIncorporationClearsOnlyItsIntentAndThenCanFinish(){
        val reserved=reserve(park());val bound=trees.bindCentralResume(reserved,"successor")
        val incorporated=trees.incorporateCentralResume(bound,"incorporated")
        assertEquals("INCORPORATED",incorporated.state);assertNull(trees.getNode(root,root)!!.controlIntent)
        assertEquals(incorporated,trees.incorporateCentralResume(bound,"incorporated"))
        trees.finishCentral(fence(),"actual-final",false)
        assertEquals("COMPLETED",trees.getRoot(root)!!.closeReason)
    }
    @Test fun pendingSuspensionPreventsHistoricalSuccessfulExitClosingRoot(){
        park();trees.finishCentral(fence(),"old-exit",false)
        assertFalse(trees.getRoot(root)!!.admissionsClosed);assertEquals(TreeNodeState.WAITING,trees.getNode(root,root)!!.state)
    }
    @Test fun stopBeforeReservationCancelsPreparedAndCannotReopen(){
        val row=park();trees.finishCentral(fence(),"stop",true)
        assertEquals("CANCELLED",trees.getCentralResume(row.resumeId)!!.state)
        assertNull(trees.reserveCentralSuspension(row,"process","run","token","cycle"))
        assertEquals("STOPPED",trees.getRoot(root)!!.closeReason)
    }
    @Test fun stopAfterReservationFencesBindingAndPreservesCounter(){
        val row=reserve(park());trees.finishCentral(fence(),"stop",true)
        assertEquals("UNCERTAIN",trees.getCentralResume(row.resumeId)!!.state)
        try{trees.bindCentralResume(row,"late");fail("Stopped reservation bound")}catch(expected:TreeIneligibleException){}
        assertEquals(1L,trees.getRoot(root)!!.executions)
    }
    @Test fun ambiguousBridgeNeverResetsToPreparedOrReusesOwner(){
        val row=reserve(park());trees.uncertainCentralResume(row)
        assertEquals("UNCERTAIN",trees.getCentralResume(row.resumeId)!!.state)
        assertNull(trees.reserveCentralSuspension(row,"process","run","token","cycle"))
        try{trees.bindCentralResume(row,"late");fail("Uncertain reservation bound")}catch(expected:TreeIneligibleException){}
    }
    @Test fun mutatedCertificateRejectedBeforeReservation(){
        val row=park()
        registry.treeTransaction{it.execSQL("UPDATE bot_tree_central_resume SET source_certificate='changed' WHERE resume_id=?",arrayOf(row.resumeId))}
        try{reserve(row);fail("Changed source accepted")}catch(expected:TreeIntegrityException){}
        assertNull(trees.getNode(root,root)!!.controlIntent)
    }
    @Test fun replacementOwnerCannotBindOrIncorporate(){
        val row=reserve(park());val forged=row.copy(ownerToken="other")
        try{trees.bindCentralResume(forged,"receipt");fail("Owner changed")}catch(expected:TreeIntegrityException){}
        val bound=trees.bindCentralResume(row,"receipt")
        try{trees.incorporateCentralResume(bound.copy(ownerRun="other"),"receipt");fail("Owner changed")}catch(expected:TreeIntegrityException){}
        assertEquals(row.resumeId,trees.getNode(root,root)!!.controlIntent)
    }
    @Test fun predecessorStopAfterBindingStillDeniesSuccessorWithoutTouchingSiblingRoot(){
        val parked=park();val reserved=reserve(parked);val bound=trees.bindCentralResume(reserved,"successor")
        trees.incorporateCentralResume(bound,"incorporated")
        val sibling=trees.beginRoot(TreeOrigin("generation",WorkspaceIdentity.CENTRAL_SESSION,"other-user","b".repeat(64)),"other-source","other-cycle")
        WorkspaceTreeStops.latchExact(registry.treeStorageIdentity,root,root,parked.parentCycle)
        try{WorkspaceTreeStops.requireAllowed(registry.treeStorageIdentity,root,root,"cycle1");fail("Lost predecessor STOP")}catch(expected:TreeIneligibleException){}
        WorkspaceTreeStops.requireAllowed(registry.treeStorageIdentity,sibling.rootId,sibling.rootId,"other-cycle")
        assertFalse(trees.getRoot(sibling.rootId)!!.admissionsClosed)
    }
    @Test fun executionWindowExhaustionCannotChargeOrBind(){
        val reserved=reserve(park())
        registry.treeTransaction{it.execSQL("UPDATE bot_tree_root SET executions=? WHERE root_id=?",arrayOf(WorkspaceTreePolicy.MAX_EXECUTIONS,root))}
        try{trees.bindCentralResume(reserved,"late");fail("Execution window bypassed")}catch(expected:TreeIneligibleException){}
        assertEquals(WorkspaceTreePolicy.MAX_EXECUTIONS,trees.getRoot(root)!!.executions)
        assertEquals("RESERVED",trees.getCentralResume(reserved.resumeId)!!.state)
    }
    @Test fun explicitRecoveryChangesOnlyUnclaimedProvenanceAndNeverReopensStoppedRoot(){
        val row=park();val rootBefore=trees.getRoot(root)
        val renewed=trees.recoverCentralSuspension(row,"fresh-action","new-process",Runnable{})
        assertEquals(row.revision+1,renewed.revision);assertEquals("PREPARED",renewed.state)
        assertEquals(rootBefore,trees.getRoot(root));assertNull(renewed.ownerToken)
        try{trees.recoverCentralSuspension(row,"stale-action","new-process",Runnable{});fail("Stale recovery accepted")}catch(expected:TreeIntegrityException){}
        trees.stopCentralResume(renewed)
        try{trees.recoverCentralSuspension(trees.getCentralResume(row.resumeId)!!,"after-stop","new-process",Runnable{});fail("Stopped root recovered")}catch(expected:TreeIneligibleException){}
    }
    @Test fun recoveryNativeGuardFailureRollsBackRevisionAndAudit(){
        val row=park();var calls=0
        try{trees.recoverCentralSuspension(row,"denied","new-process",Runnable{calls++;if(calls==2)throw IllegalStateException("native action revoked")});fail("Revoked action accepted")}
        catch(expected:IllegalStateException){}
        assertEquals(row,trees.getCentralResume(row.resumeId))
        assertEquals(0,registry.treeRead{db->db.rawQuery("SELECT count(*) FROM bot_tree_event WHERE kind='CENTRAL_RESUME_RECOVERY'",null).use{it.moveToFirst();it.getInt(0)}})
    }

}
