package com.jarvys.agent.chatdb

import com.jarvys.agent.connectors.ConnectionStateCoordinator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Canonical data only. Never accepted as an executable permission. */
internal data class NativeRegenerationEvidence(val sessionId:String,val userMessageId:String,
    val assistantMessageId:String,val hiddenSeq:Long,val runId:String?,val storageGeneration:String,val sourceSha:String,val sourceKind:String,
    val targetStatus:String?,val explicitOwner:String?,val targetPosition:List<Long>,val userPosition:List<Long>)

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
    val sourceKind get()=evidence.sourceKind
    private val consumed=AtomicBoolean(false)
    private val consumedOwnerIssued=AtomicBoolean(false)
    @Volatile private var consumedBy:com.jarvys.agent.AgentForegroundService.CentralSubmissionOwner?=null
    internal fun requireLive() { check(!consumed.get() && live[opaqueId]===this) {"Native regeneration permission is no longer live"} }
    internal fun consume(owner:com.jarvys.agent.AgentForegroundService.CentralSubmissionOwner):ConsumedOwner {
        requireLive();owner.requireCurrent(sessionId)
        check(consumed.compareAndSet(false,true));check(live.remove(opaqueId,this))
        consumedBy=owner
        return ConsumedOwner.consumed(this,owner)
    }
    /** Exact consumed native owner, not a renewed action, tree capability, or replay permission. */
    class ConsumedOwner private constructor(private val receipt:NativeRegenerationReceipt,
        private val owner:com.jarvys.agent.AgentForegroundService.CentralSubmissionOwner) {
        val sessionId get()=receipt.sessionId
        val runId get()=owner.runId
        val userMessageId get()=receipt.userMessageId
        internal fun originalReceipt()=receipt
        internal fun requireOwner(current:com.jarvys.agent.AgentForegroundService.CentralSubmissionOwner) {
            check(owner.runId==current.runId && owner.tokenGeneration==current.tokenGeneration)
            owner.requireCurrent(sessionId);current.requireCurrent(sessionId)
        }
        fun requireTransactionOwner(token:com.jarvys.agent.CancellationToken) {
            owner.requireCurrent(sessionId,token)
        }
        fun requireCurrent(token:com.jarvys.agent.CancellationToken) {
            requireTransactionOwner(token)
            val fence=receipt.lifecycle.fenceIdentity();fence.lock()
            try {check(receipt.lifecycle.isCurrentPure()) {"Consumed regeneration lifecycle changed"}}
            finally {fence.unlock()}
        }
        companion object {
            internal fun consumed(receipt:NativeRegenerationReceipt,owner:com.jarvys.agent.AgentForegroundService.CentralSubmissionOwner):ConsumedOwner {
                owner.requireCurrent(receipt.sessionId)
                check(receipt.consumedBy===owner && receipt.consumed.get() && live[receipt.opaqueId]!==receipt && receipt.consumedOwnerIssued.compareAndSet(false,true))
                return ConsumedOwner(receipt,owner)
            }
        }
    }
    companion object {
        private val live=ConcurrentHashMap<String,NativeRegenerationReceipt>()
        internal fun issue(evidence:NativeRegenerationEvidence,lifecycle:ConnectionStateCoordinator.DestinationEvidence):NativeRegenerationReceipt =
            NativeRegenerationReceipt(evidence,lifecycle).also {live[it.opaqueId]=it}
        @JvmStatic fun lookupLive(id:String?):NativeRegenerationReceipt? = id?.let {live[it]}?.takeIf {!it.consumed.get()}
        @JvmStatic fun invalidate(receipt:NativeRegenerationReceipt?) { if(receipt!=null) {receipt.consumed.set(true);live.remove(receipt.opaqueId,receipt)} }
    }
}
