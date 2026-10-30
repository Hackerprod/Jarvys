package com.jarvys.agent.chatdb

import com.jarvys.agent.*
import java.io.*

internal data class MemoryImportDecision(val domain:Int,val ordinal:Long,val action:Int,val project:String,
    val localId:String,val rowId:Long,val scope:String="project",val sourceId:String="",val targetId:String="",val removed:Long=0,val aliases:Long=0) {
    fun write(out:DataOutputStream){out.writeInt(domain);out.writeLong(ordinal);out.writeInt(action);out.writeUTF(project)
        out.writeUTF(localId);out.writeLong(rowId);out.writeUTF(scope);out.writeUTF(sourceId);out.writeUTF(targetId);out.writeLong(removed);out.writeLong(aliases)}
    companion object {
        const val SKIP=0;const val INSERT=1;const val UPDATE=2;const val DELETE=3;const val ADOPT=4;const val BLOCKED=5
        fun each(plan:MemoryImportPlan,token:CancellationToken,block:(MemoryImportDecision)->Unit){
            DataInputStream(BufferedInputStream(plan.artifacts.open(plan.decisions),8192)).use {input->
                while(true){token.throwIfCancelled();val first=input.read();if(first<0)break
                    val domain=(first shl 24)or(input.readUnsignedByte()shl 16)or(input.readUnsignedByte()shl 8)or input.readUnsignedByte()
                    block(MemoryImportDecision(domain,input.readLong(),input.readInt(),input.readUTF(),input.readUTF(),input.readLong(),input.readUTF(),input.readUTF(),input.readUTF(),input.readLong(),input.readLong()))
                }
            }
        }
        fun record(plan:MemoryImportPlan,decision:MemoryImportDecision,token:CancellationToken):MemoryImportRecord {
            var found:MemoryImportRecord?=null
            plan.data.records(token,decision.domain){if(it.ordinal==decision.ordinal){check(found==null);found=it}}
            return checkNotNull(found){"memory_import_plan_changed"}
        }
    }
}
