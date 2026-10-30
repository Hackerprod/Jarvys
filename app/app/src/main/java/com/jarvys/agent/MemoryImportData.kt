package com.jarvys.agent

import java.io.*
import java.nio.charset.CodingErrorAction

/** Fixed catalog vocabulary. Row payloads and opaque identities remain private byte ranges. */
internal object MemoryImportWire {
    const val HEADER=0;const val SESSIONS=1;const val OBSERVATIONS=2;const val PROMPTS=3
    const val RELATIONS=4;const val PROMPT_MARKERS=5;const val RAW=6;const val OBS_MARKERS=7;const val SESSION_MARKERS=8
    val names=listOf("header","sessions","observations","prompts","relations","prompt_tombstones","observation_fields","deleted_observations","deleted_sessions")
    val fields=listOf(
        listOf("version","exported_at"),
        listOf("id","project","ownership_mode","directory","started_at","ended_at","summary"),
        listOf("id","sync_id","session_id","type","title","content","tool_name","project","scope","topic_key","revision_count","duplicate_count","last_seen_at","review_after","superseded_by_sync_id","pinned","created_at","updated_at","deleted_at"),
        listOf("id","sync_id","session_id","content","project","created_at","source_inbox_id"),
        listOf("sync_id","source_id","target_id","relation","reason","evidence","confidence","judgment_status","marked_by_actor","marked_by_kind","marked_by_model","session_id","created_at","updated_at","superseded_at","superseded_by_relation_sync_id"),
        listOf("sync_id","session_id","project","source_inbox_id","deleted_at"),
        listOf("sync_id","title_bytes_base64","topic_key_bytes_base64","session_id"),
        listOf("id","project","scope","deleted_at"),listOf("id","project","deleted_at"))
    const val MISSING=0;const val NULL=1;const val STRING=2;const val NUMBER=3;const val TRUE=4;const val FALSE=5
    const val SLOTS=24
    const val RECORD_BYTES=4+8+SLOTS*(1+8+8)
    const val MAGIC=0x4d495031
}
internal data class MemoryImportCell(val type:Int,val offset:Long=0,val length:Long=0)
internal data class MemoryImportRecord(val domain:Int,val ordinal:Long,val cells:List<MemoryImportCell>) {
    fun cell(name:String):MemoryImportCell {
        val index=MemoryImportWire.fields[domain].indexOf(name)
        require(index>=0);return cells[index]
    }
}
internal class MemoryImportData(
    val raw:File,val cellsFile:File,val catalog:File,
    val sha256:String,val sizeBytes:Long,val counts:Map<String,Long>,val riskyFieldCount:Long,
    val hasExtension:Boolean,
    private val operationToken:CancellationToken,
    private val openOwned:(File)->InputStream,
) {
    fun open(cell:MemoryImportCell):InputStream {
        require(cell.offset>=0 && cell.length>=0)
        val input=openOwned(cellsFile)
        try {
            var left=cell.offset
            val skip=ByteArray(8192)
            while(left>0) { operationToken.throwIfCancelled();val n=input.read(skip,0,minOf(left,skip.size.toLong()).toInt());check(n>0){"memory_import_staging_changed"};left-=n }
        } catch(failure:Throwable) {try{input.close()}catch(close:Throwable){failure.addSuppressed(close)};throw failure}
        return object:FilterInputStream(input) {
            var remaining=cell.length
            override fun read():Int {operationToken.throwIfCancelled();if(remaining==0L)return -1;val n=super.read();check(n>=0){"memory_import_staging_changed"};remaining--;return n}
            override fun read(b:ByteArray,off:Int,len:Int):Int {operationToken.throwIfCancelled();if(len==0)return 0;if(remaining==0L)return -1
                val n=`in`.read(b,off,minOf(len.toLong(),remaining).toInt());check(n>0){"memory_import_staging_changed"};remaining-=n;return n}
        }
    }
    fun reader(cell:MemoryImportCell):Reader=InputStreamReader(open(cell),Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
    fun records(token:CancellationToken,domain:Int?=null,block:(MemoryImportRecord)->Unit) {
        DataInputStream(BufferedInputStream(openOwned(catalog),8192)).use { input ->
            check(input.readInt()==MemoryImportWire.MAGIC){"memory_import_catalog_invalid"}
            while(true) {
                token.throwIfCancelled()
                val first=input.read();if(first<0)break
                val kind=(first shl 24) or (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                check(kind in MemoryImportWire.fields.indices){"memory_import_catalog_invalid"}
                val ordinal=input.readLong();check(ordinal>=0)
                val values=ArrayList<MemoryImportCell>(MemoryImportWire.SLOTS)
                repeat(MemoryImportWire.SLOTS) { val type=input.readUnsignedByte();val offset=input.readLong();val length=input.readLong()
                    check(type in 0..5 && offset>=0 && length>=0){"memory_import_catalog_invalid"};values+=MemoryImportCell(type,offset,length) }
                if(domain==null || domain==kind)block(MemoryImportRecord(kind,ordinal,values))
            }
        }
    }
    fun equals(cell:MemoryImportCell,value:String,token:CancellationToken):Boolean {
        val bytes=value.toByteArray(Charsets.UTF_8)
        if(cell.type!=MemoryImportWire.STRING || cell.length!=bytes.size.toLong())return false
        open(cell).use { input -> for(byte in bytes){token.throwIfCancelled();if(input.read()!=byte.toInt().and(255))return false};return input.read()<0 }
    }
    fun equal(left:MemoryImportCell,right:MemoryImportCell,token:CancellationToken):Boolean {
        if(left.type!=right.type || left.length!=right.length)return false
        if(left.type !in setOf(MemoryImportWire.STRING,MemoryImportWire.NUMBER))return true
        open(left).use { a ->open(right).use { b ->val x=ByteArray(8192);val y=ByteArray(8192)
            var remaining=left.length
            while(remaining>0){token.throwIfCancelled();val count=minOf(remaining,8192L).toInt();readFully(a,x,count);readFully(b,y,count)
                for(i in 0 until count)if(x[i]!=y[i])return false;remaining-=count};return true } }
    }
    private fun readFully(input:InputStream,buffer:ByteArray,count:Int) {var done=0;while(done<count){operationToken.throwIfCancelled();val n=input.read(buffer,done,count-done);check(n>0){"memory_import_staging_changed"};done+=n}}
}
