package com.jarvys.agent.chatdb

import android.database.Cursor
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class MemoryConsolidationRequest internal constructor(ids:List<String>,val newTitle:String,val newContent:String,
    val expectedProject:String,val type:String?,val topicKeyBytesBase64:String?,val confirm:Boolean,val fingerprint:String?) {
    val sourceIds:List<String> = java.util.Collections.unmodifiableList(ArrayList(ids))
}
object MemoryConsolidationArguments {
    const val TOOL="memory_consolidate"
    const val PROOF_PREFIX="memory-consolidate-v1:"
    @JvmStatic fun prepare(args:Map<String,Any?>):MemoryConsolidationRequest {
        require(args.keys.all { it in setOf("ids_to_delete","new_title","new_content","expected_project","type","topic_key","confirm","fingerprint") }) { "memory_consolidation_argument_invalid" }
        fun string(key:String,required:Boolean=false):String? {
            if(!args.containsKey(key)) { require(!required) { "memory_consolidation_${key}_required" }; return null }
            val raw=args[key];require(raw is String) { "memory_consolidation_${key}_invalid" }
            require(!required || MemoryObservationContent.trimSpace(raw).isNotEmpty()) { "memory_consolidation_${key}_required" };return raw
        }
        val raw=args["ids_to_delete"];require(raw is List<*> && raw.size>=2) { "memory_consolidation_sources_invalid" }
        val ids=raw.map { value ->
            require(value is String) { "memory_consolidation_id_invalid" }
            val id=MemoryObservationContent.trimSpace(value)
            require(try { UUID.fromString(id).toString()==id } catch(_:IllegalArgumentException) { false }) { "memory_consolidation_id_invalid" };id
        }
        require(ids.distinct().size==ids.size) { "memory_consolidation_duplicate_id" }
        val confirm=if(args.containsKey("confirm")) { require(args["confirm"] is Boolean) { "memory_consolidation_confirm_invalid" };args["confirm"] as Boolean } else false
        val fingerprint=string("fingerprint")
        require(!confirm || fingerprint!=null && MemoryObservationContent.trimSpace(fingerprint).isNotEmpty()) { "memory_consolidation_fingerprint_required" }
        val topic=string("topic_key")?.let(MemoryObservationContent::normalizeTopicBytes)
        val title=MemoryObservationContent.prepareTitle(requireNotNull(string("new_title",true)))
        val content=MemoryObservationContent.prepareTitle(requireNotNull(string("new_content",true)))
        require(title.isNotEmpty() && content.isNotEmpty()) { "memory_consolidation_content_required" }
        return MemoryConsolidationRequest(ids,title,content,
            MemoryNamespace.normalizeProject(requireNotNull(string("expected_project",true))),string("type")?.let(MemoryObservationContent::trimSpace)?.takeIf(String::isNotEmpty),
            topic?.let { Base64.getEncoder().encodeToString(it) },confirm,fingerprint)
    }
    @JvmStatic fun proofTree(request:MemoryConsolidationRequest,session:String):List<Any?> = listOf("v1",TOOL,session,request.sourceIds,
        request.newTitle,request.newContent,request.expectedProject,request.type,request.topicKeyBytesBase64,"true",request.fingerprint)
    @JvmStatic fun checkpointDigest(args:Map<String,Any?>,session:String):String? = prepare(args).let { if(it.confirm) MemoryConsolidationCodec.digest(PROOF_PREFIX,proofTree(it,session)) else null }
    @JvmStatic fun isInputDigest(value:String?)=value!=null && value.startsWith(PROOF_PREFIX) && value.length==PROOF_PREFIX.length+64 && value.substring(PROOF_PREFIX.length).all { it in '0'..'9'||it in 'a'..'f' }
}
object MemoryConsolidationCodec {
    @JvmStatic fun digest(prefix:String,tree:List<Any?>):String {
        val hash=MessageDigest.getInstance("SHA-256")
        fun count(n:Long) { hash.update(ByteBuffer.allocate(8).putLong(n).array()) }
        fun write(value:Any?) { when(value) {
            null -> hash.update(0.toByte())
            is String -> { hash.update(1.toByte());count(value.length.toLong());value.forEach { hash.update((it.code ushr 8).toByte());hash.update(it.code.toByte()) } }
            is List<*> -> { hash.update(2.toByte());count(value.size.toLong());value.forEach(::write) }
            else -> error("memory_consolidation_codec_invalid")
        } }
        write(tree);return prefix+hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
data class MemoryConsolidationField(val name:String,val storageType:String,val value:String?)
class MemoryConsolidationSource internal constructor(val id:String,val rowId:Long,fields:List<MemoryConsolidationField>) {
    val fields:List<MemoryConsolidationField> = java.util.Collections.unmodifiableList(ArrayList(fields))
    internal fun field(name:String)=fields.single { it.name==name }
    internal fun bytes(name:String):ByteArray?=field(name).value?.let { Base64.getDecoder().decode(it) }
    internal fun text(name:String):String?=bytes(name)?.let { bytes ->
        val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        try { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch(failure:java.nio.charset.CharacterCodingException) { throw IllegalStateException("memory_consolidation_source_invalid",failure) }
    }
    internal fun long(name:String)=requireNotNull(field(name).value).toLong()
    val title:String? get()=MemoryObservationContent.topicDisplay(bytes("title"))
    val titleBytesBase64:String? get()=field("title").value
    val titleIsUtf8:Boolean? get()=MemoryObservationContent.topicIsUtf8(bytes("title"))
    val content:String get()=requireNotNull(text("text"))
    val project:String get()=requireNotNull(text("project"))
    val scope:String get()=requireNotNull(text("scope"))
    val type:String get()=requireNotNull(text("observation_type"))
    val memorySessionId:String? get()=text("memory_session_id")
    val topicKeyBytesBase64:String? get()=field("topic_key").value
    val topicKey:String? get()=MemoryObservationContent.topicDisplay(bytes("topic_key"))
    val topicKeyIsUtf8:Boolean? get()=MemoryObservationContent.topicIsUtf8(bytes("topic_key"))
    internal fun tree():List<Any?> = listOf(id,rowId.toString(),fields.map { listOf(it.name,if(it.storageType=="null") null else listOf(it.storageType,it.value)) })
}
data class MemoryConsolidationSurvivor(val memorySessionId:String?,val type:String,val title:String,val content:String,
    val scope:String,val project:String,val topicKeyBytesBase64:String?) {
    val reviewAfter:String? get()=null
    val pinned:Boolean get()=false
    internal fun tree():List<Any?> = listOf(listOf("session_id",memorySessionId),listOf("type",type),listOf("title",title),listOf("content",content),
        listOf("scope",scope),listOf("project",project),listOf("topic_key",topicKeyBytesBase64))
}
class MemoryConsolidationPlan internal constructor(ids:List<String>,sources:List<MemoryConsolidationSource>,val project:String,
    val survivor:MemoryConsolidationSurvivor,val fingerprint:String) {
    val sourceIds:List<String> = java.util.Collections.unmodifiableList(ArrayList(ids))
    val sources:List<MemoryConsolidationSource> = java.util.Collections.unmodifiableList(ArrayList(sources))
}
data class MemoryConsolidationResult(val status:String,val plan:MemoryConsolidationPlan,val survivor:MemoryConsolidationSource?=null,
    val softDeletedIds:List<String> = emptyList())

internal class MemoryConsolidationRepository(private val database:JarvysChatDatabase,private val clock:()->Long=System::currentTimeMillis) {
    private val sql get()=database.chatSql
    companion object {
        val COLUMNS:List<String> = listOf("id","layer","day","text","category","importance","triggers","provenance","source_ref","usage_count","last_used_at","created_at","updated_at","deleted_at","kind","topic_key","title","narration","event_at","source_conversation_id","source_message_id","through_seq","project","scope","observation_type","revision_count","normalized_hash","duplicate_count","last_seen_at","pinned","memory_session_id","tool_name","review_after","superseded_by_id")
        private const val MARKER="## consolidated_from"
        private val UUID_TOKEN=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
    private fun row(id:String):MemoryConsolidationSource = sql.query("SELECT rowid,"+COLUMNS.joinToString(",") { "typeof($it),$it,CAST($it AS BLOB)" }+" FROM memory_entries WHERE id=?",arrayOf(id)).use { c ->
        check(c.moveToFirst()) { "memory_consolidation_source_missing" }
        val fields=COLUMNS.mapIndexed { index,name ->
            val i=1+index*3
            when(c.getString(i)) {
                "null" -> MemoryConsolidationField(name,"null",null)
                "integer" -> MemoryConsolidationField(name,"integer",c.getLong(i+1).toString())
                "real" -> { val value=c.getDouble(i+1);check(value.isFinite()) { "memory_consolidation_source_invalid" };MemoryConsolidationField(name,"real",java.lang.Double.toHexString(value)) }
                "text" -> MemoryConsolidationField(name,"text",Base64.getEncoder().encodeToString(c.getBlob(i+2)))
                "blob" -> MemoryConsolidationField(name,"blob",Base64.getEncoder().encodeToString(c.getBlob(i+2)))
                else -> error("memory_consolidation_source_invalid")
            }
        }
        MemoryConsolidationSource(id,c.getLong(0),fields).also { check(!c.moveToNext()) { "memory_consolidation_source_invalid" } }
    }
    private fun plan(request:MemoryConsolidationRequest,namespace:MemoryNamespace):MemoryConsolidationPlan {
        namespace.requireOwner(request.expectedProject)
        val sources=request.sourceIds.map(::row).sortedWith(compareByDescending<MemoryConsolidationSource> { it.long("created_at") }.thenByDescending { it.rowId })
        sources.forEach { source ->
            check(source.field("deleted_at").storageType=="null") { "memory_consolidation_source_deleted" }
            namespace.requireOwner(source.project)
            require(source.project==request.expectedProject) { "memory_consolidation_project_mismatch" }
            check(source.text("id")==source.id) { "memory_consolidation_source_invalid" }
            MemoryDeletionMarkers.validateTarget(database,source.id,source.project,source.scope)
            source.memorySessionId?.let { session ->
                check(sql.query("SELECT 1 FROM memory_sessions WHERE id=? AND project=? AND NOT EXISTS(SELECT 1 FROM memory_session_tombstones WHERE id=?)",arrayOf(session,source.project,session)).use { it.moveToFirst() }) { "memory_consolidation_session_invalid" }
            }
            val marker=source.content.indexOf(MARKER)
            if(marker>=0) check(UUID_TOKEN.findAll(source.content.substring(marker+MARKER.length)).none { it.value in request.sourceIds }) { "memory_consolidation_provenance_cycle" }
        }
        val newest=sources.first()
        val content=request.newContent.trimEnd('\n')+"\n\n"+MARKER+"\n"+sources.joinToString("") { "- ${it.id}\n" }
        require(content.toByteArray(Charsets.UTF_8).size<=MemoryObservationContent.LIMIT_BYTES) { "memory_consolidation_content_too_large" }
        val survivor=MemoryConsolidationSurvivor(newest.memorySessionId,request.type?:newest.type,request.newTitle,content,
            MemoryNamespace.normalizeScope(newest.scope),request.expectedProject,request.topicKeyBytesBase64)
        val tree=listOf("consolidate-local-v1",MemorySaveSourceIdentity.existingGeneration(database),sources.sortedBy { it.id }.map { it.tree() },survivor.tree(),request.expectedProject)
        val fingerprint=MemoryConsolidationCodec.digest("consolidate-local-v1:",tree)
        return MemoryConsolidationPlan(request.sourceIds,sources,request.expectedProject,survivor,fingerprint)
    }
    suspend fun preview(request:MemoryConsolidationRequest,namespace:MemoryNamespace,check:()->Unit={}):MemoryConsolidationPlan = database.withChatReadTransaction {
        check();plan(request,namespace).also { check() }
    }
    suspend fun confirm(request:MemoryConsolidationRequest,fingerprint:String,namespace:MemoryNamespace,actor:String,
        before:()->Unit={},after:()->Unit={},validatePlan:(MemoryConsolidationPlan)->Unit={}):MemoryConsolidationResult = database.withChatWriteTransaction {
        require(actor=="user" || actor=="agent") { "memory_actor_invalid" };before()
        val plan=plan(request,namespace)
        validatePlan(plan);before()
        if(plan.fingerprint!=fingerprint) return@withChatWriteTransaction MemoryConsolidationResult("fingerprint_mismatch",plan)
        val id=UUID.randomUUID().toString();val now=clock();val survivor=plan.survivor
        // Retire before insert solely to preserve the local live-topic UNIQUE index. Entire writer is atomic.
        plan.sources.sortedBy { it.id }.forEach { source ->
            sql.execSQL("UPDATE memory_entries SET deleted_at=?,updated_at=?,superseded_by_id=? WHERE id=? AND deleted_at IS NULL",arrayOf(now,now,id,source.id))
            check(sql.query("SELECT changes()").use { it.moveToFirst();it.getLong(0)==1L }) { "memory_consolidation_source_changed" }
        }
        sql.execSQL("INSERT INTO memory_entries(id,layer,day,text,category,importance,triggers,provenance,source_ref,usage_count,last_used_at,created_at,updated_at,deleted_at,kind,topic_key,title,narration,event_at,source_conversation_id,source_message_id,through_seq,project,scope,observation_type,revision_count,normalized_hash,duplicate_count,last_seen_at,pinned,memory_session_id,tool_name,review_after,superseded_by_id) VALUES(?,'curated',NULL,?,NULL,0.5,'',?,NULL,0,NULL,?,?,NULL,'note',CAST(? AS TEXT),?,'none',NULL,NULL,NULL,NULL,?,?,?,1,?,1,?,0,?,'memory_consolidate',NULL,NULL)",
            arrayOf(id,survivor.content,actor,now,now,survivor.topicKeyBytesBase64?.let { Base64.getDecoder().decode(it) },survivor.title,survivor.project,survivor.scope,survivor.type,MemoryObservationContent.normalizedHash(survivor.content),now,survivor.memorySessionId))
        val actual=row(id)
        val expected=linkedMapOf<String,Any?>("id" to id,"layer" to "curated","day" to null,"text" to survivor.content,"category" to null,
            "importance" to 0.5,"triggers" to "","provenance" to actor,"source_ref" to null,"usage_count" to 0L,"last_used_at" to null,
            "created_at" to now,"updated_at" to now,"deleted_at" to null,"kind" to "note","topic_key" to survivor.topicKeyBytesBase64?.let { Base64.getDecoder().decode(it) },
            "title" to survivor.title,"narration" to "none","event_at" to null,"source_conversation_id" to null,"source_message_id" to null,
            "through_seq" to null,"project" to survivor.project,"scope" to survivor.scope,"observation_type" to survivor.type,"revision_count" to 1L,
            "normalized_hash" to MemoryObservationContent.normalizedHash(survivor.content),"duplicate_count" to 1L,"last_seen_at" to now,"pinned" to 0L,
            "memory_session_id" to survivor.memorySessionId,"tool_name" to "memory_consolidate","review_after" to null,"superseded_by_id" to null)
        val expectedFields=COLUMNS.map { name -> when(val value=expected[name]) {
            null -> MemoryConsolidationField(name,"null",null)
            is Long -> MemoryConsolidationField(name,"integer",value.toString())
            is Double -> MemoryConsolidationField(name,"real",java.lang.Double.toHexString(value))
            is ByteArray -> MemoryConsolidationField(name,"text",Base64.getEncoder().encodeToString(value))
            is String -> MemoryConsolidationField(name,"text",Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)))
            else -> error("memory_consolidation_readback_invalid")
        } }
        check(actual.fields==expectedFields) { "memory_consolidation_readback_invalid" }
        check(actual.content==survivor.content && actual.title==survivor.title && actual.project==survivor.project && actual.scope==survivor.scope && actual.type==survivor.type && actual.memorySessionId==survivor.memorySessionId && actual.topicKeyBytesBase64==survivor.topicKeyBytesBase64 && actual.field("deleted_at").value==null) { "memory_consolidation_readback_invalid" }
        plan.sources.forEach { old ->
            val retired=row(old.id)
            check(retired.long("deleted_at")==now && retired.long("updated_at")==now && retired.text("superseded_by_id")==id &&
                retired.fields.filter { it.name !in setOf("deleted_at","updated_at","superseded_by_id") }==old.fields.filter { it.name !in setOf("deleted_at","updated_at","superseded_by_id") }) { "memory_consolidation_readback_invalid" }
        }
        after();MemoryConsolidationResult("consolidated",plan,actual,java.util.Collections.unmodifiableList(ArrayList(plan.sourceIds)))
    }
}

object MemoryConsolidationJson {
    private fun nullable(value:Any?):Any=value?:org.json.JSONObject.NULL
    @JvmStatic fun source(source:MemoryConsolidationSource):org.json.JSONObject = org.json.JSONObject()
        .put("id",source.id).put("rowid",source.rowId).put("title",nullable(source.title)).put("content",source.content)
        .put("title_bytes_base64",nullable(source.titleBytesBase64)).put("title_is_utf8",nullable(source.titleIsUtf8))
        .put("fields",org.json.JSONArray(source.fields.map { org.json.JSONObject().put("name",it.name).put("storage_type",it.storageType).put("value",nullable(it.value)) }))
    @JvmStatic fun plan(plan:MemoryConsolidationPlan):org.json.JSONObject {
        val value=plan.survivor
        return org.json.JSONObject().put("source_ids",org.json.JSONArray(plan.sourceIds)).put("sources",org.json.JSONArray(plan.sources.map(::source)))
            .put("project",plan.project).put("fingerprint",plan.fingerprint).put("survivor",org.json.JSONObject()
                .put("session_id",nullable(value.memorySessionId)).put("title",value.title).put("content",value.content).put("type",value.type)
                .put("scope",value.scope).put("project",value.project).put("topic_key_bytes_base64",nullable(value.topicKeyBytesBase64))
                .put("review_after",org.json.JSONObject.NULL).put("pinned",false))
    }
    @JvmStatic fun result(result:MemoryConsolidationResult):String = org.json.JSONObject().put("status",result.status).put("plan",plan(result.plan))
        .put("survivor",result.survivor?.let(::source)?:org.json.JSONObject.NULL).put("soft_deleted_ids",org.json.JSONArray(result.softDeletedIds)).toString()
}
