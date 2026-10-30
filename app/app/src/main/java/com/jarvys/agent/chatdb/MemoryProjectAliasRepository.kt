package com.jarvys.agent.chatdb

import android.database.Cursor
import com.jarvys.agent.CancellationToken
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Manual alias metadata only. All ownership checks use the original operation namespace. */
internal class MemoryProjectAliasRepository(private val database: JarvysChatDatabase) {
    private val sql get() = database.chatSql
    private fun text(c: Cursor, type: Int, bytes: Int): String {
        if (c.getType(type) != Cursor.FIELD_TYPE_STRING || c.getString(type) != "text" || c.getType(bytes) != Cursor.FIELD_TYPE_BLOB) throw MemoryProjectAliasCorruptException()
        return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(c.getBlob(bytes))).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { throw MemoryProjectAliasCorruptException() }
    }
    private val fields = "typeof(kind),CAST(kind AS BLOB),typeof(alias_key),CAST(alias_key AS BLOB),typeof(project),CAST(project AS BLOB)"
    private fun row(c: Cursor): MemoryProjectAlias {
        val value = MemoryProjectAlias(text(c,0,1),text(c,2,3),text(c,4,5))
        try {
            if (MemoryProjectAliases.normalize(value.kind,value.key) != value.key || MemoryProjectAliases.key(value.project) != value.project) throw MemoryProjectAliasCorruptException()
        } catch (_: IllegalArgumentException) { throw MemoryProjectAliasCorruptException() }
        return value
    }
    /** A hidden exact match denies; it is never a vacant key that this caller can claim. */
    private fun exact(kind:String,key:String,namespace:MemoryNamespace):MemoryProjectAlias? {
        val (guard,args)=namespace.ids().clause()
        val exists=sql.query("SELECT typeof(project),CASE WHEN $guard THEN 1 ELSE 0 END FROM memory_project_aliases WHERE kind=? AND alias_key=?",(args+listOf(kind,key)).toTypedArray()).use { c ->
            if (!c.moveToFirst()) false else {
                if (c.getString(0)!="text") throw MemoryProjectAliasCorruptException()
                require(c.getLong(1)==1L) { "memory_alias_owner_denied" }
                true
            }
        }
        if (!exists) return null
        return sql.query("SELECT $fields FROM memory_project_aliases WHERE kind=? AND alias_key=? AND $guard",(listOf<Any?>(kind,key)+args).toTypedArray()).use { c ->
            check(c.moveToFirst()) { "memory_alias_owner_changed" };row(c)
        }
    }
    suspend fun list(namespace:MemoryNamespace,token:CancellationToken,verify:()->Unit):List<MemoryProjectAlias> = database.withChatReadTransaction {
        verify()
        val (guard,args)=namespace.ids().clause()
        val result=sql.query("SELECT $fields FROM memory_project_aliases WHERE $guard ORDER BY kind,alias_key",args.toTypedArray()).use { c ->
            buildList { while(c.moveToNext()) { token.throwIfCancelled();add(row(c)) } }
        }
        verify();java.util.Collections.unmodifiableList(ArrayList(result))
    }
    suspend fun set(kind:String,raw:String,target:String,namespace:MemoryNamespace,token:CancellationToken,verify:()->Unit):MemoryProjectAlias {
        val key=MemoryProjectAliases.normalize(kind,raw);val project=MemoryProjectAliases.key(target)
        require(namespace.owns(project)) { "memory_alias_owner_denied" }
        return database.withChatWriteTransaction {
            verify();exact(kind,key,namespace);token.throwIfCancelled()
            sql.execSQL("INSERT INTO memory_project_aliases(kind,alias_key,project) VALUES(?,?,?) ON CONFLICT(kind,alias_key) DO UPDATE SET project=excluded.project",arrayOf(kind,key,project))
            val result=exact(kind,key,namespace)
            check(result==MemoryProjectAlias(kind,key,project)) { "memory_alias_readback_failed" }
            verify();checkNotNull(result)
        }
    }
    suspend fun remove(kind:String,raw:String,namespace:MemoryNamespace,token:CancellationToken,verify:()->Unit):Boolean {
        val key=MemoryProjectAliases.normalize(kind,raw)
        return database.withChatWriteTransaction {
            verify()
            if(exact(kind,key,namespace)==null) { verify();false } else {
                token.throwIfCancelled();sql.execSQL("DELETE FROM memory_project_aliases WHERE kind=? AND alias_key=?",arrayOf(kind,key))
                check(sql.query("SELECT changes()").use{it.moveToFirst();it.getLong(0)==1L}) { "memory_alias_readback_failed" }
                check(exact(kind,key,namespace)==null) { "memory_alias_readback_failed" }
                verify();true
            }
        }
    }
    suspend fun resolve(rawKey:String,rawCwd:String,namespace:MemoryNamespace,token:CancellationToken,verify:()->Unit):MemoryProjectAliasResolution {
        val key=rawKey.takeIf{it.isNotEmpty()}?.let(MemoryProjectAliases::key)
        val cwd=rawCwd.takeIf{it.isNotEmpty()}?.let(MemoryProjectAliases::cwd)
        return database.withChatReadTransaction {
            verify()
            val (guard,args)=namespace.ids().clause()
            // One snapshot/query observes BOTH exact matches. CASE prevents hydrating foreign values.
            val guardedFields=listOf("kind","alias_key","project").flatMap { field -> listOf("CASE WHEN $guard THEN typeof($field) END","CASE WHEN $guard THEN CAST($field AS BLOB) END") }.joinToString(",")
            var keyAlias:MemoryProjectAlias?=null;var cwdAlias:MemoryProjectAlias?=null
            check(args.isEmpty()) // ids() uses only private Registry-derived literals, no external SQL identifiers.
            sql.query("SELECT $guardedFields,typeof(project),CASE WHEN $guard THEN 1 ELSE 0 END FROM memory_project_aliases WHERE (kind='key' AND alias_key=? AND ? IS NOT NULL) OR (kind='cwd' AND alias_key=? AND ? IS NOT NULL)",arrayOf(key,key,cwd,cwd)).use { c ->
                while(c.moveToNext()) {
                    token.throwIfCancelled()
                    if(c.getString(6)!="text") throw MemoryProjectAliasCorruptException()
                    require(c.getLong(7)==1L) { "memory_alias_owner_denied" }
                    val value=row(c)
                    if(value.kind=="key")keyAlias=value else cwdAlias=value
                }
            }
            val conflict=keyAlias!=null && cwdAlias!=null && keyAlias!!.project!=cwdAlias!!.project
            val result=MemoryProjectAliasResolution(if(conflict) "" else cwdAlias?.project?:keyAlias?.project?:rawKey,key,cwd,keyAlias,cwdAlias,conflict)
            verify();result
        }
    }
}
