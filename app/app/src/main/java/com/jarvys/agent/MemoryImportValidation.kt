package com.jarvys.agent

import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

/** File-local validation only. Current ownership, collision effects and authority belong to the Room plan. */
internal object MemoryImportValidation {
    fun identity(record:MemoryImportRecord):MemoryImportCell=record.cell(if(record.domain in setOf(MemoryImportWire.SESSIONS,MemoryImportWire.OBS_MARKERS,MemoryImportWire.SESSION_MARKERS))"id" else "sync_id")
    fun find(data:MemoryImportData,domain:Int,id:MemoryImportCell,token:CancellationToken):MemoryImportRecord? {
        var found:MemoryImportRecord?=null
        data.records(token,domain){row->if(data.equal(identity(row),id,token)){check(found==null){"memory_import_duplicate_identity"};found=row}}
        return found
    }
    fun validate(data:MemoryImportData,token:CancellationToken) {
        var header=false
        data.records(token){row->
            token.throwIfCancelled()
            if(row.domain==MemoryImportWire.HEADER){
                require(!header&&data.equals(row.cell("version"),"0.2.0",token)){"memory_import_version_invalid"}
                MemoryImportValues.string(row.cell("exported_at"));header=true
            } else {
                val numeric=when(row.domain){MemoryImportWire.OBSERVATIONS->setOf("id","revision_count","duplicate_count");MemoryImportWire.PROMPTS->setOf("id");else->emptySet()}
                MemoryImportWire.fields[row.domain].forEach { name->val cell=row.cell(name)
                    when {name in numeric->MemoryImportValues.integer(data,cell,token)
                        row.domain==MemoryImportWire.OBSERVATIONS&&name=="pinned"->MemoryImportValues.bool(cell)
                        row.domain==MemoryImportWire.RELATIONS&&name=="confidence"->MemoryImportValues.decimal(data,cell,token)
                        else->MemoryImportValues.string(cell)}
                }
                val id=identity(row);require(!MemoryImportValues.blank(data,id,token)){"memory_import_identity_required"}
                data.records(token,row.domain){prior->if(prior.ordinal<row.ordinal)require(!data.equal(identity(prior),id,token)){"memory_import_duplicate_identity"}}
                if(row.domain==MemoryImportWire.SESSIONS){
                    val directory=row.cell("directory");require(directory.type!=MemoryImportWire.NULL){"memory_import_directory_invalid"}
                    MemoryImportValues.ownershipMode(data,row.cell("ownership_mode"),token)
                }
                if(row.domain==MemoryImportWire.RELATIONS){
                    require(!MemoryImportValues.blank(data,row.cell("source_id"),token)&&!MemoryImportValues.blank(data,row.cell("target_id"),token)){"memory_import_relation_endpoint_required"}
                }
                if(row.domain==MemoryImportWire.RAW){
                    require(data.hasExtension){"memory_import_extension_invalid"}
                    val base=find(data,MemoryImportWire.OBSERVATIONS,id,token)?:error("memory_import_raw_unmatched")
                    raw(data,row.cell("title_bytes_base64"),base.cell("title"),true,token)
                    raw(data,row.cell("topic_key_bytes_base64"),base.cell("topic_key"),false,token)
                    val session=row.cell("session_id");require(session.type in setOf(MemoryImportWire.STRING,MemoryImportWire.NULL)){"memory_import_extension_incomplete"}
                    if(session.type==MemoryImportWire.NULL)require(MemoryImportValues.nullable(base.cell("session_id"))||data.equals(base.cell("session_id"),"",token)){"memory_import_raw_inconsistent"}
                    else require(!MemoryImportValues.blank(data,session,token)&&data.equal(session,base.cell("session_id"),token)){"memory_import_raw_inconsistent"}
                }
            }
        }
        require(header){"memory_import_version_invalid"}
        if(data.hasExtension){
            require(data.counts["observations"]==data.counts["observation_fields"]){"memory_import_extension_incomplete"}
            data.records(token,MemoryImportWire.OBSERVATIONS){require(find(data,MemoryImportWire.RAW,identity(it),token)!=null){"memory_import_extension_incomplete"}}
        }
    }
    private fun raw(data:MemoryImportData,raw:MemoryImportCell,base:MemoryImportCell,nullAsEmpty:Boolean,token:CancellationToken){
        require(raw.type in setOf(MemoryImportWire.STRING,MemoryImportWire.NULL)){"memory_import_extension_incomplete"}
        if(raw.type==MemoryImportWire.NULL){require(MemoryImportValues.nullable(base)||(nullAsEmpty&&data.equals(base,"",token))){"memory_import_raw_inconsistent"};return}
        require(base.type==MemoryImportWire.STRING){"memory_import_raw_inconsistent"}
        val bytes=MemoryImportValues.base64(data.open(raw),raw.length)
        InputStreamReader(bytes,Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)).use { expected->
            data.reader(base).use { actual->
                while(true){token.throwIfCancelled();val a=expected.read();val b=actual.read();require(a==b){"memory_import_raw_inconsistent"};if(a<0)break}
            }
        }
    }
}
