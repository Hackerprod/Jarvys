package com.jarvys.agent.chatdb

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Pure prepared semantics, not an execution grant. Main independently certifies this before retention. */
data class MemoryJudgmentItem internal constructor(val index:Int,val judgmentId:String?=null,
    val sourceId:String?=null,val targetId:String?=null,val relation:String?=null,val reason:String?=null,
    val evidence:String?=null,val confidence:Double?=null,val model:String?=null,val error:String?=null)
class MemoryJudgmentRequest internal constructor(val toolName:String,val sessionAssertion:String?,items:List<MemoryJudgmentItem>) {
    val items:List<MemoryJudgmentItem> = java.util.Collections.unmodifiableList(ArrayList(items))
}

object MemoryJudgmentArguments {
    const val PROOF_PREFIX="memory-judgment-v1:"
    val verbs:Set<String> = setOf("related","compatible","scoped","conflicts_with","supersedes","not_conflict")
    @JvmStatic fun isTool(name:String)=name in setOf("memory_judge","memory_compare","memory_judge_many")
    private fun id(raw:Any?):String {
        require(raw is String) { "memory_judgment_id_invalid" }
        val value=MemoryObservationContent.trimSpace(raw)
        val parsed=try { UUID.fromString(value) } catch(_:IllegalArgumentException) { throw IllegalArgumentException("memory_judgment_id_invalid") }
        require(parsed.toString()==value) { "memory_judgment_id_invalid" }; return value
    }
    private fun optional(args:Map<*,*>,key:String):String? {
        if(!args.containsKey(key)) return null
        val raw=args[key]; require(raw is String) { "memory_judgment_${key}_invalid" }
        return MemoryObservationContent.prepareTitle(raw).takeIf { it.isNotEmpty() }
    }
    private fun confidence(args:Map<*,*>,required:Boolean):Double? {
        if(!args.containsKey("confidence")) { require(!required) { "memory_judgment_confidence_required" }; return null }
        val raw=args["confidence"]; require(raw is Number) { "memory_judgment_confidence_invalid" }
        val exact=try { BigDecimal(raw.toString()) } catch(_:NumberFormatException) { throw IllegalArgumentException("memory_judgment_confidence_invalid") }
        require(exact>=BigDecimal.ZERO && exact<=BigDecimal.ONE) { "memory_judgment_confidence_invalid" }
        return raw.toDouble().also { require(it.isFinite()) { "memory_judgment_confidence_invalid" } }
    }
    private fun verb(raw:Any?):String {
        require(raw is String) { "memory_judgment_relation_invalid" }
        return MemoryObservationContent.trimSpace(raw).also { require(it in verbs) { "memory_judgment_relation_invalid" } }
    }
    private fun judge(args:Map<*,*>,index:Int)=MemoryJudgmentItem(index,judgmentId=id(args["judgment_id"]),relation=verb(args["relation"]),
        reason=optional(args,"reason"),evidence=optional(args,"evidence"),confidence=confidence(args,false))
    @JvmStatic fun prepare(tool:String,args:Map<String,Any?>):MemoryJudgmentRequest {
        require(isTool(tool)) { "memory_judgment_tool_invalid" }
        val session=if(args.containsKey("session_id")) {
            require(args["session_id"] is String) { "memory_judgment_session_invalid" };args["session_id"] as String
        } else null
        val items=when(tool) {
            "memory_judge" -> listOf(judge(args,0))
            "memory_compare" -> {
                val reason=optional(args,"reasoning"); require(!reason.isNullOrEmpty()) { "memory_judgment_reasoning_required" }
                listOf(MemoryJudgmentItem(0,sourceId=id(args["memory_id_a"]),targetId=id(args["memory_id_b"]),
                    relation=verb(args["relation"]),reason=reason,confidence=confidence(args,true),model=optional(args,"model")))
            }
            else -> {
                val raw=args["judgments"]; require(raw is List<*> && raw.size in 1..32) { "memory_judgment_batch_invalid" }
                raw.mapIndexed { index,item ->
                    try { require(item is Map<*,*>) { "memory_judgment_item_invalid" }; judge(item,index) }
                    catch(failure:IllegalArgumentException) {
                        val validId=try { id((item as? Map<*,*>)?.get("judgment_id")) } catch(_:IllegalArgumentException) { null }
                        MemoryJudgmentItem(index,judgmentId=validId,error=failure.message ?: "memory_judgment_item_invalid")
                    }
                }
            }
        }
        return MemoryJudgmentRequest(tool,session,items)
    }
    /** Envelope validation only; batch item errors remain real ordered outcomes. No generic coercion. */
    @JvmStatic fun validate(tool:String,args:Map<String,Any?>) { prepare(tool,args) }
    @JvmStatic fun tree(request:MemoryJudgmentRequest,session:String):List<Any?> = listOf("v1",request.toolName,session,request.sessionAssertion,
        request.items.map { item -> when {
            item.error!=null -> listOf("invalid",item.index.toString(),item.judgmentId,item.error)
            request.toolName=="memory_compare" -> listOf("compare","0",item.sourceId,item.targetId,item.relation,item.reason,
                java.lang.Double.toHexString(requireNotNull(item.confidence)),item.model)
            else -> listOf("judge",item.index.toString(),item.judgmentId,item.relation,item.reason,item.evidence,
                item.confidence?.let(java.lang.Double::toHexString))
        } })
    @JvmStatic fun digestTree(value:List<Any?>):String {
        val digest=MessageDigest.getInstance("SHA-256")
        fun count(value:Long) { digest.update(ByteBuffer.allocate(8).putLong(value).array()) }
        fun encode(value:Any?) {
            when(value) {
                null -> digest.update(0.toByte())
                is String -> { digest.update(1.toByte());count(value.length.toLong());value.forEach { c -> digest.update((c.code ushr 8).toByte());digest.update(c.code.toByte()) } }
                is List<*> -> { digest.update(2.toByte());count(value.size.toLong());value.forEach(::encode) }
                else -> error("memory_judgment_proof_type_invalid")
            }
        }
        encode(value)
        return PROOF_PREFIX+digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    @JvmStatic fun checkpointDigest(tool:String,args:Map<String,Any?>,session:String)=digestTree(tree(prepare(tool,args),session))
    @JvmStatic fun isInputDigest(value:String?)=value!=null && value.startsWith(PROOF_PREFIX) && value.length==PROOF_PREFIX.length+64 &&
        value.substring(PROOF_PREFIX.length).all { it in '0'..'9'||it in 'a'..'f' }
}
