package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.crew.*
import com.jarvys.agent.workspaces.*
import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.Callable
import java.util.function.Consumer

/** Native worker boundary over the one Registry. No scan, DTO, or checkpoint grants dispatch. */
internal class WorkspaceTreeWorkerCoordinator(context: Context, private val session: String,
    private val manager: CrewManager) : CrewTreeSupport {
    private val app = context.applicationContext
    private val workspace = WorkspaceIdentity.workspaceId(session)
    private val checkpoints = CrewCheckpointStore(app.filesDir)
    private val ownerKeys = Collections.synchronizedMap(IdentityHashMap<CancellationToken,String>())
    private val questionSources = Collections.synchronizedMap(IdentityHashMap<CrewManager.Bot,CrewInvocationWitness>())
    private data class ResumeOwner(val bot:CrewManager.Bot,val token:CancellationToken,val claim:TreeWaitClaim,val user:TreeUserWaitClaim?=null)
    private val commits = Collections.synchronizedMap(IdentityHashMap<CrewManager.ResumeCheckpointCommit,ResumeOwner>())
    private data class UserResumeOwner(val bot:CrewManager.Bot,val token:CancellationToken,val claim:TreeControlClaim,
        val source:CrewCheckpointStore.ConfirmedSnapshot)
    private val userCommits = Collections.synchronizedMap(IdentityHashMap<CrewManager.ResumeCheckpointCommit,UserResumeOwner>())
    private val wake = WorkspaceTreeWakeCoordinator(app,session,manager,this)
    fun install() { check(manager.conversationId()==session);manager.installTreeSupportIfAbsent(this,wake) }
    private fun generation() = ChatDatabaseBridge.getInstance(app).connectionProjectionEvidenceSync().generation()
    private fun node(db:WorkspaceRegistry,bot:CrewManager.Bot):TreeNode =
        requireNotNull(WorkspaceTreeStore(db).findAssignment(workspace,bot.assignmentId())) { "Worker is not enrolled" }.also {
            check(it.workspaceId==workspace && (it.botId==null || it.botId==bot.id)) { "Tree bot owner changed" }
        }
    override fun enrolled(bot:CrewManager.Bot):Boolean = WorkspaceRegistry(app).use { db ->
        val found=WorkspaceTreeStore(db).findAssignment(workspace,bot.assignmentId()) ?: return@use false
        check(found.workspaceId==workspace && (found.botId==null || found.botId==bot.id)) { "Tree enrollment owner changed" };true
    }
    private fun current(db:WorkspaceRegistry,bot:CrewManager.Bot,token:CancellationToken):TreeNode {
        check(manager.bot(bot.id)===bot && bot.token===token) { "Native tree owner changed" };token.throwIfCancelled()
        val value=node(db,bot);check(value.botId==bot.id && value.cycleId==bot.cycleId()) { "Native tree cycle changed" }
        val root=requireNotNull(WorkspaceTreeStore(db).getRoot(value.rootId))
        check(root.origin.storageGeneration==generation()) { "Tree storage owner changed" }
        return value
    }
    override fun onRequesterStop(bot:CrewManager.Bot,token:CancellationToken,cycleId:String) {
        WorkspaceRegistry(app).use { db ->
            val tree=WorkspaceTreeStore(db)
            val value=tree.findAssignment(workspace,bot.assignmentId()) ?: return@use
            // Captured-cycle denial persists even if durable ownership changes before the CAS.
            WorkspaceTreeStops.latchExact(db.treeStorageIdentity,value.rootId,value.nodeId,cycleId)
            check(manager.bot(bot.id)===bot && bot.token===token && bot.cycleId()==cycleId &&
                value.botId==bot.id && value.cycleId==cycleId) { "STOP requester cycle changed" }
            val root=requireNotNull(tree.getRoot(value.rootId))
            check(root.origin.storageGeneration==generation()) { "STOP storage generation changed" }
            // A native PAUSE/cascade already performed this durable denial. Never increment it again.
            if(value.stopEpoch>0 && value.admissionsClosed && value.wakesClosed)return@use
            tree.stopRequester(TreeNodeFence.of(value)) { targets ->
                targets.forEach { WorkspaceTreeStops.latchExact(db.treeStorageIdentity,it.rootId,it.nodeId,it.cycleId) }
            }
        }
    }
    override fun requireCurrentDispatch(bot:CrewManager.Bot,token:CancellationToken) {
        WorkspaceRegistry(app).use { db -> val value=current(db,bot,token);WorkspaceTreeStore(db).requireDispatch(TreeNodeFence.of(value)) }
    }
    override fun admitOrdinaryEnqueue(bot:CrewManager.Bot,token:CancellationToken,mailbox:Runnable) {
        // Registry and generation reads precede the short native denial monitor.
        WorkspaceRegistry(app).use { db ->
            val value=current(db,bot,token);val tree=WorkspaceTreeStore(db);tree.requireDispatch(TreeNodeFence.of(value))
            val root=requireNotNull(tree.getNode(value.rootId,value.rootId))
            WorkspaceTreeStops.admitNativeEnqueue(db.treeStorageIdentity,value.rootId,root.cycleId,value.nodeId,value.cycleId,mailbox)
        }
    }
    override fun retainAnswer(bot:CrewManager.Bot,token:CancellationToken,source:CrewInvocationWitness,args:Map<String,Any?>):CoreToolResult? {
        check(bot.role.missionAccess!=CrewMissionAccess.READ_ONLY && bot.role.tools.contains("msg_send"))
        source.requireArguments(args);source.requireInvocationCurrent()
        check(source.source().toolName=="msg_send" && (args["type"] as? String)?.uppercase()=="ANSWER")
        val target=requireNotNull(args["to"] as? String);val text=requireNotNull(args["text"] as? String)
        require(text.isNotBlank())
        val refs=(args["refs"] ?: emptyList<String>()).let { value ->
            require(value is List<*> && value.all { it is String && it.isNotBlank() });value.map { it as String }
        }
        WorkspaceRegistry(app).use { db ->
            val tree=WorkspaceTreeStore(db);val parent=current(db,bot,token)
            val targetBot=manager.bot(target) ?: return null
            val child=tree.findAssignment(workspace,targetBot.assignmentId()) ?: return null
            if(child.state!=TreeNodeState.NEEDS_INPUT)return null
            check(child.rootId==parent.rootId && child.parentNodeId==parent.nodeId && child.botId==target && child.reply==TreeReply.AWAIT)
            val evidence=requireNotNull(LocalRunStore(app).readCrewCoordinationEvidence(session,requireNotNull(child.terminalRef)))
            check(evidence.kind=="QUESTION" && evidence.botId==target && evidence.cycleId==child.cycleId &&
                evidence.contentSha256==child.terminalHash && refs.contains(evidence.messageId)) { "Exact formal question reference required" }
            prepareInvocation(bot,token,source,args,"ANSWER_ARM")
            val receipt=sourceJson(source,args)
            val answer=manager.withCurrentTreeCycle(bot,token,parent.cycleId,Callable {
                // Only retained data. The child performs no enqueue, wake, or execution until fresh native USER Resume.
                WorkspaceTreeAnswerStore(tree).prepare(TreeNodeFence.of(parent),TreeNodeFence.of(child),text,receipt)
            })
            return CoreToolResult.complete("native-tree-answer:${answer.eventKey}\nRetained exact answer; child execution requires explicit native USER Resume.")
        }
    }
    private fun sourceJson(source:CrewInvocationWitness,args:Map<String,Any?>):String {
        source.requireArguments(args);source.requireInvocationCurrent();val s=source.source()
        val encoded=WorkspaceTreeEvidenceStore.canonical(JSONObject(args))
        return WorkspaceTreeEvidenceStore.canonical(JSONObject().put("version",1).put("executionSessionId",s.executionSessionId)
            .put("botId",s.botId).put("missionId",s.missionId).put("cycleId",s.cycleId).put("scopeIdentity",s.scopeIdentity)
            .put("checkpointPayloadSha256",s.checkpointPayloadSha256).put("callId",s.callId).put("toolName",s.toolName)
            .put("argumentsDigest",WorkspaceTreeStore.identity(encoded)))
    }
    override fun prepareInvocation(bot:CrewManager.Bot,token:CancellationToken,source:CrewInvocationWitness,args:Map<String,Any?>,kind:String) {
        val expected=when(kind) { "ADMISSION"->"delegate_subtask";"WAIT_ARM"->"wait_children";"QUESTION_ARM"->"ask_chief";"REPORT_DONE"->"report_done";"ANSWER_ARM"->"msg_send";else->error("Unknown native invocation kind") }
        check(source.source().toolName==expected) { "Native invocation kind changed" }
        val receipt=sourceJson(source,args);val encoded=WorkspaceTreeEvidenceStore.canonical(JSONObject(args))
        WorkspaceRegistry(app).use { db -> val value=current(db,bot,token)
            manager.withCurrentTreeCycle(bot,token,value.cycleId,Callable {
                WorkspaceTreeEvidenceStore(WorkspaceTreeStore(db)).recordInvocation(TreeNodeFence.of(value),source.source().callId,kind,receipt,encoded)
            })
        }
        if(kind=="QUESTION_ARM")questionSources[bot]=source
    }
    fun questionInvocationSource(bot:CrewManager.Bot,evidence:CrewCoordinationEvidence,lease:CrewManager.TreePublicationLease):String {
        lease.requireCurrent(bot,evidence.evidenceId,evidence.cycleId)
        check(lease.kind==CrewManager.TreePublicationLease.Kind.NEEDS_INPUT)
        val source=requireNotNull(questionSources[bot]);source.requireInvocationCurrent()
        check(source.source().cycleId==evidence.cycleId && source.source().botId==bot.id && source.source().toolName=="ask_chief")
        return WorkspaceRegistry(app).use { db -> invocation(db,current(db,bot,bot.token),source,"QUESTION_ARM").sourceReceipt }
    }
    private fun invocation(db:WorkspaceRegistry,value:TreeNode,source:CrewInvocationWitness,kind:String):TreeInvocationReceipt {
        val s=source.source()
        return WorkspaceTreeEvidenceStore(WorkspaceTreeStore(db)).invocationReceipts(value.rootId,value.nodeId,s.cycleId)
            .single { JSONObject(it.sourceReceipt).getString("callId")==s.callId }.also {
                check(it.kind==kind && JSONObject(it.sourceReceipt).getString("checkpointPayloadSha256")==s.checkpointPayloadSha256)
            }
    }
    override fun recordInvocationResult(bot:CrewManager.Bot,token:CancellationToken,source:CrewInvocationWitness,result:CoreToolResult) {
        source.requireInvocationCurrent();check(result.previewId==null && result.persistenceEvidence==null && result.effectReceipt==null &&
            result.administrativeReceipt==null && result.jobSubmissionReceipt==null) { "Unrepresented native result metadata" }
        val exact=WorkspaceTreeEvidenceStore.canonical(JSONObject().put("success",result.success).put("content",result.content)
            .put("completeContentRequired",result.completeContentRequired).put("finishRun",result.finishRun))
        WorkspaceRegistry(app).use { db -> val value=current(db,bot,token);val evidence=WorkspaceTreeEvidenceStore(WorkspaceTreeStore(db))
            val call=source.source().callId
            val receipt=evidence.invocationReceipts(value.rootId,value.nodeId,value.cycleId).single { JSONObject(it.sourceReceipt).getString("callId")==call }
            manager.withCurrentTreeCycle(bot,token,value.cycleId,Callable {
                evidence.recordInvocationResult(value.rootId,value.nodeId,value.cycleId,call,receipt.sourceKey,exact)
            })
        }
        synchronized(questionSources) { if(questionSources[bot]===source)questionSources.remove(bot) }
    }
    override fun delegateChild(bot:CrewManager.Bot,token:CancellationToken,source:CrewInvocationWitness,args:Map<String,Any?>,
        toolCeiling:List<String>,skillCeiling:List<String>):CoreToolResult {
        source.requireArguments(args);source.requireInvocationCurrent();check(WorkspaceTreePolicy.admissionEnabled()) { "Tree admissions disabled" }
        check(bot.role.missionAccess!=CrewMissionAccess.READ_ONLY)
        val mode=when(args["mode"]?:"work") { "work"->TreeMode.WORK;"consult"->TreeMode.CONSULT;"notify"->TreeMode.NOTIFY;else->error("Unknown child mode") }
        val reply=when(args["reply"]?:if(mode==TreeMode.NOTIFY) "none" else "result") { "none"->TreeReply.NONE;"result"->TreeReply.AWAIT;else->error("Unknown reply mode") }
        val role=(args["role"] as? String)?.takeIf { it.isNotBlank() } ?: bot.role.id
        val profile=CrewProfileRepository(app).definition(role).also { check(it.enabled) }.profile
        val selectedTools=(args["tools"] as? List<*>)?.map { it as String } ?: profile.capabilities.filter { it in toolCeiling }
        val selectedSkills=(args["skills"] as? List<*>)?.map { it as String } ?: profile.skillIds
        require(toolCeiling.containsAll(selectedTools) && bot.role.tools.containsAll(selectedTools) && profile.capabilities.containsAll(selectedTools))
        require(skillCeiling.containsAll(selectedSkills) && selectedSkills.containsAll(profile.skillIds)) { "Selected profile skills exceed current ceiling" }
        val objective=args["objective"] as String
        val reservation=WorkspaceRegistry(app).use { db ->
            val tree=WorkspaceTreeStore(db);val parent=current(db,bot,token);val receipt=invocation(db,parent,source,"ADMISSION")
            val assignment=requireNotNull(db.getAssignment(workspace,bot.assignmentId()))
            manager.withCurrentTreeCycle(bot,token,parent.cycleId,Callable {
                val child=tree.admitChild(TreeNodeFence.of(parent),TreeChildRequest(workspace,"tree-call:"+receipt.sourceKey,
                    assignment.requestId,assignment.originalUserRequest,role,objective,selectedTools,args["name"] as? String,mode,reply,receipt.sourceReceipt))
                check(child.state==TreeNodeState.QUEUED) { "Child execution already admitted; never replay STARTED" }
                tree.reserveFirstExecution(TreeNodeFence.of(child),CrewProcessIdentity.ID)
            })
        }
        val result=WorkspaceCoordinator(app,{manager},{}).startReservedTreeAssignment(reservation,token,Runnable { requireCurrentDispatch(bot,token) })
        return CoreToolResult.complete("native-tree-child:"+result.getString("tree_node_id")+"\n"+result.toString())
    }
    private fun ticket(wait:TreeWait,draft:String)=CrewTreeSupport.WaitTicket(wait.rootId,wait.parentNodeId,wait.waitId,wait.parentCycleId,
        wait.revision,CrewManager.WaitMode.valueOf(wait.join.name),wait.implicit,draft)
    override fun armWait(bot:CrewManager.Bot,token:CancellationToken,source:CrewInvocationWitness,children:List<String>,mode:CrewManager.WaitMode):CrewTreeSupport.WaitTicket {
        source.requireInvocationCurrent()
        return WorkspaceRegistry(app).use { db -> val value=current(db,bot,token);val receipt=invocation(db,value,source,"WAIT_ARM")
            manager.withCurrentTreeCycle(bot,token,value.cycleId,Callable {
                ticket(WorkspaceTreeWaitStore(WorkspaceTreeStore(db)).armWait(TreeNodeFence.of(value),children,TreeJoin.valueOf(mode.name),receipt.sourceReceipt),"")
            })
        }
    }
    private fun implicit(bot:CrewManager.Bot,token:CancellationToken,draft:String,source:CrewInvocationWitness?):CrewTreeSupport.WaitTicket? {
        val saved=requireNotNull(checkpoints.confirmDurable(session,bot.id,bot.scopeIdentity()))
        return WorkspaceRegistry(app).use { db -> val tree=WorkspaceTreeStore(db);val value=current(db,bot,token);val waits=WorkspaceTreeWaitStore(tree)
            val consumed=waits.list(value.rootId,value.nodeId).filter { it.state==TreeWaitState.ACKNOWLEDGED }
                .flatMap { it.selectedCauses.orEmpty() }.filter { it.kind=="TERMINAL" }
                .map { Triple(it.nodeId,it.cycleId,it.evidenceRef) }.toSet()
            // A consumed NEEDS_INPUT question is not task completion: its child still blocks implicit ALL.
            val children=tree.children(value.rootId,value.nodeId).filter { it.reply==TreeReply.AWAIT &&
                !(it.state==TreeNodeState.TERMINAL && Triple(it.nodeId,it.cycleId,it.terminalRef) in consumed) }.map { it.nodeId }
            if(children.isEmpty())return@use null
            val receipt=if(source!=null)invocation(db,value,source,"REPORT_DONE").sourceReceipt else
                WorkspaceTreeEvidenceStore.canonical(JSONObject().put("kind","IMPLICIT_FINISH").put("draft",draft)
                    .put("source",CrewCheckpointStore.continuationSourceCertificate(saved)))
            manager.withCurrentTreeCycle(bot,token,value.cycleId,Callable { ticket(waits.armWait(TreeNodeFence.of(value),children,TreeJoin.ALL,receipt,true),draft) })
        }
    }
    override fun armImplicitWait(bot:CrewManager.Bot,token:CancellationToken,draft:String)=implicit(bot,token,draft,null)
    override fun armReportDoneWait(bot:CrewManager.Bot,token:CancellationToken,source:CrewInvocationWitness,draft:String)=implicit(bot,token,draft,source)
    override fun confirmPark(bot:CrewManager.Bot,token:CancellationToken,ticket:CrewTreeSupport.WaitTicket,saved:CrewCheckpointStore.ConfirmedSnapshot) {
        check(saved.botId==bot.id && saved.conversationId==session && saved.metadataJson().getString("cycleId")==ticket.cycleId)
        requireCertifiedCheckpointIdentity(app,session,saved)
        CrewCheckpointCoordinator(app,session) { it.role }.requireNativeSafePoint(saved)
        WorkspaceRegistry(app).use { db -> val value=current(db,bot,token);check(value.nodeId==ticket.nodeId && value.rootId==ticket.rootId)
            val waits=WorkspaceTreeWaitStore(WorkspaceTreeStore(db));val wait=requireNotNull(waits.get(ticket.rootId,ticket.waitId))
            check(wait.revision==ticket.revision && wait.parentCycleId==ticket.cycleId && wait.state==TreeWaitState.ARMING)
            manager.withCurrentTreeCycle(bot,token,ticket.cycleId,Callable { waits.confirmWait(wait,CrewCheckpointStore.continuationSourceCertificate(saved).toString(),saved.payloadSha256) })
        }
    }
    override fun afterParkQuiescent(bot:CrewManager.Bot,token:CancellationToken,ticket:CrewTreeSupport.WaitTicket,saved:CrewCheckpointStore.ConfirmedSnapshot,lease:CrewManager.TreeParkLease) {
        lease.requireCurrent(bot,token,ticket,saved);wake.onPark(bot,token,ticket,saved,lease)
    }
    fun claimOwnerToken(bot:CrewManager.Bot,token:CancellationToken):String {
        check(manager.bot(bot.id)===bot && bot.token===token);token.throwIfCancelled()
        return synchronized(ownerKeys) { ownerKeys.getOrPut(token) { UUID.randomUUID().toString() } }
    }
    private fun requireClaim(db:WorkspaceRegistry,bot:CrewManager.Bot,token:CancellationToken,claim:TreeWaitClaim) {
        val value=current(db,bot,token);val wait=requireNotNull(WorkspaceTreeWaitStore(WorkspaceTreeStore(db)).get(value.rootId,claim.wait.waitId))
        check(wait==claim.wait && wait.state==TreeWaitState.STARTED && wait.parentNodeId==value.nodeId &&
            wait.parentCycleId==bot.cycleId() && wait.ownerProcess==CrewProcessIdentity.ID && wait.ownerGeneration==generation() &&
            wait.ownerToken==ownerKeys[token] && wait.selectedCauses==claim.causes && value.assignmentRevision==claim.admittedAssignmentRevision)
        check(value.controlIntent==wait.successorIntent && value.revision==wait.parentRevision)
    }
    override fun requireLinkedResume(bot:CrewManager.Bot,token:CancellationToken,commit:CrewManager.ResumeCheckpointCommit?) {
        val user=userCommits[commit]
        if(user!=null) { check(user.bot===bot && user.token===token);requireUserClaim(user);return }
        val owner=commits[commit] ?: error("Tree successor requires its registered exact native claim/control")
        check(owner.bot===bot && owner.token===token)
        WorkspaceRegistry(app).use { if(owner.user==null)requireClaim(it,bot,token,owner.claim) else requireUserWaitClaim(it,bot,token,owner.user) }
    }
    override fun requireCompletedUserResume(bot:CrewManager.Bot,token:CancellationToken,commit:CrewManager.ResumeCheckpointCommit?) {
        val owner=userCommits[commit] ?: error("DONE requires fresh registered USER control, not child-wait authority")
        check(owner.bot===bot && owner.token===token && owner.claim.executionEventId!=null)
        requireUserClaim(owner)
    }
    /** Old cancelled token is identity only. Fresh canonical USER claim is the execution authority. */
    private fun requireUserClaim(owner:UserResumeOwner) {
        val bot=owner.bot;val claim=owner.claim;val control=claim.control;val source=owner.source
        check(manager.bot(bot.id)===bot && bot.token===owner.token && bot.cycleId()==claim.node.cycleId)
        check(source.conversationId==session && source.botId==bot.id && source.scopeIdentity==bot.scopeIdentity() &&
            source.metadataJson().getString("cycleId")==claim.node.cycleId && source.metadataJson().getString("assignmentId")==control.assignmentId)
        check(control.type!=WorkspaceControlType.PAUSE && control.origin==WorkspaceRegistry.NATIVE_UI_ORIGIN &&
            control.state==WorkspaceControlState.STARTED && control.processId==CrewProcessIdentity.ID)
        WorkspaceRegistry(app).use { db ->
            val tree=WorkspaceTreeStore(db);check(tree.getNode(claim.node.rootId,claim.node.nodeId)==claim.node && db.getControl(control.requestId)==control)
            val root=requireNotNull(tree.getRoot(claim.node.rootId));check(!root.admissionsClosed && !root.wakesClosed)
            val cert=CrewCheckpointStore.continuationSourceCertificate(source)
            WorkspaceTreeEvidenceStore(tree).requireCertifiedPredecessor(claim.node.rootId,claim.node.nodeId,claim.node.cycleId,
                control.assignmentId,bot.id,source.scopeIdentity,cert.getString("profileSha256"),generation(),cert.toString(),source.payloadSha256,null,control.requestId)
        }
    }
    /** Caller releases this identity in finally even when native admission fails before commit. */
    fun authorizeUserControlResume(bot:CrewManager.Bot,token:CancellationToken,claim:TreeControlClaim,
        source:CrewCheckpointStore.ConfirmedSnapshot,baseP1Commit:CrewManager.ResumeCheckpointCommit?,
        stageAnswer:Consumer<CrewManager.Bot>?):CrewManager.ResumeCheckpointCommit {
        val owner=UserResumeOwner(bot,token,claim,source);requireUserClaim(owner)
        val hasP1=source.metadataJson().has(CentralContinuationRecord.FIELD)
        if(hasP1)CentralContinuationRecord.fromJson(source.metadataJson().getJSONObject(CentralContinuationRecord.FIELD))
        check(baseP1Commit==null || hasP1) { "P1 callback cannot substitute for tree-only source" }
        check(!hasP1 || claim.executionEventId==null || baseP1Commit!=null) { "P1 successor requires its existing native control lineage" }
        val certificate=TreeUserControlCertificate(source,claim.node.rootId,claim.node.nodeId,claim.control.requestId,claim.control.type.name,
            claim.control.assignmentRevision,requireNotNull(claim.control.admittedAssignmentRevision),WorkspaceTreeControlStore.claimDigest(claim))
        lateinit var commit:CrewManager.ResumeCheckpointCommit
        commit=CrewManager.ResumeCheckpointCommit { successor,support,_,_ ->
            check(userCommits[commit]===owner && successor===bot && manager.bot(bot.id)===bot)
            val starts=claim.executionEventId!=null
            check(if(starts) successor.token!==token && successor.cycleId()!=claim.node.cycleId
                else successor.token===token && successor.cycleId()==claim.node.cycleId)
            successor.token.throwIfCancelled()
            val native=support as? CrewCheckpointCoordinator ?: error("Actual native checkpoint writer required")
            // Before any mailbox staging, recheck exact pending row and source certificate; no DTO fallback.
            WorkspaceRegistry(app).use { db ->
                val tree=WorkspaceTreeStore(db);check(tree.getNode(claim.node.rootId,claim.node.nodeId)==claim.node && db.getControl(claim.control.requestId)==claim.control)
                val root=requireNotNull(tree.getRoot(claim.node.rootId));check(!root.admissionsClosed && !root.wakesClosed && root.origin.storageGeneration==generation())
            }
            if(stageAnswer!=null) { check(starts) { "Retained answer cannot steer an uncounted same-cycle correction" };stageAnswer.accept(successor) }
            val saved=if(baseP1Commit!=null) {
                baseP1Commit.commit(successor,support,manager.messageBus().snapshot(),manager.messageBus().pending(successor.id))
                requireNotNull(checkpoints.confirmDurable(session,successor.id,successor.scopeIdentity()))
            } else if(starts) native.persistTreeUserSuccessorConfirmed(successor,manager.messageBus().snapshot(),manager.messageBus().pending(successor.id),certificate)
            else {
                check(checkpoints.confirmDurable(session,bot.id,bot.scopeIdentity())?.payloadSha256==source.payloadSha256) { "Same-cycle control source was replaced" }
                native.persistTreeUserSameCycleConfirmed(successor,manager.messageBus().snapshot(),manager.messageBus().pending(successor.id),certificate)
            }
            check(saved.metadataJson().getString("cycleId")==successor.cycleId())
            WorkspaceRegistry(app).use { db -> WorkspaceTreeControlStore(WorkspaceTreeStore(db)).bind(claim,successor.cycleId(),CrewCheckpointStore.continuationSourceCertificate(saved).toString()) }
            reconcileCheckpoint(app,saved)
        }
        check(userCommits.put(commit,owner)==null);return commit
    }
    fun releaseUserControl(commit:CrewManager.ResumeCheckpointCommit) { userCommits.remove(commit) }
    fun userWaitOwnerToken(bot:CrewManager.Bot,token:CancellationToken):String {
        check(manager.bot(bot.id)===bot && bot.token===token && bot.isQuiescent && bot.canResume())
        return ownerKeys.getOrPut(token) { UUID.randomUUID().toString() }
    }
    private fun requireUserWaitClaim(db:WorkspaceRegistry,bot:CrewManager.Bot,token:CancellationToken,user:TreeUserWaitClaim) {
        val claim=user.waitClaim;val control=user.control
        check(manager.bot(bot.id)===bot && bot.token===token && bot.cycleId()==claim.wait.parentCycleId)
        check(control==db.getControl(control.requestId) && control.state==WorkspaceControlState.STARTED &&
            control.type==WorkspaceControlType.RESUME && control.origin==WorkspaceRegistry.NATIVE_UI_ORIGIN &&
            control.processId==CrewProcessIdentity.ID && control.botId==bot.id && control.assignmentId==bot.assignmentId() &&
            control.assignmentRevision==claim.sourceAssignmentRevision && control.admittedAssignmentRevision==claim.admittedAssignmentRevision)
        val tree=WorkspaceTreeStore(db);val value=node(db,bot);val wait=WorkspaceTreeWaitStore(tree).get(value.rootId,claim.wait.waitId)
        check(wait==claim.wait && wait.state==TreeWaitState.STARTED && wait.parentNodeId==value.nodeId &&
            wait.ownerProcess==CrewProcessIdentity.ID && wait.ownerGeneration==generation() && wait.ownerToken==ownerKeys[token] &&
            wait.selectedCauses==claim.causes && value.assignmentRevision==claim.admittedAssignmentRevision &&
            value.controlIntent==wait.successorIntent && value.revision==wait.parentRevision)
        val raw=db.treeRead { sql -> WorkspaceTreeStore.one(sql,"bot_tree_event","root_id=? AND event_key=? AND kind='USER_WAIT_AUTHORIZATION'",
            arrayOf(value.rootId,WorkspaceTreeStore.identity("user-wait",control.requestId))) { c -> c.getString(c.getColumnIndexOrThrow("receipt")) } }
        val receipt=JSONObject(requireNotNull(raw))
        check(receipt.getString("controlId")==control.requestId && receipt.getString("claimDigest")==WorkspaceTreeWaitStore.claimDigest(claim) &&
            receipt.getString("waitId")==wait.waitId && receipt.getLong("sourceRevision")==claim.sourceAssignmentRevision &&
            receipt.getLong("admittedRevision")==claim.admittedAssignmentRevision)
    }
    fun resumeUserWait(bot:CrewManager.Bot,token:CancellationToken,user:TreeUserWaitClaim,stageReports:Consumer<CrewManager.Bot>) {
        check(bot.isQuiescent && bot.canResume()) { "Exact retained native Resume unavailable" }
        resumeClaimInternal(bot,token,user.waitClaim,stageReports,user)
    }
    fun resumeClaim(bot:CrewManager.Bot,token:CancellationToken,claim:TreeWaitClaim,stageReports:Consumer<CrewManager.Bot>) {
        check(manager.isTreeParkQuiescent(bot,token)) { "Native parent has not quiesced" }
        resumeClaimInternal(bot,token,claim,stageReports,null)
    }
    private fun resumeClaimInternal(bot:CrewManager.Bot,token:CancellationToken,claim:TreeWaitClaim,stageReports:Consumer<CrewManager.Bot>,user:TreeUserWaitClaim?) {
        WorkspaceRegistry(app).use { if(user==null)requireClaim(it,bot,token,claim) else requireUserWaitClaim(it,bot,token,user) }
        val source=requireNotNull(checkpoints.confirmDurable(session,bot.id,bot.scopeIdentity()))
        check(source.payloadSha256==claim.wait.checkpointSha256 &&
            CentralContinuationRecord.equal(CrewCheckpointStore.continuationSourceCertificate(source),JSONObject(claim.wait.checkpointCertificate!!)))
        requireCertifiedCheckpointIdentity(app,session,source)
        CrewCheckpointCoordinator(app,session) { it.role }.requireNativeSafePoint(source)
        val certificate=TreeSuccessorCertificate(source,claim.wait.rootId,claim.wait.parentNodeId,claim.wait.waitId,claim.wait.revision,
            claim.sourceAssignmentRevision,claim.admittedAssignmentRevision,WorkspaceTreeWaitStore.claimDigest(claim))
        val writer=CrewCheckpointCoordinator(app,session) { it.role }
        val prepared=writer.prepareTreeSuccessorConfirmed(certificate)
        lateinit var commit:CrewManager.ResumeCheckpointCommit
        commit=CrewManager.ResumeCheckpointCommit { successor,support,_,_ ->
            check(commits[commit]?.claim==claim && successor===bot && successor.token!==token)
            val native=support as? CrewCheckpointCoordinator ?: error("Actual native checkpoint writer required")
            stageReports.accept(successor)
            val saved=native.persistTreeSuccessorConfirmed(successor,manager.messageBus().snapshot(),manager.messageBus().pending(successor.id),prepared,certificate)
            WorkspaceRegistry(app).use { db ->
                val tree=WorkspaceTreeStore(db);val waits=WorkspaceTreeWaitStore(tree)
                check(waits.get(claim.wait.rootId,claim.wait.waitId)==claim.wait)
                waits.bindSuccessor(claim.wait,successor.cycleId(),claim.admittedAssignmentRevision,
                    CrewCheckpointStore.continuationSourceCertificate(saved).toString(),CrewProcessIdentity.ID,generation(),claimOwnerToken(successor,successor.token))
                if(user!=null)db.completeControl(user.control.requestId,user.control.revision)
            }
            reconcileCheckpoint(app,saved)
        }
        check(commits.put(commit,ResumeOwner(bot,token,claim,user))==null)
        try { manager.resumeExact(bot.id,token,null,null,commit) } finally { commits.remove(commit);ownerKeys.remove(token) }
    }
    companion object {
        @JvmStatic fun requireExactWaitSource(context:Context,session:String,saved:CrewCheckpointStore.ConfirmedSnapshot,sha:String,certificate:String) {
            check(saved.payloadSha256==sha && CentralContinuationRecord.equal(CrewCheckpointStore.continuationSourceCertificate(saved),JSONObject(certificate)))
            CrewCheckpointCoordinator(context,session) { it.role }.requireNativeSafePoint(saved)
        }

        @JvmStatic fun requireCertifiedCheckpointIdentity(context:Context,session:String,saved:CrewCheckpointStore.ConfirmedSnapshot) {
            check(saved.conversationId==session && WorkspaceIdentity.isExecutionSession(session))
            val data=saved.metadataJson();val workspace=WorkspaceIdentity.workspaceId(session)
            WorkspaceRegistry(context).use { db ->
                val tree=WorkspaceTreeStore(db);val node=requireNotNull(tree.findAssignment(workspace,data.getString("assignmentId"))) { "No certified tree lineage" }
                check(node.botId==saved.botId && node.workspaceId==workspace && data.getString("botId")==saved.botId &&
                    data.getString("conversationId")==session)
                val cert=CrewCheckpointStore.continuationSourceCertificate(saved)
                val evidence=WorkspaceTreeEvidenceStore(tree)
                val generation=ChatDatabaseBridge.getInstance(context).connectionProjectionEvidenceSync().generation()
                val pending=WorkspaceTreeWaitStore(tree).list(node.rootId,node.nodeId).filter { it.state==TreeWaitState.STARTED &&
                    it.parentCycleId==data.getString("cycleId") && it.successorIntent==node.controlIntent }
                check(pending.size<=1) { "Ambiguous pending source claim" }
                if(pending.isNotEmpty()) evidence.requireCertifiedPredecessor(node.rootId,node.nodeId,data.getString("cycleId"),
                    data.getString("assignmentId"),saved.botId,saved.scopeIdentity,cert.getString("profileSha256"),generation,
                    cert.toString(),saved.payloadSha256,pending.single().waitId,null)
                else if(node.controlIntent!=null) {
                    val control=requireNotNull(db.getControl(node.controlIntent))
                    check(control.state==WorkspaceControlState.STARTED && control.assignmentId==node.assignmentId && control.botId==saved.botId)
                    evidence.requireCertifiedPredecessor(node.rootId,node.nodeId,data.getString("cycleId"),data.getString("assignmentId"),
                        saved.botId,saved.scopeIdentity,cert.getString("profileSha256"),generation,cert.toString(),saved.payloadSha256,null,control.requestId)
                } else evidence.requireCertifiedProducer(node.rootId,node.nodeId,data.getString("cycleId"),
                    data.getString("assignmentId"),saved.botId,saved.scopeIdentity,cert.getString("profileSha256"),generation)
            }
        }
        @JvmStatic fun verifiedTreeCalls(context:Context,saved:CrewCheckpointStore.ConfirmedSnapshot):Set<String> {
            if(!WorkspaceIdentity.isExecutionSession(saved.conversationId))return emptySet()
            return WorkspaceRegistry(context).use { db ->
                val tree=WorkspaceTreeStore(db)
                val node=tree.findAssignment(WorkspaceIdentity.workspaceId(saved.conversationId),saved.metadataJson().optString("assignmentId","")) ?: return@use emptySet()
                check(node.botId==saved.botId)
                val raw=saved.loopForEvidence();val verified=linkedSetOf<String>()
                WorkspaceTreeEvidenceStore(tree).invocationReceiptsForNode(node.rootId,node.nodeId).forEach { receipt ->
                    val source=JSONObject(receipt.sourceReceipt);val callId=source.getString("callId")
                    val calls=raw.transcript.flatMap { it.toolCalls }.filter { it.id==callId }
                    if(calls.isEmpty())return@forEach // Unretained call is not granted; native safepoint rejects lifecycle loss separately.
                    check(calls.size==1 && calls.single().name==source.getString("toolName")) { "Retained invocation identity changed" }
                    check(source.getString("executionSessionId")==saved.conversationId && source.getString("botId")==saved.botId &&
                        source.getString("scopeIdentity")==saved.scopeIdentity && source.getString("missionId")==saved.metadataJson().getString("missionId"))
                    check(WorkspaceTreeEvidenceStore.canonical(JSONObject(calls.single().arguments))==receipt.argsJson) { "Retained invocation arguments changed" }
                    if(raw.toolLifecycle[callId]!="RESULT")return@forEach
                    val exact=JSONObject(requireNotNull(receipt.resultJson) { "No committed full native result" })
                    check(receipt.resultHash==WorkspaceTreeStore.identity(receipt.resultJson))
                    val results=raw.transcript.filter { it.kind==ConversationTurn.Kind.TOOL_RESULT && it.toolCallId==callId }
                    check(results.size==1 && results.single().toolName==calls.single().name && results.single().content==exact.getString("content")) {
                        "Retained full native result changed"
                    }
                    // A wait marker alone is never proof: exact typed backlink and Registry arm must agree.
                    if(receipt.kind=="WAIT_ARM" || (receipt.kind=="REPORT_DONE" && exact.getString("content").startsWith("native-tree-wait:"))) {
                        val field=saved.metadataJson().getJSONObject(CrewTreeSupport.WaitTicket.FIELD)
                        val tickets=mutableListOf(field.getJSONObject("current"));val history=field.getJSONArray("history")
                        repeat(history.length()) { tickets.add(history.getJSONObject(it)) }
                        val wait=WorkspaceTreeWaitStore(tree).list(node.rootId,node.nodeId).single { it.sourceReceipt==receipt.sourceReceipt }
                        check(tickets.any { it.getString("waitId")==wait.waitId && it.getString("cycleId")==wait.parentCycleId &&
                            it.getString("rootId")==node.rootId && it.getString("nodeId")==node.nodeId })
                    }
                    verified.add(callId)
                }
                verified
            }
        }
        @JvmStatic fun reconcileCheckpoint(context:Context,saved:CrewCheckpointStore.ConfirmedSnapshot) {
            if(!WorkspaceIdentity.isExecutionSession(saved.conversationId))return
            val enrolled=WorkspaceRegistry(context).use { db -> WorkspaceTreeStore(db).findAssignment(WorkspaceIdentity.workspaceId(saved.conversationId),
                saved.metadataJson().optString("assignmentId",""))!=null }
            if(enrolled)WorkspaceTreeDelivery.reconcileCheckpoint(context,saved)
        }
    }
}
