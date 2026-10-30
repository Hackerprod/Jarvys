package com.jarvys.agent.chatdb

import android.database.Cursor
import com.jarvys.agent.*
import com.jarvys.agent.MemoryImportLookup.Domain
import com.jarvys.agent.MemoryImportLookup.OwnedRow
import java.io.*
import java.security.MessageDigest

/** Import-specific admission and impact CAS. Every current read occurs under the caller's one Room snapshot. */
internal class MemoryImportEngine(private val plan:MemoryImportPlan,private val token:CancellationToken) {
    private val data=plan.data
    private val sql=plan.database.chatSql
    private val owners=plan.namespace.authorizedProjects()
    private val lookup=MemoryImportLookup(sql,data,owners,token)
    private val digest=MessageDigest.getInstance("SHA-256")
    private fun hash(value:String){val bytes=value.toByteArray(Charsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)}
    private fun observed(domain:Domain,cell:MemoryImportCell):OwnedRow? {
        val row=lookup.find(domain,cell);hash(domain.name);hash(if(row==null)"absent" else lookup.fingerprint(row));return row
    }
    private fun owned(row:OwnedRow){hash(row.domain.name);hash(lookup.fingerprint(row))}
    private fun id(row:MemoryImportRecord)=MemoryImportValidation.identity(row)
    private fun file(domain:Int,cell:MemoryImportCell)=MemoryImportValidation.find(data,domain,cell,token)
    private fun destination(row:MemoryImportRecord):String=plan.mapping.destination(row,token).also {project->
        require(project in owners){"memory_import_owner_denied"};hash(project)
        plan.mapping.anchor(row,token)?.let{anchor->
            val primary=when(row.domain){MemoryImportWire.SESSIONS->Domain.SESSION;MemoryImportWire.OBSERVATIONS->Domain.OBSERVATION;MemoryImportWire.PROMPTS->Domain.PROMPT;MemoryImportWire.PROMPT_MARKERS->Domain.PROMPT_MARKER;MemoryImportWire.OBS_MARKERS->Domain.OBSERVATION_MARKER;MemoryImportWire.SESSION_MARKERS->Domain.SESSION_MARKER;else->error("memory_import_mapping_changed")}
            var current=observed(primary,id(row))?.project
            if(current==null){val fallback=when(row.domain){MemoryImportWire.OBS_MARKERS->Domain.OBSERVATION;MemoryImportWire.PROMPT_MARKERS->Domain.PROMPT;MemoryImportWire.SESSION_MARKERS->Domain.SESSION;else->null};fallback?.let{current=observed(it,id(row))?.project}}
            if(current==null&&row.domain in setOf(MemoryImportWire.OBSERVATIONS,MemoryImportWire.PROMPTS,MemoryImportWire.PROMPT_MARKERS)){
                val session=row.cell("session_id");if(!MemoryImportValues.blank(data,session,token))current=observed(Domain.SESSION,session)?.project
            }
            require(current==anchor&&anchor==project){"memory_import_mapping_changed"}
        }
    }
    private fun requireProject(row:OwnedRow?,project:String){require(row==null||row.project==project){"memory_import_identity_project_conflict"}}
    private fun local(row:MemoryImportRecord,current:OwnedRow?):String=current?.let(lookup::localUuid)?:plan.prospective(row,token)
    private fun number(row:OwnedRow,column:String,nullable:Boolean=false):Long? {
        require(column in setOf("created_at","updated_at","deleted_at","last_seen_at","revision_count","duplicate_count","created_at_submillis_nanos","updated_at_submillis_nanos","deleted_at_submillis_nanos","last_seen_at_submillis_nanos"))
        return sql.query("SELECT typeof($column),CASE WHEN typeof($column)='integer' THEN $column END FROM ${row.domain.table} WHERE rowid=?",arrayOf(row.rowId)).use {c->
            check(c.moveToFirst());if(c.getString(0)=="null"){check(nullable){"memory_import_local_type_invalid"};null}else{check(c.getString(0)=="integer"&&c.getType(1)==Cursor.FIELD_TYPE_INTEGER){"memory_import_local_type_invalid"};c.getLong(1)}
        }
    }
    private fun currentTime(row:OwnedRow,name:String,nullable:Boolean=false):MemoryPortableTime.Value? {
        val millis=number(row,name,nullable);val remainder=number(row,name+"_submillis_nanos")!!
        check(remainder in 0..999999 && (millis!=null||remainder==0L)){"memory_import_local_time_invalid"}
        MemoryPortableTime.format(millis,remainder.toInt())
        return millis?.let{MemoryPortableTime.Value(it,remainder.toInt())}
    }
    private fun time(row:MemoryImportRecord,name:String,required:Boolean=false,allowInvalid:Boolean=false):MemoryPortableTime.Value? {
        val cell=row.cell(name)
        if(MemoryImportValues.nullable(cell)){require(!required){"memory_import_time_required"};return null}
        val result=data.reader(cell).use{MemoryPortableTime.parse(it,token)}
        require(result!=null||allowInvalid){"memory_import_time_invalid"};return result
    }
    private fun ordinary(row:OwnedRow) {
        check(sql.query("SELECT 1 FROM memory_entries WHERE rowid=? AND provenance IN ('user','agent') AND kind='note' AND NOT EXISTS(SELECT 1 FROM memory_evidence WHERE entry_id=memory_entries.id)",arrayOf(row.rowId)).use{it.moveToFirst()}){"memory_import_protected_target"}
    }
    private fun scope(row:MemoryImportRecord)=MemoryImportValues.enumValue(data,row.cell("scope"),setOf("personal","global","project"),"project",true,token)?:"project"
    private data class Session(val project:String,val mode:String)
    private fun session(cell:MemoryImportCell):Session {
        require(!MemoryImportValues.blank(data,cell,token)){"memory_import_session_required"}
        val current=observed(Domain.SESSION,cell)
        val incoming=file(MemoryImportWire.SESSIONS,cell)
        if(current!=null){
            if(incoming!=null)requireProject(current,destination(incoming))
            val mode=lookup.smallText(current,"ownership_mode",setOf("shared","project_owned"))?:error("memory_import_local_type_invalid")
            return Session(current.project,mode)
        }
        require(incoming!=null){"memory_import_session_missing"}
        return Session(destination(incoming),MemoryImportValues.ownershipMode(data,incoming.cell("ownership_mode"),token))
    }
    private fun observationSession(row:MemoryImportRecord,project:String) {
        val cell=MemoryImportObservationFields.session(data,row,token)
        if(cell.type==MemoryImportWire.NULL&&data.hasExtension)return
        val associated=session(cell)
        require(associated.mode=="shared"||associated.project==project){"memory_import_session_project_conflict"}
    }
    private fun incomingNewer(row:MemoryImportRecord,current:OwnedRow):Boolean {
        val incoming=time(row,"updated_at",allowInvalid=true)?:return false
        return incoming>checkNotNull(currentTime(current,"updated_at"))
    }
    private fun hasMarker(domain:Domain,wire:Int,identity:MemoryImportCell,project:String):Boolean {
        val retained=observed(domain,identity);requireProject(retained,project)
        val supplied=file(wire,identity)
        if(supplied!=null)require(destination(supplied)==project){"memory_import_identity_project_conflict"}
        return retained!=null||supplied!=null
    }
    private fun topic(row:MemoryImportRecord):MemoryImportLookup.ByteValue? {
        val raw=MemoryImportObservationFields.raw(data,row,token)
        if(raw!=null){val cell=raw.cell("topic_key_bytes_base64");if(cell.type==MemoryImportWire.NULL)return null
            return MemoryImportLookup.ByteValue(MemoryImportObservationFields.decodedLength(data,cell,token)){MemoryImportValues.base64(data.open(cell),cell.length)}}
        val bytes=MemoryImportObservationFields.topic(data,row,token)?:return null
        return MemoryImportLookup.ByteValue(bytes.size.toLong()){ByteArrayInputStream(bytes)}
    }
    private fun sameBytes(a:MemoryImportLookup.ByteValue?,b:MemoryImportLookup.ByteValue?):Boolean {
        if(a==null||b==null)return a==null&&b==null;if(a.length!=b.length)return false
        a.open().use{x->b.open().use{y->val left=ByteArray(8192);val right=ByteArray(8192);var remaining=a.length
            while(remaining>0){token.throwIfCancelled();val n=minOf(remaining,8192).toInt();readFully(x,left,n);readFully(y,right,n)
                for(i in 0 until n)if(left[i]!=right[i])return false;remaining-=n};return true}}
    }
    private fun readFully(input:InputStream,bytes:ByteArray,n:Int){var at=0;while(at<n){token.throwIfCancelled();val read=input.read(bytes,at,n-at);check(read>0);at+=read}}
    private fun clearsOccupant(occupant:OwnedRow,project:String):Boolean {
        var clearing=false
        data.records(token,MemoryImportWire.OBS_MARKERS){marker->if(lookup.equalCell(occupant.domain.table,occupant.rowId,occupant.domain.effectiveID,id(marker))){
            require(destination(marker)==project);ordinary(occupant);clearing=true}}
        data.records(token,MemoryImportWire.OBSERVATIONS){candidate->if(lookup.equalCell(occupant.domain.table,occupant.rowId,occupant.domain.effectiveID,id(candidate))&&incomingNewer(candidate,occupant)){
            require(destination(candidate)==project);if(time(candidate,"deleted_at")!=null){ordinary(occupant);clearing=true}
        }}
        return clearing
    }
    private fun checkTopic(row:MemoryImportRecord,current:OwnedRow?,project:String) {
        if(time(row,"deleted_at")!=null)return
        val topic=topic(row)?:return
        lookup.findByFields(Domain.OBSERVATION,listOf("topic_key" to topic),mapOf("project" to project,"scope" to scope(row))){occupant->
            owned(occupant)
            require(occupant.rowId==current?.rowId||clearsOccupant(occupant,project)){"memory_import_topic_conflict"}
        }
        data.records(token,MemoryImportWire.OBSERVATIONS){other->if(other.ordinal<row.ordinal&&destination(other)==project&&scope(other)==scope(row)){
            val retained=observed(Domain.OBSERVATION,id(other));requireProject(retained,project)
            if((retained==null||incomingNewer(other,retained))&&!hasMarker(Domain.OBSERVATION_MARKER,MemoryImportWire.OBS_MARKERS,id(other),project)&&time(other,"deleted_at")==null)
                require(!sameBytes(topic,topic(other))){"memory_import_topic_conflict"}
        }}
    }
    private data class Endpoint(val local:String,val project:String,val scope:String)
    private fun endpoint(cell:MemoryImportCell):Endpoint {
        val retained=observed(Domain.OBSERVATION,cell)
        val incoming=file(MemoryImportWire.OBSERVATIONS,cell)
        val project=incoming?.let{destination(it)}?:retained?.project?:error("memory_import_relation_endpoint_missing")
        requireProject(retained,project)
        require(!hasMarker(Domain.OBSERVATION_MARKER,MemoryImportWire.OBS_MARKERS,cell,project)){"memory_import_relation_endpoint_missing"}
        val local=retained?.let(lookup::localUuid)?:plan.prospective(checkNotNull(incoming),token)
        val scope=if(incoming!=null&&(retained==null||incomingNewer(incoming,retained)))scope(incoming)
            else lookup.smallText(checkNotNull(retained),"scope",setOf("project","personal","global"))?:error("memory_import_local_type_invalid")
        return Endpoint(local,project,scope)
    }
    private fun promptTuple(row:MemoryImportRecord,project:String,domain:Domain,visit:(OwnedRow)->Unit) {
        val inbox=row.cell("source_inbox_id");if(MemoryImportValues.nullable(inbox)||data.equals(inbox,"",token))return
        val associated=row.cell("session_id");require(!MemoryImportValues.blank(data,associated,token)){"memory_import_session_required"}
        lookup.findByFields(domain,listOf("session_id" to lookup.bytes(associated),"source_inbox_id" to lookup.bytes(inbox)),mapOf("project" to project)){owned(it);visit(it)}
    }
    private fun markerTupleInFile(row:MemoryImportRecord,project:String):Boolean {
        val inbox=row.cell("source_inbox_id");if(MemoryImportValues.nullable(inbox)||data.equals(inbox,"",token))return false
        var match=false
        data.records(token,MemoryImportWire.PROMPT_MARKERS){marker->if(data.equal(marker.cell("session_id"),row.cell("session_id"),token)&&data.equal(marker.cell("source_inbox_id"),inbox,token)){
            require(destination(marker)==project){"memory_import_identity_project_conflict"};match=true}}
        return match
    }
    private fun decision(row:MemoryImportRecord):MemoryImportDecision {
        token.throwIfCancelled()
        val domain=when(row.domain){MemoryImportWire.SESSIONS->Domain.SESSION;MemoryImportWire.OBSERVATIONS->Domain.OBSERVATION
            MemoryImportWire.PROMPTS->Domain.PROMPT;MemoryImportWire.RELATIONS->Domain.RELATION;MemoryImportWire.OBS_MARKERS->Domain.OBSERVATION_MARKER
            MemoryImportWire.PROMPT_MARKERS->Domain.PROMPT_MARKER;MemoryImportWire.SESSION_MARKERS->Domain.SESSION_MARKER;else->error("memory_import_domain_invalid")}
        val retained=observed(domain,id(row))
        var project=if(row.domain==MemoryImportWire.RELATIONS)"" else destination(row)
        var source="";var target="";var rowScope="project";var removed=0L;var aliases=0L;var futureMarker=false;var allowedCounterpart:Long?=null
        if(row.domain==MemoryImportWire.RELATIONS){val a=endpoint(row.cell("source_id"));val b=endpoint(row.cell("target_id"))
            require(a.project==b.project){"memory_import_relation_project_conflict"};project=a.project;rowScope=a.scope;source=a.local;target=b.local}
        requireProject(retained,project)
        var localId=if(domain==Domain.SESSION||domain==Domain.SESSION_MARKER)"" else local(row,retained)
        var action=if(retained==null)MemoryImportDecision.INSERT else MemoryImportDecision.SKIP
        when(row.domain){
            MemoryImportWire.SESSIONS->{
                val marker=observed(Domain.SESSION_MARKER,id(row));requireProject(marker,project)
                if(retained==null){time(row,"started_at",true);time(row,"ended_at")}
                if(marker!=null&&retained!=null)requireDescriptiveSession(retained)
            }
            MemoryImportWire.SESSION_MARKERS->{
                val associated=observed(Domain.SESSION,id(row));requireProject(associated,project);associated?.let(::requireDescriptiveSession)
                if(retained==null)time(row,"deleted_at",true)
            }
            MemoryImportWire.OBSERVATIONS->{
                rowScope=scope(row)
                if(hasMarker(Domain.OBSERVATION_MARKER,MemoryImportWire.OBS_MARKERS,id(row),project))action=MemoryImportDecision.BLOCKED
                else if(retained!=null&&!incomingNewer(row,retained))action=MemoryImportDecision.SKIP
                else {
                    action=if(retained==null)MemoryImportDecision.INSERT else MemoryImportDecision.UPDATE
                    retained?.let{ordinary(it);hash(MemoryImportImpact.observation(sql,localId,false,token))};observationSession(row,project)
                    time(row,"created_at",retained==null,retained!=null);time(row,"updated_at",true);time(row,"deleted_at");time(row,"last_seen_at")
                    if(retained!=null&&!MemoryImportValues.nullable(row.cell("review_after")))require(!sql.query("SELECT 1 FROM memory_review_clear_events WHERE observation_id=? LIMIT 1",arrayOf(localId)).use{it.moveToFirst()}){"memory_import_review_clear_conflict"}
                    checkTopic(row,retained,project)
                }
            }
            MemoryImportWire.OBS_MARKERS->{
                rowScope=scope(row);val targetRow=observed(Domain.OBSERVATION,id(row));requireProject(targetRow,project);allowedCounterpart=targetRow?.rowId
                if(retained!=null)require(lookup.smallText(retained,"scope",setOf("project","personal","global"))==rowScope){"memory_import_identity_scope_conflict"}
                if(targetRow!=null){ordinary(targetRow);localId=lookup.localUuid(targetRow);action=MemoryImportDecision.DELETE;removed=1;hash(MemoryImportImpact.observation(sql,localId,true,token))
                    val actualScope=lookup.smallText(targetRow,"scope",setOf("project","personal","global"));require(actualScope==rowScope){"memory_import_identity_scope_conflict"}}
                else if(retained==null){file(MemoryImportWire.OBSERVATIONS,id(row))?.let{localId=plan.prospective(it,token)}}
                if(retained==null)time(row,"deleted_at",true)
            }
            MemoryImportWire.PROMPT_MARKERS->{
                val sessionId=row.cell("session_id");val inbox=row.cell("source_inbox_id")
                if(retained!=null)action=MemoryImportDecision.UPDATE
                time(row,"deleted_at",true)
                if(!MemoryImportValues.blank(data,sessionId,token)){
                    val knownSession=observed(Domain.SESSION,sessionId);requireProject(knownSession,project)
                    file(MemoryImportWire.SESSIONS,sessionId)?.let{require(destination(it)==project){"memory_import_session_project_conflict"}}
                }else require(MemoryImportValues.nullable(inbox)||data.equals(inbox,"",token)){"memory_import_session_required"}
                val prompt=observed(Domain.PROMPT,id(row));requireProject(prompt,project);allowedCounterpart=prompt?.rowId
                if(prompt!=null){
                    if(!MemoryImportValues.nullable(sessionId)&&!data.equals(sessionId,"",token))require(lookup.equalCell(Domain.PROMPT.table,prompt.rowId,"session_id",sessionId)){"memory_import_prompt_identity_conflict"}
                    if(!MemoryImportValues.nullable(inbox)&&!data.equals(inbox,"",token))require(lookup.equalCell(Domain.PROMPT.table,prompt.rowId,"source_inbox_id",inbox)){"memory_import_prompt_identity_conflict"}
                    localId=lookup.localUuid(prompt);action=MemoryImportDecision.DELETE;removed=1
                }
                if(retained!=null){
                    if(prompt!=null){require(lookup.localUuid(retained)==lookup.localUuid(prompt)){"memory_import_prompt_identity_conflict"}
                        require(sql.query("SELECT 1 FROM memory_prompt_tombstones m JOIN memory_prompts p ON p.rowid=? WHERE m.rowid=? AND (m.session_id IS NULL OR m.session_id='' OR CAST(m.session_id AS BLOB)=CAST(p.session_id AS BLOB)) AND (m.source_inbox_id IS NULL OR m.source_inbox_id='' OR CAST(m.source_inbox_id AS BLOB)=CAST(p.source_inbox_id AS BLOB))",arrayOf(prompt.rowId,retained.rowId)).use{it.moveToFirst()}){"memory_import_prompt_identity_conflict"}}
                    if(!MemoryImportValues.nullable(sessionId)&&!data.equals(sessionId,"",token))require(sql.query("SELECT 1 FROM memory_prompt_tombstones WHERE rowid=? AND (session_id IS NULL OR session_id='')",arrayOf(retained.rowId)).use{it.moveToFirst()}||lookup.equalCell(domain.table,retained.rowId,"session_id",sessionId)){"memory_import_prompt_identity_conflict"}
                    if(!MemoryImportValues.nullable(inbox)&&!data.equals(inbox,"",token))require(sql.query("SELECT 1 FROM memory_prompt_tombstones WHERE rowid=? AND (source_inbox_id IS NULL OR source_inbox_id='')",arrayOf(retained.rowId)).use{it.moveToFirst()}||lookup.equalCell(domain.table,retained.rowId,"source_inbox_id",inbox)){"memory_import_prompt_identity_conflict"}
                }
                fun coveredByEarlier(candidate:OwnedRow):Boolean {
                    var covered=false
                    data.records(token,MemoryImportWire.PROMPT_MARKERS){prior->if(prior.ordinal<row.ordinal){
                        val exact=lookup.equalCell(Domain.PROMPT.table,candidate.rowId,Domain.PROMPT.effectiveID,id(prior))
                        val priorInbox=prior.cell("source_inbox_id");val priorSession=prior.cell("session_id")
                        val tuple=!MemoryImportValues.nullable(priorInbox)&&!data.equals(priorInbox,"",token)&&!MemoryImportValues.blank(data,priorSession,token)&&
                            lookup.equalCell(Domain.PROMPT.table,candidate.rowId,"session_id",priorSession)&&lookup.equalCell(Domain.PROMPT.table,candidate.rowId,"source_inbox_id",priorInbox)
                        if(exact||tuple){require(destination(prior)==project){"memory_import_identity_project_conflict"};covered=true}
                    }}
                    return covered
                }
                if(prompt!=null&&coveredByEarlier(prompt)){removed=0;futureMarker=retained==null}
                fun alias(candidate:OwnedRow){
                    if(coveredByEarlier(candidate))return
                    val markerRow=sql.query("SELECT m.rowid FROM memory_prompt_tombstones m JOIN memory_prompts p ON COALESCE(m.portable_sync_id,m.prompt_id)=COALESCE(p.portable_sync_id,p.id) WHERE p.rowid=?",arrayOf(candidate.rowId)).use{c->if(c.moveToFirst())c.getLong(0)else null}
                    if(markerRow==null){
                        // The primary incoming marker itself covers an exact-ID payload; only other identities need aliases.
                        if(candidate.rowId!=prompt?.rowId)aliases=Math.addExact(aliases,1)
                    }else {
                        val marker=OwnedRow(markerRow,lookup.owner(markerRow,Domain.PROMPT_MARKER.table),Domain.PROMPT_MARKER);owned(marker);requireProject(marker,project)
                        require(sql.query("SELECT 1 FROM memory_prompt_tombstones m JOIN memory_prompts p ON p.rowid=? WHERE m.rowid=? AND (m.session_id IS NULL OR m.session_id='' OR CAST(m.session_id AS BLOB)=CAST(p.session_id AS BLOB)) AND (m.source_inbox_id IS NULL OR m.source_inbox_id='' OR CAST(m.source_inbox_id AS BLOB)=CAST(p.source_inbox_id AS BLOB))",arrayOf(candidate.rowId,markerRow)).use{it.moveToFirst()}){"memory_import_prompt_identity_conflict"}
                    }
                }
                prompt?.let(::alias)
                promptTuple(row,project,Domain.PROMPT){candidate->action=MemoryImportDecision.DELETE;if(candidate.rowId!=prompt?.rowId&&!coveredByEarlier(candidate)){removed=Math.addExact(removed,1);alias(candidate)}}
                if(retained==null)time(row,"deleted_at",true)
            }
            MemoryImportWire.PROMPTS->{
                require(session(row.cell("session_id")).project==project){"memory_import_session_project_conflict"}
                var blocked=hasMarker(Domain.PROMPT_MARKER,MemoryImportWire.PROMPT_MARKERS,id(row),project)||markerTupleInFile(row,project)
                promptTuple(row,project,Domain.PROMPT_MARKER){blocked=true}
                if(blocked)action=MemoryImportDecision.BLOCKED else {
                    val inbox=row.cell("source_inbox_id");val nonempty=!MemoryImportValues.nullable(inbox)&&!data.equals(inbox,"",token)
                    if(retained!=null){
                        require(lookup.equalCell(domain.table,retained.rowId,"session_id",row.cell("session_id"))){"memory_import_prompt_identity_conflict"}
                        if(nonempty){
                            val empty=sql.query("SELECT 1 FROM memory_prompts WHERE rowid=? AND (source_inbox_id IS NULL OR source_inbox_id='')",arrayOf(retained.rowId)).use{it.moveToFirst()}
                            if(empty)action=MemoryImportDecision.ADOPT else require(lookup.equalCell(domain.table,retained.rowId,"source_inbox_id",inbox)){"memory_import_prompt_identity_conflict"}
                        }
                    }else time(row,"created_at",true)
                    promptTuple(row,project,Domain.PROMPT){require(it.rowId==retained?.rowId){"memory_import_prompt_tuple_conflict"}}
                    if(nonempty)data.records(token,MemoryImportWire.PROMPTS){other->if(other.ordinal<row.ordinal&&data.equal(other.cell("session_id"),row.cell("session_id"),token)&&data.equal(other.cell("source_inbox_id"),inbox,token))error("memory_import_prompt_tuple_conflict")}
                }
            }
            MemoryImportWire.RELATIONS->{
                if(retained==null){time(row,"created_at",true);time(row,"updated_at",true);time(row,"superseded_at")}
                val superseder=row.cell("superseded_by_relation_sync_id")
                if(!MemoryImportValues.nullable(superseder)){
                    val existing=observed(Domain.RELATION,superseder)
                    if(existing!=null)requireProject(existing,project) else {
                        val included=file(MemoryImportWire.RELATIONS,superseder)?:error("memory_import_relation_superseder_missing")
                        require(endpoint(included.cell("source_id")).project==project&&endpoint(included.cell("target_id")).project==project){"memory_import_relation_project_conflict"}
                    }
                }
            }
        }
        if(retained==null&&!futureMarker&&domain !in setOf(Domain.SESSION,Domain.SESSION_MARKER)&&action!=MemoryImportDecision.BLOCKED){
            val occupied=sql.query("SELECT 1 FROM ${domain.table} WHERE ${domain.localKey}=? LIMIT 1",arrayOf(localId)).use{it.moveToFirst()}
            require(!occupied){"memory_import_local_identity_conflict"};hash("prospective-local-absence");hash(localId)
        }
        val counterpart=when(domain){Domain.OBSERVATION->Domain.OBSERVATION_MARKER;Domain.PROMPT->Domain.PROMPT_MARKER;Domain.OBSERVATION_MARKER->Domain.OBSERVATION;Domain.PROMPT_MARKER->Domain.PROMPT;else->null}
        if(counterpart!=null&&action !in setOf(MemoryImportDecision.SKIP,MemoryImportDecision.BLOCKED)){
            // Effective portable identity is not a substitute for local UUID collision checks across the live/negative pair.
            // Only the exact target already matched and independently authorized above may share a marker's local UUID.
            val counterpartRow=sql.query("SELECT rowid FROM ${counterpart.table} WHERE ${counterpart.localKey}=?",arrayOf(localId)).use{c->if(!c.moveToFirst())null else c.getLong(0).also{check(!c.moveToNext())}}
            require(counterpartRow==null||counterpartRow==allowedCounterpart){"memory_import_local_identity_conflict"}
            hash("local-counterpart");hash(counterpart.name);hash(localId);hash(counterpartRow?.toString()?:"absent")
        }
        return MemoryImportDecision(row.domain,row.ordinal,action,project,localId,retained?.rowId?:(if(futureMarker)-2L else -1L),rowScope,source,target,removed,aliases)
    }
    private fun requireDescriptiveSession(row:OwnedRow){
        require(sql.query("SELECT 1 FROM memory_sessions WHERE rowid=? AND state IN ('manual','imported') AND source_generation IS NULL AND owner_conversation_id IS NULL AND owner_message_id IS NULL AND run_id IS NULL AND process_id IS NULL AND runtime_root_id IS NULL AND resume_event_key IS NULL AND maintenance_job_id IS NULL AND compaction_parent_session_id IS NULL AND mission_execution_session_id IS NULL AND runtime_lease_expires_at IS NULL AND maintenance_lease_owner IS NULL AND mission_evidence_id IS NULL AND mission_assignment_id IS NULL",arrayOf(row.rowId)).use{it.moveToFirst()}){"memory_import_protected_session"}
    }
    fun inspect(writeDecisions:Boolean=false):Pair<String,Map<String,Long>> {
        val counts=linkedMapOf<String,Long>()
        val output=if(writeDecisions)DataOutputStream(BufferedOutputStream(plan.artifacts.reset(plan.decisions),8192))else null
        try {
            hash("jarvys-import-impact-v1");hash(data.sha256);hash(data.sizeBytes.toString());hash(plan.mappingVersion.toString());hash(plan.generation?:"NULL");hash(plan.now.toString())
            owners.sorted().forEach(::hash)
            for(domain in ORDER)data.records(token,domain){row->
                val value=decision(row)
                val buffer=ByteArrayOutputStream(512);DataOutputStream(buffer).use{value.write(it)};digest.update(buffer.toByteArray())
                output?.let(value::write)
                val action=when(value.action){MemoryImportDecision.INSERT->"inserted";MemoryImportDecision.UPDATE->"updated";MemoryImportDecision.DELETE->"deleted";MemoryImportDecision.ADOPT->"adopted";MemoryImportDecision.BLOCKED->"blocked";else->"skipped"}
                val marker=domain in setOf(MemoryImportWire.OBS_MARKERS,MemoryImportWire.PROMPT_MARKERS,MemoryImportWire.SESSION_MARKERS)
                val key=MemoryImportWire.names[domain]+"_"+(if(marker){if(value.rowId==-1L)"inserted" else if(domain==MemoryImportWire.PROMPT_MARKERS)"updated" else "skipped"}else action)
                counts[key]=Math.addExact(counts[key]?:0,1)
                if(value.removed>0){val removedKey=MemoryImportWire.names[domain]+"_payloads_removed";counts[removedKey]=Math.addExact(counts[removedKey]?:0,value.removed)}
                if(value.aliases>0){val aliasKey="prompt_tombstones_aliases_inserted";counts[aliasKey]=Math.addExact(counts[aliasKey]?:0,value.aliases)}
            }
        }finally{output?.close()}
        token.throwIfCancelled();return digest.digest().joinToString(""){"%02x".format(it)} to counts
    }
    fun apply(){MemoryImportApply(plan,token).run()}
    companion object {val ORDER=intArrayOf(MemoryImportWire.SESSIONS,MemoryImportWire.SESSION_MARKERS,MemoryImportWire.OBS_MARKERS,MemoryImportWire.PROMPT_MARKERS,MemoryImportWire.OBSERVATIONS,MemoryImportWire.PROMPTS,MemoryImportWire.RELATIONS)}
}
