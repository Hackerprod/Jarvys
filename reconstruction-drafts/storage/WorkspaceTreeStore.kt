package com.jarvys.agent.workspaces

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.jarvys.agent.CentralInvocationSource
import com.jarvys.agent.crew.CrewMissionAccess
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Fresh reconstruction. DTOs are fences, not native invocation authority. No IO/effects inside SQL. */
internal class WorkspaceTreeStore(internal val registry:WorkspaceRegistry) {
    fun beginRoot(origin:TreeOrigin,sourceReceipt:String,cycleId:String):TreeRoot = registry.treeTransaction { db ->
        integrity(origin.ownerCentralSessionId==WorkspaceIdentity.CENTRAL_SESSION,"Not Central owner")
        nonempty(origin.storageGeneration); nonempty(origin.originUserMessageId); hash(origin.originDigest)
        nonempty(sourceReceipt); nonempty(cycleId)
        val id=identity("root",origin.storageGeneration,origin.ownerCentralSessionId,origin.originUserMessageId)
        root(db,id)?.let { integrity(it.origin==origin,"Root origin changed"); return@treeTransaction it }
        val now=timestamp()
        db.insertOrThrow("bot_tree_root",null,values("root_id" to id,"storage_generation" to origin.storageGeneration,
            "owner_central_session_id" to origin.ownerCentralSessionId,"origin_user_message_id" to origin.originUserMessageId,
            "origin_digest" to origin.originDigest,"source_receipt" to sourceReceipt,"policy_version" to WorkspaceTreePolicy.VERSION,
            "revision" to 1L,"window_epoch" to 0L,"requests" to 0L,"executions" to 1L,"wakes" to 0L,
            "admissions_closed" to 0L,"wakes_closed" to 0L,"created_at" to now,"updated_at" to now))
        val execution=identity("initial-execution",id)
        db.insertOrThrow("bot_tree_node",null,values("root_id" to id,"node_id" to id,"depth" to 0,
            "mode" to "CENTRAL","reply" to "NONE","assignment_revision" to 0L,"cycle_id" to cycleId,
            "revision" to 1L,"state" to "RUNNING","source_receipt" to sourceReceipt,"execution_event_id" to execution,
            "admissions_closed" to 0L,"wakes_closed" to 0L,"stop_epoch" to 0L,"created_at" to now,"updated_at" to now))
        appendEvent(db,requireNode(db,id,id),execution,"EXECUTION",sourceReceipt,sourceReceipt,identity(sourceReceipt),cycleId)
        requireRoot(db,id)
    }
    fun getRoot(rootId:String):TreeRoot?=registry.treeRead { if(it.version==1) null else root(it,rootId) }
    fun getNode(rootId:String,nodeId:String):TreeNode?=registry.treeRead { if(it.version==1) null else node(it,rootId,nodeId) }
    fun findAssignment(workspaceId:String,assignmentId:String):TreeNode?=registry.treeRead { db ->
        if(db.version==1) null else one(db,"bot_tree_node","workspace_id=? AND assignment_id=?",arrayOf(workspaceId,assignmentId),::decodeNode)
    }
    fun children(rootId:String,parentNodeId:String):List<TreeNode> = registry.treeRead { db ->
        if(db.version==1) emptyList() else childrenDb(db,rootId,parentNodeId)
    }
    fun admitChild(parentFence:TreeNodeFence,request:TreeChildRequest):TreeNode=registry.treeTransaction { db ->
        val parent=active(db,parentFence)
        eligible(parent.state==TreeNodeState.RUNNING && parent.mode!=TreeMode.CONSULT,"Parent cannot delegate")
        eligible(parent.depth<WorkspaceTreePolicy.MAX_DEPTH,"Depth exhausted")
        require(request.mode!=TreeMode.CENTRAL); nonempty(request.sourceReceipt)
        val workspace=requireNotNull(registry.getWorkspace(request.workspaceId))
        integrity(workspace.ownerCentralSessionId==WorkspaceIdentity.CENTRAL_SESSION,"Workspace owner changed")
        eligible(parent.workspaceId==null || parent.workspaceId==request.workspaceId,"Worker namespace changed")
        if(parent.assignmentId!=null) {
            val assignment=requireNativeAssignment(parent)
            eligible(assignment.missionAccess!=CrewMissionAccess.READ_ONLY,"Read-only worker cannot delegate")
            integrity(request.requestId==assignment.requestId && request.originalUserRequest==assignment.originalUserRequest,"Worker mission ownership changed")
            if(assignment.tools!=null) eligible(request.tools!=null && assignment.tools.containsAll(request.tools),"Child tools exceed retained assignment")
            integrity(request.centralSource==null,"Worker cannot supply Central source")
        } else request.centralSource?.let { source ->
            val origin=requireRoot(db,parent.rootId).origin
            integrity(source.ownerCentralSessionId==origin.ownerCentralSessionId && source.storageGeneration==origin.storageGeneration &&
                source.sourceMessageId==origin.originUserMessageId && parent.cycleId.startsWith(source.sourceProcessId+":") &&
                centralReceipt(source)==request.sourceReceipt,"Central admission provenance changed")
        }
        val key=identity("admit",parent.nodeId,request.assignmentKey); val payload=admissionDigest(request)
        event(db,parent.rootId,key)?.let {
            integrity(it==payload,"Admission payload changed")
            val id=one(db,"assignment","workspace_id=? AND assignment_key=?",arrayOf(request.workspaceId,request.assignmentKey)) { c -> c.s("assignment_id") }
                ?: throw TreeIntegrityException("Missing admitted assignment")
            val native=requireNotNull(registry.getAssignment(request.workspaceId,id))
            integrity(native.centralSource==request.centralSource,"Duplicate admission provenance changed")
            val old=requireNode(db,parent.rootId,id)
            integrity(old.parentNodeId==parent.nodeId && old.sourceReceipt==request.sourceReceipt,"Admission owner changed")
            return@treeTransaction old
        }
        val root=requireRoot(db,parent.rootId)
        eligible(root.requests<WorkspaceTreePolicy.MAX_REQUESTS,"Request progress window exhausted")
        integrity(one(db,"assignment","workspace_id=? AND assignment_key=?",arrayOf(request.workspaceId,request.assignmentKey)) { it.s("assignment_id") }==null,
            "Historical assignment cannot be enrolled")
        val assignment=registry.prepareAssignment(request.workspaceId,request.assignmentKey,request.requestId,request.originalUserRequest,
            request.role,request.mission,request.tools,request.name,
            if(request.mode==TreeMode.CONSULT) CrewMissionAccess.READ_ONLY else CrewMissionAccess.STANDARD,Runnable {},request.centralSource)
        integrity(assignment.state==WorkspaceAssignmentState.PREPARED && assignment.botId==null,"Native admission changed")
        val now=timestamp()
        db.insertOrThrow("bot_tree_node",null,values("root_id" to parent.rootId,"node_id" to assignment.assignmentId,
            "parent_node_id" to parent.nodeId,"depth" to parent.depth+1,"mode" to request.mode.name,"reply" to request.reply.name,
            "workspace_id" to request.workspaceId,"assignment_id" to assignment.assignmentId,"assignment_revision" to assignment.revision,
            "cycle_id" to identity("queued",assignment.assignmentId),"revision" to 1L,"state" to "QUEUED","source_receipt" to request.sourceReceipt,
            "admissions_closed" to 0L,"wakes_closed" to 0L,"stop_epoch" to 0L,"created_at" to now,"updated_at" to now))
        val child=requireNode(db,parent.rootId,assignment.assignmentId)
        appendEvent(db,child,key,"ADMISSION",payload,request.sourceReceipt,payload,child.nodeId)
        updateRoot(db,root,values("requests" to next(root.requests)))
        child
    }
    fun retryPreparedCentral(parentFence:TreeNodeFence,childFence:TreeNodeFence,request:TreeChildRequest):TreeNode=registry.treeTransaction { db ->
        val parent=active(db,parentFence); val child=current(db,childFence); val root=requireRoot(db,parent.rootId)
        eligible(parent.mode==TreeMode.CENTRAL && parent.nodeId==parent.rootId && parent.state==TreeNodeState.RUNNING,
            "Retry requires original live Central root")
        eligible(child.rootId==parent.rootId && child.parentNodeId==parent.nodeId && child.state==TreeNodeState.QUEUED &&
            child.executionEventId==null && child.botId==null && !child.admissionsClosed,"Retry is not pristine queued admission")
        val assignment=requireNativeAssignment(child,WorkspaceAssignmentState.PREPARED)
        val original=assignment.centralSource ?: throw TreeIntegrityException("Original Central provenance missing")
        val source=request.centralSource ?: throw TreeIntegrityException("Fresh Central provenance missing")
        integrity(source.ownerCentralSessionId==root.origin.ownerCentralSessionId && original.ownerCentralSessionId==source.ownerCentralSessionId &&
            source.storageGeneration==root.origin.storageGeneration && original.storageGeneration==source.storageGeneration &&
            source.sourceRunId==original.sourceRunId && source.sourceProcessId==original.sourceProcessId &&
            source.sourceMessageId==root.origin.originUserMessageId && original.sourceMessageId==root.origin.originUserMessageId &&
            parent.cycleId.startsWith(source.sourceProcessId+":"),
            "Retry source owner/run changed")
        integrity(centralReceipt(original)==child.sourceReceipt && centralReceipt(source)==request.sourceReceipt &&
            request.sourceReceipt!=child.sourceReceipt,"Retry requires distinct canonical trigger")
        integrity(assignment.workspaceId==request.workspaceId && assignment.assignmentKey==request.assignmentKey &&
            event(db,parent.rootId,identity("admit",parent.nodeId,request.assignmentKey))==admissionDigest(request.copy(sourceReceipt=child.sourceReceipt)),
            "Retry semantic payload changed")
        val key=identity("central-prepared-retry",child.nodeId,request.sourceReceipt)
        val receipt=identity(request.sourceReceipt,source.sourceMessageId,source.sourceBatchId,source.sourceCallId,source.sourceRunId,source.sourceProcessId)
        appendEvent(db,child,key,"CENTRAL_PREPARED_RETRY",receipt,request.sourceReceipt,identity(receipt),child.nodeId)
        child
    }
    fun reserveFirstExecution(fence:TreeNodeFence,processId:String):TreeExecutionReservation=registry.treeTransaction { db ->
        nonempty(processId); val child=active(db,fence)
        eligible(child.state==TreeNodeState.QUEUED && child.executionEventId==null,"First execution already reserved")
        requireNativeAssignment(child,WorkspaceAssignmentState.PREPARED)
        val root=requireRoot(db,child.rootId); eligible(root.executions<WorkspaceTreePolicy.MAX_EXECUTIONS,"Execution window exhausted")
        val key=identity("first-execution",child.nodeId)
        integrity(event(db,child.rootId,key)==null,"Execution event already exists")
        val started=updateNode(db,child,values("state" to "STARTED","execution_event_id" to key))
        val assignment=registry.markStarted(requireNotNull(child.workspaceId),requireNotNull(child.assignmentId),child.assignmentRevision,processId)
        appendEvent(db,child,key,"EXECUTION",processId,child.sourceReceipt,identity(processId),child.nodeId)
        updateRoot(db,root,values("executions" to next(root.executions)))
        val reserved=updateNode(db,started,values("assignment_revision" to assignment.revision))
        TreeExecutionReservation(reserved.rootId,reserved.nodeId,requireNotNull(reserved.assignmentId),processId,key,reserved.revision,reserved.assignmentRevision)
    }
    fun bindExecution(fence:TreeNodeFence,botId:String,cycleId:String,sourceCertificate:String):TreeNode=registry.treeTransaction { db ->
        val child=active(db,fence); eligible(child.state==TreeNodeState.STARTED && child.executionEventId!=null,"Not reserved")
        nonempty(botId); nonempty(cycleId)
        val assignment=requireNotNull(registry.getAssignment(requireNotNull(child.workspaceId),requireNotNull(child.assignmentId)))
        integrity(assignment.state==WorkspaceAssignmentState.BOUND && assignment.botId==botId && assignment.revision==next(child.assignmentRevision),
            "Native binding changed")
        val execution=one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(child.rootId,requireNotNull(child.executionEventId))) { c ->
            integrity(c.s("kind")=="EXECUTION" && c.s("node_id")==child.nodeId && c.s("cycle_id")==child.cycleId &&
                c.s("source_receipt")==child.sourceReceipt,"Execution source changed")
            val process=c.s("receipt");integrity(c.s("payload_digest")==identity(process),"Execution digest changed");process
        } ?: throw TreeIntegrityException("Missing execution event")
        integrity(assignment.processId==execution,"Native execution process changed")
        requireSourceCertificate(sourceCertificate,"native-workspace:${child.workspaceId}")
        appendEvent(db,child,identity("bind",child.nodeId,cycleId),"BIND",sourceCertificate,child.sourceReceipt,identity(sourceCertificate),cycleId)
        updateNode(db,child,values("bot_id" to botId,"cycle_id" to cycleId,"assignment_revision" to assignment.revision,"state" to "RUNNING"))
    }
    fun markUncertain(fence:TreeNodeFence):TreeNode=registry.treeTransaction { db ->
        val child=current(db,fence); eligible(child.state==TreeNodeState.STARTED,"Not reserved")
        val native=requireNativeAssignment(child,WorkspaceAssignmentState.STARTED)
        val uncertain=registry.markUncertain(native.workspaceId,native.assignmentId,native.revision)
        updateNode(db,child,values("state" to "UNCERTAIN","assignment_revision" to uncertain.revision,"admissions_closed" to 1L,"wakes_closed" to 1L))
    }
    /** Failure after native BOUND but before scheduling conserves the reservation; never mutates/replays the native bot. */
    fun markDispatchUncertain(reservation:TreeExecutionReservation,fence:TreeNodeFence,botId:String,cycleId:String):TreeNode = registry.treeTransaction { db ->
        val node=requireNode(db,fence.rootId,fence.nodeId)
        eligible(TreeNodeFence.of(node)==fence && node.rootId==reservation.rootId && node.nodeId==reservation.nodeId &&
            node.assignmentId==reservation.assignmentId && node.executionEventId==reservation.eventId &&
            node.state in setOf(TreeNodeState.STARTED,TreeNodeState.RUNNING),"Dispatch reservation changed")
        val native=requireNotNull(registry.getAssignment(requireNotNull(node.workspaceId),reservation.assignmentId))
        integrity(native.state==WorkspaceAssignmentState.BOUND && native.botId==botId && native.processId==reservation.processId &&
            native.revision==next(reservation.assignmentRevision),"Uncertain native binding changed")
        val eventProcess=one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(node.rootId,reservation.eventId)) { c ->
            integrity(c.s("kind")=="EXECUTION" && c.s("node_id")==node.nodeId && c.s("payload_digest")==identity(c.s("receipt")),"Execution evidence changed")
            c.s("receipt") } ?: throw TreeIntegrityException("Missing execution reservation")
        integrity(eventProcess==reservation.processId,"Execution process changed")
        if(node.state==TreeNodeState.RUNNING) integrity(node.botId==botId && node.cycleId==cycleId && node.assignmentRevision==native.revision,"Bound cycle changed")
        else integrity(node.botId==null && node.revision==reservation.revision && node.assignmentRevision==reservation.assignmentRevision,"Prebind reservation changed")
        nonempty(cycleId)
        updateNode(db,node,values("state" to "UNCERTAIN","admissions_closed" to 1L,"wakes_closed" to 1L))
    }
    fun requireDispatch(fence:TreeNodeFence):TreeNode=registry.treeRead { db ->
        val child=current(db,fence); val root=requireRoot(db,child.rootId)
        WorkspaceTreeStops.requireAllowed(registry.treeStorageIdentity,child.rootId,child.nodeId,child.cycleId)
        eligible(child.state==TreeNodeState.RUNNING && !child.admissionsClosed && root.closeReason !in setOf("CANCELLED","BUDGET"),"Dispatch closed")
        if(child.assignmentId!=null) requireNativeAssignment(child)
        child
    }
    fun settle(fence:TreeNodeFence,outcome:String,ref:String,digest:String,sourceReceipt:String):TreeNode=registry.treeTransaction { db ->
        val child=requireNode(db,fence.rootId,fence.nodeId); val key=identity("terminal",child.nodeId,fence.cycleId)
        eligible(child.parentNodeId!=null,"Central completion requires dedicated confirmed dependency admission")
        nonempty(ref);hash(digest);nonempty(sourceReceipt)
        require(outcome in setOf("SUCCESS","FAILED","CANCELLED","STOPPED","EXPIRED","DENIED","BUDGET"))
        val receipt=identity(outcome,ref,digest,sourceReceipt)
        event(db,child.rootId,key)?.let { integrity(it==receipt && child.cycleId==fence.cycleId,"Conflicting terminal event");return@treeTransaction child }
        eligible(TreeNodeFence.of(child)==fence && child.state !in setOf(TreeNodeState.QUEUED,TreeNodeState.TERMINAL),"Terminal producer changed")
        appendEvent(db,child,key,"TERMINAL",receipt,sourceReceipt,digest,ref)
        val terminal=updateNode(db,child,values("state" to "TERMINAL","terminal_ref" to ref,"terminal_hash" to digest,
            "terminal_outcome" to outcome,"report_receipt" to null,"admissions_closed" to 1L,"wakes_closed" to 1L))
        resetProgress(db,child.rootId)
        terminal
    }
    fun stopRequester(fence:TreeNodeFence,beforeDenial:((List<TreeNode>)->Unit)?=null):List<TreeNode> = registry.treeTransaction { db ->
        val parent=requireNode(db,fence.rootId,fence.nodeId); eligible(TreeNodeFence.of(parent)==fence,"STOP fence changed")
        val queued=childrenDb(db,parent.rootId,parent.nodeId).filter { it.state==TreeNodeState.QUEUED }
        queued.forEach { requireNativeAssignment(it,WorkspaceAssignmentState.PREPARED);integrity(it.executionEventId==null && it.botId==null,"Queued owner changed") }
        beforeDenial?.invoke(listOf(parent)+queued) // Pure in-process denial only; no effects, IO or owner lookup.
        val stopped=updateNode(db,parent,values("admissions_closed" to 1L,"wakes_closed" to 1L,"stop_epoch" to next(parent.stopEpoch)))
        if(parent.parentNodeId==null) closeRoot(db,parent.rootId,"STOPPED")
        cancelWaits(db,parent)
        val result=queued.map { cancelQueued(db,it,stopped,"STOPPED") }
        if(result.isNotEmpty()) resetProgress(db,parent.rootId)
        result
    }
    fun cancelSubtree(fence:TreeNodeFence,reason:String,requestKey:String?=null,
                      beforeDenial:((List<TreeNode>)->Unit)?=null):List<TreeNode> = registry.treeTransaction { db ->
        require(reason in setOf("CANCELLED","BUDGET"))
        val key=requestKey?.let { nonempty(it);identity("cancel-request",it) }
        val payload=identity(fence.nodeId,fence.cycleId,fence.assignmentRevision.toString(),reason)
        if(key!=null) event(db,fence.rootId,key)?.let { integrity(it==payload,"Cancellation cannot retarget");return@treeTransaction emptyList() }
        val parent=requireNode(db,fence.rootId,fence.nodeId);eligible(TreeNodeFence.of(parent)==fence,"Cancellation fence changed")
        val todo=java.util.ArrayDeque<TreeNode>();todo.add(parent);val targets=ArrayList<TreeNode>()
        val visited=HashSet<String>()
        while(todo.isNotEmpty()) {
            val item=todo.removeFirst();integrity(visited.add(item.nodeId),"Cyclic tree")
            todo.addAll(childrenDb(db,item.rootId,item.nodeId))
            if(item.state!=TreeNodeState.TERMINAL) targets.add(item)
        }
        targets.filter { it.state==TreeNodeState.QUEUED }.forEach {
            requireNativeAssignment(it,WorkspaceAssignmentState.PREPARED);integrity(it.executionEventId==null && it.botId==null,"Queued ownership changed") }
        beforeDenial?.invoke(targets.toList()) // Durable target capture precedes pure denial, commit precedes physical STOP.
        if(key!=null) appendEvent(db,parent,key,"CANCEL_REQUEST",payload,requireNotNull(requestKey),payload,parent.nodeId)
        if(parent.parentNodeId==null) closeRoot(db,parent.rootId,reason)
        var settled=false
        val result=targets.map { child ->
            appendEvent(db,child,identity("cancel-target",child.nodeId,child.cycleId),"CANCEL_TARGET",reason,
                requestKey ?: payload,identity(reason),child.nodeId)
            cancelWaits(db,child)
            if(child.state==TreeNodeState.QUEUED) { settled=true;cancelQueued(db,child,parent,reason) }
            else updateNode(db,child,values("admissions_closed" to 1L,"wakes_closed" to 1L,"stop_epoch" to next(child.stopEpoch)))
        }
        if(settled) resetProgress(db,parent.rootId)
        result
    }
    internal fun cancelQueued(db:SQLiteDatabase,child:TreeNode,owner:TreeNode,reason:String):TreeNode {
        val ref=identity("cancelled-admission",child.nodeId,child.cycleId)
        val receipt=JSONObject().put("kind","CANCELLED_ADMISSION").put("rootId",child.rootId).put("nodeId",child.nodeId)
            .put("cycleId",child.cycleId).put("assignmentId",child.assignmentId).put("cancelOwner",owner.nodeId).put("reason",reason).toString()
        val digest=identity(receipt)
        appendEvent(db,child,identity("terminal",child.nodeId,child.cycleId),"TERMINAL",receipt,owner.sourceReceipt,digest,ref)
        updateNode(db,child,values("state" to "TERMINAL","terminal_outcome" to "CANCELLED","terminal_ref" to ref,
            "terminal_hash" to digest,"admissions_closed" to 1L,"wakes_closed" to 1L,"stop_epoch" to next(child.stopEpoch)))
        // The Registry itself is the canonical producer for never-executed cancellation.
        // Retain its report in the same transaction: no runtime, fabricated worker result or crash repair gap.
        return WorkspaceTreeEvidenceStore(this).markReported(child.rootId,child.nodeId,ref,digest,"cancelled-admission:"+digest)
    }
    internal fun cancelWaits(db:SQLiteDatabase,node:TreeNode) {
        db.execSQL("UPDATE bot_tree_wait SET state='CANCELLED',revision=revision+1,updated_at=? WHERE root_id=? AND parent_node_id=? AND state IN ('ARMING','WAITING','READY')",
            arrayOf(timestamp(),node.rootId,node.nodeId))
    }
    internal fun closeRoot(db:SQLiteDatabase,id:String,reason:String) {
        val root=requireRoot(db,id)
        if(root.admissionsClosed || root.wakesClosed) return // Never replace the first terminal closure.
        updateRoot(db,root,values("admissions_closed" to 1L,"wakes_closed" to 1L,"close_reason" to reason))
    }

    fun attachCentralExecution(fence:TreeNodeFence,cycle:String,source:String,claim:String,childDue:Boolean,childId:String)=
        WorkspaceTreeCentralStore(this).attach(fence,cycle,source,claim,childDue,childId)
    fun finishCentral(fence:TreeNodeFence,receipt:String,stopped:Boolean)=WorkspaceTreeCentralStore(this).finish(fence,receipt,stopped)
    fun recordCentralIncorporated(root:String,child:String,claim:String,receipt:String)=WorkspaceTreeCentralStore(this).incorporated(root,child,claim,receipt)


    internal fun requireNativeAssignment(node:TreeNode,state:WorkspaceAssignmentState=WorkspaceAssignmentState.BOUND):WorkspaceAssignment {
        val scope=requireNotNull(registry.getWorkspace(requireNotNull(node.workspaceId)))
        val assignment=requireNotNull(registry.getAssignment(node.workspaceId,requireNotNull(node.assignmentId)))
        integrity(scope.ownerCentralSessionId==WorkspaceIdentity.CENTRAL_SESSION && assignment.revision==node.assignmentRevision &&
            assignment.state==state && assignment.botId==node.botId,"Native assignment owner/revision changed")
        return assignment
    }
    internal fun root(db:SQLiteDatabase,id:String)=one(db,"bot_tree_root","root_id=?",arrayOf(id),::decodeRoot)
    internal fun requireRoot(db:SQLiteDatabase,id:String)=root(db,id) ?: throw TreeIntegrityException("Missing root")
    internal fun node(db:SQLiteDatabase,root:String,id:String)=one(db,"bot_tree_node","root_id=? AND node_id=?",arrayOf(root,id),::decodeNode)
    internal fun requireNode(db:SQLiteDatabase,root:String,id:String)=node(db,root,id) ?: throw TreeIntegrityException("Missing node")
    internal fun current(db:SQLiteDatabase,fence:TreeNodeFence):TreeNode=requireNode(db,fence.rootId,fence.nodeId).also {
        eligible(TreeNodeFence.of(it)==fence && it.controlIntent==null,"Node/cycle/revision changed or control pending") }
    internal fun active(db:SQLiteDatabase,fence:TreeNodeFence):TreeNode=current(db,fence).also {
        val root=requireRoot(db,it.rootId);val central=requireNode(db,it.rootId,it.rootId)
        WorkspaceTreeStops.requireAllowed(registry.treeStorageIdentity,it.rootId,it.rootId,central.cycleId)
        WorkspaceTreeStops.requireAllowed(registry.treeStorageIdentity,it.rootId,it.nodeId,it.cycleId)
        eligible(!root.admissionsClosed && !root.wakesClosed && !it.admissionsClosed,"Admission closed") }
    internal fun childrenDb(db:SQLiteDatabase,root:String,parent:String):List<TreeNode> =
        db.query("bot_tree_node",null,"root_id=? AND parent_node_id=?",arrayOf(root,parent),null,null,"node_id").use { c ->
            buildList { while(c.moveToNext()) add(decodeNode(c)) } }
    internal fun updateNode(db:SQLiteDatabase,old:TreeNode,changes:ContentValues):TreeNode {
        changes.put("revision",next(old.revision));changes.put("updated_at",timestamp())
        integrity(db.update("bot_tree_node",changes,"root_id=? AND node_id=? AND revision=?",arrayOf(old.rootId,old.nodeId,old.revision.toString()))==1,"Node CAS failed")
        return requireNode(db,old.rootId,old.nodeId)
    }
    internal fun updateRoot(db:SQLiteDatabase,old:TreeRoot,changes:ContentValues):TreeRoot {
        changes.put("revision",next(old.revision));changes.put("updated_at",timestamp())
        integrity(db.update("bot_tree_root",changes,"root_id=? AND revision=?",arrayOf(old.rootId,old.revision.toString()))==1,"Root CAS failed")
        return requireRoot(db,old.rootId)
    }
    internal fun event(db:SQLiteDatabase,root:String,key:String):String? = one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(root,key)) { it.s("receipt") }
    internal fun appendEvent(db:SQLiteDatabase,node:TreeNode,key:String,kind:String,receipt:String,source:String,digest:String,ref:String,waitId:String?=null) {
        nonempty(key);nonempty(kind);nonempty(receipt);nonempty(source);hash(digest);nonempty(ref)
        val old=one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(node.rootId,key)) { c ->
            listOf(c.s("node_id"),c.s("cycle_id"),c.s("kind"),c.s("receipt"),c.s("source_receipt"),c.s("payload_digest"),c.s("payload_ref"),c.optional("wait_id")) }
        if(old!=null) { integrity(old==listOf(node.nodeId,node.cycleId,kind,receipt,source,digest,ref,waitId),"Immutable event changed");return }
        db.insertOrThrow("bot_tree_event",null,values("root_id" to node.rootId,"event_key" to key,"node_id" to node.nodeId,"cycle_id" to node.cycleId,
            "node_revision" to node.revision,"kind" to kind,"receipt" to receipt,"source_receipt" to source,"payload_digest" to digest,
            "payload_ref" to ref,"wait_id" to waitId,"created_at" to timestamp()))
    }
    internal fun resetProgress(db:SQLiteDatabase,id:String) {
        val root=requireRoot(db,id);if(root.admissionsClosed || root.wakesClosed || root.closeReason!=null) return
        updateRoot(db,root,values("window_epoch" to next(root.windowEpoch),"requests" to 0L,"executions" to 0L,"wakes" to 0L))
    }
    companion object {
        internal fun identity(vararg pieces:String):String {
            val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use { out -> pieces.forEach { text ->
                out.writeInt(text.length);text.forEach { out.writeChar(it.code) } } }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        internal fun centralReceipt(s:CentralInvocationSource)="central-call:"+UUID.nameUUIDFromBytes(
            (s.storageGeneration+"\u0000"+s.sourceBatchId+"\u0000"+s.sourceCallId).toByteArray(Charsets.UTF_8)).toString()
        internal fun admissionDigest(r:TreeChildRequest)=identity("child",r.workspaceId,r.assignmentKey,r.requestId,r.originalUserRequest,
            r.role,r.mission,if(r.tools==null) "null" else org.json.JSONArray(r.tools.sorted()).toString(),
            if(r.name==null) "null" else JSONObject.quote(r.name),r.mode.name,r.reply.name,r.sourceReceipt)
        internal fun requireSourceCertificate(raw:String,scope:String):JSONObject {
            val certificate=try { JSONObject(raw) } catch(e:Exception) { throw TreeIntegrityException("Malformed source certificate") }
            val keys=setOf("originCheckpointSha256","originScopeIdentity","originSavedAtMs","profileSha256","transcriptSha256","toolLifecycleSha256","artifactOwnershipSha256")
            integrity(certificate.keys().asSequence().toSet()==keys,"Source certificate shape changed")
            keys.filter { it.endsWith("Sha256") }.forEach { integrity(certificate.get(it) is String,"Certificate hash type");hash(certificate.getString(it)) }
            integrity(certificate.get("originScopeIdentity") is String && certificate.getString("originScopeIdentity")==scope,"Certificate scope changed")
            val saved=certificate.get("originSavedAtMs")
            integrity((saved is Long || saved is Int) && (saved as Number).toLong()>0,"Certificate timestamp changed")
            return certificate
        }
        internal fun decodeRoot(c:Cursor):TreeRoot {
            val origin=TreeOrigin(c.s("storage_generation"),c.s("owner_central_session_id"),c.s("origin_user_message_id"),c.s("origin_digest"))
            val id=c.s("root_id");hash(origin.originDigest)
            integrity(origin.ownerCentralSessionId==WorkspaceIdentity.CENTRAL_SESSION && id==identity("root",origin.storageGeneration,origin.ownerCentralSessionId,origin.originUserMessageId),"Invalid root identity")
            integrity(c.l("policy_version")==WorkspaceTreePolicy.VERSION.toLong(),"Unsupported tree policy")
            return TreeRoot(id,origin,c.s("source_receipt"),WorkspaceTreePolicy.VERSION,c.positive("revision"),c.nonnegative("window_epoch"),
                c.nonnegative("requests"),c.nonnegative("executions"),c.nonnegative("wakes"),c.bool("admissions_closed"),c.bool("wakes_closed"),c.optional("close_reason"))
        }
        internal fun decodeNode(c:Cursor):TreeNode {
            val depth=c.nonnegative("depth");integrity(depth<=WorkspaceTreePolicy.MAX_DEPTH,"Invalid depth")
            val n=TreeNode(c.s("root_id"),c.s("node_id"),c.optional("parent_node_id"),depth.toInt(),enumValueOf(c.s("mode")),enumValueOf(c.s("reply")),
                c.optional("workspace_id"),c.optional("assignment_id"),c.nonnegative("assignment_revision"),c.optional("bot_id"),c.s("cycle_id"),c.positive("revision"),
                enumValueOf(c.s("state")),c.s("source_receipt"),c.optional("execution_event_id"),c.optional("terminal_ref"),c.optional("terminal_hash"),c.optional("terminal_outcome"),
                c.optional("report_receipt"),c.optional("control_intent"),c.bool("admissions_closed"),c.bool("wakes_closed"),c.nonnegative("stop_epoch"))
            integrity(if(n.mode==TreeMode.CENTRAL) n.parentNodeId==null && n.nodeId==n.rootId && n.depth==0 && n.workspaceId==null && n.assignmentId==null && n.assignmentRevision==0L
                else n.parentNodeId!=null && n.depth>0 && n.workspaceId!=null && n.assignmentId==n.nodeId && n.assignmentRevision>0,"Invalid node ownership")
            return n
        }
        internal fun Cursor.optional(name:String):String? { val i=getColumnIndexOrThrow(name);if(isNull(i)) return null
            integrity(getType(i)==Cursor.FIELD_TYPE_STRING,"Invalid text $name");return getString(i) }
        internal fun Cursor.s(name:String)=optional(name) ?: throw TreeIntegrityException("Null $name")
        internal fun Cursor.l(name:String):Long { val i=getColumnIndexOrThrow(name);integrity(getType(i)==Cursor.FIELD_TYPE_INTEGER,"Invalid integer $name");return getLong(i) }
        internal fun Cursor.positive(name:String)=l(name).also { integrity(it>0,"Invalid $name") }
        internal fun Cursor.nonnegative(name:String)=l(name).also { integrity(it>=0,"Invalid $name") }
        internal fun Cursor.bool(name:String)=l(name).also { integrity(it==0L || it==1L,"Invalid bool $name") }==1L
        internal fun <T> one(db:SQLiteDatabase,table:String,where:String,args:Array<String>,decode:(Cursor)->T):T? =
            db.query(table,null,where,args,null,null,null,"2").use { c -> integrity(c.count<=1,"Ambiguous $table identity");if(c.moveToFirst()) decode(c) else null }
        internal fun values(vararg pairs:Pair<String,Any?>)=ContentValues().apply { pairs.forEach { (k,v) -> when(v) {
            null->putNull(k);is String->put(k,v);is Long->put(k,v);is Int->put(k,v);else->throw IllegalArgumentException("Unsupported value") } } }
        internal fun timestamp()=System.currentTimeMillis().also { integrity(it>0,"Invalid clock") }
        internal fun next(v:Long):Long { eligible(v<Long.MAX_VALUE,"Counter exhausted");return v+1 }
        internal fun hash(v:String) { integrity(v.matches(Regex("[0-9a-f]{64}")),"Invalid digest") }
        internal fun nonempty(v:String) { require(v.isNotEmpty()) }
        internal fun integrity(ok:Boolean,message:String) { if(!ok) throw TreeIntegrityException(message) }
        internal fun eligible(ok:Boolean,message:String) { if(!ok) throw TreeIneligibleException(message) }
    }
}
