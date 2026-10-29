package com.jarvys.agent.chatdb

import android.database.Cursor
import android.database.SQLException
import com.jarvys.agent.CancellationToken

/** Manual lexical scan only. A preview is not authority for a later apply. */
data class MemoryRelationScanOptions(val project:String?=null,val sinceMillis:Long?=null,val limit:Int=100,
    val maxInsert:Int=100,val apply:Boolean=false,val cursor:MemoryRelationScanCursor?=null)

/** Ephemeral boundary precondition, not a dataset snapshot or a writable capability. */
class MemoryRelationScanCursor internal constructor(internal val database:JarvysChatDatabase,
    internal val generation:String?,internal val project:String?,internal val sinceMillis:Long?,
    internal val rowId:Long,internal val observationId:String)
data class MemoryRelationScanCandidate(val id:String,val score:Double)
data class MemoryRelationScanSource(val id:String,val rankingPath:String,val candidates:List<MemoryRelationScanCandidate>)
data class MemoryRelationScanDiagnostic(val code:String,val sourceId:String?=null,val targetId:String?=null)
data class MemoryRelationScanResult(val project:String?,val sinceMillis:Long?,val dryRun:Boolean,
    val inspected:Int,val rankedQueries:Int,val candidatesFound:Int,val alreadyRelated:Int,val relationsInserted:Int,
    val capped:Boolean,val nextCursor:MemoryRelationScanCursor?,val rankingPath:String,
    val sources:List<MemoryRelationScanSource>,val confirmedRelations:List<MemoryRelationCandidate>,
    val incomplete:Boolean,val stopped:Boolean,val diagnostics:List<MemoryRelationScanDiagnostic>)

internal class MemoryRelationScanStaleCursor:IllegalStateException("memory_scan_cursor_stale")
internal class MemoryRelationScanSourceFailure(cause:Throwable):IllegalStateException("memory_scan_source_error",cause)
internal class MemoryRelationScanSqlItemFailure(cause:Throwable):IllegalStateException("memory_scan_sql_item_failed",cause)
internal class MemoryRelationScanInvariantFailure:IllegalStateException("memory_scan_readback_invalid")
internal class MemoryRelationScanIndexFailure(cause:Throwable):IllegalStateException("memory_scan_index_unavailable",cause)
internal class MemoryRelationScanCorruption(cause:Throwable):IllegalStateException("memory_scan_storage_corrupt",cause)
internal data class MemoryRelationScanSnapshot(val base:MemoryRelationSnapshot,val rowId:Long,val createdAt:Long,val toolName:String?) {
    fun same(other:MemoryRelationScanSnapshot?)=other!=null && base.same(other.base) && rowId==other.rowId && createdAt==other.createdAt && toolName==other.toolName
}
internal data class MemoryRelationScanMatch(val target:MemoryRelationScanSnapshot,val score:Double)
internal data class MemoryRelationScanSelection(val source:MemoryRelationScanSnapshot,val candidates:List<MemoryRelationScanMatch>,val problem:String?=null)
internal data class MemoryRelationScanPlan(val database:JarvysChatDatabase,val generation:String?,val options:MemoryRelationScanOptions,
    val sources:List<MemoryRelationScanSelection>,val hasMore:Boolean,val rankingPath:String)
/** Real per-item return, observed only after the worker's owned transaction has returned. */
data class MemoryRelationScanPairResult(val candidate:MemoryRelationCandidate?)

internal class MemoryRelationScanRepository(private val database:JarvysChatDatabase) {
    private val sql get()=database.chatSql
    private val columns="m.id,CAST(m.title AS BLOB),CAST(m.topic_key AS BLOB),m.text,m.observation_type,m.project,m.scope,m.rowid,m.created_at,m.tool_name"
    private fun read(c:Cursor)=MemoryRelationScanSnapshot(MemoryRelationSnapshot(c.getString(0),if(c.isNull(1)) null else c.getBlob(1),
        if(c.isNull(2)) null else c.getBlob(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6)),c.getLong(7),c.getLong(8),if(c.isNull(9)) null else c.getString(9))
    private fun snapshot(id:String,namespace:MemoryNamespace):MemoryRelationScanSnapshot? {
        val (owner,args)=namespace.ids().clause("m")
        return sql.query("SELECT $columns FROM memory_entries m WHERE m.id=? AND m.deleted_at IS NULL AND $owner",(listOf(id)+args).toTypedArray()).use { if(it.moveToFirst()) read(it) else null }
    }
    private fun generation()=MemorySaveSourceIdentity.existingGeneration(database)

    suspend fun lookup(options:MemoryRelationScanOptions,namespace:MemoryNamespace,token:CancellationToken):MemoryRelationScanPlan {
        require(options.limit in 0..100) { "memory_scan_limit_invalid" }
        require(options.project==null || MemoryNamespace.normalizeProject(options.project).isNotEmpty()) { "memory_scan_project_invalid" }
        val normalized=options.copy(project=options.project?.let(MemoryNamespace::normalizeProject),limit=if(options.limit==0) 100 else options.limit,
            maxInsert=if(options.maxInsert<=0) 100 else options.maxInsert)
        return database.withChatReadTransaction {
            token.throwIfCancelled()
            val gen=generation()
            val cursor=normalized.cursor
            if(cursor!=null) {
                val boundary=snapshot(cursor.observationId,namespace)
                if(cursor.database!==database || cursor.generation!=gen || cursor.project!=normalized.project || cursor.sinceMillis!=normalized.sinceMillis ||
                    boundary==null || boundary.rowId!=cursor.rowId || normalized.project!=null && boundary.base.project!=normalized.project ||
                    normalized.sinceMillis!=null && boundary.createdAt<normalized.sinceMillis) throw MemoryRelationScanStaleCursor()
            }
            // Definition validation is outside the recoverable ranking fallback. Never repair on read.
            PersonalMemorySearchIndex.requireCanonical(sql)
            val (owner,args)=namespace.projectOnly().clause("m")
            val since=normalized.sinceMillis?.let { " AND m.created_at>=?" }.orEmpty()
            val selected=sql.query("SELECT $columns FROM memory_entries m WHERE m.deleted_at IS NULL AND $owner$since AND m.rowid>? ORDER BY m.rowid ASC LIMIT ?",
                (args+listOfNotNull(normalized.sinceMillis)+listOf(cursor?.rowId ?: 0L,normalized.limit+1)).toTypedArray()).use { c -> buildList { while(c.moveToNext()) add(read(c)) } }
            val page=selected.take(normalized.limit)
            var path="batch_terms"
            // Only batch ranking (including its metadata/judged queries) may fall back.
            // Page/cursor/namespace/index validation must stay outside this try.
            val ranked=try { batch(page,namespace,token) } catch(failure:SQLException) {
                if(isChatSqlCorruption(failure)) throw MemoryRelationScanCorruption(failure)
                path="fallback_native_rank"
                page.map { source ->
                    token.throwIfCancelled()
                    try { MemoryRelationScanSelection(source,fallback(source,namespace)) }
                    catch(item:SQLException) {
                        if(isChatSqlCorruption(item)) throw MemoryRelationScanCorruption(item)
                        MemoryRelationScanSelection(source,emptyList(),"memory_scan_source_query_incomplete")
                    }
                }
            }
            token.throwIfCancelled()
            MemoryRelationScanPlan(database,gen,normalized,ranked,selected.size>normalized.limit,path)
        }
    }

    private fun batch(page:List<MemoryRelationScanSnapshot>,namespace:MemoryNamespace,token:CancellationToken):List<MemoryRelationScanSelection> {
        val sourceTerms=page.associate { it.base.id to MemoryRelationCandidates.candidateTerms(it.base.titleText()).distinct() }
        val terms=linkedMapOf<String,Set<Long>>()
        for(term in sourceTerms.values.flatten().distinct()) {
            token.throwIfCancelled()
            terms[term]=sql.query("SELECT rowid FROM memory_fts WHERE memory_fts MATCH ?",arrayOf(term)).use { c -> buildSet { while(c.moveToNext()) add(c.getLong(0)) } }
        }
        val candidates=linkedMapOf<Long,MemoryRelationScanSnapshot>()
        val (owner,args)=namespace.ids().clause("m")
        for(chunk in terms.values.flatten().distinct().sorted().chunked(500)) {
            token.throwIfCancelled()
            sql.query("SELECT $columns FROM memory_entries m WHERE m.deleted_at IS NULL AND $owner AND m.rowid IN (${chunk.joinToString(","){"?"}})",
                (args+chunk).toTypedArray()).use { c -> while(c.moveToNext()) { val row=read(c); candidates[row.rowId]=row } }
        }
        val judged=HashSet<Pair<String,String>>()
        for(chunk in page.map {it.base.id}.chunked(500)) {
            val placeholders=chunk.joinToString(","){"?"}
            sql.query("SELECT source_id,target_id FROM memory_relations WHERE status='judged' AND (source_id IN ($placeholders) OR target_id IN ($placeholders))",
                (chunk+chunk).toTypedArray()).use { c -> while(c.moveToNext()) { val a=c.getString(0); val b=c.getString(1); judged.add(a to b); judged.add(b to a) } }
        }
        return page.map { source ->
            token.throwIfCancelled()
            val scores=HashMap<Long,Int>()
            for(term in sourceTerms.getValue(source.base.id)) for(id in terms.getValue(term)) scores[id]=(scores[id]?:0)+1
            val found=scores.entries.mapNotNull { (id,score) ->
                val target=candidates[id]
                if(target==null || target.base.id==source.base.id || target.base.project!=source.base.project || target.base.scope!=source.base.scope ||
                    (source.base.id to target.base.id) in judged) null else MemoryRelationScanMatch(target,score.toDouble())
            }.sortedWith(compareByDescending<MemoryRelationScanMatch>{it.score}.thenBy {it.target.rowId}).take(10)
            MemoryRelationScanSelection(source,found)
        }
    }

    private fun fallback(source:MemoryRelationScanSnapshot,namespace:MemoryNamespace):List<MemoryRelationScanMatch> {
        val terms=MemoryRelationCandidates.candidateTerms(source.base.titleText())
        if(terms.isEmpty()) return emptyList()
        val (owner,args)=namespace.ids().clause("m")
        return sql.query("SELECT $columns,memory_fts.rank FROM memory_fts CROSS JOIN memory_entries m ON m.rowid=memory_fts.rowid WHERE memory_fts MATCH ? " +
            "AND m.id!=? AND m.deleted_at IS NULL AND m.project=? AND m.scope=? AND $owner AND memory_fts.rank<=0.0 " +
            "AND NOT EXISTS(SELECT 1 FROM memory_relations r WHERE r.status='judged' AND ((r.source_id=? AND r.target_id=m.id) OR (r.target_id=? AND r.source_id=m.id))) ORDER BY memory_fts.rank LIMIT 10",
            (listOf<Any?>(terms.joinToString(" OR "),source.base.id,source.base.project,source.base.scope)+args+listOf(source.base.id,source.base.id)).toTypedArray()).use { c ->
            buildList { while(c.moveToNext()) add(MemoryRelationScanMatch(read(c),c.getDouble(10))) }
        }
    }

    suspend fun insert(plan:MemoryRelationScanPlan,selection:MemoryRelationScanSelection,match:MemoryRelationScanMatch,
        namespace:MemoryNamespace,token:CancellationToken):MemoryRelationScanPairResult {
        var sourceChecked=false
        try {
            return database.withChatWriteTransaction {
                token.throwIfCancelled()
                if(plan.database!==database || plan.generation!=generation() || !selection.source.same(snapshot(selection.source.base.id,namespace)) ||
                    !match.target.same(snapshot(match.target.base.id,namespace))) throw MemoryRelationSourceChanged()
                try { PersonalMemorySearchIndex.requireCanonical(sql) }
                catch(failure:IllegalStateException) { throw MemoryRelationScanIndexFailure(failure) }
                val result=MemoryRelationRepository(database).insertPending(MemoryRelationLookup(selection.source.base,emptyList()),MemoryRelationMatch(match.target.base,match.score),namespace,
                    beforeEffect={token.throwIfCancelled()},afterEffect={token.throwIfCancelled()},beforeInsert={sourceChecked=true})
                if(result!=null) {
                    // The INSERT receipt alone is not a database readback. This query is inside the
                    // same owned writer; SQL or invariant failure rolls the new row back as well.
                    val exact=sql.query("SELECT source_id,target_id,project,scope,relation,status,created_at,updated_at,reason,evidence,confidence,marked_by_actor,marked_by_kind,marked_by_model,memory_session_id FROM memory_relations WHERE relation_id=?",
                        arrayOf(result.judgmentId)).use { c ->
                        c.moveToFirst() && c.getString(0)==selection.source.base.id && c.getString(1)==match.target.base.id &&
                            c.getString(2)==selection.source.base.project && c.getString(3)==selection.source.base.scope &&
                            c.getString(4)=="pending" && c.getString(5)=="pending" && !c.isNull(6) && c.getLong(6)==c.getLong(7) &&
                            (8..14).all { c.isNull(it) } && !c.moveToNext()
                    }
                    if(!exact) throw MemoryRelationScanInvariantFailure()
                }
                token.throwIfCancelled()
                MemoryRelationScanPairResult(result)
            }
        } catch(failure:SQLException) {
            if(isChatSqlCorruption(failure)) throw MemoryRelationScanCorruption(failure)
            if(!sourceChecked) throw MemoryRelationScanSourceFailure(failure)
            throw MemoryRelationScanSqlItemFailure(failure)
        }
    }
}
