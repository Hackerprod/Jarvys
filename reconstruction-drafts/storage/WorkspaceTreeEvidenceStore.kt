package com.jarvys.agent.workspaces

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
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.s
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.optional
import com.jarvys.agent.workspaces.WorkspaceTreeStore.Companion.positive

/** Registry evidence adapter only. Native live capabilities/checkpoint IO remain at the caller. */
internal data class TreeInvocationReceipt(val sourceKey:String,val kind:String,val sourceReceipt:String,
    val argsJson:String,val resultJson:String?,val resultHash:String?)
internal class WorkspaceTreeEvidenceStore(private val core:WorkspaceTreeStore) {
    fun recordInvocation(fence:TreeNodeFence,callId:String,kind:String,sourceReceipt:String,argsJson:String):String = core.registry.treeTransaction { db ->
        require(kind in setOf("ADMISSION","WAIT_ARM","QUESTION_ARM","REPORT_DONE","ANSWER_ARM"))
        val node=core.active(db,fence);eligible(node.state==TreeNodeState.RUNNING,"Invocation worker not running")
        val assignment=core.requireNativeAssignment(node)
        val source=parseObject(sourceReceipt);val args=parseObject(argsJson)
        requireKeys(source,setOf("version","executionSessionId","botId","missionId","cycleId","scopeIdentity",
            "checkpointPayloadSha256","callId","toolName","argumentsDigest"))
        integrity(integer(source,"version")==1L,"Unsupported invocation source")
        integrity(string(source,"executionSessionId")==WorkspaceIdentity.executionSessionId(requireNotNull(node.workspaceId)) &&
            string(source,"botId")==node.botId && string(source,"missionId")==assignment.missionId &&
            string(source,"cycleId")==node.cycleId && string(source,"scopeIdentity")=="native-workspace:${node.workspaceId}" &&
            string(source,"callId")==callId,"Invocation source owner changed")
        nonempty(string(source,"toolName"));hash(string(source,"checkpointPayloadSha256"))
        val canonicalArgs=canonical(args);integrity(string(source,"argumentsDigest")==identity(canonicalArgs),"Invocation arguments changed")
        val canonicalSource=canonical(source);val key=identity("invocation",node.nodeId,node.cycleId,callId)
        val receipt=canonical(JSONObject().put("kind",kind).put("sourceReceipt",canonicalSource).put("argsJson",canonicalArgs))
        core.appendEvent(db,node,key,"INVOCATION",receipt,canonicalSource,identity(receipt),callId)
        key
    }
    fun recordInvocationResult(rootId:String,nodeId:String,cycleId:String,callId:String,sourceKey:String,resultJson:String) = core.registry.treeTransaction { db ->
        val node=core.requireNode(db,rootId,nodeId);eligible(node.cycleId==cycleId,"Invocation result cycle changed")
        requireInvocation(db,rootId,nodeId,cycleId,callId,sourceKey)
        val result=parseObject(resultJson);requireKeys(result,setOf("success","content","completeContentRequired","finishRun"))
        listOf("success","completeContentRequired","finishRun").forEach { integrity(result.get(it) is Boolean,"Result boolean type changed") }
        string(result,"content")
        val exact=canonical(result)
        core.appendEvent(db,node,identity("invocation-result",sourceKey),"INVOCATION_RESULT",exact,sourceKey,identity(exact),callId)
        Unit
    }
    fun invocationReceipts(rootId:String,nodeId:String,cycleId:String):List<TreeInvocationReceipt> = core.registry.treeRead { db ->
        if(db.version==1) return@treeRead emptyList()
        db.query("bot_tree_event",null,"root_id=? AND node_id=? AND cycle_id=? AND kind='INVOCATION'",
            arrayOf(rootId,nodeId,cycleId),null,null,"node_revision,event_key").use { c -> buildList {
            while(c.moveToNext()) {
                val key=c.s("event_key");val receipt=requireInvocation(db,rootId,nodeId,cycleId,c.s("payload_ref"),key)
                val result=one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(rootId,identity("invocation-result",key))) { r ->
                    integrity(r.s("kind")=="INVOCATION_RESULT" && r.s("node_id")==nodeId && r.s("cycle_id")==cycleId &&
                        r.s("source_receipt")==key && r.s("payload_ref")==c.s("payload_ref") && identity(r.s("receipt"))==r.s("payload_digest"),"Result evidence changed")
                    r.s("receipt") to r.s("payload_digest") }
                add(TreeInvocationReceipt(key,string(receipt,"kind"),string(receipt,"sourceReceipt"),string(receipt,"argsJson"),result?.first,result?.second))
            }
        } }
    }
    fun invocationReceiptsForNode(rootId:String,nodeId:String):List<TreeInvocationReceipt> = core.registry.treeRead { db ->
        if(db.version==1) return@treeRead emptyList()
        val cycles=db.query("bot_tree_event",arrayOf("cycle_id"),"root_id=? AND node_id=? AND kind='INVOCATION'",
            arrayOf(rootId,nodeId),"cycle_id",null,"cycle_id").use { c -> buildList { while(c.moveToNext()) add(c.s("cycle_id")) } }
        cycles.flatMap { cycle -> invocationReceipts(rootId,nodeId,cycle) }
    }
    private fun requireInvocation(db:SQLiteDatabase,root:String,node:String,cycle:String,call:String,key:String):JSONObject {
        integrity(key==identity("invocation",node,cycle,call),"Invocation identity changed")
        return one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(root,key)) { c ->
            integrity(c.s("node_id")==node && c.s("cycle_id")==cycle && c.s("kind")=="INVOCATION" && c.s("payload_ref")==call &&
                c.s("payload_digest")==identity(c.s("receipt")),"Invocation row changed")
            val receipt=parseObject(c.s("receipt"));requireKeys(receipt,setOf("kind","sourceReceipt","argsJson"))
            integrity(string(receipt,"sourceReceipt")==c.s("source_receipt"),"Invocation source receipt changed")
            val source=parseObject(string(receipt,"sourceReceipt"));integrity(string(source,"cycleId")==cycle && string(source,"callId")==call &&
                string(source,"argumentsDigest")==identity(string(receipt,"argsJson")),"Invocation content changed")
            receipt
        } ?: throw TreeIntegrityException("Missing committed invocation")
    }
    fun recordQuestion(fence:TreeNodeFence,evidenceRef:String,evidenceHash:String,sourceReceipt:String):TreeNode = core.registry.treeTransaction { db ->
        nonempty(evidenceRef);hash(evidenceHash)
        val node=core.requireNode(db,fence.rootId,fence.nodeId);val source=parseObject(sourceReceipt)
        val call=string(source,"callId");val key=identity("invocation",node.nodeId,fence.cycleId,call)
        val invocation=requireInvocation(db,node.rootId,node.nodeId,fence.cycleId,call,key)
        integrity(string(invocation,"kind")=="QUESTION_ARM" && string(invocation,"sourceReceipt")==canonical(source),"Question has no exact armed invocation")
        val eventKey=identity("question",node.nodeId,fence.cycleId,evidenceRef)
        val receipt=identity(evidenceRef,evidenceHash,key)
        core.event(db,node.rootId,eventKey)?.let { integrity(it==receipt && node.cycleId==fence.cycleId,"Question source changed");return@treeTransaction node }
        core.active(db,fence);eligible(node.state==TreeNodeState.RUNNING,"Question producer not running")
        core.appendEvent(db,node,eventKey,"NEEDS_INPUT",receipt,key,evidenceHash,evidenceRef)
        core.updateNode(db,node,values("state" to "NEEDS_INPUT","terminal_ref" to evidenceRef,"terminal_hash" to evidenceHash,"report_receipt" to null))
    }
    /** Outbox receipt repair only. The retained report is not a scheduling permission. */
    fun markReported(rootId:String,nodeId:String,evidenceRef:String,evidenceHash:String,reportReceipt:String,
                     beforeBudgetDenial:((List<TreeNode>)->Unit)?=null):TreeNode = core.registry.treeTransaction { db ->
        nonempty(reportReceipt);hash(evidenceHash)
        val node=core.requireNode(db,rootId,nodeId)
        val cause=one(db,"bot_tree_event","root_id=? AND node_id=? AND payload_ref=? AND kind IN ('TERMINAL','NEEDS_INPUT')",
            arrayOf(rootId,nodeId,evidenceRef)) { c ->
            integrity(c.s("payload_digest")==evidenceHash,"Report source hash changed")
            Triple(c.s("event_key"),c.s("cycle_id"),c.s("kind")) } ?: throw TreeIntegrityException("No reportable source event")
        val producer=node.copy(cycleId=cause.second);val key=identity("reported",cause.first)
        core.event(db,rootId,key)?.let { integrity(it==reportReceipt,"Report receipt changed");return@treeTransaction node }
        core.appendEvent(db,producer,key,"REPORTED",reportReceipt,cause.first,identity(reportReceipt),evidenceRef)
        val isCurrent=node.cycleId==cause.second && node.terminalRef==evidenceRef && node.terminalHash==evidenceHash
        if(isCurrent) core.updateNode(db,node,values("report_receipt" to reportReceipt))
        val root=core.requireRoot(db,rootId)
        val parent=node.parentNodeId?.let { core.requireNode(db,rootId,it) }
        if(node.reply!=TreeReply.NONE && parent!=null && !root.wakesClosed && !parent.wakesClosed) {
            val wakes=next(root.wakes);core.updateRoot(db,root,values("wakes" to wakes))
            if(cause.third=="NEEDS_INPUT" && isCurrent && wakes>WorkspaceTreePolicy.MAX_WAKES) {
                val central=core.requireNode(db,rootId,rootId)
                core.cancelSubtree(TreeNodeFence.of(central),"BUDGET",identity("wake-overflow",cause.first),beforeBudgetDenial)
            }
        }
        core.requireNode(db,rootId,nodeId)
    }

    /** Positive ordered chain validation; a latest matching hash alone is never a producer proof. */
    fun requireCertifiedProducer(rootId:String,nodeId:String,cycleId:String,assignmentId:String,botId:String,
        scopeIdentity:String,profileSha256:String,storageGeneration:String) = requireProducerChain(rootId,nodeId,cycleId,
            assignmentId,botId,scopeIdentity,profileSha256,storageGeneration,null)

    /** Historical source validation only. A persisted STARTED claim never grants native scheduling. */
    fun requireCertifiedPredecessor(rootId:String,nodeId:String,cycleId:String,assignmentId:String,botId:String,
        scopeIdentity:String,profileSha256:String,storageGeneration:String,savedSourceCertificate:String,
        checkpointSha256:String,pendingWaitId:String?=null,pendingControlId:String?=null) {
        integrity((pendingWaitId==null)!=(pendingControlId==null),"Exactly one pending claim is required")
        requireProducerChain(rootId,nodeId,cycleId,assignmentId,botId,scopeIdentity,profileSha256,storageGeneration,
            Predecessor(savedSourceCertificate,checkpointSha256,pendingWaitId,pendingControlId))
    }
    private data class Predecessor(val certificate:String,val checkpoint:String,val waitId:String?,val controlId:String?)
    private fun requireProducerChain(rootId:String,nodeId:String,cycleId:String,assignmentId:String,botId:String,
        scopeIdentity:String,profileSha256:String,storageGeneration:String,predecessor:Predecessor?) = core.registry.treeRead { db ->
        val node=core.requireNode(db,rootId,nodeId);val root=core.requireRoot(db,rootId)
        integrity(root.origin.storageGeneration==storageGeneration && root.origin.ownerCentralSessionId==WorkspaceIdentity.CENTRAL_SESSION &&
            node.assignmentId==assignmentId && node.botId==botId && node.cycleId==cycleId &&
            scopeIdentity=="native-workspace:${node.workspaceId}","Producer current identity changed")
        hash(profileSha256);val native=core.requireNativeAssignment(node)
        var currentCycle:String?=null;var revision=0L;var lastNodeRevision=0L
        var pendingWait:String?=null;var pendingControl:String?=null;var certificates=0
        db.query("bot_tree_event",null,"root_id=? AND node_id=? AND kind IN ('BIND','CHILD_WAIT_EXECUTION','USER_CONTROL','SUCCESSOR','CONTROL_SUCCESSOR')",
            arrayOf(rootId,nodeId),null,null,"node_revision,event_key").use { c ->
            while(c.moveToNext()) {
                val kind=c.s("kind");val eventRevision=c.positive("node_revision")
                integrity(eventRevision>lastNodeRevision,"Ambiguous or reordered producer chain")
                lastNodeRevision=eventRevision
                val receipt=c.s("receipt");integrity(c.s("payload_digest")==identity(receipt),"Producer event digest changed")
                when(kind) {
                    "BIND"->{
                        integrity(currentCycle==null && c.s("event_key")==identity("bind",nodeId,c.s("payload_ref")) &&
                            c.s("cycle_id")==identity("queued",assignmentId) && c.s("source_receipt")==node.sourceReceipt,"Initial binding changed")
                        val execution=one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(rootId,identity("first-execution",nodeId))) { e ->
                            integrity(e.s("kind")=="EXECUTION" && e.s("node_id")==nodeId && e.s("cycle_id")==c.s("cycle_id") &&
                                e.s("source_receipt")==node.sourceReceipt && e.s("payload_digest")==identity(e.s("receipt")),"Initial execution changed")
                            e.s("receipt") } ?: throw TreeIntegrityException("Missing original execution")
                        integrity(native.processId==execution,"Native original process changed")
                        revision=3L // Native PREPARED1 -> STARTED2 -> BOUND3; no historical adoption.
                        currentCycle=c.s("payload_ref")
                    }
                    "CHILD_WAIT_EXECUTION"->{
                        integrity(currentCycle!=null && c.s("cycle_id")==currentCycle && pendingWait==null && pendingControl==null,"Wait execution breaks producer chain")
                        val r=parseObject(receipt);requireKeys(r,setOf("intent","sourceRevision","admittedRevision","process","generation","token"))
                        integrity(integer(r,"sourceRevision")==revision && integer(r,"admittedRevision")==next(revision),"Wait assignment revision changed")
                        val waitId=requireNotNull(c.optional("wait_id"));val wait=WorkspaceTreeWaitStore(core).get(rootId,waitId)
                            ?: throw TreeIntegrityException("Missing claimed wait")
                        integrity(wait.parentNodeId==nodeId && wait.parentCycleId==currentCycle && wait.successorIntent==string(r,"intent") &&
                            wait.state in setOf(TreeWaitState.STARTED,TreeWaitState.ACKNOWLEDGED) &&
                            c.s("event_key")==identity("wait-execution",waitId),"Wait producer claim changed")
                        revision=next(revision);pendingWait=waitId
                        continue
                    }
                    "USER_CONTROL"->{
                        integrity(currentCycle!=null && c.s("cycle_id")==currentCycle && pendingWait==null && pendingControl==null,"Control breaks producer chain")
                        val r=parseObject(receipt);requireKeys(r,setOf("sourceRevision","admittedRevision","controlId","process","type","oldCycle"))
                        val id=string(r,"controlId");val control=core.registry.getControl(id) ?: throw TreeIntegrityException("Missing native control")
                        integrity(c.s("source_receipt")==id && string(r,"oldCycle")==currentCycle &&
                            integer(r,"sourceRevision")==revision && integer(r,"admittedRevision")==next(revision) &&
                            control.workspaceId==node.workspaceId && control.assignmentId==assignmentId && control.botId==botId &&
                            control.assignmentRevision==revision && control.admittedAssignmentRevision==next(revision) &&
                            control.processId==string(r,"process") && control.type.name==string(r,"type") &&
                            control.state in setOf(WorkspaceControlState.STARTED,WorkspaceControlState.COMPLETE),"Native control owner/revision changed")
                        revision=next(revision)
                        pendingControl=id
                        continue
                    }
                    "SUCCESSOR"->{
                        integrity(currentCycle!=null && c.s("cycle_id")==currentCycle && pendingWait!=null &&
                            c.optional("wait_id")==pendingWait && c.s("event_key")==identity("successor",pendingWait!!),"Wait successor chain changed")
                        currentCycle=c.s("payload_ref");pendingWait=null
                    }
                    "CONTROL_SUCCESSOR"->{
                        integrity(currentCycle!=null && c.s("cycle_id")==currentCycle && pendingControl!=null && c.s("source_receipt")==pendingControl,
                            "Control successor chain changed")
                        currentCycle=c.s("payload_ref");pendingControl=null
                    }
                }
                val certificate=WorkspaceTreeStore.requireSourceCertificate(receipt,scopeIdentity)
                integrity(certificate.getString("profileSha256")==profileSha256,"Producer profile changed")
                certificates++
            }
        }
        integrity(certificates>0 && currentCycle==cycleId && revision==node.assignmentRevision && revision==native.revision &&
            lastNodeRevision<node.revision,"Incomplete producer chain")
        if(predecessor==null) {
            integrity(pendingWait==null && pendingControl==null,"Current producer has an unbound claim")
        } else {
            hash(predecessor.checkpoint)
            val provided=WorkspaceTreeStore.requireSourceCertificate(predecessor.certificate,scopeIdentity)
            integrity(provided.getString("originCheckpointSha256")==predecessor.checkpoint &&
                provided.getString("profileSha256")==profileSha256,"Predecessor checkpoint/profile changed")
            val retained:String
            if(predecessor.waitId!=null) {
                integrity(pendingWait==predecessor.waitId && pendingControl==null,"Not the exact pending wait")
                val wait=WorkspaceTreeWaitStore(core).get(rootId,predecessor.waitId) ?: throw TreeIntegrityException("Missing pending wait")
                integrity(wait.state==TreeWaitState.STARTED && wait.parentNodeId==nodeId && wait.parentCycleId==cycleId &&
                    wait.parentRevision==node.revision && wait.successorIntent==node.controlIntent &&
                    wait.checkpointSha256==predecessor.checkpoint,"Pending wait source fence changed")
                retained=requireNotNull(wait.checkpointCertificate)
            } else {
                integrity(pendingControl==predecessor.controlId && pendingWait==null && node.controlIntent==predecessor.controlId,
                    "Not the exact pending control")
                retained=requireControlPredecessor(db,node,requireNotNull(predecessor.controlId))
            }
            val expected=WorkspaceTreeStore.requireSourceCertificate(retained,scopeIdentity)
            integrity(canonical(expected)==canonical(provided),"Retained predecessor certificate changed")
        }
    }


    private fun requireControlPredecessor(db:SQLiteDatabase,node:TreeNode,controlId:String):String {
        val control=core.registry.getControl(controlId) ?: throw TreeIntegrityException("Missing pending control")
        integrity(control.state==WorkspaceControlState.STARTED && control.workspaceId==node.workspaceId &&
            control.assignmentId==node.assignmentId && control.botId==node.botId &&
            control.admittedAssignmentRevision==node.assignmentRevision && node.controlIntent==controlId,
            "Pending control source owner changed")
        return one(db,"bot_tree_event","root_id=? AND event_key=?",arrayOf(node.rootId,identity("control-predecessor",controlId))) { c ->
            integrity(c.s("kind")=="CONTROL_PREDECESSOR" && c.s("node_id")==node.nodeId && c.s("cycle_id")==node.cycleId &&
                c.s("source_receipt")==controlId && c.s("payload_ref")==node.cycleId &&
                c.s("payload_digest")==identity(c.s("receipt")) && c.positive("node_revision")<node.revision,
                "Pending control predecessor source changed")
            c.s("receipt")
        } ?: throw TreeIntegrityException("Missing canonical control predecessor certificate")
    }


    companion object {
        /** ASCII JSON escapes preserve every UTF16 code unit including NUL and lone surrogates in SQLite TEXT. */
        internal fun canonical(value:Any?):String = when(value) {
            null,JSONObject.NULL->"null"
            is JSONObject->value.keys().asSequence().toList().sorted().joinToString(",","{","}") { quote(it)+":"+canonical(value.get(it)) }
            is JSONArray->(0 until value.length()).joinToString(",","[","]") { canonical(value.get(it)) }
            is String->quote(value)
            is Boolean->value.toString()
            is Int,is Long->value.toString()
            is Double->{ integrity(value.isFinite(),"Nonfinite JSON number");value.toString() }
            is Float->{ integrity(value.isFinite(),"Nonfinite JSON number");value.toString() }
            else->throw TreeIntegrityException("Unsupported JSON type")
        }
        private fun quote(value:String)="\""+value.map { "\\u%04x".format(it.code) }.joinToString("")+"\""
        internal fun parseObject(raw:String):JSONObject=try { JSONObject(raw) } catch(e:Exception) { throw TreeIntegrityException("Malformed evidence JSON") }
        internal fun string(o:JSONObject,key:String):String { val value=o.get(key);integrity(value is String,"Invalid text $key");return value as String }
        internal fun integer(o:JSONObject,key:String):Long { val value=o.get(key);integrity(value is Int || value is Long,"Invalid integer $key");return (value as Number).toLong() }
        internal fun requireKeys(o:JSONObject,keys:Set<String>) { integrity(o.keys().asSequence().toSet()==keys,"Evidence JSON shape changed") }
    }
}
