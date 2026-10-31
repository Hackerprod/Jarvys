package com.jarvys.agent.workspaces

import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.identity
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.values
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.one
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.s
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.nonempty
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.eligible
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.integrity
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.hash
import org.json.JSONObject

internal data class TreeAnswer(val rootId:String,val childId:String,val questionId:String,val questionHash:String,
    val questionCycle:String,val text:String,val sourceReceipt:String,val digest:String,val eventKey:String)
/** Formal answers are retained data. Only a separately registered explicit USER control executes them. */
internal class WorkspaceTreeAnswerStore(private val core:WorkspaceTreeStore) {
    fun prepare(parentFence:TreeNodeFence,childFence:TreeNodeFence,text:String,source:String):TreeAnswer=core.registry.treeTransaction { db ->
        nonempty(text);nonempty(source)
        val parent=core.active(db,parentFence);val child=core.current(db,childFence)
        eligible(child.rootId==parent.rootId && child.parentNodeId==parent.nodeId && child.reply==TreeReply.AWAIT &&
            child.state==TreeNodeState.NEEDS_INPUT && child.reportReceipt!=null,"No owned formal question")
        val question=requireNotNull(child.terminalRef);val questionHash=requireNotNull(child.terminalHash)
        val key=identity("answer-prepared",child.nodeId,question)
        val receipt=WorkspaceTreeEvidenceStore.canonical(JSONObject().put("root",child.rootId).put("child",child.nodeId)
            .put("question",question).put("questionHash",questionHash).put("cycle",child.cycleId).put("text",text).put("source",source))
        core.appendEvent(db,child,key,"ANSWER_PREPARED",receipt,source,identity(receipt),question)
        decode(receipt,key)
    }
    fun pending(rootId:String,childId:String):TreeAnswer?=core.registry.treeRead { db ->
        if(db.version==1)return@treeRead null
        val child=core.requireNode(db,rootId,childId)
        if(child.state!=TreeNodeState.NEEDS_INPUT || child.terminalRef==null)return@treeRead null
        val key=identity("answer-prepared",child.nodeId,child.terminalRef)
        core.event(db,rootId,key)?.let { decode(it,key) }
    }
    internal fun claimInTransaction(db:android.database.sqlite.SQLiteDatabase,node:TreeNode,control:WorkspaceUserControl) {
        if(node.state!=TreeNodeState.NEEDS_INPUT)return
        eligible(control.type in setOf(WorkspaceControlType.RESUME,WorkspaceControlType.CORRECT),"Formal answer needs explicit USER Resume")
        val key=identity("answer-prepared",node.nodeId,requireNotNull(node.terminalRef))
        val answer=decode(core.event(db,node.rootId,key) ?: throw TreeIneligibleException("Formal question has no retained answer"),key)
        integrity(answer.questionCycle==node.cycleId && answer.questionHash==node.terminalHash,"Answer question changed")
        val started=identity("answer-started",key)
        eligible(core.event(db,node.rootId,started)==null,"Answer already started; never replay")
        core.appendEvent(db,node,started,"ANSWER_STARTED",control.requestId,key,answer.digest,answer.questionId)
    }
    fun forControl(rootId:String,childId:String,controlId:String):TreeAnswer?=core.registry.treeRead { db ->
        one(db,"bot_tree_event","root_id=? AND node_id=? AND kind='ANSWER_STARTED' AND receipt=?",arrayOf(rootId,childId,controlId)) { c ->
            val key=c.s("source_receipt");val answer=decode(core.event(db,rootId,key) ?: throw TreeIntegrityException("Missing prepared answer"),key)
            integrity(answer.digest==c.s("payload_digest") && answer.questionId==c.s("payload_ref"),"Answer claim changed");answer
        }
    }
    fun acknowledge(answer:TreeAnswer,controlId:String,cycle:String,checkpoint:String)=core.registry.treeTransaction { db ->
        val current=requireNotNull(forControl(answer.rootId,answer.childId,controlId));integrity(current==answer,"Answer content changed")
        val node=core.requireNode(db,answer.rootId,answer.childId)
        val successor=one(db,"bot_tree_event","root_id=? AND event_key=? AND kind='CONTROL_SUCCESSOR'",arrayOf(answer.rootId,identity("control-successor",controlId))){it.s("payload_ref")}
        integrity(successor==cycle,"Answer incorporation successor changed")
        val key=identity("answer-ack",answer.eventKey)
        core.event(db,answer.rootId,key)?.let { return@treeTransaction }
        core.appendEvent(db,node,key,"ANSWER_ACK",checkpoint,controlId,identity(checkpoint),answer.questionId)
    }
    companion object {
        private fun decode(raw:String,key:String):TreeAnswer {
            val o=JSONObject(raw);val keys=setOf("root","child","question","questionHash","cycle","text","source")
            integrity(o.keys().asSequence().toSet()==keys && keys.all { o.get(it) is String },"Malformed answer")
            hash(o.getString("questionHash"));integrity(raw==WorkspaceTreeEvidenceStore.canonical(o),"Noncanonical answer")
            integrity(key==identity("answer-prepared",o.getString("child"),o.getString("question")),"Answer identity changed")
            return TreeAnswer(o.getString("root"),o.getString("child"),o.getString("question"),o.getString("questionHash"),o.getString("cycle"),
                o.getString("text"),o.getString("source"),identity(raw),key)
        }
    }
}
