package com.jarvys.agent.chatdb

import com.jarvys.agent.*
import java.io.*
import java.util.Collections
import java.util.UUID

/** Operation-private prospective identities. Never a capability, session lease, or persisted import ledger. */
internal class MemoryImportPlan internal constructor(
    val data:MemoryImportData,val mapping:MemoryImportMapping,val artifacts:MemoryImportArtifacts,
    val database:JarvysChatDatabase,val namespace:MemoryNamespace,val generation:String?,val now:Long,
    val requireOriginalFile:()->Unit,private val identities:File,val decisions:File,
    val mappingVersion:Long,val fingerprint:String,counts:Map<String,Long>,
) {
    val counts:Map<String,Long> = Collections.unmodifiableMap(LinkedHashMap(counts))
    fun prospective(row:MemoryImportRecord,token:CancellationToken):String {
        DataInputStream(BufferedInputStream(artifacts.open(identities),8192)).use { input->
            while(true){token.throwIfCancelled();val first=input.read();if(first<0)break
                val domain=(first shl 24) or (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                val ordinal=input.readLong();val id=input.readUTF()
                if(domain==row.domain&&ordinal==row.ordinal)return id
            }
        }
        error("memory_import_plan_changed")
    }
    companion object {
        fun identities(data:MemoryImportData,artifacts:MemoryImportArtifacts,token:CancellationToken):File {
            val file=artifacts.create("plan.bin")
            DataOutputStream(BufferedOutputStream(artifacts.reset(file),8192)).use { output->
                data.records(token){row->if(row.domain !in setOf(MemoryImportWire.HEADER,MemoryImportWire.RAW)){
                    token.throwIfCancelled();output.writeInt(row.domain);output.writeLong(row.ordinal);output.writeUTF(UUID.randomUUID().toString())
                }}
            }
            return file
        }
    }
}
