package com.jarvys.agent.chatdb

import com.jarvys.agent.CrewInputContext
import com.jarvys.agent.coding.CodingJobEvidence
import com.jarvys.agent.coding.CodingJobEvidenceCodec
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/** Read-consistency metadata, not a cancellation, admission or journal capability. */
internal data class CodingCancellationRetainedReference(val requestId:String,val targetJobId:String,
    val technicalGeneration:String,val integrityFingerprint:String,val inputContextCoverage:CrewInputContext.Coverage,
    val unresolvedInputIds:Set<String>)

/** Caller owns the Room TX; role creation additionally requires the CURRENT cancellation ticket. */
internal class CodingCancellationSemanticRepository(private val database:JarvysChatDatabase) {
    private val sql get()=database.chatSql
    private val targets get()=CodingJobSemanticRepository(database)

    fun capture(id:String,binding:CodingJobEvidence.Binding,source:CodingJobEvidence.Source?,enabled:Boolean) {
        val encoded=source?.let(CodingJobEvidenceCodec::encodeSourceMetadata)
        var status=if(!enabled)"DISABLED" else if(source==null)"UNAVAILABLE" else "ABSENT"
        val selected=if(enabled && source?.rootCentral!=null)targets.existingTarget(source.rootCentral,binding.technicalStoreGeneration) else null
        if(selected!=null)status="PENDING"
        sql.execSQL("UPDATE coding_cancel_requests SET semantic_source_json=?,target_entry_id=?,target_fingerprint=?,semantic_status=? WHERE request_id=? AND semantic_status='UNCAPTURED'",
            arrayOf(encoded,selected?.first,selected?.second,status,id))
    }
    fun suppress(id:String) {
        sql.execSQL("UPDATE coding_cancel_requests SET semantic_status='SUPPRESSED' WHERE request_id=? AND semantic_status='PENDING'",arrayOf(id))
    }
    fun link(id:String) {
        val row=read(id) ?: error("Missing cancellation technical row")
        if(row.status!="PENDING")return
        val base=validBase(row) ?: run {suppress(id);return}
        val facts=resultFacts(row) ?: error("Cancellation projection requires its complete exact result")
        val target=row.target ?: run {suppress(id);return}
        if(!currentTarget(row,base.source)) {suppress(id);return}
        val ordinal=sql.query("SELECT COALESCE(MAX(ordinal),-1)+1 FROM memory_evidence WHERE entry_id=?",arrayOf(target))
            .use {check(it.moveToFirst());it.getLong(0)}
        val now=System.currentTimeMillis()
        val roleFacts=roleFacts(row,base,facts)
        sql.execSQL("INSERT INTO memory_evidence(entry_id,ordinal,ref_kind,ref_conversation_id,ref_id,at_ms,tool_name,outcome,authorization,facts_json,connector,source_batch_id,run_id,process_id) VALUES(?,?,'coding_cancel_request',?,?,?,'project_jobs',?,NULL,?,NULL,NULL,NULL,NULL)",
            arrayOf(target,ordinal,base.source.crew.executionSessionId,id,now,facts.getString("outcome"),roleFacts.toString()))
        sql.execSQL("UPDATE coding_cancel_requests SET semantic_status='LINKED',evidence_ordinal=? WHERE request_id=? AND semantic_status='PENDING'",arrayOf(ordinal,id))
        if (!MemoryObservationHistory.hasBodyOverride(database, target))
            sql.execSQL("UPDATE memory_entries SET narration='none',updated_at=? WHERE id=? AND project='jarvys-central' AND scope='project' AND provenance='runtime' AND deleted_at IS NULL",arrayOf(now,target))
    }

    fun narrationStates(entryId:String):List<CodingCancellationNarrationState> {
        val ids=sql.query("SELECT request_id FROM coding_cancel_requests WHERE target_entry_id=? ORDER BY request_id",arrayOf(entryId))
            .use {c ->buildList {while(c.moveToNext())add(c.getString(0))}}
        return java.util.Collections.unmodifiableList(ids.mapNotNull {id ->
            val row=read(id) ?: return@mapNotNull null
            val base=validBase(row) ?: return@mapNotNull null
            if(!currentTarget(row,base.source))return@mapNotNull null
            // A malformed completed row is not converted into a valid observation or new admission.
            val complete=row.result!=null && resultFacts(row)!=null
            if(row.kind=="NO_ADMISSION_OBSERVATION" && !complete)return@mapNotNull null
            CodingCancellationNarrationState(id,row.jobId,row.kind,row.status,complete,sha256(row.identity()))
        })
    }

    fun source(entryId:String,requestId:String,maxChars:Int):JSONObject {
        val unavailable=JSONObject().put("state","unavailable")
        require(maxChars>=unavailable.toString().length)
        val v=validated(entryId,requestId) ?: return unavailable
        val context=JSONObject(requireNotNull(v.row.semanticSource)).getJSONObject("source").getJSONObject("crew").getJSONObject("inputContext")
        val result=JSONObject().put("state","retained").put("request_id",requestId).put("job_id",v.row.jobId)
            .put("technical_generation",v.row.generation).put("record_kind",v.row.kind)
            .put("completion_semantics",v.facts.getString("completion_semantics")).put("outcome",v.facts.getString("outcome"))
            .put("goal_verified",false).put("root_is_historical",true).put("historical_root_is_exclusive_cause",false)
            .put("input_coverage",v.base.source.crew.inputContext.coverage.name.lowercase(Locale.ROOT))
            .put("sourceRefs",context.getJSONArray("references")).put("unresolvedInputIds",context.getJSONArray("unresolvedInputIds"))
        if(v.facts.getString("completion_semantics")=="cancel_request_accepted")result.put("causes_cancel_of",v.row.jobId)
        return if(result.toString().length<=maxChars)result else JSONObject().put("state","bounded")
    }
    fun retainedCancellationReference(entryId:String,requestId:String):CodingCancellationRetainedReference? {
        val v=validated(entryId,requestId) ?: return null
        return CodingCancellationRetainedReference(requestId,v.row.jobId,v.row.generation,v.fingerprint,
            v.base.source.crew.inputContext.coverage,java.util.Collections.unmodifiableSet(LinkedHashSet(v.base.source.crew.inputContext.unresolvedInputIds)))
    }
    private data class Row(val id:String,val sourceKey:String,val generation:String,val jobId:String,val sourceJson:String,
        val bindingJson:String,val admission:String?,val result:String?,val kind:String,val semanticSource:String?,
        val target:String?,val targetFingerprint:String?,val status:String,val ordinal:Long?) {
        fun identity()=JSONArray().put(id).put(sourceKey).put(generation).put(jobId).put(sourceJson).put(bindingJson)
            .put(admission ?: JSONObject.NULL).put(result ?: JSONObject.NULL).put(kind).put(semanticSource ?: JSONObject.NULL)
            .put(target ?: JSONObject.NULL).put(targetFingerprint ?: JSONObject.NULL).put(status).put(ordinal ?: JSONObject.NULL).toString()
    }
    private fun read(id:String):Row?=sql.query("SELECT request_id,source_key,technical_generation,target_job_id,source_json,target_binding_json,admission_json,result_json,record_kind,semantic_source_json,target_entry_id,target_fingerprint,semantic_status,evidence_ordinal FROM coding_cancel_requests WHERE request_id=?",arrayOf(id))
        .use {c ->if(!c.moveToFirst())null else Row(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),
            if(c.isNull(6))null else c.getString(6),if(c.isNull(7))null else c.getString(7),c.getString(8),
            if(c.isNull(9))null else c.getString(9),if(c.isNull(10))null else c.getString(10),if(c.isNull(11))null else c.getString(11),c.getString(12),if(c.isNull(13))null else c.getLong(13))}
    private data class Base(val binding:CodingJobEvidence.Binding,val source:CodingJobEvidence.Source)
    private fun validBase(row:Row):Base?=try {
        val sourceBytes=row.semanticSource
        if(sourceBytes==null || row.status !in setOf("PENDING","LINKED","SUPPRESSED") ||
            row.kind !in setOf("ADMITTED_INTENT","NO_ADMISSION_OBSERVATION") ||
            (row.kind=="ADMITTED_INTENT")!=(row.admission!=null) || !validAdmission(row)) null
        else {
            val source=CodingJobEvidenceCodec.decodeSourceMetadata(sourceBytes,sourceBytes.length)
            val binding=CodingJobEvidenceCodec.decode(row.bindingJson,row.bindingJson.length).record.binding
            val current=sql.query("SELECT value FROM storage_meta WHERE key=?",arrayOf(ConnectionProjectionRepository.GENERATION_KEY)).use {if(it.moveToFirst())it.getString(0) else null}
            val exact=sql.query("SELECT binding_json,technical_generation FROM coding_job_bindings WHERE job_id=?",arrayOf(row.jobId)).use {c ->c.moveToFirst()&&c.getString(0)==row.bindingJson&&c.getString(1)==row.generation}
            if(!exact || current!=row.generation || binding.jobId!=row.jobId || binding.technicalStoreGeneration!=row.generation ||
                source.crew.scopeIdentity!=binding.durableScopeIdentity || source.crew.toolName!="project_jobs" ||
                !binding.owner.startsWith(source.crew.executionSessionId+"/"+source.crew.botId+"/") ||
                source.crew.executionSessionId!=binding.source.crew.executionSessionId || source.crew.botId!=binding.source.crew.botId ||
                CodingJobEvidenceCodec.encodeSourceMetadata(source)!=sourceBytes || CodingJobEvidenceCodec.encodeCrewCallMetadata(source.crew)!=row.sourceJson ||
                source.rootCentral?.storageGeneration!=row.generation || sha256(CodingJobEvidenceCodec.encodeCrewCallIdentity(source.crew))!=row.sourceKey) null else Base(binding,source)
        }
    } catch(_:IllegalArgumentException){null} catch(_:org.json.JSONException){null}
    private fun validAdmission(row:Row):Boolean = try {
        if(row.kind=="NO_ADMISSION_OBSERVATION") row.admission==null else {
            val raw=row.admission
            if(raw==null)false else {
                val value=JSONObject(raw)
                val authorization=value.getString("authorization")
                val expected=JSONObject().put("schema",1).put("authorization",authorization)
                var valid=authorization in setOf("ALLOW","ASK_APPROVED")
                for(key in listOf("authority","action","revision","origin")) {
                    val field=value.get(key)
                    if(field!=JSONObject.NULL && field !is String)valid=false
                    expected.put(key,field)
                }
                valid && expected.toString()==raw
            }
        }
    } catch(_:org.json.JSONException){false}
    private fun currentTarget(row:Row,source:CodingJobEvidence.Source):Boolean {
        val root=source.rootCentral ?: return false
        val target=row.target ?: return false
        return row.targetFingerprint!=null && targets.targetFingerprint(target,root)==row.targetFingerprint
    }
    /** Rebuild the small fixed shape; reject unknown keys, coercion and duplicate/noncanonical JSON. */
    private fun resultFacts(row:Row):JSONObject? { return try {
        val raw=row.result
        if(raw==null)null else {
            val j=JSONObject(raw)
            val disposition=j.getString("disposition")
            val expected=JSONObject().put("schema",1).put("disposition",disposition)
            if(row.kind=="NO_ADMISSION_OBSERVATION") {
                if(disposition !in setOf("ALREADY_TERMINAL","ALREADY_REQUESTED"))return null
                val state=j.getString("observed_state");val exit=j.get("observed_exit_code")
                if(disposition=="ALREADY_REQUESTED" && state!="CANCELLING" || disposition=="ALREADY_TERMINAL" &&
                    state !in setOf("SUCCEEDED","FAILED","NOT_STARTED","CANCELLED","TIMED_OUT","INTERRUPTED","UNCERTAIN"))return null
                if(disposition=="ALREADY_REQUESTED" && exit!=JSONObject.NULL)return null
                if(exit!=JSONObject.NULL && exit !is Int && exit !is Long)return null
                if(exit is Number && (exit.toLong()<Int.MIN_VALUE || exit.toLong()>Int.MAX_VALUE))return null
                expected.put("completion_semantics","existing_reference").put("outcome","not_executed").put("goal_verified",false)
                    .put("observed_state",j.getString("observed_state")).put("observed_exit_code",j.get("observed_exit_code"))
            } else {
                if(disposition !in setOf("NEW_REQUEST","ALREADY_REQUESTED","ALREADY_TERMINAL","REQUEST_UNCONFIRMED"))return null
                val revision=j.get("cancelling_revision");val hash=j.get("cancelling_payload_sha256")
                if((revision==JSONObject.NULL)!=(hash==JSONObject.NULL))return null
                if(revision!=JSONObject.NULL && (disposition!="NEW_REQUEST" || (revision !is Long && revision !is Int) || (revision as Number).toLong()<=0 || hash !is String || !hash.matches(Regex("[0-9a-f]{64}"))))return null
                val accepted=disposition=="NEW_REQUEST" && revision!=JSONObject.NULL
                expected.put("completion_semantics",if(accepted)"cancel_request_accepted" else "existing_reference")
                    .put("outcome",if(accepted)"ok" else "uncertain").put("goal_verified",false)
                    .put("cancelling_revision",revision).put("cancelling_payload_sha256",hash)
            }
            if(expected.toString()!=raw)null else expected
        }
    } catch(_:org.json.JSONException){null} }
    private fun roleFacts(row:Row,base:Base,result:JSONObject):JSONObject {
        val facts=JSONObject().put("completion_semantics",result.getString("completion_semantics")).put("goal_verified",false)
            .put("technical_generation",row.generation).put("job_id",row.jobId).put("request_id",row.id)
            .put("record_kind",row.kind).put("result_payload_sha256",sha256(requireNotNull(row.result))).put("input_coverage",base.source.crew.inputContext.coverage.name.lowercase(Locale.ROOT))
            .put("root_is_historical",true).put("historical_root_is_exclusive_cause",false)
            .put("effect_reference",JSONObject().put("kind","job").put("scope_id",base.binding.scopeId).put("id",row.jobId))
        if(result.getString("completion_semantics")=="cancel_request_accepted")facts.put("causes_cancel_of",row.jobId)
        return facts
    }
    private data class Validated(val row:Row,val base:Base,val facts:JSONObject,val fingerprint:String)
    private fun validated(entryId:String,id:String):Validated? {
        val row=read(id) ?: return null
        if(row.target!=entryId || row.status!="LINKED" || row.ordinal==null)return null
        val base=validBase(row) ?: return null
        if(!currentTarget(row,base.source))return null
        val facts=resultFacts(row) ?: return null
        val rows=sql.query("SELECT ordinal,ref_conversation_id,tool_name,outcome,facts_json,authorization,connector,source_batch_id,run_id,process_id,at_ms FROM memory_evidence WHERE entry_id=? AND ref_kind='coding_cancel_request' AND ref_id=? ORDER BY ordinal",arrayOf(entryId,id)).use {c ->
            if(!c.moveToFirst() || c.getLong(0)!=row.ordinal || c.getString(1)!=base.source.crew.executionSessionId ||
                c.getString(2)!="project_jobs" || c.getString(3)!=facts.getString("outcome") || c.getString(4)!=roleFacts(row,base,facts).toString() ||
                (5..9).any {!c.isNull(it)})null else {
                val exact=JSONArray().apply {for(i in 0..10)put(if(c.isNull(i))JSONObject.NULL else c.getString(i))}.toString()
                if(c.moveToNext())null else exact
            }
        } ?: return null
        return Validated(row,base,facts,sha256(row.identity()+"\n"+rows))
    }
    private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(""){"%02x".format(Locale.ROOT,it.toInt() and 255)}
}
