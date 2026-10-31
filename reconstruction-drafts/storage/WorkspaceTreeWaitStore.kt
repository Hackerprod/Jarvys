package com.jarvys.agent.workspaces

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.identity
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.values
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.one
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.integrity
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.eligible
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.hash
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.nonempty
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.next
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.timestamp
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.s
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.optional
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.positive
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.bool

/** Same Registry transactions as assignments: this adapter is not another authority/store. */
internal class WorkspaceTreeWaitStore(private val core:WorkspaceTreeStore) {
    fun armWait(fence:TreeNodeFence,childIds:List<String>,join:TreeJoin,sourceReceipt:String,implicit:Boolean=false):TreeWait = core.registry.treeTransaction { db ->
        require(childIds.isNotEmpty() && childIds.distinct().size==childIds.size)
        nonempty(sourceReceipt);require(!implicit || join==TreeJoin.ALL)
        val ids=childIds.sorted();val parent=core.active(db,fence)
        eligible(parent.assignmentId!=null,"Central uses its native P1 continuation authority")
        val key=identity("wait",parent.nodeId,parent.cycleId,sourceReceipt)
        read(db,parent.rootId,key)?.let { old ->
            integrity(old.parentNodeId==parent.nodeId && old.parentCycleId==parent.cycleId && old.children==ids &&
                old.join==join && old.implicit==implicit && old.sourceReceipt==sourceReceipt,"Wait identity changed")
            return@treeTransaction old
        }
        eligible(parent.state==TreeNodeState.RUNNING,"Requester is not running")
        ids.forEach { id ->
            val child=core.requireNode(db,parent.rootId,id)
            eligible(child.parentNodeId==parent.nodeId && child.reply==TreeReply.AWAIT,"Wait contains foreign or reply-none child")
        }
        eligible(one(db,"bot_tree_wait","root_id=? AND parent_node_id=? AND state IN ('ARMING','WAITING','READY','STARTED')",
            arrayOf(parent.rootId,parent.nodeId)) { it.s("wait_id") }==null,"Requester already has unsettled wait")
        val parked=core.updateNode(db,parent,values("state" to "WAITING"))
        val now=timestamp()
        db.insertOrThrow("bot_tree_wait",null,values("root_id" to parent.rootId,"wait_id" to key,"parent_node_id" to parent.nodeId,
            "parent_cycle_id" to parent.cycleId,"parent_revision" to parked.revision,"revision" to 1L,"children_json" to JSONArray(ids).toString(),
            "join_mode" to join.name,"implicit_wait" to if(implicit) 1L else 0L,"state" to "ARMING","source_receipt" to sourceReceipt,
            "created_at" to now,"updated_at" to now))
        core.appendEvent(db,parked,identity("wait-arm",key),"WAIT_ARM",identity(ids.joinToString("\u0000"),join.name,implicit.toString()),
            sourceReceipt,identity(sourceReceipt),key,key)
        requireWait(db,parent.rootId,key)
    }
    fun confirmWait(expected:TreeWait,certificate:String,checkpointSha256:String):TreeWait = core.registry.treeTransaction { db ->
        val old=requireWait(db,expected.rootId,expected.waitId)
        if(old.state in setOf(TreeWaitState.WAITING,TreeWaitState.READY)) {
            integrity(old.checkpointCertificate==certificate && old.checkpointSha256==checkpointSha256 &&
                old.parentNodeId==expected.parentNodeId && old.parentCycleId==expected.parentCycleId,"Confirmed checkpoint changed")
            return@treeTransaction old
        }
        eligible(old==expected && old.state==TreeWaitState.ARMING,"Wait confirmation changed")
        val parent=requireParent(db,old);eligible(parent.state==TreeNodeState.WAITING,"Requester is not parked")
        nonempty(certificate);hash(checkpointSha256)
        val source=WorkspaceTreeStore.requireSourceCertificate(certificate,"native-workspace:"+requireNotNull(parent.workspaceId))
        integrity(source.getString("originCheckpointSha256")==checkpointSha256,"Checkpoint identity changed")
        val proof=identity(certificate,checkpointSha256)
        core.appendEvent(db,parent,identity("wait-confirmed",old.waitId),"WAIT_CONFIRMED",certificate,old.sourceReceipt,proof,old.waitId,old.waitId)
        val waiting=update(db,old,values("state" to "WAITING","checkpoint_certificate" to certificate,"checkpoint_sha256" to checkpointSha256))
        core.resetProgress(db,parent.rootId)
        waiting
    }
    fun prepareReady(rootId:String,waitId:String):TreeWait?=core.registry.treeTransaction { db ->
        val old=read(db,rootId,waitId) ?: return@treeTransaction null
        if(old.state !in setOf(TreeWaitState.WAITING,TreeWaitState.READY)) return@treeTransaction null
        requireParent(db,old)
        if(eligibleCauses(db,old).isEmpty()) return@treeTransaction null
        if(old.state==TreeWaitState.READY) old else update(db,old,values("state" to "READY"))
    }
    fun claimWait(expected:TreeWait,process:String,generation:String,token:String):TreeWaitClaim=core.registry.treeTransaction { db ->
        claimWithin(db,expected,process,generation,token,null)
    }
    fun claimUserWait(expected:TreeWait,controlId:String,process:String,generation:String,token:String):TreeUserWaitClaim=core.registry.treeTransaction { db ->
        val claim=claimWithin(db,expected,process,generation,token,controlId)
        TreeUserWaitClaim(claim,requireNotNull(core.registry.getControl(controlId)))
    }
    private fun claimWithin(db:SQLiteDatabase,expected:TreeWait,process:String,generation:String,token:String,controlId:String?):TreeWaitClaim {
        nonempty(process);nonempty(generation);nonempty(token)
        val old=requireWait(db,expected.rootId,expected.waitId)
        eligible(old==expected && old.state in setOf(TreeWaitState.WAITING,TreeWaitState.READY),"Wait is not an exact unclaimed episode")
        val parent=requireParent(db,old)
        val causes=eligibleCauses(db,old);eligible(causes.isNotEmpty(),"No exact eligible report")
        integrity(old.checkpointCertificate!=null && old.checkpointSha256!=null,"Missing confirmed safe point")
        val intent=identity("wait-claim",old.waitId,old.revision.toString(),process,generation,token)
        causes.forEach { cause ->
            val consumed=identity("consumed",parent.nodeId,cause.eventKey)
            integrity(core.event(db,parent.rootId,consumed)==null,"Cause already consumed")
            core.appendEvent(db,parent,consumed,"CONSUMED",encodeCause(cause).toString(),old.sourceReceipt,cause.evidenceHash,cause.evidenceRef,old.waitId)
        }
        val execution=identity("wait-execution",old.waitId)
        integrity(core.event(db,parent.rootId,execution)==null,"Wait execution already reserved")
        val assignment=core.requireNativeAssignment(parent)
        val admittedRevision=next(assignment.revision)
        val userControl=controlId?.let { id ->
            val control=core.registry.getControl(id) ?: throw TreeIntegrityException("Missing USER wait control")
            eligible(control.state==WorkspaceControlState.PENDING && control.type==WorkspaceControlType.RESUME &&
                control.origin==WorkspaceRegistry.NATIVE_UI_ORIGIN && control.assignmentId==parent.assignmentId &&
                control.workspaceId==parent.workspaceId && control.botId==parent.botId && control.assignmentRevision==assignment.revision,
                "USER Resume does not own this exact wait")
            core.registry.markControlStarted(id,control.revision,process)
        }
        if(userControl==null) integrity(db.update("assignment",values("revision" to admittedRevision,"updated_at" to timestamp()),
            "workspace_id=? AND assignment_id=? AND revision=? AND state='BOUND' AND bot_id=?",
            arrayOf(requireNotNull(parent.workspaceId),requireNotNull(parent.assignmentId),assignment.revision.toString(),requireNotNull(parent.botId)))==1,
            "Native child-wait revision CAS failed")
        val executionReceipt=JSONObject().put("intent",intent).put("sourceRevision",assignment.revision)
            .put("admittedRevision",admittedRevision).put("process",process).put("generation",generation).put("token",token).toString()
        core.appendEvent(db,parent,execution,"CHILD_WAIT_EXECUTION",executionReceipt,old.sourceReceipt,identity(executionReceipt),parent.nodeId,old.waitId)
        val root=core.requireRoot(db,parent.rootId)
        // Counted but specifically exempt from ordinary96 cap: exact confirmed child-wait only.
        core.updateRoot(db,root,values("executions" to next(root.executions)))
        val claimed=core.updateNode(db,parent,values("control_intent" to intent,"assignment_revision" to admittedRevision))
        val started=update(db,old,values("state" to "STARTED","parent_revision" to claimed.revision,
            "selected_causes" to encodeCauses(causes),"successor_intent" to intent,"owner_process" to process,
            "owner_generation" to generation,"owner_token" to token))
        val claim=TreeWaitClaim(started,causes,execution,assignment.revision,admittedRevision)
        if(userControl!=null) {
            integrity(userControl.admittedAssignmentRevision==admittedRevision,"USER wait revision changed")
            val authorization=JSONObject().put("controlId",userControl.requestId).put("claimDigest",claimDigest(claim))
                .put("waitId",old.waitId).put("sourceRevision",assignment.revision).put("admittedRevision",admittedRevision).toString()
            core.appendEvent(db,claimed,identity("user-wait",userControl.requestId),"USER_WAIT_AUTHORIZATION",authorization,
                userControl.requestId,identity(authorization),old.waitId,old.waitId)
        }
        return claim
    }
    fun bindSuccessor(expected:TreeWait,cycle:String,assignmentRevision:Long,certificate:String,
        process:String,generation:String,token:String):TreeNode=core.registry.treeTransaction { db ->
        val old=requireWait(db,expected.rootId,expected.waitId)
        eligible(old==expected && old.state==TreeWaitState.STARTED,"Wait claim changed")
        val parent=core.requireNode(db,old.rootId,old.parentNodeId)
        wakeAllowed(db,parent)
        eligible(parent.revision==old.parentRevision && parent.cycleId==old.parentCycleId && parent.controlIntent==old.successorIntent,
            "Native successor source changed")
        nonempty(cycle);nonempty(certificate);nonempty(process);nonempty(generation);nonempty(token)
        eligible(cycle!=old.parentCycleId && assignmentRevision==parent.assignmentRevision,"Successor identity changed")
        core.requireNativeAssignment(parent)
        val source=WorkspaceTreeStore.requireSourceCertificate(certificate,"native-workspace:"+requireNotNull(parent.workspaceId))
        val predecessor=WorkspaceTreeStore.requireSourceCertificate(requireNotNull(old.checkpointCertificate),source.getString("originScopeIdentity"))
        integrity(source.getString("profileSha256")==predecessor.getString("profileSha256"),"Successor profile changed")
        WorkspaceTreeStops.linkChildWait(core.registry.treeStorageIdentity,parent.rootId,parent.nodeId,old.parentCycleId,cycle)
        core.appendEvent(db,parent,identity("successor",old.waitId),"SUCCESSOR",certificate,old.sourceReceipt,identity(certificate),cycle,old.waitId)
        update(db,old,values("owner_process" to process,"owner_generation" to generation,"owner_token" to token))
        core.updateNode(db,parent,values("cycle_id" to cycle,"assignment_revision" to assignmentRevision,"state" to "RUNNING","control_intent" to null))
    }
    fun acknowledge(rootId:String,waitId:String,cycle:String,receipt:String):TreeWait=core.registry.treeTransaction { db ->
        nonempty(receipt);val old=requireWait(db,rootId,waitId)
        if(old.state==TreeWaitState.ACKNOWLEDGED) { integrity(old.incorporationReceipt==receipt,"Incorporation receipt changed");return@treeTransaction old }
        eligible(old.state==TreeWaitState.STARTED,"Wait was not claimed")
        val successor=one(db,"bot_tree_event","root_id=? AND event_key=? AND kind='SUCCESSOR'",arrayOf(rootId,identity("successor",waitId))) { it.s("payload_ref") }
        integrity(successor==cycle,"Incorporation cycle changed")
        update(db,old,values("state" to "ACKNOWLEDGED","incorporation_receipt" to receipt))
    }
    fun get(rootId:String,waitId:String):TreeWait?=core.registry.treeRead { if(it.version==1) null else read(it,rootId,waitId) }
    fun list(rootId:String,parentId:String):List<TreeWait> = core.registry.treeRead { db ->
        if(db.version==1) emptyList() else db.query("bot_tree_wait",null,"root_id=? AND parent_node_id=?",arrayOf(rootId,parentId),null,null,"created_at,wait_id").use { c ->
            buildList { while(c.moveToNext()) add(decode(c)) } }
    }
    fun readyCauses(expected:TreeWait):List<TreeCause> = core.registry.treeRead { db ->
        val current=requireWait(db,expected.rootId,expected.waitId)
        eligible(current==expected && current.state in setOf(TreeWaitState.WAITING,TreeWaitState.READY),"Wait selection changed")
        eligibleCauses(db,current)
    }
    internal fun eligibleCauses(db:SQLiteDatabase,wait:TreeWait):List<TreeCause> {
        val children=wait.children.map { core.requireNode(db,wait.rootId,it) }
        val causes=children.mapNotNull { child ->
            if(child.reportReceipt==null || child.controlIntent!=null) return@mapNotNull null
            val kind=when(child.state) { TreeNodeState.NEEDS_INPUT->"NEEDS_INPUT";TreeNodeState.TERMINAL->"TERMINAL";else->return@mapNotNull null }
            if(kind=="NEEDS_INPUT" && core.event(db,child.rootId,identity("answer-prepared",child.nodeId,requireNotNull(child.terminalRef)))!=null) return@mapNotNull null
            val cause=one(db,"bot_tree_event","root_id=? AND node_id=? AND cycle_id=? AND kind=? AND payload_ref=?",
                arrayOf(wait.rootId,child.nodeId,child.cycleId,kind,requireNotNull(child.terminalRef))) { c ->
                TreeCause(c.s("event_key"),child.nodeId,child.cycleId,kind,c.s("payload_ref"),c.s("payload_digest")) }
                ?: throw TreeIntegrityException("Reported source event missing")
            if(core.event(db,wait.rootId,identity("consumed",wait.parentNodeId,cause.eventKey))!=null) null else cause
        }.sortedBy { it.eventKey }
        causes.firstOrNull { it.kind=="NEEDS_INPUT" }?.let { return listOf(it) }
        if(wait.join==TreeJoin.ALL && children.any { it.state!=TreeNodeState.TERMINAL || it.reportReceipt==null || it.controlIntent!=null }) return emptyList()
        return if(wait.join==TreeJoin.ANY) causes.take(1) else causes
    }
    private fun requireParent(db:SQLiteDatabase,wait:TreeWait):TreeNode {
        val parent=core.requireNode(db,wait.rootId,wait.parentNodeId);wakeAllowed(db,parent)
        eligible(parent.cycleId==wait.parentCycleId && parent.revision==wait.parentRevision && parent.controlIntent==null,"Wait owner changed")
        return parent
    }
    private fun wakeAllowed(db:SQLiteDatabase,parent:TreeNode) {
        val root=core.requireRoot(db,parent.rootId);val central=core.requireNode(db,parent.rootId,parent.rootId)
        WorkspaceTreeStops.requireAllowed(core.registry.treeStorageIdentity,parent.rootId,parent.rootId,central.cycleId)
        WorkspaceTreeStops.requireAllowed(core.registry.treeStorageIdentity,parent.rootId,parent.nodeId,parent.cycleId)
        eligible(!root.wakesClosed && !root.admissionsClosed && !parent.wakesClosed && !parent.admissionsClosed,"Wait requester is stopped")
    }
    private fun read(db:SQLiteDatabase,root:String,id:String)=one(db,"bot_tree_wait","root_id=? AND wait_id=?",arrayOf(root,id),::decode)
    private fun requireWait(db:SQLiteDatabase,root:String,id:String)=read(db,root,id) ?: throw TreeIntegrityException("Missing wait")
    private fun update(db:SQLiteDatabase,old:TreeWait,v:ContentValues):TreeWait {
        v.put("revision",next(old.revision));v.put("updated_at",timestamp())
        integrity(db.update("bot_tree_wait",v,"root_id=? AND wait_id=? AND revision=?",arrayOf(old.rootId,old.waitId,old.revision.toString()))==1,"Wait CAS failed")
        return requireWait(db,old.rootId,old.waitId)
    }
    private fun decode(c:Cursor):TreeWait {
        val children=array(c.s("children_json")).map { integrity(it is String,"Child identity type");it as String }
        integrity(children.isNotEmpty() && children.distinct().size==children.size && children==children.sorted(),"Malformed child set")
        val selected=c.optional("selected_causes")?.let { array(it).map { value -> integrity(value is JSONObject,"Cause type");decodeCause(value as JSONObject) } }
        return TreeWait(c.s("root_id"),c.s("wait_id"),c.s("parent_node_id"),c.s("parent_cycle_id"),c.positive("parent_revision"),c.positive("revision"),
            children,enumValueOf(c.s("join_mode")),c.bool("implicit_wait"),enumValueOf(c.s("state")),c.s("source_receipt"),c.optional("checkpoint_certificate"),
            c.optional("checkpoint_sha256"),selected,c.optional("successor_intent"),c.optional("owner_process"),c.optional("owner_generation"),c.optional("owner_token"),c.optional("incorporation_receipt"))
    }
    companion object {
        /** Shared immutable claim binding only; a digest is never execution authority. */
        fun claimDigest(claim:TreeWaitClaim):String {
            val w=claim.wait
            integrity(w.state==TreeWaitState.STARTED && w.selectedCauses==claim.causes &&
                claim.sourceAssignmentRevision<Long.MAX_VALUE && claim.admittedAssignmentRevision==claim.sourceAssignmentRevision+1,
                "Malformed exact wait claim")
            val receipt=JSONObject().put("rootId",w.rootId).put("nodeId",w.parentNodeId).put("waitId",w.waitId)
                .put("waitRevision",w.revision).put("oldCycle",w.parentCycleId)
                .put("sourceRevision",claim.sourceAssignmentRevision).put("admittedRevision",claim.admittedAssignmentRevision)
                .put("intent",requireNotNull(w.successorIntent)).put("executionEventId",claim.executionEventId)
                .put("process",requireNotNull(w.ownerProcess)).put("generation",requireNotNull(w.ownerGeneration))
                .put("token",requireNotNull(w.ownerToken)).put("causes",JSONArray(claim.causes.map { encodeCause(it) }))
            return identity(WorkspaceTreeEvidenceStore.canonical(receipt))
        }

        private fun array(raw:String):List<Any> { val a=JSONArray(raw);return (0 until a.length()).map { a.get(it) } }
        internal fun encodeCause(c:TreeCause)=JSONObject().put("eventKey",c.eventKey).put("nodeId",c.nodeId).put("cycleId",c.cycleId)
            .put("kind",c.kind).put("evidenceRef",c.evidenceRef).put("evidenceHash",c.evidenceHash)
        private fun encodeCauses(c:List<TreeCause>)=JSONArray(c.map(::encodeCause)).toString()
        private fun decodeCause(o:JSONObject):TreeCause {
            val keys=setOf("eventKey","nodeId","cycleId","kind","evidenceRef","evidenceHash")
            integrity(o.keys().asSequence().toSet()==keys && keys.all { o.get(it) is String },"Malformed selected cause")
            hash(o.getString("evidenceHash"));eligible(o.getString("kind") in setOf("NEEDS_INPUT","TERMINAL"),"Unknown cause")
            return TreeCause(o.getString("eventKey"),o.getString("nodeId"),o.getString("cycleId"),o.getString("kind"),o.getString("evidenceRef"),o.getString("evidenceHash"))
        }
    }
}
