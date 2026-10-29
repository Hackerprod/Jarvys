package com.jarvys.agent.chatdb

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Narrow command codec; proof is emitted by Main before lossy argument retention. */
object MemorySessionCommands {
    const val SUMMARY = "memory_session_summary"
    const val FINALIZE = "memory_session_finalize"
    const val PREFIX = "memory-session-command-v1:"
    @JvmStatic fun isCommand(name: String) = name == SUMMARY || name == FINALIZE
    @JvmStatic fun content(name: String, args: Map<String, Any?>): String {
        require(isCommand(name)) { "memory_session_command_invalid" }
        val key=if(name==SUMMARY) "content" else "summary"
        require(args.keys.all { it==key }) { "memory_session_arguments_invalid" }
        val raw=args[key] as? String ?: throw IllegalArgumentException("$key is required")
        return MemoryObservationContent.prepareContent(raw).content.also {
            require(it.isNotEmpty()) { "memory_session_summary_empty" }
        }
    }
    @JvmStatic fun inputDigest(name:String, session:String, content:String):String {
        require(isCommand(name) && session.isNotBlank()) { "memory_session_identity_invalid" }
        val prepared=MemoryObservationContent.prepareContent(content).content
        require(prepared.isNotEmpty()) { "memory_session_summary_empty" }
        val digest=MessageDigest.getInstance("SHA-256")
        listOf("v1",name,session,prepared).forEach { value ->
            digest.update(1.toByte())
            digest.update(ByteBuffer.allocate(8).putLong(value.length.toLong()).array())
            value.forEach { unit -> digest.update((unit.code ushr 8).toByte()); digest.update(unit.code.toByte()) }
        }
        return PREFIX+digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun isInputDigest(value:String?) = value != null && value.startsWith(PREFIX) &&
        value.length==PREFIX.length+64 && value.substring(PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' }
    fun request(content:String, conversation:String, owner:String):MemorySaveRequest = MemorySaveRequest.parse(
        mapOf("content" to content,"title" to "Session summary: ${MemoryNamespace.CENTRAL}",
            "type" to "session_summary","scope" to "project","project" to MemoryNamespace.CENTRAL),conversation,owner)
}

data class MemorySessionFinalizeReceipt(val sessionId:String,val summaryId:String,
    val alreadyFinalized:Boolean,val historicalReference:Boolean) {
    fun toJson():String = org.json.JSONObject().put("session_id",sessionId).put("summary_id",summaryId)
        .put("already_finalized",alreadyFinalized).put("historical_reference",historicalReference).toString()
}
