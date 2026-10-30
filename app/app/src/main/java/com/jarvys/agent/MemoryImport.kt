package com.jarvys.agent

import java.io.InputStream
import java.util.Collections

/** Selected document bytes are data, never executable instructions or a runtime capability. */
data class MemoryImportInput(val stream:InputStream,val providerLabel:String,val displayName:String)
class MemoryImportOperationHandle internal constructor()
class MemoryImportProjectRef internal constructor(internal val owner:Any,internal val ordinal:Long)
data class MemoryImportProjectChoice(val ref:MemoryImportProjectRef,val display:String,val kind:String,val destination:String?,val editable:Boolean=true,val displayTruncated:Boolean=false)
data class MemoryImportProjectPage(val entries:List<MemoryImportProjectChoice>,val nextOffset:Long?)
data class MemoryImportPreviewPage(val text:String,val nextOffset:Long?,val totalBytes:Long)
data class MemoryImportCleanupResult(val cleanupState:String,val physicalBusy:Boolean)
class MemoryImportSource internal constructor(
    internal val owner:Any,internal val data:MemoryImportData,
    val operationHandle:MemoryImportOperationHandle,
    val providerLabel:String,val displayName:String,
) {
    val sha256:String get()=data.sha256
    val sizeBytes:Long get()=data.sizeBytes
    val riskyFieldCount:Long get()=data.riskyFieldCount
    val counts:Map<String,Long> = Collections.unmodifiableMap(LinkedHashMap(data.counts))
    val requiresPromptConsent:Boolean get()=(counts["prompts"]?:0L)>0
}
class PreparedMemoryImport internal constructor(
    internal val owner:Any,val source:MemoryImportSource,
    internal val plan:com.jarvys.agent.chatdb.MemoryImportPlan,
    internal val mappingVersion:Long,
    internal val offTicket:MemoryWriteGate.Ticket?,
) {
    val sha256:String get()=source.sha256
    val sizeBytes:Long get()=source.sizeBytes
    val operationHandle:MemoryImportOperationHandle get()=source.operationHandle
    val riskyFieldCount:Long get()=source.riskyFieldCount
    val impact:Map<String,Long> = Collections.unmodifiableMap(LinkedHashMap(plan.counts))
    val fingerprint:String get()=plan.fingerprint
}
data class MemoryImportReceipt(val status:String,val counts:Map<String,Long>,val sha256:String,
    val sizeBytes:Long,val cleanupState:String)
