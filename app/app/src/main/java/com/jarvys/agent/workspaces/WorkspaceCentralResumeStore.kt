package com.jarvys.agent.workspaces

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.identity
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.integrity
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.eligible
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.nonempty
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.one
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.values
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.timestamp
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.next
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.s
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.optional
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.positive
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.nonnegative
import org.json.JSONObject

/** Immutable snapshots, not execution capabilities. All mutations serialize in the native Registry. */
data class CentralResumeRecord(val resumeId:String,val rootId:String,val nodeId:String,val causeKind:String,val causeId:String,
    val certificate:String,val sourceDigest:String,val revision:Long,val state:String,val parentCycle:String,val parentRevision:Long,
    val stopEpoch:Long,val ownerProcess:String?,val ownerRun:String?,val ownerToken:String?,val successorCycle:String?,
    val bindingReceipt:String?,val incorporationReceipt:String?)

internal class WorkspaceCentralResumeStore(private val core:WorkspaceTreeStore) {
    companion object {
        private val unsettled=setOf("PREPARED","RESERVED","BOUND","UNCERTAIN")
        private fun decode(c:Cursor):CentralResumeRecord {
            val row=CentralResumeRecord(c.s("resume_id"),c.s("root_id"),c.s("node_id"),c.s("cause_kind"),c.s("cause_id"),
                c.s("source_certificate"),c.s("source_digest"),c.positive("revision"),c.s("state"),c.s("parent_cycle_id"),
                c.positive("parent_revision"),c.nonnegative("root_stop_epoch"),c.optional("owner_process"),c.optional("owner_run"),
                c.optional("owner_token"),c.optional("successor_cycle"),c.optional("binding_receipt"),c.optional("incorporation_receipt"))
            integrity(row.nodeId==row.rootId && row.causeKind in setOf("SUSPENSION","CHILD_RESULT") &&
                row.state in unsettled+setOf("INCORPORATED","CANCELLED"),"Invalid Central resume identity/state")
            integrity(row.resumeId==identity("central-resume",row.rootId,row.causeKind,row.causeId) &&
                row.sourceDigest==identity(row.certificate),"Central resume source changed")
            val owner=listOf(row.ownerProcess,row.ownerRun,row.ownerToken,row.successorCycle)
            integrity(owner.all{it==null} || owner.all{!it.isNullOrBlank()},"Partial Central resume owner")
            when(row.state) {
                "PREPARED" -> integrity(owner.all{it==null} && row.bindingReceipt==null && row.incorporationReceipt==null,"Prepared resume has owner")
                "RESERVED" -> integrity(owner.all{it!=null} && row.bindingReceipt==null && row.incorporationReceipt==null,"Invalid reservation")
                "BOUND" -> integrity(owner.all{it!=null} && row.bindingReceipt!=null && row.incorporationReceipt==null,"Invalid bound resume")
                "INCORPORATED" -> integrity(owner.all{it!=null} && row.bindingReceipt!=null && row.incorporationReceipt!=null,"Invalid incorporation")
            }
            return row
        }
        internal fun hasPending(db:SQLiteDatabase,root:String):Boolean {
            if(db.version<3)return false
            return db.query("bot_tree_central_resume",null,"root_id=?",arrayOf(root),null,null,"created_at,resume_id").use{c->
                var pending=false;while(c.moveToNext()){if(decode(c).state in unsettled)pending=true};pending }
        }
        internal fun dispose(db:SQLiteDatabase,root:String) {
            if(db.version<3)return
            // Denial only. Claimed bridge state is uncertain, never PREPARED again.
            db.execSQL("UPDATE bot_tree_central_resume SET state=CASE WHEN state='PREPARED' THEN 'CANCELLED' ELSE 'UNCERTAIN' END,revision=revision+1,updated_at=? WHERE root_id=? AND state IN ('PREPARED','RESERVED','BOUND')",arrayOf(timestamp(),root))
        }
    }
    private fun requireSchema(db:SQLiteDatabase){eligible(db.version==3,"Central resume schema is not ready")}
    private fun row(db:SQLiteDatabase,id:String)=one(db,"bot_tree_central_resume","resume_id=?",arrayOf(id),::decode)
    fun get(id:String):CentralResumeRecord?=core.registry.treeRead{db->requireSchema(db);row(db,id)}
    fun byCause(root:String,kind:String,cause:String):CentralResumeRecord?=core.registry.treeRead{db->
        if(db.version<3)null else row(db,identity("central-resume",root,kind,cause)) }
    private fun update(db:SQLiteDatabase,old:CentralResumeRecord,vararg changes:Pair<String,Any?>):CentralResumeRecord {
        val v=values(*changes);v.put("revision",next(old.revision));v.put("updated_at",timestamp())
        eligible(db.update("bot_tree_central_resume",v,"resume_id=? AND revision=?",arrayOf(old.resumeId,old.revision.toString()))==1,"Resume revision changed")
        return requireNotNull(row(db,old.resumeId))
    }
    private fun insert(db:SQLiteDatabase,parent:TreeNode,kind:String,cause:String,certificate:String):CentralResumeRecord {
        nonempty(cause);nonempty(certificate);val id=identity("central-resume",parent.rootId,kind,cause)
        row(db,id)?.let{integrity(it.certificate==certificate && it.parentCycle==parent.cycleId,"Resume source reused");return it}
        val now=timestamp()
        db.insertOrThrow("bot_tree_central_resume",null,values("resume_id" to id,"root_id" to parent.rootId,"node_id" to parent.nodeId,
            "cause_kind" to kind,"cause_id" to cause,"source_certificate" to certificate,"source_digest" to identity(certificate),
            "revision" to 1L,"state" to "PREPARED","parent_cycle_id" to parent.cycleId,"parent_revision" to parent.revision,
            "root_stop_epoch" to parent.stopEpoch,"created_at" to now,"updated_at" to now))
        return requireNotNull(row(db,id))
    }
    fun park(fence:TreeNodeFence,cause:String,certificate:String):CentralResumeRecord=core.registry.treeTransaction{db->
        requireSchema(db)
        val id=identity("central-resume",fence.rootId,"SUSPENSION",cause)
        row(db,id)?.let{prior->integrity(prior.certificate==certificate && prior.parentCycle==fence.cycleId,"Yield receipt changed");return@treeTransaction prior}
        val parent=core.active(db,fence)
        eligible(parent.mode==TreeMode.CENTRAL && parent.state==TreeNodeState.RUNNING,"Only running Central can yield")
        eligible(!hasPending(db,parent.rootId),"A Central resume is already unresolved")
        val parked=core.updateNode(db,parent,values("state" to "WAITING"))
        val result=insert(db,parked,"SUSPENSION",cause,certificate)
        core.appendEvent(db,parked,identity("central-yield",cause),"CENTRAL_YIELD",certificate,parent.sourceReceipt,identity(certificate),cause)
        result // No progress reset or execution charge for parking.
    }
    private fun reserve(db:SQLiteDatabase,expected:CentralResumeRecord,process:String,run:String,token:String,cycle:String):CentralResumeRecord? {
        listOf(process,run,token,cycle).forEach(::nonempty)
        val current=row(db,expected.resumeId) ?: throw TreeIntegrityException("Resume disappeared")
        integrity(current.sourceDigest==expected.sourceDigest && current.certificate==expected.certificate,"Resume source changed")
        if(current.state!="PREPARED" || current.revision!=expected.revision)return null
        val parent=core.requireNode(db,current.rootId,current.nodeId);val root=core.requireRoot(db,current.rootId)
        if(root.admissionsClosed || root.wakesClosed || parent.admissionsClosed || parent.wakesClosed ||
            parent.controlIntent!=null || parent.state!=TreeNodeState.WAITING)return null
        eligible(parent.cycleId==current.parentCycle && parent.revision==current.parentRevision && parent.stopEpoch==current.stopEpoch,"Parked Central fence changed")
        WorkspaceTreeStops.requireAllowed(core.registry.treeStorageIdentity,parent.rootId,parent.nodeId,parent.cycleId)
        if(current.causeKind=="CHILD_RESULT") {
            val suspension=db.query("bot_tree_central_resume",null,"root_id=? AND cause_kind='SUSPENSION'",arrayOf(current.rootId),null,null,null).use{c->
                var found=false;while(c.moveToNext())if(decode(c).state in unsettled)found=true;found}
            if(suspension)return null
        }
        val reserved=update(db,current,"state" to "RESERVED","owner_process" to process,"owner_run" to run,"owner_token" to token,"successor_cycle" to cycle)
        core.updateNode(db,parent,values("control_intent" to current.resumeId))
        return reserved
    }
    fun reserveSuspension(expected:CentralResumeRecord,process:String,run:String,token:String,cycle:String):CentralResumeRecord?=core.registry.treeTransaction{db->
        requireSchema(db);integrity(expected.causeKind=="SUSPENSION","Wrong resume cause");reserve(db,expected,process,run,token,cycle)}
    fun reserveChild(fence:TreeNodeFence,childId:String,claim:String,process:String,run:String,token:String,cycle:String):CentralResumeRecord?=core.registry.treeTransaction{db->
        requireSchema(db)
        // Losing P1 must leave even its PREPARED source/capability untouched.
        if(hasPending(db,fence.rootId))return@treeTransaction null
        val parent=core.active(db,fence);eligible(parent.state==TreeNodeState.WAITING && parent.mode==TreeMode.CENTRAL,"Central is not waiting")
        val child=core.requireNode(db,parent.rootId,childId)
        eligible(child.parentNodeId==parent.nodeId && child.reply==TreeReply.AWAIT && child.state==TreeNodeState.TERMINAL && child.reportReceipt!=null && child.controlIntent==null,"Child cause is not reported")
        core.requireNativeAssignment(child)
        eligible(one(db,"bot_tree_event","root_id=? AND kind='CENTRAL_CLAIM' AND payload_ref=?",arrayOf(parent.rootId,requireNotNull(child.terminalRef))){it.s("event_key")}==null,"Child cause already claimed")
        val cert=JSONObject().put("kind","CHILD_RESULT").put("child",childId).put("evidence",child.terminalRef)
            .put("digest",child.terminalHash).put("report",child.reportReceipt).put("claim",claim).toString()
        reserve(db,insert(db,parent,"CHILD_RESULT",claim,cert),process,run,token,cycle)
    }
    private fun requireOwner(current:CentralResumeRecord,expected:CentralResumeRecord) {
        integrity(current.resumeId==expected.resumeId && current.sourceDigest==expected.sourceDigest &&
            current.ownerProcess==expected.ownerProcess && current.ownerRun==expected.ownerRun && current.ownerToken==expected.ownerToken &&
            current.successorCycle==expected.successorCycle,"Resume owner changed")
    }
    fun bind(expected:CentralResumeRecord,receipt:String):CentralResumeRecord=core.registry.treeTransaction{db->
        requireSchema(db);nonempty(receipt);val current=requireNotNull(row(db,expected.resumeId));requireOwner(current,expected)
        if(current.state=="BOUND" || current.state=="INCORPORATED") {integrity(current.bindingReceipt==receipt,"Binding changed");return@treeTransaction current}
        eligible(current.state=="RESERVED" && current.revision==expected.revision,"Resume is not reserved")
        val parent=core.requireNode(db,current.rootId,current.nodeId);val root=core.requireRoot(db,current.rootId)
        eligible(!root.admissionsClosed && !root.wakesClosed && !parent.admissionsClosed && !parent.wakesClosed &&
            parent.state==TreeNodeState.WAITING && parent.controlIntent==current.resumeId && parent.cycleId==current.parentCycle &&
            parent.revision==next(current.parentRevision) && parent.stopEpoch==current.stopEpoch,"Reserved Central fence changed")
        WorkspaceTreeStops.requireAllowed(core.registry.treeStorageIdentity,parent.rootId,parent.nodeId,parent.cycleId)
        eligible(root.executions<WorkspaceTreePolicy.MAX_EXECUTIONS,"Execution progress window exhausted")
        core.updateRoot(db,root,values("executions" to next(root.executions)))
        core.updateNode(db,parent,values("state" to "RUNNING","cycle_id" to requireNotNull(current.successorCycle)))
        update(db,current,"state" to "BOUND","binding_receipt" to receipt)
    }
    fun incorporate(expected:CentralResumeRecord,receipt:String):CentralResumeRecord=core.registry.treeTransaction{db->
        requireSchema(db);nonempty(receipt);val current=requireNotNull(row(db,expected.resumeId));requireOwner(current,expected)
        if(current.state=="INCORPORATED"){integrity(current.incorporationReceipt==receipt,"Incorporation changed");return@treeTransaction current}
        eligible(current.state=="BOUND","Resume not bound")
        val parent=core.requireNode(db,current.rootId,current.nodeId);val root=core.requireRoot(db,current.rootId)
        eligible(!root.admissionsClosed && !root.wakesClosed && parent.controlIntent==current.resumeId && parent.cycleId==current.successorCycle && parent.state==TreeNodeState.RUNNING,"Bound Central owner changed")
        WorkspaceTreeStops.requireAllowed(core.registry.treeStorageIdentity,parent.rootId,parent.nodeId,parent.cycleId)
        core.updateNode(db,parent,values("control_intent" to null))
        core.appendEvent(db,parent,identity("central-resume-incorporated",current.resumeId),"CENTRAL_RESUME_INCORPORATED",receipt,current.sourceDigest,identity(receipt),current.resumeId)
        update(db,current,"state" to "INCORPORATED","incorporation_receipt" to receipt)
    }
    fun stopPrepared(expected:CentralResumeRecord)=core.registry.treeTransaction{db->
        requireSchema(db);val current=row(db,expected.resumeId) ?: throw TreeIntegrityException("Stopped resume missing")
        integrity(current.sourceDigest==expected.sourceDigest && current.parentCycle==expected.parentCycle,"Stopped resume source changed")
        val parent=core.requireNode(db,current.rootId,current.nodeId)
        if(parent.cycleId!=expected.parentCycle || parent.stopEpoch!=expected.stopEpoch)return@treeTransaction
        WorkspaceTreeStops.latchExact(core.registry.treeStorageIdentity,parent.rootId,parent.nodeId,parent.cycleId)
        core.stopRequester(TreeNodeFence.of(parent)){targets->targets.forEach{WorkspaceTreeStops.latchExact(core.registry.treeStorageIdentity,it.rootId,it.nodeId,it.cycleId)}}
    }
    fun uncertain(expected:CentralResumeRecord)=core.registry.treeTransaction{db->
        requireSchema(db);val current=requireNotNull(row(db,expected.resumeId));requireOwner(current,expected)
        if(current.state in setOf("RESERVED","BOUND"))update(db,current,"state" to "UNCERTAIN") else current
    }
}
