package com.jarvys.agent

import java.io.*

/** All files are private operation-owned artifacts. Implementations preserve inode/seal/close ownership. */
internal interface MemoryImportArtifacts {
    fun create(name:String):File
    fun open(file:File):InputStream
    fun append(file:File):OutputStream
    fun reset(file:File):OutputStream
    fun verify()
}

/** Small fixed records, not an in-memory project catalog. Source labels never become destination authority. */
internal class MemoryImportMapping(private val data:MemoryImportData,private val artifacts:MemoryImportArtifacts,
    token:CancellationToken) {
    private val file=artifacts.create("mapping.bin")
    private var initialized=false
    var version:Long=0;private set
    private var groupCount=0L
    private var pageCount=0L
    private data class Entry(val kind:Int,val ordinal:Long,val domain:Int,val cell:MemoryImportCell,val destination:String?)
    private fun entries(token:CancellationToken,block:(Entry)->Unit) {
        DataInputStream(BufferedInputStream(artifacts.open(file),8192)).use { input->
            while(true){token.throwIfCancelled();val kind=input.read();if(kind<0)break
                check(kind in 1..3){"memory_import_mapping_invalid"}
                val ordinal=input.readLong();val domain=input.readInt();val cell=MemoryImportCell(input.readUnsignedByte(),input.readLong(),input.readLong())
                val hasDestination=input.readBoolean();val destination=if(hasDestination)input.readUTF() else null
                block(Entry(kind,ordinal,domain,cell,destination))
            }
        }
    }
    private fun append(entry:Entry) {
        DataOutputStream(BufferedOutputStream(artifacts.append(file),8192)).use { out->
            out.writeByte(entry.kind);out.writeLong(entry.ordinal);out.writeInt(entry.domain)
            out.writeByte(entry.cell.type);out.writeLong(entry.cell.offset);out.writeLong(entry.cell.length)
            out.writeBoolean(entry.destination!=null);entry.destination?.let(out::writeUTF)
        }
    }
    init {token.throwIfCancelled();artifacts.reset(file).use { };token.throwIfCancelled()}
    fun initialize(owners:List<String>,anchor:(MemoryImportRecord)->String?,token:CancellationToken) {
        check(!initialized){"memory_import_mapping_initialized"}
        data.records(token){row->if(row.domain in PROJECT_DOMAINS){
            val source=sourceCell(row,token)
            if(source==null){
                val retained=anchor(row)
                if(retained!=null){require(retained in owners){"memory_import_owner_denied"};append(Entry(3,row.ordinal,row.domain,MemoryImportValidation.identity(row),retained));pageCount++;return@records}
            }
            if(group(source,token)==null){
                val default=source?.let {cell->owners.singleOrNull {data.equals(cell,it,token)}}
                append(Entry(1,groupCount++,-1,source?:EMPTY,default));pageCount++
            }
        }}
        token.throwIfCancelled();initialized=true
    }
    /** Missing project may derive only from the explicitly referenced file session, never from Central. */
    fun sourceCell(row:MemoryImportRecord,token:CancellationToken):MemoryImportCell? {
        require(row.domain in PROJECT_DOMAINS)
        val own=row.cell("project")
        if(!MemoryImportValues.blank(data,own,token))return own
        if(row.domain in SESSION_REFERENCE_DOMAINS){
            val session=row.cell("session_id")
            if(!MemoryImportValues.blank(data,session,token)){
                val referenced=MemoryImportValidation.find(data,MemoryImportWire.SESSIONS,session,token)
                if(referenced!=null){val project=referenced.cell("project");if(!MemoryImportValues.blank(data,project,token))return project}
            }
        }
        return null
    }
    private fun group(cell:MemoryImportCell?,token:CancellationToken):Entry? {
        var found:Entry?=null
        entries(token){entry->if(entry.kind==1 && ((cell==null&&entry.cell.type==MemoryImportWire.MISSING)||(cell!=null&&data.equal(entry.cell,cell,token)))){check(found==null);found=entry}}
        return found
    }
    private fun selected(group:Entry,token:CancellationToken):String? {
        var value=group.destination
        entries(token){if(it.kind==2&&it.ordinal==group.ordinal)value=it.destination}
        return value
    }
    fun anchor(row:MemoryImportRecord,token:CancellationToken):String? {
        var value:String?=null
        entries(token){if(it.kind==3&&it.domain==row.domain&&it.ordinal==row.ordinal){check(value==null);value=it.destination}}
        return value
    }
    fun destination(row:MemoryImportRecord,token:CancellationToken):String {
        check(initialized);anchor(row,token)?.let{return it}
        val group=group(sourceCell(row,token),token)?:error("memory_import_mapping_changed")
        return selected(group,token)?:error("memory_import_project_unmapped")
    }
    fun page(owner:Any,offset:Long,limit:Int,token:CancellationToken):MemoryImportProjectPage {
        require(offset>=0&&limit in 1..64);check(initialized)
        val result=ArrayList<MemoryImportProjectChoice>(limit)
        var pageOrdinal=0L
        entries(token){entry->if(entry.kind==1||entry.kind==3){
            val index=pageOrdinal++
            if(index>=offset&&result.size<limit){
                val undeclared=entry.cell.type==MemoryImportWire.MISSING
                var truncated=false
                val label=if(undeclared)"(undeclared)" else data.reader(entry.cell).use {reader->
                    val chars=CharArray(257);var n=0
                    while(n<chars.size){token.throwIfCancelled();val count=reader.read(chars,n,chars.size-n);if(count<0)break;if(count>0)n+=count}
                    truncated=n>256;String(chars,0,minOf(n,256))
                }
                val anchored=entry.kind==3
                result+=MemoryImportProjectChoice(MemoryImportProjectRef(owner,if(anchored)-index-1 else entry.ordinal),label,
                    if(anchored)"anchor" else if(undeclared)"undeclared" else "project",
                    if(anchored)entry.destination else selected(entry,token),!anchored,truncated)
            }
        }}
        val next=if(offset+result.size<pageCount)offset+result.size else null
        return MemoryImportProjectPage(java.util.Collections.unmodifiableList(result),next)
    }
    fun projectCell(owner:Any,ref:MemoryImportProjectRef,token:CancellationToken):MemoryImportCell? {
        require(ref.owner===owner){"memory_import_project_ref_invalid"};check(initialized)
        var found:Entry?=null;var index=0L
        entries(token){entry->if(entry.kind==1||entry.kind==3){
            val pageOrdinal=index++
            if((ref.ordinal>=0&&entry.kind==1&&entry.ordinal==ref.ordinal)||(ref.ordinal<0&&entry.kind==3&&pageOrdinal == -ref.ordinal-1)){
                check(found==null);found=entry
            }
        }}
        return checkNotNull(found){"memory_import_project_ref_invalid"}.cell.takeUnless{it.type==MemoryImportWire.MISSING}
    }
    fun set(owner:Any,ref:MemoryImportProjectRef,destination:String,owners:List<String>,token:CancellationToken) {
        check(initialized);require(ref.owner===owner&&ref.ordinal in 0 until groupCount){"memory_import_project_ref_invalid"}
        require(destination in owners){"memory_import_owner_denied"}
        token.throwIfCancelled();append(Entry(2,ref.ordinal,-1,EMPTY,destination));version=Math.addExact(version,1)
    }
    fun requireComplete(owners:List<String>,token:CancellationToken) {
        check(initialized)
        entries(token){entry->if(entry.kind==1){
            val destination=selected(entry,token)?:error("memory_import_project_unmapped")
            require(destination in owners){"memory_import_owner_denied"}
            entries(token){other->if(other.kind==1&&other.ordinal<entry.ordinal)
                require(selected(other,token)!=destination){"memory_import_project_mapping_collision"}}
        }else if(entry.kind==3)require(entry.destination in owners){"memory_import_owner_denied"}}
    }
    companion object {
        private val EMPTY=MemoryImportCell(MemoryImportWire.MISSING)
        private val PROJECT_DOMAINS=setOf(MemoryImportWire.SESSIONS,MemoryImportWire.OBSERVATIONS,MemoryImportWire.PROMPTS,MemoryImportWire.PROMPT_MARKERS,MemoryImportWire.OBS_MARKERS,MemoryImportWire.SESSION_MARKERS)
        private val SESSION_REFERENCE_DOMAINS=setOf(MemoryImportWire.OBSERVATIONS,MemoryImportWire.PROMPTS,MemoryImportWire.PROMPT_MARKERS)
    }
}
