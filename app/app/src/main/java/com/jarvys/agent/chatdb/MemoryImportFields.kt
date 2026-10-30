package com.jarvys.agent.chatdb

import com.jarvys.agent.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** One connection-local TEMP table, created/dropped inside the same owned Room transaction. */
internal class MemoryImportFields(private val sql:ChatSqlDatabase,private val data:MemoryImportData,private val token:CancellationToken) {
    private var created=false
    fun create(){token.throwIfCancelled();sql.execSQL("CREATE TEMP TABLE jarvys_memory_import_fields(slot INTEGER PRIMARY KEY,value BLOB)");created=true}
    fun clear(){check(created);token.throwIfCancelled();sql.execSQL("DELETE FROM temp.jarvys_memory_import_fields")}
    fun drop(){check(created);token.throwIfCancelled();sql.execSQL("DROP TABLE temp.jarvys_memory_import_fields");created=false;token.throwIfCancelled()}
    fun put(slot:Int,cell:MemoryImportCell,nullAsEmpty:Boolean=false){
        if(MemoryImportValues.nullable(cell)){put(slot,if(nullAsEmpty)ByteArrayInputStream(byteArrayOf()) else null);return}
        require(cell.type==MemoryImportWire.STRING);put(slot,data.open(cell))
    }
    fun put(slot:Int,value:String?){put(slot,value?.toByteArray(Charsets.UTF_8)?.let(::ByteArrayInputStream))}
    fun put(slot:Int,input:InputStream?){
        require(slot in 0..63);check(created);token.throwIfCancelled()
        if(input==null){sql.execSQL("INSERT INTO temp.jarvys_memory_import_fields(slot,value) VALUES(?,NULL)",arrayOf(slot));return}
        input.use { source->
            sql.execSQL("INSERT INTO temp.jarvys_memory_import_fields(slot,value) VALUES(?,X'')",arrayOf(slot))
            val buffer=ByteArray(8192)
            while(true){token.throwIfCancelled();val n=source.read(buffer);if(n<0)break;if(n==0)continue
                // SQLite may allocate a growing native value. Only the JVM transfer buffers are bounded.
                sql.execSQL("UPDATE temp.jarvys_memory_import_fields SET value=CAST(value||? AS BLOB) WHERE slot=?",arrayOf(buffer.copyOf(n),slot))
            }
        }
        token.throwIfCancelled()
    }
    fun text(slot:Int):String {require(slot in 0..63);return "CAST((SELECT value FROM temp.jarvys_memory_import_fields WHERE slot=$slot) AS TEXT)"}
}

internal object MemoryImportObservationFields {
    fun raw(data:MemoryImportData,row:MemoryImportRecord,token:CancellationToken):MemoryImportRecord?=
        if(data.hasExtension)MemoryImportValidation.find(data,MemoryImportWire.RAW,MemoryImportValidation.identity(row),token) else null
    fun session(data:MemoryImportData,row:MemoryImportRecord,token:CancellationToken):MemoryImportCell = raw(data,row,token)?.cell("session_id")?:row.cell("session_id")
    fun topic(data:MemoryImportData,row:MemoryImportRecord,token:CancellationToken):ByteArray? {
        require(!data.hasExtension){"memory_import_raw_topic_stream_required"}
        val cell=row.cell("topic_key");if(MemoryImportValues.nullable(cell))return null
        val out=ByteArrayOutputStream(120);var emitted=false;var space=false
        data.reader(cell).use { reader->
            while(out.size()<120){token.throwIfCancelled();val first=reader.read();if(first<0)break
                val cp=if(Character.isHighSurrogate(first.toChar())){val second=reader.read();require(second>=0&&Character.isLowSurrogate(second.toChar()));Character.toCodePoint(first.toChar(),second.toChar())}else first
                if(MemoryObservationContent.isGoSpace(cp)){if(emitted)space=true;continue}
                if(space){out.write(45);space=false;if(out.size()==120)break}
                val bytes=String(Character.toChars(Character.toLowerCase(cp))).toByteArray(Charsets.UTF_8)
                out.write(bytes,0,minOf(bytes.size,120-out.size()));emitted=true
            }
        }
        return out.toByteArray().takeIf{it.isNotEmpty()}
    }
    fun decodedLength(data:MemoryImportData,cell:MemoryImportCell,token:CancellationToken):Long {
        require(cell.type==MemoryImportWire.STRING&&cell.length%4==0L)
        if(cell.length==0L)return 0L
        var last=-1;var prior=-1
        data.open(cell).use {input->val bytes=ByteArray(8192);while(true){token.throwIfCancelled();val n=input.read(bytes);if(n<0)break
            for(i in 0 until n){prior=last;last=bytes[i].toInt()and 255}}}
        return Math.subtractExact(Math.multiplyExact(cell.length/4L,3L),if(prior==61)2L else if(last==61)1L else 0L)
    }
}
