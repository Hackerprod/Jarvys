package com.jarvys.agent.chatdb

import android.database.Cursor
import com.jarvys.agent.CancellationToken

/** Estimate only: confirmation authorizes this project, not a frozen list or CAS. */
data class MemoryProjectPrunePreview internal constructor(val project:String,val liveObservationCount:Long,
    val promptsToDelete:Long,val sessionsToDelete:Long,val sessionsRetained:Long)
data class MemoryProjectPruneReceipt(val project:String,val promptsDeleted:Long,val sessionsDeleted:Long,val sessionsRetained:Long)
class MemoryProjectPruneException(val code:String):IllegalStateException(code)

internal class MemoryProjectPruneRepository(private val database:JarvysChatDatabase) {
    private val sql get()=database.chatSql
    private fun ensure(value:Boolean,code:String) { if(!value)throw MemoryProjectPruneException(code) }
    private fun count(query:String,project:String):Long=sql.query(query,arrayOf(project)).use { c ->
        ensure(c.moveToFirst() && c.getType(0)==Cursor.FIELD_TYPE_INTEGER,"memory_prune_readback_failed")
        c.getLong(0).also{ensure(it>=0,"memory_prune_readback_failed")}
    }
    private val noObs="NOT EXISTS(SELECT 1 FROM memory_entries e WHERE e.memory_session_id=s.id)"
    private val noPrompts="NOT EXISTS(SELECT 1 FROM memory_prompts p WHERE p.session_id=s.id)"
    private fun estimate(project:String):MemoryProjectPrunePreview {
        val live=count("SELECT COUNT(*) FROM memory_entries WHERE project=? AND deleted_at IS NULL",project)
        val prompts=count("SELECT COUNT(*) FROM memory_prompts WHERE project=?",project)
        // Selected prompts will be purged; only remaining prompts retain a session in preview.
        val eligible=count("SELECT COUNT(*) FROM memory_sessions s WHERE s.project=? AND $noObs AND NOT EXISTS(SELECT 1 FROM memory_prompts p WHERE p.session_id=s.id AND p.project<>s.project)",project)
        val total=count("SELECT COUNT(*) FROM memory_sessions WHERE project=?",project)
        return MemoryProjectPrunePreview(project,live,prompts,eligible,total-eligible)
    }
    suspend fun preview(project:String,token:CancellationToken,verify:()->Unit):MemoryProjectPrunePreview=database.withChatReadTransaction {
        verify();val result=estimate(project);token.throwIfCancelled();verify();result
    }
    suspend fun apply(project:String,token:CancellationToken,verify:()->Unit):MemoryProjectPruneReceipt=database.withChatWriteTransaction {
        verify()
        ensure(count("SELECT COUNT(*) FROM memory_entries WHERE project=? AND deleted_at IS NULL",project)==0L,"memory_prune_live_observations_present")
        val now=System.currentTimeMillis();var prompts=0L;var sessions=0L
        val sessionRepo=MemorySessionRepository(database)
        // Bounded metadata pages, no prompt payload or foreign reference hydration.
        while(true) {
            val ids=sql.query("SELECT id FROM memory_prompts WHERE project=? ORDER BY id LIMIT 64",arrayOf(project)).use { c ->buildList {
                while(c.moveToNext()) { ensure(c.getType(0)==Cursor.FIELD_TYPE_STRING,"memory_prune_readback_failed");add(c.getString(0)) }
            } }
            if(ids.isEmpty())break
            for(id in ids) {
                verify()
                val prior=sql.query("SELECT typeof(deleted_at),typeof(deleted_at_submillis_nanos),CASE WHEN typeof(deleted_at)='integer' THEN deleted_at END,CASE WHEN typeof(deleted_at_submillis_nanos)='integer' THEN deleted_at_submillis_nanos END FROM memory_prompt_tombstones WHERE prompt_id=?",arrayOf(id)).use { c ->
                    if(!c.moveToFirst())null else {
                        ensure(c.getString(0)=="integer" && c.getString(1)=="integer" && c.getLong(3) in 0L..999999L,"memory_prune_prompt_marker_corrupt")
                        c.getLong(2) to c.getLong(3)
                    }
                }
                sessionRepo.purgePrompt(id,now)
                ensure(sql.query("SELECT changes()").use{it.moveToFirst();it.getLong(0)==1L},"memory_prune_readback_failed")
                ensure(!sql.query("SELECT 1 FROM memory_prompts WHERE id=?",arrayOf(id)).use{it.moveToFirst()},"memory_prune_readback_failed")
                ensure(sql.query("SELECT 1 FROM memory_prompt_tombstones WHERE prompt_id=? AND project=? AND typeof(deleted_at)='integer' AND deleted_at=? AND typeof(deleted_at_submillis_nanos)='integer' AND deleted_at_submillis_nanos=?",arrayOf(id,project,prior?.first?:now,prior?.second?:0L)).use{it.moveToFirst()},"memory_prune_readback_failed")
                prompts=Math.addExact(prompts,1L);token.throwIfCancelled()
            }
        }
        while(true) {
            val ids=sql.query("SELECT s.id FROM memory_sessions s WHERE s.project=? AND $noObs AND $noPrompts ORDER BY s.id LIMIT 64",arrayOf(project)).use { c ->buildList {
                while(c.moveToNext()){ensure(c.getType(0)==Cursor.FIELD_TYPE_STRING,"memory_prune_readback_failed");add(c.getString(0))}
            } }
            if(ids.isEmpty())break
            for(id in ids) {
                verify()
                val prior=sql.query("SELECT typeof(project),project IS ?,typeof(deleted_at),typeof(deleted_at_submillis_nanos),deleted_at_submillis_nanos BETWEEN 0 AND 999999,CASE WHEN typeof(deleted_at)='integer' THEN deleted_at END,CASE WHEN typeof(deleted_at_submillis_nanos)='integer' THEN deleted_at_submillis_nanos END FROM memory_session_tombstones WHERE id=?",arrayOf(project,id)).use { c ->
                    if(!c.moveToFirst())null else {
                        ensure(c.getString(0)=="text" && c.getLong(1)==1L,"memory_prune_session_marker_identity_conflict")
                        ensure(c.getString(2)=="integer" && c.getString(3)=="integer" && c.getLong(4)==1L,"memory_prune_session_marker_corrupt")
                        c.getLong(5) to c.getLong(6)
                    }
                }
                if(prior==null)sql.execSQL("INSERT INTO memory_session_tombstones(id,project,deleted_at) VALUES(?,?,?)",arrayOf(id,project,now))
                val expectedTime=prior?.first?:now;val expectedNanos=prior?.second?:0L
                fun markerReadback() { ensure(sql.query("SELECT 1 FROM memory_session_tombstones WHERE id=? AND project=? AND typeof(project)='text' AND typeof(deleted_at)='integer' AND deleted_at=? AND typeof(deleted_at_submillis_nanos)='integer' AND deleted_at_submillis_nanos=?",arrayOf(id,project,expectedTime,expectedNanos)).use{it.moveToFirst()},"memory_prune_readback_failed") }
                markerReadback()
                sql.execSQL("DELETE FROM memory_sessions WHERE id=? AND project=? AND NOT EXISTS(SELECT 1 FROM memory_entries WHERE memory_session_id=?) AND NOT EXISTS(SELECT 1 FROM memory_prompts WHERE session_id=?)",arrayOf(id,project,id,id))
                ensure(sql.query("SELECT changes()").use{it.moveToFirst();it.getLong(0)==1L},"memory_prune_readback_failed")
                ensure(!sql.query("SELECT 1 FROM memory_sessions WHERE id=?",arrayOf(id)).use{it.moveToFirst()},"memory_prune_readback_failed")
                markerReadback()
                sessions=Math.addExact(sessions,1L);token.throwIfCancelled()
            }
        }
        ensure(count("SELECT COUNT(*) FROM memory_prompts WHERE project=?",project)==0L,"memory_prune_readback_failed")
        ensure(count("SELECT COUNT(*) FROM memory_sessions s WHERE s.project=? AND $noObs AND $noPrompts",project)==0L,"memory_prune_readback_failed")
        val retained=count("SELECT COUNT(*) FROM memory_sessions WHERE project=?",project)
        verify();MemoryProjectPruneReceipt(project,prompts,sessions,retained)
    }
}
