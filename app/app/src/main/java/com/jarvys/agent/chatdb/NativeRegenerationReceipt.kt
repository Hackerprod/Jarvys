package com.jarvys.agent.chatdb

import com.jarvys.agent.connectors.ConnectionStateCoordinator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Canonical data only. Never accepted as an executable permission. */
internal data class NativeRegenerationEvidence(val sessionId:String,val userMessageId:String,
    val assistantMessageId:String,val hiddenSeq:Long,val runId:String,val storageGeneration:String,val sourceSha:String)

/** One process-live, one-use permission issued only after the native regeneration transaction commits. */
class NativeRegenerationReceipt private constructor(internal val evidence:NativeRegenerationEvidence,
    internal val lifecycle:ConnectionStateCoordinator.DestinationEvidence) {
    val opaqueId:String=UUID.randomUUID().toString()
    val sessionId get()=evidence.sessionId
    val userMessageId get()=evidence.userMessageId
    val assistantMessageId get()=evidence.assistantMessageId
    val hiddenSeq get()=evidence.hiddenSeq
    val storageGeneration get()=evidence.storageGeneration
    val sourceSha get()=evidence.sourceSha
    private val consumed=AtomicBoolean(false)
    internal fun requireLive() { check(!consumed.get() && live[opaqueId]===this) {"Native regeneration permission is no longer live"} }
    internal fun consume() { requireLive();check(consumed.compareAndSet(false,true));check(live.remove(opaqueId,this)) }
    companion object {
        private val live=ConcurrentHashMap<String,NativeRegenerationReceipt>()
        internal fun issue(evidence:NativeRegenerationEvidence,lifecycle:ConnectionStateCoordinator.DestinationEvidence):NativeRegenerationReceipt =
            NativeRegenerationReceipt(evidence,lifecycle).also {live[it.opaqueId]=it}
        @JvmStatic fun lookupLive(id:String?):NativeRegenerationReceipt? = id?.let {live[it]}?.takeIf {!it.consumed.get()}
        @JvmStatic fun invalidate(receipt:NativeRegenerationReceipt?) { if(receipt!=null) {receipt.consumed.set(true);live.remove(receipt.opaqueId,receipt)} }
    }
}
