package com.jarvys.agent.chatdb

import android.content.Context
import android.database.Cursor
import com.jarvys.agent.*

/** Manual file import only. No save/dedupe/session registration or runtime provenance is replayed. */
internal class MemoryImportRepository(private val database:JarvysChatDatabase,private val context:Context) {
    private val sql get()=database.chatSql
    suspend fun initialize(data:MemoryImportData,artifacts:MemoryImportArtifacts,namespace:MemoryNamespace,token:CancellationToken):MemoryImportMapping = database.withChatReadTransaction {
        token.throwIfCancelled();artifacts.verify();MemoryImportValidation.validate(data,token)
        val identity=JarvysChatDatabaseProvider.requireOpenFileIdentity(database,context)
        val mapping=MemoryImportMapping(data,artifacts,token)
        val lookup=MemoryImportLookup(sql,data,namespace.authorizedProjects(),token)
        mapping.initialize(namespace.authorizedProjects(),{row->anchor(data,row,lookup,token)},token)
        identity();artifacts.verify();token.throwIfCancelled();mapping
    }
    private fun anchor(data:MemoryImportData,row:MemoryImportRecord,lookup:MemoryImportLookup,token:CancellationToken):String? {
        val domain=when(row.domain){
            MemoryImportWire.SESSIONS->MemoryImportLookup.Domain.SESSION
            MemoryImportWire.OBSERVATIONS->MemoryImportLookup.Domain.OBSERVATION
            MemoryImportWire.PROMPTS->MemoryImportLookup.Domain.PROMPT
            MemoryImportWire.PROMPT_MARKERS->MemoryImportLookup.Domain.PROMPT_MARKER
            MemoryImportWire.OBS_MARKERS->MemoryImportLookup.Domain.OBSERVATION_MARKER
            MemoryImportWire.SESSION_MARKERS->MemoryImportLookup.Domain.SESSION_MARKER
            else->return null
        }
        lookup.find(domain,MemoryImportValidation.identity(row))?.let{return it.project}
        val fallback=when(row.domain){MemoryImportWire.OBS_MARKERS->MemoryImportLookup.Domain.OBSERVATION;MemoryImportWire.PROMPT_MARKERS->MemoryImportLookup.Domain.PROMPT;MemoryImportWire.SESSION_MARKERS->MemoryImportLookup.Domain.SESSION;else->null}
        fallback?.let{lookup.find(it,MemoryImportValidation.identity(row))?.let{retained->return retained.project}}
        if(row.domain in setOf(MemoryImportWire.OBSERVATIONS,MemoryImportWire.PROMPTS,MemoryImportWire.PROMPT_MARKERS)){
            val session=row.cell("session_id")
            if(!MemoryImportValues.blank(data,session,token))lookup.find(MemoryImportLookup.Domain.SESSION,session)?.let{return it.project}
        }
        return null
    }
    suspend fun plan(data:MemoryImportData,mapping:MemoryImportMapping,artifacts:MemoryImportArtifacts,
        namespace:MemoryNamespace,token:CancellationToken):MemoryImportPlan = database.withChatReadTransaction {
        token.throwIfCancelled();artifacts.verify();mapping.requireComplete(namespace.authorizedProjects(),token)
        val identity=JarvysChatDatabaseProvider.requireOpenFileIdentity(database,context)
        val identities=MemoryImportPlan.identities(data,artifacts,token)
        val decisions=artifacts.create("decisions.bin")
        val generation=generation()
        val seed=MemoryImportPlan(data,mapping,artifacts,database,namespace,generation,System.currentTimeMillis(),identity,
            identities,decisions,mapping.version,"",emptyMap())
        val result=MemoryImportEngine(seed,token).inspect(writeDecisions=true)
        identity();artifacts.verify();token.throwIfCancelled()
        MemoryImportPlan(data,mapping,artifacts,database,namespace,generation,seed.now,identity,identities,decisions,mapping.version,result.first,result.second)
    }
    suspend fun apply(plan:MemoryImportPlan,namespace:MemoryNamespace,token:CancellationToken,beforeCommit:()->Unit):Map<String,Long> = database.withChatWriteTransaction {
        token.throwIfCancelled();check(plan.database===database&&plan.namespace.hasSameOwners(namespace)){"memory_import_source_changed"}
        check(plan.mappingVersion==plan.mapping.version){"memory_import_mapping_changed"}
        plan.requireOriginalFile();plan.artifacts.verify();check(plan.generation==generation()){ "memory_import_source_changed" }
        plan.mapping.requireComplete(namespace.authorizedProjects(),token)
        val engine=MemoryImportEngine(plan,token)
        val current=engine.inspect();check(current.first==plan.fingerprint&&current.second==plan.counts){"memory_import_plan_changed"}
        engine.apply()
        beforeCommit();plan.requireOriginalFile();plan.artifacts.verify();token.throwIfCancelled()
        plan.counts
    }
    private fun generation():String? {
        val present=sql.query("SELECT typeof(value),length(CAST(value AS BLOB)) FROM storage_meta WHERE key=?",arrayOf(ConnectionProjectionRepository.GENERATION_KEY)).use {c->
            if(!c.moveToFirst())false else {check(c.getString(0)=="text"&&c.getType(1)==Cursor.FIELD_TYPE_INTEGER&&c.getLong(1)==36L){"memory_import_generation_invalid"};true}
        }
        if(!present)return null
        return sql.query("SELECT value FROM storage_meta WHERE key=?",arrayOf(ConnectionProjectionRepository.GENERATION_KEY)).use {c->check(c.moveToFirst());c.getString(0).also {value->
            check(runCatching{java.util.UUID.fromString(value).toString()==value}.getOrDefault(false)){"memory_import_generation_invalid"}
        }}
    }
}
