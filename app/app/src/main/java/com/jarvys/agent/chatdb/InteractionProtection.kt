package com.jarvys.agent.chatdb

import android.database.Cursor
import com.jarvys.agent.ConversationTurn
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Request-local evidence, not a persisted interaction entity or an execution authority. */
data class InteractionProtectionSnapshot internal constructor(
    val conversationId: String,
    val fingerprint: String,
    val protectedSources: Set<ConversationTurn.Source>,
    val sourcePositions: Map<ConversationTurn.Source, InteractionSourcePosition>,
    val earliestProtected: InteractionSourcePosition?,
    val unsafeAnchor: Boolean,
) {
    fun protectedPrefixLimit(turns: List<ConversationTurn>, safeCompactions: Set<ConversationTurn.Source>): Int {
        if (unsafeAnchor) return 0
        val boundary = earliestProtected
        for ((index, turn) in turns.withIndex()) {
            val source = turn.source() ?: return index // Live/uncommitted material is never summarized.
            if (source.conversationId != conversationId) return index
            if (source.kind == ConversationTurn.Source.Kind.COMPACTION) {
                if (source !in safeCompactions) return index
                continue
            }
            val position = sourcePositions[source] ?: return index
            if (source in protectedSources || (boundary != null && position >= boundary)) return index
        }
        return turns.size
    }
}

data class InteractionSourcePosition(val sortSeq: Long, val sortSub: Int, val seq: Long) : Comparable<InteractionSourcePosition> {
    override fun compareTo(other: InteractionSourcePosition): Int = compareValuesBy(this, other,
        InteractionSourcePosition::sortSeq, InteractionSourcePosition::sortSub, InteractionSourcePosition::seq)
}

/** All methods run within the caller's existing read/write fence. Hidden rows classify only. */
internal class InteractionProtection(private val database: JarvysChatDatabase) {
    private val sql get() = database.chatSql
    private data class Group(val human: Boolean, val root: InteractionSourcePosition?,
        val sources: MutableSet<ConversationTurn.Source> = linkedSetOf(), var completed: Boolean = false,
        var uncertain: Boolean = false)
    fun read(conversation: String): InteractionProtectionSnapshot {
        val evidence = JSONArray()
        val central = conversation == com.jarvys.agent.workspaces.WorkspaceIdentity.CENTRAL_SESSION
        fun rows(table: String): List<Map<String, String?>> {
            val columns = when(table) {
                "conversation_items" -> if (central) "seq,sort_seq,sort_sub,item_kind,item_key,turn_anchor_seq"
                    else "seq,sort_seq,sort_sub,item_kind,item_key"
                "messages" -> if (central) "message_id,stable_id,seq,message_index,role,status,run_id,owner_user_message_id,hidden_kind,hidden_seq,input_state,input_state_seq,updated_at,proactive_decision_id,proactive_thread_key,proactive_reply_to_message_id,interrupt_request_id,ack_required,duration_ms,before_message_id,suggested_replies_consumed_seq,translation_hidden_seq"
                    else "message_id,seq,message_index,role,status,run_id,owner_user_message_id,hidden_kind,hidden_seq,input_state,input_state_seq,updated_at"
                "tool_batches" -> "batch_id,seq,owner_user_message_id,message_index,last_activity_seq,calls_decode_error"
                "tool_calls" -> "batch_id,call_id,ordinal,name,lifecycle,lifecycle_seq"
                "tool_results" -> "batch_id,call_id,seq,tool_name,lifecycle,file_artifact_id"
                "model_errors" -> "seq,owner_user_message_id,message_index,error_code"
                "tool_activities" -> "seq,owner_user_message_id,source_batch_id,call_id,tool_name,stage"
                "model_provider_outputs" -> "seq,owner_user_message_id,message_index"
                else -> error("Unsupported protection metadata table")
            }
            return sql.query("SELECT $columns FROM $table WHERE conversation_id=? ORDER BY rowid", arrayOf(conversation)).use { c ->
            buildList { while (c.moveToNext()) {
                val row = linkedMapOf<String,String?>(); val encoded = JSONArray().put(table)
                for (i in 0 until c.columnCount) {
                    val value = if(c.isNull(i)) null else c.getString(i)
                    row[c.getColumnName(i)] = value; encoded.put(value ?: JSONObject.NULL)
                }
                // Compaction may archive its own source evidence before committing. Artifact
                // ownership is validated separately; this row cannot classify an interaction.
                if (table != "conversation_items" || row["item_kind"] != "model_artifact") evidence.put(encoded)
                add(row)
            } }
        }
        }
        val items = rows("conversation_items").associateBy { it["seq"] }
        fun position(seq: String?): InteractionSourcePosition? = items[seq]?.let {
            InteractionSourcePosition(it["sort_seq"]?.toLongOrNull() ?: return@let null,
                it["sort_sub"]?.toIntOrNull() ?: return@let null, seq?.toLongOrNull() ?: return@let null)
        }
        // Match ConversationReader's timeline-only assistant-card rule. Membership is metadata,
        // not invented user ownership; fingerprint it so a part-kind change invalidates the fence.
        val missionCards = sql.query(
            "SELECT message_id,ordinal,kind FROM message_parts WHERE conversation_id=? AND kind=? ORDER BY message_id,ordinal",
            arrayOf(conversation,com.jarvys.agent.workspaces.MissionRef.PART_KIND),
        ).use { c -> buildSet {
            while(c.moveToNext()) {
                add(c.getString(0))
                evidence.put(JSONArray().put("mission_card_membership").put(c.getString(0)).put(c.getInt(1)).put(c.getString(2)))
            }
        } }
        val messages = rows("messages").filterNot { it["role"] == "assistant" && it["message_id"] in missionCards && (!central || it["proactive_decision_id"] == null) }
        val groups = linkedMapOf<String,Group>()
        var unsafe = false
        for (row in messages.filter { it["role"] in setOf("user", "agent", "system") }) {
            val id = row["message_id"] ?: continue
            groups[id] = Group(row["role"] == "user", position(row["seq"]))
        }
        val positions = linkedMapOf<ConversationTurn.Source,InteractionSourcePosition>()
        fun group(owner: String?): Group = groups[owner] ?: groups.getOrPut("orphan:${owner ?: "null"}") {
            unsafe = true; Group(false,null,uncertain=true)
        }
        fun bind(g: Group, source: ConversationTurn.Source, seq: String?) {
            g.sources += source
            val item = items[seq]
            val expectedKind = when(source.kind) {
                ConversationTurn.Source.Kind.MESSAGE -> "message"
                ConversationTurn.Source.Kind.BATCH -> "model_tool_calls"
                ConversationTurn.Source.Kind.BATCH_RESULT -> "model_tool_result"
                else -> "compaction"
            }
            val expectedKey = if(source.kind == ConversationTurn.Source.Kind.BATCH_RESULT)
                "v2|${source.sourceId.length}:${source.sourceId}:${source.callId.length}:${source.callId}" else source.sourceId
            val p = position(seq)?.takeIf { item?.get("item_kind") == expectedKind && item?.get("item_key") == expectedKey }
            if(p == null) { unsafe = true; g.uncertain = true } else positions[source] = p
        }
        // Native regeneration preserves the selected legacy response, including its absent
        // historical owner. A confirmed hide marker gives it an evidence-only uncertain group;
        // this never assigns an owner, exposes hidden text, or supplies execution authority.
        fun regeneratedOrphan(row:Map<String,String?>):Group? {
            if(row["role"]!="assistant" || row["owner_user_message_id"]!=null ||
                row["hidden_kind"]!="assistant_regenerated" || !NativeRegenerationSource.terminal(row["status"]))return null
            val id=row["message_id"] ?: return null
            val hidden=row["hidden_seq"]?.toLongOrNull() ?: return null
            val seq=row["seq"]?.toLongOrNull() ?: return null
            val marker=items[hidden.toString()]
            if(hidden<=seq || marker?.get("item_kind")!="assistant_regenerated" || marker?.get("item_key")!=id)return null
            val owner=NativeRegenerationSource.ownerId(database,conversation,id) ?: return null
            val user=messages.singleOrNull {it["message_id"]==owner && it["role"]=="user" && it["hidden_kind"]==null} ?: return null
            val targetPosition=NativeRegenerationSource.position(database,conversation,id) ?: return null
            val userPosition=NativeRegenerationSource.position(database,conversation,owner) ?: return null
            // Positive submissions require explicit owner evidence at native regeneration; an
            // orphan cannot be reclassified as retained history after losing that evidence.
            if(CentralTurnSubmissionRepository(database).list(conversation).any {it.userMessageId==owner})return null
            if(sql.query("SELECT 1 FROM message_parts WHERE conversation_id=? AND message_id=? AND kind IN (?,?) LIMIT 1",
                arrayOf(conversation,owner,CentralSubmissionRequest.PART_KIND,com.jarvys.agent.ConversationInput.PROVENANCE_PART_KIND)).use {it.moveToFirst()})return null
            val sourceDigest=captureCentralUserRootDigestInTransaction(database,conversation,owner)
            evidence.put(JSONArray().put("native_regenerated_orphan").put(id).put(hidden).put(owner)
                .put(sourceDigest).put(JSONArray(targetPosition)).put(JSONArray(userPosition)))
            return Group(false,position(user["seq"]),uncertain=true)
        }
        // Native notices/tasks are standalone assistant data, not missing USER interactions.
        // Recognize only the exact existing writer shape. The group keeps its real position and
        // remains nonhuman/uncertain/protected; this never grants P4 acceptance or execution.
        fun nativeStandaloneNotice(row:Map<String,String?>):Group? {
            if(!central || row["proactive_decision_id"]==null)return null
            val id=row["message_id"] ?: return null
            val args=arrayOf(conversation,id)
            val exact=mutableListOf<String?>("jarvys-native-standalone-notice-protection-v1",conversation)
            val metadata=sql.query("SELECT message_id,stable_id,seq,message_index,role,status,run_id,owner_user_message_id,duration_ms,before_message_id,"+
                "proactive_thread_key,proactive_reply_to_message_id,proactive_decision_id,interrupt_request_id,ack_required,input_state,input_state_seq,"+
                "hidden_kind,hidden_seq,suggested_replies_consumed_seq,translation_hidden_seq FROM messages WHERE conversation_id=? AND message_id=?",args).use { c ->
                if(!c.moveToFirst())return null
                if(listOf(0,1,4,5,12).any {c.getType(it)!=Cursor.FIELD_TYPE_STRING} ||
                    listOf(2,3,14).any {c.getType(it)!=Cursor.FIELD_TYPE_INTEGER})return null
                if(c.getString(0)!=id || c.getString(1)!=id ||
                    !runCatching {java.util.UUID.fromString(id).toString()==id}.getOrDefault(false) ||
                    c.getString(4)!="assistant" || c.getString(5)!="COMPLETED" || c.getString(12).isBlank() ||
                    c.getLong(2)<=0 || c.getLong(3)<0 || c.getLong(14)!=0L)return null
                if(listOf(6,7,8,9,11,13,15,16).any {!c.isNull(it)})return null
                // Historical native writes allow every nullable String. P4 admission separately
                // requires a nonblank thread; read classification does not mint that authority.
                if(!c.isNull(10) && c.getType(10)!=Cursor.FIELD_TYPE_STRING)return null
                if(!c.isNull(17) && c.getType(17)!=Cursor.FIELD_TYPE_STRING)return null
                if(listOf(18,19,20).any {!c.isNull(it) && c.getType(it)!=Cursor.FIELD_TYPE_INTEGER})return null
                val value=(0 until c.columnCount).map {if(c.isNull(it))null else c.getString(it)}
                if(c.moveToNext())return null
                exact.addAll(value);value
            }
            val seq=metadata[2]!!.toLong()
            val ownPosition=sql.query("SELECT seq,sort_seq,sort_sub,item_kind,item_key,turn_anchor_seq FROM conversation_items WHERE conversation_id=? AND seq=?",
                arrayOf(conversation,seq)).use { c ->
                if(!c.moveToFirst() || listOf(0,1,2).any {c.getType(it)!=Cursor.FIELD_TYPE_INTEGER} ||
                    listOf(3,4).any {c.getType(it)!=Cursor.FIELD_TYPE_STRING})return null
                val sub=c.getLong(2)
                if(c.getLong(0)!=seq || c.getLong(1)!=seq || sub<0 || sub>Int.MAX_VALUE ||
                    c.getString(3)!="message" || c.getString(4)!=id ||
                    !c.isNull(5) && c.getType(5)!=Cursor.FIELD_TYPE_INTEGER)return null
                if(!c.isNull(5)) {
                    val anchor=c.getLong(5)
                    if(anchor<=0 || anchor>=seq || !sql.query("SELECT 1 FROM messages WHERE conversation_id=? AND seq=? AND role='user' AND interrupt_request_id IS NULL",
                        arrayOf(conversation,anchor)).use {it.moveToFirst()})return null
                }
                exact.add("spine");for(i in 0 until c.columnCount)exact.add(if(c.isNull(i))null else c.getString(i))
                if(c.moveToNext())return null
                InteractionSourcePosition(seq,sub.toInt(),seq)
            }
            fun marker(value:String?,kind:String):Boolean {
                if(value==null)return false
                val markerSeq=value.toLongOrNull() ?: return false
                if(markerSeq<=seq)return false
                return sql.query("SELECT seq,sort_seq,sort_sub,item_kind,item_key FROM conversation_items WHERE conversation_id=? AND seq=?",
                    arrayOf(conversation,markerSeq)).use { c ->
                    c.moveToFirst() && listOf(0,1,2).all {c.getType(it)==Cursor.FIELD_TYPE_INTEGER} &&
                        listOf(3,4).all {c.getType(it)==Cursor.FIELD_TYPE_STRING} && c.getLong(0)==markerSeq &&
                        c.getLong(1)==markerSeq && c.getLong(2)==0L && c.getString(3)==kind && c.getString(4)==id && !c.moveToNext()
                }
            }
            if(metadata[17]==null) {if(metadata[18]!=null)return null}
            else if(metadata[17] !in setOf("assistant_deleted","assistant_regenerated") || !marker(metadata[18],metadata[17]!!))return null
            if(metadata[19]!=null && !marker(metadata[19],"proactive_reply_used"))return null
            if(metadata[20]!=null && !marker(metadata[20],"assistant_translation_hidden"))return null
            sql.query("SELECT ordinal,kind,payload FROM message_parts WHERE conversation_id=? AND message_id=? ORDER BY ordinal",args).use { c ->
                if(!c.moveToFirst() || c.getType(0)!=Cursor.FIELD_TYPE_INTEGER || c.getLong(0)!=0L ||
                    c.getType(1)!=Cursor.FIELD_TYPE_STRING || c.getString(1)!=MessagePartKinds.TEXT || c.getType(2)!=Cursor.FIELD_TYPE_STRING)return null
                exact.addAll(listOf("part",c.getLong(0).toString(),c.getString(1),c.getString(2)))
                if(c.moveToNext())return null // No provenance, request, mission-card or other protocol part.
            }
            if(sql.query("SELECT 1 FROM message_attachments WHERE conversation_id=? AND message_id=? LIMIT 1",args).use {it.moveToFirst()})return null
            sql.query("SELECT ordinal,label,text FROM proactive_suggested_replies WHERE conversation_id=? AND message_id=? ORDER BY ordinal",args).use { c ->
                var ordinal=0L
                while(c.moveToNext()) {
                    if(c.getType(0)!=Cursor.FIELD_TYPE_INTEGER || c.getLong(0)!=ordinal ||
                        c.getType(1)!=Cursor.FIELD_TYPE_STRING || c.getType(2)!=Cursor.FIELD_TYPE_STRING)return null
                    exact.addAll(listOf("option",ordinal.toString(),c.getString(1),c.getString(2)));ordinal++
                }
            }
            // Keep the existing outer projection format. Only this new evidence component uses
            // exact UTF-16 framing, so NUL/unpaired surrogates cannot collapse through UTF-8.
            evidence.put(JSONArray().put("native_standalone_notice").put(id)
                .put(ProactiveReplyAcceptance.digestFields(*exact.toTypedArray())))
            return Group(false,ownPosition,uncertain=true)
        }
        for (row in messages) {
            val id = row["message_id"]
            if (id == null) { unsafe=true; continue }
            val role = row["role"]
            val notice=nativeStandaloneNotice(row)
            if(central && row["proactive_decision_id"]!=null && notice==null)unsafe=true
            val g = notice?.also {groups["native-notice:$id"]=it}
                ?: regeneratedOrphan(row)?.also { groups["regenerated:$id"]=it }
                ?: group(if(role in setOf("user","agent","system")) id else row["owner_user_message_id"])
            bind(g,ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE,conversation,id,null),row["seq"])
            if(role == "assistant") {
                if(row["status"] == "COMPLETED" && row["hidden_kind"] == null) g.completed = true
                else g.uncertain = true
            } else if(role !in setOf("user","agent","system")) g.uncertain = true
            if(role in setOf("agent","system") && row["input_state"] != "resolved") g.uncertain=true
            if(row["hidden_kind"] != null || (role != "assistant" && row["status"] in setOf(
                "STOPPED","PARTIAL","FAILED","ERROR","MODEL_ERROR","TIMED_OUT","TIMEOUT","INTERRUPTED","ABORTED","CANCELLED"))) g.uncertain=true
        }
        val submissions=CentralTurnSubmissionRepository(database)
        for(submission in submissions.list(conversation)) {
            evidence.put(JSONArray().put("central_turn_submissions").put(submission.submissionId)
                .put(submission.userMessageId).put(submission.userSeq).put(submission.sourceSha256)
                .put(submission.storageGeneration).put(submission.originalAdmissionProcess)
                .put(submission.currentAdmissionProcess).put(submission.revision).put(submission.state)
                .put(submission.claimProcess ?: JSONObject.NULL).put(submission.claimRunId ?: JSONObject.NULL)
                .put(submission.claimTokenGeneration ?: JSONObject.NULL).put(submission.terminalEvidence ?: JSONObject.NULL)
                .put(submission.recoveryReceipt ?: JSONObject.NULL))
            if(!submissions.confirmedCompleted(submission)) group(submission.userMessageId).uncertain=true
        }
        val calls = rows("tool_calls").groupBy { it["batch_id"] }
        val results = rows("tool_results").groupBy { it["batch_id"] }
        val batches = rows("tool_batches")
        for (batch in batches) {
            val id = batch["batch_id"]
            if (id == null) { unsafe=true; continue }
            val g = group(batch["owner_user_message_id"])
            bind(g,ConversationTurn.Source(ConversationTurn.Source.Kind.BATCH,conversation,id,null),batch["seq"])
            if(batch["calls_decode_error"] != null) g.uncertain=true
            val cs = calls[id].orEmpty(); val rs = results[id].orEmpty()
            if(cs.isEmpty()) g.uncertain=true
            val lastKnown = (cs.mapNotNull { it["lifecycle_seq"]?.toLongOrNull() } +
                rs.mapNotNull { it["seq"]?.toLongOrNull() } + listOfNotNull(batch["seq"]?.toLongOrNull())).maxOrNull()
            if(batch["last_activity_seq"]?.toLongOrNull() == null || batch["last_activity_seq"]?.toLongOrNull() != lastKnown) g.uncertain=true
            for(call in cs) {
                val lifecycleItem = items[call["lifecycle_seq"]]
                val callId = call["call_id"].orEmpty()
                val callKey = "v2|${id.length}:$id:${callId.length}:$callId"
                if(lifecycleItem?.get("item_kind") != "model_tool_started" || lifecycleItem?.get("item_key") != callKey)
                    g.uncertain=true
                if(items.values.any { it["item_kind"] == "model_tool_not_started" && it["item_key"] == callKey }) g.uncertain=true
                val matches=rs.filter { it["call_id"] == call["call_id"] }
                if(matches.size != 1 || matches.single()["tool_name"] != call["name"] ||
                    matches.single()["lifecycle"] != "RESULT" || call["lifecycle"] != "STARTED" ||
                    (call["lifecycle_seq"]?.toLongOrNull() ?: Long.MAX_VALUE) >= (matches.single()["seq"]?.toLongOrNull() ?: Long.MIN_VALUE)) g.uncertain=true
            }
            for(result in rs) {
                val call=result["call_id"]
                if(call.isNullOrEmpty()) { unsafe=true; continue }
                if(cs.count { it["call_id"] == call } != 1) g.uncertain=true
                bind(g,ConversationTurn.Source(ConversationTurn.Source.Kind.BATCH_RESULT,conversation,id,call),result["seq"])
            }
        }
        if(calls.keys.any { id -> batches.none { it["batch_id"] == id } } ||
            results.keys.any { id -> batches.none { it["batch_id"] == id } }) unsafe=true
        for(error in rows("model_errors")) group(error["owner_user_message_id"]).uncertain=true
        val activities = rows("tool_activities")
        for (activity in activities) {
            val g = group(activity["owner_user_message_id"])
            val batch = batches.singleOrNull { it["batch_id"] == activity["source_batch_id"] }
            val call = calls[activity["source_batch_id"]].orEmpty().singleOrNull { it["call_id"] == activity["call_id"] }
            if (batch == null || batch["owner_user_message_id"] != activity["owner_user_message_id"] ||
                call == null || call["name"] != activity["tool_name"] ||
                activity["stage"] in setOf("tool_error","tool_interrupted","tool_not_started")) g.uncertain=true
        }
        for (output in rows("model_provider_outputs")) {
            val g=group(output["owner_user_message_id"])
            if(position(output["seq"]) == null) { unsafe=true;g.uncertain=true }
        }
        val closed = groups.values.filter { it.human && it.completed && !it.uncertain && it.root != null }
            .sortedByDescending { it.root }.take(10).toSet()
        val protected = groups.values.filter { !it.completed || it.uncertain || it in closed }
            .flatMap { it.sources }.toSet()
        val earliest = protected.mapNotNull { positions[it] }.minOrNull()
        val digest = MessageDigest.getInstance("SHA-256").digest(evidence.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return InteractionProtectionSnapshot(conversation,digest,java.util.Collections.unmodifiableSet(protected),
            java.util.Collections.unmodifiableMap(positions),earliest,unsafe)
    }
}
