package com.jarvys.agent.chatdb

import java.time.Clock
import java.util.UUID
import com.jarvys.agent.CancellationToken

/** Acknowledged preparation execution; neither coverage nor model output can manufacture it. */
class CompactionMemoryBinding internal constructor(val id:String,val parent:MemorySessionBinding,coverage:CompactionCoverage?=null) {
    private val coverageSlot=java.util.concurrent.atomic.AtomicReference<CompactionCoverage?>(coverage)
    val acknowledgedCoverage:CompactionCoverage? get()=coverageSlot.get()
    internal fun acknowledge(before:CompactionCoverage?,after:CompactionCoverage?) {
        check(coverageSlot.compareAndSet(before,after)) { "memory_compaction_ack_changed" }
    }
    private val failure=java.util.concurrent.atomic.AtomicReference<RuntimeException?>()
    internal fun recordFailure(value:RuntimeException) { failure.compareAndSet(null,value) }
    fun check(token:CancellationToken) { token.throwIfCancelled();failure.get()?.let { throw it } }
}

internal class CompactionExecutionRepository(private val database:JarvysChatDatabase,
    private val clock:Clock=Clock.systemUTC()) {
    private val sql get()=database.chatSql
    private fun expiry()=Math.addExact(clock.millis(),30L*60L*1000L)
    suspend fun begin(parent:MemorySessionBinding,coverage:CompactionCoverage?=null):CompactionMemoryBinding=database.withChatWriteTransaction {
        requireParent(parent)
        check(parent.project==MemoryNamespace.CENTRAL && parent.ownerConversation==MemoryNamespace.CENTRAL) { "memory_compaction_parent_invalid" }
        if(coverage!=null) {
            check(coverage.conversationId==parent.ownerConversation && CompactionMemoryRepository(database).prepare(coverage.conversationId,coverage.sources)==coverage) { "memory_compaction_source_changed" }
        }
        val id=UUID.randomUUID().toString()
        sql.execSQL("INSERT INTO memory_sessions(id,project,ownership_mode,state,started_at,source_generation,owner_conversation_id,owner_message_id,run_id,process_id,runtime_lease_expires_at,compaction_parent_session_id) VALUES(?,?,'project_owned','active',?,?,?,?,?,?,?,?)",
            arrayOf(id,parent.project,clock.millis(),parent.sourceGeneration,parent.ownerConversation,parent.ownerMessageId,parent.runId,parent.processId,expiry(),parent.id))
        CompactionMemoryBinding(id,parent,coverage)
    }
    private fun requireParent(p:MemorySessionBinding) {
        check(MemorySaveSourceIdentity.existingGeneration(database)==p.sourceGeneration && p.project==MemoryNamespace.CENTRAL && p.ownerConversation==MemoryNamespace.CENTRAL) { "memory_compaction_source_invalid" }
        val present=sql.query("SELECT 1 FROM memory_sessions WHERE id=?",arrayOf(p.id)).use { it.moveToFirst() }
        if(!present) throw MemorySessionUnavailableException()
        check(sql.query("SELECT 1 FROM memory_sessions WHERE id=? AND project=? AND ownership_mode='shared' AND source_generation=? AND owner_conversation_id=? AND owner_message_id=? AND run_id=? AND process_id=?",
            arrayOf(p.id,p.project,p.sourceGeneration,p.ownerConversation,p.ownerMessageId,p.runId,p.processId)).use { it.moveToFirst() }) { "memory_compaction_source_invalid" }
        val state=sql.query("SELECT state FROM memory_sessions WHERE id=?",arrayOf(p.id)).use { it.moveToFirst();it.getString(0) }
        check(state in setOf("active","completed","cancelled","failed","incomplete")) { "memory_compaction_source_invalid" }
        MemorySessionRepository(database,clock).requirePassiveCurrent(p)
    }
    fun requireCurrent(binding:CompactionMemoryBinding) {
        requireParent(binding.parent)
        val p=binding.parent
        if(!sql.query("SELECT 1 FROM memory_sessions WHERE id=?",arrayOf(binding.id)).use { it.moveToFirst() }) throw MemorySessionUnavailableException()
        check(sql.query("SELECT 1 FROM memory_sessions WHERE id=? AND project=? AND ownership_mode='project_owned' AND source_generation=? AND owner_conversation_id=? AND owner_message_id=? AND run_id=? AND process_id=? AND compaction_parent_session_id=? AND maintenance_job_id IS NULL AND maintenance_lease_owner IS NULL",
            arrayOf(binding.id,p.project,p.sourceGeneration,p.ownerConversation,p.ownerMessageId,p.runId,p.processId,p.id)).use { it.moveToFirst() }) { "memory_compaction_source_invalid" }
        val state=sql.query("SELECT state FROM memory_sessions WHERE id=?",arrayOf(binding.id)).use { it.moveToFirst();it.getString(0) }
        check(state in setOf("active","completed","cancelled","failed","incomplete")) { "memory_compaction_source_invalid" }
        if(!sql.query("SELECT 1 FROM memory_sessions WHERE id=? AND state='active' AND ended_at IS NULL AND runtime_lease_expires_at>?",arrayOf(binding.id,clock.millis())).use { it.moveToFirst() }) throw MemorySessionUnavailableException()
    }
    fun changes():Long=sql.query("SELECT total_changes()").use { it.moveToFirst();it.getLong(0) }
    fun renew(binding:CompactionMemoryBinding) {
        requireCurrent(binding)
        MemorySessionRepository(database,clock).renewCurrent(binding.parent)
        sql.execSQL("UPDATE memory_sessions SET runtime_lease_expires_at=? WHERE id=?",arrayOf(expiry(),binding.id))
    }
    /** Outcome only: no parent liveness requirement, no content write, no terminal downgrade. */
    suspend fun finish(binding:CompactionMemoryBinding,outcome:String):Boolean=database.withChatWriteTransaction {
        require(outcome in setOf("completed","incomplete","cancelled")) { "memory_compaction_outcome_invalid" }
        val p=binding.parent
        check(MemorySaveSourceIdentity.existingGeneration(database)==p.sourceGeneration) { "memory_compaction_generation_changed" }
        sql.execSQL("UPDATE memory_sessions SET state=?,ended_at=? WHERE id=? AND project=? AND ownership_mode='project_owned' AND state='active' AND ended_at IS NULL AND source_generation=? AND owner_conversation_id=? AND owner_message_id=? AND run_id=? AND process_id=? AND compaction_parent_session_id=?",
            arrayOf(outcome,clock.millis(),binding.id,p.project,p.sourceGeneration,p.ownerConversation,p.ownerMessageId,p.runId,p.processId,p.id))
        sql.query("SELECT changes()").use { it.moveToFirst();it.getLong(0)==1L }
    }
}
