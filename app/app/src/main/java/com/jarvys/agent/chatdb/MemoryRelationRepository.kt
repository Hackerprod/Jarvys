package com.jarvys.agent.chatdb

import android.database.Cursor
import java.util.UUID

internal class MemoryJudgmentItemException(code:String):IllegalArgumentException(code)
internal class MemoryJudgmentAuthorityException:IllegalStateException("memory_judgment_owner_denied")
internal class MemoryRelationUnknownOutcome : IllegalStateException("memory_relation_unknown_outcome")
internal class MemoryRelationSourceChanged : IllegalStateException("memory_relation_source_changed")
internal data class MemoryRelationSnapshot(val id:String,val title:ByteArray?,val topic:ByteArray?,val content:String,
    val type:String,val project:String,val scope:String,val deleted:Boolean=false) {
    fun same(other:MemoryRelationSnapshot?):Boolean = other!=null && id==other.id &&
        MemoryObservationContent.sameTopic(title,other.title) && MemoryObservationContent.sameTopic(topic,other.topic) &&
        content==other.content && type==other.type && project==other.project && scope==other.scope && deleted==other.deleted
    fun titleText()=MemoryObservationContent.topicDisplay(title).orEmpty()
}
internal data class MemoryRelationMatch(val target:MemoryRelationSnapshot,val score:Double)
internal data class MemoryRelationLookup(val source:MemoryRelationSnapshot,val candidates:List<MemoryRelationMatch>)

/** Same-Room pending questions. Lookup does not write; each confirmed pair has its own transaction. */
internal class MemoryRelationRepository(private val database:JarvysChatDatabase,private val clock:()->Long=System::currentTimeMillis) {
    private val sql get()=database.chatSql
    private fun snapshot(id:String,namespace:MemoryNamespace):MemoryRelationSnapshot? {
        val (owner,args)=namespace.ids().clause()
        return sql.query("SELECT id,CAST(title AS BLOB),CAST(topic_key AS BLOB),text,observation_type,project,scope FROM memory_entries WHERE id=? AND deleted_at IS NULL AND $owner",(listOf(id)+args).toTypedArray()).use { c ->
            if(!c.moveToFirst()) null else readSnapshot(c)
        }
    }
    private fun readSnapshot(c:Cursor)=MemoryRelationSnapshot(c.getString(0),if(c.isNull(1)) null else c.getBlob(1),
        if(c.isNull(2)) null else c.getBlob(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6))

    suspend fun lookup(sourceId:String,namespace:MemoryNamespace,limit:Int=3):MemoryRelationLookup = database.withChatReadTransaction {
        val source=snapshot(sourceId,namespace) ?: throw MemoryRelationSourceChanged()
        val terms=MemoryRelationCandidates.candidateTerms(source.titleText())
        if(terms.isEmpty()) return@withChatReadTransaction MemoryRelationLookup(source,emptyList())
        PersonalMemorySearchIndex.requireCanonical(sql)
        val (owners,args)=namespace.ids().clause("m")
        val found=ArrayList<MemoryRelationMatch>()
        // Native rank order, no premature LIMIT: content-only hits must not consume relevance slots.
        sql.query("SELECT m.id,CAST(m.title AS BLOB),CAST(m.topic_key AS BLOB),m.text,m.observation_type,m.project,m.scope,memory_fts.rank " +
            "FROM memory_fts CROSS JOIN memory_entries m ON m.rowid=memory_fts.rowid WHERE memory_fts MATCH ? " +
            "AND m.id!=? AND m.deleted_at IS NULL AND m.project=? AND m.scope=? AND $owners AND memory_fts.rank<=0.0 " +
            "AND NOT EXISTS(SELECT 1 FROM memory_relations r WHERE r.status='judged' AND ((r.source_id=? AND r.target_id=m.id) OR (r.target_id=? AND r.source_id=m.id))) " +
            "ORDER BY memory_fts.rank",(listOf<Any?>(terms.joinToString(" OR "),source.id,source.project,source.scope)+args+listOf(source.id,source.id)).toTypedArray()).use { c ->
            while(c.moveToNext()) {
                val target=readSnapshot(c)
                if(MemoryRelationCandidates.relevant(source.titleText(),source.topic,target.titleText(),target.topic)) {
                    found.add(MemoryRelationMatch(target,c.getDouble(7)))
                    if(found.size >= if(limit<=0) 3 else limit) break
                }
            }
        }
        MemoryRelationLookup(source,found)
    }

    suspend fun insertPending(lookup:MemoryRelationLookup,match:MemoryRelationMatch,namespace:MemoryNamespace,
        beforeEffect:()->Unit = {}, afterEffect:()->Unit = {}, beforeInsert:()->Unit = {}):MemoryRelationCandidate? = database.withChatWriteTransaction {
        beforeEffect()
        if(!lookup.source.same(snapshot(lookup.source.id,namespace)) || !match.target.same(snapshot(match.target.id,namespace))) throw MemoryRelationSourceChanged()
        beforeInsert() // Source queries completed; callers may distinguish their failures from mutation SQL.
        val id=UUID.randomUUID().toString(); val now=clock()
        sql.execSQL("INSERT INTO memory_relations(relation_id,source_id,target_id,project,scope,relation,status,created_at,updated_at) " +
            "SELECT ?,?,?,?,?,'pending','pending',?,? WHERE NOT EXISTS(SELECT 1 FROM memory_relations WHERE (source_id=? AND target_id=?) OR (source_id=? AND target_id=?))",
            arrayOf(id,lookup.source.id,match.target.id,lookup.source.project,lookup.source.scope,now,now,lookup.source.id,match.target.id,match.target.id,lookup.source.id))
        val changed=sql.query("SELECT changes()").use { c -> c.moveToFirst(); c.getLong(0) }
        if(changed !in 0L..1L) throw MemoryRelationUnknownOutcome()
        if(changed==0L) null else match.target.let { target ->
            afterEffect()
            MemoryRelationCandidate(id,target.id,target.titleText(),target.type,
            MemoryObservationContent.topicDisplay(target.topic),match.score,MemoryObservationContent.topicBase64(target.title),MemoryObservationContent.topicIsUtf8(target.title),
            MemoryObservationContent.topicBase64(target.topic),MemoryObservationContent.topicIsUtf8(target.topic)) }
    }

    private fun readRelations(namespace:MemoryNamespace,pending:Boolean,ids:List<String>?=null,relationId:String?=null,includeOrphan:Boolean=false):List<MemoryRelationView> {
        val (a,aa)=namespace.ids().clause("s"); val (b,ba)=namespace.ids().clause("t")
        val selected=if(ids==null) "" else " AND (r.source_id IN (${ids.joinToString(","){"?"}}) OR r.target_id IN (${ids.joinToString(","){"?"}}))"
        // Retained endpoints must both still be owned. Audit project alone never grants payload access.
        val query="SELECT r.relation_id,r.source_id,r.target_id,CAST(s.title AS BLOB),CAST(t.title AS BLOB),s.id,s.deleted_at,t.id,t.deleted_at,r.relation,r.status,r.created_at,r.reason,r.evidence,r.confidence,r.marked_by_actor,r.marked_by_kind,r.marked_by_model,r.memory_session_id,r.updated_at " +
            "FROM memory_relations r LEFT JOIN memory_entries s ON s.id=r.source_id LEFT JOIN memory_entries t ON t.id=r.target_id " +
            "WHERE ${if(pending) "r.status='pending'" else if(includeOrphan) "1" else "r.status!='orphaned'"} AND (s.id IS NOT NULL OR t.id IS NOT NULL) " +
            "${if(namespace.requiresCompleteEndpointAttribution()) "AND s.id IS NOT NULL AND t.id IS NOT NULL " else ""}AND (s.id IS NULL OR $a) AND (t.id IS NULL OR $b)$selected ${if(relationId==null) "" else "AND r.relation_id=?"} ORDER BY r.created_at DESC,r.id DESC"
        return sql.query(query,(aa+ba+(ids.orEmpty())+(ids.orEmpty())+listOfNotNull(relationId)).toTypedArray()).use { c -> buildList {
            while(c.moveToNext()) {
                val sm=c.isNull(5)||!c.isNull(6); val tm=c.isNull(7)||!c.isNull(8)
                add(MemoryRelationView(c.getString(0),c.getString(1),c.getString(2),
                    if(c.isNull(3)||pending&&sm) "" else MemoryObservationContent.topicDisplay(c.getBlob(3)).orEmpty(),
                    if(c.isNull(4)||pending&&tm) "" else MemoryObservationContent.topicDisplay(c.getBlob(4)).orEmpty(),
                    sm,tm,c.getString(9),c.getString(10),c.getLong(11),
                    sourceTitleBytesBase64=if(c.isNull(3)||pending&&sm) null else MemoryObservationContent.topicBase64(c.getBlob(3)),
                    sourceTitleIsUtf8=if(c.isNull(3)||pending&&sm) null else MemoryObservationContent.topicIsUtf8(c.getBlob(3)),
                    targetTitleBytesBase64=if(c.isNull(4)||pending&&tm) null else MemoryObservationContent.topicBase64(c.getBlob(4)),
                    targetTitleIsUtf8=if(c.isNull(4)||pending&&tm) null else MemoryObservationContent.topicIsUtf8(c.getBlob(4)),
                    reason=if(c.isNull(12)) null else c.getString(12),evidence=if(c.isNull(13)) null else c.getString(13),
                    confidence=if(c.isNull(14)) null else c.getDouble(14),markedByActor=if(c.isNull(15)) null else c.getString(15),
                    markedByKind=if(c.isNull(16)) null else c.getString(16),markedByModel=if(c.isNull(17)) null else c.getString(17),
                    memorySessionId=if(c.isNull(18)) null else c.getString(18),updatedAt=c.getLong(19)))
            }
        } }
    }
    suspend fun pendingRelations(namespace:MemoryNamespace,limit:Int=20,offset:Int=0):MemoryRelationPage = database.withChatReadTransaction {
        val all=readRelations(namespace,true); val start=offset.coerceAtLeast(0); val max=if(limit<=0) 20 else limit
        val page=all.drop(start).take(max); val next=start+page.size; val more=next<all.size
        MemoryRelationPage(page,all.size.toLong(),more,if(more) next else null)
    }
    fun annotations(ids:List<String>,namespace:MemoryNamespace):Map<String,List<MemoryRelationView>> {
        if(ids.isEmpty()) return emptyMap()
        val result=HashMap<String,MutableList<MemoryRelationView>>()
        ids.chunked(200).forEach { chunk -> readRelations(namespace,false,chunk).forEach { r ->
            if(r.sourceId in chunk) result.getOrPut(r.sourceId){ArrayList()}.add(r.copy(direction="out"))
            if(r.targetId in chunk) result.getOrPut(r.targetId){ArrayList()}.add(r.copy(direction="in"))
        } }
        return result
    }
    private fun retainedSnapshot(id:String,namespace:MemoryNamespace):MemoryRelationSnapshot? =
        sql.query("SELECT id,CAST(title AS BLOB),CAST(topic_key AS BLOB),text,observation_type,project,scope,deleted_at FROM memory_entries WHERE id=?",arrayOf(id)).use { c ->
            if(!c.moveToFirst()) null else readSnapshot(c).copy(deleted=!c.isNull(7)).also {
                if(!namespace.owns(it.project)) throw MemoryJudgmentAuthorityException()
            }
        }
    suspend fun details(id:String,namespace:MemoryNamespace):MemoryRelationDetails = database.withChatReadTransaction {
        val relation=readRelations(namespace,false,relationId=id,includeOrphan=true).singleOrNull()
            ?: throw MemoryJudgmentItemException("memory_judgment_not_found")
        val source=retainedSnapshot(relation.sourceId,namespace); val target=retainedSnapshot(relation.targetId,namespace)
        val hits=PersonalAgentRepository(database).retainedRelationEndpoints(listOf(relation.sourceId,relation.targetId),namespace).associateBy { it.id }
        MemoryRelationDetails(relation,hits[relation.sourceId],hits[relation.targetId],source,target)
    }
    private fun endpoint(id:String,namespace:MemoryNamespace,live:Boolean):MemoryRelationSnapshot {
        val value=retainedSnapshot(id,namespace) ?: throw MemoryJudgmentItemException("memory_judgment_endpoint_missing")
        if(live && value.deleted) throw MemoryJudgmentItemException("memory_judgment_endpoint_missing")
        return value
    }
    private fun readback(id:String,namespace:MemoryNamespace):MemoryRelationView =
        readRelations(namespace,false,relationId=id).singleOrNull() ?: error("memory_judgment_readback_failed")

    suspend fun judge(item:MemoryJudgmentItem,namespace:MemoryNamespace,actor:String,kind:String,session:String?,
        displayed:MemoryRelationDetails?=null,beforeEffect:()->Unit={},afterEffect:()->Unit={}):MemoryRelationView = database.withChatWriteTransaction {
        beforeEffect()
        require(item.error==null && item.judgmentId!=null && item.relation in MemoryJudgmentArguments.verbs) { "memory_judgment_item_invalid" }
        val tuple=sql.query("SELECT source_id,target_id,status FROM memory_relations WHERE relation_id=?",arrayOf(item.judgmentId)).use { c ->
            if(!c.moveToFirst()) throw MemoryJudgmentItemException("memory_judgment_not_found")
            Triple(c.getString(0),c.getString(1),c.getString(2))
        }
        if(tuple.third=="orphaned") throw MemoryJudgmentItemException("memory_judgment_orphaned")
        val source=endpoint(tuple.first,namespace,false);val target=endpoint(tuple.second,namespace,false)
        if(source.project!=target.project) throw MemoryJudgmentItemException("memory_judgment_cross_project")
        if(displayed!=null && (displayed.relation.judgmentId!=item.judgmentId || displayed.relation.sourceId!=source.id ||
            displayed.relation.targetId!=target.id || !source.same(displayed.sourceSnapshot) || !target.same(displayed.targetSnapshot)))
            throw MemoryJudgmentItemException("memory_judgment_evidence_changed")
        val now=clock()
        sql.execSQL("UPDATE memory_relations SET relation=?,status='judged',reason=?,evidence=?,confidence=?,marked_by_actor=?,marked_by_kind=?,marked_by_model=NULL,memory_session_id=?,updated_at=? WHERE relation_id=?",
            arrayOf(item.relation,item.reason,item.evidence,item.confidence,actor,kind,session,now,item.judgmentId))
        requireOne();afterEffect();readback(item.judgmentId,namespace)
    }
    suspend fun compare(item:MemoryJudgmentItem,namespace:MemoryNamespace,session:String?,beforeEffect:()->Unit={},afterEffect:()->Unit={}):MemoryRelationView =
        comparePrepared(item,namespace,session,false,beforeEffect,afterEffect,{})

    /** Actual semantic runner permits the primary empty reasoning default, unlike caller compare. */
    suspend fun compareSemantic(sourceId:String,targetId:String,verdict:com.jarvys.agent.MemorySemanticVerdict,model:String?,namespace:MemoryNamespace,
        beforeEffect:()->Unit={},afterEffect:()->Unit={},beforeMutation:()->Unit={}):MemoryRelationView =
        comparePrepared(MemoryJudgmentItem(0,sourceId=sourceId,targetId=targetId,relation=verdict.relation,reason=verdict.reasoning,
            confidence=verdict.confidence,model=model),namespace,null,true,beforeEffect,afterEffect,beforeMutation)

    private suspend fun comparePrepared(item:MemoryJudgmentItem,namespace:MemoryNamespace,session:String?,allowEmptyReason:Boolean,
        beforeEffect:()->Unit,afterEffect:()->Unit,beforeMutation:()->Unit):MemoryRelationView = database.withChatWriteTransaction {
        beforeEffect()
        require(item.error==null && item.sourceId!=null && item.targetId!=null && item.relation in MemoryJudgmentArguments.verbs &&
            item.reason!=null && (allowEmptyReason || item.reason.isNotEmpty()) && item.confidence!=null && item.confidence.isFinite() && item.confidence in 0.0..1.0) { "memory_judgment_item_invalid" }
        val source=endpoint(item.sourceId,namespace,true);val target=endpoint(item.targetId,namespace,true)
        if(source.project!=target.project) throw MemoryJudgmentItemException("memory_judgment_cross_project")
        val existing=sql.query("SELECT relation_id,status FROM memory_relations WHERE (source_id=? AND target_id=?) OR (source_id=? AND target_id=?) ORDER BY id ASC LIMIT 1",arrayOf(source.id,target.id,target.id,source.id)).use { c ->
            if(!c.moveToFirst()) null else c.getString(0) to c.getString(1)
        }
        if(existing?.second=="orphaned") throw MemoryJudgmentItemException("memory_judgment_orphaned")
        beforeMutation()
        val id=existing?.first ?: UUID.randomUUID().toString();val now=clock()
        if(existing==null) sql.execSQL("INSERT INTO memory_relations(relation_id,source_id,target_id,project,scope,relation,status,created_at,updated_at,reason,confidence,marked_by_actor,marked_by_kind,marked_by_model,memory_session_id) VALUES(?,?,?,?,?,?,'judged',?,?,?,?,'jarvys','system',?,?)",
            arrayOf(id,source.id,target.id,source.project,source.scope,item.relation,now,now,item.reason,item.confidence,item.model,session))
        else sql.execSQL("UPDATE memory_relations SET source_id=?,target_id=?,project=?,scope=?,relation=?,status='judged',reason=?,confidence=?,marked_by_actor='jarvys',marked_by_kind='system',marked_by_model=?,updated_at=? WHERE relation_id=?",
            arrayOf(source.id,target.id,source.project,source.scope,item.relation,item.reason,item.confidence,item.model,now,id))
        requireOne();afterEffect();readback(id,namespace)
    }
    private fun requireOne() { check(sql.query("SELECT changes()").use { it.moveToFirst();it.getLong(0)==1L }) { "memory_judgment_unknown_outcome" } }
    fun orphan(id:String,now:Long) { sql.execSQL("UPDATE memory_relations SET status='orphaned',updated_at=? WHERE source_id=? OR target_id=?",arrayOf(now,id,id)) }
}
