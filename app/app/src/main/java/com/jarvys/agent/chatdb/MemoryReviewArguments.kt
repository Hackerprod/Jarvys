package com.jarvys.agent.chatdb

import java.math.BigDecimal
import java.util.UUID

/** Deliberate local UUID/raw-selector adapters; never infer a project from coerced content. */
object MemoryReviewArguments {
    @JvmStatic fun action(args:Map<String,Any?>):String {
        require(args["action"] is String) { "memory_review_action_invalid" }
        return MemoryObservationContent.trimSpace(args["action"] as String).also {
            require(it=="list" || it=="mark_reviewed") { "memory_review_action_invalid" }
        }
    }
    @JvmStatic fun limit(raw:Any?):Long {
        if(raw !is Number) return 10
        val value=try { BigDecimal(raw.toString()) } catch (_:NumberFormatException) { throw IllegalArgumentException("memory_review_limit_invalid") }
        require(value>=BigDecimal.valueOf(Long.MIN_VALUE) && value<=BigDecimal.valueOf(Long.MAX_VALUE)) { "memory_review_limit_invalid" }
        return value.toBigInteger().toLong().let { if(it<=0) 20 else it }
    }
    @JvmStatic fun id(args:Map<String,Any?>):String {
        val primary=(args["observation_id"] as? String)?.let(MemoryObservationContent::trimSpace).orEmpty()
        val value=primary.ifEmpty { (args["id"] as? String)?.let(MemoryObservationContent::trimSpace).orEmpty() }
        require(value.isNotEmpty()) { "memory_review_id_required" }
        val uuid=try { UUID.fromString(value) } catch (_:IllegalArgumentException) { throw IllegalArgumentException("memory_review_id_invalid") }
        require(uuid.toString()==value) { "memory_review_id_invalid" }
        return value
    }
    @JvmStatic fun validate(args:Map<String,Any?>) {
        val action=action(args)
        for(key in listOf("project","observation_id","id")) if(args.containsKey(key)) require(args[key] is String) { "memory_review_${key}_invalid" }
        if(action=="mark_reviewed") id(args) else limit(args["limit"])
    }
    @JvmStatic fun prepare(args:Map<String,Any?>):Map<String,Any?> {
        validate(args)
        return LinkedHashMap(args).also { it["action"]=action(args); if(action(args)=="list") it["limit"]=limit(args["limit"]) }
    }
    const val PROOF_PREFIX="memory-review-v1:"
    @JvmStatic fun checkpointDigest(args:Map<String,Any?>,session:String):String? {
        validate(args)
        if(action(args)!="mark_reviewed") return null
        val project=(args["project"] as? String)?.let(MemoryObservationContent::trimSpace).orEmpty()
        return selectorDigest(session,id(args),if(project.isEmpty()) MemoryNamespace.CENTRAL else MemoryNamespace.normalizeProject(project))
    }
    @JvmStatic fun selectorDigest(session:String,id:String,project:String):String {
        require(session.isNotBlank() && id.isNotBlank() && project.isNotBlank()) { "memory_review_source_invalid" }
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        listOf("v1","memory_review","mark_reviewed",session,id,project).forEach { field ->
            digest.update(1.toByte())
            digest.update(java.nio.ByteBuffer.allocate(8).putLong(field.length.toLong()).array())
            field.forEach { unit -> digest.update((unit.code ushr 8).toByte());digest.update(unit.code.toByte()) }
        }
        return PROOF_PREFIX+digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    @JvmStatic fun isInputDigest(value:String?):Boolean = value!=null && value.startsWith(PROOF_PREFIX) &&
        value.length==PROOF_PREFIX.length+64 && value.substring(PROOF_PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' }

}
