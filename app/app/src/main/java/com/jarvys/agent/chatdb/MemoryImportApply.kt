package com.jarvys.agent.chatdb

import com.jarvys.agent.*
import com.jarvys.agent.MemoryImportLookup.Domain
import java.io.ByteArrayInputStream
import java.io.StringReader

/** No provider operations here: private sealed chunks and the caller's existing Room writer only. */
internal class MemoryImportApply(private val plan:MemoryImportPlan,private val token:CancellationToken) {
    private val sql=plan.database.chatSql
    private val data=plan.data
    private val fields=MemoryImportFields(sql,data,token)
    private val lookup=MemoryImportLookup(sql,data,plan.namespace.authorizedProjects(),token)
    private lateinit var row:MemoryImportRecord
    private fun expression(name:String):String=fields.text(MemoryImportWire.fields[row.domain].indexOf(name).also{require(it>=0)})
    private fun scalar(name:String)="COALESCE(${expression(name)},'')"
    private fun stage(record:MemoryImportRecord){
        row=record;fields.clear()
        MemoryImportWire.fields[row.domain].forEachIndexed {index,_->val cell=row.cells[index]
            if(cell.type==MemoryImportWire.STRING||MemoryImportValues.nullable(cell))fields.put(index,cell)
        }
    }
    private fun changed(expected:Long=1){check(sql.query("SELECT changes()").use{check(it.moveToFirst());it.getLong(0)}==expected){"memory_import_write_unconfirmed"}}
    private fun requireRow(query:String,args:Array<Any?>){token.throwIfCancelled();check(sql.query(query,args).use{it.moveToFirst()&&!it.moveToNext()}){"memory_import_readback_mismatch"}}
    private fun time(name:String,required:Boolean=false):MemoryPortableTime.Value? {
        val cell=row.cell(name);if(MemoryImportValues.nullable(cell)){require(!required);return null}
        return data.reader(cell).use{MemoryPortableTime.parse(it,token)}.also{require(it!=null||!required){"memory_import_time_invalid"}}
    }
    private fun storedTime(id:String,name:String):MemoryPortableTime.Value {
        require(name in setOf("created_at","updated_at"))
        return sql.query("SELECT $name,${name}_submillis_nanos FROM memory_entries WHERE id=? AND typeof($name)='integer' AND typeof(${name}_submillis_nanos)='integer'",arrayOf(id)).use{c->
            check(c.moveToFirst());val remainder=c.getLong(1);check(remainder in 0..999999);MemoryPortableTime.Value(c.getLong(0),remainder.toInt())}
    }
    private fun restore(table:String,key:String,id:String,times:Map<String,MemoryPortableTime.Value?>){
        val assignments=times.keys.joinToString(","){"${it}_submillis_nanos=?"}
        sql.execSQL("UPDATE $table SET $assignments WHERE $key=?",(times.values.map{it?.submillisNanos?:0}+id).toTypedArray());changed()
        val checks=times.keys.joinToString(" AND "){"${it}_submillis_nanos=?"}
        requireRow("SELECT 1 FROM $table WHERE $key=? AND $checks",(listOf<Any?>(id)+times.values.map{it?.submillisNanos?:0}).toTypedArray())
    }
    private fun same(column:String,expected:String)="CAST($column AS BLOB) IS CAST(($expected) AS BLOB)"
    private fun applySession(decision:MemoryImportDecision){
        val started=checkNotNull(time("started_at",true));val ended=time("ended_at")
        val mode=MemoryImportValues.ownershipMode(data,row.cell("ownership_mode"),token)
        sql.execSQL("INSERT INTO memory_sessions(id,project,ownership_mode,state,started_at,ended_at,summary,directory,started_at_submillis_nanos,ended_at_submillis_nanos) SELECT ${expression("id")},?,?,'imported',?,?,${expression("summary")},${scalar("directory")},?,?",
            arrayOf(decision.project,mode,started.epochMillis,ended?.epochMillis,started.submillisNanos,ended?.submillisNanos?:0));changed()
        requireRow("SELECT 1 FROM memory_sessions WHERE ${same("id",expression("id"))} AND project=? AND ownership_mode=? AND state='imported' AND started_at=? AND ended_at IS ? AND started_at_submillis_nanos=? AND ended_at_submillis_nanos=? AND ${same("summary",expression("summary"))} AND ${same("directory",scalar("directory"))} AND source_generation IS NULL AND owner_conversation_id IS NULL AND owner_message_id IS NULL AND run_id IS NULL AND process_id IS NULL AND runtime_lease_expires_at IS NULL AND runtime_root_id IS NULL AND resume_event_key IS NULL AND maintenance_job_id IS NULL AND maintenance_lease_owner IS NULL AND compaction_parent_session_id IS NULL AND mission_execution_session_id IS NULL AND mission_evidence_id IS NULL AND mission_assignment_id IS NULL",
            arrayOf(decision.project,mode,started.epochMillis,ended?.epochMillis,started.submillisNanos,ended?.submillisNanos?:0))
    }
    private fun applySessionMarker(decision:MemoryImportDecision){
        val deleted=checkNotNull(time("deleted_at",true))
        sql.execSQL("INSERT INTO memory_session_tombstones(id,project,deleted_at,deleted_at_submillis_nanos) SELECT ${expression("id")},?,?,?",arrayOf(decision.project,deleted.epochMillis,deleted.submillisNanos));changed()
        requireRow("SELECT 1 FROM memory_session_tombstones WHERE ${same("id",expression("id"))} AND project=? AND deleted_at=? AND deleted_at_submillis_nanos=?",arrayOf(decision.project,deleted.epochMillis,deleted.submillisNanos))
    }
    private fun applyObservationMarker(decision:MemoryImportDecision){
        if(decision.rowId==-1L){val deleted=checkNotNull(time("deleted_at",true))
            sql.execSQL("INSERT INTO memory_deleted_observations(old_id,project,scope,deleted_at,producer,producer_key,portable_sync_id,deleted_at_submillis_nanos) SELECT ?,?,?,?,NULL,NULL,${expression("id")},?",
                arrayOf(decision.localId,decision.project,decision.scope,deleted.epochMillis,deleted.submillisNanos));changed()
            requireRow("SELECT 1 FROM memory_deleted_observations WHERE old_id=? AND project=? AND scope=? AND ${same("portable_sync_id",expression("id"))} AND producer IS NULL AND producer_key IS NULL AND deleted_at=? AND deleted_at_submillis_nanos=?",
                arrayOf(decision.localId,decision.project,decision.scope,deleted.epochMillis,deleted.submillisNanos))
        }
        if(decision.action==MemoryImportDecision.DELETE){
            // Same current purge primitives: preserve relation audit, orphan explicit links, remove revisions/payload.
            fun linkedCount(table:String,where:String):Long=sql.query("SELECT COUNT(*) FROM $table WHERE $where",arrayOf(decision.localId,decision.localId)).use{c->check(c.moveToFirst());c.getLong(0)}
            val relationsBefore=linkedCount("memory_relations","source_id=? OR target_id=?")
            val linksBefore=linkedCount("memory_links","from_id=? OR to_id=?")
            MemoryRelationRepository(plan.database).orphan(decision.localId,plan.now)
            sql.execSQL("UPDATE memory_links SET orphaned=1,updated_at=? WHERE from_id=? OR to_id=?",arrayOf(plan.now,decision.localId,decision.localId))
            sql.execSQL("DELETE FROM memory_revisions WHERE target_type='memory' AND target_id=?",arrayOf(decision.localId))
            sql.execSQL("DELETE FROM memory_entries WHERE id=? AND project=?",arrayOf(decision.localId,decision.project));changed()
            check(!sql.query("SELECT 1 FROM memory_entries WHERE id=?",arrayOf(decision.localId)).use{it.moveToFirst()}){"memory_import_readback_mismatch"}
            check(!sql.query("SELECT 1 FROM memory_revisions WHERE target_type='memory' AND target_id=? LIMIT 1",arrayOf(decision.localId)).use{it.moveToFirst()}){"memory_import_readback_mismatch"}
            check(linkedCount("memory_relations","source_id=? OR target_id=?")==relationsBefore&&linkedCount("memory_links","from_id=? OR to_id=?")==linksBefore){"memory_import_readback_mismatch"}
            check(!sql.query("SELECT 1 FROM memory_relations WHERE (source_id=? OR target_id=?) AND (status!='orphaned' OR updated_at!=? OR updated_at_submillis_nanos!=0) LIMIT 1",arrayOf(decision.localId,decision.localId,plan.now)).use{it.moveToFirst()}){"memory_import_readback_mismatch"}
            check(!sql.query("SELECT 1 FROM memory_links WHERE (from_id=? OR to_id=?) AND (orphaned!=1 OR updated_at!=?) LIMIT 1",arrayOf(decision.localId,decision.localId,plan.now)).use{it.moveToFirst()}){"memory_import_readback_mismatch"}
        }
    }
    private fun applyPromptMarker(decision:MemoryImportDecision){
        val deleted=checkNotNull(time("deleted_at",true))
        val session="NULLIF(${expression("session_id")},'')";val inbox="NULLIF(${expression("source_inbox_id")},'')"
        if(decision.rowId==-1L){sql.execSQL("INSERT INTO memory_prompt_tombstones(prompt_id,project,session_id,source_inbox_id,deleted_at,portable_sync_id,deleted_at_submillis_nanos) SELECT ?,?,$session,$inbox,?,${expression("sync_id")},?",
            arrayOf(decision.localId,decision.project,deleted.epochMillis,deleted.submillisNanos));changed()}
        else {
            sql.execSQL("INSERT INTO temp.jarvys_memory_import_fields(slot,value) SELECT 40,CAST(session_id AS BLOB) FROM memory_prompt_tombstones WHERE prompt_id=?",arrayOf(decision.localId));changed()
            sql.execSQL("INSERT INTO temp.jarvys_memory_import_fields(slot,value) SELECT 41,CAST(source_inbox_id AS BLOB) FROM memory_prompt_tombstones WHERE prompt_id=?",arrayOf(decision.localId));changed()
            sql.execSQL("UPDATE memory_prompt_tombstones SET session_id=COALESCE($session,session_id),source_inbox_id=COALESCE($inbox,source_inbox_id),deleted_at=? WHERE prompt_id=? AND project=?",arrayOf(deleted.epochMillis,decision.localId,decision.project));changed()
            restore("memory_prompt_tombstones","prompt_id",decision.localId,mapOf("deleted_at" to deleted))
        }
        val expectedSession=if(decision.rowId==-1L)session else "COALESCE($session,${fields.text(40)})"
        val expectedInbox=if(decision.rowId==-1L)inbox else "COALESCE($inbox,${fields.text(41)})"
        requireRow("SELECT 1 FROM memory_prompt_tombstones WHERE prompt_id=? AND project=? AND ${same("session_id",expectedSession)} AND ${same("source_inbox_id",expectedInbox)} AND ${same("COALESCE(portable_sync_id,prompt_id)",expression("sync_id"))} AND deleted_at=? AND deleted_at_submillis_nanos=?",arrayOf(decision.localId,decision.project,deleted.epochMillis,deleted.submillisNanos))
        val match="p.project=? AND (CAST(COALESCE(p.portable_sync_id,p.id) AS BLOB)=CAST(${expression("sync_id")} AS BLOB) OR ($session IS NOT NULL AND $inbox IS NOT NULL AND CAST(p.session_id AS BLOB)=CAST($session AS BLOB) AND CAST(p.source_inbox_id AS BLOB)=CAST($inbox AS BLOB)))"
        // Preserve every removed prompt's effective identity, including aliases sharing the deleted tuple.
        sql.execSQL("INSERT INTO memory_prompt_tombstones(prompt_id,session_id,source_inbox_id,project,deleted_at,portable_sync_id,deleted_at_submillis_nanos) SELECT p.id,p.session_id,p.source_inbox_id,p.project,?,COALESCE(p.portable_sync_id,p.id),? FROM memory_prompts p WHERE $match AND NOT EXISTS(SELECT 1 FROM memory_prompt_tombstones m WHERE COALESCE(m.portable_sync_id,m.prompt_id)=COALESCE(p.portable_sync_id,p.id))",
            arrayOf(deleted.epochMillis,deleted.submillisNanos,decision.project))
        changed(decision.aliases)
        // Existing alias negatives are strengthened from their own matched payload, never cleared or reparented.
        val aliasPredicate="EXISTS(SELECT 1 FROM memory_prompts p WHERE $match AND COALESCE(p.portable_sync_id,p.id)=COALESCE(memory_prompt_tombstones.portable_sync_id,memory_prompt_tombstones.prompt_id))"
        fun aliasValue(column:String):String {val supplied="(SELECT p.$column FROM memory_prompts p WHERE COALESCE(p.portable_sync_id,p.id)=COALESCE(memory_prompt_tombstones.portable_sync_id,memory_prompt_tombstones.prompt_id))"
            return "CASE WHEN ($column IS NULL OR $column='') AND $supplied IS NOT NULL AND $supplied!='' THEN $supplied ELSE $column END"}
        sql.execSQL("UPDATE memory_prompt_tombstones SET session_id=${aliasValue("session_id")},source_inbox_id=${aliasValue("source_inbox_id")},deleted_at=? WHERE project=? AND $aliasPredicate",arrayOf(deleted.epochMillis,decision.project,decision.project))
        sql.execSQL("UPDATE memory_prompt_tombstones SET deleted_at_submillis_nanos=? WHERE project=? AND $aliasPredicate",arrayOf(deleted.submillisNanos,decision.project,decision.project))
        check(!sql.query("SELECT 1 FROM memory_prompts p WHERE $match AND NOT EXISTS(SELECT 1 FROM memory_prompt_tombstones m WHERE COALESCE(m.portable_sync_id,m.prompt_id)=COALESCE(p.portable_sync_id,p.id) AND m.project=p.project AND (p.session_id IS NULL OR p.session_id='' OR CAST(m.session_id AS BLOB)=CAST(p.session_id AS BLOB)) AND (p.source_inbox_id IS NULL OR p.source_inbox_id='' OR CAST(m.source_inbox_id AS BLOB)=CAST(p.source_inbox_id AS BLOB)) AND m.deleted_at=? AND m.deleted_at_submillis_nanos=?) LIMIT 1",arrayOf(decision.project,deleted.epochMillis,deleted.submillisNanos)).use{it.moveToFirst()}){"memory_import_readback_mismatch"}
        sql.execSQL("DELETE FROM memory_prompts WHERE id IN (SELECT p.id FROM memory_prompts p WHERE $match)",arrayOf(decision.project));changed(decision.removed)
        check(!sql.query("SELECT 1 FROM memory_prompts p WHERE $match LIMIT 1",arrayOf(decision.project)).use{it.moveToFirst()}){"memory_import_readback_mismatch"}
        requireRow("SELECT 1 FROM memory_prompt_tombstones WHERE project=? AND ${same("COALESCE(portable_sync_id,prompt_id)",expression("sync_id"))}",arrayOf(decision.project))
    }
    private fun applyObservation(decision:MemoryImportDecision){
        val raw=MemoryImportObservationFields.raw(data,row,token)
        if(raw==null){fields.put(32,row.cell("title"),true);fields.put(33,MemoryImportObservationFields.topic(data,row,token)?.let(::ByteArrayInputStream));fields.put(34,row.cell("session_id"))}
        else {
            for((slot,name) in listOf(32 to "title_bytes_base64",33 to "topic_key_bytes_base64")){
                val cell=raw.cell(name);fields.put(slot,if(cell.type==MemoryImportWire.NULL)null else MemoryImportValues.base64(data.open(cell),cell.length))
            }
            fields.put(34,raw.cell("session_id"))
        }
        val title=fields.text(32);val topic=fields.text(33);val session=fields.text(34)
        val created=time("created_at")?:storedTime(decision.localId,"created_at")
        val updated=checkNotNull(time("updated_at",true));val deleted=time("deleted_at");val lastSeen=time("last_seen_at")
        val content=row.cell("content");val normalized=(if(MemoryImportValues.nullable(content))StringReader("")else data.reader(content)).use{MemoryPortableHash.compute(it,token)}
        val revision=MemoryImportValues.integer(data,row.cell("revision_count"),token)
        val duplicate=MemoryImportValues.integer(data,row.cell("duplicate_count"),token)
        val pin=if(MemoryImportValues.bool(row.cell("pinned")))1 else 0
        fun priorCounter(column:String):Long=sql.query("SELECT $column FROM memory_entries WHERE id=? AND typeof($column)='integer'",arrayOf(decision.localId)).use{c->check(c.moveToFirst());c.getLong(0).also{check(it>0){"memory_import_local_counter_invalid"}}}
        val expectedRevision=if(revision>0)revision else if(decision.action==MemoryImportDecision.INSERT)1L else priorCounter("revision_count")
        val expectedDuplicate=if(duplicate>0)duplicate else if(decision.action==MemoryImportDecision.INSERT)1L else priorCounter("duplicate_count")
        val unchanged=listOf("id","layer","day","category","importance","triggers","provenance","source_ref","usage_count","last_used_at","kind","narration","event_at","source_conversation_id","source_message_id","through_seq","project","superseded_by_id","portable_sync_id")
        if(decision.action==MemoryImportDecision.UPDATE){
            sql.execSQL("INSERT INTO temp.jarvys_memory_import_fields(slot,value) SELECT 35,CAST(portable_superseded_by_sync_id AS BLOB) FROM memory_entries WHERE id=?",arrayOf(decision.localId));changed()
            unchanged.forEachIndexed{index,column->sql.execSQL("INSERT INTO temp.jarvys_memory_import_fields(slot,value) SELECT ${40+index},$column FROM memory_entries WHERE id=?",arrayOf(decision.localId));changed()}
        }
        val lineage=expression("superseded_by_sync_id")
        val nonemptyLineage="CASE WHEN length(CAST($lineage AS BLOB))>0 THEN $lineage ELSE NULL END"
        if(decision.action==MemoryImportDecision.INSERT){
            sql.execSQL("INSERT INTO memory_entries(id,layer,day,text,category,importance,triggers,provenance,source_ref,usage_count,last_used_at,created_at,updated_at,deleted_at,kind,topic_key,title,narration,event_at,source_conversation_id,source_message_id,through_seq,project,scope,observation_type,revision_count,normalized_hash,duplicate_count,last_seen_at,pinned,memory_session_id,tool_name,review_after,superseded_by_id,portable_sync_id,portable_superseded_by_sync_id,created_at_submillis_nanos,updated_at_submillis_nanos,deleted_at_submillis_nanos,last_seen_at_submillis_nanos) SELECT ?,'curated',NULL,${scalar("content")},'imported',0.5,'[]','user',NULL,0,NULL,?,?,?,'note',$topic,$title,'none',NULL,NULL,NULL,NULL,?,?,${scalar("type")},?,?,?,? ,?,$session,${expression("tool_name")},${expression("review_after")},NULL,${expression("sync_id")},$nonemptyLineage,?,?,?,?",
                arrayOf(decision.localId,created.epochMillis,updated.epochMillis,deleted?.epochMillis,decision.project,decision.scope,revision.coerceAtLeast(1),normalized,duplicate.coerceAtLeast(1),lastSeen?.epochMillis,pin,created.submillisNanos,updated.submillisNanos,deleted?.submillisNanos?:0,lastSeen?.submillisNanos?:0));changed()
        }else {
            sql.execSQL("UPDATE memory_entries SET text=${scalar("content")},title=$title,topic_key=$topic,memory_session_id=$session,observation_type=${scalar("type")},tool_name=${expression("tool_name")},scope=?,revision_count=CASE WHEN ?>0 THEN ? ELSE revision_count END,duplicate_count=CASE WHEN ?>0 THEN ? ELSE duplicate_count END,normalized_hash=?,created_at=?,updated_at=?,deleted_at=?,last_seen_at=?,review_after=${expression("review_after")},pinned=?,portable_superseded_by_sync_id=COALESCE($nonemptyLineage,portable_superseded_by_sync_id) WHERE id=? AND project=?",
                arrayOf(decision.scope,revision,revision,duplicate,duplicate,normalized,created.epochMillis,updated.epochMillis,deleted?.epochMillis,lastSeen?.epochMillis,pin,decision.localId,decision.project));changed()
            restore("memory_entries","id",decision.localId,linkedMapOf("created_at" to created,"updated_at" to updated,"deleted_at" to deleted,"last_seen_at" to lastSeen))
        }
        requireRow("SELECT 1 FROM memory_entries WHERE id=? AND project=? AND scope=? AND ${same("text",scalar("content"))} AND ${same("title",title)} AND ${same("topic_key",topic)} AND ${same("memory_session_id",session)} AND ${same("observation_type",scalar("type"))} AND ${same("tool_name",expression("tool_name"))} AND ${same("review_after",expression("review_after"))} AND normalized_hash=? AND pinned=? AND created_at=? AND updated_at=? AND deleted_at IS ? AND last_seen_at IS ? AND created_at_submillis_nanos=? AND updated_at_submillis_nanos=? AND deleted_at_submillis_nanos=? AND last_seen_at_submillis_nanos=?",
            arrayOf(decision.localId,decision.project,decision.scope,normalized,pin,created.epochMillis,updated.epochMillis,deleted?.epochMillis,lastSeen?.epochMillis,created.submillisNanos,updated.submillisNanos,deleted?.submillisNanos?:0,lastSeen?.submillisNanos?:0))
        requireRow("SELECT 1 FROM memory_entries WHERE id=? AND revision_count=? AND duplicate_count=?",arrayOf(decision.localId,expectedRevision,expectedDuplicate))
        if(decision.action==MemoryImportDecision.UPDATE){
            val expectedLineage="COALESCE($nonemptyLineage,${fields.text(35)})"
            requireRow("SELECT 1 FROM memory_entries WHERE id=? AND ${same("portable_superseded_by_sync_id",expectedLineage)}",arrayOf(decision.localId))
            unchanged.forEachIndexed{index,column->val expected="(SELECT value FROM temp.jarvys_memory_import_fields WHERE slot=${40+index})"
                requireRow("SELECT 1 FROM memory_entries WHERE id=? AND typeof($column)=typeof($expected) AND $column IS $expected AND ${same(column,expected)}",arrayOf(decision.localId))}
        }
        if(decision.action==MemoryImportDecision.INSERT)requireRow("SELECT 1 FROM memory_entries WHERE id=? AND provenance='user' AND kind='note' AND category='imported' AND source_ref IS NULL AND source_conversation_id IS NULL AND source_message_id IS NULL AND through_seq IS NULL AND superseded_by_id IS NULL AND ${same("portable_sync_id",expression("sync_id"))} AND ${same("portable_superseded_by_sync_id",nonemptyLineage)}",arrayOf(decision.localId))
    }
    private fun applyPrompt(decision:MemoryImportDecision){
        val inbox="NULLIF(${expression("source_inbox_id")},'')"
        if(decision.action==MemoryImportDecision.ADOPT){
            sql.execSQL("UPDATE memory_prompts SET source_inbox_id=$inbox WHERE id=? AND project=? AND (source_inbox_id IS NULL OR source_inbox_id='')",arrayOf(decision.localId,decision.project));changed()
        }else{val created=checkNotNull(time("created_at",true))
            sql.execSQL("INSERT INTO memory_prompts(id,session_id,project,content,source_inbox_id,created_at,portable_sync_id,created_at_submillis_nanos) SELECT ?,${expression("session_id")},?,${scalar("content")},$inbox,?,${expression("sync_id")},?",arrayOf(decision.localId,decision.project,created.epochMillis,created.submillisNanos));changed()
            requireRow("SELECT 1 FROM memory_prompts WHERE id=? AND ${same("content",scalar("content"))} AND created_at=? AND created_at_submillis_nanos=?",arrayOf(decision.localId,created.epochMillis,created.submillisNanos))
        }
        requireRow("SELECT 1 FROM memory_prompts WHERE id=? AND project=? AND ${same("session_id",expression("session_id"))} AND ${same("source_inbox_id",inbox)} AND ${same("COALESCE(portable_sync_id,id)",expression("sync_id"))}",arrayOf(decision.localId,decision.project))
    }
    private fun applyRelation(decision:MemoryImportDecision){
        val created=checkNotNull(time("created_at",true));val updated=checkNotNull(time("updated_at",true));val superseded=time("superseded_at")
        val confidence=MemoryImportValues.decimal(data,row.cell("confidence"),token)
        val status=if(MemoryImportValues.enumValue(data,row.cell("judgment_status"),setOf("orphaned"),null,true,token)=="orphaned")"'orphaned'" else scalar("judgment_status")
        sql.execSQL("INSERT INTO memory_relations(relation_id,source_id,target_id,project,scope,relation,status,created_at,updated_at,reason,evidence,confidence,marked_by_actor,marked_by_kind,marked_by_model,memory_session_id,portable_sync_id,superseded_at,created_at_submillis_nanos,updated_at_submillis_nanos,superseded_at_submillis_nanos) SELECT ?,?,?,?,?,${scalar("relation")},$status,?,?,${expression("reason")},${expression("evidence")},?,${expression("marked_by_actor")},${expression("marked_by_kind")},${expression("marked_by_model")},${expression("session_id")},${expression("sync_id")},?,?,?,?",
            arrayOf(decision.localId,decision.sourceId,decision.targetId,decision.project,decision.scope,created.epochMillis,updated.epochMillis,confidence,superseded?.epochMillis,created.submillisNanos,updated.submillisNanos,superseded?.submillisNanos?:0));changed()
        requireRow("SELECT 1 FROM memory_relations WHERE relation_id=? AND source_id=? AND target_id=? AND project=? AND scope=? AND ${same("relation",scalar("relation"))} AND ${same("status",status)} AND created_at=? AND updated_at=? AND superseded_at IS ? AND confidence IS ? AND ${same("reason",expression("reason"))} AND ${same("evidence",expression("evidence"))} AND ${same("marked_by_actor",expression("marked_by_actor"))} AND ${same("marked_by_kind",expression("marked_by_kind"))} AND ${same("marked_by_model",expression("marked_by_model"))} AND ${same("memory_session_id",expression("session_id"))} AND ${same("portable_sync_id",expression("sync_id"))} AND created_at_submillis_nanos=? AND updated_at_submillis_nanos=? AND superseded_at_submillis_nanos=?",
            arrayOf(decision.localId,decision.sourceId,decision.targetId,decision.project,decision.scope,created.epochMillis,updated.epochMillis,superseded?.epochMillis,confidence,created.submillisNanos,updated.submillisNanos,superseded?.submillisNanos?:0))
    }
    private fun superseder(decision:MemoryImportDecision){
        val cell=row.cell("superseded_by_relation_sync_id")
        if(MemoryImportValues.nullable(cell))return
        val target=lookup.find(Domain.RELATION,cell)?:error("memory_import_relation_superseder_missing")
        require(target.project==decision.project){"memory_import_relation_project_conflict"}
        if(decision.action!=MemoryImportDecision.INSERT)return
        val targetId=lookup.localUuid(target)
        sql.execSQL("UPDATE memory_relations SET superseded_by_relation_id=?,portable_superseded_by_relation_sync_id=${expression("superseded_by_relation_sync_id")} WHERE relation_id=?",arrayOf(targetId,decision.localId));changed()
        requireRow("SELECT 1 FROM memory_relations WHERE relation_id=? AND superseded_by_relation_id=? AND ${same("portable_superseded_by_relation_sync_id",expression("superseded_by_relation_sync_id"))}",arrayOf(decision.localId,targetId))
    }
    private fun execute(decision:MemoryImportDecision){
        if(decision.action in setOf(MemoryImportDecision.SKIP,MemoryImportDecision.BLOCKED))return
        stage(MemoryImportDecision.record(plan,decision,token))
        when(decision.domain){MemoryImportWire.SESSIONS->applySession(decision);MemoryImportWire.SESSION_MARKERS->applySessionMarker(decision)
            MemoryImportWire.OBS_MARKERS->applyObservationMarker(decision);MemoryImportWire.PROMPT_MARKERS->applyPromptMarker(decision)
            MemoryImportWire.OBSERVATIONS->applyObservation(decision);MemoryImportWire.PROMPTS->applyPrompt(decision);MemoryImportWire.RELATIONS->applyRelation(decision)}
        token.throwIfCancelled()
    }
    fun run(){
        fields.create()
        for(domain in MemoryImportEngine.ORDER){
            if(domain==MemoryImportWire.OBSERVATIONS){
                // Confirmed retirements first free only their own unique topic; no inferred deletion.
                MemoryImportDecision.each(plan,token){decision->if(decision.domain==domain&&decision.action==MemoryImportDecision.UPDATE){
                    val record=MemoryImportDecision.record(plan,decision,token)
                    if(!MemoryImportValues.nullable(record.cell("deleted_at")))execute(decision)
                }}
            }
            MemoryImportDecision.each(plan,token){decision->if(decision.domain==domain){
                if(domain==MemoryImportWire.OBSERVATIONS&&decision.action==MemoryImportDecision.UPDATE){
                    val record=MemoryImportDecision.record(plan,decision,token)
                    if(!MemoryImportValues.nullable(record.cell("deleted_at")))return@each
                }
                execute(decision)
            }}
        }
        MemoryImportDecision.each(plan,token){decision->if(decision.domain==MemoryImportWire.RELATIONS){stage(MemoryImportDecision.record(plan,decision,token));superseder(decision)}}
        fields.drop();token.throwIfCancelled()
    }
}
