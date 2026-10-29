package com.jarvys.agent.chatdb

import java.util.UUID

/** Caller holds the same Room transaction as its actual entry update. Audit and history are distinct. */
internal object MemoryObservationHistory {
    const val BODY_UPDATE = "memory_update_body"
    const val METADATA_UPDATE = "memory_update_metadata"
    fun hasBodyOverride(database: JarvysChatDatabase,id: String): Boolean = database.chatSql.query(
        "SELECT 1 FROM memory_revisions WHERE target_type='memory' AND target_id=? AND actor IN ('user','agent') AND cause=? LIMIT 1",
        arrayOf(id,BODY_UPDATE)).use { it.moveToFirst() }
    fun revision(database: JarvysChatDatabase,id: String): Long = database.chatSql.query(
        "SELECT revision_count FROM memory_entries WHERE id=?",arrayOf(id)).use { check(it.moveToFirst()) { "memory_not_found" };it.getLong(0) }
    fun recordChange(database: JarvysChatDatabase,id: String,newText: String,newTitle: String?,actor: String,cause: String,
        now: Long,forceSnapshot: Boolean=false): Long = recordChangeRaw(database,id,newText,newTitle?.toByteArray(Charsets.UTF_8),actor,cause,now,forceSnapshot)
    fun recordChangeRaw(database: JarvysChatDatabase,id: String,newText: String,newTitle: ByteArray?,actor: String,cause: String,
        now: Long,forceSnapshot: Boolean=false): Long {
        val sql=database.chatSql
        val old=sql.query("SELECT text,CAST(title AS BLOB),revision_count FROM memory_entries WHERE id=? AND deleted_at IS NULL",arrayOf(id)).use {
            check(it.moveToFirst()) { "memory_not_found" }
            Triple(it.getString(0),if(it.isNull(1)) null else it.getBlob(1),it.getLong(2))
        }
        val version=if(forceSnapshot || old.first!=newText || !MemoryObservationContent.sameTopic(old.second,newTitle)) sql.query(
            "SELECT COALESCE(MAX(snapshot_version),0)+1 FROM memory_revisions WHERE target_type='memory' AND target_id=?",arrayOf(id)
        ).use { check(it.moveToFirst());it.getLong(0) } else null
        sql.execSQL("INSERT INTO memory_revisions(id,target_type,target_id,before_content,after_content,actor,cause,created_at,before_title,snapshot_version) VALUES(?,'memory',?,?,?,?,?,?,CAST(? AS TEXT),?)",
            arrayOf(UUID.randomUUID().toString(),id,old.first,newText,actor,cause,now,old.second,version))
        sql.execSQL("UPDATE memory_entries SET revision_count=revision_count+1,normalized_hash=? WHERE id=? AND deleted_at IS NULL",
            arrayOf(MemoryObservationContent.normalizedHash(newText),id))
        return old.third+1
    }
}
