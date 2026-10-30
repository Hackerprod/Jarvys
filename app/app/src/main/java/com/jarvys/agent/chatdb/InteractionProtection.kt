package com.jarvys.agent.chatdb

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
        fun rows(table: String): List<Map<String, String?>> {
            val columns = when(table) {
                "conversation_items" -> "seq,sort_seq,sort_sub,item_kind,item_key"
                "messages" -> "message_id,seq,message_index,role,status,run_id,owner_user_message_id,hidden_kind,hidden_seq,input_state,input_state_seq,updated_at"
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
        val messages = rows("messages").filterNot { it["role"] == "assistant" && it["message_id"] in missionCards }
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
        for (row in messages) {
            val id = row["message_id"]
            if (id == null) { unsafe=true; continue }
            val role = row["role"]
            val g = group(if(role in setOf("user","agent","system")) id else row["owner_user_message_id"])
            bind(g,ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE,conversation,id,null),row["seq"])
            if(role == "assistant") {
                if(row["status"] == "COMPLETED" && row["hidden_kind"] == null) g.completed = true
                else g.uncertain = true
            } else if(role !in setOf("user","agent","system")) g.uncertain = true
            if(role in setOf("agent","system") && row["input_state"] != "resolved") g.uncertain=true
            if(row["hidden_kind"] != null || (role != "assistant" && row["status"] in setOf(
                "STOPPED","PARTIAL","FAILED","ERROR","MODEL_ERROR","TIMED_OUT","TIMEOUT","INTERRUPTED","ABORTED","CANCELLED"))) g.uncertain=true
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
