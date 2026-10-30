package com.jarvys.agent.chatdb

import com.jarvys.agent.CancellationToken
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Only state affected by the existing owned observation purge/review primitives, never arbitrary table access. */
internal object MemoryImportImpact {
    fun observation(sql:ChatSqlDatabase,id:String,purge:Boolean,token:CancellationToken):String {
        val digest=MessageDigest.getInstance("SHA-256")
        fun number(value:Long){digest.update(ByteBuffer.allocate(8).putLong(value).array())}
        fun string(value:String){val bytes=value.toByteArray(Charsets.UTF_8);number(bytes.size.toLong());digest.update(bytes)}
        val domains=linkedMapOf("memory_review_clear_events" to Triple("observation_id=?",arrayOf<Any?>(id),listOf("id","event_key","observation_id")))
        if(purge){
            domains["memory_revisions"]=Triple("target_type='memory' AND target_id=?",arrayOf(id),listOf("id","target_type","target_id","before_content","after_content","actor","cause","created_at","before_title","snapshot_version"))
            domains["memory_links"]=Triple("from_id=? OR to_id=?",arrayOf(id,id),listOf("from_id","to_id","relation","actor","reason","created_at","orphaned","updated_at"))
            // Orphaning preserves verdict/evidence payload. Bind only exact identities and fields the primitive mutates.
            domains["memory_relations"]=Triple("source_id=? OR target_id=?",arrayOf(id,id),listOf("id","relation_id","source_id","target_id","status","updated_at","updated_at_submillis_nanos"))
        }
        domains.forEach{(table,domain)->
            string(table);var offset=0L
            while(true){token.throwIfCancelled()
                val rows=sql.query("SELECT rowid FROM $table WHERE ${domain.first} ORDER BY rowid LIMIT 64 OFFSET ?",(domain.second.toList()+offset).toTypedArray()).use{c->buildList{while(c.moveToNext())add(c.getLong(0))}}
                if(rows.isEmpty())break
                for(row in rows){number(row)
                    for(column in domain.third){token.throwIfCancelled();string(column)
                        val type=sql.query("SELECT typeof($column) FROM $table WHERE rowid=?",arrayOf(row)).use{c->check(c.moveToFirst());c.getString(0)};string(type)
                        when(type){
                            "null"->Unit
                            "integer"->number(sql.query("SELECT $column FROM $table WHERE rowid=? AND typeof($column)='integer'",arrayOf(row)).use{c->check(c.moveToFirst());c.getLong(0)})
                            "real"->number(sql.query("SELECT $column FROM $table WHERE rowid=? AND typeof($column)='real'",arrayOf(row)).use{c->check(c.moveToFirst());java.lang.Double.doubleToRawLongBits(c.getDouble(0))})
                            "text","blob"->{val length=sql.query("SELECT length(CAST($column AS BLOB)) FROM $table WHERE rowid=?",arrayOf(row)).use{c->check(c.moveToFirst());c.getLong(0)};number(length)
                                var position=0L;while(position<length){token.throwIfCancelled();val count=minOf(8192L,length-position).toInt()
                                    sql.query("SELECT substr(CAST($column AS BLOB),?,?) FROM $table WHERE rowid=?",arrayOf(position+1,count,row)).use{c->check(c.moveToFirst());val bytes=c.getBlob(0);check(bytes.size==count);digest.update(bytes)};position+=count}
                            }
                            else->error("memory_import_local_type_invalid")
                        }
                    }
                }
                offset=Math.addExact(offset,rows.size.toLong())
            }
            number(offset)
        }
        token.throwIfCancelled();return digest.digest().joinToString(""){"%02x".format(it)}
    }
}
