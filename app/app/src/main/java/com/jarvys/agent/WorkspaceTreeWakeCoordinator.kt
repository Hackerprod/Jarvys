package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.crew.*
import com.jarvys.agent.workspaces.*
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/** Process-local exact parked-owner capabilities. Retained metadata/scans never populate this map. */
internal class WorkspaceTreeWakeCoordinator(context:Context,private val session:String,
    private val manager:CrewManager,private val worker:WorkspaceTreeWorkerCoordinator):CrewManager.TreePublicationListener {
    private val app=context.applicationContext
    private val ledger=LocalRunStore(app)
    private data class Park(val bot:CrewManager.Bot,val token:CancellationToken,val ticket:CrewTreeSupport.WaitTicket,
        val source:CrewCheckpointStore.ConfirmedSnapshot)
    private val parked=ConcurrentHashMap<String,Park>()
    fun onPark(bot:CrewManager.Bot,token:CancellationToken,ticket:CrewTreeSupport.WaitTicket,
        saved:CrewCheckpointStore.ConfirmedSnapshot,lease:CrewManager.TreeParkLease) {
        lease.requireCurrent(bot,token,ticket,saved)
        val capability=Park(bot,token,ticket,saved)
        check(parked.putIfAbsent(ticket.waitId,capability)==null) { "Wait already has a live owner" }
        dispatch(capability)
    }
    override fun onTreeResult(bot:CrewManager.Bot,evidence:CrewResultEvidence,lease:CrewManager.TreePublicationLease) {
        lease.requireCurrent(bot,evidence.evidenceId,evidence.cycleId)
        check(lease.kind==CrewManager.TreePublicationLease.Kind.RESULT && evidence.conversationId==session)
        val saved=requireNotNull(ledger.readCrewResultEvidence(session,evidence.evidenceId))
        check(saved.contentSha256==evidence.contentSha256 && saved.botId==bot.id && saved.cycleId==bot.cycleId())
        val checkpoint=requireNotNull(CrewCheckpointStore(app.filesDir).confirmDurable(session,bot.id,bot.scopeIdentity()))
        WorkspaceTreeWorkerCoordinator.requireCertifiedCheckpointIdentity(app,session,checkpoint)
        val root=WorkspaceRegistry(app).use { db ->
            val tree=WorkspaceTreeStore(db);val node=tree.findAssignment(WorkspaceIdentity.workspaceId(session),bot.assignmentId()) ?: return
            check(node.botId==bot.id && node.cycleId==evidence.cycleId)
            val outcome=when(evidence.reportedStatus){"DONE"->"SUCCESS";"STOPPED"->"STOPPED";else->"FAILED"}
            tree.settle(TreeNodeFence.of(node),outcome,evidence.evidenceId,evidence.contentSha256,"native-result:"+evidence.evidenceId)
            WorkspaceTreeEvidenceStore(tree).markReported(node.rootId,node.nodeId,evidence.evidenceId,evidence.contentSha256,"native-result:"+evidence.contentSha256)
            node.rootId
        }
        dispatchRoot(root)
    }
    override fun onTreeQuestion(bot:CrewManager.Bot,evidence:CrewCoordinationEvidence,lease:CrewManager.TreePublicationLease) {
        lease.requireCurrent(bot,evidence.evidenceId,evidence.cycleId)
        val source=worker.questionInvocationSource(bot,evidence,lease)
        val saved=requireNotNull(ledger.readCrewCoordinationEvidence(session,evidence.evidenceId))
        check(saved.contentSha256==evidence.contentSha256 && saved.botId==bot.id && saved.cycleId==bot.cycleId())
        var denied:List<TreeNode> = emptyList()
        val root=WorkspaceRegistry(app).use { db ->
            val tree=WorkspaceTreeStore(db);val node=requireNotNull(tree.findAssignment(WorkspaceIdentity.workspaceId(session),bot.assignmentId()))
            val store=WorkspaceTreeEvidenceStore(tree)
            store.recordQuestion(TreeNodeFence.of(node),evidence.evidenceId,evidence.contentSha256,source)
            store.markReported(node.rootId,node.nodeId,evidence.evidenceId,evidence.contentSha256,"native-question:"+evidence.contentSha256) { targets ->
                denied=targets
                targets.forEach { WorkspaceTreeStops.latchExact(db.treeStorageIdentity,it.rootId,it.nodeId,it.cycleId) }
            }
            node.rootId
        }
        // Physical stop must be outside Registry. Exact captured cycles only, never replacements.
        denied.forEach { target -> target.botId?.let { id -> manager.bot(id)?.let { current ->
            val expected=current.token
            if(current.cycleId()==target.cycleId) manager.stopForUserControl(id,expected)
        } } }
        dispatchRoot(root)
    }
    private fun dispatchRoot(root:String) { parked.values.filter { it.ticket.rootId==root }.forEach {
        try { dispatch(it) } catch(expected:TreeIneligibleException) { parked.remove(it.ticket.waitId,it) }
    } }
    private fun dispatch(owner:Park) {
        if(manager.bot(owner.bot.id)!==owner.bot || owner.bot.token!==owner.token || owner.token.isCancelled ||
            owner.bot.cycleId()!=owner.ticket.cycleId || !manager.isTreeParkQuiescent(owner.bot,owner.token))return
        val ready=WorkspaceRegistry(app).use { db ->
            val waits=WorkspaceTreeWaitStore(WorkspaceTreeStore(db))
            try { waits.prepareReady(owner.ticket.rootId,owner.ticket.waitId) } catch(expected:TreeIneligibleException) { return }
        } ?: return
        check(ready.checkpointSha256==owner.source.payloadSha256 && ready.parentCycleId==owner.ticket.cycleId)
        val question=WorkspaceRegistry(app).use { db ->
            WorkspaceTreeWaitStore(WorkspaceTreeStore(db)).readyCauses(ready).singleOrNull { it.kind=="NEEDS_INPUT" }
        }?.let { requireNotNull(ledger.readCrewCoordinationEvidence(session,it.evidenceRef)).also { e -> check(e.contentSha256==it.evidenceHash) } }
        val admission=java.util.concurrent.Callable {
            if(!parked.remove(owner.ticket.waitId,owner))throw TreeIneligibleException("Live wait owner consumed")
            WorkspaceRegistry(app).use { db ->
                WorkspaceTreeWaitStore(WorkspaceTreeStore(db)).claimWait(ready,CrewProcessIdentity.ID,
                    com.jarvys.agent.chatdb.ChatDatabaseBridge.getInstance(app).connectionProjectionEvidenceSync().generation(),
                    worker.claimOwnerToken(owner.bot,owner.token))
            }
        }
        val claim=if(question==null)admission.call() else manager.withCurrentTreeQuestion(question,admission)
        worker.resumeClaim(owner.bot,owner.token,claim,Consumer { successor ->
            WorkspaceTreeDelivery.stageReports(app,session,manager,successor,claim)
        })
    }
}
