package com.jarvys.agent.chatdb

import android.database.Cursor
import com.jarvys.agent.CancellationToken
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Weak retained-session evidence only, never ownership or current-project detection. */
class MemoryProjectHistory(val directory: String, projects: List<String>) {
    val projects: List<String> = java.util.Collections.unmodifiableList(ArrayList(projects))
    val count: Long get() = projects.size.toLong()
}
class MemoryProjectHistoryCorruptException : IllegalStateException("memory_project_history_corrupt")

object MemoryProjectHistoryPaths {
    /** Android explicit descriptive selection: no implicit cwd or filesystem canonicalization. */
    @JvmStatic fun selection(raw: String): String = if(raw.isEmpty()) "" else MemoryProjectAliases.cwd(raw)

    /** Primary stored metadata is only lexical. A NUL component may disappear through '..'. */
    internal fun historical(raw: String): String? {
        if(!raw.startsWith('/')) return null
        val parts=ArrayList<String>()
        raw.split('/').forEach { part -> when(part) {
            "", "." -> Unit
            ".." -> if(parts.isNotEmpty())parts.removeAt(parts.lastIndex)
            else -> parts.add(part)
        } }
        return "/"+parts.joinToString("/")
    }
    /** On valid Unicode, scalar order equals Go's unsigned UTF8 byte lexicographic order. */
    internal fun compareUtf8(a: String, b: String): Int {
        var left=0;var right=0
        while(left<a.length && right<b.length) {
            val x=a.codePointAt(left);val y=b.codePointAt(right)
            if(x!=y)return x.compareTo(y)
            left+=Character.charCount(x);right+=Character.charCount(y)
        }
        return (a.length-left).compareTo(b.length-right)
    }
}

internal class MemoryProjectHistoryRepository(private val database: JarvysChatDatabase) {
    private fun text(c:Cursor,type:Int,bytes:Int):String {
        if(c.getType(type)!=Cursor.FIELD_TYPE_STRING || c.getString(type)!="text" || c.getType(bytes)!=Cursor.FIELD_TYPE_BLOB)throw MemoryProjectHistoryCorruptException()
        return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(c.getBlob(bytes))).toString() }
        catch(_:java.nio.charset.CharacterCodingException) { throw MemoryProjectHistoryCorruptException() }
    }
    suspend fun read(raw:String,namespace:MemoryNamespace,token:CancellationToken,verify:()->Unit):MemoryProjectHistory {
        val selected=MemoryProjectHistoryPaths.selection(raw)
        return database.withChatReadTransaction {
            verify()
            val (guard,args)=namespace.ids().clause()
            val projects=java.util.TreeSet<String>(Comparator(MemoryProjectHistoryPaths::compareUtf8))
            // Project authorization precedes byte projection and any name canonicalization.
            database.chatSql.query("SELECT DISTINCT typeof(project),CAST(project AS BLOB),typeof(directory),CAST(directory AS BLOB) FROM memory_sessions WHERE $guard AND project<>'' AND directory<>''",args.toTypedArray()).use { c ->
                while(c.moveToNext()) {
                    token.throwIfCancelled()
                    val project=text(c,0,1);val directory=text(c,2,3)
                    val canonical=try { MemoryProjectAliases.key(project) } catch(_:IllegalArgumentException) { null }
                    val historical=MemoryProjectHistoryPaths.historical(directory)
                    if(canonical!=null && historical!=null && historical==selected)projects.add(canonical)
                }
            }
            verify();MemoryProjectHistory(selected,projects.toList())
        }
    }
}
