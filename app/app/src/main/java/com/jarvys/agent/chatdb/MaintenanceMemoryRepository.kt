package com.jarvys.agent.chatdb

import java.time.Clock
import java.util.UUID

class MaintenanceMemoryBinding internal constructor(val generation:String,val jobId:String,val sessionId:String,
    val leaseOwner:String,val jobType:String,val sourceConversation:String?,private val workerActive:()->Boolean={true}) {
    private val observedDeletion=java.util.concurrent.atomic.AtomicReference<MaintenanceSessionDeletedException?>()
    internal fun recordDeletion(failure:MaintenanceSessionDeletedException) { observedDeletion.compareAndSet(null,failure) }
    internal fun withWorkerCheck(check:()->Boolean)=MaintenanceMemoryBinding(generation,jobId,sessionId,leaseOwner,jobType,sourceConversation,check)
    fun checkCancellation(token:com.jarvys.agent.CancellationToken) {
        if(!workerActive()) token.cancel();token.throwIfCancelled()
        observedDeletion.get()?.let { throw it }
    }
}
enum class MaintenanceClaimStatus { CLAIMED, NOT_CLAIMED, SESSION_DELETED }
data class MaintenanceClaimResult(val status:MaintenanceClaimStatus,val job:PersonalMemoryJob?=null,val binding:MaintenanceMemoryBinding?=null)
class MaintenanceSessionDeletedException:IllegalStateException("memory_job_session_deleted")

/** Existing worker claims only; no principal, manual, model selector, or scheduler authority. */
internal class MaintenanceMemoryRepository(private val database:JarvysChatDatabase,private val clock:Clock=Clock.systemUTC()) {
    private val sql get()=database.chatSql
    private fun existsSession(id:String)=sql.query("SELECT 1 FROM memory_sessions WHERE id=?",arrayOf(id)).use { it.moveToFirst() }
    private fun generation():String {
        var value=MemorySaveSourceIdentity.existingGeneration(database)
        if(value==null) {
            sql.execSQL("INSERT OR IGNORE INTO storage_meta(key,value) VALUES(?,?)",arrayOf(ConnectionProjectionRepository.GENERATION_KEY,UUID.randomUUID().toString()))
            value=MemorySaveSourceIdentity.existingGeneration(database)
        }
        return requireNotNull(value)
    }
    suspend fun claim(id:String,owner:String,now:Long,leaseUntil:Long,beforeClaim:()->Unit={}):MaintenanceClaimResult=database.withChatWriteTransaction {
        beforeClaim()
        require(owner.isNotBlank() && leaseUntil>now) { "memory_job_claim_invalid" }
        data class Job(val type:String,val source:String?,val attempts:Long,val state:String,val expires:Long,val available:Long,val session:String?,val error:String?,val owner:String?)
        val job=sql.query("SELECT job_type,source_conversation_id,attempts,state,lease_expires_at,available_at,memory_session_id,last_error,lease_owner FROM memory_jobs WHERE id=?",arrayOf(id)).use { c ->
            if(!c.moveToFirst()) null else Job(c.getString(0),if(c.isNull(1)) null else c.getString(1),c.getLong(2),c.getString(3),if(c.isNull(4)) 0 else c.getLong(4),c.getLong(5),if(c.isNull(6)) null else c.getString(6),if(c.isNull(7)) null else c.getString(7),if(c.isNull(8)) null else c.getString(8))
        } ?: return@withChatWriteTransaction MaintenanceClaimResult(MaintenanceClaimStatus.NOT_CLAIMED)
        check(job.type in setOf("extract","snapshot","narrate","daily_consolidate") &&
            (if(job.type=="daily_consolidate") job.source==null else job.source==MemoryNamespace.CENTRAL)) { "memory_job_scope_invalid" }
        if(job.state=="done" && job.error=="memory_job_session_deleted") return@withChatWriteTransaction MaintenanceClaimResult(MaintenanceClaimStatus.SESSION_DELETED)
        if(job.state=="done") return@withChatWriteTransaction MaintenanceClaimResult(MaintenanceClaimStatus.NOT_CLAIMED)
        check(job.state in setOf("queued","leased")) { "memory_job_state_invalid" }
        if(job.session!=null && !existsSession(job.session)) {
            sql.execSQL("UPDATE memory_jobs SET state='done',lease_owner=NULL,lease_expires_at=NULL,last_error='memory_job_session_deleted',updated_at=? WHERE id=? AND memory_session_id=? AND state=? AND lease_owner IS ?",arrayOf(now,id,job.session,job.state,job.owner))
            return@withChatWriteTransaction MaintenanceClaimResult(if(changed()) MaintenanceClaimStatus.SESSION_DELETED else MaintenanceClaimStatus.NOT_CLAIMED)
        }
        job.session?.let { old ->
            check(sql.query("SELECT 1 FROM memory_sessions WHERE id=? AND project=? AND ownership_mode='project_owned' AND source_generation=? AND maintenance_job_id=? AND maintenance_lease_owner IS NOT NULL" + (if(job.state=="leased") " AND maintenance_lease_owner=?" else ""),
                (listOf<Any?>(old,MemoryNamespace.CENTRAL,MemorySaveSourceIdentity.existingGeneration(database),id)+(if(job.state=="leased") listOf(job.owner) else emptyList())).toTypedArray()).use { it.moveToFirst() }) { "memory_job_binding_invalid" }
        }
        if(job.state=="leased" && job.expires>now || job.available>now) return@withChatWriteTransaction MaintenanceClaimResult(MaintenanceClaimStatus.NOT_CLAIMED)
        sql.execSQL("UPDATE memory_jobs SET state='leased',lease_owner=?,lease_expires_at=?,updated_at=? WHERE id=? AND (state='queued' OR (state='leased' AND lease_expires_at<=?)) AND available_at<=?",arrayOf(owner,leaseUntil,now,id,now,now))
        if(!changed()) return@withChatWriteTransaction MaintenanceClaimResult(MaintenanceClaimStatus.NOT_CLAIMED)
        job.session?.let { sql.execSQL("UPDATE memory_sessions SET state='incomplete' WHERE id=? AND ended_at IS NULL AND maintenance_job_id=?",arrayOf(it,id)) }
        val session=UUID.randomUUID().toString();val generation=generation()
        sql.execSQL("INSERT INTO memory_sessions(id,project,ownership_mode,state,started_at,source_generation,runtime_lease_expires_at,maintenance_job_id,maintenance_lease_owner) VALUES(? ,?,'project_owned','active',?,?,?,?,?)",arrayOf(session,MemoryNamespace.CENTRAL,now,generation,leaseUntil,id,owner))
        sql.execSQL("UPDATE memory_jobs SET memory_session_id=? WHERE id=? AND state='leased' AND lease_owner=?",arrayOf(session,id,owner))
        MaintenanceClaimResult(MaintenanceClaimStatus.CLAIMED,PersonalMemoryJob(id,job.type,job.source,job.attempts),MaintenanceMemoryBinding(generation,id,session,owner,job.type,job.source))
    }
    fun requireCurrent(binding:MaintenanceMemoryBinding,allowed:Set<String>) {
        check(binding.jobType in allowed && MemorySaveSourceIdentity.existingGeneration(database)==binding.generation) { "memory_job_binding_invalid" }
        check(sql.query("SELECT 1 FROM memory_jobs WHERE id=? AND state='leased' AND lease_owner=? AND lease_expires_at>? AND memory_session_id=? AND job_type=? AND source_conversation_id IS ?",
            arrayOf(binding.jobId,binding.leaseOwner,clock.millis(),binding.sessionId,binding.jobType,binding.sourceConversation)).use { it.moveToFirst() }) { "memory_job_lease_lost" }
        if(!existsSession(binding.sessionId)) throw MaintenanceSessionDeletedException()
        check(sql.query("SELECT 1 FROM memory_jobs j JOIN memory_sessions s ON s.id=j.memory_session_id WHERE j.id=? AND j.state='leased' AND j.lease_owner=? AND j.lease_expires_at>? AND j.memory_session_id=? AND j.job_type=? AND j.source_conversation_id IS ? AND s.project=? AND s.ownership_mode='project_owned' AND s.state='active' AND s.ended_at IS NULL AND s.source_generation=? AND s.maintenance_job_id=j.id AND s.maintenance_lease_owner=j.lease_owner",
            arrayOf(binding.jobId,binding.leaseOwner,clock.millis(),binding.sessionId,binding.jobType,binding.sourceConversation,MemoryNamespace.CENTRAL,binding.generation)).use { it.moveToFirst() }) { "memory_job_lease_lost" }
    }
    suspend fun complete(binding:MaintenanceMemoryBinding):Boolean=database.withChatWriteTransaction {
        requireCurrent(binding,setOf("extract","snapshot","narrate","daily_consolidate"))
        val now=clock.millis()
        sql.execSQL("UPDATE memory_jobs SET state='done',lease_owner=NULL,lease_expires_at=NULL,last_error=NULL,updated_at=? WHERE id=? AND state='leased' AND lease_owner=? AND memory_session_id=?",arrayOf(now,binding.jobId,binding.leaseOwner,binding.sessionId))
        check(changed()) { "memory_job_lease_lost" }
        sql.execSQL("UPDATE memory_sessions SET state='completed',ended_at=? WHERE id=?",arrayOf(now,binding.sessionId));true
    }
    suspend fun retry(binding:MaintenanceMemoryBinding,reason:String,availableAt:Long,closeSession:Boolean):Boolean=database.withChatWriteTransaction {
        check(MemorySaveSourceIdentity.existingGeneration(database)==binding.generation) { "memory_job_binding_invalid" }
        val now=clock.millis()
        sql.execSQL("UPDATE memory_jobs SET state='queued',lease_owner=NULL,lease_expires_at=NULL,attempts=attempts+1,last_error=?,available_at=?,updated_at=? WHERE id=? AND state='leased' AND lease_owner=? AND memory_session_id=?",arrayOf(reason.take(512),availableAt,now,binding.jobId,binding.leaseOwner,binding.sessionId))
        if(!changed()) return@withChatWriteTransaction false
        if(closeSession) sql.execSQL("UPDATE memory_sessions SET state=?,ended_at=? WHERE id=? AND state='active' AND ended_at IS NULL AND source_generation=? AND maintenance_job_id=? AND maintenance_lease_owner=?",arrayOf(if(reason=="cancelled") "cancelled" else "failed",now,binding.sessionId,binding.generation,binding.jobId,binding.leaseOwner))
        true
    }
    suspend fun deleted(binding:MaintenanceMemoryBinding):Boolean=database.withChatWriteTransaction {
        check(MemorySaveSourceIdentity.existingGeneration(database)==binding.generation) { "memory_job_binding_invalid" }
        if(existsSession(binding.sessionId)) return@withChatWriteTransaction false
        sql.execSQL("UPDATE memory_jobs SET state='done',lease_owner=NULL,lease_expires_at=NULL,last_error='memory_job_session_deleted',updated_at=? WHERE id=? AND state='leased' AND lease_owner=? AND memory_session_id=?",arrayOf(clock.millis(),binding.jobId,binding.leaseOwner,binding.sessionId));changed()
    }
    private fun changed()=sql.query("SELECT changes()").use { it.moveToFirst();it.getLong(0)==1L }
}
